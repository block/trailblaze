package xyz.block.trailblaze.api

// ---------------------------------------------------------------------------
// Android Accessibility strategies (22 strategies, most → least precise)
// ---------------------------------------------------------------------------

internal fun androidAccessibilityStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.AndroidAccessibility,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  // === Identity strategies ===

  // Strategy 1: Unique stable ID (most reliable)
  // Tier order: developer-assigned IDs in their stability order. uniqueId (API 33+) is the most
  // stable identity Android offers; resourceId is the long-time standard; composeTestTag picks up
  // Compose `Modifier.testTag(...)` on apps that did NOT opt into testTagsAsResourceId (the
  // opt-in already surfaces testTag as resourceId, so it's caught by the second clause).
  "Unique ID / Resource ID / Compose testTag" to {
    detail.uniqueId?.let { uid ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(uniqueId = uid))
    } ?: detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = escapeForSelector(rid)))
    } ?: detail.composeTestTag?.let { tt ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(composeTestTagRegex = escapeForSelector(tt)))
    }
  },

  // === Precise text strategies (use specific fields, not resolveText) ===

  // Strategy 2: contentDescription alone (icon buttons, images with no text)
  "Content description" to {
    if (detail.text == null && detail.contentDescription != null) {
      detail.contentDescription.takeIf { it.isNotBlank() }?.let { desc ->
        selectorWith(
          DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = escapeForSelector(desc)),
        )
      }
    } else {
      null
    }
  },
  // Strategy 3: contentDescription + className (disambiguate icon buttons)
  "Content description + class" to {
    if (detail.text == null && detail.contentDescription != null && detail.className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          contentDescriptionRegex = escapeForSelector(detail.contentDescription),
          classNameRegex = escapeForSelector(detail.className),
        ),
      )
    } else {
      null
    }
  },
  // Strategy 4: hintText alone (empty input fields with placeholder)
  "Hint text" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null) {
      detail.hintText.takeIf { it.isNotBlank() }?.let { hint ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(hintTextRegex = escapeForSelector(hint)))
      }
    } else {
      null
    }
  },
  // Strategy 5: hintText + className (disambiguate empty inputs)
  "Hint text + class" to {
    if (detail.text.isNullOrBlank() && detail.hintText != null && detail.className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          hintTextRegex = escapeForSelector(detail.hintText),
          classNameRegex = escapeForSelector(detail.className),
        ),
      )
    } else {
      null
    }
  },
  // Strategy 6: text alone (most common success case for text-bearing elements)
  // Skip for editable fields — their text is user-entered content that changes between runs.
  "Text" to {
    if (!detail.isEditable) {
      detail.text?.takeIf { it.isNotBlank() }?.let { text ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(textRegex = escapeForSelector(text)))
      }
    } else {
      null
    }
  },
  // Strategy 7: text + className (e.g., "Fries" in an EditText vs a TextView)
  // Same editable guard — user input is not a stable identity.
  "Text + class" to {
    if (!detail.isEditable) {
      val text = detail.text?.takeIf { it.isNotBlank() }
      val className = detail.className
      if (text != null && className != null) {
        selectorWith(
          DriverNodeMatch.AndroidAccessibility(
            textRegex = escapeForSelector(text),
            classNameRegex = escapeForSelector(className),
          ),
        )
      } else {
        null
      }
    } else {
      null
    }
  },
  // Strategy 8: resourceId + text (RecyclerView items sharing resource IDs)
  "Resource ID + text" to {
    val rid = detail.resourceId
    val text = detail.text?.takeIf { it.isNotBlank() && !detail.isEditable }
    if (rid != null && text != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          resourceIdRegex = escapeForSelector(rid),
          textRegex = escapeForSelector(text),
        ),
      )
    } else {
      null
    }
  },
  // Strategy 9: labeledByText (for form fields: "the input labeled Email")
  "Labeled-by text" to {
    detail.labeledByText?.takeIf { it.isNotBlank() }?.let { label ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(labeledByTextRegex = escapeForSelector(label)))
    }
  },
  // Strategy 10: labeledByText + className
  "Labeled-by text + class" to {
    val label = detail.labeledByText?.takeIf { it.isNotBlank() }
    val className = detail.className
    if (label != null && className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          labeledByTextRegex = escapeForSelector(label),
          classNameRegex = escapeForSelector(className),
        ),
      )
    } else {
      null
    }
  },

  // === Type + state strategies ===

  // Strategy 11: paneTitle (dialogs, bottom sheets, navigation drawers)
  // Developer-assigned, very stable — tried before className-based strategies.
  "Pane title" to {
    detail.paneTitle?.takeIf { it.isNotBlank() }?.let { title ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(paneTitleRegex = escapeForSelector(title)))
    }
  },
  // Strategy 12: className alone (single-instance widgets: SeekBar, ProgressBar, RatingBar)
  "Class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(classNameRegex = escapeForSelector(cn)))
    }
  },
  // Strategy 13: className + rich state flags (broader than the old 3-flag version)
  "Class + state flags" to {
    detail.className?.let { cn ->
      val match = DriverNodeMatch.AndroidAccessibility(
        classNameRegex = escapeForSelector(cn),
        isEditable = if (detail.isEditable) true else null,
        isCheckable = if (detail.isCheckable) true else null,
        isHeading = if (detail.isHeading) true else null,
        isPassword = if (detail.isPassword) true else null,
        isScrollable = if (detail.isScrollable) true else null,
        // Note: isSelected is deliberately excluded — it is transient runtime state
        // (e.g., which tab is currently selected) that changes between recording and playback.
      )
      // Only try if we added at least one state predicate beyond className
      if (match.isEditable != null ||
        match.isCheckable != null ||
        match.isHeading != null ||
        match.isPassword != null ||
        match.isScrollable != null
      ) {
        selectorWith(match)
      } else {
        null
      }
    }
  },
  // Strategy 14: isPassword + className (password fields are usually unique)
  "Password + class" to {
    if (detail.isPassword && detail.className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          isPassword = true,
          classNameRegex = escapeForSelector(detail.className),
        ),
      )
    } else {
      null
    }
  },
  // Strategy 15: inputType + className (email vs phone vs number inputs)
  "Input type + class" to {
    if (detail.inputType != 0 && detail.className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          inputType = detail.inputType,
          classNameRegex = escapeForSelector(detail.className),
        ),
      )
    } else {
      null
    }
  },
  // Strategy 16: stateDescription + className (toggles with stable non-numeric state labels)
  // Skip numeric/percentage-like values (e.g., "50%", "3 of 10") that change between runs.
  "State description + class" to {
    val state = detail.stateDescription?.takeIf {
      it.isNotBlank() && !it.any { c -> c.isDigit() }
    }
    val className = detail.className
    if (state != null && className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          stateDescriptionRegex = escapeForSelector(state),
          classNameRegex = escapeForSelector(className),
        ),
      )
    } else {
      null
    }
  },
  // Strategy 17: roleDescription + className (semantic role overrides like "Toggle" / "Tab" /
  // "Star Rating"). roleDescription is developer-assigned and stable; pairing with className
  // narrows it to the same widget instance type so two distinct elements with the same role on
  // one screen don't collide.
  "Role description + class" to {
    val role = detail.roleDescription?.takeIf { it.isNotBlank() }
    val className = detail.className
    if (role != null && className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          roleDescriptionRegex = escapeForSelector(role),
          classNameRegex = escapeForSelector(className),
        ),
      )
    } else {
      null
    }
  },

  // === Hierarchy strategies ===

  // Strategies 18-19: target + childOf / containsChild — shared with other generators.
  childOfUniqueParentStrategy(root, target, detail, parentMap),
  containsUniqueChildStrategy(root, target, detail),
  // Strategy 20: collectionItemInfo (semantic list/grid position) — AndroidAccessibility-specific.
  "Collection item info" to {
    detail.collectionItemInfo?.let { ci ->
      val targetMatch = buildTargetMatch(detail)?.let { m ->
        (m as? DriverNodeMatch.AndroidAccessibility)?.copy(
          collectionItemRowIndex = ci.rowIndex,
          collectionItemColumnIndex = ci.columnIndex,
        )
      }
      targetMatch?.let { selectorWith(it) }
    }
  },

  // === Spatial + index ===
  // Strategies 21-22: spatial neighbor, then global index fallback — shared.
  spatialStrategy(root, target, parentMap),
  indexFallbackStrategy(root, target, detail),
)

