package xyz.block.trailblaze.host.devices

import maestro.Driver
import xyz.block.trailblaze.android.maestro.LoggingDriver
import xyz.block.trailblaze.host.screenstate.HostMaestroDriverScreenState

class TrailblazeConnectedDevice(
  maestroDriver: Driver,
) {
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
