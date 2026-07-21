package xyz.block.trailblaze.trailrunner

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console

/**
 * Blaze authoring endpoints: propose steps from an objective, and the trail-bundle folder surface
 * (create / detail / file edit / record variants / delete). A bundle is a library folder with a
 * `blaze.yaml` spec plus accumulating `<platform>.trail.yaml` recordings - see [BundleStore].
 */
/** Resolves a bundle folder id against the current trail roots, on the IO dispatcher. */
private suspend fun resolveBundle(deps: TrailRunnerDeps, id: String, requireBlaze: Boolean): BundleStore.ResolvedBundle? =
  withContext(Dispatchers.IO) {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    BundleStore.resolve(id, primary, extras, requireBlaze)
  }

internal fun Route.blazeRoutes(deps: TrailRunnerDeps) {
  // objective -> proposed steps. Plan-only by default; `ground=true` requests a device-grounded
  // pass. The actual LLM/exploration work is supplied by the desktop app via [deps.proposeStepsProvider]
  // (the route module has no LLM credentials of its own).
  post("$PATH_BASE/api/blaze/propose") {
    // Body first, provider second: the shared-brain defer below must work with no provider wired
    // at all, so a bad body is now a 400 even when the proposer is absent (was a blanket 503).
    val body = runCatching { call.receive<ProposeRequest>() }.getOrNull()
    val objective = body?.objective?.trim().orEmpty()
    if (objective.isEmpty()) {
      call.respondJson(ProposeResponse.serializer(), ProposeResponse(error = "objective is required"), HttpStatusCode.BadRequest)
      return@post
    }
    // Shared brain: when the human's own agent CLI is attached to the folder this ask concerns,
    // hand the ask to it instead of the daemon's wired LLM. No matching companion -> the normal
    // provider path below, untouched.
    val folderRel = body!!.folder?.trim()?.trim('/')?.takeIf { it.isNotEmpty() } ?: companionRelFor(body.bundleId, null)
    val payload = JsonObject(
      buildMap {
        put("objective", JsonPrimitive(objective))
        body.target?.trim()?.takeIf { it.isNotEmpty() }?.let { put("target", JsonPrimitive(it)) }
        body.platform?.trim()?.takeIf { it.isNotEmpty() }?.let { put("platform", JsonPrimitive(it)) }
        folderRel?.let { put("folder", JsonPrimitive(it)) }
      },
    )
    when (val deferred = ExternalAgentSupervisor.deferToCompanion("propose-steps", folderRel, payload)) {
      is DeferOutcome.Deferred -> {
        call.respondJson(ProposeResponse.serializer(), ProposeResponse(deferred = true, requestId = deferred.requestId, runId = deferred.runId))
        return@post
      }
      is DeferOutcome.Degraded -> {
        call.respondJson(
          ProposeResponse.serializer(),
          ProposeResponse(degraded = true, runId = deferred.runId, error = COMPANION_AGENT_NOT_LISTENING),
        )
        return@post
      }
      DeferOutcome.None -> {}
    }
    val provider = deps.proposeStepsProvider
    if (provider == null) {
      call.respondJson(ProposeResponse.serializer(), ProposeResponse(error = "step proposer not available"), HttpStatusCode.ServiceUnavailable)
      return@post
    }
    if (body.ground && body.trailblazeDeviceId == null) {
      call.respondJson(ProposeResponse.serializer(), ProposeResponse(error = "grounding requires a connected device"), HttpStatusCode.BadRequest)
      return@post
    }
    val response = runCatching {
      val steps = provider(objective, body.target, body.platform, body.ground, body.trailblazeDeviceId)
      ProposeResponse(steps = steps)
    }.getOrElse { e ->
      Console.log("[BlazeRoutes] propose failed: ${e.message}")
      ProposeResponse(error = e.message ?: "could not propose steps")
    }
    call.respondJson(ProposeResponse.serializer(), response)
  }

  // ─── Library-folder editing (/api/folder/*) ───────────────────────────────────
  // The file read/write/delete/record surface for trail folders in the library
  // (id like "0/myapp/login"). Most routes resolve with requireBlaze=false so they
  // also work on a folder before its blaze.yaml exists.

  // Create a new trail bundle folder directly at a caller-chosen destination in the library and
  // write its blaze.yaml. This is how a Create session's Save lands: no staging dir, no later
  // promotion - the folder is born at its final home and shows up in the Trails list immediately.
  post("$PATH_BASE/api/folder/create") {
    val body = runCatching { call.receive<CreateBundleRequest>() }.getOrNull()
    val destination = body?.destination?.trim().orEmpty()
    val yaml = body?.yaml?.trim().orEmpty()
    if (destination.isEmpty() || yaml.isEmpty()) {
      call.respondJson(CreateBundleResponse.serializer(), CreateBundleResponse(success = false, error = "destination and yaml are required"), HttpStatusCode.BadRequest)
      return@post
    }
    val result = withContext(Dispatchers.IO) {
      runCatching {
        val primary = resolvePrimaryRoot(deps.trailsRootProvider)
        BundleStore.createAt(primary, destination, yaml)
      }
    }
    val response = result.fold(
      onSuccess = { CreateBundleResponse(success = true, id = it) },
      onFailure = { CreateBundleResponse(success = false, error = it.message ?: "unknown error") },
    )
    call.respondJson(CreateBundleResponse.serializer(), response)
  }

  // Bundle detail (title, steps, variants) for a library folder with a blaze.yaml - for callers
  // viewing a bundle that lives in the library (e.g. the Create screen's trail rail following a
  // folder the agent writes).
  get("$PATH_BASE/api/folder") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val resolved = resolveBundle(deps, id, requireBlaze = true)
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val detail = withContext(Dispatchers.IO) {
      BundleStore.detail(resolved.dir, id, resolved.home, resolved.root)
    }
    call.respondJson(BundleDetailResponse.serializer(), detail)
  }

  // Delete a whole bundle folder from the library. Deliberate and user-facing (the Trails folder
  // view's Delete): resolve keeps it contained under the trail roots, requireBlaze pins the id to
  // a real bundle folder (a suite/container directory recursively deleting would take every trail
  // under it), and an empty home (the root itself) is refused so a stale id can never wipe the
  // workspace.
  post("$PATH_BASE/api/folder/delete") {
    val id = runCatching { call.receive<BundleIdRequest>() }.getOrNull()?.id?.trim().orEmpty()
    val resolved = resolveBundle(deps, id, requireBlaze = true)
    if (resolved == null || resolved.home.isEmpty()) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    val ok = withContext(Dispatchers.IO) { BundleStore.delete(resolved.dir) }
    call.respondJson(OkResponse.serializer(), OkResponse(ok = ok))
  }

  // Reveal a bundle folder in the OS file browser (Finder) - same affordance as the tool/trail
  // "reveal" actions, for a library folder.
  post("$PATH_BASE/api/folder/reveal") {
    val id = runCatching { call.receive<BundleIdRequest>() }.getOrNull()?.id?.trim().orEmpty()
    val resolved = resolveBundle(deps, id, requireBlaze = false)
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    val ok = withContext(Dispatchers.IO) {
      runCatching { TrailblazeDesktopUtil.revealFileInFinder(resolved.dir); true }
        .onFailure { Console.log("[BlazeRoutes] folder reveal failed: ${it.message}") }
        .getOrDefault(false)
    }
    call.respondJson(OkResponse.serializer(), OkResponse(ok = ok))
  }

  // Read one file inside a library folder (blaze.yaml or a <platform>.trail.yaml) so the UI can
  // show its contents in-app. The name is a plain filename, kept inside the resolved folder.
  get("$PATH_BASE/api/folder/file") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val name = call.request.queryParameters["name"]?.trim().orEmpty()
    val resolved = resolveBundle(deps, id, requireBlaze = false)
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    // BundleStore.readFile owns the filename validation + containment, same as writeFile/deleteFile.
    val content = withContext(Dispatchers.IO) { BundleStore.readFile(resolved.dir, name) }
    if (content == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    call.respondText(text = content, contentType = ContentType.parse("application/yaml"))
  }

  // Write any single file in a library folder (an existing file OR a brand-new blaze.yaml for a
  // folder that doesn't have one yet).
  put("$PATH_BASE/api/folder/file") {
    val body = runCatching { call.receive<UpdateBundleFileRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    val name = body?.name?.trim().orEmpty()
    val yaml = body?.yaml
    if (id.isEmpty() || name.isEmpty() || yaml == null) {
      call.respondJson(SaveTrailResponse.serializer(), SaveTrailResponse(success = false, error = "id, name and yaml are required"), HttpStatusCode.BadRequest)
      return@put
    }
    val resolved = resolveBundle(deps, id, requireBlaze = false)
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@put
    }
    val ok = withContext(Dispatchers.IO) { runCatching { BundleStore.writeFile(resolved.dir, name, yaml) }.getOrDefault(false) }
    if (ok) {
      call.respondJson(SaveTrailResponse.serializer(), SaveTrailResponse(success = true, savedPath = resolved.home))
    } else {
      call.respondJson(SaveTrailResponse.serializer(), SaveTrailResponse(success = false, error = "could not write $name"), HttpStatusCode.BadRequest)
    }
  }

  // Delete one file from a library folder (BundleStore.deleteFile still refuses blaze.yaml).
  post("$PATH_BASE/api/folder/file/delete") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val name = call.request.queryParameters["name"]?.trim().orEmpty()
    if (id.isEmpty() || name.isEmpty()) {
      call.respondJson(OkResponse.serializer(), OkResponse(ok = false), HttpStatusCode.BadRequest)
      return@post
    }
    val resolved = resolveBundle(deps, id, requireBlaze = false)
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    val ok = withContext(Dispatchers.IO) { runCatching { BundleStore.deleteFile(resolved.dir, name) }.getOrDefault(false) }
    call.respondJson(OkResponse.serializer(), OkResponse(ok = ok))
  }

  // Record one variant per selected device into a library folder: dispatch the folder's blaze steps
  // to each device with recording capture on. Each run carries bundleId+variant so the recorded YAML
  // lands back in the folder on completion (see [maybeWriteBundleVariant]). Requires a blaze.yaml.
  post("$PATH_BASE/api/folder/record") {
    val deviceManager = deps.deviceManager
    if (deviceManager == null) {
      call.respondJson(RecordBundleResponse.serializer(), RecordBundleResponse(error = "deviceManager not available"), HttpStatusCode.ServiceUnavailable)
      return@post
    }
    val body = runCatching { call.receive<RecordBundleRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    if (id.isEmpty() || body!!.deviceIds.isEmpty()) {
      call.respondJson(RecordBundleResponse.serializer(), RecordBundleResponse(error = "id and at least one device are required"), HttpStatusCode.BadRequest)
      return@post
    }
    // requireBlaze: a folder with no blaze.yaml has nothing to record from.
    val resolved = resolveBundle(deps, id, requireBlaze = true)
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    val response = recordIntoFolder(deps, resolved, body)
    // If nothing started, surface the failure instead of returning an empty success. If some started,
    // include the partial errors so the UI can flag the devices that didn't record.
    val status = if (response.sessionIds.isEmpty() && response.error != null) HttpStatusCode.BadGateway else HttpStatusCode.OK
    call.respondJson(RecordBundleResponse.serializer(), response, status)
  }
}

