package xyz.block.trailblaze.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document
import kotlinx.browser.window
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.TrailblazeLogsDataProvider
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo

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
      sessions = sessionNames.map { sessionName ->
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
    SessionDetailComposable(
      details = SessionDetail(
        session = sessionInfo!!,
        logs = logs,
      ),
      toMaestroYaml = toMaestroYaml,
      onBackClick = onBackClick,
      generateRecordingYaml = {
        recordingYaml ?: "# Loading YAML..."
      }
    )
  } else if (isLoading) {
    // Optionally, show a loading indicator while sessionInfo is being determined
  } else {
    // Handle case where sessionInfo could not be determined (e.g. no logs)
  }
}
