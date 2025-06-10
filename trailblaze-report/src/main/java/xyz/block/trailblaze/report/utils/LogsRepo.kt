package xyz.block.trailblaze.report.utils

import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.client.TrailblazeLog
import java.io.File

class LogsRepo(val logsDir: File) {

  fun getSessionDirs(): List<File> = logsDir.listFiles().filter { it.isDirectory }.sortedByDescending { it.name }

  fun getSessionIds(): List<String> = getSessionDirs().map { it.name }

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

  fun clearLogs() {
    if (logsDir.exists()) {
      logsDir.listFiles().filter { it.isDirectory }.forEach {
        it.deleteRecursively()
      }
    }
  }

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

  fun getSessionIdToSessionDirMap(): Map<String, List<TrailblazeLog>> = getSessionDirs().associate { sessionDir ->
    sessionDir.name to getLogsForSession(sessionDir.name)
  }
}
