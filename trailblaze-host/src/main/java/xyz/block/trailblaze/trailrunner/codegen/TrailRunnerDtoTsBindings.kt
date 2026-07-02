package xyz.block.trailblaze.trailrunner.codegen

import kotlinx.serialization.descriptors.SerialDescriptor
import xyz.block.trailblaze.codegen.RpcClientTsCodegen
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest
import xyz.block.trailblaze.trailrunner.AddTrailRootRequest
import xyz.block.trailblaze.trailrunner.AnalyticsResponse
import xyz.block.trailblaze.trailrunner.CancelSessionRequest
import xyz.block.trailblaze.trailrunner.CancelSessionResponse
import xyz.block.trailblaze.trailrunner.CreateTrailDirRequest
import xyz.block.trailblaze.trailrunner.CreateTrailRequest
import xyz.block.trailblaze.trailrunner.DeleteSessionRequest
import xyz.block.trailblaze.trailrunner.DeleteSessionResponse
import xyz.block.trailblaze.trailrunner.DeviceAppsResponse
import xyz.block.trailblaze.trailrunner.EditedTrailsResponse
import xyz.block.trailblaze.trailrunner.FavoriteRequest
import xyz.block.trailblaze.trailrunner.FavoritesResponse
import xyz.block.trailblaze.trailrunner.GetDeviceAppsRequest
import xyz.block.trailblaze.trailrunner.GetEditedTrailsRequest
import xyz.block.trailblaze.trailrunner.GetFavoritesRequest
import xyz.block.trailblaze.trailrunner.GetIntegrationsRequest
import xyz.block.trailblaze.trailrunner.GetRunToolsRequest
import xyz.block.trailblaze.trailrunner.GetSessionAnalyticsRequest
import xyz.block.trailblaze.trailrunner.GetSessionFilesRequest
import xyz.block.trailblaze.trailrunner.GetSessionsRequest
import xyz.block.trailblaze.trailrunner.GetSettingsRequest
import xyz.block.trailblaze.trailrunner.GetToolSourceRequest
import xyz.block.trailblaze.trailrunner.GetToolUsageCountsRequest
import xyz.block.trailblaze.trailrunner.GetToolUsagesRequest
import xyz.block.trailblaze.trailrunner.GetToolsRequest
import xyz.block.trailblaze.trailrunner.GetTrailDetailRequest
import xyz.block.trailblaze.trailrunner.GetTrailRootsRequest
import xyz.block.trailblaze.trailrunner.GetTrailmapsRequest
import xyz.block.trailblaze.trailrunner.GetTrailsRequest
import xyz.block.trailblaze.trailrunner.IntegrationActionRequest
import xyz.block.trailblaze.trailrunner.IntegrationsResponse
import xyz.block.trailblaze.trailrunner.LlmSettingsDto
import xyz.block.trailblaze.trailrunner.NewComponentRequest
import xyz.block.trailblaze.trailrunner.NewComponentResponse
import xyz.block.trailblaze.trailrunner.OkResponse
import xyz.block.trailblaze.trailrunner.OpenSessionFileRequest
import xyz.block.trailblaze.trailrunner.RebuildDaemonRequest
import xyz.block.trailblaze.trailrunner.RebuildDaemonResponse
import xyz.block.trailblaze.trailrunner.RemoveTrailRootRequest
import xyz.block.trailblaze.trailrunner.RevealSessionRequest
import xyz.block.trailblaze.trailrunner.RevealTrailRequest
import xyz.block.trailblaze.trailrunner.RevealTrailsRootRequest
import xyz.block.trailblaze.trailrunner.RunRequest
import xyz.block.trailblaze.trailrunner.RunResponse
import xyz.block.trailblaze.trailrunner.SaveTrailRequest
import xyz.block.trailblaze.trailrunner.SaveTrailResponse
import xyz.block.trailblaze.trailrunner.SessionsResponse
import xyz.block.trailblaze.trailrunner.SetFavoriteRequest
import xyz.block.trailblaze.trailrunner.SettingsDto
import xyz.block.trailblaze.trailrunner.SettingsPatchRequest
import xyz.block.trailblaze.trailrunner.ToolCatalogResponse
import xyz.block.trailblaze.trailrunner.ToolRevealRequest
import xyz.block.trailblaze.trailrunner.ToolRunRequest
import xyz.block.trailblaze.trailrunner.ToolRunResponse
import xyz.block.trailblaze.trailrunner.ToolSourceSaveRequest
import xyz.block.trailblaze.trailrunner.ToolUsageCountsResponse
import xyz.block.trailblaze.trailrunner.TrailDetailResponse
import xyz.block.trailblaze.trailrunner.TrailIndexResponse
import xyz.block.trailblaze.trailrunner.TrailOpenRequest
import xyz.block.trailblaze.trailrunner.TrailRootsResponse
import xyz.block.trailblaze.trailrunner.TrailmapsResponse
import xyz.block.trailblaze.trailrunner.UpdateTrailRequest
import xyz.block.trailblaze.trailrunner.ValidateTrailRequest
import xyz.block.trailblaze.trailrunner.ValidateTrailResponse
import java.io.File
import kotlin.reflect.KClass

