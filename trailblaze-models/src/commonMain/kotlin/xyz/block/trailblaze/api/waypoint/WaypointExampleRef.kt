package xyz.block.trailblaze.api.waypoint

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo

/**
 * A reference, declared inside a [WaypointVariant], to the known-good example screen state
 * captured for that device classifier. The example itself (the view-hierarchy tree + screenshot)
 * lives in the sibling `<base>.example.json` + screenshot pair that `waypoint capture-example`
 * writes; this ref makes the binding — and its provenance — explicit and diffable per classifier.
 *
 * **This is an open, additive signal bundle** (read tolerantly, ignore-unknown), the same
 * extensibility hinge as [WaypointCondition]. Tree + screenshot today; future channels
 * (`networkEvents`, `deviceLogs`, `memorySnapshot`) are new optional fields, never a closed type.
 *
 * @property file Filename of the sibling example JSON (relative to the waypoint YAML).
 * @property capturedAt The "last updated" timestamp for this classifier's example. Surfaced so
 *   freshness is visible and diffable per device type ("ios-ipad example is 6 months stale →
 *   re-capture" while android-phone is current). Non-load-bearing — the matcher ignores it.
 * @property source Best-effort link to the full session `.zip` the example was extracted from.
 *   Omitted for live on-device captures (no archive exists). Non-load-bearing.
 * @property device The full device context the example was captured on. **This is NOT a new
 *   schema and is NOT synthesized** — it is the existing [TrailblazeDeviceInfo] that the session
 *   log already records (`SessionInfo.trailblazeDeviceInfo`), **projected verbatim** into the
 *   example by `capture-example`. To get richer device context in examples, enrich the session
 *   logs at capture time; the example inherits it for free. Non-load-bearing.
 */
@Serializable
data class WaypointExampleRef(
  val file: String,
  val capturedAt: String? = null,
  val source: String? = null,
  val device: TrailblazeDeviceInfo? = null,
)
