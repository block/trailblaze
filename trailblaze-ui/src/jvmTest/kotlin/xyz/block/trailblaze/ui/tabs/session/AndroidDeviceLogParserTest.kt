package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AndroidDeviceLogParserTest {

  // ──────────────────────────────────────────────────────────────────────────
  // parseEpochMs
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parseEpochMs extracts canonical Android epoch (seconds + millis)`() {
    val ms = AndroidDeviceLogParser.parseEpochMs("1772846521.234  5432  5432 D MyTag : hello")
    assertEquals(1772846521234L, ms)
  }

  @Test
  fun `parseEpochMs handles indented continuation lines (leading whitespace)`() {
    val ms = AndroidDeviceLogParser.parseEpochMs(
      "    1772846521.234  5432  5432 D MyTag : continued",
    )
    assertEquals(1772846521234L, ms)
  }

  @Test
  fun `parseEpochMs pads short millis fragments to 3 digits`() {
    // "1772846521.5" should be treated as 1772846521 + 500 millis (pad to "500").
    val ms = AndroidDeviceLogParser.parseEpochMs("1772846521.5    5432  5432 D : hello")
    assertEquals(1772846521500L, ms)
  }

  @Test
  fun `parseEpochMs returns null for iOS line`() {
    assertNull(
      AndroidDeviceLogParser.parseEpochMs("2026-03-10 14:23:45.678901-0700  MyApp: hi"),
    )
  }

  @Test
  fun `parseEpochMs returns null for unparseable line`() {
    assertNull(AndroidDeviceLogParser.parseEpochMs("--------- beginning of main"))
  }

  @Test
  fun `parseEpochMs returns null for too-short line`() {
    assertNull(AndroidDeviceLogParser.parseEpochMs("hi"))
    assertNull(AndroidDeviceLogParser.parseEpochMs(""))
  }

  // ──────────────────────────────────────────────────────────────────────────
  // splitTimestamp
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `splitTimestamp returns prefix plus rest for Android line`() {
    val split = AndroidDeviceLogParser.splitTimestamp(
      "1772846521.234  5432  5432 D MyTag : hello",
    )
    assertNotNull(split)
    assertEquals("1772846521.234", split.first)
    assertEquals("5432  5432 D MyTag : hello", split.second)
  }

  @Test
  fun `splitTimestamp returns null for iOS line`() {
    assertNull(
      AndroidDeviceLogParser.splitTimestamp("2026-03-10 14:23:45.678901-0700  MyApp: hi"),
    )
  }

  @Test
  fun `splitTimestamp returns null for non-timestamp line`() {
    assertNull(AndroidDeviceLogParser.splitTimestamp("--------- beginning of main"))
  }

  // ──────────────────────────────────────────────────────────────────────────
  // parseLogLevel
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parseLogLevel maps Android single-letter priorities`() {
    // Use the canonical " X " spacing the parser looks for (priority is between PID/TID and tag).
    assertEquals(
      LogLevel.VERBOSE,
      AndroidDeviceLogParser.parseLogLevel("5432  5432 V MyTag : verbose msg"),
    )
    assertEquals(
      LogLevel.DEBUG,
      AndroidDeviceLogParser.parseLogLevel("5432  5432 D MyTag : debug msg"),
    )
    assertEquals(
      LogLevel.INFO,
      AndroidDeviceLogParser.parseLogLevel("5432  5432 I MyTag : info msg"),
    )
    assertEquals(
      LogLevel.WARN,
      AndroidDeviceLogParser.parseLogLevel("5432  5432 W MyTag : warn msg"),
    )
    assertEquals(
      LogLevel.ERROR,
      AndroidDeviceLogParser.parseLogLevel("5432  5432 E MyTag : error msg"),
    )
    assertEquals(
      LogLevel.FATAL,
      AndroidDeviceLogParser.parseLogLevel("5432  5432 F MyTag : fatal msg"),
    )
  }

  @Test
  fun `parseLogLevel matches priority-followed-by-slash format`() {
    // The parser looks for " E/" (leading space) since the priority is preceded by a
    // PID/TID column in real logcat output. Pure-leading "E/MyTag" would NOT match —
    // that case is rare in logcat dumps and not worth a special branch.
    assertEquals(
      LogLevel.ERROR,
      AndroidDeviceLogParser.parseLogLevel("5432 E/MyTag : error"),
    )
  }

  @Test
  fun `parseLogLevel falls back to keyword heuristics`() {
    assertEquals(
      LogLevel.ERROR,
      AndroidDeviceLogParser.parseLogLevel("java.lang.RuntimeException: kaboom"),
    )
    assertEquals(LogLevel.ERROR, AndroidDeviceLogParser.parseLogLevel("ANR in com.example"))
    assertEquals(LogLevel.FATAL, AndroidDeviceLogParser.parseLogLevel("FATAL EXCEPTION"))
  }

  @Test
  fun `parseLogLevel returns UNKNOWN when no signal is found`() {
    assertEquals(LogLevel.UNKNOWN, AndroidDeviceLogParser.parseLogLevel("hello world"))
    assertEquals(LogLevel.UNKNOWN, AndroidDeviceLogParser.parseLogLevel(""))
  }
}
