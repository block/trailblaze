package xyz.block.trailblaze.host.recording.rpc

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.report.ReportTemplateResolver

/**
 * Serves the Compose/WASM device viewer at the `/devices` URL (and every sub-path).
 *
 * The page reuses the bundled report template ([ReportTemplateResolver.resolveTemplate])
 * — a single self-contained HTML file with `composeApp.js` and every `.wasm` binary
 * inlined as gzip+base64 strings, plus a runtime loader that intercepts the WASM
 * bundle's `fetch` / `XMLHttpRequest` / `WebAssembly.instantiateStreaming` calls and
 * serves them from memory. The browser never fetches anything else under `/devices`,
 * so no separate JS/WASM routes are needed.
 *
 * The Compose entry point ([xyz.block.trailblaze.ui.main]) branches on
 * `window.location.pathname`: bare `/devices` renders the single-device picker
 * ([xyz.block.trailblaze.ui.devices.WebDevicesPage]), and `/devices/all` renders the
 * multi-device live grid ([xyz.block.trailblaze.ui.devices.WebDevicesGridPage]). Both
 * sub-paths get the same HTML bundle — the routing branch is client-side. The empty
 * `window.trailblaze_report_compressed` placeholder shipped in the template is fine —
 * the devices views don't read session data.
 *
 * Registration mirrors [xyz.block.trailblaze.graph.WaypointGraphEndpoint] — lives in
 * trailblaze-host and is injected into the server via `additionalRouteRegistration`.
 */
object DevicesPageEndpoint {

  private const val PATH = "/devices"

  /**
   * Sub-path matcher that lets the client-side router (`main.kt`) own URLs like
   * `/devices/all`. Ktor matches the longer pattern in addition to the bare `/devices`,
   * so a direct hit on either form serves the same WASM bundle and the in-bundle
   * pathname branch decides which page to render.
   *
   * **Ordering hazard:** this wildcard catches *every* `/devices/<anything>`. If a future
   * HTTP-layer route like `/devices/api/...` or `/devices/rpc/...` is ever added under the
   * same routing tree, declare it **before** this wildcard or it will be shadowed.
   * Routes under `/devices/` that only need client-side rendering should keep adding their
   * paths to the in-bundle pathname branch in `main.kt` — they don't need new HTTP routes
   * here.
   */
  private const val SUBPATH = "/devices/{...}"

  fun register(routing: Routing) {
    // Single handler body, called from both routes — the only difference between the bare
    // `/devices` and the `/devices/{...}` wildcard registration is the pattern. Pulling the
    // body out keeps future template/error changes applying uniformly.
    val serveBundle: suspend ApplicationCall.() -> Unit = {
      val template = ReportTemplateResolver.resolveTemplate()
      if (template == null) {
        respondText(missingBundleMessage(), ContentType.Text.Plain, HttpStatusCode.NotFound)
      } else {
        respondText(text = template.readText(), contentType = ContentType.Text.Html)
      }
    }
    routing.apply {
      get(PATH) { call.serveBundle() }
      get(SUBPATH) { call.serveBundle() }
    }
  }

  private fun missingBundleMessage(): String =
    "WASM bundle not found. Rebuild with `-Ptrailblaze.wasm=true` (or " +
      "`BUNDLE_WASM=true ./scripts/install-trailblaze-source.sh`) so the report " +
      "template is bundled into the uber JAR."
}
