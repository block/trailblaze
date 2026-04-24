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

  // Strategies 8-11: hierarchy, spatial, and index — shared across all generators.
  childOfUniqueParentStrategy(root, target, detail, parentMap),
  containsUniqueChildStrategy(root, target, detail),
  spatialStrategy(root, target, parentMap),
  indexFallbackStrategy(root, target, detail),
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
  structuralChildOfParentStrategy(root, target, detail, parentMap),
  structuralChildOfLabeledParentStrategy(root, target, detail, parentMap),
  structuralContainsChildStrategy(root, target),
  structuralSpatialStrategy(root, target, parentMap),
  structuralContentAnchoredSpatialStrategy(root, target, parentMap),
  structuralScopedIndexStrategy(root, target, detail, parentMap),
  structuralIndexFallbackStrategy(root, target, detail, name = "Structural: role + index"),
)
