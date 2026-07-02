package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.escapeForSelector

// ---------------------------------------------------------------------------
// Selector enumeration: run-variable text stabilization + stability ranking
// ---------------------------------------------------------------------------
//
// [TrailblazeNodeSelectorGenerator.enumerateSelectorCandidates] lists *every* way the
// strategy cascade can identify an element (not just the single "best" one that
// [TrailblazeNodeSelectorGenerator.findAllValidSelectors] returns for a tap recording), so a
// human authoring a selector by hand can see the whole menu and pick. The helpers here supply
// the two things that enumeration needs beyond the raw cascade:
//
//  1. A ranking ([selectorStabilityRank]) that orders the computed selectors stable-first —
//     app-assigned identity, then semantic text, then structural type, then hierarchy/spatial.
//  2. One extra computed option the cascade never emits: a semantic-text selector with its
//     run-variable tail (an amount/count like `$0.00`) wildcarded to `.*`
//     ([runVariableWildcardedTextCandidate]), so a selector recorded against "Balance: $0.00"
//     still resolves on the next run.

/**
 * Currency symbols treated as run-variable, alongside digits. A label whose stable head runs
 * up to one of these (e.g. "Balance: $0.00", "Total €4,99") is wildcarded from that point on.
 */
private val CURRENCY_SYMBOLS = setOf('$', '€', '£', '¥', '¢', '₹', '₩', '₽', '₪', '₫', '₴', '₦', '฿')

/** A character that signals run-variable content: any digit or a currency symbol. */
internal fun isRunVariableChar(c: Char): Boolean = c.isDigit() || c in CURRENCY_SYMBOLS

/**
 * Wildcards a semantic label's run-variable tail (amounts, currency, counts) while keeping the
 * stable head literal, so a text selector survives between runs:
 *
 *  - `"Balance: $0.00"` -> `"Balance:.*"`
 *  - `"Cart 3"`         -> `"Cart.*"`
 *  - `"Add money"`      -> `"Add money"` (no run-variable content — returned unchanged)
 *
 * Returns `null` when the label has no stable head — a pure amount/count (`"$0.00"`, `"5"`), one
 * that leads with run-variable content (`"5 items"`), or one whose head is punctuation/sign only
 * with no actual semantic content (`"-$12.34"` -> head `"-"`; `"(555) 478-7672"` -> head `"("`).
 * A punctuation-only head would wildcard into something far too broad (`\Q-\E.*` matches any
 * string starting with a hyphen) and isn't identifying anything — so it's rejected same as an
 * empty head.
 */
internal fun waypointStableTextRegex(text: String): String? {
  val firstVariable = text.indexOfFirst { isRunVariableChar(it) }
  if (firstVariable < 0) {
    return text.takeIf { it.isNotBlank() }?.let { escapeForSelector(it) }
  }
  val head = text.substring(0, firstVariable).trimEnd()
  if (head.isEmpty() || head.none { it.isLetterOrDigit() }) return null
  return escapeForSelector(head) + ".*"
}

/**
 * Returns the best available stable-label selector for [detail] — the run-variable tail
 * wildcarded when the label needs it (see [waypointStableTextRegex]), or the plain literal
 * when it doesn't. Returns `null` when none of the driver's label fields have a usable stable
 * form (blank, editable/user-entered, or a run-variable value with no stable head).
 *
 * Tries each label field in the driver's usual priority order (text > secondary text > hint),
 * falling through to the next field whenever the preferred one is *present but unusable* — not
 * only when it's absent. This matters because the base cascade's own per-field strategies each
 * gate on "the higher-priority field is absent," so when text is present but useless (a bare
 * amount `"$0.00"` with no stable head), the base cascade never tries `contentDescription` at
 * all even if it holds a perfectly good stable label (`"Balance"`). This function is what
 * surfaces that fallback label as a candidate.
 *
 * The result only ever *adds* value on top of the base cascade: when the chosen field's pattern
 * is identical to what the base cascade's own strategy for that same field would produce, the
 * caller's selector-equality dedup collapses the two into one entry, so this never shows a
 * visible duplicate.
 */
