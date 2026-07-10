package xyz.block.trailblaze.trailrunner

import io.ktor.server.routing.Routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import xyz.block.trailblaze.mcp.RpcHandler
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcResult
import xyz.block.trailblaze.rpc.registerRpcHandler

/**
 * Trail Runner daemon RPC endpoints — the typed `/rpc/<Name>` counterparts of the read-only Trail
 * Runner endpoints. Each request pairs with a Trail Runner DTO response (in TrailRunnerDtos.kt); the
 * handlers call the SAME backing logic as the REST routes (`fetchSessionSummaries`,
 * `ToolCatalogBuilder.build`, `TrailmapCatalogBuilder.build`, and the `build*Response` /
 * `validateTrailYaml` helpers in TrailRoutes.kt), so the REST and RPC paths can't drift. The
 * TypeScript client `createTrailRunnerRpcClient` is generated from these by
 * [xyz.block.trailblaze.trailrunner.codegen.TrailRunnerDtoTsBindings].
 */
@Serializable
object GetSessionsRequest : RpcRequest<SessionsResponse>

@Serializable
object GetToolsRequest : RpcRequest<ToolCatalogResponse>

@Serializable
object GetTrailmapsRequest : RpcRequest<TrailmapsResponse>

@Serializable
object GetTrailsRequest : RpcRequest<TrailIndexResponse>

@Serializable
object GetTrailRootsRequest : RpcRequest<TrailRootsResponse>

@Serializable
object GetEditedTrailsRequest : RpcRequest<EditedTrailsResponse>

@Serializable
data class GetTrailDetailRequest(val id: String) : RpcRequest<TrailDetailResponse>

@Serializable
data class ValidateTrailRequest(val yaml: String) : RpcRequest<ValidateTrailResponse>

@Serializable
object GetFavoritesRequest : RpcRequest<FavoritesResponse>

@Serializable
object GetIntegrationsRequest : RpcRequest<IntegrationsResponse>

@Serializable
object GetSettingsRequest : RpcRequest<SettingsDto>

@Serializable
object GetToolUsageCountsRequest : RpcRequest<ToolUsageCountsResponse>

@Serializable
data class GetToolUsagesRequest(val toolId: String) : RpcRequest<TrailIndexResponse>

@Serializable
data class GetDeviceAppsRequest(val platform: String, val id: String) : RpcRequest<DeviceAppsResponse>

@Serializable
data class GetInstalledAppsRequest(
  val platform: String,
  val id: String,
  val includeSystemApps: Boolean = false,
) : RpcRequest<InstalledAppsResponse>

@Serializable
data class GetRunToolsRequest(
  val target: String,
  val driver: String? = null,
  val platform: String? = null,
) : RpcRequest<RunToolsResponse>

@Serializable
data class GetSessionAnalyticsRequest(val sessionId: String) : RpcRequest<AnalyticsResponse>

// ─── Mutation / command requests ───────────────────────────────────────────────
// The typed `/rpc/<Name>` counterparts of the Trail Runner write endpoints. Each handler calls the
// SAME backing `build*Response` helper as the matching REST route. Domain failures ride in the
// response DTO (e.g. `SaveTrailResponse.success=false`), so they survive the UI's `dataOrNull`; the
// handlers return `RpcResult.Success` for those. `CreateTrailRequest` / `CreateTrailDirRequest` /
// `ToolSourceSaveRequest` double as their own RPC requests (see TrailRunnerDtos.kt); only the
// path-param PUT needs a dedicated request since RPC carries no path segment.

@Serializable
data class UpdateTrailRequest(val id: String, val yaml: String) : RpcRequest<SaveTrailResponse>

// `AddTrailRootRequest` doubles as the POST /api/trails/roots RPC request (see TrailRunnerDtos.kt);
// DELETE needs its own type since both share the same `{path}` HTTP body.
@Serializable
data class RemoveTrailRootRequest(val path: String) : RpcRequest<TrailRootsResponse>

@Serializable
data class SetFavoriteRequest(val id: String, val favorite: Boolean) : RpcRequest<FavoritesResponse>

