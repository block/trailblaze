package xyz.block.trailblaze.waypoint

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver.ResolveResult
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.api.waypoint.WaypointSelectorEntry

object WaypointMatcher {

  fun match(definition: WaypointDefinition, screenState: ScreenState): WaypointMatchResult {
    val tree = screenState.trailblazeNodeTree
      ?: return WaypointMatchResult(
        definitionId = definition.id,
        matched = false,
        matchedRequired = emptyList(),
        missingRequired = emptyList(),
        presentForbidden = emptyList(),
        skipped = WaypointMatchResult.SkipReason.NO_NODE_TREE_IN_SCREEN_STATE,
      )
    return match(definition, tree)
  }

  fun match(definition: WaypointDefinition, root: TrailblazeNode): WaypointMatchResult {
    val matchedRequired = mutableListOf<WaypointMatchResult.MatchedRequired>()
    val missingRequired = mutableListOf<WaypointMatchResult.MissingRequired>()
    for (entry in definition.required) {
      val count = countMatches(root, entry.selector)
      if (count >= entry.minCount) {
        matchedRequired += WaypointMatchResult.MatchedRequired(entry, count)
      } else {
        missingRequired += WaypointMatchResult.MissingRequired(entry, count)
      }
    }

    val presentForbidden = mutableListOf<WaypointMatchResult.PresentForbidden>()
    for (entry in definition.forbidden) {
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

  private fun countMatches(root: TrailblazeNode, selector: TrailblazeNodeSelector): Int =
    when (val r = TrailblazeNodeSelectorResolver.resolve(root, selector)) {
      is ResolveResult.SingleMatch -> 1
      is ResolveResult.MultipleMatches -> r.nodes.size
      is ResolveResult.NoMatch -> 0
    }
}
