package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.isInProgress
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.openVideoInSystemPlayer
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

private fun logSummary(log: TrailblazeLog): Pair<String, String?> =
  when (log) {
    is TrailblazeLog.TrailblazeToolLog ->
      "Tool: ${log.toolName}" to if (log.successful) "succeeded (${log.durationMs}ms)" else "failed"
    is TrailblazeLog.AgentDriverLog -> {
      val actionDesc =
        when (val a = log.action) {
          is AgentDriverAction.TapPoint -> "Tap (${a.x}, ${a.y})"
          is AgentDriverAction.Swipe -> "Swipe ${a.direction}"
          is AgentDriverAction.EnterText ->
            "Input: ${a.text}" + if (a.hideKeyboardAfter) " (then hide keyboard)" else ""
          is AgentDriverAction.AssertCondition -> "Assert: ${a.conditionDescription}"
          is AgentDriverAction.LaunchApp -> "Launch: ${a.appId}"
          is AgentDriverAction.Scroll -> "Scroll ${if (a.forward) "down" else "up"}"
          is AgentDriverAction.LongPressPoint -> "Long press (${a.x}, ${a.y})"
          is AgentDriverAction.BackPress -> "Back"
          is AgentDriverAction.PressHome -> "Home"
          is AgentDriverAction.HideKeyboard -> "Hide keyboard"
          is AgentDriverAction.EraseText -> "Erase ${a.characters} chars"
          is AgentDriverAction.WaitForSettle -> "Wait for settle"
          else -> log.action?.toString() ?: "Driver action"
        }
      "Driver" to "$actionDesc (${log.durationMs}ms)"
    }
    is TrailblazeLog.TrailblazeLlmRequestLog -> "LLM Request" to "${log.durationMs}ms"
    is TrailblazeLog.ObjectiveStartLog -> "Step started" to log.promptStep.prompt
    is TrailblazeLog.ObjectiveCompleteLog -> "Step completed" to log.promptStep.prompt
    is TrailblazeLog.TrailblazeSessionStatusChangeLog -> "Session" to log.sessionStatus.toString()
    else -> log::class.simpleName.orEmpty() to null
  }

private val PLAYBACK_SPEEDS = listOf(0.25f, 0.5f, 1f, 1.5f, 2f, 4f)

@Composable
internal fun VideoPlaybackControls(
  isPlaying: Boolean,
  onPlayPauseClick: () -> Unit,
  currentPositionMs: Long,
  durationMs: Long,
  playbackSpeed: Float = 4f,
  onSpeedChange: ((Float) -> Unit)? = null,
) {
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier.padding(vertical = 4.dp),
  ) {
    IconButton(
      onClick = onPlayPauseClick,
      modifier =
        Modifier.size(40.dp)
          .background(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            androidx.compose.foundation.shape.CircleShape,
          ),
    ) {
      Icon(
        imageVector = if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
        contentDescription = if (isPlaying) "Pause" else "Play",
        modifier = Modifier.size(28.dp),
        tint = MaterialTheme.colorScheme.primary,
      )
    }
    Text(
      text =
        "${formatDuration(currentPositionMs.coerceAtLeast(0L))} / ${formatDuration(durationMs)}",
      style = MaterialTheme.typography.labelMedium,
      fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
      color = MaterialTheme.colorScheme.onSurface,
      fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
      modifier = Modifier.widthIn(min = 100.dp),
    )
    if (onSpeedChange != null) {
      val speedLabel = if (playbackSpeed == playbackSpeed.toLong().toFloat()) {
        "${playbackSpeed.toLong()}x"
      } else "${playbackSpeed}x"
      Text(
        text = speedLabel,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        modifier = Modifier
          .clickable {
            val idx = PLAYBACK_SPEEDS.indexOf(playbackSpeed)
            val next = PLAYBACK_SPEEDS[(idx + 1) % PLAYBACK_SPEEDS.size]
            onSpeedChange(next)
          }
          .background(
            MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
            RoundedCornerShape(4.dp),
          )
          .padding(horizontal = 6.dp, vertical = 2.dp),
      )
    }
  }
}

