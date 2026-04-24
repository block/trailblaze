package xyz.block.trailblaze.cli

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [PlaywrightInstallWaiter].
 *
 * Covers both the pure progress-string parser and the stateful polling loop
 * via an injected `fetchStatus` lambda, since wiring in a real daemon is
 * not feasible in unit tests.
 */
class PlaywrightInstallWaiterTest {

  // ── extractProgress ──────────────────────────────────────────────────────

  @Test
  fun `extractProgress pulls progress after the parenthetical block`() {
    val input =
      "Playwright browser installing (12s elapsed, timeout in 888s): [42%] Downloading Chromium"
    assertEquals("[42%] Downloading Chromium", PlaywrightInstallWaiter.extractProgress(input))
  }

  @Test
  fun `extractProgress strips trailing dots`() {
    val input =
      "Playwright browser installing (5s elapsed, timeout in 895s): Extracting files..."
    assertEquals("Extracting files", PlaywrightInstallWaiter.extractProgress(input))
  }

  @Test
  fun `extractProgress tolerates multi-space separator`() {
    // Defensive — if the daemon ever emits extra spacing, we still parse.
    val input = "Playwright browser installing (1s elapsed):    [1%] Downloading"
    assertEquals("[1%] Downloading", PlaywrightInstallWaiter.extractProgress(input))
  }

  @Test
  fun `extractProgress returns null when the separator is absent`() {
    val input = "Driver is ready"
    assertNull(PlaywrightInstallWaiter.extractProgress(input))
  }

  @Test
  fun `extractProgress returns null for empty body`() {
    val input = "Playwright browser installing (0s elapsed): "
    assertNull(PlaywrightInstallWaiter.extractProgress(input))
  }

  @Test
  fun `extractProgress ignores unrelated 'closing paren colon' earlier in the payload`() {
    // Guards the anchored regex: an unrelated `): ` appearing before the install
    // preamble (e.g. a tool summary line) must not hijack the match.
    val input = """
      Connected device:
        Instance ID: playwright-chromium
        Tools summary (all): tapOnElement, swipe, verify
      Playwright browser installing (3s elapsed, timeout in 897s): [25%] Downloading Chromium
    """.trimIndent()
    assertEquals("[25%] Downloading Chromium", PlaywrightInstallWaiter.extractProgress(input))
  }

  // ── extractDriverStatusLine ──────────────────────────────────────────────

  @Test
  fun `extractDriverStatusLine returns the status body`() {
    val input = """
      Connected device:
        Instance ID: playwright-chromium
        Platform: Web

      Driver status: Playwright browser installing (12s elapsed): [42%] Downloading Chromium
    """.trimIndent()
    assertEquals(
      "Playwright browser installing (12s elapsed): [42%] Downloading Chromium",
      PlaywrightInstallWaiter.extractDriverStatusLine(input),
    )
  }

  @Test
  fun `extractDriverStatusLine returns null when no status line is present`() {
    val input = """
      Connected device:
        Instance ID: playwright-chromium
        Platform: Web
    """.trimIndent()
    assertNull(PlaywrightInstallWaiter.extractDriverStatusLine(input))
  }

  @Test
  fun `extractDriverStatusLine does not match incidental error keywords elsewhere`() {
    // A tool summary mentioning "error" in a tool name must NOT be picked up as
    // a driver status line — that was the regression we're guarding against.
    val input = """
      Connected device:
        Instance ID: playwright-chromium
        Platform: Web

      Available web tools: getLastError, openUrl, tapOnElement
    """.trimIndent()
    assertNull(PlaywrightInstallWaiter.extractDriverStatusLine(input))
  }

  // ── awaitReady: happy path ───────────────────────────────────────────────

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `awaitReady returns null when status transitions to ready`() = runTest {
    val responses = ArrayDeque<CliMcpClient.ToolResult>()
    responses.addLast(infoResult("Playwright browser installing (2s elapsed): [50%] Downloading"))
    responses.addLast(infoResult("Playwright browser installing (4s elapsed): [90%] Downloading"))
    responses.addLast(infoResult("Connected device:\n  Instance ID: playwright-chromium\n  Platform: Web"))

    val progressLines = mutableListOf<String>()
    val result = PlaywrightInstallWaiter.awaitReady(
      initialContent = "Playwright browser installing (0s elapsed): [10%] Downloading",
      fetchStatus = { responses.removeFirst() },
      onProgress = { progressLines.add(it) },
      pollIntervalMs = 0L,
      now = fakeClock(),
    )

    assertNull(result, "Expected success; got: $result")
    assertTrue(progressLines.any { it.contains("[10%] Downloading") }, "Expected initial progress")
    assertTrue(progressLines.any { it.contains("[50%] Downloading") }, "Expected mid-progress update")
    assertTrue(progressLines.any { it.contains("[90%] Downloading") }, "Expected late-progress update")
    assertTrue(progressLines.last().contains("ready"), "Expected final ready message")
  }

