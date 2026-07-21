package xyz.block.trailblaze.capture.video

import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import xyz.block.trailblaze.util.Console

/**
 * Drains a shared Android screenrecord stream as browser-decodable H.264 access units.
 *
 * Unlike [LiveFrameConsumer], this path never decodes or re-encodes the video on the daemon. It
 * only finds Annex-B NAL boundaries and groups slices belonging to one coded picture. Each
 * callback therefore maps directly to one WebCodecs `EncodedVideoChunk` in the browser.
 */
class H264AccessUnitConsumer(
  private val tee: H264Tee,
  private val onAccessUnit: (H264AccessUnit) -> Unit,
  private val ringBufferBytes: Int = DEFAULT_RING_BUFFER_BYTES,
) {
  private var consumer: H264Tee.Consumer? = null
  private var drainThread: Thread? = null
  private val stopped = AtomicBoolean(false)

  fun start() {
    check(consumer == null) { "H264 access-unit consumer already started" }
    consumer = tee.attach(ringBufferBytes)
    drainThread =
      Thread(::drain, "live-h264-access-units").apply {
        isDaemon = true
        start()
      }
  }

  fun stop() {
    if (!stopped.compareAndSet(false, true)) return
    consumer?.detach()
    drainThread?.join(2_000)
  }

  private fun drain() {
    val source = consumer ?: return
    val splitter = AnnexBAccessUnitSplitter()
    val buffer = ByteArray(64 * 1024)
    var lastInputNanos = System.nanoTime()
    try {
      while (!stopped.get()) {
        when (val count = source.read(buffer)) {
          H264Tee.READ_RESULT_DETACHED -> break
          H264Tee.READ_RESULT_RESTART -> {
            splitter.finish(onAccessUnit)
            splitter.reset()
          }
          0 -> {
            if (System.nanoTime() - lastInputNanos >= IDLE_ACCESS_UNIT_FLUSH_NANOS) {
              splitter.flushPending(onAccessUnit)
            }
            Thread.sleep(IDLE_SLEEP_MILLIS)
          }
          else -> {
            splitter.feed(buffer, 0, count, onAccessUnit)
            lastInputNanos = System.nanoTime()
          }
        }
      }
    } catch (e: InterruptedException) {
      Thread.currentThread().interrupt()
    } catch (e: Exception) {
      if (!stopped.get()) {
        Console.log("[H264AccessUnitConsumer] drain failed: ${e.message}")
      }
    } finally {
      splitter.finish(onAccessUnit)
    }
  }

  companion object {
    private const val IDLE_SLEEP_MILLIS = 2L
    // A raw Annex-B pipe has no explicit length for its final NAL. screenrecord writes every
    // frame's bytes in a tight burst, then waits roughly 40 ms for the next 25 fps frame (or
    // indefinitely on a still screen). Treating 20 ms without bytes as the end of that burst
    // exposes the first/static frame without waiting for a following start code.
    private const val IDLE_ACCESS_UNIT_FLUSH_NANOS = 20_000_000L
    private const val DEFAULT_RING_BUFFER_BYTES = 20 * 1024 * 1024
  }
}

/** One complete coded picture in Annex-B byte-stream format. */
data class H264AccessUnit(val bytes: ByteArray, val isKeyFrame: Boolean)

/**
 * Streaming Annex-B parser that emits one access unit per coded picture.
 *
 * Android's `screenrecord` stream does not include access-unit-delimiter NALs. VCL NALs do carry
 * the standard `first_mb_in_slice` Exp-Golomb field, so a zero value marks the first slice of a new
 * picture. Parameter-set and supplemental NALs seen between pictures are retained and prepended to
 * the next picture; this ensures an IDR chunk includes the SPS/PPS required by WebCodecs.
 *
 * The most recent SPS/PPS are *also* retained for the whole stream and re-prepended to any keyframe
 * that arrives without them. screenrecord (MediaCodec) emits the parameter sets once in the
 * codec-config buffer at stream start and then periodic IDR keyframes that do NOT repeat them, so
 * without this a later IDR — or the first IDR seen by a mid-stream joiner / after the tee's cached
 * keyframe is refreshed — would be emitted SPS-less and be undecodable by a freshly-configured
 * WebCodecs decoder.
 */
