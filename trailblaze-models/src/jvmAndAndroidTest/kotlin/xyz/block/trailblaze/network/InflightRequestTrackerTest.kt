package xyz.block.trailblaze.network

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the contract of [InflightRequestTracker]. Engines depend on these
 * invariants:
 * - [InflightRequestTracker.isIdle] flips false when any request is in flight.
 * - It returns true the moment all in-flight ids have been ended.
 * - Concurrent start/end from many threads doesn't lose ids or wedge state.
 */
class InflightRequestTrackerTest {

  @Test
  fun `isIdle returns true on a fresh tracker`() {
    val tracker = InflightRequestTracker()
    assertTrue(tracker.isIdle())
    assertEquals(0, tracker.inflightCount())
  }

  @Test
  fun `isIdle is false while a request is in flight and true after end`() {
    val tracker = InflightRequestTracker()
    tracker.onRequestStart("req-1", "https://api.example.com/a")
    assertFalse(tracker.isIdle())
    assertEquals(1, tracker.inflightCount())

    tracker.onRequestEnd("req-1")
    assertTrue(tracker.isIdle())
    assertEquals(0, tracker.inflightCount())
  }

  @Test
  fun `unknown end ids are no-ops`() {
    val tracker = InflightRequestTracker()
    // Engines should be allowed to call onRequestEnd defensively without
    // coordinating with start; the tracker absorbs the noise.
    tracker.onRequestEnd("never-started")
    assertTrue(tracker.isIdle())
  }

  @Test
  fun `quietPeriod requires actual wall-clock quiet`() {
    val tracker = InflightRequestTracker()
    tracker.onRequestStart("req-1", "https://api.example.com/a")
    tracker.onRequestEnd("req-1")
    // Just-ended — quietPeriod=0 is satisfied immediately, but a positive
    // window won't be.
    assertTrue(tracker.isIdle(quietPeriodMs = 0L))
    assertFalse(tracker.isIdle(quietPeriodMs = 10_000L))
  }

  @Test
  fun `inflightMatching returns ids of urls matching the regex snapshot`() {
    val tracker = InflightRequestTracker()
    tracker.onRequestStart("req-1", "https://api.example.com/cdp/track")
    tracker.onRequestStart("req-2", "https://cdn.example.com/asset.png")
    tracker.onRequestStart("req-3", "https://api.example.com/metron/event")

    val analytics = tracker.inflightMatching(Regex("(cdp|metron)"))
    assertEquals(setOf("req-1", "req-3"), analytics.toSet())

    val cdn = tracker.inflightMatching(Regex("cdn\\."))
    assertEquals(listOf("req-2"), cdn)
  }

  @Test
  fun `reset clears state so a recycled tracker doesn't pin idle false`() {
    val tracker = InflightRequestTracker()
    tracker.onRequestStart("req-1", "https://api.example.com/a")
    tracker.reset()
    assertTrue(tracker.isIdle())
    assertEquals(0, tracker.inflightCount())
  }

  @Test
  fun `concurrent start and end leaves the map empty`() {
    // Stress test: 4 producer threads each open + close 5_000 ids. After all
    // threads complete, the tracker must be idle and inflightCount must be 0.
    // If the underlying map weren't thread-safe, IDs would race-leak in.
    val tracker = InflightRequestTracker()
    val threadCount = 4
    val perThread = 5_000
    val executor = Executors.newFixedThreadPool(threadCount)
    val started = CountDownLatch(1)
    val done = CountDownLatch(threadCount)
    repeat(threadCount) { tIdx ->
      executor.submit {
        try {
          started.await()
          for (i in 0 until perThread) {
            val id = "t$tIdx-i$i"
            tracker.onRequestStart(id, "https://example.com/$id")
            tracker.onRequestEnd(id)
          }
        } finally {
          done.countDown()
        }
      }
    }
    started.countDown()
    val finished = done.await(30, TimeUnit.SECONDS)
    executor.shutdown()
    assertTrue(finished, "Concurrent producers did not finish within 30s")
    assertTrue(tracker.isIdle())
    assertEquals(0, tracker.inflightCount())
  }

  @Test
  fun `concurrent producers leave inflight set in expected size when ends are skipped`() {
    // Producers run start without matching end; we then verify the map sees
    // every id (no dropped writes from a non-thread-safe map).
    val tracker = InflightRequestTracker()
    val threadCount = 4
    val perThread = 1_000
    val executor = Executors.newFixedThreadPool(threadCount)
    val done = CountDownLatch(threadCount)
    repeat(threadCount) { tIdx ->
      executor.submit {
        try {
          for (i in 0 until perThread) {
            tracker.onRequestStart("t$tIdx-i$i", "https://example.com/$tIdx/$i")
          }
        } finally {
          done.countDown()
        }
      }
    }
    val finished = done.await(30, TimeUnit.SECONDS)
    executor.shutdown()
    assertTrue(finished)
    assertEquals(threadCount * perThread, tracker.inflightCount())
    assertFalse(tracker.isIdle())
    tracker.reset()
    assertTrue(tracker.isIdle())
  }
}
