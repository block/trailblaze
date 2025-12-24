package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable

@Serializable
enum class Outcome {
  PASSED,
  FAILED,
  SKIPPED,
  ERROR,
  CANCELLED,
  TIMEOUT,
  MAX_CALLS_REACHED,
}
