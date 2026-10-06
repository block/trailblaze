package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Runs a visual assertion on the Revyl cloud device screen.
 *
 * Uses Revyl's AI-powered validation step to verify a condition
 * described in natural language.
 */
@Serializable
@TrailblazeToolClass("revyl_assert", isVerification = true)
@LLMDescription("Assert a visual condition on the screen.")
data class RevylNativeAssertTool(
  @param:LLMDescription(
    "Condition that should be true, in natural language, e.g. 'the Sign In button is disabled'.",
  )
  val assertion: String,
  override val reasoning: String? = null,
) : RevylExecutableTool() {

  override suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Asserting: $assertion")
    val result = revylClient.validation(assertion)
    return if (result.success) {
      TrailblazeToolResult.Success(message = "Assertion passed: '$assertion'")
    } else {
      TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "Assertion failed: '$assertion'" +
          (result.statusReason?.let { " -- $it" } ?: ""),
      )
    }
  }
}
