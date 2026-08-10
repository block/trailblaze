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

export interface AccessibilityActionLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.AccessibilityActionLog";
  actionJsonObj: Record<string, unknown>;
  actionDescription: string;
  traceId?: string | null;
  successful: boolean;
  trailblazeToolResult: TrailblazeToolResult;
  session: string;
  timestamp: string;
  durationMs: number;
}

export interface Action {
  name: string;
  args: Record<string, unknown>;
}

export interface AddMedia {
  class: "xyz.block.trailblaze.api.AgentDriverAction.AddMedia";
  mediaFiles: string[];
  type?: AgentActionType;
}

export interface AddTrailRootRequest {
  path: string;
}

export type AgentActionType = "AIRPLANE_MODE" | "ENTER_TEXT" | "LAUNCH_APP" | "STOP_APP" | "SWIPE" | "TAP_POINT" | "LONG_PRESS_POINT" | "GRANT_PERMISSIONS" | "CLEAR_APP_STATE" | "KILL_APP" | "BACK_PRESS" | "ADD_MEDIA" | "ASSERT_CONDITION" | "WEB_ACTION" | "PRESS_HOME" | "PRESS_KEY" | "HIDE_KEYBOARD" | "ERASE_TEXT" | "SCROLL" | "WAIT_FOR_SETTLE";

export type AgentDriverAction = AddMedia | AirplaneMode | AssertCondition | BackPress | ClearAppState | EnterText | EraseText | GrantPermissions | HideKeyboard | KillApp | LaunchApp | LongPressPoint | OtherAction | PressHome | Scroll | StopApp | Swipe | TapPoint | WaitForSettle;

export type AgentImplementation = "TRAILBLAZE_RUNNER" | "MULTI_AGENT_V3" | "KOOG_STRATEGY_GRAPH";

export interface AgentOptionDto {
  id: string;
  display: string;
}

export type AgentTaskStatus = AgentTaskStatusFailureMaxCallsLimitReached | InProgress | McpScreenAnalysis | ObjectiveComplete | ObjectiveFailed;

export interface AgentTaskStatusData {
  taskId: string;
  prompt: string;
  callCount: number;
  taskStartTime: string;
  totalDurationMs: number;
}

export interface AgentTaskStatusFailureMaxCallsLimitReached {
  class: "xyz.block.trailblaze.agent.model.AgentTaskStatus.Failure.MaxCallsLimitReached";
  statusData: AgentTaskStatusData;
}

export type AgentTier = "INNER" | "OUTER";

export type AgentToolTransport = "MCP_IN_PROCESS" | "MCP_OVER_HTTP";

export interface AirplaneMode {
  class: "xyz.block.trailblaze.api.AgentDriverAction.AirplaneMode";
  enable: boolean;
  type?: AgentActionType;
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

export interface AssertCondition {
  class: "xyz.block.trailblaze.api.AgentDriverAction.AssertCondition";
  conditionDescription: string;
  x: number;
  y: number;
  isVisible: boolean;
  textToDisplay?: string | null;
  succeeded?: boolean;
  type?: AgentActionType;
}

export interface Assistant {
  parts: ResponsePart[];
  metaInfo: ResponseMetaInfo;
  finishReason?: string | null;
  rawResponse?: Record<string, unknown> | null;
  id?: string | null;
  role?: Role;
}

export interface Attachment {
  class: "ai.koog.prompt.message.MessagePart.Attachment";
  source: AttachmentSource;
  cacheControl?: PolymorphicCacheControl | null;
}

export type AttachmentContent = Base64 | Bytes | PlainText | URL;

export type AttachmentSource = AttachmentSourceAudio | AttachmentSourceImage | AttachmentSourceVideo | File;

export interface AttachmentSourceAudio {
  class: "ai.koog.prompt.message.AttachmentSource.Audio";
  content: AttachmentContent;
  format: string;
  mimeType?: string;
  fileName?: string | null;
}

export interface AttachmentSourceImage {
  class: "ai.koog.prompt.message.AttachmentSource.Image";
  content: AttachmentContent;
  format: string;
  mimeType?: string;
  fileName?: string | null;
}

export interface AttachmentSourceVideo {
  class: "ai.koog.prompt.message.AttachmentSource.Video";
  content: AttachmentContent;
  format: string;
  mimeType?: string;
  fileName?: string | null;
}

export interface BackPress {
  class: "xyz.block.trailblaze.api.AgentDriverAction.BackPress";
}

export interface Base64 {
  class: "ai.koog.prompt.message.AttachmentContent.Binary.Base64";
  base64: string;
}

export interface Basic {
  class: "ai.koog.prompt.llm.LLMCapability.Schema.JSON.Basic";
}

export interface Bounds {
  left: number;
  top: number;
  right: number;
  bottom: number;
}

export interface Bytes {
  class: "ai.koog.prompt.message.AttachmentContent.Binary.Bytes";
  base64: string;
}

export interface Call {
  class: "ai.koog.prompt.message.MessagePart.Tool.Call";
  id?: string | null;
  tool: string;
  args: string;
}

export interface CancelSessionRequest {
  id: string;
}

export interface CancelSessionResponse {
  ok: boolean;
  reason?: string | null;
}

export interface Cancelled {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Ended.Cancelled";
  durationMs: number;
  cancellationMessage?: string | null;
}

export interface CaptureCoverage {
  contentNodes: number;
  zeroBoundsContentNodes: number;
  horizontalCoverage: number;
  verticalCoverage: number;
  looksTruncated: boolean;
  reason: string;
}

export interface CategoryBreakdown {
  tokens: number;
  count: number;
}

export interface ClearAppState {
  class: "xyz.block.trailblaze.api.AgentDriverAction.ClearAppState";
  appId: string;
  type?: AgentActionType;
}

export interface CollectionInfo {
  rowCount: number;
  columnCount: number;
  isHierarchical: boolean;
}

export interface CollectionItemInfo {
  rowIndex: number;
  rowSpan: number;
  columnIndex: number;
  columnSpan: number;
  isHeading: boolean;
}

export interface CompanionDirectiveDto {
  seq: number;
  payload?: string | null;
}

export interface CompanionRequestDto {
  requestId: string;
  kind: string;
  payload?: string | null;
  status: string;
  note?: string | null;
}

export interface CompanionStateDto {
  agentLabel?: string | null;
  folder?: string | null;
  directives?: Record<string, CompanionDirectiveDto>;
  requests?: Record<string, CompanionRequestDto>;
}

export interface Completion {
  class: "ai.koog.prompt.llm.LLMCapability.Completion";
}

export interface Completions {
  class: "ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Completions";
}

export interface CreateTrailDirRequest {
  path: string;
}

export interface CreateTrailRequest {
  path: string;
  yaml: string;
}

export interface DelegatingTrailblazeToolLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.DelegatingTrailblazeToolLog";
  toolName: string;
  trailblazeTool: OtherTrailblazeTool;
  session: string;
  timestamp: string;
  traceId?: string | null;
  executableTools: OtherTrailblazeTool[];
}

