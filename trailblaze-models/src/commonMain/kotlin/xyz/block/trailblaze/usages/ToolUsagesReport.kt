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
  /**
   * Absolute path of the primary trails root that was scanned.
   *
   * Absolute on purpose — a consumer resolving [TrailToolUsage.path] needs a real filesystem
   * anchor, and the alternative (relative to an unstated cwd) is ambiguous where it matters most.
   * The tradeoff is that an ARCHIVED report is machine-specific: every root and
   * [ToolUsageResult.sourcePaths] entry names a path that may not exist on the machine reading it.
   * Accepted, not a bug — pair an archived report with [generatedBy] and
   * [ChangedSinceSummary.workingTree] and treat the paths as provenance rather than as an index.
   */
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
   * Every reason this report may be INCOMPLETE, as prose. A non-empty list means a consumer gating
   * on "zero usages" should fail open rather than treat the queried tool as unused.
   *
   * DERIVED from [diagnostics] — the message of every diagnostic whose
   * [UsagesDiagnostic.severity] is [UsagesDiagnostic.INCOMPLETENESS], in the same order — so the
   * two can never disagree about what went wrong. Reports built through [of] hold that by
   * construction; see [of] for what the public constructor does not enforce.
   *
   * [UsagesDiagnostic.HINT] diagnostics are deliberately EXCLUDED. This list is a fail-open
   * trigger, and a hint about a name the caller typed is not a reason to distrust the scan — a
   * hint landing here would leave a consumer permanently failing open with nothing to fix. Read
   * [diagnostics] to see every diagnostic including hints; read this to show a human the reasons
   * the answer may be short.
   */
  val warnings: List<String> = emptyList(),
  /**
   * Every diagnostic, classified: what KIND of thing happened, WHICH entity it happened to, and
   * whether it means the report may be incomplete ([UsagesDiagnostic.severity]). A consumer that
   * must gate differently per failure class — an unscanned trails root hides whole subtrees of
   * usages, while one unparseable trail hides one file's — reads this instead of pattern-matching
   * [warnings]. A superset of [warnings]: hints appear only here.
   *
   * EMPTY does not prove a clean scan on a report from an older producer. This field shipped after
   * [warnings] and [SCHEMA_VERSION] did not bump for it (it is additive), so a report generated
   * before it existed carries populated [warnings] and no diagnostics. A gate keyed on this field
   * must still fail open when [warnings] is non-empty; [generatedBy] is what distinguishes the two
   * cases.
   */
  val diagnostics: List<UsagesDiagnostic> = emptyList(),
  /**
   * Present when the queried tools were computed by `--changed-since <ref>` rather than named
   * explicitly: which scripted tools changed between the ref and the working tree, and how.
   */
  val changedSince: ChangedSinceSummary? = null,
  /**
   * The Trailblaze version that produced this report — the bare version string, which is what
   * `trailblaze --version` prints after its `Trailblaze ` prefix. Null when the producer did not
   * record one.
   *
   * Load-bearing once reports become build artifacts: [schemaVersion] says which FIELDS to expect,
   * not which behavior produced the values. A CLI that improved its change detection emits the same
   * schema with different answers, and a triager comparing two reports needs to know that before
   * blaming the code under test.
   *
   * Declared last, where it reads least naturally, so it lands at `component8()` instead of shifting
   * every existing one — see the constructor below.
   */
  val generatedBy: String? = null,
) {
  /**
   * The published pre-[diagnostics] constructor and `copy` descriptors, kept so this module's
   * released artifact stays binary-compatible: a defaulted parameter REPLACES the old signatures
   * rather than adding overloads, so a consumer compiled against `v2026.08.25` would otherwise get
   * `NoSuchMethodError`. `HIDDEN`, not a plain overload, because `copy(trailsRoot = x)` would be
   * applicable to both and Kotlin picks the shorter one — silently dropping the newer fields.
   *
   * Nothing in this repo can call these, so nothing tests them. The guard is the committed API
   * baseline: deleting either removes a line from `trailblaze-models.api` and fails `apiCheck`.
   *
   * [scannedRoots] and [diagnostics] come back EMPTY, and [warnings] is passed through un-derived.
   * That is what a report built through this signature actually was — the release that published it
   * had no `diagnostics` to derive from — and it is why [of] exists for every current caller.
   *
   * `component5()` is NOT restorable. It returned [changedSince] at release and returns [warnings]
   * now; two `component5()` overloads differing only in return type cannot both be declared in
   * Kotlin. That break landed with `diagnostics` and is permanent — destructuring is the one part of
   * this class's released surface a shim cannot reach.
   */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    // `schemaVersion`, `warnings` and `changedSince` keep their defaults so this also re-emits the
    // synthetic `<init>(..., int, DefaultConstructorMarker)` the released shape had. A Kotlin
    // consumer that omitted any of them compiled against THAT descriptor, not the plain five-argument
    // form.
    schemaVersion: Int = SCHEMA_VERSION,
    trailsRoot: String,
    tools: List<ToolUsageResult>,
    warnings: List<String> = emptyList(),
    changedSince: ChangedSinceSummary? = null,
  ) : this(
    schemaVersion = schemaVersion,
    trailsRoot = trailsRoot,
    scannedRoots = emptyList(),
    tools = tools,
    warnings = warnings,
    diagnostics = emptyList(),
    changedSince = changedSince,
    generatedBy = null,
  )

  /** Carries the post-release fields through: a caller compiled against the old signature had no way
   *  to name them, so it cannot have meant to clear them. See the constructor above. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  fun copy(
    schemaVersion: Int = this.schemaVersion,
    trailsRoot: String = this.trailsRoot,
    tools: List<ToolUsageResult> = this.tools,
    warnings: List<String> = this.warnings,
    changedSince: ChangedSinceSummary? = this.changedSince,
  ): ToolUsagesReport = ToolUsagesReport(
    schemaVersion = schemaVersion,
    trailsRoot = trailsRoot,
    scannedRoots = scannedRoots,
    tools = tools,
    warnings = warnings,
    diagnostics = diagnostics,
    changedSince = changedSince,
    generatedBy = generatedBy,
  )

  companion object {
    const val SCHEMA_VERSION: Int = 1

    /**
     * Builds a report with [warnings] derived from [diagnostics], and the ONLY constructor a
     * producer should use. Setting the two lists independently is what lets them drift, and a
     * `warnings` list that omits a diagnostic is worse than either field alone: a human reading the
     * report is told the scan was clean while a machine reading it is told otherwise.
     *
     * Not enforceable by the type. This is a public `data class`, so the primary constructor and
     * the generated `copy()` remain callable and neither derives [warnings] — a `copy` that sets
     * one list and not the other is exactly the drift this function exists to prevent. It is also
     * deliberately not an `init { require(...) }`: a report DESERIALIZED from a producer that
     * predates [diagnostics] has populated [warnings] and none of them, and rejecting those would
     * turn a readable older artifact into a parse failure.
     */
    fun of(
      trailsRoot: String,
      scannedRoots: List<String>,
      tools: List<ToolUsageResult>,
      diagnostics: List<UsagesDiagnostic>,
      changedSince: ChangedSinceSummary? = null,
      generatedBy: String? = null,
    ): ToolUsagesReport = ToolUsagesReport(
      generatedBy = generatedBy,
      trailsRoot = trailsRoot,
      scannedRoots = scannedRoots,
      tools = tools,
      warnings = diagnostics.filter { it.severity == UsagesDiagnostic.INCOMPLETENESS }.map { it.message },
      diagnostics = diagnostics,
      changedSince = changedSince,
    )
  }
}

