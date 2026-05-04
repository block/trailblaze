package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.waypoint.SessionLogScreenState
import xyz.block.trailblaze.waypoint.WaypointMatcher
import java.io.File
import java.util.concurrent.Callable

@Command(
  name = "locate",
  mixinStandardHelpOptions = true,
  description = [
    "Given a captured screen state, report which waypoint(s) match.",
  ],
)
class WaypointLocateCommand : Callable<Int> {

  @Option(names = ["--session"], description = ["Session log directory (containing *_TrailblazeLlmRequestLog.json files)"])
  var session: File? = null

  @Option(names = ["--step"], description = ["1-based index of the step within the session (default: last step)"])
  var step: Int? = null

  @Option(names = ["--file"], description = ["Direct path to a *_TrailblazeLlmRequestLog.json file (alternative to --session/--step)"])
  var file: File? = null

  @Option(
    names = ["--root"],
    description = ["Additional directory to scan for *.waypoint.yaml files (default: $DEFAULT_WAYPOINT_ROOT, resolved against the current working directory). Pack waypoints are always included regardless of --root."],
  )
  var root: File = File(DEFAULT_WAYPOINT_ROOT)

  @Option(names = ["--live"], description = ["Pull screen state from the connected device (not yet implemented)"])
  var live: Boolean = false

  override fun call(): Int {
    if (live) {
      Console.error("--live is not yet implemented for waypoint locate. Use --session/--step or --file.")
      return CommandLine.ExitCode.USAGE
    }
    val logFile = resolveLogFile() ?: return CommandLine.ExitCode.USAGE
    val screen = SessionLogScreenState.loadStep(logFile)
    val discovery = WaypointDiscovery.discover(root)
    reportLoadFailures(discovery.rootFailures)
    val defs = discovery.definitions
    if (defs.isEmpty()) {
      val suffix = if (discovery.packLoadFailed) {
        " (some packs failed to load — see warnings above)"
      } else {
        ""
      }
      Console.error("No waypoint definitions found in active packs or under ${root.absolutePath}.$suffix")
      return CommandLine.ExitCode.USAGE
    }
    Console.log("Locating against ${defs.size} waypoint(s); screen state: ${logFile.name}")
    val results = defs.map { WaypointMatcher.match(it, screen) }
    val matched = results.filter { it.matched }
    if (matched.isEmpty()) {
      Console.log("  no waypoints match this screen.")
    } else {
      for (r in matched) Console.log("  MATCH ${r.definitionId}")
    }
    val nearMisses = results.filter { !it.matched && it.skipped == null && it.missingRequired.size <= 1 && it.presentForbidden.isEmpty() }
    if (nearMisses.isNotEmpty()) {
      Console.log("")
      Console.log("Near-misses (failed by exactly one required entry):")
      for (r in nearMisses) {
        Console.log("  ~ ${r.definitionId}")
        for (miss in r.missingRequired) {
          val descr = miss.entry.description ?: miss.entry.selector.description()
          Console.log("      missing: $descr (got ${miss.matchCount} matches, need ${miss.entry.minCount})")
        }
      }
    }
    val skipped = results.filter { it.skipped != null }
    if (skipped.isNotEmpty()) {
      Console.log("")
      Console.log("Skipped (no trailblazeNodeTree in screen state):")
      for (r in skipped) Console.log("  · ${r.definitionId}")
    }
    return CommandLine.ExitCode.OK
  }

  private fun resolveLogFile(): File? {
    file?.let { return validateLogFile(it, label = "--file") }
    val s = session ?: run {
      Console.error("Provide either --file or --session.")
      return null
    }
    val validated = validateSessionDir(s) ?: return null
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
}
