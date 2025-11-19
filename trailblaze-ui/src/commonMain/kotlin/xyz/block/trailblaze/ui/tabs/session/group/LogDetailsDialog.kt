package xyz.block.trailblaze.ui.tabs.session.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.tabs.chat.LlmMessageComposable
import xyz.block.trailblaze.ui.utils.DisplayUtils


@Composable
fun LogDetailsDialog(
  log: TrailblazeLog,
  onDismiss: () -> Unit,
) {
  val lazyListState = rememberLazyListState()
  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
    state = lazyListState
  ) {
    // Header item
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          // Add success/failure icon for ObjectiveCompleteLog
          if (log is TrailblazeLog.ObjectiveCompleteLog) {
            val isSuccess = log.objectiveResult is AgentTaskStatus.Success.ObjectiveComplete
            Icon(
              imageVector = if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Close,
              contentDescription = if (isSuccess) "Success" else "Failed",
              tint = if (isSuccess) Color(0xFF28A745) else Color(0xFFDC3545),
              modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          // Add icon for session status changes
          if (log is TrailblazeLog.TrailblazeSessionStatusChangeLog) {
            val (icon, color, label) = getSessionStatusIconAndColor(log.sessionStatus)
            Icon(
              imageVector = icon,
              contentDescription = label,
              tint = color,
              modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
          }
          Text(
            text = DisplayUtils.getLogTypeDisplayName(log),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
          )
        }
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.ArrowBack, contentDescription = "Close")
        }
      }
    }

    // Flatten all the content directly into LazyColumn items
    when (log) {
      is TrailblazeLog.TrailblazeLlmRequestLog -> {
        item {
          LlmRequestDetailsFlat(log)
        }
      }

      is TrailblazeLog.MaestroDriverLog -> {
        item {
          MaestroDriverDetailsFlat(log)
        }
      }

      is TrailblazeLog.TrailblazeToolLog -> {
        item {
          TrailblazeCommandDetailsFlat(log)
        }
      }

      is TrailblazeLog.DelegatingTrailblazeToolLog -> {
        item {
          DelegatingTrailblazeToolDetailsFlat(log)
        }
      }

      is TrailblazeLog.MaestroCommandLog -> {
        item {
          MaestroCommandDetailsFlat(log)
        }
      }

      is TrailblazeLog.TrailblazeAgentTaskStatusChangeLog -> {
        item {
          AgentTaskStatusDetailsFlat(log)
        }
      }

      is TrailblazeLog.TrailblazeSessionStatusChangeLog -> {
        item {
          SessionStatusDetailsFlat(log)
        }
      }

      is TrailblazeLog.ObjectiveStartLog -> {
        item {
          ObjectiveStartDetailsFlat(log)
        }
      }

      is TrailblazeLog.ObjectiveCompleteLog -> {
        item {
          ObjectiveCompleteDetailsFlat(log)
        }
      }

      is TrailblazeLog.AttemptAiFallbackLog -> {
        item {
          AttemptAiFallbackFlat(
            log = log,
          )
        }
      }
    }

    // Bottom padding
    item {
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}

@Composable
fun ChatHistoryDialog(
  log: TrailblazeLog.TrailblazeLlmRequestLog,
  onDismiss: () -> Unit,
) {
  val lazyListState = rememberLazyListState()
  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp),
    state = lazyListState
  ) {
    // Header item
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Chat History",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.ArrowBack, contentDescription = "Close")
        }
      }
    }

    // Chat messages
    if (log.llmMessages.isNotEmpty()) {
      items(log.llmMessages.size) { index ->
        LlmMessageComposable(log.llmMessages[index])
      }
    } else {
      item {
        Text(
          text = "No chat history available.",
          style = MaterialTheme.typography.bodyMedium,
          modifier = Modifier.padding(16.dp)
        )
      }
    }

    // Bottom padding
    item {
      Spacer(modifier = Modifier.height(16.dp))
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
