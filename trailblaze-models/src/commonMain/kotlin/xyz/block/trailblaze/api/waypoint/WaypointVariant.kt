package xyz.block.trailblaze.api.waypoint

import kotlinx.serialization.Serializable

/**
 * The per-device-classifier block of a [WaypointDefinition]. Each block is **sparse** — it
 * declares only what is specific to its classifier level. So `android:` (a family block) might
 * carry the shared `required`/`forbidden` selectors while `android-phone:` carries only the
 * per-form-factor [example]; resolution fills each field from the closest classifier in the
 * device's lineage that declares it (see [WaypointDefinition.resolveFor]).
 *
 * Why per-classifier blocks: selectors genuinely *are* platform-specific (different drivers),
 * and a phone and a tablet share accessibility identity but render completely differently — so
 * the example wants to be keyed by classifier even when the selectors are shared at the family
 * level. This mirrors the unified trail format's per-classifier step recordings.
 *
 * @property route Per-classifier route override. Most routes are the same across a platform
 *   (`scheme://items` on android + ios), so [WaypointDefinition.route] carries the shared
 *   default; a block sets this only where the address genuinely differs (e.g. web is a path,
 *   not a deep link). Resolved closest-wins, falling back to the top-level default.
 * @property required Conditions that must ALL be satisfied for the matcher to accept the screen.
 * @property forbidden Conditions that must NOT be present — even one disqualifies the waypoint.
 * @property example The known-good example screen captured for this classifier (provenance + the
 *   sibling file the standalone `validate` consumes).
 */
@Serializable
data class WaypointVariant(
  val route: String? = null,
  val required: List<WaypointCondition> = emptyList(),
  val forbidden: List<WaypointCondition> = emptyList(),
  val example: WaypointExampleRef? = null,
)