internal class AnnexBAccessUnitSplitter {
  private var unparsed = ByteArray(0)
  private val prefixNals = mutableListOf<ByteArray>()
  private val pictureNals = mutableListOf<ByteArray>()
  private var pictureHasVcl = false
  private var pictureIsKeyFrame = false
  private var retainedSps: ByteArray? = null
  private var retainedPps: ByteArray? = null

  fun feed(source: ByteArray, offset: Int, length: Int, emit: (H264AccessUnit) -> Unit) {
    if (length <= 0) return
    val combined = ByteArray(unparsed.size + length)
    unparsed.copyInto(combined)
    source.copyInto(combined, unparsed.size, offset, offset + length)

    val starts = findStartCodes(combined)
    if (starts.isEmpty()) {
      // Keep a short garbage prefix only until the first start code arrives. Once parsing has
      // begun, [unparsed] always starts with a start code and this branch is only a split marker.
      unparsed = combined.takeLast(MAX_START_CODE_BYTES - 1).toByteArray()
      return
    }
    for (index in 0 until starts.lastIndex) {
      processNal(combined.copyOfRange(starts[index], starts[index + 1]), emit)
    }
    unparsed = combined.copyOfRange(starts.last(), combined.size)
  }

  /** Processes the final NAL and picture when a producer generation ends. */
  fun finish(emit: (H264AccessUnit) -> Unit) {
    flushPending(emit)
    prefixNals.clear()
  }

  /** Emits the last NAL after the producer has been byte-idle long enough to delimit its burst. */
  fun flushPending(emit: (H264AccessUnit) -> Unit) {
    if (unparsed.isNotEmpty() && nalHeaderIndex(unparsed) != null) {
      processNal(unparsed, emit)
    }
    unparsed = ByteArray(0)
    emitPicture(emit)
  }

  fun reset() {
    unparsed = ByteArray(0)
    prefixNals.clear()
    pictureNals.clear()
    pictureHasVcl = false
    pictureIsKeyFrame = false
    // Drop the retained parameter sets: a new producer generation sends its own SPS/PPS at its
    // head, and re-prepending a previous session's parameter sets to it would be incorrect.
    retainedSps = null
    retainedPps = null
  }

  private fun nalTypeOf(nal: ByteArray): Int? =
    nalHeaderIndex(nal)?.let { nal[it].toInt() and NAL_TYPE_MASK }

  private fun pictureContainsNalType(type: Int): Boolean =
    pictureNals.any { nalTypeOf(it) == type }

  private fun processNal(nal: ByteArray, emit: (H264AccessUnit) -> Unit) {
    val headerIndex = nalHeaderIndex(nal) ?: return
    val nalType = nal[headerIndex].toInt() and NAL_TYPE_MASK
    when {
      nalType == NAL_ACCESS_UNIT_DELIMITER -> {
        if (pictureHasVcl) emitPicture(emit)
        prefixNals += nal
      }
      nalType in VCL_NAL_TYPES -> {
        val firstMacroblock = readUnsignedExpGolomb(nal, headerIndex + 1)
        if (pictureHasVcl && firstMacroblock == 0) emitPicture(emit)
        if (!pictureHasVcl) {
          pictureNals += prefixNals
          prefixNals.clear()
        }
        pictureNals += nal
        pictureHasVcl = true
        pictureIsKeyFrame = pictureIsKeyFrame || nalType == NAL_IDR_SLICE
      }
      else -> {
        when (nalType) {
          NAL_SPS -> retainedSps = nal
          NAL_PPS -> retainedPps = nal
        }
        prefixNals += nal
      }
    }
  }

