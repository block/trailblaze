package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.TrailblazeVersion
import xyz.block.trailblaze.host.WorkspaceTypeScriptSetup
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.scripting.DaemonScriptedToolBundler
import xyz.block.trailblaze.scripting.LazyYamlScriptedToolRegistration
import xyz.block.trailblaze.trailrunner.TrailIndexBuilder
import xyz.block.trailblaze.trailrunner.resolveRoots
import xyz.block.trailblaze.trailrunner.resolveTrailFile
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.usages.ChangedSinceSummary
import xyz.block.trailblaze.usages.ChangedToolAnalysis
import xyz.block.trailblaze.usages.GitRefTree
import xyz.block.trailblaze.usages.ScriptedToolSourceSnapshotScanner
import xyz.block.trailblaze.usages.ToolCallerEdgeScanner
import xyz.block.trailblaze.usages.ToolFingerprint
import xyz.block.trailblaze.usages.ToolSourceSnapshot
import xyz.block.trailblaze.usages.ToolUsageResult
import xyz.block.trailblaze.usages.ToolUsagesReport
import xyz.block.trailblaze.usages.TrailStepToolUsage
import xyz.block.trailblaze.usages.TrailToolUsage
import xyz.block.trailblaze.usages.TrailToolUsageScanner
import xyz.block.trailblaze.usages.UsagesDiagnostic
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runJsonOutput
import xyz.block.trailblaze.util.runQuiet
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import xyz.block.trailblaze.yaml.unified.UnifiedTrailTargets
import java.io.File
import java.io.IOException
import java.nio.file.Path
import java.util.concurrent.Callable

/**
 * `trailblaze usages` — find every trail that directly invokes a tool, with device context.
 *
 * The IDE analogy is Find Usages: a tool is a function, and this answers "who calls it" from the
 * parsed trail model rather than a text search. Parsing is what makes the answer trustworthy —
 * a recorded invocation is distinguishable from a mention in step text or a comment, and each
 * usage carries the device-classifier keys whose recordings invoke the tool, so an
 * `android:`-only usage never implicates a trail's iOS legs.
 *
 * Direct usage only, on purpose. A tool dispatching another tool from its implementation is code
 * analysis, not a fact the trail model states — run this command on the caller once you've read
 * the tool's source and found who composes it.
 */
@Command(
  name = "usages",
  mixinStandardHelpOptions = true,
  description = [
    "Find every trail that directly invokes a tool (IDE \"Find Usages\" for tools).",
    "Scans the trails directory's recordings via the parsed trail model, so each usage " +
      "reports WHICH device classifiers invoke the tool — not just which trails.",
    "Typical uses: before editing a tool, list the trails (and platforms) that would " +
      "exercise the change; before deleting one, confirm nothing invokes it.",
  ],
)
class UsagesCommand : Callable<Int> {

  @Parameters(
    index = "0..*",
    arity = "0..*",
    paramLabel = "<tool>",
    description = ["Tool name(s) to find trail usages for (e.g. myapp_launchSignedIn)."],
  )
  var toolNames: List<String> = emptyList()

  @Option(
    names = ["--changed-since"],
    paramLabel = "<ref>",
    description = [
      "Instead of naming tools, derive them: compare the workspace's scripted tools against " +
        "git <ref> and report usages for every tool that was added, removed, or modified. " +
        "Detection hashes each tool's source together with its resolved import closure, so " +
        "editing a shared helper flags every tool that imports it.",
    ],
  )
  var changedSince: String? = null

  @Option(
    names = ["--json"],
    description = [
      "Emit the machine-readable JSON report (schemaVersion ${ToolUsagesReport.SCHEMA_VERSION}) " +
        "to stdout instead of the human-readable summary.",
    ],
  )
  var json: Boolean = false

  @Option(
    names = ["--trails"],
    paramLabel = "<dir>",
    description = [
      "Trails directory to scan. Repeatable — a repo whose trails live under more than one root " +
        "scans them all in one pass, and every reported usage names the root it was found under. " +
        "Order matters: the FIRST one is the primary root, which is what the report names and " +
        "what --changed-since walks up from to find the workspace's trailmaps. " +
        "Passing any --trails replaces the configured roots rather than adding to them. " +
        "Default: the workspace's effective trails directory (TRAILBLAZE_TRAILS_DIR, the " +
        "workspace `trails:` declaration, or the configured default) plus any extra roots " +
        "configured in Trail Runner.",
    ],
  )
  var trailsDirs: List<String> = emptyList()

