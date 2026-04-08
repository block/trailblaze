package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.isInProgress
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

@Composable
fun SessionProgressComposable(
  logs: List<TrailblazeLog>,
  sessionStartTime: Instant,
  overallStatus: SessionStatus?,
  sessionId: String,
  imageLoader: ImageLoader = NetworkImageLoader(),
  timelineState: SessionTimelineState? = null,
  scrollState: ScrollState? = null,
  autoFollow: Boolean = false,
  onAutoFollowDisabled: (() -> Unit)? = null,
  onShowDetails: ((TrailblazeLog) -> Unit)? = null,
  onShowInspectUI: ((TrailblazeLog) -> Unit)? = null,
  onShowChatHistory: ((TrailblazeLog.TrailblazeLlmRequestLog) -> Unit)? = null,
  onShowScreenshotModal:
    ((Any?, Int, Int, Int?, Int?, AgentDriverAction?) -> Unit)? =
    null,
) {
  val plannedPrompts = remember(logs) { extractPlannedPrompts(logs) }
  val rawProgressItems = remember(logs) { buildProgressItems(logs) }
  // If the session has ended, mark any still-InProgress objectives as Failed (e.g. timeout)
  val progressItems =
    remember(rawProgressItems, overallStatus, plannedPrompts) {
      val patched = patchProgressItemsForSessionEnd(rawProgressItems, overallStatus)
      appendPendingObjectives(patched, plannedPrompts)
    }
  val objectives = progressItems.filterIsInstance<ProgressItem.ObjectiveItem>()
    .map { it.objective }
  val currentObjectiveIndex =
    objectives.indexOfFirst { it.status == ObjectiveStatus.InProgress }.takeIf { it >= 0 }
  val completedCount = objectives.count { it.status.isTerminal }
  val totalCount = objectives.count { it.status != ObjectiveStatus.Pending }

  val latestLog = logs.lastOrNull()
  val isInProgress = overallStatus?.isInProgress == true
  var tickMs by remember { mutableStateOf(0L) }
  var selectedObjectiveIndex by remember { mutableStateOf<Int?>(null) }
  val expandedObjectives = remember { mutableStateMapOf<Int, Boolean>() }
  LaunchedEffect(isInProgress) {
    if (isInProgress) {
      while (isActive) {
        tickMs = Clock.System.now().toEpochMilliseconds()
        delay(100)
      }
    }
  }
  val elapsedMs =
    if (isInProgress) {
      if (tickMs == 0L) null else tickMs - sessionStartTime.toEpochMilliseconds()
    } else {
      latestLog?.timestamp?.toEpochMilliseconds()?.minus(sessionStartTime.toEpochMilliseconds())
    }

  // Hoisted screenshot selection state (objective index → screenshot index within that objective)
  val screenshotSelections = remember { mutableStateMapOf<Int, Int>() }
  // Columns per row reported by each objective's gallery (for grid-aware key navigation)
  val galleryColumnsPerRow = remember { mutableStateMapOf<Int, Int>() }
  val progressFocusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { progressFocusRequester.requestFocus() }
  // Re-acquire focus when selected objective changes (expand/collapse can steal focus)
  LaunchedEffect(selectedObjectiveIndex) {
    progressFocusRequester.requestFocus()
  }

  // Timeline end: last log timestamp (or live tick for in-progress)
  val timelineEndMs =
    if (isInProgress && tickMs > 0L) {
      tickMs
    } else {
      latestLog?.timestamp?.toEpochMilliseconds() ?: sessionStartTime.toEpochMilliseconds()
    }

  val onTimelineScrub: (Long) -> Unit = { timestampMs ->
    timelineState?.scrubTimestampMs = timestampMs
    val itemIndex =
      progressItems.indexOfFirst { item ->
        val start = item.startedAt?.toEpochMilliseconds() ?: return@indexOfFirst false
        val end = item.completedAt?.toEpochMilliseconds() ?: Long.MAX_VALUE
        timestampMs in start..end
      }
    if (itemIndex >= 0) {
      focusObjective(itemIndex, expandedObjectives) { selectedObjectiveIndex = it }
      val shots = buildProgressItemScreenshotItems(logs, progressItems[itemIndex])
      val closestIdx =
        shots.indices.minByOrNull {
          abs(shots[it].timestamp.toEpochMilliseconds() - timestampMs)
        }
      if (closestIdx != null) screenshotSelections[itemIndex] = closestIdx
    }
  }

  // Publish state to the shared timeline state holder via SideEffect (not during composition)
  if (timelineState != null) {
    SideEffect {
      timelineState.objectives = objectives
      timelineState.logs = logs
      timelineState.sessionStartMs = sessionStartTime.toEpochMilliseconds()
      timelineState.sessionEndMs = timelineEndMs
      timelineState.hasObjectives = progressItems.isNotEmpty()
      timelineState.onScrub = onTimelineScrub
      timelineState.scrollState = scrollState
      timelineState.isInProgress = isInProgress
    }
    // Initial state: scrub to the end and expand the last item (only once).
    // For live sessions this shows the latest activity; for completed sessions
    // it shows the final step so the user sees the result immediately.
    if (timelineState.scrubTimestampMs == null && progressItems.isNotEmpty()) {
      timelineState.scrubTimestampMs = timelineEndMs
      val lastIdx = progressItems.lastIndex
      selectedObjectiveIndex = lastIdx
      expandedObjectives[lastIdx] = true
    }
  }

  // Ensure an item is selected when there's no timeline state (e.g. Wasm reports)
  if (timelineState == null && progressItems.isNotEmpty() && selectedObjectiveIndex == null) {
    val lastIdx = progressItems.lastIndex
    selectedObjectiveIndex = lastIdx
    expandedObjectives[lastIdx] = true
  }

  // Auto-follow: when enabled and in-progress, keep scrubber at live edge and track latest step
  if (autoFollow && isInProgress && timelineState != null && progressItems.isNotEmpty()) {
    LaunchedEffect(progressItems.size, objectives.lastOrNull()?.status, tickMs) {
      val lastIdx = progressItems.lastIndex
      focusObjective(lastIdx, expandedObjectives) { selectedObjectiveIndex = it }
      timelineState.scrubTimestampMs = timelineEndMs
      val shots = buildProgressItemScreenshotItems(logs, progressItems[lastIdx])
      if (shots.isNotEmpty()) {
        screenshotSelections[lastIdx] = shots.lastIndex
      }
    }
  }

  // Scrub → auto-scroll: when the selected objective changes, scroll to it.
  // Wait for expand/collapse animations to settle before reading offsets.
  if (scrollState != null && timelineState != null) {
    LaunchedEffect(selectedObjectiveIndex) {
      val idx = selectedObjectiveIndex ?: return@LaunchedEffect
      delay(350)
      val relativeY = timelineState.objectiveOffsets[idx] ?: return@LaunchedEffect
      val targetY = (relativeY + timelineState.stepsColumnOffset).coerceAtLeast(0)
      scrollState.animateScrollTo(targetY)
    }
  }

  // Scroll → scrub: when the user scrolls manually, move the thumb to match.
  var userHasScrolled by remember { mutableStateOf(false) }
  if (scrollState != null && timelineState != null && progressItems.isNotEmpty()) {
    val currentScrollValue = scrollState.value
    LaunchedEffect(currentScrollValue) {
      // Skip if a scrub gesture is actively dragging
      if (timelineState.isScrubbing) {
        userHasScrolled = true
        return@LaunchedEffect
      }
      // Skip the initial composition — don't override the intended start position
      if (!userHasScrolled) {
        userHasScrolled = true
        return@LaunchedEffect
      }
      val offsets = timelineState.objectiveOffsets
      if (offsets.isEmpty()) return@LaunchedEffect
      val stepsOffset = timelineState.stepsColumnOffset
      val visibleIdx =
        offsets.entries.minByOrNull { abs((it.value + stepsOffset) - currentScrollValue) }?.key
          ?: return@LaunchedEffect
      val item = progressItems.getOrNull(visibleIdx) ?: return@LaunchedEffect
      val startMs = item.startedAt?.toEpochMilliseconds() ?: return@LaunchedEffect
      val endMs = item.completedAt?.toEpochMilliseconds() ?: timelineEndMs
      timelineState.scrubTimestampMs = (startMs + endMs) / 2
    }
  }

  // Precompute screenshot counts per progress item for key navigation
  val objectiveScreenshotCounts =
    remember(logs, progressItems) {
      progressItems.map { buildProgressItemScreenshotItems(logs, it).size }
    }

  Column(
    modifier =
      Modifier.fillMaxWidth()
        .focusRequester(progressFocusRequester)
        .onKeyEvent { event ->
          if (event.type != KeyEventType.KeyDown || progressItems.isEmpty()) {
            return@onKeyEvent false
          }
          // Any key interaction disables auto-follow
          onAutoFollowDisabled?.invoke()
          when (event.key) {
            Key.DirectionUp -> {
              val objIdx = selectedObjectiveIndex ?: 0
              val currentShot = screenshotSelections[objIdx] ?: 0
              val cols = galleryColumnsPerRow[objIdx] ?: 1
              val currentRow = currentShot / cols
              if (currentRow > 0) {
                // Move up one row within the same objective's gallery
                screenshotSelections[objIdx] = currentShot - cols
              } else {
                // At the top row — move to the previous objective
                val target = (objIdx - 1).coerceAtLeast(0)
                if (target != objIdx) {
                  focusObjective(target, expandedObjectives) { selectedObjectiveIndex = it }
                  val lastShot =
                    (objectiveScreenshotCounts.getOrElse(target) { 1 } - 1).coerceAtLeast(0)
                  screenshotSelections[target] = lastShot
                  if (timelineState != null) {
                    progressItems[target].startedAt?.toEpochMilliseconds()?.let {
                      timelineState.scrubTimestampMs = it
                    }
                  }
                }
              }
              true
            }
            Key.DirectionDown -> {
              val objIdx = selectedObjectiveIndex ?: -1
              if (objIdx < 0) {
                focusObjective(0, expandedObjectives) { selectedObjectiveIndex = it }
                screenshotSelections[0] = 0
              } else {
                val currentShot = screenshotSelections[objIdx] ?: 0
                val cols = galleryColumnsPerRow[objIdx] ?: 1
                val totalShots = objectiveScreenshotCounts.getOrElse(objIdx) { 0 }
                val currentRow = currentShot / cols
                val lastRow = if (totalShots > 0) (totalShots - 1) / cols else 0
                val nextRowShot = currentShot + cols
                if (nextRowShot < totalShots) {
                  // Move down one row, same column
                  screenshotSelections[objIdx] = nextRowShot
                } else if (currentRow < lastRow) {
                  // Next row exists but doesn't have enough columns — snap to last item
                  screenshotSelections[objIdx] = totalShots - 1
                } else {
                  // On the last row — move to the next item
                  val target = (objIdx + 1).coerceAtMost(progressItems.lastIndex)
                  if (target != objIdx) {
                    focusObjective(target, expandedObjectives) { selectedObjectiveIndex = it }
                    screenshotSelections[target] = 0
                    if (timelineState != null) {
                      progressItems[target].startedAt?.toEpochMilliseconds()?.let {
                        timelineState.scrubTimestampMs = it
                      }
                    }
                  }
                }
              }
              true
            }
            Key.DirectionLeft -> {
              val objIdx = selectedObjectiveIndex ?: 0
              val current = screenshotSelections[objIdx] ?: 0
              if (current > 0) {
                screenshotSelections[objIdx] = current - 1
              } else if (objIdx > 0) {
                // At first item — move to previous item's last screenshot
                val target = objIdx - 1
                focusObjective(target, expandedObjectives) { selectedObjectiveIndex = it }
                val lastShot = (objectiveScreenshotCounts.getOrElse(target) { 1 } - 1)
                  .coerceAtLeast(0)
                screenshotSelections[target] = lastShot
                if (timelineState != null) {
                  progressItems[target].startedAt?.toEpochMilliseconds()?.let {
                    timelineState.scrubTimestampMs = it
                  }
                }
              }
              true
            }
            Key.DirectionRight -> {
              val objIdx = selectedObjectiveIndex ?: 0
              val current = screenshotSelections[objIdx] ?: 0
              val max = objectiveScreenshotCounts.getOrElse(objIdx) { 0 } - 1
              if (current < max) {
                screenshotSelections[objIdx] = current + 1
              } else if (objIdx < progressItems.lastIndex) {
                // At last item — move to next item's first screenshot
                val target = objIdx + 1
                focusObjective(target, expandedObjectives) { selectedObjectiveIndex = it }
                screenshotSelections[target] = 0
                if (timelineState != null) {
                  progressItems[target].startedAt?.toEpochMilliseconds()?.let {
                    timelineState.scrubTimestampMs = it
                  }
                }
              }
              true
            }
            else -> false
          }
        }
        .focusable(),
    verticalArrangement = Arrangement.spacedBy(if (isInProgress) 16.dp else 8.dp),
  ) {
    // --- Summary ---
    if (isInProgress || progressItems.isEmpty()) {
      SummaryCard(
        isInProgress = isInProgress,
        totalCount = totalCount,
        completedCount = completedCount,
        currentObjectiveIndex = currentObjectiveIndex,
        overallStatus = overallStatus,
        elapsedMs = elapsedMs,
        latestLog = latestLog,
        objectives = objectives,
      )
    } else {
      CompletedSummaryRow(
        totalCount = totalCount,
        completedCount = completedCount,
        elapsedMs = elapsedMs,
        objectives = objectives,
      )
    }

    // --- Steps List ---
    if (progressItems.isNotEmpty()) {
      Column(
        modifier =
          Modifier.onGloballyPositioned { coords ->
            timelineState?.stepsColumnOffset = coords.positionInParent().y.toInt()
          },
      ) {
          progressItems.forEachIndexed { index, progressItem ->
            val isExpanded = expandedObjectives[index] == true || selectedObjectiveIndex == index
            val itemScreenshots =
              remember(logs, progressItem.startedAt, progressItem.completedAt) {
                buildProgressItemScreenshotItems(logs = logs, item = progressItem)
              }
            // A ToolBlockItem that immediately follows an ObjectiveItem contains late-arriving
            // tool logs that semantically belong to that objective (fire-and-forget timing in
            // MCP mode). It renders inside ObjectiveStepRow — skip it as a standalone row.
            val isChildToolBlock =
              progressItem is ProgressItem.ToolBlockItem &&
                progressItems.getOrNull(index - 1) is ProgressItem.ObjectiveItem
            // Look ahead: pass any immediately following ToolBlockItem into ObjectiveStepRow so
            // it renders inside the expanded section rather than as a sibling row.
            val childToolBlock =
              if (progressItem is ProgressItem.ObjectiveItem)
                progressItems.getOrNull(index + 1) as? ProgressItem.ToolBlockItem
              else null
            val childToolBlockScreenshots =
              remember(logs, childToolBlock?.startedAt, childToolBlock?.completedAt) {
                childToolBlock?.let { buildProgressItemScreenshotItems(logs, it) } ?: emptyList()
              }
            if (!isChildToolBlock) {
              if (index > 0) {
                HorizontalDivider(
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                )
              }
              Box(
                modifier =
                  Modifier.onGloballyPositioned { coords ->
                    timelineState?.objectiveOffsets?.put(
                      index,
                      coords.positionInParent().y.toInt(),
                    )
                  },
              ) {
                when (progressItem) {
                  is ProgressItem.ObjectiveItem -> {
                    ObjectiveStepRow(
                      stepNumber = progressItem.stepNumber,
                      objective = progressItem.objective,
                      sessionStartTime = sessionStartTime,
                      isExpanded = isExpanded,
                      onToggleExpanded = {
                        onAutoFollowDisabled?.invoke()
                        expandedObjectives[index] = !isExpanded
                        selectedObjectiveIndex = if (!isExpanded) index else null
                        if (!isExpanded && timelineState != null) {
                          val startMs =
                            progressItem.objective.startedAt?.toEpochMilliseconds()
                          if (startMs != null) {
                            timelineState.scrubTimestampMs = startMs
                          }
                        }
                      },
                      objectiveScreenshots = itemScreenshots,
                      selectedScreenshotIndex = screenshotSelections[index] ?: 0,
                      onScreenshotSelected = { screenshotSelections[index] = it },
                      sessionId = sessionId,
                      imageLoader = imageLoader,
                      logs = logs,
                      onShowDetails = onShowDetails,
                      onShowInspectUI = onShowInspectUI,
                      onShowChatHistory = onShowChatHistory,
                      onShowScreenshotModal = onShowScreenshotModal,
                      onColumnsPerRowChanged = { cols -> galleryColumnsPerRow[index] = cols },
                      childToolBlock = childToolBlock,
                      childToolBlockScreenshots = childToolBlockScreenshots,
                    )
                  }
                  is ProgressItem.ToolBlockItem -> {
                    ToolBlockRow(
                      toolBlock = progressItem,
                      sessionStartTime = sessionStartTime,
                      isExpanded = isExpanded,
                      onToggleExpanded = {
                        onAutoFollowDisabled?.invoke()
                        expandedObjectives[index] = !isExpanded
                        selectedObjectiveIndex = if (!isExpanded) index else null
                        if (!isExpanded && timelineState != null) {
                          val startMs = progressItem.startedAt?.toEpochMilliseconds()
                          if (startMs != null) {
                            timelineState.scrubTimestampMs = startMs
                          }
                        }
                      },
                      toolBlockScreenshots = itemScreenshots,
                      selectedScreenshotIndex = screenshotSelections[index] ?: 0,
                      onScreenshotSelected = { screenshotSelections[index] = it },
                      sessionId = sessionId,
                      imageLoader = imageLoader,
                      onShowDetails = onShowDetails,
                      onShowScreenshotModal = onShowScreenshotModal,
                      onColumnsPerRowChanged = { cols -> galleryColumnsPerRow[index] = cols },
                    )
                  }
                }
              }
            }
          }
        }
      }
    }

    // Session-level failure banner
    if (overallStatus != null) {
      SessionFailureBanner(
        overallStatus = overallStatus,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
      )
    }
  }
