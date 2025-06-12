package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass

@Serializable
@TrailblazeToolClass("tapOnElementByNodeId")
@LLMDescription(
  """
Provide the nodeId of the element you want to tap on in the nodeId parameter.
""",
)
data class TapOnElementByNodeIdTrailblazeTool(
  @LLMDescription("Reasoning on why this element was chosen. Do NOT restate the nodeId.")
  val reason: String = "",
  @LLMDescription("The nodeId of the element in the view hierarchy that will be tapped on. Do NOT use the nodeId 0.")
  val nodeId: Long,
  @LLMDescription("A standard tap is default, but return 'true' to perform a long press instead.")
  val longPress: Boolean = false,
) : TrailblazeTool
