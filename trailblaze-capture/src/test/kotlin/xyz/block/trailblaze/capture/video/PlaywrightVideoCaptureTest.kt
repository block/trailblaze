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
 * Drives the [PlaywrightVideoCapture] lifecycle end-to-end against a real ffmpeg
 * transcode. The "browser side" is faked: the test plays the role of the
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
  fun `stop transcodes a webm fixture into video_mp4`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val webm = generateWebmFixture(File(tempDir, "playwright-fixture.webm"), durationSeconds = 1)
    assertTrue(webm.exists() && webm.length() > 0, "fixture .webm should be on disk")

    val capture = PlaywrightVideoCapture()
    capture.start(tempDir, deviceId, appId = null)
    // Caller (the test) is standing in for the browser context: WebM is already there.
    val artifact = capture.stop(
      CaptureOptions(
        captureVideo = true,
        // Zero out sprite work so the assertion is unambiguously about the MP4.
        spriteFrameFps = 0,
      ),
    )

    assertNotNull(artifact, "stop should return a CaptureArtifact when a .webm is present")
    // With sprite extraction disabled (fps=0), VideoSpriteExtractor returns null and we
    // fall back to the raw MP4 — exactly the path that the report viewer's `<video>` tag
    // depends on for Playwright runs.
    assertEquals(CaptureType.VIDEO, artifact.type)
    assertEquals("video.mp4", artifact.file.name)
    assertTrue(artifact.file.exists() && artifact.file.length() > 0)
    assertTrue(
      isValidMp4(artifact.file),
      "ffprobe should report the transcoded video.mp4 as a valid container",
    )
    assertTrue(
      !webm.exists(),
      "the source .webm should be deleted once the canonical MP4 is produced",
    )
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

  private fun isValidMp4(file: File): Boolean {
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
        process.destroyForcibly(); return false
      }
      drainThread.join(1_000)
      process.exitValue() == 0 && output.toString().contains("mp4")
    } catch (_: Exception) {
      false
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
