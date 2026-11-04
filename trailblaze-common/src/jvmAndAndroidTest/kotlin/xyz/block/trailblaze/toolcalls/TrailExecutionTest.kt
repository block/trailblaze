package xyz.block.trailblaze.toolcalls

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

class TrailExecutionTest {
  @Test
  fun `nested trail loops and cleanup retain the outer owner across threads`() = runBlocking {
    val events = mutableListOf<String>()
    lateinit var owner: TrailExecution
    TrailExecution.run("authored-trail") {
      owner = requireNotNull(TrailExecution.current())
      owner.invokeOnCompletion { events += "released" }
      TrailExecution.within("nested-item-loop") {
        withContext(Dispatchers.IO) { assertSame(owner, TrailExecution.current()) }
      }
      withContext(NonCancellable + Dispatchers.IO) {
        assertSame(owner, TrailExecution.current())
        assertFalse(owner.isFinished)
        events += "runtime and app cleanup"
      }
    }
    assertEquals(listOf("runtime and app cleanup", "released"), events)
    assertTrue(owner.isFinished)
    assertNull(TrailExecution.current())
    TrailExecution.run("authored-trail") { assertNotSame(owner, TrailExecution.current()) }
  }

  @Test
  fun `completion waits for owned child work and its cleanup`() = runBlocking {
    withTimeout(60_000) {
      val childStarted = CompletableDeferred<Unit>()
      val finishChild = CompletableDeferred<Unit>()
      lateinit var owner: TrailExecution
      val job = launch {
        TrailExecution.run("authored-trail") {
          owner = requireNotNull(TrailExecution.current())
          CoroutineScope(currentCoroutineContext()).launch {
            childStarted.complete(Unit)
            finishChild.await()
          }
        }
      }
      try {
        childStarted.await()
        assertFalse(owner.isFinished)
        finishChild.complete(Unit)
        job.join()
        assertTrue(owner.isFinished)
      } finally {
        finishChild.complete(Unit)
        job.join()
      }
    }
  }

  @Test
  fun `child failure survives cancellation and child cleanup precedes release`() = runBlocking {
    val events = mutableListOf<String>()
    val childFailure = IllegalStateException("child failed")
    lateinit var owner: TrailExecution
    val thrown =
      assertFailsWith<IllegalStateException> {
        TrailExecution.run("authored-trail") {
          owner = requireNotNull(TrailExecution.current())
          owner.invokeOnCompletion { events += "released" }
          CoroutineScope(currentCoroutineContext()).launch {
            try {
              throw childFailure
            } finally {
              withContext(NonCancellable) {
                assertFalse(owner.isFinished)
                events += "child cleanup"
              }
            }
          }
          awaitCancellation()
        }
      }
    // Child failures retain coroutine stack recovery, while the body keeps its existing identity.
    assertTrue(thrown === childFailure || thrown.cause === childFailure)
    assertEquals(listOf("child cleanup", "released"), events)
    assertTrue(owner.isFinished)
  }

  @Test
  fun `blocking replay inherits the suspending owner without finishing it`() = runBlocking {
    TrailExecution.run("authored-trail") {
      val owner = requireNotNull(TrailExecution.current())
      TrailExecution.withinBlocking("nested-item-loop") {
        assertSame(owner, TrailExecution.current())
      }
      assertFalse(owner.isFinished)
    }
  }
}