export interface DeleteSessionRequest {
  id: string;
}

export interface DeleteSessionResponse {
  deleted: string;
}

export interface DemoPlatformDto {
  key: string;
  done: boolean;
}

export interface DemoStateDto {
  phase: string;
  bundleDir?: string | null;
  objective?: string | null;
  generationRunId?: string | null;
  platform?: string | null;
  platforms?: DemoPlatformDto[];
  draftDir?: string | null;
  trailId?: string | null;
  trailFiles?: string | null;
  trailVerified?: boolean | null;
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

export interface DirectionStep {
  class: "xyz.block.trailblaze.yaml.DirectionStep";
  step: string;
  recordable?: boolean;
  recording?: ToolRecording | null;
  maxRetries?: number | null;
  isTrailhead?: boolean;
  prompt?: string;
}

export interface Document {
  class: "ai.koog.prompt.llm.LLMCapability.Document";
}

export type DriverNodeDetail = androidAccessibility | androidMaestro | compose | iosAxe | iosMaestro | web;

export interface EditedTrailsResponse {
  paths: string[];
}

export interface Embed {
  class: "ai.koog.prompt.llm.LLMCapability.Embed";
}

export interface EmptyToolCall {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.EmptyToolCall";
}

export interface EnterText {
  class: "xyz.block.trailblaze.api.AgentDriverAction.EnterText";
  text: string;
  type?: AgentActionType;
}

export interface EraseText {
  class: "xyz.block.trailblaze.api.AgentDriverAction.EraseText";
  characters: number;
  type?: AgentActionType;
}

export interface ExceptionThrown {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.ExceptionThrown";
  errorMessage: string;
  command?: unknown | null;
  stackTrace?: string | null;
}

export interface ExternalAgentEventDto {
  id: string;
  runId: string;
  seq: number;
  timeMs: number;
  agentType: ExternalAgentType;
  kind: ExternalAgentEventKind;
  status?: ExternalAgentSessionStatus | null;
  title?: string | null;
  text?: string | null;
  toolName?: string | null;
  toolCallId?: string | null;
  input?: string | null;
  output?: string | null;
  uiCommand?: TrailRunnerUiCommandDto | null;
  usage?: string | null;
  raw?: string | null;
}

export type ExternalAgentEventKind = "lifecycle" | "user_message" | "assistant_message" | "reasoning" | "tool_call" | "tool_result" | "ui_command" | "stdout" | "stderr" | "final_result" | "usage" | "error" | "human_action" | "permission_request" | "permission_decision";

export interface ExternalAgentEventsResponse {
  events: ExternalAgentEventDto[];
}

export interface ExternalAgentModelOptionDto {
  id: string;
  display: string;
}

export interface ExternalAgentOptionDto {
  id: ExternalAgentType;
  display: string;
  executable: string;
  available: boolean;
  detail?: string | null;
  installHint?: string | null;
  authHint?: string | null;
  modelsHint?: string | null;
  docsUrl?: string | null;
  models?: ExternalAgentModelOptionDto[];
}

export interface ExternalAgentPermissionRequestDto {
  id: string;
  toolName: string;
  inputJson?: string | null;
  requestedAtMs: number;
}

export interface ExternalAgentReplyRequest {
  prompt: string;
}

export interface ExternalAgentRunDto {
  id: string;
  agentType: ExternalAgentType;
  title: string;
  prompt: string;
  cwd: string;
  model?: string | null;
  status: ExternalAgentSessionStatus;
  startedAtMs: number;
  endedAtMs?: number | null;
  externalThreadId?: string | null;
  exitCode?: number | null;
  error?: string | null;
  eventCount?: number;
  demo?: DemoStateDto | null;
  demoRunId?: string | null;
  companion?: CompanionStateDto | null;
  pendingPermissions?: ExternalAgentPermissionRequestDto[];
  autoApprove?: boolean;
}

export interface ExternalAgentRunRequest {
  agentType: ExternalAgentType;
  prompt: string;
  title?: string | null;
  model?: string | null;
  cwd?: string | null;
  sandbox?: string | null;
  includeUiContract?: boolean;
  promptPreamble?: string | null;
  uiContext?: TrailRunnerUiContextDto | null;
  extraDirs?: string[];
}

export interface ExternalAgentRunsResponse {
  supportedAgents: ExternalAgentOptionDto[];
  runs: ExternalAgentRunDto[];
}

export type ExternalAgentSessionStatus = "running" | "completed" | "failed" | "cancelled";

export interface ExternalAgentStartResponse {
  ok: boolean;
  run?: ExternalAgentRunDto | null;
  error?: string | null;
}

export type ExternalAgentType = "claude" | "codex" | "solo";

export interface Failed {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Ended.Failed";
  durationMs: number;
  exceptionMessage?: string | null;
  exceptionStackTrace?: string | null;
  failureKind?: string | null;
}

export interface FailedWithSelfHeal {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Ended.FailedWithSelfHeal";
  durationMs: number;
  exceptionMessage?: string | null;
  usedSelfHeal?: boolean;
  exceptionStackTrace?: string | null;
  failureKind?: string | null;
}

export interface Failure {
  successfulTools: TrailblazeToolYamlWrapper[];
  failedTool: TrailblazeToolYamlWrapper;
  failureResult: TrailblazeToolResult;
}

export interface FatalError {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.FatalError";
  errorMessage: string;
  stackTraceString?: string | null;
}

export interface FavoriteRequest {
  id: string;
}

export interface FavoritesResponse {
  ids: string[];
}

export interface File {
  class: "ai.koog.prompt.message.AttachmentSource.File";
  content: AttachmentContent;
  format: string;
  mimeType: string;
  fileName?: string | null;
}

export interface GetDeviceAppsRequest {
  platform: string;
  id: string;
}

export interface GetEditedTrailsRequest {
}

export interface GetFavoritesRequest {
}

export interface GetInstalledAppsRequest {
  platform: string;
  id: string;
  includeSystemApps?: boolean;
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

export interface GrantPermissions {
  class: "xyz.block.trailblaze.api.AgentDriverAction.GrantPermissions";
  appId: string;
  permissions: Record<string, string>;
  type?: AgentActionType;
}

export interface HideKeyboard {
  class: "xyz.block.trailblaze.api.AgentDriverAction.HideKeyboard";
}

export type ImageTokenFormula = "anthropic" | "openai_tile" | "google_tile" | "default";

export interface InProgress {
  class: "xyz.block.trailblaze.agent.model.AgentTaskStatus.InProgress";
  statusData: AgentTaskStatusData;
}

export interface InstalledAppDto {
  appId: string;
  label?: string | null;
  version?: string | null;
}

export interface InstalledAppsResponse {
  apps: InstalledAppDto[];
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

export interface InvalidToolCall {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.InvalidToolCall";
  errorMessage: string;
  command: unknown;
}

export interface KillApp {
  class: "xyz.block.trailblaze.api.AgentDriverAction.KillApp";
  appId: string;
  type?: AgentActionType;
}

export type LLMCapability = Basic | Completion | Completions | Document | Embed | LLMCapabilityAudio | LLMCapabilityVisionImage | LLMCapabilityVisionVideo | Moderation | MultipleChoices | PromptCaching | Responses | Speculation | Standard | Temperature | Thinking | ToolChoice | Tools;

export interface LLMCapabilityAudio {
  class: "ai.koog.prompt.llm.LLMCapability.Audio";
}

export interface LLMCapabilityVisionImage {
  class: "ai.koog.prompt.llm.LLMCapability.Vision.Image";
}

export interface LLMCapabilityVisionVideo {
  class: "ai.koog.prompt.llm.LLMCapability.Vision.Video";
}

export interface LaunchApp {
  class: "xyz.block.trailblaze.api.AgentDriverAction.LaunchApp";
  appId: string;
  type?: AgentActionType;
}

export type LlmCallStrategy = "DIRECT" | "MCP_SAMPLING";

export interface LlmInputTokenBreakdown {
  systemPrompt: CategoryBreakdown;
  userPrompt: CategoryBreakdown;
  toolDescriptors: CategoryBreakdown;
  images: CategoryBreakdown;
  assistantMessageCount: number;
  toolMessageCount: number;
}

export interface LlmModelOptionDto {
  id: string;
  provider: string;
}

export interface LlmProviderOptionDto {
  id: string;
  display: string;
}

export interface LlmRequestContext {
  agentImplementation: AgentImplementation;
  llmCallStrategy: LlmCallStrategy;
  agentTier?: AgentTier | null;
}

export interface LlmRequestUsageAndCost {
  trailblazeLlmModel: TrailblazeLlmModel;
  inputTokens: number;
  outputTokens: number;
  cacheReadInputTokens?: number;
  cacheCreationInputTokens?: number;
  promptCost: number;
  completionCost: number;
  totalCost?: number;
  inputTokenBreakdown?: LlmInputTokenBreakdown | null;
}

export interface LlmSessionUsageAndCost {
  llmModel: TrailblazeLlmModel;
  averageDurationMillis: number;
  totalCostInUsDollars: number;
  totalRequestCount: number;
  totalInputTokens: number;
  totalOutputTokens: number;
  averageInputTokens: number;
  averageOutputTokens: number;
  totalCacheReadInputTokens?: number;
  totalCacheCreationInputTokens?: number;
  totalCacheSavings?: number;
  aggregatedInputTokenBreakdown?: LlmInputTokenBreakdown | null;
  requestBreakdowns?: LlmRequestUsageAndCost[];
}

export interface LlmSettingsDto {
  provider: string;
  model: string;
  availableProviders?: LlmProviderOptionDto[];
  availableModels?: LlmModelOptionDto[];
  agent?: string;
  availableAgents?: AgentOptionDto[];
}

export interface LongPressPoint {
  class: "xyz.block.trailblaze.api.AgentDriverAction.LongPressPoint";
  x: number;
  y: number;
  type?: AgentActionType;
}

export interface MaestroCommandLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.MaestroCommandLog";
  maestroCommandJsonObj: Record<string, unknown>;
  traceId?: string | null;
  successful: boolean;
  trailblazeToolResult: TrailblazeToolResult;
  session: string;
  timestamp: string;
  durationMs: number;
}

export interface MaestroDriverLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.MaestroDriverLog";
  viewHierarchy?: ViewHierarchyTreeNode | null;
  trailblazeNodeTree?: TrailblazeNode | null;
  driverMigrationTreeNode?: TrailblazeNode | null;
  screenshotFile?: string | null;
  action: AgentDriverAction;
  captureCoverage?: CaptureCoverage | null;
  durationMs: number;
  session: string;
  timestamp: string;
  deviceHeight: number;
  deviceWidth: number;
  traceId?: string | null;
}

export interface MaestroValidationError {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.MaestroValidationError";
  errorMessage: string;
  commandJsonObject: Record<string, unknown>;
}

export interface McpAgentIterationLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.McpAgentIterationLog";
  iterationNumber: number;
  transportMode: AgentToolTransport;
  toolName?: string | null;
  toolArgs?: Record<string, unknown> | null;
  toolSucceeded?: boolean | null;
  llmCompletion?: string | null;
  responseType: string;
  durationMs: number;
  session: string;
  timestamp: string;
  traceId: string;
}

