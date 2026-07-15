// @ts-nocheck -- Create-screen demonstrate-first phase components. Follows the Trail Runner
// browser-runtime pattern: Babel strips types at load, and top-level functions are cross-file
// globals (this file is loaded BEFORE external-agents.tsx in index.html, mirroring record.tsx).
//
// The demonstrate-first Create flow is a phased session, driven entirely by the demo run's
// server-side phase (run.demo.phase): positioning -> recording -> done. The user positions the
// app at the trail's starting screen, demonstrates the flow (every gesture becomes a step
// immediately, no describe-and-confirm), describes what the trail validates, and then launches
// the authoring agent. Refreshing the page restores the right phase from run.demo.

// ─── Shared trailhead picker ──────────────────────────────────────────────────
// The trailhead-picker data wiring (available trailheads, the selected one, its detail + params,
// and the "Go to trailhead" device drive), factored out of the Create chat so both the confirm-gate
// session and the demonstrate-first Position phase drive the same TrailheadStep card. Returns the
// `trailhead` object TrailheadStep consumes plus the fields the save / mark-start flows read.
// `onArrived` fires after a successful "Go to trailhead" drive (the chat uses it to refresh its
// element tree; the demo Position phase passes null).
function useTrailheadPicker(target, platform, mirror, onArrived) {
  const trailmaps = TB.useTrailmaps();
  const availTrailheads = React.useMemo(
    () => trailheadsForTarget(trailmaps.data, target, platform),
    [trailmaps.data, target, platform],
  );
  const [thByScope, setThByScope] = useStickyState('tb-interact-trailhead', {});
  const thScopeKey = (target || '') + '|' + (platform || '');
  const storedThId = thByScope[thScopeKey] || '';
  const selectedTh = availTrailheads.find((t) => t.name === storedThId)
    || (availTrailheads.length === 1 ? availTrailheads[0] : null);
  const setTrailheadId = (id) => setThByScope((m) => ({ ...m, [thScopeKey]: id }));
  const [thDetail, setThDetail] = React.useState(null); // { description, to, tools, className, name, relPath }
  const [thLoading, setThLoading] = React.useState(false);
  const [thRun, setThRun] = React.useState(null); // { running } | { ok, text }
  const [thParams, setThParams] = React.useState([]); // param schema for a CLASS-mode / scripted trailhead
  const [thArgs, setThArgs] = React.useState({});
  const [thMetaByName, setThMetaByName] = React.useState({});
  const availKey = availTrailheads.map((t) => t.relPath).join('|');
  React.useEffect(() => {
    let alive = true;
    Promise.all(availTrailheads.map(async (t) => {
      const p = parseTrailhead(await TB.fetchComponentSource(t.relPath), t.relPath.endsWith('.ts'));
      return [t.name, { to: (p && p.to) || null, description: (p && p.description) || '' }];
    })).then((pairs) => { if (alive) setThMetaByName(Object.fromEntries(pairs)); });
    return () => { alive = false; };
  }, [availKey]);
  const selectedThPath = selectedTh ? selectedTh.relPath : null;
  React.useEffect(() => {
    if (!selectedThPath) { setThDetail(null); setThParams([]); setThArgs({}); return; }
    let alive = true;
    setThLoading(true); setThRun(null); setThParams([]); setThArgs({});
    const scripted = selectedThPath.endsWith('.ts');
    TB.fetchComponentSource(selectedThPath).then(async (src) => {
      if (!alive) return;
      const parsed = parseTrailhead(src, scripted) || { description: '', to: null, tools: [], className: null };
      setThDetail({ ...parsed, name: selectedTh.name, relPath: selectedThPath });
      setThLoading(false);
      const applyParams = (params) => {
        if (!alive) return;
        setThParams(params || []);
        const seed = {};
        (params || []).forEach((p) => { if (p.required && p.validValues && p.validValues.length) seed[p.name] = p.validValues[0]; });
        if (Object.keys(seed).length) setThArgs((a) => ({ ...seed, ...a }));
      };
      if (parsed.className && !(parsed.tools && parsed.tools.length)) {
        applyParams(await TB.recordToolParams(parsed.className));
      } else if (scripted && target && TB.scriptedToolParams) {
        applyParams(await TB.scriptedToolParams(target, selectedTh.name));
      }
    }).catch(() => { if (alive) { setThDetail(null); setThLoading(false); } });
    return () => { alive = false; };
  }, [selectedThPath]);
  const accountParam = trailheadAccountParam(thParams);
  const accountValue = accountParam ? (thArgs[accountParam.name] || '') : '';
  const setAccount = (email) => { if (accountParam) setThArgs((a) => ({ ...a, [accountParam.name]: email })); };
  const thMissingRequired = thParams.filter((p) => paramVisible(p, thArgs) && p.required && !String(thArgs[p.name] || '').trim());
  const thFilledArgs = () => {
    const out = {};
    thParams.forEach((p) => { if (!paramVisible(p, thArgs)) return; const v = String(thArgs[p.name] || '').trim(); if (v) out[p.name] = v; });
    return out;
  };
  // Drives the device into the trailhead. A device move, not a recorded step - the trailhead is
  // baked into the saved trail as step 0, so replaying it now records nothing.
  async function goToTrailhead() {
    if (!selectedTh || !mirror.conn || (thRun && thRun.running)) return;
    if (thMissingRequired.length) { setThRun({ ok: false, text: 'Fill ' + thMissingRequired.map((p) => p.name).join(', ') + ' first.' }); return; }
    const yaml = buildTrailheadRunYaml(selectedTh.name, thDetail && thDetail.tools, thFilledArgs());
    if (!yaml) { setThRun({ ok: false, text: 'Could not build the trailhead run.' }); return; }
    setThRun({ running: true });
    const r = await TB.runToolQuick(yaml, mirror.tbId());
    setThRun({ ok: r.success === true, text: r.success === true ? (r.result || 'Arrived at the trailhead') : (r.error || 'Could not reach the trailhead') });
    if (onArrived) onArrived();
  }
  const trailhead = {
    target, platform, trailheads: availTrailheads, metaByName: thMetaByName,
    selected: selectedTh, onSelect: setTrailheadId, detail: thDetail, loading: thLoading,
    params: thParams, args: thArgs, onArg: (name, val) => setThArgs((a) => ({ ...a, [name]: val })),
    missingRequired: thMissingRequired,
    accountParam, accountValue, onAccount: setAccount,
    canRun: !!mirror.conn, onGoToTrailhead: goToTrailhead, run: thRun,
  };
  return { trailhead, availTrailheads, selectedTh, thDetail, thParams, thArgs, thMissingRequired, thFilledArgs };
}

