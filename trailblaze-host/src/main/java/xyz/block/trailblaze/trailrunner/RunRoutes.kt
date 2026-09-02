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
import kotlinx.datetime.Clock
import kotlinx.coroutines.withContext
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.cli.RECORDING_LOG_STABILITY_MAX_WAIT_MS
import xyz.block.trailblaze.cli.RECORDING_LOG_STABILITY_POLL_MS
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.logs.client.TrailblazeLog
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.logs.model.SessionStatus
import xyz.block.trailblaze.logs.model.getSessionStatus
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.recordings.UnifiedRecordingWriter
import xyz.block.trailblaze.ui.getVersionInfo
import xyz.block.trailblaze.util.Console
import java.io.File
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedTrailItems
import xyz.block.trailblaze.yaml.generateUnifiedRecordedYaml

/**
 * The installed app targets on a device (plus the currently-selected one) — the shared source for
 * both the REST `GET /api/device/apps` route and the `GetDeviceAppsRequest` RPC handler. Returns an
 * empty result for a missing device manager, an unparseable/absent platform, a blank id, or a web
 * device (the UI never queries web), so callers never need to special-case those.
 */
internal suspend fun buildDeviceAppsResponse(
  deps: TrailRunnerDeps,
  platform: String?,
  id: String?,
): DeviceAppsResponse {
  val deviceManager = deps.deviceManager
  val resolvedPlatform = platform?.let { TrailblazeDevicePlatform.fromString(it) }
  if (deviceManager == null || resolvedPlatform == null || id.isNullOrBlank() || resolvedPlatform == TrailblazeDevicePlatform.WEB) {
    return DeviceAppsResponse(targets = emptyList(), currentTargetAppId = null)
  }
  return withContext(Dispatchers.IO) {
    runCatching {
      val deviceId = TrailblazeDeviceId(id, resolvedPlatform)
      // One shared inventory probe (installed IDs + target-relevant versions): populates the
      // manager's flows and coalesces with any concurrent probe, instead of this route paying
      // its own `listapps`/`pm list` plus a per-target version fallback on every request. A
      // failed probe (null) yields no targets, matching this route's benign-empty contract.
      val installed = deviceManager.refreshAppInventory(deviceId) ?: emptySet()
      val versions = deviceManager.appVersionInfoByDeviceFlow.value
      val targets = deviceManager.availableAppTargets.mapNotNull { t ->
        val appId = t.getAppIdIfInstalled(resolvedPlatform, installed) ?: return@mapNotNull null
        val v = versions.getVersionInfo(deviceId, appId)
          ?: runCatching { MobileDeviceUtils.getAppVersionInfo(deviceId, appId) }.getOrNull()
        DeviceAppDto(
          id = t.id,
          displayName = t.displayName,
          appId = appId,
          versionName = v?.versionName,
          versionCode = v?.versionCode,
          buildNumber = v?.buildNumber,
          minOsVersion = v?.minOsVersion,
        )
      }.sortedBy { it.displayName.lowercase() }
      DeviceAppsResponse(targets = targets, currentTargetAppId = deviceManager.settingsRepo.getCurrentSelectedTargetApp()?.id)
    }.getOrElse { e ->
      Console.log("[TrailRunnerEndpoint] device apps query failed: ${e.message}")
      DeviceAppsResponse(targets = emptyList(), currentTargetAppId = null)
    }
  }
}

/**
 * Every installed app on a device, unfiltered by declared targets — the source for the Create
 * Target form's "Browse installed apps" picker (`GetInstalledAppsRequest`). Same benign-empty
 * contract as [buildDeviceAppsResponse]: an unparseable/absent platform, blank id, web/desktop
 * device (no installable inventory), or a probe failure all return an empty list.
 *
 * [includeSystemApps] defaults to excluding OS/preinstalled packages — the common case is
 * targeting a user/sideloaded install, and the ~200 system packages on a stock image would bury
 * it. Pass true to reach a preinstalled app (e.g. the device's own browser or calculator) as a
 * target — that's a legitimate thing to want to drive, not an edge case to hide.
 *
 * Android's host/adb inventory (`dumpsys package packages`) carries no human label — those rows
 * fall back to showing the app id in the picker; iOS (`simctl listapps`) has display names.
 */
