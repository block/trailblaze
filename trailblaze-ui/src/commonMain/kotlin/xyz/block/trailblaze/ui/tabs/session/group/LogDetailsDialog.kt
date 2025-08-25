package xyz.block.trailblaze.ui.tabs.session.group

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.ui.utils.DisplayUtils


@Composable
fun LogDetailsDialog(
  log: TrailblazeLog,
  onDismiss: () -> Unit,
) {
  LazyColumn(
    modifier = Modifier.fillMaxSize().padding(horizontal = 32.dp)
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
          text = DisplayUtils.getLogTypeDisplayName(log),
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold
        )
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
    }

    // Bottom padding
    item {
      Spacer(modifier = Modifier.height(16.dp))
    }
  }
}
