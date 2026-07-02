package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.devices.TrailblazeDriverType
import xyz.block.trailblaze.toolcalls.ToolSetCatalogEntry
import xyz.block.trailblaze.toolcalls.TrailblazeToolSetCatalog
import xyz.block.trailblaze.toolcalls.getAgentToolboxForDriver
import xyz.block.trailblaze.toolcalls.toolName

// Serves the per-target app icon (the launcher/app-store icon) the web UI shows next to a
// target. Reuses the bundled `app_icon_<id>.png` resources the desktop app already ships
// (see BlockAppTargets.BlockAppIconProvider). Missing icon → 404 (the UI falls back to a
// generic glyph).
internal fun Route.appIconRoutes() {
  get("$PATH_BASE/api/app-icon/{target}") {
    val target = call.parameters["target"]?.trim()?.lowercase().orEmpty()
    // Only simple ids — never let the id compose a path into another resource.
    if (target.isEmpty() || !target.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val bytes = withContext(Dispatchers.IO) {
      TrailRunnerEndpoint::class.java.classLoader.getResourceAsStream("app_icon_$target.png")
        ?.use { it.readAllBytes() }
    }
    if (bytes == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    call.response.headers.append("Cache-Control", "max-age=86400")
    call.respondBytes(bytes = bytes, contentType = ContentType.Image.PNG, status = HttpStatusCode.OK)
  }
}

internal fun Route.runToolsRoutes(deps: TrailRunnerDeps) {
  // Given a target app id and the device's driver, return the toolsets — and the tools
  // inside each — that actually register for a run against that target. This mirrors the
  // agent's session-start composition: the target's declared `tool_sets:` for the driver,
  // plus every `always_enabled` toolset, filtered to driver-compatible entries — the same
  // catalog + `getDeclaredToolSetIdsForDriver` the runtime uses. Resolution is fully static
  // (no device connection needed); the daemon's `availableAppTargets` already carries the
  // discovered targets.
  get("$PATH_BASE/api/run-tools") {
    val targetId = call.request.queryParameters["target"]?.trim().orEmpty()
    val driverParam = call.request.queryParameters["driver"]?.trim().orEmpty()
    val platformParam = call.request.queryParameters["platform"]?.trim().orEmpty()
    call.respondText(
      text = JSON.encodeToString(RunToolsResponse.serializer(), buildRunToolsResponse(deps, targetId, driverParam, platformParam)),
      contentType = ContentType.Application.Json,
    )
  }
}

/**
 * The toolsets (and the tools inside each) that register for a run against `targetId` on the device's
 * `driver` — the shared source for both the REST `GET /api/run-tools` route and the
 * `GetRunToolsRequest` RPC handler. Mirrors the agent's session-start composition. Empty `driver` /
 * `platform` are handled the same way the query-param route did (driver preferred, platform fallback).
 */
internal suspend fun buildRunToolsResponse(
  deps: TrailRunnerDeps,
  targetId: String,
  driverParam: String,
  platformParam: String,
): RunToolsResponse =
  withContext(Dispatchers.IO) {
    val target = deps.deviceManager?.availableAppTargets?.firstOrNull { it.id == targetId }
    val driverType = resolveDriverType(driverParam, platformParam)
    if (target == null || driverType == null) {
      RunToolsResponse(
        target = targetId,
        driver = driverParam.ifEmpty { platformParam },
        resolved = false,
        toolsets = emptyList(),
      )
    } else {
        val declaredIds = runCatching { target.getDeclaredToolSetIdsForDriver(driverType) }
          .getOrDefault(emptyList())
        val catalog = runCatching { TrailblazeToolSetCatalog.defaultEntries() }
          .getOrDefault(emptyList())
        // Authoritative set of class/YAML tool names that actually register for this
        // target+driver at session start: getAgentToolboxForDriver applies surface_to_llm
        // filtering, drops YAML tools with no config, removes the target's excluded_tools,
        // and folds in the target's own custom tools. We use it to filter each toolset's
        // tools so the tab matches what the agent sees (not the static catalog union).
        // Scripted (.ts) tools live outside this set, so they're kept as-is per toolset.
        val registered: Set<String> = runCatching {
          val tb = target.getAgentToolboxForDriver(driverType)
          tb.toolClasses.map { it.toolName().toolName }.toSet() + tb.yamlToolNames.map { it.toolName }.toSet()
        }.getOrDefault(emptySet())
        // Empty only if resolution failed; then don't filter (degrade to the catalog union)
        // rather than show nothing.
        val keep = { name: String -> registered.isEmpty() || name in registered }

        val coveredClassYaml = mutableSetOf<String>()
        val toolsets = catalog
          .filter { it.isCompatibleWith(driverType) && (it.alwaysEnabled || it.id in declaredIds) }
          .sortedWith(compareByDescending<ToolSetCatalogEntry> { it.alwaysEnabled }.thenBy { it.id })
          .map { entry ->
            val classYaml = (entry.toolClasses.map { it.toolName().toolName } + entry.yamlToolNames.map { it.toolName })
              .filter(keep)
            coveredClassYaml += classYaml
            val scripted = entry.scriptedToolNames.map { it.toolName }
            RunToolSetDto(
              id = entry.id,
              description = entry.description,
              alwaysEnabled = entry.alwaysEnabled,
              tools = (classYaml + scripted).distinct().sorted(),
            )
          }
          // Drop toolsets that surface nothing for this run (e.g. framework-only sets whose
          // tools are all surface_to_llm=false) — the tab is about what the agent can use.
          .filter { it.tools.isNotEmpty() }
          .toMutableList()

        // Tools that register for this target but belong to no declared/always toolset —
        // typically the target's own `tools:` (custom/inline) entries. Surface them so the
        // tab doesn't silently drop what the agent will actually have.
        val other = (registered - coveredClassYaml).sorted()
        if (other.isNotEmpty()) {
          toolsets += RunToolSetDto(
            id = "target-tools",
            description = "Tools this target registers directly, outside its shared toolsets.",
            alwaysEnabled = false,
            tools = other,
          )
        }

        RunToolsResponse(
          target = targetId,
          driver = driverType.name,
          resolved = true,
          toolsets = toolsets,
        )
      }
    }

// Prefer the device's exact driver (e.g. ANDROID_ONDEVICE_ACCESSIBILITY); fall back to any
// driver on the requested platform so the toolset-compatibility filter still resolves when
// only a platform string is known.
private fun resolveDriverType(driverParam: String, platformParam: String): TrailblazeDriverType? {
  if (driverParam.isNotEmpty()) {
    TrailblazeDriverType.entries.firstOrNull { it.name.equals(driverParam, ignoreCase = true) }
      ?.let { return it }
  }
  if (platformParam.isNotEmpty()) {
    return TrailblazeDriverType.entries.firstOrNull { it.platform.name.equals(platformParam, ignoreCase = true) }
  }
  return null
}
