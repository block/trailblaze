@file:JvmName("Trailblaze")
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package xyz.block.trailblaze.desktop

import androidx.compose.material.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import io.ktor.server.engine.EmbeddedServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.launch
import xyz.block.trailblaze.desktop.util.Utils
import xyz.block.trailblaze.logs.server.TrailblazeMcpServer
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.LogsServerComposables
import xyz.block.trailblaze.ui.models.IndividualServer
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.io.File

val serverStateFlow = MutableStateFlow(TrailblazeServerState())

val logsDir = File("../logs")

val logsRepo = LogsRepo(logsDir)

@OptIn(DelicateCoroutinesApi::class, ExperimentalCoroutinesApi::class)
fun main() {
  TrailblazeMain.run()
}

object TrailblazeMain {
  val isOnDeviceMode: Boolean = true
  val HTTP_PORT = 52525
  val SERVER_URL = "http://localhost:${HTTP_PORT}"

  fun run() {
    var logsServerInstance: EmbeddedServer<*, *>? = null

    CoroutineScope(Dispatchers.IO).launch {
      serverStateFlow.mapLatest { it.logsServer }
        .distinctUntilChangedBy { server -> server.serverRunning }
        .collect { individualServer: IndividualServer ->
          println("Logs Server $individualServer")
          when (individualServer.serverRunning) {
            true -> {
              logsServerInstance =
                TrailblazeMcpServer(logsRepo, isOnDeviceMode = { isOnDeviceMode }).startSseMcpServer(wait = false)
              delay(1000)
              Utils.openInDefaultBrowser(SERVER_URL)
            }

            false -> {
              logsServerInstance?.stop()
              logsServerInstance = null
            }
          }
        }
    }

    application {
      Window(
        onCloseRequest = ::exitApplication,
        title = "🧭 Trailblaze",
      ) {
        val currentServerState by serverStateFlow.collectAsState(null)
        if (currentServerState == null) {
          Text("Loading...")
        } else {
          LogsServerComposables.App(
            serverState = currentServerState!!,
            openLogsFolder = {
              Utils.openInFileBrowser(logsDir)
            },
            updateState = { newState ->
              val currValue = serverStateFlow.value
              val currLogsServer = currValue.logsServer
              serverStateFlow.value =
                currValue.copy(logsServer = currLogsServer.copy(serverRunning = !currLogsServer.serverRunning))
            },
            openUrl = { url ->
              Utils.openInDefaultBrowser(url)
            },
            openUrlInBrowser = {
              Utils.openInDefaultBrowser(TrailblazeMain.SERVER_URL)
            },
          )
        }
      }
    }
  }
}
