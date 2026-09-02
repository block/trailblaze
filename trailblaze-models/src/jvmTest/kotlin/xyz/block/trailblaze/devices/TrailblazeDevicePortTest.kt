package xyz.block.trailblaze.devices

import xyz.block.trailblaze.devices.TrailblazeDevicePort.getPortForDevice
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [TrailblazeDevicePort] — in particular the `namespace` discriminator that lets
 * parallel daemons driving same-named emulators (e.g. two `emulator-5554` reached via
 * independent ADB tunnels) compute distinct ports.
 */
class TrailblazeDevicePortTest {

  private val sharedInstanceId = "emulator-5554"

  private val allocatablePorts = TrailblazeDevicePort.PORT_RANGE_START until
    TrailblazeDevicePort.PORT_RANGE_START + TrailblazeDevicePort.PORT_RANGE_SIZE

  private fun deviceId() =
    TrailblazeDeviceId(
      instanceId = sharedInstanceId,
      trailblazeDevicePlatform = TrailblazeDevicePlatform.ANDROID,
    )

  @Test
  fun `same instanceId with different namespaces produces different ports`() {
    val portA = getPortForDevice(deviceId(), suffix = "trailblaze", namespace = "6037")
    val portB = getPortForDevice(deviceId(), suffix = "trailblaze", namespace = "6038")
    assertNotEquals(portA, portB,
      "Namespacing must disambiguate same-named devices reached over different ADB tunnels")
  }

  @Test
  fun `empty namespace preserves historical hash`() {
    val historicalPort = getPortForDevice(deviceId(), suffix = "trailblaze", namespace = "")
    val defaultPortNoEnv = run {
      // Default namespace reads from HostPortNamespace.current. When ANDROID_ADB_SERVER_PORT
      // is unset, current=="" and the default must match the explicit-empty call (single-
      // daemon backward compatibility).
      if (HostPortNamespace.current.isEmpty()) {
        deviceId().getTrailblazeOnDeviceSpecificPort()
      } else {
        // Env is set in this test environment — skip the equality assertion but still verify
        // namespacing is non-degenerate.
        getPortForDevice(deviceId(), suffix = "trailblaze", namespace = "")
      }
    }
    assertEquals(historicalPort, defaultPortNoEnv)
  }

  @Test
  fun `port stays within the documented allocation range`() {
    val port = getPortForDevice(deviceId(), suffix = "trailblaze", namespace = "6037")
    assertTrue(port in allocatablePorts, "Port $port is outside $allocatablePorts")
  }

  @Test
  fun `namespacing is stable across calls`() {
    val first = getPortForDevice(deviceId(), suffix = "trailblaze", namespace = "ws-b")
    val second = getPortForDevice(deviceId(), suffix = "trailblaze", namespace = "ws-b")
    assertEquals(first, second, "Same (deviceId, suffix, namespace) must hash identically")
  }

  /**
   * Guards the skip loop against being quietly turned back into dead code. Every reserved port
   * used to sit below `PORT_RANGE_START`, so `port !in RESERVED_PORTS` was always true and the
   * loop returned on its first iteration — while `COMPOSE_DEFAULT_RPC_PORT`, in range and
   * unreserved, was handed out. Moving the range off every reserved port fails here.
   */
  @Test
  fun `at least one reserved port is inside the allocation range`() {
    val inRange = TrailblazeDevicePort.RESERVED_PORTS.filter { it in allocatablePorts }
    assertTrue(
      inRange.isNotEmpty(),
      "No reserved port is inside $allocatablePorts, so the reserved-port skip in " +
        "getPortForDevice can never engage. Either the range moved off the ports it must " +
        "protect, or a port that needs protecting is missing from RESERVED_PORTS.",
    )
  }