export interface McpAgentRunLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.McpAgentRunLog";
  objective: string;
  transportMode: AgentToolTransport;
  llmStrategy: LlmCallStrategy;
  iterationCount: number;
  toolCallCount: number;
  successful: boolean;
  resultMessage: string;
  actionsTaken: string[];
  durationMs: number;
  session: string;
  timestamp: string;
  traceId: string;
}

export interface McpAgentToolLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.McpAgentToolLog";
  transportMode: AgentToolTransport;
  toolName: string;
  toolArgs: Record<string, unknown>;
  successful: boolean;
  resultOutput: string;
  durationMs: number;
  session: string;
  timestamp: string;
  traceId?: string | null;
}

export interface McpAskLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.McpAskLog";
  question: string;
  answer?: string | null;
  screenSummary?: string | null;
  errorMessage?: string | null;
  traceId?: string | null;
  durationMs: number;
  session: string;
  timestamp: string;
}

export interface McpSamplingLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.McpSamplingLog";
  llmStrategy: LlmCallStrategy;
  systemPrompt: string;
  userMessage: string;
  completion: string;
  includedScreenshot: boolean;
  usageAndCost?: LlmRequestUsageAndCost | null;
  modelName?: string | null;
  successful: boolean;
  errorMessage?: string | null;
  viewHierarchy?: ViewHierarchyTreeNode | null;
  viewHierarchyFiltered?: ViewHierarchyTreeNode | null;
  deviceWidth?: number;
  deviceHeight?: number;
  durationMs: number;
  session: string;
  timestamp: string;
  traceId: string;
  screenshotFile?: string | null;
}

