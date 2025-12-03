package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.strategies.utils.SelectorStrategyHelpers

/** Strategy 5: Target properties + unique descendant selectors. */
internal object UniqueDescendantsStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!
  override val isPerformant = true

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    return context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      val uniqueDescendants = context.uniqueDescendants ?: return null
      val uniqueParent = context.uniqueParent
      SelectorStrategyHelpers.tryWithAndWithoutParent(
        context = context,
        parentSelector = uniqueParent,
      ) { parent ->
        targetLeafSelector.copy(childOf = parent, containsDescendants = uniqueDescendants)
      }
    }
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> {
    val results = mutableListOf<TrailblazeElementSelector>()
    context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      val uniqueParent = context.uniqueParent
      val uniqueDescendants = context.uniqueDescendants ?: return results

      SelectorStrategyHelpers.tryWithAndWithoutParentAll(
        context = context,
        results = results,
        targetLeafSelector = targetLeafSelector,
        parentSelector = uniqueParent,
      ) { parent ->
        targetLeafSelector.copy(childOf = parent, containsDescendants = uniqueDescendants)
      }
    }

    return results
  }
}
