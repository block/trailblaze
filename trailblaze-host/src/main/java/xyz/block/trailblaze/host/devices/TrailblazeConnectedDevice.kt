package xyz.block.trailblaze.host.devices

import maestro.Driver
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLogger
import xyz.block.trailblaze.logs.client.TrailblazeSessionProvider

/**
 * A light wrapper around discovered connected devices
 */
class TrailblazeConnectedDevice(
  private val maestroDriver: Driver,
  val trailblazeDriverType: TrailblazeDriverType,
  val instanceId: String,
) {
  val initialMaestroDeviceInfo = maestroDriver.deviceInfo()

  val trailblazeDeviceId: TrailblazeDeviceId = TrailblazeDeviceId(
    instanceId = instanceId,
    trailblazeDevicePlatform = trailblazeDriverType.platform,
  )

  fun getLoggingDriver(
    trailblazeLogger: TrailblazeLogger,
    sessionProvider: TrailblazeSessionProvider,
  ): LoggingDriver = LoggingDriver(
    delegate = maestroDriver,
    screenStateProvider = {
      HostMaestroDriverScreenState(
        maestroDriver = maestroDriver,
        setOfMarkEnabled = false,
      )
    },
    trailblazeLogger = trailblazeLogger,
    sessionProvider = sessionProvider,
  )
}
