package xyz.block.trailblaze.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import java.io.File

class MainTrailblazeApp(
  val trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
  val logsDir: File,
  val trailblazeMcpServerProvider: () -> TrailblazeMcpServer,
) {
  val serverStateFlow = trailblazeSavedSettingsRepo.serverStateFlow

  fun runTrailblazeApp() {
    TrailblazeDesktopUtil.setAppConfigForTrailblaze()

    CoroutineScope(Dispatchers.IO).launch {
      val appConfig = trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig

      // Start Server
      var trailblazeMcpServer = trailblazeMcpServerProvider()
      trailblazeMcpServer.startSseMcpServer(
        port = appConfig.serverPort,
        wait = false
      )

      // Wait for the server to start
      delay(1000)

      // Auto Launch Browser if enabled
      if (appConfig.autoLaunchBrowser) {
        TrailblazeDesktopUtil.openInDefaultBrowser(trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig.serverUrl)
      }

      // Auto Launch Goose if enabled
      if (appConfig.autoLaunchGoose) {
        TrailblazeDesktopUtil.openGoose()
      }
    }

    application {
      Window(
        onCloseRequest = ::exitApplication,
        title = "🧭 Trailblaze",
      ) {
        val currentServerState by serverStateFlow.collectAsState()
        LogsServerComposables.App(
          serverState = currentServerState,
          openLogsFolder = {
            TrailblazeDesktopUtil.openInFileBrowser(logsDir)
          },
          updateState = { newState ->
            println("Update Server State: $newState")
            serverStateFlow.value = newState
          },
          openGoose = {
            TrailblazeDesktopUtil.openGoose()
          },
          openUrlInBrowser = {
            TrailblazeDesktopUtil.openInDefaultBrowser(currentServerState.appConfig.serverUrl)
          },
        )
      }
    }
  }
}
