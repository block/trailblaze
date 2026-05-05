package xyz.block.trailblaze.cli

import picocli.CommandLine
import picocli.CommandLine.Command
import picocli.CommandLine.Option
import xyz.block.trailblaze.graph.WaypointGraphBuilder
import xyz.block.trailblaze.graph.WaypointGraphHtmlRenderer
import xyz.block.trailblaze.util.Console
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.concurrent.Callable

@Command(
  name = "graph",
  mixinStandardHelpOptions = true,
  description = [
    "Render the waypoint navigation graph (waypoints, authored shortcuts, authored",
    "trailheads) as a single self-contained HTML file. The output bakes in screenshots",
    "as data URIs and loads React Flow + dagre at runtime via esm.sh CDN — open it in any",
    "browser, share it via email/Slack/zip, no Trailblaze install required on the viewer's",
    "side.",
    "",
    "For a live, refresh-on-edit view from the running daemon, point your browser at",
    "http://localhost:<daemon-port>/waypoints/graph instead.",
  ],
)
class WaypointGraphCommand : Callable<Int> {

  @Option(
    names = ["--root"],
    description = [
      "Filesystem directory to scan for *.waypoint.yaml files (default: " +
        "$DEFAULT_WAYPOINT_ROOT, resolved against the current working directory). " +
        "Pack-bundled waypoints from the classpath are always included regardless of this flag.",
    ],
  )
  var root: File = File(DEFAULT_WAYPOINT_ROOT)

  @Option(
    names = ["--out", "-o"],
    description = [
      "Output HTML file path (default: $DEFAULT_OUT_PATH in the current directory). " +
        "Parent directories are created if missing. The file is overwritten if present.",
    ],
  )
  var out: File = File(DEFAULT_OUT_PATH)

  override fun call(): Int {
    if (root.exists() && !root.isDirectory) {
      Console.error(
        "Warning: --root is not a directory: ${root.absolutePath} " +
          "(filesystem-walk waypoints will be empty; classpath-bundled packs still load)",
      )
    }

    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    val data = WaypointGraphBuilder.build(
      root = root,
      // The CLI emits a frozen snapshot — embed the timestamp + "snapshot" tag in the
      // generated note so a viewer of the saved file knows it isn't live. The daemon
      // endpoint uses "live" for the same field.
      liveSourceLabel = "snapshot · $timestamp",
    )

    if (data.waypoints.isEmpty()) {
      // Don't fail with non-zero — emitting a valid HTML "empty state" is the right
      // contract for a tool that's also called from automation. The CLI is loud
      // enough on stderr that interactive users still notice.
      Console.error(
        "Warning: no waypoints discovered. Output will render an empty-state page. " +
          "Check that --root points at a directory containing *.waypoint.yaml files, " +
          "or that the trailblaze-config classpath packs are on the runtime classpath.",
      )
    }

    val html = WaypointGraphHtmlRenderer.render(data)

    out.parentFile?.takeIf { !it.exists() }?.mkdirs()
    out.writeText(html)

    Console.log("Wrote waypoint graph to ${out.absolutePath}")
    Console.log(
      "  ${data.waypoints.size} waypoint(s), " +
        "${data.shortcuts.size} shortcut(s), " +
        "${data.trailheads.size} trailhead(s)",
    )
    Console.log("Open in a browser: file://${out.absolutePath}")
    return CommandLine.ExitCode.OK
  }

  companion object {
    /**
     * Default output filename — colocated with the user's `cwd`. Picked over
     * `~/Downloads/...` so the file is easy to find right after running and easy to
     * `.gitignore` if a workspace wants to drop the artifact next to its `trails/`.
     */
    private const val DEFAULT_OUT_PATH = "./waypoint-graph.html"
  }
}
