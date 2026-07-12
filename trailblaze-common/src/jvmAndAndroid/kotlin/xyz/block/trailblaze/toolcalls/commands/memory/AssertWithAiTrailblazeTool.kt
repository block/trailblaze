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
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execute() runs, so the AI evaluates the resolved prompt. (The old self-interpolation
    // only rewrote the error message — the evaluation itself saw the raw token.)
    val evaluation = elementComparator.evaluateBoolean(prompt)
    Console.log("UI Assertion result: ${evaluation.result}, reason: ${evaluation.reason}")

    if (!evaluation.result) {
      throw TrailblazeToolExecutionException(message = "AI assertion failed: $prompt", tool = this)
    }
    return TrailblazeToolResult.Success()
  }
}
