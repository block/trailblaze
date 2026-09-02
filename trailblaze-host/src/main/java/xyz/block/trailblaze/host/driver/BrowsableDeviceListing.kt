package xyz.block.trailblaze.host.driver

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.devices.WebInstanceIds

/**
 * The device list a user browses: `trailblaze device list`, the daemon's connected-devices RPC,
 * and the recording tab's dropdown.
 *
 * All three showed the same list and each built it with its own copy of the rules, so a change had
 * to be made in three places to stay true. They call this instead.
 */
object BrowsableDeviceListing {

  /**
   * Filters [devices] to what belongs in a browsable listing, then guarantees the entries a user
   * can always address are present.
   *
   * Dropped: drivers whose descriptor marks them [DeviceListingVisibility.ADDRESSABLE_NOT_LISTED],
   * and — unless [showHiddenPlatforms] — platforms flagged hidden.
   *
   * Added back: any running web browser the caller passes in ([runningWebBrowsers]), which the
   * device manager's own filter strips when web mode is off even though they are real and running,
   * plus the always-available Playwright-native singleton that bare `--device web` resolves to.
   */
  fun filter(
    devices: List<TrailblazeConnectedDeviceSummary>,
    descriptors: HostDriverDescriptorRegistry,
    runningWebBrowsers: List<TrailblazeConnectedDeviceSummary>,
    showHiddenPlatforms: Boolean = false,
  ): List<TrailblazeConnectedDeviceSummary> {
    val listed = devices.filter { device ->
      isListable(device.trailblazeDriverType, descriptors) &&
        (showHiddenPlatforms || !device.platform.hidden)
    }

    val seen = listed.map { it.instanceId to it.platform }.toMutableSet()
    val withRunningBrowsers = listed + runningWebBrowsers.filter {
      (it.instanceId to it.platform) !in seen
    }.also { added -> added.forEach { seen += it.instanceId to it.platform } }

    // Checked by instanceId, not driver type: named web instances are PLAYWRIGHT_NATIVE too and
    // would otherwise suppress the canonical default.
    return if (withRunningBrowsers.none { it.instanceId == WebInstanceIds.PLAYWRIGHT_NATIVE }) {
      withRunningBrowsers + TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = TrailblazeDriverType.PLAYWRIGHT_NATIVE,
        instanceId = WebInstanceIds.PLAYWRIGHT_NATIVE,
        description = "Playwright Browser (Native)",
      )
    } else {
      withRunningBrowsers
    }
  }

  /** Whether this driver's devices belong in a browsable listing at all. */
  fun isListable(
    driverType: TrailblazeDriverType,
    descriptors: HostDriverDescriptorRegistry,
  ): Boolean = descriptors.forDriverOrNull(driverType)?.listingVisibility !=
    DeviceListingVisibility.ADDRESSABLE_NOT_LISTED
}