// -- Summary Card ---------------------------------------------------------------

@Composable
private fun SummaryCard(
  isInProgress: Boolean,
  totalCount: Int,
  completedCount: Int,
  currentObjectiveIndex: Int?,
  overallStatus: SessionStatus?,
  elapsedMs: Long?,
  latestLog: TrailblazeLog?,
  objectives: List<ObjectiveProgress>,
) {
  val isDark = isSystemInDarkTheme()
  val accentColor =
    if (isInProgress) {
      val base = SessionProgressColors.inProgressAccentLight
      if (isDark) base.copy(alpha = 0.15f) else base.copy(alpha = 0.5f)
    } else {
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
    }

  Card(
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    shape = RoundedCornerShape(12.dp),
  ) {
    Column {
      Box(
        modifier =
          Modifier.fillMaxWidth()
            .height(4.dp)
            .background(
              if (isInProgress) SessionProgressColors.inProgressAccentBar
              else SessionProgressColors.succeeded,
            ),
      )
      Column(
        modifier = Modifier.fillMaxWidth().background(accentColor).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // Row 1: spinner + title + step info + elapsed time — all on one line
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          if (isInProgress) {
            CircularProgressIndicator(
              modifier = Modifier.size(14.dp),
              strokeWidth = 2.dp,
              color = SessionProgressColors.inProgressAccentBar,
            )
          }
          Text(
            text = if (isInProgress) "Live Progress" else "Progress Summary",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
          )

          val stepInfo =
            when {
              totalCount == 0 && overallStatus?.isInProgress == true -> "Waiting…"
              totalCount == 0 -> "Tools only"
              currentObjectiveIndex != null ->
                "Step ${currentObjectiveIndex + 1}/$totalCount"
              else -> "$completedCount/$totalCount done"
            }
          Text(
            text = "·  $stepInfo",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )

          if (elapsedMs != null) {
            SelectableText(
              text = "·  ${formatDuration(elapsedMs)}",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }

        // Row 2: progress dots + latest activity
        if (isInProgress && objectives.isNotEmpty()) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
          ) {
            StepProgressTrack(objectives = objectives)

            latestLog?.let { log ->
              Text(
                text = latestActivityLabel(log),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
              )
            }
          }
        } else {
          latestLog?.let { log ->
            Text(
              text = latestActivityLabel(log),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
            )
          }
        }
      }
    }
  }
}

