package xyz.block.trailblaze.android.test.tools

import ai.koog.agents.core.tools.annotations.LLMDescription
import android.os.SystemClock
import android.view.View
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.android.test.AndroidTestPhaseAttribution
import xyz.block.trailblaze.android.test.AndroidTestScreenState
import xyz.block.trailblaze.android.test.AndroidTestStopwatch
import xyz.block.trailblaze.android.test.AndroidTestTarget
import xyz.block.trailblaze.android.test.DRIVER_TRACE_CAT
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
  @param:LLMDescription(
    "Hold the press past the long-press timeout instead of tapping, for a gesture the app only " +
      "reacts to when held — entering an edit mode, opening a context menu.",
  )
  val longPress: Boolean = false,
  @param:LLMDescription(RESOLVE_TIMEOUT_DESCRIPTION) val timeoutMs: Long? = null,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching {
    val verb = if (longPress) "Long-pressed" else "Tapped"
    when (val resolved = resolveNode(target, nodeSelector, this, context, timeoutMs)) {
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
    }
  }.getOrElse { TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(it, this) }
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
    }
  }.getOrElse { TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(it, this) }
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
  ): TrailblazeToolResult = runCatching {
    // Asserted against the backend as well as the selector: the selector proved the node is in the
    // tree with on-screen bounds, and the native check proves the backend agrees it is displayed.
    //
    // An ambiguous match resolves to the first placed node here as everywhere else (see
    // `pollForNode`), which suits an assert especially: it has no side effect to misplace, and a
    // selector matching twice satisfies "this is visible" twice over. Recordings need that — the
    // trees they were made against project a container and its text into one node, so a recorded
    // assert that resolved to one element routinely resolves to that pair on this driver's denser
    // tree.
    when (val resolved = resolveNode(target, nodeSelector, this, context, timeoutMs)) {
      is ResolvedNode.ViewNode -> AndroidViewActions.assertDisplayed(target, resolved.view)
      is ResolvedNode.ComposeNode ->
        AndroidComposeActions.assertDisplayed(target, resolved.semanticsId)
    }
    TrailblazeToolResult.Success(message = "Verified ${nodeSelector.description()} is visible.")
  }.getOrElse { TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(it, this) }
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
  ): TrailblazeToolResult = runCatching {
    // The resolve poll inverted: the expected answer is "no placed match" on the first read, and
    // a match gets until the deadline to LEAVE — an outgoing screen's node is still in the tree
    // while it animates away, the same settling the positive assert tolerates in the other
    // direction. Placement gates it the same way too: a zero-area leftover in the tree is not
    // visible to anyone, so it must not fail a not-visible assertion.
    val orchestration = AndroidTestStopwatch()
    try {
      val budgetMs = timeoutMs ?: RESOLVE_TIMEOUT_MS
      val deadline = SystemClock.uptimeMillis() + budgetMs
      while (isOnScreen(target, nodeSelector, context)) {
        if (SystemClock.uptimeMillis() >= deadline) {
          throw TrailblazeToolExecutionException(
            "Selector still matched a visible element after ${budgetMs}ms: " +
              nodeSelector.description(),
            this,
          )
        }
        SystemClock.sleep(RESOLVE_POLL_MS)
      }
      TrailblazeToolResult.Success(
        message = "Verified ${nodeSelector.description()} is not visible.",
      )
    } finally {
      // The whole check is tree walking — Trailblaze's work, not the backend's — so it lands on
      // the benchmark's orchestration side, exactly like the positive assert's resolve poll.
      AndroidTestPhaseAttribution.addOrchestration(orchestration.elapsedMs())
    }
  }.getOrElse { TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(it, this) }
}

