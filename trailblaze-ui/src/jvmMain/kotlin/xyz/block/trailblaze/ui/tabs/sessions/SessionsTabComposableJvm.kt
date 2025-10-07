@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.sessions

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TemplateHelpers
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.ui.createLiveSessionDataProviderJvm
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDetailComposable
import xyz.block.trailblaze.ui.tabs.session.SessionListComposable
import xyz.block.trailblaze.ui.tabs.session.SessionViewMode
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Composable
fun SessionsTabComposableJvm(
  logsRepo: LogsRepo,
  serverState: TrailblazeServerState,
  updateState: (TrailblazeServerState) -> Unit,
) {
  val liveSessionDataProvider = remember(logsRepo) {
    createLiveSessionDataProviderJvm(logsRepo)
  }

  // Maintain session list state at parent level
  var sessions by remember { mutableStateOf(emptyList<SessionInfo>()) }

  // Keep track of session IDs for change detection
  var lastSessionIds by remember { mutableStateOf(emptySet<String>()) }

  // Get current session from serverState (persists across tab switches)
  val currentSessionId = serverState.appConfig.currentSessionId
  val selectedSession = remember(currentSessionId, sessions) {
    sessions.firstOrNull { it.sessionId == currentSessionId }
  }

  val currentSessionViewMode = remember(serverState.appConfig.currentSessionViewMode) {
    SessionViewMode.fromString(serverState.appConfig.currentSessionViewMode)
  }

  // Simple polling mechanism - check for changes every 2 seconds
  LaunchedEffect(liveSessionDataProvider) {
    while (isActive) {
      withContext(Dispatchers.IO) {
        try {
          val currentSessionIds = liveSessionDataProvider.getSessionIds().toSet()

          // Always try to load sessions, not just when IDs change
          // This allows newly created sessions to appear once their logs are written
          val sessionIdsChanged = currentSessionIds != lastSessionIds
          val shouldReload = sessionIdsChanged || sessions.size != currentSessionIds.size

          if (shouldReload) {
            val loadedSessions = currentSessionIds.mapNotNull { sessionId ->
              liveSessionDataProvider.getSessionInfo(sessionId)
            }

            sessions = loadedSessions
            lastSessionIds = currentSessionIds
          }
        } catch (e: Exception) {
          e.printStackTrace()
        }
      }

      delay(2000) // Poll every 2 seconds
    }
  }

  if (selectedSession == null) {
    SessionListComposable(
      sessions = sessions,
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
    )
  } else {
    LiveSessionDetailComposable(
      sessionDataProvider = liveSessionDataProvider,
      toMaestroYaml = { jsonObject: JsonObject -> TemplateHelpers.asMaestroYaml(jsonObject) },
      toTrailblazeYaml = TemplateHelpers::asTrailblazeYaml,
      generateRecordingYaml = {
        val logs = liveSessionDataProvider.getLogsForSession(selectedSession!!.sessionId)
        logs.generateRecordedYaml()
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
    )
  }
}
