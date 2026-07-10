// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// Blaze → Drafts. Master-detail over draft blazes. The deliverable is the folder: a blaze.yaml
// spec plus accumulating <platform>.trail.yaml recordings. Recording fans out across the selected
// devices; "Save to…" promotes the folder to its final home.
const DRAFT_STATUS = {
  draft: ['blue', 'Draft'],
  recording: ['amber', 'Recording'],
  recorded: ['green', 'Recorded'],
  failed: ['red', 'Failed'],
  partial: ['amber', 'Partial'],
};

function DraftStatusChip({ s }) {
  const [tone, label] = DRAFT_STATUS[s] || DRAFT_STATUS.draft;
  return <Chip tone={tone}>{label}</Chip>;
}

// `RUN_STATUS_DOT` (status icon+color), `parseTrailYaml`, `promptEntry`, `stepToolCount`,
// `fileToolCount`, `SelectorLine`, `RecordingCell`, `editorToolsFor`, `RecordConfigDialog` and the
// `StepsBoard` itself now live in steps-board.jsx (loaded before this file), shared with the Trails
// screen — both render the same Steps × recordings board over structurally-identical folders.

function DraftsScreen({ go, initSel, active }) {
  useLucide();
  const drafts = TB.useDrafts();
  const list = drafts.data || [];
  const sessions = TB.useSessions(); // polls while any run is live → sub-rows update in place
  const [sel, setSel] = React.useState(initSel || null);
  const [viewRun, setViewRun] = React.useState(null); // a recording session viewed inline in the pane
  // Re-select when navigated here with a specific draft (e.g. straight after Create). The screen
  // stays mounted across navigation, so the initSel prop (not just initial state) drives this.
  React.useEffect(() => { if (initSel) setSel(initSel); }, [initSel]);
  React.useEffect(() => {
    if (!sel && list.length) setSel(list[0].id);
  }, [list.length]);

  return (
    <div style={{ display: 'flex', height: '100%', overflow: 'hidden' }}>
      {/* draft list */}
      <div style={{ width: 280, flex: 'none', borderRight: '1px solid var(--tb-hairline)', overflowY: 'auto', background: 'var(--bg-subtle)' }}>
        <RailHeader
          ico="files"
          iconColor="var(--tb-running)"
          title="Drafts"
          help={(
            <HelpButton title="How drafts work" align="left"
              sub="A draft is a folder you build up: a portable spec plus one recording per device.">
              <HelpCard ico="sparkles" color="var(--tb-ai)" title="Start from a prompt"
                when="You know the objective but not the exact steps.">
                In <b>Create</b>, an objective becomes a set of proposed steps. Those steps become the draft's blaze, its portable spec.
              </HelpCard>
              <HelpCard ico="files" color="var(--tb-link)" title="The folder is the draft">
                Each draft is a folder holding <span className="tb-mono">blaze.yaml</span> (the spec) and one <span className="tb-mono">&lt;platform&gt;.trail.yaml</span> per recording. Edit the steps here any time.
              </HelpCard>
              <HelpCard ico="circle-play" color="var(--tb-amber)" title="Record per device"
                when="The steps look right and you have devices connected.">
                Pick devices and hit <b>Record</b> to fan out. Each run produces a platform recording that lands back in the folder; <b>Compare</b> lays them side by side by step.
              </HelpCard>
              <HelpCard ico="folder-output" color="var(--tb-pass)" title="Save to the library">
                When you're happy, <b>Save to…</b> promotes the folder out of <span className="tb-mono">drafts/</span> into its final home in your trail library.
              </HelpCard>
            </HelpButton>
          )}
          right={<Btn sm kind="ghost" ico="plus" onClick={() => go('create')}>New</Btn>}
        />
        {list.length === 0 && (
          <div className="tb-sub" style={{ padding: '8px 16px', fontSize: 13 }}>
            No drafts yet. Start one in <a {...clickable(() => go('create'))} style={{ color: 'var(--tb-link)', cursor: 'pointer' }}>Create</a>.
          </div>
        )}
        {list.map((d) => {
          // One dot per recording the draft *currently* holds (its variant files), colored by that
          // platform's latest run status. Driving this off variants (not run history) means deleting
          // a column drops its dot — run sessions persist in history but aren't this draft's content.
          const linked = (sessions.data || []).filter((s) => s.trailId === d.id);
          const byPlat = {};
          linked.forEach((s) => {
            const p = (s.platform || '').toLowerCase();
            if (!p) return;
            if (!byPlat[p] || (s.timestampMs || 0) > (byPlat[p].timestampMs || 0)) byPlat[p] = s;
          });
          // d.variants here is a list of variant filenames (DraftSummary) — derive the platform from
          // the `<platform>.trail.yaml` name, then color by that platform's latest run status.
          const devs = (d.variants || []).map((fname) => {
            const m = String(fname).match(/^(android|ios|web)/i);
            const p = m ? m[1].toLowerCase() : '';
            return { platform: p, status: byPlat[p] ? byPlat[p].status : null };
          });
          return (
            <DraftRow key={d.id} d={d} devs={devs} selected={d.id === sel}
              onClick={() => { setSel(d.id); setViewRun(null); }} />
          );
        })}
      </div>

      {/* detail */}
      <div style={{ flex: 1, overflowY: 'auto' }}>
        {sel
          ? <DraftDetail key={sel} id={sel} go={go} active={active} viewRun={viewRun} onViewRun={(s) => setViewRun(s)} onCloseRun={() => setViewRun(null)} onChanged={() => drafts.reload()} onDeleted={() => { setSel(null); setViewRun(null); drafts.reload(); }} />
          : <div className="tb-sub" style={{ padding: 36 }}>Select a draft, or create one.</div>}
      </div>

    </div>
  );
}

// Commit readiness as minimal colored segments — one short line per gate. Green = done, red = failed
// (a recording that errored, which blocks saving), muted = pending. No labels to keep it quiet; each
// segment is still a button (hover = what it is / what it needs, click = jump there to fix it).
function ReadinessBar({ checks }) {
  return (
    <div className="tb-ready" role="group" aria-label="Commit readiness">
      {checks.map((c, i) => {
        const state = c.state || (c.ok ? 'done' : 'todo');
        const done = state === 'done';
        return (
          <HoverTip key={i} tip={(
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
              <Ico n={done ? 'check-circle-2' : 'x-circle'} s={14} c={done ? 'var(--tb-pass)' : 'var(--tb-fail)'} style={{ flex: '0 0 auto' }} />
              <span>{c.label}</span>
            </span>
          )}>
            <button type="button" className="tb-ready-line" data-state={state} onClick={c.action}
              aria-label={`${c.label}: ${done ? 'done' : state === 'fail' ? 'failed' : 'not done'}`} />
          </HoverTip>
        );
      })}
    </div>
  );
}

