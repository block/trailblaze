package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.report.utils.LogsRepo
import xyz.block.trailblaze.ui.TrailblazeDeviceManager
import xyz.block.trailblaze.ui.TrailblazeSettingsRepo
import xyz.block.trailblaze.util.Console
import java.io.File

object TrailRunnerEndpoint {

  fun register(
    routing: Routing,
    trailsRootProvider: () -> File,
    logsRepo: LogsRepo,
    settingsRepo: TrailblazeSettingsRepo? = null,
    deviceManager: TrailblazeDeviceManager? = null,
    // Core (both open-source and downstream builds supply it): the framework's resolved LLM model
    // lists for the settings model picker.
    llmModelListsProvider: (() -> Set<xyz.block.trailblaze.llm.TrailblazeLlmModelList>)? = null,
    // Downstream behavior (integrations, analytics, LLM authoring assists, pluggable capture) flows
    // in through this one seam. Defaults to the open-source no-op. See [TrailRunnerExtension].
    extension: TrailRunnerExtension = DefaultTrailRunnerExtension,
    // Gradle task the rebuild-and-restart route compiles before restarting the daemon. Defaults to
    // this module's own compile; a downstream desktop app passes the module it actually builds
    // from, so the pre-restart compile check exercises the right sources on EVERY launch path
    // (not just launches that happen to export the TRAILBLAZE_REBUILD_GRADLE_TASK override).
    rebuildGradleTask: String = DEFAULT_REBUILD_GRADLE_TASK,
  ) {
    val deps = TrailRunnerDeps(
      trailsRootProvider = trailsRootProvider,
      logsRepo = logsRepo,
      settingsRepo = settingsRepo,
      deviceManager = deviceManager,
      integrationsProvider = extension.integrationsProvider,
      integrationActionHandler = extension.integrationActionHandler,
      analyticsProvider = extension.analyticsProvider,
      analyticsCaptureStarter = extension.analyticsCaptureStarter,
      eventCaptureController = extension.eventCaptureController,
      toolExecutor = extension.toolExecutor,
      llmModelListsProvider = llmModelListsProvider,
      proposeStepsProvider = extension.proposeStepsProvider,
      reviewTrailProvider = extension.reviewTrailProvider,
      appTargetIdsProvider = extension.appTargetIdsProvider,
      rebuildGradleTask = rebuildGradleTask,
    )
    routing.apply {
      staticRoutes()
      trailRoutes(deps)
      toolRoutes(deps)
      lspRoutes(deps)
      runToolsRoutes(deps)
      appIconRoutes()
      trailmapRoutes()
      sessionRoutes(deps)
      sessionStreamRoutes(deps)
      reviewRoutes(deps)
      settingsRoutes(deps)
      runRoutes(deps)
      blazeRoutes(deps)
      recordRoutes(deps)
      daemonRoutes(deps)
      // Typed /rpc/<Name> endpoints (sessions/tools/trailmaps) — the migration target the UI's
      // generated createTrailRunnerRpcClient calls, sharing the route builders' backing logic.
      trailRunnerRpcRoutes(deps)
    }
  }
}