/**
 * Body of the record route: read the folder's `blaze.yaml` (optionally prepending the
 * "Fresh install" clear-app-data setup tool), then dispatch one recording run per selected device via
 * the same path as `POST /api/run`. Each run carries `bundleId`+`variant` so the recorded
 * `<variant>.trail.yaml` lands back in the folder on completion (see `maybeWriteBundleVariant`).
 */
private suspend fun recordIntoFolder(
  deps: TrailRunnerDeps,
  resolved: BundleStore.ResolvedBundle,
  body: RecordBundleRequest,
): RecordBundleResponse {
  val id = resolved.home.let { "${resolved.rootIdx}/$it" }
  val baseYaml = withContext(Dispatchers.IO) { java.io.File(resolved.dir, "blaze.yaml").readText() }
  // Step 0 for this recording. The trailhead is platform-specific, so it's chosen per-platform on the
  // recording's column / the dialog and sent in the request — it is never read from the cross-platform
  // blaze.yaml. A real trailhead tool wins; otherwise the built-in "Fresh install" clears app state.
  val trailheadId = body.trailheadId?.trim()?.takeIf { it.isNotEmpty() }
  val blazeYaml = when {
    trailheadId != null -> prependTrailheadTool(baseYaml, trailheadId)
    body.freshInstall == true && !body.clearAppId.isNullOrBlank() -> prependClearAppData(baseYaml, body.clearAppId)
    else -> baseYaml
  }
  val sessionIds = mutableListOf<String>()
  val errors = mutableListOf<String>()
  for (device in body.deviceIds) {
    val variant = device.trailblazeDevicePlatform.name.lowercase()
    // Share the same dispatch path as POST /api/run; bundleId+variant make the recorded
    // <variant>.trail.yaml land back in the folder on completion (see maybeWriteBundleVariant).
    val response = when (
      val r = buildRunDispatchResult(
        deps,
        RunRequest(
          trailblazeDeviceId = device,
          yaml = blazeYaml,
          // The blaze has no recordings yet, so let the agent fill each step.
          useRecordedSteps = false,
          maxLlmCalls = body.maxLlmCalls,
          agent = body.agent,
          captureVideo = body.captureVideo,
          selfHeal = body.selfHeal,
          // trailId is the source-trail id the Runs/Trace views navigate to (go('trails', {sel})
          // / useTrailDetail), which resolve indexed *file* ids — not the folder id. Point it at the
          // concrete per-platform variant file this recording produces (`<folder>/<variant>`, e.g.
          // `…/login/ios`) so a run launched from the matrix opens its source trail + YAML. bundleId
          // stays the folder id: maybeWriteBundleVariant resolves the folder, not a file.
          trailId = "$id/$variant",
          bundleId = id,
          variant = variant,
        ),
      )
    ) {
      is RunDispatchResult.Invalid -> RunResponse(success = false, error = r.message)
      is RunDispatchResult.Ok -> r.response
    }
    if (response.sessionId != null) sessionIds += response.sessionId
    else errors += "$variant: ${response.error ?: "failed to start"}"
  }
  val errorText = errors.takeIf { it.isNotEmpty() }?.joinToString("; ")
  return RecordBundleResponse(sessionIds = sessionIds, error = errorText)
}

