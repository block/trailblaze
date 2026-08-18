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

  /**
   * Pre-rendered Markdown summary of this run's results, for a consumer to publish as-is wherever
   * it publishes CI narrative — a Buildkite annotation, a GitHub check-run summary, a Slack post.
   *
   * Exists so a repo that TRIGGERS a Trailblaze run can report what the run found without
   * reimplementing the rendering. The alternative is every caller deriving its own summary from
   * [CiSummaryReport.results], which means as many answers to "how many passed" as there are callers.
   *
   * Reflects whatever the producer's own summary says, and is therefore NOT a normalised verdict.
   * One producer may count every `PASSED` row — including one whose `execution_mode` is `UNKNOWN`,
   * meaning it replayed no recording and made no LLM call — while a stricter producer excludes
   * exactly those from its own pass count AND reports the whole run `FAIL` when any is present, so
   * for such a run two renderings disagree about the verdict and not merely the rate — this field
   * follows the producer that built it. A consumer that needs the stricter reading must apply it
   * to [CiSummaryReport.results] itself.
   *
   * Deliberately provider-NEUTRAL, in name and content. "Annotation" was avoided because it means
   * a Markdown block on the build in Buildkite but a file/line diagnostic in GitHub Actions. The
   * content is the data-derived summary only — counts, per-test rows, failure reasons — and carries
   * none of the publishing system's own chrome: emoji, build labels and assembled link markup exist
   * only where they are published, and each consumer already has its own. The report LINK is the
   * exception a reader cannot reconstruct, and it travels as [ci_report_url] rather than embedded
   * here, so nobody has to parse a URL back out of rendered Markdown.
   *
   * Null when the producer rendered no summary. Populated by the CI layer that renders it rather
   * than by the report generator itself, so a non-CI `trailblaze report` invocation leaves it null.
   */
  val ci_build_summary_markdown: String? = null,

  /**
   * Permanent URL of the HTML report for ONE execution plan — where a consumer sends a reader who
   * wants the detail behind [ci_build_summary_markdown]. The two are a pair: the summary is what
   * happened, this is where to look at it.
   *
   * **Scope is one plan, which is one device — not the whole request.** A config naming several
   * devices generates a separate execution plan per device, each with its own report and its own
   * value here; a six-device config produces six, and no single one of them describes the request.
   * [devices] listing several entries alongside a single URL is therefore not a contradiction: that
   * field is what was ASKED FOR, this one belongs to the leg that wrote the document you are reading.
   *
   * The consequence for a producer: only stamp this where the surrounding results are one leg's. A
   * document that merges legs — an aggregate across a config's devices — must leave it null rather
   * than pick one, because the per-(case, device) cells written from such a document all inherit one
   * metadata block, and every cell but one would then link another device's report.
   *
   * Exists because this is the one part of a run's context that CANNOT be derived downstream. A
   * Buildkite artifact URL is keyed by a UUID assigned at upload time and readable only through the
   * REST API, so a repo that merely TRIGGERS a run can reach it only by authenticating against the
   * producing build — while the step that uploaded the file already holds it for free.
   *
   * Points at the interactive report whenever the leg produced one and the legacy report only when
   * it did not — the same precedence the leg's own annotation links under, so the two never disagree
   * about which artifact is "the report". When the chosen artifact's URL cannot be resolved, this is
   * null rather than the other artifact's URL, which would silently break that agreement.
   *
   * Provider-NEUTRAL in name, like [ci_build_url]: the value is whatever permanent URL the CI
   * provider serves that artifact from. Producers must stamp an absolute `https` URL or leave it
   * null — never a provider-internal scheme such as Buildkite's `artifact://`, which resolves for
   * nothing outside the build that wrote it and would read as a working link to everyone else.
   *
   * Null when the leg published no HTML report, when the URL could not be resolved, or on a non-CI
   * `trailblaze report` invocation, which uploads nothing.
   */
  val ci_report_url: String? = null,

  /** Git commit SHA */
  val git_commit: String? = null,

  /** Git branch */
  val git_branch: String? = null,
)
