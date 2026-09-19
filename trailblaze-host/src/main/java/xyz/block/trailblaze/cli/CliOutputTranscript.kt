package xyz.block.trailblaze.cli

import xyz.block.trailblaze.logs.server.endpoints.CliExecChunk
import xyz.block.trailblaze.logs.server.endpoints.CliExecStream
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * One ordered recording of what a forwarded CLI command wrote to stdout and stderr.
 *
 * The daemon runs the command in its own process and hands the output back over JSON, so the
 * caller's shell has to reassemble it. Two independent buffers cannot be reassembled: replaying
 * all of stdout and then all of stderr reorders the command's output against itself, which is how
 * a status line the command printed first ends up between an error and the tip that explains it.
 * Recording every write into one list, tagged with the stream it went to, keeps the order the
 * command produced.
 *
 * Writes past [limit] total bytes are dropped and a truncation marker is recorded once, so a
 * pathological `snapshot` on a dense UI tree or a tight error-logging loop can't OOM the daemon.
 * The command is unaware — its `println` calls simply become no-ops — which is the right behavior
 * on the daemon fast path, where aborting on output overflow would be the bigger surprise.
 *
 * Two independent bounds are needed, because [limit] alone does not bound the recording. Only
 * *consecutive* same-stream writes coalesce, so a command alternating one byte between stdout and
 * stderr opens a segment per byte: at a 4 MiB limit that is millions of `Segment` objects, each
 * with its own [ByteArrayOutputStream] and backing array, which is far more heap than the bytes
 * the limit was sized around. Past [MAX_SEGMENTS] the recording stops interleaving and appends to
 * one terminal segment per stream instead. Every byte under [limit] is still kept and still
 * attributed to the stream that produced it; what degrades is ordering *between* the streams
 * after that point, back to the per-stream grouping a shim that predates the transcript already
 * handles. A one-time note says so in the output.
 *
 * Thread-safe: [CliOutCapture] hands the sinks to a picocli command that may write from more than
 * one thread, and the order recorded has to be the order the writes actually landed.
 */
class CliOutputTranscript(private val limit: Int, private val maxSegments: Int = MAX_SEGMENTS) {

  private class Segment(val stream: CliExecStream, val bytes: ByteArrayOutputStream)

  private val lock = Any()
  private val segments = mutableListOf<Segment>()
  private var totalBytes = 0
  private var truncated = false

  /** Set once [maxSegments] is reached; from then on writes go to [terminalSegments]. */
  private var coalescing = false
  private val terminalSegments = mutableMapOf<CliExecStream, ByteArrayOutputStream>()

  /** An [OutputStream] that appends everything written to it to this transcript as [stream]. */
  fun sink(stream: CliExecStream): OutputStream = object : OutputStream() {
    override fun write(b: Int) = append(stream, byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) = append(stream, b, off, len)
  }

  /** Everything written so far, in write order. */
  fun chunks(): List<CliExecChunk> = synchronized(lock) {
    segments.map { CliExecChunk(it.stream, it.bytes.toString(Charsets.UTF_8)) }
  }

  /**
   * Everything written to [stream], concatenated. The flattened view a shim that predates
   * [chunks] reads; it loses the interleaving, which is the whole reason [chunks] exists.
   */
  fun textFor(stream: CliExecStream): String = synchronized(lock) {
    segments.filter { it.stream == stream }
      .joinToString(separator = "") { it.bytes.toString(Charsets.UTF_8) }
  }

  private fun append(stream: CliExecStream, b: ByteArray, off: Int, len: Int) {
    synchronized(lock) {
      val room = limit - totalBytes
      if (room <= 0) {
        recordTruncation(stream)
        return
      }
      val accepted = minOf(len, room)
      segmentFor(stream).write(b, off, accepted)
      totalBytes += accepted
      if (accepted < len) recordTruncation(stream)
    }
  }

  /** Says once, in the output itself, that the rest was dropped. Not counted against [limit]. */
  private fun recordTruncation(stream: CliExecStream) {
    if (truncated) return
    truncated = true
    val marker = "\n[cli/exec: output truncated at $limit bytes]\n".toByteArray(Charsets.UTF_8)
    segmentFor(stream).write(marker, 0, marker.size)
  }

  /** The open segment for [stream], starting a new one when the previous write went elsewhere. */
  private fun segmentFor(stream: CliExecStream): ByteArrayOutputStream {
    if (coalescing) return terminalSegment(stream)
    val last = segments.lastOrNull()
    if (last != null && last.stream == stream) return last.bytes
    if (segments.size >= maxSegments) return beginCoalescing(stream)
    return ByteArrayOutputStream().also { segments += Segment(stream, it) }
  }

  /**
   * Stops interleaving and says so once. Returns the terminal segment for [stream] so the write
   * that hit the ceiling still lands.
   */
  private fun beginCoalescing(stream: CliExecStream): ByteArrayOutputStream {
    coalescing = true
    val terminal = terminalSegment(stream)
    val note = "\n[cli/exec: output alternated between streams more than $maxSegments times; " +
      "the remainder is grouped by stream rather than interleaved]\n"
    val bytes = note.toByteArray(Charsets.UTF_8)
    terminal.write(bytes, 0, bytes.size)
    return terminal
  }

  /** The single segment every later [stream] write appends to. Created on that stream's first use. */
  private fun terminalSegment(stream: CliExecStream): ByteArrayOutputStream =
    terminalSegments.getOrPut(stream) {
      ByteArrayOutputStream().also { segments += Segment(stream, it) }
    }

  companion object {
    /**
     * Ceiling on interleaving changes before the recording groups by stream instead. Real CLI
     * output alternates tens of times; this is orders of magnitude above that, and caps the
     * per-segment overhead at something negligible next to [limit] itself.
     */
    const val MAX_SEGMENTS: Int = 4096
  }
}
