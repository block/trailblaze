package xyz.block.trailblaze.logs.client

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class TrailblazeLoggerScreenshotFileNameTest {

  @Test
  fun shortSessionIdRoundTripsUntouched() {
    val sessionId = "2026_05_14_12_30_45_some_short_test_name_1234"
    val epochMs = 1_715_000_000_000L
    val filename = TrailblazeLogger.buildScreenshotFileName(sessionId, epochMs, "png")
    assertEquals("${sessionId}_$epochMs.png", filename)
  }

  @Test
  fun overflowFallsBackToSha8Prefix() {
    // 261-byte filename mirrors the real case_4844290 incident — anything that pushes the
    // natural <sessionId>_<epochMs>.<ext> past 255 bytes must use the hash fallback.
    val sessionId = "a".repeat(243)
    val epochMs = 1_715_000_000_000L
    val natural = "${sessionId}_$epochMs.png"
    assertTrue(natural.toByteArray(Charsets.UTF_8).size > 255, "precondition: natural overflows")

    val filename = TrailblazeLogger.buildScreenshotFileName(sessionId, epochMs, "png")
    assertNotEquals(natural, filename)
    // Hash fallback shape: <8-hex>_<epoch>.<ext>
    assertTrue(
      Regex("^[0-9a-f]{8}_${epochMs}\\.png$").matches(filename),
      "expected sha8 hash fallback, got: $filename",
    )
  }

  @Test
  fun overflowFallbackStaysUnderNameMax() {
    // Pathological 4096-byte session id — fallback must still fit under NAME_MAX.
    val sessionId = "x".repeat(4096)
    val epochMs = 1_715_000_000_000L
    val filename = TrailblazeLogger.buildScreenshotFileName(sessionId, epochMs, "webp")
    assertTrue(
      filename.toByteArray(Charsets.UTF_8).size <= 255,
      "fallback filename ${filename.toByteArray(Charsets.UTF_8).size} bytes exceeds NAME_MAX",
    )
  }

  @Test
  fun overflowFallbackIsDeterministic() {
    val sessionId = "z".repeat(300)
    val ext = "png"
    val a = TrailblazeLogger.buildScreenshotFileName(sessionId, epochMs = 1_715_000_000_000L, ext)
    val b = TrailblazeLogger.buildScreenshotFileName(sessionId, epochMs = 1_715_000_000_999L, ext)
    // Same session → same hash prefix; only the epoch suffix differs.
    val prefixA = a.substringBefore("_")
    val prefixB = b.substringBefore("_")
    assertEquals(prefixA, prefixB)
  }
}
