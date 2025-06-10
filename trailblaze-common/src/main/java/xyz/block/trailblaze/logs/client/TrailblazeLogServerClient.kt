package xyz.block.trailblaze.logs.client

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType

class TrailblazeLogServerClient(
  val httpClient: HttpClient,
  val baseUrl: String,
) {
  private suspend fun ping(): HttpResponse = httpClient.get("$baseUrl/ping")

  suspend fun isServerRunning(): Boolean {
    val startTime = System.currentTimeMillis()
    val isRunning = try {
      ping().status.value == HttpStatusCode.OK.value
    } catch (e: Exception) {
      false
    }
    println("isServerRunning $isRunning in ${System.currentTimeMillis() - startTime}ms")
    return isRunning
  }

  suspend fun postAgentLog(log: TrailblazeLog): HttpResponse {
    val logJson = TrailblazeJsonInstance.encodeToString<TrailblazeLog>(log)
    return httpClient.post("$baseUrl/agentlog") {
      contentType(ContentType.Application.Json)
      setBody(logJson)
    }
  }

  suspend fun postScreenshot(screenshotFilename: String, sessionId: String, screenshotBytes: ByteArray): HttpResponse = httpClient.post("$baseUrl/log/screenshot") {
    parameter(key = "filename", value = screenshotFilename)
    parameter(key = "session", value = sessionId)
    contentType(ContentType.Image.PNG)
    setBody(screenshotBytes)
  }
}
