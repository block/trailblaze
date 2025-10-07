package xyz.block.trailblaze.ui.composables

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary

/**
 * Simple device selector - shows all connected devices and lets user select multiple.
 */
@Composable
fun SimpleDeviceSelector(
  availableDevices: List<TrailblazeConnectedDeviceSummary>,
  selectedDevices: Set<TrailblazeConnectedDeviceSummary>,
  isLoading: Boolean,
  onDeviceToggled: (TrailblazeConnectedDeviceSummary) -> Unit,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(16.dp)
  ) {
    // Header
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Column {
        Text(
          text = "Local Run Targets",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
        if (selectedDevices.isNotEmpty()) {
          Text(
            text = "Tests will run on ${selectedDevices.size} selected device${if (selectedDevices.size != 1) "s" else ""}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        } else {
          Text(
            text = "Select devices to run tests locally",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }

      IconButton(onClick = onRefresh, enabled = !isLoading) {
        if (isLoading) {
          CircularProgressIndicator(modifier = Modifier.size(24.dp))
        } else {
          Icon(Icons.Default.Refresh, contentDescription = "Refresh devices")
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    if (availableDevices.isEmpty()) {
      Text(
        text = if (isLoading) "Loading devices..." else "No devices connected",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    } else {
      availableDevices.forEach { device ->
        val isSelected = device in selectedDevices

        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
          colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
              MaterialTheme.colorScheme.primaryContainer
            } else {
              MaterialTheme.colorScheme.surface
            }
          ),
          onClick = { onDeviceToggled(device) }
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            // Selection indicator (checkbox style)
            Icon(
              imageVector = if (isSelected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
              contentDescription = if (isSelected) "Selected" else "Not selected",
              tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
              },
              modifier = Modifier.size(24.dp)
            )

            // Platform icon
            Icon(
              imageVector = device.platform.getIcon(),
              contentDescription = device.platform.name,
              tint = if (isSelected) {
                MaterialTheme.colorScheme.primary
              } else {
                MaterialTheme.colorScheme.onSurface
              },
              modifier = Modifier.size(32.dp)
            )

            // Device info
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = device.description,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
              )
              Text(
                text = "${device.trailblazeDriverType.name.replace("_", " ")} • ${device.instanceId}",
                style = MaterialTheme.typography.bodySmall,
                color = if (isSelected) {
                  MaterialTheme.colorScheme.onPrimaryContainer
                } else {
                  MaterialTheme.colorScheme.onSurfaceVariant
                }
              )
            }
          }
        }
      }
    }
  }
}
