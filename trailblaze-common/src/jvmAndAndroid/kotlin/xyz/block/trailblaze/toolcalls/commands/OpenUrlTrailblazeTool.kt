package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.OpenLinkCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("openUrl")
@LLMDescription(
  """
    Opens the browser to the provided url.
    """,
)
data class OpenUrlTrailblazeTool(
  @LLMDescription("The URL to open that starts with https")
  val url: String,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    OpenLinkCommand(
      link = memory.interpolateVariables(url),
    ),
  )
}
