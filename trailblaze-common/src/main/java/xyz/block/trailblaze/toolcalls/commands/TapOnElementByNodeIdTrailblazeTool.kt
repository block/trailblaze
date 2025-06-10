package xyz.block.trailblaze.toolcalls.commands

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolProperty

@Serializable
@TrailblazeToolClass(
  name = "tapOnElementByNodeId",
  description = """
Provide the nodeId of the element you want to tap on in the nodeId parameter.
""",
)
data class TapOnElementByNodeIdTrailblazeTool(
  @TrailblazeToolProperty("Reasoning on why this element was chosen. Do NOT restate the nodeId.")
  val reason: String = "",
  @TrailblazeToolProperty("The nodeId of the element in the view hierarchy that will be tapped on. Do NOT use the nodeId 0.")
  val nodeId: Long,
  @TrailblazeToolProperty("A standard tap is default, but return 'true' to perform a long press instead.")
  val longPress: Boolean = false,
) : TrailblazeTool
