package xyz.block.trailblaze.cli

import kotlinx.coroutines.delay
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.util.Console

/**
 * Polls the daemon until the Playwright browser is ready, emitting progress lines
 * so the user can see the download is advancing.
 *
 * Lives next to [CliMcpClient] rather than inside it because the install-progress
 * flow is Web-only — keeping it here lets the MCP client stay platform-agnostic,
 * and a future iOS/Android install flow can sit in its own sibling waiter.
 *
 * Callers (gated to the WEB platform) hit [awaitIfInstalling], which sniffs the
 * initial result for an install in progress and, if one is underway, drives the
 * `device/INFO` polling loop. [awaitReady] is the pure core (no `CliMcpClient`
 * instance required) exposed for unit tests that inject a fake `fetchStatus`.
 */
internal object PlaywrightInstallWaiter {

  // Polling knobs. Stall timeout fires when the progress string doesn't advance
  // for this long, so fast connections finish instantly and wedged installs fail
  // fast. The overall deadline is a hard ceiling for truly pathological cases.
  const val POLL_INTERVAL_MS = 2_000L
  const val STALL_TIMEOUT_MS = 90_000L
  const val INSTALL_DEADLINE_MS = 15 * 60 * 1_000L // 15 minutes

  /**
   * If the initial connect response reports an install in progress, polls the
   * daemon until Playwright is ready (emitting progress to [Console]). Callers
   * are expected to have already gated on [TrailblazeDevicePlatform.WEB].
   *
   * @return `null` if nothing needed polling or polling succeeded; an error
   *   message string if the install failed or timed out.
   */
  suspend fun awaitIfInstalling(
    initialResult: CliMcpClient.ToolResult,
    client: CliMcpClient,
  ): String? {
    if ("installing" !in initialResult.content.lowercase()) return null
    return awaitReady(
      initialContent = initialResult.content,
      fetchStatus = { client.callTool("device", mapOf("action" to "INFO")) },
      onProgress = { Console.info(it) },
    )
  }

  /**
   * Polls until the Playwright browser is ready.
   *
   * Termination:
   * - **Ready** → returns `null`. The daemon no longer reports "installing" and
   *   any `Driver status:` line advertises a non-error state.
   * - **Failed** → returns an error message. The daemon's `Driver status:` line
   *   advertises "failed" or "error". We only look inside that line (emitted by
   *   `DeviceManagerToolSet.appendDriverStatusIfPresent`) so that incidental
   *   occurrences of those substrings elsewhere in the payload — e.g. a tool
   *   name like `getLastError` in the available-tools summary — don't cause a
   *   false failure.
   * - **Wall-clock deadline** ([INSTALL_DEADLINE_MS]) → returns a timeout error.
   *   Sanity ceiling so a wedged daemon can't hang the CLI forever.
   * - **Stall timeout** ([STALL_TIMEOUT_MS]) → returns a timeout error. Triggers
   *   when the progress string hasn't changed for this duration — i.e. the
   *   install appears stuck even though the daemon is still responsive.
   *
   * Transient [CliMcpClient.ToolResult.isError] responses do not end the loop;
   * we keep polling until one of the terminal conditions fires.
   */
  suspend fun awaitReady(
    initialContent: String,
    fetchStatus: suspend () -> CliMcpClient.ToolResult,
    onProgress: (String) -> Unit,
    pollIntervalMs: Long = POLL_INTERVAL_MS,
    stallTimeoutMs: Long = STALL_TIMEOUT_MS,
    overallDeadlineMs: Long = INSTALL_DEADLINE_MS,
    now: () -> Long = { System.currentTimeMillis() },
  ): String? {
    // CLI users see one up-front notice, then the call blocks until ready, with
    // per-tick percentage lines printed as the daemon advances. The blocking shape
    // matters: previous behaviour returned with MCP-style "Call device(action=WEB)
    // to check status" trailers, which were dead-end advice for a CLI user. Showing
    // percentages keeps users from thinking the CLI is wedged.
    onProgress("Installing Playwright (this may take up to 2 minutes)...")

    val initialProgress = extractProgress(initialContent)
    if (initialProgress != null) onProgress("  $initialProgress")
    val startTime = now()
    var lastProgress = initialProgress ?: ""
    var lastProgressChangeAt = startTime

    while (true) {
      // Sample the clock once per iteration — avoids stall vs. deadline races when
      // calls to [now] advance the clock in tests (or drift under load in prod).
      val tick = now()
      val overallElapsed = tick - startTime
      val sinceLastProgress = tick - lastProgressChangeAt

      if (overallElapsed >= overallDeadlineMs) {
        return timeoutMessage(overallDeadlineMs)
      }
      if (sinceLastProgress >= stallTimeoutMs) {
        return stallMessage(stallTimeoutMs)
      }
      delay(pollIntervalMs)

      val statusResult = fetchStatus()
      // Transient INFO failure — keep polling until a terminal condition fires
      // rather than assuming success on a non-"installing" error string.
      if (statusResult.isError) continue

      val content = statusResult.content.lowercase()
      if ("installing" in content) {
        val progress = extractProgress(statusResult.content)
        if (progress != null && progress != lastProgress) {
          onProgress("  $progress")
          lastProgress = progress
          lastProgressChangeAt = now()
        }
        continue
      }

      // No longer installing. Only treat as failure when the daemon's structured
      // "Driver status:" line advertises one — matching substrings in the rest
      // of the payload (tool summaries, device header) is not a reliable signal.
      val driverStatusLine = extractDriverStatusLine(statusResult.content)
      if (driverStatusLine != null) {
        val lowered = driverStatusLine.lowercase()
        if ("failed" in lowered || "error" in lowered) {
          return "Playwright install failed: $driverStatusLine"
        }
      }
      onProgress("Done.")
      return null
    }
  }

