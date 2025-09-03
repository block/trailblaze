package xyz.block.trailblaze.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.ui.composables.FullScreenModalOverlay
import xyz.block.trailblaze.ui.composables.ScreenshotImageModal
import xyz.block.trailblaze.ui.tabs.session.SessionDetailComposable
import xyz.block.trailblaze.ui.tabs.session.SessionListComposable
import xyz.block.trailblaze.ui.tabs.session.group.LogDetailsDialog
import xyz.block.trailblaze.ui.tabs.session.models.SessionDetail

// Central data provider instance
private val dataProvider: TrailblazeLogsDataProvider = InlinedDataLoader
//private val dataProvider: TrailblazeLogsDataProvider = NetworkTrailblazeLogsDataProvider

@OptIn(ExperimentalComposeUiApi::class)
fun main() {
  ComposeViewport(document.body!!) {
    TrailblazeApp()
  }
}

@Composable
fun TrailblazeApp() {
  var currentRoute by remember { mutableStateOf(parseRoute()) }

  // Listen for hash changes
  window.addEventListener("hashchange", {
    currentRoute = parseRoute()
  })

  val toMaestroYaml: (JsonObject) -> String = { it.toString() }
  val toTrailblazeYaml: suspend (SessionInfo) -> String = { sessionInfo ->
    dataProvider.getSessionRecordingYaml(sessionInfo.sessionId)
  }


  println("Using data provider: $dataProvider")

  when {
    currentRoute.startsWith("session/") -> {
      val sessionName = currentRoute.removePrefix("session/")
      WasmSessionDetailView(
        dataProvider = dataProvider,
        toMaestroYaml = toMaestroYaml,
        sessionName = sessionName,
        onBackClick = {
          navigateToHome()
          currentRoute = ""
        }
      )
    }

    else -> {
      println("SessionListView with dataProvider: $dataProvider")
      SessionListView(
        dataProvider = dataProvider,
        onSessionClick = { session ->
          navigateToSession(session.sessionId)
          currentRoute = "session/${session.sessionId}"
        },
        deleteSession = null
      )
    }
  }
}

fun parseRoute(): String {
  val hash = window.location.hash
  return if (hash.startsWith("#")) hash.substring(1) else ""
}

fun navigateToHome() {
  window.location.hash = ""
}

fun navigateToSession(sessionName: String) {
  window.location.hash = "session/$sessionName"
}

@Composable
fun SessionListView(
  dataProvider: TrailblazeLogsDataProvider,
  onSessionClick: (SessionInfo) -> Unit,
  deleteSession: ((SessionInfo) -> Unit)? = null,
) {
  var sessions by remember { mutableStateOf<List<SessionInfo>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) {
    try {
      isLoading = true
      errorMessage = null
      val sessionNames = dataProvider.getSessionIdsAsync()
      sessions = sessionNames.mapNotNull { sessionName ->
        dataProvider.getSessionInfoAsync(sessionName)
      }
    } catch (e: Exception) {
      errorMessage = "Failed to load sessions: ${e.message}"
      sessions = emptyList()
    } finally {
      isLoading = false
    }
  }

  SessionListComposable(
    sessions = sessions,
    sessionClicked = onSessionClick,
    deleteSession = deleteSession,
    clearAllLogs = null,
  )
}

