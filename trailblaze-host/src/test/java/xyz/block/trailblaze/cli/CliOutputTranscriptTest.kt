package xyz.block.trailblaze.cli

import org.junit.Test
import xyz.block.trailblaze.logs.server.endpoints.CliExecStream
import java.util.concurrent.CountDownLatch
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A forwarded command's output is reassembled on the caller's side from what this records, so
 * what it records has to be the order the command actually wrote — see [CliOutputTranscript].
 */
class CliOutputTranscriptTest {

  private val big = 1 shl 20

  /** Drops the one-time grouping note, leaving only what the command itself wrote. */
  private fun String.withoutNote(): String = replace(Regex("""\n\[cli/exec:[^\]]*]\n"""), "")

  private fun CliOutputTranscript.write(stream: CliExecStream, text: String) {
    val bytes = text.toByteArray(Charsets.UTF_8)
    sink(stream).write(bytes, 0, bytes.size)
  }

  @Test fun `chunks come back in the order they were written, not grouped by stream`() {
    val transcript = CliOutputTranscript(big)

    // A failed `trailblaze tool`: a status line while connecting, the error, then its tip.
    transcript.write(CliExecStream.STDERR, "Auto-using only connected device\n")
    transcript.write(CliExecStream.STDOUT, "→ Error — ref not found\n")
    transcript.write(CliExecStream.STDERR, "Tip: run snapshot\n")

    assertEquals(
      listOf(
        CliExecStream.STDERR to "Auto-using only connected device\n",
        CliExecStream.STDOUT to "→ Error — ref not found\n",
        CliExecStream.STDERR to "Tip: run snapshot\n",
      ),
      transcript.chunks().map { it.stream to it.text },
    )
  }

  @Test fun `consecutive writes to one stream are one chunk`() {
    val transcript = CliOutputTranscript(big)

    transcript.write(CliExecStream.STDOUT, "line one\n")
    transcript.write(CliExecStream.STDOUT, "line two\n")
    transcript.sink(CliExecStream.STDOUT).write('x'.code)

    // A chunk per write would make a UI tree thousands of JSON objects for no added ordering.
    assertEquals(1, transcript.chunks().size, "unbroken output on one stream should not split")
    assertEquals("line one\nline two\nx", transcript.chunks().single().text)
  }

  @Test fun `the per-stream view holds only that stream, in order`() {
    val transcript = CliOutputTranscript(big)

    transcript.write(CliExecStream.STDOUT, "out-1\n")
    transcript.write(CliExecStream.STDERR, "err-1\n")
    transcript.write(CliExecStream.STDOUT, "out-2\n")

    assertEquals("out-1\nout-2\n", transcript.textFor(CliExecStream.STDOUT))
    assertEquals("err-1\n", transcript.textFor(CliExecStream.STDERR))
  }

  @Test fun `output past the cap is dropped and said so once`() {
    val transcript = CliOutputTranscript(limit = 16)

    transcript.write(CliExecStream.STDOUT, "0123456789")
    transcript.write(CliExecStream.STDOUT, "abcdefghij")
    transcript.write(CliExecStream.STDOUT, "klmnopqrst")

    val text = transcript.textFor(CliExecStream.STDOUT)
    assertTrue(text.startsWith("0123456789abcdef"), "bytes up to the cap must survive: '$text'")
    assertTrue(text.contains("output truncated at 16 bytes"), "truncation must be visible: '$text'")
    assertEquals(
      1,
      Regex("output truncated").findAll(text).count(),
      "the marker must be recorded once, not once per rejected write: '$text'",
    )
    assertTrue(!text.contains("klmnopqrst"), "writes after the cap must be dropped: '$text'")
  }

