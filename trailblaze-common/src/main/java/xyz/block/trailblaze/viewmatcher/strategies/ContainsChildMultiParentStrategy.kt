package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.strategies.utils.SelectorStrategyHelpers

/** Strategy 6: Target + containsChild + up to 5 parent levels for tighter constraints. */
internal object ContainsChildMultiParentStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val bestChild = context.bestChildSelectorForContainsChild ?: return null

    return SelectorStrategyHelpers.tryWithParentLevels(context) { parentSelector ->
      targetLeafSelector?.copy(
        childOf = parentSelector,
        containsChild = bestChild,
      )
    }
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> {
    val results = mutableListOf<TrailblazeElementSelector>()
    val bestChild = context.bestChildSelectorForContainsChild ?: return results

    context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      SelectorStrategyHelpers.tryWithParentLevelsAll(context, results) { parentSelector ->
        targetLeafSelector.copy(
          childOf = parentSelector,
          containsChild = bestChild,
        )
      }
    }

    return results
  }
}
