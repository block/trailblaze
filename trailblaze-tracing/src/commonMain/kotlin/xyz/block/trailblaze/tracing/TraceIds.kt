package xyz.block.trailblaze.tracing

import kotlin.random.Random

/**
 * Trace and span identifiers, in the OpenTelemetry shape: lowercase hex, fixed width — 16 bytes
 * (32 characters) for a trace, 8 bytes (16 characters) for a span. Random rather than sequential so
 * ids are unique across processes — a
 * host run, the daemon it talks to and an on-device run all record into their own recorder, and
 * their events have to be mergeable into one tree without colliding.
 *
 * All-zero is the invalid id in OpenTelemetry, so it is never generated.
 */
internal object TraceIds {
  fun newTraceId(): String = "${nonZeroLong().toHex()}${nonZeroLong().toHex()}"

  fun newSpanId(): String = nonZeroLong().toHex()

  private fun nonZeroLong(): Long {
    var value = Random.nextLong()
    while (value == 0L) value = Random.nextLong()
    return value
  }

  private fun Long.toHex(): String = toULong().toString(16).padStart(16, '0')
}
