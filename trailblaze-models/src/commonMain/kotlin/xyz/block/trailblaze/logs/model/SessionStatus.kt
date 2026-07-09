package xyz.block.trailblaze.logs.model

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDeviceInfo
import xyz.block.trailblaze.model.TrailblazeTargetAppInfo
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
    /**
     * Memory state seeded into [xyz.block.trailblaze.AgentMemory] at session start —
     * `trailConfig.memory` (YAML defaults) merged with CLI `--memory KEY=VAL` overrides
     * applied on top (CLI wins). Captured here so replay is self-contained: the recording
     * carries the resolved values without needing the original CLI invocation or YAML
     * re-read. Empty when no seeding occurred.
     *
     * Sensitive seeds (CLI `--secret KEY=VAL`) are intentionally NOT carried here — only
     * their KEYS appear in [sensitiveMemoryKeys] so replay knows which values it must
     * re-supply. This keeps passwords / tokens out of session-log artifacts.
     */
    val resolvedInitialMemory: Map<String, String> = emptyMap(),
    /**
     * Keys that were seeded as SENSITIVE at session start (via CLI `--secret KEY=VAL`).
     * Values are deliberately omitted — replay must re-supply them through the same
     * channel. Empty when no sensitive seeding occurred. Disjoint with the keys of
     * [resolvedInitialMemory] by construction.
     */
    val sensitiveMemoryKeys: Set<String> = emptySet(),
    /**
     * Identity + version of the app under test, resolved from the device at session start.
     * Best-effort: null when no target app applies (web, desktop) or the probe failed —
     * never blocks the session from starting.
     */
    val targetAppInfo: TrailblazeTargetAppInfo? = null,
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