@Serializable
@TrailblazeToolClass("androidTest_scrollUntilVisible")
@LLMDescription(
  "Scroll until an Android element is on screen, by selector. Use when the element is below the " +
    "fold — a lazy list does not put off-screen rows in the hierarchy, so waiting never finds them."
)
data class AndroidTestScrollUntilVisibleTool(
  val nodeSelector: TrailblazeNodeSelector,
) : AndroidTestExecutableTool {
  override suspend fun executeWithAndroidTest(
    target: AndroidTestTarget,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult = runCatching {
    var scrolls = 0
    var idleAttempts = 0
    var triedOwnAncestor = false
    while (scrolls < MAX_SCROLLS) {
      // Matched against a snapshot directly rather than through the resolver: the element is
      // expected to be absent for most of this loop, and paying the resolver's settle budget on
      // every attempt would turn a ten-row scroll into a minute of waiting.
      if (isOnScreen(target, nodeSelector, context)) {
        return@runCatching TrailblazeToolResult.Success(
          message = "Scrolled ${nodeSelector.description()} into view after $scrolls scrolls.",
        )
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
      idleAttempts++
      if (idleAttempts >= UNSCROLLABLE_ATTEMPTS) {
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
    if (isOnScreen(target, nodeSelector, context)) {
      return@runCatching TrailblazeToolResult.Success(
        message = "Scrolled ${nodeSelector.description()} into view after $scrolls scrolls.",
      )
    }
    throw TrailblazeToolExecutionException(
      "Gave up after $MAX_SCROLLS scrolls without ${nodeSelector.description()} appearing. The " +
        "container was still scrolling, so either the element is much further down than a trail " +
        "should scroll for, or the selector is wrong.",
      this,
    )
  }.getOrElse { TrailblazeToolResult.Error.ExceptionThrown.fromThrowable(it, this) }
}

/** The node a selector resolved to, paired with the handle its backend can act on. */
private sealed interface ResolvedNode {
  val node: TrailblazeNode

  data class ViewNode(override val node: TrailblazeNode, val view: View) : ResolvedNode

  data class ComposeNode(override val node: TrailblazeNode, val semanticsId: Int) : ResolvedNode
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
  val backend = if (resolved is ResolvedNode.ComposeNode) "Compose node" else "View"
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
): Boolean {
  target.waitForIdle()
  val templateContext = context.resolvedTarget?.let { resolved ->
    TargetTemplateContext(appId = context.appId, appIds = resolved.appIds)
  }
  val tree = AndroidTestScreenState(target, includeScreenshot = false).requiredNodeTree
  return when (val result = TrailblazeNodeSelectorResolver.resolve(tree, selector, templateContext)) {
    is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> result.node.isPlaced()
    is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes.any { it.isPlaced() }
    is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> false
  }
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
  val match =
    TrailblazeNodeSelectorResolver.resolve(
      screenState.requiredNodeTree,
      selector,
      templateContext,
    ) as? TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch
      ?: return OwnAncestorScroll.NO_NODE_TO_ASK
  val semanticsId =
    screenState.semanticsIdByNodeId[match.node.nodeId] ?: return OwnAncestorScroll.NO_NODE_TO_ASK
  return if (runCatching { AndroidComposeActions.scrollTo(target, semanticsId) }.isSuccess) {
    OwnAncestorScroll.SCROLLED
  } else {
    OwnAncestorScroll.REFUSED
  }
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
      target.waitForIdle()
      screenState = AndroidTestScreenState(target, includeScreenshot = false)
      val result =
        TrailblazeNodeSelectorResolver.resolve(
          screenState.requiredNodeTree,
          selector,
          templateContext,
        )
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
            unplacedNode = result.node
            ambiguousCount = 0
          }
        // An ambiguous match takes the first PLACED node, which is what the driver these recordings
        // were made on does — `AccessibilityDeviceManager.pickPreferredMatch`: first visible-to-user
        // match, else the first. Refusing ambiguity instead was stricter than the recorded
        // semantics, and strictness the corpus never agreed to is indistinguishable from a bug:
        // case 5380720 taps `checkout_button_title`, which is unique in the tree it was recorded
        // against and names two live buttons here ("Save ticket" and "Review sale") the moment the
        // merchant has open tickets on. The recording cannot be more specific than the driver that
        // produced it, so an in-process replay that demands more replays nothing.
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
        }
      }
      if (SystemClock.uptimeMillis() >= deadline) {
        throw TrailblazeToolExecutionException(
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
          tool,
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
private fun TrailblazeNode.isPlaced(): Boolean {
  val bounds = bounds ?: return false
  return bounds.width > 0 && bounds.height > 0
}

/** Transitions and fake network responses land asynchronously, so give the screen time to arrive. */
private const val RESOLVE_TIMEOUT_MS = 8_000L

private const val RESOLVE_TIMEOUT_DESCRIPTION =
  "How long to wait for the selector to match, in milliseconds. Defaults to 8000. Raise it only " +
    "for a step that waits on work the app owns and does not declare as idle — a first-run data " +
    "sync, for example. It costs nothing when the element arrives sooner."
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
