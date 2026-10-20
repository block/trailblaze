package xyz.block.trailblaze.api

import xyz.block.trailblaze.util.escapeForIdentifier
import xyz.block.trailblaze.util.escapeForSelector
import xyz.block.trailblaze.util.quoteAsRegexLiteral

/** Wraps a [DriverNodeMatch] in a [TrailblazeNodeSelector]. */
internal fun selectorWith(match: DriverNodeMatch): TrailblazeNodeSelector =
  TrailblazeNodeSelector.withMatch(match)

/**
 * Like [escapeForSelector] but tolerant of a volatile trailing count.
 *
 * Visible labels frequently carry a live count that changes between recording and replay
 * ("All tickets (307)", "Cart 3"). [escapeForSelector] pins the whole string literally, so the
 * selector fails to *resolve* on replay once the count moves (the resolver does a full-string
 * regex match — see [TrailblazeNodeSelectorResolver] `matchesPattern`). This anchors the stable
 * head and matches any count in its place:
 *  - count in parentheses: `"All tickets (307)"` -> `\QAll tickets\E\s*\(\d[\d.,]*\)`
 *  - trailing bare count:  `"Cart 3"`            -> `\QCart\E\s+\d[\d.,]*`
 *
 * Strings whose stable head is empty — pure numerics ("307") or currency/value-only labels
 * ("$3.00") — fall through to [escapeForSelector] unchanged, so value-pinned assertions keep
 * matching their exact text.
 *
 * Generalizing can only *widen* an anchor. Generators run this before [isUniqueMatch], so if the
 * widened anchor stops being unique the strategy cascade falls through to a more specific one.
 */
internal fun stableTextAnchorRegex(text: String): String {
  // quoteAsRegexLiteral (not Regex.escape): the quoted head lands in generated selector
  // text, which must be byte-identical on every platform that generates selectors.
  countInParensRegex.matchEntire(text)?.let { match ->
    val head = match.groupValues[1].trimEnd()
    if (head.isNotEmpty()) return quoteAsRegexLiteral(head) + """\s*\(\d[\d.,]*\)"""
  }
  trailingBareCountRegex.matchEntire(text)?.let { match ->
    val head = match.groupValues[1].trimEnd()
    if (head.isNotEmpty()) return quoteAsRegexLiteral(head) + """\s+\d[\d.,]*"""
  }
  return escapeForSelector(text)
}

// These two patterns are compiled by each platform's own regex engine but deliberately NOT
// routed through selectorPatternRegexMatches (they gate generation; they aren't recorded
// selector patterns), so they must avoid every construct whose semantics differ between
// java.util.regex and ECMAScript:
//  - `$`: Java matches before a final line terminator, ECMAScript only at absolute end —
//    `"Cancel 5\n"` anchored on the JVM but not in the compiled JS engine. Replaced by
//    unanchored patterns + matchEntire, which is strict whole-input on both. Strictness is
//    also correct for the JVM alone: the lenient `$` produced an anchor
//    (`\QCancel\E\s+\d[\d.,]*`) that can't full-match its own trailing-newline text, so the
//    resolver dropped the option anyway.
//  - `.`: Java excludes U+0085 (NEL), ECMAScript doesn't — spelled as an explicit
//    line-terminator-excluding class.
//  - `\s`: ECMAScript includes Unicode spaces (NBSP etc.), Java's default doesn't — spelled
//    as Java's exact default set.
// The `\s*`/`\s+` in the *emitted* anchor suffixes below are a separate concern: those are
// resolved at match time through selectorPatternRegexMatches, whose parity the matcher
// fixtures lock.

/** Java-`.` semantics on both platforms: any char except a line terminator. */
private const val NON_LINE_TERMINATOR = """[^\n\r\u0085\u2028\u2029]"""

/** Java-default-`\s` semantics on both platforms. */
private const val JAVA_WS = """[ \t\n\u000B\f\r]"""

/** "<head> (<count>)" — head, then a parenthesized run of digits/grouping separators. */
private val countInParensRegex = Regex("""($NON_LINE_TERMINATOR*?)$JAVA_WS*\(\d[\d.,]*\)""")

