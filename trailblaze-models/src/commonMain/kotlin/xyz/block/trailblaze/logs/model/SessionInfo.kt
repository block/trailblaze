package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLog

data class SessionInfo(
  val sessionId: String,
  val latestStatus: SessionStatus,
  val timestamp: Instant,
  val trailblazeDeviceInfo: TrailblazeDeviceInfo? = null,
  val testName: String? = null,
  val testClass: String? = null,
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
    trailblazeDeviceInfo = sessionStartedInfo?.trailblazeDeviceInfo,
  )
}
