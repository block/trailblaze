package xyz.block.trailblaze.tracing

object TrailblazeTracer {
  const val IS_TRACING_ENABLED: Boolean = true

  val traceRecorder = TrailblazeTraceRecorder()

  inline fun <T> trace(name: String, cat: String = "app", args: Map<String, String> = emptyMap(), block: () -> T): T = if (IS_TRACING_ENABLED) {
    traceRecorder.trace(name, cat, args, block)
  } else {
    block()
  }

  suspend inline fun <T> traceSuspend(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    crossinline block: suspend () -> T,
  ): T = if (IS_TRACING_ENABLED) {
    traceRecorder.traceSuspend(name, cat, args, block)
  } else {
    block()
  }

  fun exportJson(): String = traceRecorder.toJson()
  fun clear() = traceRecorder.clear()
}
