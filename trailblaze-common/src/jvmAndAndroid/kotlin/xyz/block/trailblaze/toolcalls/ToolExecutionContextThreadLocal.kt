package xyz.block.trailblaze.toolcalls

import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.util.Console

/**
 * Per-thread snapshot of the active dispatch's [TrailblazeToolExecutionContext], used to
 * surface a session-scoped context to bridges that can't take a context as a method
 * parameter — most notably the QuickJS host-binding callback path
 * (`xyz.block.trailblaze.quickjs.tools.SessionScopedHostBinding`) where a bundled author
 * handler calling `trailblaze.call(name, args)` re-enters the runtime through a
 * `(name, argsJson)` binding signature with no place to thread the context through.
 *
 * Lives in `:trailblaze-common` (rather than alongside the binding in
 * `:trailblaze-quickjs-tools`) so the install site in
 * [xyz.block.trailblaze.BaseTrailblazeAgent.runTrailblazeTools] doesn't need a compile-time
 * dep on `:trailblaze-quickjs-tools` — the binding consumes the slot on the read side.
 *
 * ## Lifecycle
 *
 *  - [install] / [clear] are called as a `try { install... } finally { clear... }` pair
 *    around the sequential `runTrailblazeTools` loop. The pairing guarantees the slot is
 *    cleared even if a tool throws.
 *  - The slot is `null` outside an install; readers handle the null case explicitly rather
 *    than relying on a default-initial.
 *  - Idiomatically a single thread runs every tool in a batch (the loop in
 *    `BaseTrailblazeAgent` is sequential and `runBlocking { ... }` for nested suspends),
 *    so per-thread storage matches the actual dispatch shape.
 *
 * ## When NOT to use this
 *
 * Any code path that already has the [TrailblazeToolExecutionContext] in scope as a
 * parameter should pass it explicitly. The thread-local is a last-resort plumbing option
 * for callbacks where the context can't be threaded through (the QuickJS binding above).
 * Treating it as a general-purpose ambient context invites the usual thread-local pitfalls
 * — leakage across thread reuse, missing values under coroutine dispatcher hops, etc.
 *
 * ## Coroutine-dispatcher hazard — DO NOT introduce dispatcher hops on the read path
 *
 * `BaseTrailblazeAgent.runTrailblazeTools` is sequential per batch: every tool runs on
 * the same thread that called `install`, and any nested `runBlocking { ... }` reuses that
 * thread by design. The QuickJS binding's read of [get] therefore observes the same slot
 * the install wrote, with no thread switch in between.
 *
 * If a future change introduces `withContext(Dispatchers.X)` / `async` / `launch` /
 * `flowOn` / a custom coroutine context anywhere on the path between [install] and the
 * binding's [get] call, the read can land on a different OS thread and `slot.get()`
 * returns `null` even though the outer batch is still active. The binding then surfaces
 * a `CALL_NO_CONTEXT` error envelope mid-batch and dispatch breaks in confusing ways.
 *
 * **Use a [kotlin.coroutines.CoroutineContext] element instead** if you need to
 * reintroduce dispatcher hops to the binding's read path — see `JsScriptingCallbackDispatchDepth`
 * in this same module for the codebase's established `CoroutineContext` precedent. This
 * primitive intentionally trades coroutine-portability for a simpler install/clear shape;
 * if that trade-off stops being safe, the right fix is to migrate to a context element,
 * not to widen this primitive.
 */
object ToolExecutionContextThreadLocal {

  /**
   * Internal slot. `internal` so unit tests in this module can poke at it without forcing
   * a public read API on top of [install] / [clear]. Reader code outside this module
   * should use [get].
   */
  internal val slot: ThreadLocal<TrailblazeToolExecutionContext?> =
    ThreadLocal.withInitial { null }

  /**
   * Read the currently-installed context for this thread, or `null` when no install is
   * active. Callers should treat `null` as a configuration error (the binding callback
   * fired without a paired `runTrailblazeTools` install) and surface a structured error
   * to their caller rather than silently no-op'ing.
   */
  fun get(): TrailblazeToolExecutionContext? = slot.get()

  /**
   * Install [ctx] for the duration of one `runTrailblazeTools` batch on this thread.
   * Pair with [clear] in a `finally` block so the slot is freed even if a tool throws —
   * a leaked install would let a later batch on the same thread observe a stale context.
   *
   * Logs a [Console] warning when an existing non-null value is being clobbered. This
   * indicates either a missing [clear] in a prior batch or two concurrent install sites
   * racing — both bugs at the call site, not legitimate states. The clobber proceeds so
   * the new dispatch isn't blocked.
   */
  fun install(ctx: TrailblazeToolExecutionContext) {
    val prior = slot.get()
    if (prior != null) {
      Console.log(
        "[ToolExecutionContextThreadLocal] CONTEXT_CLOBBER thread=${Thread.currentThread().name} " +
          "— installing new context while a prior one is still in place; check the install site",
      )
    }
    slot.set(ctx)
  }

  /**
   * Free the slot for this thread. Idempotent — calling on an empty slot is a no-op so
   * `try { } finally { clear() }` works regardless of whether the matching [install]
   * actually ran.
   */
  fun clear() {
    slot.remove()
  }

  /**
   * Run [block] with [ctx] bound to the [TrailblazeToolExecutionContext] ThreadLocal,
   * propagated correctly across **coroutine dispatcher hops**. Use this when the block
   * is a suspend chain that may resume on a different thread than the one that installed
   * the context — typically when the chain ends in a QuickJS host's async binding callback
   * (`__trailblazeCall`) which can be invoked on whatever dispatcher the JS engine runs on.
   *
   * Internally uses [ThreadLocal.asContextElement] so the value rides on the coroutine
   * context and gets re-installed on whatever thread the suspension resumes on. This is
   * the recommended pattern for coroutine-aware ThreadLocal access (see
   * `ToolExecutionContextThreadLocal`'s class kdoc — same dispatcher-hop hazard).
   *
   * Pairs naturally with [install] / [clear] for non-suspend / sequential-batch callers
   * (`BaseTrailblazeAgent.runTrailblazeTools`); use this method when the block can suspend
   * across thread switches.
   */
  suspend fun <T> withInstalledContext(
    ctx: TrailblazeToolExecutionContext,
    block: suspend () -> T,
  ): T = withContext(slot.asContextElement(ctx)) { block() }
}
