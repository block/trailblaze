package xyz.block.trailblaze.trailrunner

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.JsonElement
import xyz.block.trailblaze.devices.TrailblazeDeviceId
import xyz.block.trailblaze.mcp.android.ondevice.rpc.RpcRequest

@Serializable
data class IntegrationsResponse(val integrations: List<IntegrationDto>)

// NOTE: IntegrationDto + IntegrationActionDto moved to :trailblaze-host (OSS) as part of the
// TrailRunnerExtension seam. Same `xyz.block.trailblaze.trailrunner` package, so referencing them
// here needs no import. See TrailRunnerExtensionDtos.kt.

@Serializable
data class OkResponse(val ok: Boolean, val error: String? = null)

/**
 * Whether the currently-loaded app-target set differs from what the active workspace would declare.
 * The target set is resolved once at daemon startup (it wires into the device manager), so a
 * workspace switch that adds/removes/overrides app targets can't take effect until a restart — this
 * lets the UI nudge the user. [restartNeeded] is true when [added] or [removed] is non-empty.
 */
@Serializable
data class WorkspaceTargetDriftDto(
  val restartNeeded: Boolean,
  val added: List<String> = emptyList(),
  val removed: List<String> = emptyList(),
)

@Serializable
data class AnalyticsResponse(val available: Boolean, val events: List<AnalyticsEventDto>)

// NOTE: AnalyticsEventDto moved to :trailblaze-host (OSS) as part of the TrailRunnerExtension seam
// (same package — no import needed). See TrailRunnerExtensionDtos.kt.

// ─── Event-stream capture ─────────────────────────────────────────────────────
// One run can capture pluggable event streams into NDJSON files under
// <sessionDir>/events/<name>.<style>.ndjson (see the generic xyz.block.trailblaze.events
// format + SessionEventsReader). A downstream event tap is one such producer. The run detail
// interlaces those events into the timeline (by timeMs), filterable by stream via the
// timeline's filter. The data is sourced producer-agnostically from the events/ folder.
@Serializable
data class SessionEventsResponse(val available: Boolean, val streams: List<SessionEventStreamDto>)

@Serializable
data class SessionEventStreamDto(
  // The event-stream name (the filename `<name>` segment), e.g. a producer's plugin id.
  val streamId: String,
  // Short, human-readable stream name derived from the id (e.g. "network" from
  // "com.example.network") — used as the timeline filter label.
  val label: String,
  // The declared rendering style for this stream (the filename `<style>` segment), e.g. "json",
  // "network", "analytics". Carried so the timeline can format the stream by its declared style.
  val style: String = "json",
  val count: Int,
  // True when the per-stream read cap was hit and [events] is a prefix of the file rather than the
  // whole thing. A single stream file can be tens of MB (large payloads); the endpoint bounds how
  // much it parses + returns per poll so it can't OOM the daemon.
  val truncated: Boolean = false,
  val events: List<SessionEventDto>,
)

@Serializable
data class SessionEventDto(
  val streamId: String,
  // ISO-8601 rendering of [timeMs] for the UI's "received at" display. Reconstructed from the
  // epoch-millis order key, so it is a display field, not an authoritative round-trip of the
  // producer's original timestamp string (sub-ms precision and original zone/offset are not kept).
  val receivedAt: String,
  // Epoch millis order key; the timeline interleaves on this the same way it does analytics
  // `timeMs` and trace step `ts`.
  val timeMs: Long,
  val data: JsonElement,
)

@Serializable
data class SessionsResponse(val sessions: List<SessionSummary>)

@Serializable
data class SessionSummary(
  val id: String,
  val title: String,
  val status: String,
  val durationMs: Long,
  val timestampMs: Long,
  val platform: String? = null,
  val device: String? = null,
  val target: String? = null,
  val hasRecordedSteps: Boolean = false,
  val error: String? = null,
  val trailId: String? = null,
  val imported: Boolean = false,
)

