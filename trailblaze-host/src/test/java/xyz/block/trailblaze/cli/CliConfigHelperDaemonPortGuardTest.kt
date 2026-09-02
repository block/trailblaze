package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazePortRangeConflictException
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * Every CLI command that talks to the daemon gets its port from [CliConfigHelper], so a port a
 * device could be allocated has to fail there. It cannot fail later: the device's
 * `adb forward tcp:<port> tcp:<port>` answers `/ping`, so the probe that decides whether a daemon
 * is running reports a healthy one, and the command fails only once MCP initialization reaches a
 * device RPC server.
 */
class CliConfigHelperDaemonPortGuardTest {

  @get:Rule
  val tempFolder = TemporaryFolder()

  private val priorAppDataDir = System.getProperty("trailblaze.appdata.dir")

  @After
  fun restoreAppDataDirProperty() {
    if (priorAppDataDir == null) {
      System.clearProperty("trailblaze.appdata.dir")
    } else {
      System.setProperty("trailblaze.appdata.dir", priorAppDataDir)
    }
  }

  /** Persists [config] into a settings file of this test's own, not the developer's. */
  private fun persistPorts(serverPort: Int? = null, serverHttpsPort: Int? = null) {
    val appDataDir = tempFolder.newFolder("runtime", "appdata")
    System.setProperty("trailblaze.appdata.dir", appDataDir.absolutePath)
    val defaults = CliConfigHelper.defaultConfig()
    CliConfigHelper.writeConfig(
      defaults.copy(
        serverPort = serverPort ?: defaults.serverPort,
        serverHttpsPort = serverHttpsPort ?: defaults.serverHttpsPort,
      ),
    )
  }

  @Test
  fun `a persisted http port a device could be allocated fails resolution`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first + 11
    persistPorts(serverPort = inRange)

    val failure = assertFailsWith<TrailblazePortRangeConflictException> {
      CliConfigHelper.resolveEffectiveHttpPort()
    }
    assertContains(failure.message!!, "The daemon HTTP port")
    assertContains(failure.message!!, "is $inRange")
    // The only setting that can move a persisted port: there is no --port flag, no config key,
    // and a saved value outranks TRAILBLAZE_PORT.
    assertContains(failure.message!!, "serverPort in trailblaze-settings.json")
  }

  /**
   * An explicitly configured HTTPS port outranks the HTTP + 1 derivation, so telling the user to
   * lower `TRAILBLAZE_PORT` would leave startup failing on the same port.
   */
  @Test
  fun `an explicitly persisted https port names the https setting instead of TRAILBLAZE_PORT`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.last
    persistPorts(serverHttpsPort = inRange)

    val failure = assertFailsWith<TrailblazePortRangeConflictException> {
      CliConfigHelper.resolveEffectiveHttpPort()
    }
    assertContains(failure.message!!, "change TRAILBLAZE_HTTPS_PORT, or serverHttpsPort in trailblaze-settings.json")
    assertFalse(
      failure.message!!.contains("must be below"),
      "an explicitly set HTTPS port is not fixed by lowering the HTTP port",
    )
  }

  /**
   * Resolving the HTTP port checks the HTTPS port too: the daemon this command would spawn dies at
   * its own bind guard on either one, and the command would then report only an unreachable daemon.
   */
  @Test
  fun `resolving either port reports whichever one is unusable`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first
    persistPorts(serverHttpsPort = inRange)

    assertFailsWith<TrailblazePortRangeConflictException> {
      CliConfigHelper.resolveEffectiveHttpsPort()
    }
  }

  /**
   * A daemon that came up before this check existed is still listening on its port, and `--stop` /
   * `--status` are the only things that can end it — no settings change terminates a running
   * process. Validating the port there would leave it un-killable.
   */
  @Test
  fun `a daemon already running on an in-range port stays resolvable for shutdown`() {
    val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first + 42
    persistPorts(serverPort = inRange)

    assertFailsWith<TrailblazePortRangeConflictException> {
      CliConfigHelper.resolveEffectiveHttpPort()
    }
    assertEquals(inRange, CliConfigHelper.resolveRunningDaemonHttpPortUnchecked())
  }

  @Test
  fun `ports outside the allocation range resolve unchanged`() {
    persistPorts(serverPort = 31234, serverHttpsPort = 31235)

    assertEquals(31234, CliConfigHelper.resolveEffectiveHttpPort())
    assertEquals(31235, CliConfigHelper.resolveEffectiveHttpsPort())
  }

  /**
   * The auto-connect entry points are also reachable with a port that did not come from
   * [CliConfigHelper]. They must refuse it rather than probe it — a probe would succeed against
   * the device and the auto-start path would report a daemon that cannot exist.
   */
  @Test
  fun `the auto-connect entry points refuse a device-allocatable port instead of probing it`() =
    runBlocking {
      val inRange = TrailblazeDevicePort.DEVICE_ALLOCATION_PORT_RANGE.first + 370

      assertFailsWith<TrailblazePortRangeConflictException> {
        connectOrStartDaemonOneShot(inRange)
      }
      assertFailsWith<TrailblazePortRangeConflictException> {
        connectOrStartDaemonReusable(inRange)
      }
      Unit
    }
}
