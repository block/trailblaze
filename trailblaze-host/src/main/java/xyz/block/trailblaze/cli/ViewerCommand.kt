package xyz.block.trailblaze.cli

import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.report.ViewerShellResource
import xyz.block.trailblaze.util.Console
import java.io.File
import java.util.concurrent.Callable

/**
 * Write out the standalone report viewer bundled into this binary.
 *
 * The viewer is one self-contained HTML page that renders a full interactive report from any
 * session archive — dropped on the page, or loaded over the network with `?zip=<archive-url>`.
 * Serving it is how a CI build turns hundreds of archives into hundreds of openable reports
 * without embedding any of them.
 *
 * It is bundled rather than built here on purpose: building it needs bun, this repo's TypeScript
 * sources, and the Gradle-built selector engine, none of which exist where an installed CLI runs.
 * Emitting the packaged copy also guarantees the viewer matches the renderer this binary writes
 * reports with — a hosted copy built separately drifts, and then a report link renders a run with
 * different report code than generated it.
 */
@Command(
  name = "viewer",
  mixinStandardHelpOptions = true,
  description = [
    "Write out the standalone report viewer (one self-contained HTML page) bundled into this " +
      "binary. Serve it anywhere, or just open it: drop a session archive on the page, or point " +
      "it at one with ?zip=<archive-url>. Versioned with this CLI, so it always matches the " +
      "reports this binary generates.",
  ],
)
class ViewerCommand : Callable<Int> {

  @Option(
    names = ["--output", "-o"],
    paramLabel = "<file-or-dir>",
    description = [
      "Where to write the viewer. A path ending in .html is used as-is; anything else is " +
        "treated as a directory and index.html is written inside it. Defaults to ./index.html.",
    ],
  )
  var output: File = File("index.html")

  override fun call(): Int {
    // A directory target writes index.html, since a static host serves the viewer as a page at a
    // path rather than as a named file — the same shape `build-viewer-shell.sh <out-dir>` produces.
    val destination = if (output.name.endsWith(".html", ignoreCase = true)) output else File(output, "index.html")

    if (!ViewerShellResource.copyTo(destination)) {
      reportCliError(
        verb = "Viewer",
        target = destination.path,
        reason = "this build has no bundled report viewer (missing JAR resources)",
        hint = "reinstall the CLI, or build one from a source checkout: ./scripts/build-viewer-shell.sh <out-dir>",
      )
      return TrailblazeExitCode.INFRA_FAILED.code
    }

    Console.log("Wrote the report viewer to ${destination.absolutePath} (${destination.length()} bytes)")
    return TrailblazeExitCode.SUCCESS.code
  }
}
