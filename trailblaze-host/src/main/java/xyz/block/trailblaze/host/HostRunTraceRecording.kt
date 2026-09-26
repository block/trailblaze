package xyz.block.trailblaze.host

import xyz.block.trailblaze.tracing.TrailblazeTracer

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

  // A lock, not an atomic counter: [endAndDrainIfLast] must decide "last one out" and drain in one
  // step, or a run beginning in between would clear the spans being drained, or have its own first
  // spans drained into someone else's file.
  private val lock = Any()
  private var inFlight = 0

  /**
   * Registers a starting run, clearing the recorder when it is the only one.
   *
   * @return true when this run got a fresh recording, false when it joined one already in progress.
   */
  fun begin(): Boolean = synchronized(lock) {
    inFlight++
    val alone = inFlight == 1
    if (alone) TrailblazeTracer.clear()
    alone
  }

  /**
   * Registers a finished run. Never goes below zero, so an unpaired call cannot wedge the count.
   *
   * @return true when no run is left recording.
   */
  fun end(): Boolean = synchronized(lock) {
    if (inFlight > 0) inFlight--
    inFlight == 0
  }

  /**
   * [end], draining the recorder in the same step whether or not another run is still recording.
   *
   * For a trail run, which exports everything recorded when it ends. As two steps, a CLI command
   * finishing between them would find the run still counted, leave its tail for the run, and then
   * the run — having already drained — would leave it in the recorder for the next run to clear.
   */
  fun endAndDrain(): String = synchronized(lock) {
    if (inFlight > 0) inFlight--
    TrailblazeTracer.traceRecorder.drain()
  }

  /**
   * [end], and when this was the last run recording, drains the recorder in the same step.
   *
   * @return the drained trace JSON, or null when another run is still recording and needs the
   *   spans left where they are.
   */
  fun endAndDrainIfLast(): String? = synchronized(lock) {
    if (inFlight > 0) inFlight--
    if (inFlight == 0) TrailblazeTracer.traceRecorder.drain() else null
  }

  /** Test seam: forget any runs a previous test left counted. */
  internal fun resetForTest() {
    synchronized(lock) { inFlight = 0 }
  }
}
