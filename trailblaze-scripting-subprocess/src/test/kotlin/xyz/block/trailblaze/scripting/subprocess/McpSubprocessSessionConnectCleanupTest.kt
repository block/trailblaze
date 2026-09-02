package xyz.block.trailblaze.scripting.subprocess

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.rules.Timeout
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Verifies [McpSubprocessSession.connect] doesn't leak the subprocess when the MCP
 * `initialize` handshake fails. Uses POSIX `true` as a minimal subprocess: it exits
 * immediately, closing stdin/stdout/stderr, which makes the SDK client's initialize
 * request fail with EOF. Our cleanup path should close the client, close the stderr
 * capture, and (if needed) destroy the process before rethrowing.
 *
 * The binaries are resolved from candidate paths (`/bin` on Linux, `/usr/bin` on macOS)
 * rather than hardcoded, so the tests actually run on both instead of silently skipping —
 * skipped only on a platform (e.g. Windows) that ships neither.
 */
class McpSubprocessSessionConnectCleanupTest {

  /**
   * Hang-vs-forever containment, not a performance bound: the worst LEGITIMATE duration here is
   * the default 60s handshake watchdog plus the ~9s teardown escalation ladder, so 120s can only
   * be crossed by a genuine wedge. Before the handshake-cancel lever was added to the watchdog
   * (see [McpSubprocessSession.connect]), an instant-exit subprocess could orphan the SDK's
   * initialize await with nothing left to unblock it — this test class then parked forever and
   * wedged the whole `check` run at 99% until the CI step was cancelled (three mainline
   * mandatory-checks builds). The rule converts any recurrence into a failed test.
   *
   * The process-exit lever later made that same orphan fail in milliseconds rather than at the
   * bound, so no test here should now approach this guard — but it stays as the backstop, because a
   * park is the failure mode with no other symptom.
   */
  @get:Rule val perTestHangGuard: Timeout = Timeout(120, TimeUnit.SECONDS)

  private val binTrue = firstExecutable("/bin/true", "/usr/bin/true")
  private val binSleep = firstExecutable("/bin/sleep", "/usr/bin/sleep")
  private val binSh = firstExecutable("/bin/sh", "/usr/bin/sh")

  @Test fun `connect cleans up subprocess when initialize fails`() {
    assumeTrue("POSIX `true` is required to exercise the cleanup path", binTrue != null)
    runBlocking {
      val process = ProcessBuilder(binTrue!!.absolutePath).redirectErrorStream(false).start()
      try {
        val spawned = SpawnedProcess(
          process = process,
          scriptFile = binTrue,
          argv = listOf(binTrue.absolutePath),
        )
        // Explicit capture instance so the test can assert close() was reached. If the
        // cleanup regressed to skip it, a file-backed capture would leak silently because
        // StderrCapture.close() swallows write errors via runCatching.
        val capture = StderrCapture()

        // A subprocess that exits is never a timeout. Which error surfaces is genuinely racy — the
        // organic EOF read error usually wins, the process-exit lever catches the interleaving where
        // the SDK orphans the await instead (see [McpSubprocessSession.connect]) — but BOTH are
        // non-timeout, so this assertion holds either way, and it does not by itself prove the lever
        // fired. It used to be a coin flip: before the exit lever existed the orphaned await had
        // nothing to unblock it, so on a contended runner the 60s watchdog was the only way out and
        // the failure came back as a timeout. This is the regression guard for that — note the
        // default (60s) bound is deliberately NOT shortened here, because a timeout arriving at all
        // is the failure being tested for.
        assertFailure {
          McpSubprocessSession.connect(spawnedProcess = spawned, stderrCapture = capture)
        }.isNotInstanceOf(McpSubprocessHandshakeTimeoutException::class)
        // Cleanup ran. `true` exits on its own, so isAlive is false regardless of whether
        // we actually had to destroy() — the guarantee we want to assert is "no orphan".
        assertThat(process.isAlive).isEqualTo(false)
        // And the stderr capture was closed along the way — otherwise a file-backed variant
        // would leak its FileWriter handle and there'd be no test catching it.
        assertThat(capture.isClosed).isEqualTo(true)
      } finally {
        process.destroyForcibly()
      }
    }
  }

