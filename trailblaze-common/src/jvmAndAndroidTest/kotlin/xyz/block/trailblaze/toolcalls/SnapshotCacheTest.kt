package xyz.block.trailblaze.toolcalls

import xyz.block.trailblaze.api.AnnotationElement
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.api.ViewHierarchyTreeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class SnapshotCacheTest {

  /** Always reset the stack between cases so a failing test doesn't leak frames. */
  @AfterTest
  fun cleanup() {
    repeat(SnapshotCache.frameDepth()) { SnapshotCache.popFrame() }
  }

  // -- Fake ScreenState --
  //
  // Doesn't matter what the fields are; identity comparison is what every assertion below
  // turns on. Keep the implementation tiny — the test cares about caching behavior, not
  // about what's inside a snapshot.
  private class FakeScreenState : ScreenState {
    override val screenshotBytes: ByteArray? = null
    override val deviceWidth: Int = 1080
    override val deviceHeight: Int = 1920
    override val viewHierarchy: ViewHierarchyTreeNode = ViewHierarchyTreeNode()
    override val trailblazeDevicePlatform: TrailblazeDevicePlatform =
      TrailblazeDevicePlatform.ANDROID
    override val deviceClassifiers: List<TrailblazeDeviceClassifier> = emptyList()
    override val trailblazeNodeTree: TrailblazeNode? = null
    override val annotationElements: List<AnnotationElement>? = null
  }

  private class CountingProvider {
    var calls = 0
      private set
    private val state = FakeScreenState()
    val provider: () -> ScreenState = {
      calls++
      state
    }
  }

  @Test
  fun `snapshot captures lazily once per frame`() {
    val counting = CountingProvider()

    SnapshotCache.withFrame {
      val first = SnapshotCache.snapshot(counting.provider)
      val second = SnapshotCache.snapshot(counting.provider)
      val third = SnapshotCache.snapshot(counting.provider)

      assertSame(first, second)
      assertSame(second, third)
      assertEquals(1, counting.calls)
    }
  }

  @Test
  fun `invalidateCurrent forces re-capture on next snapshot`() {
    val counting = CountingProvider()

    SnapshotCache.withFrame {
      SnapshotCache.snapshot(counting.provider)
      assertEquals(1, counting.calls)

      SnapshotCache.invalidateCurrent()

      SnapshotCache.snapshot(counting.provider)
      assertEquals(2, counting.calls)
    }
  }

  @Test
  fun `nested frame captures independently and parent slot is unaffected`() {
    val parentProvider = CountingProvider()
    val childProvider = CountingProvider()

    SnapshotCache.withFrame {
      val parentBefore = SnapshotCache.snapshot(parentProvider.provider)
      assertEquals(1, parentProvider.calls)

      // Push a child frame; child's snapshot is independent.
      SnapshotCache.withFrame {
        val child = SnapshotCache.snapshot(childProvider.provider)
        assertEquals(1, childProvider.calls)
        // Sanity: child and parent slots are different instances.
        assertNotSame(parentBefore, child)

        // Invalidating the child does NOT touch the parent's slot.
        SnapshotCache.invalidateCurrent()
        SnapshotCache.snapshot(childProvider.provider)
        assertEquals(2, childProvider.calls)
      }

      // Back in the parent frame; parent's cached snapshot is still valid.
      val parentAfter = SnapshotCache.snapshot(parentProvider.provider)
      assertSame(parentBefore, parentAfter)
      assertEquals(1, parentProvider.calls)
    }
  }

  @Test
  fun `snapshot outside any frame falls back to direct capture each time`() {
    val counting = CountingProvider()

    // No withFrame — every call should capture afresh, no cache available.
    SnapshotCache.snapshot(counting.provider)
    SnapshotCache.snapshot(counting.provider)
    SnapshotCache.snapshot(counting.provider)

    assertEquals(3, counting.calls)
    assertEquals(0, SnapshotCache.frameDepth())
  }

  @Test
  fun `withFrame pops the slot even when the block throws`() {
    val counting = CountingProvider()

    val initialDepth = SnapshotCache.frameDepth()

    runCatching {
      SnapshotCache.withFrame {
        SnapshotCache.snapshot(counting.provider)
        error("intentional")
      }
    }

    assertEquals(initialDepth, SnapshotCache.frameDepth())
  }

  @Test
  fun `frames are thread-local — sibling threads observe independent stacks`() {
    // Pin cross-thread isolation. The class kdoc claims the stack is per-thread; this
    // test enforces it by running two parallel withFrame blocks (coordinated with a
    // CountDownLatch so both are open at the same time) and asserting each thread sees
    // only its own captured snapshot via a counting provider.
    val threadACounting = CountingProvider()
    val threadBCounting = CountingProvider()
    val gate = java.util.concurrent.CountDownLatch(2)
    val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    val runnable: (CountingProvider) -> Runnable = { provider ->
      Runnable {
        try {
          SnapshotCache.withFrame {
            // First snapshot in this thread's frame — captures.
            val first = SnapshotCache.snapshot(provider.provider)
            // Wait until BOTH threads have opened a frame before continuing, so the
            // stacks overlap in time. If they leaked, the second snapshot below would
            // see the other thread's slot.
            gate.countDown()
            gate.await()
            val second = SnapshotCache.snapshot(provider.provider)
            assertSame(first, second, "same-thread reuse must hit the same slot")
          }
        } catch (t: Throwable) {
          errors.add(t)
        }
      }
    }

    val threadA = Thread(runnable(threadACounting), "SnapshotCacheTest-A")
    val threadB = Thread(runnable(threadBCounting), "SnapshotCacheTest-B")
    threadA.start()
    threadB.start()
    threadA.join(5_000)
    threadB.join(5_000)

    // Belt-and-suspenders: Thread.join(timeout) returns silently when a thread hangs
    // past the deadline, which would let a future race silently turn a real failure
    // into a green run (the per-thread assertions run on the worker, so if the worker
    // hangs they never fire and `errors` stays empty). These two checks make sure we
    // fail loud if either worker is still alive after the join.
    kotlin.test.assertFalse(threadA.isAlive, "thread A should have completed within 5s")
    kotlin.test.assertFalse(threadB.isAlive, "thread B should have completed within 5s")

    if (errors.isNotEmpty()) throw AssertionError("Thread errors: $errors")
    // Each thread captured exactly once — no cross-thread leakage caused a re-capture.
    assertEquals(1, threadACounting.calls, "thread A should capture exactly once")
    assertEquals(1, threadBCounting.calls, "thread B should capture exactly once")
    // After both threads exit, no frames remain on the main thread's stack.
    assertEquals(0, SnapshotCache.frameDepth())
  }
}
