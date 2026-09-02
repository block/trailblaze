package xyz.block.trailblaze.tracing

import kotlinx.coroutines.CopyableThreadContextElement
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlin.coroutines.CoroutineContext

actual fun traceSpanCoroutineContext(spanId: String): CoroutineContext =
  TraceSpanContextElement(spanId) + TraceSpanLocalScope(TraceSpanFrame(spanId))

/**
 * Puts its coroutine's [TraceSpanFrame] in [TraceSpanLocal] for as long as that coroutine is
 * actually running on a thread: installed on every resumption, restored on every suspension. That
 * is what keeps a suspended span out of an unrelated coroutine that lands on the same thread.
 *
 * Both callbacks only swap a reference; the span inside the frame is written by the coroutine's own
 * body, in [TrailblazeTraceRecorder.openSpan]. So a nested `trace { }` that suspends — `trace` is
 * inline, so its lambda suspends with the enclosing function — is still the innermost span when the
 * coroutine resumes, and `restoreThreadContext` running late on a multithreaded dispatcher cannot
 * clobber it.
 *
 * Copyable so a child coroutine gets its own frame, snapshotting whatever span is innermost at the
 * moment it is launched. Sharing the parent's frame would let siblings pop each other's spans.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private class TraceSpanLocalScope(private val frame: TraceSpanFrame) : CopyableThreadContextElement<TraceSpanFrame?> {
  companion object Key : CoroutineContext.Key<TraceSpanLocalScope>

  override val key: CoroutineContext.Key<TraceSpanLocalScope> get() = Key

  override fun updateThreadContext(context: CoroutineContext): TraceSpanFrame? {
    val previous = TraceSpanLocal.get()
    TraceSpanLocal.set(frame)
    return previous
  }

  override fun restoreThreadContext(context: CoroutineContext, oldState: TraceSpanFrame?) {
    TraceSpanLocal.set(oldState)
  }

  override fun copyForChild(): CopyableThreadContextElement<TraceSpanFrame?> = TraceSpanLocalScope(TraceSpanFrame(frame.spanId))

  override fun mergeForChild(overwritingElement: CoroutineContext.Element): CoroutineContext = overwritingElement
}