@Serializable
data class TrailDetailResponse(
  val id: String,
  val path: String,
  val title: String,
  val yaml: String,
  val steps: List<TrailStepEntry>,
)

@Serializable
data class TrailStepEntry(
  val kind: String,
  val text: String,
  val tools: List<String> = emptyList(),
)

@Serializable
data class TrailIndexResponse(
  val trails: List<TrailIndexEntry>,
  /** Empty directories under the roots, labeled like [TrailIndexEntry.folder]. */
  val folders: List<String> = emptyList(),
)

@Serializable
data class TrailIndexEntry(
  val id: String,
  val path: String,
  val title: String,
  val target: String? = null,
  val platform: String? = null,
  val driver: String? = null,
  val priority: String? = null,
  val tags: List<String> = emptyList(),
  val folder: String,
  val rootIdx: Int = 0,
  val kind: String = "trail",
)

@Serializable
data class TrailRootsResponse(
  val primary: String,
  val extras: List<String>,
  /** Current git branch of the primary root, or null when it isn't a git checkout (or is detached). */
  val primaryBranch: String? = null,
  /** True when the primary root is a linked git worktree (its `.git` is a file), not the main checkout. */
  val primaryIsWorktree: Boolean = false,
)

@Serializable
data class ToolCatalogResponse(val tools: List<ToolCatalogEntry>)

@Serializable
data class ToolUsageCountsResponse(val counts: Map<String, Int>)

/**
 * What kind of tool a [ToolCatalogEntry] is. The three kinds are exhaustive — a tool is a
 * `@TrailblazeToolClass` Kotlin tool, a `.tool.yaml` declaration, or a `.ts` scripted tool. There is
 * deliberately no `UNKNOWN`: a tool that can't be classified is a real authoring bug (e.g. an
 * unparseable `.tool.yaml`), which `ToolCatalogBuilder` logs and drops rather than surfacing as a
 * bogus catalog entry. `@SerialName` keeps the wire values the lowercase strings the UI already uses.
 */
@Serializable
enum class ToolFlavor {
  @SerialName("kotlin")
  KOTLIN,

  @SerialName("yaml")
  YAML,

  @SerialName("scripted")
  SCRIPTED,
}

@Serializable
data class ToolCatalogEntry(
  val id: String,
  val flavor: ToolFlavor,
  val trailmap: String,
  val sourcePath: String,
  val description: String? = null,
  val className: String? = null,
  val parameters: List<ToolParamDto> = emptyList(),
  val source: String? = null,
  val llmDescription: String? = null,
)

@Serializable
data class ToolParamDto(
  val name: String,
  val type: String,
  val required: Boolean = true,
  val description: String? = null,
)

@Serializable
data class AddTrailRootRequest(val path: String) : RpcRequest<TrailRootsResponse>

@Serializable
data class FavoritesResponse(val ids: List<String>)

@Serializable
data class FavoriteRequest(val id: String)

@Serializable
data class ToolRevealRequest(
  @SerialName("class") val className: String? = null,
  val path: String? = null,
) : RpcRequest<OkResponse>

@Serializable
data class TrailOpenRequest(val id: String) : RpcRequest<OkResponse>

@Serializable
data class CreateTrailRequest(val path: String, val yaml: String) : RpcRequest<SaveTrailResponse>

@Serializable
data class ToolRunRequest(val yaml: String, val trailblazeDeviceId: TrailblazeDeviceId? = null) :
  RpcRequest<ToolRunResponse> {
  // On-device tool execution is bounded at 5 min (TOOL_RUN_TIMEOUT_MS in ToolRoutes.kt); give the
  // RPC a slightly longer budget so a Kotlin client doesn't time out before the daemon's own cap.
  // (Getter, not a constructor param, so it stays out of the serialized wire shape + TS bindings.)
  override val requestTimeoutMs: Long?
    get() = 5L * 60 * 1000 + 30_000
}

