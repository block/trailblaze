package xyz.block.trailblaze.host.capture

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType
import xyz.block.trailblaze.capture.video.H264Tee
import xyz.block.trailblaze.capture.video.MuxResult
import xyz.block.trailblaze.capture.video.WallClockVideoMux
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.recording.IosBaguetteTeeFeed

/**
 * Behavioral tests for [BaguetteIosVideoCapture]'s observable routing contract: when baguette is
 * unavailable it must delegate the whole session to the simctl fallback recorder. The wall-clock
 * mux and gap-preserving stitch are covered separately (`WallClockMp4MuxConsumerTest`,
 * `IosVideoStitchPlanTest`) and need a real device/ffmpeg; here we assert only the routing decision
 * with injected seams — no baguette, no simulator, no ffmpeg.
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
  private class RecordingFallback(private val artifact: CaptureArtifact? = null) : CaptureStream {
    override val type = CaptureType.VIDEO
    var started = false
    var stopped = false

    override fun start(sessionDir: File, deviceId: String, appId: String?) {
      started = true
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
  ) : WallClockVideoMux {
    var started = false

    override fun start() {
      startError?.let { throw it }
      started = true
    }

    override fun stop(): MuxResult? = result
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

  @Test
  fun `baguette unavailable delegates start and stop to the simctl fallback`() {
    val fallbackArtifact =
      CaptureArtifact(
        file = File(sessionDir, "video.mp4"),
        type = CaptureType.VIDEO_FRAMES,
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
  fun `whole-session fallback records to video mp4 with sprite extraction on`() {
    // The whole-session fallback (baguette absent) must record the canonical video.mp4 with sprite
    // extraction enabled, matching today's simctl-only behavior — NOT the raw-remainder settings.
    var capturedFileName: String? = null
    var capturedExtractSprite: Boolean? = null
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { name, extractSprite ->
          capturedFileName = name
          capturedExtractSprite = extractSprite
          RecordingFallback()
        },
        feedOpener = { _, _ -> null },
      )

    capture.start(sessionDir, deviceId, appId = null)

    assertEquals("video.mp4", capturedFileName, "whole-session fallback writes the canonical video.mp4")
    assertEquals(true, capturedExtractSprite, "whole-session fallback keeps sprite extraction on")
  }

  @Test
  fun `baguette feed death mid-session starts a raw simctl remainder recorder`() {
    val simctlCalls = mutableListOf<Pair<String, Boolean>>()
    var capturedOnFeedEnded: (() -> Unit)? = null
    val remainder = RecordingFallback()
    val capture =
      BaguetteIosVideoCapture(
        simctlRecorderFactory = { name, extractSprite ->
          simctlCalls += name to extractSprite
          remainder
        },
        feedOpener = { _, onFeedEnded ->
          capturedOnFeedEnded = onFeedEnded
          IosBaguetteTeeFeed.forTest(fakeTee())
        },
        muxFactory = { _, _ -> FakeMux(result = null) },
      )

    capture.start(sessionDir, deviceId, appId = null)
    assertTrue(simctlCalls.isEmpty(), "no simctl recorder while the baguette feed is alive")

    // Simulate the baguette WebSocket dropping mid-session.
    capturedOnFeedEnded!!.invoke()

    assertEquals(1, simctlCalls.size, "feed death must start exactly one simctl remainder recorder")
    assertEquals(
      BaguetteIosVideoCapture.SIMCTL_REMAINDER_FILENAME to false,
      simctlCalls.single(),
      "the remainder is the raw simctl file with sprite extraction OFF (stitched + sprited once at stop)",
    )
    assertTrue(remainder.started, "the remainder recorder must be started on feed death")

    val artifact = capture.stop(CaptureOptions(captureVideo = true))
    assertTrue(remainder.stopped, "stop must finalize the remainder recorder")
    assertNull(artifact, "no frames were captured on either segment, so there's no video artifact")
  }

  @Test
  fun `feed open throwing falls back to the simctl recorder instead of leaving the session unrecorded`() {
    // open() blocks on ensureServing() and builds a tee — either can throw. A throw must be treated
    // like "baguette declined" (fall back), not propagate past start() and skip recording entirely.
    val fallbackArtifact =
      CaptureArtifact(
        file = File(sessionDir, "video.mp4"),
        type = CaptureType.VIDEO_FRAMES,
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
        muxFactory = { _, _ -> FakeMux(startError = RuntimeException("ffmpeg not on PATH")) },
      )

    capture.start(sessionDir, deviceId, appId = null)
    assertTrue(fallback.started, "a mux that fails to start must fall back to the simctl recorder")
    assertTrue(feedClosed, "the opened baguette feed must be closed when the mux start fails")
  }
}
