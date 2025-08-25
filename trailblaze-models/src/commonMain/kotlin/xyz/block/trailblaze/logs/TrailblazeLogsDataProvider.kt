package xyz.block.trailblaze.logs

import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionInfo

interface TrailblazeLogsDataProvider {
  suspend fun getSessionIdsAsync(): List<String>
  suspend fun getLogsForSessionAsync(sessionId: String?): List<TrailblazeLog>
  suspend fun getSessionInfoAsync(sessionName: String): SessionInfo?
  suspend fun getSessionRecordingYaml(sessionId: String): String
}
