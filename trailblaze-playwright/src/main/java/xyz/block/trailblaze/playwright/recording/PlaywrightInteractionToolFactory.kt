package xyz.block.trailblaze.playwright.recording

import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorGenerator
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeClickTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativePressKeyTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeScrollTool
import xyz.block.trailblaze.playwright.tools.PlaywrightNativeTypeTool
import xyz.block.trailblaze.recording.InteractionToolFactory
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import kotlin.math.abs

/**
 * Creates Playwright-native tool instances (web_click, web_type, etc.) from user input
 * events during interactive recording.
 *
 * Tap resolution goes through the live DOM via [PlaywrightDeviceScreenStream.resolveClickTargetAt],
 * which runs `document.elementFromPoint(x, y)` — the same path Playwright's own
 * `mouse().click(x, y)` resolves against. Replacing this with a `nodeSelector`-bearing path
 * via `PlaywrightTrailblazeNodeMapper` is the next chunk of follow-up work.
 */
class PlaywrightInteractionToolFactory(
  private val stream: PlaywrightDeviceScreenStream,
) : InteractionToolFactory {

  companion object {
    /** Tag names whose `name` attribute is a stable form-control identifier. */
    private val FORM_CONTROL_TAGS: Set<String> = setOf("input", "select", "textarea", "button")

    /** Regex metacharacters that need escaping in [escapeNameForSelector]. */
    private val REGEX_METACHARACTERS = setOf(
      '\\', '^', '$', '.', '|', '?', '*', '+', '(', ')', '[', ']', '{', '}',
    )
  }

  override fun createTapTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode?,
  ): Pair<TrailblazeTool, String> {
    // Selector resolution is **DOM-first**, not tree-first. `document.elementFromPoint`
    // is what `page.mouse().click(x, y)` itself dispatches against, so reading identifiers
    // off the live DOM ancestor chain matches the playback target exactly. The bounds-
    // matching `TrailblazeNode` tree (built by `PlaywrightTrailblazeNodeMapper.mapWithBounds`)
    // is too lossy for hit-testing in practice — interactive descendants whose accessible
    // name diverges from the ARIA snapshot's role+name end up without bounds, and the
    // tree's `hitTest` falls through to landmark containers (`<main>`, `<document>`).
    // That produced the broken `nodeSelector: ariaDescriptorRegex: main, childOf:
    // ariaDescriptorRegex: document` recordings — they were technically "round-trip valid"
    // because the landmark containers DO contain the click point, just useless.
    //
    // We walk the live DOM ancestor chain via `stream.resolveClickCandidatesAt`, score
    // each ancestor's identifiers, and pick the strongest as the primary `nodeSelector`.
    // The remaining candidates flow through `findSelectorCandidates` as picker
    // alternatives, so the user can always swap to a different ancestor / strategy.
    val candidates = stream.resolveClickCandidatesAt(x, y)
    val primary = pickBestSelector(candidates, x, y)
      ?: TrailblazeNodeSelector(web = DriverNodeMatch.Web(cssSelector = "html"))
    val tool = PlaywrightNativeClickTool(ref = null, nodeSelector = primary)
    return tool to "web_click"
  }

  override fun createLongPressTool(
    node: ViewHierarchyTreeNode?,
    x: Int,
    y: Int,
    trailblazeNodeTree: TrailblazeNode?,
  ): Pair<TrailblazeTool, String> {
    // Playwright doesn't have a native long-press tool, use click as fallback
    return createTapTool(node, x, y, trailblazeNodeTree)
  }

  /**
   * Returns up to 5 selector alternatives the user can swap to via the action-card Tune
   * dropdown. Each candidate is built from one element in the live-DOM ancestor chain
   * (deepest = clicked element, ascending = ancestors), so the picker shows a coherent
   * "this exact element / its parent / its grandparent / …" progression — every one is
   * a real DOM ancestor of the click point, never a fabricated landmark.
   *
   * Sorted by the same score `pickBestSelector` uses, so the picker's "best" entry
   * matches what `createTapTool` emitted as primary. Empty when no DOM element produced
   * a usable identifier (extremely rare — even the click target's tag is a fallback
   * candidate).
   */
  override fun findSelectorCandidates(
    trailblazeNodeTree: TrailblazeNode?,
    x: Int,
    y: Int,
  ): List<TrailblazeNodeSelectorGenerator.NamedSelector> {
    val candidates = stream.resolveClickCandidatesAt(x, y)
    val scored = candidates.mapNotNull { c ->
      candidateToSelector(c)?.let { (selector, strategy) ->
        Triple(c, selector, strategy)
      }
    }.sortedByDescending { (c, _, _) -> candidateScore(c) }
    if (scored.isEmpty()) return emptyList()
    // Round-trip-verify each candidate against the live page so the picker only ever
    // surfaces selectors that ACTUALLY resolve to the click target. Mirrors mobile's
    // `roundTripValid`: a selector that doesn't hit-test back is worse than no
    // selector — it sets the user up for a confusing replay failure later.
    val verified = scored.filter { (_, selector, _) -> stream.validateSelectorAt(selector, x, y) }
    if (verified.isEmpty()) return emptyList()
    return verified.take(5).mapIndexed { i, (_, selector, strategy) ->
      TrailblazeNodeSelectorGenerator.NamedSelector(
        selector = selector,
        strategy = strategy,
        isBest = i == 0,
      )
    }
  }

  /**
   * Picks the strongest identifier across the ancestor chain and returns it as a
   * `TrailblazeNodeSelector`. Score weights the identifier type (data-testid > id > form
   * name > aria-label > role+name > tag) and lightly penalizes depth so the actual click
   * target wins over an ancestor when their identifier strengths are close.
   *
   * Each candidate is round-trip-verified against the live page before being considered:
   * a selector that doesn't actually hit-test back to (x, y) is silently skipped. The
   * verification call is cheap (~5–20ms per candidate) and saves the recording from
   * embedding a selector that would fail at replay. Falls back to the highest-scored
   * candidate (verified or not) when nothing passes verification, so the recorder
   * always emits SOMETHING — better an imperfect selector the user can swap than an
   * empty `cssSelector="html"` fallback.
   */
  private fun pickBestSelector(
    candidates: List<PlaywrightDeviceScreenStream.ClickCandidate>,
    x: Int,
    y: Int,
  ): TrailblazeNodeSelector? {
    val withSelectors = candidates.mapNotNull { c ->
      candidateToSelector(c)?.let { (sel, _) -> c to sel }
    }
    if (withSelectors.isEmpty()) return null
    val sorted = withSelectors.sortedByDescending { (c, _) -> candidateScore(c) }
    val verified = sorted.firstOrNull { (_, sel) -> stream.validateSelectorAt(sel, x, y) }
    return verified?.second ?: sorted.first().second
  }

  /**
   * Builds a `(TrailblazeNodeSelector, strategyName)` pair from a single click candidate
   * by picking the strongest identifier the candidate carries. The strategy name
   * surfaces in the Tune dropdown's row label so the user can see WHY a candidate was
   * suggested ("data-testid", "form name", "ARIA role + text", etc.).
   *
   * Returns null when the candidate has no usable identifier — those are filtered out so
   * the picker doesn't show empty rows. A candidate that's interactive but otherwise
   * unidentified still produces a `tag:has-text(…)` selector so the picker has at least
   * one row per click target.
   */
  private fun candidateToSelector(
    c: PlaywrightDeviceScreenStream.ClickCandidate,
  ): Pair<TrailblazeNodeSelector, String>? {
    val isFormControl = c.tag in FORM_CONTROL_TAGS
    val usableId = c.id.isNotBlank() && !c.id.first().isDigit()
    return when {
      c.dataTestId.isNotBlank() ->
        TrailblazeNodeSelector(
          web = DriverNodeMatch.Web(dataTestId = c.dataTestId),
        ) to "data-testid"
      usableId ->
        TrailblazeNodeSelector(
          web = DriverNodeMatch.Web(cssSelector = "[id=\"${cssEscape(c.id)}\"]"),
        ) to "id"
      isFormControl && c.nameAttr.isNotBlank() ->
        TrailblazeNodeSelector(
          web = DriverNodeMatch.Web(
            cssSelector = "${c.tag}[name=\"${cssEscape(c.nameAttr)}\"]",
          ),
        ) to "form name"
      c.ariaLabel.isNotBlank() ->
        TrailblazeNodeSelector(
          web = DriverNodeMatch.Web(
            cssSelector = "[aria-label=\"${cssEscape(c.ariaLabel)}\"]",
          ),
        ) to "aria-label"
      c.role.isNotBlank() && c.name.isNotBlank() ->
        TrailblazeNodeSelector(
          web = DriverNodeMatch.Web(
            ariaRole = c.role,
            ariaNameRegex = escapeNameForSelector(c.name),
          ),
        ) to "ARIA role + name"
      c.interactive && c.name.isNotBlank() ->
        // Last-resort identifier on an interactive element with text but no role — use
        // Playwright's `:has-text(…)` so the recording still resolves to *something*
        // semantically anchored to the visible label.
        TrailblazeNodeSelector(
          web = DriverNodeMatch.Web(
            cssSelector = "${c.tag}:has-text(\"${cssEscape(c.name)}\")",
          ),
        ) to "tag + text"
      else -> null
    }
  }

  /**
   * Scores a candidate so [pickBestSelector] and [findSelectorCandidates] agree on the
   * "best" pick. Scoring axes:
   *   - **Identifier strength** (dominant): data-testid (1000) → unique id (900) → form
   *     name (800) → aria-label (700) → ARIA role + name when interactive (600) → role
   *     + name on any element (500) → tag + text on interactive (400) → none (0).
   *   - **Depth penalty** (slight): −30 per ancestor step. Lets a slightly stronger
   *     ancestor beat a much weaker click target, but a click target with a strong
   *     identifier always wins over an ancestor with the same strength.
   */
  private fun candidateScore(c: PlaywrightDeviceScreenStream.ClickCandidate): Int {
    val isFormControl = c.tag in FORM_CONTROL_TAGS
    val usableId = c.id.isNotBlank() && !c.id.first().isDigit()
    val identifierScore = when {
      c.dataTestId.isNotBlank() -> 1000
      usableId -> 900
      isFormControl && c.nameAttr.isNotBlank() -> 800
      c.ariaLabel.isNotBlank() -> 700
      c.role.isNotBlank() && c.name.isNotBlank() && c.interactive -> 600
      c.role.isNotBlank() && c.name.isNotBlank() -> 500
      c.interactive && c.name.isNotBlank() -> 400
      else -> 0
    }
    return identifierScore - c.depth * 30
  }

  /**
   * Escapes a plain accessible-name for use as a regex pattern. Wraps in `\Q…\E` when the
   * name contains regex metacharacters; otherwise returns it as-is so the recorded YAML
   * stays readable. Mirrors the model module's internal `escapeForSelector` helper —
   * inlined here because that one isn't exposed across module boundaries.
   */
  private fun escapeNameForSelector(name: String): String =
    if (name.any { it in REGEX_METACHARACTERS }) Regex.escape(name) else name

  /** Reuse the stream's CSS-attribute escaper so attribute-value quoting stays consistent. */
  private fun cssEscape(value: String): String =
    PlaywrightDeviceScreenStream.cssEscape(value)

  override fun createSwipeTool(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
    durationMs: Long?,
  ): Pair<TrailblazeTool, String> {
    // Web swipes collapse to scroll-by-direction; the underlying scroll tool doesn't take a
    // duration (browser scroll is instantaneous), so the recorded gesture's duration is
    // intentionally dropped for this platform. Real velocity-aware web flings would need a
    // distinct tool — out of scope here.
    val direction = computeScrollDirection(startX, startY, endX, endY)
    val tool = PlaywrightNativeScrollTool(direction = direction)
    return tool to "web_scroll"
  }

  override fun createInputTextTool(text: String): Pair<TrailblazeTool, String> {
    val tool = PlaywrightNativeTypeTool(
      text = text,
      ref = "css=:focus",
      clearFirst = false,
    )
    return tool to "web_type"
  }

  override fun createPressKeyTool(key: String): Pair<TrailblazeTool, String>? {
    val tool = PlaywrightNativePressKeyTool(key = key)
    return tool to "web_press_key"
  }

  private fun computeScrollDirection(
    startX: Int,
    startY: Int,
    endX: Int,
    endY: Int,
  ): PlaywrightNativeScrollTool.ScrollDirection {
    val dx = endX - startX
    val dy = endY - startY
    return if (abs(dy) >= abs(dx)) {
      if (dy < 0) PlaywrightNativeScrollTool.ScrollDirection.DOWN
      else PlaywrightNativeScrollTool.ScrollDirection.UP
    } else {
      if (dx < 0) PlaywrightNativeScrollTool.ScrollDirection.RIGHT
      else PlaywrightNativeScrollTool.ScrollDirection.LEFT
    }
  }
}
