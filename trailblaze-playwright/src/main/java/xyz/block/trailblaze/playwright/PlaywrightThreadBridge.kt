package xyz.block.trailblaze.playwright

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.runBlocking

/**
 * Pins the rule that bridging onto the Playwright dispatcher thread MUST detect the
 * already-on-thread case and run inline, instead of always going through
 * `runBlocking(playwrightDispatcher)`.
 *
 * The dispatcher is single-threaded (one `ExecutorService` thread backs it). When code
 * already running on that thread calls `runBlocking(playwrightDispatcher) { … }`, the
 * outer thread blocks waiting for the inner coroutine, but the inner coroutine is
 * scheduled on the same thread — which is now blocked. Deadlock. Production hit this
 * via `BasePlaywrightNativeTest` calling `syncRecordingWithRegistry()` from inside a
 * `withContext(playwrightDispatcher)` block, wedging a 1800s timeout on a 30-second
 * trail.
 *
 * Two call sites in `PlaywrightBrowserManager` need this bridge:
 *  - `syncRecordingWithRegistry()` — invoked both from the manager's `init` (off-thread)
 *    and from `runTrailblazeYamlSuspend` (on-thread).
 *  - `close()` — invoked both from JVM shutdown (off-thread) and from coroutine cleanup
 *    that may already be on-thread.
 *
 * Both routes converge on this helper so the deadlock-detection logic exists in exactly
 * one place. The unit test `PlaywrightThreadBridgeTest` pins both branches.
 */
internal object PlaywrightThreadBridge {
  /**
   * Runs [block] on the Playwright dispatcher thread, choosing between two strategies:
   *
   *  - If [playwrightThread] is initialized and the current thread IS it, executes
   *    [block] inline. No `runBlocking` — avoids the self-bridge deadlock.
   *  - Otherwise, executes [block] via `runBlocking([dispatcher])` to bridge from the
   *    caller's thread onto the Playwright thread.
   *
   * @param currentThread The thread the caller is on. Injected so tests can drive both
   *   branches without actually spinning up Playwright.
   * @param playwrightThread The dispatcher's pinned thread. Pass `null` when the
   *   dispatcher hasn't been initialized yet (early in manager construction) — the
   *   bridge will fall through to the dispatch path.
   */
  fun runOnPlaywrightThread(
    currentThread: Thread,
    playwrightThread: Thread?,
    dispatcher: CoroutineDispatcher,
    block: () -> Unit,
  ) {
    if (playwrightThread != null && currentThread === playwrightThread) {
      block()
    } else {
      runBlocking(dispatcher) { block() }
    }
  }
}
