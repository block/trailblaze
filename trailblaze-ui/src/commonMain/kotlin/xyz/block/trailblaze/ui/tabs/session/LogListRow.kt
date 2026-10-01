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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
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
import com.mikepenz.markdown.m3.Markdown
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

private fun getSessionStatusIconAndColor(status: SessionStatus): Triple<ImageVector, Color, String> {
  return when (status) {
    is SessionStatus.Started -> Triple(Icons.Filled.PlayArrow, Color(0xFF2196F3), "Started")
    is SessionStatus.Ended.Succeeded -> Triple(
      Icons.Filled.CheckCircle, Color(0xFF28A745), "Succeeded"
    )

    is SessionStatus.Ended.Failed -> Triple(Icons.Filled.Close, Color(0xFFDC3545), "Failed")
    is SessionStatus.Ended.Cancelled -> Triple(Icons.Filled.Close, Color(0xFFFFC107), "Cancelled")
    is SessionStatus.Ended.SucceededWithSelfHeal -> Triple(
      Icons.Filled.Warning, Color(0xFF28A745), "Succeeded with Self-Heal"
    )

    is SessionStatus.Ended.FailedWithSelfHeal -> Triple(
      Icons.Filled.Warning, Color(0xFFDC3545), "Failed with Self-Heal"
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
