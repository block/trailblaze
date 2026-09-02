package xyz.block.trailblaze.tracing

/**
 * The span another process should hang its work under, and the trace both halves belong to.
 *
 * A run is recorded in more than one process: the host records the agent, the LLM calls and the
 * tools it dispatches, and a device records what the driver actually did. Each recorder mints its
 * own trace id, so a merged `trace.json` carries two of them and the run arrives in a trace viewer
 * as two unrelated traces side by side. The dispatching process sends the trace it is recording
 * into plus the span it is dispatching from; the receiving process joins that trace and parents its
 * own spans to that span.
 *
 * Carried as a W3C `traceparent`, because our ids are already that shape — a 16-byte trace and an
 * 8-byte span, lowercase hex — so a standard field costs nothing over an ad-hoc pair and stays
 * readable to anything that isn't us.
 */
data class TraceContext(
  val traceId: String,
  val spanId: String,
) {
  /** This context as a `traceparent` field value. */
  fun toTraceParent(): String = "$VERSION-$traceId-$spanId-$SAMPLED"

  companion object {
    /**
     * The only version this mints or accepts. Both ends of this field are Trailblaze, so there is
     * no older sender to tolerate — and accepting a version whose layout we have not seen would
     * mean trusting ids we cannot place.
     */
    private const val VERSION = "00"

    /** The trace-flags byte, sampled bit set: we only send a context for a trace we are recording. */
    private const val SAMPLED = "01"

    private const val TRACE_ID_LENGTH = 32
    private const val SPAN_ID_LENGTH = 16

    /**
     * Reads a `traceparent`, or returns null if it is not one this can place.
     *
     * Null rather than a partial context or a thrown exception: the caller's fallback is to record
     * a trace of its own, which is what happened before any of this and still produces a usable
     * profile of its own half. A malformed field is worth a warning, not a failed run — which is
     * why the decision is the caller's to make.
     */
    fun parse(traceParent: String?): TraceContext? {
      val parts = traceParent?.split('-') ?: return null
      if (parts.size != 4) return null
      val (version, traceId, spanId, flags) = parts
      if (version != VERSION) return null
      if (!flags.isHexOfLength(2)) return null
      if (!traceId.isUsableId(TRACE_ID_LENGTH) || !spanId.isUsableId(SPAN_ID_LENGTH)) return null
      return TraceContext(traceId = traceId, spanId = spanId)
    }

    /** All-zero is the invalid id in OpenTelemetry, so it is rejected rather than joined. */
    private fun String.isUsableId(length: Int): Boolean =
      isHexOfLength(length) && any { it != '0' }

    private fun String.isHexOfLength(length: Int): Boolean =
      this.length == length && all { it in '0'..'9' || it in 'a'..'f' }
  }
}
