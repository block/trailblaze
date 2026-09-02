package xyz.block.trailblaze.tracing

import kotlin.concurrent.Volatile
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * The innermost open span, in a box so it can outlive the thread that opened it.
 *
 * A coroutine gets one frame that follows it across suspension, which is what lets a nested
 * `trace { }` opened after a resumption still be seen as innermost. The alternative — having the
 * thread-context element remember the span at each suspension and reinstall it — publishes state
 * from `restoreThreadContext`, and on a multithreaded dispatcher that callback can run AFTER the
 * next resumption has already read it. Writing through the frame instead means only the coroutine's
 * own body ever mutates the nesting state, and the element just swaps a reference.
 */
class TraceSpanFrame(@Volatile var spanId: String?)

/**
 * The [TraceSpanFrame] in effect ON THIS THREAD — its span is the parent a newly opened span
 * records.
 *
 * Tracer plumbing, public only because [TrailblazeTraceRecorder.trace] is `inline` and its body is
 * emitted at every call site. Prefer [TrailblazeTraceRecorder.openSpan] over touching this.
 *
 * Thread-scoped rather than global because a span's parent is whatever call frame opened it, and
 * frames are per-thread. Two threads tracing concurrently must not adopt each other's spans — the
 * exact confusion that timestamp-containment nesting suffers from and this replaces.
 */
@Suppress("EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA_WARNING")
expect object TraceSpanLocal {
  fun get(): TraceSpanFrame?
  fun set(frame: TraceSpanFrame?)
}

/** This thread's frame, creating one if plain non-coroutine tracing has not needed it yet. */
internal fun currentOrNewTraceSpanFrame(): TraceSpanFrame =
  TraceSpanLocal.get() ?: TraceSpanFrame(null).also { TraceSpanLocal.set(it) }

/**
 * Carries the innermost open span ACROSS SUSPENSION. [TrailblazeTraceRecorder.traceSuspend] reads
 * it in preference to [TraceSpanLocal], so a suspending traced block that resumes on a different
 * thread still parents its suspending children correctly — a thread-local alone would lose them.
 *
 * A stdlib context element, so nothing in the tracer's public surface depends on a coroutines type.
 */
class TraceSpanContextElement(val spanId: String) : AbstractCoroutineContextElement(Key) {
  companion object Key : CoroutineContext.Key<TraceSpanContextElement>
}

/**
 * The context a suspending span runs its block in: [TraceSpanContextElement] for its suspending
 * children, plus — where the platform has one — a thread-context element that owns [TraceSpanLocal]
 * for the duration of the block.
 *
 * A suspending span must NOT simply set [TraceSpanLocal] and leave it: while the block is
 * suspended, any other coroutine that lands on that thread would read the suspended span as its
 * parent, and the block may resume on a different thread entirely, so the id would never be
 * unwound. Scoping it to the coroutine instead means the local is installed on whatever thread the
 * block is currently running on and restored the moment it suspends — which is also what lets a
 * NON-suspending `trace { }` nested inside the block find its parent.
 *
 * Tracer plumbing, public only because [TrailblazeTraceRecorder.traceSuspend] is `inline`.
 *
 * PLATFORM GAP: wasmJs has no thread-context element, so there the frame is left alone and a
 * non-suspending `trace { }` inside a suspending one comes out parentless — which the profiler
 * then nests by containment inference. Parentless-and-inferred is the safe direction to fail; a
 * leaked local would hand it a confidently WRONG parent.
 */
expect fun traceSpanCoroutineContext(spanId: String): CoroutineContext

/**
 * The context that keeps the span open on THIS thread as the parent of work handed to another one.
 *
 * A non-suspending `trace { }` publishes its span per-thread, and a hand-off to a dedicated worker
 * thread — `runBlocking(otherDispatcher) { … }`, the shape every single-threaded driver bridge uses
 * — starts a fresh coroutine on a thread that has never seen it. Spans opened over there find no
 * parent and record as roots, so the profiler draws the driver's work beside the tool call that
 * caused it instead of inside it.
 *
 * Evaluate this on the CALLING thread and add it to the dispatch context. Empty when no span is
 * open, so it is safe to add unconditionally.
 */
fun currentTraceSpanContext(): CoroutineContext =
  TraceSpanLocal.get()?.spanId?.let { traceSpanCoroutineContext(it) } ?: EmptyCoroutineContext

/**
 * A span opened by [TrailblazeTraceRecorder.openSpan] and not yet closed. Holds the identity the
 * emitted event carries plus what [TrailblazeTraceRecorder.closeSpan] needs to undo.
 */
class OpenTraceSpan @PublishedApi internal constructor(
  val spanId: String,
  val parentSpanId: String?,
  @PublishedApi internal val previousLocal: String?,
  /**
   * Whether opening this span installed itself into [TraceSpanLocal]. False for suspending spans,
   * whose local is owned by [traceSpanCoroutineContext] for exactly as long as they are running.
   */
  @PublishedApi internal val installedLocal: Boolean,
)
