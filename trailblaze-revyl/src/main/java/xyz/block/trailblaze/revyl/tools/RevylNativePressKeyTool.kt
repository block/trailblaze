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
 * Sends a key press event (ENTER or BACKSPACE) on the Revyl cloud device.
 */
@Serializable
@TrailblazeToolClass("revyl_press_key")
@LLMDescription("Press a key on the device keyboard (ENTER or BACKSPACE).")
class RevylNativePressKeyTool(
  @param:LLMDescription("Key to press: 'ENTER' or 'BACKSPACE'.")
  val key: String,
  override val reasoning: String? = null,
) : RevylExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithRevyl(
    client: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Pressing key: $key")
    val result = client.pressKey(key)
    return TrailblazeToolResult.Success(message = "Pressed $key at (${result.x}, ${result.y})")
  }
}
