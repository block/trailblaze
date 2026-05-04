package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("assertWithAi", isVerification = true)
@LLMDescription(
  """
Interpolate the natural-language prompt with any included variables, then use the result to verify
the current state of the screen with AI.
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
    Console.log("UI Assertion result: ${evaluation.result}, reason: ${evaluation.reason}")

    if (!evaluation.result) {
      throw TrailblazeToolExecutionException(message = "AI assertion failed: $interpolatedPrompt", tool = this)
    }
    return TrailblazeToolResult.Success()
  }
}
