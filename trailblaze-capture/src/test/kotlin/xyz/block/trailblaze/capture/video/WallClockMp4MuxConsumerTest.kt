package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Integration-leaning test for [WallClockMp4MuxConsumer] that exercises the real
 * `ffmpeg -use_wallclock_as_timestamps 1 -c copy` mux path — the load-bearing mechanism that makes
 * the iOS baguette video timeline match host wall-clock.
 *
 * Strategy mirrors [MuxToMp4ConsumerTest]: `ffmpeg lavfi testsrc` generates a short raw H.264
 * elementary stream, a standalone [H264Tee] streams it in as if it were the baguette FIFO, and we
 * assert the output is a valid MP4 and that the [WallClockMp4MuxConsumer.MuxResult] carries sane
 * host-epoch bookends (the report anchors `startTimestampMs` to `firstFrameEpochMs`).
 *
 * Skipped when `ffmpeg`/`ffprobe` aren't on PATH so a hermetic agent without the binary doesn't
 * fail; the main build's agents have both (Hermit-pinned 6.1.1).
 */
class WallClockMp4MuxConsumerTest {

  private val deviceId = TrailblazeDeviceId("sim-test-wallclock", TrailblazeDevicePlatform.IOS)
  private lateinit var tempDir: File

  @BeforeTest
  fun setUp() {
    H264Tee.resetRegistryForTests()
    tempDir = Files.createTempDirectory("wallclock-mux-").toFile()
  }

  @AfterTest
  fun tearDown() {
    H264Tee.resetRegistryForTests()
    tempDir.deleteRecursively()
  }

  @Test
  fun `streams h264 into a valid wall-clock mp4 and reports epoch bookends`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), durationSeconds = 1)
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = streamFileOnceProducer(h264))
    val out = File(tempDir, "video.baguette.mp4")

    val beforeStart = System.currentTimeMillis()
    val consumer = WallClockMp4MuxConsumer(outputFile = out, tee = tee)
    consumer.start()
    // Wait until bytes have flowed into ffmpeg and then settle, so the whole ~1s fixture has been
    // piped before we close the pipe (the fixture EOF is what would eventually stop the reader;
    // stop() detaches to flush and finalize).
    waitForContentStable(consumer, stableMs = 500, timeoutMs = 10_000)
    val result = consumer.stop()
    val afterStop = System.currentTimeMillis()

    assertNotNull(result, "mux should produce a MuxResult")
    assertTrue(result.file.exists() && result.file.length() > 0, "mp4 should be a non-empty file")
    assertTrue(isValidMp4(result.file), "ffprobe should report the mux output as a valid container")
    // The epoch bookends are host wall-clock stamped at ffmpeg-write time, so they sit inside the
    // test's own start/stop window and advance monotonically.
    assertTrue(
      result.firstFrameEpochMs in beforeStart..afterStop,
      "firstFrameEpochMs (${result.firstFrameEpochMs}) should fall within [$beforeStart, $afterStop]",
    )
    assertTrue(
      result.lastFrameEpochMs in result.firstFrameEpochMs..afterStop,
      "lastFrameEpochMs (${result.lastFrameEpochMs}) should be >= first and <= stop",
    )
  }

  @Test
  fun `a second stop returns null so a double stop cannot false-alarm on no content`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), durationSeconds = 1)
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = streamFileOnceProducer(h264))
    val consumer = WallClockMp4MuxConsumer(outputFile = File(tempDir, "video.baguette.mp4"), tee = tee)
    consumer.start()
    waitForContentStable(consumer, stableMs = 500, timeoutMs = 10_000)

    assertNotNull(consumer.stop(), "first stop returns the MuxResult")
    assertNull(consumer.stop(), "a second stop is a no-op returning null")
  }

  @Test
  fun `no bytes captured yields a null result`() {
    if (!ffmpegOnPath()) {
      println("skipping: ffmpeg not on PATH")
      return
    }
    // A feed that never produces a byte (empty producer): the mux started ffmpeg but nothing flowed,
    // so stop() must report null rather than hand back a bogus zero-frame MuxResult.
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = emptyProducer())
    val consumer = WallClockMp4MuxConsumer(outputFile = File(tempDir, "video.baguette.mp4"), tee = tee)
    consumer.start()
    assertNull(consumer.stop(), "no captured bytes → no MuxResult")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers (mirror MuxToMp4ConsumerTest)
  // ──────────────────────────────────────────────────────────────────────────

  private fun emptyProducer(): H264Tee.ProducerFactory =
    H264Tee.ProducerFactory { _, _, _, _ ->
      object : H264Tee.ProducerHandle {
        override val input = ByteArray(0).inputStream()
        override fun close() {}
      }
    }

  private fun generateH264Fixture(target: File, durationSeconds: Int): File {
    val pb = ProcessBuilder(
      "ffmpeg", "-y",
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

  /**
   * Polls the mux's output size until it has been unchanged for [stableMs] (with content present),
   * or until [timeoutMs] elapses. The wall-clock mux writes the mp4 live as bytes flow, so a stable
   * size means the finite fixture has fully drained into ffmpeg.
   */
  private fun waitForContentStable(consumer: WallClockMp4MuxConsumer, stableMs: Long, timeoutMs: Long) {
    val out = File(tempDir, "video.baguette.mp4")
    val deadline = System.currentTimeMillis() + timeoutMs
    var lastSize = -1L
    var lastChange = System.currentTimeMillis()
    while (System.currentTimeMillis() < deadline) {
      val size = if (out.exists()) out.length() else 0L
      if (size != lastSize) {
        lastSize = size
        lastChange = System.currentTimeMillis()
      } else if (consumer.hasContent() && size > 0 && System.currentTimeMillis() - lastChange >= stableMs) {
        return
      }
      Thread.sleep(20)
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
