package xyz.block.trailblaze.ui.tabs.session

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import xyz.block.trailblaze.util.Console

/**
 * Keyboard handler for the combined view. Handles spacebar (play/pause), up/down (event
 * navigation). Returns true if the key event was consumed.
 */
internal fun handleCombinedViewKeyEvent(
  event: KeyEvent,
  navEvents: List<CombinedEvent>,
  currentTimestamp: Long,
  selectedEventKey: String?,
  timelineState: SessionTimelineState,
  videoMetadata: VideoMetadata?,
  effectiveStartMs: Long,
  effectiveEndMs: Long,
  onUserInteracted: () -> Unit,
  onSelectedEventKeyChanged: (String?) -> Unit,
): Boolean {
  if (event.type != KeyEventType.KeyDown) return false
  return when (event.key) {
    Key.Spacebar -> {
      handleSpacebar(timelineState, videoMetadata, effectiveStartMs, effectiveEndMs)
      onSelectedEventKeyChanged(null)
      true
    }
    Key.DirectionDown -> {
      handleNavigateNext(
        navEvents, currentTimestamp, selectedEventKey, timelineState,
        onUserInteracted, onSelectedEventKeyChanged,
      )
      true
    }
    Key.DirectionUp -> {
      handleNavigatePrevious(
        navEvents, currentTimestamp, selectedEventKey, timelineState,
        onUserInteracted, onSelectedEventKeyChanged,
      )
      true
    }
    else -> false
  }
}

private fun handleSpacebar(
  timelineState: SessionTimelineState,
  videoMetadata: VideoMetadata?,
  effectiveStartMs: Long,
  effectiveEndMs: Long,
) {
  Console.log("[CombinedView] Spacebar: playing=${timelineState.isVideoPlaying}")
  if (!timelineState.isVideoPlaying) {
    val scrub = timelineState.scrubTimestampMs ?: effectiveStartMs
    val end = videoMetadata?.endTimestampMs ?: effectiveEndMs
    if (scrub >= end - TimelineConstants.END_OF_VIDEO_THRESHOLD_MS) {
      timelineState.scrubTimestampMs = videoMetadata?.startTimestampMs ?: effectiveStartMs
    }
  }
  timelineState.isVideoPlaying = !timelineState.isVideoPlaying
  timelineState.isSnappedToMarker = false
}

private fun handleNavigateNext(
  navEvents: List<CombinedEvent>,
  currentTimestamp: Long,
  selectedEventKey: String?,
  timelineState: SessionTimelineState,
  onUserInteracted: () -> Unit,
  onSelectedEventKeyChanged: (String?) -> Unit,
) {
  val curIdx = findCurrentEventIndex(navEvents, currentTimestamp, selectedEventKey)
  val nextIdx = curIdx + 1
  Console.log("[CombinedView] Down: curIdx=$curIdx/${navEvents.size}, nextIdx=$nextIdx")
  if (nextIdx in navEvents.indices) {
    val next = navEvents[nextIdx]
    Console.log("[CombinedView] Down: -> ${next.title} at ${next.timestampMs}")
    onUserInteracted()
    navigateToEvent(timelineState, next)
    onSelectedEventKeyChanged(next.selectionKey())
  }
}

private fun handleNavigatePrevious(
  navEvents: List<CombinedEvent>,
  currentTimestamp: Long,
  selectedEventKey: String?,
  timelineState: SessionTimelineState,
  onUserInteracted: () -> Unit,
  onSelectedEventKeyChanged: (String?) -> Unit,
) {
  val curIdx = findCurrentEventIndex(navEvents, currentTimestamp, selectedEventKey)
  val prevIdx =
    if (selectedEventKey != null && curIdx >= 0) {
      curIdx - 1
    } else
      when {
        curIdx > 0 && navEvents[curIdx].timestampMs == currentTimestamp -> curIdx - 1
        curIdx > 0 -> curIdx
        curIdx == 0 && navEvents[0].timestampMs != currentTimestamp -> 0
        curIdx < 0 && navEvents.isNotEmpty() -> 0
        else -> -1
      }
  Console.log("[CombinedView] Up: curIdx=$curIdx/${navEvents.size}, prevIdx=$prevIdx")
  if (prevIdx in navEvents.indices) {
    val prev = navEvents[prevIdx]
    Console.log("[CombinedView] Up: -> ${prev.title} at ${prev.timestampMs}")
    onUserInteracted()
    navigateToEvent(timelineState, prev)
    onSelectedEventKeyChanged(prev.selectionKey())
  }
}

/** Find the current event index, preferring exact key match over timestamp lookup. */
private fun findCurrentEventIndex(
  navEvents: List<CombinedEvent>,
  currentTimestamp: Long,
  selectedEventKey: String?,
): Int =
  if (selectedEventKey != null) {
    navEvents.indexOfFirst { it.selectionKey() == selectedEventKey }
  } else {
    navEvents.indexOfLast { it.timestampMs <= currentTimestamp }
  }

/** Navigate the timeline to the given event. */
private fun navigateToEvent(timelineState: SessionTimelineState, event: CombinedEvent) {
  timelineState.isVideoPlaying = false
  timelineState.scrubTimestampMs = event.timestampMs
  timelineState.isSnappedToMarker = event.hasScreenshot
}
