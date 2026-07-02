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
import xyz.block.trailblaze.api.TrailblazeImageFormat
import xyz.block.trailblaze.llm.TrailblazeLlmModels
import xyz.block.trailblaze.llm.TrailblazeLlmProvider
import xyz.block.trailblaze.mcp.AgentImplementation
import xyz.block.trailblaze.ui.models.TrailblazeServerState
import xyz.block.trailblaze.util.Console
import java.io.File

/**
 * Builds the full [SettingsDto] from a resolved app config — the single source shared by the REST
 * `GET`/`PUT /api/settings` routes (which respond it) and [buildSettingsResponse] (the RPC source),
 * so the response shape can't drift between the read and write paths.
 */
internal fun settingsDtoFromConfig(
  deps: TrailRunnerDeps,
  config: TrailblazeServerState.SavedTrailblazeAppConfig,
): SettingsDto = SettingsDto(
  themeMode = config.themeMode.name,
  alwaysOnTop = config.alwaysOnTop,
  captureLogcat = config.captureLogcat,
  captureIosLogs = config.captureIosLogs,
  captureNetworkTraffic = config.captureNetworkTraffic,
  captureAnalytics = config.captureAnalytics,
  showWebBrowser = config.showWebBrowser,
  serverPort = config.serverPort,
  serverHttpsPort = config.serverHttpsPort,
  showTrailsTab = config.showTrailsTab,
  showDevicesTab = config.showDevicesTab,
  showWaypointsTab = config.showWaypointsTab,
  preferHostAgent = config.preferHostAgent,
  trailsDirectory = config.trailsDirectory,
  logsDirectory = config.logsDirectory,
  appDataDirectory = config.appDataDirectory,
  llm = buildLlmSettingsDto(deps, config.llmProvider, config.llmModel, config.agentImplementation),
  selfHealEnabled = config.selfHealEnabled,
  requireSteps = config.requireSteps,
  saveAnnotatedScreenshots = config.saveAnnotatedScreenshots,
  maxLlmCalls = config.maxLlmCalls,
  screenshotImageFormat = config.screenshotImageFormat?.name,
  screenshotMaxLongerSide = config.screenshotMaxLongerSide,
  screenshotMaxShorterSide = config.screenshotMaxShorterSide,
  screenshotCompressionQuality = config.screenshotCompressionQuality,
)

/**
 * The current settings, or `null` when no settings repo is wired (e.g. the integration-test
 * harness) — the shared source for both the REST `GET /api/settings` route (which renders the null
 * case as `{"available":false}`) and the `GetSettingsRequest` RPC handler (RPC failure → the UI's
 * `dataOrNull` maps it back to `null`, where `useSettings` shows the same "unavailable" state).
 */
internal fun buildSettingsResponse(deps: TrailRunnerDeps): SettingsDto? {
  val settingsRepo = deps.settingsRepo ?: return null
  return settingsDtoFromConfig(deps, settingsRepo.serverStateFlow.value.appConfig)
}

/**
 * The available integrations — the shared source for both the REST `GET /api/integrations` route
 * and the `GetIntegrationsRequest` RPC handler.
 */
internal fun buildIntegrationsResponse(deps: TrailRunnerDeps): IntegrationsResponse =
  IntegrationsResponse(
    // Guard the seam call so a throwing downstream integrationsProvider can't crash
    // GET /api/integrations (and the RPC handler) — matches how analyticsProvider /
    // reviewTrailProvider / appTargetIdsProvider are guarded elsewhere.
    runCatching { deps.integrationsProvider?.invoke() }
      .onFailure { Console.log("[TrailRunnerEndpoint] integrations provider failed: ${it.message}") }
      .getOrNull()
      .orEmpty(),
  )

/**
 * Applies a partial settings patch and returns the updated [SettingsDto], or `null` when no settings
 * repo is wired — the shared source for both the REST `PUT /api/settings` route (null → 503) and the
 * `SettingsPatchRequest` RPC handler (null → failure → the UI shows "settings unavailable"). Each
 * field is applied only when present (non-null); the clearable-field sentinels match the old untyped
 * patch (blank directory clears it; non-positive cap clears it).
 */
