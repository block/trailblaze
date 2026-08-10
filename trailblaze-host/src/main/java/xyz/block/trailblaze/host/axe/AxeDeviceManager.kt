package xyz.block.trailblaze.host.axe

import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.host.ios.IosDeviceManager
import xyz.block.trailblaze.host.ios.IosDeviceManager.ExecutionResult
import xyz.block.trailblaze.host.ios.IosDriverAction
import xyz.block.trailblaze.host.ios.IosSimulatorPermissions
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
     * Pure percent → pixel conversion for relative taps/swipes, clamped to the device bounds —
     * extracted so it's unit-testable without stubbing `AxeCli`. Without the clamp, out-of-range
     * percents (negative, >= 100) produce off-screen coordinates (e.g. `deviceWidth` instead of
     * `deviceWidth - 1`) that `axe` can reject or dispatch unpredictably.
     */
    internal fun pixelFromPercent(percent: Double, dimension: Int): Int =
      (dimension * percent / 100.0).toInt().coerceIn(0, dimension - 1)

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

    /**
     * A node counts as content once AXe reports a non-blank text-bearing field
     * (label/value/title). Blank strings are the mid-render empty-container state the
     * readiness gate exists to wait out, so they must not count.
     */
    private fun isContentful(node: TrailblazeNode): Boolean {
      val detail = node.driverDetail as? DriverNodeDetail.IosAxe ?: return false
      return !detail.label.isNullOrBlank() || !detail.value.isNullOrBlank() || !detail.title.isNullOrBlank()
    }

    private fun countContentfulNodes(node: TrailblazeNode): Int =
      (if (isContentful(node)) 1 else 0) + node.children.sumOf(::countContentfulNodes)

    /**
     * Content signature of a captured tree — the per-node (type, label, value, title, bounds)
     * identity in pre-order, deliberately ignoring capture-assigned [TrailblazeNode.nodeId]s
     * so two captures of the same screen compare equal. Used by [isTreeReadyAfterAction] to
     * tell "the screen changed since the action" apart from "the pre-action screen is still up".
     */
    internal fun treeContentSignature(root: TrailblazeNode): List<String> =
      root.aggregate().map { node ->
        val d = node.driverDetail as? DriverNodeDetail.IosAxe
        "${d?.type}|${d?.label}|${d?.value}|${d?.title}|${node.bounds}"
      }

    /**
     * Readiness decision for the post-launch/open-link gate, extracted pure for unit testing.
     * A tree is ready-after-action once it [isTreeReady] AND — when a pre-action
     * [baselineSignature] is available — its content differs from that baseline. Without the
     * baseline check, the previous screen's tree is already "ready" the moment the poll starts,
     * so the gate returns immediately and the first selector resolves against a stale screen.
     * A null baseline (pre-action capture failed) falls back to the content-only check.
     */
    internal fun isTreeReadyAfterAction(
      root: TrailblazeNode?,
      baselineSignature: List<String>?,
      minContentNodes: Int = READINESS_MIN_CONTENT_NODE_COUNT,
    ): Boolean {
      if (!isTreeReady(root, minContentNodes)) return false
      if (baselineSignature == null) return true
      return treeContentSignature(root!!) != baselineSignature
    }

    /**
     * Failure-message fragment for a selector that matched the accessibility tree but not the
     * viewport-clamped tree. Distinguishing "in tree" from "on screen" matters
     * because describe-ui spans the whole scroll content: without the distinction, the
     * off-screen case reads as a flaky selector instead of a missing scroll step.
     */
    internal fun offViewportDescription(
      bounds: TrailblazeNode.Bounds?,
      viewportWidth: Int,
      viewportHeight: Int,
    ): String = "is in the accessibility tree but outside the viewport " +
      "(bounds=$bounds, viewport=${viewportWidth}x$viewportHeight). " +
      "It is not visible on screen; scroll it into view first."
  }

  /**
   * Springboard notification-alert auto-dismiss intent ("allow"/"deny"), armed by the last
   * [IosDriverAction.LaunchApp]'s permissions plan. AXe-path equivalent of Maestro's XCUITest
   * runner remembering `setPermissions` and tapping the alert during hierarchy capture —
   * notifications have no TCC pre-grant (`simctl privacy` doesn't cover them), so runtime
   * dismissal is how Maestro itself keeps them dialog-free. Null = leave alerts alone.
   */
  private var notificationsAutoDismiss: String? = null

  // --- Screen state ---

  override fun getScreenState(): ScreenState = AxeScreenState(
    udid = udid,
    deviceWidth = deviceWidth,
    deviceHeight = deviceHeight,
  )

  /**
   * Fresh tree capture without waiting — used for selector resolution loops.
   *
   * Null means "no usable tree this iteration — capture again", covering three cases:
   * describe-ui failed, its JSON was unparseable, or the captured hierarchy was invalidated
   * by a notification-alert auto-dismiss tap (see [maybeDismissNotificationAlert]) taken
   * during this capture. Callers must treat null as a retry signal, never a terminal error —
   * every existing caller is a poll loop that re-captures on the next iteration.
   */
  fun captureTree(): TrailblazeNode? {
    val res = AxeCli.describeUi(udid)
    if (!res.success) {
      Console.log("[AxeDeviceManager] describe-ui failed: ${res.stderr.trim()}")
      return null
    }
    val tree = try {
      AxeJsonMapper.parse(res.stdout)
    } catch (e: Exception) {
      Console.log("[AxeDeviceManager] describe-ui produced unparseable JSON: ${e.message}")
      null
    }
    if (tree != null && maybeDismissNotificationAlert(tree)) {
      // The dismiss tap just changed the UI, so this capture is stale — it still shows the
      // alert. Returning it would let the caller resolve selectors against a hierarchy that no
      // longer exists; return null so the poll loop that drove this capture re-captures.
      return null
    }
    return tree
  }

  /**
   * Auto-taps the springboard notification-permission alert when the armed intent says so —
   * Maestro parity: its XCUITest runner does exactly this during every hierarchy capture
   * (`SystemPermissionHelper`). Best-effort like the runner: the poll loop that triggered this
   * capture re-captures next iteration and self-corrects either way. Returns true when a
   * dismiss tap landed (i.e. [tree] no longer reflects the screen); false when nothing was
   * tapped or the tap failed (the alert is still up, so [tree] is still accurate).
   */
  private fun maybeDismissNotificationAlert(tree: TrailblazeNode): Boolean {
    val value = notificationsAutoDismiss ?: return false
    val (x, y) = IosSimulatorPermissions.findNotificationAlertTap(tree, value) ?: return false
    Console.log("[AxeDeviceManager] notification permission alert detected — auto-tapping '$value' at ($x, $y)")
    val result = AxeCli.tapXy(udid, x, y)
    return if (result.success) {
      // Let the dismiss animation land so the next capture doesn't see (and re-tap) the alert.
      waitForReady()
      true
    } else {
      Console.log("[AxeDeviceManager] notification alert auto-tap failed: ${result.stderr.trim()}")
      false
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
        val x = pixelFromPercent(action.percentX, deviceWidth)
        val y = pixelFromPercent(action.percentY, deviceHeight)
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
          pixelFromPercent(action.startXPercent, deviceWidth),
          pixelFromPercent(action.startYPercent, deviceHeight),
          pixelFromPercent(action.endXPercent, deviceWidth),
          pixelFromPercent(action.endYPercent, deviceHeight),
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
        // Maestro (re-)applies app permissions before EVERY launch (default all=allow) — after
        // the clearState reinstall wiped prior TCC grants, before the app starts. Hard-fail like
        // Orchestra's UnableToSetPermissions: a silently-missing grant resurfaces as a
        // permission dialog mid-trail.
        applyLaunchPermissions(action)
        // Baseline BEFORE the launch: the pre-launch screen's tree is already "ready", so the
        // readiness poll must additionally see the tree CHANGE — otherwise it returns
        // immediately and the first selector resolves against the stale screen.
        val baseline = captureTree()
        val launched = SimctlCli.launch(udid, action.bundleId)
        if (!launched.success) {
          val container = SimctlCli.getAppContainer(udid, action.bundleId)
          if (!container.success) {
            // A prior clearState whose reinstall failed leaves the app missing for every later
            // trail — surface that directly instead of simctl's opaque POSIX error. The
            // container check can also fail for infra reasons (wedged simulator, timeout), so
            // include its own error output to make a misdiagnosis visible.
            error(
              "launch ${action.bundleId} failed: app is likely not installed on simulator $udid " +
                "(a prior clearState reinstall may have failed) — reinstall the app. " +
                "Launch error: ${launched.stderr.trim()}. " +
                "Container check error: ${container.stderr.trim()}",
            )
          }
        }
        launched.throwIfError("launch ${action.bundleId}")
        awaitTreeReady("launch ${action.bundleId}", baseline)
        ExecutionResult()
      }
      is IosDriverAction.StopApp -> {
        SimctlCli.terminate(udid, action.bundleId).throwIfError("terminate ${action.bundleId}")
        ExecutionResult()
      }
      is IosDriverAction.ClearState -> {
        // Standalone clearState — no launch afterwards, so no tree-readiness wait either.
        SimctlCli.clearAppState(udid, action.bundleId).throwIfError("clearState ${action.bundleId}")
        // Maestro resets permissions to unset on a standalone clearState (Orchestra's
        // clearAppStateCommand, for Android parity). This path is deliberately MORE lenient
        // than Maestro — Orchestra propagates setPermissions failures, but here the reinstall
        // above already wiped the app's TCC rows, so this is redundant hardening not worth
        // failing a completed wipe. Also disarms the notification auto-dismiss.
        val privacyReset = SimctlCli.privacy(udid, "reset", "all", action.bundleId)
        if (!privacyReset.success) {
          Console.log(
            "[AxeDeviceManager] best-effort TCC reset after clearState ${action.bundleId} " +
              "failed (exit ${privacyReset.exitCode}): ${privacyReset.stderr.trim()}",
          )
        }
        notificationsAutoDismiss = null
        ExecutionResult()
      }
      IosDriverAction.ClearKeychain -> {
        SimctlCli.keychainReset(udid).throwIfError("keychain reset")
        ExecutionResult()
      }
      is IosDriverAction.OpenLink -> {
        val baseline = captureTree() // same stale-screen guard as LaunchApp
        SimctlCli.openUrl(udid, action.url).throwIfError("openurl ${action.url}")
        awaitTreeReady("open ${action.url}", baseline)
        ExecutionResult()
      }
      is IosDriverAction.TapOnElement -> executeTapOnElement(action)
      is IosDriverAction.AssertVisible -> executeAssertVisible(action)
      is IosDriverAction.AssertNotVisible -> executeAssertNotVisible(action)
    }
  }

  /**
   * Applies the launch's permissions map (see [IosSimulatorPermissions] for the Maestro
   * semantics being matched): `simctl privacy` for every service it can pre-grant, plus arming
   * [notificationsAutoDismiss] for the alert-tap the simulator offers no pre-grant for.
   */
  private fun applyLaunchPermissions(action: IosDriverAction.LaunchApp) {
    val plan = IosSimulatorPermissions.plan(action.permissions)
    if (plan.skipped.isNotEmpty()) {
      Console.log(
        "[AxeDeviceManager] permissions with no simctl equivalent (Maestro services them via " +
          "applesimutils) — skipped: ${plan.skipped}",
      )
    }
    plan.privacyCommands.forEach { cmd ->
      SimctlCli.privacy(udid, cmd.action, cmd.service, action.bundleId)
        .throwIfError("privacy ${cmd.action} ${cmd.service} ${action.bundleId}")
    }
    notificationsAutoDismiss = plan.notificationsValue
  }

  /**
   * Polls `describe-ui` until the tree looks like real app content instead of a blind fixed
   * sleep — launch/deep-link render time is app-dependent, so a fixed delay is either too
   * short (flaky) or too long (slow) depending on the target. When a pre-action [baseline]
   * tree is available, the poll also requires the tree to have CHANGED since the action
   * (see [isTreeReadyAfterAction]) so the still-ready pre-action screen can't satisfy the
   * gate. Caps at [READINESS_POLL_TIMEOUT_MS] and proceeds regardless past that; a
   * still-blank (or genuinely unchanged, e.g. launch of an already-frontmost app) tree just
   * means the next selector poll (tap/assert) inherits the remaining wait.
   */
  private fun awaitTreeReady(label: String, baseline: TrailblazeNode?) {
    val baselineSignature = baseline?.let(::treeContentSignature)
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < READINESS_POLL_TIMEOUT_MS) {
      if (isTreeReadyAfterAction(captureTree(), baselineSignature)) return
      Thread.sleep(POLL_INTERVAL_MS)
    }
    Console.log(
      "[AxeDeviceManager] readiness poll timed out after ${READINESS_POLL_TIMEOUT_MS}ms for $label; " +
        "proceeding anyway",
    )
  }

  // --- Selector-driven actions ---
  //
  // Selector resolution runs against the viewport-clamped tree (Maestro parity):
  // the Maestro/XCUITest driver filters off-screen nodes out of its hierarchy at capture
  // time, so on IOS_HOST an off-screen element never matches — asserts fail, taps report
  // element-not-found, and scroll loops keep scrolling. The unclamped tree is kept only to
  // tell "in tree but off screen" from "not in tree" in failure messages.
  //
  // One deliberate divergence: Maestro falls back to the unfiltered hierarchy when the
  // filter prunes every node (`filtered ?: it` in ViewHierarchy.from), and AxeScreenState
  // mirrors that for its capture surfaces. These action paths do NOT — a clamp-to-nothing
  // capture is just a no-match poll that retries — because resolving against the unclamped
  // tree here would re-open the blind off-screen tap the clamp exists to prevent.

  private fun executeTapOnElement(action: IosDriverAction.TapOnElement): ExecutionResult {
    var lastFullTree: TrailblazeNode? = null
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val fullTree = captureTree()
      if (fullTree != null) {
        lastFullTree = fullTree
        val matched = pickMatch(clampToViewport(fullTree), action.nodeSelector)
        if (matched != null) {
          val center = matched.centerPoint()
            ?: error("Element matched but has no bounds: ${action.nodeSelector.description()}")
          tapOrLongPress(center.first, center.second, action.longPress)
          return ExecutionResult(center.first, center.second)
        }
      }
      Thread.sleep(POLL_INTERVAL_MS)
    }
    val offViewport = pickMatch(lastFullTree, action.nodeSelector, logMultipleMatches = false)
    if (offViewport != null) {
      // The element exists below/above the fold — a coordinate fallback here would be
      // exactly the blind off-screen tap this gate exists to prevent, so it is skipped.
      if (action.optional) {
        Console.log("[AxeDeviceManager] optional tap: skipping (match is outside the viewport)")
        return ExecutionResult(null, null)
      }
      error(
        "Tap target ${action.nodeSelector.description()} " +
          offViewportDescription(offViewport.bounds, deviceWidth, deviceHeight) +
          " Refusing to dispatch a blind off-screen tap.",
      )
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
    var lastFullTree: TrailblazeNode? = null
    val startTime = System.currentTimeMillis()
    while (System.currentTimeMillis() - startTime < action.timeoutMs) {
      val fullTree = captureTree()
      if (fullTree != null) {
        lastFullTree = fullTree
        val matched = pickMatch(clampToViewport(fullTree), action.nodeSelector)
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
    val offViewport = pickMatch(lastFullTree, action.nodeSelector, logMultipleMatches = false)
    if (offViewport != null) {
      error(
        "Assert visible failed: ${action.nodeSelector.description()} " +
          offViewportDescription(offViewport.bounds, deviceWidth, deviceHeight),
      )
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
        // Clamped like the positive assert: an element that scrolled below the fold is
        // "not visible" here too (Maestro parity — its filtered hierarchy drops the node).
        if (pickMatch(clampToViewport(tree), action.nodeSelector, logMultipleMatches = false) == null) {
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

  /**
   * Known limitation: [deviceWidth]/[deviceHeight] are captured once at connect time, while
   * Maestro re-reads `driver.deviceInfo()` on every capture — a mid-session rotation would
   * make this clamp prune elements that are actually on screen. Per-capture re-read is
   * deferred until a suite exercises rotation.
   */
  private fun clampToViewport(tree: TrailblazeNode): TrailblazeNode? =
    AxeViewportClamp.clamp(tree, deviceWidth, deviceHeight)

  /**
   * Resolves [nodeSelector] against [tree] and picks the single element to act on, or null
   * on no match (or a null tree). [logMultipleMatches] is off for the failure-diagnosis
   * resolve against the unclamped tree, where the "picking the first" advice would be noise.
   */
  private fun pickMatch(
    tree: TrailblazeNode?,
    nodeSelector: TrailblazeNodeSelector,
    logMultipleMatches: Boolean = true,
  ): TrailblazeNode? {
    if (tree == null) return null
    return when (val result = TrailblazeNodeSelectorResolver.resolve(tree, nodeSelector, templateContext)) {
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
        if (logMultipleMatches) {
          Console.log(
            "[AxeDeviceManager] selector '${nodeSelector.description()}' matched " +
              "${result.nodes.size} elements — picking the first; refine the selector to disambiguate",
          )
        }
        result.nodes.first()
      }
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
    }
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
