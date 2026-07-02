package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml

// Upper bound for a single on-device tool execution before we give up and report
// a timeout, so a wedged device can't hang the request thread indefinitely.
private const val TOOL_RUN_TIMEOUT_MS = 5L * 60 * 1000

/**
 * Per-tool usage counts (distinct trails per tool id) — the shared source for both the REST
 * `GET /api/tool-usage-counts` route and the `GetToolUsageCountsRequest` RPC handler.
 */
internal suspend fun buildToolUsageCountsResponse(deps: TrailRunnerDeps): ToolUsageCountsResponse =
  withContext(Dispatchers.IO) {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    // One pass over every trail file: each "- <key>:" recording entry is a
    // candidate tool id; count distinct trails per key. Framework keys
    // (tapOn, maestro, …) are counted too but simply never looked up.
    val rx = Regex("(?m)^\\s*-\\s*([A-Za-z0-9_]+)\\s*:")
    val map = mutableMapOf<String, Int>()
    TrailIndexBuilder.scanAll(primary = primary, extras = extras).forEach { entry ->
      val file = resolveTrailFile(entry.id.split("/"), primary, extras)?.second ?: return@forEach
      val ids = runCatching { rx.findAll(file.readText()).map { it.groupValues[1] }.toSet() }.getOrDefault(emptySet())
      ids.forEach { map[it] = (map[it] ?: 0) + 1 }
    }
    ToolUsageCountsResponse(map)
  }

/**
 * The trails that use a given tool id — the shared source for both the REST `GET /api/tool-usages`
 * route and the `GetToolUsagesRequest` RPC handler. A blank id yields an empty result (the UI never
 * queries with an empty id; the REST route rejects it with a 400 before reaching here).
 */
internal suspend fun buildToolUsagesResponse(deps: TrailRunnerDeps, toolId: String): TrailIndexResponse {
  if (toolId.isBlank()) return TrailIndexResponse(emptyList())
  return withContext(Dispatchers.IO) {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    // A trail uses a tool when the tool id appears as a yaml list-mapping key in
    // a recording's tools block ("- <id>:" / "- <id>: {}").
    val rx = Regex("(?m)^\\s*-\\s*${Regex.escape(toolId)}\\s*:")
    val trails = TrailIndexBuilder.scanAll(primary = primary, extras = extras).filter { entry ->
      val resolved = resolveTrailFile(entry.id.split("/"), primary, extras)
      resolved != null && runCatching { rx.containsMatchIn(resolved.second.readText()) }.getOrDefault(false)
    }
    TrailIndexResponse(trails)
  }
}

/** A catalog tool that composes another tool — the rows of the "Used by other tools" section. */
@Serializable
internal data class ToolToolRefDto(val id: String, val trailmap: String, val flavor: String)

/** Tools that dispatch the queried tool (its tool->tool callers). */
@Serializable
internal data class ToolToolUsagesResponse(val usedBy: List<ToolToolRefDto> = emptyList())

/**
 * The catalog tools that dispatch [toolId] via `ctx.tools.<id>` (scripted) or `invokeFrameworkTool`
 * (Kotlin) — the source for the REST `GET /api/tool-tool-usages` route. This is the composition the
 * trail-usage count can't see: a helper reached only through another tool has 0 trail uses but is
 * still used. A blank id yields an empty result (the REST route rejects it before reaching here).
 */
internal suspend fun buildToolToolUsagesResponse(toolId: String): ToolToolUsagesResponse {
  if (toolId.isBlank()) return ToolToolUsagesResponse()
  return withContext(Dispatchers.IO) {
    val catalog = ToolCatalogBuilder.build()
    val catalogCallers = catalog
      .filter { it.id != toolId && ToolCatalogBuilder.referencedToolIds(it).contains(toolId) }
      .map { ToolToolRefDto(it.id, it.trailmap, it.flavor.name.lowercase()) }
    // Also count Kotlin orchestrators registered via a toolset/trailhead with no .tool.yaml (not
    // catalog entries), e.g. myapp_android_signInViaUI dispatching its on-device steps. They carry
    // no trailmap (not file-backed in the catalog); the UI shows them as a non-navigable kotlin row.
    val orchestratorCallers = ToolCatalogBuilder.registeredKotlinCallerEdges(catalog.mapTo(HashSet()) { it.id })
      .filter { (callerId, callees) -> callerId != toolId && toolId in callees }
      .map { (callerId, _) -> ToolToolRefDto(callerId, "", "kotlin") }
    val callers = (catalogCallers + orchestratorCallers)
      .distinctBy { it.id }
      .sortedWith(compareBy({ it.trailmap }, { it.id }))
    ToolToolUsagesResponse(usedBy = callers)
  }
}

