package xyz.block.trailblaze.ui.tabs.devices

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import maestro.Driver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.TrailblazeDeviceService
import xyz.block.trailblaze.host.devices.WebBrowserState
import xyz.block.trailblaze.host.recording.MaestroDeviceScreenStream
import xyz.block.trailblaze.playwright.recording.PlaywrightDeviceScreenStream
import xyz.block.trailblaze.recording.DeviceScreenStream
import xyz.block.trailblaze.recording.InteractionEventBuffer
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DeviceConfigurationContent
import xyz.block.trailblaze.ui.composables.WebBrowserControlPanel
import xyz.block.trailblaze.ui.model.LocalNavController
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.model.navigateToRoute
import xyz.block.trailblaze.ui.tabs.recording.InteractiveDeviceComposable

@Composable
fun DevicesTabComposable(
  deviceManager: TrailblazeDeviceManager,
  trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
) {
  val navController = LocalNavController.current
  val serverState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()
  val browserState by deviceManager.webBrowserStateFlow.collectAsState()
  val deviceState by deviceManager.deviceStateFlow.collectAsState()

  // Check if web driver type is enabled
  val isWebEnabled = trailblazeSavedSettingsRepo.getEnabledDriverTypes()
    .any { driverType -> driverType.platform == TrailblazeDevicePlatform.WEB }

  // Find selected mobile devices
  val selectedInstanceIds = serverState.appConfig.lastSelectedDeviceInstanceIds
  val selectedMobileDevice = deviceState.devices.values
    .map { it.device }
    .firstOrNull { device ->
      device.instanceId in selectedInstanceIds &&
        (device.platform == TrailblazeDevicePlatform.ANDROID ||
          device.platform == TrailblazeDevicePlatform.IOS)
    }

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Show web browser control panel if web testing is enabled
    if (isWebEnabled) {
      WebBrowserControlPanel(
        deviceManager = deviceManager,
      )
    }

    // Show live device preview when the browser is running
    if (browserState is WebBrowserState.Running) {
      WebDevicePreviewPanel(deviceManager = deviceManager)
    }

    // Show mobile device preview when a mobile device is selected
    if (selectedMobileDevice != null) {
      MobileDevicePreviewPanel(
        deviceId = selectedMobileDevice.trailblazeDeviceId,
        driverType = selectedMobileDevice.trailblazeDriverType,
        deviceDescription = selectedMobileDevice.description,
      )
    }

    DeviceConfigurationContent(
      settingsRepo = trailblazeSavedSettingsRepo,
      deviceManager = deviceManager,
      allowMultipleSelection = true,
      showRefreshButton = true,
      autoRefreshOnLoad = true,
      onSessionClick = { sessionId ->
        // Navigate to the session details by updating the state
        trailblazeSavedSettingsRepo.updateState {
          serverState.copy(
            appConfig = serverState.appConfig.copy(currentSessionId = sessionId)
          )
        }
        // Switch to Sessions tab using navigation
        navController.navigateToRoute(TrailblazeRoute.Sessions)
      }
    )
  }
}

/**
 * Shows a live preview of the connected browser's screen.
 * Read-only for now — no recording, just validates the streaming pipeline.
 */
@Composable
private fun WebDevicePreviewPanel(deviceManager: TrailblazeDeviceManager) {
  val pageManager = deviceManager.webBrowserManager.getPageManager() ?: return
  val stream = remember(pageManager) { PlaywrightDeviceScreenStream(pageManager) }
  val previewScope = rememberCoroutineScope()
  val noOpBuffer = remember(previewScope) {
    InteractionEventBuffer(
      scope = previewScope,
      toolFactory = NoOpToolFactory,
      onInteraction = {},
    )
  }

  DevicePreviewContent(title = "Web Live Preview", stream = stream, buffer = noOpBuffer)
}

/** State for mobile device preview connection. */
private sealed interface MobilePreviewState {
  data object Disconnected : MobilePreviewState
  data object Connecting : MobilePreviewState
  data class Connected(val driver: Driver, val stream: MaestroDeviceScreenStream) : MobilePreviewState
  data class Error(val message: String) : MobilePreviewState
}

/**
 * Shows a live preview of a connected mobile (Android/iOS) device.
 * Uses a "Connect for Preview" button to establish a Maestro driver connection.
 */
