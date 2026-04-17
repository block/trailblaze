package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.time.TimeSource
import xyz.block.trailblaze.yaml.TrailblazeYaml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.HasScreenshot
import xyz.block.trailblaze.logs.model.HasTraceId
import xyz.block.trailblaze.logs.model.HasTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.isInProgress
import xyz.block.trailblaze.ui.composables.ScreenshotAnnotation
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.SelectableText
import xyz.block.trailblaze.ui.composables.createVideoFrameCache
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.Platform
import xyz.block.trailblaze.ui.getPlatform
import xyz.block.trailblaze.ui.openVideoInSystemPlayer
import xyz.block.trailblaze.ui.resolveImageModel
import xyz.block.trailblaze.ui.utils.FormattingUtils
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

/**
 * Combined view: step-grouped event hierarchy + video frame + vertical scrub bar.
 *
 * Layout:
 * ┌──────────────────┬──────────────┬──────────┐
 * │  Step-grouped     │  Video frame │ Vertical │
 * │  hierarchy with   │  (auto-sized │ scrub    │
 * │  collapsible      │   to phone   │ bar      │
 * │  objectives       │   aspect)    │          │
 * └──────────────────┴──────────────┴──────────┘
 */
@Composable
internal fun SessionCombinedView(
  logs: List<TrailblazeLog>,
  overallStatus: SessionStatus?,
  sessionId: String,
  videoMetadata: VideoMetadata? = null,
  imageLoader: ImageLoader = NetworkImageLoader(),
  onShowScreenshotModal:
    ((imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: AgentDriverAction?) -> Unit)? =
    null,
  onShowInspectUI: ((TrailblazeLog) -> Unit)? = null,
  onShowChatHistory: ((TrailblazeLog.TrailblazeLlmRequestLog) -> Unit)? = null,
) {
  // Build timeline data
  var objectives by remember { mutableStateOf<List<ObjectiveProgress>>(emptyList()) }
  var sessionStartMs by remember { mutableStateOf(0L) }
  var sessionEndMs by remember { mutableStateOf(0L) }

  LaunchedEffect(logs) {
    withContext(Dispatchers.Default) {
      objectives = buildObjectiveProgress(logs)
      val timestamps = logs.mapNotNull { it.timestamp.toEpochMilliseconds() }
      sessionStartMs = timestamps.minOrNull() ?: 0L
      sessionEndMs = timestamps.maxOrNull() ?: 0L
    }
  }

  if (logs.isEmpty() || sessionStartMs == 0L) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Loading combined view...")
    }
    return
  }

  val timelineState = rememberSessionTimelineState()

  // Constrain timeline to test execution window only (first log to last log).
  // Video frames outside this window are not useful — they show pre/post-test idle time.
  val effectiveStartMs = sessionStartMs
  val effectiveEndMs = sessionEndMs

  // Track whether the user has explicitly clicked on an event (disables live tracking)
  var userHasInteracted by remember { mutableStateOf(false) }

  // Track the specific event selected by click or keyboard, so same-timestamp events are
  // disambiguated (e.g. a ToolCall and DriverAction at the same ms).
  var selectedEventKey by remember { mutableStateOf<String?>(null) }

  // Populate timeline state
  LaunchedEffect(objectives, logs, effectiveStartMs, effectiveEndMs) {
    timelineState.objectives = objectives
    timelineState.logs = logs
    timelineState.sessionStartMs = effectiveStartMs
    timelineState.sessionEndMs = effectiveEndMs
    timelineState.hasObjectives = objectives.isNotEmpty()
    timelineState.isInProgress = overallStatus?.isInProgress == true
    if (timelineState.scrubTimestampMs == null) {
      // Completed sessions start at the end so the user sees the final step;
      // live sessions start at the beginning and auto-follow takes over.
      val isLiveSession = overallStatus?.isInProgress == true
      timelineState.scrubTimestampMs = if (isLiveSession) effectiveStartMs else effectiveEndMs
    }
  }

  // Live tracking: keep scrub at latest log timestamp unless user has clicked away
  val isLive = overallStatus?.isInProgress == true
  LaunchedEffect(logs.size, sessionEndMs, isLive, userHasInteracted) {
    if (isLive && !userHasInteracted && sessionEndMs > 0L) {
      timelineState.scrubTimestampMs = sessionEndMs
    }
  }

  // Scrub handler — updates timestamp, stops playback, marks user interaction
  timelineState.onScrub = { ts ->
    userHasInteracted = true
    selectedEventKey = null // timeline scrub — fall back to timestamp-based highlighting
    timelineState.scrubTimestampMs = ts
  }

  val currentTimestamp = timelineState.scrubTimestampMs ?: sessionStartMs

  // Find active driver action for video overlay
  val activeDriverLog =
    remember(currentTimestamp, logs) {
      logs
        .filterIsInstance<TrailblazeLog.AgentDriverLog>()
        .lastOrNull { log ->
          val logMs = log.timestamp.toEpochMilliseconds()
          logMs <= currentTimestamp && (currentTimestamp - logMs) < log.durationMs
        }
    }

  // Current video frame — hoisted so video column width can react to its aspect ratio
  var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }

  // Device aspect ratio from first log with dimensions — available before any frame loads
  val deviceAspect =
    remember(logs) {
      logs
        .filterIsInstance<HasScreenshot>()
        .firstOrNull { it.deviceWidth > 0 && it.deviceHeight > 0 }
        ?.let { it.deviceWidth.toFloat() / it.deviceHeight.toFloat() }
    }

  // Build step-grouped progress items, appending pending objectives from the YAML
  val plannedPrompts = remember(logs) { extractPlannedPrompts(logs) }
  val rawProgressItems = remember(logs) { buildProgressItems(logs) }
  val progressItems =
    remember(rawProgressItems, overallStatus, plannedPrompts) {
      val patched = patchProgressItemsForSessionEnd(rawProgressItems, overallStatus)
      appendPendingObjectives(patched, plannedPrompts)
    }

  // Build child events for each progress item
  val childEventsPerItem =
    remember(logs, progressItems, sessionStartMs) {
      progressItems.map { item -> buildChildEvents(logs, item, sessionStartMs) }
    }

  // Expand/collapse state — auto-controlled by scrub position
  val expandedItems = remember { mutableStateMapOf<Int, Boolean>() }

  // Auto-expand the item containing the current scrub timestamp
  val activeItemIndex =
    remember(currentTimestamp, progressItems) {
      // First try: exact range match
      var idx = progressItems.indexOfFirst { item ->
        val start = item.startedAt?.toEpochMilliseconds() ?: return@indexOfFirst false
        val end = item.completedAt?.toEpochMilliseconds() ?: Long.MAX_VALUE
        currentTimestamp in start..end
      }.takeIf { it >= 0 }
      if (idx == null && progressItems.isNotEmpty()) {
        // Fallback: nearest item (handles gaps between steps and timestamps before first step)
        idx = progressItems.indexOfLast { item ->
          val start = item.startedAt?.toEpochMilliseconds() ?: return@indexOfLast false
          start <= currentTimestamp
        }.takeIf { it >= 0 }
        // If still null (timestamp before all steps), use first item
        if (idx == null) idx = 0
      }
      idx
    }

  LaunchedEffect(activeItemIndex) {
    if (activeItemIndex != null) {
      // Collapse all others, expand the active one
      expandedItems.keys.toList().forEach { key ->
        if (key != activeItemIndex) expandedItems.remove(key)
      }
      expandedItems[activeItemIndex] = true
    }
  }

  // Event markers for timeline bar (used by VerticalTimelineBar)
  val eventMarkers =
    remember(logs, effectiveStartMs, effectiveEndMs) {
      buildEventMarkers(logs, effectiveStartMs, effectiveEndMs)
    }

  // Flat navigation list built from visible child events — matches exactly what's on screen.
  // No dedup: every child event is navigable, even when sharing a timestamp.
  val navEvents =
    remember(childEventsPerItem) {
      childEventsPerItem.flatMap { it }.sortedBy { it.timestampMs }
    }

  // Keyboard handling
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  // Re-request focus after expand/collapse — AnimatedVisibility can steal it
  LaunchedEffect(activeItemIndex) {
    delay(TimelineConstants.FOCUS_REQUEST_DELAY_MS)
    focusRequester.requestFocus()
  }

  val listScrollState = rememberScrollState()

  // Auto-scroll to bottom during live sessions when new items appear
  LaunchedEffect(activeItemIndex, logs.size, isLive, userHasInteracted) {
    if (!isLive || userHasInteracted) return@LaunchedEffect
    // Wait for expand/collapse animation to settle
    delay(TimelineConstants.ANIMATION_SETTLE_DELAY_MS)
    listScrollState.animateScrollTo(listScrollState.maxValue)
  }

  // Completed sessions: scroll to the bottom once so the user sees the final step
  var didInitialScroll by remember { mutableStateOf(false) }
  LaunchedEffect(isLive, logs.size) {
    if (!isLive && logs.isNotEmpty() && !didInitialScroll && !userHasInteracted) {
      delay(TimelineConstants.ANIMATION_SETTLE_DELAY_MS)
      listScrollState.scrollTo(listScrollState.maxValue)
      didInitialScroll = true
    }
  }

  Row(
    modifier =
      Modifier.fillMaxSize()
        .focusRequester(focusRequester)
        .focusable()
        .onPreviewKeyEvent { event ->
          handleCombinedViewKeyEvent(
            event = event,
            navEvents = navEvents,
            currentTimestamp = currentTimestamp,
            selectedEventKey = selectedEventKey,
            timelineState = timelineState,
            videoMetadata = videoMetadata,
            effectiveStartMs = effectiveStartMs,
            effectiveEndMs = effectiveEndMs,
            onUserInteracted = { userHasInteracted = true },
            onSelectedEventKeyChanged = { selectedEventKey = it },
          )
        },
  ) {
    // Left column: step-grouped hierarchy
    Column(
      modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(listScrollState),
    ) {
      progressItems.forEachIndexed { index, item ->
        val isExpanded = expandedItems[index] == true
        val isActiveItem = index == activeItemIndex
        val childEvents = childEventsPerItem[index]

        if (index > 0) {
          HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f),
          )
        }

        when (item) {
          is ProgressItem.ObjectiveItem -> {
            val isPending = item.objective.status == ObjectiveStatus.Pending
            CombinedObjectiveHeader(
              stepNumber = item.stepNumber,
              objective = item.objective,
              isExpanded = if (isPending) false else isExpanded,
              isActive = if (isPending) false else isActiveItem,
              onToggle = {
                if (!isPending) expandedItems[index] = !isExpanded
              },
              onClick = {
                if (!isPending) {
                  userHasInteracted = true
                  selectedEventKey = null
                  val startMs = item.objective.startedAt?.toEpochMilliseconds()
                  if (startMs != null) {
                    timelineState.scrubTimestampMs = startMs
                    timelineState.isVideoPlaying = false
                    timelineState.isSnappedToMarker = false
                  }
                }
              },
            )
          }
          is ProgressItem.ToolBlockItem -> {
            CombinedToolBlockHeader(
              toolBlock = item,
              isExpanded = isExpanded,
              isActive = isActiveItem,
              onToggle = {
                expandedItems[index] = !isExpanded
              },
              onClick = {
                userHasInteracted = true
                selectedEventKey = null
                val startMs = item.startedAt?.toEpochMilliseconds()
                if (startMs != null) {
                  timelineState.scrubTimestampMs = startMs
                  timelineState.isVideoPlaying = false
                  timelineState.isSnappedToMarker = false
                }
              },
            )
          }
        }

        // Expanded child events
        AnimatedVisibility(
          visible = isExpanded,
          enter = expandVertically() + fadeIn(),
          exit = shrinkVertically() + fadeOut(),
        ) {
          Column(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp),
          ) {
            childEvents.forEachIndexed { childIdx, event ->
              val isActiveChild =
                if (selectedEventKey != null) {
                  // Direct selection from click or keyboard — matches exactly one event
                  event.selectionKey() == selectedEventKey
                } else {
                  // Fallback: timestamp-range based (timeline scrub / video playback)
                  currentTimestamp >= event.timestampMs &&
                    (childIdx == childEvents.lastIndex ||
                      currentTimestamp < childEvents[childIdx + 1].timestampMs)
                }
              CombinedChildEventRow(
                event = event,
                isActive = isActiveChild,
                onClick = {
                    userHasInteracted = true
                  timelineState.scrubTimestampMs = event.timestampMs
                  timelineState.isVideoPlaying = false
                  timelineState.isSnappedToMarker = event.hasScreenshot
                  selectedEventKey = event.selectionKey()
                },
                onShowInspectUI = onShowInspectUI,
                onShowChatHistory = onShowChatHistory,
              )
              if (childIdx < childEvents.lastIndex) {
                HorizontalDivider(
                  color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.15f),
                  modifier = Modifier.padding(start = 12.dp),
                )
              }
            }

            // Objective result banner (passed/failed with LLM explanation)
            if (item is ProgressItem.ObjectiveItem && item.objective.status.isTerminal) {
              Spacer(modifier = Modifier.height(6.dp))
              ObjectiveResultBanner(
                objective = item.objective,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
              )
            }
          }
        }
      }
      // Overall summary row when session is complete
      if (overallStatus?.isInProgress != true && objectives.isNotEmpty()) {
        // Use objectives from patched progressItems so in-progress objectives that were
        // terminated by session end are correctly counted as failed
        val patchedObjectives = progressItems
          .filterIsInstance<ProgressItem.ObjectiveItem>()
          .map { it.objective }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        CombinedCompletedSummary(objectives = patchedObjectives)
      }
      // Session-level failure banner
      if (overallStatus != null) {
        SessionFailureBanner(
          overallStatus = overallStatus,
          modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
    }

    // Drag handle to resize the split between logs and video/screenshot panel
    val density = LocalDensity.current
    var videoPanelWidthPx by remember { mutableStateOf(0f) }
    var isDragging by remember { mutableStateOf(false) }
    Box(
      modifier =
        Modifier.width(16.dp)
          .fillMaxHeight()
          .pointerHoverIcon(PointerIcon.Hand)
          .pointerInput(Unit) {
            detectHorizontalDragGestures(
              onDragStart = { isDragging = true },
              onDragEnd = { isDragging = false },
              onDragCancel = { isDragging = false },
            ) { _, dragAmount ->
              videoPanelWidthPx = (videoPanelWidthPx - dragAmount).coerceAtLeast(0f)
            }
          },
      contentAlignment = Alignment.Center,
    ) {
      val handleColor = if (isDragging) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.outlineVariant
      }
      Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxHeight(),
      ) {
        // Three dots vertically as a grip indicator
        repeat(3) {
          Box(
            modifier =
              Modifier.size(4.dp)
                .background(handleColor, CircleShape),
          )
          if (it < 2) Spacer(modifier = Modifier.height(4.dp))
        }
      }
    }

    // Right-center: video/screenshot panel, resizable via drag handle
    BoxWithConstraints(modifier = Modifier.fillMaxHeight()) {
      val frameAspect = deviceAspect
        ?: if (currentFrame != null) {
          currentFrame!!.width.toFloat() / currentFrame!!.height.toFloat()
        } else DEFAULT_PHONE_ASPECT_RATIO
      // Default to half the screen; the video/screenshot should be large and prominent
      val aspectWidth = maxHeight * frameAspect
      val halfOfScreen = maxWidth / 2
      val defaultWidth = maxOf(aspectWidth, halfOfScreen).coerceIn(120.dp, maxWidth * 0.65f)
      val userWidthDp = with(density) { videoPanelWidthPx.toDp() }
      val videoColumnWidth =
        if (videoPanelWidthPx > 0f) userWidthDp.coerceIn(120.dp, maxWidth)
        else defaultWidth

      val durationMs = if (videoMetadata != null) {
        ((videoMetadata.endTimestampMs ?: effectiveEndMs) - videoMetadata.startTimestampMs)
          .coerceAtLeast(0L)
      } else {
        (effectiveEndMs - effectiveStartMs).coerceAtLeast(1L)
      }
      val positionMs = if (videoMetadata != null) {
        (currentTimestamp - videoMetadata.startTimestampMs).coerceAtLeast(0L)
      } else {
        (currentTimestamp - effectiveStartMs).coerceAtLeast(0L)
      }

      Column(
        modifier =
          Modifier.width(videoColumnWidth)
            .fillMaxHeight()
            .background(
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
              RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        // Push controls + image to vertical center
        Spacer(modifier = Modifier.weight(0.1f))
        // Playback controls directly above the screenshot/video
        VideoPlaybackControls(
          isPlaying = timelineState.isVideoPlaying,
          onPlayPauseClick = {
            if (!timelineState.isVideoPlaying) {
              val scrub = timelineState.scrubTimestampMs ?: effectiveStartMs
              val end = videoMetadata?.endTimestampMs ?: effectiveEndMs
              if (scrub >= end - TimelineConstants.END_OF_VIDEO_THRESHOLD_MS) {
                timelineState.scrubTimestampMs =
                  videoMetadata?.startTimestampMs ?: effectiveStartMs
              }
            }
            timelineState.isVideoPlaying = !timelineState.isVideoPlaying
            timelineState.isSnappedToMarker = false
            selectedEventKey = null
          },
          currentPositionMs = positionMs,
          durationMs = durationMs,
          playbackSpeed = timelineState.playbackSpeed,
          onSpeedChange = { timelineState.playbackSpeed = it },
        )

        if (videoMetadata != null) {
          VideoFramePanel(
            videoMetadata = videoMetadata,
            timelineState = timelineState,
            currentTimestamp = currentTimestamp,
            effectiveStartMs = effectiveStartMs,
            effectiveEndMs = effectiveEndMs,
            activeDriverLog = activeDriverLog,
            sessionId = sessionId,
            imageLoader = imageLoader,
            currentFrame = currentFrame,
            onFrameUpdated = { currentFrame = it },
            onShowScreenshotModal = onShowScreenshotModal,
            onShowInspectUI = onShowInspectUI,
          )
        } else {
          ScreenshotKeyframePanel(
            logs = logs,
            currentTimestamp = currentTimestamp,
            sessionId = sessionId,
            imageLoader = imageLoader,
            timelineState = timelineState,
            effectiveStartMs = effectiveStartMs,
            effectiveEndMs = effectiveEndMs,
            onShowScreenshotModal = onShowScreenshotModal,
            onShowInspectUI = onShowInspectUI,
          )
        }
        Spacer(modifier = Modifier.weight(0.1f))
      }
    }

    // Far right: vertical scrub bar
    VerticalTimelineBar(
      timelineState = timelineState,
      modifier = Modifier.padding(start = 6.dp, end = 4.dp),
    )
  }
}

