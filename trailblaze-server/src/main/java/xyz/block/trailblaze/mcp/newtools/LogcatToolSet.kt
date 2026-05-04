package xyz.block.trailblaze.mcp.newtools

import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.annotations.Tool
import ai.koog.agents.core.tools.reflect.ToolSet
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance
import xyz.block.trailblaze.mcp.McpToolProfile
import xyz.block.trailblaze.mcp.TrailblazeMcpSessionContext
import xyz.block.trailblaze.util.AndroidHostAdbUtils
import xyz.block.trailblaze.util.Console

/**
 * MCP tool for querying and asserting on Android logcat output during test sessions.
 *
 * Enables verification of internal app state (analytics events, UJ friction/frustration signals,
 * crash logs) that is not visible on the screen.
 *
 * Reads logcat via `adb logcat -d -t <timestamp>` scoped from tool-set construction time
 * forward, so matches don't include lines from prior test runs on the same device/emulator.
 *
 * Two actions:
 * - QUERY: Search logcat for lines matching a regex pattern. Returns matched lines.
 * - ASSERT: Check if a pattern exists in logcat. Returns pass/fail.
 */
@Suppress("unused")
class LogcatToolSet(
  private val sessionContext: TrailblazeMcpSessionContext? = null,
  private val deviceIdProvider: (() -> TrailblazeDeviceId?)? = null,
  /** Override for testing — when set, returns these lines instead of calling adb. */
  internal val logcatLinesProvider: (() -> List<String>?)? = null,
  /**
   * Test seam — when set, replaces the underlying `execAdbShellCommandWithTimeout` call inside
   * [readLogcatViaAdb]. Lets tests exercise the timeout-null path (which surfaces a different
   * error message from the no-device path) without spinning up a real device. Returning `null`
   * simulates the timeout; returning a list (including empty) simulates a successful read.
   */
  internal val readLogcatViaAdbOverride: ((TrailblazeDeviceId) -> List<String>?)? = null,
) : ToolSet {

  /** Timestamp of when this tool set was created, used to scope adb logcat reads. */
  private val sessionStartEpochSeconds: Long = System.currentTimeMillis() / 1000

  companion object {
    /**
     * Default number of matching lines returned for QUERY when the caller doesn't pass `limit`.
     * Kept small so results stay readable in the MCP chat UI; callers can raise it via `limit`.
     */
    private const val DEFAULT_QUERY_LIMIT = 50

    /**
     * Android log priority characters that appear in logcat line formats between the PID
     * columns and the tag: V(erbose), D(ebug), I(nfo), W(arn), E(rror), F(atal). Used to
     * anchor the tag-filter regex so we match the tag column rather than an incidental
     * substring. See https://developer.android.com/tools/logcat#filteringOutput.
     */
    private const val ANDROID_LOG_PRIORITY_CHARS = "VDIWEF"
  }

  enum class LogcatAction {
    /** Search logcat for lines matching a pattern. Returns matched lines. */
    QUERY,
    /** Assert that logcat contains a line matching a pattern. Returns pass/fail. */
    ASSERT,
  }

  @LLMDescription(
    """
    Query or assert on Android logcat output during a test session.

    logcat(action=QUERY, pattern="UJ_SIGNAL.*friction") → search logcat for matching lines
    logcat(action=ASSERT, pattern="http-status.*500", message="Frustration signal fired") → pass/fail
    logcat(action=QUERY, tag="UserJourney") → filter by log tag
    logcat(action=QUERY, pattern=".*", limit=10) → limit output

    Works with Android devices. Reads the live logcat buffer via adb.
    Supports regex patterns. Use tag parameter to filter by Android log tag.
    """
  )
  @Tool(McpToolProfile.TOOL_LOGCAT)
  suspend fun logcat(
    @LLMDescription("Action: QUERY to search, ASSERT to check pass/fail") action: LogcatAction,
    @LLMDescription("Regex pattern to search for (required)") pattern: String? = null,
    @LLMDescription("Filter by Android log tag (e.g., 'UserJourney', 'OkHttp')") tag: String? = null,
    @LLMDescription("Max lines to return for QUERY (default: " + DEFAULT_QUERY_LIMIT + ")") limit: Int? = null,
    @LLMDescription("Description of what you're asserting (for ASSERT action)") message: String? = null,
  ): String {
    if (pattern == null) {
      return LogcatResult(error = "pattern parameter is required for $action").toJson()
    }

    val deviceId = deviceIdProvider?.invoke() ?: sessionContext?.associatedDeviceId
    val lines = readLogcatLines()
    if (lines == null) {
      val errorMessage = if (deviceId == null && logcatLinesProvider == null) {
        // No device, no test override → user genuinely hasn't connected anything.
        "No logcat available. Connect an Android device first: device(action=ANDROID)"
      } else {
        // Device IS connected (or a test wired up the path) but the underlying adb call timed
        // out / failed. Don't tell the user to "connect a device" — they have one. Surface the
        // timeout symptom and point at where the actual diagnostic lives (the daemon log
        // captures the AndroidHostAdbUtils timeout/failure line that explains *why*).
        "Logcat read timed out for ${deviceId?.instanceId ?: "device"}. " +
          "The adb daemon or device may be wedged — check the Trailblaze daemon logs for details."
      }
      return LogcatResult(error = errorMessage).toJson()
    }

    // Filter by tag if specified (logcat format: "... D UserJourney: ..." or "... I OkHttp: ...")
    val tagFiltered =
      if (tag != null) {
        val tagPattern = Regex("""\s[$ANDROID_LOG_PRIORITY_CHARS]\s+${Regex.escape(tag)}\s*:""")
        lines.filter { tagPattern.containsMatchIn(it) }
      } else {
        lines
      }

    val regex =
      try {
        Regex(pattern)
      } catch (e: Exception) {
        return LogcatResult(error = "Invalid regex pattern: ${e.message}").toJson()
      }

    val matched = tagFiltered.filter { regex.containsMatchIn(it) }

    return when (action) {
      LogcatAction.QUERY -> {
        val effectiveLimit = limit ?: DEFAULT_QUERY_LIMIT
        LogcatResult(
          matches = matched.take(effectiveLimit),
          totalMatches = matched.size,
          message =
            if (matched.isEmpty()) "No lines matched pattern: $pattern"
            else "Found ${matched.size} matching lines",
        ).toJson()
      }
      LogcatAction.ASSERT -> {
        val passed = matched.isNotEmpty()
        LogcatResult(
          passed = passed,
          matchedLine = matched.firstOrNull(),
          totalMatches = matched.size,
          message =
            if (passed) "PASSED: ${message ?: "Pattern found in logcat"}"
            else "FAILED: ${message ?: "Pattern not found in logcat"} (pattern: $pattern)",
        ).toJson()
      }
    }
  }

  /**
   * Reads logcat lines via adb, scoped to recent entries.
   * In tests, [logcatLinesProvider] can supply lines directly.
   */
  private fun readLogcatLines(): List<String>? {
    // Test override — return injected lines without calling adb
    logcatLinesProvider?.let { return it.invoke() }

    val deviceId = deviceIdProvider?.invoke() ?: sessionContext?.associatedDeviceId
    if (deviceId != null) {
      return readLogcatViaAdb(deviceId)
    }

    return null
  }

  private fun readLogcatViaAdb(deviceId: TrailblazeDeviceId): List<String>? {
    // Test seam — bypass adb entirely when the override is wired up.
    readLogcatViaAdbOverride?.let { return it(deviceId) }
    return try {
      // Use -t to scope logcat to lines since session start, preventing stale matches
      // from prior test runs on the same device/emulator. `logcat -d` dumps the ring buffer and
      // exits, so a tight bound is safe — the timeout is here to fail fast when adbd or the
      // dadb transport is wedged rather than hanging the MCP tool call.
      val output = AndroidHostAdbUtils.execAdbShellCommandWithTimeout(
        deviceId = deviceId,
        args = listOf(
          "logcat", "-d",
          "-v", "epoch", "-v", "printable",
          "-t", "$sessionStartEpochSeconds.0",
        ),
        timeoutMs = 10_000L,
      )
      if (output == null) {
        // Tie the timeout-from-AndroidHostAdbUtils consequence ("returns null") back to the
        // caller's surface symptom (timeout error in the tool result) so a flaky adb daemon
        // shows up clearly in the logs instead of looking like a successful empty query.
        Console.log("[logcat] adb logcat -d timed out — surfacing timeout error to caller")
      }
      output?.lines()
    } catch (e: Exception) {
      Console.log("[logcat] Failed to read logcat via adb: ${e.message}")
      null
    }
  }
}

@Serializable
data class LogcatResult(
  val passed: Boolean? = null,
  val matches: List<String>? = null,
  val matchedLine: String? = null,
  val totalMatches: Int? = null,
  val message: String? = null,
  val error: String? = null,
) {
  fun toJson(): String = TrailblazeJsonInstance.encodeToString(serializer(), this)
}
