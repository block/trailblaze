package xyz.block.trailblaze.api

/**
 * Generates [TrailblazeNodeSelector]s that uniquely identify a target [TrailblazeNode]
 * within a tree, using driver-specific properties for maximum precision.
 *
 * Follows a cascading-strategy approach: tries the simplest selector first and falls back
 * to more complex strategies until a unique match is found. Each candidate selector is
 * verified against the [TrailblazeNodeSelectorResolver] to confirm it produces exactly one
 * match (the target) before being returned.
 *
 * ## Android Accessibility strategy cascade (simplest to most complex)
 *
 * **Identity (1):** Unique stable ID, resource ID, or Compose testTag.
 * **Text (2-10):** Precise text matching using the specific text field
 * (contentDescription, hintText, text, resourceId+text, labeledByText).
 * **Type + state (11-17):** paneTitle, className, className + state flags, isPassword,
 * inputType, stateDescription, roleDescription — for elements without unique text.
 * **Hierarchy (18-20):** childOf parent, containsChild, collectionItemInfo.
 * **Spatial (21):** Positional relationships to uniquely identifiable neighbors.
 * **Fallback (22):** Index-based positioning as a last resort.
 *
 * ## Compose strategy cascade (simplest to most complex)
 *
 * **Identity (1):** testTag (developer-assigned, most stable).
 * **Text (2-7):** Precise field matching — contentDescription, text, role, toggleableState.
 * editableText is skipped (user-entered content, not stable identity).
 * **Hierarchy (8-9):** childOf parent, containsChild.
 * **Spatial (10):** Positional relationships to uniquely identifiable neighbors.
 * **Fallback (11):** Index-based positioning as a last resort.
 *
 * ## iOS Maestro strategy cascade (simplest to most complex)
 *
 * **Identity (1):** resourceId (accessibility identifier — most stable on iOS).
 * **Text (2-6):** accessibilityText, hintText, text, text+className, resourceId+text.
 * **Type + state (7-8):** className alone, className + focused/selected.
 * **Hierarchy (9-10):** childOf parent, containsChild.
 * **Spatial (11):** Positional relationships to uniquely identifiable neighbors.
 * **Fallback (12):** Index-based positioning as a last resort.
 *
 * ## Recording flow
 * The caller provides the full tree and the target node. The generator returns the
 * simplest [TrailblazeNodeSelector] that resolves to exactly one match (the target).
 *
 * ## Full enumeration (for hand-authoring)
 * [enumerateSelectorCandidates] lists *every* selector the cascade can compute for an element,
 * each with its strategy name, ranked stable-first — the menu a human picks from when writing a
 * selector by hand (e.g. a `*.waypoint.yaml`). It is not gated on uniqueness (so a repeated
 * label is kept, with its match count surfaced by callers), omits the positional `index` and
 * `containsChild` families, and adds a run-variable-wildcarded text option. See its kdoc.
 *
 * ## Post-generation minimization
 * Every selector returned by [findBestSelector], [findBestStructuralSelector], and
 * [findAllValidSelectors] is run through [TrailblazeNodeSelectorMinimizer] before it
 * leaves the generator. Strategies in the cascade are written to emit kitchen-sink
 * driver matches (every non-null property on the node), and hierarchy helpers like
 * `containsChild` reuse those same kitchen-sink builders for their inner selectors.
 * The minimizer prunes any field that isn't load-bearing for uniqueness — so a
 * recorded selector that today reads
 * `textRegex="Estimates", classNameRegex="android.widget.TextView"` is reduced to
 * `textRegex="Estimates"` whenever the class is just along for the ride. That makes
 * recorded selectors less fragile under widget-implementation changes (e.g. View →
 * Compose) without weakening any case where the class is actually disambiguating.
 *
 * @see TrailblazeNodeSelectorResolver for matching selectors against trees
 * @see TrailblazeNodeSelector for the selector model
 * @see TrailblazeNodeSelectorMinimizer for the post-generation pruning pass
 */
object TrailblazeNodeSelectorGenerator {

  /**
   * A selector together with the strategy that produced it.
   */
  data class NamedSelector(
    val selector: TrailblazeNodeSelector,
    val strategy: String,
    val isBest: Boolean = false,
  )