  /**
   * The wedge that hung CI: a subprocess that is already dead when [McpSubprocessSession.connect]
   * runs. Its stdout is at EOF before the SDK sends `initialize`, so the transport can close and
   * wipe the response-handler map before the request registers — orphaning an await that no stream
   * event will ever complete — and the watchdog's force-destroy is a no-op on a dead process.
   *
   * Which error surfaces here is racy — the organic EOF read error usually wins, the process-exit
   * lever catches the interleaving where the SDK orphans the await instead — so this pins only the
   * invariant that holds either way: a subprocess that exited must never be reported as a *timeout*.
   * It does NOT prove the lever fired; the organic path satisfies this assertion on its own. The
   * lever's own coverage is
   * [connect surfaces the exit lever when the subprocess exits but its stdout stays open], which
   * removes the organic path from the race. The generous bound is still load-bearing: with a 30s
   * bound, a timeout can only appear if nothing unwedged the await, so this stays a regression guard
   * for the park without timing anything.
   */
  @Test fun `connect fails without waiting out its bound when the subprocess is already dead`() {
    assumeTrue("POSIX `true` is required to produce a dead-on-arrival subprocess", binTrue != null)
    runBlocking {
      val process = ProcessBuilder(binTrue!!.absolutePath).redirectErrorStream(false).start()
      try {
        // Guarantee dead-on-arrival: reap the process before connect ever sees it.
        process.waitFor()
        val spawned = SpawnedProcess(
          process = process,
          scriptFile = binTrue,
          argv = listOf(binTrue.absolutePath),
        )
        val capture = StderrCapture()

        assertFailure {
          McpSubprocessSession.connect(
            spawnedProcess = spawned,
            stderrCapture = capture,
            handshakeTimeoutMillis = 30_000,
          )
        }.isNotInstanceOf(McpSubprocessHandshakeTimeoutException::class)

        // No orphan, and the capture was closed on the way out.
        assertThat(process.isAlive).isEqualTo(false)
        assertThat(capture.isClosed).isEqualTo(true)
      } finally {
        process.destroyForcibly()
      }
    }
  }

  /**
   * Verifies the bounded handshake: a subprocess that stays alive but never answers the MCP
   * `initialize` handshake must make [McpSubprocessSession.connect] time out and tear the
   * subprocess down — instead of parking indefinitely, which was the root of the daemon-wide
   * MCP wedge (build 3366). `sleep 30` is that subprocess: alive, silent on stdout.
   *
   * Also the regression guard for the mechanism itself: `client.connect` parks on a blocking,
   * non-cancellable native read of the subprocess's stdout, so a plain `withTimeout` around it
   * does NOT fire until the subprocess exits on its own. The watchdog force-destroys the
   * subprocess at the timeout, unblocking that read. The subprocess's **exit status** is what
   * separates the two without timing anything: a `sleep` that ran to completion exits 0, while one
   * the watchdog SIGKILLed exits non-zero. So if the fix regressed to a bare `withTimeout`,
   * connect would unwind only once the sleep finished on its own and the exit status would be 0 —
   * failing the assertion below.
   *
   * Gated on POSIX `sleep` availability — skipped on Windows.
   */
  @Test fun `connect times out and destroys the subprocess when the handshake never completes`() {
    assumeTrue("POSIX `sleep` is required to exercise the handshake-timeout path", binSleep != null)
    runBlocking {
      val process = ProcessBuilder(binSleep!!.absolutePath, "30").redirectErrorStream(false).start()
      try {
        val spawned = SpawnedProcess(
          process = process,
          scriptFile = binSleep,
          argv = listOf(binSleep.absolutePath, "30"),
        )
        val capture = StderrCapture()

        val thrown = assertFailsWith<McpSubprocessHandshakeTimeoutException> {
          McpSubprocessSession.connect(
            spawnedProcess = spawned,
            stderrCapture = capture,
            handshakeTimeoutMillis = 250,
          )
        }

        // The subprocess was torn down by the timeout cleanup — no orphan left behind.
        assertThat(process.isAlive).isEqualTo(false)
        // Force-destroyed on the 250ms bound, NOT waited out: `sleep 30` running to completion
        // exits 0, so a non-zero (signal) status is the direct evidence that the watchdog's kill is
        // what unblocked the handshake read.
        assertThat(process.exitValue()).isNotEqualTo(0)
        // And the stderr capture was closed on the way out.
        assertThat(capture.isClosed).isEqualTo(true)
        // Attributable: the timeout names the culprit script and the bound it blew, and preserves the
        // underlying unwind (stream-closed read error or the watchdog's handshake cancellation) as
        // the cause so the daemon log keeps the root failure.
        assertThat(thrown.scriptName).isEqualTo(binSleep!!.name)
        assertThat(thrown.timeoutMillis).isEqualTo(250L)
        assertThat(thrown.cause).isNotNull()
      } finally {
        process.destroyForcibly()
      }
    }
  }

