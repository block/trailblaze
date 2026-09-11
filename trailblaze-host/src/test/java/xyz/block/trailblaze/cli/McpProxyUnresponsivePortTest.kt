package xyz.block.trailblaze.cli

import org.junit.Test
import java.net.ServerSocket
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A daemon that wedges after binding its port still ACCEPTS connections, so `/ping` answers neither
 * yes nor no. The `trailblaze` launcher script hung on exactly this, and the proxy paid for it
 * differently: it started a daemon that could never bind the port, then polled for it until
 * `DAEMON_WAIT_TIMEOUT_SECONDS` ran out — a minute of silence before the MCP client's first
 * response.
 *
 * These are behavioral: a real socket that accepts and never writes, and a real closed port.
 * Nothing here asserts which exception the HTTP client raises, because that is the engine's
 * business — only that the two cases stay distinguishable, since they call for opposite responses.
 */
class McpProxyUnresponsivePortTest {

  /**
   * Above the range the daemon refuses (device ports) so [McpProxy.refuseDeviceAllocatablePort]
   * cannot pre-empt the probe this test is about.
   */
  private fun unusedPortOutsideDeviceRange(): Int {
    for (candidate in 59_600..59_699) {
      runCatching { ServerSocket(candidate) }.getOrNull()?.use { return it.localPort }
    }
    error("no free port for the wedged-listener test")
  }

  /** Accepts every connection and never answers, holding each one open. */
  private fun <T> withWedgedListener(body: (Int) -> T): T {
    ServerSocket(unusedPortOutsideDeviceRange()).use { server ->
      val held = mutableListOf<java.net.Socket>()
      val accepting = Thread {
        runCatching {
          while (true) held += server.accept()
        }
      }.apply { isDaemon = true; start() }
      try {
        return body(server.localPort)
      } finally {
        accepting.interrupt()
        held.forEach { runCatching { it.close() } }
      }
    }
  }

  @Test
  fun `a port that accepts and never answers is reported as held, not as free`() {
    withWedgedListener { port ->
      assertEquals(DaemonProbe.HELD_UNRESPONSIVE, McpProxy(port = port).probeDaemon())
    }
  }

  @Test
  fun `a port nothing is listening on is reported as free to wait on`() {
    // Closed before probing: a refused connection proves the port is available for a daemon, which
    // is the case that must keep waiting rather than fail fast.
    val closedPort = unusedPortOutsideDeviceRange()

    assertEquals(DaemonProbe.NO_ANSWER, McpProxy(port = closedPort).probeDaemon())
  }

  /**
   * The message identity is the assertion, not the elapsed time. Every Gradle `Test` task sets
   * `TRAILBLAZE_DISABLE_DAEMON_AUTOSTART=1` on purpose (`build.gradle.kts`) so a unit test can
   * never spawn a daemon, which means the startup poll this fix skips is already unreachable from
   * here — a timing assertion would pass with the fix reverted. What still separates the two is
   * WHICH short-circuit spoke: the held-port verdict names the port and how to find its owner,
   * while the kill-switch talks about an env var the user never set. The wall-clock half of this
   * contract is covered by an end-to-end launcher test, where the poll is reachable.
   */
  @Test
  fun `waitForDaemon reports the held port itself rather than falling through to another exit`() {
    withWedgedListener { port ->
      val proxy = McpProxy(port = port)
      val logs = mutableListOf<String>()

      // True, not false: the session stays alive so it can recover once the port frees up — a
      // later successful forward clears daemonStartupFailed. Only an unusable port is fatal.
      assertTrue(proxy.waitForDaemon { logs += it }, "a held port must not be fatal")

      assertTrue(
        proxy.daemonStartupFailed.get(),
        "the first forwarded request has to fail fast with the daemon-unavailable envelope",
      )
      val message = logs.single()
      assertTrue(message.contains("$port"), "expected the offending port; got: $message")
      assertTrue(
        message.contains("lsof"),
        "expected a way to find the owner rather than a kill recipe; got: $message",
      )
      assertTrue(
        !message.contains("TRAILBLAZE_DISABLE_DAEMON_AUTOSTART"),
        "the held port must be diagnosed as itself, not as a disabled auto-start; got: $message",
      )
    }
  }

