// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function TraceViewer({ s, onDeleted, go, listCollapsed, onToggleList, onBack, backLabel, onStop, stopping }) {
  const trailsIndex = TB.useTrails();
  const sourceTrail = s.trailId ? ((trailsIndex.data || []).find((t) => t.id === s.trailId) || null) : null;
  const [mode, setMode] = React.useState('timeline');
  const [retrying, setRetrying] = React.useState(false);
  const [retryErr, setRetryErr] = React.useState(null);
  const detail = TB.useSessionDetail(s.id, s.status === 'running');
  const analytics = TB.useSessionAnalytics(s.id, s.status === 'running');
  const sessionEvents = TB.useSessionEvents(s.id, s.status === 'running');
  // Flatten the per-stream response into one event list interlaced into the timeline,
  // carrying each stream's friendly label onto its events. Captured events have no tab of
  // their own — they only show up woven into the Timeline when a run captured them.
  const streamEvents = React.useMemo(
    () => (sessionEvents.data?.streams || []).flatMap((p) => (p.events || []).map((e) => ({ ...e, label: p.label }))),
    [sessionEvents.data],
  );
  const trace = detail.data?.trace || [];
  const llmLogs = detail.data?.llmLogs || [];
  const sessionId = detail.data?.id || s.id;
  const [step, setStep] = React.useState(0);
  const [deleting, setDeleting] = React.useState(false);
  useLucide();

  React.useEffect(() => {
    if (trace.length > 0) {
      const failedIdx = trace.findIndex((t) => !t.ok);
      setStep(failedIdx >= 0 ? failedIdx + 1 : 1);
    }
  }, [detail.data?.id]);

  React.useEffect(() => {
    if (s.status !== 'running') return;
    const t = setInterval(() => detail.reload(), 1500);
    return () => clearInterval(t);
  }, [s.status, s.id]);

  React.useEffect(() => {
    if (s.status === 'running' && trace.length > 0) setStep(trace.length);
  }, [s.status, trace.length]);

  const detailReady = !!detail.data && detail.data.id === s.id;
  const showSkeleton = detail.loading && !detailReady;

  const [menuOpen, setMenuOpen] = React.useState(false);
  const [saveOpen, setSaveOpen] = React.useState(false);
  const [shareOpen, setShareOpen] = React.useState(false);
  const menuBtnRef = React.useRef(null);
  const doRetry = async () => {
    if (retrying) return;
    setRetryErr(null);
    setRetrying(true);
    const r = await TB.retrySession(s);
    setRetrying(false);
    if (!r.ok) { setRetryErr(r.error || 'Retry failed.'); return; }
    if (go) { TB.recordPendingRun({ title: s.title || s.id, target: s.target, device: s.device }); go('active', { followLive: Date.now() }); }
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
  const tabs = [
    ['timeline', 'Timeline'],
    ['info', 'Info'],
    ['logs', 'Raw logs'],
    ['yaml', 'YAML'],
    ['artifacts', fileCount ? `Artifacts (${fileCount})` : 'Artifacts'],
  ];
  if (llmLogs.length > 0) tabs.splice(2, 0, ['llm', `LLM (${llmLogs.length})`]);

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
                ['Steps', trace.length > 0 ? String(trace.length) : null],
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
      <div style={{ padding: mode === 'timeline' ? '14px 26px 18px' : '18px 26px', flex: 1, minHeight: 0, ...(mode === 'timeline' || mode === 'llm' ? { display: 'flex', overflow: 'hidden' } : (mode === 'logs' || mode === 'yaml') ? { display: 'flex', flexDirection: 'column', overflow: 'hidden' } : { overflowY: 'auto' }) }}>
        {showSkeleton && mode !== 'yaml' && <Skeleton rows={3} />}
        {!showSkeleton && mode === 'timeline' && (
          <Timeline
            trace={trace} step={step} setStep={setStep} sessionId={sessionId}
            analytics={analytics.data?.events || []}
            streamEvents={streamEvents}
          />
        )}
        {mode === 'info' && <InfoPanel s={s} sessionId={sessionId} sourceTrail={sourceTrail} trace={trace} go={go} />}
        {!showSkeleton && mode === 'llm' && <LlmPanel llmLogs={llmLogs} />}
        {!showSkeleton && mode === 'logs' && <RawLogs logs={detail.data?.logs || []} sessionId={sessionId} />}
        {mode === 'yaml' && <SessionYaml sessionId={sessionId} s={s} sourceTrail={sourceTrail} go={go} />}
        {mode === 'artifacts' && <ArtifactsPanel sessionId={sessionId} />}
      </div>
      {menuOpen && <ActionsPopover anchor={menuBtnRef.current} items={actionItems} onClose={() => setMenuOpen(false)} />}
      {saveOpen && <SaveAsTrailModal session={s} go={go} onClose={() => setSaveOpen(false)} />}
      {shareOpen && <ShareRunModal s={s} trace={trace} llmLogs={llmLogs} cmd={cliRerunCommand(s, sourceTrail)} sessionId={sessionId} onClose={() => setShareOpen(false)} />}
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

// Best-effort CLI command to reproduce this run. Replays of an existing trail map to
// `trailblaze run <file>`; ad-hoc objectives (Blaze) map to `trailblaze step "<objective>"`.
// Device is platform-only (the per-device id isn't persisted on the session record).
function cliRerunCommand(s, sourceTrail) {
  const dev = s.platform ? ` --device ${s.platform}` : '';
  if (s.trailId) {
    // `run` reads the target from the trail file's own config.target — it has no
    // --target flag (that lives on `step`/`tool`), so we don't pass one here.
    const path = (sourceTrail && sourceTrail.path) || s.trailId;
    return `trailblaze run ${path}${dev}`;
  }
  // Ad-hoc objective (Blaze): `step` takes the target explicitly. Escape backslashes
  // before quotes so the double-quoted shell argument can't be broken out of.
  const tgt = s.target ? ` --target ${s.target}` : '';
  const objective = (s.title || '').replace(/\\/g, '\\\\').replace(/"/g, '\\"');
  return `trailblaze step "${objective}"${dev}${tgt}`;
}

function CopyableCommand({ text }) {
  const [copied, setCopied] = React.useState(false);
  const copy = () => {
    try { navigator.clipboard.writeText(text); setCopied(true); setTimeout(() => setCopied(false), 1500); } catch (_) {}
  };
  return (
    <div style={{ display: 'flex', alignItems: 'stretch', gap: 8 }}>
      <pre className="tb-mono" data-selectable style={{ flex: 1, minWidth: 0, margin: 0, fontSize: 12.5, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-all', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '10px 12px', color: 'var(--text-standard)' }}>{text}</pre>
      <Btn sm ico={copied ? 'check' : 'copy'} onClick={copy} style={{ flexShrink: 0, alignSelf: 'flex-start' }}>{copied ? 'Copied' : 'Copy'}</Btn>
    </div>
  );
}

function InfoPanel({ s, sessionId, sourceTrail, trace = [], go }) {
  useLucide();
  const cmd = cliRerunCommand(s, sourceTrail);
  // "5.58.0.0 (67500009)" — user-visible version first, internal build/version code in parens.
  const appVersion = s.appVersionName
    ? s.appVersionName + ((s.appBuildNumber || s.appVersionCode) ? ` (${s.appBuildNumber || s.appVersionCode})` : '')
    : (s.appBuildNumber || s.appVersionCode);
  const rows = [
    ['Session', sessionId],
    ['Target', s.target],
    ['App', s.appId],
    ['App version', appVersion],
    ['Device', s.device],
    ['Platform', s.platform],
    ['Trail', s.trailId ? (sourceTrail ? sourceTrail.path || sourceTrail.title : s.trailId) : 'ad-hoc objective (no saved trail)'],
    ['Steps', trace.length > 0 ? String(trace.length) : null],
    ['Ran', s.ago],
    ['Duration', s.dur],
  ].filter(([, v]) => v);
  return (
    <div style={{ maxWidth: 760, display: 'flex', flexDirection: 'column', gap: 22 }}>
      <div>
        <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Rerun this in the CLI</div>
        <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5, marginBottom: 10 }}>
          {s.trailId
            ? 'Replays the same trail file on a device of this platform. Pass a specific device id (e.g. --device ' + (s.platform || 'android') + '/emulator-5554) to target one device.'
            : 'Reconstructed from this run’s objective. Pass a specific device id to --device to target one device.'}
        </div>
        <CopyableCommand text={cmd} />
      </div>
      <div>
        <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Run details</div>
        <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden' }}>
          {rows.map(([k, v], i) => (
            <div key={k} style={{ display: 'flex', alignItems: 'baseline', gap: 12, padding: '9px 13px', background: 'var(--bg-subtle)', borderBottom: i < rows.length - 1 ? '1px solid var(--tb-hairline)' : 'none' }}>
              <span className="tb-sub" style={{ flex: '0 0 90px', fontSize: 11.5 }}>{k}</span>
              {k === 'Trail' && s.trailId
                ? <span role="button" tabIndex={0} title={'Open this run’s trail'} onClick={() => go && go('trails', { sel: s.trailId })} onKeyDown={(e) => { if (e.key === 'Enter') go && go('trails', { sel: s.trailId }); }}
                    className="tb-mono" style={{ fontSize: 12.5, color: 'var(--tb-running)', cursor: 'pointer', textDecoration: 'underline', textDecorationColor: 'rgba(94,155,255,.4)', textUnderlineOffset: 3, wordBreak: 'break-all' }}>{v}</span>
                : <span className={k === 'Session' || k === 'App' ? 'tb-mono' : ''} data-selectable style={{ fontSize: 12.5, color: 'var(--text-standard)', wordBreak: 'break-all' }}>{v}</span>}
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

// The YAML tab. When the run replayed an existing trail, show the original trail YAML
// on the left (linking to the trail) and the recorded run YAML on the right so it's
// obvious which is which. Otherwise just the recorded YAML.
function SessionYaml({ sessionId, s, sourceTrail, go }) {
  const y = TB.useSessionYaml(sessionId);
  const original = TB.useTrailDetail(s && s.trailId ? s.trailId : null);
  const recorded = y.data;
  const hasOriginal = !!(s && s.trailId);

  if (!hasOriginal) {
    if (y.loading) return <div className="tb-skel" style={{ height: 220 }} />;
    if (!recorded) return <EmptyState ico="code" title="No recorded YAML" sub="This session didn't capture replayable steps to render as a trail." />;
    return <SearchableText text={recorded} language="yaml" fontSize={12.5} />;
  }

  const col = (heading, sub, child) => (
    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, flex: '0 0 auto' }}>
        <span className="tb-eyebrow">{heading}</span>
        {sub}
      </div>
      <div style={{ flex: 1, minHeight: 0, overflow: 'auto' }}>{child}</div>
    </div>
  );
  return (
    <div style={{ display: 'flex', gap: 18, flex: 1, minHeight: 0 }}>
      {col(
        'Original trail',
        <span role="button" tabIndex={0} title={'Open this trail: ' + s.trailId}
          onClick={() => go && go('trails', { sel: s.trailId })}
          onKeyDown={(e) => { if (e.key === 'Enter') go && go('trails', { sel: s.trailId }); }}
          style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--tb-running)', cursor: 'pointer', textDecoration: 'underline', textDecorationColor: 'rgba(94,155,255,.4)', textUnderlineOffset: 3, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', minWidth: 0 }}>
          {sourceTrail ? sourceTrail.title : s.trailId} ↗
        </span>,
        original.loading ? <div className="tb-skel" style={{ height: 220 }} />
          : original.data && original.data.yaml ? <SearchableText text={original.data.yaml} language="yaml" fontSize={12} minHeight={200} />
            : <EmptyState ico="code" title="No trail YAML" sub="Couldn't load the original trail file." />,
      )}
      {col(
        'Recorded run',
        <span className="tb-sub" style={{ fontSize: 11 }}>what actually ran</span>,
        y.loading ? <div className="tb-skel" style={{ height: 220 }} />
          : recorded ? <SearchableText text={recorded} language="yaml" fontSize={12} minHeight={200} />
            : <EmptyState ico="code" title="No recorded YAML" sub="This session didn't capture replayable steps." />,
      )}
    </div>
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

Object.assign(window, { TraceViewer, ActionsPopover, SaveAsTrailModal, SessionYaml, InfoPanel, CopyableCommand, cliRerunCommand, RawLogs, ArtifactsPanel, fmtBytes, artifactIcon });
