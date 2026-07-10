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
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import java.io.File

/**
 * The trail index (trails + empty folders) — the shared source for both the REST `GET /api/trails`
 * route and the `GetTrailsRequest` RPC handler, so the two paths can't drift.
 */
internal suspend fun buildTrailIndexResponse(deps: TrailRunnerDeps): TrailIndexResponse =
  withContext(Dispatchers.IO) {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    TrailIndexResponse(
      trails = TrailIndexBuilder.scanAll(primary = primary, extras = extras),
      folders = TrailIndexBuilder.scanEmptyDirs(primary = primary, extras = extras),
    )
  }

/**
 * The configured trail roots (primary + extras) — the shared source for the `GET/POST/DELETE
 * /api/trails/roots` routes and the `GetTrailRootsRequest` RPC handler, so they can't drift.
 */
internal suspend fun buildTrailRootsResponse(deps: TrailRunnerDeps): TrailRootsResponse =
  withContext(Dispatchers.IO) {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    val (branch, isWorktree) = gitWorktreeInfo(primary)
    TrailRootsResponse(
      primary = primary.absolutePath,
      extras = extras.map { it.absolutePath },
      primaryBranch = branch,
      primaryIsWorktree = isWorktree,
    )
  }

/**
 * Lightweight git probe for the workspace folder: its current branch and whether it's a *linked*
 * worktree rather than the main checkout. A linked worktree's top-level `.git` is a FILE
 * (`gitdir: …/.git/worktrees/<name>`); the main checkout's `.git` is a directory — that's the
 * cheapest reliable discriminator. Returns (null, false) when the folder isn't a git checkout.
 * Shelling out to `git` is bounded (3s) and failure-tolerant so a non-repo / missing git / slow FS
 * never breaks the roots response.
 */
private fun gitWorktreeInfo(dir: File): Pair<String?, Boolean> {
  if (!dir.isDirectory) return null to false
  val topLevel = runGit(dir, "rev-parse", "--show-toplevel")?.takeIf { it.isNotBlank() } ?: return null to false
  val branch = runGit(dir, "rev-parse", "--abbrev-ref", "HEAD")?.takeIf { it.isNotBlank() && it != "HEAD" }
  val isWorktree = File(topLevel, ".git").isFile
  return branch to isWorktree
}

private fun runGit(dir: File, vararg args: String): String? = runCatching {
  val process = ProcessBuilder(listOf("git", *args))
    .directory(dir)
    .redirectErrorStream(false)
    .start()
  val output = process.inputStream.bufferedReader().use { it.readText().trim() }
  if (!process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)) {
    process.destroyForcibly()
    return@runCatching null
  }
  if (process.exitValue() != 0) null else output.ifBlank { null }
}.getOrNull()

/**
 * Trails with uncommitted git changes (modified or untracked) under the primary workspace — the
 * shared source for both the REST `GET /api/trails/edited` route and the `GetEditedTrailsRequest`
 * RPC handler. Empty when the workspace isn't a git checkout.
 */
internal suspend fun buildEditedTrailsResponse(deps: TrailRunnerDeps): EditedTrailsResponse =
  withContext(Dispatchers.IO) {
    val paths = runCatching {
      val primary = resolvePrimaryRoot(deps.trailsRootProvider)
      // Bound both git calls: a wedged index lock or a huge/networked working
      // tree must not hang the IO dispatcher indefinitely.
      fun runGit(vararg args: String): String? {
        // Discard stderr at the OS level: an undrained stderr pipe could fill (git warnings, pager
        // or config notices) and block the process past the waitFor timeout, wedging Dispatchers.IO.
        val p = ProcessBuilder(listOf("git", "-C", primary.absolutePath) + args)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
        // Drain stdout on a daemon thread so the waitFor timeout actually governs: readText() would
        // block until the pipe hits EOF, so a wedged git holding its pipe open would hang the IO
        // dispatcher and never time out. Daemon + named so a timed-out drain can't block JVM shutdown
        // or leak a non-daemon thread per call.
        val buf = StringBuilder()
        val reader = kotlin.concurrent.thread(isDaemon = true, name = "trailrunner-git-stdout") {
          p.inputStream.bufferedReader().forEachLine { synchronized(buf) { buf.appendLine(it) } }
        }
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); reader.join(500); return null }
        reader.join(1000)
        return if (p.exitValue() == 0) synchronized(buf) { buf.toString() } else null
      }
      val top = runGit("rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() } ?: return@runCatching emptyList()
      val out = runGit("status", "--porcelain", "--", ".") ?: return@runCatching emptyList()
      val primaryCanon = primary.canonicalFile
      out.lineSequence().mapNotNull { line ->
        if (line.length < 4) return@mapNotNull null
        val p = line.substring(3).substringAfter(" -> ").trim().removeSurrounding("\"")
        // Keep only trail-shaped files, via the shared recording-layer predicate: `*.trail.yaml`,
        // the NL definitions `blaze.yaml`/`trailblaze.yaml`, AND the bare unified `trail.yaml`.
        // Keying on the basename is what lets a migrated `.../trail.yaml` show up under edited-only
        // filtering — the previous `endsWith(".trail.yaml")` missed the bare unified file.
        if (!TrailRecordings.isTrailFile(p.substringAfterLast('/'))) return@mapNotNull null
        val abs = File(top, p).canonicalFile
        val rel = runCatching { abs.relativeTo(primaryCanon).invariantSeparatorsPath }.getOrNull()
        rel?.takeIf { !it.startsWith("..") }
      }.toList()
    }.getOrDefault(emptyList())
    EditedTrailsResponse(paths)
  }

