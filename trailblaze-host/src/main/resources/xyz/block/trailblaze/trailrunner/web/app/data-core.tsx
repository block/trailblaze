// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

let _pendingRun = null;
function recordPendingRun(info) {
  _pendingRun = { title: (info && info.title) || 'New run', target: info && info.target, device: info && info.device, at: Date.now(), error: null, sessionId: (info && info.sessionId) || null };
}
function getPendingRun() { return _pendingRun; }
function clearPendingRun() { _pendingRun = null; }
// Patch the authoritative sessionId (returned by dispatchRun) onto the in-flight pending marker so
// the Active screen can lock onto the real session instead of guessing "newest running row".
function setPendingRunSession(sessionId) { if (_pendingRun && sessionId) _pendingRun = { ..._pendingRun, sessionId }; }
// Mark the in-flight pending run as failed so the Active screen can show why it never
// started (e.g. the device couldn't be reached), instead of a marker that just vanishes.
function failPendingRun(error) { if (_pendingRun) _pendingRun = { ..._pendingRun, error: error || 'Run failed to start' }; }

const API = {
  status: '/cli/status',
  sessions: '/trailrunner/api/sessions',
  roots: '/trailrunner/api/trails/roots',
  sessionLogs: (id) => `/trailrunner/api/session/${encodeURIComponent(id)}/logs`,
  sessionFiles: (id) => `/trailrunner/api/session/${encodeURIComponent(id)}/files`,
  sessionAnalytics: (id) => `/trailrunner/api/session/${encodeURIComponent(id)}/analytics`,
  sessionEvents: (id) => `/trailrunner/api/session/${encodeURIComponent(id)}/events`,
  trails: '/trailrunner/api/trails',
  tools: '/trailrunner/api/tools',
  componentSource: (relPath) => `/trailrunner/api/tool-source?path=${encodeURIComponent(relPath)}`,
  toolUsages: (id) => `/trailrunner/api/tool-usages?toolId=${encodeURIComponent(id)}`,
  toolUsageCounts: '/trailrunner/api/tool-usage-counts',
  integrations: '/trailrunner/api/integrations',
  // Device + target-app calls, and every mutation/command, go through the typed RPC client
  // (window.TbRpc, app/rpc/daemon.ts), not this REST map.
};

// cache: 'no-store' mirrors the server-side Cache-Control interceptor - the desktop shell's
// WKWebView replays header-less GETs from its persistent NSURLCache, which left polled JSON
// (e.g. polled run status) stale until a full page reload.
async function safeJson(url, init) {
  try {
    const res = await fetch(url, { cache: 'no-store', ...(init || {}) });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } catch (e) {
    return null;
  }
}

async function safeText(url) {
  try {
    const res = await fetch(url, { cache: 'no-store' });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.text();
  } catch (e) {
    return null;
  }
}

function useFetched(loader, deps = []) {
  const [state, setState] = React.useState({ data: null, loading: true, error: null, mock: false });
  const [version, setVersion] = React.useState(0);
  const depsKeyRef = React.useRef(null);
  React.useEffect(() => {
    let cancelled = false;
    // Only surface `loading` on the initial load or a real deps change (a different id/target).
    // A background reload (poll tick, manual refresh, workspace signal) keeps the current data on
    // screen without flipping loading — otherwise every poll blinks skeletons/spinners bound to it.
    const depsKey = JSON.stringify(deps);
    const isReload = depsKeyRef.current === depsKey;
    depsKeyRef.current = depsKey;
    setState((s) => (isReload && s.data != null ? s : { ...s, loading: true }));
    Promise.resolve(loader()).then((result) => {
      if (cancelled) return;
      setState((prev) => {
        // Diff before swapping: a steady poll almost always returns identical data. When it does,
        // keep the PREVIOUS `data` reference so consumers reconcile to the same objects and don't
        // re-render / flash on a no-op refresh. Only a real change swaps in the new array.
        const same = prev.data != null && JSON.stringify(prev.data) === JSON.stringify(result.data);
        return { data: same ? prev.data : result.data, loading: false, error: null, mock: !!result.mock, extra: result.extra };
      });
    }).catch((e) => {
      if (cancelled) return;
      // Same-deps refresh failure: keep the last good data — a transient poll error must not blank
      // sections that were already rendering (the empty state flashes in, then data pops back).
      // Deps-change failure: clear it — consumers gate stale data on `loading`, and leaving the
      // previous entity's data behind would let e.g. a trail editor adopt the wrong trail's yaml.
      setState((prev) => (isReload
        ? { ...prev, loading: false, error: e }
        : { data: null, loading: false, error: e, mock: false }));
    });
    return () => { cancelled = true; };
  }, [...deps, version]);
  React.useEffect(() => {
    // Switching the workspace re-points the daemon at a different folder, which invalidates every
    // fetched resource (trails, trailmaps, tools, status, …). Rather than thread a reload
    // through each screen, every useFetched-backed hook listens for one global signal and refetches.
    // The workspace-switch call sites dispatch `tb:workspace-changed` after the change is applied.
    // `tb:daemon-recovered` (from the shell's health watchdog) refetches the same way: while the
    // daemon was unreachable every in-flight fetch hung or failed, so all data is suspect.
    const onWorkspaceChanged = () => setVersion((v) => v + 1);
    window.addEventListener('tb:workspace-changed', onWorkspaceChanged);
    window.addEventListener('tb:daemon-recovered', onWorkspaceChanged);
    return () => {
      window.removeEventListener('tb:workspace-changed', onWorkspaceChanged);
      window.removeEventListener('tb:daemon-recovered', onWorkspaceChanged);
    };
  }, []);
  return { ...state, reload: () => setVersion((v) => v + 1) };
}

