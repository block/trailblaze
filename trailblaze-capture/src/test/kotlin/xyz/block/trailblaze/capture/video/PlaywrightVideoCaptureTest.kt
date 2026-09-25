package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.capture.CaptureOptions
import xyz.block.trailblaze.capture.model.CaptureType

/**
 * Drives the [PlaywrightVideoCapture] lifecycle end-to-end, including the real ffmpeg transcode
 * on the mp4 path. The "browser side" is faked: the test plays the role of the
 * Playwright manager by writing a `.webm` fixture into the directory that
 * `start()` publishes to [PlaywrightVideoRecordDir].
 *
 * Skipped when `ffmpeg` and `ffprobe` aren't on PATH, matching [MuxToMp4ConsumerTest].
 */
class PlaywrightVideoCaptureTest {

  private val deviceId = "web-capture-test"
  private lateinit var tempDir: File

  @BeforeTest
  fun setUp() {
    tempDir = Files.createTempDirectory("pwcap-").toFile()
  }

  @AfterTest
  fun tearDown() {
    PlaywrightVideoRecordDir.clearRecordDir(deviceId)
    tempDir.deleteRecursively()
  }

  @Test
  fun `start publishes the session dir to the registry`() {
    val capture = PlaywrightVideoCapture()
    capture.start(tempDir, deviceId, appId = null)
    assertEquals(tempDir, PlaywrightVideoRecordDir.getRecordDir(deviceId))
  }

  @Test
  fun `stop returns null when no webm landed`() {
    val capture = PlaywrightVideoCapture()
    capture.start(tempDir, deviceId, appId = null)
    val artifact = capture.stop(CaptureOptions(captureVideo = true))
    assertNull(artifact, "stop with no .webm in dir should produce no artifact")
    assertNull(
      PlaywrightVideoRecordDir.getRecordDir(deviceId),
      "stop must clear the registration even on the empty path",
    )
  }

  @Test
  fun `stop invokes the registered finalizer before scanning for webm`() {
    val capture = PlaywrightVideoCapture()
    val finalizerCalls = AtomicInteger(0)
    capture.start(tempDir, deviceId, appId = null)
    PlaywrightVideoRecordDir.setFinalizer(deviceId) { finalizerCalls.incrementAndGet() }
    capture.stop(CaptureOptions(captureVideo = true))
    assertEquals(
      1, finalizerCalls.get(),
      "stop() must ask the manager to flush the WebM via the registered finalizer",
    )
  }

  @Test
  fun `the window starts when the manager filmed, not when the directory was published`() {
    val recording = File(tempDir, "page@abc.webm").apply { writeBytes(ByteArray(64)) }
    // A cold browser takes seconds to launch before the page (and so the video) exists; measured
    // at 6.3 s. Everything in that gap is time the recording has no footage for.
    val publishedAt = 1_789_935_628_525L
    val filmingStartedAt = 1_789_935_634_750L
    val capture = PlaywrightVideoCapture(
      nowMs = { publishedAt },
      durationProbeMs = { 10_960L },
    )

    capture.start(tempDir, deviceId, appId = null)
    PlaywrightVideoRecordDir.markRecordingStarted(deviceId, filmingStartedAt)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertEquals(
      filmingStartedAt,
      artifact.startTimestampMs,
      "clip time zero is when Playwright got the page it films — publishing the directory is not " +
        "recording, and on a cold browser the two are seconds apart",
    )
    assertTrue(recording.name.isNotEmpty())
  }

