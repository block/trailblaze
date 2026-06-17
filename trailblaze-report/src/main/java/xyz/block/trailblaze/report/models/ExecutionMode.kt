package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * How the test/step was executed.
 */
@Serializable
enum class ExecutionMode {
  /** The trail carried recordings and the run made no LLM calls — a pure deterministic replay. */
  RECORDING_ONLY,

  /**
   * The trail carried recordings, but the run also made one or more LLM calls — e.g. an
   * LLM-backed `verify`/assertion, or a `recordable: false` prompt step mixed in among the
   * recorded steps. Still a recorded trail, just not a pure deterministic replay.
   */
  RECORDING_WITH_AI,

  /** Recording failed, self-heal recovered via LLM */
  SELF_HEAL,

  /** The trail carried no recordings; the LLM/agent drove the whole run. */
  AI_ONLY,

  /** Recording was available but skipped (e.g., config setting) */
  RECORDING_SKIPPED,

  /** Unknown execution mode */
  UNKNOWN,
  ;

  companion object {
    /**
     * Classify how a session actually ran, for reporting.
     *
     * [hasRecordedSteps] is the source of truth for "did the original trail carry recordings". It
     * is computed from the trail YAML at session start (see
     * [xyz.block.trailblaze.yaml.TrailblazeYaml.hasRecordedSteps]) and travels on the
     * session-started log, so it reflects the authored trail rather than runtime behavior. We trust
     * it over the LLM-call heuristic: a recorded trail that makes a few LLM calls (an LLM-backed
     * `verify`, a `recordable: false` step) must NOT be mislabeled [AI_ONLY] — that was the bug this
     * function fixes.
     *
     * [recordingInfo] supplies the orthogonal "did this run make any LLM calls / self-heal" signal,
     * derived from the logs (see [SessionRecordingInfo.fromLogs]). Its `available` flag does NOT mean
     * "a recording file existed" — that historical naming is exactly why the old heuristic produced
     * the misleading [AI_ONLY] label. Precisely: `fromLogs` sets `available = false` only when the
     * session emitted an LLM request log AND did not self-heal; self-heal sessions report
     * `available = true` (with `usedSelfHeal = true`). Because the self-heal branches below run first,
     * by the time `available` is consulted here it cleanly separates "made (non-self-heal) LLM calls"
     * from "made none".
     *
     * The four recorded/AI quadrants map cleanly so every label is true:
     * - recorded + no LLM calls  → [RECORDING_ONLY]
     * - recorded + LLM calls      → [RECORDING_WITH_AI]
     * - not recorded + LLM calls  → [AI_ONLY]
     * - not recorded + no LLM calls → [UNKNOWN] (e.g. a tool-only trail — can't characterize as
     *   either recorded replay or AI-driven)
     *
     * Self-heal and config-skip take precedence over all of the above.
     */
    fun classify(
      status: SessionStatus,
      hasRecordedSteps: Boolean,
      recordingInfo: SessionRecordingInfo,
    ): ExecutionMode {
      // `available` is false only when the session emitted an LLM request log and did NOT self-heal
      // (see SessionRecordingInfo.fromLogs). Self-heal is already handled by the branches above, so
      // here `madeLlmCalls` cleanly means "made non-self-heal LLM calls".
      val madeLlmCalls = !recordingInfo.available
      return when {
        status is SessionStatus.Ended.SucceededWithSelfHeal -> SELF_HEAL
        status is SessionStatus.Ended.FailedWithSelfHeal -> SELF_HEAL
        recordingInfo.usedSelfHeal -> SELF_HEAL
        recordingInfo.skipReason == RecordingSkipReason.DISABLED_BY_CONFIG -> RECORDING_SKIPPED
        hasRecordedSteps && madeLlmCalls -> RECORDING_WITH_AI
        hasRecordedSteps -> RECORDING_ONLY
        madeLlmCalls -> AI_ONLY
        else -> UNKNOWN
      }
    }
  }
}
