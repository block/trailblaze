@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.model.DesktopAppRunYamlParams
import xyz.block.trailblaze.model.DeviceConnectionStatus
import xyz.block.trailblaze.model.TrailblazeConfig
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DeviceSelectionDialog
import xyz.block.trailblaze.ui.composables.SelectableText

/**
 * Main YAML Tab composable that provides a combined text and visual editor for Trailblaze YAML files.
 * 
 * This composable orchestrates:
 * - Editor mode switching (Text vs Visual)
 * - YAML content persistence and validation
 * - Test execution on connected devices
 * - Progress and connection status display
 */
@Composable
fun YamlTabComposable(
  currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
  trailblazeSettingsRepo: TrailblazeSettingsRepo,
  deviceManager: TrailblazeDeviceManager,
  yamlRunner: (DesktopAppRunYamlParams) -> Unit,
  additionalInstrumentationArgs: (suspend () -> Map<String, String>),
) {
  val currentTrailblazeLlmModel = currentTrailblazeLlmModelProvider()
  val serverState by trailblazeSettingsRepo.serverStateFlow.collectAsState()
  val savedYamlContent = serverState.appConfig.yamlContent

  var isRunning by remember { mutableStateOf(false) }
  var progressMessages by remember { mutableStateOf<List<String>>(emptyList()) }
  var connectionStatus by remember { mutableStateOf<DeviceConnectionStatus?>(null) }
  var showDeviceSelectionDialog by remember { mutableStateOf(false) }
  
  // Editor mode state - Visual is the default
  var editorMode by remember { mutableStateOf(YamlEditorMode.VISUAL) }

  // Local state for YAML content - shared between text and visual editors
  var localYamlContent by remember(savedYamlContent) { mutableStateOf(savedYamlContent) }

  // YAML validation state
  var validationError by remember { mutableStateOf<String?>(null) }
  var isValidating by remember { mutableStateOf(false) }
  
  // Save state - shared across editors
  var saveSuccess by remember { mutableStateOf(false) }
  val hasUnsavedChanges = localYamlContent != savedYamlContent

  val coroutineScope = rememberCoroutineScope()

  // Function to save changes
  fun saveChanges() {
    trailblazeSettingsRepo.updateAppConfig { appConfig ->
      appConfig.copy(yamlContent = localYamlContent)
    }
    saveSuccess = true
  }

  // Debounce validation (but don't auto-save)
  LaunchedEffect(localYamlContent) {
    isValidating = true
    delay(300) // 300ms debounce for validation
    validationError = validateYaml(localYamlContent)
    isValidating = false
  }
  
  // Auto-dismiss save success message
  LaunchedEffect(saveSuccess) {
    if (saveSuccess && !hasUnsavedChanges) {
      delay(2000)
      saveSuccess = false
    }
  }


  LaunchedEffect(Unit) {
    deviceManager.loadDevices()
  }

  // Root Box to contain everything including dialogs
  Box(
    modifier = Modifier
      .fillMaxSize()
      .onKeyEvent { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown &&
          keyEvent.key == Key.S &&
          (keyEvent.isCtrlPressed || keyEvent.isMetaPressed)
        ) {
          if (hasUnsavedChanges && validationError == null) {
            saveChanges()
          }
          true
        } else {
          false
        }
      }
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      // Header row with title, editor mode toggle, and save button
      YamlEditorHeader(
        editorMode = editorMode,
        onEditorModeChange = { editorMode = it },
        hasUnsavedChanges = hasUnsavedChanges,
        validationError = validationError,
        saveSuccess = saveSuccess,
        onSave = { saveChanges() }
      )

      HorizontalDivider()

      // Editor area - switches between text and visual modes
      when (editorMode) {
        YamlEditorMode.TEXT -> {
          YamlTextEditor(
            yamlContent = localYamlContent,
            onYamlContentChange = { localYamlContent = it },
            isRunning = isRunning,
            validationError = validationError,
            isValidating = isValidating,
            modifier = Modifier.weight(1f)
          )
        }
        YamlEditorMode.VISUAL -> {
          YamlVisualEditor(
            yamlContent = localYamlContent,
            onYamlContentChange = { localYamlContent = it },
            modifier = Modifier.weight(1f)
          )
        }
      }

      // Progress Messages Panel
      if (progressMessages.isNotEmpty()) {
        ProgressMessagesPanel(progressMessages)
      }

      // Connection Status Panel
      connectionStatus?.let { status ->
        ConnectionStatusPanel(status)
      }

      // Run Controls
      RunControlsRow(
        isRunning = isRunning,
        yamlContent = localYamlContent,
        validationError = validationError,
        onRunClick = { showDeviceSelectionDialog = true },
        onStopClick = {
          isRunning = false
          progressMessages = progressMessages + "Test execution stopped by user"
        }
      )
    }

    // Device Selection Dialog
    if (showDeviceSelectionDialog) {
      DeviceSelectionDialog(
        deviceManager = deviceManager,
        settingsRepo = trailblazeSettingsRepo,
        onSelectionChanged = { selectedDeviceInstanceIds ->
          // Save selections immediately as they change
          trailblazeSettingsRepo.updateAppConfig { appConfig ->
            appConfig.copy(
              lastSelectedDeviceInstanceIds = selectedDeviceInstanceIds
            )
          }
        },
        onDismiss = {
          showDeviceSelectionDialog = false
        },
        onSessionClick = { sessionId ->
          // Navigate to the session details by updating the state
          trailblazeSettingsRepo.updateState {
            serverState.copy(
              appConfig = serverState.appConfig.copy(currentSessionId = sessionId)
            )
          }
        },
        onRunTests = { selectedDevices: List<TrailblazeConnectedDeviceSummary>, forceStopApp: Boolean ->
          showDeviceSelectionDialog = false
          trailblazeSettingsRepo.updateAppConfig { appConfig ->
            // Save selected device IDs
            appConfig.copy(
              lastSelectedDeviceInstanceIds = selectedDevices.map { it.instanceId }
            )
          }

          isRunning = true
          progressMessages = emptyList()
          connectionStatus = null

          val onProgressMessage: (String) -> Unit = { message ->
            progressMessages = progressMessages + message
          }

          val setOfMarkEnabledConfig = serverState.appConfig.setOfMarkEnabled
          onProgressMessage(
            "Set of Mark: ${if (setOfMarkEnabledConfig) "ENABLED" else "DISABLED"}"
          )

          val onConnectionStatus: (DeviceConnectionStatus) -> Unit = { status ->
            connectionStatus = status
          }

          val targetTestApp = deviceManager.getCurrentSelectedTargetApp()
          // Run on each selected device
          selectedDevices.forEach { device ->
            val runYamlRequest = RunYamlRequest(
              testName = "Yaml",
              yaml = localYamlContent,
              trailblazeLlmModel = currentTrailblazeLlmModel,
              useRecordedSteps = true,
              targetAppName = serverState.appConfig.selectedTargetAppName,
              config = TrailblazeConfig(
                setOfMarkEnabled = setOfMarkEnabledConfig,
                aiFallback = serverState.appConfig.aiFallbackEnabled,
                overrideSessionId = null,
              ),
              trailFilePath = null,
              trailblazeDeviceId = device.trailblazeDeviceId,
            )

            coroutineScope.launch {
              try {
                yamlRunner(
                  DesktopAppRunYamlParams(
                    forceStopTargetApp = forceStopApp,
                    runYamlRequest = runYamlRequest,
                    onProgressMessage = onProgressMessage,
                    onConnectionStatus = onConnectionStatus,
                    targetTestApp = targetTestApp,
                    additionalInstrumentationArgs = additionalInstrumentationArgs()
                  )
                )
              } catch (e: Exception) {
                onProgressMessage("Error on device ${device.instanceId}: ${e.message}")
              }
            }

            isRunning = false
          }
        },
      )
    }
  }
}

