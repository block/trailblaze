package xyz.block.trailblaze.ui.tabs.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.io.File
import javax.swing.JFileChooser

object SettingsTabComposables {

  /**
   * A composable for a single platform configuration row.
   */
  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  private fun PlatformConfigurationRow(
    platform: TrailblazeDevicePlatform,
    driversForPlatform: List<TrailblazeDriverType>,
    enabledDriverTypesMap: Map<TrailblazeDevicePlatform, TrailblazeDriverType>,
    trailblazeSettingsRepo: TrailblazeSettingsRepo
  ) {
    // Get the currently selected driver for this platform (null means "None")
    val selectedDriver = enabledDriverTypesMap[platform]

    var showDriverMenu by remember { mutableStateOf(false) }

    // Display value: either the driver name or "None"
    val displayValue = selectedDriver?.name ?: "None"

    Column(
      modifier = Modifier.fillMaxWidth(),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      SelectableText(
        platform.displayName,
        style = MaterialTheme.typography.bodyMedium
      )

      ExposedDropdownMenuBox(
        expanded = showDriverMenu,
        onExpandedChange = { showDriverMenu = !showDriverMenu }
      ) {
        OutlinedTextField(
          modifier = Modifier.fillMaxWidth().menuAnchor(),
          value = displayValue,
          onValueChange = {},
          readOnly = true,
          trailingIcon = {
            ExposedDropdownMenuDefaults.TrailingIcon(
              expanded = showDriverMenu
            )
          }
        )
        DropdownMenu(
          expanded = showDriverMenu,
          onDismissRequest = { showDriverMenu = false }
        ) {
          // "None" option to disable the platform
          DropdownMenuItem(
            text = { SelectableText("DISABLED") },
            onClick = {
              showDriverMenu = false
              trailblazeSettingsRepo.updateAppConfig { config ->
                val currentMap = config.selectedTrailblazeDriverTypes
                // Remove this platform from the map
                val newMap = currentMap - platform
                config.copy(selectedTrailblazeDriverTypes = newMap)
              }
            }
          )

          // All available drivers for this platform
          driversForPlatform.forEach { trailblazeDriverType: TrailblazeDriverType ->
            DropdownMenuItem(
              text = { SelectableText(trailblazeDriverType.name) },
              onClick = {
                showDriverMenu = false
                trailblazeSettingsRepo.updateAppConfig { config ->
                  val currentMap = config.selectedTrailblazeDriverTypes
                  // Add or update the driver for this platform
                  val newMap = currentMap + (platform to trailblazeDriverType)
                  config.copy(selectedTrailblazeDriverTypes = newMap)
                }
              }
            )
          }
        }
      }
    }
  }

