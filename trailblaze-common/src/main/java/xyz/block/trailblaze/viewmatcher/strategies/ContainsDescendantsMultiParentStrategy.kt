package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.strategies.utils.SelectorStrategyHelpers

/** Strategy 7: Target + containsDescendants + up to 5 parent levels for tighter constraints. */
internal object ContainsDescendantsMultiParentStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueDescendants = context.uniqueDescendants ?: return null

    return SelectorStrategyHelpers.tryWithParentLevels(context) { parentSelector ->
      targetLeafSelector?.copy(
        childOf = parentSelector,
        containsDescendants = uniqueDescendants,
      )
    }
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> {
    val results = mutableListOf<TrailblazeElementSelector>()
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueDescendants = context.uniqueDescendants ?: return results

    SelectorStrategyHelpers.tryWithParentLevelsAll(context, results) { parentSelector ->
      targetLeafSelector?.copy(
        childOf = parentSelector,
        containsDescendants = uniqueDescendants,
      )
    }

    return results
  }
}
