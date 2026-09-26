package xyz.block.trailblaze.capture.video

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.CaptureStream
import xyz.block.trailblaze.capture.model.CaptureArtifact
import xyz.block.trailblaze.capture.model.CaptureType

/**
 * The clock the Android recording's window is on, and the stop path around it. A fake mux stands in
 * for the real one, so no `screenrecord` and no ffmpeg are involved — only the routing and the
 * arithmetic this class owns. `start` does still shell out to adb for the display size, against a
 * device id that resolves to nothing; that call is expected to fail and fall back to its default,
 * which is all these tests need from it.
 */
class AndroidVideoCaptureTest {

  private lateinit var tempDir: File

  @BeforeTest
  fun setUp() {
    H264Tee.resetRegistryForTests()
    tempDir = Files.createTempDirectory("android-video-capture-").toFile()
  }

  @AfterTest
  fun tearDown() {
    H264Tee.resetRegistryForTests()
    tempDir.deleteRecursively()
  }

  @Test
  fun `the recording window is the mux's host-clock frame epochs, uncorrected`() {
    val recording = File(tempDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    val firstFrameEpochMs = 1_700_000_000_123L
    val lastFrameEpochMs = 1_700_000_042_456L
    val mux = FakeMux(MuxResult(file = recording, firstFrameEpochMs, lastFrameEpochMs))
    val capture = AndroidVideoCapture(muxFactory = { _, _, _ -> mux })

    capture.start(tempDir, DEVICE_ID, appId = null)
    // Building the mux is not recording with it. Without this, a start path that constructed the
    // mux and never ran it would still produce an artifact here, off the fake's canned result.
    assertEquals(1, mux.startCount, "start must actually run the mux it built, not just construct it")
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)), "a captured recording is an artifact")

    // Every reader of this window (the HTML report, the desktop app) has already put the session's
    // logs on the HOST clock, so the window has to stay there too. Converting it to the device
    // clock — as this recorder used to, by sampling `adb shell date` — shifts every step's position
    // in the recording by the device's skew, which is seconds on a drifted phone.
    assertEquals(
      firstFrameEpochMs,
      artifact.startTimestampMs,
      "the window must start at the epoch the mux stamped the first frame with, unshifted",
    )
    assertEquals(
      lastFrameEpochMs,
      artifact.endTimestampMs,
      "the window must end at the epoch the mux stamped the last frame with, unshifted",
    )
    assertEquals(recording, artifact.file, "the artifact points at the file the mux finalized")
  }

  @Test
  fun `a mux that captured nothing yields no artifact`() {
    val capture = AndroidVideoCapture(muxFactory = { _, _, _ -> FakeMux(result = null) })

    capture.start(tempDir, DEVICE_ID, appId = null)

    assertNull(
      capture.stop(CaptureOptions(captureVideo = true)),
      "a device that refused to record leaves no artifact rather than an empty one the report would try to play",
    )
  }

  @Test
  fun `a mux that captured nothing leaves no empty recording behind`() {
    // ffmpeg creates its output when it is spawned, so the file is already on disk by the time the
    // device turns out to have fed it nothing. Left there it ships in the session zip as a
    // recording that will not play.
    val leftBehind = File(tempDir, RecordingFormat.preferred.canonicalFilename).apply { createNewFile() }
    val capture = AndroidVideoCapture(muxFactory = { _, _, _ -> FakeMux(result = null) })

    capture.start(tempDir, DEVICE_ID, appId = null)
    capture.stop(CaptureOptions(captureVideo = true))

    assertFalse(leftBehind.exists(), "an empty recording must be removed, not shipped")
  }

  @Test
  fun `a recording with bytes in it is never deleted`() {
    // The guard keys on emptiness alone, so a real short recording has to survive it.
    val recording = File(tempDir, RecordingFormat.preferred.canonicalFilename).apply { writeBytes(ByteArray(64)) }
    val mux = FakeMux(MuxResult(file = recording, 1_700_000_000_000L, 1_700_000_001_000L))
    val capture = AndroidVideoCapture(muxFactory = { _, _, _ -> mux })

    capture.start(tempDir, DEVICE_ID, appId = null)
    assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertTrue(recording.isFile, "a recording that captured frames must survive the empty-file guard")
  }

  @Test
  fun `stopping twice does not hand the same recording back a second time`() {
    val recording = File(tempDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    val mux = FakeMux(MuxResult(file = recording, 1_700_000_000_000L, 1_700_000_001_000L))
    val capture = AndroidVideoCapture(muxFactory = { _, _, _ -> mux })

    capture.start(tempDir, DEVICE_ID, appId = null)
    assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertNull(capture.stop(CaptureOptions(captureVideo = true)), "the second stop has no mux left to finalize")
    assertEquals(1, mux.startCount, "the second stop must not restart the recorder either")
    assertEquals(1, mux.stopCount, "the mux must be finalized exactly once, not re-stopped")
  }

  @Test
  fun `a device with no screenrecord is recorded by the fallback instead`() {
    // Some firmware ships no screen recorder. Sampling screenshots is slower and coarser, but the
    // alternative is a session with no footage at all, so the recording still happens.
    val fallback = RecordingFallback(
      artifact = CaptureArtifact(
        file = File(tempDir, "video.webm").apply { writeBytes(ByteArray(8)) },
        type = CaptureType.VIDEO_WEBM,
        startTimestampMs = 1_700_000_000_000L,
        endTimestampMs = 1_700_000_005_000L,
      ),
    )
    val capture = AndroidVideoCapture(
      muxFactory = { _, _, _ -> error("the streaming recorder must not be started on this device") },
      fallback = fallback,
      screenrecordAvailable = { false },
    )

    capture.start(tempDir, DEVICE_ID, appId = null)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertTrue(fallback.started, "the fallback must record the session")
    assertEquals(fallback.artifact, artifact, "the session's recording is the fallback's")
  }

  @Test
  fun `a device that can stream its screen never reaches the fallback`() {
    val fallback = RecordingFallback(artifact = null)
    val recording = File(tempDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    val mux = FakeMux(MuxResult(file = recording, 1_700_000_000_000L, 1_700_000_001_000L))
    val capture = AndroidVideoCapture(
      muxFactory = { _, _, _ -> mux },
      fallback = fallback,
      screenrecordAvailable = { true },
    )

    capture.start(tempDir, DEVICE_ID, appId = null)
    assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertFalse(fallback.started, "the slow path must not run on a device that can stream")
    assertFalse(fallback.stopped, "and must not be stopped either")
  }

  @Test
  fun `a session that ends on a still screen holds its last frame to the stop`() {
    // screenrecord sends nothing while the screen is still, so the mux's last frame can be long
    // before the session ended. The report needs footage for every step, so the recorder holds the
    // last frame out to the stop and the window follows it.
    val recording = File(tempDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    val first = 1_700_000_000_000L
    val mux = FakeMux(MuxResult(file = recording, first, first + 2_000L))
    val holds = mutableListOf<Pair<File, Long>>()
    val capture = AndroidVideoCapture(
      muxFactory = { _, _, _ -> mux },
      screenrecordAvailable = { true },
      nowMs = { first + 23_000L },
      holdLastFrame = { file, untilMs -> holds += file to untilMs; true },
    )

    capture.start(tempDir, DEVICE_ID, appId = null)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertEquals(listOf(recording to 23_000L), holds, "the hold runs to the stop, in clip time")
    assertEquals(first + 23_000L, artifact.endTimestampMs, "the window ends where the held footage does")
  }

  @Test
  fun `a feed that died before the stop is not held to it`() {
    // screenrecord or adb dying is what a crash or ANR run looks like, and its file also stops at
    // its last frame. Holding that frame would show a healthy screen for the failure.
    val recording = File(tempDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    val first = 1_700_000_000_000L
    val mux = FakeMux(MuxResult(file = recording, first, first + 2_000L, feedAlive = false))
    var held = false
    val capture = AndroidVideoCapture(
      muxFactory = { _, _, _ -> mux },
      screenrecordAvailable = { true },
      nowMs = { first + 23_000L },
      holdLastFrame = { _, _ -> held = true; true },
    )

    capture.start(tempDir, DEVICE_ID, appId = null)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertFalse(held, "a dead feed's last frame is not what the screen showed at the stop")
    assertEquals(first + 2_000L, artifact.endTimestampMs, "the window ends where the evidence does")
  }

  @Test
  fun `a tail inside encoder latency is not re-encoded`() {
    val recording = File(tempDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    val first = 1_700_000_000_000L
    val mux = FakeMux(MuxResult(file = recording, first, first + 9_900L))
    var held = false
    val capture = AndroidVideoCapture(
      muxFactory = { _, _, _ -> mux },
      screenrecordAvailable = { true },
      nowMs = { first + 10_000L },
      holdLastFrame = { _, _ -> held = true; true },
    )

    capture.start(tempDir, DEVICE_ID, appId = null)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertFalse(held, "100ms is the feed's latency, not a still screen")
    assertEquals(first + 9_900L, artifact.endTimestampMs)
  }

  @Test
  fun `a hold that fails keeps the window on the footage the file has`() {
    val recording = File(tempDir, "video.webm").apply { writeBytes(ByteArray(64)) }
    val first = 1_700_000_000_000L
    val mux = FakeMux(MuxResult(file = recording, first, first + 2_000L))
    val capture = AndroidVideoCapture(
      muxFactory = { _, _, _ -> mux },
      screenrecordAvailable = { true },
      nowMs = { first + 23_000L },
      holdLastFrame = { _, _ -> false },
    )

    capture.start(tempDir, DEVICE_ID, appId = null)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertEquals(first + 2_000L, artifact.endTimestampMs, "claiming footage the file lacks would misplace every step")
  }

  @Test
  fun `a companion device records under its own basename and its empty-file guard watches that file`() {
    // In a multi-device session two of these recorders share one session directory; the companion
    // must write `video-<name>` and, when its device fed the pipe nothing, remove ITS empty file
    // rather than the start device's recording.
    val startDeviceRecording = File(tempDir, RecordingFormat.preferred.canonicalFilename).apply { createNewFile() }
    var muxOutput: File? = null
    val capture = AndroidVideoCapture(
      muxFactory = { outputFile, _, _ -> muxOutput = outputFile; FakeMux(result = null) },
      basename = "video-buyer",
      screenrecordAvailable = { true },
    )

    capture.start(tempDir, DEVICE_ID, appId = null)
    val output = assertNotNull(muxOutput, "start must build the mux")
    assertEquals(RecordingFormat.preferred.filename("video-buyer"), output.name)
    assertEquals(tempDir, output.parentFile, "the companion records into the same session directory")

    output.createNewFile()
    assertNull(capture.stop(CaptureOptions(captureVideo = true)))
    assertFalse(output.exists(), "the companion's own empty recording is removed")
    assertTrue(startDeviceRecording.exists(), "the start device's file is not the companion's to delete")
  }

  /** Stands in for the screencap recorder, recording whether it was asked to do the work. */
  private class RecordingFallback(val artifact: CaptureArtifact?) : CaptureStream {
    override val type = CaptureType.VIDEO_WEBM
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


  private class FakeMux(private val result: MuxResult?) : WallClockVideoMux {
    var startCount = 0
      private set
    var stopCount = 0
      private set

    override fun start() {
      startCount++
    }

    override fun hasContent(): Boolean = startCount > 0

    override fun stop(): MuxResult? {
      stopCount++
      return result
    }
  }

  private companion object {
    /** No such device — see the class doc for what `start` does with it. */
    const val DEVICE_ID = "emulator-nonexistent-test"
  }
}
