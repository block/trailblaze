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
  fun `unclassified byte-different frames are dropped, not published`() {
    // A byte-different frame inside the detector's throttle window has UNKNOWN content. If it
    // were published, the gate could pair it (possibly showing NEW content) with the stale
    // change-time and hand the LLM a screenshot newer than the tree. It must never become the
    // latest frame.
    val monitor = StreamFrameMonitor(
      quietWindowMs = 0,
      confirmContentChange = { _, _ -> FrameChangeDetector.Verdict.UNCLASSIFIED },
    )
    monitor.recordFrame(byteArrayOf(9, 9, 9), isContentChange = true)

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 100)
    }

    // No frame was ever published, so the wait times out rather than matching.
    assertIs<StreamFrameMonitor.Result.Unavailable>(result)
  }

  @Test
  fun `classified-unchanged byte-different frames are published without resetting quiet`() {
    // The 60fps re-encode case: byte-different, perceptually identical. The frame must be
    // published (it's the freshest known-good pixels) and must NOT reset the quiet clock.
    // Like the real detector, the first-ever frame classifies CHANGED (nothing to compare
    // against); only subsequent frames can be UNCHANGED.
    var classified = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 100,
      confirmContentChange = { _, _ ->
        if (classified++ == 0) FrameChangeDetector.Verdict.CHANGED
        else FrameChangeDetector.Verdict.UNCHANGED
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    Thread.sleep(150)
    val fresh = byteArrayOf(2)
    monitor.recordFrame(fresh, isContentChange = true)

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
    assertContentEquals(fresh, result.jpegBytes)
  }

  @Test
  fun `a transition dropped entirely into UNCLASSIFIED does not serve the pre-transition frame`() {
    // The static-after-transition hole. A fast transition's frames all land inside the
    // detector's throttle window (UNCLASSIFIED) so none are published; on a damage-driven feed
    // the screen then goes static and NO trailing frame arrives to reclassify the true final
    // screen. The pre-transition frame must NOT be served against a tree captured on the new
    // screen — even though it is byte-quiet and older than the tree (so the newer-than-tree
    // check can't save us).
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 0,
      confirmContentChange = { _, _ ->
        // 1: pre-transition frame classifies; 2..: the transition burst is all throttled.
        if (call++ == 0) FrameChangeDetector.Verdict.CHANGED
        else FrameChangeDetector.Verdict.UNCLASSIFIED
      },
    )
    val preTransition = byteArrayOf(1)
    monitor.recordFrame(preTransition, isContentChange = true)
    // The transition burst — every frame byte-different but throttle-dropped.
    monitor.recordFrame(byteArrayOf(2), isContentChange = true)
    monitor.recordFrame(byteArrayOf(3), isContentChange = true)
    val finalScreen = byteArrayOf(4)
    monitor.recordFrame(finalScreen, isContentChange = true)

    val result = runBlocking {
      // Tree captured "now" on the post-transition screen; the stale pre-transition frame is
      // older than it, so only the pending guard keeps it from being (wrongly) accepted.
      monitor.awaitFrameMatching(treeCapturedAtMs = System.currentTimeMillis(), timeoutMs = 100)
    }

    assertIs<StreamFrameMonitor.Result.Unavailable>(result)
  }

  @Test
  fun `a feed-alive ping reclassifies the held final frame into the served frame`() {
    // Same setup, but the drain loop's liveness ping fires on the now-static screen. Once the
    // detector's throttle has cleared, the ping reclassifies the held final frame — so the
    // stream serves the TRUE post-transition screen instead of falling back.
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 0,
      confirmContentChange = { _, _ ->
        when (call++) {
          0 -> FrameChangeDetector.Verdict.CHANGED // pre-transition frame
          1 -> FrameChangeDetector.Verdict.UNCLASSIFIED // final frame throttle-dropped
          else -> FrameChangeDetector.Verdict.CHANGED // ping reclassifies it (throttle cleared)
        }
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    val finalScreen = byteArrayOf(4)
    monitor.recordFrame(finalScreen, isContentChange = true)

    // Drain-loop ping on the static screen: resolves the held frame.
    monitor.recordFeedAlive()

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 100)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
    assertContentEquals(finalScreen, result.jpegBytes)
  }

  @Test
  fun `a ping publishing an unchanged held frame does not reset the quiet window`() {
    // The held frame reclassifies UNCHANGED (freshest known-good pixels, perceptually identical
    // to the last classified frame). It must be published, but must NOT move the quiet clock.
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 100,
      confirmContentChange = { _, _ ->
        when (call++) {
          0 -> FrameChangeDetector.Verdict.CHANGED // first frame establishes latest + change time
          1 -> FrameChangeDetector.Verdict.UNCLASSIFIED // a byte-different frame dropped to pending
          else -> FrameChangeDetector.Verdict.UNCHANGED // ping: identical to the last classified
        }
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true) // content change at t0
    val held = byteArrayOf(2)
    monitor.recordFrame(held, isContentChange = true) // dropped → pending, also at ~t0
    Thread.sleep(150) // let both the quiet window and the reclassify idle grace elapse
    monitor.recordFeedAlive() // reclassifies UNCHANGED → publishes held without resetting quiet

    val result = runBlocking {
      // quietWindow=100 and the last CONTENT change was >150ms ago; if UNCHANGED had reset the
      // quiet clock, this short-timeout match could not succeed.
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
    assertContentEquals(held, result.jpegBytes)
  }

  @Test
  fun `a ping does not recover a held frame until the feed has been idle for a quiet window`() {
    // Guard against recovering a mid-transition frame: a liveness ping can fire while newer
    // frames are still draining. Until the held frame has been outstanding (no newer frame) for
    // a quiet window, a ping must NOT publish it — else the gate could accept a non-final frame.
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 100,
      confirmContentChange = { _, _ ->
        // Only three calls reach the detector: the two recordFrame classifies plus the ping that
        // passes the idle guard (the too-soon ping short-circuits before calling the detector).
        when (call++) {
          0 -> FrameChangeDetector.Verdict.CHANGED // pre-transition frame
          1 -> FrameChangeDetector.Verdict.UNCLASSIFIED // transition frame dropped
          else -> FrameChangeDetector.Verdict.CHANGED // post-grace ping reclassifies as a real change
        }
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    val held = byteArrayOf(2)
    monitor.recordFrame(held, isContentChange = true) // dropped → pending

    monitor.recordFeedAlive() // ping too soon (feed not yet idle for a quiet window) → no recovery
    val tooSoon = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }
    assertIs<StreamFrameMonitor.Result.Unavailable>(tooSoon)

    Thread.sleep(150) // now the feed has been idle past the quiet window
    // The detector will now confirm the held frame is a real change relative to the pre-transition one.
    monitor.recordFeedAlive() // classify returns CHANGED (call #2) → recovers the held frame
    val recovered = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }
    assertIs<StreamFrameMonitor.Result.Matched>(recovered)
    assertContentEquals(held, recovered.jpegBytes)
  }

  @Test
  fun `a ping while still throttled keeps the frame held until a later ping resolves it`() {
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 0,
      confirmContentChange = { _, _ ->
        when (call++) {
          0 -> FrameChangeDetector.Verdict.CHANGED // first frame
          1 -> FrameChangeDetector.Verdict.UNCLASSIFIED // final frame dropped
          2 -> FrameChangeDetector.Verdict.UNCLASSIFIED // ping #1: still throttled
          else -> FrameChangeDetector.Verdict.CHANGED // ping #2: throttle cleared
        }
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    val finalScreen = byteArrayOf(4)
    monitor.recordFrame(finalScreen, isContentChange = true) // held

    monitor.recordFeedAlive() // ping #1: still throttled → frame stays held
    val stillHeld = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }
    assertIs<StreamFrameMonitor.Result.Unavailable>(stillHeld)

    monitor.recordFeedAlive() // ping #2: resolves the held frame
    val resolved = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }
    assertIs<StreamFrameMonitor.Result.Matched>(resolved)
    assertContentEquals(finalScreen, resolved.jpegBytes)
  }

  @Test
  fun `a heartbeat while a frame is pending reclassifies it rather than serving the heartbeat bytes`() {
    // The heartbeat resolution path (distinct from the recordFeedAlive path): a heartbeat
    // (isContentChange=false) re-emits the last-sent bytes while a frame is still held pending.
    // It must resolve the HELD frame via reclassification — serving the pending bytes stamped at
    // their original receipt — and never publish its own re-emitted bytes with no change stamp.
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 100,
      confirmContentChange = { _, _ ->
        when (call++) {
          0 -> FrameChangeDetector.Verdict.CHANGED // pre-transition frame
          1 -> FrameChangeDetector.Verdict.UNCLASSIFIED // final frame throttle-dropped → pending
          else -> FrameChangeDetector.Verdict.CHANGED // heartbeat reclassifies it (throttle cleared)
        }
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    val held = byteArrayOf(2)
    monitor.recordFrame(held, isContentChange = true) // dropped → pending
    Thread.sleep(150) // let both the quiet window and the reclassify idle grace elapse
    // Heartbeat re-emit while a frame is pending: its own bytes must be discarded.
    monitor.recordFrame(byteArrayOf(9), isContentChange = false)

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
    // The held (pending) bytes are served, not the heartbeat's re-emitted bytes.
    assertContentEquals(held, result.jpegBytes)
  }

  @Test
  fun `a later classified frame supersedes a held pending frame and is served`() {
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 0,
      confirmContentChange = { _, _ ->
        when (call++) {
          0 -> FrameChangeDetector.Verdict.CHANGED // frame 1
          1 -> FrameChangeDetector.Verdict.UNCLASSIFIED // frame 2 held
          else -> FrameChangeDetector.Verdict.CHANGED // frame 3 classified → clears pending
        }
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    monitor.recordFrame(byteArrayOf(2), isContentChange = true) // held
    val newest = byteArrayOf(3)
    monitor.recordFrame(newest, isContentChange = true) // classified → supersedes pending

    val result = runBlocking {
      monitor.awaitFrameMatching(treeCapturedAtMs = null, timeoutMs = 50)
    }

    assertIs<StreamFrameMonitor.Result.Matched>(result)
    assertContentEquals(newest, result.jpegBytes)
  }

  @Test
  fun `a reclassified held frame newer than the tree is refused, not served stale`() {
    // The reclassify path stamps the change at the held frame's ORIGINAL receipt time. When that
    // is newer than the tree stamp, the frame is genuinely newer than the tree and must not be
    // paired — exercising the ContentNewerThanTree fallback the null-tree tests can't reach.
    var call = 0
    val monitor = StreamFrameMonitor(
      quietWindowMs = 0,
      latencyAllowanceMs = 0,
      confirmContentChange = { _, _ ->
        when (call++) {
          0 -> FrameChangeDetector.Verdict.CHANGED // pre-transition frame
          1 -> FrameChangeDetector.Verdict.UNCLASSIFIED // final frame dropped
          else -> FrameChangeDetector.Verdict.CHANGED // ping reclassifies as a real change
        }
      },
    )
    monitor.recordFrame(byteArrayOf(1), isContentChange = true)
    monitor.recordFrame(byteArrayOf(4), isContentChange = true) // held
    monitor.recordFeedAlive() // resolves CHANGED, stamped at the (now) final-frame receipt

    val result = runBlocking {
      // Tree captured well before the final frame arrived.
      monitor.awaitFrameMatching(
        treeCapturedAtMs = System.currentTimeMillis() - 60_000,
        timeoutMs = 100,
      )
    }

    assertIs<StreamFrameMonitor.Result.Unavailable>(result)
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
