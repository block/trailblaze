package xyz.block.trailblaze.android.test.tools

import android.util.Log
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.android.test.hierarchy.ForeignWindow
import xyz.block.trailblaze.toolcalls.commands.TapOnTrailblazeTool
import xyz.block.trailblaze.android.test.hierarchy.AndroidHybridHierarchyCollector
import xyz.block.trailblaze.android.test.onMainThread
import androidx.compose.ui.platform.ViewRootForTest
import ai.koog.agents.core.tools.annotations.LLMDescription
import android.os.SystemClock
import android.view.View
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.android.test.AndroidTestPhaseAttribution
import xyz.block.trailblaze.android.test.AnotherAppInFrontException
import xyz.block.trailblaze.android.test.AndroidTestScreenState
import xyz.block.trailblaze.android.test.AndroidTestStopwatch
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.perf
import xyz.block.trailblaze.android.test.DRIVER_TRACE_CAT
import xyz.block.trailblaze.android.test.hierarchy.InProcessAccessibilityTree
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.exception.TrailblazeToolExecutionException
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.tracing.TrailblazeTracer

/*
 * The ANDROID_TEST driver's ENTIRE trail-facing surface: three tools that name an intent, never a
 * backend. Each resolves `nodeSelector` against the hybrid View + Compose hierarchy and then acts on
 * the exact node that matched, through whichever backend owns it.
 *
 * Selectors do all the matching. Espresso and the Compose rule are only asked to synchronize and to
 * act on an identity — a `View` instance, a `SemanticsNode.id`. Nothing re-describes a matched node
 * as native matcher arguments, because that made the framework search a second time under different
 * semantics and it could land somewhere else: `androidView`'s regexes are case-sensitive and
 * anchored, Espresso's `withText` is exact equality, and Compose's `hasText` matches many nodes.
 *
 * A trail therefore never names a backend. Which one owns a node is derived from the hierarchy at
 * run time, so a screen re-laid-out from Views to Compose keeps replaying the same trail.
 */

@Serializable
@TrailblazeToolClass("androidTest_tap")
@LLMDescription("Tap an Android element by selector. Works for both classic Views and composables.")
data class AndroidTestTapTool(
  val nodeSelector: TrailblazeNodeSelector,
  @param:LLMDescription("Long press instead of tap, e.g. to open a context menu.")
  val longPress: Boolean = false,
  @param:LLMDescription(RESOLVE_TIMEOUT_DESCRIPTION) val timeoutMs: Long? = null,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching {
    // The screen can recompose between resolving the node and tapping it; resolve it again.
    repeat(NODE_GONE_RETRIES - 1) {
      try {
        return@runCatching tapOnce(target, context)
      } catch (_: ComposeNodeGoneException) {
      }
    }
    tapOnce(target, context)
  }.getOrElse { thrown(it, this) }

  private suspend fun tapOnce(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    val verb = if (longPress) "Long-pressed" else "Tapped"
    return when (val resolved = resolveNode(target, nodeSelector, this, context, timeoutMs)) {
      is ResolvedNode.ViewNode ->
        if (longPress) {
          AndroidViewActions.longClick(target, resolved.view)
          succeeded(verb, nodeSelector, resolved, null)
        } else {
          succeeded(verb, nodeSelector, resolved, AndroidViewActions.click(target, resolved.view))
        }
      is ResolvedNode.ComposeNode ->
        if (longPress) {
          AndroidComposeActions.longClick(target, resolved.semanticsId)
          succeeded(verb, nodeSelector, resolved, null)
        } else {
          succeeded(verb, nodeSelector, resolved, AndroidComposeActions.click(target, resolved.semanticsId))
        }
      is ResolvedNode.ScreenPoint -> {
        if (resolved.inForeignWindow) {
          ForeignWindow.tap(resolved.x, resolved.y, longPress)
          return TrailblazeToolResult.Success(
            message = "$verb ${nodeSelector.description()} at its centre, in another app's window.",
          )
        }
        if (longPress || !AndroidScrollActions.tapScreenPoint(target, resolved.x, resolved.y)) {
          throw TrailblazeToolExecutionException(
            "Matched ${nodeSelector.description()} in the accessibility tree, but no live View or " +
              "Compose node owns it and it can't be tapped by position here.",
            this,
          )
        }
        TrailblazeToolResult.Success(message = "$verb ${nodeSelector.description()} at its centre.")
      }
    }
  }
}

// Not in the tool's companion: the serializer is looked up on the companion by reflection, and a
// private companion makes the tool unloadable.
private const val NODE_GONE_RETRIES = 3

/**
 * The in-process form of the canonical `tapOn`: resolves [nodeSelector] as the other tap tools do,
 * then touches the point [relativePoint] names inside the element's bounds (its centre when
 * omitted). The point is the whole reason a trail records `tapOn` rather than a plain tap: one node
 * can own two targets, such as a card header whose top half opens one sheet and bottom half
 * another, so the touch goes through the system's input pipeline at that point rather than to the
 * node's click handler.
 */
internal data class AndroidTestTapWithinTool(
  val nodeSelector: TrailblazeNodeSelector,
  val relativePoint: String?,
  val longPress: Boolean,
  val doubleTap: Boolean,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching {
    val resolved = resolveNode(target, nodeSelector, this, context, null)
    // The canonical tapOn refuses a selector that names more than one element rather than touching
    // the first. A recorded accessibility selector is checked against the accessibility tree it was
    // recorded on, so a step that was unique there stays unique here, and one that was not fails
    // as it would have. (The View/Compose tree is not that tree: a merged node and its child can
    // both match there without the recording being ambiguous.)
    if (nodeSelector.isAccessibilityShaped()) {
      val templateContext = context.resolvedTarget?.let { TargetTemplateContext(appId = context.appId, appIds = it.appIds) }
      val placed = placedA11yMatches(nodeSelector, templateContext)
      if (placed != null && placed.size > 1) {
        throw TrailblazeToolExecutionException(
          "tapOn: selector is ambiguous (${placed.size} matches) — add spatial, hierarchy, or index " +
            "disambiguation. Selector: ${nodeSelector.description()}",
          this,
        )
      }
    }
    val bounds = resolved.node.bounds
      ?: throw TrailblazeToolExecutionException("tapOn: ${nodeSelector.description()} has no bounds.", this)
    val (x, y) = TapOnTrailblazeTool.tapPointIn(bounds, relativePoint, tool = this)
    ForeignWindow.tap(x, y, longPress = longPress, doubleTap = doubleTap)
    if (ForeignWindow.frontPackage() == null) target.waitForIdle()
    val verb = when {
      longPress -> "Long pressed"
      doubleTap -> "Double-tapped"
      else -> "Tapped"
    }
    TrailblazeToolResult.Success(message = "$verb at ($x, $y) within ${nodeSelector.description()}.")
  }.getOrElse { thrown(it, this) }
}

