package xyz.block.trailblaze.cli

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
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
import xyz.block.trailblaze.usages.ToolUsageResult
import xyz.block.trailblaze.usages.ToolUsagesReport
import xyz.block.trailblaze.usages.TrailStepToolUsage
import xyz.block.trailblaze.usages.TrailToolUsage
import xyz.block.trailblaze.usages.TrailToolUsageScanner
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.util.runJsonOutput
import xyz.block.trailblaze.util.runQuiet
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.TrailDocument
import java.io.File
import java.io.IOException
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

  private fun produceReport(ref: String?, queried: List<String>, primary: File, extras: List<File>): Outcome {
    if (ref == null) return Outcome.Report(buildReport(queried, primary, extras))

    val trailmapsDir = resolveTrailmapsDir(primary)
      ?: return Outcome.Failure(
        TrailblazeExitCode.MISUSE,
        "No trailmaps directory found walking up from ${primary.path} (looked for " +
          TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES
            .joinToString(" and ") { "<root>/$it/${TrailblazeConfigPaths.TRAILMAPS_SUBDIR}" } +
          "). --changed-since needs scripted-tool sources to compare.",
      )
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
      inProcessSdkEntryOverride = LazyYamlScriptedToolRegistration.resolveInProcessSdkEntry(),
    )

    val (summary, analysisWarnings) = try {
      GitRefTree.withRefTree(gitRoot, ref) { refRoot, resolvedSha ->
        val base = ScriptedToolSourceSnapshotScanner.snapshot(File(refRoot, relTrailmaps.path))
        val currentRaw = ScriptedToolSourceSnapshotScanner.snapshot(trailmapsDir)
        // Trailmaps staged into the workspace from ANOTHER repo's pinned clone are gitignored
        // here, so no ref checkout contains them — leaving them in would misreport every one of
        // their tools as "added" on every run.
        val ignored = GitRefTree.ignoredPaths(gitRoot, currentRaw.toolSources.values.map { it.script.absoluteFile })
        val current = ChangedToolAnalysis.excludeRefInvisible(currentRaw, ignored)
        // LinkedHashSet: the fallback fires once per side with an identical message, and a
        // subprocess tool would otherwise repeat its warning on every run of both sides.
        val fingerprintWarnings = linkedSetOf<String>()
        val result = ChangedToolAnalysis.compute(base, current) { source, toolName ->
          val scriptKey = runCatching { runBlocking { bundler.bundleOne(source.script, toolName) }.name }
            .recoverCatching { e ->
              // A tool the in-process bundler can't bundle (e.g. `runtime: subprocess` importing
              // Node built-ins) still gets a stable fingerprint — its raw script bytes — instead
              // of failing open into a false "modified" on every run. The tradeoff is honest:
              // imports aren't followed for it, and the warning says so.
              fingerprintWarnings +=
                "$toolName: bundling failed (${e.message?.lineSequence()?.firstOrNull()}); compared by " +
                  "script file bytes only — edits to files it imports will not flag it"
              ChangedToolAnalysis.sha256(source.script.readBytes())
            }
            .getOrNull() ?: return@compute null
          ChangedToolAnalysis.composeFingerprint(scriptKey, source.descriptor)
        }
        // Tool→tool dispatch is invisible to the fingerprints above: `ctx.tools.other(...)` is a
        // runtime call through the host, not an import, so the callee never enters the closure the
        // content key covers. A tool implemented as a delegation to a changed tool therefore
        // fingerprints as untouched. Recover those edges from the bundles we already produced.
        val changedNames = (result.added + result.removed + result.modified).toSet()
        val callerEdges = ToolCallerEdgeScanner.scan(current, changedNames) { source, toolName ->
          runCatching { runBlocking { bundler.bundleOne(source.script, toolName) }.readText() }
        }
        ChangedSinceSummary(
          ref = ref,
          resolvedSha = resolvedSha,
          added = result.added,
          removed = result.removed,
          modified = result.modified,
          impactedViaCallers = ToolCallerEdgeScanner.impactedBy(changedNames, callerEdges.edges),
        ) to result.warnings + fingerprintWarnings + callerEdges.unscannableWarnings()
      }
    } catch (e: IOException) {
      return Outcome.Failure(TrailblazeExitCode.INFRA_FAILED, e.message ?: "git worktree materialization failed")
    }

    // Caller-impacted tools are QUERIED like any other: the point of deriving them is that their
    // trails replay. They stay distinguishable in the summary, where a consumer that wants only the
    // certain tiers can read `modified`/`added`/`removed` on their own.
    val changedTools = summary.modified + summary.added + summary.removed + summary.impactedViaCallers
    val report = buildReport(changedTools, primary, extras)
    return Outcome.Report(
      report.copy(changedSince = summary, warnings = analysisWarnings + report.warnings),
    )
  }

  /**
   * The trailmaps directory owning this workspace's scripted-tool sources, found by walking up
   * from the trails root and probing both workspace config layouts at each level (standalone
   * `trailblaze-config/` wins over `trails/config/`, matching
   * [TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES] precedence). The walk matters
   * because the trails root need not be the workspace root — e.g. a repo whose trails live in
   * `legacy-trails/` with config at `<repo>/trailblaze-config/`.
   */
  private fun resolveTrailmapsDir(primary: File): File? =
    generateSequence(primary.absoluteFile) { it.parentFile }
      .flatMap { root ->
        TrailblazeConfigPaths.WORKSPACE_CONFIG_DIR_CANDIDATES.asSequence()
          .map { File(root, "$it/${TrailblazeConfigPaths.TRAILMAPS_SUBDIR}") }
      }
      .firstOrNull { it.isDirectory }

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
  internal fun buildReport(queried: List<String>, primary: File, extras: List<File>): ToolUsagesReport {
    val warnings = mutableListOf<String>()
    val usagesByTool = LinkedHashMap<String, MutableList<TrailToolUsage>>()
    queried.forEach { usagesByTool[it] = mutableListOf() }

    // An extra root that no longer exists is skipped by the scanner without comment. Explicit roots
    // are validated before we get here, but configured ones (Trail Runner's saved extras) can name
    // a directory that has since been deleted or moved — and a root listed in `scannedRoots` that
    // was never read is a claim of coverage the report cannot back, which is the one thing
    // `scannedRoots` exists to prevent. Drop those, and say so where a consumer already looks.
    val (readableExtras, missingExtras) = extras.partition { it.isDirectory }
    missingExtras.forEach {
      warnings.add("${it.path}: configured trails root not scanned (no such directory)")
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
        warnings.add("${file.path}: not scanned ($reason)")
        continue
      }
      val stepUsages: Map<String, List<TrailStepToolUsage>> = TrailToolUsageScanner.toolUsages(trail)
      val relativePath = file.relativeToOrSelf(root).path
      for (tool in queried) {
        val steps = stepUsages[tool] ?: continue
        usagesByTool.getValue(tool).add(
          TrailToolUsage(
            trail = relativePath.removeSuffix(".trail.yaml").removeSuffix(".yaml"),
            path = relativePath,
            root = root.absolutePath,
            title = trail.config.title,
            classifiers = steps.flatMap { it.classifiers }.distinct(),
            skip = trail.config.skip,
            steps = steps,
          ),
        )
      }
    }

    return ToolUsagesReport(
      trailsRoot = primary.absolutePath,
      scannedRoots = (listOf(primary) + readableExtras).map { it.absolutePath },
      tools = usagesByTool.map { (tool, usages) -> ToolUsageResult(tool = tool, usages = usages) },
      warnings = warnings,
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
      if (result.usages.isEmpty()) {
        Console.log("${result.tool}: no direct trail usages found")
        continue
      }
      Console.log("${result.tool}: used by ${result.usages.size} trail(s)")
      for (usage in result.usages) {
        val title = usage.title?.let { " — $it" }.orEmpty()
        val rootSuffix = if (multipleRoots) " [root: ${usage.root}]" else ""
        Console.log("  ${usage.trail} (${usage.classifiers.joinToString(", ")})$title$rootSuffix")
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
      Console.log("${report.warnings.size} file(s) could not be scanned — the report may be incomplete:")
      report.warnings.forEach { Console.log("  $it") }
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