  /**
   * Skipping the startup poll is not enough on its own. The first MCP request still POSTs to the
   * port, a held port accepts that POST, and it then goes quiet for the client's whole
   * `requestTimeoutMillis` — five minutes before the client's first response. The
   * `daemonStartupFailed` fast-fail cannot cover it, because that hangs off `ConnectException` and
   * nothing here refuses the connection. Elapsed time is a fair assertion in this one: no test
   * harness setting shortens it, only the pre-POST check does.
   */
  @Test
  fun `a held port answers the first request instead of posting into it`() {
    withWedgedListener { port ->
      val proxy = McpProxy(port = port)
      val logs = mutableListOf<String>()
      proxy.waitForDaemon { logs += it }
      assertTrue(proxy.daemonPortHeldUnresponsive.get(), "the held verdict has to be recorded")

      val startedAt = System.currentTimeMillis()
      val response = proxy.forwardRequest("""{"jsonrpc":"2.0","id":7,"method":"tools/list"}""") { logs += it }
      val elapsedMs = System.currentTimeMillis() - startedAt

      assertTrue(elapsedMs < 30_000, "posting into a held port takes 5 minutes; took ${elapsedMs}ms")
      val body = response ?: error("a request (not a notification) must get a response")
      assertTrue(body.contains("\"id\":7"), "the client's id has to come back; got: $body")
      assertTrue(body.contains("-32000"), "expected a JSON-RPC error envelope; got: $body")
      assertTrue(body.contains("$port"), "the envelope should name the port; got: $body")
      assertTrue(body.contains("lsof"), "the envelope should say how to find the owner; got: $body")
    }
  }

  /**
   * The recovery half of the same contract: the verdict is re-probed per request, not latched. A
   * cached "held" would leave the MCP session permanently broken after the user clears the port —
   * exactly the outcome the fast-fail is supposed to avoid.
   */
  @Test
  fun `the held verdict is dropped once the port is free again`() {
    // Nothing is listening, so this stands in for "the user killed the wedged process". Short retry
    // budget so the post-recovery path (which now legitimately fails to connect) returns promptly.
    val freedPort = unusedPortOutsideDeviceRange()
    val proxy = McpProxy(port = freedPort, retryIntervalMs = 10, maxRetryMs = 100)
    proxy.daemonPortHeldUnresponsive.set(true)
    val logs = mutableListOf<String>()

    proxy.forwardRequest("""{"jsonrpc":"2.0","id":9,"method":"tools/list"}""") { logs += it }

    assertEquals(
      false,
      proxy.daemonPortHeldUnresponsive.get(),
      "a freed port must clear the verdict, or the session never recovers",
    )
    assertTrue(
      logs.any { it.contains(McpProxy.LOG_PORT_NO_LONGER_HELD) },
      "the recovery should be stated; got: $logs",
    )
  }

  @Test
  fun `a freed port lets the very next request try a restart instead of failing fast`() {
    // Both flags are set by the same held-port verdict, and clearing only one strands the request
    // the operator makes right after freeing the port. A freed port produces exactly the
    // ConnectException that the startup-failure fast-fail consumes, and that path breaks out of
    // the retry loop BEFORE trying to start a daemon — so the request fails anyway, blaming a
    // startup deadline the re-probe just showed does not apply.
    //
    // Asserted on the fast-fail log line rather than on the flag: the ConnectException handler
    // CASes the flag to false on its way out, so both the fixed and the broken code leave it
    // false, and an assertion on the flag alone passes either way.
    val freedPort = unusedPortOutsideDeviceRange()
    val proxy = McpProxy(port = freedPort, retryIntervalMs = 10, maxRetryMs = 100)
    proxy.daemonPortHeldUnresponsive.set(true)
    proxy.daemonStartupFailed.set(true)
    val logs = mutableListOf<String>()

    proxy.forwardRequest("""{"jsonrpc":"2.0","id":9,"method":"tools/list"}""") { logs += it }

    assertTrue(
      logs.none { it.contains(McpProxy.LOG_NO_DAEMON_CAN_ANSWER) },
      "a port that was just observed free must not short-circuit on the old startup failure; got: $logs",
    )
  }

