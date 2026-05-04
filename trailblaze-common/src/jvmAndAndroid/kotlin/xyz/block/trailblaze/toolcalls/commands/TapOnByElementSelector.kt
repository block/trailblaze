package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.TapOnElementCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeElementSelector
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
  isForLlm = false
)
@LLMDescription("Taps on an element by its selector.")
/**
 *  ----- DO NOT USE GIVE THIS TOOL TO THE LLM -----
 *
 * This is a tool that should be delegated to, not registered to the LLM
 */
data class TapOnByElementSelector(
  val reason: String? = null,
  val selector: TrailblazeElementSelector,
  val longPress: Boolean = false,
  /**
   * Rich driver-native selector generated from [TrailblazeNode] trees.
   * When present, the agent will attempt to use this for richer element matching
   * before falling back to the legacy Maestro command path via [selector].
   *
   * Serialized in trail YAML files so recordings preserve the rich selector for playback.
   */
  val nodeSelector: TrailblazeNodeSelector? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    TapOnElementCommand(
      selector = selector.toMaestroElementSelector(),
      longPress = longPress,
    ),
  )

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
    // Cross-driver compatibility: existing recordings made under the Maestro/instrumentation
    // driver carry BOTH a Maestro [selector] and an [androidAccessibility] nodeSelector for
    // forward-portability. Under a non-accessibility runtime agent (e.g.
    // [AndroidMaestroTrailblazeAgent] on the on-device test farm) those recordings should
    // fall through to the regular PREFER_NODE_SELECTOR path, which itself falls back to the
    // Maestro selector. Gating on [usesAccessibilityDriver] preserves the strict refusal for
    // the host CLI / device-side accessibility path while keeping legacy recordings runnable
    // under Maestro drivers.
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
      // to the legacy Maestro [selector] — the recording's [containsChild]/[textRegex] shape
      // resolves correctly under UiAutomator for cross-driver-recorded trails.
    }

    when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> {
        return super.execute(toolExecutionContext)
      }
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (agent != null) {
          val effectiveNodeSelector = nodeSelector
            ?: selector.toTrailblazeNodeSelector(toolExecutionContext.trailblazeDeviceInfo.platform)
          val result = agent.executeNodeSelectorTap(
            nodeSelector = effectiveNodeSelector,
            longPress = longPress,
            traceId = toolExecutionContext.traceId,
          )
          if (result != null) return result
        }
        return super.execute(toolExecutionContext)
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
        return super.execute(toolExecutionContext)
      }
    }
  }
}
