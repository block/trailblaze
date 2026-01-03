package xyz.block.trailblaze.ui.composables

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.ui.TrailblazeDeviceManager

/**
 * Panel visibility states.
 */
private enum class PanelState {
  /** Panel is fully hidden - just a small FAB is shown */
  Minimized,
  /** Panel header is visible but device list is collapsed */
  Collapsed,
  /** Panel is fully expanded showing all devices */
  Expanded
}

/**
 * A collapsible device status panel that shows connected devices and their session status.
 * Designed to sit in the bottom corner of the app for quick access.
 *
 * Has three states:
 * - Minimized: Just a small floating button
 * - Collapsed: Shows header with device count and active session count
 * - Expanded: Shows full list of devices with session details
 */
@Composable
fun DeviceStatusPanel(
  deviceManager: TrailblazeDeviceManager,
  onSessionClick: (SessionId) -> Unit,
  modifier: Modifier = Modifier,
) {
  val deviceState by deviceManager.deviceStateFlow.collectAsState()
  val activeDeviceSessions by deviceManager.activeDeviceSessionsFlow.collectAsState()

  val devices = deviceState.devices.values.map { it.device }
  val devicesWithSessions = devices.count { activeDeviceSessions.containsKey(it.trailblazeDeviceId) }
  val isLoading = deviceState.isLoading

  var panelState by remember { mutableStateOf(PanelState.Collapsed) }

  AnimatedContent(
    targetState = panelState,
    transitionSpec = { fadeIn() togetherWith fadeOut() },
    modifier = modifier
  ) { state ->
    when (state) {
      PanelState.Minimized -> {
        // Minimized state - just a small FAB
        MinimizedDeviceButton(
          deviceCount = devices.size,
          activeSessionCount = devicesWithSessions,
          isLoading = isLoading,
          onClick = { panelState = PanelState.Expanded }
        )
      }
      PanelState.Collapsed, PanelState.Expanded -> {
        // Collapsed or Expanded state - show the card
        Card(
          modifier = Modifier.widthIn(max = 320.dp),
          shape = RoundedCornerShape(12.dp),
          elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
          )
        ) {
          Column {
            // Header - always visible in collapsed/expanded states
            DeviceStatusHeader(
              deviceCount = devices.size,
              activeSessionCount = devicesWithSessions,
              isLoading = isLoading,
              isExpanded = state == PanelState.Expanded,
              onExpandClick = {
                panelState = if (state == PanelState.Expanded) {
                  PanelState.Collapsed
                } else {
                  PanelState.Expanded
                }
              },
              onMinimizeClick = { panelState = PanelState.Minimized }
            )

            // Expandable content
            AnimatedVisibility(
              visible = state == PanelState.Expanded,
              enter = fadeIn() + expandVertically(),
              exit = fadeOut() + shrinkVertically()
            ) {
              Column {
                HorizontalDivider(
                  modifier = Modifier.padding(horizontal = 8.dp),
                  color = MaterialTheme.colorScheme.outlineVariant
                )

                if (devices.isEmpty()) {
                  Text(
                    text = "No devices connected",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                  )
                } else {
                  Column(
                    modifier = Modifier
                      .heightIn(max = 240.dp)
                      .verticalScroll(rememberScrollState())
                      .padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                  ) {
                    devices.forEach { device ->
                      val sessionId = activeDeviceSessions[device.trailblazeDeviceId]
                      DeviceStatusItem(
                        device = device,
                        sessionId = sessionId,
                        onClick = {
                          sessionId?.let { onSessionClick(it) }
                        }
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
  }
}

/**
 * Small floating button shown when the panel is minimized.
 */
@Composable
private fun MinimizedDeviceButton(
  deviceCount: Int,
  activeSessionCount: Int,
  isLoading: Boolean,
  onClick: () -> Unit,
) {
  FloatingActionButton(
    onClick = onClick,
    containerColor = MaterialTheme.colorScheme.primaryContainer,
    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 4.dp)
  ) {
    Box {
      if (isLoading) {
        CircularProgressIndicator(
          modifier = Modifier.size(24.dp),
          strokeWidth = 2.dp,
          color = MaterialTheme.colorScheme.onPrimaryContainer
        )
      } else {
        Icon(
          imageVector = Icons.Default.Devices,
          contentDescription = "Show devices ($deviceCount connected, $activeSessionCount active)",
          modifier = Modifier.size(24.dp)
        )
      }
      // Show activity indicator badge if there are active sessions (and not loading)
      if (!isLoading && activeSessionCount > 0) {
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .size(10.dp)
            .background(Color(0xFF4CAF50), CircleShape)
        )
      }
    }
  }
}

@Composable
private fun DeviceStatusHeader(
  deviceCount: Int,
  activeSessionCount: Int,
  isLoading: Boolean,
  isExpanded: Boolean,
  onExpandClick: () -> Unit,
  onMinimizeClick: () -> Unit,
) {
  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)),
    color = Color.Transparent
  ) {
    Row(
      modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      // Clickable area for expand/collapse
      Row(
        modifier = Modifier
          .weight(1f)
          .clip(RoundedCornerShape(8.dp))
          .clickable { onExpandClick() }
          .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        // Device icon with badge (or loading indicator)
        Box {
          Icon(
            imageVector = Icons.Default.Devices,
            contentDescription = "Devices",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp)
          )
          // Show a small badge if there are active sessions (and not loading)
          if (!isLoading && activeSessionCount > 0) {
            Box(
              modifier = Modifier
                .align(Alignment.TopEnd)
                .size(8.dp)
                .background(Color(0xFF4CAF50), CircleShape)
            )
          }
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Devices",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
          )
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Text(
              text = if (isLoading) {
                "Scanning..."
              } else {
                buildString {
                  append("$deviceCount connected")
                  if (activeSessionCount > 0) {
                    append(" • $activeSessionCount active")
                  }
                }
              },
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            // Small inline loading indicator
            if (isLoading) {
              CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = MaterialTheme.colorScheme.primary
              )
            }
          }
        }

        Icon(
          imageVector = if (isExpanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
          contentDescription = if (isExpanded) "Collapse" else "Expand",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(20.dp)
        )
      }

      // Minimize button (separate from expand/collapse)
      IconButton(
        onClick = onMinimizeClick,
        modifier = Modifier.size(32.dp)
      ) {
        Icon(
          imageVector = Icons.Default.Close,
          contentDescription = "Minimize panel",
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(16.dp)
        )
      }
    }
  }
}

@Composable
private fun DeviceStatusItem(
  device: TrailblazeConnectedDeviceSummary,
  sessionId: SessionId?,
  onClick: () -> Unit,
) {
  val hasSession = sessionId != null

  Surface(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(8.dp))
      .then(
        if (hasSession) {
          Modifier.clickable { onClick() }
        } else {
          Modifier
        }
      ),
    color = if (hasSession) {
      MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.5f)
    } else {
      MaterialTheme.colorScheme.surfaceContainerLow
    },
    shape = RoundedCornerShape(8.dp)
  ) {
    Row(
      modifier = Modifier.padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      // Platform icon
      Icon(
        imageVector = device.platform.getIcon(),
        contentDescription = device.platform.toString(),
        tint = if (hasSession) {
          MaterialTheme.colorScheme.tertiary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.size(18.dp)
      )

      Column(modifier = Modifier.weight(1f)) {
        // Device description (short name)
        Text(
          text = device.description,
          style = MaterialTheme.typography.bodySmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )

        // Session indicator or driver type
        if (hasSession) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
          ) {
            Icon(
              imageVector = Icons.Default.PlayArrow,
              contentDescription = null,
              tint = MaterialTheme.colorScheme.tertiary,
              modifier = Modifier.size(12.dp)
            )
            Text(
              text = sessionId!!.value,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.tertiary,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        } else {
          Text(
            text = device.trailblazeDriverType.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      // Click indicator for sessions
      if (hasSession) {
        Text(
          text = "→",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.tertiary
        )
      }
    }
  }
}
