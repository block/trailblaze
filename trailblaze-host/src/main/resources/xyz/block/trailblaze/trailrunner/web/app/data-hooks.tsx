// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// How often the run-detail hooks (trace, analytics, events) re-poll while a session is RUNNING. Kept
// tight so a live run's taps/assertions/events stream into the timeline within ~a second of happening
// on the device, rather than the old 2.5s lag (and the trace, which didn't poll at all).
const LIVE_POLL_MS = 1000;

function useStatus() {
  return useFetched(async () => {
    // Trails roots come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const [raw, roots] = await Promise.all([safeJson(API.status), window.TbRpc.getTrailRoots()]);
    if (!raw) return { data: { running: false, daemonPort: null, trailsDirectory: null }, mock: false };
    return {
      data: {
        running: !!raw.running,
        daemonPort: raw.port || raw.serverPort || null,
        connectedDevices: raw.connectedDevices ?? null,
        uptimeSeconds: raw.uptimeSeconds ?? null,
        trailsDirectory: roots?.primary || null,
        // Git context for the active workspace folder: its branch, and whether it's a linked git
        // worktree (vs the main checkout) — surfaced on the workspace chip so it's obvious which
        // checkout/branch you're driving.
        workspaceBranch: (roots && roots.primaryBranch) || null,
        workspaceIsWorktree: !!(roots && roots.primaryIsWorktree),
        extraRoots: roots?.extras || [],
        appVersion: raw.version || raw.appVersion || null,
        raw,
      },
      mock: false,
    };
  });
}

function useFavorites() {
  const [ids, setIds] = React.useState([]);
  const pending = React.useRef(new Set());
  React.useEffect(() => {
    let cancelled = false;
    // Favorites come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    window.TbRpc.getFavorites().then((raw) => {
      if (!cancelled) setIds(raw?.ids ?? []);
    });
    return () => { cancelled = true; };
  }, []);
  const toggle = React.useCallback(async (id) => {
    if (!id || pending.current.has(id)) return;
    pending.current.add(id);
    const fav = !ids.includes(id);
    setIds((cur) => (fav ? [...cur, id] : cur.filter((x) => x !== id)));
    const next = await setFavorite(id, fav);
    pending.current.delete(id);
    if (next) setIds(next);
    else setIds((cur) => (fav ? cur.filter((x) => x !== id) : [...cur, id]));
  }, [ids]);
  return { ids, toggle };
}
async function setFavorite(id, fav) {
  // Favorite toggle goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const res = await window.TbRpc.setFavorite(id, fav);
  return res?.ids ?? null;
}

function useTools() {
  return useFetched(async () => {
    // Tool catalog comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getTools();
    return { data: raw?.tools ?? [], mock: false };
  });
}

// Session-scoped shared tool catalog for the trail DETAIL view (the Steps tab's cosmetic
// "open this tool in the catalog" links, and the unified board's tool-arg editor). `getTools()` on
// the daemon re-runs the whole ToolCatalogBuilder scan (recursive FS walk + a bun subprocess per
// trailmap), so calling it on EVERY trail switch — which `useTools()` does, because the detail view
// remounts per trail — saturated the daemon and left the (otherwise cheap) trail-detail fetch hanging,
// i.e. the "stuck skeleton when moving between trails". This caches the catalog once per session and
// reuses it across switches; concurrent first-callers share one in-flight request. Invalidated on a
// workspace change and on tool creation (see `invalidateToolCatalog`). Deliberately NOT the same as
// `useTools()` — the Tools screen keeps that so its create→`reload()` stays authoritative/fresh.
let _sharedToolCatalog = null;
let _sharedToolCatalogInFlight = null;
function invalidateToolCatalog() {
  _sharedToolCatalog = null;
  _sharedToolCatalogInFlight = null;
}
if (typeof window !== 'undefined') window.addEventListener('tb:workspace-changed', invalidateToolCatalog);
function useToolCatalog() {
  return useFetched(async () => {
    if (_sharedToolCatalog) return { data: _sharedToolCatalog, mock: false };
    if (!_sharedToolCatalogInFlight) {
      _sharedToolCatalogInFlight = Promise.resolve(window.TbRpc.getTools())
        .then((raw) => { _sharedToolCatalog = raw?.tools ?? []; return _sharedToolCatalog; })
        .finally(() => { _sharedToolCatalogInFlight = null; });
    }
    return { data: await _sharedToolCatalogInFlight, mock: false };
  });
}

