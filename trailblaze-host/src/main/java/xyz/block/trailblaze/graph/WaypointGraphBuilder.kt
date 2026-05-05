package xyz.block.trailblaze.graph

import xyz.block.trailblaze.api.ImageFormatDetector
import xyz.block.trailblaze.config.ToolYamlLoader
import xyz.block.trailblaze.ui.tabs.waypoints.loadWaypoints
import java.io.File
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Assembles a [WaypointGraphData] snapshot from a workspace root path, suitable for
 * either the in-app browser endpoint or the standalone CLI export. Pure function that
 * takes (root path, optional source label) and returns a fully-loaded graph — no
 * network, no UI dependencies.
 *
 * ## Why this lives in trailblaze-host (not trailblaze-server)
 *
 * Waypoint discovery (`WaypointDiscovery.discover`) and the existing example-loader
 * pipeline (`loadWaypoints`) live in trailblaze-host. The server module depends on
 * trailblaze-host (not the other way around — the server is a substrate, the host is
 * the surface), so co-locating the graph builder with discovery avoids the inversion of
 * pulling host-only types into the server module just for one endpoint.
 *
 * The Ktor endpoint that serves graph JSON ([WaypointGraphEndpoint]) registers as a
 * route on the server's routing block via a callback the desktop app passes when it
 * starts the daemon — that's how host-side data reaches a server-side request without
 * the dep direction flipping.
 */
object WaypointGraphBuilder {

  /**
   * Builds a snapshot. The expensive bits (filesystem walk, classpath pack scan,
   * example decoding) all happen here synchronously — callers are responsible for
   * pushing the call onto an IO dispatcher when invoking from a UI/RPC thread.
   *
   * @param root filesystem root to scan for `*.waypoint.yaml` files. Pack-bundled
   *             waypoints from the classpath are always included regardless.
   * @param liveSourceLabel populates the trailing `(live)` / `(snapshot)` suffix in the
   *             generated note. The desktop endpoint passes "live"; the CLI passes a
   *             timestamp-based label so users opening a saved file know it's frozen.
   */
  @OptIn(ExperimentalEncodingApi::class)
  fun build(
    root: File,
    liveSourceLabel: String = "live",
  ): WaypointGraphData {
    val output = loadWaypoints(root)

    val nodes = output.items.map { item ->
      val def = item.definition
      val screenshotBytes = item.example?.screenshotBytes?.takeIf { it.isNotEmpty() }
      // Inline screenshot as a data URI. Format-sniffing here keeps the generated HTML
      // self-contained (no separate `?id=...&type=...` content-type negotiation needed).
      // Sniffing via the canonical [ImageFormatDetector] — same util the screenshotSaver
      // and capture-example call sites use, so the whole pipeline picks the same answer
      // for the same bytes.
      val dataUri = screenshotBytes?.let { bytes ->
        val mime = ImageFormatDetector.detectFormat(bytes).mimeType
        "data:$mime;base64,${Base64.encode(bytes)}"
      }
      // Derive platform from the source-label path (`packs/<pack>/waypoints/<platform>/...`).
      // The id no longer carries it post-URL-rename — the platform lives on disk now and
      // gets surfaced here so the front-end filter pills don't have to parse it themselves.
      val platform = item.sourceLabel?.let { label ->
        when {
          "/android/" in label -> "android"
          "/ios/" in label -> "ios"
          "/web/" in label -> "web"
          else -> null
        }
      }
      WaypointGraphNode(
        id = def.id,
        description = def.description,
        screenshotDataUri = dataUri,
        sourceLabel = item.sourceLabel,
        platform = platform,
        // Selector entries surface in the detail panel — they answer "how
        // does the matcher know this screen is <id>?". Pulled straight from
        // the WaypointDefinition so what the panel shows is exactly what the
        // matcher checks.
        required = def.required,
        forbidden = def.forbidden,
      )
    }

    val toolConfigs = try {
      ToolYamlLoader.discoverShortcutsAndTrailheads()
    } catch (e: Exception) {
      // Loader failures are non-fatal for the graph view — we'd rather render the
      // node grid with no edges than fail the whole page on a single bad YAML.
      // Server-side: the load already logs its own warning via the lenient-load
      // path, so we don't double-log here.
      emptyMap()
    }

    val shortcuts = toolConfigs.mapNotNull { (toolName, config) ->
      config.shortcut?.let { meta ->
        WaypointGraphShortcut(
          id = toolName.toolName,
          description = config.description,
          from = meta.from,
          to = meta.to,
          variant = meta.variant,
        )
      }
    }

    val trailheads = toolConfigs.mapNotNull { (toolName, config) ->
      config.trailhead?.let { meta ->
        WaypointGraphTrailhead(
          id = toolName.toolName,
          description = config.description,
          to = meta.to,
        )
      }
    }

    val timestamp = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    val generatedNote = "Generated $timestamp from ${root.absolutePath} ($liveSourceLabel)"

    return WaypointGraphData(
      waypoints = nodes,
      shortcuts = shortcuts,
      trailheads = trailheads,
      generatedNote = generatedNote,
    )
  }

}
