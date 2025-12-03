package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.models.RelativePosition
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.search.SpatialRelationshipValidator

/** Strategy 3: Target + LLM spatial hints (above, below, leftOf, rightOf) with geometric validation. */
internal object SpatialHintsStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!
  override val isPerformant = true

  private fun interface SpatialSelectorBuilder {
    fun build(
      spatialNeighbor: TrailblazeElementSelector,
      parentConstraint: TrailblazeElementSelector?,
    ): TrailblazeElementSelector?
  }

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val hints = context.spatialHints ?: return null
    val uniqueParent = context.uniqueParent

    fun getSelectorForNode(nodeId: Long): TrailblazeElementSelector? {
      val node = context.root.aggregate().find { it.nodeId == nodeId } ?: return null
      return node.asTrailblazeElementSelector()
    }

    fun validateSpatialRelationship(referenceNodeId: Long, relationship: RelativePosition): Boolean {
      val referenceNode = context.root.aggregate().find { it.nodeId == referenceNodeId } ?: return false
      val targetBounds = context.target.bounds ?: return false

      return SpatialRelationshipValidator.validateSpatialRelationship(referenceNode, targetBounds, relationship)
    }

    fun tryWithSpatial(
      relationship: RelativePosition,
      nodeId: Long,
      selectorBuilder: SpatialSelectorBuilder,
    ): TrailblazeElementSelector? {
      if (!validateSpatialRelationship(nodeId, relationship)) {
        return null
      }

      val spatialNeighborSelector = getSelectorForNode(nodeId) ?: return null

      // Try without parent first
      selectorBuilder.build(spatialNeighborSelector, null)?.let { selectorWithoutParent: TrailblazeElementSelector ->
        val matchesWithoutParent = context.getMatches(selectorWithoutParent)
        if (matchesWithoutParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithoutParent)) {
          return matchesWithoutParent.trailblazeElementSelector
        }
      }

      // Try with parent
      selectorBuilder.build(spatialNeighborSelector, uniqueParent)?.let { selectorWithParent ->
        val matchesWithParent = context.getMatches(selectorWithParent)
        if (matchesWithParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithParent)) {
          return matchesWithParent.trailblazeElementSelector
        }
      }

      return null
    }

    context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      hints.hints.forEach { hint ->
        when (hint.relationship) {
          RelativePosition.ABOVE -> tryWithSpatial(
            RelativePosition.ABOVE,
            hint.referenceNodeId,
            SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
              targetLeafSelector.copy(childOf = parentConstraint, above = spatialNeighbor)
            },
          )?.let { return it }

          RelativePosition.BELOW -> tryWithSpatial(
            RelativePosition.BELOW,
            hint.referenceNodeId,
            SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
              targetLeafSelector.copy(childOf = parentConstraint, below = spatialNeighbor)
            },
          )?.let { return it }

          RelativePosition.LEFT_OF -> tryWithSpatial(
            RelativePosition.LEFT_OF,
            hint.referenceNodeId,
            SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
              targetLeafSelector.copy(childOf = parentConstraint, leftOf = spatialNeighbor)
            },
          )?.let { return it }

          RelativePosition.RIGHT_OF -> tryWithSpatial(
            RelativePosition.RIGHT_OF,
            hint.referenceNodeId,
            SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
              targetLeafSelector.copy(childOf = parentConstraint, rightOf = spatialNeighbor)
            },
          )?.let { return it }
        }
      }
    }

    return null
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> {
    val results = mutableListOf<TrailblazeElementSelector>()
    val hints = context.spatialHints ?: return results
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueParent = context.uniqueParent

    fun getSelectorForNode(nodeId: Long): TrailblazeElementSelector? {
      val node = context.root.aggregate().find { it.nodeId == nodeId } ?: return null
      return node.asTrailblazeElementSelector()
    }

    fun validateSpatialRelationship(referenceNodeId: Long, relationship: RelativePosition): Boolean {
      val referenceNode = context.root.aggregate().find { it.nodeId == referenceNodeId } ?: return false
      val targetBounds = context.target.bounds ?: return false

      return SpatialRelationshipValidator.validateSpatialRelationship(referenceNode, targetBounds, relationship)
    }

    fun tryWithSpatial(
      relationship: RelativePosition,
      nodeId: Long,
      selectorBuilder: SpatialSelectorBuilder,
    ) {
      if (!validateSpatialRelationship(nodeId, relationship)) {
        return
      }

      val spatialNeighborSelector = getSelectorForNode(nodeId) ?: return

      // Try without parent
      selectorBuilder.build(spatialNeighborSelector, null)?.let { selectorWithoutParent ->
        val matchesWithoutParent = context.getMatches(selectorWithoutParent)
        if (matchesWithoutParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithoutParent)) {
          results.add(matchesWithoutParent.trailblazeElementSelector)
        }
      }

      // Try with parent
      if (uniqueParent != null) {
        selectorBuilder.build(spatialNeighborSelector, uniqueParent)?.let { selectorWithParent ->
          val matchesWithParent = context.getMatches(selectorWithParent)
          if (matchesWithParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithParent)) {
            results.add(matchesWithParent.trailblazeElementSelector)
          }
        }
      }
    }

    hints.hints.forEach { hint ->
      when (hint.relationship) {
        RelativePosition.ABOVE -> tryWithSpatial(
          RelativePosition.ABOVE,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector?.copy(childOf = parentConstraint, above = spatialNeighbor)
          },
        )

        RelativePosition.BELOW -> tryWithSpatial(
          RelativePosition.BELOW,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector?.copy(childOf = parentConstraint, below = spatialNeighbor)
          },
        )

        RelativePosition.LEFT_OF -> tryWithSpatial(
          RelativePosition.LEFT_OF,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector?.copy(childOf = parentConstraint, leftOf = spatialNeighbor)
          },
        )

        RelativePosition.RIGHT_OF -> tryWithSpatial(
          RelativePosition.RIGHT_OF,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector?.copy(childOf = parentConstraint, rightOf = spatialNeighbor)
          },
        )
      }
    }

    return results
  }
}
