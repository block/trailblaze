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

  @Parameters(arity = "0..1", description = ["Path to *_TrailblazeLlmRequestLog.json (required unless --session/--step given)"])
  var positionalLogFile: File? = null

  @Option(names = ["--def"], description = ["Waypoint id to validate (required)"], required = true)
  lateinit var defId: String

  @Option(names = ["--session"], description = ["Session log directory (containing *_TrailblazeLlmRequestLog.json files)"])
  var session: File? = null

  @Option(names = ["--step"], description = ["1-based index of the step within the session (default: last step)"])
  var step: Int? = null

  @Option(
    names = ["--root"],
    description = ["Additional directory to scan for *.waypoint.yaml files (default: $DEFAULT_WAYPOINT_ROOT, resolved against the current working directory). Pack waypoints are always included regardless of --root."],
  )
  var root: File = File(DEFAULT_WAYPOINT_ROOT)

  override fun call(): Int {
    val discovery = WaypointDiscovery.discover(root)
    reportLoadFailures(discovery.rootFailures)
    val def = discovery.definitions.firstOrNull { it.id == defId } ?: run {
      val suffix = if (discovery.packLoadFailed) {
        " (some packs failed to load — see warnings above; the missing waypoint may live in a broken pack)"
      } else {
        ""
      }
      Console.error("Waypoint id not found: $defId (searched active packs and ${root.absolutePath}).$suffix")
      return CommandLine.ExitCode.USAGE
    }
    val logFile = resolveScreenStateFile() ?: return CommandLine.ExitCode.USAGE
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
   *    validate --def X` checks the waypoint against its committed example pair.
   */
  private fun resolveScreenStateFile(): File? {
    positionalLogFile?.let { return validateLogFile(it, label = "Log file") }
    session?.let { return resolveFromSession(it) }
    return resolveSiblingExample()
  }

  private fun resolveFromSession(sessionDir: File): File? {
    val validated = validateSessionDir(sessionDir) ?: return null
    val logs = SessionLogScreenState.listLlmRequestLogs(validated)
    if (logs.isEmpty()) {
      Console.error("No *_TrailblazeLlmRequestLog.json files found in: ${validated.absolutePath}")
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
   * Finds the YAML file declaring [defId] and returns its sibling `*.example.json`
   * if one exists. Used when no other screen-state source has been supplied so
   * callers can validate against the committed example pair without remembering
   * its filename.
   *
   * Walks `--root` plus the conventional pack roots in this repo. Pack-bundled
   * waypoints live in source-controlled `src/commonMain/resources/trailblaze-config/packs/`
   * trees under `trailblaze-models/`; their example sidecars sit next to the YAML.
   */
  private fun resolveSiblingExample(): File? {
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
        if (def.id != defId) continue
        val baseName = yamlFile.name.removeSuffix(".waypoint.yaml")
        val example = File(yamlFile.parentFile, "$baseName.example.json")
        if (example.exists()) return example
        Console.error("No screen state given and no sibling example file at: ${example.absolutePath}")
        Console.error("Hint: capture one with `trailblaze waypoint capture-example --def $defId ...`")
        return null
      }
    }
    Console.error("Waypoint id not found while resolving sibling example: $defId")
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
