package xyz.block.trailblaze.report.utils

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private typealias TrailblazeSessionId = String

const val DEFAULT_TIMEOUT = 120000L

class LogsRepo(val logsDir: File) : TrailblazeLogsDataProvider {

  // Create a dedicated coroutine scope for background file operations
  private val fileOperationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  init {
    // Ensure the logs directory exists
    logsDir.mkdirs()
  }

  /**
   * A map of trailblaze session IDs to their corresponding file watcher services.
   */
  private val fileWatcherByTrailblazeSession = mutableMapOf<TrailblazeSessionId?, FileWatchService>()

  /**
   * File watcher for the logs directory to monitor session creation/deletion.
   */
  private var sessionListWatcher: FileWatchService? = null

  /**
   * Set of listeners for session list changes.
   */
  private val sessionListListeners = mutableSetOf<SessionListListener>()

  /**
   * Cache of current session IDs to detect additions/removals.
   */
  private var cachedSessionIds = getSessionIds().toSet()

  // Cache for session logs to avoid redundant file reads
  private val sessionLogsCache = mutableMapOf<String, Pair<Long, List<TrailblazeLog>>>()

  fun getSessionDirs(): List<File> = logsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

  fun getSessionIds(): List<String> = getSessionDirs().map { it.name }

  /**
   * Stops watching the trailblaze session directory for changes.
   */
  fun stopWatching(trailblazeSessionId: TrailblazeSessionId) {
    fileWatcherByTrailblazeSession[trailblazeSessionId]?.stopWatching()
    fileWatcherByTrailblazeSession.remove(trailblazeSessionId)
    // Clear cache for stopped session
    sessionLogsCache.remove(trailblazeSessionId)
  }

  /**
   * Watches the trailblaze session directory for changes and reports back updates via the [TrailblazeSessionListener].
   */
  fun startWatchingTrailblazeSession(trailblazeSessionListener: TrailblazeSessionListener) {
    val trailblazeSessionId = trailblazeSessionListener.trailblazeSessionId
    if (fileWatcherByTrailblazeSession[trailblazeSessionId] == null) {
      val sessionDir = getSessionDir(trailblazeSessionId)
      println("LOGLISTENER - Starting to watch trailblaze session: $trailblazeSessionId ${sessionDir.canonicalPath}")
      val fileWatchService = FileWatchService(
        dirToWatch = sessionDir,
        onFileChange = { changeType: FileWatchService.ChangeType, fileChanged: File ->
          println("LOGLISTENER - $changeType $fileChanged")
          if (fileChanged.extension == "json") {
            fileOperationScope.launch {
              try {
                val logsForSession = getCachedLogsForSession(trailblazeSessionId).sortedBy { it.timestamp }
                if (logsForSession.size == 1) {
                  trailblazeSessionListener.onSessionStarted()
                  return@launch
                }
                val mostRecentLog = logsForSession.lastOrNull()

                if (mostRecentLog is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
                  if (mostRecentLog.sessionStatus is SessionStatus.Ended) {
                    trailblazeSessionListener.onSessionEnded()
                    stopWatching(trailblazeSessionId)
                    return@launch
                  }
                }

                if (mostRecentLog != null) {
                  trailblazeSessionListener.onUpdate("Session Updated: ${mostRecentLog::class.java.simpleName} ${mostRecentLog.timestamp}")
                  return@launch
                }
              } catch (e: Exception) {
                println("Error processing session update for $trailblazeSessionId: ${e.message}")
                e.printStackTrace()
              }
            }
          }
        },
        debounceDelayMs = 300L, // Shorter debounce for session events
        maxEventsPerSecond = 5, // Lower rate limit for sessions
      )
      fileWatcherByTrailblazeSession[trailblazeSessionId] = fileWatchService
      fileWatchService.startWatching()
    } else {
      error("Already watching trailblaze session: $trailblazeSessionId. This method would need to be supported to allow multiple listeners for the same session.")
    }
  }

  private fun getLogFilesForSession(sessionId: String): List<File> = File(logsDir, sessionId)
    .listFiles()
    ?.filter { it.extension == "json" }
    ?: emptyList()

