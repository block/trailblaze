package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext

/** Strategy 1: Uses only target's direct properties (text, id, state). */
internal object TargetPropertiesStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!
  override val isPerformant = true

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()

    if (targetLeafSelector != null) {
      val matches = context.getMatches(targetLeafSelector)
      if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
        return matches.trailblazeElementSelector
      }
    }

    return null
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> = findAllWithContext(context).map { it.selector }

  override fun findAllWithContext(context: SelectorSearchContext): List<SelectorWithContext> {
    val results = mutableListOf<SelectorWithContext>()
    context.target.asTrailblazeElementSelector()?.let { targetLeafSelector ->
      val matches = context.getMatches(targetLeafSelector)

      if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
        results.add(
          SelectorWithContext(
            matches.trailblazeElementSelector,
            "Target properties (direct match)",
          ),
        )
      }
    }

    return results
  }
}
