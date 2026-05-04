package xyz.block.trailblaze.ui.tabs.session

import kotlinx.serialization.json.Json
import xyz.block.trailblaze.network.NetworkEvent
import xyz.block.trailblaze.network.Phase
import xyz.block.trailblaze.network.REDACTED_VALUE
import xyz.block.trailblaze.network.Source
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NetworkLogSourceTest {

  private val json = Json {
    encodeDefaults = false
    explicitNulls = false
  }

  private fun encode(event: NetworkEvent): String =
    json.encodeToString(NetworkEvent.serializer(), event)

  // ──────────────────────────────────────────────────────────────────────────
  // Source contract
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `filterableLevels is empty so V D I W E F chips do not render`() {
    // Network events don't carry logcat-style severity. The panel hides chips when
    // this list is empty — pin that here so a future contributor doesn't accidentally
    // re-introduce ERROR/INFO chips that would only confuse the network view.
    assertEquals(emptyList(), NetworkLogSource.filterableLevels)
  }

  @Test
  fun `id and displayName are stable`() {
    assertEquals("network", NetworkLogSource.id)
    assertEquals("Network", NetworkLogSource.displayName)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Parse — happy path
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `paired REQUEST_START + RESPONSE_END produces two lines with shared epochs`() {
    val start = NetworkEvent(
      id = "abc",
      sessionId = "s1",
      timestampMs = 1_700_000_000_000L,
      phase = Phase.REQUEST_START,
      method = "POST",
      url = "https://api.example.com/v1/cdp/batch?u=1",
      urlPath = "/v1/cdp/batch",
      source = Source.PLAYWRIGHT_WEB,
    )
    val end = start.copy(
      phase = Phase.RESPONSE_END,
      timestampMs = 1_700_000_000_090L,
      statusCode = 204,
      durationMs = 90L,
    )
    val ndjson = "${encode(start)}\n${encode(end)}\n"

    val parsed = NetworkLogSource.parse(ndjson)
    assertEquals(2, parsed.totalRawLineCount)
    assertEquals(2, parsed.lines.size)
    assertEquals(false, parsed.truncated)

    assertEquals(1_700_000_000_000L, parsed.lines[0].epochMs)
    assertEquals(1_700_000_000_090L, parsed.lines[1].epochMs)
    // First line anchors relative timing at 0:00.000.
    assertEquals("0:00.000", parsed.lines[0].timestampDisplay)
  }

  @Test
  fun `RESPONSE_END renders as METHOD STATUS duration ms urlPath`() {
    // Pin the canonical format — the issue body literally specifies this string shape
    // (`POST 204 [90ms] /v1/cdp/batch`), so the renderer baseline is locked here.
    val event = NetworkEvent(
      id = "abc",
      sessionId = "s1",
      timestampMs = 1_700_000_000_000L,
      phase = Phase.RESPONSE_END,
      method = "POST",
      url = "https://api.example.com/v1/cdp/batch",
      urlPath = "/v1/cdp/batch",
      statusCode = 204,
      durationMs = 90L,
      source = Source.PLAYWRIGHT_WEB,
    )
    val parsed = NetworkLogSource.parse(encode(event))
    assertEquals("POST 204 [90ms] /v1/cdp/batch", parsed.lines.single().content)
  }

  @Test
  fun `REQUEST_START renders with arrow and no status`() {
    val event = NetworkEvent(
      id = "abc",
      sessionId = "s1",
      timestampMs = 1_700_000_000_000L,
      phase = Phase.REQUEST_START,
      method = "GET",
      url = "https://api.example.com/me",
      urlPath = "/me",
      source = Source.PLAYWRIGHT_WEB,
    )
    val parsed = NetworkLogSource.parse(encode(event))
    val content = parsed.lines.single().content
    // REQUEST_START uses → as the phase token. Don't pin the exact arrow byte so a
    // future tweak (e.g. an ASCII fallback) doesn't flake the test.
    assertContains(content, "GET ")
    assertContains(content, "/me")
    assertTrue(parsed.lines.single().level == LogLevel.INFO)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Severity mapping — the load-bearing thing the panel renders
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `4xx response maps to ERROR`() {
    val event = baseResponse(statusCode = 404)
    assertEquals(LogLevel.ERROR, NetworkLogSource.parse(encode(event)).lines.single().level)
  }

  @Test
  fun `5xx response maps to ERROR`() {
    val event = baseResponse(statusCode = 503)
    assertEquals(LogLevel.ERROR, NetworkLogSource.parse(encode(event)).lines.single().level)
  }

  @Test
  fun `2xx response maps to INFO`() {
    val event = baseResponse(statusCode = 200)
    assertEquals(LogLevel.INFO, NetworkLogSource.parse(encode(event)).lines.single().level)
  }

  @Test
  fun `3xx response maps to INFO`() {
    // Redirects aren't errors — pin INFO so a refactor that lumps 3xx with 4xx by accident
    // (e.g. `>= 300`) gets caught here.
    val event = baseResponse(statusCode = 301)
    assertEquals(LogLevel.INFO, NetworkLogSource.parse(encode(event)).lines.single().level)
  }

  @Test
  fun `FAILED phase maps to ERROR even without a status code`() {
    val event = NetworkEvent(
      id = "abc",
      sessionId = "s1",
      timestampMs = 1L,
      phase = Phase.FAILED,
      method = "POST",
      url = "https://api.example.com/timeout",
      urlPath = "/timeout",
      durationMs = 30_000L,
      source = Source.PLAYWRIGHT_WEB,
    )
    val parsed = NetworkLogSource.parse(encode(event))
    val line = parsed.lines.single()
    assertEquals(LogLevel.ERROR, line.level)
    assertContains(line.content, "FAILED")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Tolerance — the read tool skips malformed NDJSON; this parser does the same
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `malformed NDJSON lines are skipped and counted into ParsedLog`() {
    val good = encode(baseResponse(statusCode = 200))
    val ndjson = listOf(
      good,
      "{this is not json",
      "",
      good,
      "garbage",
    ).joinToString("\n")

    val parsed = NetworkLogSource.parse(ndjson)
    // Two valid events survive; blank lines and malformed lines are dropped.
    assertEquals(2, parsed.lines.size)
    // Total counts only non-blank raw lines; blank line was filtered before counting.
    assertEquals(4, parsed.totalRawLineCount)
    // Two non-blank lines failed to decode — pin the count so a future change that
    // silently swallows malformed lines without surfacing them gets caught here.
    assertEquals(2, parsed.malformedLineCount)
  }

  @Test
  fun `malformedLineCount is zero when every line decodes cleanly`() {
    val ndjson = (1..3).joinToString("\n") { encode(baseResponse(statusCode = 200).copy(id = "id-$it")) }
    val parsed = NetworkLogSource.parse(ndjson)
    assertEquals(3, parsed.lines.size)
    assertEquals(0, parsed.malformedLineCount)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Render-format edge cases — null statusCode and empty urlPath
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `RESPONSE_END with null statusCode renders question-mark and maps to INFO`() {
    // Aborted/zero-status responses arrive with a null statusCode. The renderer must
    // not crash and must not silently classify as ERROR (only 4xx/5xx and FAILED do).
    val event = NetworkEvent(
      id = "abc",
      sessionId = "s1",
      timestampMs = 1L,
      phase = Phase.RESPONSE_END,
      method = "GET",
      url = "https://api.example.com/aborted",
      urlPath = "/aborted",
      statusCode = null,
      durationMs = 12L,
      source = Source.PLAYWRIGHT_WEB,
    )
    val parsed = NetworkLogSource.parse(encode(event))
    val line = parsed.lines.single()
    assertContains(line.content, "?")
    assertEquals(LogLevel.INFO, line.level)
  }

  @Test
  fun `empty urlPath falls back to the full URL`() {
    // When Playwright's URI parser yields an empty `urlPath` (e.g. requests like
    // `https://example.com` with no path component), the renderer should fall back to
    // the full URL so the line still identifies the destination.
    val event = NetworkEvent(
      id = "abc",
      sessionId = "s1",
      timestampMs = 1L,
      phase = Phase.RESPONSE_END,
      method = "GET",
      url = "https://example.com",
      urlPath = "",
      statusCode = 200,
      durationMs = 12L,
      source = Source.PLAYWRIGHT_WEB,
    )
    val parsed = NetworkLogSource.parse(encode(event))
    val content = parsed.lines.single().content
    assertContains(content, "https://example.com")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Empty input
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `empty ndjson produces an empty ParsedLog`() {
    val parsed = NetworkLogSource.parse("")
    assertEquals(0, parsed.lines.size)
    assertEquals(0, parsed.totalRawLineCount)
    assertEquals(0, parsed.malformedLineCount)
    assertEquals(false, parsed.truncated)
  }

  @Test
  fun `all-blank input produces an empty ParsedLog`() {
    // Blank lines are filtered before counting so they don't inflate totalRawLineCount.
    val parsed = NetworkLogSource.parse("\n\n   \n\n")
    assertEquals(0, parsed.lines.size)
    assertEquals(0, parsed.totalRawLineCount)
    assertEquals(0, parsed.malformedLineCount)
  }

  @Test
  fun `redacted authorization header still parses cleanly`() {
    // The capture writer replaces sensitive header values with REDACTED_VALUE — it must
    // not break the parser. Confirms the parser doesn't try to interpret the marker.
    val event = NetworkEvent(
      id = "abc",
      sessionId = "s1",
      timestampMs = 1L,
      phase = Phase.REQUEST_START,
      method = "GET",
      url = "https://api.example.com/me",
      urlPath = "/me",
      requestHeaders = mapOf(
        "accept" to "application/json",
        "authorization" to REDACTED_VALUE,
      ),
      source = Source.PLAYWRIGHT_WEB,
    )
    val parsed = NetworkLogSource.parse(encode(event))
    assertEquals(1, parsed.lines.size)
    assertNotNull(parsed.lines.single().epochMs)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Truncation — same takeLast cap behavior the device source has
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parse marks truncated=true when input exceeds the display cap`() {
    val cap = 5_000
    val total = cap + 1
    val ndjson = (1..total).joinToString("\n") { i ->
      encode(
        baseResponse(statusCode = 200).copy(
          id = "id-$i",
          urlPath = "/event/$i",
          timestampMs = 1_700_000_000_000L + i,
        ),
      )
    }
    val parsed = NetworkLogSource.parse(ndjson)
    assertEquals(total, parsed.totalRawLineCount)
    assertEquals(cap, parsed.lines.size)
    assertEquals(true, parsed.truncated)
    // takeLast — the most recent event must be preserved.
    assertContains(parsed.lines.last().content, "/event/$total")
  }

  // ──────────────────────────────────────────────────────────────────────────
  // Helpers
  // ──────────────────────────────────────────────────────────────────────────

  private fun baseResponse(statusCode: Int): NetworkEvent = NetworkEvent(
    id = "abc",
    sessionId = "s1",
    timestampMs = 1L,
    phase = Phase.RESPONSE_END,
    method = "POST",
    url = "https://api.example.com/v1/cdp/batch",
    urlPath = "/v1/cdp/batch",
    statusCode = statusCode,
    durationMs = 12L,
    source = Source.PLAYWRIGHT_WEB,
  )
}
