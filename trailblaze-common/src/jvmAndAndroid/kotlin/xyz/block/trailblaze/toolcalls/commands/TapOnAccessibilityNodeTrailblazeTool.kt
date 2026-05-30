package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Public scripted-tool face for Android accessibility selector taps. Builds a
 * [TrailblazeNodeSelector] from a content-description regex (plus optional className and
 * resourceId tiebreakers) and delegates to [TapOnByElementSelector].
 *
 * Lets scripted authors target nodes that are reachable only via accessibility properties —
 * the most common case is canvas widgets whose "buttons" are virtual views of an
 * `ExploreByTouchHelper` (PIN pads, drawing-app palettes, custom map markers). Those nodes
 * have a `contentDescription` but no `text`, so [TapOnElementWithTextTrailblazeTool] can't
 * reach them; the underlying [TapOnByElementSelector] dispatcher can, but Sam's split in
 * PR #3272 classified it as an internal "delegated-to" dispatcher and gated it from
 * scripted authors. This tool is the higher-level public face that fills that gap while
 * keeping the dispatcher itself internal.
 *
 * Routes through the same `executeTapOnElement` path the LLM `tap` tool uses, so the
 * per-tap `ACTION_CLICK` routing from PR #3524 applies — the route that fixes canvas-widget
 * tap drops (`case_5559262` / Padlock 6-digit passcode entry).
 *
 * **Multi-match disambiguation.** If the regexes match more than one node, the underlying
 * `AccessibilityDeviceManager` resolver logs `"matched N elements, using first"` and taps
 * the topmost-leftmost match. There is no scripted-tool-side error on multi-match — a
 * loose regex like `contentDescriptionRegex = "Key.*"` over a PIN pad will quietly tap the
 * first key, not fail loudly. Pin the regex with `^...$` anchors and add `classNameRegex`
 * / `resourceIdRegex` tiebreakers when the on-screen text could plausibly appear on more
 * than one node.
 *
 * **No coordinate fallback on miss.** Unlike the LLM `tap` tool, this primitive never sets
 * `fallbackX/Y`, so a selector that doesn't resolve errors with "Element not found" instead
 * of tapping a stale coordinate. Authors don't have to defensive-check against off-target
 * taps from a flaky a11y tree.
 */
@Serializable
@TrailblazeToolClass(
  name = "tapOnAccessibilityNode",
  surfaceToLlm = false,
  surfaceToScriptedTools = true,
)
@LLMDescription(
  "Tap an Android accessibility node identified by its content description. " +
    "Use for canvas widgets / ExploreByTouchHelper virtual views whose buttons don't expose " +
    "text (e.g., PIN pads, drawing palettes). Optional classNameRegex / resourceIdRegex " +
    "tiebreakers disambiguate merged-semantics or wrapper trees.",
)
data class TapOnAccessibilityNodeTrailblazeTool(
  @param:LLMDescription("Regex matched against the node's content description. Provide at least one of contentDescriptionRegex or textRegex.")
  val contentDescriptionRegex: String? = null,
  @param:LLMDescription("Regex matched against the node's text. Provide at least one of contentDescriptionRegex or textRegex.")
  val textRegex: String? = null,
  @param:LLMDescription("Optional regex matched against the node's className.")
  val classNameRegex: String? = null,
  @param:LLMDescription("Optional regex matched against the node's resourceId.")
  val resourceIdRegex: String? = null,
  @param:LLMDescription("Set to true for a long press instead of a tap.")
  val longPress: Boolean = false,
) : ExecutableTrailblazeTool {

  /**
   * Builds a `TapOnByElementSelector` carrying an `androidAccessibility`-shaped node selector
   * and forwards to its `execute`. Implemented as an `ExecutableTrailblazeTool` (not
   * `DelegatingTrailblazeTool`) because `JsScriptingCallbackDispatcher` rejects non-executable
   * tools before the delegate-expansion path runs — `ctx.tools.tapOnAccessibilityNode(...)`
   * has to be directly executable on the callback dispatch path.
   */
  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (contentDescriptionRegex.isNullOrBlank() && textRegex.isNullOrBlank()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "tapOnAccessibilityNode: at least one of contentDescriptionRegex or textRegex must be non-blank.",
        command = this,
      )
    }
    val platform = toolExecutionContext.trailblazeDeviceInfo.trailblazeDeviceId.trailblazeDevicePlatform
    if (platform != TrailblazeDevicePlatform.ANDROID) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "tapOnAccessibilityNode: only runs on Android (current platform: $platform). " +
          "Content-description-based taps require the Android accessibility driver — iOS and web " +
          "sessions have no equivalent surface.",
        command = this,
      )
    }
    val agent = toolExecutionContext.maestroTrailblazeAgent
    if (agent == null || !agent.usesAccessibilityDriver) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "tapOnAccessibilityNode: requires the Android accessibility driver. " +
          "The active runtime agent is ${agent?.javaClass?.simpleName ?: "null"} — Maestro-lowered " +
          "fallback can't resolve `contentDescriptionRegex` and would mis-target. Re-run with the " +
          "accessibility driver (e.g. via the on-device runner) or use a text-based selector tool.",
        command = this,
      )
    }
    return TapOnByElementSelector(
      longPress = longPress,
      nodeSelector = TrailblazeNodeSelector(
        androidAccessibility = DriverNodeMatch.AndroidAccessibility(
          contentDescriptionRegex = contentDescriptionRegex?.takeIf { it.isNotBlank() },
          textRegex = textRegex?.takeIf { it.isNotBlank() },
          classNameRegex = classNameRegex,
          resourceIdRegex = resourceIdRegex,
        ),
      ),
    ).execute(toolExecutionContext)
  }
}