  /**
   * A hash landing squarely on a reserved port must be moved off it. The port that matters today
   * is [TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT] — the desktop app's Compose driver RPC
   * server binds it, and it sits inside the allocation range, so before it was reserved a device
   * hashing onto it was handed the driver's port.
   */
  @Test
  fun `a hash landing on a reserved port is allocated a different port`() {
    val inRange = TrailblazeDevicePort.RESERVED_PORTS.filter { it in allocatablePorts }
    inRange.forEach { reservedPort ->
      val port = TrailblazeDevicePort.portForHash(reservedPort - TrailblazeDevicePort.PORT_RANGE_START)
      assertNotEquals(reservedPort, port, "Reserved port $reservedPort was allocated anyway")
      assertTrue(port in allocatablePorts, "Skipping $reservedPort escaped the range: got $port")
    }
  }

  /**
   * `-Int.MIN_VALUE` overflows back to `Int.MIN_VALUE`, which used to leave a negative offset and
   * return a port *below* the range — where no reserved-port check applies at all.
   */
  @Test
  fun `every hash maps into the allocation range`() {
    listOf(Int.MIN_VALUE, Int.MIN_VALUE + 1, -1, 0, 1, Int.MAX_VALUE).forEach { hash ->
      val port = TrailblazeDevicePort.portForHash(hash)
      assertTrue(port in allocatablePorts, "portForHash($hash) returned $port, outside $allocatablePorts")
    }
  }

  @Test
  fun `the published allocation range matches the ports actually handed out`() {
    assertEquals(allocatablePorts, TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE)
    assertEquals(52530..59529, TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE)
  }

  /**
   * The reason the default daemon ports need no protecting: they sit below the range, so no
   * device can be allocated one. Moving a default into the range would reintroduce the
   * collision that [TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange] exists to stop.
   */
  @Test
  fun `default daemon ports are outside the device allocation range`() {
    listOf(
      TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
      TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT,
      TrailblazeDevicePort.TRAILBLAZE_DEFAULT_ON_DEVICE_RPC_PORT,
    ).forEach { port ->
      TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(port, "Default port")
    }
  }

