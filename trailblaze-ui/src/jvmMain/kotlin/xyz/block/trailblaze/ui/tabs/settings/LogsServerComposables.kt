package xyz.block.trailblaze.ui.tabs.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.runtime.collectAsState
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
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Suppress("ktlint:standard:function-naming")
object LogsServerComposables {

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  @Preview
  fun SettingsTab(
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    customEnvVariableNames: List<String>,
    availableModelLists: Set<TrailblazeLlmModelList>,
    openLogsFolder: () -> Unit,
    openDesktopAppPreferencesFile: () -> Unit,
    openGoose: () -> Unit,
    additionalContent: @Composable () -> Unit,
    environmentVariableProvider: (String) -> String? = { null },
  ) {

    val serverState: TrailblazeServerState by trailblazeSettingsRepo.serverStateFlow.collectAsState()
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
            Box(
              modifier = Modifier.fillMaxWidth(),
              contentAlignment = Alignment.TopCenter
            ) {
              LazyColumn(
                modifier = Modifier
                  .padding(16.dp)
                  .width(600.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
              ) {
                // Quick actions
                item {
                  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                      modifier = Modifier.padding(20.dp),
                      verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                      SelectableText("Quick Actions", style = MaterialTheme.typography.titleMedium)
                      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { openGoose() }) { SelectableText("Open Goose") }
                      }
                    }
                  }
                }

