package xyz.block.trailblaze.tracing

object TrailblazeTracer {
  val traceRecorder = TrailblazeTraceRecorder()

  inline fun <T> trace(name: String, cat: String = "app", args: Map<String, String> = emptyMap(), block: () -> T): T = traceRecorder.trace(name, cat, args, block)

  suspend inline fun <T> traceSuspend(
    name: String,
    cat: String = "app",
    args: Map<String, String> = emptyMap(),
    crossinline block: suspend () -> T,
  ): T = traceRecorder.traceSuspend(name, cat, args, block)

  fun exportJson(): String = traceRecorder.toJson()
  fun clear() = traceRecorder.clear()
}
