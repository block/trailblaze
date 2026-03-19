package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Provisions and manages Revyl cloud device sessions via the CLI,
 * serving as the device-listing layer for [RevylMcpBridge].
 *
 * @property cliClient CLI-based client for Revyl device interactions.
 */
class RevylDeviceService(
  private val cliClient: RevylCliClient,
) {

  /**
   * Provisions a new cloud device via the Revyl CLI.
   *
   * @param platform "ios" or "android".
   * @param appUrl Optional public download URL for an .apk/.ipa.
   * @param appLink Optional deep-link to open after launch.
   * @return A summary of the connected device.
   * @throws RevylCliException If provisioning fails.
   */
  fun startDevice(
    platform: String,
    appUrl: String? = null,
    appLink: String? = null,
  ): TrailblazeConnectedDeviceSummary {
    val session = cliClient.startSession(
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
   * Stops the active Revyl device session.
   */
  fun stopDevice() {
    cliClient.stopSession()
  }

  /**
   * Returns the [TrailblazeDeviceId] for the currently active session, or null.
   */
  fun getCurrentDeviceId(): TrailblazeDeviceId? {
    val session = cliClient.getSession() ?: return null
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
   * Returns the set of connected device summaries (at most one for CLI sessions).
   */
  fun listDevices(): Set<TrailblazeConnectedDeviceSummary> {
    val session = cliClient.getSession() ?: return emptySet()
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
