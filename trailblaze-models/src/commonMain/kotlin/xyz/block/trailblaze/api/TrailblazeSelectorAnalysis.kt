package xyz.block.trailblaze.api

import kotlinx.serialization.Serializable

// Wire DTOs for the selector-analysis surface the report's UI Inspector consumes via the
// Kotlin/JS selector engine (`:trailblaze-selector-engine-js`). This file is a source-of-truth
// input to the selector TypeScript codegen (`generateSelectorsTs`) — the generated interfaces in
// `sdks/typescript/src/generated/selectors.ts` are derived from these classes, so the TS side of
// the boundary is typed without a hand-written mirror. Keep this file to `@Serializable data
// class` declarations only (the source-text parser scoops every data class it finds), and
// regenerate + commit `selectors.ts` after any change here.
//
// Serialization contract (see `TrailblazeSelectorAnalyzer`): encoded with `encodeDefaults = true`
// and `explicitNulls = false`, so non-nullable fields are always present (required in TS) and
// nullable fields are simply absent when null (optional in TS).

/**
 * One candidate selector for a target node: the selector itself plus everything the inspector
 * needs to judge it — how many nodes it matches, which ones, where a tap on it would land, and
 * whether that tap would actually hit the target.
 */
@Serializable
internal data class TrailblazeSelectorOption(
  /** The candidate selector, in the same shape the recorder writes into a trail. */
  val selector: TrailblazeNodeSelector,
  /** Human-readable name of the generator strategy that produced this selector. */
  val strategy: String = "",
  /** True on the option the generator would pick for a recording (at most one per analysis). */
  val isBest: Boolean = false,
  /** Number of nodes in the tree this selector resolves to. 1 means unique. */
  val matchCount: Int = 0,
  /** `nodeId`s of every matching node, in resolution order. */
  val matchingNodeIds: List<Long> = emptyList(),
  /** X of the coordinate a tap on this selector would use (first match's center). */
  val resolvedCenterX: Int? = null,
  /** Y of the coordinate a tap on this selector would use (first match's center). */
  val resolvedCenterY: Int? = null,
  /**
   * True when hit-testing the resolved center lands on the target node — i.e. tapping this
   * selector at playback would really hit the element, not an overlapping child.
   */
  val hitsTarget: Boolean = false,
  /**
   * `nodeId` of the node that would actually receive a tap at the resolved center — the
   * frontmost node by the recorder's hit test ([TrailblazeNode.hitTest]). Equals the target's
   * id when [hitsTarget]; when it differs, this is the element intercepting the tap (what the
   * inspector's mismatch visualization highlights). Null when no center resolved.
   */
  val hitNodeId: Long? = null,
)

/** Ranked selector suggestions for one target node, plus a content-free structural fallback. */
@Serializable
internal data class TrailblazeSelectorAnalysis(
  /** Ranked candidate selectors; the structural (content-free) option is always last. */
  val options: List<TrailblazeSelectorOption> = emptyList(),
  /** Set instead of [options] when the analysis failed (e.g. unknown node id). */
  val error: String? = null,
)

/**
 * Result of resolving a tap coordinate to a target node + best selector — the same call the
 * daemon's recorder makes for every recorded tap (`TrailblazeNodeSelectorGenerator.resolveFromTap`).
 */
@Serializable
internal data class TrailblazeSelectorTapResolution(
  /** `nodeId` of the frontmost node at the tap coordinates. */
  val targetNodeId: Long? = null,
  /** The best unique selector for the target — what the recorder would write. */
  val selector: TrailblazeNodeSelector? = null,
  /** X the selector would resolve to at playback (compare with the tap point for drift). */
  val resolvedCenterX: Int? = null,
  /** Y the selector would resolve to at playback (compare with the tap point for drift). */
  val resolvedCenterY: Int? = null,
  /** True when the selector's resolved center hit-tests back to the same target node. */
  val roundTripValid: Boolean = false,
  /** Set when resolution failed (e.g. no node at the coordinates). */
  val error: String? = null,
)

/** Result of resolving an arbitrary selector against a tree — the inspector's "test this selector". */
@Serializable
internal data class TrailblazeSelectorResolution(
  /** Number of nodes the selector resolves to. 0 means no match. */
  val matchCount: Int = 0,
  /** `nodeId`s of every matching node, in resolution order. */
  val matchingNodeIds: List<Long> = emptyList(),
  /** X of the coordinate a tap on this selector would use (first match's center). */
  val resolvedCenterX: Int? = null,
  /** Y of the coordinate a tap on this selector would use (first match's center). */
  val resolvedCenterY: Int? = null,
  /** Set when the input selector or tree could not be decoded. */
  val error: String? = null,
)
