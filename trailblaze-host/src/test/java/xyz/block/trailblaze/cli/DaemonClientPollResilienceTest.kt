package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.endpoints.CliEndpoints
import xyz.block.trailblaze.logs.server.endpoints.CliRunRequest
import xyz.block.trailblaze.model.TrailblazeConfig

/**
 * Integration tests for [DaemonClient.runAsync]'s poll-loop resilience:
 *
 * - 4xx HTTP responses on `/cli/run-status` are terminal (no retry/ping-reset)
 * - 5xx + exceptions retry, and after [DaemonClient.Companion.MAX_CONSECUTIVE_POLL_ERRORS]
 *   the client pings `/ping` to verify the daemon is still alive
 * - Ping-success resets the counter and polling continues
 * - Ping-failure surfaces a refined "(ping also failed)" error
 *
 * Mocks the daemon with an in-process Ktor CIO server on an ephemeral port so the
 * tests don't depend on the real desktop app being running.
 */
class DaemonClientPollResilienceTest {

  /** Knobs the test mock-daemon uses to script per-endpoint behavior. */
  private inner class MockDaemon {
    val runStatusResponder = AtomicInteger(0) // call counter, used by tests for staged responses
    @Volatile var runStatusHandler: (Int) -> Pair<HttpStatusCode, String> = { _ ->
      HttpStatusCode.OK to runStatusJson("RUNNING", null)
    }
    @Volatile var pingHandler: () -> HttpStatusCode = { HttpStatusCode.OK }

    @Volatile var runId: String = UUID.randomUUID().toString()

    val port: Int = ServerSocket(0).use { it.localPort }

    private val server = embeddedServer(CIO, port = port) {
      install(ContentNegotiation) { json(TrailblazeJsonInstance) }
      routing {
        get(CliEndpoints.PING) {
          val status = pingHandler()
          call.respondText("pong", ContentType.Text.Plain, status)
        }
        post(CliEndpoints.RUN_ASYNC) {
          call.respondText(
            """{"runId":"$runId"}""",
            ContentType.Application.Json,
            HttpStatusCode.Accepted,
          )
        }
        get(CliEndpoints.RUN_STATUS) {
          val n = runStatusResponder.incrementAndGet()
          val (status, body) = runStatusHandler(n)
          call.respondText(body, ContentType.Application.Json, status)
        }
      }
    }

    fun start() {
      server.start(wait = false)
      // Wait until /ping is actually reachable instead of a fixed sleep.
      val deadline = System.currentTimeMillis() + 5_000
      while (System.currentTimeMillis() < deadline) {
        try {
          java.net.Socket("localhost", port).close()
          return
        } catch (_: Exception) {
          Thread.sleep(25)
        }
      }
    }

    fun stop() {
      server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
    }
  }

  private val daemon = MockDaemon().also { it.start() }

  // 10ms poll interval keeps the 30-failure burst test fast (~300ms) instead
  // of ~30s with the production 1s interval. Doesn't change the logic under
  // test — the threshold + ping-fallback path runs identically.
  private val client = DaemonClient(port = daemon.port, pollIntervalMs = 10L)

  @AfterTest
  fun tearDown() {
    client.close()
    daemon.stop()
  }

  /**
   * 4xx is terminal — the new behavior must NOT retry + ping-reset on a 404,
   * because 404 means "unknown runId" (e.g. the daemon restarted and lost run
   * state) and is unrecoverable. Pre-fix behavior would wait the full 30-min
   * RUN_POLL_TIMEOUT_MS.
   */
  @Test
  fun runAsync_returns404BodyImmediatelyWithoutRetryingOrPinging() {
    daemon.runStatusHandler = { _ ->
      HttpStatusCode.NotFound to "Unknown runId: ghost"
    }
    var pingCalled = false
    daemon.pingHandler = {
      pingCalled = true
      HttpStatusCode.OK
    }

    val response = client.runSync(CliRunRequest(runYamlRequest = sampleRequest()))

    assertThat(response.success).isFalse()
    assertThat(response.error).isNotNull().contains("404")
    assertThat(response.error).isNotNull().contains("Unknown runId: ghost")
    // 4xx should bail immediately — counter never reaches the ping-fallback threshold.
    assertThat(pingCalled).isFalse()
    // And exactly one status call, not 30.
    assertThat(daemon.runStatusResponder.get()).isEqualTo(1)
  }

  /**
   * 5xx burst + ping healthy → counter resets, polling continues, run eventually
   * completes when the daemon recovers. Verifies the headline fix.
   */
  @Test
  fun runAsync_resetsCounterWhenPingHealthyAfterRunStatusBurst() {
    val failureBurst = DaemonClient.MAX_CONSECUTIVE_POLL_ERRORS + 2
    daemon.runStatusHandler = { n ->
      if (n <= failureBurst) {
        HttpStatusCode.ServiceUnavailable to "{}"
      } else {
        HttpStatusCode.OK to runStatusJson("COMPLETED", successResultJson())
      }
    }

    val response = client.runSync(CliRunRequest(runYamlRequest = sampleRequest()))

    assertThat(response.success).isTrue()
    assertThat(daemon.runStatusResponder.get())
      .isGreaterThanOrEqualTo(failureBurst + 1)
  }

