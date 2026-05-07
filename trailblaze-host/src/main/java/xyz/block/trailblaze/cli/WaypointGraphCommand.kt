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
    names = ["--target"],
    paramLabel = "<id>",
    description = [
      "Pack id to scope the graph to (e.g. `myapp`, `clock`). Filters the rendered " +
        "graph to waypoints whose id starts with `<id>/` and drops shortcuts/trailheads " +
        "that cross out of that scope. Also resolves --root to " +
        "<workspace>/packs/<id>/waypoints/ when no explicit --root is given.",
    ],
  )
  var targetId: String? = null

  @Option(
    names = ["--platform"],
    paramLabel = "<id>",
    description = [
      "Platform to scope the graph to (`android`, `ios`, or `web`). Filters waypoints " +
        "whose source path is under `waypoints/<platform>/...` and drops " +
        "shortcuts/trailheads that cross out of that scope. Combine with --target to " +
        "produce a single (target, platform) map.",
    ],
  )
  var platform: String? = null

  @Option(
    names = ["--root"],
    paramLabel = "<path>",
    description = [
      "Filesystem directory to scan for *.waypoint.yaml files (default: " +
        "$DEFAULT_WAYPOINT_ROOT, resolved against the current working directory). " +
        "Overrides --target's root resolution. Pack-bundled waypoints from the classpath are always included regardless of this flag.",
    ],
  )
  var rootOverride: File? = null

  @Option(
    names = ["--out", "-o"],
    description = [
      "Output HTML file path (default: $DEFAULT_OUT_PATH, relative to the current " +
        "directory). The default scopes the artifact to .trailblaze/reports/ — a " +
        "stack-agnostic, generated-output-only subpath that consumers can gitignore " +
        "without having to blanket-ignore the rest of .trailblaze/ (which may hold " +
        "things they want to commit). Parent directories are created if missing; " +
        "the file is overwritten if present.",
    ],
  )
  var out: File = File(DEFAULT_OUT_PATH)

  override fun call(): Int {
    val root = resolveWaypointRoot(rootOverride = rootOverride, targetId = targetId)
    if (root.exists() && !root.isDirectory) {
      Console.error(
        "Warning: --root is not a directory: ${root.absolutePath} " +
          "(filesystem-walk waypoints will be empty; classpath-bundled packs still load)",
      )
    }

    val normalizedPlatform = platform?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val allowedPlatforms = setOf("android", "ios", "web")
    if (normalizedPlatform != null && normalizedPlatform !in allowedPlatforms) {
      Console.error(
        "Warning: --platform=$platform is not one of $allowedPlatforms; " +
          "the filter will match nothing. Did you mean one of those?",
      )
    }

    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    val data = WaypointGraphBuilder.build(
      root = root,
      // The CLI emits a frozen snapshot — embed the timestamp + "snapshot" tag in the
      // generated note so a viewer of the saved file knows it isn't live. The daemon
      // endpoint uses "live" for the same field.
      liveSourceLabel = "snapshot · $timestamp",
      // Use the caller's actual cwd (not the daemon's) when resolving the workspace
      // anchor for pack-tool discovery — the standalone CLI runs server-side under
      // the daemon process, so `Paths.get("")` would point at the daemon's cwd and
      // miss the user's workspace tools.
      fromPath = CliCallerContext.callerCwd(),
      targetFilter = targetId?.takeIf { it.isNotBlank() },
      platformFilter = normalizedPlatform,
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
      maybeWarnNoTarget(rootOverride, targetId, resultIsEmpty = true)
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
     * Default output path — `./.trailblaze/reports/waypoint-graph.html`,
     * resolved relative to the user's cwd. Two design notes worth keeping
     * straight:
     *
     * 1. **Why `.trailblaze/`?** It's the tool-namespaced convention this
     *    project already uses for tool-owned data (e.g. settings, sessions),
     *    and it's stack-agnostic — works the same in a Gradle / Node-TS /
     *    Python / no-build-system consumer, none of which would necessarily
     *    have a `build/`, `dist/`, or `target/` directory.
     *
     * 2. **Why the `reports/` subpath, not `.trailblaze/` directly?**
     *    Because not everything under `.trailblaze/` is necessarily
     *    "must-ignore" content — a Trailblaze consumer might reasonably
     *    want to commit some of it (team-shared settings, authored
     *    configuration). Scoping the regenerated artifact to a dedicated
     *    `reports/` subdirectory means a careful consumer can gitignore
     *    just `.trailblaze/reports/` without having to blanket-ignore the
     *    whole namespace. Consumers who *do* want to ignore everything
     *    Trailblaze writes still can — `.trailblaze/` covers it.
     *
     * Picked over `./waypoint-graph.html` (which forced every consumer to
     * add a manual gitignore entry for a single file) and over
     * `~/Downloads/...` (which dragged the artifact out of the repo
     * checkout where it's actually about, and made it harder to share by
     * relative path).
     */
    private const val DEFAULT_OUT_PATH = "./.trailblaze/reports/waypoint-graph.html"
  }
}
