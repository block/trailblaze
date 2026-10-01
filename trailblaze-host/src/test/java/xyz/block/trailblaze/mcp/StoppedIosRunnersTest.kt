package xyz.block.trailblaze.mcp

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getMaestroOnDeviceSpecificPort
import xyz.block.trailblaze.host.devices.HostIosDriverFactory

class StoppedIosRunnersTest {

  private val simulator = TrailblazeDeviceId(
    instanceId = "13AB7779-1EF4-4EFA-8BD2-696F9E7B9A52",
    trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
  )

  @Test
  fun `a live runner on its built port survives when the device default port differs`() {
    ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { runner ->
      assertNotEquals(simulator.getMaestroOnDeviceSpecificPort(), runner.localPort)
      val runners = StoppedIosRunners(HostIosDriverFactory::isRunnerReachable)
      var forgotten = false

      assertNull(runners.check(simulator, persistentDriverPort = runner.localPort) { forgotten = true })
      assertFalse(forgotten)
    }
  }

  @Test
  fun `a dead runner on its built port is dropped even when the device default port differs`() {
    val builtPort = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    assertNotEquals(simulator.getMaestroOnDeviceSpecificPort(), builtPort)
    val runners = StoppedIosRunners(HostIosDriverFactory::isRunnerReachable)
    var forgotten = false

    assertNotNull(runners.check(simulator, persistentDriverPort = builtPort) { forgotten = true })
    assertTrue(forgotten)
  }

  @Test
  fun `a runner still answering is ready and keeps its driver`() {
    ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { runner ->
      val runners = StoppedIosRunners(HostIosDriverFactory::isRunnerReachable)
      var forgotten = 0

      val status = runners.check(simulator, persistentDriverPort = runner.localPort) { forgotten++ }

      assertNull(status)
      assertEquals(0, forgotten, "a live driver was dropped")
    }
  }

  /**
   * The wedge: a runner killed under a cached driver read as ready, so the CLI reused the session
   * and every command waited out the capture retry and ended "No device connected".
   */
  @Test
  fun `a runner that stopped answering drops its driver and says the runner died`() {
    val port = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1")).use { it.localPort }
    val runners = StoppedIosRunners(HostIosDriverFactory::isRunnerReachable)
    var forgotten = 0

    val status = runners.check(simulator, persistentDriverPort = port) { forgotten++ }

    assertNotNull(status)
    assertEquals(1, forgotten, "the dead driver was kept, so the next connect would reuse it")
    assertTrue(simulator.instanceId in status, status)
    assertTrue("XCTest runner" in status, status)
    assertTrue("Reconnect the device" in status, status)
    assertFalse("No device connected" in status, status)
  }

  @Test
  fun `the stopped status outlives the dropped driver until a connect clears it`() {
    val runners = StoppedIosRunners(isRunnerReachable = { false })
    runners.check(simulator, persistentDriverPort = 1) {}

    // The driver is gone now. Reading ready here is what let the CLI skip the reconnect.
    val afterDrop = runners.check(simulator, persistentDriverPort = null) { error("nothing to drop") }
    assertNotNull(afterDrop)

    runners.clear(simulator.instanceId)
    assertNull(runners.check(simulator, persistentDriverPort = null) { error("nothing to drop") })
  }

  @Test
  fun `a probe overtaken by a connect records nothing and drops nothing`() {
    lateinit var runners: StoppedIosRunners
    runners = StoppedIosRunners(
      isRunnerReachable = {
        // A connect starts while this probe is still waiting on the old runner's port.
        runners.clear(simulator.instanceId)
        false
      },
    )

    val status = runners.check(simulator, persistentDriverPort = 1) {
      error("dropped the driver the new connect is building")
    }

    assertNull(status, "a stale probe reported the runner stopped after the connect cleared it")
  }

  @Test
  fun `a connect that starts mid-teardown waits for it to finish`() {
    val runners = StoppedIosRunners(isRunnerReachable = { false })
    lateinit var connect: Thread
    var connectStateDuringTeardown: Thread.State? = null

    runners.check(simulator, persistentDriverPort = 1) {
      connect = Thread { runners.clear(simulator.instanceId) }.apply { start() }
      // Hang guard only: a clear that does not wait for the teardown reaches TERMINATED at once.
      val deadline = System.currentTimeMillis() + 60_000
      while (connect.state != Thread.State.BLOCKED && connect.state != Thread.State.TERMINATED &&
        System.currentTimeMillis() < deadline
      ) {
        Thread.sleep(5)
      }
      connectStateDuringTeardown = connect.state
    }
    connect.join(60_000)

    assertEquals(Thread.State.BLOCKED, connectStateDuringTeardown, "the connect ran while the stale teardown was still dropping its driver")
    assertNull(runners.check(simulator, persistentDriverPort = null) { error("nothing to drop") })
  }

  @Test
  fun `no persistent driver means no probe`() {
    val runners = StoppedIosRunners(isRunnerReachable = { error("probed a device with no driver to vouch for") })

    assertNull(runners.check(simulator, persistentDriverPort = null) { error("nothing to drop") })
  }
}
