package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable

@Command(
  name = "list",
  mixinStandardHelpOptions = true,
  description = [
    "List all waypoint definitions from active packs (workspace + framework classpath)",
    "and any additional *.waypoint.yaml files discovered under --root.",
  ],
)
class WaypointListCommand : Callable<Int> {
  @Option(
    names = ["--target"],
    paramLabel = "<id>",
    description = [
      "Pack id to operate on. Resolves --root to <workspace>/packs/<id>/waypoints/. " +
        "Mutually exclusive with --root (--root wins if both given).",
    ],
  )
  var targetId: String? = null

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = ["Additional directory to scan for *.waypoint.yaml files. Overrides --target. Pack waypoints are always included regardless. (Convention: $DEFAULT_WAYPOINT_ROOT)"],
  )
  var rootOverride: File? = null

  override fun call(): Int {
    val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
    val result = WaypointDiscovery.discover(root)
    reportLoadFailures(result.rootFailures)
    if (result.definitions.isEmpty()) {
      // Distinguish "no packs configured anywhere" from "some packs failed to load"
      // — otherwise a typo in `trailblaze.yaml` produces the same message as a
      // genuinely empty workspace, leaving the user with nothing to act on.
      if (result.packLoadFailed) {
        Console.log("No waypoint definitions found (some packs failed to load — see warnings above).")
      } else {
        Console.log("No waypoint definitions found.")
      }
      // Empty + no scoping flags suggests the user might want --target. Hint only here,
      // not in resolveWaypointRoot — classpath packs typically supply 100+ results and
      // a default-path warning would be noise on every invocation.
      maybeWarnNoTarget(rootOverride, targetId, resultIsEmpty = true)
      return CommandLine.ExitCode.OK
    }
    Console.log("Found ${result.definitions.size} waypoint(s):")
    for (def in result.definitions) {
      Console.log("")
      Console.log(def.id)
      val description = def.description?.trim().orEmpty()
      if (description.isEmpty()) {
        Console.log("  (no description)")
      } else {
        // Indent every line of the description so multi-line text stays visually
        // attached to the waypoint id above it.
        description.lineSequence().forEach { line -> Console.log("  $line") }
      }
    }
    return CommandLine.ExitCode.OK
  }
}