/** Per-tool counts of how many OTHER catalog tools dispatch each tool (the tool->tool caller count). */
@Serializable
internal data class ToolToolUsageCountsResponse(val counts: Map<String, Int> = emptyMap())

/**
 * For every catalog tool, how many other catalog tools dispatch it — the bulk source for the sidebar
 * "used by N tools" chip (one pass over the catalog, vs. one `tool-tool-usages` request per row).
 * Mirrors [buildToolUsageCountsResponse] (trail counts); this is the tool->tool dimension.
 */
internal suspend fun buildToolToolUsageCountsResponse(): ToolToolUsageCountsResponse =
  withContext(Dispatchers.IO) {
    val catalog = ToolCatalogBuilder.build()
    val ids = catalog.mapTo(HashSet()) { it.id }
    val counts = HashMap<String, Int>()
    catalog.forEach { entry ->
      // referencedToolIds is a per-entry SET, so each caller counts a callee at most once.
      ToolCatalogBuilder.referencedToolIds(entry).forEach { ref ->
        if (ref in ids) counts[ref] = (counts[ref] ?: 0) + 1
      }
    }
    // Fold in Kotlin orchestrators registered via toolset/trailhead with no .tool.yaml (not catalog
    // entries) — e.g. myapp_android_signInViaUI -> its on-device step helpers — so those helpers show
    // a tool-caller count instead of reading as unused. Each orchestrator's edge set is already
    // deduped, so it adds at most one to each callee it dispatches.
    ToolCatalogBuilder.registeredKotlinCallerEdges(ids).forEach { (_, callees) ->
      callees.forEach { ref -> if (ref in ids) counts[ref] = (counts[ref] ?: 0) + 1 }
    }
    ToolToolUsageCountsResponse(counts)
  }

/**
 * The favorited trail ids — the shared source for both the REST `GET /api/favorites` route and the
 * `GetFavoritesRequest` RPC handler.
 */
internal suspend fun buildFavoritesResponse(): FavoritesResponse =
  withContext(Dispatchers.IO) { FavoritesResponse(TrailFavorites.list()) }

/**
 * Resolves a tool/component's source text by Kotlin FQN (`className`) or trailmap-relative resource
 * path (`path`), or `null` if neither is given or it doesn't resolve — the shared source for both the
 * REST `GET /api/tool-source` route and the `GetToolSourceRequest` RPC handler. Neither key given is
 * a misuse both paths reject up front (REST 400 / RPC failure); a key that resolves to nothing is a
 * `null` the REST route renders as 404 and the RPC handler as `ToolSourceResponse(source = null)`.
 */
internal suspend fun resolveToolSource(className: String?, path: String?): String? {
  val fqn = className?.trim().orEmpty()
  val resourcePath = path?.trim().orEmpty()
  if (fqn.isEmpty() && resourcePath.isEmpty()) return null
  return withContext(Dispatchers.IO) {
    when {
      fqn.isNotEmpty() -> ToolSourceFiles.sourceFor(fqn)
      else -> ToolSourceFiles.fileForResource(resourcePath)?.let { runCatching { it.readText() }.getOrNull() }
    }
  }
}

/**
 * Adds (`favorite=true`) or removes (`favorite=false`) a favorited trail id and returns the updated
 * list — the shared source for both the REST `POST`/`DELETE /api/favorites` routes and the
 * `SetFavoriteRequest` RPC handler. A blank id is a no-op that returns the current list (the REST
 * routes reject it with a 400 before reaching here).
 */
internal suspend fun buildSetFavoriteResponse(id: String, favorite: Boolean): FavoritesResponse =
  withContext(Dispatchers.IO) {
    val trimmed = id.trim()
    if (trimmed.isEmpty()) {
      FavoritesResponse(TrailFavorites.list())
    } else {
      FavoritesResponse(if (favorite) TrailFavorites.add(trimmed) else TrailFavorites.remove(trimmed))
    }
  }

/**
 * Overwrites a tool/component source file resolved by Kotlin FQN (`className`) or trailmap-relative
 * resource path (`path`) — the shared source for both the REST `PUT /api/tool-source` route and the
 * `ToolSourceSaveRequest` RPC handler. Returns a [SaveTrailOutcome] so REST keeps its status codes
 * (400 blank source, 404 unresolvable target, 500 write error) while the RPC handler uses only the
 * body (the error rides in `success=false` + `error`).
 */
