package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.contentLength
import io.ktor.server.request.contentType
import io.ktor.server.request.receive
import io.ktor.server.request.receiveParameters
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import java.io.File

/**
 * Companion sessions: the attach surface for an agent CLI running OUTSIDE Trail Runner - the
 * human's own Claude Code / Codex session, working in their own repo. The file contract stays on
 * disk (the agent authors the trail folder directly; the folder-content route lets the UI watch
 * it); this surface carries only what files can't: attach, narration events, and detach.
 *
 * Request bodies are accepted as JSON or form-urlencoded. The form path exists for the
 * `trailblaze companion` launcher verbs, which build requests with `curl --data-urlencode` so
 * narration text needs no shell-side JSON escaping.
 */
internal fun Route.companionRoutes(deps: TrailRunnerDeps) {
  post("$PATH_BASE/api/companion/connect") {
    if (call.rejectedCrossOrigin()) return@post
    val body = call.receiveJsonOrForm(
      json = { call.receive<CompanionConnectRequest>() },
      form = { p ->
        CompanionConnectRequest(
          agentType = p["agentType"]?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseCompanionAgentType),
          agentLabel = p["agentLabel"],
          title = p["title"],
          folder = p["folder"],
        )
      },
    ).getOrElse { failure ->
      call.respondJson(
        CompanionConnectResponse.serializer(),
        CompanionConnectResponse(ok = false, error = bodyErrorMessage(failure)),
        HttpStatusCode.BadRequest,
      )
      return@post
    }
    // The env-var override (TRAILBLAZE_TRAILS_DIR) must apply here: a daemon spawned by
    // `trailblaze companion start` is rooted through exactly that variable. canonicalPath stays
    // inside the runCatching so an I/O hiccup surfaces as structured JSON, not a naked 500.
    val result = runCatching { resolvePrimaryRoot(deps.trailsRootProvider).canonicalFile }
      .mapCatching { root -> root.path to ExternalAgentSupervisor.startCompanion(body, root).getOrThrow() }
    val response = result.fold(
      onSuccess = { (root, run) -> CompanionConnectResponse(ok = true, runId = run.id, primaryRoot = root) },
      onFailure = { CompanionConnectResponse(ok = false, error = it.message ?: "could not connect the companion session") },
    )
    call.respondJson(
      CompanionConnectResponse.serializer(),
      response,
      if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
    )
  }

  post("$PATH_BASE/api/companion/{id}/event") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = call.receiveJsonOrForm(
      json = { call.receive<CompanionEventRequest>() },
      form = { p -> CompanionEventRequest(kind = p["kind"], title = p["title"], text = p["text"]) },
    ).getOrElse { failure ->
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = bodyErrorMessage(failure)), HttpStatusCode.BadRequest)
      return@post
    }
    val result = ExternalAgentSupervisor.companionEvent(id, body.kind, body.title, body.text)
    call.respondCompanionResult(result, "could not record the event")
  }

  post("$PATH_BASE/api/companion/{id}/directive") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = call.receiveJsonOrForm(
      json = { call.receive<CompanionDirectiveRequest>() },
      form = { p -> CompanionDirectiveRequest(directive = p["directive"], payload = payloadFromForm(p)) },
    ).getOrElse { failure ->
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = bodyErrorMessage(failure)), HttpStatusCode.BadRequest)
      return@post
    }
    val result = ExternalAgentSupervisor.companionDirective(id, body.directive, body.payload)
    call.respondCompanionResult(result, "could not send the directive")
  }

  // The UI's report of what the human did (a quick reply, a handback, a connected device). Guarded
  // like every mutating POST; the attached agent reads these off the run's SSE stream / journal.
  post("$PATH_BASE/api/companion/{id}/user-action") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = call.receiveJsonOrForm(
      json = { call.receive<CompanionUserActionRequest>() },
      form = { p -> CompanionUserActionRequest(type = p["type"], payload = payloadFromForm(p)) },
    ).getOrElse { failure ->
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = bodyErrorMessage(failure)), HttpStatusCode.BadRequest)
      return@post
    }
    val result = ExternalAgentSupervisor.companionUserAction(id, body.type, body.payload)
    call.respondCompanionResult(result, "could not record the user action")
  }

  // The one sanctioned UI write in companion mode: a recorded variant into the declared folder.
  post("$PATH_BASE/api/companion/{id}/save-recording") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = call.receiveJsonOrForm(
      json = { call.receive<CompanionSaveRecordingRequest>() },
      form = { p -> CompanionSaveRecordingRequest(variant = p["variant"], yaml = p["yaml"], platform = p["platform"]) },
    ).getOrElse { failure ->
      call.respondJson(
        CompanionSaveRecordingResponse.serializer(),
        CompanionSaveRecordingResponse(ok = false, error = bodyErrorMessage(failure)),
        HttpStatusCode.BadRequest,
      )
      return@post
    }
    val result = runCatching { resolvePrimaryRoot(deps.trailsRootProvider) }
      .mapCatching { root -> ExternalAgentSupervisor.companionSaveRecording(id, body.variant, body.yaml, root, body.platform).getOrThrow() }
    val status = result.exceptionOrNull().toCompanionStatus()
    val response = result.fold(
      onSuccess = { CompanionSaveRecordingResponse(ok = true, savedPath = it) },
      onFailure = { CompanionSaveRecordingResponse(ok = false, error = it.message ?: "could not save the recording") },
    )
    call.respondJson(CompanionSaveRecordingResponse.serializer(), response, status)
  }

  // The agent settles a shared-brain request the daemon queued on this session (see
  // deferToCompanion) - "I reviewed the trail: done" / "couldn't: error".
  post("$PATH_BASE/api/companion/{id}/respond") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = call.receiveJsonOrForm(
      json = { call.receive<CompanionRespondRequest>() },
      form = { p -> CompanionRespondRequest(requestId = p["requestId"], status = p["status"], note = p["note"]) },
    ).getOrElse { failure ->
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = bodyErrorMessage(failure)), HttpStatusCode.BadRequest)
      return@post
    }
    val result = ExternalAgentSupervisor.companionRespond(id, body.requestId, body.status, body.note)
    call.respondCompanionResult(result, "could not record the response")
  }

  post("$PATH_BASE/api/companion/{id}/disconnect") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    // A missing/empty body is a plain disconnect with no note; a body that is present but
    // unreadable is a caller mistake (the note it meant to attach would be silently dropped),
    // and gets the same 400 as the other verbs.
    val body = if ((call.request.contentLength() ?: 0L) == 0L) {
      CompanionDisconnectRequest()
    } else {
      call.receiveJsonOrForm(
        json = { call.receive<CompanionDisconnectRequest>() },
        form = { p -> CompanionDisconnectRequest(note = p["note"]) },
      ).getOrElse { failure ->
        call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = bodyErrorMessage(failure)), HttpStatusCode.BadRequest)
        return@post
      }
    }
    val result = ExternalAgentSupervisor.disconnectCompanion(id, body.note)
    call.respondCompanionResult(result, "could not disconnect")
  }

  // The live folder view: the files of the trail folder this companion session declared. Same
  // response shape as `/demo/trail-content`, so the web's live trail rail renders both.
  get("$PATH_BASE/api/companion/{id}/folder-content") {
    if (call.rejectedNonLocalHost()) return@get
    val id = call.parameters["id"]?.trim().orEmpty()
    val response = runCatching { resolvePrimaryRoot(deps.trailsRootProvider) }
      .mapCatching { root ->
        val result = ExternalAgentSupervisor.companionFolderContent(id, root)
          ?: return@mapCatching DemoTrailContentResponse(ok = false, error = "unknown or non-companion run: $id")
        DemoTrailContentResponse(ok = true, trailId = result.trailId, files = result.files)
      }
      .getOrElse { DemoTrailContentResponse(ok = false, error = it.message ?: "could not read the folder") }
    call.respondJson(
      DemoTrailContentResponse.serializer(),
      response,
      if (response.ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
    )
  }

  // The demo-files tree: the declared folder's recursive listing, names and sizes only - file
  // contents stay behind the per-file affordances (folder-content, open-file).
  get("$PATH_BASE/api/companion/{id}/folder-tree") {
    if (call.rejectedNonLocalHost()) return@get
    val id = call.parameters["id"]?.trim().orEmpty()
    val response = runCatching { resolvePrimaryRoot(deps.trailsRootProvider) }
      .mapCatching { root ->
        val entries = ExternalAgentSupervisor.companionFolderTree(id, root)
          ?: return@mapCatching CompanionFolderTreeResponse(ok = false, error = "unknown or non-companion run: $id")
        CompanionFolderTreeResponse(ok = true, entries = entries)
      }
      .getOrElse { CompanionFolderTreeResponse(ok = false, error = it.message ?: "could not list the folder") }
    call.respondJson(
      CompanionFolderTreeResponse.serializer(),
      response,
      if (response.ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
    )
  }

  // "Open in Finder" on the demo-files tab: reveal the declared folder in the platform file browser.
  post("$PATH_BASE/api/companion/{id}/reveal-folder") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    val result = runCatching {
      val root = resolvePrimaryRoot(deps.trailsRootProvider)
      val dir = ExternalAgentSupervisor.companionFolderDir(id, root)
        ?: throw NoSuchElementException("this session has no trail folder on disk")
      TrailblazeDesktopUtil.revealFileInFinder(dir)
    }
    call.respondCompanionResult(result, "could not reveal the folder")
  }

  // Right-click on a demo file: open it in the human's editor. The path resolves strictly inside
  // the session's declared folder - the same canonical-containment check as every read of it.
  post("$PATH_BASE/api/companion/{id}/open-file") {
    if (call.rejectedCrossOrigin()) return@post
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = call.receiveJsonOrForm(
      json = { call.receive<CompanionOpenFileRequest>() },
      form = { p -> CompanionOpenFileRequest(path = p["path"]) },
    ).getOrElse { failure ->
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = bodyErrorMessage(failure)), HttpStatusCode.BadRequest)
      return@post
    }
    val result = runCatching {
      val rel = body.path?.trim().orEmpty()
      require(rel.isNotEmpty()) { "path is required" }
      val root = resolvePrimaryRoot(deps.trailsRootProvider)
      val dir = ExternalAgentSupervisor.companionFolderDir(id, root)
        ?: throw NoSuchElementException("this session has no trail folder on disk")
      val file = File(dir, rel).canonicalFile
      require(file.path.startsWith(dir.path + File.separator) && file.isFile) { "no such file in this session's folder: $rel" }
      require(openInEditor(file)) { "no editor could open the file" }
    }
    call.respondCompanionResult(result, "could not open the file")
  }
}

