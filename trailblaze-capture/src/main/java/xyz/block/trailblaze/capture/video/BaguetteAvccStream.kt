package xyz.block.trailblaze.capture.video

import java.io.ByteArrayOutputStream

/**
 * Converts `baguette serve` WebSocket video records into browser-decodable Annex-B H.264 access
 * units — the same [H264AccessUnit] shape the Android `screenrecord` path produces, so the iOS live
 * viewer reuses Android's exact WebCodecs decode path with no browser divergence.
 *
 * The iOS Simulator has no `adb screenrecord` equivalent: `simctl io recordVideo` only writes a
 * seekable file (it refuses a pipe), so there is no stock way to get a live H.264 stream off a
 * booted simulator. [baguette](https://github.com/tddworks/baguette) (`brew install baguette`)
 * captures the simulator framebuffer through private SimulatorKit frameworks and hardware-encodes
 * H.264 with VideoToolbox, exposed over a local `baguette serve` WebSocket. This parser adapts
 * baguette's WS record format to the exact [H264AccessUnit] contract the `/devices/api/stream`
 * endpoint already sends to the browser.
 *
 * ## baguette WS record format (`format=avcc&version=v2`)
 * Each WebSocket **binary message is one complete record**: a 1-byte type tag followed by the
 * payload (the WebSocket message boundary is the record boundary — there is no length prefix to
 * reassemble). Types:
 *  - `0x01` **description** — an avcC `AVCDecoderConfigurationRecord` (SPS/PPS), emitted once,
 *    immediately before the first keyframe.
 *  - `0x02` **keyframe** — an IDR sample in avcc sample format (length-prefixed NAL units); the
 *    parameter sets live in the description, not the sample.
 *  - `0x03` **delta** — a non-IDR (P-frame) sample, same avcc sample format.
 *  - `0x04` **seed** — a JPEG still baguette sends for instant first paint. Ignored here: the
 *    browser decodes Annex-B H.264 only, and baguette forces an IDR at stream start so a real
 *    keyframe arrives immediately anyway.
 *
 * ## What this emits
 * Each `0x02`/`0x03` sample becomes one [H264AccessUnit] in Annex-B (`00 00 00 01` start-code
 * delimited). The SPS/PPS parsed from the `0x01` description are prepended to **every** keyframe, so
 * each IDR access unit is self-contained (SPS+PPS+IDR) — matching the Android tee's cached keyframe
 * and giving the browser's decoder the parameter sets it needs to start (and to recover after any
 * mid-stream reset). Delta samples carry only their coded slices.
 *
 * Not thread-safe; drive it from a single WebSocket listener.
 */
class BaguetteAvccStreamParser {

  /** SPS/PPS in Annex-B, parsed from the most recent description record. Prepended to keyframes. */
  private var annexBParameterSets: ByteArray = EMPTY

  /** NAL length-prefix size (bytes) for avcc samples, read from the description. avcC default is 4. */
  private var nalLengthSize: Int = DEFAULT_NAL_LENGTH_SIZE

  /**
   * Feed one complete baguette WS record — a 1-byte type tag followed by its payload. An empty
   * record is ignored. Emits an [H264AccessUnit] for every keyframe/delta; description records
   * update parser state and JPEG seeds (and unknown future tags) emit nothing.
   */
  fun feed(record: ByteArray, emit: (H264AccessUnit) -> Unit) {
    if (record.isEmpty()) return
    val tag = record[0].toInt() and 0xff
    val payload = record.copyOfRange(1, record.size)
    when (tag) {
      TAG_DESCRIPTION -> parseDescription(payload)
      TAG_KEYFRAME ->
        emit(
          H264AccessUnit(
            bytes = sampleToAnnexB(payload, nalLengthSize, prepend = annexBParameterSets),
            isKeyFrame = true,
          ),
        )
      TAG_DELTA ->
        emit(
          H264AccessUnit(
            bytes = sampleToAnnexB(payload, nalLengthSize, prepend = EMPTY),
            isKeyFrame = false,
          ),
        )
      // TAG_SEED (0x04): JPEG still, not part of the H.264 access-unit stream. Any other tag is an
      // unknown future extension — ignore it rather than corrupt the stream.
      else -> Unit
    }
  }

  /** Discard parser state (parameter sets) for a fresh producer session. */
  fun reset() {
    annexBParameterSets = EMPTY
    nalLengthSize = DEFAULT_NAL_LENGTH_SIZE
  }

