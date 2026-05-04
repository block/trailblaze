package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.yaml.TrailConfig

@Serializable
sealed interface SessionStatus {

  @Serializable
  data object Unknown : SessionStatus

  @Serializable
  data class Started(
    val trailConfig: TrailConfig?,
    val trailFilePath: String?,
    val hasRecordedSteps: Boolean,
    val testMethodName: String,
    val testClassName: String,
    val trailblazeDeviceInfo: TrailblazeDeviceInfo,
    val trailblazeDeviceId: TrailblazeDeviceId? = null,
    val rawYaml: String? = null,
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

    @Serializable
    data class Cancelled(
      override val durationMs: Long,
      val cancellationMessage: String?,
    ) : Ended

    /**
     * Session succeeded but required self-heal after recording failures
     */
    @Serializable
    data class SucceededWithSelfHeal(
      override val durationMs: Long,
      val usedSelfHeal: Boolean = true,
    ) : Ended

    /**
     * Session failed after attempting self-heal following recording failures
     */
    @Serializable
    data class FailedWithSelfHeal(
      override val durationMs: Long,
      val exceptionMessage: String?,
      val usedSelfHeal: Boolean = true,
    ) : Ended

    @Serializable
    data class TimeoutReached(
      override val durationMs: Long,
      val message: String?,
    ) : Ended

    /**
     * Session failed due to reaching the maximum number of LLM calls allowed per objective
     */
    @Serializable
    data class MaxCallsLimitReached(
      override val durationMs: Long,
      val maxCalls: Int,
      val objectivePrompt: String,
    ) : Ended
  }
}

val SessionStatus.isInProgress: Boolean
  get() = this !is SessionStatus.Ended