/** Unknown run -> 404 (the codebase convention); validation failures -> 400; success -> 200. */
private fun Throwable?.toCompanionStatus(): HttpStatusCode = when (this) {
  null -> HttpStatusCode.OK
  is NoSuchElementException -> HttpStatusCode.NotFound
  else -> HttpStatusCode.BadRequest
}

private suspend fun ApplicationCall.respondCompanionResult(result: Result<Unit>, fallbackError: String) {
  val failure = result.exceptionOrNull()
  val response = if (failure == null) OkResponse(ok = true) else OkResponse(ok = false, error = failure.message ?: fallbackError)
  respondJson(OkResponse.serializer(), response, failure.toCompanionStatus())
}

/**
 * CSRF guard for the form-accepting POSTs: form-urlencoded is a browser "simple request" (no CORS
 * preflight), so without this a drive-by web page could create companion runs on the local daemon.
 * Browsers always attach an Origin header to cross-origin POSTs; curl (the launcher) sends none.
 * Allowlisted by Origin HOST, not Origin-equals-Host equality: a DNS-rebound page carries its own
 * hostname in both headers, so equality would pass exactly the attack this guard exists to stop.
 * Matched structurally (not URL-parsed): parsers default missing hosts to localhost, which would
 * wave through the literal `Origin: null` a sandboxed iframe sends. Anything non-local fails closed.
 */
