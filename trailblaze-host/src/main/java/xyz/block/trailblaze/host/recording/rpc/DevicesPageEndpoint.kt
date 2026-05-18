package xyz.block.trailblaze.host.recording.rpc

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Routing
import io.ktor.server.routing.get
import xyz.block.trailblaze.report.ReportTemplateResolver

/**
 * Serves the Compose/WASM device viewer at the `/devices` URL.
 *
 * The page reuses the bundled report template ([ReportTemplateResolver.resolveTemplate])
 * — a single self-contained HTML file with `composeApp.js` and every `.wasm` binary
 * inlined as gzip+base64 strings, plus a runtime loader that intercepts the WASM
 * bundle's `fetch` / `XMLHttpRequest` / `WebAssembly.instantiateStreaming` calls and
 * serves them from memory. The browser never fetches anything else under `/devices`,
 * so no separate JS/WASM routes are needed.
 *
 * The Compose entry point ([xyz.block.trailblaze.ui.main]) branches on
 * `window.location.pathname` and renders [xyz.block.trailblaze.ui.devices.WebDevicesPage]
 * when the path starts with `/devices`. The empty `window.trailblaze_report_compressed`
 * placeholder shipped in the template is fine — the devices view doesn't read session
 * data.
 *
 * Registration mirrors [xyz.block.trailblaze.graph.WaypointGraphEndpoint] — lives in
 * trailblaze-host and is injected into the server via `additionalRouteRegistration`.
 */
object DevicesPageEndpoint {

  private const val PATH = "/devices"

  fun register(routing: Routing) {
    routing.apply {
      get(PATH) {
        val template = ReportTemplateResolver.resolveTemplate()
        if (template == null) {
          call.respondText(
            "WASM bundle not found. Rebuild with `-Ptrailblaze.wasm=true` (or " +
              "`BUNDLE_WASM=true ./scripts/install-trailblaze-source.sh`) so the report " +
              "template is bundled into the uber JAR.",
            ContentType.Text.Plain,
            HttpStatusCode.NotFound,
          )
          return@get
        }
        call.respondText(text = template.readText(), contentType = ContentType.Text.Html)
      }
    }
  }
}
