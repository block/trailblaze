package xyz.block.trailblaze.host

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import xyz.block.trailblaze.logs.client.TrailblazeJsonInstance

/**
 * Lightweight Ktor server that mimics the on-device RPC server for testing. Starts on the
 * deterministic port derived from the device ID, so an OnDeviceRpcClient constructed with the same
 * device ID will connect to it automatically.
 *
 * By default every POST returns [responseStatus]/[responseBody]. Tests that need path-specific
 * behavior (e.g., different responses for `/rpc/RunYamlRequest` vs `/rpc/GetExecutionStatusRequest`)
 * can register a handler via [onPost]; unmatched paths fall back to the default. Every incoming
 * request body is appended to [requestLog] keyed by its `/rpc/<Name>` path.
 */
class MockRpcServer(deviceId: TrailblazeDeviceId) {

  val port: Int = deviceId.getTrailblazeOnDeviceSpecificPort()

  /** Default response status and JSON body returned when no per-path handler is registered. */
  @Volatile var responseStatus: HttpStatusCode = HttpStatusCode.InternalServerError

  @Volatile
  var responseBody: String =
    """{"errorType":"UNKNOWN_ERROR","message":"Mock: no handler","details":null}"""

  /** Raw request bodies received, keyed by URL path (e.g., `/rpc/RunYamlRequest`). */
  val requestLog: MutableMap<String, MutableList<String>> = ConcurrentHashMap()

  private data class HandlerResponse(val status: HttpStatusCode, val body: String)

  private val handlers = ConcurrentHashMap<String, () -> HandlerResponse>()

  /**
   * Register a response for a specific `/rpc/<RequestName>` path. The lambda is invoked on
   * every matching request, so tests can return different responses across calls (e.g., simulate
   * RUNNING→COMPLETED status transitions).
   */
  fun onPost(path: String, respond: () -> Pair<HttpStatusCode, String>) {
    handlers[path] = {
      val (status, body) = respond()
      HandlerResponse(status, body)
    }
  }

  private val server =
    embeddedServer(CIO, host = LOOPBACK_HOST, port = port) {
      install(ContentNegotiation) { json(TrailblazeJsonInstance) }
      routing {
        post("/rpc/{path...}") {
          val path = call.request.local.uri.substringBefore('?')
          val body = call.receiveText()
          requestLog.getOrPut(path) { mutableListOf() }.add(body)
          val handler = handlers[path]
          if (handler != null) {
            val response = handler()
            call.respondText(response.body, ContentType.Application.Json, response.status)
          } else {
            call.respondText(responseBody, ContentType.Application.Json, responseStatus)
          }
        }
      }
    }

  /**
   * Starts the server and returns only once [port] actually accepts a connection.
   *
   * This used to sleep a flat 300ms and return regardless of whether the bind had landed — a
   * wall-clock guess a loaded CI agent can outrun, leaving the first request of a test racing a
   * server that isn't listening yet. Polling an observable condition removes the guess.
   *
   * [awaitListening] is a *port* probe, not an identity probe: a foreign listener already on [port]
   * would satisfy it. That gap is closed by `start(wait = false)` itself, which propagates a failed
   * bind to this caller rather than returning (measured). Don't reach for `resolvedConnectors()` as a
   * stronger gate here: on a failed bind it never completes (measured: no answer in 5s), so it would
   * turn a loud failure into a hang. It isn't covered by a test on purpose — see the test class doc.
   */
  fun start() {
    check(awaitBindable(port)) {
      "Port $port was still held by another socket ${PORT_STATE_TIMEOUT_MS}ms into start()"
    }
    server.start(wait = false)
    check(awaitListening(port, isListening = true)) {
      "MockRpcServer never started listening on port $port within ${PORT_STATE_TIMEOUT_MS}ms"
    }
  }

  /** The address the engine actually bound, so a test can assert the bind is loopback-only. */
  internal fun boundHosts(): List<String> = runBlocking {
    server.engine.resolvedConnectors().map { it.host }
  }

  /**
   * Stops the server and returns only once [port] has stopped accepting connections.
   *
   * The wait is the half that made a whole test class fall over: `stop()` used to return as soon as
   * Ktor's own 500ms shutdown bound elapsed, so the next test's [start] could race a listener that
   * was still up and take a `BindException` — which surfaces on a server thread, so the test failed
   * later and unrecognizably (an `UninitializedPropertyAccessException` in `tearDown`).
   */
  fun stop() {
    server.stop(gracePeriodMillis = 0, timeoutMillis = STOP_GRACE_TIMEOUT_MS)
    check(awaitListening(port, isListening = false)) {
      "MockRpcServer was still listening on port $port ${PORT_STATE_TIMEOUT_MS}ms after stop()"
    }
  }

