package xyz.block.trailblaze.ui.waypoints

import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.waypoint.WaypointDefinition

/**
 * A waypoint plus the source label used to display it. The source label is purely
 * informational — typically a relative file path for filesystem-walked waypoints, or a
 * pack-id-prefixed string like `pack:clock` for classpath-bundled ones.
 *
 * [example] is the captured screen tree + screenshot bundled alongside the waypoint
 * (`<id>.example.json` + `<id>.example.webp/png`). When present, the visualizer can
 * resolve each selector against this tree and overlay matched bounds on the screenshot.
 *
 * Defined in commonMain so the visualizer renders identically on Desktop and Web targets.
 * The JVM tab wrapper supplies these from [xyz.block.trailblaze.cli.WaypointDiscovery]; a
 * future WASM bootstrap will supply them from a bundled fixture or fetched JSON.
 */
data class WaypointDisplayItem(
  val definition: WaypointDefinition,
  val sourceLabel: String? = null,
  val example: WaypointExample? = null,
)

/**
 * Captured proof-screen for a waypoint: the screen tree plus the matching screenshot
 * bytes and device dimensions. The bytes are passed directly to coil3, which means the
 * same composable works for desktop (file-system loaded) and WASM (fetched/bundled).
 *
 * Plain class rather than data class so [ByteArray] doesn't blow up structural equality —
 * we never need value equality on this object, only reference-stable identity.
 *
 * ## Loader contract: produce a fresh instance per load
 *
 * Compose state in `WaypointExamplePanel` (specifically the `screenshotDecodeFailed`
 * flag) is `remember`-keyed on this object's reference identity. That means a transient
 * decode failure on a given example **must not** leak into a subsequent successful load
 * that happens to reuse the same instance — so loaders are required to allocate a new
 * `WaypointExample` for every refresh, even when the underlying bytes are unchanged.
 *
 * The current JVM loader (`WaypointsTabComposable.tryLoadFilesystemExample` and
 * `loadPackWaypointAndExample`) already satisfies this contract because each call
 * constructs a new instance from scratch. Future loaders (e.g. a WASM bootstrap that
 * fetches and caches) need to obey the same rule — wrap any cached payload in a fresh
 * `WaypointExample` before returning, don't hand back a pinned instance.
 */
class WaypointExample(
  val tree: TrailblazeNode,
  val screenshotBytes: ByteArray?,
  val deviceWidth: Int,
  val deviceHeight: Int,
)