// -- Child event model --

/** A child event row within a step or tool block. */
internal data class CombinedEvent(
  val timestampMs: Long,
  val relativeMs: Long,
  val type: CombinedEventType,
  val title: String,
  val detail: String?,
  val hasScreenshot: Boolean = false,
  /** Indentation depth based on TraceId grouping: LLM=0, Tool=1, DriverAction=2 */
  val depth: Int = 0,
  /** YAML representation of the tool call, shown as a code block when expanded. */
  val toolYaml: String? = null,
  /** The original log, used to show richer details for specific log types. */
  val sourceLog: TrailblazeLog? = null,
)

internal enum class CombinedEventType {
  Objective,
  DriverAction,
  ToolCall,
  LlmRequest,
  Screenshot,
  SessionStatus,
}

/** Stable key for distinguishing same-timestamp events in selection state. */
internal fun CombinedEvent.selectionKey(): String = "$timestampMs:${type.name}:$title"

/**
 * Build child events for a single progress item by filtering logs within its time window.
 * Events are ordered by timestamp and indented by TraceId: LLM Request (depth 0) →
 * Tool call (depth 1) → Driver action (depth 2). Events without a traceId sit at depth 0.
 */
internal fun buildChildEvents(
  logs: List<TrailblazeLog>,
  item: ProgressItem,
  sessionStartMs: Long,
): List<CombinedEvent> {
  val startMs = item.startedAt?.toEpochMilliseconds() ?: return emptyList()
  val endMs = item.completedAt?.toEpochMilliseconds() ?: Long.MAX_VALUE

  // Collect traceIds initiated by LLM requests so we can assign depth to related logs
  val llmTraceIds = mutableSetOf<String>()
  for (log in logs) {
    if (log is TrailblazeLog.TrailblazeLlmRequestLog) {
      llmTraceIds.add(log.traceId.traceId)
    }
  }

  val events = mutableListOf<CombinedEvent>()
  for (log in logs) {
    val tsMs = log.timestamp.toEpochMilliseconds()
    if (tsMs < startMs || tsMs > endMs) continue
    val relMs = (tsMs - sessionStartMs).coerceAtLeast(0L)
    val traceId = (log as? HasTraceId)?.traceId?.traceId
    val hasParentLlm = traceId != null && traceId in llmTraceIds

    when (log) {
      is TrailblazeLog.AgentDriverLog -> {
        events.add(
          CombinedEvent(
            timestampMs = tsMs,
            relativeMs = relMs,
            type = CombinedEventType.DriverAction,
            title = describeAction(log.action),
            detail = "${log.durationMs}ms",
            hasScreenshot = log.screenshotFile != null,
            depth = if (hasParentLlm) 2 else 0,
            sourceLog = log,
          ),
        )
      }
      is TrailblazeLog.TrailblazeToolLog -> {
        val status = if (log.successful) "succeeded" else "failed"
        events.add(
          CombinedEvent(
            timestampMs = tsMs,
            relativeMs = relMs,
            type = CombinedEventType.ToolCall,
            title = "Tool: ${log.toolName}",
            detail = "$status (${log.durationMs}ms)",
            depth = if (hasParentLlm) 1 else 0,
            toolYaml = formatToolYaml(log.toolName, log),
          ),
        )
      }
      is TrailblazeLog.TrailblazeLlmRequestLog -> {
        val usage = log.llmRequestUsageAndCost
        val detailParts = mutableListOf(log.trailblazeLlmModel.modelId)
        if (usage != null) {
          val inTok = FormattingUtils.formatCommaNumber(usage.inputTokens)
          val outTok = FormattingUtils.formatCommaNumber(usage.outputTokens)
          detailParts.add("${inTok}in / ${outTok}out")
        }
        val toolCount = log.toolOptions.size
        if (toolCount > 0) detailParts.add("$toolCount tool${if (toolCount != 1) "s" else ""}")
        detailParts.add("${log.durationMs}ms")
        events.add(
          CombinedEvent(
            timestampMs = tsMs,
            relativeMs = relMs,
            type = CombinedEventType.LlmRequest,
            title = "LLM Request",
            detail = detailParts.joinToString(" \u2022 "),
            depth = 0,
            toolYaml = formatLlmActionsYaml(log.actions),
            sourceLog = log,
          ),
        )
      }
      is TrailblazeLog.TrailblazeSnapshotLog -> {
        events.add(
          CombinedEvent(
            timestampMs = tsMs,
            relativeMs = relMs,
            type = CombinedEventType.Screenshot,
            title = "Snapshot: ${log.displayName}",
            detail = null,
            hasScreenshot = true,
            depth = 0,
          ),
        )
      }
      // Skip DelegatingTrailblazeToolLog — it's the dispatch wrapper;
      // TrailblazeToolLog is the actual result with duration and status
      is TrailblazeLog.DelegatingTrailblazeToolLog -> Unit
      else -> Unit
    }
  }
  return events
}

