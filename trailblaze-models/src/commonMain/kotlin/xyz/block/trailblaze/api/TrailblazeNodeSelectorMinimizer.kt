package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.unescapeForSelector

/**
 * Greedy field-dropping minimizer for [TrailblazeNodeSelector]s.
 *
 * The selector generator's compound strategies and shared helpers (notably
 * [buildTargetMatch]) emit kitchen-sink driver matches — every property that's
 * non-null on the node is packed in. This keeps the strategies themselves
 * simple, but it also means generated selectors often carry fields that aren't
 * load-bearing for uniqueness: e.g. `textRegex="Estimates", classNameRegex="android.widget.TextView"`
 * when `textRegex` alone would have resolved uniquely.
 *
 * Those extra fields are pure fragility: rename the widget from
 * `android.widget.TextView` to a Compose `Text` node and the selector breaks
 * for no semantic reason. The minimizer prunes them away.
 *
 * ## Algorithm
 *
 * Single greedy pass with a defined stability order. For each candidate-drop:
 *
 *   1. Substitute the drop into the selector.
 *   2. Re-resolve against the original tree and check that the candidate still
 *      uniquely matches the same target node.
 *   3. If yes, commit the drop and continue from the smaller selector.
 *   4. If no, the field was load-bearing — keep it.
 *
 * Drops are tried from **least stable** to **most stable** so when a choice
 * exists ("text-alone is unique" vs "class-alone is unique") the more stable
 * field survives. Field-stability ordering, per driver, lives in this file.
 *
 * Top-level structural relationships (above/below/leftOf/rightOf/childOf/
 * containsChild/containsDescendants/index) are tried as outermost drops first
 * — they're the biggest pieces and if they can go, removing them saves
 * inspecting their nested fields.
 *
 * For nested selectors that survive the outermost pass, the same algorithm
 * runs recursively on each one, with the predicate being "does the **outer**
 * selector still uniquely match the target?" — the nested selector isn't
 * required to identify any specific node on its own, only to keep the outer
 * selector's overall predicate unambiguous.
 *
 * ## Fixed-point iteration
 *
 * Stages run in a loop until the selector stops shrinking. A single pass
 * catches most cases, but inter-stage opportunities exist: dropping a
 * nested-selector field (stage 3) can sometimes make a previously-load-bearing
 * top-level field (stage 2) droppable, because the nested predicate became
 * weaker. Dropping fields only **widens** a selector's match set, so the loop
 * is monotone in selector size and terminates after at most O(field count)
 * iterations — in practice 1–2.
 *
 * ## Precondition
 *
 * The input selector must already uniquely resolve to [target]. The minimizer
 * verifies this on entry and returns the input unchanged if not — there's
 * nothing safe to drop when uniqueness isn't established to begin with.
 */
internal object TrailblazeNodeSelectorMinimizer {

  /**
   * Returns a minimized equivalent of [selector] that still uniquely matches
   * [target] in [root]. See the class kdoc for the algorithm. Idempotent.
   */
  fun minimize(
    root: TrailblazeNode,
    target: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): TrailblazeNodeSelector {
    if (!isUniqueMatch(root, target, selector)) return selector

    var current = selector
    // Iterate stages 1–3 until the selector stops shrinking. See kdoc for why
    // a single pass isn't always optimal.
    while (true) {
      val before = current
      current = dropRelationships(root, target, current)
      current = minimizeDriverMatchInPlace(current) { candidate ->
        isUniqueMatch(root, target, candidate)
      }
      current = minimizeNested(root, target, current)
      if (current == before) break
    }
    // Defense-in-depth: every individual drop was gated on a uniqueness check,
    // but the combination of stages-1-through-3 across iterations is a lot of
    // independently-committed transformations. Confirm the final selector still
    // uniquely matches before returning; if anything slipped (shouldn't, but
    // tree-altering bugs in this code path would silently emit broken
    // selectors), fall back to the input rather than ship a non-unique result.
    return if (isUniqueMatch(root, target, current)) current else selector
  }