// `RunRequest` (POST /api/run) and `ToolRunRequest` (POST /api/tool/run) double as their own RPC
// requests (see TrailRunnerDtos.kt). The daemon rebuild takes no payload, so it's an object.
@Serializable
object RebuildDaemonRequest : RpcRequest<RebuildDaemonResponse> {
  // The gradle compile can run up to 10 minutes; give the RPC a longer budget than that so a Kotlin
  // client doesn't abandon the call before the daemon finishes compiling + responds.
  override val requestTimeoutMs: Long?
    get() = 11L * 60 * 1000
}

// ─── Side-effect / reveal commands ───────────────────────────────────────────────
// `ToolRevealRequest`, `TrailOpenRequest`, and `NewComponentRequest` double as their own RPC
// requests (see TrailRunnerDtos.kt). The session-scoped and reveal commands below carry their id
// (RPC has no path segment) or take no payload.

@Serializable
data class DeleteSessionRequest(val id: String) : RpcRequest<DeleteSessionResponse>

@Serializable
data class CancelSessionRequest(val id: String) : RpcRequest<CancelSessionResponse>

@Serializable
data class RevealSessionRequest(val id: String) : RpcRequest<OkResponse>

// Reveal-a-trail needs its own type since TrailOpenRequest is already the open-in-editor request.
@Serializable
data class RevealTrailRequest(val id: String) : RpcRequest<OkResponse>

@Serializable
object RevealTrailsRootRequest : RpcRequest<OkResponse>

@Serializable
data class IntegrationActionRequest(val id: String, val action: String) : RpcRequest<OkResponse>

// `OpenSessionFileRequest` doubles as its own RPC request (see TrailRunnerDtos.kt). The two reads
// below carry their id / lookup keys since RPC has no path segment or query string.
@Serializable
data class GetSessionFilesRequest(val sessionId: String) : RpcRequest<SessionFilesResponse>

@Serializable
data class GetToolSourceRequest(
  val className: String? = null,
  val path: String? = null,
) : RpcRequest<ToolSourceResponse>

internal class GetSessionsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetSessionsRequest, SessionsResponse> {
  override suspend fun handle(request: GetSessionsRequest): RpcResult<SessionsResponse> =
    RpcResult.Success(SessionsResponse(fetchSessionSummaries(deps)))
}

internal class GetToolsHandler : RpcHandler<GetToolsRequest, ToolCatalogResponse> {
  // The builder does recursive filesystem discovery (blocking I/O), so run it off the request
  // coroutine — matching the REST route (ToolRoutes.kt) it mirrors.
  override suspend fun handle(request: GetToolsRequest): RpcResult<ToolCatalogResponse> =
    RpcResult.Success(ToolCatalogResponse(withContext(Dispatchers.IO) { ToolCatalogBuilder.build() }))
}

internal class GetTrailmapsHandler : RpcHandler<GetTrailmapsRequest, TrailmapsResponse> {
  // Blocking filesystem discovery — dispatch off the request coroutine, matching TrailmapRoutes.kt.
  override suspend fun handle(request: GetTrailmapsRequest): RpcResult<TrailmapsResponse> =
    RpcResult.Success(TrailmapsResponse(withContext(Dispatchers.IO) { TrailmapCatalogBuilder.build() }))
}

// The build*Response helpers below already dispatch their blocking filesystem/git work onto
// Dispatchers.IO (see TrailRoutes.kt), so these handlers just unwrap the result.
internal class GetTrailsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetTrailsRequest, TrailIndexResponse> {
  override suspend fun handle(request: GetTrailsRequest): RpcResult<TrailIndexResponse> =
    RpcResult.Success(buildTrailIndexResponse(deps))
}

internal class GetTrailRootsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetTrailRootsRequest, TrailRootsResponse> {
  override suspend fun handle(request: GetTrailRootsRequest): RpcResult<TrailRootsResponse> =
    RpcResult.Success(buildTrailRootsResponse(deps))
}

internal class GetEditedTrailsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetEditedTrailsRequest, EditedTrailsResponse> {
  override suspend fun handle(request: GetEditedTrailsRequest): RpcResult<EditedTrailsResponse> =
    RpcResult.Success(buildEditedTrailsResponse(deps))
}

