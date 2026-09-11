package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.api.MatchDescriptor
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.SnapshotCache
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.tracing.TrailblazeTracer

/**
 * Query tool: resolves a [TrailblazeNodeSelector] against the current view
 * hierarchy and returns every match as a [MatchDescriptor] list.
 *
 * ## DEPRECATED — use [FindSelectorMatchesTrailblazeTool]
 *
 * `findSelectorMatches` does everything this does and answers N selectors from ONE capture, which
 * this tool structurally cannot: each `client.tools.*` callback enters its own cache frame, so
 * asking three questions of one screen through this tool pays three multi-second captures and gets
 * three different screens. Passing a single-element `selectors` list to the replacement behaves
 * identically to this tool — the extra trust semantics there (a "mixed" frame) are unreachable with
 * one selector — so migration is mechanical rather than behavioural.
 *
 * Kept only until its callers move, then deleted — do not add new callers.
 *
 * Surface-only — not advertised to the LLM agent (`surfaceToLlm = false`) so the
 * model can't pick `findMatches` spontaneously; only scripted-tool authors who
 * imported it as `client.tools.findMatches(...)` reach it. Not recordable
 * (`isRecordable = false`) since it never mutates device state; not a
 * verification primitive (`isVerification = false`) — the assertion lives in the
 * caller's code (`matches.length === 0`, `matches.length === 1`, …) rather than
 * in the tool itself.
 *
 * ## Wire shape
 *
 * Successful result returns the `List<MatchDescriptor>` via
 * [TrailblazeToolResult.Success.structuredContent] (the field introduced in PR
 * #3329). The TS SDK's `client.tools.findMatches(...)` proxy unwraps it as the
 * typed `result` per `TrailblazeToolMap.findMatches.result = MatchDescriptor[]`
 * (declared in `built-in-tools.ts`). [TrailblazeToolResult.Success.message]
 * carries a one-line summary for log readability — `"findMatches: N matches"`.
 *
 * ## Snapshot reuse
 *
 * Routes the capture through [SnapshotCache], which shares one captured tree
 * between `findMatches` calls dispatched as SIBLINGS in the same batch. Falls
 * back to a direct [TrailblazeToolExecutionContext.screenStateProvider] call
 * when no cache frame is active (unit tests, direct invocations).
 *
 * It does NOT deduplicate across scripted calls: each `client.tools.*` callback
 * enters its own nested frame, so N `client.tools.findMatches(...)` from one
 * scripted tool body pay N captures whatever the cache holds. Asking several
 * questions of one screen is [FindSelectorMatchesTrailblazeTool]'s job.
 *
 * ## Captures that lost nodes
 *
 * An empty match list is how a scripted caller decides an element is absent, so
 * this tool will not produce one out of a capture it knows is missing nodes
 * (see [xyz.block.trailblaze.api.ScreenState.droppedNodeFetches] — on Android
 * every node is a live fetch, and a blocked app answers with null). It
 * re-captures instead, and fails rather than answering "no matches" if it never
 * gets a capture the device could complete. A non-empty result is returned
 * straight away either way: a node that IS in a tree really was on screen.
 * Drivers that cannot measure completeness report unknown and are unaffected.
 *
 * That rule, the capture timing and the retry budget all live in
 * [SelectorQueryEngine], shared with [FindSelectorMatchesTrailblazeTool] —
 * this tool contributes only what one selector's verdict is
 * ([resolveOne]) and how to word its errors.
 */
