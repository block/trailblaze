package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.llm.config.ConfigResourceSource
import xyz.block.trailblaze.llm.config.TrailblazeConfigPaths
import xyz.block.trailblaze.llm.config.platformConfigResourceSource
import xyz.block.trailblaze.util.Console
import java.io.File

// Scans the trailmap directories and groups every component file by type, so the
// Trailmaps screen can show the full structure of each trailmap (not just tools).
// Read-only inventory: names + relative paths; bodies are fetched lazily via the
// tool-source endpoint's ?path= form.
object TrailmapCatalogBuilder {

  private class Mutable {
    var displayName: String? = null
    var manifestPath: String? = null
    val tools = mutableListOf<TrailmapComponent>()
    val trailheads = mutableListOf<TrailmapComponent>()
    val systemPrompts = mutableListOf<TrailmapComponent>()
  }

  private val DISPLAY_NAME = Regex("(?m)^\\s*display_name:\\s*[\"']?(.+?)[\"']?\\s*$")
  private val CLASS_KEY = Regex("(?m)^\\s*class:\\s*\\S")

  fun build(resourceSource: ConfigResourceSource = platformConfigResourceSource()): List<TrailmapEntry> {
    val dir = TrailblazeConfigPaths.TRAILMAPS_DIR
    fun discover(suffix: String): Map<String, String> =
      runCatching { resourceSource.discoverAndLoadRecursive(dir, suffix) }.getOrElse {
        Console.log("[TrailmapCatalogBuilder] discovery of '$suffix' failed: ${it.message}")
        emptyMap()
      }

    val acc = sortedMapOf<String, Mutable>()
    fun tm(id: String) = acc.getOrPut(id) { Mutable() }
    fun rel(r: String) = "$dir/$r"

    // One pass over every .yaml, categorized by suffix / sub-directory.
    discover(".yaml").forEach { (r, content) ->
      val seg = r.split('/')
      val id = seg.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
      val name = seg.last()
      when {
        name == "trailmap.yaml" -> {
          val m = tm(id)
          m.manifestPath = rel(r)
          m.displayName = DISPLAY_NAME.find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null }
        }
        name.endsWith(".trailhead.yaml") ->
          tm(id).trailheads += TrailmapComponent(name.removeSuffix(".trailhead.yaml"), rel(r))
        name.endsWith(".tool.yaml") ->
          tm(id).tools += TrailmapComponent(
            name = name.removeSuffix(".tool.yaml"),
            relPath = rel(r),
            flavor = if (CLASS_KEY.containsMatchIn(content)) ToolFlavor.KOTLIN else ToolFlavor.YAML,
          )
        else -> {} // multi-tool .ts descriptors and stray yaml: skip in the inventory
      }
    }

    // Scripted (.ts) tools — same filtering as the tool catalog.
    discover(".ts").forEach { (r, _) ->
      val seg = r.split('/')
      val id = seg.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
      if (seg.getOrNull(1) != "tools") return@forEach
      val name = seg.last()
      if (name.endsWith(".d.ts") || name.endsWith(".test.ts")) return@forEach
      val stem = name.removeSuffix(".ts")
      if (name.startsWith("_") || stem.endsWith("_shared") || stem == "tools") return@forEach
      tm(id).tools += TrailmapComponent(stem, rel(r), flavor = ToolFlavor.SCRIPTED)
    }

    // System-prompt markdown living at the trailmap root (id/<file>.md).
    discover(".md").forEach { (r, _) ->
      val seg = r.split('/')
      val id = seg.firstOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
      if (seg.size != 2) return@forEach
      tm(id).systemPrompts += TrailmapComponent(seg.last().removeSuffix(".md"), rel(r))
    }

    return acc.map { (id, m) ->
      TrailmapEntry(
        id = id,
        displayName = m.displayName,
        manifestPath = m.manifestPath,
        tools = m.tools.sortedBy { it.name },
        trailheads = m.trailheads.sortedBy { it.name },
        systemPrompts = m.systemPrompts.sortedBy { it.name },
      )
    }
  }
}

// Where each component type lives inside a trailmap, and the file suffix it uses.
private val TRAILMAP_COMPONENT_DIR = mapOf(
  "tools" to "tools", "trailheads" to "trailheads",
)
private val TRAILMAP_COMPONENT_SUFFIX = mapOf(
  "tools" to ".ts", "trailheads" to ".trailhead.yaml",
)
// Names may include sub-dirs (e.g. android/foo) but nothing path-escaping.
private val SAFE_COMPONENT_NAME = Regex("^[A-Za-z0-9_][A-Za-z0-9_/-]*$")

