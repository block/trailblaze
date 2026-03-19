package xyz.block.trailblaze.api

// ---------------------------------------------------------------------------
// Web strategies
// ---------------------------------------------------------------------------

internal fun webStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.Web,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "CSS selector / data-testid" to {
    detail.cssSelector?.let { css ->
      selectorWith(DriverNodeMatch.Web(cssSelector = css))
    } ?: detail.dataTestId?.let { tid ->
      selectorWith(DriverNodeMatch.Web(dataTestId = tid))
    }
  },
  "ARIA role + name" to {
    val role = detail.ariaRole
    val name = detail.ariaName
    if (role != null && name != null) {
      selectorWith(DriverNodeMatch.Web(ariaRole = role, ariaNameRegex = escapeForSelector(name)))
    } else {
      null
    }
  },
  "ARIA role + name + nth" to {
    val role = detail.ariaRole
    val name = detail.ariaName
    if (role != null && name != null && detail.nthIndex > 0) {
      selectorWith(
        DriverNodeMatch.Web(
          ariaRole = role,
          ariaNameRegex = escapeForSelector(name),
          nthIndex = detail.nthIndex,
        ),
      )
    } else {
      null
    }
  },
  "Child of parent" to {
    findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
      val match = buildTargetMatch(detail)
      TrailblazeNodeSelector.withMatch(match, childOf = parentSelector)
    }
  },
  "Spatial relationship" to {
    findSpatialSelector(root, target, parentMap)
  },
)

// ---------------------------------------------------------------------------
// Web structural strategies
// ---------------------------------------------------------------------------

internal fun namedStructuralWebStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.Web,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: CSS selector / data-testid" to {
    detail.cssSelector?.let { css ->
      selectorWith(DriverNodeMatch.Web(cssSelector = css))
    } ?: detail.dataTestId?.let { tid ->
      selectorWith(DriverNodeMatch.Web(dataTestId = tid))
    }
  },
  "Structural: ARIA role" to {
    detail.ariaRole?.let { role ->
      selectorWith(DriverNodeMatch.Web(ariaRole = role))
    }
  },
  "Structural: ARIA role + nth" to {
    val role = detail.ariaRole
    if (role != null && detail.nthIndex > 0) {
      selectorWith(DriverNodeMatch.Web(ariaRole = role, nthIndex = detail.nthIndex))
    } else {
      null
    }
  },
  "Structural: child of parent" to {
    findUniqueStructuralParentSelector(root, target, parentMap)?.let { parentSelector ->
      val match = buildStructuralMatch(detail)
      TrailblazeNodeSelector.withMatch(match, childOf = parentSelector)
    }
  },
  "Structural: child of labeled parent" to {
    findContentParentSelectorForStructural(root, target, parentMap)?.let { parentSelector ->
      val match = buildStructuralMatch(detail)
      TrailblazeNodeSelector.withMatch(match, childOf = parentSelector)
    }
  },
  "Structural: contains child" to {
    findStructuralContainsChildSelector(root, target)
  },
  "Structural: spatial" to {
    findStructuralSpatialSelector(root, target, parentMap)
  },
  "Structural: spatial (labeled anchor)" to {
    findContentAnchoredSpatialSelector(root, target, parentMap)
  },
  "Structural: scoped index in parent" to {
    computeScopedIndexSelector(root, target, parentMap, buildStructuralMatch(detail))
  },
  "Structural: role + index" to {
    computeIndexSelectorForMatch(root, target, buildStructuralMatch(detail))
  },
)