  /**
   * With `--json`, stdout is a machine-readable document a caller pipes straight into `jq`,
   * so every other channel has to move off it — for the whole command, not just report
   * production: resolving the trails directory logs which config file and which directory it
   * picked, and those lines land ahead of the opening `{`.
   *
   * Both scopes are needed. Json mode routes what the command still says to stderr, so a
   * message can never reach a `jq` consumer. Quiet mode drops the per-trail scan chatter that
   * report production emits — a large workspace logs a deprecation line per trail, over a
   * thousand of them, and redirecting that to a developer's terminal instead of suppressing it
   * would be worse than the bug being fixed. Nothing worth reading is lost: failures go through
   * [Console.error], which always writes to stderr, and anything scan-worthy is in the report's
   * `warnings`.
   */
  override fun call(): Int = if (json) {
    Console.runJsonOutput { Console.runQuiet { runUsages() } }
  } else {
    runUsages()
  }

  private fun runUsages(): Int {
    val queried = toolNames.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    val ref = changedSince?.trim()?.takeIf { it.isNotEmpty() }
    if (ref != null && queried.isNotEmpty()) {
      Console.error("Pass tool names or --changed-since, not both.")
      return TrailblazeExitCode.MISUSE.code
    }
    if (ref == null && queried.isEmpty()) {
      Console.error("No tool name given. Name tools, or pass --changed-since <ref> to derive them.")
      return TrailblazeExitCode.MISUSE.code
    }

    val explicitRoots = when (val roots = resolveExplicitRoots()) {
      is ExplicitRoots.Invalid -> {
        Console.error(roots.message)
        return TrailblazeExitCode.MISUSE.code
      }
      is ExplicitRoots.Valid -> roots.roots
    }
    val (primary, extras) = if (explicitRoots.isNotEmpty()) {
      // The first root is primary: it anchors the walk-up that finds the workspace's trailmaps
      // for --changed-since, and the rest are scanned as extras exactly like configured ones.
      explicitRoots.first() to explicitRoots.drop(1)
    } else {
      val resolved = resolveRoots {
        val config = CliConfigHelper.readConfig() ?: CliConfigHelper.defaultConfig()
        File(TrailblazeDesktopUtil.getEffectiveTrailsDirectory(config))
      }
      if (!resolved.first.isDirectory) {
        Console.error(
          "No trails directory found (resolved ${resolved.first.path}). Run from a workspace with " +
            "trails, or pass --trails <dir>.",
        )
        return TrailblazeExitCode.MISUSE.code
      }
      resolved
    }

    return when (val outcome = produceReport(ref, queried, primary, extras)) {
      is Outcome.Failure -> {
        Console.error(outcome.message)
        outcome.exitCode.code
      }
      is Outcome.Report -> {
        if (json) {
          println(REPORT_JSON.encodeToString(ToolUsagesReport.serializer(), outcome.report))
        } else {
          printHumanReadable(outcome.report)
        }
        TrailblazeExitCode.SUCCESS.code
      }
    }
  }

  private sealed interface ExplicitRoots {
    /** The `--trails` roots in the order given, deduplicated. Empty when none were passed. */
    data class Valid(val roots: List<File>) : ExplicitRoots
    data class Invalid(val message: String) : ExplicitRoots
  }

  /**
   * The `--trails` roots to scan, validated as a set before any scanning begins — so a bad second
   * `--trails` is reported as the mistake it is rather than as a quietly narrower answer.
   *
   * Three ways a caller can name roots that would produce a wrong count rather than a wrong-looking
   * one, all rejected or collapsed here:
   * - A blank value. `--trails "$UNSET"` would otherwise leave no explicit roots at all and fall
   *   back to the CONFIGURED trails directory — a different tree, scanned with full confidence.
   * - Two spellings of one directory (a trailing slash, a relative path, a symlink). Compared by
   *   canonical path and collapsed, because scanning a root twice reports every usage in it twice
   *   under an identical `root` — indistinguishable from two real hits.
   * - One root nested inside another, which double-reports the inner root's trails under two
   *   different root-relative ids. Rejected rather than collapsed: which of the two the caller
   *   meant to drop is not knowable, and the outer one alone already covers both.
   */
  private fun resolveExplicitRoots(): ExplicitRoots {
    if (trailsDirs.isEmpty()) return ExplicitRoots.Valid(emptyList())
    val kept = mutableListOf<Pair<File, String>>()
    for (raw in trailsDirs) {
      val path = raw.trim()
      if (path.isEmpty()) return ExplicitRoots.Invalid("--trails was given an empty path.")
      val dir = File(path)
      if (!dir.isDirectory) return ExplicitRoots.Invalid("--trails $path is not a directory.")
      val canonical = runCatching { dir.canonicalPath }.getOrElse { dir.absolutePath }
      val clash = kept.firstOrNull { (_, other) -> canonical.isNestedIn(other) || other.isNestedIn(canonical) }
      if (clash != null) {
        return ExplicitRoots.Invalid(
          "--trails $path and --trails ${clash.first.path} overlap — one contains the other, so " +
            "every trail under the inner root would be reported twice. Name only the outer one.",
        )
      }
      if (kept.none { (_, other) -> other == canonical }) kept.add(dir to canonical)
    }
    return ExplicitRoots.Valid(kept.map { it.first })
  }

