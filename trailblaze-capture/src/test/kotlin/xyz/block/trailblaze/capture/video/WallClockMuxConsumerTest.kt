package xyz.block.trailblaze.capture.video

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * Integration-leaning test for [WallClockMuxConsumer] that exercises the real
 * `ffmpeg -use_wallclock_as_timestamps 1` path — the load-bearing mechanism that makes a session
 * recording's timeline match host wall-clock — in both of its outputs.
 *
 * Strategy mirrors [MuxToMp4ConsumerTest]: `ffmpeg lavfi testsrc` generates a short raw H.264
 * elementary stream, a standalone [H264Tee] streams it in as if it were the device feed, and we
 * probe the produced file for the properties the report depends on.
 *
 * The WebM tests feed the fixture in **bursts with idle gaps**, because that is the shape a
 * damage-driven `screenrecord` feed has and the shape that breaks a careless encode: a constant-rate
 * resample collapses each burst to about one frame, and an encoder with lookahead holds a whole
 * burst in memory. Every assertion is probed out of the file rather than inferred from the return
 * value.
 *
 * Skipped when `ffmpeg`/`ffprobe` aren't on PATH so a hermetic agent without the binary doesn't
 * fail; the main build's agents have both (Hermit-pinned).
 */
class WallClockMuxConsumerTest {

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
  fun `an mp4 recording states its rotation in the container and keeps the stream copy`() {
    val command = muxCommandFor(
      WallClockMuxConsumer.Output.Mp4Copy,
      IosScreenRotation.COUNTER_CLOCKWISE_90,
    )

    // On the input side: `-display_rotation` sets how the incoming stream is read, and ffmpeg
    // rejects it outright as an output option. Placed after `-i` this whole recording fails to
    // start.
    assertTrue(
      command.indexOf("-display_rotation:v:0") < command.indexOf("-i"),
      "the display matrix has to precede -i: $command",
    )
    assertEquals("90", command[command.indexOf("-display_rotation:v:0") + 1])
    // The point of this container: rotating must not cost an encode.
    assertTrue(command.windowed(2).contains(listOf("-c", "copy")), "mp4 must still stream-copy: $command")
    assertFalse(command.contains("-vf"), "mp4 must not filter — that would force a re-encode: $command")
  }

  @Test
  fun `a webm recording turns the pixels because the container cannot carry rotation`() {
    val command = muxCommandFor(
      WallClockMuxConsumer.Output.WebmVp9(),
      IosScreenRotation.COUNTER_CLOCKWISE_90,
    )

    // On the output side: a filter placed before `-i` applies to nothing and ffmpeg says nothing
    // about it, so the recording comes out unrotated and no one finds out.
    assertTrue(command.indexOf("-vf") > command.indexOf("-i"), "the filter has to follow -i: $command")
    assertEquals("transpose=2", command[command.indexOf("-vf") + 1])
    assertFalse(command.contains("-display_rotation:v:0"), "WebM has no display matrix to set: $command")
  }

  @Test
  fun `an unrotated recording adds no rotation arguments at all`() {
    listOf(WallClockMuxConsumer.Output.Mp4Copy, WallClockMuxConsumer.Output.WebmVp9()).forEach { output ->
      val command = muxCommandFor(output, IosScreenRotation.NONE)
      assertFalse(command.contains("-vf"), "$output: portrait must not pay for a filter: $command")
      assertFalse(command.contains("-display_rotation:v:0"), "$output: nothing to declare: $command")
    }
  }

  private fun muxCommandFor(
    output: WallClockMuxConsumer.Output,
    rotation: IosScreenRotation,
  ): List<String> = WallClockMuxConsumer(
    outputFile = File(tempDir, "video.${output.fileExtension}"),
    tee = H264Tee.standalone(deviceId = deviceId, producerFactory = emptyProducer()),
    output = output,
    rotation = rotation,
  ).buildFfmpegCommand()

