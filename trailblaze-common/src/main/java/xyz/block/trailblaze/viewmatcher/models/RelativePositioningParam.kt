package xyz.block.trailblaze.viewmatcher.models

/**
 * Used to group relative nodeId positioning data
 */
data class RelativePositioningParam(
  val leftOf: Long?,
  val rightOf: Long?,
  val above: Long?,
  val below: Long?,
)
