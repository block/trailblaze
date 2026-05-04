package xyz.block.trailblaze.cli

import picocli.CommandLine
import kotlin.test.Test

/**
 * Walks the picocli command tree rooted at [TrailblazeCliCommand] and writes one baseline
 * file per command path under `cli-output-baselines/help-<command-path>.txt`, where the
 * path is the chain from the root joined with hyphens (e.g. `help-trailblaze.txt`,
 * `help-trailblaze-config.txt`, `help-trailblaze-config-show.txt`).
 *
 * After all files are written, [BaselineHarness.assertNoDrift] runs `git status` over
 * the entire baseline directory so a single failure surfaces every dirty path together
 * — including newly-added subcommands whose baseline wasn't yet checked in.
 *
 * ## Why every help text gets pinned
 *
 * `--help` output is the single richest source of CLI surface info: command name,
 * description, options, defaults, examples, usage line. A regression here is invisible
 * to runtime tests but immediately user-visible. Pinning the rendered text lets a PR
 * reviewer see "this rename touched the help wording" or "this option got dropped from
 * --help" the same way they'd see a code change.
 *
 * Picocli renders help purely from annotations (no command execution), so the test runs
 * in milliseconds and doesn't need a daemon. The only caveat: the root command requires
 * `appProvider` and `configProvider` lambdas — pass throw-on-call ones, since help
 * rendering must not invoke them. If a future picocli upgrade or a help customization
 * changes that, the test will fail loudly with the underlying exception, which is the
 * right signal to update the wiring.
 *
 * ## Untracked / new commands
 *
 * Adding a new subcommand creates a new `help-<path>.txt` file as untracked. Git status
 * picks it up via `??` and the assertion fails until the author commits the new
 * baseline — no flag to remember.
 */
class CliHelpBaselineTest {

  @Test
  fun `every cli command's --help output matches its checked-in baseline`() {
    val root = CommandLine(
      TrailblazeCliCommand(
        appProvider = { error("appProvider must not be invoked during --help rendering") },
        configProvider = { error("configProvider must not be invoked during --help rendering") },
      ),
    ).setCaseInsensitiveEnumValuesAllowed(true)

    walkAndWriteHelp(root, listOf(root.commandName))

    BaselineHarness.assertNoDrift(BaselineHarness.BASELINE_DIR)
  }

  /**
   * DFS through every subcommand reachable from [cl], writing a baseline for each.
   * Hidden commands ([CommandSpec.usageMessage] elides them) are still walked and
   * baselined here because the goal is full coverage — drift in a hidden command's
   * help text matters too if anyone has the muscle memory to call it.
   */
  private fun walkAndWriteHelp(cl: CommandLine, path: List<String>) {
    val rendered = cl.usageMessage
    val filename = "help-${path.joinToString("-")}.txt"
    BaselineHarness.write(filename, rendered)

    for ((subName, subCommandLine) in cl.subcommands) {
      // Skip the implicit `help` mixin — picocli auto-adds it to every command and its
      // baseline duplicates the parent's content with one paragraph swapped.
      if (subName == "help") continue
      walkAndWriteHelp(subCommandLine, path + subName)
    }
  }
}
