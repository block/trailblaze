package xyz.block.trailblaze.cli.shortcut

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.api.TargetTemplateContext
import xyz.block.trailblaze.api.waypoint.WaypointDefinition
import xyz.block.trailblaze.cli.DEFAULT_WAYPOINT_ROOT
import xyz.block.trailblaze.cli.TargetContextResolution
import xyz.block.trailblaze.cli.WaypointDiscovery
import xyz.block.trailblaze.cli.reportLoadFailures
import xyz.block.trailblaze.cli.resolveTargetTemplateContext
import xyz.block.trailblaze.cli.resolveWaypointRoot
import xyz.block.trailblaze.config.ToolYamlConfig
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointLoader
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.measureTimeMillis

/**
 * `trailblaze waypoint shortcut propose` — walk a session set for observed (A->B)
 * waypoint transitions and emit one shortcut-proposal sidecar per surviving (A->B)
 * pair. Analysis-only; the `trailblaze-waypoints-shortcut` pipeline pairs each
 * surviving proposal with a `waypoint shortcut verify` run before opening a PR.
 *
 * Design: `docs/internal/devlog/2026-05-19-waypoint-pack-shortcuts.md`.
 */
@Command(
  name = "propose",
  mixinStandardHelpOptions = true,
  description = [
    "Analyze a session set for (A->B) transitions and emit draft shortcut YAMLs.",
    "Each surviving proposal lands as a JSON+YAML pair under --out-dir, ready for",
    "the pipeline's verify + auto-PR steps.",
  ],
)
class WaypointShortcutProposeCommand : Callable<Int> {

  @Option(
    names = ["--sessions"],
    paramLabel = "<dir>",
    description = [
      "Directory containing one or more session subdirectories with " +
        "*_AgentDriverLog.json files. Each subdirectory is one session.",
    ],
    required = true,
  )
  lateinit var sessionsDir: File

