package xyz.block.trailblaze.api

/**
 * Resolves [TrailblazeNodeSelector] against a [TrailblazeNode] tree.
 *
 * This is the [TrailblazeNode]-native equivalent of Maestro's Orchestra `buildFilter`.
 * It operates directly on the rich [DriverNodeDetail] properties without going through
 * any compatibility layer.
 *
 * ## Resolution strategy
 * 1. Flatten the search scope (respecting [TrailblazeNodeSelector.childOf] for scoped searches)
 * 2. Apply driver-specific property matching via [DriverNodeMatch]
 * 3. Apply spatial relationship predicates (above, below, leftOf, rightOf)
 * 4. Apply hierarchy predicates (containsChild, containsDescendants)
 * 5. Sort by position (top-to-bottom, left-to-right)
 * 6. Apply index if specified
 *
 * @see TrailblazeNodeSelector for the selector model
 */
object TrailblazeNodeSelectorResolver {

  /** Maximum nesting depth for recursive resolve() calls (spatial/hierarchy selectors). */
  private const val MAX_RESOLVE_DEPTH = 10

  /** Result of an element resolution attempt. */
  sealed interface ResolveResult {
    /** Exactly one element matched — the success case for interactions. */
    data class SingleMatch(val node: TrailblazeNode) : ResolveResult

    /** No elements matched the selector. */
    data class NoMatch(val selector: TrailblazeNodeSelector) : ResolveResult

    /** Multiple elements matched — the selector is ambiguous. */
    data class MultipleMatches(
      val nodes: List<TrailblazeNode>,
      val selector: TrailblazeNodeSelector,
    ) : ResolveResult
  }

  /**
   * Resolves a [TrailblazeNodeSelector] against the tree rooted at [root].
   *
   * When [target] is supplied, the selector is expanded once via
   * [SelectorTemplating.expand] before resolution — every `{{target.appId}}` placeholder
   * inside the selector tree (including nested spatial/hierarchy sub-selectors) is
   * substituted before any regex compile happens. Callers that hold session context
   * (agent / matcher / executor) thread it through here so the resolver is the single
   * site that knows about templating; callers without context (inspector UI, ad-hoc
   * selector evaluation, unit-test fixtures with literal selectors) pass null.
   *
   * @param root The root of the [TrailblazeNode] tree
   * @param selector The selector to match
   * @param target Optional template context for `{{target.appId}}` substitution
   * @return [ResolveResult] indicating zero, one, or multiple matches
   */
  fun resolve(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    target: TargetTemplateContext?,
  ): ResolveResult {
    val expanded = if (target != null) SelectorTemplating.expand(selector, target) else selector
    return resolve(root, expanded, depth = 0)
  }

  /**
   * 2-arg overload preserving the wire/binary signature of the previously-published
   * `resolve(root, selector)` method. `@JvmOverloads` would be the idiomatic JVM-only
   * answer, but this object lives in `commonMain` and `kotlin.jvm.JvmOverloads` isn't
   * available on the wasmJs target — the explicit overload is multiplatform-safe.
   */
  fun resolve(root: TrailblazeNode, selector: TrailblazeNodeSelector): ResolveResult =
    resolve(root, selector, target = null)

