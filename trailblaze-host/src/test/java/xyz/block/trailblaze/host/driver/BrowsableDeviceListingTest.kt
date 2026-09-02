package xyz.block.trailblaze.host.driver

import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `trailblaze device list`, the daemon's connected-devices RPC, and the recording tab's dropdown
 * each open with the same list. They used to build it from three hand-synced copies of these
 * rules; these tests pin the shared one they now call.
 */
class BrowsableDeviceListingTest {

  private fun device(
    driverType: TrailblazeDriverType,
    instanceId: String,
  ) = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = driverType,
    instanceId = instanceId,
    description = "$driverType/$instanceId",
  )

  private val cloudDriver = TrailblazeDriverType.REVYL_ANDROID
  private val localDriver = TrailblazeDriverType.ANDROID_ONDEVICE_ACCESSIBILITY

  private val registry = HostDriverDescriptorRegistry(
    setOf(
      FakeHostDriverDescriptor(
        cloudDriver,
        listingVisibility = DeviceListingVisibility.ADDRESSABLE_NOT_LISTED,
      ),
    ),
  )

  private fun filter(
    devices: List<TrailblazeConnectedDeviceSummary>,
    descriptors: HostDriverDescriptorRegistry = registry,
    runningWebBrowsers: List<TrailblazeConnectedDeviceSummary> = emptyList(),
    showHiddenPlatforms: Boolean = false,
  ) = BrowsableDeviceListing.filter(devices, descriptors, runningWebBrowsers, showHiddenPlatforms)

  @Test
  fun `an addressable-but-unlisted driver stays out of the listing`() {
    val result = filter(listOf(device(cloudDriver, "revyl-android-phone"), device(localDriver, "emulator-5554")))

    assertFalse(
      result.any { it.trailblazeDriverType == cloudDriver },
      "a cloud device should not clutter the local device list: $result",
    )
    assertTrue(result.any { it.instanceId == "emulator-5554" }, "the local device must survive: $result")
  }

  /**
   * The visibility rule has to come from the descriptor, not from a driver name baked in here —
   * that hardcoding is what made three copies drift in the first place.
   */
  @Test
  fun `the same driver is listed when its descriptor says so`() {
    val listedRegistry = HostDriverDescriptorRegistry(
      setOf(FakeHostDriverDescriptor(cloudDriver, listingVisibility = DeviceListingVisibility.LISTED)),
    )

    val result = filter(listOf(device(cloudDriver, "revyl-android-phone")), descriptors = listedRegistry)

    assertTrue(result.any { it.trailblazeDriverType == cloudDriver }, "expected it listed: $result")
  }

  /** A driver with no descriptor keeps its old behavior: listed. */
  @Test
  fun `an unconverted driver is listed`() {
    val result = filter(listOf(device(localDriver, "emulator-5554")), descriptors = HostDriverDescriptorRegistry.EMPTY)

    assertTrue(result.any { it.instanceId == "emulator-5554" }, "expected it listed: $result")
  }

  @Test
  fun `hidden platforms are dropped unless asked for`() {
    val composeDesktop = device(TrailblazeDriverType.COMPOSE, "self")
    assertTrue(composeDesktop.platform.hidden, "this test needs a hidden-platform driver to be meaningful")

    assertFalse(filter(listOf(composeDesktop)).any { it.instanceId == "self" })
    assertTrue(filter(listOf(composeDesktop), showHiddenPlatforms = true).any { it.instanceId == "self" })
  }

  /**
   * Running browsers are re-added because the device manager's own filter strips them when web
   * mode is off, even though they are real and the user needs `--device web/<id>` to keep working.
   */
  @Test
  fun `a running browser the upstream filter stripped is re-added`() {
    val running = device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "my-browser")

    val result = filter(devices = emptyList(), runningWebBrowsers = listOf(running))

    assertTrue(result.any { it.instanceId == "my-browser" }, "expected the running browser back: $result")
  }

  @Test
  fun `a running browser already in the list is not duplicated`() {
    val browser = device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "my-browser")

    val result = filter(devices = listOf(browser), runningWebBrowsers = listOf(browser))

    assertEquals(1, result.count { it.instanceId == "my-browser" }, "expected one entry, got: $result")
  }

  /** Bare `--device web` resolves to this, so it is offered even when nothing is running. */
  @Test
  fun `the playwright-native default is always offered`() {
    val result = filter(emptyList())

    assertTrue(
      result.any { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE },
      "expected the always-available web default: $result",
    )
  }

  /**
   * Named web instances are PLAYWRIGHT_NATIVE too. Matching on driver type instead of instance id
   * would let one of them suppress the canonical default that bare `--device web` needs.
   */
  @Test
  fun `a named web instance does not suppress the canonical default`() {
    val named = device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, "my-browser")

    val result = filter(devices = listOf(named))

    assertTrue(
      result.any { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE },
      "the default must still be there alongside the named instance: $result",
    )
    assertTrue(result.any { it.instanceId == "my-browser" }, "the named instance must survive: $result")
  }

  @Test
  fun `the canonical default is not added twice when it is already present`() {
    val canonical = device(TrailblazeDriverType.PLAYWRIGHT_NATIVE, WebInstanceIds.PLAYWRIGHT_NATIVE)

    val result = filter(devices = listOf(canonical))

    assertEquals(
      1,
      result.count { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE },
      "expected one entry, got: $result",
    )
  }
}
