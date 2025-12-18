package xyz.block.trailblaze.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.model.TrailblazeOnDeviceInstrumentationTarget
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDataProvider
import xyz.block.trailblaze.util.HostAndroidDeviceConnectUtils


/**
 * JVM adapter that makes LogsRepo compatible with LiveSessionDataProvider.
 * Now much simpler - just exposes the reactive Flows directly!
 */
class JvmLiveSessionDataProvider(
  private val logsRepo: LogsRepo,
  private val deviceManager: TrailblazeDeviceManager,
) : LiveSessionDataProvider {

  // Just expose LogsRepo's reactive SessionInfo flow directly!
  // LogsRepo handles watching for session changes and status updates.
  override fun getSessionsFlow(): StateFlow<List<SessionInfo>> {
    return logsRepo.sessionInfoFlow
  }

  override fun getSessionLogsFlow(sessionId: SessionId): StateFlow<List<TrailblazeLog>> {
    return logsRepo.getSessionLogsFlow(sessionId)
  }

  override suspend fun cancelSession(sessionId: SessionId): Boolean {
    return try {
      // Get session info (much faster than loading all logs)
      val sessionInfo = logsRepo.getSessionInfo(sessionId) ?: return false
      val deviceInfo = sessionInfo.trailblazeDeviceInfo ?: return false

      // Check if this is an on-device session
      val driverType = deviceInfo.trailblazeDriverType

      if (driverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION) {
        // For on-device tests, send cancel request to the device's RPC server
        withContext(Dispatchers.IO) {
          sessionInfo.trailblazeDeviceId?.let { trailblazeDeviceId ->
            // Kill the in-process tests
            HostAndroidDeviceConnectUtils.forceStopAllAndroidInstrumentationProcesses(
              trailblazeOnDeviceInstrumentationTargetTestApps = setOf(
                TrailblazeOnDeviceInstrumentationTarget.DEFAULT_ANDROID_ON_DEVICE
              ),
              deviceId = trailblazeDeviceId
            )

            // Write cancellation log IMMEDIATELY so UI updates right away
            // We'll only do this if we have to force stop the app process itself
            writeCancellationLog(
              logsRepo = logsRepo,
              sessionId = sessionId
            )
          }
        }
      } else {
        // For host tests, cancel through the device manager
        // Get the device ID from the session info
        val deviceId = sessionInfo.trailblazeDeviceId ?: return false

        // Cancel the session on that specific device
        deviceManager.cancelSessionForDevice(deviceId)

        // Write cancellation log IMMEDIATELY so UI updates right away
        // The runner's catch blocks will NOT write logs (to avoid duplicates)
        // We're in full control of the cancellation log here
        writeCancellationLog(logsRepo, sessionId)
      }

      true
    } catch (e: Exception) {
      e.printStackTrace()
      false
    }
  }

  /**
   * Writes a cancellation log directly to the session log file.
   * This allows the UI to immediately show the session as cancelled
   * without waiting for the CancellationException to propagate through the test execution.
   */
  private fun writeCancellationLog(logsRepo: LogsRepo, sessionId: SessionId) {
    try {
      // Create cancellation log
      val cancellationLog = TrailblazeLog.TrailblazeSessionStatusChangeLog(
        session = sessionId,
        timestamp = kotlinx.datetime.Clock.System.now(),
        sessionStatus = SessionStatus.Ended.Cancelled(
          durationMs = 0L,
          cancellationMessage = "Session manually cancelled by user via Trailblaze Desktop App",
        ),
      )
      logsRepo.saveLogToDisk(cancellationLog)
    } catch (e: Exception) {
      e.printStackTrace()
      // Don't fail cancellation if log write fails
    }
  }

  override suspend fun getLogsForSession(sessionId: SessionId): List<TrailblazeLog> {
    return logsRepo.getLogsForSession(sessionId)
  }
}
