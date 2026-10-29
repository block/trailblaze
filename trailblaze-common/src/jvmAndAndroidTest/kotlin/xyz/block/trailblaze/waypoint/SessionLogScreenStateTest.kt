package xyz.block.trailblaze.waypoint

import java.io.File
import kotlinx.datetime.Instant
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeJson

/**
 * Tests for [SessionLogScreenState]'s pre-filter helpers and the timestamp-based
 * ordering used by `waypoint capture-example` and `waypoint validate --session`.
 *
 * These helpers gate the auto-search candidate set:
 *  - [SessionLogScreenState.hasScreenshot] decides which logs are even eligible.
 *  - [SessionLogScreenState.readTimestamp] is the sort key for "most recent match."
 *  - [SessionLogScreenState.listScreenStateLogs] orders the per-step log set.
 *
 * The previous implementation sorted by FILENAME, which silently mis-orders device-farm /
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
    // This is the load-bearing fix: device-farm logs use hex-hash filenames whose alphabetical
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
  fun `listScreenStateLogs compares timestamps as instants, not strings`() {
    // As strings, "10:00:00.500Z" < "10:00:00Z" ('.' sorts before 'Z'), which would put the
    // later capture first.
    val sessionDir = newTempDir()
    writeLog(sessionDir, "aaa_AgentDriverLog.json", """{"timestamp":"2026-05-08T10:00:00.500Z"}""")
    writeLog(sessionDir, "bbb_AgentDriverLog.json", """{"timestamp":"2026-05-08T10:00:00Z"}""")

    val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)
    assertEquals(listOf("bbb_AgentDriverLog.json", "aaa_AgentDriverLog.json"), logs.map { it.name })
  }

  @Test
  fun `listScreenStateLogs orders a device-stamped capture on the host clock`() {
    // The device's clock runs 60s ahead: its tool log, stamped 10:01:00 by the device, reached the
    // host at 10:00:00.100. Its driver capture stamped 10:01:10 was taken at about 10:00:10 host
    // time, before the host-stamped LLM request at 10:00:30, which is the session's last screen.
    val sessionDir = newTempDir()
    writeLog(
      sessionDir,
      "001_TrailblazeToolLog.json",
      """{"class":"xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog",""" +
        """"trailblazeTool":{"toolName":"tap","raw":{}},"toolName":"tap","successful":true,""" +
        """"traceId":"tool-1","durationMs":0,"session":"s","timestamp":"2026-05-08T10:01:00Z",""" +
        """"clock":"device","hostReceivedAt":"2026-05-08T10:00:00.100Z"}""",
    )
    writeLog(
      sessionDir,
      "aaa_AgentDriverLog.json",
      """{"timestamp":"2026-05-08T10:01:10Z","clock":"device","hostReceivedAt":"2026-05-08T10:00:10.200Z"}""",
    )
    writeLog(sessionDir, "bbb_TrailblazeLlmRequestLog.json", """{"timestamp":"2026-05-08T10:00:30Z"}""")

    val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)

    assertEquals(listOf("aaa_AgentDriverLog.json", "bbb_TrailblazeLlmRequestLog.json"), logs.map { it.name })
  }

  @Test
  fun `listScreenStateLogs reads only a log's own clock, not one nested in its body`() {
    // The LLM request is host-stamped; only a tool argument inside it says "device". Shifting it by
    // the device's -60s offset would put it before the capture and make the capture the last screen.
    val sessionDir = newTempDir()
    writeAnchoredToolLog(sessionDir)
    writeLog(
      sessionDir,
      "aaa_AgentDriverLog.json",
      """{"timestamp":"2026-05-08T10:01:10Z","clock":"device","hostReceivedAt":"2026-05-08T10:00:10.200Z"}""",
    )
    writeLog(
      sessionDir,
      "bbb_TrailblazeLlmRequestLog.json",
      """{"actions":[{"name":"tap","args":{"clock":"device","timestamp":"2026-05-08T09:00:00Z"}}],""" +
        """"timestamp":"2026-05-08T10:00:30Z","clock":"host"}""",
    )

    val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)

    assertEquals(listOf("aaa_AgentDriverLog.json", "bbb_TrailblazeLlmRequestLog.json"), logs.map { it.name })
  }

  @Test
  fun `listScreenStateLogsOnHostClock puts each session's captures on the host clock for comparing sessions`() {
    // Session A's device runs 60s ahead; its capture stamped 10:01:10 was taken at 10:00:10 host
    // time (shifted by the -59.9s offset its tool log gives), before session B's host-stamped capture at 10:00:30.
    val sessionA = newTempDir()
    writeAnchoredToolLog(sessionA)
    writeLog(
      sessionA,
      "aaa_AgentDriverLog.json",
      """{"timestamp":"2026-05-08T10:01:10Z","clock":"device","hostReceivedAt":"2026-05-08T10:00:10.200Z"}""",
    )
    val sessionB = newTempDir()
    writeLog(sessionB, "bbb_TrailblazeLlmRequestLog.json", """{"timestamp":"2026-05-08T10:00:30Z"}""")

    val a = SessionLogScreenState.listScreenStateLogsOnHostClock(sessionA).single().second!!
    val b = SessionLogScreenState.listScreenStateLogsOnHostClock(sessionB).single().second!!

    assertEquals(Instant.parse("2026-05-08T10:00:10.100Z"), a)
    assertTrue(a < b)
  }

  @Test
  fun `listScreenStateLogs keeps a device stamp as written when the session has no anchor to shift it by`() {
    // Device logs pulled off the device after a run never reached the host, so nothing says how far
    // the device's clock is off; the stamps are compared as written.
    val sessionDir = newTempDir()
    writeLog(sessionDir, "aaa_AgentDriverLog.json", """{"timestamp":"2026-05-08T10:01:10Z","clock":"device"}""")
    writeLog(sessionDir, "bbb_TrailblazeLlmRequestLog.json", """{"timestamp":"2026-05-08T10:00:30Z"}""")

    val logs = SessionLogScreenState.listScreenStateLogs(sessionDir)

    assertEquals(listOf("bbb_TrailblazeLlmRequestLog.json", "aaa_AgentDriverLog.json"), logs.map { it.name })
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
  // loadStep — device classifier cascade (label > session device info > none)
  // ==========================================================================

  @Test
  fun `loadStep reads deviceClassifiers from trailblazeDeviceInfo when no explicit label`() {
    // Middle tier of the cascade: a capture-example output carries the projected session
    // `trailblazeDeviceInfo` but no top-level `deviceClassifier` label. loadStep must surface those
    // classifiers on the ScreenState so WaypointMatcher resolves the right per-classifier block.
    val deviceInfo = TrailblazeDeviceInfo(
      trailblazeDeviceId = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID),
      trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
      widthPixels = 1080,
      heightPixels = 1920,
      classifiers = listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
    )
    val deviceInfoJson = TrailblazeJson.defaultWithoutToolsInstance
      .encodeToString(TrailblazeDeviceInfo.serializer(), deviceInfo)
    val log = newTempDir().let {
      writeLog(it, "001_TrailblazeLlmRequestLog.json", """{"deviceWidth":1080,"deviceHeight":1920,"trailblazeDeviceInfo":$deviceInfoJson}""")
    }

    val screen = SessionLogScreenState.loadStep(log)

    assertEquals(
      listOf(TrailblazeDeviceClassifier("android"), TrailblazeDeviceClassifier("phone")),
      screen.deviceClassifiers,
      "loadStep should surface the session's device classifiers when no explicit label is present",
    )
  }

  @Test
  fun `loadStep prefers the explicit deviceClassifier label over session device info`() {
    val log = newTempDir().let {
      writeLog(it, "001_TrailblazeLlmRequestLog.json", """{"deviceWidth":1080,"deviceHeight":1920,"deviceClassifier":"android-tablet"}""")
    }

    val screen = SessionLogScreenState.loadStep(log)

    assertEquals(listOf(TrailblazeDeviceClassifier("android-tablet")), screen.deviceClassifiers)
  }

  // ==========================================================================
  // readSessionContext + the session-device fallback
  // ==========================================================================

  @Test
  fun `readSessionContext returns the app id and device a session recorded at start`() {
    val sessionDir = newTempDir()
    writeLog(sessionDir, "001_TrailblazeSessionStatusChangeLog.json", startedStatusLog(appId = "com.example.app", device = ipad))
    // A later status log (the end-of-session one) carries neither; it must not mask the start.
    writeLog(
      sessionDir,
      "099_TrailblazeSessionStatusChangeLog.json",
      """{"sessionStatus":{"class":"Ended.Succeeded","durationMs":1},"timestamp":"2026-05-08T10:09:00Z"}""",
    )

    val context = SessionLogScreenState.readSessionContext(sessionDir)

    assertEquals("com.example.app", context?.appId)
    assertEquals(TrailblazeDevicePlatform.IOS, context?.deviceInfo?.platform)
    assertEquals(ipad.classifiers, context?.deviceInfo?.classifiers)
  }

  @Test
  fun `readSessionContext drops the start device for a multi-device session`() {
    // After a handover, captures come from another device; the start device would be wrong for them.
    val sessionDir = newTempDir()
    writeLog(sessionDir, "001_TrailblazeSessionStatusChangeLog.json", startedStatusLog(appId = "com.example.app", device = ipad))
    writeLog(sessionDir, "002_TrailblazeToolLog.json", """{"toolName":"tap","deviceName":"buyer","timestamp":"2026-05-08T10:01:00Z"}""")

    val context = SessionLogScreenState.readSessionContext(sessionDir)

    assertEquals("com.example.app", context?.appId)
    assertNull(context?.deviceInfo)
  }

  @Test
  fun `readSessionContext keeps the start device when only a tool's own args name a device`() {
    // Only the log's top-level `deviceName` marks a multi-device session; a tool can take one as an argument.
    val sessionDir = newTempDir()
    writeLog(sessionDir, "001_TrailblazeSessionStatusChangeLog.json", startedStatusLog(appId = "com.example.app", device = ipad))
    writeLog(
      sessionDir,
      "002_TrailblazeToolLog.json",
      """{"toolName":"pickDevice","trailblazeTool":{"deviceName":"buyer"},"timestamp":"2026-05-08T10:01:00Z"}""",
    )

    val context = SessionLogScreenState.readSessionContext(sessionDir)

    assertEquals(TrailblazeDevicePlatform.IOS, context?.deviceInfo?.platform)
  }

  @Test
  fun `readSessionContext returns null when the session has no start log`() {
    val sessionDir = newTempDir()
    writeLog(sessionDir, "001_AgentDriverLog.json", """{"timestamp":"2026-05-08T10:00:00Z"}""")

    assertNull(SessionLogScreenState.readSessionContext(sessionDir))
  }

  @Test
  fun `loadStep takes only the platform from the session device when the log has none`() {
    // Driver logs carry no device fields. Without the session's device, an iOS screen
    // was loaded as ANDROID and never resolved a waypoint's `ios:` block.
    val log = writeLog(newTempDir(), "001_AgentDriverLog.json", """{"deviceWidth":2360,"deviceHeight":1640}""")

    val withoutSession = SessionLogScreenState.loadStep(log)
    val withSession = SessionLogScreenState.loadStep(log, sessionDevice = ipad)

    assertEquals(TrailblazeDevicePlatform.ANDROID, withoutSession.trailblazeDevicePlatform)
    assertEquals(TrailblazeDevicePlatform.IOS, withSession.trailblazeDevicePlatform)
    // Classifiers stay unset: a custom hardware classifier without an `android` parent would
    // hide the `android:` blocks that match those screens today.
    assertEquals(emptyList(), withSession.deviceClassifiers)
  }

  private val ipad = TrailblazeDeviceInfo(
    trailblazeDeviceId = TrailblazeDeviceId("SIM-1", TrailblazeDevicePlatform.IOS),
    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
    widthPixels = 2360,
    heightPixels = 1640,
    classifiers = listOf(TrailblazeDeviceClassifier("ios"), TrailblazeDeviceClassifier("ipad")),
  )

  /** Shaped like a real `Started` status log: the fields this reader needs plus unrelated ones. */
  private fun startedStatusLog(appId: String, device: TrailblazeDeviceInfo): String {
    val deviceJson = TrailblazeJson.defaultWithoutToolsInstance.encodeToString(TrailblazeDeviceInfo.serializer(), device)
    return """{"class":"xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog",""" +
      """"sessionStatus":{"class":"Started","trailFilePath":"trail.yaml","hasRecordedSteps":true,""" +
      """"trailblazeDeviceInfo":$deviceJson,"targetAppInfo":{"appId":"$appId","versionName":"1.0"}},""" +
      """"session":"s1","timestamp":"2026-05-08T10:00:00Z"}"""
  }

  // ==========================================================================
  // Test infrastructure.
  // ==========================================================================

  /** A tool log from a device 60s ahead of the host: stamped 10:01:00, received at 10:00:00.100. */
  private fun writeAnchoredToolLog(sessionDir: File) = writeLog(
    sessionDir,
    "001_TrailblazeToolLog.json",
    """{"class":"xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog",""" +
      """"trailblazeTool":{"toolName":"tap","raw":{}},"toolName":"tap","successful":true,""" +
      """"traceId":"tool-1","durationMs":0,"session":"s","timestamp":"2026-05-08T10:01:00Z",""" +
      """"clock":"device","hostReceivedAt":"2026-05-08T10:00:00.100Z"}""",
  )

  private fun newTempDir(): File {
    val dir = createTempDirectory(prefix = "sls-test-").toFile()
    tempDirs += dir
    return dir
  }

  private fun writeLog(sessionDir: File, name: String, json: String): File =
    File(sessionDir, name).apply { writeText(json) }

  @Test
  fun `a capture known to have lost nodes still says so after encode and decode`() {
    val partial = SessionLogScreenState.decode("""{"deviceWidth":1080,"deviceHeight":2400,"droppedNodeFetches":4}""")
    assertEquals(4, partial.droppedNodeFetches)
    assertEquals(4, SessionLogScreenState.decode(SessionLogScreenState.encode(partial)).droppedNodeFetches)
    // A log that never recorded it stays unknown, not complete.
    assertNull(SessionLogScreenState.decode("""{"deviceWidth":1080,"deviceHeight":2400}""").droppedNodeFetches)
  }
}