internal class GetTrailDetailHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetTrailDetailRequest, TrailDetailResponse> {
  // The id carries slash-separated path segments (same shape the REST `{id...}` tailcard captures),
  // so split it the way `trail/open` does. A null result is the RPC analog of the route's 404 —
  // the UI's `dataOrNull` maps the failure back to `null`, matching the old `safeJson` contract.
  override suspend fun handle(request: GetTrailDetailRequest): RpcResult<TrailDetailResponse> =
    buildTrailDetailResponse(deps, request.id.split("/"))?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(
        // A lookup miss, not a server fault — HTTP_ERROR keeps it distinguishable in logs/telemetry
        // from real faults (the convention in ConnectToDeviceHandler / SetCurrentTargetAppHandler).
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "No trail found for id '${request.id}'",
      )
}

internal class ValidateTrailHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<ValidateTrailRequest, ValidateTrailResponse> {
  override suspend fun handle(request: ValidateTrailRequest): RpcResult<ValidateTrailResponse> =
    RpcResult.Success(validateTrailYaml(deps, request.yaml))
}

// Read/query handlers — each unwraps the matching build*Response helper (in *Routes.kt), which
// already dispatches its blocking filesystem / device work onto Dispatchers.IO.
internal class GetFavoritesHandler : RpcHandler<GetFavoritesRequest, FavoritesResponse> {
  override suspend fun handle(request: GetFavoritesRequest): RpcResult<FavoritesResponse> =
    RpcResult.Success(buildFavoritesResponse())
}

internal class GetIntegrationsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetIntegrationsRequest, IntegrationsResponse> {
  override suspend fun handle(request: GetIntegrationsRequest): RpcResult<IntegrationsResponse> =
    RpcResult.Success(buildIntegrationsResponse(deps))
}

internal class GetSettingsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetSettingsRequest, SettingsDto> {
  // No settings repo wired (e.g. integration-test harness) → failure, which the UI's dataOrNull maps
  // back to null, where useSettings renders the same "unavailable" state the REST `{available:false}`
  // branch produced.
  override suspend fun handle(request: GetSettingsRequest): RpcResult<SettingsDto> =
    buildSettingsResponse(deps)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(
        // Expected "no settings repo wired" state the UI maps to its unavailable view — HTTP_ERROR,
        // not UNKNOWN_ERROR, so it doesn't read as a real server fault in logs/telemetry.
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "settings not available",
      )
}

internal class GetToolUsageCountsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetToolUsageCountsRequest, ToolUsageCountsResponse> {
  override suspend fun handle(request: GetToolUsageCountsRequest): RpcResult<ToolUsageCountsResponse> =
    RpcResult.Success(buildToolUsageCountsResponse(deps))
}

internal class GetToolUsagesHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetToolUsagesRequest, TrailIndexResponse> {
  override suspend fun handle(request: GetToolUsagesRequest): RpcResult<TrailIndexResponse> =
    RpcResult.Success(buildToolUsagesResponse(deps, request.toolId))
}

internal class GetDeviceAppsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetDeviceAppsRequest, DeviceAppsResponse> {
  override suspend fun handle(request: GetDeviceAppsRequest): RpcResult<DeviceAppsResponse> =
    RpcResult.Success(buildDeviceAppsResponse(deps, request.platform, request.id))
}

internal class GetInstalledAppsHandler : RpcHandler<GetInstalledAppsRequest, InstalledAppsResponse> {
  override suspend fun handle(request: GetInstalledAppsRequest): RpcResult<InstalledAppsResponse> =
    RpcResult.Success(buildInstalledAppsResponse(request.platform, request.id, request.includeSystemApps))
}

internal class GetRunToolsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetRunToolsRequest, RunToolsResponse> {
  override suspend fun handle(request: GetRunToolsRequest): RpcResult<RunToolsResponse> =
    RpcResult.Success(
      buildRunToolsResponse(deps, request.target, request.driver.orEmpty(), request.platform.orEmpty()),
    )
}

internal class GetSessionAnalyticsHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetSessionAnalyticsRequest, AnalyticsResponse> {
  // Unresolved session id → failure (the RPC analog of the route's 404), mapped back to null by the
  // UI's dataOrNull; useSessionAnalytics then shows its empty "available=false" state.
  override suspend fun handle(request: GetSessionAnalyticsRequest): RpcResult<AnalyticsResponse> =
    buildSessionAnalyticsResponse(deps, request.sessionId)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(
        // Lookup miss (unresolved session id), not a server fault — HTTP_ERROR per the
        // ConnectToDeviceHandler / SetCurrentTargetAppHandler convention.
        errorType = RpcResult.ErrorType.HTTP_ERROR,
        message = "No session found for id '${request.sessionId}'",
      )
}

