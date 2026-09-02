// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// The build-time transpile strips types regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// How often the run-detail hooks (trace, analytics, events) re-poll while a session is RUNNING. Kept
// tight so a live run's taps/assertions/events stream into the timeline within ~a second of happening
// on the device, rather than the old 2.5s lag (and the trace, which didn't poll at all).
const LIVE_POLL_MS = 1000;

// How often the trail index and the open trail's YAML re-poll. Trail files are edited outside this
// app all the time - another IDE, a git checkout, an agent writing a whole suite - and until this
// existed the only way to see any of it was to reload the app. Both round trips are cheap: the
// daemon memoizes each file's parse by mtime and keeps its last walk, revalidating it by stat'ing
// the directories it visited, so an unchanged tree costs a stat sweep rather than a traversal.
const FILE_POLL_MS = 2500;

// Re-fetch a useFetched hook every `ms`, or not at all when `ms` is falsy (the callers that only
// poll a live run pass null once it finishes). The hook is read through a ref so a new interval
// isn't torn down and rebuilt on every render.
function usePolled(hook, ms) {
  const latest = React.useRef(hook);
  latest.current = hook;
  React.useEffect(() => {
    if (!ms) return;
    // A tick with the previous load still out is skipped, not queued: on a big workspace the trail
    // scan can take longer than this interval, and reloading on a fixed clock anyway would pile
    // overlapping scans onto the daemon faster than it can answer them.
    const id = setInterval(() => {
      // Nobody is looking at a hidden window, and a tick is not free: the trail-index scan walks the
      // workspace on the daemon. Several hooks poll at once, so a backgrounded app was still asking
      // for the world about once a second. The tick right after it comes back does the catching up.
      if (document.hidden) return;
      if (!latest.current.inFlight?.current) latest.current.reload();
    }, ms);
    // Coming back to the window shouldn't mean waiting out the rest of an interval to see the state
    // of the workspace, so catch up immediately instead.
    const onVisible = () => {
      if (!document.hidden && !latest.current.inFlight?.current) latest.current.reload();
    };
    document.addEventListener('visibilitychange', onVisible);
    return () => { clearInterval(id); document.removeEventListener('visibilitychange', onVisible); };
  }, [ms]);
  return hook;
}

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
    // `data` stays the installed-trailmaps array every existing consumer iterates. The
    // declared-but-absent targets ride on useFetched's `extra` passthrough instead of joining
    // `data`, so no picker or dropdown can offer a target this installation can't load — the same
    // separation the daemon enforces by carrying them in TrailmapsResponse.notInstalledTargets.
    return { data: raw?.trailmaps ?? [], mock: false, extra: { notInstalledTargets: raw?.notInstalledTargets ?? [] } };
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
  // Polled: a trail written, renamed or deleted outside this app (a git checkout, an agent
  // generating a suite) has to show up in the tree on its own. Several components hold the index at
  // once (the shell, the open screen, the run dialog), so several unsynchronized tickers each ask
  // for it. Sharing one scan between them is deliberately NOT done here: every caller must be able
  // to get an answer about the workspace and the moment IT asked about, and a shared in-flight scan
  // would hand a post-switch or post-save reader a snapshot taken before that switch or write.
  return usePolled(useFetched(async () => {
    // Trail index comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getTrails();
    // A failed RPC resolves to null rather than throwing (see dataOrNull), and null is NOT an empty
    // workspace: read as `[]` it would blank the whole tree on any poll that catches the daemon
    // mid-hiccup. Throwing routes it to useFetched's keep-the-last-good-data path.
    if (!raw) throw new Error('The trail index could not be read');
    const trails = raw?.trails ?? [];
    // `folders` carries empty directories (no trail files yet) so the tree can still show them.
    return { data: trails, extra: { folders: raw?.folders ?? [] }, mock: false };
  }), FILE_POLL_MS);
}

function useSessions() {
  const hook = useFetched(async () => {
    // Sessions come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getSessions();
    const rows = raw?.sessions;
    if (!Array.isArray(rows) || rows.length === 0) {
      return { data: [], mock: false };
    }
    // Spread FIRST, then rename and default. Everything below is one or the other, so nothing is
    // lost by carrying the row over whole - and carrying it over whole is the point: this used to be
    // a hand-written list of every field, so a field added to SessionSummary reached the browser and
    // silently stopped here. `deviceInstanceId` did exactly that, and the retry that needed it went
    // to whatever device was free instead of the one the run had used, with nothing to show for it.
    const sessions = rows.map((s) => ({
      ...s,
      title: s.title || s.id,
      target: s.target || '',
      // A classifier list ("android-phone"), not a device: it falls back to the platform on purpose.
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
      metadata: s.metadata || null,
      imported: !!s.imported,
      timestampMs: s.timestampMs || 0,
    }));
    return { data: sessions, mock: false };
  });

  // Poll steadily (faster while something runs). A just-started run isn't in the cached data yet,
  // so a "poll only while running" gate would never notice it begin — the steady base interval is
  // what makes freshly-kicked-off recordings (and their variants) show up on their own.
  const hasRunning = (hook.data || []).some((s) => s.status === 'running');
  return usePolled(hook, hasRunning ? 2500 : 5000);
}

// One read of a session's records, derived one way. The hook below polls it for the screen; the
// Share modal awaits it directly, because a run still executing writes records after the screen's
// last read and the file Share produces is the one people send on.
//
// Derived in timestamp order, not the filename order the daemon serves, so this screen's step count
// and the Share export read a run exactly as the report embedded beside them does — a tool/driver
// pair whose filenames run opposite their timestamps folds into one row for both. `logs` itself
// stays as served: Raw logs is meant to show the daemon's own listing.
async function readSessionDetail(sessionId) {
  const logs = await safeJson(API.sessionLogs(sessionId));
  if (!logs) return null;
  const records = TbRunPayload.orderLogsForExtraction(logs.filter(Boolean));
  return { id: sessionId, logs, trace: extractTrace(records), llmLogs: extractLlmLogs(records) };
}