internal suspend fun buildInstalledAppsResponse(platform: String?, id: String?, includeSystemApps: Boolean = false): InstalledAppsResponse {
  val resolvedPlatform = platform?.let { TrailblazeDevicePlatform.fromString(it) }
  if (resolvedPlatform == null || id.isNullOrBlank()) return InstalledAppsResponse(apps = emptyList())
  return withContext(Dispatchers.IO) {
    runCatching {
      val apps = MobileDeviceUtils.listInstalledAppsDetailed(TrailblazeDeviceId(id, resolvedPlatform))
      // Memoize for the per-app badge endpoints (they need installPath/version without re-probing),
      // and let warm-cached labels (from a previous badge extraction) ride the list itself.
      InstalledAppBadges.rememberInventory(resolvedPlatform, id, apps)
      val labeled = apps.map { app -> app.label?.let { app } ?: app.copy(label = InstalledAppBadges.peekLabel(resolvedPlatform, app)) }
      InstalledAppsResponse(apps = toInstalledAppPickerDtos(labeled, includeSystemApps))
    }.getOrElse { e ->
      // Name the device: a broken adb/simctl otherwise reads as "no apps" with nothing to go on.
      Console.log("[TrailRunnerEndpoint] installed apps query failed for $platform/$id: ${e.message}")
      InstalledAppsResponse(apps = emptyList())
    }
  }
}

/**
 * Shapes a raw device inventory for the Create Target picker: system apps dropped unless
 * [includeSystemApps], sorted by label (falling back to app id), then app id as a tiebreaker —
 * two builds of the same app (prod vs. debug/internal) commonly share a label, and without the
 * tiebreaker their relative order would depend on probe order rather than being deterministic.
 * Pure for unit-testing without a device.
 */
internal fun toInstalledAppPickerDtos(apps: List<InstalledApp>, includeSystemApps: Boolean = false): List<InstalledAppDto> = apps
  .filter { includeSystemApps || !it.isSystemApp }
  .map { InstalledAppDto(appId = it.appId, label = it.label, version = it.version) }
  .sortedWith(compareBy({ (it.label ?: it.appId).lowercase() }, { it.appId }))

/**
 * The outcome of a run-dispatch: an [RunResponse] once the run is kicked off (its `success=false`
 * carries a dispatch failure), or an [RunDispatchResult.Invalid] for a precondition the REST route
 * renders as a non-2xx `{error}` (503 no deviceManager, 400 blank yaml) and the RPC handler maps to
 * `RpcResult.Failure`. A malformed request body stays a transport-level 400 in the REST route.
 */
internal sealed interface RunDispatchResult {
  data class Ok(val response: RunResponse) : RunDispatchResult

  data class Invalid(val status: HttpStatusCode, val message: String) : RunDispatchResult
}

/**
 * Kicks off a recording-tab replay run — the shared source for both the REST `POST /api/run` route
 * and the `RunRequest` RPC handler. Dispatch is async: a successful [RunResponse] just carries the
 * new sessionId; the run's own success/failure surfaces later through the session status.
 */
