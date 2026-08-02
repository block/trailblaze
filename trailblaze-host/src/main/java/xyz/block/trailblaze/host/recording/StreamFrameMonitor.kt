package xyz.block.trailblaze.host.recording

import kotlinx.coroutines.delay
import xyz.block.trailblaze.util.Console

/**
 * Platform-neutral core of stream-sourced screenshots: tracks the most recent frame from a
 * live device stream and pairs it with a UI-tree capture via the pure [StreamScreenshotGate].
 *
 * The per-platform feeds (see [DeviceStreamScreenshotSource]: Android `screenrecord` tee,
 * iOS baguette WebSocket, web CDP screencast) push every emitted frame through [recordFrame]
 * with the feed's byte-exact change classification; claimed changes are confirmed
 * perceptually (see [confirmContentChange]) before they reset the quiet clock. The capture
 * path then calls [awaitFrameMatching] with the tree capture's stamp.
 *
 * Clock domains: frames are stamped with host receipt time here; the tree stamp lives on the
 * "tree clock" of whoever captured it. Android's tree is stamped on the device clock, so its
 * adapter measures a real [treeClockOffsetMs]; platforms whose tree capture is host-initiated
 * (iOS, web) stamp on the host clock and use the default offset of zero.
 */
class StreamFrameMonitor(
  /** Measured `treeClockEpoch - hostEpoch` (ms), so `hostMs + offset ≈ treeClockMs`. */
  private val treeClockOffsetMs: Long = 0L,
  /**
   * The stream's content-settle window. Governs two things: the gate's content-quiet check
   * (see [StreamScreenshotGate.evaluate]) AND the idle grace before a held frame is recovered
   * (see [reclassifyPending]) — both are "the feed has settled" semantics. Tuning it per feed
   * moves recovery timing too.
   */
  private val quietWindowMs: Long = DEFAULT_QUIET_WINDOW_MS,
  private val stallThresholdMs: Long = DEFAULT_STALL_THRESHOLD_MS,
  private val latencyAllowanceMs: Long = DEFAULT_LATENCY_ALLOWANCE_MS,
  /**
   * Confirms a feed-claimed content change perceptually — `(jpegBytes, nowHostMs) -> verdict`.
   * Feeds classify byte-exactly (SHA-256 in `LiveFrameConsumer`), which is only half the
   * signal: byte-identical is proof of *no* change, but byte-different is NOT proof of change
   * on a continuously-encoding stream (iOS baguette re-encodes ~60 fps, so codec noise makes
   * every static-screen frame byte-unique and the quiet window would never elapse). Claimed
   * changes are therefore confirmed by [FrameChangeDetector] before they reset the quiet
   * clock; frames the detector can't classify (its throttle window) are held for
   * reclassification rather than published — see [recordFrame]. Injectable for tests.
   */
  private val confirmContentChange: (ByteArray, Long) -> FrameChangeDetector.Verdict =
    FrameChangeDetector()::classify,
) {

  /** Immutable (bytes, receipt-time) pair so readers never see a torn update. */
  private class FrameRecord(val jpegBytes: ByteArray, val receivedAtHostMs: Long)

  /**
   * Guards every mutation of the fields below AND every call into [confirmContentChange]. The
   * detector is stateful and not thread-safe, and [recordFrame]/[recordFeedAlive] can be
   * invoked concurrently from different feed threads (e.g. `LiveFrameConsumer`'s decode and
   * drain threads on Android; the pump coroutine on web), so both must serialize through this
   * lock. [awaitFrameMatching] only takes a consistent snapshot under it and evaluates the pure
   * gate outside, so the capture path itself holds the lock only for a few field reads — but a
   * feed thread may hold it across one perceptual decode ([confirmContentChange]), bounded to at
   * most one decode per feed event, so a snapshot read can briefly wait on that.
   */
  private val lock = Any()

  private var latestFrame: FrameRecord? = null
  private var lastContentChangeAtHostMs: Long? = null
  private var lastFeedAliveAtHostMs: Long? = null

  /**
   * The most recent byte-different frame the detector couldn't classify (its throttle window),
   * held so it can be reclassified once the throttle clears and the feed has settled — see
   * [reclassifyPending]. Non-null means a frame newer than [latestFrame] exists whose content
   * is unknown, which blocks the gate from accepting a possibly-stale [latestFrame].
   */
  private var pendingUnclassified: FrameRecord? = null

  sealed interface Result {
    class Matched(val jpegBytes: ByteArray, val frameVsTreeSkewMs: Long?) : Result
    class Unavailable(val reason: String) : Result
  }

  /**
   * Records a frame emitted by the feed, stamped with host receipt time. [isContentChange]
   * is the feed's byte-exact classification: false for liveness heartbeats (a re-emit of
   * byte-identical content), true for anything byte-different — which is then confirmed
   * perceptually via [confirmContentChange] before it resets the quiet clock (see the
   * constructor param for why). Called on the feed's drain thread.
   *
   * A byte-different frame the detector can't classify (its throttle window) is **not
   * published** as [latestFrame]: publishing it would let [awaitFrameMatching] pair a
   * possibly-changed frame with a stale change-time and hand the LLM a screenshot newer than
   * the tree. Instead it is held in [pendingUnclassified] — which blocks the gate from
   * accepting the now-possibly-stale [latestFrame] — and reclassified once the detector's
   * throttle clears AND the feed has been idle for a quiet window (by a later frame here, or by
   * [recordFeedAlive] on a feed that then goes silent — see [reclassifyPending]). Only frames
   * whose content is known — classified by the detector — become [latestFrame].
   *
   * A heartbeat re-emits the last emitted bytes, which may be exactly a frame still held in
   * [pendingUnclassified]; when a frame is pending it is resolved via [reclassifyPending]
   * rather than published with no change stamp, keeping the two resolution paths consistent.
   * The heartbeat is itself proof of life, so it refreshes the liveness clock even when
   * reclassification is still deferred by the grace/throttle.
   */
  fun recordFrame(jpegBytes: ByteArray, isContentChange: Boolean) {
    val nowHostMs = System.currentTimeMillis()
    synchronized(lock) {
      if (!isContentChange) {
        if (pendingUnclassified != null) {
          // A heartbeat proves the pipeline is alive; record it so repeated heartbeats keep the
          // stall clock fresh even while the grace/throttle defers resolving the pending frame.
          lastFeedAliveAtHostMs = nowHostMs
          reclassifyPending(nowHostMs)
        } else {
          latestFrame = FrameRecord(jpegBytes, nowHostMs)
        }
        return
      }
      val verdict = confirmContentChange(jpegBytes, nowHostMs)
      if (verdict == FrameChangeDetector.Verdict.UNCLASSIFIED) {
        // Unknown content — hold it (superseding any older pending frame) rather than
        // publishing it, and leave [latestFrame] untouched so the gate can't accept it.
        pendingUnclassified = FrameRecord(jpegBytes, nowHostMs)
        return
      }
      publishClassified(FrameRecord(jpegBytes, nowHostMs), verdict, changeAtHostMs = nowHostMs)
    }
  }

  /**
   * Records an out-of-band proof of life from the feed's drain loop — the capture pipeline
   * is attached and draining even though nothing is decoding. Damage-driven encoders (the
   * emulator's `screenrecord`) emit no frames at all for a static screen, so without this
   * signal the gate cannot tell a static screen from a dead pipeline and would refuse
   * exactly the captures a settled UI produces. Called on the feed's drain thread.
   *
   * It also drives the recovery for a fast transition whose final frame landed inside the
   * detector's throttle window and was held in [pendingUnclassified]: on a damage-driven feed
   * that then goes silent, no later frame arrives to reclassify it, so this ping does it (see
   * [reclassifyPending]).
   */
  fun recordFeedAlive() {
    val nowHostMs = System.currentTimeMillis()
    synchronized(lock) {
      lastFeedAliveAtHostMs = nowHostMs
      reclassifyPending(nowHostMs)
    }
  }

  /**
   * Publishes [frame] as the latest known-good frame and clears any held pending frame (which
   * is necessarily older). A [FrameChangeDetector.Verdict.CHANGED] additionally stamps the
   * content-change clock at [changeAtHostMs]. Caller must hold [lock].
   */
  private fun publishClassified(
    frame: FrameRecord,
    verdict: FrameChangeDetector.Verdict,
    changeAtHostMs: Long,
  ) {
    if (verdict == FrameChangeDetector.Verdict.CHANGED) lastContentChangeAtHostMs = changeAtHostMs
    pendingUnclassified = null
    latestFrame = frame
  }

  /**
   * Reclassifies the held [pendingUnclassified] frame (if any) once the feed has settled,
   * publishing it as [latestFrame] on a CHANGED/UNCHANGED verdict — stamped with its ORIGINAL
   * receipt time, since that is when the content actually arrived (using the reclassification
   * time would cause false [StreamScreenshotGate.Decision.ContentNewerThanTree] rejections in
   * the static-after-transition case this recovery exists for). A still-UNCLASSIFIED verdict
   * (the detector's throttle hadn't cleared) leaves it held for a later retry. Caller holds [lock].
   *
   * Gated on the feed being idle for a quiet window: a liveness ping can fire while newer frames
   * are still draining (`LiveFrameConsumer` pings after successful writes, not only on idle), so
   * a held frame recovered mid-transition might not be the final one — and publishing it with a
   * backdated receipt stamp would satisfy the gate's quiet window immediately, risking an accept
   * of a mid-transition frame. Any newer frame refreshes/clears [pendingUnclassified], so the
   * grace only elapses once the transition has truly settled.
   */
  private fun reclassifyPending(nowHostMs: Long) {
    val pending = pendingUnclassified ?: return
    if (nowHostMs - pending.receivedAtHostMs < quietWindowMs) return
    val verdict = confirmContentChange(pending.jpegBytes, nowHostMs)
    if (verdict == FrameChangeDetector.Verdict.UNCLASSIFIED) return
    publishClassified(pending, verdict, changeAtHostMs = pending.receivedAtHostMs)
    Console.log("[stream-screenshot] recovered a held frame via reclassification ($verdict)")
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
      // Take a consistent snapshot of all monitor state under the lock, then evaluate the pure
      // gate outside it: the bytes returned on Accept are exactly the frame the gate judged, and
      // the feed's concurrent mutations can't tear the four fields against each other. The lock
      // is held only for these reads (no suspension inside it).
      val frame: FrameRecord?
      val lastChange: Long?
      val feedAlive: Long?
      val pendingAt: Long?
      synchronized(lock) {
        frame = latestFrame
        lastChange = lastContentChangeAtHostMs
        feedAlive = lastFeedAliveAtHostMs
        pendingAt = pendingUnclassified?.receivedAtHostMs
      }
      val decision = StreamScreenshotGate.evaluate(
        nowHostMs = System.currentTimeMillis(),
        lastFrameReceivedAtHostMs = frame?.receivedAtHostMs,
        lastContentChangeAtHostMs = lastChange,
        treeCapturedAtMs = treeCapturedAtMs,
        treeClockOffsetMs = treeClockOffsetMs,
        quietWindowMs = quietWindowMs,
        stallThresholdMs = stallThresholdMs,
        latencyAllowanceMs = latencyAllowanceMs,
        lastFeedAliveAtHostMs = feedAlive,
        pendingUnclassifiedAtHostMs = pendingAt,
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
        is StreamScreenshotGate.Decision.AwaitReclassification,
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
