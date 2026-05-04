@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.OnDeviceRpcTimeouts
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.progress.ProgressSessionManager
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * JVM-only tests for [RunYamlRequestHandler] covering the sync-completion, fire-and-forget,
 * and handler-timeout paths introduced when the fire-and-forget + host-poll contract was
 * replaced by sync-by-default RPCs.
 *
 * The handler's real Android dependency (`TrailblazeAccessibilityService`) is swapped via
 * the `waitForSettled` constructor seam for a no-op. Everything else — session lifecycle,
 * progress events, job cancellation — runs as it does in production.
 */
class RunYamlRequestHandlerTest {

  private val testDeviceId = TrailblazeDeviceId(
    instanceId = "test-device",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
  )

  private val testRequest = RunYamlRequest(
    testName = "test",
    yaml = "",
    trailFilePath = null,
    targetAppName = null,
    useRecordedSteps = false,
    trailblazeDeviceId = testDeviceId,
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

  /**
   * Happy path: sync dispatch with a callback that returns cleanly → response carries
   * `success = true` and no errorMessage.
   */
  @Test
  fun `sync success returns response with success=true`() = runTest {
    val handler = createHandler(
      runTrailblazeYaml = { _, session, _ -> session },
    )

    val result = handler.handle(testRequest.copy(awaitCompletion = true))

    assertTrue(result is RpcResult.Success, "Expected RpcResult.Success, got $result")
    assertEquals(true, result.data.success)
    assertNull(result.data.errorMessage)
  }

  /**
   * Bidirectional memory sync — the handler creates one [AgentMemory] per request,
   * populates it from `request.memorySnapshot` BEFORE the callback runs (so on-device
   * tools can read host-written keys), passes it through to the callback, and then puts
   * the post-execution map into `response.memorySnapshot`. The callback in this test
   * acts like an on-device tool that both reads a host-written key AND writes its own.
   */
  @Test
  fun `request memorySnapshot is forwarded to callback and post-execution memory comes back in response`() = runTest {
    val seen = mutableMapOf<String, String>()
    val handler = createHandler(
      runTrailblazeYaml = { _, session, agentMemory ->
        // Capture what the on-device side sees as the host's memory at dispatch time.
        seen.putAll(agentMemory.variables)
        // Simulate an on-device tool writing back to memory.
        agentMemory.remember("device_wrote", "from-device")
        session
      },
    )

    val result = handler.handle(
      testRequest.copy(
        awaitCompletion = true,
        memorySnapshot = mapOf("host_wrote" to "from-host"),
      ),
    )

    assertTrue(result is RpcResult.Success, "Expected RpcResult.Success, got $result")
    // Inbound: callback saw the host's snapshot.
    assertEquals("from-host", seen["host_wrote"])
    // Outbound: response carries both keys (host-seeded + device-written).
    val responseSnapshot = result.data.memorySnapshot
    assertEquals("from-host", responseSnapshot["host_wrote"])
    assertEquals("from-device", responseSnapshot["device_wrote"])
  }

  /**
   * Sync dispatch where the callback throws a non-cancellation exception → response carries
   * `success = false` with the exception's message in `errorMessage`. Pins the contract
   * that the host client reads off the response.
   */
  @Test
  fun `sync failure surfaces errorMessage from the thrown exception`() = runTest {
    val handler = createHandler(
      runTrailblazeYaml = { _, _, _ -> throw RuntimeException("widget not found") },
    )

    val result = handler.handle(testRequest.copy(awaitCompletion = true))

    assertTrue(result is RpcResult.Success, "Expected RpcResult.Success, got $result")
    assertEquals(false, result.data.success)
    assertEquals("widget not found", result.data.errorMessage)
  }

  /**
   * The key new-behavior test: when the launched job runs past [HANDLER_AWAIT_CAP_MS], the
   * handler must (a) return a structured timeout response with `success = false`, (b) cancel
   * the background job, and (c) run the cleanup coroutine that emits an
   * [ExecutionCompleted] progress event and ends the session with `Cancelled` status.
   *
   * Without the cleanup coroutine (the earlier form), the cancelled job's own catch branch
   * short-circuits session-end + progress-event emission on `CancellationException`, which
   * would have left `ProgressSessionManager` stuck in RUNNING forever.
   */
  @Test
  fun `awaitCompletion timeout cancels the job and emits terminal progress + session end`() =
    runTest {
      val progressManager = ProgressSessionManager()
      val hungCallbackReleased = CompletableDeferred<Unit>()
      val handler = createHandler(
        runTrailblazeYaml = { _, _, _ ->
          // Hang in a cancellable way so the handler-side timeout is what ends it.
          try {
            awaitCancellation()
          } finally {
            hungCallbackReleased.complete(Unit)
          }
        },
        progressManager = progressManager,
      )

      val dispatch = async { handler.handle(testRequest.copy(awaitCompletion = true)) }

      // Walk virtual time past the handler cap + enough for the background cleanup to finish.
      advanceTimeBy(OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS + 1_000)
      advanceUntilIdle()

      val result = dispatch.await()
      assertTrue(result is RpcResult.Success, "Expected RpcResult.Success, got $result")
      assertEquals(false, result.data.success)
      val errorMessage = result.data.errorMessage
      assertNotNull(errorMessage)
      assertTrue(
        errorMessage.contains("timed out"),
        "Expected errorMessage to mention timeout, got: $errorMessage",
      )

      // The cancelled callback's finally{} must have run — proves the job was actually
      // cancelled rather than leaking.
      assertTrue(hungCallbackReleased.isCompleted, "Cancelled job never unwound")

      // Cleanup coroutine must have emitted an ExecutionCompleted with success=false.
      val events = progressManager.getEventsForSession(result.data.sessionId)
      val terminal = events.filterIsInstance<TrailblazeProgressEvent.ExecutionCompleted>()
        .singleOrNull()
      assertNotNull(terminal, "Expected exactly one ExecutionCompleted event, got $events")
      assertFalse(terminal.success, "Terminal event should mark the run as failed")
      assertTrue(
        terminal.errorMessage?.contains("exceeded") == true,
        "Expected timeout error in terminal event, got: ${terminal.errorMessage}",
      )
    }

  /**
   * Fire-and-forget dispatch (`awaitCompletion = false`): handler must return immediately
   * with `success = null` (the fire-and-forget sentinel) and an empty `memorySnapshot`
   * (memory sync requires a round-trip; fire-and-forget has no completion event to attach
   * post-execution memory to). The background job keeps running on its own.
   */
  @Test
  fun `fire-and-forget dispatch returns immediately with null success and empty memory`() = runTest {
    val callbackStarted = CompletableDeferred<Unit>()
    val handler = createHandler(
      runTrailblazeYaml = { _, _, _ ->
        callbackStarted.complete(Unit)
        awaitCancellation()
      },
    )

    val result = handler.handle(testRequest.copy(awaitCompletion = false))

    assertTrue(result is RpcResult.Success, "Expected RpcResult.Success, got $result")
    assertNull(result.data.success)
    assertNull(result.data.errorMessage)
    assertTrue(result.data.memorySnapshot.isEmpty(), "Fire-and-forget must not carry memory")

    // Let the background job tick forward; confirm it did start (it would run indefinitely
    // in production until cancelled — the test scope will cancel it at the end of runTest).
    advanceUntilIdle()
    assertTrue(callbackStarted.isCompleted, "Background job never started")
  }

  /**
   * Wire-contract enforcement: combining `awaitCompletion = false` with a non-empty
   * `memorySnapshot` is structurally invalid — memory sync requires a round-trip and
   * fire-and-forget has no completion event. The init-block `require` rejects this at
   * construction so the contract is impossible to violate from any caller.
   */
  @Test
  fun `RunYamlRequest rejects fire-and-forget plus non-empty memory at construction`() {
    val ex = kotlin.runCatching {
      testRequest.copy(awaitCompletion = false, memorySnapshot = mapOf("k" to "v"))
    }.exceptionOrNull()
    assertNotNull(ex, "Expected IllegalArgumentException, got no throw")
    assertTrue(
      ex is IllegalArgumentException,
      "Expected IllegalArgumentException, got ${ex::class.simpleName}",
    )
  }

  /**
   * Wire-contract enforcement on the response side: a fire-and-forget response
   * (`success = null`) must not carry a memorySnapshot. Mirrors the request-side require
   * so neither end can produce a wire payload that violates the round-trip contract.
   */
  @Test
  fun `RunYamlResponse rejects fire-and-forget plus non-empty memory at construction`() {
    val ex = kotlin.runCatching {
      xyz.block.trailblaze.llm.RunYamlResponse(
        sessionId = xyz.block.trailblaze.logs.model.SessionId("s"),
        success = null,
        memorySnapshot = mapOf("k" to "v"),
      )
    }.exceptionOrNull()
    assertNotNull(ex, "Expected IllegalArgumentException, got no throw")
    assertTrue(
      ex is IllegalArgumentException,
      "Expected IllegalArgumentException, got ${ex::class.simpleName}",
    )
  }

  /**
   * The init-block requires are load-bearing at the JSON wire boundary, not just for
   * Kotlin construction. kotlinx.serialization's generated decoder must invoke the init
   * block after populating fields so a malformed external payload throws at parse time.
   * This test exercises that path directly so a future serialization-config change that
   * bypasses init blocks (e.g. switching to a custom serializer) trips immediately.
   */
  @Test
  fun `RunYamlRequest deserialization rejects fire-and-forget plus non-empty memory`() {
    val malformedRequestJson = """
      {
        "testName": "x",
        "yaml": "",
        "trailFilePath": null,
        "targetAppName": null,
        "useRecordedSteps": false,
        "trailblazeDeviceId": {"instanceId":"d","trailblazeDevicePlatform":"ANDROID"},
        "trailblazeLlmModel": {
          "trailblazeLlmProvider": {"id":"p","display":"P"},
          "modelId": "m",
          "inputCostPerOneMillionTokens": 0.0,
          "outputCostPerOneMillionTokens": 0.0,
          "contextLength": 1000,
          "maxOutputTokens": 1000,
          "capabilityIds": []
        },
        "config": {},
        "referrer": {"id":"r","display":"R"},
        "awaitCompletion": false,
        "memorySnapshot": {"k":"v"}
      }
    """.trimIndent()
    val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    val ex = kotlin.runCatching {
      parser.decodeFromString(xyz.block.trailblaze.llm.RunYamlRequest.serializer(), malformedRequestJson)
    }.exceptionOrNull()
    assertNotNull(ex, "Expected deserialization to throw, got no throw")
    assertTrue(
      ex.findIllegalArgumentInChain() != null,
      "Expected IllegalArgumentException somewhere in the cause chain, got " +
        "${ex::class.simpleName}: ${ex.message}",
    )
  }

  @Test
  fun `RunYamlResponse deserialization rejects fire-and-forget plus non-empty memory`() {
    val malformedResponseJson =
      """{"sessionId":"s","success":null,"memorySnapshot":{"k":"v"}}"""
    val parser = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; isLenient = true }
    val ex = kotlin.runCatching {
      parser.decodeFromString(xyz.block.trailblaze.llm.RunYamlResponse.serializer(), malformedResponseJson)
    }.exceptionOrNull()
    assertNotNull(ex, "Expected deserialization to throw, got no throw")
    assertTrue(
      ex.findIllegalArgumentInChain() != null,
      "Expected IllegalArgumentException somewhere in the cause chain, got " +
        "${ex::class.simpleName}: ${ex.message}",
    )
  }

