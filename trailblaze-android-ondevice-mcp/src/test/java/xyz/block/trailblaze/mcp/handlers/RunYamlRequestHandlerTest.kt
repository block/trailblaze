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
      runTrailblazeYaml = { _, session -> session },
    )

    val result = handler.handle(testRequest.copy(awaitCompletion = true))

    assertTrue(result is RpcResult.Success, "Expected RpcResult.Success, got $result")
    assertEquals(true, result.data.success)
    assertNull(result.data.errorMessage)
  }

  /**
   * Sync dispatch where the callback throws a non-cancellation exception → response carries
   * `success = false` with the exception's message in `errorMessage`. Pins the contract
   * that the host client reads off the response.
   */
  @Test
  fun `sync failure surfaces errorMessage from the thrown exception`() = runTest {
    val handler = createHandler(
      runTrailblazeYaml = { _, _ -> throw RuntimeException("widget not found") },
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
        runTrailblazeYaml = { _, _ ->
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
   * with `success = null` (the fire-and-forget sentinel), letting the caller fall back to
   * progress-event subscription. The background job keeps running on its own.
   */
  @Test
  fun `fire-and-forget dispatch returns immediately with null success`() = runTest {
    val callbackStarted = CompletableDeferred<Unit>()
    val handler = createHandler(
      runTrailblazeYaml = { _, _ ->
        callbackStarted.complete(Unit)
        awaitCancellation()
      },
    )

    val result = handler.handle(testRequest.copy(awaitCompletion = false))

    assertTrue(result is RpcResult.Success, "Expected RpcResult.Success, got $result")
    // Null success — the wire contract for async-kickoff callers that read via progress
    // events instead of the inline response.
    assertNull(result.data.success)
    assertNull(result.data.errorMessage)

    // Let the background job tick forward; confirm it did start (it would run indefinitely
    // in production until cancelled — the test scope will cancel it at the end of runTest).
    advanceUntilIdle()
    assertTrue(callbackStarted.isCompleted, "Background job never started")
  }

  // ── test infrastructure ──────────────────────────────────────────────────

  private fun TestScope.createHandler(
    runTrailblazeYaml: suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession,
    progressManager: ProgressSessionManager? = null,
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
      waitForSettled = { /* no-op */ },
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
