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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
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
import xyz.block.trailblaze.yaml.TrailblazeYaml

// Function to validate YAML
// This does a "soft" validation that checks YAML syntax but doesn't fail on unknown tools
// since the actual runner will have app-specific custom tools registered
private fun validateYaml(content: String): String? {
  if (content.isBlank()) {
    return null // Empty is not an error, just disable the button
  }

  // Check for common indentation issues before parsing
  val indentationError = checkIndentationIssues(content)
  if (indentationError != null) {
    return indentationError
  }

  return try {
    // Try to parse with default tools
    val trailblazeYaml = TrailblazeYaml()
    trailblazeYaml.decodeTrail(content)
    null // No error
  } catch (e: Exception) {
    val message = e.message ?: "Invalid YAML format"
    // Only ignore errors that are specifically about unknown/unregistered tools
    // The exact error format is: "TrailblazeYaml could not TrailblazeTool found with name: X. Did you register it?"
    val isUnknownToolError = message.contains("TrailblazeYaml could not TrailblazeTool found with name:")

    if (isUnknownToolError) {
      null // Allow unknown tools - they'll be registered when the test runs
    } else {
      // Return all other errors including syntax, structure issues
      message
    }
  }
}

// Check for common indentation problems that the YAML parser might not catch
private fun checkIndentationIssues(yaml: String): String? {
  val lines = yaml.lines()
  var inRecording = false
  var recordingIndent = 0

  for ((index, line) in lines.withIndex()) {
    if (line.isBlank()) continue

    val trimmed = line.trim()
    val indent = line.takeWhile { it == ' ' }.length

    // Detect when we enter a recording block
    if (trimmed == "recording:") {
      inRecording = true
      recordingIndent = indent
      continue
    }

    // Check for misaligned "tools:" within recording
    if (inRecording && trimmed == "tools:") {
      val expectedIndent = recordingIndent + 2
      if (indent != expectedIndent && indent != recordingIndent + 4) {
        return "Line ${index + 1}: 'tools:' has incorrect indentation ($indent spaces). " +
            "Expected $expectedIndent spaces to align with 'recording' block."
      }
    }

    // Exit recording block when we de-indent
    if (inRecording && indent <= recordingIndent && trimmed != "recording:") {
      inRecording = false
    }
  }

  return null
}

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
  val yamlContent = serverState.appConfig.yamlContent

  var isRunning by remember { mutableStateOf(false) }
  var progressMessages by remember { mutableStateOf<List<String>>(emptyList()) }
  var connectionStatus by remember { mutableStateOf<DeviceConnectionStatus?>(null) }
  var showDeviceSelectionDialog by remember { mutableStateOf(false) }

  // Local state for YAML content to avoid saving on every character
  var localYamlContent by remember(yamlContent) { mutableStateOf(yamlContent) }

  // YAML validation state
  var validationError by remember { mutableStateOf<String?>(null) }
  var isValidating by remember { mutableStateOf(false) }

  val coroutineScope = rememberCoroutineScope()

  // Debounce YAML content updates and validate
  LaunchedEffect(localYamlContent) {
    if (localYamlContent != yamlContent) {
      isValidating = true
      delay(500) // 500ms debounce
      if (localYamlContent != yamlContent) { // Check again after delay
        // Validate the YAML
        validationError = validateYaml(localYamlContent)

        // Save the content
        trailblazeSettingsRepo.updateAppConfig { appConfig ->
          appConfig.copy(
            yamlContent = localYamlContent
          )
        }
      }
      isValidating = false
    } else {
      // When content matches saved content, validate it immediately
      validationError = validateYaml(localYamlContent)
    }
  }


  LaunchedEffect(Unit) {
    deviceManager.loadDevices()
  }

  // Root Box to contain everything including dialogs
  Box(modifier = Modifier.fillMaxSize()) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
      Text(
        text = "YAML Test Runner",
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold
      )

      HorizontalDivider()

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "LLM Configuration:",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium
        )
        Text(
          text = "Provider: ${currentTrailblazeLlmModel.trailblazeLlmProvider.id}, Model: ${currentTrailblazeLlmModel.modelId}. Change settings in Settings tab.",
          style = MaterialTheme.typography.bodyMedium
        )
      }

      OutlinedCard(
        modifier = Modifier
          .fillMaxWidth()
          .weight(1f)
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
        ) {
          Text(
            text = "Trailblaze YAML",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
          )

          Spacer(modifier = Modifier.height(8.dp))

          OutlinedTextField(
            value = localYamlContent,
            onValueChange = { newContent ->
              localYamlContent = newContent
            },
            modifier = Modifier
              .fillMaxWidth()
              .weight(1f),
            textStyle = TextStyle(
              fontFamily = FontFamily.Monospace,
              fontSize = 14.sp
            ),
            placeholder = { Text("Enter your YAML test configuration here...") },
            enabled = !isRunning,
            isError = validationError != null,
            supportingText = {
              if (isValidating) {
                Text(
                  text = "Validating...",
                  color = MaterialTheme.colorScheme.primary,
                  style = MaterialTheme.typography.bodySmall
                )
              } else if (validationError != null) {
                Text(
                  text = "❌ $validationError",
                  color = MaterialTheme.colorScheme.error,
                  style = MaterialTheme.typography.bodySmall
                )
              } else if (localYamlContent.isNotBlank()) {
                Text(
                  text = "✓ Valid YAML",
                  color = Color(0xFF4CAF50),
                  style = MaterialTheme.typography.bodySmall
                )
              }
            }
          )
        }
      }

      if (progressMessages.isNotEmpty()) {
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

      connectionStatus?.let { status ->
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

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Button(
          onClick = {
            if (localYamlContent.isNotBlank()) {
              showDeviceSelectionDialog = true
            }
          },
          modifier = Modifier.weight(1f),
          enabled = !isRunning && localYamlContent.isNotBlank() && validationError == null
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
            onClick = {
              isRunning = false
              progressMessages = progressMessages + "Test execution stopped by user"
            },
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

          coroutineScope.launch {
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

            val runYamlRequest = RunYamlRequest(
              testName = "Yaml",
              yaml = yamlContent,
              trailblazeLlmModel = currentTrailblazeLlmModel,
              useRecordedSteps = true,
              targetAppName = serverState.appConfig.selectedTargetAppName,
              config = TrailblazeConfig(setOfMarkEnabled = setOfMarkEnabledConfig),
              trailFilePath = null,
            )

            val onConnectionStatus: (DeviceConnectionStatus) -> Unit = { status ->
              connectionStatus = status
            }

            val targetTestApp = deviceManager.getCurrentSelectedTargetApp()
            // Run on each selected device
            selectedDevices.forEach { device ->
              try {
                yamlRunner(
                  DesktopAppRunYamlParams(
                    device = device,
                    forceStopTargetApp = forceStopApp,
                    runYamlRequest = runYamlRequest,
                    onProgressMessage = onProgressMessage,
                    onConnectionStatus = onConnectionStatus,
                    targetTestApp = targetTestApp,
                    additionalInstrumentationArgs = additionalInstrumentationArgs
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
