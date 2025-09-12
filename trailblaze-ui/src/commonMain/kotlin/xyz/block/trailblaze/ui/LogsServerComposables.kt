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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Suppress("ktlint:standard:function-naming")
object LogsServerComposables {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  @Preview
  fun SettingsTab(
    serverState: TrailblazeServerState,
    availableModelLists: Set<TrailblazeLlmModelList>,
    openLogsFolder: () -> Unit,
    openGoose: () -> Unit,
    updateState: (TrailblazeServerState) -> Unit,
    openUrlInBrowser: () -> Unit,
    additionalContent: @Composable () -> Unit,
    environmentVariableProvider: (String) -> String? = { null },
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
                    SelectableText("Quick actions", style = MaterialTheme.typography.titleMedium)
                    Row {
                      Button(openUrlInBrowser) { SelectableText("Open Browser") }
                      Spacer(Modifier.width(16.dp))
                      Button(onClick = { openGoose() }) { SelectableText("Open Goose") }
                    }
                  }
                }
              }

              // Application settings
              item {
                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableText("Application settings", style = MaterialTheme.typography.titleMedium)

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
                      SelectableText("Theme Mode", modifier = Modifier.weight(0.4f))
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
                              text = { SelectableText(label) },
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
                    HorizontalDivider()

                    if (serverState.appConfig.availableFeatures.hostMode) {
                      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SelectableText("Host Mode Enabled (iOS Support)", modifier = Modifier.weight(1f))
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
                      HorizontalDivider()
                    }
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      SelectableText("Auto Launch Trailblaze in Goose on Startup", modifier = Modifier.weight(1f))
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
                    HorizontalDivider()
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                      SelectableText("Keep Window Always on Top", modifier = Modifier.weight(1f))
                      Switch(
                        checked = serverState.appConfig.alwaysOnTop,
                        onCheckedChange = { checkedValue ->
                          val savedSettings = serverState.appConfig
                          updateState(
                            serverState.copy(
                              appConfig = savedSettings.copy(alwaysOnTop = checkedValue),
                            ),
                          )
                        },
                      )
                    }
                    HorizontalDivider()

                    // LLM Provider and Model Selection in a single row
                    var showLlmProviderMenu by remember { mutableStateOf(false) }
                    var showLlmModelMenu by remember { mutableStateOf(false) }

                    val currentProviderModelList = availableModelLists.firstOrNull {
                      it.provider.id == serverState.appConfig.llmProvider
                    } ?: OpenAITrailblazeLlmModelList

                    val currentProvider: TrailblazeLlmProvider = currentProviderModelList.provider

                    val availableModelsForCurrentProvider = currentProviderModelList.entries
                    val currentModel: TrailblazeLlmModel = currentProviderModelList.entries.firstOrNull {
                      it.modelId == serverState.appConfig.llmModel
                    } ?: currentProviderModelList.entries.first()

                    val currentLlmModelLabel = currentModel.modelId

                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      // LLM Provider Section
                      Column(modifier = Modifier.weight(1f)) {
                        SelectableText("LLM Provider", style = MaterialTheme.typography.bodyMedium)
                        ExposedDropdownMenuBox(
                          expanded = showLlmProviderMenu,
                          onExpandedChange = { showLlmProviderMenu = !showLlmProviderMenu }
                        ) {
                          OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            value = currentProvider.display,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLlmProviderMenu) }
                          )
                          DropdownMenu(
                            expanded = showLlmProviderMenu,
                            onDismissRequest = { showLlmProviderMenu = false }
                          ) {
                            availableModelLists.forEach { modelList: TrailblazeLlmModelList ->
                              val provider: TrailblazeLlmProvider = modelList.provider
                              DropdownMenuItem(
                                text = { SelectableText(provider.display) },
                                onClick = {
                                  showLlmProviderMenu = false
                                  val savedSettings = serverState.appConfig
                                  val providerModels = availableModelLists.first {
                                    it.provider == provider
                                  }.entries
                                  updateState(
                                    serverState.copy(
                                      appConfig = savedSettings.copy(
                                        llmProvider = provider.id,
                                        llmModel = providerModels
                                          .first()
                                          .modelId
                                      )
                                    )
                                  )
                                }
                              )
                            }
                          }
                        }
                      }

                      // LLM Model Section
                      Column(modifier = Modifier.weight(1f)) {
                        SelectableText("LLM Model", style = MaterialTheme.typography.bodyMedium)
                        ExposedDropdownMenuBox(
                          expanded = showLlmModelMenu,
                          onExpandedChange = { showLlmModelMenu = !showLlmModelMenu }
                        ) {
                          OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            value = currentLlmModelLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showLlmModelMenu) }
                          )
                          DropdownMenu(
                            expanded = showLlmModelMenu,
                            onDismissRequest = { showLlmModelMenu = false }
                          ) {
                            availableModelsForCurrentProvider.forEach { model ->
                              DropdownMenuItem(
                                text = { SelectableText(model.modelId) },
                                onClick = {
                                  showLlmModelMenu = false
                                  val savedSettings = serverState.appConfig
                                  updateState(
                                    serverState.copy(
                                      appConfig = savedSettings.copy(llmModel = model.modelId)
                                    )
                                  )
                                }
                              )
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }

              // Environment variables section
              item {
                val envVariableNames = listOf(
                  "DATABRICKS_TOKEN",
                  "OPENAI_API_KEY",
                  "TEST_RAIL_API_KEY",
                  "TEST_RAIL_EMAIL",
                )

                OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                  Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SelectableText("Environment variables", style = MaterialTheme.typography.titleMedium)

                    envVariableNames.forEachIndexed { index, name ->
                      val value = environmentVariableProvider(name)
                      val maskedValue = if (value.isNullOrBlank()) "Not set" else "…" + value.takeLast(6)

                      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        SelectableText(name, modifier = Modifier.weight(1f))
                        SelectableText(maskedValue, color = MaterialTheme.colorScheme.onSurfaceVariant)
                      }

                      // Add divider between items, but not after the last one
                      if (index < envVariableNames.size - 1) {
                        HorizontalDivider()
                      }
                    }

                    SelectableText(
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
                    SelectableText("Utilities", style = MaterialTheme.typography.titleMedium)
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