  private fun resolve(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    depth: Int,
  ): ResolveResult {
    if (depth > MAX_RESOLVE_DEPTH) return ResolveResult.NoMatch(selector)

    // Step 1: Determine search scope via childOf (exclude parent itself — only descendants)
    val searchScope = selector.childOf?.let { childOfSelector ->
      when (val parentResult = resolve(root, childOfSelector, depth + 1)) {
        is ResolveResult.SingleMatch -> parentResult.node.aggregate().drop(1)
        is ResolveResult.MultipleMatches -> parentResult.nodes.flatMap { it.aggregate().drop(1) }
        is ResolveResult.NoMatch -> return ResolveResult.NoMatch(selector)
      }
    } ?: root.aggregate()

    // Step 2: Apply driver match + spatial + hierarchy predicates, sort by position
    val matched = searchScope
      .filter { node -> matchesSelector(node, selector, root, depth) }
      .sortedWith(
        compareBy(
          { it.bounds?.top ?: Int.MAX_VALUE },
          { it.bounds?.left ?: Int.MAX_VALUE },
        ),
      )

    // Step 3: Apply index if specified
    val finalResults = selector.index?.let { idx ->
      if (idx in matched.indices) listOf(matched[idx]) else emptyList()
    } ?: matched

    return when (finalResults.size) {
      0 -> ResolveResult.NoMatch(selector)
      1 -> ResolveResult.SingleMatch(finalResults.first())
      else -> ResolveResult.MultipleMatches(finalResults, selector)
    }
  }

  /**
   * Convenience: resolves and returns the center point for tapping, or null.
   * Uses the first match if multiple are found.
   *
   * See [resolve] for the [target] parameter semantics.
   */
  fun resolveToCenter(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    target: TargetTemplateContext?,
  ): Pair<Int, Int>? = when (val result = resolve(root, selector, target)) {
    is ResolveResult.SingleMatch -> result.node.centerPoint()
    is ResolveResult.MultipleMatches -> result.nodes.first().centerPoint()
    is ResolveResult.NoMatch -> null
  }

  /** See [resolve] — same multiplatform-safe explicit-overload rationale. */
  fun resolveToCenter(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): Pair<Int, Int>? = resolveToCenter(root, selector, target = null)

  // --- Private matching logic ---

