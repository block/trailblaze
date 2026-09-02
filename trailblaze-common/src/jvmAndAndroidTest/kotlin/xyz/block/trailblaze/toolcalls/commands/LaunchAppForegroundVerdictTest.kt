package xyz.block.trailblaze.toolcalls.commands

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isSameAs
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.toolcalls.TrailblazeToolResult
import xyz.block.trailblaze.toolcalls.commands.LaunchAppTrailblazeTool.LaunchMode

/**
 * `launchApp`'s Android verdict: the app is in the foreground, or the launch failed.
 *
 * Nothing else reports a dropped launch. `startActivity` returns no status, so a launch the
 * platform silently drops leaves the launcher on screen while the tool reports Success, and the
 * trail fails several steps later with an unrelated "element not found" against the wrong screen.
 * That shape was the single largest source of flake on the sample-app Android replay runs: the
 * foreground poll ran, answered false, and its answer was thrown away.
 *
 * So the properties worth pinning are the ones that let a launch lie again: a `false` foreground
 * answer that doesn't become a failure, and a re-issue that never happens. Plus the ones that make
 * promoting the poll to a verdict safe in the first place — bounded re-issues, a shorter confirm
 * budget, a fatal result that stays fatal, and a re-issue that throws rather than returns.
 */
class LaunchAppForegroundVerdictTest {

  @Test
  fun `a launch that reaches the foreground is not re-issued`() = runBlocking {
    var relaunches = 0
    val verdict = foregroundFailureAfterLaunch(
      appId = APP_ID,
      awaitForeground = { true },
      relaunch = {
        relaunches++
        TrailblazeToolResult.Success()
      },
    )

    assertThat(verdict).isEqualTo(ForegroundVerdict.InForeground)
    assertThat(relaunches).isEqualTo(0)
  }

  @Test
  fun `a launch that never reaches the foreground is re-issued, and passes once it lands`() = runBlocking {
    var relaunches = 0
    val verdict = foregroundFailureAfterLaunch(
      appId = APP_ID,
      // False first (the dropped start), true after the re-issue.
      awaitForeground = { relaunches > 0 },
      relaunch = {
        relaunches++
        TrailblazeToolResult.Success()
      },
    )

    // Distinct from InForeground on purpose: the caller reports the re-issue, because a launch that
    // needed two tries is the flake signal and a console line alone is dropped in quiet mode.
    assertThat(verdict).isEqualTo(ForegroundVerdict.Relaunched)
    assertThat(relaunches).isEqualTo(1)
  }

  @Test
  fun `the first attempt gets the supported cold start, and the pair stays inside the total budget`() = runBlocking {
    val budgets = mutableListOf<Long>()
    foregroundFailureAfterLaunch(
      appId = APP_ID,
      awaitForeground = { maxWaitMs ->
        budgets += maxWaitMs
        false
      },
      relaunch = { TrailblazeToolResult.Success() },
    )

    // The first attempt is what an app still warming up is measured against, so it gets the full
    // supported cold start — `AppUnderTestLauncher.LAUNCH_TIMEOUT_MS` allows 120s because a big
    // app's first launch on a farm emulator includes dex/AOT warmup.
    //
    // The retry absorbs the enclosing MCP request deadline instead: the whole verdict runs inside
    // one synchronous `step` call, and overrunning it replaces this tool's diagnosis with a socket
    // timeout. Asserted as the exact pair so neither poll can be lengthened alone.
    assertThat(budgets).isEqualTo(
      listOf(LaunchForegroundBudget.FIRST_ATTEMPT_MS, LaunchForegroundBudget.RETRY_MS),
    )
    assertThat(budgets.sum()).isEqualTo(LaunchForegroundBudget.TOTAL_MS)
  }

