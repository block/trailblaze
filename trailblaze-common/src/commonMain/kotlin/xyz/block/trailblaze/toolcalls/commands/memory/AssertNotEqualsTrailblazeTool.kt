package xyz.block.trailblaze.toolcalls.commands.memory

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.AgentMemory
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.utils.ElementComparator

@Serializable
@TrailblazeToolClass("assertNotEquals", isVerification = true)
@LLMDescription(
  """
Calculate the actual and expected values from the current screen state and assert they are NOT equivalent.
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
    // {{var}}/${var} tokens are resolved by the dispatch boundary (interpolateMemoryInTool)
    // before execute() runs, so the fields arrive resolved here.
    if (actual == expected) {
      throw TrailblazeToolExecutionException(
        message = "Assertion failed: Expected '$expected' to NOT equal '$actual'",
        tool = this,
      )
    }
    return TrailblazeToolResult.Success()
  }
}
