package xyz.block.trailblaze.host.axe

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.host.ios.IosDeviceManager
import xyz.block.trailblaze.host.ios.IosDeviceManager.ExecutionResult
import xyz.block.trailblaze.host.ios.IosDriverAction
import xyz.block.trailblaze.host.ios.SimctlCli
import xyz.block.trailblaze.host.screenstate.AxeScreenState
import xyz.block.trailblaze.util.Console

/**
 * Manages iOS Simulator interaction through the AXe CLI — the reference [IosDeviceManager].
 *
 * iOS-Simulator equivalent of [xyz.block.trailblaze.android.accessibility.AccessibilityDeviceManager]
 * — takes [IosDriverAction] objects and dispatches them via [AxeCli] shell-outs (plus [SimctlCli]
 * for app lifecycle). Handles selector resolution against the cached [AxeScreenState] tree
 * and provides a best-effort settle via short polling.
 */
class AxeDeviceManager(
  private val udid: String,
  private val deviceWidth: Int,
  private val deviceHeight: Int,
  // Per-session template context surfaced to every internal resolve() call so selectors
  // carrying `{{target.appId}}` placeholders expand correctly. Null when the bridge wasn't
  // constructed with a target (target-agnostic ad-hoc paths); selectors authored without
  // templates work either way.
  private val templateContext: xyz.block.trailblaze.api.TargetTemplateContext? = null,
) : IosDeviceManager {

  companion object {
    /** Polling interval for element-resolution loops. Balances responsiveness with CPU usage. */
    private const val POLL_INTERVAL_MS = 150L

    /** Settle delay after a gesture before the next describe-ui read. */
    private const val SETTLE_DELAY_MS = 300L

    /**
     * Cap on the post-launch/open-link readiness poll (see [awaitTreeReady]) — past this
     * we proceed regardless rather than block indefinitely on an app that's slow to render.
     * Soft by one in-flight probe: a stalled describe-ui holds the loop until AxeCli's own
     * subprocess timeout fires (bounded, never a hang) — the same overshoot shape as every
     * selector poll in this class, and the stall would be paid by the next capture anyway.
     */
    private const val READINESS_POLL_TIMEOUT_MS = 5_000L

    /**
     * Minimum count of content-bearing nodes (a label, value, or title) below which a captured
     * tree reads as a placeholder/loading screen rather than the app's real UI. Structure alone
     * doesn't count: a mid-render tree of blank containers is exactly the state the readiness
     * gate exists to wait out.
     */
    private const val READINESS_MIN_CONTENT_NODE_COUNT = 3

    /**
     * Pure coord math for a directional swipe — extracted so it's unit-testable without
     * needing to stub `AxeCli`. Returns `[startX, startY, endX, endY]` for a swipe that
     * spans 80% of the relevant dimension, anchored at the center of the other axis.
     */
    internal fun computeDirectionalSwipeCoords(
      direction: IosDriverAction.Direction,
      width: Int,
      height: Int,
    ): IntArray {
      val cx = width / 2
      val cy = height / 2
      return when (direction) {
        IosDriverAction.Direction.UP -> intArrayOf(cx, cy, cx, (height * 0.1).toInt())
        IosDriverAction.Direction.DOWN -> intArrayOf(cx, cy, cx, (height * 0.9).toInt())
        IosDriverAction.Direction.LEFT -> intArrayOf((width * 0.9).toInt(), cy, (width * 0.1).toInt(), cy)
        IosDriverAction.Direction.RIGHT -> intArrayOf((width * 0.1).toInt(), cy, (width * 0.9).toInt(), cy)
      }
    }

    /**
     * Pure readiness check over a captured tree — extracted so it's unit-testable with plain
     * [TrailblazeNode] fixtures rather than stubbing `AxeCli`. A null tree (capture failed) is
     * never ready; otherwise the tree is ready once it carries at least [minContentNodes]
     * content-bearing nodes. A structurally populated tree whose nodes are all blank containers
     * is still mid-render and stays not-ready.
     */
    internal fun isTreeReady(root: TrailblazeNode?, minContentNodes: Int = READINESS_MIN_CONTENT_NODE_COUNT): Boolean {
      if (root == null) return false
      return countContentfulNodes(root) >= minContentNodes
    }

    /** A node counts as content once AXe reports any of its text-bearing fields (label/value/title). */
    private fun isContentful(node: TrailblazeNode): Boolean {
      val detail = node.driverDetail as? DriverNodeDetail.IosAxe ?: return false
      return detail.label != null || detail.value != null || detail.title != null
    }

    private fun countContentfulNodes(node: TrailblazeNode): Int =
      (if (isContentful(node)) 1 else 0) + node.children.sumOf(::countContentfulNodes)
  }

  // --- Screen state ---

  override fun getScreenState(): ScreenState = AxeScreenState(
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

  override fun execute(action: IosDriverAction): ExecutionResult {
    Console.log("[AxeDeviceManager] Executing: ${action.description}")
    return when (action) {
      is IosDriverAction.Tap -> {
        AxeCli.tapXy(udid, action.x, action.y).throwIfError("tap")
        ExecutionResult(action.x, action.y)
      }
      is IosDriverAction.TapRelative -> {
        val x = (deviceWidth * action.percentX / 100.0).toInt()
        val y = (deviceHeight * action.percentY / 100.0).toInt()
        AxeCli.tapXy(udid, x, y).throwIfError("tapRelative")
        ExecutionResult(x, y)
      }
      is IosDriverAction.LongPress -> {
        AxeCli.touchHold(udid, action.x, action.y, action.durationMs).throwIfError("longPress")
        ExecutionResult(action.x, action.y)
      }
      is IosDriverAction.Swipe -> {
        AxeCli.swipe(udid, action.startX, action.startY, action.endX, action.endY, action.durationMs)
          .throwIfError("swipe")
        ExecutionResult()
      }
      is IosDriverAction.SwipeDirection -> {
        val (sx, sy, ex, ey) = computeDirectionalSwipe(action.direction, deviceWidth, deviceHeight)
        AxeCli.swipe(udid, sx, sy, ex, ey, action.durationMs).throwIfError("swipeDirection")
        ExecutionResult()
      }
      is IosDriverAction.SwipeRelative -> {
        AxeCli.swipe(
          udid,
          (deviceWidth * action.startXPercent / 100.0).toInt(),
          (deviceHeight * action.startYPercent / 100.0).toInt(),
          (deviceWidth * action.endXPercent / 100.0).toInt(),
          (deviceHeight * action.endYPercent / 100.0).toInt(),
          action.durationMs,
        ).throwIfError("swipeRelative")
        ExecutionResult()
      }
      IosDriverAction.ScrollUp -> { AxeCli.gesture(udid, "scroll-up").throwIfError("scroll-up"); ExecutionResult() }
      IosDriverAction.ScrollDown -> { AxeCli.gesture(udid, "scroll-down").throwIfError("scroll-down"); ExecutionResult() }
      IosDriverAction.ScrollLeft -> { AxeCli.gesture(udid, "scroll-left").throwIfError("scroll-left"); ExecutionResult() }
      IosDriverAction.ScrollRight -> { AxeCli.gesture(udid, "scroll-right").throwIfError("scroll-right"); ExecutionResult() }
      is IosDriverAction.InputText -> {
        AxeCli.type(udid, action.text).throwIfError("type")
        ExecutionResult()
      }
      is IosDriverAction.EraseText -> {
        repeat(action.characters) {
          AxeCli.key(udid, IosDriverAction.PressKey.BACKSPACE.keycode).throwIfError("eraseText")
        }
        ExecutionResult()
      }
      IosDriverAction.PressHome -> { AxeCli.button(udid, "home").throwIfError("press home"); ExecutionResult() }
      IosDriverAction.PressLock -> { AxeCli.button(udid, "lock").throwIfError("press lock"); ExecutionResult() }
      IosDriverAction.PressSiri -> { AxeCli.button(udid, "siri").throwIfError("press siri"); ExecutionResult() }
      is IosDriverAction.PressKey -> {
        AxeCli.key(udid, action.keycode).throwIfError("press ${action.label}")
        ExecutionResult()
      }
      is IosDriverAction.WaitForSettle -> { waitForReady(action.timeoutMs); ExecutionResult() }
      IosDriverAction.TakeScreenshot -> ExecutionResult() // screenshots captured by logging pipeline
      is IosDriverAction.LaunchApp -> {
        if (action.clearState) {
          // REINSTALL semantics — same clean-state guarantee Maestro's clearAppState gives.
          // Hard-fail on error: silently launching against dirty state is the bug this closes.
          // (clearAppState terminates the app itself, so stopFirst is subsumed.)
          SimctlCli.clearAppState(udid, action.bundleId).throwIfError("clearState ${action.bundleId}")
        } else if (action.stopFirst) {
          // Best-effort: `simctl terminate` exits nonzero when the app isn't running,
          // which is a fine starting state for a force restart.
          SimctlCli.terminate(udid, action.bundleId)
        }
        if (action.clearKeychain) {
          // Keychain entries survive the clearState reinstall — this is what actually signs a
          // user out. Hard-fail like clearState: a silently-preserved session is the bug.
          SimctlCli.keychainReset(udid).throwIfError("keychain reset for ${action.bundleId}")
        }
        SimctlCli.launch(udid, action.bundleId).throwIfError("launch ${action.bundleId}")
        awaitTreeReady("launch ${action.bundleId}")
        ExecutionResult()
      }
      is IosDriverAction.StopApp -> {
        SimctlCli.terminate(udid, action.bundleId).throwIfError("terminate ${action.bundleId}")
        ExecutionResult()
      }
      is IosDriverAction.ClearState -> {
        // Standalone clearState — no launch afterwards, so no tree-readiness wait either.
        SimctlCli.clearAppState(udid, action.bundleId).throwIfError("clearState ${action.bundleId}")
        ExecutionResult()
      }
      IosDriverAction.ClearKeychain -> {
        SimctlCli.keychainReset(udid).throwIfError("keychain reset")
        ExecutionResult()
      }
      is IosDriverAction.OpenLink -> {
        SimctlCli.openUrl(udid, action.url).throwIfError("openurl ${action.url}")
        awaitTreeReady("open ${action.url}")
        ExecutionResult()
      }
      is IosDriverAction.TapOnElement -> executeTapOnElement(action)
      is IosDriverAction.AssertVisible -> executeAssertVisible(action)
      is IosDriverAction.AssertNotVisible -> executeAssertNotVisible(action)
    }
  }

  /**
   * Polls `describe-ui` until the tree looks like real app content instead of a blind fixed
   * sleep — launch/deep-link render time is app-dependent, so a fixed delay is either too
   * short (flaky) or too long (slow) depending on the target. Caps at
   * [READINESS_POLL_TIMEOUT_MS] and proceeds regardless past that; a still-blank tree just
   * means the next selector poll (tap/assert) inherits the remaining wait.
   */
  private fun awaitTreeReady(label: String) {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < READINESS_POLL_TIMEOUT_MS) {
      if (isTreeReady(captureTree())) return
      Thread.sleep(POLL_INTERVAL_MS)
    }
    Console.log(
      "[AxeDeviceManager] readiness poll timed out after ${READINESS_POLL_TIMEOUT_MS}ms for $label; " +
        "proceeding anyway",
    )
  }

  // --- Selector-driven actions ---

  private fun executeTapOnElement(action: IosDriverAction.TapOnElement): ExecutionResult {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, action.nodeSelector, templateContext)
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
    if (action.optional) {
      Console.log("[AxeDeviceManager] optional tap: skipping (no match within ${action.timeoutMs}ms)")
      return ExecutionResult(null, null)
    }
    error(
      "Element not found for selector: ${action.nodeSelector.description()} " +
        "after ${action.timeoutMs}ms. No fallback coordinates available.",
    )
  }

  private fun executeAssertVisible(action: IosDriverAction.AssertVisible): ExecutionResult {
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, action.nodeSelector, templateContext)
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
    if (action.optional) {
      Console.log("[AxeDeviceManager] optional assert visible: skipping (no match within ${action.timeoutMs}ms)")
      return ExecutionResult(null, null)
    }
    error("Assert visible failed: ${action.nodeSelector.description()} not found within ${action.timeoutMs}ms")
  }

  private fun executeAssertNotVisible(action: IosDriverAction.AssertNotVisible): ExecutionResult {
    // Unlike assertVisible, a no-match here IS the pass — so a single capture taken against a
    // stale pre-transition tree would be a silent false pass. Require two consecutive
    // non-matching captures to narrow that window (it cannot fully close it: both captures can
    // still land before a slow transition).
    var consecutiveNoMatch = 0
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val tree = captureTree()
      if (tree != null) {
        val result = TrailblazeNodeSelectorResolver.resolve(tree, action.nodeSelector, templateContext)
        if (result is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch) {
          consecutiveNoMatch++
          if (consecutiveNoMatch >= 2) {
            return ExecutionResult(deviceWidth / 2, deviceHeight / 2)
          }
        } else {
          consecutiveNoMatch = 0
        }
      } else {
        consecutiveNoMatch = 0
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    if (action.optional) {
      Console.log("[AxeDeviceManager] optional assert not visible: skipping (still visible after ${action.timeoutMs}ms)")
      return ExecutionResult(null, null)
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
    direction: IosDriverAction.Direction,
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
