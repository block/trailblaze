// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// The build-time transpile strips types regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// The in-app "Share" button for a Run details page. It produces the SAME self-contained interactive
// HTML the CLI emits after a run — the renderer + log→timeline extraction live in run-report-core.js
// and the payload assembly in run-payload.js (both loaded before this file), so this file is just
// the browser glue: ask TbRunPayload for an embed-mode session input (screenshots inlined as data
// URIs, hierarchies packed), call core's buildRunReportHtml, then POST the result to the daemon to
// save it (the desktop WKWebView shell has no download handler, so a client blob can't be saved —
// the daemon writes the file and we open/reveal it via the host bridges).

// Build the full self-contained HTML document for a run. Async because it inlines every screenshot
// the trace references; `onProgress(done, total)` drives the modal's progress text. The trace/llmLogs
// are already derived (the Run details page holds them).
async function buildRunShareHtml({ s, trace, llmLogs, cmd, sessionId, onProgress }) {
  return buildRunReportHtml(await TbRunPayload.buildSessionInput({
    s, trace, llmLogs, cmd, sessionId, onProgress, mode: 'embed',
  }));
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
        // A run still executing keeps writing records, and Run details only polls them while its
        // Raw logs tab is showing — the report frame follows the run over its own stream instead. So
        // the trace this was handed can be older than the report the reader is looking at. Read the
        // run once here rather than trusting the props: this file is the artifact people send on,
        // and one request beats an export that silently stops a few steps short.
        const fresh = s.status === 'running' ? await TB.readSessionDetail(sessionId) : null;
        if (cancelled) return;
        const html = await buildRunShareHtml({
          s,
          trace: fresh ? fresh.trace : trace,
          llmLogs: fresh ? fresh.llmLogs : llmLogs,
          cmd,
          sessionId,
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
