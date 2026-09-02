package xyz.block.trailblaze.host.driver

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.yaml.RunOnHostParams

/**
 * A descriptor with no driver behind it, for testing the plumbing that routes to descriptors.
 *
 * [runYaml] and [screenState] throw rather than returning something inert: a test that reaches
 * them is testing the wrong thing, and a silent stub would let that pass.
 */
class FakeHostDriverDescriptor(
  override val driverTypes: Set<TrailblazeDriverType>,
  override val listingVisibility: DeviceListingVisibility = DeviceListingVisibility.LISTED,
  private val devices: List<TrailblazeConnectedDeviceSummary> = emptyList(),
) : HostDriverDescriptor {

  constructor(
    vararg driverTypes: TrailblazeDriverType,
    listingVisibility: DeviceListingVisibility = DeviceListingVisibility.LISTED,
    devices: List<TrailblazeConnectedDeviceSummary> = emptyList(),
  ) : this(driverTypes.toSet(), listingVisibility, devices)

  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> = devices

  override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult =
    error("FakeHostDriverDescriptor cannot run a trail")

  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? = error("FakeHostDriverDescriptor has no screen")
}
