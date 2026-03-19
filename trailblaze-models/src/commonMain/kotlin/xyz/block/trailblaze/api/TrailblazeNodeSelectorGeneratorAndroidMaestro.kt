package xyz.block.trailblaze.api

// ---------------------------------------------------------------------------
// Android Maestro strategies (mirrors TapSelectorV2 logic)
// ---------------------------------------------------------------------------

internal fun androidMaestroStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.AndroidMaestro,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.AndroidMaestro(resourceIdRegex = escapeForSelector(rid)))
    }
  },
  "Text" to {
    detail.resolveText()?.takeIf { it.isNotBlank() }?.let { text ->
      selectorWith(DriverNodeMatch.AndroidMaestro(textRegex = escapeForSelector(text)))
    }
  },
  "Child of parent" to {
    findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
      val text = detail.resolveText()?.takeIf { it.isNotBlank() }
      val match = if (text != null) {
        DriverNodeMatch.AndroidMaestro(textRegex = escapeForSelector(text))
      } else {
        null
      }
      TrailblazeNodeSelector.withMatch(match, childOf = parentSelector)
    }
  },
)

// ---------------------------------------------------------------------------
// Android Maestro structural strategies
// ---------------------------------------------------------------------------

internal fun namedStructuralAndroidMaestroStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.AndroidMaestro,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: resource ID" to {
    detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.AndroidMaestro(resourceIdRegex = escapeForSelector(rid)))
    }
  },
  "Structural: class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.AndroidMaestro(classNameRegex = escapeForSelector(cn)))
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