@Serializable
data class ToolRunResponse(
  val success: Boolean,
  val result: String? = null,
  val error: String? = null,
  val durationMs: Long = 0,
)

@Serializable
data class CreateTrailDirRequest(val path: String) : RpcRequest<SaveTrailResponse>

@Serializable
data class EditedTrailsResponse(val paths: List<String>)

// The RPC request carries both the session id (RPC has no path segment) and the file name, both
// required — so the generated TypeScript client can't compile a request missing the id the handler
// resolves files with.
@Serializable
data class OpenSessionFileRequest(val id: String, val name: String) : RpcRequest<OkResponse>

// The REST `open-file` route reads the session id from the path and only the file name from the body,
// so its body DTO is name-only (kept separate from the id-carrying RPC request above).
@Serializable
data class OpenSessionFileBody(val name: String)

// The REST `share-html` route persists a client-generated standalone HTML report into the session
// folder (the desktop WKWebView shell has no download handler, so the UI can't save a blob itself —
// the daemon writes the file, then the UI opens/reveals it via the existing host bridges). `name` is
// a slug the server sanitizes; `html` is the full self-contained document.
@Serializable
data class ShareHtmlBody(val name: String, val html: String)

@Serializable
data class ShareHtmlResponse(val ok: Boolean, val name: String? = null, val error: String? = null)

@Serializable
data class SessionArchiveImportResponse(
  val ok: Boolean,
  val sessionId: String? = null,
  val fileCount: Int? = null,
  val error: String? = null,
)

@Serializable
data class SessionFileDto(val name: String, val size: Long)

@Serializable
data class SessionFilesResponse(val files: List<SessionFileDto>)

@Serializable
data class SaveTrailRequest(
  val yaml: String,
  val filename: String? = null,
)

@Serializable
data class SaveTrailResponse(
  val success: Boolean,
  val savedPath: String? = null,
  val error: String? = null,
)

@Serializable
data class RunRequest(
  val trailblazeDeviceId: TrailblazeDeviceId,
  val yaml: String,
  val selfHeal: Boolean? = null,
  val useRecordedSteps: Boolean? = null,
  val maxLlmCalls: Int? = null,
  val agent: String? = null,
  val memory: Map<String, String> = emptyMap(),
  val secrets: Map<String, String> = emptyMap(),
  val captureVideo: Boolean? = null,
  val captureLogcat: Boolean? = null,
  val captureNetworkTraffic: Boolean? = null,
  val captureIosLogs: Boolean? = null,
  val captureAnalytics: Boolean? = null,
  // Dedicated event-stream capture. Host apps can supply producer-specific capture adapters; the
  // serialized Trail Runner contract stays producer-agnostic for the OSS module.
  val captureEvents: Boolean? = null,
  val trailId: String? = null,
  // When set together, this run is recording a draft-blaze variant: on objective-complete the
  // daemon writes the recorded `<variant>.trail.yaml` into the draft folder identified by [draftId].
  val draftId: String? = null,
  val variant: String? = null,
  // Lets a run completion write the recorded `<variant>.trail.yaml` back into a *committed* library
  // folder (one that has left `drafts/`). `@Transient` so it is NOT part of the serialized public
  // `RunRequest`: no `/api/run` or RPC client can set it, keeping the safety fence in
  // [maybeWriteDraftVariant] intact for ordinary callers. Only the server-side `/api/folder/record`
  // path sets it true, in-process, on the `RunRequest` it constructs and passes straight to dispatch
  // (the value is read back from the same in-memory object in `onComplete`, never re-parsed from JSON).
  @Transient val allowCommittedVariantWrite: Boolean = false,
) : RpcRequest<RunResponse>

@Serializable
data class RunResponse(
  val success: Boolean,
  val sessionId: String? = null,
  val error: String? = null,
)

@Serializable
data class DeviceAppDto(
  val id: String,
  val displayName: String,
  val appId: String,
  val versionName: String? = null,
  val versionCode: String? = null,
  val buildNumber: String? = null,
  val minOsVersion: Int? = null,
)

