package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.sse.heartbeat
import io.ktor.server.sse.sse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import java.io.File
import kotlin.time.Duration.Companion.seconds

// Bound the evidence read: the tape dir is child-writable, and the route loads the whole file
// into one array - an unbounded file would be a memory-amplification lever on the daemon.
private const val EVIDENCE_FILE_MAX_BYTES = 25L * 1024 * 1024

/** Hand-encoded error body with real JSON escaping for caller-supplied fragments. */
private fun errorJson(message: String): String = buildJsonObject { put("error", message) }.toString()

// This server has no ContentNegotiation plugin: a `call.respond(status, <object>)` throws at
// serialization time and reaches the client as a naked HTTP 500. Every DTO body goes through this.
private suspend fun <T> io.ktor.server.application.ApplicationCall.respondJson(
  serializer: kotlinx.serialization.KSerializer<T>,
  body: T,
  status: HttpStatusCode = HttpStatusCode.OK,
) = respondText(JSON.encodeToString(serializer, body), ContentType.Application.Json, status)

internal fun Route.externalAgentRoutes(deps: TrailRunnerDeps) {
  get("$PATH_BASE/api/external-agents") {
    call.respondText(
      text = JSON.encodeToString(
        ExternalAgentRunsResponse.serializer(),
        ExternalAgentRunsResponse(
          supportedAgents = ExternalAgentSupervisor.supportedAgents(),
          runs = ExternalAgentSupervisor.runs(),
        ),
      ),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/external-agent/start") {
    // Every response on this route is hand-encoded via respondText: this server has no
    // ContentNegotiation plugin, so a `call.respond(status, <object>)` error path THROWS at
    // serialization time and reaches the client as a naked HTTP 500 - masking the real reason
    // ("claude not found", "prompt is required") the branch existed to deliver.
    val body = runCatching { call.receive<ExternalAgentRunRequest>() }.getOrElse { e ->
      Console.log("[ExternalAgentRoutes] bad start body: ${e.message}")
      call.respondText(
        text = JSON.encodeToString(ExternalAgentStartResponse.serializer(), ExternalAgentStartResponse(ok = false, error = "invalid JSON body")),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
      )
      return@post
    }
    // The workspace resolution runs outside the supervisor's own runCatching - fold its failure
    // into the same structured error instead of letting it escape as a 500.
    val result = runCatching { deps.trailsRootProvider() }
      .mapCatching { root -> ExternalAgentSupervisor.start(body, root, artifactsRoot = deps.logsRepo.logsDir).getOrThrow() }
    val response = result.fold(
      onSuccess = { ExternalAgentStartResponse(ok = true, run = it) },
      onFailure = { ExternalAgentStartResponse(ok = false, error = it.message ?: "could not start agent") },
    )
    call.respondText(
      text = JSON.encodeToString(ExternalAgentStartResponse.serializer(), response),
      contentType = ContentType.Application.Json,
      status = if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
    )
  }

  // ─── Demonstrate-first Create ──────────────────────────────────────────────
  // A demonstration is an agent-less run in demo mode. Gestures keep using /api/record/gesture with
  // this run's id; these three endpoints drive the phase (positioning -> recording -> done) and the
  // durable bundle. The generation step that turns a finished bundle into a trail is a later slice.

  post("$PATH_BASE/api/external-agent/start-demo") {
    val body = runCatching { call.receive<StartDemoRequest>() }.getOrElse {
      call.respondJson(StartDemoResponse.serializer(), StartDemoResponse(ok = false, error = "invalid JSON body"), HttpStatusCode.BadRequest)
      return@post
    }
    val result = runCatching { deps.trailsRootProvider() }
      .mapCatching { root ->
        ExternalAgentSupervisor.startDemo(
          target = body.target,
          platform = body.platform,
          deviceId = body.trailblazeDeviceId,
          title = body.title,
          fallbackCwd = root,
          artifactsRoot = deps.logsRepo.logsDir,
        ).getOrThrow()
      }
    val response = result.fold(
      onSuccess = { StartDemoResponse(ok = true, runId = it.id) },
      onFailure = { StartDemoResponse(ok = false, error = it.message ?: "could not start the demonstration") },
    )
    call.respondJson(StartDemoResponse.serializer(), response, if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
  }

  post("$PATH_BASE/api/external-agent/{id}/demo/mark-start") {
    val id = call.parameters["id"]?.trim().orEmpty()
    // A missing/empty body means manual positioning (null trailhead), which is valid, so a receive
    // failure defaults to a manual mark-start rather than erroring.
    val body = runCatching { call.receive<DemoMarkStartRequest>() }.getOrDefault(DemoMarkStartRequest())
    val result = ExternalAgentSupervisor.markDemoStart(id, body.trailhead)
    val response = result.fold(
      onSuccess = { DemoPhaseResponse(ok = true, phase = it) },
      onFailure = { DemoPhaseResponse(ok = false, error = it.message ?: "could not start recording") },
    )
    call.respondJson(DemoPhaseResponse.serializer(), response, if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
  }

  post("$PATH_BASE/api/external-agent/{id}/demo/delete-step") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<DemoDeleteStepRequest>() }.getOrNull()
    if (body == null || body.eventId.isBlank()) {
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = "eventId is required"), HttpStatusCode.BadRequest)
      return@post
    }
    val result = ExternalAgentSupervisor.deleteDemoStep(id, body.eventId.trim())
    val response = result.fold(
      onSuccess = { OkResponse(ok = true) },
      onFailure = { OkResponse(ok = false, error = it.message ?: "could not delete the step") },
    )
    call.respondJson(OkResponse.serializer(), response, if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
  }

  post("$PATH_BASE/api/external-agent/{id}/demo/finish") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<DemoFinishRequest>() }.getOrNull()
    if (body == null) {
      call.respondJson(DemoFinishResponse.serializer(), DemoFinishResponse(ok = false, error = "invalid JSON body"), HttpStatusCode.BadRequest)
      return@post
    }
    val result = ExternalAgentSupervisor.finishDemo(id, body.objective, body.notes)
    val response = result.fold(
      onSuccess = { DemoFinishResponse(ok = true, bundleDir = it) },
      onFailure = { DemoFinishResponse(ok = false, error = it.message ?: "could not finish the demonstration") },
    )
    call.respondJson(DemoFinishResponse.serializer(), response, if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
  }

  post("$PATH_BASE/api/external-agent/{id}/demo/add-platform") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<DemoAddPlatformRequest>() }.getOrNull()
    if (body == null) {
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = "invalid JSON body"), HttpStatusCode.BadRequest)
      return@post
    }
    val result = ExternalAgentSupervisor.addDemoPlatform(id, body.deviceId)
    val response = result.fold(
      onSuccess = { OkResponse(ok = true) },
      onFailure = { OkResponse(ok = false, error = it.message ?: "could not add the platform") },
    )
    call.respondJson(OkResponse.serializer(), response, if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
  }

  post("$PATH_BASE/api/external-agent/{id}/demo/generate") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<DemoGenerateRequest>() }.getOrNull()
    if (body == null) {
      call.respondJson(DemoGenerateResponse.serializer(), DemoGenerateResponse(ok = false, error = "invalid JSON body"), HttpStatusCode.BadRequest)
      return@post
    }
    // Trails root resolution runs outside the supervisor's runCatching, folded into the same
    // structured error so it can't escape as a naked 500 (no ContentNegotiation on this server).
    val result = runCatching { deps.trailsRootProvider() }
      .mapCatching { root ->
        ExternalAgentSupervisor.generateFromDemo(
          demoRunId = id,
          agentType = body.agentType,
          model = body.model,
          sandbox = body.sandbox,
          fallbackCwd = root,
          artifactsRoot = deps.logsRepo.logsDir,
        ).getOrThrow()
      }
    val response = result.fold(
      onSuccess = { DemoGenerateResponse(ok = true, generationRunId = it) },
      onFailure = { DemoGenerateResponse(ok = false, error = it.message ?: "could not start generation") },
    )
    call.respondJson(DemoGenerateResponse.serializer(), response, if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest)
  }

  // The live trail rail: the files the generation run has delivered (or, before any trail_output,
  // the files it is writing into the suggested folder). {id} is the DEMO run id.
  get("$PATH_BASE/api/external-agent/{id}/demo/trail-content") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val response = runCatching {
      val result = ExternalAgentSupervisor.demoTrailContent(id, deps.trailsRootProvider())
        ?: return@runCatching DemoTrailContentResponse(ok = false, error = "unknown or non-demo run: $id")
      DemoTrailContentResponse(ok = true, trailId = result.trailId, files = result.files)
    }.getOrElse { DemoTrailContentResponse(ok = false, error = it.message ?: "could not read trail content") }
    call.respondJson(
      DemoTrailContentResponse.serializer(),
      response,
      if (response.ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
    )
  }

  // Reveal the demonstration bundle (the current platform's evidence dir) in the OS file browser -
  // same affordance as the library folder / trail "reveal" actions. {id} is the DEMO run id.
  post("$PATH_BASE/api/external-agent/{id}/demo/reveal-bundle") {
    val id = call.parameters["id"]?.trim().orEmpty()
    // The DTO's bundleDir is the same value the UI gates its button on - null for non-demo runs.
    val bundleDir = ExternalAgentSupervisor.run(id)?.demo?.bundleDir
    if (bundleDir == null) {
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = "no demonstration bundle for run: $id"), HttpStatusCode.NotFound)
      return@post
    }
    val ok = withContext(Dispatchers.IO) {
      runCatching {
        // The dir may not exist yet in the positioning phase (evidence lands once recording
        // starts) - create it so the reveal works from any phase.
        val dir = File(bundleDir).apply { mkdirs() }
        TrailblazeDesktopUtil.revealFileInFinder(dir)
        true
      }
        .onFailure { Console.log("[ExternalAgentRoutes] bundle reveal failed: ${it.message}") }
        .getOrDefault(false)
    }
    call.respondJson(OkResponse.serializer(), OkResponse(ok = ok))
  }

  get("$PATH_BASE/api/external-agent/{id}/events") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val events = ExternalAgentSupervisor.events(id)
    if (events == null) {
      // Hand-encoded (no ContentNegotiation): respond(status, map) would 500 instead of 404ing.
      // The id is a caller-supplied path segment - JSON-escape it, don't interpolate it raw.
      call.respondText(
        text = errorJson("external agent run not found: $id"),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.NotFound,
      )
      return@get
    }
    call.respondText(
      text = JSON.encodeToString(ExternalAgentEventsResponse.serializer(), ExternalAgentEventsResponse(events)),
      contentType = ContentType.Application.Json,
    )
  }

  // The skills the child CLI can reach from a conversation: the workspace's `.claude/skills`
  // (walking up from the trails root to the project boundary, the same discovery the CLI does
  // from its cwd) plus the user's `~/.claude/skills`. Powers the Skills panel - what expertise
  // is in the agent's context and where each piece lives on disk.
  get("$PATH_BASE/api/external-agent/skills") {
    // A discovery failure must say so, not render as "no skills": the panel's empty state would
    // read as a fact about the workspace when the truth is an unreadable trails root.
    val response = withContext(Dispatchers.IO) {
      runCatching { discoverAgentSkills(deps.trailsRootProvider()) }
    }.fold(
      onSuccess = { AgentSkillsResponse(ok = true, skills = it) },
      onFailure = { AgentSkillsResponse(ok = false, error = it.message ?: "skill discovery failed") },
    )
    call.respondText(
      text = JSON.encodeToString(AgentSkillsResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  // Serves a run's demonstration-evidence files (the before/after screenshots + hierarchy dumps
  // captured per human action) to the event log UI. The name is a single path segment, validated
  // and canonically contained in the run's tape dir.
  get("$PATH_BASE/api/external-agent/{id}/evidence/{name}") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val name = call.parameters["name"]?.trim().orEmpty()
    val dir = ExternalAgentSupervisor.evidenceDir(id)
    val unsafe = name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..") || name.any { it.isISOControl() }
    val dirCanon = dir?.canonicalPath
    val file = if (dir == null || unsafe || dirCanon == null) {
      null
    } else {
      File(dir, name).takeIf { it.isFile && it.canonicalPath.startsWith(dirCanon + File.separator) }
    }
    if (file == null) {
      call.respondText(
        text = """{"error":"evidence not found"}""",
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.NotFound,
      )
      return@get
    }
    // The tape dir is writable by the child process, so its contents are not trusted: bound the
    // full-file read (a screenshot or hierarchy dump is a few MB at most) and tell browsers not
    // to sniff a served file into something more executable than its declared type.
    if (file.length() > EVIDENCE_FILE_MAX_BYTES) {
      call.respondText(
        text = """{"error":"evidence file too large"}""",
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.PayloadTooLarge,
      )
      return@get
    }
    val type = when (file.extension.lowercase()) {
      "png" -> ContentType.Image.PNG
      "webp" -> ContentType.parse("image/webp")
      "jpg", "jpeg" -> ContentType.Image.JPEG
      else -> ContentType.Text.Plain
    }
    call.response.header("X-Content-Type-Options", "nosniff")
    call.respondBytes(withContext(Dispatchers.IO) { file.readBytes() }, contentType = type)
  }

  post("$PATH_BASE/api/external-agent/{id}/reply") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<ExternalAgentReplyRequest>() }.getOrNull()
    if (body == null) {
      call.respondText(
        text = JSON.encodeToString(ExternalAgentStartResponse.serializer(), ExternalAgentStartResponse(ok = false, error = "invalid JSON body")),
        contentType = ContentType.Application.Json,
        status = HttpStatusCode.BadRequest,
      )
      return@post
    }
    val result = ExternalAgentSupervisor.reply(id, body.prompt)
    val response = result.fold(
      onSuccess = { ExternalAgentStartResponse(ok = true, run = it) },
      onFailure = { ExternalAgentStartResponse(ok = false, error = it.message ?: "could not send the reply") },
    )
    call.respondText(
      text = JSON.encodeToString(ExternalAgentStartResponse.serializer(), response),
      contentType = ContentType.Application.Json,
      status = if (response.ok) HttpStatusCode.OK else HttpStatusCode.BadRequest,
    )
  }

  post("$PATH_BASE/api/external-agent/{id}/cancel") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val deviceId = ExternalAgentSupervisor.deviceFor(id)
    val mayHoldDevice = ExternalAgentSupervisor.mayHoldDeviceWork(id)
    val ok = ExternalAgentSupervisor.cancel(id)
    // Killing the child CLI doesn't stop a device operation the agent already dispatched - MCP
    // tools execute in the daemon (under their own multi-minute timeout), and until the device
    // session is cancelled the device stays held and every new run 409s against it. Child first
    // (so it can't dispatch more work), then the device - but only while this run can actually
    // be holding it (a tool call in flight); an idle run's device may belong to someone else.
    if (ok && deviceId != null && mayHoldDevice) {
      val stopped = runCatching { deps.deviceManager?.cancelSessionForDevice(deviceId) == true }.getOrDefault(false)
      if (stopped) {
        ExternalAgentSupervisor.emitLifecycle(
          id,
          title = "Stopped the in-flight device operation",
          text = "Released ${deviceId.instanceId} so the next run can use it",
        )
      }
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), OkResponse(ok = ok, error = if (ok) null else "run not found")),
      contentType = ContentType.Application.Json,
      status = if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
    )
  }

  // ─── Human-approvable permissions ──────────────────────────────────────────
  // The MCP proxy is the only caller of permission-request: it forwards a tool the spawned CLI
  // wants to run and this route suspends until the human decides. The web drives /permission and
  // /auto-approve from the pending cards it renders off the run DTO.

  post("$PATH_BASE/api/external-agent/{id}/permission-request") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<ExternalAgentPermissionRequestBody>() }.getOrNull()
    if (body == null || body.toolName.isBlank()) {
      call.respondJson(
        ExternalAgentPermissionResponse.serializer(),
        ExternalAgentPermissionResponse(behavior = "deny", message = "invalid permission request"),
        HttpStatusCode.BadRequest,
      )
      return@post
    }
    val decision = ExternalAgentSupervisor.requestPermission(id, body.toolName, body.inputJson, body.toolUseId)
    val response = when (decision) {
      is PermissionDecision.Allow -> ExternalAgentPermissionResponse(behavior = "allow", updatedInputJson = decision.updatedInputJson)
      is PermissionDecision.Deny -> ExternalAgentPermissionResponse(behavior = "deny", message = decision.message)
    }
    call.respondJson(ExternalAgentPermissionResponse.serializer(), response)
  }

  post("$PATH_BASE/api/external-agent/{id}/permission") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<ExternalAgentPermissionDecisionRequest>() }.getOrNull()
    val decision = body?.decision?.trim()
    if (body == null || body.requestId.isBlank() || decision !in setOf("allow", "allow_always", "deny")) {
      call.respondJson(
        OkResponse.serializer(),
        OkResponse(ok = false, error = "requestId and a valid decision (allow|allow_always|deny) are required"),
        HttpStatusCode.BadRequest,
      )
      return@post
    }
    val ok = ExternalAgentSupervisor.decidePermission(id, body.requestId.trim(), decision!!)
    call.respondJson(
      OkResponse.serializer(),
      OkResponse(ok = ok, error = if (ok) null else "no matching pending permission"),
      if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
    )
  }

  post("$PATH_BASE/api/external-agent/{id}/auto-approve") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val body = runCatching { call.receive<ExternalAgentAutoApproveRequest>() }.getOrNull()
    if (body == null) {
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false, error = "invalid JSON body"), HttpStatusCode.BadRequest)
      return@post
    }
    val ok = ExternalAgentSupervisor.setAutoApprove(id, body.enabled)
    call.respondJson(
      OkResponse.serializer(),
      OkResponse(ok = ok, error = if (ok) null else "run not found"),
      if (ok) HttpStatusCode.OK else HttpStatusCode.NotFound,
    )
  }

  sse("$PATH_BASE/api/external-agent/{id}/stream") {
    val id = call.parameters["id"]?.trim().orEmpty()
    if (ExternalAgentSupervisor.run(id) == null) {
      // JSON-escaping also keeps SSE framing safe: a raw newline in the id would split the frame.
      send(event = "error", data = errorJson("external agent run not found: $id"))
      return@sse
    }
    heartbeat { period = 15.seconds }

    // Flush by seq watermark (not list index): retention capping can drop old events from the
    // snapshot, and an EventSource reconnect sends Last-Event-ID so nothing already delivered is
    // replayed — the UI applies live UI commands as they stream, so replays would re-fire them.
    // `?afterSeq=N` lets the UI open the stream strictly after the history it already fetched;
    // without it a stream opened mid-run would deliver that history as if it were live.
    var lastSeq = maxOf(
      call.request.headers["Last-Event-ID"]?.toIntOrNull() ?: -1,
      call.request.queryParameters["afterSeq"]?.toIntOrNull() ?: -1,
    )
    suspend fun flushNew() {
      val snapshot = ExternalAgentSupervisor.events(id).orEmpty()
      snapshot.filter { it.seq > lastSeq }.forEach { event ->
        send(
          event = "agent-event",
          id = event.seq.toString(),
          data = JSON.encodeToString(ExternalAgentEventDto.serializer(), event),
        )
        lastSeq = event.seq
      }
    }

    try {
      while (true) {
        flushNew()
        val run = ExternalAgentSupervisor.run(id)
        if (run == null || run.status != ExternalAgentSessionStatus.RUNNING) {
          flushNew()
          send(event = "done", data = """{"runId":"$id"}""")
          break
        }
        delay(EXTERNAL_AGENT_POLL_INTERVAL_MS)
      }
    } catch (e: Throwable) {
      Console.log("[ExternalAgentRoutes] stream for $id closed: ${e.message}")
    }
  }
}