/**
 * Generates the TypeScript bindings for the Trail Runner web UI from the Kotlin `@Serializable`
 * DTOs the daemon's HTTP API exchanges as JSON, PLUS a typed `createTrailRunnerRpcClient` for the
 * Trail Runner `/rpc/<Name>` endpoints (see [xyz.block.trailblaze.trailrunner.TrailRunnerRpc]). The
 * framework stays Kotlin-canonical; the web UI consumes the derived `.ts` so a renamed/removed
 * Kotlin field surfaces as a TS compile error at every call site instead of a silent runtime drift.
 *
 * The RPC-client reflection + emission is shared with `HostRpcDtoTsBindings` via
 * [RpcClientTsCodegen] (in `:trailblaze-models`); this object owns the Trail Runner DTO export roots,
 * the RPC endpoint allowlist, and the file header.
 *
 * Run via `./gradlew :trailblaze-host:generateDtoTs`; the committed output is byte-diff verified in
 * CI by `verifyDtoTs`.
 */
internal object TrailRunnerDtoTsBindings {

  /**
   * The explicit export allowlist: the top-level request/response payloads of the Trail Runner
   * HTTP API. Nested DTOs (e.g. `SessionSummary`, `ToolParamDto`) and referenced types (e.g.
   * `TrailblazeDeviceId`, `ToolFlavor`) are pulled in transitively — only the endpoint roots need
   * listing here. Adding a type to the UI's surface is a deliberate one-line edit, by design.
   */
  private val ROOTS: List<SerialDescriptor> = listOf(
    AddTrailRootRequest.serializer().descriptor,
    AnalyticsResponse.serializer().descriptor,
    CancelSessionResponse.serializer().descriptor,
    CreateTrailDirRequest.serializer().descriptor,
    CreateTrailRequest.serializer().descriptor,
    DeleteSessionResponse.serializer().descriptor,
    DeviceAppsResponse.serializer().descriptor,
    EditedTrailsResponse.serializer().descriptor,
    FavoriteRequest.serializer().descriptor,
    FavoritesResponse.serializer().descriptor,
    IntegrationsResponse.serializer().descriptor,
    LlmSettingsDto.serializer().descriptor,
    NewComponentRequest.serializer().descriptor,
    NewComponentResponse.serializer().descriptor,
    OkResponse.serializer().descriptor,
    RebuildDaemonResponse.serializer().descriptor,
    RunRequest.serializer().descriptor,
    RunResponse.serializer().descriptor,
    SaveTrailRequest.serializer().descriptor,
    SaveTrailResponse.serializer().descriptor,
    SessionsResponse.serializer().descriptor,
    SettingsDto.serializer().descriptor,
    ToolCatalogResponse.serializer().descriptor,
    ToolRevealRequest.serializer().descriptor,
    ToolRunRequest.serializer().descriptor,
    ToolRunResponse.serializer().descriptor,
    ToolSourceSaveRequest.serializer().descriptor,
    ToolUsageCountsResponse.serializer().descriptor,
    TrailDetailResponse.serializer().descriptor,
    TrailIndexResponse.serializer().descriptor,
    TrailOpenRequest.serializer().descriptor,
    TrailRootsResponse.serializer().descriptor,
    TrailmapsResponse.serializer().descriptor,
    ValidateTrailResponse.serializer().descriptor,
  )

