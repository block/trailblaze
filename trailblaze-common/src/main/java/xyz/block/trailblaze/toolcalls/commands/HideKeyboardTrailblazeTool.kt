package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.HideKeyboardCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass(
  name = "hideKeyboard",
  description = """
This hide the keyboard on the screen. This is useful to do after entering text into an input field.
  """,
)
class HideKeyboardTrailblazeTool :
  TrailblazeTool,
  MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> = listOf(
    HideKeyboardCommand(),
  )

  override fun equals(other: Any?): Boolean = this === other

  override fun hashCode(): Int = System.identityHashCode(this)
}
