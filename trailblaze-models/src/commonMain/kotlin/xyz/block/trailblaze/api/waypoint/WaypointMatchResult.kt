package xyz.block.trailblaze.api.waypoint

data class WaypointMatchResult(
  val definitionId: String,
  val matched: Boolean,
  val matchedRequired: List<MatchedRequired>,
  val missingRequired: List<MissingRequired>,
  val presentForbidden: List<PresentForbidden>,
  val skipped: SkipReason? = null,
) {
  data class MatchedRequired(val entry: WaypointCondition, val matchCount: Int)
  data class MissingRequired(val entry: WaypointCondition, val matchCount: Int)
  data class PresentForbidden(val entry: WaypointCondition, val matchCount: Int)

  enum class SkipReason {
    NO_NODE_TREE_IN_SCREEN_STATE,

    /**
     * One or more of the definition's selectors still contains a `{{target.appId}}`
     * placeholder after expansion — i.e. the matcher was called without a
     * [xyz.block.trailblaze.api.TargetTemplateContext] (or with one that didn't resolve
     * the placeholder) for a definition whose selectors are templated.
     *
     * Fail-closed: we deliberately don't try to match. Leaving placeholders literal and
     * resolving via the normal path is safe for `required` entries (they just don't match)
     * but unsafe for `forbidden` entries — an un-substituted forbidden selector would
     * silently fail to match anything, the forbidden check would pass, and the waypoint
     * could be reported as matched when it shouldn't be. Surfacing this as an explicit
     * skip means a missing target context shows up loudly rather than as a false positive.
     */
    UNRESOLVED_TARGET_TEMPLATE,

    /**
     * The waypoint declares no classifier block for any classifier in the connected device's
     * lineage — i.e. it doesn't describe a screen on this device at all (an iOS-only waypoint
     * matched against an Android device, say). Distinct from a present-but-failing block:
     * surfacing it as a skip rather than a bare `matched = false` tells a waypoint author
     * "add a block for this platform" rather than "your selectors are wrong." A waypoint that
     * *has* a block but declares no conditions still matches vacuously (it is not skipped).
     */
    NO_CLASSIFIER_BLOCK,

    /**
     * The capture this waypoint was evaluated against is KNOWN to be missing nodes (see
     * [xyz.block.trailblaze.api.ScreenState.droppedNodeFetches]) and the waypoint declares
     * `forbidden` conditions, none of which resolved. That is the same false-positive shape as
     * [UNRESOLVED_TARGET_TEMPLATE], arriving by a different road: a forbidden selector that
     * cannot be evaluated satisfies the forbidden check vacuously, and the waypoint reports a
     * match while the thing it forbids is still on screen.
     *
     * Fail-closed, and only where it can change the answer — a partial capture that still
     * resolves a forbidden selector, or that leaves a required selector missing, produces the
     * ordinary diff, because both of those verdicts are true regardless of what the capture
     * lost. Skipping rather than failing lets a poll loop keep going and settle the waypoint on
     * a later, complete capture.
     */
    PARTIAL_CAPTURE,
  }
}