function fileUrl(id, name) {
  return `/static/${encodeURIComponent(id)}/${encodeURIComponent(name)}`;
}

async function resolveRunDevice(trail, preferredId) {
  // Device list comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const devResp = await window.TbRpc.getConnectedDevices();
  const devices = devResp?.devices || [];
  if (devices.length === 0) return { error: 'No device connected. Connect a device, then run again.' };
  const want = (trail.platform || derivePlatformFromTrail(trail) || '').toLowerCase();
  const idOf = (d) => d.instanceId || d.trailblazeDeviceId?.instanceId;
  const platOf = (d) => (d.platform || d.trailblazeDeviceId?.trailblazeDevicePlatform || '').toLowerCase();
  let device = preferredId
    ? devices.find((d) => idOf(d) === preferredId && (!want || platOf(d) === want))
    : null;
  if (!device) device = want ? devices.find((d) => platOf(d) === want) : devices[0];
  if (!device) {
    const have = devices.map(platOf).filter(Boolean).join(', ') || 'none';
    return { error: `No ${want || 'matching'} device connected (connected: ${have}).` };
  }
  const trailblazeDeviceId = device.trailblazeDeviceId
    || (device.instanceId ? { instanceId: device.instanceId, trailblazeDevicePlatform: device.platform } : null);
  if (!trailblazeDeviceId) return { error: 'Could not resolve the device id for this run.' };
  return { device, trailblazeDeviceId };
}

async function connectDevice(trailblazeDeviceId) {
  return await window.TbRpc.connectToDevice(trailblazeDeviceId);
}

// Like connectDevice but returns { ok, error } so callers can show the daemon's real failure
// reason (e.g. "No target app selected. Pick one in the Target dropdown before connecting.").
async function connectDeviceDetailed(trailblazeDeviceId) {
  return await window.TbRpc.connectToDeviceDetailed(trailblazeDeviceId);
}

async function fetchTrailYaml(id) {
  // Trail detail comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const d = await window.TbRpc.getTrailDetail(id);
  return d?.yaml || null;
}

async function dispatchRun(trailblazeDeviceId, yaml, opts = {}) {
  // Run dispatch goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts); a
  // precondition failure (no deviceManager / blank yaml) comes back as { ok: false, error }.
  return await window.TbRpc.dispatchRun({ trailblazeDeviceId, yaml, ...opts });
}

// Race a launch-path RPC against a deadline. The RPC layer has no timeout by design (some calls
// legitimately run minutes), so a wedged daemon leaves fetch pending forever - and the run flow's
// "Starting…" state with it. Resolves to '__timeout__' instead of rejecting, matching the
// connectDevice race pattern in blaze.tsx.
function withTimeout(promise, ms) {
  return Promise.race([promise, new Promise((res) => setTimeout(() => res('__timeout__'), ms))]);
}

async function retrySession(session) {
  if (!session.hasRecordedSteps) return { ok: false, error: 'This session has no recorded steps to retry.' };
  const yaml = await safeText(exportSessionUrl(session.id));
  if (!yaml) return { ok: false, error: 'This session has no recorded steps to retry.' };
  const dev = (session.device || '').toLowerCase();
  const platform = (session.platform || '').toLowerCase()
    || (dev.includes('android') ? 'android' : dev.includes('ios') ? 'ios' : dev.includes('web') ? 'web' : '');
  const resolved = await resolveRunDevice({ platform }, null);
  if (resolved.error) return { ok: false, error: resolved.error };
  const connected = await connectDevice(resolved.trailblazeDeviceId);
  if (!connected) return { ok: false, error: 'Could not connect to the device to retry.' };
  dispatchRun(resolved.trailblazeDeviceId, yaml);
  return { ok: true };
}

async function getTargetApps() {
  return await window.TbRpc.getTargetApps();
}

async function setTargetApp(targetAppId) {
  return await window.TbRpc.setCurrentTargetApp(targetAppId);
}

async function fetchDeviceApps(platform, id) {
  if (!platform || !id || platform === 'web') return { targets: [], currentTargetAppId: null };
  // Installed device apps come from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const raw = await window.TbRpc.getDeviceApps(platform, id);
  return raw || { targets: [], currentTargetAppId: null };
}

// Per-app label/icon enrichment for the Create Target picker — extraction can be slow on a cold
// cache (Android pulls the APK once per build), so rows fetch this after the fast id list.
async function fetchInstalledAppBadge(platform, deviceId, appId) {
  return await safeJson(`/trailrunner/api/installed-app-badge?platform=${encodeURIComponent(platform)}&device=${encodeURIComponent(deviceId)}&appId=${encodeURIComponent(appId)}`);
}
function installedAppIconUrl(platform, deviceId, appId) {
  return `/trailrunner/api/installed-app-icon?platform=${encodeURIComponent(platform)}&device=${encodeURIComponent(deviceId)}&appId=${encodeURIComponent(appId)}`;
}

