package xyz.block.trailblaze.tracing

import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Clock
import kotlinx.serialization.json.JsonObject
import kotlin.concurrent.Volatile
import kotlin.time.TimeSource

class TrailblazeTraceRecorder(
  private val emitProcessThreadMetadata: Boolean = true,
) {
  private val events = mutableListOf<JsonObject>()
  private val seenThreads = mutableSetOf<Long>()

  @Volatile
  private var processMetaEmitted = false

  private val mutex = Mutex()

  /**
   * Adds a trace event. Uses a brief spin on the Mutex to guarantee no events are dropped.
   * The critical section is O(1) list append, so contention resolves in nanoseconds.
   * On single-threaded targets (wasmJs), tryLock always succeeds immediately.
   */
  fun add(event: JsonObject) {
    while (!mutex.tryLock()) { /* spin — critical section is ~10ns */ }
    try {
      events += event
    } finally {
      mutex.unlock()
    }
  }

  /** Lambda block (non-suspending). Always records even on exception. */
  inline fun <T> trace(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    block: () -> T,
  ): T {
    val pid = PlatformIds.pid()
    val tid = PlatformIds.tid()
    val startWall = Clock.System.now()
    val mark = TimeSource.Monotonic.markNow()
    var threw: Throwable? = null
    val result = try {
      block()
    } catch (t: Throwable) {
      threw = t
      throw t
    } finally {
      val baseArgs = args.ifEmpty { emptyMap() }
      val finalArgs = if (threw != null) baseArgs + ("error" to (threw.message ?: threw::class.simpleName ?: "unknown")) else baseArgs
      add(CompleteEvent(name, cat, startWall, mark.elapsedNow(), pid, tid, "X", finalArgs).toJsonObject())
    }
    return result
  }

  /** Lambda block (suspending). */
  suspend inline fun <T> traceSuspend(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    crossinline block: suspend () -> T,
  ): T {
    val pid = PlatformIds.pid()
    val tid = PlatformIds.tid()
    val startWall = Clock.System.now()
    val mark = TimeSource.Monotonic.markNow()
    var threw: Throwable? = null
    val result = try {
      block()
    } catch (t: Throwable) {
      threw = t
      throw t
    } finally {
      val baseArgs = if (args.isEmpty()) emptyMap() else args
      val finalArgs = if (threw != null) baseArgs + ("error" to (threw.message ?: threw::class.simpleName ?: "unknown")) else baseArgs
      add(CompleteEvent(name, cat, startWall, mark.elapsedNow(), pid, tid, "X", finalArgs).toJsonObject())
    }
    return result
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
      events.clear()
      seenThreads.clear()
      processMetaEmitted = false
    } finally {
      mutex.unlock()
    }
  }
}
