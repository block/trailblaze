package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEqualTo
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import java.net.ServerSocket
import kotlin.test.AfterTest
import kotlin.test.Test
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.server.endpoints.CliStatusEndpoint
import xyz.block.trailblaze.logs.server.endpoints.CliStatusResponse

/**
 * End-to-end pin for the busy-daemon signal on the wire: the `activeRuns` count and the
 * `activeRunSummaries` lines a daemon stamps onto `/cli/status` must survive the HTTP +
 * kotlinx.serialization round-trip so the CLI's version-mismatch restart guard
 * ([checkAndRestartStaleDaemon]) and the dev jar-cache helper can read them.
 *
 * [CliRunManagerTest] pins the server-side counting; this pins the transport — a serializer
 * regression (e.g. dropping the field, or `encodeDefaults=false` hiding a non-zero value)
 * would pass the unit test but break every consumer, and only a real read through
 * [DaemonClient] catches it.
 */
class DaemonClientStatusActiveRunsTest {

  private val summaries = listOf(
    "trails/checkout/smoke.trail.yaml — running for 12s, session abc — tapping Charge",
    "trails/login/login.trail.yaml — pending for 1s",
  )
  private val port: Int = ServerSocket(0).use { it.localPort }

  private val server = embeddedServer(CIO, port = port) {
    install(ContentNegotiation) { json(TrailblazeJsonInstance) }
    routing {
      CliStatusEndpoint.register(this) {
        CliStatusResponse(
          running = true,
          port = port,
          connectedDevices = 1,
          uptimeSeconds = 42,
          activeRuns = summaries.size,
          activeRunSummaries = summaries,
        )
      }
    }
  }.also {
    it.start(wait = false)
    val deadline = System.currentTimeMillis() + 5_000
    while (System.currentTimeMillis() < deadline) {
      try {
        java.net.Socket("localhost", port).close()
        break
      } catch (_: Exception) {
        Thread.sleep(25)
      }
    }
  }

  private val client = DaemonClient(port = port)

  @AfterTest
  fun tearDown() {
    client.close()
    server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
  }

  @Test
  fun `active run count and summaries survive the cli status round-trip`() {
    val status = client.getStatusBlocking() ?: error("daemon status was null")
    assertThat(status.activeRuns).isEqualTo(2)
    assertThat(status.activeRunSummaries).containsExactly(*summaries.toTypedArray())
  }
}
