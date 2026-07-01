package xyz.block.trailblaze.toolcalls.commands

import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector

/**
 * Recursively collects every non-null [DriverNodeMatch] in this selector tree — the node's own
 * [TrailblazeNodeSelector.driverMatch] plus the matches of every nested hierarchy / spatial
 * relation (`childOf`, `containsChild`, `containsDescendants`, `below`, `above`, `leftOf`,
 * `rightOf`).
 *
 * Used to decide whether a single-driver agent can resolve the *whole* selector. The Android
 * accessibility agent, for example, resolves only [DriverNodeMatch.AndroidAccessibility]
 * matches; a tree carrying any other driver branch — even one nested under `below:` — cannot be
 * resolved natively, so the agent must hand off to the driver-agnostic Maestro lowering rather
 * than risk a spurious "no match" (which a not-visible assertion would wrongly treat as success).
 * Checking only the top-level [TrailblazeNodeSelector.driverMatch] would miss those nested
 * branches, hence this full traversal.
 */
fun TrailblazeNodeSelector.allDriverMatches(): List<DriverNodeMatch> {
  val matches = mutableListOf<DriverNodeMatch>()
  fun walk(selector: TrailblazeNodeSelector) {
    selector.driverMatch?.let { matches.add(it) }
    selector.childOf?.let(::walk)
    selector.containsChild?.let(::walk)
    selector.below?.let(::walk)
    selector.above?.let(::walk)
    selector.leftOf?.let(::walk)
    selector.rightOf?.let(::walk)
    selector.containsDescendants?.forEach(::walk)
  }
  walk(this)
  return matches
}
