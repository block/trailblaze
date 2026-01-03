package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.tabs.devices.TargetAppConfigRow

/**
 * Core device configuration content that can be reused in different contexts
 */
@Composable
fun DeviceConfigurationContent(
  settingsRepo: TrailblazeSettingsRepo,
  deviceManager: TrailblazeDeviceManager,
  allowMultipleSelection: Boolean = true,
  onSelectionChanged: (List<String>) -> Unit = {},
  showRefreshButton: Boolean = true,
  autoRefreshOnLoad: Boolean = true,
  allowSelectionOfActiveDevices: Boolean = false,
  onSessionClick: ((String) -> Unit)? = null,
  modifier: Modifier = Modifier,
) {
  val currentState by settingsRepo.serverStateFlow.collectAsState()
  val lastSelectedDeviceInstanceIds: List<String> = currentState.appConfig.lastSelectedDeviceInstanceIds

  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val availableDevices = deviceState.devices.values.map { it.device }
  val isLoadingDevices = deviceState.isLoading
  val activeDeviceSessions by deviceManager.activeDeviceSessionsFlow.collectAsState()

  val selectedTargetApp = deviceManager.getCurrentSelectedTargetApp()

  val installedAppIdsByDevice by deviceManager.installedAppIdsByDeviceFlow.collectAsState()

  // Initialize selectedDevices with previously selected devices that are still available and have the app installed
  var selectedDevices by remember(
    availableDevices,
    lastSelectedDeviceInstanceIds,
    selectedTargetApp,
    installedAppIdsByDevice
  ) {
    mutableStateOf(
      availableDevices.filter { device ->
        val appIdIfInstalled = selectedTargetApp?.getAppIdIfInstalled(
          platform = device.platform,
          installedAppIds = installedAppIdsByDevice[device.trailblazeDeviceId] ?: emptySet()
        )
        device.instanceId in lastSelectedDeviceInstanceIds &&
            (selectedTargetApp == null || appIdIfInstalled != null)
      }.toSet()
    )
  }

  // Auto-refresh devices when requested
  if (autoRefreshOnLoad) {
    LaunchedEffect(Unit) {
      deviceManager.loadDevices()
    }
  }

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Target App Configuration
    Text(
      text = "Target App Selection",
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.primary
    )

    TargetAppConfigRow(
      deviceManager = deviceManager,
      settingsRepo = settingsRepo,
      selectedTargetApp = selectedTargetApp,
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Device Selection Header with optional refresh button
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Text(
        text = "Device Selection",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
      )

      if (showRefreshButton) {
        IconButton(
          onClick = { deviceManager.loadDevices() },
          enabled = !isLoadingDevices
        ) {
          if (isLoadingDevices) {
            CircularProgressIndicator(modifier = Modifier.width(24.dp))
          } else {
            Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
          }
        }
      }
    }

    // Device List
    if (availableDevices.isEmpty() && !isLoadingDevices) {
      Text(
        text = "No devices found. Please connect a device and click refresh.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(16.dp)
      )
    } else {
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        availableDevices.forEach { device ->
          val appIdIfInstalled = selectedTargetApp?.getAppIdIfInstalled(
            platform = device.platform,
            installedAppIds = installedAppIdsByDevice[device.trailblazeDeviceId] ?: emptySet()
          )
          val isAppInstalled = appIdIfInstalled != null
          val isDeviceEnabled = selectedTargetApp == null || isAppInstalled
          val activeSessionId = activeDeviceSessions[device.trailblazeDeviceId]
          val hasActiveSession = activeSessionId != null

          SingleDeviceListItem(
            device = device,
            isSelected = selectedDevices.contains(device),
            installedAppId = appIdIfInstalled,
            appTarget = selectedTargetApp,
            activeSessionId = activeSessionId?.value,
            onSessionClick = onSessionClick,
            onToggle = {
              // Only allow toggle if device is enabled and (allowSelectionOfActiveDevices is true OR device has no active session)
              if (isDeviceEnabled && (allowSelectionOfActiveDevices || !hasActiveSession)) {
                selectedDevices = if (allowMultipleSelection) {
                  if (selectedDevices.contains(device)) {
                    selectedDevices - device
                  } else {
                    selectedDevices + device
                  }
                } else {
                  // Single selection mode
                  if (selectedDevices.contains(device)) {
                    emptySet()
                  } else {
                    setOf(device)
                  }
                }
                onSelectionChanged(selectedDevices.map { it.instanceId })
              }
            }
          )
        }
      }
    }

    // Selection summary
    Text(
      text = if (allowMultipleSelection) {
        "${selectedDevices.size} device${if (selectedDevices.size != 1) "s" else ""} selected"
      } else {
        if (selectedDevices.isEmpty()) "No device selected" else "1 device selected"
      },
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant
    )
  }
}

