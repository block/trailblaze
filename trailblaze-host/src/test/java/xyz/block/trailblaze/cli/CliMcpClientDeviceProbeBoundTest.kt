package xyz.block.trailblaze.cli

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The persisted-session check in [CliMcpClient.connectReusable] is a read-only `device` INFO probe
 * the user did not ask for. It has its own short bound, [CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS],
 * instead of inheriting the request deadline that is sized for a composed tool call: a daemon that
 * is alive but starved of IO slots keeps serving HTTP and never answers the probe, and without the
 * bound a `tap` would sit for the whole composed-tool budget before the tap was even sent.
 */
class CliMcpClientDeviceProbeBoundTest {

  private val createdFiles = mutableListOf<java.io.File>()

  @AfterTest
  fun cleanup() {
    createdFiles.forEach { it.delete() }
  }

  @Test
  fun `a persisted session whose INFO probe never answers fails within the probe bound`() {
    StarvedDaemonStub.start().use { daemon ->
      val sessionFile = CliMcpClient.sessionFile(daemon.port).also { createdFiles += it }
      sessionFile.writeText("persisted-session\nsampleapp")

      val startedAt = System.nanoTime()
      val failure = assertFailsWith<CliMcpClient.DaemonStarvedException> {
        runUnderPreflightGuard {
          CliMcpClient.connectReusable(port = daemon.port, targetAppId = "sampleapp").close()
        }
      }
      val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

      assertEquals(1, daemon.deviceCalls.get(), "the probe must have reached the daemon before the bound cut it off")
      assertTrue(
        elapsedMs < PREFLIGHT_GUARD_MS,
        "probe returned after ${elapsedMs}ms; the bound is ${CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS}ms",
      )

      val message = failure.message.orEmpty()
      assertTrue(
        "accepted the connection" in message && "did not answer" in message,
        "message should name the symptom: $message",
      )
      assertTrue("trailblaze status" in message, "message should point at `trailblaze status`: $message")
      assertTrue("TRAILBLAZE_IO_PARALLELISM" in message, "message should point at TRAILBLAZE_IO_PARALLELISM: $message")

      // The daemon is up, so the session it holds is very likely still alive: no replacement is
      // minted, and the pointer the next command will read is untouched.
      assertEquals(0, daemon.initializeCalls.get(), "must not open a replacement session on a starved daemon")
      assertEquals("persisted-session\nsampleapp", sessionFile.readText())
    }
  }

  /**
   * The production entry point wraps [CliMcpClient.connectReusable] in a catch-all that clears the
   * saved scope, auto-starts, and reconnects. Applied to the probe verdict that would orphan the
   * live session and then hang the fresh handshake for the full request deadline — the wait the
   * bound exists to prevent — so the wrapper has to recognise the verdict and do neither.
   */
  @Test
  fun `the connect-or-start wrapper neither clears the scope nor reconnects on a probe timeout`() {
    StarvedDaemonStub.start().use { daemon ->
      val sessionFile = CliMcpClient.sessionFile(daemon.port).also { createdFiles += it }
      sessionFile.writeText("persisted-session\nsampleapp")

      val startedAt = System.nanoTime()
      val client = runUnderPreflightGuard { connectOrStartDaemonReusable(daemon.port, targetAppId = "sampleapp") }
      val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

      assertEquals(null, client, "a starved daemon is reported, not handed back as a connection")
      assertEquals(1, daemon.deviceCalls.get(), "exactly one probe: no second connectReusable after the verdict")
      assertEquals(0, daemon.initializeCalls.get(), "no replacement session minted")
      assertEquals("persisted-session\nsampleapp", sessionFile.readText(), "the saved scope survives")
      assertTrue(
        elapsedMs < PREFLIGHT_GUARD_MS,
        "wrapper returned after ${elapsedMs}ms; the bound is ${CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS}ms",
      )
    }
  }

  /**
   * The handshake is the first `/mcp` POST of every command and waits for an IO slot like the
   * probes after it, so it carries the same bound and the same distinct verdict: a plain connect
   * failure would send the connect-or-start wrappers off to auto-start a daemon that is running.
   */
  @Test
  fun `a one-shot connect whose handshake never answers fails as starved within the bound`() {
    StarvedDaemonStub.start(handshake = StarvedDaemonStub.HandshakeMode.PARKS).use { daemon ->
      val startedAt = System.nanoTime()
      val failure = assertFailsWith<CliMcpClient.DaemonStarvedException> {
        runUnderPreflightGuard { CliMcpClient.connectOneShot(port = daemon.port).close() }
      }
      val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000

      assertEquals(1, daemon.initializeCalls.get(), "the handshake must have reached the daemon")
      assertTrue(
        elapsedMs < PREFLIGHT_GUARD_MS,
        "connect returned after ${elapsedMs}ms; the bound is ${CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS}ms",
      )
      assertTrue("initialize" in failure.message.orEmpty(), "message should name the handshake: ${failure.message}")
    }
  }

