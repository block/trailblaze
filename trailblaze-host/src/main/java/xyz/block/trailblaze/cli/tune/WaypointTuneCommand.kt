package xyz.block.trailblaze.cli.tune

import xyz.block.trailblaze.cli.TrailblazeExitCode

import kotlinx.serialization.json.Json
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
import xyz.block.trailblaze.cli.loadSessions
import xyz.block.trailblaze.cli.resolveTargetTemplateContext
import xyz.block.trailblaze.cli.resolveWaypointRoot
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.WaypointLoader
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.measureTimeMillis

/**
 * `trailblaze waypoint tune` — analyze a session set for near-miss patterns and emit
 * one proposed YAML edit per pattern that meets the confidence bar.
 *
 * Local entry point for the `trailblaze-waypoints-tune` pipeline. Operates on a
 * directory of session logs (the same shape the measurement pipeline unpacks) and writes
 * one sidecar JSON + mutated YAML per surviving proposal to `--out-dir`. The pipeline
 * shell script then iterates the sidecar set and opens one PR per proposal via
 * `scripts/waypoints_refresh_pr_create.sh` (or its inlined stub).
 *
 * See `docs/internal/devlog/2026-05-19-waypoint-pack-refinement.md` for the design.
 */
@Command(
  name = "tune",
  mixinStandardHelpOptions = true,
  description = [
    "Analyze a session set for near-miss patterns and propose YAML edits.",
    "Each surviving proposal lands as a JSON+YAML pair under --out-dir, ready for the",
    "pipeline's auto-PR step to materialize as one PR per proposal.",
  ],
)
class WaypointTuneCommand : Callable<Int> {

  @Option(
    names = ["--sessions"],
    paramLabel = "<dir>",
    description = [
      "Directory containing one or more session subdirectories. Each subdirectory is " +
        "treated as a session; *_AgentDriverLog.json (or any screen-state log) inside " +
        "is treated as a step. Required.",
    ],
    required = true,
  )
  lateinit var sessionsDir: File

