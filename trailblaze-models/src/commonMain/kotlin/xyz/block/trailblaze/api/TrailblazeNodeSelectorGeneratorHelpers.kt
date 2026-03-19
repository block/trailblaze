package xyz.block.trailblaze.api

/** Wraps a [DriverNodeMatch] in a [TrailblazeNodeSelector]. */
internal fun selectorWith(match: DriverNodeMatch): TrailblazeNodeSelector =
  TrailblazeNodeSelector.withMatch(match)

/**
 * Regex metacharacters that require escaping for selector patterns.
 * When text contains none of these, it can be used as-is (it's a valid regex
 * that matches literally). When it contains any, wrap in `\Q...\E`.
 */
internal val REGEX_METACHARACTERS = setOf(
  '\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}',
)

/**
 * Escapes text for use in selector regex fields. Returns the text as-is when it
 * contains no regex metacharacters (producing cleaner YAML), or wraps in `\Q...\E`
 * when escaping is needed.
 */
internal fun escapeForSelector(text: String): String =
  if (text.any { it in REGEX_METACHARACTERS }) Regex.escape(text) else text

/** Checks that a selector resolves to exactly the target node. */
internal fun isUniqueMatch(
  root: TrailblazeNode,
  target: TrailblazeNode,
  selector: TrailblazeNodeSelector,
): Boolean {
  val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
  return result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch &&
    result.node.nodeId == target.nodeId
}

/**
 * Builds the most precise [DriverNodeMatch] for the target, using the specific text field
 * (contentDescription, hintText, text) rather than the lossy [resolveText] fallback.
 *
 * This feeds into hierarchy strategies (childOf, containsChild, collection, spatial) and
 * the index fallback, so precision here improves all composite strategies.
 */
