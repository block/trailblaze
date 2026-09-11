package xyz.block.trailblaze.device

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [readWithDeadline]'s contract. Every wall-clock number here is passed *into* the function
 * under test as its own budget, so nothing depends on how fast the machine running the test is.
 */
class BoundedReadTest {

  private companion object {
    /** Only there so a never-cancelled read fails rather than parking the suite forever. */
    const val ESCAPE_HATCH_MS = 60_000L
  }

  /**
   * A read that parks until [release] is counted down, the way a blocking pipe read does.
   *
   * The park is itself bounded, generously, so that a regression in which the deadline never
   * cancels fails this test instead of hanging the whole suite on a thread nothing will ever wake.
   * [ESCAPE_HATCH_MS] is not a budget any assertion depends on — every test here gives
   * `readWithDeadline` a much smaller one — it only guarantees the suite terminates.
   */
  private class BlockingRead {
    val release = CountDownLatch(1)
    val cancelCount = AtomicInteger(0)

    fun cancel() {
      cancelCount.incrementAndGet()
      release.countDown()
    }

    /** Mimics a stream whose close made the in-flight read fail. */
    fun read(): String {
      if (!release.await(ESCAPE_HATCH_MS, TimeUnit.MILLISECONDS)) {
        throw AssertionError("readWithDeadline never cancelled the read within ${ESCAPE_HATCH_MS}ms")
      }
      throw IOException("stream closed")
    }

    /**
     * [read] for the one test that needs the reader's interrupt flag to survive the read, the way
     * a blocking pipe read leaves it. Spins rather than waiting on the latch because every
     * `java.util.concurrent` wait consumes the interrupt and throws, which would end the read
     * before the deadline that test is about.
     */
    fun readIgnoringInterrupts(): String {
      val giveUpAt = System.nanoTime() + ESCAPE_HATCH_MS * 1_000_000
      while (release.count > 0L) {
        if (System.nanoTime() > giveUpAt) {
          throw AssertionError("readWithDeadline never cancelled the read within ${ESCAPE_HATCH_MS}ms")
        }
        // `yield`, not `onSpinWait`: this source set also runs on device, where `onSpinWait`
        // only exists from API 33. Neither one clears the interrupt flag, which is the point.
        Thread.yield()
      }
      throw IOException("stream closed")
    }
  }

  @Test
  fun `a read that never finishes is reported as a timeout naming the work`() {
    val blocking = BlockingRead()

    val failure = assertFailsWith<BoundedReadTimeoutException> {
      readWithDeadline(
        description = "pm clear com.example.app",
        timeoutMs = 50,
        cancel = blocking::cancel,
        read = blocking::read,
      )
    }

    assertEquals("pm clear com.example.app", failure.description)
    assertEquals(50, failure.timeoutMs)
    assertTrue(
      failure.message!!.contains("pm clear com.example.app"),
      "timeout message must name the work that hung, was: ${failure.message}",
    )
  }

  @Test
  fun `the blocked read is unblocked rather than abandoned`() {
    val blocking = BlockingRead()

    assertFailsWith<BoundedReadTimeoutException> {
      readWithDeadline("hung", timeoutMs = 50, cancel = blocking::cancel, read = blocking::read)
    }

    // The point of cancel: the reader must have come back. A caller that merely gave up would
    // leave this thread parked forever, still holding whatever lock it took.
    assertEquals(0, blocking.release.count, "read was left blocked after the deadline")
    assertEquals(1, blocking.cancelCount.get(), "cancel must run exactly once")
  }

  @Test
  fun `a read that finishes in time returns its value and never cancels`() {
    val cancelled = AtomicBoolean(false)

    val result = readWithDeadline(
      description = "getprop ro.build.version.sdk",
      timeoutMs = 60_000,
      cancel = { cancelled.set(true) },
      read = { "29" },
    )

    assertEquals("29", result)
    assertTrue(!cancelled.get(), "a read that finished must not be cancelled")
  }

  @Test
  fun `the deadline thread does not outlive a fast read`() {
    // The other tests in this class deliberately let the deadline WIN, and that path reports the
    // timeout while its cancel is still finishing — so a thread of theirs can still be winding
    // down when this test starts, and these threads are only distinguishable by name. Wait that
    // out first; JUnit's method order is not something to depend on. No assertion below is
    // affected by how long this takes.
    awaitNoDeadlineThreads()

    repeat(50) { i ->
      assertEquals(i, readWithDeadline("fast", timeoutMs = 600_000, cancel = {}, read = { i }))
    }

    // A per-call thread parked on a 10 minute sleep is a leak that only shows up as an exhausted
    // machine much later, so assert the threads are gone rather than that nothing went wrong.
    //
    // Checked immediately, with no grace period, on purpose: a read that wins the race joins its
    // deadline thread before returning, so by the time the last call above came back every thread
    // it started has already terminated. If this ever needs a wait to pass, that join has been
    // lost — and the assertion should say so rather than paper over it.
    assertEquals(emptyList(), deadlineThreads(), "deadline threads leaked after the reads returned")
  }

