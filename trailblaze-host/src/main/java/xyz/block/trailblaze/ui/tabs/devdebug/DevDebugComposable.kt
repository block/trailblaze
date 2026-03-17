@file:OptIn(ExperimentalMaterial3Api::class)

package xyz.block.trailblaze.ui.tabs.devdebug

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.flow.StateFlow
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.server.McpServerDebugState
import xyz.block.trailblaze.logs.server.McpSessionSnapshot
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.devices.DeviceState

private enum class DebugTab(val title: String) {
  DEVICE_MANAGER("Device Manager"),
  MCP_SESSIONS("MCP Sessions"),
}

/**
 * Opens the Dev Debug view in a separate window.
 * Call this composable and it will manage its own window state.
 */
@Composable
fun DevDebugWindow(
  deviceManager: TrailblazeDeviceManager,
  mcpServerDebugStateFlow: StateFlow<McpServerDebugState>,
  onCloseRequest: () -> Unit,
) {
  Window(
    onCloseRequest = onCloseRequest,
    title = "Dev Debug",
    state = rememberWindowState(size = DpSize(800.dp, 900.dp))
  ) {
    MaterialTheme {
      DevDebugContent(
        deviceManager = deviceManager,
        mcpServerDebugStateFlow = mcpServerDebugStateFlow,
        showPopOutButton = false,
      )
    }
  }
}

@Composable
private fun DevDebugContent(
  deviceManager: TrailblazeDeviceManager,
  mcpServerDebugStateFlow: StateFlow<McpServerDebugState>,
  showPopOutButton: Boolean,
  onPopOut: () -> Unit = {},
) {
  var selectedTabIndex by remember { mutableIntStateOf(0) }
  val tabs = DebugTab.entries

  Column(modifier = Modifier.fillMaxSize()) {
    // Header with Tab Row and Pop Out button
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Tab Row
      PrimaryTabRow(
        selectedTabIndex = selectedTabIndex,
        modifier = Modifier.weight(1f)
      ) {
        tabs.forEachIndexed { index, tab ->
          Tab(
            selected = selectedTabIndex == index,
            onClick = { selectedTabIndex = index },
            text = { Text(tab.title) }
          )
        }
      }

      // Pop Out Button
      if (showPopOutButton) {
        IconButton(onClick = onPopOut) {
          Icon(
            imageVector = Icons.Default.OpenInNew,
            contentDescription = "Open in new window"
          )
        }
      }
    }

    // Tab Content
    when (tabs[selectedTabIndex]) {
      DebugTab.DEVICE_MANAGER -> DeviceManagerDebugTab(deviceManager)
      DebugTab.MCP_SESSIONS -> McpSessionsDebugTab(mcpServerDebugStateFlow)
    }
  }
}

@Composable
private fun DeviceManagerDebugTab(deviceManager: TrailblazeDeviceManager) {
  val activeSessions = deviceManager.activeDeviceSessionsFlow.collectAsState()
  val deviceState = deviceManager.deviceStateFlow.collectAsState()
  val installedApps = deviceManager.installedAppIdsByDeviceFlow.collectAsState()

  Column(
    modifier = Modifier
      .fillMaxSize()
      .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    // Actions Section
    Button(onClick = { deviceManager.loadDevices() }) {
      Text("Load Devices")
    }

    // Status Row
    Row(
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically
    ) {
      // Loading State
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Loading:",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium
        )
        Text(
          text = if (deviceState.value.isLoading) "Yes" else "No",
          style = MaterialTheme.typography.bodyMedium,
          color = if (deviceState.value.isLoading)
            MaterialTheme.colorScheme.primary
          else
            MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      // Active Sessions Count
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Active Sessions:",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium
        )
        Text(
          text = "${activeSessions.value.size}",
          style = MaterialTheme.typography.bodyMedium,
          color = if (activeSessions.value.isNotEmpty())
            MaterialTheme.colorScheme.tertiary
          else
            MaterialTheme.colorScheme.onSurfaceVariant
        )
      }

      // Error (if any)
      deviceState.value.error?.let { error ->
        Text(
          text = "Error: $error",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.error
        )
      }
    }

    HorizontalDivider()

    // Device List
    Column(
      modifier = Modifier
        .fillMaxSize()
        .verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      ByDeviceView(
        activeSessions = activeSessions.value,
        devices = deviceState.value.devices,
        installedApps = installedApps.value
      )
    }
  }
}

@Composable
private fun ByDeviceView(
  activeSessions: Map<TrailblazeDeviceId, SessionId>,
  devices: Map<TrailblazeDeviceId, DeviceState>,
  installedApps: Map<TrailblazeDeviceId, Set<String>>
) {
  // Collect all unique device IDs from all data sources
  val allDeviceIds = (activeSessions.keys + devices.keys + installedApps.keys).toSet()

  if (allDeviceIds.isEmpty()) {
    Card(
      modifier = Modifier.fillMaxWidth(),
      colors = CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
      )
    ) {
      Text(
        text = "No devices found. Click 'Load Devices' to discover connected devices.",
        modifier = Modifier.padding(16.dp),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
    }
  } else {
    allDeviceIds.forEach { deviceId ->
      DeviceCard(
        deviceId = deviceId,
        activeSession = activeSessions[deviceId],
        deviceState = devices[deviceId],
        installedAppIds = installedApps[deviceId]
      )
    }
  }
}

