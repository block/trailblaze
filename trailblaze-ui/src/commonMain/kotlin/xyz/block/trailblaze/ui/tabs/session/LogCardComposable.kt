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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeLog
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
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?) -> Unit = { _, _, _, _, _ -> },
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
      title = "LLM Request",
      duration = log.durationMs,
      elapsedTime = elapsedTimeMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
    )

    is TrailblazeLog.MaestroDriverLog -> LogCardData(
      title = "Maestro Driver ${log.action.type.name}",
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
      preformattedText = log.promptStep.prompt
    )

    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> LogCardData(
      title = "Session Status",
      duration = null,
      elapsedTime = elapsedTimeMs,
      preformattedText = log.sessionStatus.toString()
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

      Column(
        modifier = Modifier.padding(12.dp)
      ) {

        Spacer(modifier = Modifier.height(4.dp))

        logData.preformattedText?.let { preformattedText ->
          CodeBlock(
            text = preformattedText
          )
        }

        // Add screenshot if available
        if (logData.screenshotFile != null && logData.deviceWidth != null && logData.deviceHeight != null) {
          Spacer(modifier = Modifier.height(8.dp))
          // Determine click coordinates for MaestroDriverLog TapPoint/LongPressPoint
          val (clickX, clickY) =
            if (log is TrailblazeLog.MaestroDriverLog) {
              val action = log.action
              if (action is HasClickCoordinates) action.x to action.y else null to null
            } else null to null
          ScreenshotImage(
            sessionId = sessionId,
            screenshotFile = logData.screenshotFile,
            deviceWidth = logData.deviceWidth,
            deviceHeight = logData.deviceHeight,
            clickX = clickX,
            clickY = clickY,
            modifier = Modifier.fillMaxWidth(),
            imageLoader = imageLoader,
            forceHighQuality = cardSize != null,
            onImageClick = { imageModel, deviceWidth, deviceHeight, clickXCoord, clickYCoord ->
              onShowScreenshotModal(imageModel, deviceWidth, deviceHeight, clickXCoord, clickYCoord)
            }
          )
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
            // Add Inspect UI button for LLM Request logs (left of Info icon)
            if (log is TrailblazeLog.TrailblazeLlmRequestLog && showInspectUI != null) {
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

            // Info icon always on the right
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
          }
        }
      }
    }
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