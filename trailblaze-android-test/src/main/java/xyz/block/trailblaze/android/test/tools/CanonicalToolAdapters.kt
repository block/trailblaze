package xyz.block.trailblaze.android.test.tools

import xyz.block.trailblaze.android.test.AndroidTestInstrumentation
import xyz.block.trailblaze.trailhead.InProcessDataClearedArg
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.android.test.hierarchy.ForeignWindow
import xyz.block.trailblaze.android.test.perf
import android.app.Activity
import android.app.UiAutomation
import android.os.SystemClock
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import xyz.block.trailblaze.android.test.onMainThread
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import kotlinx.serialization.json.JsonPrimitive
import maestro.SwipeDirection
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.AppUnderTestLauncher
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.maestro.TrailblazeScrollStartPosition
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ClearTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.HideKeyboardTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextRandomTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.PressKeyTrailblazeTool.PressKeyCode
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeWithRelativeCoordinatesTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.TapOnPointTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnTrailblazeTool
import xyz.block.trailblaze.android.test.hierarchy.InProcessAccessibilityTree
import xyz.block.trailblaze.android.tools.AndroidAccessibilityClickTrailblazeTool
import xyz.block.trailblaze.android.tools.AndroidGestureTapTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.allDriverMatches
import xyz.block.trailblaze.toolcalls.commands.WaitForChangeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.WaitUntilNotVisibleTrailblazeTool

/**
 * Dispatches the CANONICAL recorded-trail tools — `tapOnElementBySelector` and the rest of the
 * vocabulary every recorded trail is written in — onto this driver's own backends.
 *
 * Without this, those tools reach their generic `execute()`, which routes through
 * `maestroTrailblazeAgent` / `runMaestroCommands` — dispatch surfaces an in-process agent does not
 * have — and every recorded trail fails on its first step with a bridge error. The point of the
 * canonical vocabulary is that ONE trail replays on any Android driver; this object is the
 * ANDROID_TEST driver's half of that contract, pairing with the selector-level bridges in
 * `TrailblazeNodeSelectorResolver` (which make the recorded selector shapes resolve against the
 * hybrid tree).
 *
 * Adapters translate intent, never selectors: the selector travels into the native tool verbatim
 * and the resolver's bridge is the single place cross-shape matching is defined.
 *
 * A canonical option this driver cannot honor fails LOUDLY naming the option, rather than
 * degrading — a trail relying on `longPress` must not pass because the press was short. The set
 * covered here is the recorded trails' measured vocabulary; growing it is adding an arm.
 */
internal object CanonicalToolAdapters {

  /** How a canonical tool runs on this driver's backends. */
  fun interface CanonicalDispatch {
    suspend fun execute(
      target: AndroidTestTarget,
      context: TrailblazeToolExecutionContext,
    ): TrailblazeToolResult
  }

