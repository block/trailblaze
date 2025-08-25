package xyz.block.trailblaze.ui.tabs.session

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo

interface LiveSessionDataProvider {
  fun getSessionIds(): List<String>
  fun getSessions(): List<SessionInfo>
  fun getSessionInfo(sessionId: String): SessionInfo?
  fun getLogsForSession(sessionId: String): List<TrailblazeLog>
  fun addSessionListListener(listener: SessionListListener)
  fun removeSessionListListener(listener: SessionListListener)
  fun startWatchingTrailblazeSession(listener: TrailblazeSessionListener)
  fun stopWatching(sessionId: String)
}