internal suspend fun buildToolSourceSaveResponse(request: ToolSourceSaveRequest): SaveTrailOutcome {
  if (request.source.isBlank()) {
    return SaveTrailOutcome(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "source is required"))
  }
  // Resolution goes through ToolSourceFiles: an index lookup, plus a base-dir-join fallback for
  // files created after the lazy index was built. That fallback joins caller input, so it is
  // canonical-containment-checked (rejects `..` escapes) to keep a write inside the trailmap dir.
  val file = withContext(Dispatchers.IO) {
    when {
      !request.className.isNullOrBlank() -> ToolSourceFiles.fileForClass(request.className.trim())
      !request.path.isNullOrBlank() -> ToolSourceFiles.fileForResource(request.path.trim())
      else -> null
    }
  }
  if (file == null || !file.exists()) {
    return SaveTrailOutcome(
      HttpStatusCode.NotFound,
      SaveTrailResponse(success = false, error = "no tool source file found for that class or path"),
    )
  }
  val result = withContext(Dispatchers.IO) { runCatching { file.writeText(request.source); file.absolutePath } }
  return if (result.isSuccess) {
    SaveTrailOutcome(HttpStatusCode.OK, SaveTrailResponse(success = true, savedPath = result.getOrThrow()))
  } else {
    SaveTrailOutcome(
      HttpStatusCode.InternalServerError,
      SaveTrailResponse(success = false, error = result.exceptionOrNull()?.message ?: "unknown error"),
    )
  }
}

/**
 * Runs the recorded tools in a one-step trail yaml on the device, blocking, and returns the joined
 * result inline — the shared source for both the REST `POST /api/tool/run` route and the
 * `ToolRunRequest` RPC handler. No executor, a blank/unparseable yaml, no tools, a timeout, or a
 * thrown tool all ride in `ToolRunResponse.success=false` + `error`.
 */
internal suspend fun buildToolRunResponse(deps: TrailRunnerDeps, request: ToolRunRequest): ToolRunResponse {
  // On-device execution for the Trailmaps "Run on device" tab: decode the one-step trail yaml the
  // tab builds, then run each recorded tool blocking via the injected executor. No session, no agent.
  val executor = deps.toolExecutor
    ?: return ToolRunResponse(success = false, error = "tool execution isn't available in this daemon")
  val yaml = request.yaml
  if (yaml.isBlank()) {
    return ToolRunResponse(success = false, error = "yaml is required")
  }
  val tools = runCatching {
    val doc = createTrailblazeYaml().decodeTrailDocument(yaml)
    when (doc) {
      is xyz.block.trailblaze.yaml.unified.TrailDocument.V1 ->
        doc.items.filterIsInstance<xyz.block.trailblaze.yaml.TrailYamlItem.PromptsTrailItem>()
          .flatMap { it.promptSteps }
          .mapNotNull { it.recording }
          .flatMap { it.tools }
          .map { it.trailblazeTool }
      else -> emptyList()
    }
  }.getOrElse { e ->
    return ToolRunResponse(success = false, error = "could not parse tool yaml: ${e.message?.lineSequence()?.first()}")
  }
  if (tools.isEmpty()) {
    return ToolRunResponse(success = false, error = "no recorded tools found in the yaml")
  }
  val started = System.currentTimeMillis()
  // Bound the blocking on-device execution: a tool stuck on a wedged device would otherwise hang
  // this request (and the tab spinner) forever.
  val outcome = runCatching {
    withTimeoutOrNull(TOOL_RUN_TIMEOUT_MS) {
      val parts = mutableListOf<String>()
      for (tool in tools) parts.add(executor(tool, request.trailblazeDeviceId))
      parts.joinToString("\n")
    }
  }
  val durationMs = System.currentTimeMillis() - started
  return outcome.fold(
    onSuccess = {
      if (it == null) {
        ToolRunResponse(success = false, error = "tool timed out after ${TOOL_RUN_TIMEOUT_MS / 1000}s", durationMs = durationMs)
      } else {
        ToolRunResponse(success = true, result = it, durationMs = durationMs)
      }
    },
    onFailure = { ToolRunResponse(success = false, error = it.message ?: it.toString(), durationMs = durationMs) },
  )
}

/**
 * Reveals a tool/component source file (by Kotlin FQN or trailmap-relative path) in the OS file
 * browser, or `null` if it doesn't resolve — the shared source for both the REST
 * `POST /api/tool/reveal` route (null → 404) and the `ToolRevealRequest` RPC handler.
 */