  /** The dispatch for [tool], or null when it is not a canonical tool this driver adapts. */
  fun adapt(tool: ExecutableTrailblazeTool): CanonicalDispatch? = when (tool) {
    is TapOnByElementSelector -> CanonicalDispatch { target, context ->
      val selector = tool.nodeSelector
        ?: return@CanonicalDispatch malformed("tapOnElementBySelector has no nodeSelector", tool)
      AndroidTestTapTool(nodeSelector = selector, longPress = tool.longPress, timeoutMs = tool.timeoutMs)
        .executeWithAndroidTest(target, context)
    }

    is TapOnTrailblazeTool -> CanonicalDispatch { target, context ->
      AndroidTestTapWithinTool(
        nodeSelector = tool.selector,
        relativePoint = tool.relativePoint,
        longPress = tool.longPress,
        doubleTap = tool.doubleTap,
      ).executeWithAndroidTest(target, context)
    }

    is LaunchAppTrailblazeTool -> CanonicalDispatch { _, _ -> launchAppUnderTest(tool) }

    is AssertVisibleBySelectorTrailblazeTool -> CanonicalDispatch { target, context ->
      val selector = tool.nodeSelector
        ?: return@CanonicalDispatch malformed("assertVisibleBySelector has no nodeSelector", tool)
      tool.removedFieldError()?.let { return@CanonicalDispatch it }
      AndroidTestAssertVisibleTool(nodeSelector = selector, timeoutMs = tool.timeoutMs)
        .executeWithAndroidTest(target, context)
    }

    is AssertNotVisibleWithTextTrailblazeTool -> CanonicalDispatch { target, context ->
      // Built from the canonical tool's own lenient pattern so both drivers agree on what
      // "not visible" means (case-insensitive, regex-or-literal). The accessibility SHAPE is
      // deliberate: the resolver bridges it onto this driver's hybrid tree, exactly like the
      // recorded androidAccessibility selectors this vocabulary ships alongside.
      val selector = TrailblazeNodeSelector.withMatch(
        DriverNodeMatch.AndroidAccessibility(
          textRegex = AssertNotVisibleWithTextTrailblazeTool.toLenientPattern(tool.text),
          resourceIdRegex = tool.id,
          isEnabled = tool.enabled,
          isSelected = tool.selected,
        ),
        index = tool.index.takeIf { it > 0 },
      )
      AndroidTestAssertNotVisibleTool(nodeSelector = selector).executeWithAndroidTest(target, context)
    }

    is AssertNotVisibleBySelectorTrailblazeTool -> CanonicalDispatch { target, context ->
      val selector = tool.nodeSelector
        ?: return@CanonicalDispatch malformed("assertNotVisibleBySelector has no nodeSelector", tool)
      AndroidTestAssertNotVisibleTool(nodeSelector = selector, timeoutMs = tool.timeoutMs)
        .executeWithAndroidTest(target, context)
    }

    // A query, not an assertion: "still visible" is an answer, so it is a Success carrying the
    // boolean, exactly as the other drivers return it.
    is WaitUntilNotVisibleTrailblazeTool -> CanonicalDispatch { target, context ->
      val notVisible = AndroidTestAssertNotVisibleTool(nodeSelector = tool.nodeSelector, timeoutMs = tool.timeoutMs)
        .executeWithAndroidTest(target, context) is TrailblazeToolResult.Success
      TrailblazeToolResult.Success(
        message =
          if (notVisible) {
            "waitUntilNotVisible: selector is not visible"
          } else {
            "waitUntilNotVisible: selector still visible after ${tool.timeoutMs ?: "default"}ms"
          },
        structuredContent = JsonPrimitive(notVisible),
      )
    }

    is PressKeyTrailblazeTool -> CanonicalDispatch { target, _ -> pressKey(target, tool.keyCode) }

    // A raw point tap means "touch the screen here", whatever window is on top: a tooltip's
    // full-screen scrim, a popup, or another app. So it goes through the system's input pipeline,
    // as `adb input tap` does on the other drivers, not into a Compose root or View this process
    // picks. The app's own settle follows only when the app is still in front.
    is TapOnPointTrailblazeTool -> CanonicalDispatch { target, context ->
      // The recorded step becomes a selector tap when the point resolves to one element, as on
      // every other driver; only the touch itself is this driver's.
      tool.recordSelectorUpgrade(context)
      ForeignWindow.tap(tool.x, tool.y, longPress = tool.longPress, doubleTap = tool.doubleTap)
      if (ForeignWindow.frontPackage() == null) target.waitForIdle()
      val action = when {
        tool.longPress -> "Long pressed"
        tool.doubleTap -> "Double-tapped"
        else -> "Tapped"
      }
      TrailblazeToolResult.Success(message = "$action at (${tool.x}, ${tool.y})")
    }

    is HideKeyboardTrailblazeTool -> CanonicalDispatch { target, _ ->
      hideKeyboard(target)
      TrailblazeToolResult.Success(message = "Keyboard hidden")
    }

    is ScrollUntilTextIsVisibleTrailblazeTool -> CanonicalDispatch { target, context ->
      // Which options this driver's scroll loop can carry out is decided in one place, shared with
      // the Maestro interpreter's `scrollUntilVisible`, so the two entry points onto the same loop
      // cannot answer differently. A non-default value refuses rather than being quietly dropped.
      val refusal = ScrollOptionSupport.unsupportedDirection(tool.direction)
        ?: ScrollOptionSupport.unsupportedVisibilityPercentage(tool.visibilityPercentage)
      if (refusal != null) {
        return@CanonicalDispatch unsupported(refusal, tool)
      }
      // Where the swipe STARTS from, which on the Maestro-backed drivers picks a different scroll
      // region and so a different amount of list per scroll. In-process there is no swipe at all —
      // the container is scrolled programmatically — so there is no region to start from, and a
      // recording that asked for one would be told it got what it asked for.
      // `scrollDurationMs` is the other half of that gesture (how slow the swipe is) and is
      // accepted: it cannot change WHERE a programmatic scroll ends up, only how a fling looks.
      if (tool.scrollStartPosition != TrailblazeScrollStartPosition.CENTER) {
        return@CanonicalDispatch unsupported(
          "scrollStartPosition=${tool.scrollStartPosition} (this driver scrolls the container " +
            "programmatically and has no swipe region to start from)",
          tool,
        )
      }
      // The canonical tool's targets are Maestro-vocabulary, so the selector is built in the
      // Maestro SHAPE and the resolver's estate bridge keeps its semantics: `text` is Maestro's
      // case-insensitive substring, `textRegex` its anchored regex. WHICH of text/textRegex/id is
      // the target, and whether a blank one counts, is the canonical tool's own decision, made by
      // its pure helpers so this driver reads a recording exactly as every other driver does: a
      // blank `textRegex` falls back to `text` rather than becoming a regex for empty text, and
      // a call whose every target is blank is refused rather than matched on nothing.
      // One divergence is left in place on purpose: a recording carrying BOTH a text target and an
      // `id` matches on the text alone here, while `execute` puts both into one selector
      // (`textRegex` AND `idRegex`) and so can stop on a different row. Narrowing this branch is a
      // selector-semantics change with its own blast radius (recorded trails in consumer repos do
      // write both), not part of reading a blank target, so it is its own change.
      val match = when {
        !ScrollUntilTextIsVisibleTrailblazeTool.hasScrollTarget(tool.text, tool.textRegex, tool.id) ->
          return@CanonicalDispatch malformed(
            "scrollUntilTextIsVisible has none of text/textRegex/id",
            tool,
          )
        ScrollUntilTextIsVisibleTrailblazeTool.hasTextTarget(tool.text, tool.textRegex) ->
          DriverNodeMatch.AndroidMaestro(
            textRegex = ScrollUntilTextIsVisibleTrailblazeTool.buildTargetTextRegex(tool.text, tool.textRegex),
          )
        else -> DriverNodeMatch.AndroidMaestro(resourceIdRegex = tool.id)
      }
      AndroidTestScrollUntilVisibleTool(
        nodeSelector = TrailblazeNodeSelector.withMatch(match, index = tool.index.takeIf { it > 0 }),
        // The canonical tool carries no timeout of its own, and on a Maestro-backed driver it
        // builds a ScrollUntilVisibleCommand that takes Maestro's default. Taking the same default
        // here is what makes an unwritten bound mean the same thing on both.
        timeoutMs = ScrollOptionSupport.DEFAULT_TIMEOUT_MS,
        centerElement = ScrollOptionSupport.centerElement(tool.centerElement),
      ).executeWithAndroidTest(target, context)
    }

    is SwipeTrailblazeTool -> CanonicalDispatch { target, _ ->
      if (tool.swipeOnElementText != null) {
        return@CanonicalDispatch unsupported("swipeOnElementText", tool)
      }
      when (tool.direction) {
        // A finger drag from the screen's centre to 10% (UP) or 90% (DOWN) of its height, as the
        // accessibility driver these recordings come from strokes it, and without a fling: its
        // gesture strokes carry none, so a list travels exactly the drag. A whole-page scroll
        // travels further and carries the element the next step taps out of view.
        SwipeDirection.UP, SwipeDirection.DOWN -> {
          val (width, height) = ForeignWindow.screenSize()
          val endFraction = if (tool.direction == SwipeDirection.UP) 0.1 else 0.9
          ForeignWindow.swipe(
            startX = width / 2,
            startY = height / 2,
            endX = width / 2,
            endY = (height * endFraction).toInt(),
            durationMs = DEFAULT_SWIPE_MS,
            fling = false,
          )
          if (ForeignWindow.frontPackage() == null) target.waitForIdle()
          TrailblazeToolResult.Success(message = "Swiped ${tool.direction}.")
        }
        SwipeDirection.LEFT, SwipeDirection.RIGHT -> {
          val towardsLeft = tool.direction == SwipeDirection.LEFT
          AndroidScrollActions.swipeHorizontally(target, towardsLeft)
          TrailblazeToolResult.Success(message = "Swiped ${tool.direction} across the screen centre.")
        }
        else -> unsupported("direction=${tool.direction}", tool)
      }
    }

    // A row measured to ignore a normal tap takes the accessibility click action, sent to the node
    // in the accessibility tree the step was recorded on, as the accessibility driver sends it.
    // That holds in the app's own window too: a Compose tap in-process is a touch, which is just
    // what such a row absorbs. In another app's window (a system document picker) it is the only
    // way in.
    is AndroidAccessibilityClickTrailblazeTool -> CanonicalDispatch { target, context ->
      accessibilityClick(target, tool, context)
    }

    // An element measured to ignore the accessibility click takes a touch at the centre of the node
    // the step was recorded on, through the system's input pipeline. Not [AndroidTestTapTool]: its
    // View path mirrors the accessibility driver's route gate and calls `performClick` on a node
    // that carries its own label, which is the dispatch such an element ignores.
    is AndroidGestureTapTrailblazeTool -> CanonicalDispatch { target, context ->
      gestureTap(target, tool, context)
    }

    // A recorded drag between two screen points, given as percentages of the display: a finger
    // on the glass, as Maestro's swipe is, so it goes through the system's input pipeline. No fling,
    // matching the accessibility driver's gesture strokes the recording was validated on.
    is SwipeWithRelativeCoordinatesTool -> CanonicalDispatch { target, _ ->
      if (tool.swipeOnElementText != null) {
        return@CanonicalDispatch unsupported("swipeOnElementText", tool)
      }
      val (width, height) = ForeignWindow.screenSize()
      val start = relativeScreenPoint(tool.startRelative, width, height)
        ?: return@CanonicalDispatch malformed("unparseable startRelative=\"${tool.startRelative}\"", tool)
      val end = relativeScreenPoint(tool.endRelative, width, height)
        ?: return@CanonicalDispatch malformed("unparseable endRelative=\"${tool.endRelative}\"", tool)
      ForeignWindow.swipe(
        start.first, start.second, end.first, end.second, tool.durationMs ?: DEFAULT_SWIPE_MS, fling = false,
      )
      if (ForeignWindow.frontPackage() == null) target.waitForIdle()
      TrailblazeToolResult.Success(message = "Swiped from ${tool.startRelative} to ${tool.endRelative}.")
    }

    // Raw Maestro command lists — the shape scripted launch tools fall back to on
    // every non-accessibility driver. Interpreted per-command; see [MaestroCommandAdapters].
    is MaestroTrailblazeTool -> CanonicalDispatch { target, context ->
      MaestroCommandAdapters.run(tool, target, context)
    }

    // `wait` is "settle until the UI goes quiet, up to a ceiling" — on Maestro drivers it maps to
    // waitForAnimationToEnd. [AndroidTestTarget.waitForIdle] is this driver's same settle: it
    // returns when Espresso and Compose report idle, and treats never-idle as "stop waiting"
    // rather than failure, which honors the tool's own contract that the time is a ceiling. The
    // tool's own ceiling is threaded through so a 1s wait on a never-idle screen stops at ~1s
    // rather than at the framework's global idle timeout.
    is WaitForIdleSyncTrailblazeTool -> CanonicalDispatch { target, _ ->
      target.waitForIdle(ceilingMs = tool.timeToWaitInSeconds * 1000L)
      TrailblazeToolResult.Success(message = "Waited for idle (ceiling ${tool.timeToWaitInSeconds}s).")
    }

    // The accessibility driver settles `waitForChange` on its service's event stream: quiet for
    // the window, or a timeout that `requireChange` decides. The in-process capture's UiAutomation
    // receives the same events, so it waits on the same signal. The base tool's fallback needs a
    // Maestro agent, which this driver does not have.
    is WaitForChangeTrailblazeTool -> CanonicalDispatch { _, _ ->
      when {
        InProcessAccessibilityTree.awaitEventsQuiet(tool.quietWindowMs, tool.timeoutMs) ->
          TrailblazeToolResult.Success(message = "UI settled")
        tool.requireChange ->
          TrailblazeToolResult.Error.ExceptionThrown(
            errorMessage = "UI did not change and settle within ${tool.timeoutMs}ms",
            command = tool,
          )
        else -> TrailblazeToolResult.Success(message = "No settled UI within ${tool.timeoutMs}ms; treated as a settle wait")
      }
    }

    is InputTextTrailblazeTool -> CanonicalDispatch { target, context ->
      // A named field is tapped first so the keys reach it rather than whatever holds focus, and
      // a field that isn't there fails the step instead of typing into the wrong one.
      tool.selector?.let { selector ->
        val tapped = perf("input.tap") { AndroidTestTapTool(nodeSelector = selector).executeWithAndroidTest(target, context) }
        if (tapped !is TrailblazeToolResult.Success) return@CanonicalDispatch tapped
      }
      // The tap can have landed in another app's window (a browser's sign-in form), where this
      // process holds no field: typed there through its accessibility node, as that driver does.
      if (ForeignWindow.frontPackage() != null) return@CanonicalDispatch typeIntoForeignWindow(tool)
      // After the tap, so the field emptied is the one the keys are about to reach.
      if (tool.clearFirst) {
        val cleared = clearFocusedTextField(target, tool)
        if (cleared !is TrailblazeToolResult.Success) return@CanonicalDispatch cleared
      }
      // The focused field's text is set directly, as the accessibility driver's `ACTION_SET_TEXT`
      // does. Key events are the fallback for screens with no focused field, such as a passcode
      // keypad listening for key presses rather than an EditText.
      if (!perf("input.setText") { appendToFocusedField(target, tool.text) }) {
        perf("input.keys") { InstrumentationRegistry.getInstrumentation().sendStringSync(tool.text) }
      }
      perf("input.idle") { target.waitForIdle() }
      // `hideKeyboardAfter` defaults TRUE and every recorded trail omits it, so this half runs on
      // essentially every recorded `inputText` — skipping it made this driver the only one that
      // leaves the field focused afterwards, and that is a state the app can see. An app can render
      // a different footer while a field holds focus — a close-keyboard button where Save was — so
      // a recorded tap on Save then fails on a screen the recording never encountered.
      if (tool.hideKeyboardAfter) {
        perf("input.hideKeyboard") { hideKeyboard(target) }
      }
      TrailblazeToolResult.Success(message = "Typed '${tool.text}'")
    }

    // Generate-then-type, in one step. The generator is the canonical tool's own — shared so the
    // value's SHAPE (prefix, digit pool, suffix) cannot drift between drivers — and the typing is
    // this driver's `inputText`, down to the hide-keyboard half. The remember happens here rather
    // than host-side for the same reason the canonical body does it inline: this execution is the
    // one that produced the value, and its memory write is what rides back in the RPC snapshot so
    // a later `{{variable}}` resolves.
    // The canonical body erases the focused field with N backspaces through Maestro. In-process the
    // field is ours to address directly: the focused Compose text input (most modern
    // screens are Compose) or, failing that, the focused EditText — replaced with the empty string
    // in one action, the same shape `AndroidViewActions.replaceText` uses so a field that re-renders
    // mid-erase cannot drop half the keystrokes. Selector-free by design, like the canonical tool:
    // "the focused field" is the whole contract.
    is ClearTextTrailblazeTool -> CanonicalDispatch { target, _ ->
      if (ForeignWindow.frontPackage() != null) {
        when (ForeignWindow.clearFocusedField()) {
          ForeignWindow.ClearOutcome.CLEARED ->
            TrailblazeToolResult.Success(message = "Cleared the focused field in another app's window")
          ForeignWindow.ClearOutcome.NO_FIELD -> noFocusedFieldToClear(tool)
          ForeignWindow.ClearOutcome.KEPT_TEXT -> foreignFieldKeptText(tool)
        }
      } else {
        clearFocusedTextField(target, tool)
      }
    }

    is InputTextRandomTrailblazeTool -> CanonicalDispatch { target, context ->
      if (tool.digitCount <= 0) {
        return@CanonicalDispatch malformed("inputTextRandom digitCount must be > 0 (was ${tool.digitCount})", tool)
      }
      val value = InputTextRandomTrailblazeTool.randomValue(
        prefix = tool.prefix,
        digitCount = tool.digitCount,
        suffix = tool.suffix,
        hex = tool.hex,
        random = Random.Default,
      )
      if (tool.variable.isNotBlank()) {
        context.memory.remember(tool.variable, value)
      }
      InstrumentationRegistry.getInstrumentation().sendStringSync(value)
      target.waitForIdle()
      if (tool.hideKeyboardAfter) {
        hideKeyboard(target)
      }
      val remembered = if (tool.variable.isNotBlank()) " and remembered it as '${tool.variable}'" else ""
      TrailblazeToolResult.Success(message = "Typed '$value'$remembered.")
    }

    else -> null
  }

