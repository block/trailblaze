package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
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
import androidx.compose.ui.unit.sp
import xyz.block.trailblaze.logs.client.TrailblazeLog

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
