package xyz.block.trailblaze.logs.server

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import kotlin.test.assertContains
import kotlin.test.assertFailsWith

/**
 * The daemon must not bind a port a device could be allocated. A device's port doubles as a host
 * port (`adb forward tcp:<port> tcp:<port>`), and `adb forward` takes an already-bound host port
 * without reporting an error — so the daemon would not fail, it would just stop being reachable.
 * Refusing at startup turns that into a message the developer can act on.
 */
class TrailblazeMcpServerPortGuardTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private fun newServer(): TrailblazeMcpServer = TrailblazeMcpServer(
    logsRepo = LogsRepo(logsDir = tempFolder.newFolder("logs"), watchFileSystem = false),
    mcpBridge = NoopBridge,
    trailsDirProvider = { tempFolder.newFolder("trails") },
    targetTestAppProvider = { TrailblazeHostAppTarget.DefaultTrailblazeHostAppTarget },
    llmModelListsProvider = { emptySet() },
  )

  @Test
  fun `an http port inside the device allocation range is refused before binding`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first + 370
    val failure = assertFailsWith<IllegalStateException> {
      newServer().startStreamableHttpMcpServer(port = inRange, httpsPort = 31001)
    }
    assertContains(failure.message!!, "The daemon HTTP port")
    assertContains(failure.message!!, "is $inRange")
  }

  @Test
  fun `an https port inside the device allocation range is refused before binding`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last
    val failure = assertFailsWith<IllegalStateException> {
      newServer().startStreamableHttpMcpServer(port = 31000, httpsPort = inRange)
    }
    assertContains(failure.message!!, "The daemon HTTPS port")
    assertContains(failure.message!!, "is $inRange")
  }

  /**
   * The one HTTP port that passes its own check and then fails on the derived HTTPS port. The
   * message has to name the knob the user actually set, or the advice ("pick a port below 52530")
   * reads as already satisfied and there is nothing to act on.
   */
  @Test
  fun `a derived https port names the setting the user controls`() {
    val lastSafeHttpPort = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first - 1
    val failure = assertFailsWith<IllegalStateException> {
      newServer().startStreamableHttpMcpServer(
        port = lastSafeHttpPort,
        httpsPort = lastSafeHttpPort + 1,
      )
    }
    assertContains(failure.message!!, "derived as the HTTP port + 1")
    // The actionable number: not "below 52530", which 52529 already satisfies.
    assertContains(failure.message!!, "TRAILBLAZE_PORT must be below 52529")
  }

  /**
   * The same failure with an HTTPS port the user set themselves. `TRAILBLAZE_HTTPS_PORT` and a
   * persisted `serverHttpsPort` both outrank the HTTP + 1 derivation, so the derived-port advice
   * would send the developer to a setting that cannot move this port.
   */
  @Test
  fun `an explicitly configured https port points at the https setting`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first + 500
    val failure = assertFailsWith<IllegalStateException> {
      newServer().startStreamableHttpMcpServer(port = 31000, httpsPort = inRange)
    }
    assertContains(failure.message!!, "change TRAILBLAZE_HTTPS_PORT, or serverHttpsPort in trailblaze-settings.json")
  }
}
