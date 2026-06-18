// AUTO-GENERATED — do not edit by hand.
//
// TypeScript bindings for the daemon's /rpc/<Name> request/response types, derived from the
// Kotlin @Serializable models. Kotlin is canonical; this is the derived artifact. Pair these
// with the rpcCall() client in ../rpc/client.ts.
//
// Regenerate with the `generateDtoTs` Gradle task; CI's `verifyDtoTs` byte-diffs this file
// against a fresh generation and fails the build on drift, so hand edits are reverted on
// the next CI run.

export interface ConnectToDeviceRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
}

export interface ConnectToDeviceResponse {
  deviceWidth: number;
  deviceHeight: number;
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

export interface NavigateWebUrlRequest {
  trailblazeDeviceId: TrailblazeDeviceId;
  url: string;
}

export interface NavigateWebUrlResponse {
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

export type TrailblazeDriverType = "ANDROID_ONDEVICE_ACCESSIBILITY" | "ANDROID_ONDEVICE_INSTRUMENTATION" | "IOS_HOST" | "IOS_AXE" | "PLAYWRIGHT_NATIVE" | "PLAYWRIGHT_ELECTRON" | "REVYL_ANDROID" | "REVYL_IOS" | "COMPOSE";