internal suspend fun buildRunDispatchResult(deps: TrailRunnerDeps, body: RunRequest): RunDispatchResult {
  val deviceManager = deps.deviceManager
    ?: return RunDispatchResult.Invalid(HttpStatusCode.ServiceUnavailable, "deviceManager not available")
  val id = body.trailblazeDeviceId
  val yaml = body.yaml
  if (yaml.isBlank()) {
    return RunDispatchResult.Invalid(HttpStatusCode.BadRequest, "yaml is required")
  }
  // Reject a duplicate dispatch while the device already has an active (or still-initializing)
  // run. Without this gate, a click stampede creates one parallel session + capture pipeline per
  // click on the same device (16 observed), each superseded run stranded as a zombie "Running"
  // row. A tracked session with no logs yet (info == null) is the stampede window between
  // dispatch and first log, so it counts as active.
  val activeSessionId = deviceManager.getCurrentSessionIdForDevice(id)
  if (activeSessionId != null) {
    // Fresh per-session read (re-runs the abandonment heuristic), NOT sessionInfoFlow: the flow
    // only rebuilds on filesystem events, so a wedged session that stopped writing logs stays
    // frozen at Started in the flow forever - a phantom 409 that outlives the session it names,
    // while the Active tab (which reads the fresh summary) says the same session already ended.
    val info = deps.logsRepo.getSessionInfoSummary(activeSessionId)
    val ended = info != null && info.latestStatus is xyz.block.trailblaze.logs.model.SessionStatus.Ended
    if (ended) {
      // The session is over but still registered as the device's holder (its cleanup never ran -
      // e.g. abandoned after a wedge). Release the device and let this dispatch proceed. Pass the
      // session id so its capture streams stop even if the device mapping is already gone.
      runCatching { deviceManager.cancelSessionForDevice(id, knownSessionId = activeSessionId) }
    } else {
      // info == null is the stampede window between dispatch and first log - still counts as
      // active so a click stampede can't stack parallel sessions on one device.
      val holder = when {
        activeSessionId.value.startsWith("yaml") -> "a Studio conversation"
        activeSessionId.value.startsWith("recording") -> "a trail run"
        else -> "a session"
      }
      return RunDispatchResult.Invalid(
        HttpStatusCode.Conflict,
        "This device is busy: $holder (${activeSessionId.value}) is still using it. " +
          "Stop it from the Active tab or wait for it to finish before starting another run.",
      )
    }
  }
  // Explicit per-request agent wins; otherwise fall back to the persisted global agent setting
  // (the one the UI's run-controls agent picker edits), then the built-in default.
  val agentImpl = body.agent
    ?.let { a -> runCatching { AgentImplementation.valueOf(a) }.getOrNull() }
    ?: deps.settingsRepo?.serverStateFlow?.value?.appConfig?.agentImplementation
    ?: AgentImplementation.DEFAULT
  val captureAnalyticsOn = body.captureAnalytics == true
  val analyticsCapture: AutoCloseable? =
    if (captureAnalyticsOn) runCatching { deps.analyticsCaptureStarter?.invoke(id) }.getOrNull() else null
  // Dedicated event-stream capture. The public Trail Runner contract is producer-agnostic; hosts can
  // wire a capture controller for app-specific stream producers. Event capture can ride the same
  // host bridge as network capture, so the run path forces network capture on when requested.
  val captureEventsOn = body.captureEvents == true
  var eventCapture: AutoCloseable? = null
  // The reservation this dispatch made (see releaseUnstartedRun) - tracked outside the runCatching
  // so the failure path below can hand the device back even when runYaml threw.
  var reservedSessionId: SessionId? = null
  val result = runCatching {
    val resolution = deviceManager.getOrCreateSessionResolution(
      trailblazeDeviceId = id,
      forceNewSession = true,
      sessionIdPrefix = "recording",
      captureVideoOverride = body.captureVideo,
      captureLogcatOverride = body.captureLogcat,
      captureIosLogsOverride = body.captureIosLogs,
    )
    reservedSessionId = resolution.sessionId
    val sessionId = resolution.sessionId.value
    eventCapture = runCatching { deps.eventCaptureController?.invoke(sessionId, captureEventsOn) }
      .onFailure { Console.log("[TrailRunnerEndpoint] event capture setup failed: ${it.message}") }
      .getOrNull()
    body.trailId?.takeIf { it.isNotBlank() }?.let { trailId ->
      runCatching {
        val dir = File(deps.logsRepo.logsDir, sessionId)
        dir.mkdirs()
        File(dir, ".trailrunner-trail-id").writeText(trailId)
      }
    }
    val companionRel = companionRelFor(body.bundleId, body.trailId)
    deviceManager.runYaml(
      yamlToRun = yaml,
      trailblazeDeviceId = id,
      sendSessionStartLog = true,
      sendSessionEndLog = true,
      existingSessionId = resolution.sessionId,
      referrer = TrailblazeReferrer.RECORDING_TAB_REPLAY,
      agentImplementation = agentImpl,
      selfHeal = body.selfHeal,
      useRecordedSteps = body.useRecordedSteps
        ?: runCatching { createTrailblazeYaml().hasRecordedSteps(yaml) }.getOrDefault(false),
      maxLlmCalls = body.maxLlmCalls,
      initialMemorySeeds = body.memory,
      initialMemorySensitiveSeeds = body.secrets,
      captureNetworkTrafficOverride = if (captureEventsOn) true else body.captureNetworkTraffic,
      // Thread the "Capture video" toggle into the run so web / Electron (self-instrumented,
      // coordinator-skipped) honor it — the getOrCreateSessionResolution override above only
      // reaches the Android/iOS coordinator path.
      captureVideoOverride = body.captureVideo,
      onComplete = { result ->
        analyticsCapture?.let { c -> runCatching { c.close() } }
        eventCapture?.let { c -> runCatching { c.close() } }
        // When this run was a bundle recording (bundleId + variant set), write the recorded
        // <variant>.trail.yaml back into the bundle folder. No-op for ordinary runs.
        maybeWriteBundleVariant(deps, body, sessionId)
        companionRel?.let {
          ExternalAgentSupervisor.announceRunStatusForFolder(
            relPath = it,
            started = false,
            sessionId = sessionId,
            status = runFinishedStatus(result),
          )
        }
        releaseUnstartedRun(deps, id, resolution.sessionId)
        // When it was a unified-trail recording (recordTrailFile set), merge the recording back into
        // that file's classifier slot. A no-op for ordinary runs, and last because it waits for the
        // device to finish flushing its tool logs - nothing else here should wait behind that.
        maybeMergeIntoUnifiedTrail(deps, body, sessionId)
      },
    )
    // Companion sessions watching this trail's folder hear the dispatch. Only after runYaml
    // accepts it - a dispatch that throws above never announces a start it didn't make. The
    // fan-out appends each listener's journal synchronously, but it's bounded by the companion
    // session cap, so the dispatch response is delayed by at most a handful of small file writes.
    companionRel?.let { ExternalAgentSupervisor.announceRunStatusForFolder(it, started = true, sessionId = sessionId) }
    // Dispatch is async: the caller gets the sessionId immediately and follows the
    // run through the session status; failures surface there, not on this response.
    RunResponse(success = true, sessionId = sessionId)
  }.getOrElse { e ->
    analyticsCapture?.let { runCatching { it.close() } }
    eventCapture?.let { runCatching { it.close() } }
    reservedSessionId?.let { releaseUnstartedRun(deps, id, it) }
    Console.log("[TrailRunnerEndpoint] POST /api/run error: ${e.message}")
    RunResponse(success = false, sessionId = null, error = e.message ?: "internal error")
  }
  return RunDispatchResult.Ok(result)
}

