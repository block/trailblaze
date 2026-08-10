package xyz.block.trailblaze.ui.tabs.recording

import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector

/**
 * Re-targets a recorded selector tap at [selector], keeping everything else the author already
 * decided about this step.
 *
 * Written as a [copy] rather than a fresh constructor call so a field added to
 * [TapOnByElementSelector] later cannot be silently dropped here: the picker only means to change
 * *which element* is tapped, and a rebuild that enumerates fields quietly reverts every one it
 * forgets. `tapRoute` is the field that made this concrete — losing it un-pins a step that was
 * measured to need its route, and the tap starts being absorbed again with nothing in the diff to
 * explain it.
 */
internal fun TapOnByElementSelector.retargetedAt(
  selector: TrailblazeNodeSelector,
): TapOnByElementSelector = copy(nodeSelector = selector)
