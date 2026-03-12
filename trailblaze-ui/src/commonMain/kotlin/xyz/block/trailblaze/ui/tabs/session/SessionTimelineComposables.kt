package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.ui.utils.FormattingUtils.formatDuration

/** Pinned vertical timeline — replaces the scrollbar on the right in Timeline mode. */
@Composable
fun VerticalTimelineBar(
  timelineState: SessionTimelineState,
  modifier: Modifier = Modifier,
) {
  VerticalSessionTimeline(
    objectives = timelineState.objectives,
    logs = timelineState.logs,
    sessionStartMs = timelineState.sessionStartMs,
    sessionEndMs = timelineState.sessionEndMs,
    scrubTimestampMs = timelineState.scrubTimestampMs,
    isScrubbing = timelineState.isScrubbing,
    isInProgress = timelineState.isInProgress,
    onScrub = timelineState.onScrub,
    onScrubStart = {
      timelineState.isScrubbing = true
      timelineState.isSnappedToMarker = false
      timelineState.isVideoPlaying = false
    },
    onScrubEnd = { timelineState.isScrubbing = false },
    modifier = modifier,
  )
}

/** Pinned horizontal timeline bar — render outside the scroll container so it stays visible. */
@Composable
fun SessionTimelineBar(
  timelineState: SessionTimelineState,
  modifier: Modifier = Modifier,
) {
  SessionTimeline(
    objectives = timelineState.objectives,
    logs = timelineState.logs,
    sessionStartMs = timelineState.sessionStartMs,
    sessionEndMs = timelineState.sessionEndMs,
    scrubTimestampMs = timelineState.scrubTimestampMs,
    isScrubbing = timelineState.isScrubbing,
    isInProgress = timelineState.isInProgress,
    onScrub = timelineState.onScrub,
    onScrubStart = {
      timelineState.isScrubbing = true
      timelineState.isSnappedToMarker = false
      timelineState.isVideoPlaying = false
    },
    onScrubEnd = { timelineState.isScrubbing = false },
    onMarkerSnap = { timelineState.isSnappedToMarker = true },
    modifier = modifier,
  )
}

