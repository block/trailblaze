package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Integration-leaning test for [MuxToMp4Consumer] that exercises the real ffmpeg concat path.
 *
 * Strategy: ask ffmpeg to *generate* two short H.264 test patterns to disk (lavfi `testsrc`),
 * stand up an [H264Tee] with a producer factory that streams those files in succession as if
 * they were two consecutive screenrecord invocations, then assert the resulting `video.mp4`
 * is a valid MP4 by re-running ffprobe on it.
 *
 * Skipped when `ffmpeg` is not on PATH so this doesn't fail on a hermetic CI agent without
 * the binary. The main build's tests all run on agents that have ffmpeg.
 */
class MuxToMp4ConsumerTest {

  private val deviceId = TrailblazeDeviceId("emulator-test-mp4", TrailblazeDevicePlatform.ANDROID)
  private lateinit var tempDir: File

  @BeforeTest
  fun setUp() {
    H264Tee.resetRegistryForTests()
    tempDir = Files.createTempDirectory("mp4consumer-").toFile()
  }

  @AfterTest
  fun tearDown() {
    H264Tee.resetRegistryForTests()
    tempDir.deleteRecursively()
  }

  @Test
  fun `single-segment recording wraps into a valid mp4`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      // CI agents that have `ffmpeg` but not `ffprobe` would otherwise reach
      // `isValidMp4()`, hit `ProcessBuilder("ffprobe", …).start()` → IOException → false,
      // and fail the final assertion with no useful diagnostic. Skip explicitly so the
      // failure mode is "test skipped, missing binary" instead of "produced invalid mp4."
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), durationSeconds = 1)
    val tee = H264Tee(
      deviceId = deviceId,
      videoSize = "320x240",
      bitRate = "500000",
      producerFactory = streamFileOnceProducer(h264),
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK }, // unlimited — no restart, single segment
    )

    val consumer = MuxToMp4Consumer(sessionDir = tempDir, tee = tee)
    consumer.start()
    // Wait for the producer to fully drain the fixture into the segment, not just for any
    // bytes to appear. The fixture is 1 s of H.264 at 320x240 @ 15 fps → ~5–20 KB total; on
    // a fast laptop that lands within a hundred ms but on a slower CI agent the previous
    // `atLeast=100` threshold could trip while the producer was still mid-frame, leaving a
    // truncated H.264 stream that ffmpeg muxes into an mp4 missing keyframes/moov entries.
    // ffprobe then rejects the resulting file. Stabilization-based wait (no file growth for
    // 500 ms) explicitly waits for the producer to hit EOF before we ask the consumer to
    // close the segment and run the ffmpeg wrap — matches what production does when
    // screenrecord exits at the 3-min cap (the cap-end signal is what flushes the trailing
    // bytes; here EOF on the fixture file plays the same role).
    waitForFileStable(File(tempDir, "video.000.h264"), stableMs = 500, timeoutMs = 10_000)
    val out = consumer.stop()

    assertNotNull(out, "consumer should produce an mp4")
    assertTrue(out.exists() && out.length() > 0, "video.mp4 should be a non-empty file")
    assertTrue(isValidMp4(out), "ffprobe should report video.mp4 as a valid container")
  }

  @Test
  fun `multi-segment recording concats into a valid mp4`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val first = generateH264Fixture(File(tempDir, "a.h264"), durationSeconds = 1)
    val second = generateH264Fixture(File(tempDir, "b.h264"), durationSeconds = 1)
    val files = listOf(first, second)
    val callCount = java.util.concurrent.atomic.AtomicInteger(0)
    val factory = H264Tee.ProducerFactory { _, _, _, _ ->
      val n = callCount.getAndIncrement()
      if (n >= files.size) throw IllegalStateException("no more fixtures")
      object : H264Tee.ProducerHandle {
        override val input = files[n].inputStream()
        override fun close() {}
      }
    }
    val tee = H264Tee(
      deviceId = deviceId,
      videoSize = "320x240",
      bitRate = "500000",
      producerFactory = factory,
      sdkLevelProvider = { 28 }, // < 30 forces restart-on-EOF chain
    )

    val consumer = MuxToMp4Consumer(sessionDir = tempDir, tee = tee)
    consumer.start()
    // Wait for the producer to fully drain BOTH segments before stopping. Same
    // stabilization rationale as the single-segment test — a byte-threshold wait races
    // the producer mid-fixture on a slow CI agent and yields a truncated H.264 stream.
    waitForFileStable(File(tempDir, "video.000.h264"), stableMs = 500, timeoutMs = 10_000)
    waitForFileStable(File(tempDir, "video.001.h264"), stableMs = 500, timeoutMs = 10_000)
    val out = consumer.stop()

    assertNotNull(out, "consumer should produce an mp4 after concat")
    assertTrue(out.length() > 0, "concatenated mp4 should be non-empty")
    assertTrue(isValidMp4(out), "ffprobe should report the concatenated mp4 as a valid container")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  /** Generate a tiny raw H.264 elementary stream via `ffmpeg lavfi testsrc`. */
  private fun generateH264Fixture(target: File, durationSeconds: Int): File {
    val pb = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-f", "lavfi",
      "-i", "testsrc=duration=$durationSeconds:size=320x240:rate=15",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-f", "h264",
      target.absolutePath,
    ).redirectErrorStream(true)
    val process = pb.start()
    process.inputStream.bufferedReader().readText()
    if (!process.waitFor(60, TimeUnit.SECONDS) || process.exitValue() != 0) {
      throw IOException("failed to generate H264 fixture at ${target.absolutePath}")
    }
    return target
  }

  private fun streamFileOnceProducer(file: File): H264Tee.ProducerFactory {
    var consumed = false
    return H264Tee.ProducerFactory { _, _, _, _ ->
      if (consumed) throw IllegalStateException("only one fixture available")
      consumed = true
      object : H264Tee.ProducerHandle {
        override val input = file.inputStream()
        override fun close() {}
      }
    }
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
      val output = process.inputStream.bufferedReader().readText().trim()
      if (!process.waitFor(30, TimeUnit.SECONDS)) {
        process.destroyForcibly(); return false
      }
      process.exitValue() == 0 && output.contains("mp4")
    } catch (e: Exception) {
      false
    }
  }

  private fun ffmpegOnPath(): Boolean = binaryOnPath("ffmpeg")

  /**
   * Companion to [ffmpegOnPath]. CI runs where `ffmpeg` is present but `ffprobe` is not
   * would otherwise reach [isValidMp4], hit `ProcessBuilder("ffprobe", …).start()` →
   * IOException → false, and fail the assertion with no useful diagnostic ("ffprobe should
   * report video.mp4 as a valid container" while the underlying H.264 stream is complete).
   * Gating the test on both binaries makes the missing-`ffprobe` case skip cleanly instead
   * of failing CI.
   */
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

  /**
   * Polls [file]'s size until it has been unchanged for [stableMs] (with size > 0), or until
   * [timeoutMs] elapses. Used to detect "the producer drained its fixture and the segment is
   * complete" — the previous `waitForFileGrowth` helper only checked that the file had grown
   * past a threshold, which raced the producer mid-stream on slower CI agents and produced a
   * truncated H.264 stream that ffmpeg muxed into an invalid mp4.
   *
   * Returns silently on stabilization or timeout — the calling test asserts on the resulting
   * mp4 validity; if the file never stabilized within the timeout, that assertion is what
   * will fail.
   */
  private fun waitForFileStable(file: File, stableMs: Long, timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastSize = -1L
    var lastChange = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
      val size = if (file.exists()) file.length() else 0L
      if (size != lastSize) {
        lastSize = size
        lastChange = System.currentTimeMillis()
      } else if (size > 0 && System.currentTimeMillis() - lastChange >= stableMs) {
        return
      }
      Thread.sleep(20)
    }
  }
}
