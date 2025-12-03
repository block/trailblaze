package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.ScrollDirection
import maestro.orchestra.ElementSelector
import maestro.orchestra.ScrollUntilVisibleCommand
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

@Serializable
@TrailblazeToolClass("scrollUntilTextIsVisible")
@LLMDescription(
  """
Scrolls the screen in the specified direction until an element containing the provided text becomes visible
in the view hierarchy. The text does not need to be an exact match - it will find elements where the 
provided text appears anywhere within the element's text.

The text argument is required. Only provide additional fields if multiple elements contain the same text.
In this case the additional fields will be used to identify the specific view to expect to be visible while scrolling.
""",
)
class ScrollUntilTextIsVisibleTrailblazeTool(
  @param:LLMDescription("Text to search for while scrolling.")
  val text: String,
  @param:LLMDescription("The element id to scroll until. REQUIRED: 'text' and/or 'id' parameter.")
  val id: String? = null,
  @param:LLMDescription("A 0-based index to disambiguate multiple views with the same text. Default is '0'.")
  val index: Int = 0,
  @param:LLMDescription("Direction to scroll. Default is 'DOWN'.")
  val direction: ScrollDirection = ScrollDirection.DOWN,
  @param:LLMDescription("Percentage of element visible in viewport. Default is '100'.")
  val visibilityPercentage: Int = ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE,
  @param:LLMDescription("If it will attempt to stop scrolling when the element is closer to the screen center. Default is 'false'.")
  val centerElement: Boolean = ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val memory = toolExecutionContext.trailblazeAgent.memory

    /** This detection is fragile - Ticket (TBZ-285) */
    val isOnDeviceAndroidDriver =
      toolExecutionContext.trailblazeAgent::class.simpleName == "AndroidMaestroTrailblazeAgent"
    val scrollDuration = if (isOnDeviceAndroidDriver) {
      /**
       * This matches Maestro's Swipe Implementation with the 400ms duration and is working well on-device Android.
       * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L404
       */
      "400"
    } else {
      ScrollUntilVisibleCommand.DEFAULT_SCROLL_DURATION
    }

    val maestroCommands = listOf(
      ScrollUntilVisibleCommand(
        selector = ElementSelector(
          textRegex = ".*${Regex.escape(memory.interpolateVariables(text))}.*",
          idRegex = id,
          index = if (index == 0) null else index.toString(),
        ),
        /** Maestro's default was 40ms which caused a "fling" behavior and scrolled past elements. */
        scrollDuration = scrollDuration,
        direction = direction,
        visibilityPercentage = visibilityPercentage,
        centerElement = centerElement,
      ),
    )
    return toolExecutionContext.trailblazeAgent.runMaestroCommands(
      maestroCommands = maestroCommands,
      traceId = toolExecutionContext.traceId,
    )
  }
}
