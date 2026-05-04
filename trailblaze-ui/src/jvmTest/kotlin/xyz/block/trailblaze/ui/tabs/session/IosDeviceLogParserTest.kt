package xyz.block.trailblaze.ui.tabs.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IosDeviceLogParserTest {

  // ──────────────────────────────────────────────────────────────────────────
  // parseEpochMs — these are *rough* values (see KDoc on IosDeviceLogParser).
  // We assert that ordering and rough magnitude are stable, not exact UTC math.
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parseEpochMs returns non-null for canonical iOS line`() {
    val ms = IosDeviceLogParser.parseEpochMs(
      "2026-03-10 14:23:45.678901-0700  MyApp[12345]: hi",
    )
    assertNotNull(ms)
  }

  @Test
  fun `parseEpochMs handles iOS line with leading whitespace (continuation lines)`() {
    val ms = IosDeviceLogParser.parseEpochMs(
      "    2026-03-10 14:23:45.123-0700  MyApp[12345]: continued",
    )
    assertNotNull(ms)
  }

  @Test
  fun `parseEpochMs is monotonically increasing within a single day`() {
    val earlier = IosDeviceLogParser.parseEpochMs(
      "2026-03-10 14:23:45.000-0700  MyApp: a",
    )!!
    val later = IosDeviceLogParser.parseEpochMs(
      "2026-03-10 14:23:46.000-0700  MyApp: b",
    )!!
    assertEquals(1000L, later - earlier)
  }

  @Test
  fun `parseEpochMs returns null for Android logcat format`() {
    assertNull(
      IosDeviceLogParser.parseEpochMs("1772846521.234  5432  5432 D MyTag : hello"),
    )
  }

  @Test
  fun `parseEpochMs returns null for unparseable line`() {
    assertNull(IosDeviceLogParser.parseEpochMs("--------- beginning of main"))
  }

  @Test
  fun `parseEpochMs returns null for short lines`() {
    assertNull(IosDeviceLogParser.parseEpochMs("hi"))
    assertNull(IosDeviceLogParser.parseEpochMs(""))
  }

  // ──────────────────────────────────────────────────────────────────────────
  // splitTimestamp
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `splitTimestamp returns prefix plus rest for iOS line`() {
    val split = IosDeviceLogParser.splitTimestamp(
      "2026-03-10 14:23:45.678901-0700  MyApp[12345]: hello",
    )
    assertNotNull(split)
    assertEquals("2026-03-10 14:23:45.678901-0700", split.first)
    assertEquals("MyApp[12345]: hello", split.second)
  }

  @Test
  fun `splitTimestamp returns null for Android line`() {
    assertNull(IosDeviceLogParser.splitTimestamp("1772846521.234  5432  5432 D MyTag : hello"))
  }

  @Test
  fun `splitTimestamp returns null for non-timestamp line`() {
    assertNull(IosDeviceLogParser.splitTimestamp("--------- beginning of main"))
  }

  // ──────────────────────────────────────────────────────────────────────────
  // parseLogLevel
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parseLogLevel maps iOS compact codes`() {
    assertEquals(LogLevel.FATAL, IosDeviceLogParser.parseLogLevel("Ft something"))
    assertEquals(LogLevel.ERROR, IosDeviceLogParser.parseLogLevel("Er bad thing"))
    assertEquals(LogLevel.DEBUG, IosDeviceLogParser.parseLogLevel("Db debug stuff"))
    assertEquals(LogLevel.INFO, IosDeviceLogParser.parseLogLevel("In info stuff"))
    assertEquals(LogLevel.INFO, IosDeviceLogParser.parseLogLevel("Df default level"))
    assertEquals(LogLevel.INFO, IosDeviceLogParser.parseLogLevel("Nt notice"))
  }

  @Test
  fun `parseLogLevel matches case-insensitively`() {
    assertEquals(LogLevel.ERROR, IosDeviceLogParser.parseLogLevel("ER big problem"))
    assertEquals(LogLevel.FATAL, IosDeviceLogParser.parseLogLevel("ft fatal"))
  }

  @Test
  fun `parseLogLevel returns null when no iOS code prefix matches`() {
    // Android-style level letters with surrounding spaces should NOT match the iOS path.
    assertNull(IosDeviceLogParser.parseLogLevel(" D MyTag : hello"))
    assertNull(IosDeviceLogParser.parseLogLevel("hello world"))
    assertNull(IosDeviceLogParser.parseLogLevel(""))
    assertNull(IosDeviceLogParser.parseLogLevel("X"))
  }
}
