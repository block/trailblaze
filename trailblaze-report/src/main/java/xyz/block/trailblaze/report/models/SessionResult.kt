package xyz.block.trailblaze.report.models

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus

/**
 * Lifts [SessionResult.failure_code] out of a structured failure payload: the top-level
 * string `code` member of a JSON-object payload, else null. Single owner of the rule so
 * the gradle-CLI ([xyz.block.trailblaze.report.GenerateTestResultsCliCommand]) and
 * daemon-CLI (`CliReportGenerator`) report paths cannot drift.
 */
fun failureCodeOf(payload: JsonElement?): String? =
  ((payload as? JsonObject)?.get("code") as? JsonPrimitive)
    ?.takeIf { it.isString }
    ?.content

/**
 * The structured failure payload a terminal session status carries, else null. Single owner
 * of the status-to-payload extraction [failureCodeOf] is always paired with, for the same
 * reason: the report paths that stamp [SessionResult.failure_payload] and the run-report
 * meta cannot drift.
 */
fun failurePayloadOf(status: SessionStatus): JsonElement? = when (status) {
  is SessionStatus.Ended.Failed -> status.failurePayload
  is SessionStatus.Ended.FailedWithSelfHeal -> status.failurePayload
  else -> null
}


const val SOURCE_TYPE_HANDWRITTEN = "HANDWRITTEN"
const val SOURCE_TYPE_GENERATED = "GENERATED"

@Serializable
data class CiSummaryReport(
  val metadata: CiRunMetadata,
  val results: List<SessionResult>,
)

