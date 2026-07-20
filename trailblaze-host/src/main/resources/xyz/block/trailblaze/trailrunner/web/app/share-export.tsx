// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// The in-app "Share" button for a Run details page. It produces the SAME self-contained interactive
// HTML the CLI emits after a run — the renderer + log→timeline extraction live in run-report-core.js
// (loaded before this file), so this file is just the browser glue: collect screenshots from /static,
// build the run `meta` from the in-hand session summary, call core's buildRunReportHtml, then POST the
// result to the daemon to save it (the desktop WKWebView shell has no download handler, so a client
// blob can't be saved — the daemon writes the file and we open/reveal it via the host bridges).

async function fetchAsDataUrl(url) {
  try {
    const res = await fetch(url);
    if (!res.ok) return null;
    const blob = await res.blob();
    return await new Promise((resolve) => {
      const fr = new FileReader();
      fr.onload = () => resolve(fr.result);
      fr.onerror = () => resolve(null);
      fr.readAsDataURL(blob);
    });
  } catch (e) { return null; }
}

// Fetch every screenshot the trace references (deduped by filename) and return a
// { filename -> dataURI } map. `onProgress(done, total)` drives the modal's progress text.
async function collectScreenshots(trace, sessionId, onProgress) {
  const files = [...new Set((trace || []).map((t) => t.screenshotFile).filter(Boolean))];
  const shots = {};
  let done = 0;
  if (onProgress) onProgress(0, files.length);
  for (const f of files) {
    const url = `/static/${encodeURIComponent(sessionId)}/${encodeURIComponent(f)}`;
    const data = await fetchAsDataUrl(url);
    if (data) shots[f] = data;
    done++;
    if (onProgress) onProgress(done, files.length);
  }
  return shots;
}

// Build the full self-contained HTML document for a run. Async because it inlines screenshots.
// The trace/llmLogs are already derived (the Run details page holds them); we just gather the
// screenshot bytes and hand everything to the shared core renderer.
// Best-effort fetch of the session's recorded .trail.yaml so the exported report's Recording tab
// matches the headless `trailblaze report` output. Failure is non-fatal — the tab just won't show.
async function fetchRecordingYaml(sessionId) {
  try {
    const res = await fetch(`/trailrunner/api/session/${encodeURIComponent(sessionId)}/export`);
    if (!res.ok) return null;
    const text = await res.text();
    return text && text.trim() ? text : null;
  } catch (e) { return null; }
}

async function fetchOriginalYaml(sessionId) {
  try {
    const res = await fetch(`/trailrunner/api/session/${encodeURIComponent(sessionId)}/logs`);
    if (!res.ok) return null;
    return originalYamlFromLogs(await res.json());
  } catch (e) { return null; }
}

// Normalize the live route's generic event-stream DTO into the compact standalone-report shape.
// This carries generic plugin event streams from any producer into Share-as-HTML exports.
async function fetchReportEvents(sessionId) {
  try {
    const res = await fetch(`/trailrunner/api/session/${encodeURIComponent(sessionId)}/events`);
    if (!res.ok) return null;
    const raw = await res.json();
    const streams = (raw.streams || []).map((s) => ({
      name: s.label || s.streamId,
      style: s.style || '',
      total: s.count || (s.events || []).length,
      truncated: !!s.truncated,
      events: (s.events || []).map((e) => ({
        t: e.timeMs == null ? null : e.timeMs,
        d: JSON.stringify(e.data == null ? e : e.data),
      })),
    }));
    return streams.length ? streams : null;
  } catch (e) { return null; }
}

async function buildRunShareHtml({ s, trace, llmLogs, cmd, sessionId, onProgress }) {
  const shots = await collectScreenshots(trace, sessionId, onProgress);
  const [recordingYaml, originalYaml, events] = await Promise.all([
    fetchRecordingYaml(sessionId),
    fetchOriginalYaml(sessionId),
    fetchReportEvents(sessionId),
  ]);
  const meta = {
    title: s.title || s.id || 'Trailblaze run',
    status: s.status || 'unknown',
    target: s.target || null,
    appId: s.appId || null,
    // "5.58.0.0 (67500009)" — same display rule as the Info tab and RunReportGenerator.
    appVersion: s.appVersionName
      ? s.appVersionName + ((s.appBuildNumber || s.appVersionCode) ? ` (${s.appBuildNumber || s.appVersionCode})` : '')
      : (s.appBuildNumber || s.appVersionCode || null),
    device: s.device || null,
    platform: s.platform || null,
    duration: s.dur || null,
    ranAt: s.timestampMs ? new Date(s.timestampMs).toLocaleString() : (s.ago || null),
    steps: (trace || []).length,
    trailId: s.trailId || null,
    cmd: cmd || null,
    error: s.err || null,
    recordingYaml,
    originalYaml,
    generatedAt: new Date().toLocaleString(),
  };
  return buildRunReportHtml({ meta, trace, llmLogs, shots, events });
}

