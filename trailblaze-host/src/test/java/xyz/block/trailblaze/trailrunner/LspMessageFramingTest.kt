package xyz.block.trailblaze.trailrunner

import assertk.assertThat
import assertk.assertions.containsExactly
import assertk.assertions.isEmpty
import assertk.assertions.isEqualTo
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * Behavior tests for the LSP stdio framing codec. The contract that matters: bytes in == bytes out
 * across arbitrary chunk boundaries (a `read()` can split anywhere), multiple messages can arrive in
 * one chunk, and UTF-8 bodies are measured in BYTES not characters. These are exactly the cases a
 * live-process integration test would be too coarse to pin.
 */
class LspMessageFramingTest {

  private fun framed(body: String): ByteArray = LspMessageFraming.encode(body)

  @Test
  fun `encode prefixes a byte-length Content-Length header`() {
    val encoded = LspMessageFraming.encode("""{"jsonrpc":"2.0"}""")
    val text = String(encoded, Charsets.UTF_8)
    assertThat(text).isEqualTo("Content-Length: 17\r\n\r\n{\"jsonrpc\":\"2.0\"}")
  }

  @Test
  fun `encode counts UTF-8 bytes not characters`() {
    // "é" + "🚀" are 2 + 4 UTF-8 bytes; a char-count header would understate the body and desync tsserver.
    val body = "é🚀"
    val encoded = LspMessageFraming.encode(body)
    val header = String(encoded, Charsets.US_ASCII).substringBefore("\r\n")
    assertThat(header).isEqualTo("Content-Length: ${body.toByteArray(Charsets.UTF_8).size}")
  }

  @Test
  fun `decoder reassembles a single whole message`() {
    val decoder = LspMessageFraming.Decoder()
    assertThat(decoder.feed(framed("""{"id":1}"""))).containsExactly("""{"id":1}""")
  }

  @Test
  fun `decoder yields multiple messages from one chunk`() {
    val decoder = LspMessageFraming.Decoder()
    val combined = framed("""{"id":1}""") + framed("""{"id":2}""")
    assertThat(decoder.feed(combined)).containsExactly("""{"id":1}""", """{"id":2}""")
  }

  @Test
  fun `decoder buffers across a chunk boundary that splits the header`() {
    val decoder = LspMessageFraming.Decoder()
    val whole = framed("""{"id":42}""")
    val splitAt = 5 // mid-"Content-Length"
    assertThat(decoder.feed(whole.copyOfRange(0, splitAt))).isEmpty()
    assertThat(decoder.feed(whole.copyOfRange(splitAt, whole.size))).containsExactly("""{"id":42}""")
  }

  @Test
  fun `decoder buffers across a chunk boundary that splits the body`() {
    val decoder = LspMessageFraming.Decoder()
    val whole = framed("""{"method":"initialize"}""")
    val splitAt = whole.size - 4 // a few body bytes still pending
    assertThat(decoder.feed(whole.copyOfRange(0, splitAt))).isEmpty()
    assertThat(decoder.feed(whole.copyOfRange(splitAt, whole.size))).containsExactly("""{"method":"initialize"}""")
  }

  @Test
  fun `decoder reassembles a multibyte body split mid-character`() {
    val decoder = LspMessageFraming.Decoder()
    val body = """{"msg":"🚀ok"}"""
    val whole = framed(body)
    // Split inside the 4-byte rocket so neither half is valid UTF-8 on its own — the decoder must
    // reassemble at the byte level before decoding, or this round-trips to replacement characters.
    val rocketFirstByte = "🚀".toByteArray(Charsets.UTF_8)[0]
    val rocketStart = whole.indexOf(rocketFirstByte)
    assertThat(decoder.feed(whole.copyOfRange(0, rocketStart + 2))).isEmpty()
    assertThat(decoder.feed(whole.copyOfRange(rocketStart + 2, whole.size))).containsExactly(body)
  }

  @Test
  fun `decoder carries a trailing partial message to the next feed`() {
    val decoder = LspMessageFraming.Decoder()
    val first = framed("""{"id":1}""")
    val second = framed("""{"id":2}""")
    // First whole message + only the head of the second arrive together.
    val firstFeed = first + second.copyOfRange(0, 6)
    assertThat(decoder.feed(firstFeed)).containsExactly("""{"id":1}""")
    assertThat(decoder.feed(second.copyOfRange(6, second.size))).containsExactly("""{"id":2}""")
  }

  @Test
  fun `parseContentLength is case-insensitive and tolerates extra headers`() {
    val header = "content-length: 12\r\nContent-Type: application/vscode-jsonrpc; charset=utf-8"
    assertThat(LspMessageFraming.parseContentLength(header)).isEqualTo(12)
  }

  @Test
  fun `parseContentLength returns null when the header is absent`() {
    assertThat(LspMessageFraming.parseContentLength("Content-Type: application/json")).isEqualTo(null)
  }

  @Test
  fun `decoder throws on a complete header block with no valid Content-Length`() {
    // A fully-arrived header block (terminated by the blank line) whose Content-Length isn't a number
    // is a protocol violation — the decoder can't know the body length, so it throws loudly rather
    // than silently stalling. (LspRoutes' stdout pump catches this and closes the socket.)
    val decoder = LspMessageFraming.Decoder()
    val bytes = "Content-Length: notanumber\r\n\r\n{\"id\":1}".toByteArray(Charsets.UTF_8)
    assertFailsWith<IllegalStateException> { decoder.feed(bytes) }
  }

  @Test
  fun `decoder returns empty for an empty chunk`() {
    assertThat(LspMessageFraming.Decoder().feed(ByteArray(0))).isEmpty()
  }
}
