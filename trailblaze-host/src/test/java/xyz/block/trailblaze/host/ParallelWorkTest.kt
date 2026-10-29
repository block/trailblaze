package xyz.block.trailblaze.host

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ParallelWorkTest {

  @Test
  fun `results and onResult calls keep input order even when later items finish first`() {
    val delivered = mutableListOf<Int>()
    // Earlier items sleep longer, so completion order is the reverse of input order.
    val results = ParallelWork.map(
      items = listOf(1, 2, 3, 4),
      parallelism = 4,
      onResult = { item, _: String -> delivered += item },
    ) { item ->
      Thread.sleep((5 - item) * 40L)
      "r$item"
    }
    assertEquals(listOf("r1", "r2", "r3", "r4"), results)
    assertEquals(listOf(1, 2, 3, 4), delivered)
  }

  @Test
  fun `items actually run at the same time`() {
    // Each item waits for both to have started: a serial run would never get past the first.
    val bothStarted = CountDownLatch(2)
    val results = ParallelWork.map(items = listOf("a", "b"), parallelism = 2) { item ->
      bothStarted.countDown()
      bothStarted.await(10, TimeUnit.SECONDS)
    }
    assertEquals(listOf(true, true), results)
  }

  @Test
  fun `never runs more items at once than the parallelism allows`() {
    val active = AtomicInteger()
    val peak = AtomicInteger()
    ParallelWork.map(items = (1..12).toList(), parallelism = 3) {
      val now = active.incrementAndGet()
      peak.accumulateAndGet(now, ::maxOf)
      Thread.sleep(20)
      active.decrementAndGet()
    }
    assertTrue(peak.get() in 2..3, "peak concurrency was ${peak.get()}")
  }

  @Test
  fun `an exception from work is rethrown as itself after earlier results are delivered`() {
    val delivered = mutableListOf<Int>()
    val thrown = assertFailsWith<IllegalStateException> {
      ParallelWork.map(
        items = listOf(1, 2, 3),
        parallelism = 3,
        onResult = { item, _: Int -> delivered += item },
      ) { item ->
        check(item != 2) { "boom on $item" }
        item
      }
    }
    assertEquals("boom on 2", thrown.message)
    assertEquals(listOf(1), delivered)
  }
}
