package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class DeviceLogSourceTest {

  // ──────────────────────────────────────────────────────────────────────────
  // forPlatform / AutoDetect equality — the data class fix that keeps
  // SessionLogsPanel's `remember(source, raw)` cache stable across recompositions.
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `forPlatform null returns the AutoDetect singleton`() {
    assertSame(DeviceLogSource.AutoDetect, DeviceLogSource.forPlatform(null))
  }

  @Test
  fun `forPlatform same platform returns equal instances`() {
    val a = DeviceLogSource.forPlatform(TrailblazeDevicePlatform.ANDROID)
    val b = DeviceLogSource.forPlatform(TrailblazeDevicePlatform.ANDROID)
    // Identity may differ (factory creates new), but value equality MUST hold so
    // remember(source, ...) doesn't thrash on every recomposition.
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  @Test
  fun `forPlatform different platforms are not equal`() {
    val android = DeviceLogSource.forPlatform(TrailblazeDevicePlatform.ANDROID)
    val ios = DeviceLogSource.forPlatform(TrailblazeDevicePlatform.IOS)
    assertNotEquals(android, ios)
  }

  // ──────────────────────────────────────────────────────────────────────────
  // parse — end-to-end dispatch verification
  // ──────────────────────────────────────────────────────────────────────────

  @Test
  fun `parse returns truncated=false and lines=N for short input`() {
    val raw = """
      1772846521.000  1  1 D Tag : line one
      1772846521.500  1  1 I Tag : line two
    """.trimIndent()
    val parsed = DeviceLogSource.AutoDetect.parse(raw)
    assertEquals(2, parsed.totalRawLineCount)
    assertEquals(2, parsed.lines.size)
    assertEquals(false, parsed.truncated)
  }

  @Test
  fun `parse with ANDROID platform extracts Android timestamps and levels`() {
    val raw = "1772846521.234  5432  5432 E TestTag : oh no"
    val parsed = DeviceLogSource.forPlatform(TrailblazeDevicePlatform.ANDROID).parse(raw)
    assertEquals(1, parsed.lines.size)
    val line = parsed.lines.single()
    assertEquals(1772846521234L, line.epochMs)
    assertEquals(LogLevel.ERROR, line.level)
    // Timestamp display is relative; first line is at offset 0.
    assertEquals("0:00.000", line.timestampDisplay)
  }

  @Test
  fun `parse with IOS platform extracts iOS timestamps and level codes`() {
    // iOS compact format: timestamp, then the 2-letter type code (Er = Error), then the
    // process / message. After splitTimestamp strips the timestamp prefix, content starts
    // with "Er  MyApp[…]: …".
    val raw = "2026-03-10 14:23:45.678-0700  Er  MyApp[12345]: failure"
    val parsed = DeviceLogSource.forPlatform(TrailblazeDevicePlatform.IOS).parse(raw)
    assertEquals(1, parsed.lines.size)
    val line = parsed.lines.single()
    assertNotNull(line.epochMs)
    assertEquals(LogLevel.ERROR, line.level)
    // The iOS timestamp prefix should be stripped — content begins with the level code.
    assertTrue(
      line.content.startsWith("Er"),
      "expected stripped content to start with the iOS level code, was: '${line.content}'",
    )
  }

  @Test
  fun `parse with ANDROID platform returns null epoch for iOS-format lines`() {
    // Routing means we DON'T fall back to iOS parsing when Android is declared explicitly.
    val raw = "2026-03-10 14:23:45.678-0700  MyApp[12345]: hello"
    val parsed = DeviceLogSource.forPlatform(TrailblazeDevicePlatform.ANDROID).parse(raw)
    val line = parsed.lines.single()
    assertNull(line.epochMs)
  }

  @Test
  fun `AutoDetect handles a mixed log with both Android and iOS lines`() {
    // Reality check: in practice a single device.log is one platform, but AutoDetect
    // shouldn't crash when given mixed input — each line dispatches independently.
    val raw = """
      1772846521.000  1  1 D Tag : android line
      2026-03-10 14:23:45.000-0700  MyApp: ios line
    """.trimIndent()
    val parsed = DeviceLogSource.AutoDetect.parse(raw)
    assertEquals(2, parsed.lines.size)
    assertNotNull(parsed.lines[0].epochMs)
    assertNotNull(parsed.lines[1].epochMs)
  }

  @Test
  fun `parse marks truncated=true when input exceeds the display cap`() {
    // The cap is 10_000 lines (private). Generate just over it to verify the flag flips.
    val raw = (1..10_001).joinToString("\n") { "1772846521.000  1  1 D : line $it" }
    val parsed = DeviceLogSource.AutoDetect.parse(raw)
    assertEquals(10_001, parsed.totalRawLineCount)
    assertEquals(10_000, parsed.lines.size)
    assertEquals(true, parsed.truncated)
    // takeLast — the FIRST kept line should be #2 (line index 1 in the original).
    assertTrue(
      parsed.lines.first().raw.endsWith("line 2"),
      "expected oldest kept line to be 'line 2' (takeLast semantics), was: '${parsed.lines.first().raw}'",
    )
    // And the last kept line should be the most recent.
    assertTrue(
      parsed.lines.last().raw.endsWith("line 10001"),
      "expected newest line preserved, was: '${parsed.lines.last().raw}'",
    )
  }
}
