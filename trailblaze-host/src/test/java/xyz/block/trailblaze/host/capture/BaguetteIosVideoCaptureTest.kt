package xyz.block.trailblaze.host.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.IosScreenRotation
import xyz.block.trailblaze.capture.video.MuxResult
import xyz.block.trailblaze.capture.video.RecordingFormat
import xyz.block.trailblaze.capture.video.WallClockVideoMux
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.recording.IosBaguetteTeeFeed

/**
 * Behavioral tests for [BaguetteIosVideoCapture]'s observable routing contract: when baguette is
 * unavailable it must delegate the whole session to the simctl fallback recorder, and when baguette
 * records the session its one segment must come out as the canonical recording in the session's
 * format. The wall-clock mux and gap-preserving stitch are covered separately
 * (`WallClockMuxConsumerTest`, `IosVideoStitchPlanTest`) and need a real device/ffmpeg; here we
 * assert only the routing and delivery decisions with injected seams — no baguette, no simulator,
 * no ffmpeg.
 *
 * Mirrors `WebScreencastVideoCaptureTest` in `trailblaze-capture` (the parallel web/Electron
 * screencast recorder), which asserts the same feed-present-vs-absent contract.
 */
class BaguetteIosVideoCaptureTest {

  private val deviceId = "sim-baguette-routing-test"
  private lateinit var sessionDir: File

  @BeforeTest
  fun setUp() {
    sessionDir = Files.createTempDirectory("baguette-video-").toFile()
  }

  @AfterTest
  fun tearDown() {
    sessionDir.deleteRecursively()
  }

  /** Stand-in for the simctl [xyz.block.trailblaze.capture.video.IosVideoCapture] recorder. */
  private class RecordingFallback(
    private val artifact: CaptureArtifact? = null,
    private val onStart: () -> Unit = {},
  ) : CaptureStream {
    override val type = CaptureType.VIDEO
    var started = false
    var stopped = false

    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      started = true
      onStart()
    }

