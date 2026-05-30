package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the four-class exit-code policy: SUCCESS=0, ASSERTION_FAILED=1,
 * INFRA_FAILED=2, MISUSE=3. Every non-AI CLI command is expected to map onto
 * one of these via [TrailblazeExitCode]; this suite covers representative
 * sites for each class.
 *
 * Out of scope (per the policy memo): the AI commands `blaze`/`ask`/`verify`,
 * which are currently hidden behind a feature flag — their exit-code wiring
 * will be exercised when they're surfaced.
 */
class TrailblazeExitCodePolicyTest {

  // ---------------------------------------------------------------------------
  // Enum values
  // ---------------------------------------------------------------------------

  @Test
  fun `SUCCESS is 0`() {
    assertEquals(0, TrailblazeExitCode.SUCCESS.code)
  }

  @Test
  fun `ASSERTION_FAILED is 1`() {
    assertEquals(1, TrailblazeExitCode.ASSERTION_FAILED.code)
  }

  @Test
  fun `INFRA_FAILED is 2`() {
    assertEquals(2, TrailblazeExitCode.INFRA_FAILED.code)
  }

  @Test
  fun `MISUSE is 3`() {
    assertEquals(3, TrailblazeExitCode.MISUSE.code)
  }

  @Test
  fun `every enum value has a distinct code`() {
    val codes = TrailblazeExitCode.entries.map { it.code }
    assertEquals(codes.size, codes.toSet().size, "exit codes must be unique")
  }

  // ---------------------------------------------------------------------------
  // SnapshotCommand — exercises the rejection branch (no --device, no env)
  // ---------------------------------------------------------------------------

  @Test
  fun `snapshot with no --device rejects via MISUSE or INFRA_FAILED`() {
    // Skip guards keep the test deterministic across environments — env-aware
    // (TRAILBLAZE_DEVICE) and autodetect-success (single connected device,
    // post-#3456) both proceed into command traffic that a unit test can't
    // reach. The rejection contract is what's pinned here.
    if (!System.getenv("TRAILBLAZE_DEVICE").isNullOrBlank()) return
    if (canAutoresolveSingleDevice) return
    val cmd = SnapshotCommand()
    val exit = cmd.call()
    assertRejectsBareDeviceInvocation(exit)
  }

  // ---------------------------------------------------------------------------
  // Top-level exception handlers — installed on every CommandLine
  // ---------------------------------------------------------------------------

  @Test
  fun `installTrailblazeExceptionHandlers maps parameter errors to MISUSE`() {
    // A command that has a required positional arg; invoking it without that
    // arg triggers picocli's MissingParameterException path. With our
    // handlers installed, that exit code should be MISUSE.code (3), not
    // picocli's default USAGE = 2.
    val cmd = MigrateTrailsCommand()
    val commandLine = CommandLine(cmd)
    installTrailblazeExceptionHandlers(commandLine)
    val exit = commandLine.execute()
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @Test
  fun `installTrailblazeExceptionHandlers maps uncaught exceptions to INFRA_FAILED`() {
    // A command whose `call()` throws an unhandled exception — the
    // IExecutionExceptionHandler should catch it and emit INFRA_FAILED.
    val commandLine = CommandLine(ThrowingTestCommand())
    installTrailblazeExceptionHandlers(commandLine)
    val exit = commandLine.execute()
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, exit)
  }

  @CommandLine.Command(name = "throwing-test")
  private class ThrowingTestCommand : java.util.concurrent.Callable<Int> {
    override fun call(): Int = throw java.net.SocketTimeoutException("simulated")
  }

  // ---------------------------------------------------------------------------
  // describeThrowableForUser — replaces raw stack-trace surfacing
  // ---------------------------------------------------------------------------

  @Test
  fun `describeThrowableForUser dresses SocketTimeoutException`() {
    val msg = describeThrowableForUser(java.net.SocketTimeoutException("connect timed out"))
    assertEquals("network request timed out (connect timed out)", msg)
  }

  @Test
  fun `describeThrowableForUser dresses ConnectException`() {
    val msg = describeThrowableForUser(java.net.ConnectException("Connection refused"))
    assertEquals("could not connect (Connection refused)", msg)
  }

  @Test
  fun `describeThrowableForUser falls back to simpleName when message is blank`() {
    val msg = describeThrowableForUser(IllegalStateException())
    assertEquals("IllegalStateException", msg)
  }

  @Test
  fun `describeThrowableForUser collapses multi-line messages onto one line`() {
    // A YAML parse error, an indented stack trace inside a wrapped cause, or any
    // exception whose message spans lines must not break the envelope's
    // one-line-per-field contract.
    val msg = describeThrowableForUser(IllegalArgumentException("yaml parse failed:\n  line 3: unexpected\n  line 4: token"))
    assertEquals("yaml parse failed: line 3: unexpected line 4: token", msg)
  }

