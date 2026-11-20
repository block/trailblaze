package xyz.block.trailblaze.host.lease

import xyz.block.trailblaze.devices.TrailblazeDeviceClassifier
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import java.net.URI

/**
 * A borrowed device's classifiers, as its lender measured them. Any process can ask, including a
 * CLI that plans a run before handing it to the daemon: it can't probe a device on another machine,
 * and guessing would pick the wrong recordings.
 */
object BorrowedDeviceClassifiers {

  /** The lenders this machine borrows from. */
  @Volatile internal var lenderUrls: () -> List<String> = RemoteDevicePools::configuredUrls

  @Volatile internal var clientFor: (String) -> DeviceLeaseClient = ::DeviceLeaseClient

  /**
   * Null when [instanceId] is not a borrowed device (`<lender's instance>@<lender host:port>` of a
   * paired lender). Otherwise the lender's measurement, empty when it has none or can't be reached.
   */
  fun lookup(platform: TrailblazeDevicePlatform, instanceId: String): List<TrailblazeDeviceClassifier>? {
    val at = instanceId.lastIndexOf('@')
    if (at <= 0) return null
    val authority = instanceId.substring(at + 1)
    val url = lenderUrls().firstOrNull { runCatching { URI(it).authority }.getOrNull() == authority } ?: return null
    val lenderId = platform.toFullyQualifiedDeviceId(instanceId.substring(0, at))
    val row = try {
      clientFor(url).devices().firstOrNull { it.id == lenderId }
    } catch (e: Exception) {
      null
    }
    return row?.classifiers.orEmpty().map(::TrailblazeDeviceClassifier)
  }
}
