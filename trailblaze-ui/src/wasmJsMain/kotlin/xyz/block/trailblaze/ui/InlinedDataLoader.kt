package xyz.block.trailblaze.ui

import getTrailblazeReportJsonFromBrowser
import kotlinx.coroutines.CompletableDeferred
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJson.createTrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.getSessionInfo

/**
 * Loads session data from inlined JavaScript variables in the HTML page.
 * Accesses window.trailblaze.sessions and window.trailblaze.session_detail.
 *
 * Uses JS bridge functions to work around Kotlin/Wasm JS interop restrictions.
 */
object InlinedDataLoader : TrailblazeLogsDataProvider {

  private val json = createTrailblazeJsonInstance(emptyMap())

  private var _sessionsCache: List<String>? = null

  /**
   * Gets the list of session names from window.trailblaze.sessions
   */
  override suspend fun getSessionIdsAsync(): List<String> {
    if (_sessionsCache != null) return _sessionsCache!!
    val completableDeferred = CompletableDeferred<List<String>>()
    println("Loading sessions from getSessionIds()")
    try {
      getTrailblazeReportJsonFromBrowser("sessions") { sessionsJson ->
        println("Got JSON for sessions: $sessionsJson")
        val value = json.decodeFromString<List<String>>(sessionsJson)
        _sessionsCache = value
        completableDeferred.complete(value)
      }
    } catch (e: Exception) {
      println("Error loading sessions from window.trailblaze.sessions: ${e.message}")
      completableDeferred.complete(emptyList())
    }
    return completableDeferred.await()
  }

  override suspend fun getLogsForSessionAsync(sessionId: String?): List<TrailblazeLog> {
    return try {
      if (sessionId == null) return emptyList()
      loadAllLogs()[sessionId] ?: error("Session detail not found for $sessionId")
    } catch (e: Exception) {
      println("Error loading session detail for $sessionId: ${e.message}")
      emptyList()
    }
  }

  override suspend fun getSessionInfoAsync(sessionName: String): SessionInfo {
    return getLogsForSessionAsync(sessionName).getSessionInfo()
  }

  private var _allLogsCache: Map<String, List<TrailblazeLog>>? = null
  private var _allRecordingsCache: Map<String, String?>? = null

  private suspend fun loadAllLogs(): Map<String, List<TrailblazeLog>> {
    if (_allLogsCache != null) return _allLogsCache!!
    val completableDeferred = CompletableDeferred<Map<String, List<TrailblazeLog>>>()
    getTrailblazeReportJsonFromBrowser("session_detail") { jsonString ->
      val allLogs = json.decodeFromString<Map<String, List<TrailblazeLog>>>(jsonString)
      _allLogsCache = allLogs
      completableDeferred.complete(allLogs)
    }
    return completableDeferred.await()
  }

  override suspend fun getSessionRecordingYaml(sessionId: String): String {
    return loadAllRecordings()[sessionId] ?: "No recording available for $sessionId."
  }

  private suspend fun loadAllRecordings(): Map<String, String?> {
    if (_allRecordingsCache != null) return _allRecordingsCache!!
    val completableDeferred = CompletableDeferred<Map<String, String?>>()
    getTrailblazeReportJsonFromBrowser("session_yaml") { jsonString ->
      val allLogs = json.decodeFromString<Map<String, String?>>(jsonString)
      _allRecordingsCache = allLogs
      completableDeferred.complete(allLogs)
    }
    return completableDeferred.await()
  }
}
