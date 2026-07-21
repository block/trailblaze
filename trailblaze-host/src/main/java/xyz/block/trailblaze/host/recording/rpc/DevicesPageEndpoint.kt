package xyz.block.trailblaze.host.recording.rpc

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get

/**
 * Serves the lightweight standalone device mirror at `/devices` (and every sub-path).
 *
 * This deliberately does not depend on the report/Compose WASM bundle. The self-contained HTML
 * resource talks directly to the daemon's existing host-device RPC API: HTTP for discovery,
 * connect, and input; `/rpc-ws` for server-pushed frames. That makes mirroring available from a
 * source build without `-Ptrailblaze.wasm=true` and keeps it outside the main Trail Runner UX.
 * Bare `/devices` renders a single-device picker; `/devices/all` renders the connected-device grid.
 *
 * Registration mirrors [xyz.block.trailblaze.graph.WaypointGraphEndpoint] — lives in
 * trailblaze-host and is injected into the server via `additionalRouteRegistration`.
 */
object DevicesPageEndpoint {

  private const val PATH = "/devices"

  /**
   * Sub-path matcher that lets the page own URLs like `/devices/all`. Ktor matches the longer
   * pattern in addition to bare `/devices`; the page chooses its layout from `location.pathname`.
   *
   * **Ordering hazard:** this wildcard catches *every* `/devices/<anything>`. If a future
   * HTTP-layer route like `/devices/api/...` or `/devices/rpc/...` is ever added under the
   * same routing tree, declare it **before** this wildcard or it will be shadowed.
   * Routes under `/devices/` that only need client-side rendering do not need new HTTP routes.
   */
  private const val SUBPATH = "/devices/{...}"

  fun register(routing: Routing) {
    // Single handler body for bare `/devices` and `/devices/{...}`. The resource is packaged in
    // trailblaze-host, so the endpoint works identically from the source launcher and release JAR.
    val serveBundle: suspend ApplicationCall.() -> Unit = {
      response.headers.append(HttpHeaders.CacheControl, "no-store")
      respondText(text = standaloneMirrorHtml, contentType = ContentType.Text.Html)
    }
    routing.apply {
      get(PATH) { call.serveBundle() }
      get(SUBPATH) { call.serveBundle() }
    }
  }

  private val standaloneMirrorHtml: String by lazy {
    requireNotNull(DevicesPageEndpoint::class.java.getResource("/xyz/block/trailblaze/devices/index.html")) {
        "Missing standalone device mirror resource"
      }
      .readText()
  }
}