// ─── Tape phase derivation ────────────────────────────────────────────────────
// A demonstrated interaction's phase, straight from the server when it tags the event (top-level
// `phase` or inside the human_action output payload). Null when the build predates phase tagging.
function demoEventPhase(e) {
  if (e && e.phase) return e.phase;
  const out = parseMaybeJson(e && e.output);
  return (out && out.phase) || null;
}

// The demonstrated STEPS: human_action events after mark-start. Prefer the server's own phase tag;
// fall back to the client-remembered mark-start timestamp (setup taps happened before it); with no
// signal at all, treat every human_action as a step so a restored session still shows its work.
function demoStepEvents(events, markStartMs) {
  return (events || []).filter((e) => {
    if (e.kind !== 'human_action') return false;
    const ph = demoEventPhase(e);
    if (ph) return ph === 'step';
    if (markStartMs != null) return e.timeMs >= markStartMs;
    return true;
  });
}

// ─── The phased flow ──────────────────────────────────────────────────────────

// The whole demonstrate-first session, rendered instead of the agent chat when the selected run is
// a demo run (run.demo present). The phase is the server's (run.demo.phase); this component only
// drives the device, appends steps, collects the objective, and launches generation.
function DemonstrateFlow({ run, runs, supported, go, active, onReload }) {
  useLucide();
  const [gt] = TB.useGlobalTarget();
  const target = gt && gt.target;
  const phase = (run.demo && run.demo.phase) || 'positioning';

  const mirror = useAgentDeviceMirror(active && !!run);
  const stageDevice = mirror.deviceList.find((d) => d.id === mirror.selectedId) || null;
  const stagePlatform = stageDevice ? stageDevice.platform : null;
  const th = useTrailheadPicker(target, stagePlatform, mirror, null);

  const running = !!run && run.status === 'running';
  const eventsHook = TB.useExternalAgentEvents(run.id, running, null, true);
  const events = eventsHook.data || [];

  // The mark-start boundary, remembered per run so a page refresh (which loses component state but
  // keeps the phase) still separates setup taps from demonstrated steps. Written when mark-start
  // succeeds; re-read when the selected run changes.
  const [markStartMs, setMarkStartMs] = React.useState(() => readDemoMarkStart(run.id));
  React.useEffect(() => { setMarkStartMs(readDemoMarkStart(run.id)); }, [run.id]);
  // Steps deleted this session, tombstoned client-side: the events hook only ever merges, so the
  // server-deleted event would linger in its state until a fresh load. The server removal is what
  // keeps the tombstone honest - the next cold fetch simply doesn't contain the event.
  const [deletedIds, setDeletedIds] = React.useState({});
  React.useEffect(() => { setDeletedIds({}); }, [run.id]);
  const tape = React.useMemo(
    () => demoStepEvents(events, markStartMs).filter((e) => !deletedIds[e.id]),
    [events, markStartMs, deletedIds],
  );

  // ── Delete a mistaken step mid-recording ──
  const [deletingId, setDeletingId] = React.useState(null);
  const [deleteErr, setDeleteErr] = React.useState(null);
  async function deleteStep(e) {
    if (deletingId) return;
    setDeletingId(e.id); setDeleteErr(null);
    const r = await TB.demoDeleteStep(run.id, { eventId: e.id });
    setDeletingId(null);
    if (!r || r.ok === false) { setDeleteErr((r && r.error) || 'Could not delete that step.'); return; }
    setDeletedIds((m) => ({ ...m, [e.id]: true }));
  }

  // ── Position -> Recording ──
  const [starting, setStarting] = React.useState(false);
  const [startErr, setStartErr] = React.useState(null);
  async function startDemonstrating() {
    if (starting || !mirror.conn) return;
    if (th.selectedTh && th.thMissingRequired.length) {
      setStartErr('Fill the trailhead required ' + (th.thMissingRequired.length === 1 ? 'parameter' : 'parameters')
        + ' (' + th.thMissingRequired.map((p) => p.name).join(', ') + ') first.');
      return;
    }
    setStarting(true); setStartErr(null);
    const trailheadPayload = th.selectedTh ? {
      name: th.selectedTh.name,
      args: th.thFilledArgs(),
      yaml: trailheadStepYaml(th.selectedTh.name, th.thParams, th.thArgs),
    } : null;
    const r = await TB.demoMarkStart(run.id, trailheadPayload);
    setStarting(false);
    if (!r || r.ok === false) { setStartErr((r && r.error) || 'Could not start the demonstration.'); return; }
    writeDemoMarkStart(run.id);
    setMarkStartMs(readDemoMarkStart(run.id));
    if (onReload) onReload();
  }

  // ── Demonstrate: text entry + hardware keys ──
  const [gestureErr, setGestureErr] = React.useState(null);
  const [gestureBusy, setGestureBusy] = React.useState(false);
  async function sendText(text) {
    const t = String(text || '').trim();
    if (!t || gestureBusy) return { ok: false };
    setGestureBusy(true); setGestureErr(null);
    const r = await mirror.sendGesture({ type: 'inputText', text: t, runId: run.id });
    setGestureBusy(false);
    if (!r || r.ok === false) { setGestureErr((r && r.error) || 'Could not type that.'); return { ok: false }; }
    return { ok: true };
  }
  async function pressKey(key) {
    if (gestureBusy) return;
    setGestureBusy(true); setGestureErr(null);
    const r = await mirror.sendGesture({ type: 'pressKey', key, runId: run.id });
    setGestureBusy(false);
    if (!r || r.ok === false) setGestureErr((r && r.error) || ('Could not press ' + key + '.'));
  }

  // ── Recording -> Done (describe) ──
  const [describeOpen, setDescribeOpen] = React.useState(false);
  const [finishing, setFinishing] = React.useState(false);
  const [finishErr, setFinishErr] = React.useState(null);
  async function finishDemo({ objective, notes }) {
    if (finishing) return;
    setFinishing(true); setFinishErr(null);
    const r = await TB.demoFinish(run.id, { objective, notes });
    setFinishing(false);
    if (!r || r.ok === false) { setFinishErr((r && r.error) || 'Could not save the objective.'); return; }
    setDescribeOpen(false);
    if (onReload) onReload();
  }

  // ── Done -> Generate ──
  const [generating, setGenerating] = React.useState(false);
  const [genErr, setGenErr] = React.useState(null);
  // The generation run id, seeded from the launch response so the embedded transcript appears
  // immediately - before the next poll folds run.demo.generationRunId in. The server value wins
  // once it lands.
  const [genIdLocal, setGenIdLocal] = React.useState(null);
  React.useEffect(() => { setGenIdLocal(null); }, [run.id]);
  const generationRunId = (run.demo && run.demo.generationRunId) || genIdLocal || null;
  async function generate({ agentType, model, sandbox }) {
    if (generating) return;
    setGenerating(true); setGenErr(null);
    const r = await TB.demoGenerate(run.id, { agentType, model, sandbox });
    setGenerating(false);
    if (!r || r.ok === false) {
      // The generation endpoint is the S3 server slice; if it isn't in this daemon build yet, say so
      // plainly instead of surfacing a raw 404.
      setGenErr(r && r.status === 404
        ? 'Trail generation is not available in this Trail Runner build yet.'
        : ((r && r.error) || 'Could not start trail generation.'));
      return;
    }
    // Stay on the demo run - the generation is embedded here, never its own sidebar entry.
    if (r.generationRunId) setGenIdLocal(r.generationRunId);
    if (onReload) onReload();
  }

  // ── Add another platform (post-verify) ──
  const [addingPlatform, setAddingPlatform] = React.useState(false);
  const [addPlatformErr, setAddPlatformErr] = React.useState(null);
  async function addPlatform(deviceId) {
    if (addingPlatform) return;
    setAddingPlatform(true); setAddPlatformErr(null);
    const r = await TB.demoAddPlatform(run.id, { deviceId });
    setAddingPlatform(false);
    if (!r || r.ok === false) { setAddPlatformErr((r && r.error) || 'Could not start another platform.'); return { ok: false }; }
    setGenIdLocal(null);
    // Point the stage mirror at the device the demo now targets - the picker offers mirror's own
    // deviceList, so the id vocabulary matches. Without this the stage keeps streaming platform 1.
    mirror.setSelectedId(deviceId);
    if (onReload) onReload();
    return { ok: true };
  }

  // The stepper's active step: Position(1) -> Record(2) -> Describe(3, the dialog is open) ->
  // Generate(4). Describe is a sub-state of recording (the dialog over the record phase).
  const activeStep = phase === 'positioning' ? 1
    : phase === 'recording' ? (describeOpen ? 3 : 2)
    : 4;
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ padding: '14px 24px 10px', flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 10 }}>
        <Ico n="clapperboard" s={15} c="var(--tb-ai)" />
        <span style={{ flex: 1, minWidth: 0, fontSize: 14, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{run.title || run.id}</span>
        {run.demo && run.demo.bundleDir && (
          <Btn sm ico="folder-open" title="Reveal the demonstration bundle (actions + evidence) in Finder"
            onClick={() => TB.demoRevealBundle(run.id)}>Open in Finder</Btn>
        )}
      </div>
      <div style={{ padding: '4px 24px 14px', flex: '0 0 auto', borderBottom: '1px solid var(--tb-hairline)' }}>
        <DemoStepper step={activeStep} />
      </div>
      {phase === 'positioning' && (
        <DemoPositionPhase mirror={mirror} run={run} trailhead={th.trailhead} go={go}
          onStart={startDemonstrating} starting={starting} startErr={startErr} canStart={!!mirror.conn} />
      )}
      {phase === 'recording' && (
        <DemoDemonstratePhase mirror={mirror} run={run} tape={tape} platform={stagePlatform}
          onText={sendText} onKey={pressKey} gestureErr={gestureErr} gestureBusy={gestureBusy}
          onDeleteStep={deleteStep} deletingId={deletingId} deleteErr={deleteErr}
          onDone={() => setDescribeOpen(true)} />
      )}
      {phase === 'done' && (
        <DemoGeneratePhase run={run} runs={runs} supported={supported} go={go} mirror={mirror}
          objective={run.demo && run.demo.objective} demo={run.demo} generationRunId={generationRunId}
          onGenerate={generate} generating={generating} genErr={genErr} onReload={onReload}
          onAddPlatform={addPlatform} addingPlatform={addingPlatform} addPlatformErr={addPlatformErr} />
      )}
      {describeOpen && (
        <DemoDescribeDialog busy={finishing} error={finishErr}
          initialObjective={(run.demo && run.demo.objective) || ''}
          onCancel={() => { if (!finishing) { setDescribeOpen(false); setFinishErr(null); } }}
          onSubmit={finishDemo} />
      )}
    </div>
  );
}