  /**
   * 5xx burst + ping also failing → bail with the refined "(ping also failed)"
   * error so triage knows both endpoints are dead, not just status.
   */
  @Test
  fun runAsync_bailsWithRefinedErrorWhenPingAlsoFails() {
    daemon.runStatusHandler = { _ -> HttpStatusCode.ServiceUnavailable to "{}" }
    daemon.pingHandler = { HttpStatusCode.InternalServerError }

    val response = client.runSync(CliRunRequest(runYamlRequest = sampleRequest()))

    assertThat(response.success).isFalse()
    assertThat(response.error).isNotNull().contains("ping also failed")
  }

  /**
   * 408 Request Timeout and 429 Too Many Requests are 4xx-by-spec but
   * semantically transient — they must follow the retry+ping path, not bail
   * immediately. Otherwise a back-pressuring daemon would tear down the user's
   * trail on the first 429.
   */
  @Test
  fun runAsync_treatsTransient4xxCodesAsRetryable() {
    val attemptsBeforeSuccess = 3
    daemon.runStatusHandler = { n ->
      when {
        n == 1 -> HttpStatusCode.TooManyRequests to "{}"
        n == 2 -> HttpStatusCode.RequestTimeout to "{}"
        n < attemptsBeforeSuccess -> HttpStatusCode.TooManyRequests to "{}"
        else -> HttpStatusCode.OK to runStatusJson("COMPLETED", successResultJson())
      }
    }

    val response = client.runSync(CliRunRequest(runYamlRequest = sampleRequest()))

    assertThat(response.success).isTrue()
    // Confirms 408/429 didn't bail on the first hit like 404 would.
    assertThat(daemon.runStatusResponder.get()).isGreaterThanOrEqualTo(attemptsBeforeSuccess)
  }

  /**
   * Exception path: the real-world failure mode this whole PR exists for —
   * SocketTimeoutException / connection-reset from the run-status endpoint
   * during event-loop contention — has to take the retry+ping fallback path,
   * not the HTTP-status branch.
   *
   * We simulate it by stopping the mock daemon partway through the run: the
   * client's outstanding GET fails with a connection error, the ping fallback
   * fires, and (because the daemon is fully down) `/ping` fails too, so the
   * run bails with the "(ping also failed)" message.
   */
  @Test
  fun runAsync_retriesOnExceptionAndBailsWhenPingAlsoFails() {
    // Submit the run first so we have a runId in flight, then yank the daemon.
    daemon.runStatusHandler = { _ -> HttpStatusCode.ServiceUnavailable to "{}" }
    daemon.pingHandler = { HttpStatusCode.OK }

    val killerThread = Thread {
      // Give runAsync time to submit + start polling, then kill the daemon
      // so subsequent /cli/run-status and /ping calls throw at the socket level.
      Thread.sleep(50)
      daemon.stop()
    }
    killerThread.start()

    val response = client.runSync(CliRunRequest(runYamlRequest = sampleRequest()))
    killerThread.join()

    assertThat(response.success).isFalse()
    // Either path is acceptable proof the exception branch was exercised:
    // (a) ping was reachable mid-flight then died → "ping also failed"
    // (b) submit succeeded but every subsequent call threw → same outcome.
    assertThat(response.error).isNotNull().contains("ping also failed")
  }

  // --- helpers --------------------------------------------------------------

  private fun runStatusJson(state: String, resultJson: String?): String {
    val resultField = if (resultJson != null) ""","result":$resultJson""" else ""
    return """{"runId":"${daemon.runId}","state":"$state"$resultField}"""
  }

  private fun successResultJson(): String =
    """{"success":true,"sessionId":"sess-${daemon.runId}"}"""

  private fun sampleRequest(): RunYamlRequest = RunYamlRequest(
    testName = "test",
    yaml = "# noop",
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = false,
    trailblazeDeviceId = TrailblazeDeviceId(
      instanceId = "test-device",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    ),
    trailblazeLlmModel = TrailblazeLlmModel(
      trailblazeLlmProvider = TrailblazeLlmProvider(id = "test", display = "Test"),
      modelId = "test-model",
      inputCostPerOneMillionTokens = 0.0,
      outputCostPerOneMillionTokens = 0.0,
      contextLength = 1000,
      maxOutputTokens = 1000,
      capabilityIds = emptyList(),
    ),
    config = TrailblazeConfig(),
    referrer = TrailblazeReferrer(id = "test", display = "Test"),
  )
}