internal fun buildTargetMatch(detail: DriverNodeDetail): DriverNodeMatch? = when (detail) {
  is DriverNodeDetail.AndroidAccessibility -> {
    // Use specific text fields for precision: contentDescription for icon buttons,
    // hintText for empty inputs, text for text-bearing elements.
    // Skip text for editable fields — user input is not stable identity.
    val text = detail.text?.takeIf { it.isNotBlank() && !detail.isEditable }
    val desc = detail.contentDescription?.takeIf { it.isNotBlank() && text == null }
    val hint = detail.hintText?.takeIf { it.isNotBlank() && text == null && desc == null }
    val className = detail.className
    val rid = detail.resourceId
    if (text != null || desc != null || hint != null || className != null || rid != null) {
      DriverNodeMatch.AndroidAccessibility(
        textRegex = text?.let { escapeForSelector(it) },
        contentDescriptionRegex = desc?.let { escapeForSelector(it) },
        hintTextRegex = hint?.let { escapeForSelector(it) },
        classNameRegex = className?.let { escapeForSelector(it) },
        resourceIdRegex = rid?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.AndroidMaestro -> {
    val text = detail.resolveText()?.takeIf { it.isNotBlank() }
    val rid = detail.resourceId
    if (text != null || rid != null) {
      DriverNodeMatch.AndroidMaestro(
        textRegex = text?.let { escapeForSelector(it) },
        resourceIdRegex = rid?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.Web -> {
    detail.ariaDescriptor?.let { DriverNodeMatch.Web(ariaDescriptorRegex = escapeForSelector(it)) }
  }
  is DriverNodeDetail.Compose -> {
    val tag = detail.testTag
    // Skip editableText — user-entered content is not stable identity.
    val text = if (detail.editableText == null) detail.text?.takeIf { it.isNotBlank() } else null
    val desc = detail.contentDescription?.takeIf { it.isNotBlank() && text == null }
    val role = detail.role
    if (tag != null || text != null || desc != null || role != null) {
      DriverNodeMatch.Compose(
        testTag = tag,
        role = role,
        textRegex = text?.let { escapeForSelector(it) },
        contentDescriptionRegex = desc?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.IosMaestro -> {
    val text = detail.resolveText()?.takeIf { it.isNotBlank() }
    val rid = detail.resourceId
    if (text != null || rid != null) {
      DriverNodeMatch.IosMaestro(
        textRegex = text?.let { escapeForSelector(it) },
        resourceIdRegex = rid?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
}

/**
 * Builds a structural-only [DriverNodeMatch] for a node, using only type, IDs, and state
 * flags — never text, contentDescription, hintText, or other content properties.
 */
internal fun buildStructuralMatch(detail: DriverNodeDetail): DriverNodeMatch? = when (detail) {
  is DriverNodeDetail.AndroidAccessibility -> {
    val className = detail.className
    val rid = detail.resourceId
    val uid = detail.uniqueId
    if (className != null || rid != null || uid != null) {
      DriverNodeMatch.AndroidAccessibility(
        classNameRegex = className?.let { escapeForSelector(it) },
        resourceIdRegex = rid?.let { escapeForSelector(it) },
        uniqueId = uid,
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.AndroidMaestro -> {
    val rid = detail.resourceId
    val className = detail.className
    if (rid != null || className != null) {
      DriverNodeMatch.AndroidMaestro(
        resourceIdRegex = rid?.let { escapeForSelector(it) },
        classNameRegex = className?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.Web -> {
    val css = detail.cssSelector
    val testId = detail.dataTestId
    val role = detail.ariaRole
    if (css != null || testId != null || role != null) {
      DriverNodeMatch.Web(
        cssSelector = css,
        dataTestId = testId,
        ariaRole = role,
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.Compose -> {
    val tag = detail.testTag
    val role = detail.role
    if (tag != null || role != null) {
      DriverNodeMatch.Compose(
        testTag = tag,
        role = role,
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.IosMaestro -> {
    val rid = detail.resourceId
    val className = detail.className
    if (rid != null || className != null) {
      DriverNodeMatch.IosMaestro(
        resourceIdRegex = rid?.let { escapeForSelector(it) },
        classNameRegex = className?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
}

/** Builds a spatial selector given target match, bounds, and an anchor. */
internal fun buildSpatialSelector(
  targetMatch: DriverNodeMatch?,
  targetBounds: TrailblazeNode.Bounds,
  anchorBounds: TrailblazeNode.Bounds,
  anchorSelector: TrailblazeNodeSelector,
): TrailblazeNodeSelector? = when {
  targetBounds.top >= anchorBounds.bottom ->
    TrailblazeNodeSelector.withMatch(targetMatch, below = anchorSelector)
  targetBounds.bottom <= anchorBounds.top ->
    TrailblazeNodeSelector.withMatch(targetMatch, above = anchorSelector)
  targetBounds.left >= anchorBounds.right ->
    TrailblazeNodeSelector.withMatch(targetMatch, rightOf = anchorSelector)
  targetBounds.right <= anchorBounds.left ->
    TrailblazeNodeSelector.withMatch(targetMatch, leftOf = anchorSelector)
  else -> null
}

/**
 * Walks up the tree to find the nearest ancestor that can be uniquely identified.
 * Returns a selector for that parent, or null if no unique parent is found.
 */
internal fun findUniqueParentSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
): TrailblazeNodeSelector? {
  // Walk up from target, trying to find a uniquely identifiable parent
  var current = parentMap[target.nodeId]
  var depth = 0
  while (current != null && depth < 5) {
    val parentMatch = buildTargetMatch(current.driverDetail)
    if (parentMatch != null) {
      val parentSelector = selectorWith(parentMatch)
      val result = TrailblazeNodeSelectorResolver.resolve(root, parentSelector)
      if (result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch &&
        result.node.nodeId == current.nodeId
      ) {
        return parentSelector
      }
    }
    current = parentMap[current.nodeId]
    depth++
  }
  return null
}

/**
 * Finds a descendant of [target] (up to [maxDepth] levels deep) that can be uniquely
 * identified within the entire tree. Returns a selector for that descendant, or null.
 *
 * Checks direct children first (most common case), then grandchildren, etc.
 */
internal fun findUniqueChildSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
  maxDepth: Int = 3,
): TrailblazeNodeSelector? {
  // BFS to check closest descendants first
  var currentLevel = target.children
  var depth = 0
  while (currentLevel.isNotEmpty() && depth < maxDepth) {
    for (descendant in currentLevel) {
      val childMatch = buildTargetMatch(descendant.driverDetail) ?: continue
      val childSelector = selectorWith(childMatch)
      val result = TrailblazeNodeSelectorResolver.resolve(root, childSelector)
      if (result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch) {
        return childSelector
      }
    }
    currentLevel = currentLevel.flatMap { it.children }
    depth++
  }
  return null
}

/**
 * Tries spatial strategies: finds a uniquely identifiable sibling or neighbor and
 * builds a selector using above/below/leftOf/rightOf relationships.
 *
 * Checks siblings first (most likely to be spatially close), then nearby nodes.
 * For each sibling, builds a spatial selector and verifies it uniquely identifies the target
 * before returning. Iterates all siblings rather than short-circuiting on the first spatial
 * candidate, because the first candidate may not produce a unique result.
 */
internal fun findSpatialSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
): TrailblazeNodeSelector? {
  val targetBounds = target.bounds ?: return null
  val targetMatch = buildTargetMatch(target.driverDetail)

  val parent = parentMap[target.nodeId]
  val candidates = parent?.children?.filter { it.nodeId != target.nodeId } ?: emptyList()

  for (sibling in candidates) {
    val siblingBounds = sibling.bounds ?: continue
    val siblingMatch = buildTargetMatch(sibling.driverDetail) ?: continue
    val siblingSelector = selectorWith(siblingMatch)

    if (!isUniqueMatch(root, sibling, siblingSelector)) continue

    val selector = buildSpatialSelector(targetMatch, targetBounds, siblingBounds, siblingSelector)
    if (selector != null && isUniqueMatch(root, target, selector)) return selector
  }

  return null
}

/**
 * Computes an index-based selector with the given driver match as a base.
 * The index is the target's position among all nodes matching the base selector.
 */
internal fun computeIndexSelectorForMatch(
  root: TrailblazeNode,
  target: TrailblazeNode,
  match: DriverNodeMatch?,
): TrailblazeNodeSelector? {
  val baseSelector = TrailblazeNodeSelector.withMatch(match)
  val result = TrailblazeNodeSelectorResolver.resolve(root, baseSelector)
  val matchedNodes = when (result) {
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> return baseSelector
    is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
    is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> return null
  }

  val idx = matchedNodes.indexOfFirst { it.nodeId == target.nodeId }
  return if (idx >= 0) baseSelector.copy(index = idx) else null
}

/**
 * Like [findUniqueParentSelector] but uses structural matches only for the parent.
 */
internal fun findUniqueStructuralParentSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
): TrailblazeNodeSelector? {
  var current = parentMap[target.nodeId]
  var depth = 0
  while (current != null && depth < 5) {
    val parentMatch = buildStructuralMatch(current.driverDetail)
    if (parentMatch != null) {
      val parentSelector = selectorWith(parentMatch)
      if (isUniqueMatch(root, current, parentSelector)) {
        return parentSelector
      }
    }
    current = parentMap[current.nodeId]
    depth++
  }
  return null
}

/**
 * Finds the nearest ancestor that can be uniquely identified using **content-based** matching.
 * The target itself remains structural, but using a content-aware parent as anchor is much
 * more reliable than a global index fallback. Returns a content-based parent selector.
 */
internal fun findContentParentSelectorForStructural(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
): TrailblazeNodeSelector? {
  var current = parentMap[target.nodeId]
  var depth = 0
  while (current != null && depth < 5) {
    val parentMatch = buildTargetMatch(current.driverDetail)
    if (parentMatch != null) {
      val parentSelector = selectorWith(parentMatch)
      if (isUniqueMatch(root, current, parentSelector)) {
        return parentSelector
      }
    }
    current = parentMap[current.nodeId]
    depth++
  }
  return null
}

/**
 * Computes a scoped index for the target within the nearest identifiable parent's subtree.
 * This produces selectors like `childOf(parent) + class + index=2` which is far more
 * stable than a global index because it only depends on sibling count, not the whole tree.
 *
 * Tries structural parent first, then content-based parent as fallback anchor.
 */
internal fun computeScopedIndexSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
  structuralMatch: DriverNodeMatch?,
): TrailblazeNodeSelector? {
  // Walk up looking for any identifiable ancestor to scope the index
  var current = parentMap[target.nodeId]
  var depth = 0
  while (current != null && depth < 5) {
    // Try structural match for parent first, then content match
    val parentMatch = buildStructuralMatch(current.driverDetail)
      ?: buildTargetMatch(current.driverDetail)
    if (parentMatch != null) {
      val parentSelector = selectorWith(parentMatch)
      if (isUniqueMatch(root, current, parentSelector)) {
        // Scope: resolve structural match within this parent's subtree only
        val scopedSelector = TrailblazeNodeSelector.withMatch(
          structuralMatch,
          childOf = parentSelector,
        )
        val result = TrailblazeNodeSelectorResolver.resolve(root, scopedSelector)
        val matchedNodes = when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> return scopedSelector
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> {
            current = parentMap[current.nodeId]
            depth++
            continue
          }
        }
        val idx = matchedNodes.indexOfFirst { it.nodeId == target.nodeId }
        if (idx >= 0) {
          return scopedSelector.copy(index = idx)
        }
      }
    }
    current = parentMap[current.nodeId]
    depth++
  }
  return null
}

/**
 * Like [findSpatialSelector] but uses structural matches for both target and sibling.
 */
internal fun findStructuralSpatialSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
): TrailblazeNodeSelector? {
  val targetBounds = target.bounds ?: return null
  val targetMatch = buildStructuralMatch(target.driverDetail)

  val parent = parentMap[target.nodeId]
  val candidates = parent?.children?.filter { it.nodeId != target.nodeId } ?: emptyList()

  for (sibling in candidates) {
    val siblingBounds = sibling.bounds ?: continue
    val siblingMatch = buildStructuralMatch(sibling.driverDetail) ?: continue
    val siblingSelector = selectorWith(siblingMatch)

    if (!isUniqueMatch(root, sibling, siblingSelector)) continue

    val selector = buildSpatialSelector(targetMatch, targetBounds, siblingBounds, siblingSelector)
    if (selector != null && isUniqueMatch(root, target, selector)) return selector
  }

  return null
}

/**
 * Like [findStructuralSpatialSelector] but allows content-based matching for the anchor
 * sibling while keeping the target match structural. This is much more likely to find a
 * unique anchor because content (text, descriptions) is far more distinctive than structure.
 */
internal fun findContentAnchoredSpatialSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
): TrailblazeNodeSelector? {
  val targetBounds = target.bounds ?: return null
  val targetMatch = buildStructuralMatch(target.driverDetail)

  val parent = parentMap[target.nodeId]
  val candidates = parent?.children?.filter { it.nodeId != target.nodeId } ?: emptyList()

  for (sibling in candidates) {
    val siblingBounds = sibling.bounds ?: continue
    // Use content-based match for the anchor sibling
    val siblingMatch = buildTargetMatch(sibling.driverDetail) ?: continue
    val siblingSelector = selectorWith(siblingMatch)

    if (!isUniqueMatch(root, sibling, siblingSelector)) continue

    val selector = buildSpatialSelector(targetMatch, targetBounds, siblingBounds, siblingSelector)
    if (selector != null && isUniqueMatch(root, target, selector)) return selector
  }

  return null
}

/**
 * Finds a uniquely identifiable child (structural or content-based) and uses it with
 * containsChild to identify the target structurally.
 */
internal fun findStructuralContainsChildSelector(
  root: TrailblazeNode,
  target: TrailblazeNode,
): TrailblazeNodeSelector? {
  val targetMatch = buildStructuralMatch(target.driverDetail)
  // BFS over children up to 2 levels deep
  var currentLevel = target.children
  var depth = 0
  while (currentLevel.isNotEmpty() && depth < 2) {
    for (descendant in currentLevel) {
      // Try structural child match first, then content-based
      val childMatch = buildStructuralMatch(descendant.driverDetail)
        ?: buildTargetMatch(descendant.driverDetail)
        ?: continue
      val childSelector = selectorWith(childMatch)
      if (!isUniqueMatch(root, descendant, childSelector)) continue

      val selector = TrailblazeNodeSelector.withMatch(targetMatch, containsChild = childSelector)
      if (isUniqueMatch(root, target, selector)) return selector
    }
    currentLevel = currentLevel.flatMap { it.children }
    depth++
  }
  return null
}