  /**
   * The only test that actually exercises the process-exit lever, rather than the invariant around
   * it. Every other dead-subprocess case here is satisfied by the organic EOF path, so deleting the
   * lever would leave them green — this one goes red.
   *
   * The fixture works by DELAYING the organic path, not removing it. `sh -c 'sleep 2 & exit 0'` exits
   * immediately, so `onExit()` fires at once, but the backgrounded grandchild inherits the stdout
   * pipe's write end and holds it open — so for the next 2s the process is dead with no EOF in sight,
   * `initialize` is never answered, and the await just parks. The lever claims that park after its
   * ~250ms grace, roughly 8x sooner than the deferred EOF at 2s, which makes
   * [McpSubprocessExitedDuringHandshakeException] the outcome and pins the exit code (0 — a clean exit
   * that never served MCP) too. Verified by mutation: disable the lever and this is the only test in
   * the class that goes red — it then burns all five attempts and reports `McpException` once each
   * grandchild finally releases stdout at 2s.
   *
   * The 30s bound is not what ends the call here; it only has to stay clear of the fixture so the
   * timeout racer never enters the picture.
   *
   * The retry is on FIXTURE SETUP, not on the assertion. Two things can cost an attempt, and neither
   * is a defect in the lever: `sleep 2 &` is a fork the shell can lose (measured at roughly 1 in 100,
   * the grandchild never takes the descriptor, EOF arrives at once), and a badly starved runner could
   * in principle spend the whole 2s window between the grace elapsing and the CAS. Either way the
   * attempt is discarded and the fixture is rebuilt. A real lever regression misses every attempt, so
   * retrying costs nothing there but the 10s the mutation run above took.
   */
  @Test fun `connect surfaces the exit lever when the subprocess exits but its stdout stays open`() {
    assumeTrue("POSIX `sh` is required to background a stdout-holding grandchild", binSh != null)
    runBlocking {
      var lastOutcome: Throwable? = null
      repeat(FIXTURE_ATTEMPTS) {
        val process = ProcessBuilder(binSh!!.absolutePath, "-c", "sleep 2 & exit 0")
          .redirectErrorStream(false)
          .start()
        try {
          val spawned = SpawnedProcess(
            process = process,
            scriptFile = binSh,
            argv = listOf(binSh.absolutePath, "-c", "sleep 2 & exit 0"),
          )
          val capture = StderrCapture()

          val thrown = assertFailsWith<Throwable> {
            McpSubprocessSession.connect(
              spawnedProcess = spawned,
              stderrCapture = capture,
              handshakeTimeoutMillis = 30_000,
            )
          }
          lastOutcome = thrown
          if (thrown !is McpSubprocessExitedDuringHandshakeException) {
            // The organic EOF path answered first — the grandchild lost the fork race, or the run was
            // starved long enough for the deferred EOF to arrive first. Either way the fixture failed
            // to hold rather than the lever failing to fire, so rebuild it and try again.
            return@repeat
          }

          assertThat(thrown.scriptName).isEqualTo(binSh!!.name)
          // `exit 0`: the script ran to completion without ever serving MCP. This is the clean-exit
          // half of the error's contract, the half a crash-only fixture would never reach.
          assertThat(thrown.exitCode).isEqualTo(0)
          // Teardown still ran on the lever's path — no orphan of the direct child, capture closed.
          assertThat(process.isAlive).isEqualTo(false)
          assertThat(capture.isClosed).isEqualTo(true)
          return@runBlocking
        } finally {
          process.destroyForcibly()
        }
      }
      throw AssertionError(
        "The exit lever never claimed the handshake in $FIXTURE_ATTEMPTS attempts — last outcome was " +
          "${lastOutcome?.let { it::class.simpleName }}: ${lastOutcome?.message}. Either the lever " +
          "regressed, or `sleep 2 &` stopped holding the stdout descriptor on this platform.",
      )
    }
  }

