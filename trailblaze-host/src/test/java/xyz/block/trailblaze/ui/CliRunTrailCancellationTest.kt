package xyz.block.trailblaze.ui

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * A daemon run blocks its handler on a latch while the trail runs in the device's own scope, so
 * cancelling the run has to reach across to the trail. These tests model that shape: a trail
 * launched in a separate scope, and a handler blocked waiting for it.
 */
class CliRunTrailCancellationTest {

  private val deviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  @Test
  fun `cancelling the run cancels the trail it is waiting on`() = runBlocking {
    val trailStarted = CountDownLatch(1)
    val trailEnded = CountDownLatch(1)
    lateinit var trail: Job

    val handler = launch(Dispatchers.IO) {
      blockUntilTrailEndsOrCancelIt { onTrailStarted ->
        trail = deviceScope.launch {
          try {
            awaitCancellation()
          } finally {
            trailEnded.countDown()
          }
        }
        onTrailStarted(trail)
        trailStarted.countDown()
        trailEnded.await()
      }
    }
    trailStarted.await()

    // Hang guard only: without the interruptible wait, this cancel never returns.
    withTimeout(HANG_GUARD_MS) { handler.cancelAndJoin() }

    assertThat(trail.isCancelled).isTrue()
    deviceScope.coroutineContext[Job]!!.cancel()
  }

  @Test
  fun `a trail that ends on its own is returned from and left alone`() = runBlocking {
    lateinit var trail: Job

    val result = blockUntilTrailEndsOrCancelIt { onTrailStarted ->
      trail = deviceScope.launch {}
      onTrailStarted(trail)
      runBlocking { trail.join() }
      "done"
    }

    assertThat(result).isEqualTo("done")
    assertThat(trail.isCancelled).isFalse()
    deviceScope.coroutineContext[Job]!!.cancel()
  }

  private companion object {
    const val HANG_GUARD_MS = 60_000L
  }
}
