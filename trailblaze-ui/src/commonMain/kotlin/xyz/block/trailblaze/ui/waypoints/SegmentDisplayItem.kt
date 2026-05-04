package xyz.block.trailblaze.ui.waypoints

/**
 * UI-side mirror of `xyz.block.trailblaze.segment.TrailSegment` for the session-lens
 * panel in the Waypoints tab. Conceptual layering: a segment is a *raw observation*
 * extracted from a session log — the user-facing output of `trailblaze segment list` —
 * distinct from a *shortcut*, which is an authored, named, reusable promotion of one
 * or more observations.
 *
 * ## Why a separate DTO instead of reusing `TrailSegment` directly
 *
 * `TrailSegment` lives in `trailblaze-common/jvmAndAndroid` (because the extractor it
 * comes from uses [java.io.File]). `trailblaze-ui/commonMain` only depends on
 * `trailblaze-models`'s commonMain — the JVM-only type isn't reachable here. Mirroring
 * the relevant fields keeps the visualizer renderable on WASM without dragging
 * `trailblaze-common`'s JVM surface across the boundary.
 *
 * The JVM tab wrapper converts `TrailSegment` → [SegmentDisplayItem] before passing it to
 * the composable; the conversion is a flat field copy.
 */
data class SegmentDisplayItem(
  /** Source waypoint id (e.g. `clock/android/alarm_tab`). */
  val from: String,
  /** Destination waypoint id (e.g. `clock/android/alarm_time_picker`). */
  val to: String,
  /**
   * Human-readable summaries of the tool calls that drove this transition, in
   * chronological order. Empty list means no actions were recorded between the two
   * matched waypoints (rare — typically means a logging gap, not a real instant
   * transition).
   */
  val triggers: List<String>,
  /** 1-based step index (among request logs) where the `from` waypoint was matched. */
  val fromStep: Int,
  /** 1-based step index (among request logs) where the `to` waypoint was matched. */
  val toStep: Int,
  /** Wall-clock duration in ms between the two matched steps. */
  val durationMs: Long,
)

/**
 * Aggregate result of a session-lens lookup against a single session log directory.
 * Mirrors `SessionSegmentExtractor.Analysis`'s public fields so the panel can render
 * the diagnostic counts (parse failures, ambiguous matches, etc.) alongside the
 * extracted segments.
 *
 * [sessionPath] is the absolute path of the session directory the user picked, used as
 * the panel header and as the failure-context prefix.
 */
data class SessionLensResult(
  val sessionPath: String,
  val totalRequestLogs: Int,
  val stepsWithNodeTree: Int,
  val stepsWithMatchedWaypoint: Int,
  val stepsWithAmbiguousMatch: Int,
  val parseFailures: Int,
  val segments: List<SegmentDisplayItem>,
  /**
   * Per-waypoint list of 1-based request-log step indices where that waypoint matched
   * uniquely in this session. Mirrors `SessionSegmentExtractor.Analysis.matchedStepsByWaypoint`
   * — see that field's kdoc for the exact semantics. Used by the v1.5 visualizer to badge
   * each waypoint card with the steps where it matched in the selected session.
   *
   * Defaults to empty so older constructions of [SessionLensResult] (without a session
   * picked, or for tests) continue to compile unchanged.
   */
  val matchedStepsByWaypoint: Map<String, List<Int>> = emptyMap(),
)