// The guided-flow progress across the top: four numbered steps with visible labels, connected by
// hairline segments that fill green as they're passed. A done step goes green with a check, the
// current step wears the AI accent, and future steps stay neutral - so where you are, where you've
// been, and where you're headed all read at a glance. `step` is 1-4.
function DemoStepper({ step }) {
  const labels = ['Position', 'Record', 'Describe', 'Generate'];
  return (
    <div style={{ display: 'flex', alignItems: 'center', maxWidth: 640, margin: '0 auto' }}>
      {labels.map((label, i) => {
        const n = i + 1;
        const done = n < step;
        const current = n === step;
        return (
          <React.Fragment key={label}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: '0 0 auto' }}>
              <span style={{ width: 22, height: 22, borderRadius: 99, display: 'inline-grid', placeItems: 'center', fontSize: 11, fontWeight: 700, flex: '0 0 auto',
                border: '1px solid ' + (done ? 'transparent' : current ? 'var(--tb-ai)' : 'var(--tb-hairline-strong)'),
                background: done ? 'rgba(0,224,19,.14)' : current ? 'rgba(181,140,255,.14)' : 'transparent',
                color: done ? 'var(--tb-pass)' : current ? 'var(--tb-ai)' : 'var(--text-subtle)' }}>
                {done ? <Ico n="check" s={12} c="var(--tb-pass)" /> : n}
              </span>
              <span style={{ fontSize: 12.5, fontWeight: current ? 700 : 600, color: current ? 'var(--text-standard)' : done ? 'var(--text-subtle-variant)' : 'var(--text-subtle)' }}>{label}</span>
            </div>
            {i < labels.length - 1 && (
              <span style={{ flex: 1, minWidth: 16, height: 2, margin: '0 10px', borderRadius: 99, background: done ? 'var(--tb-pass)' : 'var(--tb-hairline-strong)' }} />
            )}
          </React.Fragment>
        );
      })}
    </div>
  );
}

