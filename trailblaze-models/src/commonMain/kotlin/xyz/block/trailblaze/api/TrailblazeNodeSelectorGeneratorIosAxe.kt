package xyz.block.trailblaze.api

// ---------------------------------------------------------------------------
// iOS AXe strategies — selector-generation strategies for DriverNodeDetail.IosAxe nodes,
// driven off Apple's native AX vocabulary (role, subrole, label, value, uniqueId, etc.)
// rather than the Maestro-inferred shape used by [iosMaestroStrategies].
// ---------------------------------------------------------------------------

internal fun iosAxeStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.IosAxe,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Unique ID" to {
    detail.uniqueId?.takeIf { it.isNotBlank() }?.let { uid ->
      selectorWith(DriverNodeMatch.IosAxe(uniqueId = uid))
    }
  },
  "Label" to {
    detail.label?.takeIf { it.isNotBlank() }?.let { label ->
      selectorWith(DriverNodeMatch.IosAxe(labelRegex = escapeForSelector(label)))
    }
  },
  "Label + role" to {
    val label = detail.label?.takeIf { it.isNotBlank() }
    val role = detail.role
    if (label != null && role != null) {
      selectorWith(
        DriverNodeMatch.IosAxe(
          labelRegex = escapeForSelector(label),
          roleRegex = escapeForSelector(role),
        ),
      )
    } else {
      null
    }
  },
  "Value" to {
    if (detail.label.isNullOrBlank() && detail.value != null) {
      detail.value.takeIf { it.isNotBlank() }?.let { value ->
        selectorWith(DriverNodeMatch.IosAxe(valueRegex = escapeForSelector(value)))
      }
    } else {
      null
    }
  },
  "Title" to {
    if (detail.label.isNullOrBlank() && detail.value.isNullOrBlank() && detail.title != null) {
      detail.title.takeIf { it.isNotBlank() }?.let { title ->
        selectorWith(DriverNodeMatch.IosAxe(titleRegex = escapeForSelector(title)))
      }
    } else {
      null
    }
  },
  "Subrole + role" to {
    val subrole = detail.subrole?.takeIf { it.isNotBlank() }
    val role = detail.role
    if (subrole != null && role != null) {
      selectorWith(
        DriverNodeMatch.IosAxe(
          subroleRegex = escapeForSelector(subrole),
          roleRegex = escapeForSelector(role),
        ),
      )
    } else {
      null
    }
  },
  "Custom action + label" to {
    val label = detail.label?.takeIf { it.isNotBlank() }
    val action = detail.customActions.firstOrNull()
    if (label != null && action != null) {
      selectorWith(
        DriverNodeMatch.IosAxe(
          labelRegex = escapeForSelector(label),
          customAction = action,
        ),
      )
    } else {
      null
    }
  },
  "Unique ID + label" to {
    val uid = detail.uniqueId?.takeIf { it.isNotBlank() }
    val label = detail.label?.takeIf { it.isNotBlank() }
    if (uid != null && label != null) {
      selectorWith(
        DriverNodeMatch.IosAxe(
          uniqueId = uid,
          labelRegex = escapeForSelector(label),
        ),
      )
    } else {
      null
    }
  },
  "Role" to {
    detail.role?.let { role ->
      selectorWith(DriverNodeMatch.IosAxe(roleRegex = escapeForSelector(role)))
    }
  },
  // Trailing hierarchy/spatial/index — shared across all generators.
  childOfUniqueParentStrategy(root, target, detail, parentMap),
  containsUniqueChildStrategy(root, target, detail),
  spatialStrategy(root, target, parentMap),
  indexFallbackStrategy(root, target, detail),
)

// ---------------------------------------------------------------------------
// iOS AXe structural strategies — identity + type/role, no content.
// ---------------------------------------------------------------------------

internal fun namedStructuralIosAxeStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.IosAxe,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: unique ID" to {
    detail.uniqueId?.takeIf { it.isNotBlank() }?.let { uid ->
      selectorWith(DriverNodeMatch.IosAxe(uniqueId = uid))
    }
  },
  "Structural: role + subrole" to {
    val role = detail.role
    val subrole = detail.subrole?.takeIf { it.isNotBlank() }
    if (role != null && subrole != null) {
      selectorWith(
        DriverNodeMatch.IosAxe(
          roleRegex = escapeForSelector(role),
          subroleRegex = escapeForSelector(subrole),
        ),
      )
    } else {
      null
    }
  },
  "Structural: role" to {
    detail.role?.let { role ->
      selectorWith(DriverNodeMatch.IosAxe(roleRegex = escapeForSelector(role)))
    }
  },
  "Structural: type" to {
    detail.type?.let { type ->
      selectorWith(DriverNodeMatch.IosAxe(typeRegex = escapeForSelector(type)))
    }
  },
  // Trailing hierarchy/spatial/index — shared across all generators.
  // The structural index name follows the same convention as the other drivers:
  // `<primary field> + index`. IosAxe's structural match keys on role, so
  // `"Structural: role + index"` mirrors Compose/Web.
  structuralChildOfParentStrategy(root, target, detail, parentMap),
  structuralChildOfLabeledParentStrategy(root, target, detail, parentMap),
  structuralContainsChildStrategy(root, target),
  structuralSpatialStrategy(root, target, parentMap),
  structuralContentAnchoredSpatialStrategy(root, target, parentMap),
  structuralScopedIndexStrategy(root, target, detail, parentMap),
  structuralIndexFallbackStrategy(root, target, detail, name = "Structural: role + index"),
)