  /**
   * Finds all valid selectors that uniquely identify [target] within the [root] tree,
   * up to [maxResults]. Each result includes the strategy name that produced it.
   *
   * The first result is the "best" selector (same as [findBestSelector] would return).
   * Useful for UI inspector views that want to show all selector options.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param target The node to find selectors for
   * @param maxResults Maximum number of selectors to return
   * @return List of named selectors, with [NamedSelector.isBest] set on the first
   */
  fun findAllValidSelectors(
    root: TrailblazeNode,
    target: TrailblazeNode,
    maxResults: Int = 5,
  ): List<NamedSelector> {
    val parentMap = buildParentMap(root)
    val namedStrategies = strategiesForDetail(root, target, target.driverDetail, parentMap)

    val results = mutableListOf<NamedSelector>()
    // Track already-emitted minimized selectors so two strategies that converge to the
    // same shape after pruning don't both show up in the list.
    val seen = mutableSetOf<TrailblazeNodeSelector>()
    for ((name, strategy) in namedStrategies) {
      if (results.size >= maxResults) break
      strategy()?.let { raw ->
        if (isUniqueMatch(root, target, raw)) {
          val selector = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
          if (seen.add(selector)) {
            results.add(NamedSelector(selector, name, isBest = results.isEmpty()))
          }
        }
      }
    }

    // Add index fallback if nothing else worked
    if (results.isEmpty()) {
      results.add(NamedSelector(computeIndexSelector(root, target), "Global index fallback", isBest = true))
    }

    return results
  }

  /**
   * Enumerates **every** selector the strategy cascade can compute for [target] — the full menu
   * a human picks from when authoring a selector by hand, rather than the single "best" one
   * [findAllValidSelectors] returns for a tap recording.
   *
   * It differs from [findAllValidSelectors] in three deliberate ways:
   *
   *  1. **Not gated on uniqueness** — a strategy's selector is kept even when it matches several
   *     nodes (a repeated label like "Add money"). Every candidate is minimized and labeled with
   *     its live match count, so the human sees at a glance whether it's unique or a presence
   *     signal — a waypoint wants `>= 1`, a tap wants exactly `1`. Nothing is hidden: when a
   *     label repeats, the plain selector shows up alongside an index- or hierarchy-qualified
   *     variant that *does* disambiguate it — the presence of that qualified variant in the menu
   *     is itself the signal that the plain one wasn't unique.
   *  2. **One extra option the cascade never emits** — a semantic-text selector with its
   *     run-variable tail wildcarded (`"Balance: $0.00"` -> `"Balance:.*"`), added via
   *     [runVariableWildcardedTextCandidate] alongside the literal.
   *  3. **Ranked stable-first** — identity, then semantic text, then structural type, then
   *     `childOf`, then `containsChild`/`containsDescendants`, then spatial, with any
   *     index-qualified variant sorting after its non-indexed sibling (see
   *     [selectorStabilityRank]). Distinct selectors are all kept (e.g. `text` and
   *     `text + class` are separate options); exact duplicates are merged.
   *
   * Every candidate is run through [TrailblazeNodeSelectorMinimizer] before being kept — a
   * no-op for a non-unique selector (the minimizer only prunes fields once uniqueness is
   * established), but it collapses a same-shaped duplicate produced by a different strategy
   * (e.g. the index-fallback strategy's kitchen-sink match, once minimized, is often identical
   * to a simpler strategy's selector already in the menu) down to one entry.
   *
   * Every returned selector is verified to still select [target] ([selectorMatchesTarget]).
   * Even a node with no matchable properties at all still gets one candidate — a bare global
   * `index`, the last-resort signal that always resolves — so an empty list is only possible
   * in the genuine edge case where the target can't be positionally located either.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param target The node to enumerate selectors for
   * @param maxResults Safety cap on the number of selectors returned (default generous — the
   *   cascade produces at most ~20 distinct options)
   * @return Ranked named selectors (most-stable first), or empty if none are paste-worthy
   */
  fun enumerateSelectorCandidates(
    root: TrailblazeNode,
    target: TrailblazeNode,
    maxResults: Int = 25,
  ): List<NamedSelector> {
    val parentMap = buildParentMap(root)
    // Insertion order preserves the cascade's own most-to-least-precise ordering within a
    // stability tier; the final sort only reorders across tiers. First strategy name wins on
    // a duplicate selector.
    val bySelector = LinkedHashMap<TrailblazeNodeSelector, String>()

    fun consider(name: String, raw: TrailblazeNodeSelector?) {
      if (raw == null) return
      // Must still select the target (>= 1 match including it) — not required to be unique.
      if (!selectorMatchesTarget(root, target, raw)) return
      val selector = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
      // The index-fallback strategy's kitchen-sink match is sometimes already unique without
      // needing an index (SingleMatch short-circuit in computeIndexSelectorForMatch) — after
      // minimization that's typically identical to an already-listed simpler selector (deduped
      // below), but on the rare occasion it isn't, "Index fallback" would mislabel a selector
      // that doesn't actually carry an index. Give that specific case an honest generic name.
      val displayName = if (name.contains("index", ignoreCase = true) && selector.index == null) {
        "Combined fields"
      } else {
        name
      }
      // putIfAbsent is JVM-only on MutableMap (backed by java.util.Map's default method) and
      // unavailable on this module's wasmJs target — containsKey/set is the common-compatible
      // equivalent.
      if (!bySelector.containsKey(selector)) {
        bySelector[selector] = displayName
      }
    }

    for ((name, strategy) in strategiesForDetail(root, target, target.driverDetail, parentMap)) {
      consider(name, strategy())
    }
    // The one option the raw cascade never produces: the run-variable-wildcarded text variant.
    runVariableWildcardedTextCandidate(target.driverDetail)?.let { (name, match) ->
      consider(name, TrailblazeNodeSelector.withMatch(match))
    }

    val ranked = bySelector.entries
      .map { NamedSelector(it.key, it.value) }
      .sortedBy { selectorStabilityRank(it.selector) }
      .take(maxResults)
    return ranked.mapIndexed { i, named -> named.copy(isBest = i == 0) }
  }

