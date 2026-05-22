package xyz.block.trailblaze.cli.propose

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
import xyz.block.trailblaze.cli.loadSessions
import xyz.block.trailblaze.cli.reportLoadFailures
import xyz.block.trailblaze.cli.resolveTargetTemplateContext
import xyz.block.trailblaze.cli.resolveWaypointRoot
import xyz.block.trailblaze.cli.tune.WaypointSiblingCollisionGuard
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointLoader
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.io.File
import java.util.concurrent.Callable
import kotlin.system.measureTimeMillis

/**
 * `trailblaze waypoint propose` — synthesize draft waypoint YAMLs from
 * `unmatched-clusters.jsonl` and emit one sidecar per surviving proposal for the auto-PR
 * step in the `trailblaze-waypoints-propose` pipeline.
 *
 * Two input modes:
 *  - `--cluster <jsonl-string>` — ad-hoc, one-cluster invocation. Useful for an author
 *    investigating a specific unmatched cluster by hand.
 *  - `--aggregate <path>` — pipeline mode. Reads the JSONL file and processes the
 *    top-N clusters by hit count.
 *
 * See `docs/internal/devlog/2026-05-19-waypoint-pack-detection.md` for the design.
 */
@Command(
  name = "propose",
  mixinStandardHelpOptions = true,
  description = [
    "Synthesize draft waypoint YAMLs from unmatched-cluster fingerprints.",
    "Emits one proposal sidecar per surviving cluster to --out-dir; the",
    "`trailblaze-waypoints-propose` pipeline picks them up and opens one PR per proposal.",
  ],
)
class WaypointProposeCommand : Callable<Int> {

  @Option(
    names = ["--cluster"],
    paramLabel = "<jsonl-string>",
    description = ["Single JSONL cluster line. Mutually exclusive with --aggregate."],
  )
  var clusterArg: String? = null

  @Option(
    names = ["--aggregate"],
    paramLabel = "<path>",
    description = ["Path to unmatched-clusters.jsonl. Pipeline mode. Mutually exclusive with --cluster."],
  )
  var aggregateFile: File? = null

  @Option(
    names = ["--sessions"],
    paramLabel = "<dir>",
    description = [
      "Directory containing the session logs used by the source build. Each cluster's " +
        "`example_log` path is resolved relative to this dir, and the cross-waypoint bleed " +
        "guard walks the full session set.",
    ],
    required = true,
  )
  lateinit var sessionsDir: File

