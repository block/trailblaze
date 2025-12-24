package xyz.block.trailblaze.report.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Reason why a recording wasn't used.
 */
@Serializable
enum class RecordingSkipReason {
  /** No recording file exists for this platform */
  NOT_FOUND,

  /** Recording exists but is invalid/corrupted */
  INVALID,

  /** Recording execution failed (e.g., element not found) */
  EXECUTION_FAILED,

  /** Recording was disabled by configuration */
  DISABLED_BY_CONFIG,
}