/** "<head> <count>" — head, then a trailing whitespace-separated run of digits/grouping. */
private val trailingBareCountRegex = Regex("""($NON_LINE_TERMINATOR*?)$JAVA_WS+\d[\d.,]*""")

/**
 * This selector with its own driver match also requiring exactly the text [detail] shows, or null
 * when the element shows none, is a password field, or this selector already matches a different
 * dialect. The text goes in the field it came from (the first non-blank of each dialect's text
 * fields), so the resolver reads it from the same place, and replaces any pattern already there:
 * the generator's patterns tolerate changing numbers, which an assertion must not.
 *
 * - An empty field showing its hint or label also requires the field to stay empty, or typing
 *   into it would still pass. That includes a field named only by its accessibility label.
 * - An AXe element pins both its label and its value: a text field's label is its name and its
 *   value is what was typed.
 * - Maestro-shape dialects match case-insensitively by default, so their patterns opt out with
 *   `(?-i)`: a recorded "Active" must not pass on "active".
 *
 * A password field returns null rather than pinning its hint: the typed value and the hint share
 * the field on several dialects, and a recording is a file anyone can read.
 */
internal fun TrailblazeNodeSelector.withTextOf(detail: DriverNodeDetail): TrailblazeNodeSelector? {
  val caseInsensitiveDialect = detail is DriverNodeDetail.AndroidMaestro || detail is DriverNodeDetail.IosMaestro
  fun exact(text: String): String =
    assertedTextPattern(text).let { if (caseInsensitiveDialect) "$CASE_SENSITIVE$it" else it }
  fun pattern(text: String?): String? = text?.takeIf { it.isNotBlank() }?.let(::exact)

  // A text field that is unset resolves to its hint, so the same hint pattern pins it; one that
  // is set (to "" or blank) must stay exactly that.
  fun shownHintText(text: String?, hintPattern: String): String = if (text == null) hintPattern else exact(text)
  // A field named only by its label that is set (to "" or blank) must stay exactly that too.
  fun shownText(text: String?, current: String?): String? = text?.let(::exact) ?: current
  // A Maestro `textRegex` resolves to the accessibility text when text and hint are unset, and the
  // legacy lowering ([TrailblazeNodeSelector.toTrailblazeElementSelector]) keeps only `textRegex`,
  // so one already there is replaced by the strict pattern rather than left loose beside it.
  fun loweredText(detail: DriverNodeDetail, current: String?, strict: String): String? {
    val (text, hint) = when (detail) {
      is DriverNodeDetail.AndroidMaestro -> detail.text to detail.hintText
      is DriverNodeDetail.IosMaestro -> detail.text to detail.hintText
      else -> return current
    }
    return if (current != null && text == null && hint == null) strict else current
  }
  return when (detail) {
    is DriverNodeDetail.AndroidAccessibility -> {
      if (detail.isPassword || (driverMatch != null && androidAccessibility == null)) return null
      val base = androidAccessibility ?: DriverNodeMatch.AndroidAccessibility()
      val match = pattern(detail.text)?.let { base.copy(textRegex = it) }
        ?: pattern(detail.hintText)?.let { base.copy(textRegex = shownHintText(detail.text, it), hintTextRegex = it) }
        ?: pattern(detail.contentDescription)?.let { base.copy(textRegex = shownText(detail.text, base.textRegex), contentDescriptionRegex = it) }
        ?: pattern(detail.labeledByText)?.let { base.copy(textRegex = shownText(detail.text, base.textRegex), labeledByTextRegex = it) }
        ?: return null
      copy(androidAccessibility = match)
    }
    is DriverNodeDetail.AndroidView -> {
      if (detail.isPassword || (driverMatch != null && androidView == null)) return null
      val base = androidView ?: DriverNodeMatch.AndroidView()
      val match = pattern(detail.text)?.let { base.copy(textRegex = it) }
        ?: pattern(detail.hintText)?.let { base.copy(textRegex = shownHintText(detail.text, it), hintTextRegex = it) }
        ?: pattern(detail.contentDescription)?.let { base.copy(textRegex = shownText(detail.text, base.textRegex), contentDescriptionRegex = it) }
        ?: return null
      copy(androidView = match)
    }
    is DriverNodeDetail.AndroidMaestro -> {
      if (detail.password || (driverMatch != null && androidMaestro == null)) return null
      val base = androidMaestro ?: DriverNodeMatch.AndroidMaestro()
      val match = pattern(detail.text)?.let { base.copy(textRegex = it) }
        ?: pattern(detail.hintText)?.let { base.copy(textRegex = shownHintText(detail.text, it), hintTextRegex = it) }
        ?: pattern(detail.accessibilityText)?.let { base.copy(textRegex = loweredText(detail, base.textRegex, it), accessibilityTextRegex = it) }
        ?: return null
      copy(androidMaestro = match)
    }
    is DriverNodeDetail.Compose -> {
      if (detail.isPassword || (driverMatch != null && compose == null)) return null
      val base = compose ?: DriverNodeMatch.Compose()
      // A Compose `textRegex` reads the typed text before the label, so an empty field can't pin
      // its label there; it pins that it stays empty, or typing into it would still pass.
      val match = pattern(detail.editableText)?.let { base.copy(editableTextRegex = it) }
        ?: detail.editableText?.let { base.copy(editableTextRegex = exact(it)) }
        ?: pattern(detail.text)?.let { base.copy(textRegex = it) }
        ?: pattern(detail.contentDescription)?.let { base.copy(contentDescriptionRegex = it) }
        ?: return null
      copy(compose = match)
    }
    is DriverNodeDetail.IosMaestro -> {
      if (detail.password || (driverMatch != null && iosMaestro == null)) return null
      val base = iosMaestro ?: DriverNodeMatch.IosMaestro()
      val match = pattern(detail.text)?.let { base.copy(textRegex = it) }
        ?: pattern(detail.hintText)?.let { base.copy(textRegex = shownHintText(detail.text, it), hintTextRegex = it) }
        ?: pattern(detail.accessibilityText)?.let { base.copy(textRegex = loweredText(detail, base.textRegex, it), accessibilityTextRegex = it) }
        ?: return null
      copy(iosMaestro = match)
    }
    is DriverNodeDetail.IosAxe -> {
      val secure = detail.role == AXE_SECURE_TEXT_FIELD || detail.subrole == AXE_SECURE_TEXT_FIELD
      if (secure || (driverMatch != null && iosAxe == null)) return null
      val base = iosAxe ?: DriverNodeMatch.IosAxe()
      val label = pattern(detail.label)
      // A labelled field whose value is empty must stay empty, or typing into it would still pass.
      val value = pattern(detail.value) ?: detail.value?.takeIf { label != null }?.let(::exact)
      val match = if (label != null || value != null) {
        base.copy(labelRegex = label ?: base.labelRegex, valueRegex = value ?: base.valueRegex)
      } else {
        pattern(detail.title)?.let { base.copy(titleRegex = it) } ?: return null
      }
      copy(iosAxe = match)
    }
    is DriverNodeDetail.Web -> {
      if (driverMatch != null && web == null) return null
      val base = web ?: DriverNodeMatch.Web()
      copy(web = base.copy(ariaNameRegex = pattern(detail.ariaName) ?: return null))
    }
  }
}

