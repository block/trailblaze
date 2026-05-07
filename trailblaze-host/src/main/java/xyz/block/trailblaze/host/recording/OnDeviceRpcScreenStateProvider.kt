package xyz.block.trailblaze.host.recording

import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.recording.ScreenStateProvider

/**
 * [ScreenStateProvider] for Android via the on-device RPC server. Thin wrapper over
 * [OnDeviceRpcClient.rpcCall] — the on-device server is *already* the canonical screen-state
 * source for production tests, so this provider is mostly an interface adapter.
 *
 * `includeAnnotatedScreenshot = false` is locked because recording's preview just needs the
 * clean image; LLM-oriented annotation is a separate path. [requireAccessibilityService]
 * threads the same flag through `GetScreenStateRequest` so a UiAutomator fallback can't
 * silently take over when the user picked the accessibility driver.
 *
 * Failure handling: returns null on `RpcResult.Failure` so the caller's poll loop skips a
 * tick rather than tearing down. The underlying `OnDeviceRpcClient` already heals transient
 * adb-forward drops via its `IOException → diagnose-and-re-forward` path, so null here means
 * the heal didn't catch in time and the next call will likely succeed.
 */
class OnDeviceRpcScreenStateProvider(
  private val rpc: OnDeviceRpcClient,
  private val requireAccessibilityService: Boolean,
) : ScreenStateProvider {

  override suspend fun getScreenState(includeScreenshot: Boolean): GetScreenStateResponse? {
    val result = rpc.rpcCall(
      GetScreenStateRequest(
        includeScreenshot = includeScreenshot,
        includeAnnotatedScreenshot = false,
        requireAndroidAccessibilityService = requireAccessibilityService,
      ),
    )
    return when (result) {
      is RpcResult.Success -> result.data
      is RpcResult.Failure -> null
    }
  }
}