internal fun runVariableWildcardedTextCandidate(
  detail: DriverNodeDetail,
): Pair<String, DriverNodeMatch>? = when (detail) {
  is DriverNodeDetail.AndroidAccessibility -> firstStableLabel(
    detail.text?.takeIf { !detail.isEditable } to { r: String ->
      nameFor("Text", r, detail.text) to DriverNodeMatch.AndroidAccessibility(textRegex = r)
    },
    detail.contentDescription to { r: String ->
      nameFor("Content description", r, detail.contentDescription) to DriverNodeMatch.AndroidAccessibility(contentDescriptionRegex = r)
    },
    detail.hintText to { r: String ->
      nameFor("Hint text", r, detail.hintText) to DriverNodeMatch.AndroidAccessibility(hintTextRegex = r)
    },
  )

  is DriverNodeDetail.IosMaestro -> firstStableLabel(
    detail.text to { r: String -> nameFor("Text", r, detail.text) to DriverNodeMatch.IosMaestro(textRegex = r) },
    detail.accessibilityText to { r: String ->
      nameFor("Accessibility text", r, detail.accessibilityText) to DriverNodeMatch.IosMaestro(accessibilityTextRegex = r)
    },
    detail.hintText to { r: String -> nameFor("Hint text", r, detail.hintText) to DriverNodeMatch.IosMaestro(hintTextRegex = r) },
  )

  is DriverNodeDetail.AndroidMaestro -> firstStableLabel(
    detail.text to { r: String -> nameFor("Text", r, detail.text) to DriverNodeMatch.AndroidMaestro(textRegex = r) },
    detail.accessibilityText to { r: String ->
      nameFor("Accessibility text", r, detail.accessibilityText) to DriverNodeMatch.AndroidMaestro(accessibilityTextRegex = r)
    },
    detail.hintText to { r: String -> nameFor("Hint text", r, detail.hintText) to DriverNodeMatch.AndroidMaestro(hintTextRegex = r) },
  )

  is DriverNodeDetail.Compose -> firstStableLabel(
    detail.text?.takeIf { detail.editableText == null } to { r: String ->
      nameFor("Text", r, detail.text) to DriverNodeMatch.Compose(textRegex = r)
    },
    detail.contentDescription to { r: String ->
      nameFor("Content description", r, detail.contentDescription) to DriverNodeMatch.Compose(contentDescriptionRegex = r)
    },
  )

  is DriverNodeDetail.IosAxe -> firstStableLabel(
    detail.label to { r: String -> nameFor("Label", r, detail.label) to DriverNodeMatch.IosAxe(labelRegex = r) },
    detail.value to { r: String -> nameFor("Value", r, detail.value) to DriverNodeMatch.IosAxe(valueRegex = r) },
    detail.title to { r: String -> nameFor("Title", r, detail.title) to DriverNodeMatch.IosAxe(titleRegex = r) },
  )

  is DriverNodeDetail.Web -> firstStableLabel(
    detail.ariaName to { r: String -> nameFor("ARIA name", r, detail.ariaName) to DriverNodeMatch.Web(ariaNameRegex = r) },
    detail.ariaDescriptor to { r: String ->
      nameFor("ARIA descriptor", r, detail.ariaDescriptor) to DriverNodeMatch.Web(ariaDescriptorRegex = r)
    },
  )
}

/**
 * Tries each `(rawValue, build)` pair in priority order, returning the first field with a
 * usable stable pattern ([waypointStableTextRegex] non-null). A field is skipped whenever it
 * yields nothing usable — absent, blank, or present-but-unstabilizable — so the chain reaches
 * a good fallback field regardless of *why* the preferred one didn't pan out.
 */
private fun firstStableLabel(
  vararg fields: Pair<String?, (String) -> Pair<String, DriverNodeMatch>>,
): Pair<String, DriverNodeMatch>? {
  for ((raw, build) in fields) {
    val text = raw?.takeIf { it.isNotBlank() } ?: continue
    val stabilized = waypointStableTextRegex(text) ?: continue
    return build(stabilized)
  }
  return null
}

