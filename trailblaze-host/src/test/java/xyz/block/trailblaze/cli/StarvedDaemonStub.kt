package xyz.block.trailblaze.cli

import com.sun.net.httpserver.HttpServer
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * A daemon that is alive but starved: it answers `/ping`, then holds every `device` tool call open
 * and never answers it. By default it still completes the MCP handshake; [HandshakeMode] parks that
 * too. This is the documented
 * `TRAILBLAZE_IO_PARALLELISM` exhaustion shape — the HTTP layer keeps serving while every real call
 * waits for an IO slot that is never freed.
 *
 * Every request the stub sees is counted in [deviceCalls], so a test can prove the probe REACHED
 * the daemon and was then cut off by the CLI's bound — as opposed to failing early on something
 * unrelated, which would also come back fast and also never answer.
 */
internal class StarvedDaemonStub private constructor(
  private val server: HttpServer,
  private val release: CountDownLatch,
  val deviceCalls: AtomicInteger,
  val initializeCalls: AtomicInteger,
  /** Terminating DELETEs the stub received, i.e. sessions the CLI cleaned up after itself. */
  val deleteCalls: AtomicInteger,
) : AutoCloseable {
  val port: Int get() = server.address.port

  override fun close() {
    // Let the handler threads that are parked on a device call return, then stop.
    release.countDown()
    server.stop(0)
  }

  /** How the stub treats the `initialize` handshake. */
  internal enum class HandshakeMode {
    /** Completes normally, so a test can get on to the probe that follows it. */
    ANSWERS,

    /**
     * Never answers: the daemon is so starved that even `initialize` waits for an IO slot that
     * never comes (on the real daemon every `/mcp` POST is dispatched on the same pool). The call
     * is still counted before it parks, so a test can prove the handshake was attempted rather
     * than skipped.
     */
    PARKS,

    /**
     * Answers `initialize` in full -- so the daemon has minted a session and the client has
     * adopted its id -- and then never answers the `notifications/initialized` that follows. The
     * narrow window where the bound expires with a real session already created and no pointer
     * written for it yet.
     */
    PARKS_AFTER_INITIALIZE,
  }

  companion object {
    /**
     * @param persistedSessionId the session id the stub hands out on `initialize`. A test that
     *   pre-seeds a session file uses the same id so the persisted session "is recognized".
     * @param handshake see [HandshakeMode].
     * @param parksDelete when true the terminating DELETE hangs like every other call, which is
     *   what a starved daemon really does. Off by default so the tests that only care about the
     *   probe are not slowed by it.
     */
    fun start(
      persistedSessionId: String = "starved-session",
      handshake: HandshakeMode = HandshakeMode.ANSWERS,
      parksDelete: Boolean = false,
    ): StarvedDaemonStub {
      val server = bindOutsideDeviceRange()
      val release = CountDownLatch(1)
      val deviceCalls = AtomicInteger(0)
      val initializeCalls = AtomicInteger(0)
      val deleteCalls = AtomicInteger(0)
      // One thread per request: a parked device call must not block the handshake or the
      // session DELETE that `CliMcpClient.close` sends.
      server.executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "starved-daemon-stub").apply { isDaemon = true }
      }
      server.createContext("/ping") { exchange ->
        val bytes = "pong".toByteArray()
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
      }
      server.createContext("/mcp") { exchange ->
        val body = exchange.requestBody.bufferedReader().use { it.readText() }
        fun send(response: String) {
          val bytes = response.toByteArray()
          exchange.sendResponseHeaders(200, bytes.size.toLong())
          exchange.responseBody.use { it.write(bytes) }
        }
        when {
          exchange.requestMethod == "DELETE" -> {
            deleteCalls.incrementAndGet()
            if (parksDelete) {
              // A real starved daemon dispatches the terminating DELETE on the same exhausted IO
              // pool as everything else, so it hangs too. Answering it instantly (the default)
              // hides a cleanup budget that would double the caller's wait.
              release.await()
              return@createContext
            }
            exchange.sendResponseHeaders(200, -1)
            exchange.close()
          }
          "\"initialize\"" in body -> {
            initializeCalls.incrementAndGet()
            if (handshake == HandshakeMode.PARKS) {
              release.await()
              return@createContext
            }
            exchange.responseHeaders.add("mcp-session-id", persistedSessionId)
            send(
              """{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2025-11-25","capabilities":{},"serverInfo":{"name":"starved","version":"1"}}}""",
            )
          }
          "\"notifications/initialized\"" in body -> {
            if (handshake == HandshakeMode.PARKS_AFTER_INITIALIZE) {
              release.await()
              return@createContext
            }
            send("{}")
          }
          "\"tools/call\"" in body && "\"device\"" in body -> {
            deviceCalls.incrementAndGet()
            // Never answers. Parked until the stub is closed, then the write fails on a socket
            // the client has long since abandoned — which is the point.
            release.await()
            runCatching { send("""{"jsonrpc":"2.0","id":1,"result":{"content":[],"isError":false}}""") }
          }
          else -> error("Unexpected request to the starved daemon stub: $body")
        }
      }
      server.start()
      return StarvedDaemonStub(server, release, deviceCalls, initializeCalls, deleteCalls)
    }

    /**
     * An ephemeral port can land inside [TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE], and
     * the CLI refuses to probe a daemon there — that guard is not what these tests are about.
     *
     * Port 0 first, because it is the only acquisition with no close-then-rebind window (see
     * [startOnEphemeralPort]) and usually lands outside the range.
     *
     * Retrying port 0 is NOT the fallback, though it reads like the obvious one. macOS hands out
     * ephemeral ports by walking a counter, so consecutive binds are consecutive NUMBERS: once the
     * counter is inside the reserved range, every retry is also inside it, and a bounded retry loop
     * cannot get out of a ~7000-port window. So the fallback names explicit ports above the range
     * instead, which leaves the counter alone. The starting point is randomized so two test JVMs
     * on one machine do not walk the same candidates in the same order.
     */
    private fun bindOutsideDeviceRange(): HttpServer {
      HttpServer.create(InetSocketAddress("localhost", 0), 0).let { server ->
        if (server.address.port !in TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE) return server
        server.stop(0)
      }
      val firstCandidate = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last + 1
      val start = Random.nextInt(CANDIDATE_PORT_SPAN)
      for (i in 0 until CANDIDATE_PORT_SPAN) {
        val port = firstCandidate + ((start + i) % CANDIDATE_PORT_SPAN)
        val bound = runCatching { HttpServer.create(InetSocketAddress("localhost", port), 0) }.getOrNull()
        if (bound != null) return bound
      }
      error(
        "could not bind any of the $CANDIDATE_PORT_SPAN ports above " +
          "${TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last}",
      )
    }

    /** Enough room above the reserved range that a busy machine still has a free port in it. */
    private const val CANDIDATE_PORT_SPAN = 2_000
  }
}
