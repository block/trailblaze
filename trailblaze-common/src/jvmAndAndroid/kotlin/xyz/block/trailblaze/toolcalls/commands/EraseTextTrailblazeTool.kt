package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Erases [charactersToErase] characters from the end of the focused text field. With no count,
 * each driver erases its default of 50; use [ClearTextTrailblazeTool] to empty a field of any
 * length.
 */
@Serializable
@TrailblazeToolClass("eraseText")
@LLMDescription(
  "Erase characters from the end of the focused text field. To clear the whole field, use " +
    "clearText instead.",
)
data class EraseTextTrailblazeTool(
  @param:LLMDescription("Number of characters to erase, counted from the field's text in the view hierarchy.")
  val charactersToErase: Int? = null,
) : MapsToMaestroCommands() {
  override fun toMaestroCommands(): List<Command> =
    listOf(EraseTextCommand(charactersToErase = charactersToErase))
}