internal fun describeAction(action: AgentDriverAction?): String =
  when (action) {
    is AgentDriverAction.TapPoint -> "Tap (${action.x}, ${action.y})"
    is AgentDriverAction.Swipe -> "Swipe ${action.direction}"
    is AgentDriverAction.EnterText -> "Input: ${action.text}"
    is AgentDriverAction.AssertCondition -> "Assert: ${action.conditionDescription}"
    is AgentDriverAction.LaunchApp -> "Launch: ${action.appId}"
    is AgentDriverAction.Scroll -> "Scroll ${if (action.forward) "down" else "up"}"
    is AgentDriverAction.LongPressPoint -> "Long press (${action.x}, ${action.y})"
    is AgentDriverAction.BackPress -> "Back"
    is AgentDriverAction.PressHome -> "Home"
    is AgentDriverAction.HideKeyboard -> "Hide keyboard"
    is AgentDriverAction.EraseText -> "Erase ${action.characters} chars"
    is AgentDriverAction.WaitForSettle -> "Wait for settle"
    else -> action?.toString() ?: "Driver action"
  }

// -- Video frame panel --

/** Video frame panel with playback, frame extraction, and action overlays. */
@Composable
private fun ColumnScope.VideoFramePanel(
  videoMetadata: VideoMetadata,
  timelineState: SessionTimelineState,
  currentTimestamp: Long,
  effectiveStartMs: Long,
  effectiveEndMs: Long,
  activeDriverLog: TrailblazeLog.AgentDriverLog?,
  sessionId: String,
  imageLoader: ImageLoader,
  currentFrame: ImageBitmap?,
  onFrameUpdated: (ImageBitmap) -> Unit,
  onShowScreenshotModal:
    ((imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: AgentDriverAction?) -> Unit)? =
    null,
  onShowInspectUI: ((TrailblazeLog) -> Unit)? = null,
) {
  val watchVideoPath = videoMetadata.videoFilePath
  if (watchVideoPath != null && getPlatform() != Platform.WASM) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
      Text(
        text = "Watch Video \u2197",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Medium,
        modifier =
          Modifier.background(
            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
            RoundedCornerShape(4.dp),
          )
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clickable { openVideoInSystemPlayer(watchVideoPath) },
      )
    }
  }

  // Frame cache for fast scrubbing
  val frameCache = remember(videoMetadata.filePath) {
    createVideoFrameCache(videoMetadata.filePath, VIDEO_CACHE_FPS, videoMetadata.spriteInfo)
  }
  DisposableEffect(frameCache) { onDispose { frameCache.dispose() } }

  val videoPositionMs =
    (currentTimestamp - videoMetadata.startTimestampMs).coerceAtLeast(0L)

  // Load frame from sprite sheet cache (async on WASM to load embedded frames on demand)
  LaunchedEffect(videoPositionMs) {
    val frame = frameCache.getFrameAsync(videoPositionMs)
    if (frame != null) onFrameUpdated(frame)
  }

  // Auto-play: advance scrubber at playback speed
  LaunchedEffect(timelineState.isVideoPlaying, timelineState.playbackSpeed) {
    if (!timelineState.isVideoPlaying) return@LaunchedEffect
    val speed = timelineState.playbackSpeed
    val videoEndAbsMs = videoMetadata.endTimestampMs ?: effectiveEndMs
    val mark = TimeSource.Monotonic.markNow()
    val playStartAbsMs = timelineState.scrubTimestampMs ?: effectiveStartMs

    while (timelineState.isVideoPlaying) {
      val elapsed = (mark.elapsedNow().inWholeMilliseconds * speed).toLong()
      val targetAbsMs = playStartAbsMs + elapsed
      if (targetAbsMs >= videoEndAbsMs) {
        timelineState.scrubTimestampMs = videoEndAbsMs
        timelineState.isVideoPlaying = false
        break
      }
      timelineState.scrubTimestampMs = targetAbsMs
      delay(TimelineConstants.PLAYBACK_FRAME_INTERVAL_MS)
    }
  }

  // Show hi-res screenshot when snapped to a marker
  val showScreenshot =
    timelineState.isSnappedToMarker &&
      activeDriverLog != null &&
      activeDriverLog.screenshotFile != null &&
      !timelineState.isVideoPlaying

  Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
    if (showScreenshot) {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val dw = activeDriverLog!!.deviceWidth.toFloat()
        val dh = activeDriverLog.deviceHeight.toFloat()
        val imageAspect =
          if (currentFrame != null) {
            currentFrame!!.width.toFloat() / currentFrame!!.height.toFloat()
          } else if (dh > 0f) dw / dh
          else 1f
        val (renderedWidth, renderedHeight) = computeFitDimensions(imageAspect, maxWidth, maxHeight)
        val clickCoords = activeDriverLog.action as? HasClickCoordinates
        ScreenshotImage(
          sessionId = sessionId,
          screenshotFile = activeDriverLog.screenshotFile,
          deviceWidth = activeDriverLog.deviceWidth,
          deviceHeight = activeDriverLog.deviceHeight,
          clickX = clickCoords?.x,
          clickY = clickCoords?.y,
          action = activeDriverLog.action,
          modifier =
            Modifier.size(renderedWidth, renderedHeight).align(Alignment.Center),
          imageLoader = imageLoader,
          onImageClick = { imageModel, dw2, dh2, cx, cy ->
            if (onShowInspectUI != null && activeDriverLog!!.viewHierarchy != null) {
              onShowInspectUI.invoke(activeDriverLog)
            } else if (imageModel != null && onShowScreenshotModal != null) {
              onShowScreenshotModal(
                imageModel,
                dw2,
                dh2,
                cx,
                cy,
                activeDriverLog!!.action,
              )
            }
          },
        )
      }
    } else {
      BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val frameAspect = when {
          activeDriverLog != null && activeDriverLog.deviceHeight > 0 ->
            activeDriverLog.deviceWidth.toFloat() / activeDriverLog.deviceHeight.toFloat()
          currentFrame != null ->
            currentFrame!!.width.toFloat() / currentFrame!!.height.toFloat()
          else -> DEFAULT_PHONE_ASPECT_RATIO
        }
        val (renderedWidth, renderedHeight) =
          computeFitDimensions(frameAspect, maxWidth, maxHeight)
        VideoFrameWithOverlay(
          currentFrame = currentFrame,
          activeDriverLog = activeDriverLog,
          modifier = Modifier.size(renderedWidth, renderedHeight).align(Alignment.Center),
        )
      }
    }
  }
}

