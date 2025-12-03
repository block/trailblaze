package xyz.block.trailblaze.viewmatcher

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.viewmatcher.matching.CenterPointMatcher
import xyz.block.trailblaze.viewmatcher.matching.ElementMatcherUsingMaestro
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.models.ElementSelectorOption
import xyz.block.trailblaze.viewmatcher.models.OrderedSpatialHints
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.strategies.ContainsChildMultiParentStrategy
import xyz.block.trailblaze.viewmatcher.strategies.ContainsDescendantsMultiParentStrategy
import xyz.block.trailblaze.viewmatcher.strategies.IndexStrategy
import xyz.block.trailblaze.viewmatcher.strategies.SameCenterAncestorsStrategy
import xyz.block.trailblaze.viewmatcher.strategies.SameCenterDescendantsStrategy
import xyz.block.trailblaze.viewmatcher.strategies.SelectorStrategy
import xyz.block.trailblaze.viewmatcher.strategies.SpatialHintsStrategy
import xyz.block.trailblaze.viewmatcher.strategies.TargetPropertiesStrategy
import xyz.block.trailblaze.viewmatcher.strategies.TracingSelectorStrategy
import xyz.block.trailblaze.viewmatcher.strategies.UniqueChildStrategy
import xyz.block.trailblaze.viewmatcher.strategies.UniqueDescendantsStrategy
import xyz.block.trailblaze.viewmatcher.strategies.UniqueParentStrategy

/**
 * Result containing both the selector and the strategy used to generate it.
 *
 * @property selector The generated element selector
 * @property strategyName The name of the strategy that successfully generated this selector
 */
data class SelectorWithStrategy(
  val selector: TrailblazeElementSelector,
  val strategyName: String,
)

/**
 * Orchestrator for generating element selectors.
 *
 * Tries strategies (in `strategies` package) from simplest to most complex until one succeeds.
 * Search utilities (in `search` package) provide hierarchy navigation, selector building, and simplification.
 */
object TapSelectorV2 {

  private val strategies: List<SelectorStrategy> = listOf(
    TargetPropertiesStrategy,
    UniqueChildStrategy,
    UniqueParentStrategy,
    SameCenterDescendantsStrategy,
    SameCenterAncestorsStrategy,
    SpatialHintsStrategy,
    UniqueDescendantsStrategy,
    ContainsChildMultiParentStrategy,
    ContainsDescendantsMultiParentStrategy,
    IndexStrategy,
  ).map { TracingSelectorStrategy(it) }

  /**
   * Finds valid selectors optimized for UI/inspector use (e.g., hover, click interactions).
   *
   * This method is optimized for speed by:
   * - Only running the 6 most performant strategies (skips expensive multi-parent/descendant strategies)
   * - Skipping simplified variants (which require many additional tree traversals)
   * - Limiting results via [maxResults] to avoid excessive computation
   *
   * ## When to use
   * - **Use this**: For UI inspector hover/click interactions where you need multiple selector options quickly
   * - **Use [findBestTrailblazeElementSelectorForTargetNode]**: For production selector generation (single best result)
   *
   * @param root Root of the view hierarchy to search within
   * @param target The element to generate selectors for
   * @param trailblazeDevicePlatform Platform (Android/iOS) for selector matching semantics
   * @param widthPixels Screen width in pixels for layout calculations
   * @param heightPixels Screen height in pixels for layout calculations
   * @param spatialHints Optional LLM-provided hints about spatial relationships (e.g., "above button X")
   * @param centerPointTolerancePx Tolerance for matching center points in pixels (default: 1px).
   *   Allows matching elements whose center points differ by up to this amount due to rounding.
   * @param maxResults Maximum number of selectors to return (default: 10). Search stops early once limit is reached.
   * @return List of valid selectors with strategy descriptions, ordered from simplest to most complex
   */
  fun findValidSelectorsForUI(
    root: ViewHierarchyTreeNode,
    target: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
    spatialHints: OrderedSpatialHints?,
    centerPointTolerancePx: Int = CenterPointMatcher.DEFAULT_TOLERANCE_PX,
    maxResults: Int = 10,
  ): List<ElementSelectorOption> {
    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
      spatialHints = spatialHints,
      centerPointTolerancePx = centerPointTolerancePx,
    )

    val options = mutableListOf<ElementSelectorOption>()

