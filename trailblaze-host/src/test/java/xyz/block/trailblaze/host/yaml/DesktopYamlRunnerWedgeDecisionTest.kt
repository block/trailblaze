package xyz.block.trailblaze.host.yaml

import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.client.temp.OtherTrailblazeTool
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.util.UiAutomationHandleErrors
import java.nio.file.Files

/**
 * Decision-logic guard for the in-harness on-device-server wedge recovery.
 *
 * [DesktopYamlRunner.shouldRelaunchOnDeviceServer] is the host's signal for force-restarting the
 * shared on-device server before the next trail. Two overloads: the terminal-status gate (relaunch
 * ONLY on `Ended.Failed` carrying the non-recoverable stale-handle signature, never on an ordinary
 * failure or a non-failed status — an over-broad match would relaunch per failing trail and could
 * mask a genuine on-device crash) and the log-scanning overload, which additionally treats any
 * FAILED tool log carrying the signature as proof of the wedge — because a mid-trail wedge is
 * usually absorbed by the agent loop and never reaches the terminal message. This is the pure
 * decision; end-to-end recovery needs a device run.
 */
class DesktopYamlRunnerWedgeDecisionTest {

  // Reconstructed from the same shared phrases InstrumentationUtil throws, so this test fails if the
  // emitted text and the matcher ever drift apart.
  private val nonRecoverableWedgeMessage =
    "${UiAutomationHandleErrors.NON_RECOVERABLE_RETRY_FAILED_PHRASE}. The on-device server's " +
      "instrumentation is in a ${UiAutomationHandleErrors.NON_RECOVERABLE_STATE_PHRASE} — kill the " +
      "test APK process and re-launch the Trailblaze on-device server to recover. Original error: " +
      "UiAutomation not connected"

  @Test
  fun `relaunches on the non-recoverable UiAutomation wedge`() {
    assertTrue(
      DesktopYamlRunner.shouldRelaunchOnDeviceServer(
        SessionStatus.Ended.Failed(durationMs = 12_000, exceptionMessage = nonRecoverableWedgeMessage),
      ),
    )
  }

  @Test
  fun `does not relaunch on an ordinary trail failure`() {
    listOf(
      "Element not found: Text matching regex: Charge",
      "Assertion failed: expected \"Review sale\" to be visible",
      "Timed out waiting for run to complete after 600s",
      // A recoverable stale-handle signature on its own was already absorbed in-process; it must
      // NOT trip the relaunch (only the non-recoverable wrapper reaches a terminal Failed status).
      "Cannot call disconnect() while connecting UiAutomation",
      null,
    ).forEach { message ->
      assertFalse(
        "did not expect relaunch for ordinary failure: $message",
        DesktopYamlRunner.shouldRelaunchOnDeviceServer(
          SessionStatus.Ended.Failed(durationMs = 3_000, exceptionMessage = message),
        ),
      )
    }
  }

  @Test
  fun `does not relaunch on non-failed terminal statuses`() {
    listOf(
      SessionStatus.Ended.Succeeded(durationMs = 5_000),
      SessionStatus.Ended.Cancelled(durationMs = 1_000, cancellationMessage = "user cancelled"),
      // A non-recoverable-wedge message attached to a non-Failed status still must not relaunch:
      // the gate is keyed on Ended.Failed, not on the message alone.
      SessionStatus.Ended.TimeoutReached(durationMs = 600_000, message = nonRecoverableWedgeMessage),
      SessionStatus.Unknown,
      null,
    ).forEach { status ->
      assertFalse(
        "did not expect relaunch for status: ${status?.let { it::class.simpleName } ?: "null"}",
        DesktopYamlRunner.shouldRelaunchOnDeviceServer(status),
      )
    }
  }

  // On the host-agent path (preferHostAgent=true — the real CI accessibility V1 RPC path), a
  // mid-trail wedge surfaces NOT as the device's raw message but as the host-side `TrailblazeException`
  // re-thrown by the trail loop. Session logs written before `TrailblazeSessionManager` split the
  // stack into `exceptionStackTrace` carry `<message-line>\n<stack-trace>` in
  // `Ended.Failed.exceptionMessage`. Reproduce that legacy shape so these tests fail if the matcher
  // ever stops tolerating a stack-bearing message.
  private fun hostAgentFailedStatus(rootMessage: String): SessionStatus.Ended.Failed {
    val wrapped = "$rootMessage\n" + RuntimeException(rootMessage).stackTraceToString()
    return SessionStatus.Ended.Failed(durationMs = 9_000, exceptionMessage = wrapped)
  }

