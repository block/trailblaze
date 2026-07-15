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

// ─── External coding-agent sessions ─────────────────────────────────────────
//
// Trail Runner can supervise opaque vendor CLIs (Claude Code, Codex CLI) without becoming their
// tool executor. The child CLI owns its model/tool loop; Trail Runner owns process lifecycle,
// normalized event streaming, and a small UI-command protocol that lets the child explain which
// Trail Runner surface should be shown next.

@Serializable
enum class ExternalAgentType {
  @SerialName("claude")
  CLAUDE,

  @SerialName("codex")
  CODEX,

  /**
   * No agent attached: a human-only Create session. The daemon spawns no child process - the run
   * exists purely as the event stream the human's demonstrated actions (HUMAN_ACTION) land in,
   * so recording and saving a trail works without any vendor CLI installed.
   */
  @SerialName("solo")
  SOLO,
}

@Serializable
enum class ExternalAgentSessionStatus {
  @SerialName("running")
  RUNNING,

  @SerialName("completed")
  COMPLETED,

  @SerialName("failed")
  FAILED,

  @SerialName("cancelled")
  CANCELLED,
}

@Serializable
enum class ExternalAgentEventKind {
  @SerialName("lifecycle")
  LIFECYCLE,

  @SerialName("user_message")
  USER_MESSAGE,

  @SerialName("assistant_message")
  ASSISTANT_MESSAGE,

  @SerialName("reasoning")
  REASONING,

  @SerialName("tool_call")
  TOOL_CALL,

  @SerialName("tool_result")
  TOOL_RESULT,

  @SerialName("ui_command")
  UI_COMMAND,

  @SerialName("stdout")
  STDOUT,

  @SerialName("stderr")
  STDERR,

  @SerialName("final_result")
  FINAL_RESULT,

  @SerialName("usage")
  USAGE,

  @SerialName("error")
  ERROR,

  @SerialName("human_action")
  HUMAN_ACTION,

  @SerialName("permission_request")
  PERMISSION_REQUEST,

  @SerialName("permission_decision")
  PERMISSION_DECISION,
}

/**
 * One entry in the external-agent provider registry. Everything the UI needs to offer a provider
 * — the picker label, availability, the composer's model options, and the setup instructions the
 * "Configure agents" page renders — lives on this DTO, so adding a provider (or a model) is a
 * server-side registry change, not a UI change.
 */
@Serializable
data class ExternalAgentOptionDto(
  val id: ExternalAgentType,
  val display: String,
  val executable: String,
  val available: Boolean,
  val detail: String? = null,
  /** How to install the CLI (shell command(s), shown verbatim on the setup page). */
  val installHint: String? = null,
  /** How to sign in / grant the CLI access to its models. */
  val authHint: String? = null,
  /** How model selection works for this provider (aliases, config). */
  val modelsHint: String? = null,
  val docsUrl: String? = null,
  /** Predefined model choices for the composer's picker. Empty id = the CLI's own default. */
  val models: List<ExternalAgentModelOptionDto> = emptyList(),
)

@Serializable
data class ExternalAgentModelOptionDto(val id: String, val display: String)

@Serializable
data class TrailRunnerUiContextDto(
  val route: String? = null,
  val trailId: String? = null,
  val sessionId: String? = null,
  val target: String? = null,
  val platform: String? = null,
  val deviceId: TrailblazeDeviceId? = null,
)

@Serializable
data class TrailRunnerUiCommandDto(
  val version: Int = 1,
  /**
   * One of: navigate, open_session, open_trail, trail_output, show_message, focus_external_agent.
   * Kept as a string instead of an enum so older Trail Runner builds can safely ignore newer
   * commands while still showing the raw command in the event stream.
   */
  val action: String,
  val route: String? = null,
  val sessionId: String? = null,
  val trailId: String? = null,
  val message: String? = null,
  val severity: String? = null,
  /** String-valued parameters for route/query state, for example {"sel":"session-id"}. */
  val params: Map<String, String> = emptyMap(),
)