// ─── Mutation / command handlers ────────────────────────────────────────────────
// Each handler returns RpcResult.Success with the outcome's body — the REST status code is dropped
// because a domain failure already rides in SaveTrailResponse.success/error (and survives the UI's
// dataOrNull), exactly the in-band contract the .jsx callers expect.
//
// When a failure has NO response DTO to ride in (a validation reject or an unresolved id/target),
// it surfaces as RpcResult.Failure(HTTP_ERROR, message) — matching the read handlers above: any
// failure the REST route expresses as a non-2xx is an HTTP_ERROR, the benign RPC analog of that
// status. UNKNOWN_ERROR stays reserved for the genuine-fault path (registerRpcHandler's catch), so
// logs/telemetry can tell an expected reject from an actual server fault.
//
// The shared `build*Response` helpers come in four deliberate shapes; the shape is chosen by what
// the REST side needs to express, and every handler here adapts it to an RpcResult:
//   1. success-in-payload (SaveTrailResponse / RunResponse / ToolRunResponse / OkResponse): the DTO
//      carries its own success flag, so REST + RPC both just return it. Used where the failure is a
//      domain outcome the UI renders in-band (already-exists, dispatch error).
//   2. status + body (SaveTrailOutcome, NewComponentOutcome): a (HttpStatusCode, body) pair so the
//      REST route keeps distinct codes (400 vs 409 vs 500) while RPC takes only `.body`.
//   3. sealed multi-arm (TrailRootsMutationResult, RunDispatchResult, CancelSessionOutcome): when
//      REST needs to tell two failures apart by status (e.g. 503 no-deviceManager vs 404 not-found)
//      and RPC maps each arm to a Failure.
//   4. nullable (delete / reveal / open / tool-source): a single lookup-miss failure → null → REST
//      404, RPC Failure. The simplest shape; used when there's only one way to fail.
internal class CreateTrailHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<CreateTrailRequest, SaveTrailResponse> {
  override suspend fun handle(request: CreateTrailRequest): RpcResult<SaveTrailResponse> =
    RpcResult.Success(buildCreateTrailResponse(deps, request).body)
}

internal class CreateTrailDirHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<CreateTrailDirRequest, SaveTrailResponse> {
  override suspend fun handle(request: CreateTrailDirRequest): RpcResult<SaveTrailResponse> =
    RpcResult.Success(buildCreateTrailDirResponse(deps, request).body)
}

internal class UpdateTrailHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<UpdateTrailRequest, SaveTrailResponse> {
  // The id carries slash-separated path segments (same shape the REST `{id...}` tailcard captures).
  override suspend fun handle(request: UpdateTrailRequest): RpcResult<SaveTrailResponse> =
    RpcResult.Success(buildUpdateTrailResponse(deps, request.id.split("/"), request.yaml).body)
}

internal class ToolSourceSaveHandler : RpcHandler<ToolSourceSaveRequest, SaveTrailResponse> {
  override suspend fun handle(request: ToolSourceSaveRequest): RpcResult<SaveTrailResponse> =
    RpcResult.Success(buildToolSourceSaveResponse(request).body)
}

internal class AddTrailRootHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<AddTrailRootRequest, TrailRootsResponse> {
  // A validation failure (blank / not-a-directory) has no response DTO to ride in, so it surfaces as
  // a Failure whose message daemon.ts's `dataOrError` maps back to the UI (e.g. "not a directory").
  override suspend fun handle(request: AddTrailRootRequest): RpcResult<TrailRootsResponse> =
    when (val result = buildAddTrailRootResult(deps, request)) {
      is TrailRootsMutationResult.Ok -> RpcResult.Success(result.response)
      is TrailRootsMutationResult.Invalid ->
        RpcResult.Failure(errorType = RpcResult.ErrorType.HTTP_ERROR, message = result.message)
    }
}

