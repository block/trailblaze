package xyz.block.trailblaze.revyl.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Presses the device back button on the Revyl cloud device.
 *
 * On Android this triggers the system back navigation. On iOS the Revyl
 * agent uses the app's UI-level back navigation (e.g. navigation bar
 * back button) since there is no hardware back key.
 */
@Serializable
@TrailblazeToolClass("revyl_back")
@LLMDescription(
  "Press the device back button to go to the previous screen. " +
    "On Android, triggers the system back. On iOS, navigates back using the app's UI navigation.",
)
class RevylNativeBackTool(
  override val reasoning: String? = null,
) : RevylExecutableTool() {

  override suspend fun executeWithRevyl(
    revylClient: RevylCliClient,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    Console.log("### Pressing back")
    val result = revylClient.back()
    return TrailblazeToolResult.Success(message = "Pressed back at (${result.x}, ${result.y})")
  }
}
