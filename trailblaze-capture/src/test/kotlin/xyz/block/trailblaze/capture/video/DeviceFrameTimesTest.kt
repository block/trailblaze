package xyz.block.trailblaze.capture.video

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform

/**
 * The device-frame-time path, piece by piece: the SEI that carries a frame's device time through
 * the tee, scrcpy's socket framing, the FLV rewrap that hands those times to ffmpeg, and the choice
 * between scrcpy and `screenrecord`. Everything here is pure; [WallClockMuxConsumerTest] runs the
 * same path through a real ffmpeg.
 */
class DeviceFrameTimesTest {

  // ── FrameTimeSei ────────────────────────────────────────────────────────────

  @Test
  fun `a frame time survives the trip through an SEI, zero bytes and all`() {
    // Zero-heavy values force emulation-prevention bytes; a missed escape would read as a start code.
    val timing = FrameTimeSei(devicePtsUs = 0x0000_0001_0000_0003L, hostArrivalMs = 0x0000_0000_0000_0102L)
    val nal = timing.toAnnexBNal()

    val payload = nal.copyOfRange(4, nal.size)
    assertEquals(listOf(payload.toList()), AnnexB.nals(nal).map { it.toList() }, "one NAL, no stray start code inside")
    assertEquals(timing, FrameTimeSei.parse(payload))
  }

  @Test
  fun `an SEI that is not ours is never read as a frame time`() {
    val ours = AnnexB.nals(FrameTimeSei(devicePtsUs = 5, hostArrivalMs = 6).toAnnexBNal()).single()
    val otherUuid = ours.copyOf().also { it[3] = (it[3] + 1).toByte() } // byte 3 is the UUID's first
    assertNull(FrameTimeSei.parse(otherUuid), "someone else's user_data_unregistered")
    assertNull(FrameTimeSei.parse(byteArrayOf(0x06, 0x01, 0x01, 0x00, 0x80.toByte())), "a different SEI payload type")
    assertNull(FrameTimeSei.parse(IDR), "not an SEI at all")
  }

  @Test
  fun `annex-b parsing takes both start code lengths and leaves trailing zeros to the next code`() {
    val stream = byteArrayOf(0, 0, 0, 1) + SPS + byteArrayOf(0, 0, 1) + PPS + byteArrayOf(0, 0, 0, 0, 1) + IDR
    assertEquals(listOf(SPS, PPS, IDR).map { it.toList() }, AnnexB.nals(stream).map { it.toList() })
  }

  // ── ScrcpyAnnexBInputStream ─────────────────────────────────────────────────

  @Test
  fun `scrcpy packets become an annex-b stream with each frame's device time ahead of it`() {
    val config = annexB(SPS, PPS)
    val key = annexB(IDR)
    val inter = annexB(P_SLICE)
    val socket = ByteArrayOutputStream().also { out ->
      DataOutputStream(out).apply {
        writeInt(0x80000000.toInt()); writeInt(720); writeInt(1280) // session packet: no payload
        writeLong(1L shl 62); writeInt(config.size); write(config) // config: passes through as-is
        writeLong((1L shl 61) or 1_000_000L); writeInt(key.size); write(key) // key frame at 1.0s
        writeLong(1_016_667L); writeInt(inter.size); write(inter) // next frame 16.667ms later
      }
    }.toByteArray()
    var clock = 5_000L
    val out = ScrcpyAnnexBInputStream(ByteArrayInputStream(socket), nowMs = { clock++ }).readBytes()

    val expected = config +
      FrameTimeSei(devicePtsUs = 1_000_000L, hostArrivalMs = 5_000L).toAnnexBNal() + key +
      FrameTimeSei(devicePtsUs = 1_016_667L, hostArrivalMs = 5_001L).toAnnexBNal() + inter
    assertContentEquals(expected, out, "the key-frame flag is not part of the PTS, and config gets no timing")
  }

