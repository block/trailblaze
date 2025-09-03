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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.llm.DynamicLlmConfig
import xyz.block.trailblaze.llm.RunYamlRequest
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.TrailblazeLlmModelList
import xyz.block.trailblaze.llm.providers.OpenAITrailblazeLlmModelList
import xyz.block.trailblaze.mcp.models.AdbDevice
import xyz.block.trailblaze.mcp.models.DeviceConnectionStatus
import xyz.block.trailblaze.mcp.utils.DeviceConnectUtils
import xyz.block.trailblaze.mcp.utils.TargetTestApp
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.models.TrailblazeServerState

@Composable
fun YamlTabComposable(
  targetTestApp: TargetTestApp,
  serverState: TrailblazeServerState,
  availableLlmModelLists: Set<TrailblazeLlmModelList>,
  updateState: (TrailblazeServerState) -> Unit,
) {
  val yamlContent = serverState.appConfig.yamlContent

  var availableDevices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
  var selectedDevice by remember { mutableStateOf<AdbDevice?>(null) }
  var isDeviceMenuExpanded by remember { mutableStateOf(false) }
  var isLoadingDevices by remember { mutableStateOf(false) }
  var isRunning by remember { mutableStateOf(false) }
  var progressMessages by remember { mutableStateOf<List<String>>(emptyList()) }
  var connectionStatus by remember { mutableStateOf<DeviceConnectionStatus?>(null) }

  // Local state for YAML content to avoid saving on every character
  var localYamlContent by remember(yamlContent) { mutableStateOf(yamlContent) }

  val coroutineScope = rememberCoroutineScope()

  // Debounce Y AML content updates
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

  val currentModel: TrailblazeLlmModel = currentProviderModelList.entries.firstOrNull { it.modelId == savedModelId }
    ?: OpenAITrailblazeLlmModelList.OPENAI_GPT_4_1

  LaunchedEffect(Unit) {
    loadAvailableDevices(
      onDevicesLoaded = { devices ->
        availableDevices = devices
        selectedDevice = devices.firstOrNull()
      },
      onLoadingChanged = { loading -> isLoadingDevices = loading }
    )
  }

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
        text = "Target Device:",
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.Medium
      )

      ExposedDropdownMenuBox(
        expanded = isDeviceMenuExpanded,
        onExpandedChange = { isDeviceMenuExpanded = it },
        modifier = Modifier.weight(1f)
      ) {
        OutlinedTextField(
          value = selectedDevice?.let { "${it.name} (${it.id})" } ?: "No device selected",
          onValueChange = {},
          readOnly = true,
          trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDeviceMenuExpanded) },
          modifier = Modifier
            .menuAnchor()
            .fillMaxWidth(),
          enabled = !isLoadingDevices && !isRunning
        )

        ExposedDropdownMenu(
          expanded = isDeviceMenuExpanded,
          onDismissRequest = { isDeviceMenuExpanded = false }
        ) {
          availableDevices.forEach { device ->
            DropdownMenuItem(
              text = { Text("${device.name} (${device.id})") },
              onClick = {
                selectedDevice = device
                isDeviceMenuExpanded = false
              }
            )
          }
        }
      }

      IconButton(
        onClick = {
          coroutineScope.launch {
            loadAvailableDevices(
              onDevicesLoaded = { devices ->
                availableDevices = devices
                if (selectedDevice?.id !in devices.map { it.id }) {
                  selectedDevice = devices.firstOrNull()
                }
              },
              onLoadingChanged = { loading -> isLoadingDevices = loading }
            )
          }
        },
        enabled = !isLoadingDevices && !isRunning
      ) {
        if (isLoadingDevices) {
          CircularProgressIndicator(modifier = Modifier.width(24.dp))
        } else {
          Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
        }
      }
    }

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
        text = "Provider: ${currentProvider.id}, Model: ${currentModel.modelId}. Change settings in Settings tab.",
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
                text = "✓ Trailblaze instrumentation running on device: ${status.deviceId ?: "default"}",
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

    val dynamicLlmConfig = DynamicLlmConfig(
      modelId = currentModel.modelId,
      providerId = currentProvider.id,
      capabilityIds = currentModel.capabilityIds,
      contextLength = currentModel.contextLength
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      Button(
        onClick = {
          if (selectedDevice != null && localYamlContent.isNotBlank()) {
            coroutineScope.launch {
              runYamlOnDevice(
                device = selectedDevice!!,
                yamlContent = localYamlContent,
                targetTestApp = targetTestApp,
                onProgressMessage = { message ->
                  progressMessages = progressMessages + message
                },
                dynamicLlmConfig = dynamicLlmConfig,
                onStatusChanged = { running -> isRunning = running },
                onConnectionStatus = { status -> connectionStatus = status }
              )
            }
          }
        },
        enabled = !isRunning && selectedDevice != null && localYamlContent.isNotBlank(),
        modifier = Modifier.weight(1f)
      ) {
        if (isRunning) {
          CircularProgressIndicator(
            modifier = Modifier.width(16.dp),
            color = MaterialTheme.colorScheme.onPrimary
          )
          Spacer(modifier = Modifier.width(8.dp))
        } else {
          Icon(Icons.Default.PlayArrow, contentDescription = null)
          Spacer(modifier = Modifier.width(8.dp))
        }
        Text(if (isRunning) "Running..." else "Run YAML Test")
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
}

private suspend fun loadAvailableDevices(
  onDevicesLoaded: (List<AdbDevice>) -> Unit,
  onLoadingChanged: (Boolean) -> Unit,
) {
  withContext(Dispatchers.IO) {
    onLoadingChanged(true)
    try {
      val devices = DeviceConnectUtils.getAdbDevices()
      withContext(Dispatchers.Default) {
        onDevicesLoaded(devices)
      }
    } catch (e: Exception) {
      withContext(Dispatchers.Default) {
        onDevicesLoaded(emptyList())
      }
    } finally {
      withContext(Dispatchers.Default) {
        onLoadingChanged(false)
      }
    }
  }
}

private suspend fun runYamlOnDevice(
  device: AdbDevice,
  targetTestApp: TargetTestApp,
  dynamicLlmConfig: DynamicLlmConfig,
  yamlContent: String,
  onProgressMessage: (String) -> Unit,
  onStatusChanged: (Boolean) -> Unit,
  onConnectionStatus: (DeviceConnectionStatus) -> Unit,
) {
  withContext(Dispatchers.IO) {
    onStatusChanged(true)
    try {
      onProgressMessage("Starting YAML test execution on device: ${device.name} (${device.id})")

      val status = DeviceConnectUtils.connectToInstrumentationAndInstallAppIfNotAvailable(
        sendProgressMessage = onProgressMessage,
        deviceId = device.id,
        targetTestApp = targetTestApp,
      )

      withContext(Dispatchers.Default) {
        onConnectionStatus(status)

        DeviceConnectUtils.sentRequestStartTestWithYaml(
          RunYamlRequest(
            testName = "Yaml",
            yaml = yamlContent,
            dynamicLlmConfig = dynamicLlmConfig,
            useRecordedSteps = true,
          )
        )
      }

    } catch (e: Exception) {
      withContext(Dispatchers.Default) {
        onProgressMessage("Error: ${e.message}")
        onConnectionStatus(DeviceConnectionStatus.ConnectionFailure(e.message ?: "Unknown error"))
      }
    } finally {
      withContext(Dispatchers.Default) {
        onStatusChanged(false)
      }
    }
  }
}