// Every installed app on a device (unfiltered by declared targets) — feeds the Create Target
// form's "Browse installed apps" picker. Returns [{ appId, label?, version? }]. System/preinstalled
// apps (browser, calculator, etc.) are excluded by default — pass includeSystemApps to reach one
// of those as a target.
async function fetchInstalledApps(platform, id, includeSystemApps) {
  if (!platform || !id || platform === 'web') return [];
  const raw = await window.TbRpc.getInstalledApps(platform, id, includeSystemApps);
  return (raw && raw.apps) || [];
}

async function openSessionFile(id, name) {
  // Open goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts); a null result
  // (RPC failure / unresolvable file) maps to ok:false, matching the old non-2xx contract.
  const res = await window.TbRpc.openSessionFile(id, name);
  return { ok: !!(res && res.ok) };
}

async function revealTrailsRoot() {
  // Reveal goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return (await window.TbRpc.revealTrailsRoot()) || { ok: false };
}

async function rebuildDaemon() {
  // Daemon rebuild + restart goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return await window.TbRpc.rebuildDaemon();
}

async function validateTrail(yaml) {
  // Distinguish "valid" from "couldn't validate": a validator error/500 must NOT
  // render as a green pass (fail-open masks a malformed trail). `unavailable` lets the
  // editor show a neutral "validation unavailable" note instead of claiming validity.
  // Validation goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts);
  // a null result (RPC failure/network error) maps to the same "unavailable" shape.
  const res = await window.TbRpc.validateTrail(yaml);
  if (!res) return { valid: null, unavailable: true, errors: [] };
  return res;
}

async function createTrail(path, yaml) {
  // Trail creation goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts);
  // a domain failure rides in the returned SaveTrailResponse's success/error fields.
  return await window.TbRpc.createTrail(path, yaml);
}

async function createTrailDir(path) {
  // Folder creation goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return await window.TbRpc.createTrailDir(path);
}

async function runToolQuick(yaml, trailblazeDeviceId) {
  // On-device tool run goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return await window.TbRpc.runToolQuick(yaml, trailblazeDeviceId);
}

async function fetchEditedTrails() {
  // Edited-trails list comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const raw = await window.TbRpc.getEditedTrails();
  return raw?.paths || [];
}

async function updateTrail(id, yaml) {
  // Trail save goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return await window.TbRpc.updateTrail(id, yaml);
}

async function updateToolSource(target, source) {
  // Tool-source save goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return await window.TbRpc.updateToolSource(target.className || null, target.path || null, source);
}

function pickDirectoryViaShell(initialDir) {
  if (typeof window.trailblazePickDirectory === 'function') {
    return window.trailblazePickDirectory(initialDir);
  }
  return Promise.resolve(window.prompt('Absolute path to directory of .trail.yaml files:', initialDir || '') || null);
}

async function addTrailRoot(path) {
  // Trail-root add goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts); the
  // daemon's validation error (e.g. "not a directory: X") rides in the result's `error` field.
  return await window.TbRpc.addTrailRoot(path);
}

async function removeTrailRoot(path) {
  // Trail-root remove goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return await window.TbRpc.removeTrailRoot(path);
}

async function updateSetting(patch) {
  // Settings patch goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return await window.TbRpc.updateSetting(patch);
}

// Apply a workspace switch end to end: persist the new trails directory, then — only if the daemon
// accepted it — broadcast `tb:workspace-changed` so every useFetched-backed hook refetches against
// the new folder (trails, trailmaps, tools, status, …). Returns { ok, applied, empty, error }.
// `empty` is true when the new workspace has no trails AND no trailmaps yet, so callers can warn that
// it will look empty until content is added (the daemon still accepts the switch — empty is allowed).
async function switchWorkspace(path) {
  if (!path) return { ok: false, error: 'No folder selected' };
  let res;
  try {
    res = await updateSetting({ trailsDirectory: path });
  } catch (e) {
    return { ok: false, error: (e && e.message) ? e.message : String(e) };
  }
  const applied = (res && res.ok && res.data && res.data.trailsDirectory) || '';
  if (!res || !res.ok || applied.trim() !== path.trim()) {
    return { ok: false, error: (res && res.error) || "That folder isn't a directory Trailblaze can read." };
  }
  // The daemon's settings flow updates synchronously inside updateSetting, so the workspace-aware
  // config resolver already points at the new folder by the time we refetch below.
  window.dispatchEvent(new CustomEvent('tb:workspace-changed'));
  let empty = false;
  let restartNeededForTargets = false;
  let addedTargets = [];
  try {
    // Trails/trailmaps reload live via the event above; app targets are resolved once at daemon
    // startup, so ask the daemon whether THIS workspace would declare a different target set (in
    // which case the picker is stale until a restart). Both checks are advisory — never fail the
    // switch on them.
    // `empty` keys off trails only: trails are workspace-exclusive, whereas getTrailmaps() is backed
    // by platformConfigResourceSource() and always includes classpath-bundled trailmaps (revyl, the
    // server runtime), so it can't tell whether the SELECTED folder has authored content.
    const [trails, drift] = await Promise.all([
      window.TbRpc.getTrails(),
      safeJson('/trailrunner/api/workspace/target-drift'),
    ]);
    empty = (trails && trails.trails ? trails.trails.length : 0) === 0;
    restartNeededForTargets = !!(drift && drift.restartNeeded);
    addedTargets = (drift && drift.added) || [];
  } catch (_) { /* advisory only; never fail the switch on them */ }
  // Restart-needed is a persistent, surface-independent state (the workspace chip is always visible
  // and owns the badge), so record it where any surface can read it instead of inline at the switch
  // site. Switching back to a workspace whose targets already match clears it.
  setTargetsRestartNeeded(restartNeededForTargets ? addedTargets : null);
  return { ok: true, applied, empty, restartNeededForTargets, addedTargets };
}

