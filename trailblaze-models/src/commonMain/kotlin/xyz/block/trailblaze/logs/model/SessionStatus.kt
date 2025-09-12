package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo

@Serializable
sealed interface SessionStatus {

  @Serializable
  data object Unknown : SessionStatus

  @Serializable
  data class Started(
    val testMethodName: String,
    val testClassName: String,
    val trailblazeDeviceInfo: TrailblazeDeviceInfo,
  ) : SessionStatus

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

val SessionStatus.inProgress: Boolean
  get() = this is SessionStatus.Started
