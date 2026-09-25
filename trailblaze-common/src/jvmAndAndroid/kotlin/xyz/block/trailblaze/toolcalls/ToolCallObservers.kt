package xyz.block.trailblaze.toolcalls

import java.util.concurrent.CopyOnWriteArrayList
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.TraceId
import xyz.block.trailblaze.util.Console

/**
 * Told about every top-level tool call the agent loop dispatches, right before the tool runs and
 * right after it returns. Nested dispatches (a tool running other tools) are not reported: the
 * outer tool is the unit of work a reader recognises from the trail.
 *
 * [traceId] is the trace the dispatch's own logs carry, so what an observer records can be joined
 * to the session log rather than matched on tool name and a timestamp. It identifies the DISPATCH,
 * not the tool: a batch of tools run in one `runTrailblazeTools` call shares one trace — the same
 * grouping the report folds into a single timeline row.
 *
 * Observers run on the dispatching thread, so a slow observer slows the tool call. Keep the
 * work bounded (a probe with a timeout) and never throw — exceptions are logged and swallowed so
 * an observer can't fail a trail.
 */
interface ToolCallObserver {
  fun onBeforeToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?)

  /**
   * Called from a `finally`, so a tool that threw still closes its pair. The outcome is not passed:
   * [traceId] joins the observer's record to that dispatch's own tool log, which is where whether it
   * succeeded already lives — carrying a second copy here would be a field with no reader.
   */
  fun onAfterToolCall(sessionId: SessionId, toolName: String, traceId: TraceId?)
}

/**
 * Process-wide registry of [ToolCallObserver]s, notified from the agent loop's tool dispatch.
 * Process-wide because the loop has no handle on the host services that care (the capture
 * coordinator lives in the daemon, the loop in the shared agent module); the session id is the
 * key an observer routes on.
 */
object ToolCallObservers {
  private val observers = CopyOnWriteArrayList<ToolCallObserver>()

  fun register(observer: ToolCallObserver) {
    observers.addIfAbsent(observer)
  }

  fun unregister(observer: ToolCallObserver) {
    observers.remove(observer)
  }

  val isEmpty: Boolean get() = observers.isEmpty()

  /**
   * How many observers are registered. This registry is process-global and holds by identity for
   * the life of the JVM, so "did this component put itself on the dispatch path, and did it take
   * itself off again" is worth being able to assert without having to be the only registrant.
   */
  val registeredCount: Int get() = observers.size

  fun notifyBefore(sessionId: SessionId, toolName: String, traceId: TraceId?) {
    for (observer in observers) {
      try {
        observer.onBeforeToolCall(sessionId, toolName, traceId)
      } catch (t: Throwable) {
        Console.log("[tool-call-observer] ${observer::class.simpleName} failed before $toolName: ${t.message}")
      }
    }
  }

  fun notifyAfter(sessionId: SessionId, toolName: String, traceId: TraceId?) {
    for (observer in observers) {
      try {
        observer.onAfterToolCall(sessionId, toolName, traceId)
      } catch (t: Throwable) {
        Console.log("[tool-call-observer] ${observer::class.simpleName} failed after $toolName: ${t.message}")
      }
    }
  }
}
