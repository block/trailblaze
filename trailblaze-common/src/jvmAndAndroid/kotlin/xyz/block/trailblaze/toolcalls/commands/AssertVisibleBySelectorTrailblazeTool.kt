package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelector
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
  isForLlm = false,
)
@LLMDescription("Asserts that an element with the provided selector is visible on the screen.")
/**
 *  ----- DO NOT USE GIVE THIS TOOL TO THE LLM -----
 *
 * This is a tool that should be delegated to, not registered to the LLM
 */
data class AssertVisibleBySelectorTrailblazeTool(
  val reason: String? = null,
  /**
   * "The selector of the element to assert visibility for."
   */
  val selector: TrailblazeElementSelector,
  /**
   * Rich driver-native selector generated from [TrailblazeNode] trees.
   * When present, the agent will attempt to use this for richer element matching
   * before falling back to the legacy Maestro command path via [selector].
   */
  val nodeSelector: TrailblazeNodeSelector? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    AssertConditionCommand(
      condition = Condition(
        visible = selector.toMaestroElementSelector(),
      ),
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val mode = toolExecutionContext.nodeSelectorMode
    val agent = toolExecutionContext.maestroTrailblazeAgent

    val result = when (mode) {
      NodeSelectorMode.FORCE_LEGACY -> super.execute(toolExecutionContext)
      NodeSelectorMode.FORCE_NODE_SELECTOR -> {
        if (agent != null) {
          val effectiveNodeSelector = nodeSelector
            ?: selector.toTrailblazeNodeSelector(toolExecutionContext.trailblazeDeviceInfo.platform)
          agent.executeNodeSelectorAssertVisible(
            nodeSelector = effectiveNodeSelector,
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
            traceId = toolExecutionContext.traceId,
          ) ?: super.execute(toolExecutionContext)
        } else {
          super.execute(toolExecutionContext)
        }
      }
    }
    if (result.isSuccess()) {
      val desc = selector.textRegex ?: selector.idRegex ?: "element"
      return TrailblazeToolResult.Success(message = "Verified '$desc' visible")
    }
    return result
  }
}
