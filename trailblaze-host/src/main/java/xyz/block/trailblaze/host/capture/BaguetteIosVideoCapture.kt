package xyz.block.trailblaze.host.capture

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.IosScreenRotation
import xyz.block.trailblaze.capture.video.IosScreenRotationRegistry
import xyz.block.trailblaze.capture.video.IosVideoCapture
import xyz.block.trailblaze.capture.video.IosVideoStitchPlan
import xyz.block.trailblaze.capture.video.IosVideoStitcher
import xyz.block.trailblaze.capture.video.MuxResult
import xyz.block.trailblaze.capture.video.RecordingFormat
import xyz.block.trailblaze.capture.video.WallClockMuxConsumer
import xyz.block.trailblaze.capture.video.WallClockVideoMux
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.recording.IosBaguetteTeeFeed
import xyz.block.trailblaze.util.Console

/**
 * **EXPERIMENTAL, opt-in — off by default.** Selected only when [IosBaguetteVideoGate] resolves on
 * (`trailblaze config ios-baguette-video true` or `TRAILBLAZE_IOS_BAGUETTE_VIDEO=1`); otherwise iOS
 * video recording stays on the shipping `xcrun simctl io recordVideo` path ([IosVideoCapture]).
 * Opt-in is the settled arrangement, not a staging step towards becoming the default — see "Why
 * simctl stays the default" below before proposing to flip [IosBaguetteVideoGate].
 *
 * Records the iOS Simulator session video from Trailblaze's **baguette H.264 stream** — the same
 * live feed the `/devices` viewer and the agent's stream-screenshots consume — muxing it to disk
 * with per-frame **host wall-clock** PTS ([WallClockMuxConsumer]). When enabled it's the primary
 * iOS recorder on an Apple-Silicon Mac where baguette is installed; `xcrun simctl io recordVideo`
 * ([IosVideoCapture]) is the automatic fallback.
 *
 * ### Why simctl stays the default
 * This recorder was built for wall-clock accuracy: the baguette feed hands every access unit to
 * the host live, so each frame is stamped at its arrival instant and the recording's offset-0 is a
 * real host epoch by construction — a session-log event at epoch `e` lands on frame
 * `e - startTimestampMs` with no report-side guess. simctl has since caught up on its own terms:
 * once its window is anchored on its first frame rather than on the process (see
 * [IosVideoCapture]) its clip time is linear in host wall-clock with no measurable drift, tracking
 * the screen to about 13 ms. The accuracy gap this recorder existed to close is gone.
 *
 * What remains in its favour is a live encode — no stop-time transcode — and sharing a feed the
 * `/devices` viewer or stream-screenshots may already hold open. That transcode is cheap: measured
 * on an iPhone 17 Pro simulator with production's exact ffmpeg arguments, a 39 s recording of a
 * quiet screen re-encoded to VP9 WebM in 0.3 s, and a synthetic 60 fps full-motion clip of the same
 * length in about 6 s. Against that, this recorder needs `brew install baguette` on an
 * Apple-Silicon Mac — installed on no CI agent, so a default flip would change nothing there —
 * its live VP9 WebM path has never been validated on a simulator, and the feed
 * handover plus stitch below is a failure surface simctl does not have. Revisit only if
 * stream-screenshots become the iOS default: the feed is then already running, and video off it
 * is nearly free.
 *
 * ### Feed lifecycle
 * - **baguette available at start:** open one [IosBaguetteTeeFeed] and drive a
 *   [WallClockMuxConsumer] over its tee, encoding live in the process-wide [RecordingFormat] into
 *   the baguette segment ([baguetteSegmentFilename]). The tee is shared plumbing — the live viewer
 *   / screenshot path holds its own feed against the same multiplexed `baguette serve`, so video
 *   recording is just one more consumer. [start] then blocks until the first frame actually
 *   arrives, so the session never runs ahead of the footage.
 * - **baguette absent at start, or producing no frames within [firstFrameTimeoutMs]:** delegate the
 *   whole session to [IosVideoCapture] (simctl), which delivers the same canonical recording.
 * - **baguette feed dies mid-session** (WS drop, baguette crash): finalize the baguette segment,
 *   then restart recording via [IosVideoCapture] (simctl, [SIMCTL_REMAINDER_FILENAME], kept as
 *   simctl's own mp4 — the stitch re-encodes it) for the remainder. At [stop] the two segments are
 *   stitched gap-preserving via [IosVideoStitchPlan] so the simctl portion sits at its true
 *   wall-clock offset.
 *
 * At [stop] the final (possibly single-segment) recording is written to the canonical name for the
 * format (`video.webm`, or `video.mp4` on a host without a VP9 encoder) and returned as that
 * format's artifact over the plan's wall-clock window — the same shape as [IosVideoCapture]'s
 * output, just sourced from a wall-clock-accurate recording. Any failure degrades quietly (returns
 * null, loud log) — a video problem must never fail the run.
 *
 * ### Orientation
 * An iOS device hands out a portrait framebuffer whatever the screen is showing, and nothing in
 * the baguette stream says which way up it is — the only place that is knowable is the
 * accessibility tree, which the screen state reads on every capture and publishes to
 * [IosScreenRotationRegistry]. This recorder subscribes to it, applies the rotation to the encode
 * ([IosScreenRotation]), and starts a **new segment** whenever it changes, because neither a WebM
 * transpose nor an mp4 display matrix can change partway through a file. Those segments go through
 * the same stitch as a feed handover.
 *
 * Rotating mid-session is ordinary rather than exceptional here: a session in the MCP path opens
 * the first time any tool touches the device and stays open while calls arrive at arbitrary times,
 * so there is no moment at which the eventual orientation could have been sampled once. One
 * consequence is unavoidable: a file has a single frame size, so a session that spends time in both
 * orientations has to letterbox one of them onto the other's canvas.
 *
 * Two mechanics matter to that roll. The observation arrives on the screen-capture thread — inside
 * a tool call — so the recorder only queues the roll there and performs it on its own thread:
 * finalizing a segment waits on ffmpeg, and a tool call must not. And every segment records from
 * **its own feed**. The feed's tee can only seed a joiner with the last keyframe it saw, and baguette
 * sends one every ~4.5 s, so a segment attached to the running tee would begin with that stale
 * picture under current predicted frames — smeared until the next keyframe, which is exactly the
 * moment a rotation happened. A fresh WebSocket gets its own keyframe in 0.4-0.5 s (measured with a
 * second client joining a running stream, which left the first one's cadence untouched). The old
 * segment keeps recording until the new one has that frame, so nothing is lost in the swap; only
 * then are the old segment and its feed released.
 *
 * Today only the Maestro/XCUITest screen state publishes an orientation; the AXe screen state does
 * not, so an `IOS_AXE` session records as portrait whatever the device shows — exactly what every
 * iOS recording from this feed did before rotation was handled at all.
 */