// Single source of truth for the workspace UI copy + the persistent "restart for app targets" state,
// so every switch surface (chip, trails sidebar, Settings) reads identical wording and signals.
const WORKSPACE_BLURB = 'The folder Trail Runner works from. Your trails, trailmaps, tools, and recordings all come from here.';
const WORKSPACE_EMPTY_NOTICE = 'This folder has no trails yet, so the trails list will be empty until you add some.';
function workspaceRestartNotice(added) {
  const ids = (added || []).filter(Boolean);
  const suffix = ids.length ? ` (${ids.join(', ')})` : '';
  return `Restart Trail Runner to load this workspace's app targets${suffix}.`;
}
// Persisted in sessionStorage so the badge survives SPA re-renders and shows regardless of which
// surface triggered the switch; a daemon restart reloads the page and clears it. Pass null to clear.
function setTargetsRestartNeeded(added) {
  try {
    if (added && added.length >= 0 && added !== null) {
      sessionStorage.setItem('tb-targets-restart', JSON.stringify({ added }));
    } else {
      sessionStorage.removeItem('tb-targets-restart');
    }
  } catch (_) { /* sessionStorage unavailable — fall back to event-only signalling */ }
  window.dispatchEvent(new CustomEvent('tb:targets-restart-changed'));
}
function getTargetsRestartNeeded() {
  try {
    const raw = sessionStorage.getItem('tb-targets-restart');
    return raw ? (JSON.parse(raw).added || []) : null;
  } catch (_) { return null; }
}

async function runIntegrationAction(id, action) {
  // Integration actions go through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const res = await window.TbRpc.runIntegrationAction(id, action);
  if (!res) return { ok: false, error: 'Request failed.' };
  return { ok: !!res.ok, error: res.error || null };
}

async function deleteSession(id) {
  // Session delete goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const res = await window.TbRpc.deleteSession(id);
  return res ? { ok: true } : { ok: false, error: 'Could not delete session.' };
}

async function clearSessions() {
  const res = await safeJson('/trailrunner/api/sessions/clear', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ confirm: true }),
  });
  return res ? { ok: true, deleted: res.deleted || 0 } : { ok: false, error: 'Could not clear runs.' };
}

async function cancelSession(id) {
  // Session cancel goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  // reason: 'cancelled' | 'released_device' | 'already_ended' | 'not_running' | null (request itself failed).
  const res = await window.TbRpc.cancelSession(id);
  if (!res) return { ok: false, reason: null };
  return { ok: res.ok !== false, reason: res.reason || null };
}

async function revealSession(id) {
  // Session reveal goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return (await window.TbRpc.revealSession(id)) || { ok: false };
}

async function revealLogsRoot() {
  const res = await safeJson('/trailrunner/api/sessions/reveal', { method: 'POST' });
  return res || { ok: false };
}

async function revealToolSource({ className, path }) {
  // Tool reveal goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return (await window.TbRpc.revealToolSource(className || null, path || null)) || { ok: false };
}

async function openTrailInEditor(id) {
  // Trail open goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return (await window.TbRpc.openTrailInEditor(id)) || { ok: false };
}

async function revealTrail(id) {
  // Trail reveal goes through the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  return (await window.TbRpc.revealTrail(id)) || { ok: false };
}

function exportSessionUrl(id) {
  return `/trailrunner/api/session/${encodeURIComponent(id)}/export`;
}

function sessionArchiveUrl(id) {
  return `/trailrunner/api/session/${encodeURIComponent(id)}/export.zip`;
}

async function importSessionArchive(file) {
  if (!file) return { ok: false, error: 'Choose a ZIP archive.' };
  const r = await fetch('/trailrunner/api/session/import', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/zip',
      'X-TrailRunner-Filename': file.name || 'session.zip',
    },
    body: file,
  });
  const body = await r.json().catch(() => ({}));
  if (!r.ok) return { ok: false, error: body.error || `HTTP ${r.status}` };
  return body;
}

// Lazily read a trailmap component body (trailhead/tool/etc.) by its
// trails/config/trailmaps-relative path. Reuses the tool-source endpoint's ?path= form.
async function fetchComponentSource(relPath) {
  // Component/tool source comes from the typed RPC client (window.TbRpc, from app/rpc/daemon.ts).
  const raw = await window.TbRpc.getToolSource(null, relPath);
  return raw?.source ?? null;
}

// Scaffold a new trailmap component file. Goes through the typed RPC client (window.TbRpc, from
// app/rpc/daemon.ts); the server's error (e.g. "already exists") rides in the response's error field.
async function createTrailmapComponent(req) {
  const j = await window.TbRpc.createTrailmapComponent(req);
  return j ? { ok: !!j.ok, relPath: j.relPath, error: j.error } : { ok: false, error: 'request failed' };
}