  /**
   * Finds the best **structural** selector that uniquely identifies [target] within the [root]
   * tree, avoiding all text/content properties that change with localization or dynamic data.
   *
   * Structural selectors rely on:
   * - **Type**: className, role, ariaRole
   * - **Developer-assigned IDs**: resourceId, testTag, cssSelector, dataTestId
   * - **State flags**: isClickable, isScrollable, isEditable, isPassword, isHeading, etc.
   * - **Hierarchy**: childOf, containsChild, index among siblings
   * - **Spatial**: above/below/leftOf/rightOf structurally identifiable neighbors
   * - **Collection position**: row/column indices
   *
   * Content properties that are **excluded**: text, contentDescription, hintText,
   * labeledByText, stateDescription, paneTitle, ariaName, ariaDescriptor, editableText.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param target The node to find a structural selector for
   * @return A [NamedSelector] with the best structural selector, or an index fallback
   */
  fun findBestStructuralSelector(
    root: TrailblazeNode,
    target: TrailblazeNode,
  ): NamedSelector {
    val parentMap = buildParentMap(root)
    val namedStrategies = when (val detail = target.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility ->
        namedStructuralAndroidAccessibilityStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.AndroidMaestro ->
        namedStructuralAndroidMaestroStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Web ->
        namedStructuralWebStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Compose ->
        namedStructuralComposeStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.IosMaestro ->
        namedStructuralIosMaestroStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.IosAxe ->
        namedStructuralIosAxeStrategies(root, target, detail, parentMap)
    }

    for ((name, strategy) in namedStrategies) {
      strategy()?.let { raw ->
        if (isUniqueMatch(root, target, raw)) {
          val selector = TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
          return NamedSelector(selector, name, isBest = true)
        }
      }
    }

    return NamedSelector(
      computeIndexSelector(root, target),
      "Structural: global index fallback",
      isBest = true,
    )
  }

  /**
   * Finds the best selector that uniquely identifies [target] within the [root] tree.
   *
   * Tries strategies from simplest to most complex, returning the first selector
   * that resolves to a [TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch]
   * targeting the correct node.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param target The node to generate a selector for (must be in the tree)
   * @return A selector guaranteed to uniquely match [target], or falls back to index
   */
  fun findBestSelector(root: TrailblazeNode, target: TrailblazeNode): TrailblazeNodeSelector {
    // Pre-compute the parent map once for all strategies that need hierarchy traversal.
    val parentMap = buildParentMap(root)

    // Try each strategy in order until one produces a unique match
    val strategies = strategiesForDetail(root, target, target.driverDetail, parentMap)

    for ((_, strategy) in strategies) {
      strategy()?.let { raw ->
        if (isUniqueMatch(root, target, raw)) {
          return TrailblazeNodeSelectorMinimizer.minimize(root, target, raw)
        }
      }
    }

    // Absolute last resort: index among all nodes (should never fail)
    return computeIndexSelector(root, target)
  }

