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

  companion object {
    /**
     * Upper bound on the live H.264 → JPEG decode. Intentionally generous: this waits on a real
     * ffmpeg subprocess whose cold-start + first-frame latency balloons on a loaded CI agent. The
     * bound guards only against a genuinely wedged pipeline — normal decode finishes in well under a
     * second — so raising it converts timing flakes into passes without hiding a real regression
     * (the pipe stays open for the whole wait, so "frames only at EOF" still times out here).
     */
    private const val FRAME_DECODE_TIMEOUT_SECONDS: Long = 60

    /** Cadence at which the live-feeder thread re-writes the fixture — roughly one keyframe/second. */
    private const val LIVE_FEED_INTERVAL_MS: Long = 250
  }

  /**
   * Streams the fixture into the producer pipe on repeat, the way a real damage-driven encoder
   * keeps delivering bytes for as long as anything changes on screen. A single 22 KB burst
   * followed by a minute of silence is NOT the live contract — and it leaves nothing to decode
   * if the ffmpeg sidecar is killed mid-burst and respawned (the CI failure mode this suite
   * regressed on: a loaded agent killed the sidecar right after startup, observed as full ffmpeg
   * startup stderr and then silence with no exit message). Exits on its own when the pipe closes.
   */
  private fun startLiveFeeder(
    producerOutput: PipedOutputStream,
    h264: ByteArray,
    stopFeeding: AtomicBoolean,
  ): Thread = Thread {
    try {
      while (!stopFeeding.get()) {
        producerOutput.write(h264)
        producerOutput.flush()
        Thread.sleep(LIVE_FEED_INTERVAL_MS)
      }
    } catch (_: Exception) {
      // Pipe closed at teardown — done.
    }
  }.apply {
    isDaemon = true
    start()
  }

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
    // Snapshot of producer-open state captured *at the instant the first frame arrived*, not read
    // at some later point where a teardown could have raced in. This is what proves the decoder
    // emitted while the pipe was still open (live behavior) rather than only at EOF.
    val producerClosedAtFrame = AtomicBoolean(false)
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
        if (firstFrame.compareAndSet(null, frame)) {
          producerClosedAtFrame.set(producerClosed.get())
        }
        frameArrived.countDown()
      },
    )

    val stopFeeding = AtomicBoolean(false)
    var feeder: Thread? = null
    try {
      val h264 = generateH264Fixture(File(tempDir, "live.h264")).readBytes()
      consumer.start()
      feeder = startLiveFeeder(producerOutput, h264, stopFeeding)

      // Bound rationale (why 60s, why keeping the pipe open still catches regressions) lives on
      // FRAME_DECODE_TIMEOUT_SECONDS. producerOutput is only closed in finally, so the pipe stays
      // open for the whole wait.
      assertTrue(
        frameArrived.await(FRAME_DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the decoder should emit while the producer pipe remains open",
      )
      assertFalse(
        producerClosedAtFrame.get(),
        "the frame must arrive before producer EOF or teardown",
      )
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
      stopFeeding.set(true)
      consumer.stop()
      runCatching { producerOutput.close() }
      feeder?.join(2_000)
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `respawns the decoder and keeps emitting after the ffmpeg sidecar is killed`() {
    if (!ffmpegOnPath()) {
      println("skipping: ffmpeg not on PATH")
      return
    }

    val tempDir = Files.createTempDirectory("live-frame-respawn-").toFile()
    val producerInput = PipedInputStream(256 * 1024)
    val producerOutput = PipedOutputStream(producerInput)
    val firstFrame = CountDownLatch(1)
    // Set only once a *new* sidecar process has been observed after the kill, so frames decoded
    // by the dead sidecar (drained from its buffers) can't satisfy the recovery assertion.
    val respawnObserved = AtomicBoolean(false)
    val frameAfterRespawn = CountDownLatch(1)
    val tee = H264Tee(
      deviceId = TrailblazeDeviceId("respawn-test", TrailblazeDevicePlatform.ANDROID),
      videoSize = "320x240",
      bitRate = "500000",
      producerFactory = H264Tee.ProducerFactory { _, _, _, _ ->
        object : H264Tee.ProducerHandle {
          override val input = producerInput

          override fun close() {
            producerInput.close()
          }
        }
      },
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK },
    )
    val consumer = LiveFrameConsumer(
      tee = tee,
      onFrame = { _, _ ->
        firstFrame.countDown()
        if (respawnObserved.get()) frameAfterRespawn.countDown()
      },
    )

    val stopFeeding = AtomicBoolean(false)
    var feeder: Thread? = null
    try {
      val h264 = generateH264Fixture(File(tempDir, "respawn.h264")).readBytes()
      consumer.start()
      feeder = startLiveFeeder(producerOutput, h264, stopFeeding)
      assertTrue(
        firstFrame.await(FRAME_DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the pipeline must be healthy before the sidecar is killed",
      )

      // Kill the sidecar the way a memory-pressured CI agent does: SIGKILL, no warning, no exit
      // message. The consumer must notice (EOF on the sidecar's stdout) and respawn on its own.
      val killedSidecar = assertNotNull(consumer.sidecarProcessForTest(), "a started consumer has a sidecar")
      killedSidecar.destroyForcibly()
      killedSidecar.waitFor()

      val respawnDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(FRAME_DECODE_TIMEOUT_SECONDS)
      while (System.nanoTime() < respawnDeadline) {
        val current = consumer.sidecarProcessForTest()
        if (current != null && current.pid() != killedSidecar.pid()) break
        Thread.sleep(20)
      }
      val respawned = consumer.sidecarProcessForTest()
      assertTrue(
        respawned != null && respawned.pid() != killedSidecar.pid(),
        "a killed sidecar must be replaced by a fresh ffmpeg process",
      )
      respawnObserved.set(true)

      assertTrue(
        frameAfterRespawn.await(FRAME_DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "the respawned decoder must keep emitting frames from the still-open live feed",
      )
    } finally {
      stopFeeding.set(true)
      consumer.stop()
      runCatching { producerOutput.close() }
      feeder?.join(2_000)
      tempDir.deleteRecursively()
    }
  }

  @Test
  fun `liveness pings flow while the producer stays silent`() {
    if (!ffmpegOnPath()) {
      println("skipping: ffmpeg not on PATH")
      return
    }

    // Open pipe that never delivers a byte — the damage-driven-encoder static-screen state.
    val producerInput = PipedInputStream(256 * 1024)
    val producerOutput = PipedOutputStream(producerInput)
    val feedAlive = CountDownLatch(2)
    val tee = H264Tee(
      deviceId = TrailblazeDeviceId("silent-feed-test", TrailblazeDevicePlatform.ANDROID),
      videoSize = "320x240",
      bitRate = "500000",
      producerFactory = H264Tee.ProducerFactory { _, _, _, _ ->
        object : H264Tee.ProducerHandle {
          override val input = producerInput

          override fun close() {
            producerInput.close()
          }
        }
      },
      sdkLevelProvider = { H264Tee.ANDROID_R_SDK },
    )
    val consumer = LiveFrameConsumer(
      tee = tee,
      onFrame = { _, _ -> },
      onFeedAlive = { feedAlive.countDown() },
    )

    try {
      consumer.start()
      // Pings need no decoded frames (they come from the tee drain loop), but start() spawns
      // the same real ffmpeg whose cold start balloons on a loaded CI agent — same generous
      // bound as the decode test.
      assertTrue(
        feedAlive.await(FRAME_DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "a healthy-but-silent feed must keep proving liveness via onFeedAlive pings",
      )
    } finally {
      consumer.stop()
      runCatching { producerOutput.close() }
    }
  }

  @Test
  fun `alive pings fire immediately and then throttle to the interval`() {
    val gate = LiveFrameConsumer.AlivePingGate(intervalMillis = 500)

    // First call pings regardless of the clock's origin — System.nanoTime()-derived values
    // are typically large positive numbers.
    assertTrue(gate.tryPing(nowMillis = 86_400_000))
    assertFalse(gate.tryPing(nowMillis = 86_400_499))
    assertTrue(gate.tryPing(nowMillis = 86_400_500))
    assertFalse(gate.tryPing(nowMillis = 86_400_501))
  }

  @Test
  fun `alive pings work when the clock origin is negative`() {
    // nanoTime's origin is arbitrary; some platforms yield negative values.
    val gate = LiveFrameConsumer.AlivePingGate(intervalMillis = 500)

    assertTrue(gate.tryPing(nowMillis = -10_000))
    assertFalse(gate.tryPing(nowMillis = -9_600))
    assertTrue(gate.tryPing(nowMillis = -9_500))
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
  fun `gives up and releases the producer after repeated instant decoder deaths`() {
    val producerInput = PipedInputStream(256 * 1024)
    val producerOutput = PipedOutputStream(producerInput)
    val producerClosed = CountDownLatch(1)
    val tee = H264Tee(
      deviceId = TrailblazeDeviceId("crash-loop-test", TrailblazeDevicePlatform.ANDROID),
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
    // `true` spawns successfully and exits immediately — the pathological decoder that dies the
    // instant it starts, every time. Covers the startup race (the very first death can be
    // observed by a pump before start() even returns, and must still be handled as current)
    // and the bounded budget: once MAX_CONSECUTIVE_SILENT_DEATHS consecutive frameless deaths
    // accrue the consumer must give up and detach so the shared producer is released.
    val consumer = LiveFrameConsumer(
      tee = tee,
      onFrame = { _, _ -> },
      ffmpegBinary = "true",
    )

    try {
      consumer.start()
      assertTrue(
        producerClosed.await(FRAME_DECODE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
        "a crash-looping decoder must exhaust its respawn budget and release the producer",
      )
      assertNull(
        consumer.sidecarProcessForTest(),
        "after giving up there must be no current sidecar",
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
