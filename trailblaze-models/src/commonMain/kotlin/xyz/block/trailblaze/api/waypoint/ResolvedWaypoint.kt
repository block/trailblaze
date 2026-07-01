package xyz.block.trailblaze.api.waypoint

import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier

/**
 * A [WaypointDefinition] flattened to a single device classifier — the per-field, closest-wins
 * resolution of the classifier-keyed blocks. This is the view the matcher and the CLI commands
 * operate on: it has the same `required`/`forbidden`/`route`/`example` shape a v1 waypoint had,
 * but specialized to the connected device's classifier.
 *
 * @property classifier The classifier this view was resolved for (the connected device's).
 */
data class ResolvedWaypoint(
  val id: String,
  val classifier: TrailblazeDeviceClassifier,
  val description: String? = null,
  val route: String? = null,
  val required: List<WaypointCondition> = emptyList(),
  val forbidden: List<WaypointCondition> = emptyList(),
  val example: WaypointExampleRef? = null,
)

/**
 * Resolve this definition for [classifier], walking [lineage] (most-specific-first) and picking
 * **each field independently, closest-wins, replace-not-merge**:
 *
 *  - `route` — the closest block that declares one, else the top-level [WaypointDefinition.route]
 *    default.
 *  - `required` / `forbidden` — the closest block that declares a non-empty list. (Empty is
 *    treated as "not declared" so a more-specific block that only carries an `example` doesn't
 *    wipe out the family block's selectors.)
 *  - `example` — the closest block that declares one. **Ancestor chain only, no sibling
 *    fallback**: a tablet with no tablet/family example shows nothing, never a phone's.
 *
 * [lineage] defaults to [classifier]'s canonical chain ([TrailblazeClassifierLineage.chainFor]);
 * callers with a device's full classifier set pass
 * `TrailblazeClassifierLineage.resolutionChain(deviceClassifiers)`.
 */
fun WaypointDefinition.resolveFor(
  classifier: TrailblazeDeviceClassifier,
  lineage: List<TrailblazeDeviceClassifier> = TrailblazeClassifierLineage.chainFor(classifier),
): ResolvedWaypoint {
  // Always include the requested classifier so resolution is total even if a caller passes an
  // empty/foreign lineage.
  val chain = if (lineage.any { it == classifier }) lineage else listOf(classifier) + lineage
  val variants = chain.mapNotNull { byClassifier[it.classifier] }
  return ResolvedWaypoint(
    id = id,
    classifier = classifier,
    description = description,
    route = variants.firstNotNullOfOrNull { it.route } ?: route,
    required = variants.firstOrNull { it.required.isNotEmpty() }?.required ?: emptyList(),
    forbidden = variants.firstOrNull { it.forbidden.isNotEmpty() }?.forbidden ?: emptyList(),
    example = variants.firstNotNullOfOrNull { it.example },
  )
}

/**
 * Convenience overload taking a device's broad-first classifier segments (e.g. `[android, phone]`
 * or `[ios, iphone]`). Resolves them through [TrailblazeClassifierLineage.resolutionChain] — which
 * joins them into the device's compound identity and expands to the most-specific-first chain — then
 * resolves against the first (most-specific) classifier.
 */
fun WaypointDefinition.resolveForDevice(
  deviceClassifiers: List<TrailblazeDeviceClassifier>,
): ResolvedWaypoint {
  val expanded = TrailblazeClassifierLineage.resolutionChain(deviceClassifiers)
  val primary = expanded.firstOrNull()
    ?: return ResolvedWaypoint(id = id, classifier = TrailblazeDeviceClassifier(""), description = description, route = route)
  return resolveFor(primary, expanded)
}
