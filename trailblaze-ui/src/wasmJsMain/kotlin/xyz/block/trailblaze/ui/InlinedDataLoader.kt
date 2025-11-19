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
 *
 * Data Loading Strategy (optimized for fast initial page load):
 * 1. Session list loads lightweight metadata from window.trailblaze.session_info (fast!)
 * 2. Session details (logs, YAML) are loaded on-demand when viewing a session
 * 3. Per-session data is cached to avoid re-parsing
 * 4. Falls back to legacy bulk loading if per-session data is not available
 *
 * Uses JS bridge functions to work around Kotlin/Wasm JS interop restrictions.
 */
object InlinedDataLoader : TrailblazeLogsDataProvider {

  private val json = createTrailblazeJsonInstance(emptyMap())

  private var _sessionsCache: List<String>? = null
    private var _sessionInfoCache: Map<String, SessionInfo>? = null

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

    /**
     * Loads the session info map (lightweight metadata for all sessions)
     */
    private suspend fun loadSessionInfoMap(): Map<String, SessionInfo> {
        if (_sessionInfoCache != null) {
            println("✅ Using cached session info map")
            return _sessionInfoCache!!
        }
        val completableDeferred = CompletableDeferred<Map<String, SessionInfo>>()
        val startTime = kotlinx.browser.window.performance.now()
        println("⏳ [${startTime.toInt()}ms] Loading session info map...")
        try {
            getTrailblazeReportJsonFromBrowser("session_info") { jsonString ->
                val decompressEndTime = kotlinx.browser.window.performance.now()
                println("📦 [${decompressEndTime.toInt()}ms] Decompressed session_info (${jsonString.length} chars) in ${(decompressEndTime - startTime).toInt()}ms")

                val parseStartTime = kotlinx.browser.window.performance.now()
                val sessionInfoMap = json.decodeFromString<Map<String, SessionInfo>>(jsonString)
                val parseEndTime = kotlinx.browser.window.performance.now()
                println("✅ [${parseEndTime.toInt()}ms] Parsed ${sessionInfoMap.size} SessionInfo objects in ${(parseEndTime - parseStartTime).toInt()}ms")

                _sessionInfoCache = sessionInfoMap
                completableDeferred.complete(sessionInfoMap)
            }
        } catch (e: Exception) {
            println("❌ Error loading session info map: ${e.message}")
            println("⚠️  Falling back to legacy method (loading full logs)")
            completableDeferred.complete(emptyMap())
    }
    return completableDeferred.await()
  }

    // Per-session caches (lazy loaded on demand)
    private val _perSessionLogsCache = mutableMapOf<String, List<TrailblazeLog>>()
    private val _perSessionYamlCache = mutableMapOf<String, String>()

