package xyz.block.trailblaze.logs.server.endpoints

import kotlinx.serialization.json.*
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Singleton that continuously collects HAR (HTTP Archive) data and writes it to per-session files
 * after each new entry is added.
 */
object HarCollector {
    private val lock = ReentrantReadWriteLock()
    private val entriesBySession = ConcurrentHashMap<String, MutableList<JsonObject>>()
    private var logsRepo: LogsRepo? = null

    /**
     * Initializes the HAR collector with the LogsRepo for session management.
     */
    fun initialize(logsRepo: LogsRepo) {
        lock.write {
            this.logsRepo = logsRepo

            // Load existing HAR data from all sessions
            loadExistingHarData()
        }
    }

    /**
     * Gets the current session ID by finding the most recent session.
     * Sessions are sorted by their date prefix in descending order.
     */
    private fun getCurrentSessionId(): String? {
        val repo = logsRepo ?: return null
        val sessionIds = repo.getSessionIds()
        return sessionIds.firstOrNull() // Already sorted in descending order
    }

    /**
     * Adds a new HAR entry to the current session and immediately writes the updated HAR file.
     */
    fun addEntry(entry: JsonObject) {
        val currentSessionId = getCurrentSessionId()
        if (currentSessionId == null) {
            println("HarCollector: No current session found, skipping HAR entry")
            return
        }

        addEntryToSession(currentSessionId, entry)
    }

    /**
     * Adds a new HAR entry to a specific session and immediately writes the updated HAR file.
     */
    fun addEntryToSession(sessionId: String, entry: JsonObject) {
        lock.write {
            val sessionEntries = entriesBySession.getOrPut(sessionId) { mutableListOf() }
            sessionEntries.add(entry)
            writeHarFileForSession(sessionId)
            println("HarCollector: Added entry to session $sessionId (${sessionEntries.size} total entries)")
        }
    }

    /**
     * Gets the current number of entries for the current session.
     */
    fun getEntryCount(): Int {
        val currentSessionId = getCurrentSessionId() ?: return 0
        return getEntryCountForSession(currentSessionId)
    }

    /**
     * Gets the number of entries for a specific session.
     */
    fun getEntryCountForSession(sessionId: String): Int = lock.read {
        entriesBySession[sessionId]?.size ?: 0
    }

    /**
     * Gets all current entries for the current session (read-only copy).
     */
    fun getAllEntries(): List<JsonObject> {
        val currentSessionId = getCurrentSessionId() ?: return emptyList()
        return getAllEntriesForSession(currentSessionId)
    }

    /**
     * Gets all entries for a specific session (read-only copy).
     */
    fun getAllEntriesForSession(sessionId: String): List<JsonObject> = lock.read {
        entriesBySession[sessionId]?.toList() ?: emptyList()
    }

    /**
     * Clears all entries for the current session and updates the file.
     */
    fun clearEntries() {
        val currentSessionId = getCurrentSessionId() ?: return
        clearEntriesForSession(currentSessionId)
    }

    /**
     * Clears all entries for a specific session and updates the file.
     */
    fun clearEntriesForSession(sessionId: String) {
        lock.write {
            entriesBySession[sessionId]?.clear()
            writeHarFileForSession(sessionId)
            println("HarCollector: Cleared all entries for session $sessionId")
        }
    }

    /**
     * Gets all session IDs that have HAR entries.
     */
    fun getSessionsWithEntries(): Set<String> = lock.read { entriesBySession.keys.toSet() }

    /**
     * Loads existing HAR data from all session directories.
     */
    private fun loadExistingHarData() {
        val repo = logsRepo ?: return

        repo.getSessionIds().forEach { sessionId ->
            val harFile = getHarFileForSession(sessionId)
            if (harFile.exists() && harFile.length() > 0) {
                try {
                    val existingContent = harFile.readText()
                    val existingHar = Json.parseToJsonElement(existingContent).jsonObject
                    val existingEntries = existingHar["log"]?.jsonObject?.get("entries")?.jsonArray

                    val sessionEntries = entriesBySession.getOrPut(sessionId) { mutableListOf() }
                    existingEntries?.forEach { entry ->
                        sessionEntries.add(entry.jsonObject)
                    }

                    if (sessionEntries.isNotEmpty()) {
                        println("HarCollector: Loaded ${sessionEntries.size} existing entries for session $sessionId")
                    }
                } catch (e: Exception) {
                    println("HarCollector: Could not load existing HAR file for session $sessionId: ${e.message}")
                }
            }
        }
    }

    /**
     * Gets the HAR file for a specific session.
     */
    private fun getHarFileForSession(sessionId: String): File {
        val repo = logsRepo ?: throw IllegalStateException("HarCollector not initialized")
        val sessionDir = repo.getSessionDir(sessionId)
        return File(sessionDir, "http-requests.har")
    }

    /**
     * Writes the current HAR data for a specific session to its file.
     */
    private fun writeHarFileForSession(sessionId: String) {
        val harFile = getHarFileForSession(sessionId)
        val sessionEntries = entriesBySession[sessionId] ?: return

        val harData = buildJsonObject {
            put(
                "log",
                buildJsonObject {
                    put("version", JsonPrimitive("1.2"))
                    put(
                        "creator",
                        buildJsonObject {
                            put("name", JsonPrimitive("trailblaze"))
                            put("version", JsonPrimitive("1.0"))
                        }
                    )
                    put(
                        "entries",
                        buildJsonArray {
                            sessionEntries.forEach { entry ->
                                add(entry)
                            }
                        }
                    )
                }
            )
        }

        try {
            // Ensure parent directory exists
            harFile.parentFile?.mkdirs()

            // Write to a temporary file first, then rename to ensure atomicity
            val tempFile = File(harFile.parentFile, "${harFile.name}.tmp")
            tempFile.writeText(Json.encodeToString(harData))
            tempFile.renameTo(harFile)
        } catch (e: Exception) {
            println("HarCollector: Error writing HAR file for session $sessionId: ${e.message}")
        }
    }

    /**
     * Gets the HAR file path for the current session.
     */
    fun getHarFilePath(): String? {
        val currentSessionId = getCurrentSessionId() ?: return null
        return getHarFilePathForSession(currentSessionId)
    }

    /**
     * Gets the HAR file path for a specific session.
     */
    fun getHarFilePathForSession(sessionId: String): String? = lock.read {
        getHarFileForSession(sessionId).absolutePath
    }
}