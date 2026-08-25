// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// The build-time transpile strips types regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// The run's report, rendered by the same viewer the exported .html and the CLI's `trailblaze report`
// use, in a same-origin frame. One implementation of the timeline, the screenshots, the LLM calls and
// the metadata instead of two, so what a reader sees here and what they see in a file they were sent
// cannot drift. The document links its frames to /static/ rather than embedding them, and follows a
// still-running run itself; `chrome=none` drops its own run header, which this screen supplies.
//
// Keyed by session id so switching runs loads that run rather than mutating this one's document.
function EmbeddedReport({ sessionId }) {
  return (
    <iframe
      key={sessionId}
      data-testid="embedded-report"
      title="Run report"
      src={`/trailrunner/report-live.html?session=${encodeURIComponent(sessionId)}&chrome=none`}
      style={{ display: 'block', width: '100%', height: '100%', border: 0, background: 'transparent' }}
    />
  );
}

function TraceViewer({ s, onDeleted, go, listCollapsed, onToggleList, onBack, backLabel, onStop, stopping }) {
  const trailsIndex = TB.useTrails();
  const sourceTrail = s.trailId ? ((trailsIndex.data || []).find((t) => t.id === s.trailId) || null) : null;
  const [mode, setMode] = React.useState('report');
  const [retrying, setRetrying] = React.useState(false);
  const [retryErr, setRetryErr] = React.useState(null);
  // Followed live only while Raw logs is the visible tab. Report is the default now and that frame
  // follows the run over its own stream, so polling here would re-fetch the whole session detail —
  // trace, LLM calls and every raw record — once a second to keep a hidden tab warm.
  const detail = TB.useSessionDetail(s.id, s.status === 'running' && mode === 'logs');
  const trace = detail.data?.trace || [];
  const llmLogs = detail.data?.llmLogs || [];
  const sessionId = detail.data?.id || s.id;
  const [deleting, setDeleting] = React.useState(false);
  useLucide();

  const detailReady = !!detail.data && detail.data.id === s.id;
  const showSkeleton = detail.loading && !detailReady;

  // One catch-up read when the run ends. The poll above only runs on the Raw logs tab, so a reader
  // who watched the run in the report would otherwise be left holding the trace as it stood when
  // they opened the page — including the step count in the header beside it.
  // Holds the run's id, not a flag: this screen is reused across runs (no `key` on it), so a plain
  // flag set while watching one run would fire a redundant catch-up read for the next run selected.
  const followedLive = React.useRef(s.status === 'running' ? s.id : null);
  React.useEffect(() => {
    if (s.status === 'running') { followedLive.current = s.id; return; }
    if (followedLive.current !== s.id) return;
    followedLive.current = null;
    detail.reload();
  }, [s.status, s.id]);

  const [menuOpen, setMenuOpen] = React.useState(false);
  const [saveOpen, setSaveOpen] = React.useState(false);
  const [shareOpen, setShareOpen] = React.useState(false);
  const menuBtnRef = React.useRef(null);
  const doRetry = async () => {
    if (retrying) return;
    setRetryErr(null);
    setRetrying(true);
    const prepared = await TB.prepareRetry(s);
    setRetrying(false);
    // A session with nothing to replay is refused right here: the user is looking at that session,
    // and there is no run to go and follow. Everything past this point is on the Active screen's
    // card, so the connect and the dispatch are not awaited - see the note on `launchRetry`.
    if (!prepared.ok) { setRetryErr(prepared.error || 'Retry failed.'); return; }
    const marker = TB.recordPendingRun({ title: s.title || s.id, target: s.target, device: s.device, awaitsDispatch: true });
    if (go) go('runs', { followLive: Date.now() });
    TB.launchRetry(prepared, marker);
  };
  const doDelete = async () => {
    if (deleting) return;
    if (!window.confirm('Delete this session? This cannot be undone.')) return;
    setDeleting(true);
    const r = await TB.deleteSession(s.id);
    setDeleting(false);
    if (r.ok && onDeleted) onDeleted();
  };
  const doExport = () => {
    const a = document.createElement('a');
    a.href = TB.exportSessionUrl(s.id);
    a.download = '';
    document.body.appendChild(a); a.click(); a.remove();
  };
  const doExportArchive = () => {
    const a = document.createElement('a');
    a.href = TB.sessionArchiveUrl(s.id);
    a.download = '';
    document.body.appendChild(a); a.click(); a.remove();
  };
  const actionItems = [
    { ico: 'save', label: 'Save as trail', accent: true, fn: () => setSaveOpen(true) },
    { ico: 'rotate-cw', label: retrying ? 'Retrying…' : 'Retry run', fn: doRetry },
    { ico: 'share-2', label: 'Share as HTML', fn: () => setShareOpen(true) },
    { ico: 'download', label: 'Export YAML', fn: doExport },
    { ico: 'archive', label: 'Export archive', fn: doExportArchive },
    { sep: true },
    { ico: 'trash-2', label: deleting ? 'Deleting…' : 'Delete run', danger: true, fn: doDelete },
  ];

  const files = TB.useSessionFiles(sessionId);
  const fileCount = (files.data || []).length;
  // The run's timeline, screenshots, LLM calls, YAML, captured event streams and metadata all live
  // in the report now, under its own tabs. What stays out here is what the report has no notion of:
  // the raw log records on disk, and the run's artifact files.
  const tabs = [
    ['report', 'Report'],
    ['logs', 'Raw logs'],
    ['artifacts', fileCount ? `Artifacts (${fileCount})` : 'Artifacts'],
  ];

  return (
    <div className="tb-in" style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, width: '100%' }}>
      <div style={{ padding: '24px 28px 0', flex: '0 0 auto' }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', justifyContent: 'space-between', gap: 16 }}>
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10, minWidth: 0 }}>
            {onBack ? (
              <button
                data-testid="trace-back"
                onClick={onBack}
                title={backLabel || 'Back'}
                style={{ flexShrink: 0, marginTop: 2, width: 30, height: 30, borderRadius: 8, border: '1px solid var(--tb-hairline-strong)', background: 'var(--bg-prominent)', color: 'var(--text-subtle)', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer' }}
              >
                <Ico n="arrow-left" s={16} />
              </button>
            ) : onToggleList && (
              <button
                data-testid="toggle-session-list"
                onClick={onToggleList}
                title={listCollapsed ? 'Show sessions' : 'Hide sessions'}
                style={{ flexShrink: 0, marginTop: 2, width: 30, height: 30, borderRadius: 8, border: '1px solid var(--tb-hairline-strong)', background: 'var(--bg-prominent)', color: 'var(--text-subtle)', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer' }}
              >
                <Ico n={listCollapsed ? 'panel-left-open' : 'panel-left-close'} s={16} />
              </button>
            )}
            <div style={{ minWidth: 0 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <h1 className="tb-h1" style={{ fontSize: 20 }}>{decodeEntities(s.title)}</h1>
              <StatusChip s={s.status} />
            </div>
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px 20px', marginTop: 10 }}>
              {s.trailId && (
                <div style={{ display: 'flex', flexDirection: 'column', minWidth: 0, maxWidth: 300 }}>
                  <span className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 2 }}>Trail</span>
                  <span
                    role="button"
                    tabIndex={0}
                    title={'Open this run\u2019s trail: ' + s.trailId}
                    onClick={() => go && go('trails', { sel: s.trailId })}
                    onKeyDown={(e) => { if (e.key === 'Enter') go && go('trails', { sel: s.trailId }); }}
                    style={{ fontSize: 12.5, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', color: 'var(--tb-running)', cursor: 'pointer', textDecoration: 'underline', textDecorationColor: 'rgba(94,155,255,.4)', textUnderlineOffset: 3 }}
                  >{sourceTrail ? sourceTrail.title : s.trailId}</span>
                </div>
              )}
              {[
                ['Target', s.target],
                ['Device', s.device],
                ['Duration', s.dur],
                // Only once the run has stopped: while it executes this trace is as fresh as the
                // last read, and a number frozen at 3 beside a report showing 25 rows reads as a
                // bug in the report.
                ['Steps', s.status !== 'running' && trace.length > 0 ? String(trace.length) : null],
                ['Ran', s.ago],
              ].filter(([, v]) => v).map(([k, v]) => (
                <div key={k} style={{ display: 'flex', flexDirection: 'column', minWidth: 0, maxWidth: 260 }}>
                  <span className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 2 }}>{k}</span>
                  <span style={{ fontSize: 12.5, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{v}</span>
                </div>
              ))}
            </div>
            </div>
          </div>
          <div style={{ display: 'flex', gap: 8, flexShrink: 0 }}>
            {s.trailId && go && (
              <button
                data-testid="open-in-editor"
                className="tb-btn sm"
                title="Open this run's trail in the editor"
                onClick={() => go('trails', { sel: s.trailId, mode: 'edit' })}
              ><Ico n="pencil" s={14} /> Edit trail</button>
            )}
            {s.status === 'running' && onStop && (
              <button
                data-testid="stop-run-detail"
                className="tb-btn sm"
                title="Stop this run"
                onClick={() => onStop(s)}
                disabled={stopping === s.id}
                style={{ borderColor: 'rgba(248,71,82,.4)', color: 'var(--tb-fail)' }}
              ><span style={{ width: 9, height: 9, borderRadius: 2, background: 'var(--tb-fail)', display: 'inline-block' }} /> {stopping === s.id ? 'Stopping…' : 'Stop'}</button>
            )}
            <button
              data-testid="share-run"
              className="tb-btn sm"
              title="Export this run as a standalone, interactive HTML file you can share"
              onClick={() => setShareOpen(true)}
            ><Ico n="share-2" s={14} /> Share</button>
            <button
              ref={menuBtnRef}
              data-testid="run-actions-menu"
              className="tb-btn ghost sm"
              title="Run actions"
              onClick={() => setMenuOpen((o) => !o)}
              style={{ padding: 6 }}
            ><Ico n="ellipsis-vertical" s={16} /></button>
          </div>
        </div>
        {s.err && <ErrorBanner text={s.err} />}
        {retryErr && <ErrorBanner text={retryErr} />}
        <div className="tb-tabs" style={{ marginTop: 16 }}>
          {tabs.map(([id, l]) => (
            <div key={id} className={'tb-tab ' + (mode === id ? 'active' : '')} onClick={() => setMode(id)} style={{ cursor: 'pointer' }}>{l}</div>
          ))}
        </div>
      </div>
      {/* The report and the other tabs are stacked rather than swapped, and the inactive one is
          hidden with `visibility` instead of being unmounted. The report frame follows a live run
          over its own connection and holds the reader's selected step, scroll offset and expanded
          groups; unmounting it — or collapsing its box with `display: none` — would throw that away
          every time someone glanced at Raw logs. */}
      <div style={{ position: 'relative', flex: 1, minHeight: 0 }}>
        {/* 8px of inset is what lines the report's own tab row up with the Report/Raw logs/Artifacts
            row above it — the report supplies the rest of its page padding itself. */}
        <div style={{ position: 'absolute', inset: 0, padding: '4px 8px 0', visibility: mode === 'report' ? 'visible' : 'hidden' }}>
          {/* `s.id` rather than the shared `sessionId`, which prefers the fetched detail and so still
              names the PREVIOUS run for as long as this one's detail is in flight. The frame keys on
              the id it is given, so that lag would show the wrong run's report. */}
          <EmbeddedReport sessionId={s.id} />
        </div>
        {mode !== 'report' && (
          <div style={{ position: 'absolute', inset: 0, padding: '18px 26px', ...(mode === 'logs' ? { display: 'flex', flexDirection: 'column', overflow: 'hidden' } : { overflowY: 'auto' }) }}>
            {showSkeleton && <Skeleton rows={3} />}
            {!showSkeleton && mode === 'logs' && <RawLogs logs={detail.data?.logs || []} sessionId={sessionId} />}
            {mode === 'artifacts' && <ArtifactsPanel sessionId={sessionId} />}
          </div>
        )}
      </div>
      {menuOpen && <ActionsPopover anchor={menuBtnRef.current} items={actionItems} onClose={() => setMenuOpen(false)} />}
      {saveOpen && <SaveAsTrailModal session={s} go={go} onClose={() => setSaveOpen(false)} />}
      {shareOpen && <ShareRunModal s={s} trace={trace} llmLogs={llmLogs} cmd={TbRunPayload.cliRerunCommand(s, sourceTrail)} sessionId={sessionId} onClose={() => setShareOpen(false)} />}
    </div>
  );
}

// Save a completed run's recording as a replayable .trail.yaml in the workspace.
// The exported session YAML IS a trail (config + recorded steps), so we just fetch
// it and write it via the same create-trail endpoint the Trails "new" flow uses.
function SaveAsTrailModal({ session, go, onClose }) {
  useLucide();
  const y = TB.useSessionYaml(session.id);
  const hasYaml = !!(y.data && y.data.trim());
  const defaultSlug = (session.title || session.id || 'run').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'run';
  const [path, setPath] = React.useState(defaultSlug);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState(null);
  const clean = path.trim().replace(/^\/+|\/+$/g, '').replace(/\.trail\.yaml$/, '');
  const save = async () => {
    if (!clean || busy) return;
    if (!hasYaml) { setErr('This run has no recorded steps to save.'); return; }
    setBusy(true); setErr(null);
    const r = await TB.createTrail(clean, y.data);
    setBusy(false);
    if (!r.success) { setErr(r.error || 'Could not save the trail'); return; }
    onClose();
    if (go) go('trails', { sel: '0/' + clean });
  };
  return (
    <div className="tb-overlay" onClick={onClose} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(520px, 94vw)', padding: 24 }}>
        <h2 className="tb-h2" style={{ marginBottom: 6 }}>Save run as trail</h2>
        <p className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5, margin: '0 0 16px' }}>Writes this run's recorded steps as a replayable <span className="tb-mono">.trail.yaml</span> in your workspace. Replays re-run the exact steps - fast, deterministic, no LLM calls.</p>
        <div className="tb-eyebrow" style={{ marginBottom: 6 }}>File path</div>
        <div className="tb-input"><input autoFocus value={path} onChange={(e) => setPath(e.target.value)} onKeyDown={(e) => { if (e.key === 'Enter') save(); }} placeholder="e.g. sample/login/android-phone" /></div>
        <div className="tb-sub" style={{ fontSize: 11, marginTop: 6 }}>Saves <span className="tb-mono">{clean || '<path>'}.trail.yaml</span> · relative to the workspace</div>
        {y.loading ? <div className="tb-sub" style={{ fontSize: 12, marginTop: 10 }}>Loading recording…</div>
          : !hasYaml ? <div style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-amber)' }}>This run captured no replayable steps to save.</div> : null}
        {err ? <div style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-fail)' }}>{err}</div> : null}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 10, marginTop: 18 }}>
          <Btn sm onClick={onClose}>Cancel</Btn>
          <Btn sm kind="primary" ico="save" onClick={save} disabled={busy || !hasYaml || !clean}>{busy ? 'Saving…' : 'Save trail'}</Btn>
        </div>
      </div>
    </div>
  );
}

function ActionsPopover({ anchor, items, onClose }) {
  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  if (!anchor) return null;
  const r = anchor.getBoundingClientRect();
  const W = 188;
  const left = Math.max(8, Math.min(r.right - W, window.innerWidth - W - 8));
  const top = Math.min(r.bottom + 6, window.innerHeight - 230);
  return (
    <>
      <div style={{ position: 'fixed', inset: 0, zIndex: 60 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }}></div>
      <div className="tb-card tb-pop" style={{ position: 'fixed', left, top, zIndex: 61, minWidth: W, padding: 5, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)' }}>
        {items.map((it, i) => it.sep
          ? <div key={i} style={{ height: 1, background: 'var(--tb-hairline)', margin: '4px 6px' }} />
          : (
            <div key={i} data-testid="run-action" className="tb-pal-row" onClick={() => { onClose(); it.fn(); }} style={{ cursor: 'pointer', padding: '7px 10px' }}>
              <Ico n={it.ico} s={15} c={it.danger ? 'var(--tb-fail)' : it.accent ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} />
              <span style={{ fontSize: 13, color: it.danger ? 'var(--tb-fail)' : 'var(--text-standard)' }}>{it.label}</span>
            </div>
          ))}
      </div>
    </>
  );
}

function RawLogs({ logs, sessionId }) {
  const PREVIEW_LINES = 800;
  const [full, setFull] = React.useState(false);
  const text = React.useMemo(() => JSON.stringify(logs, null, 2), [logs]);
  const lines = React.useMemo(() => text.split('\n'), [text]);
  const truncated = !full && lines.length > PREVIEW_LINES;
  const shown = truncated ? lines.slice(0, PREVIEW_LINES).join('\n') : text;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <span className="tb-sub" style={{ fontSize: 11.5 }}>
          {truncated ? `Showing the first ${PREVIEW_LINES.toLocaleString()} of ${lines.length.toLocaleString()} lines` : `${lines.length.toLocaleString()} lines`}
        </span>
        <div style={{ flex: 1 }} />
        {truncated && <Btn sm onClick={() => setFull(true)}>Load all {lines.length.toLocaleString()} lines</Btn>}
        {sessionId && <Btn sm ico="folder-open" title="Reveal this run's log folder in Finder" onClick={() => TB.revealSession(sessionId)}>Open in Finder</Btn>}
      </div>
      <SearchableText text={shown} language="json" fontSize={11.5} />
    </div>
  );
}

function fmtBytes(n) {
  if (n == null) return '';
  if (n < 1024) return n + ' B';
  if (n < 1024 * 1024) return (n / 1024).toFixed(1) + ' KB';
  return (n / (1024 * 1024)).toFixed(1) + ' MB';
}
function artifactIcon(name) {
  const ext = (name.split('.').pop() || '').toLowerCase();
  if (['png', 'webp', 'jpg', 'jpeg', 'gif'].includes(ext)) return 'image';
  if (['mp4', 'mov', 'webm'].includes(ext)) return 'video';
  if (['yaml', 'yml'].includes(ext)) return 'file-code';
  if (ext === 'json') return 'braces';
  if (['txt', 'log'].includes(ext)) return 'file-text';
  return 'file';
}

function ArtifactsPanel({ sessionId }) {
  const files = TB.useSessionFiles(sessionId);
  useLucide();
  const list = files.data || [];
  // Group by the file's folder (server sends session-relative paths like `events/network.ndjson`).
  // Root-level files ('' key) render first with no header; subfolders get a folder header so nested
  // artifacts are grouped instead of hidden. The list arrives path-sorted, so groups stay ordered.
  const groups = React.useMemo(() => {
    const m = new Map();
    for (const f of list) {
      const slash = f.name.lastIndexOf('/');
      const dir = slash < 0 ? '' : f.name.slice(0, slash);
      if (!m.has(dir)) m.set(dir, []);
      m.get(dir).push(f);
    }
    return [...m.entries()];
  }, [list]);
  if (files.loading && !files.data) return <Skeleton rows={5} />;
  if (list.length === 0) return <EmptyState ico="folder-open" title="No artifacts" sub="This run didn't capture any files." />;
  const row = (f, last) => (
    <div
      key={f.name}
      role="button"
      tabIndex={0}
      data-testid="artifact-row"
      title={'Open in the default app: ' + f.name}
      onClick={() => TB.openSessionFile(sessionId, f.name)}
      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); TB.openSessionFile(sessionId, f.name); } }}
      style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '9px 13px', cursor: 'pointer', color: 'var(--text-standard)', background: 'var(--bg-subtle)', borderBottom: !last ? '1px solid var(--tb-hairline)' : 'none' }}
    >
      <Ico n={artifactIcon(f.name)} s={15} c="var(--text-subtle)" />
      <span className="tb-mono" style={{ flex: 1, minWidth: 0, fontSize: 12.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name.includes('/') ? f.name.slice(f.name.lastIndexOf('/') + 1) : f.name}</span>
      <span className="tb-sub" style={{ fontSize: 11, flexShrink: 0 }}>{fmtBytes(f.size)}</span>
      <Ico n="arrow-up-right" s={13} c="var(--text-subtle-variant)" />
    </div>
  );
  return (
    <div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 10 }}>
        <div className="tb-eyebrow">Artifacts · {list.length}</div>
        <span className="tb-sub" style={{ fontSize: 11.5 }}>Files captured on disk for this run - click to open in the default app.</span>
        <div style={{ flex: 1 }} />
        <Btn sm ico="folder-open" title="Reveal this run's folder in Finder" onClick={() => TB.revealSession(sessionId)}>Open in Finder</Btn>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
        {groups.map(([dir, dirFiles]) => (
          <div key={dir || '<root>'}>
            {dir && (
              <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginBottom: 6 }}>
                <Ico n="folder" s={13} c="var(--text-subtle-variant)" />
                <span className="tb-mono tb-sub" style={{ fontSize: 11.5 }}>{dir}/</span>
                <span className="tb-sub" style={{ fontSize: 11 }}>· {dirFiles.length}</span>
              </div>
            )}
            <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden' }}>
              {dirFiles.map((f, i) => row(f, i === dirFiles.length - 1))}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

Object.assign(window, { TraceViewer, EmbeddedReport, ActionsPopover, SaveAsTrailModal, RawLogs, ArtifactsPanel, fmtBytes, artifactIcon });
