package xyz.block.trailblaze.ui.composables

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
import androidx.compose.material.icons.filled.Refresh
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary

/**
 * Dialog for selecting one or more devices to run tests on.
 *
 * @param availableDevices List of devices available for selection
 * @param isLoadingDevices Whether devices are currently being loaded
 * @param onRefreshDevices Callback to refresh the device list
 * @param onRunTests Callback when user confirms device selection with list of selected devices
 * @param onDismiss Callback when dialog is dismissed without running tests
 * @param allowMultipleSelection Whether to allow selecting multiple devices (default: true)
 * @param lastSelectedDeviceInstanceIds List of previously selected device instance IDs to pre-select
 * @param onSelectionChanged Callback when device selection changes, receives list of selected device instance IDs
 */
@Composable
fun DeviceSelectionDialog(
  availableDevices: List<TrailblazeConnectedDeviceSummary>,
  isLoadingDevices: Boolean,
  onRefreshDevices: () -> Unit,
  onRunTests: (List<TrailblazeConnectedDeviceSummary>) -> Unit,
  onDismiss: () -> Unit,
  allowMultipleSelection: Boolean = true,
  lastSelectedDeviceInstanceIds: List<String> = emptyList(),
  onSelectionChanged: (List<String>) -> Unit = {},
) {
  // Initialize selectedDevices with previously selected devices that are still available
  var selectedDevices by remember(availableDevices, lastSelectedDeviceInstanceIds) {
    mutableStateOf(
      availableDevices.filter { device ->
        device.instanceId in lastSelectedDeviceInstanceIds
      }.toSet()
    )
  }

  // Auto-refresh devices when dialog is shown
  LaunchedEffect(Unit) {
    onRefreshDevices()
  }

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
              text = "Select Device${if (allowMultipleSelection) "(s)" else ""}",
              style = MaterialTheme.typography.headlineSmall,
              fontWeight = FontWeight.Bold
            )

            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              IconButton(
                onClick = onRefreshDevices,
                enabled = !isLoadingDevices
              ) {
                if (isLoadingDevices) {
                  CircularProgressIndicator(modifier = Modifier.width(24.dp))
                } else {
                  Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
                }
              }

              IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "Close")
              }
            }
          }

          HorizontalDivider()

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
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              availableDevices.forEach { device ->
                DeviceSelectionItem(
                  device = device,
                  isSelected = selectedDevices.contains(device),
                  onToggle = {
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
                )
              }
            }
          }

          // Footer
          HorizontalDivider()

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = if (allowMultipleSelection) {
                "${selectedDevices.size} device${if (selectedDevices.size != 1) "s" else ""} selected"
              } else {
                if (selectedDevices.isEmpty()) "No device selected" else "1 device selected"
              },
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
              }

              Button(
                onClick = { onRunTests(selectedDevices.toList()) },
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
private fun DeviceSelectionItem(
  device: TrailblazeConnectedDeviceSummary,
  isSelected: Boolean,
  onToggle: () -> Unit,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
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
        checked = isSelected,
        onCheckedChange = { onToggle() }
      )

      Icon(
        imageVector = device.platform.getIcon(),
        contentDescription = "Platform: ${device.platform}",
        tint = MaterialTheme.colorScheme.primary
      )

      Column(
        modifier = Modifier.weight(1f)
      ) {
        Text(
          text = device.trailblazeDriverType.toString(),
          style = MaterialTheme.typography.bodyLarge,
          fontWeight = FontWeight.Medium
        )
        Text(
          text = "${device.description} • ${device.instanceId}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }
  }
}
