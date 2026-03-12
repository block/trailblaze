package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Resolved color palette for timeline rendering. Computed once per composition from the theme so
 * Canvas draw calls don't need theme access.
 */
internal data class TimelineColors(
  val track: Color,
  val success: Color,
  val fail: Color,
  val inProgress: Color,
  val toolTick: Color,
  val thumb: Color,
  val thumbBorder: Color,
  val labelBackground: Color,
)

/** Resolve timeline colors from the current MaterialTheme. */
@Composable
internal fun resolveTimelineColors(trackAlpha: Float = 0.3f, toolTickAlpha: Float = 0.4f) =
  TimelineColors(
    track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = trackAlpha),
    success = SessionProgressColors.succeeded,
    fail = MaterialTheme.colorScheme.error,
    inProgress = MaterialTheme.colorScheme.primary,
    toolTick = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = toolTickAlpha),
    thumb = MaterialTheme.colorScheme.primary,
    thumbBorder = MaterialTheme.colorScheme.surface,
    labelBackground = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
  )

/** Pulsing alpha animation for the live-edge indicator. Returns 0f when not in progress. */
@Composable
internal fun rememberPulseAlpha(isInProgress: Boolean, label: String = "pulse"): Float {
  if (!isInProgress) return 0f
  val transition = rememberInfiniteTransition(label = "${label}Transition")
  val alpha by
    transition.animateFloat(
      initialValue = TimelineConstants.PULSE_MIN_ALPHA,
      targetValue = TimelineConstants.PULSE_MAX_ALPHA,
      animationSpec =
        infiniteRepeatable(
          animation = tween(durationMillis = TimelineConstants.PULSE_TWEEN_MS),
          repeatMode = RepeatMode.Reverse,
        ),
      label = "${label}Alpha",
    )
  return alpha
}

/**
 * Find the label of the nearest event marker within the snap threshold of [scrubTimestampMs].
 * Returns null if no marker is close enough or if not currently scrubbing.
 */
internal fun findNearestEventLabel(
  markers: List<EventMarker>,
  scrubTimestampMs: Long?,
  range: Long,
  isScrubbing: Boolean,
): String? {
  if (!isScrubbing || scrubTimestampMs == null || markers.isEmpty()) return null
  val nearest = markers.minByOrNull { kotlin.math.abs(it.timestampMs - scrubTimestampMs) }
    ?: return null
  val snapThresholdMs =
    (range * TimelineConstants.SNAP_FRACTION_THRESHOLD).toLong()
      .coerceAtLeast(TimelineConstants.SNAP_THRESHOLD_MIN_MS)
  return if (kotlin.math.abs(nearest.timestampMs - scrubTimestampMs) < snapThresholdMs) {
    nearest.label
  } else null
}

/** Resolve the color for a tick based on its type, using the provided palette. */
internal fun tickColor(type: TickType, colors: TimelineColors): Color =
  when (type) {
    TickType.Screenshot -> SessionProgressColors.screenshotTick
    TickType.LlmRequest -> SessionProgressColors.llmTick
    TickType.DriverAction -> SessionProgressColors.driverTick
    TickType.ToolCall -> colors.toolTick
  }

/** Resolve the color for an event marker based on its action kind. */
internal fun markerColor(kind: ActionKind): Color =
  when (kind) {
    ActionKind.Tap -> SessionProgressColors.markerTap
    ActionKind.Swipe -> SessionProgressColors.markerSwipe
    ActionKind.Assert -> SessionProgressColors.markerAssert
    ActionKind.Input -> SessionProgressColors.markerInput
    ActionKind.Navigation -> SessionProgressColors.markerNav
    ActionKind.Tool -> SessionProgressColors.markerTool
    ActionKind.Screenshot -> SessionProgressColors.markerScreenshot
  }

// ---------------------------------------------------------------------------
// Shared DrawScope helpers
// ---------------------------------------------------------------------------

/** Draw objective progress spans on a timeline track. Works for both orientations. */
internal fun DrawScope.drawObjectiveSpans(
  objectives: List<ObjectiveProgress>,
  sessionStartMs: Long,
  sessionEndMs: Long,
  range: Long,
  colors: TimelineColors,
  /** Main-axis length (width for horizontal, height for vertical). */
  mainAxisLength: Float,
  /** Track position on the cross axis (trackY for horizontal, trackX for vertical). */
  trackCrossStart: Float,
  /** Track thickness (height for horizontal, width for vertical). */
  trackThickness: Float,
  isHorizontal: Boolean,
  spanAlpha: Float = 0.7f,
) {
  objectives.forEach { obj ->
    val objStartMs = obj.startedAt?.toEpochMilliseconds() ?: return@forEach
    val objEndMs = obj.completedAt?.toEpochMilliseconds() ?: sessionEndMs
    val startFraction = ((objStartMs - sessionStartMs).toFloat() / range).coerceIn(0f, 1f)
    val endFraction = ((objEndMs - sessionStartMs).toFloat() / range).coerceIn(0f, 1f)
    val spanColor =
      when (obj.status) {
        ObjectiveStatus.Pending -> colors.inProgress.copy(alpha = 0.2f)
        ObjectiveStatus.Succeeded -> colors.success
        ObjectiveStatus.Failed -> colors.fail
        ObjectiveStatus.InProgress -> colors.inProgress
      }
    val spanStart = startFraction * mainAxisLength
    val spanExtent = ((endFraction - startFraction) * mainAxisLength).coerceAtLeast(2f)
    val topLeft =
      if (isHorizontal) Offset(spanStart, trackCrossStart)
      else Offset(trackCrossStart, spanStart)
    val size =
      if (isHorizontal) Size(spanExtent, trackThickness)
      else Size(trackThickness, spanExtent)
    drawRoundRect(
      color = spanColor.copy(alpha = spanAlpha),
      topLeft = topLeft,
      size = size,
      cornerRadius = CornerRadius(trackThickness / 2f),
    )
  }
}

