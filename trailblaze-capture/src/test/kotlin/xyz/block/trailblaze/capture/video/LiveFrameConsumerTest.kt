package xyz.block.trailblaze.capture.video

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [LiveFrameConsumer.JpegFrameSplitter] — the byte-level marker logic that
 * reassembles individual JPEG frames out of ffmpeg's image2pipe concatenated output.
 *
 * Don't need real JPEG bytes: we just synthesize SOI/EOI markers with arbitrary payload in
 * between. The splitter is pure logic; the production path is exercised end-to-end via the
 * live integration test Sam runs pre-merge.
 */
class LiveFrameConsumerTest {

  @Test
  fun `splits a single frame in one feed`() {
    val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte())
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val frames = splitter.feedForTest(frame)
    assertEquals(1, frames.size)
    assertTrue(frames[0].contentEquals(frame), "frame should round-trip identically")
  }

  @Test
  fun `splits two back-to-back frames in one feed`() {
    val a = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 10, 20, 0xFF.toByte(), 0xD9.toByte())
    val b = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 30, 40, 50, 0xFF.toByte(), 0xD9.toByte())
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val frames = splitter.feedForTest(a + b)
    assertEquals(2, frames.size)
    assertTrue(frames[0].contentEquals(a))
    assertTrue(frames[1].contentEquals(b))
  }

  @Test
  fun `reassembles a frame split across multiple feeds`() {
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val out = mutableListOf<ByteArray>()
    // Feed byte-at-a-time to exercise marker-straddles-boundary handling.
    val frame = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 7, 8, 9, 0xFF.toByte(), 0xD9.toByte())
    for (b in frame) {
      splitter.feed(byteArrayOf(b), 0, 1) { out.add(it) }
    }
    assertEquals(1, out.size)
    assertTrue(out[0].contentEquals(frame))
  }

  @Test
  fun `ignores garbage bytes before the first SOI`() {
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val input = byteArrayOf(99, 98, 97, 0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
    val frames = splitter.feedForTest(input)
    assertEquals(1, frames.size)
    // Frame begins at the SOI; garbage prefix is dropped.
    val expected = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 0xFF.toByte(), 0xD9.toByte())
    assertTrue(frames[0].contentEquals(expected))
  }

  @Test
  fun `SOI marker mid-frame resets and starts a new frame`() {
    // Pathological but possible if a stray marker shows up in the byte stream.
    val splitter = LiveFrameConsumer.JpegFrameSplitter()
    val input = byteArrayOf(
      0xFF.toByte(), 0xD8.toByte(), 1, 2, // start frame A
      0xFF.toByte(), 0xD8.toByte(), 3, 4, // mid-frame SOI: should restart
      0xFF.toByte(), 0xD9.toByte(), // EOI: complete the new frame
    )
    val frames = splitter.feedForTest(input)
    assertEquals(1, frames.size, "should only emit the frame closed by EOI")
    val expected = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 3, 4, 0xFF.toByte(), 0xD9.toByte())
    assertTrue(frames[0].contentEquals(expected))
  }
}
