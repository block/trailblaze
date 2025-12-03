package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.search.ParentChildSelectorFinder
import xyz.block.trailblaze.viewmatcher.strategies.utils.SelectorStrategyHelpers

/** Strategy 4: Target properties + unique child selector. */
internal object UniqueChildStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!
  override val isPerformant = true

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val globallyUniqueChild = ParentChildSelectorFinder.findFirstUniqueDirectChildSelector(
      target = context.target,
      root = context.root,
      trailblazeDevicePlatform = context.trailblazeDevicePlatform,
      widthPixels = context.widthPixels,
      heightPixels = context.heightPixels,
    )

    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueParent = context.uniqueParent
    if (globallyUniqueChild != null) {
      SelectorStrategyHelpers.tryWithAndWithoutParent(
        context = context,
        parentSelector = uniqueParent,
      ) { parent ->
        val targetLeafSelectorNonNull = (targetLeafSelector ?: TrailblazeElementSelector())
        targetLeafSelectorNonNull.copy(childOf = parent, containsChild = globallyUniqueChild)
      }?.let { return it }
    }

    val bestChild = context.bestChildSelectorForContainsChild
    if (bestChild != null && bestChild != globallyUniqueChild) {
      SelectorStrategyHelpers.tryWithAndWithoutParent(
        context = context,
        parentSelector = uniqueParent,
      ) { parent ->
        val targetLeafSelectorNonNull = (targetLeafSelector ?: TrailblazeElementSelector())
        targetLeafSelectorNonNull.copy(childOf = parent, containsChild = bestChild)
      }?.let { return it }
    }

    return null
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> {
    val results = mutableListOf<TrailblazeElementSelector>()
    context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      val uniqueParent = context.uniqueParent

      val globallyUniqueChild = ParentChildSelectorFinder.findFirstUniqueDirectChildSelector(
        target = context.target,
        root = context.root,
        trailblazeDevicePlatform = context.trailblazeDevicePlatform,
        widthPixels = context.widthPixels,
        heightPixels = context.heightPixels,
      )

      if (globallyUniqueChild != null) {
        SelectorStrategyHelpers.tryWithAndWithoutParentAll(
          context = context,
          results = results,
          targetLeafSelector = targetLeafSelector,
          parentSelector = uniqueParent,
        ) { parent ->
          targetLeafSelector.copy(childOf = parent, containsChild = globallyUniqueChild)
        }
      }

      val bestChild = context.bestChildSelectorForContainsChild
      if (bestChild != null && bestChild != globallyUniqueChild) {
        SelectorStrategyHelpers.tryWithAndWithoutParentAll(
          context = context,
          results = results,
          targetLeafSelector = targetLeafSelector,
          parentSelector = uniqueParent,
        ) { parent ->
          targetLeafSelector.copy(childOf = parent, containsChild = bestChild)
        }
      }
    }

    return results
  }
}
