package xyz.block.trailblaze.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.tabs.session.LiveSessionDataProvider
import xyz.block.trailblaze.ui.tabs.session.SessionListListener
import xyz.block.trailblaze.ui.tabs.session.TrailblazeSessionListener
import xyz.block.trailblaze.report.utils.SessionListListener as ReportSessionListListener
import xyz.block.trailblaze.report.utils.TrailblazeSessionListener as ReportTrailblazeSessionListener


/**
 * JVM adapter that makes LogsRepo compatible with LiveSessionDataProvider
 */
class JvmLiveSessionDataProvider(private val logsRepo: LogsRepo) : LiveSessionDataProvider {

  // Use background scope for potentially blocking operations
  private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  // Keep track of listener adapters for proper cleanup
  private val sessionListenerAdapters = mutableMapOf<SessionListListener, ReportSessionListListener>()
  private val trailblazeSessionAdapters = mutableMapOf<TrailblazeSessionListener, ReportTrailblazeSessionListener>()

  override fun getSessionIds(): List<String> {
    return try {
      logsRepo.getSessionIds()
    } catch (e: Exception) {
      println("Error getting session IDs: ${e.message}")
      emptyList()
    }
  }

  override fun getSessions(): List<SessionInfo> {
    return try {
      getSessionIds().mapNotNull { getSessionInfo(it) }
    } catch (e: Exception) {
      println("Error getting sessions: ${e.message}")
      emptyList()
    }
  }

  override fun getSessionInfo(sessionId: String): SessionInfo? {
    return try {
      logsRepo.getSessionInfo(sessionId)
    } catch (e: Exception) {
      println("Error getting session info for $sessionId: ${e.message}")
      null
    }
  }

  override fun getLogsForSession(sessionId: String): List<TrailblazeLog> {
    return try {
      logsRepo.getCachedLogsForSession(sessionId)
    } catch (e: Exception) {
      println("Error getting logs for session $sessionId: ${e.message}")
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
              println("Error in session added listener for $sessionId: ${e.message}")
            }
          }
        }

        override fun onSessionRemoved(sessionId: String) {
          backgroundScope.launch {
            try {
              listener.onSessionRemoved(sessionId)
            } catch (e: Exception) {
              println("Error in session removed listener for $sessionId: ${e.message}")
            }
          }
        }
      }
      sessionListenerAdapters[listener] = adapter
      logsRepo.addSessionListListener(adapter)
    } catch (e: Exception) {
      println("Error adding session list listener: ${e.message}")
    }
  }

  override fun removeSessionListListener(listener: SessionListListener) {
    try {
      sessionListenerAdapters[listener]?.let { adapter ->
        logsRepo.removeSessionListListener(adapter)
        sessionListenerAdapters.remove(listener)
      }
    } catch (e: Exception) {
      println("Error removing session list listener: ${e.message}")
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
              println("Error in session started listener for ${listener.trailblazeSessionId}: ${e.message}")
            }
          }
        }

        override fun onUpdate(message: String) {
          backgroundScope.launch {
            try {
              listener.onUpdate(message)
            } catch (e: Exception) {
              println("Error in session update listener for ${listener.trailblazeSessionId}: ${e.message}")
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
              println("Error in session ended listener for ${listener.trailblazeSessionId}: ${e.message}")
            }
          }
        }
      }

      trailblazeSessionAdapters[listener] = adapter
      logsRepo.startWatchingTrailblazeSession(adapter)
    } catch (e: Exception) {
      println("Error starting to watch trailblaze session ${listener.trailblazeSessionId}: ${e.message}")
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
      println("Error stopping watch for session $sessionId: ${e.message}")
    }
  }
}
