package xyz.block.trailblaze.playwright.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import com.microsoft.playwright.Page
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("playwright_scroll")
@LLMDescription(
  """
Scroll the page or a specific container in the specified direction.
When ref is provided, scrolls within that container (e.g., a sidebar or panel) by moving the
mouse to its center first. When ref is omitted, scrolls the full page.
""",
)
class PlaywrightNativeScrollTool(
  @param:LLMDescription("Direction to scroll. UP/DOWN for vertical, LEFT/RIGHT for horizontal.")
  val direction: ScrollDirection = ScrollDirection.DOWN,
  @param:LLMDescription("Number of pixels to scroll. Defaults to 500.")
  val amount: Int = 500,
  @param:LLMDescription(
    "Element reference for the container to scroll within: ARIA descriptor " +
      "(e.g., 'navigation \"Sidebar\"'), element ID (e.g., 'e5'), " +
      "or CSS selector with css= prefix (e.g., 'css=#scrollable-panel'). " +
      "When omitted, scrolls the full page.",
  )
  val ref: String = "",
  @param:LLMDescription("Human-readable description of the container being scrolled, for logging.")
  val element: String = "",
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val elementDescriptor: String? get() = element.ifBlank { null }
  override val targetRef: String? get() = ref.ifBlank { null }
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeScrollTool(direction = direction, amount = amount, ref = ref, element = element, reasoning = reasoning, nodeSelector = selector)

  @Serializable
  enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
  }

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val description = element.ifBlank { ref.ifBlank { "page" } }
    reasoning?.let { Console.log("### Reasoning: $it") }
    Console.log("### Scrolling $direction by $amount pixels on '$description'")
    return try {
      val (deltaX, deltaY) =
        when (direction) {
          ScrollDirection.DOWN -> 0.0 to amount.toDouble()
          ScrollDirection.UP -> 0.0 to -amount.toDouble()
          ScrollDirection.RIGHT -> amount.toDouble() to 0.0
          ScrollDirection.LEFT -> -amount.toDouble() to 0.0
        }

      if (ref.isNotBlank()) {
        val (locator, error) =
          PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
        if (error != null) return error
        val box =
          locator!!.first().boundingBox()
            ?: return TrailblazeToolResult.Error.ExceptionThrown(
              "Could not determine bounding box for '$description'.",
            )
        val centerX = box.x + box.width / 2
        val centerY = box.y + box.height / 2
        page.mouse().move(centerX, centerY)
      }

      page.mouse().wheel(deltaX, deltaY)
      TrailblazeToolResult.Success(
        message = "Scrolled $direction by $amount pixels on '$description'."
      )
    } catch (e: Exception) {
      TrailblazeToolResult.Error.ExceptionThrown("Scroll failed on '$description': ${e.message}")
    }
  }
}