/** One skill the child CLI can invoke, and where it lives on disk. */
@Serializable
internal data class AgentSkillDto(
  val name: String,
  val description: String? = null,
  /** Absolute path of the skill directory (holds SKILL.md and any references/scripts). */
  val dir: String,
  /** "workspace" (found via the trails root's ancestry) or "user" (~/.claude/skills). */
  val scope: String,
)

@Serializable
internal data class AgentSkillsResponse(
  val ok: Boolean,
  val skills: List<AgentSkillDto> = emptyList(),
  val error: String? = null,
)

/**
 * The skills a conversation's child CLI would discover: every `.claude/skills/<name>/SKILL.md`
 * from the trails root up to the project boundary (the first `.git`, matching the CLI's own
 * walk-up from its cwd), then the user-level `~/.claude/skills`. Workspace skills sort first.
 */
internal fun discoverAgentSkills(trailsRoot: File): List<AgentSkillDto> {
  val out = mutableListOf<AgentSkillDto>()
  var dir: File? = runCatching { trailsRoot.canonicalFile }.getOrNull()
  var hops = 0
  while (dir != null && hops < 12) {
    val skillsDir = File(dir, ".claude/skills")
    if (skillsDir.isDirectory) out += skillsIn(skillsDir, scope = "workspace")
    if (File(dir, ".git").exists()) break
    dir = dir.parentFile
    hops++
  }
  val userSkills = File(System.getProperty("user.home"), ".claude/skills")
  if (userSkills.isDirectory) out += skillsIn(userSkills, scope = "user")
  // Nearest definition wins on a name collision within a scope (same rule as the CLI).
  val seen = mutableSetOf<String>()
  return out.filter { seen.add(it.scope + "/" + it.name) }
}

