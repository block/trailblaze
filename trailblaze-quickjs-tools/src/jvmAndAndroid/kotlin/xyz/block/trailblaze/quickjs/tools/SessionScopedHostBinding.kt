package xyz.block.trailblaze.quickjs.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.DelegatingTrailblazeTool
import xyz.block.trailblaze.toolcalls.ExecutableTrailblazeTool
import xyz.block.trailblaze.toolcalls.ToolExecutionContextThreadLocal
import xyz.block.trailblaze.toolcalls.TrailblazeTool
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.util.Console

/**
 * Live [HostBinding] that dispatches `trailblaze.call(name, args)` from inside an author
 * bundle through the session's [TrailblazeToolRepo]. Replaces the stub returns in
 * [QuickJsRepoHostBinding] for inline scripted-tool composition.
 *
 * ### Resolution flow
 *
 * 1. Look up the named tool via
 *    [TrailblazeToolRepo.toolCallToTrailblazeToolUnfiltered] — this bypasses the
 *    `isForLlm = false` filter that hides YAML-defined building-block tools from the LLM
 *    descriptor list. Class-backed tools were already reachable through the dispatch path
 *    (see `TrailblazeToolRepo.toolCallToTrailblazeTool` line 257-269); the unfiltered
 *    variant extends the bypass to YAML-defined tools that exist globally on the classpath
 *    but aren't in this session's registered set.
 * 2. Execute the tool against the [TrailblazeToolExecutionContext] snapshotted in
 *    [ToolExecutionContextThreadLocal] at the outer `runTrailblazeTools` boundary. Both
 *    [ExecutableTrailblazeTool] (single execute) and [DelegatingTrailblazeTool] (expand to
 *    a sequence of executables and run them in order) are supported. The latter is what a
 *    YAML-defined tool resolves to.
 * 3. Serialize the resulting [TrailblazeToolResult] to JSON via [TrailblazeJsonInstance]
 *    and return the string for QuickJS to `JSON.parse`.
 *
 * ### Failure modes
 *
 * Per the [HostBinding] contract documented on [QuickJsRepoHostBinding], this binding
 * **never throws back to JS**. Every failure path produces a structured envelope JSON
 * `{"isError":true,"error":"<message>"}` so the awaiting handler sees a well-formed
 * response on the JS side instead of an opaque transport error. Failure cases:
 *
 *  - Unknown tool name (no class-backed, dynamic, or YAML-defined match).
 *  - No execution context installed in the [ToolExecutionContextThreadLocal] — caller forgot to call
 *    [installContext] at the dispatch boundary.
 *  - Tool resolved but not executable (neither [ExecutableTrailblazeTool] nor
 *    [DelegatingTrailblazeTool]).
 *  - The tool's `execute(...)` throws.
 *  - The tool returns a [TrailblazeToolResult.Error] (mapped to the same envelope shape so
 *    the JS side can branch on `isError`).
 *
 * ### Concurrency
 *
 * Thread-local installation is safe because the host's `evalMutex` (see [QuickJsToolHost])
 * serializes every dispatch through a single thread at a time, and `runTrailblazeTools`
 * is sequential per-batch (see `BaseTrailblazeAgent`). The companion's [installContext] /
 * [clearContext] are called once per batch — overlapping batches on a shared context would
 * indicate a bug at the install site, which [installContext] flags via a warning log.
 */
