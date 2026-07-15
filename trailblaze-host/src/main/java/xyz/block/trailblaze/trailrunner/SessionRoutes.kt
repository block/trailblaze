package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.header
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.report.utils.SessionEventsReader
import xyz.block.trailblaze.ui.JvmLiveSessionDataProvider
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.ui.tabs.sessions.SessionImportResult
import xyz.block.trailblaze.ui.tabs.sessions.SessionImporter
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

internal const val WINDOW_PAD_MS: Long = 3_000L

// Per-stream read caps for the session events endpoint (whichever hits first). A single stream file
// can be tens of MB and the UI polls it every few seconds, so the endpoint returns at most a bounded
// prefix per stream and flags it `truncated` rather than materializing the whole file each poll.
internal const val EVENTS_MAX_EVENTS_PER_STREAM: Int = 2_000
internal const val EVENTS_MAX_BYTES_PER_STREAM: Long = 2_000_000L

// Upper bound for the /share-html body. Reports are ~1 MB; this leaves generous headroom for
// screenshot-heavy runs while bounding what a buggy/abusive client can make the daemon buffer + write.
internal const val SHARE_HTML_MAX_BYTES: Long = 64L * 1024 * 1024

// Session archives can include screenshots/video, but the web upload path still needs a daemon-side
// cap because Ktor buffers the request body before passing it to the existing ZIP importer.
internal const val SESSION_ARCHIVE_MAX_BYTES: Long = 512L * 1024 * 1024

/**
 * The recorded sessions, newest first — the shared source for both the REST `GET /api/sessions`
 * route and the `GetSessionsRequest` RPC handler, so the two paths can't drift.
 */
internal suspend fun fetchSessionSummaries(deps: TrailRunnerDeps): List<SessionSummary> =
  withContext(Dispatchers.IO) {
    deps.logsRepo.getSessionIds().mapNotNull { id ->
      // Summary-grade read: status logs + file mtimes only. The full-parse getSessionInfoDirect
      // deserializes every log (hierarchies + LLM transcripts) and, called for every session on
      // every poll, exhausted the daemon heap.
      val info = runCatching { deps.logsRepo.getSessionInfoSummary(id) }.getOrNull() ?: return@mapNotNull null
      val trailId = runCatching {
        java.io.File(deps.logsRepo.logsDir, "$id/.trailrunner-trail-id").takeIf { it.isFile }?.readText()?.trim()
      }.getOrNull()
      toSessionSummary(info).copy(
        trailId = trailId?.takeIf { it.isNotEmpty() },
        imported = SessionImporter.isImportedSession(id.value, deps.logsRepo),
      )
    }.sortedByDescending { it.timestampMs }
  }

/**
 * The captured analytics events for a session window, or `null` if the session id doesn't resolve —
 * the shared source for both the REST `GET /api/session/{id}/analytics` route (404 on null) and the
 * `GetSessionAnalyticsRequest` RPC handler (RPC failure on null). `available=false` (with no events)
 * is the valid "no analytics provider wired" response, distinct from the unresolved-session null.
 */
internal suspend fun buildSessionAnalyticsResponse(deps: TrailRunnerDeps, id: String): AnalyticsResponse? {
  resolveSafeSessionDir(deps.logsRepo.logsDir, id) ?: return null
  val analyticsProvider = deps.analyticsProvider ?: return AnalyticsResponse(available = false, events = emptyList())
  val events = withContext(Dispatchers.IO) {
    val info = runCatching { deps.logsRepo.getSessionInfoDirect(SessionId(id)) }.getOrNull()
    if (info == null) {
      emptyList()
    } else {
      val startMs = info.timestamp.toEpochMilliseconds()
      val endMs = if (info.durationMs > 0) startMs + info.durationMs else System.currentTimeMillis()
      runCatching { analyticsProvider(startMs - WINDOW_PAD_MS, endMs + WINDOW_PAD_MS) }
        .onFailure { Console.log("[TrailRunnerEndpoint] analytics provider failed for $id: ${it.message}") }
        .getOrDefault(emptyList())
    }
  }
  return AnalyticsResponse(available = true, events = events)
}

/**
 * The outcome of a cancel-session request — the shared source for both the REST
 * `POST /api/session/{id}/cancel` route and the `CancelSessionRequest` RPC handler. The two failure
 * modes stay distinct so REST keeps its exact status codes: [NoDeviceManager] → 503 (the daemon
 * can't reach a device), [NotFound] → 404 (unknown session id). The RPC handler maps both to a
 * Failure (the UI's `dataOrNull` renders either as the same "couldn't cancel" state).
 */