function useTrailmaps() {
  return useFetched(async () => {
    // Trailmaps come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getTrailmaps();
    return { data: raw?.trailmaps ?? [], mock: false };
  });
}

function useToolSource(className) {
  return useFetched(async () => {
    if (!className) return { data: null, mock: false };
    // Tool source comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getToolSource(className, null);
    return { data: raw?.source || null, mock: false };
  }, [className]);
}

function useScriptedToolParams(trailmap, toolId) {
  return useFetched(async () => {
    if (!trailmap || !toolId) return { data: [], mock: false };
    // Scripted (.ts) tools carry no params in the static catalog — the analyzer that derives a
    // tool's `<I>` arg schema from its TypeScript type is a per-trailmap bun subprocess, too slow
    // to run for the whole catalog. Resolve them on demand here (memoized per trailmap on the
    // daemon). [] when the analyzer is unavailable on this host.
    const params = await window.scriptedToolParams(trailmap, toolId);
    return { data: params || [], mock: false };
  }, [trailmap, toolId]);
}

function useTrails() {
  return useFetched(async () => {
    // Trail index comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getTrails();
    const trails = raw?.trails ?? [];
    // `folders` carries empty directories (no trail files yet) so the tree can still show them.
    return { data: trails, extra: { folders: raw?.folders ?? [] }, mock: false };
  });
}

function useSessions() {
  const hook = useFetched(async () => {
    // Sessions come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getSessions();
    const rows = raw?.sessions;
    if (!Array.isArray(rows) || rows.length === 0) {
      return { data: [], mock: false };
    }
    const sessions = rows.map((s) => ({
      id: s.id,
      title: s.title || s.id,
      target: s.target || '',
      device: s.device || s.platform || '',
      platform: s.platform || null,
      appId: s.appId || null,
      appVersionName: s.appVersionName || null,
      appVersionCode: s.appVersionCode || null,
      appBuildNumber: s.appBuildNumber || null,
      status: STATUS[s.status] ? s.status : 'unknown',
      dur: formatDuration(s.durationMs),
      ago: formatAgo(s.timestampMs),
      hasRecordedSteps: !!s.hasRecordedSteps,
      err: s.error || null,
      trailId: s.trailId || null,
      imported: !!s.imported,
      timestampMs: s.timestampMs || 0,
    }));
    return { data: sessions, mock: false };
  });

  const reloadRef = React.useRef(hook.reload);
  reloadRef.current = hook.reload;
  // Poll steadily (faster while something runs). A just-started run isn't in the cached data yet,
  // so a "poll only while running" gate would never notice it begin — the steady base interval is
  // what makes freshly-kicked-off recordings (and their variants) show up on their own.
  const hasRunning = (hook.data || []).some((s) => s.status === 'running');
  React.useEffect(() => {
    const id = setInterval(() => reloadRef.current(), hasRunning ? 2500 : 5000);
    return () => clearInterval(id);
  }, [hasRunning]);

  return hook;
}

// `isRunning`: while true, re-fetch the session logs on a fast interval so the step timeline (taps,
// assertions, screenshots) streams in live instead of only loading once when the run is opened. This is
// the trace the run-detail Timeline renders, so without polling it looked frozen mid-run.
function useSessionDetail(sessionId, isRunning) {
  const hook = useFetched(async () => {
    if (!sessionId) return { data: null, mock: false };
    const logs = await safeJson(API.sessionLogs(sessionId));
    if (!logs) return { data: null, mock: false };
    return {
      data: {
        id: sessionId,
        logs,
        trace: extractTrace(logs),
        llmLogs: extractLlmLogs(logs),
      },
      mock: false,
    };
  }, [sessionId]);

  const reloadRef = React.useRef(hook.reload);
  reloadRef.current = hook.reload;
  React.useEffect(() => {
    if (!isRunning) return;
    const id = setInterval(() => reloadRef.current(), LIVE_POLL_MS);
    return () => clearInterval(id);
  }, [isRunning]);

  return hook;
}