// The one instruction each phase leads with: a single sentence, an optional sub-sentence, one
// consistent style - never a wall of text. The AI-accent left border ties it to the guided flow.
function DemoInstruction({ ico, title, sub }) {
  return (
    <div style={{ border: '1px solid var(--tb-hairline)', borderLeft: '3px solid var(--tb-ai)', borderRadius: 10, background: 'var(--bg-subtle)', padding: '11px 13px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: sub ? 5 : 0 }}>
        <Ico n={ico} s={14} c="var(--tb-ai)" style={{ flex: '0 0 auto' }} />
        <span style={{ fontSize: 13, fontWeight: 700, lineHeight: 1.4 }}>{title}</span>
      </div>
      {sub && <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.55 }}>{sub}</div>}
    </div>
  );
}

// Position: get the app to the trail's starting screen. The trailhead picker is the preferred way
// (deterministic, baked into the trail); free-driving on the stage is allowed and captured as setup.
function DemoPositionPhase({ mirror, run, trailhead, go, onStart, starting, startErr, canStart }) {
  const hasTrailheads = trailhead && trailhead.trailheads && trailhead.trailheads.length > 0;
  return (
    <div style={{ flex: 1, minHeight: 0, minWidth: 0, display: 'flex' }}>
      <div style={{ flex: '0 1 400px', minWidth: 300, minHeight: 0, display: 'flex', flexDirection: 'column', borderRight: '1px solid var(--tb-hairline)', overflowY: 'auto', padding: '16px 16px 14px', gap: 12 }}>
        <DemoInstruction ico="crosshair"
          title="Bring the app to the screen where your trail should start."
          sub="Use a deeplink or trailhead if you can - it makes the trail reproducible. Nothing is recorded yet. Press Start recording when you're there." />
        {hasTrailheads ? (
          <div>
            <div className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 6 }}>Preferred: start from a trailhead</div>
            <TrailheadStep
              target={trailhead.target} platform={trailhead.platform} trailheads={trailhead.trailheads} metaByName={trailhead.metaByName}
              selected={trailhead.selected} onSelect={trailhead.onSelect} detail={trailhead.detail} loading={trailhead.loading}
              params={trailhead.params} args={trailhead.args} onArg={trailhead.onArg} missingRequired={trailhead.missingRequired}
              accountParam={trailhead.accountParam} accountValue={trailhead.accountValue} onAccount={trailhead.onAccount}
              canRun={trailhead.canRun} onGoToTrailhead={trailhead.onGoToTrailhead} run={trailhead.run} go={go}
              expanded onExpand={() => {}} />
          </div>
        ) : (
          <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, padding: '0 2px' }}>
            This target has no trailheads yet - drive the device to the starting screen yourself, then Start.
          </div>
        )}
        <div style={{ flex: 1 }} />
        {startErr && (
          <div role="alert" style={{ display: 'flex', gap: 7, alignItems: 'flex-start', padding: '7px 10px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', background: 'rgba(255,90,90,.06)' }}>
            <Ico n="triangle-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
            <span style={{ fontSize: 12, color: 'var(--tb-danger-text)', fontWeight: 600, lineHeight: 1.45 }}>{startErr}</span>
          </div>
        )}
        <Btn kind="primary" ico={starting ? 'loader-2' : 'circle-play'} spin={starting} disabled={starting || !canStart} onClick={onStart}
          title={!canStart ? 'Connect a device first' : 'Mark this screen as the trail start and begin recording'}>
          {starting ? 'Starting…' : 'Start recording'}
        </Btn>
        {!canStart && <div className="tb-sub" style={{ fontSize: 10.5, textAlign: 'center' }}>Connect a device to begin.</div>}
      </div>
      <AgentRecordStage mirror={mirror} runId={run.id} hasSteps mode="position" inspect={null} stepBusy={false} />
    </div>
  );
}