// `isRunning`: while true, re-fetch the session logs on a fast interval so the run's derived trace
// keeps up with the device instead of only loading once when the run is opened. Run details passes
// true only while its Raw logs tab is showing — the report frame beside it follows the run over its
// own stream, and Share reads the run itself rather than trusting what this holds.
function useSessionDetail(sessionId, isRunning) {
  const hook = useFetched(async () => {
    if (!sessionId) return { data: null, mock: false };
    return { data: await readSessionDetail(sessionId), mock: false };
  }, [sessionId]);

  return usePolled(hook, isRunning ? LIVE_POLL_MS : null);
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
  // Polled for the same reason as the index: the open trail's YAML is edited outside this app, and
  // its readers (the editor, the steps board, the run dialog's preview) were all stuck on whatever
  // the file said when it was opened.
  const read = usePolled(useFetched(async () => {
    if (!id) return { data: null, mock: false, extra: '' };
    // Trail detail comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
    const raw = await window.TbRpc.getTrailDetail(id);
    // Same reason as the index: a null here is a failed call, not an empty trail. Throwing keeps the
    // YAML the editor is holding instead of handing it a blank file to reconcile against.
    if (!raw) throw new Error('The trail could not be read');
    // Stamp which trail this detail is, the same way [useTrailFolderFile] stamps its file. This hook
    // reports its last settled state on the render where `id` changes, so without a stamp no reader
    // can tell the new trail's first frame from one still carrying the previous trail's YAML - and
    // the editor seeds its buffer from that frame. See TbEditorSync.polledTrailDetail.
    return { data: raw, mock: false, extra: id };
  }, [id]), id ? FILE_POLL_MS : null);
  // Narrowed centrally rather than at each call site: every reader (the Edit tab, the steps board,
  // the run dialog's device hints) wants the trail it asked for, and all of them read the detail
  // before the effect that would have flipped `loading`. Guarded like the other TbEditorSync call
  // sites - a missing or reordered editor-sync.js must not blank every screen that opens a trail -
  // and falls back to the unnarrowed read, which is what this hook returned before the stamp.
  const sync = window.TbEditorSync;
  return sync ? sync.polledTrailDetail(read, id || '') : read;
}

// One file inside a trail bundle folder, polled while it is open. The Implementations matrix opens a
// variant or `blaze.yaml` in a pushed editor, and that editor was fed by a one-shot read — so unlike
// the Edit tab (which reads [useTrailDetail]) it never saw an edit made in another IDE or by an
// agent, and never entered the editor's conflict handling. `folderId` is pinned by the caller for as
// long as the editor is mounted, so a workspace hiccup can't repoint a live buffer at another folder.
function useTrailFolderFile(folderId, name) {
  const ready = !!(folderId && name);
  const key = ready ? folderId + '/' + name : '';
  return usePolled(useFetched(async () => {
    if (!ready) return { data: null, mock: false, extra: '' };
    const text = await fetchTrailFolderFile(folderId, name);
    // Same reason as the index and the trail detail: a null is a failed read, not an empty file.
    // Throwing routes it to useFetched's keep-the-last-good-data path, so a poll that catches the
    // daemon mid-hiccup leaves the buffer alone instead of asking the editor to reconcile against
    // a blank document (or against the "(could not read file)" placeholder the first read uses).
    if (text == null) {
      // Stamped like the success below, because useFetched drops `extra` when a deps change fails:
      // the failure has to name its own file or it would be read as the NEXT file's failure.
      const failed = new Error('The file could not be read');
      failed.fileKey = key;
      throw failed;
    }
    // Stamp which file this text is. This hook reports its last settled state on the render where
    // its deps change, so without a stamp the caller can't tell the new file's first frame from one
    // still carrying the previous file's text - and the editor seeds its buffer from that frame.
    return { data: text, mock: false, extra: key };
  }, [folderId || '', name || '']), ready ? FILE_POLL_MS : null);
}

// Per-stream event capture for a run (logs/<id>/events/<name>.ndjson). Same
// poll-while-running shape as the rest of a live run's data, so its events stream in as they
// land. Any downstream event tap that writes this generic events format shows up here.
function useSessionEvents(sessionId, isRunning) {
  const hook = useFetched(async () => {
    if (!sessionId) return { data: { available: false, streams: [] }, mock: false };
    const raw = await safeJson(API.sessionEvents(sessionId));
    if (!raw) return { data: { available: false, streams: [] }, mock: false };
    return { data: { available: !!raw.available, streams: raw.streams || [] }, mock: false };
  }, [sessionId]);

  return usePolled(hook, isRunning ? LIVE_POLL_MS : null);
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
  const hasRunning = ((hook.data && hook.data.runs) || []).some((r) => r.status === 'running');
  return usePolled(hook, hasRunning ? 1500 : null);
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
  useSessionDetail, readSessionDetail, useDevices, useTrailDetail, useTrailFolderFile, useRunTools, useSessionEvents, useSessionFiles,
  useDeviceApps, useTrailRoots, useSettings, useIntegrations, useSessionYaml, useTargetAppMap,
  useGlobalTarget, getGlobalTarget, setGlobalTarget,
  useToolUsages, useToolUsageCounts, useToolToolUsages, useToolToolUsageCounts,
  useExternalAgents, useExternalAgentEvents,
});
