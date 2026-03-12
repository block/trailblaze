package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.ui.unit.dp

/** Named constants for timeline rendering and interaction thresholds. */
internal object TimelineConstants {
  // Pulse animation
  const val PULSE_MIN_ALPHA = 0.3f
  const val PULSE_MAX_ALPHA = 1.0f
  const val PULSE_TWEEN_MS = 800

  // Horizontal timeline
  val HORIZONTAL_CANVAS_HEIGHT = 64.dp
  const val HORIZONTAL_TRACK_HEIGHT = 16f
  const val HORIZONTAL_LABEL_AREA_HEIGHT = 18f
  const val HORIZONTAL_THUMB_OUTER_RADIUS = 24f
  const val HORIZONTAL_THUMB_INNER_RADIUS = 22f

  // Vertical timeline
  val VERTICAL_BAR_WIDTH = 56.dp
  const val VERTICAL_TRACK_WIDTH = 8f
  const val VERTICAL_THUMB_RADIUS = 20f
  const val VERTICAL_THUMB_BORDER_RADIUS = 24f
  const val VERTICAL_THUMB_GLOW_RADIUS = 36f

  // Marker snapping
  const val SNAP_THRESHOLD_PX = 20f
  const val SNAP_FRACTION_THRESHOLD = 0.02f
  const val SNAP_THRESHOLD_MIN_MS = 500L

  // Tick sizing
  const val LARGE_TICK_EXTEND = 12f
  const val SMALL_TICK_EXTEND = 6f
  const val LARGE_TICK_STROKE = 3f
  const val SMALL_TICK_STROKE = 2f
  const val ACTIVE_MARKER_RADIUS = 7f
  const val INACTIVE_MARKER_RADIUS = 5f

  // Playback
  const val PLAYBACK_FRAME_INTERVAL_MS = 50L
  const val FRAME_DEBOUNCE_MS = 300L
  const val END_OF_VIDEO_THRESHOLD_MS = 500L
  const val NEARBY_LOGS_WINDOW_MS = 2000L
  const val SLIDESHOW_MIN_DELAY_MS = 200L
  const val SLIDESHOW_MAX_DELAY_MS = 3000L

  // Video panel sizing
  val MIN_VIDEO_PANEL_WIDTH = 120.dp
  const val MAX_VIDEO_PANEL_WIDTH_FRACTION = 0.65f

  // Live-edge pulsing dot
  const val LIVE_EDGE_RADIUS = 6f
  const val VERTICAL_LIVE_EDGE_RADIUS = 5f

  // Spotlight alpha for vertical ticks
  const val SPOTLIGHT_MIN_ALPHA = 0.45f
  const val SPOTLIGHT_MAX_ALPHA = 1.0f
  const val SPOTLIGHT_RANGE = 0.14f

  // Keyboard navigation
  const val MARKER_JUMP_OFFSET_MS = 50L
  const val SCRUB_MATCH_THRESHOLD_MS = 500L
  const val FOCUS_REQUEST_DELAY_MS = 100L
  const val ANIMATION_SETTLE_DELAY_MS = 350L
}