// The agent pairing rail — companion to the draft's steps/board. SLICE 1: the static shell + a
// locally-echoing composer (no backend yet). A restrained sparkle is the agent's only accent;
// later slices stream live turns and wire proposal→apply (edits, recording, self-heal). When
// collapsed it shrinks to a slim icon strip you click to reopen.
function AgentRail({ open, onToggle, sessionId, sessionRunning, onApplyStep }) {
  useLucide();
  const [msgs, setMsgs] = React.useState([]);
  const [text, setText] = React.useState('');
  const scrollRef = React.useRef(null);
  // "Review this trail": ask the agent to critique the finished run for assertion gaps + fragile
  // selectors. Read-only suggestions; applying one appends a verify step to the draft (onApplyStep).
  const [reviewing, setReviewing] = React.useState(false);
  const [suggestions, setSuggestions] = React.useState(null); // null = not run, [] = none, [...] = found
  const [reviewErr, setReviewErr] = React.useState(null);
  const [applied, setApplied] = React.useState({});
  React.useEffect(() => { setSuggestions(null); setReviewErr(null); setApplied({}); }, [sessionId]);
  const reviewTrail = async () => {
    if (!sessionId) return;
    setReviewing(true); setReviewErr(null);
    try {
      const r = await fetch(`/trailrunner/api/session/${encodeURIComponent(sessionId)}/review`, { method: 'POST' });
      const j = await r.json();
      if (j.error) setReviewErr(j.error); else setSuggestions(j.suggestions || []);
    } catch (e) { setReviewErr(String((e && e.message) || e)); }
    setReviewing(false);
  };
  // Live agent thread: one EventSource per linked session pushes each agent log as it lands (no
  // polling). The raw log JSON is the same the trace viewer parses, so TB.extractTrace collapses it
  // into the same user-meaningful turns. Replay stays deterministic; this only observes.
  const [liveLogs, setLiveLogs] = React.useState([]);
  const [streamState, setStreamState] = React.useState('idle'); // idle | live | done | error
  React.useEffect(() => {
    if (!sessionId) { setLiveLogs([]); setStreamState('idle'); return; }
    setLiveLogs([]); setStreamState('live');
    const es = new EventSource(`/trailrunner/api/session/${encodeURIComponent(sessionId)}/stream`);
    es.addEventListener('log', (e) => { try { const o = JSON.parse(e.data); setLiveLogs((ls) => [...ls, o]); } catch (_) {} });
    es.addEventListener('done', () => { setStreamState('done'); es.close(); });
    es.addEventListener('error', () => { setStreamState((s) => (s === 'done' ? s : 'error')); es.close(); });
    return () => es.close();
  }, [sessionId]);
  const turns = React.useMemo(() => (sessionId && window.TB ? (TB.extractTrace(liveLogs) || []) : []), [sessionId, liveLogs]);
  React.useEffect(() => { if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight; }, [msgs.length, turns.length]);
  const send = () => {
    const t = text.trim();
    if (!t) return;
    setText('');
    setMsgs((m) => [...m,
      { who: 'user', text: t },
      { who: 'agent', text: 'Live pairing is being wired up — this thread will drive step edits, recordings, and self-heal from here. (Coming in the next slices.)' },
    ]);
  };
  if (!open) {
    return (
      <button type="button" onClick={onToggle} title="Open agent chat" aria-label="Open agent chat"
        style={{ width: 46, margin: '0 12px 12px 0', display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 12, padding: '12px 0', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 14, cursor: 'pointer', color: 'var(--text-subtle)' }}>
        <Ico n="panel-left-open" s={16} />
        <Ico n="sparkles" s={16} c="var(--tb-ai)" />
      </button>
    );
  }
  return (
    <div style={{ width: 'clamp(300px, 22vw, 360px)', margin: '0 12px 12px 0', display: 'flex', flexDirection: 'column', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 14, overflow: 'hidden' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '11px 14px', borderBottom: '1px solid var(--tb-hairline)' }}>
        <Ico n="sparkles" s={16} c="var(--tb-ai)" />
        <span style={{ fontSize: 14, fontWeight: 600, color: 'var(--text-standard)' }}>Pair with agent</span>
        <span style={{ flex: 1 }} />
        <ModelPicker />
        <span role="button" onClick={onToggle} title="Collapse chat" style={{ cursor: 'pointer', color: 'var(--text-subtle)', display: 'inline-flex' }}><Ico n="panel-left-close" s={16} /></span>
      </div>
      <div ref={scrollRef} style={{ flex: 1, overflowY: 'auto', padding: '16px 18px', display: 'flex', flexDirection: 'column', gap: 14, minHeight: 140 }}>
        {turns.length > 0 && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11, fontWeight: 700, letterSpacing: '.05em', textTransform: 'uppercase', color: streamState === 'live' ? 'var(--tb-running)' : streamState === 'done' ? 'var(--tb-pass)' : 'var(--text-subtle)' }}>
              <Ico n={streamState === 'live' ? 'loader-circle' : streamState === 'done' ? 'check-circle' : 'circle'} s={13} spin={streamState === 'live'} />
              {streamState === 'live' ? 'Agent running' : streamState === 'done' ? 'Run finished' : streamState === 'error' ? 'Thread ended' : 'Agent thread'}
            </div>
            {turns.map((t, i) => (
              <div key={i} style={{ display: 'flex', gap: 9, alignItems: 'flex-start', fontSize: 13, lineHeight: 1.5 }}>
                <Ico n={t.ok === false ? 'x' : 'check'} s={14} c={t.ok === false ? 'var(--tb-fail)' : 'var(--tb-pass)'} style={{ flex: '0 0 auto', marginTop: 2 }} />
                <span style={{ minWidth: 0, color: 'var(--text-subtle-variant)' }}>
                  <span style={{ fontWeight: 600, color: 'var(--text-standard)' }}>{t.label}</span>
                  {t.tool && <span> {t.tool}</span>}
                  {t.count > 1 && <span style={{ color: 'var(--text-subtle)' }}> ×{t.count}</span>}
                  {t.err && <span style={{ color: 'var(--tb-danger-text)' }}> — {t.err}</span>}
                </span>
              </div>
            ))}
          </div>
        )}
        {sessionId && !sessionRunning && (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
            {suggestions === null ? (
              <button type="button" onClick={reviewTrail} disabled={reviewing}
                style={{ alignSelf: 'flex-start', display: 'inline-flex', alignItems: 'center', gap: 7, border: '1px solid var(--tb-hairline-strong)', background: 'var(--bg-standard)', color: 'var(--text-standard)', borderRadius: 99, padding: '7px 14px', fontSize: 12.5, fontWeight: 600, cursor: reviewing ? 'default' : 'pointer', opacity: reviewing ? 0.7 : 1 }}>
                <Ico n={reviewing ? 'loader-circle' : 'sparkles'} s={14} c="var(--tb-ai)" spin={reviewing} />
                {reviewing ? 'Reviewing the trail…' : 'Review this trail'}
              </button>
            ) : suggestions.length === 0 ? (
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 13, color: 'var(--text-subtle-variant)' }}>
                <Ico n="check-circle" s={15} c="var(--tb-pass)" /> No gaps found — the trail verifies its outcomes and uses semantic selectors.
              </div>
            ) : (
              suggestions.map((s, i) => {
                const gap = s.kind === 'assertion-gap';
                return (
                  <div key={i} style={{ border: '1px solid var(--tb-hairline)', borderLeft: '2px solid var(--tb-ai)', borderRadius: 10, background: 'var(--bg-standard)', padding: '10px 12px', display: 'flex', flexDirection: 'column', gap: 6 }}>
                    <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                      <span className={'tb-chip ' + (gap ? 'amber' : 'blue')}>
                        <Ico n={gap ? 'shield-alert' : 'crosshair'} s={11} /> {gap ? 'assertion gap' : 'fragile selector'}
                      </span>
                      <span style={{ fontSize: 13, fontWeight: 600, color: 'var(--text-standard)' }}>{s.title}</span>
                    </div>
                    <div style={{ fontSize: 12.5, color: 'var(--text-subtle-variant)', lineHeight: 1.5 }}>{s.detail}</div>
                    {s.suggestedStep && <div className="tb-mono" style={{ fontSize: 12, color: 'var(--text-standard)', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 7, padding: '6px 9px', whiteSpace: 'pre-wrap' }}>{s.suggestedStep}</div>}
                    <div style={{ display: 'flex', gap: 7, marginTop: 2 }}>
                      {s.suggestedStep && onApplyStep && (applied[i] ? (
                        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 12, fontWeight: 600, color: 'var(--tb-pass)' }}><Ico n="check" s={13} /> Added to steps</span>
                      ) : (
                        <button type="button" onClick={() => { onApplyStep(s.suggestedStep); setApplied((a) => ({ ...a, [i]: true })); }}
                          style={{ display: 'inline-flex', alignItems: 'center', gap: 5, border: 'none', background: 'var(--tb-ai)', color: '#160a26', borderRadius: 8, padding: '5px 11px', fontSize: 12, fontWeight: 700, cursor: 'pointer' }}>
                          <Ico n="plus" s={13} /> Apply
                        </button>
                      ))}
                      <button type="button" onClick={() => setSuggestions((sg) => sg.filter((_, j) => j !== i))}
                        style={{ border: '1px solid var(--tb-hairline-strong)', background: 'transparent', color: 'var(--text-subtle-variant)', borderRadius: 8, padding: '5px 11px', fontSize: 12, fontWeight: 600, cursor: 'pointer' }}>Dismiss</button>
                    </div>
                  </div>
                );
              })
            )}
            {reviewErr && <div style={{ fontSize: 12.5, color: 'var(--tb-danger-text)' }}>{reviewErr}</div>}
          </div>
        )}
        {msgs.map((m, i) => (
          m.who === 'user' ? (
            <div key={i} style={{ alignSelf: 'flex-end', maxWidth: '86%', fontSize: 13.5, lineHeight: 1.55, padding: '9px 13px', borderRadius: 11, background: 'var(--bg-prominent)', color: 'var(--text-standard)' }}>{m.text}</div>
          ) : (
            <div key={i} style={{ display: 'flex', gap: 9, alignItems: 'flex-start', fontSize: 13.5, lineHeight: 1.6, color: 'var(--text-subtle-variant)' }}>
              <Ico n="sparkles" s={15} c="var(--tb-ai)" style={{ flex: '0 0 auto', marginTop: 3 }} />
              <span>{m.text}</span>
            </div>
          )
        ))}
        {turns.length === 0 && msgs.length === 0 && !sessionId && (
          <div style={{ margin: 0, maxWidth: 420, display: 'flex', flexDirection: 'column', gap: 12 }}>
            <div style={{ fontSize: 13.5, color: 'var(--text-subtle-variant)', lineHeight: 1.55 }}>Describe what this test should do, or pick a starting point.</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 7, marginTop: 2 }}>
              {['Harden the selectors in these steps', 'Add a verification after sign-in', 'Record this on an iOS simulator'].map((p) => (
                <button key={p} type="button" onClick={() => setText(p)}
                  style={{ display: 'inline-flex', alignItems: 'center', gap: 8, textAlign: 'left', border: '1px solid var(--tb-hairline)', background: 'var(--bg-standard)', color: 'var(--text-subtle-variant)', borderRadius: 9, padding: '8px 11px', fontSize: 12.5, cursor: 'pointer', font: 'inherit', transition: 'border-color .12s var(--ease-out-soft), color .12s var(--ease-out-soft)' }}
                  onMouseEnter={(e) => { e.currentTarget.style.borderColor = 'var(--tb-ai)'; e.currentTarget.style.color = 'var(--text-standard)'; }}
                  onMouseLeave={(e) => { e.currentTarget.style.borderColor = 'var(--tb-hairline)'; e.currentTarget.style.color = 'var(--text-subtle-variant)'; }}>
                  <Ico n="arrow-up-right" s={13} c="var(--tb-ai)" /> {p}
                </button>
              ))}
            </div>
          </div>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '11px 14px', borderTop: '1px solid var(--tb-hairline)' }}>
        <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Ask the agent to change a step…"
          onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); send(); } }}
          style={{ flex: 1, height: 38, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 9, outline: 'none', color: 'var(--text-standard)', font: 'inherit', fontSize: 13.5, padding: '0 12px' }} />
        <button type="button" onClick={send} disabled={!text.trim()} title="Send" aria-label="Send"
          style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 38, height: 38, borderRadius: 9, border: 'none', background: text.trim() ? 'var(--tb-ai)' : 'var(--bg-prominent)', color: text.trim() ? '#160a26' : 'var(--text-subtle)', cursor: text.trim() ? 'pointer' : 'default' }}>
          <Ico n="arrow-up" s={17} />
        </button>
      </div>
    </div>
  );
}