/**
 * One classified thing the scan wants to tell the caller — in almost every case a reason a
 * [ToolUsagesReport] may be incomplete.
 *
 * [kind] is a **string, not an enum**, on purpose: new kinds are added additively without bumping
 * [ToolUsagesReport.SCHEMA_VERSION], and a consumer deserializing into an enum would fail on the
 * first one it had never heard of. Match the kinds you handle and ignore the rest — but treat any
 * unrecognized kind as "the report may be incomplete for a reason I don't understand", which is the
 * same fail-open posture [ToolUsagesReport.warnings] already asks for. Read [severity] rather than
 * enumerating kinds to decide whether an unrecognized one is a fail-open trigger.
 */
@Serializable
data class UsagesDiagnostic(
  /** One of the `kind` constants below, or a kind added by a newer CLI than this reader. */
  val kind: String,
  /**
   * What this diagnostic is about: a trail file's path, a trails root's path, a tool name, the side
   * of a comparison (`base` / `current`), or — for a diagnostic about an aggregate rather than one
   * entity — the report field whose completeness is affected.
   */
  val subject: String,
  /** The human-readable explanation. Appears verbatim in [ToolUsagesReport.warnings] when [INCOMPLETENESS]. */
  val message: String,
  /**
   * Whether this diagnostic means the report may be INCOMPLETE, or is merely a hint about the
   * query — [INCOMPLETENESS] or [HINT].
   *
   * The field a gate should key on, so that a kind added by a newer CLI classifies itself instead
   * of falling into whichever bucket this reader's `when` happens to default to. Only
   * [INCOMPLETENESS] diagnostics reach [ToolUsagesReport.warnings]: a hint about a name the caller
   * typed is not a reason to distrust the scan, and treating it as one leaves a consumer failing
   * open forever with nothing to fix.
   *
   * A string for the same reason as [kind]. Defaults to [INCOMPLETENESS] — the conservative
   * reading, and correct for every kind that predates this field.
   */
  val severity: String = INCOMPLETENESS,
) {
  companion object {

    /**
     * Something the scan could not see, so the report may be missing usages. A non-empty set of
     * these is a fail-open trigger, and their messages are what [ToolUsagesReport.warnings] holds.
     */
    const val INCOMPLETENESS: String = "incompleteness"

    /**
     * Nothing went wrong — the scan is telling the caller something useful about their QUERY. Kept
     * out of [ToolUsagesReport.warnings] so it cannot be read as incompleteness.
     */
    const val HINT: String = "hint"
    /**
     * A trail file could not be parsed, so its usages are missing from [ToolUsagesReport.tools].
     * Subject: the trail file's path. Blast radius: one trail.
     */
    const val TRAIL_UNPARSEABLE: String = "trail-unparseable"

    /**
     * A trails root that was asked for could not be read, so nothing under it was scanned and it is
     * absent from [ToolUsagesReport.scannedRoots]. Subject: the root's path. Blast radius: every
     * trail in that subtree — the widest of these kinds, and the one a CI gate should fail on.
     */
    const val ROOT_UNSCANNED: String = "root-unscanned"

    /**
     * A tool's bundle could not be produced while fingerprinting, so its import closure is unknown
     * on that side and edits to files it imports cannot flag it. Subject: the tool's name. Affects
     * [ChangedSinceSummary.modified].
     */
    const val TOOL_BUNDLING_FAILED: String = "tool-bundling-failed"

    /**
     * A tool's two sides were not comparable like for like — one resolved an import closure and the
     * other could not, or neither did and the comparison fell back to the tool's own bytes.
     * Subject: the tool's name. The tool is counted as modified (fail open) in the first case, and
     * compared blind to its imports in the second.
     */
    const val TOOL_COMPARISON_DEGRADED: String = "tool-comparison-degraded"

    /**
     * Tools were left out of the comparison entirely because no git ref can contain them (they are
     * gitignored — e.g. a trailmap staged from another repo's pinned clone). Subject: the declaring
     * trailmap's directory name. Their changes are invisible to `--changed-since` here and must be
     * validated against their owning repo.
     */
    const val TOOL_COMPARISON_EXCLUDED: String = "tool-comparison-excluded"

    /**
     * One side's scripted-tool inventory scan hit a problem (a malformed descriptor, an ambiguous
     * bare `.ts`), so a tool may be missing from that side and read as added or removed. Subject:
     * `base` or `current`.
     */
    const val TOOL_INVENTORY_INCOMPLETE: String = "tool-inventory-incomplete"

    /**
     * Tools went unscanned for tool-to-tool dispatch because their bundle could not be produced, so
     * no edge points OUT of them and [ChangedSinceSummary.impactedViaCallers] may under-report.
     * Subject: `impactedViaCallers`, the field whose completeness is affected — this one diagnostic
     * covers every unscanned tool, which its message names in full.
     */
    const val CALLER_SCAN_UNAVAILABLE: String = "caller-scan-unavailable"

    /**
     * A named tool is absent from the workspace's SCRIPTED tool inventory, so
     * [ToolUsageResult.sourcePaths] is empty for it. Subject: the tool name.
     *
     * Deliberately not called "unknown tool": built-in and class-backed tools are legitimately
     * absent from this inventory and scan normally, so this is a hint and not an error. What it
     * closes is the case where a zero-usage answer for a MISSPELLED scripted-tool name is
     * indistinguishable from a truthful "nothing uses this tool" — the answer that greenlights
     * deleting the wrong thing.
     *
     * Emitted only when the workspace inventory could actually be read AND read completely; a
     * report from a directory with no workspace above it, or one whose inventory scan produced a
     * [TOOL_INVENTORY_INCOMPLETE] of its own, says nothing here rather than accusing a name that
     * is missing because the scan failed to see it.
     *
     * Carries [severity] [HINT], so it never lands in [ToolUsagesReport.warnings] — nothing about
     * it says the scan was incomplete.
     */
    const val TOOL_NOT_IN_SCRIPTED_INVENTORY: String = "tool-not-in-scripted-inventory"
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
  /**
   * The state of the OTHER side of the comparison — the working tree the tools were read from.
   *
   * [ref] and [resolvedSha] pin only the baseline. Two reports naming the same ref can disagree
   * completely, and without this there is no way to tell a stale report from a real change, or to
   * reproduce one. Null when the working tree's own state could not be read (not a git repository,
   * or git could not answer): a comparison must say nothing rather than claim a clean tree.
   */
  val workingTree: WorkingTreeState? = null,
) {
  /**
   * The pre-[workingTree] constructor and `copy` descriptors, kept so this module's released
   * artifact stays binary-compatible — see `ToolUsagesReport`'s shim above for the full reason and
   * for what guards these.
   *
   * Unlike that one, this shim loses nothing: [workingTree] is appended LAST, so every existing
   * `componentN()` keeps its position and type.
   */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    ref: String,
    resolvedSha: String,
    // The four lists keep their defaults so this also re-emits the synthetic
    // `<init>(..., int, DefaultConstructorMarker)` the old shape had.
    added: List<String> = emptyList(),
    removed: List<String> = emptyList(),
    modified: List<String> = emptyList(),
    impactedViaCallers: List<String> = emptyList(),
  ) : this(ref, resolvedSha, added, removed, modified, impactedViaCallers, null)

  /** Carries [workingTree] through: a caller compiled against the old signature had no way to name
   *  it, so it cannot have meant to clear it. See the constructor above. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  fun copy(
    ref: String = this.ref,
    resolvedSha: String = this.resolvedSha,
    added: List<String> = this.added,
    removed: List<String> = this.removed,
    modified: List<String> = this.modified,
    impactedViaCallers: List<String> = this.impactedViaCallers,
  ): ChangedSinceSummary =
    ChangedSinceSummary(ref, resolvedSha, added, removed, modified, impactedViaCallers, workingTree)
}

/**
 * The working tree a report was generated from: the commit `HEAD` points at, and whether tracked
 * content differs from it.
 */
@Serializable
data class WorkingTreeState(
  /** The commit `HEAD` resolved to when the report was generated. */
  val headSha: String,
  /**
   * Whether TRACKED content differed from [headSha] — so a reader knows whether [headSha] alone
   * reproduces this report.
   *
   * Untracked files are excluded. An untracked scripted tool is ordinary work in progress that the
   * comparison already reports as `added`, so counting it here would mark every report a developer
   * runs mid-edit as unreproducible.
   */
  val dirty: Boolean,
)

/** Every scanned trail that directly invokes [tool], with per-classifier context. */
@Serializable
data class ToolUsageResult(
  val tool: String,
  val usages: List<TrailToolUsage>,
  /**
   * WHY this tool is in the queried set — one of the `changeKind` constants below.
   *
   * In `--changed-since` mode this is the same answer as looking the tool up across
   * [ChangedSinceSummary]'s four lists, carried here so a consumer does not have to do that join to
   * learn what it is holding. It matters most for [REMOVED]: those [usages] are trails that are now
   * BROKEN, which reads nothing like the other kinds and is easy to miss when the only signal for it
   * is membership in a list somewhere else in the document.
   *
   * A string, not an enum, for the same reason as [UsagesDiagnostic.kind].
   *
   * The default is [UNKNOWN], not [NAMED]: a producer always writes this field explicitly, so the
   * default is reached only when DECODING a report written before the field existed. Defaulting to
   * `named` would turn "this producer could not say" into a confident wrong answer — a
   * `--changed-since` report from an older CLI would present every one of its tools as
   * caller-named, including the [REMOVED] ones whose usages are broken trails. On [UNKNOWN] a
   * consumer must fall back to joining [ChangedSinceSummary]'s four lists, which is exactly what
   * this field exists to spare it when the producer is new enough to have written it.
   */
  val changeKind: String = UNKNOWN,
  /**
   * Absolute path(s) of the scripted-tool script(s) declaring [tool], when the workspace's tool
   * inventory could be resolved.
   *
   * A LIST because two trailmaps may legally declare the same tool name — the runtime loader
   * enforces uniqueness only within a trailmap — and a single path would silently pick one of them.
   * Ordinarily one entry; two or more means a name is declared more than once, which is worth
   * seeing.
   *
   * EMPTY has three causes and cannot tell them apart on its own: no workspace was reachable, the
   * tool is not a scripted tool (a built-in or class-backed tool is legitimately absent from this
   * inventory and still scans normally), or the name is a typo. Check [ToolUsagesReport.diagnostics]
   * for [UsagesDiagnostic.TOOL_NOT_IN_SCRIPTED_INVENTORY], which distinguishes the first case from
   * the other two.
   */
  val sourcePaths: List<String> = emptyList(),
) {
  /**
   * The pre-[changeKind]/[sourcePaths] constructor and `copy` descriptors, kept so this module's
   * published artifact stays binary-compatible — the same shim, for the same reason, as
   * `TrailRecordingResolution`'s.
   *
   * Defaulting the two new parameters does NOT preserve the old JVM signatures: it REPLACES
   * `<init>(String,List)V` and the two-argument `copy`/`copy$default` with four-argument forms, so a
   * consumer compiled against the `v2026.08.24` or `v2026.08.25` artifact — the releases that
   * carried this class before these fields — throws `NoSuchMethodError` on upgrade. The defaults
   * keep older SERIALIZED reports decodable; these keep older COMPILED callers linking.
   *
   * [DeprecationLevel.HIDDEN] rather than plain overloads: it emits the bytecode while removing the
   * members from source resolution. As plain overloads, `copy(usages = filtered)` would be
   * applicable to both and Kotlin picks the SHORTER one, so ordinary source would silently drop a
   * tool's attribution.
   *
   * Nothing in this repo can call them, so nothing tests them. The guard is the committed API
   * baseline: deleting either removes a line from `trailblaze-models.api` and fails `apiCheck`.
   */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(tool: String, usages: List<TrailToolUsage>) : this(tool, usages, UNKNOWN, emptyList())

  /** Carries [changeKind] and [sourcePaths] through: a caller compiled against the old signature had
   *  no way to name either, so it cannot have meant to clear them. See the constructor above. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  fun copy(
    tool: String = this.tool,
    usages: List<TrailToolUsage> = this.usages,
  ): ToolUsageResult = ToolUsageResult(tool, usages, changeKind, sourcePaths)

  companion object {
    /**
     * The report does not say — a field-less report from a producer predating [changeKind]. Never
     * written by a current producer; see that field's KDoc for what a consumer must do on it.
     */
    const val UNKNOWN: String = "unknown"

    /** The caller named this tool explicitly. */
    const val NAMED: String = "named"

    /** `--changed-since`: exists now, absent at the ref. */
    const val ADDED: String = "added"

    /** `--changed-since`: existed at the ref, absent now — these usages are trails now BROKEN. */
    const val REMOVED: String = "removed"

    /** `--changed-since`: present on both sides, bundled implementation differs. */
    const val MODIFIED: String = "modified"

    /** `--changed-since`: unchanged itself, but its bundle dispatches a tool that changed. */
    const val IMPACTED_VIA_CALLERS: String = "impactedViaCallers"
  }
}

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
  /**
   * Every device classifier this TRAIL declares direction for, invoking or not — its `config.devices`
   * run matrix plus every classifier keying a recording, with multi-device configuration names
   * resolved to their member devices. The denominator [invokingDevices] is a subset of.
   *
   * May contain `all`, which is a real coverage token here ("the leg nothing more specific claims"),
   * not a placeholder: a trail that keys only `all:` recordings declares exactly `[all]`.
   *
   * Order is the trail's own declaration order (`config.devices` first, then the trailhead, then
   * steps in order), not sorted — deterministic for a given file, so a report diffs cleanly, but do
   * not read it as a ranking. [invokingDevices] preserves this order for the subset it keeps.
   */
  val devices: List<String> = emptyList(),
  /**
   * The subset of [devices] that actually reaches the tool, after closest-wins resolution.
   *
   * This is the field a consumer selecting lanes should key on, and it is NOT derivable from
   * [classifiers] by set membership: replay picks each device's single closest matching recording
   * key, so an `all:` invocation is shadowed on any device whose step also declares something more
   * specific that does not invoke. A trail keying `all:` and `ios-iphone:` in one step, with the tool
   * only under `all:`, lists `ios-iphone` in [devices] and omits it here.
   *
   * A member of a multi-device configuration reaches the tool when the CONFIGURATION's recording
   * does: selection puts the configuration's own name at the head of the resolution chain, so a
   * step keyed only `pos-pair:` invokes on every device that configuration casts.
   *
   * ABSENT is not EMPTY. This field shipped additively at [ToolUsagesReport.SCHEMA_VERSION] 1, so a
   * report from an older producer omits the key entirely and a consumer deserializing it gets `[]`
   * — which reads as "reaches no device" and would replay nothing, the exact miss this field
   * exists to prevent. Distinguish the two before gating on it: decode into a nullable and fall
   * back to [classifiers] when the key is missing, or require a [ToolUsagesReport.generatedBy] you
   * know emits it.
   *
   * [classifiers] and [TrailStepToolUsage.declaredClassifiers] stay available for a consumer that
   * needs the raw authored facts rather than this derivation.
   */
  val invokingDevices: List<String> = emptyList(),
) {
  /**
   * The pre-[devices]/[invokingDevices] constructor and `copy` descriptors, kept so this module's
   * published artifact stays binary-compatible — see `ToolUsageResult`'s shim for the full reason.
   * A consumer compiled against `v2026.08.24` or `v2026.08.25` calls the seven-argument forms, which
   * defaulting the two new parameters would otherwise have replaced.
   *
   * The new fields are appended LAST rather than grouped beside [classifiers], where they read more
   * naturally, because inserting them there shifts every later `componentN()` — `component6()` would
   * change from `skip`'s `Map` to a `List` — and a `componentN` break is NOT recoverable by a shim:
   * two `component6()` overloads differing only in return type cannot both be declared in Kotlin.
   * Appending keeps destructuring additive, at the cost of field order in the emitted JSON.
   */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  constructor(
    trail: String,
    path: String,
    root: String,
    // `title` and `skip` keep their defaults so this also re-emits the synthetic
    // `<init>(..., int, DefaultConstructorMarker)` the old shape had. A Kotlin consumer that omitted
    // either one compiled against THAT descriptor, not the plain seven-argument form.
    title: String? = null,
    classifiers: List<String>,
    skip: Map<String, String>? = null,
    steps: List<TrailStepToolUsage>,
  ) : this(trail, path, root, title, classifiers, skip, steps, emptyList(), emptyList())

  /** Carries [devices] and [invokingDevices] through: a caller compiled against the old signature
   *  had no way to name either, so it cannot have meant to clear them. See the constructor above. */
  @Deprecated("Binary compatibility only", level = DeprecationLevel.HIDDEN)
  fun copy(
    trail: String = this.trail,
    path: String = this.path,
    root: String = this.root,
    title: String? = this.title,
    classifiers: List<String> = this.classifiers,
    skip: Map<String, String>? = this.skip,
    steps: List<TrailStepToolUsage> = this.steps,
  ): TrailToolUsage =
    TrailToolUsage(trail, path, root, title, classifiers, skip, steps, devices, invokingDevices)
}

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
