package xyz.block.trailblaze.ui

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
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
      // Get session info (much faster than loading all logs). The cached flow can lag a freshly
      // started run (its logs are on disk before the watcher refreshes the cache), so fall back to
      // reading disk directly - otherwise a Stop pressed seconds after launch silently no-ops.
      val sessionInfo = logsRepo.getSessionInfo(sessionId)
        ?: logsRepo.getSessionInfoDirect(sessionId)
        ?: return false
      val deviceInfo = sessionInfo.trailblazeDeviceInfo ?: return false

      // Check if this is an on-device session
      val driverType = deviceInfo.trailblazeDriverType

      if (!driverType.requiresHost && driverType.platform == TrailblazeDevicePlatform.ANDROID) {
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
        // This branch never goes through the device manager's cancel, so stop the session's
        // host-side capture streams (screenrecord/logcat) explicitly - they would otherwise keep
        // recording forever. Idempotent when no capture was started.
        deviceManager.sessionCaptureCoordinator.stopForSession(sessionId)
      } else {
        // For host tests, cancel through the device manager
        // Get the device ID from the session info
        val deviceId = sessionInfo.trailblazeDeviceId ?: return false

        // Guard against cancelling an innocent bystander: cancelSessionForDevice kills whatever
        // is live on the DEVICE, so if the device has since moved on to a different session, the
        // requested one is no longer running and there is nothing to cancel.
        val activeOnDevice = deviceManager.getCurrentSessionIdForDevice(deviceId)
        if (activeOnDevice != null && activeOnDevice != sessionId) return false

        if (activeOnDevice == sessionId) {
          // This exact session is live on the device and we are about to kill it. Stamp Cancelled
          // FIRST: cancelSessionForDevice closes the driver before cancelling the coroutine, so
          // the runner's in-flight driver call surfaces as a plain Exception (not cancellation)
          // and its async Ended.Failed can reach disk during the kill's settle window. LogsRepo's
          // first-Ended-wins gate must see our Cancelled first, or the stopped run permanently
          // reads Failed. Registration guarantees the cancel below actually kills something, so
          // pre-stamping can't mislabel a no-op.
          writeCancellationLog(logsRepo, sessionId)
          deviceManager.cancelSessionForDevice(deviceId, knownSessionId = sessionId)
        } else {
          // Session not registered on the device (still initializing, or already gone): only
          // stamp Cancelled if something was actually cancelled - stamping on a no-op cancel
          // would permanently misreport a run that goes on to finish (terminal statuses are
          // immutable).
          val cancelled = deviceManager.cancelSessionForDevice(deviceId, knownSessionId = sessionId)
          if (!cancelled) return false
          writeCancellationLog(logsRepo, sessionId)
        }
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
