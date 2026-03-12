package xyz.block.trailblaze.compose.driver.rpc

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.RpcHandler

/**
 * Mirrors [xyz.block.trailblaze.mcp.models.RpcErrorResponse] from the on-device RPC module.
 *
 * Duplicated here because `trailblaze-compose` does not depend on `trailblaze-android-ondevice-mcp`.
 * If the error response schema is ever moved to `trailblaze-models`, this can be replaced.
 */
@Serializable
data class RpcErrorResponse(
  val errorType: String,
  val message: String,
  val details: String? = null,
)

suspend inline fun <reified T : Any> ApplicationCall.respondRpcResult(result: RpcResult<T>) {
  when (result) {
    is RpcResult.Success -> respond(status = HttpStatusCode.OK, message = result.data)
    is RpcResult.Failure ->
      respond(
        status = HttpStatusCode.InternalServerError,
        message =
          RpcErrorResponse(
            errorType = result.errorType.name,
            message = result.message,
            details = result.details,
          ),
      )
  }
}

suspend inline fun ApplicationCall.respondRpcException(e: Exception) {
  respond(
    status = HttpStatusCode.InternalServerError,
    message =
      RpcErrorResponse(
        errorType = "UNKNOWN_ERROR",
        message = "Failed to process RPC request",
        details = e.message,
      ),
  )
}

inline fun <
  reified TResponse : Any,
  reified TRequest : RpcRequest<TResponse>,
> Route.registerRpcHandler(handler: RpcHandler<TRequest, TResponse>) {
  post(TRequest::class.toRpcPath()) {
    try {
      val request: TRequest = call.receive()
      val result = handler.handle(request)
      call.respondRpcResult(result)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      call.respondRpcException(e)
    }
  }
}

suspend fun ApplicationCall.respondRpcError(
  status: HttpStatusCode = HttpStatusCode.InternalServerError,
  errorType: RpcResult.ErrorType = RpcResult.ErrorType.UNKNOWN_ERROR,
  message: String,
  details: String? = null,
) {
  respond(
    status = status,
    message =
      RpcErrorResponse(
        errorType = errorType.name,
        message = message,
        details = details,
      ),
  )
}
