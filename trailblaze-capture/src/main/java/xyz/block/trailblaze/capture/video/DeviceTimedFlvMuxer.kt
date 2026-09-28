package xyz.block.trailblaze.capture.video

import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.abs

/**
 * Turns a tee's Annex-B stream into an FLV stream whose every frame is stamped with the time the
 * DEVICE drew it, for ffmpeg to read in place of the raw stream it would otherwise stamp on arrival.
 *
 * ### Why not stamp on arrival
 * A frame reaches the host after the device has encoded it and adb has carried it over, and on a
 * loaded machine that delay is neither small nor steady: measured on an emulator under CPU load,
 * frames arrived a median 26 ms and a 99th percentile 578 ms later than the fastest one. Stamped
 * on arrival, a screen change lands that much after the tap that caused it. The producer
 * ([ScrcpyAnnexBInputStream]) knows each frame's device presentation time and writes it into the
 * stream as a [FrameTimeSei]; this class reads it back and puts it where ffmpeg will use it.
 *
 * ### Why FLV
 * ffmpeg needs a container to take timestamps from a pipe, and FLV is the simplest one it reads:
 * an eleven-byte header per frame carrying a millisecond timestamp, the same resolution the WebM
 * recording is written at.
 *
 * ### Mapping the device clock to the host clock
 * Each [FrameTimeSei] carries both clocks: the device PTS and the host arrival time. Their
 * difference is the clock offset plus that frame's delay, so the smallest difference seen is the
 * offset plus the *least* delay any frame had — the best estimate available of when a frame was
 * really drawn, in host time. [firstFrameEpochMs] and [lastFrameEpochMs] use it.
 *
 * A frame with no timing (a stream from `screenrecord`, which has none) is timed by when this
 * class received it (see [flushIdle]), moved onto the device timeline by the same offset: the
 * old arrival-stamped behavior, and no worse than it.
 *
 * ### Producer restarts
 * The file keeps the timeline of the stream it began with. A respawned scrcpy server counts on the
 * same device clock, so when a later stream's (after [restart]) first frame lands within
 * [SAME_CLOCK_TOLERANCE_US] of the old offset, the old offset carries on and keeps being refined.
 * Re-learning it would place the first frames by the new stream's few samples, and a slow first
 * frame (usually its keyframe) would squash the frames after it together.
 *
 * A stream that counts from a different origin — screenrecord replaced by scrcpy, or a rebooted
 * device — is carried onto the file's timeline through the host clock, by its own offset. Until
 * that offset settles, its first frames can be squashed the same way.
 *
 * Not thread-safe; one instance per recording, driven from its drain thread.
 */