internal fun buildSettingsPatchResponse(deps: TrailRunnerDeps, request: SettingsPatchRequest): SettingsDto? {
  val settingsRepo = deps.settingsRepo ?: return null
  settingsRepo.updateAppConfig { config ->
    var updated = config
    request.themeMode?.let { v ->
      runCatching { TrailblazeServerState.ThemeMode.valueOf(v) }
        .getOrNull()?.let { updated = updated.copy(themeMode = it) }
    }
    request.alwaysOnTop?.let { updated = updated.copy(alwaysOnTop = it) }
    request.captureLogcat?.let { updated = updated.copy(captureLogcat = it) }
    request.captureIosLogs?.let { updated = updated.copy(captureIosLogs = it) }
    request.captureNetworkTraffic?.let { updated = updated.copy(captureNetworkTraffic = it) }
    request.captureAnalytics?.let { updated = updated.copy(captureAnalytics = it) }
    request.showWebBrowser?.let { updated = updated.copy(showWebBrowser = it) }
    request.serverPort?.takeIf(::isValidTcpPort)?.let { port ->
      updated = updated.copy(serverPort = port, serverUrl = "http://localhost:$port")
    }
    request.serverHttpsPort?.takeIf(::isValidTcpPort)?.let { updated = updated.copy(serverHttpsPort = it) }
    request.showTrailsTab?.let { updated = updated.copy(showTrailsTab = it) }
    request.showDevicesTab?.let { updated = updated.copy(showDevicesTab = it) }
    request.showWaypointsTab?.let { updated = updated.copy(showWaypointsTab = it) }
    request.trailsDirectory?.let { v ->
      val trimmed = v.trim()
      if (trimmed.isEmpty()) updated = updated.copy(trailsDirectory = null)
      else if (File(trimmed).isDirectory) {
        updated = updated.copy(trailsDirectory = trimmed)
        // Switching the workspace relocates run logs + daemon state UNDER the chosen folder, so a
        // workspace is self-contained — its trails, logs, and state live together rather than the
        // logs/state staying behind in whatever folder the daemon launched from. Skipped when the
        // same patch explicitly sets these (the per-field "Change" buttons in Settings send only
        // that one field). Mirrors the default workspace layout (<root>/logs, <root>/.trailblaze) with
        // the picked folder as the root; the dirs are created on first write. App-data is
        // "restart to fully apply" (its existing semantics); logs apply on the next run.
        if (request.logsDirectory == null) {
          updated = updated.copy(logsDirectory = File(trimmed, "logs").path)
        }
        if (request.appDataDirectory == null) {
          updated = updated.copy(appDataDirectory = File(trimmed, ".trailblaze").path)
        }
      }
    }
    request.logsDirectory?.let { v ->
      val trimmed = v.trim()
      if (trimmed.isEmpty()) updated = updated.copy(logsDirectory = null)
      else if (File(trimmed).isDirectory) updated = updated.copy(logsDirectory = trimmed)
    }
    request.appDataDirectory?.let { v ->
      val trimmed = v.trim()
      if (trimmed.isEmpty()) updated = updated.copy(appDataDirectory = null)
      else if (File(trimmed).isDirectory) updated = updated.copy(appDataDirectory = trimmed)
    }
    request.preferHostAgent?.let { updated = updated.copy(preferHostAgent = it) }
    request.selfHealEnabled?.let { updated = updated.copy(selfHealEnabled = it) }
    request.requireSteps?.let { updated = updated.copy(requireSteps = it) }
    request.saveAnnotatedScreenshots?.let { updated = updated.copy(saveAnnotatedScreenshots = it) }
    request.maxLlmCalls?.let { updated = updated.copy(maxLlmCalls = it.takeIf { n -> n > 0 }) }
    request.llmProvider?.let { updated = updated.copy(llmProvider = it) }
    request.llmModel?.let { updated = updated.copy(llmModel = it) }
    request.agent?.let { a ->
      runCatching { AgentImplementation.valueOf(a) }.getOrNull()
        ?.let { updated = updated.copy(agentImplementation = it) }
    }
    request.screenshotImageFormat?.let { v ->
      if (v.isBlank()) updated = updated.copy(screenshotImageFormat = null)
      else runCatching { TrailblazeImageFormat.valueOf(v) }.getOrNull()?.let { updated = updated.copy(screenshotImageFormat = it) }
    }
    request.screenshotMaxLongerSide?.let { updated = updated.copy(screenshotMaxLongerSide = it.takeIf { n -> n > 0 }) }
    request.screenshotMaxShorterSide?.let { updated = updated.copy(screenshotMaxShorterSide = it.takeIf { n -> n > 0 }) }
    request.screenshotCompressionQuality?.let { updated = updated.copy(screenshotCompressionQuality = it.coerceIn(0f, 1f)) }
    updated
  }
  return settingsDtoFromConfig(deps, settingsRepo.serverStateFlow.value.appConfig)
}

