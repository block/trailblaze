package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Provisions and manages Revyl cloud device sessions as an alternative
 * to [xyz.block.trailblaze.host.devices.TrailblazeDeviceService] which
 * discovers local ADB/iOS devices.
 *
 * @property revylClient The HTTP client used for Revyl API and worker communication.
 */
class RevylDeviceService(
  private val revylClient: RevylWorkerClient,
) {

  /**
   * Provisions a new cloud device via the Revyl backend.
   *
   * @param platform "ios" or "android".
   * @param appUrl Optional direct download URL for an .apk/.ipa.
   * @param appLink Optional deep-link to open after launch.
   * @return A summary of the connected device for use with [DeviceManagerToolSet].
   * @throws RevylApiException If provisioning fails.
   */
  fun startDevice(
    platform: String,
    appUrl: String? = null,
    appLink: String? = null,
  ): TrailblazeConnectedDeviceSummary {
    val session = revylClient.startSession(
      platform = platform,
      appUrl = appUrl,
      appLink = appLink,
    )

    val driverType = when (session.platform) {
      "ios" -> TrailblazeDriverType.IOS_HOST
      else -> TrailblazeDriverType.ANDROID_HOST
    }

    return TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = driverType,
      instanceId = session.workflowRunId,
      description = "Revyl cloud ${session.platform} device (${session.viewerUrl})",
    )
  }

  /**
   * Stops all active Revyl device sessions managed by this service.
   */
  fun stopDevice() {
    revylClient.stopSession()
  }

  /**
   * Returns the [TrailblazeDeviceId] for the currently active session, or null if none.
   */
  fun getCurrentDeviceId(): TrailblazeDeviceId? {
    val session = revylClient.getSession() ?: return null
    val platform = when (session.platform) {
      "ios" -> TrailblazeDevicePlatform.IOS
      else -> TrailblazeDevicePlatform.ANDROID
    }
    return TrailblazeDeviceId(
      instanceId = session.workflowRunId,
      trailblazeDevicePlatform = platform,
    )
  }

  /**
   * Returns the set of connected device summaries (at most one for Revyl sessions).
   */
  fun listDevices(): Set<TrailblazeConnectedDeviceSummary> {
    val session = revylClient.getSession() ?: return emptySet()
    val driverType = when (session.platform) {
      "ios" -> TrailblazeDriverType.IOS_HOST
      else -> TrailblazeDriverType.ANDROID_HOST
    }
    return setOf(
      TrailblazeConnectedDeviceSummary(
        trailblazeDriverType = driverType,
        instanceId = session.workflowRunId,
        description = "Revyl cloud ${session.platform} device",
      ),
    )
  }
}
