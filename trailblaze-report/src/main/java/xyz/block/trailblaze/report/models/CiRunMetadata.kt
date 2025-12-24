package xyz.block.trailblaze.report.models

import kotlinx.serialization.Serializable


/**
 * Input parameters and execution context for the CI run.
 */
@Serializable
data class CiRunMetadata(
  // === Target Configuration ===
  /** Target application (e.g., "myapp") */
  val target_app: String = "",

  /** Build type (e.g., "latest", "release") */
  val build_type: String = "",

  /** Devices/platforms requested (e.g., ["android-phone", "ios-iphone"]) */
  val devices: List<String> = emptyList(),

  // === Build Artifacts ===
  /** Android APK/AAB URL (if Android tests were run) */
  val android_build_url: String? = null,

  /** iOS IPA URL (if iOS tests were run) */
  val ios_build_url: String? = null,

  /** Android build version/number */
  val android_build_version: String? = null,

  /** iOS build version/number */
  val ios_build_version: String? = null,

  // === Execution Settings ===
  /** Number of retries on test failure */
  val retry_count: Int = 0,

  /** Whether AI execution was enabled */
  val ai_enabled: Boolean = true,

  /** Whether AI fallback on recording failure was enabled */
  val ai_fallback_enabled: Boolean = true,

  /** Whether tests ran in parallel */
  val parallel_execution: Boolean = false,

  // === CI Context ===
  /** CI build URL */
  val ci_build_url: String? = null,

  /** CI build number */
  val ci_build_number: String? = null,

  /** Git commit SHA */
  val git_commit: String? = null,

  /** Git branch */
  val git_branch: String? = null,
)
