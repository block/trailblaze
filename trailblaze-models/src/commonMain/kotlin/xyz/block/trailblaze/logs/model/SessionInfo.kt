package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import xyz.block.trailblaze.logs.client.TrailblazeLog

data class SessionInfo(
  val sessionId: String,
  val latestStatus: SessionStatus,
  val testName: String? = null,
  val testClass: String? = null,
  val timestamp: Instant,
) {
  val displayName: String = testName ?: testClass ?: sessionId
}

fun List<TrailblazeLog>.getSessionStatus(): SessionStatus = this
  .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
  .lastOrNull()?.sessionStatus ?: SessionStatus.Unknown

fun List<TrailblazeLog>.getSessionStartedInfo(): SessionStatus.Started? = this
  .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
  .map { it.sessionStatus }
  .filterIsInstance<SessionStatus.Started>()
  .firstOrNull()

fun List<TrailblazeLog>.getSessionInfo(): SessionInfo {
  val sessionStartedInfo = this.getSessionStartedInfo()
  val firstLog = this.first()
  return SessionInfo(
    sessionId = firstLog.session,
    timestamp = firstLog.timestamp,
    latestStatus = this.getSessionStatus(),
    testName = sessionStartedInfo?.testMethodName,
    testClass = sessionStartedInfo?.testClassName,
  )
}