internal sealed interface CancelSessionOutcome {
  data class Ok(val response: CancelSessionResponse) : CancelSessionOutcome

  data object NoDeviceManager : CancelSessionOutcome

  data object NotFound : CancelSessionOutcome
}

internal suspend fun buildCancelSessionOutcome(deps: TrailRunnerDeps, id: String): CancelSessionOutcome {
  val deviceManager = deps.deviceManager ?: return CancelSessionOutcome.NoDeviceManager
  if (resolveSafeSessionDir(deps.logsRepo.logsDir, id) == null) return CancelSessionOutcome.NotFound
  val response = withContext(Dispatchers.IO) {
    // A session that already reached a terminal status must stay terminal: a cancel raced against
    // a fast finish would otherwise append Ended.Cancelled OVER Ended.Succeeded and rewrite
    // history (observed live: a passed 12.5s run flipped to "cancelled" by a stale Stop button).
    val alreadyEnded = deps.logsRepo.getSessionInfoDirect(SessionId(id))
      ?.latestStatus is SessionStatus.Ended
    // Ended-but-still-holding-a-device is the phantom-409 state: the session's logs say it's over
    // but its cleanup never ran, so it's still registered as the device's active session and every
    // new run 409s against it. That hold must be cancellable - release the device (without writing
    // a second terminal status log over the real one) instead of short-circuiting.
    val holdingDevice = deviceManager.activeDeviceSessionsFlow.value.entries
      .firstOrNull { it.value.value == id }?.key
    if (alreadyEnded && holdingDevice != null) {
      // knownSessionId covers the mapping-already-cleared case: without it, an ended-but-wedged
      // session's screenrecord/logcat capture streams keep running forever.
      val released = runCatching { deviceManager.cancelSessionForDevice(holdingDevice, knownSessionId = SessionId(id)) }.getOrDefault(false)
      CancelSessionResponse(ok = released, reason = if (released) "released_device" else "not_running")
    } else if (alreadyEnded) {
      CancelSessionResponse(ok = false, reason = "already_ended")
    } else {
      val ok = runCatching {
        JvmLiveSessionDataProvider(deps.logsRepo, deviceManager).cancelSession(SessionId(id))
      }.getOrDefault(false)
      // "not_running": no live execution was found for the session (or the cancel path errored) -
      // nothing was stopped and no cancellation log was written, and the UI should say so instead
      // of silently pretending the Stop landed.
      CancelSessionResponse(ok = ok, reason = if (ok) "cancelled" else "not_running")
    }
  }
  return CancelSessionOutcome.Ok(response)
}

/**
 * Deletes a session's logs, or `null` if the id doesn't resolve — the shared source for both the
 * REST `POST /api/session/{id}/delete` route (null → 404) and the `DeleteSessionRequest` RPC handler.
 */
internal suspend fun buildDeleteSessionResponse(deps: TrailRunnerDeps, id: String): DeleteSessionResponse? {
  if (resolveSafeSessionDir(deps.logsRepo.logsDir, id) == null) return null
  withContext(Dispatchers.IO) { deps.logsRepo.deleteLogsForSession(SessionId(id)) }
  return DeleteSessionResponse(deleted = id)
}

internal suspend fun buildClearSessionsResponse(deps: TrailRunnerDeps): ClearSessionsResponse =
  withContext(Dispatchers.IO) {
    val before = deps.logsRepo.getSessionIds().size
    deps.logsRepo.clearLogs()
    ClearSessionsResponse(deleted = before)
  }

internal suspend fun buildRevealLogsRootResponse(deps: TrailRunnerDeps): OkResponse {
  val ok = withContext(Dispatchers.IO) {
    runCatching {
      TrailblazeDesktopUtil.openInFileBrowser(deps.logsRepo.logsDir)
      true
    }.onFailure { Console.log("[TrailRunnerEndpoint] reveal logs root failed: ${it.message}") }.getOrDefault(false)
  }
  return OkResponse(ok = ok)
}

/**
 * Reveals a session's directory in the OS file browser, or `null` if the id doesn't resolve — the
 * shared source for both the REST `POST /api/session/{id}/reveal` route (null → 404) and the
 * `RevealSessionRequest` RPC handler. A reveal that fails (resolved but the OS open threw) is
 * `OkResponse(ok = false)`, distinct from the unresolved-session null.
 */