  @Option(
    names = ["--target"],
    paramLabel = "<id>",
    description = ["Pack id. Resolves --root + provides the proposal namespace (`<target>/auto-<slug>`)."],
    required = true,
  )
  lateinit var targetId: String

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = ["Override the waypoint-root dir for sibling-overlap checks. (Convention: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var rootOverride: File? = null

  @Option(
    names = ["--top-n"],
    paramLabel = "<n>",
    description = ["Maximum number of clusters to process in --aggregate mode (default: 10)."],
  )
  var topN: Int = 10

  @Option(
    names = ["--out-dir"],
    paramLabel = "<dir>",
    description = ["Output directory for proposal sidecars. Default: ./.waypoints_propose/proposals/. Wiped at the start of each run."],
  )
  var outDir: File = File(".waypoints_propose/proposals")

  @Option(
    names = ["--idempotence-check"],
    description = ["Re-run after applying the proposals in-memory; fail (exit 1) if the second pass emits any new proposal."],
  )
  var idempotenceCheck: Boolean = false

  override fun call(): Int {
    if ((clusterArg == null) == (aggregateFile == null)) {
      Console.error("Pass exactly one of --cluster or --aggregate.")
      return CommandLine.ExitCode.USAGE
    }
    if (!sessionsDir.isDirectory) {
      Console.error("--sessions must be a directory: ${sessionsDir.absolutePath}")
      return CommandLine.ExitCode.USAGE
    }
    if (topN < 1) {
      Console.error("--top-n must be >= 1, got $topN")
      return CommandLine.ExitCode.USAGE
    }

    val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
    val packDefinitions = loadPackDefinitions(root)
    val target = when (val r = resolveTargetTemplateContext(targetId = targetId)) {
      is TargetContextResolution.Error -> {
        Console.error(r.message)
        return CommandLine.ExitCode.USAGE
      }
      is TargetContextResolution.Resolved -> r.context
      is TargetContextResolution.NoTarget -> null
    }

    val clusters = readClusters()
    if (clusters.isEmpty()) {
      Console.log("No clusters to propose against.")
      return CommandLine.ExitCode.OK
    }
    Console.log("Processing ${clusters.size} cluster(s) against ${packDefinitions.size} pack waypoint(s).")

    // Build the session set once: every screen-state log under --sessions. Needed for
    // the cross-waypoint bleed guard (a proposal's `definitionAfter` must not match a
    // step that some existing waypoint already matches). Timing both the load and the
    // propose loop so a regression (e.g. a 5x session-set growth that pushes us past the
    // Buildkite agent timeout) shows up directly in the pipeline log — same instrument
    // pattern as `waypoint tune` post-#3099 round 2.
    val sessionsLoadStartMs = System.currentTimeMillis()
    val sessions = loadSessions(sessionsDir)
    val sessionsLoadMs = System.currentTimeMillis() - sessionsLoadStartMs
    Console.log(
      "Loaded ${sessions.size} session step(s) in ${sessionsLoadMs}ms across " +
        "${sessions.map { it.sessionId }.distinct().size} session(s).",
    )

    val proposals = mutableListOf<SurvivingProposal>()
    val skipped = mutableListOf<WaypointProposer.Synthesis.Skipped>()

    val proposeLoopMs = measureTimeMillis {
      for (cluster in clusters) {
        val outcome = processCluster(cluster, packDefinitions, sessions, target)
        when (outcome) {
          is ClusterOutcome.Survived -> proposals += outcome.proposal
          is ClusterOutcome.Skipped -> skipped += outcome.skipped
        }
      }
    }
    Console.log(
      "Survived: ${proposals.size}, skipped: ${skipped.size} in ${proposeLoopMs}ms " +
        "across ${clusters.size} cluster(s) × ${packDefinitions.size} pack waypoint(s) × " +
        "${sessions.size} session step(s).",
    )

    if (idempotenceCheck) {
      val secondPass = idempotenceSecondPass(clusters, packDefinitions, proposals, sessions, target)
      if (secondPass.isNotEmpty()) {
        Console.error("Idempotence check FAILED: second pass emitted ${secondPass.size} new proposal(s):")
        for (p in secondPass) Console.error("  ${p.definition.id} (key=${p.proposalKey})")
        return 1
      }
      Console.log("Idempotence check passed.")
    }

    writeSidecars(proposals, skipped)
    Console.log("Wrote ${proposals.size} proposal sidecar(s) to ${outDir.absolutePath}.")
    return CommandLine.ExitCode.OK
  }

  /**
   * Single-cluster pipeline: synthesize → self-match → sibling-overlap-on-example →
   * cross-waypoint bleed. Returns a typed outcome; the CLI joins them up for sidecars.
   */
  private fun processCluster(
    cluster: WaypointProposer.ClusterFingerprint,
    packDefinitions: List<WaypointDefinition>,
    sessions: List<WaypointSiblingCollisionGuard.SessionStep>,
    target: TargetTemplateContext?,
  ): ClusterOutcome {
    val exampleLogFile = resolveExampleLog(cluster) ?: return ClusterOutcome.Skipped(
      WaypointProposer.Synthesis.Skipped(
        reason = "example_log not found at ${cluster.exampleLog} under session set",
        cluster = cluster,
      ),
    )
    val exampleScreen = try {
      SessionLogScreenState.loadStep(exampleLogFile)
    } catch (e: Exception) {
      return ClusterOutcome.Skipped(
        WaypointProposer.Synthesis.Skipped(
          reason = "failed to load example_log: ${e.message}",
          cluster = cluster,
        ),
      )
    }

    return when (val s = WaypointProposer.synthesize(cluster, exampleScreen, targetId)) {
      is WaypointProposer.Synthesis.Skipped -> ClusterOutcome.Skipped(s)
      is WaypointProposer.Synthesis.Ok -> validateAndJoin(cluster, s, packDefinitions, exampleScreen, sessions, target)
    }
  }

  /**
   * Three gates after a synthesis returns Ok:
   *  1. **Self-match** — the draft must match its own example. A failure here is a
   *     synthesizer bug, not a session-set signal.
   *  2. **Sibling overlap on example** — no existing waypoint in the pack may already
   *     match the example screen. If one does, the cluster is a false unmatched and
   *     refinement should handle the existing waypoint's drift instead.
   *  3. **Cross-waypoint bleed** — the new definition must not match any other session
   *     step that an existing waypoint already matches. Runs through
   *     [WaypointSiblingCollisionGuard.checkOverlap] with `definitionBefore = null` —
   *     the detection-specific match-only branch (no diff; "newly-matched" collapses
   *     to "matched by the new definition"). The empty-definition shape that an
   *     earlier draft tried doesn't work here — see the kdoc on `checkOverlap`.
   */
  private fun validateAndJoin(
    cluster: WaypointProposer.ClusterFingerprint,
    s: WaypointProposer.Synthesis.Ok,
    packDefinitions: List<WaypointDefinition>,
    exampleScreen: xyz.block.trailblaze.api.ScreenState,
    sessions: List<WaypointSiblingCollisionGuard.SessionStep>,
    target: TargetTemplateContext?,
  ): ClusterOutcome {
    val selfMatch = WaypointMatcher.match(s.definition, exampleScreen, target)
    if (!selfMatch.matched) {
      return ClusterOutcome.Skipped(
        WaypointProposer.Synthesis.Skipped(
          reason = "synthesized draft does NOT match its own example screen — synthesizer bug",
          cluster = cluster,
        ),
      )
    }
    val siblingsOnExample = packDefinitions.firstOrNull { sibling ->
      WaypointMatcher.match(sibling, exampleScreen, target).matched
    }
    if (siblingsOnExample != null) {
      return ClusterOutcome.Skipped(
        WaypointProposer.Synthesis.Skipped(
          reason = "existing waypoint ${siblingsOnExample.id} already matches the example screen — " +
            "this is refinement territory, not new-waypoint detection",
          cluster = cluster,
        ),
      )
    }
    // Cross-waypoint bleed for a brand-new waypoint: no `definitionBefore` diff — there's
    // no prior definition to compare against. For each session step the proposal matches,
    // check whether any existing waypoint also matches; that overlap is the bleed signal.
    val bleed = WaypointSiblingCollisionGuard.checkOverlap(
      waypointId = s.definition.id,
      definitionBefore = null,
      definitionAfter = s.definition,
      siblings = packDefinitions,
      sessions = sessions,
      target = target,
    )
    if (!bleed.safe) {
      val sampleSiblings = bleed.collidingSteps.map { it.siblingWaypointId }.distinct().take(3)
      return ClusterOutcome.Skipped(
        WaypointProposer.Synthesis.Skipped(
          reason = "cross-waypoint bleed: proposal would overlap on ${bleed.collidingSteps.size} step(s) " +
            "with existing waypoint(s) ${sampleSiblings.joinToString(", ")}",
          cluster = cluster,
        ),
      )
    }
    return ClusterOutcome.Survived(
      SurvivingProposal(
        cluster = cluster,
        definition = s.definition,
        proposalKey = s.proposalKey,
        rationale = s.rationale,
        bleedNewlyMatched = bleed.newlyMatchedStepIds.size,
      ),
    )
  }

  /**
   * Second pass: apply all surviving proposals to a virtual pack, then re-run the same
   * propose loop. The second pass must emit zero proposals — otherwise the synthesis
   * isn't stable on its own output.
   */
  private fun idempotenceSecondPass(
    clusters: List<WaypointProposer.ClusterFingerprint>,
    originalPack: List<WaypointDefinition>,
    survivors: List<SurvivingProposal>,
    sessions: List<WaypointSiblingCollisionGuard.SessionStep>,
    target: TargetTemplateContext?,
  ): List<SurvivingProposal> {
    if (survivors.isEmpty()) return emptyList()
    val mutatedPack = originalPack + survivors.map { it.definition }
    val out = mutableListOf<SurvivingProposal>()
    for (cluster in clusters) {
      val outcome = processCluster(cluster, mutatedPack, sessions, target)
      if (outcome is ClusterOutcome.Survived) out += outcome.proposal
    }
    return out
  }

  /**
   * Pack waypoints used for the sibling-overlap + cross-waypoint bleed gates. Routes
   * through [WaypointDiscovery.discover] so classpath-bundled packs (framework
   * `clock`/`contacts`/etc., plus workspace-declared packs) are included alongside the
   * filesystem-walked `*.waypoint.yaml` files. Using `WaypointLoader.discover` alone
   * (filesystem-only) would silently miss a class of overlaps with framework waypoints,
   * which is exactly the kind of bleed the guard is supposed to catch.
   */
  private fun loadPackDefinitions(root: File): List<WaypointDefinition> {
    val discovery = WaypointDiscovery.discover(root)
    reportLoadFailures(discovery.rootFailures)
    return discovery.definitions
  }

  // Session loading lives in WaypointCommandShared.loadSessions so the tune pipeline
  // sees identical semantics. Imported at the top of the file.

  /**
   * Resolve `cluster.exampleLog` to a concrete file under `--sessions`. The
   * aggregate's path is recorded relative to the session set root (the pipeline's unzip
   * structure), so we just join.
   */
  private fun resolveExampleLog(cluster: WaypointProposer.ClusterFingerprint): File? {
    val joined = File(sessionsDir, cluster.exampleLog)
    if (joined.isFile) return joined
    // Some pipelines write the path as `<session>/<step>` already-rel; some include
    // the zip-name prefix. Try a recursive find as fallback.
    val candidates = sessionsDir.walkTopDown()
      .filter { it.isFile && it.name == File(cluster.exampleLog).name }
      .toList()
    return candidates.firstOrNull()
  }

  private fun readClusters(): List<WaypointProposer.ClusterFingerprint> {
    val raw = clusterArg?.let { listOf(it) }
      ?: aggregateFile!!.readLines().filter { it.isNotBlank() }
    return raw
      .map { line -> WaypointProposer.parseCluster(line) }
      .sortedByDescending { it.count }
      .take(topN)
  }

  private fun writeSidecars(
    proposals: List<SurvivingProposal>,
    skipped: List<WaypointProposer.Synthesis.Skipped>,
  ) {
    if (outDir.exists()) outDir.deleteRecursively()
    outDir.mkdirs()
    // Reuse the loader's Kaml instance so the round-trip stays in lock-step.
    val yaml = WaypointLoader.yaml
    val json = Json { prettyPrint = true }
    for ((i, p) in proposals.withIndex()) {
      val slug = p.definition.id.replace('/', '_').replace(Regex("[^a-zA-Z0-9_-]"), "_")
      val dir = File(outDir, "%04d-%s".format(i, slug))
      dir.mkdirs()
      val draftYaml = yaml.encodeToString(WaypointDefinition.serializer(), p.definition)
      File(dir, "draft.waypoint.yaml").writeText(draftYaml)
      val sidecar = buildJsonObject {
        put("waypointId", p.definition.id)
        put("kind", "NEW_WAYPOINT")
        put("proposalKey", p.proposalKey)
        put("rationale", p.rationale)
        put("clusterCount", p.cluster.count)
        put("clusterKeyTexts", JsonArray(p.cluster.keyTexts.map { JsonPrimitive(it) }))
        put("exampleSession", p.cluster.exampleSession)
        put("exampleLog", p.cluster.exampleLog)
        put("bleedNewlyMatched", p.bleedNewlyMatched)
      }
      File(dir, "proposal.json").writeText(json.encodeToString(JsonObject.serializer(), sidecar))
    }
    if (skipped.isNotEmpty()) {
      val skippedArr = JsonArray(
        skipped.map { s ->
          buildJsonObject {
            put("reason", s.reason)
            put("clusterCount", s.cluster.count)
            put("keyTexts", JsonArray(s.cluster.keyTexts.map { JsonPrimitive(it) }))
            put("exampleSession", s.cluster.exampleSession)
            put("exampleLog", s.cluster.exampleLog)
          }
        },
      )
      File(outDir, "rejected.json").writeText(
        json.encodeToString(JsonArray.serializer(), skippedArr),
      )
    }
  }

  private sealed class ClusterOutcome {
    data class Survived(val proposal: SurvivingProposal) : ClusterOutcome()
    data class Skipped(val skipped: WaypointProposer.Synthesis.Skipped) : ClusterOutcome()
  }

  private data class SurvivingProposal(
    val cluster: WaypointProposer.ClusterFingerprint,
    val definition: WaypointDefinition,
    val proposalKey: String,
    val rationale: String,
    val bleedNewlyMatched: Int,
  )

}