  private fun awaitNoDeadlineThreads() {
    val giveUpAt = System.currentTimeMillis() + ESCAPE_HATCH_MS
    while (deadlineThreads().isNotEmpty() && System.currentTimeMillis() < giveUpAt) {
      Thread.sleep(10)
    }
  }

  /**
   * Live deadline threads, as names rather than [Thread]s so a failure prints a readable count
   * instead of `Thread[#42,...]` identities.
   */
  private fun deadlineThreads(): List<String> =
    Thread.getAllStackTraces().keys
      .filter { it.name == "trailblaze-bounded-read" && it.isAlive }
      .map { it.name }

  @Test
  fun `a failure of the read itself propagates unchanged`() {
    val boom = IllegalStateException("device disconnected")

    val thrown = assertFailsWith<IllegalStateException> {
      readWithDeadline("dumpsys window", timeoutMs = 60_000, cancel = {}, read = { throw boom })
    }

    // Wrapping a real error in a timeout would hide the actual reason behind a made-up one.
    assertSame(boom, thrown)
  }

  @Test
  fun `a timeout keeps the underlying failure as its cause`() {
    val blocking = BlockingRead()

    val failure = assertFailsWith<BoundedReadTimeoutException> {
      readWithDeadline("hung", timeoutMs = 50, cancel = blocking::cancel, read = blocking::read)
    }

    assertTrue(failure.cause is IOException, "expected the cancelled read's failure, was ${failure.cause}")
  }

  @Test
  fun `cancel still finishes before the timeout when the caller is already interrupted`() {
    // The real reader arrives here interrupted: the tool bounding the whole dispatch interrupts it
    // on the way out, and the pipe read carries on regardless — which is the entire reason this
    // helper cancels by closing the stream. A `join` on an already-interrupted thread throws
    // instead of waiting, so this is the case where the wait is most likely to be skipped, and the
    // one where skipping it matters: recovery goes on to close the same stream `cancel` is holding.
    val cancelFinished = AtomicBoolean(false)
    val blocking = BlockingRead()

    Thread.currentThread().interrupt()
    val failure = try {
      assertFailsWith<BoundedReadTimeoutException> {
        readWithDeadline(
          description = "hung",
          timeoutMs = 50,
          cancel = {
            blocking.cancel()
            Thread.sleep(200)
            cancelFinished.set(true)
          },
          // Not `BlockingRead.read`: that one waits on a latch, which *is* interruptible, so it
          // would throw before the deadline ever fired and this test would never reach the join it
          // is about. A real pipe read ignores the interrupt and leaves the flag set, so this one
          // does too.
          read = blocking::readIgnoringInterrupts,
        )
      }
    } finally {
      // Clear it however this ends, so a failure here cannot leak an interrupt into later tests.
      val stillInterrupted = Thread.interrupted()
      assertTrue(stillInterrupted, "the caller's interrupt was swallowed rather than restored")
    }

    assertEquals(50, failure.timeoutMs)
    assertTrue(cancelFinished.get(), "cancel was still running when the timeout was reported")
  }

  @Test
  fun `cancel has finished before the timeout is reported`() {
    val cancelFinished = AtomicBoolean(false)
    val blocking = BlockingRead()

    assertFailsWith<BoundedReadTimeoutException> {
      readWithDeadline(
        description = "hung",
        timeoutMs = 50,
        cancel = {
          // Release the read first, then keep working — so a caller that regained control the
          // moment the read came back would observe this cancel mid-flight.
          blocking.cancel()
          Thread.sleep(200)
          cancelFinished.set(true)
        },
        read = blocking::read,
      )
    }

    // Deliberately not polled. A caller that catches the timeout and reuses the resource must not
    // be racing a cancel that is still touching it.
    assertTrue(cancelFinished.get(), "cancel was still running when the timeout was reported")
  }

