package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

/**
 * `trailblaze selector`: work out and check selectors against captured screens.
 *
 * Both subcommands read session logs, or with `--live` the connected device's current screen, and
 * are cheap enough to call many times: forwarded to a running daemon they skip JVM startup, and
 * `--batch` answers a whole file of questions in one call.
 */
@Command(
  name = "selector",
  mixinStandardHelpOptions = true,
  description = [
    "Suggest and check element selectors against captured screens, or the device's (--live).",
  ],
  subcommands = [
    SelectorSuggestCommand::class,
    SelectorMatchCommand::class,
  ],
)
class SelectorCommand : Callable<Int> {
  @CommandLine.Spec
  private lateinit var spec: CommandLine.Model.CommandSpec

  override fun call(): Int {
    spec.commandLine().usage(System.out)
    return TrailblazeExitCode.SUCCESS.code
  }
}
