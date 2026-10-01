package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.ui.unit.dp

/** Named constants for timeline rendering and interaction thresholds. */
internal object TimelineConstants {
  // Pulse animation
  const val PULSE_MIN_ALPHA = 0.3f
  const val PULSE_MAX_ALPHA = 1.0f
  const val PULSE_TWEEN_MS = 800

  // Vertical timeline
  val VERTICAL_BAR_WIDTH = 56.dp
  const val VERTICAL_TRACK_WIDTH = 8f
  const val VERTICAL_THUMB_RADIUS = 20f
  const val VERTICAL_THUMB_BORDER_RADIUS = 24f
  const val VERTICAL_THUMB_GLOW_RADIUS = 36f

  // Marker snapping
  const val SNAP_FRACTION_THRESHOLD = 0.02f
  const val SNAP_THRESHOLD_MIN_MS = 500L

  // Playback
  const val PLAYBACK_FRAME_INTERVAL_MS = 50L
  const val END_OF_VIDEO_THRESHOLD_MS = 500L

  // Live-edge pulsing dot
  const val VERTICAL_LIVE_EDGE_RADIUS = 5f

  // Spotlight alpha for vertical ticks
  const val SPOTLIGHT_MIN_ALPHA = 0.45f
  const val SPOTLIGHT_MAX_ALPHA = 1.0f
  const val SPOTLIGHT_RANGE = 0.14f

  // Keyboard navigation
  const val FOCUS_REQUEST_DELAY_MS = 100L
  const val ANIMATION_SETTLE_DELAY_MS = 350L
}
