package xyz.block.trailblaze.mcp.handlers

import xyz.block.trailblaze.InstrumentationUtil
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.Console

/**
 * Handles [DrainSessionRequest] — host calls this immediately before tearing down a persistent
 * device connection so the on-device server can drop cached UiAutomation bookkeeping before
 * `am instrument` is left with a half-torn-down handle.
 *
 * Why it matters: CLI MCP session-displacement events trigger `releasePersistentDeviceConnection`
 * on the host, which closes the host-side Maestro driver. Without a drain RPC, the on-device
 * server keeps running with a stale `Instrumentation.mUiAutomation` cache. When the next host
 * reconnects, every `UiAutomation.connectWithTimeout` throws `DeadObjectException` — observed
 * ~40 distinct handle hashes failing back-to-back in build 5463 before the emulator went
 * `device offline`.
 *
 * Cross-version compatibility: when the host is newer than the deployed APK, the request hits
 * the catch-all `/rpc/{...}` route and returns HTTP 404; the host treats that as a no-op and
 * continues teardown (see [xyz.block.trailblaze.mcp.android.ondevice.rpc.DrainSessionRequest]
 * KDoc). When the host is older than the APK, this handler is just unused.
 *
 * Reflection safety: [InstrumentationUtil.clearInstrumentationUiAutomationCache] tries
 * `Instrumentation.disconnectUiAutomation()` first (the platform's own teardown API) and falls
 * back to nulling `mUiAutomation` via reflection. Both paths returning false is possible on a
 * future Android release that renames the symbol — the handler still returns `Success` with
 * `uiAutomationCleared=false` so the host can see the breakage in its logs without itself
 * failing.
 */
class DrainSessionRequestHandler : RpcHandler<DrainSessionRequest, DrainSessionResponse> {

  override suspend fun handle(request: DrainSessionRequest): RpcResult<DrainSessionResponse> {
    Console.log("🔌 DrainSessionRequestHandler: draining (reason=${request.reason})")
    val cleared = try {
      InstrumentationUtil.clearInstrumentationUiAutomationCache()
    } catch (t: Throwable) {
      Console.log(
        "❌ DrainSessionRequestHandler: clearInstrumentationUiAutomationCache threw " +
          "${t::class.java.simpleName}: ${t.message}",
      )
      false
    }
    Console.log("🔌 DrainSessionRequestHandler: drain complete (uiAutomationCleared=$cleared)")
    return RpcResult.Success(DrainSessionResponse(uiAutomationCleared = cleared))
  }
}