// -- Completed Summary Row ------------------------------------------------------

@Composable
private fun CompletedSummaryRow(
  totalCount: Int,
  completedCount: Int,
  elapsedMs: Long?,
  objectives: List<ObjectiveProgress>,
) {
  val failedCount = objectives.count { it.status == ObjectiveStatus.Failed }
  val statusColor =
    if (failedCount > 0) MaterialTheme.colorScheme.error else SessionProgressColors.succeeded

  SelectionContainer {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Box(modifier = Modifier.size(8.dp).background(statusColor, CircleShape))

      val summary =
        if (failedCount > 0) {
          "$failedCount of $totalCount failed"
        } else {
          "$completedCount step${if (completedCount != 1) "s" else ""} passed"
        }
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
}

// -- Step Progress Track --------------------------------------------------------

@Composable
private fun StepProgressTrack(
  objectives: List<ObjectiveProgress>,
  modifier: Modifier = Modifier,
) {
  val infiniteTransition = rememberInfiniteTransition(label = "pulse")
  val pulseAlpha by
    infiniteTransition.animateFloat(
      initialValue = 0.4f,
      targetValue = 1.0f,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = 1000),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "pulseAlpha",
    )

  Row(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    objectives.forEach { objective ->
      val baseColor =
        when (objective.status) {
          ObjectiveStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
          ObjectiveStatus.Succeeded -> SessionProgressColors.succeeded
          ObjectiveStatus.Failed -> MaterialTheme.colorScheme.error
          ObjectiveStatus.InProgress -> MaterialTheme.colorScheme.primary
        }
      val dotColor by animateColorAsState(targetValue = baseColor, label = "dotColor")
      val alpha = if (objective.status == ObjectiveStatus.InProgress) pulseAlpha else 1f

      Box(
        modifier = Modifier.size(8.dp).background(dotColor.copy(alpha = alpha), CircleShape),
      )
    }
  }
}

