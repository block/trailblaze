package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeJson
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.composables.CodeBlock
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.models.LogCardData
import xyz.block.trailblaze.ui.utils.ColorUtils
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

@Composable
fun LogCard(
  log: TrailblazeLog,
  sessionId: String,
  sessionStartTime: Instant,
  toMaestroYaml: (JsonObject) -> String,
  toTrailblazeYaml: (toolName: String, trailblazeTool: TrailblazeTool) -> String,
  imageLoader: ImageLoader = NetworkImageLoader(),
  showDetails: (() -> Unit)? = null,
  showInspectUI: (() -> Unit)? = null,
  showChatHistory: (() -> Unit)? = null,
  cardSize: androidx.compose.ui.unit.Dp? = null,
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: AgentDriverAction?) -> Unit = { _, _, _, _, _, _ -> },
  onOpenInFinder: (() -> Unit)? = null,
) {

  val elapsedTimeMs = log.timestamp.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()

  val logData = when (log) {
    is TrailblazeLog.TrailblazeToolLog -> LogCardData(
      title = "Tool: ${log.toolName}",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = toTrailblazeYaml(log.toolName, log.trailblazeTool)
    )

    is TrailblazeLog.MaestroCommandLog -> LogCardData(
      title = "Maestro Command",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = toMaestroYaml(log.maestroCommandJsonObj)
    )

    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> LogCardData(
      title = "Agent Task Status",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = log.agentTaskStatus.toString()
    )

    is TrailblazeLog.TrailblazeLlmRequestLog -> LogCardData(
      title = if (log.llmRequestLabel != null) "LLM: ${log.llmRequestLabel}" else "LLM Request",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
    )

    is TrailblazeLog.AgentDriverLog -> LogCardData(
      title = "Driver ${log.action.type.name}",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
      preformattedText = null,
    )

    is TrailblazeLog.DelegatingTrailblazeToolLog -> LogCardData(
      title = "Delegating Tool: ${log.toolName}",
      duration = null,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        appendLine(toTrailblazeYaml(log.toolName, log.trailblazeTool))
      }
    )

    is TrailblazeLog.ObjectiveStartLog -> LogCardData(
      title = "Objective Start",
      duration = null,
      elapsedTime = elapsedTimeMs,
      preformattedText = log.promptStep.prompt
    )

    is TrailblazeLog.ObjectiveCompleteLog -> LogCardData(
      title = "Objective Complete",
      duration = null,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        appendLine(log.promptStep.prompt)
      }
    )

    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> LogCardData(
      title = "Session Status",
      duration = null,
      elapsedTime = elapsedTimeMs,
      preformattedText = log.sessionStatus.toString()
    )

    is TrailblazeLog.AttemptAiFallbackLog -> LogCardData(
      title = "Attempt AI Fallback",
      duration = null,
      elapsedTime = elapsedTimeMs,
      preformattedText = log.promptStep.prompt
    )

    is TrailblazeLog.TrailblazeSnapshotLog -> LogCardData(
      title = "Device Snapshot",
      duration = null,
      elapsedTime = elapsedTimeMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
      preformattedText = log.displayName?.let { "Name: $it" }
    )

    is TrailblazeLog.AccessibilityActionLog -> LogCardData(
      title = "Accessibility: ${log.actionDescription}",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = log.actionJsonObj.toString()
    )

    is TrailblazeLog.McpAgentRunLog -> LogCardData(
      title = "MCP Agent: ${log.objective.take(50)}${if (log.objective.length > 50) "..." else ""}",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        appendLine("Transport: ${log.transportMode}")
        appendLine("Strategy: ${log.llmStrategy}")
        appendLine("Iterations: ${log.iterationCount}")
        appendLine("Tools Called: ${log.toolCallCount}")
        appendLine("Success: ${log.successful}")
        appendLine()
        appendLine("Result: ${log.resultMessage}")
      }
    )

    is TrailblazeLog.McpAgentIterationLog -> LogCardData(
      title = "MCP Iteration #${log.iterationNumber}",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        appendLine("Type: ${log.responseType}")
        log.toolName?.let { appendLine("Tool: $it") }
        log.llmCompletion?.let { appendLine("Completion: ${it.take(200)}...") }
      }
    )

    is TrailblazeLog.McpSamplingLog -> LogCardData(
      title = "MCP Sampling",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        appendLine("Strategy: ${log.llmStrategy}")
        appendLine("Model: ${log.modelName ?: "unknown"}")
        appendLine("Screenshot: ${log.includedScreenshot}")
        appendLine()
        appendLine("Completion: ${log.completion.take(200)}...")
      }
    )

    is TrailblazeLog.McpAgentToolLog -> LogCardData(
      title = "MCP Tool: ${log.toolName}",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        appendLine("Transport: ${log.transportMode}")
        appendLine("Success: ${log.successful}")
        appendLine("Args: ${log.toolArgs}")
        appendLine()
        appendLine("Result: ${log.resultOutput.take(500)}")
      }
    )

    is TrailblazeLog.McpToolCallRequestLog -> LogCardData(
      title = "MCP Request: ${log.toolName}",
      duration = null,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        val args = log.toolArgs.toString()
        if (args != "{}" && args.isNotEmpty()) {
          appendLine(args.take(500))
        }
      }.takeIf { it.isNotBlank() }
    )

    is TrailblazeLog.McpToolCallResponseLog -> LogCardData(
      title = if (log.successful) "MCP Response: ${log.toolName}" else "MCP Response: ${log.toolName} (FAILED)",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        log.errorMessage?.let { appendLine("Error: $it\n") }
        val result = log.resultSummary.prettyPrint().take(800)
        if (result.isNotBlank()) {
          appendLine(result)
        }
      }.takeIf { it.isNotBlank() }
    )

    is TrailblazeLog.TrailblazeProgressLog -> LogCardData(
      title = "Progress: ${log.eventType}",
      duration = log.durationMs.takeIf { it > 0 },
      elapsedTime = elapsedTimeMs,
      preformattedText = buildString {
        appendLine(log.description)
        log.stepIndex?.let { step ->
          appendLine("Step ${step + 1}${log.totalSteps?.let { " of $it" } ?: ""}")
        }
        log.progressPercent?.let { appendLine("Progress: $it%") }
      }
    )
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
      ),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    elevation = CardDefaults.cardElevation(
      defaultElevation = 6.dp
    )
  ) {
    Column {
      // Colored header bar
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(5.dp)
          .background(ColorUtils.getLogTypeColor(log))
      )

      // Colored title background
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .background(ColorUtils.getLogTypeColor(log))
          .padding(horizontal = 12.dp, vertical = 4.dp)
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          // Add success/failure icon for ObjectiveCompleteLog
          if (log is TrailblazeLog.ObjectiveCompleteLog) {
            val isSuccess = log.objectiveResult is AgentTaskStatus.Success.ObjectiveComplete
            Icon(
              imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Close,
              contentDescription = when {
                isSuccess -> "Success"
                else -> "Failed"
              },
              tint = when {
                isSuccess -> Color(0xFF28A745)
                else -> Color(0xFFDC3545)
              },
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
          }
          // Add icon for session status changes
          if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
            val (icon, color, label) = getSessionStatusIconAndColor(log.sessionStatus)
            Icon(
              imageVector = icon,
              contentDescription = label,
              tint = color,
              modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.size(6.dp))
          }
          SelectableText(
            text = buildString {
              append(logData.title)
              logData.duration?.let { duration ->
                append(" (${formatDuration(duration)})")
              }
            },
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = if (isSystemInDarkTheme()) Color.Black else Color.Black
          )
        }
      }

      Column(
        modifier = Modifier.padding(12.dp)
      ) {

        Spacer(modifier = Modifier.height(4.dp))

        logData.preformattedText?.let { preformattedText ->
          CodeBlock(
            text = preformattedText
          )
        }

        // Add LLM Explanation card for ObjectiveCompleteLog
        if (log is TrailblazeLog.ObjectiveCompleteLog) {
          val llmExplanation = when (val result = log.objectiveResult) {
            is AgentTaskStatus.Success.ObjectiveComplete -> result.llmExplanation
            is AgentTaskStatus.Failure.ObjectiveFailed -> result.llmExplanation
            else -> null
          }

          if (llmExplanation != null) {
            Spacer(modifier = Modifier.height(8.dp))
            val isSuccess = log.objectiveResult is AgentTaskStatus.Success.ObjectiveComplete
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(
                  when {
                    isSuccess -> Color(0xFF28A745).copy(alpha = 0.1f)
                    else -> Color(0xFFDC3545).copy(alpha = 0.1f)
                  },
                  shape = RoundedCornerShape(8.dp)
                )
                .border(
                  width = 1.dp,
                  color = when {
                    isSuccess -> Color(0xFF28A745).copy(alpha = 0.3f)
                    else -> Color(0xFFDC3545).copy(alpha = 0.3f)
                  },
                  shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp)
            ) {
              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Icon(
                    imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Close,
                    contentDescription = when {
                      isSuccess -> "Success"
                      else -> "Failed"
                    },
                    tint = when {
                      isSuccess -> Color(0xFF28A745)
                      else -> Color(0xFFDC3545)
                    },
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.size(6.dp))
                  SelectableText(
                    text = "LLM Explanation:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = when {
                      isSuccess -> Color(0xFF28A745)
                      else -> Color(0xFFDC3545)
                    }
                  )
                }
                Spacer(modifier = Modifier.height(4.dp))
                SelectableText(
                  text = llmExplanation,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface
                )
              }
            }
          }
        }

        // Add screenshot if available
        if (logData.screenshotFile != null && logData.deviceWidth != null && logData.deviceHeight != null) {
          Spacer(modifier = Modifier.height(8.dp))
          // Determine click coordinates for AgentDriverLog TapPoint/LongPressPoint
          val (clickX, clickY) =
            if (log is TrailblazeLog.AgentDriverLog) {
              val action = log.action
              if (action is HasClickCoordinates) action.x to action.y else null to null
            } else null to null

          // Extract action for screenshot overlay
          val action = if (log is TrailblazeLog.AgentDriverLog) log.action else null

          // Check if this log has view hierarchy data for UI Inspector
          val hasViewHierarchy = when (log) {
            is TrailblazeLog.TrailblazeLlmRequestLog -> log.viewHierarchy != null
            is TrailblazeLog.AgentDriverLog -> log.viewHierarchy != null
            is TrailblazeLog.TrailblazeSnapshotLog -> true // Snapshot logs always have view hierarchy
            else -> false
          }

          ScreenshotImage(
            sessionId = sessionId,
            screenshotFile = logData.screenshotFile,
            deviceWidth = logData.deviceWidth,
            deviceHeight = logData.deviceHeight,
            clickX = clickX,
            clickY = clickY,
            action = action,
            modifier = Modifier.fillMaxWidth(),
            imageLoader = imageLoader,
            forceHighQuality = cardSize != null,
            onImageClick = { imageModel, deviceWidth, deviceHeight, clickXCoord, clickYCoord ->
              when (log) {
                is TrailblazeLog.TrailblazeLlmRequestLog -> {
                  // LLM request logs should open UI Inspector when screenshot is clicked
                  if (hasViewHierarchy && showInspectUI != null) {
                    showInspectUI.invoke()
                  } else {
                    // Fall back to screenshot modal if no view hierarchy available
                    onShowScreenshotModal(
                      imageModel, deviceWidth, deviceHeight, clickXCoord, clickYCoord,
                      action as? AgentDriverAction
                    )
                  }
                }

                is TrailblazeLog.AgentDriverLog -> {
                  // Maestro driver logs should open annotated screenshot modal when screenshot is clicked
                  onShowScreenshotModal(
                    imageModel, deviceWidth, deviceHeight, clickXCoord, clickYCoord,
                    action as? AgentDriverAction
                  )
                }

                else -> {
                  // For other log types, fall back to previous behavior
                  if (hasViewHierarchy && showInspectUI != null) {
                    showInspectUI.invoke()
                  } else {
                    onShowScreenshotModal(
                      imageModel, deviceWidth, deviceHeight, clickXCoord, clickYCoord,
                      action as? AgentDriverAction
                    )
                  }
                }
              }
            }
          )

          // Add helpful hint for UI Inspector-enabled screenshots
          if (hasViewHierarchy) {
            Spacer(modifier = Modifier.height(4.dp))
            val hintText = when (log) {
              is TrailblazeLog.TrailblazeLlmRequestLog -> "💡 Click screenshot to inspect UI elements"
              is TrailblazeLog.AgentDriverLog -> "💡 Click screenshot to view annotated screenshot"
              else -> "💡 Click screenshot to inspect UI elements"
            }
            SelectableText(
              text = hintText,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
          }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          SelectableText(
            text = "Elapsed: ${formatDuration(logData.elapsedTime)}",
            style = MaterialTheme.typography.bodySmall,
            color = if (isSystemInDarkTheme())
              MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f)
            else
              MaterialTheme.colorScheme.onSurfaceVariant
          )

          Row {
            // Add Inspect UI button for logs with view hierarchy (LLM Request logs, Maestro Driver logs, and Snapshot logs)
            if ((log is TrailblazeLog.TrailblazeLlmRequestLog ||
                (log is TrailblazeLog.AgentDriverLog && log.viewHierarchy != null) ||
                log is TrailblazeLog.TrailblazeSnapshotLog) &&
              showInspectUI != null
            ) {
              IconButton(
                onClick = { showInspectUI.invoke() },
                modifier = Modifier.size(24.dp)
              ) {
                Icon(
                  imageVector = Icons.Filled.Search,
                  contentDescription = "Inspect UI",
                  modifier = Modifier.size(16.dp),
                  tint = if (isSystemInDarkTheme())
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                  else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
              }
            }

            // Add Chat History button for LLM Request logs
            if (log is TrailblazeLog.TrailblazeLlmRequestLog && showChatHistory != null) {
              IconButton(
                onClick = { showChatHistory.invoke() },
                modifier = Modifier.size(24.dp)
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.Chat,
                  contentDescription = "Chat History",
                  modifier = Modifier.size(16.dp),
                  tint = if (isSystemInDarkTheme())
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                  else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
              }
            }

            // Info icon
            IconButton(
              onClick = { showDetails?.invoke() },
              modifier = Modifier.size(24.dp)
            ) {
              Icon(
                imageVector = Icons.Filled.Info,
                contentDescription = "View Details",
                modifier = Modifier.size(16.dp),
                tint = if (isSystemInDarkTheme())
                  MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                else
                  MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
              )
            }

            // Open in Finder button comes last (rightmost)
            if (onOpenInFinder != null) {
              IconButton(
                onClick = { onOpenInFinder.invoke() },
                modifier = Modifier.size(24.dp)
              ) {
                Icon(
                  imageVector = Icons.Filled.Folder,
                  contentDescription = "Open in Finder",
                  modifier = Modifier.size(16.dp),
                  tint = if (isSystemInDarkTheme())
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
                  else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * Returns icon, color, and label for a given session status
 */
private fun getSessionStatusIconAndColor(status: SessionStatus): Triple<ImageVector, Color, String> {
  return when (status) {
    is SessionStatus.Started -> Triple(Icons.Filled.PlayArrow, Color(0xFF2196F3), "Started")
    is SessionStatus.Ended.Succeeded -> Triple(
      Icons.Filled.CheckCircle, Color(0xFF28A745), "Succeeded"
    )

    is SessionStatus.Ended.Failed -> Triple(Icons.Filled.Close, Color(0xFFDC3545), "Failed")
    is SessionStatus.Ended.Cancelled -> Triple(Icons.Filled.Close, Color(0xFFFFC107), "Cancelled")
    is SessionStatus.Ended.SucceededWithFallback -> Triple(
      Icons.Filled.Warning, Color(0xFF28A745), "Succeeded with Fallback"
    )

    is SessionStatus.Ended.FailedWithFallback -> Triple(
      Icons.Filled.Warning, Color(0xFFDC3545), "Failed with Fallback"
    )

    is SessionStatus.Unknown -> Triple(Icons.Filled.Warning, Color.Gray, "Unknown")
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
fun DetailSection(title: String, content: @Composable () -> Unit) {
  Column {
    SelectableText(
      text = title,
      style = MaterialTheme.typography.titleLarge,
      fontWeight = FontWeight.Bold,
      modifier = Modifier.padding(bottom = 8.dp)
    )
    content()
    Spacer(modifier = Modifier.height(4.dp))
  }
}

/** Pretty prints a JsonElement for display in UI */
private fun JsonElement.prettyPrint(): String =
  TrailblazeJson.defaultWithoutToolsInstance.encodeToString(JsonElement.serializer(), this)
