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
   * Imports a session from a ZIP file into the logs repository.
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

      var sessionId: String? = null
      var fileCount = 0

      // First pass: extract session ID and check for conflicts
      ZipInputStream(FileInputStream(zipFile)).use { zis ->
        val entry = zis.nextEntry

        if (entry != null) {
          sessionId = entry.name.substringBefore("/")
        }

        if (sessionId.isNullOrEmpty()) {
          return SessionImportResult.Error(
            title = "Invalid Archive Structure",
            message = "Could not determine session ID from archive structure.\n\n" +
                "Expected format: sessionId/logfiles"
          )
        }

        val targetSessionDir = File(logsRepo.logsDir, sessionId!!)
        if (targetSessionDir.exists()) {
          return SessionImportResult.Error(
            title = "Session Already Exists",
            message = "A session with ID '$sessionId' already exists.\n\n" +
                "Please delete the existing session first if you want to import this one."
          )
        }
      }

      // Second pass: extract all files
      ZipInputStream(FileInputStream(zipFile)).use { zis ->
        var entry = zis.nextEntry

        while (entry != null) {
          if (!entry.isDirectory) {
            val targetFile = File(logsRepo.logsDir, entry.name)
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

      // Create marker file to track that this session was imported
      val importMarkerFile = File(File(logsRepo.logsDir, sessionId!!), ".imported")
      importMarkerFile.writeText(
        """
        Imported: ${Clock.System.now()}
        Source: ${zipFile.name}
        """.trimIndent()
      )

      return SessionImportResult.Success(
        sessionId = sessionId,
        fileCount = fileCount
      )
    } catch (e: Exception) {
      return SessionImportResult.Error(
        title = "Import Failed",
        message = "Failed to import session:\n${e.message}"
      )
    }
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
