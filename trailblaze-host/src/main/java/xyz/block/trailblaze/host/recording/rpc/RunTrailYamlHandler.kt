package xyz.block.trailblaze.host.recording.rpc

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.host.recording.OnDeviceRpcDeviceScreenStream
import xyz.block.trailblaze.host.rpc.RunTrailYamlRequest
import xyz.block.trailblaze.host.rpc.RunTrailYamlResponse
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/**
 * Runs an arbitrary trail YAML against a connected device — the web Tool Palette's
 * "Run on Device" button. Mirrors the **exact** dispatch decisions
 * [xyz.block.trailblaze.ui.tabs.recording.dispatchYamlOnDevice] makes on the desktop, so
 * web parity is real instead of "close enough":
 *
 *  - **[OnDeviceRpcDeviceScreenStream] (Android)** → [OnDeviceRpcDeviceScreenStream.dispatchYaml],
 *    a direct on-device RPC that bypasses session bookkeeping. Returns when the on-device runner
 *    accepts the request; the next frame poll picks up the result. Same fast-path the desktop's
 *    Replay button uses to avoid the ~2s session-bookkeeping latency.
 *
 *  - **[PlaywrightDeviceScreenStream] (Web)** → [TrailblazeDeviceManager.runYaml] with
 *    `sendSessionEndLog = false` + `getOrCreateSessionResolution`. Critical: without
 *    `keepBrowserAlive = !sendSessionEndLog`, `runPlaywrightNativeYaml` builds a fresh
 *    `BasePlaywrightNativeTest`, launches a NEW headed browser, and runs the YAML *there*
 *    while the user's connected browser stays empty. This was the first version's bug —
 *    "Run on Device" succeeded server-side but did nothing visible to the user.
 *
 *  - **Anything else (iOS, …)** → `runYaml` with a fresh session.
 */
class RunTrailYamlHandler(
  private val deviceManager: TrailblazeDeviceManager,
  private val sessionManager: HostDeviceSessionManager,
) : RpcHandler<RunTrailYamlRequest, RunTrailYamlResponse> {

  override suspend fun handle(
    request: RunTrailYamlRequest,
  ): RpcResult<RunTrailYamlResponse> {
    val deviceId = request.trailblazeDeviceId
    val stream = sessionManager.get(deviceId)
      ?: return RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Device not connected: ${deviceId.toFullyQualifiedDeviceId()}. " +
          "Call ConnectToDeviceRequest first.",
      )

    return try {
      when (stream) {
        is OnDeviceRpcDeviceScreenStream -> {
          stream.dispatchYaml(request.yaml)
          RpcResult.Success(RunTrailYamlResponse(success = true))
        }

        is PlaywrightDeviceScreenStream -> {
          // Bundle web replays into one logical session via `getOrCreateSessionResolution`
          // (same pattern as MCP `blaze()`): first replay creates a session, subsequent
          // ones re-use it. Combined with `sendSessionEndLog = false`, this is what
          // keeps `runPlaywrightNativeYaml` using the cached BasePlaywrightNativeTest
          // adopted at connect time — i.e., your connected browser, not a fresh one.
          val resolution = deviceManager.getOrCreateSessionResolution(
            trailblazeDeviceId = deviceId,
            sessionIdPrefix = "recording",
          )
          val completion = CompletableDeferred<TrailExecutionResult>()
          deviceManager.runYaml(
            yamlToRun = request.yaml,
            trailblazeDeviceId = deviceId,
            sendSessionStartLog = resolution.isNewSession,
            sendSessionEndLog = false,
            existingSessionId = resolution.sessionId,
            referrer = TrailblazeReferrer.RECORDING_TAB_REPLAY,
            onComplete = { completion.complete(it) },
          )
          awaitCompletionWithTimeout(completion)
        }

        else -> {
          val completion = CompletableDeferred<TrailExecutionResult>()
          deviceManager.runYaml(
            yamlToRun = request.yaml,
            trailblazeDeviceId = deviceId,
            sendSessionStartLog = true,
            sendSessionEndLog = true,
            existingSessionId = null,
            referrer = TrailblazeReferrer.RECORDING_TAB_REPLAY,
            onComplete = { completion.complete(it) },
          )
          awaitCompletionWithTimeout(completion)
        }
      }
    } catch (e: Exception) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Trail dispatch failed: ${e.message ?: e::class.simpleName}",
      )
    }
  }

  /**
   * Awaits [completion] for at most [TRAIL_RUN_TIMEOUT_MS]. Without a timeout the HTTP RPC
   * blocks forever if the runner never invokes `onComplete` (e.g., a stalled device
   * connection, an iOS XCUITest socket flap, a Playwright page that never settles).
   * Returning a clear timeout failure beats hanging the browser tab indefinitely —
   * the trail may still complete server-side, but the user gets a response and can
   * decide whether to retry. Five minutes is generous for any reasonable single-tool
   * dispatch and lets longer multi-step Run-All trails finish.
   */
  private suspend fun awaitCompletionWithTimeout(
    completion: CompletableDeferred<TrailExecutionResult>,
  ): RpcResult<RunTrailYamlResponse> {
    val result = withTimeoutOrNull(TRAIL_RUN_TIMEOUT_MS) { completion.await() }
    return result?.toRpcResult() ?: RpcResult.Failure(
      errorType = RpcResult.ErrorType.HTTP_ERROR,
      message = "Trail run did not complete within ${TRAIL_RUN_TIMEOUT_MS / 1000}s. " +
        "The trail may still be running on the daemon; check the daemon log.",
    )
  }

  private fun TrailExecutionResult.toRpcResult(): RpcResult<RunTrailYamlResponse> = when (this) {
    is TrailExecutionResult.Success -> RpcResult.Success(RunTrailYamlResponse(success = true))
    is TrailExecutionResult.Failed -> RpcResult.Failure(
      errorType = RpcResult.ErrorType.HTTP_ERROR,
      message = "Trail run failed: $errorMessage",
    )
    is TrailExecutionResult.Cancelled -> RpcResult.Failure(
      errorType = RpcResult.ErrorType.HTTP_ERROR,
      message = "Trail run was cancelled.",
    )
  }

  companion object {
    /** 5 minutes. Generous enough for any reasonable single-tool dispatch and long Run-All trails. */
    private const val TRAIL_RUN_TIMEOUT_MS: Long = 5 * 60 * 1000L
  }
}