// Record: the mirror is primary. Every gesture drives the device AND appends a step to the tape
// immediately - no describe-and-confirm. The stage owns the device column, with text entry and
// Back/Home directly beneath it (the interactions a tap can't express). The right rail is the
// growing step list, with Finish recording pinned at its foot.
function DemoDemonstratePhase({ mirror, run, tape, platform, onText, onKey, gestureErr, gestureBusy, onDeleteStep, deletingId, deleteErr, onDone }) {
  const [text, setText] = React.useState('');
  const scrollRef = React.useRef(null);
  React.useEffect(() => { const el = scrollRef.current; if (el) el.scrollTop = el.scrollHeight; }, [tape.length]);
  const submitText = async () => {
    const r = await onText(text);
    if (r && r.ok) setText('');
  };
  return (
    <div style={{ flex: 1, minHeight: 0, minWidth: 0, display: 'flex' }}>
      {/* Device column: the stage, then the text + hardware-key controls directly under it. */}
      <div style={{ flex: 1, minWidth: 240, minHeight: 0, display: 'flex', flexDirection: 'column', background: 'var(--bg-standard)' }}>
        <AgentRecordStage mirror={mirror} runId={run.id} hasSteps mode="record" inspect={null} stepBusy={false} />
        <div style={{ flex: '0 0 auto', padding: '0 18px 16px', display: 'flex', justifyContent: 'center' }}>
          <div style={{ width: '100%', maxWidth: 460, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {gestureErr && (
              <div role="alert" style={{ display: 'flex', gap: 7, alignItems: 'flex-start', padding: '7px 10px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', background: 'rgba(255,90,90,.06)' }}>
                <Ico n="triangle-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
                <span style={{ fontSize: 11.5, color: 'var(--tb-danger-text)', fontWeight: 600, lineHeight: 1.45 }}>{gestureErr}</span>
              </div>
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <input value={text} spellCheck={false} disabled={gestureBusy}
                onChange={(e) => setText(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); submitText(); } }}
                placeholder="Type text into the focused field…"
                style={{ flex: 1, minWidth: 0, background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: 'var(--text-standard)', padding: '7px 10px', font: 'inherit', fontSize: 12.5 }} />
              <Btn sm ico={gestureBusy ? 'loader-2' : 'corner-down-left'} spin={gestureBusy} disabled={gestureBusy || !text.trim()} onClick={submitText} title="Type this text on the device (records an inputText step)">Type</Btn>
              {platform === 'android' && (
                <Btn sm ico="arrow-left" disabled={gestureBusy} onClick={() => onKey('Back')} title="Press Back (records a pressKey step)">Back</Btn>
              )}
              <Btn sm ico="house" disabled={gestureBusy} onClick={() => onKey('Home')} title="Press Home (records a pressKey step)">Home</Btn>
            </div>
          </div>
        </div>
      </div>
      {/* Right rail: the instruction, the growing step list, and Finish recording pinned below.
          overflow:'hidden' bounds the column to its flex allocation - without it, the scroller's
          minHeight:100 floor plus the pinned instruction + Finish footer can exceed a short rail's
          height and the overflow escapes up to the .tb-screen scroll container, growing the page. */}
      <div style={{ flex: '0 1 400px', minWidth: 300, minHeight: 0, overflow: 'hidden', borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
        <div style={{ flex: '0 0 auto', padding: '12px 14px 0' }}>
          <DemoInstruction ico="hand-pointer"
            title="Do the flow you want to test, one action at a time."
            sub="Tap, swipe, and type on the device - every action becomes a step. Press Finish recording when the flow is complete." />
        </div>
        <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '10px 14px' }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingBottom: 8 }}>
            <Ico n="list-video" s={14} c="var(--tb-pass)" />
            <span style={{ fontSize: 13, fontWeight: 700 }}>Steps</span>
            <span style={{ flex: 1 }} />
            <Chip tone={tape.length ? 'green' : ''}>{tape.length} step{tape.length === 1 ? '' : 's'}</Chip>
          </div>
          <div ref={scrollRef} style={{ flex: 1, minHeight: 100, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 8 }}>
            {tape.length === 0
              ? <EmptyState ico="hand-pointer" title="No steps yet" sub="Tap, type, and swipe on the device. Every action you take is recorded as a step here, in order." />
              : tape.map((e, i) => <AgentTapeCard key={e.id || i} e={e} index={i} compact
                  onDelete={() => onDeleteStep(e)} deleting={deletingId === e.id} />)}
          </div>
          {deleteErr && (
            <div role="alert" style={{ display: 'flex', gap: 7, alignItems: 'flex-start', marginTop: 8, padding: '7px 10px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', background: 'rgba(255,90,90,.06)' }}>
              <Ico n="triangle-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
              <span style={{ fontSize: 11.5, color: 'var(--tb-danger-text)', fontWeight: 600, lineHeight: 1.45 }}>{deleteErr}</span>
            </div>
          )}
        </div>
        <div style={{ flex: '0 0 auto', borderTop: '1px solid var(--tb-hairline)', padding: '10px 14px' }}>
          <Btn kind="primary" ico="flag" disabled={tape.length === 0} onClick={onDone} style={{ width: '100%', justifyContent: 'center' }}
            title={tape.length === 0 ? 'Record at least one step first' : 'Finish recording and describe what this trail proves'}>Finish recording</Btn>
        </div>
      </div>
    </div>
  );
}

// Describe: the objective this trail validates (required) plus optional notes. Styled dialog
// (tb-overlay/tb-card), mirroring SaveTrailDialog - never window.prompt/confirm. On a later
// platform the objective already exists - pre-fill it so finishing is one click, not a re-type.
function DemoDescribeDialog({ busy, error, initialObjective, onCancel, onSubmit }) {
  const [objective, setObjective] = React.useState(initialObjective || '');
  const [notes, setNotes] = React.useState('');
  const ok = objective.trim() !== '' && !busy;
  return (
    <div className="tb-overlay" onClick={() => { if (!busy) onCancel(); }} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(560px, 94vw)', padding: 24 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 4 }}>
          <h2 className="tb-h2" style={{ fontSize: 17, margin: 0 }}>What should this trail prove?</h2>
          <Btn sm ico="x" onClick={onCancel} disabled={busy}>Close</Btn>
        </div>
        <div className="tb-sub" style={{ fontSize: 12.5, marginBottom: 16 }}>Describe the specific outcome that proves this flow works - a value, screen, or confirmation that must be true at the end. This grounds the trail the agent authors.</div>
        <div className="tb-eyebrow" style={{ marginBottom: 6 }}>Objective <span style={{ color: 'var(--tb-danger-text)' }}>*</span></div>
        <textarea autoFocus value={objective} rows={3} spellCheck disabled={busy}
          onChange={(e) => setObjective(e.target.value)}
          onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey) && ok) onSubmit({ objective: objective.trim(), notes: notes.trim() || null }); if (e.key === 'Escape' && !busy) onCancel(); }}
          placeholder="A user can send $5 and see the payment confirmed on the receipt screen"
          style={{ width: '100%', boxSizing: 'border-box', resize: 'vertical', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '8px 11px', color: 'var(--text-standard)', font: 'inherit', fontSize: 13, lineHeight: 1.5, outline: 'none' }} />
        <div className="tb-eyebrow" style={{ margin: '14px 0 6px' }}>Notes <span className="tb-sub" style={{ fontWeight: 600, letterSpacing: 0, textTransform: 'none' }}>(optional)</span></div>
        <textarea value={notes} rows={2} spellCheck disabled={busy}
          onChange={(e) => setNotes(e.target.value)}
          placeholder="Anything the agent should know - edge cases, what to ignore, which variation you demonstrated"
          style={{ width: '100%', boxSizing: 'border-box', resize: 'vertical', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '8px 11px', color: 'var(--text-standard)', font: 'inherit', fontSize: 13, lineHeight: 1.5, outline: 'none' }} />
        {error && <div role="alert" style={{ marginTop: 10, fontSize: 12, color: 'var(--tb-danger-text)' }}>{error}</div>}
        <div style={{ display: 'flex', justifyContent: 'flex-end', gap: 8, marginTop: 18 }}>
          <Btn onClick={onCancel} disabled={busy}>Cancel</Btn>
          <Btn kind="primary" ico={busy ? 'loader-2' : 'check'} spin={busy} onClick={() => { if (ok) onSubmit({ objective: objective.trim(), notes: notes.trim() || null }); }} disabled={!ok}>
            {busy ? 'Saving…' : 'Save and continue'}
          </Btn>
        </div>
      </div>
    </div>
  );
}