  @Test fun `a command writing from two threads loses nothing`() {
    val transcript = CliOutputTranscript(big)
    val start = CountDownLatch(1)
    val writers = listOf(CliExecStream.STDOUT, CliExecStream.STDERR).map { stream ->
      Thread {
        start.await()
        repeat(500) { transcript.write(stream, "$stream-$it\n") }
      }
    }

    writers.forEach { it.start() }
    start.countDown()
    writers.forEach { it.join() }

    // Interleaving between threads is whatever the OS scheduled; what must hold is that no write
    // was lost or spliced into another one's bytes.
    listOf(CliExecStream.STDOUT, CliExecStream.STDERR).forEach { stream ->
      val lines = transcript.textFor(stream).trimEnd('\n').split("\n")
      assertEquals((0 until 500).map { "$stream-$it" }, lines, "$stream lost or corrupted a write")
    }
  }

  @Test fun `capture through System out and err records the real order`() {
    CliOutCapture.install()
    val transcript = CliOutputTranscript(big)

    CliOutCapture.withCapture(
      transcript.sink(CliExecStream.STDOUT),
      transcript.sink(CliExecStream.STDERR),
    ) {
      System.err.print("connecting…\n")
      print("→ Error — héllo 🔥\n")
      System.err.print("Tip: try again\n")
    }

    assertEquals(
      listOf(
        CliExecStream.STDERR to "connecting…\n",
        CliExecStream.STDOUT to "→ Error — héllo 🔥\n",
        CliExecStream.STDERR to "Tip: try again\n",
      ),
      transcript.chunks().map { it.stream to it.text },
    )
  }

  @Test fun `alternating streams cannot open a segment per byte`() {
    // The attack the byte limit alone does not stop: only consecutive same-stream writes coalesce,
    // so one-byte alternation used to open a segment — and a ByteArrayOutputStream with its own
    // backing array — for every byte written, at a cost per byte far above the byte itself.
    val maxSegments = 8
    val transcript = CliOutputTranscript(big, maxSegments = maxSegments)

    repeat(500) {
      transcript.write(CliExecStream.STDOUT, "o")
      transcript.write(CliExecStream.STDERR, "e")
    }

    assertTrue(
      transcript.chunks().size <= maxSegments + 2,
      "1000 alternating writes produced ${transcript.chunks().size} segments",
    )
  }

  @Test fun `every byte survives the switch to grouping, attributed to its own stream`() {
    val transcript = CliOutputTranscript(big, maxSegments = 4)

    repeat(200) {
      transcript.write(CliExecStream.STDOUT, "o")
      transcript.write(CliExecStream.STDERR, "e")
    }

    // The note is the only thing added, so drop it before looking at what the command wrote.
    val stdout = transcript.textFor(CliExecStream.STDOUT).withoutNote()
    val stderr = transcript.textFor(CliExecStream.STDERR).withoutNote()
    assertEquals("o".repeat(200), stdout, "stdout is not exactly what the command wrote to it")
    assertEquals("e".repeat(200), stderr, "stderr is not exactly what the command wrote to it")
  }

  @Test fun `the reader is told once that ordering stopped being interleaved`() {
    val transcript = CliOutputTranscript(big, maxSegments = 4)

    repeat(50) {
      transcript.write(CliExecStream.STDOUT, "o")
      transcript.write(CliExecStream.STDERR, "e")
    }

    val combined = transcript.chunks().joinToString("") { it.text }
    assertEquals(
      1,
      Regex("grouped by stream rather than interleaved").findAll(combined).count(),
      "the note should appear exactly once",
    )
  }

  @Test fun `ordinary output is unaffected by the segment ceiling`() {
    val transcript = CliOutputTranscript(big)

    transcript.write(CliExecStream.STDERR, "connecting\n")
    transcript.write(CliExecStream.STDOUT, "result\n")
    transcript.write(CliExecStream.STDERR, "tip\n")

    assertEquals(
      listOf(
        CliExecStream.STDERR to "connecting\n",
        CliExecStream.STDOUT to "result\n",
        CliExecStream.STDERR to "tip\n",
      ),
      transcript.chunks().map { it.stream to it.text },
    )
  }
}
