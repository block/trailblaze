package xyz.block.trailblaze.host.recording

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behavioral contract of the async-producer → InputStream bridge: bytes read out equal bytes
 * appended in (across chunk/read-size boundaries), and closing signals EOF after the buffer
 * drains. These are the guarantees `H264Tee`'s reader loop and the ffmpeg decoder depend on.
 */
class BlockingByteStreamTest {

  @Test
  fun `reads back exactly what was appended, in order`() {
    val stream = BlockingByteStream()
    stream.append(byteArrayOf(1, 2, 3))
    stream.append(byteArrayOf(4, 5))
    stream.close()

    assertContentEquals(byteArrayOf(1, 2, 3, 4, 5), stream.readRemaining())
  }

  @Test
  fun `a single read can span multiple appended chunks`() {
    val stream = BlockingByteStream()
    stream.append(byteArrayOf(1, 2))
    stream.append(byteArrayOf(3, 4))
    stream.close()

    val dest = ByteArray(10)
    val n = stream.read(dest, 0, 10)
    assertEquals(4, n)
    assertContentEquals(byteArrayOf(1, 2, 3, 4), dest.copyOf(4))
  }

  @Test
  fun `a read smaller than the front chunk leaves the remainder for the next read`() {
    val stream = BlockingByteStream()
    stream.append(byteArrayOf(1, 2, 3, 4))
    stream.close()

    val first = ByteArray(2)
    assertEquals(2, stream.read(first, 0, 2))
    assertContentEquals(byteArrayOf(1, 2), first)

    val second = ByteArray(2)
    assertEquals(2, stream.read(second, 0, 2))
    assertContentEquals(byteArrayOf(3, 4), second)

    assertEquals(-1, stream.read(ByteArray(1), 0, 1))
  }

  @Test
  fun `close after draining yields EOF`() {
    val stream = BlockingByteStream()
    stream.append(byteArrayOf(9))
    stream.close()

    assertEquals(9, stream.read())
    assertEquals(-1, stream.read())
  }

  @Test
  fun `empty append is a no-op`() {
    val stream = BlockingByteStream()
    stream.append(ByteArray(0))
    stream.close()
    assertEquals(-1, stream.read())
  }

  @Test
  fun `read blocks until a producer appends, then returns the bytes`() {
    val stream = BlockingByteStream()
    val reader = Thread {
      // Blocks here until the producer appends below.
      val dest = ByteArray(3)
      val n = stream.read(dest, 0, 3)
      readResult = dest.copyOf(n)
    }
    reader.start()
    // Give the reader a moment to park in read(); it must not have produced a result yet.
    Thread.sleep(100)
    assertTrue(readResult == null, "read() returned before any bytes were appended")

    stream.append(byteArrayOf(7, 8, 9))
    reader.join(2_000)
    assertContentEquals(byteArrayOf(7, 8, 9), readResult)
  }

  @Test
  fun `append blocks while the chunk would overshoot the buffer budget`() {
    val stream = BlockingByteStream(maxBufferedBytes = 4)
    stream.append(byteArrayOf(1, 2, 3))
    val producer = Thread {
      // 3 buffered + 2 incoming > 4: must block until the consumer drains enough.
      stream.append(byteArrayOf(4, 5))
      appendCompleted = true
    }
    producer.start()
    Thread.sleep(100)
    assertTrue(!appendCompleted, "append() overshot the budget instead of blocking")

    val dest = ByteArray(3)
    assertEquals(3, stream.read(dest, 0, 3)) // drain → 0 buffered + 2 incoming fits
    producer.join(2_000)
    assertTrue(appendCompleted, "append() did not unblock after the consumer drained")

    stream.close()
    assertContentEquals(byteArrayOf(4, 5), stream.readRemaining())
  }

  @Test
  fun `a chunk larger than the whole budget is admitted into an empty buffer`() {
    // Blocking an oversized chunk forever would deadlock the producer — an empty buffer can
    // never get any emptier. It is admitted alone; the next append then waits for a full drain.
    val stream = BlockingByteStream(maxBufferedBytes = 4)
    stream.append(byteArrayOf(1, 2, 3, 4, 5, 6)) // 6 > 4: admitted, buffer was empty
    stream.close()
    assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6), stream.readRemaining())
  }

  @Volatile private var appendCompleted = false

  @Volatile private var readResult: ByteArray? = null

  /** Drains the stream to EOF and returns everything read. */
  private fun BlockingByteStream.readRemaining(): ByteArray {
    val out = ArrayList<Byte>()
    val buf = ByteArray(8)
    while (true) {
      val n = read(buf, 0, buf.size)
      if (n == -1) break
      for (i in 0 until n) out.add(buf[i])
    }
    return out.toByteArray()
  }
}
