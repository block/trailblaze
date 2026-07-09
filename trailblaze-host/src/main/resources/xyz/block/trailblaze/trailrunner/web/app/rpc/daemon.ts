// Typed daemon-RPC layer for the Trail Runner web UI, built on the generated client (host-rpc.ts).
// The existing .jsx data layer (data-core.jsx / data-hooks.jsx) calls these through `window.TbRpc`
// instead of hand-written `fetch('/rpc/<Name>', …)` + `JSON.parse`, so a renamed Kotlin field now
// surfaces here as a TS compile error rather than a silent runtime `undefined`. The .jsx callers
// prefer `window.TbRpc` and fall back to their old fetch path if this bundle didn't load — so the
// migration is additive and safe to ship one endpoint at a time.
//
// `createDaemonRpc` is exported for tests (inject a `fetch` via RpcCallOptions). The browser bundle
// publishes a same-origin instance on `window.TbRpc` as a side effect.

// Long relative paths to the generated SDK bindings; a workspace package would shorten these later.
import {
  createRpcClient,
  type GetConnectedDevicesResponse,
  type GetTargetAppsResponse,
  type SetCurrentTargetAppResponse,
  type TrailblazeDeviceId,
} from "../../../../../../../../../../../sdks/typescript/src/generated/host-rpc";
import {
  createTrailRunnerRpcClient,
  type AnalyticsResponse,
  type CancelSessionResponse,
  type DeleteSessionResponse,
  type DeviceAppsResponse,
  type EditedTrailsResponse,
  type FavoritesResponse,
  type InstalledAppsResponse,
  type IntegrationsResponse,
  type NewComponentRequest,
  type NewComponentResponse,
  type OkResponse,
  type RebuildDaemonResponse,
  type RunRequest,
  type RunToolsResponse,
  type SaveTargetConfigRequest,
  type SaveTargetConfigResponse,
  type SaveTrailResponse,
  type SessionFilesResponse,
  type SessionsResponse,
  type SettingsDto,
  type SettingsPatchRequest,
  type ToolCatalogResponse,
  type ToolRunResponse,
  type ToolSourceResponse,
  type ToolUsageCountsResponse,
  type TrailDetailResponse,
  type TrailIndexResponse,
  type TrailmapsResponse,
  type TrailRootsResponse,
  type ValidateTrailResponse,
} from "../../../../../../../../../../../sdks/typescript/src/generated/trailrunner-dtos";
import type {
  RpcCallOptions,
  RpcResult,
} from "../../../../../../../../../../../sdks/typescript/src/rpc/client";

// Unwrap an RpcResult to its data, or null on failure — matches the old `safeJson` contract the
// .jsx callers already handle (`resp?.devices`, `if (resp)`), so the swap stays drop-in. The error
// detail is surfaced to the console (the old fetch path swallowed it) so a daemon/network failure
// is debuggable instead of silently rendering as "no devices".
async function dataOrNull<T>(call: Promise<RpcResult<T>>): Promise<T | null> {
  const result = await call;
  if (result.ok) return result.data;
  console.warn("[TbRpc] RPC call failed:", result.error);
  return null;
}

// Unwrap a SaveTrailResponse-shaped mutation. The DTO's own `success` flag carries the domain
// outcome (e.g. "already exists", a write error), so a 2xx with `success:false` is returned as-is —
// the .jsx callers branch on `body.success === false` / `body.error`. A transport/daemon failure is
// folded into the same shape so those callers don't need a separate error path.
async function saveTrailResult(call: Promise<RpcResult<SaveTrailResponse>>): Promise<SaveTrailResponse> {
  const result = await call;
  if (result.ok) return result.data;
  console.warn("[TbRpc] RPC call failed:", result.error);
  return { success: false, error: result.error.message };
}

// Unwrap to the `{ ok, data?, error? }` shape used by mutations whose failure has no response DTO to
// ride in (e.g. add-trail-root's "not a directory"). Unlike dataOrNull, this preserves the real
// error message so the UI can show it instead of a generic failure.
async function dataOrError<T>(
  call: Promise<RpcResult<T>>,
): Promise<{ ok: boolean; data?: T; error?: string }> {
  const result = await call;
  if (result.ok) return { ok: true, data: result.data };
  console.warn("[TbRpc] RPC call failed:", result.error);
  return { ok: false, error: result.error.message };
}

