package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.utils.parseNumberString

@Serializable
@TrailblazeToolClass("rememberNumber")
@LLMDescription(
  """
This will find a number on the current screen using the natural language prompt parameter.
Once the number is found it will be saved for future test operations under the variable parameter.
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
      ?: throw TrailblazeException("Failed to find element for prompt: $prompt")

    val numberOutput = parseNumberString(extractedValue)
      ?: throw TrailblazeException("Failed to parse number for extracted value: $extractedValue from prompt: $prompt")

    memory.remember(variable, numberOutput)
    return TrailblazeToolResult.Success
  }
}
