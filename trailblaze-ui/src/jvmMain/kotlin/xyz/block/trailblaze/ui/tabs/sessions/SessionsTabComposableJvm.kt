@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.sessions

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TemplateHelpers
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.ui.createLiveSessionDataProviderJvm
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDetailComposable
import xyz.block.trailblaze.ui.tabs.session.SessionListComposable
import xyz.block.trailblaze.ui.tabs.session.SessionViewMode
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.util.toPascalCaseIdentifier
import xyz.block.trailblaze.util.toSnakeCaseWithId
import java.io.File
import java.awt.Desktop
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

// Export result sealed class for better error handling
private sealed class ExportResult {
  data class SuccessZip(
    val zipFile: String
  ) : ExportResult()

  data class SuccessRepo(
    val ktFile: String,
    val yamlFile: String
  ) : ExportResult()

  data class Error(
    val title: String,
    val message: String
  ) : ExportResult()
}

// Import result sealed class
private sealed class ImportResult {
  data class Success(
    val sessionId: String,
    val fileCount: Int
  ) : ImportResult()

  data class Error(
    val title: String,
    val message: String
  ) : ImportResult()
}

private fun exportSessionToZip(
  sessionInfo: SessionInfo,
  logsRepo: LogsRepo
): ExportResult {
  val sessionFolder = File(logsRepo.logsDir, sessionInfo.sessionId)

  if (!sessionFolder.exists() || !sessionFolder.isDirectory) {
    return ExportResult.Error(
      title = "Session Folder Not Found",
      message = "Could not find the logs folder for this session:\n${sessionFolder.absolutePath}"
    )
  }

  // Generate a filename using timestamp, test class, and test name
  val timestamp =
    sessionInfo.timestamp.toString().replace(":", "-").replace("T", "_").substringBefore(".")
  val testClass = sessionInfo.testClass?.substringAfterLast(".") ?: "UnknownTest"
  val testName = sessionInfo.testName ?: "unknownMethod"
  val suggestedFileName = "${timestamp}_${testClass}_${testName}.zip"

  // Show file chooser dialog on EDT (Event Dispatch Thread)
  var zipFile: File? = null
  var userCancelled = false

  try {
    javax.swing.SwingUtilities.invokeAndWait {
      val fileChooser = JFileChooser().apply {
        dialogTitle = "Save Session Export"
        selectedFile = File(suggestedFileName)
        fileFilter = FileNameExtensionFilter("ZIP files (*.zip)", "zip")
      }

      val result = fileChooser.showSaveDialog(null)

      if (result == JFileChooser.APPROVE_OPTION) {
        zipFile = fileChooser.selectedFile
        // Ensure .zip extension
        if (zipFile != null && !zipFile!!.name.endsWith(".zip", ignoreCase = true)) {
          zipFile = File(zipFile!!.parentFile, zipFile!!.name + ".zip")
        }
      } else {
        userCancelled = true
      }
    }
  } catch (e: Exception) {
    println("Failed to show file chooser: ${e.message}")
    return ExportResult.Error(
      title = "Dialog Error",
      message = "Failed to show file chooser dialog:\n${e.message}"
    )
  }

  if (userCancelled || zipFile == null) {
    return ExportResult.Error(
      title = "Export Cancelled",
      message = "Export was cancelled by user."
    )
  }

  val selectedZipFile = zipFile!!

  return try {
    // Create the zip file
    ZipOutputStream(FileOutputStream(selectedZipFile)).use { zipOut ->
      zipDirectory(sessionFolder, sessionFolder.name, zipOut)
    }

    ExportResult.SuccessZip(
      zipFile = selectedZipFile.absolutePath
    )
  } catch (e: Exception) {
    println("Failed to create zip: ${e.message}")
    ExportResult.Error(
      title = "Export Failed",
      message = "Failed to create zip file:\n${e.message}"
    )
  }
}

