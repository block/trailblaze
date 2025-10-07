package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.ui.DeviceManager

/**
 * A reusable device selector composable that works with a shared DeviceManager.
 * This allows multiple composables to share the same device state.
 *
 * @param deviceManager The shared DeviceManager instance
 * @param enabled Whether the selector is enabled
 * @param onDeviceSelected Optional callback when a device is selected
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSelectorComposable(
  deviceManager: DeviceManager,
  enabled: Boolean = true,
  onDeviceSelected: (() -> Unit)? = null,
) {
  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val availableDevices = deviceState.availableDevices
  val selectedDevice = deviceState.selectedDevices.firstOrNull()
  val isLoadingDevices = deviceState.isLoading

  var isDeviceMenuExpanded by remember { mutableStateOf(false) }

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    ExposedDropdownMenuBox(
      expanded = isDeviceMenuExpanded,
      onExpandedChange = { isDeviceMenuExpanded = it },
      modifier = Modifier.weight(1f)
    ) {
      OutlinedTextField(
        value = selectedDevice?.let { "${it.trailblazeDriverType} (${it.description})" } ?: "No device selected",
        onValueChange = {},
        readOnly = true,
        label = { Text("Target Device") },
        leadingIcon = selectedDevice?.let { device ->
          {
            Icon(
              imageVector = device.platform.getIcon(),
              contentDescription = "Platform"
            )
          }
        },
        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = isDeviceMenuExpanded) },
        modifier = Modifier
          .menuAnchor()
          .fillMaxWidth(),
        enabled = enabled && !isLoadingDevices
      )

      ExposedDropdownMenu(
        expanded = isDeviceMenuExpanded,
        onDismissRequest = { isDeviceMenuExpanded = false }
      ) {
        availableDevices.forEach { device ->
          DropdownMenuItem(
            text = {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(device.platform.getIcon(), contentDescription = null)
                Text("${device.trailblazeDriverType} (${device.description} ${device.instanceId})")
              }
            },
            onClick = {
              deviceManager.selectDevice(device)
              isDeviceMenuExpanded = false
              onDeviceSelected?.invoke()
            }
          )
        }
      }
    }

    IconButton(
      onClick = {
        deviceManager.loadDevices()
      },
      enabled = enabled && !isLoadingDevices
    ) {
      if (isLoadingDevices) {
        CircularProgressIndicator(modifier = Modifier.width(24.dp))
      } else {
        Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
      }
    }
  }
}
