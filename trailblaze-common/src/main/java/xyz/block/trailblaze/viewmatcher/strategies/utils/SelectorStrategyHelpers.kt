package xyz.block.trailblaze.viewmatcher.strategies.utils

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.viewmatcher.models.SelectorSearchContext
import xyz.block.trailblaze.viewmatcher.search.HierarchyNavigator

/**
 * Common helper utilities for selector strategies to reduce duplication.
 */
internal object SelectorStrategyHelpers {

  /**
   * Tries a selector with and without parent constraint, returning the first match.
   *
   * Pattern: Try selector alone first (simpler), then try with parent constraint if needed.
   * This is used by strategies that add constraints like containsChild, containsDescendants, etc.
   *
   * @param context Search context
   * @param targetLeafSelector Base selector for the target element
   * @param parentSelector Optional parent constraint to try if base selector isn't unique
   * @param selectorBuilder Builds the selector with the given parent (or null for no parent)
   * @return Matching selector if found, null otherwise
   */
  fun tryWithAndWithoutParent(
    context: SelectorSearchContext,
    parentSelector: TrailblazeElementSelector?,
    selectorBuilder: (parent: TrailblazeElementSelector?) -> TrailblazeElementSelector,
  ): TrailblazeElementSelector? {
    // Try without parent first (simpler selector)
    val selectorWithoutParent = selectorBuilder(null)
    val matchesWithoutParent = context.getMatches(selectorWithoutParent)

    if (matchesWithoutParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithoutParent)) {
      return matchesWithoutParent.trailblazeElementSelector
    }

    // Try with parent constraint
    if (parentSelector != null) {
      val selectorWithParent = selectorBuilder(parentSelector)
      val matchesWithParent = context.getMatches(selectorWithParent)

      if (matchesWithParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithParent)) {
        return matchesWithParent.trailblazeElementSelector
      }
    }

    return null
  }

  /**
   * Tries a selector with and without parent constraint, adding all matches to results list.
   *
   * Same as [tryWithAndWithoutParent] but collects all valid selectors instead of early-exiting.
   *
   * @param context Search context
   * @param results List to add matching selectors to
   * @param targetLeafSelector Base selector for the target element
   * @param parentSelector Optional parent constraint to try if base selector isn't unique
   * @param selectorBuilder Builds the selector with the given parent (or null for no parent)
   */
  fun tryWithAndWithoutParentAll(
    context: SelectorSearchContext,
    results: MutableList<TrailblazeElementSelector>,
    targetLeafSelector: TrailblazeElementSelector,
    parentSelector: TrailblazeElementSelector?,
    selectorBuilder: (parent: TrailblazeElementSelector?) -> TrailblazeElementSelector,
  ) {
    // Try without parent first (simpler selector)
    val selectorWithoutParent = selectorBuilder(null)
    val matchesWithoutParent = context.getMatches(selectorWithoutParent)

    if (matchesWithoutParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithoutParent)) {
      results.add(matchesWithoutParent.trailblazeElementSelector)
    }

    // Try with parent constraint
    if (parentSelector != null) {
      val selectorWithParent = selectorBuilder(parentSelector)
      val matchesWithParent = context.getMatches(selectorWithParent)

      if (matchesWithParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithParent)) {
        results.add(matchesWithParent.trailblazeElementSelector)
      }
    }
  }

  /**
   * Tries building a selector with up to 5 parent levels, returning the first match.
   *
   * For each ancestor (up to 5 levels up from target), checks if it has meaningful constraints
   * (textRegex, idRegex, or containsChild), verifies it uniquely matches, then tries building
   * a selector using it as a parent constraint.
   *
   * @param context Search context
   * @param selectorBuilder Builds the selector with the given parent constraint
   * @return Matching selector if found, null otherwise
   */
  fun tryWithParentLevels(
    context: SelectorSearchContext,
    selectorBuilder: (TrailblazeElementSelector) -> TrailblazeElementSelector?,
  ): TrailblazeElementSelector? {
    val pathToTarget = HierarchyNavigator.findPathToTarget(context.target, context.root)
    val ancestorsToTry = pathToTarget.reversed().take(5)

    for (ancestor in ancestorsToTry) {
      ancestor.asTrailblazeElementSelector()?.let { parentSelector ->
        if (parentSelector.textRegex == null && parentSelector.idRegex == null && parentSelector.containsChild == null) {
          return@let
        }

        val parentMatches = context.getMatches(parentSelector)
        if (parentMatches !is ElementMatches.SingleMatch) {
          return@let
        }

        selectorBuilder(parentSelector)?.let { selector ->
          val matches = context.getMatches(selector)

          if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
            return matches.trailblazeElementSelector
          }
        }
      }
    }

    return null
  }

  /**
   * Tries building a selector with up to 5 parent levels, adding all matches to results list.
   *
   * Same as [tryWithParentLevels] but collects all valid selectors instead of early-exiting.
   *
   * @param context Search context
   * @param results List to add matching selectors to
   * @param selectorBuilder Builds the selector with the given parent constraint
   */
  fun tryWithParentLevelsAll(
    context: SelectorSearchContext,
    results: MutableList<TrailblazeElementSelector>,
    selectorBuilder: (TrailblazeElementSelector) -> TrailblazeElementSelector?,
  ) {
    val pathToTarget = HierarchyNavigator.findPathToTarget(context.target, context.root)
    val ancestorsToTry = pathToTarget.reversed().take(5)

    for (ancestor in ancestorsToTry) {
      ancestor.asTrailblazeElementSelector()?.let { parentSelector ->
        if (parentSelector.textRegex == null && parentSelector.idRegex == null && parentSelector.containsChild == null) {
          return@let
        }

        val parentMatches = context.getMatches(parentSelector)
        if (parentMatches !is ElementMatches.SingleMatch) {
          return@let
        }

        selectorBuilder(parentSelector)?.let { selector ->
          val matches = context.getMatches(selector)

          if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
            results.add(matches.trailblazeElementSelector)
          }
        }
      }
    }
  }
}
