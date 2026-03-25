package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.host.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Sends a key press event on the Revyl cloud device.
 *
 * Supports ENTER, BACKSPACE, and ANDROID_BACK. The ANDROID_BACK key
 * is routed through [RevylCliClient.back] for correct platform handling.
 */
@Serializable
@TrailblazeToolClass("revyl_pressKey")
@LLMDescription("Press a key on the device keyboard (ENTER, BACKSPACE, or ANDROID_BACK).")
class RevylNativePressKeyTool(
  @param:LLMDescription("Key to press: 'ENTER', 'BACKSPACE', or 'ANDROID_BACK'.")
  val key: String,
  override val reasoning: String? = null,
) : RevylExecutableTool() {

  override suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Pressing key: $key")
    val normalized = key.uppercase()
    val result = if (normalized == "ANDROID_BACK") {
      revylClient.back()
    } else {
      revylClient.pressKey(normalized)
    }
    return TrailblazeToolResult.Success(message = "Pressed $normalized at (${result.x}, ${result.y})")
  }
}
