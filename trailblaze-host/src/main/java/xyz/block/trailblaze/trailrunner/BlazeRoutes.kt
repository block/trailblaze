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
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console

/**
 * Blaze authoring endpoints: propose steps from an objective, and the draft-blaze folder lifecycle
 * (list / create / detail / update / record variants / promote / delete). A draft is a folder with a
 * `blaze.yaml` spec plus accumulating `<platform>.trail.yaml` recordings — see [DraftStore].
 */
internal fun Route.blazeRoutes(deps: TrailRunnerDeps) {
  // objective -> proposed steps. Plan-only by default; `ground=true` requests a device-grounded
  // pass. The actual LLM/exploration work is supplied by the desktop app via [deps.proposeStepsProvider]
  // (the route module has no LLM credentials of its own).
  post("$PATH_BASE/api/blaze/propose") {
    val provider = deps.proposeStepsProvider
    if (provider == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, ProposeResponse(error = "step proposer not available"))
      return@post
    }
    val body = runCatching { call.receive<ProposeRequest>() }.getOrNull()
    val objective = body?.objective?.trim().orEmpty()
    if (objective.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, ProposeResponse(error = "objective is required"))
      return@post
    }
    if (body!!.ground && body.trailblazeDeviceId == null) {
      call.respond(HttpStatusCode.BadRequest, ProposeResponse(error = "grounding requires a connected device"))
      return@post
    }
    val response = runCatching {
      val steps = provider(objective, body.target, body.platform, body.ground, body.trailblazeDeviceId)
      ProposeResponse(steps = steps)
    }.getOrElse { e ->
      Console.log("[BlazeRoutes] propose failed: ${e.message}")
      ProposeResponse(error = e.message ?: "could not propose steps")
    }
    call.respondText(
      text = JSON.encodeToString(ProposeResponse.serializer(), response),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/drafts") {
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val drafts = withContext(Dispatchers.IO) { DraftStore.list(primary, extras) }
    call.respondText(
      text = JSON.encodeToString(DraftsResponse.serializer(), DraftsResponse(drafts)),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/draft") {
    val body = runCatching { call.receive<CreateDraftRequest>() }.getOrNull()
    val name = body?.name?.trim().orEmpty()
    val yaml = body?.yaml?.trim().orEmpty()
    if (name.isEmpty() || yaml.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, CreateDraftResponse(success = false, error = "name and yaml are required"))
      return@post
    }
    val result = withContext(Dispatchers.IO) {
      runCatching {
        val primary = resolvePrimaryRoot(deps.trailsRootProvider)
        DraftStore.create(primary, name, yaml)
      }
    }
    if (result.isSuccess) {
      call.respond(CreateDraftResponse(success = true, id = result.getOrThrow()))
    } else {
      call.respond(CreateDraftResponse(success = false, error = result.exceptionOrNull()?.message ?: "unknown error"))
    }
  }

  get("$PATH_BASE/api/draft") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val detail = withContext(Dispatchers.IO) {
      DraftStore.detail(resolved.dir, id, resolved.home, resolved.inDrafts, resolved.root)
    }
    call.respondText(
      text = JSON.encodeToString(DraftDetailResponse.serializer(), detail),
      contentType = ContentType.Application.Json,
    )
  }

  // Read one file inside a draft folder (blaze.yaml or a <platform>.trail.yaml) so the UI can show
  // its contents in-app. The name is a plain filename, kept inside the resolved draft dir.
  get("$PATH_BASE/api/draft/file") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val name = call.request.queryParameters["name"]?.trim().orEmpty()
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) {
      call.respond(HttpStatusCode.BadRequest)
      return@get
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val content = withContext(Dispatchers.IO) {
      val file = java.io.File(resolved.dir, name)
      if (file.canonicalPath.startsWith(resolved.dir.canonicalPath + "/") && file.isFile) file.readText() else null
    }
    if (content == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    call.respondText(text = content, contentType = ContentType.parse("application/yaml"))
  }

  put("$PATH_BASE/api/draft") {
    val body = runCatching { call.receive<UpdateDraftRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    val yaml = body?.yaml
    if (id.isEmpty() || yaml.isNullOrBlank()) {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "id and yaml are required"))
      return@put
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@put
    }
    // Drafts API mutates staged drafts only. `resolve` finds any folder with a blaze.yaml, so without
    // this guard a stale id could overwrite an already-committed trail's spec (same root cause the
    // delete route guards against).
    if (!resolved.inDrafts) {
      call.respond(HttpStatusCode.Conflict, SaveTrailResponse(success = false, error = "only staged drafts can be edited"))
      return@put
    }
    val result = withContext(Dispatchers.IO) { runCatching { DraftStore.updateBlaze(resolved.dir, yaml) } }
    if (result.isSuccess) {
      call.respond(SaveTrailResponse(success = true, savedPath = resolved.home))
    } else {
      call.respond(SaveTrailResponse(success = false, error = result.exceptionOrNull()?.message ?: "unknown error"))
    }
  }

  // Write any single file in a draft folder (blaze.yaml OR a recorded <platform>.trail.yaml) so the
  // UI can edit each file inline. Name is validated + kept inside the resolved draft dir.
  put("$PATH_BASE/api/draft/file") {
    val body = runCatching { call.receive<UpdateDraftFileRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    val name = body?.name?.trim().orEmpty()
    val yaml = body?.yaml
    if (id.isEmpty() || name.isEmpty() || yaml == null) {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "id, name and yaml are required"))
      return@put
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@put
    }
    if (!resolved.inDrafts) {
      call.respond(HttpStatusCode.Conflict, SaveTrailResponse(success = false, error = "only staged drafts can be edited"))
      return@put
    }
    val ok = withContext(Dispatchers.IO) { runCatching { DraftStore.writeFile(resolved.dir, name, yaml) }.getOrDefault(false) }
    if (ok) {
      call.respond(SaveTrailResponse(success = true, savedPath = resolved.home))
    } else {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "could not write $name"))
    }
  }

  // Delete one recorded <platform>.trail.yaml from a staged draft folder (never blaze.yaml).
  post("$PATH_BASE/api/draft/file/delete") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val name = call.request.queryParameters["name"]?.trim().orEmpty()
    if (id.isEmpty() || name.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, OkResponse(ok = false))
      return@post
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    if (!resolved.inDrafts) {
      call.respond(HttpStatusCode.Conflict, OkResponse(ok = false))
      return@post
    }
    val ok = withContext(Dispatchers.IO) { runCatching { DraftStore.deleteFile(resolved.dir, name) }.getOrDefault(false) }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), OkResponse(ok = ok)),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/draft/save-to") {
    val body = runCatching { call.receive<SaveDraftToRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    val destination = body?.destination?.trim().orEmpty()
    if (id.isEmpty() || destination.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, CreateDraftResponse(success = false, error = "id and destination are required"))
      return@post
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    // Only a staged draft may be promoted. Without this guard a stale id for an already-committed
    // folder would ATOMIC_MOVE a live trail-library directory to a new path (same guard the delete
    // route applies). The UI also blocks committing while a recording is in flight via `anyRunning`;
    // that gate is not yet enforced server-side (see TODO in maybeWriteDraftVariant).
    if (!resolved.inDrafts) {
      call.respond(HttpStatusCode.Conflict, CreateDraftResponse(success = false, error = "only staged drafts can be saved to the library"))
      return@post
    }
    val result = withContext(Dispatchers.IO) {
      runCatching { DraftStore.saveTo(resolved.dir, primary, destination) }
    }
    if (result.isSuccess) {
      call.respond(CreateDraftResponse(success = true, id = result.getOrThrow()))
    } else {
      call.respond(CreateDraftResponse(success = false, error = result.exceptionOrNull()?.message ?: "unknown error"))
    }
  }

  post("$PATH_BASE/api/draft/delete") {
    val id = runCatching { call.receive<DraftIdRequest>() }.getOrNull()?.id?.trim().orEmpty()
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    // Only staged drafts may be deleted. `resolve` happily finds any trail folder with a blaze.yaml,
    // so without this guard a stale id for an already-committed folder would recursively delete the
    // committed trail-library directory.
    if (!resolved.inDrafts) {
      call.respond(HttpStatusCode.Conflict, OkResponse(ok = false))
      return@post
    }
    val ok = withContext(Dispatchers.IO) { DraftStore.delete(resolved.dir) }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), OkResponse(ok = ok)),
      contentType = ContentType.Application.Json,
    )
  }

  // Reveal a draft's folder in the OS file browser (Finder). Resolves the draft, then hands its
  // directory to the desktop reveal util — same affordance as the tool/trail "reveal" actions.
  post("$PATH_BASE/api/draft/reveal") {
    val id = runCatching { call.receive<DraftIdRequest>() }.getOrNull()?.id?.trim().orEmpty()
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    val ok = withContext(Dispatchers.IO) {
      runCatching { TrailblazeDesktopUtil.revealFileInFinder(resolved.dir); true }
        .onFailure { Console.log("[BlazeRoutes] draft reveal failed: ${it.message}") }
        .getOrDefault(false)
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), OkResponse(ok = ok)),
      contentType = ContentType.Application.Json,
    )
  }

  // Record one variant per selected device: dispatch the draft's blaze steps to each device with
  // recording capture on. Each run carries draftId+variant so the recorded YAML lands back in the
  // folder on completion (see [maybeWriteDraftVariant]).
  post("$PATH_BASE/api/draft/record") {
    val deviceManager = deps.deviceManager
    if (deviceManager == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, RecordDraftResponse(error = "deviceManager not available"))
      return@post
    }
    val body = runCatching { call.receive<RecordDraftRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    if (id.isEmpty() || body!!.deviceIds.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, RecordDraftResponse(error = "id and at least one device are required"))
      return@post
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    val response = recordIntoFolder(deps, resolved, body, allowCommittedWrite = false)
    // If nothing started, surface the failure instead of returning an empty success. If some started,
    // include the partial errors so the UI can flag the devices that didn't record.
    val status = if (response.sessionIds.isEmpty() && response.error != null) HttpStatusCode.BadGateway else HttpStatusCode.OK
    call.respondText(
      text = JSON.encodeToString(RecordDraftResponse.serializer(), response),
      contentType = ContentType.Application.Json,
      status = status,
    )
  }

  // ─── Library-folder editing (/api/folder/*) ───────────────────────────────────
  // Same file read/write/delete/record surface as the draft routes, but resolved with
  // requireBlaze=false and WITHOUT the `inDrafts` fence: the caller is intentionally editing a
  // committed library trail folder (id like "0/myapp/login"), not a staged draft.

  // Read one file inside a committed library folder. Mirrors GET /api/draft/file but resolves with
  // requireBlaze=false.
  get("$PATH_BASE/api/folder/file") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val name = call.request.queryParameters["name"]?.trim().orEmpty()
    if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.contains("..")) {
      call.respond(HttpStatusCode.BadRequest)
      return@get
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras, requireBlaze = false) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    val content = withContext(Dispatchers.IO) {
      val file = java.io.File(resolved.dir, name)
      if (file.canonicalPath.startsWith(resolved.dir.canonicalPath + "/") && file.isFile) file.readText() else null
    }
    if (content == null) {
      call.respond(HttpStatusCode.NotFound)
      return@get
    }
    call.respondText(text = content, contentType = ContentType.parse("application/yaml"))
  }

  // Write any single file in a committed library folder (an existing file OR a brand-new blaze.yaml
  // for a folder that doesn't have one yet). No `inDrafts` fence — this is the whole point.
  put("$PATH_BASE/api/folder/file") {
    val body = runCatching { call.receive<UpdateDraftFileRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    val name = body?.name?.trim().orEmpty()
    val yaml = body?.yaml
    if (id.isEmpty() || name.isEmpty() || yaml == null) {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "id, name and yaml are required"))
      return@put
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras, requireBlaze = false) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@put
    }
    val ok = withContext(Dispatchers.IO) { runCatching { DraftStore.writeFile(resolved.dir, name, yaml) }.getOrDefault(false) }
    if (ok) {
      call.respond(SaveTrailResponse(success = true, savedPath = resolved.home))
    } else {
      call.respond(HttpStatusCode.BadRequest, SaveTrailResponse(success = false, error = "could not write $name"))
    }
  }

  // Delete one file from a committed library folder (DraftStore.deleteFile still refuses blaze.yaml).
  post("$PATH_BASE/api/folder/file/delete") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    val name = call.request.queryParameters["name"]?.trim().orEmpty()
    if (id.isEmpty() || name.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, OkResponse(ok = false))
      return@post
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras, requireBlaze = false) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    val ok = withContext(Dispatchers.IO) { runCatching { DraftStore.deleteFile(resolved.dir, name) }.getOrDefault(false) }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), OkResponse(ok = ok)),
      contentType = ContentType.Application.Json,
    )
  }

  // Migrate a legacy per-platform bundle folder into a single unified `<folder>.trail.yaml` (deleting
  // the per-platform inputs + blaze.yaml it consumed). Backs the Trails "Migrate to unified" button.
  // The heavy lifting is BundleMigration/UnifiedTrailMigrator; this just resolves the folder and maps
  // the migrator's refusals (top-level tools, trailhead, already-migrated) to a 400 with a reason.
  post("$PATH_BASE/api/folder/migrate-unified") {
    val id = call.request.queryParameters["id"]?.trim().orEmpty()
    if (id.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, MigrateFolderResponse(success = false, error = "id is required"))
      return@post
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras, requireBlaze = false) }
    // resolve() only path-validates — it returns a ResolvedDraft for a directory that doesn't exist.
    // Without the isDirectory check, a missing folder would fall through to the migrator and surface
    // as a 400 with its internal "no *.trail.yaml files" message instead of a plain 404.
    if (resolved == null || !withContext(Dispatchers.IO) { resolved.dir.isDirectory }) {
      call.respond(HttpStatusCode.NotFound, MigrateFolderResponse(success = false, error = "folder not found"))
      return@post
    }
    val outcome = withContext(Dispatchers.IO) { runCatching { BundleMigration.migrateFolder(resolved.dir) } }
    outcome.fold(
      onSuccess = { o ->
        // The one server-side record of this destructive action — which folder, what was written,
        // and exactly which inputs were deleted (the response's `removed` list isn't persisted).
        Console.log(
          "[BlazeRoutes] migrate-unified '$id': wrote ${o.outputName}, " +
            "removed [${o.removed.joinToString()}], ${o.driftComments.size} drift warning(s)",
        )
        call.respond(
          MigrateFolderResponse(
            success = true,
            outputName = o.outputName,
            steps = o.steps,
            driftCount = o.driftComments.size,
            drift = o.driftComments,
            removed = o.removed,
          ),
        )
      },
      onFailure = { e ->
        // IllegalArgumentException = the migrator (or BundleMigration) refused the input: no v1 files,
        // a top-level `- tools:` block, a `- trailhead:`, or an already-migrated folder. Everything
        // else is an unexpected server error.
        val status = if (e is IllegalArgumentException) HttpStatusCode.BadRequest else HttpStatusCode.InternalServerError
        call.respond(status, MigrateFolderResponse(success = false, error = e.message ?: (e::class.simpleName ?: "migration failed")))
      },
    )
  }

  // Record one variant per selected device into a committed library folder. Same dispatch as
  // /api/draft/record, but allowCommittedVariantWrite=true so the recorded variant lands back in the
  // committed folder. Requires a blaze.yaml to drive the run.
  post("$PATH_BASE/api/folder/record") {
    val deviceManager = deps.deviceManager
    if (deviceManager == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, RecordDraftResponse(error = "deviceManager not available"))
      return@post
    }
    val body = runCatching { call.receive<RecordDraftRequest>() }.getOrNull()
    val id = body?.id?.trim().orEmpty()
    if (id.isEmpty() || body!!.deviceIds.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, RecordDraftResponse(error = "id and at least one device are required"))
      return@post
    }
    val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
    val resolved = withContext(Dispatchers.IO) { DraftStore.resolve(id, primary, extras, requireBlaze = true) }
    if (resolved == null) {
      call.respond(HttpStatusCode.NotFound)
      return@post
    }
    if (!withContext(Dispatchers.IO) { java.io.File(resolved.dir, "blaze.yaml").isFile }) {
      call.respond(HttpStatusCode.BadRequest, RecordDraftResponse(error = "folder has no blaze.yaml to record from"))
      return@post
    }
    val response = recordIntoFolder(deps, resolved, body, allowCommittedWrite = true)
    val status = if (response.sessionIds.isEmpty() && response.error != null) HttpStatusCode.BadGateway else HttpStatusCode.OK
    call.respondText(
      text = JSON.encodeToString(RecordDraftResponse.serializer(), response),
      contentType = ContentType.Application.Json,
      status = status,
    )
  }
}

