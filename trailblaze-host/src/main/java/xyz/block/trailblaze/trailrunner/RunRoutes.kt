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
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.devices.TrailblazeDevicePlatform
import xyz.block.trailblaze.host.ios.MobileDeviceUtils
import xyz.block.trailblaze.llm.TrailblazeReferrer
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.logs.model.SessionId
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
      onComplete = {
        analyticsCapture?.let { c -> runCatching { c.close() } }
        eventCapture?.let { c -> runCatching { c.close() } }
        // When this run was a draft-blaze recording (draftId + variant set), write the recorded
        // <variant>.trail.yaml back into the draft folder. No-op for ordinary runs.
        maybeWriteDraftVariant(deps, body, sessionId)
      },
    )
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

// Materialize a finished draft-recording run into its draft folder. No-op unless the run carried
// both [RunRequest.draftId] and [RunRequest.variant]. Reuses the same logs→YAML conversion as the
// session export endpoint.
private fun maybeWriteDraftVariant(deps: TrailRunnerDeps, body: RunRequest, sessionId: String) {
  val draftId = body.draftId?.takeIf { it.isNotBlank() } ?: return
  val variant = body.variant?.takeIf { it.isNotBlank() } ?: return
  runCatching {
    val (primary, extras) = resolveRoots(deps.trailsRootProvider)
    // KNOWN LIMITATION: commit (save-to) is not yet serialized against an in-flight recording for the
    // same draft (no running-session registry is plumbed here). The UI gates this via `anyRunning`;
    // server-side we fail safe — if the folder was moved/committed between dispatch and now, we refuse
    // to write rather than overwrite a committed trail, and log it so the dropped recording is visible.
    // The recording itself is still recoverable from the session logs via the export endpoint.
    val resolved = DraftStore.resolve(draftId, primary, extras)
      ?: return Console.log("[BlazeRoutes] draft $draftId no longer resolvable (committed/moved during recording?); variant '$variant' from session $sessionId not written")
    // `/api/run` accepts draftId/variant from any caller; never let a run completion write into a
    // folder that has left drafts/ (a committed trail in the library) — UNLESS the caller explicitly
    // opted in (the `/api/folder/record` path is intentionally editing a committed library folder).
    if (!resolved.inDrafts && !body.allowCommittedVariantWrite) {
      return Console.log("[BlazeRoutes] draft $draftId is no longer staged; refusing to write variant '$variant' (session $sessionId) into a committed trail folder")
    }
    val logs = deps.logsRepo.getLogsForSession(SessionId(sessionId))
    if (logs.isEmpty()) {
      return Console.log("[BlazeRoutes] no logs for session $sessionId; variant '$variant' not written")
    }
    val yaml = logs.generateRecordedYaml(createTrailblazeYaml())
    DraftStore.writeVariant(resolved.dir, variant, yaml)
  }.onFailure { Console.log("[BlazeRoutes] draft variant write failed: ${it.message}") }
}
