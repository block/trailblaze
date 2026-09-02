package xyz.block.trailblaze.tracing

actual object TraceSpanLocal {
  private val current = ThreadLocal<TraceSpanFrame?>()

  actual fun get(): TraceSpanFrame? = current.get()

  // remove() rather than set(null) so a finished thread's entry doesn't pin its map slot.
  actual fun set(frame: TraceSpanFrame?) {
    if (frame == null) current.remove() else current.set(frame)
  }
}