/**
 * Hands the device back when a dispatch never became a run.
 *
 * [buildRunDispatchResult] registers the device as busy (via `getOrCreateSessionResolution`) BEFORE
 * the run starts, and the busy-device gate above only clears a registration once the session it
 * names has ended. A run that never starts writes no session log at all - a trail short-circuited by
 * `config.skip`, a device that went away, a rejected driver pin, or a dispatch that threw - so that
 * gate has nothing to read and treats the device as busy forever: every later run on it 409s until
 * the daemon restarts. Releasing it here is what keeps a failed dispatch a failed dispatch instead
 * of a lost device.
 *
 * Only releases a run that never opened a session: a session with logs did start, so its own
 * teardown owns the device. Whether the registration is still THIS dispatch's is decided inside
 * [TrailblazeDeviceManager.releaseUnstartedSession], which compares and clears under one lock so a
 * newer run that has taken the device cannot be released by a late callback from an older one.
 */
private fun releaseUnstartedRun(deps: TrailRunnerDeps, deviceId: TrailblazeDeviceId, sessionId: SessionId) {
  val deviceManager = deps.deviceManager ?: return
  if (deps.logsRepo.getSessionInfoSummary(sessionId) != null) return
  if (runCatching { deviceManager.releaseUnstartedSession(deviceId, sessionId) }.getOrDefault(false)) {
    Console.log("[TrailRunnerEndpoint] released ${deviceId.instanceId}: run ${sessionId.value} never started")
  }
}

internal fun Route.runRoutes(deps: TrailRunnerDeps) {
  post("$PATH_BASE/api/run") {
    val body = runCatching { call.receive<RunRequest>() }.getOrElse { e ->
      Console.log("[TrailRunnerEndpoint] POST /api/run bad body: ${e.message}")
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid JSON body"))
      return@post
    }
    when (val result = buildRunDispatchResult(deps, body)) {
      is RunDispatchResult.Invalid -> call.respond(result.status, mapOf("error" to result.message))
      is RunDispatchResult.Ok ->
        call.respondText(
          text = JSON.encodeToString(RunResponse.serializer(), result.response),
          contentType = ContentType.Application.Json,
        )
    }
  }

  get("$PATH_BASE/api/device/apps") {
    val platform = call.request.queryParameters["platform"]
    val instanceId = call.request.queryParameters["id"]
    call.respondText(
      text = JSON.encodeToString(DeviceAppsResponse.serializer(), buildDeviceAppsResponse(deps, platform, instanceId)),
      contentType = ContentType.Application.Json,
    )
  }
}

// The run's library path relative to the primary root, when the dispatch named one - the key that
// routes run-started/run-finished to companion sessions. "0/<rel>" is the primary-root marker in
// both trailId and bundleId (bundleId wins: it names the folder, not a variant file); extras roots
// (1/, 2/, ...) are outside companion scope, so runs from them never announce. Raw-YAML dispatches
// (no id at all) stay silent too - there's no folder to attribute them to. trailId is a
// caller-claimed field (unlike the @Transient bundleId), so a local caller can aim these events at
// any folder - accepted: the events are advisory LIFECYCLE only (title and payload stay
// daemon-built), and anyone who can POST /api/run could generate the same events genuinely by
// running a real trail there. Same trust stance as the caller-claimed .trailrunner-trail-id marker.
internal fun companionRelFor(bundleId: String?, trailId: String?): String? = sequenceOf(bundleId, trailId)
  .mapNotNull { id -> id?.takeIf { it.startsWith("0/") }?.substringAfter('/')?.takeIf { it.isNotEmpty() } }
  .firstOrNull()

// The run-finished status vocabulary the companion contract promises agents; exhaustive over
// [TrailExecutionResult] so a new outcome is a compile error here, not a silent contract gap.
internal fun runFinishedStatus(result: TrailExecutionResult): String = when (result) {
  is TrailExecutionResult.Success -> "succeeded"
  is TrailExecutionResult.Failed -> "failed"
  is TrailExecutionResult.Cancelled -> "cancelled"
}