  @Test
  fun `a scrcpy packet size far beyond any frame fails the stream instead of being allocated`() {
    listOf(Int.MAX_VALUE, -1).forEach { size ->
      val socket = ByteArrayOutputStream().also { out ->
        DataOutputStream(out).apply { writeLong(1_000_000L); writeInt(size) }
      }.toByteArray()
      assertFailsWith<java.io.IOException>("size $size") {
        ScrcpyAnnexBInputStream(ByteArrayInputStream(socket), nowMs = { 0L }).readBytes()
      }
    }
  }

  // ── DeviceTimedFlvMuxer ─────────────────────────────────────────────────────

  @Test
  fun `frames are timed by when the device drew them, not when they arrived`() {
    // All three frames arrive at the same host instant — a burst released after an encoder stall —
    // but the device drew them 250ms and then 600ms apart. Arrival time would collapse them.
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 10_000)
    val out = ByteArrayOutputStream()
    val arrival = 10_900L
    val stream = timedFrame(ptsUs = 50_000_000, arrivalMs = 10_700, SPS, PPS, IDR) +
      timedFrame(ptsUs = 50_250_000, arrivalMs = 10_960, P_SLICE) +
      timedFrame(ptsUs = 50_850_000, arrivalMs = 11_600, P_SLICE)
    muxer.feed(stream, stream.size, arrival, out)
    muxer.finish(arrival, out)

