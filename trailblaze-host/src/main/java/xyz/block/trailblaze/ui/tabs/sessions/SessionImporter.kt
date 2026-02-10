package xyz.block.trailblaze.ui.tabs.sessions

import kotlinx.datetime.Clock
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * Utility for importing Trailblaze sessions from ZIP archives.
 */
object SessionImporter {

  /**
   * Validates that a ZIP file contains session logs by checking for JSON files.
   */
  private fun validateSessionZip(zipFile: File): Pair<Boolean, String?> {
    try {
      ZipInputStream(FileInputStream(zipFile)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
          if (entry.name.endsWith(".json", ignoreCase = true)) {
            return Pair(true, null)
          }
          entry = zis.nextEntry
        }
      }

      return Pair(false, "No JSON log files found in archive")
    } catch (e: Exception) {
      return Pair(false, "Failed to read archive: ${e.message}")
    }
  }

  /**
   * Imports session(s) from a ZIP file into the logs repository.
   * Handles both single-session and multi-session ZIP archives.
   * Supports both direct format (sessionId/logfiles) and CI artifact format (logs/sessionId/logfiles).
   * Also supports an optional single top-level folder that wraps the logs.
   */
  fun importSessionFromZip(
    zipFile: File,
    logsRepo: LogsRepo
  ): SessionImportResult {
    try {
      val (isValid, errorMessage) = validateSessionZip(zipFile)
      if (!isValid) {
        return SessionImportResult.Error(
          title = "Invalid Archive",
          message = errorMessage ?: "This archive doesn't appear to contain any JSON log files."
        )
      }

      // First pass: determine structure and collect all session IDs
      val pathPartsList = mutableListOf<List<String>>()
      ZipInputStream(FileInputStream(zipFile)).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
          if (!entry.isDirectory) {
            val pathParts = entry.name.split("/").filter { it.isNotEmpty() }
            if (pathParts.isNotEmpty()) {
              pathPartsList.add(pathParts)
            }
          }
          entry = zis.nextEntry
        }
      }

      val stripSegments = determineStripSegments(pathPartsList)
      val sessionIds = collectSessionIds(pathPartsList, stripSegments)
      val existingSessions = mutableListOf<String>()

      if (sessionIds.isEmpty()) {
        return SessionImportResult.Error(
          title = "Invalid Archive Structure",
          message = "Could not determine session ID from archive structure.\n\n" +
              "Expected format: sessionId/logfiles or logs/sessionId/logfiles (optionally wrapped by a single folder)"
        )
      }

      // Check for existing sessions
      for (sessionId in sessionIds) {
        val targetSessionDir = File(logsRepo.logsDir, sessionId)
        if (targetSessionDir.exists()) {
          existingSessions.add(sessionId)
        }
      }

      if (existingSessions.isNotEmpty()) {
        return SessionImportResult.Error(
          title = "Session(s) Already Exist",
          message = "${existingSessions.size} of ${sessionIds.size} session(s) already exist:\n\n" +
              existingSessions.take(3).joinToString("\n") { "• $it" } +
              (if (existingSessions.size > 3) "\n• ... and ${existingSessions.size - 3} more" else "") +
              "\n\nPlease delete the existing sessions first if you want to import."
        )
      }

      // Second pass: extract all files
      var fileCount = 0
      ZipInputStream(FileInputStream(zipFile)).use { zis ->
        var entry = zis.nextEntry

        while (entry != null) {
          if (!entry.isDirectory) {
            val pathParts = entry.name.split("/").filter { it.isNotEmpty() }
            val normalizedParts = normalizePathParts(pathParts, stripSegments)
            if (normalizedParts.isEmpty()) {
              entry = zis.nextEntry
              continue
            }
            val entryPath = normalizedParts.joinToString("/")
            val targetFile = File(logsRepo.logsDir, entryPath)

            // Guard against zip path traversal (e.g., entries containing "../")
            if (!targetFile.canonicalPath.startsWith(logsRepo.logsDir.canonicalPath + File.separator)) {
              entry = zis.nextEntry
              continue
            }

            targetFile.parentFile?.mkdirs()

            FileOutputStream(targetFile).use { fos ->
              val buffer = ByteArray(1024)
              var len: Int
              while (zis.read(buffer).also { len = it } > 0) {
                fos.write(buffer, 0, len)
              }
            }
            fileCount++
          }

          entry = zis.nextEntry
        }
      }

      // Create marker files to track that these sessions were imported
      for (sessionId in sessionIds) {
        val importMarkerFile = File(File(logsRepo.logsDir, sessionId), ".imported")
        importMarkerFile.writeText(
          """
          Imported: ${Clock.System.now()}
          Source: ${zipFile.name}
          """.trimIndent()
        )
      }

      return SessionImportResult.Success(
        sessionId = if (sessionIds.size == 1) sessionIds.first() else "${sessionIds.size} sessions",
        fileCount = fileCount
      )
    } catch (e: Exception) {
      return SessionImportResult.Error(
        title = "Import Failed",
        message = "Failed to import session:\n${e.message}"
      )
    }
  }

  internal fun determineStripSegments(pathPartsList: List<List<String>>): List<String> {
    if (pathPartsList.isEmpty()) {
      return emptyList()
    }

    val stripSegments = mutableListOf<String>()
    val firstSegment = pathPartsList.firstOrNull()?.firstOrNull()

    val hasCommonWrapper = firstSegment != null &&
        pathPartsList.all { it.firstOrNull() == firstSegment && it.size >= 3 }

    if (hasCommonWrapper) {
      stripSegments.add(firstSegment)
    }

    val afterWrapper = pathPartsList.map { normalizePathParts(it, stripSegments) }
    val hasLogsPrefix = afterWrapper.isNotEmpty() &&
        afterWrapper.all { it.firstOrNull() == "logs" && it.size >= 2 }

    if (hasLogsPrefix) {
      stripSegments.add("logs")
    }

    return stripSegments
  }

  internal fun collectSessionIds(
    pathPartsList: List<List<String>>,
    stripSegments: List<String>,
  ): Set<String> {
    val sessionIds = mutableSetOf<String>()
    pathPartsList.forEach { pathParts ->
      val normalizedParts = normalizePathParts(pathParts, stripSegments)
      val sessionId = normalizedParts.firstOrNull()
      if (sessionId != null) {
        sessionIds.add(sessionId)
      }
    }
    return sessionIds
  }

  internal fun normalizePathParts(
    pathParts: List<String>,
    stripSegments: List<String>,
  ): List<String> {
    var remaining = pathParts
    stripSegments.forEach { segment ->
      remaining = if (remaining.firstOrNull() == segment) {
        remaining.drop(1)
      } else {
        remaining
      }
    }
    return remaining
  }

  /**
   * Checks if a session was imported by looking for the .imported marker file.
   */
  fun isImportedSession(
    sessionId: String,
    logsRepo: LogsRepo
  ): Boolean {
    val importMarkerFile = File(File(logsRepo.logsDir, sessionId), ".imported")
    return importMarkerFile.exists()
  }
}