  /**
   * Stage 1: try dropping each whole relationship slot
   * (spatial/index/childOf/containsChild/containsDescendants). Spatial and
   * index are fragile and rarely the cheapest disambiguator, so try them
   * first. `childOf` is typically a deliberate scope anchor — drop last among
   * relationships.
   */
  private fun dropRelationships(
    root: TrailblazeNode,
    target: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): TrailblazeNodeSelector {
    val drops: List<(TrailblazeNodeSelector) -> TrailblazeNodeSelector> = listOf(
      { it.copy(index = null) },
      { it.copy(above = null) },
      { it.copy(below = null) },
      { it.copy(leftOf = null) },
      { it.copy(rightOf = null) },
      { it.copy(containsChild = null) },
      { it.copy(containsDescendants = null) },
      { it.copy(childOf = null) },
    )
    var current = selector
    for (drop in drops) {
      val candidate = drop(current)
      if (candidate != current && isUniqueMatch(root, target, candidate)) {
        current = candidate
      }
    }
    return current
  }

  // ---------------------------------------------------------------------------
  // Top-level driver-match minimization
  // ---------------------------------------------------------------------------

  /**
   * Greedily drops non-null fields on [selector.driverMatch] while the
   * substituted selector still passes [stillUnique]. Drops are tried in a
   * driver-specific stability order — fragile fields like `classNameRegex`
   * are tried before stable ones like `uniqueId`/`resourceIdRegex`, so the
   * more stable identifier wins when either alone would suffice.
   *
   * If every individual field ends up dropped, the all-null match shell is
   * also removed so the selector serializes without a stray empty driver
   * block (e.g. `androidAccessibility: {}`).
   */
  private fun minimizeDriverMatchInPlace(
    selector: TrailblazeNodeSelector,
    stillUnique: (TrailblazeNodeSelector) -> Boolean,
  ): TrailblazeNodeSelector {
    val match = selector.driverMatch ?: return selector
    val minimized = minimizeMatch(match) { candidate ->
      stillUnique(selector.replaceDriverMatch(candidate))
    }
    return when {
      minimized === match -> selector
      minimized.isEmpty() -> {
        // Clearing the match here would leave a purely-positional selector when
        // the selector still carries an index. A naked ordinal shifts whenever
        // anything before the target changes, so retain the single most-stable
        // anchor instead — re-adding one field only narrows the empty-match set,
        // which already resolved uniquely.
        if (selector.index != null) {
          val anchor = keepMostStableField(match)
          if (!anchor.isEmpty()) return selector.replaceDriverMatch(anchor)
          // No non-null field existed on the original match (attribute-less node):
          // a bare index is the legitimate last resort. Fall through to clearing.
        }
        val withoutMatch = selector.clearDriverMatch()
        // Double-check uniqueness: a no-op-match selector should still resolve
        // uniquely since every empty-match candidate already passed `stillUnique`
        // during the field-drop loop, but be defensive against any path that
        // builds an empty match without testing it.
        if (stillUnique(withoutMatch)) withoutMatch else selector.replaceDriverMatch(minimized)
      }
      else -> selector.replaceDriverMatch(minimized)
    }
  }

  /**
   * Reduces [match] to its single most-stable non-null field. The per-driver
   * greedy drop tries fields least-stable → most-stable; gating each drop on
   * "at least one field still set" keeps dropping until only the single
   * most-stable field remains (dropping that last one would empty the match and
   * is rejected). Returns an empty match when [match] had no non-null fields.
   */
  private fun keepMostStableField(match: DriverNodeMatch): DriverNodeMatch =
    // drops only -- NOT minimizeMatch: this runs under a weaker "non-empty" gate, and
    // de-escaping (which can broaden a match) is only ever safe behind a real
    // tree-uniqueness gate. The single field kept here stays in its escaped form.
    dropFields(match) { candidate -> !candidate.isEmpty() }

