// AUTO-GENERATED — do not edit by hand.
//
// TypeScript bindings for the Trail Runner web UI, derived from the Kotlin @Serializable
// DTOs the daemon's HTTP API exchanges as JSON, plus a typed client for the Trail Runner
// /rpc/<Name> endpoints. Kotlin is canonical; this is the derived artifact.
//
// Regenerate with the `generateDtoTs` Gradle task; CI's `verifyDtoTs` byte-diffs this file
// against a fresh generation and fails the build on drift, so hand edits are reverted on
// the next CI run.
import { rpcCall, type RpcResult, type RpcCallOptions } from "../rpc/client.js";

export interface AddTrailRootRequest {
  path: string;
}

export interface AgentOptionDto {
  id: string;
  display: string;
}

export interface AnalyticsEventDto {
  id: string;
  name: string;
  timeMs: number;
  source?: string | null;
  properties?: Record<string, string>;
}

export interface AnalyticsResponse {
  available: boolean;
  events: AnalyticsEventDto[];
}

export interface CancelSessionRequest {
  id: string;
}

export interface CancelSessionResponse {
  ok: boolean;
}

export interface CreateTrailDirRequest {
  path: string;
}

export interface CreateTrailRequest {
  path: string;
  yaml: string;
}

export interface DeleteSessionRequest {
  id: string;
}

export interface DeleteSessionResponse {
  deleted: string;
}

export interface DeviceAppDto {
  id: string;
  displayName: string;
  appId: string;
  versionName?: string | null;
  versionCode?: string | null;
  buildNumber?: string | null;
  minOsVersion?: number | null;
}

export interface DeviceAppsResponse {
  targets: DeviceAppDto[];
  currentTargetAppId?: string | null;
}

export interface EditedTrailsResponse {
  paths: string[];
}

export interface FavoriteRequest {
  id: string;
}

export interface FavoritesResponse {
  ids: string[];
}

export interface GetDeviceAppsRequest {
  platform: string;
  id: string;
}

export interface GetEditedTrailsRequest {
}

export interface GetFavoritesRequest {
}

export interface GetIntegrationsRequest {
}

export interface GetRunToolsRequest {
  target: string;
  driver?: string | null;
  platform?: string | null;
}

export interface GetSessionAnalyticsRequest {
  sessionId: string;
}

export interface GetSessionFilesRequest {
  sessionId: string;
}

export interface GetSessionsRequest {
}

export interface GetSettingsRequest {
}

export interface GetToolSourceRequest {
  className?: string | null;
  path?: string | null;
}

export interface GetToolUsageCountsRequest {
}

export interface GetToolUsagesRequest {
  toolId: string;
}

export interface GetToolsRequest {
}

export interface GetTrailDetailRequest {
  id: string;
}

export interface GetTrailRootsRequest {
}

export interface GetTrailmapsRequest {
}

export interface GetTrailsRequest {
}

export interface IntegrationActionDto {
  id: string;
  label: string;
}

export interface IntegrationActionRequest {
  id: string;
  action: string;
}

export interface IntegrationDto {
  id: string;
  name: string;
  connected: boolean;
  detail: string;
  action?: IntegrationActionDto | null;
}

export interface IntegrationsResponse {
  integrations: IntegrationDto[];
}

export interface LlmModelOptionDto {
  id: string;
  provider: string;
}

export interface LlmProviderOptionDto {
  id: string;
  display: string;
}

export interface LlmSettingsDto {
  provider: string;
  model: string;
  availableProviders?: LlmProviderOptionDto[];
  availableModels?: LlmModelOptionDto[];
  agent?: string;
  availableAgents?: AgentOptionDto[];
}

export interface NewComponentRequest {
  trailmap: string;
  kind: string;
  name: string;
}

export interface NewComponentResponse {
  ok: boolean;
  relPath?: string | null;
  savedPath?: string | null;
  error?: string | null;
}

export interface OkResponse {
  ok: boolean;
  error?: string | null;
}

export interface OpenSessionFileRequest {
  id: string;
  name: string;
}

export interface RebuildDaemonRequest {
}

