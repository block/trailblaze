// Hermetic tests for the typed daemon-RPC layer — no daemon, no browser. A fake `fetch` records the
// request and returns a canned Response, so we assert the URL, the request body, the typed result
// mapping, and the error → null contract that data-core.jsx / data-hooks.jsx rely on.
//
// Run: `bun test app/rpc/daemon.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";
import { createDaemonRpc } from "./daemon";

function fakeFetch(status: number, body: unknown) {
  const calls: Array<{ url: string; init?: RequestInit }> = [];
  const impl = (async (url: string | URL | Request, init?: RequestInit) => {
    calls.push({ url: String(url), init });
    return new Response(JSON.stringify(body), {
      status,
      headers: { "content-type": "application/json" },
    });
  }) as unknown as typeof fetch;
  return { impl, calls };
}

describe("createDaemonRpc", () => {
  test("getConnectedDevices posts to the right path and returns typed devices", async () => {
    const { impl, calls } = fakeFetch(200, {
      devices: [
        { trailblazeDriverType: "ANDROID_ONDEVICE_ACCESSIBILITY", instanceId: "emulator-5554", description: "Pixel 7" },
      ],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getConnectedDevices();

    expect(calls[0].url).toBe("http://daemon/rpc/GetConnectedDevicesRequest");
    expect(resp?.devices[0].instanceId).toBe("emulator-5554");
    expect(resp?.devices[0].description).toBe("Pixel 7");
  });

  test("getConnectedDevices returns null on a non-2xx (the safeJson contract callers expect)", async () => {
    const { impl } = fakeFetch(500, { message: "boom" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.getConnectedDevices()).toBeNull();
  });

  test("connectToDevice wraps the id in the request and returns true on 2xx", async () => {
    const { impl, calls } = fakeFetch(200, { deviceWidth: 1080, deviceHeight: 2400 });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const ok = await rpc.connectToDevice({ instanceId: "emulator-5554", trailblazeDevicePlatform: "ANDROID" });

    expect(ok).toBe(true);
    expect(calls[0].url).toBe("http://daemon/rpc/ConnectToDeviceRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({
      trailblazeDeviceId: { instanceId: "emulator-5554", trailblazeDevicePlatform: "ANDROID" },
    });
  });

  test("connectToDevice returns false on a non-2xx", async () => {
    const { impl } = fakeFetch(404, { message: "no such device" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.connectToDevice({ instanceId: "x", trailblazeDevicePlatform: "ANDROID" })).toBe(false);
  });

  test("setCurrentTargetApp wraps the id in the request body", async () => {
    const { impl, calls } = fakeFetch(200, { success: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.setCurrentTargetApp("trailblaze-sample");

    expect(resp?.success).toBe(true);
    expect(calls[0].url).toBe("http://daemon/rpc/SetCurrentTargetAppRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ targetAppId: "trailblaze-sample" });
  });

  test("setCurrentTargetApp returns null on a non-2xx", async () => {
    const { impl } = fakeFetch(500, { message: "boom" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.setCurrentTargetApp("trailblaze-sample")).toBeNull();
  });

  test("getTargetApps posts to the right path and returns the typed target apps", async () => {
    const { impl, calls } = fakeFetch(200, {
      targetApps: [{ id: "trailblaze-sample", displayName: "Trailblaze Sample" }],
      currentTargetAppId: "trailblaze-sample",
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getTargetApps();

    expect(calls[0].url).toBe("http://daemon/rpc/GetTargetAppsRequest");
    expect(resp?.targetApps[0].id).toBe("trailblaze-sample");
    expect(resp?.currentTargetAppId).toBe("trailblaze-sample");
  });

  test("getTargetApps returns null on a non-2xx", async () => {
    const { impl } = fakeFetch(503, { message: "unavailable" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.getTargetApps()).toBeNull();
  });

  // --- Trail Runner RPC endpoints (createTrailRunnerRpcClient) ---

  test("getSessions posts to the right path and returns the typed sessions", async () => {
    const { impl, calls } = fakeFetch(200, {
      sessions: [{ id: "s1", title: "Login", status: "passed", timestampMs: 1 }],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getSessions();

    expect(calls[0].url).toBe("http://daemon/rpc/GetSessionsRequest");
    expect(resp?.sessions[0].id).toBe("s1");
  });

  test("getTools returns the typed catalog with the flavor union", async () => {
    const { impl, calls } = fakeFetch(200, {
      tools: [{ id: "openUrl", flavor: "scripted", trailmap: "sample", sourcePath: "a/b.ts" }],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getTools();

    expect(calls[0].url).toBe("http://daemon/rpc/GetToolsRequest");
    expect(resp?.tools[0].flavor).toBe("scripted");
  });

  test("getTrailmaps returns the typed trailmaps", async () => {
    const { impl, calls } = fakeFetch(200, { trailmaps: [{ id: "sample", components: [] }] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getTrailmaps();

    expect(calls[0].url).toBe("http://daemon/rpc/GetTrailmapsRequest");
    expect(resp?.trailmaps[0].id).toBe("sample");
  });

  test("getSessions returns null on a non-2xx", async () => {
    const { impl } = fakeFetch(500, { message: "boom" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.getSessions()).toBeNull();
  });

  test("getTrails posts to the right path and returns the typed index", async () => {
    const { impl, calls } = fakeFetch(200, {
      trails: [{ id: "t1", path: "a.trail.yaml", title: "A", folder: "" }],
      folders: ["empty-dir"],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getTrails();

    expect(calls[0].url).toBe("http://daemon/rpc/GetTrailsRequest");
    expect(resp?.trails[0].id).toBe("t1");
    expect(resp?.folders?.[0]).toBe("empty-dir");
  });

  test("getTrailRoots returns the typed roots", async () => {
    const { impl, calls } = fakeFetch(200, { primary: "/ws/trails", extras: ["/extra/trails"] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getTrailRoots();

    expect(calls[0].url).toBe("http://daemon/rpc/GetTrailRootsRequest");
    expect(resp?.primary).toBe("/ws/trails");
    expect(resp?.extras[0]).toBe("/extra/trails");
  });

  test("getEditedTrails returns the typed paths", async () => {
    const { impl, calls } = fakeFetch(200, { paths: ["sub/changed.trail.yaml"] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getEditedTrails();

    expect(calls[0].url).toBe("http://daemon/rpc/GetEditedTrailsRequest");
    expect(resp?.paths[0]).toBe("sub/changed.trail.yaml");
  });

  test("getTrailDetail sends the id in the request body and returns the typed detail", async () => {
    const { impl, calls } = fakeFetch(200, {
      id: "sub/login.trail.yaml",
      path: "/ws/sub/login.trail.yaml",
      title: "Login",
      yaml: "steps: []",
      steps: [],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getTrailDetail("sub/login.trail.yaml");

    expect(calls[0].url).toBe("http://daemon/rpc/GetTrailDetailRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sub/login.trail.yaml" });
    expect(resp?.yaml).toBe("steps: []");
  });

  test("getTrailDetail returns null on a non-2xx (missing trail → 404/failure)", async () => {
    const { impl } = fakeFetch(500, { message: "No trail found" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.getTrailDetail("missing")).toBeNull();
  });

  test("validateTrail posts the yaml and returns the typed validation result", async () => {
    const { impl, calls } = fakeFetch(200, { valid: false, errors: [{ message: "bad yaml", line: 3 }] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.validateTrail("steps: [");

    expect(calls[0].url).toBe("http://daemon/rpc/ValidateTrailRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ yaml: "steps: [" });
    expect(resp?.valid).toBe(false);
    expect(resp?.errors?.[0].message).toBe("bad yaml");
  });

  test("validateTrail returns null on a non-2xx (validator unavailable)", async () => {
    const { impl } = fakeFetch(500, { message: "boom" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.validateTrail("steps: []")).toBeNull();
  });

  test("getFavorites returns the typed ids", async () => {
    const { impl, calls } = fakeFetch(200, { ids: ["a.trail.yaml", "b.trail.yaml"] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getFavorites();

    expect(calls[0].url).toBe("http://daemon/rpc/GetFavoritesRequest");
    expect(resp?.ids).toEqual(["a.trail.yaml", "b.trail.yaml"]);
  });

  test("getIntegrations returns the typed integrations", async () => {
    const { impl, calls } = fakeFetch(200, {
      integrations: [{ id: "sample", name: "Sample", connected: true, detail: "ready" }],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getIntegrations();

    expect(calls[0].url).toBe("http://daemon/rpc/GetIntegrationsRequest");
    expect(resp?.integrations[0].id).toBe("sample");
  });

  test("getSettings returns the typed settings dto", async () => {
    const { impl, calls } = fakeFetch(200, {
      themeMode: "DARK",
      alwaysOnTop: false,
      captureLogcat: true,
      captureIosLogs: false,
      captureNetworkTraffic: false,
      captureAnalytics: true,
      showWebBrowser: true,
      showTrailsTab: true,
      showDevicesTab: true,
      showWaypointsTab: false,
      llm: { provider: "anthropic", model: "claude" },
      selfHealEnabled: true,
      requireSteps: false,
      saveAnnotatedScreenshots: true,
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getSettings();

    expect(calls[0].url).toBe("http://daemon/rpc/GetSettingsRequest");
    expect(resp?.themeMode).toBe("DARK");
    expect(resp?.llm.provider).toBe("anthropic");
  });

  test("getSettings returns null when settings are unavailable (RPC failure → unavailable state)", async () => {
    const { impl } = fakeFetch(500, { message: "settings not available" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.getSettings()).toBeNull();
  });

  test("getToolUsageCounts returns the typed counts map", async () => {
    const { impl, calls } = fakeFetch(200, { counts: { openUrl: 3, tapOn: 7 } });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getToolUsageCounts();

    expect(calls[0].url).toBe("http://daemon/rpc/GetToolUsageCountsRequest");
    expect(resp?.counts.openUrl).toBe(3);
  });

  test("getToolUsages sends the toolId and returns the typed trails", async () => {
    const { impl, calls } = fakeFetch(200, {
      trails: [{ id: "t1", path: "a.trail.yaml", title: "A", folder: "" }],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getToolUsages("openUrl");

    expect(calls[0].url).toBe("http://daemon/rpc/GetToolUsagesRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ toolId: "openUrl" });
    expect(resp?.trails[0].id).toBe("t1");
  });

  test("getDeviceApps sends platform + id and returns the typed targets", async () => {
    const { impl, calls } = fakeFetch(200, {
      targets: [{ id: "sample", displayName: "Sample", appId: "com.example.sample" }],
      currentTargetAppId: "sample",
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getDeviceApps("ANDROID", "emulator-5554");

    expect(calls[0].url).toBe("http://daemon/rpc/GetDeviceAppsRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ platform: "ANDROID", id: "emulator-5554" });
    expect(resp?.targets[0].id).toBe("sample");
    expect(resp?.currentTargetAppId).toBe("sample");
  });

  test("getRunTools sends target/driver/platform and returns the typed toolsets", async () => {
    const { impl, calls } = fakeFetch(200, {
      target: "sample",
      driver: "ANDROID_ONDEVICE_ACCESSIBILITY",
      resolved: true,
      toolsets: [{ id: "core", description: "Core", alwaysEnabled: true, tools: ["tapOn"] }],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getRunTools("sample", "ANDROID_ONDEVICE_ACCESSIBILITY", "android");

    expect(calls[0].url).toBe("http://daemon/rpc/GetRunToolsRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({
      target: "sample",
      driver: "ANDROID_ONDEVICE_ACCESSIBILITY",
      platform: "android",
    });
    expect(resp?.resolved).toBe(true);
    expect(resp?.toolsets[0].tools[0]).toBe("tapOn");
  });

  test("getSessionAnalytics sends the sessionId and returns the typed events", async () => {
    const { impl, calls } = fakeFetch(200, {
      available: true,
      events: [{ id: "e1", name: "screen_view", timeMs: 100 }],
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getSessionAnalytics("session-123");

    expect(calls[0].url).toBe("http://daemon/rpc/GetSessionAnalyticsRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ sessionId: "session-123" });
    expect(resp?.available).toBe(true);
    expect(resp?.events[0].name).toBe("screen_view");
  });

  test("getSessionAnalytics returns null on a non-2xx (unresolved session)", async () => {
    const { impl } = fakeFetch(500, { message: "No session found" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.getSessionAnalytics("missing")).toBeNull();
  });

  // --- Trail file mutations (createTrail / createTrailDir / updateTrail / updateToolSource) ---

  test("createTrail posts path + yaml and returns the typed SaveTrailResponse", async () => {
    const { impl, calls } = fakeFetch(200, { success: true, savedPath: "/ws/sub/a.trail.yaml" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.createTrail("sub/a", "steps: []");

    expect(calls[0].url).toBe("http://daemon/rpc/CreateTrailRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ path: "sub/a", yaml: "steps: []" });
    expect(resp.success).toBe(true);
    expect(resp.savedPath).toBe("/ws/sub/a.trail.yaml");
  });

  test("createTrail surfaces a domain failure carried in the 2xx body", async () => {
    const { impl } = fakeFetch(200, { success: false, error: "a.trail.yaml already exists at that path" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const resp = await rpc.createTrail("sub/a", "steps: []");
    expect(resp.success).toBe(false);
    expect(resp.error).toBe("a.trail.yaml already exists at that path");
  });

  test("createTrail folds a transport/daemon failure into success:false + error", async () => {
    const { impl } = fakeFetch(500, { message: "boom" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const resp = await rpc.createTrail("sub/a", "steps: []");
    expect(resp.success).toBe(false);
    expect(resp.error).toBe("boom");
  });

  test("createTrailDir posts the path and returns the typed SaveTrailResponse", async () => {
    const { impl, calls } = fakeFetch(200, { success: true, savedPath: "/ws/sub" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.createTrailDir("sub");

    expect(calls[0].url).toBe("http://daemon/rpc/CreateTrailDirRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ path: "sub" });
    expect(resp.success).toBe(true);
  });

  test("updateTrail posts id + yaml to the path-param-free request", async () => {
    const { impl, calls } = fakeFetch(200, { success: true, savedPath: "/ws/sub/a.trail.yaml" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.updateTrail("sub/a.trail.yaml", "steps: []");

    expect(calls[0].url).toBe("http://daemon/rpc/UpdateTrailRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sub/a.trail.yaml", yaml: "steps: []" });
    expect(resp.success).toBe(true);
  });

  test("updateToolSource posts className/path/source to ToolSourceSaveRequest", async () => {
    const { impl, calls } = fakeFetch(200, { success: true, savedPath: "/ws/trails/config/trailmaps/x/tools/foo.ts" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.updateToolSource(null, "trails/config/trailmaps/x/tools/foo.ts", "export const foo = 1;");

    expect(calls[0].url).toBe("http://daemon/rpc/ToolSourceSaveRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({
      className: null,
      path: "trails/config/trailmaps/x/tools/foo.ts",
      source: "export const foo = 1;",
    });
    expect(resp.success).toBe(true);
  });

  // --- Roots + favorites mutations (addTrailRoot / removeTrailRoot / setFavorite) ---

  test("addTrailRoot posts the path and returns the refreshed roots in { ok, data }", async () => {
    const { impl, calls } = fakeFetch(200, { primary: "/ws/trails", extras: ["/extra/trails"] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const res = await rpc.addTrailRoot("/extra/trails");

    expect(calls[0].url).toBe("http://daemon/rpc/AddTrailRootRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ path: "/extra/trails" });
    expect(res.ok).toBe(true);
    expect(res.data?.extras[0]).toBe("/extra/trails");
  });

  test("addTrailRoot surfaces the daemon's validation message (e.g. not a directory)", async () => {
    const { impl } = fakeFetch(500, { errorType: "HTTP_ERROR", message: "not a directory: /nope" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const res = await rpc.addTrailRoot("/nope");
    expect(res.ok).toBe(false);
    expect(res.error).toBe("not a directory: /nope");
  });

  test("removeTrailRoot posts the path to RemoveTrailRootRequest", async () => {
    const { impl, calls } = fakeFetch(200, { primary: "/ws/trails", extras: [] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const res = await rpc.removeTrailRoot("/extra/trails");

    expect(calls[0].url).toBe("http://daemon/rpc/RemoveTrailRootRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ path: "/extra/trails" });
    expect(res.ok).toBe(true);
    expect(res.data?.extras).toEqual([]);
  });

  test("setFavorite posts id + favorite and returns the updated favorites", async () => {
    const { impl, calls } = fakeFetch(200, { ids: ["a.trail.yaml"] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.setFavorite("a.trail.yaml", true);

    expect(calls[0].url).toBe("http://daemon/rpc/SetFavoriteRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "a.trail.yaml", favorite: true });
    expect(resp?.ids).toEqual(["a.trail.yaml"]);
  });

  test("setFavorite returns null on a non-2xx (so the optimistic toggle reverts)", async () => {
    const { impl } = fakeFetch(500, { message: "boom" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.setFavorite("a.trail.yaml", false)).toBeNull();
  });

  // --- Run / tool-run / rebuild commands (dispatchRun / runToolQuick / rebuildDaemon) ---

  test("dispatchRun posts the run request and maps the typed response", async () => {
    const { impl, calls } = fakeFetch(200, { success: true, sessionId: "recording_123" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.dispatchRun({
      trailblazeDeviceId: { instanceId: "emulator-5554", trailblazeDevicePlatform: "ANDROID" },
      yaml: "- prompts:\n  - step: noop",
    });

    expect(calls[0].url).toBe("http://daemon/rpc/RunRequest");
    expect(r.ok).toBe(true);
    expect(r.success).toBe(true);
    expect(r.sessionId).toBe("recording_123");
  });

  test("dispatchRun returns { ok: false, error } on a precondition failure (e.g. no deviceManager)", async () => {
    const { impl } = fakeFetch(503, { errorType: "HTTP_ERROR", message: "deviceManager not available" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const r = await rpc.dispatchRun({
      trailblazeDeviceId: { instanceId: "x", trailblazeDevicePlatform: "ANDROID" },
      yaml: "- prompts:\n  - step: noop",
    });
    expect(r.ok).toBe(false);
    expect(r.error).toBe("deviceManager not available");
  });

  test("runToolQuick posts yaml + deviceId and returns the typed tool-run result", async () => {
    const { impl, calls } = fakeFetch(200, { success: true, result: "tapped", durationMs: 42 });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.runToolQuick("- prompts:\n  - step: tap", { instanceId: "emulator-5554", trailblazeDevicePlatform: "ANDROID" });

    expect(calls[0].url).toBe("http://daemon/rpc/ToolRunRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({
      yaml: "- prompts:\n  - step: tap",
      trailblazeDeviceId: { instanceId: "emulator-5554", trailblazeDevicePlatform: "ANDROID" },
    });
    expect(r.success).toBe(true);
    expect(r.result).toBe("tapped");
    expect(r.durationMs).toBe(42);
  });

  test("runToolQuick folds a transport failure into success:false", async () => {
    const { impl } = fakeFetch(500, { message: "boom" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const r = await rpc.runToolQuick("- prompts:\n  - step: tap", null);
    expect(r.success).toBe(false);
    expect(r.error).toBe("boom");
  });

  test("rebuildDaemon posts an empty body and returns the typed { ok }", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.rebuildDaemon();

    expect(calls[0].url).toBe("http://daemon/rpc/RebuildDaemonRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({});
    expect(r.ok).toBe(true);
  });

  test("rebuildDaemon returns { ok: false, error } on a transport failure", async () => {
    const { impl } = fakeFetch(500, { message: "compile failed" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const r = await rpc.rebuildDaemon();
    expect(r.ok).toBe(false);
    expect(r.error).toBe("compile failed");
  });

  test("rebuildDaemon surfaces a compile failure carried in the 2xx body (ok:false)", async () => {
    // The daemon returns 200 with ok:false when the gradle compile itself fails — distinct from a
    // transport failure. The tools screen reads r.ok either way, so both must come through as false.
    const { impl } = fakeFetch(200, { ok: false, error: "e: Foo.kt:1:1 unresolved reference" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const r = await rpc.rebuildDaemon();
    expect(r.ok).toBe(false);
    expect(r.error).toBe("e: Foo.kt:1:1 unresolved reference");
  });

  // --- Reveals / opens + session commands + trailmap component (Group 4) ---

  test("deleteSession posts the id and returns the typed response", async () => {
    const { impl, calls } = fakeFetch(200, { deleted: "sess_1" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.deleteSession("sess_1");

    expect(calls[0].url).toBe("http://daemon/rpc/DeleteSessionRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sess_1" });
    expect(r?.deleted).toBe("sess_1");
  });

  test("deleteSession returns null on a non-2xx (unresolved session)", async () => {
    const { impl } = fakeFetch(500, { message: "no session found" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.deleteSession("missing")).toBeNull();
  });

  test("cancelSession posts the id and returns { ok }", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.cancelSession("sess_1");

    expect(calls[0].url).toBe("http://daemon/rpc/CancelSessionRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sess_1" });
    expect(r?.ok).toBe(true);
  });

  test("cancelSession returns null on a non-2xx (no deviceManager / unresolved session)", async () => {
    const { impl } = fakeFetch(500, { message: "could not cancel session 'sess_1'" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.cancelSession("sess_1")).toBeNull();
  });

  test("revealSession posts the id to RevealSessionRequest", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.revealSession("sess_1");

    expect(calls[0].url).toBe("http://daemon/rpc/RevealSessionRequest");
    expect(r?.ok).toBe(true);
  });

  test("revealToolSource maps className → the `class` wire field of ToolRevealRequest", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.revealToolSource("com.example.MyTool", null);

    expect(calls[0].url).toBe("http://daemon/rpc/ToolRevealRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ class: "com.example.MyTool", path: null });
    expect(r?.ok).toBe(true);
  });

  test("openTrailInEditor posts the id to TrailOpenRequest", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.openTrailInEditor("sub/login.trail.yaml");

    expect(calls[0].url).toBe("http://daemon/rpc/TrailOpenRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sub/login.trail.yaml" });
    expect(r?.ok).toBe(true);
  });

  test("revealTrail posts the id to its own RevealTrailRequest", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    await rpc.revealTrail("sub/login.trail.yaml");

    expect(calls[0].url).toBe("http://daemon/rpc/RevealTrailRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sub/login.trail.yaml" });
  });

  test("revealTrailsRoot posts an empty body to RevealTrailsRootRequest", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.revealTrailsRoot();

    expect(calls[0].url).toBe("http://daemon/rpc/RevealTrailsRootRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({});
    expect(r?.ok).toBe(true);
  });

  test("runIntegrationAction posts the selected integration action and returns the OkResponse", async () => {
    const { impl, calls } = fakeFetch(200, { ok: false, error: "Action unavailable" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.runIntegrationAction("sample", "open");

    expect(calls[0].url).toBe("http://daemon/rpc/IntegrationActionRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sample", action: "open" });
    expect(r?.ok).toBe(false);
    expect(r?.error).toBe("Action unavailable");
  });

  test("createTrailmapComponent posts the request to NewComponentRequest and surfaces the error", async () => {
    const { impl, calls } = fakeFetch(200, { ok: false, error: "a trailheads named 'home' already exists" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.createTrailmapComponent({ trailmap: "sample", kind: "trailheads", name: "home" });

    expect(calls[0].url).toBe("http://daemon/rpc/NewComponentRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ trailmap: "sample", kind: "trailheads", name: "home" });
    expect(r?.ok).toBe(false);
    expect(r?.error).toBe("a trailheads named 'home' already exists");
  });

  // --- Settings patch (updateSetting) ---

  test("updateSetting posts the partial patch and returns the updated settings in { ok, data }", async () => {
    const { impl, calls } = fakeFetch(200, {
      themeMode: "DARK",
      alwaysOnTop: false,
      captureLogcat: true,
      captureIosLogs: false,
      captureNetworkTraffic: false,
      captureAnalytics: true,
      showWebBrowser: true,
      showTrailsTab: true,
      showDevicesTab: true,
      showWaypointsTab: false,
      llm: { provider: "anthropic", model: "claude" },
      selfHealEnabled: true,
      requireSteps: false,
      saveAnnotatedScreenshots: true,
    });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const r = await rpc.updateSetting({ selfHealEnabled: true });

    expect(calls[0].url).toBe("http://daemon/rpc/SettingsPatchRequest");
    // A one-key patch goes out as exactly that key — the daemon leaves every other setting untouched.
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ selfHealEnabled: true });
    expect(r.ok).toBe(true);
    expect(r.data?.selfHealEnabled).toBe(true);
  });

  test("updateSetting surfaces 'settings not available' when no settings repo is wired", async () => {
    const { impl } = fakeFetch(500, { errorType: "HTTP_ERROR", message: "settings not available" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    const r = await rpc.updateSetting({ llmModel: "claude" });
    expect(r.ok).toBe(false);
    expect(r.error).toBe("settings not available");
  });

  test("getSessionFiles sends the sessionId and returns the typed files", async () => {
    const { impl, calls } = fakeFetch(200, { files: [{ name: "video.mp4", size: 1024 }] });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getSessionFiles("sess-1");

    expect(calls[0].url).toBe("http://daemon/rpc/GetSessionFilesRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ sessionId: "sess-1" });
    expect(resp?.files[0].name).toBe("video.mp4");
    expect(resp?.files[0].size).toBe(1024);
  });

  test("getSessionFiles returns null on a non-2xx (unresolved session)", async () => {
    const { impl } = fakeFetch(500, { message: "no session" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });
    expect(await rpc.getSessionFiles("missing")).toBeNull();
  });

  test("getToolSource sends class + path and returns the typed source", async () => {
    const { impl, calls } = fakeFetch(200, { source: "fun foo() {}" });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.getToolSource("com.example.MyTool", null);

    expect(calls[0].url).toBe("http://daemon/rpc/GetToolSourceRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ className: "com.example.MyTool", path: null });
    expect(resp?.source).toBe("fun foo() {}");
  });

  test("openSessionFile sends id + name and returns the typed ok", async () => {
    const { impl, calls } = fakeFetch(200, { ok: true });
    const rpc = createDaemonRpc({ baseUrl: "http://daemon", fetchImpl: impl });

    const resp = await rpc.openSessionFile("sess-1", "video.mp4");

    expect(calls[0].url).toBe("http://daemon/rpc/OpenSessionFileRequest");
    expect(JSON.parse(calls[0].init!.body as string)).toEqual({ id: "sess-1", name: "video.mp4" });
    expect(resp?.ok).toBe(true);
  });
});
