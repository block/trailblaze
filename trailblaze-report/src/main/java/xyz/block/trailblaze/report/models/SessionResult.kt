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
)
