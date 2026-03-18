package xyz.block.trailblaze.logs.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.llm.LlmSessionUsageAndCost
import xyz.block.trailblaze.llm.LlmUsageAndCostExt.computeUsageSummary
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.yaml.TrailConfig

@Serializable
data class SessionInfo(
  val sessionId: SessionId,
  val latestStatus: SessionStatus,
  val timestamp: Instant,
  /** How long the session lasted (based on calculating from logs) */
  val durationMs: Long,
  val trailFilePath: String?,
  val hasRecordedSteps: Boolean,
  val trailblazeDeviceId: TrailblazeDeviceId? = null,
  val trailblazeDeviceInfo: TrailblazeDeviceInfo? = null,
  val testName: String? = null,
  val testClass: String? = null,
  val trailConfig: TrailConfig? = null,
  val llmUsageSummary: LlmSessionUsageAndCost? = null,
) {
  // Title resolution priority:
  //  1. trailConfig.title  — explicit human-readable title in YAML
  //  2. trailConfig.id     — explicit stable ID in YAML (e.g. "sample-app/taps/simple-tap")
  //  3. trailFilePath      — derived from asset path when running from a YAML file
  //                          e.g. "trails/EvaluationLongTest/tenKey.trail.yaml"
  //                               → "EvaluationLongTest/tenKey"
  //  4. ClassName/method   — fully-qualified stable name for tool-based tests
  //  5. sessionId          — last resort (includes timestamp+random, not stable across runs)
  @Transient
  val displayName: String = trailConfig?.title
    ?: trailConfig?.id
    ?: trailFilePath?.removePrefix("trails/")?.removeSuffix(TrailRecordings.DOT_TRAIL_DOT_YAML_FILE_SUFFIX)
    ?: testName?.takeIf { it.isNotBlank() }?.let { name ->
      testClass?.substringAfterLast(".")?.let { cls -> "$cls/$name" } ?: name
    }
    ?: testClass?.substringAfterLast(".")
    ?: sessionId.value
}

fun List<TrailblazeLog>.getSessionStatus(): SessionStatus = this
  .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
  .lastOrNull()?.sessionStatus ?: SessionStatus.Unknown

fun List<TrailblazeLog>.getSessionStartedInfo(): SessionStatus.Started? = this
  .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
  .map { it.sessionStatus }
  .filterIsInstance<SessionStatus.Started>()
  .firstOrNull()

fun List<TrailblazeLog>.getSessionInfo(): SessionInfo? {
  if (this.isEmpty()) {
    return null
  }
  val sessionStartedInfo: SessionStatus.Started? = this.getSessionStartedInfo()
  val firstLog: TrailblazeLog = this.first()
  val lastLog: TrailblazeLog? = this.lastOrNull()

  val durationMs = if (lastLog != null) {
    lastLog.timestamp.toEpochMilliseconds() - firstLog.timestamp.toEpochMilliseconds()
  } else {
    0L
  }

  return SessionInfo(
    sessionId = firstLog.session,
    timestamp = firstLog.timestamp,
    latestStatus = this.getSessionStatus(),
    trailblazeDeviceId = sessionStartedInfo?.trailblazeDeviceId,
    testName = sessionStartedInfo?.testMethodName,
    testClass = sessionStartedInfo?.testClassName,
    trailblazeDeviceInfo = sessionStartedInfo?.trailblazeDeviceInfo,
    trailConfig = sessionStartedInfo?.trailConfig,
    durationMs = durationMs,
    trailFilePath = sessionStartedInfo?.trailFilePath,
    hasRecordedSteps = sessionStartedInfo?.hasRecordedSteps ?: false,
    llmUsageSummary = this.computeUsageSummary(),
  )
}
