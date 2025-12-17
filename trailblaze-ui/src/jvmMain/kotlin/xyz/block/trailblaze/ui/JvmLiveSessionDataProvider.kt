package xyz.block.trailblaze.ui

import io.ktor.client.request.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDevicePort
import xyz.block.trailblaze.devices.TrailblazeDevicePort.getDeviceSpecificPort
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDataProvider
import xyz.block.trailblaze.ui.tabs.session.SessionListListener
import xyz.block.trailblaze.ui.tabs.session.TrailblazeSessionListener
import xyz.block.trailblaze.report.utils.SessionListListener as ReportSessionListListener
import xyz.block.trailblaze.report.utils.TrailblazeSessionListener as ReportTrailblazeSessionListener


/**
 * JVM adapter that makes LogsRepo compatible with LiveSessionDataProvider
 */
class JvmLiveSessionDataProvider(
  private val logsRepo: LogsRepo,
  private val deviceManager: TrailblazeDeviceManager? = null,
) : LiveSessionDataProvider {

  // Use background scope for potentially blocking operations
  private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Keep track of listener adapters for proper cleanup
  private val sessionListenerAdapters = mutableMapOf<SessionListListener, ReportSessionListListener>()
  private val trailblazeSessionAdapters = mutableMapOf<TrailblazeSessionListener, ReportTrailblazeSessionListener>()

  override fun getSessionIds(): List<SessionId> {
    return try {
      logsRepo.getSessionIds()
    } catch (e: Exception) {
      emptyList()
    }
  }

  override fun getSessions(): List<SessionInfo> {
    return try {
      getSessionIds().mapNotNull { getSessionInfo(it) }
    } catch (e: Exception) {
      emptyList()
    }
  }

  override fun getSessionInfo(sessionId: SessionId): SessionInfo? {
    return try {
      logsRepo.getSessionInfo(sessionId)
    } catch (e: Exception) {
      null
    }
  }

  override fun getLogsForSession(sessionId: SessionId): List<TrailblazeLog> {
    return try {
      logsRepo.getCachedLogsForSession(sessionId)
    } catch (e: Exception) {
      emptyList()
    }
  }

  override fun addSessionListListener(listener: SessionListListener) {
    try {
      val adapter = object : ReportSessionListListener {
        override fun onSessionAdded(sessionId: SessionId) {
          // Ensure UI updates happen on appropriate thread
          backgroundScope.launch {
            try {
              listener.onSessionAdded(sessionId)
            } catch (e: Exception) {
              // Ignore listener errors
            }
          }
        }

        override fun onSessionRemoved(sessionId: SessionId) {
          backgroundScope.launch {
            try {
              listener.onSessionRemoved(sessionId)
            } catch (e: Exception) {
              // Ignore listener errors
            }
          }
        }
      }
      sessionListenerAdapters[listener] = adapter
      logsRepo.addSessionListListener(adapter)
    } catch (e: Exception) {
      // Ignore listener registration errors
    }
  }

  override fun removeSessionListListener(listener: SessionListListener) {
    try {
      sessionListenerAdapters[listener]?.let { adapter ->
        logsRepo.removeSessionListListener(adapter)
        sessionListenerAdapters.remove(listener)
      }
    } catch (e: Exception) {
      // Ignore listener removal errors
    }
  }

  override fun startWatchingTrailblazeSession(listener: TrailblazeSessionListener) {
    try {
      val adapter = object : ReportTrailblazeSessionListener {
        override val trailblazeSessionId: SessionId = listener.trailblazeSessionId

        override fun onSessionStarted() {
          backgroundScope.launch {
            try {
              listener.onSessionStarted()
            } catch (e: Exception) {
              // Ignore listener errors
            }
          }
        }

        override fun onUpdate(message: String) {
          backgroundScope.launch {
            try {
              listener.onUpdate(message)
            } catch (e: Exception) {
              // Ignore listener errors
            }
          }
        }

        override fun onSessionEnded() {
          backgroundScope.launch {
            try {
              listener.onSessionEnded()
              // Clean up adapter when session ends
              trailblazeSessionAdapters.remove(listener)
            } catch (e: Exception) {
              // Ignore listener errors
            }
          }
        }
      }

      trailblazeSessionAdapters[listener] = adapter
      logsRepo.startWatchingTrailblazeSession(adapter)
    } catch (e: Exception) {
      // Ignore session watch errors
    }
  }

  override fun stopWatching(sessionId: SessionId) {
    try {
      logsRepo.stopWatching(sessionId)
      // Clean up any adapters for this session
      trailblazeSessionAdapters.entries.removeAll { (_, adapter) ->
        adapter.trailblazeSessionId == sessionId
      }
    } catch (e: Exception) {
      // Ignore stop watching errors
    }
  }

  override suspend fun cancelSession(sessionId: SessionId): Boolean {
    return try {
      // Get logs to check session status
      val logs = logsRepo.getLogsForSession(sessionId)
      val sessionStartedLog: SessionStatus.Started = logs
        .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .filter { it.sessionStatus is SessionStatus.Started }
        .map { it.sessionStatus as SessionStatus.Started }
        .firstOrNull() ?: return false

      // Check if this is an on-device session
      val driverType = sessionStartedLog.trailblazeDeviceInfo.trailblazeDriverType
      val isOnDevice = driverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION

      if (isOnDevice) {
        // For on-device tests, send cancel request to the device's RPC server
        withContext(Dispatchers.IO) {
          try {
            val devicePort = sessionStartedLog.trailblazeDeviceId?.getDeviceSpecificPort()
              ?: TrailblazeDevicePort.DEFAULT_ADB_REVERSE_PORT
            val cancelUrl = "http://localhost:$devicePort/cancel"
            val httpClient = TrailblazeHttpClientFactory.createDefaultHttpClient(10L)
            val response = httpClient.post(cancelUrl)
            httpClient.close()
          } catch (e: Exception) {
            e.printStackTrace()
            return@withContext false
          }
        }
      } else {
        // For host tests, cancel through the device manager's session manager
        deviceManager?.let { manager ->
          // Find the device running this session
          val deviceSessionInfo = manager.deviceStateFlow.value.activeSessionsByDevice.values
            .firstOrNull { it.sessionId == sessionId.value }

          if (deviceSessionInfo != null) {
            manager.cancelSession(deviceSessionInfo.trailblazeDeviceId)
          } else {
            return false
          }
        } ?: run {
          return false
        }
      }

      true
    } catch (e: Exception) {
      e.printStackTrace()
      false
    }
  }
}