  // ---------------------------------------------------------------------------
  // runActionWithIoEnvelope — the headline chip claim: SocketTimeoutException
  // from inside a CLI action must NOT leak to the user as a stack trace.
  // These tests invoke the *production* wrapper (no test-side double) so a
  // future change that narrows the catch (e.g. to SocketTimeoutException only)
  // would fail the IOException test below.
  // ---------------------------------------------------------------------------

  @Test
  fun `runActionWithIoEnvelope catches SocketTimeoutException as INFRA_FAILED`() {
    val exitCode = kotlinx.coroutines.runBlocking {
      runActionWithIoEnvelope(target = "android") {
        throw java.net.SocketTimeoutException("connect timed out")
      }
    }
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, exitCode)
  }

  @Test
  fun `runActionWithIoEnvelope catches ConnectException as INFRA_FAILED`() {
    val exitCode = kotlinx.coroutines.runBlocking {
      runActionWithIoEnvelope(target = "android") {
        throw java.net.ConnectException("Connection refused")
      }
    }
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, exitCode)
  }

  @Test
  fun `runActionWithIoEnvelope catches generic IOException as INFRA_FAILED`() {
    val exitCode = kotlinx.coroutines.runBlocking {
      runActionWithIoEnvelope(target = null) {
        throw java.io.IOException("disk full")
      }
    }
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, exitCode)
  }

  @Test
  fun `runActionWithIoEnvelope passes through normal action returns`() {
    val exitCode = kotlinx.coroutines.runBlocking {
      runActionWithIoEnvelope(target = "android") { TrailblazeExitCode.SUCCESS.code }
    }
    assertEquals(TrailblazeExitCode.SUCCESS.code, exitCode)
  }

  @Test
  fun `runActionWithIoEnvelope does NOT catch non-IO exceptions`() {
    // Non-IO exceptions are programmer errors, not transport failures — they
    // must continue to escape so the top-level execution-exception handler can
    // produce its own envelope (or so a unit test sees the original failure).
    // Pinning this behavior prevents a future "just catch Throwable" change
    // from silently masking bugs as INFRA_FAILED.
    val ex = kotlin.runCatching {
      kotlinx.coroutines.runBlocking {
        runActionWithIoEnvelope(target = null) {
          throw IllegalStateException("programmer bug")
        }
      }
    }.exceptionOrNull()
    assertEquals(IllegalStateException::class, ex?.let { it::class })
  }

  // ---------------------------------------------------------------------------
  // chooseWorseExitCode — batch aggregation must not collapse codes
  // ---------------------------------------------------------------------------

  @Test
  fun `chooseWorseExitCode picks INFRA_FAILED over ASSERTION_FAILED`() {
    assertEquals(
      TrailblazeExitCode.INFRA_FAILED.code,
      chooseWorseExitCode(
        TrailblazeExitCode.ASSERTION_FAILED.code,
        TrailblazeExitCode.INFRA_FAILED.code,
      ),
    )
    assertEquals(
      TrailblazeExitCode.INFRA_FAILED.code,
      chooseWorseExitCode(
        TrailblazeExitCode.INFRA_FAILED.code,
        TrailblazeExitCode.ASSERTION_FAILED.code,
      ),
    )
  }

  @Test
  fun `chooseWorseExitCode picks ASSERTION_FAILED over SUCCESS`() {
    assertEquals(
      TrailblazeExitCode.ASSERTION_FAILED.code,
      chooseWorseExitCode(TrailblazeExitCode.SUCCESS.code, TrailblazeExitCode.ASSERTION_FAILED.code),
    )
  }

  @Test
  fun `chooseWorseExitCode preserves SUCCESS when both are SUCCESS`() {
    assertEquals(
      TrailblazeExitCode.SUCCESS.code,
      chooseWorseExitCode(TrailblazeExitCode.SUCCESS.code, TrailblazeExitCode.SUCCESS.code),
    )
  }

  @Test
  fun `chooseWorseExitCode treats unknown non-zero codes as INFRA-tier`() {
    // A legacy `return 1` from a path that hasn't migrated yet is `1` numerically
    // but semantically unknown — treat it as the worst tier so a chained
    // `&& deploy` can't silently green-light because of it.
    val unknown = 99
    assertEquals(unknown, chooseWorseExitCode(TrailblazeExitCode.ASSERTION_FAILED.code, unknown))
  }

  // Note: An earlier reflection-based `MigrateTrailsCommand EXIT_FAILURE aliases
  // ASSERTION_FAILED` test lived here, but its `getOrElse` fallback returned the
  // expected value unconditionally — so a broken reflection lookup would never
  // fail the assertion (the lead-dev review flagged this). Removed in favor of
  // the behavior tests in `MigrateTrailsCommandTest` and `CompileCommandTest`,
  // which already exercise the full command paths end-to-end and would catch a
  // future change to the EXIT_FAILURE constant.
}
