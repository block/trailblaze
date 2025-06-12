package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Command for string evaluations on UI elements.
 * Returns a string value with explanation of how it was determined.
 */
@Serializable
@TrailblazeToolClass("stringEvaluation")
@LLMDescription(
  "Extract or evaluate textual information from the current screen",
)
data class StringEvaluationTrailblazeTool(
  @LLMDescription("Explanation of how this value was determined")
  val reason: String,

  @LLMDescription("The resulting string value or answer")
  val result: String,
) : TrailblazeTool