  @Test
  fun `an app that never reaches the foreground fails the tool instead of reporting a launch`() = runBlocking {
    var relaunches = 0
    val verdict = foregroundFailureAfterLaunch(
      appId = APP_ID,
      awaitForeground = { false },
      relaunch = {
        relaunches++
        TrailblazeToolResult.Success()
      },
    )

    // Named so a reader of the failing step knows the app never came up, rather than reading a
    // downstream selector miss.
    val failed = assertThat(verdict).isInstanceOf(ForegroundVerdict.Failed::class)
    failed.transform { it.message }.contains(APP_ID)
    failed.transform { it.message }.contains("never reached the foreground")
    // Re-issued exactly once — a launch is expensive (force-stop, clear, cold start), so this
    // must not become an unbounded retry loop.
    assertThat(relaunches).isEqualTo(1)
  }

  @Test
  fun `a failed re-issue reports why, rather than the bare foreground miss`() = runBlocking {
    var relaunches = 0
    val verdict = foregroundFailureAfterLaunch(
      appId = APP_ID,
      awaitForeground = { false },
      relaunch = {
        relaunches++
        TrailblazeToolResult.Error.ExceptionThrown(errorMessage = "app not installed")
      },
    )

    assertThat(verdict).isInstanceOf(ForegroundVerdict.Failed::class)
      .transform { it.message }
      .contains("app not installed")
    // Still bounded: an erroring re-issue must not be retried again.
    assertThat(relaunches).isEqualTo(1)
  }

  @Test
  fun `a re-issue that throws is reported as a launch failure, not an unrelated crash`() = runBlocking {
    // Maestro's launch surfaces UnableToLaunchApp / UnableToClearState by throwing rather than
    // returning an Error, so this — not the branch above — is the real second-launch failure path.
    val verdict = foregroundFailureAfterLaunch(
      appId = APP_ID,
      awaitForeground = { false },
      relaunch = { throw IllegalStateException("unable to launch app") },
    )

    assertThat(verdict).isInstanceOf(ForegroundVerdict.Failed::class)
      .transform { it.message }
      .contains("unable to launch app")
  }

  @Test
  fun `a fatal re-issue result propagates unchanged instead of becoming a retryable error`() = runBlocking {
    val fatal = TrailblazeToolResult.Error.FatalError(errorMessage = "device disconnected")
    val verdict = foregroundFailureAfterLaunch(
      appId = APP_ID,
      awaitForeground = { false },
      relaunch = { fatal },
    )

    // FatalError means "stop execution now"; flattening it into ExceptionThrown would hand a
    // dead device back to the agent as something worth healing.
    assertThat(verdict).isInstanceOf(ForegroundVerdict.Fatal::class)
      .transform { it.result }
      .isSameAs(fatal)
  }

  @Test
  fun `a re-issue never clears state, and never kills a process RESUME promised to keep`() {
    // REINSTALL clears app data. The poll that triggers a re-issue can be a false negative on a
    // launch that worked, so repeating it would wipe a logged-in session mid-trail.
    assertThat(relaunchModeFor(LaunchMode.REINSTALL)).isEqualTo(LaunchMode.FORCE_RESTART)

    // RESUME is repeated as-is rather than upgraded. FORCE_RESTART maps to stopApp = true, which
    // would kill the in-memory process RESUME exists to continue — re-issuing the resume is both
    // contract-preserving and the right recovery for a dropped start.
    assertThat(relaunchModeFor(LaunchMode.RESUME)).isEqualTo(LaunchMode.RESUME)

    assertThat(relaunchModeFor(LaunchMode.FORCE_RESTART)).isEqualTo(LaunchMode.FORCE_RESTART)

    // No mode re-issues as REINSTALL — the whole point is that recovery cannot clear state.
    assertThat(LaunchMode.entries.map { relaunchModeFor(it) }).doesNotContain(LaunchMode.REINSTALL)
  }

  private companion object {
    const val APP_ID = "xyz.block.trailblaze.examples.sampleapp"
  }
}