export interface McpScreenAnalysis {
  class: "xyz.block.trailblaze.agent.model.AgentTaskStatus.McpScreenAnalysis";
  statusData: AgentTaskStatusData;
  recommendedAction?: string | null;
  confidence?: string | null;
}

export interface McpToolCallRequestLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.McpToolCallRequestLog";
  toolName: string;
  toolArgs: Record<string, unknown>;
  mcpSessionId: string;
  traceId: string;
  session: string;
  timestamp: string;
}

export interface McpToolCallResponseLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.McpToolCallResponseLog";
  toolName: string;
  mcpSessionId: string;
  successful: boolean;
  resultSummary: unknown;
  errorMessage?: string | null;
  traceId: string;
  durationMs: number;
  session: string;
  timestamp: string;
}

export interface MissingRequiredArgs {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.MissingRequiredArgs";
  functionName: string;
  functionArgs: Record<string, unknown>;
  requiredArgs: string[];
}

export interface Moderation {
  class: "ai.koog.prompt.llm.LLMCapability.Moderation";
}

export interface MultipleChoices {
  class: "ai.koog.prompt.llm.LLMCapability.MultipleChoices";
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

export interface ObjectiveComplete {
  class: "xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete";
  statusData: AgentTaskStatusData;
  llmExplanation: string;
}

export interface ObjectiveCompleteLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog";
  promptStep: PromptStep;
  objectiveResult: AgentTaskStatus;
  session: string;
  timestamp: string;
}

export interface ObjectiveFailed {
  class: "xyz.block.trailblaze.agent.model.AgentTaskStatus.Failure.ObjectiveFailed";
  statusData: AgentTaskStatusData;
  llmExplanation: string;
}

export interface ObjectiveStartLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveStartLog";
  promptStep: PromptStep;
  session: string;
  timestamp: string;
}

export interface OkResponse {
  ok: boolean;
  error?: string | null;
}