function useDevices() {
  return useFetched(async () => {
    // Device list comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getConnectedDevices();
    const devices = (raw?.devices || []).map((d) => {
      const platform = (d.platform || d.trailblazeDeviceId?.trailblazeDevicePlatform || '').toLowerCase();
      const id = d.instanceId || d.trailblazeDeviceId?.instanceId || '?';
      return {
        id,
        platform,
        name: d.description || id,
        short: d.description || id,
        driver: d.trailblazeDriverType || '?',
        connected: true,
      };
    });
    if (devices.length === 0) {
      return { data: [], mock: false };
    }
    return { data: devices, mock: false };
  });
}

// The toolsets + tools that actually register for a run against `targetId` on the
// device's `driver` (falls back to `platform`). Mirrors the agent's session-start
// composition — see /api/run-tools. Re-fetches whenever the target or driver changes.
function useRunTools(targetId, driver, platform) {
  return useFetched(async () => {
    if (!targetId) return { data: null, mock: false };
    // Run-tools composition comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getRunTools(targetId, driver, platform);
    return { data: raw || null, mock: false };
  }, [targetId, driver || '', platform || '']);
}

function useTrailDetail(id) {
  return useFetched(async () => {
    if (!id) return { data: null, mock: false };
    // Trail detail comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getTrailDetail(id);
    if (!raw) return { data: null, mock: false };
    return { data: raw, mock: false };
  }, [id]);
}

function useSessionAnalytics(sessionId, isRunning) {
  const hook = useFetched(async () => {
    if (!sessionId) return { data: { available: false, events: [] }, mock: false };
    // Session analytics come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getSessionAnalytics(sessionId);
    if (!raw) return { data: { available: false, events: [] }, mock: false };
    return { data: { available: !!raw.available, events: raw.events || [] }, mock: false };
  }, [sessionId]);

  const reloadRef = React.useRef(hook.reload);
  reloadRef.current = hook.reload;
  React.useEffect(() => {
    if (!isRunning) return;
    const id = setInterval(() => reloadRef.current(), LIVE_POLL_MS);
    return () => clearInterval(id);
  }, [isRunning]);

  return hook;
}

// Per-stream event capture for a run (logs/<id>/events/<name>.<style>.ndjson). Same
// poll-while-running shape as analytics so a live run's events stream into the timeline as
// they land. Any downstream event tap that writes this generic events format shows up here.
function useSessionEvents(sessionId, isRunning) {
  const hook = useFetched(async () => {
    if (!sessionId) return { data: { available: false, streams: [] }, mock: false };
    const raw = await safeJson(API.sessionEvents(sessionId));
    if (!raw) return { data: { available: false, streams: [] }, mock: false };
    return { data: { available: !!raw.available, streams: raw.streams || [] }, mock: false };
  }, [sessionId]);

  const reloadRef = React.useRef(hook.reload);
  reloadRef.current = hook.reload;
  React.useEffect(() => {
    if (!isRunning) return;
    const id = setInterval(() => reloadRef.current(), LIVE_POLL_MS);
    return () => clearInterval(id);
  }, [isRunning]);

  return hook;
}

function useSessionFiles(id) {
  return useFetched(async () => {
    if (!id) return { data: [], mock: false };
    // Session files come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getSessionFiles(id);
    return { data: raw?.files ?? [], mock: false };
  }, [id]);
}

function useToolUsageCounts() {
  return useFetched(async () => {
    // Tool usage counts come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getToolUsageCounts();
    return { data: (raw && raw.counts) || {}, mock: false };
  }, []);
}

function useToolUsages(toolId) {
  return useFetched(async () => {
    if (!toolId) return { data: [], mock: false };
    // Trails using a tool come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getToolUsages(toolId);
    return { data: (raw && raw.trails) || [], mock: false };
  }, [toolId]);
}

