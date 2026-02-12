package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

@Serializable
@TrailblazeToolClass("rememberText")
@LLMDescription(
  """
This will find text on the current screen using the natural language prompt parameter.
Once the text is found it will be saved for future test operations under the variable parameter.
      """,
)
data class RememberTextTrailblazeTool(
  val prompt: String,
  val variable: String,
) : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    val interpolatedPrompt = memory.interpolateVariables(prompt)
    val extractedValue = elementComparator.getElementValue(interpolatedPrompt)
      ?: throw TrailblazeToolExecutionException(
        message = "Failed to find element for prompt: $prompt",
        tool = this,
      )

    memory.remember(variable, extractedValue)
    return TrailblazeToolResult.Success
  }
}