// -- Screenshot keyframe panel (no-video fallback) --

/**
 * When no video is available, shows the most recent screenshot at the current scrub position.
 * Supports play/pause to step through screenshots as a slideshow.
 */
@Composable
private fun ColumnScope.ScreenshotKeyframePanel(
  logs: List<TrailblazeLog>,
  currentTimestamp: Long,
  sessionId: String,
  imageLoader: ImageLoader,
  timelineState: SessionTimelineState,
  effectiveStartMs: Long,
  effectiveEndMs: Long,
  onShowScreenshotModal:
    ((imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: AgentDriverAction?) -> Unit)? =
    null,
  onShowInspectUI: ((TrailblazeLog) -> Unit)? = null,
) {
  // Build sorted list of logs that have screenshots
  val screenshotLogs = remember(logs) {
    logs.filter { it is HasScreenshot && (it as HasScreenshot).screenshotFile != null }
      .sortedBy { it.timestamp.toEpochMilliseconds() }
  }

  // Find the most recent screenshot at or before the current timestamp
  val activeScreenshotLog = remember(currentTimestamp, screenshotLogs) {
    screenshotLogs.lastOrNull { log ->
      log.timestamp.toEpochMilliseconds() <= currentTimestamp
    }
  }

  Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
    SelectableText(
      text = "${screenshotLogs.size} keyframe${if (screenshotLogs.size != 1) "s" else ""}",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  // Auto-play: step through event markers at playback speed
  LaunchedEffect(timelineState.isVideoPlaying, timelineState.playbackSpeed) {
    if (!timelineState.isVideoPlaying) return@LaunchedEffect
    val speed = timelineState.playbackSpeed
    val mark = TimeSource.Monotonic.markNow()
    val playStartAbsMs = timelineState.scrubTimestampMs ?: effectiveStartMs

    while (timelineState.isVideoPlaying) {
      val elapsed = (mark.elapsedNow().inWholeMilliseconds * speed).toLong()
      val targetAbsMs = playStartAbsMs + elapsed
      if (targetAbsMs >= effectiveEndMs) {
        timelineState.scrubTimestampMs = effectiveEndMs
        timelineState.isVideoPlaying = false
        break
      }
      timelineState.scrubTimestampMs = targetAbsMs
      delay(TimelineConstants.PLAYBACK_FRAME_INTERVAL_MS)
    }
  }

  // Screenshot display
  Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
    if (activeScreenshotLog != null && activeScreenshotLog is HasScreenshot) {
      val hasScreenshot = activeScreenshotLog as HasScreenshot
      val screenshotFile = hasScreenshot.screenshotFile
      val dw = hasScreenshot.deviceWidth
      val dh = hasScreenshot.deviceHeight

      // Resolve the screenshot for the active log
      val imageModel = resolveImageModel(sessionId, screenshotFile, imageLoader)

      if (imageModel != null) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
          val imageAspect = if (dh > 0) dw.toFloat() / dh.toFloat() else DEFAULT_PHONE_ASPECT_RATIO
          val (renderedWidth, renderedHeight) = computeFitDimensions(imageAspect, maxWidth, maxHeight)

          // Determine click coordinates for action overlay
          val action = when (activeScreenshotLog) {
            is TrailblazeLog.AgentDriverLog -> activeScreenshotLog.action
            else -> null
          }
          val clickCoords = action as? HasClickCoordinates

          ScreenshotImage(
            sessionId = sessionId,
            screenshotFile = screenshotFile,
            deviceWidth = dw,
            deviceHeight = dh,
            clickX = clickCoords?.x,
            clickY = clickCoords?.y,
            action = action,
            modifier = Modifier.size(renderedWidth, renderedHeight).align(Alignment.Center),
            imageLoader = imageLoader,
            onImageClick = { model, dw2, dh2, cx, cy ->
              // Open inspector if the log has view hierarchy, otherwise fall back to screenshot modal
              val canInspect = onShowInspectUI != null && when (activeScreenshotLog) {
                is TrailblazeLog.TrailblazeLlmRequestLog -> true
                is TrailblazeLog.TrailblazeSnapshotLog -> true
                is TrailblazeLog.AgentDriverLog -> activeScreenshotLog.viewHierarchy != null
                else -> false
              }
              if (canInspect) {
                onShowInspectUI!!.invoke(activeScreenshotLog)
              } else if (model != null && onShowScreenshotModal != null) {
                onShowScreenshotModal(model, dw2, dh2, cx, cy, action)
              }
            },
          )
        }
      } else {
        Box(
          modifier =
            Modifier.fillMaxSize()
              .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(8.dp),
              ),
          contentAlignment = Alignment.Center,
        ) {
          Text(
            "Loading screenshot...",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    } else {
      Box(
        modifier =
          Modifier.fillMaxSize()
            .border(
              width = 1.dp,
              color = MaterialTheme.colorScheme.outlineVariant,
              shape = RoundedCornerShape(8.dp),
            ),
        contentAlignment = Alignment.Center,
      ) {
        Text(
          text = "No screenshots yet",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

/** Format a tool call as proper Trailblaze YAML using the real YAML serializer.
 *  Prefixes with `- ` so the output is a valid YAML array item, ready to copy-paste. */
private fun formatToolYaml(toolName: String, log: TrailblazeLog.TrailblazeToolLog): String? {
  val tool = (log as? HasTrailblazeTool)?.trailblazeTool ?: return null
  return try {
    val raw = TrailblazeYaml.toolToYaml(toolName, tool).trimEnd()
    // Wrap as a YAML array item: prefix first line with "- ", indent continuation lines
    val lines = raw.lines()
    if (lines.isEmpty()) return "- $toolName:"
    buildString {
      append("- ")
      append(lines.first())
      for (i in 1 until lines.size) {
        append('\n')
        append("  ")
        append(lines[i])
      }
    }
  } catch (e: Exception) {
    "- $toolName: # (serialization error)"
  }
}

/** Format LLM response actions as YAML, matching the tool YAML style. */
private fun formatLlmActionsYaml(
  actions: List<TrailblazeLog.TrailblazeLlmRequestLog.Action>,
): String? {
  if (actions.isEmpty()) return null
  return try {
    buildString {
      actions.forEachIndexed { index, action ->
        if (index > 0) append('\n')
        val argsYaml = TrailblazeYaml.jsonToYaml(action.args).trimEnd()
        if (argsYaml.isBlank() || argsYaml == "{}") {
          append("- ${action.name}:")
        } else {
          append("- ${action.name}:\n")
          argsYaml.lines().forEach { line ->
            if (line.isNotBlank()) {
              append("    ")
              append(line)
              append('\n')
            }
          }
        }
      }
    }.trimEnd()
  } catch (_: Exception) {
    actions.joinToString("\n") { "- ${it.name}: # (serialization error)" }
  }
}

/** Default phone screen aspect ratio (width / height) for sizing the video column. */
internal const val DEFAULT_PHONE_ASPECT_RATIO = 0.46f // ~9:19.5
