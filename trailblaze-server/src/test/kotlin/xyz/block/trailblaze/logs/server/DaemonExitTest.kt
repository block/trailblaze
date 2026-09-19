package xyz.block.trailblaze.logs.server

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DaemonExitTest {

  /**
   * Mirrors the deadlock this exists to prevent: the exit cannot complete until the requesting
   * thread has moved on (Ktor's shutdown hook waits for the event loop that served the request).
   * An exit that ran on the caller's thread would never see the latch released and the test would
   * time out instead of passing.
   */
  @Test
  fun `the exit waits on nothing the caller still has to do`() {
    val callerReturned = CountDownLatch(1)
    val exitFinished = CountDownLatch(1)
    val exitThread = AtomicReference<Thread>()
    val exitStatus = AtomicReference<Int>()
    val sawCallerReturn = AtomicBoolean(false)

    DaemonExit.exitOffCallerThread(status = 3) { status ->
      exitThread.set(Thread.currentThread())
      exitStatus.set(status)
      // Blocks until the caller has returned, the way the shutdown hook blocks on the event loop.
      sawCallerReturn.set(callerReturned.await(5, TimeUnit.SECONDS))
      exitFinished.countDown()
    }
    callerReturned.countDown()

    assertTrue(exitFinished.await(10, TimeUnit.SECONDS), "exit never completed")
    assertTrue(sawCallerReturn.get(), "the exit ran on the caller's thread and timed out waiting for it")
    assertNotEquals(Thread.currentThread(), exitThread.get())
    assertEquals(3, exitStatus.get())
  }
}