// Generate: the demonstration is captured and described - launch the authoring agent to turn it
// into a durable, verified trail. Before launch: the agent/access/model pickers. After launch: the
// generation run's own transcript, embedded inline (never a separate sidebar entry), with a Stop
// control in its composer and - once it lands - a verified-and-saved panel or an honest warning.
function DemoGeneratePhase({ run, runs, supported, go, mirror, objective, demo, generationRunId, onGenerate, generating, genErr, onReload, onAddPlatform, addingPlatform, addPlatformErr }) {
  const [agentType, setAgentType] = useStickyState('tb-external-agent-type', 'claude');
  const [access, setAccess] = useStickyState('tb-external-agent-sandbox', 'workspace-write');
  const [model, setModel] = useStickyState('tb-external-agent-model', '');
  const available = (supported || []).filter((a) => a.available !== false);
  const selected = (supported || []).find((a) => a.id === agentType) || null;
  const selectedAvailable = !selected || selected.available !== false;
  React.useEffect(() => {
    if (available.length && !available.some((a) => a.id === agentType)) setAgentType(available[0].id);
  }, [available.map((a) => a.id).join(',')]);
  const modelOptions = (selected && selected.models && selected.models.length) ? selected.models : [{ id: '', display: 'Default' }];
  const modelValue = modelOptions.some((m) => m.id === model) ? model : '';
  const agentOptions = (supported && supported.length ? supported : [{ id: 'claude', display: 'Claude Code', available: true }])
    .map((a) => ({ id: a.id, display: `${a.display}${a.available === false ? ' (not installed)' : ''}` }));

  // The generation run and its live event stream, embedded here rather than opened as its own
  // conversation. It may not be in `runs` yet on the poll right after launch, so fall back to a
  // minimal running stand-in keyed by generationRunId - the events hook drives the transcript either way.
  const foundGenRun = React.useMemo(() => (generationRunId ? (runs || []).find((r) => r.id === generationRunId) : null), [runs, generationRunId]);
  const genRun = generationRunId ? (foundGenRun || { id: generationRunId, status: 'running', title: 'Generating trail' }) : null;
  const genRunning = !!genRun && genRun.status === 'running';
  const genEventsHook = TB.useExternalAgentEvents(generationRunId || null, genRunning, null, true);
  const genEvents = genEventsHook.data || [];
  const pendingQuestions = React.useMemo(() => pendingAskQuestions(genEvents), [genEvents]);

  // Success is the server marking the trail verified, or the agent declaring a verified trail_output.
  const outputVerified = React.useMemo(
    () => (genEvents || []).some((e) => e.kind === 'ui_command' && e.uiCommand && e.uiCommand.action === 'trail_output'
      && e.uiCommand.params && String(e.uiCommand.params.verified) === 'true'),
    [genEvents],
  );
  const composed = React.useMemo(() => detectComposedTrail(genEvents), [genEvents]);
  const trailId = (demo && demo.trailId) || (composed && composed.trailId) || null;
  const verified = (demo && demo.trailVerified === true) || outputVerified;
  const genFinished = !!genRun && genRun.status !== 'running';
  // A landed-but-unverified result (a draft, or a finished run that produced a trail without passing
  // verification) gets an honest warning - never dressed up as success.
  const showWarn = !verified && genFinished && (!!composed || !!trailId);
  // Server sends platforms as {key, done} objects; everything downstream (covered-set, chips,
  // React keys) wants the plain platform key strings.
  const platforms = (demo && demo.platforms && demo.platforms.length) ? demo.platforms.map((p) => (p && p.key) ? p.key : p)
    : (demo && demo.platform ? [demo.platform] : []);

  // Is the agent running a trail on the device right now? A trail-run tool call that hasn't
  // returned yet is the signal: the center device pane appears only for that window, so the human
  // watches the verification replay, and collapses back to transcript + trail rail otherwise.
  const runInFlight = React.useMemo(() => {
    if (!genRunning) return false;
    const done = new Set((genEvents || []).filter((e) => e.kind === 'tool_result' && e.toolCallId).map((e) => e.toolCallId));
    return (genEvents || []).some((e) => {
      if (e.kind !== 'tool_call') return false;
      if (e.toolCallId && done.has(e.toolCallId)) return false;
      const name = String(e.toolName || '');
      if (/^mcp__trailblaze__/.test(name) && /trail/i.test(name)) return true;
      return /trailblaze\s+run/i.test(String(e.input || ''));
    });
  }, [genEvents, genRunning]);

  // Pre-launch: the picker card.
  if (!generationRunId) {
    return (
      <div style={{ flex: 1, minHeight: 0, display: 'flex', justifyContent: 'center', overflowY: 'auto' }}>
        <div style={{ width: 'min(620px, 100%)', padding: 'clamp(16px, 4vh, 40px) 24px 32px' }}>
          <DemoInstruction ico="wand-sparkles"
            title="An agent authors the trail from your demonstration, then verifies it by running it."
            sub="Your demonstration and objective are captured. Pick the agent and start - it writes a durable, selector-based trail and proves it replays." />
          <div style={{ marginTop: 14, border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-subtle)', padding: '13px 15px' }}>
            <div className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 6 }}>Objective</div>
            <div data-selectable style={{ fontSize: 13, lineHeight: 1.5 }}>{objective || 'No objective recorded.'}</div>
          </div>
          <div style={{ marginTop: 16, border: '1px solid var(--tb-hairline-strong)', borderRadius: 12, background: 'var(--bg-subtle)', padding: '12px 14px', display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
            <AgentQuietPicker title="Agent" value={agentType} onChange={setAgentType} options={agentOptions} />
            <AgentQuietPicker title="Access level" value={access} onChange={setAccess}
              options={EXTERNAL_AGENT_ACCESS_OPTIONS.map(([id, display]) => ({ id, display }))} />
            <AgentQuietPicker title="Model" value={modelValue} options={modelOptions} onChange={setModel} />
            <span style={{ flex: 1 }} />
            <Btn kind="primary" ico={generating ? 'loader-2' : 'wand-sparkles'} spin={generating} disabled={generating || !selectedAvailable}
              onClick={() => onGenerate({ agentType, model: modelValue || null, sandbox: access })}>
              {generating ? 'Starting…' : 'Generate trail'}
            </Btn>
          </div>
          {/* Full access lets the authoring agent run any command without prompting - flag it in
              danger red at launch so the choice is deliberate, not a default the eye slid past. */}
          {access === 'danger-full-access' && (
            <div style={{ display: 'flex', gap: 7, alignItems: 'flex-start', marginTop: 10, padding: '8px 11px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', borderLeft: '3px solid var(--tb-danger-text)', background: 'rgba(255,90,90,.06)' }}>
              <Ico n="shield-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
              <span style={{ fontSize: 11.5, color: 'var(--tb-danger-text)', fontWeight: 600, lineHeight: 1.45 }}>Full access lets the agent run any command without asking. Use it only for a flow you trust.</span>
            </div>
          )}
          {genErr && (
            <div role="alert" style={{ display: 'flex', gap: 7, alignItems: 'flex-start', marginTop: 10, padding: '7px 10px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', background: 'rgba(255,90,90,.06)' }}>
              <Ico n="triangle-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
              <span data-selectable style={{ fontSize: 12, color: 'var(--tb-danger-text)', fontWeight: 600, lineHeight: 1.45 }}>{genErr}</span>
            </div>
          )}
        </div>
      </div>
    );
  }

  // Post-launch: the generation studio. A result banner (if any) pins above three viewport-bounded,
  // independently-scrollable panes: the agent's narration on the left, the device it's driving in
  // the center (only while a trail run is in flight), and the trail it's writing streaming in on the
  // right. Every level carries minHeight:0 so each pane scrolls internally and the page never grows.
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      {verified ? (
        <div style={{ flex: '0 0 auto', padding: '14px 24px 0' }}>
          <DemoVerifiedPanel trailId={trailId} platforms={platforms} mirror={mirror} go={go}
            onAddPlatform={onAddPlatform} addingPlatform={addingPlatform} addPlatformErr={addPlatformErr} />
        </div>
      ) : showWarn ? (
        <div style={{ flex: '0 0 auto', padding: '14px 24px 0' }}>
          <div style={{ border: '1px solid var(--tb-hairline)', borderLeft: '3px solid var(--tb-amber)', borderRadius: 10, background: 'var(--bg-subtle)', padding: '12px 14px' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
              <Ico n="triangle-alert" s={16} c="var(--tb-amber)" style={{ flex: '0 0 auto' }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13.5, fontWeight: 700 }}>Trail saved, but not verified</div>
                <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5, marginTop: 2 }}>The agent wrote a draft but it hasn't passed a verification run. Open it in Trails to review and finish verifying.</div>
              </div>
              {trailId && <Btn sm ico="arrow-up-right" onClick={() => go('trails', { sel: trailId })}>Open in Trails</Btn>}
            </div>
          </div>
        </div>
      ) : null}
      <div style={{ flex: 1, minHeight: 0, minWidth: 0, display: 'flex' }}>
        {/* LEFT: the agent's narration - the embedded generation transcript, in narrow-rail mode.
            Grows to fill when the center device pane is collapsed (no trail running) so the row
            never leaves a gap; holds its ~460px basis while the device pane is up. */}
        <div style={{ flex: runInFlight ? '0 1 460px' : '1 1 460px', minWidth: 340, minHeight: 0, display: 'flex', flexDirection: 'column', borderRight: '1px solid var(--tb-hairline)' }}>
          <AgentConversationColumn run={genRun} supported={supported} go={go}
            events={genEvents} eventsLoading={genEventsHook.loading} running={genRunning}
            pins={{}} onStarted={onReload} onTurn={onReload}
            pendingQuestions={pendingQuestions} recording={false} narrow />
        </div>
        {/* CENTER: the device, read-only, only while the agent is running a trail on it. */}
        {runInFlight && (
          <div style={{ flex: 1, minWidth: 240, minHeight: 0, display: 'flex', flexDirection: 'column', borderRight: '1px solid var(--tb-hairline)' }}>
            <AgentRecordStage mirror={mirror} runId={generationRunId} hasSteps mode="record" inspect={null} stepBusy={false} readOnly />
          </div>
        )}
        {/* RIGHT: the trail the agent is writing, streamed in with a typewriter as it lands. */}
        <DemoLiveTrailRail demoRunId={run.id} working={genRunning} />
      </div>
    </div>
  );
}

