package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import picocli.CommandLine.Parameters
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointLoader
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.io.File
import java.util.concurrent.Callable

@Command(
  name = "validate",
  mixinStandardHelpOptions = true,
  description = [
    "Validate that a specific waypoint definition matches a captured screen state.",
  ],
)
class WaypointValidateCommand : Callable<Int> {

  /** Picocli wires this to the enclosing `WaypointCommand` so we can reach `cliRoot.configProvider()`. */
  @CommandLine.ParentCommand
  private lateinit var parent: WaypointCommand

  @Parameters(
    arity = "0..1",
    description = [
      "Path to a screen-state log — accepts *_TrailblazeLlmRequestLog.json, " +
        "*_AgentDriverLog.json, *_TrailblazeSnapshotLog.json, or a sibling " +
        "*.example.json. Optional: omit and use --session, or rely on the " +
        "auto-resolved sibling example pair next to the matching *.waypoint.yaml.",
    ],
  )
  var positionalLogFile: File? = null

  @Option(names = ["--id"], description = ["Waypoint id to validate (matches the YAML's top-level `id:` field). Required."], required = true)
  lateinit var waypointId: String

  @Option(
    names = ["--session"],
    description = [
      "Session id (the directory name under --logs-dir, e.g. `2026_05_07_22_26_48_yaml_6258`). " +
        "Combine with --step to pin a specific step; without --step the last step is used.",
    ],
  )
  var sessionId: String? = null

  @Option(names = ["--step"], description = ["1-based index of the step within --session (default: last step). Requires --session."])
  var step: Int? = null

