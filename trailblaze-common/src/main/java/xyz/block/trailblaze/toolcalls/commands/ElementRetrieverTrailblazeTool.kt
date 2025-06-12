package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

/**
 * Command to retrieve a selector for an element based on the description.
 * This command is specifically used by the TrailblazeElementComparator.
 *
 * Note: The locator itself doesn't need to describe the element - it just needs to correctly
 * point to the element that matches the description. The locator will be used to find the actual
 * text/element later.
 */
@Serializable
@TrailblazeToolClass(
  name = "elementRetriever",
  description = "Retrieve a selector for an element based on natural language description",
)
data class ElementRetrieverTrailblazeTool(
  @LLMDescription("The natural language description of the element to find")
  val identifier: String,

  @LLMDescription("The type of locator to use (RESOURCE_ID, TEXT, or ACCESSIBILITY_TEXT)")
  val locatorType: LocatorType,

  @LLMDescription("The actual value of the locator from the view hierarchy")
  val value: String,

  @LLMDescription("Index to use when multiple elements match the same locator (0-based)")
  val index: Int = 0,

  @LLMDescription("Whether a reliable locator was found")
  val success: Boolean = true,

  @LLMDescription("Explanation of the chosen locator or reason for failure")
  val reason: String = "",
) : TrailblazeTool {
  /**
   * Represents the response from LLM when identifying a UI element locator.
   */
  @Serializable
  data class LocatorResponse(
    val success: Boolean,
    val locatorType: LocatorType?,
    val value: String?,
    val index: Int?,
    val reason: String,
  )

  /**
   * Types of locators in order of preference.
   */
  @Serializable
  enum class LocatorType {
    RESOURCE_ID,
    CONTENT_DESCRIPTION,
    TEXT,
  }
}
