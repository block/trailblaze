@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.sessions

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.report.utils.TemplateHelpers
import xyz.block.trailblaze.report.utils.TrailblazeYamlSessionRecording.generateRecordedYaml
import xyz.block.trailblaze.ui.createLiveSessionDataProviderJvm
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDetailComposable
import xyz.block.trailblaze.ui.tabs.session.LiveSessionListComposable

@Composable
fun SessionsTabComposableJvm(logsRepo: LogsRepo) {
  val liveSessionDataProvider = createLiveSessionDataProviderJvm(logsRepo)
  var selectedSession by remember { mutableStateOf<SessionInfo?>(null) }
  if (selectedSession == null) {
    LiveSessionListComposable(
      sessionDataProvider = liveSessionDataProvider,
      sessionClicked = { session ->
        selectedSession = session
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
      toMaestroYaml = TemplateHelpers::asMaestroYaml,
      generateRecordingYaml = {
        val logs = liveSessionDataProvider.getLogsForSession(selectedSession!!.sessionId)
        logs.generateRecordedYaml()
      },
      session = selectedSession!!,
      onBackClick = {
        selectedSession = null
      },
    )
  }
}
