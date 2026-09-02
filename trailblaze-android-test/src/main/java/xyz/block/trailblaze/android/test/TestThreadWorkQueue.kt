package xyz.block.trailblaze.android.test

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext

/**
 * Marshals work from arbitrary threads onto the one thread that calls [drainUntilStopped] — for
 * the in-process ANDROID_TEST driver, the JUnit instrumentation thread of a standalone RPC server
 * test.
 *
 * Why this exists: Espresso interactions must be issued from the instrumentation thread, but the
 * on-device RPC server handles requests on Ktor worker threads. The accessibility runner sidesteps
 * this because UiAutomation is thread-agnostic; this driver cannot. So the server's `@Test` body
 * parks in [drainUntilStopped] and every trail-running request crosses over via [dispatch].
 *
 * One queue, drained synchronously — dispatched work is strictly serialized, which is also the
 * on-device RPC contract (one run at a time).
 */
class TestThreadWorkQueue {

  /**
   * [abandon] is why an entry is a pair rather than a bare lambda: [stop] has to reach the waiter
   * of work it will never run, and a closure that has captured its own deferred cannot be asked
   * to fail it from outside.
   */
  private class Entry(val run: () -> Unit, val abandon: () -> Unit)

  private val queue = LinkedBlockingQueue<Entry>()

  @Volatile
  private var stopped = false

  /**
   * Runs [block] on the draining thread and suspends until it completes, rethrowing anything it
   * threw. Callable from any thread — including a Ktor request handler mid-`suspend`.
   *
   * Cancelling the caller cancels the dispatch: the RPC handler cancels this when the HTTP call
   * is dropped or its await cap expires, and a block that has not started yet must not run
   * afterwards — it would drive the UI for a request nobody is reading and hold the next request
   * behind it on this one serialized queue. Parenting the deferred to the caller's [Job] is what
   * makes that cancellation observable here; the queued entry then no-ops. A block already
   * executing still runs to completion — an in-flight Espresso interaction is not interruptible.
   *
   * Dispatching to a stopped queue fails rather than suspending forever; see [stop].
   */
  suspend fun <T> dispatch(block: () -> T): T {
    val deferred = CompletableDeferred<Result<T>>(parent = currentCoroutineContext()[Job])
    queue.put(
      Entry(
        run = { if (deferred.isActive) deferred.complete(runCatching(block)) },
        // Completed with a failed Result, NOT completeExceptionally: the deferred is parented to
        // the caller's Job, so failing it would cancel that Job and take the caller's whole scope
        // down with it. Carrying the failure inside the value lets `getOrThrow` raise it on the
        // dispatching coroutine alone — the same reason `run` wraps the block in `runCatching`.
        abandon = { deferred.complete(Result.failure(stoppedException())) },
      ),
    )
    // Ordering, not belt-and-braces: a stop that swept the queue before this put would leave the
    // entry with nobody to run it. Re-sweeping here is idempotent — abandoning a deferred that
    // already completed is a no-op — and closes that window from the enqueuing side.
    if (stopped) abandonQueuedWork()
    return deferred.await().getOrThrow()
  }

  /**
   * Blocks the calling thread, executing dispatched work in arrival order until [stop]. The
   * per-iteration timeout is only how often the stop flag is polled; it does not bound how long a
   * dispatched block may run.
   *
   * Every exit marks the queue stopped and fails what is still on it, because the drain thread
   * leaving is what makes the queue unservable — not [stop] being the thing that ended it.
   * `poll` throws `InterruptedException` when the instrumentation thread is torn down (a JUnit
   * timeout rule, the runner killing the test), and on that exit `stopped` would otherwise stay
   * false: queued waiters keep waiting, and a LATER [dispatch] skips its re-sweep and joins them,
   * all suspended on a thread that is never coming back. The interrupt itself is left to
   * propagate — the server's `@Test` should fail when its serve loop is killed, not return as
   * though it had been asked to shut down.
   */
  fun drainUntilStopped() {
    try {
      while (!stopped) {
        queue.poll(250, TimeUnit.MILLISECONDS)?.run?.invoke()
      }
    } finally {
      stopped = true
      abandonQueuedWork()
    }
  }

  /**
   * Ends [drainUntilStopped] after at most one more poll interval.
   *
   * Work still queued is not run — the thread that would run it is leaving — but each waiter is
   * failed rather than left suspended, so a shutdown mid-request surfaces as an error on that
   * request instead of an RPC that never answers.
   *
   * No production caller yet, deliberately: the RPC protocol has no shutdown request, so the
   * server's `@Test` parks in [drainUntilStopped] until the instrumentation process is killed.
   * This exists so that the graceful path is already correct when that request lands — and so the
   * abandon semantics above are pinned by tests rather than discovered later.
   */
  fun stop() {
    stopped = true
    abandonQueuedWork()
  }

  /** Fails every queued waiter. Idempotent: abandoning an already-completed deferred is a no-op. */
  private fun abandonQueuedWork() {
    while (true) {
      (queue.poll() ?: return).abandon()
    }
  }

  private fun stoppedException() =
    IllegalStateException("TestThreadWorkQueue stopped before this work ran")
}