/**
 * Shared body of the record routes: read the folder's `blaze.yaml` (optionally prepending the
 * "Fresh install" clear-app-data setup tool), then dispatch one recording run per selected device via
 * the same path as `POST /api/run`. Each run carries `draftId`+`variant` so the recorded
 * `<variant>.trail.yaml` lands back in the folder on completion (see `maybeWriteDraftVariant`).
 * [allowCommittedWrite] is forwarded to each [RunRequest] so the `/api/folder/record` caller can land
 * the variant in a committed library folder (the draft-record caller passes false).
 */
private suspend fun recordIntoFolder(
  deps: TrailRunnerDeps,
  resolved: DraftStore.ResolvedDraft,
  body: RecordDraftRequest,
  allowCommittedWrite: Boolean,
): RecordDraftResponse {
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
    // Share the same dispatch path as POST /api/run; draftId+variant make the recorded
    // <variant>.trail.yaml land back in the folder on completion (see maybeWriteDraftVariant).
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
          // `…/login/ios`) so a run launched from the matrix opens its source trail + YAML. draftId
          // stays the folder id: maybeWriteDraftVariant resolves the folder, not a file.
          trailId = "$id/$variant",
          draftId = id,
          variant = variant,
          allowCommittedVariantWrite = allowCommittedWrite,
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
  return RecordDraftResponse(sessionIds = sessionIds, error = errorText)
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
