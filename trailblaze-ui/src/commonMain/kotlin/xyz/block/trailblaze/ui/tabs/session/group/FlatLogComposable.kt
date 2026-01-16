package xyz.block.trailblaze.ui.tabs.session.group

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.tabs.session.DetailSection
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration
import xyz.block.trailblaze.yaml.toDetailedString


// Simplified flat detail composables that don't use nested Column layouts

@Composable
fun LlmRequestDetailsFlat(
  log: TrailblazeLog.TrailblazeLlmRequestLog,
) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("LLM Response & Actions") {
      log.llmResponse.forEach { response ->
        CodeBlock(response.toString())
        if (log.llmResponse.last() != response) {
          Spacer(modifier = Modifier.height(8.dp))
        }
      }
    }

    DetailSection("Request Duration") {
      Text(formatDuration(log.durationMs))
    }

    DetailSection("Available Tools") {
      log.toolOptions.forEach { toolOption ->
        CodeBlock(toolOption.name)
      }
    }

    DetailSection("Chat History") {
      Text("Use the 'Chat History' button", style = MaterialTheme.typography.bodyMedium)

    }

    DetailSection("View Hierarchy") {
      Text("Use the 'Inspect UI' button", style = MaterialTheme.typography.bodyMedium)
    }
  }
}

@Composable
fun MaestroDriverDetailsFlat(log: TrailblazeLog.MaestroDriverLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Class Name") {
      CodeBlock(log.action::class.simpleName ?: "Unknown")
    }

    DetailSection("Raw Log") {
      CodeBlock(log.toString())
    }
  }
}

@Composable
fun TrailblazeCommandDetailsFlat(log: TrailblazeLog.TrailblazeToolLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Trailblaze Command (JSON)") {
      CodeBlock(TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log))
    }
  }
}

@Composable
fun DelegatingTrailblazeToolDetailsFlat(log: TrailblazeLog.DelegatingTrailblazeToolLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Delegating Trailblaze Tool (JSON)") {
      CodeBlock(log.toString())
    }
  }
}

@Composable
fun MaestroCommandDetailsFlat(log: TrailblazeLog.MaestroCommandLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Maestro Command (YAML)") {
      CodeBlock(log.toString())
    }
  }
}

@Composable
fun AgentTaskStatusDetailsFlat(log: TrailblazeLog.TrailblazeAgentTaskStatusChangeLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Agent Task Status") {
      Text("Status Type: ${log.agentTaskStatus::class.simpleName}")
      Spacer(modifier = Modifier.height(8.dp))
      Text("Prompt:", fontWeight = FontWeight.Bold)
      CodeBlock(log.agentTaskStatus.statusData.prompt)
    }
  }
}

@Composable
fun SessionStatusDetailsFlat(log: TrailblazeLog.TrailblazeSessionStatusChangeLog) {
  val (icon, color, label) = getSessionStatusIconAndColor(log.sessionStatus)

  // Extract failure, cancellation, and max calls messages
  val failureMessage = when (val status = log.sessionStatus) {
    is SessionStatus.Ended.Failed -> status.exceptionMessage
    is SessionStatus.Ended.FailedWithFallback -> status.exceptionMessage
    else -> null
  }

  val cancellationMessage = when (val status = log.sessionStatus) {
    is SessionStatus.Ended.Cancelled -> status.cancellationMessage
    else -> null
  }

  val maxCallsMessage = when (val status = log.sessionStatus) {
    is SessionStatus.Ended.MaxCallsLimitReached -> buildString {
      appendLine(
        "The agent reached the maximum number of LLM calls (${status.maxCalls}) allowed per objective."
      )
      appendLine()
      appendLine("Objective prompt:")
      appendLine(status.objectivePrompt)
    }
    else -> null
  }

  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    // Show status indicator with icon and label
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = 8.dp)
    ) {
      Icon(
        imageVector = icon,
        contentDescription = label,
        tint = color,
        modifier = Modifier.size(24.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = label,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = color
      )
    }

    // Show failure reason prominently if present
    if (failureMessage != null) {
      DetailSection("Failure Reason") {
        CodeBlock(failureMessage)
      }
    }

    // Show cancellation reason prominently if present
    if (cancellationMessage != null) {
      DetailSection("Cancellation Reason") {
        CodeBlock(cancellationMessage)
      }
    }

    // Show max calls limit information prominently if present
    if (maxCallsMessage != null) {
      DetailSection("Max LLM Calls Limit Reached") {
        CodeBlock(maxCallsMessage)
      }
    }

    DetailSection("Session Status") {
      CodeBlock(log.sessionStatus.toString())
    }
  }
}

/**
 * Returns icon, color, and label for a given session status
 */
private fun getSessionStatusIconAndColor(status: SessionStatus): Triple<ImageVector, Color, String> {
  return when (status) {
    is SessionStatus.Started -> Triple(Icons.Filled.PlayArrow, Color(0xFF2196F3), "Session Started")
    is SessionStatus.Ended.Succeeded -> Triple(
      Icons.Filled.CheckCircle, Color(0xFF28A745), "Session Succeeded"
    )

    is SessionStatus.Ended.Failed -> Triple(Icons.Filled.Close, Color(0xFFDC3545), "Session Failed")
    is SessionStatus.Ended.Cancelled -> Triple(
      Icons.Filled.Close, Color(0xFFFFC107), "Session Cancelled"
    )

    is SessionStatus.Ended.SucceededWithFallback -> Triple(
      Icons.Filled.Warning, Color(0xFF28A745), "Session Succeeded (with AI Fallback)"
    )

    is SessionStatus.Ended.FailedWithFallback -> Triple(
      Icons.Filled.Warning, Color(0xFFDC3545), "Session Failed (with AI Fallback)"
    )

    is SessionStatus.Unknown -> Triple(Icons.Filled.Warning, Color.Gray, "Unknown Status")
    is SessionStatus.Ended.TimeoutReached -> Triple(
      first = Icons.Filled.Timer,
      second = Color(0xFFFF7F00),
      third = "Timed Out",
    )
    is SessionStatus.Ended.MaxCallsLimitReached -> Triple(
      first = Icons.Filled.Block,
      second = Color(0xFFDC3545),
      third = "Max LLM Calls Limit Reached",
    )
  }
}

