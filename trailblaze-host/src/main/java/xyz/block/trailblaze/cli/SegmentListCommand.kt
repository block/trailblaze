package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.segment.SessionSegmentExtractor
import xyz.block.trailblaze.util.Console
import java.io.File
import java.io.IOException
import java.util.concurrent.Callable

@Command(
  name = "list",
  mixinStandardHelpOptions = true,
  description = [
    "List trail segments observed in a session log directory.",
    "A segment is a transition from one matched waypoint to another, with the tool calls that drove it.",
  ],
)
class SegmentListCommand : Callable<Int> {

  @Option(
    names = ["--session"],
    required = true,
    description = ["Session log directory (containing *.json log files)"],
  )
  lateinit var session: File

  @Option(
    names = ["--root"],
    description = ["Additional directory to scan for *.waypoint.yaml files (default: $DEFAULT_WAYPOINT_ROOT, resolved against the current working directory). Pack waypoints are always included regardless of --root."],
  )
  var root: File = File(DEFAULT_WAYPOINT_ROOT)

  override fun call(): Int {
    val validatedSession = validateSessionDir(session) ?: return CommandLine.ExitCode.USAGE

    val discovery = WaypointDiscovery.discover(root)
    reportLoadFailures(discovery.rootFailures)
    if (discovery.definitions.isEmpty()) {
      val suffix = if (discovery.packLoadFailed) {
        " (some packs failed to load — see warnings above)"
      } else {
        ""
      }
      Console.error("No waypoint definitions found in active packs or under ${root.absolutePath}.$suffix")
      return CommandLine.ExitCode.USAGE
    }

    val analysis = try {
      SessionSegmentExtractor.analyze(validatedSession, discovery.definitions)
    } catch (e: IOException) {
      // Permission denied / disappeared mid-walk / similar. Surface it as a CLI error
      // rather than letting picocli print a stack trace, so the user sees the actionable
      // message instead of the framework noise.
      Console.error("Failed to read session directory: ${e.message}")
      return CommandLine.ExitCode.USAGE
    }
    Console.log("Session: ${validatedSession.absolutePath}")
    Console.log("Waypoints: ${discovery.definitions.size}")
    Console.log(
      "Steps: ${analysis.totalRequestLogs} request logs, " +
        "${analysis.stepsWithNodeTree} with trailblazeNodeTree, " +
        "${analysis.stepsWithMatchedWaypoint} matched a waypoint",
    )
    if (analysis.parseFailures > 0) {
      // Surface the dropped-file count so a user diagnosing "no segments observed" can
      // tell that we silently skipped malformed log files rather than the session being
      // genuinely empty. Filenames are intentionally not printed by default — the count
      // is the actionable signal.
      Console.log(
        "  ${analysis.parseFailures} file(s) skipped: failed to decode as TrailblazeLog.",
      )
    }
    if (analysis.stepsWithAmbiguousMatch > 0) {
      Console.log(
        "  ${analysis.stepsWithAmbiguousMatch} step(s) skipped: matched multiple waypoints " +
          "(tighten selectors to disambiguate).",
      )
    }
    val segments = analysis.segments
    if (segments.isEmpty()) {
      Console.log("No segments observed.")
      Console.log(noSegmentsHint(analysis, validatedSession.absolutePath))
      return CommandLine.ExitCode.OK
    }
    Console.log("Segments observed: ${segments.size}")
    Console.log("")
    for ((i, segment) in segments.withIndex()) {
      val durationSecs = segment.observation.durationMs / 1000.0
      Console.log(
        "${i + 1}. ${segment.from} → ${segment.to}  " +
          "(steps ${segment.observation.fromStep}→${segment.observation.toStep}, " +
          "${"%.1f".format(durationSecs)}s)",
      )
      if (segment.triggers.isEmpty()) {
        Console.log("    triggers: (none recorded between these steps)")
      } else {
        for (trigger in segment.triggers) {
          Console.log("    · $trigger")
        }
      }
    }
    return CommandLine.ExitCode.OK
  }
}

/**
 * Picks the user-facing hint to print when [SessionSegmentExtractor.Analysis.segments] is
 * empty. The four branches correspond to the four root causes a user might hit:
 *  1. No request logs in the session at all (typically: pointed at the wrong directory).
 *  2. Request logs exist but none carry a `trailblazeNodeTree` (legacy session shape from
 *     before the multi-agent logging fix; can't be matched against waypoints).
 *  3. Fewer than two steps matched a waypoint (no transition possible).
 *  4. All matched steps landed on the same waypoint (no boundary between distinct ones).
 *
 * Extracted as a top-level package function so tests can pin the four-branch contract
 * without needing to drive the whole `call()` method.
 */
internal fun noSegmentsHint(
  analysis: SessionSegmentExtractor.Analysis,
  sessionAbsolutePath: String,
): String = when {
  analysis.totalRequestLogs == 0 ->
    "  hint: no TrailblazeLlmRequestLog files found in this session."
  analysis.stepsWithNodeTree == 0 ->
    "  hint: no step had a trailblazeNodeTree — this session predates the multi-agent " +
      "logging fix; only sessions captured after that change can be matched."
  analysis.stepsWithMatchedWaypoint < 2 ->
    "  hint: <2 steps matched any waypoint, so there are no transitions to report. " +
      "Try `trailblaze waypoint locate --session $sessionAbsolutePath --step N` to debug per-step."
  else ->
    "  hint: every matched step landed on the same waypoint — no transitions occurred."
}
