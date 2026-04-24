package xyz.block.trailblaze.host.axe

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.host.screenstate.AxeScreenState
import xyz.block.trailblaze.util.Console

/**
 * Manages iOS Simulator interaction through the AXe CLI.
 *
 * iOS-Simulator equivalent of [xyz.block.trailblaze.android.accessibility.AccessibilityDeviceManager]
 * — takes [AxeAction] objects and dispatches them via [AxeCli] shell-outs (plus [SimctlCli]
 * for app lifecycle). Handles selector resolution against the cached [AxeScreenState] tree
 * and provides a best-effort settle via short polling.
 */
class AxeDeviceManager(
  private val udid: String,
  private val deviceWidth: Int,
  private val deviceHeight: Int,
) {

  companion object {
    /** Polling interval for element-resolution loops. Balances responsiveness with CPU usage. */
    private const val POLL_INTERVAL_MS = 150L

    /** Settle delay after a gesture before the next describe-ui read. */
    private const val SETTLE_DELAY_MS = 300L

    /**
     * Pure coord math for a directional swipe — extracted so it's unit-testable without
     * needing to stub `AxeCli`. Returns `[startX, startY, endX, endY]` for a swipe that
     * spans 80% of the relevant dimension, anchored at the center of the other axis.
     */
    internal fun computeDirectionalSwipeCoords(
      direction: AxeAction.Direction,
      width: Int,
      height: Int,
    ): IntArray {
      val cx = width / 2
      val cy = height / 2
      return when (direction) {
        AxeAction.Direction.UP -> intArrayOf(cx, cy, cx, (height * 0.1).toInt())
        AxeAction.Direction.DOWN -> intArrayOf(cx, cy, cx, (height * 0.9).toInt())
        AxeAction.Direction.LEFT -> intArrayOf((width * 0.9).toInt(), cy, (width * 0.1).toInt(), cy)
        AxeAction.Direction.RIGHT -> intArrayOf((width * 0.1).toInt(), cy, (width * 0.9).toInt(), cy)
      }
    }
  }

  data class ExecutionResult(val resolvedX: Int? = null, val resolvedY: Int? = null)

  // --- Screen state ---

  fun getScreenState(): ScreenState = AxeScreenState(
    udid = udid,
    deviceWidth = deviceWidth,
    deviceHeight = deviceHeight,
  )

  /** Fresh tree capture without waiting — used for selector resolution loops. */
  fun captureTree(): TrailblazeNode? {
    val res = AxeCli.describeUi(udid)
    if (!res.success) {
      Console.log("[AxeDeviceManager] describe-ui failed: ${res.stderr.trim()}")
      return null
    }
    return try {
      AxeJsonMapper.parse(res.stdout)
    } catch (e: Exception) {
      Console.log("[AxeDeviceManager] describe-ui produced unparseable JSON: ${e.message}")
      null
    }
  }

  /** Fixed-delay settle — replace with tree-hash polling when a loop needs it. */
  fun waitForReady(timeoutMs: Long = SETTLE_DELAY_MS) {
    Thread.sleep(timeoutMs)
  }

  // --- Action dispatch ---

  fun execute(action: AxeAction): ExecutionResult {
    Console.log("[AxeDeviceManager] Executing: ${action.description}")
    return when (action) {
      is AxeAction.Tap -> {
        AxeCli.tapXy(udid, action.x, action.y).throwIfError("tap")
        ExecutionResult(action.x, action.y)
      }
      is AxeAction.TapRelative -> {
        val x = (deviceWidth * action.percentX / 100.0).toInt()
        val y = (deviceHeight * action.percentY / 100.0).toInt()
        AxeCli.tapXy(udid, x, y).throwIfError("tapRelative")
        ExecutionResult(x, y)
      }
      is AxeAction.LongPress -> {
        AxeCli.touchHold(udid, action.x, action.y, action.durationMs).throwIfError("longPress")
        ExecutionResult(action.x, action.y)
      }
      is AxeAction.Swipe -> {
        AxeCli.swipe(udid, action.startX, action.startY, action.endX, action.endY, action.durationMs)
          .throwIfError("swipe")
        ExecutionResult()
      }
      is AxeAction.SwipeDirection -> {
        val (sx, sy, ex, ey) = computeDirectionalSwipe(action.direction, deviceWidth, deviceHeight)
        AxeCli.swipe(udid, sx, sy, ex, ey, action.durationMs).throwIfError("swipeDirection")
        ExecutionResult()
      }
      AxeAction.ScrollUp -> { AxeCli.gesture(udid, "scroll-up").throwIfError("scroll-up"); ExecutionResult() }
      AxeAction.ScrollDown -> { AxeCli.gesture(udid, "scroll-down").throwIfError("scroll-down"); ExecutionResult() }
      AxeAction.ScrollLeft -> { AxeCli.gesture(udid, "scroll-left").throwIfError("scroll-left"); ExecutionResult() }
      AxeAction.ScrollRight -> { AxeCli.gesture(udid, "scroll-right").throwIfError("scroll-right"); ExecutionResult() }
      is AxeAction.InputText -> {
        AxeCli.type(udid, action.text).throwIfError("type")
        ExecutionResult()
      }
      is AxeAction.EraseText -> {
        // AXe keycode 42 = Delete/Backspace on iOS HID keyboard.
        repeat(action.characters) {
          AxeCli.key(udid, 42).throwIfError("eraseText")
        }
        ExecutionResult()
      }
      AxeAction.PressHome -> { AxeCli.button(udid, "home").throwIfError("press home"); ExecutionResult() }
      AxeAction.PressLock -> { AxeCli.button(udid, "lock").throwIfError("press lock"); ExecutionResult() }
      AxeAction.PressSiri -> { AxeCli.button(udid, "siri").throwIfError("press siri"); ExecutionResult() }
      is AxeAction.WaitForSettle -> { waitForReady(action.timeoutMs); ExecutionResult() }
      AxeAction.TakeScreenshot -> ExecutionResult() // screenshots captured by logging pipeline
      is AxeAction.LaunchApp -> {
        SimctlCli.launch(udid, action.bundleId).throwIfError("launch ${action.bundleId}")
        // Apps take ~1-3s to fully render. Bigger delay than a tap.
        Thread.sleep(2_000L)
        ExecutionResult()
      }
      is AxeAction.StopApp -> {
        SimctlCli.terminate(udid, action.bundleId).throwIfError("terminate ${action.bundleId}")
        ExecutionResult()
      }
      is AxeAction.OpenLink -> {
        SimctlCli.openUrl(udid, action.url).throwIfError("openurl ${action.url}")
        Thread.sleep(1_000L)
        ExecutionResult()
      }
      is AxeAction.TapOnElement -> executeTapOnElement(action)
      is AxeAction.AssertVisible -> executeAssertVisible(action)
      is AxeAction.AssertNotVisible -> executeAssertNotVisible(action)
    }
  }

  // --- Selector-driven actions ---

  private fun executeTapOnElement(action: AxeAction.TapOnElement): ExecutionResult {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, action.nodeSelector)
        val matched: TrailblazeNode? = when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            Console.log(
              "[AxeDeviceManager] selector '${action.nodeSelector.description()}' matched " +
                "${result.nodes.size} elements — picking the first; refine the selector to disambiguate",
            )
            result.nodes.first()
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
        }
        if (matched != null) {
          val center = matched.centerPoint()
            ?: error("Element matched but has no bounds: ${action.nodeSelector.description()}")
          tapOrLongPress(center.first, center.second, action.longPress)
          return ExecutionResult(center.first, center.second)
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    // Fallback to recorded coordinates if provided.
    if (action.fallbackX != null && action.fallbackY != null) {
      Console.log("[AxeDeviceManager] selector miss, using fallback (${action.fallbackX}, ${action.fallbackY})")
      tapOrLongPress(action.fallbackX, action.fallbackY, action.longPress)
      return ExecutionResult(action.fallbackX, action.fallbackY)
    }
    error(
      "Element not found for selector: ${action.nodeSelector.description()} " +
        "after ${action.timeoutMs}ms. No fallback coordinates available.",
    )
  }

  private fun executeAssertVisible(action: AxeAction.AssertVisible): ExecutionResult {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, action.nodeSelector)
        val matched: TrailblazeNode? = when (result) {
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
            Console.log(
              "[AxeDeviceManager] selector '${action.nodeSelector.description()}' matched " +
                "${result.nodes.size} elements — picking the first; refine the selector to disambiguate",
            )
            result.nodes.first()
          }
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
        }
        if (matched != null) {
          val center = matched.centerPoint()
          return ExecutionResult(center?.first, center?.second)
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error("Assert visible failed: ${action.nodeSelector.description()} not found within ${action.timeoutMs}ms")
  }

  private fun executeAssertNotVisible(action: AxeAction.AssertNotVisible): ExecutionResult {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, action.nodeSelector)
        if (result is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch) {
          return ExecutionResult(deviceWidth / 2, deviceHeight / 2)
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    error("Assert not visible failed: ${action.nodeSelector.description()} still visible after ${action.timeoutMs}ms")
  }

  private fun tapOrLongPress(x: Int, y: Int, longPress: Boolean) {
    if (longPress) {
      AxeCli.touchHold(udid, x, y, durationMs = 500L).throwIfError("longPress-element")
    } else {
      AxeCli.tapXy(udid, x, y).throwIfError("tap-element")
    }
  }

  private fun computeDirectionalSwipe(
    direction: AxeAction.Direction,
    width: Int,
    height: Int,
  ): IntArray = computeDirectionalSwipeCoords(direction, width, height)

  private fun AxeCli.Result.throwIfError(label: String) {
    if (!success) error("axe $label failed (exit=$exitCode): ${stderr.trim()}")
  }

  private fun SimctlCli.Result.throwIfError(label: String) {
    if (!success) error("simctl $label failed (exit=$exitCode): ${stderr.trim()}")
  }

  // Trivial IntArray destructuring (4 components) for the swipe return tuple.
  private operator fun IntArray.component1() = this[0]
  private operator fun IntArray.component2() = this[1]
  private operator fun IntArray.component3() = this[2]
  private operator fun IntArray.component4() = this[3]
}
