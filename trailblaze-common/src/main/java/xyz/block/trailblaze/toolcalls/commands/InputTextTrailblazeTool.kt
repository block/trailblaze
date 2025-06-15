package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.InputTextCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeTools.REQUIRED_TEXT_DESCRIPTION

@Serializable
@TrailblazeToolClass("inputText")
@LLMDescription(
  """
This will type characters into the currently focused text field. This is useful for entering text.
- NOTE: If the text input field is not currently focused, please tap on the text field to focus it before using this command.
- NOTE: After typing text, considering closing the soft keyboard to avoid any issues with the app.
      """,
)
data class InputTextTrailblazeTool(
  @LLMDescription(REQUIRED_TEXT_DESCRIPTION) val text: String,
) : MapsToMaestroCommands() {

  override fun toMaestroCommands(): List<Command> = listOf(
    InputTextCommand(
      text = text,
    ),
  )
}
