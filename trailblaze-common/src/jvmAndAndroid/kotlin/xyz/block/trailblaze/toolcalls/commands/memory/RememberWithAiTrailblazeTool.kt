package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

@Serializable
@TrailblazeToolClass("rememberWithAi")
@LLMDescription(
  """
This will remember some data about the current screen using the natural language prompt parameter.
Once the data is found it will be saved for future test operations under the variable parameter.
      """,
)
data class RememberWithAiTrailblazeTool(
  val prompt: String,
  val variable: String,
) : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    val interpolatedPrompt = memory.interpolateVariables(prompt)
    val evaluation = elementComparator.evaluateString(interpolatedPrompt)
    println("UI Evaluation result: ${evaluation.result}, reason: ${evaluation.reason}")

    memory.remember(variable, evaluation.result)
    return TrailblazeToolResult.Success
  }
}
