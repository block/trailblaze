package xyz.block.trailblaze.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import xyz.block.trailblaze.ui.models.IndividualServer
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

@Suppress("ktlint:standard:function-naming")
object LogsServerComposables {

  @Composable
  @Preview
  private fun IndividualServerComposable(
    individualServer: IndividualServer,
    updateServer: (IndividualServer) -> Unit,
    openTrailblazeWeb: () -> Unit,
    openUrl: (String) -> Unit,
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .wrapContentHeight(),
    ) {
      val showDebugText = false
      if (showDebugText) {
        Text(text = "${individualServer.name} (Port ${individualServer.port})", modifier = Modifier.padding(8.dp))
        Spacer(Modifier.width(16.dp))
      }
      val canStartStopServer = false
      if (canStartStopServer) {
        Button(onClick = {
          updateServer(
            individualServer.copy(
              serverRunning = !individualServer.serverRunning,
            ),
          )
        }) {
          Text(
            when (individualServer.serverRunning) {
              true -> "Stop Server"
              false -> "Start Server"
            },
          )
        }
        Spacer(Modifier.width(16.dp))
      }
      Button(openTrailblazeWeb) {
        Text("Open Browser")
      }
      Spacer(Modifier.width(16.dp))
      val gooseRecipeJson = this::class.java.classLoader.getResource("trailblaze_goose_recipe.json").readText()

      @OptIn(ExperimentalEncodingApi::class)
      val gooseRecipeEncoded = Base64.encode(gooseRecipeJson.toByteArray())
      val gooseUrl = "goose://recipe?config=${gooseRecipeEncoded}"
      Button(onClick = {
        openUrl(gooseUrl)
      }) {
        Text("Open Goose")
      }
    }
  }

  @Composable
  @Preview
  fun App(
    serverState: TrailblazeServerState,
    openLogsFolder: () -> Unit,
    openUrl: (String) -> Unit,
    updateState: (TrailblazeServerState) -> Unit,
    openUrlInBrowser: () -> Unit,
  ) {
    MaterialTheme {
      Surface(
        modifier = Modifier
          .fillMaxSize(),
      ) {
        LazyColumn(
          modifier = Modifier
            .padding(16.dp)
            .fillMaxWidth()
        ) {
          item {
            IndividualServerComposable(
              serverState.logsServer,
              { individualServer ->
                updateState(
                  serverState.copy(
                    logsServer = serverState.logsServer.copy(
                      serverRunning = !individualServer.serverRunning
                    )
                  )
                )
              },
              openTrailblazeWeb = {
                openUrlInBrowser()
              },
              openUrl = openUrl,
            )
          }
          if (serverState.hostModeAvailable) {
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .wrapContentHeight(),
              ) {
                Text(
                  modifier = Modifier.align(Alignment.CenterVertically),
                  text = "Host Mode Enabled (iOS Support)"
                )
                Spacer(Modifier.width(16.dp))
                Switch(
                  modifier = Modifier.align(Alignment.CenterVertically),
                  checked = serverState.savedSettings.hostModeEnabled,
                  onCheckedChange = {
                    val savedSettings = serverState.savedSettings
                    updateState(
                      serverState.copy(
                        savedSettings = savedSettings.copy(
                          hostModeEnabled = !savedSettings.hostModeEnabled
                        ),
                      )
                    )
                  }
                )
              }
            }
          }
          item {
            Button(onClick = openLogsFolder) {
              Text("View Logs Folder")
            }
          }
        }
      }
    }
  }
}
