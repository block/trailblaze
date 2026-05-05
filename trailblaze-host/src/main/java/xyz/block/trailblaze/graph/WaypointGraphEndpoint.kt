package xyz.block.trailblaze.graph

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Ktor route registration for the live waypoint graph view served by the daemon.
 *
 * Two endpoints:
 *  - `GET /waypoints/graph` → the React Flow HTML page with graph data inlined.
 *  - `GET /waypoints/graph.json` → the same data as JSON for tooling that wants the
 *    raw shape (e.g. piping into another visualizer, or scripting around it).
 *
 * Both endpoints accept `?root=<path>` to override the workspace root; if absent, they
 * fall back to the [defaultRootProvider] (which the desktop wires to its current
 * "trails directory" setting). The override exists so an operator can poke a different
 * waypoint root without restarting the daemon — useful for comparing branches.
 *
 * ## Why register from trailblaze-host instead of trailblaze-server
 *
 * The graph data builder ([WaypointGraphBuilder]) needs `WaypointDiscovery` and
 * `loadWaypoints` from trailblaze-host. Server depends on host *not* the other way, so
 * the endpoint cannot live in trailblaze-server's routing block directly — it would
 * pull host-only types into the lower layer. Instead this object exposes a `register`
 * extension that the server's start-up code invokes via a callback param.
 */
object WaypointGraphEndpoint {

  private const val PATH_HTML = "/waypoints/graph"
  private const val PATH_JSON = "/waypoints/graph.json"

  /**
   * Registers both routes against [routing]. Call from inside a Ktor `routing { }` or
   * directly from the server's `additionalRouteRegistration` callback.
   *
   * @param routing the Ktor routing block.
   * @param defaultRootProvider returns the fallback `--root` filesystem path when the
   *        request omits `?root=`. Typically `() -> File(savedSettingsRepo.trailsDir)`.
   *        Called per-request so a settings change reflects without re-registering.
   */
  fun register(
    routing: Routing,
    defaultRootProvider: () -> File,
  ) {
    routing.apply {
      get(PATH_HTML) {
        val root = resolveRoot(call.parameters["root"], defaultRootProvider)
        val html = withContext(Dispatchers.IO) {
          val data = WaypointGraphBuilder.build(root, liveSourceLabel = "live · daemon")
          WaypointGraphHtmlRenderer.render(data)
        }
        // text/html — Ktor's respondText writes UTF-8 by default, which round-trips
        // non-ASCII waypoint descriptions cleanly without browser charset-guess
        // heuristics tripping on em-dashes / smart-quotes.
        call.respondText(text = html, contentType = ContentType.Text.Html)
      }

      get(PATH_JSON) {
        val root = resolveRoot(call.parameters["root"], defaultRootProvider)
        val data = withContext(Dispatchers.IO) {
          WaypointGraphBuilder.build(root, liveSourceLabel = "live · daemon")
        }
        // Reuse the renderer's JSON encoder configuration so HTML and JSON stay in
        // sync — if a future field is added, both surfaces emit it without separate
        // serializer plumbing.
        val json = kotlinx.serialization.json.Json { encodeDefaults = true }
        call.respondText(
          text = json.encodeToString(WaypointGraphData.serializer(), data),
          contentType = ContentType.Application.Json,
        )
      }
    }
  }

  /**
   * Resolves the request-scoped `?root=` query param, falling back to the default
   * provider when absent or blank. Empty strings are treated as absent — a query
   * param like `?root=` (which can happen when a UI clears its input) shouldn't
   * silently route to a non-existent file path; using the default is friendlier.
   *
   * Failure to resolve a `File` (provider throws) propagates upward as a 500, which
   * is correct: the desktop misconfigured something and we'd rather see the stack
   * trace in logs than render an empty graph and look fine.
   */
  private fun resolveRoot(rawParam: String?, defaultProvider: () -> File): File {
    val trimmed = rawParam?.trim().orEmpty()
    return if (trimmed.isEmpty()) {
      defaultProvider()
    } else {
      File(trimmed).also {
        if (!it.exists() || !it.isDirectory) {
          // Don't fail the request — the discovery layer is robust to bad roots and
          // will emit an empty filesystem-walk while still loading classpath packs.
          // Surface it to the operator's log so a typo isn't invisible.
          Console.error(
            "[WaypointGraphEndpoint] ?root=$trimmed is not a directory. " +
              "Filesystem-walk waypoints will be empty; classpath-bundled packs still load.",
          )
        }
      }
    }
  }
}
