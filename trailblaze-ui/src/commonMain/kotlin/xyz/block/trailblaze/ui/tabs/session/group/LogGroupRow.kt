package xyz.block.trailblaze.ui.tabs.session.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.tabs.session.LogCard
import xyz.block.trailblaze.ui.tabs.session.models.GroupedLog
import xyz.block.trailblaze.ui.theme.isDarkTheme
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration
import xyz.block.trailblaze.ui.utils.LogUtils.logIndentLevel
import xyz.block.trailblaze.api.MaestroDriverActionType
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LogGroupRow(
  group: GroupedLog.Group,
  sessionId: String,
  sessionStartTime: Instant,
  toMaestroYaml: (JsonObject) -> String,
  toTrailblazeYaml: (toolName: String, trailblazeTool: TrailblazeTool) -> String,
  imageLoader: ImageLoader = NetworkImageLoader(),
  cardSize: androidx.compose.ui.unit.Dp? = null,
  showDetails: ((TrailblazeLog) -> Unit)? = null,
  showInspectUI: ((TrailblazeLog) -> Unit)? = null,
  showChatHistory: ((TrailblazeLog) -> Unit)? = null,
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: MaestroDriverActionType?) -> Unit = { _, _, _, _, _, _ -> },
  onOpenInFinder: ((TrailblazeLog) -> Unit)? = null,
) {
  val firstLog = group.logs.first()
  val indent = logIndentLevel(firstLog)

  // Calculate elapsed time from session start to this group
  val elapsedMs = group.timestamp.toEpochMilliseconds() - sessionStartTime.toEpochMilliseconds()
  val elapsedSeconds = elapsedMs / 1000.0
  val borderColor = if (isDarkTheme()) Color(0xFF444444) else Color(0xFFE1E4E8)

  // Create a card that spans the full width for the group
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .padding(vertical = 6.dp)
      .padding(start = (indent * 16).dp).border(
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, borderColor),),
    colors = CardDefaults.cardColors(
      containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
    )
  ) {

    Column(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 24.dp)) {
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
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(end = 8.dp) // Extra padding to prevent spillover
      ) {
        group.logs.forEach { log ->
          Box(
            modifier = Modifier
              .width(cardSize ?: 160.dp)
          ) {
            LogCard(
              log = log,
              sessionId = sessionId,
              sessionStartTime = sessionStartTime,
              toMaestroYaml = toMaestroYaml,
              toTrailblazeYaml = toTrailblazeYaml,
              imageLoader = imageLoader,
              cardSize = cardSize,
              showDetails = { showDetails?.invoke(log) },
              showInspectUI = { showInspectUI?.invoke(log) },
              showChatHistory = { showChatHistory?.invoke(log) },
              onShowScreenshotModal = onShowScreenshotModal,
              onOpenInFinder = { onOpenInFinder?.invoke(log) }
            )
          }
        }
      }
    }
  }
}

