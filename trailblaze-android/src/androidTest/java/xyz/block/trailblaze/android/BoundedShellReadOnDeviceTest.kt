package xyz.block.trailblaze.android

import android.os.SystemClock
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import xyz.block.trailblaze.AdbCommandUtil
import xyz.block.trailblaze.device.BoundedReadTimeoutException
import xyz.block.trailblaze.util.UiAutomationHandleErrors

/**
 * On-device verification of what `AdbCommandUtil` does with a shell command whose output never
 * ends, covering the three things its JVM unit tests cannot reach: that a read blocked on a pipe
 * fed by another process really does end when the descriptor under it is closed, that the shell
 * connection survives being closed out from under mid-read, and that the recovery this triggers —
 * drop the cached UiAutomation handle, report a failure nothing will replay — works against the
 * real platform rather than a double.
 *
 * `BoundedReadTest` covers the decision logic against a test double, which is the right place for
 * it. But the reason `readWithDeadline` takes a `cancel` lambda at all is a claim about real Android
 * plumbing — that such a read ignores `Thread.interrupt()`, so closing the `ParcelFileDescriptor`
 * underneath the reader is the only way out. A double cannot confirm that, and getting it wrong is
 * worse than the hang it replaces: the read runs inside `withUiAutomation`, which is `synchronized`
 * on the process-wide UiAutomation monitor, so a read that never unwinds holds that monitor for the
 * life of the process and queues every later device action behind a call already reported as failed.
 *
 * This calls [AdbCommandUtil.runShellCommand] itself rather than restating its wiring, so the
 * assertions below are about the shipped path. A copy could — and did — drift out from under them:
 * it went on asserting a raw [BoundedReadTimeoutException] long after production started converting
 * that into the handle-discarding error asserted here.
 *
 * What each part establishes:
 *  - **The read unwinds at all**, which is what releases the monitor — the JVM drops it when the
 *    `synchronized` block exits. So a `cancel` that fails to break the read shows up here as this
 *    test never returning, contained only by [timeout]. It is deliberately *not* claimed that the
 *    later shell command detects a still-held monitor: Java monitors are reentrant, so the test
 *    thread would re-enter one it held itself.
 *  - **The failure is not classified as a replayable one.** `UiAutomationHandleErrors.isStaleHandleSignature`
 *    IS the replay decision in `InstrumentationUtil.runWithStaleUiAutomationRecovery`, so the
 *    assertion is on that predicate rather than on any wording: a wedged `pm clear` / `input tap` /
 *    `am force-stop` that classified as stale would be re-issued on a real device, having possibly
 *    already taken effect.
 *  - **The shell still answers afterwards** ([LIVENESS_COMMAND]). Cancelling closes the descriptor
 *    while the platform is still writing into it, and a UiAutomation connection that did not survive
 *    that would answer every later command with `""` rather than failing — the silent-shell wedge
 *    `AdbCommandUtil.execShellCommand` exists to catch. "Promptly" is enforced by [timeout] covering
 *    the whole test, not by a wall-clock bound on the command: a held monitor parks forever rather
 *    than running slowly, so a stopwatch here would only add a machine-speed bet.
 * Deliberately NOT asserted here: *which* of the two
 * [UiAutomationHandleErrors.wedgedShellReadMessage] branches the failure takes, i.e. whether the
 * cached UiAutomation handle was droppable. That is a platform fact rather than a device-only
 * behaviour — dropping it is reflection into `Instrumentation`, which hidden-API enforcement refuses
 * unless `am instrument` is given `--no-hidden-api-checks` — and both branches, including the
 * escalation to a runner restart when the drop fails, are already covered precisely by
 * `UiAutomationHandleErrorsTest` on the JVM. Pinning a branch here would only make this test a claim
 * about the image's hidden-API policy.
 *
 * ## Why the budget here is not a machine-speed bet
 *
 * The production bound is 5 minutes and deliberately far above the slowest real command, so
 * exercising it directly would park a device lane for five minutes per run. This test instead passes
 * its **own** [TEST_BUDGET_MS] into the same function under test, against a command that cannot
 * finish inside any budget ([WEDGING_COMMAND]). Nothing here depends on how fast the host is. The
 * [timeout] rule is hang containment for the monitor-leak case — where the failure mode IS parking
 * forever — not a performance budget.
 *
 * A red here is not a retry candidate. If the monitor really did leak, the thread holding it cannot
 * be freed — it ignores interrupt by design, which is this test's whole premise — so [timeout]
 * fails this test without recovering the process, and every later class in the same instrumentation
 * run hangs too. Read the first failure, not the cascade.
 */
