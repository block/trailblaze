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
