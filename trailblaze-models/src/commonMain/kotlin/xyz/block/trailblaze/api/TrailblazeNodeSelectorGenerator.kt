package xyz.block.trailblaze.api

/**
 * Generates [TrailblazeNodeSelector]s that uniquely identify a target [TrailblazeNode]
 * within a tree, using driver-specific properties for maximum precision.
 *
 * Follows a cascading-strategy approach: tries the simplest selector first and falls back
 * to more complex strategies until a unique match is found. Each candidate selector is
 * verified against the [TrailblazeNodeSelectorResolver] to confirm it produces exactly one
 * match (the target) before being returned.
 *
 * ## Android Accessibility strategy cascade (simplest to most complex)
 *
 * **Identity (1):** Unique stable ID or resource ID.
 * **Text (2-10):** Precise text matching using the specific text field
 * (contentDescription, hintText, text, resourceId+text, labeledByText).
 * **Type + state (11-16):** paneTitle, className, className + state flags, isPassword,
 * inputType, stateDescription — for elements without unique text.
 * **Hierarchy (17-19):** childOf parent, containsChild, collectionItemInfo.
 * **Spatial (20):** Positional relationships to uniquely identifiable neighbors.
 * **Fallback (21):** Index-based positioning as a last resort.
 *
 * ## Compose strategy cascade (simplest to most complex)
 *
 * **Identity (1):** testTag (developer-assigned, most stable).
 * **Text (2-7):** Precise field matching — contentDescription, text, role, toggleableState.
 * editableText is skipped (user-entered content, not stable identity).
 * **Hierarchy (8-9):** childOf parent, containsChild.
 * **Spatial (10):** Positional relationships to uniquely identifiable neighbors.
 * **Fallback (11):** Index-based positioning as a last resort.
 *
 * ## Recording flow
 * The caller provides the full tree and the target node. The generator returns the
 * simplest [TrailblazeNodeSelector] that resolves to exactly one match (the target).
 *
 * @see TrailblazeNodeSelectorResolver for matching selectors against trees
 * @see TrailblazeNodeSelector for the selector model
 */
object TrailblazeNodeSelectorGenerator {

  /**
   * A selector together with the strategy that produced it.
   */
  data class NamedSelector(
    val selector: TrailblazeNodeSelector,
    val strategy: String,
    val isBest: Boolean = false,
  )

  /**
   * Finds all valid selectors that uniquely identify [target] within the [root] tree,
   * up to [maxResults]. Each result includes the strategy name that produced it.
   *
   * The first result is the "best" selector (same as [findBestSelector] would return).
   * Useful for UI inspector views that want to show all selector options.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param target The node to find selectors for
   * @param maxResults Maximum number of selectors to return
   * @return List of named selectors, with [NamedSelector.isBest] set on the first
   */
  fun findAllValidSelectors(
    root: TrailblazeNode,
    target: TrailblazeNode,
    maxResults: Int = 5,
  ): List<NamedSelector> {
    val parentMap = buildParentMap(root)
    val namedStrategies = when (val detail = target.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility ->
        namedAndroidAccessibilityStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.AndroidMaestro ->
        namedAndroidMaestroStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Web ->
        namedWebStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Compose ->
        namedComposeStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.IosMaestro -> emptyList() // TODO: implement iOS selector strategies
    }

    val results = mutableListOf<NamedSelector>()
    for ((name, strategy) in namedStrategies) {
      if (results.size >= maxResults) break
      strategy()?.let { selector ->
        if (isUniqueMatch(root, target, selector)) {
          results.add(NamedSelector(selector, name, isBest = results.isEmpty()))
        }
      }
    }

    // Add index fallback if nothing else worked
    if (results.isEmpty()) {
      results.add(NamedSelector(computeIndexSelector(root, target), "Global index fallback", isBest = true))
    }

    return results
  }

