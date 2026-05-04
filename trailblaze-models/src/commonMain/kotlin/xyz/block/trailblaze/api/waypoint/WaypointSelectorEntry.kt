package xyz.block.trailblaze.api.waypoint

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Selector entry used by waypoint matching.
 *
 * `minCount` controls how many tree elements must match the [selector] for a `required`
 * entry to be satisfied. It must be at least 1 — values of 0 or negative would make a
 * required entry trivially satisfied and silently produce incorrect matches.
 *
 * `minCount` is currently ignored for `forbidden` entries: the matcher fails the waypoint
 * as soon as the forbidden selector matches at all, regardless of count.
 */
@Serializable
data class WaypointSelectorEntry(
  val selector: TrailblazeNodeSelector,
  val minCount: Int = 1,
  val description: String? = null,
) {
  init {
    require(minCount >= 1) { "WaypointSelectorEntry.minCount must be at least 1, got $minCount" }
  }
}