  fun stallMessage(stallMs: Long): String =
    "Playwright browser install appears stuck: no progress for ${stallMs / 1000}s. " +
      "Check your network and try again, or pre-install via `npx playwright install chromium`."

  fun timeoutMessage(deadlineMs: Long): String =
    "Playwright browser install timed out after ${deadlineMs / 60_000}m. " +
      "Check your network and try again, or pre-install via `npx playwright install chromium`."

  /**
   * Extracts the progress portion from a Playwright installation status message.
   *
   * Input example: `"Playwright browser installing (12s elapsed, timeout in 888s): [42%] Downloading Chromium..."`
   *
   * Output: `"[42%] Downloading Chromium"`
   *
   * Anchored on the `installing (...):` preamble so unrelated `): ` sequences
   * elsewhere in the payload (e.g. a tool summary line) never hijack the match.
   *
   * Tolerates minor format drift:
   * - trailing dots/whitespace are stripped
   * - missing progress body returns null (caller shows the generic "installing" line)
   * - any message without the preamble returns null rather than throwing
   */
  fun extractProgress(content: String): String? {
    // Require the `installing (...):` preamble so we only match the Playwright
    // install line — not an unrelated `): ` segment that happened to appear earlier.
    val match = PROGRESS_REGEX.find(content) ?: return null
    val progress = match.groupValues[1].trim().trimEnd('.', ' ', '\t')
    return progress.ifEmpty { null }
  }

  private val PROGRESS_REGEX =
    Regex("""installing\s*\([^)]*\):\s+(.+)""", RegexOption.IGNORE_CASE)

  /**
   * Extracts the `Driver status: ...` line emitted by
   * `DeviceManagerToolSet.appendDriverStatusIfPresent`, or null if no such
   * line is present.
   *
   * The daemon is the single source of truth for this format — see
   * `DeviceManagerToolSet.appendDriverStatusIfPresent` in `trailblaze-server`.
   * It emits exactly one status line (newline-terminated), so this regex only
   * captures up to the end of the first matching line. If the daemon ever
   * starts emitting multi-line status blocks (e.g. status followed by a
   * stack trace), the continuation lines will be ignored here and the
   * function + regex will need to be updated to capture the full block.
   *
   * Binding failure detection to this structured marker keeps the CLI
   * classifier decoupled from free-text content in the rest of the INFO
   * response (tool summaries, device header, etc.).
   */
  fun extractDriverStatusLine(content: String): String? {
    val match = DRIVER_STATUS_REGEX.find(content) ?: return null
    return match.groupValues[1].trim().ifEmpty { null }
  }

  private val DRIVER_STATUS_REGEX =
    Regex("""(?m)^Driver status:\s*(.+)$""", RegexOption.IGNORE_CASE)
}