  /**
   * Returns a list of logs for the given session ID with caching to avoid redundant file reads.
   * If the session ID is null or the session directory does not exist, an empty list is returned.
   */
  fun getCachedLogsForSession(sessionId: String?): List<TrailblazeLog> {
    if (sessionId == null) return emptyList()

    val sessionDir = File(logsDir, sessionId)
    if (!sessionDir.exists()) return emptyList()

    val lastModified = sessionDir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L

    // Check cache
    val cached = sessionLogsCache[sessionId]
    if (cached != null && cached.first >= lastModified) {
      return cached.second
    }

    // Refresh cache
    val logs = getLogsForSession(sessionId)
    sessionLogsCache[sessionId] = Pair(lastModified, logs)
    return logs
  }

  /**
   * Returns a list of logs for the given session ID.
   * If the session ID is null or the session directory does not exist, an empty list is returned.
   */
  fun getLogsForSession(sessionId: String?): List<TrailblazeLog> {
    if (sessionId != null) {
      val jsonFiles = getLogFilesForSession(sessionId)
      val logs: List<TrailblazeLog> = jsonFiles.mapNotNull {
        parseTrailblazeLogFromFile(it)
      }.sortedBy { it.timestamp }
      return logs
    }
    return emptyList()
  }

  private fun parseTrailblazeLogFromFile(logFile: File): TrailblazeLog? = try {
    TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(
      logFile.readText(),
    )
  } catch (e: Exception) {
    if (!logFile.name.endsWith("trace.json")) {
      println("Could Not Parse Log: ${logFile.absolutePath}.  ${e.stackTraceToString()}")
    }
    null
  }

  fun deleteLogsForSession(sessionId: String) {
    val sessionDir = File(logsDir, sessionId)
    if (sessionDir.exists()) {
      sessionDir.deleteRecursively()
    }
  }

  /**
   * Clears all logs in the logs directory.
   * This will delete all session directories and their contents.
   */
  fun clearLogs() {
    if (logsDir.exists()) {
      logsDir.listFiles().filter { it.isDirectory }.forEach {
        it.deleteRecursively()
      }
    }
  }

  /**
   * Returns the directory for the given session, creating it if it does not exist.
   */
  fun getSessionDir(session: String): File {
    if (!logsDir.exists()) {
      logsDir.mkdirs()
    }
    val sessionDir = File(logsDir, session)
    if (!sessionDir.exists()) {
      sessionDir.mkdirs()
    }
    return sessionDir
  }

  /**
   * Adds a listener for session list changes.
   */
  fun addSessionListListener(listener: SessionListListener) {
    sessionListListeners.add(listener)

    // Start watching if this is the first listener
    if (sessionListListeners.size == 1) {
      startWatchingSessionList()
    }
  }

  /**
   * Removes a listener for session list changes.
   */
  fun removeSessionListListener(listener: SessionListListener) {
    sessionListListeners.remove(listener)

    // Stop watching if no more listeners
    if (sessionListListeners.isEmpty()) {
      stopWatchingSessionList()
    }
  }

  /**
   * Starts watching the logs directory for session additions/removals.
   */
  private fun startWatchingSessionList() {
    if (sessionListWatcher == null) {
      println("SESSIONLISTENER - Starting to watch session list: ${logsDir.canonicalPath}")
      sessionListWatcher = FileWatchService(
        dirToWatch = logsDir,
        onFileChange = { changeType: FileWatchService.ChangeType, fileChanged: File ->
          println("SESSIONLISTENER - $changeType $fileChanged")

          fileOperationScope.launch {
            try {
              val currentSessionIds = getSessionIds().toSet()
              val previousSessionIds = cachedSessionIds

              // Only proceed if the session list actually changed
              if (currentSessionIds == previousSessionIds) {
                return@launch
              }

              val addedSessions = currentSessionIds - previousSessionIds
              // Detect removals
              val removedSessions = previousSessionIds - currentSessionIds

              if (addedSessions.isNotEmpty()) {
                // Detect additions
                addedSessions.forEach { sessionId ->
                  sessionListListeners.forEach { listener ->
                    listener.onSessionAdded(sessionId)
                  }
                }
              }

              if (removedSessions.isNotEmpty()) {
                removedSessions.forEach { sessionId ->
                  // Clear cache for removed sessions
                  sessionLogsCache.remove(sessionId)
                  sessionListListeners.forEach { listener ->
                    listener.onSessionRemoved(sessionId)
                  }
                }
              }

              // Update cache
              cachedSessionIds = currentSessionIds
            } catch (e: Exception) {
              println("Error processing session list changes: ${e.message}")
              e.printStackTrace()
            }
          }
        },
        debounceDelayMs = 1000L, // Longer debounce for session list changes
        maxEventsPerSecond = 3, // Very low rate limit for session list
      )
      sessionListWatcher?.startWatching()
    }
  }

