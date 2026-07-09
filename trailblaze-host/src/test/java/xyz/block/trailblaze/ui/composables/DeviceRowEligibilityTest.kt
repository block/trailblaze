package xyz.block.trailblaze.ui.composables

import kotlin.reflect.KClass
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.model.TrailblazeHostAppTarget
import xyz.block.trailblaze.toolcalls.TrailblazeTool

/**
 * Pins the Run Configuration dialog's per-row eligibility gate for virtual devices: a virtual
 * row (web browser, Compose desktop) is always selectable — there is no app to install on it —
 * even when the selected product target doesn't declare that platform. Without the bypass, a
 * compose-only trail's Compose row was listed but disabled under a mobile target, leaving the
 * trail unrunnable without switching targets.
 */
class DeviceRowEligibilityTest {

  /** A product target that declares mobile platforms only — no WEB, no DESKTOP. */
  private object MobileOnlyTarget :
    TrailblazeHostAppTarget(id = "mobile-only", displayName = "Mobile Only") {
    override fun getPossibleAppIdsForPlatform(platform: TrailblazeDevicePlatform): List<String>? =
      when (platform) {
        TrailblazeDevicePlatform.ANDROID -> listOf("com.example.app")
        TrailblazeDevicePlatform.IOS -> listOf("com.example.app")
        else -> null
      }

    override fun internalGetCustomToolsForDriver(
      driverType: TrailblazeDriverType,
    ): Set<KClass<out TrailblazeTool>> = emptySet()
  }

  private val composeDesktop = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.COMPOSE,
    instanceId = "self",
    description = "Compose Desktop (RPC)",
  )
  private val webNative = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    instanceId = "playwright-native",
    description = "Playwright Browser (Native)",
  )
  private val android = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY,
    instanceId = "emulator-5554",
    description = "Pixel emulator",
  )

  @Test
  fun `virtual devices are eligible under a target that doesn't declare their platform`() {
    assertTrue(composeDesktop.isEligibleFor(MobileOnlyTarget, emptyMap()))
    assertTrue(webNative.isEligibleFor(MobileOnlyTarget, emptyMap()))
  }

  @Test
  fun `real devices still gate on the target's app being installed`() {
    // Android is a declared platform but the app isn't installed → ineligible.
    assertFalse(android.isEligibleFor(MobileOnlyTarget, emptyMap()))
    assertTrue(
      android.isEligibleFor(
        MobileOnlyTarget,
        mapOf(android.trailblazeDeviceId to setOf("com.example.app")),
      ),
    )
  }

  @Test
  fun `no selected target keeps every row eligible`() {
    assertTrue(android.isEligibleFor(null, emptyMap()))
    assertTrue(composeDesktop.isEligibleFor(null, emptyMap()))
  }
}
