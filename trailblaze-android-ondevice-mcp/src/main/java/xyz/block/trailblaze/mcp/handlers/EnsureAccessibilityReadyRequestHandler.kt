package xyz.block.trailblaze.mcp.handlers

import xyz.block.trailblaze.android.accessibility.OnDeviceAccessibilityServiceSetup
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.EnsureAccessibilityReadyRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.EnsureAccessibilityReadyResponse
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.util.Console

/**
 * RPC handler that blocks until the on-device accessibility service is connected.
 *
 * Uses the reliable in-process [TrailblazeAccessibilityService.isServiceRunning] check
 * rather than host-side `dumpsys accessibility` parsing which is unreliable on API 35+.
 */
class EnsureAccessibilityReadyRequestHandler :
  RpcHandler<EnsureAccessibilityReadyRequest, EnsureAccessibilityReadyResponse> {

  override suspend fun handle(
    request: EnsureAccessibilityReadyRequest
  ): RpcResult<EnsureAccessibilityReadyResponse> {
    return try {
      OnDeviceAccessibilityServiceSetup.ensureAccessibilityServiceReady(
        timeoutMs = request.timeoutMs,
      )
      Console.log("Accessibility service verified as running on-device via RPC.")
      RpcResult.Success(EnsureAccessibilityReadyResponse(ready = true))
    } catch (e: Exception) {
      Console.log("EnsureAccessibilityReady failed: ${e.message}")
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "Accessibility service failed to start: ${e.message}",
        details = e.stackTraceToString(),
      )
    }
  }
}