function DraftDetail({ id, go, active, viewRun, onViewRun, onCloseRun, onChanged, onDeleted }) {
  useLucide();
  const detail = TB.useDraftDetail(id);
  const d = detail.data;
  const devices = TB.useDevices();
  const toolsCatalog = TB.useTools(); // for editor autocomplete, scoped to the target below
  const [err, setErr] = React.useState(null);
  const sessions = TB.useSessions();
  // Editable steps, seeded from the loaded blaze and re-seeded when the saved yaml changes.
  const [steps, setSteps] = React.useState(null);
  // Agent pairing chat open/collapsed (Blaze tab only).
  const [railOpen, setRailOpen] = React.useState(true);
  // The pair-with-agent chat renders into the app-level right rail (#tb-agent-rail) via a portal, so
  // it sits beside the content like the left nav rail while keeping this draft's context. Declared
  // here (above the early `!d` return) so the hook order stays stable across renders.
  const [railSlot, setRailSlot] = React.useState(null);
  React.useEffect(() => { setRailSlot(document.getElementById('tb-agent-rail')); }, []);
  // Artifact panel view: 'rendered' (steps & recordings board) | 'raw' (blaze.yaml source).
  const [blazeView, setBlazeView] = React.useState('rendered');
  const [viewFile, setViewFile] = React.useState(null); // { name, content } — the pushed file-editor page
  const [pushShown, setPushShown] = React.useState(false); // drives the push-in/parallax; false while parked off-screen right
  const editorDirty = React.useRef(false); // set by the editor page's Edit tab; guards close-on-discard
  const reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  // Scroll target for the readiness bar's click-to-act (Steps/Recording jump to the board); the
  // destination input lives in the Finalize-and-Save popover.
  const boardRef = React.useRef(null);
  const destInputRef = React.useRef(null);
  const [commitPopoverOpen, setCommitPopoverOpen] = React.useState(false);
  const [menuOpen, setMenuOpen] = React.useState(false);
  const scrollTo = (ref) => ref.current && ref.current.scrollIntoView({ behavior: reduce ? 'auto' : 'smooth', block: 'start' });
  const [titleEdit, setTitleEdit] = React.useState(null); // null = not editing; string = working title
  const [savingTitle, setSavingTitle] = React.useState(false);
  const cancelTitleRef = React.useRef(false);
  const [variantDocs, setVariantDocs] = React.useState({}); // <variant file name> -> parsed trail yaml (for the board columns)
  const [tab, setTab] = React.useState('blaze'); // 'blaze' (author + board) | 'runs' (recording history + traces)
  const [cfg, setCfg] = React.useState(null); // editable blaze config: { target, platform, destination }
  const [committing, setCommitting] = React.useState(false);
  const [variantReload, setVariantReload] = React.useState(0); // bump to refetch variant docs (e.g. after an inline trail edit)
  React.useEffect(() => {
    if (d) {
      setSteps((d.steps || []).map((s) => ({ kind: s.kind === 'verify' ? 'verify' : 'do', text: s.text || '' })));
      setCfg({ target: d.target || '', platform: d.platform || '', destination: d.destination || '' });
    }
  }, [d && d.blazeYaml]);

  // Keep the folder fresh while a recording for this draft is running, and reload once when it
  // finishes so the newly-written <platform>.trail.yaml appears without a manual Refresh.
  const hasRunningLinked = (sessions.data || []).some((s) => s.trailId === id && s.status === 'running');
  // The session the agent thread streams live: prefer a running linked session, else the most
  // recent linked one (so opening a draft shows its last run's agent turns).
  const liveSession = (() => {
    const linked = (sessions.data || []).filter((s) => s.trailId === id);
    if (!linked.length) return null;
    return linked.find((s) => s.status === 'running')
      || linked.slice().sort((a, b) => (b.timestampMs || 0) - (a.timestampMs || 0))[0];
  })();
  const liveSessionId = liveSession ? liveSession.id : null;
  const liveSessionRunning = !!(liveSession && liveSession.status === 'running');
  const wasRunningRef = React.useRef(false);
  React.useEffect(() => {
    if (hasRunningLinked) {
      wasRunningRef.current = true;
      const t = setInterval(() => detail.reload(), 3000);
      return () => clearInterval(t);
    }
    if (wasRunningRef.current) {
      wasRunningRef.current = false;
      detail.reload();
    }
  }, [hasRunningLinked]);

  // Fetch + parse each recorded variant so the board can show its tools aligned to the steps.
  // Refetches when the variant set changes, when steps are saved, and when a recording finishes.
  const variantNames = ((d && d.variants) || []).map((v) => v.name).join(',');
  React.useEffect(() => {
    const vs = (d && d.variants) || [];
    if (!vs.length) { setVariantDocs({}); return; }
    let cancelled = false;
    Promise.all(vs.map((v) => TB.fetchDraftFile(id, v.name).then((c) => [v.name, parseTrailYaml(c || '')])))
      .then((entries) => { if (!cancelled) setVariantDocs(Object.fromEntries(entries)); });
    return () => { cancelled = true; };
  }, [id, variantNames, d && d.blazeYaml, hasRunningLinked, variantReload]);

  // The blaze's full config block, parsed straight from the file and flattened for display —
  // every key it carries (target, platform, driver, context, metadata.*, tags, …), not a hardcoded
  // subset. Edits happen in the blaze.yaml editor. Declared before the `!d` guard so the hook count
  // stays constant across the loading/loaded renders (null-safe on d).
  const blazeConfigRows = React.useMemo(() => flattenObject(parseTrailYaml((d && d.blazeYaml) || '').config || {}, { joinArray: (a) => a.join(', ') }), [d && d.blazeYaml]);
  const blazeTrailhead = React.useMemo(() => parseTrailYaml((d && d.blazeYaml) || '').trailhead, [d && d.blazeYaml]);

  if (!d) return <div className="tb-sub" style={{ padding: 36 }}>Loading…</div>;

  // Viewing a recording inline (no tab switch). Reuse the same run-detail TraceViewer the Runs tab
  // uses; its header's left slot becomes a "Back to draft" button in this context.
  if (viewRun) {
    return (
      <div style={{ height: '100%', display: 'flex', flexDirection: 'column' }}>
        <div style={{ flex: 1, minHeight: 0 }}>
          <TraceViewer s={viewRun} go={go} onDeleted={onCloseRun} onBack={onCloseRun} backLabel="Back to draft" />
        </div>
      </div>
    );
  }

  const deviceList = devices.data || [];

  // Board I/O primitives — the network half of record/play/delete. StepsBoard owns the optimistic
  // "starting…"/busy UI and calls these; each returns {ok, error}. Drafts hit /api/draft/*.
  async function dispatchRecord(chosen, options) {
    const deviceIds = chosen.map((dev) => ({ instanceId: dev.id, trailblazeDevicePlatform: (dev.platform || '').toUpperCase() }));
    // Connect each device's RPC bridge first — the daemon's runYaml needs a live connection, same as
    // the Blaze and Configure-run flows (which call connectDevice before dispatching).
    for (const tbId of deviceIds) {
      const ok = await TB.connectDevice(tbId);
      if (!ok) return { ok: false, error: `Couldn't connect to ${chosen.find((d) => d.id === tbId.instanceId)?.name || 'the device'}. Is it still online?` };
    }
    const r = await TB.recordDraft(id, deviceIds, options);
    if (!r.ok) return { ok: false, error: r.error };
    // Recordings land in the folder on completion; refresh shortly after.
    onChanged && onChanged();
    setTimeout(() => detail.reload(), 4000);
    return { ok: true, error: r.error }; // r.error here is a partial-start note, not a hard failure
  }
  // Play = replay a recorded <platform>.trail.yaml on its device (deterministic, using the recorded
  // steps) — a normal run, so it shows up in Runs. Distinct from Record (which regenerates the file).
  async function dispatchPlay(col) {
    if (!col.device || !col.recorded) return { ok: false };
    const yaml = await TB.fetchDraftFile(id, col.name);
    if (!yaml) return { ok: false, error: 'Could not read ' + col.name };
    const tbId = { instanceId: col.device.id, trailblazeDevicePlatform: (col.device.platform || '').toUpperCase() };
    const ok = await TB.connectDevice(tbId);
    if (!ok) return { ok: false, error: `Couldn't connect to ${col.device.name}. Is it still online?` };
    const r = await TB.dispatchRun(tbId, yaml, { useRecordedSteps: true, trailId: id });
    if (!r.ok) return { ok: false, error: r.error };
    onChanged && onChanged();
    setTimeout(() => detail.reload(), 3000);
    return { ok: true };
  }
  async function deleteVariantFile(name) {
    const r = await TB.deleteDraftFile(id, name);
    if (r.ok) { detail.reload(); setVariantReload((n) => n + 1); onChanged && onChanged(); }
    return r;
  }
  // Inline tool-call-editor writer (mirrors the Trails board): re-read the variant, patch the step's
  // recording in place preserving every other top-level item (config + any setup `- tools:` block),
  // save, and hot-update the parsed doc.
  async function saveVariantTools(variantName, promptIndex, newToolsArray, stepInfo) {
    const r0 = await patchVariantRecording(TB.fetchDraftFile, id, variantName, promptIndex, newToolsArray, stepInfo);
    if (r0.error) return { success: false, error: r0.error };
    if (r0.noop) return { success: true };
    const r = await TB.updateDraftFile(id, variantName, r0.yaml);
    if (r.success) setVariantDocs((prev) => ({ ...prev, [variantName]: parseTrailYaml(r0.yaml) }));
    return r;
  }
  // Full-file writer for the expanded variant's inline raw-YAML editor. Hot-updates the parsed doc.
  async function saveVariantYaml(name, yaml) {
    const r = await TB.updateDraftFile(id, name, yaml);
    if (r.success) setVariantDocs((prev) => ({ ...prev, [name]: parseTrailYaml(yaml) }));
    return r;
  }

  async function remove() {
    if (!window.confirm('Delete this draft and its recordings?')) return;
    await TB.deleteDraft(id);
    onDeleted && onDeleted();
  }

  function revealInFinder() { TB.revealDraft(id); }

  // Delete every recorded variant but keep the blaze spec — lets you re-record from a clean slate.
  async function discardRecordings() {
    const vs = (d.variants || []);
    if (!vs.length) return;
    if (!window.confirm(`Discard ${vs.length} recording${vs.length === 1 ? '' : 's'}? The blaze spec stays.`)) return;
    setErr(null);
    for (const v of vs) {
      const r = await TB.deleteDraftFile(id, v.name);
      if (!r.ok) { setErr(r.error || 'Could not discard recordings.'); break; }
    }
    detail.reload(); setVariantReload((n) => n + 1); onChanged && onChanged();
  }

  // Push the file-editor page in (mac NavigationStack style): mount it parked off-screen right, then
  // flip `pushShown` on the next frame so it slides in while the draft detail parallaxes left. An
  // optional `highlight` ({text}) jumps the editor to (and flashes) that step's YAML block — the same
  // object is reused across both setViewFile calls so the editor only flashes once per open.
  function openFile(name, highlight = null) {
    setViewFile({ name, content: null, highlight });
    requestAnimationFrame(() => setPushShown(true));
    TB.fetchDraftFile(id, name).then((content) =>
      setViewFile((vf) => (vf && vf.name === name ? { name, content: content || '(could not read file)', highlight } : vf)));
  }
  // Pop the page: guard unsaved edits, slide back out, then unmount after the transition.
  function closeFile() {
    if (editorDirty.current && !window.confirm('Discard unsaved changes?')) return;
    setPushShown(false);
    window.setTimeout(() => { setViewFile(null); editorDirty.current = false; }, reduce ? 0 : PUSH_MS);
  }

  // ── editable steps (the array is held here; StepsBoard mutates it via onStepsChange=setSteps) ──
  const origSteps = (d.steps || []).map((s) => ({ kind: s.kind === 'verify' ? 'verify' : 'do', text: s.text || '' }));
  const dirty = steps && JSON.stringify(steps) !== JSON.stringify(origSteps);
  // Apply a "Review this trail" assertion-gap suggestion: append it as a verify step and save.
  const applyReviewStep = (suggested) => {
    const txt = String(suggested || '').replace(/^\s*verify\s*:\s*/i, '').trim();
    if (!txt) return;
    const next = [...(steps || []), { kind: 'verify', text: txt }];
    setSteps(next);
    saveBlaze(null, next);
  };
  // Single writer for blaze.yaml. The title rename, the config editor, and the step editor all flow
  // through here so none clobbers the others. Uses mergeBlazeYaml so config the structured editor
  // doesn't model (tags, priority, driver, skip, …, added via the raw YAML editor) is preserved.
  async function saveBlaze(nextTitle, nextSteps, nextCfg) {
    const c = nextCfg || cfg || {};
    const useSteps = (nextSteps != null ? nextSteps : (steps || [])).filter((s) => s.text.trim());
    const yaml = TB.mergeBlazeYaml(d.blazeYaml, {
      title: nextTitle != null ? nextTitle : d.name,
      target: c.target || null,
      platform: c.platform || null,
      objective: d.objective,
      context: d.context || null,
      destination: c.destination || null,
      steps: useSteps,
    });
    return await TB.updateDraftBlaze(id, yaml);
  }
  // StepsBoard's "Save steps" writer — returns {success,error}; reload on success.
  async function saveSteps(next) {
    const r = await saveBlaze(null, next);
    if (r.success) { detail.reload(); onChanged && onChanged(); }
    return r;
  }
  // Commit = promote the folder out of drafts/ into its chosen destination.
  async function commit() {
    const destination = (cfg && cfg.destination || '').trim();
    if (!destination) { setErr('Set a commit destination first (in Config).'); return; }
    setCommitting(true); setErr(null);
    // Persist any unsaved config first so the moved folder's blaze.yaml destination matches where it lands.
    if (cfgDirty) {
      const cr = await saveBlaze(null, null, cfg);
      if (!cr.success) { setCommitting(false); setErr(cr.error || 'Could not save config before commit.'); return; }
    }
    const r = await TB.saveDraftTo(id, destination);
    setCommitting(false);
    if (!r.success) { setErr(r.error || 'Could not commit to that location.'); return; }
    onChanged && onChanged();
    // The folder has left drafts/ for the trail library — send the user there, not back to a
    // now-committed "draft" (which would still render a Delete action against a real trail folder).
    go('trails');
  }
  async function commitTitle() {
    if (titleEdit === null) return;
    const next = titleEdit.trim();
    if (!next || next === d.name) { setTitleEdit(null); return; }
    setSavingTitle(true); setErr(null);
    const r = await saveBlaze(next, null);
    setSavingTitle(false);
    setTitleEdit(null);
    if (!r.success) { setErr(r.error || 'Could not rename.'); return; }
    detail.reload();
    onChanged && onChanged();
  }

  // ── variant ↔ session linkage (status + trace link) ──
  const draftSessions = (sessions.data || []).filter((s) => s.trailId === id);
  const sessForPlatform = (p) => draftSessions
    .filter((s) => (s.platform || '').toLowerCase() === (p || '').toLowerCase())
    .sort((a, b) => (b.timestampMs || 0) - (a.timestampMs || 0))[0];
  const anyRunning = draftSessions.some((s) => s.status === 'running');

  const variants = d.variants || [];

  // ── config dirtiness + commit readiness ──
  const cfgDirty = cfg && (cfg.target !== (d.target || '') || cfg.platform !== (d.platform || '') || cfg.destination !== (d.destination || ''));
  // Editor tools palette (shared helper): the target app's own tools + the generalized ones,
  // scoped to the opened file's platform (android.trail.yaml → android), falling back to the draft's.
  const editorTarget = (cfg && cfg.target) || d.target;
  const palettePlatformMatch = ((viewFile && viewFile.name) || '').match(/^(android|ios|web)\./);
  const palettePlatform = palettePlatformMatch ? palettePlatformMatch[1] : ((cfg && cfg.platform) || d.platform || '').toLowerCase();
  const editorTools = editorToolsFor(toolsCatalog.data, editorTarget, palettePlatform);
  const failedPlatforms = variants.filter((v) => { const s = sessForPlatform(v.platform); return s && (s.status === 'failed' || s.status === 'timeout'); });
  // The gates that must pass before committing. The commit destination is no longer a gate — it's
  // captured as part of the "Finalize and Save" action itself (the popover on that button).
  // The gates, each → one colored segment. `state` drives the color (green done / red failed / muted
  // pending); `ok` drives whether commit is allowed. The recording gate is only green when there's at
  // least one recording AND none failed — so a failed recording keeps the segment red and blocks save.
  const recFailed = variants.length > 0 && failedPlatforms.length > 0;
  const commitChecks = [
    { ok: !!(d.name || '').trim(), label: 'Title set', hint: 'Rename the draft', action: () => setTitleEdit(d.name) },
    { ok: (steps || []).some((s) => s.text.trim()), label: 'At least one step', hint: 'Add a step to the blaze', action: () => scrollTo(boardRef) },
    { ok: !!(cfg && cfg.target), label: 'Target set', hint: 'Set the target app in the blaze', action: () => openFile('blaze.yaml') },
    {
      ok: variants.length > 0 && !recFailed,
      state: recFailed ? 'fail' : (variants.length > 0 ? 'done' : 'todo'),
      label: recFailed ? `${failedPlatforms.length} recording${failedPlatforms.length === 1 ? '' : 's'} failed` : 'Recordings captured',
      hint: recFailed ? 're-record the failing device(s)' : 'Record on a device',
      action: () => scrollTo(boardRef),
    },
  ];
  const commitRemaining = commitChecks.filter((c) => !c.ok).length;
  // A live recording is also a hard block: committing ATOMIC-moves the folder out from under the run.
  const requiredOk = commitRemaining === 0 && !anyRunning;

  return (
    // Nav-stack container: the draft detail is the base page; the file editor pushes over it as a
    // page (it covers this panel, not the whole window). overflow:hidden clips the off-screen page.
    <div style={{ position: 'relative', height: '100%', overflow: 'hidden' }}>
      <div style={{
        position: 'absolute', inset: 0, overflowY: 'auto',
        transform: pushShown && !reduce ? 'translateX(-22%)' : 'none',
        transition: reduce ? 'none' : `transform ${PUSH_MS}ms ${PUSH_EASE}`,
        pointerEvents: viewFile ? 'none' : 'auto',
      }}>
      <div style={{ padding: '24px 32px 70px' }}>
      <div style={{ marginBottom: 22 }}>
        {/* Fixed-height header row: the title display and the edit input occupy the SAME height so
            switching to edit mode (the input's border + padding) never bounces the row or the
            action cluster. proportions ch05 — a stable rhythm beats reflow on interaction. */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap', minHeight: 42 }}>
          <div style={{ flex: 1, minWidth: 220, height: 42, display: 'flex', alignItems: 'center' }}>
          {titleEdit === null ? (
            <div {...clickable(() => setTitleEdit(d.name))} title="Rename draft"
              style={{ fontSize: 20, fontWeight: 700, color: 'var(--text-standard)', lineHeight: 1.2, cursor: 'text', display: 'flex', alignItems: 'center', gap: 9, minWidth: 0 }}>
              <span style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.name}</span>
              {savingTitle ? <Ico n="loader-2" s={15} c="var(--text-subtle)" spin /> : <Ico n="pencil" s={14} c="var(--text-subtle)" style={{ opacity: 0.55, flex: '0 0 auto' }} />}
            </div>
          ) : (
            <input autoFocus value={titleEdit}
              onChange={(e) => setTitleEdit(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur(); }
                else if (e.key === 'Escape') { cancelTitleRef.current = true; e.currentTarget.blur(); }
              }}
              onBlur={() => { if (cancelTitleRef.current) { cancelTitleRef.current = false; setTitleEdit(null); } else commitTitle(); }}
              style={{ fontSize: 20, fontWeight: 700, color: 'var(--text-standard)', lineHeight: 1.2, height: 36, width: '100%', boxSizing: 'border-box', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline-strong)', borderRadius: 8, padding: '0 10px', outline: 'none', fontFamily: 'inherit' }} />
          )}
          </div>
          {/* top-right action cluster: compact readiness progress · Finalize and Save · ⋮ menu */}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: '0 0 auto', flexWrap: 'wrap', justifyContent: 'flex-end' }}>
            {tab === 'blaze' && <ReadinessBar checks={commitChecks} />}
            {tab === 'blaze' && (
              <span style={{ position: 'relative', display: 'inline-flex' }}>
                {/* When disabled, the button must let hover events through to the HoverTip wrapper
                    (a disabled <button> swallows them), so pointer-events move to the wrapper. */}
                <HoverTip tip={anyRunning ? 'Wait for the recording to finish' : !requiredOk ? `${commitRemaining} item${commitRemaining === 1 ? '' : 's'} left before you can finalize` : null}
                  style={!requiredOk || committing ? { cursor: 'not-allowed' } : undefined}>
                  <Btn sm kind="primary" ico={committing ? 'loader-2' : null} spin={committing}
                    disabled={!requiredOk || committing} onClick={() => setCommitPopoverOpen((v) => !v)}
                    style={{ height: 30, ...(!requiredOk || committing ? { pointerEvents: 'none' } : null) }}>
                    {committing ? 'Saving…' : 'Finalize and Save'}
                  </Btn>
                </HoverTip>
                {commitPopoverOpen && (
                  <React.Fragment>
                    <div style={{ position: 'fixed', inset: 0, zIndex: 70 }} onClick={() => setCommitPopoverOpen(false)} />
                    <div className="tb-card" style={{ position: 'absolute', top: 'calc(100% + 8px)', right: 0, zIndex: 71, width: 320, padding: 14, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)' }}>
                      <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Commit to</div>
                      <input ref={destInputRef} value={(cfg && cfg.destination) || ''} autoFocus
                        onChange={(e) => setCfg({ ...cfg, destination: e.target.value })}
                        onKeyDown={(e) => { if (e.key === 'Enter' && ((cfg && cfg.destination) || '').trim()) { setCommitPopoverOpen(false); commit(); } }}
                        placeholder="e.g. sample/login"
                        style={{ width: '100%', boxSizing: 'border-box', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: 'var(--text-standard)', font: 'inherit', fontSize: 13, fontFamily: 'var(--font-mono)', padding: '7px 9px' }} />
                      <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 8, lineHeight: 1.45 }}>Moves this folder out of <span className="tb-mono">drafts/</span> into the trail library.</div>
                      <div style={{ display: 'flex', justifyContent: 'flex-end', marginTop: 11 }}>
                        <Btn sm kind="primary" ico={committing ? 'loader-2' : 'check-circle-2'} spin={committing}
                          disabled={!((cfg && cfg.destination) || '').trim() || committing}
                          onClick={() => { setCommitPopoverOpen(false); commit(); }}>Finalize and Save</Btn>
                      </div>
                    </div>
                  </React.Fragment>
                )}
              </span>
            )}
            <span style={{ position: 'relative', display: 'inline-flex' }}>
              <button type="button" className="tb-icon-sq" title="More actions" aria-label="More actions" onClick={() => setMenuOpen((v) => !v)}>
                <Ico n="more-vertical" s={18} />
              </button>
              {menuOpen && (
                <React.Fragment>
                  <div style={{ position: 'fixed', inset: 0, zIndex: 70 }} onClick={() => setMenuOpen(false)} />
                  <div className="tb-card" style={{ position: 'absolute', top: 'calc(100% + 6px)', right: 0, zIndex: 71, minWidth: 200, padding: 6, background: 'var(--bg-elevated)', boxShadow: '0 14px 40px rgba(0,0,0,.5)' }}>
                    {[
                      { ico: 'folder-open', label: 'Open In Finder', on: () => { setMenuOpen(false); revealInFinder(); }, show: true },
                      { ico: 'eraser', label: 'Discard Recordings', on: () => { setMenuOpen(false); discardRecordings(); }, show: variants.length > 0 },
                      { ico: 'trash-2', label: 'Delete', on: () => { setMenuOpen(false); remove(); }, show: d.inDrafts, danger: true },
                    ].filter((m) => m.show).map((m) => (
                      <div key={m.label} role="button" className="tb-menu-item" onClick={m.on}
                        style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '8px 9px', borderRadius: 7, cursor: 'pointer', fontSize: 13, color: m.danger ? 'var(--tb-danger-text)' : 'var(--text-standard)' }}>
                        <Ico n={m.ico} s={15} c={m.danger ? 'var(--tb-danger-text)' : 'var(--text-subtle-variant)'} /> {m.label}
                      </div>
                    ))}
                  </div>
                </React.Fragment>
              )}
            </span>
          </div>
        </div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 9, flexWrap: 'wrap' }}>
          {/* How this draft was authored, as a split token (Source | value). Today every draft comes from
              a prompt; Record/Import sources are coming. Hover reveals the original prompt text. */}
          {d.objective && (
            <HoverTip tip={d.objective} maxWidth={400} style={{ cursor: 'help', maxWidth: 460 }}>
              <span className="tb-split">
                <span className="k">Source</span>
                <span className="v"><Ico n="sparkles" s={12} c="var(--tb-ai)" style={{ flex: '0 0 auto' }} /><span className="vt">Prompt</span></span>
              </span>
            </HoverTip>
          )}
        </div>
      </div>

      {err && <div style={{ marginBottom: 16, color: 'var(--tb-danger-text)', fontSize: 13 }}>{err}</div>}

      {/* tabs: Blaze (author + board) · Runs (recording history + traces). Shared .tb-tab grammar —
          green active underline, constant weight, real focusable buttons — same as every other tab. */}
      <div className="tb-tabs" style={{ marginBottom: 20 }}>
        {[['blaze', 'list-checks', 'Blaze', null], ['runs', 'activity', 'Runs', draftSessions.length || null]].map(([t, ico, label, n]) => (
          <button key={t} type="button" className={'tb-tab' + (tab === t ? ' active' : '')} onClick={() => setTab(t)}
            style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <Ico n={ico} s={14} /> {label}
            {n ? <span style={{ fontSize: 11, color: 'var(--text-subtle)', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', padding: '0 6px', borderRadius: 99, lineHeight: '16px' }}>{n}</span> : null}
          </button>
        ))}
      </div>

      {tab === 'blaze' && (
      <div style={{ minWidth: 0 }}>
      {/* The pair-with-agent chat is the app-level right rail (portaled below); this tab is now the
          full-width trail artifact — rendered board or raw yaml. */}
      {/* Artifact panel header — toggle only; the board carries its own section title (no duplicate label,
          per Tufte 1+1=3). In Raw mode a single "blaze.yaml" label stands in for that missing board header. */}
      <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 16, minHeight: 26 }}>
        {blazeView === 'raw' && <span className="tb-mono" style={{ fontSize: 12, color: 'var(--text-subtle-variant)' }}>blaze.yaml</span>}
        <span style={{ flex: 1 }} />
        <div style={{ display: 'inline-flex', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 99, padding: 2 }}>
          {[['rendered', 'Rendered', 'list-checks'], ['raw', 'Raw', 'code']].map(([v, label, ico]) => (
            <button key={v} type="button" onClick={() => setBlazeView(v)}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 5, border: '1px solid ' + (blazeView === v ? 'var(--tb-hairline-strong)' : 'transparent'), cursor: 'pointer', borderRadius: 99, padding: '4px 11px', fontSize: 12, fontWeight: 600,
                background: blazeView === v ? 'var(--bg-elevated)' : 'transparent',
                color: blazeView === v ? 'var(--text-standard)' : 'var(--text-subtle)',
                transition: 'background .12s var(--ease-out-soft), color .12s var(--ease-out-soft)' }}>
              <Ico n={ico} s={13} /> {label}
            </button>
          ))}
        </div>
      </div>
      {blazeView === 'raw' ? (
        <pre className="tb-mono" style={{ margin: 0, padding: '14px 16px', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 12, overflow: 'auto', fontSize: 12.5, lineHeight: 1.65, color: 'var(--text-standard)', whiteSpace: 'pre', height: 'calc(100vh - 196px)', minHeight: 360, boxSizing: 'border-box' }}>{(d && d.blazeYaml) || '# (empty draft)'}</pre>
      ) : (<>
      {/* The prompt now lives as the "Source: Prompt" tag in the header (hover to read it). */}

      {/* Steps × recordings board (steps-board.jsx) — the draft's main working surface. The board
          owns its view-local state; this screen supplies the draft data + the /api/draft/* I/O. */}
      <StepsBoard
        steps={steps || []} onStepsChange={setSteps} dirty={dirty} onSaveSteps={saveSteps}
        variants={variants} variantDocs={variantDocs} blazeConfigRows={blazeConfigRows} blazeTrailhead={blazeTrailhead}
        deviceList={deviceList} linkedSessions={draftSessions}
        home={d.home} blazeName="blaze.yaml" target={cfg && cfg.target} canRecord
        onOpenFile={openFile} dispatchRecord={dispatchRecord} dispatchPlay={dispatchPlay}
        onDeleteFile={deleteVariantFile} onViewRun={onViewRun} onError={setErr} boardRef={boardRef}
        onSaveVariantTools={saveVariantTools}
        onSaveVariantYaml={saveVariantYaml}
        onFetchVariantYaml={(name) => TB.fetchDraftFile(id, name)}
      />

      {/* Files in the draft folder — the blaze spec + each recorded variant; all open the editor.
          Commit + destination now live in the header's "Finalize and Save". */}
      <DraftFilesSection title="Files" sub={d.home + '/'}
        right={<Btn sm kind="ghost" ico="refresh-cw" onClick={() => detail.reload()}>Refresh</Btn>}>
        {/* Every file in the folder, editable inline. blaze.yaml is the spec; each
            <platform>.trail.yaml is a recorded variant — all open the same editor drawer. */}
        <div {...clickable(() => openFile('blaze.yaml'))}
          style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '11px 14px', borderBottom: variants.length ? '1px solid var(--tb-hairline)' : 'none', cursor: 'pointer' }}>
          <Ico n="file-text" s={15} c="var(--tb-link)" />
          <span style={{ fontFamily: 'var(--font-mono)', fontSize: 13, color: 'var(--tb-link)' }}>blaze.yaml</span>
          <Chip tone="blue">source</Chip>
          <span style={{ flex: 1 }} />
          <span className="tb-sub" style={{ fontSize: 11 }}>{(d.steps || []).length} steps</span>
          <span style={{ fontSize: 11, color: 'var(--tb-link)', display: 'inline-flex', alignItems: 'center', gap: 4 }}><Ico n="pencil" s={12} /> edit</span>
        </div>
        {variants.map((v, vi) => (
          <div key={v.name} {...clickable(() => openFile(v.name))}
            style={{ display: 'flex', alignItems: 'center', gap: 11, padding: '11px 14px', borderBottom: vi < variants.length - 1 ? '1px solid var(--tb-hairline)' : 'none', cursor: 'pointer' }}>
            <Ico n="circle-play" s={15} c="var(--text-subtle)" />
            <span style={{ fontFamily: 'var(--font-mono)', fontSize: 13, color: 'var(--tb-link)' }}>{v.name}</span>
            <Chip>recording</Chip>
            <span style={{ flex: 1 }} />
            {v.platform && <span className="tb-sub" style={{ fontSize: 11 }}>{v.platform}</span>}
            <span style={{ fontSize: 11, color: 'var(--tb-link)', display: 'inline-flex', alignItems: 'center', gap: 4 }}><Ico n="pencil" s={12} /> edit</span>
          </div>
        ))}
      </DraftFilesSection>
      </>)}
      </div>
      )}

      {tab === 'runs' && (
        <div style={{ marginBottom: 24 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 11 }}>
            <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-standard)' }}>Run history</span>
            <span className="tb-sub" style={{ fontSize: 12 }}>every recording attempt, newest first · click to open its trace</span>
            <span style={{ flex: 1 }} />
            <Btn sm kind="ghost" ico="refresh-cw" onClick={() => detail.reload()}>Refresh</Btn>
          </div>
          {draftSessions.length === 0 ? (
            <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 12, background: 'var(--bg-subtle)', padding: 20 }}>
              <span className="tb-sub" style={{ fontSize: 13 }}>No runs yet. Record a device in the Blaze tab.</span>
            </div>
          ) : (
            <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 12, background: 'var(--bg-subtle)', overflow: 'hidden', padding: 6 }}>
              {[...draftSessions].sort((a, b) => (b.timestampMs || 0) - (a.timestampMs || 0)).map((s) => (
                <RecordingRow key={s.id} s={s} active={viewRun && viewRun.id === s.id} onClick={() => onViewRun(s)} />
              ))}
            </div>
          )}
        </div>
      )}
      </div>{/* /base content padding */}
      </div>{/* /base scroll layer */}

      {/* dim the page behind the push (cheap opacity fade, synced to the slide) */}
      {viewFile && <div style={{ position: 'absolute', inset: 0, zIndex: 4, background: 'rgba(0,0,0,.32)', pointerEvents: 'none', opacity: pushShown ? 1 : 0, transition: reduce ? 'none' : `opacity ${PUSH_MS}ms ${PUSH_EASE}` }} />}

      {viewFile && (() => {
        // The pushed editor page IS the shared trail detail view (Steps · Edit · Runs), opened to
        // Edit. Steps/Runs read this file directly (not the workspace index); Edit is the YAML+tools
        // editor. blaze.yaml gets the same treatment: its config + prompt steps + all draft runs.
        const isBlaze = viewFile.name === 'blaze.yaml';
        const fileCfg = parseTrailYaml(viewFile.content || '').config || {};
        const filePlat = (viewFile.name.match(/^(android|ios|web)\./) || [])[1] || null;
        const fileRuns = isBlaze || !filePlat ? draftSessions : draftSessions.filter((s) => (s.platform || '').toLowerCase() === filePlat);
        const fileTarget = fileCfg.target || d.target;
        const filePlatform = fileCfg.platform || d.platform || filePlat;
        return (
          <DetailPushPage
            shown={pushShown}
            title={viewFile.name}
            badges={<>
              {fileTarget && <Chip tone="blue">{fileTarget}</Chip>}
              {filePlatform && <Chip>{filePlatform}</Chip>}
              <Chip tone={isBlaze ? 'amber' : ''}>{isBlaze ? 'source' : 'recording'}</Chip>
            </>}
            onBack={closeFile}>
            <TrailDetailView
              yaml={viewFile.content}
              editable
              // blaze.yaml is the natural-language spec (prompts only) — tools live in the per-platform
              // recordings, never the blaze. No palette here keeps its editor focused on the prose.
              tools={isBlaze ? null : editorTools}
              onSave={(t) => TB.updateDraftFile(id, viewFile.name, t)}
              onSaved={() => { detail.reload(); setVariantReload((n) => n + 1); onChanged && onChanged(); }}
              configTrail={{ target: fileCfg.target || d.target, platform: fileCfg.platform || d.platform, driver: fileCfg.driver, priority: fileCfg.priority, tags: fileCfg.tags }}
              runs={fileRuns}
              go={go}
              defaultTab="edit"
              dirtyRef={editorDirty}
              highlight={viewFile.highlight}
            />
          </DetailPushPage>
        );
      })()}
      {active && railSlot && ReactDOM.createPortal(
        <AgentRail open={railOpen} onToggle={() => setRailOpen((v) => !v)} sessionId={liveSessionId} sessionRunning={liveSessionRunning} onApplyStep={applyReviewStep} />,
        railSlot,
      )}
    </div>
  );
}