  @Test
  fun `a reachable daemon still proceeds`() {
    // The control: the fast-fail must key on the held verdict, not on any probe that isn't
    // REACHABLE, or every cold start becomes an immediate failure.
    val proxy = McpProxy(port = unusedPortOutsideDeviceRange(), daemonProbeOverride = { DaemonProbe.REACHABLE })

    assertTrue(proxy.waitForDaemon {})
    assertEquals(false, proxy.daemonStartupFailed.get())
  }

  /**
   * A port that cannot be bound does not necessarily REFUSE a connection: a listener with a full
   * accept backlog, or one that hangs up mid-request, produces a transport error that is not
   * `ConnectException` and so misses the fast-fail on that branch. The first MCP request then
   * spends the whole `maxRetryMs` retrying an outcome `waitForDaemon` has already given up on.
   *
   * Asserted on the retry count rather than on elapsed time, so it cannot flake on a loaded agent:
   * failing fast logs the request error once, while retrying logs it every `retryIntervalMs`.
   */
  @Test
  fun `a terminal startup refusal short-circuits transport errors that are not connection refusals`() {
    // Accepts and immediately hangs up, so the POST fails fast with something other than
    // ConnectException — the shape of a wedged port, without a five-minute request timeout.
    ServerSocket(unusedPortOutsideDeviceRange()).use { server ->
      Thread {
        runCatching { while (true) server.accept().close() }
      }.apply { isDaemon = true; start() }

      val proxy = McpProxy(port = server.localPort, retryIntervalMs = 10, maxRetryMs = 400)
      proxy.daemonStartupFailed.set(true)
      val logs = mutableListOf<String>()

      proxy.forwardRequest("""{"jsonrpc":"2.0","id":11,"method":"tools/list"}""") { logs += it }

      // WHICH branch spoke, not just that the retries stopped: if this host happens to surface the
      // hang-up as a ConnectException, the pre-existing branch handles it, no `Request error` is
      // logged at all, and a count-only assertion passes without the branch under test existing.
      assertTrue(
        logs.any { it.contains(McpProxy.LOG_FAST_FAIL_TRANSPORT_ERROR) },
        "the transport-error fast-fail is the branch under test; got: $logs",
      )
      assertTrue(
        logs.count { it.contains(McpProxy.LOG_REQUEST_ERROR) } <= 1,
        "startup was already refused, so this must not keep retrying; got: $logs",
      )
    }
  }

  @Test
  fun `a refusal to start stops the retry loop instead of polling out the window`() {
    // The retry loop's periodic restart used to discard `startDaemon`'s answer. It is the only
    // caller that reaches the refusals at all — `waitForDaemon` returns before `startDaemon`
    // whenever the kill switch is set, which every Gradle `Test` task does — so ignoring it meant
    // the second and later requests each spent the full `maxRetryMs` on a port that had just been
    // reported unusable. The first request got away with it because the fast-fail above consumes
    // `daemonStartupFailed`.
    //
    // Asserted on elapsed time, which is a fair bet here rather than a machine-speed one: the
    // window being compared against is `maxRetryMs`, a value this test passes INTO the code under
    // test, and nothing is listening, so every attempt refuses immediately. Only the retry loop can
    // consume the window.
    val closedPort = unusedPortOutsideDeviceRange()
    val proxy = McpProxy(port = closedPort, retryIntervalMs = 10, maxRetryMs = 8_000)

    val startedAt = System.currentTimeMillis()
    proxy.forwardRequest("""{"jsonrpc":"2.0","id":13,"method":"tools/list"}""") {}
    val elapsedMs = System.currentTimeMillis() - startedAt

    assertTrue(elapsedMs < 4_000, "a refused start must end the loop; spun for ${elapsedMs}ms")
  }
}
