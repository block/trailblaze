package xyz.block.trailblaze.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import xyz.block.trailblaze.ui.models.IndividualServer
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Suppress("ktlint:standard:function-naming")
object LogsServerComposables {

  @Composable
  @Preview
  private fun IndividualServerComposable(individualServer: IndividualServer, updateServer: (IndividualServer) -> Unit) {
    Text(text = "${individualServer.name} (Port ${individualServer.port})", modifier = Modifier.padding(8.dp))
    Spacer(Modifier.width(16.dp))
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

  @Composable
  @Preview
  fun App(
    serverState: TrailblazeServerState,
    openLogsFolder: () -> Unit,
    toggleServer: (TrailblazeServerState) -> Unit,
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
            IndividualServerComposable(serverState.logsServer, { individualServer ->
              toggleServer(
                serverState.copy(
                  logsServer = serverState.logsServer.copy(
                    serverRunning = !individualServer.serverRunning
                  )
                )
              )
            })
          }
          item {
            Button(openUrlInBrowser) {
              Text("Open in Default Browser")
            }
          }
          item {
            Button(onClick = openLogsFolder) {
              Text("Open Logs Folder")
            }
          }
        }
      }
    }
  }

  @Composable
  fun InputTextArea(onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }

    Button(modifier = Modifier.wrapContentSize(), onClick = {
      onSubmit(text)
      text = ""
    }) {
      Text("Run Prompt")
    }

    Row {
      OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text("Enter your message") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = false,
        maxLines = Int.MAX_VALUE,
      )
    }
  }
}
