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
  childOfUniqueParentStrategy(root, target, detail, parentMap),
  spatialStrategy(root, target, parentMap),
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
  structuralChildOfParentStrategy(root, target, detail, parentMap),
  structuralChildOfLabeledParentStrategy(root, target, detail, parentMap),
  structuralContainsChildStrategy(root, target),
  structuralSpatialStrategy(root, target, parentMap),
  structuralContentAnchoredSpatialStrategy(root, target, parentMap),
  structuralScopedIndexStrategy(root, target, detail, parentMap),
  structuralIndexFallbackStrategy(root, target, detail, name = "Structural: role + index"),
)