// ---------------------------------------------------------------------------
// Android Accessibility structural strategies
// ---------------------------------------------------------------------------

internal fun namedStructuralAndroidAccessibilityStrategies(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail.AndroidAccessibility,
  parentMap: Map<Long, TrailblazeNode>,
): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
  // 1: Unique ID or resource ID or Compose testTag (most stable — developer-assigned identifiers)
  "Structural: unique ID" to {
    detail.uniqueId?.let { uid ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(uniqueId = uid))
    } ?: detail.resourceId?.let { rid ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = escapeForSelector(rid)))
    } ?: detail.composeTestTag?.let { tt ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(composeTestTagRegex = escapeForSelector(tt)))
    }
  },
  // 2: className alone (works for singleton widgets: SeekBar, ProgressBar, etc.)
  "Structural: class name" to {
    detail.className?.let { cn ->
      selectorWith(DriverNodeMatch.AndroidAccessibility(classNameRegex = escapeForSelector(cn)))
    }
  },
  // 3: className + state flags (distinguishes editable fields, checkboxes, etc.)
  "Structural: class + state" to {
    detail.className?.let { cn ->
      val match = DriverNodeMatch.AndroidAccessibility(
        classNameRegex = escapeForSelector(cn),
        isClickable = if (detail.isClickable) true else null,
        isEditable = if (detail.isEditable) true else null,
        isCheckable = if (detail.isCheckable) true else null,
        isHeading = if (detail.isHeading) true else null,
        isPassword = if (detail.isPassword) true else null,
        isScrollable = if (detail.isScrollable) true else null,
      )
      if (match.isClickable != null || match.isEditable != null || match.isCheckable != null ||
        match.isHeading != null || match.isPassword != null || match.isScrollable != null
      ) {
        selectorWith(match)
      } else {
        null
      }
    }
  },
  // 4: className + inputType
  "Structural: class + input type" to {
    if (detail.inputType != 0 && detail.className != null) {
      selectorWith(
        DriverNodeMatch.AndroidAccessibility(
          inputType = detail.inputType,
          classNameRegex = escapeForSelector(detail.className),
        ),
      )
    } else {
      null
    }
  },
  // 5: collectionItemInfo (semantic list/grid position)
  "Structural: collection position" to {
    detail.collectionItemInfo?.let { ci ->
      val match = buildStructuralMatch(detail)?.let { m ->
        (m as? DriverNodeMatch.AndroidAccessibility)?.copy(
          collectionItemRowIndex = ci.rowIndex,
          collectionItemColumnIndex = ci.columnIndex,
        )
      }
      match?.let { selectorWith(it) }
    }
  },
  // 6-12: structural hierarchy / spatial / index — all shared with the other generators.
  structuralChildOfParentStrategy(root, target, detail, parentMap),
  structuralChildOfLabeledParentStrategy(root, target, detail, parentMap),
  structuralContainsChildStrategy(root, target),
  structuralSpatialStrategy(root, target, parentMap),
  structuralContentAnchoredSpatialStrategy(root, target, parentMap),
  structuralScopedIndexStrategy(root, target, detail, parentMap),
  structuralIndexFallbackStrategy(root, target, detail, name = "Structural: class + index"),
)
