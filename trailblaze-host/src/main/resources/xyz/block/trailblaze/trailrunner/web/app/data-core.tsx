// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

let _pendingRun = null;
function recordPendingRun(info) {
  _pendingRun = { title: (info && info.title) || 'New run', target: info && info.target, device: info && info.device, at: Date.now(), error: null };
}
function getPendingRun() { return _pendingRun; }
function clearPendingRun() { _pendingRun = null; }
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
  trailmaps: '/trailrunner/api/trailmaps',
  componentSource: (relPath) => `/trailrunner/api/tool-source?path=${encodeURIComponent(relPath)}`,
  toolUsages: (id) => `/trailrunner/api/tool-usages?toolId=${encodeURIComponent(id)}`,
  toolUsageCounts: '/trailrunner/api/tool-usage-counts',
  integrations: '/trailrunner/api/integrations',
  // Device + target-app calls, and every mutation/command, go through the typed RPC client
  // (window.TbRpc, app/rpc/daemon.ts), not this REST map.
};

async function safeJson(url, init) {
  try {
    const res = await fetch(url, init);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.json();
  } catch (e) {
    return null;
  }
}

async function safeText(url) {
  try {
    const res = await fetch(url);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    return await res.text();
  } catch (e) {
    return null;
  }
}

function useFetched(loader, deps = []) {
  const [state, setState] = React.useState({ data: null, loading: true, error: null, mock: false });
  const [version, setVersion] = React.useState(0);
  React.useEffect(() => {
    let cancelled = false;
    setState((s) => ({ ...s, loading: true }));
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
      setState({ data: null, loading: false, error: e, mock: false });
    });
    return () => { cancelled = true; };
  }, [...deps, version]);
  React.useEffect(() => {
    // Switching the workspace re-points the daemon at a different folder, which invalidates every
    // fetched resource (trails, trailmaps, tools, drafts, status, …). Rather than thread a reload
    // through each screen, every useFetched-backed hook listens for one global signal and refetches.
    // The workspace-switch call sites dispatch `tb:workspace-changed` after the change is applied.
    const onWorkspaceChanged = () => setVersion((v) => v + 1);
    window.addEventListener('tb:workspace-changed', onWorkspaceChanged);
    return () => window.removeEventListener('tb:workspace-changed', onWorkspaceChanged);
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
// the new folder (trails, trailmaps, tools, drafts, status, …). Returns { ok, applied, empty, error }.
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
  const res = await window.TbRpc.cancelSession(id);
  return { ok: res ? res.ok !== false : false };
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

// Lazily read a trailmap component body (toolset/waypoint/shortcut/etc.) by its
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

// ─── Blaze authoring: propose + drafts ────────────────────────────────────────
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

// Create a draft folder from a blaze.yaml. Returns { success, id }.
async function createDraft(name, yaml) {
  try {
    const r = await fetch('/trailrunner/api/draft', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ name, yaml }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.success === false) return { success: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { success: false, error: String(e) }; }
}

async function fetchDraftDetail(id) {
  return await safeJson(`/trailrunner/api/draft?id=${encodeURIComponent(id)}`);
}

async function fetchDraftFile(id, name) {
  return await safeText(`/trailrunner/api/draft/file?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name)}`);
}

async function updateDraftBlaze(id, yaml) {
  try {
    const r = await fetch('/trailrunner/api/draft', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, yaml }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.success === false) return { success: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { success: false, error: String(e) }; }
}

// Write any single file in a draft folder (blaze.yaml or a <platform>.trail.yaml) — inline editing.
async function updateDraftFile(id, name, yaml) {
  try {
    const r = await fetch('/trailrunner/api/draft/file', {
      method: 'PUT', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, name, yaml }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.success === false) return { success: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { success: false, error: String(e) }; }
}

// Delete one recorded <platform>.trail.yaml from a draft folder (never blaze.yaml).
async function deleteDraftFile(id, name) {
  try {
    const r = await fetch(`/trailrunner/api/draft/file/delete?id=${encodeURIComponent(id)}&name=${encodeURIComponent(name)}`, { method: 'POST' });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return { ok: true };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// Promote a draft: move its folder to `destination` (relative to the primary root). Returns { success, id }.
async function saveDraftTo(id, destination) {
  try {
    const r = await fetch('/trailrunner/api/draft/save-to', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, destination }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.success === false) return { success: false, error: body.error || `HTTP ${r.status}` };
    return body;
  } catch (e) { return { success: false, error: String(e) }; }
}

async function deleteDraft(id) {
  try {
    const r = await fetch('/trailrunner/api/draft/delete', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    // Honor the server's OkResponse.ok (the route now returns the real recursive-delete result and a
    // 409 when the folder isn't a staged draft) — a 200 alone doesn't mean the delete succeeded.
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
    return { ok: true };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// Reveal a draft's folder in Finder.
async function revealDraft(id) {
  try {
    const r = await fetch('/trailrunner/api/draft/reveal', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id }),
    });
    const body = await r.json().catch(() => ({}));
    return { ok: !!(r.ok && body.ok) };
  } catch (e) { return { ok: false }; }
}

// Record one variant per device: fan out the draft's blaze steps to each device. Returns
// { ok, sessionIds }. The recorded <platform>.trail.yaml lands back in the folder on completion.
async function recordDraft(id, deviceIds, options) {
  try {
    const r = await fetch('/trailrunner/api/draft/record', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ id, deviceIds, ...(options || {}) }),
    });
    const body = await r.json().catch(() => ({}));
    if (!r.ok) return { ok: false, error: body.error || `HTTP ${r.status}` };
    // 200 can still carry a partial error (some devices started, some didn't).
    return { ok: true, sessionIds: body.sessionIds || [], error: body.error || null };
  } catch (e) { return { ok: false, error: String(e) }; }
}

// ─── Library trail folder editing (/api/folder/*) ──────────────────────────────────────────────
// These mirror the /api/draft/* file/record endpoints but target an EXPLICITLY-chosen committed
// trail folder (a bundle), identified by its folder id `<rootIdx>/<relPath>` (e.g. `0/sample/login`).
// They deliberately bypass the drafts-only fence — the Trails board uses them to make a committed
// bundle as editable as a draft (edit steps, record, delete a recording).
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
    const body = await r.json().catch(() => ({}));
    if (!r.ok || body.ok === false) return { ok: false, error: body.error || `HTTP ${r.status}` };
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

Object.assign(window, {
  WORKSPACE_BLURB, WORKSPACE_EMPTY_NOTICE, workspaceRestartNotice, setTargetsRestartNeeded, getTargetsRestartNeeded,
  recordPendingRun, getPendingRun, clearPendingRun, failPendingRun,
  API, safeJson, safeText, useFetched, fileUrl,
  recordConnect, recordScreen, recordGesture, recordTree, recordDisconnect, recordToolParams, scriptedToolParams, toolToolUsages, toolToolUsageCounts,
  resolveRunDevice, connectDevice, fetchTrailYaml, dispatchRun, retrySession,
  getTargetApps, setTargetApp, updateTrail, createTrail, createTrailDir, fetchEditedTrails, runToolQuick, updateToolSource, fetchDeviceApps, validateTrail, rebuildDaemon, openSessionFile, revealTrailsRoot,
  pickDirectoryViaShell, addTrailRoot, removeTrailRoot, updateSetting, runIntegrationAction,
  deleteSession, clearSessions, cancelSession, revealSession, revealLogsRoot, revealToolSource, openTrailInEditor, revealTrail, exportSessionUrl, sessionArchiveUrl, importSessionArchive,
  fetchComponentSource, createTrailmapComponent,
  proposeSteps, createDraft, fetchDraftDetail, fetchDraftFile, updateDraftBlaze, saveDraftTo, deleteDraft, recordDraft,
  fetchTrailFolderFile, saveTrailFolderFile, deleteTrailFolderFile, recordTrailFolder,
});