    val frames = flvTags(out.toByteArray()).filter { it.packetType == 1 }
    assertEquals(listOf(0L, 250L, 850L), frames.map { it.timestampMs })
    assertEquals(listOf(true, false, false), frames.map { it.keyFrame })
    assertEquals(3, muxer.timedFrameCount)
    // Offset = the smallest (arrival − pts), here the first frame's: 10_700ms − 50_000ms. Its epoch is
    // its own arrival; the last frame's is 850ms later, not the 11_600ms it happened to arrive at.
    assertEquals(10_700L, muxer.firstFrameEpochMs)
    assertEquals(11_550L, muxer.lastFrameEpochMs)
  }

  @Test
  fun `a restarted stream counting from a new origin continues the timeline`() {
    // The respawned server's clock starts somewhere else entirely. Read against the first stream's
    // origin, its frames would all sit in the past and collapse onto one another.
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 10_000)
    val out = ByteArrayOutputStream()
    val first = timedFrame(ptsUs = 50_000_000, arrivalMs = 10_000, SPS, PPS, IDR) +
      timedFrame(ptsUs = 50_100_000, arrivalMs = 10_100, P_SLICE)
    muxer.feed(first, first.size, 10_100, out)
    muxer.restart(10_500, out)
    val second = timedFrame(ptsUs = 1_000_000, arrivalMs = 11_000, SPS, PPS, IDR) +
      timedFrame(ptsUs = 1_200_000, arrivalMs = 11_200, P_SLICE)
    muxer.feed(second, second.size, 11_200, out)
    muxer.finish(11_300, out)

    val frames = flvTags(out.toByteArray()).filter { it.packetType == 1 }
    assertEquals(listOf(0L, 100L, 1_000L, 1_200L), frames.map { it.timestampMs })
    assertEquals(10_000L, muxer.firstFrameEpochMs)
    assertEquals(11_200L, muxer.lastFrameEpochMs)
  }

  @Test
  fun `a respawned server on the same device clock keeps the frame spacing`() {
    // The new stream's keyframe arrives 500ms late and the frame after it only 20ms late. Re-learning
    // the offset from those two would put the second frame 480ms before the first, then clamp it on.
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 10_000)
    val out = ByteArrayOutputStream()
    val first = timedFrame(ptsUs = 50_000_000, arrivalMs = 10_000, SPS, PPS, IDR) +
      timedFrame(ptsUs = 50_100_000, arrivalMs = 10_100, P_SLICE)
    muxer.feed(first, first.size, 10_100, out)
    muxer.restart(10_500, out)
    val second = timedFrame(ptsUs = 51_000_000, arrivalMs = 11_500, SPS, PPS, IDR) +
      timedFrame(ptsUs = 51_100_000, arrivalMs = 11_120, P_SLICE)
    muxer.feed(second, second.size, 11_500, out)
    muxer.finish(11_600, out)

    val frames = flvTags(out.toByteArray()).filter { it.packetType == 1 }
    assertEquals(listOf(0L, 100L, 1_000L, 1_100L), frames.map { it.timestampMs })
    assertEquals(11_100L, muxer.lastFrameEpochMs)
  }

  @Test
  fun `device times after an untimed start are placed on the host clock the file began on`() {
    // screenrecord first (scrcpy was in its retry window), scrcpy after a restart.
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 0)
    val out = ByteArrayOutputStream()
    listOf(annexB(SPS, PPS, IDR) to 2_000L, annexB(P_SLICE) to 2_300L).forEach { (bytes, at) ->
      muxer.feed(bytes, bytes.size, at, out)
    }
    muxer.restart(2_400, out)
    val timed = timedFrame(ptsUs = 7_000_000, arrivalMs = 3_000, SPS, PPS, IDR) +
      timedFrame(ptsUs = 7_500_000, arrivalMs = 3_600, P_SLICE)
    muxer.feed(timed, timed.size, 3_600, out)
    muxer.finish(3_700, out)

    val timestamps = flvTags(out.toByteArray()).filter { it.packetType == 1 }.map { it.timestampMs }
    assertEquals(4, timestamps.size)
    val start = assertNotNull(muxer.firstFrameEpochMs)
    assertEquals(listOf(3_000L, 3_500L), timestamps.takeLast(2).map { start + it }, "on the host clock: $timestamps")
  }

  @Test
  fun `the timing SEI stays out of the file and the parameter sets go in a sequence header`() {
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 0)
    val out = ByteArrayOutputStream()
    val stream = timedFrame(ptsUs = 1_000, arrivalMs = 1, SPS, PPS, IDR)
    muxer.feed(stream, stream.size, 1, out)
    muxer.finish(1, out)

    val tags = flvTags(out.toByteArray())
    assertEquals(listOf(0, 1), tags.map { it.packetType }, "sequence header, then the frame")
    val config = tags[0].body
    assertEquals(SPS[1], config[1], "profile comes from the SPS")
    val frameNals = avccNals(tags[1].body)
    assertEquals(listOf(SPS, PPS, IDR).map { it.toList() }, frameNals.map { it.toList() }, "no SEI in the frame")
  }

  @Test
  fun `a keyframe the tee replays from before the recording is placed at its start`() {
    // The tee seeds a late joiner with its cached keyframe, drawn a minute before this recording.
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 100_000)
    val out = ByteArrayOutputStream()
    val stream = timedFrame(ptsUs = 40_000_000, arrivalMs = 40_010, SPS, PPS, IDR) +
      timedFrame(ptsUs = 100_500_000, arrivalMs = 100_520, P_SLICE)
    muxer.feed(stream, stream.size, 100_520, out)
    muxer.finish(100_520, out)

    val frames = flvTags(out.toByteArray()).filter { it.packetType == 1 }
    // Held at the start (100_000ms host = 99_990ms device by its own offset), not a minute back.
    assertEquals(listOf(0L, 510L), frames.map { it.timestampMs })
    assertEquals(100_000L, muxer.firstFrameEpochMs)
  }

  @Test
  fun `a replayed keyframe that arrived late stays at the start once a faster frame refines the offset`() {
    // Device clock = host + 40s. The cached keyframe arrived 500ms after it was drawn; the first live
    // frame, drawn at host 10_200ms, arrives 20ms late and lowers the offset estimate by 480ms.
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 10_000)
    val out = ByteArrayOutputStream()
    val stream = timedFrame(ptsUs = 49_000_000, arrivalMs = 9_500, SPS, PPS, IDR) +
      timedFrame(ptsUs = 50_200_000, arrivalMs = 10_220, P_SLICE)
    muxer.feed(stream, stream.size, 10_220, out)
    muxer.finish(10_220, out)

    // Not 700ms: that would hold the replayed frame 480ms too long, from before the recording began.
    assertEquals(listOf(0L, 220L), flvTags(out.toByteArray()).filter { it.packetType == 1 }.map { it.timestampMs })
    assertEquals(10_000L, muxer.firstFrameEpochMs)
  }

  @Test
  fun `a feed with no device times falls back to host time and still records`() {
    // Only if scrcpy is replaced by screenrecord mid-session. With no SEI, a frame is known to be
    // complete only once the next begins, so it is timed then — a frame late, never early.
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 0)
    val out = ByteArrayOutputStream()
    listOf(annexB(SPS, PPS, IDR) to 2_000L, annexB(P_SLICE) to 2_300L, annexB(P_SLICE) to 2_700L).forEach { (bytes, at) ->
      muxer.feed(bytes, bytes.size, at, out)
    }
    muxer.finish(3_000, out)

    assertEquals(0, muxer.timedFrameCount)
    assertEquals(3, muxer.frameCount)
    val timestamps = flvTags(out.toByteArray()).filter { it.packetType == 1 }.map { it.timestampMs }
    assertEquals(timestamps.sorted().distinct(), timestamps, "increasing: $timestamps")
    assertTrue(muxer.firstFrameEpochMs!! in 2_000L..3_000L, "host time, no offset: ${muxer.firstFrameEpochMs}")
  }

  @Test
  fun `timestamps never repeat or run backwards`() {
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 0)
    val out = ByteArrayOutputStream()
    val stream = timedFrame(ptsUs = 1_000_000, arrivalMs = 1_000, SPS, PPS, IDR) +
      timedFrame(ptsUs = 1_000_400, arrivalMs = 1_001, P_SLICE) + // same millisecond
      timedFrame(ptsUs = 999_000, arrivalMs = 1_002, P_SLICE) // earlier than both
    muxer.feed(stream, stream.size, 1_002, out)
    muxer.finish(1_002, out)

    assertEquals(listOf(0L, 1L, 2L), flvTags(out.toByteArray()).filter { it.packetType == 1 }.map { it.timestampMs })
  }

  @Test
  fun `nothing before the first parameter sets reaches the file or sets its start`() {
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 0)
    val out = ByteArrayOutputStream()
    val stream = timedFrame(ptsUs = 1_000_000, arrivalMs = 1_000, P_SLICE) +
      timedFrame(ptsUs = 3_000_000, arrivalMs = 3_000, SPS, PPS, IDR)
    muxer.feed(stream, stream.size, 3_000, out)
    muxer.finish(3_000, out)

    assertEquals(1, muxer.frameCount)
    assertEquals(3_000L, muxer.firstFrameEpochMs)
  }

  @Test
  fun `a frame with no device time that ends a burst is stamped when it arrived, not when the next one does`() {
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 0)
    val out = ByteArrayOutputStream()
    val first = annexB(SPS, PPS, IDR)
    muxer.feed(first, first.size, 2_000, out)
    muxer.flushIdle(lastInputMs = 2_000, out) // the drain loop, 20ms into a still screen
    val second = annexB(P_SLICE)
    muxer.feed(second, second.size, 9_000, out)
    muxer.flushIdle(lastInputMs = 9_000, out)

    assertEquals(listOf(0L, 7_000L), flvTags(out.toByteArray()).filter { it.packetType == 1 }.map { it.timestampMs })
    assertEquals(2_000L, muxer.firstFrameEpochMs)
  }

  @Test
  fun `a quiet feed never cuts a timed frame whose bytes are still arriving`() {
    val muxer = DeviceTimedFlvMuxer(startedAtHostMs = 0)
    val out = ByteArrayOutputStream()
    val stream = timedFrame(ptsUs = 1_000_000, arrivalMs = 2_000, SPS, PPS, IDR) +
      timedFrame(ptsUs = 1_100_000, arrivalMs = 2_100, P_SLICE)
    val split = stream.size - (FrameTimeSei(0, 0).toAnnexBNal().size + P_SLICE.size + 4) - 2
    muxer.feed(stream.copyOfRange(0, split), split, 2_000, out) // the keyframe's last bytes stall
    muxer.flushIdle(lastInputMs = 2_000, out)
    muxer.feed(stream.copyOfRange(split, stream.size), stream.size - split, 2_100, out)
    muxer.finish(2_200, out)

    val frames = flvTags(out.toByteArray()).filter { it.packetType == 1 }
    assertEquals(2, frames.size)
    assertContentEquals(IDR, avccNals(frames[0].body).last(), "the keyframe arrives whole")
  }

  @Test
  fun `scrcpy is reached on the adb server's host`() {
    assertEquals("127.0.0.1", AdbExecOut.serverHostOf(null))
    assertEquals("127.0.0.1", AdbExecOut.serverHostOf("tcp:localhost:5037"))
    assertEquals("10.0.0.7", AdbExecOut.serverHostOf("tcp:10.0.0.7:5037"))
    assertEquals("adb-host.internal", AdbExecOut.serverHostOf("tcp:adb-host.internal:5037"))
    assertEquals("::1", AdbExecOut.serverHostOf("tcp:[::1]:5037"))
    assertEquals("127.0.0.1", AdbExecOut.serverHostOf("tcp:5037"), "a port alone means this machine")
  }

  // ── Source selection ────────────────────────────────────────────────────────

  @Test
  fun `scrcpy is used when it starts, and screenrecord takes over when it cannot`() {
    val calls = mutableListOf<String>()
    fun factory(name: String, fails: Boolean = false) = H264Tee.ProducerFactory { _, _, _, _ ->
      calls += name
      if (fails) error("$name failed")
      object : H264Tee.ProducerHandle {
        override val input = ByteArray(0).inputStream()
        override fun close() {}
      }
    }
    var clock = 0L

    AndroidScreenProducerFactory(scrcpy = factory("scrcpy"), screenrecord = factory("screenrecord"))
      .spawn(device, "720x1280", "4000000", unlimited = true)
    assertEquals(listOf("scrcpy"), calls)

    calls.clear()
    val flaky = AndroidScreenProducerFactory(
      scrcpy = factory("scrcpy", fails = true),
      screenrecord = factory("screenrecord"),
      screenrecordAvailable = { true },
      nowMs = { clock },
    )
    flaky.spawn(device, "720x1280", "4000000", unlimited = true)
    flaky.spawn(device, "720x1280", "4000000", unlimited = true)
    assertEquals(listOf("scrcpy", "screenrecord", "screenrecord"), calls, "a failed device skips scrcpy for a while")
    clock += AndroidScreenProducerFactory.RETRY_AFTER_MS
    calls.clear()
    flaky.spawn(device, "720x1280", "4000000", unlimited = true)
    assertEquals(listOf("scrcpy", "screenrecord"), calls, "and tries it again once the window passes")
    clock += AndroidScreenProducerFactory.RETRY_AFTER_MS
    calls.clear()
    flaky.respawn(device, "720x1280", "4000000", unlimited = false)
    assertEquals(listOf("screenrecord"), calls, "but not mid-recording, where a hung server would blank the video")

    calls.clear()
    AndroidScreenProducerFactory(scrcpy = factory("scrcpy"), screenrecord = factory("screenrecord"))
      .respawn(device, "720x1280", "4000000", unlimited = false)
    assertEquals(listOf("scrcpy"), calls, "a scrcpy server that exited is respawned as scrcpy")

    calls.clear()
    AndroidScreenProducerFactory(
      scrcpy = factory("scrcpy"),
      screenrecord = factory("screenrecord"),
      scrcpyEnabled = { false },
      screenrecordAvailable = { true },
    ).spawn(device, "720x1280", "4000000", unlimited = true)
    assertEquals(listOf("screenrecord"), calls, "TRAILBLAZE_ANDROID_VIDEO_SOURCE=screenrecord skips scrcpy")

    calls.clear()
    assertFailsWith<IllegalStateException>("with neither source, spawning fails so capture can fall back") {
      AndroidScreenProducerFactory(
        scrcpy = factory("scrcpy", fails = true),
        screenrecord = factory("screenrecord"),
        screenrecordAvailable = { false },
      ).spawn(device, "720x1280", "4000000", unlimited = true)
    }
    assertEquals(listOf("scrcpy"), calls, "a device with no screenrecord binary never spawns it")
  }

  // ── The bundled server ──────────────────────────────────────────────────────

  @Test
  fun `the bundled scrcpy server is the pinned upstream release`() {
    val resource = assertNotNull(javaClass.getResourceAsStream(AdbScrcpyProducerFactory.RESOURCE), "bundled server missing")
    val bytes = resource.use { it.readBytes() }
    val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    assertEquals(AdbScrcpyProducerFactory.SHA256, sha256)
    assertTrue(AdbScrcpyProducerFactory.RESOURCE.endsWith("v${AdbScrcpyProducerFactory.VERSION}"))
  }

  @Test
  fun `the server is started video-only, orientation-locked, with frame times on`() {
    val command = AdbScrcpyProducerFactory.serverCommand(scid = "0000abcd", videoSize = "720x1560", bitRate = "4000000")
    assertEquals(AdbScrcpyProducerFactory.VERSION, command[command.indexOf("com.genymobile.scrcpy.Server") + 1])
    listOf(
      "scid=0000abcd", "tunnel_forward=true", "audio=false", "control=false", "send_frame_meta=true",
      "video_codec=h264", "video_bit_rate=4000000", "max_size=1560", "capture_orientation=@", "power_on=false",
    ).forEach { assertTrue(it in command, "missing $it: $command") }
    assertEquals(1280, AdbScrcpyProducerFactory.maxSide("720x1280"))
    assertEquals(0, AdbScrcpyProducerFactory.maxSide("unknown"), "0 asks scrcpy for the display's own size")
  }

  // ── Fixtures ────────────────────────────────────────────────────────────────

  private val device = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  private class FlvTag(val timestampMs: Long, val keyFrame: Boolean, val packetType: Int, val body: ByteArray)

  private fun flvTags(flv: ByteArray): List<FlvTag> {
    assertContentEquals(byteArrayOf(0x46, 0x4c, 0x56, 1, 1), flv.copyOfRange(0, 5), "FLV header, video only")
    val tags = mutableListOf<FlvTag>()
    var i = 13
    while (i < flv.size) {
      val size = u(flv, i + 1, 3).toInt()
      val ts = u(flv, i + 4, 3) or (u(flv, i + 7, 1) shl 24)
      val data = flv.copyOfRange(i + 11, i + 11 + size)
      tags += FlvTag(ts, (data[0].toInt() ushr 4) == 1, data[1].toInt(), data.copyOfRange(5, data.size))
      assertEquals(11L + size, u(flv, i + 11 + size, 4), "previous-tag-size must match")
      i += 11 + size + 4
    }
    return tags
  }

  private fun avccNals(body: ByteArray): List<ByteArray> {
    val nals = mutableListOf<ByteArray>()
    var i = 0
    while (i < body.size) {
      val len = u(body, i, 4).toInt()
      nals += body.copyOfRange(i + 4, i + 4 + len)
      i += 4 + len
    }
    return nals
  }

  private fun u(b: ByteArray, off: Int, n: Int): Long = (0 until n).fold(0L) { acc, k -> (acc shl 8) or (b[off + k].toLong() and 0xff) }

  private fun annexB(vararg nals: ByteArray): ByteArray = nals.fold(ByteArray(0)) { acc, nal -> acc + byteArrayOf(0, 0, 0, 1) + nal }

  private fun timedFrame(ptsUs: Long, arrivalMs: Long, vararg nals: ByteArray): ByteArray {
    val (params, slices) = nals.partition { (it[0].toInt() and 0x1f) in 7..8 }
    return annexB(*params.toTypedArray()) +
      FrameTimeSei(devicePtsUs = ptsUs, hostArrivalMs = arrivalMs).toAnnexBNal() +
      annexB(*slices.toTypedArray())
  }

  private companion object {
    val SPS = byteArrayOf(0x67, 0x42, 0xC0.toByte(), 0x1E, 0xAA.toByte())
    val PPS = byteArrayOf(0x68, 0xCE.toByte(), 0x3C, 0x80.toByte())
    // first_mb_in_slice = 0 (a leading 1 bit), so each slice starts a new picture.
    val IDR = byteArrayOf(0x65, 0x88.toByte(), 0x84.toByte(), 0x21)
    val P_SLICE = byteArrayOf(0x41, 0x9A.toByte(), 0x02, 0x11)
  }
}