  /**
   * A reusable settings section with a card wrapper and title.
   */
  @Composable
  private fun SettingsSection(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
  ) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
      Column(
        modifier = Modifier.padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        SelectableText(title, style = MaterialTheme.typography.titleMedium)
        content()
      }
    }
  }

  /**
   * A reusable preference toggle row with a label, optional description, and switch.
   */
  @Composable
  private fun PreferenceToggle(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null
  ) {
    Row(
      modifier = modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column(modifier = Modifier.weight(1f)) {
        SelectableText(
          text = label,
          style = MaterialTheme.typography.bodyMedium
        )
        if (description != null) {
          SelectableText(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      Switch(
        checked = checked,
        onCheckedChange = onCheckedChange
      )
    }
  }

  @OptIn(ExperimentalMaterial3Api::class)
  @Composable
  fun SettingsTab(
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    customEnvVariableNames: List<String>,
    availableModelLists: Set<TrailblazeLlmModelList>,
    openLogsFolder: () -> Unit,
    openDesktopAppPreferencesFile: () -> Unit,
    openGoose: () -> Unit,
    additionalContent: @Composable () -> Unit,
    globalSettingsContent: @Composable ColumnScope.(serverState: TrailblazeServerState) -> Unit,
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
                  SettingsSection(title = "Quick Actions") {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                      Button(onClick = { openGoose() }) { SelectableText("Open Goose") }
                    }
                  }
                }

                // Application settings
                item {
                  SettingsSection(title = "Application Settings") {
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

                    PreferenceToggle(
                      label = "Auto Launch Goose",
                      description = "Launch Trailblaze in Goose on startup",
                      checked = serverState.appConfig.autoLaunchGoose,
                      onCheckedChange = { checkedValue ->
                        trailblazeSettingsRepo.updateAppConfig {
                          it.copy(autoLaunchGoose = checkedValue)
                        }
                      }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    PreferenceToggle(
                      label = "Always on Top",
                      description = "Keep window above other applications",
                      checked = serverState.appConfig.alwaysOnTop,
                      onCheckedChange = { checkedValue ->
                        trailblazeSettingsRepo.updateAppConfig {
                          it.copy(alwaysOnTop = checkedValue)
                        }
                      }
                    )
                  }
                }

                // LLM Settings
                item {
                  SettingsSection(title = "Language Model Settings") {
                    // Set of Mark Toggle
                    PreferenceToggle(
                      label = "Enable Set of Mark",
                      checked = serverState.appConfig.setOfMarkEnabled,
                      onCheckedChange = { checkedValue ->
                        trailblazeSettingsRepo.updateAppConfig {
                          it.copy(setOfMarkEnabled = checkedValue)
                        }
                      }
                    )

                    Spacer(modifier = Modifier.height(8.dp))

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

                item {
                  SettingsSection(title = "Device Targets") {
                    val allDriverTypes = trailblazeSettingsRepo.getAllSupportedDriverTypes()
                    // Use the reactive serverState instead of calling getEnabledDriverTypesMap()
                    val enabledDriverTypesMap = serverState.appConfig.selectedTrailblazeDriverTypes

                    // Group drivers by platform
                    val driversByPlatform = allDriverTypes.groupBy { it.platform }

                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                      driversByPlatform.forEach { (platform, driversForPlatform) ->
                        PlatformConfigurationRow(
                          platform = platform,
                          driversForPlatform = driversForPlatform,
                          enabledDriverTypesMap = enabledDriverTypesMap,
                          trailblazeSettingsRepo = trailblazeSettingsRepo
                        )
                      }
                    }
                  }
                }

                // Global Settings (Databricks OAuth)
                item {
                  SettingsSection(title = "Global Settings") {
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      globalSettingsContent(serverState)
                    }
                  }
                }

                // Environment variables section
                item {
                  val envVariableNames = (listOf("OPENAI_API_KEY") + customEnvVariableNames)

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

                          val displayValue = if (!value.isNullOrBlank()) {
                            "•••••" + value.takeLast(4)
                          } else {
                            "Not set"
                          }

                          val hasValue = !value.isNullOrBlank()

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
                              text = displayValue,
                              color = if (hasValue)
                                MaterialTheme.colorScheme.onSurfaceVariant
                              else
                                MaterialTheme.colorScheme.error,
                              style = MaterialTheme.typography.bodyMedium
                            )
                          }
                        }
                      }

                      Spacer(modifier = Modifier.height(8.dp))

                      HorizontalDivider()

                      Spacer(modifier = Modifier.height(8.dp))

                      SelectableText(
                        text = "💡 Tip: If you change environment variables, restart the app to apply changes.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                      )
                    }
                  }
                }

                // Advanced Configuration
                item {
                  SettingsSection(title = "Advanced Configuration") {
                    // Logs Directory Configuration
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      SelectableText("Logs Directory", style = MaterialTheme.typography.bodyMedium)

                      // Compute the effective logs directory location
                      val effectiveLogsDirectory = remember(serverState.appConfig) {
                        TrailblazeDesktopUtil.getEffectiveLogsDirectory(serverState.appConfig)
                      }

                      SelectableText(
                        text = if (serverState.appConfig.logsDirectory != null) {
                          effectiveLogsDirectory
                        } else {
                          "Using default location ($effectiveLogsDirectory)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = openLogsFolder) {
                          Text("Open Logs Folder in Finder")
                        }
                        Button(onClick = {
                          // Determine the starting directory for the file chooser
                          val effectiveLogsDir =
                            TrailblazeDesktopUtil.getEffectiveLogsDirectory(serverState.appConfig)
                          val startingDir = File(effectiveLogsDir)

                          val fileChooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Select Logs Directory"
                            // Set to current directory or its parent if it doesn't exist
                            currentDirectory = if (startingDir.exists()) startingDir else startingDir.parentFile
                          }
                          val result = fileChooser.showOpenDialog(null)
                          if (result == JFileChooser.APPROVE_OPTION) {
                            val selectedDir = fileChooser.selectedFile.absolutePath
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(logsDirectory = selectedDir)
                            }
                          }
                        }) {
                          Text("Change Location")
                        }

                        if (serverState.appConfig.logsDirectory != null) {
                          Button(onClick = {
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(logsDirectory = null)
                            }
                          }) {
                            Text("Reset to Default")
                          }
                        }
                      }
                      SelectableText(
                        text = "⚠️ Changing the logs directory requires restarting the app",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                      )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Root App Data Directory Configuration
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      SelectableText("Root App Data Directory", style = MaterialTheme.typography.bodyMedium)

                      // Compute the effective app data directory location
                      val effectiveAppDataDirectory = remember(serverState.appConfig) {
                        TrailblazeDesktopUtil.getEffectiveAppDataDirectory(serverState.appConfig)
                      }

                      SelectableText(
                        text = if (serverState.appConfig.appDataDirectory != null) {
                          effectiveAppDataDirectory
                        } else {
                          "Using default location ($effectiveAppDataDirectory)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                          // Determine the starting directory
                          val effectiveAppDataDir =
                            TrailblazeDesktopUtil.getEffectiveAppDataDirectory(serverState.appConfig)
                          val startingDir = File(effectiveAppDataDir)

                          // Open the directory in file browser
                          if (startingDir.exists()) {
                            TrailblazeDesktopUtil.openInFileBrowser(startingDir)
                          } else {
                            startingDir.mkdirs()
                            TrailblazeDesktopUtil.openInFileBrowser(startingDir)
                          }
                        }) {
                          Text("Open App Data Folder in Finder")
                        }
                        Button(onClick = {
                          // Determine the starting directory
                          val effectiveAppDataDir =
                            TrailblazeDesktopUtil.getEffectiveAppDataDirectory(serverState.appConfig)
                          val startingDir = File(effectiveAppDataDir)

                          val fileChooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Select Root App Data Directory"
                            currentDirectory = if (startingDir.exists()) startingDir else startingDir.parentFile
                          }
                          val result = fileChooser.showOpenDialog(null)
                          if (result == JFileChooser.APPROVE_OPTION) {
                            val selectedDir = fileChooser.selectedFile.absolutePath
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(appDataDirectory = selectedDir)
                            }
                          }
                        }) {
                          Text("Change Location")
                        }

                        if (serverState.appConfig.appDataDirectory != null) {
                          Button(onClick = {
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(appDataDirectory = null)
                            }
                          }) {
                            Text("Reset to Default")
                          }
                        }
                      }
                      SelectableText(
                        text = "⚠️ Changing the root app data directory requires restarting the app",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                      )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Trails Directory Configuration
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      SelectableText("Trails Directory", style = MaterialTheme.typography.bodyMedium)

                      // Compute the effective trails directory location
                      val effectiveTrailsDirectory = remember(serverState.appConfig) {
                        TrailblazeDesktopUtil.getEffectiveTrailsDirectory(serverState.appConfig)
                      }

                      SelectableText(
                        text = if (serverState.appConfig.trailsDirectory != null) {
                          effectiveTrailsDirectory
                        } else {
                          "Using default location ($effectiveTrailsDirectory)"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                          // Determine the starting directory for the file chooser
                          val effectiveTrailsDir =
                            TrailblazeDesktopUtil.getEffectiveTrailsDirectory(serverState.appConfig)
                          val startingDir = File(effectiveTrailsDir)

                          // Open the directory in file browser
                          if (startingDir.exists()) {
                            TrailblazeDesktopUtil.openInFileBrowser(startingDir)
                          } else {
                            startingDir.mkdirs()
                            TrailblazeDesktopUtil.openInFileBrowser(startingDir)
                          }
                        }) {
                          Text("Open Trails Folder in Finder")
                        }
                        Button(onClick = {
                          // Determine the starting directory for the file chooser
                          val effectiveTrailsDir =
                            TrailblazeDesktopUtil.getEffectiveTrailsDirectory(serverState.appConfig)
                          val startingDir = File(effectiveTrailsDir)

                          val fileChooser = JFileChooser().apply {
                            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                            dialogTitle = "Select Trails Directory"
                            currentDirectory = if (startingDir.exists()) startingDir else startingDir.parentFile
                          }
                          val result = fileChooser.showOpenDialog(null)
                          if (result == JFileChooser.APPROVE_OPTION) {
                            val selectedDir = fileChooser.selectedFile.absolutePath
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(trailsDirectory = selectedDir)
                            }
                          }
                        }) {
                          Text("Change Location")
                        }

                        if (serverState.appConfig.trailsDirectory != null) {
                          Button(onClick = {
                            trailblazeSettingsRepo.updateAppConfig {
                              it.copy(trailsDirectory = null)
                            }
                          }) {
                            Text("Reset to Default")
                          }
                        }
                      }
                      SelectableText(
                        text = "⚠️ Changing the trails directory requires restarting the app",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall
                      )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Preferences File
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      SelectableText("Preferences File", style = MaterialTheme.typography.bodyMedium)
                      Button(onClick = openDesktopAppPreferencesFile) {
                        Text("Open Preferences in Finder")
                      }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Development Debug Window
                    Column(
                      modifier = Modifier.fillMaxWidth(),
                      verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                      SelectableText("Development Debug Window", style = MaterialTheme.typography.bodyMedium)

                      PreferenceToggle(
                        label = "Show Debug Window",
                        description = "Show the debug window (also persists for next launch)",
                        checked = serverState.appConfig.showDebugPopOutWindow,
                        onCheckedChange = { checkedValue ->
                          trailblazeSettingsRepo.updateAppConfig {
                            it.copy(showDebugPopOutWindow = checkedValue)
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
      }
    }
  }
}