// POST the built HTML to the daemon, which writes it into the run's folder and returns the filename.
// We save host-side (not a client blob download) because the desktop WKWebView shell has no download
// handler — `<a download>`, blob:, and window.open are all silently dropped there. The daemon writing
// the file + the existing open/reveal host bridges are what make Share actually work in the app.
async function saveRunShareHtml(sessionId, name, html) {
  const res = await fetch(`/trailrunner/api/session/${encodeURIComponent(sessionId)}/share-html`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ name, html }),
  });
  if (!res.ok) throw new Error('Save failed (HTTP ' + res.status + ')');
  const j = await res.json();
  if (!j.ok) throw new Error(j.error || 'Save failed');
  return j.name;
}

// The Share modal. Builds the standalone HTML on open (inlining screenshots, with progress), saves it
// into the run's folder via the daemon, then offers "Open in browser" / "Show in Finder" through the
// host file bridges. Mirrors the look/feel of SaveAsTrailModal.
function ShareRunModal({ s, trace, llmLogs, cmd, sessionId, onClose }) {
  useLucide();
  const [phase, setPhase] = React.useState('building'); // building | saving | ready | error
  const [progress, setProgress] = React.useState({ done: 0, total: 0 });
  const [err, setErr] = React.useState(null);
  const [size, setSize] = React.useState(0);
  const [savedName, setSavedName] = React.useState(null);
  const slug = (s.title || s.id || 'run').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'run';

  React.useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const html = await buildRunShareHtml({
          s, trace, llmLogs, cmd, sessionId,
          onProgress: (done, total) => { if (!cancelled) setProgress({ done, total }); },
        });
        if (cancelled) return;
        setSize(new Blob([html]).size);
        setPhase('saving');
        const name = await saveRunShareHtml(sessionId, slug, html);
        if (cancelled) return;
        setSavedName(name);
        setPhase('ready');
      } catch (e) {
        if (!cancelled) { setErr(String((e && e.message) || e)); setPhase('error'); }
      }
    })();
    return () => { cancelled = true; };
  }, []);

  const fmtSize = (n) => n < 1024 ? n + ' B' : n < 1048576 ? (n / 1024).toFixed(0) + ' KB' : (n / 1048576).toFixed(1) + ' MB';
  const openInBrowser = () => { if (savedName) TB.openSessionFile(sessionId, savedName); };
  const revealInFinder = () => TB.revealSession(sessionId);
  const ready = phase === 'ready';

  return (
    <div className="tb-overlay" onClick={onClose} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px, 94vw)', padding: 24 }}>
        <h2 className="tb-h2" style={{ marginBottom: 6 }}>Share this run</h2>
        <p className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5, margin: '0 0 16px' }}>
          Saves this run as a single, self-contained <span className="tb-mono">.html</span> in the run's folder. Open it in
          your browser to view offline - no Trailblaze, no daemon needed - or reveal it to send the file to someone.
          Screenshots, the step timeline, and the agent's reasoning are all embedded.
        </p>
        {(phase === 'building' || phase === 'saving') && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 12.5, color: 'var(--text-subtle)' }}>
            <Ico n="loader-2" s={15} c="var(--tb-running)" spin />
            {phase === 'saving' ? 'Saving…' : progress.total > 0 ? `Embedding screenshots… ${progress.done} / ${progress.total}` : 'Gathering run data…'}
          </div>
        )}
        {phase === 'error' && <div style={{ fontSize: 12, color: 'var(--tb-fail)' }}>Could not create the file: {err}</div>}
        {ready && (
          <div style={{ fontSize: 12.5 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--tb-pass)' }}>
              <Ico n="check-circle-2" s={15} /> Saved · {fmtSize(size)}{progress.total > 0 ? ` · ${progress.total} screenshot${progress.total === 1 ? '' : 's'}` : ''}
            </div>
            <div className="tb-mono tb-sub" style={{ fontSize: 11, marginTop: 6, wordBreak: 'break-all' }}>{savedName}</div>
          </div>
        )}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
          <Btn sm onClick={onClose}>Close</Btn>
          <Btn sm ico="folder-open" onClick={revealInFinder} disabled={!ready}>Show in Finder</Btn>
          <Btn sm kind="primary" ico="external-link" onClick={openInBrowser} disabled={!ready}>Open in browser</Btn>
        </div>
      </div>
    </div>
  );
}

Object.assign(window, { buildRunShareHtml, saveRunShareHtml, ShareRunModal });