internal suspend fun buildRevealSessionResponse(deps: TrailRunnerDeps, id: String): OkResponse? {
  val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id) ?: return null
  val ok = withContext(Dispatchers.IO) {
    runCatching {
      TrailblazeDesktopUtil.openInFileBrowser(sessionDir)
      true
    }.onFailure { Console.log("[TrailRunnerEndpoint] reveal failed for $id: ${it.message}") }.getOrDefault(false)
  }
  return OkResponse(ok = ok)
}

/**
 * The files in a session's directory (name + size), or `null` if the id doesn't resolve — the shared
 * source for both the REST `GET /api/session/{id}/files` route (null → 404; responds a bare array for
 * back-compat) and the `GetSessionFilesRequest` RPC handler (which returns the wrapped response).
 */
internal suspend fun buildSessionFilesResponse(deps: TrailRunnerDeps, id: String): SessionFilesResponse? {
  val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id) ?: return null
  return withContext(Dispatchers.IO) {
    // Walk recursively so files inside subfolders (events/, in-process-scripted-tools/, …) show up too,
    // not just top-level files. `name` carries the session-relative path with forward slashes (e.g.
    // `events/network.ndjson`); the open-file route accepts that subpath and re-validates containment.
    val files = sessionDir.walkTopDown()
      .maxDepth(8)
      .filter { it.isFile }
      .map { SessionFileDto(name = it.relativeTo(sessionDir).invariantSeparatorsPath, size = it.length()) }
      .sortedBy { it.name }
      .toList()
    SessionFilesResponse(files)
  }
}

/**
 * Opens a named file in a session's directory with the OS default app (falling back to revealing it),
 * or `null` if the id/name doesn't resolve to a real file — the shared source for both the REST
 * `POST /api/session/{id}/open-file` route (null → 404) and the `OpenSessionFileRequest` RPC handler.
 * The name is a session-relative path (forward slashes allowed, e.g. `events/network.ndjson`) validated
 * to keep the open inside the session dir.
 */
internal suspend fun buildOpenSessionFileResponse(deps: TrailRunnerDeps, id: String, name: String): OkResponse? {
  val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id)
  val trimmed = name.trim()
  // Allow forward-slash subpaths (artifacts in subfolders) but block escapes: no absolute paths,
  // backslashes, `..` traversal, or control chars. The canonical-containment check below is the
  // real guard (it also resolves symlinks).
  val unsafe = trimmed.isEmpty() || trimmed.startsWith('/') || trimmed.contains('\\') || trimmed.contains("..") || trimmed.any { it.isISOControl() }
  // Canonical-containment check (resolves symlinks) on top of the name validation: a session
  // artifact that's a symlink to a file outside the session dir would otherwise be openable.
  val sessionCanon = sessionDir?.canonicalPath
  val file = if (sessionDir == null || unsafe || sessionCanon == null) {
    null
  } else {
    java.io.File(sessionDir, trimmed).takeIf {
      it.isFile && it.canonicalPath.startsWith(sessionCanon + java.io.File.separator)
    }
  }
  if (file == null) return null
  val ok = withContext(Dispatchers.IO) {
    runCatching {
      // Open with the default app. If there's no association (e.g. a raw .h264), Desktop.open throws —
      // fall back to revealing it in the file manager so the click always does something visible.
      runCatching { TrailblazeDesktopUtil.openInFileBrowser(file) }
        .getOrElse { TrailblazeDesktopUtil.revealFileInFinder(file) }
      true
    }.getOrDefault(false)
  }
  return OkResponse(ok = ok)
}

private fun safeArchiveName(raw: String): String =
  raw.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.ifEmpty { "session" }

// Streams the archive straight to [out] — a session can carry video and hundreds of screenshots,
// so materializing the whole zip in memory (the previous ByteArrayOutputStream + toByteArray
// implementation) could put hundreds of MB on the long-lived daemon heap per export.
internal fun writeSessionArchive(sessionDir: File, safeName: String, out: java.io.OutputStream) {
  val sessionCanon = sessionDir.canonicalPath
  ZipOutputStream(out).use { zip ->
    sessionDir.walkTopDown()
      .filter { it.isFile }
      .filter { it.canonicalPath.startsWith(sessionCanon + File.separator) }
      .forEach { file ->
        val relative = file.relativeTo(sessionDir).invariantSeparatorsPath
        zip.putNextEntry(ZipEntry("$safeName/$relative"))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
      }
  }
}