export interface RebuildDaemonResponse {
  ok: boolean;
  error?: string | null;
}

export interface RemoveTrailRootRequest {
  path: string;
}

export interface RevealSessionRequest {
  id: string;
}

export interface RevealTrailRequest {
  id: string;
}

export interface RevealTrailsRootRequest {
}

export interface RunRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
  yaml: string;
  selfHeal?: boolean | null;
  useRecordedSteps?: boolean | null;
  maxLlmCalls?: number | null;
  agent?: string | null;
  memory?: Record<string, string>;
  secrets?: Record<string, string>;
  captureVideo?: boolean | null;
  captureLogcat?: boolean | null;
  captureNetworkTraffic?: boolean | null;
  captureIosLogs?: boolean | null;
  captureAnalytics?: boolean | null;
  captureEvents?: boolean | null;
  trailId?: string | null;
  draftId?: string | null;
  variant?: string | null;
}

export interface RunResponse {
  success: boolean;
  sessionId?: string | null;
  error?: string | null;
}

export interface RunToolSetDto {
  id: string;
  description: string;
  alwaysEnabled: boolean;
  tools: string[];
}

export interface RunToolsResponse {
  target: string;
  driver: string;
  resolved: boolean;
  toolsets: RunToolSetDto[];
}

export interface SaveTrailRequest {
  yaml: string;
  filename?: string | null;
}

export interface SaveTrailResponse {
  success: boolean;
  savedPath?: string | null;
  error?: string | null;
}

export interface SessionFileDto {
  name: string;
  size: number;
}

export interface SessionFilesResponse {
  files: SessionFileDto[];
}

export interface SessionSummary {
  id: string;
  title: string;
  status: string;
  durationMs: number;
  timestampMs: number;
  platform?: string | null;
  device?: string | null;
  target?: string | null;
  hasRecordedSteps?: boolean;
  error?: string | null;
  trailId?: string | null;
  imported?: boolean;
}

export interface SessionsResponse {
  sessions: SessionSummary[];
}

export interface SetFavoriteRequest {
  id: string;
  favorite: boolean;
}

export interface SettingsDto {
  themeMode: string;
  alwaysOnTop: boolean;
  captureLogcat: boolean;
  captureIosLogs: boolean;
  captureNetworkTraffic: boolean;
  captureAnalytics: boolean;
  showWebBrowser: boolean;
  serverPort: number;
  serverHttpsPort: number;
  showTrailsTab: boolean;
  showDevicesTab: boolean;
  showWaypointsTab: boolean;
  preferHostAgent?: boolean;
  trailsDirectory?: string | null;
  logsDirectory?: string | null;
  appDataDirectory?: string | null;
  llm: LlmSettingsDto;
  selfHealEnabled: boolean;
  requireSteps: boolean;
  saveAnnotatedScreenshots: boolean;
  maxLlmCalls?: number | null;
  screenshotImageFormat?: string | null;
  screenshotMaxLongerSide?: number | null;
  screenshotMaxShorterSide?: number | null;
  screenshotCompressionQuality?: number | null;
}

export interface SettingsPatchRequest {
  themeMode?: string | null;
  alwaysOnTop?: boolean | null;
  captureLogcat?: boolean | null;
  captureIosLogs?: boolean | null;
  captureNetworkTraffic?: boolean | null;
  captureAnalytics?: boolean | null;
  showWebBrowser?: boolean | null;
  serverPort?: number | null;
  serverHttpsPort?: number | null;
  showTrailsTab?: boolean | null;
  showDevicesTab?: boolean | null;
  showWaypointsTab?: boolean | null;
  preferHostAgent?: boolean | null;
  trailsDirectory?: string | null;
  logsDirectory?: string | null;
  appDataDirectory?: string | null;
  selfHealEnabled?: boolean | null;
  requireSteps?: boolean | null;
  saveAnnotatedScreenshots?: boolean | null;
  maxLlmCalls?: number | null;
  llmProvider?: string | null;
  llmModel?: string | null;
  agent?: string | null;
  screenshotImageFormat?: string | null;
  screenshotMaxLongerSide?: number | null;
  screenshotMaxShorterSide?: number | null;
  screenshotCompressionQuality?: number | null;
}