/** Leading inline flag that makes a Maestro-shape pattern case-sensitive. */
private const val CASE_SENSITIVE = "(?-i)"

private const val AXE_SECURE_TEXT_FIELD = "AXSecureTextField"

/**
 * [text] as a selector pattern that matches it exactly, except that a trailing item-count line
 * ("Review sale\n3 items") may change or disappear: that count is live cart state, not what the
 * assertion is about. A text that is only a count stays exact, so a trail can still pin one.
 */
internal fun assertedTextPattern(text: String): String {
  val count = trailingItemCountRegex.find(text)
  val head = count?.let { text.substring(0, it.range.first) }
  if (head == null || head.isBlank()) return escapeForSelector(text)
  return escapeForSelector(head) + OPTIONAL_ITEM_COUNT
}

private val trailingItemCountRegex = Regex("""\n\s*\d+\s+items?\s*$""", RegexOption.IGNORE_CASE)

private const val OPTIONAL_ITEM_COUNT = """(?:\n\s*\d+\s+(?i:items?)\s*)?"""

/**
 * Builds a map from each child node's id to its parent node, for the entire tree.
 *
 * Used by every hierarchy and spatial strategy to walk upward from a target — pre-computed
 * once per resolution so repeated walks don't each traverse the tree. Kept `internal` so
 * generator code, strategy factories, and tests share one implementation.
 */
