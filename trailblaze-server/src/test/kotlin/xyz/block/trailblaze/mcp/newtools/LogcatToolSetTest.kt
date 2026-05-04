package xyz.block.trailblaze.mcp.newtools

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [LogcatToolSet].
 *
 * Verifies that the logcat MCP tool correctly:
 * - Queries logcat output with regex pattern matching
 * - Asserts on logcat patterns with pass/fail results
 * - Filters by log tag
 * - Returns errors when no logcat source is available
 * - Handles empty logcat output gracefully
 * - Limits output to requested number of lines
 */
class LogcatToolSetTest {

  private fun createToolSet(lines: List<String>? = null): LogcatToolSet {
    return LogcatToolSet(
      logcatLinesProvider = if (lines != null) {{ lines }} else null,
    )
  }

  private fun createToolSetFromContent(content: String): LogcatToolSet {
    val lines = content.lines().filter { it.isNotBlank() }
    return createToolSet(lines)
  }

  private val sampleLogcat =
    """
    1776279402.584  5750  5750 D UserJourney: FRICTION: journey=onboarding signal=http-status-403
    1776279402.600  5750  5750 D UserJourney: Journey started: onboarding variant=us
    1776279402.700  5750  5905 I OkHttp: --> POST /1.0/onboarding/business/update
    1776279402.850  5750  5905 I OkHttp: <-- 403 /1.0/onboarding/business/update (150ms)
    1776279403.100  5750  5750 D UserJourney: FRUSTRATION: journey=onboarding signal=http-status-500
    1776279403.200  5750  5750 W Analytics: Event dispatched: screen_view home_screen
    1776279403.300  5750  5750 E CrashHandler: NullPointerException at LoginActivity.kt:42
    """
      .trimIndent()

  // ── QUERY action ──────────────────────────────────────────────────────────

  @Test
  fun `QUERY returns matching lines for regex pattern`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = "FRICTION.*http-status")
    val json = Json.parseToJsonElement(result).jsonObject

    val matches = json["matches"]!!.jsonArray
    assertTrue(matches.size >= 1)
    assertTrue(matches[0].jsonPrimitive.content.contains("http-status-403"))
  }

  @Test
  fun `QUERY filters by tag`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = ".*", tag = "Analytics")
    val json = Json.parseToJsonElement(result).jsonObject

    val matches = json["matches"]!!.jsonArray
    assertEquals(1, matches.size)
    assertTrue(matches[0].jsonPrimitive.content.contains("screen_view"))
  }

  @Test
  fun `QUERY respects limit parameter`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = ".*", limit = 2)
    val json = Json.parseToJsonElement(result).jsonObject

    val matches = json["matches"]!!.jsonArray
    assertEquals(2, matches.size)
  }

  @Test
  fun `QUERY returns empty matches when pattern not found`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = "NONEXISTENT_PATTERN")
    val json = Json.parseToJsonElement(result).jsonObject

    val matches = json["matches"]!!.jsonArray
    assertEquals(0, matches.size)
  }

  // ── ASSERT action ─────────────────────────────────────────────────────────

  @Test
  fun `ASSERT passes when pattern is found`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result =
      toolSet.logcat(
        action = LogcatToolSet.LogcatAction.ASSERT,
        pattern = "FRICTION.*http-status-403",
        message = "Friction signal emitted for 403",
      )
    val json = Json.parseToJsonElement(result).jsonObject

    assertEquals(true, json["passed"]!!.jsonPrimitive.boolean)
    assertNotNull(json["matchedLine"]?.jsonPrimitive?.content)
  }

  @Test
  fun `ASSERT fails when pattern is not found`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result =
      toolSet.logcat(
        action = LogcatToolSet.LogcatAction.ASSERT,
        pattern = "FRICTION.*http-status-999",
        message = "Friction signal emitted for 999",
      )
    val json = Json.parseToJsonElement(result).jsonObject

    assertEquals(false, json["passed"]!!.jsonPrimitive.boolean)
  }

  @Test
  fun `ASSERT filters by tag before matching`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result =
      toolSet.logcat(
        action = LogcatToolSet.LogcatAction.ASSERT,
        pattern = "FRICTION",
        tag = "Analytics", // FRICTION is on UserJourney tag, not Analytics
      )
    val json = Json.parseToJsonElement(result).jsonObject

    assertEquals(false, json["passed"]!!.jsonPrimitive.boolean)
  }

  // ── Error handling ────────────────────────────────────────────────────────

  @Test
  fun `returns error when no logcat source available`() = runTest {
    val toolSet = createToolSet(lines = null)

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = ".*")
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["error"]?.jsonPrimitive?.content)
  }

  @Test
  fun `handles empty logcat output`() = runTest {
    val toolSet = createToolSet(lines = emptyList())

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = ".*")
    val json = Json.parseToJsonElement(result).jsonObject

    val matches = json["matches"]!!.jsonArray
    assertEquals(0, matches.size)
  }

  @Test
  fun `QUERY requires pattern parameter`() = runTest {
    val toolSet = createToolSetFromContent(sampleLogcat)

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = null)
    val json = Json.parseToJsonElement(result).jsonObject

    assertNotNull(json["error"]?.jsonPrimitive?.content)
  }

  // ── adb timeout / failure path ───────────────────────────────────────────

  @Test
  fun `error message distinguishes timeout from no-device`() = runTest {
    // Wire up the test seam to simulate adb returning null (timeout / wedged daemon).
    // The tool should surface a different error than the "no device connected" case so
    // operators don't get told to "connect a device" when one is already connected.
    val deviceId = TrailblazeDeviceId(
      instanceId = "emulator-5554",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
    val toolSet = LogcatToolSet(
      deviceIdProvider = { deviceId },
      readLogcatViaAdbOverride = { null }, // simulate timeout
    )

    val result = toolSet.logcat(action = LogcatToolSet.LogcatAction.QUERY, pattern = ".*")
    val json = Json.parseToJsonElement(result).jsonObject
    val errorMessage = json["error"]?.jsonPrimitive?.content
    assertNotNull(errorMessage)
    assertTrue(
      errorMessage.contains("timed out", ignoreCase = true),
      "Expected timeout-specific error, got: $errorMessage",
    )
    assertTrue(
      errorMessage.contains("emulator-5554"),
      "Expected the device id in the error so operators can correlate, got: $errorMessage",
    )
    // The legacy "Connect an Android device" message should NOT appear here — the device IS
    // connected, the call just timed out. This is the entire point of the new error path.
    assertTrue(
      !errorMessage.contains("Connect an Android device"),
      "Should not tell user to connect a device when one is already connected: $errorMessage",
    )
  }

  @Test
  fun `successful adb path returns lines via override`() = runTest {
    // Round-trip: the override returns lines; the tool should treat them like real adb output.
    // Confirms the test seam doesn't accidentally short-circuit the filter/match pipeline.
    val deviceId = TrailblazeDeviceId(
      instanceId = "emulator-5554",
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )
    val toolSet = LogcatToolSet(
      deviceIdProvider = { deviceId },
      readLogcatViaAdbOverride = {
        listOf(
          "1776279402.584  5750  5750 D UserJourney: FRICTION: signal=http-status-403",
          "1776279402.700  5750  5905 I OkHttp: --> POST /1.0/onboarding",
        )
      },
    )

    val result = toolSet.logcat(
      action = LogcatToolSet.LogcatAction.ASSERT,
      pattern = "FRICTION.*http-status-403",
    )
    val json = Json.parseToJsonElement(result).jsonObject
    assertEquals(true, json["passed"]!!.jsonPrimitive.boolean)
  }
}