/**
 * Header row with title, editor mode toggle, and save button.
 */
@Composable
private fun YamlEditorHeader(
  editorMode: YamlEditorMode,
  onEditorModeChange: (YamlEditorMode) -> Unit,
  hasUnsavedChanges: Boolean,
  validationError: String?,
  saveSuccess: Boolean,
  onSave: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "YAML Editor",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
      )
      
      if (hasUnsavedChanges) {
        Text(
          text = "● Unsaved",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.primary
        )
      }
    }
    
    // Editor mode toggle and save button
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      FilterChip(
        selected = editorMode == YamlEditorMode.TEXT,
        onClick = { onEditorModeChange(YamlEditorMode.TEXT) },
        label = { Text("Text") },
        leadingIcon = {
          Icon(
            Icons.Filled.Code,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
        }
      )
      FilterChip(
        selected = editorMode == YamlEditorMode.VISUAL,
        onClick = { onEditorModeChange(YamlEditorMode.VISUAL) },
        label = { Text("Visual") },
        leadingIcon = {
          Icon(
            Icons.Filled.ViewModule,
            contentDescription = null,
            modifier = Modifier.size(18.dp)
          )
        }
      )
      
      Spacer(modifier = Modifier.width(8.dp))
      
      Button(
        onClick = onSave,
        enabled = hasUnsavedChanges && validationError == null,
        colors = ButtonDefaults.buttonColors(
          containerColor = if (hasUnsavedChanges && validationError == null) 
            MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.surfaceVariant
        )
      ) {
        Icon(
          Icons.Filled.Save,
          contentDescription = "Save",
          modifier = Modifier.size(18.dp).padding(end = 4.dp)
        )
        Text(
          text = when {
            saveSuccess && !hasUnsavedChanges -> "Saved!"
            validationError != null -> "Invalid"
            hasUnsavedChanges -> "Save"
            else -> "Saved"
          }
        )
      }
    }
  }
}

