package xyz.block.trailblaze.viewmatcher.models

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewmatcher.matching.CenterPointMatcher
import xyz.block.trailblaze.viewmatcher.matching.ElementMatcherUsingMaestro
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.search.ParentChildSelectorFinder

/**
 * Context object that holds all the state needed for selector search strategies.
 *
 * This encapsulates the search parameters and provides cached access to computed
 * properties like unique parents and descendants.
 *
 * Note: This is not a data class because it contains lazy properties that would
 * break equality/hashCode semantics. Two contexts with identical inputs but different
 * lazy computation states would have inconsistent equality behavior.
 */
internal class SelectorSearchContext(
  val root: ViewHierarchyTreeNode,
  val target: ViewHierarchyTreeNode,
  val trailblazeDevicePlatform: TrailblazeDevicePlatform,
  val widthPixels: Int,
  val heightPixels: Int,
  val spatialHints: OrderedSpatialHints? = null,
  val centerPointTolerancePx: Int = CenterPointMatcher.DEFAULT_TOLERANCE_PX,
) {
  val uniqueParent: TrailblazeElementSelector? by lazy {
    ParentChildSelectorFinder.findFirstUniqueParentSelector(
      target = target,
      root = root,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
    )
  }

  val uniqueDescendants: List<TrailblazeElementSelector>? by lazy {
    ParentChildSelectorFinder.findUniqueDescendantSelectors(
      targetViewHierarchyTreeNode = target,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
    )
  }

  val bestChildSelectorForContainsChild: TrailblazeElementSelector? by lazy {
    ParentChildSelectorFinder.findMostSpecificChildSelector(
      target = target,
      root = root,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
    )
  }

  /**
   * Attempts to match the given selector against the view hierarchy.
   *
   * Handles expected matching failures (e.g., invalid selectors, reflection errors from Maestro internals)
   * by returning [ElementMatches.NoMatches], allowing selector search strategies to try alternatives.
   * Critical errors (OutOfMemoryError, etc.) are rethrown to prevent masking serious issues.
   */
  fun getMatches(
    selector: TrailblazeElementSelector,
    rootNode: ViewHierarchyTreeNode = root,
  ): ElementMatches = try {
    ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
      rootTreeNode = rootNode,
      trailblazeElementSelector = selector,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
    )
  } catch (e: RuntimeException) {
    // Expected: Maestro internal errors (reflection failures, element not found, invalid selectors).
    // Return NoMatches to allow selector search to continue with other strategies instead of crashing.
    // Note: We don't log these as errors since they're expected during selector exploration.
    ElementMatches.NoMatches(selector)
  } catch (e: Error) {
    // Critical system errors should not be swallowed - rethrow to surface serious issues.
    // Examples: OutOfMemoryError, StackOverflowError, AssertionError
    throw e
  }

  fun isCorrectTarget(match: ElementMatches): Boolean {
    if (match !is ElementMatches.SingleMatch) return false
    val targetNormalized = target.clearAllNodeIdsForThisAndAllChildren()
    val matchNormalized = match.viewHierarchyTreeNode.clearAllNodeIdsForThisAndAllChildren()
    return matchNormalized.compareEqualityBasedOnBoundsNotDimensions(targetNormalized)
  }
}