@Composable
private fun DeviceCard(
  deviceId: TrailblazeDeviceId,
  activeSession: SessionId?,
  deviceState: DeviceState?,
  installedAppIds: Set<String>?
) {
  var expanded by remember { mutableStateOf(true) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    )
  ) {
    Column {
      // Device Header
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { expanded = !expanded }
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = deviceId.instanceId,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
          )
          Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Text(
              text = deviceId.trailblazeDevicePlatform.name,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary
            )
            deviceState?.device?.trailblazeDriverType?.let { driverType ->
              Text(
                text = driverType.name,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.secondary
              )
            }
            if (activeSession != null) {
              Text(
                text = "● Session: ${activeSession.value}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
              )
            }
          }
        }
        Icon(
          imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = if (expanded) "Collapse" else "Expand",
          modifier = Modifier.size(24.dp)
        )
      }

      // Expanded Content
      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          // Active Session Section
          DeviceDataSection(
            title = "Active Session",
            hasData = activeSession != null
          ) {
            CodeBlock(
              TrailblazeJsonInstance.encodeToString(activeSession)
            )
          }

          // Device State Section
          DeviceDataSection(
            title = "Device State",
            hasData = deviceState != null
          ) {
            CodeBlock(
              TrailblazeJsonInstance.encodeToString(deviceState)
            )
          }

          // Installed Apps Section
          DeviceDataSection(
            title = "Installed Apps",
            hasData = !installedAppIds.isNullOrEmpty(),
            itemCount = installedAppIds?.size
          ) {
            CodeBlock(
              TrailblazeJsonInstance.encodeToString(installedAppIds)
            )
          }
        }
      }
    }
  }
}

@Composable
private fun DeviceDataSection(
  title: String,
  hasData: Boolean,
  itemCount: Int? = null,
  content: @Composable () -> Unit
) {
  var expanded by remember { mutableStateOf(false) }

  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { if (hasData) expanded = !expanded }
        .padding(vertical = 4.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium
        )
        if (itemCount != null) {
          Text(
            text = "($itemCount)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
        if (!hasData) {
          Text(
            text = "—",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
        }
      }
      if (hasData) {
        Icon(
          imageVector = if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
          contentDescription = if (expanded) "Collapse" else "Expand",
          modifier = Modifier.size(20.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
      }
    }

    AnimatedVisibility(visible = expanded && hasData) {
      Column(modifier = Modifier.padding(top = 8.dp)) {
        content()
      }
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// MCP Sessions Debug Tab
// ─────────────────────────────────────────────────────────────────────────────

@Composable
private fun McpSessionsDebugTab(mcpServerDebugStateFlow: StateFlow<McpServerDebugState>) {
  val mcpState by mcpServerDebugStateFlow.collectAsState()

  Column(
    modifier = Modifier.fillMaxSize().padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    // Server Status Row
    Row(
      horizontalArrangement = Arrangement.spacedBy(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Server:",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = if (mcpState.isRunning) "Running" else "Stopped",
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (mcpState.isRunning) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.error,
        )
      }

      Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
          text = "Sessions:",
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
        )
        Text(
          text = "${mcpState.sessions.size}",
          style = MaterialTheme.typography.bodyMedium,
          color =
            if (mcpState.sessions.isNotEmpty()) MaterialTheme.colorScheme.tertiary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      if (mcpState.isRunning) {
        Text(
          text = "MCP Enabled",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    HorizontalDivider()

    // Session List
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      if (mcpState.sessions.isEmpty()) {
        Card(
          modifier = Modifier.fillMaxWidth(),
          colors =
            CardDefaults.cardColors(
              containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            ),
        ) {
          Text(
            text = "No active MCP sessions. Connect an MCP client to see sessions here.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      } else {
        mcpState.sessions.forEach { session -> McpSessionCard(session) }
      }
    }
  }
}

@Composable
private fun McpSessionCard(session: McpSessionSnapshot) {
  var expanded by remember { mutableStateOf(true) }

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors =
      CardDefaults.cardColors(
        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
      ),
  ) {
    Column {
      // Session Header
      Row(
        modifier =
          Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column {
          Text(
            text = session.clientName ?: "Unknown Client",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
          )
          Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
              text = session.mode.name,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.primary,
            )
            Text(
              text = session.toolProfile.name,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.secondary,
            )
            session.associatedDeviceId?.let { deviceId ->
              Text(
                text = deviceId.instanceId,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
              )
            }
            if (session.isRecording) {
              Text(
                text = "REC ${session.currentTrailName ?: ""}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
              )
            }
          }
        }
        Icon(
          imageVector =
            if (expanded) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
          contentDescription = if (expanded) "Collapse" else "Expand",
          modifier = Modifier.size(24.dp),
        )
      }

      // Expanded Details
      AnimatedVisibility(visible = expanded) {
        Column(
          modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          McpSessionDetailRow("Session ID", session.sessionId)
          McpSessionDetailRow("Mode", session.mode.name)
          McpSessionDetailRow("Tool Profile", session.toolProfile.name)
          McpSessionDetailRow("Agent", session.agentImplementation.name)
          McpSessionDetailRow(
            "Device",
            session.associatedDeviceId?.instanceId ?: "None"
          )
          McpSessionDetailRow("Recording", if (session.isRecording) "Yes" else "No")
          session.currentTrailName?.let { McpSessionDetailRow("Trail", it) }
          session.createdAtMillis?.let { createdAt ->
            val ageSeconds = (System.currentTimeMillis() - createdAt) / 1000
            val ageMinutes = ageSeconds / 60
            McpSessionDetailRow("Age", "${ageMinutes}m ${ageSeconds % 60}s")
          }
        }
      }
    }
  }
}

@Composable
private fun McpSessionDetailRow(label: String, value: String) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "$label:",
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodySmall,
    )
  }
}
