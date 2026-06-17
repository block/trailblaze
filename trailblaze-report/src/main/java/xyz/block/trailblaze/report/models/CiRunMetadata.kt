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
  // Grouped by platform, fields sorted alphabetically within each platform.
  /**
   * Branch / release line of the Android app build (e.g. `master`, `main`, `5.48`) — the line
   * [android_build_git_sha] sits on. Distinct from [android_build_version] (a numeric version code).
   */
  val android_build_branch: String? = null,

  /**
   * Git commit SHA of the Android app build under test. Traces a failing run back to a specific
   * app commit rather than just a build URL. Null when the build source doesn't expose a SHA.
   */
  val android_build_git_sha: String? = null,

  /**
   * Pull request number the Android app build was produced for, when the build came from a PR
   * ([android_build_git_sha] is then that PR's head commit). Null for non-PR builds.
   */
  val android_build_pr_number: String? = null,

  /**
   * Source-code repo the Android app build came from (e.g. `<org>/<app>`) — the repo
   * [android_build_git_sha] is a commit in. Null when it can't be determined (e.g. a URL override).
   */
  val android_build_source_repo: String? = null,

  /** Android APK/AAB URL (if Android tests were run) */
  val android_build_url: String? = null,

  /** Android build version/number */
  val android_build_version: String? = null,

  /**
   * Branch / release line of the iOS app build (e.g. `master`, `main`, `5.48`) — the line
   * [ios_build_git_sha] sits on. Distinct from [ios_build_version] (a numeric version code).
   */
  val ios_build_branch: String? = null,

  /**
   * Git commit SHA of the iOS app build under test. Traces a failing run back to a specific app
   * commit rather than just a build URL. Null when the build source doesn't expose a SHA.
   */
  val ios_build_git_sha: String? = null,

  /**
   * Pull request number the iOS app build was produced for, when the build came from a PR
   * ([ios_build_git_sha] is then that PR's head commit). Null for non-PR builds.
   */
  val ios_build_pr_number: String? = null,

  /**
   * Source-code repo the iOS app build came from (e.g. `<org>/<app>`) — the repo
   * [ios_build_git_sha] is a commit in. Null when it can't be determined (e.g. a URL override).
   */
  val ios_build_source_repo: String? = null,

  /** iOS IPA URL (if iOS tests were run) */
  val ios_build_url: String? = null,

  /** iOS build version/number */
  val ios_build_version: String? = null,

  // === Trail Source ===
  // Where the *trails* (the tests) came from — a separate axis from the app build (the
  // `*_build_*` fields above) and from the CI run's own commit ([git_commit] / [git_branch]).
  // Fields sorted alphabetically.

  /** Git ref (branch/tag/SHA) of the trail repo, when the trails came from a git repo. */
  val trail_source_ref: String? = null,

  /** Git repo the trails were sourced from, when the trails came from a git repo. */
  val trail_source_repo: String? = null,

  /**
   * How the trails were sourced: `"git"` when they came from a git trail repo (see
   * [trail_source_repo] / [trail_source_ref]); null otherwise.
   */
  val trail_source_type: String? = null,

  // === Execution Settings ===
  /** Number of retries on test failure */
  val retry_count: Int = 0,

  /** Whether AI execution was enabled */
  val ai_enabled: Boolean = true,

  /** Whether self-heal on recording failure was enabled */
  val self_heal_enabled: Boolean = true,

  /** Whether tests ran in parallel */
  val parallel_execution: Boolean = false,

  // === CI Context ===
  /** CI build URL */
  val ci_build_url: String? = null,

  /** CI build number */
  val ci_build_number: String? = null,

  /** CI organization slug (the CI provider's top-level account/org identifier). Lets tooling reach the build without parsing [ci_build_url]. */
  val ci_organization_slug: String? = null,

  /** CI pipeline slug (the CI provider's pipeline identifier within the org). Lets tooling reach the build without parsing [ci_build_url]. */
  val ci_pipeline_slug: String? = null,

  /** CI build source (e.g., "web", "api", "schedule) */
  val ci_build_source: String? = null,

  /** CI build message */
  val ci_build_message: String? = null,

  /** CI build label */
  val ci_build_label: String? = null,

  /** Git commit SHA */
  val git_commit: String? = null,

  /** Git branch */
  val git_branch: String? = null,
)
