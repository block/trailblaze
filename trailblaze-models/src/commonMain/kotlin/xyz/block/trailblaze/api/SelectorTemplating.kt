package xyz.block.trailblaze.api

/**
 * Per-session target context for selector template expansion.
 *
 * Carries the data a [TrailblazeNodeSelector] needs to expand `{{target.appId}}` placeholders
 * in its `*Regex` fields. Kept deliberately minimal (just the two strings the runtime can
 * resolve from a session's target) so the model layer doesn't take on a wider dependency.
 *
 * @property appId The single device-resolved app id when the framework picked one at session
 *   start. Null in log-replay contexts and when no declared candidate is installed.
 * @property appIds The full declared candidate list from the pack manifest. Used as the
 *   fallback when [appId] is null — the matcher substitutes a `(?:id1|id2|…)` alternation
 *   so a captured tree still matches any declared variant.
 */
data class TargetTemplateContext(
  val appId: String?,
  val appIds: List<String> = emptyList(),
)

/**
 * Selector regex template engine.
 *
 * ## Expansion contract — single chokepoint at the resolver
 *
 * [TrailblazeNodeSelectorResolver.resolve] accepts a `target: TargetTemplateContext?`
 * parameter and pre-expands the selector once at the entry. Callers that hold session
 * context (agents, matchers, executors) thread it through; callers without context
 * (inspector UI, ad-hoc literal selectors, unit-test fixtures) pass null. Every
 * production resolver site participates:
 *
 *  - [xyz.block.trailblaze.waypoint.WaypointMatcher.match] — accepts an optional
 *    `target: TargetTemplateContext?` and forwards to the resolver.
 *  - [xyz.block.trailblaze.toolcalls.commands.TapOnTrailblazeTool] — builds the context
 *    from `toolExecutionContext.{resolvedTarget, appId}` and forwards.
 *  - `AccessibilityDeviceManager`, `HostMaestroTrailblazeAgent`, `AxeDeviceManager` —
 *    each take a `templateContext` constructor param and forward on every resolve.
 *
 * Inspector UI (`InspectTrailblazeNodeSelectorHelper`, `WaypointExamplePanel`) and other
 * inspection paths pass null — placeholders render as the literal `{{target.appId}}` for
 * display, which is the right shape for a "what does this selector say" view.
 *
 * The wire-side YAML form expresses cross-variant app id matching as
 * `^{{target.appId}}:id/foo$`. At match time, this expands the placeholder against the
 * current session's [TargetTemplateContext] so the compiled regex sees a concrete value:
 *
 *  1. If [TargetTemplateContext.appId] is non-null, substitute its [Regex.escape]d form —
 *     the runtime resolved a specific install, so the matcher requires a literal hit on it.
 *  2. Else if [TargetTemplateContext.appIds] is non-empty, substitute
 *     `(?:appId1|appId2|…)` with each entry [Regex.escape]d. This is the log-replay safety
 *     net: a captured tree could have come from any declared variant and the matcher should
 *     still hit.
 *  3. If neither is supplied (no target context at all), leave the literal `{{target.appId}}`
 *     un-substituted. The downstream regex compile won't match anything meaningful, which
 *     surfaces the missing-context bug rather than producing a silent zero-match.
 *
 * Substitution happens at match time (not YAML load) because the target is per-session.
 */
object SelectorTemplating {

  /** The literal placeholder string authors write in YAML regex fields. */
  const val APP_ID_PLACEHOLDER: String = "{{target.appId}}"

  /**
   * Expands `{{target.appId}}` in [pattern] using [target] per the rules above. Returns
   * [pattern] unchanged when it contains no placeholder.
   */
  fun expand(pattern: String, target: TargetTemplateContext?): String {
    if (!pattern.contains(APP_ID_PLACEHOLDER)) return pattern
    val replacement = when {
      target?.appId != null -> Regex.escape(target.appId)
      !target?.appIds.isNullOrEmpty() ->
        target.appIds.joinToString(separator = "|", prefix = "(?:", postfix = ")") { Regex.escape(it) }
      else -> APP_ID_PLACEHOLDER
    }
    return pattern.replace(APP_ID_PLACEHOLDER, replacement)
  }

