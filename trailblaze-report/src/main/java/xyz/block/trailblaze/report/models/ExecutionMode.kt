package xyz.block.trailblaze.report.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * How the test/step was executed.
 */
@Serializable
enum class ExecutionMode {
  /** Executed using recording only, no AI */
  RECORDING_ONLY,

  /** Recording failed, fell back to AI */
  AI_FALLBACK,

  /** No recording available, used AI only */
  AI_ONLY,

  /** Recording was available but skipped (e.g., config setting) */
  RECORDING_SKIPPED,

  /** Unknown execution mode */
  UNKNOWN,
}