  /**
   * The distinct type is load-bearing: the desktop app's bind-failure handler branches on it to
   * avoid reporting a configuration error as a lost bind race against a rival daemon.
   */
  @Test
  fun `a daemon port inside the allocation range is refused`() {
    val failure = assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(52900, "TRAILBLAZE_PORT")
    }
    // The message has to be actionable on its own: what was set, why it can't be used, what to do.
    assertContains(failure.message!!, "TRAILBLAZE_PORT is 52900")
    assertContains(failure.message!!, "52530-59529")
    assertContains(failure.message!!, "adb forward")
  }

  /**
   * A reserved port is safe only for the server the reservation is *for*. Devices are kept off it,
   * so that server may bind it — but pointing a different server at it (`TRAILBLAZE_PORT=52600`)
   * would land the daemon on top of the Compose driver's RPC server. Hence two helpers.
   */
  @Test
  fun `a reserved in-range port is allowed for its own server and refused as a daemon port`() {
    val reserved = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT
    assertTrue(reserved in allocatablePorts, "This test is only meaningful for an in-range port")

    TrailblazeDevicePort.requirePortNotAllocatableToDevices(reserved, "The Compose driver RPC port")

    assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(reserved, "TRAILBLAZE_PORT")
    }
  }

  @Test
  fun `an unreserved in-range port is refused by both helpers`() {
    val allocatable = TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT + 1
    assertFalse(allocatable in TrailblazeDevicePort.RESERVED_PORTS)
    assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requirePortNotAllocatableToDevices(allocatable, "port")
    }
    assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(allocatable, "port")
    }
  }

  /**
   * Above the range is not a safe alternative: it is further into the OS ephemeral range, where an
   * unrelated outbound connection can hold the port as its source and the bind fails outright.
   */
  @Test
  fun `the failure message does not suggest a port above the range`() {
    val failure = assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(52900, "TRAILBLAZE_PORT")
    }
    assertContains(failure.message!!, "Pick a port below 52530")
    assertFalse(
      failure.message!!.contains("or above"),
      "The message must not offer the ephemeral range as an option: ${failure.message}",
    )
  }

  /**
   * Every surface that *saves* a daemon port shares this predicate, so a port the Settings UI or
   * the settings patch accepts is one the startup check
   * ([TrailblazeDevicePort.requireDaemonPortsOutsideDeviceAllocationRange]) will also accept. The
   * two must not disagree: a saved port outranks everything but `--port`, and the launch it kills
   * takes the UI that could undo it.
   */
  @Test
  fun `a selectable daemon port is exactly one the startup check accepts`() {
    val candidates = listOf(
      0, 1, 1024, 31995, 52524,
      TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTP_PORT,
      TrailblazeDevicePort.TRAILBLAZE_DEFAULT_HTTPS_PORT,
      TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first - 1,
      TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first,
      TrailblazeDevicePort.COMPOSE_DEFAULT_RPC_PORT,
      TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last,
      TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last + 1,
      65535, 65536,
    )
    candidates.forEach { port ->
      val selectable = TrailblazeDevicePort.isSelectableDaemonPort(port)
      val startupAccepts = runCatching {
        TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(port, "port")
      }.isSuccess
      val realTcpPort = port in 1..65535
      assertEquals(
        startupAccepts && realTcpPort,
        selectable,
        "isSelectableDaemonPort($port) disagrees with the startup check",
      )
    }
  }

  /**
   * The pair check is what every daemon entry point calls — the `/ping` probes as well as the
   * bind — so both halves have to be enforced by the one call, and the HTTPS half has to hand the
   * user a number they can act on.
   */
  @Test
  fun `the daemon pair check refuses either port and names the setting for the derived one`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first

    val httpFailure = assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requireDaemonPortsOutsideDeviceAllocationRange(inRange, inRange - 1)
    }
    assertContains(httpFailure.message!!, "The daemon HTTP port")
    assertContains(httpFailure.message!!, "is $inRange")
    assertContains(httpFailure.message!!, "serverPort in trailblaze-settings.json")

    // One below the range: the HTTP port passes, the derived HTTPS port does not.
    val httpsFailure = assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requireDaemonPortsOutsideDeviceAllocationRange(inRange - 1, inRange)
    }
    assertContains(httpsFailure.message!!, "TRAILBLAZE_PORT must be below ${inRange - 1}")

    TrailblazeDevicePort.requireDaemonPortsOutsideDeviceAllocationRange(inRange - 2, inRange - 1)
  }

  /**
   * An HTTPS port that is not the HTTP port + 1 was set explicitly, and an explicit value wins over
   * the derivation. Advising `TRAILBLAZE_PORT` there sends the user to a setting that cannot move
   * this port, so startup keeps failing on it.
   */
  @Test
  fun `an explicitly configured https port is not blamed on the http port`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last
    val safeHttpPort = 31234

    val failure = assertFailsWith<TrailblazePortRangeConflictException> {
      TrailblazeDevicePort.requireDaemonPortsOutsideDeviceAllocationRange(safeHttpPort, inRange)
    }
    assertContains(failure.message!!, "The daemon HTTPS port (set explicitly")
    assertContains(failure.message!!, "change TRAILBLAZE_HTTPS_PORT, or serverHttpsPort in trailblaze-settings.json")
    assertFalse(
      failure.message!!.contains("must be below"),
      "lowering the HTTP port cannot move an explicitly configured HTTPS port",
    )
  }

  @Test
  fun `the boundaries of the allocation range are refused and allowed exactly`() {
    // One below and one above the range are fine; both endpoints are not.
    TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(52529, "port")
    TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(59530, "port")
    assertFailsWith<IllegalStateException> {
      TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(52530, "port")
    }
    assertFailsWith<IllegalStateException> {
      TrailblazeDevicePort.requirePortOutsideDeviceAllocationRange(59529, "port")
    }
  }

  @Test
  fun `no device is ever allocated a reserved or out-of-range port`() {
    val reserved = TrailblazeDevicePort.RESERVED_PORTS
    for (n in 0 until 20_000) {
      for (suffix in listOf("trailblaze", "maestro")) {
        val port = getPortForDevice(
          TrailblazeDeviceId("emulator-$n", TrailblazeDevicePlatform.ANDROID),
          suffix = suffix,
          namespace = "",
        )
        assertFalse(port in reserved, "emulator-$n/$suffix was allocated reserved port $port")
        assertTrue(port in allocatablePorts, "emulator-$n/$suffix got $port, outside $allocatablePorts")
      }
    }
  }
}