internal fun buildParentMap(root: TrailblazeNode): Map<Long, TrailblazeNode> {
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

/** Checks that a selector resolves to exactly the target node. */
internal fun isUniqueMatch(
  root: TrailblazeNode,
  target: TrailblazeNode,
  selector: TrailblazeNodeSelector,
): Boolean {
  val result = TrailblazeNodeSelectorResolver.resolve(root, selector)
  return result is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch &&
    result.node.nodeId == target.nodeId
}

/**
 * Checks that a selector still *selects* the target — the target is among its
 * matches — without requiring the match to be unique.
 *
 * This is the presence predicate for waypoint selectors: a waypoint asserts a
 * screen signal is present (`minCount >= 1`), so a selector that matches the
 * target plus a few of its on-screen twins ("Add money" appearing twice) is
 * exactly right, where [isUniqueMatch] would reject it and push the cascade
 * toward a positional `index`.
 */
internal fun selectorMatchesTarget(
  root: TrailblazeNode,
  target: TrailblazeNode,
  selector: TrailblazeNodeSelector,
): Boolean = when (val result = TrailblazeNodeSelectorResolver.resolve(root, selector)) {
  is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node.nodeId == target.nodeId
  is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches ->
    result.nodes.any { it.nodeId == target.nodeId }
  is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> false
}

/**
 * Builds the most precise [DriverNodeMatch] for the target, using the specific text field
 * (contentDescription, hintText, text) rather than the lossy [resolveText] fallback.
 *
 * This feeds into hierarchy strategies (childOf, containsChild, collection, spatial) and
 * the index fallback, so precision here improves all composite strategies.
 */
internal fun buildTargetMatch(detail: DriverNodeDetail): DriverNodeMatch? = when (detail) {
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
        textRegex = text?.let { stableTextAnchorRegex(it) },
        contentDescriptionRegex = desc?.let { stableTextAnchorRegex(it) },
        hintTextRegex = hint?.let { stableTextAnchorRegex(it) },
        classNameRegex = className?.let { escapeForIdentifier(it) },
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.AndroidView -> {
    // Same precision rules as the accessibility branch: pick one text field rather than the
    // lossy resolveText() fallback, and never use an editable field's current content.
    val text = detail.text?.takeIf { it.isNotBlank() && !detail.isEditable }
    val desc = detail.contentDescription?.takeIf { it.isNotBlank() && text == null }
    val hint = detail.hintText?.takeIf { it.isNotBlank() && text == null && desc == null }
    val className = detail.className
    val rid = detail.resourceId
    val tag = detail.tag?.takeIf { it.isNotBlank() }
    if (text != null || desc != null || hint != null || className != null || rid != null || tag != null) {
      DriverNodeMatch.AndroidView(
        textRegex = text?.let { stableTextAnchorRegex(it) },
        contentDescriptionRegex = desc?.let { stableTextAnchorRegex(it) },
        hintTextRegex = hint?.let { stableTextAnchorRegex(it) },
        classNameRegex = className?.let { escapeForIdentifier(it) },
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
        tagRegex = tag?.let { escapeForIdentifier(it) },
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
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
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
    // Captures serialize `text` as "" on nodes labeled only via accessibilityText, and
    // resolveText()'s elvis stops at the blank — without this fallback such a node anchors
    // no text at all, so the composite strategies (childOf, spatial, index) that this match
    // feeds degrade to broad class/index shapes that can replay against the wrong node.
    val a11y = if (text == null) detail.accessibilityText?.takeIf { it.isNotBlank() } else null
    val rid = detail.resourceId
    if (text != null || a11y != null || rid != null) {
      DriverNodeMatch.IosMaestro(
        textRegex = text?.let { escapeForSelector(it) },
        accessibilityTextRegex = a11y?.let { escapeForSelector(it) },
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.IosAxe -> {
    // Prefer uniqueId (stable app-assigned identifier) when present. Otherwise fall back to
    // label/value, carrying role when we have it so generated selectors exploit Apple's
    // native AX vocabulary rather than the Maestro-shaped lowest-common-denominator.
    val uid = detail.uniqueId
    val label = detail.label?.takeIf { it.isNotBlank() }
    val value = detail.value?.takeIf { it.isNotBlank() }
    val title = detail.title?.takeIf { it.isNotBlank() }
    val role = detail.role
    if (uid != null || label != null || value != null || title != null || role != null) {
      DriverNodeMatch.IosAxe(
        uniqueId = uid,
        labelRegex = label?.let { escapeForSelector(it) },
        valueRegex = if (label == null) value?.let { escapeForSelector(it) } else null,
        titleRegex = if (label == null && value == null) title?.let { escapeForSelector(it) } else null,
        roleRegex = role?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
}

/**
 * Builds a structural-only [DriverNodeMatch] for a node, using only type, IDs, and state
 * flags — never text, contentDescription, hintText, or other content properties.
 */
internal fun buildStructuralMatch(detail: DriverNodeDetail): DriverNodeMatch? = when (detail) {
  is DriverNodeDetail.AndroidAccessibility -> {
    val className = detail.className
    val rid = detail.resourceId
    val uid = detail.uniqueId
    if (className != null || rid != null || uid != null) {
      DriverNodeMatch.AndroidAccessibility(
        classNameRegex = className?.let { escapeForIdentifier(it) },
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
        uniqueId = uid,
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.AndroidView -> {
    val rid = detail.resourceId
    val tag = detail.tag?.takeIf { it.isNotBlank() }
    val className = detail.className
    if (rid != null || tag != null || className != null) {
      DriverNodeMatch.AndroidView(
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
        tagRegex = tag?.let { escapeForIdentifier(it) },
        classNameRegex = className?.let { escapeForIdentifier(it) },
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
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
        classNameRegex = className?.let { escapeForIdentifier(it) },
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
        resourceIdRegex = rid?.let { escapeForIdentifier(it) },
        classNameRegex = className?.let { escapeForIdentifier(it) },
      )
    } else {
      null
    }
  }
  is DriverNodeDetail.IosAxe -> {
    // Structural-only match: identity + type/role, no content. Mirrors the AndroidAccessibility
    // / IosMaestro shape of "class + uniqueId" but uses AX-native `type` and `role` instead.
    val uid = detail.uniqueId
    val type = detail.type
    val role = detail.role
    if (uid != null || type != null || role != null) {
      DriverNodeMatch.IosAxe(
        uniqueId = uid,
        typeRegex = type?.let { escapeForIdentifier(it) },
        roleRegex = role?.let { escapeForSelector(it) },
      )
    } else {
      null
    }
  }
}

/** Builds a spatial selector given target match, bounds, and an anchor. */
internal fun buildSpatialSelector(
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

/**
 * Walks up the tree to find the nearest ancestor that can be uniquely identified.
 * Returns a selector for that parent, or null if no unique parent is found.
 */
internal fun findUniqueParentSelector(
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
internal fun findUniqueChildSelector(
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
internal fun findSpatialSelector(
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
internal fun computeIndexSelectorForMatch(
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
 * Like [findUniqueParentSelector] but uses structural matches only for the parent.
 */
internal fun findUniqueStructuralParentSelector(
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
internal fun findContentParentSelectorForStructural(
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
internal fun computeScopedIndexSelector(
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
internal fun findStructuralSpatialSelector(
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
internal fun findContentAnchoredSpatialSelector(
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
internal fun findStructuralContainsChildSelector(
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

// ---------------------------------------------------------------------------
// Shared strategy factories
// ---------------------------------------------------------------------------
//
// Each generator (AndroidAccessibility, AndroidMaestro, Compose, IosMaestro, Web) enumerates
// a list of `Pair<String, () -> TrailblazeNodeSelector?>` strategies, written as
// `"name" to { /* returns TrailblazeNodeSelector? */ }`. The content-field strategies at
// the top of each list are necessarily bespoke — they read driver-specific properties.
// But the trailing hierarchy/spatial/index strategies have the same structure across every
// generator: wrap a shared finder in a named lambda.
//
// The factories below collapse those pattern-identical strategies into one place, each
// taking an optional `name` so generators that use a different label (e.g. "Structural:
// global index fallback" vs. "Structural: class + index") can still share the body.
// Per-generator quirks (e.g. AndroidMaestro's inline-text "Child of parent") stay bespoke
// in the generator file — not every strategy generalizes cleanly.

/**
 * Target match + `childOf` a uniquely-identifiable parent.
 * Uses [buildTargetMatch] for the target — the precise per-driver field selection.
 */
internal fun childOfUniqueParentStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail,
  parentMap: Map<Long, TrailblazeNode>,
  name: String = "Child of parent",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findUniqueParentSelector(root, target, parentMap)?.let { parentSelector ->
    TrailblazeNodeSelector.withMatch(buildTargetMatch(detail), childOf = parentSelector)
  }
}

/** Target match + `containsChild` a uniquely-identifiable descendant. */
internal fun containsUniqueChildStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail,
  name: String = "Contains child",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findUniqueChildSelector(root, target)?.let { childSelector ->
    TrailblazeNodeSelector.withMatch(buildTargetMatch(detail), containsChild = childSelector)
  }
}

/** Target + positional relationship to a uniquely-identifiable neighbor. */
internal fun spatialStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
  name: String = "Spatial relationship",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findSpatialSelector(root, target, parentMap)
}

/** Target match + global index. Last resort — always produces something. */
internal fun indexFallbackStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail,
  name: String = "Index fallback",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  computeIndexSelectorForMatch(root, target, buildTargetMatch(detail))
}

// --- Structural variants (identity + type only, no content) ---

/** Structural match + `childOf` a structurally-identifiable parent. */
internal fun structuralChildOfParentStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail,
  parentMap: Map<Long, TrailblazeNode>,
  name: String = "Structural: child of parent",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findUniqueStructuralParentSelector(root, target, parentMap)?.let { parentSelector ->
    TrailblazeNodeSelector.withMatch(buildStructuralMatch(detail), childOf = parentSelector)
  }
}

/** Structural match + `childOf` a content-labeled parent (hybrid anchor). */
internal fun structuralChildOfLabeledParentStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail,
  parentMap: Map<Long, TrailblazeNode>,
  name: String = "Structural: child of labeled parent",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findContentParentSelectorForStructural(root, target, parentMap)?.let { parentSelector ->
    TrailblazeNodeSelector.withMatch(buildStructuralMatch(detail), childOf = parentSelector)
  }
}

/** Structural match identifies the target by a unique structural descendant. */
internal fun structuralContainsChildStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  name: String = "Structural: contains child",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findStructuralContainsChildSelector(root, target)
}

/** Structural match + positional relationship to a structurally-identifiable sibling. */
internal fun structuralSpatialStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
  name: String = "Structural: spatial",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findStructuralSpatialSelector(root, target, parentMap)
}

/** Structural match + spatial anchor based on a content-labeled sibling. */
internal fun structuralContentAnchoredSpatialStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  parentMap: Map<Long, TrailblazeNode>,
  name: String = "Structural: spatial (labeled anchor)",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  findContentAnchoredSpatialSelector(root, target, parentMap)
}

/** Structural match + scoped index within the nearest identifiable parent. */
internal fun structuralScopedIndexStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail,
  parentMap: Map<Long, TrailblazeNode>,
  name: String = "Structural: scoped index in parent",
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  computeScopedIndexSelector(root, target, parentMap, buildStructuralMatch(detail))
}

/**
 * Structural match + global index. Generators name this after their primary type
 * (e.g. "Structural: class + index", "Structural: role + index"), so [name] is required.
 */
internal fun structuralIndexFallbackStrategy(
  root: TrailblazeNode,
  target: TrailblazeNode,
  detail: DriverNodeDetail,
  name: String,
): Pair<String, () -> TrailblazeNodeSelector?> = name to {
  computeIndexSelectorForMatch(root, target, buildStructuralMatch(detail))
}