                // Application settings
                item {
                  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                      modifier = Modifier.padding(20.dp),
                      verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                      SelectableText(
                        "Application Settings", style = MaterialTheme.typography.titleMedium
                      )

                      // Theme Mode Selection
                      var showThemeMenu by remember { mutableStateOf(false) }
                      val themeOptions = listOf(
                        TrailblazeServerState.ThemeMode.System to "System",
                        TrailblazeServerState.ThemeMode.Light to "Light",
                        TrailblazeServerState.ThemeMode.Dark to "Dark"
                      )
                      val currentThemeLabel =
                        themeOptions.find { it.first == serverState.appConfig.themeMode }?.second
                          ?: "System"

                      Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                      ) {
                        SelectableText("Theme Mode", style = MaterialTheme.typography.bodyMedium)
                        ExposedDropdownMenuBox(
                          expanded = showThemeMenu,
                          onExpandedChange = { showThemeMenu = !showThemeMenu }
                        ) {
                          OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            value = currentThemeLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                              ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = showThemeMenu
                              )
                            }
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
                                  trailblazeSettingsRepo.updateAppConfig {
                                    it.copy(themeMode = themeMode)
                                  }
                                }
                              )
                            }
                          }
                        }
                      }

                      Spacer(modifier = Modifier.height(8.dp))

                      if (serverState.appConfig.availableFeatures.hostMode) {
                        Row(
                          modifier = Modifier.fillMaxWidth(),
                          horizontalArrangement = Arrangement.SpaceBetween,
                          verticalAlignment = Alignment.CenterVertically
                        ) {
                          Column(modifier = Modifier.weight(1f)) {
                            SelectableText("Host Mode", style = MaterialTheme.typography.bodyMedium)
                            SelectableText(
                              "Enable iOS support",
                              style = MaterialTheme.typography.bodySmall,
                              color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                          }
                          Switch(
                            checked = serverState.appConfig.hostModeEnabled,
                            onCheckedChange = { checkedValue ->
                              trailblazeSettingsRepo.updateAppConfig {
                                it.copy(hostModeEnabled = checkedValue)
                              }
                            },
                          )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                      }

                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          SelectableText(
                            "Auto Launch Goose", style = MaterialTheme.typography.bodyMedium
                          )
                          SelectableText(
                            "Launch Trailblaze in Goose on startup",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        Switch(
                          checked = serverState.appConfig.autoLaunchGoose,
                          onCheckedChange = { checkedValue ->
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(autoLaunchGoose = checkedValue)
                            }
                          },
                        )
                      }

                      Spacer(modifier = Modifier.height(8.dp))

                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          SelectableText(
                            "Always on Top", style = MaterialTheme.typography.bodyMedium
                          )
                          SelectableText(
                            "Keep window above other applications",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        Switch(
                          checked = serverState.appConfig.alwaysOnTop,
                          onCheckedChange = { checkedValue ->
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(alwaysOnTop = checkedValue)
                            }
                          },
                        )
                      }
                    }
                  }
                }

                // LLM Settings
                item {
                  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                      modifier = Modifier.padding(20.dp),
                      verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                      SelectableText(
                        "Language Model Settings", style = MaterialTheme.typography.titleMedium
                      )

                      // LLM Provider and Model Selection
                      var showLlmProviderMenu by remember { mutableStateOf(false) }
                      var showLlmModelMenu by remember { mutableStateOf(false) }

                      val currentProviderModelList = availableModelLists.firstOrNull {
                        it.provider.id == serverState.appConfig.llmProvider
                      } ?: OpenAITrailblazeLlmModelList

                      val currentProvider: TrailblazeLlmProvider = currentProviderModelList.provider

                      val availableModelsForCurrentProvider = currentProviderModelList.entries
                      val currentModel: TrailblazeLlmModel =
                        currentProviderModelList.entries.firstOrNull {
                          it.modelId == serverState.appConfig.llmModel
                        } ?: currentProviderModelList.entries.first()

                      val currentLlmModelLabel = currentModel.modelId

                      // LLM Provider Section
                      Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                      ) {
                        SelectableText("Provider", style = MaterialTheme.typography.bodyMedium)
                        ExposedDropdownMenuBox(
                          expanded = showLlmProviderMenu,
                          onExpandedChange = { showLlmProviderMenu = !showLlmProviderMenu }
                        ) {
                          OutlinedTextField(
                            modifier = Modifier.fillMaxWidth()
                              .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                            value = currentProvider.display,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                              ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = showLlmProviderMenu
                              )
                            }
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
                                  trailblazeSettingsRepo.updateState { serverState2 ->
                                    serverState.copy(
                                      appConfig = savedSettings.copy(
                                        llmProvider = provider.id,
                                        llmModel = providerModels
                                          .first()
                                          .modelId
                                      )
                                    )
                                  }
                                }
                              )
                            }
                          }
                        }
                      }

                      // LLM Model Section
                      Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                      ) {
                        SelectableText("Model", style = MaterialTheme.typography.bodyMedium)
                        ExposedDropdownMenuBox(
                          expanded = showLlmModelMenu,
                          onExpandedChange = { showLlmModelMenu = !showLlmModelMenu }
                        ) {
                          OutlinedTextField(
                            modifier = Modifier.fillMaxWidth().menuAnchor(),
                            value = currentLlmModelLabel,
                            onValueChange = {},
                            readOnly = true,
                            trailingIcon = {
                              ExposedDropdownMenuDefaults.TrailingIcon(
                                expanded = showLlmModelMenu
                              )
                            }
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
                                  trailblazeSettingsRepo.updateAppConfig {
                                    it.copy(llmModel = model.modelId)
                                  }
                                }
                              )
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
                    "OPENAI_API_KEY",
                  ) + customEnvVariableNames

                  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                      modifier = Modifier.padding(20.dp),
                      verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                      SelectableText(
                        "Environment Variables", style = MaterialTheme.typography.titleMedium
                      )

                      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        envVariableNames.forEach { name ->
                          val value = environmentVariableProvider(name)
                          val maskedValue =
                            if (value.isNullOrBlank()) "Not set" else "•••••" + value.takeLast(4)

                          Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                          ) {
                            SelectableText(
                              text = name,
                              style = MaterialTheme.typography.bodyMedium,
                              modifier = Modifier.weight(1f)
                            )
                            SelectableText(
                              text = maskedValue,
                              color = if (value.isNullOrBlank())
                                MaterialTheme.colorScheme.error
                              else
                                MaterialTheme.colorScheme.onSurfaceVariant,
                              style = MaterialTheme.typography.bodyMedium
                            )
                          }
                        }
                      }
                    }

                    HorizontalDivider()

                    SelectableText(
                      text = "💡 Tip: If you change environment variables, restart the app to apply changes.",
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                      style = MaterialTheme.typography.bodySmall
                    )
                  }
                }

                // Utilities
                item {
                  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                      modifier = Modifier.padding(20.dp),
                      verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                      SelectableText("Utilities", style = MaterialTheme.typography.titleMedium)
                      Row {
                        Button(onClick = openLogsFolder) { Text("View Logs Folder") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(onClick = openDesktopAppPreferencesFile) {
                          Text("Open Desktop App Preferences File")
                        }
                      }
                    }
                  }
                }

                // Experimental Features
                item {
                  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                      modifier = Modifier.padding(20.dp),
                      verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                      SelectableText(
                        "Experimental Features", style = MaterialTheme.typography.titleMedium
                      )
                      SelectableText(
                        "⚠️  These features are experimental and may change or be removed in future versions.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )

                      Spacer(modifier = Modifier.height(8.dp))

                      Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                      ) {
                        Column(modifier = Modifier.weight(1f)) {
                          SelectableText(
                            "Export to Repo", style = MaterialTheme.typography.bodyMedium
                          )
                          SelectableText(
                            "Enable exporting session recordings to repository",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                          )
                        }
                        Switch(
                          checked = serverState.appConfig.experimentalFeatures.exportToRepoEnabled,
                          onCheckedChange = { checkedValue ->
                            trailblazeSettingsRepo.updateAppConfig { currAppConfig: TrailblazeServerState.SavedTrailblazeAppConfig ->
                              currAppConfig.copy(
                                experimentalFeatures = currAppConfig.experimentalFeatures.copy(
                                  exportToRepoEnabled = checkedValue
                                )
                              )
                            }
                          },
                        )
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
  }
}