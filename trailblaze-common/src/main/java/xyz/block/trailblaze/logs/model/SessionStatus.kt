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
    override val duration: Long

    @Serializable
    data class Succeeded(
      override val duration: Long,
    ) : Ended

    @Serializable
    data class Failed(
      override val duration: Long,
      val exceptionMessage: String?,
    ) : Ended
  }
}
