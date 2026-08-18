package xyz.block.trailblaze.viewmatcher.models

/**
 * Ordered spatial hints that preserve the LLM's preference ordering.
 * Each hint includes the reference nodeId and the type of relationship.
 */
data class OrderedSpatialHints(
  val hints: List<SpatialHint>,
) {
  data class SpatialHint(
    val referenceNodeId: Long,
    val relationship: RelativePosition,
  )
}