@Serializable
data class ExternalAgentRunRequest(
  val agentType: ExternalAgentType,
  val prompt: String,
  val title: String? = null,
  val model: String? = null,
  /**
   * Optional working directory for the child CLI. The web UI sends the active workspace; the daemon
   * falls back to [TrailRunnerDeps.trailsRootProvider] when this is blank or unusable.
   */
  val cwd: String? = null,
  /**
   * Cross-vendor access level: read-only, workspace-write, or danger-full-access (default
   * workspace-write). Translated into each CLI's own permission mechanism — Codex `--sandbox`,
   * Claude permission mode / skip-permissions — by `externalAgentCommand`.
   */
  val sandbox: String? = null,
  /** Whether to append/prepend the Trail Runner UI-command contract to the child agent prompt. */
  val includeUiContract: Boolean = true,
  /**
   * Instruction block prepended to the first turn's child prompt but NOT shown as the user
   * message. The UI sends session recipes (e.g. the guided compose script) here, so the composer
   * and the transcript carry only the human's own words while the child CLI still gets the full
   * script. Follow-up turns resume the same vendor thread, which already carries it.
   */
  val promptPreamble: String? = null,
  val uiContext: TrailRunnerUiContextDto? = null,
  /**
   * Extra directories the agent may access outside its cwd. Claude: passed via `--add-dir`.
   * Codex sandboxing does not block reads outside the workspace, so no flag is needed there.
   */
  val extraDirs: List<String> = emptyList(),
)

@Serializable
data class ExternalAgentRunDto(
  val id: String,
  val agentType: ExternalAgentType,
  val title: String,
  val prompt: String,
  val cwd: String,
  val model: String? = null,
  val status: ExternalAgentSessionStatus,
  val startedAtMs: Long,
  val endedAtMs: Long? = null,
  val externalThreadId: String? = null,
  val exitCode: Int? = null,
  val error: String? = null,
  val eventCount: Int = 0,
  /** Present only for demonstrate-first Create runs; lets the web restore the demo phase on refresh. */
  val demo: DemoStateDto? = null,
  /**
   * Set only on a GENERATION run: the demonstration run it authors a trail from. The web hides such
   * runs from the Create sidebar (one entry per trail-in-progress) and embeds their transcript in
   * the demonstration's view instead.
   */
  val demoRunId: String? = null,
  /**
   * Permission requests from the spawned CLI that are waiting on a human decision. The web renders
   * one approve/deny card per entry; the events stream carries the transcript record separately.
   */
  val pendingPermissions: List<ExternalAgentPermissionRequestDto> = emptyList(),
  /** When true, this run auto-allows every permission request without asking the human. */
  val autoApprove: Boolean = false,
)

/**
 * A permission request from a spawned CLI that is waiting on a human decision (approve / deny).
 * [inputJson] is the tool's proposed input as a compact JSON string, for the card's preview.
 */
@Serializable
data class ExternalAgentPermissionRequestDto(
  val id: String,
  val toolName: String,
  val inputJson: String? = null,
  val requestedAtMs: Long,
)

// Body of `/permission-request` (the MCP proxy is the only caller). The proxy forwards the tool the
// spawned CLI wants to run; the route suspends until the human decides, then answers with the
// --permission-prompt-tool contract's allow/deny shape (see [ExternalAgentPermissionResponse]).
@Serializable
data class ExternalAgentPermissionRequestBody(
  val toolName: String,
  val inputJson: String? = null,
  val toolUseId: String? = null,
)

// Response to `/permission-request`. `behavior` is "allow" or "deny"; on allow [updatedInputJson]
// is the (pass-through) input the tool should run with; on deny [message] explains why. The proxy
// converts this back into the object form Claude Code's --permission-prompt-tool contract expects.
@Serializable
data class ExternalAgentPermissionResponse(
  val behavior: String,
  val updatedInputJson: String? = null,
  val message: String? = null,
)

// Body of `/permission`: the human's decision on a pending request. `decision` is one of
// "allow", "allow_always" (allow + remember this tool for the run), or "deny".
@Serializable
data class ExternalAgentPermissionDecisionRequest(
  val requestId: String,
  val decision: String,
)

// Body of `/auto-approve`: turn per-run auto-approval on or off.
@Serializable
data class ExternalAgentAutoApproveRequest(val enabled: Boolean)