private fun zipDirectory(
  folder: File,
  parentPath: String,
  zipOut: ZipOutputStream
) {
  val files = folder.listFiles() ?: return

  for (file in files) {
    val filePath = "$parentPath/${file.name}"

    if (file.isDirectory) {
      // Recursively zip subdirectories
      zipDirectory(file, filePath, zipOut)
    } else {
      // Add file to zip
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

// Validate if a zip file contains session logs (basic check for JSON files)
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

// Import a session from a zip file
private fun importSessionFromZip(
  zipFile: File,
  logsRepo: LogsRepo
): ImportResult {
  try {
    // Basic validation - just check if archive has JSON files
    val (isValid, errorMessage) = validateSessionZip(zipFile)
    if (!isValid) {
      return ImportResult.Error(
        title = "Invalid Archive",
        message = errorMessage ?: "This archive doesn't appear to contain any JSON log files."
      )
    }

    // Extract to a temporary directory first to get the session ID
    var sessionId: String? = null
    var fileCount = 0

    ZipInputStream(FileInputStream(zipFile)).use { zis ->
      var entry = zis.nextEntry

      // First pass: find the session ID from the first entry's parent folder
      if (entry != null) {
        val firstPath = entry.name
        // Extract session ID from path like "sessionId/file.json"
        sessionId = firstPath.substringBefore("/")
      }

      if (sessionId.isNullOrEmpty()) {
        return ImportResult.Error(
          title = "Invalid Archive Structure",
          message = "Could not determine session ID from archive structure.\n\n" +
            "Expected format: sessionId/logfiles"
        )
      }

      // Check if session already exists
      val targetSessionDir = File(logsRepo.logsDir, sessionId!!)
      if (targetSessionDir.exists()) {
        return ImportResult.Error(
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
        val entryName = entry.name

        // Skip the root directory entry itself
        if (!entry.isDirectory) {
          // Create the full path in the logs directory
          val targetFile = File(logsRepo.logsDir, entryName)

          // Create parent directories
          targetFile.parentFile?.mkdirs()

          // Write the file
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

    // Create a marker file to indicate this session was imported
    val importMarkerFile = File(File(logsRepo.logsDir, sessionId!!), ".imported")
    importMarkerFile.writeText(
      """
      Imported: ${kotlinx.datetime.Clock.System.now()}
      Source: ${zipFile.name}
      """.trimIndent()
    )

    return ImportResult.Success(
      sessionId = sessionId!!,
      fileCount = fileCount
    )
  } catch (e: Exception) {
    return ImportResult.Error(
      title = "Import Failed",
      message = "Failed to import session:\n${e.message}"
    )
  }
}

// Check if a session was imported (has .imported marker file)
private fun isImportedSession(
  sessionId: String,
  logsRepo: LogsRepo
): Boolean {
  val importMarkerFile = File(File(logsRepo.logsDir, sessionId), ".imported")
  return importMarkerFile.exists()
}

@Composable
fun SessionsTabComposableJvm(
  logsRepo: LogsRepo,
  serverState: TrailblazeServerState,
  updateState: (TrailblazeServerState) -> Unit,
  deviceManager: xyz.block.trailblaze.ui.TrailblazeDeviceManager? = null,
) {
  val liveSessionDataProvider = remember(logsRepo, deviceManager) {
    createLiveSessionDataProviderJvm(logsRepo, deviceManager)
  }

  // Maintain session list state at parent level
  var sessions by remember { mutableStateOf(emptyList<SessionInfo>()) }

  // Keep track of session IDs for change detection
  var lastSessionIds by remember { mutableStateOf(emptySet<String>()) }

  // Track which sessions are imported
  var importedSessionIds by remember { mutableStateOf(emptySet<String>()) }

  // Get current session from serverState (persists across tab switches)
  val currentSessionId = serverState.appConfig.currentSessionId
  val selectedSession = remember(currentSessionId, sessions) {
    sessions.firstOrNull { it.sessionId == currentSessionId }
  }

  val currentSessionViewMode = remember(serverState.appConfig.currentSessionViewMode) {
    SessionViewMode.fromString(serverState.appConfig.currentSessionViewMode)
  }

  var showDialog by remember { mutableStateOf(false) }
  var exportResult: ExportResult? by remember { mutableStateOf(null) }

  // Import state
  var showImportDialog by remember { mutableStateOf(false) }
  var importResult: ImportResult? by remember { mutableStateOf(null) }

  // Simple polling mechanism - check for changes every 2 seconds
  LaunchedEffect(liveSessionDataProvider) {
    while (isActive) {
      withContext(Dispatchers.IO) {
        try {
          val currentSessionIds = liveSessionDataProvider.getSessionIds().toSet()

          // Always reload sessions to catch status changes (e.g., in-progress -> completed)
          // Session status can change without the session IDs changing
          val loadedSessions = currentSessionIds.mapNotNull { sessionId ->
            liveSessionDataProvider.getSessionInfo(sessionId)
          }

          // Only update state if something actually changed
          val hasChanges = currentSessionIds != lastSessionIds ||
            sessions.size != loadedSessions.size ||
            sessions.zip(loadedSessions).any { (old, new) ->
              old.latestStatus != new.latestStatus ||
                old.sessionId != new.sessionId
            }

          if (hasChanges) {
            sessions = loadedSessions
            lastSessionIds = currentSessionIds
            importedSessionIds = sessions.filter { isImportedSession(it.sessionId, logsRepo) }
              .map { it.sessionId }
              .toSet()
          }
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }

      delay(2000) // Poll every 2 seconds
    }
  }

  val coroutineScope = rememberCoroutineScope()

  if (selectedSession == null) {
    SessionListComposable(
      sessions = sessions,
      importedSessionIds = importedSessionIds,
      sessionClicked = { session ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(currentSessionId = session.sessionId)
          )
        )
      },
      deleteSession = { session ->
        logsRepo.deleteLogsForSession(session.sessionId)
      },
      clearAllLogs = {
        logsRepo.clearLogs()
      },
      openLogsFolder = { session ->
        // Open the logs folder for the session in the system file explorer
        val logsDirectory = logsRepo.logsDir
        val sessionFolder = File(logsDirectory, session.sessionId)

        if (sessionFolder.exists() && sessionFolder.isDirectory) {
          try {
            Desktop.getDesktop().open(sessionFolder)
          } catch (e: Exception) {
            println("Failed to open logs folder: ${e.message}")
            e.printStackTrace()
          }
        } else {
          println("Logs folder not found: ${sessionFolder.absolutePath}")
        }
      },
      openLogsFolderRoot = {
        // Open the root logs directory
        val logsDirectory = logsRepo.logsDir
        try {
          Desktop.getDesktop().open(logsDirectory)
        } catch (e: Exception) {
          println("Failed to open root logs folder: ${e.message}")
          e.printStackTrace()
        }
      },
      onExportSession = { session ->
        coroutineScope.launch {
          withContext(Dispatchers.IO) {
            exportResult = exportSessionToZip(session, logsRepo)
            showDialog = true
          }
        }
      },
      onImportSession = {
        coroutineScope.launch {
          withContext(Dispatchers.IO) {
            // Show file chooser on EDT
            var selectedFile: File? = null
            try {
              javax.swing.SwingUtilities.invokeAndWait {
                val fileChooser = JFileChooser().apply {
                  dialogTitle = "Select Session Archive to Import"
                  fileFilter = FileNameExtensionFilter("ZIP files (*.zip)", "zip")
                }

                val result = fileChooser.showOpenDialog(null)
                if (result == JFileChooser.APPROVE_OPTION) {
                  selectedFile = fileChooser.selectedFile
                }
              }

              // If user selected a file, import it
              selectedFile?.let { zipFile ->
                importResult = importSessionFromZip(zipFile, logsRepo)
                showImportDialog = true
              }
            } catch (e: Exception) {
              importResult = ImportResult.Error(
                title = "Dialog Error",
                message = "Failed to show file chooser:\n${e.message}"
              )
              showImportDialog = true
            }
          }
        }
      },
    )
  } else {
    LiveSessionDetailComposable(
      sessionDataProvider = liveSessionDataProvider,
      toMaestroYaml = { jsonObject: JsonObject -> TemplateHelpers.asMaestroYaml(jsonObject) },
      toTrailblazeYaml = TemplateHelpers::asTrailblazeYaml,
      generateRecordingYaml = {
        val logs = liveSessionDataProvider.getLogsForSession(selectedSession!!.sessionId)
        val yamlContent = logs.generateRecordedYaml(selectedSession.trailConfig)

        // Add TestRail comment header at the top if test ID is available
        val testId = selectedSession.trailConfig?.id
        val testSuiteId = selectedSession.trailConfig?.metadata?.get("testSuiteId")

        if (testId != null) {
          buildString {
            appendLine("# Test Case: https://square.testrail.com/index.php?/cases/view/$testId")
            if (testSuiteId != null) {
              appendLine(
                "# Test Suite: https://square.testrail.com/index.php?/suites/view/$testSuiteId"
              )
            }
            append(yamlContent)
          }
        } else {
          yamlContent
        }
      },
      session = selectedSession!!,
      initialZoomOffset = serverState.appConfig.sessionDetailZoomOffset,
      initialFontScale = serverState.appConfig.sessionDetailFontScale,
      initialViewMode = currentSessionViewMode,
      onZoomOffsetChanged = { newZoomOffset ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(sessionDetailZoomOffset = newZoomOffset)
          )
        )
      },
      onFontScaleChanged = { newFontScale ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(sessionDetailFontScale = newFontScale)
          )
        )
      },
      onViewModeChanged = { newViewMode ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(
              currentSessionViewMode = newViewMode.toStringValue()
            )
          )
        )
      },
      onBackClick = {
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(currentSessionId = null)
          )
        )
      },
      onDeleteSession = {
        // Delete the session and navigate back to list
        logsRepo.deleteLogsForSession(selectedSession.sessionId)
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(currentSessionId = null)
          )
        )
      },
      onOpenLogsFolder = {
        // Open the logs folder for the session
        val logsDirectory = logsRepo.logsDir
        val sessionFolder = File(logsDirectory, selectedSession.sessionId)

        if (sessionFolder.exists() && sessionFolder.isDirectory) {
          try {
            Desktop.getDesktop().open(sessionFolder)
          } catch (e: Exception) {
            println("Failed to open logs folder: ${e.message}")
            e.printStackTrace()
          }
        } else {
          println("Logs folder not found: ${sessionFolder.absolutePath}")
        }
      },
      onExportSession = {
        coroutineScope.launch {
          withContext(Dispatchers.IO) {
            exportResult = exportSessionToZip(selectedSession!!, logsRepo)
            showDialog = true
          }
        }
      },
      onExportToRepo = { yamlContent ->
        coroutineScope.launch {
          withContext(Dispatchers.IO) {
            error("Export to repo is not yet implemented")
          }
        }
      },
      exportFeatureEnabled = serverState.appConfig.experimentalFeatures.exportToRepoEnabled,
      initialInspectorScreenshotWidth = serverState.appConfig.uiInspectorScreenshotWidth,
      initialInspectorDetailsWidth = serverState.appConfig.uiInspectorDetailsWidth,
      initialInspectorHierarchyWidth = serverState.appConfig.uiInspectorHierarchyWidth,
      initialInspectorFontScale = serverState.appConfig.uiInspectorFontScale,
      onInspectorScreenshotWidthChanged = { newWidth ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(uiInspectorScreenshotWidth = newWidth)
          )
        )
      },
      onInspectorDetailsWidthChanged = { newWidth ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(uiInspectorDetailsWidth = newWidth)
          )
        )
      },
      onInspectorHierarchyWidthChanged = { newWidth ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(uiInspectorHierarchyWidth = newWidth)
          )
        )
      },
      onInspectorFontScaleChanged = { newFontScale ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(uiInspectorFontScale = newFontScale)
          )
        )
      },
    )
  }

  // Show export result dialog
  if (showDialog && exportResult != null) {
    val result = exportResult!!
    when (result) {
      is ExportResult.SuccessZip -> {
        AlertDialog(
          onDismissRequest = {
            showDialog = false
            exportResult = null
          },
          title = { Text("✅ Export Successful") },
          text = {
            Text(
              "Zip file created successfully:\n\n" +
                "📄 ${result.zipFile}"
            )
          },
          confirmButton = {
            TextButton(onClick = {
              showDialog = false
              exportResult = null
            }) {
              Text("OK")
            }
          }
        )
      }
      is ExportResult.SuccessRepo -> {
        AlertDialog(
          onDismissRequest = {
            showDialog = false
            exportResult = null
          },
          title = { Text("✅ Export Successful") },
          text = {
            Text(
              "KT and YAML files created successfully:\n\n" +
                "📄 ${result.yamlFile}\n" +
                "📄 ${result.ktFile}"
            )
          },
          confirmButton = {
            TextButton(onClick = {
              showDialog = false
              exportResult = null
            }) {
              Text("OK")
            }
          }
        )
      }

      is ExportResult.Error -> {
        AlertDialog(
          onDismissRequest = {
            showDialog = false
            exportResult = null
          },
          title = { Text("❌ ${result.title}") },
          text = { Text(result.message) },
          confirmButton = {
            TextButton(onClick = {
              showDialog = false
              exportResult = null
            }) {
              Text("OK")
            }
          }
        )
      }
    }
  }

  // Show import result dialog
  if (showImportDialog && importResult != null) {
    val result = importResult!!
    when (result) {
      is ImportResult.Success -> {
        AlertDialog(
          onDismissRequest = {
            showImportDialog = false
            importResult = null
          },
          title = { Text("✅ Import Successful") },
          text = {
            Text(
              "Session imported successfully:\n\n" +
                "📄 ${result.sessionId} with ${result.fileCount} files"
            )
          },
          confirmButton = {
            TextButton(onClick = {
              showImportDialog = false
              importResult = null
            }) {
              Text("OK")
            }
          }
        )
      }

      is ImportResult.Error -> {
        AlertDialog(
          onDismissRequest = {
            showImportDialog = false
            importResult = null
          },
          title = { Text("❌ ${result.title}") },
          text = { Text(result.message) },
          confirmButton = {
            TextButton(onClick = {
              showImportDialog = false
              importResult = null
            }) {
              Text("OK")
            }
          }
        )
      }
    }
  }
}
