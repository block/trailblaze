package xyz.block.trailblaze.mcp.android.ondevice.rpc

import kotlinx.serialization.Serializable

/**
 * Host -> on-device RPC sent immediately before the host tears down its persistent device
 * connection. The on-device server drops cached UiAutomation bookkeeping (and any other
 * per-session handles keyed to the about-to-be-released session) so the next host that
 * connects to the same `am instrument` process doesn't inherit a half-torn-down handle.
 *
 * Compatibility: when the host is newer than the deployed test APK, the request hits the
 * catch-all `/rpc/{...}` route and returns HTTP 404. The host MUST treat 404 (and any other
 * error) as a successful no-op and continue teardown — see [DrainSessionResponse] for the
 * happy-path shape. When the host is older than the APK, the handler is just unused.
 *
 * @param reason Operator-visible string explaining why the host is dropping the connection.
 *   Logged on-device so post-mortems can correlate "host called drain because X" with the
 *   on-device side of the same event. No semantic effect on the handler.
 */
@Serializable
data class DrainSessionRequest(
  val reason: String = "host_release",
) : RpcRequest<DrainSessionResponse> {
  override val requestTimeoutMs: Long get() = DRAIN_TIMEOUT_MS

  companion object {
    /**
     * Drain is a fire-and-cleanup-locally call — if the on-device server is already wedged
     * (the very condition we're trying to fix), the host must not block forever. Short
     * enough that a healthy device drains comfortably (typical drain is <100ms) and a
     * wedged device fails fast so teardown can proceed.
     */
    const val DRAIN_TIMEOUT_MS: Long = 2_000L
  }
}

/**
 * On-device acknowledgement that the drain ran. Booleans report which clear paths the
 * handler exercised so a host operator reading the response can tell whether the
 * underlying private-API reflection (`Instrumentation.disconnectUiAutomation()` /
 * `mUiAutomation` field) is still working on the target Android version.
 *
 * @param uiAutomationCleared True when the cached `UiAutomation` handle on
 *   `Instrumentation` was reset (either via `disconnectUiAutomation()` or via the
 *   `mUiAutomation` field fallback). False when both reflection paths failed — the
 *   handler still returns Success because there is no recovery the host can do; an
 *   on-device error counter would just have to log it anyway.
 */
@Serializable
data class DrainSessionResponse(
  val uiAutomationCleared: Boolean,
)