  /**
   * kotlinx.serialization may wrap the init-block `IllegalArgumentException` in a
   * `SerializationException`. Walk the cause chain so the test pins the underlying
   * contract violation rather than the wrapping behavior.
   */
  private fun Throwable.findIllegalArgumentInChain(): IllegalArgumentException? {
    var cur: Throwable? = this
    while (cur != null) {
      if (cur is IllegalArgumentException) return cur
      cur = cur.cause
    }
    return null
  }

  /**
   * Pin the invocation count of `waitForSettled` — the handler must call it EXACTLY ONCE
   * per handled request (pre-dispatch only). The post-dispatch settle was dropped
   * intentionally in the parent PR of this test's companion comment. If a future change
   * reintroduces a second settle, this catches it immediately without waiting for a
   * real-device benchmark regression to surface.
   */
  @Test
  fun `waitForSettled fires exactly once per handled sync request`() = runTest {
    // AtomicInteger because the handler invokes `waitForSettled` from inside the
    // background-scope coroutine that runs the launched job. Under StandardTestDispatcher +
    // a single testScheduler this is serialized in practice, but using an atomic keeps the
    // test correct if the dispatcher or scheduling ever changes.
    val settleCount = java.util.concurrent.atomic.AtomicInteger(0)
    val handler = createHandler(
      runTrailblazeYaml = { _, session, _ -> session },
      waitForSettled = { settleCount.incrementAndGet() },
    )

    handler.handle(testRequest.copy(awaitCompletion = true))

    assertEquals(
      1,
      settleCount.get(),
      "Expected exactly one waitForSettled call (pre-dispatch). " +
        "A count of 2 means the post-dispatch settle was re-introduced; " +
        "a count of 0 means the pre-dispatch crash-prevention settle was lost.",
    )
  }

