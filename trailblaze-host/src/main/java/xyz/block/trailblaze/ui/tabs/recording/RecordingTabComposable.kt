package xyz.block.trailblaze.ui.tabs.recording

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
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.host.recording.MaestroDeviceScreenStream
import xyz.block.trailblaze.host.recording.MaestroInteractionToolFactory
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.playwright.recording.PlaywrightInteractionToolFactory
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.InteractionRecorder
import xyz.block.trailblaze.recording.InteractionToolFactory
import xyz.block.trailblaze.host.recording.RecordingLlmService
import xyz.block.trailblaze.llm.TrailblazeLlmModel
import xyz.block.trailblaze.llm.providers.TrailblazeDynamicLlmTokenProvider
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/** State for a connected recording device. */
private data class RecordingDeviceConnection(
  val stream: DeviceScreenStream,
  val toolFactory: InteractionToolFactory,
  val deviceLabel: String,
)

/** Connection lifecycle state. */
private sealed interface ConnectionState {
  data object Idle : ConnectionState
  data object Connecting : ConnectionState
  data class Connected(val connection: RecordingDeviceConnection) : ConnectionState
  data class Error(val message: String) : ConnectionState
}

/**
 * Top-level composable for the Record tab. Provides a device dropdown/switcher
 * and manages the lifecycle of the [DeviceScreenStream] and [InteractionRecorder].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingTabComposable(
  deviceManager: TrailblazeDeviceManager,
  currentTrailblazeLlmModelProvider: () -> TrailblazeLlmModel,
  llmTokenProvider: TrailblazeDynamicLlmTokenProvider,
  onSaveTrail: (String) -> Unit,
) {
  val scope = rememberCoroutineScope()
  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val webBrowserState by deviceManager.webBrowserStateFlow.collectAsState()

  // Build the list of available devices for recording
  val availableDevices: List<TrailblazeConnectedDeviceSummary> = remember(deviceState, webBrowserState) {
    buildList {
      // Add web browser if running
      if (webBrowserState is WebBrowserState.Running) {
        deviceManager.webBrowserManager.getRunningBrowserSummary()?.let { add(it) }
      }
      // Add mobile devices
      deviceState.devices.values
        .map { it.device }
        .filter { it.platform == TrailblazeDevicePlatform.ANDROID || it.platform == TrailblazeDevicePlatform.IOS }
        .forEach { add(it) }
    }
  }

  var selectedDevice by remember { mutableStateOf<TrailblazeConnectedDeviceSummary?>(null) }
  var connectionState by remember { mutableStateOf<ConnectionState>(ConnectionState.Idle) }
  var recorder by remember { mutableStateOf<InteractionRecorder?>(null) }

  // Auto-select the first device if none selected, or clear if selected device is gone
  LaunchedEffect(availableDevices, selectedDevice) {
    if (selectedDevice == null && availableDevices.isNotEmpty()) {
      selectedDevice = availableDevices.first()
    } else if (selectedDevice != null && selectedDevice !in availableDevices) {
      selectedDevice = availableDevices.firstOrNull()
      connectionState = ConnectionState.Idle
      recorder = null
    }
  }

  // Clean up on dispose
  DisposableEffect(Unit) {
    onDispose {
      connectionState = ConnectionState.Idle
      recorder = null
    }
  }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
  ) {
    // Device selector row
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text(
        text = "Device:",
        style = MaterialTheme.typography.titleSmall,
      )

      if (availableDevices.isEmpty()) {
        Text(
          text = "No devices available. Connect a mobile device or launch a web browser from the Devices tab.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        DeviceDropdown(
          devices = availableDevices,
          selectedDevice = selectedDevice,
          onDeviceSelected = { device ->
            if (device != selectedDevice) {
              selectedDevice = device
              connectionState = ConnectionState.Idle
              recorder = null
            }
          },
          modifier = Modifier.weight(1f),
        )

        val currentSelection = selectedDevice
        when (connectionState) {
          is ConnectionState.Idle, is ConnectionState.Error -> {
            Button(
              onClick = {
                if (currentSelection == null) return@Button
                connectionState = ConnectionState.Connecting
                scope.launch {
                  val result = connectToDevice(currentSelection, deviceManager)
                  connectionState = result
                  if (result is ConnectionState.Connected) {
                    val session = TrailblazeSession(
                      sessionId = SessionId("recording-${Clock.System.now().toEpochMilliseconds()}"),
                      startTime = Clock.System.now(),
                    )
                    recorder = InteractionRecorder(
                      logger = TrailblazeLogger.createNoOp(),
                      session = session,
                      scope = scope,
                      toolFactory = result.connection.toolFactory,
                    )
                  }
                }
              },
              enabled = currentSelection != null,
            ) {
              Text("Connect")
            }
          }
          is ConnectionState.Connecting -> {
            CircularProgressIndicator(
              modifier = Modifier.padding(4.dp),
              strokeWidth = 2.dp,
            )
            Text("Connecting...", style = MaterialTheme.typography.bodySmall)
          }
          is ConnectionState.Connected -> {
            Text(
              text = "Connected",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
          }
        }
      }
    }

    // Show error if any
    if (connectionState is ConnectionState.Error) {
      Spacer(Modifier.height(4.dp))
      Text(
        text = (connectionState as ConnectionState.Error).message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }

    Spacer(Modifier.height(12.dp))

    // Main content
    val connected = connectionState as? ConnectionState.Connected
    val currentRecorder = recorder
    if (connected != null && currentRecorder != null) {
      val currentLlmModel = currentTrailblazeLlmModelProvider()
      val llmService = remember(currentLlmModel, llmTokenProvider) {
        RecordingLlmService(
          trailblazeLlmModel = currentLlmModel,
          tokenProvider = llmTokenProvider,
        )
      }
      DisposableEffect(llmService) {
        onDispose { llmService.close() }
      }
      RecordingScreenComposable(
        stream = connected.connection.stream,
        recorder = currentRecorder,
        llmService = llmService,
        onSaveTrail = onSaveTrail,
      )
    } else if (connectionState !is ConnectionState.Connecting) {
      Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = if (availableDevices.isEmpty()) {
            "Connect a mobile device or launch a browser from the Devices tab to start recording."
          } else {
            "Select a device and click Connect to start recording."
          },
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Dropdown for selecting a device to record from. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeviceDropdown(
  devices: List<TrailblazeConnectedDeviceSummary>,
  selectedDevice: TrailblazeConnectedDeviceSummary?,
  onDeviceSelected: (TrailblazeConnectedDeviceSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  var expanded by remember { mutableStateOf(false) }

  ExposedDropdownMenuBox(
    expanded = expanded,
    onExpandedChange = { expanded = it },
    modifier = modifier,
  ) {
    OutlinedTextField(
      value = selectedDevice?.let { formatDeviceLabel(it) } ?: "Select device",
      onValueChange = {},
      readOnly = true,
      singleLine = true,
      trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
      modifier = Modifier
        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
        .fillMaxWidth(),
    )
    ExposedDropdownMenu(
      expanded = expanded,
      onDismissRequest = { expanded = false },
    ) {
      devices.forEach { device ->
        DropdownMenuItem(
          text = {
            Column {
              Text(
                text = formatDeviceLabel(device),
                style = MaterialTheme.typography.bodyMedium,
              )
              Text(
                text = "${device.platform.name} - ${device.trailblazeDriverType.name}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          onClick = {
            onDeviceSelected(device)
            expanded = false
          },
        )
      }
    }
  }
}

private fun formatDeviceLabel(device: TrailblazeConnectedDeviceSummary): String {
  val platformIcon = when (device.platform) {
    TrailblazeDevicePlatform.ANDROID -> "Android"
    TrailblazeDevicePlatform.IOS -> "iOS"
    TrailblazeDevicePlatform.WEB -> "Web"
  }
  return "$platformIcon: ${device.description}"
}

/**
 * Connects to a device and returns the appropriate stream + tool factory.
 * Runs device creation on IO dispatcher since it can be slow.
 */