  /**
   * Stops watching the logs directory for session additions/removals.
   */
  private fun stopWatchingSessionList() {
    sessionListWatcher?.stopWatching()
    sessionListWatcher = null
  }

  override suspend fun getSessionIdsAsync(): List<String> = getSessionIds()

  override suspend fun getLogsForSessionAsync(sessionId: String?): List<TrailblazeLog> = getLogsForSession(sessionId)
  override suspend fun getSessionInfoAsync(sessionId: String): SessionInfo? = getSessionInfo(sessionId)

  override suspend fun getSessionRecordingYaml(sessionId: String): String = getLogsForSessionAsync(sessionId).generateRecordedYaml()

  fun getSessionInfo(sessionId: String): SessionInfo? {
    val logFiles = getLogFilesForSession(sessionId)
    if (logFiles.isEmpty()) {
      return null
    }

    // Parse all log files once and filter for session status logs
    val sessionStatusLogs = logFiles
      .sortedBy { it.lastModified() }
      .mapNotNull { file ->
        val log = parseTrailblazeLogFromFile(file)
        if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
          Pair(file.lastModified(), log)
        } else {
          null
        }
      }

    if (sessionStatusLogs.isEmpty()) {
      return null
    }

    val sessionStartedLog = sessionStatusLogs
      .firstOrNull { it.second.sessionStatus is SessionStatus.Started }
      ?.second

    val lastSessionStatusLog = sessionStatusLogs
      .maxByOrNull { it.first }
      ?.second

