package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolProperty

/**
 * Command for boolean assertions on UI elements.
 * Returns true/false with explanation of why the assertion passed or failed.
 */
@Serializable
@TrailblazeToolClass(
  name = "booleanAssertion",
  description = "Evaluate if a statement about the current screen is true or false",
)
data class BooleanAssertionTrailblazeTool(
  @TrailblazeToolProperty("Explanation of why the statement is true or false")
  val reason: String,

  @TrailblazeToolProperty("Whether the statement is true or false")
  val result: Boolean,
) : TrailblazeTool
