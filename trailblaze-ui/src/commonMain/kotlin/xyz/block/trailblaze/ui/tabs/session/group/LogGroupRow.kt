package xyz.block.trailblaze.ui.tabs.session.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.models.GroupedLog
import xyz.block.trailblaze.ui.utils.DisplayUtils
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration
import xyz.block.trailblaze.ui.utils.LogUtils.logIndentLevel
import androidx.compose.foundation.lazy.grid.items as gridItems

@Composable
fun LogGroupRow(
  group: GroupedLog.Group,
  sessionId: String,
  sessionStartTime: Instant,
  imageLoader: ImageLoader = NetworkImageLoader(),
  showDetails: ((TrailblazeLog) -> Unit)? = null,
  showInspectUI: ((TrailblazeLog) -> Unit)? = null,
) {
  val firstLog = group.logs.first()
  val indent = logIndentLevel(firstLog)

  // Calculate elapsed time from session start to this group
  val elapsedMs = group.timestamp.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()
  val elapsedSeconds = elapsedMs / 1000.0

  // Create a card that spans the full width for the group
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .padding(start = (indent * 16).dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    )
  ) {
    Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 12.dp)) {
      // Group header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
      ) {
        SelectableText(
          text = "Elapsed: ${formatDuration((elapsedSeconds * 1000).toLong())}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      // Grid of log cards that wraps to multiple rows if overflowed
      LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = 160.dp, max = 500.dp),
        userScrollEnabled = false // disables inner grid scrolling, so outer list scrolls
      ) {
        gridItems(group.logs) { log ->
          GroupLogCard(
            log = log,
            sessionId = sessionId,
            imageLoader = imageLoader,
            showDetails = { showDetails?.invoke(log) },
            showInspectUI = { showInspectUI?.invoke(log) },
          )
        }
      }
      // Horizontal divider below the group for separation
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(1.dp)
          .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.13f))
      )
    }
  }
}


@Composable
fun GroupLogCard(
  log: TrailblazeLog,
  sessionId: String,
  imageLoader: ImageLoader = NetworkImageLoader(),
  showDetails: (() -> Unit)? = null,
  showInspectUI: (() -> Unit)? = null,
) {
  // Always use the static log type display name as the label
  val label = DisplayUtils.getLogTypeDisplayName(log)

  // Optional dynamic subtitle (e.g., prompt for LLM Request or Maestro Driver action type)
  val subtitle: String? = when (log) {
    is TrailblazeLog.MaestroDriverLog -> log.action::class.simpleName
    is TrailblazeLog.TrailblazeLlmRequestLog -> null // Or: log.instructions.take(24) if you wish
    is TrailblazeLog.TrailblazeToolLog -> log.toolName.takeIf { it.isNotBlank() }
    else -> null
  }

  val screenshotFile = when (log) {
    is TrailblazeLog.TrailblazeLlmRequestLog -> log.screenshotFile
    is TrailblazeLog.MaestroDriverLog -> log.screenshotFile
    else -> null
  }
  val deviceWidth = when (log) {
    is TrailblazeLog.TrailblazeLlmRequestLog -> log.deviceWidth
    is TrailblazeLog.MaestroDriverLog -> log.deviceWidth
    else -> null
  }
  val deviceHeight = when (log) {
    is TrailblazeLog.TrailblazeLlmRequestLog -> log.deviceHeight
    is TrailblazeLog.MaestroDriverLog -> log.deviceHeight
    else -> null
  }
  val duration = when (log) {
    is TrailblazeLog.TrailblazeToolLog -> log.durationMs
    is TrailblazeLog.MaestroCommandLog -> log.durationMs
    is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> log.durationMs
    is TrailblazeLog.TrailblazeLlmRequestLog -> log.durationMs
    is TrailblazeLog.MaestroDriverLog -> log.durationMs
    else -> null
  }

  Card(
    modifier = Modifier
      .width(200.dp)
      .height(if (screenshotFile != null) 135.dp else 110.dp),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surface
    ),
    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
  ) {
    Column(
      modifier = Modifier.padding(8.dp).fillMaxSize(),
    ) {
      // Label at the top (always)
      Text(
        text = label,
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.Bold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth()
      )

      // Subtitle for MaestroDriverLog/action type or other desired content
      if (subtitle != null) {
        Text(
          text = subtitle,
          style = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.primary.copy(alpha = 0.8f)),
          fontWeight = FontWeight.Normal,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.fillMaxWidth()
        )
      } else {
        Spacer(modifier = Modifier.height(2.dp))
      }

      // Screenshot section
      val hasScreenshot = screenshotFile != null && deviceWidth != null && deviceHeight != null
      if (hasScreenshot) {
        val (clickX, clickY) =
          if (log is TrailblazeLog.MaestroDriverLog) {
            val action = log.action
            if (action is HasClickCoordinates) action.x to action.y else null to null
          } else null to null
        ScreenshotImage(
          sessionId = sessionId,
          screenshotFile = screenshotFile,
          deviceWidth = deviceWidth,
          deviceHeight = deviceHeight,
          clickX = clickX,
          clickY = clickY,
          modifier = Modifier
            .fillMaxWidth()
            .height(60.dp),
          imageLoader = imageLoader
        )
        Spacer(modifier = Modifier.height(2.dp))
      } else {
        Spacer(modifier = Modifier.height(6.dp))
      }

      // Duration at the bottom
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        duration?.let {
          Text(
            text = formatDuration(it),
            style = MaterialTheme.typography.bodySmall.copy(
              color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            ),
            modifier = Modifier.fillMaxWidth(0.8f),
            maxLines = 1
          )
        }
        Button(
          onClick = { showDetails?.invoke() },
          modifier = Modifier
            .padding(start = 4.dp)
            .fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.secondary
          ),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Info,
            contentDescription = "Details",
            modifier = Modifier.size(18.dp)
          )
        }
      }

      // Add Inspect UI button for LLM Request logs
      if (log is TrailblazeLog.TrailblazeLlmRequestLog && showInspectUI != null) {
        Spacer(modifier = Modifier.height(4.dp))
        Button(
          onClick = { showInspectUI.invoke() },
          modifier = Modifier.fillMaxWidth(),
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.tertiary
          ),
          contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
        ) {
          Text("Inspect UI")
        }
      }
    }
  }
}
