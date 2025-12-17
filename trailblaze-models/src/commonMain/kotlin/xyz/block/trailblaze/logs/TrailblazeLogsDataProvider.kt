package xyz.block.trailblaze.logs

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionInfo

interface TrailblazeLogsDataProvider {
  suspend fun getSessionIdsAsync(): List<SessionId>
  suspend fun getLogsForSessionAsync(sessionId: SessionId?): List<TrailblazeLog>
  suspend fun getSessionInfoAsync(sessionName: SessionId): SessionInfo?
  suspend fun getSessionRecordingYaml(sessionId: SessionId): String
}
