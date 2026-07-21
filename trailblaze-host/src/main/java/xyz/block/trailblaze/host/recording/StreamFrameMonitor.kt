package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.delay

/**
 * Platform-neutral core of stream-sourced screenshots: tracks the most recent frame from a
 * live device stream and pairs it with a UI-tree capture via the pure [StreamScreenshotGate].
 *
 * A platform feed adapter (Android: [StreamScreenshotSource] over the `screenrecord` H.264
 * tee; future iOS: the baguette WebSocket; future web: CDP screencast) pushes every emitted
 * frame through [recordFrame], flagging whether it is a content change or a liveness
 * heartbeat — the feed already knows (it deduplicates frames to implement heartbeats), so
 * this class never hashes bytes itself. The capture path then calls [awaitFrameMatching]
 * with the tree capture's stamp.
 *
 * Clock domains: frames are stamped with host receipt time here; the tree stamp lives on the
 * "tree clock" of whoever captured it. Android's tree is stamped on the device clock, so its
 * adapter measures a real [treeClockOffsetMs]; platforms whose tree capture is host-initiated
 * (iOS, web) stamp on the host clock and use the default offset of zero.
 */
class StreamFrameMonitor(
  /** Measured `treeClockEpoch - hostEpoch` (ms), so `hostMs + offset ≈ treeClockMs`. */
  private val treeClockOffsetMs: Long = 0L,
  /** See [StreamScreenshotGate.evaluate]. Tune per feed if its cadence differs. */
  private val quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS,
  private val stallThresholdMs: Long = DEFAULT_STALL_THRESHOLD_MS,
  private val latencyAllowanceMs: Long = DEFAULT_LATENCY_ALLOWANCE_MS,
) {

  /** Immutable (bytes, receipt-time) pair so readers never see a torn update. */
  private class FrameRecord(val jpegBytes: ByteArray, val receivedAtHostMs: Long)

  @Volatile private var latestFrame: FrameRecord? = null
  @Volatile private var lastContentChangeAtHostMs: Long? = null
  @Volatile private var lastFeedAliveAtHostMs: Long? = null

  sealed interface Result {
    class Matched(val jpegBytes: ByteArray, val frameVsTreeSkewMs: Long?) : Result
    class Unavailable(val reason: String) : Result
  }

  /**
   * Records a frame emitted by the feed, stamped with host receipt time. [isContentChange]
   * is false for liveness heartbeats (a re-emit of unchanged content). Called on the feed's
   * drain thread.
   *
   * Write order matters: the content-change stamp is published before the frame itself so
   * [awaitFrameMatching]'s snapshot (which reads in the opposite order) can only ever pair a
   * frame with a change-time of the same age or newer — biasing the gate toward waiting,
   * never toward accepting a stale pairing.
   */
  fun recordFrame(jpegBytes: ByteArray, isContentChange: Boolean) {
    val nowHostMs = System.currentTimeMillis()
    if (isContentChange) {
      lastContentChangeAtHostMs = nowHostMs
    }
    latestFrame = FrameRecord(jpegBytes, nowHostMs)
  }

  /**
   * Records an out-of-band proof of life from the feed's drain loop — the capture pipeline
   * is attached and draining even though nothing is decoding. Damage-driven encoders (the
   * emulator's `screenrecord`) emit no frames at all for a static screen, so without this
   * signal the gate cannot tell a static screen from a dead pipeline and would refuse
   * exactly the captures a settled UI produces. Called on the feed's drain thread.
   */
  fun recordFeedAlive() {
    lastFeedAliveAtHostMs = System.currentTimeMillis()
  }

  /**
   * Waits (bounded by [timeoutMs]) for the stream to reach a state where its latest frame
   * provably matches the tree capture stamped [treeCapturedAtMs] (tree clock; null when
   * unstamped), then returns that exact frame. Returns [Result.Unavailable] — caller falls
   * back to a direct screenshot capture — when the stream is stalled, the screen changed
   * after the tree capture, or the timeout elapses.
   */
  suspend fun awaitFrameMatching(treeCapturedAtMs: Long?, timeoutMs: Long): Result {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
      // Snapshot before evaluating so the bytes returned on Accept are exactly the frame
      // the gate judged — a frame arriving mid-decision must not be returned against the
      // already-proven quiet window. Frame is read before change-time (see [recordFrame]
      // for why this order is the safe one).
      val frame = latestFrame
      val lastChange = lastContentChangeAtHostMs
      val decision = StreamScreenshotGate.evaluate(
        nowHostMs = System.currentTimeMillis(),
        lastFrameReceivedAtHostMs = frame?.receivedAtHostMs,
        lastContentChangeAtHostMs = lastChange,
        treeCapturedAtMs = treeCapturedAtMs,
        treeClockOffsetMs = treeClockOffsetMs,
        quietWindowMs = quietWindowMs,
        stallThresholdMs = stallThresholdMs,
        latencyAllowanceMs = latencyAllowanceMs,
        lastFeedAliveAtHostMs = lastFeedAliveAtHostMs,
      )
      when (decision) {
        is StreamScreenshotGate.Decision.Accept ->
          // Non-null whenever the gate can Accept — it saw this frame's receipt time.
          return Result.Matched(checkNotNull(frame).jpegBytes, decision.frameVsTreeSkewMs)
        is StreamScreenshotGate.Decision.Stalled ->
          return Result.Unavailable(
            "stream stalled (${decision.silentForMs}ms without a frame or liveness ping)",
          )
        is StreamScreenshotGate.Decision.ContentNewerThanTree ->
          return Result.Unavailable(
            "screen changed ${decision.contentChangeAfterTreeMs}ms after the tree capture",
          )
        is StreamScreenshotGate.Decision.AwaitFirstFrame,
        is StreamScreenshotGate.Decision.AwaitQuiet,
        -> {
          if (System.currentTimeMillis() >= deadline) {
            return Result.Unavailable("timed out after ${timeoutMs}ms in state $decision")
          }
          delay(POLL_INTERVAL_MS)
        }
      }
    }
  }

  companion object {
    /**
     * Content must be unchanged this long before the latest frame counts as settled. Kept
     * below typical tree-capture settle caps so the stream wait usually overlaps the tree
     * capture's own settle rather than adding to it.
     */
    const val DEFAULT_QUIET_WINDOW_MS = 300L

    /**
     * Feeds prove liveness at ≥ ~1 Hz even for a static screen — via frames when the encoder
     * emits them, and via [recordFeedAlive] drain-loop pings when it doesn't (damage-driven
     * encoders like the emulator's `screenrecord` go fully silent on a static screen). 3s of
     * total silence therefore means the pipeline died, not that the screen is static. A feed
     * with a slower idle cadence must raise this.
     */
    const val DEFAULT_STALL_THRESHOLD_MS = 3_000L

    /** Slack for encode + transport + decode when comparing a host-observed content change
     *  against the tree stamp. */
    const val DEFAULT_LATENCY_ALLOWANCE_MS = 500L

    private const val POLL_INTERVAL_MS = 25L
  }
}
