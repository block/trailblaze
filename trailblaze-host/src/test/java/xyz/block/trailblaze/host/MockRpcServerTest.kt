package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.net.BindException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.Rule
import org.junit.rules.Timeout
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import kotlin.test.Test

/**
 * Lifecycle guards for the [MockRpcServer] test fixture itself.
 *
 * Mainline build 12247 failed every [HostAccessibilityRpcClientTest] case at once with
 * `BindException: Address already in use` on the fixture's device-derived port, and dragged
 * `DevicesPageEndpointTest` down as collateral (the uncaught bind failure was charged to the next
 * `runTest`). The commit under test touched only shell scripts, so nothing in the product was
 * involved — the fixture's own start/stop contract was the defect. Both halves were wall-clock bets:
 *  - `start()` slept a flat 300ms and returned regardless of whether the bind had landed;
 *  - `stop()` returned once Ktor's own 500ms shutdown bound elapsed, without confirming the port was
 *    actually free, so the next test's bind could land while the previous listener was still up.
 *
 * Two independent things make a bind on this port fail, and `TIME_WAIT` is neither of them.
 * (`TIME_WAIT` is survivable because `SO_REUSEADDR` defaults to true on the JDK socket types CIO
 * builds on — measured: with it explicitly false the same rebind does fail. CIO exposes no knob for
 * it, so it isn't something this fixture can get wrong.) The two real ones:
 *  - **a still-live listener** — the fixture's own previous instance, which the `stop()` barrier
 *    above closes;
 *  - **an outbound connection holding the port as its source port** — this port is a hash inside
 *    Trailblaze's device-port range, which overlaps the OS ephemeral range, so any unrelated
 *    connection on the machine can own it. A wildcard bind loses to that; a loopback-only bind
 *    narrows it to connections whose *destination* is also loopback, which keep 127.0.0.1 as their
 *    source. See the loopback-host constant in [MockRpcServer] for the measurement.
 *
 * Neither is a socket-option problem. The first isn't fixed by waiting longer; the loopback
 * remainder of the second is — the squatting socket is unrelated to this fixture and goes away on
 * its own — which is why `start()` waits for the port to be bindable before handing it to Ktor
 * rather than letting the engine take the `BindException`. Doing it in that order also keeps the
 * failure contained, since Ktor reports a failed bind on its own coroutine as well as to the
 * caller (below).
 *
 * The second is guarded by asserting the *bind address* rather than by manufacturing a conflict — three
 * attempts at the latter all failed to earn their keep, so don't re-litigate it without a new idea. A
 * second *listener* bound to a specific address doesn't block a later wildcard bind (both carry
 * `SO_REUSEADDR`), so that version passed with the fix reverted. A real *connected* socket does block
 * it, but binding one needs a non-loopback local address and a reachable sink, which a VPN tunnel
 * blackholes. And a *loopback* squatter does make the bind fail deterministically — but Ktor reports
 * that failure on its own coroutine as well as to the caller, and the uncaught half lands on whatever
 * `runTest`-based test happens to run next: reproduced twice, taking down `DevicesPageEndpointTest`
 * once and `AppIconRouteTest` once. That is build 12247's collateral shape exactly, so pinning the
 * contract that way would manufacture the flake class this fixture exists to remove.
 *
 * JUnit constructs a fresh instance per test method, so every test in a class re-binds the same
 * port in sequence — which is why one fixture race takes out an entire class rather than one case.
 * The cycle test below reproduces that sequence; it does not reproduce the *loss* of the race, which
 * needs the CI contention that stretches Ktor's shutdown past its bound (on an idle machine the
 * pre-fix `stop()` released the port in 0ms on every cycle).
 */
class MockRpcServerTest {

  /** Hang containment, not a performance bound: every assertion here settles in milliseconds. */
  @get:Rule val perTestHangGuard: Timeout = Timeout(120, TimeUnit.SECONDS)

  @Test fun `awaitListening observes a bound port as listening`() {
    ServerSocket(0).use { socket ->
      assertThat(awaitListeningWithin(socket.localPort, isListening = true)).isTrue()
    }
  }

  @Test fun `awaitListening observes a closed port as not listening`() {
    assertThat(awaitListeningWithin(NEVER_LISTENING_PORT, isListening = false)).isTrue()
  }

  /**
   * The bound is real, not decorative: a state that never arrives has to return false so the caller
   * can attribute it, rather than parking until the suite is cancelled — the failure shape that
   * wedged Gradle `check` at 99% on three mainline builds in a different module.
   *
   * [NEVER_LISTENING_PORT] rather than a just-closed ephemeral port, here and in the not-listening
   * case above: the OS can hand a released ephemeral port to another process mid-test, which would
   * fail these assertions for a reason that has nothing to do with the code. A deflaking test should
   * not itself depend on machine state.
   */
  @Test fun `awaitListening gives up within its bound when the state never arrives`() {
    assertThat(awaitListeningWithin(NEVER_LISTENING_PORT, isListening = true)).isFalse()
  }

