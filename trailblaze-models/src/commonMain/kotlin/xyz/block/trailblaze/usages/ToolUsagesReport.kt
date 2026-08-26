package xyz.block.trailblaze.usages

import kotlinx.serialization.Serializable

/**
 * Machine-readable answer to "which trails use these tools?" — the structured output of
 * `trailblaze usages`, versioned so CI consumers can gate on it across releases.
 *
 * [tools] covers **direct usage only**: a trail names the tool in a recorded step (or a trailhead).
 * That is the one relationship the framework can state with certainty, because it comes from the
 * parsed trail model rather than a text search — including *which device classifiers'* recordings
 * carry the invocation, so an `android:`-only usage never implicates a trail's iOS legs.
 *
 * Indirect usage — one tool dispatching another from its implementation — is reported only under
 * [ChangedSinceSummary.impactedViaCallers], and only when `--changed-since` derived the queried
 * set. It is kept out of [tools] and out of the other change fields because it is an inference from
 * reading bundled code, not a fact about bytes; see that field for what it can and cannot see.
 */
@Serializable
data class ToolUsagesReport(
  /** Bump on any backward-incompatible change to this schema. Additive fields don't bump it. */
  val schemaVersion: Int = SCHEMA_VERSION,
  /** Absolute path of the primary trails root that was scanned. */
  val trailsRoot: String,
  /**
   * Absolute paths of EVERY trails root the scan actually READ, primary first. A repo whose trails
   * live under more than one root reports them all here. A root that was asked for but could not be
   * read is absent from this list and named in [warnings] instead, so this is never a claim of
   * coverage the scan cannot back.
   *
   * Read this, not [trailsRoot], to know what a zero-usage answer covered: [trailsRoot] names one
   * root, and [TrailToolUsage.root] only ever names roots that produced a hit, so neither can tell
   * "nothing uses this tool anywhere" from "nothing in the one root I scanned uses it".
   */
  val scannedRoots: List<String> = emptyList(),
  /** One entry per queried tool, in query order — including tools with zero usages. */
  val tools: List<ToolUsageResult>,
  /**
   * Trail files the scan could not parse (with the reason). A non-empty list means the report may
   * be INCOMPLETE — a consumer gating on "zero usages" should fail open rather than treat the
   * queried tool as unused.
   */
  val warnings: List<String> = emptyList(),
  /**
   * Present when the queried tools were computed by `--changed-since <ref>` rather than named
   * explicitly: which scripted tools changed between the ref and the working tree, and how.
   */
  val changedSince: ChangedSinceSummary? = null,
) {
  companion object {
    const val SCHEMA_VERSION: Int = 1
  }
}

/**
 * How the `--changed-since <ref>` tool set was derived. Change detection hashes each scripted
 * tool's source TOGETHER WITH its resolved import closure (via the bundler's content key) on both
 * sides — so editing a shared helper flags every tool that imports it. Detection is byte-level and
 * deliberately conservative: a comment-only or formatting-only edit also flags (a false "modified"
 * costs a redundant validation run; a false "unchanged" silently skips one).
 */
@Serializable
data class ChangedSinceSummary(
  /** The ref as the caller spelled it. */
  val ref: String,
  /** The commit the ref resolved to. */
  val resolvedSha: String,
  /** Tools that exist now but not at [resolvedSha]. */
  val added: List<String> = emptyList(),
  /** Tools that existed at [resolvedSha] but not now — their usages are trails now broken. */
  val removed: List<String> = emptyList(),
  /** Tools present on both sides whose bundled implementation differs. */
  val modified: List<String> = emptyList(),
  /**
   * Tools that did NOT themselves change, but whose bundled implementation dispatches one that did
   * (directly or through a chain of such dispatches) — a tool implemented as a delegation to a
   * changed tool behaves differently while fingerprinting as untouched.
   *
   * Kept separate from [modified] on purpose. Those three fields are FACTS about bytes; this one is
   * an inference from reading a bundle for names, which over-reports by design and cannot see a
   * callee name computed at runtime. A consumer that only wants certainty can ignore this field; one
   * validating blast radius should replay these too.
   */
  val impactedViaCallers: List<String> = emptyList(),
)

/** Every scanned trail that directly invokes [tool], with per-classifier context. */
@Serializable
data class ToolUsageResult(
  val tool: String,
  val usages: List<TrailToolUsage>,
)

/** One trail's direct usage of one tool. */
@Serializable
data class TrailToolUsage(
  /** The trail's id: its root-relative path with the `.trail.yaml` / `.yaml` suffix stripped. */
  val trail: String,
  /** Path of the trail file, relative to the root it was found under. */
  val path: String,
  /**
   * Absolute path of the trails root this trail was found under — always one of
   * [ToolUsagesReport.scannedRoots]. It equals [ToolUsagesReport.trailsRoot] for every trail found
   * under the primary root, and names a different root for the rest, so a single-root scan reports
   * the same value throughout. [trail] and [path] are root-relative, so without this field two
   * same-named trails from different roots would be indistinguishable (and a hit from a
   * non-primary root would masquerade as a primary-root file).
   */
  val root: String,
  /** The trail's `config.title`, when it declares one. */
  val title: String? = null,
  /**
   * Union of the classifier keys whose recordings invoke the tool, across all steps, AS AUTHORED —
   * the device-scoping signal. A tool that appears only under `android:` keys does not affect this
   * trail's iOS runs. `all` means the recording is authored for every device — but note that at
   * replay each device resolves to its single closest matching key, so an `all:` invocation is
   * shadowed on devices whose step also declares a more specific recording. Effective per-device
   * coverage therefore needs closest-wins resolution against each step's
   * [TrailStepToolUsage.declaredClassifiers].
   */
  val classifiers: List<String>,
  /**
   * The trail's own per-classifier `config.skip:` map, verbatim, when it declares one. Carried so
   * a consumer selecting trails to run can drop skipped legs without re-reading the file.
   */
  val skip: Map<String, String>? = null,
  /** The individual steps that invoke the tool. */
  val steps: List<TrailStepToolUsage>,
)

/** One step's invocation of the queried tool. */
@Serializable
data class TrailStepToolUsage(
  /** 0-based index into `trail:`, or null for the trailhead (the deterministic step 0). */
  val stepIndex: Int? = null,
  /** The step's natural-language text. */
  val step: String,
  /** The classifier keys of this step's recordings that invoke the tool, as authored. */
  val classifiers: List<String>,
  /**
   * ALL classifier keys this step declares recordings for, invoking or not. Needed to resolve
   * effective per-device coverage: replay picks each device's single closest matching key, so a
   * device whose closest declared key is NOT in [classifiers] never invokes the tool on this step
   * even when a broader key (e.g. `all`) does. A consumer selecting device legs should
   * closest-wins-resolve its device against this set and check membership in [classifiers].
   */
  val declaredClassifiers: List<String>,
)