class BoundedShellReadOnDeviceTest {

  @get:Rule
  val timeout: Timeout = Timeout(2, TimeUnit.MINUTES)

  /**
   * Kills the wedging command. This is not tidiness — the orphan is what the *instrumentation run*
   * waits on. The command inherits the instrumentation process's stdout, which is the pipe
   * `am instrument -w` reads, so until it exits that pipe never reaches EOF. Measured on an API 29
   * tablet: without this, the same passing test reported `time="2.994"` inside a suite that took
   * `603.061` — one whole [WEDGING_COMMAND] of dead time on a gating farm step, doubled by the
   * retry. With it, the suite is `3.69`.
   *
   * In `@After` rather than at the end of the test so it also runs when an assertion fails, which
   * is exactly when the run can least afford another ten minutes.
   */
  @After
  fun reapTheWedgingCommand() {
    // `pkill -f` matches against the whole command line, but UiAutomation does not run commands
    // through a shell — argv is split on whitespace and nothing else — so the pattern has to be a
    // single token with no space in it. `.` stands in for the space as a regex wildcard. Matching
    // the duration too is what keeps this narrow: a bare `pkill sleep` would take a neighbouring
    // test's sleep with it.
    AdbCommandUtil.execShellCommand("pkill -f ${WEDGING_COMMAND.replace(' ', '.')}")
  }