// The live trail rail: the trail file(s) the generation agent is writing, polled while it works and
// revealed with a typewriter so the authoring reads as it happens rather than blinking in whole.
// Each file is a titled monospace block; the newest characters stay in view unless the reader has
// scrolled up to inspect something above.
function DemoLiveTrailRail({ demoRunId, working }) {
  const [files, setFiles] = React.useState([]); // [{ name, content }]
  const [trailId, setTrailId] = React.useState(null);
  const [fetched, setFetched] = React.useState(false);
  // Poll while the agent works (and once on mount); stop when it finishes. A finished run still
  // fetches once so a reopened, already-done generation shows its trail.
  React.useEffect(() => {
    if (!demoRunId) return undefined;
    let alive = true;
    const load = async () => {
      const r = await TB.demoTrailContent(demoRunId);
      if (!alive) return;
      if (r && Array.isArray(r.files)) setFiles(r.files);
      if (r && r.trailId) setTrailId(r.trailId);
      setFetched(true);
    };
    load();
    if (!working) return () => { alive = false; };
    const t = setInterval(load, 1500);
    return () => { alive = false; clearInterval(t); };
  }, [demoRunId, working]);

  // Typewriter: the full content lives in `files`; we reveal it a few characters per animation
  // frame. revealRef holds the revealed length per file; one rAF loop advances every file toward
  // its full length and stops when all have caught up. New/growing files re-arm the loop.
  const [, forceTick] = React.useReducer((x) => x + 1, 0);
  const fullRef = React.useRef([]);
  const revealRef = React.useRef({});
  const rafRef = React.useRef(0);
  fullRef.current = files;
  React.useEffect(() => {
    const step = () => {
      let animating = false;
      fullRef.current.forEach((f) => {
        const full = (f.content || '').length;
        const cur = revealRef.current[f.name] || 0;
        if (cur < full) { revealRef.current[f.name] = Math.min(full, cur + (3 + Math.floor(Math.random() * 4))); animating = true; }
      });
      forceTick();
      rafRef.current = animating ? requestAnimationFrame(step) : 0;
    };
    if (!rafRef.current) rafRef.current = requestAnimationFrame(step);
    return () => { if (rafRef.current) { cancelAnimationFrame(rafRef.current); rafRef.current = 0; } };
  }, [files]);

  // Keep the newest characters in view unless the reader scrolled up.
  const scrollRef = React.useRef(null);
  const stickRef = React.useRef(true);
  const stillTyping = files.some((f) => (revealRef.current[f.name] || 0) < (f.content || '').length);
  React.useEffect(() => {
    const el = scrollRef.current;
    if (el && stickRef.current) el.scrollTop = el.scrollHeight;
  });
  const onScroll = (e) => {
    const el = e.currentTarget;
    stickRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 60;
  };
  return (
    <div style={{ flex: '0 1 420px', minWidth: 300, minHeight: 0, overflow: 'hidden', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
      <div style={{ flex: '0 0 auto', display: 'flex', alignItems: 'center', gap: 8, padding: '12px 14px 10px', borderBottom: '1px solid var(--tb-hairline)' }}>
        <Ico n="file-code" s={14} c="var(--tb-ai)" />
        <span style={{ fontSize: 13, fontWeight: 700 }}>Trail</span>
        {trailId && <span className="tb-mono" data-selectable style={{ flex: 1, minWidth: 0, fontSize: 10.5, color: 'var(--text-subtle-variant)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{String(trailId).replace(/^\d+\//, '')}</span>}
        {stillTyping && <Ico n="pen-line" s={12} c="var(--tb-ai)" />}
      </div>
      <div ref={scrollRef} onScroll={onScroll} style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '12px 14px', display: 'flex', flexDirection: 'column', gap: 12 }}>
        {files.length === 0 ? (
          <EmptyState ico={working ? 'loader-2' : 'file-code'}
            title={working ? 'Writing the trail…' : (fetched ? 'No trail yet' : 'Loading…')}
            sub={working ? 'The agent is authoring the trail from your demonstration. It appears here as it writes.' : 'The trail the agent writes will appear here.'} />
        ) : files.map((f) => {
          const revealed = (f.content || '').slice(0, revealRef.current[f.name] || 0);
          return (
            <div key={f.name} style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', overflow: 'hidden' }}>
              <div className="tb-mono" style={{ fontSize: 11, fontWeight: 700, color: 'var(--text-subtle-variant)', padding: '7px 11px', borderBottom: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name}</div>
              <pre className="tb-mono" data-selectable style={{ margin: 0, padding: '10px 12px', fontSize: 11.5, lineHeight: 1.55, color: 'var(--text-standard)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{revealed}</pre>
            </div>
          );
        })}
      </div>
    </div>
  );
}

// The verified-and-saved result: what the whole demonstrate-first flow is for. Names the trail,
// shows which platforms it covers, and offers the two next moves - add another platform (record the
// same flow on a second device) or open the finished trail in the library.
function DemoVerifiedPanel({ trailId, platforms, mirror, go, onAddPlatform, addingPlatform, addPlatformErr }) {
  const [adding, setAdding] = React.useState(false);
  const devices = (mirror && mirror.deviceList) || [];
  const covered = new Set((platforms || []).map((p) => String(p).toLowerCase()));
  const candidates = devices.filter((d) => !covered.has(String(d.platform || '').toLowerCase()));
  const [deviceId, setDeviceId] = React.useState('');
  React.useEffect(() => {
    if ((!deviceId || !candidates.some((d) => d.id === deviceId)) && candidates.length) setDeviceId(candidates[0].id);
  }, [candidates.map((d) => d.id).join(',')]);
  const confirm = async () => {
    const r = await onAddPlatform(deviceId || null);
    if (r && r.ok) setAdding(false);
  };
  return (
    <div style={{ border: '1px solid rgba(0,224,19,.4)', borderLeft: '3px solid var(--tb-pass)', borderRadius: 10, background: 'var(--bg-subtle)', padding: '14px 16px' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 10 }}>
        <Ico n="check-circle-2" s={18} c="var(--tb-pass)" style={{ flex: '0 0 auto', marginTop: 1 }} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14.5, fontWeight: 700 }}>Trail verified and saved</div>
          {trailId && <div className="tb-mono" data-selectable style={{ fontSize: 11.5, color: 'var(--text-subtle-variant)', marginTop: 3, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{String(trailId).replace(/^\d+\//, '')}</div>}
          {platforms && platforms.length > 0 && (
            <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, marginTop: 8 }}>
              {platforms.map((p) => <Chip key={p} tone="green" ico="check">{p}</Chip>)}
            </div>
          )}
        </div>
        <div style={{ display: 'flex', gap: 8, flex: '0 0 auto' }}>
          {!adding && <Btn sm ico="plus" onClick={() => setAdding(true)} disabled={!candidates.length}
            title={candidates.length ? 'Record this flow on another platform' : 'Every connected platform is already covered'}>Add another platform</Btn>}
          {trailId && <Btn kind="primary" sm ico="check" onClick={() => go('trails', { sel: trailId })}>Done</Btn>}
        </div>
      </div>
      {adding && (
        <div style={{ marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--tb-hairline)' }}>
          <div className="tb-eyebrow" style={{ fontSize: 9.5, marginBottom: 6 }}>Record on another platform</div>
          {candidates.length ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flexWrap: 'wrap' }}>
              <Select compact value={deviceId || ''} label={deviceId || 'device'}
                options={candidates.map((d) => [d.id, `${d.name || d.id} (${d.platform || '?'})`])}
                onChange={(e) => setDeviceId(e.target.value)} />
              <span style={{ flex: 1 }} />
              <Btn sm onClick={() => setAdding(false)} disabled={addingPlatform}>Cancel</Btn>
              <Btn kind="primary" sm ico={addingPlatform ? 'loader-2' : 'circle-play'} spin={addingPlatform}
                disabled={addingPlatform || !deviceId} onClick={confirm}>{addingPlatform ? 'Starting…' : 'Start recording'}</Btn>
            </div>
          ) : (
            <div className="tb-sub" style={{ fontSize: 11.5 }}>Connect a device on another platform to record this flow there too.</div>
          )}
          {addPlatformErr && <div role="alert" style={{ marginTop: 8, fontSize: 11.5, color: 'var(--tb-danger-text)', fontWeight: 600 }}>{addPlatformErr}</div>}
        </div>
      )}
    </div>
  );
}

// The mark-start boundary is remembered in sessionStorage so a refresh mid-demonstration still
// separates setup taps from steps when the server hasn't tagged the events with a phase.
function readDemoMarkStart(runId) {
  try { const v = sessionStorage.getItem('tb-demo-markstart-' + runId); return v ? Number(v) : null; } catch (_) { return null; }
}
function writeDemoMarkStart(runId) {
  try { sessionStorage.setItem('tb-demo-markstart-' + runId, String(Date.now())); } catch (_) { /* sessionStorage unavailable */ }
}

Object.assign(window, {
  useTrailheadPicker, demoStepEvents, demoEventPhase,
  DemonstrateFlow, DemoStepper, DemoInstruction, DemoPositionPhase, DemoDemonstratePhase,
  DemoDescribeDialog, DemoGeneratePhase, DemoVerifiedPanel, DemoLiveTrailRail,
});