/**
 * Dialog for selecting target app and devices to run tests on.
 */
@Composable
fun DeviceSelectionDialog(
  settingsRepo: TrailblazeSettingsRepo,
  deviceManager: TrailblazeDeviceManager,
  onRunTests: (List<TrailblazeConnectedDeviceSummary>, forceStopApp: Boolean) -> Unit,
  onDismiss: () -> Unit,
  forceStopApp: Boolean = false,
  allowMultipleSelection: Boolean = true,
  allowSelectionOfActiveDevices: Boolean = false,
  onSelectionChanged: (List<String>) -> Unit = {},
  onSessionClick: ((String) -> Unit)? = null,
) {
  val currentState by settingsRepo.serverStateFlow.collectAsState()
  val lastSelectedDeviceInstanceIds: List<String> = currentState.appConfig.lastSelectedDeviceInstanceIds

  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val availableDevices = deviceState.devices.values.map { it.device }

  val selectedTargetApp = deviceManager.getCurrentSelectedTargetApp()
  val installedAppIdsByDevice by deviceManager.installedAppIdsByDeviceFlow.collectAsState()

  // Initialize selectedDevices with previously selected devices that are still available and have the app installed
  var selectedDevices by remember(
    availableDevices,
    lastSelectedDeviceInstanceIds,
    selectedTargetApp,
    installedAppIdsByDevice
  ) {
    mutableStateOf(
      availableDevices.filter { device ->
        val appIdIfInstalled = selectedTargetApp?.getAppIdIfInstalled(
          platform = device.platform,
          installedAppIds = installedAppIdsByDevice[device.trailblazeDeviceId] ?: emptySet()
        )
        device.instanceId in lastSelectedDeviceInstanceIds && (appIdIfInstalled != null)
      }.toSet()
    )
  }

  // State for force stop app option
  var forceStopApp by remember { mutableStateOf(forceStopApp) }

  FullScreenModalOverlay(onDismiss = onDismiss) {
    Box(
      modifier = Modifier.fillMaxSize(),
      contentAlignment = Alignment.Center
    ) {
      Card(
        modifier = Modifier
          .fillMaxSize()
          .padding(16.dp),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
          verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
          // Header
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Run Configuration",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold
            )

            IconButton(onClick = onDismiss) {
              Icon(Icons.Default.Close, contentDescription = "Close")
            }
          }

          HorizontalDivider()

          // Reuse the shared content
          DeviceConfigurationContent(
            settingsRepo = settingsRepo,
            deviceManager = deviceManager,
            allowMultipleSelection = allowMultipleSelection,
            allowSelectionOfActiveDevices = allowSelectionOfActiveDevices,
            onSelectionChanged = { instanceIds ->
              selectedDevices = availableDevices.filter { it.instanceId in instanceIds }.toSet()
              onSelectionChanged(instanceIds)
            },
            showRefreshButton = true,
            autoRefreshOnLoad = true,
            onSessionClick = { sessionId ->
              // Dismiss dialog and navigate to session
              onSessionClick?.invoke(sessionId)
              onDismiss()
            },
            modifier = Modifier.weight(1f, fill = false)
          )

          HorizontalDivider()

          // Force Stop App Section
          if (selectedTargetApp != null) {
            Column(
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Text(
                text = "App Management",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
              )

              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
              ) {
                Checkbox(
                  checked = forceStopApp,
                  onCheckedChange = { forceStopApp = it }
                )

                Icon(
                  imageVector = Icons.Default.Warning,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.secondary,
                  modifier = Modifier.size(20.dp)
                )

                Column(
                  modifier = Modifier.weight(1f)
                ) {
                  Text(
                    text = "Force stop ${selectedTargetApp.name} before running tests",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                  )
                  Text(
                    text = "This will terminate the app on all selected devices before starting tests",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }

            HorizontalDivider()
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Spacer(modifier = Modifier.width(1.dp)) // Placeholder for alignment

            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
              }

              Button(
                onClick = { onRunTests(selectedDevices.toList(), forceStopApp) },
                enabled = selectedDevices.isNotEmpty()
              ) {
                Text("Run Tests")
              }
            }
          }
        }
      }
    }
  }
}