  @Option(
    names = ["--target"],
    paramLabel = "<id>",
    description = [
      "Pack id to operate on. Resolves --root to <workspace>/packs/<id>/waypoints/ — the " +
        "canonical workspace-pack location. Warns if no such pack exists. Mutually exclusive " +
        "with --root (--root wins if both given).",
    ],
  )
  var targetId: String? = null

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = ["Additional directory to scan for *.waypoint.yaml files. Overrides --target. Pack waypoints are always included regardless. (Convention: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var rootOverride: File? = null

  @Option(
    names = ["--logs-dir"],
    paramLabel = "<path>",
    description = ["Override the directory containing per-session log dirs. Defaults to the running daemon's resolved logsDir."],
  )
  var logsDirOverride: File? = null

  override fun call(): Int {
    val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
    val discovery = WaypointDiscovery.discover(root)
    reportLoadFailures(discovery.rootFailures)
    val def = discovery.definitions.firstOrNull { it.id == waypointId } ?: run {
      val suffix = if (discovery.packLoadFailed) {
        " (some packs failed to load — see warnings above; the missing waypoint may live in a broken pack)"
      } else {
        ""
      }
      Console.error("Waypoint id not found: $waypointId (searched active packs and ${root.absolutePath}).$suffix")
      maybeWarnNoTarget(rootOverride, targetId, resultIsEmpty = true)
      return CommandLine.ExitCode.USAGE
    }
    val logFile = resolveScreenStateFile(root) ?: return CommandLine.ExitCode.USAGE
    val screen = SessionLogScreenState.loadStep(logFile)
    val r = WaypointMatcher.match(def, screen)
    Console.log("Definition: ${def.id}")
    def.description?.let { Console.log("  $it") }
    Console.log("Screen state: ${logFile.name}")
    Console.log("")
    Console.log(formatResult(r))
    return if (r.matched) CommandLine.ExitCode.OK else 1
  }

  /**
   * Resolves the screen-state file in this order:
   * 1. The positional log file argument, if given.
   * 2. The `--session` (with optional `--step`) pair, if given.
   * 3. A sibling `<def-base-name>.example.json` next to the matching `*.waypoint.yaml`,
   *    if neither of the above is given. This is the "zero-arg" case: `waypoint
   *    validate --id X` checks the waypoint against its committed example pair.
   */
  private fun resolveScreenStateFile(root: File): File? {
    positionalLogFile?.let { return validateLogFile(it, label = "Log file") }
    // --step is meaningful only with --session — silently dropping it would mask the user's
    // pinned-step intent (they ask for step 5 and get the sibling example or auto-resolve).
    // Fail fast.
    if (step != null && sessionId == null) {
      Console.error("--step requires --session. Pass --session <id> [--step <n>], or drop --step.")
      return null
    }
    sessionId?.let { return resolveFromSession(it) }
    return resolveSiblingExample(root)
  }

  /**
   * Resolves `--session <id>` to a directory under the effective logs-dir (caller-provided
   * `--logs-dir` override OR the running daemon's path-only `config.logsDir`), then picks
   * the step. Walks the broader screen-state log set (AgentDriverLog / SnapshotLog /
   * LlmRequestLog) so any of the three log types written during a session are eligible.
   */
  private fun resolveFromSession(id: String): File? {
    // Path-only read — uses [TrailblazeDesktopAppConfig.logsDir] rather than touching
    // `logsRepo.logsDir`, which would force LogsRepo construction and spawn its non-daemon
    // FileWatcher threads (keeping the one-shot CLI JVM alive after the command completes).
    // The lazy LogsRepo split landed alongside this command's --logs-dir support.
    val effectiveLogsDir = logsDirOverride ?: try {
      parent.cliRoot.configProvider().logsDir
    } catch (_: UninitializedPropertyAccessException) {
      File("./logs")
    }
    val sessionDir = File(effectiveLogsDir, id)
    val resolved = if (sessionDir.isDirectory) sessionDir else File(id).takeIf { it.isDirectory }
    if (resolved == null) {
      Console.error("--session $id: no directory found at ${sessionDir.absolutePath} (or as a literal path).")
      return null
    }
    val logs = SessionLogScreenState.listScreenStateLogs(resolved)
    if (logs.isEmpty()) {
      Console.error("No screen-state logs found in: ${resolved.absolutePath}")
      return null
    }
    val idx = step?.let { it - 1 } ?: (logs.size - 1)
    if (idx !in logs.indices) {
      Console.error("--step out of range: 1..${logs.size}")
      return null
    }
    return logs[idx]
  }

  /**
   * Finds the YAML file declaring [waypointId] and returns its sibling `*.example.json`
   * if one exists. Used when no other screen-state source has been supplied so
   * callers can validate against the committed example pair without remembering
   * its filename.
   *
   * Walks `--root` plus the conventional pack roots in this repo. Pack-bundled
   * waypoints live in source-controlled `src/commonMain/resources/trailblaze-config/packs/`
   * trees under `trailblaze-models/`; their example sidecars sit next to the YAML.
   */
  private fun resolveSiblingExample(root: File): File? {
    val candidateRoots = (
      sequenceOf(root) + SIBLING_EXAMPLE_PACK_ROOTS.asSequence().map(::File)
    ).distinct()
    for (rootDir in candidateRoots) {
      for (yamlFile in WaypointLoader.discover(rootDir)) {
        val def = try {
          WaypointLoader.loadFile(yamlFile)
        } catch (_: Exception) {
          continue
        }
        if (def.id != waypointId) continue
        val baseName = yamlFile.name.removeSuffix(".waypoint.yaml")
        val example = File(yamlFile.parentFile, "$baseName.example.json")
        if (example.exists()) return example
        Console.error("No screen state given and no sibling example file at: ${example.absolutePath}")
        Console.error("Hint: capture one with `trailblaze waypoint capture-example --id $waypointId ...`")
        return null
      }
    }
    Console.error("Waypoint id not found while resolving sibling example: $waypointId")
    return null
  }

  companion object {
    /**
     * Conventional pack-source roots in this repo. Walked alongside `--root` when
     * resolving a sibling `<base>.example.json` for the zero-arg validate path so
     * pack-bundled waypoints can be validated against their own committed examples
     * without callers having to specify each pack root by hand.
     */
    private val SIBLING_EXAMPLE_PACK_ROOTS = listOf(
      "trailblaze-models/src/commonMain/resources/trailblaze-config/packs",
    )
  }
}
