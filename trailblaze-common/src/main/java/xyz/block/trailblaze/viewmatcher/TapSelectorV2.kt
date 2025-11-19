package xyz.block.trailblaze.viewmatcher

import kotlinx.serialization.encodeToString
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.viewmatcher.ElementMatcherUsingMaestro.asTrailblazeElementSelector
import xyz.block.trailblaze.viewmatcher.models.OrderedSpatialHints
import xyz.block.trailblaze.viewmatcher.models.RelativePosition
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Generates optimal element selectors for UI automation.
 *
 * Tries progressively complex strategies until finding a unique selector for the target element:
 * 1. Target properties only (text, id, state)
 * 2. Target + unique parent
 * 3. Target + spatial hints (LLM-provided, geometrically validated)
 * 4. Target + unique child
 * 5. Target + unique descendants
 * 6. Target + multiple parent levels (closer ancestors for tighter constraints)
 * 7. Target + index (last resort)
 *
 * ## Key Rules
 * - Never combine `index` with `childOf` (too brittle)
 * - Compare by bounds not dimensions (rounding errors)
 * - Limit to 5 parent levels (performance + maintainability)
 * - Try without parent before with parent (simpler selectors)
 *
 * ## Coordinate System
 * x1,y1 = top-left; x2,y2 = bottom-right; Y↓ X→
 */
object TapSelectorV2 {

  fun findBestTrailblazeElementSelectorForTargetNode(
    root: ViewHierarchyTreeNode,
    target: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
    spatialHints: OrderedSpatialHints?,
  ): TrailblazeElementSelector {
    val context = SelectorSearchContext(
      root = root,
      target = target,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
      spatialHints = spatialHints,
    )

    return tryTargetPropertiesOnly(context)
      ?: tryWithUniqueParent(context)
      ?: tryWithSpatialHints(context)
      ?: tryWithUniqueChild(context)
      ?: tryWithUniqueDescendants(context)
      ?: tryWithContainsChildAtMultipleParentLevels(context)
      ?: tryWithContainsDescendantsAtMultipleParentLevels(context)
      ?: tryWithIndex(context)
  }

  /**
   * Finds a node using a selector and returns its center coordinates.
   * This is the public API for using selectors to locate elements.
   *
   * @return Pair of (centerX, centerY) if the selector finds exactly one match, null otherwise
   */
  fun findNodeCenterUsingSelector(
    root: ViewHierarchyTreeNode,
    selector: TrailblazeElementSelector,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): Pair<Int, Int>? {
    val matches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
      rootTreeNode = root,
      trailblazeElementSelector = selector,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
    )

