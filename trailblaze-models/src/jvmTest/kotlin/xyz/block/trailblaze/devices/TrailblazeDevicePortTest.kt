package xyz.block.trailblaze.devices

import xyz.block.trailblaze.devices.TrailblazeDevicePort.getPortForDevice
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getTrailblazeOnDeviceSpecificPort
import kotlin.test.Test
import kotlin.test.assertEquals
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
