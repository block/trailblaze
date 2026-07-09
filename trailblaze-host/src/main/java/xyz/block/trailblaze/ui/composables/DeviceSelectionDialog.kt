package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.cli.pickPreferringNativeBrowser
import xyz.block.trailblaze.model.AppVersionInfo
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.getVersionInfo
import xyz.block.trailblaze.ui.tabs.devices.TargetAppConfigRow

/**
 * Whether this device should be selectable for [target] in the Run Configuration dialog.
 *
 * Virtual-device rows (web browsers, Compose desktop) are always enabled — there is no app to
 * install on them, so app-install gating is meaningless and would only disable rows a product
 * target doesn't declare (e.g. the Compose row under a mobile target, which made compose-only
 * trails unrunnable without switching targets). When no target is selected the row is also
 * enabled — pre-existing behavior. For native rows with a selected target this delegates
 * to [TrailblazeHostAppTarget.acceptsDeviceForPlatform], which both checks that the target
 * supports the device platform AND applies the `allowsAppNotInstalled` bypass.
 *
 * Single source of truth for the predicate previously inlined at four sites in this file.
 */
internal fun TrailblazeConnectedDeviceSummary.isEligibleFor(
  target: TrailblazeHostAppTarget?,
  installedAppIdsByDevice: Map<TrailblazeDeviceId, Set<String>>,
): Boolean {
  if (platform.usesVirtualDevice) return true
  if (target == null) return true
  val installedAppId = target.getAppIdIfInstalled(
    platform = platform,
    installedAppIds = installedAppIdsByDevice[trailblazeDeviceId] ?: emptySet(),
  )
  return target.acceptsDeviceForPlatform(platform, installedAppId)
}

/**
 * Which devices the Run Configuration dialog lists. Normally the device manager's filtered list
 * ([TrailblazeDeviceManager.deviceStateFlow]), whose `targetDeviceFilter` hides virtual devices
 * (Playwright, Compose) unless the app's testing environment is WEB. For a virtual-only trail
 * that filter would make the run impossible in a mobile environment — no browser row to check —
 * so this unions the matching virtual devices from the unfiltered discovery
 * ([TrailblazeDeviceManager.allDiscoveredDevicesFlow]) into the list. The run path is already
 * environment-independent (`DesktopYamlRunner` resolves virtual devices via an unfiltered
 * lookup); listing them here is the missing piece.
 *
 * Scoped to virtual-ONLY trails: mobile/mixed/unknown trails and non-trail callers (the Devices
 * tab passes [trailPlatforms] = null) keep the filtered list untouched. Only devices whose
 * platform the trail declares are added, so a web trail in a mobile environment gains browser
 * rows but never, say, a Compose row.
 */
internal fun devicesForRunPicker(
  filteredDevices: List<TrailblazeConnectedDeviceSummary>,
  allDiscoveredDevices: List<TrailblazeConnectedDeviceSummary>,
  trailPlatforms: Set<TrailblazeDevicePlatform>?,
): List<TrailblazeConnectedDeviceSummary> {
  if (trailPlatforms.isNullOrEmpty() || !trailPlatforms.all { it.usesVirtualDevice }) {
    return filteredDevices
  }
  val listedDeviceIds = filteredDevices.map { it.trailblazeDeviceId }.toSet()
  return filteredDevices + allDiscoveredDevices.filter {
    it.platform in trailPlatforms && it.trailblazeDeviceId !in listedDeviceIds
  }
}

/**
 * Which devices the Run Configuration dialog pre-checks when it opens. Pure and unit-tested —
 * the composables below delegate here so the desktop's default pick shares the CLI's routing
 * semantics ([TrailDeviceSelector]'s web/compose defer) instead of drifting:
 *
 * - A virtual-only trail ([trailPlatforms] all web/compose) pre-checks its virtual device
 *   (preferring the native Playwright browser) — NOT the last-used mobile devices, which would
 *   put a one-click "Run Tests" on the wrong platform. The user can still override by checking
 *   another device (the picker equivalent of `--device`). [availableDevices] comes from
 *   [devicesForRunPicker], which lists a virtual-only trail's virtual devices regardless of the
 *   app's testing environment — so this pre-check works in a mobile environment too.
 * - Otherwise (mobile / unknown / no declared platforms) it restores the last-used devices that
 *   are still connected and eligible — the pre-existing behavior. The picker itself is the
 *   fail-loud step: nothing checked keeps "Run Tests" disabled, so 2+ connected devices never
 *   silently run on a default.
 *
 * [trailPlatforms] is null when the caller isn't running a specific trail (e.g. the Devices tab).
 */