  /**
   * Returns a copy of [selector] with [APP_ID_PLACEHOLDER] expanded in every `*Regex` field,
   * recursing through spatial/hierarchy sub-selectors so deeply-nested selectors get the
   * same treatment. Selectors that don't contain the placeholder anywhere are returned
   * untouched so the matcher avoids unnecessary copies.
   */
  fun expand(selector: TrailblazeNodeSelector, target: TargetTemplateContext?): TrailblazeNodeSelector {
    if (target == null) return selector
    return selector.copy(
      androidAccessibility = selector.androidAccessibility?.expanded(target),
      androidMaestro = selector.androidMaestro?.expanded(target),
      web = selector.web?.expanded(target),
      compose = selector.compose?.expanded(target),
      iosMaestro = selector.iosMaestro?.expanded(target),
      iosAxe = selector.iosAxe?.expanded(target),
      below = selector.below?.let { expand(it, target) },
      above = selector.above?.let { expand(it, target) },
      leftOf = selector.leftOf?.let { expand(it, target) },
      rightOf = selector.rightOf?.let { expand(it, target) },
      childOf = selector.childOf?.let { expand(it, target) },
      containsChild = selector.containsChild?.let { expand(it, target) },
      containsDescendants = selector.containsDescendants?.map { expand(it, target) },
    )
  }

  /**
   * Returns true if [selector] — or any of its nested spatial / hierarchy / driver-match
   * sub-fields — still contains [APP_ID_PLACEHOLDER] verbatim. Used by the matcher to
   * fail closed: if the placeholder survived the expansion pass (because the caller didn't
   * supply a [TargetTemplateContext], or supplied one with no `appId`/`appIds`), evaluating
   * the selector against the tree would silently misbehave for `forbidden` entries — the
   * literal `{{target.appId}}` can't match any real resourceId, so the forbidden check
   * passes and the waypoint could be reported as matched. Detecting the literal after
   * expansion lets the matcher short-circuit to a skip instead.
   */
  fun containsUnresolvedPlaceholder(selector: TrailblazeNodeSelector): Boolean {
    if (selector.androidAccessibility?.hasPlaceholder() == true) return true
    if (selector.androidMaestro?.hasPlaceholder() == true) return true
    if (selector.web?.hasPlaceholder() == true) return true
    if (selector.compose?.hasPlaceholder() == true) return true
    if (selector.iosMaestro?.hasPlaceholder() == true) return true
    if (selector.iosAxe?.hasPlaceholder() == true) return true
    if (selector.below?.let(::containsUnresolvedPlaceholder) == true) return true
    if (selector.above?.let(::containsUnresolvedPlaceholder) == true) return true
    if (selector.leftOf?.let(::containsUnresolvedPlaceholder) == true) return true
    if (selector.rightOf?.let(::containsUnresolvedPlaceholder) == true) return true
    if (selector.childOf?.let(::containsUnresolvedPlaceholder) == true) return true
    if (selector.containsChild?.let(::containsUnresolvedPlaceholder) == true) return true
    if (selector.containsDescendants?.any(::containsUnresolvedPlaceholder) == true) return true
    return false
  }

  private fun String?.containsPlaceholder(): Boolean = this?.contains(APP_ID_PLACEHOLDER) == true

  private fun DriverNodeMatch.AndroidAccessibility.hasPlaceholder(): Boolean =
    classNameRegex.containsPlaceholder() || resourceIdRegex.containsPlaceholder() ||
      composeTestTagRegex.containsPlaceholder() || textRegex.containsPlaceholder() ||
      contentDescriptionRegex.containsPlaceholder() || hintTextRegex.containsPlaceholder() ||
      labeledByTextRegex.containsPlaceholder() || stateDescriptionRegex.containsPlaceholder() ||
      paneTitleRegex.containsPlaceholder() || roleDescriptionRegex.containsPlaceholder()