  // ── awaitReady: failure surfaces ─────────────────────────────────────────

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `awaitReady returns error when Driver status line reports failed`() = runTest {
    val responses = ArrayDeque<CliMcpClient.ToolResult>()
    responses.addLast(
      infoResult(
        """
          Connected device:
            Instance ID: playwright-chromium
            Platform: Web

          Driver status: Playwright browser installation failed: connection refused
        """.trimIndent(),
      ),
    )

    val result = PlaywrightInstallWaiter.awaitReady(
      initialContent = "Playwright browser installing (0s elapsed): starting",
      fetchStatus = { responses.removeFirst() },
      onProgress = { },
      pollIntervalMs = 0L,
      now = fakeClock(),
    )

    assertNotNull(result)
    assertTrue(
      result.contains("install failed", ignoreCase = true),
      "Expected failure message; got: $result",
    )
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `awaitReady returns error when Driver status line contains error keyword`() = runTest {
    val responses = ArrayDeque<CliMcpClient.ToolResult>()
    responses.addLast(
      infoResult(
        """
          Connected device:
            Instance ID: playwright-chromium
            Platform: Web

          Driver status: Driver initialization error: missing dependencies
        """.trimIndent(),
      ),
    )

    val result = PlaywrightInstallWaiter.awaitReady(
      initialContent = "Playwright browser installing (0s elapsed): ",
      fetchStatus = { responses.removeFirst() },
      onProgress = { },
      pollIntervalMs = 0L,
      now = fakeClock(),
    )

    assertNotNull(result)
    assertTrue(result.contains("failed", ignoreCase = true))
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `awaitReady treats payload as ready when 'error' appears only outside Driver status`() = runTest {
    // Regression guard: once install completes, an INFO response containing an
    // incidental "error"/"failed" substring (e.g. a tool name like getLastError)
    // must NOT be misclassified as a driver failure — only a `Driver status:`
    // line with those keywords should trip the failure path.
    val responses = ArrayDeque<CliMcpClient.ToolResult>()
    responses.addLast(
      infoResult(
        """
          Connected device:
            Instance ID: playwright-chromium
            Platform: Web
            Driver: playwright_native

          Available web tools:
            core: getLastError, openUrl, tapOnElement
        """.trimIndent(),
      ),
    )

    val result = PlaywrightInstallWaiter.awaitReady(
      initialContent = "Playwright browser installing (0s elapsed): [10%] Downloading",
      fetchStatus = { responses.removeFirst() },
      onProgress = { },
      pollIntervalMs = 0L,
      now = fakeClock(),
    )

    assertNull(result, "Should treat incidental 'error' substring outside Driver status as ready; got: $result")
  }

  // ── awaitReady: transient isError is tolerated ──────────────────────────

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `awaitReady ignores transient isError responses and keeps polling`() = runTest {
    val responses = ArrayDeque<CliMcpClient.ToolResult>()
    responses.addLast(CliMcpClient.ToolResult("transient error", isError = true))
    responses.addLast(infoResult("Playwright browser installing (4s elapsed): [60%] Downloading"))
    responses.addLast(infoResult("Connected device:\n  Instance ID: playwright-chromium\n  Platform: Web"))

    val result = PlaywrightInstallWaiter.awaitReady(
      initialContent = "Playwright browser installing (0s elapsed): [10%] Downloading",
      fetchStatus = { responses.removeFirst() },
      onProgress = { },
      pollIntervalMs = 0L,
      now = fakeClock(),
    )

    assertNull(result, "Transient isError should not end the loop; got: $result")
  }

  // ── awaitReady: stall detection ──────────────────────────────────────────

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `awaitReady aborts when progress stalls past stall timeout`() = runTest {
    val responses = ArrayDeque<CliMcpClient.ToolResult>()
    // Same progress string every poll — this is what a wedged install looks like.
    repeat(200) {
      responses.addLast(
        infoResult("Playwright browser installing (90s elapsed): [42%] Downloading Chromium"),
      )
    }

    // Clock advances 10s per poll so the 90s stall window closes deterministically.
    val result = PlaywrightInstallWaiter.awaitReady(
      initialContent = "Playwright browser installing (0s elapsed): [42%] Downloading Chromium",
      fetchStatus = { responses.removeFirst() },
      onProgress = { },
      pollIntervalMs = 0L,
      stallTimeoutMs = 90_000L,
      overallDeadlineMs = 15 * 60_000L,
      now = advancingClock(stepMs = 10_000L),
    )

    assertNotNull(result)
    assertTrue(
      result.contains("stuck", ignoreCase = true) || result.contains("no progress", ignoreCase = true),
      "Expected stall error; got: $result",
    )
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  @Test
  fun `awaitReady aborts at overall deadline even if progress is advancing`() = runTest {
    // Emit a new progress line every poll so stall detection never fires.
    var pct = 0
    val fetchStatus: suspend () -> CliMcpClient.ToolResult = {
      pct = (pct + 1) % 100
      infoResult("Playwright browser installing (1s elapsed): [${pct}%] Downloading")
    }

    // Clock advances 60s per poll; overall deadline is 15m ⇒ loop should abort
    // after ~15 iterations with a deadline message.
    val result = PlaywrightInstallWaiter.awaitReady(
      initialContent = "Playwright browser installing (0s elapsed): [0%] Downloading",
      fetchStatus = fetchStatus,
      onProgress = { },
      pollIntervalMs = 0L,
      stallTimeoutMs = 90_000L,
      overallDeadlineMs = 15 * 60_000L,
      now = advancingClock(stepMs = 60_000L),
    )

    assertNotNull(result)
    assertTrue(
      result.contains("timed out", ignoreCase = true),
      "Expected deadline error; got: $result",
    )
  }

  // ── helpers ──────────────────────────────────────────────────────────────

  private fun infoResult(content: String): CliMcpClient.ToolResult =
    CliMcpClient.ToolResult(content = content, isError = false)

  /** Returns a clock that stays at epoch 0 — fine for tests that only look at deltas. */
  private fun fakeClock(): () -> Long = { 0L }

  /** Returns a clock that advances [stepMs] on every call, starting at 0. */
  private fun advancingClock(stepMs: Long): () -> Long {
    var current = 0L
    return {
      val t = current
      current += stepMs
      t
    }
  }
}