private val LOCAL_ORIGIN = Regex("""https?://(localhost|127\.0\.0\.1|\[::1])(:\d+)?""")

private suspend fun ApplicationCall.rejectedCrossOrigin(): Boolean {
  val origin = request.headers["Origin"] ?: return false
  if (LOCAL_ORIGIN.matches(origin.trim())) return false
  respondJson(
    OkResponse.serializer(),
    OkResponse(ok = false, error = "cross-origin requests are not allowed"),
    HttpStatusCode.Forbidden,
  )
  return true
}

/**
 * DNS-rebinding guard for the companion GET routes. Browsers omit Origin on same-origin GETs, so
 * [rejectedCrossOrigin] can't cover them: a page at attacker.com rebound to 127.0.0.1 could read
 * the trail folder through the daemon. The Host header still carries the attacker's name, so any
 * present Host that isn't loopback is refused. Absent Host (non-browser clients like the curl
 * launcher) fails open, matching the Origin guard's stance.
 */
private val LOCAL_HOST = Regex("""(localhost|127\.0\.0\.1|\[::1]|::1)(:\d+)?""")

private suspend fun ApplicationCall.rejectedNonLocalHost(): Boolean {
  val host = request.headers["Host"] ?: return false
  if (LOCAL_HOST.matches(host.trim())) return false
  respondJson(
    OkResponse.serializer(),
    OkResponse(ok = false, error = "requests to a non-local host are not allowed"),
    HttpStatusCode.Forbidden,
  )
  return true
}

