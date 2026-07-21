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
import xyz.block.trailblaze.toolcalls.ReasoningTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.isSuccess
import xyz.block.trailblaze.toolcalls.commands.TrailblazeElementSelectorExt.toMaestroElementSelector
import xyz.block.trailblaze.utils.Ext.toViewHierarchyTreeNode
import xyz.block.trailblaze.viewmatcher.matching.ElementMatcherUsingMaestro
import xyz.block.trailblaze.viewmatcher.matching.asTreeNode
import xyz.block.trailblaze.viewmatcher.models.ElementMatches
import xyz.block.trailblaze.util.Console

@Serializable
@TrailblazeToolClass("scrollUntilTextIsVisible")
@LLMDescription(
  """
Scrolls the screen in the specified direction until a target element becomes visible in the view hierarchy.

Provide EXACTLY ONE target:
- 'text' — substring match: finds elements where this text appears anywhere within the element's text.
- 'textRegex' — anchored full-match regex, used verbatim (the same semantics selector tools use), so
  'Loyalty' matches only "Loyalty" and not "Loyalty Enroll". Use this when you need an exact match.
- (or 'id' alone) — scroll until the element with this id is visible.

At least one of 'text', 'textRegex', or 'id' is required; a call with none is rejected (it would match
every element). If both 'text' and 'textRegex' are given, 'textRegex' takes precedence. Only provide the
additional disambiguation fields (e.g. 'index') when multiple elements match the same target.
""",
)
data class ScrollUntilTextIsVisibleTrailblazeTool(
  @param:LLMDescription("Text to search for while scrolling (substring match). Provide this OR 'textRegex'.")
  val text: String = "",
  @param:LLMDescription(
    "Full-match regex to scroll until visible, used verbatim (anchored, like selector tools). " +
      "Use instead of 'text' for an exact match, e.g. 'Loyalty' won't match 'Loyalty Enroll'.",
  )
  val textRegex: String? = null,
  @param:LLMDescription("The element id to scroll until. At least one of 'text', 'textRegex', or 'id' is required.")
  val id: String? = null,
  @param:LLMDescription("A 0-based index to disambiguate multiple views with the same text. Default is '0'.")
  val index: Int = 0,
  @param:LLMDescription("Direction to scroll. Default is 'DOWN'.")
  @Serializable(with = LenientScrollDirectionSerializer::class)
  val direction: ScrollDirection = ScrollDirection.DOWN,
  @param:LLMDescription("Percentage of element visible in viewport. Default is '100'.")
  val visibilityPercentage: Int = ScrollUntilVisibleCommand.DEFAULT_ELEMENT_VISIBILITY_PERCENTAGE,
  @param:LLMDescription("If it will attempt to stop scrolling when the element is closer to the screen center. Default is 'false'.")
  val centerElement: Boolean = ScrollUntilVisibleCommand.DEFAULT_CENTER_ELEMENT,
  @param:LLMDescription("Which part of the screen to scroll from. Default is 'CENTER'.")
  val scrollStartPosition: TrailblazeScrollStartPosition = TrailblazeScrollStartPosition.CENTER,
  override val reasoning: String? = null,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val trailblazeDriverType = toolExecutionContext.trailblazeDeviceInfo.trailblazeDriverType
    val scrollDuration = scrollDurationFor(trailblazeDriverType)

    // Require an actual target. `text` defaults to "" (so `textRegex` can be used instead), but a
    // call that supplies none of text/textRegex/id would otherwise build `.*\Q\E.*` — a match-all
    // that stops on the first text-bearing element and reports a false "scrolled to it" success.
    // Fail loudly instead. ({{var}}/${var} tokens are resolved by the dispatch boundary —
    // interpolateMemoryInTool — before execute() runs, so a variable that resolves to blank is
    // also caught here.)
    require(hasScrollTarget(text, textRegex, id)) {
      "scrollUntilTextIsVisible requires a target: provide 'text' (substring match), " +
        "'textRegex' (anchored full-match), or 'id'."
    }

    val trailblazeElementSelector = TrailblazeElementSelector(
      textRegex = buildTargetTextRegex(
        text = text,
        textRegex = textRegex,
      ),
      idRegex = id,
      index = if (index == 0) null else index.toString(),
    )
    // Label used only for human-readable success messages. Prefer the anchored regex when set.
    val targetLabel = textRegex?.takeIf { it.isNotBlank() } ?: text
    val scrollCommand = ScrollUntilVisibleCommand(
      selector = trailblazeElementSelector.toMaestroElementSelector(),
      /** Maestro's default was 40ms which caused a "fling" behavior and scrolled past elements. */
      scrollDuration = scrollDuration,
      direction = direction,
      visibilityPercentage = visibilityPercentage,
      centerElement = centerElement,
    )

    // The accessibility driver always uses the manual scroll loop because Maestro's
    // ScrollUntilVisibleCommand requires a Maestro Driver instance, which the accessibility
    // path bypasses entirely. Non-center start positions also require the manual loop since
    // Maestro's built-in command doesn't support custom scroll regions.
    val useManualScrollLoop = scrollStartPosition != TrailblazeScrollStartPosition.CENTER ||
      trailblazeDriverType == TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

    return if (!useManualScrollLoop) {
      // For default scrolling with Maestro-compatible drivers, delegate to Maestro's
      // ScrollUntilVisibleCommand which handles the scroll-check loop internally.
      val result = toolExecutionContext.trailblazeAgent.runMaestroCommands(
        maestroCommands = listOf(scrollCommand),
        traceId = toolExecutionContext.traceId,
      )
      if (result.isSuccess()) {
        TrailblazeToolResult.Success(
          message = "Scrolled ${direction.name} until '$targetLabel' visible",
        )
      } else {
        result
      }
    } else {
      scrollUntilVisibleWithStartPosition(
        toolExecutionContext = toolExecutionContext,
        trailblazeElementSelector = trailblazeElementSelector,
        maestroCommand = scrollCommand,
        scrollStartPosition = scrollStartPosition,
        targetLabel = targetLabel,
      )
    }
  }


  private suspend fun scrollUntilVisibleWithStartPosition(
    toolExecutionContext: TrailblazeToolExecutionContext,
    trailblazeElementSelector: TrailblazeElementSelector,
    maestroCommand: ScrollUntilVisibleCommand,
    scrollStartPosition: TrailblazeScrollStartPosition,
    targetLabel: String,
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
            rootTreeNode = screenState.viewHierarchy,
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
          Console.log("Scrolling try count: $retryCenterCount, DeviceWidth: ${widthGrid}, DeviceHeight: ${heightGrid}")
          Console.log("Element bounds: ${bounds}")
          Console.log("Visibility Percent: $visibility")
          Console.log("Command centerElement: $maestroCommand.centerElement")
          Console.log("visibilityPercentageNormalized: ${maestroCommand.visibilityPercentageNormalized}")

          if (maestroCommand.centerElement && visibility > 0.1 && retryCenterCount <= maxRetryCenterCount) {
            if (maestroUiElement.isElementNearScreenCenter(direction, widthGrid, heightGrid)) {
              return TrailblazeToolResult.Success(
                message = "Scrolled ${direction.name} until '$targetLabel' visible",
              )
            }
            retryCenterCount++
          } else if (visibility >= maestroCommand.visibilityPercentageNormalized) {
            return TrailblazeToolResult.Success(
              message = "Scrolled ${direction.name} until '$targetLabel' visible",
            )
          }
        }
      } catch (ignored: MaestroException.ElementNotFound) {
        Console.log("Error: $ignored")
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

    val debugMessage = buildScrollFailureMessage(maestroCommand)
    throw MaestroException.ElementNotFound(
      message = "No visible element found: ${maestroCommand.selector.description()}",
      hierarchyRoot = screenState.viewHierarchy.asTreeNode(),
      debugMessage = debugMessage,
    )
  }

  companion object {
    /**
     * Resolves the effective full-match regex the scroll loop searches for. Extracted as a pure
     * function so the substring-vs-anchored contract is unit-testable without a Maestro driver or
     * agent memory (memory tokens are resolved at the dispatch boundary before execution).
     *
     * - [textRegex] (non-blank): used verbatim, giving the same full (anchored) match that selector
     *   tools like `tapOnElementBySelector` get from Maestro — `Loyalty` matches only "Loyalty", not
     *   "Loyalty Enroll".
     * - [text] otherwise: regex-escaped and wrapped in `.*…*.` for a substring (contains) match,
     *   preserving the historical behavior for every existing caller.
     */
    internal fun buildTargetTextRegex(text: String, textRegex: String?): String =
      if (!textRegex.isNullOrBlank()) {
        textRegex
      } else {
        ".*${Regex.escape(text)}.*"
      }

    /**
     * True when the call carries a real scroll target — at least one of [text] (substring),
     * [textRegex] (anchored), or [id] is non-blank. Extracted as a pure predicate so the
     * "reject match-all / empty-target calls" guard is unit-testable without a device (mirrors
     * [buildTargetTextRegex] / [pollForConsecutiveStable]). Memory tokens are resolved at the
     * dispatch boundary before execution, so a `{{var}}` that resolves to blank is also rejected.
     */
    internal fun hasScrollTarget(text: String, textRegex: String?, id: String?): Boolean =
      text.isNotBlank() || !textRegex.isNullOrBlank() || !id.isNullOrBlank()

    /**
     * Builds the failure message for the scroll-until-visible loop. Extracted into a pure
     * function so unit tests can lock in the LLM-facing wording (the leading "could not
     * find" line and the `objectiveStatus(FAILED)` give-up signal) without spinning up
     * the full Maestro driver fixture.
     *
     * Order matters: the LLM-actionable advice must appear BEFORE the framework-tuning
     * section because LLMs lean on the head of the message when deciding next steps.
     * Putting "consider calling objectiveStatus(FAILED)" up front is what unblocks the
     * `verifySelfHealFailsGracefully` stuck pattern.
     */
    internal fun buildScrollFailureMessage(
      maestroCommand: ScrollUntilVisibleCommand,
    ): String = buildString {
      appendLine(
        "Could not find an element matching '${maestroCommand.selector.description()}' on this screen " +
          "after scrolling ${maestroCommand.direction.name} to the maximum allowed time."
      )
      appendLine(
        "If you have already tried scrolling in the opposite direction without success, the element " +
          "is likely not present on this screen. Consider calling `objectiveStatus(FAILED)` and " +
          "reporting that the element could not be found."
      )
      appendLine()
      appendLine("--- Framework tuning hints (only if you control the trail YAML, not for LLM use) ---")
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

    fun scrollDurationFor(trailblazeDriverType: TrailblazeDriverType): String =
      when (trailblazeDriverType) {
        /**
         * This matches Maestro's Swipe Implementation with the 400ms duration and is working well on-device Android.
         * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L404
         *
         * The default (40ms) causes a "fling" that overshoots elements. The accessibility driver
         * also uses the manual scroll loop, so it needs the same 400ms duration.
         */
        TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION,
        TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY -> "400"
        else -> ScrollUntilVisibleCommand.DEFAULT_SCROLL_DURATION
      }

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
