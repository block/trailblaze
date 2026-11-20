package xyz.block.trailblaze.host.yaml

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import xyz.block.trailblaze.config.TargetDeviceClassifierConfig
import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.mcp.android.ondevice.rpc.OnDeviceRunnerCapabilities

class DesktopYamlRunnerDeviceClassifierCapabilityTest {

  @Test
  fun `ordinary runs do not require a runner capability`() {
    assertNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = false,
        runnerCapabilities = null,
      ),
    )
  }

  @Test
  fun `classifier override is accepted when the runner advertises support`() {
    assertNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = true,
        runnerCapabilities = OnDeviceRunnerCapabilities.ALL,
      ),
    )
  }

  @Test
  fun `classifier override is rejected when a stale runner advertises no support`() {
    assertNotNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = true,
        runnerCapabilities = null,
      ),
    )
  }

  @Test
  fun `an unrelated runner capability does not authorize classifier overrides`() {
    assertNotNull(
      DesktopYamlRunner.deviceClassifierOverrideCapabilityRejection(
        requestsOverride = true,
        runnerCapabilities = listOf("some-other-capability"),
      ),
    )
  }

  @Test
  fun `a computed device key is refused on drivers that would ignore it`() {
    val key = listOf("desktop", "testrack").map(::TrailblazeDeviceClassifier)
    listOf(TrailblazeDriverType.COMPOSE, TrailblazeDriverType.PLAYWRIGHT_ELECTRON, TrailblazeDriverType.REVYL_ANDROID)
      .forEach { driver -> assertNotNull(DesktopYamlRunner.deviceClassifierIgnoredByDriverRejection(driver, key), "$driver") }
    listOf(TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY, TrailblazeDriverType.IOS_AXE, TrailblazeDriverType.PLAYWRIGHT_NATIVE)
      .forEach { driver -> assertNull(DesktopYamlRunner.deviceClassifierIgnoredByDriverRejection(driver, key), "$driver") }
  }

  @Test
  fun `a borrowed device skips the target's device classifier and says so`() {
    val classifier = TargetDeviceClassifierConfig(name = "classifyDevice", script = "device-classifier.ts")
    val message = DesktopYamlRunner.borrowedDeviceClassifierSkipped("android/emulator-5554", classifier)
    assertTrue(message!!.contains("classifyDevice skipped"), message)
    assertNull(DesktopYamlRunner.borrowedDeviceClassifierSkipped("android/emulator-5554", null))
  }

  @Test
  fun `a multi-device run skips the target's device classifier and says so`() {
    val classifier = TargetDeviceClassifierConfig(name = "classifyDevice", script = "device-classifier.ts")
    val message = DesktopYamlRunner.multiDeviceClassifierSkipped("pair", classifier)
    assertTrue(message!!.contains("classifyDevice skipped"), message)
    assertNull(DesktopYamlRunner.multiDeviceClassifierSkipped(null, classifier))
    assertNull(DesktopYamlRunner.multiDeviceClassifierSkipped("pair", null))
  }

  @Test
  fun `a borrowed device takes a requested key only when it fits the lender's`() {
    fun key(value: String) = value.split("-").map(::TrailblazeDeviceClassifier)
    val lender = key("android-phone")
    assertEquals(key("android-phone-es"), DesktopYamlRunner.borrowedRunKey(lender, key("android-phone-es")))
    assertEquals(lender, DesktopYamlRunner.borrowedRunKey(lender, emptyList()))
    // A device family's key needs the target's classifier, which a borrowed device doesn't run.
    assertFailsWith<IllegalArgumentException> { DesktopYamlRunner.borrowedRunKey(lender, key("acme-k2")) }
    assertNull(DesktopYamlRunner.borrowedRunKey(null, key("acme-k2")))
  }
}
