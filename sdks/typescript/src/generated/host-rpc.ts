// AUTO-GENERATED — do not edit by hand.
//
// Daemon /rpc/<Name> TypeScript bindings — request/response types AND a typed client —
// derived from the Kotlin @Serializable models and their RpcRequest<TResponse> declarations.
// Kotlin is canonical; this is the derived artifact.
//
// Regenerate with the `generateDtoTs` Gradle task; CI's `verifyDtoTs` byte-diffs this file
// against a fresh generation and fails the build on drift, so hand edits are reverted on
// the next CI run.
import { rpcCall, type RpcResult, type RpcCallOptions } from "../rpc/client.js";

export interface ConnectToDeviceRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
  targetAppId?: string | null;
}

export interface ConnectToDeviceResponse {
  deviceWidth: number;
  deviceHeight: number;
  logSessionId?: string | null;
}

export type DeviceInteraction = inputText | longPress | pressKey | swipe | tap;

export interface DeviceInteractionRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
  interaction: DeviceInteraction;
}

export interface DeviceInteractionResponse {
  success: boolean;
  errorMessage?: string | null;
}

export interface DisconnectDeviceRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
}

export interface DisconnectDeviceResponse {
  success: boolean;
}

export interface GetConnectedDevicesRequest {
}

export interface GetConnectedDevicesResponse {
  devices: TrailblazeConnectedDeviceSummary[];
}

export interface GetTargetAppsRequest {
}

export interface GetTargetAppsResponse {
  targetApps: TargetAppSummary[];
  currentTargetAppId?: string | null;
}

export interface GetToolCatalogRequest {
  trailblazeDriverType: TrailblazeDriverType;
}

export interface GetToolCatalogResponse {
  tools: TrailblazeToolDescriptor[];
}

export interface NavigateWebUrlRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
  url: string;
}

export interface NavigateWebUrlResponse {
  success: boolean;
}

export interface RunTrailYamlRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
  yaml: string;
}

export interface RunTrailYamlResponse {
  success: boolean;
}

export interface SetCurrentTargetAppRequest {
  targetAppId: string;
}

export interface SetCurrentTargetAppResponse {
  success: boolean;
}

export interface TargetAppSummary {
  id: string;
  displayName: string;
}

export interface TrailblazeConnectedDeviceSummary {
  trailblazeDriverType: TrailblazeDriverType;
  instanceId: string;
  description: string;
  platform?: TrailblazeDevicePlatform;
  trailblazeDeviceId?: TrailblazeDeviceId;
}

export interface TrailblazeDeviceId {
  instanceId: string;
  trailblazeDevicePlatform: TrailblazeDevicePlatform;
}

export type TrailblazeDevicePlatform = "ANDROID" | "IOS" | "WEB" | "DESKTOP";

export type TrailblazeDriverType = "ANDROID_ONDEVICE_ACCESSIBILITY" | "ANDROID_ONDEVICE_INSTRUMENTATION" | "ANDROID_TEST" | "IOS_HOST" | "IOS_AXE" | "PLAYWRIGHT_NATIVE" | "PLAYWRIGHT_ELECTRON" | "REVYL_ANDROID" | "REVYL_IOS" | "COMPOSE";

export interface TrailblazeToolDescriptor {
  name: string;
  description?: string | null;
  requiredParameters?: TrailblazeToolParameterDescriptor[];
  optionalParameters?: TrailblazeToolParameterDescriptor[];
  source?: TrailblazeToolSourceDescriptor | null;
  inputSchema?: Record<string, unknown> | null;
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

export interface TrailblazeToolSourceDescriptor {
  type: TrailblazeToolSourceType;
  identifier?: string | null;
  className?: string | null;
  yamlPath?: string | null;
  scriptPath?: string | null;
}

export type TrailblazeToolSourceType = "KOTLIN" | "YAML" | "TYPESCRIPT" | "JAVASCRIPT" | "DYNAMIC";

export interface inputText {
  type: "inputText";
  text: string;
}

export interface longPress {
  type: "longPress";
  x: number;
  y: number;
}

export interface pressKey {
  type: "pressKey";
  key: string;
}

export interface swipe {
  type: "swipe";
  startX: number;
  startY: number;
  endX: number;
  endY: number;
  durationMs?: number | null;
}

export interface tap {
  type: "tap";
  x: number;
  y: number;
}

/**
 * Typed client for the daemon's /rpc/<Name> endpoints — one method per RpcRequest<T>.
 *
 *   const rpc = createRpcClient({ baseUrl });
 *   const r = await rpc.getConnectedDevices();   // RpcResult<GetConnectedDevicesResponse>
 */
export function createRpcClient(options: RpcCallOptions = {}) {
  return {
    connectToDevice: (request: ConnectToDeviceRequest): Promise<RpcResult<ConnectToDeviceResponse>> =>
      rpcCall<ConnectToDeviceRequest, ConnectToDeviceResponse>("ConnectToDeviceRequest", request, options),
    deviceInteraction: (request: DeviceInteractionRequest): Promise<RpcResult<DeviceInteractionResponse>> =>
      rpcCall<DeviceInteractionRequest, DeviceInteractionResponse>("DeviceInteractionRequest", request, options),
    disconnectDevice: (request: DisconnectDeviceRequest): Promise<RpcResult<DisconnectDeviceResponse>> =>
      rpcCall<DisconnectDeviceRequest, DisconnectDeviceResponse>("DisconnectDeviceRequest", request, options),
    getConnectedDevices: (request: GetConnectedDevicesRequest = {}): Promise<RpcResult<GetConnectedDevicesResponse>> =>
      rpcCall<GetConnectedDevicesRequest, GetConnectedDevicesResponse>("GetConnectedDevicesRequest", request, options),
    getTargetApps: (request: GetTargetAppsRequest = {}): Promise<RpcResult<GetTargetAppsResponse>> =>
      rpcCall<GetTargetAppsRequest, GetTargetAppsResponse>("GetTargetAppsRequest", request, options),
    getToolCatalog: (request: GetToolCatalogRequest): Promise<RpcResult<GetToolCatalogResponse>> =>
      rpcCall<GetToolCatalogRequest, GetToolCatalogResponse>("GetToolCatalogRequest", request, options),
    navigateWebUrl: (request: NavigateWebUrlRequest): Promise<RpcResult<NavigateWebUrlResponse>> =>
      rpcCall<NavigateWebUrlRequest, NavigateWebUrlResponse>("NavigateWebUrlRequest", request, options),
    runTrailYaml: (request: RunTrailYamlRequest): Promise<RpcResult<RunTrailYamlResponse>> =>
      rpcCall<RunTrailYamlRequest, RunTrailYamlResponse>("RunTrailYamlRequest", request, options),
    setCurrentTargetApp: (request: SetCurrentTargetAppRequest): Promise<RpcResult<SetCurrentTargetAppResponse>> =>
      rpcCall<SetCurrentTargetAppRequest, SetCurrentTargetAppResponse>("SetCurrentTargetAppRequest", request, options),
  };
}