    // Only use performant strategies (skip expensive multi-parent and descendant strategies)
    for (strategy: SelectorStrategy in strategies.filter { it.isPerformant }) {
      if (options.size >= maxResults) break

      // Get selectors with context from this strategy (no simplification to save time)
      val selectorsWithContext = strategy.findAllWithContext(context)
      selectorsWithContext.forEach { selectorWithContext ->
        if (options.size < maxResults) {
          options.add(
            ElementSelectorOption(
              selectorWithContext.selector,
              selectorWithContext.description,
              isSimplified = false,
            ),
          )
        }
      }
    }

    return options
  }

  /**
   * Finds the single best selector for the target element using a prioritized strategy approach.
   *
   * This is the **primary API** for production selector generation. It:
   * - Tries strategies in order from simplest to most complex
   * - Returns immediately on first successful match (fast for most cases)
   * - Always succeeds (falls back to index-based selector if needed)
   *
   * ## Strategy order (tried in sequence until one succeeds)
   * 1. **Target properties only** - text, id, or state (fastest, most maintainable)
   * 2. **Same center descendants** - descendants at same tap location
   * 3. **Same center ancestors** - ancestors at same tap location (limited to 3 levels)
   * 4. **Unique parent** - childOf constraint to disambiguate
   * 5. **Spatial hints** - LLM-provided spatial relationships (semantic, maintainable)
   * 6. **Unique child** - containsChild constraint
   * 7. **Unique descendants** - containsDescendants for nested content
   * 8. **Multi-parent child** - tries multiple parent levels with containsChild
   * 9. **Multi-parent descendants** - tries multiple parent levels with containsDescendants
   * 10. **Index** - last resort, position-based (brittle but always works)
   *
   * ## When to use
   * - **Use this**: For production selector generation in tests/automation
   * - **Use [findValidSelectorsForUI]**: For interactive UI tools where you need multiple selector options quickly
   *
   * @param root Root of the view hierarchy to search within
   * @param target The element to generate a selector for
   * @param trailblazeDevicePlatform Platform (Android/iOS) for selector matching semantics
   * @param widthPixels Screen width in pixels for layout calculations
   * @param heightPixels Screen height in pixels for layout calculations
   * @param spatialHints Optional LLM-provided hints about spatial relationships
   * @param centerPointTolerancePx Tolerance for matching center points in pixels (default: 1px)
   * @return The best selector found (guaranteed to uniquely match the target element)
   */
  fun findBestTrailblazeElementSelectorForTargetNode(
    root: ViewHierarchyTreeNode,
    target: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
    spatialHints: OrderedSpatialHints?,
    centerPointTolerancePx: Int = CenterPointMatcher.DEFAULT_TOLERANCE_PX,
  ): TrailblazeElementSelector = findBestTrailblazeElementSelectorForTargetNodeWithStrategy(
    root = root,
    target = target,
    trailblazeDevicePlatform = trailblazeDevicePlatform,
    widthPixels = widthPixels,
    heightPixels = heightPixels,
    spatialHints = spatialHints,
    centerPointTolerancePx = centerPointTolerancePx,
  ).selector

  /**
   * Same as [findBestTrailblazeElementSelectorForTargetNode] but also returns which strategy was used.
   * Useful for testing, debugging, and analytics.
   */
  fun findBestTrailblazeElementSelectorForTargetNodeWithStrategy(
    root: ViewHierarchyTreeNode,
    target: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
    spatialHints: OrderedSpatialHints?,
    centerPointTolerancePx: Int = CenterPointMatcher.DEFAULT_TOLERANCE_PX,
  ): SelectorWithStrategy {
    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
      spatialHints = spatialHints,
      centerPointTolerancePx = centerPointTolerancePx,
    )

    // Try each strategy until one returns a result
    strategies.forEach { strategy ->
      strategy.findFirst(context)?.let { selector ->
        return SelectorWithStrategy(
          selector = selector,
          strategyName = strategy.name,
        )
      }
    }

    // IndexStrategy should always return a result, so this should never happen
    error("No selector found for target node ${target.nodeId}. This should never happen.")
  }

  /** Returns (centerX, centerY) if selector finds exactly one match, null otherwise. */
  fun findNodeCenterUsingSelector(
    root: ViewHierarchyTreeNode,
    selector: TrailblazeElementSelector,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): Pair<Int, Int>? {
    val matches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
      rootTreeNode = root,
      trailblazeElementSelector = selector,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
    )

    return when (matches) {
      is ElementMatches.SingleMatch -> {
        val bounds = matches.viewHierarchyTreeNode.bounds
        if (bounds != null) {
          val centerX = (bounds.x1 + bounds.x2) / 2
          val centerY = (bounds.y1 + bounds.y2) / 2
          Pair(centerX, centerY)
        } else {
          null
        }
      }

      else -> null
    }
  }
}
