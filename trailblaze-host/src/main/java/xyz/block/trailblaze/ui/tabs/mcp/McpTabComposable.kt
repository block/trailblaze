package xyz.block.trailblaze.ui.tabs.mcp

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.server.McpServerDebugState
import xyz.block.trailblaze.logs.server.McpSessionSnapshot
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.ui.model.LocalNavController
import xyz.block.trailblaze.ui.model.TrailblazeRoute
import xyz.block.trailblaze.ui.model.navigateToRoute

private val McpGreen: Color = Color(0xFF4CAF50)

@Composable
fun McpTabComposable(
  mcpServerDebugStateFlow: StateFlow<McpServerDebugState>,
  trailblazeSettingsRepo: TrailblazeSettingsRepo,
  recommendTrailblazeAsAgent: Boolean = false,
) {
  val mcpState by mcpServerDebugStateFlow.collectAsState()
  val navController = LocalNavController.current

  Column(
    modifier =
      Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    verticalArrangement = Arrangement.spacedBy(24.dp),
  ) {
    // Header
    Column {
      Text(
        text = "MCP Server",
        style = MaterialTheme.typography.headlineLarge,
        fontWeight = FontWeight.Bold,
      )
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text =
          "Connect AI coding assistants to Trailblaze via the Model Context Protocol. " +
            "MCP clients can control devices, run tests, and explore apps through natural language.",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    // Server Status
    ServerStatusSection(mcpState = mcpState)

    // Active Sessions
    ActiveSessionsSection(
      mcpState = mcpState,
      onSessionClick = { session ->
        // Navigate to the session detail view via the Sessions tab
        trailblazeSettingsRepo.updateState { state ->
          state.copy(
            appConfig = state.appConfig.copy(currentSessionId = session.sessionId)
          )
        }
        navController.navigateToRoute(TrailblazeRoute.Sessions)
      },
    )

    HorizontalDivider()

    // Client Setup
    ClientSetupSection(
      openGoose = { TrailblazeDesktopUtil.openGoose(port = trailblazeSettingsRepo.portManager.httpPort) },
    )

    // How It Works
    HowItWorksSection(recommendTrailblazeAsAgent = recommendTrailblazeAsAgent)
  }
}

// ---------------------------------------------------------------------------
// Server Status
// ---------------------------------------------------------------------------

@Composable
private fun ServerStatusSection(mcpState: McpServerDebugState) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Status indicator dot
        Box(
          modifier =
            Modifier.size(12.dp)
              .clip(CircleShape)
              .background(
                if (mcpState.isRunning) McpGreen
                else MaterialTheme.colorScheme.error
              )
        )
        Text(
          text = if (mcpState.isRunning) "Server Running" else "Server Stopped",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
      }

      if (mcpState.isRunning) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(24.dp),
        ) {
          StatusItem(label = "MCP", value = "Enabled")
          StatusItem(label = "Active Sessions", value = "${mcpState.sessions.size}")
        }
      } else {
        Text(
          text =
            "The MCP server starts automatically when Trailblaze launches. " +
              "If it's not running, try restarting the app.",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

@Composable
private fun StatusItem(label: String, value: String) {
  Column {
    Text(
      text = label,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(
      text = value,
      style = MaterialTheme.typography.bodyMedium,
      fontWeight = FontWeight.Medium,
    )
  }
}

// ---------------------------------------------------------------------------
// Active Sessions
// ---------------------------------------------------------------------------

@Composable
private fun ActiveSessionsSection(
  mcpState: McpServerDebugState,
  onSessionClick: (McpSessionSnapshot) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = "Active Sessions",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )

    if (mcpState.sessions.isEmpty()) {
      OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier = Modifier.padding(20.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = "No active MCP sessions",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
          )
          Text(
            text =
              "Connect an MCP client like Claude Code or Goose to see active sessions here. " +
                "Each session represents a client interacting with a device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      mcpState.sessions.forEach { session ->
        McpSessionCard(session = session, onClick = { onSessionClick(session) })
      }
    }
  }
}

@Composable
private fun McpSessionCard(session: McpSessionSnapshot, onClick: () -> Unit) {
  OutlinedCard(
    onClick = onClick,
    modifier = Modifier.fillMaxWidth(),
  ) {
    Row(
      modifier = Modifier.padding(16.dp).fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(
        modifier = Modifier.weight(1f),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        Text(
          text = session.clientName ?: "Unknown Client",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
          SessionTag(
            text = session.mode.name.replace("_", " "),
            color = MaterialTheme.colorScheme.primary,
          )
          SessionTag(
            text = session.toolProfile.name,
            color = MaterialTheme.colorScheme.secondary,
          )
          session.associatedDeviceId?.let { deviceId ->
            SessionTag(
              text = deviceId.instanceId,
              color = MaterialTheme.colorScheme.tertiary,
            )
          }
          if (session.isRecording) {
            SessionTag(
              text = "REC ${session.currentTrailName ?: ""}".trim(),
              color = MaterialTheme.colorScheme.error,
            )
          }
        }
        session.createdAtMillis?.let { createdAt ->
          val ageSeconds = (System.currentTimeMillis() - createdAt) / 1000
          val ageMinutes = ageSeconds / 60
          Text(
            text = "Connected ${ageMinutes}m ${ageSeconds % 60}s ago",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
      Icon(
        imageVector = Icons.Default.OpenInNew,
        contentDescription = "View session details",
        modifier = Modifier.size(20.dp),
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SessionTag(text: String, color: Color) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    color = color,
    fontWeight = FontWeight.Medium,
  )
}

// ---------------------------------------------------------------------------
// Client Setup
// ---------------------------------------------------------------------------

@Composable
private fun ClientSetupSection(openGoose: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = "Connect an MCP Client",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Text(
      text =
        "Set up your preferred AI assistant to use Trailblaze as an MCP tool server.",
      style = MaterialTheme.typography.bodyMedium,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      ClaudeCodeSetupCard(
        modifier = Modifier.weight(1f),
      )
      GooseSetupCard(
        modifier = Modifier.weight(1f),
        openGoose = openGoose,
      )
    }
  }
}

@Composable
private fun ClaudeCodeSetupCard(modifier: Modifier = Modifier) {

  OutlinedCard(modifier = modifier) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Terminal,
          contentDescription = null,
          modifier = Modifier.size(32.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Claude Code",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = "Anthropic's AI coding assistant",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Text(
        text =
          "Add Trailblaze as an MCP server in your Claude Code configuration. " +
            "Claude will be able to control devices and run tests via natural language.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      // Quick setup: copy the CLI command
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
          text = "Run in your terminal:",
          style = MaterialTheme.typography.labelMedium,
          fontWeight = FontWeight.Medium,
        )
        CopyableCommand(command = "claude mcp add trailblaze -- trailblaze mcp")
      }

      Text(
        text =
          "This connects Claude Code to Trailblaze via STDIO. " +
            "The Trailblaze daemon starts automatically if needed and sessions survive app restarts.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun GooseSetupCard(modifier: Modifier = Modifier, openGoose: () -> Unit) {
  OutlinedCard(modifier = modifier) {
    Column(
      modifier = Modifier.padding(20.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Goose icon placeholder
        Icon(
          imageVector = Icons.Filled.OpenInNew,
          contentDescription = null,
          modifier = Modifier.size(32.dp),
          tint = MaterialTheme.colorScheme.primary,
        )
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Goose",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
          )
          Text(
            text = "Block's open-source AI agent",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      Text(
        text =
          "Trailblaze integrates natively with Goose. " +
            "Click below to install the Trailblaze extension and open Goose with a pre-configured recipe.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      Button(
        onClick = openGoose,
        modifier = Modifier.fillMaxWidth(),
      ) {
        Text("Open Goose with Trailblaze")
      }

      Text(
        text =
          "This will install the Trailblaze extension in Goose's config if needed " +
            "and launch Goose with mobile testing tools pre-loaded.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// ---------------------------------------------------------------------------
// How It Works
// ---------------------------------------------------------------------------

@Composable
private fun HowItWorksSection(recommendTrailblazeAsAgent: Boolean) {
  Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text(
      text = "How MCP Works with Trailblaze",
      style = MaterialTheme.typography.headlineSmall,
      fontWeight = FontWeight.SemiBold,
    )

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        HowItWorksItem(
          number = "1",
          title = "Client Connects",
          description =
            "An MCP client (Claude Code, Goose, etc.) connects to the Trailblaze server " +
              "via HTTP or STDIO transport.",
        )
        HowItWorksItem(
          number = "2",
          title = "Tools Are Discovered",
          description =
            "The client discovers available tools: device control, screen reading, " +
              "element interaction, verification, and trail management.",
        )
        HowItWorksItem(
          number = "3",
          title = "AI Drives the Device",
          description =
            "The AI reasons about the screen, decides actions, and calls Trailblaze tools " +
              "to tap, type, scroll, and verify UI state on real devices and emulators.",
        )
        HowItWorksItem(
          number = "4",
          title = "Sessions Are Recorded",
          description =
            "Every interaction is logged as a session. You can review results, " +
              "export recordings as trail YAML, and replay them later.",
        )

        HorizontalDivider()

        Text(
          text = "Operating Modes",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
        )
        Text(
          text =
            "MCP clients can switch modes at runtime using the config tool: " +
              "config(action=SET, key=\"mode\", value=\"MCP_CLIENT_AS_AGENT\")",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ModeCard(
          title = "MCP Client as Agent",
          description =
            "The MCP client (Claude, Goose) handles all AI reasoning and calls " +
              "device primitives directly (tap, swipe, type, read screen). " +
              "No LLM configuration needed in Trailblaze.",
          isRecommended = !recommendTrailblazeAsAgent,
        )
        ModeCard(
          title = "Trailblaze as Agent",
          description =
            if (recommendTrailblazeAsAgent) {
              "Trailblaze handles AI reasoning internally using pre-configured LLM services. " +
                "The client sends high-level prompts and Trailblaze executes multi-step flows. " +
                "Generally higher accuracy with our LLM configuration."
            } else {
              "Trailblaze handles AI reasoning internally using blaze/verify/ask tools. " +
                "The client sends high-level prompts and Trailblaze executes multi-step flows. " +
                "Requires LLM configuration in Settings."
            },
          isRecommended = recommendTrailblazeAsAgent,
        )
      }
    }
  }

@Composable
private fun HowItWorksItem(number: String, title: String, description: String) {
  Row(
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Box(
      modifier =
        Modifier.size(28.dp)
          .clip(CircleShape)
          .background(MaterialTheme.colorScheme.primaryContainer),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = number,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimaryContainer,
      )
    }
    Column(
      modifier = Modifier.weight(1f),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ModeCard(title: String, description: String, isRecommended: Boolean) {
  OutlinedCard(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = title,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
        )
        if (isRecommended) {
          Text(
            text = "Recommended",
            style = MaterialTheme.typography.labelSmall,
            color = McpGreen,
            fontWeight = FontWeight.Bold,
          )
        }
      }
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// ---------------------------------------------------------------------------
// Shared Components
// ---------------------------------------------------------------------------

@Composable
private fun CopyableCommand(command: String) {
  val clipboardManager = LocalClipboardManager.current
  val scope = rememberCoroutineScope()
  var copied by remember { mutableStateOf(false) }
  var copyResetJob by remember { mutableStateOf<Job?>(null) }

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clip(RoundedCornerShape(8.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant)
        .padding(start = 12.dp, top = 4.dp, bottom = 4.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
      text = command,
      style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.weight(1f),
    )
    IconButton(
      onClick = {
        clipboardManager.setText(AnnotatedString(command))
        copied = true
        copyResetJob?.cancel()
        copyResetJob = scope.launch {
          delay(2000)
          copied = false
        }
      },
      modifier = Modifier.size(32.dp),
    ) {
      if (copied) {
        Icon(
          imageVector = Icons.Filled.CheckCircle,
          contentDescription = "Copied",
          modifier = Modifier.size(16.dp),
          tint = McpGreen,
        )
      } else {
        Icon(
          imageVector = Icons.Filled.ContentCopy,
          contentDescription = "Copy to clipboard",
          modifier = Modifier.size(16.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
