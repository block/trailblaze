package xyz.block.trailblaze.capture.video

import java.io.ByteArrayOutputStream

/**
 * When a frame was drawn on the device, carried inside the H.264 stream itself.
 *
 * The shared [H264Tee] moves raw Annex-B bytes, and a raw H.264 stream has nowhere to put a
 * timestamp. So a producer that knows each frame's device time ([ScrcpyAnnexBInputStream]) writes
 * it into an SEI NAL (`user_data_unregistered`, tagged with [UUID]) just ahead of the frame's
 * slices. Every decoder skips an SEI it does not recognize, so the live viewer and the screenshot
 * path see an ordinary stream; only the recorder ([DeviceTimedFlvMuxer]) reads it.
 *
 * @property devicePtsUs The frame's presentation time on the device's monotonic clock — when the
 *   display composed it, before any encode or transport delay.
 * @property hostArrivalMs Host epoch when the host received the frame. The gap between the two is
 *   the clock offset plus that frame's latency; the recorder keeps the smallest one it sees.
 */
internal data class FrameTimeSei(val devicePtsUs: Long, val hostArrivalMs: Long) {

  /** This timing as one Annex-B SEI NAL, start code included. */
  fun toAnnexBNal(): ByteArray {
    val rbsp = ByteArrayOutputStream(PAYLOAD_SIZE + 3)
    rbsp.write(PAYLOAD_TYPE_USER_DATA_UNREGISTERED)
    rbsp.write(PAYLOAD_SIZE)
    rbsp.write(UUID)
    rbsp.write(longBytes(devicePtsUs))
    rbsp.write(longBytes(hostArrivalMs))
    rbsp.write(RBSP_STOP_BIT)
    return byteArrayOf(0, 0, 0, 1, NAL_TYPE_SEI.toByte()) + AnnexB.escape(rbsp.toByteArray())
  }

  companion object {
    /** Marks this SEI as Trailblaze's, so a timing field is never read out of someone else's. */
    internal val UUID: ByteArray = byteArrayOf(
      0x3F, 0x8F.toByte(), 0x98.toByte(), 0x9B.toByte(), 0x1C, 0x2D, 0x42, 0x54,
      0x82.toByte(), 0x07, 0x6C, 0x96.toByte(), 0x15, 0x1C, 0x33, 0x73,
    )

    internal const val NAL_TYPE_SEI = 6
    private const val PAYLOAD_TYPE_USER_DATA_UNREGISTERED = 5
    private const val PAYLOAD_SIZE = 16 + 8 + 8
    private const val RBSP_STOP_BIT = 0x80

    /**
     * Reads the timing out of [nal] (header byte first, no start code), or null when it is not one
     * of these SEIs.
     */
    fun parse(nal: ByteArray): FrameTimeSei? {
      if (nal.isEmpty() || (nal[0].toInt() and 0x1f) != NAL_TYPE_SEI) return null
      val rbsp = AnnexB.unescape(nal, 1)
      if (rbsp.size < 2 + PAYLOAD_SIZE) return null
      if (rbsp[0].toInt() != PAYLOAD_TYPE_USER_DATA_UNREGISTERED || rbsp[1].toInt() != PAYLOAD_SIZE) return null
      if (!rbsp.copyOfRange(2, 18).contentEquals(UUID)) return null
      return FrameTimeSei(devicePtsUs = readLong(rbsp, 18), hostArrivalMs = readLong(rbsp, 26))
    }

    private fun longBytes(value: Long) = ByteArray(8) { (value ushr (56 - 8 * it)).toByte() }

    private fun readLong(bytes: ByteArray, offset: Int): Long =
      (0 until 8).fold(0L) { acc, i -> (acc shl 8) or (bytes[offset + i].toLong() and 0xff) }
  }
}

/** Annex-B byte-stream helpers shared by the stream producers and the recorder. */
internal object AnnexB {

  /**
   * The NAL units in [bytes], each without its start code and without trailing zero bytes (a NAL
   * never ends in 0x00, so those belong to the next start code).
   */
  fun nals(bytes: ByteArray): List<ByteArray> {
    val starts = mutableListOf<Pair<Int, Int>>() // (start-code index, payload index)
    var i = 0
    while (i + 2 < bytes.size) {
      if (bytes[i] == ZERO && bytes[i + 1] == ZERO && bytes[i + 2] == ONE) {
        starts += i to i + 3
        i += 3
      } else {
        i++
      }
    }
    return starts.mapIndexedNotNull { index, (_, payload) ->
      var end = starts.getOrNull(index + 1)?.first ?: bytes.size
      while (end > payload && bytes[end - 1] == ZERO) end--
      bytes.copyOfRange(payload, end).takeIf { it.isNotEmpty() }
    }
  }

  /** Inserts emulation-prevention bytes so [rbsp] cannot be mistaken for a start code. */
  fun escape(rbsp: ByteArray): ByteArray {
    val out = ByteArrayOutputStream(rbsp.size + rbsp.size / 64 + 4)
    var zeros = 0
    for (b in rbsp) {
      val v = b.toInt() and 0xff
      if (zeros >= 2 && v <= 3) {
        out.write(3)
        zeros = 0
      }
      out.write(v)
      zeros = if (v == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  /** Removes emulation-prevention bytes from [nal], starting at [offset]. */
  fun unescape(nal: ByteArray, offset: Int): ByteArray {
    val out = ByteArrayOutputStream(nal.size)
    var zeros = 0
    for (index in offset until nal.size) {
      val v = nal[index].toInt() and 0xff
      if (zeros >= 2 && v == 3) {
        zeros = 0
        continue
      }
      out.write(v)
      zeros = if (v == 0) zeros + 1 else 0
    }
    return out.toByteArray()
  }

  private const val ZERO: Byte = 0
  private const val ONE: Byte = 1
}
