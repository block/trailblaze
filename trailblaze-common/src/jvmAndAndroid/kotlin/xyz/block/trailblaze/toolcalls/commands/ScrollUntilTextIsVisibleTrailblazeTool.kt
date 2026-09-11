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
import kotlin.math.abs

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
  @param:LLMDescription(
    "If true, keeps scrolling until the found element is near the screen center instead of " +
      "stopping at first visibility — so a tab bar, sticky footer or promo banner cannot " +
      "intercept a tap aimed at it. Omit to use the driver-tuned default (true for vertical " +
      "scrolls on the Android accessibility driver, which needs the extra travel; false " +
      "elsewhere, including horizontal scrolls, where a correction can carry the target off " +
      "the opposite edge).",
  )
  val centerElement: Boolean? = null,
  @param:LLMDescription("Which part of the screen to scroll from. Default is 'CENTER'.")
  val scrollStartPosition: TrailblazeScrollStartPosition = TrailblazeScrollStartPosition.CENTER,
  @param:LLMDescription(
    "Duration in milliseconds of each scroll swipe gesture. Lower is a faster swipe. Omit to use " +
      "the driver-tuned default (400ms on Android on-device). Set a lower value (e.g. '200') on " +
      "screens where a slower swipe is misread as a tap.",
  )
  val scrollDurationMs: Int? = null,
  override val reasoning: String? = null,
) : ExecutableTrailblazeTool, ReasoningTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val trailblazeDriverType = toolExecutionContext.trailblazeDeviceInfo.trailblazeDriverType
    val scrollDuration = resolveScrollDuration(scrollDurationMs, trailblazeDriverType)
    val resolvedCenterElement = resolveCenterElement(centerElement, trailblazeDriverType, direction)

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
      centerElement = resolvedCenterElement,
    )

    // Non-center start positions also require the manual loop since Maestro's built-in command
    // doesn't support custom scroll regions.
    val useManualScrollLoop = scrollStartPosition != TrailblazeScrollStartPosition.CENTER ||
      trailblazeDriverType.usesManualScrollLoop

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
    var lastCorrectedPosition: Int? = null
    var consecutiveStalls = 0

    // Visibility from the most recent hierarchy read, or null when that read matched nothing.
    //
    // One corrective swipe can still land between that read and the deadline, so this is the last
    // KNOWN position rather than a guaranteed current one. Re-reading after the swipe would cost a
    // hierarchy read on every timeout and would leave a scroll that was visible the whole time
    // failing for want of budget, which is the defect this exists to prevent — so whether the
    // reading can still be trusted is decided from the direction instead, in
    // [timedOutTargetIsAcceptable].
    //
    // Null and 0.0 must stay distinguishable: Maestro floors `visibilityPercentageNormalized` to
    // 0.0 for every percentage below 100 (it divides ints), so "no match" would otherwise clear
    // the bar.
    var lastVisibility: Double? = null

    // Whether a swipe has landed since the reading above was taken, which is what makes that
    // reading potentially stale.
    var swipedSinceLastRead = false

    do {
      try {
        // Reset per iteration: a target that has scrolled out of view must not be reported as
        // visible on timeout using a reading from an earlier pass.
        lastVisibility = null
        swipedSinceLastRead = false
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
          lastVisibility = visibility
          Console.log("Scrolling try count: $retryCenterCount, DeviceWidth: ${widthGrid}, DeviceHeight: ${heightGrid}")
          Console.log("Element bounds: ${bounds}")
          Console.log("Visibility Percent: $visibility")
          Console.log("Command centerElement: $maestroCommand.centerElement")
          Console.log("visibilityPercentageNormalized: ${maestroCommand.visibilityPercentageNormalized}")

          if (maestroCommand.centerElement && visibility > 0.1 && retryCenterCount <= maxRetryCenterCount) {
            val axisPosition = scrollAxisCenter(direction, maestroBounds)
            val centered = maestroUiElement.isElementNearScreenCenter(direction, widthGrid, heightGrid)
            consecutiveStalls = stallStreak(
              previousStreak = consecutiveStalls,
              previousPosition = lastCorrectedPosition,
              currentPosition = axisPosition,
              direction = direction,
              widthGrid = widthGrid,
              heightGrid = heightGrid,
            )
            Console.log("Centered: $centered, consecutiveStalls: $consecutiveStalls, axisPosition: $axisPosition")
            val stalledAtAnAcceptablePosition = stalledTargetIsAcceptable(
              consecutiveStalls = consecutiveStalls,
              visibility = visibility,
              visibilityPercentageNormalized = maestroCommand.visibilityPercentageNormalized,
            )
            if (centered || stalledAtAnAcceptablePosition) {
              return TrailblazeToolResult.Success(
                message = "Scrolled ${direction.name} until '$targetLabel' visible",
              )
            }
            lastCorrectedPosition = axisPosition
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

      // Stop before dispatching a swipe whose result there is no time left to read. The loop would
      // exit immediately after it, so that swipe could only move the target away from the position
      // the timeout branch below reports on.
      if (System.currentTimeMillis() >= endTime) break

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
      swipedSinceLastRead = true

    } while (System.currentTimeMillis() < endTime)

    // Centering is best-effort; visibility is the contract. Running out of time before the target
    // could be centered must not fail a scroll that would have succeeded without centering — that
    // would let the driver-tuned default turn a working step into "element not found".
    if (
      timedOutTargetIsAcceptable(
        lastVisibility = lastVisibility,
        visibilityPercentageNormalized = maestroCommand.visibilityPercentageNormalized,
        swipedSinceLastRead = swipedSinceLastRead,
        isVerticalScroll = isVerticalScroll(maestroCommand.direction),
      )
    ) {
      return TrailblazeToolResult.Success(
        message = "Scrolled ${direction.name} until '$targetLabel' visible (timed out before centering)",
      )
    }

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
     * - [text] otherwise: regex-escaped and wrapped in `.*….*` for a substring (contains) match,
     *   preserving the historical behavior for every existing caller.
     *
     * Public, together with [hasTextTarget] and [hasScrollTarget], because drivers that dispatch
     * this tool onto their own backends instead of [execute] (the in-process ANDROID_TEST adapter)
     * must read a recording's target exactly as this tool does. A blank `textRegex` in particular
     * falls back to `text` here; a driver deciding that for itself once built a regex that matched
     * only empty text.
     */
    fun buildTargetTextRegex(text: String, textRegex: String?): String =
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
    fun hasScrollTarget(text: String, textRegex: String?, id: String?): Boolean =
      hasTextTarget(text, textRegex) || !id.isNullOrBlank()

    /**
     * True when the call targets an element by its text — [text] (substring) or [textRegex]
     * (anchored) is non-blank — so [buildTargetTextRegex] has something real to build from. False
     * means the only possible target is `id`, or (see [hasScrollTarget]) there is none.
     */
    fun hasTextTarget(text: String, textRegex: String?): Boolean =
      text.isNotBlank() || !textRegex.isNullOrBlank()

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

    /**
     * Resolves the swipe duration (in milliseconds) the scroll loop uses. Extracted as a pure
     * function so the "caller override wins, else driver-tuned default" contract is unit-testable
     * without a device (mirrors [buildTargetTextRegex] / [hasScrollTarget]).
     *
     * - [scrollDurationMs] (non-null): the caller's explicit per-swipe duration, e.g. a faster
     *   200ms swipe on screens where the driver default is misread as a tap. Must be positive:
     *   a zero/negative duration would flow into [SwipeCommand.duration] as an invalid gesture.
     * - otherwise: the driver-tuned default from [scrollDurationFor], preserving prior behavior for
     *   every existing caller.
     */
    internal fun resolveScrollDuration(
      scrollDurationMs: Int?,
      trailblazeDriverType: TrailblazeDriverType,
    ): String {
      require(scrollDurationMs == null || scrollDurationMs > 0) {
        "scrollDurationMs must be > 0 when provided (was $scrollDurationMs)."
      }
      return scrollDurationMs?.toString() ?: scrollDurationFor(trailblazeDriverType)
    }

    /**
     * Resolves whether the scroll loop keeps scrolling until the found element is near the screen
     * center. A caller-supplied value always wins; otherwise the driver declares its own default
     * via [TrailblazeDriverType.centersScrollTargetByDefault] (mirrors [resolveScrollDuration]'s
     * caller-override-else-driver-default contract) — and only for a vertical scroll.
     *
     * The default excludes horizontal scrolls because a corrective swipe there cannot be undone.
     * Maestro's gate rejects a target past 70% of the screen along the scroll axis, and the
     * driver's horizontal stroke moves content 80% of the screen WIDTH, so a target resting
     * between those two lands off the opposite edge — out of the hierarchy entirely, where every
     * further swipe pushes it further away and the loop can only time out as "element not found".
     * Vertical corrections cannot overshoot: their stroke moves content 40% of the screen HEIGHT
     * against the same 70% gate, so a corrected target always lands between 30% and 60%. The
     * driver-tuned default is about vertical travel anyway — the per-swipe shortfall behind it was
     * measured on vertical scrolls. An explicit `centerElement: true` is still honored in every
     * direction.
     */
    internal fun resolveCenterElement(
      centerElement: Boolean?,
      trailblazeDriverType: TrailblazeDriverType,
      direction: ScrollDirection,
    ): Boolean = centerElement
      ?: (trailblazeDriverType.centersScrollTargetByDefault && isVerticalScroll(direction))

    /**
     * Whether [direction] moves content along the vertical axis. A `when` rather than a set
     * membership check so a new [ScrollDirection] has to be classified explicitly instead of
     * silently inheriting the horizontal-overshoot risk described on [resolveCenterElement].
     */
    internal fun isVerticalScroll(direction: ScrollDirection): Boolean = when (direction) {
      ScrollDirection.UP, ScrollDirection.DOWN -> true
      ScrollDirection.LEFT, ScrollDirection.RIGHT -> false
    }

    /**
     * How far the target must travel between two corrective swipes for a third to be worth trying,
     * as a fraction of the screen along the scroll axis.
     */
    internal const val STALL_THRESHOLD_FRACTION = 0.01

    /**
     * How many no-movement readings in a row end the loop. A dropped swipe looks exactly like a
     * list that has hit its end, and stopping early on one leaves the target un-centered — the
     * defect centering exists to prevent. Two in a row costs one extra swipe on a genuinely stuck
     * list and still ends it well short of [maxRetryCenterCount].
     */
    internal const val STALLS_BEFORE_GIVING_UP = 2

    /** The target's center along the axis the scroll moves it on. */
    internal fun scrollAxisCenter(
      direction: SwipeDirection,
      bounds: Bounds,
    ): Int = when (direction) {
      SwipeDirection.UP, SwipeDirection.DOWN -> bounds.y + bounds.height / 2
      SwipeDirection.LEFT, SwipeDirection.RIGHT -> bounds.x + bounds.width / 2
    }

    /**
     * Whether the last corrective swipe failed to move the target, meaning the list has hit its end
     * and further swipes cannot improve the position. [maxRetryCenterCount] alone only bounds that
     * case; detecting it ends the loop on the first wasted swipe instead of the fifth.
     */
    internal fun hasStalled(
      previousPosition: Int?,
      currentPosition: Int,
      direction: SwipeDirection,
      widthGrid: Int,
      heightGrid: Int,
    ): Boolean {
      if (previousPosition == null) return false
      val axisLength = when (direction) {
        SwipeDirection.UP, SwipeDirection.DOWN -> heightGrid
        SwipeDirection.LEFT, SwipeDirection.RIGHT -> widthGrid
      }
      return abs(currentPosition - previousPosition) <= axisLength * STALL_THRESHOLD_FRACTION
    }

    /**
     * How many no-movement readings the loop has now seen in a row. Any real movement resets the
     * count, so a single dropped swipe — which is indistinguishable from a list at its end — cannot
     * end the loop on its own.
     */
    internal fun stallStreak(
      previousStreak: Int,
      previousPosition: Int?,
      currentPosition: Int,
      direction: SwipeDirection,
      widthGrid: Int,
      heightGrid: Int,
    ): Int = if (hasStalled(previousPosition, currentPosition, direction, widthGrid, heightGrid)) {
      previousStreak + 1
    } else {
      0
    }

    /**
     * Whether a scroll whose list has stopped moving should be reported as a success.
     *
     * A stall ends the centering attempt early, which is the one exit that can happen while the
     * target is still clipped — the end of a list holds it wherever it landed, often a sliver at
     * the screen edge. So the shortcut has to clear
     * [ScrollUntilVisibleCommand.visibilityPercentageNormalized] itself. Declining it is not a
     * failure: the loop keeps retrying and falls to the same bar on the way out, which is what it
     * did before this early exit existed. Unlike the centered exit, being stalled says nothing
     * about where the target ended up.
     */
    internal fun stalledTargetIsAcceptable(
      consecutiveStalls: Int,
      visibility: Double,
      visibilityPercentageNormalized: Double,
    ): Boolean = consecutiveStalls >= STALLS_BEFORE_GIVING_UP &&
      visibility >= visibilityPercentageNormalized

    /**
     * Whether a scroll that ran out of time should still be reported as a success.
     *
     * Centering is best-effort — it buys a tappable position, not a different definition of
     * "found". The contract is [ScrollUntilVisibleCommand.visibilityPercentageNormalized], the
     * same bar a non-centering scroll clears, so a target already meeting it must not be reported
     * as missing just because the loop ran out of budget before centering it. Without this, turning
     * centering on by default could fail a step that passed with it off.
     *
     * Null [lastVisibility] means that read matched nothing, which is never acceptable — Maestro
     * floors [ScrollUntilVisibleCommand.visibilityPercentageNormalized] to 0.0 for every percentage
     * below 100 (it divides two ints), so comparing a "not found" reading numerically would report
     * success for a target that was never on screen.
     *
     * When a swipe landed after that read, whether the reading can still be trusted depends on the
     * axis. A vertical correction is bounded — Maestro's gate rejects a target past 70% of the
     * screen and the stroke moves content 40% of screen height, so the target lands between 30%
     * and 60%, still on screen and no less visible. A horizontal one is not: the stroke moves 80%
     * of screen WIDTH against the same gate, so it can carry the target off the opposite edge. The
     * default already excludes horizontal centering for that reason, but an explicit
     * `centerElement: true` still reaches here on a horizontal scroll, and that combination must
     * not vouch for a position the target may have already left.
     */
    internal fun timedOutTargetIsAcceptable(
      lastVisibility: Double?,
      visibilityPercentageNormalized: Double,
      swipedSinceLastRead: Boolean,
      isVerticalScroll: Boolean,
    ): Boolean = lastVisibility != null &&
      lastVisibility >= visibilityPercentageNormalized &&
      (!swipedSinceLastRead || isVerticalScroll)

    /**
     * 400ms matches Maestro's Swipe Implementation duration and is working well on-device Android.
     * https://github.com/mobile-dev-inc/Maestro/blob/0a38a9468cb769ecbc1edc76974fd2f8a8b0b64e/maestro-client/src/main/java/maestro/drivers/AndroidDriver.kt#L404
     *
     * The default (40ms) causes a "fling" that overshoots elements. Every on-device Android driver
     * and every host-native iOS driver needs the same 400ms duration — a wider set than the drivers
     * on the manual loop, since on-device Maestro still overshoots at 40ms.
     */
    fun scrollDurationFor(trailblazeDriverType: TrailblazeDriverType): String =
      if (trailblazeDriverType.executesToolsOnDevice || trailblazeDriverType.hostNativeSimulatorDriver) {
        "400"
      } else {
        ScrollUntilVisibleCommand.DEFAULT_SCROLL_DURATION
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
