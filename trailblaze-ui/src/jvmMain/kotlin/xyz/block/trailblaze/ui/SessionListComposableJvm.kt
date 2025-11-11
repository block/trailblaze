package xyz.block.trailblaze.ui

import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.images.ImageLoader
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDataProvider
import java.io.File


// JVM-specific helper that accepts a File parameter for better integration
fun createLogsFileSystemImageLoader(logsDir: File): ImageLoader {
  return FileSystemImageLoader(logsDir.absolutePath)
}

/**
 * Helper function to create a JvmLiveSessionDataProvider from LogsRepo
 */
fun createLiveSessionDataProviderJvm(
  logsRepo: LogsRepo,
  deviceManager: TrailblazeDeviceManager? = null,
): LiveSessionDataProvider {
  return JvmLiveSessionDataProvider(logsRepo, deviceManager)
}
