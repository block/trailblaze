package xyz.block.trailblaze.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [decodeTransportError]. Pins the contract that transport-level
 * error payloads (the `HTTP {code}: {body}` strings `CliMcpClient.sendRequest`
 * produces when the daemon returns non-2xx) get mapped into human-actionable
 * `reason:` lines before they reach the structured CLI error envelope.
 *
 * Headline regression this guards: the iOS FTUX validator hit
 * `Error connecting to device: HTTP 404` and reported "exit 2 but nothing
 * actionable in the output." The mapper turns that into a sentence the user
 * can act on without reading source.
 */
class DecodeTransportErrorTest {

  @Test
  fun `404 maps to a multi-cause hint covering stale session, race, and recycle`() {
    val msg = decodeTransportError("HTTP 404: Not Found")
    assertTrue(
      "device list may have shifted" in msg,
      "404 reason must surface the stale-list cause. Got: $msg",
    )
    assertTrue(
      "MCP session may be stale" in msg,
      "404 reason must surface the stale-session cause. Got: $msg",
    )
    assertTrue(
      "another caller is racing" in msg,
      "404 reason must surface the contention cause. Got: $msg",
    )
    // The hint must give the user a concrete recovery command.
    assertTrue(
      "trailblaze device" in msg && "trailblaze app --stop" in msg,
      "404 reason must include actionable recovery commands. Got: $msg",
    )
  }

  @Test
  fun `409 and 423 map to a device-busy hint identifying the conflict`() {
    listOf(409, 423).forEach { code ->
      val msg = decodeTransportError("HTTP $code: Locked")
      assertTrue(
        "held by another caller" in msg,
        "HTTP $code reason must surface the conflict semantics. Got: $msg",
      )
      assertTrue(
        "HTTP $code" in msg,
        "the code must remain visible for debuggability. Got: $msg",
      )
    }
  }

  @Test
  fun `5xx maps to a daemon-internal-error hint pointing at the log file`() {
    val msg = decodeTransportError("HTTP 500: Internal Server Error")
    assertTrue(
      "Daemon internal error" in msg,
      "5xx must declare the daemon as the failing party. Got: $msg",
    )
    assertTrue(
      "~/.trailblaze/daemon.log" in msg,
      "5xx must point users at the daemon log. Got: $msg",
    )
  }

  @Test
  fun `unrecognized HTTP code stays generic but keeps the number and body`() {
    val msg = decodeTransportError("HTTP 418: I'm a teapot")
    assertTrue("HTTP 418" in msg)
    assertTrue("I'm a teapot" in msg, "body must be preserved for debugging. Got: $msg")
  }

  @Test
  fun `non-HTTP content passes through unchanged so descriptive errors aren't homogenized`() {
    // Inputs that don't look like a `HTTP {code}` payload must not be touched
    // — they already have descriptive content from upstream code paths
    // (parse errors, unrecognized session id, busy device block, etc.) that
    // we'd lose if we forced them through the HTTP mapper.
    val original = "Connection refused: localhost:4245"
    assertEquals(original, decodeTransportError(original))
  }

  @Test
  fun `HTTP code with empty body still produces a clean reason without parenthetical noise`() {
    // `sendRequest` builds the prefix `HTTP {code}: {body}` where body can be
    // empty. The reason should NOT render `(body: )` for an empty body — that's
    // visual clutter in the envelope.
    val msg = decodeTransportError("HTTP 500:")
    assertTrue(
      "(body:" !in msg,
      "empty body must not render `(body: )` parenthetical noise. Got: $msg",
    )
  }

  @Test
  fun `HTTP code with no body and no colon still produces clean output`() {
    // The body group in the regex is optional — `HTTP 500` (no colon at all)
    // is a legal prefix shape. Should not render `(body: )` parenthetical
    // noise. Pins the optional-body branch against future regex refactors.
    val msg = decodeTransportError("HTTP 500")
    assertTrue(
      "(body:" !in msg,
      "no body must not render `(body: )` parenthetical noise. Got: $msg",
    )
    assertTrue("HTTP 500" in msg, "code must remain visible. Got: $msg")
  }

  @Test
  fun `multi-line body is collapsed to one line to preserve envelope invariant`() {
    // `reportCliError` puts the decoded message into the `reason:` line of a
    // structured envelope that contracts one line per field. A multi-line
    // HTML body or indented JSON would splatter `(body: …)` across multiple
    // lines and break the contract. The decoder must collapse whitespace runs
    // (including newlines) before embedding.
    val msg = decodeTransportError("HTTP 502: <html>\n  <body>\n  bad gateway\n  </body>\n</html>")
    assertTrue(
      "\n" !in msg,
      "multi-line body must be collapsed to a single line. Got: $msg",
    )
    assertTrue(
      "bad gateway" in msg,
      "collapsed body must still carry the original text. Got: $msg",
    )
  }
}
