package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.utils.parseNumberString

@Serializable
@TrailblazeToolClass("rememberNumber")
@LLMDescription(
  """
Find a number on the current screen using the natural-language prompt, then save it under the
variable name for future test operations.
      """,
)
data class RememberNumberTrailblazeTool(
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

    val numberOutput = parseNumberString(extractedValue)
      ?: throw TrailblazeToolExecutionException(
        message = "Failed to parse number for extracted value: $extractedValue from prompt: $prompt",
        tool = this,
      )

    memory.remember(variable, numberOutput)
    return TrailblazeToolResult.Success()
  }
}