export interface OpenSessionFileRequest {
  id: string;
  name: string;
}

export interface OtherAction {
  class: "xyz.block.trailblaze.api.AgentDriverAction.OtherAction";
  type: AgentActionType;
}

export interface OtherTrailblazeTool {
}

export interface PlainText {
  class: "ai.koog.prompt.message.AttachmentContent.PlainText";
  text: string;
}

export type PolymorphicCacheControl = { class: string } & { [key: string]: unknown };

export interface PressHome {
  class: "xyz.block.trailblaze.api.AgentDriverAction.PressHome";
}

export interface PromptCaching {
  class: "ai.koog.prompt.llm.LLMCapability.PromptCaching";
}

export type PromptStep = DirectionStep | VerificationStep;

export interface RangeInfo {
  type: number;
  min: number;
  max: number;
  current: number;
}

export interface Reasoning {
  class: "ai.koog.prompt.message.MessagePart.Reasoning";
  content: string[];
  summary?: string[] | null;
  encrypted?: string | null;
  id?: string | null;
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

export interface ResponseMetaInfo {
  timestamp: string;
  totalTokensCount?: number | null;
  inputTokensCount?: number | null;
  outputTokensCount?: number | null;
  modelId?: string | null;
  metadata?: Record<string, unknown> | null;
}

export type ResponsePart = Attachment | Call | Reasoning | Text;

export interface Responses {
  class: "ai.koog.prompt.llm.LLMCapability.OpenAIEndpoint.Responses";
}

export interface RevealSessionRequest {
  id: string;
}

export interface RevealTrailRequest {
  id: string;
}

export interface RevealTrailsRootRequest {
}

export type Role = "System" | "User" | "Assistant";

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

export interface SaveTargetConfigRequest {
  trailmapId: string;
  displayName: string;
  icon?: string | null;
  platforms?: Record<string, SaveTargetPlatformPatch>;
  createIfMissing?: boolean;
}

export interface SaveTargetConfigResponse {
  ok: boolean;
  error?: string | null;
  created?: boolean;
  warning?: string | null;
  registeredLive?: boolean;
}

export interface SaveTargetPlatformPatch {
  appIds?: string[] | null;
  baseUrl?: string | null;
  icon?: string | null;
  remove?: boolean;
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

export interface ScreenshotScalingConfig {
  maxDimension1?: number;
  maxDimension2?: number;
  imageFormat?: TrailblazeImageFormat;
  compressionQuality?: number;
}

export interface Scroll {
  class: "xyz.block.trailblaze.api.AgentDriverAction.Scroll";
  forward: boolean;
  type?: AgentActionType;
}

export interface SelfHealInvokedLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.SelfHealInvokedLog";
  promptStep: PromptStep;
  session: string;
  timestamp: string;
  recordingResult: Failure;
}

export interface SessionFileDto {
  name: string;
  size: number;
}

export interface SessionFilesResponse {
  files: SessionFileDto[];
}

export interface SessionInfo {
  sessionId: string;
  latestStatus: SessionStatus;
  timestamp: string;
  durationMs: number;
  trailFilePath?: string | null;
  hasRecordedSteps: boolean;
  trailblazeDeviceId?: TrailblazeDeviceId | null;
  trailblazeDeviceInfo?: TrailblazeDeviceInfo | null;
  targetAppInfo?: TrailblazeTargetAppInfo | null;
  testName?: string | null;
  testClass?: string | null;
  trailConfig?: TrailConfig | null;
  llmUsageSummary?: LlmSessionUsageAndCost | null;
}

export type SessionStatus = Cancelled | Failed | FailedWithSelfHeal | SessionStatusEndedMaxCallsLimitReached | Started | Succeeded | SucceededWithSelfHeal | TimeoutReached | Unknown;

export interface SessionStatusEndedMaxCallsLimitReached {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Ended.MaxCallsLimitReached";
  durationMs: number;
  maxCalls: number;
  objectivePrompt: string;
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
  appId?: string | null;
  appVersionName?: string | null;
  appVersionCode?: string | null;
  appBuildNumber?: string | null;
  hasRecordedSteps?: boolean;
  error?: string | null;
  trailId?: string | null;
  imported?: boolean;
  metadata?: Record<string, string> | null;
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

export interface Speculation {
  class: "ai.koog.prompt.llm.LLMCapability.Speculation";
}

export interface Standard {
  class: "ai.koog.prompt.llm.LLMCapability.Schema.JSON.Standard";
}

export interface Started {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Started";
  trailConfig?: TrailConfig | null;
  trailFilePath?: string | null;
  hasRecordedSteps: boolean;
  testMethodName: string;
  testClassName: string;
  trailblazeDeviceInfo: TrailblazeDeviceInfo;
  trailblazeDeviceId?: TrailblazeDeviceId | null;
  rawYaml?: string | null;
  resolvedInitialMemory?: Record<string, string>;
  sensitiveMemoryKeys?: string[];
  targetAppInfo?: TrailblazeTargetAppInfo | null;
}

export interface StopApp {
  class: "xyz.block.trailblaze.api.AgentDriverAction.StopApp";
  appId: string;
  type?: AgentActionType;
}

export interface Succeeded {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Ended.Succeeded";
  durationMs: number;
}

export interface SucceededWithSelfHeal {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Ended.SucceededWithSelfHeal";
  durationMs: number;
  usedSelfHeal?: boolean;
}

export interface Success {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Success";
  message?: string | null;
  structuredContent?: unknown | null;
}

export interface Swipe {
  class: "xyz.block.trailblaze.api.AgentDriverAction.Swipe";
  direction: string;
  durationMs: number;
  startX?: number | null;
  startY?: number | null;
  endX?: number | null;
  endY?: number | null;
  type?: AgentActionType;
}

export type TapDispatchRoute = "ACTION_CLICK" | "GESTURE" | "GESTURE_AFTER_ACTION_CLICK_MISS";

export interface TapPoint {
  class: "xyz.block.trailblaze.api.AgentDriverAction.TapPoint";
  x: number;
  y: number;
  dispatchRoute?: TapDispatchRoute | null;
  type?: AgentActionType;
}

export interface Temperature {
  class: "ai.koog.prompt.llm.LLMCapability.Temperature";
}

export interface Text {
  class: "ai.koog.prompt.message.MessagePart.Text";
  text: string;
  cacheControl?: PolymorphicCacheControl | null;
}

export interface Thinking {
  class: "ai.koog.prompt.llm.LLMCapability.Thinking";
}

export interface TimeoutReached {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Ended.TimeoutReached";
  durationMs: number;
  message?: string | null;
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

export interface ToolChoice {
  class: "ai.koog.prompt.llm.LLMCapability.ToolChoice";
}

export type ToolFlavor = "kotlin" | "yaml" | "scripted";

export interface ToolParamDto {
  name: string;
  type: string;
  required?: boolean;
  description?: string | null;
  validValues?: string[] | null;
  validValueDescriptions?: string[] | null;
  visibleWhen?: ToolParamVisibilityDto | null;
}

export interface ToolParamVisibilityDto {
  parameterName: string;
  values: string[];
}

export interface ToolRecording {
  tools: unknown[];
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

export interface Tools {
  class: "ai.koog.prompt.llm.LLMCapability.Tools";
}

export interface TrailArgConfig {
  type: string;
  description?: string;
  default?: string;
}

export interface TrailConfig {
  context?: string | null;
  id?: string | null;
  title?: string | null;
  description?: string | null;
  priority?: string | null;
  source?: TrailSource | null;
  metadata?: Record<string, string> | null;
  target?: string | null;
  platform?: string | null;
  driver?: string | null;
  tags?: string[] | null;
  skip?: string | null;
  memory?: Record<string, string> | null;
  args?: Record<string, TrailArgConfig> | null;
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
  format?: string;
  configId?: string | null;
  hasRecordedSteps?: boolean;
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

export interface TrailRunnerUiCommandDto {
  version?: number;
  action: string;
  route?: string | null;
  sessionId?: string | null;
  trailId?: string | null;
  message?: string | null;
  severity?: string | null;
  params?: Record<string, string>;
}

export interface TrailRunnerUiContextDto {
  route?: string | null;
  trailId?: string | null;
  sessionId?: string | null;
  target?: string | null;
  platform?: string | null;
  deviceId?: TrailblazeDeviceId | null;
}

export interface TrailSource {
  type?: TrailSourceType | null;
  reason?: string | null;
}

export type TrailSourceType = "HANDWRITTEN";

export interface TrailStepEntry {
  kind: string;
  text: string;
  tools?: string[];
}

export interface TrailblazeAgentTaskStatusChangeLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeAgentTaskStatusChangeLog";
  agentTaskStatus: AgentTaskStatus;
  durationMs?: number;
  session: string;
  timestamp: string;
}

export interface TrailblazeDeviceId {
  instanceId: string;
  trailblazeDevicePlatform: TrailblazeDevicePlatform;
}

export interface TrailblazeDeviceInfo {
  trailblazeDeviceId: TrailblazeDeviceId;
  trailblazeDriverType: TrailblazeDriverType;
  widthPixels: number;
  heightPixels: number;
  metadata?: Record<string, string>;
  locale?: string | null;
  classifiers?: string[];
  orientation?: TrailblazeDeviceOrientation;
  platform?: TrailblazeDevicePlatform;
}

export type TrailblazeDeviceOrientation = "PORTRAIT" | "LANDSCAPE";

export type TrailblazeDevicePlatform = "ANDROID" | "IOS" | "WEB" | "DESKTOP";

export type TrailblazeDriverType = "ANDROID_ONDEVICE_ACCESSIBILITY" | "ANDROID_ONDEVICE_INSTRUMENTATION" | "IOS_HOST" | "IOS_AXE" | "PLAYWRIGHT_NATIVE" | "PLAYWRIGHT_ELECTRON" | "REVYL_ANDROID" | "REVYL_IOS" | "COMPOSE";

export type TrailblazeImageFormat = "PNG" | "JPEG" | "WEBP";

export interface TrailblazeLlmMessage {
  role: string;
  message?: string | null;
  toolName?: string | null;
}

export interface TrailblazeLlmModel {
  trailblazeLlmProvider: TrailblazeLlmProvider;
  modelId: string;
  inputCostPerOneMillionTokens: number;
  outputCostPerOneMillionTokens: number;
  cachedInputCostPerOneMillionTokens?: number;
  imageTokenFormula?: ImageTokenFormula;
  contextLength: number;
  maxOutputTokens: number;
  capabilityIds: string[];
  defaultTemperature?: number | null;
  screenshotScalingConfig?: ScreenshotScalingConfig;
  capabilities?: LLMCapability[];
}

export interface TrailblazeLlmProvider {
  id: string;
  display: string;
  description?: string | null;
}

export interface TrailblazeLlmRequestLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog";
  agentTaskStatus: AgentTaskStatus;
  viewHierarchy: ViewHierarchyTreeNode;
  viewHierarchyFiltered?: ViewHierarchyTreeNode | null;
  trailblazeNodeTree?: TrailblazeNode | null;
  driverMigrationTreeNode?: TrailblazeNode | null;
  instructions: string;
  trailblazeLlmModel: TrailblazeLlmModel;
  llmMessages: TrailblazeLlmMessage[];
  llmResponse: Assistant[];
  actions: Action[];
  toolOptions: TrailblazeToolDescriptor[];
  llmRequestUsageAndCost?: LlmRequestUsageAndCost | null;
  screenshotFile?: string | null;
  durationMs: number;
  session: string;
  timestamp: string;
  traceId: string;
  deviceHeight: number;
  deviceWidth: number;
  requestContext?: LlmRequestContext | null;
  llmRequestLabel?: string | null;
  screenshotIsAnnotated?: boolean | null;
}

export type TrailblazeLog = AccessibilityActionLog | DelegatingTrailblazeToolLog | MaestroCommandLog | MaestroDriverLog | McpAgentIterationLog | McpAgentRunLog | McpAgentToolLog | McpAskLog | McpSamplingLog | McpToolCallRequestLog | McpToolCallResponseLog | ObjectiveCompleteLog | ObjectiveStartLog | SelfHealInvokedLog | TrailblazeAgentTaskStatusChangeLog | TrailblazeLlmRequestLog | TrailblazeProgressLog | TrailblazeSessionStatusChangeLog | TrailblazeSnapshotLog | TrailblazeToolLog;

export interface TrailblazeNode {
  nodeId?: number;
  ref?: string | null;
  children?: TrailblazeNode[];
  bounds?: Bounds | null;
  driverDetail: DriverNodeDetail;
}

export interface TrailblazeProgressLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeProgressLog";
  eventType: string;
  description: string;
  deviceId?: TrailblazeDeviceId | null;
  success?: boolean | null;
  stepIndex?: number | null;
  totalSteps?: number | null;
  progressPercent?: number | null;
  durationMs?: number;
  eventData?: Record<string, unknown> | null;
  session: string;
  timestamp: string;
}

export interface TrailblazeSessionStatusChangeLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog";
  sessionStatus: SessionStatus;
  session: string;
  timestamp: string;
}

export interface TrailblazeSnapshotLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSnapshotLog";
  displayName?: string | null;
  screenshotFile: string;
  viewHierarchy: ViewHierarchyTreeNode;
  trailblazeNodeTree?: TrailblazeNode | null;
  driverMigrationTreeNode?: TrailblazeNode | null;
  viewHierarchyText?: string | null;
  captureCoverage?: CaptureCoverage | null;
  deviceWidth: number;
  deviceHeight: number;
  session: string;
  timestamp: string;
  traceId?: string | null;
}