/**
 * Trail detail for an id's path segments, or `null` if the id doesn't resolve to a trail file — the
 * shared source for the REST `GET /api/trail/{id...}` route (404 on null) and the
 * `GetTrailDetailRequest` RPC handler (RPC failure on null).
 */
internal suspend fun buildTrailDetailResponse(deps: TrailRunnerDeps, idSegments: List<String>): TrailDetailResponse? =
  withContext(Dispatchers.IO) {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    val resolved = resolveTrailFile(idSegments, primary, extras) ?: return@withContext null
    val (root, file) = resolved
    TrailDetailBuilder.build(root, file)
  }

/**
 * Validates a trail YAML, returning the same `ValidateTrailResponse` for both the REST
 * `POST /api/trail/validate` route and the `ValidateTrailRequest` RPC handler. A blank/absent yaml
 * is a validation failure (not an exception), matching the REST contract the editor relies on.
 */
internal fun validateTrailYaml(deps: TrailRunnerDeps, yaml: String?): ValidateTrailResponse {
  if (yaml.isNullOrBlank()) {
    return ValidateTrailResponse(valid = false, errors = listOf(ValidationErrorDto("yaml is required")))
  }
  val errors = mutableListOf<ValidationErrorDto>()
  val tb = createTrailblazeYaml()
  runCatching { tb.decodeTrailDocument(yaml) }.onFailure { e ->
    val msg = e.message ?: "could not parse trail yaml"
    val line = Regex("line (\\d+)").find(msg)?.groupValues?.get(1)?.toIntOrNull()
    errors += ValidationErrorDto(message = msg.lineSequence().first().take(300), line = line)
  }
  if (errors.isEmpty()) {
    val target = runCatching { tb.extractTrailConfig(yaml)?.target }.getOrNull()
    val dm = deps.deviceManager
    if (target != null && dm != null) {
      val known = runCatching { dm.availableAppTargets.map { it.id } }.getOrDefault(emptyList())
      if (known.isNotEmpty() && target !in known) {
        errors += ValidationErrorDto(message = "unknown target '$target' — known targets: ${known.sorted().joinToString(", ")}")
      }
    }
  }
  return ValidateTrailResponse(valid = errors.isEmpty(), errors = errors)
}

/**
 * A [SaveTrailResponse] plus the HTTP status the REST route should use for it. The RPC handlers
 * ignore [status] — they always return `RpcResult.Success(body)`, so a domain failure rides in
 * `body.success=false` + `body.error` (and survives the web UI's `dataOrNull`). [status] exists only
 * so the REST routes keep their exact status codes (400 validation, 404 not-found, 500 write error)
 * while sharing every bit of the write logic with the RPC path, so the two can't drift.
 */
internal data class SaveTrailOutcome(val status: HttpStatusCode, val body: SaveTrailResponse)

/**
 * The outcome of an add/remove trail-root mutation: the refreshed roots on success, or a validation
 * message (e.g. "not a directory: X") that the REST route renders as a 400 `{error}` and the RPC
 * handler maps to `RpcResult.Failure` (its message reaches the UI via daemon.ts's `dataOrError`).
 */
internal sealed interface TrailRootsMutationResult {
  data class Ok(val response: TrailRootsResponse) : TrailRootsMutationResult

  data class Invalid(val message: String) : TrailRootsMutationResult
}