export interface ToolCatalogEntry {
  id: string;
  flavor: ToolFlavor;
  trailmap: string;
  sourcePath: string;
  description?: string | null;
  className?: string | null;
  parameters?: ToolParamDto[];
  source?: string | null;
  llmDescription?: string | null;
}

export interface ToolCatalogResponse {
  tools: ToolCatalogEntry[];
}

export type ToolFlavor = "kotlin" | "yaml" | "scripted";

export interface ToolParamDto {
  name: string;
  type: string;
  required?: boolean;
  description?: string | null;
}

export interface ToolRevealRequest {
  class?: string | null;
  path?: string | null;
}

export interface ToolRunRequest {
  yaml: string;
  trailblazeDeviceId?: TrailblazeDeviceId | null;
}

export interface ToolRunResponse {
  success: boolean;
  result?: string | null;
  error?: string | null;
  durationMs?: number;
}

export interface ToolSourceResponse {
  source?: string | null;
}

export interface ToolSourceSaveRequest {
  className?: string | null;
  path?: string | null;
  source: string;
}

export interface ToolUsageCountsResponse {
  counts: Record<string, number>;
}

export interface TrailDetailResponse {
  id: string;
  path: string;
  title: string;
  yaml: string;
  steps: TrailStepEntry[];
}

export interface TrailIndexEntry {
  id: string;
  path: string;
  title: string;
  target?: string | null;
  platform?: string | null;
  driver?: string | null;
  priority?: string | null;
  tags?: string[];
  folder: string;
  rootIdx?: number;
  kind?: string;
}

export interface TrailIndexResponse {
  trails: TrailIndexEntry[];
  folders?: string[];
}

export interface TrailOpenRequest {
  id: string;
}

export interface TrailRootsResponse {
  primary: string;
  extras: string[];
  primaryBranch?: string | null;
  primaryIsWorktree?: boolean;
}

export interface TrailStepEntry {
  kind: string;
  text: string;
  tools?: string[];
}

export interface TrailblazeDeviceId {
  instanceId: string;
  trailblazeDevicePlatform: TrailblazeDevicePlatform;
}

export type TrailblazeDevicePlatform = "ANDROID" | "IOS" | "WEB" | "DESKTOP";

export interface TrailmapComponent {
  name: string;
  relPath: string;
  flavor?: ToolFlavor | null;
}

export interface TrailmapEntry {
  id: string;
  displayName?: string | null;
  manifestPath?: string | null;
  tools?: TrailmapComponent[];
  trailheads?: TrailmapComponent[];
  systemPrompts?: TrailmapComponent[];
}

export interface TrailmapsResponse {
  trailmaps: TrailmapEntry[];
}

export interface UpdateTrailRequest {
  id: string;
  yaml: string;
}

export interface ValidateTrailRequest {
  yaml: string;
}

export interface ValidateTrailResponse {
  valid: boolean;
  errors?: ValidationErrorDto[];
}

export interface ValidationErrorDto {
  message: string;
  line?: number | null;
}

/**
 * Typed client for the Trail Runner /rpc/<Name> endpoints — one method per RpcRequest<T>.
 *
 *   const rpc = createTrailRunnerRpcClient({ baseUrl });
 *   const r = await rpc.getEditedTrails();   // RpcResult<EditedTrailsResponse>
 */
