package xyz.block.trailblaze.tracing

// Single-threaded target: a plain global IS the thread-local (matching PlatformIds.tid() == 1).
actual object TraceSpanLocal {
  private var current: TraceSpanFrame? = null

  actual fun get(): TraceSpanFrame? = current

  actual fun set(frame: TraceSpanFrame?) {
    current = frame
  }
}