// Response to `/demo/trail-content`: the files of the trail a demonstration's generation run has
// delivered (or is writing). [files] is empty until any are known.
@Serializable
data class DemoTrailContentResponse(
  val ok: Boolean,
  val trailId: String? = null,
  val files: List<DemoTrailFileDto> = emptyList(),
  val error: String? = null,
)

@Serializable
data class DemoTrailFileDto(val name: String, val content: String)

@Serializable
data class ExternalAgentEventDto(
  val id: String,
  val runId: String,
  val seq: Int,
  val timeMs: Long,
  val agentType: ExternalAgentType,
  val kind: ExternalAgentEventKind,
  val status: ExternalAgentSessionStatus? = null,
  val title: String? = null,
  val text: String? = null,
  val toolName: String? = null,
  val toolCallId: String? = null,
  /** Compact JSON string for arbitrary vendor-specific payloads. */
  val input: String? = null,
  /** Compact JSON string for arbitrary vendor-specific payloads. */
  val output: String? = null,
  val uiCommand: TrailRunnerUiCommandDto? = null,
  /** Compact JSON string for arbitrary vendor-specific usage payloads. */
  val usage: String? = null,
  /** Compact JSON string for the raw vendor event. */
  val raw: String? = null,
)

/**
 * A follow-up turn on an existing run. The daemon resumes the vendor thread ([ExternalAgentRunDto.externalThreadId])
 * with a fresh CLI invocation, so a run is a whole conversation — events keep appending across turns.
 */
@Serializable
data class ExternalAgentReplyRequest(val prompt: String)

@Serializable
data class ExternalAgentRunsResponse(
  val supportedAgents: List<ExternalAgentOptionDto>,
  val runs: List<ExternalAgentRunDto>,
)

@Serializable
data class ExternalAgentStartResponse(
  val ok: Boolean,
  val run: ExternalAgentRunDto? = null,
  val error: String? = null,
)

@Serializable
data class ExternalAgentEventsResponse(val events: List<ExternalAgentEventDto>)

// ─── Demonstrate-first Create (demo sessions) ───────────────────────────────
//
// A demonstration is a solo-style (agent-less) run in "demo mode": the human positions the app,
// presses Start, then demonstrates a flow on the device mirror. Every gesture drives the device
// AND is captured into a durable on-disk bundle (manifest + actions.ndjson + evidence frames)
// under the run's tape dir, so a later slice can author a trail from it. Phases:
// positioning -> recording -> done.

@Serializable
data class StartDemoRequest(
  val target: String? = null,
  val platform: String? = null,
  val trailblazeDeviceId: TrailblazeDeviceId,
  val title: String? = null,
)

@Serializable
data class StartDemoResponse(
  val ok: Boolean,
  val runId: String? = null,
  val error: String? = null,
)

/** The trailhead the human positioned from; a null trailhead in [DemoMarkStartRequest] means manual positioning. */
@Serializable
data class DemoTrailheadDto(
  val name: String,
  val args: Map<String, String> = emptyMap(),
  val yaml: String? = null,
)

@Serializable
data class DemoMarkStartRequest(val trailhead: DemoTrailheadDto? = null)

@Serializable
data class DemoPhaseResponse(
  val ok: Boolean,
  val phase: String? = null,
  val error: String? = null,
)

@Serializable
data class DemoFinishRequest(
  val objective: String,
  val notes: String? = null,
)

@Serializable
data class DemoFinishResponse(
  val ok: Boolean,
  val bundleDir: String? = null,
  val error: String? = null,
)

/**
 * Body of `/demo/delete-step`: remove one demonstrated step (a mistake made mid-recording) by the
 * id of its HUMAN_ACTION event. The event, its actions.ndjson line, and its evidence files all go.
 */
@Serializable
data class DemoDeleteStepRequest(val eventId: String)

/**
 * Demo state rides the existing run snapshot so the web can restore the phase on refresh.
 * [bundleDir] is the CURRENT platform's bundle dir (`<draftDir>/demos/<platform>/`); [draftDir] is
 * the whole draft dir under the workspace; [generationRunId] is the external-agent run launched by
 * /demo/generate to author the trail from this demonstration (null until generation is requested).
 *
 * A trail is demonstrable on multiple platforms: [platform] is the platform being demonstrated now,
 * [platforms] is every platform demonstrated so far (each with whether its demonstration completed).
 * Once a generation run delivers a trail, [trailId]/[trailFiles]/[trailVerified] carry it (observed
 * from the generation run's `trail_output`). Every field beyond [phase] is optional so an older
 * payload still deserializes.
 */
