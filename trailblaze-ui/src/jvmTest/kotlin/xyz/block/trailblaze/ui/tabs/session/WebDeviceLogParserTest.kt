package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WebDeviceLogParserTest {

  private val sample = "2026-06-22 14:23:45.678 [error] Failed to load resource: net::ERR_FAILED"

  // ──────────────────────────────────────────────────────────────────────────
  // parseEpochMs — rough values (see KDoc on WebDeviceLogParser / IosDeviceLogParser).
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parseEpochMs returns non-null for canonical web console line`() {
    assertNotNull(WebDeviceLogParser.parseEpochMs(sample))
  }

  @Test
  fun `parseEpochMs handles leading whitespace`() {
    assertNotNull(
      WebDeviceLogParser.parseEpochMs("    2026-06-22 14:23:45.678 [log] continued"),
    )
  }

  @Test
  fun `parseEpochMs is monotonically increasing within a single day`() {
    val earlier = WebDeviceLogParser.parseEpochMs("2026-06-22 14:23:45.000 [log] a")!!
    val later = WebDeviceLogParser.parseEpochMs("2026-06-22 14:23:46.000 [log] b")!!
    assertEquals(1000L, later - earlier)
  }

  @Test
  fun `parseEpochMs preserves sub-second precision`() {
    // The web format always carries `.SSS` millis; two messages in the same second should
    // resolve to distinct epochs so the panel can order them on the timeline.
    val earlier = WebDeviceLogParser.parseEpochMs("2026-06-22 14:23:45.123 [log] a")!!
    val later = WebDeviceLogParser.parseEpochMs("2026-06-22 14:23:45.678 [log] b")!!
    assertEquals(555L, later - earlier)
  }

  @Test
  fun `parseEpochMs returns null for Android logcat format`() {
    assertNull(WebDeviceLogParser.parseEpochMs("1772846521.234  5432  5432 D MyTag : hello"))
  }

  @Test
  fun `parseEpochMs returns null for unparseable and short lines`() {
    assertNull(WebDeviceLogParser.parseEpochMs("--------- beginning of main"))
    assertNull(WebDeviceLogParser.parseEpochMs("hi"))
    assertNull(WebDeviceLogParser.parseEpochMs(""))
  }

  // ──────────────────────────────────────────────────────────────────────────
  // splitTimestamp
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `splitTimestamp returns prefix plus rest`() {
    val split = WebDeviceLogParser.splitTimestamp(sample)
    assertNotNull(split)
    assertEquals("2026-06-22 14:23:45.678", split.first)
    assertEquals("[error] Failed to load resource: net::ERR_FAILED", split.second)
  }

  @Test
  fun `splitTimestamp returns null for non-timestamp line`() {
    assertNull(WebDeviceLogParser.splitTimestamp("[error] no timestamp here"))
    assertNull(WebDeviceLogParser.splitTimestamp("--------- beginning of main"))
  }

  // ──────────────────────────────────────────────────────────────────────────
  // parseLogLevel — maps the bracketed console type tag.
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parseLogLevel maps console type tags`() {
    assertEquals(LogLevel.ERROR, WebDeviceLogParser.parseLogLevel("[error] boom"))
    assertEquals(LogLevel.WARN, WebDeviceLogParser.parseLogLevel("[warning] careful"))
    assertEquals(LogLevel.DEBUG, WebDeviceLogParser.parseLogLevel("[debug] trace stuff"))
    assertEquals(LogLevel.INFO, WebDeviceLogParser.parseLogLevel("[info] fyi"))
    assertEquals(LogLevel.INFO, WebDeviceLogParser.parseLogLevel("[log] plain log"))
    assertEquals(LogLevel.VERBOSE, WebDeviceLogParser.parseLogLevel("[trace] verbose"))
  }

  @Test
  fun `parseLogLevel maps unknown tag to UNKNOWN`() {
    assertEquals(LogLevel.UNKNOWN, WebDeviceLogParser.parseLogLevel("[table] data"))
  }

  @Test
  fun `parseLogLevel returns null when no bracket tag present`() {
    assertNull(WebDeviceLogParser.parseLogLevel("no bracket here"))
    assertNull(WebDeviceLogParser.parseLogLevel(""))
    assertNull(WebDeviceLogParser.parseLogLevel("[unterminated"))
  }
}