  @Test
  fun `host-agent path relaunches on the wrapped non-recoverable wedge`() {
    assertTrue(
      "expected relaunch when the host-agent wedge signature survives stack-bearing-message wrapping",
      DesktopYamlRunner.shouldRelaunchOnDeviceServer(hostAgentFailedStatus(nonRecoverableWedgeMessage)),
    )
  }

  @Test
  fun `host-agent path does not relaunch on an ordinary wrapped failure`() {
    listOf(
      // Tool-loop failures the host-agent path throws via `TrailblazeException(itemResult.errorMessage)`.
      "Element not found: Text matching regex: Charge",
      "Unsupported tool type for RPC execution: OtherTrailblazeTool",
      "RPC call failed for 'TapOnElement': re-warm failed: connection refused",
      // A recoverable signature alone (absorbed in-process) must not trip the relaunch even when wrapped.
      "Cannot call disconnect() while connecting UiAutomation",
    ).forEach { rootMessage ->
      assertFalse(
        "did not expect relaunch for ordinary host-agent failure: $rootMessage",
        DesktopYamlRunner.shouldRelaunchOnDeviceServer(hostAgentFailedStatus(rootMessage)),
      )
    }
  }

  // ── Log-scanning overload ─────────────────────────────────────────────────────────────────
  //
  // The terminal status alone is unreliable: a mid-trail wedge is absorbed by the agent loop
  // (tool errors feed the LLM / self-heal), so the session keeps running and ends with whatever
  // the LAST step failed with — or as MaxCallsLimitReached with no message at all. Both shapes
  // slipped past the status gate in trailblaze-android-pr/2712 and poisoned the rest of the
  // build. The logs overload also scans per-tool failures, which carry the signature verbatim.

  private val testSessionId = SessionId("wedge-decision-test")

  private fun toolLog(successful: Boolean, exceptionMessage: String?): TrailblazeLog.TrailblazeToolLog =
    TrailblazeLog.TrailblazeToolLog(
      trailblazeTool = OtherTrailblazeTool(toolName = "tapOnElementBySelector", raw = JsonObject(emptyMap())),
      toolName = "tapOnElementBySelector",
      successful = successful,
      traceId = null,
      exceptionMessage = exceptionMessage,
      durationMs = 100,
      session = testSessionId,
      timestamp = Instant.fromEpochMilliseconds(0),
    )

  private fun statusLog(status: SessionStatus): TrailblazeLog.TrailblazeSessionStatusChangeLog =
    TrailblazeLog.TrailblazeSessionStatusChangeLog(
      sessionStatus = status,
      session = testSessionId,
      timestamp = Instant.fromEpochMilliseconds(0),
    )

  @Test
  fun `log scan relaunches when a wedged tool log hides behind a different terminal failure`() {
    // 2712 shape #1: the wedged trail kept running (the agent loop absorbed the tool errors), so
    // the terminal message was the last step's unrelated failure — the wedge shows only in tool logs.
    val logs = listOf(
      toolLog(successful = false, exceptionMessage = nonRecoverableWedgeMessage),
      statusLog(
        SessionStatus.Ended.Failed(
          durationMs = 60_000,
          exceptionMessage = "Assertion failed: expected \"Review sale\" to be visible",
        ),
      ),
    )
    assertTrue(DesktopYamlRunner.shouldRelaunchOnDeviceServer(logs))
  }

  @Test
  fun `log scan relaunches when the wedged trail exhausted its LLM call budget`() {
    // 2712 shape #2: consecutive wedged dispatches burned the objective's call budget; the
    // terminal status is MaxCallsLimitReached — not Ended.Failed, and it carries no message.
    val logs = listOf(
      toolLog(successful = false, exceptionMessage = nonRecoverableWedgeMessage),
      statusLog(
        SessionStatus.Ended.MaxCallsLimitReached(
          durationMs = 300_000,
          maxCalls = 31,
          objectivePrompt = "View the account balance",
        ),
      ),
    )
    assertTrue(DesktopYamlRunner.shouldRelaunchOnDeviceServer(logs))
  }