@Serializable
data class DemoStateDto(
  val phase: String,
  val bundleDir: String? = null,
  val objective: String? = null,
  val generationRunId: String? = null,
  /** The platform key being demonstrated right now (iphone, ipad, android, android-tablet, web). */
  val platform: String? = null,
  /** Every platform demonstrated for this trail, in first-demonstrated order. */
  val platforms: List<DemoPlatformDto> = emptyList(),
  val draftDir: String? = null,
  val trailId: String? = null,
  /** Comma-separated files the delivered trail touched (from the generation run's trail_output). */
  val trailFiles: String? = null,
  /** The server-side verified verdict for the delivered trail (a passing run was actually observed). */
  val trailVerified: Boolean? = null,
)

/** One demonstrated platform: its key (see [DemoStateDto.platform]) and whether its demo completed. */
@Serializable
data class DemoPlatformDto(
  val key: String,
  val done: Boolean,
)

/** Body of `/demo/add-platform`: the device to demonstrate the next platform on (same shape start-demo uses). */
@Serializable
data class DemoAddPlatformRequest(val deviceId: TrailblazeDeviceId)

/**
 * Launch the generation agent for a finished demonstration: a new external-agent run reads the
 * demonstration bundle and authors a trail from it. [sandbox] defaults to workspace-write (the run
 * writes the trail into the trails library); [model] is the optional vendor model alias.
 */
@Serializable
data class DemoGenerateRequest(
  val agentType: ExternalAgentType,
  val model: String? = null,
  val sandbox: String? = null,
)

@Serializable
data class DemoGenerateResponse(
  val ok: Boolean,
  val generationRunId: String? = null,
  val error: String? = null,
)

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
  /** Resolved package name (Android) / bundle id (iOS) of the app under test, when captured. */
  val appId: String? = null,
  /** User-visible app version (Android versionName / iOS CFBundleShortVersionString). */
  val appVersionName: String? = null,
  /** Internal app version (Android versionCode / iOS CFBundleVersion). */
  val appVersionCode: String? = null,
  /** iOS app-specific build number, when available. */
  val appBuildNumber: String? = null,
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
  /** On-disk YAML shape: "unified" (single-file `config:`+`trail:` mapping) or "v1" (legacy list). */
  val format: String = "v1",
  /** The trail's declared `config.id`. Per-platform variants of one logical trail share it. */
  val configId: String? = null,
  /** Whether the file carries any recorded (deterministically replayable) steps. */
  val hasRecordedSteps: Boolean = false,
)

/** Result of migrating a legacy per-platform bundle folder to a single unified `.trail.yaml`. */
@Serializable
data class MigrateFolderResponse(
  val success: Boolean,
  /** Name of the unified file written into the folder, on success. */
  val outputName: String? = null,
  val steps: Int = 0,
  val driftCount: Int = 0,
  /** NL / memory drift warnings surfaced by the migrator (also leading comments in the file). */
  val drift: List<String> = emptyList(),
  /** Per-platform input files (+ `blaze.yaml`) that were deleted. */
  val removed: List<String> = emptyList(),
  /** Human-readable reason on failure (e.g. the migrator refused a trailhead / top-level tools). */
  val error: String? = null,
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
  /** Closed set of allowed values (a schema enum, or a union discriminator's variants) - render a dropdown. */
  val validValues: List<String>? = null,
  /** Per-value descriptions aligned with [validValues] ("" when a value has none); null when no value has one. */
  val validValueDescriptions: List<String>? = null,
  /**
   * Present when this param belongs to some variants of a discriminated union: it applies only
   * while the named sibling param (the `<parent>.type` discriminator) holds one of [ToolParamVisibilityDto.values].
   * Same lowering the MCP tool-discovery surface uses; dotted names group back into nested YAML.
   */
  val visibleWhen: ToolParamVisibilityDto? = null,
)