internal class RemoveTrailRootHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<RemoveTrailRootRequest, TrailRootsResponse> {
  override suspend fun handle(request: RemoveTrailRootRequest): RpcResult<TrailRootsResponse> =
    when (val result = buildRemoveTrailRootResult(deps, request.path)) {
      is TrailRootsMutationResult.Ok -> RpcResult.Success(result.response)
      is TrailRootsMutationResult.Invalid ->
        RpcResult.Failure(errorType = RpcResult.ErrorType.HTTP_ERROR, message = result.message)
    }
}

internal class SetFavoriteHandler : RpcHandler<SetFavoriteRequest, FavoritesResponse> {
  override suspend fun handle(request: SetFavoriteRequest): RpcResult<FavoritesResponse> =
    RpcResult.Success(buildSetFavoriteResponse(request.id, request.favorite))
}

internal class RunHandler(private val deps: TrailRunnerDeps) : RpcHandler<RunRequest, RunResponse> {
  // Invalid (no deviceManager / blank yaml) → Failure (its message reaches the UI via daemon.ts);
  // a dispatched run → Success, where RunResponse.success may still be false for a dispatch error.
  override suspend fun handle(request: RunRequest): RpcResult<RunResponse> =
    when (val result = buildRunDispatchResult(deps, request)) {
      is RunDispatchResult.Ok -> RpcResult.Success(result.response)
      is RunDispatchResult.Invalid ->
        RpcResult.Failure(errorType = RpcResult.ErrorType.HTTP_ERROR, message = result.message)
    }
}

internal class ToolRunHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<ToolRunRequest, ToolRunResponse> {
  override suspend fun handle(request: ToolRunRequest): RpcResult<ToolRunResponse> =
    RpcResult.Success(buildToolRunResponse(deps, request))
}

internal class RebuildDaemonHandler(private val deps: TrailRunnerDeps) : RpcHandler<RebuildDaemonRequest, RebuildDaemonResponse> {
  override suspend fun handle(request: RebuildDaemonRequest): RpcResult<RebuildDaemonResponse> =
    RpcResult.Success(buildRebuildDaemonResponse(deps))
}

// ─── Side-effect / reveal handlers ──────────────────────────────────────────────
// An unresolved target (session/trail/tool not found) → Failure, the RPC analog of the route's 404,
// which the UI's dataOrNull maps back to null (rendered as the same "couldn't do it" state).
internal class DeleteSessionHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<DeleteSessionRequest, DeleteSessionResponse> {
  override suspend fun handle(request: DeleteSessionRequest): RpcResult<DeleteSessionResponse> =
    buildDeleteSessionResponse(deps, request.id)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no session found for id '${request.id}'")
}

internal class CancelSessionHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<CancelSessionRequest, CancelSessionResponse> {
  override suspend fun handle(request: CancelSessionRequest): RpcResult<CancelSessionResponse> =
    when (val outcome = buildCancelSessionOutcome(deps, request.id)) {
      is CancelSessionOutcome.Ok -> RpcResult.Success(outcome.response)
      is CancelSessionOutcome.NoDeviceManager ->
        RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "deviceManager not available")
      is CancelSessionOutcome.NotFound ->
        RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no session found for id '${request.id}'")
    }
}

internal class RevealSessionHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<RevealSessionRequest, OkResponse> {
  override suspend fun handle(request: RevealSessionRequest): RpcResult<OkResponse> =
    buildRevealSessionResponse(deps, request.id)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no session found for id '${request.id}'")
}

internal class RevealToolSourceHandler : RpcHandler<ToolRevealRequest, OkResponse> {
  override suspend fun handle(request: ToolRevealRequest): RpcResult<OkResponse> =
    buildRevealToolSourceResponse(request)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no tool source file found for that class or path")
}

internal class OpenTrailHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<TrailOpenRequest, OkResponse> {
  override suspend fun handle(request: TrailOpenRequest): RpcResult<OkResponse> =
    buildOpenTrailResponse(deps, request.id)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no trail found for id '${request.id}'")
}

internal class RevealTrailHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<RevealTrailRequest, OkResponse> {
  override suspend fun handle(request: RevealTrailRequest): RpcResult<OkResponse> =
    buildRevealTrailResponse(deps, request.id)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no trail found for id '${request.id}'")
}

