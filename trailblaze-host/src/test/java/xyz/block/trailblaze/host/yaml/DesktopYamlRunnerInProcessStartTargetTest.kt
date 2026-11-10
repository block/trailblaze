package xyz.block.trailblaze.host.yaml

import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.FakeHostAppTarget
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * An in-process start device runs the app the host instrumented for the run's target. A start
 * device that declares another app is refused by name instead of being instrumented as the wrong one.
 */
class DesktopYamlRunnerInProcessStartTargetTest {

  private val runTarget = FakeHostAppTarget("seller")
  private val otherApp = FakeHostAppTarget("buyer")

  private fun refusal(driverType: TrailblazeDriverType, startDeviceTarget: FakeHostAppTarget?) =
    DesktopYamlRunner.inProcessStartDeviceTargetRefusal(
      driverType = driverType,
      configurationName = "pair",
      startDeviceName = "register",
      startDeviceTarget = startDeviceTarget,
      runTargetApp = runTarget,
    )

  @Test
  fun `an in-process start device declaring another app is refused, naming both targets`() {
    val reason = assertNotNull(refusal(TrailblazeDriverType.ANDROID_TEST, otherApp))
    assertTrue("'buyer'" in reason && "'seller'" in reason && "'register'" in reason, reason)
  }

  @Test
  fun `an in-process start device running the run's own target starts`() {
    assertNull(refusal(TrailblazeDriverType.ANDROID_TEST, FakeHostAppTarget("seller")))
    assertNull(refusal(TrailblazeDriverType.ANDROID_TEST, null))
  }

  @Test
  fun `another driver's start device may run its own target`() {
    assertNull(refusal(TrailblazeDriverType.DEFAULT_ANDROID, otherApp))
  }
}