internal class BaguetteIosVideoCapture internal constructor(
  /** The container the session recording is delivered in; shared by every recorder in the process. */
  private val format: RecordingFormat = RecordingFormat.preferred,
  /**
   * Test/wiring seam for the simctl recorder used as fallback and mid-session remainder. Receives
   * the filename to record and the format to deliver it in.
   */
  private val simctlRecorderFactory: (outputFileName: String, format: RecordingFormat) -> CaptureStream =
    { name, fmt -> IosVideoCapture(format = fmt, outputFileName = name) },
  /**
   * Test seam for opening the baguette feed. Defaults to [IosBaguetteTeeFeed.open], which returns
   * null when baguette isn't installed. A test injects a stub to drive the feed-present vs.
   * feed-absent routing without a real simulator.
   */
  private val feedOpener: (TrailblazeDeviceId, () -> Unit) -> IosBaguetteTeeFeed? =
    { id, onFeedEnded -> IosBaguetteTeeFeed.open(id, onFeedEnded) },
  /**
   * Test seam for the wall-clock mux driven over the baguette tee. Defaults to
   * [WallClockMuxConsumer]; a test injects a fake [WallClockVideoMux] to drive the start / stop /
   * mid-session-feed-death routing with no ffmpeg and no real H.264 feed.
   */
  private val muxFactory: (File, H264Tee, IosScreenRotation) -> WallClockVideoMux =
    { outFile, tee, rotation ->
      WallClockMuxConsumer(outFile, tee, output = format.liveMuxOutput(), rotation = rotation)
    },
  /**
   * Test/wiring seam for the device's screen rotation. Defaults to the registry the screen-state
   * capture publishes to; the subscription delivers the currently-known rotation immediately and
   * then every change.
   */
  private val rotationUpdates: (String, (IosScreenRotation) -> Unit) -> AutoCloseable =
    { instanceId, onRotation -> IosScreenRotationRegistry.subscribe(instanceId, onRotation) },
  /**
   * Where a mid-session rotation's segment roll runs. Null (production) means one daemon thread
   * owned by this recorder, created on the first roll and shut down at [stop]. A test passes a
   * same-thread executor to keep the roll synchronous with the observation that caused it.
   */
  private val rotationRollExecutor: Executor? = null,
  /**
   * How long [start] waits for the baguette feed's first frame before giving up on it and recording
   * the session with simctl instead. Generous against the measured 460-650ms so a loaded machine
   * isn't pushed onto the fallback, while still bounding how long a wedged baguette can delay a
   * session's start.
   */
  private val firstFrameTimeoutMs: Long = 5_000,
  /** Test seam for the host clock the first-frame wait is measured against. */
  private val nowMs: () -> Long = System::currentTimeMillis,
  /** Test seam for the first-frame wait's sleep, so a test doesn't spend real time in it. */
  private val sleepMs: (Long) -> Unit = { Thread.sleep(it) },
) : CaptureStream {

  override val type: CaptureType get() = format.captureType

  /** Serializes every state transition (start / stop / mid-session feed-death handler). */
  private val lock = Any()

  private var sessionDir: File? = null
  private var deviceId: String? = null
  private var appId: String? = null

  // baguette path
  private var feed: IosBaguetteTeeFeed? = null
  private var mux: WallClockVideoMux? = null
  private var baguetteActive = false

  /**
   * Finished baguette segments, in order. More than one means the screen rotated mid-session: a
   * rotation can be expressed neither by an encode filter nor by a container tag partway through a
   * file, so each orientation gets its own segment and they are stitched at [stop].
   */
  private val baguetteSegments = mutableListOf<MuxResult>()

  /** Serial number for the next baguette segment file; 0 is the one already open. */
  private var segmentIndex = 0

  /** The rotation the currently-open segment was started with. */
  private var rotation: IosScreenRotation = IosScreenRotation.NONE

  private var rotationSubscription: AutoCloseable? = null

  /**
   * The thread a rotation's segment roll runs on when no [rotationRollExecutor] was injected.
   * Created on the first rotation, so a session that never rotates — nearly all of them — never
   * starts it, and never once [stopping] is set: an observation can arrive after [stop] has shut it
   * down, and a thread made then would never be shut down. Guarded by [rollExecutorLock], not
   * [lock], because the observing thread must not wait on the recorder.
   */
  private var ownedRollExecutor: ExecutorService? = null
  private val rollExecutorLock = Any()

  /** Where to run a roll, or null once the session is stopping and there is nothing left to roll. */
  private fun rollExecutor(): Executor? = rotationRollExecutor ?: synchronized(rollExecutorLock) {
    if (stopping.get()) return@synchronized null
    ownedRollExecutor ?: Executors.newSingleThreadExecutor { task ->
      Thread(task, "baguette-video-rotation").apply { isDaemon = true }
    }.also { ownedRollExecutor = it }
  }

  // simctl path (whole-session fallback OR mid-session remainder)
  private var fallback: CaptureStream? = null
  private var remainderRecorder: CaptureStream? = null

  private val stopping = AtomicBoolean(false)
  private var feedDied = false

  /**
   * Every opened feed is tagged with a generation, so its end can be told apart from the end of a
   * feed a rotation retired on purpose. [feedGeneration] is the one [feed] carries; 0 means none.
   */
  private var nextFeedGeneration = 0
  private var feedGeneration = 0

  /**
   * Generations whose feed has reported it ended, recorded *before* taking [lock]. A first-frame
   * wait holds that lock, so a feed that dies during the wait would otherwise park its own
   * notification behind the wait it should be cutting short.
   */
  private val endedFeedGenerations: MutableSet<Int> = ConcurrentHashMap.newKeySet()

  /** A freshly opened feed, the mux recording it, and the generation its end will report. */
  private class LiveSegment(val generation: Int, val feed: IosBaguetteTeeFeed, val mux: WallClockVideoMux)

  override fun start(sessionDir: File, deviceId: String, appId: String?) = synchronized(lock) {
    this.sessionDir = sessionDir
    this.deviceId = deviceId
    this.appId = appId

    // Subscribe before anything is recording. The subscription delivers the last-known rotation
    // synchronously, so a session that opens on an already-landscape device starts its very first
    // segment rotated rather than recording sideways until the next observation arrives.
    rotationSubscription =
      runCatching { rotationUpdates(deviceId) { observed -> onRotationObserved(observed) } }
        .onFailure { Console.log("[baguette-video] could not subscribe to screen rotation: ${it.message}") }
        .getOrNull()

    val live = startSegmentOnFreshFeed(File(sessionDir, baguetteSegmentFilename(format)), rotation, deviceId)
    if (live == null) {
      Console.log("[baguette-video] baguette is not recording — recording iOS video via simctl")
      startWholeSessionFallback(sessionDir, deviceId, appId)
      return
    }
    feed = live.feed
    feedGeneration = live.generation
    mux = live.mux
    baguetteActive = true
  }

  /**
   * Opens a new baguette feed and starts recording it into [segmentFile], returning only once the
   * first frame has actually arrived — or null, with the partial work undone, when baguette declines,
   * the mux cannot start, or no frame comes within [firstFrameTimeoutMs].
   *
   * Waiting for that frame is the point. A started mux is not yet a recording one: baguette makes
   * each subscriber wait for a keyframe (measured at 390-650ms on a booted simulator, whether or not
   * another client already had the capture open), and the server health check and ffmpeg spawn cost
   * more on top. At session start, returning early hands those several hundred milliseconds to the
   * session, which spends them doing things no frame exists for — a 12s session came back with 11.1s
   * of footage. At a rotation, the old segment is still recording while this waits.
   */
  private fun startSegmentOnFreshFeed(
    segmentFile: File,
    segmentRotation: IosScreenRotation,
    deviceId: String,
  ): LiveSegment? {
    val generation = ++nextFeedGeneration
    // Guard the open itself: open() blocks on `ensureServing()` and builds a standalone tee, either
    // of which can throw. Treat a throw exactly like a null return (baguette declined), so the caller
    // still reaches its simctl fallback rather than leaving the session unrecorded.
    val openedFeed =
      runCatching {
        feedOpener(TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.IOS)) { onBaguetteFeedEnded(generation) }
      }
        .onFailure { Console.log("[baguette-video] opening the baguette feed threw (${it.message})") }
        .getOrNull()
    if (openedFeed == null) {
      Console.log("[baguette-video] baguette unavailable")
      return null
    }

    val consumer = muxFactory(segmentFile, openedFeed.tee, segmentRotation)
    try {
      consumer.start()
    } catch (e: Exception) {
      Console.log("[baguette-video] failed to start the wall-clock mux (${e.message})")
      runCatching { openedFeed.close() }
      return null
    }

    val waitedMs = awaitFirstFrame(consumer, generation)
    if (waitedMs == null) {
      Console.log("[baguette-video] the baguette feed produced no frame within ${firstFrameTimeoutMs}ms")
      runCatching { consumer.stop() }
      runCatching { openedFeed.close() }
      runCatching { segmentFile.delete() }
      return null
    }
    Console.log(
      "[baguette-video] recording ${segmentFile.name} from the baguette stream (host wall-clock PTS, " +
        "rotation=$segmentRotation); first frame after ${waitedMs}ms",
    )
    return LiveSegment(generation, openedFeed, consumer)
  }

  /**
   * Hands the whole session to simctl. Drops the rotation subscription on the way: simctl records
   * its own orientation into the file, so an observation we acted on would rotate it twice.
   */
  private fun startWholeSessionFallback(sessionDir: File, deviceId: String, appId: String?) {
    closeRotationSubscription()
    fallback =
      simctlRecorderFactory(format.canonicalFilename, format).also { it.start(sessionDir, deviceId, appId) }
  }

  private fun closeRotationSubscription() {
    runCatching { rotationSubscription?.close() }
    rotationSubscription = null
  }

  /**
   * A new screen rotation was observed on the device.
   *
   * The observation is published from inside a screen capture — a tool call's thread — and acting
   * on it means finalizing a segment, which waits on ffmpeg and can take tens of seconds when it
   * goes badly. So this only hands the observation to the roll thread and returns; the tool call
   * never waits on the recorder, not even while [start] holds [lock] through its first-frame wait.
   *
   * The one exception is the delivery the registry makes synchronously *inside* our own subscribe
   * call in [start]: that thread already holds [lock], nothing is recording yet, and the whole point
   * of that delivery is to open the very first segment already rotated — so it is applied inline.
   */
  private fun onRotationObserved(observed: IosScreenRotation) {
    if (Thread.holdsLock(lock)) {
      applyRotation(observed)
      return
    }
    // The registry may have picked this listener up just before [stop] unsubscribed it, so the
    // observation can land after teardown. A stopped session has nothing to rotate; drop it.
    val executor = rollExecutor() ?: return
    try {
      executor.execute { applyRotation(observed) }
    } catch (_: RejectedExecutionException) {
      // [stop] shut the roll thread down between the check above and this hand-off.
    }
  }

  /**
   * Rolls the recording onto a fresh segment so the new orientation can be applied, because neither
   * the WebM transpose nor the mp4 display matrix can change partway through a file.
   *
   * Only meaningful while baguette is the active recorder: the simctl paths record their own
   * orientation, and a session already tearing down has nothing left to rotate.
   */
  private fun applyRotation(observed: IosScreenRotation) = synchronized(lock) {
    if (observed == rotation) return@synchronized
    val previous = rotation
    rotation = observed
    if (!baguetteActive || stopping.get() || feedDied) return@synchronized
    Console.log("[baguette-video] screen rotated ($previous → $observed) — rolling onto a new video segment")
    rollSegmentForRotation(observed)
  }

  private fun rollSegmentForRotation(newRotation: IosScreenRotation) {
    val sd = sessionDir ?: return
    val dev = deviceId ?: return

    // The new segment gets its own feed rather than a second consumer on the running tee: a joiner
    // there is seeded with a keyframe up to ~4.5 s old and would record smeared frames until the next
    // one (see the class KDoc). The old segment keeps recording while the new feed comes up.
    val live = startSegmentOnFreshFeed(File(sd, baguetteSegmentFilename(format, ++segmentIndex)), newRotation, dev)
    if (live == null) {
      // Nothing can record the new orientation from baguette. Hand the rest of the session to simctl,
      // which rotates for itself, rather than carry on recording sideways.
      Console.error("[baguette-video] could not open a segment for the new orientation — simctl records the rest")
      baguetteActive = false
      feedDied = true
      finalizeOpenSegment()
      startSimctlRemainder()
      return
    }

    // Swap first, then release: the retired feed's end callback reports a generation that is no
    // longer [feedGeneration], so closing it cannot be mistaken for the live feed dying.
    val retiredFeed = feed
    feed = live.feed
    feedGeneration = live.generation
    finalizeOpenSegment()
    mux = live.mux
    runCatching { retiredFeed?.close() }
  }

  /**
   * Blocks until [mux] has taken its first frame, returning how long that took, or null when the
   * feed ended or [firstFrameTimeoutMs] elapsed first.
   *
   * Null is not a failure to report loudly — it means baguette is serving but not producing, and
   * the caller still has simctl. The timeout is what bounds a session's startup when baguette is
   * wedged; the feed-ended check is what keeps a *dead* feed from costing the full timeout.
   */
  private fun awaitFirstFrame(mux: WallClockVideoMux, generation: Int): Long? {
    val startedAtMs = nowMs()
    while (true) {
      if (mux.hasContent()) return nowMs() - startedAtMs
      if (generation in endedFeedGenerations) return null
      if (nowMs() - startedAtMs >= firstFrameTimeoutMs) return null
      sleepMs(FIRST_FRAME_POLL_MS)
    }
  }

  /**
   * Fired when a baguette WebSocket terminates — on its own mid-session, or because we closed it.
   * Only the live feed ending matters: that finalizes the baguette segment and restarts recording via
   * simctl for the remainder. A no-op during normal teardown (the feed-close in [stop] also triggers
   * this), after the first death, and for a feed a rotation has already retired.
   */
  private fun onBaguetteFeedEnded(generation: Int) {
    endedFeedGenerations += generation
    handleBaguetteFeedEnded(generation)
  }

  private fun handleBaguetteFeedEnded(generation: Int) = synchronized(lock) {
    if (generation != feedGeneration || stopping.get() || feedDied || !baguetteActive) return
    feedDied = true
    Console.log(
      "[baguette-video] ⚠️ baguette feed ended mid-session — finalizing the baguette segment and " +
        "restarting via simctl for the remainder",
    )
    finalizeOpenSegment()
    startSimctlRemainder()
  }

  /** Stops the open baguette mux and keeps whatever it recorded as a finished segment. */
  private fun finalizeOpenSegment() {
    runCatching { mux?.stop() }
      .onFailure { Console.error("[baguette-video] finalizing the baguette segment failed: ${it.message}") }
      .getOrNull()
      ?.let { baguetteSegments += it }
    mux = null
  }

  /**
   * Records the rest of the session with simctl, after baguette has stopped being usable. Also
   * drops the rotation subscription: simctl writes its own orientation into the file, so acting on
   * a further observation would only rotate it a second time.
   */
  private fun startSimctlRemainder() {
    closeRotationSubscription()
    val sd = sessionDir ?: return
    val dev = deviceId ?: return
    remainderRecorder =
      runCatching {
        // The remainder stays simctl's native mp4: it's stitch input, and the stitch re-encodes
        // into the session's format anyway, so a transcode here would be paid twice.
        simctlRecorderFactory(SIMCTL_REMAINDER_FILENAME, RecordingFormat.MP4).also { it.start(sd, dev, appId) }
      }
        .onFailure {
          Console.log("[baguette-video] could not start the simctl remainder recorder: ${it.message}")
        }
        .getOrNull()
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
    } finally {
      // A roll still queued behind this lock sees `stopping` and returns; nothing else may follow.
      synchronized(rollExecutorLock) { ownedRollExecutor?.shutdown() }
    }
  }

  private fun stopBaguettePath(options: CaptureOptions): CaptureArtifact? {
    closeRotationSubscription()
    // Finalize the open segment (unless the mid-session death handler already did).
    if (!feedDied) finalizeOpenSegment()
    // baguette was the active recorder but captured zero frames — an unexpected silent failure that
    // would otherwise surface only as a missing video. Call it out loudly so it isn't mistaken for
    // "no session"; the mux's own ffmpeg log lines above carry the cause.
    if (baguetteActive && baguetteSegments.isEmpty()) {
      Console.error(
        "[baguette-video] ⚠️ baguette was the active recorder but produced no video content — " +
          "see the [WallClockMuxConsumer] / [WallClockMuxConsumer/ffmpeg] log lines above for why.",
      )
    }
    // A lost remainder is the difference between a whole session and its first few seconds. The
    // window stays honest (a one-segment plan spans only the baguette segment), but the footage is
    // gone and nothing downstream says why, so say it here rather than swallowing the reason.
    val remainderArtifact = remainderRecorder?.let { recorder ->
      runCatching { recorder.stop(options) }
        .onFailure { Console.error("[baguette-video] the simctl remainder recorder failed to stop: ${it.message}") }
        .getOrNull()
        .also {
          if (it == null) {
            Console.error(
              "[baguette-video] ⚠️ the simctl remainder produced no recording — everything after the " +
                "baguette feed died is missing from this session's video.",
            )
          }
        }
    }
    runCatching { feed?.close() }
    feed = null

    val sd = sessionDir ?: return null
    // Only the final recording needs to survive; the baguette/simctl segments and the concat
    // script are stitch scratch. Clean them in a `finally` so a failed stitch/promote doesn't leave
    // intermediates behind to get uploaded and muddy debugging (parallels MuxToMp4Consumer deleting
    // its `.h264` segments + concat list).
    val keep = mutableSetOf<File>()
    try {
      val segments = buildList {
        baguetteSegments.forEach {
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

      val finalFile = File(sd, format.canonicalFilename)
      val only = if (plan.needsConcat) null else plan.entries.single().file
      // A rename cannot change a container. The lone survivor is the simctl remainder whenever the
      // baguette feed died before its mux produced a frame, and that file is always mp4 — moving it
      // to `video.webm` would publish mp4 bytes under a WebM type, which a MIME-driven consumer
      // (the report's <video>, the desktop hand-off) refuses. Re-encode instead; a one-entry plan
      // through the stitcher is exactly that encode.
      val placed = when {
        only == null -> IosVideoStitcher.stitch(plan, finalFile, format)
        only == finalFile -> true
        only.extension.equals(format.fileExtension, ignoreCase = true) -> promoteToFinal(only, finalFile)
        else -> IosVideoStitcher.stitch(plan, finalFile, format)
      }
      if (!placed) return null

      keep += finalFile
      return CaptureArtifact(
        file = finalFile,
        type = format.captureType,
        startTimestampMs = plan.overallStartEpochMs,
        endTimestampMs = plan.overallEndEpochMs,
      )
    } finally {
      cleanupIntermediates(sd, keep)
    }
  }

  /**
   * Deletes stitch scratch files under [sessionDir], preserving anything in [keep].
   *
   * Baguette segments are matched by prefix rather than listed: a session that rotates produces one
   * per orientation, and there is no bound on how many times a long-lived session can rotate.
   */
  private fun cleanupIntermediates(sessionDir: File, keep: Set<File>) {
    val scratch = sessionDir.listFiles().orEmpty()
      .filter { it.name.startsWith("$BAGUETTE_SEGMENT_BASENAME.") } +
      listOf(
        File(sessionDir, SIMCTL_REMAINDER_FILENAME),
        // The final recording is only in `keep` when it's the returned artifact. On a failure return
        // (stitch failed) it isn't kept, so clean it here rather than orphan a half-written file that
        // report generation would re-process.
        File(sessionDir, format.canonicalFilename),
      )
    scratch
      .filter { it !in keep && it.exists() }
      .forEach { runCatching { it.delete() } }
  }

  /** Renames (or copies) the single recorded segment onto the canonical recording name. */
  private fun promoteToFinal(only: File, finalFile: File): Boolean {
    runCatching { finalFile.delete() }
    if (only.renameTo(finalFile)) return true
    return runCatching { only.copyTo(finalFile, overwrite = true); true }
      .getOrElse {
        Console.error("[baguette-video] could not place ${only.name} at ${finalFile.name}: ${it.message}")
        false
      }
  }

  companion object {
    /**
     * How often [start] re-checks for the feed's first frame. Short enough that the wait adds no
     * meaningful delay of its own to a several-hundred-millisecond attach.
     */
    private const val FIRST_FRAME_POLL_MS = 20L

    /** Basename of the wall-clock-PTS baguette segment, before any stitch/promotion to the canonical recording. */
    const val BAGUETTE_SEGMENT_BASENAME = "video.baguette"

    /**
     * A baguette segment's filename: the segment is muxed live in [format], so it carries that
     * extension. [index] is 0 for the session's first segment and increments each time the screen
     * rotates, since a rotation can only be applied by starting a new file.
     */
    fun baguetteSegmentFilename(format: RecordingFormat, index: Int = 0): String {
      val suffix = if (index == 0) "" else ".$index"
      return "$BAGUETTE_SEGMENT_BASENAME$suffix.${format.fileExtension}"
    }

    /**
     * Raw simctl remainder recorded after a mid-session baguette feed death. Always simctl's own
     * mp4 regardless of the session format — it only ever feeds the stitch.
     */
    const val SIMCTL_REMAINDER_FILENAME = "video.simctl.mp4"
  }
}