    return when (matches) {
      is ElementMatches.SingleMatch -> {
        val bounds = matches.viewHierarchyTreeNode.bounds
        if (bounds != null) {
          val centerX = (bounds.x1 + bounds.x2) / 2
          val centerY = (bounds.y1 + bounds.y2) / 2
          Pair(centerX, centerY)
        } else {
          null
        }
      }

      else -> null
    }
  }

  private data class SelectorSearchContext(
    val root: ViewHierarchyTreeNode,
    val target: ViewHierarchyTreeNode,
    val trailblazeDevicePlatform: TrailblazeDevicePlatform,
    val widthPixels: Int,
    val heightPixels: Int,
    val spatialHints: OrderedSpatialHints? = null,
  ) {
    val uniqueParent: TrailblazeElementSelector? by lazy {
      findFirstUniqueParentSelector(
        target = target,
        root = root,
        trailblazeDevicePlatform = trailblazeDevicePlatform,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
      )
    }

    val uniqueDescendants: List<TrailblazeElementSelector>? by lazy {
      findUniqueDescendantSelectors(
        targetViewHierarchyTreeNode = target,
        trailblazeDevicePlatform = trailblazeDevicePlatform,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
      )
    }

    val bestChildSelectorForContainsChild: TrailblazeElementSelector? by lazy {
      findMostSpecificChildSelector(
        target = target,
        root = root,
        trailblazeDevicePlatform = trailblazeDevicePlatform,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
      )
    }

    fun getMatches(
      selector: TrailblazeElementSelector,
      rootNode: ViewHierarchyTreeNode = root,
    ): ElementMatches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
      rootTreeNode = rootNode,
      trailblazeElementSelector = selector,
      trailblazeDevicePlatform = trailblazeDevicePlatform,
      widthPixels = widthPixels,
      heightPixels = heightPixels,
    )

    fun isCorrectTarget(match: ElementMatches): Boolean {
      if (match !is ElementMatches.SingleMatch) return false
      val targetNormalized = target.clearAllNodeIdsForThisAndAllChildren()
      val matchNormalized = match.viewHierarchyTreeNode.clearAllNodeIdsForThisAndAllChildren()
      return matchNormalized.compareEqualityBasedOnBoundsNotDimensions(targetNormalized)
    }
  }

  /**
   * Finds best child for `containsChild` constraint.
   *
   * Tries 4 strategies:
   * 1. Globally unique child
   * 2. Child that becomes unique with one of target's ancestors
   * 3. Subtree-unique child (shortest YAML)
   * 4. Any child (shortest YAML)
   */
  private fun findMostSpecificChildSelector(
    target: ViewHierarchyTreeNode,
    root: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): TrailblazeElementSelector? {
    data class ChildCandidate(
      val selector: TrailblazeElementSelector,
      val isUniqueInSubtree: Boolean,
      val isUniqueGlobal: Boolean,
      val yamlLength: Int,
    )

    fun buildMostSpecificChildSelector(child: ViewHierarchyTreeNode): TrailblazeElementSelector = TrailblazeElementSelector(
      textRegex = child.resolveMaestroText()?.takeIf { it.isNotBlank() },
      idRegex = child.resourceId?.takeIf { it.isNotBlank() },
      enabled = child.enabled.takeIf { !it },
      selected = child.selected.takeIf { it },
      checked = child.checked.takeIf { it },
      focused = child.focused.takeIf { it },
    )

    fun tryParentLevelsForUniqueContainsChild(childSelector: TrailblazeElementSelector): TrailblazeElementSelector? {
      val pathToTarget = TapSelectorV2.findPathToTarget(target, root).reversed()
      for (parent in pathToTarget) {
        val parentSelector = parent.asTrailblazeElementSelector()
        if (parentSelector.textRegex == null && parentSelector.idRegex == null) continue

        val parentMatches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
          rootTreeNode = root,
          trailblazeElementSelector = parentSelector,
          trailblazeDevicePlatform = trailblazeDevicePlatform,
          widthPixels = widthPixels,
          heightPixels = heightPixels,
        )
        if (parentMatches !is ElementMatches.SingleMatch) continue

        val targetLeafSelector = target.asTrailblazeElementSelector().copy(
          childOf = parentSelector,
          containsChild = childSelector,
        )
        val matches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
          rootTreeNode = root,
          trailblazeElementSelector = targetLeafSelector,
          trailblazeDevicePlatform = trailblazeDevicePlatform,
          widthPixels = widthPixels,
          heightPixels = heightPixels,
        )
        if (matches is ElementMatches.SingleMatch) {
          return childSelector
        }
      }
      return null
    }

    val candidates: List<ChildCandidate> = target.children
      .mapNotNull { child ->
        val selector = buildMostSpecificChildSelector(child)
        if (
          selector.textRegex == null &&
          selector.idRegex == null &&
          selector.enabled == null &&
          selector.selected == null &&
          selector.checked == null &&
          selector.focused == null
        ) {
          null
        } else {
          val subtreeMatches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
            rootTreeNode = target,
            trailblazeElementSelector = selector,
            trailblazeDevicePlatform = trailblazeDevicePlatform,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
          )
          val isUniqueInSubtree = subtreeMatches is ElementMatches.SingleMatch

          val globalMatches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
            rootTreeNode = root,
            trailblazeElementSelector = selector,
            trailblazeDevicePlatform = trailblazeDevicePlatform,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
          )
          val isUniqueGlobal = globalMatches is ElementMatches.SingleMatch

          ChildCandidate(
            selector = selector,
            isUniqueInSubtree = isUniqueInSubtree,
            isUniqueGlobal = isUniqueGlobal,
            yamlLength = TrailblazeYaml.defaultYamlInstance.encodeToString(
              TrailblazeElementSelector.serializer(),
              selector,
            ).length,
          )
        }
      }

    return candidates.firstOrNull { it.isUniqueGlobal }?.selector
      ?: run {
        for (candidate in candidates) {
          val uniqueSelector = tryParentLevelsForUniqueContainsChild(candidate.selector)
          if (uniqueSelector != null) {
            return@run uniqueSelector
          }
        }
        null
      }
      ?: candidates
        .filter { it.isUniqueInSubtree }
        .minByOrNull { it.yamlLength }
        ?.selector
      ?: candidates
        .minByOrNull { it.yamlLength }
        ?.selector
  }

  /**
   * STRATEGY 1: Try matching using only the target element's properties (text, id, state).
   *
   * @return Selector if target is uniquely identifiable by properties alone, null otherwise
   */
  private fun tryTargetPropertiesOnly(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val matches = context.getMatches(targetLeafSelector)

    return if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
      matches.trailblazeElementSelector
    } else {
      null
    }
  }

  private fun interface SpatialSelectorBuilder {
    fun build(
      spatialNeighbor: TrailblazeElementSelector,
      parentConstraint: TrailblazeElementSelector?,
    ): TrailblazeElementSelector
  }

  /**
   * Tries spatial hints from LLM, validating geometric relationships before use.
   * Tried early because spatial relationships are semantic and maintainable.
   */
  private fun tryWithSpatialHints(context: SelectorSearchContext): TrailblazeElementSelector? {
    val hints = context.spatialHints ?: return null
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueParent = context.uniqueParent

    fun getSelectorForNode(nodeId: Long): TrailblazeElementSelector? {
      val node = context.root.aggregate().find { it.nodeId == nodeId } ?: return null
      return node.asTrailblazeElementSelector()
    }

    fun validateSpatialRelationship(referenceNodeId: Long, relationship: RelativePosition): Boolean {
      val referenceNode = context.root.aggregate().find { it.nodeId == referenceNodeId } ?: return false
      val targetBounds = context.target.bounds ?: return false
      val referenceBounds = referenceNode.bounds ?: return false

      return when (relationship) {
        RelativePosition.ABOVE -> targetBounds.y2 <= referenceBounds.y1
        RelativePosition.BELOW -> targetBounds.y1 >= referenceBounds.y2
        RelativePosition.LEFT_OF -> targetBounds.x2 <= referenceBounds.x1
        RelativePosition.RIGHT_OF -> targetBounds.x1 >= referenceBounds.x2
      }
    }

    fun tryWithSpatial(
      relationship: RelativePosition,
      nodeId: Long,
      selectorBuilder: SpatialSelectorBuilder,
    ): TrailblazeElementSelector? {
      if (!validateSpatialRelationship(nodeId, relationship)) {
        return null
      }

      val spatialNeighborSelector = getSelectorForNode(nodeId) ?: return null

      // Try without parent first
      val selectorWithoutParent = selectorBuilder.build(spatialNeighborSelector, null)
      val matchesWithoutParent = context.getMatches(selectorWithoutParent)
      if (matchesWithoutParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithoutParent)) {
        return matchesWithoutParent.trailblazeElementSelector
      }

      // Try with parent
      val selectorWithParent = selectorBuilder.build(spatialNeighborSelector, uniqueParent)
      val matchesWithParent = context.getMatches(selectorWithParent)
      if (matchesWithParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithParent)) {
        return matchesWithParent.trailblazeElementSelector
      }

      return null
    }

    hints.hints.forEach { hint ->
      when (hint.relationship) {
        RelativePosition.ABOVE -> tryWithSpatial(
          RelativePosition.ABOVE,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector.copy(childOf = parentConstraint, above = spatialNeighbor)
          },
        )?.let { return it }

        RelativePosition.BELOW -> tryWithSpatial(
          RelativePosition.BELOW,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector.copy(childOf = parentConstraint, below = spatialNeighbor)
          },
        )?.let { return it }

        RelativePosition.LEFT_OF -> tryWithSpatial(
          RelativePosition.LEFT_OF,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector.copy(childOf = parentConstraint, leftOf = spatialNeighbor)
          },
        )?.let { return it }

        RelativePosition.RIGHT_OF -> tryWithSpatial(
          RelativePosition.RIGHT_OF,
          hint.referenceNodeId,
          SpatialSelectorBuilder { spatialNeighbor, parentConstraint ->
            targetLeafSelector.copy(childOf = parentConstraint, rightOf = spatialNeighbor)
          },
        )?.let { return it }
      }
    }

    return null
  }

  private fun tryWithUniqueParent(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueParent = context.uniqueParent

    val selectorWithParent = targetLeafSelector.copy(childOf = uniqueParent)
    val matches = context.getMatches(selectorWithParent)

    return if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
      matches.trailblazeElementSelector
    } else {
      null
    }
  }

  private fun tryChildSelector(
    context: SelectorSearchContext,
    targetLeafSelector: TrailblazeElementSelector,
    childSelector: TrailblazeElementSelector,
    parentSelector: TrailblazeElementSelector?,
  ): TrailblazeElementSelector? {
    val selectorWithoutParent = targetLeafSelector.copy(containsChild = childSelector)
    val matchesWithoutParent = context.getMatches(selectorWithoutParent)

    if (matchesWithoutParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithoutParent)) {
      return matchesWithoutParent.trailblazeElementSelector
    }

    if (parentSelector != null) {
      val selectorWithParent = targetLeafSelector.copy(
        childOf = parentSelector,
        containsChild = childSelector,
      )
      val matchesWithParent = context.getMatches(selectorWithParent)

      if (matchesWithParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithParent)) {
        return matchesWithParent.trailblazeElementSelector
      }
    }

    return null
  }

  private fun tryWithUniqueChild(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueParent = context.uniqueParent

    val globallyUniqueChild = findFirstUniqueDirectChildSelector(
      target = context.target,
      root = context.root,
      trailblazeDevicePlatform = context.trailblazeDevicePlatform,
      widthPixels = context.widthPixels,
      heightPixels = context.heightPixels,
    )

    if (globallyUniqueChild != null) {
      tryChildSelector(context, targetLeafSelector, globallyUniqueChild, uniqueParent)?.let { return it }
    }

    val bestChild = context.bestChildSelectorForContainsChild
    if (bestChild != null && bestChild != globallyUniqueChild) {
      tryChildSelector(context, targetLeafSelector, bestChild, uniqueParent)?.let { return it }
    }

    return null
  }

  private fun tryWithUniqueDescendants(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueParent = context.uniqueParent
    val uniqueDescendants = context.uniqueDescendants ?: return null

    val selectorWithoutParent = targetLeafSelector.copy(
      containsDescendants = uniqueDescendants,
    )
    val matchesWithoutParent = context.getMatches(selectorWithoutParent)

    if (matchesWithoutParent is ElementMatches.SingleMatch && context.isCorrectTarget(matchesWithoutParent)) {
      return matchesWithoutParent.trailblazeElementSelector
    }

    val selectorWithDescendants = targetLeafSelector.copy(
      childOf = uniqueParent,
      containsDescendants = uniqueDescendants,
    )
    val matches = context.getMatches(selectorWithDescendants)

    return if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
      matches.trailblazeElementSelector
    } else {
      null
    }
  }

  /** Tries containsChild with up to 5 parent levels for tighter constraints. */
  private fun tryWithContainsChildAtMultipleParentLevels(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val bestChild = context.bestChildSelectorForContainsChild ?: return null

    return tryWithParentLevels(context) { parentSelector ->
      targetLeafSelector.copy(
        childOf = parentSelector,
        containsChild = bestChild,
      )
    }
  }

  /** Tries containsDescendants with up to 5 parent levels for tighter constraints. */
  private fun tryWithContainsDescendantsAtMultipleParentLevels(context: SelectorSearchContext): TrailblazeElementSelector? {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val uniqueDescendants = context.uniqueDescendants ?: return null

    return tryWithParentLevels(context) { parentSelector ->
      targetLeafSelector.copy(
        childOf = parentSelector,
        containsDescendants = uniqueDescendants,
      )
    }
  }

  /**
   * Helper function to try a selector with up to 5 parent levels for tighter constraints.
   *
   * Iterates through ancestors of the target and tests if the selector built with each
   * parent uniquely identifies the target. Returns the first matching selector found.
   *
   * @param context The search context containing root, target, and matching functions
   * @param selectorBuilder Function that builds a complete selector given a parent selector
   * @return A unique selector if found, null otherwise
   */
  private fun tryWithParentLevels(
    context: SelectorSearchContext,
    selectorBuilder: (TrailblazeElementSelector) -> TrailblazeElementSelector,
  ): TrailblazeElementSelector? {
    val pathToTarget = findPathToTarget(context.target, context.root)
    val ancestorsToTry = pathToTarget.reversed().take(5)

    for (ancestor in ancestorsToTry) {
      val parentSelector = ancestor.asTrailblazeElementSelector()

      if (parentSelector.textRegex == null && parentSelector.idRegex == null && parentSelector.containsChild == null) {
        continue
      }

      val parentMatches = context.getMatches(parentSelector)
      if (parentMatches !is ElementMatches.SingleMatch) {
        continue
      }

      val selector = selectorBuilder(parentSelector)
      val matches = context.getMatches(selector)

      if (matches is ElementMatches.SingleMatch && context.isCorrectTarget(matches)) {
        return matches.trailblazeElementSelector
      }
    }

    return null
  }

  /**
   * Last resort: use position-based index.
   *
   * IMPORTANT: This NEVER uses childOf with index - we only use index on global tree searches.
   *
   * We try:
   * 1. target + containsChild + index (no parent)
   * 2. target + containsDescendants + index (no parent)
   * 3. target properties + index (no parent)
   *
   * We pick the selector with the LOWEST index (most maintainable).
   * This should always succeed since an empty selector matches all nodes.
   */
  private fun tryWithIndex(context: SelectorSearchContext): TrailblazeElementSelector {
    val targetLeafSelector = context.target.asTrailblazeElementSelector()
    val bestChild = context.bestChildSelectorForContainsChild

    fun computeIndexForSelector(selector: TrailblazeElementSelector): Pair<TrailblazeElementSelector, Int>? {
      val matches = context.getMatches(selector)

      if (matches !is ElementMatches.MultipleMatches) {
        return null // Can't use index without multiple candidates
      }

      // Sort using Maestro's ordering (Y position, then X position)
      val sortedMatches = matches.viewHierarchyTreeNodes
        .map { it.clearAllNodeIdsForThisAndAllChildren() }
        .sortedWith(
          compareBy(
            { it.bounds?.y1 ?: Int.MAX_VALUE },
            { it.bounds?.x1 ?: Int.MAX_VALUE },
          ),
        )

      val targetNormalized = context.target.clearAllNodeIdsForThisAndAllChildren()
      val targetIndex = sortedMatches.indexOfFirst {
        it.compareEqualityBasedOnBoundsNotDimensions(targetNormalized)
      }

      if (targetIndex != -1) {
        return Pair(matches.trailblazeElementSelector.copy(index = targetIndex.toString()), targetIndex)
      }

      // Target not found in matches
      throwSelectorNotFoundError(context, matches, sortedMatches)
    }

    val candidateSelectors = mutableListOf<Pair<TrailblazeElementSelector, Int>>()

    // Option 1: containsChild + index (no parent)
    if (bestChild != null) {
      val selectorWithChild = targetLeafSelector.copy(
        containsChild = bestChild,
      )
      computeIndexForSelector(selectorWithChild)?.let {
        candidateSelectors.add(it)
      }
    }

    // Option 2: containsDescendants + index (no parent)
    if (context.uniqueDescendants != null) {
      val selectorWithDescendants = targetLeafSelector.copy(
        containsDescendants = context.uniqueDescendants,
      )
      computeIndexForSelector(selectorWithDescendants)?.let {
        candidateSelectors.add(it)
      }
    }

    // Option 3: just target properties + index (no parent)
    // Always attempt this as a last resort - even an empty selector will match all nodes
    computeIndexForSelector(targetLeafSelector)?.let {
      candidateSelectors.add(it)
    }

    // Return the selector with the LOWEST index (most maintainable)
    return candidateSelectors.minByOrNull { it.second }?.first
      ?: error("Index fallback failed for target node ${context.target.nodeId}. This should never happen.")
  }

  /**
   * Throws a detailed error when we have multiple matches but can't find the target among them.
   * This should rarely happen and indicates either a bug in the matching logic or an inconsistent
   * view hierarchy.
   */
  private fun throwSelectorNotFoundError(
    context: SelectorSearchContext,
    matches: ElementMatches.MultipleMatches,
    sortedMatches: List<ViewHierarchyTreeNode>,
  ): Nothing {
    val errorMessage = buildString {
      appendLine("No exact match for selector with center ${context.target.centerPoint}, found ${sortedMatches.size} matches.")
      appendLine()
      appendLine("Selector Description:")
      appendLine("```\n${matches.trailblazeElementSelector.description()}\n```")
      appendLine()
      appendLine("Selector Yaml:")
      appendLine("```\n${TrailblazeYaml.defaultYamlInstance.encodeToString(matches.trailblazeElementSelector)}\n```")
      appendLine()
      sortedMatches.forEachIndexed { idx, match ->
        appendLine("Match $idx:")
        appendLine("```\n${TrailblazeJsonInstance.encodeToString(match)}\n```")
        appendLine()
        appendLine("Without dimensions:")
        appendLine("```\n${TrailblazeJsonInstance.encodeToString(match.deepCopyWithoutDimensions())}\n```")
        appendLine()
      }
    }
    error(errorMessage)
  }

  /**
   * Builds a leaf selector from a node's available properties.
   *
   * A "leaf" selector only includes the node's direct properties (text, id, state)
   * and doesn't include any relationship properties (childOf, containsChild, etc.).
   *
   * @param node The node to build a selector for
   * @return A selector with the node's available properties
   */
  private fun buildLeafSelectorWithAvailableProperties(
    node: ViewHierarchyTreeNode,
  ): TrailblazeElementSelector {
    val computedSelector = TrailblazeElementSelector(
      textRegex = node.resolveMaestroText()?.takeIf { it.isNotBlank() },
      idRegex = node.resourceId?.takeIf { it.isNotBlank() },
      enabled = node.enabled.takeIf { !it },
      selected = node.selected.takeIf { it },
      checked = node.checked.takeIf { it },
      focused = node.focused.takeIf { it },
    )
    return computedSelector
  }

  /**
   * Finds descendant selectors that are unique within the target node's subtree.
   *
   * This searches all descendants of the target node and identifies selectors that match
   * exactly one element within the target's subtree. These unique descendants can be used
   * in `containsDescendants` to help disambiguate the target from similar elements.
   *
   * @param targetViewHierarchyTreeNode The target node whose descendants to search
   * @param trailblazeDevicePlatform Platform for matching behavior
   * @param widthPixels Device width for bounds calculations
   * @param heightPixels Device height for bounds calculations
   * @return List of unique descendant selectors, or null if none found
   */
  private fun findUniqueDescendantSelectors(
    targetViewHierarchyTreeNode: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): List<TrailblazeElementSelector>? = targetViewHierarchyTreeNode.children
    .flatMap { it.aggregate() }.mapNotNull { child ->
      val matches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
        rootTreeNode = targetViewHierarchyTreeNode,
        trailblazeElementSelector = buildLeafSelectorWithAvailableProperties(node = child),
        trailblazeDevicePlatform = trailblazeDevicePlatform,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
      )
      val isSelectorUnique = matches is ElementMatches.SingleMatch

      if (isSelectorUnique) {
        matches.trailblazeElementSelector
      } else {
        null
      }
    }.takeIf { it.isNotEmpty() }

  /**
   * Finds the first direct child of the target that has a globally unique selector.
   *
   * Searches through the target's immediate children to find one whose properties
   * (text, id, state) uniquely identify it within the entire view hierarchy. This child
   * can then be used in a `containsChild` constraint.
   *
   * Note: Due to Maestro's `containsChild` limitation (finding only the FIRST matching child),
   * this should be verified before use to avoid false positives.
   *
   * @param target The target node whose children to search
   * @param root The root of the entire view hierarchy for uniqueness checking
   * @param trailblazeDevicePlatform Platform for matching behavior
   * @param widthPixels Device width for bounds calculations
   * @param heightPixels Device height for bounds calculations
   * @return A selector for the first unique child, or null if none found
   */
  private fun findFirstUniqueDirectChildSelector(
    target: ViewHierarchyTreeNode,
    root: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): TrailblazeElementSelector? {
    target.children.forEach { directChild ->
      val selector = directChild.asTrailblazeElementSelector()
      val elementMatchesResult: ElementMatches =
        ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
          rootTreeNode = root,
          trailblazeElementSelector = selector,
          trailblazeDevicePlatform = trailblazeDevicePlatform,
          widthPixels = widthPixels,
          heightPixels = heightPixels,
        )
      // Is Selector Unique
      if (elementMatchesResult is ElementMatches.SingleMatch) {
        return selector
      }
    }

    return null
  }

  /**
   * Finds the first ancestor of the target that has a globally unique selector.
   *
   * Walks up the ancestor path from the target's parent to the root, looking for the first
   * ancestor whose properties uniquely identify it in the view hierarchy. This ancestor can
   * then be used as a `childOf` constraint to narrow the search space.
   *
   * We try two approaches for each ancestor:
   * 1. Direct properties (text, id)
   * 2. containsChild with a unique child (for ancestors without direct properties)
   *
   * The search starts from the immediate parent and works backward toward the root, preferring
   * the closest unique ancestor (which provides the tightest constraint).
   *
   * @param target The target node to find a unique ancestor for
   * @param root The root of the entire view hierarchy
   * @param trailblazeDevicePlatform Platform for matching behavior
   * @param widthPixels Device width for bounds calculations
   * @param heightPixels Device height for bounds calculations
   * @return A selector for the first unique ancestor, or null if none found
   */
  private fun findFirstUniqueParentSelector(
    target: ViewHierarchyTreeNode,
    root: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): TrailblazeElementSelector? {
    val pathToTarget = findPathToTarget(target, root).reversed()

    pathToTarget.forEach { candidate ->
      // Try 1: Direct properties (text, id)
      val directPropsSelector = candidate.asTrailblazeElementSelector()
      if (directPropsSelector.textRegex != null || directPropsSelector.idRegex != null) {
        val elementMatchesResult: ElementMatches =
          ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
            rootTreeNode = root,
            trailblazeElementSelector = directPropsSelector,
            trailblazeDevicePlatform = trailblazeDevicePlatform,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
          )
        if (elementMatchesResult is ElementMatches.SingleMatch) {
          return directPropsSelector
        }
      }

      // Try 2: containsChild with a unique child
      val uniqueChild = findFirstUniqueDirectChildSelector(
        target = candidate,
        root = root,
        trailblazeDevicePlatform = trailblazeDevicePlatform,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
      )
      if (uniqueChild != null) {
        val selectorViaChild = TrailblazeElementSelector(
          containsChild = uniqueChild,
        )
        val elementMatchesResult: ElementMatches =
          ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
            rootTreeNode = root,
            trailblazeElementSelector = selectorViaChild,
            trailblazeDevicePlatform = trailblazeDevicePlatform,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
          )
        if (elementMatchesResult is ElementMatches.SingleMatch) {
          return selectorViaChild
        }
      }
    }

    return null
  }

  /**
   * Finds the path from root to target node (excluding the target itself).
   *
   * Performs a depth-first search to find the target node and returns all ancestors
   * from root to the target's parent. The returned list is in root-first order
   * (e.g., [root, grandparent, parent]).
   *
   * @param target The target node to find a path to
   * @param root The root of the view hierarchy to search from
   * @return List of nodes from root to target's parent (empty if target is root)
   */
  private fun findPathToTarget(
    target: ViewHierarchyTreeNode,
    root: ViewHierarchyTreeNode,
  ): List<ViewHierarchyTreeNode> {
    fun findPath(
      current: ViewHierarchyTreeNode,
      targetId: Long,
      path: MutableList<ViewHierarchyTreeNode>,
    ): Boolean {
      // If we found the target, return true (don't include target in the path)
      if (current.nodeId == targetId) {
        return true
      }

      // Add current node to path and search children
      path.add(current)

      for (child in current.children) {
        if (findPath(child, targetId, path)) {
          return true
        }
      }

      // If target not found in this subtree, remove current node from path
      path.removeAt(path.size - 1)
      return false
    }

    val parentPath = mutableListOf<ViewHierarchyTreeNode>()
    findPath(
      current = root,
      targetId = target.nodeId,
      path = parentPath,
    )

    return parentPath
  }
}