@Composable
fun ObjectiveStartDetailsFlat(log: TrailblazeLog.ObjectiveStartLog) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Objective Start") {
      CodeBlock(TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log))
    }
  }
}

@Composable
fun ObjectiveCompleteDetailsFlat(log: TrailblazeLog.ObjectiveCompleteLog) {
  val isSuccess = log.objectiveResult is AgentTaskStatus.Success.ObjectiveComplete
  val statusText = when (log.objectiveResult) {
    is AgentTaskStatus.Success.ObjectiveComplete -> "Success"
    else -> "Failed"
  }
  val statusColor = when (statusText) {
    "Success" -> Color(0xFF28A745)
    else -> Color(0xFFDC3545)
  }

  // Extract the LLM explanation
  val llmExplanation = when (val result = log.objectiveResult) {
    is AgentTaskStatus.Success.ObjectiveComplete -> result.llmExplanation
    is AgentTaskStatus.Failure.ObjectiveFailed -> result.llmExplanation
    else -> null
  }

  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    // Status indicator at the top
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.padding(bottom = 8.dp)
    ) {
      Icon(
        imageVector = if (statusText=="Success") Icons.Filled.CheckCircle else Icons.Filled.Close,
        contentDescription = statusText,
        tint = statusColor,
        modifier = Modifier.size(24.dp)
      )
      Spacer(modifier = Modifier.width(8.dp))
      Text(
        text = "Objective $statusText",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = statusColor
      )
    }

    // Display LLM Explanation prominently
    if (llmExplanation != null) {
      DetailSection("LLM Explanation") {
        CodeBlock(llmExplanation)
      }
    }

    DetailSection("Prompt") {
      CodeBlock(log.objectiveResult.statusData.prompt)
    }

    DetailSection("Objective Complete (Full Details)") {
      CodeBlock(TrailblazeJson.defaultWithoutToolsInstance.encodeToString(log))
    }

    DetailSection("Result") {
      CodeBlock(log.objectiveResult.toString())
    }
  }
}

@Composable
fun AttemptAiFallbackFlat(
  log: TrailblazeLog.AttemptAiFallbackLog,
) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    DetailSection("Objective Complete") {
      CodeBlock(
        text = buildString {
          appendLine("Prompt Step: " + log.promptStep.toDetailedString())
          appendLine("Recording Result: " + log.recordingResult)
        }
      )
    }
    DetailSection("Recording Result") {
      CodeBlock(log.recordingResult.toString())
    }
    DetailSection("Prompt") {
      CodeBlock(log.promptStep.prompt)
    }
  }
}

@Composable
fun DeviceSnapshotFlat(
  log: TrailblazeLog.TrailblazeSnapshotLog,
  sessionId: String,
  imageLoader: xyz.block.trailblaze.ui.images.ImageLoader,
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: xyz.block.trailblaze.api.MaestroDriverActionType?) -> Unit,
  showInspectUI: (() -> Unit)?,
) {
  Column(modifier = Modifier.padding(horizontal = 16.dp)) {
    // Display the user-provided display name if provided
    val displayName = log.displayName
    if (displayName != null) {
      DetailSection("Display Name") {
        CodeBlock(displayName)
      }
    }

    // Note: Test context (testClassName, testMethodName) is available in the session-level
    // SessionStatus.Started log, not duplicated here

    // Display screenshot
    DetailSection("Screenshot") {
      xyz.block.trailblaze.ui.composables.ScreenshotImage(
        sessionId = sessionId,
        screenshotFile = log.screenshotFile,
        deviceWidth = log.deviceWidth,
        deviceHeight = log.deviceHeight,
        clickX = null,
        clickY = null,
        action = null,
        modifier = Modifier.fillMaxWidth(),
        imageLoader = imageLoader,
        forceHighQuality = false,
        onImageClick = { imageModel, deviceWidth, deviceHeight, clickX, clickY ->
          // Snapshot logs should open UI Inspector when screenshot is clicked (if view hierarchy available)
          if (showInspectUI != null) {
            showInspectUI.invoke()
          } else {
            // Fall back to screenshot modal if no view hierarchy handler available
            onShowScreenshotModal(imageModel, deviceWidth, deviceHeight, clickX, clickY, null)
          }
        }
      )
      
      // Add helpful hint for UI Inspector
      if (showInspectUI != null) {
        Spacer(modifier = Modifier.height(4.dp))
        xyz.block.trailblaze.ui.composables.SelectableText(
          text = "💡 Click screenshot to inspect UI elements",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
      }
    }

    // Display screenshot file path
    DetailSection("Screenshot File") {
      CodeBlock(log.screenshotFile)
    }

    // Display device dimensions
    DetailSection("Device Dimensions") {
      CodeBlock("${log.deviceWidth} x ${log.deviceHeight}")
    }
  }
}
