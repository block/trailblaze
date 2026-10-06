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
import xyz.block.trailblaze.yaml.serializers.CaseInsensitiveEnumSerializer

@Serializable
@TrailblazeToolClass("web_scroll")
@LLMDescription("Scroll the page, or the container given by ref (e.g. a sidebar).")
data class PlaywrightNativeScrollTool(
  @param:LLMDescription("UP, DOWN, LEFT, or RIGHT.")
  val direction: ScrollDirection = ScrollDirection.DOWN,
  @param:LLMDescription("Pixels to scroll (default 500).")
  val amount: Int = 500,
  @param:LLMDescription(
    "Container to scroll: element ID ('e5'), ARIA descriptor ('navigation \"Sidebar\"'), or 'css=<selector>'.",
  )
  val ref: String? = null,
  override val reasoning: String? = null,
  val nodeSelector: TrailblazeNodeSelector? = null,
) : PlaywrightExecutableTool, ReasoningTrailblazeTool {
  override val targetRef: String? get() = ref?.takeIf { it.isNotBlank() }
  override val targetNodeSelector: TrailblazeNodeSelector? get() = nodeSelector
  override fun withNodeSelector(selector: TrailblazeNodeSelector): PlaywrightExecutableTool =
    PlaywrightNativeScrollTool(direction = direction, amount = amount, ref = null, reasoning = reasoning, nodeSelector = selector)

  @Serializable(with = ScrollDirection.Serializer::class)
  enum class ScrollDirection {
    UP,
    DOWN,
    LEFT,
    RIGHT,
    ;

    object Serializer : CaseInsensitiveEnumSerializer<ScrollDirection>(ScrollDirection::class, ScrollDirection.entries)
  }

  override suspend fun executeWithPlaywright(
    page: Page,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val hasTarget = nodeSelector != null || !ref.isNullOrBlank()
    val description = if (hasTarget) {
      PlaywrightExecutableTool.describeTarget(nodeSelector, ref)
    } else {
      "page"
    }
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

      if (hasTarget) {
        val (locator, error) =
          PlaywrightExecutableTool.validateAndResolveRef(page, ref, description, context, nodeSelector)
        if (error != null) return error
        // Position the mouse over the scroll target via `locator.hover()` rather than
        // raw `page.mouse().move(coords)`. Playwright's hover does the full actionability
        // dance (visible, stable, receives events) so the subsequent `page.mouse().wheel`
        // dispatches inside a real container, not on top of an overlay. Wheel itself has
        // no locator-based equivalent — coordinate-driven is the Playwright primitive.
        locator!!.hover()
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
