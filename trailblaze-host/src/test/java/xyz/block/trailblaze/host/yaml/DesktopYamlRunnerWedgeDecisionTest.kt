package xyz.block.trailblaze.host.yaml

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.util.UiAutomationHandleErrors

/**
 * Decision-logic guard for the in-harness on-device-server wedge recovery.
 *
 * The on-device V1 RunYaml RPC is fire-and-forget and the readiness probe can't see a mid-trail
 * UiAutomation wedge, so [DesktopYamlRunner.shouldRelaunchOnDeviceServer] is the host's only signal
 * for force-restarting the shared server before the next trail. These tests pin the gate: relaunch
 * ONLY on `Ended.Failed` carrying the non-recoverable stale-handle signature, never on an ordinary
 * failure or a non-failed status — an over-broad match would relaunch per failing trail and could
 * mask a genuine on-device crash. This is the pure decision; end-to-end recovery needs a device run.
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
  // re-thrown by the trail loop, which `TrailblazeSessionManager.formatException` wraps into
  // `Ended.Failed.exceptionMessage` as `<message-line>\n<stack-trace>`. Reproduce that exact shape so
  // these tests fail if the wrapping ever stops carrying the signature the matcher keys on.
  private fun hostAgentFailedStatus(rootMessage: String): SessionStatus.Ended.Failed {
    val wrapped = "$rootMessage\n" + RuntimeException(rootMessage).stackTraceToString()
    return SessionStatus.Ended.Failed(durationMs = 9_000, exceptionMessage = wrapped)
  }

  @Test
  fun `host-agent path relaunches on the wrapped non-recoverable wedge`() {
    assertTrue(
      "expected relaunch when the host-agent wedge signature survives formatException wrapping",
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
}