  /** True when this canonical path is strictly inside [other] — equal paths are duplicates, not nesting. */
  private fun String.isNestedIn(other: String): Boolean = startsWith(other + File.separator)

  /**
   * Per-tool provenance the trail scan cannot know on its own: WHY a tool is in the queried set, and
   * which scripted-tool script declares it.
   *
   * [scriptedToolPaths] is null when no workspace inventory could be read at all — distinct from an
   * empty map, which means the inventory WAS read and named none of the queried tools. Only the
   * second justifies telling a caller their tool name might be a typo.
   */
  internal data class ToolAttribution(
    val changeKinds: Map<String, String>,
    val scriptedToolPaths: Map<String, List<String>>?,
    /**
     * Whether a queried name missing from [scriptedToolPaths] is worth telling the caller about.
     *
     * True only when the caller NAMED the tools, which is the only mode where a name can be a typo.
     * `--changed-since` derives its own set, so every name there is real by construction — and a
     * REMOVED tool is legitimately absent from the current inventory, which under this flag would
     * be reported as a possible misspelling of the very deletion being analysed.
     *
     * Also false when the inventory scan itself was incomplete: a name the scan never managed to
     * read is absent for a reason that has nothing to do with spelling, and saying otherwise points
     * the caller at the one explanation that is definitely wrong.
     */
    val flagNamesAbsentFromInventory: Boolean,
    /**
     * What went wrong while reading the inventory, if anything — carried so it reaches the report
     * instead of being dropped at the point the inventory is reduced to paths. These are exactly the
     * cases where a real tool is missing from [scriptedToolPaths] (a descriptor that did not decode,
     * a descriptor naming a script that does not exist, an ambiguous bare `.ts`), which is also
     * exactly what makes an "absent from the inventory" hint misleading.
     */
    val diagnostics: List<UsagesDiagnostic> = emptyList(),
  ) {
    companion object {
      val NONE = ToolAttribution(emptyMap(), null, flagNamesAbsentFromInventory = false)
    }
  }