// -- Objective Step Row ---------------------------------------------------------

@Composable
private fun ObjectiveStepRow(
  stepNumber: Int,
  objective: ObjectiveProgress,
  sessionStartTime: Instant,
  isExpanded: Boolean,
  onToggleExpanded: () -> Unit,
  objectiveScreenshots: List<ScreenshotTimelineItem>,
  selectedScreenshotIndex: Int,
  onScreenshotSelected: (Int) -> Unit,
  sessionId: String,
  imageLoader: ImageLoader,
  logs: List<TrailblazeLog>,
  onShowDetails: ((TrailblazeLog) -> Unit)? = null,
  onShowInspectUI: ((TrailblazeLog) -> Unit)? = null,
  onShowChatHistory: ((TrailblazeLog.TrailblazeLlmRequestLog) -> Unit)? = null,
  onShowScreenshotModal:
    ((Any?, Int, Int, Int?, Int?, AgentDriverAction?) -> Unit)? =
    null,
  onColumnsPerRowChanged: ((Int) -> Unit)? = null,
  childToolBlock: ProgressItem.ToolBlockItem? = null,
  childToolBlockScreenshots: List<ScreenshotTimelineItem> = emptyList(),
) {
  var childToolBlockExpanded by remember { mutableStateOf(false) }
  val isPending = objective.status == ObjectiveStatus.Pending
  val isActive = objective.status == ObjectiveStatus.InProgress
  val statusColor =
    when (objective.status) {
      ObjectiveStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
      ObjectiveStatus.InProgress -> MaterialTheme.colorScheme.primary
      ObjectiveStatus.Succeeded -> SessionProgressColors.succeeded
      ObjectiveStatus.Failed -> MaterialTheme.colorScheme.error
    }

  // Image click handler — delegates to the parent screenshot modal callback
  val onScreenshotClick: (Any?, Int, Int, Int?, Int?) -> Unit = { imageModel, dw, dh, cx, cy ->
    if (imageModel != null && onShowScreenshotModal != null) {
      val action =
        objectiveScreenshots
          .firstOrNull { it.clickX == cx && it.clickY == cy && it.deviceWidth == dw }
          ?.action
      onShowScreenshotModal.invoke(imageModel, dw, dh, cx, cy, action)
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    // Clickable header row
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clickable { onToggleExpanded() }
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Leading: step number + status indicator
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
              modifier = Modifier.size(20.dp),
              color = statusColor,
              strokeWidth = 2.dp,
            )
          }
          ObjectiveStatus.Succeeded -> {
            Icon(
              imageVector = Icons.Filled.CheckCircle,
              contentDescription = "Passed",
              tint = statusColor,
              modifier = Modifier.size(20.dp),
            )
          }
          ObjectiveStatus.Failed -> {
            Icon(
              imageVector = Icons.Filled.Cancel,
              contentDescription = "Failed",
              tint = statusColor,
              modifier = Modifier.size(20.dp),
            )
          }
        }
      }

      // Content: objective prompt (bold) + subtitle
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = promptSummary(objective.prompt, maxLength = 200),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = if (isPending) FontWeight.Normal else FontWeight.SemiBold,
          color =
            if (isPending) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            else Color.Unspecified,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        if (!isPending) {
          val subtitle = buildStepSubtitle(objective, sessionStartTime)
          SelectableText(
            text = subtitle,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Trailing: live screenshot thumbnail for in-progress, duration for completed
      if (isActive) {
        val latestScreenshot = objectiveScreenshots.lastOrNull()
        if (latestScreenshot != null) {
          ScreenshotImage(
            sessionId = sessionId,
            screenshotFile = latestScreenshot.screenshotFile,
            deviceWidth = latestScreenshot.deviceWidth,
            deviceHeight = latestScreenshot.deviceHeight,
            clickX = latestScreenshot.clickX,
            clickY = latestScreenshot.clickY,
            action = latestScreenshot.action,
            modifier = Modifier.width(80.dp),
            imageLoader = imageLoader,
            onImageClick = onScreenshotClick,
          )
        }
      } else {
        val duration = objectiveDurationMs(objective)
        if (duration != null) {
          SelectableText(
            text = formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      // Expand chevron
      Icon(
        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (isExpanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }

    // In-progress: single "Latest: ..." line
    if (isActive) {
      val latestLogInObjective =
        remember(logs, objective.startedAt) { latestLogForObjective(logs, objective) }
      if (latestLogInObjective != null) {
        Text(
          text = "Latest: ${latestActivityLabel(latestLogInObjective)}",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier =
            Modifier.fillMaxWidth()
              .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.04f))
              .padding(horizontal = 16.dp, vertical = 6.dp),
        )
      }
    }

    // Expanded detail
    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Column(modifier = Modifier.fillMaxWidth()) {
        Column(
          modifier =
            Modifier.fillMaxWidth()
              .padding(start = 52.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
          // Full prompt + tool call count / duration
          SelectionContainer {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
              Text(
                text = objective.prompt.trim(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )

              // Tool call count + duration line
              val toolCount = objective.toolCallCount
              val duration = objectiveDurationMs(objective)
              if (toolCount > 0 || duration != null) {
                val parts = mutableListOf<String>()
                if (toolCount > 0) parts.add("$toolCount tool call${if (toolCount != 1) "s" else ""}")
                if (duration != null) parts.add(formatDuration(duration))
                Text(
                  text = parts.joinToString(" \u2022 "),
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
              }
            }
          }

          // Failure suggestion
          if (objective.status == ObjectiveStatus.Failed) {
            val suggestion = buildFailureSuggestion(objective)
            if (suggestion != null) {
              Box(
                modifier =
                  Modifier.fillMaxWidth()
                    .background(
                      MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                      RoundedCornerShape(8.dp),
                    )
                    .padding(12.dp),
              ) {
                SelectableText(
                  text = suggestion,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onErrorContainer,
                )
              }
            }
          }

          // Screenshots gallery
          if (objectiveScreenshots.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
              SectionLabel("EVENTS")
              ScreenshotGallery(
                items = objectiveScreenshots,
                sessionStartTime = sessionStartTime,
                selectedIndex = selectedScreenshotIndex,
                onSelectedIndexChanged = onScreenshotSelected,
                sessionId = sessionId,
                imageLoader = imageLoader,
                onFullScreenClick = onScreenshotClick,
                onShowDetails = onShowDetails,
                onShowInspectUI = onShowInspectUI,
                onShowChatHistory = onShowChatHistory,
                onColumnsPerRowChanged = onColumnsPerRowChanged,
              )
            }
          }

          // LLM explanation (objective result) — shown at the bottom of every completed objective
          if (objective.status.isTerminal && objective.llmExplanation != null) {
            val isSuccess = objective.status == ObjectiveStatus.Succeeded
            val accentColor =
              if (isSuccess) SessionProgressColors.succeeded
              else MaterialTheme.colorScheme.error
            Box(
              modifier =
                Modifier.fillMaxWidth()
                  .background(
                    accentColor.copy(alpha = 0.08f),
                    RoundedCornerShape(8.dp),
                  )
                  .padding(12.dp),
            ) {
              Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                  Icon(
                    imageVector =
                      if (isSuccess) Icons.Filled.CheckCircle
                      else Icons.Filled.Cancel,
                    contentDescription = if (isSuccess) "Succeeded" else "Failed",
                    tint = accentColor,
                    modifier = Modifier.size(16.dp),
                  )
                  Text(
                    text = if (isSuccess) "Objective Passed" else "Objective Failed",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = accentColor,
                  )
                }
                SelectableText(
                  text = objective.llmExplanation,
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurface,
                )
              }
            }
          }
        }

        // Child tool block: late-arriving MCP tools rendered inside the expanded objective section
        if (childToolBlock != null) {
          HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
          )
          Box(modifier = Modifier.padding(start = 16.dp)) {
            ToolBlockRow(
              toolBlock = childToolBlock,
              sessionStartTime = sessionStartTime,
              isExpanded = childToolBlockExpanded,
              onToggleExpanded = { childToolBlockExpanded = !childToolBlockExpanded },
              toolBlockScreenshots = childToolBlockScreenshots,
              selectedScreenshotIndex = 0,
              onScreenshotSelected = {},
              sessionId = sessionId,
              imageLoader = imageLoader,
              onShowDetails = onShowDetails,
              onShowScreenshotModal = onShowScreenshotModal,
            )
          }
        }
      }
    }
  }
}