export interface TrailblazeTargetAppInfo {
  appId: string;
  versionName?: string | null;
  versionCode?: string | null;
  buildNumber?: string | null;
  metadata?: Record<string, string>;
}

export interface TrailblazeToolDescriptor {
  name: string;
  description?: string | null;
  requiredParameters?: TrailblazeToolParameterDescriptor[];
  optionalParameters?: TrailblazeToolParameterDescriptor[];
  source?: TrailblazeToolSourceDescriptor | null;
  inputSchema?: Record<string, unknown> | null;
}

export interface TrailblazeToolLog {
  class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog";
  trailblazeTool: OtherTrailblazeTool;
  toolName: string;
  successful: boolean;
  traceId?: string | null;
  exceptionMessage?: string | null;
  durationMs: number;
  session: string;
  timestamp: string;
  isRecordable?: boolean;
  isTopLevelToolCall?: boolean;
  isVerification?: boolean;
  dispatchedHostSide?: boolean;
  rawTrailblazeTool?: OtherTrailblazeTool | null;
}

export interface TrailblazeToolParameterDescriptor {
  name: string;
  type: string;
  description?: string | null;
  validValues?: string[] | null;
  visibleWhen?: TrailblazeToolParameterVisibility | null;
}

export interface TrailblazeToolParameterVisibility {
  parameterName: string;
  values: string[];
}

