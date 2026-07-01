package xyz.block.trailblaze.api.waypoint

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * One condition in a waypoint's [WaypointVariant.required] / [WaypointVariant.forbidden] list.
 *
 * **This is a wrapper, deliberately — never collapse it to a bare [TrailblazeNodeSelector].**
 * Today a condition carries exactly one kind of check: a [selector] (a view-hierarchy match).
 * Future condition kinds — a network event having fired, a memory value being set, a log line
 * having appeared — arrive as **new optional fields on this same wrapper**, exactly the way
 * [TrailblazeNodeSelector] holds one-of-N optional per-driver fields. The matcher dispatches on
 * which field is present. Adding a kind is a new optional `@Serializable` field (an apiDump
 * baseline regen; named defaults keep source compatibility), **not** a consumer break. Keeping
 * `required`/`forbidden` typed as `List<WaypointCondition>` rather than `List<TrailblazeNodeSelector>`
 * is the single discipline that makes that future non-breaking.
 *
 * `minCount` controls how many tree elements must match the [selector] for a `required`
 * condition to be satisfied. It must be at least 1 — values of 0 or negative would make a
 * required condition trivially satisfied and silently produce incorrect matches.
 *
 * `minCount` is currently ignored for `forbidden` conditions: the matcher fails the waypoint
 * as soon as the forbidden selector matches at all, regardless of count.
 */
@Serializable
data class WaypointCondition(
  val description: String? = null,
  val minCount: Int = 1,
  val selector: TrailblazeNodeSelector? = null,
) {
  init {
    require(minCount >= 1) { "WaypointCondition.minCount must be at least 1, got $minCount" }
  }
}
