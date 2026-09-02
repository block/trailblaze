package xyz.block.trailblaze.mcp.android.ondevice.rpc

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode

/**
 * Driver seam for the on-device RPC server's screen-state capture.
 *
 * `GetScreenStateRequestHandler` owns the wire shaping (base64 vs binary, which screenshot
 * variants to render) and the failure mapping; HOW a frame is captured is the one part of that
 * handler that differs per driver, so it is injected. The accessibility runner captures through
 * the bound accessibility service (falling back to UiAutomator); the in-process ANDROID_TEST
 * driver captures through its own instrumentation-side hierarchy. Lives beside the request/response
 * types in trailblaze-models so a driver module can implement it without depending on the RPC
 * server module — and the server module without depending on any driver.
 *
 * Implementations signal failure by throwing; the handler maps any exception onto the
 * [RpcResult.Failure] the host's readiness polling and error surfaces already consume. A capture
 * path that is merely still coming up must throw [OnDeviceScreenStateNotReadyException] rather
 * than a generic exception, so the handler can keep that expected poll response off the
 * stack-trace-logging path.
 */
fun interface OnDeviceScreenStateCaptor {
  suspend fun capture(request: GetScreenStateRequest): OnDeviceCapturedScreenState
}

/**
 * "Ask me again shortly" — the capture path is not up yet (the accessibility captor's unbound
 * service, for instance). Distinct from a generic capture failure because the host polls
 * `GetScreenState` up to 120 times during `waitForReady`: the handler answers this with a bare
 * [RpcResult.Failure] carrying [message] and no stack-trace details, so a normal cold start
 * doesn't spam logcat or pile a full stack trace into the host's retained failure string.
 *
 * The [message] is the poll's only signal — it is what surfaces in the host's terminal
 * "Device not ready" error — so keep it stable and self-explanatory.
 */
class OnDeviceScreenStateNotReadyException(message: String) : Exception(message)

/**
 * One captured frame, exactly the trio the response builders read: the [screenState] itself, the
 * optional migration-mode secondary tree, and the device-epoch stamp taken when the
 * (screenshot, tree) pair was final — same clock as on-device session logs, so the host can
 * correlate the capture against other device-clock timestamps.
 *
 * No parameter defaults: every captor must state both side values explicitly. Defaulting them
 * would let a new driver's captor silently drop the device-clock stamp the host correlates on,
 * with no compile error to catch it.
 */
class OnDeviceCapturedScreenState(
  val screenState: ScreenState,
  val driverMigrationTreeNode: TrailblazeNode?,
  val capturedAtDeviceMs: Long?,
)