internal class TrailRunnerDeps(
  val trailsRootProvider: () -> File,
  val logsRepo: LogsRepo,
  val settingsRepo: TrailblazeSettingsRepo?,
  val deviceManager: TrailblazeDeviceManager?,
  val integrationsProvider: (() -> List<IntegrationDto>)?,
  val integrationActionHandler: (suspend (integrationId: String, actionId: String) -> Unit)?,
  val analyticsProvider: ((Long, Long) -> List<AnalyticsEventDto>)?,
  val analyticsCaptureStarter: ((TrailblazeDeviceId) -> AutoCloseable?)?,
  // Enables or clears capture for optional event streams associated with a run. Public Trail Runner
  // only knows about generic event streams; product-specific producers are supplied by the host app.
  val eventCaptureController: ((sessionId: String, captureEvents: Boolean) -> AutoCloseable?)?,
  // Executes a single TrailblazeTool against the currently-connected device,
  // blocking until it completes; returns the tool's result string. Drives the
  // Trailmaps "Run on device" tab (shortcuts/trailheads). Null when the daemon
  // runs without an executor (e.g. the integration test harness).
  val toolExecutor: (suspend (xyz.block.trailblaze.toolcalls.TrailblazeTool, xyz.block.trailblaze.devices.TrailblazeDeviceId?) -> String)? = null,
  // Supplies the LLM model lists the settings UI should offer: the framework's
  // built-in models plus any contributed via the Trailblaze YAML config. Null in
  // contexts (e.g. tests) that don't wire the desktop app's model resolution.
  val llmModelListsProvider: (() -> Set<xyz.block.trailblaze.llm.TrailblazeLlmModelList>)? = null,
  // Turns an objective into proposed steps for the Blaze "Create" flow. Plan-only when
  // ground=false (LLM drafts steps from the prompt alone); device-grounded when ground=true.
  // Supplied by the desktop app (which owns the LLM client + credentials); null in tests.
  val proposeStepsProvider: (suspend (objective: String, target: String?, platform: String?, ground: Boolean, deviceId: TrailblazeDeviceId?) -> List<ProposedStep>)? = null,
  // "Review my trail": critiques a session's recorded trail YAML for missing assertions and fragile
  // selectors, returning read-only suggestions. Supplied by the desktop app (owns the LLM client);
  // null in tests / when no provider is wired (the route then returns a friendly unavailable error).
  val reviewTrailProvider: (suspend (recordedYaml: String, target: String?, platform: String?) -> List<ReviewSuggestionDto>)? = null,
  // Re-runs app-target discovery against the CURRENT (live) workspace and returns the resolved
  // target ids. Used by the workspace-target-drift check to tell whether a switch changed the set
  // (which only takes effect on restart). Null in contexts without target discovery (e.g. tests).
  val appTargetIdsProvider: (() -> Set<String>)? = null,
  // Gradle task compiled by the rebuild-and-restart route before the daemon restarts. See
  // [TrailRunnerEndpoint.register].
  val rebuildGradleTask: String = DEFAULT_REBUILD_GRADLE_TASK,
)

internal const val PATH_BASE = "/trailrunner"
internal const val RESOURCE_ROOT = "xyz/block/trailblaze/trailrunner/web/"

internal val MIME_BY_EXTENSION: Map<String, ContentType> = mapOf(
  "html" to ContentType.Text.Html,
  "css" to ContentType.Text.CSS,
  "js" to ContentType.Application.JavaScript,
  "jsx" to ContentType.Text.Plain.withParameter("charset", "utf-8"),
  "json" to ContentType.Application.Json,
  "svg" to ContentType.Image.SVG,
  "png" to ContentType.Image.PNG,
  "jpg" to ContentType.Image.JPEG,
  "jpeg" to ContentType.Image.JPEG,
  "ico" to ContentType.Image.XIcon,
  "woff" to ContentType.parse("font/woff"),
  "woff2" to ContentType.parse("font/woff2"),
  "ttf" to ContentType.parse("font/ttf"),
  "map" to ContentType.Application.Json,
)

internal val JSON: Json = Json {
  encodeDefaults = true
  explicitNulls = false
}

/**
 * Serves the SPA entry document (`index.html`) with a `<base href="$PATH_BASE/">` injected into
 * `<head>`.
 *
 * `index.html` references its own assets with RELATIVE paths (`./app/...`, `./styles/...`,
 * `./favicon.svg`). The browser resolves those against the document's base URL — which, absent a
 * `<base>` tag, is the page URL with its last path segment stripped. When the UI is reached at
 * `$PATH_BASE` WITHOUT a trailing slash (e.g. behind a cloud-workstation preview proxy, which
 * forwards `localhost:$port` transparently, so the `$PATH_BASE` -> `$PATH_BASE/` redirect doesn't
 * end up changing the base the browser uses for the relative asset URLs), every relative asset
 * resolves to the host ROOT — `/app/ui.jsx`, `/styles/trailrunner.css` — and 404s. Only the
 * `<title>` renders.
 *
 * An explicit `<base href="$PATH_BASE/">` pins relative resolution to `$PATH_BASE/` regardless of
 * the trailing slash, so the assets always load. Root-absolute URLs are unaffected by `<base>`, so
 * this leaves the RPC client (`/rpc/...`) and the unpkg CDN scripts (`https://...`) untouched.
 *
 * The base path is derived from [PATH_BASE] so the injected href can never drift from the route
 * prefix the assets are actually mounted under.
 */
