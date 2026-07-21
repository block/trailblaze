package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class H264AccessUnitConsumerTest {

  @Test
  fun `groups parameter sets and one slice into browser access units`() {
    val splitter = AnnexBAccessUnitSplitter()
    val units = mutableListOf<H264AccessUnit>()
    val stream =
      nal(type = 7, payload = byteArrayOf(0x42, 0xC0.toByte(), 0x20)) +
        nal(type = 8, payload = byteArrayOf(0x01)) +
        nal(type = 5, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 1, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 1, payload = byteArrayOf(0x80.toByte()))

    // Deliberately split start codes and NAL payloads across feeds.
    stream.asList().chunked(2).forEach { chunk ->
      val bytes = chunk.toByteArray()
      splitter.feed(bytes, 0, bytes.size, units::add)
    }
    splitter.finish(units::add)

    assertEquals(3, units.size)
    assertTrue(units.first().isKeyFrame)
    assertTrue(containsNalType(units.first().bytes, 7), "key chunk should retain SPS")
    assertTrue(containsNalType(units.first().bytes, 8), "key chunk should retain PPS")
    assertFalse(units[1].isKeyFrame)
    assertFalse(units[2].isKeyFrame)
  }

  @Test
  fun `keeps multiple slices from one picture in one access unit`() {
    val splitter = AnnexBAccessUnitSplitter()
    val units = mutableListOf<H264AccessUnit>()
    // first_mb_in_slice=0 is Exp-Golomb `1`; first_mb_in_slice=1 is `010`.
    val stream =
      nal(type = 5, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 5, payload = byteArrayOf(0x40)) +
        nal(type = 1, payload = byteArrayOf(0x80.toByte()))

    splitter.feed(stream, 0, stream.size, units::add)
    splitter.finish(units::add)

    assertEquals(2, units.size)
    assertEquals(2, countNalType(units.first().bytes, 5))
    assertTrue(units.first().isKeyFrame)
  }

  @Test
  fun `access unit delimiters close the previous picture`() {
    val splitter = AnnexBAccessUnitSplitter()
    val units = mutableListOf<H264AccessUnit>()
    val stream =
      nal(type = 5, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 9, payload = byteArrayOf(0xF0.toByte())) +
        nal(type = 1, payload = byteArrayOf(0x80.toByte()))

    splitter.feed(stream, 0, stream.size, units::add)
    splitter.finish(units::add)

    assertEquals(2, units.size)
    assertTrue(containsNalType(units[1].bytes, 9))
  }

  @Test
  fun `idle flush exposes a static first frame without a following start code`() {
    val splitter = AnnexBAccessUnitSplitter()
    val units = mutableListOf<H264AccessUnit>()
    val stream =
      nal(type = 7, payload = byteArrayOf(0x42, 0xC0.toByte(), 0x20)) +
        nal(type = 8, payload = byteArrayOf(0x01)) +
        nal(type = 5, payload = byteArrayOf(0x80.toByte()))

    splitter.feed(stream, 0, stream.size, units::add)
    assertTrue(units.isEmpty(), "the last NAL has no following start code yet")

    splitter.flushPending(units::add)

    assertEquals(1, units.size)
    assertTrue(units.single().isKeyFrame)
    assertTrue(containsNalType(units.single().bytes, 7))
    assertTrue(containsNalType(units.single().bytes, 8))
  }

  @Test
  fun `re-prepends parameter sets to a later IDR that omits them`() {
    val splitter = AnnexBAccessUnitSplitter()
    val units = mutableListOf<H264AccessUnit>()
    // screenrecord sends SPS/PPS once at stream start, then periodic IDR keyframes that do NOT
    // repeat them. A freshly-configured WebCodecs decoder (mid-stream joiner, reconnect, cached
    // seed) needs the parameter sets in-band on whichever keyframe it configures from, so every
    // keyframe the splitter emits must carry them.
    val stream =
      nal(type = 7, payload = byteArrayOf(0x42, 0xC0.toByte(), 0x20)) +
        nal(type = 8, payload = byteArrayOf(0x01)) +
        nal(type = 5, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 1, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 5, payload = byteArrayOf(0x80.toByte())) + // later IDR, no SPS/PPS in-band
        nal(type = 1, payload = byteArrayOf(0x80.toByte()))

    splitter.feed(stream, 0, stream.size, units::add)
    splitter.finish(units::add)

    val keyframes = units.filter { it.isKeyFrame }
    assertEquals(2, keyframes.size, "both IDRs should surface as keyframes")
    keyframes.forEach { unit ->
      assertTrue(containsNalType(unit.bytes, 7), "every keyframe must carry SPS in-band")
      assertTrue(containsNalType(unit.bytes, 8), "every keyframe must carry PPS in-band")
    }
  }

  @Test
  fun `re-prepends only the missing PPS when a later IDR repeats the SPS`() {
    val splitter = AnnexBAccessUnitSplitter()
    val units = mutableListOf<H264AccessUnit>()
    // Establish SPS+PPS, then a later IDR that repeats only the SPS and omits the PPS. The
    // keyframe must still come out with both parameter sets in SPS→PPS order.
    val stream =
      nal(type = 7, payload = byteArrayOf(0x42, 0xC0.toByte(), 0x20)) +
        nal(type = 8, payload = byteArrayOf(0x01)) +
        nal(type = 5, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 1, payload = byteArrayOf(0x80.toByte())) +
        nal(type = 7, payload = byteArrayOf(0x42, 0xC0.toByte(), 0x20)) + // later IDR repeats SPS...
        nal(type = 5, payload = byteArrayOf(0x80.toByte())) + // ...but no PPS precedes it
        nal(type = 1, payload = byteArrayOf(0x80.toByte()))

    splitter.feed(stream, 0, stream.size, units::add)
    splitter.finish(units::add)

    val keyframes = units.filter { it.isKeyFrame }
    assertEquals(2, keyframes.size, "both IDRs should surface as keyframes")
    keyframes.forEach { unit ->
      assertTrue(containsNalType(unit.bytes, 7), "every keyframe must carry SPS in-band")
      assertTrue(containsNalType(unit.bytes, 8), "every keyframe must carry PPS in-band")
      assertTrue(
        firstNalTypeIndex(unit.bytes, 7) < firstNalTypeIndex(unit.bytes, 8),
        "SPS must precede PPS in the keyframe",
      )
    }
  }

  @Test
  fun `forgets parameter sets after reset so a new generation is not seeded with stale SPS`() {
    val splitter = AnnexBAccessUnitSplitter()
    val units = mutableListOf<H264AccessUnit>()
    // First generation establishes SPS/PPS.
    val first =
      nal(type = 7, payload = byteArrayOf(0x42, 0xC0.toByte(), 0x20)) +
        nal(type = 8, payload = byteArrayOf(0x01)) +
        nal(type = 5, payload = byteArrayOf(0x80.toByte()))
    splitter.feed(first, 0, first.size, units::add)
    splitter.finish(units::add)
    splitter.reset()

    // Second generation opens with a bare IDR (no SPS yet). Nothing retained should leak into it.
    units.clear()
    val second = nal(type = 5, payload = byteArrayOf(0x80.toByte()))
    splitter.feed(second, 0, second.size, units::add)
    splitter.finish(units::add)

    assertEquals(1, units.size)
    assertFalse(containsNalType(units.single().bytes, 7), "a reset must not carry SPS across generations")
  }

  private fun nal(type: Int, payload: ByteArray): ByteArray =
    byteArrayOf(0, 0, 0, 1, type.toByte()) + payload

  private fun containsNalType(bytes: ByteArray, type: Int): Boolean = countNalType(bytes, type) > 0

  /** Byte offset of the first Annex-B start code introducing a NAL of [type], or -1. */
  private fun firstNalTypeIndex(bytes: ByteArray, type: Int): Int {
    for (index in 0 until bytes.size - 4) {
      if (
        bytes[index] == 0.toByte() &&
          bytes[index + 1] == 0.toByte() &&
          bytes[index + 2] == 0.toByte() &&
          bytes[index + 3] == 1.toByte() &&
          (bytes[index + 4].toInt() and 0x1f) == type
      ) {
        return index
      }
    }
    return -1
  }

  private fun countNalType(bytes: ByteArray, type: Int): Int {
    var count = 0
    for (index in 0 until bytes.size - 4) {
      if (
        bytes[index] == 0.toByte() &&
          bytes[index + 1] == 0.toByte() &&
          bytes[index + 2] == 0.toByte() &&
          bytes[index + 3] == 1.toByte() &&
          (bytes[index + 4].toInt() and 0x1f) == type
      ) {
        count++
      }
    }
    return count
  }
}