  @Test
  fun `the window spans the file so the finalization tail does not displace every step`() {
    File(tempDir, "page@abc.webm").writeBytes(ByteArray(64))
    val filmingStartedAt = 1_789_935_634_750L
    // Playwright keeps writing duplicate frames while it finalizes — measured at ~910 ms — so the
    // file outlasts the stop call. The report SCALES clip time onto the window, so a short window
    // would push every step later in the clip by that proportion.
    val capture = PlaywrightVideoCapture(
      nowMs = { 1_789_935_644_796L },
      durationProbeMs = { 10_960L },
    )

    capture.start(tempDir, deviceId, appId = null)
    PlaywrightVideoRecordDir.markRecordingStarted(deviceId, filmingStartedAt)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertEquals(
      filmingStartedAt + 10_960L,
      artifact.endTimestampMs,
      "the window must be exactly as long as the recording, so the report's clip-time scale is 1",
    )
  }

  @Test
  fun `a recording whose duration cannot be read still gets a window`() {
    File(tempDir, "page@abc.webm").writeBytes(ByteArray(64))
    val stoppedAt = 1_789_935_644_796L
    val capture = PlaywrightVideoCapture(
      nowMs = { stoppedAt },
      durationProbeMs = { null },
    )

    capture.start(tempDir, deviceId, appId = null)
    PlaywrightVideoRecordDir.markRecordingStarted(deviceId, 1_789_935_634_750L)
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertEquals(
      stoppedAt,
      artifact.endTimestampMs,
      "a host without ffprobe falls back to the stop instant — an approximate window still places " +
        "steps, while no window makes the report drop the recording entirely",
    )
  }

  @Test
  fun `a browser that never filmed falls back to the publish instant`() {
    File(tempDir, "page@abc.webm").writeBytes(ByteArray(64))
    val publishedAt = 1_789_935_628_525L
    val capture = PlaywrightVideoCapture(nowMs = { publishedAt }, durationProbeMs = { 1_000L })

    capture.start(tempDir, deviceId, appId = null)
    // No markRecordingStarted: the manager was torn down, or never configured recording.
    val artifact = assertNotNull(capture.stop(CaptureOptions(captureVideo = true)))

    assertEquals(
      publishedAt,
      artifact.startTimestampMs,
      "with nothing reported the publish instant is the best available anchor",
    )
  }

  @Test
  fun `stop clears the filming anchor so the next session cannot inherit it`() {
    File(tempDir, "page@abc.webm").writeBytes(ByteArray(64))
    val capture = PlaywrightVideoCapture(nowMs = { 1L }, durationProbeMs = { 1_000L })

    capture.start(tempDir, deviceId, appId = null)
    PlaywrightVideoRecordDir.markRecordingStarted(deviceId, 1_789_935_634_750L)
    capture.stop(CaptureOptions(captureVideo = true))

    assertNull(
      PlaywrightVideoRecordDir.recordingStartedAtMs(deviceId),
      "a stale anchor would silently date the NEXT session's recording to this one",
    )
  }

  @Test
  fun `stop keeps Playwright's own webm as the session recording without transcoding`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val webm = generateWebmFixture(File(tempDir, "playwright-fixture.webm"), durationSeconds = 1)
    val fixtureBytes = webm.length()
    assertTrue(webm.exists() && fixtureBytes > 0, "fixture .webm should be on disk")

    val capture = PlaywrightVideoCapture()
    capture.start(tempDir, deviceId, appId = null)
    // Caller (the test) is standing in for the browser context: WebM is already there.
    val artifact = capture.stop(CaptureOptions(captureVideo = true))

