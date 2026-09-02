package xyz.block.trailblaze.cli

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isGreaterThanOrEqualTo
import assertk.assertions.isLessThanOrEqualTo
import assertk.assertions.isTrue
import org.junit.Test
import xyz.block.trailblaze.toolcalls.commands.LaunchForegroundBudget

/**
 * `launchApp`'s foreground verdict has to fit inside the MCP request deadline that encloses it.
 *
 * The whole recovery — poll, re-issue, poll again — happens inside one synchronous `step` call, and
 * [CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS] is applied to both the request and the socket. Overrun
 * does not degrade into a slow answer; the client aborts the socket and the user gets a transport
 * error in place of the "never reached the foreground" diagnosis the tool went to the trouble of
 * producing. A launch verdict nobody receives is the bug this PR is fixing, one layer out.
 *
 * The two constants live in different modules — the budget in `:trailblaze-common` beside the tool,
 * the deadline in `:trailblaze-host` beside the client — so nothing but this test notices when one
 * moves. Raising either without the other is the failure it exists to catch.
 */
class LaunchForegroundBudgetFitsRequestDeadlineTest {

  @Test
  fun `the launch recovery budget fits inside the MCP request deadline`() {
    assertThat(
      LaunchForegroundBudget.TOTAL_MS + LaunchForegroundBudget.REQUEST_DEADLINE_HEADROOM_MS,
    ).isLessThanOrEqualTo(CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS)
  }

  @Test
  fun `the headroom leaves real room for the work that is not polling`() {
    // Checked separately because the fit assertion above only gets laxer as the headroom shrinks:
    // zeroing it still passes, while quietly meaning "polling may consume the entire deadline" —
    // with nothing left for the two launch commands, the hierarchy read, or MCP transport.
    assertThat(LaunchForegroundBudget.REQUEST_DEADLINE_HEADROOM_MS)
      .isGreaterThanOrEqualTo(MIN_MEANINGFUL_HEADROOM_MS)
  }

  @Test
  fun `the budget is fully spent by the two polls, so no wait escapes the accounting`() {
    // TOTAL_MS is what the test above checks against the deadline. It only means anything if the
    // polls actually add up to it — a third wait, or a first attempt raised on its own, would
    // otherwise overrun a total that still looks compliant.
    assertThat(
      LaunchForegroundBudget.FIRST_ATTEMPT_MS + LaunchForegroundBudget.RETRY_MS,
    ).isEqualTo(LaunchForegroundBudget.TOTAL_MS)
  }

  @Test
  fun `the first attempt keeps the full supported cold start`() {
    // Fitting the deadline must not be paid for by shrinking the FIRST attempt: that is the one an
    // app still warming up is measured against, and `AppUnderTestLauncher.LAUNCH_TIMEOUT_MS` allows
    // 120s for it. The retry is where the deadline is absorbed.
    assertThat(LaunchForegroundBudget.FIRST_ATTEMPT_MS).isEqualTo(SUPPORTED_COLD_START_MS)
    assertThat(LaunchForegroundBudget.RETRY_MS > 0).isTrue()
  }

  private companion object {
    /** `AppUnderTestLauncher.LAUNCH_TIMEOUT_MS`. */
    const val SUPPORTED_COLD_START_MS = 120_000L

    /** Two `startActivity` round trips, a hierarchy read, and MCP transport need more than a tick. */
    const val MIN_MEANINGFUL_HEADROOM_MS = 15_000L
  }
}