@Serializable
@TrailblazeToolClass("androidTest_type")
@LLMDescription(
  "Replace the text of an Android element by selector. Works for both classic Views and composables."
)
data class AndroidTestTypeTool(
  @param:LLMDescription("Text to enter.") val value: String,
  val nodeSelector: TrailblazeNodeSelector,
  @param:LLMDescription(RESOLVE_TIMEOUT_DESCRIPTION) val timeoutMs: Long? = null,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching {
    when (val resolved = resolveNode(target, nodeSelector, this, context, timeoutMs)) {
      is ResolvedNode.ViewNode ->
        succeeded(
          "Entered text into",
          nodeSelector,
          resolved,
          AndroidViewActions.replaceText(target, resolved.view, value),
        )
      is ResolvedNode.ComposeNode ->
        succeeded(
          "Entered text into",
          nodeSelector,
          resolved,
          AndroidComposeActions.replaceText(target, resolved.semanticsId, value),
        )
      is ResolvedNode.ScreenPoint -> throw TrailblazeToolExecutionException(
        "Matched ${nodeSelector.description()} in the accessibility tree, but no live View or " +
          "Compose node owns it, so there is nothing to type into.",
        this,
      )
    }
  }.getOrElse { thrown(it, this) }
}

@Serializable
@TrailblazeToolClass("androidTest_assertVisible", isVerification = true)
@LLMDescription(
  "Verify an Android element is displayed, by selector. Works for both classic Views and composables."
)
data class AndroidTestAssertVisibleTool(
  val nodeSelector: TrailblazeNodeSelector,
  @param:LLMDescription(RESOLVE_TIMEOUT_DESCRIPTION) val timeoutMs: Long? = null,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching { verify(target, context) }
    .getOrElse { thrown(it, this) }

  /**
   * The check itself, throwing on failure. A wait that ran out is a [SelectorWaitMissException],
   * which is how the Maestro interpreter tells "the screen said no" from a broken check.
   */
  internal fun verify(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult.Success {
    // Asserted against the backend as well as the selector: the selector proved the node is in the
    // tree with on-screen bounds, and the native check proves the backend agrees it is displayed.
    //
    // An ambiguous match resolves to the first placed node here as everywhere else (see
    // `pollForNode`), which suits an assert especially: it has no side effect to misplace, and a
    // selector matching twice satisfies "this is visible" twice over. Recordings need that — the
    // trees they were made against project a container and its text into one node, so a recorded
    // assert that resolved to one element routinely resolves to that pair on this driver's denser
    // tree.
    //
    // A view that detaches between the two was the outgoing screen's: a selector that names a
    // title bar by id alone matches the screen being replaced as well as its replacement, so it
    // resolves again against the screen that arrived rather than failing on the one that left.
    var attempts = 0
    while (true) {
      try {
        when (val resolved = resolveNode(target, nodeSelector, this, context, timeoutMs)) {
          is ResolvedNode.ViewNode -> AndroidViewActions.assertDisplayed(target, resolved.view)
          is ResolvedNode.ComposeNode ->
            AndroidComposeActions.assertDisplayed(target, resolved.semanticsId)
          // Placed in the platform's own accessibility tree, which is what the recording asserted.
          is ResolvedNode.ScreenPoint -> Unit
        }
        return TrailblazeToolResult.Success(message = "Verified ${nodeSelector.description()} is visible.")
      } catch (e: ElementDetachedException) {
        if (++attempts >= DETACHED_RESOLVE_ATTEMPTS) throw e
      }
    }
  }
}

@Serializable
@TrailblazeToolClass("androidTest_assertNotVisible", isVerification = true)
@LLMDescription(
  "Verify NO Android element matches the selector. Works for both classic Views and composables."
)
data class AndroidTestAssertNotVisibleTool(
  val nodeSelector: TrailblazeNodeSelector,
  @param:LLMDescription(RESOLVE_TIMEOUT_DESCRIPTION) val timeoutMs: Long? = null,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching { verify(target, context) }
    .getOrElse { thrown(it, this) }

  /** The check itself, throwing on failure. See [AndroidTestAssertVisibleTool.verify]. */
  internal fun verify(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult.Success {
    // The resolve poll inverted: the expected answer is "no placed match" on the first read, and
    // a match gets until the deadline to LEAVE — an outgoing screen's node is still in the tree
    // while it animates away, the same settling the positive assert tolerates in the other
    // direction. Placement gates it the same way too: a zero-area leftover in the tree is not
    // visible to anyone, so it must not fail a not-visible assertion.
    val orchestration = AndroidTestStopwatch()
    try {
      val budgetMs = timeoutMs ?: RESOLVE_TIMEOUT_MS
      val deadline = SystemClock.uptimeMillis() + budgetMs
      // A read that could not see the whole screen proves nothing about absence, so it is polled
      // again like a match would be.
      while (true) {
        val placed = placedOnScreen(target, nodeSelector, context)
        if (placed != null && placed.isEmpty()) break
        if (SystemClock.uptimeMillis() >= deadline) {
          ForeignWindow.frontPackage()?.let { requireMatchableInForeignWindow(it, nodeSelector) }
          throw SelectorWaitMissException(
            if (placed == null) {
              "Could not read the whole screen within ${budgetMs}ms to confirm this is gone: "
            } else {
              "Selector still matched a visible element after ${budgetMs}ms: "
            } + nodeSelector.description(),
          )
        }
        SystemClock.sleep(RESOLVE_POLL_MS)
      }
      return TrailblazeToolResult.Success(
        message = "Verified ${nodeSelector.description()} is not visible.",
      )
    } finally {
      // The whole check is tree walking — Trailblaze's work, not the backend's — so it lands on
      // the benchmark's orchestration side, exactly like the positive assert's resolve poll.
      AndroidTestPhaseAttribution.addOrchestration(orchestration.elapsedMs())
    }
  }
}

@Serializable
@TrailblazeToolClass("androidTest_scrollUntilVisible")
@LLMDescription(
  "Scroll until an Android element is on screen, by selector. Use when it is below the fold: lazy " +
    "lists omit off-screen rows, so waiting never finds them."
)
data class AndroidTestScrollUntilVisibleTool(
  val nodeSelector: TrailblazeNodeSelector,
  /**
   * Wall-clock ceiling on the whole loop, or null for the scroll cap alone.
   *
   * The cap counts scrolls, which is the right bound for "how far down a trail should look"; a
   * caller that recorded a TIME bound (Maestro's `scrollUntilVisible: timeout`) means something the
   * cap cannot express — a slow list can burn a long time inside far fewer than [MAX_SCROLLS]
   * scrolls. Both bounds apply, and whichever is reached first ends the loop.
   */
  val timeoutMs: Long? = null,
  /**
   * Once the element is on screen, scroll its container so the element sits at the container's
   * vertical centre, as far as the content allows — what a recording's `centerElement: true` asks
   * of the Maestro-backed drivers.
   */
  val centerElement: Boolean = false,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching {
    suspend fun found(scrolls: Int): TrailblazeToolResult {
      val centred = centerElement && centreOnScreen(target, nodeSelector, context)
      return TrailblazeToolResult.Success(
        message = "Scrolled ${nodeSelector.description()} into view after $scrolls scrolls" +
          (if (centred) ", then centred it." else "."),
      )
    }
    var scrolls = 0
    var idleAttempts = 0
    var triedOwnAncestor = false
    var unreadableReads = 0
    val deadline = timeoutMs?.let { SystemClock.uptimeMillis() + it }
    while (scrolls < MAX_SCROLLS && (deadline == null || SystemClock.uptimeMillis() < deadline)) {
      // Matched against a snapshot directly rather than through the resolver: the element is
      // expected to be absent for most of this loop, and paying the resolver's settle budget on
      // every attempt would turn a ten-row scroll into a minute of waiting.
      val placed = placedOnScreen(target, nodeSelector, context)
      if (!placed.isNullOrEmpty()) return@runCatching found(scrolls)
      // A screen read only in part (an index counts matches, so a dropped one shifts it) says
      // neither yes nor no. Scrolling on it could move a target already in view out of it, so read
      // again first, a few times, before treating it as absent.
      if (placed == null && unreadableReads < UNREADABLE_RETRIES) {
        unreadableReads++
        SystemClock.sleep(UNSCROLLABLE_RETRY_MS)
        continue
      }
      unreadableReads = 0
      // Every scroll below works on this app's own containers and waits for its Activity, which is
      // paused behind another app's window. With a time bound, keep looking until it runs out (the
      // other app may still be loading); without one, say this driver does not scroll that window.
      ForeignWindow.frontPackage()?.let { foreignPackage ->
        if (deadline == null) {
          throw TrailblazeToolExecutionException(
            "${nodeSelector.description()} is not on screen, and $foreignPackage's window is in " +
              "front of the app: this driver does not scroll another app's window.",
            this,
          )
        }
        SystemClock.sleep(UNSCROLLABLE_RETRY_MS)
        continue
      }
      // A node that is already in the tree but has no bounds is inside a container that has not
      // scrolled to it, and only the node knows which container that is. Scrolling "the biggest
      // scrollable on screen" instead is wrong under an overlay, where the screen underneath stays
      // attached and scrollable and is usually the bigger of the two.
      //
      // Spent only once an attempt was actually POSSIBLE. In the lazy-list case the row does not
      // exist yet, so the first pass has nothing to ask; marking the attempt used there would hand
      // the rest of the call to the screen-scrolling fallback — exactly the overlay case this path
      // exists for — the moment the row finally composed.
      if (!triedOwnAncestor) {
        when (scrollToOwnAncestor(target, nodeSelector, context)) {
          OwnAncestorScroll.SCROLLED -> {
            // Compose scrolls the node fully into view, so a repeat would do nothing new.
            triedOwnAncestor = true
            scrolls++
            idleAttempts = 0
            continue
          }
          // The node was there and could not be scrolled to; asking again would fail the same way.
          OwnAncestorScroll.REFUSED -> triedOwnAncestor = true
          // Nothing matched, so nothing was asked. Keep the attempt for when the row composes.
          OwnAncestorScroll.NO_NODE_TO_ASK -> Unit
        }
      }
      if (AndroidScrollActions.scrollForward(target)) {
        scrolls++
        idleAttempts = 0
        continue
      }
      // "Nothing can scroll" is not a reliable end-of-content signal on its own: a lazy list that
      // has been composed but not yet measured reports the same thing as one scrolled to its
      // bottom. Only a container that stays unable to move across several attempts is at its end.
      //
      // With a time bound, the end of the list does not end the search either: the Maestro-backed
      // drivers keep looking until the bound runs out, and recordings lean on that as a wait. A
      // screen still behind a loading overlay has nothing to scroll for a few
      // seconds, and giving up then fails a step those drivers pass.
      idleAttempts++
      if (deadline == null && idleAttempts >= UNSCROLLABLE_ATTEMPTS) {
        throw TrailblazeToolExecutionException(
          if (scrolls == 0) {
            "Nothing on this screen can scroll, and ${nodeSelector.description()} is not on it. " +
              AndroidScrollActions.describeCandidates(target)
          } else {
            "Scrolled to the end after $scrolls scrolls and ${nodeSelector.description()} never " +
              "appeared."
          },
          this,
        )
      }
      SystemClock.sleep(UNSCROLLABLE_RETRY_MS)
    }
    // The loop tests visibility BEFORE each scroll, so the last permitted scroll's result would
    // otherwise never be looked at: reaching the cap with the target now on screen would report a
    // failure the screen contradicts.
    if (isOnScreen(target, nodeSelector, context)) return@runCatching found(scrolls)
    // Which bound ended the search, read from the bound itself. Inferring it from the scroll count
    // instead would name the wrong one whenever both are reached in the same pass — and the count
    // is the one that gets there first on a screen whose snapshot is cheap.
    throw TrailblazeToolExecutionException(
      if (deadline != null && SystemClock.uptimeMillis() >= deadline) {
        if (idleAttempts >= UNSCROLLABLE_ATTEMPTS) {
          "Scrolled to the end after $scrolls scrolls and ${nodeSelector.description()} never " +
            "appeared within ${timeoutMs}ms."
        } else {
          "Gave up after ${timeoutMs}ms without ${nodeSelector.description()} appearing, having " +
            "scrolled $scrolls times. Either the element is further down than the timeout allows " +
            "for, or the selector is wrong."
        }
      } else {
        "Gave up after $MAX_SCROLLS scrolls without ${nodeSelector.description()} appearing. The " +
          "container was still scrolling, so either the element is much further down than a trail " +
          "should scroll for, or the selector is wrong."
      },
      this,
    )
  }.getOrElse { thrown(it, this) }
}

/** The node a selector resolved to, paired with the handle its backend can act on. */
private sealed interface ResolvedNode {
  val node: TrailblazeNode

  data class ViewNode(override val node: TrailblazeNode, val view: View) : ResolvedNode

  data class ComposeNode(override val node: TrailblazeNode, val semanticsId: Int) : ResolvedNode

  /**
   * Matched in the accessibility tree and owned by nothing live here, so acted on by its centre.
   * [inForeignWindow] when another app's window holds it, which only an injected touch can reach.
   */
  data class ScreenPoint(
    override val node: TrailblazeNode,
    val x: Int,
    val y: Int,
    val inForeignWindow: Boolean = false,
  ) : ResolvedNode
}

/**
 * The tool's failure as a result, with its stack written to logcat first: the result carries the
 * message alone, and a timeout's message does not say which of a tool's waits ran out.
 */
private fun thrown(failure: Throwable, tool: TrailblazeTool): TrailblazeToolResult.Error.ExceptionThrown {
  Log.w("Trailblaze", "${tool::class.simpleName} failed", failure)
  return TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(failure, tool)
}

/**
 * The tool's result line. Names the element by the SELECTOR the trail author wrote, not by
 * `nodeId` — that is a counter over one snapshot, so it identifies nothing to someone reading the
 * report and does not survive to the next run.
 */
private fun succeeded(
  verb: String,
  selector: TrailblazeNodeSelector,
  resolved: ResolvedNode,
  relocation: String?,
): TrailblazeToolResult {
  val backend = when (resolved) {
    is ResolvedNode.ComposeNode -> "Compose node"
    is ResolvedNode.ViewNode -> "View"
    is ResolvedNode.ScreenPoint -> "accessibility node"
  }
  val message = "$verb $backend ${selector.description()}."
  return TrailblazeToolResult.Success(
    message = if (relocation == null) message else "$message $relocation",
  )
}

/**
 * Whether [selector] matches a laid-out node on the screen as it is right now.
 *
 * One snapshot, no waiting: used by the scroll loop, where "not there" is the expected answer on
 * every attempt but the last and is what drives the next scroll. Ambiguity counts as present —
 * the element is on screen either way, and it is the following tool's job to refuse to act on an
 * ambiguous selector.
 */
private fun isOnScreen(
  target: AndroidTestTarget,
  selector: TrailblazeNodeSelector,
  context: TrailblazeToolExecutionContext,
): Boolean = placedOnScreen(target, selector, context).orEmpty().isNotEmpty()

/**
 * The laid-out nodes [selector] matches on the screen as it is right now, or null when the screen
 * could not be read in full (no accessibility root, or a child the app did not answer for): an
 * empty answer from such a read is not proof that the element is gone. Null too while another app
 * is in front of a selector only the app's own tree can match: that app is often in front only
 * for a moment, so callers wait on it as on any unreadable screen rather than failing at once.
 */
private fun placedOnScreen(
  target: AndroidTestTarget,
  selector: TrailblazeNodeSelector,
  context: TrailblazeToolExecutionContext,
): List<TrailblazeNode>? {
  val foreignPackage = ForeignWindow.frontPackage()
  if (foreignPackage == null) target.waitForIdle()
  val templateContext = context.resolvedTarget?.let { resolved ->
    TargetTemplateContext(appId = context.appId, appIds = resolved.appIds)
  }
  if (foreignPackage != null && !selector.isAccessibilityShaped()) return null
  if (foreignPackage != null || usesAccessibilityTree(selector)) return placedA11yMatches(selector, templateContext)
  val screenState = try {
    AndroidTestScreenState(target, includeScreenshot = false)
  } catch (_: AnotherAppInFrontException) {
    // Another app reached the front after the read above; read the screen through it instead.
    return placedOnScreen(target, selector, context)
  }
  val placed = placedMatches(TrailblazeNodeSelectorResolver.resolve(screenState.requiredNodeTree, selector, templateContext))
  // A virtual node (one a View draws and exposes only through its own provider) is not in that
  // tree, and the positive lookup finds it in the accessibility tree, so absence is not confirmed
  // until that tree agrees.
  if (placed.isNotEmpty() || !screenState.exposesVirtualNodes(selector)) return placed
  return placedA11yMatches(selector, templateContext)
}

/** Placed matches in the accessibility tree, or null when it could not be read in full. */
private fun placedA11yMatches(
  selector: TrailblazeNodeSelector,
  templateContext: TargetTemplateContext?,
): List<TrailblazeNode>? {
  val a11y = InProcessAccessibilityTree.capture(uncached = true) ?: return null
  return placedA11yMatchesIn(a11y, selector, templateContext)
}

/**
 * Placed matches in [a11y], the ones visible to the user first, or null when [a11y] is a partial
 * read that cannot answer for [selector].
 */
internal fun placedA11yMatchesIn(
  a11y: InProcessAccessibilityTree.Captured,
  selector: TrailblazeNodeSelector,
  templateContext: TargetTemplateContext?,
): List<TrailblazeNode>? {
  // A partial read would shift an index onto the next match, so it is not an answer either way.
  if (selector.countsMatches() && !a11y.isComplete) return null
  val placed = placedMatches(xyz.block.trailblaze.api.resolveImportantForAccessibilityFirst(a11y.tree, selector, templateContext))
  return if (placed.isEmpty() && !a11y.isComplete) null else placed
}

/**
 * Another app's window is read only as an accessibility tree, so only an accessibility selector
 * can be matched in it. Anything else fails here: matching nothing would read as "not there",
 * and pass an absence check while the element is on screen.
 */
private fun requireMatchableInForeignWindow(foreignPackage: String, selector: TrailblazeNodeSelector) {
  if (selector.isAccessibilityShaped()) return
  throw IllegalStateException(
    "${selector.description()} cannot be matched in $foreignPackage's window, which is in front of " +
      "the app: only accessibility selectors are matched against another app's window. Record this " +
      "step with an accessibility selector.",
  )
}

/**
 * Scrolls the first placed match to its container's vertical centre. False when it was no longer
 * on screen or nothing around it scrolls; the element is still visible either way, which is what
 * the scroll was for.
 */
private suspend fun centreOnScreen(
  target: AndroidTestTarget,
  selector: TrailblazeNodeSelector,
  context: TrailblazeToolExecutionContext,
): Boolean {
  // Centring scrolls this app's own containers, and waits for its Activity to find them. Another
  // app's window has none of those; the element is already on screen, so leave it where it is.
  if (ForeignWindow.frontPackage() != null) return false
  val bounds = placedOnScreen(target, selector, context)?.firstOrNull()?.bounds ?: return false
  return AndroidScrollActions.centreVertically(
    target,
    x = (bounds.left + bounds.right) / 2,
    y = (bounds.top + bounds.bottom) / 2,
  )
}

/** Why an own-ancestor scroll did or did not happen — the caller spends its one attempt on two of these. */
private enum class OwnAncestorScroll {
  /** The node asked its own container to bring it into view, and the container did. */
  SCROLLED,

  /** The node was in the tree but could not be scrolled to — no scrollable ancestor. */
  REFUSED,

  /** The selector matched nothing, so there was no node to ask. */
  NO_NODE_TO_ASK,
}

/**
 * Asks the node [selector] matched to bring itself into view, and reports what happened.
 *
 * The two failure modes are worth telling apart. [NO_NODE_TO_ASK] is the lazy-list case, where the
 * row does not exist until the list is scrolled — nothing was attempted, so the caller should keep
 * its attempt for once the row composes. [REFUSED] means Compose had the node and would not scroll
 * to it, which will not change on a retry.
 *
 * Only Compose has this. The View collector drops a view with no visible rect, so a matched
 * `androidView` node is on screen by construction and never reaches here unplaced.
 */
private suspend fun scrollToOwnAncestor(
  target: AndroidTestTarget,
  selector: TrailblazeNodeSelector,
  context: TrailblazeToolExecutionContext,
): OwnAncestorScroll {
  target.waitForIdle()
  val templateContext = context.resolvedTarget?.let { resolved ->
    TargetTemplateContext(appId = context.appId, appIds = resolved.appIds)
  }
  val screenState = AndroidTestScreenState(target, includeScreenshot = false)
  if (usesAccessibilityTree(selector)) {
    val a11y = InProcessAccessibilityTree.capture(uncached = true) ?: return OwnAncestorScroll.NO_NODE_TO_ASK
    if (selector.countsMatches() && !a11y.isComplete) return OwnAncestorScroll.NO_NODE_TO_ASK
    val node = (xyz.block.trailblaze.api.resolveImportantForAccessibilityFirst(a11y.tree, selector, templateContext) as?
      TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch)?.node ?: return OwnAncestorScroll.NO_NODE_TO_ASK
    val owner = a11y.infoFor(node)?.let { screenState.ownerOf(it) } as? InProcessAccessibilityTree.Owner.ComposeOwner
      ?: return OwnAncestorScroll.NO_NODE_TO_ASK
    return if (runCatching { AndroidComposeActions.scrollTo(target, owner.semanticsId) }.isSuccess) {
      OwnAncestorScroll.SCROLLED
    } else {
      OwnAncestorScroll.REFUSED
    }
  }
  val semanticsId = singleComposeMatch(screenState.requiredNodeTree, screenState.semanticsIdByNodeId, selector, templateContext)
    // A composed item clipped to nothing inside its scroller is left out of every capture, though
    // its own container can still bring it back; so it is looked for again with such nodes kept.
    ?: AndroidHybridHierarchyCollector.collect(target.currentActivity(), target, keepClipped = true).let { unpruned ->
      singleComposeMatch(unpruned.trailblazeTree, unpruned.semanticsIdByNodeId, selector, templateContext)
    }
    ?: return OwnAncestorScroll.NO_NODE_TO_ASK
  return if (runCatching { AndroidComposeActions.scrollTo(target, semanticsId) }.isSuccess) {
    OwnAncestorScroll.SCROLLED
  } else {
    OwnAncestorScroll.REFUSED
  }
}

/** The semantics id of the one node [selector] matches in [tree], or null. */
private fun singleComposeMatch(
  tree: TrailblazeNode,
  semanticsIdByNodeId: Map<Long, Int>,
  selector: TrailblazeNodeSelector,
  templateContext: TargetTemplateContext?,
): Int? {
  val match = TrailblazeNodeSelectorResolver.resolve(tree, selector, templateContext) as?
    TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch ?: return null
  return semanticsIdByNodeId[match.node.nodeId]
}

/**
 * Resolves [selector] against a hierarchy captured now, and returns the live handle for the node
 * that matched.
 *
 * The snapshot is built here rather than read from `context.screenState`, which can be the agent
 * loop's view of the screen from before the previous tool ran. Acting on a `View` instance found in
 * a stale tree is worse than re-matching would be: the instance may be detached, or recycled into a
 * different row of a list.
 *
 * Re-snapshotting on a timeout preserves the tolerance the per-backend tools used to have for a
 * screen that is still settling. `waitForIdle` covers everything the app declares through idling
 * resources and the Compose clock; it does not cover work those never learn about.
 *
 * This is Trailblaze's own work, not Espresso's or Compose's, even though it runs inside the
 * dispatch the driver measures as native. Both readers of that split are fed from here: the span
 * puts it on the timeline nested inside `nativeDispatch`, and [AndroidTestPhaseAttribution] hands
 * the same window to the benchmark sink as orchestration. They cover the same window on purpose —
 * `nativeDispatch` minus `resolveSelector` is the sink's `nativeExecutionMs`. Without the span, a
 * profile blames a slow selector on the backend, which is the one place it cannot be fixed.
 */
private fun resolveNode(
  target: AndroidTestTarget,
  selector: TrailblazeNodeSelector,
  tool: AndroidTestExecutableTool,
  context: TrailblazeToolExecutionContext,
  timeoutMs: Long?,
): ResolvedNode = TrailblazeTracer.traceDetail("resolveSelector", cat = DRIVER_TRACE_CAT) {
  pollForNode(target, selector, tool, context, timeoutMs ?: RESOLVE_TIMEOUT_MS)
}

/**
 * The polling half of [resolveNode], split out only so the span above can wrap a call.
 *
 * Inlined into the traced lambda it would leave the loop — which exits by returning or throwing,
 * never by finishing — as the lambda's trailing expression, and that reads as `Unit`.
 */
private fun pollForNode(
  target: AndroidTestTarget,
  selector: TrailblazeNodeSelector,
  tool: AndroidTestExecutableTool,
  context: TrailblazeToolExecutionContext,
  timeoutMs: Long,
): ResolvedNode {
  val orchestration = AndroidTestStopwatch()
  try {
    val deadline = SystemClock.uptimeMillis() + timeoutMs
    // `{{target.appId}}` in a `*Regex` field is expanded by the resolver, but only when it is
    // given the session's target. Without it the placeholder stays literal, matches nothing, and
    // the loop below reports "no element" after the full timeout for a selector that was fine.
    val templateContext = context.resolvedTarget?.let { resolved ->
      TargetTemplateContext(appId = context.appId, appIds = resolved.appIds)
    }
    var unplacedNode: TrailblazeNode? = null
    var ambiguousCount = 0
    lateinit var screenState: AndroidTestScreenState
    while (true) {
      // Another app in front (a browser, a permission prompt) is matched and acted on through the
      // platform's accessibility tree alone: the app's own capture would describe the screen
      // behind it, and waits for an Activity that is paused until the other app goes away.
      val foreignPackage = perf("poll.foreign") { ForeignWindow.frontPackage() }
      if (foreignPackage == null) {
        // Another app can reach the front between the read above and this capture; read it again.
        screenState = try {
          perf("poll.waitForIdle") { target.waitForIdle() }
          perf("poll.screenState") { AndroidTestScreenState(target, includeScreenshot = false) }
        } catch (_: AnotherAppInFrontException) {
          continue
        }
      }
      // Another app is often in front only for a moment, as a sign-in browser hands back to the
      // app: a selector only the app's own tree can match waits for the app until the deadline.
      if (foreignPackage != null && !selector.isAccessibilityShaped()) {
        if (SystemClock.uptimeMillis() >= deadline) requireMatchableInForeignWindow(foreignPackage, selector)
        SystemClock.sleep(RESOLVE_POLL_MS)
        continue
      }
      if (foreignPackage != null || usesAccessibilityTree(selector)) {
        val a11y = perf("poll.a11yTree") { InProcessAccessibilityTree.capture(uncached = true) }
        // An index counts matches, and a capture that dropped an earlier one would shift it onto
        // the next: wait for a full read rather than act on a partial one.
        val indexUnsure = selector.countsMatches() && a11y?.isComplete == false
        val matched = a11y?.takeIf { selector.isAccessibilityShaped() && !indexUnsure }?.let {
          placedMatches(xyz.block.trailblaze.api.resolveImportantForAccessibilityFirst(it.tree, selector, templateContext))
        }.orEmpty()
        val owner = matched.firstOrNull()?.takeIf { foreignPackage == null }?.let { node ->
          a11y?.infoFor(node)?.let { perf("poll.a11yOwner") { screenState.ownerOf(it) } }
        }
        if (owner != null) return owner.toResolvedNode()
        matched.firstOrNull()?.let { node ->
          val b = node.bounds ?: return@let null
          return ResolvedNode.ScreenPoint(
            node,
            (b.left + b.right) / 2,
            (b.top + b.bottom) / 2,
            inForeignWindow = foreignPackage != null,
          )
        }
        if (SystemClock.uptimeMillis() >= deadline) {
          if (indexUnsure) {
            throw IllegalStateException(
              "The accessibility tree could not be read in full for ${timeoutMs}ms, so the index " +
                "of ${selector.description()} could not be counted: ${a11y?.droppedFetches} " +
                "node(s) were dropped.",
            )
          }
          throw SelectorWaitMissException(
            if (foreignPackage != null) {
              // Same "matched no element after Nms" wording, so a probe reads it as "not there".
              "Selector matched no element after ${timeoutMs}ms (in $foreignPackage's window, in " +
                "front of the app): ${selector.description()}" +
                a11y?.tree?.let { "\n" + InProcessAccessibilityTree.describe(it) }.orEmpty()
            } else if (matched.isNotEmpty()) {
              "Selector matched an accessibility node after ${timeoutMs}ms, but no View or Compose " +
                "node in this process owns it (another window, or one the in-process capture " +
                "lacks): ${selector.description()}"
            } else {
              // The same wording as every other driver's miss: scripted tools read "matched no
              // element after Nms" as the "not there" answer to a probe, and anything else as a
              // failure. A scripted sign-in that polls that way would fail on its first probe.
              "Selector matched no element after ${timeoutMs}ms (in the accessibility tree): ${selector.description()}" +
                a11y?.tree?.let { "\n" + InProcessAccessibilityTree.describe(it) }.orEmpty()
            },
          )
        }
        SystemClock.sleep(RESOLVE_POLL_MS)
        continue
      }
      val result = perf("poll.resolve") {
        TrailblazeNodeSelectorResolver.resolve(
          screenState.requiredNodeTree,
          selector,
          templateContext,
        )
      }
      when (result) {
        is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch ->
          // A Compose semantics node exists before it is laid out, and reads as zero-area until
          // then. Acting on one taps a point with no element under it and asserts a node nobody
          // can see, so treat it as not-ready and keep polling — the same wait a not-yet-present
          // node already gets. (The View half never produces these: the collector drops a view
          // with no visible rect.)
          if (result.node.isPlaced()) {
            return screenState.handleFor(result.node, tool)
          } else {
            // The match can be a zero-area copy nested in a placed node that matches too, as a
            // composable that merges a child's description into its own does. The accessibility
            // tree these recordings were made on never holds a node nobody can see, so it is the
            // placed one that the selector named. An index counted over the View/Compose tree
            // would land on a different node once the unplaced ones are gone, so such a selector
            // waits for its own node to be placed instead.
            if (!selector.countsMatches() || selector.isAccessibilityShaped()) {
              placedOnlyMatch(screenState.requiredNodeTree, selector, templateContext)
                ?.let { return screenState.handleFor(it, tool) }
            }
            unplacedNode = result.node
            ambiguousCount = 0
          }
        // An ambiguous match takes the first PLACED node, which is what the driver these recordings
        // were made on does — `AccessibilityDeviceManager.pickPreferredMatch`: first visible-to-user
        // match, else the first. Refusing ambiguity instead was stricter than the recorded
        // semantics, and strictness the recordings never agreed to is indistinguishable from a bug:
        // a trail can tap a resource id that is unique in the tree it was recorded against and
        // names two live buttons here once the app shows a second button with it. The recording
        // cannot be more specific than the driver that produced it, so an in-process replay that
        // demands more replays nothing.
        //
        // Placement is still a gate, and is the reason this is not simply `nodes.first()`: a
        // zero-area node is as unactionable as no node, so a match set with none placed keeps
        // polling and reports the ambiguity at the deadline.
        is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> {
          result.nodes.firstOrNull { it.isPlaced() }?.let { return screenState.handleFor(it, tool) }
          ambiguousCount = result.nodes.size
          unplacedNode = null
        }
        is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> {
          unplacedNode = null
          ambiguousCount = 0
          perf("poll.virtualNodes") { virtualNodeMatch(screenState, selector, templateContext) }
            ?.let { return it }
        }
      }
      if (SystemClock.uptimeMillis() >= deadline) {
        throw SelectorWaitMissException(
          when {
            ambiguousCount > 0 ->
              "Selector matched $ambiguousCount elements after ${timeoutMs}ms and none of them " +
                "had on-screen bounds: ${selector.description()}"
            unplacedNode != null ->
              "Selector matched element ${unplacedNode.nodeId} but it still had no on-screen " +
                "bounds after ${timeoutMs}ms: ${selector.description()}"
            else ->
              "Selector matched no element after ${timeoutMs}ms: " +
                "${selector.description()}\n${screenState.onScreenSummary()}"
          },
        )
      }
      // Blocking sleep, not `delay`: Espresso and the Compose rule are driven from the
      // instrumentation test thread, and a coroutine could resume the loop on another one.
      SystemClock.sleep(RESOLVE_POLL_MS)
    }
  } finally {
    AndroidTestPhaseAttribution.addOrchestration(orchestration.elapsedMs())
  }
}

private fun AndroidTestScreenState.handleFor(
  node: TrailblazeNode,
  tool: AndroidTestExecutableTool,
): ResolvedNode =
  when (val detail = node.driverDetail) {
    is DriverNodeDetail.AndroidView ->
      ResolvedNode.ViewNode(
        node = node,
        view = viewByNodeId[node.nodeId]
          ?: throw TrailblazeToolExecutionException(
            "Node ${node.nodeId} is not backed by a live View. The only such node is the " +
              "synthetic container the hierarchy is rooted at, which nothing can act on.",
            tool,
          ),
      )
    is DriverNodeDetail.Compose ->
      ResolvedNode.ComposeNode(
        node = node,
        semanticsId = semanticsIdByNodeId[node.nodeId]
          ?: throw TrailblazeToolExecutionException(
            "Compose node ${node.nodeId} has no semantics id recorded for it.",
            tool,
          ),
      )
    else ->
      throw TrailblazeToolExecutionException(
        "Resolved a ${detail::class.simpleName} node, which this driver has no backend for. " +
          "The in-process hierarchy is built only from live Views and Compose semantics.",
        tool,
      )
  }

/**
 * The identifiable elements on screen, one per line, each labelled with the selector dialect that
 * can address it.
 *
 * Attached to a "matched no element" failure because that failure has two very different causes
 * that the selector alone cannot tell apart: the screen was not the expected one, or the element is
 * there but reached through the other dialect. A trail author writing against a mixed
 * View/Compose app cannot know which half a control belongs to without being told, and a CI failure
 * without this is unactionable from the report alone.
 */
private fun AndroidTestScreenState.onScreenSummary(): String =
  onScreenSummary(requiredNodeTree)

/**
 * The tree half of [onScreenSummary], separated from the screen state so it can be exercised
 * without a device — the redaction below is the kind of rule that has to be provable.
 */
internal fun onScreenSummary(root: TrailblazeNode): String {
  val lines = mutableListOf<String>()
  fun visit(node: TrailblazeNode) {
    val bounds = node.bounds
    val line =
      when (val detail = node.driverDetail) {
        is DriverNodeDetail.Compose ->
          describe(
            dialect = "compose",
            fields = listOf(
              "text" to detail.text,
              "editableText" to detail.editableText,
              "contentDescription" to detail.contentDescription,
              "testTag" to detail.testTag,
            ),
            isPassword = detail.isPassword,
          )
        is DriverNodeDetail.AndroidView ->
          describe(
            dialect = "androidView",
            fields = listOf(
              "text" to detail.text,
              "contentDescription" to detail.contentDescription,
              "resourceId" to detail.resourceId,
              "hintText" to detail.hintText,
            ),
            isPassword = detail.isPassword,
          )
        else -> null
      }
    if (line != null && bounds != null && bounds.width > 0 && bounds.height > 0) {
      lines += "$line [${bounds.left},${bounds.top}-${bounds.right},${bounds.bottom}]"
    }
    node.children.forEach(::visit)
  }
  visit(root)
  val shown = lines.take(MAX_SUMMARY_ELEMENTS)
  val omitted = lines.size - shown.size
  return buildString {
    append("On screen (${lines.size} identifiable elements):\n")
    shown.forEach { append("  ").append(it).append('\n') }
    if (omitted > 0) append("  … $omitted more\n")
  }
}

/**
 * One summary line, or null when the node carries nothing a selector could match on.
 *
 * A password field's own value is replaced with [REDACTED]. This summary is attached to a failure
 * message, which reaches CI logs and the persisted step log — and a sign-in step that mistyped a
 * selector is exactly when it gets written, with the credential already in the field. The field
 * still appears so the failure can be diagnosed; only its content is withheld.
 */
private fun describe(
  dialect: String,
  fields: List<Pair<String, String?>>,
  isPassword: Boolean,
): String? {
  val present = fields.filter { !it.second.isNullOrBlank() }
  if (present.isEmpty()) return null
  return "$dialect " +
    present.joinToString(" ") { (name, value) ->
      val shown = if (isPassword && name in VALUE_FIELDS) REDACTED else value
      "$name=\"$shown\""
    }
}

/** The fields that hold what a user typed, as opposed to how the control is addressed. */
private val VALUE_FIELDS = setOf("text", "editableText")
private const val REDACTED = "<redacted>"

/**
 * Whether the node occupies real screen space. Both halves of the hierarchy report bounds in
 * window coordinates, and a node that has been composed but not yet laid out reports an empty
 * rectangle — indistinguishable, by any other property, from one that is ready.
 */
/**
 * Whether a recorded accessibility selector is matched against the app's real accessibility tree
 * rather than this driver's View/Compose tree: opt-in per device, see [InProcessAccessibilityTree].
 */
private fun usesAccessibilityTree(selector: TrailblazeNodeSelector): Boolean =
  selector.isAccessibilityShaped() && InProcessAccessibilityTree.isEnabled()

/**
 * Any accessibility match anywhere in the selector, and no other driver's at any depth: a native
 * match under a relation-only node would match nothing in the accessibility tree, which an
 * absence check reads as "gone".
 */
private fun TrailblazeNodeSelector.isAccessibilityShaped(): Boolean =
  hasOnlyAccessibilityMatches() && hasAccessibilityMatch()

private fun TrailblazeNodeSelector.relationParts(): List<TrailblazeNodeSelector> =
  listOfNotNull(below, above, leftOf, rightOf, childOf, containsChild) + containsDescendants.orEmpty()

/** Whether an `index` anywhere in [this], its relations included, counts matches. */
private fun TrailblazeNodeSelector.countsMatches(): Boolean =
  index != null || relationParts().any { it.countsMatches() }

private fun TrailblazeNodeSelector.hasOnlyAccessibilityMatches(): Boolean =
  (driverMatch == null || driverMatch is xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility) &&
    relationParts().all { it.hasOnlyAccessibilityMatches() }

private fun TrailblazeNodeSelector.hasAccessibilityMatch(): Boolean =
  driverMatch is xyz.block.trailblaze.api.DriverNodeMatch.AndroidAccessibility ||
    relationParts().any { it.hasAccessibilityMatch() }

/**
 * Placed matches, the ones visible to the user first, then in match order: the first is what the
 * accessibility driver acts on too (`pickPreferredMatch`). A popup's control and an occluded one
 * behind it can share a label, and only the visible one takes a tap.
 */
private fun placedMatches(result: TrailblazeNodeSelectorResolver.ResolveResult): List<TrailblazeNode> =
  when (result) {
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node)
    is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
    is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
  }.filter { it.isPlaced() }
    .sortedBy { (it.driverDetail as? xyz.block.trailblaze.api.DriverNodeDetail.AndroidAccessibility)?.isVisibleToUser == false }

/**
 * A recorded accessibility selector the View/Compose tree cannot match because what it names is a
 * VIRTUAL accessibility node: one a View draws itself and exposes only through its own
 * [android.view.accessibility.AccessibilityNodeProvider] (a text field's clear button, a chart's
 * bars). Read from the platform's accessibility tree, the one the recording was made against, and
 * only when some View on screen exposes such nodes, so an ordinary miss costs one View walk.
 */
private fun virtualNodeMatch(
  screenState: AndroidTestScreenState,
  selector: TrailblazeNodeSelector,
  templateContext: TargetTemplateContext?,
): ResolvedNode? {
  if (!screenState.exposesVirtualNodes(selector)) return null
  val a11y = InProcessAccessibilityTree.capture(uncached = true) ?: return null
  // A partial read would shift an index onto the next match; the caller polls again.
  if (selector.countsMatches() && !a11y.isComplete) return null
  val node = placedMatches(
    xyz.block.trailblaze.api.resolveImportantForAccessibilityFirst(a11y.tree, selector, templateContext),
  ).firstOrNull() ?: return null
  a11y.infoFor(node)?.let { screenState.ownerOf(it) }?.let { return it.toResolvedNode() }
  val b = node.bounds ?: return null
  return ResolvedNode.ScreenPoint(node, (b.left + b.right) / 2, (b.top + b.bottom) / 2)
}

/** Whether [selector] could name a virtual node: it is accessibility-shaped and some View on screen exposes them. */
private fun AndroidTestScreenState.exposesVirtualNodes(selector: TrailblazeNodeSelector): Boolean {
  if (!selector.isAccessibilityShaped()) return false
  val views = viewByNodeId.values
  return onMainThread { views.any { it !is ViewRootForTest && it.accessibilityNodeProvider != null } }
}

private fun AndroidTestScreenState.ownerOf(
  info: android.view.accessibility.AccessibilityNodeInfo,
): InProcessAccessibilityTree.Owner? =
  InProcessAccessibilityTree.ownerOf(info, requiredNodeTree, viewByNodeId, semanticsIdByNodeId)

private fun InProcessAccessibilityTree.Owner.toResolvedNode(): ResolvedNode = when (this) {
  is InProcessAccessibilityTree.Owner.ViewOwner -> ResolvedNode.ViewNode(node, view)
  is InProcessAccessibilityTree.Owner.ComposeOwner -> ResolvedNode.ComposeNode(node, semanticsId)
}

/** [selector]'s match in [tree] once the nodes nobody can see are gone, or null when none is placed. */
private fun placedOnlyMatch(
  tree: TrailblazeNode,
  selector: TrailblazeNodeSelector,
  templateContext: TargetTemplateContext?,
): TrailblazeNode? {
  val placedTree = tree.withoutUnplacedLeaves() ?: return null
  return when (val result = TrailblazeNodeSelectorResolver.resolve(placedTree, selector, templateContext)) {
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node.takeIf { it.isPlaced() }
    is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes.firstOrNull { it.isPlaced() }
    is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> null
  }
}

/** This node with every unplaced subtree that holds no placed node removed, or null if it is one. */
private fun TrailblazeNode.withoutUnplacedLeaves(): TrailblazeNode? {
  val kept = children.mapNotNull { it.withoutUnplacedLeaves() }
  return if (kept.isEmpty() && !isPlaced()) null else copy(children = kept)
}

private fun TrailblazeNode.isPlaced(): Boolean {
  val bounds = bounds ?: return false
  return bounds.width > 0 && bounds.height > 0
}

/**
 * A wait that ran out with the screen still saying no: the selector never matched a placed element
 * (or, for a not-visible check, never stopped matching one) before its deadline.
 *
 * Its own type so the Maestro interpreter can tell the screen's answer apart from every other
 * failure, and report it the way Maestro does. See [MaestroCommandAdapters].
 */
internal class SelectorWaitMissException(message: String) : RuntimeException(message)

/** Transitions and fake network responses land asynchronously, so give the screen time to arrive. */
private const val RESOLVE_TIMEOUT_MS = 8_000L

private const val RESOLVE_TIMEOUT_DESCRIPTION =
  "Max ms to wait for the selector to match. Default 8000; raise only for slow app work the app " +
    "does not report as idle, such as a first-run data sync."
private const val RESOLVE_POLL_MS = 50L

/** Enough to identify the screen and find a missed element; short of dumping a whole long list. */
private const val MAX_SUMMARY_ELEMENTS = 60

/**
 * Well past any list a trail should be walking by scrolling, and low enough that a selector that
 * will never match fails in seconds rather than grinding to the bottom of an infinite feed.
 */
private const val MAX_SCROLLS = 25

/** How long a container gets to finish measuring before "cannot scroll" is believed. */
private const val UNSCROLLABLE_ATTEMPTS = 6
private const val UNSCROLLABLE_RETRY_MS = 250L
private const val UNREADABLE_RETRIES = 4

/** How many times an assert resolves its selector again after the matched view detached. */
private const val DETACHED_RESOLVE_ATTEMPTS = 3

