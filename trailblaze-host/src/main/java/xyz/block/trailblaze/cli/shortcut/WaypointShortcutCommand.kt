package xyz.block.trailblaze.cli.shortcut

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

/**
 * Parent command for the shortcut analyze-and-propose loop. Houses the two
 * subcommands — `propose` (offline analysis, no device) and `verify` (empirical
 * replay on a connected emulator).
 *
 * Sits under `waypoint` (the parent of `tune`, `propose`, …) rather than as a
 * standalone top-level so the noun stays consistent: shortcuts are pack-relative
 * artifacts in the same world as waypoints. See
 * `docs/internal/devlog/2026-05-19-waypoint-pack-shortcuts.md` for the design.
 */
@Command(
  name = "shortcut",
  mixinStandardHelpOptions = true,
  description = [
    "Analyze a session set for (A->B) waypoint transitions and synthesize draft",
    "shortcut YAMLs, then empirically replay each candidate on a fresh emulator",
    "before opening a PR. Subcommands: propose (offline), verify (on-device).",
  ],
  subcommands = [
    WaypointShortcutProposeCommand::class,
    WaypointShortcutVerifyCommand::class,
  ],
)
class WaypointShortcutCommand : Callable<Int> {
  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }
}
