package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.StopAppCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass(
  name = "stopApp",
  description = """
Kills the app with the provided appId. This is useful for stopping the app when it is in a bad state.
    """,
)
data class StopAppTrailblazeTool(val appId: String) :
  TrailblazeTool,
  MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> = listOf(
    StopAppCommand(
      appId = appId,
    ),
  )
}
