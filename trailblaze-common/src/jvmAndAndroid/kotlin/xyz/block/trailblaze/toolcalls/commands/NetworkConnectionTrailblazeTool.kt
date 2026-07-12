package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.AirplaneValue
import maestro.orchestra.Command
import maestro.orchestra.SetAirplaneModeCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("networkConnection")
@LLMDescription(
  """
Toggles the device to be offline (airplane mode) or online (connected).
Use this tool to control network connectivity for the device.
""",
)
data class NetworkConnectionTrailblazeTool(
  @param:LLMDescription("Whether the device should be connected or disconnected to the network.")
  val connected: Boolean,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> = listOf(
    SetAirplaneModeCommand(
      value = when (connected) {
        true -> AirplaneValue.Disable
        false -> AirplaneValue.Enable
      },
    ),
  )
}
