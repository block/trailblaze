package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

@Command(
  name = "segment",
  mixinStandardHelpOptions = true,
  description = [
    "Inspect transitions between waypoints observed in a session log.",
  ],
  subcommands = [
    SegmentListCommand::class,
  ],
)
class SegmentCommand : Callable<Int> {
  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }
}