  @Test
  fun `streams h264 into a valid wall-clock mp4 and reports epoch bookends`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), frames = 15)
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = streamFileOnceProducer(h264))
    val out = File(tempDir, "video.baguette.mp4")

    val beforeStart = System.currentTimeMillis()
    val consumer = WallClockMuxConsumer(outputFile = out, tee = tee)
    consumer.start()
    // Wait until bytes have flowed into ffmpeg and then settle, so the whole fixture has been
    // piped before we close the pipe (the fixture EOF is what would eventually stop the reader;
    // stop() detaches to flush and finalize).
    waitForContentStable(consumer, out, stableMs = 500, timeoutMs = 10_000)
    val result = consumer.stop()
    val afterStop = System.currentTimeMillis()

    assertNotNull(result, "mux should produce a MuxResult")
    assertTrue(result.file.exists() && result.file.length() > 0, "mp4 should be a non-empty file")
    assertTrue(readFormat(result.file).orEmpty().contains("mp4"), "ffprobe should report a valid mp4 container")
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
  fun `a rotated session really comes out rotated, in both containers`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    // End to end through the live pipeline, because the arg-placement tests above only prove the
    // command is *shaped* right. ffmpeg accepts a filter that reaches nothing and a display matrix
    // on a stream it can't apply to, and in both cases exits 0 with an unrotated recording — the
    // dimensions of the finished file are the only thing that actually says it worked.
    val outputs = buildList {
      add(WallClockMuxConsumer.Output.Mp4Copy)
      if (WallClockMuxConsumer.Output.vp9EncoderAvailable()) add(WallClockMuxConsumer.Output.WebmVp9())
    }

    outputs.forEach { output ->
      val h264 = generateH264Fixture(File(tempDir, "src-$output.h264"), frames = 15)
      val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = streamFileOnceProducer(h264))
      val out = File(tempDir, "rotated-${output.fileExtension}.${output.fileExtension}")

      val consumer = WallClockMuxConsumer(
        outputFile = out,
        tee = tee,
        output = output,
        rotation = IosScreenRotation.COUNTER_CLOCKWISE_90,
      )
      consumer.start()
      waitForContentStable(consumer, out, stableMs = 500, timeoutMs = 10_000)
      assertNotNull(consumer.stop(), "$output: the rotated mux must still produce a recording")

      // The fixture is 320x240. A quarter turn has to come back 240x320 however the container
      // expresses it — baked into the pixels for WebM, declared in the display matrix for mp4.
      assertEquals(
        VideoFrameSize.Size(240, 320),
        VideoFrameSize.probe(out),
        "$output: a quarter-turned 320x240 recording must present as 240x320",
      )
    }
  }

  @Test
  fun `a second stop returns null so a double stop cannot false-alarm on no content`() {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), frames = 15)
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = streamFileOnceProducer(h264))
    val out = File(tempDir, "video.baguette.mp4")
    val consumer = WallClockMuxConsumer(outputFile = out, tee = tee)
    consumer.start()
    waitForContentStable(consumer, out, stableMs = 500, timeoutMs = 10_000)

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
    val consumer = WallClockMuxConsumer(outputFile = File(tempDir, "video.baguette.mp4"), tee = tee)
    consumer.start()
    assertNull(consumer.stop(), "no captured bytes → no MuxResult")
  }

  @Test
  fun `webm output is vp9 in a webm and keeps every frame of a bursty feed`() {
    if (!webmToolsAvailable()) return
    // 60 frames delivered as 6 bursts with idle gaps between them — the damage-driven shape.
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), frames = 60)
    val feed = burstProducer(h264, bursts = 6, gapMs = 300)
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = feed.factory)
    val out = File(tempDir, "video.webm")
    val consumer = WallClockMuxConsumer(outputFile = out, tee = tee, output = WallClockMuxConsumer.Output.WebmVp9())
    consumer.start()
    // Stop only once the feed has delivered its last byte. (Waiting for the file to stop growing
    // is wrong here: between bursts the file legitimately sits still, and a stop during that pause
    // detaches the tee before the remaining bursts arrive — truncating the recording.)
    assertTrue(feed.finished.await(20, TimeUnit.SECONDS), "the fixture feed should complete")

    val result = assertNotNull(consumer.stop(), "mux should produce a MuxResult")

    assertEquals("vp9", readCodec(result.file), "the report embeds this as video/webm, which admits only VP8/VP9/AV1")
    assertEquals("matroska,webm", readFormat(result.file), "must be a WebM container, not an mp4 named .webm")
    assertEquals(
      60,
      readFrameCount(result.file),
      "every frame the capture saw must reach the report; a constant-rate resample collapses each " +
        "burst to about one frame, and a burst IS the transition a viewer opened the report to watch",
    )
  }

  @Test
  fun `webm on disk is playable while the session is still recording and seekable after stop`() {
    if (!webmToolsAvailable()) return
    // A slow feed: 60 frames over ~4s. We probe the file on disk in the MIDDLE of it — before stop
    // and before ffmpeg has written any trailer — which is exactly what a daemon crash leaves behind.
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), frames = 60)
    val feed = burstProducer(h264, bursts = 8, gapMs = 500)
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = feed.factory)
    val out = File(tempDir, "video.webm")
    val consumer = WallClockMuxConsumer(outputFile = out, tee = tee, output = WallClockMuxConsumer.Output.WebmVp9())
    consumer.start()
    waitForContentStable(consumer, out, stableMs = 0, timeoutMs = 10_000)
    // Roughly half-way through the ~3.5s feed: bytes have flowed and more are still coming.
    Thread.sleep(2_000)

    val midSessionFrames = readFrameCount(out)
    val midSessionSize = out.length()
    val feedStillRunning = feed.finished.count > 0

    assertTrue(feedStillRunning, "sanity: the probe must run while the feed is still delivering, not after it drained")
    assertNotNull(midSessionFrames, "the half-written file must already decode — a crash now keeps the recording so far")
    assertTrue(midSessionFrames > 0, "the half-written file must already hold frames, got $midSessionFrames")
    assertFalse(
      readDurationSeconds(out) != null && readDurationSeconds(out)!! > 0.0,
      "sanity: a mid-session file has no finalized duration yet (that is written at stop)",
    )

    assertTrue(feed.finished.await(20, TimeUnit.SECONDS), "the fixture feed should complete")
    val result = assertNotNull(consumer.stop())

    assertTrue(result.file.length() > midSessionSize, "the file grew after the mid-session probe")
    assertEquals(60, readFrameCount(result.file), "the finished file holds every frame")
    val duration = assertNotNull(readDurationSeconds(result.file), "stop must finalize the file with a duration")
    assertTrue(duration > 0.0, "finalized duration should be positive, got $duration")
  }

  @Test
  fun `frame timestamps keep millisecond resolution instead of snapping to the declared frame rate`() {
    if (!webmToolsAvailable()) return
    if (!WallClockMuxConsumer.Output.probeLiveMuxSupport().reencodeKeepsWallClock) {
      // This ffmpeg re-encodes onto the declared grid (FFmpeg #11268). Recording falls back to the
      // mp4 stream copy on it, which the next test proves keeps arrival time.
      println("skipping: this ffmpeg loses wall-clock time through a re-encode, so recordings use mp4")
      return
    }
    assertArrivalTimed(WallClockMuxConsumer.Output.WebmVp9(), "video.webm")
  }

  @Test
  fun `the mp4 stream copy keeps millisecond arrival time too, on every ffmpeg`() {
    // The fallback for an ffmpeg whose re-encode loses wall-clock time: a copy never decodes, so
    // the arrival stamps reach the file. Runs even where the webm case above skips.
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return
    }
    assertArrivalTimed(WallClockMuxConsumer.Output.Mp4Copy, "video.mp4")
  }

  private fun assertArrivalTimed(output: WallClockMuxConsumer.Output, fileName: String) {
    // The fixture's bitstream DECLARES one frame rate; the feed delivers a frame every ~16ms. That
    // mismatch is the production shape — a damage-driven screen feed's declared rate says nothing
    // about when its frames actually arrived, and Android's `screenrecord` declares no rate at all,
    // which makes ffmpeg assume 25fps. Derive the encoder's time base from that rate and every
    // wall-clock stamp is snapped onto its grid, piling several frames onto one timestamp: a player
    // then shows one frame per group, so the recording stutters at the guessed rate.
    val h264 = generateH264Fixture(File(tempDir, "src.h264"), frames = 60)
    val declaredRate = assertNotNull(readDeclaredFrameRate(h264), "fixture must declare a rate to snap to")
    val feed = burstProducer(h264, bursts = 60, gapMs = 16)
    val tee = H264Tee.standalone(deviceId = deviceId, producerFactory = feed.factory)
    val out = File(tempDir, fileName)
    val consumer = WallClockMuxConsumer(outputFile = out, tee = tee, output = output)
    consumer.start()
    assertTrue(feed.finished.await(20, TimeUnit.SECONDS), "the fixture feed should complete")

    val result = assertNotNull(consumer.stop(), "mux should produce a MuxResult")

    val ptsMs = readPacketPtsMs(result.file).sorted()
    assertTrue(ptsMs.size >= 30, "sanity: the feed should have reached the file, got ${ptsMs.size} packets")
    val snapped = ptsMs.count { onFrameRateGrid(it, declaredRate) }
    // Deliberately a proportion, not a count of distinct timestamps: how many frames share a stamp
    // depends on how fast the machine running the test feeds them, but whether the stamps sit on
    // the declared-rate grid does not. A grid-derived time base puts ALL of them there.
    assertTrue(
      snapped * 2 < ptsMs.size,
      "frame timestamps must come from arrival time, not the declared ${declaredRate}fps grid — " +
        "$snapped of ${ptsMs.size} land exactly on it",
    )
  }

  @Test
  fun `vp9 availability is read off the encoder listing not the binary's presence`() {
    val withVp9 = """
      Encoders:
       V..... libvpx               libvpx VP8 (codec vp8)
       V..... libvpx-vp9           libvpx VP9 (codec vp9)
       V..... libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
    """.trimIndent()
    val vp8Only = """
      Encoders:
       V..... libvpx               libvpx VP8 (codec vp8)
       V..... libx264              libx264 H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10 (codec h264)
    """.trimIndent()

    assertTrue(WallClockMuxConsumer.Output.vp9EncoderListed(withVp9))
    assertFalse(WallClockMuxConsumer.Output.vp9EncoderListed(vp8Only), "a VP8-only libvpx must not count as VP9")
    assertFalse(WallClockMuxConsumer.Output.vp9EncoderListed(""), "no listing at all means no encoder")
  }

  @Test
  fun `the ffmpeg version is read off the banner the encoder probe already prints`() {
    val listing = """
      ffmpeg version 6.1.1-3ubuntu5 Copyright (c) 2000-2023 the FFmpeg developers
        built with gcc 13 (Ubuntu 13.2.0-23ubuntu3)
      Encoders:
       V..... libvpx-vp9           libvpx VP9 (codec vp9)
    """.trimIndent()

    assertEquals("6.1.1-3ubuntu5", WallClockMuxConsumer.Output.ffmpegVersionIn(listing))
    assertTrue(WallClockMuxConsumer.Output.vp9EncoderListed(listing), "the banner must not hide the listing")
    assertNull(WallClockMuxConsumer.Output.ffmpegVersionIn("Encoders:\n"), "no banner means no version")
  }

  @Test
  fun `only the ffmpeg releases that drop wall-clock time on a re-encode are flagged`() {
    // FFmpeg ticket #11268: introduced in 6.1, fixed in 6.1.3, 7.0.3, 7.1.2 and 8.0.
    val affected = listOf("6.1", "6.1.1-3ubuntu5", "6.1.2", "7.0", "7.0.2", "7.1", "7.1.1-1+b1", "n7.1.1")
    val unaffected = listOf(
      "6.0", "4.4.2-0ubuntu0.22.04.1", "6.1.3", "6.1.6", "7.0.3", "7.1.2", "8.0",
      "n8.1.2-50-g1a748fe2cd", "9.0.2",
      // A git snapshot names no release. Treated as unaffected: those builds are recent.
      "N-117000-gabc1234",
    )

    affected.forEach { assertTrue(WallClockMuxConsumer.Output.dropsWallClockOnDecode(it), "$it should be flagged") }
    unaffected.forEach { assertFalse(WallClockMuxConsumer.Output.dropsWallClockOnDecode(it), "$it should not be flagged") }
  }

  @Test
  fun `an unknown ffmpeg version keeps webm rather than guessing it is broken`() {
    assertTrue(WallClockMuxConsumer.Output.LiveMuxSupport(vp9Encoder = true, ffmpegVersion = null).reencodeKeepsWallClock)
    assertFalse(
      WallClockMuxConsumer.Output.LiveMuxSupport(vp9Encoder = true, ffmpegVersion = "6.1.1").reencodeKeepsWallClock,
    )
  }

  @Test
  fun `the encoder probe gives up on an ffmpeg that never exits instead of hanging capture start`() {
    // Two shims standing in for ffmpeg: one prints the listing and exits, one prints it and then
    // never exits (stdout stays open). The probe runs at capture start, so the wedged one has to
    // cost the session its timeout and the mp4 fallback — not the run. Reading stdout to EOF before
    // waiting would hang here for the shim's full lifetime and then report the encoder present.
    fun shim(name: String, body: String) = File(tempDir, name).apply {
      writeText("#!/bin/sh\nprintf ' V..... libvpx-vp9  libvpx VP9 (codec vp9)\\n'\n$body")
      setExecutable(true)
    }
    val prompt = shim("prompt-ffmpeg", "")
    // `exec` so the process the probe kills on timeout is the sleeper itself, not a shell that
    // would leave an orphaned `sleep` on the agent.
    val wedged = shim("wedged-ffmpeg", "exec sleep 120\n")

    // The positive control only proves a prompt listing is read, so its budget is hang containment,
    // not a timing claim. The first exec of a just-written script can take seconds on a loaded CI
    // Mac, which is what made a 5s budget here flaky.
    assertTrue(
      WallClockMuxConsumer.Output.vp9EncoderAvailable(prompt.absolutePath, timeoutSeconds = 60),
      "a shim that prints the listing and exits must report the encoder",
    )

    val startedAt = System.nanoTime()
    val available = WallClockMuxConsumer.Output.vp9EncoderAvailable(wedged.absolutePath, timeoutSeconds = 1)
    val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
    assertFalse(available, "a probe that timed out must not report an encoder")
    // Hang containment, not a speed budget: an ignored timeout waits out the shim's 120s sleep.
    assertTrue(elapsedMs < 60_000, "the probe must return on its timeout, took ${elapsedMs}ms")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Producers
  // ──────────────────────────────────────────────────────────────────────────

  private fun emptyProducer(): H264Tee.ProducerFactory =
    H264Tee.ProducerFactory { _, _, _, _ ->
      object : H264Tee.ProducerHandle {
        override val input = ByteArray(0).inputStream()
        override fun close() {}
      }
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

  /** A bursty fixture feed plus a latch that opens once its last byte has been read by the tee. */
  private class BurstFeed(val factory: H264Tee.ProducerFactory, val finished: CountDownLatch)

  /**
   * Delivers [file] as [bursts] equal chunks with a [gapMs] pause before each chunk after the first —
   * the shape of a damage-driven screen feed (a burst of frames while the screen changes, nothing
   * while it is still). Chunk boundaries don't align to frames; the H.264 parser downstream handles
   * a frame that straddles two chunks, just as it does in production. [BurstFeed.finished] opens on
   * EOF, which the tee's reader thread reaches only after it has fanned out the final chunk.
   */
  private fun burstProducer(file: File, bursts: Int, gapMs: Long): BurstFeed {
    var consumed = false
    val finished = CountDownLatch(1)
    val factory = H264Tee.ProducerFactory { _, _, _, _ ->
      if (consumed) throw IllegalStateException("only one fixture available")
      consumed = true
      val bytes = file.readBytes()
      val chunk = (bytes.size + bursts - 1) / bursts
      object : H264Tee.ProducerHandle {
        override val input: InputStream = object : InputStream() {
          private var pos = 0
          private var burstStart = 0

          override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
          }

          override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (pos >= bytes.size) {
              finished.countDown()
              return -1
            }
            if (pos == burstStart) {
              if (pos > 0) Thread.sleep(gapMs)
              burstStart = minOf(pos + chunk, bytes.size)
            }
            val n = minOf(len, burstStart - pos)
            System.arraycopy(bytes, pos, b, off, n)
            pos += n
            return n
          }
        }

        override fun close() {}
      }
    }
    return BurstFeed(factory, finished)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Fixtures / probes
  // ──────────────────────────────────────────────────────────────────────────

  private fun generateH264Fixture(target: File, frames: Int): File {
    val pb = ProcessBuilder(
      "ffmpeg", "-y",
      "-f", "lavfi",
      "-i", "testsrc=size=320x240:rate=15",
      "-frames:v", frames.toString(),
      "-c:v", "libx264",
      "-preset", "ultrafast",
      // No B-frames, like screenrecord and baguette — the mux's monotonic-DTS assumption.
      "-tune", "zerolatency",
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

  /**
   * Polls the mux's output size until it has been unchanged for [stableMs] (with content present),
   * or until [timeoutMs] elapses. The wall-clock mux writes the file live as bytes flow, so a stable
   * size means the finite fixture has fully drained into ffmpeg.
   */
  private fun waitForContentStable(consumer: WallClockMuxConsumer, out: File, stableMs: Long, timeoutMs: Long) {
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

  private fun readCodec(file: File): String? = ffprobe(file, "stream=codec_name")

  private fun readFormat(file: File): String? = ffprobe(file, "format=format_name")

  private fun readDurationSeconds(file: File): Double? = ffprobe(file, "format=duration")?.toDoubleOrNull()

  /** Decodes the whole file to count frames — the container's header can't be trusted for this. */
  private fun readFrameCount(file: File): Int? =
    runFfprobe(file, "stream=nb_read_frames", count = true)?.toIntOrNull()

  private fun ffprobe(file: File, entries: String): String? = runFfprobe(file, entries, count = false)

  /** Every packet's PTS rounded to whole milliseconds, in file order. */
  private fun readPacketPtsMs(file: File): List<Long> =
    runFfprobe(file, "packet=pts_time", count = false)
      ?.lineSequence()
      ?.mapNotNull { it.trim().toDoubleOrNull() }
      ?.map { Math.round(it * 1000.0) }
      ?.toList()
      .orEmpty()

  /**
   * The frame rate the raw fixture's own bitstream declares — what ffmpeg guesses from it, and the
   * grid a time base derived from that guess would put every frame on.
   */
  private fun readDeclaredFrameRate(h264: File): Int? = try {
    val process = ProcessBuilder(
      "ffprobe", "-v", "error", "-f", "h264",
      "-select_streams", "v:0", "-show_entries", "stream=r_frame_rate",
      "-of", "default=nw=1:nk=1", h264.absolutePath,
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      null
    } else {
      // ffprobe prints a rational, e.g. "30/1".
      output.substringBefore('/').toIntOrNull()?.takeIf { it > 0 }
    }
  } catch (_: Exception) {
    null
  }

  /** Whether [ptsMs] is exactly where a time base derived from [rate] would have placed a frame. */
  private fun onFrameRateGrid(ptsMs: Long, rate: Int): Boolean {
    val tick = Math.round(ptsMs * rate / 1000.0)
    return ptsMs == Math.round(tick * 1000.0 / rate)
  }

  private fun runFfprobe(file: File, entries: String, count: Boolean): String? = try {
    val process = ProcessBuilder(
      listOfNotNull(
        "ffprobe",
        "-v", "error",
        "-select_streams", "v:0",
        "-count_frames".takeIf { count },
        "-show_entries", entries,
        "-of", "default=nw=1:nk=1",
        file.absolutePath,
      ),
    ).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText().trim()
    if (!process.waitFor(30, TimeUnit.SECONDS)) {
      process.destroyForcibly()
      null
    } else if (process.exitValue() != 0) {
      null
    } else {
      output.takeIf { it.isNotEmpty() && it != "N/A" }
    }
  } catch (_: Exception) {
    null
  }

  private fun webmToolsAvailable(): Boolean {
    if (!ffmpegOnPath() || !ffprobeOnPath()) {
      println("skipping: ffmpeg or ffprobe not on PATH")
      return false
    }
    if (!WallClockMuxConsumer.Output.vp9EncoderAvailable()) {
      println("skipping: this ffmpeg has no libvpx-vp9 encoder")
      return false
    }
    return true
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

  @Test
  fun `a drain that is still writing is given as long as it needs, and one that has stalled is not`() {
    // Stop detaches the tee, which flushes whatever the ring buffer still holds — up to ~100 s of
    // feed. On a loaded box that flush is slow work, not a fault, and killing it mid-write
    // truncates a recording that was about to be complete. The wedge test has to be "no bytes are
    // moving", not "this is taking a while".
    var clock = 0L
    var bytes = 0L
    var alive = true
    val backlogged = WallClockMuxConsumer.awaitDrain(
      isAlive = { alive },
      drainedBytes = { bytes },
      joinSlice = { slice ->
        clock += slice
        // Slow, but always moving — and for far longer than the stall budget.
        bytes += 1_024
        if (clock >= 30_000) alive = false
      },
      nowMs = { clock },
    )
    assertTrue(backlogged, "a drain still moving bytes after 30s must be allowed to finish, not killed")

    clock = 0L
    val stalled = WallClockMuxConsumer.awaitDrain(
      isAlive = { true },
      drainedBytes = { 4_096L }, // never advances: blocked in sink.write against a stuck ffmpeg
      joinSlice = { slice -> clock += slice },
      nowMs = { clock },
    )
    assertFalse(stalled, "a drain that has moved no bytes at all is wedged and must be abandoned")
    assertTrue(clock in 2_000..3_000, "the wedge verdict must come at the stall budget, not later; was ${clock}ms")
  }

  @Test
  fun `a drain that never finishes is still bounded, however slowly it trickles`() {
    // The progress rule must not become "wait forever as long as something moves" — a nearly
    // stuck ffmpeg accepting a few bytes per second would otherwise hold a session's teardown open.
    var clock = 0L
    var bytes = 0L
    val trickling = WallClockMuxConsumer.awaitDrain(
      isAlive = { true },
      drainedBytes = { bytes },
      joinSlice = { slice -> clock += slice; bytes += 1 },
      nowMs = { clock },
    )
    assertFalse(trickling, "an endless trickle must still hit the absolute bound")
    assertTrue(clock in 60_000..61_000, "the absolute bound must be about a minute; was ${clock}ms")
  }

}
