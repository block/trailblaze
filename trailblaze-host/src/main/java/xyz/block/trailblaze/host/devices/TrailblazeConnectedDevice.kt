package xyz.block.trailblaze.host.devices

import maestro.DeviceInfo
import maestro.Driver
import xyz.block.trailblaze.maestro.LoggingDriver
import xyz.block.trailblaze.api.ScreenState
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.axe.AxeDeviceManager
import xyz.block.trailblaze.host.ios.IosDeviceManager
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.host.screenstate.SecondaryTreeResult
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider

/**
 * A connected device the daemon can drive.
 *
 * Sealed so new driver backends can declare their own native state (e.g. the AXe CLI on
 * iOS Simulator) without being forced through the Maestro-shaped API of
 * [MaestroConnectedDevice]. Screen dimensions are hoisted to the common base because
 * every consumer needs them for layout/classification; driver-specific accessors live on
 * the concrete subclasses and callers cast to reach them.
 */
sealed class TrailblazeConnectedDevice(
  val trailblazeDriverType: TrailblazeDriverType,
  val instanceId: String,
) {
  abstract val deviceWidth: Int
  abstract val deviceHeight: Int

  val trailblazeDeviceId: TrailblazeDeviceId = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = trailblazeDriverType.platform,
  )
}

/**
 * Maestro-backed connected device — the current default path for iOS Simulator / real
 * devices (via XCUITest) and Android (via UiAutomator). Exposes the raw [Driver] for
 * consumers that still need Maestro-native operations (live screen streaming, Orchestra
 * command execution).
 */
class MaestroConnectedDevice(
  private val maestroDriver: Driver,
  trailblazeDriverType: TrailblazeDriverType,
  instanceId: String,
  /** The actual port supplied when building this iOS driver; null for Android. */
  val driverHostPort: Int? = null,
) : TrailblazeConnectedDevice(trailblazeDriverType, instanceId) {

  val initialMaestroDeviceInfo: DeviceInfo = maestroDriver.deviceInfo()

  override val deviceWidth: Int = initialMaestroDeviceInfo.widthPixels
  override val deviceHeight: Int = initialMaestroDeviceInfo.heightPixels

  /** Returns the underlying Maestro driver for direct access (e.g., live preview streaming). */
  fun getMaestroDriver(): Driver = maestroDriver

  fun getLoggingDriver(
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
    /**
     * Applied to every screen state this driver logs an action with. Identity by default; the
     * iOS host runner passes its dual-tree wrap here, because command logs (tap / swipe / input)
     * come from THIS provider rather than the runner's own — without the decorator a tap log,
     * the one a tap migration reads, would carry no secondary tree.
     */
    screenStateDecorator: (ScreenState) -> ScreenState = { it },
    /**
     * Driver-migration side capture, run inside the screen state build so the secondary tree
     * is read in the same instant as the driver's own tree. Null when capture is off.
     */
    secondaryTreeCapture: (() -> SecondaryTreeResult)? = null,
  ): LoggingDriver = LoggingDriver(
    delegate = maestroDriver,
    screenStateProvider = {
      screenStateDecorator(
        HostMaestroDriverScreenState(
          maestroDriver = maestroDriver,
          trailblazeDeviceId = trailblazeDeviceId,
          secondaryTreeCapture = secondaryTreeCapture,
        ),
      )
    },
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )
}

/**
 * An iOS Simulator driven natively from the host — no Maestro/XCUITest connection is ever
 * opened for it. This is the pluggable seam for iOS drivers: a new driver subclasses this,
 * returns its [IosDeviceManager] from [createDeviceManager], and every wiring site
 * (host test rule, MCP bridge, screen-state capture) works polymorphically without
 * driver-specific branches. The driver's enum entry must be a member of
 * [TrailblazeDriverType.hostNativeSimulatorDriver]; its connection factory lives in
 * [TrailblazeDeviceService.getConnectedDevice].
 */
abstract class IosNativeConnectedDevice(
  trailblazeDriverType: TrailblazeDriverType,
  /** Simulator UDID the driver targets. */
  val udid: String,
) : TrailblazeConnectedDevice(
  trailblazeDriverType = trailblazeDriverType,
  instanceId = udid,
) {

  /**
   * Builds this driver's [IosDeviceManager]. [templateContext] carries the resolved
   * target's `{{target.appId}}` expansion for selector resolution; null on target-agnostic
   * paths (e.g. one-shot MCP tool calls).
   */
  abstract fun createDeviceManager(templateContext: TargetTemplateContext? = null): IosDeviceManager

  /** Fresh [ScreenState] for the current UI. Cheap — expensive members are lazy. */
  open fun screenState(): ScreenState = createDeviceManager().getScreenState()
}

/**
 * AXe-backed connected device — iOS Simulator only. Drives the simulator by
 * shelling out to the [AXe CLI](https://github.com/cameroncooke/AXe) rather than going
 * through Maestro/XCUITest. What this unlocks vs. the Maestro path:
 *
 *  - **AX role vocabulary** — every node carries an Apple AX `role` string (`AXButton`,
 *    `AXStaticText`, …), a human-readable `role_description`, and optional `subrole`.
 *    Maestro exposes an integer `elementType` enum instead.
 *  - **Custom accessibility actions** (e.g. `Copy name`, `Show other options`) —
 *    not surfaced anywhere on the Maestro path.
 *  - **AXHelp** tooltip text — same story.
 *
 * On snapshot latency, AXe and a warm in-daemon Maestro RPC are comparable (AXe's
 * `describe-ui` forks a subprocess per call; Maestro's XCTest HTTP runner stays warm).
 * This driver is a fidelity play, not a speed play.
 */
class AxeConnectedDevice(
  udid: String,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
) : IosNativeConnectedDevice(
  trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
  udid = udid,
) {

  override fun createDeviceManager(templateContext: TargetTemplateContext?): IosDeviceManager =
    AxeDeviceManager(
      udid = udid,
      deviceWidth = deviceWidth,
      deviceHeight = deviceHeight,
      templateContext = templateContext,
    )
}