internal fun trailmapComponentSkeleton(kind: String, name: String): String = when (kind) {
  "tools" -> {
    val id = name.substringAfterLast('/')
    """
    import { trailblaze } from "@trailblaze/scripting";

    /**
     * TODO: Describe what this tool does and when the agent should use it. This text
     * is exactly what the model sees when deciding to call this tool.
     */
    export const $id = trailblaze.tool(
      { supportedPlatforms: ["android", "ios", "web"], requiresContext: true },
      async (_input, ctx) => {
        // TODO: implement. Compose other tools via ctx.tools.*, read ctx.target, etc.
        return "TODO: describe the result";
      },
    );
    """.trimIndent() + "\n"
  }
  "trailheads" -> "description: \"\"\ntrailhead:\n  to: \"\"\ntools: []\n"
  else -> ""
}

/**
 * A [NewComponentResponse] plus the HTTP status the REST route should use. The RPC handler ignores
 * [status] (it returns the body in an RpcResult); [status] keeps the REST route's exact codes:
 * a bad request shape (unknown kind, invalid name) is a 400, while a conflict (unknown trailmap,
 * path escape, already-exists, write error) is a 409 — distinct, as the route was before.
 */
internal data class NewComponentOutcome(val status: HttpStatusCode, val body: NewComponentResponse)

/**
 * Scaffolds a new component file (trailhead/tool) inside an existing
 * trailmap with a type-appropriate skeleton — the shared source for both the REST
 * `POST /api/trailmap/component` route and the `NewComponentRequest` RPC handler. Refuses to
 * overwrite. Every failure rides in `NewComponentResponse.ok=false` + `error`.
 */
internal suspend fun buildNewComponentResponse(request: NewComponentRequest): NewComponentOutcome {
  val kind = request.kind.trim()
  val name = request.name.trim().removeSuffix("/")
  val trailmap = request.trailmap.trim()
  val dir = TRAILMAP_COMPONENT_DIR[kind]
  val suffix = TRAILMAP_COMPONENT_SUFFIX[kind]
  if (dir == null || suffix == null) {
    return NewComponentOutcome(HttpStatusCode.BadRequest, NewComponentResponse(ok = false, error = "unknown component kind '$kind'"))
  }
  if (name.isEmpty() || !SAFE_COMPONENT_NAME.matches(name) || name.contains("..")) {
    return NewComponentOutcome(
      HttpStatusCode.BadRequest,
      NewComponentResponse(ok = false, error = "invalid name — use letters, numbers, _ - / only"),
    )
  }
  return withContext(Dispatchers.IO) {
    val base = ToolSourceFiles.trailmapBaseDir(trailmap)
      ?: return@withContext NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = "unknown trailmap '$trailmap'"))
    val relInside = "$dir/$name$suffix"
    val file = File(base, relInside)
    val baseCanon = base.canonicalPath
    if (!file.canonicalPath.startsWith(baseCanon + File.separator)) {
      return@withContext NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = "resolved path escapes the trailmap"))
    }
    if (file.exists()) {
      return@withContext NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = "a $kind named '$name' already exists"))
    }
    runCatching {
      file.parentFile?.mkdirs()
      file.writeText(trailmapComponentSkeleton(kind, name))
      NewComponentOutcome(
        HttpStatusCode.OK,
        NewComponentResponse(
          ok = true,
          relPath = "${TrailblazeConfigPaths.TRAILMAPS_DIR}/$trailmap/$relInside",
          savedPath = file.absolutePath,
        ),
      )
    }.getOrElse { NewComponentOutcome(HttpStatusCode.Conflict, NewComponentResponse(ok = false, error = it.message ?: "could not write file")) }
  }
}

internal fun Route.trailmapRoutes() {
  get("$PATH_BASE/api/trailmaps") {
    val entries = withContext(Dispatchers.IO) {
      runCatching { TrailmapCatalogBuilder.build() }.getOrElse {
        Console.log("[TrailRunnerEndpoint] GET /api/trailmaps build failed: ${it.message}")
        emptyList()
      }
    }
    call.respondText(
      text = JSON.encodeToString(TrailmapsResponse.serializer(), TrailmapsResponse(entries)),
      contentType = ContentType.Application.Json,
    )
  }

  // Scaffold a new component file (trailhead/tool) inside an
  // existing trailmap, with a type-appropriate skeleton. Refuses to overwrite.
  post("$PATH_BASE/api/trailmap/component") {
    val body = runCatching { call.receive<NewComponentRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, NewComponentResponse(ok = false, error = "invalid request body"))
      return@post
    }
    val outcome = buildNewComponentResponse(body)
    call.respond(outcome.status, outcome.body)
  }
}
