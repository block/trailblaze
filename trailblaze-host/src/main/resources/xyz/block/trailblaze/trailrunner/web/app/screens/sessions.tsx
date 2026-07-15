// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function conciseError(text) {
  const t = String(text || '');
  // AI objective/verification failures wrap the real reason in an "llmExplanation" field inside a
  // "Failed to successfully run prompt with AI { … }" blob. Surface that human-readable reason
  // rather than the raw wrapper (which otherwise shows as a useless "… with AI {" headline).
  const m = t.match(/"llmExplanation"\s*:\s*"((?:[^"\\]|\\.)*)"/);
  if (m) {
    try { return JSON.parse('"' + m[1] + '"'); } catch (e) { return m[1]; }
  }
  const fail = t.split('\n').map((l) => l.trim()).filter((l) => l.startsWith('failure:')).pop();
  if (fail) return fail.replace(/^failure:\s*/, '');
  // Fall back to the first non-empty line, but drop a dangling opening brace so we never show a
  // headline that ends in "{".
  const firstReal = t.split('\n').map((l) => l.trim()).find(Boolean) || '';
  return firstReal.replace(/\s*\{\s*$/, '');
}

function ErrorBanner({ text }) {
  const [copied, setCopied] = React.useState(false);
  const [showFull, setShowFull] = React.useState(false);
  const copy = () => {
    navigator.clipboard?.writeText(text || '').then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); });
  };
  const headline = conciseError(text);
  const full = String(text || '').trim();
  // Collapsed by default: just the key failure reason (wrapped, never truncated). The full
  // message + stack lives behind "View full error", same as the raw-log viewer.
  const hasMore = full && full !== headline;
  return (
    <>
      <div style={{ marginTop: 12, background: 'rgba(248,71,82,.1)', border: '1px solid rgba(248,71,82,.25)', borderRadius: 9 }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 9, padding: '11px 10px 11px 13px' }}>
          <Ico n="triangle-alert" s={15} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
          <span data-selectable style={{ fontSize: 13, fontWeight: 600, color: 'var(--tb-danger-text)', flex: 1, minWidth: 0, lineHeight: 1.5, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{headline}</span>
          {hasMore && <Btn sm ico="file-text" onClick={() => setShowFull(true)} style={{ flex: '0 0 auto' }}>View full error</Btn>}
          <Btn sm ico="copy" onClick={copy} title="Copy the full error + stack trace" style={{ flex: '0 0 auto' }}>{copied ? 'Copied!' : 'Copy'}</Btn>
        </div>
      </div>
      {showFull && <ErrorModal text={full} onClose={() => setShowFull(false)} />}
    </>
  );
}

// Full failure message + stack, shown on demand from the error banner.
function ErrorModal({ text, onClose }) {
  useLucide();
  const [copied, setCopied] = React.useState(false);
  const copy = () => { navigator.clipboard?.writeText(text || '').then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); }); };
  return (
    <div className="tb-overlay" onClick={onClose} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(820px, 94vw)', maxHeight: '86vh', display: 'flex', flexDirection: 'column', padding: 0, overflow: 'hidden' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '14px 16px', borderBottom: '1px solid var(--tb-hairline)' }}>
          <Ico n="triangle-alert" s={16} c="var(--tb-danger-text)" />
          <div style={{ fontSize: 14, fontWeight: 700, flex: 1 }}>Full error</div>
          <Btn sm ico="copy" onClick={copy}>{copied ? 'Copied!' : 'Copy'}</Btn>
          <Btn sm ico="x" onClick={onClose}>Close</Btn>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '12px 14px' }}>
          <SearchableText text={text} fontSize={11.5} />
        </div>
      </div>
    </div>
  );
}

