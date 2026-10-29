package xyz.block.trailblaze.android.test

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StartupWarmupTest {

  @Test
  fun `start returns before the tasks finish, and they run on another thread`() {
    val release = CountDownLatch(1)
    val done = CountDownLatch(1)
    val ranOn = CopyOnWriteArrayList<Thread>()
    val warmup = StartupWarmup(
      tasks = listOf(
        task("slow") {
          ranOn.add(Thread.currentThread())
          release.await(10, TimeUnit.SECONDS)
          done.countDown()
        },
      ),
    )

    warmup.start()

    // The caller is the test thread a harness is building its rules on: it must not wait.
    assertEquals(1L, done.count, "start() must not wait for the work")
    release.countDown()
    assertTrue(done.await(10, TimeUnit.SECONDS))
    assertNotEquals(Thread.currentThread(), ranOn.single())
  }

  @Test
  fun `a failing task is logged and the tasks after it still run`() {
    val ran = CopyOnWriteArrayList<String>()
    val warmup = StartupWarmup(
      tasks = listOf(
        task("first") { ran.add("first") },
        task("broken") { error("asset missing") },
        task("last") { ran.add("last") },
      ),
      startThread = { work: Runnable -> work.run() },
    )

    warmup.start()

    assertEquals(listOf("first", "last"), ran)
  }

  @Test
  fun `a second start does not run the tasks again`() {
    var runs = 0
    val warmup = StartupWarmup(
      tasks = listOf(task("count") { runs += 1 }),
      startThread = { work: Runnable -> work.run() },
    )

    warmup.start()
    warmup.start()

    assertEquals(1, runs)
  }

  private fun task(name: String, block: () -> Unit): Pair<String, () -> Unit> = name to block
}
