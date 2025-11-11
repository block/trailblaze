package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AssertConditionCommand
import maestro.orchestra.Command
import maestro.orchestra.Condition
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector

@Serializable
@TrailblazeToolClass("assertVisibleBySelector", isForLlm = false)
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
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    AssertConditionCommand(
      condition = Condition(
        visible = selector.toMaestroElementSelector(),
      ),
    ),
  )
}
