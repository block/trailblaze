package xyz.block.trailblaze.mcp.utils

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import xyz.block.trailblaze.util.Console

class HttpRequestUtils(
  private val baseUrl: String,
) {

  // Prepare the HTTP client with timeout configuration
  private val client = HttpClient {
    install(HttpTimeout) {
      requestTimeoutMillis = 300_000
      connectTimeoutMillis = 300_000
      socketTimeoutMillis = 300_000
    }
  }

  suspend fun getRequest(urlPath: String): String {
    return try {
      val response = client.post("$baseUrl$urlPath") {
        contentType(ContentType.Application.Json)
      }

      val responseBody = response.bodyAsText()
      Console.log("Response Body: $responseBody")
      Console.log("Response Code: ${response.status.value}")
      Console.log("Response Message: ${response.status.description}")

      if (response.status.value !in 200..299) {
        """"Unexpected code ${response.status}""""
      } else {
        responseBody
      }
    } catch (e: Exception) {
      val errorMessage = "Exception sending HTTP request to device. Error: ${e.message}"
      errorMessage
    }
  }

  suspend fun postRequest(urlPath: String, jsonPostBody: String? = null): String {
    return try {
      val response = client.post("$baseUrl$urlPath") {
        contentType(ContentType.Application.Json)
        jsonPostBody?.let {
          setBody(jsonPostBody)
        }
      }

      val responseBody = response.bodyAsText()
      Console.log("Response Body: $responseBody")
      Console.log("Response Code: ${response.status.value}")
      Console.log("Response Message: ${response.status.description}")

      if (response.status.value !in 200..299) {
        """"Unexpected code ${response.status}""""
      } else {
        responseBody
      }
    } catch (e: Exception) {
      val errorMessage = "Exception sending HTTP request to device. Error: ${e.message}"
      errorMessage
    } finally {
      close()
    }
  }

  fun close() {
    client.close()
  }
}
