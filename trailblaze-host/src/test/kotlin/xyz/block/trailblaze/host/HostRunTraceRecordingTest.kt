package xyz.block.trailblaze.host

import xyz.block.trailblaze.tracing.TrailblazeTracer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The daemon runs trails concurrently and the trace recorder is process-wide, so "a new run means a
 * new recording" and "do not delete a running run's spans" are in direct tension. These pin which
 * way that resolves.
 */
class HostRunTraceRecordingTest {

  @BeforeTest
  fun reset() {
    HostRunTraceRecording.resetForTest()
    TrailblazeTracer.clear()
  }

  @AfterTest
  fun tearDown() {
    HostRunTraceRecording.resetForTest()
    TrailblazeTracer.clear()
  }

  private fun recordedCount(): Int =
    kotlinx.serialization.json.Json.parseToJsonElement(TrailblazeTracer.exportJson())
      .let { it as kotlinx.serialization.json.JsonArray }
      .size

  @Test
  fun `a run that starts alone gets a fresh recording`() {
    TrailblazeTracer.trace("left-over-from-the-last-run") { }

    assertTrue(HostRunTraceRecording.begin())

    assertEquals(0, recordedCount())
  }

  @Test
  fun `a run that starts while another is in flight keeps that run's spans`() {
    // The loss this guards: an unconditional clear at session start deleted everything the run
    // already going had buffered — the same last-writer-wins failure the merged trace file exists
    // to prevent, moved from the file into the recorder.
    HostRunTraceRecording.begin()
    TrailblazeTracer.trace("first-run-tool") { }

    assertFalse(HostRunTraceRecording.begin(), "a second concurrent run must not start a recording")

    assertEquals(1, recordedCount())
  }

  @Test
  fun `the run after the last one finishes gets a fresh recording again`() {
    // Otherwise the guard would trade one bug for another: a daemon that recorded every run it ever
    // served into a single trace after the first pair of runs overlapped once.
    HostRunTraceRecording.begin()
    HostRunTraceRecording.begin()
    HostRunTraceRecording.end()
    HostRunTraceRecording.end()

    TrailblazeTracer.trace("stale") { }

    assertTrue(HostRunTraceRecording.begin())
    assertEquals(0, recordedCount())
  }

  @Test
  fun `an unpaired end cannot wedge the count negative`() {
    // A run that fails before its `finally` — or a future caller that ends twice — must not leave
    // the counter below zero, where the next two runs would both think they were alone and the
    // second would clear the first's spans.
    HostRunTraceRecording.end()
    HostRunTraceRecording.end()

    assertTrue(HostRunTraceRecording.begin())
    TrailblazeTracer.trace("first-run-tool") { }
    assertFalse(HostRunTraceRecording.begin())
    assertEquals(1, recordedCount())
  }

  @Test
  fun `exactly one of many runs starting together clears the recorder`() {
    val runs = 16
    val ready = java.util.concurrent.CyclicBarrier(runs)
    val clears = java.util.concurrent.atomic.AtomicInteger(0)
    val threads = (0 until runs).map {
      Thread {
        ready.await()
        if (HostRunTraceRecording.begin()) clears.incrementAndGet()
      }
    }
    threads.forEach { it.start() }
    threads.forEach { it.join() }

    assertEquals(1, clears.get())
  }
}