@Composable
private fun MobileDevicePreviewPanel(
  deviceId: TrailblazeDeviceId,
  driverType: TrailblazeDriverType,
  deviceDescription: String,
) {
  var previewState by remember(deviceId) { mutableStateOf<MobilePreviewState>(MobilePreviewState.Disconnected) }
  val scope = rememberCoroutineScope()

  // Clean up driver when composable leaves composition or device changes
  DisposableEffect(deviceId) {
    onDispose {
      // Don't close the driver — it uses singleton caching and closing it would
      // break subsequent connections. Just let the stream stop collecting.
      previewState = MobilePreviewState.Disconnected
    }
  }

  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "Mobile Preview",
        style = MaterialTheme.typography.titleSmall,
      )
      Text(
        text = "($deviceDescription)",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    when (val state = previewState) {
      is MobilePreviewState.Disconnected -> {
        Button(
          onClick = {
            previewState = MobilePreviewState.Connecting
            scope.launch {
              try {
                val connectedDevice = withContext(Dispatchers.IO) {
                  TrailblazeDeviceService.getConnectedDevice(deviceId, driverType)
                }
                if (connectedDevice != null) {
                  val driver = connectedDevice.getMaestroDriver()
                  val stream = MaestroDeviceScreenStream(driver)
                  previewState = MobilePreviewState.Connected(driver, stream)
                } else {
                  previewState = MobilePreviewState.Error("Device not found: ${deviceId.instanceId}")
                }
              } catch (e: Exception) {
                previewState = MobilePreviewState.Error(e.message ?: "Connection failed")
              }
            }
          },
        ) {
          Text("Connect for Preview")
        }
      }

      is MobilePreviewState.Connecting -> {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CircularProgressIndicator(
            modifier = Modifier.padding(4.dp),
            strokeWidth = 2.dp,
          )
          Text(
            text = "Connecting to device...",
            style = MaterialTheme.typography.bodyMedium,
          )
        }
      }

      is MobilePreviewState.Connected -> {
        val mobilePreviewScope = rememberCoroutineScope()
        val noOpBuffer = remember(mobilePreviewScope) {
          InteractionEventBuffer(
            scope = mobilePreviewScope,
            toolFactory = NoOpToolFactory,
            onInteraction = {},
          )
        }

        DevicePreviewContent(
          title = null, // Title already shown above
          stream = state.stream,
          buffer = noOpBuffer,
        )

        OutlinedButton(
          onClick = {
            previewState = MobilePreviewState.Disconnected
          },
        ) {
          Text("Disconnect Preview")
        }
      }

      is MobilePreviewState.Error -> {
        Text(
          text = "Error: ${state.message}",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.error,
        )
        Button(
          onClick = { previewState = MobilePreviewState.Disconnected },
        ) {
          Text("Retry")
        }
      }
    }
  }
}

/** Shared preview content that renders a device stream in a bordered box. */
@Composable
private fun DevicePreviewContent(
  title: String?,
  stream: DeviceScreenStream,
  buffer: InteractionEventBuffer,
) {
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    if (title != null) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
      )
    }

    Box(
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 200.dp, max = 400.dp)
        .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
    ) {
      InteractiveDeviceComposable(
        stream = stream,
        buffer = buffer,
        isRecording = false,
        modifier = Modifier.fillMaxSize().padding(1.dp),
      )
    }
  }
}

private data class NoOpTool(val name: String) : xyz.block.trailblaze.toolcalls.TrailblazeTool

/** Placeholder factory for preview-only mode (no interactions recorded). */
private object NoOpToolFactory : xyz.block.trailblaze.recording.InteractionToolFactory {
  override fun createTapTool(
    node: xyz.block.trailblaze.api.ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ) = NoOpTool("tap") to "tap"

  override fun createLongPressTool(
    node: xyz.block.trailblaze.api.ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
  ) = NoOpTool("longPress") to "longPress"

  override fun createSwipeTool(startX: Int, startY: Int, endX: Int, endY: Int) =
    NoOpTool("swipe") to "swipe"

  override fun createInputTextTool(text: String) = NoOpTool("inputText") to "inputText"

  override fun createPressKeyTool(key: String): Pair<xyz.block.trailblaze.toolcalls.TrailblazeTool, String>? = NoOpTool("pressKey") to "pressKey"
}
