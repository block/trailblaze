package xyz.block.trailblaze.scripting.subprocess

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isInstanceOf
import assertk.assertions.isLessThan
import assertk.assertions.isNotInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.messageContains
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.system.measureTimeMillis
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

  private val binTrue = firstExecutable("/bin/true", "/usr/bin/true")
  private val binSleep = firstExecutable("/bin/sleep", "/usr/bin/sleep")

  @Test fun `connect cleans up subprocess when initialize fails`() {
    assumeTrue("POSIX `true` is required to exercise the cleanup path", binTrue != null)
    runBlocking {
      val process = ProcessBuilder(binTrue!!.absolutePath).redirectErrorStream(false).start()
      val spawned = SpawnedProcess(
        process = process,
        scriptFile = binTrue,
        argv = listOf(binTrue.absolutePath),
      )
      // Explicit capture instance so the test can assert close() was reached. If the
      // cleanup regressed to skip it, a file-backed capture would leak silently because
      // StderrCapture.close() swallows write errors via runCatching.
      val capture = StderrCapture()

      // An immediate EOF is an organic handshake failure, not a timeout — connect must propagate
      // the underlying error unchanged, never re-attribute it as a handshake timeout.
      assertFailure {
        McpSubprocessSession.connect(spawnedProcess = spawned, stderrCapture = capture)
      }.isNotInstanceOf(McpSubprocessHandshakeTimeoutException::class)
      // Cleanup ran. `true` exits on its own, so isAlive is false regardless of whether
      // we actually had to destroy() — the guarantee we want to assert is "no orphan".
      assertThat(process.isAlive).isEqualTo(false)
      // And the stderr capture was closed along the way — otherwise a file-backed variant
      // would leak its FileWriter handle and there'd be no test catching it.
      assertThat(capture.isClosed).isEqualTo(true)
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
   * does NOT fire until the subprocess exits on its own (empirically ~30s here). The watchdog
   * force-destroys the subprocess at the timeout, unblocking that read. If the fix regressed to
   * a bare `withTimeout`, `elapsedMs` would jump to ~30s and this test would fail.
   *
   * Gated on POSIX `sleep` availability — skipped on Windows.
   */
  @Test fun `connect times out and destroys the subprocess when the handshake never completes`() {
    assumeTrue("POSIX `sleep` is required to exercise the handshake-timeout path", binSleep != null)
    runBlocking {
      val process = ProcessBuilder(binSleep!!.absolutePath, "30").redirectErrorStream(false).start()
      val spawned = SpawnedProcess(
        process = process,
        scriptFile = binSleep,
        argv = listOf(binSleep.absolutePath, "30"),
      )
      val capture = StderrCapture()

      lateinit var thrown: McpSubprocessHandshakeTimeoutException
      val elapsedMs = measureTimeMillis {
        thrown = assertFailsWith<McpSubprocessHandshakeTimeoutException> {
          McpSubprocessSession.connect(
            spawnedProcess = spawned,
            stderrCapture = capture,
            handshakeTimeoutMillis = 250,
          )
        }
      }

      // Failed fast on the 250ms bound plus the bounded teardown ladder — nowhere near the 30s
      // sleep, so the handshake was genuinely cancelled rather than run to completion.
      assertThat(elapsedMs).isLessThan(20_000L)
      // The subprocess was torn down by the timeout cleanup — no orphan left behind.
      assertThat(process.isAlive).isEqualTo(false)
      // And the stderr capture was closed on the way out.
      assertThat(capture.isClosed).isEqualTo(true)
      // Attributable: the timeout names the culprit script and the bound it blew, and preserves the
      // underlying stream-closed read error as the cause so the daemon log keeps the root failure.
      assertThat(thrown.scriptName).isEqualTo(binSleep!!.name)
      assertThat(thrown.timeoutMillis).isEqualTo(250L)
      assertThat(thrown.cause).isNotNull()
    }
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
    /** First of [candidates] that exists and is executable, or null if none (e.g. Windows). */
    fun firstExecutable(vararg candidates: String): File? =
      candidates.map { File(it) }.firstOrNull { it.canExecute() }
  }
}