function useToolToolUsages(toolId) {
  return useFetched(async () => {
    if (!toolId) return { data: [], mock: false };
    // Tools that compose this tool (tool->tool callers) — REST endpoint, see window.toolToolUsages.
    // Distinct from useToolUsages above, which counts trails that record the tool.
    return { data: await window.toolToolUsages(toolId), mock: false };
  }, [toolId]);
}

function useToolToolUsageCounts() {
  return useFetched(async () => {
    // Bulk { toolId: callerCount } for the sidebar's "used by N tools" chip. The tool->tool analog
    // of useToolUsageCounts (trails). One call for the whole catalog.
    return { data: await window.toolToolUsageCounts(), mock: false };
  }, []);
}

function useDeviceApps(platform, id) {
  return useFetched(async () => {
    if (!platform || !id || platform === 'web') return { data: { targets: [], currentTargetAppId: null }, mock: false };
    // Installed device apps come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getDeviceApps(platform, id);
    return { data: raw || { targets: [], currentTargetAppId: null }, mock: false };
  }, [platform, id]);
}

function useTrailRoots() {
  return useFetched(async () => {
    // Trails roots come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getTrailRoots();
    if (!raw) return { data: { primary: null, extras: [] }, mock: false };
    return { data: { primary: raw.primary || null, extras: raw.extras || [] }, mock: false };
  });
}

function useSettings() {
  return useFetched(async () => {
    // Settings come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    // A null result (no settings repo wired / RPC failure) renders the "unavailable" state.
    const raw = await window.TbRpc.getSettings();
    if (!raw) return { data: { available: false }, mock: false };
    return { data: raw, mock: false };
  });
}

function useIntegrations() {
  return useFetched(async () => {
    // Integrations come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getIntegrations();
    const integrations = raw?.integrations ?? [];
    return { data: integrations, mock: false };
  });
}

function useSessionYaml(sessionId) {
  return useFetched(async () => {
    if (!sessionId) return { data: null, mock: false };
    const yaml = await safeText(exportSessionUrl(sessionId));
    return { data: yaml || null, mock: false };
  }, [sessionId]);
}

let _targetAppMapCache = null;
function useTargetAppMap() {
  const [map, setMap] = React.useState(_targetAppMapCache || {});
  React.useEffect(() => {
    if (_targetAppMapCache) return;
    Promise.resolve(getTargetApps()).then((r) => {
      const m = {};
      ((r && r.targetApps) || []).forEach((a) => { m[a.id] = a.displayName || a.id; });
      _targetAppMapCache = m;
      setMap(m);
    }).catch(() => {});
  }, []);
  return map;
}

// Global target selection — the active target app plus the set of devices chosen for it,
// shared across every screen. Target is the primary axis (scopes the Trailmaps/Tools
// views); `deviceIds` is a multi-select set (runs default to the first, see RunConfigDialog).
// Backed by localStorage + a listener set so any component can read/set it and re-render
// on change, without prop-drilling through the shell.
// Shape: { target, label, deviceIds: string[] }.
function normalizeGlobalTarget(t) {
  if (!t) return null;
  if (Array.isArray(t.deviceIds)) return t;
  // Migrate the legacy single-device shape { deviceId, deviceName, target, label }.
  if (t.deviceId) return { target: t.target || null, label: t.label || t.target || null, deviceIds: [t.deviceId] };
  return { target: t.target || null, label: t.label || t.target || null, deviceIds: [] };
}
let _globalTarget = (() => { try { return normalizeGlobalTarget(JSON.parse(window.localStorage.getItem('tb-global-target') || 'null')); } catch (_) { return null; } })();
const _globalTargetListeners = new Set();
function getGlobalTarget() { return _globalTarget; }
function setGlobalTarget(t) {
  _globalTarget = normalizeGlobalTarget(t);
  try { window.localStorage.setItem('tb-global-target', JSON.stringify(_globalTarget)); } catch (_) {}
  _globalTargetListeners.forEach((fn) => fn());
}
function useGlobalTarget() {
  const [, force] = React.useReducer((x) => x + 1, 0);
  React.useEffect(() => { _globalTargetListeners.add(force); return () => { _globalTargetListeners.delete(force); }; }, []);
  return [_globalTarget, setGlobalTarget];
}

