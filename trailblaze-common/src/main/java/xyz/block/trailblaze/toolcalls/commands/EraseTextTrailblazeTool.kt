package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("eraseText")
@LLMDescription(
  """
Erases the specified number of characters from the text field.  If no number is provided, it will erase all.
    """,
)
data class EraseTextTrailblazeTool(
  val charactersToErase: Int? = null,
) : TrailblazeTool,
  MapsToMaestroCommands {
  override fun toMaestroCommands(): List<Command> = listOf(
    EraseTextCommand(
      charactersToErase = charactersToErase,
    ),
  )
}