/**
 * Registers a new extra trail root — the shared source for both the REST `POST /api/trails/roots`
 * route and the `AddTrailRootRequest` RPC handler. A blank path or a path that isn't an existing
 * directory is an [TrailRootsMutationResult.Invalid].
 */
internal suspend fun buildAddTrailRootResult(deps: TrailRunnerDeps, request: AddTrailRootRequest): TrailRootsMutationResult {
  val rawPath = request.path.trim()
  if (rawPath.isEmpty()) return TrailRootsMutationResult.Invalid("path is required")
  val dir = File(rawPath)
  if (!dir.exists() || !dir.isDirectory) return TrailRootsMutationResult.Invalid("not a directory: $rawPath")
  ExtraTrailRoots.add(dir.canonicalPath)
  return TrailRootsMutationResult.Ok(buildTrailRootsResponse(deps))
}

/**
 * Removes an extra trail root — the shared source for both the REST `DELETE /api/trails/roots` route
 * and the `RemoveTrailRootRequest` RPC handler. A blank path is an [TrailRootsMutationResult.Invalid].
 */
internal suspend fun buildRemoveTrailRootResult(deps: TrailRunnerDeps, path: String): TrailRootsMutationResult {
  val rawPath = path.trim()
  if (rawPath.isEmpty()) return TrailRootsMutationResult.Invalid("path is required")
  ExtraTrailRoots.remove(rawPath)
  return TrailRootsMutationResult.Ok(buildTrailRootsResponse(deps))
}

/**
 * Creates a new trail file at a workspace-relative path — the shared source for both the REST
 * `POST /api/trail/create` route and the `CreateTrailRequest` RPC handler.
 */
internal suspend fun buildCreateTrailResponse(deps: TrailRunnerDeps, request: CreateTrailRequest): SaveTrailOutcome {
  val rawPath = request.path.trim().trim('/').removeSuffix(".trail.yaml")
  val yaml = request.yaml
  val segments = rawPath.split('/').map { it.trim() }
  val unsafe = rawPath.isEmpty() || yaml.isBlank() ||
    segments.any { seg -> seg.isEmpty() || seg == "." || seg == ".." || seg.any { it.isISOControl() || it == '\\' } }
  if (unsafe) {
    return SaveTrailOutcome(
      HttpStatusCode.BadRequest,
      SaveTrailResponse(success = false, error = "a relative path and yaml are required"),
    )
  }
  val result = withContext(Dispatchers.IO) {
    runCatching {
      val primary = resolvePrimaryRoot(deps.trailsRootProvider)
      val file = File(primary, segments.joinToString("/") + ".trail.yaml")
      val rootCanon = primary.canonicalPath
      require(file.canonicalPath.startsWith("$rootCanon/")) { "path escapes the trails workspace" }
      require(!file.exists()) { "${file.name} already exists at that path" }
      file.parentFile?.mkdirs()
      file.writeText(yaml)
      file.absolutePath
    }
  }
  return if (result.isSuccess) {
    SaveTrailOutcome(HttpStatusCode.OK, SaveTrailResponse(success = true, savedPath = result.getOrThrow()))
  } else {
    // A write failure (already exists, path escape) stays a 200 with success=false, as the route did.
    SaveTrailOutcome(HttpStatusCode.OK, SaveTrailResponse(success = false, error = result.exceptionOrNull()?.message ?: "unknown error"))
  }
}

/**
 * Creates a new (empty) directory at a workspace-relative path — the shared source for both the
 * REST `POST /api/trails/mkdir` route and the `CreateTrailDirRequest` RPC handler.
 */
internal suspend fun buildCreateTrailDirResponse(deps: TrailRunnerDeps, request: CreateTrailDirRequest): SaveTrailOutcome {
  val rawPath = request.path.trim().trim('/')
  val segments = rawPath.split('/').map { it.trim() }
  val unsafe = rawPath.isEmpty() ||
    segments.any { seg -> seg.isEmpty() || seg == "." || seg == ".." || seg.any { it.isISOControl() || it == '\\' } }
  if (unsafe) {
    return SaveTrailOutcome(
      HttpStatusCode.BadRequest,
      SaveTrailResponse(success = false, error = "a relative directory path is required"),
    )
  }
  val result = withContext(Dispatchers.IO) {
    runCatching {
      val primary = resolvePrimaryRoot(deps.trailsRootProvider)
      val dir = File(primary, segments.joinToString("/"))
      val rootCanon = primary.canonicalPath
      require(dir.canonicalPath.startsWith("$rootCanon/")) { "path escapes the trails workspace" }
      require(!dir.exists()) { "${dir.name} already exists at that path" }
      require(dir.mkdirs()) { "could not create ${dir.name}" }
      dir.absolutePath
    }
  }
  return if (result.isSuccess) {
    SaveTrailOutcome(HttpStatusCode.OK, SaveTrailResponse(success = true, savedPath = result.getOrThrow()))
  } else {
    SaveTrailOutcome(HttpStatusCode.OK, SaveTrailResponse(success = false, error = result.exceptionOrNull()?.message ?: "unknown error"))
  }
}

