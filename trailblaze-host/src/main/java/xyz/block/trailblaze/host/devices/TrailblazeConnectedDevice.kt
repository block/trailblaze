package xyz.block.trailblaze.host.devices

import maestro.Driver
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState

class TrailblazeConnectedDevice(
  maestroDriver: Driver,
  val trailblazeDriverType: TrailblazeDriverType,
) {
  val initialMaestroDeviceInfo = maestroDriver.deviceInfo()

  val loggingDriver: LoggingDriver = LoggingDriver(
    delegate = maestroDriver,
    screenStateProvider = {
      HostMaestroDriverScreenState(
        maestroDriver = loggingDriver,
        setOfMarkEnabled = true,
      )
    },
  )
}