@Serializable
data class DeviceAppsResponse(val targets: List<DeviceAppDto>, val currentTargetAppId: String? = null)

@Serializable
data class DeleteSessionResponse(val deleted: String)

@Serializable
data class ClearSessionsRequest(val confirm: Boolean = false)

@Serializable
data class ClearSessionsResponse(val deleted: Int)

@Serializable
data class CancelSessionResponse(val ok: Boolean)

@Serializable
data class RebuildDaemonResponse(
  val ok: Boolean,
  val error: String? = null,
)

@Serializable
data class ValidateTrailResponse(
  val valid: Boolean,
  val errors: List<ValidationErrorDto> = emptyList(),
)

@Serializable
data class ValidationErrorDto(
  val message: String,
  val line: Int? = null,
)

@Serializable
data class ToolSourceSaveRequest(
  val className: String? = null,
  val path: String? = null,
  val source: String,
) : RpcRequest<SaveTrailResponse>

/** The resolved source text for a tool/component, or `null` when it doesn't resolve. */
@Serializable
data class ToolSourceResponse(val source: String? = null)

@Serializable
data class LlmProviderOptionDto(
  val id: String,
  val display: String,
)

@Serializable
data class LlmModelOptionDto(
  val id: String,
  val provider: String,
)

@Serializable
data class AgentOptionDto(
  val id: String,
  val display: String,
)

@Serializable
data class LlmSettingsDto(
  val provider: String,
  val model: String,
  val availableProviders: List<LlmProviderOptionDto> = emptyList(),
  val availableModels: List<LlmModelOptionDto> = emptyList(),
  // The agent implementation that drives runs/recordings (separate from the LLM, but surfaced
  // alongside it so the UI's run controls show both "which model" and "which agent" together).
  val agent: String = "",
  val availableAgents: List<AgentOptionDto> = emptyList(),
)

@Serializable
data class SettingsDto(
  val themeMode: String,
  val alwaysOnTop: Boolean,
  val captureLogcat: Boolean,
  val captureIosLogs: Boolean,
  val captureNetworkTraffic: Boolean,
  val captureAnalytics: Boolean,
  val showWebBrowser: Boolean,
  val serverPort: Int,
  val serverHttpsPort: Int,
  val showTrailsTab: Boolean,
  val showDevicesTab: Boolean,
  val showWaypointsTab: Boolean,
  val preferHostAgent: Boolean = true,
  val trailsDirectory: String? = null,
  val logsDirectory: String? = null,
  val appDataDirectory: String? = null,
  val llm: LlmSettingsDto,
  val selfHealEnabled: Boolean,
  val requireSteps: Boolean,
  val saveAnnotatedScreenshots: Boolean,
  val maxLlmCalls: Int? = null,
  val screenshotImageFormat: String? = null,
  val screenshotMaxLongerSide: Int? = null,
  val screenshotMaxShorterSide: Int? = null,
  val screenshotCompressionQuality: Float? = null,
)

/**
 * A partial settings update — every field optional, mirroring the keys the old untyped `JsonObject`
 * patch accepted. `null` means "not in this patch, leave it unchanged"; the daemon's `explicitNulls
 * = false` JSON means an omitted field deserializes to `null` and a `null` field never goes on the
 * wire, so a one-key patch like `{"selfHealEnabled": true}` round-trips exactly as before. Clearable
 * fields keep their old sentinels: a blank `trailsDirectory`/`logsDirectory` clears it, and a
 * non-positive `maxLlmCalls` / `screenshotMax*` clears it (see `buildSettingsPatchResponse`).
 */