  private fun emitPicture(emit: (H264AccessUnit) -> Unit) {
    if (!pictureHasVcl) return
    // Guarantee an IDR keyframe carries its parameter sets in-band. SPS and PPS are checked
    // independently: a periodic IDR carries neither, but an encoder that repeats only the SPS on
    // its IDRs would otherwise leave the chunk without a PPS. Any set the picture didn't already
    // pick up from the preceding NALs is re-prepended from the retained copy, keeping SPS→PPS order
    // ahead of the slices so the chunk is self-contained for a freshly-configured decoder.
    if (pictureIsKeyFrame) {
      if (!pictureContainsNalType(NAL_SPS)) {
        retainedSps?.let { pictureNals.add(0, it) }
      }
      if (!pictureContainsNalType(NAL_PPS)) {
        // Insert right after the SPS (added just now or already present) so ordering stays
        // SPS→PPS→slices; indexOfLast returns -1 when there's no SPS, landing PPS at the front.
        val afterSps = pictureNals.indexOfLast { nalTypeOf(it) == NAL_SPS } + 1
        retainedPps?.let { pictureNals.add(afterSps, it) }
      }
    }
    val output = ByteArrayOutputStream(pictureNals.sumOf { it.size })
    pictureNals.forEach(output::write)
    emit(H264AccessUnit(bytes = output.toByteArray(), isKeyFrame = pictureIsKeyFrame))
    pictureNals.clear()
    pictureHasVcl = false
    pictureIsKeyFrame = false
  }

  private fun findStartCodes(bytes: ByteArray): List<Int> {
    val starts = mutableListOf<Int>()
    var index = 0
    while (index <= bytes.size - 3) {
      if (bytes[index] == 0.toByte() && bytes[index + 1] == 0.toByte()) {
        when {
          bytes[index + 2] == 1.toByte() -> {
            starts += index
            index += 3
            continue
          }
          index + 3 < bytes.size &&
            bytes[index + 2] == 0.toByte() &&
            bytes[index + 3] == 1.toByte() -> {
            starts += index
            index += 4
            continue
          }
        }
      }
      index++
    }
    return starts
  }

  private fun nalHeaderIndex(nal: ByteArray): Int? {
    var index = 0
    while (index < nal.size && nal[index] == 0.toByte()) index++
    if (index >= nal.size || nal[index] != 1.toByte()) return null
    return (index + 1).takeIf { it < nal.size }
  }

  /** Reads the first unsigned Exp-Golomb value from an escaped RBSP payload. */
  private fun readUnsignedExpGolomb(nal: ByteArray, payloadOffset: Int): Int? {
    val rbsp = ByteArrayOutputStream(nal.size - payloadOffset)
    var zeros = 0
    for (index in payloadOffset until nal.size) {
      val value = nal[index].toInt() and 0xff
      if (zeros >= 2 && value == 0x03) {
        zeros = 0
        continue
      }
      rbsp.write(value)
      zeros = if (value == 0) zeros + 1 else 0
    }
    val bytes = rbsp.toByteArray()
    var bitIndex = 0
    var leadingZeros = 0
    while (bitIndex < bytes.size * 8 && bitAt(bytes, bitIndex) == 0) {
      leadingZeros++
      bitIndex++
    }
    if (bitIndex >= bytes.size * 8 || leadingZeros > 30) return null
    bitIndex++ // delimiter one bit
    var suffix = 0
    repeat(leadingZeros) {
      if (bitIndex >= bytes.size * 8) return null
      suffix = (suffix shl 1) or bitAt(bytes, bitIndex++)
    }
    return ((1 shl leadingZeros) - 1) + suffix
  }

  private fun bitAt(bytes: ByteArray, bitIndex: Int): Int =
    (bytes[bitIndex / 8].toInt() ushr (7 - (bitIndex % 8))) and 1

  companion object {
    private const val MAX_START_CODE_BYTES = 4
    private const val NAL_TYPE_MASK = 0x1f
    private const val NAL_IDR_SLICE = 5
    private const val NAL_SPS = 7
    private const val NAL_PPS = 8
    private const val NAL_ACCESS_UNIT_DELIMITER = 9
    private val VCL_NAL_TYPES = 1..5
  }
}
