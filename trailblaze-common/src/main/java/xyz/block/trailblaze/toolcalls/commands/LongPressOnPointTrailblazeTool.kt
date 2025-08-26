package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("longPressOnPoint")
@LLMDescription(
  """
Long press on the provided coordinates.
      """,
)
data class LongPressOnPointTrailblazeTool(
  @LLMDescription("The center X coordinate for the clickable element")
  val x: Int,
  @LLMDescription("The center Y coordinate for the clickable element")
  val y: Int,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    TapOnPointV2Command(
      point = "$x,$y",
      longPress = true,
    ),
  )
}