@Composable
internal fun SessionTimeline(
  objectives: List<ObjectiveProgress>,
  logs: List<TrailblazeLog>,
  sessionStartMs: Long,
  sessionEndMs: Long,
  scrubTimestampMs: Long?,
  isScrubbing: Boolean = false,
  isInProgress: Boolean = false,
  onScrub: (timestampMs: Long) -> Unit,
  onScrubStart: () -> Unit = {},
  onScrubEnd: () -> Unit = {},
  onMarkerSnap: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val range = (sessionEndMs - sessionStartMs).coerceAtLeast(1L)
  val ticks =
    remember(logs, sessionStartMs, sessionEndMs) {
      buildTimelineTicks(logs, sessionStartMs, sessionEndMs)
    }
  val markers =
    remember(logs, sessionStartMs, sessionEndMs) {
      buildEventMarkers(logs, sessionStartMs, sessionEndMs)
    }
  val totalElapsedMs = sessionEndMs - sessionStartMs
  val pulseAlpha = rememberPulseAlpha(isInProgress, label = "timelinePulse")
  val colors = resolveTimelineColors()

  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(MaterialTheme.colorScheme.surface)
        .padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text(
        text = "0:00",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      val textMeasurer = rememberTextMeasurer()

      Canvas(
        modifier =
          Modifier.weight(1f)
            .height(TimelineConstants.HORIZONTAL_CANVAS_HEIGHT)
            .pointerInput(onScrub, onScrubStart, onScrubEnd, onMarkerSnap, sessionStartMs, range, markers) {
              detectTapGestures { offset ->
                onScrubStart()
                val tapX = offset.x
                val nearest = markers.minByOrNull {
                  kotlin.math.abs(it.offsetFraction * size.width - tapX)
                }
                val isMarkerHit = nearest != null &&
                  kotlin.math.abs(nearest.offsetFraction * size.width - tapX) < TimelineConstants.SNAP_THRESHOLD_PX
                val ts = if (isMarkerHit) {
                  onMarkerSnap()
                  nearest!!.timestampMs
                } else {
                  val fraction = (tapX / size.width).coerceIn(0f, 1f)
                  sessionStartMs + (fraction * range).toLong()
                }
                onScrub(ts)
                onScrubEnd()
              }
            }
            .pointerInput(onScrub, onScrubStart, onScrubEnd, sessionStartMs, range) {
              detectHorizontalDragGestures(
                onDragStart = { onScrubStart() },
                onDragEnd = { onScrubEnd() },
                onDragCancel = { onScrubEnd() },
              ) { change, _ ->
                change.consume()
                val fraction = (change.position.x / size.width).coerceIn(0f, 1f)
                onScrub(sessionStartMs + (fraction * range).toLong())
              }
            },
      ) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val trackHeight = TimelineConstants.HORIZONTAL_TRACK_HEIGHT
        val labelAreaHeight = TimelineConstants.HORIZONTAL_LABEL_AREA_HEIGHT
        val trackCenterY = labelAreaHeight + (canvasHeight - labelAreaHeight) / 2f
        val trackY = trackCenterY - trackHeight / 2f

        // Background track
        drawRoundRect(
          color = colors.track,
          topLeft = Offset(0f, trackY),
          size = Size(canvasWidth, trackHeight),
          cornerRadius = CornerRadius(trackHeight / 2f),
        )

        // Objective spans
        drawObjectiveSpans(
          objectives, sessionStartMs, sessionEndMs, range, colors,
          mainAxisLength = canvasWidth,
          trackCrossStart = trackY,
          trackThickness = trackHeight,
          isHorizontal = true,
        )

        // Event ticks — colored by type
        ticks.forEach { tick ->
          val x = tick.offsetFraction * canvasWidth
          val isLarge = tick.type == TickType.Screenshot ||
            tick.type == TickType.DriverAction ||
            tick.type == TickType.LlmRequest
          val tickH = trackHeight + if (isLarge) TimelineConstants.LARGE_TICK_EXTEND else TimelineConstants.SMALL_TICK_EXTEND
          val tickTopY = trackCenterY - tickH / 2f
          drawLine(
            color = tickColor(tick.type, colors),
            start = Offset(x, tickTopY),
            end = Offset(x, tickTopY + tickH),
            strokeWidth = if (isLarge) TimelineConstants.LARGE_TICK_STROKE else TimelineConstants.SMALL_TICK_STROKE,
          )
        }

        // Event markers: duration spans on the track + icons above
        val markerRowY = trackY - 12f
        val currentTs = scrubTimestampMs ?: sessionStartMs
        val activeMarker =
          markers.lastOrNull { m ->
            currentTs >= m.timestampMs && currentTs < m.timestampMs + m.durationMs
          }

        markers.forEach { marker ->
          val mColor = markerColor(marker.actionKind)
          val mx = marker.offsetFraction * canvasWidth
          val isActive = marker === activeMarker

          // Duration span bar on the track
          val spanWidth =
            ((marker.endFraction - marker.offsetFraction) * canvasWidth).coerceAtLeast(3f)
          drawRoundRect(
            color = mColor.copy(alpha = if (isActive) 0.6f else 0.25f),
            topLeft = Offset(mx, trackY + 1f),
            size = Size(spanWidth, trackHeight - 2f),
            cornerRadius = CornerRadius(2f),
          )

          // Distinctive icon above the track
          val r = if (isActive) TimelineConstants.ACTIVE_MARKER_RADIUS else TimelineConstants.INACTIVE_MARKER_RADIUS
          drawMarkerIcon(marker.actionKind, Offset(mx, markerRowY), r, mColor)
        }

        // Label for the active marker
        if (activeMarker != null) {
          val amx = activeMarker.offsetFraction * canvasWidth
          val markerLabelStyle =
            TextStyle(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = markerColor(activeMarker.actionKind))
          val measured = textMeasurer.measure(activeMarker.label, markerLabelStyle)
          val lx = (amx - measured.size.width / 2f).coerceIn(0f, canvasWidth - measured.size.width.toFloat())
          drawText(measured, topLeft = Offset(lx, markerRowY - measured.size.height - 2f))
        }

        // Thumb
        scrubTimestampMs?.let { ts ->
          val thumbFraction = ((ts - sessionStartMs).toFloat() / range).coerceIn(0f, 1f)
          val thumbCenter = Offset(thumbFraction * canvasWidth, trackCenterY)
          drawThumb(
            thumbCenter, colors,
            innerRadius = TimelineConstants.HORIZONTAL_THUMB_INNER_RADIUS,
            borderRadius = TimelineConstants.HORIZONTAL_THUMB_OUTER_RADIUS,
          )

          // Scrub label near thumb
          val scrubLabel = findNearestEventLabel(markers, ts, range, isScrubbing)
          if (scrubLabel != null) {
            val labelStyle = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = colors.thumb)
            val measuredText = textMeasurer.measure(scrubLabel, labelStyle)
            val labelX = (thumbCenter.x - measuredText.size.width / 2f)
              .coerceIn(0f, canvasWidth - measuredText.size.width.toFloat())
            drawText(measuredText, topLeft = Offset(labelX, 0f))
          }
        }

        // Pulsing live edge indicator
        if (isInProgress) {
          drawLiveEdgePulse(
            Offset(canvasWidth, trackCenterY), colors.inProgress, pulseAlpha,
            TimelineConstants.LIVE_EDGE_RADIUS,
          )
        }
      }

      Text(
        text = if (isInProgress) "Live" else formatDuration(totalElapsedMs),
        style = MaterialTheme.typography.labelSmall,
        fontWeight = if (isInProgress) FontWeight.SemiBold else FontWeight.Normal,
        color =
          if (isInProgress) MaterialTheme.colorScheme.primary
          else MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
internal fun VerticalSessionTimeline(
  objectives: List<ObjectiveProgress>,
  logs: List<TrailblazeLog> = emptyList(),
  sessionStartMs: Long,
  sessionEndMs: Long,
  scrubTimestampMs: Long?,
  isScrubbing: Boolean = false,
  isInProgress: Boolean,
  onScrub: (timestampMs: Long) -> Unit,
  onScrubStart: () -> Unit = {},
  onScrubEnd: () -> Unit = {},
  modifier: Modifier = Modifier,
) {
  val range = (sessionEndMs - sessionStartMs).coerceAtLeast(1L)
  val ticks =
    remember(logs, sessionStartMs, sessionEndMs) {
      buildTimelineTicks(logs, sessionStartMs, sessionEndMs)
    }
  val markers =
    remember(logs, sessionStartMs, sessionEndMs) {
      buildEventMarkers(logs, sessionStartMs, sessionEndMs)
    }
  val pulseAlpha = rememberPulseAlpha(isInProgress, label = "vertPulse")
  val colors = resolveTimelineColors(trackAlpha = 0.25f, toolTickAlpha = 0.5f)
  val density = LocalDensity.current

  val scrubFraction =
    scrubTimestampMs?.let {
      ((it - sessionStartMs).toFloat() / range).coerceIn(0f, 1f)
    }
  val eventLabel = findNearestEventLabel(markers, scrubTimestampMs, range, isScrubbing)
  val textMeasurer = rememberTextMeasurer()

  BoxWithConstraints(
    modifier =
      modifier
        .fillMaxHeight()
        .width(TimelineConstants.VERTICAL_BAR_WIDTH)
        .pointerInput(onScrub, onScrubStart, onScrubEnd, sessionStartMs, range) {
          detectTapGestures { offset ->
            onScrubStart()
            val fraction = (offset.y / size.height).coerceIn(0f, 1f)
            onScrub(sessionStartMs + (fraction * range).toLong())
            onScrubEnd()
          }
        }
        .pointerInput(onScrub, onScrubStart, onScrubEnd, sessionStartMs, range) {
          detectVerticalDragGestures(
            onDragStart = { onScrubStart() },
            onDragEnd = { onScrubEnd() },
            onDragCancel = { onScrubEnd() },
          ) { change, _ ->
            change.consume()
            val fraction = (change.position.y / size.height).coerceIn(0f, 1f)
            onScrub(sessionStartMs + (fraction * range).toLong())
          }
        },
  ) {
    val boxHeightPx = with(density) { maxHeight.toPx() }
    val boxWidthPx = with(density) { maxWidth.toPx() }
    val trackWidth = TimelineConstants.VERTICAL_TRACK_WIDTH
    val trackCenterX = boxWidthPx / 2f
    val trackX = trackCenterX - trackWidth / 2f

    Canvas(modifier = Modifier.fillMaxSize()) {
      // Background track
      drawRoundRect(
        color = colors.track,
        topLeft = Offset(trackX, 0f),
        size = Size(trackWidth, boxHeightPx),
        cornerRadius = CornerRadius(trackWidth / 2f),
      )

      // Objective spans
      drawObjectiveSpans(
        objectives, sessionStartMs, sessionEndMs, range, colors,
        mainAxisLength = boxHeightPx,
        trackCrossStart = trackX,
        trackThickness = trackWidth,
        isHorizontal = false,
        spanAlpha = 0.6f,
      )

      // Colored tick marks — horizontal bars extending left from the track
      ticks.forEach { tick ->
        val tickY = tick.offsetFraction * boxHeightPx
        val alpha = spotlightAlpha(tick.offsetFraction, scrubFraction)
        val tickLength =
          when (tick.type) {
            TickType.LlmRequest -> 18f
            TickType.DriverAction -> 14f
            TickType.Screenshot -> 10f
            TickType.ToolCall -> 10f
          }
        val tickHeight = 3f
        drawRoundRect(
          color = tickColor(tick.type, colors).copy(alpha = alpha),
          topLeft = Offset(trackX - tickLength - 3f, tickY - tickHeight / 2f),
          size = Size(tickLength + 3f, tickHeight),
          cornerRadius = CornerRadius(tickHeight / 2f),
        )
      }

      // Pulsing live-edge dot at the bottom
      if (isInProgress) {
        drawLiveEdgePulse(
          Offset(trackCenterX, boxHeightPx), colors.inProgress, pulseAlpha,
          TimelineConstants.VERTICAL_LIVE_EDGE_RADIUS,
        )
      }

      // Thumb
      scrubTimestampMs?.let { ts ->
        val thumbFraction = ((ts - sessionStartMs).toFloat() / range).coerceIn(0f, 1f)
        val thumbY = thumbFraction * boxHeightPx
        val thumbCenter = Offset(trackCenterX, thumbY)

        // Soft glow behind thumb
        drawCircle(
          color = colors.thumb.copy(alpha = 0.12f),
          radius = TimelineConstants.VERTICAL_THUMB_GLOW_RADIUS,
          center = thumbCenter,
        )
        drawThumb(
          thumbCenter, colors,
          innerRadius = TimelineConstants.VERTICAL_THUMB_RADIUS,
          borderRadius = TimelineConstants.VERTICAL_THUMB_BORDER_RADIUS,
        )

        // Event label drawn to the left of the track at thumb Y
        if (eventLabel != null) {
          val labelStyle =
            TextStyle(fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = colors.thumb)
          val measured = textMeasurer.measure(eventLabel, labelStyle)
          val labelX =
            trackCenterX - TimelineConstants.VERTICAL_THUMB_BORDER_RADIUS - 10f - measured.size.width
          val labelY = (thumbY - measured.size.height / 2f)
            .coerceIn(0f, boxHeightPx - measured.size.height)
          drawRoundRect(
            color = colors.labelBackground,
            topLeft = Offset(labelX - 6f, labelY - 2f),
            size = Size(measured.size.width + 12f, measured.size.height + 4f),
            cornerRadius = CornerRadius(4f),
          )
          drawText(measured, topLeft = Offset(labelX, labelY))
        }
      }
    }
  }
}
