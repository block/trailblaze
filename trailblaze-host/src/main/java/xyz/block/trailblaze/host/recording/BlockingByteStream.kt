package xyz.block.trailblaze.host.recording

import java.io.InputStream

/**
 * A blocking byte FIFO exposed as an [InputStream], bridging an asynchronous byte producer to a
 * consumer that wants a plain blocking stream.
 *
 * One producer appends chunks via [append]; one consumer drains them via [read]. [read] blocks
 * until at least one byte is available, returns any buffered bytes, and only returns `-1` (standard
 * EOF) once the stream is [close]d AND the buffer has fully drained — it never returns `0`, so it
 * satisfies the InputStream contract a byte-oriented reader (e.g. `H264Tee`'s reader loop, which
 * treats any `<= 0` as end-of-stream) expects.
 *
 * The buffer is bounded: [append] blocks while the new chunk wouldn't fit under
 * [maxBufferedBytes], applying backpressure to the producer rather than growing without limit.
 * A single chunk larger than the whole budget is admitted only into an empty buffer (blocking
 * such a chunk forever would deadlock the producer), so the buffer never holds more than
 * `maxBufferedBytes` plus at most one oversized chunk. This is
 * why it doesn't use `java.io.PipedInputStream` — Piped streams key their "write end dead"
 * detection on the identity of the last writing thread, which breaks when the producer runs on a
 * rotating coroutine-dispatcher pool. This FIFO is thread-agnostic: any thread may [append].
 */
internal class BlockingByteStream(
  private val maxBufferedBytes: Int = DEFAULT_MAX_BUFFERED_BYTES,
) : InputStream() {

  private val lock = Object()
  private val chunks = ArrayDeque<ByteArray>()
  private var frontOffset = 0
  private var bufferedBytes = 0
  private var closed = false

  /**
   * Appends [bytes] to the FIFO, blocking while the chunk wouldn't fit under [maxBufferedBytes]
   * (backpressure; an oversized chunk waits only for an empty buffer — see class kdoc). A no-op
   * for an empty array or after [close]. Returns without appending if the stream closes while
   * blocked.
   */
  fun append(bytes: ByteArray) {
    if (bytes.isEmpty()) return
    synchronized(lock) {
      while (!closed && bufferedBytes > 0 && bufferedBytes + bytes.size > maxBufferedBytes) {
        lock.wait()
      }
      if (closed) return
      chunks.addLast(bytes)
      bufferedBytes += bytes.size
      lock.notifyAll()
    }
  }

  override fun read(): Int {
    val one = ByteArray(1)
    val n = read(one, 0, 1)
    return if (n == -1) -1 else one[0].toInt() and 0xff
  }

  override fun read(dest: ByteArray, off: Int, len: Int): Int {
    if (len == 0) return 0
    synchronized(lock) {
      while (chunks.isEmpty() && !closed) lock.wait()
      if (chunks.isEmpty()) return -1 // closed and drained → EOF
      var written = 0
      while (written < len && chunks.isNotEmpty()) {
        val front = chunks.first()
        val available = front.size - frontOffset
        val toCopy = minOf(available, len - written)
        System.arraycopy(front, frontOffset, dest, off + written, toCopy)
        frontOffset += toCopy
        written += toCopy
        bufferedBytes -= toCopy
        if (frontOffset == front.size) {
          chunks.removeFirst()
          frontOffset = 0
        }
      }
      lock.notifyAll() // wake a producer blocked on backpressure
      return written
    }
  }

  /**
   * Signals end-of-stream: no further [append] takes effect, but already-buffered bytes stay
   * readable. [read] drains the remaining bytes and only then returns `-1` (EOF). A [read] blocked
   * on an empty buffer wakes and returns `-1`.
   */
  override fun close() {
    synchronized(lock) {
      closed = true
      lock.notifyAll()
    }
  }

  companion object {
    /** ~8 MB: generous slack between the baguette producer and the ffmpeg drain rate. */
    private const val DEFAULT_MAX_BUFFERED_BYTES = 8 * 1024 * 1024
  }
}