// Patches the target: block of a trailmap.yaml (Edit Target), or bootstraps a brand-new trailmap
// when the request carries createIfMissing (Create Target). Goes through the typed RPC client
// (window.TbRpc, from app/rpc/daemon.ts); the server's error (e.g. "unknown trailmap") rides in
// the response's error field, `created`/`warning`/`registeredLive` in their own fields alongside
// ok=true. `registeredLive` must be threaded through here — the caller reads it to decide whether
// to skip the "restart to activate" banner, so dropping it would leave the banner always on.
async function saveTargetConfig(req) {
  const j = await window.TbRpc.saveTargetConfig(req);
  return j ? { ok: !!j.ok, error: j.error, created: !!j.created, warning: j.warning, registeredLive: !!j.registeredLive } : { ok: false, error: 'request failed' };
}

// ─── Blaze authoring: propose + bundles ────────────────────────────────────────
// Turn an objective into proposed steps. opts: { target, platform, ground, trailblazeDeviceId }.
async function proposeSteps(objective, opts = {}) {
  try {
    const r = await fetch('/trailrunner/api/blaze/propose', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ objective, ...opts }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) return { steps: [], error: body.error || `HTTP ${r.status}` };
    return { steps: body.steps || [], error: body.error || null };
  } catch (e) { return { steps: [], error: String(e) }; }
}

// ─── Library trail folder editing (/api/folder/*) ──────────────────────────────────────────────
// The bundle surface: a trail folder in the library (`blaze.yaml` + `<platform>.trail.yaml`
// siblings), identified by its folder id `<rootIdx>/<relPath>` (e.g. `0/sample/login`). Create
// sessions save straight here - there is no staging area - and the Trails board edits, records
// into, and deletes these folders in place.

// Create a new bundle folder directly at `destination` (relative to the primary root) and write its
// blaze.yaml. Returns { success, id }.
async function createBundle(destination, yaml) {
  try {
    const r = await fetch('/trailrunner/api/folder/create', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ destination, yaml }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.success === false) return { success: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { success: false, error: String(e) }; }
}

// Bundle detail (title, steps, variants) for a folder that carries a blaze.yaml.
async function fetchBundleDetail(id) {
  return await safeJson(`/trailrunner/api/folder?id=${encodeURIComponent(id)}`);
}

// Delete a whole bundle folder from the library. Honor the server's OkResponse.ok - a 200 alone
// doesn't mean the recursive delete succeeded.
async function deleteTrailFolder(id) {
  try {
    const r = await fetch('/trailrunner/api/folder/delete', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return { ok: true };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// Reveal a bundle folder in Finder.
async function revealTrailFolder(id) {
  try {
    const r = await fetch('/trailrunner/api/folder/reveal', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    const body = await r.json().catch(() => ({}));
    return { ok: !!(r.ok && body.ok) };
  } catch (e) { return { ok: false }; }
}
async function fetchTrailFolderFile(folderId, name) {
  return await safeText(`/trailrunner/api/folder/file?id=${encodeURIComponent(folderId)}&name=${encodeURIComponent(name)}`);
}

// Write any single file in a trail folder (blaze.yaml or a <platform>.trail.yaml). Creates the file
// if it doesn't exist yet — including a brand-new blaze.yaml when promoting a recordings-only bundle.
async function saveTrailFolderFile(folderId, name, yaml) {
  try {
    const r = await fetch('/trailrunner/api/folder/file', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: folderId, name, yaml }),
    });
    const body = await r.json().catch(() => ({}));
    // A `success:false` body can ride a 200, so don't fall back to "HTTP 200" — that reads as success.
    if (!r.ok || body.success === false) return { success: false, error: body.error || (r.ok ? 'the server rejected the save' : `HTTP ${r.status}`) };
    return body;
  } catch (e) { return { success: false, error: String(e) }; }
}

async function deleteTrailFolderFile(folderId, name) {
  try {
    const r = await fetch(`/trailrunner/api/folder/file/delete?id=${encodeURIComponent(folderId)}&name=${encodeURIComponent(name)}`, { method: 'POST' });
    const body = await r.json().catch(() => ({}));
    // An `ok:false` body can ride a 200, so don't fall back to "HTTP 200" — that reads as success.
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || (r.ok ? 'the server refused the delete' : `HTTP ${r.status}`) };
    return { ok: true };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// Convert a legacy per-platform bundle folder into a single unified `<folder>.trail.yaml` (deleting
// the per-platform files + blaze.yaml it folds in). The server runs the Kotlin UnifiedTrailMigrator;
// on refusal (a trailhead / top-level tools / already migrated) it returns { success:false, error }.
async function migrateTrailFolder(folderId) {
  try {
    const r = await fetch(`/trailrunner/api/folder/migrate-unified?id=${encodeURIComponent(folderId)}`, { method: 'POST' });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.success === false) return { success: false, error: body.error || (r.ok ? 'the server refused the migration' : `HTTP ${r.status}`) };
    return body;
  } catch (e) { return { success: false, error: String(e) }; }
}

// Record one variant per device into a committed trail folder. The recorded <platform>.trail.yaml
// lands back in the bundle on completion. Requires the folder to carry a blaze.yaml to drive the run.
async function recordTrailFolder(folderId, deviceIds, options) {
  try {
    const r = await fetch('/trailrunner/api/folder/record', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id: folderId, deviceIds, ...(options || {}) }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return { ok: true, sessionIds: body.sessionIds || [], error: body.error || null };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// ─── Interactive recording: drive the live device, capture each gesture as a tool ─────────────
// These power the Record screen. `connect` opens a live stream the daemon holds; `screen` polls one
// mirror frame; `gesture` dispatches a tap/type/swipe to the device AND returns the recorded tool's
// YAML; `disconnect` releases the stream. The device id is `{ instanceId, trailblazeDevicePlatform }`.
async function recordConnect(trailblazeDeviceId, signal) {
  try {
    const r = await fetch('/trailrunner/api/record/connect', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trailblazeDeviceId }), signal,
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) {
    // Caller aborted (cancel / timeout) — distinguish from a real error so the UI can stay quiet.
    if (e && (e.name === 'AbortError' || String(e).includes('aborted'))) return { ok: false, aborted: true };
    return { ok: false, error: String(e) };
  }
}

async function recordScreen(trailblazeDeviceId) {
  try {
    const r = await fetch('/trailrunner/api/record/screen', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trailblazeDeviceId }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { ok: false, error: String(e) }; }
}

// `gesture`: { type: 'tap'|'longPress'|'swipe'|'inputText'|'pressKey', ...fields }.
async function recordGesture(trailblazeDeviceId, gesture) {
  try {
    const r = await fetch('/trailrunner/api/record/gesture', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trailblazeDeviceId, ...gesture }),
    });
    // Read the body as text so a non-JSON error (e.g. a bare 5xx) still yields a real message
    // instead of a naked "HTTP 500".
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) { /* non-JSON error body */ }
    if (!r.ok || body.ok === false) {
      const detail = body.error || (raw && raw.trim() ? raw.trim().split('\n')[0].slice(0, 200) : '') || `HTTP ${r.status}`;
      return { ok: false, error: detail, status: r.status };
    }
    return body;
  } catch (e) { return { ok: false, error: String(e) }; }
}

