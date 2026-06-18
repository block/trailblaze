// AUTO-GENERATED — do not edit by hand.
//
// TypeScript bindings for the Trail Runner web UI, derived from the Kotlin @Serializable
// DTOs the daemon's HTTP API exchanges as JSON. Kotlin is canonical; this is the derived
// artifact.
//
// Regenerate with the `generateDtoTs` Gradle task; CI's `verifyDtoTs` byte-diffs this file
// against a fresh generation and fails the build on drift, so hand edits are reverted on
// the next CI run.

export interface AddTrailRootRequest {
  path: string;
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

export interface CreateTrailDirRequest {
  path: string;
}

export interface CreateTrailRequest {
  path: string;
  yaml: string;
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

export interface IntegrationActionDto {
  id: string;
  label: string;
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
  name: string;
}

export interface RebuildDaemonResponse {
  ok: boolean;
  error?: string | null;
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
  trailId?: string | null;
}

export interface RunResponse {
  success: boolean;
  sessionId?: string | null;
  error?: string | null;
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
}

export interface SessionsResponse {
  sessions: SessionSummary[];
}

export interface SettingsDto {
  themeMode: string;
  alwaysOnTop: boolean;
  captureLogcat: boolean;
  captureIosLogs: boolean;
  captureNetworkTraffic: boolean;
  captureAnalytics: boolean;
  showWebBrowser: boolean;
  showTrailsTab: boolean;
  showDevicesTab: boolean;
  showWaypointsTab: boolean;
  trailsDirectory?: string | null;
  logsDirectory?: string | null;
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

export interface ToolCatalogEntry {
  id: string;
  flavor: string;
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
  flavor?: string | null;
}

export interface TrailmapEntry {
  id: string;
  displayName?: string | null;
  manifestPath?: string | null;
  tools?: TrailmapComponent[];
  toolsets?: TrailmapComponent[];
  waypoints?: TrailmapComponent[];
  shortcuts?: TrailmapComponent[];
  trailheads?: TrailmapComponent[];
  systemPrompts?: TrailmapComponent[];
}

export interface TrailmapsResponse {
  trailmaps: TrailmapEntry[];
}

export interface ValidateTrailResponse {
  valid: boolean;
  errors?: ValidationErrorDto[];
}

export interface ValidationErrorDto {
  message: string;
  line?: number | null;
}