  /**
   * A cancelled command is not a connect failure. The distinction matters past tidiness: the
   * connect-or-start wrappers react to a connect failure by starting a daemon, so losing the
   * cancellation here would spawn a daemon for a command the user just interrupted.
   */
  @Test
  fun `ctrl-c during a stalled handshake propagates as cancellation not a connect failure`() {
    StarvedDaemonStub.start(handshake = StarvedDaemonStub.HandshakeMode.PARKS).use { daemon ->
      assertFailsWith<CancellationException> {
        runUnderPreflightGuard {
          coroutineScope {
            val connect = async { CliMcpClient.connectOneShot(port = daemon.port).close() }
            // Well inside the pre-flight bound, so the cancellation lands while the handshake is
            // still in flight rather than after it has already produced the starved verdict.
            delay(500)
            connect.cancel()
            connect.await()
          }
        }
      }
      assertEquals(1, daemon.initializeCalls.get(), "the handshake was in flight when the cancel landed")
    }
  }

  /**
   * A reusable client normally leaves its session alive on close — the pointer file is the whole
   * point. Not when the handshake never finished: the bound can expire after `initialize` has
   * minted a session and before the pointer is written, and nobody can ever reattach to a session
   * whose id was never saved.
   */
  @Test
  fun `a session created by a handshake that never finished is terminated, not orphaned`() {
    StarvedDaemonStub.start(
      handshake = StarvedDaemonStub.HandshakeMode.PARKS_AFTER_INITIALIZE,
    ).use { daemon ->
      val sessionFile = CliMcpClient.sessionFile(daemon.port).also { createdFiles += it }
      sessionFile.delete()

      assertFailsWith<CliMcpClient.DaemonStarvedException> {
        runUnderPreflightGuard { CliMcpClient.connectReusable(port = daemon.port).close() }
      }

      assertEquals(1, daemon.initializeCalls.get(), "the daemon minted a session before it stalled")
      assertEquals(1, daemon.deleteCalls.get(), "that session must be terminated, since nothing can reattach to it")
      assertEquals(false, sessionFile.exists(), "no pointer is written for a handshake that did not finish")
    }
  }

  /**
   * `connectReusableOrNull` returning null means "this scope has no live session", and callers are
   * entitled to answer that with `No active session.` and exit 0. A daemon that accepted the
   * connection and never replied has established nothing about the scope, so it must not be
   * flattened into that answer.
   */
  @Test
  fun `the nullable connect reports a starved daemon instead of an absent session`() {
    StarvedDaemonStub.start().use { daemon ->
      // A saved pointer, so the INFO probe actually runs. Without one, a non-creating connect
      // answers "no session" off the missing file and never reaches the daemon.
      val sessionFile = CliMcpClient.sessionFile(daemon.port).also { createdFiles += it }
      sessionFile.writeText("persisted-session\nsampleapp")

      assertFailsWith<CliMcpClient.DaemonStarvedException> {
        runUnderPreflightGuard {
          connectReusableOrNull(daemon.port, sessionScope = null, createIfMissing = false)
        }
      }
      assertEquals(1, daemon.deviceCalls.get(), "the probe reached the daemon and was cut off by the bound")
    }
  }

  @Test
  fun `the probe bound is a fraction of the request deadline and the proxy shares it`() {
    // The bound only helps if it is meaningfully shorter than the deadline it replaces; and the
    // proxy's copy of the LIST probe must stay on the same number so the three pre-flight probes
    // cannot drift apart.
    assertTrue(CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS * 10 <= CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS)
    assertEquals(CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS, McpProxy.AUTODETECT_PROBE_TIMEOUT_MS)
  }

  /**
   * A pre-flight bound above the request deadline could never fire: the deadline it is carving a
   * window out of would expire first, and the caller would get a generic connect failure instead of
   * the verdict that names the symptom. So the resolved bound is clamped, and an operator who
   * lowers the request timeout to triage a wedged device gets a pre-flight bound that still fits
   * inside it.
   */
  @Test
  fun `the resolved bound never exceeds the request deadline it sits inside`() {
    // Both request timeouts are passed rather than resolved, so an exported
    // TRAILBLAZE_MCP_REQUEST_TIMEOUT_MS cannot move what this measures.
    val tightRequestTimeoutMs = 1_000L
    CliCallerContext.withCallerEnv(emptyMap()) {
      CliMcpClient(
        serverUrl = "http://localhost:1/mcp",
        requestTimeoutMs = tightRequestTimeoutMs,
      ).use { client ->
        assertEquals(tightRequestTimeoutMs, client.preflightTimeoutMs, "clamped to the tighter deadline")
      }
      CliMcpClient(
        serverUrl = "http://localhost:1/mcp",
        requestTimeoutMs = CliMcpClient.DEFAULT_REQUEST_TIMEOUT_MS,
      ).use { client ->
        assertEquals(
          CliMcpClient.DEFAULT_PREFLIGHT_TIMEOUT_MS,
          client.preflightTimeoutMs,
          "the clamp must not shorten the ordinary case",
        )
      }
    }
  }
}