@Composable
fun WasmSessionDetailView(
  dataProvider: TrailblazeLogsDataProvider,
  toMaestroYaml: (JsonObject) -> String,
  sessionName: String,
  onBackClick: () -> Unit,
) {
  var logs by remember { mutableStateOf<List<TrailblazeLog>>(emptyList()) }
  var isLoading by remember { mutableStateOf(true) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var sessionInfo by remember { mutableStateOf<SessionInfo?>(null) }
  var recordingYaml by remember { mutableStateOf<String?>(null) }

  // Modal state
  var showDetailsDialog by remember { mutableStateOf(false) }
  var showInspectUIDialog by remember { mutableStateOf(false) }
  var currentLog by remember { mutableStateOf<TrailblazeLog?>(null) }
  var currentLlmLog by remember { mutableStateOf<TrailblazeLog.TrailblazeLlmRequestLog?>(null) }
  
  // Screenshot modal state
  var showScreenshotModal by remember { mutableStateOf(false) }
  var modalImageModel by remember { mutableStateOf<Any?>(null) }
  var modalDeviceWidth by remember { mutableStateOf(0) }
  var modalDeviceHeight by remember { mutableStateOf(0) }
  var modalClickX by remember { mutableStateOf<Int?>(null) }
  var modalClickY by remember { mutableStateOf<Int?>(null) }

  LaunchedEffect(sessionName) {
    try {
      isLoading = true
      errorMessage = null
      val fetchedLogs: List<TrailblazeLog> = dataProvider.getLogsForSessionAsync(sessionName)
      logs = fetchedLogs
      sessionInfo = dataProvider.getSessionInfoAsync(sessionName)

      // Precompute the recording YAML
      try {
        recordingYaml = dataProvider.getSessionRecordingYaml(sessionName)
      } catch (e: Exception) {
        recordingYaml = "# Error generating YAML: ${e.message}"
      }
    } catch (e: Exception) {
      errorMessage = "Failed to load logs: ${e.message}"
    } finally {
      isLoading = false
    }
  }

  if (sessionInfo != null) {
    Box(modifier = Modifier.fillMaxSize()) {
      SessionDetailComposable(
        details = SessionDetail(
          session = sessionInfo!!,
          logs = logs,
        ),
        toMaestroYaml = toMaestroYaml,
        onBackClick = onBackClick,
        generateRecordingYaml = {
          recordingYaml ?: "# Loading YAML..."
        },
        onShowDetails = { log ->
          currentLog = log
          showDetailsDialog = true
        },
        onShowInspectUI = { log ->
          currentLlmLog = log
          showInspectUIDialog = true
        },
        onShowScreenshotModal = { imageModel, deviceWidth, deviceHeight, clickX, clickY ->
          modalImageModel = imageModel
          modalDeviceWidth = deviceWidth
          modalDeviceHeight = deviceHeight
          modalClickX = clickX
          modalClickY = clickY
          showScreenshotModal = true
        }
      )

      // Modal dialogs
      if (showDetailsDialog && currentLog != null) {
        FullScreenModalOverlay(
          onDismiss = {
            showDetailsDialog = false
            currentLog = null
          }
        ) {
          LogDetailsDialog(
            log = currentLog!!,
            onDismiss = {
              showDetailsDialog = false
              currentLog = null
            }
          )
        }
      }

      if (showInspectUIDialog && currentLlmLog != null) {
        FullScreenModalOverlay(
          onDismiss = {
            showInspectUIDialog = false
            currentLlmLog = null
          }
        ) {
          Column(
            modifier = Modifier.fillMaxSize()
          ) {
            // Header with close button
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = "UI Inspector",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
              )
              Button(
                onClick = {
                  showInspectUIDialog = false
                  currentLlmLog = null
                }
              ) {
                Text("Close")
              }
            }

            // Inspector content
            InspectViewHierarchyScreenComposable(
              sessionId = sessionName,
              viewHierarchy = currentLlmLog!!.viewHierarchy,
              imageUrl = currentLlmLog!!.screenshotFile,
              deviceWidth = currentLlmLog!!.deviceWidth,
              deviceHeight = currentLlmLog!!.deviceHeight,
            )
          }
        }
      }

      // Screenshot modal
      if (showScreenshotModal && modalImageModel != null) {
        ScreenshotImageModal(
          imageModel = modalImageModel!!,
          deviceWidth = modalDeviceWidth,
          deviceHeight = modalDeviceHeight,
          clickX = modalClickX,
          clickY = modalClickY,
          onDismiss = {
            showScreenshotModal = false
            modalImageModel = null
            modalClickX = null
            modalClickY = null
          }
        )
      }
    }
  } else if (isLoading) {
    // Optionally, show a loading indicator while sessionInfo is being determined
  } else {
    // Handle case where sessionInfo could not be determined (e.g. no logs)
  }
}
