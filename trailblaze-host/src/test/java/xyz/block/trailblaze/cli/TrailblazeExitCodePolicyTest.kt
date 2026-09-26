package xyz.block.trailblaze.cli

import io.ktor.client.network.sockets.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import picocli.CommandLine
import xyz.block.trailblaze.logs.server.endpoints.CliRunResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
    val commandLine = CommandLine(RequiresArgTestCommand())
    installTrailblazeExceptionHandlers(commandLine)
    val exit = commandLine.execute()
    assertEquals(TrailblazeExitCode.MISUSE.code, exit)
  }

  @CommandLine.Command(name = "requires-arg-test")
  private class RequiresArgTestCommand : java.util.concurrent.Callable<Int> {
    @CommandLine.Parameters(index = "0", description = ["a required positional arg"])
    lateinit var required: String

    override fun call(): Int = TrailblazeExitCode.SUCCESS.code
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
  // daemonRunFailureExitCode — failure-class mapping for daemon-delegated runs
  // ---------------------------------------------------------------------------

  @Test
  fun `daemon misuse rejection maps to MISUSE`() {
    val response = CliRunResponse(
      success = false,
      error = "unknown driver type 'axe'",
      errorKind = CliRunResponse.ERROR_KIND_MISUSE,
    )
    assertEquals(TrailblazeExitCode.MISUSE, daemonRunFailureExitCode(response))
  }

  @Test
  fun `a run the client lost sight of maps to INFRA_FAILED`() {
    // The run may still be driving its device, so a scheduler must not treat the device as free.
    val response = CliRunResponse(
      success = false,
      error = "Timed out waiting for run to complete: no progress for 600s",
      errorKind = CliRunResponse.ERROR_KIND_INFRA,
    )
    assertEquals(TrailblazeExitCode.INFRA_FAILED, daemonRunFailureExitCode(response))
  }

  @Test
  fun `daemon failure without an errorKind maps to ASSERTION_FAILED`() {
    // Covers ordinary attempted-run failures AND responses from older daemons
    // that don't send the field.
    val response = CliRunResponse(success = false, error = "assertion failed")
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED, daemonRunFailureExitCode(response))
  }

  @Test
  fun `daemon failure with an unrecognized errorKind maps to ASSERTION_FAILED`() {
    // A future daemon sending a kind this CLI doesn't know must degrade to the
    // attempted-run default, never to success or a random code.
    val response = CliRunResponse(success = false, error = "boom", errorKind = "some-future-kind")
    assertEquals(TrailblazeExitCode.ASSERTION_FAILED, daemonRunFailureExitCode(response))
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

  // ---------------------------------------------------------------------------
  // ioFailureHint - which of the two hints each transport failure earns. "The daemon accepted a
  // request it has not answered yet" and "nothing is listening at all" are opposite
  // instructions, and the CLI only gets to print one. Every type Ktor's OkHttp engine can hand
  // us is pinned here by type, not by message, because the engine is what chooses the type.
  // ---------------------------------------------------------------------------

  @Test
  fun `a response timeout says the daemon is still working, not that it is down`() {
    // A target launch tool or an agent loop can outlast the per-request budget while the daemon
    // keeps working. Sending that user to restart the daemon would kill their own command, so
    // the hint must name the wait, not the daemon's health.
    val hint = ioFailureHint(
      java.net.SocketTimeoutException("Socket timeout has expired [url=http://localhost:52525/mcp, socket_timeout=180000] ms"),
    )
    assertTrue(hint.contains("still working on this command"), hint)
    assertTrue(hint.contains(CliMcpClient.REQUEST_TIMEOUT_ENV), hint)
    assertFalse(hint.contains("trailblaze app start"), hint)
  }

  @Test
  fun `a whole-request timeout says the daemon is still working`() {
    // Ktor's HttpTimeout plugin raises this when `requestTimeoutMillis` expires. CliMcpClient
    // sets that budget and the socket read budget to the same value, so which of the two fires
    // is a race - and both mean the daemon has the request. Safe to read that way only because
    // CliMcpClient.connectTimeoutMsFor keeps the connect budget under the request budget, so
    // this type can never mean "never connected".
    val hint = ioFailureHint(HttpRequestTimeoutException("http://localhost:52525/mcp", 180_000L))
    assertTrue(hint.contains("still working on this command"), hint)
    assertFalse(hint.contains("trailblaze app start"), hint)
  }

  @Test
  fun `a ktor connect timeout keeps the daemon-down hint`() {
    // The engine's own classification: a connect timeout arrives as this type, never as a
    // SocketTimeoutException, so the hint follows the type rather than the message.
    val hint = ioFailureHint(ConnectTimeoutException("http://localhost:52525/mcp"))
    assertEquals("is the Trailblaze daemon running? try `trailblaze app start`", hint)
  }

  @Test
  fun `a bare connect timeout outside the engine mapping keeps the daemon-down hint`() {
    val hint = ioFailureHint(java.net.SocketTimeoutException("connect timed out"))
    assertTrue(hint.contains("trailblaze app start"), hint)
  }

  @Test
  fun `a refused connection keeps the daemon-down hint`() {
    val hint = ioFailureHint(java.net.ConnectException("Connection refused"))
    assertTrue(hint.contains("trailblaze app start"), hint)
  }

  @Test
  fun `an unclassified IO failure keeps the daemon-down hint`() {
    val hint = ioFailureHint(java.io.IOException("unexpected end of stream"))
    assertTrue(hint.contains("trailblaze app start"), hint)
  }

  @Test
  fun `a timeout with no message is treated as the daemon still working`() {
    // Only a connect failure can claim the daemon is gone, and the engine gives connect failures
    // their own type. A message-less read timeout must not be guessed into the down hint.
    val hint = ioFailureHint(java.net.SocketTimeoutException())
    assertTrue(hint.contains("still working on this command"), hint)
  }

  @Test
  fun `the envelope a timed-out command prints carries the still-working hint`() {
    // Pins the wiring, not just the classifier: a change that stopped passing ioFailureHint into
    // reportCliError would leave every test above green while the user still read "is the
    // daemon running?".
    val captured = captureConsole {
      kotlinx.coroutines.runBlocking {
        runActionWithIoEnvelope(target = "android", verb = "Tool") {
          throw java.net.SocketTimeoutException("Socket timeout has expired [url=http://localhost:52525/mcp, socket_timeout=180000] ms")
        }
      }
    }
    assertEquals(TrailblazeExitCode.INFRA_FAILED.code, captured.result)
    assertTrue(captured.err.contains("hint: the daemon is still working on this command"), captured.err)
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
}
