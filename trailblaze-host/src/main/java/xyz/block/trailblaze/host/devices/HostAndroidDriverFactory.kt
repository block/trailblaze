package xyz.block.trailblaze.host.devices

import dadb.Dadb
import maestro.Maestro
import maestro.drivers.AndroidDriver

internal object HostAndroidDriverFactory {
  fun createAndroid(
    instanceId: String,
    openDriver: Boolean,
    driverHostPort: Int?,
  ): Maestro {
    val driver = AndroidDriver(
      dadb = Dadb
        .list()
        .find { it.toString() == instanceId }
        ?: Dadb.discover()
        ?: error("Unable to find device with id $instanceId"),
      hostPort = driverHostPort,
      emulatorName = instanceId,
    )

    return Maestro.android(
      driver = driver,
      openDriver = openDriver,
    )
  }
}
