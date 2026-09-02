package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass(
  name = "assertVisibleBySelector",
  surfaceToLlm = false,
  isVerification = true,
)
@LLMDescription("Asserts that an element with the provided selector is visible on the screen. Also matches state (checked/enabled/selected/stateDescription).")
/**
 *  ----- DO NOT USE GIVE THIS TOOL TO THE LLM -----
 *
 * This is a tool that should be delegated to, not registered to the LLM
 */
data class AssertVisibleBySelectorTrailblazeTool(
  val reason: String? = null,
  /**
   * Rich driver-native selector generated from [TrailblazeNode] trees. Required — the
   * [execute] function enforces non-null. When present, the agent will attempt to use this
   * for richer element matching before falling back to the Maestro command path, which
   * lowers this to a Maestro-shaped selector via [lowerToMaestroSelector].
   */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /**
   * Maximum time (in milliseconds) to wait for the element to become visible. The driver
   * polls the screen until either the element appears or this timeout elapses, so this
   * doubles as a "wait for selector" knob — set it higher when the screen needs time to
   * settle (e.g. an "Authorizing" overlay clearing) before the target text renders.
   *
   * When `null` the call is unopinionated about timeout and each agent applies its own
   * idle/wait policy (per-driver default). Forwarded to the Maestro fallback path too —
   * without that, a driver that resolves the selector but does not itself poll (the iOS host
   * agent returns null when the element is not on screen yet, by design) silently got
   * Maestro's default budget instead of the one the author asked for.
   */
  val timeoutMs: Long? = null,
  /**
   * Optional value-equality check applied AFTER the visibility check passes. When set, the
   * resolved element's driver-native text (text → contentDescription → accessibilityText/
   * label/ariaName, depending on the driver) must equal this string after whitespace
   * trimming on both sides. Case-sensitive. When null, only presence is enforced — the
   * pre-modernization behavior.
   *
   * Folded onto this tool (rather than a separate `assertVisibleWithText` replay class)
   * so the LLM-facing surface stays one tool with one optional field instead of forking
   * the family.
   */
  val expectedText: String? = null,
  /**
   * How [expectedText] is compared against the live element text at replay. [TextMatchMode.EXACT]
   * keeps the original strict-equality pin; [TextMatchMode.PREFIX] / [TextMatchMode.REGEX] let a
   * capture keep a stable head while tolerating volatile tails (e.g. live item counts). Defaults
   * to [TextMatchMode.EXACT] so trails recorded before this field deserialize to the original
   * behavior, and (with `encodeDefaults = false`) EXACT captures don't write the field at all.
   */
  val textMatchMode: TextMatchMode = TextMatchMode.EXACT,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> {
    val maestroSelector = lowerToMaestroSelector(nodeSelector)
      ?: error(
        "AssertVisibleBySelectorTrailblazeTool.toMaestroCommands called with `nodeSelector` " +
          "not set — malformed recording.",
      )
    // When expectedText is set on the legacy fallback path, narrow the Maestro selector to
    // also require that text — that's the closest analogue to selector-pinned text equality
    // the Maestro path can express. Drivers that support the modern node-selector path
    // (accessibility, etc.) hit the richer post-pass check in execute() below.
    //
    // textMatchMode controls how that text becomes the Maestro textRegex: EXACT lowers the value to
    // the trimmed, case-sensitive literal equality verifyTextEquality applies on the modern path;
    // PREFIX escapes only the stable head so a volatile tail (e.g. live item count) can't fail the
    // match; REGEX passes the value through as the regex pattern directly.
    val maestroElement = maestroSelector.toMaestroElementSelector().let { base ->
      if (expectedText != null) {
        base.copy(textRegex = conjoinTextRegex(base.textRegex, maestroTextRegexFor()))
      } else {
        base
      }
    }
    return listOf(
      AssertConditionCommand(
        condition = Condition(visible = maestroElement),
        timeout = timeoutMs?.toString(),
      ),
    )
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    require(nodeSelector != null) {
      "AssertVisibleBySelectorTrailblazeTool requires `nodeSelector` to be non-null."
    }
    val mode = toolExecutionContext.nodeSelectorMode
    val agent = toolExecutionContext.maestroTrailblazeAgent

    val result = when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> super.execute(toolExecutionContext)
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (agent != null) {
          agent.executeNodeSelectorAssertVisible(
            nodeSelector = nodeSelector,
            timeoutMs = timeoutMs,
            traceId = toolExecutionContext.traceId,
          ) ?: super.execute(toolExecutionContext)
        } else {
          super.execute(toolExecutionContext)
        }
      }
      NodeSelectorMode.PREFER_NODE_SELECTOR -> {
        if (nodeSelector != null && agent != null) {
          agent.executeNodeSelectorAssertVisible(
            nodeSelector = nodeSelector,
            timeoutMs = timeoutMs,
            traceId = toolExecutionContext.traceId,
          ) ?: super.execute(toolExecutionContext)
        } else {
          super.execute(toolExecutionContext)
        }
      }
    }
    if (result.isSuccess()) {
      val desc = selectorDescription()
      // When expectedText is set, the visibility check above only confirmed the element is
      // present. Now re-resolve against a fresh tree to read the matched element's text
      // and assert equality with the expected value. Soft-fall back to the visibility result
      // if no tree is available (the Maestro path above already enforced textRegex, so a
      // success there implies the text matched).
      if (expectedText != null) {
        return verifyTextEquality(toolExecutionContext, desc, result)
      }
      return TrailblazeToolResult.Success(message = "Verified '$desc' visible")
    }
    return result
  }

  /**
   * The [expectedText] post-pass for a driver that ran the VISIBILITY check on its own backend
   * instead of through [execute] — the in-process ANDROID_TEST adapter dispatches visibility to
   * its native assert and then must apply the same text verdict every other driver applies. One
   * implementation, so a trail cannot pass EXACT on one driver and PREFIX on another.
   *
   * Returns [visibilityResult] unchanged when [expectedText] is unset or the visibility check
   * failed; otherwise the text-equality verdict.
   */
  suspend fun applyExpectedTextPostPass(
    toolExecutionContext: TrailblazeToolExecutionContext,
    visibilityResult: TrailblazeToolResult,
  ): TrailblazeToolResult {
    if (expectedText == null || !visibilityResult.isSuccess()) return visibilityResult
    return verifyTextEquality(toolExecutionContext, selectorDescription(), visibilityResult)
  }

  /**
   * The nodeSelector's most human-readable handle, for result messages. Ordered by property
   * tier (most → least human-readable), with drivers alphabetized within each tier:
   *   1. Driver-block textRegex (best for log readability)
   *   2. Accessibility / content-description text (still human-readable)
   *   3. Resource ID (last resort — typically opaque)
   */
  private fun selectorDescription(): String = nodeSelector?.androidAccessibility?.textRegex
    ?: nodeSelector?.androidMaestro?.textRegex
    ?: nodeSelector?.androidView?.textRegex
    ?: nodeSelector?.iosMaestro?.textRegex
    // Tier: accessibility / content-description text
    ?: nodeSelector?.androidAccessibility?.contentDescriptionRegex
    ?: nodeSelector?.androidMaestro?.accessibilityTextRegex
    ?: nodeSelector?.androidView?.contentDescriptionRegex
    ?: nodeSelector?.iosMaestro?.accessibilityTextRegex
    // Tier: resource ID
    ?: nodeSelector?.androidAccessibility?.resourceIdRegex
    ?: nodeSelector?.androidMaestro?.resourceIdRegex
    ?: nodeSelector?.androidView?.resourceIdRegex
    ?: nodeSelector?.iosMaestro?.resourceIdRegex
    ?: "element"

  /**
   * Post-pass text-equality check, invoked only when [expectedText] is set. The
   * visibility check has already passed at this point; this method re-resolves the
   * selector against the live tree, reads the matched element's driver-native text, and
   * compares to [expectedText] after whitespace trimming on both sides. Returns a
   * surfaceable [TrailblazeToolResult.Error] on mismatch.
   */
  private fun verifyTextEquality(
    toolExecutionContext: TrailblazeToolExecutionContext,
    desc: String,
    visibilityResult: TrailblazeToolResult,
  ): TrailblazeToolResult {
    val fresh = toolExecutionContext.screenStateProvider?.invoke() ?: toolExecutionContext.screenState
    val tree = fresh?.trailblazeNodeTree ?: return visibilityResult
    val effective = nodeSelector ?: return visibilityResult

    val matched = when (
      val r = TrailblazeNodeSelectorResolver.resolve(tree, effective)
    ) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> r.node
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> r.nodes.first()
      // Visibility check said the element was there but the post-pass re-resolution
      // didn't find it. Don't surface as a failure — would be flaky on drivers where
      // the tree changes between the wait + re-read.
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> return visibilityResult
    }

    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execute() runs, so `expectedText` arrives resolved here.
    val expected = expectedText!!.trim()
    // Pick the candidate set whose text we compare against `expected`. The candidate
    // depends on the structural predicates on the selector:
    //
    //   - `containsChild` / `containsDescendants`: the candidate(s) are the descendants
    //     that satisfy the inner selector, NOT any node in the matched container's
    //     subtree. This binds the text check to the structurally-selected element so
    //     a sibling or unrelated descendant with the same text can't accidentally pass
    //     the assertion (Codex review on #3660).
    //
    //   - no structural predicate: the candidate is `matched` itself. The pre-fix
    //     behavior (read the matched node's own text) is preserved when the selector
    //     directly targets a leaf.
    val candidates = collectTextCandidates(matched, effective)
    val foundText = candidates.asSequence()
      .mapNotNull { it.extractText()?.trim() }
      .firstOrNull { matchesExpected(it, expected) }
    return if (foundText != null) {
      TrailblazeToolResult.Success(message = "Verified '$desc' shows text='$expected'")
    } else {
      val candidateTexts = candidates.mapNotNull { it.extractText()?.trim() }
        .filter { it.isNotBlank() }
      val sample = candidateTexts.take(5).joinToString(", ") { "'$it'" }
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "assertVisible: element matched '$desc' but expected text '$expected' " +
          "not found on the selector-matched element(s). " +
          (if (sample.isNotEmpty()) "Actual text(s): $sample" else "Matched element has no readable text."),
      )
    }
  }

  /**
   * Compares a live element's [actual] text against the (already-interpolated, trimmed)
   * [expected] value using [textMatchMode]. EXACT preserves the original strict equality, modulo
   * the space folding described on [ZS]. A malformed REGEX pattern is treated as a non-match
   * (surfaced as a normal assertion failure) rather than thrown, so one bad hand-authored pattern
   * can't turn replay into an infra error.
   *
   * REGEX deliberately does NOT fold: the author wrote a pattern, so a space there is whatever
   * they spelled, and `\p{Zs}` is available to them. Folding only applies to the two modes whose
   * value is a literal, and matches the folding [maestroTextRegexFor] bakes into the legacy path's
   * pattern, so both paths reach the same verdict.
   */
  private fun matchesExpected(actual: String, expected: String): Boolean = when (textMatchMode) {
    TextMatchMode.EXACT -> foldSpacesAndTrim(actual) == foldSpacesAndTrim(expected)
    TextMatchMode.PREFIX -> foldSpacesAndTrim(actual).startsWith(foldSpacesAndTrim(expected))
    TextMatchMode.REGEX -> runCatching { Regex(expected).matches(actual) }.getOrDefault(false)
  }

  /**
   * Builds the Maestro `textRegex` for the legacy fallback path from [expectedText] under
   * [textMatchMode]. Memory tokens in [expectedText] are already resolved by the dispatch
   * boundary.
   *
   * EXACT reproduces [verifyTextEquality]'s comparison — trimmed, case-sensitive equality — through
   * a pattern, which takes three pieces because Orchestra compiles `textRegex` with
   * `IGNORE_CASE | DOT_MATCHES_ALL | MULTILINE` (`Orchestra.REGEX_OPTIONS`) and full-matches it:
   *  - [escapeLiteralWithSpaceEquivalence] on the value, because unescaped a `?`, `.` or `$` in real
   *    UI copy is a metacharacter and the assertion can never match the text it was captured from;
   *  - `(?-i)` to undo Orchestra's IGNORE_CASE, since EXACT is case-sensitive on the modern path;
   *  - [EDGE_WHITESPACE] on both ends, because the modern path trims both sides while a Maestro full
   *    match sees the element's untrimmed text. It has to span `Zs` and not just `\s`: `trim()`
   *    strips 13 space separators that Java's ASCII-only `\s` cannot match, so a plain `\s*` would
   *    fail on padding the modern path silently absorbs.
   *
   * PREFIX escapes the stable head and allows any volatile tail (incl. newlines) so the count etc.
   * can't fail the match regardless of Maestro's anchoring, and tolerates leading padding for the
   * same reason EXACT does; REGEX forwards the value as the pattern, escaping it to a literal if it
   * doesn't compile so Maestro never receives a malformed pattern (which would surface as an
   * execution error, not a clean miss).
   */
  private fun maestroTextRegexFor(): String {
    val resolved = expectedText!!
    return when (textMatchMode) {
      TextMatchMode.EXACT ->
        "(?-i)" + EDGE_WHITESPACE + escapeLiteralWithSpaceEquivalence(resolved.trim()) + EDGE_WHITESPACE
      TextMatchMode.PREFIX ->
        EDGE_WHITESPACE + escapeLiteralWithSpaceEquivalence(resolved.trim()) + "[\\s\\S]*"
      TextMatchMode.REGEX ->
        if (runCatching { Regex(resolved) }.isSuccess) resolved else Regex.escape(resolved)
    }
  }

  /**
   * Requires BOTH the selector's own `textRegex` and the [expectedText]-derived pattern of the
   * element Maestro resolves, expressed as the one `textRegex` field Maestro gives us.
   *
   * The modern path checks these separately: the node selector resolves the element (its own
   * `textRegex` included), then [verifyTextEquality] checks [expectedText] on top. The legacy path
   * has one text slot, so before this it just overwrote the selector's value — discarding a
   * constraint the author supplied, and silently so. That loses real precision whenever the two
   * spell the same string differently: an agent that reads a NO-BREAK SPACE out of the
   * accessibility tree writes it into the selector verbatim and normalizes it to a plain space in
   * [expectedText], so the surviving pattern was the one that could never match the tree.
   *
   * "Both must hold" is the contract because it's the one the modern path already implements —
   * making the legacy path agree means a trail can't pass on one driver and fail on the other. The
   * alternatives both lose: "selector wins" drops [expectedText] entirely on drivers with no node
   * tree (where [verifyTextEquality] soft-passes and this pattern is the only thing enforcing it),
   * and "narrower wins" isn't decidable between two arbitrary regexes.
   *
   * Conjunction shape: `(?=(?:expected)\z)(?:selector)`. Orchestra full-matches, so the lookahead
   * pins `expected` across the whole value while `selector` consumes it — `\z` rather than `$`
   * because Orchestra's MULTILINE would otherwise let `$` stop at a line break. Wrapping each side
   * in `(?:…)` keeps a top-level `|` from spanning the join, and confines EXACT's leading `(?-i)`
   * to its own side so the selector keeps the case-insensitivity it meant under Maestro.
   *
   * `expected` goes first because group numbering runs left to right across the whole pattern,
   * lookaheads included, so whichever side comes second has its capture groups renumbered and any
   * numbered backreference in it silently retargeted. `expected` is the hand- or LLM-authored side
   * (REGEX mode), while selector patterns come out of the generator as escaped literals and digit
   * classes — so the authored side is the one that gets to keep its numbering. A backreference in a
   * hand-written *selector* pattern is still affected; use a named group (`\k<name>`) there, which
   * numbering can't disturb.
   *
   * The selector side becomes `regex-or-exact-literal`, not just the regex. That is what selectors
   * mean everywhere else: [TrailblazeNodeSelectorResolver]'s `matchesPattern` falls back to
   * `text == pattern`, mirroring Maestro's own `Filters.textMatches`
   * (`regex.matches(value) || regex.pattern == value`). Without the alternative, a recorded
   * `textRegex` of `$5.00` — which compiles fine but can never match, since a bare `$` anchors the
   * end — would resolve on the modern path and fail here. When the pattern doesn't compile at all
   * the literal is all that's left, which is also what Orchestra's `toRegexSafe` would have
   * degraded it to; inlining it raw would instead invalidate the *combined* pattern and take
   * `expected` down with it.
   */
  private fun conjoinTextRegex(selectorTextRegex: String?, expectedTextRegex: String): String {
    if (selectorTextRegex == null) return expectedTextRegex
    val asLiteral = Regex.escape(selectorTextRegex)
    val selectorAlternatives = if (runCatching { Regex(selectorTextRegex) }.isSuccess) {
      "(?:$selectorTextRegex)|$asLiteral"
    } else {
      asLiteral
    }
    return "(?=(?:$expectedTextRegex)\\z)(?:$selectorAlternatives)"
  }

  /**
   * Returns the nodes whose text should be compared against `expectedText` for an
   * assertVisible-with-text check.
   *
   * - If the selector carries `containsChild` or `containsDescendants`, those inner
   *   selectors identify the specific descendants the user is structurally pointing at
   *   — the text check binds to those. Resolving each inner against `matched.children`
   *   (not `matched` itself) avoids the matched outer container leaking into the candidate
   *   set if it happens to coincidentally satisfy the inner predicate.
   * - Otherwise, if the matched element carries its own readable text it is treated as the
   *   leaf and is the only candidate (original behavior). If it has NO readable text, the
   *   selector landed on a structural container (e.g. `android.view.View` / a RecyclerView)
   *   while the asserted text lives on a descendant — fall back to the matched node's subtree
   *   so the check finds that descendant. Without this, a textless-container match fails with
   *   "Matched element has no readable text" even though the text is present in the subtree,
   *   and the runtime LLM (which sees the text as a descendant in the hierarchy) re-issues the
   *   identical assertion in a zero-progress loop until the per-step call budget is exhausted.
   */
  private fun collectTextCandidates(
    matched: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): List<TrailblazeNode> {
    val innerSelectors = buildList {
      selector.containsChild?.let { add(it) }
      selector.containsDescendants?.let { addAll(it) }
    }
    if (innerSelectors.isEmpty()) {
      return if (matched.extractText()?.isNotBlank() == true) listOf(matched) else matched.aggregate()
    }
    val out = LinkedHashSet<TrailblazeNode>()
    for (inner in innerSelectors) {
      for (child in matched.children) {
        when (val r = TrailblazeNodeSelectorResolver.resolve(child, inner)) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> out += r.node
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> out += r.nodes
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> {}
        }
      }
    }
    // If a containsChild/containsDescendants predicate was present but didn't re-resolve
    // (tree drift between visibility check and post-pass), fall back to the matched
    // element rather than auto-passing the assertion.
    return if (out.isEmpty()) listOf(matched) else out.toList()
  }

  /**
   * Exhaustive on purpose — no `else`. A dialect this doesn't name reads as "the element has no
   * readable text", which turns [expectedText] into an assertion that always fails, so the
   * compiler is the right place to catch the next one. `compose` was exactly that hole: a
   * composable's text sat one branch away and the assertion reported a mismatch that wasn't real.
   *
   * Compose reads `editableText` first, matching [DriverNodeDetail.Compose.resolveText]. A text
   * field publishes both its current value and its label, and an assertion about a field is about
   * what the user typed — checking the label first would fail every assertion on a filled-in value
   * and pass an assertion on the label without ever reading the input.
   *
   * `hintText` sits behind `text` on every dialect that HAS one, matching each dialect's own
   * `resolveText()` — which is the same fold the resolver applies to `textRegex`. Reading it here
   * is what keeps `expectedText` answerable on an EMPTY text field: an unfilled `EditText`
   * publishes its placeholder as the hint and nothing as its text, so a selector that matched the
   * field on `textRegex` would then fail the post-pass with "no readable text" — one tool
   * disagreeing with itself about what the element says (case 5380822 asserts the item-search
   * field shows "Search all items", which the field only ever carries as a hint).
   */
  private fun TrailblazeNode.extractText(): String? = when (val d = driverDetail) {
    is DriverNodeDetail.AndroidAccessibility ->
      d.text ?: d.hintText ?: d.contentDescription ?: d.labeledByText
    is DriverNodeDetail.AndroidView -> d.text ?: d.hintText ?: d.contentDescription
    is DriverNodeDetail.AndroidMaestro -> d.text ?: d.hintText ?: d.accessibilityText
    is DriverNodeDetail.Compose -> d.editableText ?: d.text ?: d.contentDescription
    is DriverNodeDetail.IosMaestro -> d.text ?: d.hintText ?: d.accessibilityText
    is DriverNodeDetail.IosAxe -> d.label
    is DriverNodeDetail.Web -> d.ariaName
  }

  companion object {
    /**
     * Every Unicode space separator — all 17 members of category `Zs`. A reader cannot tell any of
     * them apart from a plain space, so a literal text assertion typed with U+0020 has to match
     * whichever one the app actually ships.
     *
     * UI copy carries them constantly, deliberately (a NO-BREAK SPACE keeping "your receipt?" or
     * "$4.00" from wrapping) and incidentally (a CMS or the accessibility tree substituting one in).
     * Nobody types one into an assertion and nobody can see one in a diff, so the failure reads
     * `expected 'Total due', got 'Total due'`.
     *
     * `Zs` rather than a hand-picked subset because the two mechanisms that were supposed to absorb
     * these each miss a *different* part of it, and neither miss is visible:
     *  - Java's `\s` is ASCII-only (`[ \t\n\x0B\f\r]`) — Orchestra's REGEX_OPTIONS does not set
     *    UNICODE_CHARACTER_CLASS — so a regex `\s` matches U+0020 and none of the other 16.
     *  - Kotlin's `trim()` goes by `Char.isWhitespace`, which excludes exactly the three
     *    non-breaking ones (U+00A0, U+2007, U+202F), so those survive trimming.
     * Between them no subset was covered consistently, and the modern and legacy paths disagreed
     * about which characters they tolerated. Folding all of `Zs` is the one rule that makes both
     * paths agree.
     *
     * Deliberately NOT whitespace at large: `Zs` excludes `\n` and `\t` (they are `Cc`/`Zl`/`Zp`),
     * and a newline carries real layout meaning in these assertions.
     */
    private const val ZS = "\\p{Zs}"

    /** Leading/trailing padding: ASCII whitespace plus every [ZS] character. */
    private const val EDGE_WHITESPACE = "[\\s$ZS]*"

    private val zsRegex = Regex(ZS)

    /**
     * Collapses every [ZS] character to a plain space, then trims. Folding before trimming is the
     * point: the three non-breaking members are not `Char.isWhitespace`, so trimming first would
     * leave them stuck to the ends.
     */
    private fun foldSpacesAndTrim(text: String): String = zsRegex.replace(text, " ").trim()

    /**
     * `Regex.escape` on [literal], except each [ZS] character becomes the [ZS] class so the emitted
     * pattern matches the text whichever space it uses — the pattern-side equivalent of
     * [foldSpacesAndTrim].
     *
     * Splitting on the class and escaping the pieces, rather than escaping the whole string and
     * patching it up, keeps every other character inside a `\Q…\E` quote where it belongs.
     */
    private fun escapeLiteralWithSpaceEquivalence(literal: String): String = literal
      .split(zsRegex)
      .joinToString(ZS) { Regex.escape(it) }
  }
}
