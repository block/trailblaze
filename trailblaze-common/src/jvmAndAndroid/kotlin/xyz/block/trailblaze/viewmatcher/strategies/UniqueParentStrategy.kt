package xyz.block.trailblaze.viewmatcher.strategies

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext

/** Strategy 2: Target properties + unique parent constraint. */
internal object UniqueParentStrategy : SelectorStrategy {
  override val name = this::class.simpleName!!
  override val isPerformant = true

  override fun findFirst(context: SelectorSearchContext): TrailblazeElementSelector? {
    val uniqueParent = context.uniqueParent ?: return null
    val targetLeafSelectorNonNull = (context.target.asTrailblazeElementSelector() ?: TrailblazeElementSelector())
    val selectorWithParent = targetLeafSelectorNonNull.copy(childOf = uniqueParent)
    val matches = context.getMatches(selectorWithParent)

    return if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
      matches.trailblazeElementSelector
    } else {
      null
    }
  }

  override fun findAll(context: SelectorSearchContext): List<TrailblazeElementSelector> = findAllWithContext(context).map { it.selector }

  override fun findAllWithContext(context: SelectorSearchContext): List<SelectorWithContext> {
    val results = mutableListOf<SelectorWithContext>()
    val uniqueParent = context.uniqueParent

    if (uniqueParent != null) {
      val targetLeafSelector = context.target.asTrailblazeElementSelector()
      targetLeafSelector?.copy(childOf = uniqueParent)?.let { selectorWithParent ->
        val matches = context.getMatches(selectorWithParent)

        if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
          // Build a description of which parent properties were used
          val parentProps = mutableListOf<String>()
          if (uniqueParent.textRegex != null) parentProps.add("text")
          if (uniqueParent.idRegex != null) parentProps.add("id")
          if (uniqueParent.enabled != null) parentProps.add("enabled")
          if (uniqueParent.selected != null) parentProps.add("selected")
          if (uniqueParent.checked != null) parentProps.add("checked")
          if (uniqueParent.focused != null) parentProps.add("focused")

          val propsText = if (parentProps.isNotEmpty()) {
            " (parent: ${parentProps.joinToString(", ")})"
          } else {
            ""
          }

          results.add(
            SelectorWithContext(
              matches.trailblazeElementSelector,
              "Target + unique parent$propsText",
            ),
          )
        }
      }
    }

    return results
  }
}
