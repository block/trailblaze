package xyz.block.trailblaze.viewmatcher.search

import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter
import xyz.block.trailblaze.viewmatcher.models.RelativePosition

/** Validates spatial relationships (above, below, leftOf, rightOf) using geometric bounds. */
object SpatialRelationshipValidator {

  fun validateSpatialRelationship(
    referenceNode: ViewHierarchyTreeNode,
    targetBounds: ViewHierarchyFilter.Bounds,
    relationship: RelativePosition,
  ): Boolean {
    val referenceBounds = referenceNode.bounds ?: return false

    return when (relationship) {
      RelativePosition.ABOVE -> targetBounds.y2 <= referenceBounds.y1
      RelativePosition.BELOW -> targetBounds.y1 >= referenceBounds.y2
      RelativePosition.LEFT_OF -> targetBounds.x2 <= referenceBounds.x1
      RelativePosition.RIGHT_OF -> targetBounds.x1 >= referenceBounds.x2
    }
  }
}
