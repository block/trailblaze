package xyz.block.trailblaze.ui

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.DelicateCoroutinesApi
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.getSessionInfo

private const val BASE_URL = "http://localhost:52525"

/**
 * Allows our Wasm UI to query the Trailblaze Server endpoint to grab the logs.
 */
@OptIn(DelicateCoroutinesApi::class)
object NetworkTrailblazeLogsDataProvider : TrailblazeLogsDataProvider {
  private val httpClient = HttpClient {
    install(ContentNegotiation) {
      json(TrailblazeJson.createTrailblazeJsonInstance(emptyMap()))
    }
  }

  // Async methods that match the HTTP patterns from the Composables
  override suspend fun getSessionIdsAsync(): List<String> {
    return try {
      val url = "$BASE_URL/api/sessions"
      httpClient.get(url).body<List<String>>()
    } catch (e: Exception) {
      emptyList()
    }
  }

  private val logsForSessionCache: MutableMap<String, List<TrailblazeLog>> = mutableMapOf()

  override suspend fun getLogsForSessionAsync(sessionId: String?): List<TrailblazeLog> {
    val cachedValue = logsForSessionCache[sessionId]
    return if (!cachedValue.isNullOrEmpty()) {
      return cachedValue
    } else {
      try {
        if (sessionId == null) return emptyList()
        val url = "$BASE_URL/api/session/$sessionId/logs"
        httpClient.get(url).body<List<TrailblazeLog>>().also {
          logsForSessionCache[sessionId] = it
        }
      } catch (e: Exception) {
        emptyList()
      }
    }
  }

  override suspend fun getSessionInfoAsync(sessionName: String): SessionInfo {
    return getLogsForSessionAsync(sessionName).getSessionInfo()
  }

  private val recordedYamlForSessionCache: MutableMap<String, String> = mutableMapOf()
  override suspend fun getSessionRecordingYaml(sessionId: String): String {
    val cachedValue = recordedYamlForSessionCache[sessionId]

    return if (!cachedValue.isNullOrEmpty()) {
      return cachedValue
    } else {
      try {
        if (sessionId == null) return "No Session ID Specified"
        val url = "$BASE_URL/api/session/$sessionId/yaml"
        httpClient.get(url).bodyAsText().also {
          recordedYamlForSessionCache[sessionId] = it
        }
      } catch (e: Exception) {
        "Could not load YAML for session $sessionId: ${e.message}"
      }
    }
  }
}