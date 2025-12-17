package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo

interface LiveSessionDataProvider {
  fun getSessionIds(): List<SessionId>
  fun getSessions(): List<SessionInfo>
  fun getSessionInfo(sessionId: SessionId): SessionInfo?
  fun getLogsForSession(sessionId: SessionId): List<TrailblazeLog>
  fun addSessionListListener(listener: SessionListListener)
  fun removeSessionListListener(listener: SessionListListener)
  fun startWatchingTrailblazeSession(listener: TrailblazeSessionListener)
  fun stopWatching(sessionId: SessionId)

  /**
   * Cancel an active session.
   * Writes a cancellation log to gracefully end the session.
   */
  suspend fun cancelSession(sessionId: SessionId): Boolean
}