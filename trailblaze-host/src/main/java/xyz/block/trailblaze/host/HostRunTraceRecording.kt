package xyz.block.trailblaze.host

import xyz.block.trailblaze.tracing.TrailblazeTracer
import java.util.concurrent.atomic.AtomicInteger

/**
 * Decides when a host run may start a fresh trace recording.
 *
 * A new run wants a new recording — without one, a long-lived daemon files every run it ever serves
 * under a single trace id. But the recorder is process-wide and the daemon deliberately runs more
 * than one trail at a time, so a run that cleared it at its own start would delete the spans a run
 * already in flight had buffered: the same loss the merged trace file exists to prevent, one layer
 * down.
 *
 * So the clear happens only for a run that finds itself alone. Overlapping runs share one recording
 * and one trace id, and [begin] reports that so a caller can say so — a `trace.json` holding two
 * runs' spans is otherwise a mystery to whoever opens it.
 *
 * A recorder per session is the real fix, and would let overlapping runs each own their spans
 * outright. It means threading a recorder to every `trace { }` call site, including the ones on the
 * device; this is the part that holds without that.
 */
object HostRunTraceRecording {

  private val inFlight = AtomicInteger(0)

  /**
   * Registers a starting run, clearing the recorder when it is the only one.
   *
   * @return true when this run got a fresh recording, false when it joined one already in progress.
   */
  fun begin(): Boolean {
    val alone = inFlight.incrementAndGet() == 1
    if (alone) TrailblazeTracer.clear()
    return alone
  }

  /** Registers a finished run. Never goes below zero, so an unpaired call cannot wedge the count. */
  fun end() {
    inFlight.updateAndGet { if (it > 0) it - 1 else 0 }
  }

  /** Test seam: forget any runs a previous test left counted. */
  internal fun resetForTest() {
    inFlight.set(0)
  }
}