  companion object {

    /**
     * Bind loopback-only rather than the wildcard `0.0.0.0` Ktor defaults to.
     *
     * [port] is a hash of the device id inside Trailblaze's device-port range (52530-59529), which
     * sits inside the OS ephemeral range (49152-65535 on macOS, 32768-60999 on Linux). So any
     * unrelated *outbound* connection on the machine can be assigned this port as its source port,
     * and a wildcard bind then fails with `BindException` through no fault of any test. Measured
     * directly: with a VPN tunnel holding `100.96.155.178:54782` as an outbound source port,
     * binding `0.0.0.0:54782` fails while `127.0.0.1:54782` succeeds — an outbound socket's source
     * is the egress interface address, never loopback.
     *
     * Narrowing the bind loses nothing: `0.0.0.0` is the IPv4 wildcard, so every client that
     * reaches this fixture today (`http://localhost:$port` via `OnDeviceRpcClient`, `127.0.0.1`
     * directly in tests) is already arriving over IPv4 loopback.
     */
    private const val LOOPBACK_HOST = "127.0.0.1"

    /**
     * Hang containment, not a performance bound. Reaching either port state takes milliseconds in
     * practice; this only exists so a genuinely wedged bind fails the test with an attributable
     * message instead of parking forever. Deliberately generous — a tight bound here would
     * reintroduce exactly the CI timing flake this fixture was fixed to remove.
     */
    private const val PORT_STATE_TIMEOUT_MS = 60_000L

    /** Ktor's own shutdown bound. Separate from [PORT_STATE_TIMEOUT_MS], which covers the result. */
    private const val STOP_GRACE_TIMEOUT_MS = 5_000L

    private const val POLL_INTERVAL_MS = 25L

    /** Connect timeout per probe. Loopback either answers immediately or isn't listening. */
    private const val PROBE_CONNECT_TIMEOUT_MS = 250

    /**
     * Polls [port] on loopback until it is (or is not) accepting connections, per [isListening].
     *
     * Returns false if the state wasn't reached within [timeoutMs] — callers turn that into an
     * attributable failure. Extracted and `internal` so the wait itself is directly unit-testable
     * against a hand-controlled [java.net.ServerSocket], with no Ktor server involved.
     */
    internal fun awaitListening(
      port: Int,
      isListening: Boolean,
      timeoutMs: Long = PORT_STATE_TIMEOUT_MS,
    ): Boolean {
      val deadline = System.nanoTime() + timeoutMs * 1_000_000
      while (true) {
        if (probeListening(port) == isListening) return true
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(POLL_INTERVAL_MS)
      }
    }

    /**
     * Polls [port] until a loopback listener can actually be bound on it, so the engine is only
     * ever started against a port that is free.
     *
     * Narrowing the bind to loopback rules out an *egress-interface* source port owning [port]
     * (see [LOOPBACK_HOST]) but not a loopback one: a connection whose destination is 127.0.0.1
     * has 127.0.0.1 as its source address too, and [port] sits inside the OS ephemeral range, so
     * on a machine running many loopback connections at once — a CI agent running this suite —
     * an unrelated socket can hold it. That clears on its own, so waiting is the whole fix.
     *
     * Waiting *here* rather than letting the engine take the `BindException` is what keeps the
     * failure contained: Ktor reports a failed bind on its own coroutine as well as to the caller,
     * and the uncaught half lands on whatever `runTest`-based test runs next — build 12247's
     * collateral shape, and how one bind failure in this class took `DevicesPageEndpointTest`
     * down with it.
     */
    internal fun awaitBindable(port: Int, timeoutMs: Long = PORT_STATE_TIMEOUT_MS): Boolean {
      val deadline = System.nanoTime() + timeoutMs * 1_000_000
      while (true) {
        if (probeBindable(port)) return true
        if (System.nanoTime() >= deadline) return false
        Thread.sleep(POLL_INTERVAL_MS)
      }
    }

    /** True when a loopback listener can be bound on [port] right now. */
    private fun probeBindable(port: Int): Boolean =
      try {
        ServerSocket(port, 0, InetAddress.getByName(LOOPBACK_HOST)).close()
        true
      } catch (_: IOException) {
        false
      }

    /** True when something accepts a loopback connection on [port]. */
    private fun probeListening(port: Int): Boolean =
      try {
        Socket().use {
          it.connect(InetSocketAddress(LOOPBACK_HOST, port), PROBE_CONNECT_TIMEOUT_MS)
          true
        }
      } catch (_: IOException) {
        false
      }
  }
}