@OptIn(ExperimentalSerializationApi::class)
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

  /**
   * The trail's `config.metadata` map — arbitrary author-supplied key/value pairs from the trail
   * YAML — or null when the trail declared none (or the session didn't run from a trail config).
   * Carried through onto the result row so downstream consumers can key off durable trail-authoring
   * metadata that survives a trail moving to another repo or being re-keyed under a new [test_key],
   * without that identity having to be re-encoded into [test_key] itself.
   */
  val metadata: Map<String, String>? = null,

  // === JUnit Identity ===
  // The JUnit class/method that ran this session, when it ran inside a JUnit harness
  // (`SessionInfo.testClass` / `testName`). Carried separately from [title] because the title
  // resolution prefers the trail's `title:`/`id:` — so a run labelled by its trail title is
  // still identifiable as `com.example.LaunchSmokeTest#launchNoCrash` by consumers that speak
  // the JUnit namespace (e.g. expected-tests validation against a test-runner manifest). Null
  // for non-JUnit runs (CLI trail runs; MCP sessions carry the "MCP" sentinel class and no real
  // method name).
  /** Fully-qualified JUnit test class, when the session ran under a JUnit harness. */
  val test_class: String? = null,

  /** JUnit test method name, when the session ran under a JUnit harness. */
  val test_name: String? = null,

  val platform: String,
  val outcome: Outcome,

  /** How the test was executed */
  val execution_mode: ExecutionMode,

  /** Source of the trail file (handwritten vs generated), null if unknown */
  val trail_source: String,

  /** Device classifier used */
  val device_classifier: String? = null,

  // === App Under Test ===
  // Captured from the device at session start (SessionStatus.Started.targetAppInfo). All four
  // are null for legacy log archives predating the field, and for targets with no installable
  // app (web, desktop).
  /** Resolved package name (Android) / bundle identifier (iOS) of the app under test. */
  val app_id: String? = null,

  /** User-visible app version (Android versionName / iOS CFBundleShortVersionString). */
  val app_version_name: String? = null,

  /** Internal app version (Android versionCode / iOS CFBundleVersion). */
  val app_version_code: String? = null,

  /** iOS app-specific build number. */
  val app_build_number: String? = null,

  /** Cost of LLM calls in USD */
  val llm_cost_usd: Double? = null,

  // === Timing ===
  /** Test duration in milliseconds */
  val duration_ms: Long,

  // === LLM Usage (if AI was used) ===
  /** Number of LLM calls made */
  val llm_call_count: Int? = null,

  /**
   * Human-readable failure reason (if failed). In reports that carry a [failure_stack]
   * key this is the exception message only; legacy reports (no [failure_stack] key) may
   * embed the full stack trace after the message line, so consumers keep a head-cut
   * heuristic as the fallback for those.
   */
  val failure_reason: String? = null,

  /**
   * Stack trace of the failure, carried separately from [failure_reason]. Null when the
   * failure had no stack trace (e.g. cancellation/timeout) and absent on reports written
   * before the field existed.
   */
  val failure_stack: String? = null,

  /**
   * Structured failure classification (e.g. "TRAILHEAD" —
   * `xyz.block.trailblaze.exception.TrailheadException.KIND`). Renderers dispatch on this
   * instead of matching message prefixes; null for ordinary failures and absent on legacy
   * reports (where consumers fall back to the message-prefix match).
   */
  val failure_kind: String? = null,

  /**
   * Machine-readable failure code, extracted from [failure_payload]'s top-level `code`
   * member when the payload is a JSON object carrying a string one. The framework only
   * lifts the field — it never interprets the values; the payload-emitting repo owns the
   * vocabulary (open enum: consumers ignore unknown codes). Refines [failure_kind]:
   * kind says WHICH structured failure family (e.g. "TRAILHEAD"), code says HOW it failed
   * within that family. Null when the failure carried no payload, the payload had no
   * string `code`, and on reports written before the field existed.
   *
   * [EncodeDefault.Mode.NEVER] (here and on [failure_payload]) because the report writers
   * encode with `encodeDefaults = true`: without it every payload-less row — i.e. every row
   * written today — would gain explicit-null keys, breaking the additive/legacy-shape
   * guarantee for consumers that distinguish absent from null.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  val failure_code: String? = null,

  /**
   * The raw structured error payload the failing tool attached (see
   * `SessionStatus.Ended.Failed.failurePayload`), verbatim, for consumers that need more
   * than [failure_code]. Null whenever no payload was carried.
   */
  @EncodeDefault(EncodeDefault.Mode.NEVER)
  val failure_payload: JsonElement? = null,

  /** Excerpt from device logs (logcat) around the failure, if available */
  val device_log_excerpt: String? = null,

  // === Recording Info ===
  /**
   * Whether the trail YAML contained recorded steps (recording.tools blocks). This is the
   * canonical "is this a recorded trail?" signal — computed from the authored trail at session
   * start, independent of how many LLM calls the run made. Query/group on this boolean; it is
   * `true` for both [ExecutionMode.RECORDING_ONLY] and [ExecutionMode.RECORDING_WITH_AI].
   */
  val has_recorded_steps: Boolean = false,

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

  /**
   * [ci_agent_name] of each attempt this result superseded. Without it the deduplicated report
   * keeps only the winner's agent, so the one question a retry raises — did it clear a wedged
   * host, or re-run on the same one — is unanswerable from the report and sends the reader into
   * per-session artifacts. Compare [ci_agent_name] against this list: absent from it means the
   * retry moved hosts.
   *
   * Sparse, like [replaced_failure_kinds]: an attempt whose agent wasn't stamped contributes
   * nothing, so this is a set to test membership against and must not be indexed against
   * [replaced_session_ids].
   */
  val replaced_agent_names: List<String> = emptyList(),

  /**
   * [failure_kind] of each replaced attempt that carried one, in attempt order.
   *
   * Dedup keeps one result per test, so without this the earlier attempts' classification is
   * gone and the only kind left to read is the surviving attempt's. Anything asking "did this
   * fail the same way twice?" would then have a single classification to reason about and would
   * be describing the pair by one of its halves. Kept alongside [replaced_failure_reasons] so a
   * consumer can classify each attempt and then combine, rather than classify the survivor and
   * call it the verdict.
   *
   * Sparse by construction: an attempt whose failure has no structured kind contributes nothing,
   * so this can be shorter than [replaced_session_ids] and must not be indexed against it.
   */
  val replaced_failure_kinds: List<String> = emptyList(),

  /**
   * [outcome] of every replaced attempt, in attempt order.
   *
   * This is what makes "did an earlier attempt fail?" answerable without a failure string.
   * [replaced_failure_reasons] can only see a failure that recorded prose, and a failure that
   * recorded none is indistinguishable from a rerun that never failed — so a rescue would be
   * reported as a plain rerun and lose the warning it earned.
   *
   * Dense, unlike [replaced_failure_kinds]: every attempt has an outcome, so this is index-aligned
   * with [replaced_session_ids] and a consumer may pair them.
   */
  val replaced_outcomes: List<Outcome> = emptyList(),

  /**
   * What this test's attempts, taken together, say about the product — see [CombinedVerdict].
   *
   * [outcome] describes only the attempt that survived dedup, so on its own it cannot distinguish
   * a failure that reproduced from one a retry replaced with a timeout.
   *
   * Always set on a generated report, including for a test that ran exactly once. Nullable only so
   * a report generated before this existed still deserializes, and consumers must render that
   * absence as unclassified rather than as any verdict. Absence must never be read as "no retry,
   * so nothing to classify": that would give a lone pass and a lone unretried failure the same
   * signature, which is the ambiguity this field exists to remove.
   */
  val combined_verdict: CombinedVerdict? = null,

  /** Priority label for this test (e.g. "P0", "P1", "P2"). Null when not set. */
  val priority: String? = null,

  // === CI Provenance (per-session) ===
  /**
   * CI job ID that produced this session — typically the provider's per-step UUID. Captured
   * at session-emit time so a later report-generation job in a different CI job can still
   * trace each result back to the originating shard. Nullable — absent for local runs or
   * pre-provenance log archives.
   */
  val ci_job_id: String? = null,

  /**
   * CI agent (worker host) that executed this session. Two attempts of the same test carrying
   * the same value ran on the same machine, so a retry that "cleared" a wedged host can be told
   * apart from one that merely re-ran on it — without this, a rescue and a genuine reproduction
   * are indistinguishable in the report. Nullable, and independently so: an agent name the
   * upload script cannot safely serialize is omitted while [ci_job_id] is still stamped, and
   * every log archive predating this field lacks it entirely.
   */
  val ci_agent_name: String? = null,

  /**
   * Filename of the per-session zip artifact that contains this session's logs
   * (e.g. `logs_uitest-sample-app-accessibility_0__a1b2c3d4.zip`). Despite the legacy `logs_`
   * prefix, the artifact this points to is the *per-session* zip (one zip per session),
   * not a step-wide bundle — the CI log-upload step decides the grouping. Combined
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

  /**
   * Roll-up of the Android on-device accessibility-tree completeness signal across this
   * session's captures. Null on non-Android sessions, on legacy log archives predating the
   * field, and on Android sessions where every capture missed the gate's window. See
   * [AccessibilityTruncationSummary] for what the field means and how to read it.
   */
  val accessibility_truncation: AccessibilityTruncationSummary? = null,
)
