package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

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
  @LLMDescription("Explanation of why the statement is true or false")
  val reason: String,

  @LLMDescription("Whether the statement is true or false")
  val result: Boolean,
) : TrailblazeTool
