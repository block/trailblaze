package xyz.block.trailblaze.ui

import io.ktor.client.request.post
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.http.TrailblazeHttpClientFactory
import xyz.block.trailblaze.logs.client.TrailblazeLog
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

  override fun getSessionIds(): List<String> {
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

  override fun getSessionInfo(sessionId: String): SessionInfo? {
    return try {
      logsRepo.getSessionInfo(sessionId)
    } catch (e: Exception) {
      null
    }
  }

  override fun getLogsForSession(sessionId: String): List<TrailblazeLog> {
    return try {
      logsRepo.getCachedLogsForSession(sessionId)
    } catch (e: Exception) {
      emptyList()
    }
  }

  override fun addSessionListListener(listener: SessionListListener) {
    try {
      val adapter = object : ReportSessionListListener {
        override fun onSessionAdded(sessionId: String) {
          // Ensure UI updates happen on appropriate thread
          backgroundScope.launch {
            try {
              listener.onSessionAdded(sessionId)
            } catch (e: Exception) {
              // Ignore listener errors
            }
          }
        }

        override fun onSessionRemoved(sessionId: String) {
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
        override val trailblazeSessionId: String = listener.trailblazeSessionId

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

  override fun stopWatching(sessionId: String) {
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

  override suspend fun cancelSession(sessionId: String): Boolean {
    return try {
      // Get logs to check session status
      val logs = logsRepo.getLogsForSession(sessionId)
      val latestStatus = logs
        .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
        .lastOrNull()?.sessionStatus

      if (latestStatus !is SessionStatus.Started) {
        return false
      }

      // Check if this is an on-device session
      val driverType = latestStatus.trailblazeDeviceInfo?.trailblazeDriverType
      val isOnDevice = driverType == TrailblazeDriverType.ANDROID_ONDEVICE_INSTRUMENTATION

      if (isOnDevice) {
        // For on-device tests, send cancel request to the device's RPC server
        withContext(Dispatchers.IO) {
          try {
            val devicePort = 52526 // TODO: Get from session metadata
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
            .firstOrNull { it.sessionId == sessionId }

          if (deviceSessionInfo != null) {
            manager.cancelSession(deviceSessionInfo.deviceInstanceId)
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
