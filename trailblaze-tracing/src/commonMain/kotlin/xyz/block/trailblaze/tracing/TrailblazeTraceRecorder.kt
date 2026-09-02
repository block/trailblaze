package xyz.block.trailblaze.tracing

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.concurrent.Volatile
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

class TrailblazeTraceRecorder(
  private val emitProcessThreadMetadata: Boolean = true,
) {
  private val events = mutableListOf<JsonObject>()
  private val seenThreads = mutableSetOf<Long>()

  @Volatile
  private var processMetaEmitted = false

  private val mutex = Mutex()

  private var currentTraceId: String? = null

  /**
   * The span in another process that dispatched this recording's work, set by [joinTrace].
   *
   * Volatile rather than mutex-guarded: [openSpan] reads it on every span and takes no lock, and a
   * span that reads it a moment before [joinTrace] lands would only be parented the way it was
   * before the join — the same outcome as not joining at all.
   */
  @Volatile
  private var dispatchingSpanId: String? = null

  /**
   * Whether this recording has joined another process's trace since it last started fresh.
   *
   * Distinct from [dispatchingSpanId], which a dispatch naming no trace clears — that null says
   * "stop parenting to a span that no longer exists", not "forget a join ever happened". The
   * different-trace reset in [joinTrace] keys off THIS, so a traced run arriving after an untraced
   * one still starts its recording fresh instead of stacking onto earlier runs' events, while a
   * recording that never joined keeps its pre-traceparent spans through its first join.
   *
   * Not volatile: every reader and writer already holds the mutex.
   */
  private var joinedSinceReset = false

  /**
   * How much this recorder records. The gate lives HERE, not on [TrailblazeTracer], because that
   * façade is not the only way in: driver wrappers and the LLM client call [trace] directly, and the
   * HTTP and Playwright producers call [add] with events they built themselves. Gating only the
   * façade left `off` recording nearly everything an ordinary run does.
   *
   * A plain volatile: a span that has already started finishes and records regardless, since the
   * alternative is an event with a start and no end.
   */
  @Volatile
  var level: TraceLevel = configuredTraceLevel()

  /**
   * The trace every span in this recording belongs to, minted on first use and replaced by
   * [clear]. Callers that hand work to another process pass this along so the spans that process
   * records join this trace instead of starting an unrelated one.
   */
  fun traceId(): String {
    while (!mutex.tryLock()) { /* spin — critical section is ~10ns */ }
    try {
      return traceIdLocked()
    } finally {
      mutex.unlock()
    }
  }

  /**
   * A fresh span id, for a producer that builds its own [CompleteEvent] instead of wrapping work in
   * [trace]. Such an event still needs an id: it is what makes the span addressable as a parent,
   * and a span with no id can only be placed by containment inference downstream.
   */
  fun newSpanId(): String = TraceIds.newSpanId()

  /** [traceId] without taking the lock. Callers must already hold it. */
  private fun traceIdLocked(): String = currentTraceId ?: TraceIds.newTraceId().also { currentTraceId = it }

  /**
   * Records into the trace [context] names, parenting every span opened outside an enclosing
   * `trace { }` to the span that dispatched the work.
   *
   * Call it wherever dispatched work arrives, on EVERY dispatch — a null [context] included, when
   * the dispatch named no trace. This is the whole story of what the recording inherits, so a
   * dispatch that names nothing has to say so: the receiving process outlives any one run, since a
   * device's RPC server serves run after run, and leaving the previous dispatch's context in place
   * would file a run whose host is not recording into the finished run's trace, under a parent span
   * that exists nowhere.
   *
   * A dispatch usually names a trace this recorder is already on: a host sends each tool call
   * separately, and each names a different span to hang under. That only re-points the parent and
   * keeps what is recorded. A different trace on a recording that has JOINED before empties it, for
   * the reason [clear] does: the work belongs to a different run, and keeping the old events would
   * file one recording under two trace ids. Joined-before is load-bearing — a recorder tracing on
   * its own holds a self-minted id that never equals any host's, so "different id" alone cannot
   * distinguish a new run from the same run's first dispatch to carry a traceparent, and those
   * self-traced spans belong to the run being dispatched. They are kept, at the price of the upload
   * holding two trace ids: a split trace names both halves, lost spans name nothing.
   *
   * Only spans with nothing above them inherit the dispatching span. A span opened inside another
   * still parents to its enclosing one, so the shape of this process's own tree is untouched; the
   * join only supplies the root that was previously missing.
   */
  fun joinTrace(context: TraceContext?) {
    while (!mutex.tryLock()) { /* spin */ }
    try {
      if (context == null) {
        // Only a join sets a dispatching span, so its presence is what "inherited" means. The trace
        // id goes with it, leaving the next span to mint a fresh one rather than joining a trace
        // this dispatch has nothing to do with. Nothing is emptied — those events belong to the run
        // that was dispatched, and this process has not uploaded them yet. A recorder that never
        // joined is left alone, so a device tracing on its own keeps one trace id for the whole
        // session instead of minting one per dispatch. [joinedSinceReset] deliberately survives:
        // this null re-anchors parentage, it does not make the recording never-joined again.
        if (dispatchingSpanId != null) {
          currentTraceId = null
          dispatchingSpanId = null
        }
        return
      }
      if (joinedSinceReset && currentTraceId != context.traceId) resetLocked()
      currentTraceId = context.traceId
      dispatchingSpanId = context.spanId
      joinedSinceReset = true
    } finally {
      mutex.unlock()
    }
  }

  /**
   * Adds a trace event, stamping the recording's trace id when the event does not carry one — so a
   * producer that builds its own [CompleteEvent] still lands in the right trace.
   *
   * Stamping happens in the SAME critical section as the append: resolving the id first and
   * appending second leaves a window where [clear] starts a new recording in between, which would
   * file an event under the trace that just ended and leave one recording holding two trace ids.
   *
   * Uses a brief spin on the Mutex to guarantee no events are dropped. The critical section is
   * O(1) list append, so contention resolves in nanoseconds. On single-threaded targets (wasmJs),
   * tryLock always succeeds immediately.
   */
  fun add(event: JsonObject) {
    if (level == TraceLevel.OFF) return
    while (!mutex.tryLock()) { /* spin — critical section is ~10ns */ }
    try {
      events += if (event.containsKey("trid")) event else JsonObject(event + ("trid" to JsonPrimitive(traceIdLocked())))
    } finally {
      mutex.unlock()
    }
  }

  /**
   * Opens a span. Its parent is [parentOverride] when given — [traceSuspend] passes the span
   * carried across suspension — and otherwise whatever frame on this thread is already inside a
   * `trace { }`.
   *
   * With [installLocal], the new span also becomes the innermost open span in this frame until
   * [closeSpan], which is right for a synchronous block and wrong for a suspending one: a
   * suspending block's local is owned by [traceSpanCoroutineContext] for exactly as long as it is
   * running, so whatever lands on the thread while it is suspended cannot read it.
   */
  fun openSpan(parentOverride: String? = null, installLocal: Boolean = true): OpenTraceSpan {
    val id = TraceIds.newSpanId()
    val frame = currentOrNewTraceSpanFrame()
    val previousLocal = frame.spanId
    if (installLocal) frame.spanId = id
    return OpenTraceSpan(
      spanId = id,
      // Falling back to the dispatching span is what makes a device's root spans children of the
      // host tool call that asked for the work, rather than roots of a second trace.
      parentSpanId = parentOverride ?: previousLocal ?: dispatchingSpanId,
      previousLocal = previousLocal,
      installedLocal = installLocal,
    )
  }

  /**
   * Closes [span], restoring the span that was innermost on this thread when it opened.
   *
   * Restores only when this span is still the one installed: if something else owns the local now,
   * overwriting it would replace a live parent with a closed one, and the extractor trusts a
   * declared parent by id — a span nested under a long-closed parent reads as zero self time.
   */
  fun closeSpan(span: OpenTraceSpan) {
    if (!span.installedLocal) return
    val frame = TraceSpanLocal.get() ?: return
    if (frame.spanId != span.spanId) return
    frame.spanId = span.previousLocal
  }

  /** Lambda block (non-suspending). Always records even on exception. */
  inline fun <T> trace(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    kind: SpanKind = SpanKind.INTERNAL,
    block: () -> T,
  ): T {
    // Before any of the bookkeeping, not just before the append: opening a span mutates the frame
    // and reading the wall clock is not free either.
    if (level == TraceLevel.OFF) return block()
    val pid = PlatformIds.pid()
    val tid = PlatformIds.tid()
    val span = openSpan()
    val startWall = Clock.System.now()
    val mark = TimeSource.Monotonic.markNow()
    var threw: Throwable? = null
    val result = try {
      block()
    } catch (t: Throwable) {
      threw = t
      throw t
    } finally {
      closeSpan(span)
      val baseArgs = args.ifEmpty { emptyMap() }
      val finalArgs = if (threw != null) baseArgs + ("error" to (threw.message ?: threw::class.simpleName ?: "unknown")) else baseArgs
      add(
        CompleteEvent(
          name, cat, startWall, mark.elapsedNow(), pid, tid, "X", finalArgs,
          sid = span.spanId,
          psid = span.parentSpanId,
          kind = kind.takeIf { it != SpanKind.INTERNAL },
        ).toJsonObject(),
      )
    }
    return result
  }

  /**
   * Lambda block (suspending).
   *
   * Reads its parent from [TraceSpanContextElement] in preference to the thread-local, and runs
   * [block] in [traceSpanCoroutineContext] — so a child opened after the coroutine resumed on a
   * different thread still finds this span, and a coroutine that lands on the thread while this
   * one is suspended does not. Adding non-dispatcher elements does not re-dispatch.
   */
  suspend inline fun <T> traceSuspend(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    kind: SpanKind = SpanKind.INTERNAL,
    crossinline block: suspend () -> T,
  ): T {
    // Also skips the `withContext` below, which is the expensive part of a suspending span.
    if (level == TraceLevel.OFF) return block()
    val pid = PlatformIds.pid()
    val tid = PlatformIds.tid()
    val span = openSpan(
      parentOverride = coroutineContext[TraceSpanContextElement.Key]?.spanId,
      installLocal = false,
    )
    val startWall = Clock.System.now()
    val mark = TimeSource.Monotonic.markNow()
    var threw: Throwable? = null
    val result = try {
      withContext(traceSpanCoroutineContext(span.spanId)) { block() }
    } catch (t: Throwable) {
      threw = t
      throw t
    } finally {
      closeSpan(span)
      val baseArgs = if (args.isEmpty()) emptyMap() else args
      val finalArgs = if (threw != null) baseArgs + ("error" to (threw.message ?: threw::class.simpleName ?: "unknown")) else baseArgs
      add(
        CompleteEvent(
          name, cat, startWall, mark.elapsedNow(), pid, tid, "X", finalArgs,
          sid = span.spanId,
          psid = span.parentSpanId,
          kind = kind.takeIf { it != SpanKind.INTERNAL },
        ).toJsonObject(),
      )
    }
    return result
  }

  /**
   * Takes everything recorded so far and hands it over, leaving the recorder empty but still on the
   * SAME trace.
   *
   * What a flush needs, and what [toJson] followed by [clear] is not. Two reasons:
   *  - [clear] starts a new trace, so a session that flushes twice would file its two halves under
   *    two unrelated trace ids — and a span in the second half whose parent was recorded in the
   *    first could never be resolved.
   *  - the export and the reset happen under one lock. Separately, an event added in between is
   *    dropped: written after the snapshot, erased before the next one.
   */
  fun drain(): String {
    while (!mutex.tryLock()) { /* spin */ }
    try {
      val json = TRACING_JSON_INSTANCE.encodeToString(events.toList())
      events.clear()
      seenThreads.clear()
      processMetaEmitted = false
      return json
    } finally {
      mutex.unlock()
    }
  }

  /** Build the JSON string ready for Perfetto. */
  fun toJson(): String {
    while (!mutex.tryLock()) { /* spin */ }
    try {
      return TRACING_JSON_INSTANCE.encodeToString(events.toList())
    } finally {
      mutex.unlock()
    }
  }

  /** Clear recorded events (keep metadata flags). */
  fun clear() {
    while (!mutex.tryLock()) { /* spin */ }
    try {
      resetLocked()
    } finally {
      mutex.unlock()
    }
  }

  /** [clear] without taking the lock. Callers must already hold it. */
  private fun resetLocked() {
    events.clear()
    seenThreads.clear()
    processMetaEmitted = false
    // A cleared recorder starts a new trace: the next span belongs to a different run, and a
    // psid from the cleared recording must not resolve against it. That includes a span inherited
    // from another process — the run it was dispatched for is the run that just ended. Forgetting
    // the join itself is what re-arms the first-join grace: the fresh recording's own
    // pre-traceparent spans deserve to survive its first join, same as on a fresh recorder.
    currentTraceId = null
    dispatchingSpanId = null
    joinedSinceReset = false
  }
}
