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
    // Segments are derived from waypoints — running the matcher against a session log
    // and emitting the observed transitions between matched waypoints. Lives under
    // `waypoint` rather than as a top-level peer because its inputs (waypoints) and
    // its outputs (transitions between waypoints) are both inside the waypoint world.
    // Future shortcut/route commands per the 2026-04-28 devlog will sit alongside.
    SegmentCommand::class,
  ],
)
class WaypointCommand : Callable<Int> {
  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }
}