/**
 * Overwrites an existing trail file resolved from an id's path segments — the shared source for both
 * the REST `PUT /api/trail/{id...}` route and the `UpdateTrailRequest` RPC handler.
 */
internal suspend fun buildUpdateTrailResponse(deps: TrailRunnerDeps, idSegments: List<String>, yaml: String?): SaveTrailOutcome {
  if (yaml.isNullOrBlank()) {
    return SaveTrailOutcome(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "yaml is required"))
  }
  val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
  val resolved = withContext(Dispatchers.IO) { resolveTrailFile(idSegments, primary, extras) }
    ?: return SaveTrailOutcome(
      HttpStatusCode.NotFound,
      SaveTrailResponse(success = false, error = "no trail found for id '${idSegments.joinToString("/")}'"),
    )
  val result = withContext(Dispatchers.IO) {
    runCatching {
      resolved.second.writeText(yaml)
      resolved.second.absolutePath
    }
  }
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
 * Opens a trail file in the user's editor, or `null` if the id doesn't resolve — the shared source
 * for both the REST `POST /api/trail/open` route (null → 404) and the `TrailOpenRequest` RPC handler.
 */
internal suspend fun buildOpenTrailResponse(deps: TrailRunnerDeps, id: String): OkResponse? {
  val trimmed = id.trim()
  if (trimmed.isEmpty()) return null
  val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
  val resolved = withContext(Dispatchers.IO) { resolveTrailFile(trimmed.split("/"), primary, extras) } ?: return null
  val ok = withContext(Dispatchers.IO) { openInEditor(resolved.second) }
  return OkResponse(ok = ok)
}

/**
 * Reveals a trail file in the OS file browser, or `null` if the id doesn't resolve — the shared
 * source for both the REST `POST /api/trail/reveal` route (null → 404) and the `RevealTrailRequest`
 * RPC handler.
 */
internal suspend fun buildRevealTrailResponse(deps: TrailRunnerDeps, id: String): OkResponse? {
  val trimmed = id.trim()
  if (trimmed.isEmpty()) return null
  val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
  val resolved = withContext(Dispatchers.IO) { resolveTrailFile(trimmed.split("/"), primary, extras) } ?: return null
  val ok = withContext(Dispatchers.IO) {
    runCatching {
      TrailblazeDesktopUtil.revealFileInFinder(resolved.second)
      true
    }.onFailure { Console.log("[TrailRunnerEndpoint] trail reveal failed for $id: ${it.message}") }.getOrDefault(false)
  }
  return OkResponse(ok = ok)
}

/**
 * Reveals the primary trails root in the OS file browser — the shared source for both the REST
 * `POST /api/trails/roots/reveal` route and the `RevealTrailsRootRequest` RPC handler. Always returns
 * an [OkResponse]; `ok=false` means the OS open command couldn't be launched.
 */
internal suspend fun buildRevealTrailsRootResponse(deps: TrailRunnerDeps): OkResponse {
  val (primary, _) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
  val ok = withContext(Dispatchers.IO) {
    runCatching {
      val osName = System.getProperty("os.name").lowercase()
      val cmd = when {
        osName.contains("mac") -> listOf("open", primary.absolutePath)
        osName.contains("win") -> listOf("explorer", primary.absolutePath)
        else -> listOf("xdg-open", primary.absolutePath)
      }
      ProcessBuilder(cmd).start()
      true
    }.getOrDefault(false)
  }
  return OkResponse(ok = ok)
}

