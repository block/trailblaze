package xyz.block.trailblaze.ui

import getTrailblazeReportJsonFromBrowser
import kotlinx.coroutines.CompletableDeferred
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeJson.createTrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.getSessionInfo
import xyz.block.trailblaze.util.Console

/**
 * Loads session data from inlined JavaScript variables in the HTML page.
 *
 * Data Loading Strategy (optimized for fast initial page load):
 * 1. Session list loads lightweight metadata from window.trailblaze.session_info (fast!)
 * 2. Session logs are loaded on-demand when viewing a session
 * 3. Recording YAML is generated on-the-fly from logs (no pre-generation needed)
 * 4. Per-session data is cached to avoid re-parsing
 * 5. Falls back to legacy bulk loading if per-session data is not available
 *
 * Uses JS bridge functions to work around Kotlin/Wasm JS interop restrictions.
 */
object InlinedDataLoader : TrailblazeLogsDataProvider {

  private val json = createTrailblazeJsonInstance(emptyMap())

  private var _sessionsCache: List<xyz.block.trailblaze.logs.model.SessionId>? = null
  private var _sessionInfoCache: Map<String, SessionInfo>? = null

  /**
   * Gets the list of session names from window.trailblaze.sessions
   */
  override suspend fun getSessionIdsAsync(): List<xyz.block.trailblaze.logs.model.SessionId> {
    if (_sessionsCache != null) return _sessionsCache!!
    val completableDeferred = CompletableDeferred<List<xyz.block.trailblaze.logs.model.SessionId>>()
    Console.log("Loading sessions from getSessionIds()")
    try {
      getTrailblazeReportJsonFromBrowser("sessions") { sessionsJson ->
        Console.log("Got JSON for sessions: $sessionsJson")
        val value =
          json.decodeFromString<List<String>>(sessionsJson).map {
            xyz.block.trailblaze.logs.model.SessionId(it)
          }
        _sessionsCache = value
        completableDeferred.complete(value)
      }
    } catch (e: Exception) {
      Console.log("Error loading sessions from window.trailblaze.sessions: ${e.message}")
      completableDeferred.complete(emptyList())
    }
    return completableDeferred.await()
  }

  /**
   * Loads the session info map (lightweight metadata for all sessions)
   */
  private suspend fun loadSessionInfoMap(): Map<String, SessionInfo> {
    if (_sessionInfoCache != null) {
      Console.log("✅ Using cached session info map")
      return _sessionInfoCache!!
    }
    val completableDeferred = CompletableDeferred<Map<String, SessionInfo>>()
    val startTime = kotlinx.browser.window.performance.now()
    Console.log("⏳ [${startTime.toInt()}ms] Loading session info map...")
    try {
      getTrailblazeReportJsonFromBrowser("session_info") { jsonString ->
        val decompressEndTime = kotlinx.browser.window.performance.now()
        Console.log(
          "📦 [${decompressEndTime.toInt()}ms] Decompressed session_info (${jsonString.length} chars) in ${(decompressEndTime - startTime).toInt()}ms",
        )

        val parseStartTime = kotlinx.browser.window.performance.now()
        val sessionInfoMap = json.decodeFromString<Map<String, SessionInfo>>(jsonString)
        val parseEndTime = kotlinx.browser.window.performance.now()
        Console.log(
          "✅ [${parseEndTime.toInt()}ms] Parsed ${sessionInfoMap.size} SessionInfo objects in ${(parseEndTime - parseStartTime).toInt()}ms",
        )

        _sessionInfoCache = sessionInfoMap
        completableDeferred.complete(sessionInfoMap)
      }
    } catch (e: Exception) {
      Console.log("❌ Error loading session info map: ${e.message}")
      Console.log("⚠️  Falling back to legacy method (loading full logs)")
      completableDeferred.complete(emptyMap())
    }
    return completableDeferred.await()
  }

  // Per-session logs cache (lazy loaded on demand)
  private val _perSessionLogsCache = mutableMapOf<String, List<TrailblazeLog>>()