// `tree`: read the interactive/identifiable elements on the current screen (plain label + type +
// bounds) for the Elements inspector. Returns { ok, deviceWidth, deviceHeight, elements: [...] }.
async function recordTree(trailblazeDeviceId) {
  try {
    const r = await fetch('/trailrunner/api/record/tree', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trailblazeDeviceId }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { ok: false, error: String(e) }; }
}

// Selector advice for a pending step: a fast model's second opinion on which selector candidate
// will replay most reliably. Strictly advisory - the server hard-caps its wait, and callers race
// it against their own budget. Returns { ok, advice: { reason, preferOption? } | null }.
async function recordSelectorAdvice(payload) {
  try {
    const r = await fetch('/trailrunner/api/record/selector-advice', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { ok: false, error: String(e) }; }
}

async function recordDisconnect(trailblazeDeviceId) {
  try {
    await fetch('/trailrunner/api/record/disconnect', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trailblazeDeviceId }),
    });
    return { ok: true };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// Param schema for a Kotlin tool FQN, for the trailhead card's CLASS-mode param form. [] if none.
async function recordToolParams(className) {
  try {
    const r = await fetch('/trailrunner/api/record/tool-params', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ className }),
    });
    const body = await r.json().catch(() => ({}));
    return (r.ok && body.ok && Array.isArray(body.parameters)) ? body.parameters : [];
  } catch (e) { return []; }
}

// Analyzer-extracted `<I>` params for a scripted (.ts) tool, resolved on demand. [] if unavailable.
async function scriptedToolParams(trailmap, toolId) {
  try {
    const r = await fetch('/trailrunner/api/record/scripted-tool-params', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trailmap, toolId }),
    });
    const body = await r.json().catch(() => ({}));
    return (r.ok && body.ok && Array.isArray(body.parameters)) ? body.parameters : [];
  } catch (e) { return []; }
}

// Catalog tools that compose a given tool — its tool->tool callers (via `ctx.tools.<id>` in scripted
// tools or `invokeFrameworkTool` in Kotlin). Distinct from useToolUsages, which counts trails. Each
// entry is { id, trailmap, flavor }. [] on failure.
async function toolToolUsages(toolId) {
  try {
    const r = await fetch('/trailrunner/api/tool-tool-usages?toolId=' + encodeURIComponent(toolId));
    const body = await r.json().catch(() => ({}));
    return (r.ok && Array.isArray(body.usedBy)) ? body.usedBy : [];
  } catch (e) { return []; }
}

// Bulk { toolId: callerCount } for the sidebar's "used by N tools" chip — one call for the whole
// catalog (vs. toolToolUsages per row). The tool->tool analog of getToolUsageCounts (trails). {} on
// failure.
async function toolToolUsageCounts() {
  try {
    const r = await fetch('/trailrunner/api/tool-tool-usage-counts');
    const body = await r.json().catch(() => ({}));
    return (r.ok && body.counts) ? body.counts : {};
  } catch (e) { return {}; }
}

async function fetchExternalAgents() {
  return await safeJson('/trailrunner/api/external-agents') || { supportedAgents: [], runs: [] };
}

// A failed response's body is read as TEXT first: a structured {error} is preferred, but even a
// non-JSON crash page yields its first line instead of collapsing to a bare "HTTP <code>".
function externalAgentErrorDetail(status, raw, body) {
  if (body && body.error) return body.error;
  const line = String(raw || '').trim().split('\n')[0].slice(0, 200);
  if (line) return line;
  return status >= 500
    ? `the daemon hit an internal error (HTTP ${status}) and gave no details - its log has the full story`
    : `the daemon rejected the request (HTTP ${status}) with no details`;
}