export function createTrailRunnerRpcClient(options: RpcCallOptions = {}) {
  return {
    addTrailRoot: (request: AddTrailRootRequest): Promise<RpcResult<TrailRootsResponse>> =>
      rpcCall<AddTrailRootRequest, TrailRootsResponse>("AddTrailRootRequest", request, options),
    cancelSession: (request: CancelSessionRequest): Promise<RpcResult<CancelSessionResponse>> =>
      rpcCall<CancelSessionRequest, CancelSessionResponse>("CancelSessionRequest", request, options),
    createTrail: (request: CreateTrailRequest): Promise<RpcResult<SaveTrailResponse>> =>
      rpcCall<CreateTrailRequest, SaveTrailResponse>("CreateTrailRequest", request, options),
    createTrailDir: (request: CreateTrailDirRequest): Promise<RpcResult<SaveTrailResponse>> =>
      rpcCall<CreateTrailDirRequest, SaveTrailResponse>("CreateTrailDirRequest", request, options),
    deleteSession: (request: DeleteSessionRequest): Promise<RpcResult<DeleteSessionResponse>> =>
      rpcCall<DeleteSessionRequest, DeleteSessionResponse>("DeleteSessionRequest", request, options),
    getDeviceApps: (request: GetDeviceAppsRequest): Promise<RpcResult<DeviceAppsResponse>> =>
      rpcCall<GetDeviceAppsRequest, DeviceAppsResponse>("GetDeviceAppsRequest", request, options),
    getEditedTrails: (request: GetEditedTrailsRequest = {}): Promise<RpcResult<EditedTrailsResponse>> =>
      rpcCall<GetEditedTrailsRequest, EditedTrailsResponse>("GetEditedTrailsRequest", request, options),
    getFavorites: (request: GetFavoritesRequest = {}): Promise<RpcResult<FavoritesResponse>> =>
      rpcCall<GetFavoritesRequest, FavoritesResponse>("GetFavoritesRequest", request, options),
    getIntegrations: (request: GetIntegrationsRequest = {}): Promise<RpcResult<IntegrationsResponse>> =>
      rpcCall<GetIntegrationsRequest, IntegrationsResponse>("GetIntegrationsRequest", request, options),
    getRunTools: (request: GetRunToolsRequest): Promise<RpcResult<RunToolsResponse>> =>
      rpcCall<GetRunToolsRequest, RunToolsResponse>("GetRunToolsRequest", request, options),
    getSessionAnalytics: (request: GetSessionAnalyticsRequest): Promise<RpcResult<AnalyticsResponse>> =>
      rpcCall<GetSessionAnalyticsRequest, AnalyticsResponse>("GetSessionAnalyticsRequest", request, options),
    getSessionFiles: (request: GetSessionFilesRequest): Promise<RpcResult<SessionFilesResponse>> =>
      rpcCall<GetSessionFilesRequest, SessionFilesResponse>("GetSessionFilesRequest", request, options),
    getSessions: (request: GetSessionsRequest = {}): Promise<RpcResult<SessionsResponse>> =>
      rpcCall<GetSessionsRequest, SessionsResponse>("GetSessionsRequest", request, options),
    getSettings: (request: GetSettingsRequest = {}): Promise<RpcResult<SettingsDto>> =>
      rpcCall<GetSettingsRequest, SettingsDto>("GetSettingsRequest", request, options),
    getToolSource: (request: GetToolSourceRequest): Promise<RpcResult<ToolSourceResponse>> =>
      rpcCall<GetToolSourceRequest, ToolSourceResponse>("GetToolSourceRequest", request, options),
    getToolUsageCounts: (request: GetToolUsageCountsRequest = {}): Promise<RpcResult<ToolUsageCountsResponse>> =>
      rpcCall<GetToolUsageCountsRequest, ToolUsageCountsResponse>("GetToolUsageCountsRequest", request, options),
    getToolUsages: (request: GetToolUsagesRequest): Promise<RpcResult<TrailIndexResponse>> =>
      rpcCall<GetToolUsagesRequest, TrailIndexResponse>("GetToolUsagesRequest", request, options),
    getTools: (request: GetToolsRequest = {}): Promise<RpcResult<ToolCatalogResponse>> =>
      rpcCall<GetToolsRequest, ToolCatalogResponse>("GetToolsRequest", request, options),
    getTrailDetail: (request: GetTrailDetailRequest): Promise<RpcResult<TrailDetailResponse>> =>
      rpcCall<GetTrailDetailRequest, TrailDetailResponse>("GetTrailDetailRequest", request, options),
    getTrailRoots: (request: GetTrailRootsRequest = {}): Promise<RpcResult<TrailRootsResponse>> =>
      rpcCall<GetTrailRootsRequest, TrailRootsResponse>("GetTrailRootsRequest", request, options),
    getTrailmaps: (request: GetTrailmapsRequest = {}): Promise<RpcResult<TrailmapsResponse>> =>
      rpcCall<GetTrailmapsRequest, TrailmapsResponse>("GetTrailmapsRequest", request, options),
    getTrails: (request: GetTrailsRequest = {}): Promise<RpcResult<TrailIndexResponse>> =>
      rpcCall<GetTrailsRequest, TrailIndexResponse>("GetTrailsRequest", request, options),
    integrationAction: (request: IntegrationActionRequest): Promise<RpcResult<OkResponse>> =>
      rpcCall<IntegrationActionRequest, OkResponse>("IntegrationActionRequest", request, options),
    newComponent: (request: NewComponentRequest): Promise<RpcResult<NewComponentResponse>> =>
      rpcCall<NewComponentRequest, NewComponentResponse>("NewComponentRequest", request, options),
    openSessionFile: (request: OpenSessionFileRequest): Promise<RpcResult<OkResponse>> =>
      rpcCall<OpenSessionFileRequest, OkResponse>("OpenSessionFileRequest", request, options),
    rebuildDaemon: (request: RebuildDaemonRequest = {}): Promise<RpcResult<RebuildDaemonResponse>> =>
      rpcCall<RebuildDaemonRequest, RebuildDaemonResponse>("RebuildDaemonRequest", request, options),
    removeTrailRoot: (request: RemoveTrailRootRequest): Promise<RpcResult<TrailRootsResponse>> =>
      rpcCall<RemoveTrailRootRequest, TrailRootsResponse>("RemoveTrailRootRequest", request, options),
    revealSession: (request: RevealSessionRequest): Promise<RpcResult<OkResponse>> =>
      rpcCall<RevealSessionRequest, OkResponse>("RevealSessionRequest", request, options),
    revealTrail: (request: RevealTrailRequest): Promise<RpcResult<OkResponse>> =>
      rpcCall<RevealTrailRequest, OkResponse>("RevealTrailRequest", request, options),
    revealTrailsRoot: (request: RevealTrailsRootRequest = {}): Promise<RpcResult<OkResponse>> =>
      rpcCall<RevealTrailsRootRequest, OkResponse>("RevealTrailsRootRequest", request, options),
    run: (request: RunRequest): Promise<RpcResult<RunResponse>> =>
      rpcCall<RunRequest, RunResponse>("RunRequest", request, options),
    setFavorite: (request: SetFavoriteRequest): Promise<RpcResult<FavoritesResponse>> =>
      rpcCall<SetFavoriteRequest, FavoritesResponse>("SetFavoriteRequest", request, options),
    settingsPatch: (request: SettingsPatchRequest): Promise<RpcResult<SettingsDto>> =>
      rpcCall<SettingsPatchRequest, SettingsDto>("SettingsPatchRequest", request, options),
    toolReveal: (request: ToolRevealRequest): Promise<RpcResult<OkResponse>> =>
      rpcCall<ToolRevealRequest, OkResponse>("ToolRevealRequest", request, options),
    toolRun: (request: ToolRunRequest): Promise<RpcResult<ToolRunResponse>> =>
      rpcCall<ToolRunRequest, ToolRunResponse>("ToolRunRequest", request, options),
    toolSourceSave: (request: ToolSourceSaveRequest): Promise<RpcResult<SaveTrailResponse>> =>
      rpcCall<ToolSourceSaveRequest, SaveTrailResponse>("ToolSourceSaveRequest", request, options),
    trailOpen: (request: TrailOpenRequest): Promise<RpcResult<OkResponse>> =>
      rpcCall<TrailOpenRequest, OkResponse>("TrailOpenRequest", request, options),
    updateTrail: (request: UpdateTrailRequest): Promise<RpcResult<SaveTrailResponse>> =>
      rpcCall<UpdateTrailRequest, SaveTrailResponse>("UpdateTrailRequest", request, options),
    validateTrail: (request: ValidateTrailRequest): Promise<RpcResult<ValidateTrailResponse>> =>
      rpcCall<ValidateTrailRequest, ValidateTrailResponse>("ValidateTrailRequest", request, options),
  };
}
