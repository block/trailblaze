package xyz.block.trailblaze.host.revyl

import xyz.block.trailblaze.devices.TrailblazeConnectedDeviceSummary
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.devices.TrailblazeDriverType

/**
 * Provisions and manages Revyl cloud device sessions via the CLI,
 * serving as the device-listing layer for [RevylMcpBridge].
 *
 * Supports multiple concurrent sessions -- each session is tracked
 * in the underlying [RevylCliClient] session map.
 *
 * @property cliClient CLI-based client for Revyl device interactions.
 */
class RevylDeviceService(
  private val cliClient: RevylCliClient,
) {

  /**
   * Provisions a new cloud device via the Revyl CLI.
   * The new session is added to the client's session map without
   * replacing any existing sessions.
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

    return sessionToSummary(session)
  }

  /**
   * Stops a device session by index.
   *
   * @param index Session index to stop. Defaults to [RevylCliClient.ACTIVE_SESSION].
   */
  fun stopDevice(index: Int = RevylCliClient.ACTIVE_SESSION) {
    cliClient.stopSession(index)
  }

  /**
   * Stops all active device sessions.
   */
  fun stopAllDevices() {
    cliClient.stopAllSessions()
  }

  /**
   * Returns the [TrailblazeDeviceId] for the currently active session, or null.
   */
  fun getCurrentDeviceId(): TrailblazeDeviceId? {
    val session = cliClient.getActiveRevylSession() ?: return null
    return sessionToDeviceId(session)
  }

  /**
   * Returns summaries for all active device sessions.
   */
  fun listDevices(): Set<TrailblazeConnectedDeviceSummary> {
    return cliClient.getAllSessions().values.map { sessionToSummary(it) }.toSet()
  }

  private fun sessionToSummary(session: RevylSession): TrailblazeConnectedDeviceSummary {
    return TrailblazeConnectedDeviceSummary(
      trailblazeDriverType = session.toDriverType(),
      instanceId = session.workflowRunId,
      description = "Revyl cloud ${session.platform} device (session ${session.index})",
    )
  }

  private fun sessionToDeviceId(session: RevylSession): TrailblazeDeviceId {
    return TrailblazeDeviceId(
      instanceId = session.workflowRunId,
      trailblazeDevicePlatform = session.toDevicePlatform(),
    )
  }
}

private fun RevylSession.toDriverType(): TrailblazeDriverType = when (platform) {
  "ios" -> TrailblazeDriverType.REVYL_IOS
  else -> TrailblazeDriverType.REVYL_ANDROID
}

private fun RevylSession.toDevicePlatform(): TrailblazeDevicePlatform = when (platform) {
  "ios" -> TrailblazeDevicePlatform.IOS
  else -> TrailblazeDevicePlatform.ANDROID
}
