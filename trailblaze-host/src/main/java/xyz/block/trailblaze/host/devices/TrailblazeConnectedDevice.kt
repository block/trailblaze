package xyz.block.trailblaze.host.devices

import maestro.Driver
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState
import xyz.block.trailblaze.logs.client.TrailblazeLogger

class TrailblazeConnectedDevice(
  private val maestroDriver: Driver,
  val trailblazeDriverType: TrailblazeDriverType,
) {
  val initialMaestroDeviceInfo = maestroDriver.deviceInfo()

  @Deprecated("Use getLoggingDriver(TrailblazeLogger) instead")
  val loggingDriver: LoggingDriver
    get() = throw IllegalStateException("Use getLoggingDriver(TrailblazeLogger) instead")

  fun getLoggingDriver(trailblazeLogger: TrailblazeLogger): LoggingDriver = LoggingDriver(
    delegate = maestroDriver,
    screenStateProvider = {
      HostMaestroDriverScreenState(
        maestroDriver = maestroDriver,
        setOfMarkEnabled = false,
      )
    },
    trailblazeLogger = trailblazeLogger,
  )
}
