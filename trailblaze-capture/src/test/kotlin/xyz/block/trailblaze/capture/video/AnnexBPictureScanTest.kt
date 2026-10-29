package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AnnexBPictureScanTest {

  private val sps = nal(0x67, 0x42, 0x00, 0x1f)
  private val pps = nal(0x68, 0xce, 0x3c, 0x80)
  private val sei = nal(0x06, 0x05, 0x10)
  private val idrSlice = nal(0x65, 0x88, 0x84)
  private val pSlice = nal(0x41, 0x9a, 0x22)

  @Test
  fun `codec config alone is not a picture`() {
    val scan = AnnexBPictureScan()
    assertFalse(scan.feed(sps + pps + sei))
  }

  @Test
  fun `a keyframe after the codec config is a picture`() {
    val scan = AnnexBPictureScan()
    assertFalse(scan.feed(sps + pps))
    assertTrue(scan.feed(idrSlice))
  }

  @Test
  fun `a non-key slice is a picture too`() {
    assertTrue(AnnexBPictureScan().feed(pSlice))
  }

  @Test
  fun `a slice header split across two reads is still found`() {
    val stream = sps + pps + idrSlice
    // Cut between the start code's last zero and its 0x01, so neither read holds the whole header.
    val cut = sps.size + pps.size + 3
    val scan = AnnexBPictureScan()
    assertFalse(scan.feed(stream.copyOfRange(0, cut)))
    assertTrue(scan.feed(stream.copyOfRange(cut, stream.size)))
  }

  @Test
  fun `a reset forgets the bytes carried from the previous stream`() {
    val scan = AnnexBPictureScan()
    assertFalse(scan.feed(byteArrayOf(0, 0, 0)))
    scan.reset()
    assertFalse(scan.feed(byteArrayOf(1, 0x65)), "the start code began in a stream that was reset")
  }

  private fun AnnexBPictureScan.feed(bytes: ByteArray): Boolean = feed(bytes, bytes.size)

  private fun nal(vararg bytes: Int): ByteArray = byteArrayOf(0, 0, 0, 1) + bytes.map { it.toByte() }.toByteArray()
}
