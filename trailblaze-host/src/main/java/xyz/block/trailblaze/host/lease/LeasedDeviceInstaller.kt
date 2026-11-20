package xyz.block.trailblaze.host.lease

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import java.io.File

/** A lender's answer to a borrower's app install ([DeviceLeaseEndpoint.INSTALL_PATH]). */
@Serializable
data class RemoteInstallReply(val success: Boolean, val message: String)

/** Installs an app a borrower sent on a device this daemon lent it. */
fun interface LeasedDeviceInstaller {
  suspend fun install(device: TrailblazeDeviceId, appFile: File): RemoteInstallReply

  companion object {
    /** With this machine's adb or simctl, as for its own devices. */
    val OnThisMachine = LeasedDeviceInstaller { device, appFile ->
      MobileDeviceUtils.installAppFile(device, appFile).let { RemoteInstallReply(it.success, it.message) }
    }
  }
}
