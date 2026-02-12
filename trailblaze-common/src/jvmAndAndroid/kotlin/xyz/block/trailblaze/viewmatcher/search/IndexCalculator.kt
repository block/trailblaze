package xyz.block.trailblaze.viewmatcher.search

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.viewmatcher.matching.CenterPointMatcher
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext

/** Computes Maestro-compatible indices (Y position, then X position) for ambiguous selectors. */
internal object IndexCalculator {

  data class IndexedSelector(
    val selector: TrailblazeElementSelector,
    val index: Int,
  )

  fun computeIndexForSelector(
    selector: TrailblazeElementSelector,
    context: SelectorSearchContext,
  ): IndexedSelector? {
    val matches = context.getMatches(selector)

    if (matches !is ElementMatches.MultipleMatches) {
      return null // Can't use index without multiple candidates
    }

    // Sort using Maestro's ordering (Y position, then X position)
    val sortedMatches = matches.viewHierarchyTreeNodes
      .map { it.clearAllNodeIdsForThisAndAllChildren() }
      .sortedWith(
        compareBy(
          { it.bounds?.y1 ?: Int.MAX_VALUE },
          { it.bounds?.x1 ?: Int.MAX_VALUE },
        ),
      )

    val targetNormalized = context.target.clearAllNodeIdsForThisAndAllChildren()
    val targetIndex = sortedMatches.indexOfFirst {
      it.compareEqualityBasedOnBoundsNotDimensions(targetNormalized)
    }

    if (targetIndex != -1) {
      return IndexedSelector(
        selector = matches.trailblazeElementSelector.copy(index = targetIndex.toString()),
        index = targetIndex,
      )
    }

    // Target not found in matches - this can happen when:
    // 1. Maestro's case-insensitive text matching causes the selector to match both
    //    a parent (e.g., accessibilityText: "Alarm") and its child (e.g., text: "ALARM")
    // 2. The comparison fails because one is a parent with children and one is a leaf
    // In this case, we return null and try a more specific selector
    return null
  }

  /**
   * Finds the index of the first match at the target's center point within a sorted list.
   *
   * This is used when a selector matches multiple elements, and we need to find which one
   * is at the target's exact location. The sortedMatches list must be pre-sorted using
   * Maestro's ordering (Y position, then X position).
   *
   * @param sortedMatches List of matches sorted by Y position, then X position
   * @param targetCenterPoint Center point string in "x,y" format to match against
   * @param centerPointTolerancePx Tolerance in pixels for center point matching (default: 1px)
   * @return Index of the first match at the target's center point, or -1 if not found
   */
  fun findIndexAtCenterPoint(
    sortedMatches: List<ViewHierarchyTreeNode>,
    targetCenterPoint: String?,
    centerPointTolerancePx: Int,
  ): Int = sortedMatches.indexOfFirst {
    CenterPointMatcher.centerPointsMatch(
      it.centerPoint,
      targetCenterPoint,
      centerPointTolerancePx,
    )
  }
}
