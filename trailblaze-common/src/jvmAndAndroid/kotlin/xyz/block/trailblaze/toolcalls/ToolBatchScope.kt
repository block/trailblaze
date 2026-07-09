package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.util.Console

/**
 * Per-thread marker for a "shared tool batch" — a group of tool dispatches that should run against
 * ONE [TrailblazeToolExecutionContext] and ONE [SnapshotCache] frame, instead of each
 * [xyz.block.trailblaze.BaseTrailblazeAgent.runTrailblazeTools] call building its own.
 *
 * ## Why this exists
 *
 * Recorded replay ([xyz.block.trailblaze.rules.TrailblazeRunnerUtil.runRecordedTools]) dispatches a
 * step's recorded tools one-at-a-time — historically one `runTrailblazeTools` call per tool, so
 * each tool got a fresh execution context. Any device state cached ON the context (e.g. the Android
 * clipboard cache on `AndroidDeviceCommandExecutor`, which must be in-process because Android 10+
 * blocks cross-process clipboard reads) was dropped between tools, so a `mobile_setClipboard` →
 * `mobile_pasteClipboard` recording read an empty clipboard. Wrapping the per-tool loop in
 * [xyz.block.trailblaze.BaseTrailblazeAgent.runInSharedToolBatch] opens a scope; the per-tool
 * `runTrailblazeTools` calls then reuse the scope's single context + frame, so the recording
 * behaves like the legacy single-`- tools:`-block batch that already shares one context.
 *
 * ## Lifecycle
 *
 *  - [enter] marks a scope active on this thread. The context + frame are built LAZILY on the first
 *    [contextOrBuild] so the caller (the runner) doesn't need the screen-state-provider inputs
 *    `buildExecutionContext` requires.
 *  - [contextOrBuild] returns the scope's context, building it (and installing the context
 *    ThreadLocal + pushing one [SnapshotCache] frame) on first call and reusing it after.
 *  - [exit] tears down exactly what the scope established (pops the frame, clears the ThreadLocal)
 *    and clears the marker. Always call it in a `finally`.
 *
 * ## Thread-scoped, not coroutine-scoped
 *
 * Same trade-off as [SnapshotCache] / [ToolExecutionContextThreadLocal]: the recorded-replay loop
 * runs sequentially on one thread (each per-tool dispatch completes before the next), so a
 * ThreadLocal marker is sufficient and simplest. Do NOT reuse this across a coroutine dispatcher
 * hop (e.g. the QuickJS binding callback) — see the [ToolExecutionContextThreadLocal] kdoc on that
 * hazard. Nested scripted-tool composition that wants a shared context should reuse the
 * closure-captured outer context instead.
 */
object ToolBatchScope {

  private class Scope(val enteredThread: Thread) {
    var context: TrailblazeToolExecutionContext? = null
    var pushedFrame: Boolean = false
    var installedThreadLocal: Boolean = false
  }

  private val current: ThreadLocal<Scope?> = ThreadLocal.withInitial { null }

  /** True when a shared batch scope is open on this thread. */
  fun isActive(): Boolean = current.get() != null

  /**
   * Open a scope on this thread.
   *
   * @throws IllegalStateException if a scope is already active on this thread. The only
   * caller ([xyz.block.trailblaze.BaseTrailblazeAgent.runInSharedToolBatch]) already checks
   * [isActive] first and passes through instead of re-entering, so this should never fire in
   * practice — it exists to fail loudly on any future caller that skips that guard, rather than
   * silently overwriting the active [Scope] and leaking its pushed frame / installed ThreadLocal
   * (neither would ever be torn down, since [exit] only knows about the newest [Scope]).
   */
  fun enter() {
    check(current.get() == null) {
      "ToolBatchScope.enter() called while a scope is already active on thread " +
        "${Thread.currentThread().name} — nest via isActive()-guarded pass-through instead " +
        "(see runInSharedToolBatch); entering again would leak the active scope's pushed " +
        "SnapshotCache frame and installed ToolExecutionContextThreadLocal."
    }
    current.set(Scope(Thread.currentThread()))
  }

  /**
   * Return the scope's shared context, building it via [build] on first call. The first build also
   * installs the context into [ToolExecutionContextThreadLocal] and pushes one [SnapshotCache]
   * frame; both are undone by [exit]. Must be called inside an [enter]/[exit] bracket.
   */
  fun contextOrBuild(build: () -> TrailblazeToolExecutionContext): TrailblazeToolExecutionContext {
    val scope = current.get()
      ?: error("ToolBatchScope.contextOrBuild called with no active scope")
    warnIfThreadHopped(scope, "contextOrBuild")
    scope.context?.let { return it }
    val ctx = build()
    scope.context = ctx
    ToolExecutionContextThreadLocal.install(ctx)
    scope.installedThreadLocal = true
    SnapshotCache.pushFrame()
    scope.pushedFrame = true
    return ctx
  }

  /** Close the scope, undoing exactly what [contextOrBuild] established. Idempotent. */
  fun exit() {
    val scope = current.get() ?: return
    warnIfThreadHopped(scope, "exit")
    if (scope.pushedFrame) SnapshotCache.popFrame()
    if (scope.installedThreadLocal) ToolExecutionContextThreadLocal.clear()
    current.remove()
  }

  /**
   * This is a ThreadLocal primitive (see class kdoc) — it cannot correctly follow a suspend
   * block across a coroutine dispatcher hop. A hop off [Scope.enteredThread] means [exit] will
   * run on a *different* thread than [enter], where `current.get()` reads that thread's own
   * (likely-null) slot — so the original thread's [Scope] silently leaks its pushed frame and
   * installed ThreadLocal, undetectable from the new thread. This check can't recover from
   * that, but it can make the corruption loud instead of a mysterious downstream symptom (e.g. a
   * later batch reading a stale ThreadLocal context on the original thread).
   */
  private fun warnIfThreadHopped(scope: Scope, site: String) {
    val nowThread = Thread.currentThread()
    if (nowThread !== scope.enteredThread) {
      Console.log(
        "[ToolBatchScope] THREAD_HOP at $site — scope entered on thread " +
          "${scope.enteredThread.name} but now running on ${nowThread.name}. The batch's " +
          "execution context is a per-thread primitive; a dispatcher hop inside the batched " +
          "block means teardown will not run on the entering thread and can leak the pushed " +
          "SnapshotCache frame / installed ToolExecutionContextThreadLocal there.",
      )
    }
  }
}
