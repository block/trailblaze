package xyz.block.trailblaze.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.ui.tooling.preview.Preview
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Suppress("ktlint:standard:function-naming")
object LogsServerComposables {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  @Preview
  fun Settings(
    serverState: TrailblazeServerState,
    openLogsFolder: () -> Unit,
    openGoose: () -> Unit,
    updateState: (TrailblazeServerState) -> Unit,
    openUrlInBrowser: () -> Unit,
    additionalContent: @Composable () -> Unit,
    envOpenAiApiKey: String? = null,
    envDatabricksToken: String? = null,
  ) {
    MaterialTheme {
      Surface(
        modifier = Modifier
          .fillMaxSize(),
      ) {
        Column {
          Row {
            additionalContent()
          }
          Row {
            LazyColumn(
              modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
              verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
              // Quick actions
              item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Quick actions", style = MaterialTheme.typography.titleMedium)
                    Row {
                      Button(openUrlInBrowser) { Text("Open Browser") }
                      Spacer(Modifier.width(16.dp))
                      Button(onClick = { openGoose() }) { Text("Open Goose") }
                    }
                  }
                }
              }

              // Application settings
              item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Application settings", style = MaterialTheme.typography.titleMedium)

                    // Theme Mode Selection
                    var showThemeMenu by remember { mutableStateOf(false) }
                    val themeOptions = listOf(
                      TrailblazeServerState.ThemeMode.System to "System",
                      TrailblazeServerState.ThemeMode.Light to "Light",
                      TrailblazeServerState.ThemeMode.Dark to "Dark"
                    )
                    val currentThemeLabel =
                      themeOptions.find { it.first == serverState.appConfig.themeMode }?.second ?: "System"

                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Text("Theme Mode", modifier = Modifier.weight(0.4f))
                      ExposedDropdownMenuBox(
                        expanded = showThemeMenu,
                        onExpandedChange = { showThemeMenu = !showThemeMenu },
                        modifier = Modifier.weight(0.6f)
                      ) {
                        OutlinedTextField(
                          modifier = Modifier.menuAnchor(),
                          value = currentThemeLabel,
                          onValueChange = {},
                          readOnly = true,
                          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showThemeMenu) }
                        )
                        DropdownMenu(
                          expanded = showThemeMenu,
                          onDismissRequest = { showThemeMenu = false }
                        ) {
                          themeOptions.forEach { (themeMode, label) ->
                            DropdownMenuItem(
                              text = { Text(label) },
                              onClick = {
                                showThemeMenu = false
                                val savedSettings = serverState.appConfig
                                updateState(
                                  serverState.copy(
                                    appConfig = savedSettings.copy(themeMode = themeMode)
                                  )
                                )
                              }
                            )
                          }
                        }
                      }
                    }
                    Divider()

                    if (serverState.appConfig.availableFeatures.hostMode) {
                      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Host Mode Enabled (iOS Support)", modifier = Modifier.weight(1f))
                        Switch(
                          checked = serverState.appConfig.hostModeEnabled,
                          onCheckedChange = { checkedValue ->
                            val savedSettings = serverState.appConfig
                            updateState(
                              serverState.copy(
                                appConfig = savedSettings.copy(hostModeEnabled = checkedValue),
                              ),
                            )
                          },
                        )
                      }
                      Divider()
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Text("Auto Launch Trailblaze in Browser on Startup", modifier = Modifier.weight(1f))
                      Switch(
                        checked = serverState.appConfig.autoLaunchBrowser,
                        onCheckedChange = { checkedValue ->
                          val savedSettings = serverState.appConfig
                          updateState(
                            serverState.copy(
                              appConfig = savedSettings.copy(autoLaunchBrowser = checkedValue),
                            ),
                          )
                        },
                      )
                    }
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Text("Auto Launch Trailblaze in Goose on Startup", modifier = Modifier.weight(1f))
                      Switch(
                        checked = serverState.appConfig.autoLaunchGoose,
                        onCheckedChange = { checkedValue ->
                          val savedSettings = serverState.appConfig
                          updateState(
                            serverState.copy(
                              appConfig = savedSettings.copy(autoLaunchGoose = checkedValue),
                            ),
                          )
                        },
                      )
                    }
                  }
                }
              }

              // Environment variables section
              item {
                val maskedOpenAi = if (envOpenAiApiKey.isNullOrBlank()) "Not set" else "…" + envOpenAiApiKey.takeLast(4)
                val maskedDbx =
                  if (envDatabricksToken.isNullOrBlank()) "Not set" else "…" + envDatabricksToken.takeLast(4)
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Environment variables", style = MaterialTheme.typography.titleMedium)
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Text("OPENAI_API_KEY", modifier = Modifier.weight(1f))
                      Text(maskedOpenAi, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Divider()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      Text("DATABRICKS_TOKEN", modifier = Modifier.weight(1f))
                      Text(maskedDbx, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                      text = "If you change your environment variables, please restart the app so it can be picked up.",
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                  }
                }
              }

              // Utilities
              item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Utilities", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = openLogsFolder) { Text("View Logs Folder") }
                  }
                }
              }
            }
          }
        }


      }
    }
  }
}