@Serializable
data class ToolParamVisibilityDto(
  val parameterName: String,
  val values: List<String>,
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
  // When set together, this run is recording a bundle variant: on objective-complete the daemon
  // writes the recorded `<variant>.trail.yaml` into the bundle folder identified by [bundleId].
  // `@Transient` so they are NOT part of the serialized public `RunRequest`: no `/api/run` or RPC
  // client can request a library write. Only the server-side `/api/folder/record` path sets them,
  // in-process, on the `RunRequest` it constructs and passes straight to dispatch (the values are
  // read back from the same in-memory object in `onComplete`, never re-parsed from JSON) - their
  // presence IS the authorization checked by [maybeWriteBundleVariant].
  @Transient val bundleId: String? = null,
  @Transient val variant: String? = null,
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

// One user-installed app on a device, unfiltered by declared targets — what the Create Target
// form's "Browse installed apps" picker lists. Distinct from [DeviceAppDto], which only covers
// apps matching an already-declared target.
@Serializable
data class InstalledAppDto(
  val appId: String,
  val label: String? = null,
  val version: String? = null,
)

@Serializable
data class InstalledAppsResponse(val apps: List<InstalledAppDto>)

// REST-only (GET /api/installed-app-badge) — the per-row enrichment the Create Target picker
// fetches after the fast id list, since label/icon extraction can be slow on a cold cache.
@Serializable
data class InstalledAppBadgeDto(val label: String? = null, val hasIcon: Boolean = false)

@Serializable
data class DeleteSessionResponse(val deleted: String)

@Serializable
data class ClearSessionsRequest(val confirm: Boolean = false)

@Serializable
data class ClearSessionsResponse(val deleted: Int)

/**
 * [reason] disambiguates the `ok = false` cases so the UI can be honest about what happened:
 * "cancelled" (ok = true), "already_ended" (the run reached a terminal status before the cancel
 * landed), or "not_running" (no live execution was found to cancel - nothing was stopped and no
 * cancellation was recorded).
 */
@Serializable
data class CancelSessionResponse(val ok: Boolean, val reason: String? = null)

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
  val trailheads: List<TrailmapComponent> = emptyList(),
  val systemPrompts: List<TrailmapComponent> = emptyList(),
  // Platform keys of the resolved target block (`android`/`ios`/`web`/`compose`), sorted; empty
  // for library trailmaps. Lets the Target picker decide which declared-but-undetected targets
  // can ever surface on a connected device (web-only ones can't — apps aren't "installed" there).
  val platforms: List<String> = emptyList(),
  // False when the workspace `trailblaze.yaml` carries a non-empty `targets:` allow-list that
  // does NOT include this id — the runtime will never load such a target, so the picker must not
  // offer it as a card that could activate. The Trailmaps screen still browses everything.
  val workspaceListed: Boolean = true,
)

