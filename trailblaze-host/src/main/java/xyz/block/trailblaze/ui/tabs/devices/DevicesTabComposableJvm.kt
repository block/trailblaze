package xyz.block.trailblaze.ui.tabs.devices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.composables.DeviceConfigurationContent
import xyz.block.trailblaze.ui.composables.WebBrowserControlPanel
import xyz.block.trailblaze.ui.model.LocalNavController
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.model.navigateToRoute

@Composable
fun DevicesTabComposable(
  deviceManager: TrailblazeDeviceManager,
  trailblazeSavedSettingsRepo: TrailblazeSettingsRepo,
) {
  val navController = LocalNavController.current
  val serverState by trailblazeSavedSettingsRepo.serverStateFlow.collectAsState()

  // Check if web driver type is enabled
  val isWebEnabled = trailblazeSavedSettingsRepo.getEnabledDriverTypes()
    .contains(TrailblazeDriverType.WEB_PLAYWRIGHT_HOST)

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