    override fun stop(options: CaptureOptions): CaptureArtifact? {
      stopped = true
      return artifact
    }
  }

  /**
   * Fake wall-clock mux — no ffmpeg, no real H.264. Reports [result] on stop; when [startError] is
   * set, [start] throws it (to drive the mux-start-failure fallback).
   */
  private class FakeMux(
    private val result: MuxResult? = null,
    private val startError: Throwable? = null,
    /**
     * Number of [hasContent] polls before the fake feed's first frame "arrives". 0 (the default)
     * is a feed producing immediately; a large number stands in for one that never produces.
     */
    private val framesAfterPolls: Int = 0,
  ) : WallClockVideoMux {
    var started = false
    var stopped = false
    var contentPolls = 0
      private set

    override fun start() {
      startError?.let { throw it }
      started = true
    }

    override fun hasContent(): Boolean = started && contentPolls++ >= framesAfterPolls

    override fun stop(): MuxResult? {
      stopped = true
      return result
    }
  }

  /** A mux whose [stop] blocks until [release] is counted down — an ffmpeg that is slow to finalize. */
  private class LatchedStopMux(private val release: CountDownLatch) : WallClockVideoMux {
    private val done = CountDownLatch(1)

    @Volatile
    var stopped = false
      private set

    override fun start() = Unit

    override fun hasContent(): Boolean = true

    override fun stop(): MuxResult? {
      release.await(10, TimeUnit.SECONDS)
      stopped = true
      done.countDown()
      return null
    }

    fun awaitStopped(timeout: Long, unit: TimeUnit): Boolean = done.await(timeout, unit)
  }

  /** A standalone [H264Tee] whose reader never starts (nothing attaches) — just a handle to pass. */
  private fun fakeTee(): H264Tee =
    H264Tee.standalone(
      deviceId = TrailblazeDeviceId(deviceId, TrailblazeDevicePlatform.IOS),
      producerFactory =
        object : H264Tee.ProducerFactory {
          override fun spawn(
            deviceId: TrailblazeDeviceId,
            videoSize: String,
            bitRate: String,
            unlimited: Boolean,
          ): H264Tee.ProducerHandle =
            object : H264Tee.ProducerHandle {
              override val input: InputStream = ByteArrayInputStream(ByteArray(0))

              override fun close() = Unit
            }
        },
    )

  /**
   * Stand-in for [xyz.block.trailblaze.capture.video.IosScreenRotationRegistry]: hands over
   * [initial] the moment the recorder subscribes (as the real registry does when the device's
   * orientation is already known), and lets a test rotate the screen afterwards.
   */
  private class FakeRotations(private val initial: IosScreenRotation? = null) {
    private val listeners = mutableListOf<(IosScreenRotation) -> Unit>()
    var subscriberCount = 0
      private set

    val seam: (String, (IosScreenRotation) -> Unit) -> AutoCloseable = { _, onRotation ->
      listeners += onRotation
      subscriberCount++
      initial?.let(onRotation)
      AutoCloseable {
        listeners -= onRotation
        subscriberCount--
      }
    }

    fun rotateTo(rotation: IosScreenRotation) = listeners.toList().forEach { it(rotation) }
  }

  /**
   * A rotation seam that keeps the callback it was given even after it is closed, the way the
   * registry's snapshot of its listeners can still call a recorder that has just unsubscribed.
   */
  private class RetainedRotationCallback {
    var callback: ((IosScreenRotation) -> Unit)? = null
    val seam: (String, (IosScreenRotation) -> Unit) -> AutoCloseable = { _, onRotation ->
      callback = onRotation
      AutoCloseable {}
    }
  }

  private fun liveRollThreads(): Int =
    Thread.getAllStackTraces().keys.count { it.name == "baguette-video-rotation" && it.isAlive }

  /**
   * Runs a rotation's segment roll on the observing thread, so a test can assert the roll's outcome
   * right after rotating. Production hands the roll to its own thread; that hand-off has its own test.
   */
  private val sameThread = Executor { it.run() }

  /** Records what each segment was opened as, so a test can assert the rotation actually applied. */
  private class SegmentLog {
    val opened = mutableListOf<Pair<String, IosScreenRotation>>()
    val muxes = mutableListOf<FakeMux>()

    fun factory(): (File, H264Tee, IosScreenRotation) -> WallClockVideoMux = { outFile, _, rotation ->
      opened += outFile.name to rotation
      outFile.writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
      FakeMux(
        result = MuxResult(outFile, firstFrameEpochMs = 1_000L * opened.size, lastFrameEpochMs = 1_000L * opened.size + 500),
      ).also { muxes += it }
    }
  }

  @Test
  fun `a screen already in landscape is recorded rotated from the first frame`() {
    // The registry hands over the last-known rotation as soon as the recorder subscribes. If the
    // recorder only reacted to later *changes*, a session on a device that was already landscape —
    // and stays landscape throughout — would record the whole thing sideways.
    val log = SegmentLog()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("simctl must not be touched while baguette records") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = log.factory(),
        rotationUpdates = FakeRotations(initial = IosScreenRotation.COUNTER_CLOCKWISE_90).seam,
      )

    capture.start(sessionDir, deviceId, appId = null)

    assertEquals(
      listOf("video.baguette.webm" to IosScreenRotation.COUNTER_CLOCKWISE_90),
      log.opened,
      "the session's first and only segment must be opened rotated",
    )
  }

  @Test
  fun `rotating mid-session rolls the recording onto a segment in the new orientation`() {
    // A rotation cannot be applied to a file already being written — neither a WebM transpose nor
    // an mp4 display matrix can change partway through — so the only way to honour it is to close
    // the current segment and open another. In a long-lived MCP session, where calls arrive at
    // arbitrary times, this is ordinary rather than exceptional.
    val log = SegmentLog()
    val rotations = FakeRotations()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("a rotation must not fall back to simctl") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = log.factory(),
        rotationUpdates = rotations.seam,
        rotationRollExecutor = sameThread,
      )

    capture.start(sessionDir, deviceId, appId = null)
    rotations.rotateTo(IosScreenRotation.CLOCKWISE_90)

    assertEquals(
      listOf(
        "video.baguette.webm" to IosScreenRotation.NONE,
        "video.baguette.1.webm" to IosScreenRotation.CLOCKWISE_90,
      ),
      log.opened,
      "the rotation must open a second segment, under its own name, in the new orientation",
    )
    assertTrue(log.muxes[0].stopped, "the pre-rotation segment must be finalized, not abandoned mid-write")
    assertFalse(log.muxes[1].stopped, "the new segment must still be recording")
  }

  @Test
  fun `a rotated segment records from its own fresh feed, and the old feed is retired only after it has a frame`() {
    // A second consumer on the running tee would be seeded with a keyframe up to ~4.5 s old (baguette's
    // keyframe interval) and record smeared frames on top of it until the next one — right at the
    // rotation. A fresh WebSocket gets its own keyframe at once, so the new segment must be recorded
    // from a newly opened feed, and the old segment must keep recording until that feed is producing.
    val events = mutableListOf<String>()
    val feeds = mutableListOf<H264Tee>()
    val rotations = FakeRotations()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("retiring the old feed must not look like the feed dying") },
        feedOpener = { _, ended ->
          val n = feeds.size
          events += "open feed $n"
          val tee = fakeTee().also { feeds += it }
          // Production's closer ends the WebSocket, and the WebSocket's end fires onFeedEnded — the same
          // callback a feed that died on its own fires. Reproduce that, or the test cannot see a roll
          // mistake its own close for a death.
          IosBaguetteTeeFeed.forTest(tee) {
            events += "close feed $n"
            ended()
          }
        },
        muxFactory = { _, tee, _ ->
          val n = feeds.indexOf(tee)
          object : WallClockVideoMux {
            override fun start() {
              events += "start mux on feed $n"
            }

            override fun hasContent(): Boolean = true.also { events += "frame on feed $n" }

            override fun stop(): MuxResult? = null.also { events += "stop mux on feed $n" }
          }
        },
        rotationUpdates = rotations.seam,
        rotationRollExecutor = sameThread,
      )

    capture.start(sessionDir, deviceId, appId = null)
    events.clear()
    rotations.rotateTo(IosScreenRotation.CLOCKWISE_90)

    assertEquals(
      listOf(
        "open feed 1",
        "start mux on feed 1",
        "frame on feed 1",
        "stop mux on feed 0",
        "close feed 0",
      ),
      events,
      "the new segment must come from a new feed, and the old one may only be released once it records",
    )
  }

  @Test
  fun `a rotation whose new feed never produces hands the rest of the session to simctl`() {
    // If the new orientation cannot be recorded from baguette, carrying on in the old segment would
    // record the rest of the session sideways. simctl rotates for itself, so it takes over.
    val clock = FakeClock()
    val remainder = RecordingFallback()
    val first = FakeMux()
    var opened = 0
    val rotations = FakeRotations()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { name, _ ->
          assertEquals(BaguetteIosVideoCapture.SIMCTL_REMAINDER_FILENAME, name)
          remainder
        },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()).also { opened++ } },
        muxFactory = { _, _, _ -> if (opened == 1) first else FakeMux(framesAfterPolls = Int.MAX_VALUE) },
        rotationUpdates = rotations.seam,
        rotationRollExecutor = sameThread,
        nowMs = clock::nowMs,
        sleepMs = clock::advance,
      )

    capture.start(sessionDir, deviceId, appId = null)
    rotations.rotateTo(IosScreenRotation.CLOCKWISE_90)

    assertTrue(first.stopped, "the pre-rotation segment must be finalized, not left recording sideways")
    assertTrue(remainder.started, "simctl must record the rest of the session")
  }

  @Test
  fun `a rotation observation returns to the screen capture before the old segment is finalized`() {
    // The observation is published from inside a screen capture, i.e. a tool call. Finalizing a
    // segment waits on ffmpeg — tens of seconds when it goes badly — and the tool call must not wait
    // with it. So the roll runs on the recorder's own thread (production wiring, no executor injected).
    val release = CountDownLatch(1)
    val first = LatchedStopMux(release)
    val muxes = mutableListOf<WallClockVideoMux>()
    val rotations = FakeRotations()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("a rotation must not fall back to simctl") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = { _, _, _ -> (if (muxes.isEmpty()) first else FakeMux()).also { muxes += it } },
        rotationUpdates = rotations.seam,
      )

    capture.start(sessionDir, deviceId, appId = null)
    rotations.rotateTo(IosScreenRotation.CLOCKWISE_90)

    // We are back on the observing thread while the old segment cannot possibly have been finalized.
    assertFalse(first.stopped, "the observation must not wait for the pre-rotation segment to finalize")

    release.countDown()
    assertTrue(first.awaitStopped(5, TimeUnit.SECONDS), "the roll must still finalize the old segment, just not on the observer's thread")
    capture.stop(CaptureOptions(captureVideo = true))
  }

  @Test
  fun `observing the same rotation again does not cut the recording`() {
    // Every screen capture publishes an observation, which in a long session is every tool call.
    // Re-opening a segment for each of them would shred the recording into unjoinable fragments.
    val log = SegmentLog()
    val rotations = FakeRotations(initial = IosScreenRotation.CLOCKWISE_90)
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("simctl must not be touched while baguette records") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = log.factory(),
        rotationUpdates = rotations.seam,
        rotationRollExecutor = sameThread,
      )

    capture.start(sessionDir, deviceId, appId = null)
    repeat(5) { rotations.rotateTo(IosScreenRotation.CLOCKWISE_90) }

    assertEquals(1, log.opened.size, "an unchanged orientation is not a new segment: ${log.opened}")
  }

  @Test
  fun `a session recorded by simctl ignores rotation entirely`() {
    // simctl writes the device's orientation into the file itself. Acting on an observation here
    // would turn the recording a second time — and there is no baguette mux to roll anyway.
    val rotations = FakeRotations(initial = IosScreenRotation.COUNTER_CLOCKWISE_90)
    val fallback = RecordingFallback()
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { _, _ -> fallback },
        feedOpener = { _, _ -> null },
        muxFactory = { _, _, _ -> error("no mux may be opened when baguette is unavailable") },
        rotationUpdates = rotations.seam,
        rotationRollExecutor = sameThread,
      )

    capture.start(sessionDir, deviceId, appId = null)
    rotations.rotateTo(IosScreenRotation.CLOCKWISE_90)

    assertTrue(fallback.started)
    assertEquals(
      0,
      rotations.subscriberCount,
      "the recorder must drop its rotation subscription when it hands the session to simctl",
    )
  }

  @Test
  fun `a finished session stops listening for rotations`() {
    // A recorder that stayed subscribed past stop would try to roll a segment onto a recording
    // that has already been written out and cleaned up.
    val log = SegmentLog()
    val rotations = FakeRotations()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("simctl must not be touched while baguette records") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = log.factory(),
        rotationUpdates = rotations.seam,
        rotationRollExecutor = sameThread,
      )

    capture.start(sessionDir, deviceId, appId = null)
    capture.stop(CaptureOptions(captureVideo = true))
    rotations.rotateTo(IosScreenRotation.HALF_TURN)

    assertEquals(0, rotations.subscriberCount, "the subscription must be released at stop")
    assertEquals(1, log.opened.size, "no segment may be opened after the session is over: ${log.opened}")
  }

  @Test
  fun `a rotation that reaches a stopped session starts no thread`() {
    // A thread made after stop would never be shut down: stop has already run its cleanup.
    val rotations = RetainedRotationCallback()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("simctl must not be touched while baguette records") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = { _, _, _ -> FakeMux() },
        rotationUpdates = rotations.seam,
      )
    capture.start(sessionDir, deviceId, appId = null)
    capture.stop(CaptureOptions(captureVideo = true))
    val before = liveRollThreads()

    rotations.callback!!(IosScreenRotation.CLOCKWISE_90)

    assertEquals(before, liveRollThreads(), "a rotation after stop must not start the roll thread")
  }

  @Test
  fun `a rotation that loses the race with stop is dropped instead of failing the screen capture`() {
    // stop() can shut the roll thread down after the observation found it running but before the
    // hand-off, and a shut-down executor rejects the task. The observation is on a screen capture's
    // thread, so that rejection must not surface as an exception in a tool call.
    val shutDown = Executors.newSingleThreadExecutor().apply { shutdown() }
    val log = SegmentLog()
    val rotations = FakeRotations()
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("simctl must not be touched while baguette records") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = log.factory(),
        rotationUpdates = rotations.seam,
        rotationRollExecutor = shutDown,
      )
    capture.start(sessionDir, deviceId, appId = null)

    rotations.rotateTo(IosScreenRotation.CLOCKWISE_90)

    assertEquals(1, log.opened.size, "a rejected roll must not open a segment: ${log.opened}")
    assertFalse(log.muxes.single().stopped, "a rejected roll must leave the open segment recording")
  }

  @Test
  fun `baguette unavailable delegates start and stop to the simctl fallback`() {
    val fallbackArtifact =
      CaptureArtifact(
        file = File(sessionDir, "video.mp4"),
        type = CaptureType.VIDEO,
        startTimestampMs = 1_000,
        endTimestampMs = 2_000,
      )
    val fallback = RecordingFallback(fallbackArtifact)
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { _, _ -> fallback },
        // baguette declines: open returns null, exactly as on a machine without baguette.
        feedOpener = { _, _ -> null },
      )

    capture.start(sessionDir, deviceId, appId = null)
    assertTrue(fallback.started, "start must delegate to the simctl fallback when baguette is unavailable")

    val artifact = capture.stop(CaptureOptions(captureVideo = true))
    assertTrue(fallback.stopped, "stop must delegate to the simctl fallback on the delegated path")
    assertSame(fallbackArtifact, artifact, "the fallback's artifact must be returned unchanged")
  }

  @Test
  fun `whole-session fallback records the canonical recording in the session's format`() {
    // The whole-session fallback (baguette absent) must record the canonical recording, matching
    // the simctl-only behavior — NOT the remainder filename — and must hand the simctl recorder the
    // session's format so a webm session doesn't silently come back as mp4.
    fun fallbackRequest(format: RecordingFormat): Pair<String, RecordingFormat> {
      var request: Pair<String, RecordingFormat>? = null
      val capture =
        BaguetteIosVideoCapture(
          format = format,
          simctlRecorderFactory = { name, fmt ->
            request = name to fmt
            RecordingFallback()
          },
          feedOpener = { _, _ -> null },
        )
      capture.start(sessionDir, deviceId, appId = null)
      return request!!
    }

    assertEquals("video.webm" to RecordingFormat.WEBM, fallbackRequest(RecordingFormat.WEBM))
    assertEquals("video.mp4" to RecordingFormat.MP4, fallbackRequest(RecordingFormat.MP4))
  }

  @Test
  fun `a single baguette segment is promoted onto the canonical webm recording`() {
    // The primary path: baguette records the whole session, muxed live as VP9 WebM. At stop the
    // one segment IS the recording — renamed onto `video.webm`, published as VIDEO_WEBM over the
    // mux's wall-clock bookends, with the segment intermediate gone.
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> error("simctl must not be touched while baguette records") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = { outFile, _, _ ->
          assertEquals("video.baguette.webm", outFile.name, "the live segment carries the session format's extension")
          outFile.writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
          FakeMux(result = MuxResult(outFile, firstFrameEpochMs = 1_000, lastFrameEpochMs = 2_000))
        },
      )

    capture.start(sessionDir, deviceId, appId = null)
    val artifact = capture.stop(CaptureOptions(captureVideo = true))

    assertNotNull(artifact, "one recorded segment is a complete recording")
    assertEquals(CaptureType.VIDEO_WEBM, artifact.type)
    assertEquals(File(sessionDir, "video.webm"), artifact.file)
    assertTrue(artifact.file.exists() && artifact.file.length() > 0, "the recording must be on disk under the canonical name")
    assertEquals(1_000, artifact.startTimestampMs)
    assertEquals(2_000, artifact.endTimestampMs)
    assertFalse(File(sessionDir, "video.baguette.webm").exists(), "the segment intermediate is cleaned up after promotion")
  }

  @Test
  fun `baguette feed death mid-session starts a simctl remainder recorder`() {
    val simctlCalls = mutableListOf<Pair<String, RecordingFormat>>()
    var capturedOnFeedEnded: (() -> Unit)? = null
    val remainder = RecordingFallback()
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { name, fmt ->
          simctlCalls += name to fmt
          remainder
        },
        feedOpener = { _, onFeedEnded ->
          capturedOnFeedEnded = onFeedEnded
          IosBaguetteTeeFeed.forTest(fakeTee())
        },
        muxFactory = { _, _, _ -> FakeMux(result = null) },
      )

    capture.start(sessionDir, deviceId, appId = null)
    assertTrue(simctlCalls.isEmpty(), "no simctl recorder while the baguette feed is alive")

    // Simulate the baguette WebSocket dropping mid-session.
    capturedOnFeedEnded!!.invoke()

    assertEquals(1, simctlCalls.size, "feed death must start exactly one simctl remainder recorder")
    assertEquals(
      BaguetteIosVideoCapture.SIMCTL_REMAINDER_FILENAME to RecordingFormat.MP4,
      simctlCalls.single(),
      "the remainder records to its own file so it doesn't clobber the baguette segment, and stays " +
        "simctl's native mp4 because the stitch re-encodes it into the session format anyway",
    )
    assertTrue(remainder.started, "the remainder recorder must be started on feed death")

    val artifact = capture.stop(CaptureOptions(captureVideo = true))
    assertTrue(remainder.stopped, "stop must finalize the remainder recorder")
    assertNull(artifact, "no frames were captured on either segment, so there's no video artifact")
  }

  @Test
  fun `a lone simctl remainder is never renamed onto the webm name it is not`() {
    // The baguette feed died before its mux produced a frame, so the only surviving segment is the
    // simctl remainder — always mp4. Placing it at `video.webm` would publish mp4 bytes under a
    // WebM capture type, and a MIME-driven consumer (the report's <video>) refuses that file. The
    // recording either gets re-encoded into the session's container or it is not published at all;
    // what must never happen is a `video.webm` holding the mp4.
    val remainderFile = File(sessionDir, BaguetteIosVideoCapture.SIMCTL_REMAINDER_FILENAME)
    val mp4Bytes = byteArrayOf(0x00, 0x00, 0x00, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte())
    var capturedOnFeedEnded: (() -> Unit)? = null
    val remainder =
      RecordingFallback(
        CaptureArtifact(
          file = remainderFile,
          type = CaptureType.VIDEO,
          startTimestampMs = 3_000,
          endTimestampMs = 4_000,
        ),
        onStart = { remainderFile.writeBytes(mp4Bytes) },
      )
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { _, _ -> remainder },
        feedOpener = { _, onFeedEnded ->
          capturedOnFeedEnded = onFeedEnded
          IosBaguetteTeeFeed.forTest(fakeTee())
        },
        muxFactory = { _, _, _ -> FakeMux(result = null) },
      )

    capture.start(sessionDir, deviceId, appId = null)
    capturedOnFeedEnded!!.invoke()
    val artifact = capture.stop(CaptureOptions(captureVideo = true))

    val webm = File(sessionDir, "video.webm")
    assertFalse(
      webm.exists() && webm.readBytes().contentEquals(mp4Bytes),
      "the simctl mp4 was renamed onto the WebM name — its bytes are still mp4",
    )
    if (artifact != null) {
      assertEquals(CaptureType.VIDEO_WEBM, artifact.type, "a published artifact must match the container it points at")
      assertFalse(artifact.file.readBytes().contentEquals(mp4Bytes), "a published WebM must not be the mp4 verbatim")
    }
  }

  @Test
  fun `feed open throwing falls back to the simctl recorder instead of leaving the session unrecorded`() {
    // open() blocks on ensureServing() and builds a tee — either can throw. A throw must be treated
    // like "baguette declined" (fall back), not propagate past start() and skip recording entirely.
    val fallbackArtifact =
      CaptureArtifact(
        file = File(sessionDir, "video.mp4"),
        type = CaptureType.VIDEO,
        startTimestampMs = 1_000,
        endTimestampMs = 2_000,
      )
    val fallback = RecordingFallback(fallbackArtifact)
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { _, _ -> fallback },
        feedOpener = { _, _ -> throw RuntimeException("baguette serve never came up") },
      )

    capture.start(sessionDir, deviceId, appId = null)
    assertTrue(fallback.started, "a throwing feed open must fall back to the simctl recorder")

    val artifact = capture.stop(CaptureOptions(captureVideo = true))
    assertSame(fallbackArtifact, artifact, "the fallback's artifact must be returned unchanged")
  }

  @Test
  fun `mux start throwing falls back to the simctl recorder`() {
    // The baguette feed opened, but the wall-clock mux couldn't start (ffmpeg missing/failed). The
    // session must still record via simctl rather than lose all video.
    val fallback = RecordingFallback()
    var feedClosed = false
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { _, _ -> fallback },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee(), closer = { feedClosed = true }) },
        muxFactory = { _, _, _ -> FakeMux(startError = RuntimeException("ffmpeg not on PATH")) },
      )

    capture.start(sessionDir, deviceId, appId = null)
    assertTrue(fallback.started, "a mux that fails to start must fall back to the simctl recorder")
    assertTrue(feedClosed, "the opened baguette feed must be closed when the mux start fails")
  }

  @Test
  fun `start does not return until frames are arriving`() {
    // baguette makes every subscriber wait for a keyframe. Returning from start() before the first
    // frame hands that wait to the session, which spends it doing things no footage exists for.
    val clock = FakeClock()
    val mux = FakeMux(framesAfterPolls = 5)
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { _, _ -> error("simctl must not be touched while baguette records") },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee()) },
        muxFactory = { _, _, _ -> mux },
        nowMs = clock::nowMs,
        sleepMs = clock::advance,
      )

    capture.start(sessionDir, deviceId, appId = null)

    assertTrue(mux.contentPolls > 1, "start must keep checking for a first frame, not assume one")
    assertTrue(clock.nowMs() > 0, "start must have waited rather than returning immediately")
  }

  @Test
  fun `a feed that never produces a frame records the session with simctl instead`() {
    // baguette is serving but not producing (a wedged capture). Waiting forever would hang the
    // session and recording anyway would deliver an empty file, so hand the whole session to simctl
    // — which is only possible because this is decided at start, not discovered at stop.
    val clock = FakeClock()
    val fallback = RecordingFallback()
    var feedClosed = false
    val mux = FakeMux(framesAfterPolls = Int.MAX_VALUE)
    val capture =
      BaguetteIosVideoCapture(
        format = RecordingFormat.WEBM,
        simctlRecorderFactory = { name, _ ->
          assertEquals("video.webm", name, "the whole session goes to simctl, not the remainder file")
          fallback
        },
        feedOpener = { _, _ -> IosBaguetteTeeFeed.forTest(fakeTee(), closer = { feedClosed = true }) },
        muxFactory = { outFile, _, _ ->
          outFile.writeBytes(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))
          mux
        },
        firstFrameTimeoutMs = 500,
        nowMs = clock::nowMs,
        sleepMs = clock::advance,
      )

    capture.start(sessionDir, deviceId, appId = null)

    assertTrue(fallback.started, "a feed producing nothing must hand the session to simctl")
    assertTrue(mux.stopped, "the unproductive mux must be stopped, not left running alongside simctl")
    assertTrue(feedClosed, "the unproductive baguette feed must be closed")
    assertFalse(
      File(sessionDir, "video.baguette.webm").exists(),
      "the empty segment must not be left behind for report generation to pick up",
    )
    assertTrue(clock.nowMs() >= 500, "start must wait the full timeout before giving up on the feed")
  }

  @Test
  fun `a feed that dies while starting gives up immediately instead of waiting out the timeout`() {
    // The feed's own end signal is the answer to "will a frame arrive?", so it must cut the wait
    // short. Otherwise a session whose feed drops at start pays the whole timeout before recording.
    val clock = FakeClock()
    val fallback = RecordingFallback()
    var onFeedEnded: (() -> Unit)? = null
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { _, _ -> fallback },
        feedOpener = { _, ended ->
          onFeedEnded = ended
          IosBaguetteTeeFeed.forTest(fakeTee())
        },
        muxFactory = { _, _, _ -> FakeMux(framesAfterPolls = Int.MAX_VALUE) },
        firstFrameTimeoutMs = 60_000,
        nowMs = clock::nowMs,
        sleepMs = { millis ->
          clock.advance(millis)
          if (clock.nowMs() >= 100) onFeedEnded?.invoke()
        },
      )

    capture.start(sessionDir, deviceId, appId = null)

    assertTrue(fallback.started, "a feed that ended before producing must hand the session to simctl")
    assertTrue(
      clock.nowMs() < 1_000,
      "the wait must end on the feed's death, not 60s later; waited ${clock.nowMs()}ms",
    )
  }

  /** Virtual clock so the first-frame wait costs a test no real time. */
  private class FakeClock {
    private var millis = 0L

    fun nowMs(): Long = millis

    fun advance(by: Long) {
      millis += by
    }
  }
}
