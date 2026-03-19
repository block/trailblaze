package xyz.block.trailblaze.api

// ---------------------------------------------------------------------------
// Compose strategies
// ---------------------------------------------------------------------------

internal fun composeStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.Compose,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  // Strategy 1: testTag (most stable, developer-assigned)
  "Test tag" to {
    detail.testTag?.let { tag ->
      selectorWith(DriverNodeMatch.Compose(testTag = tag))
    }
  },

  // === Precise text strategies (use specific fields, not resolveText) ===

  // Strategy 2: contentDescription alone (icon buttons, images with no text)
  "Content description" to {
    if (detail.text == null && detail.contentDescription != null) {
      detail.contentDescription.takeIf { it.isNotBlank() }?.let { desc ->
        selectorWith(DriverNodeMatch.Compose(contentDescriptionRegex = escapeForSelector(desc)))
      }
    } else {
      null
    }
  },
  // Strategy 3: contentDescription + role
  "Content description + role" to {
    if (detail.text == null && detail.contentDescription != null && detail.role != null) {
      selectorWith(
        DriverNodeMatch.Compose(
          contentDescriptionRegex = escapeForSelector(detail.contentDescription),
          role = detail.role,
        ),
      )
    } else {
      null
    }
  },
  // Strategy 4: text alone (static display text; skip for editable fields whose content changes)
  "Text" to {
    if (detail.editableText == null) {
      detail.text?.takeIf { it.isNotBlank() }?.let { text ->
        selectorWith(DriverNodeMatch.Compose(textRegex = escapeForSelector(text)))
      }
    } else {
      null
    }
  },
  // Strategy 5: text + role
  "Text + role" to {
    if (detail.editableText == null) {
      val text = detail.text?.takeIf { it.isNotBlank() }
      val role = detail.role
      if (text != null && role != null) {
        selectorWith(
          DriverNodeMatch.Compose(
            textRegex = escapeForSelector(text),
            role = role,
          ),
        )
      } else {
        null
      }
    } else {
      null
    }
  },
  // Strategy 6: role alone (single-instance widgets: unique Checkbox, Switch, ProgressIndicator)
  "Role" to {
    detail.role?.let { role ->
      selectorWith(DriverNodeMatch.Compose(role = role))
    }
  },
  // Strategy 7: role + toggleableState (stable toggle state labels like "On" / "Off")
  "Role + toggleable state" to {
    val role = detail.role
    val state = detail.toggleableState?.takeIf { it.isNotBlank() }
    if (role != null && state != null) {
      selectorWith(DriverNodeMatch.Compose(role = role, toggleableState = state))
    } else {
      null
    }
  },

  // === Hierarchy strategies ===

  // Strategy 8: childOf unique parent
  "Child of parent" to {
    findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
      val targetMatch = buildTargetMatch(detail)
      TrailblazeNodeSelector.withMatch(targetMatch, childOf = parentSelector)
    }
  },
  // Strategy 9: containsChild (unique child content)
  "Contains child" to {
    findUniqueChildSelector(root, target)?.let { childSelector ->
      val targetMatch = buildTargetMatch(detail)
      TrailblazeNodeSelector.withMatch(targetMatch, containsChild = childSelector)
    }
  },

  // === Spatial strategies ===

  // Strategy 10: spatial relationship to a uniquely identifiable sibling
  "Spatial relationship" to {
    findSpatialSelector(root, target, parentMap)
  },

  // === Index fallback ===

  // Strategy 11: index as last resort
  "Index fallback" to {
    computeIndexSelectorForMatch(root, target, buildTargetMatch(detail))
  },
)

// ---------------------------------------------------------------------------
// Compose structural strategies
// ---------------------------------------------------------------------------

internal fun namedStructuralComposeStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.Compose,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  "Structural: test tag" to {
    detail.testTag?.let { tag ->
      selectorWith(DriverNodeMatch.Compose(testTag = tag))
    }
  },
  "Structural: role" to {
    detail.role?.let { role ->
      selectorWith(DriverNodeMatch.Compose(role = role))
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
