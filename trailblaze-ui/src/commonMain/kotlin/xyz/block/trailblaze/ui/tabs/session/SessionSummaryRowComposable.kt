package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.yaml.TrailConfig
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.StatusBadge
import xyz.block.trailblaze.ui.composables.getIcon
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

@Composable
fun SessionSummaryRow(
  status: SessionStatus?,
  deviceName: String?,
  deviceType: String?,
  totalDurationMs: Long? = null,
  trailConfig: TrailConfig? = null,
  sessionInfo: SessionInfo? = null,
) {
  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
  ) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(12.dp)
    ) {
      // Main status and device info row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
      ) {
        status?.let {
          StatusBadge(status = status)
        }
        Spacer(modifier = Modifier.width(16.dp))
        sessionInfo?.trailblazeDeviceInfo?.let { deviceInfo ->
          Icon(
            imageVector = deviceInfo.platform.getIcon(),
            contentDescription = deviceInfo.platform.name,
            modifier = Modifier.size(24.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
        }
        SelectableText(
          text = "Device: ${deviceName ?: "Unknown"}${deviceType?.let { " ($it)" } ?: ""}",
          style = MaterialTheme.typography.bodyMedium
        )
        sessionInfo?.trailblazeDeviceInfo?.trailblazeDriverType?.let { driverType ->
          Spacer(modifier = Modifier.width(16.dp))
          SelectableText(
            text = "Driver: ${driverType.name}",
            style = MaterialTheme.typography.bodyMedium
          )
        }
        sessionInfo?.trailblazeDeviceInfo?.classifiers?.let { classifiers ->
          if (classifiers.isNotEmpty()) {
            Spacer(modifier = Modifier.width(16.dp))
            SelectableText(
              text = "Classifiers: ${classifiers.joinToString(", ")}",
              style = MaterialTheme.typography.bodyMedium
            )
          }
        }
        totalDurationMs?.let {
          Spacer(modifier = Modifier.width(16.dp))
          val durationText = "Total: ${formatDuration(it)}"
          SelectableText(
            text = durationText,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
          )
        }
      }

      // Trail config row (if available)
      trailConfig?.let { config ->
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.Start,
          verticalAlignment = Alignment.CenterVertically
        ) {
          config.id?.let {
            SelectableText(
              text = "ID: $it",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.width(16.dp))
          }
          config.priority?.let {
            SelectableText(
              text = "Priority: $it",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        config.description?.let { description ->
          Spacer(modifier = Modifier.height(4.dp))
          SelectableText(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
    }
  }
}
