package xyz.block.trailblaze.trailrunner

/**
 * Codec for the Language Server Protocol's stdio base framing.
 *
 * LSP servers (here: `vtsls`, the TypeScript language server we spawn for the Trail Runner
 * scripted-tool editor) speak a stream of `Content-Length`-delimited messages over stdin/stdout:
 *
 * ```
 * Content-Length: 123\r\n
 * \r\n
 * {"jsonrpc":"2.0", ... 123 bytes of UTF-8 JSON ... }
 * ```
 *
 * The browser side ([monaco-languageclient] over [vscode-ws-jsonrpc]) instead carries **one bare
 * JSON-RPC message per WebSocket text frame** — no headers. The Ktor WebSocket↔stdio bridge in
 * [lspRoutes] is the translation layer:
 *
 *  - **WS → server**: each inbound text frame (bare JSON) is wrapped by [encode] and written to the
 *    server's stdin.
 *  - **server → WS**: the server's stdout byte stream is fed to a [Decoder], which yields each
 *    complete message body to be sent back as one outbound text frame.
 *
 * This object is intentionally pure (no I/O, no coroutines) so the framing — the part most likely to
 * break on a chunk boundary — is unit-testable by feeding byte slices directly, without spawning a
 * process. See `LspMessageFramingTest`.
 */
internal object LspMessageFraming {

  private const val CONTENT_LENGTH_HEADER = "Content-Length"

  /** The 4 bytes `\r\n\r\n` that terminate the header block. */
  private val HEADER_TERMINATOR = byteArrayOf(0x0D, 0x0A, 0x0D, 0x0A)

  /**
   * Frame a single JSON-RPC message body for the server's stdin: a `Content-Length` header (byte
   * count of the UTF-8 body, NOT character count) + the blank-line separator + the body. Headers are
   * ASCII per the LSP spec; the body is UTF-8.
   */
  fun encode(jsonBody: String): ByteArray {
    val bodyBytes = jsonBody.toByteArray(Charsets.UTF_8)
    val header = "$CONTENT_LENGTH_HEADER: ${bodyBytes.size}\r\n\r\n".toByteArray(Charsets.US_ASCII)
    return header + bodyBytes
  }

  /**
   * Streaming reassembler for the server's stdout. [feed] is called with whatever bytes a single
   * `read()` produced — which may split a message mid-header or mid-body, or carry several messages
   * at once — and returns the list of complete message bodies (decoded UTF-8 JSON) now available,
   * retaining any partial trailing message for the next call.
   *
   * Stateful (it buffers the partial tail), so one instance is owned by one server connection.
   */
  class Decoder {
    // Accumulates bytes not yet emitted as a complete message. Reassigned (not mutated in place) on
    // each successful extraction so the retained buffer only ever holds the partial trailing message.
    private var buffer = ByteArray(0)

    fun feed(chunk: ByteArray): List<String> {
      if (chunk.isEmpty()) return emptyList()
      buffer += chunk
      val messages = mutableListOf<String>()
      while (true) {
        val headerEnd = indexOf(buffer, HEADER_TERMINATOR)
        if (headerEnd < 0) break // header block not fully arrived yet
        val headerText = String(buffer, 0, headerEnd, Charsets.US_ASCII)
        val contentLength = parseContentLength(headerText)
          ?: error(
            "LSP frame header block lacked a valid Content-Length (got: ${headerText.replace("\r\n", " | ")}). " +
              "The language server's stdout is not LSP-framed as expected.",
          )
        val bodyStart = headerEnd + HEADER_TERMINATOR.size
        if (buffer.size - bodyStart < contentLength) break // body not fully arrived yet
        messages += String(buffer, bodyStart, contentLength, Charsets.UTF_8)
        buffer = buffer.copyOfRange(bodyStart + contentLength, buffer.size)
      }
      return messages
    }
  }

  /**
   * Parse the `Content-Length` value from a header block (the text before `\r\n\r\n`). Header-name
   * match is case-insensitive per RFC; returns null if the header is absent or its value isn't a
   * non-negative integer.
   */
  internal fun parseContentLength(headerBlock: String): Int? {
    for (line in headerBlock.split("\r\n")) {
      val colon = line.indexOf(':')
      if (colon < 0) continue
      if (!line.substring(0, colon).trim().equals(CONTENT_LENGTH_HEADER, ignoreCase = true)) continue
      return line.substring(colon + 1).trim().toIntOrNull()?.takeIf { it >= 0 }
    }
    return null
  }

  /** First index of [needle] within [haystack], or -1. Small inputs (LSP headers), so the naive scan is fine. */
  private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
    if (needle.isEmpty() || haystack.size < needle.size) return -1
    outer@ for (i in 0..haystack.size - needle.size) {
      for (j in needle.indices) {
        if (haystack[i + j] != needle[j]) continue@outer
      }
      return i
    }
    return -1
  }
}