// -- Tool Block Row -------------------------------------------------------------

@Composable
private fun ToolBlockRow(
  toolBlock: ProgressItem.ToolBlockItem,
  sessionStartTime: Instant,
  isExpanded: Boolean,
  onToggleExpanded: () -> Unit,
  toolBlockScreenshots: List<ScreenshotTimelineItem>,
  selectedScreenshotIndex: Int,
  onScreenshotSelected: (Int) -> Unit,
  sessionId: String,
  imageLoader: ImageLoader,
  onShowDetails: ((TrailblazeLog) -> Unit)? = null,
  onShowScreenshotModal:
    ((Any?, Int, Int, Int?, Int?, AgentDriverAction?) -> Unit)? =
    null,
  onColumnsPerRowChanged: ((Int) -> Unit)? = null,
) {
  val toolCount = toolBlock.toolLogs.size
  val statusColor = MaterialTheme.colorScheme.onSurfaceVariant

  val onScreenshotClick: (Any?, Int, Int, Int?, Int?) -> Unit = { imageModel, dw, dh, cx, cy ->
    if (imageModel != null && onShowScreenshotModal != null) {
      val action =
        toolBlockScreenshots
          .firstOrNull { it.clickX == cx && it.clickY == cy && it.deviceWidth == dw }
          ?.action
      onShowScreenshotModal.invoke(imageModel, dw, dh, cx, cy, action)
    }
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    // Clickable header row
    Row(
      modifier =
        Modifier.fillMaxWidth()
          .clickable { onToggleExpanded() }
          .padding(horizontal = 16.dp, vertical = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      // Leading: wrench icon (no step number)
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
      ) {
        Icon(
          imageVector = Icons.Filled.Build,
          contentDescription = "Tools",
          tint = statusColor,
          modifier = Modifier.size(20.dp),
        )
      }

      // Content: "Tools" label + tool count
      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = "Tools",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
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
        SelectableText(
          text = subtitle,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Trailing: duration
      val durationMs = toolBlock.let {
        val start = it.startedAt?.toEpochMilliseconds()
        val end = it.completedAt?.toEpochMilliseconds()
        if (start != null && end != null) end - start else null
      }
      if (durationMs != null) {
        SelectableText(
          text = formatDuration(durationMs),
          style = MaterialTheme.typography.labelSmall,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // Expand chevron
      Icon(
        imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
        contentDescription = if (isExpanded) "Collapse" else "Expand",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(18.dp),
      )
    }

    // Expanded detail
    AnimatedVisibility(
      visible = isExpanded,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Column(
        modifier =
          Modifier.fillMaxWidth()
            .padding(start = 52.dp, end = 16.dp, top = 0.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        // Screenshots gallery
        if (toolBlockScreenshots.isNotEmpty()) {
          Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel("EVENTS")
            ScreenshotGallery(
              items = toolBlockScreenshots,
              sessionStartTime = sessionStartTime,
              selectedIndex = selectedScreenshotIndex,
              onSelectedIndexChanged = onScreenshotSelected,
              sessionId = sessionId,
              imageLoader = imageLoader,
              onFullScreenClick = onScreenshotClick,
              onShowDetails = onShowDetails,
              onColumnsPerRowChanged = onColumnsPerRowChanged,
            )
          }
        }
      }
    }
  }
}

@Composable
private fun SectionLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelSmall,
    fontWeight = FontWeight.SemiBold,
    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
  )
}

/** Collapse all objectives except [index], expand [index], and update the selected index. */
private fun focusObjective(
  index: Int,
  expandedObjectives: MutableMap<Int, Boolean>,
  setSelectedIndex: (Int) -> Unit,
) {
  expandedObjectives.keys.toList().forEach { key ->
    if (key != index) expandedObjectives.remove(key)
  }
  setSelectedIndex(index)
  expandedObjectives[index] = true
}