function SessionsScreen({ initSel, followLive, go, view = 'completed', target = 'all', active = true }) {
  const [sel, setSel] = React.useState(initSel || null);
  const [filter, setFilter] = React.useState('');
  const [menu, setMenu] = React.useState(null);
  const sessions = TB.useSessions();
  const lockedRef = React.useRef(false);
  const attemptsRef = React.useRef(0);
  const handedOffRef = React.useRef(false);
  const [listCollapsed, setListCollapsed] = React.useState(false);
  const [listW, startListDrag] = useResizableWidth('tb-runs-list-w', 340, 240, 600);
  const archiveInputRef = React.useRef(null);
  const [importingArchive, setImportingArchive] = React.useState(false);
  const [importNotice, setImportNotice] = React.useState(null);
  const [clearingRuns, setClearingRuns] = React.useState(false);
  useLucide();

  const all = sessions.data || [];
  const running = all.filter((s) => s.status === 'running');
  const completed = all.filter((s) => s.status !== 'running');
  const baseList = view === 'active' ? running : view === 'all' ? all : completed;
  const byTarget = target === '__none__'
    ? baseList.filter((s) => !s.target)
    : target && target !== 'all'
    ? baseList.filter((s) => String(s.target || '').toLowerCase() === String(target).toLowerCase())
    : baseList;
  const pool = byTarget;
  const filtered = !filter ? pool : pool.filter((s) => (s.title + ' ' + (s.target || '') + ' ' + (s.device || '')).toLowerCase().includes(filter.toLowerCase()));
  const cur = all.find((s) => s.id === sel);

  const pending = (view === 'active' || view === 'all') ? TB.getPendingRun() : null;
  // Errored markers are exempt from the TTL: a "couldn't start" card that silently evaporates
  // after 90s reads as "the run never happened" - it stays until dismissed or a new run starts.
  const showPending = !!pending && running.length === 0 && (pending.error || (Date.now() - pending.at) < 90000);
  // The detail pane gets its own gate: keep the placeholder up until a CONCRETE run
  // is selected (`!cur`), not just until one is running. followLive locks `sel` onto
  // the new session one render AFTER it lands in `running`, so gating on
  // `running.length === 0` (like the list card) would blink "Select a run" for that
  // one frame. Keying on `!cur` holds the placeholder right through the hand-off to
  // the live TraceViewer.
  const showPendingDetail = !!pending && !cur && (pending.error || (Date.now() - pending.at) < 90000);
  // Clear the optimistic marker once a concrete run is locked onto (`cur` set) — the
  // SAME condition the detail pane hands off on. Clearing on `running.length > 0`
  // instead would drop the marker one render before `sel`/`cur` catches up, blinking
  // the empty "Select a run" state during the hand-off (the flicker this stack kills).
  React.useEffect(() => { if (cur) TB.clearPendingRun(); }, [!!cur]);

  // Re-apply the routed selection whenever it changes — screens stay mounted, so a
  // deep-link to a different run (Home → recent run, or the Active → Completed hand-off
  // below) must move the selection, not just on first mount.
  React.useEffect(() => {
    if (initSel) setSel(initSel);
  }, [initSel]);

  // Refetch the list each time this tab becomes visible again. Each SessionsScreen
  // keeps its own useSessions instance and stays mounted (display:none), and the poll
  // only runs while its own data has a running row — so a sibling tab can hold data
  // that predates a run which just finished elsewhere. The clearest hit: a run followed
  // in Active hands off to Completed (`go('completed', {sel})`), but Completed's stale
  // list has no row for it, so `cur` is undefined and the run looks like it vanished.
  // Reloading on show is the cheap, self-healing fix (one fetch per tab activation).
  const wasActiveRef = React.useRef(active);
  React.useEffect(() => {
    if (active && !wasActiveRef.current) sessions.reload();
    wasActiveRef.current = active;
  }, [active]);

  React.useEffect(() => {
    if (!sel && pool.length > 0) setSel(pool[0].id);
  }, [sessions.data, view]);

  React.useEffect(() => {
    if (followLive) { lockedRef.current = false; attemptsRef.current = 0; handedOffRef.current = false; }
  }, [followLive]);

  // Hand off to Completed once a live-followed run finishes. Without this, a run we
  // locked onto in Active drops out of the `running` list when it ends (failed/passed)
  // — the Active list goes empty but the detail stays pinned to it, stranding you in
  // Active. Especially visible for "fail to run" cases that finish almost immediately.
  React.useEffect(() => {
    if (view !== 'active' || handedOffRef.current || !lockedRef.current) return;
    if (cur && cur.status && cur.status !== 'running') {
      handedOffRef.current = true;
      TB.clearPendingRun();
      const id = cur.id;
      setSel(null);
      if (go) go('completed', { sel: id });
    }
  }, [view, cur && cur.id, cur && cur.status]);

  React.useEffect(() => {
    if (!followLive || lockedRef.current) return;
    const tick = () => {
      if (lockedRef.current) return;
      const data = sessions.data || [];
      // Authoritative path: dispatch reported the real session id (patched onto the pending
      // marker as soon as the RPC answers). Lock straight onto it — no guessing — and consider
      // it locked once the row actually exists so the Completed hand-off can fire even when the
      // run finished before we ever saw it as `running`.
      const p = TB.getPendingRun();
      if (p && p.sessionId) {
        setSel(p.sessionId);
        if (data.some((s) => s.id === p.sessionId)) { lockedRef.current = true; return; }
        // Watchdog: the daemon accepted the run (it minted a session id) but the session never
        // materialized - the run died before writing its first log. Without this, the card just
        // sits on "Initializing run…" until the TTL erases it without a word.
        if (attemptsRef.current > 20) {
          if (!p.error) TB.failPendingRun('The daemon accepted the run (session ' + p.sessionId + ') but it never appeared. It likely died before starting - check the daemon log (~/.trailblaze/desktop-logs/).');
          lockedRef.current = true;
          return;
        }
        attemptsRef.current += 1;
        sessions.reload();
        return;
      }
      // Legacy heuristic (dispatch paths that don't report a session id yet): newest running row.
      const newest = data[0];
      if (newest && newest.status === 'running') { setSel(newest.id); lockedRef.current = true; return; }
      if (attemptsRef.current > 16) { if (newest) setSel(newest.id); lockedRef.current = true; return; }
      attemptsRef.current += 1;
      sessions.reload();
    };
    tick();
    // Keep ticking even when a reload returns reference-identical data (useFetched keeps the
    // previous array on no-op refreshes, so this effect wouldn't re-run) — the late-arriving
    // sessionId patch and the row's first appearance both need a re-check, not a re-render.
    const t = setInterval(tick, 1200);
    return () => clearInterval(t);
  }, [followLive, sessions.data]);

  // Bridge poll: only while we're actively waiting for a just-dispatched run to show
  // up — there's an optimistic pending marker AND nothing is running yet. Once a run
  // is live, useSessions' own auto-poll (data.jsx) takes over; once the marker clears
  // (run locked on, or TTL) we stop. This avoids both a perpetual idle-tab poll and a
  // double-poll racing useSessions' interval while a run is in flight.
  React.useEffect(() => {
    if (view !== 'active' || !pending || running.length > 0) return;
    const id = setInterval(() => sessions.reload(), 3000);
    return () => clearInterval(id);
  }, [view, !!pending, running.length]);

  const title = view === 'active' ? 'Active' : view === 'all' ? 'Runs' : 'Completed';
  const sub = view === 'active' ? 'Runs in flight right now' : view === 'all' ? 'All runs · live and finished, newest first' : 'Finished runs · newest first';

  const onAfterDelete = (deletedId) => {
    if (deletedId === sel) { setSel(null); setListCollapsed(false); }
    sessions.reload();
  };

  const onArchivePicked = async (e) => {
    const file = e.target.files && e.target.files[0];
    e.target.value = '';
    if (!file || importingArchive) return;
    setImportingArchive(true);
    setImportNotice(null);
    const r = await TB.importSessionArchive(file);
    setImportingArchive(false);
    if (r.ok) {
      setImportNotice({ ok: true, text: `Imported ${r.sessionId || 'session archive'}${r.fileCount ? ` (${r.fileCount} files)` : ''}.` });
      sessions.reload();
      if (r.sessionId && !/ sessions$/.test(r.sessionId)) setSel(r.sessionId);
    } else {
      setImportNotice({ ok: false, text: r.error || 'Could not import archive.' });
    }
  };

  const onClearRuns = async () => {
    const count = all.length;
    if (!window.confirm(`Delete ${count || 'all'} run${count === 1 ? '' : 's'}? This cannot be undone.`)) return;
    setClearingRuns(true);
    const r = await TB.clearSessions();
    setClearingRuns(false);
    if (!r || !r.ok) {
      setImportNotice({ ok: false, text: (r && r.error) || 'Could not clear runs.' });
      return;
    }
    setSel(null);
    sessions.reload();
    setImportNotice({ ok: true, text: r.deleted ? `Deleted ${r.deleted} run${r.deleted === 1 ? '' : 's'}.` : 'No runs to delete.' });
  };

  const [stopping, setStopping] = React.useState(null);
  const [showHelp, setShowHelp] = React.useState(false);
  // In-app confirm instead of the native window.confirm/alert pair - the browser dialog reads as
  // a crack in the app, and its error twin ate the real cancellation reason.
  const [confirmStop, setConfirmStop] = React.useState(null);
  // Outcome of the stop attempt, shown in the same dialog the user acted in - a silent no-op stop
  // (cancel raced a fast finish, or nothing was running) must never look like a successful stop.
  const [stopOutcome, setStopOutcome] = React.useState(null);
  // Live view of the run in the dialog: sessions keep polling, so a run that finishes while the
  // confirm is open re-labels the dialog instead of offering a Stop that can only no-op.
  const liveConfirm = confirmStop ? all.find((s) => s.id === confirmStop.id) : null;
  const confirmEnded = !!(liveConfirm && liveConfirm.status !== 'running');
  const closeConfirm = () => { setConfirmStop(null); setStopOutcome(null); };
  // The dialog can outlive its moment two ways: the cancel RPC blocks while a wedged driver
  // closes (~90s seen live), and this screen is keep-mounted so a hidden tab still holds the
  // dialog state. Guard both: a late resolution only lands if THIS run's dialog is still open,
  // and hiding the tab dismisses the dialog outright.
  const confirmRef = React.useRef(null);
  confirmRef.current = confirmStop;
  React.useEffect(() => { if (!active) closeConfirm(); }, [active]);
  const onStop = (run) => {
    if (!run || stopping) return;
    setStopOutcome(null);
    setConfirmStop(run);
  };
  const doStop = async (run) => {
    setStopping(run.id);
    const r = await TB.cancelSession(run.id);
    sessions.reload();
    setStopping(null);
    if (!confirmRef.current || confirmRef.current.id !== run.id) return; // dialog dismissed - don't re-pop
    setStopOutcome(r.ok
      ? r.reason === 'released_device'
        ? { ok: true, text: 'This run had already ended but was still holding its device - the device has been released for the next run.' }
        : { ok: true, text: 'Run stopped. It will show as Cancelled under Completed.' }
      : r.reason === 'already_ended'
        ? { ok: true, text: 'This run had already finished before the stop landed - its recorded result is unchanged.' }
        : { ok: false, text: 'Nothing was stopped: no live execution was found for this run. It may have just finished; the list has been refreshed.' });
  };

  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%' }}>
      {confirmStop && (
        <div onClick={closeConfirm} style={{ position: 'fixed', inset: 0, zIndex: 90, background: 'rgba(0,0,0,.55)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(380px, 92vw)', padding: 16, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13.5, fontWeight: 700 }}>
              {stopOutcome
                ? <React.Fragment><Ico n={stopOutcome.ok ? 'check' : 'triangle-alert'} s={15} c={stopOutcome.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)'} /> {stopOutcome.ok ? 'Stop result' : 'Nothing to stop'}</React.Fragment>
                : confirmEnded
                  ? <React.Fragment><Ico n="check" s={15} c="var(--tb-pass)" /> Run already finished</React.Fragment>
                  : <React.Fragment><Ico n="octagon-x" s={15} c="var(--tb-danger-text)" /> Stop this run?</React.Fragment>}
            </div>
            <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5, marginTop: 8, wordBreak: 'break-word' }}>
              {stopOutcome
                ? stopOutcome.text
                : confirmEnded
                  ? <React.Fragment>“{decodeEntities(confirmStop.title)}” finished while this dialog was open ({(liveConfirm && liveConfirm.status) || 'done'}) - there is nothing left to stop.</React.Fragment>
                  : <React.Fragment>“{decodeEntities(confirmStop.title)}” will be cancelled. Anything it already recorded stays in its logs.</React.Fragment>}
            </div>
            <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 14 }}>
              {(stopOutcome || confirmEnded)
                ? <Btn sm onClick={closeConfirm}>Close</Btn>
                : <React.Fragment>
                    <Btn sm onClick={closeConfirm} disabled={stopping === confirmStop.id}>Keep running</Btn>
                    <Btn sm kind="danger" ico="octagon-x" onClick={() => doStop(confirmStop)} disabled={stopping === confirmStop.id}>{stopping === confirmStop.id ? 'Stopping…' : 'Stop run'}</Btn>
                  </React.Fragment>}
            </div>
          </div>
        </div>
      )}
      <div style={{ position: 'relative', width: listCollapsed ? 0 : listW, flex: listCollapsed ? '0 0 0px' : '0 0 ' + listW + 'px', borderRight: listCollapsed ? 'none' : '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', overflow: 'hidden', transition: 'width .2s var(--ease-out-soft), flex-basis .2s var(--ease-out-soft)' }}>
        <RailHeader
          ico={view === 'active' ? 'radio' : 'history'}
          iconColor={view === 'active' ? 'var(--tb-running)' : 'var(--text-subtle-variant)'}
          title={<React.Fragment>{view === 'active' && running.length > 0 ? <Dot c="var(--tb-pass)" s={7} cls="tb-pulse" /> : null}<span style={{ marginLeft: view === 'active' && running.length > 0 ? 6 : 0 }}>{title}</span></React.Fragment>}
          sub={sub}
          help={<HelpButton title={view === 'active' ? 'How runs work' : 'Reading a trace'} onClick={() => setShowHelp(true)} />} />
        <div style={{ padding: '0 12px 10px' }}>
          <div className="tb-input">
            <Ico n="search" s={14} />
            <input placeholder="Filter runs…" value={filter} onChange={(e) => setFilter(e.target.value)} />
          </div>
          <input
            ref={archiveInputRef}
            type="file"
            accept=".zip,application/zip"
            onChange={onArchivePicked}
            style={{ display: 'none' }}
          />
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8 }}>
            <Btn sm ico="archive-restore" onClick={() => archiveInputRef.current?.click()} disabled={importingArchive}>
              {importingArchive ? 'Importing...' : 'Import archive'}
            </Btn>
            <Btn sm ico="folder-open" onClick={() => TB.revealLogsRoot()} title="Open the logs folder that contains every run">
              Open logs
            </Btn>
            <Btn sm kind="danger" ico="trash-2" onClick={onClearRuns} disabled={clearingRuns || all.length === 0} title="Delete all run logs">
              {clearingRuns ? 'Clearing...' : 'Clear all'}
            </Btn>
            {importNotice ? (
              <span
                className="tb-sub"
                style={{
                  minWidth: 0,
                  overflow: 'hidden',
                  textOverflow: 'ellipsis',
                  whiteSpace: 'nowrap',
                  color: importNotice.ok ? 'var(--tb-pass)' : 'var(--tb-danger-text)',
                }}
                title={importNotice.text}
              >
                {importNotice.text}
              </span>
            ) : null}
          </div>
        </div>
        <div data-testid="sessions-list" tabIndex={0} style={{ flex: 1, overflowY: 'auto', padding: '0 10px 12px', outline: 'none' }}
          onKeyDown={(e) => listNavKeyDown(e, { index: filtered.findIndex((x) => x.id === sel), count: filtered.length, set: (i) => setSel(filtered[i].id) })}>
          {sessions.loading && !sessions.data && !showPending && <div style={{ padding: 12 }}><Skeleton rows={4} /></div>}
          {showPending && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '11px 12px', borderRadius: 9, marginBottom: 6, background: 'var(--bg-prominent)', border: '1px solid ' + (pending.error ? 'rgba(248,71,82,.35)' : 'var(--tb-hairline-strong)') }}>
              {pending.error ? <Ico n="circle-x" s={15} c="var(--tb-fail)" style={{ flex: '0 0 auto' }} /> : <Dot c="var(--tb-running)" s={8} cls="tb-pulse" />}
              <div style={{ minWidth: 0, flex: 1 }}>
                <div style={{ fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{pending.title}</div>
                <div className="tb-sub" style={{ fontSize: 11, marginTop: 2, color: pending.error ? 'var(--tb-danger-text)' : undefined }}>{pending.error || 'Initializing - starting the run…'}</div>
              </div>
              {pending.error
                ? <Btn sm ico="x" title="Dismiss" onClick={() => { TB.clearPendingRun(); sessions.reload(); }}>Dismiss</Btn>
                : <Btn sm ico="octagon-x" title="Stop this run before it starts" onClick={async () => { await stopPendingRun(); sessions.reload(); }}>Stop</Btn>}
            </div>
          )}
          {filtered.length > 0 && (
            <>
              <div className="tb-eyebrow" style={{ padding: '8px 4px 4px' }}>{title} · {filtered.length}</div>
              {filtered.map((s) => (
                <SessionRow key={s.id} s={s} sel={sel} setSel={setSel} onMenu={(e) => setMenu({ x: e.clientX, y: e.clientY, run: s })} onStop={onStop} stopping={stopping} />
              ))}
            </>
          )}
        </div>
        {(sessions.data || !sessions.loading) && filtered.length === 0 && !showPending && (
          // Centered in the full column height (not just the list area) so it
          // lines up with the "Select a run" placeholder in the detail pane.
          // pointerEvents:none keeps the header filter clickable underneath.
          <div style={{ position: 'absolute', inset: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', pointerEvents: 'none' }}>
            <EmptyState
              ico={view === 'active' ? 'radio' : 'history'}
              title={view === 'active' ? 'No active runs' : 'No completed runs yet'}
              sub={view === 'active' ? 'Runs appear here live while they execute.' : 'Run a trail to see results here.'}
            />
          </div>
        )}
      </div>
      {showHelp && view === 'active' && (
        <HelpOverlay
          title="How runs work"
          sub="A run (a 'session') is one trail dispatched to one device. The daemon executes it step by step; this screen follows it live."
          onClose={() => setShowHelp(false)}
        >
          <HelpCard ico="send" color="var(--tb-running)" title="Dispatch">
            Runs start from the Prompt screen, a trail's Run button, or a tool's Test tab. The daemon connects to the device, launches the target app, and works through the steps - recorded steps replay exactly; AI steps go through the agent loop.
          </HelpCard>
          <HelpCard ico="radio" color="var(--tb-pass)" title="Follow it live">
            Each step appears here as it executes, with status and screenshots arriving in real time. You don't have to keep watching - the run continues on the daemon either way.
          </HelpCard>
          <HelpCard ico="octagon-x" color="var(--tb-fail)" title="Stop">
            Stop cancels the run on the device; whatever was already executed keeps its trace, so a stopped run is still inspectable under Completed.
          </HelpCard>
        </HelpOverlay>
      )}
      {showHelp && view !== 'active' && (
        <HelpOverlay
          title="Reading a trace"
          sub="Every run keeps a full trace - enough to understand what happened without re-running it. Pick a run and work through the tabs."
          onClose={() => setShowHelp(false)}
        >
          <HelpCard ico="gallery-vertical-end" color="var(--tb-running)" title="Timeline">
            The run step by step: screenshot, the tool calls performed, and whether each step was replayed from a recording or decided by the agent. This is where you diagnose what the device actually did.
          </HelpCard>
          <HelpCard ico="cpu" color="var(--tb-pass)" title="LLM">
            Every model call the run made: the system prompt, the objective, the screen state the model saw, and the action it chose - plus token counts and cost. If the agent did something odd, the why is here.
          </HelpCard>
          <HelpCard ico="file-text" color="var(--text-subtle)" title="Raw logs · YAML · Artifacts">
            Raw logs is the daemon's full log stream. YAML is the run exported as a recorded trail - what "Use new recording" loads into the editor. Artifacts holds screenshots, video, and any captured files; click one to open it.
          </HelpCard>
          <HelpCard ico="rotate-cw" color="var(--tb-amber)" title="Retry & adopt">
            Retry re-dispatches the same trail. If an AI run produced a good recording, open the trail in the editor and use the captured recording so future runs replay deterministically.
          </HelpCard>
        </HelpOverlay>
      )}
      {!listCollapsed && <Splitter onDown={startListDrag} />}
      <div style={{ flex: 1, minWidth: 0, display: 'flex', overflow: 'hidden' }}>
        {cur
          ? <TraceViewer s={cur} onDeleted={() => onAfterDelete(cur.id)} go={go} listCollapsed={listCollapsed} onToggleList={() => setListCollapsed((c) => !c)} onStop={onStop} stopping={stopping} />
          : showPendingDetail
          ? <PendingRunDetail pending={pending} onDismiss={() => sessions.reload()} />
          : (sel && sessions.loading)
          // A run is selected but not yet in the list (e.g. the Active → Completed
          // hand-off, where this tab is still reloading its stale list). Hold a skeleton
          // rather than blinking "Select a run" until the refetched row lands as `cur`.
          ? (
            <div style={{ flex: 1, minWidth: 0, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <div style={{ width: 'min(420px, 70%)' }}><Skeleton rows={5} /></div>
            </div>
          )
          : (
            <div style={{ flex: 1, minWidth: 0, height: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
              <EmptyState ico="gallery-vertical-end" title="Select a run" sub="Pick a run to open its trace - per-step screenshots, view hierarchy, recorded tools, YAML, and the LLM transcript." />
            </div>
          )}
      </div>
      <RunContextMenu menu={menu} onClose={() => setMenu(null)} go={go} onAfterDelete={onAfterDelete} />
    </div>
  );
}

// Stop a run that hasn't materialized as a session row yet (still "Initializing"). Best-effort
// cancel when dispatch already reported a session id, then flip the optimistic marker to an
// errored (sticky) state so the outcome is explicit rather than the card silently evaporating.
// Without this, a run wedged during startup has NO stop affordance anywhere - the row-level
// Stop only exists once the session row appears.
async function stopPendingRun() {
  const p = TB.getPendingRun();
  if (!p) return;
  if (p.sessionId) await TB.cancelSession(p.sessionId).catch(() => null);
  TB.failPendingRun('Stopped by you while initializing. Anything the run already logged stays in its session.');
}

function PendingRunDetail({ pending, onDismiss }) {
  useLucide();
  const failed = !!pending.error;
  const meta = [['Target', pending.target ? TB.humanizeTarget(pending.target) : null], ['Device', pending.device]].filter(([, v]) => v);
  return (
    <div className="tb-in" data-testid="pending-run-detail" style={{ display: 'flex', flexDirection: 'column', height: '100%', minHeight: 0, width: '100%' }}>
      <div style={{ padding: '18px 26px 0', flex: '0 0 auto' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <h1 className="tb-h1" style={{ fontSize: 20, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{pending.title}</h1>
          {failed
            ? <span className="tb-chip red" style={{ flex: '0 0 auto', display: 'inline-flex', alignItems: 'center', gap: 6 }}><Ico n="circle-x" s={13} c="var(--tb-fail)" />Couldn't start</span>
            : <span className="tb-chip blue" style={{ flex: '0 0 auto', display: 'inline-flex', alignItems: 'center', gap: 6 }}><Dot c="var(--tb-running)" s={7} cls="tb-pulse" />Starting…</span>}
        </div>
        {meta.length > 0 && (
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: '8px 20px', marginTop: 10 }}>
            {meta.map(([k, v]) => (
              <div key={k} style={{ display: 'flex', flexDirection: 'column', minWidth: 0, maxWidth: 260 }}>
                <span className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 2 }}>{k}</span>
                <span style={{ fontSize: 12.5, fontWeight: 500, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{v}</span>
              </div>
            ))}
          </div>
        )}
      </div>
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16, padding: 24 }}>
        <div style={{ width: 54, height: 54, borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg-elevated)', border: '1px solid ' + (failed ? 'rgba(248,71,82,.3)' : 'var(--tb-hairline)') }}>
          {failed ? <Ico n="circle-x" s={24} c="var(--tb-fail)" /> : <Dot c="var(--tb-running)" s={13} cls="tb-pulse" />}
        </div>
        <div style={{ textAlign: 'center' }}>
          <div style={{ fontSize: 14.5, fontWeight: 600, color: failed ? 'var(--tb-danger-text)' : 'var(--text-standard)' }}>{failed ? "Couldn't start the run" : 'Initializing run…'}</div>
          <div className="tb-sub" style={{ fontSize: 12.5, marginTop: 5, maxWidth: 360, lineHeight: 1.5 }}>{failed ? pending.error : 'Connecting to the device and launching the trail. The live trace will appear here as the first steps run.'}</div>
        </div>
        {failed ? (
          <Btn sm ico="x" onClick={() => { TB.clearPendingRun(); if (onDismiss) onDismiss(); }}>Dismiss</Btn>
        ) : (
        <React.Fragment>
          <div style={{ width: 240, maxWidth: '60%' }}>
            <div className="tb-skel" style={{ height: 9, width: '100%', marginBottom: 8 }} />
            <div className="tb-skel" style={{ height: 9, width: '78%', marginBottom: 8 }} />
            <div className="tb-skel" style={{ height: 9, width: '88%' }} />
          </div>
          <Btn sm kind="danger" ico="octagon-x" title="Stop this run before it starts" onClick={async () => { await stopPendingRun(); if (onDismiss) onDismiss(); }}>Stop run</Btn>
        </React.Fragment>
        )}
      </div>
    </div>
  );
}

// Classify a run so the row can show what kind of thing it was at a glance.
function runKindOf(s) {
  const t = s.title || '';
  if (/^Blaze:/.test(t)) return { label: 'Blaze', ico: 'sparkles', color: 'var(--tb-ai)' };
  if (s.trailId) return { label: 'Trail', ico: 'route', color: 'var(--tb-pass)' };
  if (/^OnDeviceRpc|^Run:|tool_/.test(t)) return { label: 'Tool', ico: 'wrench', color: 'var(--tb-amber)' };
  return { label: 'Run', ico: 'gallery-vertical-end', color: 'var(--text-subtle-variant)' };
}
function cleanRunTitle(s) {
  return decodeEntities((s.title || s.id || '').replace(/^(Blaze|OnDeviceRpc|Run):\s*/, '') || s.id);
}

function SessionRow({ s, sel, setSel, onMenu, onStop, stopping }) {
  useLucide();
  const active = sel === s.id;
  const isStopping = stopping === s.id;
  const kind = runKindOf(s);
  const trailName = s.trailId ? (s.trailId.split('/').pop().replace(/\.trail\.yaml$/, '') || s.trailId) : null;
  return (
    <div data-navrow
      data-testid="session-row"
      onClick={() => setSel(s.id)}
      onContextMenu={(e) => { e.preventDefault(); if (onMenu) onMenu(e); }}
      style={{
        padding: '11px 12px',
        borderRadius: 10,
        marginBottom: 4,
        cursor: 'pointer',
        background: active ? 'var(--bg-prominent)' : 'transparent',
        border: '1px solid ' + (active ? 'var(--tb-hairline-strong)' : 'transparent'),
      }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Ico n={kind.ico} s={13} c={kind.color} style={{ flex: '0 0 auto' }} />
        <span style={{ fontSize: 13, fontWeight: 600, flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{cleanRunTitle(s)}</span>
        {s.status === 'running' && onStop && (
          <button
            data-testid="stop-run"
            title="Stop this run"
            onClick={(e) => { e.stopPropagation(); onStop(s); }}
            disabled={isStopping}
            style={{ flex: '0 0 auto', display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 22, height: 22, borderRadius: 6, border: '1px solid rgba(248,71,82,.3)', background: 'rgba(248,71,82,.12)', cursor: isStopping ? 'default' : 'pointer', opacity: isStopping ? 0.5 : 1 }}
          >
            <span style={{ width: 8, height: 8, borderRadius: 2, background: 'var(--tb-fail)' }} />
          </button>
        )}
        {/* End-state token, top-right corner */}
        <span style={{ flex: '0 0 auto' }}><StatusChip s={s.status} /></span>
      </div>
      <div className="tb-sub" style={{ fontSize: 11, marginTop: 4, marginLeft: 21, display: 'flex', alignItems: 'center', gap: 7 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, flex: 1, minWidth: 0, overflow: 'hidden' }}>
          <span style={{ color: kind.color, fontWeight: 600, flex: '0 0 auto' }}>{kind.label}</span>
          {(s.device || s.platform) ? <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0, flex: '0 1 auto', overflow: 'hidden' }}>{s.platform ? <PlatformGlyph platform={s.platform} s={12} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} /> : <Ico n="smartphone" s={11} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />}<span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.device || s.platform}</span></span> : null}
          {s.target ? <span style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--tb-running)', fontWeight: 600, flex: '0 0 auto' }}><Ico n="package" s={11} c="var(--tb-running)" />{s.target}</span> : null}
          {trailName ? <span title={s.trailId} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, minWidth: 0, flex: '0 1 auto', overflow: 'hidden' }}><Ico n="route" s={11} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} /><span className="tb-mono" style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{trailName}</span></span> : null}
          {s.imported ? <span title="Imported session archive" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--tb-amber)', fontWeight: 600, flex: '0 0 auto' }}><Ico n="archive-restore" s={11} c="var(--tb-amber)" />Imported</span> : null}
        </div>
        {/* Times, directly under the token */}
        <span style={{ flex: '0 0 auto', whiteSpace: 'nowrap', fontVariantNumeric: 'tabular-nums' }}>{[s.dur && s.dur !== '—' ? s.dur : null, s.ago].filter(Boolean).join(' · ')}</span>
      </div>
    </div>
  );
}

function RunContextMenu({ menu, onClose, go, onAfterDelete }) {
  useLucide();
  React.useEffect(() => {
    if (!menu) return;
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [menu]);
  if (!menu) return null;
  const { x, y, run } = menu;

  const doExport = () => {
    onClose();
    const a = document.createElement('a');
    a.href = TB.exportSessionUrl(run.id);
    a.download = '';
    document.body.appendChild(a); a.click(); a.remove();
  };
  const doExportArchive = () => {
    onClose();
    const a = document.createElement('a');
    a.href = TB.sessionArchiveUrl(run.id);
    a.download = '';
    document.body.appendChild(a); a.click(); a.remove();
  };
  const doReveal = async () => { onClose(); await TB.revealSession(run.id); };
  const doRetry = async () => {
    onClose();
    const r = await TB.retrySession(run);
    if (r.ok && go) { TB.recordPendingRun({ title: run.title || run.id, target: run.target, device: run.device }); go('active', { followLive: Date.now() }); }
  };
  const doDelete = async () => {
    onClose();
    if (!window.confirm('Delete this run? This cannot be undone.')) return;
    const r = await TB.deleteSession(run.id);
    if (r.ok) onAfterDelete(run.id);
  };

  const items = [
    { ico: 'download', label: 'Export YAML', fn: doExport },
    { ico: 'archive', label: 'Export archive', fn: doExportArchive },
    { ico: 'folder-open', label: 'Open in Finder', fn: doReveal },
    { ico: 'rotate-cw', label: 'Retry', fn: doRetry },
    { sep: true },
    { ico: 'trash-2', label: 'Delete', fn: doDelete, danger: true },
  ];
  const left = Math.min(x, window.innerWidth - 220);
  const top = Math.min(y, window.innerHeight - 200);

  return (
    <>
      <div style={{ position: 'fixed', inset: 0, zIndex: 60 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }}></div>
      <div className="tb-card tb-pop" style={{ position: 'fixed', left, top, zIndex: 61, minWidth: 196, padding: 5, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)' }}>
        {items.map((it, i) => it.sep
          ? <div key={i} style={{ height: 1, background: 'var(--tb-hairline)', margin: '4px 6px' }} />
          : (
            <div key={i} className="tb-pal-row" onClick={it.fn} style={{ cursor: 'pointer', padding: '7px 10px' }}>
              <Ico n={it.ico} s={15} c={it.danger ? 'var(--tb-fail)' : 'var(--text-subtle-variant)'} />
              <span style={{ fontSize: 13, color: it.danger ? 'var(--tb-fail)' : 'var(--text-standard)' }}>{it.label}</span>
            </div>
          ))}
      </div>
    </>
  );
}

window.SessionsScreen = SessionsScreen;
Object.assign(window, { ErrorBanner, ErrorModal, conciseError, PendingRunDetail, SessionRow, RunContextMenu });