internal class RevealTrailsRootHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<RevealTrailsRootRequest, OkResponse> {
  override suspend fun handle(request: RevealTrailsRootRequest): RpcResult<OkResponse> =
    RpcResult.Success(buildRevealTrailsRootResponse(deps))
}

internal class IntegrationActionHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<IntegrationActionRequest, OkResponse> {
  override suspend fun handle(request: IntegrationActionRequest): RpcResult<OkResponse> =
    RpcResult.Success(buildIntegrationActionResponse(deps, request.id, request.action))
}

internal class NewComponentHandler : RpcHandler<NewComponentRequest, NewComponentResponse> {
  override suspend fun handle(request: NewComponentRequest): RpcResult<NewComponentResponse> =
    RpcResult.Success(buildNewComponentResponse(request).body)
}

internal class SaveTargetConfigHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<SaveTargetConfigRequest, SaveTargetConfigResponse> {
  // ok=false rides in the response body (mirrors NewComponentHandler) so the UI's error message
  // comes from the response itself, not a generic RPC failure string.
  override suspend fun handle(request: SaveTargetConfigRequest): RpcResult<SaveTargetConfigResponse> =
    RpcResult.Success(
      buildSaveTargetConfigResponse(
        request,
        // A non-null registerNewTarget result means the id resolved into the live target set
        // (freshly appended, or already present) — selectable now, no restart needed.
        registerLiveTarget = deps.deviceManager?.let { dm -> { id -> dm.registerNewTarget(id) != null } },
      ),
    )
}

internal class SettingsPatchHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<SettingsPatchRequest, SettingsDto> {
  // No settings repo wired → failure, which the UI's dataOrError surfaces as "settings not
  // available" (the RPC analog of the REST route's 503).
  override suspend fun handle(request: SettingsPatchRequest): RpcResult<SettingsDto> =
    buildSettingsPatchResponse(deps, request)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "settings not available")
}

internal class GetSessionFilesHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<GetSessionFilesRequest, SessionFilesResponse> {
  // Unresolved session id → failure (the RPC analog of the route's 404), mapped to null by the UI.
  override suspend fun handle(request: GetSessionFilesRequest): RpcResult<SessionFilesResponse> =
    buildSessionFilesResponse(deps, request.sessionId)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no session found for id '${request.sessionId}'")
}

internal class OpenSessionFileHandler(private val deps: TrailRunnerDeps) :
  RpcHandler<OpenSessionFileRequest, OkResponse> {
  // Unresolved id / unsafe name / missing file → failure (the route's 404).
  override suspend fun handle(request: OpenSessionFileRequest): RpcResult<OkResponse> =
    buildOpenSessionFileResponse(deps, request.id, request.name)?.let { RpcResult.Success(it) }
      ?: RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "no openable file '${request.name}' in session '${request.id}'")
}

internal class GetToolSourceHandler : RpcHandler<GetToolSourceRequest, ToolSourceResponse> {
  // Neither key given is a caller misuse — fail, mirroring the REST route's 400, so a caller bug
  // isn't masked as an empty source. A key that resolves to nothing is a normal source = null read
  // (the editor / Trailmaps viewer render the empty state), not a failure.
  override suspend fun handle(request: GetToolSourceRequest): RpcResult<ToolSourceResponse> {
    if (request.className.isNullOrBlank() && request.path.isNullOrBlank()) {
      return RpcResult.Failure(RpcResult.ErrorType.HTTP_ERROR, "class or path is required")
    }
    return RpcResult.Success(ToolSourceResponse(resolveToolSource(request.className, request.path)))
  }
}

