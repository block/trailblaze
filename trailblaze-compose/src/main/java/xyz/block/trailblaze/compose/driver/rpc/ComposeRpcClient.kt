package xyz.block.trailblaze.compose.driver.rpc

import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.compose.driver.ComposeViewHierarchyDetail
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import java.io.IOException

/**
 * HTTP client for communicating with a [ComposeRpcServer].
 *
 * Provides typed [rpcCall] for type-safe request/response handling, convenience methods
 * [getScreenState] and [executeTools], and a [waitForServer] health-check loop.
 */
class ComposeRpcClient(
  @PublishedApi internal val baseUrl: String,
) {

  @PublishedApi
  internal val client = TrailblazeHttpClientFactory.createDefaultHttpClient(timeoutInSeconds = 60)

  suspend inline fun <
    reified TResponse : Any,
    reified TRequest : RpcRequest<TResponse>,
  > rpcCall(request: TRequest): RpcResult<TResponse> {
    val urlPath = TRequest::class.toRpcPath()
    val fullUrl = "$baseUrl$urlPath"
    val methodName = TRequest::class.simpleName

    return try {
      val jsonBody = TrailblazeJsonInstance.encodeToString(request)
      val response =
        client.post(fullUrl) {
          contentType(ContentType.Application.Json)
          setBody(jsonBody)
        }

      if (response.status.value !in 200..299) {
        return RpcResult.Failure(
          errorType = RpcResult.ErrorType.HTTP_ERROR,
          message = "HTTP ${response.status.value}",
          details = response.bodyAsText(),
          method = methodName,
          url = fullUrl,
        )
      }

      val responseJson = response.bodyAsText()
      val decoded: TResponse = TrailblazeJsonInstance.decodeFromString(responseJson)
      RpcResult.Success(decoded)
    } catch (e: SerializationException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.SERIALIZATION_ERROR,
        message = "Failed to serialize/deserialize RPC data",
        details = e.message,
        method = methodName,
        url = fullUrl,
      )
    } catch (e: IOException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.NETWORK_ERROR,
        message = "Network error during RPC call",
        details = e.message,
        method = methodName,
        url = fullUrl,
      )
    } catch (e: Exception) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "RPC call failed",
        details = e.message,
        method = methodName,
        url = fullUrl,
      )
    }
  }

  suspend fun getScreenState(
    requestedDetails: Set<ComposeViewHierarchyDetail> = emptySet(),
  ): RpcResult<GetScreenStateResponse> =
    rpcCall(GetScreenStateRequest(requestedDetails = requestedDetails))

  suspend fun executeTools(request: ExecuteToolsRequest): RpcResult<ExecuteToolsResponse> =
    rpcCall(request)

  suspend fun waitForServer(maxAttempts: Int = 10, delayMs: Long = 200): Boolean {
    repeat(maxAttempts) { attempt ->
      try {
        val response = client.get("$baseUrl/ping")
        if (response.status.value == 200) return true
      } catch (_: Exception) {
        // server not ready yet
      }
      delay(delayMs)
    }
    return false
  }

  fun close() {
    client.close()
  }
}