/**
 * Draw the scrub thumb (border circle + filled circle). Works for both orientations by taking the
 * center point directly.
 */
internal fun DrawScope.drawThumb(
  center: Offset,
  colors: TimelineColors,
  innerRadius: Float,
  borderRadius: Float,
) {
  drawCircle(color = colors.thumbBorder, radius = borderRadius, center = center)
  drawCircle(color = colors.thumb, radius = innerRadius, center = center)
}

/** Draw a pulsing live-edge dot at the given position. */
internal fun DrawScope.drawLiveEdgePulse(
  center: Offset,
  color: Color,
  pulseAlpha: Float,
  radius: Float,
) {
  drawCircle(color = color.copy(alpha = pulseAlpha), radius = radius, center = center)
}

// ---------------------------------------------------------------------------
// Marker icon drawing (used by horizontal timeline)
// ---------------------------------------------------------------------------

/** Draw a distinctive icon for an event marker at the given center point. */
internal fun DrawScope.drawMarkerIcon(
  actionKind: ActionKind,
  center: Offset,
  radius: Float,
  color: Color,
) {
  // White border behind the icon
  drawCircle(color = Color.White, radius = radius + 2f, center = center)

  when (actionKind) {
    ActionKind.Tap -> {
      drawCircle(color = color, radius = radius, center = center, style = Stroke(2f))
      drawCircle(color = color, radius = radius * 0.35f, center = center)
    }
    ActionKind.Swipe -> {
      drawCircle(color = color.copy(alpha = 0.15f), radius = radius, center = center)
      val hw = radius * 0.6f
      drawLine(color, Offset(center.x - hw, center.y), Offset(center.x + hw, center.y), 2f)
      val ah = radius * 0.35f
      drawLine(color, Offset(center.x + hw, center.y), Offset(center.x + hw - ah, center.y - ah), 2f)
      drawLine(color, Offset(center.x + hw, center.y), Offset(center.x + hw - ah, center.y + ah), 2f)
    }
    ActionKind.Assert -> {
      drawCircle(color = color.copy(alpha = 0.15f), radius = radius, center = center)
      val path = Path().apply {
        moveTo(center.x - radius * 0.4f, center.y)
        lineTo(center.x - radius * 0.05f, center.y + radius * 0.35f)
        lineTo(center.x + radius * 0.45f, center.y - radius * 0.3f)
      }
      drawPath(path, color, style = Stroke(2f))
    }
    ActionKind.Tool -> {
      val hs = radius * 0.35f
      drawCircle(color = color.copy(alpha = 0.15f), radius = radius, center = center)
      drawRect(color, Offset(center.x - hs, center.y - hs), Size(hs * 2, hs * 2), style = Stroke(1.5f))
      drawLine(color, Offset(center.x + hs, center.y + hs), Offset(center.x + radius * 0.6f, center.y + radius * 0.6f), 2f)
    }
    ActionKind.Screenshot -> {
      drawCircle(color = color.copy(alpha = 0.15f), radius = radius, center = center)
      val bw = radius * 0.7f
      val bh = radius * 0.5f
      drawRect(color, Offset(center.x - bw, center.y - bh + 1f), Size(bw * 2, bh * 2), style = Stroke(1.5f))
      drawCircle(color = color, radius = radius * 0.2f, center = center)
    }
    ActionKind.Input -> {
      drawCircle(color = color.copy(alpha = 0.15f), radius = radius, center = center)
      val vh = radius * 0.5f
      drawLine(color, Offset(center.x, center.y - vh), Offset(center.x, center.y + vh), 2f)
      val hh = radius * 0.25f
      drawLine(color, Offset(center.x - hh, center.y - vh), Offset(center.x + hh, center.y - vh), 1.5f)
      drawLine(color, Offset(center.x - hh, center.y + vh), Offset(center.x + hh, center.y + vh), 1.5f)
    }
    ActionKind.Navigation -> {
      drawCircle(color = color.copy(alpha = 0.15f), radius = radius, center = center)
      val path = Path().apply {
        moveTo(center.x - radius * 0.3f, center.y - radius * 0.4f)
        lineTo(center.x + radius * 0.45f, center.y)
        lineTo(center.x - radius * 0.3f, center.y + radius * 0.4f)
        close()
      }
      drawPath(path, color)
    }
  }
}

/**
 * Compute spotlight alpha for a tick at [tickFraction] relative to [scrubFraction]. Near the scrub
 * position: full brightness. Far away: dimmed. Uses cosine interpolation for smooth falloff.
 */
internal fun spotlightAlpha(
  tickFraction: Float,
  scrubFraction: Float?,
  minAlpha: Float = TimelineConstants.SPOTLIGHT_MIN_ALPHA,
  maxAlpha: Float = TimelineConstants.SPOTLIGHT_MAX_ALPHA,
  range: Float = TimelineConstants.SPOTLIGHT_RANGE,
): Float {
  if (scrubFraction == null) return 0.6f
  val distance = kotlin.math.abs(tickFraction - scrubFraction)
  if (distance >= range) return minAlpha
  val t = (1f + kotlin.math.cos((distance / range) * kotlin.math.PI.toFloat())) / 2f
  return minAlpha + (maxAlpha - minAlpha) * t
}
