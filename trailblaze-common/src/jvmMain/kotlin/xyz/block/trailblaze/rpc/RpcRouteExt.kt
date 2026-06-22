package xyz.block.trailblaze.rpc

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult

/**
 * Canonical host-side helpers for registering a daemon `/rpc/<Name>` endpoint from a typed
 * `RpcRequest<TResponse>` + an [RpcHandler]. Lives in `:trailblaze-common` (jvmMain) so every
 * JVM/host module that runs the daemon's embedded Ktor server can share one implementation — the
 * host device API (`DeviceApiEndpoint`), the Compose driver server, and the Trail Runner endpoint.
 * Scoped to `jvmMain` (not `commonMain`) so the on-device Android build does not inherit ktor-server.
 *
 * The wire contract matches the generated TypeScript `rpcCall` client (`sdks/typescript/src/rpc`):
 * a 2xx carries the raw `TResponse`, a non-2xx carries a flat [RpcErrorResponse]. The on-device RPC
 * module (`trailblaze-android-ondevice-mcp`) keeps its own variant; the existing `:trailblaze-compose`
 * copy is now redundant and should be repointed here in a follow-up.
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
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR.name,
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
