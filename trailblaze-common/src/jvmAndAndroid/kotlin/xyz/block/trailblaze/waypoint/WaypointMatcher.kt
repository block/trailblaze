package xyz.block.trailblaze.waypoint

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.SelectorTemplating
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver.ResolveResult
import xyz.block.trailblaze.api.waypoint.ResolvedWaypoint
import xyz.block.trailblaze.api.waypoint.WaypointCondition
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.api.waypoint.WaypointMatchResult
import xyz.block.trailblaze.api.waypoint.resolveFor
import xyz.block.trailblaze.devices.TrailblazeClassifierLineage
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier

/**
 * Matches a waypoint against a captured screen. v2 waypoints are classifier-keyed, so matching is
 * a two-step operation: first **resolve** the definition for the connected device's classifier
 * (closest-wins up its lineage → a [ResolvedWaypoint]), then evaluate that resolved view's
 * `required`/`forbidden` conditions against the tree.
 *
 * The convenience overloads that take a [ScreenState] derive the classifier from the screen
 * itself (its [ScreenState.deviceClassifiers], falling back to its platform), so existing call
 * sites don't have to thread a classifier through — the screen already knows which device it came
 * from. The [ResolvedWaypoint] overload is the core every other overload funnels into.
 */
object WaypointMatcher {

  /** Core matcher: evaluate an already-resolved waypoint view against [root]. */
  fun match(
    resolved: ResolvedWaypoint,
    root: TrailblazeNode,
    target: TargetTemplateContext? = null,
  ): WaypointMatchResult {
    // Pre-expand once per condition so we can both (a) feed the resolver an already-substituted
    // selector and (b) detect any survived placeholder up front. A `forbidden` selector with an
    // un-substituted `{{target.appId}}` would silently fail to match anything, the forbidden check
    // would pass, and a waypoint that shouldn't match would be reported as matched. Fail closed by
    // skipping the whole definition with UNRESOLVED_TARGET_TEMPLATE the moment any survived
    // placeholder shows up.
    val expandedRequired = resolved.required.map { it.expandedWith(target) }
    val expandedForbidden = resolved.forbidden.map { it.expandedWith(target) }
    val hasUnresolvedTemplate = (expandedRequired + expandedForbidden).any { entry ->
      entry.selector?.let { SelectorTemplating.containsUnresolvedPlaceholder(it) } == true
    }
    if (hasUnresolvedTemplate) {
      return WaypointMatchResult(
        definitionId = resolved.id,
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
      val count = countMatches(root, entry)
      if (count >= entry.minCount) {
        matchedRequired += WaypointMatchResult.MatchedRequired(entry, count)
      } else {
        missingRequired += WaypointMatchResult.MissingRequired(entry, count)
      }
    }

    val presentForbidden = mutableListOf<WaypointMatchResult.PresentForbidden>()
    for (entry in expandedForbidden) {
      val count = countMatches(root, entry)
      if (count > 0) {
        presentForbidden += WaypointMatchResult.PresentForbidden(entry, count)
      }
    }

    return WaypointMatchResult(
      definitionId = resolved.id,
      matched = missingRequired.isEmpty() && presentForbidden.isEmpty(),
      matchedRequired = matchedRequired,
      missingRequired = missingRequired,
      presentForbidden = presentForbidden,
    )
  }

  /** Resolve [definition] for [classifier] (closest-wins up its lineage), then match. */
  fun match(
    definition: WaypointDefinition,
    classifier: TrailblazeDeviceClassifier,
    root: TrailblazeNode,
    target: TargetTemplateContext? = null,
  ): WaypointMatchResult = match(definition, TrailblazeClassifierLineage.chainFor(classifier), root, target)

  /**
   * Match against a [ScreenState], resolving the classifier from the screen: its reported
   * [ScreenState.deviceClassifiers] (expanded through the shared lineage), falling back to the
   * screen's platform when none are recorded (e.g. session-log-backed screen states). Returns
   * [WaypointMatchResult.SkipReason.NO_NODE_TREE_IN_SCREEN_STATE] when the screen carries no
   * driver-native tree (the surface waypoint selectors resolve against).
   */
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
    return match(definition, classifierFor(screenState), tree, target)
  }

  /**
   * The device classifier chain to resolve a waypoint for, derived from a [ScreenState]:
   * its reported classifiers (most-specific-first, lineage-expanded), or — when none are
   * recorded — the screen's platform as a single classifier.
   */
  fun classifierFor(screenState: ScreenState): List<TrailblazeDeviceClassifier> {
    val reported = screenState.deviceClassifiers.ifEmpty {
      listOf(screenState.trailblazeDevicePlatform.asTrailblazeDeviceClassifier())
    }
    return TrailblazeClassifierLineage.resolutionChain(reported)
  }

  /**
   * Overload of [match] taking a pre-resolved device classifier chain (most-specific-first).
   *
   * **A waypoint that declares no block for any classifier in [classifierChain] does not match.**
   * v2 waypoints are classifier-keyed, so "no block for this device" means "this waypoint doesn't
   * describe a screen on this device" — distinct from a block that exists but happens to declare no
   * conditions (which still matches vacuously, the same as a v1 empty-required waypoint). This guard
   * is what stops an iOS-only waypoint from matching every Android screen (and vice-versa).
   */
  fun match(
    definition: WaypointDefinition,
    classifierChain: List<TrailblazeDeviceClassifier>,
    root: TrailblazeNode,
    target: TargetTemplateContext? = null,
  ): WaypointMatchResult {
    val declaresBlock = classifierChain.any { definition.byClassifier.containsKey(it.classifier) }
    if (!declaresBlock) {
      // Surface "this waypoint has no block for this device's classifier" as an explicit skip,
      // not a bare non-match — so an author debugging "why doesn't it match on iOS" sees "add an
      // ios: block" rather than mistaking it for failing selectors.
      return WaypointMatchResult(
        definitionId = definition.id,
        matched = false,
        matchedRequired = emptyList(),
        missingRequired = emptyList(),
        presentForbidden = emptyList(),
        skipped = WaypointMatchResult.SkipReason.NO_CLASSIFIER_BLOCK,
      )
    }
    val primary = classifierChain.firstOrNull() ?: TrailblazeDeviceClassifier("")
    return match(definition.resolveFor(primary, classifierChain), root, target)
  }

  private fun WaypointCondition.expandedWith(target: TargetTemplateContext?): WaypointCondition {
    val sel = selector ?: return this
    return if (target == null) this else copy(selector = SelectorTemplating.expand(sel, target))
  }

  /**
   * Match count for a condition. A condition with no [WaypointCondition.selector] (a future
   * non-selector condition kind this matcher version doesn't yet evaluate) counts as 0 — so a
   * `required` such condition fails (unsatisfiable) and a `forbidden` one passes (harmless), which
   * is the safe default until that kind's evaluation is implemented here.
   */
  private fun countMatches(root: TrailblazeNode, entry: WaypointCondition): Int {
    val selector = entry.selector ?: return 0
    return countMatches(root, selector)
  }

  private fun countMatches(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): Int = when (val r = TrailblazeNodeSelectorResolver.resolve(root, selector)) {
    is ResolveResult.SingleMatch -> 1
    is ResolveResult.MultipleMatches -> r.nodes.size
    is ResolveResult.NoMatch -> 0
  }
}
