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
import xyz.block.trailblaze.device.InstalledApp
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.logs.model.SessionId
import xyz.block.trailblaze.model.TrailExecutionResult
import xyz.block.trailblaze.ui.getVersionInfo
import xyz.block.trailblaze.util.Console
import java.io.File
import xyz.block.trailblaze.yaml.createTrailblazeYaml
import xyz.block.trailblaze.yaml.generateRecordedYaml

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
      val installed = MobileDeviceUtils.getInstalledAppIds(deviceId)
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
  val result = runCatching {
    val resolution = deviceManager.getOrCreateSessionResolution(
      trailblazeDeviceId = id,
      forceNewSession = true,
      sessionIdPrefix = "recording",
      captureVideoOverride = body.captureVideo,
      captureLogcatOverride = body.captureLogcat,
      captureIosLogsOverride = body.captureIosLogs,
    )
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
    Console.log("[TrailRunnerEndpoint] POST /api/run error: ${e.message}")
    RunResponse(success = false, sessionId = null, error = e.message ?: "internal error")
  }
  return RunDispatchResult.Ok(result)
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
    val yaml = logs.generateRecordedYaml(createTrailblazeYaml())
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
