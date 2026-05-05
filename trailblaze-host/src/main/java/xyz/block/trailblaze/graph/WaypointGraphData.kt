package xyz.block.trailblaze.graph

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry

/**
 * Pure data shape of the waypoint navigation graph — the JSON contract between Kotlin
 * (which builds it) and the React Flow front-end (which renders it).
 *
 * This intentionally lives outside the existing `xyz.block.trailblaze.ui.*` packages so
 * the renderer (HTML) and the builder (Kotlin) share a single source of truth that has
 * nothing to do with Compose. Both the in-app browser button and the standalone
 * `./trailblaze waypoint graph` CLI emit the same structure; the front-end is unaware of
 * which surface produced it.
 *
 * Shape choices:
 *  - **Screenshots inlined as data URIs.** The whole point of "save the page and share
 *    it" is single-file portability. Externalizing screenshots (as `/screenshot/<id>`
 *    URLs served by the daemon) is a future optimization for the live in-desktop view
 *    but would break the standalone-file workflow. Inline by default; revisit if file
 *    sizes get unreasonable for huge waypoint sets.
 *  - **Pack ids parsed lazily on the front-end.** We don't pre-compute pack/platform
 *    grouping here because React Flow's filter UI is cheap and doing it in JS keeps the
 *    JSON payload smaller and lets the front-end iterate without re-emitting the file.
 */
@Serializable
data class WaypointGraphData(
  /** Every waypoint that should appear as a node, regardless of edge connectivity. */
  val waypoints: List<WaypointGraphNode>,
  /** Authored shortcut edges (`*.shortcut.yaml`) — render as solid arrows. */
  val shortcuts: List<WaypointGraphShortcut>,
  /** Authored trailhead edges (`*.trailhead.yaml`) — render as dashed arrows from a virtual origin. */
  val trailheads: List<WaypointGraphTrailhead>,
  /**
   * One-line generation provenance shown in the page footer, e.g.
   * `"generated 2026-04-29T13:45:00Z from /Users/sam/.../trails (live)"`. Shown to the
   * viewer so they know this is a snapshot, not a live view (especially important when
   * the file has been emailed/Slacked around).
   */
  val generatedNote: String,
)

@Serializable
data class WaypointGraphNode(
  /** Slash-separated waypoint id (e.g. `square/banking`). */
  val id: String,
  /** Optional human-readable description from the waypoint YAML's `description:` field. */
  val description: String?,
  /**
   * Raw screenshot bytes encoded as a data URI (`data:image/<format>;base64,...`), or
   * null when no example pair was found. The front-end falls back to a placeholder card
   * for nulls so the layout still renders.
   */
  val screenshotDataUri: String?,
  /**
   * Provenance label for hover tooltip — e.g. `pack:myapp — waypoints/banking.waypoint.yaml`
   * or a relative filesystem path. Helps the viewer debug "which file did this come from?"
   * without leaving the page.
   */
  val sourceLabel: String?,
  /**
   * Platform for this waypoint variant — `"android"`, `"ios"`, `"web"`, or `null` when not
   * platform-specific. Derived from the waypoint file's location on disk
   * (`packs/<pack>/waypoints/<platform>/...`); the id itself no longer carries the
   * platform segment. Drives the platform filter pills in the graph viewer.
   */
  val platform: String?,
  /**
   * Selector entries that must ALL match in the captured tree for the waypoint matcher to
   * accept this screen. The detail panel renders these as the "how does the matcher know
   * this is `<id>`?" answer — the selectors *are* the waypoint's identity.
   */
  val required: List<WaypointSelectorEntry> = emptyList(),
  /**
   * Selector entries that must NOT match. Even one match here disqualifies the waypoint —
   * useful for distinguishing siblings that share most identity signals (e.g. "this is
   * the Money tab specifically — the Withdraw button must NOT be present, otherwise we'd
   * be on the Withdraw composer").
   */
  val forbidden: List<WaypointSelectorEntry> = emptyList(),
)

@Serializable
data class WaypointGraphShortcut(
  val id: String,
  val description: String?,
  val from: String,
  val to: String,
  val variant: String?,
)

@Serializable
data class WaypointGraphTrailhead(
  val id: String,
  val description: String?,
  val to: String,
)