/**
 * Inserts a top-level `- trailhead: <toolId>` root element (the bare-string shorthand) ahead of the
 * blaze's prompts — the deterministic step 0. The trailhead tool itself owns any account provisioning
 * / launch / sign-in (e.g. `myapp_android_signedInFresh`), so no params are passed here.
 *
 * The trailhead must sit after `config:` and before the first step (the parser enforces both), so we
 * insert before the first `- prompts:` / `- tools:` block; with no step block we append at the end
 * (after the config) rather than prepending ahead of it.
 */
internal fun prependTrailheadTool(yaml: String, toolId: String): String {
  // toolId comes from a discovered `*.trailhead.yaml` id (or the dialog), which the loader already
  // constrains to `^[a-zA-Z0-9/_-]+$`. Re-validate here so a malformed value can't break out of the
  // YAML scalar; on a bad id, run the prompts with no trailhead rather than emitting invalid YAML.
  if (!toolId.matches(Regex("^[a-zA-Z0-9/_-]+$"))) {
    // Silent degradation would run the trail with no trailhead and no signal; log so a bad
    // discovered id (or dialog input) is debuggable rather than a mysteriously-missing step 0.
    Console.log("[BlazeRoutes] ignoring trailhead id that failed validation: '$toolId'")
    return yaml
  }
  val trailheadBlock = "- trailhead: $toolId\n"
  // Idempotency: strip any pre-existing top-level `- trailhead:` item (shorthand OR object form)
  // before inserting the new one. Without this, re-recording a trail that already adopted a
  // trailhead would emit a second one, which TrailblazeYaml rejects ("Only one trailhead item is
  // allowed") — breaking the record path for already-migrated trails. The pattern consumes the
  // `- trailhead:` line plus any following indented body lines (the object form).
  val base = yaml.replace(Regex("(?m)^- trailhead:.*(?:\\n[ \\t]+.*)*\\n?"), "")
  // Match the first `- prompts:` / `- tools:` step block, whether it's the first line of the file
  // (no config) or preceded by the config block.
  val match = Regex("(?:^|\n)- (?:prompts|tools):").find(base)
  return if (match != null) {
    // Insert before the step line's leading "- ": at the newline+1 when preceded by config, or at 0
    // when the step block is the very first line.
    val lineStart = match.range.first + if (base[match.range.first] == '\n') 1 else 0
    base.substring(0, lineStart) + trailheadBlock + base.substring(lineStart)
  } else {
    // No step block — append after the existing content (typically just the config block) so the
    // trailhead still lands after `config:`.
    if (base.isEmpty() || base.endsWith("\n")) base + trailheadBlock else "$base\n$trailheadBlock"
  }
}

/**
 * Prepends a top-level `tools:` setup item that runs `mobile_clearAppData` for [appId] before the
 * blaze's prompts — the "Fresh install" trailhead. Inserts before the first `- prompts:` line so the
 * clear runs first; falls back to prepending if there's no prompts block.
 */
private fun prependClearAppData(yaml: String, appId: String): String {
  // Hand-built double-quoted YAML scalar: escape backslash/quote first, then EVERY control char
  // (< 0x20 plus DEL) as a \uXXXX escape — otherwise a newline or other control byte in appId would
  // break out of the scalar (injecting sibling keys) or produce a parser-undefined scalar.
  val escaped = appId
    .replace("\\", "\\\\").replace("\"", "\\\"")
    .replace(Regex("[\\x00-\\x1F\\x7F]")) { m -> "\\u%04x".format(m.value[0].code) }
  val quoted = "\"$escaped\""
  val toolsBlock = "- tools:\n  - mobile_clearAppData:\n      appId: $quoted\n"
  val marker = "\n- prompts:"
  val idx = yaml.indexOf(marker)
  return if (idx >= 0) yaml.substring(0, idx + 1) + toolsBlock + yaml.substring(idx + 1) else toolsBlock + yaml
}