@Composable
fun SingleDeviceListItem(
  device: TrailblazeConnectedDeviceSummary,
  isSelected: Boolean,
  appTarget: TrailblazeHostAppTarget?,
  installedAppId: String?,
  activeSessionId: String? = null,
  onSessionClick: ((String) -> Unit)? = null,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val isAppInstalled = installedAppId != null
  val isEnabled = appTarget == null || isAppInstalled
  // A device has an active session if there's a session ID
  val hasActiveSession = activeSessionId != null

  val possibleAppIdsMessage =
    appTarget?.getPossibleAppIdsForPlatform(device.platform)?.joinToString(", ")
      ?: "No App ID Specified for ${device.platform} Platform"

  Card(
    modifier = modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
      } else if (hasActiveSession) {
        // Show a distinct color for devices with active sessions
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.7f)
      } else if (!isEnabled) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      }
    )
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(12.dp),
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      Checkbox(
        checked = isSelected && isEnabled,
        onCheckedChange = { if (isEnabled && !hasActiveSession) onToggle() },
        enabled = isEnabled && !hasActiveSession
      )

      Icon(
        imageVector = device.platform.getIcon(),
        contentDescription = "Platform: ${device.platform}",
        tint = if (isSelected && isEnabled) {
          MaterialTheme.colorScheme.primary
        } else if (!isEnabled) {
          MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        } else {
          MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.size(32.dp)
      )

      Column(
        modifier = Modifier.weight(1f)
      ) {
        Text(
          text = device.trailblazeDriverType.toString(),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium,
          color = if (!isEnabled) {
            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
          } else {
            MaterialTheme.colorScheme.onSurface
          }
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          Text(
            text = "${device.description} • ${device.instanceId}",
            style = MaterialTheme.typography.bodySmall,
            color = if (!isEnabled) {
              MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            }
          )
        }

        // Show active session indicator
        if (activeSessionId != null && onSessionClick != null) {
          Row(
            modifier = Modifier
              .clickable {
                // Navigate to the session - the navigation will handle switching tabs
                onSessionClick.invoke(activeSessionId)
              }
              .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = "Session running: $activeSessionId (click to view)",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.tertiary,
              fontWeight = FontWeight.Medium
            )
          }
        }

        if (appTarget != null) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            if (installedAppId != null) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "${appTarget.name} App installed ($installedAppId)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
              )
            } else {
              Icon(
                imageVector = Icons.Default.Close,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
              )
              Text(
                text = "${appTarget.name} App not installed ($possibleAppIdsMessage)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                fontWeight = FontWeight.Medium
              )
            }
          }
        }
      }
    }
  }
}