  override suspend fun getLogsForSessionAsync(
    sessionId: xyz.block.trailblaze.logs.model.SessionId?,
  ): List<TrailblazeLog> {
    return try {
      if (sessionId == null) return emptyList()
      val sessionIdStr = sessionId.value

      // Try per-session lazy loading first (preferred)
      if (_perSessionLogsCache.containsKey(sessionIdStr)) {
        return _perSessionLogsCache[sessionIdStr]!!
      }

      // Try to load from per-session compressed data
      try {
        val logs = loadSessionLogs(sessionIdStr)
        _perSessionLogsCache[sessionIdStr] = logs
        return logs
      } catch (e: Exception) {
        Console.log(
          "Per-session loading failed for $sessionIdStr, falling back to bulk load: ${e.message}",
        )
        // Fall back to loading all logs at once
        return loadAllLogs()[sessionIdStr]
          ?: error("Session detail not found for $sessionIdStr")
      }
    } catch (e: Exception) {
      Console.log("Error loading session detail for $sessionId: ${e.message}")
      emptyList()
    }
  }

  override suspend fun getSessionInfoAsync(
    sessionName: xyz.block.trailblaze.logs.model.SessionId,
  ): SessionInfo? {
    val sessionNameStr = sessionName.value
    val startTime = kotlinx.browser.window.performance.now()
    Console.log("⏳ [${startTime.toInt()}ms] Getting session info for: $sessionNameStr")

    // Try to get from the lightweight session info map first (fast!)
    val sessionInfoMap = loadSessionInfoMap()
    if (sessionInfoMap.containsKey(sessionNameStr)) {
      val endTime = kotlinx.browser.window.performance.now()
      Console.log(
        "✅ [${endTime.toInt()}ms] Got session info from map in ${(endTime - startTime).toInt()}ms",
      )
      return sessionInfoMap[sessionNameStr]
    }

    // Fallback: load full logs and compute session info (slow, legacy compatibility)
    Console.log("⚠️  Session info not found in map for $sessionNameStr, loading full logs...")
    val fallbackStartTime = kotlinx.browser.window.performance.now()
    val result = getLogsForSessionAsync(sessionName).getSessionInfo()
    val fallbackEndTime = kotlinx.browser.window.performance.now()
    Console.log(
      "✅ [${fallbackEndTime.toInt()}ms] Computed session info from logs in ${(fallbackEndTime - fallbackStartTime).toInt()}ms",
    )
    return result
  }

  /**
   * Load logs for a single session (lazy loading)
   */
  private suspend fun loadSessionLogs(sessionId: String): List<TrailblazeLog> {
    val completableDeferred = CompletableDeferred<List<TrailblazeLog>>()
    val key = "session/$sessionId/logs"
    val startTime = kotlinx.browser.window.performance.now()
    Console.log("⏳ [${startTime.toInt()}ms] Loading per-session logs: $key")
    getTrailblazeReportJsonFromBrowser(key) { jsonString ->
      try {
        val decompressEndTime = kotlinx.browser.window.performance.now()
        Console.log(
          "📦 [${decompressEndTime.toInt()}ms] Decompressed JSON (${jsonString.length} chars) in ${(decompressEndTime - startTime).toInt()}ms",
        )

        val parseStartTime = kotlinx.browser.window.performance.now()
        val logs = json.decodeFromString<List<TrailblazeLog>>(jsonString)
        val parseEndTime = kotlinx.browser.window.performance.now()
        Console.log(
          "✅ [${parseEndTime.toInt()}ms] Parsed ${logs.size} logs in ${(parseEndTime - parseStartTime).toInt()}ms",
        )

        completableDeferred.complete(logs)
      } catch (e: Exception) {
        Console.log("Failed to parse logs JSON for $sessionId: ${e.message}")
        completableDeferred.complete(emptyList())
      }
    }
    return completableDeferred.await()
  }

  private var _allLogsCache: Map<String, List<TrailblazeLog>>? = null

  /**
   * Load all logs at once (fallback for legacy data or errors)
   */
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
}