async function startExternalAgent(request) {
  try {
    const r = await fetch('/trailrunner/api/external-agent/start', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request || {}),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body) };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

async function fetchExternalAgentEvents(id) {
  if (!id) return { events: [] };
  return await safeJson(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/events`) || { events: [] };
}

// The skills the conversation's child CLI can discover (workspace .claude/skills up the ancestry
// plus ~/.claude/skills), with names, descriptions, and on-disk locations. Powers the Skills tab.
async function fetchAgentSkills() {
  return await safeJson('/trailrunner/api/external-agent/skills') || { ok: false, skills: [] };
}

async function cancelExternalAgent(id) {
  if (!id) return { ok: false, error: 'missing run id' };
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/cancel`, { method: 'POST' });
    const body = await r.json().catch(() => ({}));
    return { ok: !!(r.ok && body.ok), error: body.error || null };
  } catch (e) { return { ok: false, error: String(e) }; }
}

async function replyExternalAgent(id, prompt) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/reply`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body) };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// ─── Demonstrate-first Create: the demo-session lifecycle endpoints ────────────────────────────
// A demo session is a solo-style run in "demo mode": start-demo creates it (phase positioning),
// mark-start bakes in the chosen trailhead (phase recording), finish records the objective (phase
// done), generate launches the authoring agent. Gestures keep using recordGesture with the demo
// runId - the server tags them setup (before mark-start) or step (after). The failure `status` is
// returned so callers can tell a not-yet-landed endpoint (404) from a real error.

async function startDemo(request) {
  try {
    const r = await fetch('/trailrunner/api/external-agent/start-demo', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request || {}),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// `trailhead`: { name, args, yaml } for the picked trailhead, or null for manual positioning.
async function demoMarkStart(id, trailhead) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/demo/mark-start`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ trailhead: trailhead || null }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

async function demoFinish(id, { objective, notes }) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/demo/finish`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ objective, notes: notes || null }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

async function demoGenerate(id, { agentType, model, sandbox }) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/demo/generate`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ agentType, model: model || null, sandbox: sandbox || null }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// Deletes one demonstrated step (a mistake made mid-recording) by its HUMAN_ACTION event id; the
// server removes the event, its actions.ndjson line, and its evidence files from the bundle.
async function demoDeleteStep(id, { eventId }) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/demo/delete-step`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ eventId }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// Collect another platform's demonstration for a trail that's already verified on one: the server
// resets the demo phase back to positioning (bound to the passed device) so the same
// Position -> Record -> Describe -> Generate flow runs again for the second device.
async function demoAddPlatform(id, { deviceId }) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/demo/add-platform`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ deviceId: deviceId || null }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// Reveal the demonstration bundle folder (actions.ndjson + evidence) in the OS file browser -
// same affordance as the trail/library "reveal" actions, for the demo's on-disk bundle.
async function demoRevealBundle(id) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/demo/reveal-bundle`, { method: 'POST' });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// ─── External-agent permission gating (mid-run tool approvals) ─────────────────────────────────
// Answer one pending tool-permission request the agent raised (`allow` this once, `allow_always`
// for this tool, or `deny`). The server clears it from run.pendingPermissions; the poll reflects it.
async function decideExternalAgentPermission(id, requestId, decision) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/permission`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ requestId, decision }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// Flip the run's mid-run auto-approve: when enabled, the agent's tool requests are granted without
// prompting. Reflected back in run.autoApprove on the next poll.
async function setExternalAgentAutoApprove(id, enabled) {
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/auto-approve`, {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ enabled: !!enabled }),
    });
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status };
    return body;
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e) }; }
}

// The trail file(s) the generation agent is writing, polled while it works so the live trail rail can
// stream them in as they appear. Files fill progressively (empty until the agent creates the trail);
// 404-graceful so a daemon build without the endpoint yet just yields no files rather than erroring.
async function demoTrailContent(id) {
  if (!id) return { ok: false, files: [] };
  try {
    const r = await fetch(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/demo/trail-content`);
    const raw = await r.text().catch(() => '');
    let body = {}; try { body = raw ? JSON.parse(raw) : {}; } catch (_) {}
    if (!r.ok || body.ok === false) return { ok: false, error: externalAgentErrorDetail(r.status, raw, body), status: r.status, files: [] };
    return { files: [], ...body };
  } catch (e) { return { ok: false, error: 'the daemon could not be reached: ' + String(e && e.message || e), files: [] }; }
}

// `afterSeq`: only deliver events strictly newer than this — pass the max seq already fetched so
// a stream opened mid-run never re-delivers history as if it were live.
function streamExternalAgentEvents(id, afterSeq, onEvent, onDone, onError) {
  if (!id) return null;
  const after = Number.isFinite(afterSeq) ? afterSeq : -1;
  const es = new EventSource(`/trailrunner/api/external-agent/${encodeURIComponent(id)}/stream?afterSeq=${after}`);
  es.addEventListener('agent-event', (e) => {
    try { onEvent && onEvent(JSON.parse(e.data)); } catch (_) {}
  });
  es.addEventListener('done', (e) => {
    onDone && onDone(e);
    es.close();
  });
  es.addEventListener('error', (e) => {
    // A transient drop (daemon restart, network blip) leaves readyState CONNECTING: the browser
    // auto-reconnects, re-sending Last-Event-ID (which the server honors), so keep the source
    // open and dedup handles any overlap. Only a terminal failure (CLOSED - e.g. the run no
    // longer exists server-side) is surfaced to the caller.
    if (es.readyState !== EventSource.CLOSED) return;
    onError && onError(e);
  });
  return es;
}

