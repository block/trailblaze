package xyz.block.trailblaze.ui.tabs.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.llm.LlmProviderEnvVarUtil
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.ui.desktoputil.DesktopUtil
import xyz.block.trailblaze.ui.desktoputil.EnvVarSaveResult
import xyz.block.trailblaze.ui.desktoputil.saveEnvVarToShellProfile
import xyz.block.trailblaze.ui.desktoputil.ShellProfileRestartRequiredDialog
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import xyz.block.trailblaze.host.devices.PlaywrightInstallState
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
    trailblazeSettingsRepo: TrailblazeSettingsRepo,
    showWebBrowser: Boolean = true,
    playwrightInstallState: PlaywrightInstallState? = null,
    onInstallPlaywright: (() -> Unit)? = null,
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
            text = { SelectableText("None") },
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

      // Show web browser options for WEB platform when a driver is selected
      if (platform == TrailblazeDevicePlatform.WEB && enabledDriverTypesMap[platform] != null) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column {
            Text("Show browser window", style = MaterialTheme.typography.bodyMedium)
            Text(
              "Open a visible Chrome window during web sessions",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = showWebBrowser,
            onCheckedChange = { checked ->
              trailblazeSettingsRepo.updateAppConfig { it.copy(showWebBrowser = checked) }
            },
          )
        }
      }

      // Show Playwright browser install button for WEB platform
      if (platform == TrailblazeDevicePlatform.WEB &&
        enabledDriverTypesMap[platform] != null &&
        playwrightInstallState != null &&
        onInstallPlaywright != null
      ) {
        when (playwrightInstallState) {
          is PlaywrightInstallState.NotInstalled, is PlaywrightInstallState.Error -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(onClick = onInstallPlaywright) {
                Text("Install Browser")
              }
              if (playwrightInstallState is PlaywrightInstallState.Error) {
                SelectableText(
                  "Error: ${playwrightInstallState.message}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                )
              }
            }
          }
          is PlaywrightInstallState.Installing, is PlaywrightInstallState.Checking -> {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
              )
              SelectableText(
                if (playwrightInstallState is PlaywrightInstallState.Installing) "Installing browser..." else "Checking...",
                style = MaterialTheme.typography.bodySmall,
              )
            }
          }
          is PlaywrightInstallState.Installed -> {
            SelectableText(
              "Browser ready",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
          is PlaywrightInstallState.Unknown -> {
            // Don't show anything until check completes
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
    openShellProfile: (() -> Unit)? = null,
    shellProfileName: String? = null,
    onQuitApp: (() -> Unit)? = null,
    isProviderLocked: Boolean = false,
    playwrightInstallState: StateFlow<PlaywrightInstallState>? = null,
    onInstallPlaywright: (() -> Unit)? = null,
  ) {

    val serverState: TrailblazeServerState by trailblazeSettingsRepo.serverStateFlow.collectAsState()
    // Provider lock state: starts locked if isProviderLocked is true, can be unlocked via dialog
    var isProviderUnlocked by remember { mutableStateOf(!isProviderLocked) }
    var showProviderUnlockWarning by remember { mutableStateOf(false) }
    var showShellProfileRestartDialog by remember { mutableStateOf(false) }
    var restartDialogMessage by remember { mutableStateOf("") }

    // State for configuring environment variables
    var envVarBeingConfigured by remember { mutableStateOf<String?>(null) }
    var envVarInputValue by remember { mutableStateOf("") }
    var envVarSaveError by remember { mutableStateOf<String?>(null) }

    val shellProfile = remember { DesktopUtil.getShellProfileFile() }
    val effectiveShellProfileName = shellProfileName ?: shellProfile?.name

    // Dialog prompting user to restart after editing shell profile
    if (showShellProfileRestartDialog && effectiveShellProfileName != null && onQuitApp != null) {
      ShellProfileRestartRequiredDialog(
        title = "Restart Required",
        message = restartDialogMessage,
        onDismiss = { showShellProfileRestartDialog = false },
        onQuit = onQuitApp,
      )
    }

    // Dialog warning before unlocking provider switching
    if (showProviderUnlockWarning) {
      AlertDialog(
        onDismissRequest = { showProviderUnlockWarning = false },
        title = { Text("Unlock Provider Switching?") },
        text = {
          Text(
            "Changing the LLM provider from the default may affect test reliability " +
              "and is intended for development purposes only.",
            style = MaterialTheme.typography.bodyMedium,
          )
        },
        confirmButton = {
          Button(
            onClick = {
              showProviderUnlockWarning = false
              isProviderUnlocked = true
            }
          ) {
            Text("Unlock")
          }
        },
        dismissButton = {
          TextButton(onClick = { showProviderUnlockWarning = false }) {
            Text("Cancel")
          }
        },
      )
    }

    // Dialog for configuring an environment variable
    if (envVarBeingConfigured != null && effectiveShellProfileName != null) {
      ConfigureEnvVarDialog(
        variableName = envVarBeingConfigured!!,
        shellProfileName = effectiveShellProfileName,
        inputValue = envVarInputValue,
        onInputChange = {
          envVarInputValue = it
          envVarSaveError = null
        },
        errorMessage = envVarSaveError,
        onDismiss = {
          envVarBeingConfigured = null
          envVarInputValue = ""
          envVarSaveError = null
        },
        onSave = {
          val result = saveEnvVarToShellProfile(
            variableName = envVarBeingConfigured!!,
            value = envVarInputValue.trim(),
            shellProfile = shellProfile,
          )
          when (result) {
            is EnvVarSaveResult.Success -> {
              restartDialogMessage = "${envVarBeingConfigured!!} has been saved to $effectiveShellProfileName.\n\n" +
                "Quit and reopen the app for the changes to take effect."
              envVarBeingConfigured = null
              envVarInputValue = ""
              envVarSaveError = null
              showShellProfileRestartDialog = true
            }
            is EnvVarSaveResult.Error -> {
              envVarSaveError = result.message
            }
          }
        },
      )
    }

    // --- Section composables for responsive layout ---

    val quickActionsBar: @Composable () -> Unit = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Button(onClick = { openGoose() }) { SelectableText("Open Goose") }
      }
    }

    val applicationSettingsSection: @Composable () -> Unit = {
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

        Spacer(modifier = Modifier.height(8.dp))

        PreferenceToggle(
          label = "Show Devices Tab",
          description = "Show the Devices tab in the navigation rail",
          checked = serverState.appConfig.showDevicesTab,
          onCheckedChange = { checkedValue ->
            trailblazeSettingsRepo.updateAppConfig {
              it.copy(showDevicesTab = checkedValue)
            }
          }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PreferenceToggle(
          label = "Show Record Tab",
          description = "Show the Record tab for interactive test recording",
          checked = serverState.appConfig.showRecordTab,
          onCheckedChange = { checkedValue ->
            trailblazeSettingsRepo.updateAppConfig {
              it.copy(showRecordTab = checkedValue)
            }
          }
        )

        Spacer(modifier = Modifier.height(8.dp))

        PreferenceToggle(
          label = "Show Device Status Panel",
          description = "Show floating device status overlay in the bottom-right corner",
          checked = serverState.appConfig.showDeviceStatusPanel,
          onCheckedChange = { checkedValue ->
            trailblazeSettingsRepo.updateAppConfig {
              it.copy(showDeviceStatusPanel = checkedValue)
            }
          }
        )

        HorizontalDivider()

        PreferenceToggle(
          label = "Capture Logcat",
          description = "Capture device logs filtered to app under test (Android logcat / iOS log stream)",
          checked = serverState.appConfig.captureLogcat,
          onCheckedChange = { checkedValue ->
            trailblazeSettingsRepo.updateAppConfig {
              it.copy(captureLogcat = checkedValue)
            }
          }
        )
      }
    }

    val languageModelSection: @Composable () -> Unit = {
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

        // Agent Implementation Selection
        var showAgentImplMenu by remember { mutableStateOf(false) }
        val agentImplOptions = listOf(
          AgentImplementation.TRAILBLAZE_RUNNER to "TrailblazeRunner (Legacy)",
          AgentImplementation.MULTI_AGENT_V3 to "Multi-Agent V3"
        )
        val currentAgentImplLabel =
          agentImplOptions.find { it.first == serverState.appConfig.agentImplementation }?.second
            ?: "TrailblazeRunner (Legacy)"

        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          SelectableText("Agent Implementation", style = MaterialTheme.typography.bodyMedium)
          SelectableText(
            text = "Controls which architecture handles the agent loop",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          ExposedDropdownMenuBox(
            expanded = showAgentImplMenu,
            onExpandedChange = { showAgentImplMenu = !showAgentImplMenu }
          ) {
            OutlinedTextField(
              modifier = Modifier.fillMaxWidth().menuAnchor(),
              value = currentAgentImplLabel,
              onValueChange = {},
              readOnly = true,
              trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(
                  expanded = showAgentImplMenu
                )
              }
            )
            DropdownMenu(
              expanded = showAgentImplMenu,
              onDismissRequest = { showAgentImplMenu = false }
            ) {
              agentImplOptions.forEach { (agentImpl, label) ->
                DropdownMenuItem(
                  text = { SelectableText(label) },
                  onClick = {
                    showAgentImplMenu = false
                    trailblazeSettingsRepo.updateAppConfig {
                      it.copy(agentImplementation = agentImpl)
                    }
                  }
                )
              }
            }
          }
        }

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
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            ExposedDropdownMenuBox(
              expanded = showLlmProviderMenu,
              onExpandedChange = {
                if (isProviderUnlocked) {
                  showLlmProviderMenu = !showLlmProviderMenu
                }
              },
              modifier = Modifier.weight(1f)
            ) {
              OutlinedTextField(
                modifier = Modifier.fillMaxWidth()
                  .menuAnchor(MenuAnchorType.PrimaryNotEditable),
                value = currentProvider.display,
                onValueChange = {},
                readOnly = true,
                enabled = isProviderUnlocked,
                trailingIcon = {
                  ExposedDropdownMenuDefaults.TrailingIcon(
                    expanded = showLlmProviderMenu
                  )
                }
              )
              if (isProviderUnlocked) {
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
            // Lock/unlock icon button (only shown when provider locking is configured)
            if (isProviderLocked) {
              IconButton(
                onClick = {
                  if (isProviderUnlocked) {
                    // Re-lock provider switching without changing the current selection.
                    isProviderUnlocked = false
                  } else {
                    showProviderUnlockWarning = true
                  }
                }
              ) {
                Icon(
                  imageVector = if (isProviderUnlocked) Icons.Default.LockOpen else Icons.Default.Lock,
                  contentDescription = if (isProviderUnlocked) "Lock provider switching" else "Unlock provider switching",
                  tint = if (isProviderUnlocked) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
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

    val deviceTargetsSection: @Composable () -> Unit = {
      SettingsSection(title = "Device Targets") {
        val allDriverTypes = trailblazeSettingsRepo.getAllSupportedDriverTypes()
        // Use the reactive serverState instead of calling getEnabledDriverTypesMap()
        val enabledDriverTypesMap = serverState.appConfig.selectedTrailblazeDriverTypes

        // Group drivers by platform
        val driversByPlatform = allDriverTypes.groupBy { it.platform }

        // Collect the playwright install state
        val currentPlaywrightState = playwrightInstallState?.collectAsState()?.value

        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
          driversByPlatform.forEach { (platform, driversForPlatform) ->
            PlatformConfigurationRow(
              platform = platform,
              driversForPlatform = driversForPlatform,
              enabledDriverTypesMap = enabledDriverTypesMap,
              trailblazeSettingsRepo = trailblazeSettingsRepo,
              showWebBrowser = serverState.appConfig.showWebBrowser,
              playwrightInstallState = currentPlaywrightState,
              onInstallPlaywright = onInstallPlaywright,
            )
          }
        }
      }
    }

    val globalSettingsSection: @Composable () -> Unit = {
      SettingsSection(title = "Global Settings") {
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          globalSettingsContent(serverState)
        }
      }
    }

    val environmentVariablesSection: @Composable () -> Unit = {
      SettingsSection(title = "Environment Variables") {
        val openAiEnvVarName =
          LlmProviderEnvVarUtil.getEnvironmentVariableKeyForProvider(
            TrailblazeLlmProvider.OPENAI
          )
        val envVariableNames =
          (listOfNotNull(openAiEnvVarName) + customEnvVariableNames)

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          envVariableNames.forEach { name ->
            val value = environmentVariableProvider(name)
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

              if (hasValue) {
                // Show masked value for set variables
                SelectableText(
                  text = "\u2022\u2022\u2022\u2022\u2022" + value!!.takeLast(4),
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  style = MaterialTheme.typography.bodyMedium
                )
              } else {
                // Show Configure button for unset variables
                if (effectiveShellProfileName != null) {
                  OutlinedButton(
                    onClick = {
                      envVarBeingConfigured = name
                      envVarInputValue = ""
                      envVarSaveError = null
                    }
                  ) {
                    Text("Configure")
                  }
                } else {
                  SelectableText(
                    text = "Not set",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        HorizontalDivider()

        Spacer(modifier = Modifier.height(8.dp))

        SelectableText(
          text = "\uD83D\uDCA1 Tip: After configuring environment variables, restart the app to apply changes.",
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          style = MaterialTheme.typography.bodySmall
        )

        // Button to open shell profile for manual editing
        if (openShellProfile != null && effectiveShellProfileName != null) {
          Spacer(modifier = Modifier.height(8.dp))

          OutlinedButton(
            onClick = {
              openShellProfile()
              restartDialogMessage = "$effectiveShellProfileName has been opened in your default text editor.\n\n" +
                "After making changes, save the file, then quit and reopen the app for the changes to take effect."
              showShellProfileRestartDialog = true
            }
          ) {
            Text("Edit $effectiveShellProfileName manually")
          }
        }
      }
    }

    val advancedConfigSection: @Composable () -> Unit = {
      SettingsSection(title = "Advanced Configuration") {
        // Server Port Configuration
        Column(
          modifier = Modifier.fillMaxWidth(),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          SelectableText("Server Ports", style = MaterialTheme.typography.bodyMedium)
          SelectableText(
            text = "Custom ports allow running multiple Trailblaze instances simultaneously. " +
              "Save and restart the app to apply changes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          // Local draft state so edits don't auto-save
          var draftHttpPort by remember(serverState.appConfig.serverPort) {
            mutableStateOf(serverState.appConfig.serverPort.toString())
          }
          var draftHttpsPort by remember(serverState.appConfig.serverHttpsPort) {
            mutableStateOf(serverState.appConfig.serverHttpsPort.toString())
          }

          val draftHttpPortInt = draftHttpPort.toIntOrNull()
          val draftHttpsPortInt = draftHttpsPort.toIntOrNull()
          val isHttpPortValid = draftHttpPortInt != null && draftHttpPortInt in 1024..65535
          val isHttpsPortValid = draftHttpsPortInt != null && draftHttpsPortInt in 1024..65535

          val hasUnsavedChanges = (draftHttpPort != serverState.appConfig.serverPort.toString()) ||
            (draftHttpsPort != serverState.appConfig.serverHttpsPort.toString())

          Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxWidth()
          ) {
            // HTTP Port
            OutlinedTextField(
              modifier = Modifier.weight(1f),
              value = draftHttpPort,
              onValueChange = { draftHttpPort = it },
              label = { Text("HTTP Port") },
              isError = draftHttpPort.isNotEmpty() && !isHttpPortValid,
              singleLine = true,
            )

            // HTTPS Port
            OutlinedTextField(
              modifier = Modifier.weight(1f),
              value = draftHttpsPort,
              onValueChange = { draftHttpsPort = it },
              label = { Text("HTTPS Port") },
              isError = draftHttpsPort.isNotEmpty() && !isHttpsPortValid,
              singleLine = true,
            )
          }

          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
              onClick = {
                trailblazeSettingsRepo.updateAppConfig {
                  it.copy(
                    serverPort = draftHttpPortInt!!,
                    serverHttpsPort = draftHttpsPortInt!!,
                    serverUrl = "http://localhost:$draftHttpPortInt",
                  )
                }
                restartDialogMessage = "Server ports have been updated. Restart Trailblaze for the new ports to take effect."
                showShellProfileRestartDialog = true
              },
              enabled = hasUnsavedChanges && isHttpPortValid && isHttpsPortValid,
            ) {
              Text("Save")
            }

            if (serverState.appConfig.serverPort != TrailblazeServerState.HTTP_PORT ||
              serverState.appConfig.serverHttpsPort != TrailblazeServerState.HTTPS_PORT
            ) {
              OutlinedButton(onClick = {
                draftHttpPort = TrailblazeServerState.HTTP_PORT.toString()
                draftHttpsPort = TrailblazeServerState.HTTPS_PORT.toString()
                trailblazeSettingsRepo.updateAppConfig {
                  it.copy(
                    serverPort = TrailblazeServerState.HTTP_PORT,
                    serverHttpsPort = TrailblazeServerState.HTTPS_PORT,
                    serverUrl = "http://localhost:${TrailblazeServerState.HTTP_PORT}",
                  )
                }
                restartDialogMessage = "Server ports have been reset to defaults. Restart Trailblaze for the changes to take effect."
                showShellProfileRestartDialog = true
              }) {
                Text("Reset to Defaults")
              }
            }
          }

          SelectableText(
            text = "Can also be set via TRAILBLAZE_PORT / TRAILBLAZE_HTTPS_PORT env vars, " +
              "or --port / --https-port CLI flags (which take highest priority).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

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
            text = "\u26A0\uFE0F Changing the logs directory requires restarting the app",
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
            text = "\u26A0\uFE0F Changing the root app data directory requires restarting the app",
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
            text = "\u26A0\uFE0F Changing the trails directory requires restarting the app",
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

    // --- Responsive layout ---

    MaterialTheme {
      Surface(
        modifier = Modifier
          .fillMaxSize(),
      ) {
        Column {
          Row {
            additionalContent()
          }
          BoxWithConstraints(
            modifier = Modifier.fillMaxWidth().weight(1f),
          ) {
            val useWideLayout = maxWidth >= 900.dp
            val scrollState = rememberScrollState()
            Box(
              modifier = Modifier.fillMaxSize(),
              contentAlignment = Alignment.TopCenter
            ) {
              Column(
                modifier = Modifier
                  .verticalScroll(scrollState)
                  .padding(24.dp)
                  .widthIn(max = if (useWideLayout) 1200.dp else 600.dp)
                  .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(20.dp),
              ) {
                quickActionsBar()
                if (useWideLayout) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                  ) {
                    Column(
                      modifier = Modifier.weight(1f),
                      verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                      applicationSettingsSection()
                      environmentVariablesSection()
                    }
                    Column(
                      modifier = Modifier.weight(1f),
                      verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                      languageModelSection()
                      deviceTargetsSection()
                      globalSettingsSection()
                    }
                  }
                  advancedConfigSection()
                } else {
                  applicationSettingsSection()
                  languageModelSection()
                  deviceTargetsSection()
                  globalSettingsSection()
                  environmentVariablesSection()
                  advancedConfigSection()
                }
              }
            }
          }
        }
      }
    }
  }

  /**
   * Dialog for configuring an environment variable by saving it to the shell profile.
   */
  @Composable
  private fun ConfigureEnvVarDialog(
    variableName: String,
    shellProfileName: String,
    inputValue: String,
    onInputChange: (String) -> Unit,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onSave: () -> Unit,
  ) {
    AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("Configure $variableName") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
          Text(
            text = "Enter the value for $variableName. This will be saved to ~/$shellProfileName " +
              "as a standard shell export statement.",
            style = MaterialTheme.typography.bodyMedium,
          )

          OutlinedTextField(
            value = inputValue,
            onValueChange = onInputChange,
            label = { Text(variableName) },
            placeholder = { Text("Paste your value here") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
          )

          // Error message
          if (errorMessage != null) {
            Text(
              text = errorMessage,
              color = MaterialTheme.colorScheme.error,
              style = MaterialTheme.typography.bodySmall,
            )
          }

          // Security notice
          Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
              Text("\u26A0\uFE0F", style = MaterialTheme.typography.bodyMedium)
              Text(
                text = "This value will be stored in plain text in your shell profile (~/$shellProfileName). " +
                  "This is the standard way to configure environment variables on your machine, " +
                  "but be aware that anyone with access to your user account can read this file.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = onSave,
          enabled = inputValue.isNotBlank(),
        ) {
          Text("Save to $shellProfileName")
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) {
          Text("Cancel")
        }
      },
    )
  }
}
