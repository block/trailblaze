package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.yaml.TrailConfig

@Serializable
data class SessionInfo(
  val sessionId: String,
  val latestStatus: SessionStatus,
  val timestamp: Instant,
  /** How long the session lasted (based on calculating from logs) */
  val durationMs: Long,
  val trailblazeDeviceInfo: TrailblazeDeviceInfo? = null,
  val testName: String? = null,
  val testClass: String? = null,
  val trailConfig: TrailConfig? = null,
) {
  // Use config title if available, otherwise fall back to method name, class name, or session ID
  @Transient
  val displayName: String = trailConfig?.title
    ?: testName?.takeIf { it.isNotBlank() }
    ?: testClass?.substringAfterLast(".")
    ?: sessionId
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
  val lastLog = this.lastOrNull()

  val durationMs = if (lastLog != null) {
    lastLog.timestamp.toEpochMilliseconds() - firstLog.timestamp.toEpochMilliseconds()
  } else {
    0L
  }

  return SessionInfo(
    sessionId = firstLog.session,
    timestamp = firstLog.timestamp,
    latestStatus = this.getSessionStatus(),
    testName = sessionStartedInfo?.testMethodName,
    testClass = sessionStartedInfo?.testClassName,
    trailblazeDeviceInfo = sessionStartedInfo?.trailblazeDeviceInfo,
    trailConfig = sessionStartedInfo?.trailConfig,
    durationMs = durationMs,
  )
}
