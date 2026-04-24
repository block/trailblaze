package xyz.block.trailblaze.scripting.subprocess

import assertk.assertFailure
import assertk.assertThat
import assertk.assertions.isEqualTo
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import java.io.File
import kotlin.test.Test

/**
 * Verifies [McpSubprocessSession.connect] doesn't leak the subprocess when the MCP
 * `initialize` handshake fails. Uses `/bin/true` as a minimal subprocess: it exits
 * immediately, closing stdin/stdout/stderr, which makes the SDK client's initialize
 * request fail with EOF. Our cleanup path should close the client, close the stderr
 * capture, and (if needed) destroy the process before rethrowing.
 *
 * Gated on POSIX `/bin/true` availability — skipped on Windows (which isn't a supported
 * Trailblaze development target today anyway).
 */
class McpSubprocessSessionConnectCleanupTest {

  private val binTrue = File("/bin/true")

  @Test fun `connect cleans up subprocess when initialize fails`() {
    assumeTrue("POSIX /bin/true is required to exercise the cleanup path", binTrue.canExecute())
    runBlocking {
      val process = ProcessBuilder("/bin/true").redirectErrorStream(false).start()
      val spawned = SpawnedProcess(
        process = process,
        scriptFile = binTrue,
        argv = listOf(binTrue.absolutePath),
      )
      // Explicit capture instance so the test can assert close() was reached. If the
      // cleanup regressed to skip it, a file-backed capture would leak silently because
      // StderrCapture.close() swallows write errors via runCatching.
      val capture = StderrCapture()

      assertFailure {
        McpSubprocessSession.connect(spawnedProcess = spawned, stderrCapture = capture)
      }
      // Cleanup ran. /bin/true exits on its own, so isAlive is false regardless of whether
      // we actually had to destroy() — the guarantee we want to assert is "no orphan".
      assertThat(process.isAlive).isEqualTo(false)
      // And the stderr capture was closed along the way — otherwise a file-backed variant
      // would leak its FileWriter handle and there'd be no test catching it.
      assertThat(capture.isClosed).isEqualTo(true)
    }
  }
}
