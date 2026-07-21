package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Tests the live H.264 → JPEG contract end-to-end against ffmpeg, plus the pure byte-level
 * splitter and unchanged-frame throttling policies.
 */
class LiveFrameConsumerTest {

  @Test
  fun `emits a jpeg before the live h264 producer reaches eof`() {
    if (!ffmpegOnPath()) {
      println("skipping: ffmpeg not on PATH")
      return
    }

    val tempDir = Files.createTempDirectory("live-frame-consumer-").toFile()
    val producerInput = PipedInputStream(256 * 1024)
    val producerOutput = PipedOutputStream(producerInput)
    val producerClosed = AtomicBoolean(false)
    val firstFrame = AtomicReference<ByteArray?>()
    val frameArrived = CountDownLatch(1)
    val tee = H264Tee(
      deviceId = TrailblazeDeviceId("live-test", TrailblazeDevicePlatform.ANDROID),
      videoSize = "320x240",
      bitRate = "500000",
      producerFactory = H264Tee.ProducerFactory { _, _, _, _ ->
        object : H264Tee.ProducerHandle {
          override val input = producerInput

          override fun close() {
            producerClosed.set(true)
            producerInput.close()
          }
        }
      },
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK },
    )
    val consumer = LiveFrameConsumer(
      tee = tee,
      onFrame = { frame, _ ->
        firstFrame.compareAndSet(null, frame)
        frameArrived.countDown()
      },
    )

    try {
      val h264 = generateH264Fixture(File(tempDir, "live.h264"))
      consumer.start()
      producerOutput.write(h264.readBytes())
      producerOutput.flush()

      assertTrue(
        frameArrived.await(5, TimeUnit.SECONDS),
        "the decoder should emit while the producer pipe remains open",
      )
      assertFalse(producerClosed.get(), "the frame must arrive before producer EOF or teardown")
      val jpeg = assertNotNull(firstFrame.get())
      assertTrue(
        jpeg.size >= 4 &&
          jpeg[0] == 0xFF.toByte() &&
          jpeg[1] == 0xD8.toByte() &&
          jpeg[jpeg.lastIndex - 1] == 0xFF.toByte() &&
          jpeg[jpeg.lastIndex] == 0xD9.toByte(),
        "callback should receive one complete JPEG",
      )
    } finally {
      consumer.stop()
      runCatching { producerOutput.close() }
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `unchanged frames are throttled but still emit a heartbeat`() {
    val gate = LiveFrameConsumer.FrameEmissionGate(maxSilenceMillis = 1_000)
    val first = byteArrayOf(1, 2, 3)

    assertEquals(
      LiveFrameConsumer.FrameEmissionGate.Emission.CONTENT_CHANGE,
      gate.admit(first, nowMillis = 100),
    )
    assertNull(gate.admit(first, nowMillis = 1_099))
    assertEquals(
      LiveFrameConsumer.FrameEmissionGate.Emission.HEARTBEAT,
      gate.admit(first, nowMillis = 1_100),
    )
    assertEquals(
      LiveFrameConsumer.FrameEmissionGate.Emission.CONTENT_CHANGE,
      gate.admit(byteArrayOf(4, 5, 6), nowMillis = 1_101),
    )
  }

  @Test
  fun `decoder startup failure releases the h264 producer`() {
    val producerInput = PipedInputStream()
    val producerOutput = PipedOutputStream(producerInput)
    val producerClosed = CountDownLatch(1)
    val tee = H264Tee(
      deviceId = TrailblazeDeviceId("failed-decoder-test", TrailblazeDevicePlatform.ANDROID),
      videoSize = "320x240",
      bitRate = "500000",
      producerFactory = H264Tee.ProducerFactory { _, _, _, _ ->
        object : H264Tee.ProducerHandle {
          override val input = producerInput

          override fun close() {
            producerInput.close()
            producerClosed.countDown()
          }
        }
      },
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK },
    )
    val consumer = LiveFrameConsumer(
      tee = tee,
      onFrame = { _, _ -> },
      ffmpegBinary = "definitely-not-a-real-ffmpeg-binary",
    )

    try {
      assertFailsWith<java.io.IOException> { consumer.start() }
      assertTrue(
        producerClosed.await(2, TimeUnit.SECONDS),
        "a failed decoder start should stop the screenrecord producer",
      )
    } finally {
      consumer.stop()
      runCatching { producerOutput.close() }
    }
  }

  @Test
  fun `splits a single frame in one feed`() {
    val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val frames = splitter.feedForTest(frame)
    assertEquals(1, frames.size)
    assertTrue(frames[0].contentEquals(frame), "frame should round-trip identically")
  }

  @Test
  fun `splits two back-to-back frames in one feed`() {
    val a = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 10, 20, 0xFF.toByte(), 0xD9.toByte())
    val b = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 30, 40, 50, 0xFF.toByte(), 0xD9.toByte())
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val frames = splitter.feedForTest(a + b)
    assertEquals(2, frames.size)
    assertTrue(frames[0].contentEquals(a))
    assertTrue(frames[1].contentEquals(b))
  }

  @Test
  fun `reassembles a frame split across multiple feeds`() {
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val out = mutableListOf<ByteArray>()
    // Feed byte-at-a-time to exercise marker-straddles-boundary handling.
    val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 8, 9, 0xFF.toByte(), 0xD9.toByte())
    for (b in frame) {
      splitter.feed(byteArrayOf(b), 0, 1) { out.add(it) }
    }
    assertEquals(1, out.size)
    assertTrue(out[0].contentEquals(frame))
  }

  @Test
  fun `ignores garbage bytes before the first SOI`() {
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val input = byteArrayOf(99, 98, 97, 0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
    val frames = splitter.feedForTest(input)
    assertEquals(1, frames.size)
    // Frame begins at the SOI; garbage prefix is dropped.
    val expected = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
    assertTrue(frames[0].contentEquals(expected))
  }

  @Test
  fun `SOI marker mid-frame resets and starts a new frame`() {
    // Pathological but possible if a stray marker shows up in the byte stream.
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val input = byteArrayOf(
      0xFF.toByte(), 0xD8.toByte(), 1, 2, // start frame A
      0xFF.toByte(), 0xD8.toByte(), 3, 4, // mid-frame SOI: should restart
      0xFF.toByte(), 0xD9.toByte(), // EOI: complete the new frame
    )
    val frames = splitter.feedForTest(input)
    assertEquals(1, frames.size, "should only emit the frame closed by EOI")
    val expected = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 3, 4, 0xFF.toByte(), 0xD9.toByte())
    assertTrue(frames[0].contentEquals(expected))
  }

  private fun generateH264Fixture(target: File): File {
    val process = ProcessBuilder(
      "ffmpeg",
      "-y",
      "-hide_banner",
      "-loglevel", "error",
      "-f", "lavfi",
      "-i", "testsrc=duration=1:size=320x240:rate=15",
      "-c:v", "libx264",
      "-preset", "ultrafast",
      "-tune", "zerolatency",
      "-f", "h264",
      target.absolutePath,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(30, TimeUnit.SECONDS) && process.exitValue() == 0) {
      "failed to generate H.264 fixture: $output"
    }
    return target
  }

  private fun ffmpegOnPath(): Boolean = try {
    ProcessBuilder("ffmpeg", "-version")
      .redirectErrorStream(true)
      .start()
      .let { process ->
        val finished = process.waitFor(5, TimeUnit.SECONDS)
        if (!finished) process.destroyForcibly()
        finished && process.exitValue() == 0
      }
  } catch (_: Exception) {
    false
  }
}
