package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.orchestra.Command
import maestro.orchestra.EraseTextCommand
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.MapsToMaestroCommands
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess

@Serializable
@TrailblazeToolClass("eraseText")
@LLMDescription(
  """
Erases characters from the currently focused text field.
- If charactersToErase is omitted or null, ALL text in the field is erased.
- If a number is provided, that many characters are erased from the end.
- Use this BEFORE inputText when you need to replace existing text in a field (e.g. a search bar or form field that already has content).
    """,
)
data class EraseTextTrailblazeTool(
  val charactersToErase: Int? = null,
  override val reasoning: String? = null,
) : MapsToMaestroCommands(), ReasoningTrailblazeTool {
  override fun toMaestroCommands(memory: AgentMemory): List<Command> = listOf(
    EraseTextCommand(
      charactersToErase = charactersToErase,
    ),
  )

  override suspend fun execute(
    toolExecutionContext: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val result = super.execute(toolExecutionContext)
    if (result.isSuccess()) {
      val detail = charactersToErase?.let { "Erased $it characters" } ?: "Erased all text"
      return TrailblazeToolResult.Success(message = detail)
    }
    return result
  }
}
