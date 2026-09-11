package xyz.block.trailblaze.android.test.tools

import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.random.Random
import maestro.SwipeDirection
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.api.DriverNodeMatch
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.maestro.TrailblazeScrollStartPosition
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.AssertNotVisibleWithTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.AssertVisibleBySelectorTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextRandomTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.InputTextTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.MaestroTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.ScrollUntilTextIsVisibleTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.SwipeTrailblazeTool
import xyz.block.trailblaze.toolcalls.commands.TapOnByElementSelector
import xyz.block.trailblaze.toolcalls.commands.WaitForIdleSyncTrailblazeTool

/**
 * Dispatches the CANONICAL recorded-trail tools — `tapOnElementBySelector` and the rest of the
 * vocabulary every recorded trail in the estate is written in — onto this driver's own backends.
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
 * covered here is the estate's measured vocabulary; growing it is adding an arm.
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
      // tapRoute is ignored, matching every non-accessibility driver: it pins how the
      // ACCESSIBILITY driver routes a tap, and its own contract says other drivers dispatch
      // their one native gesture unconditionally.
      AndroidTestTapTool(nodeSelector = selector, longPress = tool.longPress)
        .executeWithAndroidTest(target, context)
    }

    is AssertVisibleBySelectorTrailblazeTool -> CanonicalDispatch { target, context ->
      val selector = tool.nodeSelector
        ?: return@CanonicalDispatch malformed("assertVisibleBySelector has no nodeSelector", tool)
      val visibility = AndroidTestAssertVisibleTool(nodeSelector = selector, timeoutMs = tool.timeoutMs)
        .executeWithAndroidTest(target, context)
      // `expectedText` is the canonical tool's own post-pass (re-resolve, read the matched
      // element's text, compare under textMatchMode) and runs on the shared resolver against
      // this driver's tree via context.screenStateProvider — so it is delegated, not mirrored:
      // one implementation is what keeps EXACT/PREFIX/REGEX and the Zs-folding identical on
      // every driver. No-op when expectedText is unset or the visibility check failed.
      tool.applyExpectedTextPostPass(context, visibility)
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

    is ScrollUntilTextIsVisibleTrailblazeTool -> CanonicalDispatch { target, context ->
      // Which options this driver's scroll loop can carry out is decided in one place, shared with
      // the Maestro interpreter's `scrollUntilVisible`, so the two entry points onto the same loop
      // cannot answer differently. A non-default value refuses rather than being quietly dropped.
      val refusal = ScrollOptionSupport.unsupportedDirection(tool.direction)
        ?: ScrollOptionSupport.unsupportedVisibilityPercentage(tool.visibilityPercentage)
        ?: ScrollOptionSupport.unsupportedCenterElement(tool.centerElement)
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
      ).executeWithAndroidTest(target, context)
    }

    is SwipeTrailblazeTool -> CanonicalDispatch { target, _ ->
      if (tool.swipeOnElementText != null) {
        return@CanonicalDispatch unsupported("swipeOnElementText", tool)
      }
      when (tool.direction) {
        // Finger up = reveal content below = scroll forward, on the same "largest scrollable"
        // heuristic the driver's own scroll tool uses.
        SwipeDirection.UP ->
          if (AndroidScrollActions.scrollForward(target)) {
            TrailblazeToolResult.Success(message = "Swiped UP (scrolled the largest container forward).")
          } else {
            malformed(
              "swipe UP found nothing that can scroll. ${AndroidScrollActions.describeCandidates(target)}",
              tool,
            )
          }
        else -> unsupported("direction=${tool.direction}", tool)
      }
    }

    // Raw Maestro command lists — the shape the estate's scripted launch tools fall back to on
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

    is InputTextTrailblazeTool -> CanonicalDispatch { target, _ ->
      // Key-event injection into the focused window — the same semantics the instrumentation
      // driver this vocabulary was recorded under gives `inputText`, and the only shape that
      // also reaches a passcode keypad listening for key presses rather than an EditText.
      InstrumentationRegistry.getInstrumentation().sendStringSync(tool.text)
      target.waitForIdle()
      // `hideKeyboardAfter` defaults TRUE and every recorded trail omits it, so this half runs on
      // essentially every recorded `inputText` — skipping it made this driver the only one that
      // leaves the field focused afterwards, and that is a state the app can see. One trail
      // types an item note and taps Save: while the note field holds focus the app renders its
      // footer as Clear + "close keyboard" and the recorded `id~"item-details-done"` Save button
      // does not exist, so the next step failed on a screen the recording never encountered.
      if (tool.hideKeyboardAfter) {
        hideKeyboard(target)
      }
      TrailblazeToolResult.Success(message = "Typed '${tool.text}'")
    }

    // Generate-then-type, in one step. The generator is the canonical tool's own — shared so the
    // value's SHAPE (prefix, digit pool, suffix) cannot drift between drivers — and the typing is
    // this driver's `inputText`, down to the hide-keyboard half. The remember happens here rather
    // than host-side for the same reason the canonical body does it inline: this execution is the
    // one that produced the value, and its memory write is what rides back in the RPC snapshot so
    // a later `{{variable}}` resolves.
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
   * The in-process equivalent of the canonical `hideKeyboard`: dismiss the IME, and do nothing at
   * all when there is no IME up.
   *
   * The recording driver reaches this through `GLOBAL_ACTION_BACK`
   * (`AccessibilityDeviceManager.hideKeyboard`), and BACK only means "hide the keyboard" while a
   * keyboard is on screen to consume it — the IME window takes the event first. With no IME up it
   * is ordinary back navigation. Sending it unconditionally therefore does not reproduce that
   * driver; it reproduces one of that driver's two behaviours in both situations. One CI run lost
   * a trail to `No activities found` on the team-passcode screen, whose keypad is drawn by the
   * app and raises no IME, so the BACK that followed `inputText` finished the Activity.
   *
   * Espresso's close-keyboard action rather than a key event, because it is conditioned on the
   * thing that decides which of those two meanings applies: it asks the
   * [android.view.inputmethod.InputMethodManager] to hide the focused window's IME, which no-ops
   * when none is showing and can never navigate. It fails loudly when it cannot reach a focused
   * root, which is not a failure of the trail — the typing already landed — so that is swallowed.
   */
  private fun hideKeyboard(target: AndroidTestTarget) {
    runCatching { Espresso.closeSoftKeyboard() }
    target.waitForIdle()
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