// Like saveTrailResult, but for the on-device tool-run result the Trailmaps "Run on device" tab
// reads (success/result/error/durationMs). A transport/daemon failure is folded into success:false.
async function toolRunResult(call: Promise<RpcResult<ToolRunResponse>>): Promise<ToolRunResponse> {
  const result = await call;
  if (result.ok) return result.data;
  console.warn("[TbRpc] RPC call failed:", result.error);
  return { success: false, error: result.error.message, durationMs: 0 };
}

/** Build the typed daemon-RPC surface the UI uses. `options` lets tests inject a `fetch`. */
export function createDaemonRpc(options: RpcCallOptions = {}) {
  const rpc = createRpcClient(options);
  const trailRunner = createTrailRunnerRpcClient(options);
  return {
    /** GetConnectedDevicesRequest → response (or null). Used by resolveRunDevice + useDevices. */
    getConnectedDevices: (): Promise<GetConnectedDevicesResponse | null> =>
      dataOrNull(rpc.getConnectedDevices()),
    /** GetTargetAppsRequest → response (or null). Used by getTargetApps. */
    getTargetApps: (): Promise<GetTargetAppsResponse | null> => dataOrNull(rpc.getTargetApps()),
    /** SetCurrentTargetAppRequest → response (or null). Used by setTargetApp. */
    setCurrentTargetApp: (targetAppId: string): Promise<SetCurrentTargetAppResponse | null> =>
      dataOrNull(rpc.setCurrentTargetApp({ targetAppId })),
    /** ConnectToDeviceRequest → success boolean (mirrors connectDevice's old `r.ok`). */
    connectToDevice: async (trailblazeDeviceId: TrailblazeDeviceId): Promise<boolean> => {
      const result = await rpc.connectToDevice({ trailblazeDeviceId });
      if (!result.ok) console.warn("[TbRpc] RPC call failed:", result.error);
      return result.ok;
    },
    /** GetSessionsRequest → response (or null). Used by useSessions. */
    getSessions: (): Promise<SessionsResponse | null> => dataOrNull(trailRunner.getSessions()),
    /** GetToolsRequest → response (or null). Used by useTools. */
    getTools: (): Promise<ToolCatalogResponse | null> => dataOrNull(trailRunner.getTools()),
    /** GetTrailmapsRequest → response (or null). Used by useTrailmaps. */
    getTrailmaps: (): Promise<TrailmapsResponse | null> => dataOrNull(trailRunner.getTrailmaps()),
    /** GetTrailsRequest → response (or null). Used by useTrails. */
    getTrails: (): Promise<TrailIndexResponse | null> => dataOrNull(trailRunner.getTrails()),
    /** GetTrailRootsRequest → response (or null). Used by useTrailRoots + useStatus. */
    getTrailRoots: (): Promise<TrailRootsResponse | null> => dataOrNull(trailRunner.getTrailRoots()),
    /** GetEditedTrailsRequest → response (or null). Used by fetchEditedTrails. */
    getEditedTrails: (): Promise<EditedTrailsResponse | null> => dataOrNull(trailRunner.getEditedTrails()),
    /** GetTrailDetailRequest → response (or null). Used by useTrailDetail + fetchTrailYaml. */
    getTrailDetail: (id: string): Promise<TrailDetailResponse | null> =>
      dataOrNull(trailRunner.getTrailDetail({ id })),
    /** ValidateTrailRequest → response (or null). Used by validateTrail. */
    validateTrail: (yaml: string): Promise<ValidateTrailResponse | null> =>
      dataOrNull(trailRunner.validateTrail({ yaml })),
    /** GetFavoritesRequest → response (or null). Used by useFavorites. */
    getFavorites: (): Promise<FavoritesResponse | null> => dataOrNull(trailRunner.getFavorites()),
    /** GetIntegrationsRequest → response (or null). Used by useIntegrations. */
    getIntegrations: (): Promise<IntegrationsResponse | null> => dataOrNull(trailRunner.getIntegrations()),
    /** GetSettingsRequest → response (or null). Used by useSettings (null → "unavailable"). */
    getSettings: (): Promise<SettingsDto | null> => dataOrNull(trailRunner.getSettings()),
    /** GetToolUsageCountsRequest → response (or null). Used by useToolUsageCounts. */
    getToolUsageCounts: (): Promise<ToolUsageCountsResponse | null> =>
      dataOrNull(trailRunner.getToolUsageCounts()),
    /** GetToolUsagesRequest → response (or null). Used by useToolUsages. */
    getToolUsages: (toolId: string): Promise<TrailIndexResponse | null> =>
      dataOrNull(trailRunner.getToolUsages({ toolId })),
    /** GetDeviceAppsRequest → response (or null). Used by useDeviceApps + fetchDeviceApps. */
    getDeviceApps: (platform: string, id: string): Promise<DeviceAppsResponse | null> =>
      dataOrNull(trailRunner.getDeviceApps({ platform, id })),
    /** GetInstalledAppsRequest → response (or null). Used by fetchInstalledApps (Create Target). */
    getInstalledApps: (platform: string, id: string, includeSystemApps?: boolean): Promise<InstalledAppsResponse | null> =>
      dataOrNull(trailRunner.getInstalledApps({ platform, id, includeSystemApps })),
    /** GetRunToolsRequest → response (or null). Used by useRunTools. */
    getRunTools: (
      target: string,
      driver?: string | null,
      platform?: string | null,
    ): Promise<RunToolsResponse | null> =>
      dataOrNull(trailRunner.getRunTools({ target, driver, platform })),
    /** GetSessionAnalyticsRequest → response (or null). Used by useSessionAnalytics. */
    getSessionAnalytics: (sessionId: string): Promise<AnalyticsResponse | null> =>
      dataOrNull(trailRunner.getSessionAnalytics({ sessionId })),
    /** GetSessionFilesRequest → response (or null). Used by useSessionFiles. */
    getSessionFiles: (sessionId: string): Promise<SessionFilesResponse | null> =>
      dataOrNull(trailRunner.getSessionFiles({ sessionId })),
    /** GetToolSourceRequest → response (or null). Used by useToolSource + fetchComponentSource. */
    getToolSource: (className: string | null, path: string | null): Promise<ToolSourceResponse | null> =>
      dataOrNull(trailRunner.getToolSource({ className, path })),

    // ─── Mutations ────────────────────────────────────────────────────────────
    /** CreateTrailRequest → SaveTrailResponse. Used by createTrail. */
    createTrail: (path: string, yaml: string): Promise<SaveTrailResponse> =>
      saveTrailResult(trailRunner.createTrail({ path, yaml })),
    /** CreateTrailDirRequest → SaveTrailResponse. Used by createTrailDir. */
    createTrailDir: (path: string): Promise<SaveTrailResponse> =>
      saveTrailResult(trailRunner.createTrailDir({ path })),
    /** UpdateTrailRequest → SaveTrailResponse. Used by updateTrail. */
    updateTrail: (id: string, yaml: string): Promise<SaveTrailResponse> =>
      saveTrailResult(trailRunner.updateTrail({ id, yaml })),
    /** ToolSourceSaveRequest → SaveTrailResponse. Used by updateToolSource. */
    updateToolSource: (
      className: string | null,
      path: string | null,
      source: string,
    ): Promise<SaveTrailResponse> =>
      saveTrailResult(trailRunner.toolSourceSave({ className, path, source })),
    /** AddTrailRootRequest → roots, or the validation error message. Used by addTrailRoot. */
    addTrailRoot: (path: string): Promise<{ ok: boolean; data?: TrailRootsResponse; error?: string }> =>
      dataOrError(trailRunner.addTrailRoot({ path })),
    /** RemoveTrailRootRequest → roots, or the validation error message. Used by removeTrailRoot. */
    removeTrailRoot: (path: string): Promise<{ ok: boolean; data?: TrailRootsResponse; error?: string }> =>
      dataOrError(trailRunner.removeTrailRoot({ path })),
    /** SetFavoriteRequest → favorites (or null). Used by setFavorite (data-hooks.jsx). */
    setFavorite: (id: string, favorite: boolean): Promise<FavoritesResponse | null> =>
      dataOrNull(trailRunner.setFavorite({ id, favorite })),

    // ─── Long-running commands ──────────────────────────────────────────────
    /**
     * RunRequest → the {ok, success, sessionId, error} shape dispatchRun's callers branch on. A
     * precondition failure (no deviceManager / blank yaml) comes back as { ok: false, error }.
     */
    dispatchRun: async (
      request: RunRequest,
    ): Promise<{ ok: boolean; success?: boolean; sessionId?: string | null; error?: string | null }> => {
      const result = await trailRunner.run(request);
      if (!result.ok) {
        console.warn("[TbRpc] RPC call failed:", result.error);
        return { ok: false, error: result.error.message };
      }
      const data = result.data;
      return { ok: true, success: data.success !== false, sessionId: data.sessionId ?? null, error: data.error ?? null };
    },
    /** ToolRunRequest → ToolRunResponse. Used by runToolQuick (up to ~5 min on-device). */
    runToolQuick: (yaml: string, trailblazeDeviceId: TrailblazeDeviceId | null): Promise<ToolRunResponse> =>
      toolRunResult(trailRunner.toolRun({ yaml, trailblazeDeviceId })),
    /** RebuildDaemonRequest → RebuildDaemonResponse {ok, error}. Used by rebuildDaemon (up to ~10 min). */
    rebuildDaemon: async (): Promise<RebuildDaemonResponse> => {
      const result = await trailRunner.rebuildDaemon();
      if (result.ok) return result.data;
      console.warn("[TbRpc] RPC call failed:", result.error);
      return { ok: false, error: result.error.message };
    },

    // ─── Side-effect / reveal commands (resolve a target, then act; null on failure) ─────
    /** DeleteSessionRequest → response (or null). Used by deleteSession. */
    deleteSession: (id: string): Promise<DeleteSessionResponse | null> =>
      dataOrNull(trailRunner.deleteSession({ id })),
    /** CancelSessionRequest → response (or null). Used by cancelSession. */
    cancelSession: (id: string): Promise<CancelSessionResponse | null> =>
      dataOrNull(trailRunner.cancelSession({ id })),
    /** RevealSessionRequest → OkResponse (or null). Used by revealSession. */
    revealSession: (id: string): Promise<OkResponse | null> => dataOrNull(trailRunner.revealSession({ id })),
    /** ToolRevealRequest → OkResponse (or null). Used by revealToolSource. */
    revealToolSource: (className: string | null, path: string | null): Promise<OkResponse | null> =>
      dataOrNull(trailRunner.toolReveal({ class: className, path })),
    /** TrailOpenRequest → OkResponse (or null). Used by openTrailInEditor. */
    openTrailInEditor: (id: string): Promise<OkResponse | null> => dataOrNull(trailRunner.trailOpen({ id })),
    /** RevealTrailRequest → OkResponse (or null). Used by revealTrail. */
    revealTrail: (id: string): Promise<OkResponse | null> => dataOrNull(trailRunner.revealTrail({ id })),
    /** RevealTrailsRootRequest → OkResponse (or null). Used by revealTrailsRoot. */
    revealTrailsRoot: (): Promise<OkResponse | null> => dataOrNull(trailRunner.revealTrailsRoot()),
    /** IntegrationActionRequest → OkResponse (or null). Used by integration cards. */
    runIntegrationAction: (id: string, action: string): Promise<OkResponse | null> =>
      dataOrNull(trailRunner.integrationAction({ id, action })),
    /** OpenSessionFileRequest → OkResponse (or null). Used by openSessionFile. */
    openSessionFile: (id: string, name: string): Promise<OkResponse | null> =>
      dataOrNull(trailRunner.openSessionFile({ id, name })),
    /** NewComponentRequest → response (or null). Used by createTrailmapComponent. */
    createTrailmapComponent: (req: NewComponentRequest): Promise<NewComponentResponse | null> =>
      dataOrNull(trailRunner.newComponent(req)),
    /** SaveTargetConfigRequest → response (or null). Used by saveTargetConfig (Edit Target). */
    saveTargetConfig: (req: SaveTargetConfigRequest): Promise<SaveTargetConfigResponse | null> =>
      dataOrNull(trailRunner.saveTargetConfig(req)),
    /** SettingsPatchRequest → updated settings (or the error message). Used by updateSetting. */
    updateSetting: (
      patch: SettingsPatchRequest,
    ): Promise<{ ok: boolean; data?: SettingsDto; error?: string }> =>
      dataOrError(trailRunner.settingsPatch(patch)),
  };
}

export type TbRpcApi = ReturnType<typeof createDaemonRpc>;

// Browser bundle side effect: publish a same-origin instance so the global-script .jsx files can
// call `window.TbRpc` without imports. Guarded so this module stays importable in a non-DOM test
// runner (bun:test), where `window` is undefined.
if (typeof window !== "undefined") {
  (window as unknown as { TbRpc?: TbRpcApi }).TbRpc = createDaemonRpc({ baseUrl: "" });
}