  /**
   * Finds the best **structural** selector that uniquely identifies [target] within the [root]
   * tree, avoiding all text/content properties that change with localization or dynamic data.
   *
   * Structural selectors rely on:
   * - **Type**: className, role, ariaRole
   * - **Developer-assigned IDs**: resourceId, testTag, cssSelector, dataTestId
   * - **State flags**: isClickable, isScrollable, isEditable, isPassword, isHeading, etc.
   * - **Hierarchy**: childOf, containsChild, index among siblings
   * - **Spatial**: above/below/leftOf/rightOf structurally identifiable neighbors
   * - **Collection position**: row/column indices
   *
   * Content properties that are **excluded**: text, contentDescription, hintText,
   * labeledByText, stateDescription, paneTitle, ariaName, ariaDescriptor, editableText.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param target The node to find a structural selector for
   * @return A [NamedSelector] with the best structural selector, or an index fallback
   */
  fun findBestStructuralSelector(
    root: TrailblazeNode,
    target: TrailblazeNode,
  ): NamedSelector {
    val parentMap = buildParentMap(root)
    val namedStrategies = when (val detail = target.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility ->
        namedStructuralAndroidAccessibilityStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.AndroidMaestro ->
        namedStructuralAndroidMaestroStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Web ->
        namedStructuralWebStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Compose ->
        namedStructuralComposeStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.IosMaestro -> emptyList() // TODO: implement iOS structural strategies
    }

    for ((name, strategy) in namedStrategies) {
      strategy()?.let { selector ->
        if (isUniqueMatch(root, target, selector)) {
          return NamedSelector(selector, name, isBest = true)
        }
      }
    }

    return NamedSelector(
      computeIndexSelector(root, target),
      "Structural: global index fallback",
      isBest = true,
    )
  }

  /**
   * Finds the best selector that uniquely identifies [target] within the [root] tree.
   *
   * Tries strategies from simplest to most complex, returning the first selector
   * that resolves to a [TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch]
   * targeting the correct node.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param target The node to generate a selector for (must be in the tree)
   * @return A selector guaranteed to uniquely match [target], or falls back to index
   */
  fun findBestSelector(root: TrailblazeNode, target: TrailblazeNode): TrailblazeNodeSelector {
    // Pre-compute the parent map once for all strategies that need hierarchy traversal.
    val parentMap = buildParentMap(root)

    // Try each strategy in order until one produces a unique match
    val strategies: List<() -> TrailblazeNodeSelector?> = when (val detail = target.driverDetail) {
      is DriverNodeDetail.AndroidAccessibility ->
        androidAccessibilityStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.AndroidMaestro ->
        androidMaestroStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Web ->
        webStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.Compose ->
        composeStrategies(root, target, detail, parentMap)
      is DriverNodeDetail.IosMaestro -> emptyList() // TODO: implement iOS selector strategies
    }

    for (strategy in strategies) {
      strategy()?.let { selector ->
        if (isUniqueMatch(root, target, selector)) return selector
      }
    }

    // Absolute last resort: index among all nodes (should never fail)
    return computeIndexSelector(root, target)
  }

  // ---------------------------------------------------------------------------
  // Coordinate-based API: tap (x, y) → node → selector → verify
  // ---------------------------------------------------------------------------

  /**
   * Result of resolving a tap at (x, y) against the view hierarchy.
   *
   * Provides the hit-tested target node, the best selector for it, and a round-trip
   * verification confirming the selector would tap the same node at playback.
   */
  data class TapResolution(
    /** The frontmost node at the tap coordinates (smallest-area hit test). */
    val targetNode: TrailblazeNode,
    /** The best selector that uniquely identifies the target. */
    val selector: TrailblazeNodeSelector,
    /**
     * The coordinates that [selector] would resolve to at playback (node center by default).
     * Compare with the original tap point to assess coordinate drift.
     */
    val resolvedCenter: Pair<Int, Int>?,
    /**
     * True if [resolvedCenter] hit-tests back to the same [targetNode].
     * When false, a child element would intercept the tap at playback — the selector
     * needs a relative offset or a more specific target.
     */
    val roundTripValid: Boolean,
  )