  /**
   * The died-during-handshake error is what an author sees when their script throws at module scope,
   * so its text has to carry the two things that resolve it: which script, and the exit code that
   * says the script itself failed to boot (vs. a clean exit). Pins the unknown-code rendering too —
   * that branch omits the parenthetical rather than printing "exit code null".
   */
  @Test fun `the exited-during-handshake error names the script and its exit code`() {
    val withCode = McpSubprocessExitedDuringHandshakeException("tools.ts", 1)
    assertThat(withCode.scriptName).isEqualTo("tools.ts")
    assertThat(withCode.exitCode).isEqualTo(1)
    assertThat(withCode.message).isEqualTo(
      "MCP subprocess 'tools.ts' exited during its initialize handshake (exit code 1)" +
        " — a non-zero code means the script failed (see its stderr); zero means it exited without" +
        " serving MCP",
    )

    val withoutCode = McpSubprocessExitedDuringHandshakeException("tools.ts", null)
    assertThat(withoutCode.message).isEqualTo(
      "MCP subprocess 'tools.ts' exited during its initialize handshake" +
        " — a non-zero code means the script failed (see its stderr); zero means it exited without" +
        " serving MCP",
    )
  }

  /**
   * The exit lever's grace must stay strictly inside the bound it runs under, or a short bound
   * silently disables it: the timeout racer claims the outcome while the lever is still waiting, and
   * an instantly-exited subprocess is reported as having blown the bound — the misleading error the
   * lever exists to remove. Reachable, not theoretical: the env override and the parameter both take
   * any positive value, and the timeout test in this class passes 250ms.
   */
  @Test fun `the dead-subprocess grace always leaves the exit lever room inside the bound`() {
    // Generous bounds keep the full grace — nothing to scale down.
    assertThat(McpSubprocessSession.deadSubprocessGraceMs(60_000)).isEqualTo(250L)
    assertThat(McpSubprocessSession.deadSubprocessGraceMs(500)).isEqualTo(250L)

    // At or below twice the grace it scales down, landing strictly under the bound.
    assertThat(McpSubprocessSession.deadSubprocessGraceMs(250)).isEqualTo(125L)
    assertThat(McpSubprocessSession.deadSubprocessGraceMs(10)).isEqualTo(5L)
    // Floored at 1ms rather than 0: `delay(0)` would claim instantly and reintroduce the very steal
    // of an about-to-succeed handshake that the grace prevents. This is the one bound where the floor
    // makes grace == bound, so the two racers tie — accepted, because no subprocess can answer an
    // initialize handshake in 1ms and both errors describe the same failure.
    assertThat(McpSubprocessSession.deadSubprocessGraceMs(1)).isEqualTo(1L)
  }

  /**
   * A non-positive handshake bound is a programming error: `delay(<=0)` returns immediately, so a
   * watchdog armed with it would force-kill every subprocess before it could answer. [connect] must
   * reject it up front rather than arm that self-defeating watchdog. Deterministic and `bun`-free —
   * the precondition fires before any I/O, so any live process suffices to build the [SpawnedProcess].
   */
  @Test fun `connect rejects a non-positive handshake timeout`() {
    assumeTrue("POSIX `sleep` is required to construct a live SpawnedProcess", binSleep != null)
    val process = ProcessBuilder(binSleep!!.absolutePath, "30").redirectErrorStream(false).start()
    try {
      val spawned = SpawnedProcess(
        process = process,
        scriptFile = binSleep,
        argv = listOf(binSleep.absolutePath, "30"),
      )
      runBlocking {
        assertFailure {
          McpSubprocessSession.connect(spawnedProcess = spawned, handshakeTimeoutMillis = 0)
        }.isInstanceOf(IllegalArgumentException::class).messageContains("must be positive")
      }
    } finally {
      process.destroyForcibly()
    }
  }

  private companion object {
    /**
     * Attempts allowed for the stdout-holding grandchild fixture. Sized off a measured ~1-in-100
     * fork loss: five attempts puts a false failure far below the noise floor of the suite, while
     * still failing fast when the lever itself is gone (every attempt misses, not just one).
     */
    const val FIXTURE_ATTEMPTS = 5

    /** First of [candidates] that exists and is executable, or null if none (e.g. Windows). */
    fun firstExecutable(vararg candidates: String): File? =
      candidates.map { File(it) }.firstOrNull { it.canExecute() }
  }
}
