package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("tapOnPoint")
@LLMDescription(
  """
Taps on the UI at the provided coordinates.
      """,
)
data class TapOnPointTrailblazeTool(
  @LLMDescription("The center X coordinate for the clickable element")
  val x: Int,
  @LLMDescription("The center Y coordinate for the clickable element")
  val y: Int,
) : TrailblazeTool,
  MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> = listOf(
    TapOnPointV2Command(
      point = "$x,$y",
      retryIfNoChange = false,
    ),
  )
}
