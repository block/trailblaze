package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.util.Console

/**
 * Per-thread, stack-scoped cache for an active dispatch batch's [ScreenState].
 *
 * Carries one cached snapshot per frame. Within a frame, repeated calls to
 * [snapshot] return the same captured [ScreenState] without re-running the
 * multi-second view-hierarchy fetch — the query tool that motivated this
 * (`findMatches`) needs to be cheap enough that scripted authors can call it
 * naturally on every branch in a tool body. When the frame pops the cached
 * snapshot is freed, so a future batch starts with a fresh capture.
 *
 * ## Frame lifecycle in [xyz.block.trailblaze.BaseTrailblazeAgent.runTrailblazeTools]
 *
 * Today the dispatch loop pushes **one frame per batch** (around the whole
 * `for (tool in tools)` loop), not one frame per tool. All sibling tools in
 * the same batch share the slot — that's what makes
 * `findMatches → findMatches` in the same batch reuse the captured tree. The
 * loop calls [invalidateCurrent] on the way out of any tool that isn't a
 * [ReadOnlyTrailblazeTool] and isn't a verification, so a `findMatches →
 * tap → findMatches` sequence re-captures after `tap`.
 *
 * Re-entrant frame nesting (one tool's execute() pushes another frame via
 * [withFrame]) is supported by the stack — child frames don't affect the
 * parent's slot, and invalidation in a child doesn't propagate up. Today no
 * production caller does this; the stack shape leaves the door open.
 *
 * ## Why thread-local, not [kotlin.coroutines.CoroutineContext]
 *
 * The dispatch path that owns frames ([BaseTrailblazeAgent.runTrailblazeTools]
 * and its sequential `for (tool in tools)` loop with nested
 * `runBlocking { resolved.execute(context) }`) is single-threaded per session
 * by design — every tool in a batch resumes on the install thread. This matches
 * the established pattern in [ToolExecutionContextThreadLocal] (see its kdoc on
 * the coroutine-dispatcher hazard). If a future change introduces dispatcher
 * hops on the read path, migrate to a `CoroutineContext` element rather than
 * widening this primitive — same trade-off the existing thread-local makes.
 *
 * ## Lifecycle
 *
 * - [withFrame] is the only entry point. It pushes a fresh slot, runs the
 *   caller's block, and pops the slot in a `finally` — the slot can't leak
 *   even if the block throws.
 * - Nested [withFrame] calls (re-entrant dispatch) each get their own slot;
 *   the parent's slot is untouched and reappears when the nested block returns.
 * - Outside any [withFrame], [snapshot] falls back to capturing via the
 *   supplied provider on every call (no cache available, no error — callers
 *   in unit tests / direct invocations work normally).
 *
 * ## Authoring rule for query-shaped Kotlin tools
 *
 * The dispatch loop in `BaseTrailblazeAgent.runTrailblazeTools` invalidates the
 * cache after every tool by default — that's the safe choice for tools whose
 * behaviour the dispatcher can't introspect. Two opt-out paths preserve the
 * cached snapshot for a follow-up query:
 *
 *  - Mark the tool class with [ReadOnlyTrailblazeTool] for query-shaped tools
 *    (today: `FindMatchesTrailblazeTool`). Use this when the tool reads from
 *    the device but never mutates state.
 *  - Set `@TrailblazeToolClass(isVerification = true)` for assertion tools.
 *    Verifications never mutate, and the dispatcher recognises this flag.
 *
 * `isRecordable = false` does NOT exempt a tool from invalidation — it's a
 * delegation/recording marker, not a read-only marker. `TapTrailblazeTool`
 * has `isRecordable = false` while still mutating the device, so the gate
 * has to be explicit about which tools are genuinely read-only.
 *
 * ## Diagnostic logging
 *
 * Capture / reuse / invalidate decisions are emitted via [Console.log] with the
 * `[SnapshotCache]` prefix, matching the convention used by `[AndroidHostAdbUtils]`
 * and `[ScriptedToolDefinitionAnalyzer]` elsewhere. Logs are quiet by default in CI
 * (Console.log is silenced) and visible during local debugging. Grep with
 * `grep "\[SnapshotCache\]"` to isolate the cache behaviour from the surrounding
 * tool-execution stream.
 */
object SnapshotCache {

  private class Slot(var screenState: ScreenState? = null)

  private val stack: ThreadLocal<ArrayDeque<Slot>> =
    ThreadLocal.withInitial { ArrayDeque() }

  /**
   * Push a frame, run [block], pop the frame.
   *
   * Use this to bracket one tool-invocation worth of work — the dispatcher does
   * this around every tool in a `runTrailblazeTools` batch, but tests and other
   * direct callers may also wrap their own tool execute() calls to opt into
   * caching.
   */
  inline fun <T> withFrame(block: () -> T): T {
    pushFrame()
    return try {
      block()
    } finally {
      popFrame()
    }
  }

  /**
   * Return the cached snapshot in the current frame, or capture one via
   * [provider] and cache it. Outside any frame (no [withFrame] active),
   * captures via [provider] without caching.
   *
   * [traceTag] is appended to the `[SnapshotCache]` log prefix when non-null
   * so concurrent-session logs can be disambiguated via `grep`. Pass the
   * current trace id from `TrailblazeToolExecutionContext.traceId.value`
   * when one is available. Hit-path is silent — only first capture and
   * invalidate emit logs.
   */
  fun snapshot(provider: () -> ScreenState, traceTag: String? = null): ScreenState {
    val top = stack.get().lastOrNull()
    if (top == null) {
      Console.log("${prefix(traceTag)} miss (no active frame) — capturing via provider, no cache available")
      return provider()
    }
    val cached = top.screenState
    if (cached != null) {
      // Hit-path is silent — chatty when invoked many times per batch and
      // adds no operational signal. Misses and invalidations are the
      // transition events worth surfacing.
      return cached
    }
    Console.log("${prefix(traceTag)} miss (frame empty) — capturing and caching")
    return provider().also { top.screenState = it }
  }

  /**
   * Drop the current frame's cached snapshot so the next [snapshot] call
   * re-captures. No-op when called outside any frame.
   *
   * Action tools call this on the way out of their execute() so a query
   * issued later in the same frame (e.g. by a wrapping scripted tool whose
   * body taps then re-queries) doesn't read the pre-tap tree.
   *
   * See [snapshot] for [traceTag] semantics.
   */
  fun invalidateCurrent(traceTag: String? = null) {
    val top = stack.get().lastOrNull() ?: return
    if (top.screenState != null) {
      Console.log("${prefix(traceTag)} invalidate — dropping cached snapshot for current frame")
      top.screenState = null
    }
  }

  private fun prefix(traceTag: String?): String =
    if (traceTag.isNullOrBlank()) "[SnapshotCache]" else "[SnapshotCache:$traceTag]"

  /** Test-only — current frame depth on this thread. Always >= 0. */
  internal fun frameDepth(): Int = stack.get().size

  /** Internal — push a fresh slot. Public for [withFrame]'s inline call site. */
  fun pushFrame() {
    stack.get().addLast(Slot())
  }

  /** Internal — pop the topmost slot, if any. Public for [withFrame]'s inline call site. */
  fun popFrame() {
    val deque = stack.get()
    deque.removeLastOrNull()
    if (deque.isEmpty()) stack.remove()
  }
}
