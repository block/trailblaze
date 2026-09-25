package xyz.block.trailblaze.cli

import xyz.block.trailblaze.devices.TrailblazeDevicePort
import java.io.File
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Ports for tests that care which port they get.
 *
 * Most tests do not: `ServerSocket(0)` is fine when any free port will do. These helpers are for
 * the daemon-port tests, where the port number itself is part of the subject — a port the daemon
 * refuses on sight, or one the OS reassigns mid-test, makes the assertion pass or fail for a reason
 * that has nothing to do with the code under test.
 *
 * @see EphemeralPortServer for the other half of the problem — binding a Ktor server without a
 *   close-then-rebind window.
 */
internal object TestPorts {

  /**
   * The band these tests draw from. It sits BELOW two hazards that lie in opposite directions:
   * [TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE], which [McpProxy.refuseDeviceAllocatablePort]
   * turns away before any probe runs, and the OS ephemeral range above it, where an unrelated
   * outbound connection can be handed the port as its source and the bind then fails outright.
   * Port 0 is no good either: the OS's own choice can land inside the device range, and asking
   * again walks upward from there rather than jumping clear.
   */
  val CANDIDATE_PORT_RANGE: IntRange = candidateRangeFor(hostEphemeralRange())

  /**
   * Where the ephemeral range starts on the platforms these tests run on: 32768 on Linux, 49152 on
   * macOS. The Linux figure is the conservative one everywhere.
   */
  const val DEFAULT_EPHEMERAL_START = 32_768

  /** Ports below this need privileges to bind, so the band never reaches them. */
  const val LOWEST_UNPRIVILEGED_PORT = 1_024

  /** Thousands wide, so random draws rarely collide across the several test JVMs on one machine. */
  const val PREFERRED_BAND_WIDTH = 12_768

  /**
   * The band for a host that reserves [hostEphemeral] for outbound connections, or `null` when the
   * host has no opinion.
   *
   * Normally the band sits just below the ephemeral floor, and that floor is a host fact rather
   * than a constant: a container can set `ip_local_port_range` to start far below the platform
   * default, and a band picked against the default would then sit inside the range the kernel
   * assigns from. So the lower of the two wins — and only ever narrowing, because a host that
   * reserves *less* than the default must not earn a band that reaches up into the
   * device-allocation range.
   *
   * A host can also start its range so low that nothing unprivileged is left underneath it. Such a
   * host bounds the range at the top as well, and the ports above that bound are as safe as the
   * ones we wanted: not assigned by the kernel, and still below the device-allocation range.
   */
  fun candidateRangeFor(hostEphemeral: IntRange?): IntRange {
    val ceiling = minOf(hostEphemeral?.first ?: DEFAULT_EPHEMERAL_START, DEFAULT_EPHEMERAL_START)
    val floor = maxOf(LOWEST_UNPRIVILEGED_PORT + 1, ceiling - PREFERRED_BAND_WIDTH)
    if (floor < ceiling) return floor until ceiling

    val aboveHostEphemeral = (hostEphemeral?.last ?: DEFAULT_EPHEMERAL_START) + 1
    check(aboveHostEphemeral < DEFAULT_EPHEMERAL_START) {
      "this host reserves $hostEphemeral for outbound connections, which leaves these tests no " +
        "unprivileged band below it and nothing safe above it either. Narrow ip_local_port_range, " +
        "or run the suite on a host with a normal ephemeral range."
    }
    return aboveHostEphemeral until DEFAULT_EPHEMERAL_START
  }

  /** Bounds both the search for a free port and the re-pick when one is taken mid-test. */
  const val PORT_ATTEMPTS = 50

  /** A refused connection comes back at once; this only bounds a port that has gone quiet. */
  const val REFUSAL_CHECK_TIMEOUT_MS = 250

  /**
   * Bind a random port from [CANDIDATE_PORT_RANGE] and hand the socket over still bound, so a
   * caller that needs the port occupied never has a window where it is not.
   *
   * Random rather than scanned: a fixed starting point means every test JVM on the machine tries
   * the same port first, and several run at once, one per checkout. Loopback-scoped, because every
   * caller dials it back on loopback.
   */
  fun openPortInCandidateBand(): ServerSocket {
    val tried = mutableListOf<Int>()
    repeat(PORT_ATTEMPTS) {
      val candidate = CANDIDATE_PORT_RANGE.random()
      tried += candidate
      runCatching {
        ServerSocket(candidate, 0, InetAddress.getLoopbackAddress())
      }.getOrNull()?.let { return it }
    }
    error(
      "no free port in $CANDIDATE_PORT_RANGE after $PORT_ATTEMPTS attempts " +
        "(last tried: ${tried.last()})",
    )
  }

  /**
   * Run [body] against a port that refuses connections, re-picking if the machine takes it first.
   *
   * A closed port cannot be reserved, so the only honest guarantee is that it was still refusing a
   * moment before [body] ran. The retry re-establishes that PRECONDITION and never re-runs an
   * assertion: a verdict the code got wrong is returned on the first attempt, unchanged.
   */
  fun <T> withRefusingPort(body: (Int) -> T): T =
    withRefusingPort(
      pickPort = { openPortInCandidateBand().use { it.localPort } },
      isRefusing = ::refusesConnections,
      body = body,
    )

  /** [withRefusingPort] with its two sources of nondeterminism injectable, so the retry and give-up paths can be tested. */
  fun <T> withRefusingPort(
    pickPort: () -> Int,
    isRefusing: (Int) -> Boolean,
    body: (Int) -> T,
  ): T {
    val tried = mutableListOf<Int>()
    repeat(PORT_ATTEMPTS) {
      val port = pickPort()
      tried += port
      if (isRefusing(port)) return body(port)
    }
    error(
      "no candidate port was still refusing when it was checked, after $PORT_ATTEMPTS attempts " +
        "(tried: $tried). Either something on this machine is taking these ports as fast as they " +
        "are released, or connects to closed ports on this host hang past " +
        "${REFUSAL_CHECK_TIMEOUT_MS}ms instead of being refused.",
    )
  }

  /**
   * Whether the port actively refuses. Only `ConnectException` counts: a connect that times out
   * means something IS holding the port quietly — the `HELD_UNRESPONSIVE` condition this harness
   * exists to keep away from a test asserting `NO_ANSWER`.
   */
  fun refusesConnections(port: Int): Boolean = refusesConnections {
    Socket().use {
      it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), REFUSAL_CHECK_TIMEOUT_MS)
    }
  }

  /** [refusesConnections] with the connect injectable, since a real connect timeout cannot be provoked on demand. */
  fun refusesConnections(connect: () -> Unit): Boolean =
    try {
      connect()
      false
    } catch (_: ConnectException) {
      true
    } catch (_: IOException) {
      false
    }

  /**
   * What this host reserves for outbound connections, or null when it cannot be read. Linux
   * publishes it as a file — two numbers on one line — and is where CI runs; an unreadable `/proc`
   * has no opinion rather than an error.
   */
  fun hostEphemeralRange(): IntRange? =
    runCatching { File("/proc/sys/net/ipv4/ip_local_port_range").readText() }
      .getOrNull()
      ?.trim()
      ?.split(Regex("\\s+"))
      ?.mapNotNull(String::toIntOrNull)
      ?.takeIf { it.size == 2 && it[0] <= it[1] }
      ?.let { (start, end) -> start..end }
}
