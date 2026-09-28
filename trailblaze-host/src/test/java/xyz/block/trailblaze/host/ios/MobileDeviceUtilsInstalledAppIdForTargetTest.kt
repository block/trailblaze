package xyz.block.trailblaze.host.ios

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Listing a device's installed apps is a device round trip (`simctl listapps` takes about a
 * second), and host runs resolve the target's app id on every CLI tool call. A target that declares
 * no app for the device's platform — the default target — can only resolve to null, so these pin
 * that it gets there without listing anything.
 */
class MobileDeviceUtilsInstalledAppIdForTargetTest {

  private object IosOnlyTarget : TrailblazeHostAppTarget(id = "ios-only", displayName = "iOS Only") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      when (platform) {
        TrailblazeDevicePlatform.IOS -> listOf("com.example.beta", "com.example.app")
        TrailblazeDevicePlatform.ANDROID -> emptyList()
        else -> null
      }

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val iosDevice = TrailblazeDeviceId("SIM-1", TrailblazeDevicePlatform.IOS)
  private val androidDevice = TrailblazeDeviceId("emulator-1", TrailblazeDevicePlatform.ANDROID)

  private class RecordingLister(private val installed: Set<String>) : (TrailblazeDeviceId) -> Set<String> {
    val listed = mutableListOf<TrailblazeDeviceId>()
    override fun invoke(deviceId: TrailblazeDeviceId): Set<String> = installed.also { listed += deviceId }
  }

  @Test
  fun `a target with no app for the device's platform resolves to null without listing apps`() {
    val lister = RecordingLister(setOf("com.example.app"))

    assertNull(MobileDeviceUtils.installedAppIdForTarget(IosOnlyTarget, androidDevice, lister))
    assertEquals(emptyList(), lister.listed)
  }

  @Test
  fun `a target that declares no platform entry at all resolves to null without listing apps`() {
    val lister = RecordingLister(setOf("https://example.com"))
    val webDevice = TrailblazeDeviceId("browser-1", TrailblazeDevicePlatform.WEB)

    assertNull(MobileDeviceUtils.installedAppIdForTarget(IosOnlyTarget, webDevice, lister))
    assertEquals(emptyList(), lister.listed)
  }

  @Test
  fun `no target resolves to null without listing apps`() {
    val lister = RecordingLister(setOf("com.example.app"))

    assertNull(MobileDeviceUtils.installedAppIdForTarget(null, iosDevice, lister))
    assertEquals(emptyList(), lister.listed)
  }

  @Test
  fun `a target with apps for the platform lists the device once and picks the installed one`() {
    val lister = RecordingLister(setOf("com.apple.Preferences", "com.example.app"))

    assertEquals("com.example.app", MobileDeviceUtils.installedAppIdForTarget(IosOnlyTarget, iosDevice, lister))
    assertEquals(listOf(iosDevice), lister.listed)
  }

  @Test
  fun `session-start app info for a target with no app on the platform lists nothing`() {
    val lister = RecordingLister(setOf("com.example.app"))

    assertNull(
      MobileDeviceUtils.resolveTargetAppInfo(
        target = IosOnlyTarget,
        trailblazeDeviceId = androidDevice,
        listInstalledAppIds = lister,
      ),
    )
    assertEquals(emptyList(), lister.listed)
  }
}