    override suspend fun getLogsForSessionAsync(sessionId: String?): List<TrailblazeLog> {
        return try {
            if (sessionId == null) return emptyList()

            // Try per-session lazy loading first (preferred)
            if (_perSessionLogsCache.containsKey(sessionId)) {
                return _perSessionLogsCache[sessionId]!!
            }

            // Try to load from per-session compressed data
            try {
                val logs = loadSessionLogs(sessionId)
                _perSessionLogsCache[sessionId] = logs
                return logs
            } catch (e: Exception) {
                println("Per-session loading failed for $sessionId, falling back to bulk load: ${e.message}")
                // Fall back to loading all logs at once
                return loadAllLogs()[sessionId] ?: error("Session detail not found for $sessionId")
            }
        } catch (e: Exception) {
            println("Error loading session detail for $sessionId: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getSessionInfoAsync(sessionName: String): SessionInfo? {
        val startTime = kotlinx.browser.window.performance.now()
        println("⏳ [${startTime.toInt()}ms] Getting session info for: $sessionName")

        // Try to get from the lightweight session info map first (fast!)
        val sessionInfoMap = loadSessionInfoMap()
        if (sessionInfoMap.containsKey(sessionName)) {
            val endTime = kotlinx.browser.window.performance.now()
            println("✅ [${endTime.toInt()}ms] Got session info from map in ${(endTime - startTime).toInt()}ms")
            return sessionInfoMap[sessionName]
        }

        // Fallback: load full logs and compute session info (slow, legacy compatibility)
        println("⚠️  Session info not found in map for $sessionName, loading full logs...")
        val fallbackStartTime = kotlinx.browser.window.performance.now()
        val result = getLogsForSessionAsync(sessionName).getSessionInfo()
        val fallbackEndTime = kotlinx.browser.window.performance.now()
        println("✅ [${fallbackEndTime.toInt()}ms] Computed session info from logs in ${(fallbackEndTime - fallbackStartTime).toInt()}ms")
        return result
    }

    /**
     * Load logs for a single session (lazy loading)
     */
    private suspend fun loadSessionLogs(sessionId: String): List<TrailblazeLog> {
        val completableDeferred = CompletableDeferred<List<TrailblazeLog>>()
        val key = "session/$sessionId/logs"
        val startTime = kotlinx.browser.window.performance.now()
        println("⏳ [${startTime.toInt()}ms] Loading per-session logs: $key")
        getTrailblazeReportJsonFromBrowser(key) { jsonString ->
            val decompressEndTime = kotlinx.browser.window.performance.now()
            println("📦 [${decompressEndTime.toInt()}ms] Decompressed JSON (${jsonString.length} chars) in ${(decompressEndTime - startTime).toInt()}ms")

            val parseStartTime = kotlinx.browser.window.performance.now()
            val logs = json.decodeFromString<List<TrailblazeLog>>(jsonString)
            val parseEndTime = kotlinx.browser.window.performance.now()
            println("✅ [${parseEndTime.toInt()}ms] Parsed ${logs.size} logs in ${(parseEndTime - parseStartTime).toInt()}ms")

            completableDeferred.complete(logs)
        }
        return completableDeferred.await()
    }

    private var _allLogsCache: Map<String, List<TrailblazeLog>>? = null
    private var _allRecordingsCache: Map<String, String?>? = null

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

    override suspend fun getSessionRecordingYaml(sessionId: String): String {
        // Try per-session lazy loading first
        if (_perSessionYamlCache.containsKey(sessionId)) {
            return _perSessionYamlCache[sessionId]!!
        }

        try {
            val yaml = loadSessionYaml(sessionId)
            _perSessionYamlCache[sessionId] = yaml
            return yaml
        } catch (e: Exception) {
            println("❌ Per-session YAML loading failed for $sessionId: ${e.message}")
            println("⚠️  Attempting fallback to legacy bulk load...")
            try {
                val allRecordings = loadAllRecordings()
                val yaml = allRecordings[sessionId]
                if (yaml != null) {
                    _perSessionYamlCache[sessionId] = yaml
                    return yaml
                } else {
                    val errorMsg =
                        "# No recording available for session: $sessionId\n# YAML chunks were not generated or are missing."
                    println("❌ No YAML found for $sessionId in legacy data either")
                    return errorMsg
                }
            } catch (fallbackError: Exception) {
                val errorMsg =
                    "# Error loading YAML: ${fallbackError.message}\n# Both per-session chunks and legacy data failed."
                println("❌ Fallback also failed: ${fallbackError.message}")
                return errorMsg
            }
        }
    }

    /**
     * Load YAML for a single session (lazy loading)
     */
    private suspend fun loadSessionYaml(sessionId: String): String {
        val completableDeferred = CompletableDeferred<String>()
        val key = "session/$sessionId/yaml"
        println("Loading per-session YAML: $key")
        getTrailblazeReportJsonFromBrowser(key) { jsonString ->
            val yaml = json.decodeFromString<String>(jsonString)
            completableDeferred.complete(yaml)
        }
        return completableDeferred.await()
    }

    /**
     * Load all recordings at once (fallback for legacy data or errors)
     */
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
