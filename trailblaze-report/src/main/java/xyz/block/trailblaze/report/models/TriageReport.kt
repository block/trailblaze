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
  /**
   * One entry per (test, device) failure in this group — the per-failure identity that
   * [signature] deliberately normalizes away. [affected_tests] carries titles only, which
   * collapses N devices of the same test into one string and drops the case ID entirely;
   * a consumer given only a signature and a count has to guess which tests it names, and
   * downstream triage has repeatedly guessed wrong. This is the manifest that makes the
   * mapping explicit.
   */
  val affected_failures: List<AffectedFailure> = emptyList(),
)

/** One concrete (test, device) failure behind a [FailureSignatureGroup]. */
@Serializable
data class AffectedFailure(
  /** Human-readable test title (may collide between unrelated tests — prefer [test_key]). */
  val title: String,
  /** Stable test key, e.g. `<source>/suite_71172/section_838951/case_4837766`. */
  val test_key: String? = null,
  /** Case ID parsed out of [test_key]'s `case_<id>` segment, when it has one. */
  val case_id: String? = null,
  /** Device this failure happened on, e.g. `android-tablet` / `ios-iphone`. */
  val device: String? = null,
  /** Session ID — the handle for this specific failure's logs and artifacts. */
  val session_id: String? = null,
  /** First line of THIS failure's reason, before signature normalization folded it in with others. */
  val reason: String? = null,
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
