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
import androidx.compose.ui.graphics.ImageBitmap
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.HasClickCoordinates
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.isInProgress
import xyz.block.trailblaze.ui.composables.ScreenshotImage
import xyz.block.trailblaze.ui.composables.createVideoFrameCache
import xyz.block.trailblaze.ui.composables.extractVideoFrame
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.images.NetworkImageLoader
import xyz.block.trailblaze.ui.openVideoInSystemPlayer
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

/**
 * Timeline view: a horizontal scrubber at the top with screenshot + action detail panels below.
 *
 * Scrubbing the timeline seeks to that moment in time and shows:
 * - Left panel: the closest screenshot with action overlay (tap point, swipe, etc.)
 * - Right panel: active objective, tool call details, and log entries around that timestamp
 */
@Composable
internal fun SessionTimelineView(
  logs: List<TrailblazeLog>,
  overallStatus: SessionStatus?,
  sessionId: String,
  videoMetadata: VideoMetadata? = null,
  imageLoader: ImageLoader = NetworkImageLoader(),
  onShowScreenshotModal:
    ((imageModel: Any?, deviceWidth: Int, deviceHeight: Int, clickX: Int?, clickY: Int?, action: AgentDriverAction?) -> Unit)? =
    null,
) {
  // Build timeline data in background
  var objectives by remember { mutableStateOf<List<ObjectiveProgress>>(emptyList()) }
  var screenshotItems by remember { mutableStateOf<List<ScreenshotTimelineItem>>(emptyList()) }
  var sessionStartMs by remember { mutableStateOf(0L) }
  var sessionEndMs by remember { mutableStateOf(0L) }

  LaunchedEffect(logs) {
    withContext(Dispatchers.Default) {
      objectives = buildObjectiveProgress(logs)
      screenshotItems = buildScreenshotTimeline(logs)
      val timestamps = logs.mapNotNull { it.timestamp.toEpochMilliseconds() }
      sessionStartMs = timestamps.minOrNull() ?: 0L
      sessionEndMs = timestamps.maxOrNull() ?: 0L
    }
  }

  if (logs.isEmpty() || sessionStartMs == 0L) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
      Text("Loading timeline...")
    }
    return
  }

  val timelineState = rememberSessionTimelineState()

  // Constrain timeline to test execution window only (first log to last log).
  // Video frames outside this window are not useful — they show pre/post-test idle time.
  val effectiveStartMs = sessionStartMs
  val effectiveEndMs = sessionEndMs

  // Populate timeline state
  LaunchedEffect(objectives, logs, effectiveStartMs, effectiveEndMs) {
    timelineState.objectives = objectives
    timelineState.logs = logs
    timelineState.sessionStartMs = effectiveStartMs
    timelineState.sessionEndMs = effectiveEndMs
    timelineState.hasObjectives = objectives.isNotEmpty()
    timelineState.isInProgress = overallStatus?.isInProgress == true
    // Auto-scrub to session start if no position set
    if (timelineState.scrubTimestampMs == null) {
      timelineState.scrubTimestampMs = effectiveStartMs
    }
  }

  // Screenshot slideshow state — declared early so scrub handler can stop it
  var isSlideshowPlaying by remember { mutableStateOf(false) }

  // Set scrub handler — also sends seek requests to the video player when video is available.
  // isSnappedToMarker is reset in onScrubStart (before onMarkerSnap can set it back),
  // so we don't reset it here — that would undo marker snap taps.
  timelineState.onScrub = { ts ->
    timelineState.scrubTimestampMs = ts
    // Stop playback when user manually scrubs
    isSlideshowPlaying = false
    if (videoMetadata != null) {
      timelineState.isVideoPlaying = false
    }
  }

  val currentTimestamp = timelineState.scrubTimestampMs ?: sessionStartMs

  // Find the closest screenshot to the current timestamp
  val closestScreenshot =
    remember(currentTimestamp, screenshotItems) {
      screenshotItems.minByOrNull {
        kotlin.math.abs(it.timestamp.toEpochMilliseconds() - currentTimestamp)
      }
    }

  // Find the active objective at the current timestamp
  val activeObjective =
    remember(currentTimestamp, objectives) {
      objectives.lastOrNull { obj ->
        val startMs = obj.startedAt?.toEpochMilliseconds() ?: return@lastOrNull false
        startMs <= currentTimestamp
      }
    }

  // Find nearby log entries (within a window around the scrub position)
  val nearbyLogs =
    remember(currentTimestamp, logs) {
      val windowMs = TimelineConstants.NEARBY_LOGS_WINDOW_MS
      logs.filter { log ->
        val logMs = log.timestamp.toEpochMilliseconds()
        logMs in (currentTimestamp - windowMs)..(currentTimestamp + windowMs)
      }
    }

  // Find the tool log at or just before the current timestamp
  val activeToolLog =
    remember(currentTimestamp, logs) {
      logs
        .filterIsInstance<TrailblazeLog.TrailblazeToolLog>()
        .lastOrNull { it.timestamp.toEpochMilliseconds() <= currentTimestamp }
    }

  // Find the active driver action near the current timestamp for video overlay.
  // Show the annotation when the scrubber is within OVERLAY_WINDOW_MS after the action.
  val activeDriverLog =
    remember(currentTimestamp, logs) {
      logs
        .filterIsInstance<TrailblazeLog.AgentDriverLog>()
        .lastOrNull { log ->
          val logMs = log.timestamp.toEpochMilliseconds()
          logMs <= currentTimestamp && (currentTimestamp - logMs) < log.durationMs
        }
    }

  // Screenshot slideshow: auto-advance through screenshot timestamps when playing without video
  LaunchedEffect(isSlideshowPlaying, screenshotItems) {
    if (!isSlideshowPlaying || screenshotItems.isEmpty()) return@LaunchedEffect
    val sorted = screenshotItems.sortedBy { it.timestamp }
    // Find the first screenshot after the current scrub position
    val startIdx =
      sorted.indexOfFirst { it.timestamp.toEpochMilliseconds() > (timelineState.scrubTimestampMs ?: 0L) }
        .let { if (it < 0) 0 else it }
    for (i in startIdx until sorted.size) {
      if (!isSlideshowPlaying) break
      val item = sorted[i]
      timelineState.scrubTimestampMs = item.timestamp.toEpochMilliseconds()
      // Delay proportional to real time gap, capped so it doesn't freeze too long
      val nextIdx = i + 1
      val delayMs = if (nextIdx < sorted.size) {
        (sorted[nextIdx].timestamp.toEpochMilliseconds() - item.timestamp.toEpochMilliseconds())
          .coerceIn(TimelineConstants.SLIDESHOW_MIN_DELAY_MS, TimelineConstants.SLIDESHOW_MAX_DELAY_MS)
      } else 500L
      delay(delayMs)
    }
    isSlideshowPlaying = false
  }

  // Event markers for arrow key navigation (jump between driver/tool events)
  val eventMarkers =
    remember(logs, effectiveStartMs, effectiveEndMs) {
      buildEventMarkers(logs, effectiveStartMs, effectiveEndMs)
    }

  // Focus requester for keyboard handling (spacebar play/pause)
  val focusRequester = remember { FocusRequester() }
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Column(
    modifier = Modifier.fillMaxSize()
      .focusRequester(focusRequester)
      .focusable()
      .onPreviewKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
        when (event.key) {
          Key.Spacebar -> {
            if (videoMetadata != null) {
              if (!timelineState.isVideoPlaying) {
                val scrub = timelineState.scrubTimestampMs ?: effectiveStartMs
                val videoEnd = videoMetadata.endTimestampMs ?: effectiveEndMs
                if (scrub >= videoEnd - TimelineConstants.END_OF_VIDEO_THRESHOLD_MS) {
                  timelineState.scrubTimestampMs = videoMetadata.startTimestampMs
                }
              }
              timelineState.isVideoPlaying = !timelineState.isVideoPlaying
              timelineState.isSnappedToMarker = false
            } else {
              isSlideshowPlaying = !isSlideshowPlaying
            }
            true
          }
          Key.DirectionRight -> {
            // Jump to next event marker
            val now = currentTimestamp
            val next = eventMarkers.firstOrNull { it.timestampMs > now + TimelineConstants.MARKER_JUMP_OFFSET_MS }
            if (next != null) {
              timelineState.isVideoPlaying = false
              isSlideshowPlaying = false
              timelineState.scrubTimestampMs = next.timestampMs
              timelineState.isSnappedToMarker = true
            }
            true
          }
          Key.DirectionLeft -> {
            // Jump to previous event marker
            val now = currentTimestamp
            val prev = eventMarkers.lastOrNull { it.timestampMs < now - TimelineConstants.MARKER_JUMP_OFFSET_MS }
            if (prev != null) {
              timelineState.isVideoPlaying = false
              isSlideshowPlaying = false
              timelineState.scrubTimestampMs = prev.timestampMs
              timelineState.isSnappedToMarker = true
            }
            true
          }
          else -> false
        }
      },
  ) {
    // Horizontal timeline scrubber at the top
    SessionTimelineBar(
      timelineState = timelineState,
      modifier = Modifier.fillMaxWidth(),
    )

    HorizontalDivider()

    // Main content: screenshot + details split panel
    Row(modifier = Modifier.fillMaxSize().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
      // Left panel: video player (if available) or screenshot with action overlay
      Column(
        modifier =
          Modifier.weight(1f)
            .fillMaxHeight()
            .background(
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
              RoundedCornerShape(8.dp),
            )
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
      ) {
        if (videoMetadata != null) {
          // Video frame mode: extract frames via ffmpeg, overlay driver actions
          val videoDurationMs =
            ((videoMetadata.endTimestampMs ?: effectiveEndMs) -
              videoMetadata.startTimestampMs).coerceAtLeast(0L)

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
          ) {
            VideoPlaybackControls(
              isPlaying = timelineState.isVideoPlaying,
              onPlayPauseClick = {
                if (!timelineState.isVideoPlaying) {
                  val scrub = timelineState.scrubTimestampMs ?: effectiveStartMs
                  val videoEnd = videoMetadata.endTimestampMs ?: effectiveEndMs
                  // If at/past the end, restart from beginning
                  if (scrub >= videoEnd - TimelineConstants.END_OF_VIDEO_THRESHOLD_MS) {
                    timelineState.scrubTimestampMs = videoMetadata.startTimestampMs
                  }
                }
                timelineState.isVideoPlaying = !timelineState.isVideoPlaying
                timelineState.isSnappedToMarker = false
              },
              currentPositionMs =
                (currentTimestamp - videoMetadata.startTimestampMs).coerceAtLeast(0L),
              durationMs = videoDurationMs,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
              text = "Watch Video \u2197",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              fontWeight = FontWeight.Medium,
              modifier = Modifier
                .padding(end = 4.dp)
                .background(
                  MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                  RoundedCornerShape(4.dp),
                )
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .clickable { openVideoInSystemPlayer(videoMetadata.filePath) },
            )
          }

          // Pre-extract all frames in the background for fast scrubbing/playback
          val frameCache = remember(videoMetadata.filePath) {
            createVideoFrameCache(videoMetadata.filePath, VIDEO_CACHE_FPS)
          }
          DisposableEffect(frameCache) { onDispose { frameCache.dispose() } }

          val videoPositionMs =
            (currentTimestamp - videoMetadata.startTimestampMs).coerceAtLeast(0L)
          var currentFrame by remember { mutableStateOf<ImageBitmap?>(null) }

          // Fast low-res frame from cache (async on WASM to load embedded frames on demand)
          LaunchedEffect(videoPositionMs) {
            val frame = frameCache.getFrameAsync(videoPositionMs)
            if (frame != null) currentFrame = frame
          }

          // Upgrade to full-res native frame when paused and idle
          LaunchedEffect(
            videoPositionMs,
            timelineState.isVideoPlaying,
            timelineState.isScrubbing,
          ) {
            if (timelineState.isVideoPlaying || timelineState.isScrubbing) return@LaunchedEffect
            delay(TimelineConstants.FRAME_DEBOUNCE_MS) // wait for scrubbing to settle
            val hiRes = extractVideoFrame(videoMetadata.filePath, videoPositionMs)
            if (hiRes != null) currentFrame = hiRes
          }

          // Auto-play: advance scrubber in real time at 20fps
          LaunchedEffect(timelineState.isVideoPlaying) {
            if (!timelineState.isVideoPlaying) return@LaunchedEffect
            val videoEndAbsMs = videoMetadata.endTimestampMs ?: effectiveEndMs
            val mark = TimeSource.Monotonic.markNow()
            val playStartAbsMs = timelineState.scrubTimestampMs ?: effectiveStartMs

            while (timelineState.isVideoPlaying) {
              val elapsed = mark.elapsedNow().inWholeMilliseconds
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

          // Display: high-res screenshot only when user explicitly snapped to a marker
          val showScreenshot =
            timelineState.isSnappedToMarker &&
              activeDriverLog != null &&
              activeDriverLog.screenshotFile != null &&
              !timelineState.isVideoPlaying

          Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            if (showScreenshot) {
              // High-res screenshot constrained to match video frame display area
              BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                val dw = activeDriverLog!!.deviceWidth.toFloat()
                val dh = activeDriverLog.deviceHeight.toFloat()
                val imageAspect =
                  if (currentFrame != null) {
                    currentFrame!!.width.toFloat() / currentFrame!!.height.toFloat()
                  } else if (dh > 0f) dw / dh else 1f
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
                  modifier = Modifier
                    .size(renderedWidth, renderedHeight)
                    .align(Alignment.Center),
                  imageLoader = imageLoader,
                  onImageClick = { imageModel, dw2, dh2, cx, cy ->
                    if (imageModel != null && onShowScreenshotModal != null) {
                      onShowScreenshotModal(imageModel, dw2, dh2, cx, cy, activeDriverLog.action)
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

        } else {
          // Screenshot slideshow mode: play/pause + screenshot display
          val elapsed = (currentTimestamp - sessionStartMs).coerceAtLeast(0L)
          val totalDuration = (sessionEndMs - sessionStartMs).coerceAtLeast(0L)
          VideoPlaybackControls(
            isPlaying = isSlideshowPlaying,
            onPlayPauseClick = { isSlideshowPlaying = !isSlideshowPlaying },
            currentPositionMs = elapsed,
            durationMs = totalDuration,
          )

          if (closestScreenshot != null) {
            val screenshotElapsed =
              (closestScreenshot.timestamp.toEpochMilliseconds() - sessionStartMs).coerceAtLeast(0L)
            Text(
              text = "${closestScreenshot.label} @ ${formatDuration(screenshotElapsed)}",
              style = MaterialTheme.typography.labelMedium,
              fontWeight = FontWeight.Medium,
              modifier = Modifier.padding(bottom = 4.dp),
            )

            ScreenshotImage(
              sessionId = sessionId,
              screenshotFile = closestScreenshot.screenshotFile,
              deviceWidth = closestScreenshot.deviceWidth,
              deviceHeight = closestScreenshot.deviceHeight,
              clickX = closestScreenshot.clickX,
              clickY = closestScreenshot.clickY,
              action = closestScreenshot.action,
              modifier = Modifier.fillMaxWidth(),
              imageLoader = imageLoader,
              onImageClick = { imageModel, dw, dh, cx, cy ->
                if (imageModel != null && onShowScreenshotModal != null) {
                  onShowScreenshotModal(
                    imageModel,
                    dw,
                    dh,
                    cx,
                    cy,
                    closestScreenshot.action,
                  )
                }
              },
            )

            closestScreenshot.toolCallName?.let { toolName ->
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = toolName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
              )
            }
          } else {
            Text(
              text = "No screenshot at this time",
              style = MaterialTheme.typography.bodyMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }

      // Right panel: objective + action details + log entries
      Column(
        modifier =
          Modifier.weight(1f)
            .fillMaxHeight()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        // Current position indicator
        val elapsed = (currentTimestamp - sessionStartMs).coerceAtLeast(0L)
        Text(
          text = "Position: ${formatDuration(elapsed)}",
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
        )

        // Active objective
        if (activeObjective != null) {
          TimelineDetailCard(
            title = "Step",
            color = objectiveStatusColor(activeObjective.status),
          ) {
            Text(
              text = activeObjective.prompt,
              style = MaterialTheme.typography.bodyMedium,
            )
            Text(
              text = activeObjective.status.label,
              style = MaterialTheme.typography.labelSmall,
              color = objectiveStatusColor(activeObjective.status),
              fontWeight = FontWeight.Bold,
            )
          }
        }

        // Active tool call
        if (activeToolLog != null) {
          TimelineDetailCard(
            title = "Tool: ${activeToolLog.toolName}",
            color = if (activeToolLog.successful) SessionProgressColors.succeeded else MaterialTheme.colorScheme.error,
          ) {
            Text(
              text = if (activeToolLog.successful) "Succeeded" else "Failed",
              style = MaterialTheme.typography.labelSmall,
              fontWeight = FontWeight.Bold,
              color = if (activeToolLog.successful) SessionProgressColors.succeeded else MaterialTheme.colorScheme.error,
            )
            activeToolLog.durationMs?.let { dur ->
              Text(
                text = "Duration: ${dur}ms",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        // Nearby log entries
        if (nearbyLogs.isNotEmpty()) {
          HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
          Text(
            text = "Events",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
          )
          nearbyLogs.forEach { log ->
            TimelineLogEntry(log = log, sessionStartMs = sessionStartMs, currentTimestamp = currentTimestamp)
          }
        }
      }
    }
  }
}

@Composable
private fun TimelineDetailCard(
  title: String,
  color: androidx.compose.ui.graphics.Color,
  content: @Composable () -> Unit,
) {
  Column(
    modifier =
      Modifier.fillMaxWidth()
        .border(
          width = 1.dp,
          color = color.copy(alpha = 0.5f),
          shape = RoundedCornerShape(8.dp),
        )
        .background(color.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
        .padding(12.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Text(
      text = title,
      style = MaterialTheme.typography.labelMedium,
      fontWeight = FontWeight.Bold,
      color = color,
    )
    content()
  }
}

@Composable
private fun TimelineLogEntry(
  log: TrailblazeLog,
  sessionStartMs: Long,
  currentTimestamp: Long,
) {
  val logMs = log.timestamp.toEpochMilliseconds()
  val elapsed = (logMs - sessionStartMs).coerceAtLeast(0L)
  val isAtScrub = kotlin.math.abs(logMs - currentTimestamp) < TimelineConstants.SCRUB_MATCH_THRESHOLD_MS
  val (label, detail) = logSummary(log)

  Row(
    modifier =
      Modifier.fillMaxWidth()
        .background(
          if (isAtScrub) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
          else MaterialTheme.colorScheme.surface,
          RoundedCornerShape(4.dp),
        )
        .padding(horizontal = 8.dp, vertical = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = formatDuration(elapsed),
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.width(56.dp),
    )
    Spacer(modifier = Modifier.width(8.dp))
    Column(modifier = Modifier.weight(1f)) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isAtScrub) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (detail != null) {
        Text(
          text = detail,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

private fun logSummary(log: TrailblazeLog): Pair<String, String?> =
  when (log) {
    is TrailblazeLog.TrailblazeToolLog ->
      "Tool: ${log.toolName}" to if (log.successful) "succeeded (${log.durationMs}ms)" else "failed"
    is TrailblazeLog.AgentDriverLog -> {
      val actionDesc =
        when (val a = log.action) {
          is AgentDriverAction.TapPoint -> "Tap (${a.x}, ${a.y})"
          is AgentDriverAction.Swipe -> "Swipe ${a.direction}"
          is AgentDriverAction.EnterText -> "Input: ${a.text}"
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
  playbackSpeed: Float = 2f,
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

@Composable
private fun objectiveStatusColor(status: ObjectiveStatus): androidx.compose.ui.graphics.Color =
  when (status) {
    ObjectiveStatus.Pending -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
    ObjectiveStatus.Succeeded -> SessionProgressColors.succeeded
    ObjectiveStatus.Failed -> MaterialTheme.colorScheme.error
    ObjectiveStatus.InProgress -> MaterialTheme.colorScheme.primary
  }

/** Builds the chronological screenshot timeline from session logs. */
internal fun buildScreenshotTimeline(logs: List<TrailblazeLog>): List<ScreenshotTimelineItem> {
  val items = mutableListOf<ScreenshotTimelineItem>()
  logs.forEach { log ->
    when (log) {
      is TrailblazeLog.AgentDriverLog -> {
        if (log.screenshotFile != null) {
          val clickCoords = (log.action as? HasClickCoordinates)
          items.add(
            ScreenshotTimelineItem(
              timestamp = log.timestamp,
              screenshotFile = log.screenshotFile,
              deviceWidth = log.deviceWidth,
              deviceHeight = log.deviceHeight,
              label = logSummary(log).first,
              action = log.action,
              clickX = clickCoords?.x,
              clickY = clickCoords?.y,
              sourceLog = log,
            ),
          )
        }
      }
      is TrailblazeLog.TrailblazeSnapshotLog -> {
        items.add(
          ScreenshotTimelineItem(
            timestamp = log.timestamp,
            screenshotFile = log.screenshotFile,
            deviceWidth = log.deviceWidth,
            deviceHeight = log.deviceHeight,
            label = "Snapshot: ${log.displayName}",
            action = null,
            clickX = null,
            clickY = null,
            sourceLog = log,
          ),
        )
      }
      else -> Unit
    }
  }
  return items.sortedBy { it.timestamp }
}

/** Target frame rate for the pre-extracted video frame cache. */
internal const val VIDEO_CACHE_FPS = 10