    assertNotNull(artifact, "stop should return a CaptureArtifact when a .webm is present")
    // Playwright's WebM IS the recording every other platform produces, so it is delivered as-is
    // under the canonical name — no ffmpeg pass, no generational loss.
    assertEquals(CaptureType.VIDEO_WEBM, artifact.type)
    assertEquals("video.webm", artifact.file.name)
    assertEquals(fixtureBytes, artifact.file.length(), "the recording must be Playwright's file byte-for-byte")
    assertTrue(probeFormat(artifact.file).contains("webm"), "ffprobe should report video.webm as a WebM container")
    assertTrue(!webm.exists(), "the randomly named source is renamed away, not duplicated")
  }

  @Test
  fun `asked for mp4 stop transcodes the webm into video_mp4`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val webm = generateWebmFixture(File(tempDir, "playwright-fixture.webm"), durationSeconds = 1)
    assertTrue(webm.exists() && webm.length() > 0, "fixture .webm should be on disk")

    // `trailblaze report --video` promises an MP4, so it asks for one explicitly.
    val capture = PlaywrightVideoCapture(format = RecordingFormat.MP4)
    capture.start(tempDir, deviceId, appId = null)
    val artifact = capture.stop(CaptureOptions(captureVideo = true))

    assertNotNull(artifact, "stop should return a CaptureArtifact when a .webm is present")
    assertEquals(CaptureType.VIDEO, artifact.type)
    assertEquals("video.mp4", artifact.file.name)
    assertTrue(artifact.file.exists() && artifact.file.length() > 0)
    assertTrue(probeFormat(artifact.file).contains("mp4"), "ffprobe should report the transcoded video.mp4 as a valid container")
    assertTrue(!webm.exists(), "the source .webm should be deleted once the MP4 is produced")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Generate a tiny VP9 WebM via `ffmpeg lavfi testsrc` — same codec family Playwright produces. */
  private fun generateWebmFixture(target: File, durationSeconds: Int): File {
    val pb = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-f", "lavfi",
      "-i", "testsrc=duration=$durationSeconds:size=320x240:rate=15",
      "-c:v", "libvpx-vp9",
      "-deadline", "realtime",
      "-cpu-used", "8",
      "-b:v", "200k",
      "-an",
      "-f", "webm",
      target.absolutePath,
    ).redirectErrorStream(true)
    val process = pb.start()
    // Drain on a background thread so a hung ffmpeg can't deadlock the inline read
    // and bypass the `waitFor` timeout below.
    val drainThread = Thread {
      process.inputStream.bufferedReader().use { it.forEachLine { _ -> } }
    }.apply { isDaemon = true; start() }
    val finished = process.waitFor(60, TimeUnit.SECONDS)
    if (!finished) {
      process.destroyForcibly()
      drainThread.join(1_000)
      throw IOException("ffmpeg fixture generation timed out at ${target.absolutePath}")
    }
    drainThread.join(1_000)
    if (process.exitValue() != 0) {
      throw IOException("failed to generate webm fixture at ${target.absolutePath}")
    }
    return target
  }

  /** ffprobe's `format_name` for [file] (e.g. `matroska,webm` or `mov,mp4,...`); empty when it can't be probed. */
  private fun probeFormat(file: File): String {
    val pb = ProcessBuilder(
      "ffprobe",
      "-v", "error",
      "-show_entries", "format=format_name",
      "-of", "default=noprint_wrappers=1:nokey=1",
      file.absolutePath,
    ).redirectErrorStream(true)
    return try {
      val process = pb.start()
      // Drain on a background thread so a hung ffprobe doesn't deadlock the inline
      // read past the `waitFor` deadline below.
      val output = StringBuilder()
      val drainThread = Thread {
        process.inputStream.bufferedReader().use { reader -> reader.forEachLine { output.appendLine(it) } }
      }.apply { isDaemon = true; start() }
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly(); return ""
      }
      drainThread.join(1_000)
      if (process.exitValue() == 0) output.toString() else ""
    } catch (_: Exception) {
      ""
    }
  }

  private fun ffmpegOnPath(): Boolean = binaryOnPath("ffmpeg")
  private fun ffprobeOnPath(): Boolean = binaryOnPath("ffprobe")

  private fun binaryOnPath(name: String): Boolean = try {
    ProcessBuilder(name, "-version")
      .redirectErrorStream(true)
      .start()
      .let {
        val finished = it.waitFor(5, TimeUnit.SECONDS)
        if (!finished) it.destroyForcibly()
        finished && it.exitValue() == 0
      }
  } catch (_: Exception) {
    false
  }
}