  // ── test infrastructure ──────────────────────────────────────────────────

  private fun TestScope.createHandler(
    runTrailblazeYaml: suspend (RunYamlRequest, TrailblazeSession, AgentMemory) -> TrailblazeSession,
    progressManager: ProgressSessionManager? = null,
    waitForSettled: suspend () -> Unit = { /* no-op */ },
  ): RunYamlRequestHandler {
    // StandardTestDispatcher lets the test control when the launched block runs, which is
    // what makes the virtual-time advanceTimeBy in the timeout test actually trigger the
    // withTimeoutOrNull inside handle().
    val loggingRule = TestLoggingRule()
    var currentJob: kotlinx.coroutines.Job? = null
    return RunYamlRequestHandler(
      backgroundScope = TestScope(StandardTestDispatcher(testScheduler)),
      getCurrentJob = { currentJob },
      setCurrentJob = { currentJob = it },
      loggingRule = loggingRule,
      runTrailblazeYaml = runTrailblazeYaml,
      trailblazeDeviceInfoProvider = { deviceId ->
        TrailblazeDeviceInfo(
          trailblazeDeviceId = deviceId,
          trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
          widthPixels = 1080,
          heightPixels = 1920,
        )
      },
      progressManager = progressManager,
      // Swap the Android `TrailblazeAccessibilityService.waitForSettled` call — loading that
      // class requires the Android framework which isn't on the JVM test classpath.
      waitForSettled = waitForSettled,
    )
  }

  /**
   * Minimal [TrailblazeLoggingRule] concrete subclass with `noLogging = true` so none of
   * the HTTP/disk log-emission paths fire during tests.
   */
  private class TestLoggingRule : TrailblazeLoggingRule(noLogging = true) {
    override val trailblazeDeviceInfoProvider: () -> TrailblazeDeviceInfo = {
      TrailblazeDeviceInfo(
        trailblazeDeviceId = TrailblazeDeviceId(
          instanceId = "test-device",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
        ),
        trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        widthPixels = 1080,
        heightPixels = 1920,
      )
    }
  }
}