function useExternalAgents() {
  const hook = useFetched(async () => {
    const raw = await fetchExternalAgents();
    return {
      data: {
        supportedAgents: raw.supportedAgents || [],
        runs: raw.runs || [],
      },
      mock: false,
    };
  });
  const reloadRef = React.useRef(hook.reload);
  reloadRef.current = hook.reload;
  const hasRunning = ((hook.data && hook.data.runs) || []).some((r) => r.status === 'running');
  React.useEffect(() => {
    if (!hasRunning) return;
    const id = setInterval(() => reloadRef.current(), 1500);
    return () => clearInterval(id);
  }, [hasRunning]);
  return hook;
}

function useExternalAgentEvents(runId, isRunning, onLiveEvent, follow) {
  const [state, setState] = React.useState({ data: [], loading: true, error: null });
  const liveRef = React.useRef(onLiveEvent);
  liveRef.current = onLiveEvent;

  React.useEffect(() => {
    setState({ data: [], loading: !!runId, error: null });
  }, [runId]);

  // Fetch history first, THEN open the stream strictly after it (?afterSeq): streamed events
  // append client-side (deduped by seq), so a chatty run costs one fetch plus one SSE message per
  // event — and `onLiveEvent` fires only for genuinely live events, never for loaded history.
  // That ordering is what keeps UI commands from re-applying when a running run is (re)opened.
  React.useEffect(() => {
    if (!runId) return;
    let closed = false;
    let es = null;
    const merge = (incoming) => setState((s) => ({ ...s, loading: false, data: mergeExternalAgentEvents(s.data, incoming) }));
    fetchExternalAgentEvents(runId).then((raw) => {
      if (closed) return;
      const events = raw.events || [];
      merge(events);
      if (!isRunning) return;
      const afterSeq = events.reduce((m, e) => Math.max(m, e.seq), -1);
      es = streamExternalAgentEvents(
        runId,
        afterSeq,
        (event) => {
          if (closed) return;
          merge([event]);
          if (liveRef.current) liveRef.current(event);
        },
        () => { if (!closed) fetchExternalAgentEvents(runId).then((raw2) => { if (!closed) merge(raw2.events || []); }); },
        () => {},
      );
    });
    return () => { closed = true; if (es) es.close(); };
  }, [runId, isRunning]);

  // Follow a FINISHED run by polling (record mode: the human demonstrates after the agent's turn,
  // so new human_action events arrive with no live SSE open). Merge only — never fire onLiveEvent,
  // so the live-only UI-command apply invariant above stays intact. When the run is running the
  // SSE effect already delivers everything, so this stays idle.
  React.useEffect(() => {
    if (!runId || !follow || isRunning) return;
    let closed = false;
    const merge = (incoming) => setState((s) => ({ ...s, loading: false, data: mergeExternalAgentEvents(s.data, incoming) }));
    const tick = () => { if (!closed) fetchExternalAgentEvents(runId).then((raw) => { if (!closed) merge(raw.events || []); }); };
    const timer = setInterval(tick, 1500);
    return () => { closed = true; clearInterval(timer); };
  }, [runId, isRunning, follow]);

  return state;
}

Object.assign(window, {
  useStatus, useFavorites, setFavorite, useTools, useToolCatalog, invalidateToolCatalog, useTrailmaps, useToolSource, useScriptedToolParams, useTrails, useSessions,
  useSessionDetail, useDevices, useTrailDetail, useRunTools, useSessionAnalytics, useSessionEvents, useSessionFiles,
  useDeviceApps, useTrailRoots, useSettings, useIntegrations, useSessionYaml, useTargetAppMap,
  useGlobalTarget, getGlobalTarget, setGlobalTarget,
  useToolUsages, useToolUsageCounts, useToolToolUsages, useToolToolUsageCounts,
  useExternalAgents, useExternalAgentEvents,
});
