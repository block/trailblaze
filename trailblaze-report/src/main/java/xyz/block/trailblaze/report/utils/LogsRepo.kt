package xyz.block.trailblaze.report.utils
 
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.inProgress
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import java.io.File
import kotlin.time.Duration.Companion.milliseconds

private typealias TrailblazeSessionId = SessionId

const val DEFAULT_TIMEOUT = 120000L

class LogsRepo(val logsDir: File) : TrailblazeLogsDataProvider {

  // Create a dedicated coroutine scope for background file operations
  private val fileOperationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Reactive StateFlows for sessions and logs
  private val _sessionsFlow = MutableStateFlow<List<SessionId>>(emptyList())
  val sessionsFlow: StateFlow<List<SessionId>> = _sessionsFlow.asStateFlow()

  private val _sessionLogsFlows = mutableMapOf<SessionId, MutableStateFlow<List<TrailblazeLog>>>()

  // Reactive StateFlow for SessionInfo objects (auto-updates when sessions or their logs change)
  private val _sessionInfoFlow = MutableStateFlow<List<SessionInfo>>(emptyList())
  val sessionInfoFlow: StateFlow<List<SessionInfo>> = _sessionInfoFlow.asStateFlow()

  // Filtered flows for common use cases

  /**
   * Flow of only active/in-progress sessions (status is Started).
   * Automatically updates when sessions start or complete.
   */
  val activeSessionsFlow: StateFlow<List<SessionInfo>> = sessionInfoFlow
    .map { sessions -> sessions.filter { it.latestStatus.inProgress } }
    .stateIn(fileOperationScope, SharingStarted.Eagerly, emptyList())

  /**
   * Flow of only completed sessions (status is Ended, Cancelled, MaxCallsLimitReached, or Timeout).
   */
  val completedSessionsFlow: StateFlow<List<SessionInfo>> = sessionInfoFlow
    .map { sessions -> sessions.filter { !it.latestStatus.inProgress } }
    .stateIn(fileOperationScope, SharingStarted.Eagerly, emptyList())

  /**
   * Flow of count of active sessions.
   */
  val activeSessionCountFlow: StateFlow<Int> = activeSessionsFlow
    .map { it.size }
    .stateIn(fileOperationScope, SharingStarted.Eagerly, 0)

  // Track jobs watching individual session logs for SessionInfo updates
  private val sessionInfoWatcherJobs = mutableMapOf<SessionId, Job>()

  init {
    // Ensure the logs directory exists
    logsDir.mkdirs()

    // Start watching session list immediately for reactive updates
    startWatchingSessionList()

    // Initialize with current sessions
    _sessionsFlow.value = getSessionIds()

    // Initialize SessionInfo flow and keep it updated
    fileOperationScope.launch {
      sessionsFlow.collect { sessionIds ->
        // Update SessionInfo for all sessions
        _sessionInfoFlow.value = sessionIds.mapNotNull { getSessionInfo(it) }

        // Cancel watchers for removed sessions
        val removedSessions = sessionInfoWatcherJobs.keys - sessionIds.toSet()
        removedSessions.forEach { sessionId ->
          sessionInfoWatcherJobs[sessionId]?.cancel()
          sessionInfoWatcherJobs.remove(sessionId)
        }

        // Start watching new sessions' logs to detect status changes
        sessionIds.forEach { sessionId ->
          if (sessionId !in sessionInfoWatcherJobs) {
            sessionInfoWatcherJobs[sessionId] = fileOperationScope.launch {
              getSessionLogsFlow(sessionId).collect {
                // When any session's logs change, refresh all SessionInfo
                _sessionInfoFlow.value = sessionsFlow.value.mapNotNull { getSessionInfo(it) }
              }
            }
          }
        }
      }
    }
  }

  /**
   * A map of trailblaze session IDs to their corresponding file watcher services.
   */
  private val fileWatcherByTrailblazeSession = mutableMapOf<TrailblazeSessionId?, FileWatchService>()

  /**
   * File watcher for the logs directory to monitor session creation/deletion.
   */
  private var sessionListWatcher: FileWatchService? = null

