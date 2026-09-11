package xyz.block.trailblaze.host.ios

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.driver.BootedIosSimulator
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import kotlin.test.assertEquals

class IosHostDriverDescriptorTest {

  private val simulators = listOf(
    BootedIosSimulator(udid = "UDID-A", name = "iPhone 16"),
    BootedIosSimulator(udid = "UDID-B", name = "iPad Pro"),
  )

  private fun discover(inventory: HostDeviceInventory) =
    runBlocking { IosHostDriverDescriptor().discoverDevices(inventory) }

  /**
   * Every booted simulator becomes a device, keyed by udid — that mapping is what `--device <udid>`
   * resolves against, so a dropped or renamed key makes a real simulator unaddressable.
   */
  @Test
  fun `each booted simulator becomes one addressable device`() {
    val devices = discover(HostDeviceInventory(bootedIosSimulators = simulators))

    assertEquals(listOf("UDID-A", "UDID-B"), devices.map { it.instanceId })
    assertEquals(listOf("iPhone 16", "iPad Pro"), devices.map { it.description })
    assertEquals(setOf(TrailblazeDriverType.IOS_HOST), devices.map { it.trailblazeDriverType }.toSet())
  }

  /**
   * No simulators, no devices. The descriptor contributes nothing of its own — a host that never
   * ran `simctl` (or has nothing booted) must not produce a phantom iOS entry.
   */
  @Test
  fun `an empty inventory yields no devices`() {
    assertEquals(emptyList(), discover(HostDeviceInventory.EMPTY))
  }

  /**
   * Unlike the AXe driver, this one has no host dependency to probe: Maestro's iOS support ships
   * with the host. If this ever started gating, a booted simulator would stop being listed on a
   * machine where it is perfectly drivable.
   */
  @Test
  fun `simulators are listed without any host availability probe`() {
    val devices = discover(HostDeviceInventory(bootedIosSimulators = simulators.take(1)))

    assertEquals(1, devices.size)
  }

  @Test
  fun `ios host is a listed driver covering only its own entry`() {
    val descriptor = IosHostDriverDescriptor()
    assertEquals(setOf(TrailblazeDriverType.IOS_HOST), descriptor.driverTypes)
    assertEquals(DeviceListingVisibility.LISTED, descriptor.listingVisibility)
  }
}
