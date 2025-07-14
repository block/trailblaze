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
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Suppress("ktlint:standard:function-naming")
object LogsServerComposables {

  @Composable
  @Preview
  fun App(
    serverState: TrailblazeServerState,
    openLogsFolder: () -> Unit,
    openGoose: () -> Unit,
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
            .fillMaxWidth(),
        ) {
          item {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            ) {
              Button(openUrlInBrowser) {
                Text("Open Browser")
              }
              Spacer(Modifier.width(16.dp))
              Button(onClick = {
                openGoose()
              }) {
                Text("Open Goose")
              }
            }
          }
          if (serverState.appConfig.availableFeatures.hostMode) {
            item {
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .wrapContentHeight(),
              ) {
                Text(
                  modifier = Modifier.align(Alignment.CenterVertically),
                  text = "Host Mode Enabled (iOS Support)",
                )
                Spacer(Modifier.width(16.dp))
                Switch(
                  modifier = Modifier.align(Alignment.CenterVertically),
                  checked = serverState.appConfig.hostModeEnabled,
                  onCheckedChange = { checkedValue ->
                    val savedSettings = serverState.appConfig
                    updateState(
                      serverState.copy(
                        appConfig = savedSettings.copy(
                          hostModeEnabled = checkedValue,
                        ),
                      ),
                    )
                  },
                )
              }
            }
          }
          item {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            ) {
              Text(
                modifier = Modifier.align(Alignment.CenterVertically),
                text = "Auto Launch Trailblaze in Browser on Startup",
              )
              Spacer(Modifier.width(16.dp))
              Switch(
                modifier = Modifier.align(Alignment.CenterVertically),
                checked = serverState.appConfig.autoLaunchBrowser,
                onCheckedChange = { checkedValue ->
                  val savedSettings = serverState.appConfig
                  updateState(
                    serverState.copy(
                      appConfig = savedSettings.copy(
                        autoLaunchBrowser = checkedValue,
                      ),
                    ),
                  )
                },
              )
            }
          }
          item {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight(),
            ) {
              Text(
                modifier = Modifier.align(Alignment.CenterVertically),
                text = "Auto Launch Trailblaze in Goose on Startup",
              )
              Spacer(Modifier.width(16.dp))
              Switch(
                modifier = Modifier.align(Alignment.CenterVertically),
                checked = serverState.appConfig.autoLaunchGoose,
                onCheckedChange = { checkedValue ->
                  val savedSettings = serverState.appConfig
                  updateState(
                    serverState.copy(
                      appConfig = savedSettings.copy(
                        autoLaunchGoose = checkedValue,
                      ),
                    ),
                  )
                },
              )
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