internal class DeviceTimedFlvMuxer(
  /** Host epoch the recording started at. A frame the tee replayed from before then is held here. */
  private val startedAtHostMs: Long,
) {
  private val splitter = AnnexBAccessUnitSplitter()
  private var headerWritten = false
  private var sps: ByteArray? = null
  private var pps: ByteArray? = null

  /**
   * Host time minus file-timeline time, in µs; null until the first frame is written. When the file
   * starts on a timed stream this is that stream's [streamOffsetUs], refined until it restarts;
   * when it starts untimed the file's timeline is the host clock itself and this is zero.
   */
  private var offsetUs: Long? = null
  private var offsetFollowsStream = false

  /** Smallest (host arrival − device PTS) seen in the current stream, in µs. */
  private var streamOffsetUs: Long? = null

  /** The last timed stream's offset, and whether the file followed it, kept across a [restart]. */
  private var previousStreamOffsetUs: Long? = null
  private var previousOffsetFollowedStream = false
  private var firstDeviceUs: Long? = null

  /** True while the only frame written is one the tee replayed from before the recording began. */
  private var onlyHeldFrameWritten = false
  private var lastTimestampMs = -1L

  /** Frames written so far. */
  var frameCount: Int = 0
    private set

  /** Timed frames among [frameCount]; zero means the feed carried no device times at all. */
  var timedFrameCount: Int = 0
    private set

  /** Host epoch of the first frame written, or null before any. */
  val firstFrameEpochMs: Long?
    get() = firstDeviceUs?.let { (it + (offsetUs ?: 0L)) / 1000 }

  /** Host epoch of the last frame written, or null before any. */
  val lastFrameEpochMs: Long?
    get() = firstDeviceUs?.let { (it + lastTimestampMs * 1000 + (offsetUs ?: 0L)) / 1000 }

  /** Feeds tee bytes received at [nowMs]; every frame they complete is written to [out]. */
  fun feed(bytes: ByteArray, length: Int, nowMs: Long, out: OutputStream) {
    splitter.feed(bytes, 0, length) { write(it, nowMs, out) }
  }

  /**
   * Writes the frame still waiting for a successor once the feed has gone quiet, stamped (if it has
   * no device time) with [lastInputMs], when its bytes arrived. Without this a frame with no timing
   * would be stamped when the next one began — on a still screen, whenever the screen next changed.
   */
  fun flushIdle(lastInputMs: Long, out: OutputStream) {
    // A frame with a device time needs no arrival stamp, and flushing would cut one whose bytes
    // stalled mid-transfer: the rest would arrive after it had been written as complete.
    if (splitter.pendingNals().any { FrameTimeSei.parse(it) != null }) return
    splitter.flushPending { write(it, lastInputMs, out) }
  }

  /** A new producer generation: finish the old one's last frame and parse the new one fresh. */
  fun restart(nowMs: Long, out: OutputStream) {
    splitter.finish { write(it, nowMs, out) }
    splitter.reset()
    if (streamOffsetUs != null) {
      previousStreamOffsetUs = streamOffsetUs
      previousOffsetFollowedStream = offsetFollowsStream
    }
    streamOffsetUs = null
    offsetFollowsStream = false
  }

  /** Writes the frame still waiting for a successor, at the end of the recording. */
  fun finish(nowMs: Long, out: OutputStream) {
    splitter.finish { write(it, nowMs, out) }
  }

  private fun write(accessUnit: H264AccessUnit, nowMs: Long, out: OutputStream) {
    val nals = AnnexB.nals(accessUnit.bytes)
    val timing = nals.firstNotNullOfOrNull(FrameTimeSei::parse)
    if (timing != null) {
      val frameOffsetUs = timing.hostArrivalMs * 1000 - timing.devicePtsUs
      val current = streamOffsetUs
      val previous = previousStreamOffsetUs
      streamOffsetUs = when {
        current != null -> minOf(current, frameOffsetUs)
        // First timed frame after a restart, on the old stream's clock: carry the old offset on.
        previous != null && abs(frameOffsetUs - previous) < SAME_CLOCK_TOLERANCE_US -> {
          offsetFollowsStream = previousOffsetFollowedStream
          minOf(previous, frameOffsetUs)
        }
        else -> frameOffsetUs
      }
    }
    val frameSps = nals.lastOrNull { nalType(it) == NAL_SPS }
    val framePps = nals.lastOrNull { nalType(it) == NAL_PPS }
    // Nothing is decodable before the first parameter sets, so nothing before them may set the
    // file's zero point either.
    if (sps == null && (frameSps == null || framePps == null)) return

    if (offsetUs == null) {
      offsetUs = streamOffsetUs ?: 0L
      offsetFollowsStream = streamOffsetUs != null
    } else if (offsetFollowsStream) {
      offsetUs = streamOffsetUs
    }
    val fileOffsetUs = offsetUs!!
    val streamOffset = streamOffsetUs
    // A frame received before the recording began is the tee's cached keyframe, replayed so the
    // file starts decodable. It still shows the screen as it is now, so it is placed at the start.
    val heldUs = if (timing != null) maxOf(0L, (startedAtHostMs - timing.hostArrivalMs) * 1000) else 0L
    val deviceUs = if (timing != null && streamOffset != null) {
      timedFrameCount++
      timing.devicePtsUs + heldUs + (streamOffset - fileOffsetUs)
    } else {
      nowMs * 1000 - fileOffsetUs
    }
    // The held frame's place came from its own offset, which includes however late it arrived. Until
    // a live frame is written, keep it pinned to the recording's start as a faster frame improves the
    // offset; otherwise the file would begin before the recording did, by that difference.
    if (onlyHeldFrameWritten) firstDeviceUs = startedAtHostMs * 1000 - fileOffsetUs
    val first = firstDeviceUs ?: deviceUs.also { firstDeviceUs = it }
    onlyHeldFrameWritten = (frameCount == 0 || onlyHeldFrameWritten) && heldUs > 0
    // FLV wants increasing timestamps; two frames in one millisecond, or a replayed keyframe held
    // at the start, are nudged forward a millisecond rather than written out of order.
    val timestampMs = maxOf(lastTimestampMs + 1, (deviceUs - first) / 1000)
    lastTimestampMs = timestampMs

    if (!headerWritten) {
      out.write(FLV_HEADER)
      headerWritten = true
    }
    if (frameSps != null && framePps != null &&
      !(frameSps.contentEquals(sps) && framePps.contentEquals(pps))
    ) {
      sps = frameSps
      pps = framePps
      writeTag(out, timestampMs, keyFrame = true, packetType = AVC_SEQUENCE_HEADER, body = decoderConfig(frameSps, framePps))
    }

    val avcc = ByteArrayOutputStream(accessUnit.bytes.size + 16)
    nals.filter { FrameTimeSei.parse(it) == null }.forEach { nal ->
      avcc.write(intBytes(nal.size))
      avcc.write(nal)
    }
    writeTag(out, timestampMs, keyFrame = accessUnit.isKeyFrame, packetType = AVC_NALU, body = avcc.toByteArray())
    frameCount++
  }

  private fun writeTag(out: OutputStream, timestampMs: Long, keyFrame: Boolean, packetType: Int, body: ByteArray) {
    val dataSize = 5 + body.size
    val tag = ByteArrayOutputStream(11 + dataSize + 4)
    tag.write(TAG_TYPE_VIDEO)
    tag.write(uint24(dataSize.toLong()))
    tag.write(uint24(timestampMs and 0xffffff))
    tag.write(((timestampMs ushr 24) and 0xff).toInt())
    tag.write(uint24(0)) // stream id
    tag.write(((if (keyFrame) FRAME_KEY else FRAME_INTER) shl 4) or CODEC_AVC)
    tag.write(packetType)
    tag.write(uint24(0)) // composition time: no B-frames, so decode order is display order
    tag.write(body)
    tag.write(intBytes(11 + dataSize))
    out.write(tag.toByteArray())
  }

  /** The `AVCDecoderConfigurationRecord` FLV carries as a stream's sequence header. */
  private fun decoderConfig(sps: ByteArray, pps: ByteArray): ByteArray = ByteArrayOutputStream().apply {
    write(1) // configurationVersion
    write(sps.getOrElse(1) { 0 }.toInt() and 0xff) // profile
    write(sps.getOrElse(2) { 0 }.toInt() and 0xff) // profile compatibility
    write(sps.getOrElse(3) { 0 }.toInt() and 0xff) // level
    write(0xff) // 4-byte NAL lengths
    write(0xe1) // one SPS
    write(uint16(sps.size))
    write(sps)
    write(1) // one PPS
    write(uint16(pps.size))
    write(pps)
  }.toByteArray()

  private fun nalType(nal: ByteArray) = nal[0].toInt() and 0x1f

  private fun uint16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())

  private fun uint24(v: Long) = byteArrayOf((v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

  private fun intBytes(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())

  companion object {
    /**
     * How far a restarted stream's first offset may sit from the old one and still count as the same
     * clock. It covers a first frame's extra delay (seconds at worst under load); a rebooted device's
     * clock is off by the old uptime, which is more.
     */
    internal const val SAME_CLOCK_TOLERANCE_US = 10_000_000L

    /** `FLV`, version 1, video only, header size 9, then the zero "previous tag size" before tag 1. */
    private val FLV_HEADER = byteArrayOf(0x46, 0x4c, 0x56, 1, 1, 0, 0, 0, 9, 0, 0, 0, 0)
    private const val TAG_TYPE_VIDEO = 9
    private const val FRAME_KEY = 1
    private const val FRAME_INTER = 2
    private const val CODEC_AVC = 7
    private const val AVC_SEQUENCE_HEADER = 0
    private const val AVC_NALU = 1
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
  }
}
