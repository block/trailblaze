package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("web_press_key")
@LLMDescription(
  """
Press a keyboard key or key combination.
Supports keys like 'Enter', 'Tab', 'Escape', 'Backspace', 'ArrowDown', 'ArrowUp',
and combinations like 'Control+A', 'Meta+C', 'Shift+Tab'.
""",
)
class PlaywrightNativePressKeyTool(
  @param:LLMDescription(
    "The key or key combination to press (e.g., 'Enter', 'Tab', 'Control+A', 'Meta+C').",
  )
  val key: String,
  override val reasoning: String? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Pressing key: $key")
    return try {
      page.keyboard().press(key)
      TrailblazeToolResult.Success(message = "Pressed key '$key'.")
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Key press failed for '$key': ${e.message}")
    }
  }
}