// Color for a per-device health dot from its latest run status.
function runDotColor(st) {
  return (RUN_STATUS_DOT[st] || [null, 'var(--text-subtle)'])[1];
}

// One draft in the rail: folder · name · status · path, plus a compact per-device health summary
// (dots + count). The run history itself lives in the detail's Runs tab, not nested here.
function DraftRow({ d, devs, selected, onClick }) {
  useLucide();
  const [hover, setHover] = React.useState(false);
  const bg = selected ? 'var(--bg-standard)' : hover ? 'var(--bg-standard)' : 'transparent';
  return (
    <div role="button" onClick={onClick}
      onMouseEnter={() => setHover(true)} onMouseLeave={() => setHover(false)}
      style={{ padding: '11px 14px', borderTop: '1px solid var(--tb-hairline)', cursor: 'pointer', background: bg }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
        <Ico n={selected ? 'folder-open' : 'folder'} s={15} c="var(--text-subtle-variant)" style={{ flex: '0 0 auto', marginTop: 1 }} />
        <span style={{ fontSize: 14, fontWeight: selected ? 600 : 400, color: 'var(--text-standard)', lineHeight: 1.35, flex: 1, minWidth: 0, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{d.name}</span>
        {devs.length > 0 && (
          <span style={{ fontSize: 11, color: 'var(--text-subtle)', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', padding: '0 7px', borderRadius: 99, flex: '0 0 auto', lineHeight: '17px' }}>{devs.length}</span>
        )}
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 7 }}>
        <DraftStatusChip s={d.status} />
        <span className="tb-sub" style={{ fontSize: 11, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{d.inDrafts ? 'drafts/' : d.home}</span>
        {devs.length > 0 && (
          <span style={{ display: 'flex', gap: 5, flex: '0 0 auto', alignItems: 'center' }}>
            {devs.map((dv, i) => <PlatformGlyph key={i} platform={dv.platform} s={16} c={runDotColor(dv.status)} title={`${dv.platform || 'device'}: ${dv.status || 'not recorded'}`} />)}
          </span>
        )}
      </div>
    </div>
  );
}

function RecordingRow({ s, active, onClick }) {
  useLucide();
  const running = s.status === 'running';
  const [ico, col] = RUN_STATUS_DOT[s.status] || ['circle', 'var(--text-subtle)'];
  const variant = `${s.platform || 'run'}.trail.yaml`;
  const statusText = running ? 'recording' : (s.status || '');
  const timing = [s.dur && s.dur !== '—' ? s.dur : null, s.ago].filter(Boolean).join(' · ');
  return (
    <div role="button" onClick={onClick}
      style={{ padding: '7px 8px', borderRadius: 6, cursor: 'pointer', marginBottom: 2,
        background: active ? 'var(--bg-prominent)' : 'transparent', border: '1px solid ' + (active ? 'var(--tb-hairline-strong)' : 'transparent') }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Ico n={ico} s={13} c={col} spin={running} style={{ flex: '0 0 auto' }} />
        <span style={{ fontFamily: 'var(--font-mono)', fontSize: 12, color: 'var(--text-standard)', flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{variant}</span>
        <span style={{ fontSize: 10.5, color: col, fontWeight: 600, flex: '0 0 auto', textTransform: 'capitalize' }}>{statusText}</span>
      </div>
      {timing && <div className="tb-sub" style={{ fontSize: 10.5, marginLeft: 21, marginTop: 2 }}>{timing}</div>}
    </div>
  );
}

// Build the YAML snippet for a tool: `- toolId: {}` or `- toolId:` + `<param>: <type>` lines.
function toolSnippet(t) {
  const ps = t.parameters || [];
  if (!ps.length) return `- ${t.id}: {}`;
  return `- ${t.id}:\n` + ps.map((p) => `    ${p.name}: <${p.type || 'value'}>`).join('\n');
}

// macOS NavigationStack push: duration + a smooth-deceleration curve (the iOS push/sheet ease).
const PUSH_MS = 320;
const PUSH_EASE = 'cubic-bezier(.32,.72,0,1)';

// A pushed "page" that lives INSIDE the draft detail panel (not a window-level drawer). It slides in
// from the right while the page behind it parallaxes left + dims, and a back arrow (or Esc) pops it —
// the mac NavigationView controller feel. `shown` drives the slide; the parent keeps the page mounted
// through the pop animation, then unmounts. Pop goes through `onBack`, which guards unsaved edits.
function DetailPushPage({ shown, context, title, badges, onBack, children }) {
  useLucide();
  const reduce = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches);
  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onBack(); };
    document.addEventListener('keydown', onKey);
    return () => document.removeEventListener('keydown', onKey);
  }, [onBack]);
  return (
    <div style={{
      position: 'absolute', inset: 0, zIndex: 5, display: 'flex', flexDirection: 'column',
      background: 'var(--bg-window)', borderLeft: '1px solid var(--tb-hairline)',
      boxShadow: shown ? '-18px 0 50px rgba(0,0,0,.30)' : 'none',
      transform: reduce ? 'none' : (shown ? 'translateX(0)' : 'translateX(100%)'),
      transition: reduce ? 'none' : `transform ${PUSH_MS}ms ${PUSH_EASE}, box-shadow ${PUSH_MS}ms ${PUSH_EASE}`,
      willChange: 'transform',
    }}>
      {/* Same DetailHeader as the Trails detail (path · title · badges); a back arrow leads it. */}
      <div style={{ padding: '20px 28px 0', flex: '0 0 auto' }}>
        <DetailHeader context={context} title={title} badges={badges}
          leading={<span role="button" onClick={onBack} title="Back to draft (Esc)"
            style={{ display: 'inline-flex', alignItems: 'center', justifyContent: 'center', width: 30, height: 30, marginLeft: -6, borderRadius: 8, cursor: 'pointer', color: 'var(--text-subtle-variant)', flex: '0 0 auto', transition: 'background .12s, color .12s' }}
            onMouseEnter={(e) => { e.currentTarget.style.background = 'var(--bg-prominent)'; e.currentTarget.style.color = 'var(--text-standard)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; e.currentTarget.style.color = 'var(--text-subtle-variant)'; }}>
            <Ico n="arrow-left" s={18} />
          </span>} />
      </div>
      <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '14px 28px 20px' }}>{children}</div>
    </div>
  );
}

// Small labeled card used by the draft detail blocks.
function DraftFilesSection({ title, sub, right, children }) {
  return (
    <div style={{ marginBottom: 24 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 9, marginBottom: 11 }}>
        <span style={{ fontSize: 13, fontWeight: 700, color: 'var(--text-standard)' }}>{title}</span>
        {sub && <span className="tb-sub" style={{ fontSize: 12 }}>{sub}</span>}
        <span style={{ flex: 1 }} />
        {right}
      </div>
      <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 12, background: 'var(--bg-subtle)', overflow: 'hidden' }}>{children}</div>
    </div>
  );
}
