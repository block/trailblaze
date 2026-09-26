package xyz.block.trailblaze.mcp

import kotlinx.coroutines.runBlocking
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY
import xyz.block.trailblaze.devices.TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION
import xyz.block.trailblaze.mcp.TrailblazeMcpBridgeImpl.Companion.rememberedDriverType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RememberedDriverTypeTest {

  private val emulator = TrailblazeDeviceId("emulator-5554", TrailblazeDevicePlatform.ANDROID)

  private fun variant(type: TrailblazeDriverType, instanceId: String = emulator.instanceId) =
    TrailblazeConnectedDeviceSummary(type, instanceId, description = instanceId)

  private val bothDrivers = listOf(variant(ANDROID_ONDEVICE_INSTRUMENTATION), variant(ANDROID_ONDEVICE_ACCESSIBILITY))

  /** A device scan asks every attached device, and every forwarded tool call used to run one. */
  @Test
  fun `a device the last scan saw is answered without scanning again`() = runBlocking {
    var scans = 0

    val driver = rememberedDriverType(
      emulator,
      remembered = bothDrivers,
      scan = { scans++; bothDrivers },
      configuredDriverType = { ANDROID_ONDEVICE_ACCESSIBILITY },
    )

    assertEquals(ANDROID_ONDEVICE_ACCESSIBILITY, driver)
    assertEquals(0, scans)
  }

  @Test
  fun `a changed driver setting takes effect without a new scan`() = runBlocking {
    val driver = rememberedDriverType(
      emulator,
      remembered = bothDrivers,
      scan = { error("no scan expected") },
      configuredDriverType = { ANDROID_ONDEVICE_INSTRUMENTATION },
    )

    assertEquals(ANDROID_ONDEVICE_INSTRUMENTATION, driver)
  }

  @Test
  fun `a device the last scan missed is scanned for`() = runBlocking {
    var scans = 0

    val driver = rememberedDriverType(
      emulator,
      remembered = listOf(variant(ANDROID_ONDEVICE_ACCESSIBILITY, instanceId = "emulator-5556")),
      scan = { scans++; bothDrivers },
      configuredDriverType = { ANDROID_ONDEVICE_ACCESSIBILITY },
    )

    assertEquals(ANDROID_ONDEVICE_ACCESSIBILITY, driver)
    assertEquals(1, scans)
  }

  /** A driver whose discovery failed once must not leave the device on another driver for good. */
  @Test
  fun `a scan that missed the configured driver is not trusted`() = runBlocking {
    var scans = 0

    val driver = rememberedDriverType(
      emulator,
      remembered = listOf(variant(ANDROID_ONDEVICE_INSTRUMENTATION)),
      scan = { scans++; bothDrivers },
      configuredDriverType = { ANDROID_ONDEVICE_ACCESSIBILITY },
    )

    assertEquals(ANDROID_ONDEVICE_ACCESSIBILITY, driver)
    assertEquals(1, scans)
  }

  @Test
  fun `a device no scan finds has no driver`() = runBlocking {
    assertNull(
      rememberedDriverType(
        emulator,
        remembered = emptyList(),
        scan = { emptyList() },
        configuredDriverType = { ANDROID_ONDEVICE_ACCESSIBILITY },
      ),
    )
  }
}
