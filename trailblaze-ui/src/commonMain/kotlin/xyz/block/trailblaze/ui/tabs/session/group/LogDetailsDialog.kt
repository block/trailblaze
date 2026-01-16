package xyz.block.trailblaze.ui.tabs.session.group

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.agent.model.AgentTaskStatus
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.tabs.chat.LlmMessageComposable
import xyz.block.trailblaze.ui.utils.DisplayUtils
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatCommaNumber


@Composable
fun LogDetailsDialog(
  log: TrailblazeLog,
  sessionId: String,
  imageLoader: xyz.block.trailblaze.ui.images.ImageLoader,
  onShowScreenshotModal: (imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: xyz.block.trailblaze.api.MaestroDriverActionType?) -> Unit,
  showInspectUI: (() -> Unit)?,
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

      is TrailblazeLog.TrailblazeSnapshotLog -> {
        item {
          DeviceSnapshotFlat(
            log = log,
            sessionId = sessionId,
            imageLoader = imageLoader,
            onShowScreenshotModal = onShowScreenshotModal,
            showInspectUI = showInspectUI,
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
  // Tool descriptors are already in the log
  val toolDescriptors = log.toolOptions
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
          text = "LLM Request Details",
          style = MaterialTheme.typography.headlineSmall,
          fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onDismiss) {
          Icon(Icons.Default.ArrowBack, contentDescription = "Close")
        }
      }
    }

    // Available Tools Section
    if (toolDescriptors.isNotEmpty()) {
      item {
        var isToolsSectionExpanded by remember { mutableStateOf(false) }
        
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
          elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .padding(16.dp)
          ) {
            // Collapsible header
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { isToolsSectionExpanded = !isToolsSectionExpanded }
                .padding(vertical = 4.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = if (isToolsSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                  contentDescription = if (isToolsSectionExpanded) "Collapse" else "Expand",
                  tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                  imageVector = Icons.Default.Build,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary
                )
                Column {
                  Text(
                    text = "Available Tools (${log.toolOptions.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                  )
                  
                  // Token usage if available
                  log.llmRequestUsageAndCost?.inputTokenBreakdown?.let { breakdown ->
                    Text(
                      text = "${breakdown.toolDescriptors.count} tools using ${formatCommaNumber(breakdown.toolDescriptors.tokens)} Tokens (${
                        if (breakdown.totalEstimatedTokens > 0) 
                          "${((breakdown.toolDescriptors.tokens.toDouble() / breakdown.totalEstimatedTokens) * 100).toInt()}% of context"
                        else "0%"
                      })",
                      style = MaterialTheme.typography.bodySmall,
                      color = MaterialTheme.colorScheme.tertiary,
                      fontWeight = FontWeight.Medium
                    )
                  }
                }
              }
            }
            
            // Expanded content
            if (isToolsSectionExpanded) {
              Spacer(modifier = Modifier.height(8.dp))
              
              Text(
                text = "Actions the LLM can call to interact with the app",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
              
              Spacer(modifier = Modifier.height(12.dp))
              HorizontalDivider()
              Spacer(modifier = Modifier.height(12.dp))
            
            toolDescriptors.forEachIndexed { index, tool ->
              var isExpanded by remember { mutableStateOf(false) }

              // Estimate token count for this tool
              val toolCharCount = tool.name.length + 
                                 (tool.description?.length ?: 0) +
                                 tool.requiredParameters.sumOf { it.name.length + it.type.length + (it.description?.length ?: 0) } +
                                 tool.optionalParameters.sumOf { it.name.length + it.type.length + (it.description?.length ?: 0) } +
                                 200 // JSON overhead
              
              val totalToolChars = toolDescriptors.sumOf { t ->
                t.name.length + 
                (t.description?.length ?: 0) +
                t.requiredParameters.sumOf { it.name.length + it.type.length + (it.description?.length ?: 0) } +
                t.optionalParameters.sumOf { it.name.length + it.type.length + (it.description?.length ?: 0) } +
                200
              }
              
              val estimatedTokens = log.llmRequestUsageAndCost?.inputTokenBreakdown?.let { breakdown ->
                if (totalToolChars > 0) {
                  ((toolCharCount.toDouble() / totalToolChars) * breakdown.toolDescriptors.tokens).toLong()
                } else {
                  0L
                }
              } ?: (toolCharCount / 4) // Fallback: ~4 chars per token
              
              val percentageOfTools = log.llmRequestUsageAndCost?.inputTokenBreakdown?.let { breakdown ->
                if (breakdown.toolDescriptors.tokens > 0) {
                  (estimatedTokens.toDouble() / breakdown.toolDescriptors.tokens * 100)
                } else 0.0
              } ?: 0.0
              
              val percentageOfTotal = log.llmRequestUsageAndCost?.inputTokenBreakdown?.let { breakdown ->
                if (breakdown.totalEstimatedTokens > 0) {
                  (estimatedTokens.toDouble() / breakdown.totalEstimatedTokens * 100)
                } else 0.0
              } ?: 0.0
              
              Column(
                modifier = Modifier.fillMaxWidth()
              ) {
                // Collapsible header
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clickable { isExpanded = !isExpanded }
                    .padding(vertical = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Icon(
                      imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                      contentDescription = if (isExpanded) "Collapse" else "Expand",
                      modifier = Modifier.size(20.dp),
                      tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                      text = tool.name,
                      style = MaterialTheme.typography.bodyMedium,
                      fontWeight = FontWeight.Medium,
                      color = MaterialTheme.colorScheme.primary
                    )
                  }
                  
                  Text(
                    text = "~${formatCommaNumber(estimatedTokens)} Tokens (${percentageOfTools.toInt()}% of tools, ${percentageOfTotal.toInt()}% of total context)",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
                
                // Expanded content
                if (isExpanded) {
                  Column(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(start = 28.dp, top = 4.dp, bottom = 8.dp)
                  ) {
                    tool.description?.let { desc ->
                      Text(
                        text = desc,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface
                      )
                      Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Required parameters
                    if (tool.requiredParameters.isNotEmpty()) {
                      Text(
                        text = "Required Parameters:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.error
                      )
                      Spacer(modifier = Modifier.height(4.dp))
                      tool.requiredParameters.forEach { param ->
                        Row(
                          modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                          horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                          Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall
                          )
                          Column {
                            Text(
                              text = "${param.name}: ${param.type}",
                              style = MaterialTheme.typography.bodySmall,
                              fontWeight = FontWeight.Medium
                            )
                            param.description?.let {
                              Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                              )
                            }
                          }
                        }
                      }
                      Spacer(modifier = Modifier.height(8.dp))
                    }
                    
                    // Optional parameters
                    if (tool.optionalParameters.isNotEmpty()) {
                      Text(
                        text = "Optional Parameters:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                      )
                      Spacer(modifier = Modifier.height(4.dp))
                      tool.optionalParameters.forEach { param ->
                        Row(
                          modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                          horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                          Text(
                            text = "•",
                            style = MaterialTheme.typography.bodySmall
                          )
                          Column {
                            Text(
                              text = "${param.name}: ${param.type}",
                              style = MaterialTheme.typography.bodySmall,
                              fontWeight = FontWeight.Medium
                            )
                            param.description?.let {
                              Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                              )
                            }
                          }
                        }
                      }
                    }
                  }
                }
              }
              
              // Divider between tools
              if (index < toolDescriptors.size - 1) {
                HorizontalDivider()
              }
            }
            }
          }
        }
      }
    }

    // Chat History Section
    item {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = "Chat History",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
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
