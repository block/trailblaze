package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.search.SameCenterPointFinder

/** Strategy: Finds descendants with same center point as target. Prefers leaf elements. */
internal object SameCenterDescendantsStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!
  override val isPerformant = true

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetCenterPoint = context.target.centerPoint ?: return null
    return SameCenterPointFinder.findFromDescendants(context, targetCenterPoint)?.selector
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> = findAllWithContext(context).map { it.selector }

  override fun findAllWithContext(context: SelectorSearchContext): List<SelectorWithContext> {
    val targetCenterPoint = context.target.centerPoint ?: return emptyList()

    return SameCenterPointFinder.findFromDescendants(
      context,
      targetCenterPoint,
      includeDescription = true,
    )?.let { result ->
      listOf(SelectorWithContext(result.selector, result.description))
    } ?: emptyList()
  }
}
