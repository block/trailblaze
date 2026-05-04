package xyz.block.trailblaze.mcp

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.mcp.models.RpcErrorResponse

/**
 * Helper function to handle RPC result responses.
 * Extracts common logic for responding to RPC results and errors.
 */
suspend inline fun <reified T : Any> ApplicationCall.respondRpcResult(result: RpcResult<T>) {
  when (result) {
    is RpcResult.Success -> {
      respond(HttpStatusCode.OK, result.data)
    }

    is RpcResult.Failure -> {
      respond(
        HttpStatusCode.InternalServerError,
        RpcErrorResponse(
          errorType = result.errorType.name,
          message = result.message,
          details = result.details
        )
      )
    }
  }
}

/**
 * Helper function to handle RPC exceptions.
 * Provides consistent error response formatting.
 */
suspend inline fun ApplicationCall.respondRpcException(e: Exception) {
  respond(
    HttpStatusCode.InternalServerError,
    RpcErrorResponse(
      errorType = "UNKNOWN_ERROR",
      message = "Failed to process RPC request",
      details = e.message
    )
  )
}

/**
 * Registers a type-safe handler for an RPC request type.
 * Automatically handles:
 * - Route registration based on request type
 * - Request deserialization
 * - Response serialization
 * - Error handling
 * 
 * The response type is automatically inferred from the request type.
 * 
 * @param TResponse The response type (inferred from request)
 * @param TRequest The request type (must implement RpcRequest<TResponse>)
 * @param handler The handler instance that processes the request
 */
inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> Route.registerRpcHandler(
  handler: RpcHandler<TRequest, TResponse>
) {
  post(TRequest::class.toRpcPath()) {
    val request: TRequest = try {
      call.receive()
    } catch (e: SerializationException) {
      // Malformed JSON or schema mismatch.
      call.respondRpcDeserializationError(e)
      return@post
    } catch (e: IllegalArgumentException) {
      // `init { require(...) }` violation in the request data class — caller sent a
      // payload that decodes structurally but violates the wire contract (e.g.
      // RunYamlRequest with awaitCompletion=false plus non-empty memorySnapshot).
      call.respondRpcDeserializationError(e)
      return@post
    }
    try {
      val result = handler.handle(request)
      call.respondRpcResult(result)
    } catch (e: Exception) {
      call.respondRpcException(e)
    }
  }
}

/**
 * Deserialization-time failures (malformed JSON, schema drift, or wire-contract `require`
 * violations from data-class init blocks) are caller errors, not server errors. Surface
 * them as HTTP 400 with [RpcResult.ErrorType.SERIALIZATION_ERROR] so a host can
 * differentiate "fix your request" from "retry on infra failure."
 */
suspend fun ApplicationCall.respondRpcDeserializationError(e: Exception) {
  respond(
    HttpStatusCode.BadRequest,
    RpcErrorResponse(
      errorType = RpcResult.ErrorType.SERIALIZATION_ERROR.name,
      message = e.message ?: "Request failed to deserialize",
      details = e::class.simpleName,
    ),
  )
}

/**
 * Utility function to respond with an RPC error.
 * This makes it easy to return consistent error responses from anywhere in your routing.
 * 
 * Example:
 * ```kotlin
 * post("/some-endpoint") {
 *   if (notAuthorized) {
 *     call.respondRpcError(
 *       status = HttpStatusCode.Forbidden,
 *       errorType = RpcResult.ErrorType.HTTP_ERROR,
 *       message = "Not authorized",
 *       details = "User does not have permission"
 *     )
 *     return@post
 *   }
 *   // ... handle request
 * }
 * ```
 */
suspend fun ApplicationCall.respondRpcError(
  status: HttpStatusCode = HttpStatusCode.InternalServerError,
  errorType: RpcResult.ErrorType = RpcResult.ErrorType.UNKNOWN_ERROR,
  message: String,
  details: String? = null
) {
  respond(
    status,
    RpcErrorResponse(
      errorType = errorType.name,
      message = message,
      details = details
    )
  )
}