internal suspend fun buildImportSessionArchiveResponse(
  deps: TrailRunnerDeps,
  archiveFile: File,
): SessionArchiveImportResponse =
  withContext(Dispatchers.IO) {
    when (val result = SessionImporter.importSessionFromZip(archiveFile, deps.logsRepo)) {
      is SessionImportResult.Success ->
        SessionArchiveImportResponse(
          ok = true,
          sessionId = result.sessionId,
          fileCount = result.fileCount,
        )
      is SessionImportResult.Error ->
        SessionArchiveImportResponse(
          ok = false,
          error = listOf(result.title, result.message).filter { it.isNotBlank() }.joinToString(": "),
        )
    }
  }

internal fun Route.sessionRoutes(deps: TrailRunnerDeps) {
  get("$PATH_BASE/api/sessions") {
    call.respondText(
      text = JSON.encodeToString(SessionsResponse.serializer(), SessionsResponse(fetchSessionSummaries(deps))),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/session/{id}/logs") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id)
    if (sessionDir == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val logsArray = withContext(Dispatchers.IO) {
      val files = (sessionDir.listFiles() ?: emptyArray())
        .filter { f ->
          f.extension == "json" &&
            f.name.firstOrNull()?.let { c -> c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F' } == true
        }
        .sortedBy { it.name }
      val elements = files.mapNotNull { f ->
        runCatching { JSON.parseToJsonElement(f.readText()) }.getOrNull()
      }
      kotlinx.serialization.json.JsonArray(elements)
    }
    call.respondText(
      text = JSON.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), logsArray),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/session/{id}/files") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val response = buildSessionFilesResponse(deps, id)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    // Keep the REST response a bare array (the RPC handler returns the wrapped SessionFilesResponse).
    call.respondText(
      text = JSON.encodeToString(kotlinx.serialization.builtins.ListSerializer(SessionFileDto.serializer()), response.files),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/session/{id}/cancel") {
    val id = call.parameters["id"]?.trim().orEmpty()
    when (val outcome = buildCancelSessionOutcome(deps, id)) {
      is CancelSessionOutcome.NoDeviceManager ->
        call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "deviceManager not available"))
      is CancelSessionOutcome.NotFound -> call.respond(HttpStatusCode.NotFound)
      is CancelSessionOutcome.Ok ->
        call.respondText(
          text = JSON.encodeToString(CancelSessionResponse.serializer(), outcome.response),
          contentType = ContentType.Application.Json,
        )
    }
  }

  get("$PATH_BASE/api/session/{id}/analytics") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val response = buildSessionAnalyticsResponse(deps, id)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    call.respondText(
      text = JSON.encodeToString(AnalyticsResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/session/{id}/events") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id)
    if (sessionDir == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val response = withContext(Dispatchers.IO) {
      // Sourced producer-agnostically from <sessionDir>/events/ via the generic reader; any stream
      // that follows the events format shows up here.
      val streams =
        SessionEventsReader(
            maxEventsPerStream = EVENTS_MAX_EVENTS_PER_STREAM,
            maxBytesPerStream = EVENTS_MAX_BYTES_PER_STREAM,
          )
          .read(sessionDir)
      val streamDtos = streams.map { stream ->
        SessionEventStreamDto(
          streamId = stream.name,
          label = eventStreamLabel(stream.name),
          style = stream.style,
          count = stream.count,
          truncated = stream.truncated,
          events =
            stream.events.map { e ->
              SessionEventDto(
                streamId = stream.name,
                receivedAt = java.time.Instant.ofEpochMilli(e.timeMs).toString(),
                timeMs = e.timeMs,
                data = e.data,
              )
            },
        )
      }
      SessionEventsResponse(available = streamDtos.isNotEmpty(), streams = streamDtos)
    }
    call.respondText(
      text = JSON.encodeToString(SessionEventsResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/session/{id}/delete") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val response = buildDeleteSessionResponse(deps, id)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(DeleteSessionResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/sessions/clear") {
    val request = runCatching { call.receive<ClearSessionsRequest>() }.getOrNull()
    if (request?.confirm != true) {
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "confirmation required"))
      return@post
    }
    val response = buildClearSessionsResponse(deps)
    call.respondText(
      text = JSON.encodeToString(ClearSessionsResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/sessions/reveal") {
    val response = buildRevealLogsRootResponse(deps)
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/session/{id}/open-file") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val name = runCatching { call.receive<OpenSessionFileBody>() }.getOrNull()?.name.orEmpty()
    val response = buildOpenSessionFileResponse(deps, id, name)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/session/{id}/reveal") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val response = buildRevealSessionResponse(deps, id)
    if (response == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/session/{id}/export") {
    val id = call.parameters["id"]?.trim().orEmpty()
    if (resolveSafeSessionDir(deps.logsRepo.logsDir, id) == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val logs = withContext(Dispatchers.IO) { deps.logsRepo.getLogsForSession(SessionId(id)) }
    if (logs.isEmpty()) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val yaml = withContext(Dispatchers.IO) { logs.generateRecordedYaml(createTrailblazeYaml()) }
    val safeName = id.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }.ifEmpty { "session" }
    call.response.header("Content-Disposition", "attachment; filename=\"$safeName.trail.yaml\"")
    call.respondText(text = yaml, contentType = ContentType.parse("application/yaml"))
  }

  get("$PATH_BASE/api/session/{id}/export.zip") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id)
    if (sessionDir == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val safeName = safeArchiveName(id)
    call.response.header("Content-Disposition", "attachment; filename=\"$safeName.zip\"")
    call.respondOutputStream(contentType = ContentType.parse("application/zip"), status = HttpStatusCode.OK) {
      withContext(Dispatchers.IO) { writeSessionArchive(sessionDir, safeName, this@respondOutputStream) }
    }
  }

  post("$PATH_BASE/api/session/import") {
    val declaredLength = call.request.headers["Content-Length"]?.toLongOrNull()
    if (declaredLength != null && declaredLength > SESSION_ARCHIVE_MAX_BYTES) {
      call.respond(HttpStatusCode.PayloadTooLarge)
      return@post
    }
    val temp = File.createTempFile("trailrunner-session-import-", ".zip")
    try {
      val channel = call.receiveChannel()
      var totalBytes = 0L
      var tooLarge = false
      withContext(Dispatchers.IO) {
        temp.outputStream().use { output ->
          val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
          while (true) {
            val read = channel.readAvailable(buffer, 0, buffer.size)
            if (read == -1) break
            totalBytes += read
            if (totalBytes > SESSION_ARCHIVE_MAX_BYTES) {
              tooLarge = true
              break
            }
            output.write(buffer, 0, read)
          }
        }
      }
      if (totalBytes == 0L) {
        call.respond(HttpStatusCode.BadRequest)
        return@post
      }
      if (tooLarge) {
        call.respond(HttpStatusCode.PayloadTooLarge)
        return@post
      }
      val response = buildImportSessionArchiveResponse(deps, temp)
      call.respondText(
        text = JSON.encodeToString(SessionArchiveImportResponse.serializer(), response),
        contentType = ContentType.Application.Json,
      )
    } finally {
      runCatching { temp.delete() }
    }
  }

  // Persist a client-generated standalone HTML report into the session folder, then return its
  // filename so the UI can open/reveal it via the existing file bridges. The desktop WKWebView shell
  // has no download handler (so `<a download>` / blob: / window.open are dropped) — the daemon writing
  // the file is what makes "Share" actually produce a file on disk in the app. The HTML is built on the
  // client (it already holds the derived trace + LLM transcript + screenshots), so this route just writes.
  post("$PATH_BASE/api/session/{id}/share-html") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val sessionDir = resolveSafeSessionDir(deps.logsRepo.logsDir, id)
    if (sessionDir == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    // Reject oversized payloads before buffering/parsing the whole JSON body.
    val declaredLength = call.request.headers["Content-Length"]?.toLongOrNull()
    if (declaredLength != null && declaredLength > SHARE_HTML_MAX_BYTES) {
      call.respond(HttpStatusCode.PayloadTooLarge)
      return@post
    }
    val body = runCatching { call.receive<ShareHtmlBody>() }.getOrNull()
    if (body == null || body.html.isBlank() || body.html.length.toLong() > SHARE_HTML_MAX_BYTES) {
      call.respondText(
        text = JSON.encodeToString(ShareHtmlResponse.serializer(), ShareHtmlResponse(ok = false, error = "No HTML content")),
        contentType = ContentType.Application.Json,
      )
      return@post
    }
    val base = body.name.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifEmpty { "run" }.take(80)
    val fileName = "$base.trailblaze.html"
    val ok = withContext(Dispatchers.IO) {
      runCatching {
        java.io.File(sessionDir, fileName).writeText(body.html)
        true
      }.onFailure { Console.log("[TrailRunnerEndpoint] share-html write failed for $id: ${it.message}") }.getOrDefault(false)
    }
    call.respondText(
      text = JSON.encodeToString(
        ShareHtmlResponse.serializer(),
        if (ok) ShareHtmlResponse(ok = true, name = fileName) else ShareHtmlResponse(ok = false, error = "Could not write file"),
      ),
      contentType = ContentType.Application.Json,
    )
  }
}