    // Check if session is abandoned (in progress but >2 mins since last activity)
    if (sessionStartedLog != null && lastSessionStatusLog != null) {
      val lastStatus = lastSessionStatusLog.sessionStatus

      // Only check for abandonment if session is still in Started state
      if (lastStatus is SessionStatus.Started) {
        val currentTimeMs = Clock.System.now().toEpochMilliseconds()

        // Find the most recent log of any type to determine last activity
        val allLogs = getLogsForSession(sessionId)
        val mostRecentLog = allLogs.maxByOrNull { it.timestamp.toEpochMilliseconds() }

        if (mostRecentLog != null) {
          val timeSinceLastActivity = currentTimeMs - mostRecentLog.timestamp.toEpochMilliseconds()
          val twoMinutesMs = DEFAULT_TIMEOUT

          if (timeSinceLastActivity > twoMinutesMs) {
            // Session is abandoned - generate a fake end log
            val abandonedTimestamp = kotlinx.datetime.Instant.fromEpochMilliseconds(
              mostRecentLog.timestamp.toEpochMilliseconds() + twoMinutesMs,
            )
            val durationMs = abandonedTimestamp.toEpochMilliseconds() - sessionStartedLog.timestamp.toEpochMilliseconds()

            val abandonedLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
              sessionStatus = SessionStatus.Ended.TimeoutReached(
                durationMs = durationMs,
                message = "Session was abandoned after ${timeSinceLastActivity.milliseconds.inWholeMinutes} minutes of inactivity",
              ),
              session = sessionId,
              timestamp = abandonedTimestamp,
            )

            // Return session info with Abandoned status
            val startedStatus: SessionStatus.Started = sessionStartedLog.sessionStatus as SessionStatus.Started
            return SessionInfo(
              sessionId = sessionStartedLog.session,
              timestamp = sessionStartedLog.timestamp,
              latestStatus = abandonedLog.sessionStatus,
              testName = startedStatus.testMethodName,
              testClass = startedStatus.testClassName,
              trailblazeDeviceInfo = startedStatus.trailblazeDeviceInfo,
              trailConfig = startedStatus.trailConfig,
              durationMs = durationMs,
            )
          }
        }
      }
    }

    return if (sessionStartedLog != null && lastSessionStatusLog != null) {
      val startedStatus: SessionStatus.Started = sessionStartedLog.sessionStatus as SessionStatus.Started
      val durationMs = lastSessionStatusLog.timestamp.toEpochMilliseconds() - sessionStartedLog.timestamp.toEpochMilliseconds()
      SessionInfo(
        sessionId = sessionStartedLog.session,
        timestamp = sessionStartedLog.timestamp,
        latestStatus = lastSessionStatusLog.sessionStatus,
        testName = startedStatus.testMethodName,
        testClass = startedStatus.testClassName,
        trailblazeDeviceInfo = startedStatus.trailblazeDeviceInfo,
        trailConfig = startedStatus.trailConfig,
        durationMs = durationMs,
      )
    } else {
      null
    }
  }

  private val countBySession = mutableMapOf<String, Int>()

  private fun getNextLogCountForSession(sessionId: String): Int = synchronized(countBySession) {
    val newValue = (countBySession[sessionId] ?: 0) + 1
    countBySession[sessionId] = newValue
    newValue
  }

  /**
   * If the number has 3 or more digits, it will just use its natural width, so 1000 stays 1000 (4 digits).
   */
  private fun formatNumber(num: Int): String = String.format("%03d", num)

  /**
   * @return the file where the log was written
   */
  fun saveLogToDisk(logEvent: TrailblazeLog): File {
    val logCount = getNextLogCountForSession(logEvent.session)
    val jsonLogFilename = File(
      getSessionDir(session = logEvent.session),
      "${formatNumber(logCount)}_${logEvent::class.java.simpleName}.json",
    )
    jsonLogFilename.writeText(
      TrailblazeJsonInstance.encodeToString<TrailblazeLog>(
        logEvent,
      ),
    )
    // Invalidate cache for this session since we wrote a new log
    sessionLogsCache.remove(logEvent.session)
    return jsonLogFilename
  }

  fun saveScreenshotToDisk(sessionId: String, fileName: String, bytes: ByteArray) {
    val sessionDir = getSessionDir(sessionId)
    val screenshotFile = File(sessionDir, fileName)
    println("Writing Screenshot to ${screenshotFile.absolutePath}")
    screenshotFile.writeBytes(bytes)
  }

  /**
   * Returns a list of image files (PNG and JPEG) for the given session.
   */
  fun getImagesForSession(sessionId: String): List<File> {
    val sessionDir = File(logsDir, sessionId)
    if (!sessionDir.exists()) return emptyList()
    return sessionDir.listFiles()?.filter {
      it.extension == "png" || it.extension == "jpg" || it.extension == "jpeg"
    }?.sortedBy { it.name }
      ?: emptyList()
  }

  /**
   * Finds the log file on disk for a specific TrailblazeLog.
   * Returns the file if found, or null if not found.
   *
   * The log file is identified by matching the session ID and log class simple name.
   * Since multiple logs of the same type can exist, we match by timestamp as well.
   */
  fun findLogFile(log: TrailblazeLog): File? {
    val sessionDir = File(logsDir, log.session)
    if (!sessionDir.exists()) return null

    val logClassName = log::class.java.simpleName
    val logFiles = sessionDir.listFiles()?.filter { file ->
      file.extension == "json" && file.name.endsWith("_$logClassName.json")
    } ?: return null

    // Parse each matching file to find the one with the exact timestamp
    for (file in logFiles) {
      try {
        val parsedLog = parseTrailblazeLogFromFile(file)
        if (parsedLog != null &&
          parsedLog.timestamp == log.timestamp &&
          parsedLog::class.java.simpleName == logClassName
        ) {
          return file
        }
      } catch (e: Exception) {
        // Continue searching if this file can't be parsed
        continue
      }
    }

    return null
  }
}
