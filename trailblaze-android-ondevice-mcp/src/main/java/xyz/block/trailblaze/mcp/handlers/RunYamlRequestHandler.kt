package xyz.block.trailblaze.mcp.handlers

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.agent.TrailblazeProgressEvent
import xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.OnDeviceRpcTimeouts
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.progress.ProgressSessionManager
import xyz.block.trailblaze.rules.TrailblazeLoggingRule
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.toSnakeCaseIdentifier
import xyz.block.trailblaze.yaml.createTrailblazeYaml

/**
 * Default implementation of the UI-settle seam — used on real Android where the
 * accessibility service singleton is bound. Top-level (not inside the handler class) so
 * JVM tests that construct the handler with a test lambda never touch this reference, and
 * so the Android-loading of [TrailblazeAccessibilityService] is deferred until the first
 * real invocation.
 */
private suspend fun defaultWaitForSettled() {
  if (TrailblazeAccessibilityService.isServiceRunning()) {
    TrailblazeAccessibilityService.waitForSettled()
  }
}

/**
 * Handler for test execution requests.
 *
 * Routes requests based on [RunYamlRequest.agentImplementation]:
 * - [AgentImplementation.TRAILBLAZE_RUNNER]: Legacy YAML-based TrailblazeRunner
 * - [AgentImplementation.MULTI_AGENT_V3]: Mobile-Agent-v3 inspired implementation
 *
 * Manages session lifecycle and executes tests in the background.
 *
 * ## Progress Reporting
 *
 * When [progressManager] is provided, the handler emits progress events:
 * - [TrailblazeProgressEvent.ExecutionStarted] when execution begins
 * - [TrailblazeProgressEvent.ExecutionCompleted] when execution finishes
 *
 * Additional progress events (step progress, reflection, etc.) are emitted
 * by the agent implementations themselves via the [progressManager].
 */
