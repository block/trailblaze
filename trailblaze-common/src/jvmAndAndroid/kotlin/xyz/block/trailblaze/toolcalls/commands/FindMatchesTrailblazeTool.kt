package xyz.block.trailblaze.toolcalls.commands

import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.api.MatchDescriptor
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.toMatchDescriptor
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ReadOnlyTrailblazeTool
import xyz.block.trailblaze.toolcalls.SnapshotCache
import xyz.block.trailblaze.toolcalls.TrailblazeToolClass
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.tracing.TrailblazeTracer
import xyz.block.trailblaze.util.Console

/**
 * Query tool: resolves a [TrailblazeNodeSelector] against the current view
 * hierarchy and returns every match as a [MatchDescriptor] list.
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
 * Routes the capture through [SnapshotCache] so a tool body that calls
 * `findMatches` multiple times within one invocation pays the multi-second
 * view-hierarchy fetch once. Falls back to a direct
 * [TrailblazeToolExecutionContext.screenStateProvider] call when no cache frame
 * is active (unit tests, direct invocations).
 */
@Serializable
@TrailblazeToolClass(
  name = "findMatches",
  surfaceToLlm = false,
  surfaceToScriptedTools = true,
  isRecordable = false,
  isVerification = false,
)
data class FindMatchesTrailblazeTool(
  /** Selector to match against the current view hierarchy. */
  val selector: TrailblazeNodeSelector,
  /**
   * Optional wait budget in milliseconds. `null` (the default) keeps the historical behavior: a
   * single point-in-time snapshot, reusing the per-invocation [SnapshotCache] frame. When set, the
   * tool polls the LIVE hierarchy — re-capturing every [pollIntervalMs], bypassing the cache so
   * each poll sees the current screen — until at least one match appears or the budget elapses,
   * then returns whatever matched (an empty list if nothing did).
   *
   * This is the non-throwing "wait until this selector is visible" probe that scripted tools use
   * for conditional flows. It's the framework-side equivalent of the Kotlin agent's
   * `isTextVisible(regex, timeoutMs)` / `executeNodeSelectorAssertVisible(timeoutMs)`, but it
   * RETURNS matches rather than asserting — so "absent after the timeout" is a normal empty result
   * (the caller's `matches.length === 0` branch), not a logged verification failure. Keeps scripted
   * authors from hand-rolling a poll loop on top of point-in-time `findMatches`.
   *
   * A value `<= 0` performs a single immediate live capture (no wait) and returns its matches —
   * effectively the point-in-time result routed through the polling path rather than an error.
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
      if (timeoutMs == null) {
        // Point-in-time: route through SnapshotCache so repeated findMatches in one tool body
        // share the multi-second capture.
        val screenState = SnapshotCache.snapshot(provider, traceTag)
        val tree = screenState.trailblazeNodeTree
          ?: return missingTreeError(screenState.trailblazeDevicePlatform)
        TrailblazeTracer.trace(name = "findMatches", cat = TRACE_CAT, args = traceArgs) {
          resolveMatches(tree, selectorDesc)
        }
      } else {
        val outcome = TrailblazeTracer.traceSuspend(name = "findMatches", cat = TRACE_CAT, args = traceArgs) {
          pollForMatches(provider, selectorDesc, timeoutMs)
        }
        when (outcome) {
          // A driver that NEVER produced a node tree across the whole poll (e.g. a Maestro-only
          // driver whose ScreenState has no `trailblazeNodeTree`) is a driver/platform mismatch —
          // surface the SAME error as the point-in-time path rather than a misleading empty result
          // that would send a scripted caller down its "absent element" branch. A merely TRANSIENT
          // null (a tree was seen on some other poll) does NOT trigger this.
          is PollOutcome.NoTreeEverSeen -> return missingTreeError(outcome.platform)
          is PollOutcome.Resolved -> outcome.descriptors
        }
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
   * Polls the LIVE hierarchy until ≥1 match or [timeoutMs] elapses. Re-captures via [provider]
   * directly (NOT [SnapshotCache]) each [pollIntervalMs] so every iteration sees the current
   * screen — the whole point is to catch UI that renders after the call begins.
   *
   * Tree handling mirrors the point-in-time path's contract:
   *  - A null tree on SOME polls but a real tree on others (mid-transition) is fine — those polls
   *    are just "no match yet".
   *  - A tree that is NEVER seen across the whole poll (a driver whose `ScreenState` has no
   *    `trailblazeNodeTree`, e.g. Maestro-only) returns [PollOutcome.NoTreeEverSeen] so the caller
   *    surfaces the same missing-tree error the point-in-time path returns, instead of a misleading
   *    empty result.
   *
   * The per-iteration sleep is capped to the remaining budget so a small [timeoutMs] returns at
   * ~[timeoutMs] rather than rounding up to a full poll interval.
   *
   * The re-capture loop is intentionally simple; a future optimization could route through a
   * driver-native wait (the accessibility / Maestro side already has an event-driven
   * `executeNodeSelectorAssertVisible` wait) to avoid a full-tree capture on every poll.
   */
  private suspend fun pollForMatches(
    provider: () -> ScreenState,
    selectorDesc: String,
    timeoutMs: Long,
  ): PollOutcome {
    val start = TimeSource.Monotonic.markNow()
    val budget = timeoutMs.milliseconds
    var sawTree = false
    var lastPlatform = TrailblazeDevicePlatform.ANDROID
    var descriptors: List<MatchDescriptor> = emptyList()
    var pollCount = 0
    while (true) {
      pollCount++
      val screenState = provider()
      lastPlatform = screenState.trailblazeDevicePlatform
      val tree = screenState.trailblazeNodeTree
      if (tree != null) {
        sawTree = true
        descriptors = resolveMatches(tree, selectorDesc)
        if (descriptors.isNotEmpty()) return PollOutcome.Resolved(descriptors)
      }
      val remaining = budget - start.elapsedNow()
      if (remaining <= Duration.ZERO) break
      delay(minOf(pollIntervalMs.milliseconds, remaining))
    }
    return if (sawTree) {
      // Polled the whole budget against a resolvable tree but the selector never matched — a
      // normal "not visible within timeout" outcome (empty Resolved, NOT an error). Logged so a
      // scripted author debugging a flaky wait can see the poll actually ran rather than
      // short-circuiting. `descriptors` is empty here: a non-empty match returns inside the loop.
      Console.log(
        "[FindMatches] timeoutMs=$timeoutMs elapsed after $pollCount poll(s); selector never " +
          "matched, returning empty — selector=$selectorDesc",
      )
      PollOutcome.Resolved(descriptors)
    } else {
      PollOutcome.NoTreeEverSeen(lastPlatform)
    }
  }

  /**
   * Resolves [selector] against a captured [tree] into [MatchDescriptor]s — shared by the
   * point-in-time and polling paths. Intentionally UNTRACED: the single enclosing
   * [TrailblazeTracer] span is emitted once per `findMatches` call by [execute] (so the polling
   * path doesn't emit one span per re-capture). Drops any resolved node the descriptor builder
   * can't path-resolve against the same tree (observable via [Console.log]) so one bad node never
   * sinks the whole result.
   */
  private fun resolveMatches(tree: TrailblazeNode, selectorDesc: String): List<MatchDescriptor> {
    val matchedNodes: List<TrailblazeNode> = when (
      val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)
    ) {
      is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
      is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node)
      is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
    }
    return matchedNodes.mapNotNull { node ->
      node.toMatchDescriptor(tree) ?: run {
        // Observable signal for a resolver-vs-captured-tree mismatch — the resolver handed back
        // a node the builder couldn't path-resolve against the same tree. Production-path bug if
        // it fires, but the tool degrades to "skip that match" so the rest still flows.
        Console.log(
          "[FindMatches] dropping match — node not in captured tree, nodeId=${node.nodeId}, " +
            "selector=$selectorDesc",
        )
        null
      }
    }
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

  /** Outcome of [pollForMatches]. [NoTreeEverSeen] is the persistent-no-tree driver mismatch. */
  private sealed interface PollOutcome {
    data class Resolved(val descriptors: List<MatchDescriptor>) : PollOutcome

    data class NoTreeEverSeen(val platform: TrailblazeDevicePlatform) : PollOutcome
  }

  companion object {
    /**
     * `cat` for the single per-call [TrailblazeTracer] span. Kept as the class simple name so the
     * span stays grouped with the tool in trace tooling (pinned by the trace-event test).
     */
    private val TRACE_CAT: String = FindMatchesTrailblazeTool::class.simpleName!!

    /**
     * Production delay between live re-captures on the [timeoutMs] polling path.
     *
     * Deliberately a fixed constant with no `TRAILBLAZE_*` env override: this client-side poll is
     * a stopgap (see [pollForMatches] — the real fix is a driver-native event-driven wait), so it
     * isn't worth a tunable knob plus the CLAUDE.md doc surface that would outlive the mechanism.
     * Tests adjust the sibling [pollIntervalMs] seam directly.
     */
    internal const val DEFAULT_POLL_INTERVAL_MS = 300L

    /**
     * Delay between live re-captures on the [timeoutMs] polling path. A mutable `internal` seam
     * (not a `const`) so tests can shrink it to avoid real-time sleeps; production uses
     * [DEFAULT_POLL_INTERVAL_MS]. The per-iteration sleep is additionally capped to the remaining
     * budget in [pollForMatches] so it never overshoots `timeoutMs`.
     */
    internal var pollIntervalMs: Long = DEFAULT_POLL_INTERVAL_MS
  }
}
