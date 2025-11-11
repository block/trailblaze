package xyz.block.trailblaze.viewmatcher.models

import kotlinx.serialization.Serializable

@Serializable
data class RelativeViewPositioningData(
  val position: RelativePosition,
  val otherNodeId: Long,
)