  private fun produceReport(ref: String?, queried: List<String>, primary: File, extras: List<File>): Outcome {
    if (ref == null) {
      // Explicit mode never needed the workspace before. It is read now — a directory walk over the
      // trailmaps' tool dirs, no esbuild and no git — so `usages myapp_doThing` can answer "declared
      // where?" and can tell a misspelled scripted-tool name from a tool nothing uses.
      return Outcome.Report(buildReport(queried, primary, extras, explicitModeAttribution(primary)))
    }

    // Walked up, because the trails root need not be the workspace root — e.g. a repo whose
    // trails live in `legacy-trails/` with config at `<repo>/trailblaze-config/`.
    val workspaceRoot = CliPathUtils.findWorkspaceRoot(primary.absoluteFile.toPath())
      ?: return Outcome.Failure(
        TrailblazeExitCode.MISUSE,
        "No trailmaps directory found walking up from ${primary.path} (looked for " +
          TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES
            .joinToString(" and ") { "<root>/$it/${TrailblazeConfigPaths.TRAILMAPS_SUBDIR}" } +
          "). --changed-since needs scripted-tool sources to compare.",
      )
    val trailmapsDir = CliPathUtils.workspaceTrailmapsDir(workspaceRoot).toFile()
    val gitRoot = GitRefTree.gitRootOf(trailmapsDir)
      ?: return Outcome.Failure(
        TrailblazeExitCode.MISUSE,
        "${trailmapsDir.path} is not inside a git repository, so --changed-since has no ref to compare against.",
      )
    val relTrailmaps = trailmapsDir.canonicalFile.relativeToOrNull(gitRoot.canonicalFile)
      ?: return Outcome.Failure(
        TrailblazeExitCode.INFRA_FAILED,
        "Could not relativize ${trailmapsDir.path} against its git root ${gitRoot.path}.",
      )
    val sha = GitRefTree.resolveCommit(gitRoot, ref)
      ?: return Outcome.Failure(
        TrailblazeExitCode.MISUSE,
        "'$ref' does not resolve to a commit in ${gitRoot.path} (try fetching it first).",
      )
    val esbuild = LazyYamlScriptedToolRegistration.resolveEsbuildBinary()
      ?: return Outcome.Failure(
        TrailblazeExitCode.INFRA_FAILED,
        "--changed-since compares tools by their bundled implementation, which needs esbuild — " +
          "none found on PATH or in a reachable sdks/typescript/node_modules. Install esbuild " +
          "(e.g. `brew install esbuild`) and retry.",
      )
    val bundler = DaemonScriptedToolBundler(
      esbuildBinary = esbuild,
      inProcessSdkEntryOverride = resolveSdkAliasTarget(workspaceRoot),
    )

    val (summary, analysisDiagnostics, currentScriptedToolPaths) = try {
      GitRefTree.withRefTree(gitRoot, ref) { refRoot, resolvedSha ->
        val base = ScriptedToolSourceSnapshotScanner.snapshot(File(refRoot, relTrailmaps.path))
        val currentRaw = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)
        // Trailmaps staged into the workspace from ANOTHER repo's pinned clone are gitignored
        // here, so no ref checkout contains them — leaving them in would misreport every one of
        // their tools as "added" on every run.
        val ignored = GitRefTree.ignoredPaths(gitRoot, currentRaw.toolSources.values.map { it.script.absoluteFile })
        val excluded = ChangedToolAnalysis.excludeRefInvisible(currentRaw, ignored)
        val current = excluded.snapshot
        // LinkedHashSet: the fallback fires once per side with an identical message, and a
        // subprocess tool would otherwise repeat its warning on every run of both sides.
        val fingerprintDiagnostics = linkedSetOf<UsagesDiagnostic>()
        val result = ChangedToolAnalysis.compute(base, current) { source, toolName ->
          // Both strengths, every time. A tool the in-process bundler can't bundle (e.g. a
          // `runtime: subprocess` tool importing Node built-ins) still gets the weaker bytes
          // fingerprint, and the analysis compares like for like rather than treating a
          // one-sided bundling failure as a detected edit.
          val closureKey = runCatching { runBlocking { bundler.bundleOne(source.script, toolName) }.name }
            .onFailure { e ->
              fingerprintDiagnostics += UsagesDiagnostic(
                kind = UsagesDiagnostic.TOOL_BUNDLING_FAILED,
                subject = toolName,
                message = "$toolName: bundling failed (${e.message?.lineSequence()?.firstOrNull()}) — its import " +
                  "closure is unknown on that side, so edits to files it imports cannot flag it",
              )
            }
            .getOrNull()
          val bytesKey = runCatching { ChangedToolAnalysis.sha256(source.script.readBytes()) }.getOrNull()
          ToolFingerprint(
            closure = closureKey?.let { ChangedToolAnalysis.composeFingerprint(it, source.descriptor) },
            bytes = bytesKey?.let { ChangedToolAnalysis.composeFingerprint(it, source.descriptor) },
          )
        }
        // Tool→tool dispatch is invisible to the fingerprints above: `ctx.tools.other(...)` is a
        // runtime call through the host, not an import, so the callee never enters the closure the
        // content key covers. A tool implemented as a delegation to a changed tool therefore
        // fingerprints as untouched. Recover those edges from the bundles we already produced.
        val changedNames = (result.added + result.removed + result.modified).toSet()
        val callerEdges = ToolCallerEdgeScanner.scan(current, changedNames) { source, toolName ->
          runCatching { runBlocking { bundler.bundleOne(source.script, toolName) }.readText() }
        }
        val summary = ChangedSinceSummary(
          ref = ref,
          resolvedSha = resolvedSha,
          added = result.added,
          removed = result.removed,
          modified = result.modified,
          impactedViaCallers = ToolCallerEdgeScanner.impactedBy(changedNames, callerEdges.edges),
          // The other half of the comparison. Read from `gitRoot`, not the ref tree — the ref tree
          // is a detached checkout of `resolvedSha` and would trivially report itself.
          workingTree = GitRefTree.workingTreeStateOf(gitRoot),
        )
        // The CURRENT side's inventory, so `sourcePaths` names where each flagged tool lives now.
        // A `removed` tool is absent from it by definition and correctly reports no source.
        ChangedSinceOutcome(
          summary = summary,
          diagnostics = excluded.diagnostics + result.diagnostics + fingerprintDiagnostics +
            callerEdges.unscannableDiagnostics(),
          scriptedToolPaths = scriptedToolPathsOf(current),
        )
      }
    } catch (e: IOException) {
      return Outcome.Failure(TrailblazeExitCode.INFRA_FAILED, e.message ?: "git worktree materialization failed")
    }

