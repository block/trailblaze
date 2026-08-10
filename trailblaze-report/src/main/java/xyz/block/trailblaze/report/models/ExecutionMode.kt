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
     * Did this session self-heal? Self-heal is reported two ways that don't always coincide: the
     * runner may end the session with a `*WithSelfHeal` status, or the recovery may only be
     * evidenced by a self-heal log ([SessionRecordingInfo.usedSelfHeal]). A run can carry the log
     * evidence without the status, so a status-only test under-reports.
     *
     * The log leg means self-heal was *attempted*, not that it worked: `markSelfHealUsed` writes
     * that log immediately before handing off to `recover()` (see `TrailblazeRunnerUtil`), so a heal
     * that then failed still sets it. Both legs together answer "this run did not replay clean",
     * which is what every reporting surface is actually asking.
     *
     * Every JVM consumer must ask through this one function. The HTML report's writer previously
     * inlined the status half on its own, which left its whole summary layer — index outcome,
     * self-healed tally, filter, and the timeline panel — blind to log-evidenced self-heal while
     * [classify] reported [SELF_HEAL] for the same run. The browser-side zip report can't call in;
     * it carries its own copy in `zip-report-core.js` (`buildRunMeta`), to change in lockstep.
     */
    fun selfHealed(status: SessionStatus, recordingInfo: SessionRecordingInfo): Boolean =
      status is SessionStatus.Ended.SucceededWithSelfHeal ||
        status is SessionStatus.Ended.FailedWithSelfHeal ||
        recordingInfo.usedSelfHeal

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
     * `available = true` (with `usedSelfHeal = true`). Because the [selfHealed] branch below runs
     * first, by the time `available` is consulted here it cleanly separates "made (non-self-heal)
     * LLM calls" from "made none".
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
      // (see SessionRecordingInfo.fromLogs). Self-heal is already handled by the branch above, so
      // here `madeLlmCalls` cleanly means "made non-self-heal LLM calls".
      val madeLlmCalls = !recordingInfo.available
      return when {
        selfHealed(status, recordingInfo) -> SELF_HEAL
        recordingInfo.skipReason == RecordingSkipReason.DISABLED_BY_CONFIG -> RECORDING_SKIPPED
        hasRecordedSteps && madeLlmCalls -> RECORDING_WITH_AI
        hasRecordedSteps -> RECORDING_ONLY
        madeLlmCalls -> AI_ONLY
        else -> UNKNOWN
      }
    }
  }
}
