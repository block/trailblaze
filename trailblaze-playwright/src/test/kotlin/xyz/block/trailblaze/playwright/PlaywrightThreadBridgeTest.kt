package xyz.block.trailblaze.playwright

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.asCoroutineDispatcher

/**
 * Pins the deadlock-prevention logic in [PlaywrightThreadBridge]. The fix matters
 * because the production deadlock that motivated it (PR #3077 history) wedged a 1800s
 * timeout on a 30-second trail and was only diagnosable via thread dump — a future
 * refactor of `PlaywrightBrowserManager`'s threading model could silently regress it,
 * and a regression looks like "trail hangs," which most callers attribute to flakes.
 *
 * The tests intentionally don't construct a real `PlaywrightBrowserManager` — that
 * requires a real Playwright + Chromium boot. By extracting the on-thread-vs-off-thread
 * decision into [PlaywrightThreadBridge], we can verify both branches with stock JDK
 * concurrency primitives.
 */
class PlaywrightThreadBridgeTest {

  // Single-threaded executor that mirrors `PlaywrightBrowserManager.playwrightExecutor`:
  // one Thread, captured as the "playwright thread" the bridge compares against.
  private val executor = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "test-playwright-thread").apply { isDaemon = true }
  }
  private val dispatcher = executor.asCoroutineDispatcher()

  // Capture the executor's thread the same way `PlaywrightBrowserManager.init` does:
  // by submitting a task that records `Thread.currentThread()`.
  private val playwrightThread: Thread = executor.submit<Thread> { Thread.currentThread() }.get()

  @AfterTest
  fun tearDown() {
    executor.shutdownNow()
    executor.awaitTermination(2, TimeUnit.SECONDS)
  }

  @Test
  fun `on-thread case runs block inline without runBlocking`() {
    // When the caller is already the playwright thread, the bridge must NOT run a
    // `runBlocking(dispatcher)` — that would deadlock (dispatcher is single-threaded
    // and the only thread is blocked waiting on the new coroutine to run on it).
    //
    // Strategy: submit the bridge call onto the executor (so we ARE the playwright
    // thread), inside the block capture the running thread, and verify it's the same
    // thread (proving inline execution). If the bridge had used `runBlocking`, the
    // submit task would never complete and `get(5s)` would time out.
    val blockRanOn = AtomicReference<Thread>()
    val callCount = AtomicInteger(0)
    val future = executor.submit {
      PlaywrightThreadBridge.runOnPlaywrightThread(
        currentThread = Thread.currentThread(),
        playwrightThread = playwrightThread,
        dispatcher = dispatcher,
      ) {
        blockRanOn.set(Thread.currentThread())
        callCount.incrementAndGet()
      }
    }
    // 2s is well under the production deadlock symptom (1800s) but enough to absorb
    // CI scheduling jitter; locally the bridge completes in microseconds.
    future.get(2, TimeUnit.SECONDS)
    assertSame(playwrightThread, blockRanOn.get(), "block must run on the playwright thread")
    assertEquals(1, callCount.get(), "block must run exactly once")
  }

  @Test
  fun `off-thread case dispatches the block onto the playwright thread`() {
    // When the caller is NOT the playwright thread, the bridge uses
    // `runBlocking(dispatcher)` to hop the block onto the playwright thread. Verify
    // the block ran on the dispatcher's thread (NOT the test's calling thread) and
    // that `runOnPlaywrightThread` blocked the caller until it completed.
    val blockRanOn = AtomicReference<Thread>()
    val blockStarted = CountDownLatch(1)
    val callerThread = Thread.currentThread()
    PlaywrightThreadBridge.runOnPlaywrightThread(
      currentThread = Thread.currentThread(),
      playwrightThread = playwrightThread,
      dispatcher = dispatcher,
    ) {
      blockRanOn.set(Thread.currentThread())
      blockStarted.countDown()
    }
    assertTrue(blockStarted.count == 0L, "block must have completed before bridge returned")
    assertSame(playwrightThread, blockRanOn.get(), "block must run on the playwright thread, not caller")
    assertTrue(blockRanOn.get() !== callerThread, "block must NOT run on the caller thread")
  }

  @Test
  fun `null playwright thread falls through to dispatcher (pre-init safety)`() {
    // During `PlaywrightBrowserManager` construction the `playwrightThread` lateinit
    // is only assigned inside the executor's first task, so very-early calls may pass
    // null. The bridge should fall through to the dispatch path rather than crash on
    // a null comparison. (The thread we end up on is the dispatcher's, which is the
    // safe answer — we'd rather over-bridge once at startup than deadlock.)
    val blockRanOn = AtomicReference<Thread>()
    PlaywrightThreadBridge.runOnPlaywrightThread(
      currentThread = Thread.currentThread(),
      playwrightThread = null,
      dispatcher = dispatcher,
    ) {
      blockRanOn.set(Thread.currentThread())
    }
    assertSame(playwrightThread, blockRanOn.get(), "block dispatched onto playwright thread")
  }
}
