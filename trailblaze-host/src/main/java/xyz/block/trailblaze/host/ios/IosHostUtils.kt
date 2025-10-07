package xyz.block.trailblaze.host.ios

import util.LocalSimulatorUtils

object IosHostUtils {

  fun killAppOnSimulator(deviceId: String? = null, appId: String) {
    LocalSimulatorUtils.terminate(
      deviceId = deviceId ?: "booted",
      bundleId = appId,
    )
  }
}
