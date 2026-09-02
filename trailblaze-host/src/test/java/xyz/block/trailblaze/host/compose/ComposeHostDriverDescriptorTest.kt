package xyz.block.trailblaze.host.compose

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ComposeHostDriverDescriptorTest {

  /**
   * Serves `/ping` at [status] on an ephemeral port the test owns, so discovery probes a real
   * socket without betting on the production port being free on this machine.
   */
  private fun withPingServer(status: Int, block: (port: Int) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    try {
      server.createContext("/ping") { exchange ->
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
      }
      server.start()
      block(server.address.port)
    } finally {
      server.stop(0)
    }
  }

  /**
   * The one logical instance per host: a responding RPC server IS the device, and `desktop/self`
   * is how a trail addresses it.
   */
  @Test
  fun `a responding RPC server is discovered as the one self device`() {
    withPingServer(status = 200) { port ->
      val devices = runBlocking {
        ComposeHostDriverDescriptor(rpcProbePort = port)
          .discoverDevices(HostDeviceInventory.EMPTY)
      }

      assertEquals(1, devices.size)
      assertEquals(TrailblazeDriverType.COMPOSE, devices.single().trailblazeDriverType)
      assertEquals(ComposeHostDriverDescriptor.SELF_INSTANCE_ID, devices.single().instanceId)
    }
  }

  /**
   * An empty list is the whole availability signal — offering `desktop/self` with nothing
   * listening would put an unconnectable device in front of the user.
   */
  @Test
  fun `nothing is discovered when no server is listening`() {
    // Bind-then-close reserves a port that is almost certainly still free. If something rebinds
    // it before the probe, every plausible outcome — refused, hang (self-bounded at 500ms), or a
    // non-200 answer — still reads as absence; only a hijacker serving HTTP 200 at `/ping` inside
    // the window could flip this test. Holding the socket open instead would change the scenario
    // from "nothing listening" to "listening but mute", which is a different case.
    val freePort = ServerSocket(0).use { it.localPort }

    val devices = runBlocking {
      ComposeHostDriverDescriptor(rpcProbePort = freePort)
        .discoverDevices(HostDeviceInventory.EMPTY)
    }

    assertEquals(emptyList(), devices)
  }

  /** A server that answers but is unhealthy is absence, not presence — only 200 counts. */
  @Test
  fun `a non-200 ping answer is not a device`() {
    withPingServer(status = 500) { port ->
      val devices = runBlocking {
        ComposeHostDriverDescriptor(rpcProbePort = port)
          .discoverDevices(HostDeviceInventory.EMPTY)
      }

      assertEquals(emptyList(), devices)
    }
  }

  /**
   * Compose keeps the pre-descriptor manager behavior: no capture path at this level. Interactive
   * capture goes through the MCP bridge's own Compose RPC provider.
   */
  @Test
  fun `screen state is null because no manager-level capture path is wired`() {
    val screenState = runBlocking {
      ComposeHostDriverDescriptor().screenState(
        driverType = TrailblazeDriverType.COMPOSE,
        deviceId = xyz.block.trailblaze.devices.TrailblazeDeviceId(
          instanceId = ComposeHostDriverDescriptor.SELF_INSTANCE_ID,
          trailblazeDevicePlatform = TrailblazeDriverType.COMPOSE.platform,
        ),
        deps = HostScreenStateDeps(activeMaestroDriver = { null }),
      )
    }

    assertNull(screenState)
  }

  /** Listed like a local device; the web-mode gate on virtual devices stays in the manager (3b). */
  @Test
  fun `compose is a listed driver covering only the COMPOSE entry`() {
    val descriptor = ComposeHostDriverDescriptor()
    assertEquals(setOf(TrailblazeDriverType.COMPOSE), descriptor.driverTypes)
    assertEquals(DeviceListingVisibility.LISTED, descriptor.listingVisibility)
  }
}
