package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolProperty

/**
 * Command for string evaluations on UI elements.
 * Returns a string value with explanation of how it was determined.
 */
@Serializable
@TrailblazeToolClass(
  name = "stringEvaluation",
  description = "Extract or evaluate textual information from the current screen",
)
data class StringEvaluationTrailblazeTool(
  @TrailblazeToolProperty("Explanation of how this value was determined")
  val reason: String,

  @TrailblazeToolProperty("The resulting string value or answer")
  val result: String,
) : TrailblazeTool
