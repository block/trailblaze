package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.api.MatchDescriptor
import xyz.block.trailblaze.api.MatchDescriptorBuilder
import xyz.block.trailblaze.api.ScreenState
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
 * Query tool: captures the view hierarchy ONCE and resolves N selectors against that one capture,
 * returning an index-aligned `List<List<MatchDescriptor>>`.
 *
 * ## Why this exists
 *
 * A view-hierarchy capture is the expensive part of a query — multiple seconds on a loaded Android
 * device, where every node is a live accessibility fetch — and resolving a selector against a tree
 * already in hand is free. [FindMatchesTrailblazeTool] answers one selector per call and therefore
 * one CAPTURE per selector, which is the right shape for one question and the wrong shape for the
 * common scripted pattern of asking several at once ("is the wizard up, or is the app already on
 * home?").
 *
 * [SnapshotCache] does not rescue that pattern from a scripted tool. It caches one [ScreenState]
 * per stack frame, and `BaseTrailblazeAgent.runTrailblazeTools` pushes one frame per tool BATCH —
 * but each scripting callback enters its own nested frame, so N `client.tools.findMatches(...)`
 * calls from inside one scripted tool pay N captures no matter what the cache holds. Widening the
 * frame to span a whole scripted-tool invocation is not the fix either: a tool that polls for 30s
 * would then read one stale capture for its entire run. The cache has to stay short-lived, so the
 * batching has to be explicit — which is this tool.
 *
 * ## Atomicity, not just speed
 *
 * Every selector is resolved against the SAME tree, so the answers describe one instant of one
 * screen. Sequential `findMatches` calls cannot promise that: between selector A's capture and
 * selector B's, the screen can change, and a caller racing two conditions can see both true, or
 * neither, in a way that depends on which it probed first. That ambiguity disappears here. Callers
 * that need a tie-break (A wins over B when both match in one frame) get to make it deterministic
 * in their own code.
 *
 * ## Wire shape
 *
 * [TrailblazeToolResult.Success.structuredContent] carries a `List<List<MatchDescriptor>>` aligned
 * to [selectors] by INDEX — result `i` is selector `i`'s matches, and a selector that matched
 * nothing gets an empty list rather than being omitted, so the two lists always have equal length.
 * Index alignment rather than a keyed map is deliberate: it needs no new serialized model type, and
 * it survives two identical selectors in one call (a map keyed by selector would silently collapse
 * them).
 *
 * ## A selector may match many elements, in reading order
 *
 * Each entry is a LIST, so 0, 1 and N matches all have a home and N is never an error — two "Item"
 * tiles is an ordinary screen, and a race primitive that failed on them would be useless. Detecting
 * ambiguity is the caller's job (`matches.length > 1` → narrow the selector), exactly as with
 * `findMatches`.
 *
 * Within one selector's matches the order is POSITIONAL, not tree order: sorted by `bounds.top`,
 * then `bounds.left` — reading order. Callers will build on that, so it is a contract, not an
 * accident of traversal. To pick one of several, prefer `TrailblazeNodeSelector.index`, which
 * selects the Nth match AFTER that sort; reaching for `result[i][2]` instead relies on the same
 * ordering while hiding that it does.
 *
 * ## An empty result is an ANSWER; an error means the question could not be asked
 *
 * This tool answers exactly one question — did each selector match the screen, within the
 * timeout — and "no" is an answer, not a failure. An empty list at index `i` is a MEASURED
 * absence, and it is the caller's absent branch.
 *
 * So no error path here means "the answer was no". Every one of them means the question was
 * unanswerable: an empty [selectors] list or a missing screen-state provider (caller and harness
 * bugs), a driver that produces no node tree at all, a resolver throw, and the partial-capture
 * refusal below. Keeping those two things apart is what makes the empty list safe to branch on —
 * an absence nobody measured can never enter it.
 *
 * ## Captures that lost nodes
 *
 * Inherits [FindMatchesTrailblazeTool]'s refusal to report absence out of a capture known to be
 * missing nodes ([ScreenState.droppedNodeFetches]), generalized per-selector and applied strictly:
 * a frame is trusted only if the capture was complete, or if EVERY selector matched something in
 * it. If any selector came back empty out of a holey tree, the capture is dropped and retaken; if
 * that never resolves, the call fails rather than handing back an empty list some caller reads as
 * "not on screen". Strict is the right default because it matches what N separate `findMatches`
 * calls would have done — each of them would have refused for its own empty selector.
 *
 * **That applies to the [timeoutMs] wait too, and it is the one place a caller might be surprised.**
 * A match normally ends the wait, but not out of a holey capture that also left another selector
 * empty: such a frame would answer one selector truthfully and the rest with an "absent" nobody
 * measured. The wait keeps going instead, and if the budget runs out having only ever seen frames
 * like that, the call fails rather than returning one. Both resolve paths get this from
 * [SelectorQueryEngine], which is why they cannot diverge on it.
 */
@Serializable
@TrailblazeToolClass(
  name = "findSelectorMatches",
  surfaceToLlm = false,
  isRecordable = false,
  isVerification = false,
)
data class FindSelectorMatchesTrailblazeTool(
  /**
   * Selectors to resolve against one capture. Order is the result's contract — result `i` belongs
   * to selector `i` — so a caller may pass duplicates or reorder freely. Must be non-empty; an
   * empty list is a caller bug (a capture with nothing to ask of it) rather than a trivially empty
   * success, so it is rejected.
   */
  val selectors: List<TrailblazeNodeSelector>,
  /**
   * Optional wait budget in milliseconds, with the same meaning as
   * [FindMatchesTrailblazeTool.timeoutMs] widened to N selectors.
   *
   * `null` (the default) is a single point-in-time capture through the per-invocation
   * [SnapshotCache] frame. When set, the tool polls the LIVE hierarchy — re-capturing every
   * [SelectorQueryEngine.pollIntervalMs], bypassing the cache — and returns as soon as ANY selector
   * matches, or when the budget elapses.
   *
   * **"Any" is what makes this a race primitive.** A caller waiting for whichever of several
   * screens shows up first gets exactly one wait, ending the instant one of them renders, with the
   * other selectors' answers taken from that same frame. The alternative — a hand-rolled loop of
   * short per-selector waits — pays a capture per selector per poll and returns late by up to one
   * slice. A caller that instead wants "wait for THIS one" passes that selector alone.
   *
   * The one thing that does NOT end the wait is a match in a capture that lost nodes while some
   * OTHER selector came back empty: the empty entries in that frame are unknown rather than absent,
   * so the wait keeps spending budget it already has on a frame worth trusting. See the class kdoc.
   *
   * **`null` and `0` are not the same knob, despite reading like one.** `null` goes through the
   * [SnapshotCache] frame (so it can reuse a sibling tool's capture) and retries a capture that
   * lost nodes up to [SelectorQueryEngine.PARTIAL_CAPTURE_RECAPTURES] times. `<= 0` is one
   * immediate LIVE capture: no cache, no retry — so a holey capture that left a selector empty has
   * no second look to redeem it and the call fails rather than answering. Prefer `null` for a
   * point-in-time question; reach for `0` only to force a fresh capture on purpose.
   */
  val timeoutMs: Long? = null,
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    if (selectors.isEmpty()) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "findSelectorMatches: `selectors` is empty — nothing to resolve. Pass at " +
          "least one selector.",
        command = this,
      )
    }
    val provider = toolExecutionContext.screenStateProvider
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "findSelectorMatches: no screenStateProvider available on the execution context.",
      )
    val traceTag = toolExecutionContext.traceId?.traceId

    // Rendered once per call, not once per resolve: a deeply nested `containsDescendants` selector
    // traverses its whole subtree to describe itself, and the polling path resolves every selector
    // on every poll.
    val selectorDescs = selectors.map { it.description() }
    val selectorSummary = selectorDescs.joinToString(", ")

    // One trace span per CALL — covering the capture(s), every selector's resolve, and any wait.
    // Never one per poll or per selector; see the same rule in FindMatchesTrailblazeTool.execute.
    val traceArgs = mapOf(
      "selectorCount" to selectors.size.toString(),
      "selectors" to selectorSummary,
    )
    val resolved: SelectorQueryEngine.Outcome.Resolved<List<List<MatchDescriptor>>> = try {
      val outcome = TrailblazeTracer.traceSuspend(
        name = "findSelectorMatches",
        cat = TRACE_CAT,
        args = traceArgs,
      ) {
        val describeAbsence: (List<List<MatchDescriptor>>) -> String = { result ->
          "unmatched=[${emptySelectorDescs(result, selectorDescs).joinToString(", ")}]"
        }
        if (timeoutMs == null) {
          SelectorQueryEngine.resolvePointInTime(
            provider = provider,
            traceTag = traceTag,
            logTag = LOG_TAG,
            describeAbsence = describeAbsence,
            resolve = { tree -> resolveAll(tree, selectorDescs) },
          )
        } else {
          SelectorQueryEngine.poll(
            provider = provider,
            timeoutMs = timeoutMs,
            traceTag = traceTag,
            logTag = LOG_TAG,
            describeAbsence = describeAbsence,
            resolve = { tree -> resolveAll(tree, selectorDescs) },
          )
        }
      }
      when (outcome) {
        is SelectorQueryEngine.Outcome.NoTreeEverSeen -> return missingTreeError(outcome.platform)
        is SelectorQueryEngine.Outcome.UntrustworthyAbsence -> return partialCaptureError(
          emptySelectorDescs = emptySelectorDescs(outcome.lastResult, selectorDescs),
          captures = outcome.captures,
          sawCompleteCapture = outcome.sawCompleteCapture,
          nextStep = when {
            // The app demonstrably answers complete captures — this wait just ended on a holey one
            // that had picked up a new match, so the advice is "look again", not "go debug the app".
            outcome.sawCompleteCapture ->
              "A complete capture DID arrive earlier in this wait, so the app is answering " +
                "intermittently rather than wedged — re-query, or raise `timeoutMs` so a later " +
                "poll can land on a complete capture."
            timeoutMs == null -> "Pass a `timeoutMs` to wait for a capture the device can complete."
            // `timeoutMs = 0` is the polling path with no budget: one live capture, no retry. One
            // holey capture establishes nothing about the app, so the advice is the retry the
            // caller didn't ask for — not an investigation.
            timeoutMs <= 0L ->
              "A `timeoutMs` of ${timeoutMs}ms takes exactly one live capture and never retries, " +
                "so nothing here says the app is wedged — pass a real budget so a later poll can " +
                "land on a complete capture, or omit `timeoutMs` for a point-in-time read."
            // `timeoutMs = 0` is the polling path with no budget: one live capture, no retry. One
            // holey capture establishes nothing about the app, so the advice is the retry the
            // caller didn't ask for — not an investigation.
            else ->
              "The ${timeoutMs}ms wait already elapsed without one, so a longer `timeoutMs` only " +
                "helps if the app recovers — investigate why the app stopped answering node fetches."
          },
        )
        is SelectorQueryEngine.Outcome.Resolved -> outcome
      }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "findSelectorMatches: resolve failed for selectors [$selectorSummary]: ${e.message}",
        command = this,
        stackTrace = e.stackTraceToString(),
      )
    }

    val matchesPerSelector = resolved.result
    val structured: JsonElement = TrailblazeJsonInstance.encodeToJsonElement(
      ListSerializer(ListSerializer(MatchDescriptor.serializer())),
      matchesPerSelector,
    )
    val total = matchesPerSelector.sumOf { it.size }
    val matchedSelectors = matchesPerSelector.count { it.isNotEmpty() }
    // The capture count is REPORTED rather than asserted as 1: the whole claim of this tool is
    // "N selectors, one capture", and a hard-coded "from 1 capture" would keep saying so on the
    // paths that legitimately re-capture (a partial tree) or poll. A reader comparing this line
    // against the selector count is exactly who needs it to be true.
    return TrailblazeToolResult.Success(
      message = "findSelectorMatches: $total match${if (total == 1) "" else "es"} across " +
        "$matchedSelectors/${selectors.size} selector(s), from ${resolved.captures} capture(s)",
      structuredContent = structured,
    )
  }

  /**
   * The whole point of the tool: N selectors, one already-captured [tree], no device round trip
   * between them. Delegates each selector to [SelectorMatchResolution] — the same code
   * `findMatches` uses — so batching changes when the captures happen and nothing about what
   * matches.
   *
   * The verdict it hands [SelectorQueryEngine] is where N selectors differ from one, and it is the
   * whole of the difference. `matched` and `claimsAbsence` are both true when SOME selectors
   * matched and others didn't — so a partial capture in that state is neither trusted nor
   * discarded on the strength of the match alone: the engine keeps looking for a frame that is
   * either complete or unanimous. That is deliberately the strict reading, because it is what N
   * separate `findMatches` calls would have done — each one would have refused for its own empty
   * selector.
   */
  private fun resolveAll(
    tree: TrailblazeNode,
    selectorDescs: List<String>,
  ): SelectorQueryEngine.Resolution<List<List<MatchDescriptor>>> {
    // At most one walk of the tree for the whole batch, and none at all if every selector misses.
    // Describing a match needs its index path, and looking that up per match walks the tree again —
    // O(matches x nodes), invisible for ordinary selectors and ~109ms for one matching most of an
    // 8,000-node screen, on every poll. Shared across the N selectors because it depends only on
    // the tree; lazy because a batch of presence probes that all come back empty needs no paths.
    val indexPaths = lazy { MatchDescriptorBuilder.indexPaths(tree) }
    val matchesPerSelector = selectors.mapIndexed { index, selector ->
      SelectorMatchResolution.resolve(
        tree = tree,
        selector = selector,
        selectorDesc = selectorDescs[index],
        logTag = LOG_TAG,
        indexPaths = indexPaths,
      )
    }
    return SelectorQueryEngine.Resolution(
      result = matchesPerSelector,
      matched = matchesPerSelector.any { it.isNotEmpty() },
      claimsAbsence = matchesPerSelector.any { it.isEmpty() },
    )
  }

  /** Descriptions of the selectors that matched nothing — what the partial-capture refusal names. */
  private fun emptySelectorDescs(
    matchesPerSelector: List<List<MatchDescriptor>>,
    selectorDescs: List<String>,
  ): List<String> = matchesPerSelector.indices
    .filter { matchesPerSelector[it].isEmpty() }
    .map { selectorDescs[it] }

  /**
   * The current driver produces no [TrailblazeNode] tree, so node selectors cannot be resolved.
   * Worded to match [FindMatchesTrailblazeTool]'s so the two read the same in a log.
   */
  private fun missingTreeError(platform: TrailblazeDevicePlatform): TrailblazeToolResult.Error.ExceptionThrown =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "findSelectorMatches: current driver does not produce a TrailblazeNode tree " +
        "(platform=${platform.name}). The selectors cannot be resolved.",
    )

  /**
   * The refusal both resolve paths share: this call never got a capture it could read "no matches"
   * out of for [emptySelectorDescs], so it says so instead of returning empty lists a caller reads
   * as absence.
   */
  private fun partialCaptureError(
    emptySelectorDescs: List<String>,
    captures: Int,
    sawCompleteCapture: Boolean,
    nextStep: String,
  ): TrailblazeToolResult.Error.ExceptionThrown =
    TrailblazeToolResult.Error.ExceptionThrown(
      errorMessage = "findSelectorMatches: " +
        // "every capture was partial" is a stronger claim than this call can always make — when a
        // complete capture did arrive, only the one being returned was holey. Saying the former
        // anyway would send a reader off debugging a wedged app that isn't wedged.
        (
          if (sawCompleteCapture) {
            "the capture this wait ended on dropped node fetches"
          } else {
            "$captures capture(s) each dropped node fetches"
          }
          ) + " and " +
        "${emptySelectorDescs.size} of ${selectors.size} selector(s) matched nothing " +
        "([${emptySelectorDescs.joinToString(", ")}]), so whether those elements are on screen is " +
        "unknown — the app was not answering accessibility node fetches (typically a blocked main " +
        "thread) and the captured tree is missing subtrees. Reporting no matches here would read " +
        "as 'absent'. " + nextStep,
      command = this,
    )

  companion object {
    /**
     * `cat` for the single per-call [TrailblazeTracer] span, kept as the class simple name so the
     * span groups with the tool in trace tooling.
     */
    private val TRACE_CAT: String = FindSelectorMatchesTrailblazeTool::class.simpleName!!

    /** Prefix on this tool's [SelectorQueryEngine] log lines, so a log names the tool, not the engine. */
    private const val LOG_TAG = "FindSelectorMatches"
  }
}
