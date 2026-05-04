package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import java.util.concurrent.Callable

/**
 * Hidden CLI surface for the Compose desktop driver — the [xyz.block.trailblaze.compose.driver.rpc.ComposeRpcServer]
 * that the desktop app already runs on `127.0.0.1:52600` (gated by
 * `TrailblazeServerState.AppConfig.enableSelfTestServer`, default `true`).
 *
 * Why hidden: the Compose driver is mature enough to demo (snapshot the live desktop
 * window, drive it via the tool RPC) but not yet a first-class device platform — it
 * doesn't appear in `device list` and doesn't have a `TrailblazeDevicePlatform` enum
 * value. Surfacing it as a hidden command lets us show off what's there during demos
 * without confusing users who'd reasonably expect a fully-supported `desktop` device
 * sitting next to ANDROID/IOS/WEB. Promote to a visible command + platform when we
 * commit to it as a public surface.
 *
 * `hidden = true` removes the command from `trailblaze --help`'s subcommand list and
 * from auto-generated shell completions; the command still resolves by name when
 * typed explicitly.
 *
 * Usage (when promoted or for demos):
 *   trailblaze desktop snapshot               # capture the live window's screen state
 *   trailblaze desktop snapshot --port 52600  # override the RPC port
 */
@Command(
  name = "desktop",
  hidden = true,
  mixinStandardHelpOptions = true,
  description = [
    "Internal Compose desktop driver — snapshot/drive the running Trailblaze desktop window.",
  ],
  subcommands = [
    DesktopSnapshotCommand::class,
  ],
)
class DesktopCommand : Callable<Int> {
  override fun call(): Int {
    CommandLine(this).usage(System.out)
    return CommandLine.ExitCode.OK
  }
}
