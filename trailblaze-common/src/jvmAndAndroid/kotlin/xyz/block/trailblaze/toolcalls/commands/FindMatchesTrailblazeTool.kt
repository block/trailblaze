package xyz.block.trailblaze.toolcalls.commands

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.api.MatchDescriptor
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.TrailblazeNodeSelector
import xyz.block.trailblaze.api.TrailblazeNodeSelectorResolver
import xyz.block.trailblaze.api.toMatchDescriptor
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
) : ExecutableTrailblazeTool, ReadOnlyTrailblazeTool {

  override suspend fun execute(toolExecutionContext: TrailblazeToolExecutionContext): TrailblazeToolResult {
    val provider = toolExecutionContext.screenStateProvider
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "findMatches: no screenStateProvider available on the execution context.",
      )
    val traceTag = toolExecutionContext.traceId?.traceId
    val screenState = SnapshotCache.snapshot(provider, traceTag)
    val tree: TrailblazeNode = screenState.trailblazeNodeTree
      ?: return TrailblazeToolResult.Error.ExceptionThrown(
        errorMessage = "findMatches: current driver does not produce a TrailblazeNode tree " +
          "(platform=${screenState.trailblazeDevicePlatform.name}). The selector cannot be resolved.",
      )

    // Hoist the selector description out of the try block so both the drop-log and the
    // error-message paths reuse the same string — deeply nested `containsDescendants`
    // selectors traverse the whole subtree to render and we don't want to do it twice.
    val selectorDesc = selector.description()

    // Resolver throws are wrapped into a structured `Error.ExceptionThrown` carrying the
    // selector that failed — keeps the dispatch path from leaking a raw stack trace and
    // gives scripted-tool authors a parseable error envelope. `CancellationException`
    // re-throws so structured-concurrency cancellation isn't swallowed.
    //
    // The resolve + descriptor build is wrapped in `TrailblazeTracer.trace` so it shows
    // up in session traces alongside the legacy Maestro matcher (which already traces
    // via `getMatchingElementsFromSelector`). Without this, `findMatches` is invisible
    // to the same trace.json tooling that profiles every other selector resolve.
    val descriptors: List<MatchDescriptor> = try {
      TrailblazeTracer.trace(
        name = "findMatches",
        cat = FindMatchesTrailblazeTool::class.simpleName!!,
        args = mapOf("selector" to selectorDesc),
      ) {
        val matchedNodes: List<TrailblazeNode> = when (
          val result = TrailblazeNodeSelectorResolver.resolve(tree, selector)
        ) {
          is TrailblazeNodeSelectorResolver.ResolveResult.NoMatch -> emptyList()
          is TrailblazeNodeSelectorResolver.ResolveResult.SingleMatch -> listOf(result.node)
          is TrailblazeNodeSelectorResolver.ResolveResult.MultipleMatches -> result.nodes
        }
        matchedNodes.mapNotNull { node ->
          node.toMatchDescriptor(tree) ?: run {
            // Observable signal for a resolver-vs-captured-tree mismatch — the resolver
            // handed back a node the builder couldn't path-resolve against the same tree.
            // Production-path bug if it fires, but the tool degrades to "skip that match"
            // so the rest of the result still flows to the caller.
            Console.log(
              "[FindMatches] dropping match — node not in captured tree, nodeId=${node.nodeId}, " +
                "selector=$selectorDesc",
            )
            null
          }
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
}