/**
 * The form path's payload builder: the launcher sends flat convenience fields (curl
 * --data-urlencode, so no shell-side JSON assembly), and this reconstructs the payload object the
 * JSON path would have carried. `items` is newline-separated - checklist entries and quick-reply
 * labels legitimately contain commas - and `payload` accepts one raw JSON object for anything
 * beyond the conveniences (a parse failure surfaces as the route's 400).
 */
private fun payloadFromForm(p: Parameters): JsonObject? {
  val fields = buildMap {
    p["payload"]?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
      // Strict parse: the lenient instance would accept `{text: hello}` with an unquoted value
      // that then fails the "must be a string" type check - a 400 blaming the wrong thing.
      // Relaxed JSON should fail here, as a parse error naming the payload.
      val parsed = Json.parseToJsonElement(raw) as? JsonObject ?: error("payload must be a JSON object")
      putAll(parsed)
    }
    // Empty values are dropped, not carried: an all-empty form means "no payload", which the
    // directive path reads as a retract - `send <runId> banner --text ""` clears the banner.
    for (key in listOf("text", "route", "variant", "platform", "app", "title", "note", "actionId", "label")) {
      p[key]?.takeIf { it.isNotEmpty() }?.let { put(key, JsonPrimitive(it)) }
    }
    p["items"]?.let { items ->
      // Every --item is carried, blanks included: a blank must FAIL validation loudly like it
      // does on the JSON path - filtering here would let an all-blank list decay into "no
      // payload", which the directive path reads as a RETRACT the agent never asked for.
      put("items", JsonArray(items.lineSequence().map { JsonPrimitive(it.trim()) }.toList()))
    }
  }
  return if (fields.isEmpty()) null else JsonObject(fields)
}

/** Receives the body as form parameters when form-urlencoded, else as JSON. */
private suspend fun <T> ApplicationCall.receiveJsonOrForm(
  json: suspend () -> T,
  form: (Parameters) -> T,
): Result<T> = runCatching {
  if (request.contentType().withoutParameters().match(ContentType.Application.FormUrlEncoded)) {
    form(receiveParameters())
  } else {
    json()
  }
}

/**
 * An unreadable body is a caller mistake worth explaining (the agentType vocabulary, a require
 * message), but deserializer failures can be paragraphs - keep the first line only.
 */
private fun bodyErrorMessage(failure: Throwable): String =
  failure.message?.trim()?.lineSequence()?.firstOrNull()?.take(300)?.takeIf { it.isNotEmpty() }
    ?: "invalid request body"

/** The form path's agent-type parse; unknown values are rejected, like the JSON path's enum decode. */
private fun parseCompanionAgentType(value: String): ExternalAgentType = when (value.trim().lowercase()) {
  "claude" -> ExternalAgentType.CLAUDE
  "codex" -> ExternalAgentType.CODEX
  else -> error("unknown agentType: $value (use claude or codex)")
}
