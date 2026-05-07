package xyz.block.trailblaze.waypoint

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SessionLogScreenState]'s pre-filter helpers and the timestamp-based
 * ordering used by `waypoint capture-example` and `waypoint validate --session`.
 *
 * These helpers gate the auto-search candidate set:
 *  - [SessionLogScreenState.hasScreenshot] decides which logs are even eligible.
 *  - [SessionLogScreenState.readTimestamp] is the sort key for "most recent match."
 *  - [SessionLogScreenState.listScreenStateLogs] orders the per-step log set.
 *
 * The previous implementation sorted by FILENAME, which silently mis-orders ATF /
 * accessibility-driver logs that use hex-hash names (e.g. `7d50895f_AgentDriverLog.json`)
 * — so the auto-search could pick an OLDER step's screenshot. The fix moved both
 * `listScreenStateLogs` and `findMatchingLog` to sort by the JSON `timestamp` field;
 * these tests pin the chronological-vs-filename behavior so it can't silently regress.
 */
class SessionLogScreenStateTest {

  private val tempDirs = mutableListOf<File>()

  @AfterTest
  fun cleanup() {
    tempDirs.forEach { it.deleteRecursively() }
    tempDirs.clear()
  }

  // ==========================================================================
  // hasScreenshot
  // ==========================================================================

  @Test
  fun `hasScreenshot returns true when screenshotFile is set and the file exists with non-zero size`() {
    val sessionDir = newTempDir()
    val screenshot = File(sessionDir, "shot.webp").apply { writeBytes(byteArrayOf(0x52, 0x49, 0x46, 0x46)) }
    val log = writeLog(
      sessionDir,
      "001_TrailblazeLlmRequestLog.json",
      """{"screenshotFile":"shot.webp","timestamp":"2026-05-08T10:00:00Z"}""",
    )
    assertTrue(SessionLogScreenState.hasScreenshot(log))
    // Sanity that we set this up correctly:
    assertTrue(screenshot.length() > 0)
  }

  @Test
  fun `hasScreenshot returns false when screenshotFile is null in the JSON`() {
    val sessionDir = newTempDir()
    val log = writeLog(
      sessionDir,
      "001_TrailblazeLlmRequestLog.json",
      """{"screenshotFile":null,"timestamp":"2026-05-08T10:00:00Z"}""",
    )
    assertFalse(SessionLogScreenState.hasScreenshot(log))
  }

  @Test
  fun `hasScreenshot returns false when screenshotFile is set but the referenced file is missing on disk`() {
    // Real bug we hit: a log carries `screenshotFile: "x.png"` but the binary write failed
    // or was pruned. Pre-filter must catch this so the matcher doesn't waste cycles on a
    // candidate that capture-example would then fail to write.
    val sessionDir = newTempDir()
    val log = writeLog(
      sessionDir,
      "001_TrailblazeLlmRequestLog.json",
      """{"screenshotFile":"never-written.webp","timestamp":"2026-05-08T10:00:00Z"}""",
    )
    assertFalse(
      SessionLogScreenState.hasScreenshot(log),
      "JSON references an image that doesn't exist on disk — should NOT be eligible",
    )
  }

  @Test
  fun `hasScreenshot returns false when screenshotFile exists but is zero bytes`() {
    // Edge case: framework partially wrote the screenshot file, then crashed. An empty
    // file is unusable as a captured screen.
    val sessionDir = newTempDir()
    File(sessionDir, "empty.webp").createNewFile()
    val log = writeLog(
      sessionDir,
      "001_TrailblazeLlmRequestLog.json",
      """{"screenshotFile":"empty.webp","timestamp":"2026-05-08T10:00:00Z"}""",
    )
    assertFalse(
      SessionLogScreenState.hasScreenshot(log),
      "zero-byte screenshot is not a usable capture",
    )
  }

  @Test
  fun `hasScreenshot returns false on malformed JSON instead of throwing`() {
    val sessionDir = newTempDir()
    val log = File(sessionDir, "001_TrailblazeLlmRequestLog.json").apply {
      writeText("this is not json at all { {")
    }
    assertFalse(
      SessionLogScreenState.hasScreenshot(log),
      "must swallow parse errors so a single corrupt file doesn't abort the whole sweep",
    )
  }

  // ==========================================================================
  // readTimestamp
  // ==========================================================================

  @Test
  fun `readTimestamp extracts the JSON timestamp field as an ISO-8601 string`() {
    val log = newTempDir().let {
      writeLog(it, "001_log.json", """{"foo":"bar","timestamp":"2026-05-08T10:23:45.678Z"}""")
    }
    assertEquals("2026-05-08T10:23:45.678Z", SessionLogScreenState.readTimestamp(log))
  }