export type TrailblazeToolResult = EmptyToolCall | ExceptionThrown | FatalError | InvalidToolCall | MaestroValidationError | MissingRequiredArgs | Success | UnknownTool | UnknownTrailblazeTool;

export interface TrailblazeToolSourceDescriptor {
  type: TrailblazeToolSourceType;
  identifier?: string | null;
  className?: string | null;
  yamlPath?: string | null;
  scriptPath?: string | null;
}

export type TrailblazeToolSourceType = "KOTLIN" | "YAML" | "TYPESCRIPT" | "JAVASCRIPT" | "DYNAMIC";

export interface TrailblazeToolYamlWrapper {
  name: string;
  trailblazeTool: unknown;
}

export interface TrailmapComponent {
  name: string;
  relPath: string;
  flavor?: ToolFlavor | null;
  platforms?: string[] | null;
}

export interface TrailmapEntry {
  id: string;
  displayName?: string | null;
  manifestPath?: string | null;
  tools?: TrailmapComponent[];
  trailheads?: TrailmapComponent[];
  systemPrompts?: TrailmapComponent[];
  platforms?: string[];
  workspaceListed?: boolean;
}

export interface TrailmapsResponse {
  trailmaps: TrailmapEntry[];
}

export interface URL {
  class: "ai.koog.prompt.message.AttachmentContent.URL";
  url: string;
}