  /**
   * Resolves a tap at (x, y) to a selector with full round-trip verification.
   *
   * 1. **Hit test** — finds the frontmost node at (x, y)
   * 2. **Generate** — produces the best unique selector for that node
   * 3. **Verify** — resolves the selector back to coordinates and hit-tests those coordinates
   *    to confirm the same node would be tapped at playback
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param x The tap X coordinate in device pixels
   * @param y The tap Y coordinate in device pixels
   * @return [TapResolution] with the target, selector, and verification result, or null if
   *         no node exists at (x, y)
   */
  fun resolveFromTap(root: TrailblazeNode, x: Int, y: Int): TapResolution? {
    val target = root.hitTest(x, y) ?: return null
    val selector = findBestSelector(root, target)

    val resolvedCenter = TrailblazeNodeSelectorResolver.resolveToCenter(root, selector)
    val roundTripValid = if (resolvedCenter != null) {
      val hitBack = root.hitTest(resolvedCenter.first, resolvedCenter.second)
      hitBack?.nodeId == target.nodeId
    } else {
      false
    }

    return TapResolution(
      targetNode = target,
      selector = selector,
      resolvedCenter = resolvedCenter,
      roundTripValid = roundTripValid,
    )
  }

  // --- Verification ---

  /** Checks that a selector resolves to exactly the target node. */
  private fun isUniqueMatch(
    root: TrailblazeNode,
    target: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): Boolean {
    val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
    return result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch &&
      result.node.nodeId == target.nodeId
  }

  // ---------------------------------------------------------------------------
  // Android Accessibility strategies (21 strategies, most → least precise)
  // ---------------------------------------------------------------------------

