package xyz.block.trailblaze.capture.video

import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behavioral tests for [BaguetteAvccStreamParser]: they assert the observable contract the browser
 * relies on — that baguette's WS video records become self-contained Annex-B [H264AccessUnit]s with
 * the right key/delta flags and start codes — not the parser's internal steps.
 */
class BaguetteAvccStreamParserTest {

  private val startCode = byteArrayOf(0, 0, 0, 1)

  // ── canned-byte builders for baguette's WS record format ──────────────────────────────────────

  /** One baguette WS record: a 1-byte type tag followed by the payload. */
  private fun record(tag: Int, payload: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + payload

  /** avcC AVCDecoderConfigurationRecord carrying one SPS + one PPS with a 4-byte NAL length size. */
  private fun avccConfig(sps: ByteArray, pps: ByteArray, nalLengthSize: Int = 4): ByteArray =
    avccConfigMulti(listOf(sps), listOf(pps), nalLengthSize)

  /** An avcc sample: each NAL prefixed by its big-endian [nalLengthSize]-byte length. */
  private fun avccSample(vararg nals: ByteArray, nalLengthSize: Int = 4): ByteArray {
    val out = ByteArrayOutputStream()
    for (nal in nals) {
      for (i in nalLengthSize - 1 downTo 0) out.write((nal.size ushr (8 * i)) and 0xff)
      out.write(nal)
    }
    return out.toByteArray()
  }

  /** Feeds each record in order and returns every access unit emitted across them. */
  private fun BaguetteAvccStreamParser.feedRecords(vararg records: ByteArray): List<H264AccessUnit> {
    val units = mutableListOf<H264AccessUnit>()
    for (r in records) feed(r, units::add)
    return units
  }

  /** Finds every start-code-delimited NAL in an Annex-B blob and returns each NAL's first byte. */
  private fun nalHeaderBytes(annexB: ByteArray): List<Int> {
    val headers = mutableListOf<Int>()
    var i = 0
    while (i + 4 <= annexB.size) {
      if (annexB[i] == 0.toByte() && annexB[i + 1] == 0.toByte() &&
        annexB[i + 2] == 0.toByte() && annexB[i + 3] == 1.toByte()
      ) {
        if (i + 4 < annexB.size) headers.add(annexB[i + 4].toInt() and 0xff)
        i += 4
      } else {
        i++
      }
    }
    return headers
  }

  private fun nal(type: Int, payload: ByteArray): ByteArray =
    byteArrayOf((type and 0x1f).toByte()) + payload

  // ── tests ─────────────────────────────────────────────────────────────────────────────────────

  @Test
  fun `keyframe carries the description's SPS and PPS as Annex-B before the IDR slice`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte(), 0x84.toByte()))

    val units =
      parser.feedRecords(record(0x01, avccConfig(sps, pps)), record(0x02, avccSample(idr)))

    assertEquals(1, units.size)
    val unit = units.single()
    assertTrue(unit.isKeyFrame, "0x02 tag must map to a key access unit")
    // SPS(7), PPS(8), then the IDR slice(5) — each Annex-B framed, in order.
    assertEquals(listOf(7, 8, 5), nalHeaderBytes(unit.bytes))
    assertTrue(unit.bytes.copyOfRange(0, 4).contentEquals(startCode), "must begin with a start code")
  }

  @Test
  fun `delta frame is Annex-B slices only, no parameter sets`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte()))
    val pSlice = nal(type = 1, payload = byteArrayOf(0x9a.toByte()))

    val units =
      parser.feedRecords(
        record(0x01, avccConfig(sps, pps)),
        record(0x02, avccSample(idr)),
        record(0x03, avccSample(pSlice)),
      )

    assertEquals(2, units.size)
    assertFalse(units[1].isKeyFrame, "0x03 tag must map to a delta access unit")
    assertEquals(listOf(1), nalHeaderBytes(units[1].bytes), "delta carries only its coded slice")
  }

  @Test
  fun `multiple NAL units in one sample all survive as separate Annex-B NALs`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idrA = nal(type = 5, payload = byteArrayOf(0x11))
    val idrB = nal(type = 5, payload = byteArrayOf(0x22, 0x33))

    val unit =
      parser
        .feedRecords(record(0x01, avccConfig(sps, pps)), record(0x02, avccSample(idrA, idrB)))
        .single()

    assertEquals(listOf(7, 8, 5, 5), nalHeaderBytes(unit.bytes))
  }

  @Test
  fun `JPEG seed records are ignored on the H264 path`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte()))

    // baguette sends a 0x04 JPEG seed before the first description/keyframe.
    val jpeg = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0x00, 0x11, 0xff.toByte(), 0xd9.toByte())
    val units =
      parser.feedRecords(
        record(0x04, jpeg),
        record(0x01, avccConfig(sps, pps)),
        record(0x02, avccSample(idr)),
      )

    assertEquals(1, units.size, "the seed must not produce an access unit")
    assertTrue(units.single().isKeyFrame)
  }

  @Test
  fun `honors a non-default NAL length size from the description`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte(), 0x84.toByte()))

    val unit =
      parser
        .feedRecords(
          record(0x01, avccConfig(sps, pps, nalLengthSize = 2)),
          record(0x02, avccSample(idr, nalLengthSize = 2)),
        )
        .single()

    assertEquals(listOf(7, 8, 5), nalHeaderBytes(unit.bytes))
  }

  @Test
  fun `an empty record is ignored`() {
    val parser = BaguetteAvccStreamParser()
    assertTrue(parser.feedRecords(ByteArray(0)).isEmpty(), "an empty WS message must emit nothing")
  }

  @Test
  fun `keyframe before any description still emits its slice`() {
    // Defensive: if a description never arrives, a keyframe should still yield its coded slice
    // (undecodable without SPS/PPS, but the parser must not drop or crash).
    val parser = BaguetteAvccStreamParser()
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte()))
    val unit = parser.feedRecords(record(0x02, avccSample(idr))).single()

    assertTrue(unit.isKeyFrame)
    assertEquals(listOf(5), nalHeaderBytes(unit.bytes))
  }

  @Test
  fun `reset clears parameter sets so a later keyframe carries none until re-described`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte()))

    parser.feedRecords(record(0x01, avccConfig(sps, pps)))
    parser.reset()
    val unit = parser.feedRecords(record(0x02, avccSample(idr))).single()

    assertEquals(listOf(5), nalHeaderBytes(unit.bytes), "reset must forget the earlier SPS/PPS")
  }

  @Test
  fun `a malformed re-description keeps the earlier parameter sets`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte()))

    parser.feedRecords(record(0x01, avccConfig(sps, pps)))
    // A description too short to parse (< 7 bytes): parseAvccConfig returns null, prior sets stand.
    parser.feedRecords(record(0x01, byteArrayOf(1, 0x42, 0x00, 0x1f)))
    val unit = parser.feedRecords(record(0x02, avccSample(idr))).single()

    assertEquals(
      listOf(7, 8, 5),
      nalHeaderBytes(unit.bytes),
      "a bad re-description must not drop the working SPS/PPS",
    )
  }

  @Test
  fun `a truncated trailing NAL is dropped and the whole NALs survive`() {
    val parser = BaguetteAvccStreamParser()
    val sps = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val pps = nal(type = 8, payload = byteArrayOf(0x01))
    val idr = nal(type = 5, payload = byteArrayOf(0x11, 0x22))

    // One whole NAL, then a 4-byte length claiming 99 bytes with only 2 present (corruption/desync).
    val truncated = avccSample(idr) + byteArrayOf(0, 0, 0, 99, 0xab.toByte(), 0xcd.toByte())
    val unit =
      parser.feedRecords(record(0x01, avccConfig(sps, pps)), record(0x02, truncated)).single()

    assertEquals(listOf(7, 8, 5), nalHeaderBytes(unit.bytes), "conversion stops at the last whole NAL")
  }

  @Test
  fun `multiple SPS and PPS in one description are all prepended in order`() {
    val parser = BaguetteAvccStreamParser()
    val sps1 = nal(type = 7, payload = byteArrayOf(0x42, 0xc0.toByte(), 0x1f))
    val sps2 = nal(type = 7, payload = byteArrayOf(0x4d))
    val pps1 = nal(type = 8, payload = byteArrayOf(0x01))
    val pps2 = nal(type = 8, payload = byteArrayOf(0x02))
    val idr = nal(type = 5, payload = byteArrayOf(0x88.toByte()))

    val unit =
      parser
        .feedRecords(
          record(0x01, avccConfigMulti(listOf(sps1, sps2), listOf(pps1, pps2))),
          record(0x02, avccSample(idr)),
        )
        .single()

    assertEquals(listOf(7, 7, 8, 8, 5), nalHeaderBytes(unit.bytes))
  }

  /** avcC config carrying an arbitrary number of SPS and PPS NALs. */
  private fun avccConfigMulti(
    spsList: List<ByteArray>,
    ppsList: List<ByteArray>,
    nalLengthSize: Int = 4,
  ): ByteArray {
    val out = ByteArrayOutputStream()
    out.write(1) // configurationVersion
    out.write(0x42); out.write(0); out.write(0x1f) // profile / compat / level (cosmetic here)
    out.write(0xfc or (nalLengthSize - 1)) // 6 reserved bits + lengthSizeMinusOne
    out.write(0xe0 or spsList.size) // 3 reserved bits + numSPS
    for (sps in spsList) {
      out.write(sps.size ushr 8); out.write(sps.size and 0xff); out.write(sps)
    }
    out.write(ppsList.size) // numPPS
    for (pps in ppsList) {
      out.write(pps.size ushr 8); out.write(pps.size and 0xff); out.write(pps)
    }
    return out.toByteArray()
  }
}