/**
 * Panel displaying progress messages during test execution.
 */
@Composable
private fun ProgressMessagesPanel(
  progressMessages: List<String>,
) {
  OutlinedCard(
    modifier = Modifier
      .fillMaxWidth()
      .height(200.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
    ) {
      Text(
        text = "Progress Messages",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
      )

      Spacer(modifier = Modifier.height(8.dp))

      val scrollState = rememberScrollState()

      // Auto-scroll to bottom when messages change
      LaunchedEffect(progressMessages.size) {
        scrollState.animateScrollTo(scrollState.maxValue)
      }

      Box(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(scrollState)
      ) {
        Column {
          progressMessages.forEach { message ->
            SelectableText(
              text = "• $message",
              style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp
              ),
              modifier = Modifier.padding(vertical = 2.dp)
            )
          }
        }
      }
    }
  }
}

/**
 * Panel displaying the current device connection status.
 */
@Composable
private fun ConnectionStatusPanel(
  status: DeviceConnectionStatus,
) {
  OutlinedCard(
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(16.dp)
    ) {
      Text(
        text = "Connection Status",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Medium
      )

      Spacer(modifier = Modifier.height(8.dp))

      when (status) {
        is DeviceConnectionStatus.WithTargetDevice.TrailblazeInstrumentationRunning -> {
          Text(
            text = "✓ Trailblaze running on device: ${status.trailblazeDeviceId}",
            color = Color(0xFF4CAF50)
          )
        }

        is DeviceConnectionStatus.DeviceConnectionError.ConnectionFailure -> {
          Text(
            text = "✗ Connection failed: ${status.errorMessage}",
            color = Color(0xFFF44336)
          )
        }

        is DeviceConnectionStatus.WithTargetDevice.StartingConnection -> {
          Text(
            text = "🔄 Starting connection to device: ${status.trailblazeDeviceId}",
            color = Color(0xFF2196F3)
          )
        }

        is DeviceConnectionStatus.DeviceConnectionError.NoConnection -> {
          Text(
            text = "⚪ No active connections",
            color = Color(0xFF9E9E9E)
          )
        }

        is DeviceConnectionStatus.DeviceConnectionError.ThereIsAlreadyAnActiveConnection -> {
          Text(
            text = "⚠️ Already connected to device: ${status.deviceId}",
            color = Color(0xFFFF9800)
          )
        }
      }
    }
  }
}

/**
 * Row containing run and stop controls for test execution.
 */
@Composable
private fun RunControlsRow(
  isRunning: Boolean,
  yamlContent: String,
  validationError: String?,
  onRunClick: () -> Unit,
  onStopClick: () -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp)
  ) {
    Button(
      onClick = onRunClick,
      modifier = Modifier.weight(1f),
      enabled = !isRunning && yamlContent.isNotBlank() && validationError == null
    ) {
      Icon(
        Icons.Filled.Laptop,
        contentDescription = null,
        modifier = Modifier.padding(end = 6.dp)
      )
      Text(
        if (isRunning) {
          "Running..."
        } else {
          "Run"
        }
      )
      Icon(
        Icons.Filled.PlayArrow,
        contentDescription = null,
        modifier = Modifier.padding(start = 6.dp)
      )
    }

    if (isRunning) {
      Button(
        onClick = onStopClick,
        colors = ButtonDefaults.buttonColors(
          containerColor = Color(0xFFF44336)
        )
      ) {
        Icon(Icons.Default.Close, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Stop")
      }
    }
  }
}
