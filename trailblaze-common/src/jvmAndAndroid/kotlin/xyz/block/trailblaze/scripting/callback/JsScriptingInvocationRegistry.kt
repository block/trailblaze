package xyz.block.trailblaze.scripting.callback

import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.toolcalls.TrailblazeToolExecutionContext
import xyz.block.trailblaze.toolcalls.TrailblazeToolRepo
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide registry of in-flight `tools/call` invocations so the
 * `/scripting/callback` HTTP endpoint can resolve a callback request's `invocation_id` back to
 * the live session + tool repo + execution context that originated it.
 *
 * Lifecycle: a subprocess `tools/call` dispatch [register]s an entry before sending the MCP
 * request, closes the returned [Handle] in a finally block when the dispatch returns. While the
 * entry is live, the subprocess may call back via HTTP — the endpoint does
 * [lookup] + dispatches through the entry's [TrailblazeToolRepo] + [TrailblazeToolExecutionContext].
 * Concurrent calls on the same session each have their own invocation id; the map is keyed by
 * id, not session.
 *
 * **Process-wide singleton by design.** The callback endpoint lives on the daemon's single HTTP
 * server, and every session's subprocess dispatches into the same registry instance. Using an
 * object (vs. a per-session registry) keeps the lookup path free of session-wiring ceremony the
 * endpoint would otherwise need.
 *
 * **Not persistent.** Daemon restart wipes the registry; subprocess reconnection semantics are
 * out of scope for this landing (the subprocess is spawned per session and doesn't survive the
 * daemon anyway).
 *
 * **Cleanup bound.** Entries are released when the dispatching subprocess closes its [Handle]
 * (typically in a finally block around the outer `tools/call`). If the subprocess terminates
 * abruptly before its finally runs — SIGTERM mid-dispatch, session teardown that skips the
 * normal unwind, crash — the entry stays in the map until daemon restart. There is no TTL,
 * session-teardown sweeper, or shutdown hook; long-running daemons under high session churn
 * could accumulate stale entries. Adding a cleanup mechanism (session-keyed eviction on
 * session close, or a periodic stale-entry sweeper) is a tracked follow-up; not load-bearing
 * today because sessions are coterminous with the daemon process.
 */
object JsScriptingInvocationRegistry {

  /**
   * Bundle of state a callback dispatch needs: the [sessionId] for log correlation,
   * the [toolRepo] for `toolCallToTrailblazeTool` deserialization, and the
   * [executionContext] to pass to the deserialized tool's `execute`. Kept private to prevent
   * callers from fabricating entries outside [register].
   *
   * [depth] is the callback-reentrance depth: 0 for an outer `tools/call` that originated
   * straight from the LLM, N for an invocation registered by a tool that was itself dispatched
   * through the `/scripting/callback` endpoint N levels deep. The endpoint reads this and
   * refuses to dispatch once it exceeds [MAX_CALLBACK_DEPTH] so a buggy recursive callback chain
   * fails fast instead of hanging the session until the outer agent timeout fires.
   *
   * **Shared mutable state warning.** [executionContext] is the SAME object the outer
   * `tools/call` is running against. Any callback-dispatched inner tool runs with the same
   * [xyz.block.trailblaze.AgentMemory], the same driver handle, the same screen-state cache.
   * The outer and inner tool can race on memory reads/writes: a sequence like `memory.get("x");
   * await client.callTool(...); memory.put("x", updated)` is NOT atomic — the inner tool sees
   * and can mutate `memory` mid-sequence. `AgentMemory.variables` is a plain `mutableMapOf`,
   * not concurrent-safe. Authors composing tools via `callTool` must avoid read-then-write
   * patterns across a callback, or serialize the access themselves. Making `AgentMemory`
   * concurrent-safe is a tracked follow-up; the constraint is documented rather than
   * enforced, because no sample tool exercises this pattern today.
   */
  data class Entry internal constructor(
    val sessionId: SessionId,
    val toolRepo: TrailblazeToolRepo,
    val executionContext: TrailblazeToolExecutionContext,
    val depth: Int = 0,
  )

  /**
   * Handle returned by [register]. [close] removes the entry from the registry; idempotent so
   * a caller can safely close in a finally block even if the invocation path already removed
   * the entry on its own.
   */
  class Handle internal constructor(val invocationId: String) : AutoCloseable {
    override fun close() {
      entries.remove(invocationId)
    }
  }

  private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()

  /**
   * Registers a new in-flight invocation. Returns a [Handle] whose [Handle.invocationId] must
   * be forwarded to the subprocess (via the `_meta.trailblaze.invocationId` envelope) so a
   * callback can find its way home. The caller owns closing the handle — typically `.use { }`
   * or a finally-block on the dispatch path.
   *
   * Uses [UUID.randomUUID] for the id so correlation with logs stays unique across the process
   * lifetime and unpredictable enough that a subprocess can't fabricate an id to target another
   * session's invocation.
   */
  fun register(
    sessionId: SessionId,
    toolRepo: TrailblazeToolRepo,
    executionContext: TrailblazeToolExecutionContext,
    depth: Int = 0,
  ): Handle {
    val id = UUID.randomUUID().toString()
    entries[id] = Entry(sessionId, toolRepo, executionContext, depth)
    return Handle(id)
  }

  /**
   * Returns the live [Entry] for [invocationId], or null if no such invocation is in flight.
   * "Not in flight" covers unknown ids (malicious or buggy subprocess), already-completed ids
   * (callback raced the dispatch return), and id collisions with a closed handle. Callers
   * render any of these as a [JsScriptingCallbackResult.Error] to the subprocess.
   */
  fun lookup(invocationId: String): Entry? = entries[invocationId]

  /**
   * Clears all entries. Test-only escape hatch — the endpoint + registry are a process-wide
   * singleton so JUnit's `@After` hooks call this to avoid state bleed between test methods.
   * Do not call from production code; doing so mid-flight would cause every in-flight callback
   * to fail the lookup step with a spurious "invocation not found" error.
   *
   * Public (vs. internal) because tests in `:trailblaze-server` also depend on it to reset the
   * registry between endpoint-test cases, and a module-crossing `internal` accessor would
   * require exposing a public wrapper anyway. Marking it public + documenting the
   * not-for-production intent keeps the surface honest.
   */
  fun clearForTest() {
    entries.clear()
  }

  /**
   * Cap on [Entry.depth] enforced by the `/scripting/callback` endpoint. 16 mirrors Decision
   * 038's execution-model cap for in-process scripts so a tool moved between subprocess and
   * QuickJS runtimes doesn't hit a surprise boundary. See the client-callTool devlog for
   * the rationale.
   */
  const val MAX_CALLBACK_DEPTH: Int = 16
}
