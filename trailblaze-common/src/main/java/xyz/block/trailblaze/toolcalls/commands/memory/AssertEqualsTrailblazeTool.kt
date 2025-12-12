package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

@Serializable
@TrailblazeToolClass("assertEquals")
@LLMDescription(
  """
This will calculate the actual and expected values based on the current screen state and assert that
they are equivalent.
      """,
)
data class AssertEqualsTrailblazeTool(
  val actual: String,
  val expected: String,
) : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    val interpolatedActual = memory.interpolateVariables(actual)
    val interpolatedExpected = memory.interpolateVariables(expected)

    if (interpolatedActual != interpolatedExpected) {
      throw TrailblazeToolExecutionException(
        message = "Assertion failed: Expected '$interpolatedExpected', but got '$interpolatedActual'",
        tool = this,
      )
    }
    return TrailblazeToolResult.Success
  }
}
