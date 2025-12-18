package xyz.block.trailblaze.ui

import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDataProvider

/**
 * Helper function to create a JvmLiveSessionDataProvider from LogsRepo
 */
fun createLiveSessionDataProviderJvm(
  logsRepo: LogsRepo,
  deviceManager: TrailblazeDeviceManager,
): LiveSessionDataProvider {
  return JvmLiveSessionDataProvider(logsRepo, deviceManager)
}