private suspend fun connectToDevice(
  device: TrailblazeConnectedDeviceSummary,
  deviceManager: TrailblazeDeviceManager,
): ConnectionState {
  return try {
    when (device.platform) {
      TrailblazeDevicePlatform.WEB -> {
        val pageManager = deviceManager.webBrowserManager.getPageManager()
          ?: return ConnectionState.Error("Browser is not running")
        val stream = PlaywrightDeviceScreenStream(pageManager)
        val toolFactory = PlaywrightInteractionToolFactory(stream)
        ConnectionState.Connected(
          RecordingDeviceConnection(
            stream = stream,
            toolFactory = toolFactory,
            deviceLabel = formatDeviceLabel(device),
          )
        )
      }
      TrailblazeDevicePlatform.ANDROID, TrailblazeDevicePlatform.IOS -> {
        val connectedDevice = withContext(Dispatchers.IO) {
          TrailblazeDeviceService.getConnectedDevice(device.trailblazeDeviceId)
        } ?: return ConnectionState.Error("Device not found: ${device.instanceId}")

        val driver = connectedDevice.getMaestroDriver()
        val stream = MaestroDeviceScreenStream(driver)
        val toolFactory = MaestroInteractionToolFactory(
          deviceWidth = stream.deviceWidth,
          deviceHeight = stream.deviceHeight,
        )
        ConnectionState.Connected(
          RecordingDeviceConnection(
            stream = stream,
            toolFactory = toolFactory,
            deviceLabel = formatDeviceLabel(device),
          )
        )
      }
    }
  } catch (e: Exception) {
    ConnectionState.Error(e.message ?: "Connection failed")
  }
}
