package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

@Serializable
@TrailblazeToolClass("assertNotEquals")
@LLMDescription(
  """
This will calculate the actual and expected values based on the current screen state and assert that
they are not equivalent.
      """,
)
data class AssertNotEqualsTrailblazeTool(
  val actual: String,
  val expected: String,
) : MemoryTrailblazeTool {
  override fun execute(
    memory: AgentMemory,
    elementComparator: ElementComparator,
  ): TrailblazeToolResult {
    val interpolatedActual = memory.interpolateVariables(actual)
    val interpolatedExpected = memory.interpolateVariables(expected)

    if (interpolatedActual == interpolatedExpected) {
      throw TrailblazeException("Assertion failed: Expected '$expected' to NOT equal '$actual'")
    }
    return TrailblazeToolResult.Success
  }
}