@Serializable
data class SettingsPatchRequest(
  val themeMode: String? = null,
  val alwaysOnTop: Boolean? = null,
  val captureLogcat: Boolean? = null,
  val captureIosLogs: Boolean? = null,
  val captureNetworkTraffic: Boolean? = null,
  val captureAnalytics: Boolean? = null,
  val showWebBrowser: Boolean? = null,
  val serverPort: Int? = null,
  val serverHttpsPort: Int? = null,
  val showTrailsTab: Boolean? = null,
  val showDevicesTab: Boolean? = null,
  val showWaypointsTab: Boolean? = null,
  val preferHostAgent: Boolean? = null,
  val trailsDirectory: String? = null,
  val logsDirectory: String? = null,
  val appDataDirectory: String? = null,
  val selfHealEnabled: Boolean? = null,
  val requireSteps: Boolean? = null,
  val saveAnnotatedScreenshots: Boolean? = null,
  val maxLlmCalls: Int? = null,
  val llmProvider: String? = null,
  val llmModel: String? = null,
  val agent: String? = null,
  val screenshotImageFormat: String? = null,
  val screenshotMaxLongerSide: Int? = null,
  val screenshotMaxShorterSide: Int? = null,
  val screenshotCompressionQuality: Float? = null,
) : RpcRequest<SettingsDto>

// ─── Trailmaps ──────────────────────────────────────────────────────────────
// A trailmap is the directory that defines one test target. The Trailmaps screen
// browses each trailmap and the component files inside it, grouped by type.
@Serializable
data class TrailmapsResponse(val trailmaps: List<TrailmapEntry>)

@Serializable
data class TrailmapEntry(
  val id: String,
  val displayName: String? = null,
  val manifestPath: String? = null,
  val tools: List<TrailmapComponent> = emptyList(),
  val toolsets: List<TrailmapComponent> = emptyList(),
  val waypoints: List<TrailmapComponent> = emptyList(),
  val shortcuts: List<TrailmapComponent> = emptyList(),
  val trailheads: List<TrailmapComponent> = emptyList(),
  val systemPrompts: List<TrailmapComponent> = emptyList(),
)

@Serializable
data class TrailmapComponent(
  val name: String,
  // Path relative to the workspace, carrying the trails/config/trailmaps/ marker so
  // it resolves via ToolSourceFiles.fileForResource for reveal + lazy body read.
  val relPath: String,
  // Tools only — null for waypoints/shortcuts/trailheads/toolsets/system-prompts.
  val flavor: ToolFlavor? = null,
)

@Serializable
data class NewComponentRequest(val trailmap: String, val kind: String, val name: String) :
  RpcRequest<NewComponentResponse>

@Serializable
data class NewComponentResponse(
  val ok: Boolean,
  val relPath: String? = null,
  val savedPath: String? = null,
  val error: String? = null,
)

// The toolsets (and their tools) that actually register for a run against a given
// target + driver — what the LLM agent will see at session start. Powers the
// "Tools for this run" tab in the Configure-run dialog.
@Serializable
data class RunToolsResponse(
  val target: String,
  val driver: String,
  val resolved: Boolean,
  val toolsets: List<RunToolSetDto>,
)

@Serializable
data class RunToolSetDto(
  val id: String,
  val description: String,
  val alwaysEnabled: Boolean,
  val tools: List<String>,
)

// ─── Blaze authoring: propose + drafts ────────────────────────────────────────
// NOTE: ProposedStep moved to :trailblaze-host (OSS) as part of the TrailRunnerExtension seam
// (same package — no import needed). See TrailRunnerExtensionDtos.kt.

// Structured LLM result for the plan-only proposer (Koog executeStructured target).
// IMPORTANT schema constraints for OpenAI strict structured-output mode:
//  - No default values — every property must appear in the schema's `required` array, and a Kotlin
//    default makes the field optional (rejected with "'required' … Missing 'steps'").
//  - This is a list of PRIMITIVE strings, NOT a list of objects, on purpose: Koog emits a nested
//    object type as a `$ref` carrying a sibling `$id`, which OpenAI strict mode rejects ("$ref
//    cannot have keywords {'$id'}"). A primitive-string array inlines cleanly with no `$ref`.
//    Each entry is "do: <action>" or "verify: <assertion>"; we parse the prefix into [ProposedStep].
@Serializable
data class ProposedStepsResult(val steps: List<String>)

