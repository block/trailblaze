package xyz.block.trailblaze.cli

import org.junit.Test
import xyz.block.trailblaze.cli.TestPorts.CANDIDATE_PORT_RANGE
import xyz.block.trailblaze.cli.TestPorts.DEFAULT_EPHEMERAL_START
import xyz.block.trailblaze.cli.TestPorts.LOWEST_UNPRIVILEGED_PORT
import xyz.block.trailblaze.cli.TestPorts.PORT_ATTEMPTS
import xyz.block.trailblaze.cli.TestPorts.candidateRangeFor
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import java.net.ConnectException
import java.net.SocketTimeoutException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [TestPorts] is a fixture, so nothing it gets wrong shows up as its own failure — it shows up as a
 * daemon-port test failing for a reason that has nothing to do with the daemon. These pin the two
 * properties every caller assumes: the band is safe, and "refusing" means refusing.
 */
class TestPortsTest {

  @Test
  fun `no port this hands out is one the daemon refuses`() {
    // Against `TrailblazeDevicePort` itself, not a copy of the boundary, so this moves with it.
    assertTrue(
      TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.none { it in CANDIDATE_PORT_RANGE },
      "the candidate band $CANDIDATE_PORT_RANGE overlaps the device-allocation range " +
        "${TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE}, so a probe may never run",
    )

    repeat(HARNESS_SAMPLES) {
      TestPorts.openPortInCandidateBand().use { socket ->
        assertFalse(
          socket.localPort in TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE,
          "a port inside the device range is refused before any probe runs; got ${socket.localPort}",
        )
      }
    }
  }

  @Test
  fun `no port this hands out is one the OS can assign to someone else`() {
    assertTrue(
      CANDIDATE_PORT_RANGE.last < DEFAULT_EPHEMERAL_START,
      "candidate band $CANDIDATE_PORT_RANGE reaches into the ephemeral range at $DEFAULT_EPHEMERAL_START",
    )

    // And against the machine actually running it, not only the default.
    val hostEphemeral = TestPorts.hostEphemeralRange() ?: return
    assertTrue(
      CANDIDATE_PORT_RANGE.last < hostEphemeral.first || CANDIDATE_PORT_RANGE.first > hostEphemeral.last,
      "this host hands out ephemeral ports from $hostEphemeral, which overlaps the candidate band " +
        "$CANDIDATE_PORT_RANGE — an outbound connection here can take a port mid-test",
    )
  }

  @Test
  fun `a host that reserves more than the default gets a lower band`() {
    // A container with a lowered `ip_local_port_range`. Driven with the host answer injected,
    // because no developer machine has one.
    val lowered = candidateRangeFor(hostEphemeral = 10_240..60_999)

    assertTrue(lowered.last < 10_240, "the band must clear the floor this host reports; got $lowered")
    assertTrue(lowered.first > LOWEST_UNPRIVILEGED_PORT, "and stay out of the privileged range; got $lowered")
    assertTrue(lowered.first < lowered.last, "and not be empty; got $lowered")
  }

  @Test
  fun `a host that reserves less than the default does not widen the band`() {
    // Believing a host in both directions would let a machine with a high floor hand out ports
    // from inside the device-allocation range.
    assertEquals(
      candidateRangeFor(hostEphemeral = null),
      candidateRangeFor(hostEphemeral = 60_999..65_535),
      "a roomier host earns no extra ports — above the default lies the device-allocation range",
    )
  }

  @Test
  fun `a host whose range starts at the privileged line gets the band above that range instead`() {
    // Reading only the range's lower bound would give up here, even though the host has left a
    // wide gap between where its range ends and where the default ephemeral floor begins.
    val above = candidateRangeFor(hostEphemeral = LOWEST_UNPRIVILEGED_PORT..10_000)

    assertTrue(above.first > 10_000, "the band must clear the range this host reports; got $above")
    assertTrue(
      above.last < DEFAULT_EPHEMERAL_START,
      "and stop below the default ephemeral floor, above which lies the device range; got $above",
    )
  }

  @Test
  fun `a host with nowhere safe on either side says so instead of handing out port 1`() {
    val failure = assertFailsWith<IllegalStateException> {
      candidateRangeFor(hostEphemeral = LOWEST_UNPRIVILEGED_PORT..60_999)
    }

    assertTrue(
      "ip_local_port_range" in failure.message.orEmpty(),
      "the give-up must name the setting to change; got: <<${failure.message}>>",
    )
  }

  @Test
  fun `ports are not handed out from one fixed starting point`() {
    // Several test JVMs run at once on a developer machine; a scan that always starts at the same
    // number hands every one of them the same port first.
    val ports = (1..HARNESS_SAMPLES).map {
      TestPorts.openPortInCandidateBand().use { socket -> socket.localPort }
    }

    assertTrue(
      ports.toSet().size > 1,
      "every sample was the same port, so concurrent JVMs would collide on it; got ${ports.first()}",
    )
  }

  @Test
  fun `the refusal check tells a closed port from a listening one`() {
    // The closed half re-picks for the same reason `withRefusingPort` does: a rival process can
    // bind the port between the close and the check.
    repeat(PORT_ATTEMPTS) {
      val port = TestPorts.openPortInCandidateBand().use { server ->
        assertFalse(
          TestPorts.refusesConnections(server.localPort),
          "a bound socket accepts, so treating it as refusing would hand a held port to a test",
        )
        server.localPort
      }
      if (TestPorts.refusesConnections(port)) return
    }

    error("no released port ever reported a refusal, so the guard cannot recognise a closed port")
  }

  @Test
  fun `silence is not a refusal, so a port that times out is not handed over`() {
    assertTrue(
      TestPorts.refusesConnections { throw ConnectException("connection refused") },
      "a refused connect is the closed port these tests need",
    )
    assertFalse(
      TestPorts.refusesConnections { throw SocketTimeoutException("connect timed out") },
      "a connect that timed out means something is holding the port silently, not refusing",
    )
    assertFalse(
      TestPorts.refusesConnections { },
      "a connect that succeeded means someone is listening",
    )
  }

  @Test
  fun `a port that is no longer refusing is replaced instead of used`() {
    val offered = listOf(40_001, 40_002, 40_003)
    val checked = mutableListOf<Int>()

    val used = TestPorts.withRefusingPort(
      pickPort = offered.iterator()::next,
      isRefusing = { port -> checked += port; port == offered.last() },
      body = { it },
    )

    assertEquals(offered.last(), used, "the body must only ever see a port that was still refusing")
    assertEquals(offered, checked, "every earlier candidate must be checked and discarded in turn")
  }

  @Test
  fun `giving up on finding a refusing port says what it tried`() {
    var bodyRan = false

    val failure = assertFailsWith<IllegalStateException> {
      TestPorts.withRefusingPort(
        pickPort = { 40_007 },
        isRefusing = { false },
        body = { bodyRan = true },
      )
    }

    assertFalse(bodyRan, "the body must not run on a port that never passed the refusal check")
    // "$PORT_ATTEMPTS attempts", not just the number: 50 on its own also matches the 250ms timeout
    // printed in the same sentence.
    assertTrue(failure.message!!.contains("$PORT_ATTEMPTS attempts"), "expected the attempt count: $failure")
    assertTrue(failure.message!!.contains("40007"), "expected the ports it tried: $failure")
  }

  private companion object {
    /** Enough draws that an all-identical result means a fixed start, not bad luck. */
    const val HARNESS_SAMPLES = 20
  }
}
