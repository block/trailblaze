package xyz.block.trailblaze.api

// ---------------------------------------------------------------------------
// iOS Maestro strategies (mirrors Android Maestro with iOS-specific fields)
// ---------------------------------------------------------------------------

internal fun iosMaestroStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.IosMaestro,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.IosMaestro(resourceIdRegex = escapeForSelector(rid)))
    }
  },
  "Accessibility text" to {
    if (detail.text == null && detail.accessibilityText != null) {
      detail.accessibilityText.takeIf { it.isNotBlank() }?.let { a11y ->
        selectorWith(DriverNodeMatch.IosMaestro(accessibilityTextRegex = escapeForSelector(a11y)))
      }
    } else {
      null
    }
  },
  "Text" to {
    detail.text?.takeIf { it.isNotBlank() }?.let { text ->
      selectorWith(DriverNodeMatch.IosMaestro(textRegex = escapeForSelector(text)))
    }
  },
  "Hint text" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null) {
      detail.hintText.takeIf { it.isNotBlank() }?.let { hint ->
        selectorWith(DriverNodeMatch.IosMaestro(hintTextRegex = escapeForSelector(hint)))
      }
    } else {
      null
    }
  },
  "Text + class" to {
    val text = detail.text?.takeIf { it.isNotBlank() }
    val className = detail.className
    if (text != null && className != null) {
      selectorWith(
        DriverNodeMatch.IosMaestro(
          textRegex = escapeForSelector(text),
          classNameRegex = escapeForSelector(className),
        ),
      )
    } else {
      null
    }
  },
  "Hint text + class" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null && detail.className != null) {
      selectorWith(
        DriverNodeMatch.IosMaestro(
          hintTextRegex = escapeForSelector(detail.hintText),
          classNameRegex = escapeForSelector(detail.className),
        ),
      )
    } else {
      null
    }
  },
  "Resource ID + text" to {
    val rid = detail.resourceId
    val text = detail.text?.takeIf { it.isNotBlank() }
    if (rid != null && text != null) {
      selectorWith(
        DriverNodeMatch.IosMaestro(
          resourceIdRegex = escapeForSelector(rid),
          textRegex = escapeForSelector(text),
        ),
      )
    } else {
      null
    }
  },
  "Class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.IosMaestro(classNameRegex = escapeForSelector(cn)))
    }
  },
  "Child of parent" to {
    findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
      val targetMatch = buildTargetMatch(detail)
      TrailblazeNodeSelector.withMatch(targetMatch, childOf = parentSelector)
    }
  },
  "Contains child" to {
    findUniqueChildSelector(root, target)?.let { childSelector ->
      val targetMatch = buildTargetMatch(detail)
      TrailblazeNodeSelector.withMatch(targetMatch, containsChild = childSelector)
    }
  },
  "Spatial relationship" to {
    findSpatialSelector(root, target, parentMap)
  },
  "Index fallback" to {
    computeIndexSelectorForMatch(root, target, buildTargetMatch(detail))
  },
)

// ---------------------------------------------------------------------------
// iOS Maestro structural strategies
// ---------------------------------------------------------------------------

internal fun namedStructuralIosMaestroStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.IosMaestro,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.IosMaestro(resourceIdRegex = escapeForSelector(rid)))
    }
  },
  "Structural: class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.IosMaestro(classNameRegex = escapeForSelector(cn)))
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
  "Structural: spatial (labeled anchor)" to {
    findContentAnchoredSpatialSelector(root, target, parentMap)
  },
  "Structural: scoped index in parent" to {
    computeScopedIndexSelector(root, target, parentMap, buildStructuralMatch(detail))
  },
  "Structural: class + index" to {
    computeIndexSelectorForMatch(root, target, buildStructuralMatch(detail))
  },
)