@Serializable
data class ProposeRequest(
  val objective: String,
  val target: String? = null,
  val platform: String? = null,
  // When true, ground the steps with an exploratory device pass (requires [trailblazeDeviceId]).
  // When false (default), the steps are drafted from the prompt alone.
  val ground: Boolean = false,
  val trailblazeDeviceId: TrailblazeDeviceId? = null,
)

@Serializable
data class ProposeResponse(val steps: List<ProposedStep> = emptyList(), val error: String? = null)

// NOTE: ReviewSuggestionDto moved to :trailblaze-host (OSS) as part of the TrailRunnerExtension seam
// (same package — no import needed). See TrailRunnerExtensionDtos.kt.

@Serializable
data class ReviewTrailResponse(
  val suggestions: List<ReviewSuggestionDto> = emptyList(),
  val error: String? = null,
)

// A draft blaze: a folder holding a `blaze.yaml` spec plus zero or more recorded
// `<platform>.trail.yaml` variants. [id] is "<rootIdx>/<relPath>"; [home] is the folder's
// path relative to its root; [inDrafts] is true while it lives under the `drafts/` staging dir.
@Serializable
data class DraftSummary(
  val id: String,
  val name: String,
  val home: String,
  val inDrafts: Boolean,
  val variants: List<String> = emptyList(),
  val hasRecordings: Boolean = false,
)

@Serializable
data class DraftsResponse(val drafts: List<DraftSummary>)

@Serializable
data class CreateDraftRequest(val name: String, val yaml: String)

@Serializable
data class CreateDraftResponse(val success: Boolean, val id: String? = null, val error: String? = null)

@Serializable
data class DraftVariant(val name: String, val platform: String? = null)

@Serializable
data class DraftDetailResponse(
  val id: String,
  val name: String,
  val objective: String? = null,
  val target: String? = null,
  val platform: String? = null,
  // Where this draft will be committed (its eventual home under the trails workspace). Picked up
  // front and carried in the blaze's config.metadata["destination"]; the folder physically stays
  // under drafts/ until the user commits.
  val destination: String? = null,
  val context: String? = null,
  val home: String,
  val inDrafts: Boolean,
  val blazeYaml: String,
  val steps: List<TrailStepEntry> = emptyList(),
  val variants: List<DraftVariant> = emptyList(),
)

@Serializable
data class UpdateDraftRequest(val id: String, val yaml: String)

@Serializable
data class SaveDraftToRequest(val id: String, val destination: String)

@Serializable
data class DraftIdRequest(val id: String)

@Serializable
data class RecordDraftRequest(
  val id: String,
  val deviceIds: List<TrailblazeDeviceId>,
  // Optional recording properties set in the Configure-recording dialog; forwarded to each run.
  val maxLlmCalls: Int? = null,
  val agent: String? = null,
  val captureVideo: Boolean? = null,
  val selfHeal: Boolean? = null,
  // "Fresh install" trailhead: clear the app's state before recording (prepends a mobile_clearAppData
  // setup tool for [clearAppId], the device app id / bundle id to reset).
  val freshInstall: Boolean? = null,
  val clearAppId: String? = null,
  // The per-platform trailhead tool id to run as step 0 before the prompts (e.g.
  // myapp_android_signedInFresh). Chosen on the recording's platform column / the Configure-recording
  // dialog and baked into THIS platform's recording; the trailhead is platform-specific, so it lives
  // on the recording, never on the cross-platform blaze.yaml. Prepended as a top-level `- tools:`
  // item with no params. Takes precedence over freshInstall when both are set.
  val trailheadId: String? = null,
)

@Serializable
data class RecordDraftResponse(val sessionIds: List<String> = emptyList(), val error: String? = null)

@Serializable
data class UpdateDraftFileRequest(val id: String, val name: String, val yaml: String)
