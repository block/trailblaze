package xyz.block.trailblaze.waypoint

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.SelectorTemplating
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver.ResolveResult
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry

object WaypointMatcher {

  fun match(
    definition: WaypointDefinition,
    screenState: ScreenState,
    target: TargetTemplateContext? = null,
  ): WaypointMatchResult {
    val tree = screenState.trailblazeNodeTree
      ?: return WaypointMatchResult(
        definitionId = definition.id,
        matched = false,
        matchedRequired = emptyList(),
        missingRequired = emptyList(),
        presentForbidden = emptyList(),
        skipped = WaypointMatchResult.SkipReason.NO_NODE_TREE_IN_SCREEN_STATE,
      )
    return match(definition, tree, target)
  }

  fun match(
    definition: WaypointDefinition,
    root: TrailblazeNode,
    target: TargetTemplateContext? = null,
  ): WaypointMatchResult {
    // Pre-expand once per entry so we can both (a) feed the resolver an already-substituted
    // selector and (b) detect any survived placeholder up front. Resolver-internal expansion
    // works for the happy path but doesn't surface "the placeholder didn't expand" — which
    // matters here: a `forbidden` selector with an un-substituted `{{target.appId}}` would
    // silently fail to match anything, the forbidden check would pass, and a waypoint that
    // shouldn't match would be reported as matched. Fail closed by skipping the whole
    // definition with UNRESOLVED_TARGET_TEMPLATE the moment any survived placeholder shows up.
    val expandedRequired = definition.required.map { it.expandedWith(target) }
    val expandedForbidden = definition.forbidden.map { it.expandedWith(target) }
    val hasUnresolvedTemplate = (expandedRequired + expandedForbidden).any { entry ->
      SelectorTemplating.containsUnresolvedPlaceholder(entry.selector)
    }
    if (hasUnresolvedTemplate) {
      return WaypointMatchResult(
        definitionId = definition.id,
        matched = false,
        matchedRequired = emptyList(),
        missingRequired = emptyList(),
        presentForbidden = emptyList(),
        skipped = WaypointMatchResult.SkipReason.UNRESOLVED_TARGET_TEMPLATE,
      )
    }

    val matchedRequired = mutableListOf<WaypointMatchResult.MatchedRequired>()
    val missingRequired = mutableListOf<WaypointMatchResult.MissingRequired>()
    for (entry in expandedRequired) {
      val count = countMatches(root, entry.selector)
      if (count >= entry.minCount) {
        matchedRequired += WaypointMatchResult.MatchedRequired(entry, count)
      } else {
        missingRequired += WaypointMatchResult.MissingRequired(entry, count)
      }
    }

    val presentForbidden = mutableListOf<WaypointMatchResult.PresentForbidden>()
    for (entry in expandedForbidden) {
      val count = countMatches(root, entry.selector)
      if (count > 0) {
        presentForbidden += WaypointMatchResult.PresentForbidden(entry, count)
      }
    }

    return WaypointMatchResult(
      definitionId = definition.id,
      matched = missingRequired.isEmpty() && presentForbidden.isEmpty(),
      matchedRequired = matchedRequired,
      missingRequired = missingRequired,
      presentForbidden = presentForbidden,
    )
  }

  private fun WaypointSelectorEntry.expandedWith(target: TargetTemplateContext?): WaypointSelectorEntry =
    if (target == null) this else copy(selector = SelectorTemplating.expand(selector, target))

  private fun countMatches(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): Int = when (val r = TrailblazeNodeSelectorResolver.resolve(root, selector)) {
    is ResolveResult.SingleMatch -> 1
    is ResolveResult.MultipleMatches -> r.nodes.size
    is ResolveResult.NoMatch -> 0
  }
}