  /**
   * Clicks [tool]'s node with `ACTION_CLICK`, polling the accessibility tree until it matches and
   * takes the action or the timeout passes. The node is the one the accessibility driver picks: a
   * placed match, visible ones first, so a dialog's control wins over one hidden behind it, and
   * never from a partial read an index would miscount. A node that refuses the action fails the
   * step rather than getting a tap, as the tool promises.
   */
  private suspend fun accessibilityClick(
    target: AndroidTestTarget,
    tool: AndroidAccessibilityClickTrailblazeTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val templateContext = context.resolvedTarget?.let { resolved ->
      TargetTemplateContext(appId = context.appId, appIds = resolved.appIds)
    }
    val deadline = SystemClock.uptimeMillis() + (tool.timeoutMs ?: ACCESSIBILITY_CLICK_TIMEOUT_MS)
    while (true) {
      val inForeignWindow = ForeignWindow.frontPackage() != null
      // The app's own idle wait, as every other selector lookup takes, so a control that appears
      // after a pending delay or transition gets the time to. Another app's window is not this
      // app's to wait on.
      if (!inForeignWindow) target.waitForIdle()
      val a11y = InProcessAccessibilityTree.capture(uncached = true)
      val match = a11y?.let { placedA11yMatchesIn(it, tool.nodeSelector, templateContext) }?.firstOrNull()
      val info = match?.let { a11y.infoFor(it) }
      val where = if (inForeignWindow) " in another app's window" else ""
      if (info != null && ForeignWindow.accessibilityClick(info)) {
        if (!inForeignWindow) target.waitForIdle()
        return TrailblazeToolResult.Success(
          message = "Clicked ${tool.nodeSelector.description()} with the accessibility click action$where.",
        )
      }
      if (SystemClock.uptimeMillis() >= deadline) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "android_accessibilityClick: ${tool.nodeSelector.description()} " +
            (if (info == null) "matched nothing" else "refused the click action") + "$where.",
          command = tool,
        )
      }
      SystemClock.sleep(ACCESSIBILITY_CLICK_POLL_MS)
    }
  }

  /**
   * Touches the centre of [tool]'s node, polling the accessibility tree until it matches or the
   * timeout passes. The node is picked as [accessibilityClick] picks it: a placed match, visible
   * ones first, never from a partial read. The touch is injected, so it reaches whatever window is
   * on top there, as the accessibility driver's gesture does.
   */
  private fun gestureTap(
    target: AndroidTestTarget,
    tool: AndroidGestureTapTrailblazeTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    // The same requirement the tool's own execute() enforces on the accessibility driver. This
    // path bypasses it, and without a matcher an empty or index-only selector would resolve to
    // some placed node and touch it.
    if (tool.nodeSelector.allDriverMatches().none { it is DriverNodeMatch.AndroidAccessibility }) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "android_gestureTap needs a selector with an androidAccessibility match.",
        command = tool,
      )
    }
    val templateContext = context.resolvedTarget?.let { resolved ->
      TargetTemplateContext(appId = context.appId, appIds = resolved.appIds)
    }
    val deadline = SystemClock.uptimeMillis() + (tool.timeoutMs ?: ACCESSIBILITY_CLICK_TIMEOUT_MS)
    while (true) {
      val inForeignWindow = ForeignWindow.frontPackage() != null
      if (!inForeignWindow) target.waitForIdle()
      val a11y = InProcessAccessibilityTree.capture(uncached = true)
      val bounds = a11y?.let { placedA11yMatchesIn(it, tool.nodeSelector, templateContext) }
        ?.firstOrNull()?.bounds
      val where = if (inForeignWindow) " in another app's window" else ""
      if (bounds != null) {
        ForeignWindow.tap(bounds.centerX, bounds.centerY)
        if (ForeignWindow.frontPackage() == null) target.waitForIdle()
        return TrailblazeToolResult.Success(
          message = "Touched ${tool.nodeSelector.description()} at its centre with a gesture$where.",
        )
      }
      if (SystemClock.uptimeMillis() >= deadline) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "android_gestureTap: ${tool.nodeSelector.description()} matched nothing$where.",
          command = tool,
        )
      }
      SystemClock.sleep(ACCESSIBILITY_CLICK_POLL_MS)
    }
  }

  /**
   * Whether every letter and digit of [typed] appears in this field text in order, so formatting
   * the field adds ("4013 4912", "03/15/2013", "$12") does not count against it.
   */
  private fun String.showsInOrder(typed: String): Boolean {
    var at = 0
    for (c in typed.filter(Char::isLetterOrDigit)) {
      at = indexOf(c, at, ignoreCase = true).takeIf { it >= 0 }?.plus(1) ?: return false
    }
    return true
  }

  /** What a focused Compose field kept of a value set into it. */
  private enum class EditOutcome { TOOK_IT, TOOK_PART, REFUSED }

  /**
   * Appends [text] to the focused Compose text input or, failing that, the focused EditText, in one
   * edit. A Compose input inserts at its end (see [AndroidComposeActions.appendText]); otherwise it is
   * the accessibility driver's `ACTION_SET_TEXT` of existing + [text], which is what its recordings
   * replay with. Key events cost about a millisecond a character to inject and go
   * through the IME, which reshapes some of them (autocorrect, capitalisation).
   *
   * False when neither is focused, the EditText kept its text, or the Compose field did not take
   * the edit, so the caller falls back to key events. The last is the accessibility driver's
   * readback: a masked field reports its mask as its text ("MM/DD/YYYY"), so the appended value is
   * the mask plus the input, which the field's own filter refuses — and a date of birth was left
   * blank with the step passed.
   */
  private suspend fun appendToFocusedField(target: AndroidTestTarget, text: String): Boolean {
    val focusedCompose = focusedComposeTextInput(target)
    if (focusedCompose != null) {
      val existing = focusedCompose.config.getOrNull(SemanticsProperties.EditableText)?.text.orEmpty()
      // What the field holds before any mask or formatting is drawn over it: a masked field shows
      // "MM/DD/YYYY" while it holds nothing.
      val held = focusedCompose.config.getOrNull(SemanticsProperties.InputText)?.text ?: existing
      // Both edits report only that they were dispatched, so the field is read back after each. The
      // edit already waited for the screen to settle, so the read-back skips the idle wait, which a
      // screen that never goes idle would make each typed step sit through again.
      //
      // A field can also take only part of a value. A masked date field given "03152013" at the end
      // of "MM/DD/YYYY" keeps "03" alone; a card-number field refuses a whole number failing its
      // checksum. Typed one key at a time, both take what a user's keys would, which is what
      // recordings expect, so a half-filled field is put back as it was and the keys follow.
      //
      // The value is checked against the raw text when the field reports it, since what it shows
      // can hide the input entirely: a password field shows only dots.
      suspend fun outcome(): EditOutcome {
        val afterConfig = AndroidComposeActions.findSemanticsNode(composeRootsWithoutIdleWait(target), focusedCompose.id)
          ?.config
        val raw = afterConfig?.getOrNull(SemanticsProperties.InputText)?.text
        val after = raw ?: afterConfig?.getOrNull(SemanticsProperties.EditableText)?.text
        val before = if (raw != null) held else existing
        return when {
          // A field gone after the keys took the screen with it: a passcode screen moves on as soon
          // as its last digit lands, so the input was accepted.
          text.isEmpty() || after == null -> EditOutcome.TOOK_IT
          after != before && after.showsInOrder(text) -> EditOutcome.TOOK_IT
          after != before -> {
            AndroidComposeActions.replaceText(target, focusedCompose.id, held)
            EditOutcome.TOOK_PART
          }
          else -> EditOutcome.REFUSED
        }
      }
      if (AndroidComposeActions.appendText(target, focusedCompose.id, text)) {
        when (outcome()) {
          EditOutcome.TOOK_IT -> return true
          EditOutcome.TOOK_PART -> return false
          EditOutcome.REFUSED -> Unit
        }
      }
      AndroidComposeActions.replaceText(target, focusedCompose.id, held + text)
      return outcome() == EditOutcome.TOOK_IT
    }
    val activity = target.currentActivity()
    return onMainThread {
      val field = inputFocusView(activity) as? EditText ?: return@onMainThread false
      val existing = field.text.toString()
      field.setText(existing + text)
      field.setSelection(field.length())
      // A masked field's TextWatcher can undo a bulk edit while it takes keys one at a time.
      text.isEmpty() || field.text.toString() != existing
    }
  }

  /** Every window's Compose roots, read on the UI thread without waiting for the app to go idle. */
  private fun composeRootsWithoutIdleWait(target: AndroidTestTarget): List<SemanticsNode> {
    val activity = target.currentActivity()
    return AndroidHybridHierarchyCollector.windowRoots(activity).flatMap { target.composeRootsIn(it) }
  }

  /**
   * The focused Compose text input the keyboard is attached to, or null.
   *
   * Every Compose host keeps focus of its own, so a field left focused in a host the user has
   * moved on from still reads `Focused`. A sheet over a search screen is one: the search box
   * behind it can stay focused, come first in the root order, and take the text typed into the
   * sheet. So when the window's input focus sits in a Compose host, only that host's tree is
   * searched, and when it sits in a classic View no Compose input is the target. With no View
   * focus to go by, every host is searched as before.
   */
  private suspend fun focusedComposeTextInput(target: AndroidTestTarget): SemanticsNode? {
    val activity = target.currentActivity()
    val focus = onMainThread { inputFocusView(activity)?.let { FocusedHost(it.composeHostRootId()) } }
    val roots = target.composeRoots()
    val searched = when {
      focus == null -> roots
      focus.rootId == null -> emptyList()
      else -> roots.filter { it.id == focus.rootId }
    }
    return searched.firstNotNullOfOrNull { findFocusedEditableText(it) }
  }

  /** Where input focus sits: [rootId] is its Compose host's semantics root, null for a classic View. */
  private class FocusedHost(val rootId: Int?)

  /**
   * The View holding input focus in the focused window: what the keyboard types into. A dialog or
   * sheet is a window of its own, so the activity's `currentFocus` alone would name the View
   * behind it.
   */
  private fun inputFocusView(activity: Activity): View? {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      WindowInspector.getGlobalWindowViews().lastOrNull { it.hasWindowFocus() }?.findFocus()?.let { return it }
    }
    return activity.currentFocus
  }

  /**
   * The focused EditText: the one in the focused window first, then one focused in any of this
   * process's windows, which is where focus sits while the window that owns it is still taking
   * window focus.
   */
  private fun focusedEditText(activity: Activity): EditText? {
    (inputFocusView(activity) as? EditText)?.let { return it }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      WindowInspector.getGlobalWindowViews().asReversed()
        .firstNotNullOfOrNull { it.findFocus() as? EditText }
        ?.let { return it }
    }
    return activity.window.decorView.findFocus() as? EditText
  }

  private const val FOCUS_SETTLE_MS = 2_000L
  private const val FOCUS_POLL_MS = 100L

  private fun View.composeHostRootId(): Int? {
    var view: View? = this
    while (view != null) {
      if (view is ViewRootForTest) return runCatching { view.semanticsOwner.unmergedRootSemanticsNode.id }.getOrNull()
      view = view.parent as? View
    }
    return null
  }

  /**
   * Empties the focused Compose text input or, failing that, the focused EditText — `clearText`, and
   * `inputText`'s `clearFirst`. Fails when neither is on screen, rather than letting the typing
   * that follows land on whatever the field still holds.
   */
  private suspend fun clearFocusedTextField(target: AndroidTestTarget, tool: TrailblazeTool): TrailblazeToolResult {
    val activity = target.currentActivity()
    // Polled briefly: with no idle wait in front of it, this can run before the tap that focused
    // the field has moved focus, and Espresso's wait used to cover that.
    val deadline = SystemClock.uptimeMillis() + FOCUS_SETTLE_MS
    var cleared: String?
    while (true) {
      cleared = focusedComposeTextInput(target)?.let {
        AndroidComposeActions.replaceText(target, it.id, "")
        "the focused Compose text input"
      }
        // Set directly on the main thread, as [appendToFocusedField] types, rather than through
        // Espresso's `clearText`: `onView().perform()` waits for the app to go idle FIRST and fails
        // when it never does, so on a screen that never goes idle a clearText times out with the
        // field focused the whole time.
        ?: onMainThread {
          focusedEditText(activity)?.let {
            it.setText("")
            "the focused EditText"
          }
        }
        // Last, a focused Compose input in any host. A transient popup can hold window focus while
        // the field under it keeps input focus, which the focused-host search above cannot see; it
        // comes after the EditText so a stale focus in a host left behind never beats a live one.
        ?: target.composeRoots().firstNotNullOfOrNull { findFocusedEditableText(it) }?.let {
          AndroidComposeActions.replaceText(target, it.id, "")
          "the focused Compose text input"
        }
      if (cleared != null || SystemClock.uptimeMillis() >= deadline) break
      SystemClock.sleep(FOCUS_POLL_MS)
    }
    if (cleared == null) return noFocusedFieldToClear(tool)
    target.waitForIdle()
    return TrailblazeToolResult.Success(message = "Cleared $cleared")
  }

  private fun noFocusedFieldToClear(tool: TrailblazeTool): TrailblazeToolResult =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "Found no focused text field to clear — neither a focused Compose text " +
        "input nor a focused EditText is on screen.",
      command = tool,
    )

  private fun foreignFieldKeptText(tool: TrailblazeTool): TrailblazeToolResult =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "The focused field in another app's window kept its text after clearing, " +
        "by setting it and by delete keys.",
      command = tool,
    )

  /**
   * `inputText` into the field focused in another app's window. Key events are the fallback, as
   * in-app, through the shell: this process may not inject keys into a window it does not own.
   */
  private fun typeIntoForeignWindow(tool: InputTextTrailblazeTool): TrailblazeToolResult {
    if (tool.clearFirst) {
      when (ForeignWindow.clearFocusedField()) {
        ForeignWindow.ClearOutcome.CLEARED -> Unit
        ForeignWindow.ClearOutcome.NO_FIELD -> return noFocusedFieldToClear(tool)
        ForeignWindow.ClearOutcome.KEPT_TEXT -> return foreignFieldKeptText(tool)
      }
    }
    if (ForeignWindow.appendToFocusedField(tool.text, fieldKnownEmpty = tool.clearFirst) == ForeignWindow.AppendOutcome.REJECTED) {
      ForeignWindow.typeKeys(tool.text)?.let { reason ->
        return TrailblazeToolResult.Error.ExceptionThrown(errorMessage = reason, command = tool)
      }
    }
    // The keyboard is left up: the only hide that reaches another app's window is BACK, which can
    // also close or leave the form just typed into. An explicit hideKeyboard step still sends it.
    return TrailblazeToolResult.Success(
      message = "Typed '${tool.text}' in another app's window" +
        if (tool.hideKeyboardAfter) " (keyboard left up: hiding it there can leave the form)" else "",
    )
  }

  /**
   * The in-process equivalent of the canonical `hideKeyboard`: dismiss the IME, and do nothing at
   * all when there is no IME up.
   *
   * The recording driver reaches this through `GLOBAL_ACTION_BACK`
   * (`AccessibilityDeviceManager.hideKeyboard`), and BACK only means "hide the keyboard" while a
   * keyboard is on screen to consume it — the IME window takes the event first. With no IME up it
   * is ordinary back navigation. Sending it unconditionally therefore does not reproduce that
   * driver; it reproduces one of that driver's two behaviours in both situations. On a passcode
   * screen whose keypad is drawn by the app and raises no IME, the BACK that followed `inputText`
   * finished the Activity and the trail failed with `No activities found`.
   *
   * An IME hide request rather than a key event, because it is conditioned on the thing that
   * decides which of those two meanings applies: it asks the [InputMethodManager] to hide the
   * focused window's IME, which no-ops when none is showing and can never navigate. Failing to
   * reach a focused window is not a failure of the trail — the typing already landed — so that is
   * swallowed.
   */
  private fun hideKeyboard(target: AndroidTestTarget) {
    if (ForeignWindow.frontPackage() != null) return ForeignWindow.hideKeyboard()
    val sent = perf("input.hideIme") { runCatching { hideImeAndAwaitReply(target) }.getOrDefault(false) }
    if (!sent) perf("input.closeSoftKeyboard") { runCatching { Espresso.closeSoftKeyboard() } }
    perf("input.hideIdle") { target.waitForIdle() }
  }

  /**
   * Asks the [InputMethodManager] to hide the focused window's IME and waits for its reply — the
   * request Espresso's close-keyboard makes, without the two app syncs it wraps around it, which
   * cost each `inputText` about a second. The [AndroidTestTarget.waitForIdle] after it is the sync.
   *
   * False when the request could not be sent: focus is in a window this Activity's focused View
   * does not own, such as a dialog's. Espresso finds that window itself, so it takes over then.
   */
  private fun hideImeAndAwaitReply(target: AndroidTestTarget): Boolean {
    val activity = target.currentActivity()
    val replied = CountDownLatch(1)
    val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
      override fun onReceiveResult(resultCode: Int, resultData: Bundle?) = replied.countDown()
    }
    val sent = onMainThread {
      val view = activity.currentFocus ?: activity.window.decorView
      activity.getSystemService(InputMethodManager::class.java)
        .hideSoftInputFromWindow(view.windowToken, 0, receiver)
    }
    if (sent) replied.await(IME_REPLY_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    return sent
  }

  /** Espresso's own wait for the same reply. */
  private const val IME_REPLY_TIMEOUT_SECONDS = 2L

  /** The focused node under [node] that carries editable text, or null — the field `clearText` erases. */
  private fun findFocusedEditableText(node: SemanticsNode): SemanticsNode? {
    val config = node.config
    if (config.getOrNull(SemanticsProperties.Focused) == true &&
      config.getOrNull(SemanticsProperties.EditableText) != null
    ) {
      return node
    }
    return node.children.firstNotNullOfOrNull { findFocusedEditableText(it) }
  }

  /**
   * `launchApp` of the app under test, in-process. Every mode relaunches the entry Activity; what
   * differs is what has to be true BEFORE it, because this process is the app and cannot stop or
   * clear itself.
   *
   * - `RESUME` brings the existing task forward.
   * - `FORCE_RESTART` restarts at the entry point. Only the first launch in a process is a real
   *   cold start; the host force-stops the app before every trail, so a trailhead's launch is.
   * - `REINSTALL` needs the app's data cleared. The host runs `pm clear` before starting the
   *   instrumentation when a trailhead's launch asks for it, and says whether it did
   *   ([InProcessDataClearedArg]); [AppUnderTestLauncher.freshDataMarker], written by every
   *   launch and by the first UI tool, and [AppUnderTestLauncher.usedSinceProcessStart] tell a
   *   REINSTALL on that cleared data from one after the app was launched or driven. A REINSTALL
   *   on data that was not cleared refuses by name rather than launching on old data.
   */
  private fun launchAppUnderTest(tool: LaunchAppTrailblazeTool): TrailblazeToolResult {
    val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    val self = targetContext.packageName
    if (tool.appId != self) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "launchApp targets '${tool.appId}', but in-process this driver can only " +
          "launch the app under test ('$self').",
        command = tool,
      )
    }
    val mode = tool.launchMode
    val marker = AppUnderTestLauncher.freshDataMarker()
    if (mode == LaunchAppTrailblazeTool.LaunchMode.REINSTALL) {
      // Absent when no host started this run; a farm starts every test on a fresh install.
      val hostCleared = AndroidTestInstrumentation.stringArg(InProcessDataClearedArg.KEY)?.toBooleanStrictOrNull()
      if (hostCleared == false) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "launchApp REINSTALL needs '$self' to start with cleared data, and the host " +
            "did not clear it before this run. It clears only for a trailhead whose launch is " +
            "REINSTALL, or for a target that asks to be cleared before every trailhead; in-process " +
            "the app cannot clear itself.",
          command = tool,
        )
      }
      if (marker.exists() || AppUnderTestLauncher.usedSinceProcessStart) {
        return TrailblazeToolResult.Error.ExceptionThrown(
          errorMessage = "launchApp REINSTALL needs '$self' to start with cleared data, and it was " +
            "launched or used since it was last cleared. The host clears it before the instrumentation " +
            "starts only for a launch in the trailhead; in-process the app cannot clear itself.",
          command = tool,
        )
      }
    }
    // Every mode, so a REINSTALL after a RESUME or FORCE_RESTART launch sees the data was used.
    AppUnderTestLauncher.markDataUsed()
    // The instrumentation already cold-launched the app at its entry point when this process
    // started, after the host's force-stop (and `pm clear` for REINSTALL). Until something else
    // runs, a restart or reinstall launch would only pay that cold start a second time.
    if (mode != LaunchAppTrailblazeTool.LaunchMode.RESUME && AppUnderTestLauncher.untouchedSinceColdLaunch) {
      return TrailblazeToolResult.Success(
        message = "'$self' ($mode) was cold-launched at its entry point when this in-process run " +
          "started, and nothing has run since; not launching it again.",
      )
    }
    val coldStart = !AppUnderTestLauncher.hasLaunched
    AppUnderTestLauncher.launchAppUnderTest(clearTask = mode != LaunchAppTrailblazeTool.LaunchMode.RESUME)
    return TrailblazeToolResult.Success(
      message = "Launched '$self' ($mode) in-process" +
        if (mode == LaunchAppTrailblazeTool.LaunchMode.FORCE_RESTART && !coldStart) {
          " at its entry point; the process was not restarted."
        } else {
          "."
        },
    )
  }

  /**
   * BACK waits for idle first, then is injected without synchronizing, so it is delivered exactly
   * once even on a screen that never goes idle, and may leave the app. HOME
   * leaves the app's windows, which an instrumentation's own key injection cannot reach, so it goes
   * through the shell. Every other key lands in the focused window like a hardware key.
   *
   * While another app is in front, every key but HOME goes to it through [ForeignWindow]: this
   * process may not inject keys into a window it does not own.
   */
  private fun pressKey(target: AndroidTestTarget, key: PressKeyCode): TrailblazeToolResult {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    if (key != PressKeyCode.HOME && ForeignWindow.frontPackage() != null) {
      when (key) {
        PressKeyCode.BACK -> ForeignWindow.pressBack()
        else -> ForeignWindow.pressKey(key.keyCode())
      }
      return TrailblazeToolResult.Success(message = "Pressed ${key.name} in another app's window.")
    }
    when (key) {
      // Settle, deliver, settle, as separate steps. Espresso's press waits for idle on both sides
      // of the key, so its timeout cannot say whether the key went out, and retrying on one could
      // pop a second screen. waitForIdle gives up quietly on a screen that never goes idle.
      PressKeyCode.BACK -> {
        target.waitForIdle()
        injectKey(instrumentation, KeyEvent.KEYCODE_BACK)
      }
      PressKeyCode.HOME ->
        instrumentation.automation().executeShellCommand("input keyevent ${KeyEvent.KEYCODE_HOME}").close()
      PressKeyCode.ENTER, PressKeyCode.BACKSPACE, PressKeyCode.TAB, PressKeyCode.ESCAPE ->
        instrumentation.sendKeyDownUpSync(key.keyCode())
    }
    target.waitForIdle()
    return TrailblazeToolResult.Success(message = "Pressed ${key.name}.")
  }

  /**
   * UiAutomation with the flags every other caller here uses: asking with different ones reconnects
   * it, which switches accessibility off and on for every app and drops a capture in flight.
   */
  private fun android.app.Instrumentation.automation(): UiAutomation =
    getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

  /** Down and up, injected and waited for, with no Espresso or Compose synchronization. */
  private fun injectKey(instrumentation: android.app.Instrumentation, keyCode: Int) {
    val downAt = android.os.SystemClock.uptimeMillis()
    for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
      val event = KeyEvent(downAt, android.os.SystemClock.uptimeMillis(), action, keyCode, 0)
      check(instrumentation.automation().injectInputEvent(event, true)) { "The device refused key $keyCode." }
    }
  }

  private fun PressKeyCode.keyCode(): Int = when (this) {
    PressKeyCode.BACK -> KeyEvent.KEYCODE_BACK
    PressKeyCode.HOME -> KeyEvent.KEYCODE_HOME
    PressKeyCode.ENTER -> KeyEvent.KEYCODE_ENTER
    PressKeyCode.BACKSPACE -> KeyEvent.KEYCODE_DEL
    PressKeyCode.TAB -> KeyEvent.KEYCODE_TAB
    PressKeyCode.ESCAPE -> KeyEvent.KEYCODE_ESCAPE
  }

  /** Maestro's swipe duration when a recording names none. */
  private const val DEFAULT_SWIPE_MS = 400L
  private const val ACCESSIBILITY_CLICK_TIMEOUT_MS = 10_000L
  private const val ACCESSIBILITY_CLICK_POLL_MS = 200L

  /** `"50%,90%"` as a pixel point on a [width] x [height] display, or null when it does not parse. */
  private fun relativeScreenPoint(relative: String, width: Int, height: Int): Pair<Int, Int>? {
    val (x, y) = TapOnTrailblazeTool.parseRelativePoint(relative) ?: return null
    return (width * x / 100).toInt().coerceIn(0, width - 1) to (height * y / 100).toInt().coerceIn(0, height - 1)
  }

  private fun malformed(reason: String, tool: TrailblazeTool): TrailblazeToolResult =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "$reason — malformed recording.",
      command = tool,
    )

  private fun unsupported(option: String, tool: TrailblazeTool): TrailblazeToolResult =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "${tool::class.simpleName} with $option is not supported on the in-process " +
        "ANDROID_TEST driver yet. Failing loudly instead of degrading the recorded behavior — " +
        "replay this trail on the accessibility driver, or add the capability to " +
        "CanonicalToolAdapters.",
      command = tool,
    )
}
