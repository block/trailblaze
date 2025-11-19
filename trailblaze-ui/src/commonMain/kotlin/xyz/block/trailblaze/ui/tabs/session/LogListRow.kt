package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasPromptStep
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.models.LogCardData
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration
import xyz.block.trailblaze.ui.utils.LogUtils.logIndentLevel

@Composable
fun LogListRow(
  log: TrailblazeLog,
  sessionId: String,
  sessionStartTime: Instant,
  imageLoader: ImageLoader = NetworkImageLoader(),
  showDetails: (() -> Unit)? = null,
  showInspectUI: (() -> Unit)? = null,
  showChatHistory: (() -> Unit)? = null,
  cardSize: androidx.compose.ui.unit.Dp? = null,
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: xyz.block.trailblaze.api.MaestroDriverActionType?) -> Unit = { _, _, _, _, _, _ -> },
  onOpenInFinder: (() -> Unit)? = null,
) {
  val elapsedTimeMs = log.timestamp.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()

  val data = when (log) {
    is TrailblazeLog.TrailblazeToolLog -> LogCardData(
      title = "Tool: ${log.toolName}",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs
    )

    is TrailblazeLog.MaestroCommandLog -> LogCardData(
      title = "Maestro Command",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs
    )

    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> LogCardData(
      title = "Agent Task Status",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs
    )

    is TrailblazeLog.TrailblazeLlmRequestLog -> LogCardData(
      title = "LLM Request",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight
    )

    is TrailblazeLog.MaestroDriverLog -> LogCardData(
      title = "Maestro Driver",
      duration = null,
      elapsedTime = elapsedTimeMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight
    )

    is TrailblazeLog.DelegatingTrailblazeToolLog -> LogCardData(
      title = "Delegating Tool",
      duration = null,
      elapsedTime = elapsedTimeMs
    )

    is TrailblazeLog.ObjectiveStartLog -> LogCardData(
      title = "Objective Start",
      duration = null,
      elapsedTime = elapsedTimeMs
    )

    is TrailblazeLog.ObjectiveCompleteLog -> LogCardData(
      title = "Objective Complete",
      duration = null,
      elapsedTime = elapsedTimeMs
    )

    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> LogCardData(
      title = "Session Status",
      duration = null,
      elapsedTime = elapsedTimeMs
    )

    is TrailblazeLog.AttemptAiFallbackLog -> LogCardData(
      title = "Attempt AI Fallback",
      duration = null,
      elapsedTime = elapsedTimeMs
    )
  }

  val elapsedMs = log.timestamp.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()
  val elapsedSeconds = elapsedMs / 1000.0

  Column {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(vertical = 6.dp)
        .padding(start = (logIndentLevel(log) * 16).dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
          modifier = Modifier.size(10.dp).clip(RoundedCornerShape(50))
            .background(
              Color(0xFF0F5132) // Dark green for success, dark red for failure
            )
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
          Row(verticalAlignment = Alignment.CenterVertically) {
            if (log is TrailblazeLog.ObjectiveCompleteLog) {
              val isSuccess = log.objectiveResult is AgentTaskStatus.Success.ObjectiveComplete
              Icon(
                imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Close,
                contentDescription = if (isSuccess) "Success" else "Failed",
                tint = if (isSuccess) Color(0xFF28A745) else Color(0xFFDC3545),
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
            }
            if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
              val (icon, color, label) = getSessionStatusIconAndColor(log.sessionStatus)
              Icon(
                imageVector = icon,
                contentDescription = label,
                tint = color,
                modifier = Modifier.size(18.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
            }
            SelectableText(text = data.title, fontWeight = FontWeight.Bold)
          }
          Row(verticalAlignment = Alignment.CenterVertically) {
            SelectableText(
              text = "Elapsed: ${formatDuration((elapsedSeconds * 1000).toLong())}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )
            data.duration?.let {
              SelectableText(
                text = " (${formatDuration(it)})",
                style = MaterialTheme.typography.bodySmall
              )
            }
            if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
              val statusLabel = getSessionStatusLabel(log.sessionStatus)
              SelectableText(
                text = " - $statusLabel",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
              )
            }
          }
        }
      }
      // Only show action buttons on the right if there's no screenshot card below
      if (data.screenshotFile == null || data.deviceWidth == null || data.deviceHeight == null) {
        Row {
          TextButton(onClick = { showDetails?.invoke() }) { Text("Details") }
          if (showInspectUI != null) {
            IconButton(onClick = { showInspectUI.invoke() }) {
              Icon(
                imageVector = Icons.Filled.Search,
                contentDescription = "Inspect UI",
                modifier = Modifier.size(18.dp)
              )
            }
          }
          if (onOpenInFinder != null) {
            IconButton(onClick = { onOpenInFinder.invoke() }) {
              Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = "Open in Finder",
                modifier = Modifier.size(18.dp)
              )
            }
          }
        }
      }
    }

    if (log is HasPromptStep) {
      SelectableText(
        text = log.promptStep.prompt,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        fontWeight = FontWeight.Bold,
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = ((logIndentLevel(log) * 16) + 26).dp) // Align with the text above
          .padding(end = 16.dp)
          .padding(bottom = 8.dp)
      )
    }

    if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
      val failureMessage = when (val status = log.sessionStatus) {
        is SessionStatus.Ended.Failed -> status.exceptionMessage
        is SessionStatus.Ended.FailedWithFallback -> status.exceptionMessage
        else -> null
      }

      val cancellationMessage = when (val status = log.sessionStatus) {
        is SessionStatus.Ended.Cancelled -> status.cancellationMessage
        else -> null
      }

      if (failureMessage != null) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((logIndentLevel(log) * 16) + 26).dp)
            .padding(end = 16.dp)
            .padding(bottom = 8.dp)
        ) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .border(
                width = 1.dp,
                color = Color(0xFFDC3545).copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
              ),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(0.dp),
            colors = CardDefaults.cardColors(
              containerColor = Color(0xFFDC3545).copy(alpha = 0.08f)
            )
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Filled.Close,
                  contentDescription = "Failed",
                  tint = Color(0xFFDC3545),
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SelectableText(
                  text = "Failure Reason:",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFDC3545)
                )
              }
              Spacer(modifier = Modifier.size(5.dp))
              SelectableText(
                text = failureMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
      }

      if (cancellationMessage != null) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .padding(start = ((logIndentLevel(log) * 16) + 26).dp)
            .padding(end = 16.dp)
            .padding(bottom = 8.dp)
        ) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .border(
                width = 1.dp,
                color = Color(0xFFFFC107).copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
              ),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(0.dp),
            colors = CardDefaults.cardColors(
              containerColor = Color(0xFFFFC107).copy(alpha = 0.08f)
            )
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = Icons.Filled.Close,
                  contentDescription = "Cancelled",
                  tint = Color(0xFFFFC107),
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SelectableText(
                  text = "Cancellation Reason:",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = Color(0xFFFFC107)
                )
              }
              Spacer(modifier = Modifier.size(5.dp))
              SelectableText(
                text = cancellationMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
      }
    }

    if (log is TrailblazeLog.ObjectiveCompleteLog) {
      val llmExplanation = when (val result = log.objectiveResult) {
        is AgentTaskStatus.Success.ObjectiveComplete -> result.llmExplanation
        is AgentTaskStatus.Failure.ObjectiveFailed -> result.llmExplanation
        else -> null
      }

      if (llmExplanation != null) {
        val isSuccess = log.objectiveResult is AgentTaskStatus.Success.ObjectiveComplete
        Box(
          modifier = Modifier
            .padding(start = ((logIndentLevel(log) * 16) + 26).dp)
            .padding(end = 16.dp)
            .padding(bottom = 8.dp)
            .width(cardSize ?: 420.dp)
        ) {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .border(
                width = 1.dp,
                color = if (isSuccess)
                  Color(0xFF28A745).copy(alpha = 0.3f)
                else
                  Color(0xFFDC3545).copy(alpha = 0.3f),
                shape = RoundedCornerShape(8.dp)
              ),
            shape = RoundedCornerShape(8.dp),
            elevation = CardDefaults.cardElevation(0.dp),
            colors = CardDefaults.cardColors(
              containerColor = if (isSuccess)
                Color(0xFF28A745).copy(alpha = 0.08f)
              else
                Color(0xFFDC3545).copy(alpha = 0.08f)
            )
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                  imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Close,
                  contentDescription = if (isSuccess) "Success" else "Failed",
                  tint = if (isSuccess) Color(0xFF28A745) else Color(0xFFDC3545),
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                SelectableText(
                  text = "LLM Explanation:",
                  style = MaterialTheme.typography.labelMedium,
                  fontWeight = FontWeight.Bold,
                  color = if (isSuccess) Color(0xFF28A745) else Color(0xFFDC3545)
                )
              }
              Spacer(modifier = Modifier.size(5.dp))
              SelectableText(
                text = llmExplanation,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
            }
          }
        }
      }
    }

    if (data.screenshotFile != null && data.deviceWidth != null && data.deviceHeight != null) {
      Box(
        modifier = Modifier
          .padding(start = ((logIndentLevel(log) * 16) + 26).dp)
          .padding(end = 16.dp)
          .padding(bottom = 8.dp)
          .width(cardSize ?: 420.dp)
      ) {
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .border(
              width = 1.dp,
              color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
              shape = RoundedCornerShape(8.dp)
            ),
          shape = RoundedCornerShape(8.dp),
          elevation = CardDefaults.cardElevation(6.dp),
          colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
          )
        ) {
          Column {
            // Colored header bar matching log type
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .background(xyz.block.trailblaze.ui.utils.ColorUtils.getLogTypeColor(log))
            )

            // Colored title background
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .background(xyz.block.trailblaze.ui.utils.ColorUtils.getLogTypeColor(log))
                .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
              SelectableText(
                text = buildString {
                  append(data.title)
                  data.duration?.let { duration ->
                    append(" (${formatDuration(duration)})")
                  }
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = Color.Black
              )
            }

            Column(modifier = Modifier.padding(12.dp)) {
              // Determine click coordinates for MaestroDriverLog TapPoint/LongPressPoint
              val (clickX, clickY) =
                if (log is TrailblazeLog.MaestroDriverLog) {
                  val action = log.action
                  if (action is HasClickCoordinates) action.x to action.y else null to null
                } else null to null

              // Extract action for screenshot overlay
              val action = if (log is TrailblazeLog.MaestroDriverLog) log.action else null

              ScreenshotImage(
                sessionId = sessionId,
                screenshotFile = data.screenshotFile,
                deviceWidth = data.deviceWidth,
                deviceHeight = data.deviceHeight,
                clickX = clickX,
                clickY = clickY,
                action = action,
                modifier = Modifier.fillMaxWidth(),
                imageLoader = imageLoader,
                forceHighQuality = cardSize != null,
                onImageClick = { imageModel, deviceWidth, deviceHeight, clickXCoord, clickYCoord ->
                  // For LLM request logs, clicking should open UI Inspector if available
                  if (log is TrailblazeLog.TrailblazeLlmRequestLog && showInspectUI != null) {
                    showInspectUI.invoke()
                  } else {
                    // For other logs, open the screenshot modal
                    onShowScreenshotModal(
                      imageModel,
                      deviceWidth,
                      deviceHeight,
                      clickXCoord,
                      clickYCoord,
                      action
                    )
                  }
                }
              )

              Spacer(modifier = Modifier.height(8.dp))

              // Action buttons below screenshot
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                SelectableText(
                  text = "Elapsed: ${formatDuration(data.elapsedTime)}",
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row {
                  // Add Inspect UI button for logs with view hierarchy
                  if (showInspectUI != null) {
                    IconButton(
                      onClick = { showInspectUI.invoke() },
                      modifier = Modifier.size(24.dp)
                    ) {
                      Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Inspect UI",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                      )
                    }
                  }

                  // Chat history button for LLM Request logs
                  if (log is TrailblazeLog.TrailblazeLlmRequestLog && showChatHistory != null) {
                    IconButton(
                      onClick = { showChatHistory.invoke() },
                      modifier = Modifier.size(24.dp)
                    ) {
                      Icon(
                        imageVector = Icons.AutoMirrored.Filled.Chat,
                        contentDescription = "Chat History",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
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
      third = "Max LLM Calls Limit",
    )
  }
}

private fun getSessionStatusLabel(status: SessionStatus): String {
  return when (status) {
    is SessionStatus.Started -> "Session Started"
    is SessionStatus.Ended.Succeeded -> "Session Succeeded"
    is SessionStatus.Ended.Failed -> "Session Failed"
    is SessionStatus.Ended.Cancelled -> "Session Cancelled"
    is SessionStatus.Ended.SucceededWithFallback -> "Session Succeeded (with AI Fallback)"
    is SessionStatus.Ended.FailedWithFallback -> "Session Failed (with AI Fallback)"
    is SessionStatus.Unknown -> "Unknown Status"
    is SessionStatus.Ended.TimeoutReached -> "Timeout Reached"
    is SessionStatus.Ended.MaxCallsLimitReached -> "Max LLM Calls Limit Reached"
  }
}