export interface Unknown {
  class: "xyz.block.trailblaze.logs.model.SessionStatus.Unknown";
}

export interface UnknownTool {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.UnknownTool";
  functionName: string;
  functionArgs: Record<string, unknown>;
}

export interface UnknownTrailblazeTool {
  class: "xyz.block.trailblaze.toolcalls.TrailblazeToolResult.Error.UnknownTrailblazeTool";
  command: unknown;
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

export interface VerificationStep {
  class: "xyz.block.trailblaze.yaml.VerificationStep";
  verify: string;
  recordable?: boolean;
  recording?: ToolRecording | null;
  maxRetries?: number | null;
  prompt?: string;
}

export interface ViewHierarchyTreeNode {
  nodeId?: number;
  accessibilityText?: string | null;
  x1?: number;
  y1?: number;
  x2?: number;
  y2?: number;
  centerPoint?: string | null;
  checked?: boolean;
  children?: ViewHierarchyTreeNode[];
  className?: string | null;
  clickable?: boolean;
  dimensions?: string | null;
  enabled?: boolean;
  focusable?: boolean;
  focused?: boolean;
  hintText?: string | null;
  ignoreBoundsFiltering?: boolean;
  password?: boolean;
  resourceId?: string | null;
  scrollable?: boolean;
  selected?: boolean;
  text?: string | null;
}

export interface WaitForSettle {
  class: "xyz.block.trailblaze.api.AgentDriverAction.WaitForSettle";
  timeoutMs: number;
  type?: AgentActionType;
}

export interface androidAccessibility {
  class: "androidAccessibility";
  className?: string | null;
  resourceId?: string | null;
  uniqueId?: string | null;
  text?: string | null;
  contentDescription?: string | null;
  hintText?: string | null;
  labeledByText?: string | null;
  stateDescription?: string | null;
  paneTitle?: string | null;
  roleDescription?: string | null;
  composeTestTag?: string | null;
  isEnabled?: boolean;
  isClickable?: boolean;
  isCheckable?: boolean;
  isChecked?: boolean;
  isSelected?: boolean;
  isFocused?: boolean;
  isEditable?: boolean;
  isScrollable?: boolean;
  isPassword?: boolean;
  isHeading?: boolean;
  isMultiLine?: boolean;
  inputType?: number;
  collectionItemInfo?: CollectionItemInfo | null;
  packageName?: string | null;
  tooltipText?: string | null;
  error?: string | null;
  isShowingHintText?: boolean;
  isContentInvalid?: boolean;
  isVisibleToUser?: boolean;
  isLongClickable?: boolean;
  isFocusable?: boolean;
  isTextSelectable?: boolean;
  isImportantForAccessibility?: boolean;
  drawingOrder?: number;
  maxTextLength?: number;
  actions?: string[];
  collectionInfo?: CollectionInfo | null;
  rangeInfo?: RangeInfo | null;
}

export interface androidMaestro {
  class: "androidMaestro";
  text?: string | null;
  resourceId?: string | null;
  accessibilityText?: string | null;
  className?: string | null;
  hintText?: string | null;
  clickable?: boolean;
  enabled?: boolean;
  focused?: boolean;
  checked?: boolean;
  selected?: boolean;
  focusable?: boolean;
  scrollable?: boolean;
  password?: boolean;
}

export interface compose {
  class: "compose";
  testTag?: string | null;
  role?: string | null;
  text?: string | null;
  editableText?: string | null;
  contentDescription?: string | null;
  toggleableState?: string | null;
  isEnabled?: boolean;
  isFocused?: boolean;
  isSelected?: boolean;
  isPassword?: boolean;
  hasClickAction?: boolean;
  hasScrollAction?: boolean;
}

export interface iosAxe {
  class: "iosAxe";
  role?: string | null;
  subrole?: string | null;
  roleDescription?: string | null;
  label?: string | null;
  value?: string | null;
  uniqueId?: string | null;
  type?: string | null;
  title?: string | null;
  help?: string | null;
  customActions?: string[];
  enabled?: boolean;
  contentRequired?: boolean;
  pid?: number | null;
}

export interface iosMaestro {
  class: "iosMaestro";
  text?: string | null;
  resourceId?: string | null;
  accessibilityText?: string | null;
  className?: string | null;
  hintText?: string | null;
  clickable?: boolean;
  enabled?: boolean;
  focused?: boolean;
  checked?: boolean;
  selected?: boolean;
  focusable?: boolean;
  scrollable?: boolean;
  password?: boolean;
  visible?: boolean;
  ignoreBoundsFiltering?: boolean;
}

export interface web {
  class: "web";
  ariaRole?: string | null;
  ariaName?: string | null;
  ariaDescriptor?: string | null;
  headingLevel?: number | null;
  cssSelector?: string | null;
  dataTestId?: string | null;
  nthIndex?: number;
  isInteractive?: boolean;
  isLandmark?: boolean;
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
    getInstalledApps: (request: GetInstalledAppsRequest): Promise<RpcResult<InstalledAppsResponse>> =>
      rpcCall<GetInstalledAppsRequest, InstalledAppsResponse>("GetInstalledAppsRequest", request, options),
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
    saveTargetConfig: (request: SaveTargetConfigRequest): Promise<RpcResult<SaveTargetConfigResponse>> =>
      rpcCall<SaveTargetConfigRequest, SaveTargetConfigResponse>("SaveTargetConfigRequest", request, options),
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