  // Cache for session logs to avoid redundant file reads
  private val sessionLogsCache = mutableMapOf<SessionId, Pair<Long, List<TrailblazeLog>>>()

  fun getSessionDirs(): List<File> =
    logsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

  fun getSessionIds(): List<SessionId> = getSessionDirs().map { SessionId(it.name) }

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
   * Stops all file watchers and cleans up resources.
   * Call this when you're done with the LogsRepo to allow the JVM to exit cleanly.
   */
  fun close() {
    println("[LogsRepo] Cleaning up resources...")

    // Cancel the coroutine scope first to stop all background operations
    // This prevents concurrent modifications while we clean up
    fileOperationScope.cancel()

    // Stop session list watcher
    sessionListWatcher?.stopWatching()
    sessionListWatcher = null

    // Stop all session watchers - copy values first to avoid ConcurrentModificationException
    val watchersToStop = fileWatcherByTrailblazeSession.values.toList()
    watchersToStop.forEach { it.stopWatching() }
    fileWatcherByTrailblazeSession.clear()

    // Cancel all session info watcher jobs - copy values first to avoid ConcurrentModificationException
    val jobsToCancel = sessionInfoWatcherJobs.values.toList()
    jobsToCancel.forEach { it.cancel() }
    sessionInfoWatcherJobs.clear()

    println("[LogsRepo] Cleanup complete")
  }



  private fun getLogFilesForSession(sessionId: SessionId): List<File> = File(logsDir, sessionId.value)
    .listFiles()
    ?.filter { it.extension == "json" }
    ?: emptyList()

