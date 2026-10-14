package xyz.block.trailblaze.logs.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** A runtime rebuild and the session's tool calls never overlap, whichever arrives first. */
@OptIn(ExperimentalCoroutinesApi::class)
class RuntimeRebuildGateTest {

  @Test
  fun `a rebuild waits for the running call to finish`() = runTest {
    val gate = RuntimeRebuildGate()
    val events = mutableListOf<String>()
    val finishCall = CompletableDeferred<Unit>()

    launch { gate.call { events += "call started"; finishCall.await(); events += "call finished" } }
    runCurrent()
    launch { gate.rebuild { events += "rebuilt" } }
    runCurrent()
    assertEquals(listOf("call started"), events)

    finishCall.complete(Unit)
    runCurrent()
    assertEquals(listOf("call started", "call finished", "rebuilt"), events)
  }

  @Test
  fun `a call that arrives during a rebuild waits for it`() = runTest {
    val gate = RuntimeRebuildGate()
    val events = mutableListOf<String>()
    val finishRebuild = CompletableDeferred<Unit>()

    launch { gate.rebuild { events += "rebuild started"; finishRebuild.await(); events += "rebuild finished" } }
    runCurrent()
    launch { gate.call { events += "call" } }
    runCurrent()
    assertEquals(listOf("rebuild started"), events)

    finishRebuild.complete(Unit)
    runCurrent()
    assertEquals(listOf("rebuild started", "rebuild finished", "call"), events)
  }

  @Test
  fun `a rebuild waits for every overlapping call, not just the latest`() = runTest {
    val gate = RuntimeRebuildGate()
    val events = mutableListOf<String>()
    val finishFirst = CompletableDeferred<Unit>()
    val finishSecond = CompletableDeferred<Unit>()

    launch { gate.call { finishFirst.await(); events += "first finished" } }
    launch { gate.call { finishSecond.await(); events += "second finished" } }
    runCurrent()
    launch { gate.rebuild { events += "rebuilt" } }

    finishSecond.complete(Unit)
    runCurrent()
    assertEquals(listOf("second finished"), events, "the first call is still running")

    finishFirst.complete(Unit)
    runCurrent()
    assertEquals(listOf("second finished", "first finished", "rebuilt"), events)
  }
}
