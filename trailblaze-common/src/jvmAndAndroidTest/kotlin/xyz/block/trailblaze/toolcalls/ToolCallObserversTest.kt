package xyz.block.trailblaze.toolcalls

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId

class ToolCallObserversTest {

  private class Recording : ToolCallObserver {
    val calls = mutableListOf<String>()
    override fun onBeforeToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?) {
      calls += "before ${sessionId.value} $toolName ${traceId?.traceId}"
    }
    override fun onAfterToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?) {
      calls += "after ${sessionId.value} $toolName ${traceId?.traceId}"
    }
  }

  private val registered = mutableListOf<ToolCallObserver>()

  private fun <T : ToolCallObserver> register(observer: T): T = observer.also {
    ToolCallObservers.register(it)
    registered += it
  }

  @AfterTest
  fun tearDown() {
    registered.forEach(ToolCallObservers::unregister)
  }

  @Test
  fun `registered observers hear both sides of a tool call with its session and trace`() {
    val observer = register(Recording())
    val session = SessionId("session-1")
    // The trace is what lets an observer's record join to this tool's session logs, so it has to
    // survive the hop rather than being dropped for name-and-timestamp matching.
    val trace = TraceId.generate(TraceId.Companion.TraceOrigin.TOOL)
    ToolCallObservers.notifyBefore(session, "tapOnElement", trace)
    ToolCallObservers.notifyAfter(session, "tapOnElement", trace)
    assertEquals(
      listOf(
        "before session-1 tapOnElement ${trace.traceId}",
        "after session-1 tapOnElement ${trace.traceId}",
      ),
      observer.calls,
    )
  }

  @Test
  fun `an observer that throws neither fails the call nor silences the others`() {
    register(object : ToolCallObserver {
      override fun onBeforeToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?): Unit = error("probe exploded")
      override fun onAfterToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?): Unit =
        error("probe exploded")
    })
    val healthy = register(Recording())
    val session = SessionId("session-2")
    ToolCallObservers.notifyBefore(session, "launchApp", traceId = null)
    ToolCallObservers.notifyAfter(session, "launchApp", traceId = null)
    assertEquals(listOf("before session-2 launchApp null", "after session-2 launchApp null"), healthy.calls)
  }

  @Test
  fun `an unregistered observer hears nothing more and registering twice counts once`() {
    val observer = register(Recording())
    ToolCallObservers.register(observer)
    ToolCallObservers.notifyBefore(SessionId("s"), "a", traceId = null)
    ToolCallObservers.unregister(observer)
    ToolCallObservers.notifyBefore(SessionId("s"), "b", traceId = null)
    assertEquals(listOf("before s a null"), observer.calls)
  }
}
