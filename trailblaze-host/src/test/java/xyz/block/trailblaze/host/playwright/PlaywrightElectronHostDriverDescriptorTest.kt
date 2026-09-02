package xyz.block.trailblaze.host.playwright

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.system.measureTimeMillis
import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlaywrightElectronHostDriverDescriptorTest {

  /**
   * Serves `/json/version` at [status] on an ephemeral port the test owns, so the probe hits a
   * real socket without betting on any well-known CDP port being free on this machine.
   */
  private fun withCdpServer(status: Int, block: (port: Int) -> Unit) {
    val server = HttpServer.create(InetSocketAddress("localhost", 0), 0)
    try {
      server.createContext("/json/version") { exchange ->
        exchange.sendResponseHeaders(status, -1)
        exchange.close()
      }
      server.start()
      block(server.address.port)
    } finally {
      server.stop(0)
    }
  }

  private fun discover(port: Int) = runBlocking {
    PlaywrightElectronHostDriverDescriptor(cdpBaseUrlProvider = { "http://localhost:$port" })
      .discoverDevices(HostDeviceInventory.EMPTY)
  }

  /** An answering CDP endpoint IS the device — `web/playwright-electron` addresses it. */
  @Test
  fun `an answering CDP endpoint is discovered as the one electron device`() {
    withCdpServer(status = 200) { port ->
      val devices = discover(port)

      assertEquals(1, devices.size)
      assertEquals(TrailblazeDriverType.PLAYWRIGHT_ELECTRON, devices.single().trailblazeDriverType)
      assertEquals(WebInstanceIds.PLAYWRIGHT_ELECTRON, devices.single().instanceId)
    }
  }

  /**
   * An empty list is the whole availability signal — offering the device with no Electron app
   * listening would put an unconnectable entry in front of the user.
   */
  @Test
  fun `nothing is discovered when no endpoint is listening`() {
    // Bind-then-close reserves a port that is almost certainly still free. If something rebinds
    // it before the probe, every plausible outcome — refused, hang (bounded by the probe's own
    // timeout), or a non-200 answer — still reads as absence; only a hijacker serving HTTP 200 at
    // `/json/version` inside the window could flip this test. Holding the socket open instead
    // would change the scenario from "nothing listening" to "listening but mute", a different case.
    val freePort = ServerSocket(0).use { it.localPort }

    assertEquals(emptyList(), discover(freePort))
  }

  /**
   * The socket timeouts don't cover hostname resolution, so the probe carries its own wall-clock
   * bound. Without it a stalled step inside the probe holds the whole device-discovery pass until
   * its 60s deadline — the regression this guards is a discovery refresh that stops answering.
   */
  @Test
  fun `a probe that blocks past its bound reports absence instead of hanging discovery`() {
    val release = CountDownLatch(1)
    val descriptor = PlaywrightElectronHostDriverDescriptor(
      cdpBaseUrlProvider = {
        release.await(30, TimeUnit.SECONDS)
        "http://localhost:1"
      },
    )

    try {
      val elapsedMs = measureTimeMillis {
        assertEquals(emptyList(), runBlocking { descriptor.discoverDevices(HostDeviceInventory.EMPTY) })
      }

      // The bound is 1s and the blocked probe would otherwise take 30s; 5s discriminates those
      // two with room for a loaded agent, and is not a performance budget on real work.
      assertTrue(elapsedMs < 5_000, "probe should have given up at its bound, took ${elapsedMs}ms")
    } finally {
      release.countDown()
    }
  }

  /** An endpoint that answers but is unhealthy is absence, not presence — only 200 counts. */
  @Test
  fun `a non-200 answer is not a device`() {
    withCdpServer(status = 500) { port ->
      assertEquals(emptyList(), discover(port))
    }
  }

  @Test
  fun `playwright electron is a listed driver covering only its own entry`() {
    val descriptor = PlaywrightElectronHostDriverDescriptor()
    assertEquals(setOf(TrailblazeDriverType.PLAYWRIGHT_ELECTRON), descriptor.driverTypes)
    assertEquals(DeviceListingVisibility.LISTED, descriptor.listingVisibility)
  }
}