  /**
   * Returns true if [node] matches all predicates in [selector] (excluding childOf and index).
   *
   * The [depth] parameter guards against unbounded recursion from nested containsChild/
   * containsDescendants selectors. Each recursive call increments depth.
   */
  private fun matchesSelector(
    node: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    root: TrailblazeNode,
    depth: Int = 0,
  ): Boolean {
    if (depth > MAX_RESOLVE_DEPTH) return false

    // Driver-specific property matching
    selector.driverMatch?.let { match ->
      if (!matchesDriverDetail(node.driverDetail, match)) return false
    }

    // Spatial relationships
    val spatialChecks = listOf(
      selector.below to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.top >= anchor.bottom },
      selector.above to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.bottom <= anchor.top },
      selector.leftOf to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.right <= anchor.left },
      selector.rightOf to { anchor: TrailblazeNode.Bounds, n: TrailblazeNode.Bounds -> n.left >= anchor.right },
    )
    for ((spatialSelector, predicate) in spatialChecks) {
      if (spatialSelector == null) continue
      val anchorBounds = resolveFirstBounds(root, spatialSelector, depth) ?: return false
      val nodeBounds = node.bounds ?: return false
      if (!predicate(anchorBounds, nodeBounds)) return false
    }

    // Hierarchy: containsChild — depth incremented to guard against nested containsChild chains
    selector.containsChild?.let { childSelector ->
      if (!node.children.any { child -> matchesSelector(child, childSelector, root, depth + 1) }) return false
    }

    // Hierarchy: containsDescendants — must match ALL, depth incremented
    selector.containsDescendants?.let { descendantSelectors ->
      val allDescendants = node.aggregate().drop(1) // exclude self
      val allMatch = descendantSelectors.all { descendantSelector ->
        allDescendants.any { desc -> matchesSelector(desc, descendantSelector, root, depth + 1) }
      }
      if (!allMatch) return false
    }

    return true
  }

  /** Resolves the first match's bounds from a selector. */
  private fun resolveFirstBounds(
    root: TrailblazeNode,
    selector: TrailblazeNodeSelector,
    depth: Int,
  ): TrailblazeNode.Bounds? = when (val result = resolve(root, selector, depth + 1)) {
    is ResolveResult.SingleMatch -> result.node.bounds
    is ResolveResult.MultipleMatches -> result.nodes.firstOrNull()?.bounds
    is ResolveResult.NoMatch -> null
  }

  // --- Driver-specific matching ---

  /** Dispatches to the appropriate driver-specific matcher. */
  private fun matchesDriverDetail(
    detail: DriverNodeDetail,
    match: DriverNodeMatch,
  ): Boolean = when (match) {
    is DriverNodeMatch.AndroidAccessibility ->
      detail is DriverNodeDetail.AndroidAccessibility && matchesAndroidAccessibility(detail, match)
    is DriverNodeMatch.AndroidMaestro ->
      detail is DriverNodeDetail.AndroidMaestro && matchesAndroidMaestro(detail, match)
    is DriverNodeMatch.Web ->
      detail is DriverNodeDetail.Web && matchesWeb(detail, match)
    is DriverNodeMatch.Compose ->
      detail is DriverNodeDetail.Compose && matchesCompose(detail, match)
    is DriverNodeMatch.IosMaestro ->
      detail is DriverNodeDetail.IosMaestro && matchesIosMaestro(detail, match)
    is DriverNodeMatch.IosAxe ->
      detail is DriverNodeDetail.IosAxe && matchesIosAxe(detail, match)
  }

  private fun matchesAndroidAccessibility(
    detail: DriverNodeDetail.AndroidAccessibility,
    match: DriverNodeMatch.AndroidAccessibility,
  ): Boolean {
    if (!requirePattern(match.classNameRegex, detail.className)) return false
    if (!requirePattern(match.resourceIdRegex, detail.resourceId)) return false
    if (!requireEqual(match.uniqueId, detail.uniqueId)) return false
    if (!requirePattern(match.composeTestTagRegex, detail.composeTestTag)) return false
    // textRegex matches resolveText() (text > hintText > contentDescription)
    if (!requirePattern(match.textRegex, detail.resolveText())) return false
    if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText)) return false
    if (!requirePattern(match.labeledByTextRegex, detail.labeledByText)) return false
    if (!requirePattern(match.stateDescriptionRegex, detail.stateDescription)) return false
    if (!requirePattern(match.paneTitleRegex, detail.paneTitle)) return false
    if (!requirePattern(match.roleDescriptionRegex, detail.roleDescription)) return false
    if (!requireEqual(match.isEnabled, detail.isEnabled)) return false
    if (!requireEqual(match.isClickable, detail.isClickable)) return false
    if (!requireEqual(match.isCheckable, detail.isCheckable)) return false
    if (!requireEqual(match.isChecked, detail.isChecked)) return false
    if (!requireEqual(match.isSelected, detail.isSelected)) return false
    if (!requireEqual(match.isFocused, detail.isFocused)) return false
    if (!requireEqual(match.isEditable, detail.isEditable)) return false
    if (!requireEqual(match.isScrollable, detail.isScrollable)) return false
    if (!requireEqual(match.isPassword, detail.isPassword)) return false
    if (!requireEqual(match.isHeading, detail.isHeading)) return false
    if (!requireEqual(match.isMultiLine, detail.isMultiLine)) return false
    if (!requireEqual(match.inputType, detail.inputType)) return false
    match.collectionItemRowIndex?.let { row ->
      if (detail.collectionItemInfo?.rowIndex != row) return false
    }
    match.collectionItemColumnIndex?.let { col ->
      if (detail.collectionItemInfo?.columnIndex != col) return false
    }
    return true
  }

  private fun matchesAndroidMaestro(
    detail: DriverNodeDetail.AndroidMaestro,
    match: DriverNodeMatch.AndroidMaestro,
  ): Boolean {
    // Maestro-shape selectors keep the semantics Maestro's Orchestra evaluated them with.
    val dialect = MatchDialect.MAESTRO
    if (!requirePattern(match.textRegex, detail.resolveText(), dialect)) return false
    if (!requirePattern(match.resourceIdRegex, detail.resourceId, dialect)) return false
    if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText, dialect)) return false
    if (!requirePattern(match.classNameRegex, detail.className, dialect)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText, dialect)) return false
    if (!requireEqual(match.clickable, detail.clickable)) return false
    if (!requireEqual(match.enabled, detail.enabled)) return false
    if (!requireEqual(match.focused, detail.focused)) return false
    if (!requireEqual(match.checked, detail.checked)) return false
    if (!requireEqual(match.selected, detail.selected)) return false
    return true
  }

  private fun matchesWeb(
    detail: DriverNodeDetail.Web,
    match: DriverNodeMatch.Web,
  ): Boolean {
    if (!requireEqual(match.ariaRole, detail.ariaRole)) return false
    if (!requirePattern(match.ariaNameRegex, detail.ariaName)) return false
    if (!requirePattern(match.ariaDescriptorRegex, detail.ariaDescriptor)) return false
    if (!requireEqual(match.headingLevel, detail.headingLevel)) return false
    if (!requireEqual(match.cssSelector, detail.cssSelector)) return false
    if (!requireEqual(match.dataTestId, detail.dataTestId)) return false
    if (!requireEqual(match.nthIndex, detail.nthIndex)) return false
    return true
  }

  private fun matchesCompose(
    detail: DriverNodeDetail.Compose,
    match: DriverNodeMatch.Compose,
  ): Boolean {
    if (!requireEqual(match.testTag, detail.testTag)) return false
    if (!requireEqual(match.role, detail.role)) return false
    if (!requirePattern(match.textRegex, detail.resolveText())) return false
    if (!requirePattern(match.editableTextRegex, detail.editableText)) return false
    if (!requirePattern(match.contentDescriptionRegex, detail.contentDescription)) return false
    if (!requireEqual(match.toggleableState, detail.toggleableState)) return false
    if (!requireEqual(match.isEnabled, detail.isEnabled)) return false
    if (!requireEqual(match.isFocused, detail.isFocused)) return false
    if (!requireEqual(match.isSelected, detail.isSelected)) return false
    if (!requireEqual(match.isPassword, detail.isPassword)) return false
    return true
  }

  private fun matchesIosMaestro(
    detail: DriverNodeDetail.IosMaestro,
    match: DriverNodeMatch.IosMaestro,
  ): Boolean {
    // Maestro-shape selectors keep the semantics Maestro's Orchestra evaluated them with.
    val dialect = MatchDialect.MAESTRO
    if (!requirePattern(match.textRegex, detail.resolveText(), dialect)) return false
    if (!requirePattern(match.resourceIdRegex, detail.resourceId, dialect)) return false
    if (!requirePattern(match.accessibilityTextRegex, detail.accessibilityText, dialect)) return false
    if (!requirePattern(match.classNameRegex, detail.className, dialect)) return false
    if (!requirePattern(match.hintTextRegex, detail.hintText, dialect)) return false
    if (!requireEqual(match.focused, detail.focused)) return false
    if (!requireEqual(match.selected, detail.selected)) return false
    return true
  }

  private fun matchesIosAxe(
    detail: DriverNodeDetail.IosAxe,
    match: DriverNodeMatch.IosAxe,
  ): Boolean {
    if (!requirePattern(match.roleRegex, detail.role)) return false
    if (!requirePattern(match.subroleRegex, detail.subrole)) return false
    if (!requirePattern(match.labelRegex, detail.label)) return false
    if (!requirePattern(match.valueRegex, detail.value)) return false
    if (!requireEqual(match.uniqueId, detail.uniqueId)) return false
    if (!requirePattern(match.typeRegex, detail.type)) return false
    if (!requirePattern(match.titleRegex, detail.title)) return false
    match.customAction?.let { needed ->
      if (needed !in detail.customActions) return false
    }
    if (!requireEqual(match.enabled, detail.enabled)) return false
    return true
  }

  // --- Match helpers ---

  /** Returns true if [expected] is null (no constraint) or equals [actual]. */
  private fun <T> requireEqual(expected: T?, actual: T): Boolean =
    expected == null || expected == actual

  /**
   * The matching semantics a selector shape carries. A selector means what it meant under the
   * driver dialect it was authored for, everywhere it is evaluated — so the Maestro-shape
   * branches ([DriverNodeMatch.AndroidMaestro], [DriverNodeMatch.IosMaestro]) keep the lenient
   * semantics Maestro's Orchestra compiled them with, while native shapes stay strict.
   *
   * Deliberately no runtime kill-switch: the MAESTRO dialect is strictly loosening (it can only
   * add matches, never remove one), a per-selector escape exists (leading `(?-i)` / `(?-s)`),
   * and this common code also targets Wasm where env vars don't exist. A match that succeeds
   * only via the lenient dialect is not separately logged; to debug a surprising match, re-test
   * the pattern with a `(?-i)` prefix.
   */
  private enum class MatchDialect {
    /** Strict: no implicit regex options; case-sensitive; `.` does not cross newlines. */
    NATIVE,

    /**
     * Maestro-compatible: `IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE` (Orchestra's
     * `REGEX_OPTIONS`), and an invalid pattern degrades to an escaped literal compiled with the
     * same options (Maestro's `toRegexSafe`) — i.e. a case-insensitive literal.
     */
    MAESTRO,
  }

  /**
   * Returns true if [pattern] is null (no constraint) or [text] matches it.
   * When pattern is set but text is null, the match fails (element lacks the property).
   */
  private fun requirePattern(pattern: String?, text: String?, dialect: MatchDialect = MatchDialect.NATIVE): Boolean {
    if (pattern == null) return true
    if (text == null) return false
    return matchesPattern(pattern, text, dialect)
  }

  /**
   * Matches a regex pattern against the full text, then falls back to literal string equality
   * when the pattern doesn't match as a regex. The fallback covers both an unmatchable-but-valid
   * pattern (e.g. "$3.00", where a bare `$` is an end-of-input anchor so nothing can follow it —
   * it compiles fine but can never regex-match) and a pattern that fails to compile at all. This
   * mirrors Maestro's `Filters.textMatches` (`regex.matches(value) || regex.pattern == value`),
   * so a natural-language value like a price matches identically on the Maestro and accessibility
   * drivers without hand-escaping the metacharacters.
   *
   * Uses full-string matching (not substring) to prevent false positives when element text
   * contains the pattern as a substring (e.g., pattern "ok" should not match "book").
   *
   * [MatchDialect.MAESTRO] additionally compiles with Orchestra's `REGEX_OPTIONS` and degrades an
   * invalid pattern to an escaped literal with the same options (`toRegexSafe`), so a Maestro-shape
   * selector matches here exactly as it did under Maestro. Case-sensitivity escape hatch inside a
   * Maestro-shape pattern: a leading `(?-i)`. [MatchDialect.NATIVE] is strict; opt into
   * case-insensitivity with a leading `(?i)`.
   *
   * The behavioral contract is locked by the shared cross-language fixture
   * `sdks/typescript/src/matcher/matcher-parity-fixtures.json`, consumed by both this
   * implementation's [MatcherParityFixturesTest] and the TS mirror's `matcher-parity.test.ts`.
   * Semantics changes must update the fixture and both implementations together.
   */
  private fun matchesPattern(pattern: String, text: String, dialect: MatchDialect = MatchDialect.NATIVE): Boolean {
    val options = when (dialect) {
      MatchDialect.NATIVE -> emptySet()
      MatchDialect.MAESTRO -> MAESTRO_REGEX_OPTIONS
    }
    val regex = try {
      Regex(pattern, options)
    } catch (_: IllegalArgumentException) {
      when (dialect) {
        MatchDialect.NATIVE -> null
        // Maestro's StringUtils.toRegexSafe: invalid regex → escaped literal, same options.
        MatchDialect.MAESTRO -> Regex(Regex.escape(pattern), options)
      }
    }
    if (regex != null && regex.matches(text)) return true
    return text == pattern
  }
}
