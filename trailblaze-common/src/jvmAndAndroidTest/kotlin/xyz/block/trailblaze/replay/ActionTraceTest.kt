package xyz.block.trailblaze.replay

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlin.test.Test

/**
 * The trace exists to attribute a request's unaccounted time, so the two things that would make it
 * lie are what these pin: a boundary recorded twice (a retried leg overwriting the first arrival,
 * which would shrink whatever gap precedes it) and a trace that keeps recording after it has been
 * emitted (the next request's marks landing in the previous request's line).
 */
class ActionTraceTest {

  @Test
  fun `no two boundaries share a wire name`() {
    // Two boundaries with the same wire name would collide in the emitted line, and because the
    // first write wins the later one would simply never appear — the gap it was added to measure
    // would silently fold into its neighbour. The enum stops a mistyped name; only this stops a
    // duplicated one.
    val duplicates = ActionTrace.Boundary.entries
      .groupBy { it.wireName }
      .filterValues { it.size > 1 }
    check(duplicates.isEmpty()) { "boundaries share a wire name: $duplicates" }
  }

  @Test
  fun `reading the gate does not arm a trace`() {
    // The RPC frame loop reads the gate before it decodes anything, so this read has to be a
    // pure query — if it armed, an untraced frame's marks would start accumulating.
    ActionTrace.begin(on = false)
    ActionTrace.gateEnabled()
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
    assertThat(ActionTrace.renderAndDisarm()).isNull()
  }

  @Test
  fun `records nothing while the gate is off`() {
    ActionTrace.begin(on = false)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
    ActionTrace.logPost(kind = "TrailblazeToolLog", ms = 40, bytes = 500)
    assertThat(ActionTrace.renderAndDisarm()).isNull()
  }

  @Test
  fun `emits every boundary in the order the request walked them`() {
    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
    ActionTrace.mark(ActionTrace.Boundary.CAPTURE_DONE)
    ActionTrace.mark(ActionTrace.Boundary.REPLY_WRITTEN)
    val line = ActionTrace.renderAndDisarm()?.first()
    assertThat(line).isNotNull()
    val order = listOf("dispatchStart", "captureDone", "replyWritten").map { line!!.indexOf(it) }
    check(order == order.sorted() && order.none { it < 0 }) {
      "boundaries out of order or missing in: $line"
    }
  }

  /**
   * t=0 is the caller's stamp, not the moment `begin()` ran. By the time the frame loop knows a
   * request is worth bracketing it has read the frame, dispatched a coroutine and decoded the
   * envelope to name the op — ingress the request paid for. Stamping t=0 inside `begin()` drops
   * that from the window and makes the request total read lower than it was, which is the one
   * number this trace exists to attribute.
   */
  @Test
  fun `boundaries are offset from the caller's arrival stamp, not from begin`() {
    // A request that arrived 50ms before anything decided to trace it.
    val arrivedNs = System.nanoTime() - 50_000_000L
    ActionTrace.begin(on = true, arrivedNs = arrivedNs)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)

    val line = ActionTrace.renderAndDisarm()!!.first()
    val offset = Regex("dispatchStart=(\\d+)").find(line)!!.groupValues[1].toInt()
    // Stamping t=0 in `begin()` would put this at ~0 and hide the ingress entirely.
    check(offset >= 50) { "ingress before begin() was dropped from the window: $line" }
  }

  @Test
  fun `the first arrival at a boundary wins`() {
    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.CAPTURE_DONE)
    Thread.sleep(15)
    ActionTrace.mark(ActionTrace.Boundary.CAPTURE_DONE)
    val line = ActionTrace.renderAndDisarm()!!.first()
    // Exactly one `captureDone=`, and it is the early one — a second write would have replaced a
    // near-zero offset with a >=15ms one and silently deleted 15ms from the following gap.
    check(Regex("captureDone=").findAll(line).count() == 1) { "boundary recorded twice: $line" }
    val offset = Regex("captureDone=(\\d+)").find(line)!!.groupValues[1].toInt()
    check(offset < 15) { "the second arrival overwrote the first: $line" }
  }

  @Test
  fun `attributes uploads to the log class that made them`() {
    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
    ActionTrace.logPost(kind = "AgentDriverLog", ms = 60, bytes = 15494)
    ActionTrace.logPost(kind = "Screenshot", ms = 47, bytes = 20948)
    val lines = ActionTrace.renderAndDisarm()!!
    assertThat(lines[0]).contains("logPosts=2")
    assertThat(lines[0]).contains("logPostMs=107")
    assertThat(lines[0]).contains("logPostBytes=36442")
    // Sorted by cost, so the biggest uploader is the first thing read.
    assertThat(lines[1]).contains("AgentDriverLog=1/60ms/15494b Screenshot=1/47ms/20948b")
  }

  /**
   * Two `run_yaml` requests can overlap: starting one only launches the previous job's
   * cancellation, and that job's teardown is `NonCancellable`, so its last boundaries can arrive
   * inside the replacement's window. The trace is a process-wide singleton by design — marks come
   * from six modules and from driver threads, so nothing request-local reaches them all — which
   * makes saying so the only honest option. A line that quietly blends two requests is worse than
   * one that admits it.
   */
  @Test
  fun `a trace that displaced an unfinished one says so`() {
    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
    // The replacement request, arriving before the first one emitted.
    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)

    assertThat(ActionTrace.renderAndDisarm()!!.first()).contains("displacedOpenTrace=1")
  }

  @Test
  fun `a trace that follows a finished one is not marked as displaced`() {
    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
    ActionTrace.renderAndDisarm()

    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)

    assertThat(ActionTrace.renderAndDisarm()!!.first()).contains("displacedOpenTrace=0")
  }

  @Test
  fun `a trace stops recording once it has been emitted`() {
    ActionTrace.begin(on = true)
    ActionTrace.mark(ActionTrace.Boundary.DISPATCH_START)
    ActionTrace.renderAndDisarm()
    ActionTrace.mark(ActionTrace.Boundary.REPLY_WRITTEN)
    assertThat(ActionTrace.renderAndDisarm()).isNull()
  }
}
