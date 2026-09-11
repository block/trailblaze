package xyz.block.trailblaze.host.ios

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.BootedIosSimulator
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import kotlin.test.assertEquals
import kotlin.test.assertNull

class IosAxeHostDriverDescriptorTest {

  private val inventory = HostDeviceInventory(
    bootedIosSimulators = listOf(
      BootedIosSimulator(udid = "UDID-A", name = "iPhone 16"),
      BootedIosSimulator(udid = "UDID-B", name = "iPad Pro"),
    ),
  )

  private fun discover(axeAvailable: Boolean) = runBlocking {
    IosAxeHostDriverDescriptor(axeAvailable = { axeAvailable }).discoverDevices(inventory)
  }

  /** With the CLI present, AXe offers the same simulators under its own driver type. */
  @Test
  fun `booted simulators are offered under axe when its cli is installed`() {
    val devices = discover(axeAvailable = true)

    assertEquals(listOf("UDID-A", "UDID-B"), devices.map { it.instanceId })
    assertEquals(setOf(TrailblazeDriverType.IOS_AXE), devices.map { it.trailblazeDriverType }.toSet())
  }

  /**
   * The availability gate, which is the whole reason this driver is a separate descriptor from
   * [IosHostDriverDescriptor]. Listing an AXe device without the CLI installed puts an entry in
   * front of the user that fails at connect time with a confusing error.
   */
  @Test
  fun `no devices are offered when the axe cli is missing`() {
    assertEquals(emptyList(), discover(axeAvailable = false))
  }

  /**
   * The gate is on the driver, not the simulators: a host with AXe installed and nothing booted
   * still has no devices to offer.
   */
  @Test
  fun `an installed cli with no booted simulators yields no devices`() {
    val devices = runBlocking {
      IosAxeHostDriverDescriptor(axeAvailable = { true }).discoverDevices(HostDeviceInventory.EMPTY)
    }

    assertEquals(emptyList(), devices)
  }

  /**
   * Capture returns null rather than throwing. A host-native driver's screen state lives on its
   * live connection in the MCP bridge, which serves it before delegating here; reaching the
   * descriptor means there is no live connection, and "nothing to read" is the honest answer.
   */
  @Test
  fun `capture reports nothing rather than failing when no live connection exists`() {
    val screenState = runBlocking {
      IosAxeHostDriverDescriptor().screenState(
        driverType = TrailblazeDriverType.IOS_AXE,
        deviceId = TrailblazeDeviceId(
          instanceId = "UDID-A",
          trailblazeDevicePlatform = TrailblazeDevicePlatform.IOS,
        ),
        deps = HostScreenStateDeps(activeMaestroDriver = { null }),
      )
    }

    assertNull(screenState)
  }

  @Test
  fun `ios axe is a listed driver covering only its own entry`() {
    val descriptor = IosAxeHostDriverDescriptor()
    assertEquals(setOf(TrailblazeDriverType.IOS_AXE), descriptor.driverTypes)
    assertEquals(DeviceListingVisibility.LISTED, descriptor.listingVisibility)
  }
}