  /**
   * Dispatch helper — the per-driver field-drop minimizers all follow the same shape but
   * each driver has a different field set, so they're written out separately. Drops only;
   * see [minimizeMatch] for the de-escape that follows.
   */
  private fun dropFields(
    match: DriverNodeMatch,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch = when (match) {
    is DriverNodeMatch.AndroidAccessibility -> minimizeAndroidAccessibility(match, stillUnique)
    is DriverNodeMatch.AndroidMaestro -> minimizeAndroidMaestro(match, stillUnique)
    is DriverNodeMatch.Web -> minimizeWeb(match, stillUnique)
    is DriverNodeMatch.Compose -> minimizeCompose(match, stillUnique)
    is DriverNodeMatch.IosMaestro -> minimizeIosMaestro(match, stillUnique)
    is DriverNodeMatch.IosAxe -> minimizeIosAxe(match, stillUnique)
  }

  /**
   * Drops redundant fields, then prettifies the survivors via [deEscapeProseText]. Both
   * stages are gated on [stillUnique], so this must be called only with a real
   * tree-uniqueness predicate -- [keepMostStableField] calls [dropFields] directly to
   * avoid de-escaping under its weaker "non-empty" gate.
   */
  private fun minimizeMatch(
    match: DriverNodeMatch,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch = deEscapeProseText(dropFields(match, stillUnique), stillUnique)

  /**
   * Stability order for AndroidAccessibility, least-stable → most-stable:
   *
   *   transient state flags → numeric input/collection → meta-text (pane,
   *   role, state descriptions) → **className** → secondary text
   *   (labeledBy/hint/contentDescription) → primary text → IDs (testTag,
   *   resourceId, uniqueId).
   *
   * Rationale: fragile UI implementation details (class type, transient
   * state) drop before stable developer-assigned identifiers. Text outranks
   * className because text is what the user sees; IDs outrank text because
   * they survive localization.
   */
  private fun minimizeAndroidAccessibility(
    match: DriverNodeMatch.AndroidAccessibility,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch.AndroidAccessibility {
    val drops: List<(DriverNodeMatch.AndroidAccessibility) -> DriverNodeMatch.AndroidAccessibility> = listOf(
      { it.copy(isSelected = null) },
      { it.copy(isFocused = null) },
      { it.copy(isChecked = null) },
      { it.copy(isCheckable = null) },
      { it.copy(isEnabled = null) },
      { it.copy(isClickable = null) },
      { it.copy(isEditable = null) },
      { it.copy(isScrollable = null) },
      { it.copy(isPassword = null) },
      { it.copy(isHeading = null) },
      { it.copy(isMultiLine = null) },
      { it.copy(inputType = null) },
      { it.copy(collectionItemRowIndex = null) },
      { it.copy(collectionItemColumnIndex = null) },
      { it.copy(paneTitleRegex = null) },
      { it.copy(roleDescriptionRegex = null) },
      { it.copy(stateDescriptionRegex = null) },
      { it.copy(classNameRegex = null) },
      { it.copy(labeledByTextRegex = null) },
      { it.copy(hintTextRegex = null) },
      { it.copy(contentDescriptionRegex = null) },
      { it.copy(textRegex = null) },
      { it.copy(composeTestTagRegex = null) },
      { it.copy(resourceIdRegex = null) },
      { it.copy(uniqueId = null) },
    )
    return greedilyApply(match, drops, stillUnique)
  }

  private fun minimizeAndroidMaestro(
    match: DriverNodeMatch.AndroidMaestro,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch.AndroidMaestro {
    val drops: List<(DriverNodeMatch.AndroidMaestro) -> DriverNodeMatch.AndroidMaestro> = listOf(
      { it.copy(selected = null) },
      { it.copy(focused = null) },
      { it.copy(checked = null) },
      { it.copy(enabled = null) },
      { it.copy(clickable = null) },
      { it.copy(classNameRegex = null) },
      { it.copy(hintTextRegex = null) },
      { it.copy(accessibilityTextRegex = null) },
      { it.copy(textRegex = null) },
      { it.copy(resourceIdRegex = null) },
    )
    return greedilyApply(match, drops, stillUnique)
  }

  private fun minimizeWeb(
    match: DriverNodeMatch.Web,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch.Web {
    val drops: List<(DriverNodeMatch.Web) -> DriverNodeMatch.Web> = listOf(
      { it.copy(nthIndex = null) },
      { it.copy(headingLevel = null) },
      { it.copy(ariaDescriptorRegex = null) },
      { it.copy(ariaRole = null) },
      { it.copy(ariaNameRegex = null) },
      { it.copy(cssSelector = null) },
      { it.copy(dataTestId = null) },
    )
    return greedilyApply(match, drops, stillUnique)
  }

  private fun minimizeCompose(
    match: DriverNodeMatch.Compose,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch.Compose {
    val drops: List<(DriverNodeMatch.Compose) -> DriverNodeMatch.Compose> = listOf(
      { it.copy(isSelected = null) },
      { it.copy(isFocused = null) },
      { it.copy(isEnabled = null) },
      { it.copy(isPassword = null) },
      { it.copy(toggleableState = null) },
      { it.copy(role = null) },
      { it.copy(editableTextRegex = null) },
      { it.copy(contentDescriptionRegex = null) },
      { it.copy(textRegex = null) },
      { it.copy(testTag = null) },
    )
    return greedilyApply(match, drops, stillUnique)
  }

  private fun minimizeIosMaestro(
    match: DriverNodeMatch.IosMaestro,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch.IosMaestro {
    val drops: List<(DriverNodeMatch.IosMaestro) -> DriverNodeMatch.IosMaestro> = listOf(
      { it.copy(selected = null) },
      { it.copy(focused = null) },
      { it.copy(classNameRegex = null) },
      { it.copy(hintTextRegex = null) },
      { it.copy(accessibilityTextRegex = null) },
      { it.copy(textRegex = null) },
      { it.copy(resourceIdRegex = null) },
    )
    return greedilyApply(match, drops, stillUnique)
  }

  private fun minimizeIosAxe(
    match: DriverNodeMatch.IosAxe,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch.IosAxe {
    val drops: List<(DriverNodeMatch.IosAxe) -> DriverNodeMatch.IosAxe> = listOf(
      { it.copy(enabled = null) },
      { it.copy(customAction = null) },
      { it.copy(subroleRegex = null) },
      { it.copy(roleRegex = null) },
      { it.copy(typeRegex = null) },
      { it.copy(titleRegex = null) },
      { it.copy(valueRegex = null) },
      { it.copy(labelRegex = null) },
      { it.copy(uniqueId = null) },
    )
    return greedilyApply(match, drops, stillUnique)
  }

  /**
   * Threads a [match] through each transform in [drops], committing every drop
   * that keeps [stillUnique] true. Equality short-circuits a no-op drop so we
   * don't waste a resolve when the candidate field is already null.
   */
  private inline fun <T : DriverNodeMatch> greedilyApply(
    match: T,
    drops: List<(T) -> T>,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): T {
    var current = match
    for (drop in drops) {
      val candidate = drop(current)
      if (candidate != current && stillUnique(candidate)) {
        current = candidate
      }
    }
    return current
  }

  // ---------------------------------------------------------------------------
  // Prose-text de-escaping
  // ---------------------------------------------------------------------------

  /**
   * Prettifies survivors: rewrites each prose-text field holding a `\\Q...\\E` escaped
   * literal back to its bare form, so a price reads `textRegex: $15.00` instead of
   * `\\Q$15.00\\E`. The resolver's regex->literal fallback (`matchesPattern`, mirrored in
   * the TS resolver and pinned by the `$3.00` case in `matcher-parity-fixtures.json`)
   * matches the bare literal identically, so this changes representation, not matching.
   *
   * Committed only while the selector still uniquely matches the target (via
   * [stillUnique]) -- same greedy, uniqueness-gated shape as the field-drop pass; a no-op
   * rewrite (non-escaped field) short-circuits on equality. The match set is identical to
   * the escaped form on the recorded hierarchy; a de-escaped live-regex literal can widen
   * against a hypothetical future sibling, exactly as a dropped field can, which is why
   * the gate is required. Genuinely ambiguous cases (bare `a.b` would also regex-match a
   * sibling `axb`) fail the gate and keep their escaping. Only human-visible text fields
   * are candidates; identifier fields (resourceId, className, testTag) are left alone.
   */
  private fun deEscapeProseText(
    match: DriverNodeMatch,
    stillUnique: (DriverNodeMatch) -> Boolean,
  ): DriverNodeMatch = when (match) {
    is DriverNodeMatch.AndroidAccessibility -> greedilyApply(
      match,
      listOf(
        { m -> unescapeForSelector(m.textRegex)?.let { m.copy(textRegex = it) } ?: m },
        { m -> unescapeForSelector(m.contentDescriptionRegex)?.let { m.copy(contentDescriptionRegex = it) } ?: m },
        { m -> unescapeForSelector(m.hintTextRegex)?.let { m.copy(hintTextRegex = it) } ?: m },
        { m -> unescapeForSelector(m.labeledByTextRegex)?.let { m.copy(labeledByTextRegex = it) } ?: m },
        { m -> unescapeForSelector(m.stateDescriptionRegex)?.let { m.copy(stateDescriptionRegex = it) } ?: m },
        { m -> unescapeForSelector(m.paneTitleRegex)?.let { m.copy(paneTitleRegex = it) } ?: m },
        { m -> unescapeForSelector(m.roleDescriptionRegex)?.let { m.copy(roleDescriptionRegex = it) } ?: m },
      ),
      stillUnique,
    )
    is DriverNodeMatch.AndroidMaestro -> greedilyApply(
      match,
      listOf(
        { m -> unescapeForSelector(m.textRegex)?.let { m.copy(textRegex = it) } ?: m },
        { m -> unescapeForSelector(m.accessibilityTextRegex)?.let { m.copy(accessibilityTextRegex = it) } ?: m },
        { m -> unescapeForSelector(m.hintTextRegex)?.let { m.copy(hintTextRegex = it) } ?: m },
      ),
      stillUnique,
    )
    is DriverNodeMatch.Web -> greedilyApply(
      match,
      listOf(
        { m -> unescapeForSelector(m.ariaNameRegex)?.let { m.copy(ariaNameRegex = it) } ?: m },
        { m -> unescapeForSelector(m.ariaDescriptorRegex)?.let { m.copy(ariaDescriptorRegex = it) } ?: m },
      ),
      stillUnique,
    )
    is DriverNodeMatch.Compose -> greedilyApply(
      match,
      listOf(
        { m -> unescapeForSelector(m.textRegex)?.let { m.copy(textRegex = it) } ?: m },
        { m -> unescapeForSelector(m.editableTextRegex)?.let { m.copy(editableTextRegex = it) } ?: m },
        { m -> unescapeForSelector(m.contentDescriptionRegex)?.let { m.copy(contentDescriptionRegex = it) } ?: m },
      ),
      stillUnique,
    )
    is DriverNodeMatch.IosMaestro -> greedilyApply(
      match,
      listOf(
        { m -> unescapeForSelector(m.textRegex)?.let { m.copy(textRegex = it) } ?: m },
        { m -> unescapeForSelector(m.accessibilityTextRegex)?.let { m.copy(accessibilityTextRegex = it) } ?: m },
        { m -> unescapeForSelector(m.hintTextRegex)?.let { m.copy(hintTextRegex = it) } ?: m },
      ),
      stillUnique,
    )
    is DriverNodeMatch.IosAxe -> greedilyApply(
      match,
      listOf(
        { m -> unescapeForSelector(m.labelRegex)?.let { m.copy(labelRegex = it) } ?: m },
        { m -> unescapeForSelector(m.valueRegex)?.let { m.copy(valueRegex = it) } ?: m },
        { m -> unescapeForSelector(m.titleRegex)?.let { m.copy(titleRegex = it) } ?: m },
      ),
      stillUnique,
    )
  }

  // ---------------------------------------------------------------------------
  // Nested-selector minimization
  // ---------------------------------------------------------------------------

  /**
   * Walks each surviving nested-selector slot and prunes redundant fields on
   * its driver match. The outer selector's uniqueness w.r.t. [target] is what
   * gates each drop — a nested selector inside `containsChild` doesn't need to
   * identify any specific node on its own, it just has to keep the outer
   * predicate unambiguous.
   *
   * Children, descendants, and ancestors are reached recursively: each nested
   * field is itself substitution-tested with [minimize] running under the
   * outer uniqueness predicate.
   */
  private fun minimizeNested(
    root: TrailblazeNode,
    target: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): TrailblazeNodeSelector {
    var current = selector

    // Spatial slots (`above`/`below`/`leftOf`/`rightOf`) have a stricter invariant
    // than other nested slots: the resolver uses `resolveFirstBounds` on the
    // anchor selector, so an anchor that resolves to more than one node will
    // silently pick whichever match the resolver enumerates first. That can
    // happen to keep the target unique under the current tree shape but break
    // at playback when sibling ordering shifts. Require the minimized anchor
    // to still resolve to a single node, on top of the usual outer-uniqueness
    // gate.
    current = minimizeNestedField(
      current,
      get = { it.above },
      set = { sel, nested -> sel.copy(above = nested) },
      outerUnique = { isUniqueMatch(root, target, it) },
      requireUniqueAnchor = { it.above },
      root = root,
    )
    current = minimizeNestedField(
      current,
      get = { it.below },
      set = { sel, nested -> sel.copy(below = nested) },
      outerUnique = { isUniqueMatch(root, target, it) },
      requireUniqueAnchor = { it.below },
      root = root,
    )
    current = minimizeNestedField(
      current,
      get = { it.leftOf },
      set = { sel, nested -> sel.copy(leftOf = nested) },
      outerUnique = { isUniqueMatch(root, target, it) },
      requireUniqueAnchor = { it.leftOf },
      root = root,
    )
    current = minimizeNestedField(
      current,
      get = { it.rightOf },
      set = { sel, nested -> sel.copy(rightOf = nested) },
      outerUnique = { isUniqueMatch(root, target, it) },
      requireUniqueAnchor = { it.rightOf },
      root = root,
    )
    // `childOf` and `containsChild` don't need the anchor-uniqueness check —
    // they're applied as predicates per candidate node, not via a "find the
    // first match's bounds" step.
    current = minimizeNestedField(
      current,
      get = { it.childOf },
      set = { sel, nested -> sel.copy(childOf = nested) },
      outerUnique = { isUniqueMatch(root, target, it) },
    )
    current = minimizeNestedField(
      current,
      get = { it.containsChild },
      set = { sel, nested -> sel.copy(containsChild = nested) },
      outerUnique = { isUniqueMatch(root, target, it) },
    )

    // containsDescendants is a list — try minimizing each element, and also
    // try dropping each element entirely (the list is a conjunction, so
    // removing one element only widens the match set).
    current.containsDescendants?.let { descendants ->
      var workingList = descendants
      // First pass: drop unneeded list elements.
      var i = 0
      while (i < workingList.size) {
        val candidateList = workingList.toMutableList().apply { removeAt(i) }
        val candidateSelector = current.copy(
          containsDescendants = candidateList.ifEmpty { null },
        )
        if (isUniqueMatch(root, target, candidateSelector)) {
          workingList = candidateList
          current = candidateSelector
          // don't increment i — the next element shifted into this slot
        } else {
          i++
        }
      }
      // Second pass: minimize each surviving element's driver match.
      //
      // Critical correctness point flagged by both Copilot and Codex on PR #3050:
      // each per-element field-drop is gated on "still unique under the *current*
      // list state," not the original list. Two drops on different elements can
      // each look safe in isolation but combine to widen the conjunction beyond
      // uniqueness. So we commit drops sequentially and the predicate always
      // resolves against the latest working list — same shape as `greedilyApply`.
      var idx = 0
      while (idx < workingList.size) {
        val descendant = workingList[idx]
        val descMatch = descendant.driverMatch
        if (descMatch == null) {
          idx++
          continue
        }
        val minimizedMatch = minimizeMatch(descMatch) { candidate ->
          val replaced = workingList.toMutableList().apply {
            set(idx, descendant.replaceDriverMatch(candidate))
          }
          isUniqueMatch(root, target, current.copy(containsDescendants = replaced))
        }
        val newDescendant = when {
          minimizedMatch === descMatch -> descendant
          minimizedMatch.isEmpty() -> descendant.clearDriverMatch()
          else -> descendant.replaceDriverMatch(minimizedMatch)
        }
        if (newDescendant !== descendant) {
          workingList = workingList.toMutableList().apply { set(idx, newDescendant) }
          current = current.copy(containsDescendants = workingList)
        }
        idx++
      }
    }

    return current
  }

  /**
   * Runs the field-drop loop on a single nested-selector slot. The slot's
   * presence and any internal relationships (a `containsChild` whose own
   * `childOf` is itself a selector) are left alone — only the driver-match
   * fields of [get](selector) are pruned. Whole-slot drops happened in
   * stage 1 of [minimize].
   */
  private fun minimizeNestedField(
    selector: TrailblazeNodeSelector,
    get: (TrailblazeNodeSelector) -> TrailblazeNodeSelector?,
    set: (TrailblazeNodeSelector, TrailblazeNodeSelector) -> TrailblazeNodeSelector,
    outerUnique: (TrailblazeNodeSelector) -> Boolean,
    requireUniqueAnchor: ((TrailblazeNodeSelector) -> TrailblazeNodeSelector?)? = null,
    root: TrailblazeNode? = null,
  ): TrailblazeNodeSelector {
    val nested = get(selector) ?: return selector
    val nestedMatch = nested.driverMatch ?: return selector
    val combinedPredicate: (TrailblazeNodeSelector) -> Boolean = if (requireUniqueAnchor != null && root != null) {
      { candidate -> outerUnique(candidate) && anchorResolvesToSingleNode(root, requireUniqueAnchor(candidate)) }
    } else {
      outerUnique
    }
    val minimizedNestedMatch = minimizeMatch(nestedMatch) { candidate ->
      val newNested = nested.replaceDriverMatch(candidate)
      combinedPredicate(set(selector, newNested))
    }
    if (minimizedNestedMatch === nestedMatch) return selector
    // Same empty-shell cleanup as the top level: if minimization stripped every
    // field on the nested driver match, the residual `AndroidAccessibility(...)`
    // shell would serialize as `androidAccessibility: {}` and add nothing. Strip
    // it — provided the outer predicate still passes with a match-less nested.
    val candidateNested = if (minimizedNestedMatch.isEmpty()) {
      nested.clearDriverMatch()
    } else {
      nested.replaceDriverMatch(minimizedNestedMatch)
    }
    return if (combinedPredicate(set(selector, candidateNested))) {
      set(selector, candidateNested)
    } else {
      set(selector, nested.replaceDriverMatch(minimizedNestedMatch))
    }
  }

  /**
   * True when [selector] resolves to exactly one node in [root]. A null selector
   * trivially satisfies the predicate (no anchor → nothing to validate).
   */
  private fun anchorResolvesToSingleNode(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector?,
  ): Boolean {
    if (selector == null) return true
    return TrailblazeNodeSelectorResolver.resolve(root, selector) is
      TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch
  }
}

/**
 * Builds a copy of this [TrailblazeNodeSelector] with the driver-match swapped
 * out — preserves all spatial/hierarchy/index slots and dispatches the new
 * match to the correct per-driver field via [TrailblazeNodeSelector.withMatch].
 */
internal fun TrailblazeNodeSelector.replaceDriverMatch(
  match: DriverNodeMatch,
): TrailblazeNodeSelector = TrailblazeNodeSelector.withMatch(
  match = match,
  below = below,
  above = above,
  leftOf = leftOf,
  rightOf = rightOf,
  childOf = childOf,
  containsChild = containsChild,
  containsDescendants = containsDescendants,
  index = index,
)

/**
 * Builds a copy of this [TrailblazeNodeSelector] with every per-driver field
 * cleared. Use when minimization has reduced the driver match to no fields —
 * the selector should serialize without a stray empty driver block.
 */
internal fun TrailblazeNodeSelector.clearDriverMatch(): TrailblazeNodeSelector =
  TrailblazeNodeSelector.withMatch(
    match = null,
    below = below,
    above = above,
    leftOf = leftOf,
    rightOf = rightOf,
    childOf = childOf,
    containsChild = containsChild,
    containsDescendants = containsDescendants,
    index = index,
  )

/**
 * True when no per-driver field is set on this match. An empty match contributes
 * no predicate at the driver-match level; if a selector's only constraint is
 * such a match, it would resolve to every node in the tree.
 */
internal fun DriverNodeMatch.isEmpty(): Boolean = when (this) {
  is DriverNodeMatch.AndroidAccessibility ->
    classNameRegex == null && resourceIdRegex == null && uniqueId == null &&
      composeTestTagRegex == null && textRegex == null && contentDescriptionRegex == null &&
      hintTextRegex == null && labeledByTextRegex == null && stateDescriptionRegex == null &&
      paneTitleRegex == null && roleDescriptionRegex == null && isEnabled == null &&
      isClickable == null && isCheckable == null && isChecked == null && isSelected == null &&
      isFocused == null && isEditable == null && isScrollable == null && isPassword == null &&
      isHeading == null && isMultiLine == null && inputType == null &&
      collectionItemRowIndex == null && collectionItemColumnIndex == null
  is DriverNodeMatch.AndroidMaestro ->
    textRegex == null && resourceIdRegex == null && accessibilityTextRegex == null &&
      classNameRegex == null && hintTextRegex == null && clickable == null && enabled == null &&
      focused == null && checked == null && selected == null
  is DriverNodeMatch.Web ->
    ariaRole == null && ariaNameRegex == null && ariaDescriptorRegex == null &&
      headingLevel == null && cssSelector == null && dataTestId == null && nthIndex == null
  is DriverNodeMatch.Compose ->
    testTag == null && role == null && textRegex == null && editableTextRegex == null &&
      contentDescriptionRegex == null && toggleableState == null && isEnabled == null &&
      isFocused == null && isSelected == null && isPassword == null
  is DriverNodeMatch.IosMaestro ->
    textRegex == null && resourceIdRegex == null && accessibilityTextRegex == null &&
      classNameRegex == null && hintTextRegex == null && focused == null && selected == null
  is DriverNodeMatch.IosAxe ->
    roleRegex == null && subroleRegex == null && labelRegex == null && valueRegex == null &&
      uniqueId == null && typeRegex == null && titleRegex == null && customAction == null &&
      enabled == null
}
