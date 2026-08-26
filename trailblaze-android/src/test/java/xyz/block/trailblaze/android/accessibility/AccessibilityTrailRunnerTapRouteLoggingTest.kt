package xyz.block.trailblaze.android.accessibility

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import xyz.block.trailblaze.api.AgentDriverAction
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TapDispatchRoute
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult

/**
 * Pins the tap-route half of the driver log: whatever route
 * [AccessibilityDeviceManager.execute] reports has to reach the logged [AgentDriverAction], because
 * that log is the only artifact a reviewer reads after a farm run.
 *
 * The case that motivates this: a recorded tap whose selector matches nothing still reports
 * success by tapping the coordinates captured at record time, so a green replay says nothing about
 * whether its selectors resolve. The route is what makes that visible.
 */
class AccessibilityTrailRunnerTapRouteLoggingTest {

  private val selector = TrailblazeNodeSelector(
    androidAccessibility = DriverNodeMatch.AndroidAccessibility(textRegex = "Checkout"),
  )

  private fun mapTap(
    action: AccessibilityAction.TapOnElement,
    executionResult: AccessibilityDeviceManager.ExecutionResult,
  ): AgentDriverAction = AccessibilityTrailRunner.mapToAgentDriverAction(
    action = action,
    executionResult = executionResult,
    toolResult = TrailblazeToolResult.Success(),
  )

  @Test
  fun `a tap delivered at recorded coordinates after a selector miss is logged as such`() {
    val logged = mapTap(
      action = AccessibilityAction.TapOnElement(
        nodeSelector = selector,
        fallbackX = 540,
        fallbackY = 1790,
      ),
      executionResult = AccessibilityDeviceManager.ExecutionResult(
        resolvedX = 540,
        resolvedY = 1790,
        dispatchRoute = TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      ),
    )

    val tap = assertIs<AgentDriverAction.TapPoint>(logged)
    assertEquals(
      TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      tap.dispatchRoute,
      "The step reports success, so the log is the only place the selector miss survives.",
    )
    assertEquals(540, tap.x)
    assertEquals(1790, tap.y)
  }

  @Test
  fun `a long press delivered at recorded coordinates after a selector miss is logged as such`() {
    val logged = mapTap(
      action = AccessibilityAction.TapOnElement(
        nodeSelector = selector,
        longPress = true,
        fallbackX = 120,
        fallbackY = 640,
      ),
      executionResult = AccessibilityDeviceManager.ExecutionResult(
        resolvedX = 120,
        resolvedY = 640,
        dispatchRoute = TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      ),
    )

    val longPress = assertIs<AgentDriverAction.LongPressPoint>(logged)
    assertEquals(
      TapDispatchRoute.RECORDED_COORDINATES_AFTER_SELECTOR_MISS,
      longPress.dispatchRoute,
      "A long-press fallback is the same false evidence as a tap fallback and must be as visible.",
    )
  }

  @Test
  fun `a selector-resolved tap is logged with the route the driver actually dispatched`() {
    val logged = mapTap(
      action = AccessibilityAction.TapOnElement(nodeSelector = selector),
      executionResult = AccessibilityDeviceManager.ExecutionResult(
        resolvedX = 300,
        resolvedY = 900,
        dispatchRoute = TapDispatchRoute.ACTION_CLICK,
      ),
    )

    val tap = assertIs<AgentDriverAction.TapPoint>(logged)
    assertEquals(TapDispatchRoute.ACTION_CLICK, tap.dispatchRoute)
  }

  @Test
  fun `a selector-resolved long press now records its route instead of dropping it`() {
    val logged = mapTap(
      action = AccessibilityAction.TapOnElement(nodeSelector = selector, longPress = true),
      executionResult = AccessibilityDeviceManager.ExecutionResult(
        resolvedX = 300,
        resolvedY = 900,
        dispatchRoute = TapDispatchRoute.GESTURE,
      ),
    )

    val longPress = assertIs<AgentDriverAction.LongPressPoint>(logged)
    assertEquals(TapDispatchRoute.GESTURE, longPress.dispatchRoute)
  }

  @Test
  fun `a tap that dispatched nothing is logged at the origin with no route`() {
    // The shape an `optional: true` miss produces: no resolved coordinates, so the log coerces to
    // (0, 0) and carries no route. Deliberate — the enum describes how a tap *was* delivered, and
    // this one wasn't — but it means the origin and a null route are not evidence of a real tap.
    val logged = mapTap(
      action = AccessibilityAction.TapOnElement(nodeSelector = selector, optional = true),
      executionResult = AccessibilityDeviceManager.ExecutionResult(),
    )

    val tap = assertIs<AgentDriverAction.TapPoint>(logged)
    assertEquals(0, tap.x)
    assertEquals(0, tap.y)
    assertEquals(null, tap.dispatchRoute)
  }
}
