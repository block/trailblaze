package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
  name = "waypoint",
  mixinStandardHelpOptions = true,
  description = [
    "Match named app locations (waypoints) against captured screen state.",
  ],
  subcommands = [
    WaypointListCommand::class,
    WaypointLocateCommand::class,
    WaypointValidateCommand::class,
    WaypointCaptureExampleCommand::class,
    // Translate one element ref (as shown in `trailblaze snapshot`) into a list of
    // selector candidates the author can paste into a *.waypoint.yaml. Wraps the
    // existing TrailblazeNodeSelectorGenerator strategies — same logic the runtime
    // uses for tap recordings, repurposed for waypoint authoring.
    WaypointSuggestSelectorCommand::class,
    // Mechanical Maestro→accessibility selector migration. Walks a trail YAML, pairs each
    // selector-bearing tool with the corresponding session log step, runs the deterministic
    // two-tree resolution (Maestro selector → coordinate → accessibility selector via
    // findBestSelector), and emits an updated YAML with `nodeSelector:` populated. Replaces
    // the LLM-driven migration pattern from #2673.
    WaypointMigrateTrailCommand::class,
    // Segments are derived from waypoints — running the matcher against a session log
    // and emitting the observed transitions between matched waypoints. Lives under
    // `waypoint` rather than as a top-level peer because its inputs (waypoints) and
    // its outputs (transitions between waypoints) are both inside the waypoint world.
    // Future shortcut/route commands per the 2026-04-28 devlog will sit alongside.
    SegmentCommand::class,
    // Renders the navigation graph (waypoints + authored shortcuts + trailheads) as a
    // standalone HTML page suitable for emailing/Slacking. Same data that backs the
    // daemon's live `/waypoints/graph` browser view.
    WaypointGraphCommand::class,
  ],
)
class WaypointCommand : Callable<Int> {
  /**
   * Reach the root command so subcommands (validate / capture-example) can call
   * `cliRoot.configProvider()` to access the same `LogsRepo` the running daemon
   * already resolved. This is what makes `capture-example --id <id>` "magic" by
   * default — no need for the caller to pass a `--logs-dir`; we already know it.
   */
  @CommandLine.ParentCommand
  internal lateinit var cliRoot: TrailblazeCliCommand

  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }
}