// Merge streamed/fetched event batches into an existing list, deduped by seq. Returns the
// existing array unchanged when nothing new arrived so React consumers don't re-render.
function mergeExternalAgentEvents(existing, incoming) {
  const base = existing || [];
  const add = (incoming || []).filter(Boolean);
  if (!add.length) return base;
  const seen = new Set(base.map((e) => e.seq));
  const fresh = add.filter((e) => !seen.has(e.seq));
  if (!fresh.length) return base;
  return base.concat(fresh).sort((a, b) => a.seq - b.seq);
}

function applyTrailRunnerUiCommand(command, go) {
  if (!command || !command.action) return { ok: false, error: 'missing action' };
  const params = command.params || {};
  const valueOf = (v) => {
    if (v == null) return null;
    if (typeof v === 'string' || typeof v === 'number' || typeof v === 'boolean') return v;
    if (v && typeof v === 'object' && 'value' in v) return v.value;
    return v;
  };
  const route = command.route || valueOf(params.route);
  const p = {};
  Object.keys(params || {}).forEach((k) => { p[k] = valueOf(params[k]); });
  switch (command.action) {
    case 'navigate':
      if (!route || !go) return { ok: false, error: 'missing route' };
      go(String(route), p);
      return { ok: true };
    case 'open_session': {
      const sessionId = command.sessionId || valueOf(params.sessionId);
      if (!sessionId || !go) return { ok: false, error: 'missing sessionId' };
      const view = valueOf(params.view) || 'completed';
      go(String(view), { sel: String(sessionId) });
      return { ok: true };
    }
    case 'open_trail': {
      const trailId = command.trailId || valueOf(params.trailId);
      if (!trailId || !go) return { ok: false, error: 'missing trailId' };
      go('trails', { sel: String(trailId) });
      return { ok: true };
    }
    case 'trail_output': {
      // The agent's result channel: surface the declared trail in the details panel WITHOUT
      // navigating away from the conversation. The chat listens and pins its YAML tab to this trail.
      const trailId = command.trailId || valueOf(params.trailId);
      if (!trailId) return { ok: false, error: 'missing trailId' };
      window.dispatchEvent(new CustomEvent('tb:external-agent-trail-output', { detail: command }));
      return { ok: true };
    }
    case 'ask_user':
      // The question renders inline in the transcript as a card with clickable answers (see
      // AgentAskCard). Nothing to navigate — acknowledge so it isn't treated as an unknown command.
      return { ok: true };
    case 'focus_external_agent': {
      const runId = valueOf(params.runId);
      if (!go) return { ok: false, error: 'missing navigator' };
      go('agents', runId ? { sel: String(runId) } : {});
      return { ok: true };
    }
    case 'show_message':
      window.dispatchEvent(new CustomEvent('tb:external-agent-message', { detail: command }));
      return { ok: true };
    default:
      return { ok: false, error: `unknown UI command: ${command.action}` };
  }
}

Object.assign(window, {
  WORKSPACE_BLURB, WORKSPACE_EMPTY_NOTICE, workspaceRestartNotice, setTargetsRestartNeeded, getTargetsRestartNeeded,
  recordPendingRun, getPendingRun, clearPendingRun, failPendingRun, setPendingRunSession,
  API, safeJson, safeText, useFetched, fileUrl,
  recordConnect, recordScreen, recordGesture, recordTree, recordDisconnect, recordSelectorAdvice, recordToolParams, scriptedToolParams, toolToolUsages, toolToolUsageCounts,
  resolveRunDevice, connectDevice, connectDeviceDetailed, fetchTrailYaml, dispatchRun, retrySession, withTimeout,
  getTargetApps, setTargetApp, updateTrail, createTrail, createTrailDir, fetchEditedTrails, runToolQuick, updateToolSource, fetchDeviceApps, fetchInstalledApps, fetchInstalledAppBadge, installedAppIconUrl, validateTrail, rebuildDaemon, openSessionFile, revealTrailsRoot,
  pickDirectoryViaShell, addTrailRoot, removeTrailRoot, updateSetting, runIntegrationAction,
  deleteSession, clearSessions, cancelSession, revealSession, revealLogsRoot, revealToolSource, openTrailInEditor, revealTrail, exportSessionUrl, sessionArchiveUrl, importSessionArchive,
  fetchComponentSource, createTrailmapComponent, saveTargetConfig,
  proposeSteps, createBundle, fetchBundleDetail, deleteTrailFolder, revealTrailFolder,
  fetchTrailFolderFile, saveTrailFolderFile, deleteTrailFolderFile, recordTrailFolder, migrateTrailFolder,
  fetchExternalAgents, startExternalAgent, fetchExternalAgentEvents, cancelExternalAgent, streamExternalAgentEvents, fetchAgentSkills,
  replyExternalAgent, applyTrailRunnerUiCommand, mergeExternalAgentEvents,
  startDemo, demoMarkStart, demoFinish, demoGenerate, demoAddPlatform, demoDeleteStep, demoRevealBundle,
  decideExternalAgentPermission, setExternalAgentAutoApprove, demoTrailContent,
});
