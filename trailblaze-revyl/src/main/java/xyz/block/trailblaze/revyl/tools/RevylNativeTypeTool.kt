package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Types text into an input field on the Revyl cloud device.
 *
 * Optionally targets a specific field by natural language description.
 * When [clearFirst] is true, the field is cleared before typing.
 */
@Serializable
@TrailblazeToolClass("revyl_type")
@LLMDescription(
  "Type text into an input field. Optionally specify a target field " +
    "(e.g. 'email field', 'password input').",
)
class RevylNativeTypeTool(
  @param:LLMDescription("The text to type into the field.")
  val text: String,
  @param:LLMDescription("Optional target field, described in natural language.")
  val target: String = "",
  @param:LLMDescription("If true, clear the field before typing.")
  val clearFirst: Boolean = false,
  override val reasoning: String? = null,
) : RevylExecutableTool() {

  override suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val desc = if (target.isNotBlank()) "into '$target'" else "into focused field"
    Console.log("### Typing '$text' $desc")
    val result = revylClient.typeText(text, target.ifBlank { null }, clearFirst)
    val feedback = "Typed '$text' $desc at (${result.x}, ${result.y})"
    Console.log("### $feedback")
    return TrailblazeToolResult.Success(message = feedback)
  }
}
