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
import xyz.block.trailblaze.config.KnownTargetMessages
import xyz.block.trailblaze.recordings.TrailRecordings
import xyz.block.trailblaze.ui.TrailblazeDesktopUtil
import xyz.block.trailblaze.util.Console
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.unified.UnifiedTrail
import xyz.block.trailblaze.yaml.unified.UnifiedTrailAdapter
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
  val topLevel = runGit(dir, "rev-parse", "--show-toplevel", timeoutSeconds = 3)?.trim()?.takeIf { it.isNotBlank() }
    ?: return null to false
  val branch = runGit(dir, "rev-parse", "--abbrev-ref", "HEAD", timeoutSeconds = 3)
    ?.trim()?.takeIf { it.isNotBlank() && it != "HEAD" }
  val isWorktree = File(topLevel, ".git").isFile
  return branch to isWorktree
}

/**
 * Runs `git` in [dir] and returns its stdout verbatim on exit 0, or null on every failure — no git,
 * not a checkout, non-zero exit, a timeout, or a stdout drain that never reached EOF. The one git
 * runner for this file: the branch probe, the edited-trails scan and the diff baseline all bound the
 * same way.
 *
 * Stdout is drained on a daemon thread so [timeoutSeconds] actually governs: reading it inline
 * blocks until the pipe hits EOF, so a wedged git (an index lock, a networked working tree) would
 * hang the caller's dispatcher long past the timeout. Stderr is discarded at the OS level, because
 * an undrained stderr pipe can fill on git's warnings and block the process the same way. Daemon +
 * named so a timed-out drain can't hold up JVM shutdown or leak a thread per call.
 *
 * Verbatim, not trimmed: blank stdout is a real answer for `status --porcelain` (nothing changed),
 * and `show` output has to reproduce the committed file byte for byte or a diff against it invents
 * whitespace changes the user never made.
 */