// Materialize a finished bundle-recording run into its bundle folder. No-op unless the run carried
// both [RunRequest.bundleId] and [RunRequest.variant] - @Transient fields only the server-side
// `/api/folder/record` dispatch can set, so a raw REST/RPC caller can never land a recorded
// variant in a library folder. Reuses the same logs→YAML conversion as the session export endpoint.
private fun maybeWriteBundleVariant(deps: TrailRunnerDeps, body: RunRequest, sessionId: String) {
  val bundleId = body.bundleId?.takeIf { it.isNotBlank() } ?: return
  val variant = body.variant?.takeIf { it.isNotBlank() } ?: return
  runCatching {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    val resolved = BundleStore.resolve(bundleId, primary, extras)
      ?: return Console.log("[BlazeRoutes] bundle $bundleId no longer resolvable (moved during recording?); variant '$variant' from session $sessionId not written")
    val logs = deps.logsRepo.getLogsForSession(SessionId(sessionId))
    if (logs.isEmpty()) {
      return Console.log("[BlazeRoutes] no logs for session $sessionId; variant '$variant' not written")
    }
    // The variant IS the recording device's platform name, so it keys the unified recording slot.
    // Unified emitter falls back to v1 only when it can't produce the map shape (blank classifier,
    // empty trail, multi-tool trailhead).
    val yaml = logs.generateUnifiedRecordedYaml(createTrailblazeYaml(), classifierOverride = variant)
    val written = BundleStore.writeVariant(resolved.dir, variant, yaml)
    // A recording landing in the folder is the same fact whether a human saved it from the
    // companion view or the board's record flow wrote it here - companion sessions watching the
    // folder hear both. Primary root only: companion folders resolve against rootIdx 0. On this
    // path the variant IS the recording device's platform name (see /api/folder/record).
    if (written != null && resolved.rootIdx == 0) {
      ExternalAgentSupervisor.announceRecordingSavedForFolder(
        relFolder = resolved.home,
        file = written.name,
        platform = variant,
      )
    }
  }.onFailure { Console.log("[BlazeRoutes] bundle variant write failed: ${it.message}") }
}

/**
 * Records what the save-back did in the run's OWN log, so the outcome is part of the run rather than
 * a line in whichever console started the daemon. Both verdicts land here: a merge that wrote the
 * file, and every refusal that left it alone.
 *
 * A [TrailblazeLog.TrailblazeProgressLog] because it is a run-scoped note rather than a step - the
 * same shape [xyz.block.trailblaze.host.yaml.PendingSessionStartAdvisories] uses for pre-run
 * warnings, and one the session views already render.
 */
private fun logSaveBackOutcome(
  deps: TrailRunnerDeps,
  sessionId: String,
  message: String,
  ok: Boolean?,
  eventType: String = RECORDING_SAVE_BACK_EVENT_TYPE,
) {
  runCatching {
    deps.logsRepo.saveLogToDisk(
      TrailblazeLog.TrailblazeProgressLog(
        eventType = eventType,
        description = message,
        success = ok,
        session = SessionId(sessionId),
        timestamp = Clock.System.now(),
      ),
    )
  }.onFailure { Console.log("[TrailRunnerEndpoint] could not log the save-back outcome: ${it.message}") }
}

/** Marks the save-back note in a session's log; a reader filtering the timeline can key on it. */
internal const val RECORDING_SAVE_BACK_EVENT_TYPE = "RecordingSavedBack"

/**
 * Marks that a save-back is under way, written before the wait for the device's tool logs - which can
 * take up to [RECORDING_LOG_STABILITY_MAX_WAIT_MS].
 *
 * It exists so a reader can tell "this run is still deciding" from "this run never recorded". Only
 * the daemon knows a run carried a trail file, so without this note a surface watching for a verdict
 * would have to poll every finished run on the chance that one is coming.
 */
internal const val RECORDING_SAVE_BACK_PENDING_EVENT_TYPE = "RecordingSaveBackPending"

/**
 * Why this run's recording must NOT be written back into [trailFile], or null when it may be.
 *
 * Both reasons are facts about the run and the file, not about the recording - the writer's refusals
 * already cover everything the recording itself can get wrong.
 *
 * - A run that didn't pass has an agent's flailing in it, not a recording. Saving it would replace a
 *   working recording with a failed attempt, and the CLI's save-back has always been success-gated.
 *   [status] is the session's own terminal status rather than the run callback's result, because the
 *   callback reports success for an on-device run the moment the RPC returns - the same reason the
 *   CLI reconciles its exit code against the session logs.
 * - A file renamed or deleted mid-run would fall through the writer's directory resolution and land
 *   in the folder's shared `trail.yaml` as a greenfield write - a different file than the one the
 *   run was dispatched against. (The writer refuses that write too, under its lock, since a trail
 *   this run never ran can't hold the steps it recorded; this just says so in the run's own terms.)
 */
internal fun saveBackRefusal(status: SessionStatus, trailFile: File): String? = when {
  status !is SessionStatus.Ended ->
    "Recording not saved back into ${trailFile.name}: this run never reported a finished status, so there " +
      "is no recording to trust."

  status !is SessionStatus.Ended.Succeeded && status !is SessionStatus.Ended.SucceededWithSelfHeal ->
    "Recording not saved back into ${trailFile.name}: the run ended as " +
      "${status::class.simpleName?.lowercase() ?: "unsuccessful"}, so nothing was written. Re-record once it passes."

  !trailFile.isFile ->
    "Recording not saved back into ${trailFile.name}: that file was renamed or deleted while the run was going."

  else -> null
}

