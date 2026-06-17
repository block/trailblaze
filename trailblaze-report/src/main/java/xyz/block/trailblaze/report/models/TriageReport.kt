package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable

/**
 * Pre-computed triage analysis report. Emitted (as trailblaze_triage_report.json) alongside the
 * standard report on every GenerateTestResultsCliCommand run. Contains deduplicated test-case
 * outcomes plus aggregated failure analysis — no external dependencies, purely derived from the
 * session results in logsDir.
 */
@Serializable
data class TriageReport(
  val schema_version: Int = 1,
  val metadata: CiRunMetadata,
  val summary: TriageSummary,
  val retries: RetrySummary,
  val failure_signatures: List<FailureSignatureGroup>,
  val failure_axes: FailureAxes,
  val cross_platform_mismatches: List<CrossPlatformMismatch>,
  val test_cases: List<SessionResult>,
)

@Serializable
data class TriageSummary(
  val total_test_cases: Int,
  val passed: Int,
  val failed: Int,
  val pass_rate: Double,
)

@Serializable
data class RetrySummary(
  /** Total execution attempts across all test cases (before dedup). */
  val total_attempts: Int,
  /** Unique test cases after dedup. */
  val unique_test_cases: Int,
  /** Tests that failed initially but passed on a subsequent retry. */
  val passed_on_retry: Int,
  /** Tests that failed on every attempt (persistent failures). */
  val failed_after_retries: Int,
)

@Serializable
data class FailureSignatureGroup(
  /** Normalized failure pattern (stripped of IDs, timestamps, paths). */
  val signature: String,
  /** Number of test cases matching this signature. */
  val count: Int,
  /**
   * Fraction of total failures this signature represents, in the range 0.0–1.0 (e.g. 0.25 =
   * a quarter of all failures). Named `share` rather than `percentage` to make the fractional
   * scale unambiguous and to match the `_rate` (also 0.0–1.0) fields elsewhere in this report.
   */
  val share: Double,
  /** Titles of affected tests. */
  val affected_tests: List<String>,
)

@Serializable
data class FailureAxes(
  val by_platform: Map<String, AxisBucket> = emptyMap(),
  val by_device: Map<String, AxisBucket> = emptyMap(),
  val by_execution_mode: Map<String, AxisBucket> = emptyMap(),
)

@Serializable
data class AxisBucket(
  val passed: Int,
  val failed: Int,
  val total: Int,
  val pass_rate: Double,
)

@Serializable
data class CrossPlatformMismatch(
  /** Human-readable test title. */
  val test_title: String,
  /** Stable test key (if available). */
  val test_key: String? = null,
  /** Devices/platforms where this test passed. */
  val passed_on: List<String>,
  /** Devices/platforms where this test failed. */
  val failed_on: List<String>,
)