  private fun parseDescription(avcc: ByteArray) {
    parseAvccConfig(avcc)?.let {
      annexBParameterSets = it.annexBParameterSets
      nalLengthSize = it.nalLengthSize
    }
  }

  /** Parsed avcC `AVCDecoderConfigurationRecord`: SPS/PPS in Annex-B plus the sample NAL length size. */
  internal data class AvccConfig(val annexBParameterSets: ByteArray, val nalLengthSize: Int)

  companion object {
    private const val TAG_DESCRIPTION = 0x01
    private const val TAG_KEYFRAME = 0x02
    private const val TAG_DELTA = 0x03

    /** avcC default when the record is unreadable: 4-byte NAL length prefixes (VideoToolbox's shape). */
    private const val DEFAULT_NAL_LENGTH_SIZE = 4

    private val EMPTY = ByteArray(0)
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /**
     * Parses an avcC `AVCDecoderConfigurationRecord` into its SPS/PPS (as an Annex-B blob ready to
     * prepend to a keyframe) and the NAL length-prefix size used by avcc samples. Returns null if
     * the record is too short or self-inconsistent (truncated lengths) — the caller keeps its prior
     * parameter sets rather than corrupting the stream.
     *
     * Layout (ISO/IEC 14496-15):
     * ```
     * [0] configurationVersion (1)
     * [1] AVCProfileIndication      [2] profile_compatibility     [3] AVCLevelIndication
     * [4] 111111 + lengthSizeMinusOne(2 bits)   -> nalLengthSize = (byte & 0x3) + 1
     * [5] 111 + numOfSequenceParameterSets(5 bits)
     *     per SPS: [2-byte BE length][SPS NAL bytes]
     * [.] numOfPictureParameterSets (1 byte)
     *     per PPS: [2-byte BE length][PPS NAL bytes]
     * ```
     */
    internal fun parseAvccConfig(avcc: ByteArray): AvccConfig? {
      if (avcc.size < 7) return null
      val nalLengthSize = (avcc[4].toInt() and 0x03) + 1
      val out = ByteArrayOutputStream()
      var index = 5

      val numSps = avcc[index].toInt() and 0x1f
      index++
      repeat(numSps) {
        index = appendParameterSet(avcc, index, out) ?: return null
      }
      if (index >= avcc.size) return null
      val numPps = avcc[index].toInt() and 0xff
      index++
      repeat(numPps) {
        index = appendParameterSet(avcc, index, out) ?: return null
      }
      return AvccConfig(annexBParameterSets = out.toByteArray(), nalLengthSize = nalLengthSize)
    }

    /**
     * Reads one 2-byte-length-prefixed parameter-set NAL at [index], writes it Annex-B-framed into
     * [out], and returns the index just past it — or null if the record is truncated.
     */
    private fun appendParameterSet(avcc: ByteArray, index: Int, out: ByteArrayOutputStream): Int? {
      if (index + 2 > avcc.size) return null
      val length = readBigEndian(avcc, index, 2)
      val start = index + 2
      val end = start + length
      if (length <= 0 || end > avcc.size) return null
      out.write(START_CODE)
      out.write(avcc, start, length)
      return end
    }

    /**
     * Converts one avcc sample (a sequence of `nalLengthSize`-prefixed NAL units) to Annex-B,
     * optionally prepending [prepend] (the SPS/PPS blob for a keyframe). A truncated trailing NAL
     * (corruption / desync) ends the conversion at the last whole NAL rather than throwing.
     */
    internal fun sampleToAnnexB(sample: ByteArray, nalLengthSize: Int, prepend: ByteArray): ByteArray {
      val out = ByteArrayOutputStream(prepend.size + sample.size + 8)
      if (prepend.isNotEmpty()) out.write(prepend)
      var index = 0
      while (index + nalLengthSize <= sample.size) {
        val nalLength = readBigEndian(sample, index, nalLengthSize)
        val start = index + nalLengthSize
        val end = start + nalLength
        if (nalLength <= 0 || end > sample.size) break
        out.write(START_CODE)
        out.write(sample, start, nalLength)
        index = end
      }
      return out.toByteArray()
    }

    /** Reads [count] big-endian bytes from [bytes] at [offset] as an unsigned int. */
    private fun readBigEndian(bytes: ByteArray, offset: Int, count: Int): Int {
      var value = 0
      for (i in 0 until count) {
        value = (value shl 8) or (bytes[offset + i].toInt() and 0xff)
      }
      return value
    }
  }
}
