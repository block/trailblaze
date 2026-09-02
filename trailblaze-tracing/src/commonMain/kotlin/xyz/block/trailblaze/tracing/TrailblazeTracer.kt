package xyz.block.trailblaze.tracing

import kotlinx.coroutines.sync.Mutex

object TrailblazeTracer {

  val traceRecorder = TrailblazeTraceRecorder()

  /**
   * How much this process records. Defaults to the environment (`TRAILBLAZE_TRACE_LEVEL`, or the
   * `trailblaze.trace.level` system property) and can be changed at runtime — a long-lived daemon
   * should be able to turn detail on for one investigation without a restart.
   *
   * Delegates to the recorder, which is where the gate is enforced: producers that build their own
   * events call it directly, so a gate that lived only here would not hold.
   */
  var level: TraceLevel
    get() = traceRecorder.level
    set(value) { traceRecorder.level = value }

  val isEnabled: Boolean get() = level != TraceLevel.OFF

  /**
   * Runs [block] at [level], restoring the level that was in place afterwards.
   *
   * For a process that outlives the run it is recording — a daemon — where the level belongs to
   * whoever asked for the run, not to the environment the process happened to start in. A null
   * [level] means "not requested" and leaves the process default alone, so a caller that sends no
   * level is not the same as one that asks for `normal`.
   *
   * The level is process-wide, like the recorder it gates, and a daemon deliberately runs more than
   * one trail at a time. Overlapping runs therefore share one level, resolved to the most verbose
   * any of them asked for: a run recording more than it needed loses nothing, whereas a run recording
   * less has lost spans that cannot be recovered. The process default is restored when the last of
   * them finishes, whatever order they finish in — a save-and-restore per run would have the first
   * to finish reinstate the default under a run still going, and leave the daemon at a run's level
   * afterwards. Per-session recorders are what would let each run record only what it asked for.
   */
  fun <T> withLevel(level: TraceLevel?, block: () -> T): T {
    if (level == null) return block()
    enterLevelScope(level)
    return try {
      block()
    } finally {
      exitLevelScope()
    }
  }

  private fun enterLevelScope(level: TraceLevel) {
    withLevelScopeLock {
      if (scopeDepth == 0) processDefaultLevel = this.level
      scopeDepth++
      // Ordinals ascend OFF → NORMAL → VERBOSE, so this is "the most verbose request in flight".
      val resolved = maxOf(scopedLevel ?: level, level)
      scopedLevel = resolved
      this.level = resolved
    }
  }

  private fun exitLevelScope() {
    withLevelScopeLock {
      // A run that ends while another is still going leaves the level alone: lowering it here would
      // silently stop recording spans the other run asked for.
      if (--scopeDepth <= 0) {
        scopeDepth = 0
        processDefaultLevel?.let { this.level = it }
        processDefaultLevel = null
        scopedLevel = null
      }
    }
  }

  /** Same spin as the recorder's: the critical section is a handful of field writes. */
  private inline fun withLevelScopeLock(block: () -> Unit) {
    while (!levelScopeMutex.tryLock()) { /* spin */ }
    try {
      block()
    } finally {
      levelScopeMutex.unlock()
    }
  }

  private val levelScopeMutex = Mutex()

  /** How many runs are inside [withLevel] right now. */
  private var scopeDepth = 0

  /** The level to hand back once the last of them leaves, or null when none are in flight. */
  private var processDefaultLevel: TraceLevel? = null

  /** The most verbose level any run in flight asked for. */
  private var scopedLevel: TraceLevel? = null

  /** Whether [traceDetail] is recording. Worth checking before *building* expensive span args. */
  val isVerbose: Boolean get() = level == TraceLevel.VERBOSE

  /** Delegates: the recorder decides whether anything is recorded. */
  inline fun <T> trace(name: String, cat: String = "app", args: Map<String, String> = emptyMap(), kind: SpanKind = SpanKind.INTERNAL, block: () -> T): T =
    traceRecorder.trace(name, cat, args, kind, block)

  suspend inline fun <T> traceSuspend(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    kind: SpanKind = SpanKind.INTERNAL,
    crossinline block: suspend () -> T,
  ): T = traceRecorder.traceSuspend(name, cat, args, kind, block)

  /**
   * A span that only exists at [TraceLevel.VERBOSE].
   *
   * For work fine-grained enough that recording it changes what you are measuring: a driver's
   * individual operations, the inside of a screen capture, per-node selector matching. These fire
   * hundreds of times per step, so they are off unless asked for — and because this is `inline`, at
   * [TraceLevel.NORMAL] the block runs with nothing around it but a field read.
   *
   * Build [args] cheaply, or guard the construction with [isVerbose]: argument expressions are
   * evaluated whether or not the span is recorded.
   */
  inline fun <T> traceDetail(name: String, cat: String = "app", args: Map<String, String> = emptyMap(), kind: SpanKind = SpanKind.INTERNAL, block: () -> T): T = if (level == TraceLevel.VERBOSE) {
    traceRecorder.trace(name, cat, args, kind, block)
  } else {
    block()
  }

  /** Suspending [traceDetail]. */
  suspend inline fun <T> traceDetailSuspend(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    kind: SpanKind = SpanKind.INTERNAL,
    crossinline block: suspend () -> T,
  ): T = if (level == TraceLevel.VERBOSE) {
    traceRecorder.traceSuspend(name, cat, args, kind, block)
  } else {
    block()
  }

  fun exportJson(): String = traceRecorder.toJson()
  fun clear() = traceRecorder.clear()

  /**
   * Records this process's half of a run into the trace another process started — see
   * [TrailblazeTraceRecorder.joinTrace]. Call it where the dispatched work arrives, once per
   * dispatch and on every one: each names the span its own work belongs under, and a null
   * [context] says this dispatch named none, which is not the same as saying nothing.
   */
  fun joinTrace(context: TraceContext?) = traceRecorder.joinTrace(context)

  /**
   * What to send another process so its spans join this recording: the trace, and the span the
   * caller is currently inside.
   *
   * Null when there is nothing to hand over — tracing is off here, or no span is open on this
   * thread. The receiver then records a trace of its own, which is what happened before any of this
   * and still profiles its own half correctly; the two halves simply do not join into one tree.
   *
   * Reads the thread's span, so call it from the frame that is dispatching the work rather than
   * from a worker it hands off to.
   */
  fun currentTraceContext(): TraceContext? {
    if (level == TraceLevel.OFF) return null
    val spanId = TraceSpanLocal.get()?.spanId ?: return null
    return TraceContext(traceId = traceRecorder.traceId(), spanId = spanId)
  }
}