  @Test
  fun `log scan relaunches on a wedged tool log even when the session nominally succeeded`() {
    // The wedge message is only emitted after the on-device in-process reconnect retry has already
    // failed — the server is in the kill-and-relaunch-only state no matter how the session later
    // ended (a session can even end green if its remaining steps never touch the device).
    val logs = listOf(
      toolLog(successful = false, exceptionMessage = nonRecoverableWedgeMessage),
      statusLog(SessionStatus.Ended.Succeeded(durationMs = 42_000)),
    )
    assertTrue(DesktopYamlRunner.shouldRelaunchOnDeviceServer(logs))
  }

  @Test
  fun `log scan does not relaunch on ordinary failed tool logs`() {
    val logs = listOf(
      toolLog(successful = false, exceptionMessage = "Element not found: Text matching regex: Charge"),
      // Recoverable stale-handle signature absorbed in-process — must NOT trip the relaunch.
      toolLog(successful = false, exceptionMessage = "Cannot call disconnect() while connecting UiAutomation"),
      toolLog(successful = false, exceptionMessage = null),
      statusLog(SessionStatus.Ended.Failed(durationMs = 8_000, exceptionMessage = "Element not found")),
    )
    assertFalse(DesktopYamlRunner.shouldRelaunchOnDeviceServer(logs))
  }

  @Test
  fun `log scan ignores wedge text on a successful tool log`() {
    // The gate requires a FAILED tool log; message content alone (e.g. a diagnostic tool echoing
    // device state on success) must not arm a relaunch.
    val logs = listOf(
      toolLog(successful = true, exceptionMessage = nonRecoverableWedgeMessage),
      statusLog(SessionStatus.Ended.Succeeded(durationMs = 5_000)),
    )
    assertFalse(DesktopYamlRunner.shouldRelaunchOnDeviceServer(logs))
  }

  @Test
  fun `log scan still honors the terminal-status signal with no tool logs`() {
    // Delegation to the status overload: the V1 fire-and-forget path can wedge during session
    // setup, before any tool log exists.
    val logs = listOf(
      statusLog(
        SessionStatus.Ended.Failed(durationMs = 2_000, exceptionMessage = nonRecoverableWedgeMessage),
      ),
    )
    assertTrue(DesktopYamlRunner.shouldRelaunchOnDeviceServer(logs))
    assertFalse(DesktopYamlRunner.shouldRelaunchOnDeviceServer(emptyList()))
  }

  @Test
  fun `the wedge decision holds through a real LogsRepo disk round-trip`() {
    // `armIfWedged` doesn't see in-memory log objects — it reads the session's logs back from
    // disk via `LogsRepo.getLogsForSession` before applying the decision above. Pin the full
    // encode → write → decode round-trip against a REAL LogsRepo with the 2712 shape (wedged
    // tool log hidden behind a benign terminal status): a serialization or repo-read change
    // that drops or mangles either log (e.g. the tool log's `exceptionMessage`) breaks here
    // instead of silently reverting detection to the status-only gate on a real daemon.
    // (`DesktopYamlRunner` itself isn't hermetically constructable — it needs the full
    // `TrailblazeDeviceManager` graph — so the repo boundary is the widest slice a JVM unit
    // test can pin; the remaining glue in `armIfWedged` is one straight-line read + call.)
    val logsDir = Files.createTempDirectory("wedge-decision-logs-repo").toFile()
    try {
      val logsRepo = LogsRepo(logsDir = logsDir, watchFileSystem = false)
      logsRepo.saveLogToDisk(
        toolLog(successful = false, exceptionMessage = nonRecoverableWedgeMessage),
      )
      logsRepo.saveLogToDisk(
        statusLog(
          SessionStatus.Ended.MaxCallsLimitReached(
            durationMs = 300_000,
            maxCalls = 31,
            objectivePrompt = "View the account balance",
          ),
        ),
      )

      val roundTripped = logsRepo.getLogsForSession(testSessionId)
      assertEquals("both logs must survive the disk round-trip", 2, roundTripped.size)
      assertTrue(DesktopYamlRunner.shouldRelaunchOnDeviceServer(roundTripped))
    } finally {
      logsDir.deleteRecursively()
    }
  }
}
