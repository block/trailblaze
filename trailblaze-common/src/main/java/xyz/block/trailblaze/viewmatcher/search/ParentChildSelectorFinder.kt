package xyz.block.trailblaze.viewmatcher.search

import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.asTrailblazeElementSelector
import xyz.block.trailblaze.viewhierarchy.ViewHierarchyFilter.Companion.hasMaestroProperties
import xyz.block.trailblaze.viewmatcher.matching.ElementMatcherUsingMaestro
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.yaml.TrailblazeYaml

/**
 * Utilities for finding unique parent and child selectors.
 */
object ParentChildSelectorFinder {

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
   * @param excludeNode Optional node to exclude from the search (e.g., to prevent circular references)
   * @return A selector for the first unique child, or null if none found
   */
  fun findFirstUniqueDirectChildSelector(
    target: ViewHierarchyTreeNode,
    root: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
    excludeNode: ViewHierarchyTreeNode? = null,
  ): TrailblazeElementSelector? = TrailblazeTracer.trace(
    "findFirstUniqueDirectChildSelector",
    this::class.simpleName!!,
    mapOf("target" to target.nodeId.toString()),
  ) {
    target.children.filter { it.hasMaestroProperties() }.forEach { directChild ->
      // Skip if this is the node we want to exclude (prevents circular references)
      if (excludeNode != null && directChild.nodeId == excludeNode.nodeId) {
        return@forEach
      }

      directChild.asTrailblazeElementSelector()?.let { selector ->
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
  fun findFirstUniqueParentSelector(
    target: ViewHierarchyTreeNode,
    root: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): TrailblazeElementSelector? {
    val pathToTarget = HierarchyNavigator.findPathToTarget(target, root).reversed()

    pathToTarget.forEach { candidate ->
      // Try 1: Direct properties (text, id)
      val directPropsSelector = candidate.asTrailblazeElementSelector()
      if (directPropsSelector != null) {
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
      }

      // Try 2: containsChild with a unique child
      // Exclude the target node itself to prevent circular references (e.g., target as containsChild of its own parent)
      val uniqueChild = findFirstUniqueDirectChildSelector(
        target = candidate,
        root = root,
        trailblazeDevicePlatform = trailblazeDevicePlatform,
        widthPixels = widthPixels,
        heightPixels = heightPixels,
        excludeNode = target,
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
  fun findUniqueDescendantSelectors(
    targetViewHierarchyTreeNode: ViewHierarchyTreeNode,
    trailblazeDevicePlatform: TrailblazeDevicePlatform,
    widthPixels: Int,
    heightPixels: Int,
  ): List<TrailblazeElementSelector>? = targetViewHierarchyTreeNode.children
    .flatMap { it.aggregate() }.mapNotNull { child ->
      val matches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
        rootTreeNode = targetViewHierarchyTreeNode,
        trailblazeElementSelector = SelectorBuilder.buildLeafSelectorWithAvailableProperties(node = child),
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
   * Finds best child for `containsChild` constraint.
   *
   * Tries 4 strategies:
   * 1. Globally unique child
   * 2. Child that becomes unique with one of target's ancestors
   * 3. Subtree-unique child (shortest YAML)
   * 4. Any child (shortest YAML)
   */
  fun findMostSpecificChildSelector(
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

    fun tryParentLevelsForUniqueContainsChild(childSelector: TrailblazeElementSelector): TrailblazeElementSelector? {
      val pathToTarget = HierarchyNavigator.findPathToTarget(target, root).reversed()
      for (parent in pathToTarget) {
        val parentSelector = parent.asTrailblazeElementSelector()
        if (parentSelector != null) {
          if (parentSelector.textRegex == null && parentSelector.idRegex == null) continue

          val parentMatches = ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
            rootTreeNode = root,
            trailblazeElementSelector = parentSelector,
            trailblazeDevicePlatform = trailblazeDevicePlatform,
            widthPixels = widthPixels,
            heightPixels = heightPixels,
          )
          if (parentMatches !is ElementMatches.SingleMatch) continue

          val targetLeafSelector = target.asTrailblazeElementSelector()?.copy(
            childOf = parentSelector,
            containsChild = childSelector,
          )
          if (targetLeafSelector != null) {
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
        }
      }
      return null
    }

    val candidates: List<ChildCandidate> = target.children
      .mapNotNull { child ->
        val selector: TrailblazeElementSelector? = child.asTrailblazeElementSelector()
        if (selector == null) {
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
}