    // Caller-impacted tools are QUERIED like any other: the point of deriving them is that their
    // trails replay. They stay distinguishable in the summary, where a consumer that wants only the
    // certain tiers can read `modified`/`added`/`removed` on their own.
    val changedTools = summary.modified + summary.added + summary.removed + summary.impactedViaCallers
    val report = buildReport(
      changedTools,
      primary,
      extras,
      ToolAttribution(
        changeKinds = changeKindsOf(summary),
        scriptedToolPaths = currentScriptedToolPaths,
        // Every name here was DERIVED from the inventory or the ref, so none of them can be a typo.
        flagNamesAbsentFromInventory = false,
      ),
    )
    // Rebuilt through `of` rather than `copy`, so `warnings` is re-derived from the combined
    // diagnostics. A `copy` that set one and not the other is exactly the drift `of` exists to
    // prevent — and this is the only place in the command where both lists are already populated.
    return Outcome.Report(
      ToolUsagesReport.of(
        trailsRoot = report.trailsRoot,
        scannedRoots = report.scannedRoots,
        tools = report.tools,
        diagnostics = analysisDiagnostics + report.diagnostics,
        changedSince = summary,
        generatedBy = report.generatedBy,
      ),
    )
  }

  /**
   * What esbuild aliases `@trailblaze/scripting` to while fingerprinting both sides of the
   * comparison. Null leaves the alias off, and each side then resolves the import on its own.
   *
   * The alias is what makes the REF side bundleable at all. A ref tree is a plain git checkout, so
   * it carries only committed files — and in a workspace that consumes the SDK as a framework
   * artifact, neither the SDK (`.trailblaze/sdk/`) nor the generated per-trailmap `tsconfig.json`
   * that points at it is committed. Without an alias esbuild has nothing to resolve the import to
   * there, every tool falls back to a bytes-only fingerprint, and the import-closure detection this
   * command exists for is dead. The working tree resolves the same import fine, so the failure is
   * one-sided: every tool reads as modified, on every run, against any ref.
   *
   * 1. The SDK source tree, when one is reachable (`TRAILBLAZE_SDK_DIR`, or a `sdks/typescript`
   *    ancestor of the cwd) — the slim in-process entry, so bundles stay KB-scale.
   * 2. The workspace's extracted SDK runtime bundle, which is exactly what this workspace's own
   *    tsconfig `paths` resolve to. Heavier, but it makes the two sides resolve IDENTICALLY, which
   *    is the property a comparison needs.
   * 3. The framework's own SDK, extracted from this JAR. Tier 2 requires a workspace some
   *    `trailblaze check` has already touched, and a FRESH worktree is not that — which is the
   *    state `scripts/validate-trailmap-tool-change.sh` runs this command in.
   */
  private fun resolveSdkAliasTarget(workspaceRoot: Path): File? =
    LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry()
      ?: workspaceSdkAliasFallback(workspaceRoot)
      ?: WorkspaceTypeScriptSetup.frameworkSdkRuntimeEntry(
        File(TrailblazeDesktopUtil.getDefaultAppDataDirectory(), TrailblazeDesktopUtil.SDK_CACHE_SUBDIR),
      )

  /**
   * Tier 2 of [resolveSdkAliasTarget], split out so a test can pin it without a reachable SDK
   * source tree deciding the answer first.
   *
   * Where `.trailblaze/` anchors is [CliPathUtils.workspaceGeneratedArtifactsRoot]'s rule to own —
   * it is what `CheckCommand` and `WorkspaceCompileBootstrap` hand to
   * [WorkspaceTypeScriptSetup.setUp]. Routing through it means a layout change cannot move the
   * extracted SDK out from under this alias.
   */
  internal fun workspaceSdkAliasFallback(workspaceRoot: Path): File? =
    WorkspaceTypeScriptSetup.extractedSdkRuntimeEntry(
      CliPathUtils.workspaceGeneratedArtifactsRoot(workspaceRoot).toFile(),
    )

  /**
   * What the `--changed-since` comparison produced, carried out of the ref-tree block together: the
   * summary, the diagnostics it accumulated, and the CURRENT side's scripted-tool inventory (which
   * only exists while the ref tree is materialized).
   */
  private data class ChangedSinceOutcome(
    val summary: ChangedSinceSummary,
    val diagnostics: List<UsagesDiagnostic>,
    val scriptedToolPaths: Map<String, List<String>>,
  )

  /**
   * Tool name → its [ToolUsageResult] change kind. The four lists are disjoint by construction —
   * added/removed/modified partition the tools present on either side, and `impactedViaCallers`
   * excludes all three — so no name gets two answers.
   */
  internal fun changeKindsOf(summary: ChangedSinceSummary): Map<String, String> = buildMap {
    summary.added.forEach { put(it, ToolUsageResult.ADDED) }
    summary.removed.forEach { put(it, ToolUsageResult.REMOVED) }
    summary.modified.forEach { put(it, ToolUsageResult.MODIFIED) }
    summary.impactedViaCallers.forEach { put(it, ToolUsageResult.IMPACTED_VIA_CALLERS) }
  }

  /**
   * The scripted-tool inventory for a plain (non-`--changed-since`) run, or [ToolAttribution.NONE]
   * when this trails root has no workspace above it. Every queried tool is [ToolUsageResult.NAMED]
   * here by definition — the caller named it.
   */
  private fun explicitModeAttribution(primary: File): ToolAttribution {
    val workspaceRoot = CliPathUtils.findWorkspaceRoot(primary.absoluteFile.toPath()) ?: return ToolAttribution.NONE
    val trailmapsDir = CliPathUtils.workspaceTrailmapsDir(workspaceRoot).toFile()
    if (!trailmapsDir.isDirectory) return ToolAttribution.NONE
    val snapshot = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)
    return ToolAttribution(
      changeKinds = emptyMap(),
      scriptedToolPaths = scriptedToolPathsOf(snapshot),
      // A partial inventory cannot support "this name is not a scripted tool" — the tool the scan
      // failed to read is absent from the map for that reason, and the hint would blame a typo.
      flagNamesAbsentFromInventory = snapshot.warnings.isEmpty(),
      // Same kind and same `current` subject `--changed-since` uses for its own side's inventory,
      // so a consumer matches one vocabulary regardless of which mode produced the report.
      diagnostics = snapshot.warnings.map { warning ->
        UsagesDiagnostic(
          kind = UsagesDiagnostic.TOOL_INVENTORY_INCOMPLETE,
          subject = ChangedToolAnalysis.SIDE_CURRENT,
          message = "${ChangedToolAnalysis.SIDE_CURRENT}: $warning",
        )
      },
    )
  }

  /**
   * Tool name → the script(s) declaring it. Grouped rather than associated because the inventory is
   * keyed per trailmap and two trailmaps may legally declare one name, which a single-valued map
   * would silently resolve to whichever came last.
   */
  private fun scriptedToolPathsOf(snapshot: ToolSourceSnapshot): Map<String, List<String>> =
    snapshot.toolSources.entries
      .groupBy { it.key.name }
      .mapValues { (_, entries) -> entries.map { it.value.script.absolutePath }.sorted() }

  private sealed interface Outcome {
    data class Report(val report: ToolUsagesReport) : Outcome
    data class Failure(val exitCode: TrailblazeExitCode, val message: String) : Outcome
  }

  /**
   * `internal` so a test can hand it a missing extra root. The CLI surface cannot produce that
   * state — explicit roots are validated before this runs — but the CONFIGURED roots this is also
   * called with come from Trail Runner's saved list and can name a directory that no longer exists,
   * and reaching that path through `call()` would mean reading the developer's real settings.
   */
  internal fun buildReport(queried: List<String>, primary: File, extras: List<File>): ToolUsagesReport =
    buildReport(queried, primary, extras, ToolAttribution.NONE)

  internal fun buildReport(
    queried: List<String>,
    primary: File,
    extras: List<File>,
    attribution: ToolAttribution,
  ): ToolUsagesReport {
    // First, because they are about the inventory the whole report is attributed against — a reader
    // needs "the inventory was partial" before any per-tool conclusion drawn from it.
    val diagnostics = attribution.diagnostics.toMutableList()
    val usagesByTool = LinkedHashMap<String, MutableList<TrailToolUsage>>()
    queried.forEach { usagesByTool[it] = mutableListOf() }

    // An extra root that no longer exists is skipped by the scanner without comment. Explicit roots
    // are validated before we get here, but configured ones (Trail Runner's saved extras) can name
    // a directory that has since been deleted or moved — and a root listed in `scannedRoots` that
    // was never read is a claim of coverage the report cannot back, which is the one thing
    // `scannedRoots` exists to prevent. Drop those, and say so where a consumer already looks.
    val (readableExtras, missingExtras) = extras.partition { it.isDirectory }
    missingExtras.forEach {
      diagnostics.add(
        UsagesDiagnostic(
          kind = UsagesDiagnostic.ROOT_UNSCANNED,
          subject = it.path,
          message = "${it.path}: configured trails root not scanned (no such directory)",
        ),
      )
    }

    val yaml = createTrailblazeYaml()
    for (entry in TrailIndexBuilder.scanAll(primary = primary, extras = readableExtras)) {
      val resolved = resolveTrailFile(entry.id.split("/"), primary, readableExtras) ?: continue
      val (root, file) = resolved
      val decoded = runCatching {
        (yaml.decodeTrailDocument(file.readText()) as TrailDocument.Unified).trail
      }
      val trail = decoded.getOrNull()
      if (trail == null) {
        val reason = decoded.exceptionOrNull()?.message?.lineSequence()?.firstOrNull() ?: "unreadable"
        diagnostics.add(
          UsagesDiagnostic(
            kind = UsagesDiagnostic.TRAIL_UNPARSEABLE,
            subject = file.path,
            message = "${file.path}: not scanned ($reason)",
          ),
        )
        continue
      }
      val stepUsages: Map<String, List<TrailStepToolUsage>> = TrailToolUsageScanner.toolUsages(trail)
      val relativePath = file.relativeToOrSelf(root).path
      val declaredDevices = UnifiedTrailTargets.declaredClassifiers(trail).toList()
      for (tool in queried) {
        val steps = stepUsages[tool] ?: continue
        usagesByTool.getValue(tool).add(
          TrailToolUsage(
            trail = relativePath.removeSuffix(".trail.yaml").removeSuffix(".yaml"),
            path = relativePath,
            root = root.absolutePath,
            title = trail.config.title,
            classifiers = steps.flatMap { it.classifiers }.distinct(),
            devices = declaredDevices,
            invokingDevices = TrailToolUsageScanner.invokingClassifiers(trail, steps).toList(),
            skip = trail.config.skip,
            steps = steps,
          ),
        )
      }
    }

    // Only when the inventory was actually READ, and read COMPLETELY. With no workspace above the
    // trails root there is nothing to be absent from; with a partial scan the absence is explained
    // by the scan itself (already reported above as `tool-inventory-incomplete`), and blaming a
    // typo would be the one answer that is definitely wrong.
    //
    // Restricted to names with ZERO usages, because that is the whole ambiguity this resolves: a
    // name a trail actually invokes has demonstrated it exists, so saying it is not a scripted tool
    // is true but useless noise (`tapOn` is a built-in). Zero usages is the reading a caller cannot
    // otherwise disambiguate — a real tool nothing invokes, or a name that never existed.
    attribution.scriptedToolPaths?.takeIf { attribution.flagNamesAbsentFromInventory }?.let { declared ->
      usagesByTool.filter { (tool, usages) -> usages.isEmpty() && tool !in declared }.keys.forEach { tool ->
        diagnostics.add(
          UsagesDiagnostic(
            kind = UsagesDiagnostic.TOOL_NOT_IN_SCRIPTED_INVENTORY,
            subject = tool,
            message = "$tool: no usages found, and not declared as a scripted tool in this " +
              "workspace's trailmaps. Built-in and class-backed tools are legitimately absent from " +
              "that inventory — but if you meant a scripted tool, check the spelling, because a " +
              "misspelled name reports zero usages exactly like a tool nothing invokes",
            // A HINT, so it stays out of `warnings`. Nothing here says the scan was incomplete, and
            // a fail-open gate reading `warnings` would otherwise trip on `usages tapOn` forever.
            severity = UsagesDiagnostic.HINT,
          ),
        )
      }
    }

    return ToolUsagesReport.of(
      generatedBy = TrailblazeVersion.displayVersion,
      trailsRoot = primary.absolutePath,
      scannedRoots = (listOf(primary) + readableExtras).map { it.absolutePath },
      tools = usagesByTool.map { (tool, usages) ->
        ToolUsageResult(
          tool = tool,
          usages = usages,
          changeKind = attribution.changeKinds[tool] ?: ToolUsageResult.NAMED,
          sourcePaths = attribution.scriptedToolPaths?.get(tool) ?: emptyList(),
        )
      },
      diagnostics = diagnostics,
    )
  }

  private fun printHumanReadable(report: ToolUsagesReport) {
    // Every root, not just the ones that produced a hit — otherwise the zero-usage answer, which is
    // the one a reader is most likely to distrust, gives no way to check what it looked at.
    val roots = report.scannedRoots.ifEmpty { listOf(report.trailsRoot) }
    Console.log(
      if (roots.size == 1) {
        "Scanned trails root: ${roots.single()}"
      } else {
        "Scanned ${roots.size} trails roots: ${roots.joinToString(", ")}"
      },
    )
    report.changedSince?.let { c ->
      Console.log(
        "Changed since ${c.ref} (${c.resolvedSha.take(12)}): " +
          "${c.modified.size} modified, ${c.added.size} added, ${c.removed.size} removed" +
          if (c.impactedViaCallers.isEmpty()) "" else ", ${c.impactedViaCallers.size} impacted via callers",
      )
      if (c.modified.isEmpty() && c.added.isEmpty() && c.removed.isEmpty()) {
        Console.log("No scripted tools changed — no usages to report.")
      }
      c.removed.forEach { Console.log("  removed: $it — its usages below are trails now BROKEN") }
      c.impactedViaCallers.forEach {
        Console.log("  impacted via caller: $it — unchanged itself, but it dispatches a changed tool")
      }
    }
    // Trail ids are per-root relative, so once more than one root was SCANNED an id no longer
    // identifies a file on its own — including when every hit happened to come from one of them.
    val multipleRoots = roots.size > 1
    for (result in report.tools) {
      Console.log("")
      // Named right on the tool's own line rather than only in the change summary above, which
      // scrolls off once more than a handful of tools flagged — and `removed` is the one kind a
      // reader must not miss, because its usages are trails that no longer resolve.
      val kind = if (result.changeKind == ToolUsageResult.NAMED) "" else " [${result.changeKind}]"
      if (result.usages.isEmpty()) {
        Console.log("${result.tool}$kind: no direct trail usages found")
        result.sourcePaths.forEach { Console.log("  declared in $it") }
        continue
      }
      Console.log("${result.tool}$kind: used by ${result.usages.size} trail(s)")
      // Where to go edit it — the question a reader running this before a change asks next.
      result.sourcePaths.forEach { Console.log("  declared in $it") }
      for (usage in result.usages) {
        val title = usage.title?.let { " — $it" }.orEmpty()
        val rootSuffix = if (multipleRoots) " [root: ${usage.root}]" else ""
        Console.log("  ${usage.trail} (${usage.classifiers.joinToString(", ")})$title$rootSuffix")
        // Only when some declared device does NOT reach the tool. That is the case a reader cannot
        // work out from the classifier keys on the line above — closest-wins resolution shadows an
        // `all:` invocation on any device whose step declares something more specific — and it is
        // the case that decides whether an iOS lane needs replaying. When every declared device
        // reaches it, saying so again would just repeat the line above.
        if (usage.invokingDevices.size < usage.devices.size) {
          Console.log(
            "    reaches ${usage.invokingDevices.joinToString(", ").ifEmpty { "no device" }} " +
              "— NOT ${(usage.devices - usage.invokingDevices.toSet()).joinToString(", ")}",
          )
        }
        for (step in usage.steps) {
          val label = step.stepIndex?.let { "step $it" } ?: "trailhead"
          Console.log("    $label [${step.classifiers.joinToString(", ")}] ${step.step}")
        }
        usage.skip?.let { skip ->
          Console.log("    skip: ${skip.entries.joinToString(", ") { "${it.key}: ${it.value}" }}")
        }
      }
    }
    if (report.warnings.isNotEmpty()) {
      Console.log("")
      // Not "file(s) could not be scanned": diagnostics cover unread roots and tools whose
      // comparison degraded — neither of which is an unscannable file, and calling them one sends a
      // reader looking for a broken trail that does not exist.
      Console.log("${report.warnings.size} caveat(s) on this report — it may be incomplete:")
      report.warnings.forEach { Console.log("  $it") }
    }
    // Printed separately from the caveats above, matching their split in the document: a hint is
    // about the QUERY, not about what the scan could not see, and folding it into a list headed
    // "may be incomplete" is what made a plain `usages tapOn` read as a degraded scan.
    val hints = report.diagnostics.filter { it.severity == UsagesDiagnostic.HINT }
    if (hints.isNotEmpty()) {
      Console.log("")
      Console.log("${hints.size} note(s) on the tools you asked for:")
      hints.forEach { Console.log("  ${it.message}") }
    }
  }

  companion object {
    // encodeDefaults so the contract fields a consumer keys on (`schemaVersion`, `warnings`)
    // are always present, not elided when they happen to hold their default.
    private val REPORT_JSON = Json {
      prettyPrint = true
      encodeDefaults = true
    }
  }
}
