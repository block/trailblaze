package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.TapOnElementCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.model.NodeSelectorMode
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass(
  name = "tapOnElementBySelector",
  surfaceToLlm = false,
  // Available to scripted tools: this is the selector-resolved tap (ACTION_CLICK-vs-gesture
  // routing + animation settle) that scripted authors compose for "tap the element matching this
  // selector". It stays hidden from the LLM (which uses the friendlier `tap` /
  // `tapOnElementWithText`) — those two audiences are orthogonal. The earlier blanket `false` was
  // a mechanical carry-over from the single-flag `isForLlm` split (#3272), not a scripted-specific
  // decision; surfacing it matches this tool's stated "delegated to, not LLM-registered" role.
)
@LLMDescription("Taps on an element by its selector.")
/**
 *  ----- DO NOT USE GIVE THIS TOOL TO THE LLM -----
 *
 * This is a tool that should be delegated to, not registered to the LLM
 */
data class TapOnByElementSelector(
  val reason: String? = null,
  val longPress: Boolean = false,
  /**
   * Rich driver-native selector generated from [TrailblazeNode] trees.
   *
   * The only way to identify the tap target. When the runtime dispatch needs a Maestro
   * command (non-accessibility drivers), [toMaestroCommands] lowers this to a Maestro-shaped
   * selector via [lowerToMaestroSelector].
   *
   * Serialized in trail YAML files so recordings preserve the rich selector for playback.
   */
  val nodeSelector: TrailblazeNodeSelector? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> {
    val maestroSelector = lowerToMaestroSelector(nodeSelector) ?: return emptyList()
    return listOf(
      TapOnElementCommand(
        selector = maestroSelector.toMaestroElementSelector(),
        longPress = longPress,
      ),
    )
  }

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val mode = toolExecutionContext.nodeSelectorMode
    val agent = toolExecutionContext.maestroTrailblazeAgent

    // Accessibility-driver recordings carry a [DriverNodeMatch.AndroidAccessibility]-shaped
    // nodeSelector and rely on the accessibility-native dispatch path (live tree resolution
    // + coordinate gesture). When the runtime agent is accessibility-capable
    // ([usesAccessibilityDriver]), Maestro fallback would resolve against UiAutomator's
    // tree — shaped differently and producing different element matches — never the right
    // answer. Surface the failure rather than silently mis-targeting under fallback.
    //
    // Cross-driver compatibility: some recordings carry an [androidAccessibility] nodeSelector
    // for forward-portability even though they're replayed under a non-accessibility runtime
    // agent (e.g. [AndroidMaestroTrailblazeAgent] on the on-device test farm). Those recordings
    // fall through to the regular PREFER_NODE_SELECTOR path below, which lowers the nodeSelector
    // to a Maestro selector — the recording's [containsChild]/[textRegex] shape resolves
    // correctly under UiAutomator for cross-driver-recorded trails. Gating on
    // [usesAccessibilityDriver] preserves the strict refusal for the host CLI / device-side
    // accessibility path while keeping those recordings runnable under Maestro drivers.
    if (nodeSelector?.androidAccessibility != null) {
      if (agent == null) {
        val message = "Accessibility recording cannot replay: no on-device " +
          "AccessibilityTrailblazeAgent is registered on the bridge. " +
          "Check that the device-side companion is running and the bridge connected."
        Console.log("### tap (accessibility): $message — selector=${nodeSelector.driverMatch?.description() ?: "?"}")
        return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message)
      }
      if (agent.usesAccessibilityDriver) {
        val accessibilityResult = agent.executeNodeSelectorTap(
          nodeSelector = nodeSelector,
          longPress = longPress,
          traceId = toolExecutionContext.traceId,
        )
        if (accessibilityResult != null) return accessibilityResult
        val message = "On-device AccessibilityTrailblazeAgent did not resolve the selector " +
          "for this recording. Refusing Maestro fallback — accessibility recordings must " +
          "resolve via the on-device agent. Check that the device is running with the " +
          "accessibility driver and the target node is present in the live tree."
        Console.log("### tap (accessibility): $message — selector=${nodeSelector.driverMatch?.description() ?: "?"}")
        return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message)
      }
      // Non-accessibility runtime agent (e.g. AndroidMaestroTrailblazeAgent on the on-device
      // test farm under instrumentation/Maestro driver). The recording happens to carry an
      // accessibility-shaped nodeSelector for forward-portability, but at runtime we have no
      // accessibility-native dispatch path. Fall through to PREFER_NODE_SELECTOR below, which
      // either dispatches via the agent's nodeSelector path (returns null here) or falls back
      // to the Maestro path via [lowerToMaestroSelector] — the recording's
      // [containsChild]/[textRegex] shape resolves correctly under UiAutomator for
      // cross-driver-recorded trails.
    }

    when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> {
        return runMaestroFallbackOrFail(toolExecutionContext)
      }
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (nodeSelector != null && agent != null) {
          val result = agent.executeNodeSelectorTap(
            nodeSelector = nodeSelector,
            longPress = longPress,
            traceId = toolExecutionContext.traceId,
          )
          if (result != null) return result
        }
        return runMaestroFallbackOrFail(toolExecutionContext)
      }
      NodeSelectorMode.PREFER_NODE_SELECTOR -> {
        if (nodeSelector != null && agent != null) {
          val result = agent.executeNodeSelectorTap(
            nodeSelector = nodeSelector,
            longPress = longPress,
            traceId = toolExecutionContext.traceId,
          )
          if (result != null) return result
        }
        return runMaestroFallbackOrFail(toolExecutionContext)
      }
    }
  }

  /**
   * Runs the Maestro command path via [super.execute]. [toMaestroCommands] lowers
   * [nodeSelector] through [TrailblazeNodeSelector.toTrailblazeElementSelector] so the Maestro
   * orchestra resolves it. Fails loudly when [nodeSelector] isn't set — that's a malformed
   * recording.
   */
  private suspend fun runMaestroFallbackOrFail(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    if (nodeSelector == null) {
      val message = "tapOnElementBySelector: `nodeSelector` is not set on this recording. " +
        "Cannot resolve a tap target."
      Console.log("### tap (no fallback): $message")
      return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = message)
    }
    return super.execute(toolExecutionContext)
  }
}