private fun runGit(dir: File, vararg args: String, timeoutSeconds: Long = 10): String? = runCatching {
  val process = ProcessBuilder(listOf("git", *args))
    .directory(dir)
    .redirectError(ProcessBuilder.Redirect.DISCARD)
    .start()
  // Null until the drain reads all the way to EOF, so a drain that didn't finish reads as no answer
  // rather than as empty output. Blank stdout on exit 0 is a real answer that has to survive this,
  // which is why the completed read sets its own value instead of the caller testing for emptiness.
  val out = java.util.concurrent.atomic.AtomicReference<String?>(null)
  val reader = kotlin.concurrent.thread(isDaemon = true, name = "trailrunner-git-stdout") {
    runCatching { out.set(process.inputStream.bufferedReader().readText()) }
  }
  if (!process.waitFor(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS)) {
    process.destroyForcibly()
    reader.join(500)
    return@runCatching null
  }
  // The process has exited, so its pipe is at EOF and the drain finishes as fast as the reader is
  // scheduled. Still bounded rather than joined outright: a child that inherited the pipe and
  // outlived git would hold it open forever, and this runs on a shared dispatcher.
  reader.join(1000)
  if (process.exitValue() != 0) null else out.get()
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
      val top = runGit(primary, "rev-parse", "--show-toplevel")?.trim()?.takeIf { it.isNotEmpty() }
        ?: return@runCatching emptyList()
      val out = runGit(primary, "status", "--porcelain", "--", ".") ?: return@runCatching emptyList()
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
 * The committed version of one trail file, plus how the file on disk compares to it — what an
 * editor diff view reads. `null` when the id doesn't resolve to a trail file, matching
 * [buildTrailDetailResponse].
 *
 * The state is derived from the committed bytes themselves rather than from `git status`, so the
 * verdict and the diff can't disagree: [TrailGitState.MODIFIED] means exactly "these two texts
 * differ". A file git has nothing committed for is [TrailGitState.UNTRACKED] whether it is
 * genuinely untracked or merely staged-but-never-committed — from a diff's point of view those are
 * the same thing, every line is new. [TrailGitState.UNAVAILABLE] covers every case where git can't
 * answer at all (not installed, not a checkout, a wedged index), so a caller can hide the
 * affordance instead of showing an empty baseline that reads as "you deleted the whole file".
 */
internal suspend fun buildTrailGitBaselineResponse(
  deps: TrailRunnerDeps,
  idSegments: List<String>,
): TrailGitBaselineResponse? =
  withContext(Dispatchers.IO) {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    val (_, file) = resolveTrailFile(idSegments, primary, extras) ?: return@withContext null
    val dir = file.parentFile ?: return@withContext TrailGitBaselineResponse(TrailGitState.UNAVAILABLE)
    // `HEAD:./name` resolves against git's own working directory, so running in the file's directory
    // is all this needs — no repo-root-relative path to compute, and it works the same in a linked
    // worktree or a submodule.
    val committed = runGit(dir, "show", "HEAD:./${file.name}")
      ?: return@withContext TrailGitBaselineResponse(
        // `show` reports that nothing came back, not why, and the two reasons need opposite answers:
        // a genuinely new trail has no baseline BY DESIGN, while a wedged index or a killed git has
        // one we merely failed to read. `ls-tree` separates them, because it answers about HEAD's
        // contents instead of trying to produce bytes: exit 0 with empty stdout is "git looked, and
        // HEAD has no such file", so the trail is new. Anything else — a null (git couldn't answer,
        // including a repo with no commits yet) or a listed entry (it IS committed, so `show` itself
        // failed) — is a baseline this daemon can't produce, and calling that UNTRACKED would render
        // the whole file as added.
        if (runGit(dir, "ls-tree", "HEAD", "--", "./${file.name}", timeoutSeconds = 3)?.isBlank() == true) {
          TrailGitState.UNTRACKED
        } else {
          TrailGitState.UNAVAILABLE
        },
      )
    val current = runCatching { file.readText() }.getOrNull()
    TrailGitBaselineResponse(
      state = if (current == committed) TrailGitState.CLEAN else TrailGitState.MODIFIED,
      committed = committed,
    )
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
        // Same case the daemon's run path warns about, reached through validation instead: if the
        // target is declared as living in another repo, say so here too, or the editor reports a
        // trail authored for another workspace as simply misspelled. The single-line variant, because
        // every other error on this response is normalized to one line just above.
        val elsewhere = KnownTargetMessages.unavailableTargetSummary(target)?.let { " — $it" }.orEmpty()
        errors += ValidationErrorDto(
          message = "unknown target '$target' — known targets: ${known.sorted().joinToString(", ")}$elsewhere",
        )
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
 * The outcome of a record-a-step-range request, carrying the status the REST route answers with for
 * the same reason [SaveTrailOutcome] does: only the shared builder knows whether it turned the
 * request down (400) or tried to dispatch it and got nothing back (502).
 */
internal data class RecordTrailRangeOutcome(val status: HttpStatusCode, val body: RecordTrailRangeResponse)

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
      val parent = requireNotNull(file.parentFile)
      parent.mkdirs()
      when (BundleStore.writeFile(parent, file.name, yaml, operation = "create")) {
        BundleStore.FileWriteResult.WRITTEN -> Unit
        BundleStore.FileWriteResult.ALREADY_EXISTS -> error("${file.name} already exists at that path")
        else -> error("Unable to create ${file.name}")
      }
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
 * What a recording of steps `[from, to]` runs, and the window its result is merged under.
 *
 * [mergeWindow] is null when [runnable] is the trail whole - the two travel together because they
 * are the same decision, and splitting them is how a full-range recording ends up dispatched as a
 * whole trail but merged as a partial one.
 */
internal data class StepWindowRun(val runnable: UnifiedTrail, val mergeWindow: IntRange?)

/**
 * The trail a recording of steps `[from, to]` actually runs, or null when the window names steps
 * [unified] doesn't have.
 */
internal fun runnableForStepWindow(unified: UnifiedTrail, from: Int, to: Int): StepWindowRun? = when {
  from < 0 || to < from || to >= unified.trail.size -> null
  // A window covering every step is a recording of the trail as authored: it runs whole, trailhead
  // included, so the device is launched into the state step 1 expects - and it merges back as an
  // ordinary whole-trail save-back, with no window at all. Naming a window here instead would
  // exempt the trailhead from the merge's drift check (a window sits outside it by definition)
  // while the recording still replaced it, which is how a trailhead edited mid-run ends up carrying
  // tool calls that never ran under it.
  from == 0 && to == unified.trail.size - 1 -> StepWindowRun(unified, null)
  // Only a NARROWED window is sliced, which is also what drops the trailhead - a partial run picks
  // up from whatever is on the device's screen. Its merge is scoped by the same window, so a run
  // that recorded a different number of steps than it covered is refused rather than shifting every
  // later recording.
  else -> UnifiedTrailAdapter.sliceTrail(unified, from, to)?.let { StepWindowRun(it, from..to) }
}

/**
 * Run a contiguous span of an existing unified trail's steps on each selected device and merge each
 * recording back into that trail's own file under the recording device's classifier. A span covering
 * every step is the whole trail, trailhead included; a narrower one starts from whatever is on the
 * device's screen.
 *
 * The slice happens HERE, not on the client, and the file the merge writes is the file this route
 * resolved - the client only ever names a trail id and a step range. That is what keeps the
 * `RunRequest.recordTrailFile` write authority server-side (see its `@Transient` declaration): a
 * client that could hand over a path, or a pre-sliced fragment paired with an arbitrary offset,
 * could write anywhere or write the wrong steps.
 *
 * Returns one session id per device that started. A device that failed to start is reported in
 * `error` alongside the ones that did, so a partial launch is visible rather than averaged away.
 *
 * The outcome carries its own status because the two kinds of failure here are not the same kind of
 * failure: a request naming a trail, a range or a device that can't be honoured is the caller's
 * error (400), while a well-formed request whose devices all refused to start is the daemon
 * reporting that dispatch failed (502). Only the outcome knows which it returned.
 */
internal suspend fun buildRecordTrailRangeResponse(
  deps: TrailRunnerDeps,
  body: RecordTrailRangeRequest,
): RecordTrailRangeOutcome {
  val id = body.id.trim()
  if (id.isEmpty() || body.deviceIds.isEmpty()) {
    return badRequest("a trail id and at least one device are required")
  }
  val (primary, extras) = withContext(Dispatchers.IO) { resolveRoots(deps.trailsRootProvider) }
  // resolveTrailFile applies the canonical-path containment check against the resolved root, so a
  // traversal id can't name a file outside the trails roots.
  val trailFile = withContext(Dispatchers.IO) { resolveTrailFile(id.split("/"), primary, extras) }?.second
    ?: return badRequest("trail `$id` not found")

  val yaml = createTrailblazeYaml()
  val unified = withContext(Dispatchers.IO) {
    runCatching { yaml.decodeUnifiedTrail(trailFile.readText()) }
  }.getOrElse {
    // Only a unified single-file trail has per-classifier slots to merge a partial recording into.
    return badRequest(
      "Can't record a step range in ${trailFile.name}: it isn't a unified trail file " +
        "(${it.message ?: "unreadable"}).",
    )
  }
  val runnable = runnableForStepWindow(unified, body.from, body.to)
    ?: return badRequest(
      "Steps ${body.from + 1}-${body.to + 1} are outside this trail's ${unified.trail.size} step(s).",
    )
  val sliceYaml = runCatching { yaml.encodeUnifiedTrailToString(runnable.runnable) }.getOrElse {
    return badRequest("Could not build a runnable trail for those steps: ${it.message}")
  }

  val sessionIds = mutableListOf<String>()
  val errors = mutableListOf<String>()
  for (device in body.deviceIds) {
    val response = when (
      val r = buildRunDispatchResult(
        deps,
        RunRequest(
          trailblazeDeviceId = device,
          yaml = sliceYaml,
          // Recording, so the agent fills each step rather than replaying what is already there.
          useRecordedSteps = false,
          maxLlmCalls = body.maxLlmCalls,
          agent = body.agent,
          selfHeal = body.selfHeal,
          captureVideo = body.captureVideo,
          captureLogcat = body.captureLogcat,
          captureNetworkTraffic = body.captureNetworkTraffic,
          captureIosLogs = body.captureIosLogs,
          captureAnalytics = body.captureAnalytics,
          captureEvents = body.captureEvents,
          // Navigating from this run's card lands on the trail it was recorded against.
          trailId = id,
          recordTrailFile = trailFile,
          recordStepRange = runnable.mergeWindow,
        ),
      )
    ) {
      is RunDispatchResult.Invalid -> RunResponse(success = false, error = r.message)
      is RunDispatchResult.Ok -> r.response
    }
    if (response.sessionId != null) sessionIds += response.sessionId
    else errors += "${device.toFullyQualifiedDeviceId()}: ${response.error ?: "failed to start"}"
  }
  val body = RecordTrailRangeResponse(
    sessionIds = sessionIds,
    error = errors.takeIf { it.isNotEmpty() }?.joinToString("; "),
  )
  // A partial launch is still OK with the per-device errors attached (matching /api/folder/record);
  // nothing at all started is a dispatch failure, not a bad request.
  val status = if (sessionIds.isEmpty()) HttpStatusCode.BadGateway else HttpStatusCode.OK
  return RecordTrailRangeOutcome(status, body)
}

private fun badRequest(error: String) =
  RecordTrailRangeOutcome(HttpStatusCode.BadRequest, RecordTrailRangeResponse(error = error))

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

  // Record a step range of a unified trail back into its own file.
  post("$PATH_BASE/api/trail/record-range") {
    val body = runCatching { call.receive<RecordTrailRangeRequest>() }.getOrNull()
    if (body == null) {
      call.respond(
        HttpStatusCode.BadRequest,
        RecordTrailRangeResponse(error = "a trail id, a step range and at least one device are required"),
      )
      return@post
    }
    val outcome = buildRecordTrailRangeResponse(deps, body)
    call.respond(outcome.status, outcome.body)
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
