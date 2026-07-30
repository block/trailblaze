package xyz.block.trailblaze.host.recording

/**
 * Pure decision logic for pairing a live device-stream frame with a UI-tree capture.
 *
 * Platform-neutral: nothing here knows where frames come from (Android `screenrecord` tee,
 * iOS baguette WebSocket, web CDP screencast) or how the tree was captured. Feeds supply the
 * observations via [StreamFrameMonitor]; this function only reasons about their timing.
 *
 * The consistency argument is **dual-quiet + liveness**, not timestamp equality: the device
 * streams are damage-driven (a static screen emits no new content), so once the stream has
 * been content-quiet for a window AND the pipeline is provably alive (frames or feed
 * liveness pings keep arriving), the latest frame *is* the current screen. The tree capture
 * ran behind its own settle gate, so a quiet stream around the tree-capture instant means
 * frame and tree describe the same screen.
 *
 * Timestamps close the one hole quiet-detection can't see: the screen changing *after* the
 * tree was captured. The tree carries a capture stamp on the "tree clock" — the clock of
 * whatever stamped it. On Android that's the device clock
 * ([xyz.block.trailblaze.mcp.android.ondevice.rpc.GetScreenStateResponse.capturedAtDeviceMs],
 * mapped from host time via a measured offset); on platforms whose tree capture is
 * host-initiated (iOS, web) the tree clock IS the host clock and the offset is zero. A
 * content change whose tree-clock time is later than the tree stamp (beyond the
 * encode/transport latency allowance) means the cached frame shows newer content than the
 * tree describes.
 *
 * All inputs are plain values so the gate is unit-testable without a device, a stream, or a
 * clock — the IO wrapper ([StreamFrameMonitor]) samples state and polls this function.
 */
object StreamScreenshotGate {

  sealed interface Decision {
    /** No frame has arrived yet (stream still warming up) — keep waiting. */
    data object AwaitFirstFrame : Decision

    /** Screen content changed too recently — keep waiting for the quiet window to elapse. */
    data class AwaitQuiet(val remainingQuietMs: Long) : Decision

    /**
     * A byte-different frame the detector couldn't classify is outstanding: the latest
     * known-good frame may be stale, so we hold until that frame is reclassified into the
     * latest frame (or superseded). Distinct from [AwaitQuiet] so a hold-driven timeout is
     * attributable to this mechanism rather than misread as an ordinary quiet-window wait.
     * [pendingForMs] is how long the outstanding frame has gone unresolved.
     */
    data class AwaitReclassification(val pendingForMs: Long) : Decision

    /**
     * Neither a frame nor a feed liveness ping has arrived within the stall threshold — the
     * capture pipeline is dead, so the cached frame can't be trusted to be current. A static
     * screen alone must not land here: damage-driven encoders emit no frames for it, which
     * is exactly why feeds report liveness out-of-band (see `lastFeedAliveAtHostMs`).
     */
    data class Stalled(val silentForMs: Long) : Decision

    /**
     * The screen changed *after* the tree was captured: the latest frame shows newer content
     * than the tree describes. Terminal for this tree capture — waiting longer can't fix it.
     */
    data class ContentNewerThanTree(val contentChangeAfterTreeMs: Long) : Decision

    /**
     * Frame accepted as matching the tree capture. [frameVsTreeSkewMs] is the tree-clock
     * delta between the latest frame's receipt and the tree capture (positive = frame
     * received after the tree stamp); null when the tree capture wasn't stamped.
     */
    data class Accept(val frameVsTreeSkewMs: Long?) : Decision
  }

