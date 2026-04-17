package xyz.block.trailblaze.report.utils

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.datetime.Clock
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.TrailblazeScreenStateLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.isInProgress
import java.io.File
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import xyz.block.trailblaze.util.Console

private typealias TrailblazeSessionId = SessionId

const val DEFAULT_TIMEOUT = 120000L

class LogsRepo(
  val logsDir: File,
  /**
   * Whether we should monitor the filesystem for changes, or just do a single read.
   *
   * Single read is good for analyzing files on disk that won't change.
   */
  private val watchFileSystem: Boolean = true,
  /**
   * When true, all write operations are no-ops. Reads and reactive flows still work normally,
   * so existing sessions (e.g. fixture sessions) remain visible. Use this with [--no-logging]
   * to prevent test runs from polluting the session list.
   */
  val readOnly: Boolean = false,
  /**
   * Cost enrichment function called on every log write and read. Recalculates LLM request
   * costs using the host's pricing config, ensuring on-device logs get accurate pricing
   * regardless of what pricing data was available on the device.
   *
   * Defaults to identity (no enrichment) for backward compatibility.
   */
  private val costEnricher: (TrailblazeLog) -> TrailblazeLog = { it },
) : TrailblazeLogsDataProvider {

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
    .map { sessions -> sessions.filter { it.latestStatus.isInProgress } }
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

    // Initialize with current sessions
    _sessionsFlow.value = getSessionIds()

    if (watchFileSystem) {
      // Start watching session list immediately for reactive updates
      startWatchingSessionList()

      // Initialize SessionInfo flow and keep it updated.
      // Only create file watchers for in-progress sessions to avoid the overhead
      // of watching hundreds of completed historical session directories.
      fileOperationScope.launch {
        sessionsFlow.collect { sessionIds ->
          // Read session info: use direct disk read (no watcher) for initial scan,
          // cached flow (with watcher) for sessions we're already tracking.
          val sessionInfos = sessionIds.mapNotNull { sessionId ->
            if (sessionId in sessionInfoWatcherJobs) {
              // Already watching — use the cached/reactive path
              getSessionInfo(sessionId)
            } else {
              // Not yet watching — read directly from disk without creating a watcher
              getSessionInfoDirect(sessionId)
            }
          }
          _sessionInfoFlow.value = sessionInfos

          // Cancel watchers for removed sessions
          val removedSessions = sessionInfoWatcherJobs.keys - sessionIds.toSet()
          removedSessions.forEach { sessionId ->
            sessionInfoWatcherJobs[sessionId]?.cancel()
            sessionInfoWatcherJobs.remove(sessionId)
            fileWatcherByTrailblazeSession.remove(sessionId)?.stopWatching()
            _sessionLogsFlows.remove(sessionId)
          }

          // Only start watching sessions that are in-progress (active).
          // Completed sessions' logs won't change, so no need to watch them.
          val activeSessionIds = sessionInfos
            .filter { it.latestStatus.isInProgress }
            .map { it.sessionId }
            .toSet()

          activeSessionIds.forEach { sessionId ->
            if (sessionId !in sessionInfoWatcherJobs) {
              ensureWatchingSession(sessionId)
              sessionInfoWatcherJobs[sessionId] = fileOperationScope.launch {
                getSessionLogsFlow(sessionId).collect {
                  // When an active session's logs change, refresh SessionInfo
                  _sessionInfoFlow.value = sessionsFlow.value.mapNotNull { sid ->
                    if (sid in sessionInfoWatcherJobs) getSessionInfo(sid) else getSessionInfoDirect(sid)
                  }
                }
              }
            }
          }

          // Stop watching sessions that have completed since we last checked
          val completedSessions = sessionInfoWatcherJobs.keys - activeSessionIds
          completedSessions.forEach { sessionId ->
            sessionInfoWatcherJobs[sessionId]?.cancel()
            sessionInfoWatcherJobs.remove(sessionId)
            fileWatcherByTrailblazeSession.remove(sessionId)?.stopWatching()
            // Keep _sessionLogsFlows for completed sessions (cached data is still useful)
          }
        }
      }
    } else {
      // Single read mode: read all session logs once and cache them.
      // This avoids redundant disk I/O in the main loop and makes the report
      // resilient to transient I/O failures after initialization.
      _sessionInfoFlow.value = _sessionsFlow.value.mapNotNull { sessionId ->
        val logs = getLogsForSession(sessionId)
        // Cache the logs so getCachedLogsForSession/getSessionLogsFlow can reuse them
        _sessionLogsFlows[sessionId] = MutableStateFlow(logs)
        buildSessionInfo(logs)
      }
      Console.log("[LogsRepo] Initialized in single-read mode with ${_sessionsFlow.value.size} sessions")
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

  fun getSessionDirs(): List<File> =
    logsDir.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name } ?: emptyList()

  fun getSessionIds(): List<SessionId> = getSessionDirs().map { SessionId(it.name) }

  /**
   * Stops all file watchers and cleans up resources.
   * Call this when you're done with the LogsRepo to allow the JVM to exit cleanly.
   */
  fun close() {
    Console.log("[LogsRepo] Cleaning up resources...")

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

    Console.log("[LogsRepo] Cleanup complete")
  }


  /**
   * Reads log files from disk for the given session.
   *
   * Log filenames use two conventions:
   * - Numbered: `001_TrailblazeSessionStatusChangeLog.json` (from on-device logging)
   * - Hex-hashed: `a1b2c3d4_TrailblazeSessionStatusChangeLog.json`
   *
   * We filter to JSON files whose name starts with a hex character to match both conventions
   * while excluding non-log files like `recording.trail.yaml`.
   *
   * WARNING: This performs disk I/O on every call. Prefer using [getCachedLogsForSession]
   * or [getSessionLogsFlow] for cached/reactive access to avoid repeated disk reads.
   */
  private fun readLogFilesFromDisk(sessionId: SessionId): List<File> = File(logsDir, sessionId.value)
    .listFiles()
    ?.filter { it.extension == "json" && it.name.first().isHexDigit() && it.name != "capture_metadata.json" }
    ?: emptyList()

  private fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f'

  /**
   * Returns a list of logs for the given session ID with caching via the reactive flow.
   * If the session ID is null or the session directory does not exist, an empty list is returned.
   * Creates and populates the flow cache if it doesn't exist yet.
   */
  fun getCachedLogsForSession(sessionId: SessionId?): List<TrailblazeLog> {
    if (sessionId == null) return emptyList()

    // Return from cache if already populated (avoids disk check when volume may be unavailable)
    _sessionLogsFlows[sessionId]?.let { return it.value }

    val sessionDir = File(logsDir, sessionId.value)
    if (!sessionDir.exists()) return emptyList()

    // Get or create the flow (which will read from disk and cache if needed)
    return getSessionLogsFlow(sessionId).value
  }

  /**
   * Returns a list of logs for the given session ID.
   * If the session ID is null or the session directory does not exist, an empty list is returned.
   */
  fun getLogsForSession(sessionId: SessionId?): List<TrailblazeLog> {
    if (sessionId != null) {
      val jsonFiles = readLogFilesFromDisk(sessionId)
      val logs: List<TrailblazeLog> = jsonFiles.mapNotNull {
        parseTrailblazeLogFromFile(it)
      }.sortedBy { it.timestamp }
      return logs
    }
    return emptyList()
  }

  private fun parseTrailblazeLogFromFile(logFile: File): TrailblazeLog? = try {
    val log = TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(
      logFile.readText(),
    )
    // Always recalculate costs from the host's pricing config on read
    costEnricher(log)
  } catch (e: Exception) {
    if (!logFile.name.endsWith("trace.json")) {
      Console.log("Could Not Parse Log: ${logFile.absolutePath}.  ${e.stackTraceToString()}")
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
   * When [readOnly] is true the directory is returned but NOT created.
   */
  fun getSessionDir(session: SessionId): File {
    val sessionDir = File(logsDir, session.value)
    if (!readOnly) {
      if (!logsDir.exists()) logsDir.mkdirs()
      if (!sessionDir.exists()) sessionDir.mkdirs()
    }
    return sessionDir
  }

  /**
   * Gets a StateFlow for logs of a specific session.
   * The flow will be updated reactively as new logs are written.
   */
  fun getSessionLogsFlow(sessionId: SessionId): StateFlow<List<TrailblazeLog>> {
    return _sessionLogsFlows.getOrPut(sessionId) {
      // Read from disk to initialize the flow (only happens once per session).
      // File watching is NOT started here — only active sessions get watchers,
      // started explicitly via ensureWatchingSession().
      MutableStateFlow(getLogsForSession(sessionId))
    }
  }

  /**
   * Suspends for up to [timeout] (default 5 seconds) waiting for a log of type [T] matching [predicate]
   * to appear in the session logs.
   *
   * Usage: `awaitLog<TrailblazeLog.AgentDriverLog>(sessionId) { it.successful }`
   *
   * @param sessionId The session to watch logs for
   * @param timeout Maximum time to wait for the log (default 5 seconds)
   * @param skipExisting If true, only consider logs that arrive after this call (ignore existing logs)
   * @param predicate Returns true if the log matches
   * @return The matching log, or null if timeout is reached
   */
  suspend inline fun <reified T : TrailblazeLog> awaitLog(
    sessionId: SessionId,
    timeout: Duration = 30.seconds,
    skipExisting: Boolean = true,
    noinline predicate: (T) -> Boolean = { true }
  ): T? = awaitLog(sessionId, T::class, timeout, skipExisting, predicate)

  /**
   * Suspends for up to [timeout] (default 5 seconds) waiting for a log of type [T] matching [predicate]
   * to appear in the session logs.
   *
   * @param sessionId The session to watch logs for
   * @param expectedType The expected log type class
   * @param timeout Maximum time to wait for the log (default 5 seconds)
   * @param skipExisting If true, only consider logs that arrive after this call (ignore existing logs)
   * @param predicate Returns true if the log matches
   * @return The matching log, or null if timeout is reached
   */
  @Suppress("UNCHECKED_CAST")
  suspend fun <T : TrailblazeLog> awaitLog(
    sessionId: SessionId,
    expectedType: KClass<T>,
    timeout: Duration = 5.seconds,
    skipExisting: Boolean = true,
    predicate: (T) -> Boolean = { true }
  ): T? {
    val startIndex = if (skipExisting) getSessionLogsFlow(sessionId).value.size else 0
    ensureWatchingSession(sessionId)

    return withTimeoutOrNull(timeout) {
      getSessionLogsFlow(sessionId)
        .mapNotNull { logs ->
          logs.drop(startIndex)
            .asReversed()
            .firstOrNull { expectedType.isInstance(it) && predicate(it as T) } as? T
        }
        .first()
    }
  }

  /**
   * Ensures a file watcher is running for the given session so its flow gets reactive updates.
   * Creates the flow if it doesn't exist yet. No-op if a watcher is already active or if
   * file system watching is disabled.
   */
  private fun ensureWatchingSession(sessionId: SessionId) {
    val flow = _sessionLogsFlows.getOrPut(sessionId) {
      MutableStateFlow(getLogsForSession(sessionId))
    }
    startWatchingSessionForFlow(sessionId, flow)
  }

  /**
   * Starts watching the logs directory for session additions/removals.
   * Emits updates to sessionsFlow.
   */
  private fun startWatchingSessionList() {
    if (!watchFileSystem) {
      Console.log("[LogsRepo] File system watching disabled, skipping session list watcher")
      return
    }
    
    if (sessionListWatcher == null) {
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
            Console.log("[LogsRepo] Session list event: $changeType ${fileChanged.name}")

            try {
              val currentSessionIds = getSessionIds()
              val previousSessionIds = _sessionsFlow.value

              // Only proceed if the session list actually changed
              if (currentSessionIds.toSet() == previousSessionIds.toSet()) {
                return@collect
              }

              val removedSessions = previousSessionIds.toSet() - currentSessionIds.toSet()

              // Clean up flows for removed sessions
              removedSessions.forEach { sessionId ->
                _sessionLogsFlows.remove(sessionId)
              }

              // Update the flow with new session list
              _sessionsFlow.value = currentSessionIds
            } catch (e: Exception) {
              Console.log("[LogsRepo] Error processing session list event: ${e.message}")
              e.printStackTrace()
            }
          }
        } catch (e: Exception) {
          Console.log("[LogsRepo] Flow collection ended for session list: ${e.message}")
        }
      }
    }
  }

  /**
   * Starts watching a specific session and updates its StateFlow with new logs.
   */
  private fun startWatchingSessionForFlow(sessionId: SessionId, flow: MutableStateFlow<List<TrailblazeLog>>) {
    if (!watchFileSystem) {
      Console.log("[LogsRepo] File system watching disabled, skipping session watcher for: $sessionId")
      return
    }
    
    if (fileWatcherByTrailblazeSession[sessionId] == null) {
      val sessionDir = getSessionDir(sessionId)
      Console.log("[LogsRepo] Starting session watcher for flow: $sessionId")
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
            Console.log("[LogsRepo] Session event received: $changeType ${fileChanged.name} (session: $sessionId)")
            if (fileChanged.extension == "json") {
              try {
                // Read fresh from disk to update the flow (which serves as the cache)
                val logs = getLogsForSession(sessionId).sortedBy { it.timestamp }
                flow.value = logs
              } catch (e: Exception) {
                Console.log("[LogsRepo] Error processing session event for $sessionId: ${e.message}")
                e.printStackTrace()
              }
            }
          }
        } catch (e: Exception) {
          Console.log("[LogsRepo] Flow collection ended for session $sessionId: ${e.message}")
        }
      }
    }
  }

  override suspend fun getSessionIdsAsync(): List<SessionId> = getSessionIds()

  override suspend fun getLogsForSessionAsync(
    sessionId: SessionId?
  ): List<TrailblazeLog> = getCachedLogsForSession(sessionId)

  override suspend fun getSessionInfoAsync(sessionName: SessionId): SessionInfo? = getSessionInfo(sessionName)

  /**
   * Reads session info directly from disk without creating a file watcher.
   *
   * Use this for one-shot reads of completed/historical sessions where you don't
   * need reactive updates. This avoids the overhead of creating a [FileWatchService]
   * per session directory.
   *
   * @see getSessionInfo for the cached/reactive version
   */
  fun getSessionInfoDirect(sessionId: SessionId): SessionInfo? {
    val allLogs = getLogsForSession(sessionId)
    return buildSessionInfo(allLogs)
  }

  fun getSessionInfo(sessionId: SessionId): SessionInfo? {
    // Use cached logs from the flow if available, otherwise read from disk
    val allLogs = getCachedLogsForSession(sessionId)
    return buildSessionInfo(allLogs)
  }

  private fun buildSessionInfo(allLogs: List<TrailblazeLog>): SessionInfo? {
    if (allLogs.isEmpty()) {
      return null
    }

    // Filter for session status logs from the cached logs
    val sessionStatusLogs = allLogs
      .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .sortedBy { it.timestamp }

    if (sessionStatusLogs.isEmpty()) {
      return null
    }

    val sessionStartedLog = sessionStatusLogs
      .firstOrNull { it.sessionStatus is SessionStatus.Started }

    val lastSessionStatusLog = sessionStatusLogs
      .maxByOrNull { it.timestamp }

    val startedStatus = sessionStartedLog?.sessionStatus as? SessionStatus.Started

    // Check if session is abandoned (in progress but >2 mins since last activity)
    if (sessionStartedLog != null && lastSessionStatusLog != null) {
      val lastStatus = lastSessionStatusLog.sessionStatus

      // Only check for abandonment if session is still in Started state
      if (lastStatus is SessionStatus.Started) {
        val currentTimeMs = Clock.System.now().toEpochMilliseconds()

        // Find the most recent log of any type to determine last activity (already have allLogs)
        val mostRecentLog = allLogs.maxByOrNull { it.timestamp.toEpochMilliseconds() }

        if (mostRecentLog != null) {
          val timeSinceLastActivity = currentTimeMs - mostRecentLog.timestamp.toEpochMilliseconds()
          val twoMinutesMs = DEFAULT_TIMEOUT

          if (timeSinceLastActivity > twoMinutesMs) {
            // Session is abandoned - generate a fake end log
            val abandonedTimestamp = kotlinx.datetime.Instant.fromEpochMilliseconds(
              mostRecentLog.timestamp.toEpochMilliseconds() + twoMinutesMs,
            )
            val durationMs =
              abandonedTimestamp.toEpochMilliseconds() - sessionStartedLog.timestamp.toEpochMilliseconds()

            val abandonedSessionStatus = SessionStatus.Ended.TimeoutReached(
              durationMs = durationMs,
              message = "Session was abandoned after ${timeSinceLastActivity.milliseconds.inWholeMinutes} minutes of inactivity",
            )

            return SessionInfo(
              sessionId = sessionStartedLog.session,
              timestamp = sessionStartedLog.timestamp,
              latestStatus = abandonedSessionStatus,
              testName = startedStatus?.testMethodName,
              testClass = startedStatus?.testClassName,
              trailblazeDeviceInfo = startedStatus?.trailblazeDeviceInfo,
              trailblazeDeviceId = startedStatus?.trailblazeDeviceId,
              trailConfig = startedStatus?.trailConfig,
              durationMs = durationMs,
              trailFilePath = startedStatus?.trailFilePath,
              hasRecordedSteps = startedStatus?.hasRecordedSteps ?: false,
            )
          }
        }
      }
    }

    if (lastSessionStatusLog == null) {
      return null
    }

    // Use the first log's timestamp as fallback when there's no Started log
    // (e.g., on-device instrumentation sessions that only emit Ended logs)
    val firstLog = allLogs.first()
    val referenceLog = sessionStartedLog ?: firstLog
    val durationMs = lastSessionStatusLog.timestamp.toEpochMilliseconds() -
      referenceLog.timestamp.toEpochMilliseconds()

    return SessionInfo(
      sessionId = referenceLog.session,
      timestamp = referenceLog.timestamp,
      latestStatus = lastSessionStatusLog.sessionStatus,
      testName = startedStatus?.testMethodName,
      testClass = startedStatus?.testClassName,
      trailblazeDeviceInfo = startedStatus?.trailblazeDeviceInfo,
      trailblazeDeviceId = startedStatus?.trailblazeDeviceId,
      trailConfig = startedStatus?.trailConfig,
      durationMs = durationMs,
      trailFilePath = startedStatus?.trailFilePath,
      hasRecordedSteps = startedStatus?.hasRecordedSteps ?: false,
    )
  }

  private val countBySession = mutableMapOf<SessionId, Int>()

  // Sessions where we detected existing logs on first write (restarted mid-session)
  // This enables session resuming/continuation
  private val sessionsNeedingTimestamp = mutableSetOf<SessionId>()

  private fun getNextLogCountForSession(sessionId: SessionId): Int = synchronized(countBySession) {
    // On first access, check if there are existing logs (indicates restart mid-session)
    if (sessionId !in countBySession) {
      val existingLogs = readLogFilesFromDisk(sessionId)
      if (existingLogs.isNotEmpty()) {
        sessionsNeedingTimestamp.add(sessionId)
      }
    }
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
    if (readOnly) return File(logsDir, "noop")
    @Suppress("NAME_SHADOWING")
    val logEvent = costEnricher(logEvent)
    val logCount = getNextLogCountForSession(logEvent.session)
    // Only add timestamp if we restarted mid-session (to avoid filename collisions)
    val filename = if (logEvent.session in sessionsNeedingTimestamp) {
      val timestampMs = logEvent.timestamp.toEpochMilliseconds()
      "${formatNumber(logCount)}_${timestampMs}_${logEvent::class.java.simpleName}.json"
    } else {
      "${formatNumber(logCount)}_${logEvent::class.java.simpleName}.json"
    }
    val jsonLogFilename = File(
      getSessionDir(session = logEvent.session),
      filename,
    )
    jsonLogFilename.writeText(
      TrailblazeJsonInstance.encodeToString<TrailblazeLog>(
        logEvent,
      ),
    )
    // The flow will be updated automatically via the file watcher
    return jsonLogFilename
  }

  fun saveScreenshotToDisk(screenshot: TrailblazeScreenStateLog) {
    if (readOnly) return
    screenshot.screenState.screenshotBytes?.let { bytes ->
      val sessionDir = getSessionDir(screenshot.sessionId)
      val screenshotFile = File(sessionDir, screenshot.fileName)
      Console.log("Writing Screenshot to ${screenshotFile.absolutePath}")
      screenshotFile.writeBytes(bytes)
    }
  }

  /**
   * Saves raw screenshot bytes to disk and returns the filename.
   *
   * @param sessionId The session ID to save the screenshot under
   * @param bytes The screenshot bytes (PNG, JPEG, or WebP)
   * @param fileExtension The file extension (e.g. "png", "jpg", "webp")
   * @return The filename of the saved screenshot
   */
  fun saveScreenshotBytes(
    sessionId: SessionId,
    bytes: ByteArray,
    fileExtension: String = "png",
  ): String {
    if (readOnly) return "noop"
    val sessionDir = getSessionDir(sessionId)
    val timestamp = Clock.System.now().toEpochMilliseconds()
    val filename = "${sessionId.value}_${timestamp}.$fileExtension"
    val screenshotFile = File(sessionDir, filename)
    Console.log("Writing Screenshot to ${screenshotFile.absolutePath}")
    screenshotFile.writeBytes(bytes)
    return filename
  }

  /**
   * Returns a list of image files for the given session.
   */
  fun getImagesForSession(sessionId: SessionId): List<File> {
    val sessionDir = File(logsDir, sessionId.value)
    if (!sessionDir.exists()) return emptyList()
    return sessionDir.listFiles()?.filter {
      it.extension in setOf("png", "jpg", "jpeg", "webp")
    }?.sortedBy { it.name }
      ?: emptyList()
  }

  /**
   * Returns the screenshot File for a log that has a screenshot.
   * Returns null if the log has no screenshot or the file doesn't exist.
   *
   * @param log A log that implements both TrailblazeLog and HasScreenshot
   * @return The screenshot File, or null if unavailable
   */
  fun getScreenshotFile(log: TrailblazeLog): File? {
    if (log !is HasScreenshot) return null
    val screenshotFile = log.screenshotFile ?: return null
    val sessionDir = File(logsDir, log.session.value)
    val file = File(sessionDir, screenshotFile)
    return if (file.exists()) file else null
  }

  /**
   * Finds the log file on disk for a specific TrailblazeLog.
   * Returns the file if found, or null if not found.
   *
   * Parses each JSON file in the session directory and matches by timestamp and type.
   */
  fun findLogFile(log: TrailblazeLog): File? {
    val logFiles = readLogFilesFromDisk(log.session)
    if (logFiles.isEmpty()) return null

    for (file in logFiles) {
      try {
        val parsedLog = parseTrailblazeLogFromFile(file)
        if (parsedLog != null &&
          parsedLog.timestamp == log.timestamp &&
          parsedLog::class == log::class
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
