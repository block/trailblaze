package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.StopAppCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("stopApp")
@LLMDescription(
  """
Kill the app with the provided appId. Use to stop the app when it is in a bad state.
    """,
)
data class StopAppTrailblazeTool(val appId: String) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    StopAppCommand(
      appId = appId,
    ),
  )
}