@Serializable
@TrailblazeToolClass(
  name = "findMatches",
  surfaceToLlm = false,
  isRecordable = false,
  isVerification = false,
)
@Deprecated(
  "Use [FindSelectorMatchesTrailblazeTool] — it answers N selectors from one capture. " +
    "A single-element `selectors` list behaves identically, so migrating is mechanical.",
)
data class FindMatchesTrailblazeTool(
  /** Selector to match against the current view hierarchy. */
  val selector: TrailblazeNodeSelector,
  /**
   * Optional wait budget in milliseconds. `null` (the default) keeps the historical behavior: a
   * single point-in-time snapshot, reusing the per-invocation [SnapshotCache] frame. When set, the
   * tool polls the LIVE hierarchy — re-capturing every [SelectorQueryEngine.pollIntervalMs],
   * bypassing the cache so
   * each poll sees the current screen — until at least one match appears or the budget elapses,
   * then returns whatever matched (an empty list if nothing did, provided at least one poll got a
   * capture that held every node the device advertised — see the class kdoc).
   *
   * This is the non-throwing "wait until this selector is visible" probe that scripted tools use
   * for conditional flows. It's the framework-side equivalent of the Kotlin agent's
   * `isTextVisible(regex, timeoutMs)` / `executeNodeSelectorAssertVisible(timeoutMs)`, but it
   * RETURNS matches rather than asserting — so "absent after the timeout" is a normal empty result
   * (the caller's `matches.length === 0` branch), not a logged verification failure. Keeps scripted
   * authors from hand-rolling a poll loop on top of point-in-time `findMatches`.
   *
   * A value `<= 0` performs a single immediate live capture (no wait) and returns its matches —
   * effectively the point-in-time result routed through the polling path rather than an error. It
   * gets a single look, so a capture that lost nodes and matched nothing has no later poll to
   * redeem it and the call fails rather than reporting absence.
   */
  val timeoutMs: Long? = null,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val provider = toolExecutionContext.screenStateProvider
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "findMatches: no screenStateProvider available on the execution context.",
      )
    val traceTag = toolExecutionContext.traceId?.traceId

    // Hoist the selector description out of the try block so both the drop-log and the
    // error-message paths reuse the same string — deeply nested `containsDescendants`
    // selectors traverse the whole subtree to render and we don't want to do it twice.
    val selectorDesc = selector.description()

    // Resolver throws are wrapped into a structured `Error.ExceptionThrown` carrying the
    // selector that failed — keeps the dispatch path from leaking a raw stack trace and
    // gives scripted-tool authors a parseable error envelope. `CancellationException`
    // re-throws so structured-concurrency cancellation isn't swallowed.
    // One trace span per findMatches CALL — the point-in-time resolve, or the whole poll (wait
    // included). Never one span per poll iteration: the polling path previously re-entered the
    // traced `resolveDescriptors` on every re-capture, flooding session.trace.json with dozens of
    // duplicate "findMatches" spans for a single wait. `resolveMatches` is now untraced; the span
    // is emitted here, once.
    val traceArgs = mapOf("selector" to selectorDesc)
    val descriptors: List<MatchDescriptor> = try {
      val outcome = TrailblazeTracer.traceSuspend(name = "findMatches", cat = TRACE_CAT, args = traceArgs) {
        if (timeoutMs == null) {
          // Point-in-time: routed through SnapshotCache so repeated findMatches in one tool body
          // share the multi-second capture.
          SelectorQueryEngine.resolvePointInTime(
            provider = provider,
            traceTag = traceTag,
            logTag = LOG_TAG,
            describeAbsence = { "selector=$selectorDesc" },
            resolve = { tree -> resolveOne(tree, selectorDesc) },
          )
        } else {
          SelectorQueryEngine.poll(
            provider = provider,
            timeoutMs = timeoutMs,
            traceTag = traceTag,
            logTag = LOG_TAG,
            describeAbsence = { "selector=$selectorDesc" },
            resolve = { tree -> resolveOne(tree, selectorDesc) },
          )
        }
      }
      when (outcome) {
        // A driver that never produced a node tree is a driver/platform mismatch — the same error
        // from either path, rather than a misleading empty result that would send a scripted caller
        // down its "absent element" branch. On the polling path a merely TRANSIENT null (a tree was
        // seen on some other poll) does NOT trigger this.
        is SelectorQueryEngine.Outcome.NoTreeEverSeen -> return missingTreeError(outcome.platform)
        // Every capture we could get lost nodes and none of them held the selector. Refusing to
        // answer is the point: the empty list is a scripted caller's "element is absent" branch,
        // and here we do not know that.
        // `timeoutMs` is exactly "did this call already wait", so one call site gives each path
        // the advice it used to get from its own.
        is SelectorQueryEngine.Outcome.UntrustworthyAbsence ->
          return partialCaptureError(
            selectorDesc = selectorDesc,
            captures = outcome.captures,
            waitedMs = timeoutMs,
            sawCompleteCapture = outcome.sawCompleteCapture,
          )
        is SelectorQueryEngine.Outcome.Resolved -> outcome.result
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "findMatches: resolve failed for selector $selectorDesc: ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }

    val structured: JsonElement =
      TrailblazeJsonInstance.encodeToJsonElement(ListSerializer(MatchDescriptor.serializer()), descriptors)
    return TrailblazeToolResult.Success(
      message = "findMatches: ${descriptors.size} match${if (descriptors.size == 1) "" else "es"}",
      structuredContent = structured,
    )
  }

  /**
   * Resolves [selector] against one captured [tree] and states the verdict in the two terms
   * [SelectorQueryEngine] reasons about. For a single selector they collapse into each other — a
   * match means the wait is over and nothing is being claimed absent; an empty list means the
   * opposite — which is why the engine's trust rule reduces here to exactly the historical
   * behavior: return a match immediately, but never return "empty" out of a capture that lost nodes.
   *
   * Delegates the actual matching to [SelectorMatchResolution] so `findSelectorMatches` answers
   * each of its N selectors exactly as an equivalent `findMatches` call would. Intentionally
   * UNTRACED: the single enclosing [TrailblazeTracer] span is emitted once per call by [execute],
   * so the polling path doesn't emit one span per re-capture.
   */
  private fun resolveOne(
    tree: TrailblazeNode,
    selectorDesc: String,
  ): SelectorQueryEngine.Resolution<List<MatchDescriptor>> {
    val descriptors = SelectorMatchResolution.resolve(
      tree = tree,
      selector = selector,
      selectorDesc = selectorDesc,
      logTag = LOG_TAG,
    )
    return SelectorQueryEngine.Resolution(
      result = descriptors,
      matched = descriptors.isNotEmpty(),
      claimsAbsence = descriptors.isEmpty(),
    )
  }

  /**
   * Shared missing-tree error — the current driver produces no [TrailblazeNode] tree, so a node
   * selector cannot be resolved. Used by both the point-in-time path and the polling path (when no
   * tree was ever seen) so the two report the same error.
   */
  private fun missingTreeError(platform: TrailblazeDevicePlatform): TrailblazeToolResult.Error.ExceptionThrown =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "findMatches: current driver does not produce a TrailblazeNode tree " +
        "(platform=${platform.name}). The selector cannot be resolved.",
    )

  /**
   * The refusal both resolve paths share: this call never got a capture it could read "no
   * matches" out of, so it reports that instead of an empty list a caller would read as absence.
   *
   * The paths need different advice, so [waitedMs] decides which is given. A point-in-time call
   * has a wait to reach for; a call that already waited does not, and telling it to pass a
   * `timeoutMs` it just passed is the least useful thing this message could say. `timeoutMs = 0`
   * is a third case, not the second one: it is the polling path with no budget — exactly one live
   * capture, no retry — so one holey capture there says nothing about the app, and blaming the
   * main thread would be a guess.
   *
   * [sawCompleteCapture] is always `false` here, because a single selector's result cannot be
   * "mixed" — the one case that reaches the refusal with a complete capture behind it. It is
   * threaded and branched anyway so this message cannot start lying if that ever stops being true,
   * and so the two tools read identically in a log.
   */
  private fun partialCaptureError(
    selectorDesc: String,
    captures: Int,
    waitedMs: Long?,
    sawCompleteCapture: Boolean,
  ): TrailblazeToolResult.Error.ExceptionThrown =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "findMatches: " +
        (
          if (sawCompleteCapture) {
            "the capture this wait ended on dropped node fetches"
          } else {
            "$captures capture(s) each dropped node fetches"
          }
          ) + " and none " +
        "matched $selectorDesc, so whether the element is on screen is unknown — the app was " +
        "not answering accessibility node fetches (typically a blocked main thread) and the " +
        "captured tree is missing subtrees. Reporting no matches here would read as 'absent'. " +
        when {
          waitedMs == null -> "Pass a `timeoutMs` to wait for a capture the device can complete."
          waitedMs <= 0L ->
            "A `timeoutMs` of ${waitedMs}ms takes exactly one live capture and never retries, so " +
              "nothing here says the app is wedged — pass a real budget so a later poll can land " +
              "on a complete capture, or omit `timeoutMs` for a point-in-time read."
          else ->
            "This call already waited ${waitedMs}ms and the app did not answer a complete capture " +
              "in that window, so a longer `timeoutMs` only helps if the block clears on its own. " +
              "Fix what is holding the app's main thread."
        },
      command = this,
    )

  companion object {
    /**
     * `cat` for the single per-call [TrailblazeTracer] span. Kept as the class simple name so the
     * span stays grouped with the tool in trace tooling (pinned by the trace-event test).
     */
    private val TRACE_CAT: String = FindMatchesTrailblazeTool::class.simpleName!!

    /** Prefix on this tool's [SelectorQueryEngine] log lines, so a log names the tool, not the engine. */
    private const val LOG_TAG = "FindMatches"
  }
}
