package xyz.block.trailblaze.viewmatcher.search

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeElementSelector.Companion.isBlank
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.matching.CenterPointMatcher.centerPointsMatch
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext

/**
 * Finds selectors for nodes that share the same center point as the target.
 *
 * This is useful when the target node's own selector is ambiguous but there's
 * a descendant or ancestor at the same tap location that's uniquely identifiable.
 */
internal object SameCenterPointFinder {

  data class SameCenterResult(
    val selector: TrailblazeElementSelector,
    val description: String,
  )

  /**
   * Tries to find a unique selector from descendants with the same center point.
   * Prefers deepest (leaf) elements.
   */
  fun findFromDescendants(
    context: SelectorSearchContext,
    targetCenterPoint: String,
    includeDescription: Boolean = false,
  ): SameCenterResult? {
    val sameCenterDescendants = context.target.aggregate()
      .filter {
        it.nodeId != context.target.nodeId &&
          centerPointsMatch(
            it.centerPoint,
            targetCenterPoint,
            context.centerPointTolerancePx,
          )
      }
      .reversed() // Prefer deepest (leaf) elements

    for (descendant in sameCenterDescendants) {
      val result = tryCandidateNode(descendant, targetCenterPoint, context)
      if (result != null) {
        return if (includeDescription) {
          SameCenterResult(result, "Target properties (via descendant at same center)")
        } else {
          SameCenterResult(result, "")
        }
      }
    }

    return null
  }

  /**
   * Tries to find a unique selector from ancestors with the same center point.
   * Prefers closest ancestors. Limits to 3 levels to avoid generic container IDs.
   */
  fun findFromAncestors(
    context: SelectorSearchContext,
    targetCenterPoint: String,
    includeDescription: Boolean = false,
    maxLevels: Int = 3,
  ): SameCenterResult? {
    val pathToTarget = HierarchyNavigator.findPathToTarget(context.target, context.root)
    val sameCenterAncestors = pathToTarget.reversed()
      .take(maxLevels)
      .filter { ancestor ->
        centerPointsMatch(
          ancestor.centerPoint,
          targetCenterPoint,
          context.centerPointTolerancePx,
        )
      }

    var depth = 0
    for (ancestor in sameCenterAncestors) {
      depth++
      val result = tryCandidateNode(ancestor, targetCenterPoint, context)
      if (result != null) {
        return if (includeDescription) {
          val levelText = if (depth == 1) "1 level" else "$depth levels"
          SameCenterResult(result, "Target properties (via ancestor $levelText up)")
        } else {
          SameCenterResult(result, "")
        }
      }
    }

    return null
  }

  /**
   * Tests if a candidate node can be used as a selector for the target location.
   *
   * This method validates that a candidate node (either an ancestor or descendant at the same
   * center point) can serve as a valid selector for the target. It performs the following checks:
   *
   * 1. Converts the candidate to a selector (text, id, or state properties)
   * 2. Tests if the selector is unique in the hierarchy:
   *    - If unique: Verifies it's at the target's center point
   *    - If ambiguous: Attempts to add an index to disambiguate
   * 3. Returns the validated selector or null if validation fails
   *
   * @param candidate The node to test as a potential selector (must be at target's center point)
   * @param targetCenterPoint The center point string ("x,y") we're trying to match
   * @param context Search context with hierarchy and matching utilities
   * @return A valid selector for the target location, or null if candidate doesn't work
   */
  private fun tryCandidateNode(
    candidate: ViewHierarchyTreeNode,
    targetCenterPoint: String,
    context: SelectorSearchContext,
  ): TrailblazeElementSelector? {
    candidate.asTrailblazeElementSelector()?.let { candidateSelector ->
      // Skip if candidate selector is empty
      if (candidateSelector.isBlank()) {
        return null
      }

      val matches = context.getMatches(candidateSelector!!)

      // If unique, verify it's at the target location
      if (matches is ElementMatches.SingleMatch) {
        if (centerPointsMatch(
            matches.viewHierarchyTreeNode.centerPoint,
            targetCenterPoint,
            context.centerPointTolerancePx,
          )
        ) {
          return matches.trailblazeElementSelector
        }
      }

      // If not unique, find the match at target center point and compute index
      if (matches is ElementMatches.MultipleMatches) {
        val sortedMatches = matches.viewHierarchyTreeNodes
          .sortedWith(
            compareBy(
              { it.bounds?.y1 ?: Int.MAX_VALUE },
              { it.bounds?.x1 ?: Int.MAX_VALUE },
            ),
          )

        val targetIndex = IndexCalculator.findIndexAtCenterPoint(
          sortedMatches,
          targetCenterPoint,
          context.centerPointTolerancePx,
        )

        if (targetIndex != -1) {
          // If it's the first match (index 0), we can omit the index
          return if (targetIndex == 0) {
            candidateSelector
          } else {
            candidateSelector.copy(index = targetIndex.toString())
          }
        }
      }
    }

    return null
  }

  private fun isEmptySelector(selector: TrailblazeElementSelector): Boolean = selector.textRegex == null &&
    selector.idRegex == null &&
    selector.enabled == null &&
    selector.selected == null &&
    selector.checked == null &&
    selector.focused == null
}
