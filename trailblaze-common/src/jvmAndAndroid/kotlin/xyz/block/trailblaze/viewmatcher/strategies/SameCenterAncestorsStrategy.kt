package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.search.SameCenterPointFinder

/** Strategy: Finds ancestors with same center point as target. Limited to 3 levels to avoid generic containers. */
internal object SameCenterAncestorsStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!
  override val isPerformant = true

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetCenterPoint = context.target.centerPoint ?: return null

    // Only try ancestors if target has no usable children
    // (otherwise containsChild strategy will handle it better)
    val targetHasUsableChildren = context.target.children.any { child ->
      child.asTrailblazeElementSelector()
        ?.let { childSelector -> childSelector.textRegex != null || childSelector.idRegex != null } ?: false
    }

    if (targetHasUsableChildren) {
      return null
    }

    return SameCenterPointFinder.findFromAncestors(context, targetCenterPoint, maxLevels = 3)?.selector
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> = findAllWithContext(context).map { it.selector }

  override fun findAllWithContext(context: SelectorSearchContext): List<SelectorWithContext> {
    val targetCenterPoint = context.target.centerPoint ?: return emptyList()

    return SameCenterPointFinder.findFromAncestors(
      context = context,
      targetCenterPoint = targetCenterPoint,
      includeDescription = true,
    )?.let { result ->
      listOf(SelectorWithContext(result.selector, result.description))
    } ?: emptyList()
  }
}
