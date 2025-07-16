package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

@Serializable
@TrailblazeToolClass("assertWithAi")
@LLMDescription(
  """
This tool will interpolate the natural language prompt with any included variables, then use that
interpolated prompt to verify the current state of the screen using AI.
      """,
)
data class AssertWithAiTrailblazeTool(
  val prompt: String,
) : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    val interpolatedPrompt = memory.interpolateVariables(prompt)
    val evaluation = elementComparator.evaluateBoolean(prompt)
    println("UI Assertion result: ${evaluation.result}, reason: ${evaluation.reason}")

    if (!evaluation.result) {
      throw TrailblazeException("AI assertion failed: $interpolatedPrompt")
    }
    return TrailblazeToolResult.Success
  }
}
