package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.ui.utils.FormattingUtils
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

// -- Step-grouped hierarchy components --

/** Collapsible header for an objective step in the combined view. */
@Composable
internal fun CombinedObjectiveHeader(
  stepNumber: Int,
  objective: ObjectiveProgress,
  isExpanded: Boolean,
  isActive: Boolean,
  onToggle: () -> Unit,
  onClick: () -> Unit,
) {
  val isPending = objective.status == ObjectiveStatus.Pending
  val statusColor =
    when (objective.status) {
      ObjectiveStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
      ObjectiveStatus.InProgress -> MaterialTheme.colorScheme.primary
      ObjectiveStatus.Succeeded -> SessionProgressColors.succeeded
      ObjectiveStatus.Failed -> MaterialTheme.colorScheme.error
    }
  val bgColor =
    if (isActive) statusColor.copy(alpha = 0.08f) else Color.Transparent

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable {
          onClick()
          onToggle()
        }
        .background(bgColor)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    // Step number + status icon
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      Text(
        text = "$stepNumber",
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.Bold,
        color = statusColor,
      )
      when (objective.status) {
        ObjectiveStatus.Pending -> Unit
        ObjectiveStatus.InProgress -> {
          CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = statusColor,
            strokeWidth = 2.dp,
          )
        }
        ObjectiveStatus.Succeeded -> {
          Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Passed",
            tint = statusColor,
            modifier = Modifier.size(16.dp),
          )
        }
        ObjectiveStatus.Failed -> {
          Icon(
            imageVector = Icons.Filled.Cancel,
            contentDescription = "Failed",
            tint = statusColor,
            modifier = Modifier.size(16.dp),
          )
        }
      }
    }

    // Prompt text + subtitle
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text(
        text = promptSummary(objective.prompt, maxLength = 160),
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (isPending) FontWeight.Normal else FontWeight.SemiBold,
        color =
          if (isPending) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
          else Color.Unspecified,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      if (!isPending) {
        val duration = objectiveDurationMs(objective)
        val toolCount = objective.toolCallCount
        val parts = mutableListOf<String>()
        parts.add(objective.status.label)
        if (toolCount > 0) parts.add("$toolCount tool${if (toolCount != 1) "s" else ""}")
        if (duration != null) parts.add(formatDuration(duration))
        Text(
          text = parts.joinToString(" \u2022 "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }

    // Expand/collapse chevron (hidden for pending objectives)
    if (!isPending) {
      Icon(
        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (isExpanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }
  }
}

/** Collapsible header for a tool block (tools outside any step). */
@Composable
internal fun CombinedToolBlockHeader(
  toolBlock: ProgressItem.ToolBlockItem,
  isExpanded: Boolean,
  isActive: Boolean,
  onToggle: () -> Unit,
  onClick: () -> Unit,
) {
  val bgColor =
    if (isActive) MaterialTheme.colorScheme.primary.copy(alpha = 0.06f) else Color.Transparent

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable {
          onClick()
          onToggle()
        }
        .background(bgColor)
        .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Icon(
      imageVector = Icons.Filled.Build,
      contentDescription = "Tools",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      val toolCount = toolBlock.toolLogs.size
      Text(
        text = "Tools",
        style = MaterialTheme.typography.bodySmall,
        fontWeight = FontWeight.SemiBold,
      )
      val durationMs = toolBlock.let {
        val start = it.startedAt?.toEpochMilliseconds()
        val end = it.completedAt?.toEpochMilliseconds()
        if (start != null && end != null) end - start else null
      }
      val subtitle = buildList {
        add("$toolCount tool${if (toolCount != 1) "s" else ""}")
        if (durationMs != null) add(formatDuration(durationMs))
      }.joinToString(" \u2022 ")
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

    Icon(
      imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
      contentDescription = if (isExpanded) "Collapse" else "Expand",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(18.dp),
    )
  }
}

// -- Child event row --

/** A single child event row within an expanded step or tool block. */
@Composable
internal fun CombinedChildEventRow(
  event: CombinedEvent,
  isActive: Boolean,
  onClick: () -> Unit,
  onShowInspectUI: ((TrailblazeLog) -> Unit)? = null,
  onShowChatHistory: ((TrailblazeLog.TrailblazeLlmRequestLog) -> Unit)? = null,
) {
  val typeColor =
    when (event.type) {
      CombinedEventType.Objective -> MaterialTheme.colorScheme.primary
      CombinedEventType.DriverAction -> SessionProgressColors.markerTap
      CombinedEventType.ToolCall -> SessionProgressColors.markerTool
      CombinedEventType.LlmRequest -> SessionProgressColors.llmTick
      CombinedEventType.Screenshot -> SessionProgressColors.markerScreenshot
      CombinedEventType.SessionStatus -> MaterialTheme.colorScheme.onSurfaceVariant
    }
  val bgColor =
    if (isActive) typeColor.copy(alpha = 0.10f) else Color.Transparent
  val indentDp = (event.depth * 16).dp

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .clickable(onClick = onClick)
        .background(bgColor)
        .padding(vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Colored left accent bar
    Box(
      modifier =
        Modifier.width(3.dp)
          .height(32.dp)
          .background(
            typeColor.copy(alpha = if (isActive) 0.9f else 0.35f),
            RoundedCornerShape(1.5.dp),
          ),
    )
    Row(
      modifier = Modifier.weight(1f).padding(start = 7.dp, end = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      Text(
        text = formatDuration(event.relativeMs),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.width(48.dp),
        fontFamily = FontFamily.Monospace,
      )
      if (event.depth > 0) {
        Spacer(modifier = Modifier.width(indentDp))
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = event.title,
          style = MaterialTheme.typography.labelSmall,
          fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        if (event.detail != null) {
          Text(
            text = event.detail,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (event.sourceLog is TrailblazeLog.TrailblazeLlmRequestLog && onShowChatHistory != null) {
        IconButton(
          onClick = { onShowChatHistory(event.sourceLog) },
          modifier = Modifier.size(24.dp),
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Chat,
            contentDescription = "Chat History",
            modifier = Modifier.size(16.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
          )
        }
      }

      if (onShowInspectUI != null && event.sourceLog != null) {
        val canInspect = when (event.sourceLog) {
          is TrailblazeLog.TrailblazeLlmRequestLog -> true
          is TrailblazeLog.TrailblazeSnapshotLog -> true
          is TrailblazeLog.AgentDriverLog -> event.sourceLog.viewHierarchy != null
          else -> false
        }
        if (canInspect) {
          IconButton(
            onClick = { onShowInspectUI(event.sourceLog) },
            modifier = Modifier.size(24.dp),
          ) {
            Icon(
              imageVector = Icons.Filled.Search,
              contentDescription = "Inspect UI",
              modifier = Modifier.size(16.dp),
              tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
          }
        }
      }
    }
  }

  // YAML code block — shown when selected
  if (isActive && event.toolYaml != null) {
    ExpandedDetailPanel(typeColor = typeColor, indentDp = indentDp, minHeight = 48.dp) {
      SelectionContainer {
        Text(
          text = event.toolYaml,
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurface,
        )
      }
    }
  }

  // LLM usage details — shown when selected
  if (isActive && event.sourceLog is TrailblazeLog.TrailblazeLlmRequestLog) {
    val llmLog = event.sourceLog
    val usage = llmLog.llmRequestUsageAndCost
    val model = llmLog.trailblazeLlmModel

    ExpandedDetailPanel(typeColor = typeColor, indentDp = indentDp, minHeight = 56.dp) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
          text = "${model.trailblazeLlmProvider.display} / ${model.modelId}",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = typeColor,
        )
        if (usage != null) {
          Text(
            text = "Tokens: ${FormattingUtils.formatCommaNumber(usage.inputTokens)} in \u2192 ${FormattingUtils.formatCommaNumber(usage.outputTokens)} out",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        val toolCount = llmLog.toolOptions.size
        if (toolCount > 0) {
          Text(
            text = "Tools: $toolCount available",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Text(
          text = "Duration: ${FormattingUtils.formatCommaNumber(llmLog.durationMs)}ms",
          style = MaterialTheme.typography.labelSmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Indented detail panel with a colored accent bar, used for YAML and LLM details. */
@Composable
private fun ExpandedDetailPanel(
  typeColor: Color,
  indentDp: androidx.compose.ui.unit.Dp,
  minHeight: androidx.compose.ui.unit.Dp,
  content: @Composable () -> Unit,
) {
  Row(
    modifier =
      Modifier.fillMaxWidth()
        .padding(start = 54.dp + indentDp, end = 10.dp, top = 2.dp, bottom = 4.dp),
  ) {
    Box(
      modifier =
        Modifier.width(2.dp)
          .height(minHeight)
          .background(typeColor.copy(alpha = 0.5f), RoundedCornerShape(1.dp)),
    )
    Box(
      modifier =
        Modifier.weight(1f)
          .background(
            typeColor.copy(alpha = 0.06f),
            RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp),
          )
          .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
      content()
    }
  }
}

// -- Objective result banner --

/** Shows "Objective Passed" or "Objective Failed" with LLM explanation and failure suggestion. */
@Composable
internal fun ObjectiveResultBanner(
  objective: ObjectiveProgress,
  modifier: Modifier = Modifier,
) {
  val isSuccess = objective.status == ObjectiveStatus.Succeeded
  val accentColor =
    if (isSuccess) SessionProgressColors.succeeded else MaterialTheme.colorScheme.error

  // Failure suggestion
  if (objective.status == ObjectiveStatus.Failed) {
    val suggestion = buildFailureSuggestion(objective)
    if (suggestion != null) {
      Box(
        modifier =
          modifier
            .fillMaxWidth()
            .background(
              MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
              RoundedCornerShape(8.dp),
            )
            .padding(10.dp),
      ) {
        Text(
          text = suggestion,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
      Spacer(modifier = Modifier.height(4.dp))
    }
  }

  // Passed/Failed banner with LLM explanation
  if (objective.llmExplanation != null) {
    Box(
      modifier =
        modifier
          .fillMaxWidth()
          .background(accentColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
          .padding(10.dp),
    ) {
      Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Icon(
            imageVector =
              if (isSuccess) Icons.Filled.CheckCircle else Icons.Filled.Cancel,
            contentDescription = if (isSuccess) "Succeeded" else "Failed",
            tint = accentColor,
            modifier = Modifier.size(14.dp),
          )
          Text(
            text = if (isSuccess) "Objective Passed" else "Objective Failed",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            color = accentColor,
          )
        }
        Text(
          text = objective.llmExplanation,
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 3,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

// -- Completed summary row --

/** Overall summary at the bottom showing "X steps passed" or "Y of Z failed". */
@Composable
internal fun CombinedCompletedSummary(objectives: List<ObjectiveProgress>) {
  val failedCount = objectives.count { it.status == ObjectiveStatus.Failed }
  val completedCount = objectives.count { it.status == ObjectiveStatus.Succeeded }
  val totalCount = objectives.count { it.status != ObjectiveStatus.Pending }
  val statusColor =
    if (failedCount > 0) MaterialTheme.colorScheme.error else SessionProgressColors.succeeded

  val elapsedMs = run {
    val start = objectives.mapNotNull { it.startedAt?.toEpochMilliseconds() }.minOrNull()
    val end = objectives.mapNotNull { it.completedAt?.toEpochMilliseconds() }.maxOrNull()
    if (start != null && end != null) end - start else null
  }

  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))
    val summary =
      if (failedCount > 0) "$failedCount of $totalCount failed"
      else "$completedCount step${if (completedCount != 1) "s" else ""} passed"
    Text(
      text = summary,
      style = MaterialTheme.typography.bodySmall,
      fontWeight = FontWeight.Medium,
    )
    if (elapsedMs != null) {
      Text(
        text = formatDuration(elapsedMs),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/** Banner shown at the bottom of a session when it ended with a failure status. */
@Composable
internal fun SessionFailureBanner(
  overallStatus: SessionStatus,
  modifier: Modifier = Modifier,
) {
  val failureMessage = extractSessionFailureReason(overallStatus) ?: return

  val accentColor = MaterialTheme.colorScheme.error
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .background(accentColor.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
        .padding(10.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Cancel,
          contentDescription = "Failed",
          tint = accentColor,
          modifier = Modifier.size(14.dp),
        )
        Text(
          text = "Session Failed",
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.SemiBold,
          color = accentColor,
        )
      }
      Text(
        text = failureMessage,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}
