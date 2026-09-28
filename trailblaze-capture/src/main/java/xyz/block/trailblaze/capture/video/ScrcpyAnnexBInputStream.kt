package xyz.block.trailblaze.capture.video

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads scrcpy's framed video socket and presents it as the plain Annex-B stream [H264Tee] and its
 * consumers expect, with each frame's device time written in ahead of it as a [FrameTimeSei].
 *
 * The socket carries (scrcpy 4.x, `send_frame_meta=true`, after the 4-byte codec id the caller has
 * already read) a sequence of 12-byte headers, each either
 *  - a **session packet** — top bit set, then the video width and height; sent once per capture
 *    session and carrying no payload; or
 *  - a **media packet** header — config flag, key-frame flag and a 61-bit PTS in the first eight
 *    bytes, the payload size in the last four — followed by that many bytes of the encoder's
 *    Annex-B output.
 *
 * A config packet (the SPS/PPS) passes through untouched. Every other packet is prefixed with its
 * timing SEI, so each coded picture in the output begins with its own timestamp.
 */
internal class ScrcpyAnnexBInputStream(
  source: InputStream,
  /** Host clock read as each packet arrives; see [FrameTimeSei.hostArrivalMs]. */
  private val nowMs: () -> Long = System::currentTimeMillis,
) : InputStream() {
  private val input = DataInputStream(source)
  private var pending = ByteArray(0)
  private var position = 0

  override fun read(): Int {
    val one = ByteArray(1)
    return if (read(one, 0, 1) <= 0) -1 else one[0].toInt() and 0xff
  }

  override fun read(b: ByteArray, off: Int, len: Int): Int {
    if (len == 0) return 0
    if (position >= pending.size && !nextPacket()) return -1
    val n = minOf(len, pending.size - position)
    System.arraycopy(pending, position, b, off, n)
    position += n
    return n
  }

  override fun available(): Int = pending.size - position

  override fun close() = input.close()

  /** Loads the next packet with a payload into [pending]; false at a clean end of stream. */
  private fun nextPacket(): Boolean {
    while (true) {
      val header = try {
        input.readLong()
      } catch (_: EOFException) {
        return false
      }
      val size = input.readInt()
      if (header < 0) continue // session packet: width and height only, no payload
      // A size this far out means the stream is out of step; fail into the tee's restart path rather
      // than allocate it (an OutOfMemoryError would kill the reader and leave the tee looking alive).
      if (size < 0 || size > MAX_PACKET_BYTES) throw IOException("scrcpy packet of $size bytes; stream out of step")
      val payload = ByteArray(size)
      input.readFully(payload)
      pending = if (header and FLAG_CONFIG != 0L) {
        payload
      } else {
        FrameTimeSei(devicePtsUs = header and PTS_MASK, hostArrivalMs = nowMs()).toAnnexBNal() + payload
      }
      position = 0
      if (pending.isNotEmpty()) return true
    }
  }

  companion object {
    private const val FLAG_CONFIG = 1L shl 62
    private const val PTS_MASK = (1L shl 61) - 1

    /** Far above any real frame at the bit rates recordings use. */
    internal const val MAX_PACKET_BYTES = 16 shl 20
  }
}