  @Test
  fun `readTimestamp returns null when the timestamp field is absent`() {
    val log = newTempDir().let {
      writeLog(it, "001_log.json", """{"screenshotFile":"x.webp"}""")
    }
    assertNull(SessionLogScreenState.readTimestamp(log))
  }

  @Test
  fun `readTimestamp returns null on malformed JSON instead of throwing`() {
    val log = newTempDir().let { dir ->
      File(dir, "001_log.json").apply { writeText("not json {") }
    }
    assertNull(
      SessionLogScreenState.readTimestamp(log),
      "must swallow parse errors so missing-timestamp falls back to filename ordering, not crash",
    )
  }

  @Test
  fun `readTimestamp returns null when the file does not exist`() {
    val nonexistent = File(newTempDir(), "not-here.json")
    assertNull(SessionLogScreenState.readTimestamp(nonexistent))
  }

  // ==========================================================================
  // listScreenStateLogs — chronological ordering
  // ==========================================================================

  @Test
  fun `listScreenStateLogs orders by JSON timestamp NOT by filename`() {
    // This is the load-bearing fix: ATF logs use hex-hash filenames whose alphabetical
    // sort doesn't match emit order. If we sorted by filename here, capture-example's
    // "default last step" pick would be wrong.
    val sessionDir = newTempDir()
    // Filename-alphabetical order: aaa, bbb, ccc
    // Timestamp order:             ccc (10:00) < aaa (11:00) < bbb (12:00)
    writeLog(sessionDir, "aaa_AgentDriverLog.json", """{"timestamp":"2026-05-08T11:00:00Z"}""")
    writeLog(sessionDir, "bbb_AgentDriverLog.json", """{"timestamp":"2026-05-08T12:00:00Z"}""")
    writeLog(sessionDir, "ccc_AgentDriverLog.json", """{"timestamp":"2026-05-08T10:00:00Z"}""")

    val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)
    assertEquals(
      listOf("ccc_AgentDriverLog.json", "aaa_AgentDriverLog.json", "bbb_AgentDriverLog.json"),
      logs.map { it.name },
      "logs must order chronologically by JSON timestamp regardless of hex-hash filename order",
    )
  }

  @Test
  fun `listScreenStateLogs falls back to filename order when timestamps are missing`() {
    val sessionDir = newTempDir()
    writeLog(sessionDir, "002_log.json", """{"screenshotFile":"x.webp"}""")
    writeLog(sessionDir, "001_log_AgentDriverLog.json", """{"screenshotFile":"x.webp"}""")
    writeLog(sessionDir, "003_log_TrailblazeSnapshotLog.json", """{"screenshotFile":"x.webp"}""")

    val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)
    // Files matching the screen-state suffix list — note "002_log.json" doesn't match
    // any of _AgentDriverLog, _TrailblazeSnapshotLog, _TrailblazeLlmRequestLog so it's
    // filtered out. The remaining two should sort by name (since no timestamps).
    assertEquals(
      listOf("001_log_AgentDriverLog.json", "003_log_TrailblazeSnapshotLog.json"),
      logs.map { it.name },
      "tiebreaker on missing timestamps is filename so the sort stays total and deterministic",
    )
  }

  @Test
  fun `listScreenStateLogs accepts AgentDriverLog SnapshotLog and LlmRequestLog suffixes`() {
    val sessionDir = newTempDir()
    writeLog(sessionDir, "001_AgentDriverLog.json", """{"timestamp":"2026-05-08T10:00:00Z"}""")
    writeLog(sessionDir, "002_TrailblazeSnapshotLog.json", """{"timestamp":"2026-05-08T10:01:00Z"}""")
    writeLog(sessionDir, "003_TrailblazeLlmRequestLog.json", """{"timestamp":"2026-05-08T10:02:00Z"}""")
    writeLog(sessionDir, "004_McpToolCallRequestLog.json", """{"timestamp":"2026-05-08T10:03:00Z"}""")

    val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)
    assertEquals(
      listOf(
        "001_AgentDriverLog.json",
        "002_TrailblazeSnapshotLog.json",
        "003_TrailblazeLlmRequestLog.json",
      ),
      logs.map { it.name },
      "non-screen-state log types like McpToolCallRequestLog must be excluded",
    )
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  private fun newTempDir(): File {
    val dir = createTempDirectory(prefix = "sls-test-").toFile()
    tempDirs += dir
    return dir
  }

  private fun writeLog(sessionDir: File, name: String, json: String): File =
    File(sessionDir, name).apply { writeText(json) }
}
