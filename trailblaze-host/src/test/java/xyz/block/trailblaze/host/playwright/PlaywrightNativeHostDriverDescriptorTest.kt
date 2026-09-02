package xyz.block.trailblaze.host.playwright

import kotlinx.coroutines.runBlocking
import org.junit.Test
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import kotlin.test.assertEquals

class PlaywrightNativeHostDriverDescriptorTest {

  private fun browserSummary(instanceId: String) = TrailblazeConnectedDeviceSummary(
    trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
    instanceId = instanceId,
    description = "Playwright Browser ($instanceId)",
  )

  private fun discover(runningWebBrowsers: List<TrailblazeConnectedDeviceSummary>) = runBlocking {
    PlaywrightNativeHostDriverDescriptor()
      .discoverDevices(HostDeviceInventory(runningWebBrowsers = runningWebBrowsers))
  }

  /**
   * The browser is provisioned on demand, so the canonical `web/playwright-native` device must be
   * offered even when nothing is running — otherwise web trails only work after someone launched
   * a browser some other way.
   */
  @Test
  fun `the default device is offered even with no browser running`() {
    val devices = discover(runningWebBrowsers = emptyList())

    assertEquals(1, devices.size)
    assertEquals(TrailblazeDriverType.PLAYWRIGHT_NATIVE, devices.single().trailblazeDriverType)
    assertEquals(WebInstanceIds.PLAYWRIGHT_NATIVE, devices.single().instanceId)
  }

  /**
   * Once the default browser is actually running, the running entry (which carries live detail
   * like the viewport in its description) must not be joined by a second synthetic one with the
   * same instance id — the pre-descriptor manager deduped exactly this way.
   */
  @Test
  fun `a running default browser is not listed twice`() {
    val running = browserSummary(WebInstanceIds.PLAYWRIGHT_NATIVE)

    val devices = discover(runningWebBrowsers = listOf(running))

    assertEquals(listOf(running), devices)
  }

  /** A named instance (`--device web/foo`) lists alongside the always-on default. */
  @Test
  fun `a named running browser joins the default device`() {
    val named = browserSummary("foo")

    val devices = discover(runningWebBrowsers = listOf(named))

    assertEquals(2, devices.size)
    assertEquals(named, devices.first())
    assertEquals(WebInstanceIds.PLAYWRIGHT_NATIVE, devices.last().instanceId)
  }

  @Test
  fun `playwright native is a listed driver covering only its own entry`() {
    val descriptor = PlaywrightNativeHostDriverDescriptor()
    assertEquals(setOf(TrailblazeDriverType.PLAYWRIGHT_NATIVE), descriptor.driverTypes)
    assertEquals(DeviceListingVisibility.LISTED, descriptor.listingVisibility)
  }
}