class SessionScopedHostBinding(
  private val toolRepo: TrailblazeToolRepo,
  private val sessionId: SessionId,
) : HostBinding {

  /**
   * Per-dispatch context the in-process scripted-tool path sets immediately before
   * invoking the host (and clears in `finally`). Bypasses the
   * [ToolExecutionContextThreadLocal] read path because QuickJS's `asyncFunction`
   * callback can resume on a coroutine scope that doesn't inherit `asContextElement`
   * propagation — the ThreadLocal would be `null` on the resumption thread even when
   * the outer caller installed it correctly. Setting on the binding instance is safe
   * because each [LazyYamlScriptedToolRegistration][xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration]
   * gets its own host + binding pair, and the host's `evalMutex` serializes calls so
   * there's at most one in-flight dispatch per binding at a time.
   *
   * `@Volatile` so the read in [callFromBundle] (which may run on a different thread
   * than the writer) sees a coherent value without locking.
   */
  @Volatile
  var activeContext: TrailblazeToolExecutionContext? = null

  override suspend fun callFromBundle(name: String, argsJson: String): String {
    // Prefer the directly-set [activeContext] (used by the in-process scripted-tool
    // dispatch path that wraps the call) and fall back to [ToolExecutionContextThreadLocal]
    // (used by `BaseTrailblazeAgent.runTrailblazeTools` for non-QuickJS paths). Either
    // mechanism is fine — the load-bearing requirement is that the binding sees a real
    // context when QuickJS's async binding callback fires.
    val context = activeContext ?: ToolExecutionContextThreadLocal.get()
    if (context == null) {
      Console.log(
        "[SessionScopedHostBinding] CALL_NO_CONTEXT tool=$name session=${sessionId.value} " +
          "— no TrailblazeToolExecutionContext installed; install at runTrailblazeTools boundary",
      )
      return errorEnvelope(
        "trailblaze.call('$name'): no execution context installed for this session",
      )
    }

    val resolved: TrailblazeTool? = try {
      toolRepo.toolCallToTrailblazeToolUnfiltered(name, argsJson)
    } catch (e: CancellationException) {
      // Coroutine cancellation must propagate even from the lookup phase. Same rationale
      // as the execute-side rethrow below — structured concurrency can't be papered over
      // with an error envelope without breaking session teardown semantics.
      throw e
    } catch (e: kotlinx.serialization.SerializationException) {
      // Preserved as a distinct catch so the log line distinguishes "args didn't decode"
      // from "lookup itself failed" (e.g. a custom KSerializer threw an unchecked
      // exception). The Throwable branch below catches the unchecked-exception case.
      Console.log(
        "[SessionScopedHostBinding] CALL_DECODE_FAIL tool=$name session=${sessionId.value} " +
          "reason=${e.message}",
      )
      return errorEnvelope(
        "trailblaze.call('$name'): failed to decode args — ${e.message}",
      )
    } catch (e: Throwable) {
      // Catches IllegalStateException (the standard "not found" path is null-returned by
      // toolCallToTrailblazeToolUnfiltered, so anything reaching here is a different kind
      // of state issue) AND any unchecked exception thrown out of a custom KSerializer
      // (NPE, IllegalArgumentException, NumberFormatException, …). The binding's contract
      // documented on QuickJsRepoHostBinding is JSON-on-every-path; a propagated unchecked
      // throw would surface to the JS side as an opaque transport error.
      Console.log(
        "[SessionScopedHostBinding] CALL_LOOKUP_FAIL tool=$name session=${sessionId.value} " +
          "reason=${e::class.simpleName}: ${e.message}",
      )
      return errorEnvelope(
        "trailblaze.call('$name'): tool lookup failed — ${e.message ?: e::class.simpleName}",
      )
    }

    if (resolved == null) {
      Console.log(
        "[SessionScopedHostBinding] CALL_UNKNOWN tool=$name session=${sessionId.value}",
      )
      return errorEnvelope(
        "trailblaze.call('$name'): no tool registered with that name",
      )
    }

    val result: TrailblazeToolResult = try {
      executeResolved(resolved, context)
    } catch (e: CancellationException) {
      // Coroutine cancellation must propagate — swallowing it would convert a
      // session timeout / user abort into a normal tool error, defeating structured
      // concurrency teardown. The outer suspend chain (QuickJsTrailblazeTool.execute,
      // BaseTrailblazeAgent.runTrailblazeTools) needs to see the cancellation so the
      // session can wind down deterministically. Mirrors QuickJsTrailblazeTool.execute's
      // pattern. Catch order matters because CancellationException extends Exception
      // (and therefore Throwable).
      throw e
    } catch (e: Throwable) {
      // Catch Throwable so a tool implementation that throws an unchecked exception
      // (NPE / IllegalArgumentException / etc.) doesn't propagate into QuickJS as an
      // engine-level error — the binding's contract is JSON-on-every-path.
      Console.log(
        "[SessionScopedHostBinding] CALL_EXEC_THREW tool=$name session=${sessionId.value} " +
          "reason=${e.message}",
      )
      return errorEnvelope(
        "trailblaze.call('$name'): execute threw — ${e.message ?: e::class.simpleName}",
      )
    }

    // Surface tool-reported errors via the same envelope shape as exceptions so the JS
    // side has a single branch (`isError`) rather than two failure modes to discriminate.
    if (result is TrailblazeToolResult.Error) {
      return errorEnvelope(result.errorMessage)
    }

    return TrailblazeJsonInstance.encodeToString(TrailblazeToolResult.serializer(), result)
  }

  /**
   * Execute the resolved tool against [context]. Handles both single-execute tools
   * ([ExecutableTrailblazeTool]) and YAML-defined / scripted compositions
   * ([DelegatingTrailblazeTool]). For delegating tools, each expanded executable runs in
   * order; the first error short-circuits and is returned; otherwise the last success
   * (or default `Success`) is returned.
   */
  private suspend fun executeResolved(
    tool: TrailblazeTool,
    context: TrailblazeToolExecutionContext,
  ): TrailblazeToolResult {
    return when (tool) {
      is ExecutableTrailblazeTool -> tool.execute(context)
      is DelegatingTrailblazeTool -> {
        val expanded = tool.toExecutableTrailblazeTools(context)
        var last: TrailblazeToolResult = TrailblazeToolResult.Success()
        for (sub in expanded) {
          val r = sub.execute(context)
          if (r is TrailblazeToolResult.Error) return r
          last = r
        }
        last
      }
      else -> TrailblazeToolResult.Error.UnknownTrailblazeTool(command = tool)
    }
  }

  /**
   * Encode a `{"isError":true,"error":"<message>"}` JSON string. Built by hand via
   * [buildJsonObject] so the shape matches the documented binding-error contract exactly,
   * regardless of how [TrailblazeToolResult.Error] serializes (its discriminator field
   * names would leak class info to the JS side; the binding-error envelope is intentionally
   * minimal).
   */
  private fun errorEnvelope(message: String): String {
    val obj: JsonObject = buildJsonObject {
      put("isError", true)
      put("error", message)
    }
    return obj.toString()
  }

  companion object {
    /**
     * Frozen-contract entry point that delegates to the shared
     * [ToolExecutionContextThreadLocal] slot. Lives on the binding's companion (rather than
     * exposing the slot directly to callers) so the install boundary in
     * `BaseTrailblazeAgent.runTrailblazeTools` matches the contract published in the
     * Sub-PR-A2 plan; callers without compile-time access to `:trailblaze-quickjs-tools`
     * should call [ToolExecutionContextThreadLocal.install] directly instead.
     */
    fun installContext(ctx: TrailblazeToolExecutionContext) {
      ToolExecutionContextThreadLocal.install(ctx)
    }

    /**
     * Frozen-contract entry point that delegates to [ToolExecutionContextThreadLocal.clear].
     * See [installContext] for the rationale on routing through the binding's companion.
     */
    fun clearContext() {
      ToolExecutionContextThreadLocal.clear()
    }
  }
}