class RunYamlRequestHandler(
  private val backgroundScope: CoroutineScope,
  private val getCurrentJob: () -> Job?,
  private val setCurrentJob: (Job?) -> Unit,
  /** Source of the session manager, logger, and [TrailblazeLoggingRule.failureScreenStateProvider]
   *  for capturing failure screenshots. The rule must have its provider wired before the first
   *  request — see the callers of [OnDeviceRpcServer]. */
  private val loggingRule: TrailblazeLoggingRule,
  /**
   * Callback to run via TrailblazeRunner (legacy YAML processing). The third argument is a
   * shared [AgentMemory] that the handler pre-populates from `request.memorySnapshot`; the
   * callback is responsible for threading it into the constructed agent so writes from
   * on-device tools land in the same instance the handler reads from afterward.
   */
  private val runTrailblazeYaml: suspend (RunYamlRequest, TrailblazeSession, AgentMemory) -> TrailblazeSession,
  /** Provider for device info including classifiers - used in session start logs.
   *  Accepts the device ID from the request so callers can initialize state before
   *  building the info (e.g., setting lateinit properties). */
  private val trailblazeDeviceInfoProvider: (TrailblazeDeviceId) -> TrailblazeDeviceInfo,
  /** Optional progress manager for emitting progress events to MCP clients */
  private val progressManager: ProgressSessionManager? = null,
  /** Callback to run via MultiAgentV3. When null (default for on-device), MULTI_AGENT_V3
   *  requests fall back to TRAILBLAZE_RUNNER. V3 is intended to run on the host, not on-device. */
  private val runMultiAgentV3Callback: (suspend (RunYamlRequest, TrailblazeSession) -> TrailblazeSession)? = null,
  /**
   * Seam for waiting on the UI to settle before tool dispatch. Invoked ONCE per handled
   * request, immediately before the tool(s) execute — not after. (Post-tool settling, when
   * the caller needs a stable screen, is the responsibility of the next request: another
   * `RunYamlRequest` pre-settles on entry, and `GetScreenStateRequest` settles on entry too.
   * See `RunYamlRequestHandler`'s body comment where the post-settle was dropped.)
   *
   * Default calls into the Android
   * [xyz.block.trailblaze.android.accessibility.TrailblazeAccessibilityService] singleton.
   * JVM unit tests override this with a no-op (or a counter) because loading that Android
   * class requires the Android framework.
   */
  private val waitForSettled: suspend () -> Unit = ::defaultWaitForSettled,
) : RpcHandler<RunYamlRequest, RunYamlResponse> {

  private val sessionManager = loggingRule.sessionManager

  /** Tracks the session associated with the currently running job, so we can end the correct
   *  session when a new request arrives and cancels the previous one. */
  @Volatile private var currentRunningSession: TrailblazeSession? = null

  /** Terminal outcome signalled from inside the launched block to the sync awaiter. */
  private sealed interface Outcome {
    object Success : Outcome

    object Cancelled : Outcome

    data class Failure(val message: String) : Outcome
  }


  override suspend fun handle(request: RunYamlRequest): RpcResult<RunYamlResponse> {
    // Create a single YAML parser instance for consistent usage throughout the handler
    val trailblazeYaml = createTrailblazeYaml()

    // Extract config values for session naming
    val trailConfig = try {
      trailblazeYaml.extractTrailConfig(request.yaml)
    } catch (e: Exception) {
      null
    }

    // Start session for this test execution
    val overrideId = request.config.overrideSessionId
    val session: TrailblazeSession = if (overrideId != null) {
      sessionManager.createSessionWithId(overrideId)
    } else {
      val configTitle = trailConfig?.title
      val configId = trailConfig?.id
      val newSessionId = run {
        configTitle ?: configId ?: request.testName
      }.let {
        // Ensure sessionId is a valid snake case identifier without special characters for a filename
        toSnakeCaseIdentifier(it)
      }
      sessionManager.startSession(newSessionId)
    }

    // Emit session start log with device info and classifiers.
    // This must happen BEFORE any tool logs to establish the session context.
    // The handler emits this for ALL agent implementations to ensure the report
    // generator can always find a Started log (required for test result generation).
    val shouldEmitStartLogHere = request.config.sendSessionStartLog

    if (shouldEmitStartLogHere) {
      val deviceInfo = trailblazeDeviceInfoProvider(request.trailblazeDeviceId).let { info ->
        // Use the driver type from the request (set by CLI --driver flag or trail config)
        // rather than the provider's default, which is always ANDROID_ONDEVICE_INSTRUMENTATION.
        val requestDriverType = request.driverType
        if (requestDriverType != null) info.copy(trailblazeDriverType = requestDriverType) else info
      }
      val hasRecordedSteps = try {
        trailblazeYaml.hasRecordedSteps(
          trailblazeYaml.decodeTrail(request.yaml)
        )
      } catch (e: Exception) {
        false
      }

      sessionManager.emitSessionStartLog(
        session = session,
        trailConfig = trailConfig,
        trailFilePath = null,
        hasRecordedSteps = hasRecordedSteps,
        testMethodName = request.testName,
        testClassName = "OnDeviceRpc",
        trailblazeDeviceInfo = deviceInfo,
        trailblazeDeviceId = request.trailblazeDeviceId,
        rawYaml = request.yaml,
      )
    }

    // Extract objective from YAML for progress reporting
    val objective = trailConfig?.title ?: trailConfig?.id ?: request.testName

    // Shared AgentMemory for this RPC. Pre-populated from the host's `request.memorySnapshot`
    // so on-device tools see the host's memory state at dispatch time (reads work). The
    // callback threads this same instance into the constructed agent, so writes performed
    // by on-device tools land here and are returned to the host in `response.memorySnapshot`.
    val agentMemory = AgentMemory().apply { variables.putAll(request.memorySnapshot) }

    return try {
      // Cancel any currently running job before starting a new session.
      // We use currentRunningSession to track which session belongs to the previous job,
      // so we end the correct (old) session — not the newly created one.
      getCurrentJob()?.let { job ->
        if (job.isActive) {
          val previousSession = currentRunningSession
          // Launch cancellation in background to avoid blocking the response
          backgroundScope.launch {
            job.cancelAndJoin()
          }
          // Send end log for the interrupted (previous) session with cancellation status
          if (previousSession != null) {
            sessionManager.endSession(
              session = previousSession,
              endedStatus = SessionStatus.Ended.Cancelled(
                durationMs = 0L,
                cancellationMessage = "Session cancelled after the user started a new session.",
              ),
            )
          }
        }
      }

      val startTimeMs = System.currentTimeMillis()

      // Outcome is signalled by the launched block on every terminal path (success, failure,
      // cancellation). When [RunYamlRequest.awaitCompletion] is true the handler awaits this
      // before replying, so the HTTP response carries the terminal state inline — no polling.
      val outcome = CompletableDeferred<Outcome>()

      // Launch the job in the background scope so it doesn't block the response.
      // Fire-and-forget callers observe progress via SubscribeToProgressHandler; sync callers
      // observe completion via [outcome] below.
      val job = backgroundScope.launch {
        try {
          // Emit execution started progress event
          progressManager?.onProgressEvent(
            TrailblazeProgressEvent.ExecutionStarted(
              timestamp = startTimeMs,
              sessionId = session.sessionId,
              deviceId = request.trailblazeDeviceId,
              objective = objective,
              agentImplementation = request.agentImplementation,
              hasTaskPlan = request.agentImplementation == AgentImplementation.MULTI_AGENT_V3,
            )
          )

          // When the host-side agent dispatches individual tools via RPC, each tool
          // arrives as a separate RunYamlRequest. Wait for the UI to settle before
          // execution so animations and accessibility events from the previous tool
          // have completed. Without this, rapid sequential tool dispatches (e.g.,
          // tap → inputText → tap → inputText) can overwhelm the accessibility
          // service and crash the on-device server.
          waitForSettled()

          // Route to appropriate agent implementation
          // For TRAILBLAZE_RUNNER, suppress the Started log in the callback since
          // the handler already emitted it above via sessionManager.emitSessionStartLog().
          val finalSession = when (request.agentImplementation) {
            AgentImplementation.TRAILBLAZE_RUNNER -> {
              Console.log("[RunYamlRequestHandler] Using TRAILBLAZE_RUNNER (legacy)")
              val requestWithStartLogSuppressed = request.copy(
                config = request.config.copy(sendSessionStartLog = false),
              )
              runTrailblazeYaml(requestWithStartLogSuppressed, session, agentMemory)
            }

            AgentImplementation.MULTI_AGENT_V3 -> {
              val callback = runMultiAgentV3Callback
              if (callback != null) {
                Console.log("[RunYamlRequestHandler] Using MULTI_AGENT_V3")
                callback.invoke(request, session)
              } else {
                Console.log("[RunYamlRequestHandler] MULTI_AGENT_V3 is not supported on-device; falling back to TRAILBLAZE_RUNNER")
                val requestWithStartLogSuppressed = request.copy(
                  config = request.config.copy(sendSessionStartLog = false),
                )
                runTrailblazeYaml(requestWithStartLogSuppressed, session, agentMemory)
              }
            }
          }

          // Post-settle intentionally omitted. The previous version called waitForSettled()
          // here for up to 5s to ensure a settled UI for any follow-up. That protection is
          // redundant:
          //  - The next per-tool RunYamlRequest already pre-settles on entry (see block
          //    before the tool dispatch above), so tool-to-tool handoff is covered.
          //  - CLI "tool-then-snapshot" flows capture the post-action screen via a separate
          //    GetScreenStateRequest, and GetScreenStateRequestHandler settles on entry too.
          //  - AccessibilityDeviceManager already settles internally after every action, so
          //    the tool itself doesn't return mid-action.
          // Dropping this settle halves the worst-case per-tool timeout penalty on noisy UIs
          // (e.g. text input with an active IME): we pay at most one 5s waitForSettled
          // timeout per tool-pair instead of two.
          val endTimeMs = System.currentTimeMillis()

          // Emit execution completed progress event
          progressManager?.onProgressEvent(
            TrailblazeProgressEvent.ExecutionCompleted(
              timestamp = endTimeMs,
              sessionId = session.sessionId,
              deviceId = request.trailblazeDeviceId,
              success = true,
              totalDurationMs = endTimeMs - startTimeMs,
              totalActions = 0, // Action count tracked by agent implementations
              errorMessage = null,
            )
          )

          if (request.config.sendSessionEndLog) {
            sessionManager.endSession(
              session = finalSession,
              isSuccess = true,
            )
          } else {
            // Keep the session open
          }

          outcome.complete(Outcome.Success)
        } catch (e: Exception) {
          // Propagate cancellation without capturing a failure screenshot —
          // cancelled sessions aren't failures. Signal the sync awaiter first so
          // it doesn't wait the full timeout budget for a cancelled job.
          if (e is CancellationException) {
            outcome.complete(Outcome.Cancelled)
            throw e
          }

          e.printStackTrace()

          // Capture failure screenshot before ending the session
          loggingRule.captureFailureScreenshot(session)

          val endTimeMs = System.currentTimeMillis()

          // Emit execution failed progress event
          progressManager?.onProgressEvent(
            TrailblazeProgressEvent.ExecutionCompleted(
              timestamp = endTimeMs,
              sessionId = session.sessionId,
              deviceId = request.trailblazeDeviceId,
              success = false,
              totalDurationMs = endTimeMs - startTimeMs,
              totalActions = 0,
              errorMessage = e.message ?: "Unknown error",
            )
          )

          if (request.config.sendSessionEndLog) {
            sessionManager.endSession(
              session = session,
              isSuccess = false,
              exception = e,
            )
          }

          outcome.complete(Outcome.Failure(e.message ?: e::class.simpleName ?: "Unknown error"))
        }
      }

      currentRunningSession = session
      setCurrentJob(job)

      if (request.awaitCompletion) {
        val resolved = withTimeoutOrNull(OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS) { outcome.await() }
        if (resolved == null) {
          // Hard cap so a hung tool can't tie up an HTTP socket indefinitely. Cancel the job
          // and launch a background cleanup: the launched block's catch path exits via
          // `CancellationException` and short-circuits session-end + progress-event emission,
          // so without this the ProgressSessionManager would stay in RUNNING and the session
          // would never get a terminal log. We do the end-session and ExecutionCompleted
          // emission here ourselves after joining the cancelled job, so every RunYamlRequest
          // lifecycle ends with a terminal state regardless of how it unwinds.
          val timeoutMessage =
            "RunYamlRequest awaitCompletion exceeded ${OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS}ms"
          val timeoutTimeMs = System.currentTimeMillis()
          job.cancel(CancellationException(timeoutMessage))
          backgroundScope.launch {
            job.join()
            progressManager?.onProgressEvent(
              TrailblazeProgressEvent.ExecutionCompleted(
                timestamp = timeoutTimeMs,
                sessionId = session.sessionId,
                deviceId = request.trailblazeDeviceId,
                success = false,
                totalDurationMs = timeoutTimeMs - startTimeMs,
                totalActions = 0,
                errorMessage = timeoutMessage,
              ),
            )
            if (request.config.sendSessionEndLog) {
              sessionManager.endSession(
                session = session,
                endedStatus = SessionStatus.Ended.Cancelled(
                  durationMs = timeoutTimeMs - startTimeMs,
                  cancellationMessage = timeoutMessage,
                ),
              )
            }
          }
          return RpcResult.Success(
            RunYamlResponse(
              sessionId = session.sessionId,
              success = false,
              errorMessage = "Execution timed out after ${OnDeviceRpcTimeouts.HANDLER_AWAIT_CAP_MS}ms",
              memorySnapshot = agentMemory.variables.toMap(),
            ),
          )
        }
        return RpcResult.Success(
          RunYamlResponse(
            sessionId = session.sessionId,
            success = resolved is Outcome.Success,
            errorMessage = (resolved as? Outcome.Failure)?.message
              ?: (resolved as? Outcome.Cancelled)?.let { "Execution cancelled" },
            memorySnapshot = agentMemory.variables.toMap(),
          ),
        )
      }

      // Fire-and-forget returns before any tool executes; memory sync requires a round-trip,
      // so the response carries no memorySnapshot. Async callers observe terminal state via
      // progress events. Enforced by RunYamlResponse's init-block require. Pass `emptyMap()`
      // explicitly (rather than relying on the default) for symmetry with the sync paths.
      RpcResult.Success(RunYamlResponse(sessionId = session.sessionId, memorySnapshot = emptyMap()))
    } catch (e: Exception) {
      sessionManager.endSession(
        session = session,
        isSuccess = false,
        exception = e,
      )
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "Failed to start YAML execution",
        details = e.stackTraceToString()
      )
    }
  }

}
