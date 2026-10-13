package xyz.block.trailblaze.host.yaml

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import xyz.block.trailblaze.llm.RunYamlResponse
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * When a synchronous whole-trail run counts as over: at the reply, or at the session's own end
 * status, whichever the host sees first ([DesktopYamlRunner.replyOrSessionEnd]).
 */
class DesktopYamlRunnerReplyOrSessionEndTest {

  private val pinned = SessionId("pinned_trail_1234")

  @Test
  fun `a run whose session has ended is over without waiting for the reply`() = runTest {
    val polls = AtomicInteger(0)
    val requestCancelled = CompletableDeferred<Unit>()

    val result = DesktopYamlRunner.replyOrSessionEnd(
      pinnedSessionId = pinned,
      sendRequest = {
        try {
          awaitCancellation()
        } finally {
          requestCancelled.complete(Unit)
        }
      },
      sessionHasEnded = { id -> id == pinned && polls.incrementAndGet() >= 3 },
      pollIntervalMs = 10,
    )

    assertEquals(RpcResult.Success(RunYamlResponse(sessionId = pinned)), result)
    // The abandoned request is not left running against the device.
    assertTrue(requestCancelled.isCompleted)
  }

  @Test
  fun `a request the device refuses fails even though no session ever ends`() = runTest {
    val refused = RpcResult.Failure(errorType = RpcResult.ErrorType.HTTP_ERROR, message = "HTTP 400: bad trail")

    val result = DesktopYamlRunner.replyOrSessionEnd(
      pinnedSessionId = pinned,
      sendRequest = { refused },
      sessionHasEnded = { false },
      pollIntervalMs = 10,
    )

    assertEquals(refused, result)
  }

  @Test
  fun `a reply that arrives first is returned as the device sent it`() = runTest {
    val reply = RpcResult.Success(
      RunYamlResponse(sessionId = pinned, success = true, memorySnapshot = mapOf("total" to "42")),
    )

    val result = DesktopYamlRunner.replyOrSessionEnd(
      pinnedSessionId = pinned,
      sendRequest = { reply },
      sessionHasEnded = { false },
      pollIntervalMs = 10,
    )

    assertEquals(reply, result)
  }

  @Test
  fun `an unpinned run waits for the reply, the only place its session id exists`() = runTest {
    var consulted = false
    val reply = RpcResult.Success(RunYamlResponse(sessionId = SessionId("minted_on_device")))

    val result = DesktopYamlRunner.replyOrSessionEnd(
      pinnedSessionId = null,
      sendRequest = { reply },
      sessionHasEnded = { consulted = true; true },
      pollIntervalMs = 10,
    )

    assertEquals(reply, result)
    assertEquals(false, consulted)
  }

  @Test
  fun `capture started for a request the device refuses is handed back to be stopped`() = runTest {
    val events = mutableListOf<String>()

    DesktopYamlRunner.replyOrSessionEnd(
      pinnedSessionId = pinned,
      sendRequest = {
        events += "send"
        RpcResult.Failure(errorType = RpcResult.ErrorType.HTTP_ERROR, message = "HTTP 400: bad trail")
      },
      sessionHasEnded = { false },
      onDispatching = { events += "start:${it.value}" },
      onAbandoned = { events += "abandon:${it.value}" },
      pollIntervalMs = 10,
    )

    assertEquals(listOf("start:${pinned.value}", "send", "abandon:${pinned.value}"), events)
  }

  @Test
  fun `capture started for a request that throws is handed back to be stopped`() = runTest {
    val abandoned = mutableListOf<SessionId>()

    val thrown = runCatching {
      DesktopYamlRunner.replyOrSessionEnd(
        pinnedSessionId = pinned,
        sendRequest = { throw IllegalStateException("runner lacks a required capability") },
        sessionHasEnded = { false },
        onAbandoned = { abandoned += it },
        pollIntervalMs = 10,
      )
    }.exceptionOrNull()

    assertTrue(thrown is IllegalStateException)
    assertEquals(listOf(pinned), abandoned)
  }

  @Test
  fun `a session the device ran is never handed back as abandoned`() = runTest {
    val abandoned = mutableListOf<SessionId>()

    DesktopYamlRunner.replyOrSessionEnd(
      pinnedSessionId = pinned,
      sendRequest = { RpcResult.Success(RunYamlResponse(sessionId = pinned)) },
      sessionHasEnded = { false },
      onAbandoned = { abandoned += it },
      pollIntervalMs = 10,
    )

    assertEquals(emptyList(), abandoned)
  }

  @Test
  fun `an end status left by an earlier run does not end this one before its reply`() = runTest {
    val reply = CompletableDeferred<RpcResult<RunYamlResponse>>()
    val answer = RpcResult.Success(RunYamlResponse(sessionId = pinned, errorMessage = "from this run"))

    val result = async {
      DesktopYamlRunner.replyOrSessionEnd(
        pinnedSessionId = pinned,
        sendRequest = { reply.await() },
        // Already ended before this request was sent.
        sessionHasEnded = { true },
        pollIntervalMs = 10,
      )
    }
    advanceTimeBy(1_000)
    assertTrue(result.isActive, "returned on the earlier run's end status")
    reply.complete(answer)

    assertEquals(answer, result.await())
  }
}

