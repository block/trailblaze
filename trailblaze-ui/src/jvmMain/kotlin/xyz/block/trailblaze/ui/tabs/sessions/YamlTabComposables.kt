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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.mcp.models.DeviceConnectionStatus
import xyz.block.trailblaze.model.TargetTestApp
import xyz.block.trailblaze.ui.DeviceManager
import xyz.block.trailblaze.ui.composables.DeviceSelectionDialog
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.getIcon
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.ui.yaml.DesktopYamlRunner

@Composable
fun YamlTabComposable(
  targetTestApp: TargetTestApp,
  serverState: TrailblazeServerState,
  availableLlmModelLists: Set<TrailblazeLlmModelList>,
  updateState: (TrailblazeServerState) -> Unit,
  deviceManager: DeviceManager,
  yamlRunner: DesktopYamlRunner,
) {
  val yamlContent = serverState.appConfig.yamlContent

  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val availableDevices = deviceState.availableDevices
  val isLoadingDevices = deviceState.isLoading

  var isRunning by remember { mutableStateOf(false) }
  var progressMessages by remember { mutableStateOf<List<String>>(emptyList()) }
  var connectionStatus by remember { mutableStateOf<DeviceConnectionStatus?>(null) }
  var showDeviceSelectionDialog by remember { mutableStateOf(false) }

  // Local state for YAML content to avoid saving on every character
  var localYamlContent by remember(yamlContent) { mutableStateOf(yamlContent) }

  val coroutineScope = rememberCoroutineScope()

  // Debounce YAML content updates
  LaunchedEffect(localYamlContent) {
    if (localYamlContent != yamlContent) {
      delay(500) // 500ms debounce
      if (localYamlContent != yamlContent) { // Check again after delay
        updateState(
          serverState.copy(
            appConfig = serverState.appConfig.copy(
              yamlContent = localYamlContent
            )
          )
        )
      }
    }
  }

  val savedProviderId = serverState.appConfig.llmProvider
  val savedModelId: String = serverState.appConfig.llmModel
  val currentProviderModelList = availableLlmModelLists.firstOrNull { it.provider.id == savedProviderId }
    ?: OpenAITrailblazeLlmModelList
  val currentProvider = currentProviderModelList.provider

  val selectedTrailblazeLlmModel: TrailblazeLlmModel =
    currentProviderModelList.entries.firstOrNull { it.modelId == savedModelId }
      ?: OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1


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
          text = "Provider: ${currentProvider.id}, Model: ${selectedTrailblazeLlmModel.modelId}. Change settings in Settings tab.",
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
              .fillMaxSize(),
            textStyle = TextStyle(
              fontFamily = FontFamily.Monospace,
              fontSize = 14.sp
            ),
            placeholder = { Text("Enter your YAML test configuration here...") },
            enabled = !isRunning
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

            Box(
              modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
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
              is DeviceConnectionStatus.TrailblazeInstrumentationRunning -> {
                Text(
                  text = "✓ Trailblaze running on device: ${status.deviceId ?: "default"}",
                  color = Color(0xFF4CAF50)
                )
              }

              is DeviceConnectionStatus.ConnectionFailure -> {
                Text(
                  text = "✗ Connection failed: ${status.errorMessage}",
                  color = Color(0xFFF44336)
                )
              }

              is DeviceConnectionStatus.StartingConnection -> {
                Text(
                  text = "🔄 Starting connection to device: ${status.deviceId ?: "default"}",
                  color = Color(0xFF2196F3)
                )
              }

              is DeviceConnectionStatus.NoConnection -> {
                Text(
                  text = "⚪ No active connections",
                  color = Color(0xFF9E9E9E)
                )
              }

              is DeviceConnectionStatus.ThereIsAlreadyAnActiveConnection -> {
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
          enabled = !isRunning && localYamlContent.isNotBlank()
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
        availableDevices = availableDevices,
        isLoadingDevices = isLoadingDevices,
        onRefreshDevices = { deviceManager.loadDevices() },
        lastSelectedDeviceInstanceIds = serverState.appConfig.lastSelectedDeviceInstanceIds,
        onSelectionChanged = { selectedDeviceInstanceIds ->
          // Save selections immediately as they change
          updateState(
            serverState.copy(
              appConfig = serverState.appConfig.copy(
                lastSelectedDeviceInstanceIds = selectedDeviceInstanceIds
              )
            )
          )
        },
        onRunTests = { selectedDevices ->
          showDeviceSelectionDialog = false

          // Save selected device IDs
          updateState(
            serverState.copy(
              appConfig = serverState.appConfig.copy(
                lastSelectedDeviceInstanceIds = selectedDevices.map { it.instanceId }
              )
            )
          )

          coroutineScope.launch {
            isRunning = true
            progressMessages = emptyList()
            connectionStatus = null

            val runYamlRequest = RunYamlRequest(
              testName = "Yaml",
              yaml = yamlContent,
              trailblazeLlmModel = selectedTrailblazeLlmModel,
              useRecordedSteps = true,
            )

            val onProgressMessage: (String) -> Unit = { message ->
              progressMessages = progressMessages + message
            }

            val onConnectionStatus: (DeviceConnectionStatus) -> Unit = { status ->
              connectionStatus = status
            }

            // Run on each selected device
            selectedDevices.forEach { device ->
              try {
                yamlRunner.runYaml(device, runYamlRequest, onProgressMessage, onConnectionStatus)
              } catch (e: Exception) {
                onProgressMessage("Error on device ${device.instanceId}: ${e.message}")
              }
            }

            isRunning = false
          }
        },
        onDismiss = { showDeviceSelectionDialog = false },
        allowMultipleSelection = true
      )
    }
  }
}