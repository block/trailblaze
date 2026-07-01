package xyz.block.trailblaze.cli

import xyz.block.trailblaze.api.waypoint.WaypointCondition
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointVariant

/**
 * Test helpers for the classifier-keyed (v2) waypoint schema. The waypoint CLI tests are mostly
 * single-platform fixtures, so these build/read an `android` block by default — the v2 equivalent
 * of the v1 top-level `required`/`forbidden` the tests used to construct directly.
 */

/** Build a single-classifier (`android` by default) waypoint definition. */
internal fun androidWaypoint(
  id: String,
  required: List<WaypointCondition> = emptyList(),
  forbidden: List<WaypointCondition> = emptyList(),
  classifier: String = "android",
): WaypointDefinition = WaypointDefinition(
  id = id,
  byClassifier = mapOf(classifier to WaypointVariant(required = required, forbidden = forbidden)),
)

/** The `required` conditions of the `android` block (the common single-platform test shape). */
internal val WaypointDefinition.androidRequired: List<WaypointCondition>
  get() = byClassifier["android"]?.required ?: emptyList()

/** The `forbidden` conditions of the `android` block. */
internal val WaypointDefinition.androidForbidden: List<WaypointCondition>
  get() = byClassifier["android"]?.forbidden ?: emptyList()
