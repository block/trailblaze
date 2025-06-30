package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import java.io.File

private typealias TrailblazeSessionId = String

class LogsRepo(val logsDir: File) {

  init {
    // Ensure the logs directory exists
    logsDir.mkdirs()
  }

  /**
   * A map of trailblaze session IDs to their corresponding file watcher services.
   */
  private val fileWatcherByTrailblazeSession = mutableMapOf<TrailblazeSessionId?, FileWatchService>()

  fun getSessionDirs(): List<File> = logsDir.listFiles().filter { it.isDirectory }.sortedByDescending { it.name }

  fun getSessionIds(): List<String> = getSessionDirs().map { it.name }

  /**
   * Stops watching the trailblaze session directory for changes.
   */
  fun stopWatching(trailblazeSessionId: TrailblazeSessionId) {
    fileWatcherByTrailblazeSession[trailblazeSessionId]?.stopWatching()
    fileWatcherByTrailblazeSession.remove(trailblazeSessionId)
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
        sessionDir,
      ) { changeType: FileWatchService.ChangeType, fileChanged: File ->
        println("LOGLISTENER - $changeType $fileChanged")
        if (fileChanged.extension == "json") {
          val logsForSession = getLogsForSession(trailblazeSessionId).sortedBy { it.timestamp }
          if (logsForSession.size == 1) {
            trailblazeSessionListener.onSessionStarted()
            return@FileWatchService
          }
          val mostRecentLog = logsForSession.lastOrNull()

          if (mostRecentLog is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
            if (mostRecentLog.sessionStatus is SessionStatus.Ended) {
              trailblazeSessionListener.onSessionEnded()
              stopWatching(trailblazeSessionId)
              return@FileWatchService
            }
          }

          if (mostRecentLog != null) {
            trailblazeSessionListener.onUpdate("Session Updated: ${mostRecentLog::class.java.simpleName} ${mostRecentLog.timestamp}")
            return@FileWatchService
          }
        }
      }
      fileWatcherByTrailblazeSession[trailblazeSessionId] = fileWatchService
      fileWatchService.startWatching()
    } else {
      error("Already watching trailblaze session: $trailblazeSessionId. This method would need to be supported to allow multiple listeners for the same session.")
    }
  }

  /**
   * Returns a list of logs for the given session ID.
   * If the session ID is null or the session directory does not exist, an empty list is returned.
   */
  fun getLogsForSession(sessionId: String?): List<TrailblazeLog> {
    if (sessionId != null) {
      val sessionDir = File(logsDir, sessionId)
      if (sessionDir.exists()) {
        val jsonFiles = sessionDir.listFiles().filter { it.extension == "json" }
        val logs = jsonFiles.mapNotNull {
          try {
            TrailblazeJsonInstance.decodeFromString<TrailblazeLog>(
              it.readText(),
            )
          } catch (e: Exception) {
            println("Error Reading ${it.absolutePath}")
            null
          }
        }.sortedBy { it.timestamp }
        return logs
      }
    }
    return emptyList()
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
}