  /**
   * The Trail Runner RPC endpoint allowlist: `RpcRequest<TResponse>` implementors whose typed
   * `createTrailRunnerRpcClient` method (response type + `/rpc/<Name>` path) is reflected, not
   * hand-paired. Adding an endpoint is a one-line edit here + a handler in `TrailRunnerRpc.kt`.
   */
  private val RPC_REQUESTS: List<KClass<out RpcRequest<*>>> = listOf(
    GetSessionsRequest::class,
    GetToolsRequest::class,
    GetTrailmapsRequest::class,
    GetTrailsRequest::class,
    GetTrailRootsRequest::class,
    GetEditedTrailsRequest::class,
    GetTrailDetailRequest::class,
    ValidateTrailRequest::class,
    GetFavoritesRequest::class,
    GetIntegrationsRequest::class,
    GetSettingsRequest::class,
    GetToolUsageCountsRequest::class,
    GetToolUsagesRequest::class,
    GetDeviceAppsRequest::class,
    GetRunToolsRequest::class,
    GetSessionAnalyticsRequest::class,
    GetSessionFilesRequest::class,
    GetToolSourceRequest::class,
    // Mutation / command endpoints.
    CreateTrailRequest::class,
    CreateTrailDirRequest::class,
    UpdateTrailRequest::class,
    ToolSourceSaveRequest::class,
    AddTrailRootRequest::class,
    RemoveTrailRootRequest::class,
    SetFavoriteRequest::class,
    RunRequest::class,
    ToolRunRequest::class,
    RebuildDaemonRequest::class,
    // Side-effect / reveal commands.
    ToolRevealRequest::class,
    TrailOpenRequest::class,
    RevealTrailRequest::class,
    RevealTrailsRootRequest::class,
    RevealSessionRequest::class,
    DeleteSessionRequest::class,
    CancelSessionRequest::class,
    IntegrationActionRequest::class,
    OpenSessionFileRequest::class,
    NewComponentRequest::class,
    SettingsPatchRequest::class,
  )

  fun generate(): String = RpcClientTsCodegen.generate(
    header = HEADER,
    extraTypeRoots = ROOTS,
    requests = RPC_REQUESTS,
    clientFunctionName = "createTrailRunnerRpcClient",
    surfaceLabel = "Trail Runner",
  )

  private const val HEADER: String =
    "// AUTO-GENERATED — do not edit by hand.\n" +
      "//\n" +
      "// TypeScript bindings for the Trail Runner web UI, derived from the Kotlin @Serializable\n" +
      "// DTOs the daemon's HTTP API exchanges as JSON, plus a typed client for the Trail Runner\n" +
      "// /rpc/<Name> endpoints. Kotlin is canonical; this is the derived artifact.\n" +
      "//\n" +
      "// Regenerate with the `generateDtoTs` Gradle task; CI's `verifyDtoTs` byte-diffs this file\n" +
      "// against a fresh generation and fails the build on drift, so hand edits are reverted on\n" +
      "// the next CI run.\n"
}

/**
 * Entry point for the `generateDtoTs` Gradle task (invoked via `JavaExec`).
 * `args[0]` is the output file path. Public so the JavaExec resolves a standard `main(String[])`.
 */
fun main(args: Array<String>) {
  val outPath = args.firstOrNull()
    ?: error("usage: TrailRunnerDtoTsBindingsKt <output-file.ts>")
  val outFile = File(outPath)
  outFile.parentFile?.mkdirs()
  outFile.writeText(TrailRunnerDtoTsBindings.generate(), Charsets.UTF_8)
  println("Wrote Trail Runner DTO TypeScript bindings to ${outFile.absolutePath}")
}