private fun isValidTcpPort(port: Int): Boolean = port in 1..65535

/**
 * Runs an integration action, if wired — the shared source for both the REST
 * `POST /api/integrations/{id}/actions/{action}` route and the [IntegrationActionRequest] RPC
 * handler. Always returns an [OkResponse]; a missing handler or a thrown action rides in
 * `ok=false` + `error`.
 */
internal suspend fun buildIntegrationActionResponse(
  deps: TrailRunnerDeps,
  integrationId: String,
  actionId: String,
): OkResponse {
  val handler = deps.integrationActionHandler
    ?: return OkResponse(ok = false, error = "Integration actions are not available")
  val result = withContext(Dispatchers.IO) { runCatching { handler(integrationId, actionId) } }
  return if (result.isSuccess) {
    OkResponse(ok = true)
  } else {
    Console.log("[TrailRunnerEndpoint] POST /integrations/$integrationId/actions/$actionId error: ${result.exceptionOrNull()?.message}")
    OkResponse(ok = false, error = result.exceptionOrNull()?.message ?: "failed to run integration action")
  }
}

/**
 * Compares the app-target set the CURRENT workspace would resolve (fresh discovery via
 * [TrailRunnerDeps.appTargetIdsProvider]) against the set the daemon loaded at startup
 * (`TrailblazeDeviceManager.availableAppTargets`). The startup set is what the device manager and
 * target picker actually use, and it isn't rebuilt on a workspace switch — so a difference here is
 * exactly the "restart to pick up this workspace's app targets" case. Returns a no-drift result when
 * either side is unavailable (no provider / no device manager / discovery failure) so the UI degrades
 * to "no nudge" rather than a false alarm.
 */
internal fun buildWorkspaceTargetDriftResponse(deps: TrailRunnerDeps): WorkspaceTargetDriftDto {
  val provider = deps.appTargetIdsProvider
  val loaded = deps.deviceManager?.availableAppTargets?.map { it.id }?.toSet()
  if (provider == null || loaded == null) {
    return WorkspaceTargetDriftDto(restartNeeded = false)
  }
  val fresh = runCatching { provider() }.getOrElse {
    Console.log("[TrailRunnerEndpoint] workspace target-drift discovery failed: ${it.message}")
    return WorkspaceTargetDriftDto(restartNeeded = false)
  }
  val added = (fresh - loaded).sorted()
  val removed = (loaded - fresh).sorted()
  return WorkspaceTargetDriftDto(
    restartNeeded = added.isNotEmpty() || removed.isNotEmpty(),
    added = added,
    removed = removed,
  )
}