internal suspend fun buildRevealToolSourceResponse(request: ToolRevealRequest): OkResponse? {
  val file = withContext(Dispatchers.IO) {
    when {
      !request.className.isNullOrBlank() -> ToolSourceFiles.fileForClass(request.className.trim())
      !request.path.isNullOrBlank() -> ToolSourceFiles.fileForResource(request.path.trim())
      else -> null
    }
  }
  if (file == null || !file.exists()) return null
  val ok = withContext(Dispatchers.IO) {
    runCatching {
      TrailblazeDesktopUtil.revealFileInFinder(file)
      true
    }.onFailure { Console.log("[TrailRunnerEndpoint] tool reveal failed: ${it.message}") }.getOrDefault(false)
  }
  return OkResponse(ok = ok)
}

internal fun Route.toolRoutes(deps: TrailRunnerDeps) {
  get("$PATH_BASE/api/tools") {
    val tools = withContext(Dispatchers.IO) {
      runCatching { ToolCatalogBuilder.build() }.getOrElse {
        Console.log("[TrailRunnerEndpoint] GET /api/tools tool-catalog build failed: ${it.message}")
        emptyList()
      }
    }
    call.respondText(
      text = JSON.encodeToString(ToolCatalogResponse.serializer(), ToolCatalogResponse(tools)),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/tool-source") {
    // Read a tool/component body either by Kotlin FQN (?class=) or by a
    // trailmap-relative resource path (?path=, used by the Trailmaps viewer for
    // toolsets/waypoints/shortcuts/etc.). Both resolve through the catalog index.
    val fqn = call.request.queryParameters["class"]?.trim().orEmpty()
    val path = call.request.queryParameters["path"]?.trim().orEmpty()
    if (fqn.isEmpty() && path.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "class or path is required"))
      return@get
    }
    val src = resolveToolSource(fqn, path)
    if (src == null) {
      call.respond(HttpStatusCode.NotFound)
    } else {
      call.respondText(text = src, contentType = ContentType.Text.Plain)
    }
  }

  post("$PATH_BASE/api/tool/run") {
    val body = runCatching { call.receive<ToolRunRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, ToolRunResponse(success = false, error = "yaml is required"))
      return@post
    }
    call.respond(buildToolRunResponse(deps, body))
  }

  get("$PATH_BASE/api/tool-usage-counts") {
    call.respondText(
      text = JSON.encodeToString(ToolUsageCountsResponse.serializer(), buildToolUsageCountsResponse(deps)),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/tool-usages") {
    val toolId = call.request.queryParameters["toolId"]?.trim().orEmpty()
    if (toolId.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "toolId is required"))
      return@get
    }
    call.respondText(
      text = JSON.encodeToString(TrailIndexResponse.serializer(), buildToolUsagesResponse(deps, toolId)),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/tool-tool-usages") {
    val toolId = call.request.queryParameters["toolId"]?.trim().orEmpty()
    if (toolId.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "toolId is required"))
      return@get
    }
    call.respondText(
      text = JSON.encodeToString(ToolToolUsagesResponse.serializer(), buildToolToolUsagesResponse(toolId)),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/tool-tool-usage-counts") {
    call.respondText(
      text = JSON.encodeToString(ToolToolUsageCountsResponse.serializer(), buildToolToolUsageCountsResponse()),
      contentType = ContentType.Application.Json,
    )
  }

  put("$PATH_BASE/api/tool-source") {
    val body = runCatching { call.receive<ToolSourceSaveRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "source is required"))
      return@put
    }
    val outcome = buildToolSourceSaveResponse(body)
    call.respond(outcome.status, outcome.body)
  }

  post("$PATH_BASE/api/tool/reveal") {
    val body = runCatching { call.receive<ToolRevealRequest>() }.getOrNull()
    val response = if (body == null) null else buildRevealToolSourceResponse(body)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/favorites") {
    call.respondText(
      text = JSON.encodeToString(FavoritesResponse.serializer(), buildFavoritesResponse()),
      contentType = ContentType.Application.Json,
    )
  }
  post("$PATH_BASE/api/favorites") {
    val id = runCatching { call.receive<FavoriteRequest>() }.getOrNull()?.id?.trim().orEmpty()
    if (id.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id is required"))
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(FavoritesResponse.serializer(), buildSetFavoriteResponse(id, favorite = true)),
      contentType = ContentType.Application.Json,
    )
  }
  delete("$PATH_BASE/api/favorites") {
    val id = runCatching { call.receive<FavoriteRequest>() }.getOrNull()?.id?.trim().orEmpty()
    if (id.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "id is required"))
      return@delete
    }
    call.respondText(
      text = JSON.encodeToString(FavoritesResponse.serializer(), buildSetFavoriteResponse(id, favorite = false)),
      contentType = ContentType.Application.Json,
    )
  }
}
