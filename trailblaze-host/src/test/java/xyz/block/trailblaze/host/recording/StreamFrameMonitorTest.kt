package xyz.block.trailblaze.host.recording

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * Behavior of the platform-neutral frame monitor: what a feed records is what a matching
 * capture gets back. Timing-sensitive cases (stall, quiet-window waits) are covered against
 * the pure gate in [StreamScreenshotGateTest]; these tests use windows small enough that
 * real sleeps stay trivial.
 */
class StreamFrameMonitorTest {

  @Test
  fun `matched result returns the exact frame the gate judged`() {
    val monitor = StreamFrameMonitor(quietWindowMs = 0)
    val frame = byteArrayOf(1, 2, 3)
    monitor.recordFrame(frame, isContentChange = true)

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 500)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
    assertContentEquals(frame, result.jpegBytes)
  }

  @Test
  fun `heartbeat frames prove liveness without resetting the quiet window`() {
    val monitor = StreamFrameMonitor(quietWindowMs = 100)
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    Thread.sleep(150)
    // A heartbeat re-emit of unchanged content arrives after the quiet window elapsed. If it
    // (wrongly) counted as a content change, the short timeout below could not match.
    monitor.recordFrame(byteArrayOf(1), isContentChange = false)

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
  }

  @Test
  fun `content change after the tree stamp refuses the pairing`() {
    // Tree stamped on the host clock (offset 0) well in the past; the change recorded now is
    // newer than the stamp by more than the latency allowance.
    val monitor = StreamFrameMonitor(quietWindowMs = 0, latencyAllowanceMs = 0)
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)

    val result = runBlocking {
      monitor.awaitFrameMatching(
        treeCapturedAtMs = System.currentTimeMillis() - 60_000,
        timeoutMs = 500,
      )
    }

    assertIs<StreamFrameMonitor.Result.Unavailable>(result)
  }

  @Test
  fun `feed-alive pings keep an old frame acceptable on a static screen`() {
    // Damage-driven encoder: the screen went static right after this frame, so no further
    // frames (not even heartbeats) arrive. The drain loop's liveness ping is what keeps the
    // gate from misreading the silence as a dead stream.
    val monitor = StreamFrameMonitor(quietWindowMs = 0, stallThresholdMs = 100)
    val frame = byteArrayOf(7)
    monitor.recordFrame(frame, isContentChange = true)
    Thread.sleep(150)
    monitor.recordFeedAlive()

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
    assertContentEquals(frame, result.jpegBytes)
  }

  @Test
  fun `no frames within the timeout is unavailable, not a hang`() {
    val monitor = StreamFrameMonitor()

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 100)
    }

    assertIs<StreamFrameMonitor.Result.Unavailable>(result)
  }
}
