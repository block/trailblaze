package xyz.block.trailblaze.mcp.utils

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import xyz.block.trailblaze.util.Console

class HttpRequestUtils(
  private val baseUrl: String,
) : AutoCloseable {

  private val client = createOnDeviceRpcHttpClient()

  suspend fun getRequest(urlPath: String): String {
    val response = client.get("$baseUrl$urlPath") {
      contentType(ContentType.Application.Json)
    }

    val responseBody = response.bodyAsText()
    Console.log("Response Body: $responseBody")
    Console.log("Response Code: ${response.status.value}")
    Console.log("Response Message: ${response.status.description}")

    if (response.status.value !in 200..299) {
      throw HttpRpcException("HTTP ${response.status.value}: ${response.status.description}", responseBody)
    }
    return responseBody
  }

  /**
   * Posts a JSON body to [urlPath]. If [requestTimeoutMs] is non-null, applies a per-request
   * HttpTimeout override for both request + socket — so a long-running RPC (e.g. a sync
   * [xyz.block.trailblaze.llm.RunYamlRequest]) can extend the cap without every short RPC on
   * the same client inheriting it.
   */
  suspend fun postRequest(
    urlPath: String,
    jsonPostBody: String? = null,
    requestTimeoutMs: Long? = null,
  ): String {
    val response = client.post("$baseUrl$urlPath") {
      contentType(ContentType.Application.Json)
      if (requestTimeoutMs != null) {
        timeout {
          requestTimeoutMillis = requestTimeoutMs
          socketTimeoutMillis = requestTimeoutMs
        }
      }
      jsonPostBody?.let {
        setBody(jsonPostBody)
      }
    }

    val responseBody = response.bodyAsText()
    Console.log("Response Body: $responseBody")
    Console.log("Response Code: ${response.status.value}")
    Console.log("Response Message: ${response.status.description}")

    if (response.status.value !in 200..299) {
      throw HttpRpcException("HTTP ${response.status.value}: ${response.status.description}", responseBody)
    }
    return responseBody
  }

  class HttpRpcException(message: String, val responseBody: String?) : Exception(message)

  override fun close() {
    client.close()
  }

  private companion object {
    /**
     * Builds the HTTP client used for every on-device RPC in this class.
     *
     * Default timeouts are sized for typical fast RPCs (`GetScreenState`, progress queries,
     * etc.) so a stuck on-device server surfaces as a failure within seconds-to-minutes rather
     * than tying up the caller for much longer.
     *
     * Long-running calls — chiefly [xyz.block.trailblaze.llm.RunYamlRequest] with
     * `awaitCompletion = true`, which can run for many minutes while a whole trail executes
     * on-device — opt into a longer per-request timeout via [RpcRequest.requestTimeoutMs],
     * applied by [postRequest] as a per-call `HttpTimeout` override. This keeps the short
     * failure-detection budget for every other RPC intact.
     *
     * Connect stays short — [xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRpcClient.waitForReady]
     * handles the "is the server actually up" dance separately.
     */
    private fun createOnDeviceRpcHttpClient(): HttpClient = HttpClient {
      install(HttpTimeout) {
        requestTimeoutMillis = 300_000
        connectTimeoutMillis = 30_000
        socketTimeoutMillis = 300_000
      }
    }
  }
}