  private fun androidAccessibilityStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.AndroidAccessibility,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<() -> TrailblazeNodeSelector?> = listOf(
    // === Identity strategies ===

    // Strategy 1: Unique stable ID (most reliable)
    {
      detail.uniqueId?.let { uid ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(uniqueId = uid))
      } ?: detail.resourceId?.let { rid ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = escapeForSelector(rid)))
      }
    },

    // === Precise text strategies (use specific fields, not resolveText) ===

    // Strategy 2: contentDescription alone (icon buttons, images with no text)
    {
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
    {
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
    {
      if (detail.text.isNullOrBlank() && detail.hintText != null) {
        detail.hintText.takeIf { it.isNotBlank() }?.let { hint ->
          selectorWith(DriverNodeMatch.AndroidAccessibility(hintTextRegex = escapeForSelector(hint)))
        }
      } else {
        null
      }
    },
    // Strategy 5: hintText + className (disambiguate empty inputs)
    {
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
    {
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
    {
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
    {
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
    {
      detail.labeledByText?.takeIf { it.isNotBlank() }?.let { label ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(labeledByTextRegex = escapeForSelector(label)))
      }
    },
    // Strategy 10: labeledByText + className
    {
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
    {
      detail.paneTitle?.takeIf { it.isNotBlank() }?.let { title ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(paneTitleRegex = escapeForSelector(title)))
      }
    },
    // Strategy 12: className alone (single-instance widgets: SeekBar, ProgressBar, RatingBar)
    {
      detail.className?.let { cn ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(classNameRegex = escapeForSelector(cn)))
      }
    },
    // Strategy 13: className + rich state flags (broader than the old 3-flag version)
    {
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
    {
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
    {
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
    {
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

    // === Hierarchy strategies ===

    // Strategy 17: target + childOf unique parent
    {
      findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
        val targetMatch = buildTargetMatch(detail)
        TrailblazeNodeSelector.withMatch(targetMatch, childOf = parentSelector)
      }
    },
    // Strategy 18: containsChild (unique child content)
    {
      findUniqueChildSelector(root, target)?.let { childSelector ->
        val targetMatch = buildTargetMatch(detail)
        TrailblazeNodeSelector.withMatch(targetMatch, containsChild = childSelector)
      }
    },
    // Strategy 19: collectionItemInfo (semantic list/grid position)
    {
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

    // === Spatial strategies ===

    // Strategy 20: spatial relationship to a uniquely identifiable sibling
    {
      findSpatialSelector(root, target, parentMap)
    },

    // === Index fallback ===

    // Strategy 21: Index as last resort (before the global fallback)
    {
      computeIndexSelectorForMatch(root, target, buildTargetMatch(detail))
    },
  )

  // ---------------------------------------------------------------------------
  // Android Maestro strategies (mirrors TapSelectorV2 logic)
  // ---------------------------------------------------------------------------

  private fun androidMaestroStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.AndroidMaestro,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<() -> TrailblazeNodeSelector?> = listOf(
    // Strategy 1: Resource ID
    {
      detail.resourceId?.let { rid ->
        selectorWith(DriverNodeMatch.AndroidMaestro(resourceIdRegex = escapeForSelector(rid)))
      }
    },
    // Strategy 2: Text
    {
      detail.resolveText()?.takeIf { it.isNotBlank() }?.let { text ->
        selectorWith(DriverNodeMatch.AndroidMaestro(textRegex = escapeForSelector(text)))
      }
    },
    // Strategy 3: childOf parent
    {
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
  // Web strategies
  // ---------------------------------------------------------------------------

  private fun webStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.Web,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<() -> TrailblazeNodeSelector?> = listOf(
    // Strategy 1: CSS selector (data-testid or id)
    {
      detail.cssSelector?.let { css ->
        selectorWith(DriverNodeMatch.Web(cssSelector = css))
      } ?: detail.dataTestId?.let { tid ->
        selectorWith(DriverNodeMatch.Web(dataTestId = tid))
      }
    },
    // Strategy 2: ARIA role + name
    {
      val role = detail.ariaRole
      val name = detail.ariaName
      if (role != null && name != null) {
        selectorWith(DriverNodeMatch.Web(ariaRole = role, ariaNameRegex = escapeForSelector(name)))
      } else {
        null
      }
    },
    // Strategy 3: ARIA role + name + nth
    {
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
    // Strategy 4: childOf unique parent
    {
      findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
        val match = buildTargetMatch(detail)
        TrailblazeNodeSelector.withMatch(match, childOf = parentSelector)
      }
    },
    // Strategy 5: below a uniquely identifiable sibling
    {
      findSpatialSelector(root, target, parentMap)
    },
  )

  // ---------------------------------------------------------------------------
  // Compose strategies
  // ---------------------------------------------------------------------------

  private fun composeStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.Compose,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<() -> TrailblazeNodeSelector?> = listOf(
    // Strategy 1: testTag (most stable, developer-assigned)
    {
      detail.testTag?.let { tag ->
        selectorWith(DriverNodeMatch.Compose(testTag = tag))
      }
    },

    // === Precise text strategies (use specific fields, not resolveText) ===

    // Strategy 2: contentDescription alone (icon buttons, images with no text)
    {
      if (detail.text == null && detail.contentDescription != null) {
        detail.contentDescription.takeIf { it.isNotBlank() }?.let { desc ->
          selectorWith(DriverNodeMatch.Compose(contentDescriptionRegex = escapeForSelector(desc)))
        }
      } else {
        null
      }
    },
    // Strategy 3: contentDescription + role
    {
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
    {
      if (detail.editableText == null) {
        detail.text?.takeIf { it.isNotBlank() }?.let { text ->
          selectorWith(DriverNodeMatch.Compose(textRegex = escapeForSelector(text)))
        }
      } else {
        null
      }
    },
    // Strategy 5: text + role
    {
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
    {
      detail.role?.let { role ->
        selectorWith(DriverNodeMatch.Compose(role = role))
      }
    },
    // Strategy 7: role + toggleableState (stable toggle state labels like "On" / "Off")
    {
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
    {
      findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
        val targetMatch = buildTargetMatch(detail)
        TrailblazeNodeSelector.withMatch(targetMatch, childOf = parentSelector)
      }
    },
    // Strategy 9: containsChild (unique child content)
    {
      findUniqueChildSelector(root, target)?.let { childSelector ->
        val targetMatch = buildTargetMatch(detail)
        TrailblazeNodeSelector.withMatch(targetMatch, containsChild = childSelector)
      }
    },

    // === Spatial strategies ===

    // Strategy 10: spatial relationship to a uniquely identifiable sibling
    {
      findSpatialSelector(root, target, parentMap)
    },

    // === Index fallback ===

    // Strategy 11: index as last resort
    {
      computeIndexSelectorForMatch(root, target, buildTargetMatch(detail))
    },
  )

  // ---------------------------------------------------------------------------
  // Named strategies (for UI inspector — pairs of strategy name + lambda)
  // ---------------------------------------------------------------------------

  private fun namedAndroidAccessibilityStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.AndroidAccessibility,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<Pair<String, () -> TrailblazeNodeSelector?>> {
    val strategies = androidAccessibilityStrategies(root, target, detail, parentMap)
    val names = listOf(
      "Unique ID / Resource ID",
      "Content description",
      "Content description + class",
      "Hint text",
      "Hint text + class",
      "Text",
      "Text + class",
      "Resource ID + text",
      "Labeled-by text",
      "Labeled-by text + class",
      "Pane title",
      "Class name",
      "Class + state flags",
      "Password + class",
      "Input type + class",
      "State description + class",
      "Child of parent",
      "Collection item info",
      "Contains child",
      "Spatial relationship",
      "Index fallback",
    )
    return names.zip(strategies)
  }

  private fun namedAndroidMaestroStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.AndroidMaestro,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<Pair<String, () -> TrailblazeNodeSelector?>> {
    val strategies = androidMaestroStrategies(root, target, detail, parentMap)
    val names = listOf(
      "Resource ID",
      "Text",
      "Child of parent",
    )
    return names.zip(strategies)
  }

  private fun namedWebStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.Web,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<Pair<String, () -> TrailblazeNodeSelector?>> {
    val strategies = webStrategies(root, target, detail, parentMap)
    val names = listOf(
      "CSS selector / data-testid",
      "ARIA role + name",
      "ARIA role + name + nth",
      "Child of parent",
      "Spatial relationship",
    )
    return names.zip(strategies)
  }

  private fun namedComposeStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.Compose,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<Pair<String, () -> TrailblazeNodeSelector?>> {
    val strategies = composeStrategies(root, target, detail, parentMap)
    val names = listOf(
      "Test tag",
      "Content description",
      "Content description + role",
      "Text",
      "Text + role",
      "Role",
      "Role + toggleable state",
      "Child of parent",
      "Contains child",
      "Spatial relationship",
      "Index fallback",
    )
    return names.zip(strategies)
  }

  // ---------------------------------------------------------------------------
  // Structural strategies (content-free selectors)
  // ---------------------------------------------------------------------------

  /**
   * Builds a structural-only [DriverNodeMatch] for a node, using only type, IDs, and state
   * flags — never text, contentDescription, hintText, or other content properties.
   */
  private fun buildStructuralMatch(detail: DriverNodeDetail): DriverNodeMatch? = when (detail) {
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

  /**
   * Like [findUniqueParentSelector] but uses structural matches only for the parent.
   */
  private fun findUniqueStructuralParentSelector(
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
  private fun findContentParentSelectorForStructural(
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
  private fun computeScopedIndexSelector(
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
  private fun findStructuralSpatialSelector(
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
  private fun findContentAnchoredSpatialSelector(
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
  private fun findStructuralContainsChildSelector(
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

  /** Builds a spatial selector given target match, bounds, and an anchor. */
  private fun buildSpatialSelector(
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

  // -- Android Accessibility structural strategies --

  private fun namedStructuralAndroidAccessibilityStrategies(
    root: TrailblazeNode,
    target: TrailblazeNode,
    detail: DriverNodeDetail.AndroidAccessibility,
    parentMap: Map<Long, TrailblazeNode>,
  ): List<Pair<String, () -> TrailblazeNodeSelector?>> = listOf(
    // 1: Unique ID or resource ID (most stable — developer-assigned identifiers)
    "Structural: unique ID" to {
      detail.uniqueId?.let { uid ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(uniqueId = uid))
      } ?: detail.resourceId?.let { rid ->
        selectorWith(DriverNodeMatch.AndroidAccessibility(resourceIdRegex = escapeForSelector(rid)))
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
    // 6: childOf structurally unique parent
    "Structural: child of parent" to {
      findUniqueStructuralParentSelector(root, target, parentMap)?.let { parentSelector ->
        val targetMatch = buildStructuralMatch(detail)
        TrailblazeNodeSelector.withMatch(targetMatch, childOf = parentSelector)
      }
    },
    // 7: childOf content-identifiable parent (target stays structural, parent uses content)
    "Structural: child of labeled parent" to {
      findContentParentSelectorForStructural(root, target, parentMap)?.let { parentSelector ->
        val targetMatch = buildStructuralMatch(detail)
        TrailblazeNodeSelector.withMatch(targetMatch, childOf = parentSelector)
      }
    },
    // 8: containsChild (target identified by having a unique descendant)
    "Structural: contains child" to {
      findStructuralContainsChildSelector(root, target)
    },
    // 9: spatial relationship to structurally identifiable sibling
    "Structural: spatial" to {
      findStructuralSpatialSelector(root, target, parentMap)
    },
    // 10: spatial with content-based anchor (target structural, anchor uses content)
    "Structural: spatial (labeled anchor)" to {
      findContentAnchoredSpatialSelector(root, target, parentMap)
    },
    // 11: scoped index within identifiable parent (much better than global index)
    "Structural: scoped index in parent" to {
      computeScopedIndexSelector(root, target, parentMap, buildStructuralMatch(detail))
    },
    // 12: structural match + global index (last resort before bare global index)
    "Structural: class + index" to {
      computeIndexSelectorForMatch(root, target, buildStructuralMatch(detail))
    },
  )

  // -- Android Maestro structural strategies --

  private fun namedStructuralAndroidMaestroStrategies(
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

  // -- Web structural strategies --

  private fun namedStructuralWebStrategies(
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

  // -- Compose structural strategies --

  private fun namedStructuralComposeStrategies(
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

  // ---------------------------------------------------------------------------
  // Shared helpers
  // ---------------------------------------------------------------------------

  /** Wraps a [DriverNodeMatch] in a [TrailblazeNodeSelector]. */
  private fun selectorWith(match: DriverNodeMatch): TrailblazeNodeSelector =
    TrailblazeNodeSelector.withMatch(match)

  /**
   * Regex metacharacters that require escaping for selector patterns.
   * When text contains none of these, it can be used as-is (it's a valid regex
   * that matches literally). When it contains any, wrap in `\Q...\E`.
   */
  private val REGEX_METACHARACTERS = setOf(
    '\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}',
  )

  /**
   * Escapes text for use in selector regex fields. Returns the text as-is when it
   * contains no regex metacharacters (producing cleaner YAML), or wraps in `\Q...\E`
   * when escaping is needed.
   */
  private fun escapeForSelector(text: String): String =
    if (text.any { it in REGEX_METACHARACTERS }) Regex.escape(text) else text

  /**
   * Builds the most precise [DriverNodeMatch] for the target, using the specific text field
   * (contentDescription, hintText, text) rather than the lossy [resolveText] fallback.
   *
   * This feeds into hierarchy strategies (childOf, containsChild, collection, spatial) and
   * the index fallback, so precision here improves all composite strategies.
   */
  private fun buildTargetMatch(detail: DriverNodeDetail): DriverNodeMatch? = when (detail) {
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

  /** Builds a map from child nodeId to parent node for the entire tree. */
  private fun buildParentMap(root: TrailblazeNode): Map<Long, TrailblazeNode> {
    val parentMap = mutableMapOf<Long, TrailblazeNode>()
    fun visit(node: TrailblazeNode) {
      for (child in node.children) {
        parentMap[child.nodeId] = node
        visit(child)
      }
    }
    visit(root)
    return parentMap
  }

  /**
   * Walks up the tree to find the nearest ancestor that can be uniquely identified.
   * Returns a selector for that parent, or null if no unique parent is found.
   */
  private fun findUniqueParentSelector(
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
  private fun findUniqueChildSelector(
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
  private fun findSpatialSelector(
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
  private fun computeIndexSelectorForMatch(
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
   * Global index fallback: assigns an index among ALL nodes in the tree.
   * This always succeeds but produces the most brittle selector.
   */
  private fun computeIndexSelector(
    root: TrailblazeNode,
    target: TrailblazeNode,
  ): TrailblazeNodeSelector {
    val allNodes = root.aggregate()
      .sortedWith(
        compareBy(
          { it.bounds?.top ?: Int.MAX_VALUE },
          { it.bounds?.left ?: Int.MAX_VALUE },
        ),
      )
    val idx = allNodes.indexOfFirst { it.nodeId == target.nodeId }
    check(idx >= 0) { "Target node ${target.nodeId} not found in tree — cannot generate selector" }
    return TrailblazeNodeSelector(index = idx)
  }
}
