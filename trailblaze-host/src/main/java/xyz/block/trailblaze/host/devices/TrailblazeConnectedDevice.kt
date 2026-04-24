package xyz.block.trailblaze.host.devices

import maestro.DeviceInfo
import maestro.Driver
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
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
) : TrailblazeConnectedDevice(trailblazeDriverType, instanceId) {

  val initialMaestroDeviceInfo: DeviceInfo = maestroDriver.deviceInfo()

  override val deviceWidth: Int = initialMaestroDeviceInfo.widthPixels
  override val deviceHeight: Int = initialMaestroDeviceInfo.heightPixels

  /** Returns the underlying Maestro driver for direct access (e.g., live preview streaming). */
  fun getMaestroDriver(): Driver = maestroDriver

  fun getLoggingDriver(
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
  ): LoggingDriver = LoggingDriver(
    delegate = maestroDriver,
    screenStateProvider = {
      HostMaestroDriverScreenState(
        maestroDriver = maestroDriver,
      )
    },
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )
}

/**
 * AXe-backed connected device (POC) — iOS Simulator only. Drives the simulator by
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
  /** Simulator UDID that the `axe` binary targets via `--udid`. */
  val udid: String,
  override val deviceWidth: Int,
  override val deviceHeight: Int,
) : TrailblazeConnectedDevice(
  trailblazeDriverType = TrailblazeDriverType.IOS_AXE,
  instanceId = udid,
)
