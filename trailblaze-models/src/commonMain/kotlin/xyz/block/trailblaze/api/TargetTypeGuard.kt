package xyz.block.trailblaze.api

/**
 * Detects a selector that resolved onto a **text input it never asked for**.
 *
 * A bare `textRegex` is matched case-insensitively against [DriverNodeDetail.resolveText], so a
 * search field currently holding "pizza" matches a selector looking for `Pizza` just as well as
 * the result row the author meant. Tapping it types nothing and asserts nothing, but both report
 * success — `assertVisibleBySelector: Pizza` passes off the search box with an empty cart.
 *
 * Pure functions over the resolve result, so this reports on `SingleMatch`, `MultipleMatches` and
 * assertions alike, without the resolver having to carry match provenance through its seven
 * `Boolean`-returning matchers (and without moving `ResolveResult` in the binary-compatibility
 * baseline to thread a warning string).
 */
object TargetTypeGuard {

  /**
   * Returns a warning when [node] is a text input and [selector] never asked for one, or null.
   *
   * "Never asked for one" means the selector pins nothing that would distinguish an input from a
   * label — no editability, class, resource id, test tag or password constraint — so the match
   * rests entirely on text that the input happens to be displaying.
   */
  fun assessUnrequestedTextInput(
    selector: TrailblazeNodeSelector,
    node: TrailblazeNode,
  ): String? {
    val detail = node.driverDetail ?: return null
    if (!isTextInput(detail)) return null
    if (selectorAskedForInput(selector)) return null
    val provenance = textMatchProvenance(detail)?.let { " via its `$it`" } ?: ""
    return "selector ${selector.description()} resolved to a text input$provenance " +
      "(${describe(detail)}). A bare text match can land on a field that is merely displaying " +
      "the text — e.g. a search box echoing the query — rather than the element it names."
  }

  /**
   * Returns a warning when an ambiguous match set mixes a text input with non-input candidates,
   * or null.
   *
   * This is the shape the single-match check cannot see: when a bare-text selector matches both
   * a search field and the result row beneath it, the resolver reports `MultipleMatches` and the
   * choice of which one gets tapped is made downstream — so the warning has to be raised over the
   * match set, not over one resolved node.
   */
  fun assessAmbiguousTextInput(
    selector: TrailblazeNodeSelector,
    nodes: List<TrailblazeNode>,
  ): String? {
    if (selectorAskedForInput(selector)) return null
    val details = nodes.mapNotNull { it.driverDetail }
    val input = details.firstOrNull { isTextInput(it) } ?: return null
    if (details.none { !isTextInput(it) }) return null
    return "selector ${selector.description()} is ambiguous across ${nodes.size} matches, one of " +
      "which is a text input (${describe(input)}). Whichever is chosen, a bare text match that " +
      "also hits an input is usually matching text the input is displaying."
  }

  /**
   * Names the field a `textRegex` match came from, mirroring each driver's
   * [DriverNodeDetail.resolveText] chain **exactly**.
   *
   * The chains are not uniform and a "first non-null field" shortcut would mislabel two of them:
   * [DriverNodeDetail.IosAxe] skips blank values (so an empty `label` falls through to `value`)
   * while the others plain null-coalesce (so an empty `text` wins its chain), and
   * [DriverNodeDetail.Compose] puts `editableText` **first** — meaning on Compose a bare
   * `textRegex` matches an editable field's current contents ahead of every other field, which is
   * this whole check's failure shape arriving by construction.
   */
  fun textMatchProvenance(detail: DriverNodeDetail): String? = when (detail) {
    is DriverNodeDetail.AndroidAccessibility -> firstNonNull(
      "text" to detail.text,
      "hintText" to detail.hintText,
      "contentDescription" to detail.contentDescription,
    )
    is DriverNodeDetail.AndroidMaestro -> firstNonNull(
      "text" to detail.text,
      "hintText" to detail.hintText,
      "accessibilityText" to detail.accessibilityText,
    )
    is DriverNodeDetail.IosMaestro -> firstNonNull(
      "text" to detail.text,
      "hintText" to detail.hintText,
      "accessibilityText" to detail.accessibilityText,
    )
    is DriverNodeDetail.Compose -> firstNonNull(
      "editableText" to detail.editableText,
      "text" to detail.text,
      "contentDescription" to detail.contentDescription,
    )
    // The only blank-skipping chain: `label?.takeIf { it.isNotBlank() } ?: value?…  ?: title?…`
    is DriverNodeDetail.IosAxe -> firstNonBlank(
      "label" to detail.label,
      "value" to detail.value,
      "title" to detail.title,
    )
    else -> null
  }

  /**
   * Whether the node is a text input.
   *
   * Only the drivers this check has been rolled out to answer; the rest return false so they stay
   * silent rather than guessing. Compose is next in line precisely because `editableText` leads
   * its text-resolution chain (see [textMatchProvenance]).
   */
  private fun isTextInput(detail: DriverNodeDetail): Boolean = when (detail) {
    is DriverNodeDetail.AndroidAccessibility -> detail.isEditable
    is DriverNodeDetail.Compose -> detail.editableText != null
    else -> false
  }

  private fun selectorAskedForInput(selector: TrailblazeNodeSelector): Boolean {
    selector.androidAccessibility?.let { m ->
      if (m.isEditable != null || m.isPassword != null || m.classNameRegex != null ||
        m.resourceIdRegex != null || m.uniqueId != null || m.composeTestTagRegex != null
      ) {
        return true
      }
    }
    selector.compose?.let { m ->
      if (m.editableTextRegex != null || m.isPassword != null || m.testTag != null ||
        m.role != null
      ) {
        return true
      }
    }
    return false
  }

  private fun describe(detail: DriverNodeDetail): String = when (detail) {
    is DriverNodeDetail.AndroidAccessibility ->
      "className=${detail.className}, text=${detail.text}"
    is DriverNodeDetail.Compose ->
      "testTag=${detail.testTag}, editableText=${detail.editableText}"
    else -> detail.toString()
  }

  private fun firstNonNull(vararg candidates: Pair<String, String?>): String? =
    candidates.firstOrNull { it.second != null }?.first

  private fun firstNonBlank(vararg candidates: Pair<String, String?>): String? =
    candidates.firstOrNull { !it.second.isNullOrBlank() }?.first
}