  /**
   * Returns a list of logs for the given session ID with caching to avoid redundant file reads.
   * If the session ID is null or the session directory does not exist, an empty list is returned.
   */
  fun getCachedLogsForSession(sessionId: SessionId?): List<TrailblazeLog> {
    if (sessionId == null) return emptyList()

    val sessionDir = File(logsDir, sessionId.value)
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
  fun getLogsForSession(sessionId: SessionId?): List<TrailblazeLog> {
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

  fun deleteLogsForSession(sessionId: SessionId) {
    val sessionDir = File(logsDir, sessionId.value)
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
  fun getSessionDir(session: SessionId): File {
    if (!logsDir.exists()) {
      logsDir.mkdirs()
    }
    val sessionDir = File(logsDir, session.value)
    if (!sessionDir.exists()) {
      sessionDir.mkdirs()
    }
    return sessionDir
  }

  /**
   * Gets a StateFlow for logs of a specific session.
   * The flow will be updated reactively as new logs are written.
   */
  fun getSessionLogsFlow(sessionId: SessionId): StateFlow<List<TrailblazeLog>> {
    return _sessionLogsFlows.getOrPut(sessionId) {
      MutableStateFlow(getCachedLogsForSession(sessionId)).also { flow ->
        // Start watching this session to update the flow
        startWatchingSessionForFlow(sessionId, flow)
      }
    }
  }

  /**
   * Starts watching the logs directory for session additions/removals.
   * Emits updates to sessionsFlow.
   */
  private fun startWatchingSessionList() {
    if (sessionListWatcher == null) {
      println("[LogsRepo] Starting session list watcher")
      val watcher = FileWatchService(
        dirToWatch = logsDir,
        debounceDelayMs = 100L, // Faster for session list updates (especially status changes)
      )

      sessionListWatcher = watcher

      // Start watching first, then collect from the Flow
      sessionListWatcher?.startWatching()

      // Collect from the Flow and process events in a separate coroutine
      fileOperationScope.launch {
        try {
          watcher.fileChanges.collect { event ->
            val (changeType, fileChanged) = event
            println("[LogsRepo] Session list event: $changeType ${fileChanged.name}")

            try {
              val currentSessionIds = getSessionIds()
              val previousSessionIds = _sessionsFlow.value

              // Only proceed if the session list actually changed
              if (currentSessionIds.toSet() == previousSessionIds.toSet()) {
                return@collect
              }

              val removedSessions = previousSessionIds.toSet() - currentSessionIds.toSet()

              // Clear cache for removed sessions
              removedSessions.forEach { sessionId ->
                sessionLogsCache.remove(sessionId)
                _sessionLogsFlows.remove(sessionId)
              }

              // Update the flow with new session list
              _sessionsFlow.value = currentSessionIds
            } catch (e: Exception) {
              println("[LogsRepo] Error processing session list event: ${e.message}")
              e.printStackTrace()
            }
          }
        } catch (e: Exception) {
          println("[LogsRepo] Flow collection ended for session list: ${e.message}")
        }
      }
    }
  }

  /**
   * Starts watching a specific session and updates its StateFlow with new logs.
   */
  private fun startWatchingSessionForFlow(sessionId: SessionId, flow: MutableStateFlow<List<TrailblazeLog>>) {
    if (fileWatcherByTrailblazeSession[sessionId] == null) {
      val sessionDir = getSessionDir(sessionId)
      println("[LogsRepo] Starting session watcher for flow: $sessionId")
      val fileWatchService = FileWatchService(
        dirToWatch = sessionDir,
        debounceDelayMs = 50L, // Faster for immediate UI updates (especially cancellation)
      )

      fileWatcherByTrailblazeSession[sessionId] = fileWatchService
      fileWatchService.startWatching()

      fileOperationScope.launch {
        try {
          fileWatchService.fileChanges.collect { event ->
            val (changeType, fileChanged) = event
            println("[LogsRepo] Session event received: $changeType ${fileChanged.name} (session: $sessionId)")
            if (fileChanged.extension == "json") {
              try {
                val logs = getCachedLogsForSession(sessionId).sortedBy { it.timestamp }
                flow.value = logs
              } catch (e: Exception) {
                println("[LogsRepo] Error processing session event for $sessionId: ${e.message}")
                e.printStackTrace()
              }
            }
          }
        } catch (e: Exception) {
          println("[LogsRepo] Flow collection ended for session $sessionId: ${e.message}")
        }
      }
    }
  }

  override suspend fun getSessionIdsAsync(): List<SessionId> = getSessionIds()

  override suspend fun getLogsForSessionAsync(sessionId: SessionId?): List<TrailblazeLog> = getLogsForSession(sessionId)
  override suspend fun getSessionInfoAsync(sessionName: SessionId): SessionInfo? = getSessionInfo(sessionName)

  override suspend fun getSessionRecordingYaml(sessionId: SessionId): String =
    getLogsForSessionAsync(sessionId).generateRecordedYaml()

  fun getSessionInfo(sessionId: SessionId): SessionInfo? {
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
              trailblazeDeviceId = startedStatus.trailblazeDeviceId,
              trailConfig = startedStatus.trailConfig,
              durationMs = durationMs,
              trailFilePath = startedStatus.trailFilePath,
              hasRecordedSteps = startedStatus.hasRecordedSteps,
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
        trailblazeDeviceId = startedStatus.trailblazeDeviceId,
        trailConfig = startedStatus.trailConfig,
        durationMs = durationMs,
        trailFilePath = startedStatus.trailFilePath,
        hasRecordedSteps = startedStatus.hasRecordedSteps,
      )
    } else {
      null
    }
  }

  private val countBySession = mutableMapOf<SessionId, Int>()

  private fun getNextLogCountForSession(sessionId: SessionId): Int = synchronized(countBySession) {
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

  fun saveScreenshotToDisk(screenshot: TrailblazeScreenStateLog) {
    screenshot.screenState.screenshotBytes?.let { bytes ->
      val sessionDir = getSessionDir(screenshot.sessionId)
      val screenshotFile = File(sessionDir, screenshot.fileName)
      println("Writing Screenshot to ${screenshotFile.absolutePath}")
      screenshotFile.writeBytes(bytes)
    }
  }

  /**
   * Returns a list of image files (PNG and JPEG) for the given session.
   */
  fun getImagesForSession(sessionId: SessionId): List<File> {
    val sessionDir = File(logsDir, sessionId.value)
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
    val sessionDir = File(logsDir, log.session.value)
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
