package xyz.block.trailblaze.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.Serializable
import org.junit.Test
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.toKoogToolDescriptor

class ReasoningTrailblazeToolDescriptorTest {

  /**
   * A fake tool that implements [ReasoningTrailblazeTool].
   *
   * Note: no `@LLMDescription` on the `reasoning` parameter — the description is provided
   * to the LLM via the system prompt to save context window tokens.
   */
  @Serializable
  @TrailblazeToolClass("fake_reasoning_tool")
  @LLMDescription("A fake tool for testing reasoning parameter descriptor generation.")
  class FakeReasoningTool(
    @param:LLMDescription("Some required input.") val input: String,
    override val reasoning: String? = null,
  ) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {
    override suspend fun execute(
      toolExecutionContext: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult = TrailblazeToolResult.Success()
  }

  @Serializable
  @TrailblazeToolClass("fake_non_reasoning_tool")
  @LLMDescription("A fake tool without reasoning.")
  class FakeNonReasoningTool(
    @param:LLMDescription("Some required input.") val input: String,
  ) : TrailblazeTool

  @Test
  fun `reasoning parameter is present and optional in tool descriptor`() {
    val descriptor = FakeReasoningTool::class.toKoogToolDescriptor()
    assertNotNull(descriptor)

    val requiredParamNames = descriptor.requiredParameters.map { it.name }
    val optionalParamNames = descriptor.optionalParameters.map { it.name }

    assertTrue("reasoning" in optionalParamNames, "reasoning should be in optional parameters")
    assertTrue("reasoning" !in requiredParamNames, "reasoning should not be required")
    assertTrue("input" in requiredParamNames, "input should be required")
  }

  @Test
  fun `reasoning parameter has no per-tool LLM description`() {
    val descriptor = FakeReasoningTool::class.toKoogToolDescriptor()
    assertNotNull(descriptor)

    val reasoningParam = descriptor.optionalParameters.find { it.name == "reasoning" }
    assertNotNull(reasoningParam, "Expected 'reasoning' in optional parameters")
    assertTrue(
      reasoningParam.description.isEmpty(),
      "Reasoning description should be empty — it is explained via the system prompt instead",
    )
  }

  @Test
  fun `non-reasoning tool does not have reasoning parameter`() {
    val descriptor = FakeNonReasoningTool::class.toKoogToolDescriptor()
    assertNotNull(descriptor)

    val allParamNames =
      descriptor.requiredParameters.map { it.name } +
        descriptor.optionalParameters.map { it.name }
    assertTrue("reasoning" !in allParamNames, "Non-reasoning tool should not have reasoning param")
  }
}
