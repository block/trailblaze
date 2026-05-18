package xyz.block.trailblaze.host.recording.rpc

import xyz.block.trailblaze.host.rpc.NavigateWebUrlRequest
import xyz.block.trailblaze.host.rpc.NavigateWebUrlResponse
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.recording.WebDeviceScreenStream

class NavigateWebUrlHandler(
  private val sessionManager: HostDeviceSessionManager,
) : RpcHandler<NavigateWebUrlRequest, NavigateWebUrlResponse> {

  override suspend fun handle(
    request: NavigateWebUrlRequest,
  ): RpcResult<NavigateWebUrlResponse> {
    val stream = sessionManager.get(request.trailblazeDeviceId)
      ?: return RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Device not connected: ${request.trailblazeDeviceId.toFullyQualifiedDeviceId()}",
      )
    val webStream = stream as? WebDeviceScreenStream
      ?: return RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Device is not a web device — navigate is only supported on Playwright streams.",
      )
    return try {
      webStream.navigate(request.url)
      RpcResult.Success(NavigateWebUrlResponse(success = true))
    } catch (e: Exception) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Navigation failed: ${e.message ?: e::class.simpleName}",
      )
    }
  }
}
