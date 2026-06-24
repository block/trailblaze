// The `selector` field on this tool is @Deprecated to focus the deprecation signal on
// *external* construction sites that still pass legacy selectors. The class's own
// internal logic (toMaestroCommands lowering, the desc fallback, dispatch in execute)
// must continue to read `selector` until the migration completes — those internal
// references are the legitimate handling for legacy recordings already on disk, not
// new tech debt. Suppress at file scope so the warning signal stays focused.
@file:Suppress("DEPRECATION")

package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeElementSelector
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
  surfaceToScriptedTools = false,
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
   * Legacy Maestro-shape selector.
   *
   * **Deprecated** — new tool construction should use [nodeSelector] exclusively. This
   * field remains nullable + serializable so older trail YAMLs (which carry a `selector:`
   * block alongside `nodeSelector:` or solo) continue to load unchanged and the runtime
   * dispatch logic in [execute] picks the right path per recording. The field will be
   * removed once the remaining flat-selector inventory in committed trails reaches zero
   * (tracked in the selector→nodeSelector migration workstream). At least one of
   * [selector] and [nodeSelector] must be non-null at runtime — the [execute]
   * function enforces this.
   */
  @Deprecated(
    message = "Prefer `nodeSelector` for new construction; this field exists only to load " +
      "legacy YAML recordings until the migration completes.",
    level = DeprecationLevel.WARNING,
  )
  val selector: TrailblazeElementSelector? = null,
  /**
   * Rich driver-native selector generated from [TrailblazeNode] trees.
   * When present, the agent will attempt to use this for richer element matching
   * before falling back to the legacy Maestro command path via [selector].
   */
  val nodeSelector: TrailblazeNodeSelector? = null,
  /**
   * Maximum time (in milliseconds) to wait for the element to become visible. The driver
   * polls the screen until either the element appears or this timeout elapses, so this
   * doubles as a "wait for selector" knob — set it higher when the screen needs time to
   * settle (e.g. an "Authorizing" overlay clearing) before the target text renders.
   *
   * When `null` the call is unopinionated about timeout and each agent applies its own
   * idle/wait policy (per-driver default). The Maestro fallback path ignores this field
   * entirely — Maestro's own assert timeout is always used there.
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
  override fun toMaestroCommands(memory: AgentMemory): List<Command> {
    val maestroSelector = lowerToMaestroSelector(selector, nodeSelector)
      ?: error(
        "AssertVisibleBySelectorTrailblazeTool.toMaestroCommands called with neither " +
          "`selector` nor `nodeSelector` set — malformed recording.",
      )
    // When expectedText is set on the legacy fallback path, narrow the Maestro selector to
    // also require that text — that's the closest analogue to selector-pinned text equality
    // the Maestro path can express. Drivers that support the modern node-selector path
    // (accessibility, etc.) hit the richer post-pass check in execute() below.
    //
    // textMatchMode controls how that text becomes the Maestro textRegex: EXACT pins the full
    // interpolated value (byte-identical to the original behavior); PREFIX escapes only the
    // stable head so a volatile tail (e.g. live item count) can't fail the match; REGEX passes
    // the value through as the regex pattern directly.
    val maestroElement = maestroSelector.toMaestroElementSelector().let { base ->
      if (expectedText != null) base.copy(textRegex = maestroTextRegexFor(memory)) else base
    }
    return listOf(AssertConditionCommand(condition = Condition(visible = maestroElement)))
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    require(selector != null || nodeSelector != null) {
      "AssertVisibleBySelectorTrailblazeTool requires at least one of `selector` or " +
        "`nodeSelector` to be non-null."
    }
    val mode = toolExecutionContext.nodeSelectorMode
    val agent = toolExecutionContext.maestroTrailblazeAgent

    val result = when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> super.execute(toolExecutionContext)
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (agent != null) {
          val effectiveNodeSelector = nodeSelector
            ?: selector?.toTrailblazeNodeSelector(toolExecutionContext.trailblazeDeviceInfo.platform)
            ?: error("FORCE_NODE_SELECTOR with neither nodeSelector nor selector")
          agent.executeNodeSelectorAssertVisible(
            nodeSelector = effectiveNodeSelector,
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
      // Prefer the original selector's text for a friendly message, but fall back to the
      // nodeSelector's driver-specific text for post-migration recordings where the legacy
      // `selector:` block is gone. Ordered by property tier (most → least human-readable),
      // with drivers alphabetized within each tier:
      //   1. Legacy `selector` (most direct authoring intent: textRegex → idRegex)
      //   2. Driver-block textRegex (best for log readability)
      //   3. Accessibility / content-description text (still human-readable)
      //   4. Resource ID (last resort — typically opaque)
      val desc = selector?.textRegex
        ?: selector?.idRegex
        // Tier: textRegex across all driver blocks
        ?: nodeSelector?.androidAccessibility?.textRegex
        ?: nodeSelector?.androidMaestro?.textRegex
        ?: nodeSelector?.iosMaestro?.textRegex
        // Tier: accessibility / content-description text
        ?: nodeSelector?.androidAccessibility?.contentDescriptionRegex
        ?: nodeSelector?.androidMaestro?.accessibilityTextRegex
        ?: nodeSelector?.iosMaestro?.accessibilityTextRegex
        // Tier: resource ID
        ?: nodeSelector?.androidAccessibility?.resourceIdRegex
        ?: nodeSelector?.androidMaestro?.resourceIdRegex
        ?: nodeSelector?.iosMaestro?.resourceIdRegex
        ?: "element"
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
    val effective = nodeSelector
      ?: selector?.toTrailblazeNodeSelector(toolExecutionContext.trailblazeDeviceInfo.platform)
      ?: return visibilityResult

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

    val expected = toolExecutionContext.memory.interpolateVariables(expectedText!!).trim()
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
   * [expected] value using [textMatchMode]. EXACT preserves the original strict equality. A
   * malformed REGEX pattern is treated as a non-match (surfaced as a normal assertion failure)
   * rather than thrown, so one bad hand-authored pattern can't turn replay into an infra error.
   */
  private fun matchesExpected(actual: String, expected: String): Boolean = when (textMatchMode) {
    TextMatchMode.EXACT -> actual == expected
    TextMatchMode.PREFIX -> actual.startsWith(expected)
    TextMatchMode.REGEX -> runCatching { Regex(expected).matches(actual) }.getOrDefault(false)
  }

  /**
   * Builds the Maestro `textRegex` for the legacy fallback path from [expectedText] under
   * [textMatchMode]. EXACT pins the full interpolated value (unchanged from the original
   * behavior); PREFIX escapes the stable head and allows any volatile tail (incl. newlines)
   * so the count etc. can't fail the match regardless of Maestro's anchoring; REGEX forwards
   * the value as the pattern, escaping it to a literal if it doesn't compile so Maestro never
   * receives a malformed pattern (which would surface as an execution error, not a clean miss).
   */
  private fun maestroTextRegexFor(memory: AgentMemory): String {
    val interpolated = memory.interpolateVariables(expectedText!!)
    return when (textMatchMode) {
      TextMatchMode.EXACT -> interpolated
      TextMatchMode.PREFIX -> Regex.escape(interpolated) + "[\\s\\S]*"
      TextMatchMode.REGEX ->
        if (runCatching { Regex(interpolated) }.isSuccess) interpolated else Regex.escape(interpolated)
    }
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
   * - Otherwise the matched element is itself the leaf and is the only candidate.
   */
  private fun collectTextCandidates(
    matched: TrailblazeNode,
    selector: TrailblazeNodeSelector,
  ): List<TrailblazeNode> {
    val innerSelectors = buildList {
      selector.containsChild?.let { add(it) }
      selector.containsDescendants?.let { addAll(it) }
    }
    if (innerSelectors.isEmpty()) return listOf(matched)
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

  private fun TrailblazeNode.extractText(): String? = when (val d = driverDetail) {
    is DriverNodeDetail.AndroidAccessibility -> d.text ?: d.contentDescription ?: d.labeledByText
    is DriverNodeDetail.AndroidMaestro -> d.text ?: d.accessibilityText
    is DriverNodeDetail.IosMaestro -> d.text ?: d.accessibilityText
    is DriverNodeDetail.IosAxe -> d.label
    is DriverNodeDetail.Web -> d.ariaName
    else -> null
  }
}
