package xyz.block.trailblaze.host.revyl

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.revyl.RevylCliClient
import xyz.block.trailblaze.revyl.RevylDeviceTarget
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RevylHostDriverDescriptorTest {

  /**
   * One descriptor for both entries is what keeps the 10-second catalog probe running once per
   * discovery pass instead of twice. Splitting it into two descriptors would silently double it.
   */
  @Test
  fun `one descriptor covers both Revyl drivers`() {
    assertEquals(
      setOf(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS),
      RevylHostDriverDescriptor().driverTypes,
    )
  }

  /**
   * Cloud devices are addressable by name but stay out of browsable listings — the behavior three
   * separate listing surfaces used to hardcode by driver name.
   */
  @Test
  fun `Revyl devices are addressable but unlisted`() {
    assertEquals(
      DeviceListingVisibility.ADDRESSABLE_NOT_LISTED,
      RevylHostDriverDescriptor().listingVisibility,
    )
  }

  /**
   * No CLI means no Revyl. An empty list is the whole availability signal, so a descriptor that
   * returned its defaults anyway would put unreachable devices in front of the user.
   */
  @Test
  fun `no devices are discovered when the revyl CLI is missing`() {
    val descriptor = RevylHostDriverDescriptor(
      RevylCliClient(revylBinaryOverride = "revyl-binary-that-does-not-exist-a7f3c1"),
    )

    assertEquals(emptyList(), runBlocking { descriptor.discoverDevices(HostDeviceInventory.EMPTY) })
  }

  /** Offered whenever the CLI is present, since Revyl provisions these on demand. */
  @Test
  fun `both platform defaults are offered`() {
    assertEquals(
      listOf(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS),
      RevylHostDriverDescriptor.DEFAULT_DEVICES.map { it.trailblazeDriverType },
    )
  }

  /**
   * `RevylCliClient.startSession` parses model and OS version back out of the instance id, so the
   * `revyl-model:<model>::<os>` shape is a contract between discovery and the run path, not a
   * display string.
   */
  @Test
  fun `a catalog entry encodes model and OS version in its instance id`() {
    val devices = RevylHostDriverDescriptor.catalogDevices(
      listOf(RevylDeviceTarget(TrailblazeDevicePlatform.ANDROID, "Pixel 8", "14")),
    )

    assertEquals(1, devices.size)
    assertEquals("revyl-model:Pixel 8::14", devices.single().instanceId)
  }

  @Test
  fun `a catalog entry takes the driver matching its platform`() {
    val devices = RevylHostDriverDescriptor.catalogDevices(
      listOf(
        RevylDeviceTarget(TrailblazeDevicePlatform.ANDROID, "Pixel 8", "14"),
        RevylDeviceTarget(TrailblazeDevicePlatform.IOS, "iPhone 15", "17.2"),
      ),
    )

    assertEquals(
      listOf(TrailblazeDriverType.REVYL_ANDROID, TrailblazeDriverType.REVYL_IOS),
      devices.map { it.trailblazeDriverType },
    )
  }

  /**
   * Every discovered device has to round-trip to a usable `--device` spec, and the platform on the
   * id comes from the driver type — an iOS model landing on the Android driver would address the
   * wrong device rather than fail.
   */
  @Test
  fun `a catalog entry's device id carries its own platform`() {
    val device = RevylHostDriverDescriptor.catalogDevices(
      listOf(RevylDeviceTarget(TrailblazeDevicePlatform.IOS, "iPhone 15", "17.2")),
    ).single()

    assertEquals(TrailblazeDevicePlatform.IOS, device.trailblazeDeviceId.trailblazeDevicePlatform)
  }

  @Test
  fun `an empty catalog yields no extra devices`() {
    assertTrue(RevylHostDriverDescriptor.catalogDevices(emptyList()).isEmpty())
  }
}
