package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.HasScreenshot

@Serializable
sealed interface SessionEvent {
  val timestamp: Long
  val elapsedTimeMs: Long
  val durationMs: Long

  @Serializable
  data class AgentStatusChanged(
    val details: String,
    val prompt: String?,
    override val timestamp: Long,
    override val elapsedTimeMs: Long,
    override val durationMs: Long = 0,
  ) : SessionEvent

  @Serializable
  data class SessionStatusChanged(
    val details: String,
    override val timestamp: Long,
    override val elapsedTimeMs: Long,
    override val durationMs: Long = 0,
  ) : SessionEvent

  @Serializable
  data class OtherEvent(
    val type: String,
    val details: String,
    override val timestamp: Long,
    override val elapsedTimeMs: Long,
    override val durationMs: Long,
  ) : SessionEvent

  @Serializable
  data class TrailblazeTool(
    val code: String,
    override val timestamp: Long,
    override val elapsedTimeMs: Long,
    override val durationMs: Long,
  ) : SessionEvent

  @Serializable
  data class MaestroCommand(
    val code: String,
    override val durationMs: Long,
    override val elapsedTimeMs: Long,
    override val timestamp: Long,
  ) : SessionEvent

  @Serializable
  data class MaestroDriver(
    val code: String,
    override val screenshotFile: String?,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
    override val durationMs: Long,
    override val elapsedTimeMs: Long,
    override val timestamp: Long,
    val x: Int? = null,
    val y: Int? = null,
  ) : SessionEvent,
    HasScreenshot

  @kotlinx.serialization.Serializable
  data class LlmRequest(
    override val screenshotFile: String?,
    override val deviceHeight: Int,
    override val deviceWidth: Int,
    override val durationMs: Long,
    override val elapsedTimeMs: Long,
    override val timestamp: Long,
  ) : SessionEvent,
    HasScreenshot
}