/**
 * The tool logs a recording is actually built from: the same `isRecordable` population
 * [xyz.block.trailblaze.yaml.generateRecordedTrailItems] keeps. Counting the rest would let a run
 * whose only tool calls are non-recordable author utilities look like it recorded something.
 */
internal fun List<TrailblazeLog>.recordableToolLogs(): List<TrailblazeLog.TrailblazeToolLog> =
  filterIsInstance<TrailblazeLog.TrailblazeToolLog>().filter { it.isRecordable }

/**
 * True once [this] session's logs hold everything a recording is built from.
 *
 * An on-device run logs each tool twice: the nodeId-keyed `DelegatingTrailblazeToolLog` immediately,
 * and the selector-keyed `TrailblazeToolLog` the recording is actually made of when the device gets
 * around to flushing it - sometimes tens of seconds after the run ended. Merging before then writes
 * a recording that is missing the steps that hadn't landed yet, over one that was complete.
 *
 * The same traced-tool-for-traced-tool wait the CLI's own save-back does, counted per trace rather
 * than set-compared: one delegating tool expands into several executable ones under a single trace,
 * and the first child landing doesn't mean its siblings have. Each parent advertises how many it
 * delegated to, so that is what's counted.
 *
 * A trace is also done when one of its tools FAILED. Execution abandons an expansion at its first
 * failing child, so the tools after it never run and never log - waiting for the advertised count
 * would burn the whole budget and then refuse a save-back the run had earned, which is exactly the
 * self-healed re-record this feature exists for.
 *
 * Two populations, deliberately: per-trace completeness counts every executed tool (that is what a
 * parent's advertised count is a count of), while "did this run record anything, and has it stopped
 * arriving" counts only the logs a recording is built from.
 *
 * The per-trace count alone would settle a run that has no delegating parent to count against.
 * Most tools an agent picks are executed directly, and a directly-executed tool logs no parent at
 * all - so its expectation set is empty and every count is trivially satisfied while the device is
 * still flushing. Hence [previousRecordableCount]: the recording's own logs have to be non-empty and
 * have to have stopped arriving since the last look. A burst in progress is not a stable count, and
 * the device sends no "that was all of them" to wait for instead.
 *
 * One residual limit, accepted rather than papered over: several tool calls from one LLM response
 * share a trace, so an independent call landing on the same trace as a delegated expansion can
 * satisfy that trace's count while a sibling is still in flight. Telling them apart needs a batch
 * identity the logs do not carry. The stable-count requirement is what stands in for it - a sibling
 * that lands late moves the count and unsettles the run - and the caller refuses to write when this
 * never becomes true rather than treating the timeout as permission.
 */
internal fun List<TrailblazeLog>.recordableLogsSettled(previousRecordableCount: Int): Boolean {
  if (getSessionStatus() !is SessionStatus.Ended) return false
  val expectedPerTrace = filterIsInstance<TrailblazeLog.DelegatingTrailblazeToolLog>()
    .mapNotNull { log -> log.traceId?.let { it to log.executableTools.size.coerceAtLeast(1) } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, counts) -> counts.sum() }
  val recordable = recordableToolLogs()
  if (recordable.isEmpty() || recordable.size != previousRecordableCount) return false
  // Per-trace completeness counts EVERY executed tool, not just the recordable ones: what it is
  // compared against is `executableTools`, which is every tool the parent delegated to. Counting
  // only the recordable half against that total never reaches it, and a batch holding one
  // non-recordable tool would wait out the whole budget and then refuse a save-back it earned.
  val recorded = filterIsInstance<TrailblazeLog.TrailblazeToolLog>().filter { it.traceId != null }
  val recordedPerTrace = recorded.groupingBy { it.traceId!! }.eachCount()
  val abandonedTraces = recorded.filterNot { it.successful }.mapNotNull { it.traceId }.toSet()
  return expectedPerTrace.all { (traceId, expected) ->
    traceId in abandonedTraces || (recordedPerTrace[traceId] ?: 0) >= expected
  }
}

/**
 * How long the recording's own log count has to hold still before a save-back believes the device is
 * done sending. Deliberately several poll intervals: a device flushes selector logs in bursts, and a
 * single unchanged look lands inside a gap between two of them as easily as after the last one.
 */
private const val RECORDING_LOG_STABILITY_QUIET_MS = 10_000L

/**
 * Merge a finished recording run back into the unified trail file it was recorded against. No-op
 * unless the run carried [RunRequest.recordTrailFile] - a @Transient field only the server-side
 * `/api/trail/record-range` dispatch can set, so no REST/RPC caller can name a file to write.
 *
 * The counterpart to [maybeWriteBundleVariant] for the single-file layout: a bundle's recording is a
 * whole sibling file to replace, while a unified trail's recording is one classifier's legs to merge
 * into the file that is already there. [RunRequest.recordStepRange] scopes the merge to the steps the
 * run actually covered, so recording from step 6 does not touch steps 1-5.
 *
 * Every refusal is logged rather than thrown: the run itself already succeeded, and the recording is
 * still readable from the session, so a merge the writer declines must not look like a failed run.
 */