  // ---------------------------------------------------------------------------
  // Coordinate-based API: tap (x, y) → node → selector → verify
  // ---------------------------------------------------------------------------

  /**
   * Result of resolving a tap at (x, y) against the view hierarchy.
   *
   * Provides the hit-tested target node, the best selector for it, and a round-trip
   * verification confirming the selector would tap the same node at playback.
   */
  data class TapResolution(
    /** The frontmost node at the tap coordinates (smallest-area hit test). */
    val targetNode: TrailblazeNode,
    /** The best selector that uniquely identifies the target. */
    val selector: TrailblazeNodeSelector,
    /**
     * The coordinates that [selector] would resolve to at playback (node center by default).
     * Compare with the original tap point to assess coordinate drift.
     */
    val resolvedCenter: Pair<Int, Int>?,
    /**
     * True if [resolvedCenter] hit-tests back to the same [targetNode].
     * When false, a child element would intercept the tap at playback — the selector
     * needs a relative offset or a more specific target.
     */
    val roundTripValid: Boolean,
  )

  /**
   * Resolves a tap at (x, y) to a selector with full round-trip verification.
   *
   * 1. **Hit test** — finds the frontmost node at (x, y)
   * 2. **Generate** — produces the best unique selector for that node
   * 3. **Verify** — resolves the selector back to coordinates and hit-tests those coordinates
   *    to confirm the same node would be tapped at playback
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param x The tap X coordinate in device pixels
   * @param y The tap Y coordinate in device pixels
   * @return [TapResolution] with the target, selector, and verification result, or null if
   *         no node exists at (x, y)
   */
  fun resolveFromTap(root: TrailblazeNode, x: Int, y: Int): TapResolution? {
    val target = root.hitTest(x, y) ?: return null
    val selector = findBestSelector(root, target)

    val resolvedCenter = TrailblazeNodeSelectorResolver.resolveToCenter(root, selector)
    val roundTripValid = if (resolvedCenter != null) {
      val hitBack = root.hitTest(resolvedCenter.first, resolvedCenter.second)
      hitBack?.nodeId == target.nodeId
    } else {
      false
    }

    return TapResolution(
      targetNode = target,
      selector = selector,
      resolvedCenter = resolvedCenter,
      roundTripValid = roundTripValid,
    )
  }

  // --- Strategy resolution ---

  /** Maps [DriverNodeDetail] to the appropriate platform-specific strategies. */
  private fun strategiesForDetail(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<Pair<String, () -> TrailblazeNodeSelector?>> = when (detail) {
    is DriverNodeDetail.AndroidAccessibility ->
      androidAccessibilityStrategies(root, target, detail, parentMap)
    is DriverNodeDetail.AndroidMaestro ->
      androidMaestroStrategies(root, target, detail, parentMap)
    is DriverNodeDetail.Web ->
      webStrategies(root, target, detail, parentMap)
    is DriverNodeDetail.Compose ->
      composeStrategies(root, target, detail, parentMap)
    is DriverNodeDetail.IosMaestro ->
      iosMaestroStrategies(root, target, detail, parentMap)
    is DriverNodeDetail.IosAxe ->
      iosAxeStrategies(root, target, detail, parentMap)
  }

  // --- Private helpers ---

  /**
   * Global index fallback: assigns an index among ALL nodes in the tree.
   * This always succeeds but produces the most brittle selector.
   */
  private fun computeIndexSelector(
    root: TrailblazeNode,
    target: TrailblazeNode,
  ): TrailblazeNodeSelector {
    val allNodes = root.aggregate()
      .sortedWith(
        compareBy(
          { it.bounds?.top ?: Int.MAX_VALUE },
          { it.bounds?.left ?: Int.MAX_VALUE },
        ),
      )
    val idx = allNodes.indexOfFirst { it.nodeId == target.nodeId }
    check(idx >= 0) { "Target node ${target.nodeId} not found in tree — cannot generate selector" }
    return TrailblazeNodeSelector(index = idx)
  }
}