/**
 * Strategy name for a chosen label field: suffixed with "— run-variable wildcarded" only when
 * [stabilized] actually differs from the field's plain literal (i.e. wildcarding happened).
 * A field that stabilized to its own literal (no run-variance) keeps the bare field name — it's
 * either identical to what the base cascade already offers (harmless duplicate, deduped by the
 * caller) or a fallback field the base cascade's own gate couldn't reach.
 */
private fun nameFor(field: String, stabilized: String, rawText: String?): String =
  if (rawText != null && stabilized != escapeForSelector(rawText)) "$field — run-variable wildcarded" else field

/**
 * Orders enumerated selectors stable-first. Lower rank sorts earlier:
 *
 *  - `0` app-assigned identity (resource id / unique id / test tag) — survives text, locale, layout
 *  - `1` semantic text (the label the user reads) — readable, locale-bound
 *  - `2` structural type only (class / role) — weak; matches many nodes
 *  - `3` a driver match with none of the above (should be rare)
 *  - `4` scoped to a parent (`childOf`) — depends on the ancestor being identifiable
 *  - `5` identified by a descendant (`containsChild` / `containsDescendants`) — depends on a
 *    child's content, historically prone to latching onto a meaningless one (e.g. an animation
 *    label) when a more direct signal exists, so it ranks below the direct-match tiers
 *  - `6` spatial (`above` / `below` / `leftOf` / `rightOf`) — depends on layout, most fragile
 *
 * Any selector that also carries a positional `index` ranks after every non-indexed selector
 * (its base tier is preserved as a tiebreaker among indexed selectors), reflecting that an index
 * is the most order-dependent, least meaningful signal — needed only when nothing else about the
 * node is unique. Its presence in the menu is itself informative: seeing an indexed variant next
 * to a non-indexed one flags that the non-indexed selector matches more than one node.
 */
internal fun selectorStabilityRank(selector: TrailblazeNodeSelector): Int {
  val base = when {
    selector.childOf != null -> 4
    selector.containsChild != null || selector.containsDescendants != null -> 5
    selector.above != null || selector.below != null ||
      selector.leftOf != null || selector.rightOf != null -> 6
    else -> selector.driverMatch?.let { matchTier(it) } ?: 3
  }
  return if (selector.index != null) base + 10 else base
}

/** Identity (0) > text (1) > type-only (2) > other (3), inspecting which fields a match sets. */
private fun matchTier(match: DriverNodeMatch): Int = when (match) {
  is DriverNodeMatch.AndroidAccessibility -> when {
    match.resourceIdRegex != null || match.uniqueId != null || match.composeTestTagRegex != null -> 0
    match.textRegex != null || match.contentDescriptionRegex != null || match.hintTextRegex != null ||
      match.labeledByTextRegex != null || match.paneTitleRegex != null ||
      match.stateDescriptionRegex != null || match.roleDescriptionRegex != null -> 1
    else -> 2
  }
  is DriverNodeMatch.AndroidMaestro -> when {
    match.resourceIdRegex != null -> 0
    match.textRegex != null || match.accessibilityTextRegex != null || match.hintTextRegex != null -> 1
    else -> 2
  }
  is DriverNodeMatch.IosMaestro -> when {
    match.resourceIdRegex != null -> 0
    match.textRegex != null || match.accessibilityTextRegex != null || match.hintTextRegex != null -> 1
    else -> 2
  }
  is DriverNodeMatch.IosAxe -> when {
    match.uniqueId != null -> 0
    match.labelRegex != null || match.valueRegex != null || match.titleRegex != null -> 1
    else -> 2
  }
  is DriverNodeMatch.Web -> when {
    match.dataTestId != null || match.cssSelector != null -> 0
    match.ariaNameRegex != null || match.ariaDescriptorRegex != null -> 1
    else -> 2
  }
  is DriverNodeMatch.Compose -> when {
    match.testTag != null -> 0
    match.textRegex != null || match.contentDescriptionRegex != null || match.editableTextRegex != null -> 1
    else -> 2
  }
}