internal suspend fun io.ktor.server.routing.RoutingContext.respondIndexHtml(): Unit? {
  val resourcePath = "${RESOURCE_ROOT}index.html"
  val stream = withContext(Dispatchers.IO) {
    TrailRunnerEndpoint::class.java.classLoader.getResourceAsStream(resourcePath)
  }
  if (stream == null) {
    Console.log("[TrailRunnerEndpoint] missing resource: $resourcePath")
    return null
  }
  val html = withContext(Dispatchers.IO) { stream.use { it.readAllBytes() } }.decodeToString()
  call.response.headers.append("Cache-Control", "no-store")
  call.respondBytes(
    bytes = injectBaseHref(html, PATH_BASE).encodeToByteArray(),
    contentType = ContentType.Text.Html,
    status = HttpStatusCode.OK,
  )
  return Unit
}

private val BASE_TAG_REGEX = Regex("""<base\b""", RegexOption.IGNORE_CASE)
private val HEAD_OPEN_REGEX = Regex("""<head\b[^>]*>""", RegexOption.IGNORE_CASE)

/**
 * Returns [html] with `<base href="$basePath/">` inserted immediately after the opening `<head>`
 * tag, so the document's relative asset URLs resolve under [basePath] regardless of the page's
 * trailing slash. Pure (no I/O) so it can be unit-tested directly. See [respondIndexHtml] for why
 * the base tag is needed.
 *
 * Robust to `index.html` evolving:
 *  - Idempotent — if the document already declares a `<base>` (any case, with or without a space,
 *    e.g. `<base/>`, `<BASE ...>`), it's returned unchanged rather than gaining a second one.
 *  - Matches the opening head tag by pattern (`<head ...>`, any case/attributes), not a literal
 *    `<head>`, so an added `lang`/attribute or casing change can't silently skip the injection.
 *  - If no `<head>` is found, logs a loud warning and returns [html] unchanged — serving an
 *    un-based document (degraded: assets may 404 behind a path-prefix proxy) beats failing the
 *    request, but the warning makes the cause diagnosable instead of silent.
 */
internal fun injectBaseHref(html: String, basePath: String): String {
  if (BASE_TAG_REGEX.containsMatchIn(html)) return html
  val headOpen = HEAD_OPEN_REGEX.find(html)
  if (headOpen == null) {
    Console.log(
      "[TrailRunnerEndpoint] WARNING: no <head> found in index.html — serving without the injected " +
        "<base href=\"$basePath/\">. Relative assets may 404 when the UI is served behind a path-prefix proxy.",
    )
    return html
  }
  val insertAt = headOpen.range.last + 1
  return buildString {
    append(html, 0, insertAt)
    append("\n<base href=\"")
    append(basePath)
    append("/\" />")
    append(html, insertAt, html.length)
  }
}

internal suspend fun io.ktor.server.routing.RoutingContext.respondResource(relativePath: String): Unit? {
  val resourcePath = "$RESOURCE_ROOT$relativePath"
  val stream = withContext(Dispatchers.IO) {
    TrailRunnerEndpoint::class.java.classLoader.getResourceAsStream(resourcePath)
  }
  if (stream == null) {
    Console.log("[TrailRunnerEndpoint] missing resource: $resourcePath")
    return null
  }
  val bytes = withContext(Dispatchers.IO) { stream.use { it.readAllBytes() } }
  val ext = relativePath.substringAfterLast('.', "").lowercase()
  val type = MIME_BY_EXTENSION[ext] ?: ContentType.Application.OctetStream
  // The WKWebView shell now uses a persistent data store so the browser keeps
  // unpkg's immutable, versioned CDN libs across launches (they no longer
  // re-download every open). Our own app code changes on every daemon rebuild,
  // so mark it no-store: always re-fetched from localhost (fast) and never
  // served stale after a rebuild.
  call.response.headers.append("Cache-Control", "no-store")
  call.respondBytes(bytes = bytes, contentType = type, status = HttpStatusCode.OK)
  return Unit
}
