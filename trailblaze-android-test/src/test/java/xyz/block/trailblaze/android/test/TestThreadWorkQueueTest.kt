package xyz.block.trailblaze.android.test

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withTimeoutOrNull

class TestThreadWorkQueueTest {

  @Test
  fun `dispatched work runs on the draining thread and returns its value`() {
    val queue = TestThreadWorkQueue()
    val drainThread = Thread { queue.drainUntilStopped() }.apply { start() }
    try {
      val (ranOn, value) = runBlocking { queue.dispatch { Thread.currentThread() to 42 } }
      assertEquals(drainThread, ranOn, "work must execute on the draining thread")
      assertEquals(42, value)
    } finally {
      queue.stop()
      drainThread.join(DRAIN_SHUTDOWN_TIMEOUT_MS)
    }
  }

  @Test
  fun `a throwing block rethrows to the dispatcher and the drain loop survives`() {
    val queue = TestThreadWorkQueue()
    val drainThread = Thread { queue.drainUntilStopped() }.apply { start() }
    try {
      // The throw must land on the DISPATCHER — thrown on the drain thread it would kill the
      // server's whole serve loop, turning one bad trail into a dead instrumentation.
      assertFailsWith<IllegalStateException> {
        runBlocking { queue.dispatch<Unit> { error("boom") } }
      }
      // And the loop must still be serving afterwards.
      assertEquals("alive", runBlocking { queue.dispatch { "alive" } })
    } finally {
      queue.stop()
      drainThread.join(DRAIN_SHUTDOWN_TIMEOUT_MS)
    }
  }

  @Test
  fun `a cancelled dispatch does not run its block, and the loop keeps serving`() {
    val queue = TestThreadWorkQueue()
    val occupied = CountDownLatch(1)
    val release = CountDownLatch(1)
    val ranAfterCancel = AtomicBoolean(false)
    val drainThread = Thread { queue.drainUntilStopped() }.apply { start() }
    try {
      runBlocking {
        // Occupy the drain thread so the second dispatch is still QUEUED when it is cancelled —
        // the exact shape of the handler cancelling on a dropped call or an expired await cap.
        val blocking = launch(start = CoroutineStart.UNDISPATCHED) {
          queue.dispatch {
            occupied.countDown()
            check(release.await(DRAIN_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
              "the occupier was never released"
            }
          }
        }
        // Asserted, not just awaited: if this times out the occupier is no longer holding the
        // drain thread, the "cancelled" dispatch below is no longer QUEUED when it is cancelled,
        // and the test would pass having exercised nothing. A silent false green is worse than
        // the flake — fail here instead.
        assertTrue(
          occupied.await(DRAIN_SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS),
          "the drain thread never picked up the occupying dispatch",
        )
        // UNDISPATCHED runs `dispatch` up to its first suspension — i.e. past the enqueue — so
        // this is queued behind the occupier before it is cancelled, with no race to lose.
        val cancelled = launch(start = CoroutineStart.UNDISPATCHED) {
          queue.dispatch { ranAfterCancel.set(true) }
        }
        cancelled.cancelAndJoin()
        release.countDown()
        blocking.join()
        // Drains past the cancelled entry, which must no-op rather than run its block.
        assertEquals("alive", queue.dispatch { "alive" })
      }
      assertFalse(ranAfterCancel.get(), "a cancelled dispatch's block must never execute")
    } finally {
      release.countDown()
      queue.stop()
      drainThread.join(DRAIN_SHUTDOWN_TIMEOUT_MS)
    }
  }

  @Test
  fun `stop ends the drain loop`() {
    val queue = TestThreadWorkQueue()
    val drainThread = Thread { queue.drainUntilStopped() }.apply { start() }
    queue.stop()
    drainThread.join(DRAIN_SHUTDOWN_TIMEOUT_MS)
    assertFalse(drainThread.isAlive, "drainUntilStopped must return after stop()")
  }

  @Test
  fun `stop fails work still queued instead of stranding its dispatcher`() {
    val queue = TestThreadWorkQueue()
    val ran = AtomicBoolean(false)
    runBlocking {
      // supervisorScope so the async's failure is observed at `await` instead of also cancelling
      // this test's own scope. Production dispatchers throw from `dispatch` directly and carry no
      // such wrapper.
      supervisorScope {
        // No drain thread at all, so this entry can only ever be abandoned — the shape of a server
        // shutting down with a request still queued. Left un-abandoned the dispatcher would
        // suspend forever and the RPC would simply never answer.
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
          queue.dispatch { ran.set(true) }
        }
        queue.stop()
        assertFailsWith<IllegalStateException> { queued.await() }
      }
    }
    assertFalse(ran.get(), "abandoned work must never execute")
  }

