package xyz.block.trailblaze.viewmatcher.models

fun List<RelativeViewPositioningData>.toOrderedSpatialHints(): OrderedSpatialHints = OrderedSpatialHints(
  hints = this.map { positioning ->
    OrderedSpatialHints.SpatialHint(
      referenceNodeId = positioning.otherNodeId,
      relationship = positioning.position,
    )
  },
)
