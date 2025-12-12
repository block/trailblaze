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
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TemplateHelpers
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.createLiveSessionDataProviderJvm
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.recordings.RecordedTrailsRepo
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDetailComposableWithSelectorSupport
import xyz.block.trailblaze.ui.tabs.session.SessionListComposable
import xyz.block.trailblaze.ui.tabs.session.SessionViewMode
import xyz.block.trailblaze.ui.tabs.testresults.TestResultsComposableJvm
import java.awt.Desktop
import java.io.File
import javax.swing.JFileChooser
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter


@Composable
fun SessionsTabComposableJvm(
  logsRepo: LogsRepo,
  serverState: TrailblazeServerState,
  updateState: (TrailblazeServerState) -> Unit,
  deviceManager: TrailblazeDeviceManager? = null,
  recordedTrailsRepo: RecordedTrailsRepo,
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
  var exportResult: SessionExportResult? by remember { mutableStateOf(null) }

  // Import state
  var showImportDialog by remember { mutableStateOf(false) }
  var importResult: SessionImportResult? by remember { mutableStateOf(null) }

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
            importedSessionIds = sessions.filter { SessionImporter.isImportedSession(it.sessionId, logsRepo) }
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
      testResultsSummaryView = { TestResultsComposableJvm(logsRepo) },
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
            exportResult = SessionExporter.exportSessionToZip(session, logsRepo)
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
              SwingUtilities.invokeAndWait {
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
                importResult = SessionImporter.importSessionFromZip(zipFile, logsRepo)
                showImportDialog = true
              }
            } catch (e: Exception) {
              importResult = SessionImportResult.Error(
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
    LiveSessionDetailComposableWithSelectorSupport(
      sessionDataProvider = liveSessionDataProvider,
      imageLoader = xyz.block.trailblaze.ui.createLogsFileSystemImageLoader(),
      toMaestroYaml = { jsonObject: JsonObject -> TemplateHelpers.asMaestroYaml(jsonObject) },
      toTrailblazeYaml = TemplateHelpers::asTrailblazeYaml,
      generateRecordingYaml = {
        val logs = liveSessionDataProvider.getLogsForSession(selectedSession.sessionId)
        val yamlContent = logs.generateRecordedYaml(selectedSession.trailConfig)
        yamlContent
      },
      session = selectedSession,
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
      onOpenInFinder = { log ->
        val logFile = logsRepo.findLogFile(log)
        if (logFile != null) {
          TrailblazeDesktopUtil.revealFileInFinder(logFile)
        }
      },
      onRevealRecordingInFinder = { filePath ->
        TrailblazeDesktopUtil.revealFileInFinder(File(filePath))
      },
      onExportSession = {
        coroutineScope.launch {
          withContext(Dispatchers.IO) {
            exportResult = SessionExporter.exportSessionToZip(selectedSession, logsRepo)
            showDialog = true
          }
        }
      },
      inspectorScreenshotWidth = serverState.appConfig.uiInspectorScreenshotWidth,
      inspectorDetailsWeight = serverState.appConfig.uiInspectorDetailsWeight,
      inspectorHierarchyWeight = serverState.appConfig.uiInspectorHierarchyWeight,
      initialInspectorFontScale = serverState.appConfig.uiInspectorFontScale,
      onInspectorDetailsWeightChanged = { newWeight ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(uiInspectorDetailsWeight = newWeight)
          )
        )
      },
      onInspectorHierarchyWeightChanged = { newWeight ->
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(uiInspectorHierarchyWeight = newWeight)
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
      recordedTrailsRepo = recordedTrailsRepo,
    )
  }

  // Show export result dialog
  if (showDialog && exportResult != null) {
    val result = exportResult!!
    when (result) {
      is SessionExportResult.SuccessZip -> {
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

      is SessionExportResult.Error -> {
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
      is SessionImportResult.Success -> {
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

      is SessionImportResult.Error -> {
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