  private fun DriverNodeMatch.AndroidMaestro.hasPlaceholder(): Boolean =
    textRegex.containsPlaceholder() || resourceIdRegex.containsPlaceholder() ||
      accessibilityTextRegex.containsPlaceholder() || classNameRegex.containsPlaceholder() ||
      hintTextRegex.containsPlaceholder()

  private fun DriverNodeMatch.Web.hasPlaceholder(): Boolean =
    ariaNameRegex.containsPlaceholder() || ariaDescriptorRegex.containsPlaceholder()

  private fun DriverNodeMatch.Compose.hasPlaceholder(): Boolean =
    textRegex.containsPlaceholder() || editableTextRegex.containsPlaceholder() ||
      contentDescriptionRegex.containsPlaceholder()

  private fun DriverNodeMatch.IosMaestro.hasPlaceholder(): Boolean =
    textRegex.containsPlaceholder() || resourceIdRegex.containsPlaceholder() ||
      accessibilityTextRegex.containsPlaceholder() || classNameRegex.containsPlaceholder() ||
      hintTextRegex.containsPlaceholder()

  private fun DriverNodeMatch.IosAxe.hasPlaceholder(): Boolean =
    roleRegex.containsPlaceholder() || subroleRegex.containsPlaceholder() ||
      labelRegex.containsPlaceholder() || valueRegex.containsPlaceholder() ||
      typeRegex.containsPlaceholder() || titleRegex.containsPlaceholder()

  private fun String?.expanded(target: TargetTemplateContext): String? =
    this?.let { expand(it, target) }

  private fun DriverNodeMatch.AndroidAccessibility.expanded(target: TargetTemplateContext) = copy(
    classNameRegex = classNameRegex.expanded(target),
    resourceIdRegex = resourceIdRegex.expanded(target),
    composeTestTagRegex = composeTestTagRegex.expanded(target),
    textRegex = textRegex.expanded(target),
    contentDescriptionRegex = contentDescriptionRegex.expanded(target),
    hintTextRegex = hintTextRegex.expanded(target),
    labeledByTextRegex = labeledByTextRegex.expanded(target),
    stateDescriptionRegex = stateDescriptionRegex.expanded(target),
    paneTitleRegex = paneTitleRegex.expanded(target),
    roleDescriptionRegex = roleDescriptionRegex.expanded(target),
  )

  private fun DriverNodeMatch.AndroidMaestro.expanded(target: TargetTemplateContext) = copy(
    textRegex = textRegex.expanded(target),
    resourceIdRegex = resourceIdRegex.expanded(target),
    accessibilityTextRegex = accessibilityTextRegex.expanded(target),
    classNameRegex = classNameRegex.expanded(target),
    hintTextRegex = hintTextRegex.expanded(target),
  )

  private fun DriverNodeMatch.Web.expanded(target: TargetTemplateContext) = copy(
    ariaNameRegex = ariaNameRegex.expanded(target),
    ariaDescriptorRegex = ariaDescriptorRegex.expanded(target),
  )

  private fun DriverNodeMatch.Compose.expanded(target: TargetTemplateContext) = copy(
    textRegex = textRegex.expanded(target),
    editableTextRegex = editableTextRegex.expanded(target),
    contentDescriptionRegex = contentDescriptionRegex.expanded(target),
  )

  private fun DriverNodeMatch.IosMaestro.expanded(target: TargetTemplateContext) = copy(
    textRegex = textRegex.expanded(target),
    resourceIdRegex = resourceIdRegex.expanded(target),
    accessibilityTextRegex = accessibilityTextRegex.expanded(target),
    classNameRegex = classNameRegex.expanded(target),
    hintTextRegex = hintTextRegex.expanded(target),
  )

  private fun DriverNodeMatch.IosAxe.expanded(target: TargetTemplateContext) = copy(
    roleRegex = roleRegex.expanded(target),
    subroleRegex = subroleRegex.expanded(target),
    labelRegex = labelRegex.expanded(target),
    valueRegex = valueRegex.expanded(target),
    typeRegex = typeRegex.expanded(target),
    titleRegex = titleRegex.expanded(target),
  )
}