  @Option(
    names = ["--target"],
    paramLabel = "<id>",
    description = [
      "Trailmap id. Resolves --root to <workspace>/trailmaps/<id>/waypoints/. Also supplies the " +
        "trailmap's app_ids for templated selector expansion.",
    ],
  )
  var targetId: String? = null

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = ["Directory containing *.waypoint.yaml files to consider for tuning. Overrides --target. (Convention: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var rootOverride: File? = null

  @Option(
    names = ["--min-support"],
    paramLabel = "<n>",
    description = ["Minimum number of supporting sessions for a proposal to fire. Default: ${WaypointTuner.DEFAULT_MIN_SUPPORT}."],
  )
  var minSupport: Int = WaypointTuner.DEFAULT_MIN_SUPPORT

  @Option(
    names = ["--out-dir"],
    paramLabel = "<dir>",
    description = ["Output directory for proposal sidecars. Default: ./.waypoints_tune/proposals/. Wiped at the start of each run."],
  )
  var outDir: File = File(".waypoints_tune/proposals")

  @Option(
    names = ["--idempotence-check"],
    description = ["After the first pass, re-run the analyzer on the same session set with the proposals applied in-memory and fail if the second pass emits any proposal."],
  )
  var idempotenceCheck: Boolean = false

  override fun call(): Int {
    if (!sessionsDir.isDirectory) {
      Console.error("--sessions must be a directory: ${sessionsDir.absolutePath}")
      return TrailblazeExitCode.MISUSE.code
    }
    if (minSupport < 1) {
      Console.error("--min-support must be >= 1, got $minSupport")
      return TrailblazeExitCode.MISUSE.code
    }
    val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
    val sources = loadWaypointSources(root)
    if (sources.isEmpty()) {
      Console.error("No filesystem waypoints found under ${root.absolutePath}. Trailmap-only waypoints aren't tunable today; see devlog.")
      return TrailblazeExitCode.MISUSE.code
    }
    val target = when (val r = resolveTargetTemplateContext(targetId = targetId)) {
      is TargetContextResolution.Error -> {
        Console.error(r.message)
        return TrailblazeExitCode.MISUSE.code
      }
      is TargetContextResolution.Resolved -> r.context
      is TargetContextResolution.NoTarget -> null
    }

    val sessions = loadSessions(sessionsDir)
    if (sessions.isEmpty()) {
      Console.error("No screen-state logs found under --sessions ${sessionsDir.absolutePath}.")
      return TrailblazeExitCode.MISUSE.code
    }
    Console.log("Loaded ${sources.size} waypoint(s) and ${sessions.size} step(s) across ${sessions.map { it.sessionId }.distinct().size} session(s).")

    val matches = runMatcher(sources, sessions, target)
    val proposals = WaypointTuner.analyze(sources, matches, minSupport = minSupport)
    Console.log("Analyzer emitted ${proposals.size} candidate proposal(s) before collision guard.")

    // First pass: each proposal checked against the unmodified trailmap. Catches the common
    // case where a single loosen would overlap an existing sibling.
    val firstPassSafe = mutableListOf<WaypointTuner.Proposal>()
    val rejected = mutableListOf<WaypointSiblingCollisionGuard.Verdict>()
    val firstPassMs = measureTimeMillis {
      for (proposal in proposals) {
        val siblings = sources.map { it.definition }.filter { it.id != proposal.waypointId }
        val verdict = WaypointSiblingCollisionGuard.check(
          proposal = proposal,
          siblings = siblings,
          sessions = sessions,
          target = target,
        )
        if (verdict.safe) {
          firstPassSafe += proposal
        } else {
          rejected += verdict
          logReject(proposal, verdict)
        }
      }
    }
    Console.log(
      "Sibling-collision guard (single-proposal): ${firstPassSafe.size} kept, " +
        "${rejected.size} rejected in ${firstPassMs}ms (${sessions.size} step(s) × " +
        "${(sources.size - 1).coerceAtLeast(0)} sibling(s) × ${proposals.size} proposal(s)).",
    )

    // Second pass: cross-proposal composition. Two independently-safe loosenings on
    // different waypoints can mutually collide once both apply (proposal A's
    // definitionBefore was checked against B's definitionBefore — not B.definitionAfter).
    // Build the fully-mutated trailmap and re-check each proposal against the joint state.
    val safeProposals = secondPassCollisionFilter(
      firstPassSafe = firstPassSafe,
      sources = sources,
      sessions = sessions,
      target = target,
      rejected = rejected,
    )

    if (idempotenceCheck) {
      val secondPass = runIdempotenceCheck(sources, safeProposals, sessions, target)
      if (secondPass.isNotEmpty()) {
        xyz.block.trailblaze.cli.reportCliError(
          verb = "Idempotence check",
          reason = "a second pass with proposals applied still emitted ${secondPass.size} proposal(s)",
        )
        for (p in secondPass) Console.error("  ${p.waypointId} ${p.kind}")
        return TrailblazeExitCode.ASSERTION_FAILED.code
      }
      Console.log("Idempotence check passed.")
    }

    writeProposalSidecars(safeProposals, rejected)
    Console.log("Wrote ${safeProposals.size} proposal sidecar(s) to ${outDir.absolutePath}.")
    return TrailblazeExitCode.SUCCESS.code
  }

  private fun loadWaypointSources(root: File): List<WaypointTuner.WaypointSource> {
    val out = mutableListOf<WaypointTuner.WaypointSource>()
    for (file in WaypointLoader.discover(root)) {
      val def = try {
        WaypointLoader.loadFile(file)
      } catch (e: Exception) {
        Console.error("Skipping unloadable waypoint ${file.absolutePath}: ${e.message}")
        continue
      }
      out += WaypointTuner.WaypointSource(sourceFile = file, definition = def)
    }
    return out
  }

  // Session loading lives in WaypointCommandShared.loadSessions so the propose pipeline
  // sees identical semantics. Imported as a top-level function.

  private fun logReject(
    proposal: WaypointTuner.Proposal,
    verdict: WaypointSiblingCollisionGuard.Verdict,
  ) {
    val sample = verdict.collidingSteps
      .map { it.siblingWaypointId }
      .distinct()
      .take(3)
      .joinToString(", ")
    // Include the stable proposal key so a SRE triaging a rejection can grep last week's
    // open PRs / sidecar dumps without needing to parse JSON.
    Console.log(
      "  REJECT ${proposal.waypointId} ${proposal.kind} (key=${proposal.proposalKey}) — " +
        "collides with $sample on ${verdict.collidingSteps.size} step(s).",
    )
  }

  /**
   * After the per-proposal collision pass, two independently-safe loosenings on different
   * waypoints can still collide once both apply. Compose all surviving proposals into a
   * mutated trailmap and re-check each proposal against the joint state; drop any that newly
   * collide.
   *
   * Mutates [rejected] in place to surface dropped proposals to the sidecar writer.
   */
  private fun secondPassCollisionFilter(
    firstPassSafe: List<WaypointTuner.Proposal>,
    sources: List<WaypointTuner.WaypointSource>,
    sessions: List<WaypointSiblingCollisionGuard.SessionStep>,
    target: TargetTemplateContext?,
    rejected: MutableList<WaypointSiblingCollisionGuard.Verdict>,
  ): List<WaypointTuner.Proposal> {
    if (firstPassSafe.size <= 1) return firstPassSafe
    // Build the fully-mutated trailmap via the shared compose helper — same primitive the
    // idempotence check uses, so the joint-state semantics stay in lock-step.
    val mutatedById = WaypointTuner.composeMutatedTrailmap(firstPassSafe)
    val mutatedTrailmap = sources.map { src ->
      mutatedById[src.definition.id]?.let { mutated -> src.copy(definition = mutated) } ?: src
    }
    val survived = mutableListOf<WaypointTuner.Proposal>()
    val secondPassMs = measureTimeMillis {
      for (proposal in firstPassSafe) {
        val siblings = mutatedTrailmap.map { it.definition }.filter { it.id != proposal.waypointId }
        val verdict = WaypointSiblingCollisionGuard.check(
          proposal = proposal,
          siblings = siblings,
          sessions = sessions,
          target = target,
        )
        if (verdict.safe) {
          survived += proposal
        } else {
          rejected += verdict
          Console.log(
            "  REJECT (composition) ${proposal.waypointId} ${proposal.kind} " +
              "(key=${proposal.proposalKey}) — collides only under the fully-mutated trailmap.",
          )
        }
      }
    }
    Console.log(
      "Sibling-collision guard (cross-proposal): ${survived.size} kept, " +
        "${firstPassSafe.size - survived.size} rejected in ${secondPassMs}ms.",
    )
    return survived
  }

  private fun runMatcher(
    sources: List<WaypointTuner.WaypointSource>,
    sessions: List<WaypointSiblingCollisionGuard.SessionStep>,
    target: TargetTemplateContext?,
  ): List<WaypointTuner.StepMatch> {
    val out = mutableListOf<WaypointTuner.StepMatch>()
    for (step in sessions) {
      for (source in sources) {
        val result = WaypointMatcher.match(source.definition, step.screen, target)
        out += WaypointTuner.StepMatch(
          sessionId = step.sessionId,
          stepId = step.stepId,
          waypointId = source.definition.id,
          result = result,
        )
      }
    }
    return out
  }

  /**
   * Runs the analyzer a second time with the safe proposals applied to their respective
   * waypoints. Idempotence requires zero output here — any proposal that fires twice on
   * the same data is a bug (e.g. lowering minCount but the counter still trips).
   */
  private fun runIdempotenceCheck(
    sources: List<WaypointTuner.WaypointSource>,
    proposals: List<WaypointTuner.Proposal>,
    sessions: List<WaypointSiblingCollisionGuard.SessionStep>,
    target: TargetTemplateContext?,
  ): List<WaypointTuner.Proposal> {
    if (proposals.isEmpty()) return emptyList()
    // Compose ALL proposals per waypoint onto the same definition via the shared helper.
    // The previous `associateBy { waypointId }` form silently kept only the last proposal
    // per waypoint, which made multi-edit corpora look like idempotence failures.
    val mutatedById = WaypointTuner.composeMutatedTrailmap(proposals)
    val mutatedSources = sources.map { src ->
      mutatedById[src.definition.id]?.let { mutated -> src.copy(definition = mutated) } ?: src
    }
    val mutatedMatches = runMatcher(mutatedSources, sessions, target)
    return WaypointTuner.analyze(mutatedSources, mutatedMatches, minSupport = minSupport)
  }

  private fun writeProposalSidecars(
    proposals: List<WaypointTuner.Proposal>,
    rejected: List<WaypointSiblingCollisionGuard.Verdict>,
  ) {
    if (outDir.exists()) outDir.deleteRecursively()
    outDir.mkdirs()
    // Reuse the loader's Kaml instance so the round-trip (load → mutate → write → load
    // on the next pipeline run) is guaranteed to hit the same config.
    val yaml = WaypointLoader.yaml
    val json = Json { prettyPrint = true }
    for ((i, p) in proposals.withIndex()) {
      val dir = File(outDir, "%04d-%s-%s".format(i, p.waypointId.replace('/', '_'), p.kind.name.lowercase()))
      dir.mkdirs()
      val mutatedYaml = yaml.encodeToString(WaypointDefinition.serializer(), p.definitionAfter)
      File(dir, "mutated.waypoint.yaml").writeText(mutatedYaml)
      val sidecar = buildJsonObject {
        put("waypointId", p.waypointId)
        put("kind", p.kind.name)
        put("proposalKey", p.proposalKey)
        put("rationale", p.rationale)
        put("supportSessions", p.supportSessions)
        put("supportSteps", p.supportSteps)
        put("sourceFile", p.sourceFile.absolutePath)
      }
      File(dir, "proposal.json").writeText(json.encodeToString(JsonObject.serializer(), sidecar))
    }
    if (rejected.isNotEmpty()) {
      val rejectedArray = kotlinx.serialization.json.JsonArray(
        rejected.map { v ->
          buildJsonObject {
            put("waypointId", v.proposal.waypointId)
            put("kind", v.proposal.kind.name)
            put("collidingSteps", JsonPrimitive(v.collidingSteps.size))
            put(
              "sampleSiblings",
              JsonPrimitive(
                v.collidingSteps.map { it.siblingWaypointId }.distinct().take(5).joinToString(","),
              ),
            )
          }
        },
      )
      File(outDir, "rejected.json").writeText(
        json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), rejectedArray),
      )
    }
  }
}
