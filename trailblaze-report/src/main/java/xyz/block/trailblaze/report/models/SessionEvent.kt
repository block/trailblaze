package xyz.block.trailblaze.report.models

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.HasScreenshot

@Serializable
sealed interface SessionEvent {
  val timestamp: Instant
  val elapsedTimeMs: Long
  val durationMs: Long

  @Serializable
  data class AgentStatusChanged(
    val details: String,
    val prompt: String?,
    override val timestamp: Instant,
    override val elapsedTimeMs: Long,
    override val durationMs: Long = 0,
  ) : SessionEvent

  @Serializable
  data class SessionStatusChanged(
    val details: String,
    override val timestamp: Instant,
    override val elapsedTimeMs: Long,
    override val durationMs: Long = 0,
  ) : SessionEvent

  @Serializable
  data class OtherEvent(
    val type: String,
    val details: String,
    override val timestamp: Instant,
    override val elapsedTimeMs: Long,
    override val durationMs: Long,
  ) : SessionEvent

  @Serializable
  data class TrailblazeTool(
    val code: String,
    override val timestamp: Instant,
    override val elapsedTimeMs: Long,
    override val durationMs: Long,
  ) : SessionEvent

  @Serializable
  data class MaestroCommand(
    val code: String,
    override val durationMs: Long,
    override val elapsedTimeMs: Long,
    override val timestamp: Instant,
  ) : SessionEvent

  @Serializable
  data class MaestroDriver(
    val code: String,
    override val screenshotFile: String?,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
    override val durationMs: Long,
    override val elapsedTimeMs: Long,
    override val timestamp: Instant,
    val x: Int? = null,
    val y: Int? = null,
  ) : SessionEvent,
    HasScreenshot

  @Serializable
  data class LlmRequest(
    override val screenshotFile: String?,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
    override val durationMs: Long,
    override val elapsedTimeMs: Long,
    override val timestamp: Instant,
  ) : SessionEvent,
    HasScreenshot
}
