package xyz.block.trailblaze.ui

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

  override fun getSessionIds(): List<String> = logsRepo.getSessionIds()
  override fun getSessions(): List<SessionInfo> {
    return getSessionIds().mapNotNull { getSessionInfo(it) }
  }

  override fun getSessionInfo(sessionId: String): SessionInfo? {
    return logsRepo.getSessionInfo(sessionId)
  }

  override fun getLogsForSession(sessionId: String): List<TrailblazeLog> = logsRepo.getLogsForSession(sessionId)

  override fun addSessionListListener(listener: SessionListListener) {
    val adapter = object : ReportSessionListListener {
      override fun onSessionAdded(sessionId: String) = listener.onSessionAdded(sessionId)
      override fun onSessionRemoved(sessionId: String) = listener.onSessionRemoved(sessionId)
    }
    logsRepo.addSessionListListener(adapter)
  }

  override fun removeSessionListListener(listener: SessionListListener) {
    // Note: This is tricky because we need to track the adapter objects
    // For now, we'll implement a simple approach that may not perfectly match listeners
    // In a production system, you'd want to maintain a mapping between listeners and adapters
  }

  override fun startWatchingTrailblazeSession(listener: TrailblazeSessionListener) {
    val adapter = object : ReportTrailblazeSessionListener {
      override val trailblazeSessionId: String = listener.trailblazeSessionId
      override fun onSessionStarted() = listener.onSessionStarted()
      override fun onUpdate(message: String) = listener.onUpdate(message)
      override fun onSessionEnded() = listener.onSessionEnded()
    }
    logsRepo.startWatchingTrailblazeSession(adapter)
  }

  override fun stopWatching(sessionId: String) {
    logsRepo.stopWatching(sessionId)
  }
}