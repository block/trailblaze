package xyz.block.trailblaze.api.waypoint

data class WaypointMatchResult(
  val definitionId: String,
  val matched: Boolean,
  val matchedRequired: List<MatchedRequired>,
  val missingRequired: List<MissingRequired>,
  val presentForbidden: List<PresentForbidden>,
  val skipped: SkipReason? = null,
) {
  data class MatchedRequired(val entry: WaypointSelectorEntry, val matchCount: Int)
  data class MissingRequired(val entry: WaypointSelectorEntry, val matchCount: Int)
  data class PresentForbidden(val entry: WaypointSelectorEntry, val matchCount: Int)

  enum class SkipReason {
    NO_NODE_TREE_IN_SCREEN_STATE,
  }
}