  /**
   * The gate [MockRpcServer.start] applies before handing the port to Ktor. Both directions are
   * asserted against a port this test holds and releases itself, so neither depends on machine
   * state: a held port must read as not-bindable within the bound rather than parking, and the
   * same port must read as bindable once released.
   */
  @Test fun `awaitBindable distinguishes a held port from a free one`() {
    val held = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    try {
      assertThat(awaitBindableWithin(held.localPort)).isFalse()
    } finally {
      held.close()
    }
    assertThat(awaitBindableWithin(held.localPort)).isTrue()
  }

  /**
   * The readiness contract: once [MockRpcServer.start] returns, a request must succeed with no
   * additional waiting by the caller. Under the previous flat 300ms sleep this was a bet on the
   * bind finishing first.
   */
  @Test fun `start returns only once the server answers a request`() {
    val server = MockRpcServer(deviceId("mock-rpc-readiness"))
    server.responseStatus = HttpStatusCode.OK
    server.responseBody = """{"ok":true}"""
    server.start()
    try {
      assertThat(post(server.port, "/rpc/ReadinessProbe")).isEqualTo(HttpURLConnection.HTTP_OK)
      assertThat(server.requestLog["/rpc/ReadinessProbe"]?.size).isEqualTo(1)
    } finally {
      server.stop()
    }
  }

  /**
   * The half that took out build 12247: [MockRpcServer.stop] must be a barrier, so a caller that
   * returns from it can bind the port again. Previously it returned on Ktor's shutdown bound with the
   * listener's actual state unchecked, which is what let the next test's bind land on a live socket.
   *
   * The rebind is loopback-scoped to match the fixture's own contract. A wildcard `ServerSocket(port)`
   * here would be vulnerable to the very ephemeral-source-port collision the fixture now sidesteps, so
   * this guard could fail on a healthy fixture.
   */
  @Test fun `stop returns only once the port is free to rebind`() {
    val server = MockRpcServer(deviceId("mock-rpc-stop-barrier"))
    server.start()
    server.stop()
    ServerSocket(server.port, 0, InetAddress.getByName("127.0.0.1")).close()
  }

  /**
   * The half that took out build 15969: a [MockRpcServer.stop] on a server that never started must
   * not report the squatting socket as its own teardown failure.
   *
   * An `@After` runs whether or not `@Before` succeeded, so a failed [MockRpcServer.start] is
   * routinely followed by a [MockRpcServer.stop]. Pre-fix, that stop asked the port to go quiet,
   * saw the squatter still listening, and threw "still listening on port N" — so the test reported
   * its teardown's complaint about an unrelated socket instead of the real failure, and whichever
   * test failed first was buried. The port is held here for exactly the reason it is held in
   * production: something else has it, which is why start would have failed in the first place.
   *
   * Asserts the thrown/not-thrown outcome and that the squatter is untouched, NOT elapsed time. A
   * wall-clock bound is the only thing that would distinguish "returned immediately" from "waited
   * and then succeeded", and on a loaded CI agent that is a coin flip — the flake class this whole
   * fixture exists to remove.
   *
   * The squatter has to *answer* connections, which is why it gets a thread rather than just a bind.
   * A `ServerSocket` that never accepts reads as listening only until its backlog fills, and the
   * fixture's own probe is what fills it: measured, a passive squatter stopped accepting connections
   * after roughly 50 probes, so the port read as quiet ~1.2s in and the unguarded `stop` returned
   * successfully. This test passed with the guard reverted until the squatter started accepting.
   *
   * The squatter's own bind takes [MockRpcServer.start]'s gate directly — the only bind here on the
   * fixture's device-derived port that no `start()` precedes, because the whole point is that this
   * server never starts. Without it, build 17500 failed in this test's *setup* with a raw
   * `BindException` from an unrelated ephemeral socket, on a line whose subject is not this fixture
   * at all; it passed on the immediate rebuild. That is the class doc's second hazard landing on the
   * one bind nothing had gated.
   *
   * The gate is a precondition, not a guarantee, and the difference is the point. [awaitBindable]
   * probes by binding and closing, so a socket arriving inside that window still takes the bind
   * below — which is why that bind says so when it loses, rather than retrying. Closing the window
   * would mean manufacturing exactly the machine-state-dependent complexity the class doc says has
   * three times failed to earn its keep here. `stop returns only once the port is free to rebind`
   * keeps the same residual on its own rebind for a stronger reason: that bind *is* its assertion,
   * so gating it would make the test wait for the very condition it exists to prove.
   */
  @Test fun `stop on a never-started server leaves a squatted port alone instead of failing`() {
    val server = MockRpcServer(deviceId("mock-rpc-stop-unstarted"))
    // Setup, not assertion: this test has to own the port before it can squat it. See the KDoc.
    check(MockRpcServer.awaitBindable(server.port)) {
      "Test setup: port ${server.port} never became bindable within start()'s bind wait, so" +
        " this test could not squat it — an unrelated socket holding it is the usual cause"
    }
    val squatter =
      try {
        ServerSocket(server.port, SQUATTER_BACKLOG, InetAddress.getByName("127.0.0.1"))
      } catch (e: BindException) {
        // The probe above binds and closes, so this is the window it cannot cover, named rather
        // than retried. Left bare it reads as a broken gate instead of a late-arriving socket.
        throw IllegalStateException(
          "Test setup: port ${server.port} was bindable when checked and taken before this test" +
            " could bind it, so something else claimed it in between",
          e,
        )
      }
    val accepting =
      thread(isDaemon = true, name = "mock-rpc-squatter-accept") {
        while (true) {
          try {
            squatter.accept().close()
          } catch (_: IOException) {
            break // The close() below is the only way out.
          }
        }
      }
    try {
      assertThat(awaitListeningWithin(server.port, isListening = true)).isTrue()
      server.stop()
      // Still bound and still ours: stop() neither waited for it to go quiet nor closed it.
      assertThat(squatter.isClosed).isFalse()
      assertThat(squatter.localPort).isEqualTo(server.port)
    } finally {
      squatter.close()
      accepting.join(SHORT_BOUND_MS)
    }
  }

