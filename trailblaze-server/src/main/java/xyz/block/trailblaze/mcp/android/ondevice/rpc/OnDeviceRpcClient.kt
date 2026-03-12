package xyz.block.trailblaze.mcp.android.ondevice.rpc

import io.ktor.client.request.get
import kotlinx.coroutines.delay
import kotlinx.serialization.SerializationException
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest.Companion.toRpcPath
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils
import xyz.block.trailblaze.mcp.utils.HttpRequestUtils.HttpRpcException
import java.io.IOException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import xyz.block.trailblaze.util.Console

/**
 * This is a pseudo-RPC client that communicates with the on-device server.
 */
class OnDeviceRpcClient(
  trailblazeDeviceId: TrailblazeDeviceId,
  private val sendProgressMessage: (String) -> Unit = {},
) {

  @PublishedApi
  internal val baseUrl = "http://localhost:${trailblazeDeviceId.getTrailblazeOnDeviceSpecificPort()}"

  @PublishedApi
  internal val httpRequestUtils: HttpRequestUtils = HttpRequestUtils(
    baseUrl = baseUrl,
  )

  /**
   * Generic RPC call function that handles request serialization, routing, and response deserialization.
   * Wraps the result in RpcResult to handle errors gracefully.
   * 
   * This is the primary way to make RPC calls. Any type implementing [RpcRequest] can be used.
   * The response type is automatically inferred from the request type.
   * 
   * @param TResponse The response type (automatically inferred from the request)
   * @param TRequest The request type (must implement RpcRequest<TResponse>)
   * @param request The request object
   * @return RpcResult containing either the deserialized response or error information
   * 
   * Example:
   * ```
   * // Response type is automatically inferred!
   * val result = rpcCall(DeviceStatusRequest) // Returns RpcResult<DeviceStatusResponse>
   * when (result) {
   *   is RpcResult.Success -> Console.log(result.data)
   *   is RpcResult.Failure -> Console.log(result.message)
   * }
   * ```
   */
  suspend inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> rpcCall(
    request: TRequest
  ): RpcResult<TResponse> {
    val urlPath = TRequest::class.toRpcPath()
    val fullUrl = "$baseUrl$urlPath"
    val methodName = TRequest::class.simpleName

    return try {
      val jsonInputString = TrailblazeJsonInstance.encodeToString(request)
      val responseJson = httpRequestUtils.postRequest(
        urlPath = urlPath,
        jsonPostBody = jsonInputString
      )
      val response: TResponse = TrailblazeJsonInstance.decodeFromString(responseJson)
      RpcResult.Success(response)
    } catch (e: HttpRpcException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "Server error during RPC call: ${e.message}",
        details = e.responseBody,
        method = methodName,
        url = fullUrl
      )
    } catch (e: SerializationException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.SERIALIZATION_ERROR,
        message = "Failed to serialize/deserialize RPC data",
        details = e.message,
        method = methodName,
        url = fullUrl
      )
    } catch (e: IOException) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.NETWORK_ERROR,
        message = "Network error during RPC call",
        details = e.message,
        method = methodName,
        url = fullUrl
      )
    } catch (e: Exception) {
      RpcResult.Failure(
        errorType = RpcResult.ErrorType.UNKNOWN_ERROR,
        message = "RPC call failed: ${e.message}",
        details = e.stackTraceToString(),
        method = methodName,
        url = fullUrl
      )
    }
  }

  /**
   * Convenience method for RPC calls with success and failure callbacks.
   * This is more ergonomic than manually handling RpcResult in a when expression.
   * 
   * @param TResponse The response type (automatically inferred from the request)
   * @param TRequest The request type (must implement RpcRequest<TResponse>)
   * @param request The request object
   * @param onSuccess Callback invoked with the response data when successful
   * @param onFailure Callback invoked with the failure when the call fails
   * 
   * Example:
   * ```
   * rpcCall(
   *   request = runYamlRequest,
   *   onSuccess = { response -> Console.log("Started: ${response.message}") },
   *   onFailure = { failure -> Console.log("Error: ${failure.message}") }
   * )
   * ```
   */
  suspend inline fun <reified TResponse : Any, reified TRequest : RpcRequest<TResponse>> rpcCall(
    request: TRequest,
    crossinline onSuccess: (TResponse) -> Unit,
    crossinline onFailure: (RpcResult.Failure) -> Unit
  ) {
    when (val result = rpcCall(request)) {
      is RpcResult.Success -> onSuccess(result.data)
      is RpcResult.Failure -> onFailure(result)
    }
  }

  @OptIn(ExperimentalTime::class)
  suspend fun verifyServerIsRunning(): Boolean {
    val startTimeSeconds = Clock.System.now().epochSeconds
    (0..5).forEach {
      try {
        delay(it * 500L)
        val client = TrailblazeHttpClientFactory.createDefaultHttpClient(2L)
        val response = client.get("$baseUrl/ping")
        client.close()
        val timeElapsed = Clock.System.now().epochSeconds - startTimeSeconds
        if (response.status.value == 200) {
          sendProgressMessage("Verified on-device server is running after $timeElapsed seconds ($it pings)")
          return true // Server is running
        } else {
          sendProgressMessage("Waiting for server to start ($timeElapsed seconds elapsed)")
        }
      } catch (e: Exception) {
        // Ignore Exception
      }
    }
    val timeElapsed = Clock.System.now().epochSeconds - startTimeSeconds
    sendProgressMessage("Failed to verify on-device is running after 5 attempts over $timeElapsed seconds")
    return false
  }
}
