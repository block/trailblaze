package xyz.block.trailblaze.ui.tabs.session

import kotlinx.coroutines.flow.StateFlow
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo

/**
 * Provides reactive access to session data using StateFlows.
 * All data updates are emitted reactively - no need for manual listener management!
 */
interface LiveSessionDataProvider {
  /**
   * Reactive flow of all session IDs.
   * Emits updates when sessions are added or removed.
   */
  fun getSessionsFlow(): StateFlow<List<SessionInfo>>

  /**
   * Reactive flow of logs for a specific session.
   * Emits updates when new logs are written to the session.
   */
  fun getSessionLogsFlow(sessionId: SessionId): StateFlow<List<TrailblazeLog>>

  /**
   * Cancel an active session.
   * Writes a cancellation log to gracefully end the session.
   */
  suspend fun cancelSession(sessionId: SessionId): Boolean

  /**
   * Get logs for a session synchronously (for YAML generation, export, etc.).
   * For reactive updates, use getSessionLogsFlow() instead.
   */
  suspend fun getLogsForSession(sessionId: SessionId): List<TrailblazeLog>
}