private fun skillsIn(dir: File, scope: String): List<AgentSkillDto> =
  (dir.listFiles() ?: emptyArray())
    .filter { it.isDirectory }
    .sortedBy { it.name }
    .mapNotNull { d ->
      val md = File(d, "SKILL.md").takeIf { it.isFile } ?: return@mapNotNull null
      val (name, description) = parseSkillFrontmatter(md)
      AgentSkillDto(name = name ?: d.name, description = description, dir = d.absolutePath, scope = scope)
    }

/**
 * Minimal frontmatter read: `name:` and `description:` (plain or block-scalar `|`/`>` style)
 * from the leading `---` block. Deliberately not a YAML parser - these two fields are all the
 * panel shows, and a malformed file just falls back to the directory name.
 */
private fun parseSkillFrontmatter(md: File): Pair<String?, String?> {
  val lines = runCatching { md.useLines { seq -> seq.take(60).toList() } }.getOrDefault(emptyList())
  if (lines.firstOrNull()?.trim() != "---") return null to null
  var name: String? = null
  val description = StringBuilder()
  var inDescription = false
  for (line in lines.drop(1)) {
    if (line.trim() == "---") break
    when {
      line.startsWith("name:") -> {
        name = line.removePrefix("name:").trim().ifEmpty { null }
        inDescription = false
      }
      line.startsWith("description:") -> {
        val value = line.removePrefix("description:").trim()
        inDescription = value.isEmpty() || value == "|" || value == ">"
        if (!inDescription) description.append(value)
      }
      inDescription && (line.startsWith(" ") || line.isBlank()) -> {
        if (description.isNotEmpty() && line.isNotBlank()) description.append(' ')
        description.append(line.trim())
      }
      else -> inDescription = false
    }
  }
  return name to description.toString().trim().take(320).ifEmpty { null }
}
