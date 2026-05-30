package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.util.concurrent.Callable

/**
 * Open the multi-device live grid at `/devices/all` in your default browser.
 *
 * The page renders one tile per connected device, each streaming a low-fps screenshot
 * via the same WebSocket plumbing the single-device viewer uses. Click a tile to drop
 * into the existing `/devices` single-device picker for full-fps + recording controls.
 * Mirrors playwright-cli's `show-*` family: a thin wrapper that resolves the running
 * daemon's URL and hands it to the OS browser.
 *
 * Example:
 *   trailblaze show              - open http://localhost:<port>/devices/all
 */
@Command(
  name = "show",
  mixinStandardHelpOptions = true,
  description = ["Open the multi-device live grid (/devices/all) in your default browser"],
)
class ShowCommand : Callable<Int> {

  @CommandLine.ParentCommand
  private lateinit var parent: TrailblazeCliCommand

  override fun call(): Int {
    val port = parent.getEffectivePort()
    return DaemonClient(port = port).use { daemon ->
      if (!daemon.isRunningBlocking()) {
        reportCliError(
          verb = "Show",
          target = "http://localhost:$port/devices/all",
          reason = "Trailblaze daemon is not running on port $port.",
          hint = "Start it with `trailblaze app` (desktop GUI) or `trailblaze app --headless` (daemon only).",
        )
        return@use TrailblazeExitCode.INFRA_FAILED.code
      }
      val url = "http://localhost:$port/devices/all"
      Console.info("Opening $url ...")
      // openInDefaultBrowser returns false when Desktop.BROWSE is unsupported (headless
      // server, missing X11 on Linux) or the underlying call threw. Branch on the return
      // so the user gets a structured INFRA_FAILED envelope instead of a misleading
      // SUCCESS exit when nothing actually opened.
      if (!TrailblazeDesktopUtil.openInDefaultBrowser(url)) {
        reportCliError(
          verb = "Show",
          target = url,
          reason = "Could not open the URL in a browser.",
          hint = "Open it manually, or check that your environment supports Desktop.BROWSE " +
            "(headless servers and some Linux desktops don't).",
        )
        return@use TrailblazeExitCode.INFRA_FAILED.code
      }
      TrailblazeExitCode.SUCCESS.code
    }
  }
}