  @Test
  fun `a winning cancel is not interrupted when the read returns instead of failing`() {
    val released = CountDownLatch(1)
    val cancelFinished = AtomicBoolean(false)
    val cancelInterrupted = AtomicBoolean(false)

    assertFailsWith<BoundedReadTimeoutException> {
      readWithDeadline(
        description = "hung",
        timeoutMs = 50,
        cancel = {
          released.countDown()
          try {
            // Stands in for the interruptible part of a real close, after the read is already free.
            Thread.sleep(200)
            cancelFinished.set(true)
          } catch (interrupted: InterruptedException) {
            cancelInterrupted.set(true)
          }
        },
        // Returns a value rather than failing, which is the path that used to interrupt the winning
        // cancel: the reader would signal the deadline thread on its way out and only then wait for
        // it, so the wait finished on a cancel that had been aborted half-done.
        read = {
          released.await(ESCAPE_HATCH_MS, TimeUnit.MILLISECONDS)
          "half a dumpsys"
        },
      )
    }

    assertTrue(!cancelInterrupted.get(), "the winning cancel was interrupted mid-cleanup")
    assertTrue(cancelFinished.get(), "cancel had not finished when the timeout was reported")
  }

  @Test
  fun `a non-positive budget is rejected instead of never expiring`() {
    // Zero would mean `Thread.sleep(0)` — an immediate cancel — and a negative value would throw
    // from inside the deadline thread, where nothing observes it, leaving the read unbounded.
    assertFailsWith<IllegalArgumentException> {
      readWithDeadline("x", timeoutMs = 0, cancel = {}, read = { "" })
    }
    assertFailsWith<IllegalArgumentException> {
      readWithDeadline("x", timeoutMs = -1, cancel = {}, read = { "" })
    }
  }

  @Test
  fun `a value the read returns after being cancelled is rejected, not returned`() {
    val released = CountDownLatch(1)

    val failure = assertFailsWith<BoundedReadTimeoutException> {
      readWithDeadline(
        description = "dumpsys activity activities",
        timeoutMs = 50,
        cancel = { released.countDown() },
        read = {
          released.await(ESCAPE_HATCH_MS, TimeUnit.MILLISECONDS)
          "half a dumpsys"
        },
      )
    }

    // The whole hazard of cancelling by closing the stream: the read can come back with whatever
    // had already arrived instead of failing, and a truncated shell output is indistinguishable
    // from a complete one to every caller. So a late value is a timeout, not a result.
    assertEquals("dumpsys activity activities", failure.description)
    assertNull(failure.cause, "nothing failed here — the read returned, late")
  }

  @Test
  fun `cancel throwing does not mask the timeout`() {
    val released = CountDownLatch(1)

    val failure = assertFailsWith<BoundedReadTimeoutException> {
      readWithDeadline(
        description = "hung",
        timeoutMs = 50,
        cancel = {
          released.countDown()
          throw IOException("close failed")
        },
        read = {
          released.await(ESCAPE_HATCH_MS, TimeUnit.MILLISECONDS)
          "partial"
        },
      )
    }

    // A cancel that blew up must not turn into the reported reason: the caller needs to know the
    // command hung, and `close failed` on the way out says nothing about that.
    assertEquals(50, failure.timeoutMs)
    assertNull(failure.cause)
  }

  @Test
  fun `a read that returns its value is never also cancelled`() {
    // The read finishes on its OWN after about as long as the budget it was given, so the two
    // finish together and many of these iterations land in the window that matters: the deadline
    // thread's sleep has already expired — making `interrupt()` a no-op — while the read is
    // returning a complete value. That is where an interrupt-only implementation cancels a read
    // that had already finished, closing a resource the caller is about to be handed back.
    //
    // A read that beats the clock and a read that misses it are both correct outcomes here and
    // which one happens is genuinely unpredictable, so the assertion is the invariant that spans
    // them rather than a winner.
    val budgetMs = 5L
    var returned = 0
    var timedOut = 0
    repeat(400) {
      val cancels = AtomicInteger(0)
      val result = runCatching {
        readWithDeadline(
          description = "racing",
          timeoutMs = budgetMs,
          cancel = { cancels.incrementAndGet() },
          read = {
            Thread.sleep(budgetMs)
            "done"
          },
        )
      }
      if (result.isSuccess) {
        returned++
        assertEquals("done", result.getOrNull())
        assertEquals(0, cancels.get(), "a read that returned its value was cancelled anyway")
      } else {
        timedOut++
        assertTrue(
          result.exceptionOrNull() is BoundedReadTimeoutException,
          "expected a timeout, was ${result.exceptionOrNull()}",
        )
      }
    }
    assertEquals(400, returned + timedOut)
  }
}
