package xyz.block.trailblaze.toolcalls.commands

import ai.koog.agents.core.tools.annotations.LLMDescription
import kotlinx.serialization.Serializable
import maestro.Bounds
import maestro.MaestroException
import maestro.Point
import maestro.ScrollDirection
import maestro.SwipeDirection
import maestro.TreeNode
import maestro.UiElement
import maestro.orchestra.ScrollUntilVisibleCommand
import maestro.orchestra.SwipeCommand
import maestro.toSwipeDirection
import xyz.block.trailblaze.api.TrailblazeElementSelector
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.maestro.TrailblazeScrollStartPosition
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewmatcher.matching.ElementMatcherUsingMaestro
import xyz.block.trailblaze.viewmatcher.matching.asTreeNode
import xyz.block.trailblaze.viewmatcher.models.ElementMatches

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
  @param:LLMDescription("Which part of the screen to scroll from. Default is 'CENTER'.")
  val scrollStartPosition: TrailblazeScrollStartPosition = TrailblazeScrollStartPosition.CENTER,
) : ExecutableTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val memory = toolExecutionContext.trailblazeAgent.memory
    val trailblazeDriverType = toolExecutionContext.trailblazeDeviceInfo.trailblazeDriverType
    val scrollDuration = if (trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION) {
        /**
         * This matches Maestro's Swipe Implementation with the 400ms duration and is working well on-device Android.
         * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L404
         */
        "400"
      } else {
        ScrollUntilVisibleCommand.DEFAULT_SCROLL_DURATION
      }

    val trailblazeElementSelector = TrailblazeElementSelector(
      textRegex = ".*${Regex.escape(memory.interpolateVariables(text))}.*",
      idRegex = id,
      index = if (index == 0) null else index.toString(),
    )
    val scrollCommand = ScrollUntilVisibleCommand(
      selector = trailblazeElementSelector.toMaestroElementSelector(),
      /** Maestro's default was 40ms which caused a "fling" behavior and scrolled past elements. */
      scrollDuration = scrollDuration,
      direction = direction,
      visibilityPercentage = visibilityPercentage,
      centerElement = centerElement,
    )

    return if (scrollStartPosition == TrailblazeScrollStartPosition.CENTER) {
      // For default scrolling, we don't need to calculate custom start positions
      toolExecutionContext.trailblazeAgent.runMaestroCommands(
        maestroCommands = listOf(scrollCommand),
        traceId = toolExecutionContext.traceId
      )
    } else {
      scrollUntilVisibleWithStartPosition(
        toolExecutionContext = toolExecutionContext,
        trailblazeElementSelector = trailblazeElementSelector,
        maestroCommand = scrollCommand,
        scrollStartPosition = scrollStartPosition,
      )
    }
  }


  private suspend fun scrollUntilVisibleWithStartPosition(
    toolExecutionContext: TrailblazeToolExecutionContext,
    trailblazeElementSelector: TrailblazeElementSelector,
    maestroCommand: ScrollUntilVisibleCommand,
    scrollStartPosition: TrailblazeScrollStartPosition,
  ): TrailblazeToolResult {
    var screenState = requireNotNull(toolExecutionContext.screenState) {
      "Screen state must be available before scrolling."
    }
    var widthGrid = screenState.deviceWidth
    var heightGrid = screenState.deviceHeight

    val endTime = System.currentTimeMillis() + maestroCommand.timeout.toLong()
    val direction = maestroCommand.direction.toSwipeDirection()
    var retryCenterCount = 0
    val maxRetryCenterCount = 4 // for when the list is no longer scrollable (last element) but the element is visible

    do {
      try {
        screenState = toolExecutionContext.screenStateProvider?.invoke() ?: screenState
        widthGrid = screenState.deviceWidth
        heightGrid = screenState.deviceHeight
        val tbElementMatches: ElementMatches =
          ElementMatcherUsingMaestro.getMatchingElementsFromSelector(
            rootTreeNode = screenState.viewHierarchyOriginal,
            trailblazeDevicePlatform = toolExecutionContext.trailblazeDeviceInfo.platform,
            trailblazeElementSelector = trailblazeElementSelector,
            widthPixels = widthGrid,
            heightPixels = heightGrid,
          )
        val element: TreeNode? = when (tbElementMatches) {
          is ElementMatches.MultipleMatches -> tbElementMatches.nodes.first()
          is ElementMatches.NoMatches -> null
          is ElementMatches.SingleMatch -> tbElementMatches.node
        }
        val bounds = element?.toViewHierarchyTreeNode()?.bounds
        if (bounds != null) {
          val maestroBounds = Bounds(
            x = bounds.x1,
            y = bounds.y1,
            width = bounds.width,
            height = bounds.height
          )

          val maestroUiElement = UiElement(
            treeNode = element,
            bounds = maestroBounds
          )


          val visibility: Double = maestroUiElement.getVisiblePercentage(
            widthGrid, heightGrid
          )
          println("Scrolling try count: $retryCenterCount, DeviceWidth: ${widthGrid}, DeviceWidth: ${heightGrid}")
          println("Element bounds: ${bounds}")
          println("Visibility Percent: $visibility")
          println("Command centerElement: $maestroCommand.centerElement")
          println("visibilityPercentageNormalized: ${maestroCommand.visibilityPercentageNormalized}")

          if (maestroCommand.centerElement && visibility > 0.1 && retryCenterCount <= maxRetryCenterCount) {
            if (maestroUiElement.isElementNearScreenCenter(direction, widthGrid, heightGrid)) {
              return TrailblazeToolResult.Success
            }
            retryCenterCount++
          } else if (visibility >= maestroCommand.visibilityPercentageNormalized) {
            return TrailblazeToolResult.Success
          }
        }
      } catch (ignored: MaestroException.ElementNotFound) {
        println("Error: $ignored")
      }

      val durationMs = maestroCommand.scrollDuration.toLong()
      val waitToSettleTimeoutMs = maestroCommand.waitToSettleTimeoutMs

      val swipeCommand = if (scrollStartPosition == TrailblazeScrollStartPosition.CENTER) {
        val center = Point(x = widthGrid / 2, y = heightGrid / 2)
        SwipeCommand(
          direction = direction,
          startPoint = center,
          duration = durationMs,
        )
      } else {
        val (startRelative, endRelative) = relativeScrollStartPoints(scrollStartPosition, direction)
        SwipeCommand(
          direction = direction,
          startRelative = startRelative.let { (x, y) -> "$x,$y" },
          endRelative = endRelative.let { (x, y) -> "$x,$y" },
          waitToSettleTimeoutMs = waitToSettleTimeoutMs,
          duration = durationMs,
        )
      }

      val trailblazeToolResult: TrailblazeToolResult =
        toolExecutionContext.trailblazeAgent.runMaestroCommands(
          maestroCommands = listOf(swipeCommand),
          traceId = toolExecutionContext.traceId
        )
      if (trailblazeToolResult !is TrailblazeToolResult.Success) {
        return trailblazeToolResult
      }

    } while (System.currentTimeMillis() < endTime)

    val debugMessage = buildString {
      appendLine("Could not find a visible element matching selector: ${maestroCommand.selector.description()}")
      appendLine("Tip: Try adjusting the following settings to improve detection:")
      appendLine("- `timeout`: current = ${maestroCommand.timeout}ms → Increase if you need more time to find the element")
      val originalSpeed = maestroCommand.originalSpeedValue?.toIntOrNull()
      val speedAdvice = if (originalSpeed != null && originalSpeed > 50) {
        "Reduce for slower, more precise scrolling to avoid overshooting elements"
      } else {
        "Increase for faster scrolling if element is far away"
      }
      appendLine("- `speed`: current = ${maestroCommand.originalSpeedValue} (0-100 scale) → $speedAdvice")
      val waitSettleAdvice = if (maestroCommand.waitToSettleTimeoutMs == null) {
        "Set this value (e.g., 500ms) if your UI updates frequently between scrolls"
      } else {
        "Increase if your UI needs more time to update between scrolls"
      }
      val waitToTimeSettleMessage = if (maestroCommand.waitToSettleTimeoutMs != null) {
        "${maestroCommand.waitToSettleTimeoutMs}ms"
      } else {
        "Not defined"
      }
      appendLine("- `waitToSettleTimeoutMs`: current = $waitToTimeSettleMessage → $waitSettleAdvice")
      appendLine("- `visibilityPercentage`: current = ${maestroCommand.visibilityPercentage}% → Lower this value if you want to detect partially visible elements")
      val centerAdvice = if (maestroCommand.centerElement) {
        "Disable if you don't need the element to be centered after finding it"
      } else {
        "Enable if you want the element to be centered after finding it"
      }
      appendLine("- `centerElement`: current = ${maestroCommand.centerElement} → $centerAdvice")
    }
    throw MaestroException.ElementNotFound(
      message = "No visible element found: ${maestroCommand.selector.description()}",
      hierarchyRoot = screenState.viewHierarchyOriginal.asTreeNode(),
      debugMessage = debugMessage,
    )
  }

  companion object {
    private fun relativeScrollStartPoints(
      scrollStartPosition: TrailblazeScrollStartPosition,
      direction: SwipeDirection,
    ): Pair<Point, Point> {
      val (startX, endX) = when (direction) {
        SwipeDirection.LEFT -> 85 to 15
        SwipeDirection.RIGHT -> 15 to 85
        SwipeDirection.UP,
        SwipeDirection.DOWN,
          -> 50 to 50
      }

      val (startY, endY) = when (direction) {
        SwipeDirection.UP -> when (scrollStartPosition) {
          TrailblazeScrollStartPosition.TOP -> 40 to 15
          TrailblazeScrollStartPosition.BOTTOM -> 85 to 60
          TrailblazeScrollStartPosition.CENTER -> 85 to 15
        }

        SwipeDirection.DOWN -> when (scrollStartPosition) {
          TrailblazeScrollStartPosition.TOP -> 15 to 40
          TrailblazeScrollStartPosition.BOTTOM -> 60 to 85
          TrailblazeScrollStartPosition.CENTER -> 15 to 85
        }

        SwipeDirection.LEFT,
        SwipeDirection.RIGHT,
          -> {
          val y = when (scrollStartPosition) {
            TrailblazeScrollStartPosition.TOP -> 25
            TrailblazeScrollStartPosition.BOTTOM -> 75
            TrailblazeScrollStartPosition.CENTER -> 50
          }
          y to y
        }
      }
      return Point(startX, startY) to Point(endX, endY)
    }
  }
}
