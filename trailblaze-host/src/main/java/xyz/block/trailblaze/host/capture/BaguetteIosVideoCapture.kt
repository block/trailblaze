package xyz.block.trailblaze.host.capture

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureFilenames
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.IosVideoCapture
import xyz.block.trailblaze.capture.video.IosVideoStitchPlan
import xyz.block.trailblaze.capture.video.IosVideoStitcher
import xyz.block.trailblaze.capture.video.MuxResult
import xyz.block.trailblaze.capture.video.VideoSpriteExtractor
import xyz.block.trailblaze.capture.video.WallClockMp4MuxConsumer
import xyz.block.trailblaze.capture.video.WallClockVideoMux
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.recording.IosBaguetteTeeFeed
import xyz.block.trailblaze.util.Console

/**
 * **EXPERIMENTAL, opt-in — off by default.** Selected only when [IosBaguetteVideoGate] resolves on
 * (`trailblaze config ios-baguette-video true` or `TRAILBLAZE_IOS_BAGUETTE_VIDEO=1`); otherwise iOS
 * video recording stays on the shipping `xcrun simctl io recordVideo` path ([IosVideoCapture]). The
 * baguette→mux→wall-clock-PTS integration is pending on-device validation, so it does not become the
 * default until proven on a real simulator.
 *
 * Records the iOS Simulator session video from Trailblaze's **baguette H.264 stream** — the same
 * live feed the `/devices` viewer and the agent's stream-screenshots consume — muxing it to disk
 * with per-frame **host wall-clock** PTS ([WallClockMp4MuxConsumer]). When enabled it's the primary
 * iOS recorder on an Apple-Silicon Mac where baguette is installed; `xcrun simctl io recordVideo`
 * ([IosVideoCapture]) is the automatic fallback.
 *
 * ### Why not simctl as primary
 * `simctl recordVideo` produces an mp4 whose timeline has no relation to host wall-clock — the
 * report has to *guess* the mapping (`VideoSpriteExtractor.maybeRestamp`, uniform re-stamp). The
 * baguette feed hands every access unit to the host live, so we stamp each frame at its arrival
 * instant and the mp4's offset-0 anchors to a real host epoch. A session-log event at epoch `e`
 * then lands on frame `e - startTimestampMs` with zero report-side change — exactly what the
 * timeline overlay (taps, step boundaries, agent turns, view-hierarchy captures) needs.
 *
 * ### Feed lifecycle
 * - **baguette available at start:** open one [IosBaguetteTeeFeed] and drive a
 *   [WallClockMp4MuxConsumer] over its tee, writing [BAGUETTE_SEGMENT_FILENAME]. The tee is shared
 *   plumbing — the live viewer / screenshot path holds its own feed against the same multiplexed
 *   `baguette serve`, so video recording is just one more consumer.
 * - **baguette absent at start:** delegate the whole session to [IosVideoCapture] (simctl),
 *   producing today's `video.mp4` + sprite unchanged.
 * - **baguette feed dies mid-session** (WS drop, baguette crash): finalize the baguette segment,
 *   then restart recording via [IosVideoCapture] (simctl, raw mp4 [SIMCTL_REMAINDER_FILENAME]) for
 *   the remainder. At [stop] the two segments are stitched gap-preserving via [IosVideoStitchPlan]
 *   so the simctl portion sits at its true wall-clock offset.
 *
 * At [stop] the final (possibly single-segment) mp4 is written to the canonical `video.mp4` and
 * sprite-ified once — the artifact mirrors [IosVideoCapture]'s `VIDEO_FRAMES` output, just sourced
 * from a wall-clock-accurate mp4. Any failure degrades quietly (returns null, loud log) — a video
 * problem must never fail the run.
 *
 * **Orientation:** v1 sprite-ifies assuming portrait (`isLandscape = false`); rotating baguette
 * frames for a landscape simulator is a follow-up, the same open risk stream-screenshots carry
 * until validated on-device.
 */
