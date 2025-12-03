package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.search.IndexCalculator

/** Strategy 8: Last resort using position-based index. Never combines index with childOf. Always succeeds. */
internal object IndexStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector {
    val bestChild = context.bestChildSelectorForContainsChild

    val candidateSelectors = mutableListOf<IndexCalculator.IndexedSelector>()

    context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      // Option 1: containsChild + index (no parent)
      if (bestChild != null) {
        val selectorWithChild = targetLeafSelector.copy(
          containsChild = bestChild,
        )
        IndexCalculator.computeIndexForSelector(selectorWithChild, context)?.let {
          candidateSelectors.add(it)
        }
      }
      // Option 2: containsDescendants + index (no parent)
      if (context.uniqueDescendants != null) {
        val selectorWithDescendants = targetLeafSelector.copy(
          containsDescendants = context.uniqueDescendants,
        )
        IndexCalculator.computeIndexForSelector(selectorWithDescendants, context)?.let {
          candidateSelectors.add(it)
        }
      }

      // Option 3: just target properties + index (no parent)
      IndexCalculator.computeIndexForSelector(targetLeafSelector, context)?.let {
        candidateSelectors.add(it)
      }
    }

    // Option 4: empty selector + index (absolute last resort)
    // This will match all clickable elements in the hierarchy
    if (candidateSelectors.isEmpty()) {
      val emptySelector = TrailblazeElementSelector()
      IndexCalculator.computeIndexForSelector(emptySelector, context)?.let {
        candidateSelectors.add(it)
      }
    }

    // Return the selector with the LOWEST index (most maintainable)
    return candidateSelectors.minByOrNull { it.index }?.selector
      ?: error("Index fallback failed for target node ${context.target.nodeId}. This should never happen.")
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> {
    val results = mutableListOf<TrailblazeElementSelector>()
    val bestChild = context.bestChildSelectorForContainsChild

    context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      // Try all index strategies and collect them all
      if (bestChild != null) {
        val selectorWithChild = targetLeafSelector.copy(containsChild = bestChild)
        IndexCalculator.computeIndexForSelector(selectorWithChild, context)?.let { results.add(it.selector) }
      }

      if (context.uniqueDescendants != null) {
        val selectorWithDescendants = targetLeafSelector.copy(
          containsDescendants = context.uniqueDescendants,
        )
        IndexCalculator.computeIndexForSelector(selectorWithDescendants, context)?.let { results.add(it.selector) }
      }

      IndexCalculator.computeIndexForSelector(targetLeafSelector, context)?.let { results.add(it.selector) }
    }
    // Empty selector + index (last resort)
    if (results.isEmpty()) {
      val emptySelector = TrailblazeElementSelector()
      IndexCalculator.computeIndexForSelector(emptySelector, context)?.let { results.add(it.selector) }
    }

    return results
  }
}
