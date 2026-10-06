package xyz.block.trailblaze.host

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.hasSize
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isInstanceOf
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import kotlinx.datetime.Clock
import maestro.DeviceInfo
import maestro.TreeNode
import maestro.device.Platform
import org.junit.Test
import xyz.block.trailblaze.api.DriverNodeDetail
import xyz.block.trailblaze.api.MigrationScreenState
import xyz.block.trailblaze.api.TrailblazeNode
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.devices.MaestroConnectedDevice
import xyz.block.trailblaze.host.screenstate.SecondaryTreeResult
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSession
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.viewmatcher.matching.ViewHierarchyOnlyDriver

/**
 * Maestro command logs (tap / swipe / input) are written from the screen state
 * [MaestroConnectedDevice.getLoggingDriver] builds internally, NOT from the runner's own
 * provider — so a tap log, the one a tap migration reads, only carries the secondary tree if
 * the decorator reaches that provider. These pin both halves: the driver applies whatever
 * decorator it is handed, and the iOS runner hands it the dual-tree wrap.
 */
class MaestroCommandLogSecondaryTreeTest {

  private val fakeUdid = "CCCCCCCC-4444-5555-6666-DDDDDDDDDDDD"

  private val noopLogger = TrailblazeLogger(
    logEmitter = { },
    screenStateLogger = { it.fileName },
  )

  private val sessionProvider = {
    TrailblazeSession(sessionId = SessionId("command-log-test"), startTime = Clock.System.now())
  }

  private fun axeTree(label: String) = TrailblazeNode(
    nodeId = 7,
    driverDetail = DriverNodeDetail.IosAxe(role = "AXButton", label = label),
  )

  /**
   * A driver that answers `contentDescriptor` and no-ops `takeScreenshot`, which is everything
   * [xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState]'s eager init needs.
   * Reported as Android so the init skips the iOS screenshot-rotation branch, which this fake
   * has no pixels for; the decorator seam under test is platform-agnostic.
   */
  private fun fakeDevice(): MaestroConnectedDevice = MaestroConnectedDevice(
    maestroDriver = ViewHierarchyOnlyDriver(
      rootTreeNode = TreeNode(
        attributes = mutableMapOf("bounds" to "[0,0][400,800]", "text" to "Continue"),
      ),
      deviceInfo = DeviceInfo(
        platform = Platform.ANDROID,
        widthPixels = 400,
        heightPixels = 800,
        widthGrid = 400,
        heightGrid = 800,
      ),
    ),
    trailblazeDriverType = TrailblazeDriverType.IOS_HOST,
    instanceId = fakeUdid,
  )

  @Test
  fun `the logging driver applies the decorator it is handed to every screen state it logs`() {
    val tree = axeTree("Continue")

    val loggingDriver = fakeDevice().getLoggingDriver(
      trailblazeLogger = noopLogger,
      sessionProvider = sessionProvider,
      screenStateDecorator = { MigrationScreenState.wrap(it, tree) },
    )

    val state = loggingDriver.getCurrentScreenState()

    assertThat(state).isInstanceOf(MigrationScreenState::class)
    assertThat((state as MigrationScreenState).driverMigrationTreeNode).isEqualTo(tree)
  }

  @Test
  fun `without a decorator the logging driver still logs the plain driver state`() {
    val loggingDriver = fakeDevice().getLoggingDriver(
      trailblazeLogger = noopLogger,
      sessionProvider = sessionProvider,
    )

    val state = loggingDriver.getCurrentScreenState()

    assertThat(state is MigrationScreenState).isFalse()
  }

  @Test
  fun `the iOS runner's logging driver wraps command-log states with the axe tree`() {
    val tree = axeTree("Continue")

    val runner = MaestroHostRunnerImpl(
      trailblazeDeviceId = TrailblazeDeviceId(fakeUdid, TrailblazeDevicePlatform.IOS),
      trailblazeLogger = noopLogger,
      sessionProvider = sessionProvider,
      captureSecondaryTree = true,
      secondaryTreeCapture = { SecondaryTreeResult(tree) },
    )

    val state = runner.buildLoggingDriver(fakeDevice()).getCurrentScreenState()

    assertThat(state).isInstanceOf(MigrationScreenState::class)
    assertThat((state as MigrationScreenState).driverMigrationTreeNode).isEqualTo(tree)
  }

  @Test
  fun `a throwing axe read still yields a logged screen state, once-warned`() {
    val warnings = mutableListOf<String>()
    val runner = MaestroHostRunnerImpl(
      trailblazeDeviceId = TrailblazeDeviceId(fakeUdid, TrailblazeDevicePlatform.IOS),
      trailblazeLogger = noopLogger,
      sessionProvider = sessionProvider,
      captureSecondaryTree = true,
      secondaryTreeCapture = { throw RuntimeException("axe subprocess died") },
      migrationCaptureLog = { warnings.add(it) },
    )

    // No injected screen state factory: this goes through the real HostMaestroDriverScreenState
    // that getLoggingDriver builds, so it covers the production wiring end to end.
    val loggingDriver = runner.buildLoggingDriver(fakeDevice())
    val first = loggingDriver.getCurrentScreenState()
    val second = loggingDriver.getCurrentScreenState()

    assertThat(first).isInstanceOf(MigrationScreenState::class)
    assertThat((first as MigrationScreenState).driverMigrationTreeNode).isNull()
    assertThat(first.viewHierarchy).isNotNull()
    assertThat((second as MigrationScreenState).driverMigrationTreeNode).isNull()
    assertThat(warnings).hasSize(1)
    assertThat(warnings.single()).contains("axe subprocess died")
  }

  @Test
  fun `with capture off the runner's logging driver logs the plain driver state`() {
    val runner = MaestroHostRunnerImpl(
      trailblazeDeviceId = TrailblazeDeviceId(fakeUdid, TrailblazeDevicePlatform.IOS),
      trailblazeLogger = noopLogger,
      sessionProvider = sessionProvider,
      captureSecondaryTree = false,
      secondaryTreeCapture = { error("must not be called when capture is off") },
    )

    val state = runner.buildLoggingDriver(fakeDevice()).getCurrentScreenState()

    assertThat(state is MigrationScreenState).isFalse()
  }
}