internal fun initialRunDeviceSelection(
  availableDevices: List<TrailblazeConnectedDeviceSummary>,
  lastSelectedInstanceIds: List<String>,
  trailPlatforms: Set<TrailblazeDevicePlatform>?,
  isEligible: (TrailblazeConnectedDeviceSummary) -> Boolean,
): Set<TrailblazeConnectedDeviceSummary> {
  if (!trailPlatforms.isNullOrEmpty() && trailPlatforms.all { it.usesVirtualDevice }) {
    val candidates = availableDevices.filter { it.platform in trailPlatforms && isEligible(it) }
    return setOfNotNull(candidates.pickPreferringNativeBrowser())
  }
  return availableDevices.filter { device ->
    device.instanceId in lastSelectedInstanceIds && isEligible(device)
  }.toSet()
}

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
  trailPlatforms: Set<TrailblazeDevicePlatform>? = null,
  modifier: Modifier = Modifier,
) {
  val currentState by settingsRepo.serverStateFlow.collectAsState()
  val lastSelectedDeviceInstanceIds: List<String> = currentState.appConfig.lastSelectedDeviceInstanceIds

  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val allDiscoveredDevices by deviceManager.allDiscoveredDevicesFlow.collectAsState()
  val availableDevices = devicesForRunPicker(
    filteredDevices = deviceState.devices.values.map { it.device },
    allDiscoveredDevices = allDiscoveredDevices,
    trailPlatforms = trailPlatforms,
  )
  val isLoadingDevices = deviceState.isLoading
  val activeDeviceSessions by deviceManager.activeDeviceSessionsFlow.collectAsState()

  val selectedTargetApp = deviceManager.getCurrentSelectedTargetApp()

  val installedAppIdsByDevice by deviceManager.installedAppIdsByDeviceFlow.collectAsState()
  val appVersionInfoByDevice by deviceManager.appVersionInfoByDeviceFlow.collectAsState()

  // Initial pre-check: last-used devices that are still available/eligible, except that a
  // web/compose trail pre-checks its virtual device — see [initialRunDeviceSelection].
  var selectedDevices by remember(
    availableDevices,
    lastSelectedDeviceInstanceIds,
    selectedTargetApp,
    installedAppIdsByDevice,
    trailPlatforms,
  ) {
    mutableStateOf(
      initialRunDeviceSelection(availableDevices, lastSelectedDeviceInstanceIds, trailPlatforms) { device ->
        device.isEligibleFor(selectedTargetApp, installedAppIdsByDevice)
      }
    )
  }

  LaunchedEffect(activeDeviceSessions, allowSelectionOfActiveDevices) {
    if (allowSelectionOfActiveDevices) return@LaunchedEffect
    val filteredSelection = selectedDevices.filterTo(mutableSetOf()) { device ->
      !activeDeviceSessions.containsKey(device.trailblazeDeviceId)
    }
    if (filteredSelection != selectedDevices) {
      selectedDevices = filteredSelection
      onSelectionChanged(selectedDevices.map { it.instanceId })
    }
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
          val isDeviceEnabled = device.isEligibleFor(selectedTargetApp, installedAppIdsByDevice)
          val activeSessionId = activeDeviceSessions[device.trailblazeDeviceId]
          val hasActiveSession = activeSessionId != null
          // Get version info for the installed app
          val versionInfo = appIdIfInstalled?.let { appId ->
            appVersionInfoByDevice.getVersionInfo(device.trailblazeDeviceId, appId)
          }

          SingleDeviceListItem(
            device = device,
            isSelected = selectedDevices.contains(device),
            installedAppId = appIdIfInstalled,
            appTarget = selectedTargetApp,
            appVersionInfo = versionInfo,
            activeSessionId = activeSessionId?.value,
            onSessionClick = onSessionClick,
            onToggle = {
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
  trailPlatforms: Set<TrailblazeDevicePlatform>? = null,
) {
  val currentState by settingsRepo.serverStateFlow.collectAsState()
  val lastSelectedDeviceInstanceIds: List<String> = currentState.appConfig.lastSelectedDeviceInstanceIds

  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val allDiscoveredDevices by deviceManager.allDiscoveredDevicesFlow.collectAsState()
  val availableDevices = devicesForRunPicker(
    filteredDevices = deviceState.devices.values.map { it.device },
    allDiscoveredDevices = allDiscoveredDevices,
    trailPlatforms = trailPlatforms,
  )

  val selectedTargetApp = deviceManager.getCurrentSelectedTargetApp()
  val installedAppIdsByDevice by deviceManager.installedAppIdsByDeviceFlow.collectAsState()

  // Mirrors DeviceConfigurationContent's initial pre-check (same inputs → same result); the
  // content's onSelectionChanged keeps the two in sync from then on.
  var selectedDevices by remember(
    availableDevices,
    lastSelectedDeviceInstanceIds,
    selectedTargetApp,
    installedAppIdsByDevice,
    trailPlatforms,
  ) {
    mutableStateOf(
      initialRunDeviceSelection(availableDevices, lastSelectedDeviceInstanceIds, trailPlatforms) { device ->
        device.isEligibleFor(selectedTargetApp, installedAppIdsByDevice)
      }
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
            trailPlatforms = trailPlatforms,
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
                    text = "Force stop ${selectedTargetApp.displayName} before running tests",
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
                onClick = {
                  onRunTests(selectedDevices.toList(), forceStopApp)
                },
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
  appVersionInfo: AppVersionInfo? = null,
  activeSessionId: String? = null,
  onSessionClick: ((String) -> Unit)? = null,
  onToggle: () -> Unit,
  modifier: Modifier = Modifier,
) {
  // Virtual devices (web browsers, Compose desktop) don't have apps to install — they're
  // always ready to use. Must stay consistent with [isEligibleFor], which gates the pre-check.
  val isVirtualDevice = device.platform.usesVirtualDevice
  val isAppInstalled = installedAppId != null
  val isEnabled = isVirtualDevice || appTarget == null ||
      appTarget.acceptsDeviceForPlatform(device.platform, installedAppId)
  // Gate the "any app supported" friendly message on the row actually being eligible — a
  // target that opts into [allowsAppNotInstalled] on platforms it supports shouldn't claim
  // "any app supported" on a platform it doesn't support at all.
  val allowsAppNotInstalled = appTarget?.allowsAppNotInstalled == true && isEnabled
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

        // Show app installation status for real devices, or "Ready" for virtual ones
        if (isVirtualDevice) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Check,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.primary,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = if (device.platform == TrailblazeDevicePlatform.WEB) {
                "Web browser ready for testing"
              } else {
                "Virtual device ready for testing"
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Medium
            )
          }
        } else if (appTarget != null) {
          Column(
            verticalArrangement = Arrangement.spacedBy(2.dp)
          ) {
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
                // Show version info if available, otherwise just show the app ID
                val versionText = if (appVersionInfo != null) {
                  val formattedVersion = appTarget.formatVersionInfo(device.platform, appVersionInfo)
                    ?: appVersionInfo.versionName ?: appVersionInfo.versionCode
                  "${appTarget.displayName} App installed - $formattedVersion"
                } else {
                  "${appTarget.displayName} App installed ($installedAppId)"
                }
                Text(
                  text = versionText,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.primary,
                  fontWeight = FontWeight.Medium
                )
              } else if (allowsAppNotInstalled) {
                // Generic stand-in target (e.g. "Default") — no specific app id is required, so
                // a missing install is expected and the device should still be usable.
                Icon(
                  imageVector = Icons.Default.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(16.dp)
                )
                Text(
                  text = "${appTarget.displayName} target — any app supported",
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
                  text = "${appTarget.displayName} App not installed ($possibleAppIdsMessage)",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.error,
                  fontWeight = FontWeight.Medium
                )
              }
            }

            // Show version warning if app is installed but version is below minimum
            if (installedAppId != null && appVersionInfo != null) {
              val isVersionOk = appTarget.isVersionAcceptable(device.platform, appVersionInfo)
              if (!isVersionOk) {
                val minVersion = appTarget.getMinBuildVersion(device.platform)
                val warningMessage = minVersion?.let {
                  appTarget.getVersionWarningMessage(device.platform, appVersionInfo, it)
                } ?: "App version may be outdated"

                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(14.dp)
                  )
                  Markdown(
                    content = warningMessage,
                    colors = markdownColor(text = MaterialTheme.colorScheme.error),
                    typography = markdownTypography(
                      text = MaterialTheme.typography.bodySmall,
                      paragraph = MaterialTheme.typography.bodySmall,
                    ),
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