internal fun Route.settingsRoutes(deps: TrailRunnerDeps) {
  get("$PATH_BASE/api/workspace/target-drift") {
    // Discovery touches disk (and may spawn the scripted-tool analyzer), so run it off the request
    // thread. This is hit once per workspace switch (a deliberate user action), not on a poll.
    val dto = withContext(Dispatchers.IO) { buildWorkspaceTargetDriftResponse(deps) }
    call.respondText(
      text = JSON.encodeToString(WorkspaceTargetDriftDto.serializer(), dto),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/settings") {
    val dto = buildSettingsResponse(deps)
    if (dto == null) {
      call.respondText(
        text = """{"available":false}""",
        contentType = ContentType.Application.Json,
      )
      return@get
    }
    call.respondText(
      text = JSON.encodeToString(SettingsDto.serializer(), dto),
      contentType = ContentType.Application.Json,
    )
  }

  put("$PATH_BASE/api/settings") {
    val body = runCatching { call.receive<SettingsPatchRequest>() }.getOrElse { e ->
      Console.log("[TrailRunnerEndpoint] PUT /settings bad body: ${e.message}")
      call.respond(HttpStatusCode.BadRequest, mapOf("error" to "invalid JSON body"))
      return@put
    }
    val dto = buildSettingsPatchResponse(deps, body)
    if (dto == null) {
      call.respond(HttpStatusCode.ServiceUnavailable, mapOf("error" to "settings not available"))
      return@put
    }
    call.respondText(
      text = JSON.encodeToString(SettingsDto.serializer(), dto),
      contentType = ContentType.Application.Json,
    )
  }

  get("$PATH_BASE/api/integrations") {
    call.respondText(
      text = JSON.encodeToString(IntegrationsResponse.serializer(), buildIntegrationsResponse(deps)),
      contentType = ContentType.Application.Json,
    )
  }

  post("$PATH_BASE/api/integrations/{id}/actions/{action}") {
    val id = call.parameters["id"]?.trim().orEmpty()
    val action = call.parameters["action"]?.trim().orEmpty()
    // Reject blank segments up front with a clear 400 — otherwise they'd reach the extension
    // handler as empty strings and surface as a generic handler failure.
    if (id.isEmpty() || action.isEmpty()) {
      call.respond(HttpStatusCode.BadRequest, OkResponse(ok = false))
      return@post
    }
    call.respondText(
      text = JSON.encodeToString(OkResponse.serializer(), buildIntegrationActionResponse(deps, id, action)),
      contentType = ContentType.Application.Json,
    )
  }
}

/**
 * Builds the LLM section of the settings response. The available providers and models
 * come from the desktop app's resolved model lists (built-in providers plus anything
 * contributed via the Trailblaze YAML config). Falls back to the static built-in catalog
 * when no provider is wired (e.g. the integration test harness).
 */
private fun buildLlmSettingsDto(
  deps: TrailRunnerDeps,
  selectedProvider: String,
  selectedModel: String,
  selectedAgent: AgentImplementation,
): LlmSettingsDto {
  val modelLists = deps.llmModelListsProvider?.invoke()
  val providers = modelLists?.map { it.provider }?.distinctBy { it.id }
    ?: TrailblazeLlmProvider.ALL_PROVIDERS
  val models = modelLists?.flatMap { list -> list.entries.map { it.modelId to it.trailblazeLlmProvider.id } }
    ?: TrailblazeLlmModels.ALL_MODELS.map { it.modelId to it.trailblazeLlmProvider.id }
  return LlmSettingsDto(
    provider = selectedProvider,
    model = selectedModel,
    availableProviders = providers.map { LlmProviderOptionDto(it.id, it.display) },
    availableModels = models.distinct().map { (modelId, providerId) -> LlmModelOptionDto(modelId, providerId) },
    agent = selectedAgent.name,
    availableAgents = AgentImplementation.entries.map { AgentOptionDto(it.name, agentDisplayName(it)) },
  )
}

/** Human-readable label for an agent implementation, shown in the run-controls agent picker. */
private fun agentDisplayName(agent: AgentImplementation): String = when (agent) {
  AgentImplementation.TRAILBLAZE_RUNNER -> "Trailblaze Runner"
  AgentImplementation.MULTI_AGENT_V3 -> "Multi-Agent v3"
  AgentImplementation.KOOG_STRATEGY_GRAPH -> "Koog Strategy Graph"
}
