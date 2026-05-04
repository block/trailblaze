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
 * @see TrailblazeNodeSelectorResolver for matching selectors against trees
 * @see TrailblazeNodeSelector for the selector model
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
    for ((name, strategy) in namedStrategies) {
      if (results.size >= maxResults) break
      strategy()?.let { selector ->
        if (isUniqueMatch(root, target, selector)) {
          results.add(NamedSelector(selector, name, isBest = results.isEmpty()))
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
      strategy()?.let { selector ->
        if (isUniqueMatch(root, target, selector)) {
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
      strategy()?.let { selector ->
        if (isUniqueMatch(root, target, selector)) return selector
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
