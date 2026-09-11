package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.screenstate.MaestroDriverScreenStates
import xyz.block.trailblaze.host.yaml.RunOnHostParams

/**
 * Plugs the Maestro-backed iOS simulator driver into the host: every booted simulator can be
 * driven, so discovery is a straight map from the inventory's `simctl` pass.
 *
 * The run body lives in [runIosSimulatorYaml] rather than on this class because
 * [IosAxeHostDriverDescriptor] runs a trail the same way: the two differ in how they reach a
 * simulator, not in what happens once they have one.
 */
class IosHostDriverDescriptor : HostDriverDescriptor {

  override val driverTypes: Set<TrailblazeDriverType> = setOf(TrailblazeDriverType.IOS_HOST)

  override val listingVisibility = DeviceListingVisibility.LISTED

  /**
   * One device per booted simulator, unconditionally. There is no availability probe because
   * Maestro's iOS support ships with the host, so a booted simulator is always drivable. An empty
   * inventory (no simulators, or a non-macOS host that never ran `simctl`) yields no devices.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> =
    inventory.iosSimulatorDevices(TrailblazeDriverType.IOS_HOST)

  override suspend fun runYaml(deps: HostRunDeps, params: RunOnHostParams): HostYamlRunResult =
    runIosSimulatorYaml(
      dynamicLlmClient = deps.dynamicLlmClient,
      runOnHostParams = params,
      deviceManager = deps.deviceManager,
      logsDir = deps.logsDir,
    )

  override suspend fun screenState(
    driverType: TrailblazeDriverType,
    deviceId: TrailblazeDeviceId,
    deps: HostScreenStateDeps,
  ): ScreenState? = MaestroDriverScreenStates.fromActiveDriver(deps, deviceId)
}
