package xyz.block.trailblaze.devices

import kotlinx.serialization.Serializable

@Serializable
data class TrailblazeDeviceInfo(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val trailblazeDriverType: TrailblazeDriverType,
  val widthPixels: Int,
  val heightPixels: Int,
  val metadata: Map<String, String> = emptyMap(),
  val locale: String? = null,
  val classifiers: List<TrailblazeDeviceClassifier> = emptyList(),
  val orientation: TrailblazeDeviceOrientation =
    if (widthPixels > heightPixels) TrailblazeDeviceOrientation.LANDSCAPE else TrailblazeDeviceOrientation.PORTRAIT,
  /**
   * The instance id device DISCOVERY lists this device under
   * ([TrailblazeConnectedDeviceSummary.instanceId]), when it differs from [trailblazeDeviceId] —
   * the web runner paths mint a per-session instance id (`playwright-electron-<8 hex>`) that keys
   * their capture/screencast registries and that nothing ever advertises. Consumers that have to
   * find the device again later (a "run this on the same device" retry, a session→device lookup)
   * must use this one; the per-session id resolves to nothing. Null when [trailblazeDeviceId] is
   * itself the advertised id, which is every non-web driver.
   */
  val advertisedInstanceId: String? = null,
) {
  val platform = trailblazeDriverType.platform

}