  @Option(
    names = ["--target"],
    paramLabel = "<id>",
    description = [
      "Pack id. Resolves --root to <workspace>/packs/<id>/waypoints/ and supplies the " +
        "pack's app_ids for templated selector expansion.",
    ],
    required = true,
  )
  lateinit var targetId: String

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = ["Override the waypoint-root dir for waypoint + existing-shortcut discovery. (Convention: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var rootOverride: File? = null

  @Option(
    names = ["--min-support"],
    paramLabel = "<n>",
    description = ["Minimum distinct sessions for a transition to be proposed. Default: ${ShortcutProposer.DEFAULT_MIN_SUPPORT}."],
  )
  var minSupport: Int = ShortcutProposer.DEFAULT_MIN_SUPPORT

  @Option(
    names = ["--fingerprint-agreement"],
    paramLabel = "<ratio>",
    description = [
      "Fraction of supporting sessions that must share the dominant action fingerprint",
      "(default: 0.67). Sessions disagreeing on the procedure short-circuit the proposal.",
    ],
  )
  var fingerprintAgreement: Double = ShortcutProposer.DEFAULT_FINGERPRINT_AGREEMENT

  @Option(
    names = ["--top-k"],
    paramLabel = "<n>",
    description = [
      "Process at most the top-K surviving proposals by hit count. The replay step",
      "is expensive enough that v1 caps weekly throughput here. Default: 5.",
    ],
  )
  var topK: Int = 5

  @Option(
    names = ["--out-dir"],
    paramLabel = "<dir>",
    description = ["Output directory for proposal sidecars. Default: ./.waypoints_shortcut/proposals/. Wiped at the start of each run."],
  )
  var outDir: File = File(".waypoints_shortcut/proposals")

  @Option(
    names = ["--idempotence-check"],
    description = ["Re-run after applying the surviving proposals in-memory; fail (exit 1) if any new proposal surfaces."],
  )
  var idempotenceCheck: Boolean = false

  override fun call(): Int {
    if (!sessionsDir.isDirectory) {
      Console.error("--sessions must be a directory: ${sessionsDir.absolutePath}")
      return CommandLine.ExitCode.USAGE
    }
    if (minSupport < 1) {
      Console.error("--min-support must be >= 1, got $minSupport")
      return CommandLine.ExitCode.USAGE
    }
    if (fingerprintAgreement !in 0.0..1.0) {
      Console.error("--fingerprint-agreement must be in [0.0, 1.0], got $fingerprintAgreement")
      return CommandLine.ExitCode.USAGE
    }
    if (topK < 1) {
      Console.error("--top-k must be >= 1, got $topK")
      return CommandLine.ExitCode.USAGE
    }

    val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
    val waypoints = loadWaypoints(root)
    if (waypoints.isEmpty()) {
      // Without this guard, every step labels `null` and `analyze` emits zero
      // proposals — indistinguishable in the build log from "no transitions met
      // support." Fail loud so an SRE can tell waypoint discovery failed vs.
      // legitimately produced nothing.
      Console.error(
        "No waypoints found under ${root.absolutePath}. " +
          "Check --target / --root resolution; a pack-load failure would silently produce zero proposals.",
      )
      return CommandLine.ExitCode.USAGE
    }
    val target = when (val r = resolveTargetTemplateContext(targetId = targetId)) {
      is TargetContextResolution.Error -> {
        Console.error(r.message)
        return CommandLine.ExitCode.USAGE
      }
      is TargetContextResolution.Resolved -> r.context
      is TargetContextResolution.NoTarget -> null
    }

    val sessionsLoadStartMs = System.currentTimeMillis()
    val sessions = loadSessions(sessionsDir)
    val sessionsLoadMs = System.currentTimeMillis() - sessionsLoadStartMs
    if (sessions.isEmpty()) {
      Console.error("No session steps found under --sessions ${sessionsDir.absolutePath}.")
      return CommandLine.ExitCode.USAGE
    }
    val totalSteps = sessions.sumOf { it.size }
    Console.log(
      "Loaded ${waypoints.size} waypoint(s) and $totalSteps step(s) across " +
        "${sessions.size} session(s) in ${sessionsLoadMs}ms.",
    )

    val packRoot = resolvePackRoot(root = root, rootOverride = rootOverride)
    val existingShortcuts = loadExistingShortcuts(packRoot)
    Console.log("Found ${existingShortcuts.size} existing shortcut(s) under ${packRoot.absolutePath}.")

    val analysis: ShortcutProposer.Analysis
    val analyzeMs = measureTimeMillis {
      analysis = ShortcutProposer.analyze(
        sessions = sessions,
        waypoints = waypoints,
        target = target,
        minSupport = minSupport,
        fingerprintAgreement = fingerprintAgreement,
      )
    }
    Console.log(
      "Analyzer surfaced ${analysis.proposals.size} candidate proposal(s) and " +
        "${analysis.skipped.size} pre-guard skip(s) in ${analyzeMs}ms " +
        "($totalSteps step(s) × ${waypoints.size} waypoint(s)).",
    )

    val verdict = ShortcutSiblingCollisionGuard.check(
      proposals = analysis.proposals,
      existingShortcuts = existingShortcuts,
    )
    Console.log(
      "Sibling-collision guard: ${verdict.survived.size} kept, " +
        "${verdict.rejections.size} rejected.",
    )

    val survivors = verdict.survived.take(topK)
    val deferred = verdict.survived.drop(topK)
    if (deferred.isNotEmpty()) {
      Console.log(
        "Deferring ${deferred.size} lower-support proposal(s) beyond --top-k=$topK; " +
          "they'll resurface in a subsequent run as the top ones land.",
      )
    }

    if (idempotenceCheck) {
      // Idempotence semantics: with ALL guard-surviving proposals (top-K survivors PLUS
      // the deferred tail) treated as if already applied, re-running the proposer must
      // emit zero new proposals.
      //
      // Earlier the augmented set was just `survivors` — the top-K. That meant any
      // analysis whose surviving proposal count exceeded `topK` would fail
      // `--idempotence-check` even when the analyzer was perfectly stable, because
      // the deferred proposals would reappear in the second pass. Since the bootstrap
      // always passes `--idempotence-check`, that turned normal top-K overflow into a
      // hard pipeline failure. Including deferred in the augmented set is the right
      // fix: the question we're asking ("is my synthesizer stable on its own output?")
      // doesn't care about the top-K throttle — it's about whether the proposals
      // collectively cover their inputs.
      val augmented = existingShortcuts + verdict.survived.map {
        ShortcutSiblingCollisionGuard.ExistingShortcut(
          from = it.fromWaypointId,
          to = it.toWaypointId,
          variant = null,
        )
      }
      // `ShortcutProposer.analyze` is documented (and tested via
      // `analyze yields stable proposal ordering across runs`) to be pure on its inputs
      // — so a second call with identical args is guaranteed to yield the same
      // `Analysis` we already have. Reuse `analysis.proposals` and only re-run the
      // guard against the augmented existing-shortcut set; that's the thing the check
      // actually exercises. Saves one whole analyzer walk per invocation and stops the
      // logs from suggesting we're checking analyzer stability when the question is
      // really "does the guard drop everything now that the survivors are 'applied'?"
      val secondVerdict = ShortcutSiblingCollisionGuard.check(
        proposals = analysis.proposals,
        existingShortcuts = augmented,
      )
      if (secondVerdict.survived.isNotEmpty()) {
        Console.error(
          "Idempotence check FAILED: second pass with all guard-surviving proposals " +
            "applied still emitted ${secondVerdict.survived.size} new proposal(s):",
        )
        for (p in secondVerdict.survived) {
          Console.error("  ${p.fromWaypointId} -> ${p.toWaypointId} (key=${p.proposalKey})")
        }
        return 1
      }
      Console.log("Idempotence check passed.")
    }

    writeSidecars(survivors, deferred, verdict.rejections, analysis.skipped)
    Console.log("Wrote ${survivors.size} proposal sidecar(s) to ${outDir.absolutePath}.")
    return CommandLine.ExitCode.OK
  }

  private fun loadWaypoints(root: File): List<WaypointDefinition> {
    val discovery = WaypointDiscovery.discover(root)
    reportLoadFailures(discovery.rootFailures)
    return discovery.definitions
  }

  /**
   * Resolves the pack root used to discover existing `*.shortcut.yaml` files.
   *
   * When the user passed `--target X` (i.e. [rootOverride] is null), [root] is the
   * waypoints subdirectory `<workspace>/packs/X/waypoints/`; shortcut files live under
   * sibling `shortcuts/`, so we need to climb to the pack root (the parent) to find
   * them. When `--root` was passed explicitly, treat that path as authoritative — the
   * user said "look here," and climbing to the parent would silently scan a different
   * directory than the one they pointed at. The pre-PR-#3131 code scanned only [root]
   * for the target-resolved case too, which silently missed authored shortcuts and let
   * the proposer re-propose already-merged shortcuts every week.
   *
   * Visible as `internal` so the focused test in WaypointShortcutProposeCommandTest can
   * pin the branch matrix (target-resolved climbs vs. explicit override stays put).
   */
  internal fun resolvePackRoot(root: File, rootOverride: File?): File =
    if (
      rootOverride == null &&
      root.name == "waypoints" &&
      root.parentFile?.isDirectory == true
    ) {
      root.parentFile
    } else {
      root
    }

  /**
   * Walks `<root>` for `*.shortcut.yaml` files via the shared loader's kaml instance.
   * One filesystem-walk pass; failures are logged and skipped per the lenient policy
   * the propose pipeline already uses for waypoints.
   *
   * Classpath-bundled shortcuts (framework `clock`/`contacts`) are not enumerated
   * here — v1 only deduplicates against on-disk pack shortcuts, which is the surface
   * a new shortcut PR would conflict with anyway. Classpath duplicates would surface
   * as runtime contextual-filter conflicts and the reviewer can spot them.
   */
  internal fun loadExistingShortcuts(root: File): List<ShortcutSiblingCollisionGuard.ExistingShortcut> {
    if (!root.isDirectory) return emptyList()
    val out = mutableListOf<ShortcutSiblingCollisionGuard.ExistingShortcut>()
    for (file in root.walkTopDown().filter { it.isFile && it.name.endsWith(".shortcut.yaml") }) {
      try {
        val cfg = WaypointLoader.yaml.decodeFromString(ToolYamlConfig.serializer(), file.readText())
        cfg.shortcut?.let { sc ->
          out += ShortcutSiblingCollisionGuard.ExistingShortcut(
            from = sc.from,
            to = sc.to,
            variant = sc.variant,
          )
        }
      } catch (e: Exception) {
        Console.error("Skipping unloadable shortcut ${file.absolutePath}: ${e.message}")
      }
    }
    return out
  }

  /**
   * Walks `[root]` for every session directory (a directory containing one or more
   * `*_AgentDriverLog.json` files), and returns one chronologically-ordered list of
   * [ShortcutProposer.SessionStepWithAction] per session.
   *
   * Deliberately diverges from [xyz.block.trailblaze.cli.loadSessions] (the shared
   * helper that tune + propose use): the shared helper loads all three screen-state
   * log suffixes (`_AgentDriverLog`, `_TrailblazeSnapshotLog`,
   * `_TrailblazeLlmRequestLog`) so the matcher sees every captured screen. Shortcuts
   * are different — we need the per-step `AgentDriverAction`, which only the
   * `_AgentDriverLog` files carry. Pulling in the other two suffixes would give us
   * screens with no transition signal, weakening every fingerprint we compute and
   * producing spurious "in-between" labels that confuse the (A->B) walk.
   *
   * If a future automation lift wants its own log-type filter, the cleaner answer is
   * extending the shared helper with an optional `suffixes` parameter; for now the
   * cost of one local function is lower than the cost of touching every caller of
   * the shared helper.
   */
  internal fun loadSessions(root: File): List<List<ShortcutProposer.SessionStepWithAction>> {
    val sessionDirs = root.walkTopDown()
      .filter { it.isFile && it.name.endsWith("_AgentDriverLog.json") }
      .mapNotNull { it.parentFile }
      .distinct()
      .toList()
    val out = mutableListOf<List<ShortcutProposer.SessionStepWithAction>>()
    var totalSteps = 0
    var skippedSteps = 0
    for (sessionDir in sessionDirs) {
      val sessionId = sessionDir.name
      val steps = sessionDir.listFiles { f -> f.name.endsWith("_AgentDriverLog.json") }
        ?.sortedWith(compareBy({ SessionLogScreenState.readTimestamp(it) ?: "" }, { it.name }))
        ?: continue
      val loaded = mutableListOf<ShortcutProposer.SessionStepWithAction>()
      var sessionSkipped = 0
      steps.forEachIndexed { index, logFile ->
        totalSteps += 1
        val screen = try {
          SessionLogScreenState.loadStep(logFile)
        } catch (e: Exception) {
          Console.error("Skipping unloadable step ${logFile.name}: ${e.message}")
          skippedSteps += 1
          sessionSkipped += 1
          return@forEachIndexed
        }
        val action = AgentDriverActionLoader.load(logFile)
        loaded += ShortcutProposer.SessionStepWithAction(
          sessionId = sessionId,
          stepIndex = index,
          stepId = logFile.relativeToOrSelf(root).path,
          screen = screen,
          action = action,
        )
      }
      // Per-session warning when skip rate is unusually high — a burst of unparseable
      // logs in one session corrupts that session's transition signal, which can shift
      // edge-support enough to mis-route proposals across the agreement floor.
      if (sessionSkipped > 0 && steps.isNotEmpty() && sessionSkipped.toDouble() / steps.size >= SESSION_SKIP_WARN_THRESHOLD) {
        Console.error(
          "WARNING: session $sessionId had $sessionSkipped/${steps.size} unloadable step(s) " +
            "(>= ${(SESSION_SKIP_WARN_THRESHOLD * 100).toInt()}%); confidence floor degraded for this session.",
        )
      }
      if (loaded.isNotEmpty()) out += loaded
    }
    if (skippedSteps > 0) {
      val rate = if (totalSteps > 0) skippedSteps.toDouble() / totalSteps else 0.0
      val level: (String) -> Unit = if (rate >= GLOBAL_SKIP_WARN_THRESHOLD) Console::error else Console::log
      level(
        "Session loader skipped $skippedSteps/$totalSteps step(s) (${"%.1f".format(rate * 100)}%) " +
          "due to load failures.",
      )
    }
    return out
  }

  private companion object {
    /** Per-session skip-rate threshold above which we emit an explicit warning. */
    private const val SESSION_SKIP_WARN_THRESHOLD: Double = 0.10

    /** Global skip-rate threshold above which the cross-session summary escalates to error. */
    private const val GLOBAL_SKIP_WARN_THRESHOLD: Double = 0.05
  }

  /**
   * Per-proposal sidecar: `proposal.json` + `draft.shortcut.yaml`. Plus an aggregate
   * `rejected.json` (sibling-collision + pre-guard skips) and `deferred.json`
   * (proposals below the top-K cap) for diagnostic uploading.
   */
  internal fun writeSidecars(
    survivors: List<ShortcutProposer.Proposal>,
    deferred: List<ShortcutProposer.Proposal>,
    rejections: List<ShortcutSiblingCollisionGuard.Rejection>,
    skipped: List<ShortcutProposer.Skipped>,
  ) {
    if (outDir.exists()) outDir.deleteRecursively()
    outDir.mkdirs()
    val json = Json { prettyPrint = true }

    // Per-proposal emit failures (e.g. ShortcutYamlEmitter.requireSelectorIsEmittable on
    // an unexpected non-android matcher) MUST NOT abort the whole run — earlier sidecars
    // would be lost and the bootstrap would see zero proposals despite legitimate
    // survivors. Track per-proposal exceptions and surface them via the same `rejected.json`
    // path used by the analyzer/guard, then continue.
    val emitFailures = mutableListOf<Pair<ShortcutProposer.Proposal, Throwable>>()
    for ((i, p) in survivors.withIndex()) {
      try {
        val id = ShortcutProposer.generateShortcutId(p.fromWaypointId, p.toWaypointId)
        val slug = id.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val dir = File(outDir, "%04d-%s".format(i, slug))
        dir.mkdirs()
        val description = describeShortcut(p)
        val yaml = ShortcutYamlEmitter.emit(
          shortcutId = id,
          fromWaypointId = p.fromWaypointId,
          toWaypointId = p.toWaypointId,
          description = description,
          body = p.toolBody,
        )
        File(dir, "draft.shortcut.yaml").writeText(yaml)
        val sidecar = buildJsonObject {
          put("kind", "NEW_SHORTCUT")
          put("shortcutId", id)
          put("from", p.fromWaypointId)
          put("to", p.toWaypointId)
          put("proposalKey", p.proposalKey)
          put("actionFingerprint", p.actionFingerprint)
          put("rationale", p.rationale)
          put("supportSessions", p.supportSessions)
          put("supportSteps", p.supportSteps)
          put("toolName", p.toolBody.toolName)
          put("target", targetId)
        }
        File(dir, "proposal.json").writeText(json.encodeToString(JsonObject.serializer(), sidecar))
      } catch (e: Exception) {
        Console.error(
          "Failed to write sidecar for ${p.fromWaypointId} -> ${p.toWaypointId} " +
            "(key=${p.proposalKey}): ${e.message}. Continuing with remaining proposals.",
        )
        emitFailures += p to e
      }
    }

    if (rejections.isNotEmpty() || skipped.isNotEmpty() || emitFailures.isNotEmpty()) {
      val arr = JsonArray(
        rejections.map { r ->
          buildJsonObject {
            put("kind", "REJECTED_BY_GUARD")
            put("from", r.proposal.fromWaypointId)
            put("to", r.proposal.toWaypointId)
            put("proposalKey", r.proposal.proposalKey)
            put("reason", r.reason)
          }
        } + skipped.map { s ->
          buildJsonObject {
            put("kind", "SKIPPED_BY_ANALYZER")
            put("from", s.fromWaypointId)
            put("to", s.toWaypointId)
            put("reason", s.reason)
            put("observedSessions", JsonPrimitive(s.observedSessions))
          }
        } + emitFailures.map { (p, e) ->
          buildJsonObject {
            put("kind", "EMIT_FAILED")
            put("from", p.fromWaypointId)
            put("to", p.toWaypointId)
            put("proposalKey", p.proposalKey)
            put("reason", "sidecar emit failed: ${e.message}")
          }
        },
      )
      File(outDir, "rejected.json").writeText(json.encodeToString(JsonArray.serializer(), arr))
    }

    if (deferred.isNotEmpty()) {
      val arr = JsonArray(
        deferred.map { p ->
          buildJsonObject {
            put("from", p.fromWaypointId)
            put("to", p.toWaypointId)
            put("proposalKey", p.proposalKey)
            put("supportSessions", p.supportSessions)
            put("reason", "below --top-k cap; will resurface in a subsequent run")
          }
        },
      )
      File(outDir, "deferred.json").writeText(json.encodeToString(JsonArray.serializer(), arr))
    }
  }

  private fun describeShortcut(p: ShortcutProposer.Proposal): String {
    val fromSegment = p.fromWaypointId.split('/').last()
    val toSegment = p.toWaypointId.split('/').last()
    return "AUTO-PROPOSED. From $fromSegment to $toSegment via ${p.toolBody.toolName}."
  }
}