private fun maybeMergeIntoUnifiedTrail(deps: TrailRunnerDeps, body: RunRequest, sessionId: String) {
  val trailFile = body.recordTrailFile ?: return
  runCatching {
    var logs = deps.logsRepo.getLogsForSession(SessionId(sessionId))
    if (logs.isEmpty()) {
      // A dispatch that died before opening a session has nothing to annotate, and writing a log
      // here would MAKE a session out of it - one holding a progress note and no status, which
      // every reader derives as a run stuck at "unknown". Console only.
      return Console.log("[TrailRunnerEndpoint] no logs for session $sessionId; nothing merged into ${trailFile.name}")
    }
    // A run that has already ended badly is refused before the wait rather than after it: nothing
    // arriving in the next two minutes can make a cancelled run's recording savable, and the reader
    // would sit in front of a "saving..." note the whole time.
    val statusBeforeWaiting = logs.getSessionStatus()
    if (statusBeforeWaiting is SessionStatus.Ended) {
      saveBackRefusal(statusBeforeWaiting, trailFile)?.let { refusal ->
        Console.info(refusal)
        return logSaveBackOutcome(deps, sessionId, refusal, false)
      }
    }
    // Announced before the wait, not after it: everything below can take two minutes, and a reader
    // that arrives in the meantime should see that a verdict is coming.
    logSaveBackOutcome(
      deps,
      sessionId,
      "Saving this run's recording back into ${trailFile.name}...",
      null,
      RECORDING_SAVE_BACK_PENDING_EVENT_TYPE,
    )
    // Same wait, and the same budget, as the CLI's own save-back path. The count from the previous
    // look is what makes "the device has stopped sending" answerable at all, so it is carried across
    // iterations rather than recomputed inside the gate.
    //
    // One unchanged look is not a quiet period. A device flushes selector logs in bursts with gaps
    // between them, so the gate has to hold across [RECORDING_LOG_STABILITY_QUIET_MS] before this
    // accepts it - otherwise a run whose tools all executed directly (no advertised count to satisfy)
    // settles in the first gap and merges half its recording.
    val deadline = System.currentTimeMillis() + RECORDING_LOG_STABILITY_MAX_WAIT_MS
    val requiredQuietChecks = (RECORDING_LOG_STABILITY_QUIET_MS / RECORDING_LOG_STABILITY_POLL_MS).toInt()
    var previousRecordableCount = -1
    var quietChecks = 0
    var settled = false
    while (true) {
      quietChecks = if (logs.recordableLogsSettled(previousRecordableCount)) quietChecks + 1 else 0
      settled = quietChecks >= requiredQuietChecks
      if (settled || System.currentTimeMillis() >= deadline) break
      previousRecordableCount = logs.recordableToolLogs().size
      Thread.sleep(RECORDING_LOG_STABILITY_POLL_MS)
      logs = deps.logsRepo.getLogsForSession(SessionId(sessionId))
    }
    saveBackRefusal(logs.getSessionStatus(), trailFile)?.let { refusal ->
      Console.info(refusal)
      return logSaveBackOutcome(deps, sessionId, refusal, false)
    }
    if (!settled) {
      // Waited the full budget. Either the run logged no tool calls to record, or the device is still
      // holding some: writing either way replaces a complete recording with a partial or empty one,
      // and every count guard would pass while it did.
      val message = if (logs.recordableToolLogs().isEmpty()) {
        "Recording not saved back into ${trailFile.name}: this run logged no tool calls to record, so there " +
          "was nothing to save into those steps."
      } else {
        "Recording not saved back into ${trailFile.name}: the device was still sending this run's " +
          "tool calls after ${RECORDING_LOG_STABILITY_MAX_WAIT_MS / 1_000}s, so saving now would drop the ones " +
          "still in flight. Re-record when the device is responsive."
      }
      Console.info(message)
      return logSaveBackOutcome(deps, sessionId, message, false)
    }
    // The recording device's own classifier chain keys the slot - the same key the CLI's save-back
    // and the desktop's Save use. This path is single-device by construction (the record-range route
    // dispatches one run per selected device), so there is no configuration name to key by. A trail
    // that declares a multi-device cast is refused by the writer with a message rather than having a
    // member's run written into the cast's leg: recording a cast is one run of the whole cast, which
    // per-device dispatch is not.
    val deviceInfo = logs
      .filterIsInstance<TrailblazeLog.TrailblazeSessionStatusChangeLog>()
      .map { it.sessionStatus }
      .filterIsInstance<SessionStatus.Started>()
      .firstOrNull()
      ?.trailblazeDeviceInfo
    val classifier = deviceInfo?.classifiers.orEmpty().joinToString("-") { it.classifier }
    if (classifier.isBlank()) {
      val message = "Recording not saved back into ${trailFile.name}: this run reported no device classifier to key its recording by."
      Console.log("[TrailRunnerEndpoint] $message")
      return logSaveBackOutcome(deps, sessionId, message, false)
    }
    // The document this run executed IS the expectation the merge is held to, passed whole rather
    // than field by field: for a step range it is the slice (its steps, no trailhead), for a
    // whole-trail recording the trail as authored. The writer compares it under the same lock it
    // writes with, so a trail edited - reworded, retargeted at another app, given a trailhead -
    // while the run was in flight is refused rather than handed these tool calls under someone
    // else's prose.
    //
    // A document that won't decode is a refusal, not a merge with the check switched off: passing
    // null here would turn the drift comparison off entirely, so the one case where we can't say
    // what the run executed would also be the case where nothing checks the file it lands in.
    val dispatched = runCatching { createTrailblazeYaml().decodeUnifiedTrail(body.yaml) }.getOrElse {
      val message = "Recording not saved back into ${trailFile.name}: the trail this run executed " +
        "could not be read back (${it.message ?: it::class.simpleName}), so there is nothing to hold " +
        "the merge to. The trail was left unchanged."
      Console.info(message)
      return logSaveBackOutcome(deps, sessionId, message, false)
    }
    val outcome = UnifiedRecordingWriter.mergeIntoUnified(
      trailFileOrDir = trailFile,
      recordedItems = logs.generateRecordedTrailItems(createTrailblazeYaml()),
      classifier = classifier,
      stepWindow = body.recordStepRange,
      expectedDispatched = dispatched,
    )
    val verdict = when (outcome) {
      is UnifiedRecordingWriter.MergeOutcome.Merged ->
        "Recording saved back into ${outcome.target.name} (classifier `$classifier`)." to true

      is UnifiedRecordingWriter.MergeOutcome.StepWindowMismatch ->
        UnifiedRecordingWriter.stepWindowMismatchMessage(
          outcome.target,
          outcome.window,
          outcome.expectedStepCount,
          outcome.recordedStepCount,
        ) to false

      is UnifiedRecordingWriter.MergeOutcome.StepWindowOutOfRange ->
        UnifiedRecordingWriter.stepWindowOutOfRangeMessage(
          outcome.target,
          outcome.window,
          outcome.existingStepCount,
        ) to false

      is UnifiedRecordingWriter.MergeOutcome.RefusedCorrupt ->
        UnifiedRecordingWriter.corruptRefusalMessage(outcome.target, outcome.reason) to false

      is UnifiedRecordingWriter.MergeOutcome.SkippedMultiDeviceTrail ->
        UnifiedRecordingWriter.multiDeviceMergeSkippedMessage(outcome.target, outcome.configurationNames) to false

      // A save-back passes no cast to declare, so this can't fire here. Reported rather than
      // swallowed, so a future caller that does pass one gets the refusal on the banner instead of a
      // silent no-write.
      is UnifiedRecordingWriter.MergeOutcome.SynthesizedCastWouldBeShadowed ->
        UnifiedRecordingWriter.synthesizedCastShadowedMessage(outcome.target, outcome.siblingFileNames) to false

      is UnifiedRecordingWriter.MergeOutcome.ConfigurationNotDeclared ->
        UnifiedRecordingWriter.configurationNotDeclaredMessage(
          outcome.target,
          outcome.configurationName,
          outcome.declaredConfigurationNames,
        ) to false

      is UnifiedRecordingWriter.MergeOutcome.SteplessIntoExistingTrail ->
        UnifiedRecordingWriter.STEPLESS_INTO_EXISTING_MESSAGE to false

      is UnifiedRecordingWriter.MergeOutcome.SkippedEmpty ->
        UnifiedRecordingWriter.EMPTY_MERGE_MESSAGE to false

      is UnifiedRecordingWriter.MergeOutcome.TrailChangedUnderRun ->
        UnifiedRecordingWriter.trailChangedUnderRunMessage(outcome.target, outcome.changed) to false

      is UnifiedRecordingWriter.MergeOutcome.NoTarget ->
        "Recording not merged: no unified trail resolved for ${trailFile.absolutePath}." to false
    }
    Console.info(verdict.first)
    logSaveBackOutcome(deps, sessionId, verdict.first, verdict.second)
  }.onFailure {
    // The banner is the only surface this run has, so an unexpected merge failure has to reach it
    // too - a silent console line reads exactly like a save-back that never ran.
    val message = "Recording not saved back into ${trailFile.name}: the merge failed (${it.message ?: it::class.simpleName})."
    Console.log("[TrailRunnerEndpoint] $message")
    logSaveBackOutcome(deps, sessionId, message, false)
  }
}
