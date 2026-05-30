package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable
import xyz.block.trailblaze.logs.model.SessionId


const val SOURCE_TYPE_HANDWRITTEN = "HANDWRITTEN"
const val SOURCE_TYPE_GENERATED = "GENERATED"

@Serializable
data class CiSummaryReport(
  val metadata: CiRunMetadata,
  val results: List<SessionResult>,
)

@Serializable
data class SessionResult(
  val session_id: SessionId,
  val title: String,
  /**
   * Stable identifier used to group retries of the same test together. Sourced from
   * `SessionInfo.stableTestKey` (see that property's kdoc for the resolution priority).
   * Distinct from [title], which is the human-readable label and may collide between
   * unrelated tests.
   */
  val test_key: String? = null,
  val platform: String,
  val outcome: Outcome,

  /** How the test was executed */
  val execution_mode: ExecutionMode,

  /** Source of the trail file (handwritten vs generated), null if unknown */
  val trail_source: String,

  /** Device classifier used */
  val device_classifier: String? = null,

  /** Cost of LLM calls in USD */
  val llm_cost_usd: Double? = null,

  // === Timing ===
  /** Test duration in milliseconds */
  val duration_ms: Long,

  // === LLM Usage (if AI was used) ===
  /** Number of LLM calls made */
  val llm_call_count: Int? = null,

  /** Human-readable failure reason (if failed) */
  val failure_reason: String? = null,

  /** Excerpt from device logs (logcat) around the failure, if available */
  val device_log_excerpt: String? = null,

  // === Recording Info ===
  /** Whether the trail YAML contained recorded steps (recording.tools blocks) */
  val has_recorded_steps: Boolean = false,

  /** Whether a valid recording was found on disk for this platform */
  val recording_available: Boolean = false,

  /** Reason recording wasn't used (if applicable) */
  val recording_skip_reason: RecordingSkipReason? = null,

  /** ISO 8601 timestamp when test started */
  val started_at: String? = null,

  /** Milliseconds since epoch when test started */
  val started_at_epoch_ms: Long? = null,

  /** ISO 8601 timestamp when test completed */
  val completed_at: String? = null,

  /** Milliseconds since epoch when test completed */
  val completed_at_epoch_ms: Long? = null,

  // === Retry Info ===
  /** Which attempt this result represents (1-based). 1 = first try, 2 = first retry, etc. */
  val attempt: Int = 1,

  /** Total number of attempts for this test (including retries) */
  val total_attempts: Int = 1,

  /** Session IDs of previous attempts that were replaced by this result */
  val replaced_session_ids: List<SessionId> = emptyList(),

  /** Failure reasons from replaced attempts (populated during dedup when this result superseded earlier failures) */
  val replaced_failure_reasons: List<String> = emptyList(),

  // === CI Provenance (per-session) ===
  /**
   * CI job ID that produced this session — typically the provider's per-step UUID. Captured
   * at session-emit time so a later report-generation job in a different CI job can still
   * trace each result back to the originating shard. Nullable — absent for local runs or
   * pre-provenance log archives.
   */
  val ci_job_id: String? = null,

  /**
   * Filename of the per-session zip artifact that contains this session's logs
   * (e.g. `logs_uitest-sample-app-accessibility_0__a1b2c3d4.zip`). Despite the legacy `logs_`
   * prefix, the artifact this points to is the *per-session* zip (one zip per session),
   * not a step-wide bundle — see `scripts/buildkite/buildkite_upload_logs.sh`. Combined
   * with [ci_job_id] and the build's organization/pipeline/number from [CiRunMetadata], a
   * consumer can resolve the artifact's deep-link URL via the CI provider's CLI without
   * inspecting zip contents.
   */
  val logs_zip_filename: String? = null,

  /**
   * Resolved deep-link URL for the per-session zip artifact. Populated either by the upload
   * script (via post-upload `buildkite-agent artifact search`, stamped into the on-disk
   * sidecar before report generation) or, as a backstop, by the test-results publisher
   * during cell-write. Nullable for local runs / pre-resolution archives.
   */
  val logs_zip_url: String? = null,
)