  @Test
  fun aWedgedShellReadFailsOnItsDeadlineAndReleasesTheUiAutomationMonitor() {
    val startedAt = SystemClock.elapsedRealtime()
    // The real function, with only the budget swapped for one this test owns. `loggableCommand` is
    // what the caller would have redacted; there is nothing to redact in a `sleep`.
    val outcome = runCatching {
      AdbCommandUtil.runShellCommand(
        shellCommand = WEDGING_COMMAND,
        loggableCommand = WEDGING_COMMAND,
        timeoutMs = TEST_BUDGET_MS,
      )
    }
    val elapsedMs = SystemClock.elapsedRealtime() - startedAt

    // Captured rather than asserted with assertThrows so the message can carry what actually
    // happened. This lane collects no logcat, so a wrong outcome has to explain itself from the
    // assertion text alone, and "nothing was thrown" would throw away the two facts that tell a
    // broken bound apart from a command that never blocked: what the read returned, and when.
    val wedged = outcome.exceptionOrNull() as? IllegalStateException
      ?: throw AssertionError(
        "expected `$WEDGING_COMMAND` to be bounded out after ${TEST_BUDGET_MS}ms and reported as a " +
          "wedged shell read, but after ${elapsedMs}ms it " +
          outcome.fold(
            onSuccess = { "returned '$it' — the command never blocked" },
            onFailure = { "failed with $it" },
          ),
      )

    // The deadline honoured the budget it was given, which is what catches a bound that fires
    // without waiting. (A read that failed early for an unrelated reason is not wrapped as a
    // timeout at all — `readWithDeadline` rethrows it raw — so it lands on the check above.)
    assertTrue(
      "expected the read to block for its full ${TEST_BUDGET_MS}ms budget, took ${elapsedMs}ms",
      elapsedMs >= TEST_BUDGET_MS,
    )

    // A wedged read must never be replayed: `isStaleHandleSignature` is what decides that in
    // `InstrumentationUtil.runWithStaleUiAutomationRecovery`, so the classification is asserted
    // rather than the wording. Stand-ins for `$WEDGING_COMMAND` in production are `pm clear`,
    // `input tap` and `am force-stop` — commands that may already have taken effect by the time
    // their output pipe wedges. Holds on both branches of [UiAutomationHandleErrors.wedgedShellReadMessage].
    assertFalse(
      "a wedged shell read must not classify as a replayable stale handle, message was: " +
        "'${wedged.message}'",
      UiAutomationHandleErrors.isStaleHandleSignature(wedged.message),
    )

    // The two fields the failure has to carry, named individually rather than by asserting the
    // whole diagnostic — the wording of a human-readable message is not a contract. Comparing it to
    // the same formatter's own output would be worse than loose: a formatter that stopped naming
    // either field would move both sides of the comparison and assert nothing at all.
    //
    // The budget is the load-bearing one here: it is what shows the `timeoutMs` this test passed in
    // actually reached the failure, rather than the production default being reported. Matched WITH
    // its unit, because the bare number would not discriminate — the production default is
    // `300000`, and `"300000".contains("3000")` is true. `"300000ms".contains("3000ms")` is not.
    val message = wedged.message.orEmpty()
    assertTrue("the failure should name the command that hung, was: '$message'", message.contains(WEDGING_COMMAND))
    assertTrue(
      "the failure should name the ${TEST_BUDGET_MS}ms budget this test gave it, was: '$message'",
      message.contains("${TEST_BUDGET_MS}ms"),
    )

    // The bounded read's own timeout is kept as the cause so a non-timeout failure stays readable.
    val boundedTimeout = wedged.cause as? BoundedReadTimeoutException
      ?: throw AssertionError(
        "the wedged-shell failure should carry the bounded read's timeout as its cause, was: " +
          "${wedged.cause}",
      )
    // Cancelling is what lands the deadline, so the read must have failed rather than returned, and
    // that failure is kept as the cause. A null cause means the read returned at the same moment
    // the deadline fired — a timeout this test did not actually produce.
    assertNotNull(
      "the cancelled read's own failure should be kept as the timeout's cause; a null cause means " +
        "`$WEDGING_COMMAND` returned on its own after ${elapsedMs}ms instead of being cancelled",
      boundedTimeout.cause,
    )
    assertTrue(
      "the bounded read should have been told which command it was guarding, was: " +
        "'${boundedTimeout.description}'",
      boundedTimeout.description.contains(WEDGING_COMMAND),
    )

    // The shell connection survived having a read closed out from under it. Goes through
    // execShellCommand, so the silent-wedge check runs too: a connection that answers "" rather
    // than failing is caught there rather than read as a successful command.
    val afterTimeout = AdbCommandUtil.execShellCommand(LIVENESS_COMMAND).trim()
    assertTrue(
      "a shell command after a bounded-out read should still answer, got: '$afterTimeout'",
      (afterTimeout.toIntOrNull() ?: 0) > 0,
    )
  }

  private companion object {
    /**
     * Produces no output and holds its pipe open, so the read blocks — the shape being bounded.
     *
     * The duration is load-bearing and must stay well ABOVE the [timeout] rule, not just above
     * [TEST_BUDGET_MS]. At `sleep 60` this test still failed when `cancel()` was mutated to a no-op,
     * but for the wrong reason: the read ended on its own when the command exited, so the test
     * reported a timeout with a null cause instead of demonstrating a held monitor. Outlasting the
     * containment window is what makes an uncancelled read park, which is the failure this test
     * exists to catch.
     *
     * Closing the read end of the pipe does not signal a command that never writes to it, so this
     * outlives the test and has to be killed in [reapTheWedgingCommand] — see there for why the
     * run, not just the device, pays for it otherwise.
     */
    const val WEDGING_COMMAND = "sleep 600"
    const val TEST_BUDGET_MS = 3_000L

    /**
     * Liveness probe for the shell connection: its output — the device's API level — is a value
     * only a working shell produces, so an answer of `""` or anything unparseable is a wedge.
     */
    const val LIVENESS_COMMAND = "getprop ro.build.version.sdk"
  }
}
