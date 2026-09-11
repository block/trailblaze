package xyz.block.trailblaze.host.ios

import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.HostYamlRunResult
import xyz.block.trailblaze.host.axe.AxeCli
import xyz.block.trailblaze.host.driver.DeviceListingVisibility
import xyz.block.trailblaze.host.driver.HostDeviceInventory
import xyz.block.trailblaze.host.driver.HostDriverDescriptor
import xyz.block.trailblaze.host.driver.HostRunDeps
import xyz.block.trailblaze.host.driver.HostScreenStateDeps
import xyz.block.trailblaze.host.yaml.RunOnHostParams
import xyz.block.trailblaze.util.Console

/**
 * Plugs the host-native AXe iOS driver into the host: the same booted simulators
 * [IosHostDriverDescriptor] lists, offered under a second execution engine.
 *
 * Separate from [IosHostDriverDescriptor] rather than a two-entry `driverTypes` set, even though
 * both map the same `simctl` pass: they answer "is this driver usable here?" differently — AXe
 * needs its CLI on the host, Maestro doesn't — and a shared descriptor cannot give two answers.
 * The `simctl` enumeration they'd have shared is already shared, through [HostDeviceInventory].
 *
 * Runs a trail through the shared [runIosSimulatorYaml], same as [IosHostDriverDescriptor].
 *
 * @param axeAvailable injectable so a test can assert both sides of the availability gate without
 *   depending on whether the agent running it happens to have AXe installed.
 */
class IosAxeHostDriverDescriptor(
  private val axeAvailable: () -> Boolean = { AxeCli.isAvailable() },
) : HostDriverDescriptor {

  override val driverTypes: Set<TrailblazeDriverType> = setOf(TrailblazeDriverType.IOS_AXE)

  override val listingVisibility = DeviceListingVisibility.LISTED

  /**
   * Every booted simulator, but only when the AXe CLI is installed. Listing them without it would
   * put entries in front of the user that fail at connect time with a confusing error.
   */
  override suspend fun discoverDevices(inventory: HostDeviceInventory): List<TrailblazeConnectedDeviceSummary> {
    if (!axeAvailable()) return emptyList()
    return inventory.iosSimulatorDevices(TrailblazeDriverType.IOS_AXE)
  }

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
  ): ScreenState? {
    // A host-native driver's screen state lives on its live IosNativeConnectedDevice in the MCP
    // bridge's persistent-device registry, and the bridge serves it before delegating here.
    // Reaching this point means no live connection exists for the device.
    Console.log("⚠️ $driverType has no live connected device to capture screen state from")
    return null
  }
}
