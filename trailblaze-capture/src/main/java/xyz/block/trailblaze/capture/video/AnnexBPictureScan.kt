package xyz.block.trailblaze.capture.video

/**
 * Watches an Annex-B H.264 byte stream for its first coded picture: a VCL NAL (types 1–5).
 *
 * A stream that has sent bytes has not necessarily sent a picture. An encoder whose input surface
 * the emulator's renderer cannot fill still emits its codec config (SPS/PPS) and then nothing, so
 * "bytes arrived" reads healthy while the recording ends with no frames. Looking for a slice
 * header tells those apart.
 *
 * Reports the picture as soon as its NAL header arrives rather than when the slice is complete,
 * so a still screen that sends one keyframe and then nothing still counts. A start code split
 * across two reads is found by carrying the last bytes of each read into the next.
 */
internal class AnnexBPictureScan {
  private val carry = ByteArray(CARRY_BYTES)
  private var carryLength = 0

  /** True once [buffer]'s first [length] bytes, read after everything fed before, hold a picture. */
  fun feed(buffer: ByteArray, length: Int): Boolean {
    if (length <= 0) return false
    val total = carryLength + length
    fun at(index: Int): Int =
      (if (index < carryLength) carry[index] else buffer[index - carryLength]).toInt() and 0xff
    for (i in 0 until total - CARRY_BYTES) {
      if (at(i) == 0 && at(i + 1) == 0 && at(i + 2) == 1 && (at(i + 3) and NAL_TYPE_MASK) in VCL_NAL_TYPES) {
        return true
      }
    }
    val keep = minOf(CARRY_BYTES, total)
    val tail = ByteArray(keep) { at(total - keep + it).toByte() }
    tail.copyInto(carry)
    carryLength = keep
    return false
  }

  fun reset() {
    carryLength = 0
  }

  private companion object {
    /** A three-byte start code, so the NAL header byte is the fourth. */
    const val CARRY_BYTES = 3
    const val NAL_TYPE_MASK = 0x1f
    val VCL_NAL_TYPES = 1..5
  }
}
