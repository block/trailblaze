package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.TapOnPointV2Command
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolProperty

@Serializable
@TrailblazeToolClass(
  name = "tapOnPoint",
  description = """
Taps on the UI at the provided coordinates.
      """,
)
data class TapOnPointTrailblazeTool(
  @TrailblazeToolProperty("The center X coordinate for the clickable element")
  val x: Int,
  @TrailblazeToolProperty("The center Y coordinate for the clickable element")
  val y: Int,
) : TrailblazeTool,
  MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> = listOf(
    TapOnPointV2Command(
      point = "$x,$y",
    ),
  )
}
