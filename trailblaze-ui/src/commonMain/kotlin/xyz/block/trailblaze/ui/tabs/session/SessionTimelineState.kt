package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import xyz.block.trailblaze.logs.client.TrailblazeLog

/** Session data: objectives, logs, and time bounds. */
class SessionDataState internal constructor() {
  internal var objectives: List<ObjectiveProgress> by mutableStateOf(emptyList())
  internal var logs: List<TrailblazeLog> by mutableStateOf(emptyList())
  internal var sessionStartMs: Long by mutableStateOf(0L)
  internal var sessionEndMs: Long by mutableStateOf(0L)
  internal var hasObjectives: Boolean by mutableStateOf(false)
  internal var isInProgress: Boolean by mutableStateOf(false)
}

/** Scrubber position, gesture state, and marker snapping. */
class ScrubberState internal constructor() {
  internal var scrubTimestampMs: Long? by mutableStateOf(null)
  internal var onScrub: (Long) -> Unit by mutableStateOf({})

  /** True while a scrub gesture is actively dragging. Prevents scroll→scrub echo. */
  internal var isScrubbing: Boolean by mutableStateOf(false)

  /** True when the user explicitly navigated to an event marker (click or arrow key). */
  internal var isSnappedToMarker: Boolean by mutableStateOf(false)
}

/** Scroll position sync between the timeline and the steps list. */
class ScrollSyncState internal constructor() {
  internal var scrollState: ScrollState? = null

  /** Pixel Y offsets of each objective row within the steps Column. */
  internal val objectiveOffsets = mutableStateMapOf<Int, Int>()

  /** Pixel Y offset of the steps Column within the scrollable content. */
  internal var stepsColumnOffset: Int by mutableStateOf(0)
}

/** Video playback state: playing, speed, and seek requests. */
class VideoPlaybackState internal constructor() {
  internal var isVideoPlaying: Boolean by mutableStateOf(false)
  internal var videoDurationMs: Long by mutableStateOf(0L)

  /** Playback speed multiplier. Cycles through 0.25x, 0.5x, 1x, 1.5x, 2x, 3x. */
  internal var playbackSpeed: Float by mutableStateOf(1f)

  /**
   * Seek request for the video player (ms from video start). Set when the user scrubs/taps the
   * timeline. Cleared (null) when video playback is driving the scrubber position instead.
   */
  internal var videoSeekRequestMs: Long? by mutableStateOf(null)
}

/**
 * State holder for timeline scrubber, shared between the progress view and the pinned bar.
 *
 * Composed of focused sub-states. For backward compatibility, properties are delegated so existing
 * call sites continue to work unchanged. New code should prefer accessing the sub-state directly
 * (e.g. `timelineState.scrubber.isScrubbing`).
 */
class SessionTimelineState internal constructor() {
  val sessionData = SessionDataState()
  val scrubber = ScrubberState()
  val scrollSync = ScrollSyncState()
  val video = VideoPlaybackState()

  // --- Delegated properties for backward compatibility ---

  internal var objectives by sessionData::objectives
  internal var logs by sessionData::logs
  internal var sessionStartMs by sessionData::sessionStartMs
  internal var sessionEndMs by sessionData::sessionEndMs
  internal var hasObjectives by sessionData::hasObjectives
  internal var isInProgress by sessionData::isInProgress

  internal var scrubTimestampMs by scrubber::scrubTimestampMs
  internal var onScrub by scrubber::onScrub
  internal var isScrubbing by scrubber::isScrubbing
  internal var isSnappedToMarker by scrubber::isSnappedToMarker

  internal var scrollState by scrollSync::scrollState
  internal val objectiveOffsets get() = scrollSync.objectiveOffsets
  internal var stepsColumnOffset by scrollSync::stepsColumnOffset

  internal var isVideoPlaying by video::isVideoPlaying
  internal var videoDurationMs by video::videoDurationMs
  internal var playbackSpeed by video::playbackSpeed
  internal var videoSeekRequestMs by video::videoSeekRequestMs
}

@Composable
fun rememberSessionTimelineState(): SessionTimelineState {
  return remember { SessionTimelineState() }
}
