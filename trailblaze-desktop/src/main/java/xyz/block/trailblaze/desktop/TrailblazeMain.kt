@file:JvmName("Trailblaze")
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.desktop

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.ExperimentalCoroutinesApi
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.MainTrailblazeApp
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.io.File

val logsDir = File("../logs")

val logsRepo = LogsRepo(logsDir)

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main() {
  val trailblazeSavedSettingsRepo = TrailblazeSettingsRepo(
    initialConfig = TrailblazeServerState.SavedTrailblazeAppConfig(
      availableFeatures = TrailblazeServerState.SavedTrailblazeAppConfig.AvailableFeatures(
        hostMode = false,
      ),
    ),
  )
  val server = TrailblazeMcpServer(
    logsRepo,
    isOnDeviceMode = {
      !trailblazeSavedSettingsRepo.serverStateFlow.value.appConfig.availableFeatures.hostMode
    },
  )
  MainTrailblazeApp(
    trailblazeSavedSettingsRepo = trailblazeSavedSettingsRepo,
    logsDir = logsDir,
    trailblazeMcpServerProvider = { server },
  ).runTrailblazeApp()
}
