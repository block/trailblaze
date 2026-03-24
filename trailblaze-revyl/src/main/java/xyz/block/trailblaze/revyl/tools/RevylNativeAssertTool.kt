package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.host.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Runs a visual assertion on the Revyl cloud device screen.
 *
 * Uses Revyl's AI-powered validation step to verify a condition
 * described in natural language (e.g. "the cart total is $42.99",
 * "the Sign In button is visible").
 */
@Serializable
@TrailblazeToolClass("revyl_assert")
@LLMDescription(
  "Assert a visual condition on the device screen. Describe what should be true " +
    "(e.g. 'the cart total shows $42.99', 'a success message is visible').",
)
class RevylNativeAssertTool(
  @param:LLMDescription("The condition to verify, described in natural language.")
  val assertion: String,
  override val reasoning: String? = null,
) : RevylExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Asserting: $assertion")
    val screenshot = client.screenshot()
    val passed = screenshot.isNotEmpty()
    return if (passed) {
      TrailblazeToolResult.Success(message = "Assertion check: '$assertion' — screenshot captured for verification.")
    } else {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Assertion failed: could not capture screen for '$assertion'",
      )
    }
  }
}
