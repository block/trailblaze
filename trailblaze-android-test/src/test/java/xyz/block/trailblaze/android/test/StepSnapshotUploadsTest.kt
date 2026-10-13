package xyz.block.trailblaze.android.test

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class StepSnapshotUploadsTest {

  @Test
  fun `the next tool does not wait for the previous snapshot's upload`() {
    val uploads = StepSnapshotUploads()
    val release = CountDownLatch(1)
    val ranOn = CopyOnWriteArrayList<Thread>()

    uploads.submit {
      ranOn.add(Thread.currentThread())
      release.await(10, TimeUnit.SECONDS)
    }

    // submit() returned while the upload is still held: the caller (the test thread) moved on.
    assertEquals(1L, release.count)
    release.countDown()
    uploads.drain()
    assertNotEquals(Thread.currentThread(), ranOn.single())
  }

  @Test
  fun `snapshots are logged in the order they were captured`() {
    val uploads = StepSnapshotUploads()
    val logged = CopyOnWriteArrayList<Int>()

    // Earlier uploads are slower, so any concurrency would reorder them.
    for (i in 1..3) uploads.submit {
      Thread.sleep((4 - i) * 50L)
      logged.add(i)
    }
    uploads.drain()

    assertEquals(listOf(1, 2, 3), logged)
  }

  @Test
  fun `drain returns only after every queued snapshot is logged`() {
    val uploads = StepSnapshotUploads()
    val logged = CopyOnWriteArrayList<Int>()

    uploads.submit { Thread.sleep(200); logged.add(1) }
    uploads.submit { Thread.sleep(200); logged.add(2) }
    uploads.drain()

    assertEquals(listOf(1, 2), logged)
  }

  @Test
  fun `a capture waits for the oldest snapshot once too many are queued`() {
    val uploads = StepSnapshotUploads(maxPending = 1)
    val release = CountDownLatch(1)
    uploads.submit { release.await(10, TimeUnit.SECONDS) }

    val secondQueued = CountDownLatch(1)
    thread {
      uploads.submit {}
      secondQueued.countDown()
    }

    assertFalse(secondQueued.await(300, TimeUnit.MILLISECONDS), "second capture must wait")
    release.countDown()
    assertTrue(secondQueued.await(10, TimeUnit.SECONDS))
    uploads.drain()
  }

  @Test
  fun `a failed upload does not stop the snapshots after it`() {
    val uploads = StepSnapshotUploads()
    val logged = CopyOnWriteArrayList<Int>()

    uploads.submit { error("log server refused the upload") }
    uploads.submit { logged.add(2) }
    uploads.drain()

    assertEquals(listOf(2), logged)
  }

  @Test
  fun `drain gives up on an upload that never finishes`() {
    val uploads = StepSnapshotUploads(drainTimeoutMs = 200)
    val never = CountDownLatch(1)
    uploads.submit { never.await(60, TimeUnit.SECONDS) }

    val startedNs = System.nanoTime()
    uploads.drain()
    val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNs)

    assertTrue(tookMs < 5_000, "drain took ${tookMs}ms")
    never.countDown()
  }

  @Test
  fun `a capture is dropped rather than queued while the oldest upload stays stalled`() {
    val uploads = StepSnapshotUploads(maxPending = 1, drainTimeoutMs = 200)
    val release = CountDownLatch(1)
    val logged = CopyOnWriteArrayList<Int>()
    uploads.submit { release.await(10, TimeUnit.SECONDS) }

    uploads.submit { logged.add(2) }
    release.countDown()
    uploads.drain()

    assertEquals(emptyList(), logged)
  }

  @Test
  fun `snapshots after a drain that gave up still upload`() {
    val uploads = StepSnapshotUploads(drainTimeoutMs = 200)
    val release = CountDownLatch(1)
    val logged = CopyOnWriteArrayList<Int>()
    // Ignores interrupts, like an upload stuck in a blocking socket write.
    uploads.submit {
      while (release.count > 0) {
        try {
          release.await(10, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
        }
      }
    }
    uploads.drain()

    uploads.submit { logged.add(1) }
    uploads.drain()
    release.countDown()

    assertEquals(listOf(1), logged)
  }
}