internal class BaguetteIosVideoCapture internal constructor(
  /** Test/wiring seam for the simctl recorder used as fallback and mid-session remainder. */
  private val simctlRecorderFactory: (outputFileName: String, extractSprite: Boolean) -> CaptureStream =
    { name, sprite -> IosVideoCapture(outputFileName = name, extractSprite = sprite) },
  /**
   * Test seam for opening the baguette feed. Defaults to [IosBaguetteTeeFeed.open], which returns
   * null when baguette isn't installed. A test injects a stub to drive the feed-present vs.
   * feed-absent routing without a real simulator.
   */
  private val feedOpener: (TrailblazeDeviceId, () -> Unit) -> IosBaguetteTeeFeed? =
    { id, onFeedEnded -> IosBaguetteTeeFeed.open(id, onFeedEnded) },
  /**
   * Test seam for the wall-clock mux driven over the baguette tee. Defaults to
   * [WallClockMp4MuxConsumer]; a test injects a fake [WallClockVideoMux] to drive the start / stop /
   * mid-session-feed-death routing with no ffmpeg and no real H.264 feed.
   */
  private val muxFactory: (File, H264Tee) -> WallClockVideoMux =
    { outFile, tee -> WallClockMp4MuxConsumer(outFile, tee) },
) : CaptureStream {

  override val type = CaptureType.VIDEO

  /** Serializes every state transition (start / stop / mid-session feed-death handler). */
  private val lock = Any()

  private var sessionDir: File? = null
  private var deviceId: String? = null
  private var appId: String? = null

  // baguette path
  private var feed: IosBaguetteTeeFeed? = null
  private var mux: WallClockVideoMux? = null
  private var baguetteActive = false
  private var muxResult: MuxResult? = null

  // simctl path (whole-session fallback OR mid-session remainder)
  private var fallback: CaptureStream? = null
  private var remainderRecorder: CaptureStream? = null

  private val stopping = AtomicBoolean(false)
  private var feedDied = false

  override fun start(sessionDir: File, deviceId: String, appId: String?) = synchronized(lock) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId
    this.appId = appId

    // Guard the open itself, not just the mux start below: open() blocks on `ensureServing()` and
    // builds a standalone tee, either of which can throw. An uncaught throw here would skip the
    // simctl fallback entirely and leave the whole session unrecorded, so treat a throw exactly like
    // a null return (baguette declined) and fall back.
    val openedFeed =
      runCatching {
        feedOpener(TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.IOS)) { onBaguetteFeedEnded() }
      }
        .onFailure { Console.log("[baguette-video] opening the baguette feed threw (${it.message})") }
        .getOrNull()

    if (openedFeed == null) {
      Console.log("[baguette-video] baguette unavailable — recording iOS video via simctl")
      fallback =
        simctlRecorderFactory(CaptureFilenames.VIDEO, true).also { it.start(sessionDir, deviceId, appId) }
      return
    }

    val consumer = muxFactory(File(sessionDir, BAGUETTE_SEGMENT_FILENAME), openedFeed.tee)
    try {
      consumer.start()
    } catch (e: Exception) {
      Console.log("[baguette-video] failed to start the wall-clock mux (${e.message}) — falling back to simctl")
      runCatching { openedFeed.close() }
      fallback =
        simctlRecorderFactory(CaptureFilenames.VIDEO, true).also { it.start(sessionDir, deviceId, appId) }
      return
    }
    feed = openedFeed
    mux = consumer
    baguetteActive = true
    Console.log("[baguette-video] recording iOS video from the baguette stream (host wall-clock PTS)")
  }

  /**
   * Fired when the baguette WebSocket terminates on its own mid-session. Finalizes the baguette
   * segment and restarts recording via simctl for the remainder. A no-op during normal teardown
   * (the feed-close in [stop] also triggers this) and after the first death.
   */
  private fun onBaguetteFeedEnded() = synchronized(lock) {
    if (stopping.get() || feedDied || !baguetteActive) return
    feedDied = true
    Console.log(
      "[baguette-video] ⚠️ baguette feed ended mid-session — finalizing the baguette segment and " +
        "restarting via simctl for the remainder",
    )
    muxResult =
      runCatching { mux?.stop() }
        .onFailure { Console.error("[baguette-video] finalizing the baguette segment failed: ${it.message}") }
        .getOrNull()
    mux = null

    val sd = sessionDir
    val dev = deviceId
    if (sd != null && dev != null) {
      remainderRecorder =
        runCatching {
          simctlRecorderFactory(SIMCTL_REMAINDER_FILENAME, false).also { it.start(sd, dev, appId) }
        }
          .onFailure {
            Console.log("[baguette-video] could not start the simctl remainder recorder: ${it.message}")
          }
          .getOrNull()
    }
  }

  override fun stop(options: CaptureOptions): CaptureArtifact? = synchronized(lock) {
    // Idempotent: a second stop() must not re-run the finalize path — that would overwrite muxResult
    // to null and fire the loud "produced no video content" alarm falsely. Only the first call does
    // the work and returns the artifact.
    if (!stopping.compareAndSet(false, true)) return@synchronized null
    try {
      fallback?.let { return it.stop(options) }
      stopBaguettePath(options)
    } catch (e: Exception) {
      Console.log("[baguette-video] stop failed (${e.message}) — no video artifact; session keeps its screenshots")
      null
    }
  }

  private fun stopBaguettePath(options: CaptureOptions): CaptureArtifact? {
    // Finalize the baguette segment (unless the mid-session death handler already did).
    if (!feedDied) {
      muxResult =
        runCatching { mux?.stop() }
          .onFailure { Console.error("[baguette-video] finalizing the baguette segment failed: ${it.message}") }
          .getOrNull()
      mux = null
    }
    // baguette was the active recorder but captured zero frames — an unexpected silent failure that
    // would otherwise surface only as a missing video. Call it out loudly so it isn't mistaken for
    // "no session"; the mux's own ffmpeg log lines above carry the cause.
    if (baguetteActive && muxResult == null) {
      Console.error(
        "[baguette-video] ⚠️ baguette was the active recorder but produced no video content — " +
          "see the [WallClockMp4MuxConsumer] / [WallClockMp4MuxConsumer/ffmpeg] log lines above for why.",
      )
    }
    val remainderArtifact = remainderRecorder?.let { runCatching { it.stop(options) }.getOrNull() }
    runCatching { feed?.close() }
    feed = null

    val sd = sessionDir ?: return null
    // Only the final artifact (+ its `video.mp4` source on the sprite path) needs to survive; the
    // baguette/simctl segments and the concat script are stitch scratch. Clean them in a `finally`
    // so a failed stitch/promote/sprite doesn't leave intermediates behind to get uploaded and muddy
    // debugging (parallels MuxToMp4Consumer deleting its `.h264` segments + concat list).
    val keep = mutableSetOf<File>()
    try {
      val segments = buildList {
        muxResult?.let {
          add(IosVideoStitchPlan.VideoSegment(it.file, it.firstFrameEpochMs, it.lastFrameEpochMs))
        }
        remainderArtifact?.let {
          add(
            IosVideoStitchPlan.VideoSegment(
              file = it.file,
              startEpochMs = it.startTimestampMs,
              endEpochMs = it.endTimestampMs ?: it.startTimestampMs,
            ),
          )
        }
      }
      val plan = IosVideoStitchPlan.plan(segments)
      if (plan == null) {
        Console.log("[baguette-video] no video captured for the session")
        return null
      }

      val finalMp4 = File(sd, CaptureFilenames.VIDEO)
      if (!plan.needsConcat) {
        val only = plan.entries.single().file
        if (only != finalMp4 && !promoteToFinal(only, finalMp4)) return null
      } else if (!IosVideoStitcher.stitch(plan, sd, finalMp4)) {
        return null
      }

      val artifact = spriteOrRaw(finalMp4, plan, options) ?: return null
      keep += artifact.file
      keep += finalMp4
      return artifact
    } finally {
      cleanupIntermediates(sd, keep)
    }
  }

  /** Deletes stitch scratch files under [sessionDir], preserving anything in [keep]. */
  private fun cleanupIntermediates(sessionDir: File, keep: Set<File>) {
    listOf(
      BAGUETTE_SEGMENT_FILENAME,
      SIMCTL_REMAINDER_FILENAME,
      IosVideoStitcher.CONCAT_SCRIPT_FILENAME,
      // video.mp4 is only in `keep` when it's the returned artifact (or its sprite source). On a
      // failure/skip return (stitch failed, or spriteOrRaw declined a broken mp4) it isn't kept, so
      // clean it here rather than orphan a half-written file that report generation would re-process.
      CaptureFilenames.VIDEO,
    )
      .map { File(sessionDir, it) }
      .filter { it !in keep && it.exists() }
      .forEach { runCatching { it.delete() } }
  }

  /** Renames (or copies) the single recorded segment onto the canonical `video.mp4`. */
  private fun promoteToFinal(only: File, finalMp4: File): Boolean {
    runCatching { finalMp4.delete() }
    if (only.renameTo(finalMp4)) return true
    return runCatching { only.copyTo(finalMp4, overwrite = true); true }
      .getOrElse {
        Console.error("[baguette-video] could not place ${only.name} at ${finalMp4.name}: ${it.message}")
        false
      }
  }

  /** Sprite-ifies [finalMp4] over the plan's wall-clock window, mirroring [IosVideoCapture]'s output. */
  private fun spriteOrRaw(
    finalMp4: File,
    plan: IosVideoStitchPlan.StitchPlan,
    options: CaptureOptions,
  ): CaptureArtifact? {
    val expectedDurationMs = plan.overallEndEpochMs - plan.overallStartEpochMs
    val sprite =
      VideoSpriteExtractor.generateSpriteSheet(
        finalMp4,
        fps = options.spriteFrameFps,
        frameHeight = options.spriteFrameHeight,
        webpQuality = options.spriteQuality,
        isLandscape = false,
        expectedDurationMs = expectedDurationMs,
        // Both segment sources carry correct per-frame PTS (baguette: host wall-clock stamps;
        // simctl remainder: the simulator's own recorder), so a container shorter than the
        // wall-clock window is a static tail to tail-pad, not broken timing to re-stamp.
        vfrTimestampsTrusted = true,
      )
    if (sprite != null) {
      return CaptureArtifact(
        file = sprite,
        type = CaptureType.VIDEO_FRAMES,
        startTimestampMs = plan.overallStartEpochMs,
        endTimestampMs = plan.overallEndEpochMs,
      )
    }
    if (VideoSpriteExtractor.shouldSkipVideoFallbackForBrokenMp4(finalMp4.parentFile, "BaguetteIosVideoCapture")) {
      return null
    }
    return CaptureArtifact(
      file = finalMp4,
      type = CaptureType.VIDEO,
      startTimestampMs = plan.overallStartEpochMs,
      endTimestampMs = plan.overallEndEpochMs,
    )
  }

  companion object {
    /** Wall-clock-PTS baguette segment, before any stitch/promotion to `video.mp4`. */
    const val BAGUETTE_SEGMENT_FILENAME = "video.baguette.mp4"
    /** Raw simctl remainder recorded after a mid-session baguette feed death. */
    const val SIMCTL_REMAINDER_FILENAME = "video.simctl.mp4"
  }
}
