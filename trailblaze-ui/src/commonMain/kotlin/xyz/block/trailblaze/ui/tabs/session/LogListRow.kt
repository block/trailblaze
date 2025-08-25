package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeLog
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
) {
  val data = when (log) {
    is TrailblazeLog.TrailblazeToolLog -> LogCardData(
      title = "Tool: ${log.toolName}",
      duration = log.durationMs
    )

    is TrailblazeLog.MaestroCommandLog -> LogCardData(
      title = "Maestro Command",
      duration = log.durationMs
    )

    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> LogCardData(
      title = "Agent Task Status",
      duration = log.durationMs,
    )

    is TrailblazeLog.TrailblazeLlmRequestLog -> LogCardData(
      title = "LLM Request",
      duration = log.durationMs,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
    )

    is TrailblazeLog.MaestroDriverLog -> LogCardData(
      title = "Maestro Driver",
      duration = null,
      screenshotFile = log.screenshotFile,
      deviceWidth = log.deviceWidth,
      deviceHeight = log.deviceHeight,
    )

    is TrailblazeLog.DelegatingTrailblazeToolLog -> LogCardData(
      title = "Delegating Tool",
      duration = null
    )

    is TrailblazeLog.ObjectiveStartLog -> LogCardData(
      title = "Objective Start",
      duration = null
    )

    is TrailblazeLog.ObjectiveCompleteLog -> LogCardData(
      title = "Objective Complete",
      duration = null
    )

    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> LogCardData(
      title = "Session Status",
      duration = null,
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
          SelectableText(text = data.title, fontWeight = FontWeight.Bold)
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
          }
        }
      }
      TextButton(onClick = { showDetails?.invoke() }) { Text("Details") }
    }

    // Show objective text for ObjectiveStartLog
    if (log is TrailblazeLog.ObjectiveStartLog) {
      SelectableText(
        text = log.promptStep.step,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = ((logIndentLevel(log) * 16) + 26).dp) // Align with the text above
          .padding(end = 16.dp)
          .padding(bottom = 8.dp)
      )
    }

    // Show objective text for ObjectiveCompleteLog
    if (log is TrailblazeLog.ObjectiveCompleteLog) {
      SelectableText(
        text = log.promptStep.step,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
        modifier = Modifier
          .fillMaxWidth()
          .padding(start = ((logIndentLevel(log) * 16) + 26).dp) // Align with the text above
          .padding(end = 16.dp)
          .padding(bottom = 8.dp)
      )
    }
  }
}