@Serializable
data class TrailmapComponent(
  val name: String,
  // Path relative to the workspace, carrying the trails/config/trailmaps/ marker so
  // it resolves via ToolSourceFiles.fileForResource for reveal + lazy body read.
  val relPath: String,
  // Tools and dynamic (scripted) trailheads - null for YAML trailheads/system-prompts.
  val flavor: ToolFlavor? = null,
  // Platforms the component supports (a scripted tool's supportedPlatforms gate). Null =
  // unrestricted; pickers fall back to the `_<platform>_` name-token heuristic.
  val platforms: List<String>? = null,
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

/**
 * Edits the `target:` block of an *existing* `trailmap.yaml` (adds one if the trailmap currently
 * has none — a "library trailmap", see `docs/trailmaps.md`). Covers the common fields only:
 * [displayName], [icon], and per-platform [SaveTargetPlatformPatch.appIds]/
 * [SaveTargetPlatformPatch.baseUrl]/[SaveTargetPlatformPatch.icon]. The target's own `id` and the
 * rarer target/platform fields (`system_prompt_file`, `has_custom_ios_driver`, `target.tools`,
 * per-platform `tool_sets`/`tools`/`excluded_tools`/`drivers`/`min_build_version`) are preserved
 * from the existing manifest untouched — not editable via this request.
 *
 * With [createIfMissing] false (the default), [trailmapId] must already resolve via
 * [ToolSourceFiles.trailmapBaseDir] to a directory carrying a `trailmap.yaml`. With
 * [createIfMissing] true, a missing trailmap is bootstrapped instead: the directory and a minimal
 * manifest (`id`, `dependencies: [trailblaze]`, and the requested `target:` block — the exact
 * shape `docs/your-first-trailmap.md` teaches) are created under the active workspace's
 * `trailmaps/` dir, and the id is appended to the workspace `trailblaze.yaml`'s `targets:` list
 * when (and only when) that list is non-empty — an empty/omitted list already auto-discovers
 * every workspace trailmap, and rewriting it would silently narrow the workspace to an explicit
 * allow-list.
 *
 * [platforms] only carries the platform keys the caller wants to add/edit/remove — any platform
 * already configured on the trailmap but absent from this map is left completely untouched.
 * Within an edited platform ([SaveTargetPlatformPatch.remove] false), [SaveTargetPlatformPatch.appIds]/
 * [SaveTargetPlatformPatch.baseUrl]/[SaveTargetPlatformPatch.icon] wholesale-replace the existing
 * ones (null clears the field) — the caller (the Edit Target form) is expected to resend the
 * platform's *current* values for any field the user didn't change, since it loads the form
 * pre-populated with them. The platform's other fields ([PlatformConfig.toolSets]/
 * [PlatformConfig.tools]/[PlatformConfig.excludedTools]/[PlatformConfig.drivers]/
 * [PlatformConfig.minBuildVersion]), which this request doesn't expose, are always carried over
 * from the existing manifest unchanged for a platform that isn't removed. [SaveTargetPlatformPatch.remove]
 * true drops the platform key entirely, regardless of any other field set alongside it — the
 * explicit signal a caller must send to delete a platform the manifest already has; merely
 * omitting the key from [platforms] means "leave alone," not "remove."
 */
@Serializable
data class SaveTargetConfigRequest(
  val trailmapId: String,
  val displayName: String,
  val icon: String? = null,
  val platforms: Map<String, SaveTargetPlatformPatch> = emptyMap(),
  val createIfMissing: Boolean = false,
) : RpcRequest<SaveTargetConfigResponse>

@Serializable
data class SaveTargetPlatformPatch(
  val appIds: List<String>? = null,
  val baseUrl: String? = null,
  val icon: String? = null,
  val remove: Boolean = false,
)

@Serializable
data class SaveTargetConfigResponse(
  val ok: Boolean,
  val error: String? = null,
  // True only when this save bootstrapped a brand-new trailmap (createIfMissing path) — the UI
  // uses it to raise the "restart to load app targets" banner only for genuine creations.
  val created: Boolean = false,
  // Non-fatal problem alongside ok=true — e.g. the trailmap was created but the workspace
  // `targets:` list couldn't be updated, so the user must register the id by hand.
  val warning: String? = null,
  // True when the newly-created target was registered into the running daemon's live target set,
  // so it's immediately selectable/runnable without a restart. When true the UI skips the
  // "restart to load app targets" banner (raised by [created]) and just refetches the target list.
  val registeredLive: Boolean = false,
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

// ─── Blaze authoring: propose + bundles ────────────────────────────────────────
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

// A trail bundle: a library folder holding a `blaze.yaml` spec plus zero or more recorded
// `<platform>.trail.yaml` variants. [id] is "<rootIdx>/<relPath>"; [home] is the folder's
// path relative to its root.

/** `POST /api/folder/create` - a new bundle folder born directly at [destination] in the library. */
@Serializable
data class CreateBundleRequest(val destination: String, val yaml: String)

@Serializable
data class CreateBundleResponse(val success: Boolean, val id: String? = null, val error: String? = null)

@Serializable
data class BundleVariant(val name: String, val platform: String? = null)

@Serializable
data class BundleDetailResponse(
  val id: String,
  val name: String,
  val objective: String? = null,
  val target: String? = null,
  val platform: String? = null,
  val context: String? = null,
  val home: String,
  val blazeYaml: String,
  val steps: List<TrailStepEntry> = emptyList(),
  val variants: List<BundleVariant> = emptyList(),
)

@Serializable
data class BundleIdRequest(val id: String)

@Serializable
data class RecordBundleRequest(
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
data class RecordBundleResponse(val sessionIds: List<String> = emptyList(), val error: String? = null)

@Serializable
data class UpdateBundleFileRequest(val id: String, val name: String, val yaml: String)