  /**
   * @param nowHostMs Host wall-clock now.
   * @param lastFrameReceivedAtHostMs Host receipt time of the most recent frame of any kind
   *   (content changes AND heartbeat repeats). Null when no frame has arrived yet.
   * @param lastContentChangeAtHostMs Host receipt time of the most recent frame whose content
   *   differed from its predecessor. Null when no frame has arrived yet.
   * @param lastFeedAliveAtHostMs Host time of the feed's most recent out-of-band proof of
   *   life — its drain loop confirming the capture pipeline is attached and draining even
   *   when nothing decodes (a damage-driven encoder emits no frames for a static screen, so
   *   frame arrival alone can't distinguish "static screen" from "dead pipeline"). Null when
   *   the feed doesn't report liveness; the last frame receipt then stands in.
   * @param treeCapturedAtMs Tree-clock stamp of the tree capture; null when unstamped (e.g.
   *   an older on-device server — the timestamp check is skipped, dual-quiet still applies).
   * @param treeClockOffsetMs Measured `treeClockEpoch - hostEpoch`, so
   *   `hostMs + offset ≈ treeClockMs`. Zero when the tree stamp is on the host clock.
   * @param quietWindowMs How long the stream content must be unchanged before the latest
   *   frame is considered settled.
   * @param stallThresholdMs Max silence (no frames AND no liveness pings) before the stream
   *   is declared dead. Must comfortably exceed the feed's idle liveness cadence.
   * @param latencyAllowanceMs Slack for encode + transport delay when comparing a host-side
   *   content-change observation against the tree stamp. A change *observed* at host time T
   *   happened on-screen at T-minus-latency, and the latency is unmeasured.
   * @param pendingUnclassifiedAtHostMs Host receipt time of the most recent byte-different frame
   *   the detector couldn't classify (its throttle window), which has NOT yet been reclassified
   *   into [lastFrameReceivedAtHostMs]. Non-null means a frame newer than the latest KNOWN-good
   *   one exists whose content is unknown — so the cached frame may already be stale. The gate
   *   refuses to Accept while this is set (it still proves liveness for the stall check). Null
   *   once no such frame is outstanding. See [StreamFrameMonitor.recordFrame]/[recordFeedAlive].
   */
  fun evaluate(
    nowHostMs: Long,
    lastFrameReceivedAtHostMs: Long?,
    lastContentChangeAtHostMs: Long?,
    treeCapturedAtMs: Long?,
    treeClockOffsetMs: Long,
    quietWindowMs: Long,
    stallThresholdMs: Long,
    latencyAllowanceMs: Long,
    lastFeedAliveAtHostMs: Long? = null,
    pendingUnclassifiedAtHostMs: Long? = null,
  ): Decision {
    // Nothing usable yet: no known frame and no outstanding unclassified one either.
    if (lastFrameReceivedAtHostMs == null && pendingUnclassifiedAtHostMs == null) {
      return Decision.AwaitFirstFrame
    }

    // A dropped-unclassified frame is still a frame that arrived, so it counts as a sign of life.
    val lastSignOfLifeMs = maxOf(
      lastFrameReceivedAtHostMs ?: Long.MIN_VALUE,
      lastFeedAliveAtHostMs ?: Long.MIN_VALUE,
      pendingUnclassifiedAtHostMs ?: Long.MIN_VALUE,
    )
    val silentForMs = nowHostMs - lastSignOfLifeMs
    if (silentForMs > stallThresholdMs) return Decision.Stalled(silentForMs)

    // A byte-different frame we couldn't classify is newer than the latest known-good frame, so
    // the cached bytes may no longer describe the current screen. Never pair the tree with a
    // possibly-stale frame — hold until the pending frame is reclassified into the latest frame
    // (or superseded by a newer classified one). On a damage-driven feed that then goes silent,
    // the reclassification is driven by the drain loop's liveness ping; if it never resolves the
    // wait times out and the caller falls back to a direct screenshot. This closes the
    // static-after-transition hole where a fast transition's frames were all dropped
    // UNCLASSIFIED and no trailing frame re-classified the true final screen.
    //
    // The terminal ContentNewerThanTree fast-fail below is intentionally deferred while a frame
    // is pending: we can't tell whether the unresolved frame is newer than the tree until it's
    // classified, so on a wedged feed we pay the poll timeout rather than risk a wrong verdict.
    if (pendingUnclassifiedAtHostMs != null) {
      return Decision.AwaitReclassification(nowHostMs - pendingUnclassifiedAtHostMs)
    }

    // Past the pending guard we always have a real latest frame (both-null returned above).
    val latestFrameAtHostMs = lastFrameReceivedAtHostMs!!
    val lastChangeHostMs = lastContentChangeAtHostMs ?: latestFrameAtHostMs
    val quietForMs = nowHostMs - lastChangeHostMs
    if (quietForMs < quietWindowMs) return Decision.AwaitQuiet(quietWindowMs - quietForMs)

    if (treeCapturedAtMs != null) {
      val changeTreeClockMs = lastChangeHostMs + treeClockOffsetMs
      val changeAfterTreeMs = changeTreeClockMs - latencyAllowanceMs - treeCapturedAtMs
      if (changeAfterTreeMs > 0) return Decision.ContentNewerThanTree(changeAfterTreeMs)
    }

    val skewMs = treeCapturedAtMs?.let {
      (latestFrameAtHostMs + treeClockOffsetMs) - it
    }
    return Decision.Accept(skewMs)
  }
}
