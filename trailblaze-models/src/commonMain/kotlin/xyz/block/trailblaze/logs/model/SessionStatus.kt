package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable

@Serializable
sealed interface SessionStatus {

  @Serializable
  data object Started : SessionStatus

  @Serializable
  sealed interface Ended :
    SessionStatus,
    HasDuration {
    override val durationMs: Long

    @Serializable
    data class Succeeded(
      override val durationMs: Long,
    ) : Ended

    @Serializable
    data class Failed(
      override val durationMs: Long,
      val exceptionMessage: String?,
    ) : Ended
  }
}