/** Registers the Trail Runner RPC endpoints. Called from [TrailRunnerEndpoint.register]. */
internal fun Routing.trailRunnerRpcRoutes(deps: TrailRunnerDeps) {
  registerRpcHandler<SessionsResponse, GetSessionsRequest>(GetSessionsHandler(deps))
  registerRpcHandler<ToolCatalogResponse, GetToolsRequest>(GetToolsHandler())
  registerRpcHandler<TrailmapsResponse, GetTrailmapsRequest>(GetTrailmapsHandler())
  registerRpcHandler<TrailIndexResponse, GetTrailsRequest>(GetTrailsHandler(deps))
  registerRpcHandler<TrailRootsResponse, GetTrailRootsRequest>(GetTrailRootsHandler(deps))
  registerRpcHandler<EditedTrailsResponse, GetEditedTrailsRequest>(GetEditedTrailsHandler(deps))
  registerRpcHandler<TrailDetailResponse, GetTrailDetailRequest>(GetTrailDetailHandler(deps))
  registerRpcHandler<ValidateTrailResponse, ValidateTrailRequest>(ValidateTrailHandler(deps))
  registerRpcHandler<FavoritesResponse, GetFavoritesRequest>(GetFavoritesHandler())
  registerRpcHandler<IntegrationsResponse, GetIntegrationsRequest>(GetIntegrationsHandler(deps))
  registerRpcHandler<SettingsDto, GetSettingsRequest>(GetSettingsHandler(deps))
  registerRpcHandler<ToolUsageCountsResponse, GetToolUsageCountsRequest>(GetToolUsageCountsHandler(deps))
  registerRpcHandler<TrailIndexResponse, GetToolUsagesRequest>(GetToolUsagesHandler(deps))
  registerRpcHandler<DeviceAppsResponse, GetDeviceAppsRequest>(GetDeviceAppsHandler(deps))
  registerRpcHandler<InstalledAppsResponse, GetInstalledAppsRequest>(GetInstalledAppsHandler())
  registerRpcHandler<RunToolsResponse, GetRunToolsRequest>(GetRunToolsHandler(deps))
  registerRpcHandler<AnalyticsResponse, GetSessionAnalyticsRequest>(GetSessionAnalyticsHandler(deps))
  // Mutation / command endpoints.
  registerRpcHandler<SaveTrailResponse, CreateTrailRequest>(CreateTrailHandler(deps))
  registerRpcHandler<SaveTrailResponse, CreateTrailDirRequest>(CreateTrailDirHandler(deps))
  registerRpcHandler<SaveTrailResponse, UpdateTrailRequest>(UpdateTrailHandler(deps))
  registerRpcHandler<SaveTrailResponse, ToolSourceSaveRequest>(ToolSourceSaveHandler())
  registerRpcHandler<TrailRootsResponse, AddTrailRootRequest>(AddTrailRootHandler(deps))
  registerRpcHandler<TrailRootsResponse, RemoveTrailRootRequest>(RemoveTrailRootHandler(deps))
  registerRpcHandler<FavoritesResponse, SetFavoriteRequest>(SetFavoriteHandler())
  registerRpcHandler<RunResponse, RunRequest>(RunHandler(deps))
  registerRpcHandler<ToolRunResponse, ToolRunRequest>(ToolRunHandler(deps))
  registerRpcHandler<RebuildDaemonResponse, RebuildDaemonRequest>(RebuildDaemonHandler(deps))
  // Side-effect / reveal commands.
  registerRpcHandler<DeleteSessionResponse, DeleteSessionRequest>(DeleteSessionHandler(deps))
  registerRpcHandler<CancelSessionResponse, CancelSessionRequest>(CancelSessionHandler(deps))
  registerRpcHandler<OkResponse, RevealSessionRequest>(RevealSessionHandler(deps))
  registerRpcHandler<OkResponse, ToolRevealRequest>(RevealToolSourceHandler())
  registerRpcHandler<OkResponse, TrailOpenRequest>(OpenTrailHandler(deps))
  registerRpcHandler<OkResponse, RevealTrailRequest>(RevealTrailHandler(deps))
  registerRpcHandler<OkResponse, RevealTrailsRootRequest>(RevealTrailsRootHandler(deps))
  registerRpcHandler<OkResponse, IntegrationActionRequest>(IntegrationActionHandler(deps))
  registerRpcHandler<NewComponentResponse, NewComponentRequest>(NewComponentHandler())
  registerRpcHandler<SaveTargetConfigResponse, SaveTargetConfigRequest>(SaveTargetConfigHandler(deps))
  registerRpcHandler<SettingsDto, SettingsPatchRequest>(SettingsPatchHandler(deps))
  registerRpcHandler<OkResponse, OpenSessionFileRequest>(OpenSessionFileHandler(deps))
  registerRpcHandler<SessionFilesResponse, GetSessionFilesRequest>(GetSessionFilesHandler(deps))
  registerRpcHandler<ToolSourceResponse, GetToolSourceRequest>(GetToolSourceHandler())
}
