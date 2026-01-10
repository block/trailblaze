package xyz.block.trailblaze.ui.tabs.sessions

import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * Utility for exporting Trailblaze sessions to ZIP archives.
 */
object SessionExporter {

  /**
   * Recursively adds a directory and its contents to a ZIP output stream.
   */
  private fun zipDirectory(
    folder: File,
    parentPath: String,
    zipOut: ZipOutputStream
  ) {
    val files = folder.listFiles() ?: return

    for (file in files) {
      val filePath = "$parentPath/${file.name}"

      if (file.isDirectory) {
        zipDirectory(file, filePath, zipOut)
      } else {
        FileInputStream(file).use { fis ->
          val zipEntry = ZipEntry(filePath)
          zipOut.putNextEntry(zipEntry)

          val buffer = ByteArray(1024)
          var length: Int
          while (fis.read(buffer).also { length = it } > 0) {
            zipOut.write(buffer, 0, length)
          }

          zipOut.closeEntry()
        }
      }
    }
  }

  /**
   * Exports a session to a ZIP file, prompting the user for a save location.
   */
  fun exportSessionToZip(
    sessionInfo: SessionInfo,
    logsRepo: LogsRepo
  ): SessionExportResult {
    val sessionFolder = File(logsRepo.logsDir, sessionInfo.sessionId.value)

    if (!sessionFolder.exists() || !sessionFolder.isDirectory) {
      return SessionExportResult.Error(
        title = "Session Folder Not Found",
        message = "Could not find the logs folder for this session:\n${sessionFolder.absolutePath}"
      )
    }

    val timestamp = sessionInfo.timestamp.toString()
      .replace(":", "-")
      .replace("T", "_")
      .substringBefore(".")
    val testClass = sessionInfo.testClass?.substringAfterLast(".") ?: "UnknownTest"
    val testName = sessionInfo.testName ?: "unknownMethod"
    val suggestedFileName = "${timestamp}_${testClass}_${testName}.zip"

    var zipFile: File? = null
    var userCancelled = false

    try {
      SwingUtilities.invokeAndWait {
        val fileChooser = JFileChooser().apply {
          dialogTitle = "Save Session Export"
          selectedFile = File(suggestedFileName)
          fileFilter = FileNameExtensionFilter("ZIP files (*.zip)", "zip")
        }

        val result = fileChooser.showSaveDialog(null)

        if (result == JFileChooser.APPROVE_OPTION) {
          zipFile = fileChooser.selectedFile
          if (zipFile != null && !zipFile!!.name.endsWith(".zip", ignoreCase = true)) {
            zipFile = File(zipFile!!.parentFile, zipFile!!.name + ".zip")
          }
        } else {
          userCancelled = true
        }
      }
    } catch (e: Exception) {
      println("Failed to show file chooser: ${e.message}")
      return SessionExportResult.Error(
        title = "Dialog Error",
        message = "Failed to show file chooser dialog:\n${e.message}"
      )
    }

    if (userCancelled || zipFile == null) {
      return SessionExportResult.Error(
        title = "Export Cancelled",
        message = "Export was cancelled by user."
      )
    }

    return try {
      ZipOutputStream(FileOutputStream(zipFile)).use { zipOut ->
        zipDirectory(sessionFolder, sessionFolder.name, zipOut)
      }

      SessionExportResult.SuccessZip(
        zipFile = zipFile.absolutePath
      )
    } catch (e: Exception) {
      println("Failed to create zip: ${e.message}")
      SessionExportResult.Error(
        title = "Export Failed",
        message = "Failed to create zip file:\n${e.message}"
      )
    }
  }
}