internal fun Route.trailRoutes(deps: TrailRunnerDeps) {
  get("$PATH_BASE/api/trails") {
    call.respondText(
      text = JSON.encodeToString(TrailIndexResponse.serializer(), buildTrailIndexResponse(deps)),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/trails/roots") {
    call.respond(buildTrailRootsResponse(deps))
  }

  post("$PATH_BASE/api/trails/roots") {
    val body = runCatching { call.receive<AddTrailRootRequest>() }.getOrNull()
    val result = if (body == null) {
      TrailRootsMutationResult.Invalid("path is required")
    } else {
      buildAddTrailRootResult(deps, body)
    }
    when (result) {
      is TrailRootsMutationResult.Invalid -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
      is TrailRootsMutationResult.Ok -> call.respond(result.response)
    }
  }

  delete("$PATH_BASE/api/trails/roots") {
    val body = runCatching { call.receive<AddTrailRootRequest>() }.getOrNull()
    when (val result = buildRemoveTrailRootResult(deps, body?.path.orEmpty())) {
      is TrailRootsMutationResult.Invalid -> call.respond(HttpStatusCode.BadRequest, mapOf("error" to result.message))
      is TrailRootsMutationResult.Ok -> call.respond(result.response)
    }
  }

  post("$PATH_BASE/api/trail") {
    val body = runCatching { call.receive<SaveTrailRequest>() }.getOrNull()
    val yaml = body?.yaml?.trim()
    if (yaml.isNullOrEmpty()) {
      call.respond(
        HttpStatusCode.BadRequest,
        SaveTrailResponse(success = false, error = "yaml is required"),
      )
      return@post
    }
    val rawName = body.filename?.trim()
    val name = if (!rawName.isNullOrEmpty()) {
      rawName.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.')
        .ifEmpty { null }
    } else null
    val finalName = name ?: run {
      val ts = java.time.format.DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .format(java.time.LocalDateTime.now())
      "recording-$ts"
    }
    val result = withContext(Dispatchers.IO) {
      runCatching {
        val primary = resolvePrimaryRoot(deps.trailsRootProvider)
        val dir = File(primary, "_recorded")
        dir.mkdirs()
        val file = File(dir, "$finalName.trail.yaml")
        file.writeText(yaml)
        file.absolutePath
      }
    }
    if (result.isSuccess) {
      call.respond(SaveTrailResponse(success = true, savedPath = result.getOrThrow()))
    } else {
      call.respond(
        HttpStatusCode.InternalServerError,
        SaveTrailResponse(success = false, error = result.exceptionOrNull()?.message ?: "unknown error"),
      )
    }
  }

  post("$PATH_BASE/api/trail/create") {
    val body = runCatching { call.receive<CreateTrailRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "a relative path and yaml are required"))
      return@post
    }
    val outcome = buildCreateTrailResponse(deps, body)
    call.respond(outcome.status, outcome.body)
  }

  post("$PATH_BASE/api/trails/mkdir") {
    val body = runCatching { call.receive<CreateTrailDirRequest>() }.getOrNull()
    if (body == null) {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "a relative directory path is required"))
      return@post
    }
    val outcome = buildCreateTrailDirResponse(deps, body)
    call.respond(outcome.status, outcome.body)
  }

  get("$PATH_BASE/api/trails/edited") {
    // Trails with uncommitted git changes (modified or untracked) under the
    // primary workspace — powers the tree's edited-only filter.
    call.respondText(
      text = JSON.encodeToString(EditedTrailsResponse.serializer(), buildEditedTrailsResponse(deps)),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/trail/{id...}") {
    val segments = call.parameters.getAll("id").orEmpty()
    val detail = buildTrailDetailResponse(deps, segments)
    if (detail == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    call.respondText(
      text = JSON.encodeToString(TrailDetailResponse.serializer(), detail),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/trails/roots/reveal") {
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), buildRevealTrailsRootResponse(deps)),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/trail/validate") {
    val yaml = runCatching { call.receive<SaveTrailRequest>() }.getOrNull()?.yaml
    call.respond(validateTrailYaml(deps, yaml))
  }

  put("$PATH_BASE/api/trail/{id...}") {
    val segments = call.parameters.getAll("id").orEmpty()
    val yaml = runCatching { call.receive<SaveTrailRequest>() }.getOrNull()?.yaml
    val outcome = buildUpdateTrailResponse(deps, segments, yaml)
    call.respond(outcome.status, outcome.body)
  }

  post("$PATH_BASE/api/trail/open") {
    val id = runCatching { call.receive<TrailOpenRequest>() }.getOrNull()?.id.orEmpty()
    val response = buildOpenTrailResponse(deps, id)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  // Reveal a single trail file in Finder (selects it), mirroring the tool/session reveal
  // endpoints. Resolves the same way as trail/open; reuses TrailOpenRequest (carries `id`).
  post("$PATH_BASE/api/trail/reveal") {
    val id = runCatching { call.receive<TrailOpenRequest>() }.getOrNull()?.id.orEmpty()
    val response = buildRevealTrailResponse(deps, id)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }
}