  @Test
  fun `dispatching to an already-stopped queue fails instead of suspending forever`() {
    val queue = TestThreadWorkQueue()
    val ran = AtomicBoolean(false)
    queue.stop()
    runBlocking {
      supervisorScope {
        // The enqueue-AFTER-stop ordering, the mirror of `stop fails work still queued`: there the
        // sweep runs after the put, here it ran before one ever arrived. Without dispatch's own
        // re-sweep this entry would sit in the queue with no drain thread coming for it, and the
        // RPC handler awaiting it would never be answered.
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
          queue.dispatch { ran.set(true) }
        }
        // Deliberately NOT `assertFailsWith { withTimeout { ... } }`: that shape passes when the
        // guard is gone, because the timeout's own CancellationException satisfies the assertion
        // as readily as the real one. Verified by mutation — removing the re-sweep left the
        // assertFailsWith version green after burning the full 60s. Separate the two outcomes so
        // "never answered" and "answered with the wrong thing" are distinct failures.
        val outcome = withTimeoutOrNull(DRAIN_SHUTDOWN_TIMEOUT_MS) {
          runCatching { queued.await() }
        }
        queued.cancel()
        assertNotNull(
          outcome,
          "dispatch to a stopped queue never completed — nothing abandoned the queued entry",
        )
        assertIs<IllegalStateException>(
          outcome.exceptionOrNull(),
          "a stopped queue must fail its dispatcher, not return normally",
        )
      }
    }
    assertFalse(ran.get(), "work dispatched to a stopped queue must never execute")
  }

  @Test
  fun `an interrupted drain thread stops the queue instead of stranding its callers`() {
    val queue = TestThreadWorkQueue()
    val ran = AtomicBoolean(false)
    // runCatching so the InterruptedException the loop propagates does not print a stack trace
    // from an uncaught handler. The propagation itself is the contract — see [drainUntilStopped].
    val drainThread = Thread { runCatching { queue.drainUntilStopped() } }.apply { start() }
    // Serve one dispatch first, so the interrupt below lands on a loop that is genuinely polling
    // rather than on a thread that has not reached `poll` yet.
    assertEquals("alive", runBlocking { queue.dispatch { "alive" } })
    drainThread.interrupt()
    drainThread.join(DRAIN_SHUTDOWN_TIMEOUT_MS)
    assertFalse(drainThread.isAlive, "an interrupted drain loop must return rather than spin")

    runBlocking {
      supervisorScope {
        // The case the `finally` exists for: no `stop()` was ever called, so without it `stopped`
        // stays false and this entry waits on a drain thread that has already died.
        val queued = async(start = CoroutineStart.UNDISPATCHED) {
          queue.dispatch { ran.set(true) }
        }
        val outcome = withTimeoutOrNull(DRAIN_SHUTDOWN_TIMEOUT_MS) {
          runCatching { queued.await() }
        }
        queued.cancel()
        assertNotNull(
          outcome,
          "dispatch after the drain thread died never completed — the queue never marked itself stopped",
        )
        assertIs<IllegalStateException>(
          outcome.exceptionOrNull(),
          "a queue whose drain thread is gone must fail its dispatcher, not return normally",
        )
      }
    }
    assertFalse(ran.get(), "work dispatched after the drain thread died must never execute")
  }

  companion object {
    /**
     * Hang containment, not a speed assertion: the drain loop polls on a 250ms interval, so any
     * bound tight enough to measure "was it fast" is a flake on a loaded CI agent.
     */
    private const val DRAIN_SHUTDOWN_TIMEOUT_MS = 60_000L
  }
}