  /** Rebinds one port the way a whole test class does, so the start/stop contract holds in sequence. */
  @Test fun `repeated start and stop cycles rebind the same port`() {
    val deviceId = deviceId("mock-rpc-rebind")
    repeat(REBIND_CYCLES) { cycle ->
      val server = MockRpcServer(deviceId)
      server.responseStatus = HttpStatusCode.OK
      server.responseBody = """{"cycle":$cycle}"""
      server.start()
      try {
        assertThat(post(server.port, "/rpc/Cycle")).isEqualTo(HttpURLConnection.HTTP_OK)
      } finally {
        server.stop()
      }
    }
  }

  /**
   * The durable guard on the loopback bind: asserts the engine's own resolved connector rather than
   * trying to manufacture a port conflict (see the class doc for why every conflict-based version of
   * this failed to earn its keep). Reverting to Ktor's default wildcard host fails here.
   */
  @Test fun `the server binds loopback only, not the wildcard address`() {
    val server = MockRpcServer(deviceId("mock-rpc-bind-host"))
    server.start()
    try {
      assertThat(server.boundHosts()).isEqualTo(listOf("127.0.0.1"))
    } finally {
      server.stop()
    }
  }

  private fun awaitListeningWithin(port: Int, isListening: Boolean): Boolean =
    MockRpcServer.awaitListening(port, isListening = isListening, timeoutMs = SHORT_BOUND_MS)

  private fun awaitBindableWithin(port: Int): Boolean =
    MockRpcServer.awaitBindable(port, timeoutMs = SHORT_BOUND_MS)

  private fun deviceId(instanceId: String) =
    TrailblazeDeviceId(
      instanceId = instanceId,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )

  /** Minimal POST so the assertion is on the fixture's own behavior, not on an HTTP client. */
  private fun post(port: Int, path: String): Int {
    val connection =
      (URI("http://127.0.0.1:$port$path").toURL().openConnection() as HttpURLConnection).apply {
        requestMethod = "POST"
        doOutput = true
        connectTimeout = REQUEST_TIMEOUT_MS
        readTimeout = REQUEST_TIMEOUT_MS
      }
    return try {
      connection.outputStream.use { it.write("{}".toByteArray()) }
      connection.responseCode
    } finally {
      connection.disconnect()
    }
  }

  private companion object {
    /**
     * Short on purpose, and safe to be short: this bounds a loopback connect against a port whose
     * state the test itself controls — no subprocess, no external binary, nothing whose cold start a
     * loaded CI agent can stretch. The give-up case would otherwise pay the fixture's full
     * production bound on every run.
     */
    const val SHORT_BOUND_MS = 500L

    /** Privileged, so no unrelated process on this machine can start listening on it mid-test. */
    const val NEVER_LISTENING_PORT = 1

    const val REBIND_CYCLES = 10

    /** Deep enough that the squatter's liveness never depends on how fast its thread accepts. */
    const val SQUATTER_BACKLOG = 50

    const val REQUEST_TIMEOUT_MS = 10_000
  }
}
