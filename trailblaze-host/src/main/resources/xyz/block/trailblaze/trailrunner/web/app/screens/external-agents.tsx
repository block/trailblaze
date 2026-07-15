// @ts-nocheck -- migrated from .jsx; this file follows the existing Trail Runner browser-runtime
// pattern: Babel strips types at load time, and the typecheck gate covers regressions separately.

// Agents: three panes. Left is just the conversation list; the middle is the chat (the composer —
// including the agent/access/model choice for a NEW conversation — lives inside it, at the
// bottom); the right is a details panel with tabs (live device mirror, the raw YAML the agent is
// composing) that more views can join later.

// The full grounded-composition recipe (trail-directory convention, ground on the real device,
// do/verify steps, trail_output handoff). It never appears in the composer: it rides the start request as
// promptPreamble - under the hood, ahead of the human's own words - so the transcript and the
// text box carry only what the human actually typed. The user's message supplies the flow to
// build (the preamble ends with "The flow I want to build:" via composePresetPreamble).
const EXTERNAL_AGENT_COMPOSE_RECIPE = [
  'You are my partner for composing a Trailblaze trail: an automated UI test written as short,',
  'observable steps against a real device. Drive this as a guided session, one stage at a time, like a',
  'thoughtful test author pairing with me. Do your part of a stage, then wait for me. Do not run ahead.',
  '',
  'Stage 1 - Frame the trail, centered on the starting point. Interview me before touching anything;',
  'ask 2-3 questions, one message at a time, and wait for each answer.',
  '- FIRST, ask which TRAILHEAD to start from - the named entry state the trail begins in. Ask it with',
  '  the ask_user command and params.source "trailheads" so Trail Runner shows this workspace\'s',
  '  trailheads as clickable options for me to pick:',
  '  TRAILRUNNER_UI {"version":1,"action":"ask_user","message":"Which starting point should this trail begin from?","params":{"source":"trailheads"}}',
  '  If I pick one, that trailhead is the trail\'s entry (step 0). If none fit, ask me to describe the',
  '  starting screen instead.',
  '- THEN ask what the flow is from that starting point and what must be observably TRUE at the end to',
  '  call it a pass - a specific value, screen, or confirmation, not "it works".',
  '- ONLY if it matters, ask about a specific variation (a particular item, amount, or account). Use',
  '  ask_user with params.options for any either/or choice so I can click the answer.',
  '- Do NOT open by probing the environment (no status/version/list commands). Open with the trailhead',
  '  question, and wait for my answers.',
  '',
  'Stage 2 - Scaffold the trail, then connect and capture.',
  '- As soon as the trailhead is chosen, pick the trail\'s home in the library - <area>/<kebab-slug>/,',
  '  where <area> matches how neighboring trails are organized - and write its blaze.yaml right away',
  '  with just the config and that trailhead as the entry (step 0), then emit trail_output so I can',
  '  see the trail from the start. It grows from here - you append each step to this same file as we go.',
  '- When the UI context includes a deviceId, that is the device I picked for this session: connect',
  '  to it directly (do not list devices first). Only if there is none, ask me to connect one, then wait.',
  '- Start a capture session so Trailblaze records the screen, the view hierarchy, and the device event',
  '  and log streams while I demonstrate - this is the grounding you transcribe from and later verify',
  '  against. Tell me it is capturing.',
  '- Get us to the chosen trailhead\'s starting state (drive there, or ask me to), and confirm we are',
  '  both looking at it before we record.',
  '',
  'Stage 3 - I demonstrate, you transcribe.',
  '- Ask me to turn on Record and click through the flow on the device, narrating as I go.',
  '- As each of my taps lands, transcribe it into a candidate step: a short do/verify pair grounded in',
  '  the element I actually hit, using the selector that resolved (prefer the most durable one). Group',
  '  taps into named waypoints. Ask a sharp question ONLY when a tap is ambiguous (this exact item or',
  '  any of its kind? which value must hold?). Never invent a step I did not perform.',
  '- Keep going until I say the flow is done.',
  '',
  'Stage 4 - Assemble and refine the trail.',
  '- Write <area>/<kebab-slug>/blaze.yaml: config with the target, platform, and the chosen trailhead as',
  '  the entry, then my transcribed steps as do/verify pairs. Read an existing trail for the',
  '  schema first, and keep every file for this trail inside its directory.',
  '- Walk the steps back with me. For every verify, confirm it actually captures the Stage 1 intent;',
  '  flag any assertion that would pass on the wrong screen. Tighten selectors.',
  '- Hand back with trail_output (status "draft") after each meaningful change:',
  '  TRAILRUNNER_UI {"version":1,"action":"trail_output","trailId":"0/<area>/<slug>","message":"<what changed>","params":{"status":"draft","files":"blaze.yaml"}}',
  '',
  'Stage 5 - Verify and finalize.',
  '- Offer to replay the trail from the trailhead starting state. Read the captured logs and artifacts to',
  '  confirm the outcome actually happened, not just that the screen looked right.',
  '- Refine on failure. When it runs clean and I agree, emit trail_output with status "ready".',
  '',
  'Throughout: ground every step on what is actually on screen, narrate briefly (I am following on the',
  'device screen and the event log), and ask before you guess. Use the "trailblaze" skill (in this',
  'workspace\'s .claude/skills) for trail syntax, selector rules, and the authoring workflow.',
].join('\n');

// The compose preamble armed by the compose chip / a trailhead pick: the recipe, the pre-chosen
// trailhead if any (with its configured parameters as ready-to-use step YAML), ending with the
// lead-in the human's typed message completes.
function composePresetPreamble(preset) {
  let headBlock = '';
  if (preset && preset.trailhead && preset.stepYaml) {
    const step = preset.stepYaml.split('\n').map((l, i) => (i === 0 ? `    - ${l}` : `      ${l}`)).join('\n');
    headBlock = `\n\nStart from the "${preset.trailhead}" trailhead - already chosen AND configured in the session brief `
      + `(skip the trailhead question). Use exactly this as the trail's first step:\n\n- tools:\n${step}`;
  } else if (preset && preset.trailhead) {
    headBlock = `\n\nStart from the "${preset.trailhead}" trailhead (already chosen - skip the trailhead question).`;
  }
  return EXTERNAL_AGENT_COMPOSE_RECIPE + headBlock + '\n\nThe flow I want to build:';
}

// `paramVisible`, `paramPlaceholder` and `cleanParamDesc` live in record.tsx (loaded before this
// file) alongside the other trailhead param helpers - both screens render the same param forms.

// The configured trailhead as a tool-call YAML mapping. Dotted names (account.type) group under
// their parent key as nested YAML; hidden fields and blank values stay out (blank optional means
// "use the default", and a blank required field is the agent's to ask about - never emit "").
function trailheadStepYaml(name, params, args) {
  const visible = (params || []).filter((p) => paramVisible(p, args));
  const flat = [];
  const nested = new Map();
  visible.forEach((p) => {
    const v = String(args[p.name] == null ? '' : args[p.name]).trim();
    if (!v) return;
    const dot = p.name.indexOf('.');
    if (dot < 0) { flat.push([p.name, v]); return; }
    const parent = p.name.slice(0, dot);
    if (!nested.has(parent)) nested.set(parent, []);
    nested.get(parent).push([p.name.slice(dot + 1), v]);
  });
  if (!flat.length && !nested.size) return `${name}: {}`;
  const lines = [`${name}:`];
  nested.forEach((kvs, parent) => {
    lines.push(`  ${parent}:`);
    kvs.forEach(([k, v]) => lines.push(`    ${k}: ${v}`));
  });
  flat.forEach(([k, v]) => lines.push(`  ${k}: ${v}`));
  return lines.join('\n');
}

const EXTERNAL_AGENT_ACCESS_OPTIONS = [
  ['read-only', 'Read only'],
  ['workspace-write', 'Workspace'],
  ['danger-full-access', 'Full access'],
];

// Selection (`sel`/`onSel`) and the agents hook are owned by the shell: the sessions list
// lives in the nav rail (the Create group), so the rail rows and this screen must share one
// selection and one polled run list. 'new' selects the blank composer.
function ExternalAgentsScreen({ go, agents, sel, onSel, initSel, active = true }) {
  useLucide();
  const agentsHook = agents;
  const setSel = onSel;
  const [notice, setNotice] = React.useState(null);

  const runs = (agentsHook.data && agentsHook.data.runs) || [];
  const supported = (agentsHook.data && agentsHook.data.supportedAgents) || [];
  const cur = sel !== 'new' ? runs.find((r) => r.id === sel) || null : null;

  React.useEffect(() => { if (initSel) setSel(initSel); }, [initSel]);
  React.useEffect(() => {
    if (!sel && runs.length) setSel(runs[0].id);
    if (!sel && !runs.length && !agentsHook.loading) setSel('new');
  }, [runs.length, sel, agentsHook.loading]);
  React.useEffect(() => {
    if (!active) return;
    agentsHook.reload();
  }, [active]);
  React.useEffect(() => {
    const onMessage = (e) => setNotice(e.detail || null);
    window.addEventListener('tb:external-agent-message', onMessage);
    return () => window.removeEventListener('tb:external-agent-message', onMessage);
  }, []);

  // stoppingId drives the header button's "Stopping…" state; it stays set until the polled run
  // status actually leaves 'running' (cleared in the effect below), so the optimistic label can't
  // outlive a cancel that didn't take.
  const [stoppingId, setStoppingId] = React.useState(null);
  React.useEffect(() => {
    if (!stoppingId) return;
    const r = runs.find((x) => x.id === stoppingId);
    if (!r || r.status !== 'running') setStoppingId(null);
  }, [runs, stoppingId]);
  const stop = async (run) => {
    if (!run || run.status !== 'running' || stoppingId) return;
    setStoppingId(run.id);
    const res = await TB.cancelExternalAgent(run.id);
    if (!res || !res.ok) {
      setStoppingId(null);
      setNotice({ message: 'Could not stop the conversation: ' + ((res && res.error) || 'the daemon did not acknowledge the cancel. It may have restarted - reload and check the conversation status.') });
    }
    agentsHook.reload();
  };

  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%', minHeight: 0 }}>
      <div style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        {notice && (
          <div style={{ margin: '14px 24px 0', border: '1px solid var(--tb-hairline)', borderLeft: '3px solid var(--tb-ai)', borderRadius: 9, padding: '9px 12px', display: 'flex', gap: 9, alignItems: 'flex-start', background: 'var(--bg-subtle)' }}>
            <Ico n="message-square" s={14} c="var(--tb-ai)" style={{ marginTop: 2, flex: '0 0 auto' }} />
            <span style={{ flex: 1, minWidth: 0, fontSize: 12.5, lineHeight: 1.5 }}>{notice.message || JSON.stringify(notice)}</span>
            <button type="button" onClick={() => setNotice(null)} style={{ border: 'none', background: 'transparent', color: 'var(--text-subtle)', cursor: 'pointer' }}><Ico n="x" s={14} /></button>
          </div>
        )}
        {cur && cur.demo ? (
          // A demo run drives the demonstrate-first flow (position -> demonstrate -> describe ->
          // generate) instead of the agent chat. The phase lives on cur.demo, so a refresh restores
          // the right step; this component only drives the device and collects the objective.
          <DemonstrateFlow
            run={cur}
            runs={runs}
            supported={supported}
            go={go}
            active={active}
            onReload={() => agentsHook.reload()}
          />
        ) : (
          <ExternalAgentChat
            run={cur}
            supported={supported}
            go={go}
            active={active}
            onStop={() => stop(cur)}
            stopping={!!(cur && stoppingId === cur.id)}
            onStarted={(run) => { setSel(run.id); agentsHook.reload(); }}
            onTurn={() => agentsHook.reload()}
          />
        )}
      </div>
    </div>
  );
}

// ─── Conversation view ───────────────────────────────────────────────────────

function ExternalAgentChat({ run, supported, go, active, onStop, stopping, onStarted, onTurn }) {
  const running = !!run && run.status === 'running';
  // A solo session is the same composition minus the agent: the narration column becomes a guide
  // strip, the composer and the reasoning strip never render. Subtraction, not a second design.
  const solo = !!run && run.agentType === 'solo';
  const [railTab, setRailTab] = useStickyState('tb-external-agent-panel-tab', 'trail');
  const [railW, startRailDrag] = useResizableWidth('tb-external-agent-panel-w', 360, 280, 960, true);
  const [narrationW, startNarrationDrag] = useResizableWidth('tb-external-agent-narration-w', 340, 260, 520);

  // One live device connection, owned at the conversation level: the stage is a permanent surface
  // of every session, so the mirror holds for the session's whole life.
  const mirror = useAgentDeviceMirror(active && !!run);
  const frameRef = React.useRef(null);
  frameRef.current = mirror.frame;
  // Screenshot pins: seq -> frame data URL captured the moment a trailblaze tool event streamed
  // in. Transient by design (frames aren't persisted server-side) — they exist to let you follow
  // along live and scroll back within this page load.
  const [pins, setPins] = React.useState({});
  const trailblazeCallIdsRef = React.useRef(new Set());

  const onLiveEvent = React.useCallback((event) => {
    if (event.kind === 'ui_command' && event.uiCommand) {
      // Auto-apply only live UI commands from the selected, still-running run. Historical
      // commands must never replay on load; they stay on the manual Apply button.
      TB.applyTrailRunnerUiCommand(event.uiCommand, go);
      return;
    }
    const isTbCall = event.kind === 'tool_call' && String(event.toolName || '').startsWith('mcp__trailblaze__');
    const isTbResult = event.kind === 'tool_result' && event.toolCallId && trailblazeCallIdsRef.current.has(event.toolCallId);
    if (isTbCall && event.toolCallId) trailblazeCallIdsRef.current.add(event.toolCallId);
    if ((isTbCall || isTbResult) && frameRef.current) {
      const url = frameRef.current;
      setPins((p) => ({ ...p, [event.seq]: url }));
    }
  }, [go]);

  // `follow: true`: a finished run keeps pulling new events so the taps you demonstrate after the
  // agent's turn still land on the trail rail (there's no live SSE once a run has finished). The
  // stage is always up, so the session always follows.
  const eventsHook = TB.useExternalAgentEvents(run ? run.id : null, running, onLiveEvent, true);
  const events = run ? (eventsHook.data || []) : [];
  const tape = React.useMemo(() => events.filter((e) => e.kind === 'human_action'), [events]);

  React.useEffect(() => { trailblazeCallIdsRef.current = new Set(); setPins({}); }, [run && run.id]);

  // When the agent declares output (trail_output), bring the trail rail to its YAML tab so the
  // result is visible without navigating away. Live convenience; the YAML panel also detects the
  // declaration from history via detectComposedTrail.
  React.useEffect(() => {
    const onOutput = () => setRailTab('yaml');
    window.addEventListener('tb:external-agent-trail-output', onOutput);
    return () => window.removeEventListener('tb:external-agent-trail-output', onOutput);
  }, []);

  // The agent's unanswered questions surface in the composer (not the stream), so several can be
  // answered and sent together.
  const pendingQuestions = React.useMemo(() => pendingAskQuestions(events), [events]);

  // ─── The confirm gate ───
  // A tap on the stage PROPOSES the next step: the element is resolved (nothing dispatched) and
  // held as a pending card in the trail rail, where the author describes it, can change the
  // selector tier or tools, and confirms. Confirm-and-run executes the confirmed tools
  // (selector-driven, proving the step replays as recorded) and records the step - with evidence -
  // into the conversation. Same gate as the Interact recorder; reuses its card and helpers.
  const [pending, setPending] = React.useState(null);
  const [pendingPrompt, setPendingPrompt] = React.useState('');
  const [appendArm, setAppendArm] = React.useState(null);
  const [stepBusy, setStepBusy] = React.useState(false);
  const [stepErr, setStepErr] = React.useState(null);
  React.useEffect(() => { setPending(null); setPendingPrompt(''); setAppendArm(null); setStepErr(null); }, [run && run.id]);
  const toolsHook = TB.useTools();
  const toolMap = React.useMemo(() => new Set((toolsHook.data || []).map((t) => t.id)), [toolsHook.data]);
  const [gt] = TB.useGlobalTarget();
  const target = gt && gt.target;
  const stageDevice = mirror.deviceList.find((d) => d.id === mirror.selectedId) || null;
  const stagePlatform = stageDevice ? stageDevice.platform : null;
  const toolScope = React.useMemo(() => (target ? TB.scopeTrailmaps(target, stagePlatform) : null), [target, stagePlatform]);

  async function proposeAt(e) {
    return proposeAtPoint(mirror.toDeviceCoords(e));
  }

  // Propose a step at device coordinates - the shared path behind a stage click and an Elements
  // inspector pick (which arrives with the element's center, not a mouse event).
  async function proposeAtPoint(p) {
    if (stepBusy) return;
    if (!p) return;
    // When armed, the next tap APPENDS that tool to the open step (not re-target).
    if (pending && appendArm) {
      const armAction = appendArm;
      setAppendArm(null);
      setStepBusy(true);
      const r = await mirror.sendGesture({ type: 'tap', x: p.x, y: p.y, action: armAction, resolveOnly: true });
      setStepBusy(false);
      if (!r.ok) { setStepErr(r.error || 'Could not resolve that element.'); return; }
      setStepErr(null);
      const best = (r.options || []).find((o) => o.isSelector);
      const nt = parseRecordStepTools(best ? best.yaml : (r.yaml || ''))[0];
      if (nt && window.jsyaml) {
        const arr = [...parseRecordStepTools(pending.yaml), nt].map((t) => ({ [t.name]: t.args == null ? {} : t.args }));
        const yaml = window.jsyaml.dump([{ tools: arr }], { lineWidth: -1 }).replace(/\n+$/, '');
        setPending((prev) => (prev ? { ...prev, yaml } : prev));
      }
      return;
    }
    // If a proposal is already open, this tap RE-TARGETS it: re-resolve at the new spot.
    const prior = pending;
    const priorSeed = prior ? seedPrompt(prior.action, prior.element, prior.gesture) : null;
    const gesture = { type: 'tap', x: p.x, y: p.y, action: 'tap' };
    setStepBusy(true);
    const r = await mirror.sendGesture({ ...gesture, resolveOnly: true });
    setStepBusy(false);
    if (!r.ok) { setStepErr(r.error || 'Could not resolve that interaction.'); return; }
    setStepErr(null);
    // (Re)seed the description only when fresh or still showing the previous element's seed -
    // never clobber a prompt the author has hand-written.
    const newSeed = seedPrompt('tap', r.element, gesture);
    setPendingPrompt((cur) => (!prior || !cur.trim() || cur === priorSeed ? newSeed : cur));
    setRailTab('trail');
    // Prefer a stable selector by default, falling back to whatever the resolver returned.
    const opts = r.options || [];
    const best = opts.find((o) => o.isSelector);
    const tool0Yaml = best ? best.yaml : (r.yaml || '');
    // Re-targeting a proposal that already chained extra tools: swap ONLY tool 0 (the gesture
    // tool) and keep the rest of the series.
    let yaml = tool0Yaml;
    if (prior && window.jsyaml) {
      const priorTools = parseRecordStepTools(prior.yaml);
      const nt = parseRecordStepTools(tool0Yaml)[0];
      if (nt && priorTools.length > 1) {
        const arr = [nt, ...priorTools.slice(1)].map((t) => ({ [t.name]: t.args == null ? {} : t.args }));
        yaml = window.jsyaml.dump([{ tools: arr }], { lineWidth: -1 }).replace(/\n+$/, '');
      }
    }
    setPending({ gesture, action: 'tap', toolName: best ? best.toolName : r.toolName, yaml, label: r.label || r.toolName || 'Step', options: opts, element: r.element || null });
  }

  // ─── The reasoning strip ───
  // When a proposal opens (or re-targets to a new element), a fast model gets ~3 seconds to weigh
  // the selector candidates. The verdict annotates the pending card if it makes the window and is
  // dropped otherwise - Confirm NEVER waits on it. Keyed to the gesture (not the yaml) so picking
  // a different tier or editing args doesn't re-fire the request.
  const [advice, setAdvice] = React.useState(null);
  const adviceNonce = React.useRef(0);
  const adviceKey = pending ? pending.gesture.x + ',' + pending.gesture.y : null;
  React.useEffect(() => {
    // Solo sessions have no agent voice anywhere - the strip never renders on pending cards.
    if (!pending || solo) { setAdvice(null); return; }
    const nonce = ++adviceNonce.current;
    setAdvice({ state: 'thinking' });
    const p = pending;
    const budget = new Promise((res) => setTimeout(() => res(null), 3200));
    const req = TB.recordSelectorAdvice({
      stepYaml: p.yaml || '',
      prompt: (pendingPrompt || '').trim() || null,
      options: (p.options || []).map((o) => ({ label: o.label, toolName: o.toolName, yaml: o.yaml })),
      elementLabel: p.element ? p.element.label : null,
      elementType: p.element ? p.element.type : null,
      platform: stagePlatform,
    });
    Promise.race([req, budget]).then((r) => {
      if (adviceNonce.current !== nonce) return; // superseded by a newer proposal
      if (r && r.ok && r.advice && r.advice.reason) setAdvice({ state: 'done', reason: r.advice.reason, preferOption: r.advice.preferOption || null });
      else setAdvice(null); // budget missed / no provider / error: the strip simply goes away
    });
  }, [adviceKey]);

  async function confirmPending() {
    if (!pending || stepBusy || !run) return;
    setAppendArm(null);
    const runnable = buildRunnableToolYaml(pending.label || 'step', pending.yaml);
    if (!runnable) { setStepErr('This step has no runnable tools.'); return; }
    setStepBusy(true);
    const r = await mirror.sendGesture({
      ...pending.gesture, resolveOnly: false, runId: run.id,
      runYaml: runnable, stepYaml: pending.yaml, prompt: pendingPrompt.trim(),
      chosenOption: chosenRecordOption(pending), element: pending.element,
    });
    setStepBusy(false);
    if (!r.ok) { setStepErr(r.error || 'Could not run this step.'); return; }
    setStepErr(null); setPending(null); setPendingPrompt('');
  }
  const pickOption = (opt) => setPending((p) => (p ? { ...p, yaml: opt.yaml, toolName: opt.toolName } : p));
  const pickYaml = (yaml) => setPending((p) => (p ? { ...p, yaml } : p));
  const cancelPending = () => { setPending(null); setPendingPrompt(''); setAppendArm(null); setStepErr(null); };
  const pendingStep = pending ? {
    pending, prompt: pendingPrompt, setPrompt: setPendingPrompt, busy: stepBusy, err: stepErr,
    toolMap, catalog: toolsHook.data || [], scope: toolScope, platform: stagePlatform,
    onConfirm: confirmPending, onCancel: cancelPending, onPickOption: pickOption, onPickYaml: pickYaml,
    armed: appendArm, onArmAppend: setAppendArm, advice,
  } : null;

  // ─── Trailhead (step 0: the known state the trail starts from) ───
  // The Interact recorder's trailhead machinery, hosted here so the trail rail can carry the same
  // required step 0. Shared with the demonstrate-first Position phase via useTrailheadPicker (same
  // sticky key as Interact - a pick made on either screen carries over). onArrived refreshes the
  // element tree after a "Go to trailhead" drive moves the device.
  const { trailhead, availTrailheads, selectedTh, thDetail, thMissingRequired, thFilledArgs } =
    useTrailheadPicker(target, stagePlatform, mirror, () => refreshTreeIfShown());

  // ─── Elements inspector: the accessibility tree, as a rail tab + labeled boxes on the stage ───
  const [els, setEls] = React.useState([]);
  const [elsLoading, setElsLoading] = React.useState(false);
  const [elsErr, setElsErr] = React.useState(null);
  const [elQuery, setElQuery] = React.useState('');
  const [hoverEl, setHoverEl] = React.useState(null);
  const [showTree, setShowTree] = React.useState(false);
  async function loadTree() {
    const id = mirror.tbId();
    if (!id) return;
    setElsLoading(true); setElsErr(null);
    const r = await TB.recordTree(id);
    setElsLoading(false);
    if (!r.ok) { setElsErr(r.error || 'Could not read the screen.'); setEls([]); return; }
    setEls(r.elements || []);
  }
  // Load when the author opens the Elements tab OR turns on the stage overlay.
  React.useEffect(() => {
    if ((railTab === 'elements' || showTree) && mirror.conn && !elsLoading && els.length === 0 && !elsErr) loadTree();
  }, [railTab, showTree, mirror.conn]);
  const refreshTreeIfShown = () => { if (mirror.tbId() && (showTree || railTab === 'elements')) loadTree(); };
  // Anything that moved the device screen makes the cached tree stale: a confirmed step landing on
  // the tape, a drive tap, a trailhead replay. Confirmed steps arrive as tape growth; the others
  // call refreshTreeIfShown directly.
  React.useEffect(() => { refreshTreeIfShown(); }, [tape.length]);
  const elList = React.useMemo(() => {
    const q = elQuery.trim().toLowerCase();
    if (!q) return els;
    return els.filter((e) => [e.label, e.type].some((v) => v && String(v).toLowerCase().includes(q)));
  }, [els, elQuery]);
  // An inspector pick proposes the same step a stage tap at the element's center would.
  const proposeElement = (el) => { if (mirror.conn && !stepBusy) proposeAtPoint({ x: el.centerX, y: el.centerY }); };
  const inspector = {
    els: elList, total: els.length, loading: elsLoading, err: elsErr, query: elQuery, setQuery: setElQuery,
    onHover: setHoverEl, onPick: proposeElement, refresh: loadTree, busy: stepBusy,
  };
  const stageInspect = {
    on: showTree || railTab === 'elements', toggled: showTree, toggle: () => setShowTree((v) => !v),
    els: elList, hoverEl, pendingEl: pending ? pending.element : null,
    onPickEl: proposeElement, busy: stepBusy, onDeviceMaybeChanged: refreshTreeIfShown,
  };

  // ─── Save the trail ───
  // The agentless exit: assemble the chosen trailhead (baked in as step 0) + the demonstrated tape
  // into a trail folder written straight into the library, exactly as the Interact recorder saves.
  // When the agent writes the trail itself (trail_output), the YAML tab shows that file instead and
  // this path isn't offered. The Save button validates, then asks where the trail should live via
  // SaveTrailDialog (shared from record.tsx).
  const [savingTrail, setSavingTrail] = React.useState(false);
  const [saveTrailErr, setSaveTrailErr] = React.useState(null);
  const [saveTrailOpen, setSaveTrailOpen] = React.useState(false);
  // The demonstrated tape as recordable steps ({ text, yaml, verify }) for the shared save flow.
  // A recorded Assert-visible action is a verify step, same as the Interact recorder's mapping -
  // the original gesture action rides the HUMAN_ACTION event's input.
  const recordedTapeSteps = () => tape.map((e) => {
    const out = parseMaybeJson(e.output) || {};
    const inp = parseMaybeJson(e.input) || {};
    return { text: decodeEntities(e.title) || '', yaml: out.yaml || '', verify: inp.action === 'assertVisible' };
  }).filter((s) => s.text.trim() || s.yaml.trim());
  function openSaveTrail() {
    const blocker = trailSaveBlocker({ hasSteps: recordedTapeSteps().length > 0, availTrailheads, selectedTh, thMissingRequired });
    if (blocker) { setSaveTrailErr(blocker); return; }
    setSaveTrailErr(null);
    setSaveTrailOpen(true);
  }
  async function doSaveTrail(dest) {
    if (savingTrail) return;
    setSavingTrail(true); setSaveTrailErr(null);
    const recorded = recordedTapeSteps();
    const leadStep = trailheadLeadStep(selectedTh, thDetail, thFilledArgs());
    const saved = await saveTrailToLibrary({
      dest, target, platform: stagePlatform, objective: ((run && run.title) || '').trim(),
      steps: leadStep ? [leadStep, ...recorded] : recorded,
    });
    setSavingTrail(false);
    if (!saved.ok) { setSaveTrailErr(saved.error); return; }
    setSaveTrailOpen(false);
    go('trails', { sel: saved.id });
  }
  const trailSave = {
    saving: savingTrail, err: saveTrailErr, onSave: openSaveTrail, stepCount: tape.length,
    trailheadName: selectedTh ? selectedTh.name : null,
  };

  const conversation = (
    <AgentConversationColumn
      run={run} supported={supported} go={go} events={events} eventsLoading={eventsHook.loading}
      running={running} pins={pins} onStarted={onStarted} onTurn={onTurn}
      pendingQuestions={pendingQuestions}
      recording={!!run} narrow={!!run} solo={solo} />
  );

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div style={{ padding: '14px 24px 10px', flex: '0 0 auto', borderBottom: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 10 }}>
        {run ? (
          <>
            <Ico n={solo ? 'pointer' : externalAgentStatusIcon(run.status)[0]} s={15} c={solo ? 'var(--tb-running)' : externalAgentStatusIcon(run.status)[1]} spin={running} />
            <span style={{ flex: 1, minWidth: 0, fontSize: 14, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{run.title || run.id}</span>
            {/* A solo run's lifecycle status is an implementation detail (born finished, no
                process) - the honest label is what KIND of session this is. */}
            {solo
              ? <Chip tone="blue">solo</Chip>
              : <Chip tone={run.status === 'running' ? 'blue' : run.status === 'completed' ? 'green' : run.status === 'failed' ? 'red' : ''}>{run.status}</Chip>}
            {!solo && run.model && <Chip>{run.model}</Chip>}
            {!solo && <Chip>{run.agentType}</Chip>}
            {/* The Stop slot stays reserved (hidden, not unmounted) when the turn ends: unmounting
                it shifts whatever sits to its left into Stop's exact position, so a click aimed at
                Stop during the running->done transition would land on the wrong control (eval
                round 8). A hidden element keeps the layout and swallows nothing - the stale click
                lands on inert header space. */}
            <span style={{ visibility: running ? 'visible' : 'hidden' }}>
              <Btn sm ico={stopping ? 'loader-circle' : 'octagon-x'} spin={stopping} disabled={stopping || !running} onClick={onStop}>{stopping ? 'Stopping…' : 'Stop'}</Btn>
            </span>
          </>
        ) : (
          <>
            <Ico n="message-square-plus" s={15} c="var(--tb-ai)" />
            <span style={{ flex: 1, fontSize: 14, fontWeight: 700 }}>New session</span>
          </>
        )}
      </div>
      {run ? (
        // The one Create composition: the stage takes the middle (dominant - the device is the
        // subject, ch06), the conversation is the narration rail on the left, and the trail being
        // built stands on the right. flex '0 1' on both side rails (with floors): on a narrow
        // window (~1280px total) the preferred widths don't fit, and rails that refuse to shrink
        // push the trail rail clean off-screen. Letting them yield keeps all three surfaces
        // visible.
        <div style={{ flex: 1, minHeight: 0, minWidth: 0, display: 'flex' }}>
          <div style={{ flex: '0 1 ' + narrationW + 'px', minWidth: 220, minHeight: 0, display: 'flex', flexDirection: 'column', borderRight: '1px solid var(--tb-hairline)' }}>
            {conversation}
          </div>
          <Splitter onDown={startNarrationDrag} />
          <AgentRecordStage mirror={mirror} runId={run.id} hasSteps={tape.length > 0} onStageClick={proposeAt} stepBusy={stepBusy} inspect={stageInspect} />
          <Splitter onDown={startRailDrag} />
          <AgentTrailRail width={railW} tab={railTab} setTab={setRailTab} tape={tape} events={events} run={run} go={go} pendingStep={pendingStep} stepErr={stepErr}
            trailhead={trailhead} inspector={inspector} save={trailSave} />
          {saveTrailOpen && (
            <SaveTrailDialog
              target={target} busy={savingTrail} error={saveTrailErr}
              onCancel={() => { setSaveTrailOpen(false); setSaveTrailErr(null); }}
              onSave={doSaveTrail} />
          )}
        </div>
      ) : (
        // A session that doesn't exist yet is just the brief + composer, full width; the stage and
        // trail rail appear the moment the session does.
        <div style={{ flex: 1, minHeight: 0, display: 'flex' }}>
          <div style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
            {conversation}
          </div>
        </div>
      )}
    </div>
  );
}

// The conversation stream + composer: full-width on the new-session canvas, the narration rail
// (narrow, no reading-measure cap) once a session exists. Owns its own stick-to-bottom scroll so
// either host can drop it in.
function AgentConversationColumn({ run, supported, go, events, eventsLoading, running, pins, onStarted, onTurn, pendingQuestions, recording, narrow, solo }) {
  // groupPlumbingRuns + nestToolsUnderAssistant are display-only post-passes (NOT baked into
  // buildAgentTranscript, which the device event log and share export also consume): the first
  // folds long runs of quiet internal tool calls into one collapsed group so a grep-heavy agent
  // turn can't drown the narration; the second tucks the surviving tool cards under the assistant
  // bubble that triggered them.
  const transcript = React.useMemo(() => nestToolsUnderAssistant(groupPlumbingRuns(buildAgentTranscript(events), pins)), [events, pins]);
  const scrollRef = React.useRef(null);
  const stickToBottomRef = React.useRef(true);
  React.useEffect(() => {
    const el = scrollRef.current;
    if (el && stickToBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [events.length, run && run.status]);
  // KEEP the pin as content settles: a fresh mount renders the whole backlog, then cards,
  // screenshots, and code blocks finish laying out and grow the content long after the one-shot
  // scroll ran - measured 683px of 25366px in a real session, the agent's newest ask far below
  // the fold. Observing both the container and its content re-pins on every size change while
  // the user hasn't scrolled away.
  React.useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    // Re-stick on every conversation switch: the column persists across selections (the screen is
    // keep-mounted), so one upward scroll in an old conversation must not disable following forever.
    stickToBottomRef.current = true;
    const pin = () => { if (stickToBottomRef.current) el.scrollTop = el.scrollHeight; };
    pin();
    const ro = new ResizeObserver(pin);
    ro.observe(el);
    if (el.firstElementChild) ro.observe(el.firstElementChild);
    return () => ro.disconnect();
  }, [run && run.id]);
  // Only a genuine user gesture may un-stick: bare onScroll also fires for browser scroll-anchoring
  // and layout shifts (images/collapsibles re-measuring), which silently latched following off.
  // onScroll keeps the re-stick half (scrolling back near the bottom resumes following); scrollbar
  // drags count as gestures via the pointer-down flag.
  const dragRef = React.useRef(false);
  const unstickOnGesture = () => {
    const el = scrollRef.current;
    if (el && el.scrollHeight - el.scrollTop - el.clientHeight >= 90) stickToBottomRef.current = false;
  };

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
      <div ref={scrollRef}
        onScroll={(e) => {
          const el = e.currentTarget;
          const gap = el.scrollHeight - el.scrollTop - el.clientHeight;
          if (gap < 90) stickToBottomRef.current = true;
          else if (dragRef.current) stickToBottomRef.current = false;
        }}
        onWheel={unstickOnGesture} onTouchMove={unstickOnGesture}
        onPointerDown={() => { dragRef.current = true; }}
        onPointerUp={() => { dragRef.current = false; }}
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: narrow ? '14px 16px' : '16px 24px' }}>
        {/* Normal mode keeps one reading measure so the eye tracks a single axis; the narration rail
            is already narrow, so it fills its width. */}
        <div style={{ width: '100%', maxWidth: narrow ? 'none' : 760, margin: '0 auto', minHeight: '100%', display: 'flex', flexDirection: 'column', gap: 10 }}>
          {run ? (
            solo ? (
              // A solo session has no narration to stream: the column holds the working guide
              // instead - same surface, one fewer voice.
              <SoloGuideStrip />
            ) : (
              <>
                {eventsLoading && !events.length && <Skeleton rows={5} />}
                {!eventsLoading && events.length === 0 && <EmptyState ico="activity" title="No activity yet" sub="The agent process has started; its messages and tool use will appear here." />}
                {transcript.map((item, i) => <AgentTranscriptItem key={i} item={item} running={running} pins={pins} go={go} />)}
                {running && <AgentWorkingRow events={events} />}
              </>
            )
          ) : (
            <AgentNewConversationBody go={go} onStarted={onStarted} />
          )}
        </div>
      </div>
      {/* Pending tool-permission cards + the mid-run auto-approve toggle sit directly above the
          composer, in both the Active chat and the embedded generation transcript (both render this
          column). Driven off run.pendingPermissions / run.autoApprove; renders even while the run is
          working/streaming, and self-hides when there's nothing to decide. */}
      {!solo && run && <AgentPermissionBar run={run} running={running} onDecided={onTurn} />}
      {/* The composer is the reply box for an existing run only - a new session starts from the
          demonstrate-first canvas above, and a solo session has nobody on the other end. */}
      {!solo && run && <AgentComposer run={run} supported={supported} go={go} onStarted={onStarted} onTurn={onTurn} recording={recording} pendingQuestions={pendingQuestions} />}
    </div>
  );
}

// The solo session's left column: the Context -> Trailhead -> Steps arc, written down where the
// agent's narration would otherwise stream. Static by design - the live state (captured steps,
// the pending card, the growing YAML) already shows on the trail rail; this strip is the "how".
function SoloGuideStrip() {
  const items = [
    ['flag', 'Pick a trailhead', 'The starting state lives at the top of the Trail tab. It is baked in as the trail’s first step when you save.'],
    ['mouse-pointer-click', 'Click the device to propose a step', 'Nothing runs on a plain click: describe the step, adjust the selector if needed, then Confirm and run.'],
    ['command', 'Cmd-click to drive without recording', 'Navigate freely to reach the right screen - driven taps never become steps.'],
    ['save', 'Save your trail', 'The YAML tab previews the trail as it grows and saves it into your trail library, ready to replay and refine.'],
  ];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 12 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Ico n="pointer" s={14} c="var(--tb-running)" />
        <span className="tb-eyebrow" style={{ fontSize: 10 }}>Solo session</span>
      </div>
      <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.55 }}>
        No agent attached - you are recording by hand. Every action you confirm on the device
        becomes a step on the trail rail.
      </div>
      {items.map(([ico, title, sub]) => (
        <div key={title} style={{ display: 'flex', gap: 9, alignItems: 'flex-start', border: '1px solid var(--tb-hairline)', borderRadius: 10, padding: '9px 11px', background: 'var(--bg-subtle)' }}>
          <Ico n={ico} s={14} c="var(--tb-running)" style={{ flex: '0 0 auto', marginTop: 2 }} />
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 12.5, fontWeight: 700, lineHeight: 1.35 }}>{title}</div>
            <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, marginTop: 2 }}>{sub}</div>
          </div>
        </div>
      ))}
    </div>
  );
}

// The trail rail's Trail tab: the steps captured this session, in order, as uniform cards at a
// regular beat (rhythm, ch06) so the growing recording stays scannable. Newest at the bottom,
// where the eye rests after a tap. Below the steps, the event log keeps the full device activity
// (agent tool calls + your taps with evidence) and the turn boundary.
function AgentTrailTapePanel({ tape, events, pendingStep, stepErr, go, trailhead }) {
  const scrollRef = React.useRef(null);
  // Stick to the bottom on a new step AND when the pending card opens - the card is the thing to
  // act on, so it must arrive in view.
  React.useEffect(() => { const el = scrollRef.current; if (el) el.scrollTop = el.scrollHeight; }, [tape.length, !!pendingStep]);
  // Step 0 stays expanded until it's settled (picked + required params filled), then collapses to
  // its summary line; tapping it re-opens. A new tape step re-collapses an open editor - the
  // author has moved on to demonstrating.
  const th = trailhead || null;
  const thDone = !!th && (!th.trailheads.length || (!!th.selected && th.missingRequired.length === 0));
  const [thEditing, setThEditing] = React.useState(false);
  React.useEffect(() => { setThEditing(false); }, [tape.length]);
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '12px 14px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingBottom: 8 }}>
        <Ico n="list-video" s={14} c="var(--tb-pass)" />
        <span style={{ fontSize: 13, fontWeight: 700 }}>Steps</span>
        <span style={{ flex: 1 }} />
        <Chip tone={tape.length ? 'green' : ''}>{tape.length} step{tape.length === 1 ? '' : 's'}</Chip>
      </div>
      <div ref={scrollRef} style={{ flex: 1, minHeight: 100, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {/* Step 0 - the required trailhead the trail starts from (the Interact recorder's card). */}
        {th && (
          <TrailheadStep
            target={th.target} platform={th.platform} trailheads={th.trailheads} metaByName={th.metaByName}
            selected={th.selected} onSelect={th.onSelect} detail={th.detail} loading={th.loading}
            params={th.params} args={th.args} onArg={th.onArg} missingRequired={th.missingRequired}
            accountParam={th.accountParam} accountValue={th.accountValue} onAccount={th.onAccount}
            canRun={th.canRun} onGoToTrailhead={th.onGoToTrailhead} run={th.run} go={go}
            expanded={!thDone || thEditing} onExpand={() => setThEditing(true)} />
        )}
        {tape.length === 0 && !pendingStep
          ? <EmptyState ico="hand-pointer" title="No steps yet" sub="Tap the device on the stage to propose your first step. You confirm each step before it runs and records." />
          : tape.map((e, i) => <AgentTapeCard key={e.id || i} e={e} index={i} compact />)}
        {stepErr && (
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, border: '1px solid rgba(248,71,82,.4)', borderRadius: 9, padding: '8px 10px', background: 'rgba(248,71,82,.07)' }}>
            <Ico n="circle-x" s={13} c="var(--tb-fail)" style={{ marginTop: 1, flex: '0 0 auto' }} />
            <span style={{ fontSize: 11.5, lineHeight: 1.5 }}>{stepErr}</span>
          </div>
        )}
        {/* The confirm gate's pending card: the resolved-but-not-run step, exactly the Interact
            recorder's card (record.tsx globals load first), hosted in the trail rail. */}
        {pendingStep && (
          <ProposalCard pending={pendingStep.pending} num={tape.length + 1}
            prompt={pendingStep.prompt} setPrompt={pendingStep.setPrompt} busy={pendingStep.busy}
            toolMap={pendingStep.toolMap} catalog={pendingStep.catalog} scope={pendingStep.scope} platform={pendingStep.platform} go={go}
            onConfirm={pendingStep.onConfirm} onCancel={pendingStep.onCancel}
            onPickOption={pendingStep.onPickOption} onPickYaml={pendingStep.onPickYaml}
            armed={pendingStep.armed} onArmAppend={pendingStep.onArmAppend} advice={pendingStep.advice} />
        )}
      </div>
      <AgentDeviceEventLog events={events} />
    </div>
  );
}

// The null state is the session brief: name the target the trail is for, pick the device you'll
// demonstrate on (the pick carries into the session's mirror), and go - the two decisions a
// demonstration needs. Everything else (positioning, trailheads, describing) lives in the
// demonstration flow itself.
function AgentNewConversationBody({ go, onStarted }) {
  const [gt] = TB.useGlobalTarget();
  const target = gt && gt.target;
  const devices = TB.useDevices();
  const deviceList = (devices.data || []).filter((d) => d.connected);
  // Same sticky key as the composer's uiContext handoff and useAgentDeviceMirror - all three are
  // mounted at once, so the pick reaches them via useStickyState's cross-instance sync.
  const [deviceId, setDeviceId] = useStickyState('tb-agent-device', null);
  const selectedDevice = deviceList.find((d) => d.id === deviceId) || null;
  // "Choose target" opens the full target/device picker in a drawer INSTEAD of navigating to
  // Home - the session brief stays alive underneath.
  const [pickingTarget, setPickingTarget] = React.useState(false);
  React.useEffect(() => {
    // Picking a different target is the decision the drawer exists for - close it on success.
    if (pickingTarget && target) setPickingTarget(false);
  }, [target]);
  React.useEffect(() => {
    if ((!deviceId || !deviceList.some((d) => d.id === deviceId)) && deviceList.length) setDeviceId(deviceList[0].id);
  }, [devices.data]);
  // Interact's form language: a numbered dot + eyebrow label per section, hairline-separated
  // sections, and the full-width Select field (label inside the control) instead of filter chips.
  const dotStyle = { width: 16, height: 16, borderRadius: 99, border: '1px solid var(--text-subtle)', color: 'var(--text-subtle)', fontSize: 9.5, fontWeight: 700, display: 'inline-grid', placeItems: 'center', flex: '0 0 auto' };
  // A made decision completes its dot in the app's "done" green with a check (the same treatment
  // as captured tape steps) - color carries state, the glyph is the redundant cue (ch08).
  const doneDotStyle = { ...dotStyle, border: '1px solid transparent', background: 'rgba(0,224,19,.14)', color: 'var(--tb-pass)' };
  const sectionLabel = (n, text, done) => (
    <label className="tb-eyebrow" style={{ fontSize: 9.5, display: 'flex', alignItems: 'center', gap: 7 }}>
      <span style={done ? doneDotStyle : dotStyle}>{done ? <Ico n="check" s={10} c="var(--tb-pass)" /> : n}</span>{text}
    </label>
  );
  const sectionStyle = { borderTop: '1px solid var(--tb-hairline)', padding: '10px 13px 12px', display: 'flex', flexDirection: 'column', gap: 7 };
  // Options carry the icon of what the thing IS: devices get their platform mark - no default wrenches.
  const platIco = (p) => (p === 'ios' ? 'apple' : p === 'web' ? 'globe' : 'smartphone');
  // The one door in: create a demo run bound to the picked device, then hand off to the phased
  // Position -> Record -> Describe -> Generate flow. Needs a target and a connected device -
  // everything after is driven on that device.
  const [demoStarting, setDemoStarting] = React.useState(false);
  const [demoErr, setDemoErr] = React.useState(null);
  const canDemo = !!target && !!selectedDevice;
  const beginDemo = async () => {
    if (demoStarting || !canDemo) return;
    setDemoStarting(true);
    setDemoErr(null);
    const r = await TB.startDemo({
      target: target || null,
      platform: selectedDevice.platform || null,
      trailblazeDeviceId: { instanceId: selectedDevice.id, trailblazeDevicePlatform: (selectedDevice.platform || '').toUpperCase() },
      title: 'Record: ' + ((gt && (gt.label || gt.target)) || 'recording'),
    });
    setDemoStarting(false);
    if (!r || r.ok === false || !r.runId) { setDemoErr((r && r.error) || 'Could not start the session.'); return; }
    if (onStarted) onStarted({ id: r.runId });
  };
  return (
    <div style={{ flex: 1, display: 'flex', justifyContent: 'center' }}>
      <div style={{ width: 'min(560px, 100%)', paddingTop: 'clamp(24px, 10vh, 110px)' }}>
        <EmptyState ico="clapperboard" icoColor="var(--tb-ai)" icoBg="rgba(181,140,255,.12)" title="Record a demonstration" sub="Show the flow once on a live device - an agent turns it into a durable, verified trail." />
        {/* The whole brief: pick the app, pick the device, go. */}
        <div style={{ marginTop: 16, border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-subtle)', overflow: 'hidden' }}>
          <div style={{ ...sectionStyle, borderTop: 'none' }}>
            {sectionLabel(1, 'Target', !!gt)}
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <AppIcon target={target} size={26} radius={7} fallbackColor={gt ? 'var(--tb-pass)' : 'var(--text-subtle-variant)'} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div style={{ fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{gt ? (gt.label || gt.target) : 'No target selected'}</div>
                {!gt && <div className="tb-sub" style={{ fontSize: 10.5, lineHeight: 1.4 }}>Pick the app this trail is for.</div>}
              </div>
              <Btn sm onClick={() => setPickingTarget(true)}>{gt ? 'Change' : 'Choose target'}</Btn>
            </div>
          </div>
          <div style={sectionStyle}>
            {sectionLabel(2, 'Device', !!(deviceList.length && deviceId && deviceList.some((d) => d.id === deviceId)))}
            {deviceList.length ? (
              <Select full value={deviceId || ''}
                options={deviceList.map((d) => ({
                  value: d.id,
                  label: d.name || d.id,
                  ico: platIco(d.platform),
                  meta: [d.platform, (d.name || d.id) !== d.id ? d.id : null].filter(Boolean).join(' · '),
                }))}
                onChange={(e) => setDeviceId(e.target.value)} />
            ) : devices.loading && !devices.data ? (
              // First paint: the device fetch is still in flight - "No devices connected" is a
              // hard claim we can't make yet (same class as the home footer's first-paint fix).
              <span className="tb-sub" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11.5 }}>
                <Ico n="loader-circle" s={11} spin /> Looking for connected devices…
              </span>
            ) : (
              <span className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.4 }}>No devices connected.</span>
            )}
          </div>
        </div>
        {/* One door in: target + device above, this button below. Everything else lives in the
            demonstration itself. */}
        <button type="button" onClick={beginDemo} disabled={demoStarting || !canDemo}
          title={!target ? 'Choose a target first' : !selectedDevice ? 'Connect a device first' : 'Jump into the demonstration'}
          style={{ width: '100%', display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 9, marginTop: 14, background: canDemo ? 'linear-gradient(180deg, rgba(181,140,255,.14), rgba(181,140,255,.05))' : 'var(--bg-subtle)', border: '1px solid ' + (canDemo ? 'var(--tb-ai)' : 'var(--tb-hairline)'), color: 'var(--text-standard)', borderRadius: 12, padding: '12px 15px', cursor: (demoStarting || !canDemo) ? 'default' : 'pointer', font: 'inherit', fontSize: 13.5, fontWeight: 700, opacity: demoStarting ? .7 : 1 }}>
          <Ico n={demoStarting ? 'loader-circle' : 'clapperboard'} s={17} c="var(--tb-ai)" spin={demoStarting} style={{ flex: '0 0 auto' }} />
          Start the demonstration
        </button>
        {!canDemo && (
          <div className="tb-sub" style={{ marginTop: 8, fontSize: 11, textAlign: 'center', color: 'var(--tb-amber)' }}>
            {!target ? 'Choose a target above to begin.' : 'Connect a device to begin.'}
          </div>
        )}
        {demoErr && (
          <div role="alert" style={{ display: 'flex', gap: 7, alignItems: 'flex-start', marginTop: 8, padding: '7px 10px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', background: 'rgba(255,90,90,.06)' }}>
            <Ico n="triangle-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
            <span data-selectable style={{ fontSize: 12, color: 'var(--tb-danger-text)', fontWeight: 600, lineHeight: 1.45 }}>The session did not start: {demoErr}</span>
          </div>
        )}
      </div>
      {pickingTarget && (
        <HelpOverlay title="Choose target" sub="Pick the app this trail is for. Your session setup stays right here - the drawer closes once a target is picked." onClose={() => setPickingTarget(false)}>
          <TargetDevicePicker go={go} />
        </HelpOverlay>
      )}
    </div>
  );
}

// "working…" plus WHAT it's working on: a first turn can run for minutes, and the only honest
// live signal is the newest tool call. Names the current activity so the wait reads as progress.
function AgentWorkingRow({ events }) {
  const current = React.useMemo(() => {
    const evs = events || [];
    for (let i = evs.length - 1; i >= 0; i--) {
      if (evs[i].kind === 'tool_call') return { name: agentToolMeta(evs[i], null).name, sinceMs: evs[i].timeMs };
      if (evs[i].kind === 'user_message') break;
    }
    const last = evs[evs.length - 1];
    return { name: null, sinceMs: last ? last.timeMs : null };
  }, [events]);
  // Elapsed time on the newest activity: a call wedged for minutes must LOOK wedged, not
  // pixel-identical to one started a second ago. Server timestamp anchors, client clock ticks
  // (clock skew just offsets the counter by a constant); quiet under 5s so quick calls stay calm.
  const [now, setNow] = React.useState(() => Date.now());
  React.useEffect(() => { const t = setInterval(() => setNow(Date.now()), 1000); return () => clearInterval(t); }, []);
  const secs = current.sinceMs ? Math.max(0, Math.floor((now - current.sinceMs) / 1000)) : 0;
  const elapsed = secs >= 60 ? `${Math.floor(secs / 60)}m ${secs % 60}s` : `${secs}s`;
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-subtle)', fontSize: 12, padding: '2px 4px' }}>
      <Ico n="loader-circle" s={13} c="var(--tb-running)" spin /> working…
      {current.name && <span className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle)' }}>{current.name}</span>}
      {secs >= 5 && <span className="tb-sub" style={{ fontSize: 11 }}>· {elapsed}{secs >= 120 ? ' - still waiting on this call' : ''}</span>}
    </div>
  );
}

// The single composer for both modes: with no run it STARTS a conversation (agent / access /
// model chosen inline, chat-style); with a run it REPLIES to the same vendor thread. Enter sends,
// Shift+Enter breaks a line.
// The ask_user questions still awaiting an answer: every ask_user command the agent emitted after
// the last thing the human said. They surface in the composer (not the stream) so the human can
// answer several at once and send them together.
function pendingAskQuestions(events) {
  let lastUser = -1;
  (events || []).forEach((e, i) => { if (e.kind === 'user_message') lastUser = i; });
  const qs = [];
  (events || []).forEach((e, i) => {
    if (i > lastUser && e.kind === 'ui_command' && e.uiCommand && e.uiCommand.action === 'ask_user') qs.push(e.uiCommand);
  });
  return qs;
}

// The clickable answers for one ask_user question. Trailhead questions fill from the workspace's
// live trailheads; the value keeps the "Start from the ... trailhead." phrasing so the trail preview
// still recognizes it. Otherwise the agent's own pipe-separated params.options.
function askQuestionOptions(cmd, trailmaps, target) {
  const source = String((cmd.params && cmd.params.source) || '').toLowerCase();
  if (source === 'trailheads') {
    const heads = target ? TB.trailheadsForPlatform(trailmaps || [], target, null) : [];
    return heads.map((t) => ({ label: t.name, value: `Start from the "${t.name}" trailhead.`, mono: true }));
  }
  return String((cmd.params && cmd.params.options) || '')
    .split('|').map((s) => s.trim()).filter(Boolean)
    .map((o) => ({ label: o, value: o }));
}

// A compact, human-first read of a permission request's tool input: lead with the command or
// description the agent wants to run, fall back to other meaningful fields, else the raw JSON.
function permissionInputPreview(inputJson) {
  if (inputJson == null) return '';
  const parsed = parseMaybeJson(inputJson);
  if (parsed && typeof parsed === 'object' && !Array.isArray(parsed)) {
    for (const key of ['command', 'description', 'cmd', 'prompt', 'path', 'file_path', 'url', 'query']) {
      const v = parsed[key];
      if (typeof v === 'string' && v.trim()) return truncateMiddle(v.trim(), 300);
    }
    try { return truncateMiddle(JSON.stringify(parsed, null, 2), 300); } catch (_) { return ''; }
  }
  return truncateMiddle(String(parsed), 300);
}

// The tool-permission gate. One card per pending request the agent raised (tool name + a compact
// input preview), with Allow / Always allow (this tool) / Deny. A decision posts and then relies on
// the next poll to drop the card from run.pendingPermissions - the buttons stay optimistically
// disabled in the meantime so a double-tap can't double-answer. A mid-run auto-approve toggle sits
// beneath, so the human can hand the agent a free rein without babysitting each request.
function AgentPermissionBar({ run, running, onDecided }) {
  const pending = (run && Array.isArray(run.pendingPermissions)) ? run.pendingPermissions : [];
  const autoApprove = !!(run && run.autoApprove);
  const [inflight, setInflight] = React.useState({});
  const [autoBusy, setAutoBusy] = React.useState(false);
  React.useEffect(() => { setInflight({}); }, [run && run.id]);
  if (!run || (!pending.length && !running)) return null;
  const decide = async (requestId, decision) => {
    if (!requestId || inflight[requestId]) return;
    setInflight((m) => ({ ...m, [requestId]: true }));
    await TB.decideExternalAgentPermission(run.id, requestId, decision);
    if (onDecided) onDecided();
    // Leave the card disabled; the poll removes it from run.pendingPermissions once the server clears it.
  };
  const toggleAuto = async () => {
    if (autoBusy) return;
    setAutoBusy(true);
    await TB.setExternalAgentAutoApprove(run.id, !autoApprove);
    setAutoBusy(false);
    if (onDecided) onDecided();
  };
  return (
    <div style={{ flex: '0 0 auto', borderTop: '1px solid var(--tb-hairline)', padding: '12px 24px 0' }}>
      <div style={{ maxWidth: 760, margin: '0 auto', display: 'flex', flexDirection: 'column', gap: 8 }}>
        {pending.map((p) => {
          const busy = !!inflight[p.id];
          const preview = permissionInputPreview(p.inputJson);
          return (
            <div key={p.id} style={{ border: '1px solid var(--tb-hairline-strong)', borderLeft: '3px solid var(--tb-amber)', borderRadius: 10, background: 'var(--bg-subtle)', padding: '10px 12px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                <Ico n="shield-alert" s={14} c="var(--tb-amber)" style={{ flex: '0 0 auto' }} />
                <span style={{ fontSize: 12.5, fontWeight: 700 }}>Permission needed</span>
                <span className="tb-mono" data-selectable style={{ fontSize: 11.5, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--text-subtle-variant)' }}>{p.toolName}</span>
              </div>
              {preview && (
                <pre className="tb-mono" data-selectable style={{ margin: '6px 0 0', fontSize: 11.5, lineHeight: 1.5, color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 120, overflow: 'auto' }}>{preview}</pre>
              )}
              <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 10, flexWrap: 'wrap' }}>
                <Btn sm kind="primary" ico={busy ? 'loader-2' : 'check'} spin={busy} disabled={busy} onClick={() => decide(p.id, 'allow')}>Allow</Btn>
                <Btn sm ico="check-check" disabled={busy} onClick={() => decide(p.id, 'allow_always')} title="Always allow this tool for the rest of this run">Always allow</Btn>
                <span style={{ flex: 1 }} />
                <Btn sm ico="x" disabled={busy} onClick={() => decide(p.id, 'deny')}
                  style={{ borderColor: 'rgba(255,90,90,.35)', color: 'var(--tb-danger-text)' }}>Deny</Btn>
              </div>
            </div>
          );
        })}
        {running && (
          <label style={{ display: 'flex', alignItems: 'center', gap: 8, cursor: autoBusy ? 'default' : 'pointer', fontSize: 11.5, color: 'var(--text-subtle-variant)', paddingBottom: 2 }}>
            <input type="checkbox" checked={autoApprove} disabled={autoBusy} onChange={toggleAuto} />
            <Ico n={autoApprove ? 'shield-check' : 'shield'} s={13} c={autoApprove ? 'var(--tb-pass)' : 'var(--text-subtle)'} />
            <span>Auto-approve the agent's tool requests for this run</span>
            {autoBusy && <Ico n="loader-2" s={12} spin c="var(--text-subtle)" />}
          </label>
        )}
      </div>
    </div>
  );
}

function AgentComposer({ run, supported, go, onStarted, onTurn, recording, pendingQuestions }) {
  const status = TB.useStatus();
  const [gt] = TB.useGlobalTarget();
  const trailmaps = TB.useTrailmaps();
  // The session brief's device pick (shared sticky key): handed to the agent as
  // uiContext.deviceId so it connects straight to it - measured live, an agent WITHOUT this
  // burned its first turn on device(action=LIST) + retries before doing any real work.
  const devices = TB.useDevices();
  const [agentDeviceId] = useStickyState('tb-agent-device', null);
  const [agentType, setAgentType] = useStickyState('tb-external-agent-type', 'claude');
  const [access, setAccess] = useStickyState('tb-external-agent-sandbox', 'workspace-write');
  const [model, setModel] = useStickyState('tb-external-agent-model', '');
  const [text, setText] = React.useState('');
  const [picks, setPicks] = React.useState({});
  const [sending, setSending] = React.useState(false);
  const [error, setError] = React.useState(null);
  // A message typed while the agent is mid-turn: held here and delivered the moment the turn
  // finishes. Vendor CLIs can't take input mid-turn (the process owns its stdin), so the queue is
  // how "talk to it while it works" becomes real instead of a disabled text box.
  const [queued, setQueued] = React.useState(null);
  const [stopping, setStopping] = React.useState(false);
  const textareaRef = React.useRef(null);

  const running = !!run && run.status === 'running';
  // Stop the agent mid-turn from the composer itself (the header's Stop is out of the eye's path
  // while you're typing a reply). Disabled while the cancel is in flight; a poll clears the run's
  // running status, which un-renders the button.
  const stop = async () => {
    if (stopping || !running) return;
    setStopping(true);
    await TB.cancelExternalAgent(run.id);
    setStopping(false);
    if (onTurn) onTurn();
  };
  React.useEffect(() => { if (!running) setStopping(false); }, [running]);
  const canReply = !!run && !running && !!run.externalThreadId;
  const questions = (run && canReply && pendingQuestions) ? pendingQuestions : [];
  const hasPicks = Object.values(picks).some(Boolean);
  const available = (supported || []).filter((a) => a.available !== false);
  const selected = (supported || []).find((a) => a.id === agentType) || null;
  const selectedAvailable = !selected || selected.available !== false;
  const canSend = !sending && (run
    ? (running ? text.trim().length > 0 : (canReply && (text.trim().length > 0 || hasPicks)))
    : (selectedAvailable && text.trim().length > 0));
  // Model options come predefined from the provider registry; empty id = the CLI's own default.
  const modelOptions = (selected && selected.models && selected.models.length)
    ? selected.models
    : [{ id: '', display: 'Default' }];
  const modelValue = modelOptions.some((m) => m.id === model) ? model : '';

  // Reset staged answers when the set of pending questions changes (a new turn) or the run switches.
  const questionSig = questions.map((q) => q.message).join('|');
  React.useEffect(() => { setPicks({}); }, [questionSig, run && run.id]);
  // A queued message belongs to ONE run; switching conversations drops it back into the box so
  // it isn't silently delivered to (or lost from) the wrong session.
  React.useEffect(() => {
    setQueued((q) => { if (q) setText((t) => t || q); return null; });
  }, [run && run.id]);

  // Deliver the queued message the moment the turn finishes. A cancelled run gets nothing sent
  // on its behalf - the text returns to the box for the human to decide.
  React.useEffect(() => {
    if (!queued || running) return;
    if (!canReply || (run && run.status === 'cancelled')) {
      setQueued(null);
      setText((t) => t || queued);
      return;
    }
    const msg = queued;
    setQueued(null);
    setSending(true);
    TB.replyExternalAgent(run.id, msg).then((r) => {
      setSending(false);
      if (!r.ok) {
        setError(r.error || 'Could not deliver your queued message.');
        setText((t) => t || msg);
        return;
      }
      if (onTurn) onTurn();
    });
  }, [queued, running, canReply, run && run.status]);

  React.useEffect(() => {
    if (available.length && !available.some((a) => a.id === agentType)) setAgentType(available[0].id);
  }, [available.map((a) => a.id).join(',')]);
  // Session recipes never land in the text box: a preset arms them to ride the start request as
  // promptPreamble (under the hood), leaving the composer for the human's own words.
  const [preset, setPreset] = React.useState(null);
  React.useEffect(() => {
    const onFill = (e) => {
      // A plain fill is a different intent than an armed guided session - disarm it (through the
      // preset event, so the brief's "Start from" field hears the same).
      window.dispatchEvent(new CustomEvent('tb:agent-composer-preset'));
      setText(e.detail || '');
      requestAnimationFrame(() => { const el = textareaRef.current; if (el) el.focus(); });
    };
    const onPreset = (e) => {
      // An object detail arms (trailhead may be null: guided without an entry point picked);
      // no detail clears - the brief dispatches that on an explicit revert. Quiet updates carry
      // the brief's parameter edits - refresh the preset without stealing the keyboard focus.
      setPreset(e.detail || null);
      if (e.detail && !e.detail.quiet) requestAnimationFrame(() => { const el = textareaRef.current; if (el) el.focus(); });
    };
    window.addEventListener('tb:agent-composer-fill', onFill);
    window.addEventListener('tb:agent-composer-preset', onPreset);
    return () => {
      window.removeEventListener('tb:agent-composer-fill', onFill);
      window.removeEventListener('tb:agent-composer-preset', onPreset);
    };
  }, []);
  // Presets only make sense for a NEW conversation; drop one when switching to an existing run.
  React.useEffect(() => { if (run) setPreset(null); }, [run && run.id]);

  // Chat-box sizing: grow with the content up to ~6 lines, then scroll - so a longer message is
  // never cut off behind a fixed two-line box. Runs on every text change (typing, fills, clears).
  React.useLayoutEffect(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 126) + 'px';
  }, [text]);

  // One reply carries every staged answer plus anything typed. Trailhead answers are already full
  // sentences; other answers get their question restated so the agent has context.
  const composeReply = () => {
    const lines = [];
    questions.forEach((q, i) => {
      const v = picks[i];
      if (!v) return;
      const isTrailhead = String((q.params && q.params.source) || '').toLowerCase() === 'trailheads';
      lines.push(isTrailhead ? v : `${q.message}\n→ ${v}`);
    });
    return [lines.join('\n\n'), text.trim()].filter(Boolean).join('\n\n');
  };

  const send = async () => {
    if (sending || !canSend) return;
    if (run && running) {
      // Mid-turn: stage it. The flush effect above delivers it when the turn ends. Appending to
      // an existing queued message keeps "send, then send again" from dropping the first one.
      const t = text.trim();
      if (t) setQueued((q) => (q ? `${q}\n\n${t}` : t));
      setText('');
      setError(null);
      return;
    }
    setSending(true);
    setError(null);
    if (run) {
      const msg = composeReply();
      const r = await TB.replyExternalAgent(run.id, msg);
      setSending(false);
      if (!r.ok) { setError(r.error || 'Could not send the reply.'); return; }
      setText('');
      setPicks({});
      if (onTurn) onTurn();
      return;
    }
    const t = text.trim();
    // The armed trailhead stays visible as ONE short line (the same phrasing an ask_user answer
    // uses) - it's part of what the human chose, and the live trail preview reads it from the
    // message. Only the session script itself (and the configured step YAML) is under the hood.
    const visiblePrompt = preset && preset.trailhead
      ? `${t}\n\nStart from the "${preset.trailhead}" trailhead${preset.summary ? ` (${preset.summary})` : ''}.`
      : t;
    const response = await TB.startExternalAgent({
      agentType,
      prompt: visiblePrompt,
      title: t.length > 72 ? t.slice(0, 69) + '...' : t,
      model: modelValue || null,
      cwd: status.data && status.data.trailsDirectory,
      sandbox: access,
      includeUiContract: true,
      // The armed recipe rides here, not in `prompt`: the transcript shows only the human's words.
      promptPreamble: preset ? composePresetPreamble(preset) : null,
      uiContext: {
        route: 'agents',
        target: gt && gt.target,
        deviceId: (() => {
          const d = (devices.data || []).find((x) => x.id === agentDeviceId && x.connected);
          const platform = d ? String(d.platform || '').toUpperCase() : '';
          // The platform decodes into a server-side enum; an unknown value would fail the WHOLE
          // start request. A device we can't describe degrades to "no hint", not a dead send.
          if (!['ANDROID', 'IOS', 'WEB', 'DESKTOP'].includes(platform)) return null;
          return { instanceId: d.id, trailblazeDevicePlatform: platform };
        })(),
      },
    });
    setSending(false);
    if (!response || response.ok === false) {
      setError((response && response.error) || 'Could not start the agent.');
      return;
    }
    setText('');
    setPreset(null);
    if (onStarted) onStarted(response.run);
  };

  const answeredCount = Object.values(picks).filter(Boolean).length;
  // "Send your selections" is only honest when at least one pending question actually offers
  // selectable chips - an options-less ask gets typed-answer copy instead.
  const anyOptions = questions.some((q) => askQuestionOptions(q, trailmaps.data || [], gt && gt.target).length > 0);

  return (
    <div style={{ flex: '0 0 auto', borderTop: '1px solid var(--tb-hairline)', padding: '12px 24px 14px' }}>
      <div style={{ maxWidth: 760, margin: '0 auto', border: '1px solid var(--tb-hairline-strong)', borderRadius: 14, background: 'var(--bg-subtle)', padding: '10px 12px' }}>
        {/* Pending questions answer here, in the composer - pick one per question, then send them all
            together. Dense: question, then chips; the selected chip stays lit until you send. */}
        {questions.length > 0 && (
          <div style={{ marginBottom: 10, paddingBottom: 10, borderBottom: '1px solid var(--tb-hairline)', display: 'flex', flexDirection: 'column', gap: 9 }}>
            <div className="tb-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
              <Ico n="list-checks" s={12} c="var(--tb-ai)" /> {questions.length === 1 ? 'Answer to continue' : `Answer ${questions.length} questions`}
              <span style={{ flex: 1 }} />
              {answeredCount > 0 && <span className="tb-sub" style={{ fontSize: 10.5 }}>{answeredCount}/{questions.length} selected</span>}
            </div>
            {questions.map((q, i) => {
              const opts = askQuestionOptions(q, trailmaps.data || [], gt && gt.target);
              return (
                <div key={i} style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
                  <span data-selectable style={{ fontSize: 12, fontWeight: 600, lineHeight: 1.4 }}>{q.message}</span>
                  {opts.length > 0 ? (
                    <div style={{ display: 'flex', flexWrap: 'wrap', gap: 5 }}>
                      {opts.map((o) => {
                        const sel = picks[i] === o.value;
                        return (
                          <button key={o.label} type="button"
                            onClick={() => setPicks((p) => ({ ...p, [i]: sel ? undefined : o.value }))}
                            className={o.mono ? 'tb-mono' : ''}
                            style={{ cursor: 'pointer', font: 'inherit', fontSize: o.mono ? 11 : 11.5, fontWeight: 600, borderRadius: 8, padding: '3px 9px',
                              color: sel ? 'var(--text-standard)' : 'var(--text-subtle-variant)',
                              background: sel ? 'rgba(94,155,255,.16)' : 'var(--bg-standard)',
                              border: '1px solid ' + (sel ? 'var(--tb-ai)' : 'var(--tb-hairline-strong)') }}>
                            {sel && <Ico n="check" s={11} c="var(--tb-ai)" style={{ marginRight: 4, verticalAlign: '-1px' }} />}{o.label}
                          </button>
                        );
                      })}
                    </div>
                  ) : (
                    <span className="tb-sub" style={{ fontSize: 11 }}>Type your answer below.</span>
                  )}
                </div>
              );
            })}
          </div>
        )}
        {/* The armed session recipe, visible as a compact chip - the script itself stays under the
            hood (it rides the start request as promptPreamble). Dismissible: ✕ returns to a plain
            conversation. */}
        {!run && preset && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8, padding: '6px 10px', borderRadius: 9, background: 'rgba(155,124,255,.07)', border: '1px solid rgba(155,124,255,.28)' }}>
            <Ico n="sparkles" s={13} c="var(--tb-ai)" style={{ flex: '0 0 auto' }} />
            <span style={{ flex: '0 0 auto', fontSize: 11.5, fontWeight: 700 }}>Guided trail composition</span>
            {preset.trailhead && <span className="tb-mono" style={{ flex: '0 0 auto', fontSize: 10.5, color: 'var(--text-subtle-variant)' }}>from {preset.trailhead}{preset.summary ? ` · ${preset.summary}` : ''}</span>}
            <span className="tb-sub" style={{ flex: 1, minWidth: 0, fontSize: 10.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>Rides along under the hood.</span>
            <button type="button" onClick={() => window.dispatchEvent(new CustomEvent('tb:agent-composer-preset'))} title="Remove - start a plain conversation instead"
              style={{ flex: '0 0 auto', display: 'inline-flex', border: 'none', background: 'transparent', color: 'var(--text-subtle)', cursor: 'pointer', padding: 2 }}>
              <Ico n="x" s={12} />
            </button>
          </div>
        )}
        {/* The staged mid-turn message, visible and revocable until the turn ends and it delivers. */}
        {queued && (
          <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8, marginBottom: 8, padding: '6px 10px', borderRadius: 9, background: 'rgba(94,155,255,.07)', border: '1px solid rgba(94,155,255,.28)' }}>
            <Ico n="clock" s={13} c="var(--tb-ai)" style={{ flex: '0 0 auto', marginTop: 2 }} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 11.5, fontWeight: 700 }}>Queued - delivers when this turn finishes</div>
              <div data-selectable className="tb-sub" style={{ fontSize: 11, marginTop: 1, lineHeight: 1.45, whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 60, overflow: 'hidden' }}>{queued}</div>
            </div>
            <button type="button" onClick={() => { setText((t) => t ? `${queued}\n\n${t}` : queued); setQueued(null); }} title="Un-queue - put it back in the box to edit"
              style={{ flex: '0 0 auto', display: 'inline-flex', border: 'none', background: 'transparent', color: 'var(--text-subtle)', cursor: 'pointer', padding: 2 }}>
              <Ico n="x" s={12} />
            </button>
          </div>
        )}
        <textarea ref={textareaRef} value={text} onChange={(e) => { setText(e.target.value); if (error) setError(null); }} rows={2}
          disabled={sending || (!!run && !running && !canReply)}
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send(); } }}
          placeholder={run
            ? (running ? 'The agent is working - type here and send; it delivers when this turn finishes…'
              : !canReply ? 'No session id captured - start a new conversation to chat.'
              : questions.length ? (anyOptions ? 'Add anything else, or just send your selections…' : 'Type your answer here, then send…')
              : recording ? 'Reply, or narrate what you’re demonstrating - send hands it to the agent…' : 'Reply to continue this conversation…')
            : preset ? 'Validate that a user can…'
            : 'Describe a flow to build, or a task…'}
          style={{ width: '100%', boxSizing: 'border-box', minHeight: 40, maxHeight: 126, overflowY: 'auto', resize: 'none', background: 'transparent', border: 'none', outline: 'none', color: 'var(--text-standard)', font: 'inherit', fontSize: 13.5, lineHeight: 1.5 }} />
        {/* Left group may wrap on narrow columns; model + send stay pinned to the right edge so
            the send button never drops onto its own line. */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginTop: 6 }}>
          <div style={{ flex: 1, minWidth: 0, display: 'flex', alignItems: 'center', gap: 14, flexWrap: 'wrap' }}>
            {run ? (
              <>
                <Chip>{run.agentType}</Chip>
                {run.model && <Chip>{run.model}</Chip>}
              </>
            ) : (
              <>
                {/* Session config grouped left, one quiet treatment across every control. */}
                <AgentQuietPicker title="Agent" value={agentType} onChange={setAgentType}
                  options={(supported && supported.length ? supported : [{ id: 'claude', display: 'Claude Code', available: true }])
                    .map((a) => ({ id: a.id, display: `${a.display}${a.available === false ? ' (not installed)' : ''}` }))} />
                <AgentQuietPicker title="Access level" value={access} onChange={setAccess}
                  options={EXTERNAL_AGENT_ACCESS_OPTIONS.map(([id, display]) => ({ id, display }))} />
              </>
            )}
          </div>
          {!run && (
            <button type="button" onClick={() => go('agents-setup')} title="Configure agents"
              style={{ display: 'inline-flex', alignItems: 'center', border: 'none', background: 'transparent', color: 'var(--text-subtle)', cursor: 'pointer', padding: 2 }}>
              <Ico n="settings-2" s={13} />
            </button>
          )}
          {!run && <AgentQuietPicker title="Model" value={modelValue} options={modelOptions} onChange={setModel} />}
          {running && (
            <Btn sm ico={stopping ? 'loader-circle' : 'square'} spin={stopping} disabled={stopping} onClick={stop}
              title="Stop the agent" style={{ borderColor: 'rgba(255,90,90,.35)', color: 'var(--tb-danger-text)' }}>
              {stopping ? 'Stopping…' : 'Stop'}
            </Btn>
          )}
          <Btn kind="primary" ico={sending ? 'loader-2' : 'arrow-up'} spin={sending} disabled={!canSend} onClick={send} title="Send (Enter)" />
        </div>
      </div>
      {/* A recoverable failure, not a status code: what went wrong, and the way back (the typed
          message is never cleared on failure, so retry is just pressing send again). */}
      {error && (
        <div role="alert" style={{ display: 'flex', gap: 7, alignItems: 'flex-start', marginTop: 8, padding: '7px 10px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', background: 'rgba(255,90,90,.06)' }}>
          <Ico n="triangle-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
          <div style={{ minWidth: 0 }}>
            <div data-selectable style={{ fontSize: 12, color: 'var(--tb-danger-text)', fontWeight: 600, lineHeight: 1.45, wordBreak: 'break-word' }}>
              {run ? 'The reply was not sent' : 'The conversation did not start'}: {error}
            </div>
            <div className="tb-sub" style={{ fontSize: 10.5, marginTop: 2, lineHeight: 1.45 }}>
              Your message is still in the box - press send to retry. If this keeps happening, the daemon log has the full story.
            </div>
          </div>
        </div>
      )}
      {!!run && !running && !canReply && (
        <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 7 }}>
          This conversation can't be resumed - no agent session id was captured. Start a new conversation.
        </div>
      )}
    </div>
  );
}

// The composer's single control treatment (similarity, ch06): every picker in the row is the
// app's low-chrome inline Select — quiet text trigger, and the SHARED custom dropdown
// (tb-select-pop, same as the Create-blaze footer's model picker), never the OS-native menu.
function AgentQuietPicker({ value, options, onChange, title }) {
  const current = options.find((o) => o.id === value) || options[0];
  return (
    <Select compact subtle
      title={title}
      value={current ? current.id : ''}
      options={options.map((o) => [o.id, o.display])}
      label={(current && current.display) || ''}
      onChange={(e) => onChange(e.target.value)} />
  );
}

// ─── Trail rail (right) ──────────────────────────────────────────────────────

// No Device tab: the device IS the stage in the center of the screen. The rail is about the trail
// the session is producing - the steps, the YAML, and the context feeding them.
const AGENT_PANEL_TABS = [
  ['trail', 'Trail', 'list-video'],
  ['elements', 'Elements', 'list-tree'],
  ['yaml', 'YAML', 'file-code'],
  ['skills', 'Skills', 'graduation-cap'],
  ['artifacts', 'Artifacts', 'folder-open'],
];

function AgentTrailRail({ width, tab, setTab, tape, events, run, go, pendingStep, stepErr, trailhead, inspector, save }) {
  // Sticky keys may carry a retired tab id (e.g. 'device') from an older layout - fall back.
  const current = AGENT_PANEL_TABS.some(([id]) => id === tab) ? tab : 'trail';
  // The trail (YAML tab) grows while you watch the device: every demonstrated action and every
  // trail_output the agent declares moves it. A dot on the tab says "the shared artifact changed
  // since you last looked" - it clears the moment you open the tab.
  const trailPulse = React.useMemo(() => {
    let n = 0;
    (events || []).forEach((e) => {
      if (e.kind === 'human_action' || (e.kind === 'ui_command' && e.uiCommand && e.uiCommand.action === 'trail_output')) n++;
    });
    return n;
  }, [events]);
  // "Seen" is per run, and loaded history is not news: the first non-empty events render seeds
  // the baseline (seeded=false until then), so only changes that happen while you're watching
  // light the dot - not opening a past conversation that happens to contain trail output.
  const runId = run && run.id;
  const seenPulseRef = React.useRef({ runId, pulse: 0, seeded: false });
  if (seenPulseRef.current.runId !== runId || !(events || []).length) {
    seenPulseRef.current = { runId, pulse: trailPulse, seeded: false };
  } else if (!seenPulseRef.current.seeded) {
    seenPulseRef.current = { runId, pulse: trailPulse, seeded: true };
  }
  React.useEffect(() => { if (current === 'yaml') seenPulseRef.current = { runId, pulse: trailPulse, seeded: true }; }, [current, trailPulse, runId]);
  const yamlUnseen = current !== 'yaml' && seenPulseRef.current.seeded && trailPulse > seenPulseRef.current.pulse;
  return (
    // overflow hidden makes the DRAGGED width authoritative: a flex item's min-width defaults to
    // its content's min-content size, so one long unwrappable line (a bash command, a selector
    // string) would otherwise push the whole rail wider than the splitter set it. flex '0 1' with
    // a floor lets the rail yield on narrow windows instead of crushing the stage.
    <div style={{ flex: '0 1 ' + (width || 360) + 'px', minWidth: 240, overflow: 'hidden', minHeight: 0, borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
      <div style={{ display: 'flex', gap: 4, padding: '10px 12px 0', borderBottom: '1px solid var(--tb-hairline)' }}>
        {AGENT_PANEL_TABS.map(([id, label, ico]) => (
          <button key={id} type="button" onClick={() => setTab(id)}
            style={{ display: 'inline-flex', alignItems: 'center', gap: 6, border: 'none', background: 'transparent', cursor: 'pointer', font: 'inherit', fontSize: 12, fontWeight: 600, padding: '6px 10px 9px', color: current === id ? 'var(--text-standard)' : 'var(--text-subtle)', borderBottom: '2px solid ' + (current === id ? 'var(--tb-ai)' : 'transparent') }}>
            <Ico n={ico} s={13} c={current === id ? 'var(--tb-ai)' : 'var(--text-subtle)'} /> {label}
            {id === 'yaml' && yamlUnseen && <span title="The trail changed since you last looked" style={{ width: 6, height: 6, borderRadius: 99, background: 'var(--tb-ai)', display: 'inline-block' }} />}
          </button>
        ))}
      </div>
      {current === 'trail' && <AgentTrailTapePanel tape={tape} events={events} pendingStep={pendingStep} stepErr={stepErr} go={go} trailhead={trailhead} />}
      {current === 'elements' && <AgentElementsPanel inspector={inspector} />}
      {current === 'yaml' && <AgentYamlPanel events={events} go={go} save={save} pickedTrailhead={trailhead && trailhead.selected ? trailhead.selected.name : null} />}
      {current === 'skills' && <AgentSkillsPanel events={events} />}
      {current === 'artifacts' && <AgentArtifactsPanel run={run} go={go} />}
    </div>
  );
}

// The Elements tab: the on-screen accessibility tree as a browseable list, reusing the Interact
// recorder's panel (record.tsx loads first). Hovering highlights the element on the stage;
// clicking proposes a step at its center - same confirm gate as a stage tap.
function AgentElementsPanel({ inspector }) {
  const i = inspector || {};
  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '12px 14px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, paddingBottom: 8 }}>
        <Ico n="list-tree" s={14} c="var(--tb-pass)" />
        <span style={{ fontSize: 13, fontWeight: 700 }}>Elements</span>
        {i.total > 0 && <Chip>{i.total}</Chip>}
        <span style={{ flex: 1 }} />
        <Btn sm kind="ghost" ico={i.loading ? 'loader-circle' : 'refresh-cw'} spin={i.loading} onClick={i.refresh} disabled={i.loading}>Refresh</Btn>
      </div>
      <ElementsPanel els={i.els || []} total={i.total || 0} loading={i.loading} err={i.err}
        query={i.query || ''} setQuery={i.setQuery} mode="interact" disabled={i.busy}
        onHover={i.onHover} onPick={i.onPick} />
    </div>
  );
}

// The skills in the agent's context: everything the child CLI can discover from this workspace's
// .claude/skills (and the user's ~/.claude/skills), each with its description and where it lives
// on disk. Skills the agent actually invoked in THIS conversation are marked "used" - so "which
// expertise is being referenced" is answerable at a glance. Tops out with the target-knowledge
// nudge: what the agent learns about an app should get banked into a skill, not evaporate.
function AgentSkillsPanel({ events }) {
  const [gt] = TB.useGlobalTarget();
  const target = gt && gt.target;
  const [skills, setSkills] = React.useState(null);
  const [skillsError, setSkillsError] = React.useState(null);
  React.useEffect(() => {
    let stale = false;
    TB.fetchAgentSkills().then((r) => {
      if (stale) return;
      // A discovery failure is not "no skills": keep them apart so the empty state stays honest.
      setSkillsError(r && r.ok === false ? (r.error || 'Could not read the skills directories.') : null);
      setSkills((r && r.skills) || []);
    });
    return () => { stale = true; };
  }, []);
  // Skills the agent invoked in this conversation (Skill tool calls), by skill name.
  const used = React.useMemo(() => {
    const names = new Set();
    (events || []).forEach((e) => {
      if (e.kind !== 'tool_call' || String(e.toolName) !== 'Skill') return;
      const input = parseMaybeJson(e.input);
      const n = input && (input.skill || input.name);
      if (n) names.add(String(n));
    });
    return names;
  }, [events]);
  const list = skills || [];
  const targetSkills = React.useMemo(() => {
    if (!target) return [];
    const t = String(target).toLowerCase();
    return list.filter((s) => (s.name + ' ' + (s.description || '')).toLowerCase().includes(t));
  }, [list, target]);
  const askToSaveTargetSkill = () => window.dispatchEvent(new CustomEvent('tb:agent-composer-fill', {
    detail: `Bank what you have learned about ${target} in this session into a skill the next session can load: `
      + `create or update .claude/skills/${target}-target-notes/SKILL.md (frontmatter name + a description listing trigger phrases, `
      + `then concise notes: trailheads and entry states that work, selectors that proved durable, screens that need settle time, gotchas). `
      + `Keep it under 150 lines and only include what you actually observed.`,
  }));
  const scopes = [['workspace', 'Workspace', 'This repo\'s .claude/skills - travels with the codebase.'], ['user', 'Yours', '~/.claude/skills - personal, every project.']];
  return (
    <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '12px 14px' }}>
      {skills == null && <Skeleton rows={4} />}
      {skills != null && (
        <>
          {skillsError && (
            <div className="tb-sub" style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11, marginBottom: 12, color: 'var(--tb-fail)' }}>
              <Ico n="circle-x" s={12} c="var(--tb-fail)" style={{ flex: '0 0 auto' }} /> Skill discovery failed: {skillsError}
            </div>
          )}
          {/* The bank-your-knowledge nudge: green when the target has skills, amber when nothing
              captures it yet - with the one action that fixes it. */}
          {target && (targetSkills.length > 0 ? (
            <div className="tb-sub" style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11, marginBottom: 12 }}>
              <Ico n="circle-check" s={12} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />
              {targetSkills.length} skill{targetSkills.length === 1 ? '' : 's'} carry knowledge about <b>{target}</b>: {targetSkills.slice(0, 3).map((s) => s.name).join(', ')}{targetSkills.length > 3 ? '…' : ''}
            </div>
          ) : (
            <div style={{ display: 'flex', gap: 8, alignItems: 'flex-start', marginBottom: 12, padding: '8px 10px', borderRadius: 9, border: '1px solid rgba(255,180,60,.3)', background: 'rgba(255,180,60,.06)' }}>
              <Ico n="lightbulb" s={13} c="var(--tb-amber)" style={{ flex: '0 0 auto', marginTop: 1 }} />
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 11.5, fontWeight: 600, lineHeight: 1.4 }}>No skill captures what's known about {target}.</div>
                <div className="tb-sub" style={{ fontSize: 10.5, lineHeight: 1.45, margin: '2px 0 7px' }}>What the agent learns this session (working trailheads, durable selectors, gotchas) evaporates unless it's banked into a skill the next session loads.</div>
                <Btn sm onClick={askToSaveTargetSkill}>Ask the agent to save one</Btn>
              </div>
            </div>
          ))}
          {scopes.map(([scope, label, hint]) => {
            const group = list.filter((s) => s.scope === scope);
            if (!group.length) return null;
            return (
              <div key={scope} style={{ marginBottom: 14 }}>
                <div className="tb-eyebrow" title={hint} style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '0 2px 6px' }}>
                  <Ico n={scope === 'workspace' ? 'folder-git-2' : 'user'} s={11} c="var(--text-subtle)" /> {label} · {group.length}
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 2 }}>
                  {group.map((s) => (
                    <div key={s.dir} title={s.dir} style={{ display: 'flex', gap: 8, alignItems: 'flex-start', padding: '4px 4px', borderRadius: 6 }}>
                      <Ico n="graduation-cap" s={12} c={used.has(s.name) ? 'var(--tb-ai)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto', marginTop: 2 }} />
                      <div style={{ flex: 1, minWidth: 0 }}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
                          <span className="tb-mono" style={{ fontSize: 11.5, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{s.name}</span>
                          {used.has(s.name) && <Chip tone="blue">used</Chip>}
                        </div>
                        {s.description && (
                          <div className="tb-sub" data-selectable style={{ fontSize: 10.5, lineHeight: 1.45, overflow: 'hidden', display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical' }}>{s.description}</div>
                        )}
                        <div className="tb-mono" data-selectable style={{ fontSize: 9.5, color: 'var(--text-subtle)', marginTop: 1, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{shortPathSummary(s.dir)}</div>
                      </div>
                    </div>
                  ))}
                </div>
              </div>
            );
          })}
          {list.length === 0 && <EmptyState ico="graduation-cap" title="No skills found" sub="Add .claude/skills/<name>/SKILL.md to this workspace (or ~/.claude/skills) and the agent can invoke them." />}
        </>
      )}
    </div>
  );
}

// Every Trailblaze run that starts during this conversation — whether the agent kicked it off via
// MCP, the CLI, or the human did — lands here with its artifacts: screenshots, device logs, and
// the captured event streams (events/*.ndjson). The agent gets the same artifacts by path in its
// system context, so what you see here is what it can read to iterate on the trail.
function AgentArtifactsPanel({ run, go }) {
  const sessions = TB.useSessions();
  const windowed = React.useMemo(() => {
    if (!run) return [];
    const start = run.startedAtMs - 15000;
    const end = run.status === 'running' || !run.endedAtMs ? Number.POSITIVE_INFINITY : run.endedAtMs + 15000;
    return (sessions.data || []).filter((s) => s.timestampMs >= start && s.timestampMs <= end);
  }, [sessions.data, run && run.id, run && run.status, run && run.endedAtMs]);

  return (
    <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '12px 14px' }}>
      {!run && <EmptyState ico="folder-open" title="No conversation" sub="Artifacts from runs started during a conversation appear here." />}
      {run && windowed.length === 0 && (
        <EmptyState ico="folder-open" title="No runs yet" sub="When a trail runs during this conversation, its artifacts appear here." />
      )}
      {windowed.map((s) => <AgentSessionArtifacts key={s.id} session={s} go={go} />)}
    </div>
  );
}

function AgentSessionArtifacts({ session, go }) {
  const [open, setOpen] = React.useState(false);
  const [ico, color] = externalAgentStatusIcon(session.status === 'running' ? 'running' : session.status === 'passed' || session.status === 'completed' ? 'completed' : session.status === 'failed' ? 'failed' : '');
  return (
    <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', padding: '9px 11px', marginBottom: 8 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <button type="button" onClick={() => setOpen((v) => !v)}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 7, flex: 1, minWidth: 0, border: 'none', background: 'transparent', cursor: 'pointer', font: 'inherit', textAlign: 'left', padding: 0 }}>
          <Ico n={open ? 'chevron-down' : 'chevron-right'} s={12} c="var(--text-subtle)" />
          <Ico n={ico} s={13} c={color} spin={session.status === 'running'} />
          <span style={{ flex: 1, minWidth: 0, fontSize: 12, fontWeight: 600, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{session.title || session.id}</span>
        </button>
        <Btn sm ico="arrow-up-right" onClick={() => go('runs', { sel: session.id })} title="Open the run" />
      </div>
      {open && <AgentSessionArtifactList id={session.id} running={session.status === 'running'} />}
    </div>
  );
}

function AgentSessionArtifactList({ id, running }) {
  const files = TB.useSessionFiles(id);
  const events = TB.useSessionEvents(id, running);
  const fileList = files.data || [];
  const streams = (events.data && events.data.streams) || [];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 4, margin: '8px 0 2px 19px' }}>
      {streams.map((st) => (
        <div key={st.streamId} style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5 }}>
          <Ico n="radio" s={11} c="var(--tb-analytics)" />
          <span className="tb-mono" style={{ color: 'var(--text-subtle-variant)' }}>{st.label || st.streamId}</span>
          <span className="tb-sub">· {st.count} events{st.truncated ? ' (truncated)' : ''}</span>
        </div>
      ))}
      {fileList.map((f) => (
        <a key={f.name} href={TB.fileUrl(id, f.name)} target="_blank" rel="noreferrer"
          style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5, color: 'var(--text-subtle-variant)', textDecoration: 'none' }}>
          <Ico n="file" s={11} c="var(--text-subtle)" />
          <span className="tb-mono" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{f.name}</span>
          <span className="tb-sub">{f.size >= 1024 ? Math.round(f.size / 1024) + ' KB' : f.size + ' B'}</span>
        </a>
      ))}
      {!files.loading && fileList.length === 0 && streams.length === 0 && (
        <span className="tb-sub" style={{ fontSize: 11.5 }}>No files captured yet.</span>
      )}
    </div>
  );
}

// Watches the event stream for the trail the agent is composing (a trail_output declaration, or
// an open_trail navigation) and shows that file's current content, refreshed as new events land.
function AgentYamlPanel({ events, go, save, pickedTrailhead }) {
  const detected = React.useMemo(() => detectComposedTrail(events), [events]);
  const [gt] = TB.useGlobalTarget();
  const target = gt && gt.target;
  // Before the agent has saved the trail, show a live "trail so far" preview built from the chosen
  // trailhead and the demonstration tape, so the trail is visible from the very start of the session.
  const preview = React.useMemo(() => (detected ? null : buildTrailPreview(events, target, pickedTrailhead)), [detected, events, target, pickedTrailhead]);
  const [state, setState] = React.useState({ text: null, loading: false, error: null });

  const key = detected ? detected.trailId + '/' + detected.file : null;
  React.useEffect(() => {
    if (!detected) { setState({ text: null, loading: false, error: null }); return; }
    let stale = false;
    setState((s) => ({ ...s, loading: true }));
    const load = detected.file === 'blaze.yaml'
      ? TB.fetchBundleDetail(detected.trailId).then((d) => (d && d.blazeYaml) || null)
      : TB.fetchTrailFolderFile(detected.trailId, detected.file);
    load.then((text) => {
      if (stale) return;
      setState({ text, loading: false, error: text == null ? 'The file could not be read yet.' : null });
    });
    return () => { stale = true; };
    // events.length: refetch as the agent keeps writing during the run.
  }, [key, events.length]);

  const preStyle = { flex: 1, minHeight: 0, margin: 0, overflow: 'auto', whiteSpace: 'pre-wrap', wordBreak: 'break-word', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '9px 11px', fontSize: 11, lineHeight: 1.55, color: 'var(--text-subtle-variant)' };

  return (
    <div style={{ flex: 1, minHeight: 0, display: 'flex', flexDirection: 'column', padding: '12px 14px' }}>
      {detected ? (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: detected.summary ? 4 : 9 }}>
            <Ico n="file-code" s={13} c="var(--tb-ai)" />
            <span className="tb-mono" data-selectable style={{ flex: 1, minWidth: 0, fontSize: 11.5, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{detected.trailId.replace(/^\d+\//, '')}/{detected.file}</span>
            {detected.status && <Chip tone={detected.status === 'ready' ? 'green' : 'blue'}>{detected.status}</Chip>}
            <Btn sm ico="arrow-up-right" onClick={() => go('trails', { sel: detected.trailId })} title="Open in Trails" />
          </div>
          {detected.summary && <div className="tb-sub" data-selectable style={{ fontSize: 11.5, lineHeight: 1.5, marginBottom: 9 }}>{detected.summary}</div>}
          {state.error && state.text == null && !state.loading ? (
            <div className="tb-sub" style={{ fontSize: 12 }}>{state.error}</div>
          ) : (
            // The real trail editor: syntax highlighting + target-scoped autocomplete, and it reseeds
            // from `content` as the agent writes without clobbering your unsaved edits. Editing here
            // saves straight back to the trail file. No tools palette - too wide for this panel; the
            // in-editor autocomplete covers it.
            <div style={{ flex: 1, minHeight: 0 }}>
              <TrailYamlEditor
                content={state.text}
                editable
                tools={null}
                onSave={(txt) => TB.saveTrailFolderFile(detected.trailId, detected.file, txt)} />
            </div>
          )}
        </>
      ) : preview ? (
        <>
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
            <Ico n="file-code" s={13} c="var(--tb-ai)" />
            <span style={{ flex: 1, minWidth: 0, fontSize: 12, fontWeight: 700 }}>Trail so far</span>
            <Chip tone="blue">building</Chip>
            {/* The agentless exit: the demonstrated tape saves into the trail library without the
                agent writing anything. Disappears once the agent declares a real trail (detected). */}
            {save && (
              <Btn sm ico={save.saving ? 'loader-circle' : 'save'} spin={save.saving}
                onClick={save.onSave} disabled={save.saving || !save.stepCount}
                title={!save.stepCount ? 'Record at least one step first' : 'Save the recorded steps as a trail in your library'}>
                Save trail
              </Btn>
            )}
          </div>
          <div className="tb-sub" style={{ fontSize: 11, lineHeight: 1.5, marginBottom: 9 }}>
            Taking shape as you go{preview.stepCount ? ` · ${preview.stepCount} step${preview.stepCount === 1 ? '' : 's'} so far` : ''}. Save it yourself, or let the agent write it.
          </div>
          {save && save.err && <div role="alert" style={{ fontSize: 11.5, lineHeight: 1.5, color: 'var(--tb-danger-text)', marginBottom: 8 }}>{save.err}</div>}
          <pre className="tb-mono" data-selectable style={preStyle}>{preview.yaml}</pre>
        </>
      ) : (
        <EmptyState ico="file-code" title="The trail appears here" sub="Pick a starting trailhead to begin, and the trail takes shape as you demonstrate and the agent writes it." />
      )}
    </div>
  );
}

// A live "trail so far" preview so the trail is visible from the start of a composition session,
// before the agent writes the trail file. Derived from the chosen trailhead (the ask_user answer we
// generate) and the demonstration tape. Once the agent declares a real trail (trail_output),
// AgentYamlPanel shows that file instead - this is just the head start.
function buildTrailPreview(events, target, picked) {
  let trailhead = null;
  const steps = [];
  (events || []).forEach((e) => {
    if (e.kind === 'user_message' && e.text) {
      const m = String(e.text).match(/Start from the "([^"]+)" trailhead/);
      if (m) trailhead = m[1];
    }
    if (e.kind === 'human_action') steps.push(decodeEntities(e.title) || 'Tap');
  });
  // The trail rail's step-0 pick reflects here immediately, before any message mentions it.
  if (!trailhead && picked) trailhead = picked;
  if (!trailhead && steps.length === 0) return null;
  // Unified single-file shape (config / trailhead / trail), matching <case>.trail.yaml on disk.
  const lines = ['config:', `  target: ${target || '<target>'}`];
  if (trailhead) {
    lines.push('trailhead:');
    lines.push(`  step: Start from the ${trailhead} trailhead`);
  }
  lines.push('trail:');
  if (steps.length) steps.forEach((s) => lines.push(`- step: ${JSON.stringify(s)}`));
  else lines.push('# steps appear here as you demonstrate on the device');
  return { trailhead, stepCount: steps.length, yaml: lines.join('\n') };
}

// Which trail file to show, strongest signal first: an explicit trail_output declaration (the
// agent's contract for handing back a result - carries the summary, status, and touched files)
// beats an open_trail navigation to a folder id.
function detectComposedTrail(events) {
  let output = null;
  let opened = null;
  (events || []).forEach((e) => {
    if (e.kind === 'ui_command' && e.uiCommand && e.uiCommand.trailId) {
      if (e.uiCommand.action === 'trail_output') {
        const files = String((e.uiCommand.params && e.uiCommand.params.files) || '')
          .split(',').map((s) => s.trim()).filter(Boolean);
        output = {
          trailId: e.uiCommand.trailId,
          file: files.find((f) => /\.ya?ml$/i.test(f)) || 'blaze.yaml',
          summary: e.uiCommand.message || '',
          status: (e.uiCommand.params && e.uiCommand.params.status) || '',
        };
      } else if (e.uiCommand.action === 'open_trail') {
        opened = { trailId: e.uiCommand.trailId, file: 'blaze.yaml' };
      }
    }
  });
  return output || opened;
}

// ─── Transcript model ────────────────────────────────────────────────────────

// Fold the normalized event stream into chat items: user/assistant bubbles, tool cards with
// their results attached (paired by toolCallId), reasoning, UI commands, and a collapsed group
// for process/system noise.
function buildAgentTranscript(events) {
  const items = [];
  const toolCards = new Map();
  events.forEach((e) => {
    switch (e.kind) {
      case 'user_message':
        items.push({ type: 'user', e });
        break;
      case 'assistant_message': {
        const text = stripUiCommandLines(e.text);
        if (text) items.push({ type: 'assistant', e, text });
        break;
      }
      case 'reasoning':
        items.push({ type: 'reasoning', e });
        break;
      case 'human_action':
        // A tap/gesture the human performed on the mirror during record mode — a captured step,
        // grounded in the element it actually resolved to.
        items.push({ type: 'human_action', e });
        break;
      case 'tool_call': {
        const item = { type: 'tool', call: e, result: null };
        if (e.toolCallId) toolCards.set(e.toolCallId, item);
        items.push(item);
        break;
      }
      case 'tool_result': {
        const owner = e.toolCallId && toolCards.get(e.toolCallId);
        if (owner) owner.result = e;
        else items.push({ type: 'tool', call: null, result: e });
        break;
      }
      case 'ui_command':
        items.push({ type: 'ui_command', e });
        break;
      case 'final_result':
        items.push({ type: 'final', e });
        break;
      case 'error':
        items.push({ type: 'error', e });
        break;
      case 'permission_request':
      case 'permission_decision':
        // A pending tool-permission request (and its resolution) is surfaced as a live card above
        // the composer, driven off run.pendingPermissions - never as a transcript item (same intent
        // as ask_user, which is answered in the composer, not the stream). Drop it here so it also
        // stays out of the System disclosure the default branch would fold it into.
        break;
      default: {
        // A cancel must be visible in the conversation body, not folded into the System
        // disclosure - the evaluator's Stop click otherwise produces zero visible change.
        if (e.kind === 'lifecycle' && e.status === 'cancelled') {
          items.push({ type: 'cancelled', e });
          break;
        }
        const last = items[items.length - 1];
        if (last && last.type === 'system') last.events.push(e);
        else items.push({ type: 'system', events: [e] });
      }
    }
  });
  // A tool result reading "Execution cancelled" with a human demonstration landing around the
  // same moment is the mirror-tap collision: tapping the mirror takes control of the device and
  // interrupts the agent's in-flight action. Without this flag the card renders as a raw,
  // unexplained failure that looks like something the user broke. The flag is
  // set on the result event itself (stable across rebuilds) so AgentToolCard can explain it.
  const humanTimes = events.filter((e) => e.kind === 'human_action').map((e) => e.timeMs || 0);
  items.forEach((it) => {
    if (it.type !== 'tool' || !it.result) return;
    const txt = String(it.result.text || it.result.output || '');
    if (!/execution cancelled/i.test(txt)) return;
    const t = it.result.timeMs || 0;
    // The capture is grounded asynchronously (evidence settle), so the human_action event may be
    // logged shortly after the cancellation - match on a generous time window, not ordering.
    it.result.humanInterrupted = humanTimes.some((h) => Math.abs(h - t) < 20000);
  });
  // The vendor's final result usually repeats the last assistant message verbatim — keep the
  // usage/status line but don't render the same text twice.
  for (let i = items.length - 1; i >= 0; i--) {
    const it = items[i];
    if (it.type !== 'final') continue;
    for (let j = i - 1; j >= 0; j--) {
      if (items[j].type === 'assistant') {
        it.duplicateText = stripUiCommandLines(it.e.text) === items[j].text;
        break;
      }
      if (items[j].type === 'user' || items[j].type === 'final') break;
    }
  }
  return items;
}

function stripUiCommandLines(text) {
  return String(text || '').replace(/(?:^|\n)\s*TRAILRUNNER_UI\s*:?\s*\{.*}\s*(?=\n|$)/g, '').trim();
}

// Raw machine payloads (tool-descriptor JSON, multi-page view-hierarchy dumps) read as noise when
// painted as prose - they belong behind a collapsed disclosure, not in the body of the transcript.
// The length leg guards tool RESULTS only (multi-page non-JSON dumps); it must never apply to the
// agent's own prose bubbles - a thorough 1.5k-char answer collapsing into an "Output" chip buries
// the very conclusion the user is waiting for.
function looksLikePayload(text) {
  if (looksLikeMachinePayload(text)) return true;
  return String(text || '').trim().length > 1200;
}
// Structural checks only - safe for prose bubbles of any length.
function looksLikeMachinePayload(text) {
  const t = String(text || '').trim();
  if (!t) return false;
  if (t[0] === '{' || t[0] === '[') return true;
  if (/"className"\s*:/.test(t)) return true;
  // View-hierarchy dumps ("[r6] TextView Block…" line per node) often land just under the length
  // gate and aren't JSON, so they painted as prose - three or more node refs is unambiguously a
  // dump, never a chat sentence.
  return ((t.match(/\[r\d+\]/g) || []).length >= 3);
}

// Fold each maximal run of consecutive quiet plumbing tool calls (Bash, file reads, tool search -
// anything isProminentToolItem rejects) into one collapsed group, so a turn that greps its way
// through hundreds of internal calls can't drown the narration and device actions. Prominent,
// failed, or pinned cards break a run; so does a still-pending call (no result yet) - while a
// turn is running, that call IS the "what is it doing right now" signal.
function groupPlumbingRuns(items, pins) {
  const pinned = (it) => !!((pins && it.result && pins[it.result.seq]) || (pins && it.call && pins[it.call.seq]));
  const foldable = (it) => it.type === 'tool' && !!it.result && !isProminentToolItem(it) && !pinned(it);
  const out = [];
  let run = [];
  const flush = () => {
    if (run.length >= 3) out.push({ type: 'tool_group', items: run });
    else out.push(...run);
    run = [];
  };
  items.forEach((it) => {
    if (foldable(it)) run.push(it);
    else { flush(); out.push(it); }
  });
  flush();
  // Second pass: collapse consecutive IDENTICAL failures. One failed card carries the signal;
  // a degraded device retried N times paints N identical full-height red cards otherwise. The
  // first occurrence stays a full card; repeats fold behind a "×N repeats" disclosure.
  const failSig = (it) => {
    if (it.type !== 'tool' || !it.result || pinned(it)) return null;
    if (!/"is_error"\s*:\s*true/.test(String(it.result.output || ''))) return null;
    const name = (it.call && it.call.toolName) || it.result.toolName || 'tool';
    return name + '|' + String(it.result.text || it.result.output || '').slice(0, 200);
  };
  const merged = [];
  out.forEach((it) => {
    const sig = failSig(it);
    const prev = merged[merged.length - 1];
    if (sig && prev && prev.type === 'tool_fail_group' && prev.sig === sig) { prev.items.push(it); return; }
    if (sig && prev && failSig(prev) === sig) { merged[merged.length - 1] = { type: 'tool_fail_group', sig, items: [prev, it] }; return; }
    merged.push(it);
  });
  return merged;
}

// Display-only post-pass (kept OUT of buildAgentTranscript, which the device event log and share
// export also consume as a flat list): tuck each contiguous run of tool cards under the assistant
// bubble that precedes them, so the model's stated reason owns the tool noise it triggered instead
// of the two competing for the reader's attention as flat siblings. A tool-like item (tool /
// tool_group / tool_fail_group) attaches to the most recent assistant item; anything else (a user
// turn, reasoning, a UI command, a human tap) breaks the run, so tools with no assistant before
// them - or after a break - still render standalone.
function nestToolsUnderAssistant(items) {
  const isToolLike = (it) => it && (it.type === 'tool' || it.type === 'tool_group' || it.type === 'tool_fail_group');
  const out = [];
  let host = null;
  items.forEach((it) => {
    if (it.type === 'assistant') { host = { ...it, tools: [] }; out.push(host); }
    else if (isToolLike(it) && host) { host.tools.push(it); }
    else { host = null; out.push(it); }
  });
  return out;
}

// Minimal inline-markdown renderer for agent prose bubbles: **bold**, `code`, and [text](url).
// Agents write markdown, so painting the literal asterisks/backticks reads as a bug. Block
// markdown (lists, headings) intentionally stays plain text - the pre-wrap bubble already keeps
// line structure, and these are chat turns, not documents. React nodes only - no HTML injection.
function renderInline(text) {
  const s = decodeEntities(String(text || ''));
  const re = /(\*\*[^*\n]+\*\*|`[^`\n]+`|\[[^\]\n]+\]\(https?:\/\/[^)\s]+\))/g;
  const parts = s.split(re);
  if (parts.length === 1) return s;
  return parts.map((p, i) => {
    if (!p) return null;
    if (p.startsWith('**') && p.endsWith('**')) return <b key={i}>{p.slice(2, -2)}</b>;
    if (p.startsWith('`') && p.endsWith('`')) return <code key={i} className="tb-mono" style={{ fontSize: '0.92em', background: 'var(--bg-prominent)', border: '1px solid var(--tb-hairline)', borderRadius: 4, padding: '0 3px' }}>{p.slice(1, -1)}</code>;
    const m = p.match(/^\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)$/);
    if (m) return <a key={i} href={m[2]} target="_blank" rel="noreferrer">{m[1]}</a>;
    return p;
  });
}

// Human labels + button verbs for the ui_command stream chips - "navigate · Apply" reads as
// internals; "Open screen · Open" says what pressing the button will do.
const UI_COMMAND_META = {
  navigate: { label: 'Open screen', verb: 'Open' },
  open_session: { label: 'Open run', verb: 'Open run' },
  open_trail: { label: 'Open trail', verb: 'Open trail' },
  focus_external_agent: { label: 'Focus agent run', verb: 'Focus' },
  show_message: { label: 'Message', verb: 'Show' },
};
function uiCommandMeta(action) {
  return UI_COMMAND_META[action] || { label: String(action || 'UI command').replace(/_/g, ' '), verb: 'Apply' };
}

function AgentTranscriptItem({ item, running, pins, go }) {
  switch (item.type) {
    case 'user':
      return (
        <div style={{ alignSelf: 'flex-end', maxWidth: 'min(680px, 88%)', borderRadius: 12, border: '1px solid rgba(94,155,255,.3)', background: 'rgba(94,155,255,.09)', padding: '9px 13px' }}>
          <div data-selectable style={{ fontSize: 13, lineHeight: 1.55, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{item.e.text}</div>
        </div>
      );
    case 'assistant': {
      const bubble = looksLikeMachinePayload(item.text) ? (
        <div style={{ alignSelf: 'flex-start', maxWidth: 'min(760px, 94%)', width: '100%' }}>
          <Collapsible bare label="Output" text={item.text} />
        </div>
      ) : (
        <div style={{ alignSelf: 'flex-start', maxWidth: 'min(760px, 94%)', display: 'flex', gap: 9 }}>
          <Ico n="sparkles" s={14} c="var(--tb-ai)" style={{ flex: '0 0 auto', marginTop: 4 }} />
          <div data-selectable style={{ fontSize: 13, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', minWidth: 0 }}>{renderInline(item.text)}</div>
        </div>
      );
      // nestToolsUnderAssistant attaches the tools this turn triggered; render them as a quiet,
      // indented stack of collapsed dropdown rows beneath the bubble (aligned under the text, past
      // the sparkles glyph) so the reason and its tool calls read as one unit, not flat siblings.
      if (!item.tools || !item.tools.length) return bubble;
      return (
        <div style={{ alignSelf: 'stretch', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {bubble}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, marginLeft: 23 }}>
            {item.tools.map((t, i) => <AgentTranscriptItem key={i} item={t} running={running} pins={pins} go={go} />)}
          </div>
        </div>
      );
    }
    case 'reasoning':
      return (
        <div style={{ alignSelf: 'flex-start', maxWidth: 'min(720px, 92%)', width: '100%' }}>
          <Collapsible bare label="Thinking" text={item.e.text || ''} mono={false} />
        </div>
      );
    case 'tool':
      return <AgentToolCard call={item.call} result={item.result} running={running} pins={pins} />;
    case 'tool_group': {
      const counts = {};
      item.items.forEach((it) => { const n = agentToolMeta(it.call, it.result).name || 'tool'; counts[n] = (counts[n] || 0) + 1; });
      const breakdown = Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 3)
        .map(([n, c]) => (c > 1 ? `${n} ×${c}` : n)).join(' · ');
      return (
        <div style={{ alignSelf: 'stretch' }}>
          <Collapsible bare label={`${item.items.length} internal steps · ${breakdown}`}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {item.items.map((it, i) => <AgentToolCard key={i} call={it.call} result={it.result} running={running} pins={pins} />)}
            </div>
          </Collapsible>
        </div>
      );
    }
    case 'tool_fail_group': {
      const name = (item.items[0].call && item.items[0].call.toolName) || (item.items[0].result && item.items[0].result.toolName) || 'tool';
      return (
        <div style={{ alignSelf: 'stretch', display: 'flex', flexDirection: 'column', gap: 6 }}>
          <AgentToolCard call={item.items[0].call} result={item.items[0].result} running={running} pins={pins} />
          <Collapsible bare label={`${name} failed the same way ×${item.items.length - 1} more`}>
            <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
              {item.items.slice(1).map((it, i) => <AgentToolCard key={i} call={it.call} result={it.result} running={running} pins={pins} />)}
            </div>
          </Collapsible>
        </div>
      );
    }
    case 'human_action':
      return <AgentTapeCard e={item.e} />;
    case 'ui_command':
      if (item.e.uiCommand && item.e.uiCommand.action === 'trail_output') return <AgentTrailOutputCard cmd={item.e.uiCommand} go={go} />;
      // ask_user is answered in the composer (dense, multi-question), not rendered as a stream card.
      if (item.e.uiCommand && item.e.uiCommand.action === 'ask_user') return null;
      return (
        <div style={{ alignSelf: 'flex-start', display: 'flex', alignItems: 'center', gap: 9, border: '1px solid var(--tb-hairline)', borderLeft: '3px solid var(--tb-analytics)', borderRadius: 9, padding: '7px 11px', background: 'var(--bg-subtle)' }}>
          <Ico n="mouse-pointer-click" s={13} c="var(--tb-analytics)" />
          <span style={{ fontSize: 11.5, fontWeight: 600 }}>{uiCommandMeta(item.e.uiCommand && item.e.uiCommand.action).label}</span>
          {item.e.text && <span className="tb-sub" style={{ fontSize: 11.5 }}>{truncateMiddle(decodeEntities(item.e.text), 120)}</span>}
          {item.e.uiCommand && <Btn sm ico="mouse-pointer-click" onClick={() => TB.applyTrailRunnerUiCommand(item.e.uiCommand, go)}>{uiCommandMeta(item.e.uiCommand.action).verb}</Btn>}
        </div>
      );
    case 'final': {
      const usage = parseMaybeJson(item.e.usage);
      const usageLine = usage && typeof usage === 'object'
        ? ['input_tokens' in usage ? `in ${usage.input_tokens}` : null, 'output_tokens' in usage ? `out ${usage.output_tokens}` : null].filter(Boolean).join(' · ')
        : '';
      const text = stripUiCommandLines(item.e.text);
      return (
        <div style={{ alignSelf: 'stretch', display: 'flex', flexDirection: 'column', gap: 8 }}>
          {!item.duplicateText && text && (looksLikeMachinePayload(text) ? (
            <div style={{ alignSelf: 'flex-start', maxWidth: 'min(760px, 94%)', width: '100%' }}>
              <Collapsible bare label="Output" text={text} />
            </div>
          ) : (
            <div style={{ alignSelf: 'flex-start', maxWidth: 'min(760px, 94%)', display: 'flex', gap: 9 }}>
              <Ico n="sparkles" s={14} c="var(--tb-ai)" style={{ flex: '0 0 auto', marginTop: 4 }} />
              <div data-selectable style={{ fontSize: 13, lineHeight: 1.6, whiteSpace: 'pre-wrap', wordBreak: 'break-word', minWidth: 0 }}>{renderInline(text)}</div>
            </div>
          ))}
          <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-subtle)', fontSize: 11 }}>
            <Ico n="flag" s={12} c="var(--tb-pass)" /> turn finished{usageLine ? ' · ' + usageLine : ''}
          </div>
        </div>
      );
    }
    case 'error':
      return (
        <div role="alert" style={{ alignSelf: 'stretch', border: '1px solid rgba(255,90,90,.4)', borderRadius: 9, background: 'rgba(255,90,90,.07)', padding: '8px 12px', fontSize: 12.5, color: 'var(--tb-danger-text)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
          <b>{decodeEntities(item.e.title) || 'Error'}</b>{item.e.text ? ' - ' + truncateMiddle(decodeEntities(item.e.text), 600) : ''}
        </div>
      );
    case 'cancelled':
      // Neutral flag line (mirrors 'turn finished'), not a red error card: a user-initiated stop
      // is an outcome, not a failure.
      return (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, color: 'var(--text-subtle)', fontSize: 11 }}>
          <Ico n="octagon-x" s={12} c="var(--tb-warn, #e0a800)" /> {decodeEntities(item.e.title) || 'Stopped'}{item.e.text ? ' · ' + truncateMiddle(decodeEntities(item.e.text), 200) : ''}
        </div>
      );
    case 'system':
      return <AgentSystemGroup events={item.events} />;
    default:
      return null;
  }
}

function AgentSystemGroup({ events }) {
  return (
    <div style={{ alignSelf: 'stretch' }}>
      <Collapsible bare label={`System · ${events.length} event${events.length === 1 ? '' : 's'}`}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 5 }}>
          {events.map((e) => (
            <div key={e.id} style={{ display: 'flex', gap: 8, alignItems: 'baseline', fontSize: 11.5 }}>
              <span className="tb-mono" style={{ color: 'var(--text-subtle)', flex: '0 0 auto' }}>{e.kind}</span>
              <span data-selectable className="tb-mono" style={{ color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', minWidth: 0 }}>{truncateMiddle(decodeEntities((e.title || '') + (e.text ? ' - ' + e.text : '')), 400)}</span>
            </div>
          ))}
        </div>
      </Collapsible>
    </div>
  );
}

// ─── Tool cards ──────────────────────────────────────────────────────────────

// One collapsed dropdown row per tool decision. The trigger carries everything the reader needs at
// a glance - a state-colored icon, the tool name, prominence chips (trailblaze / skill / failed /
// paused), and a one-line summary - so a turn's tool calls stack quietly under the assistant bubble
// that triggered them instead of a wall of full-height cards. Expanding reveals the input, the
// result, and (for a device-moving call) the screenshot pinned when the call streamed in.
function AgentToolCard({ call, result, running, pins }) {
  const meta = agentToolMeta(call, result);
  const pending = !!call && !result && running;
  const pin = (result && pins[result.seq]) || (call && pins[call.seq]) || null;
  const [zoom, setZoom] = React.useState(false);
  const resultText = result ? (result.text || summarizeJson(parseMaybeJson(result.output))) : '';
  // A cancelled action isn't a failure - it was interrupted (usually by a demonstration tap on
  // the mirror, which takes control of the device). Explain it in amber instead of the raw
  // "Execution cancelled" that reads like a user-caused error.
  const cancelled = result && /execution cancelled/i.test(String(result.text || result.output || ''));
  const cancelledCopy = cancelled
    ? (result.humanInterrupted
      ? 'Paused - your tap on the device mirror took control here. The tap was captured and rides with your next reply; the agent picks up from there.'
      : 'Cancelled before it finished - the device was asked to do something else.')
    : '';
  // Payload-shaped results (descriptor JSON, hierarchy dumps) show as their own section below; only
  // prose results earn the lead result line inside the expanded row.
  const inlineResult = cancelled ? cancelledCopy : (looksLikePayload(resultText) ? '' : resultText);
  const failed = !cancelled && result && /"is_error"\s*:\s*true/.test(String(result.output || ''));
  // A device-moving Trailblaze call, a skill invocation, a failure, or a pinned screenshot is the
  // load-bearing signal - the trigger row keeps the accent so it still reads as prominent while
  // collapsed; everything else stays muted.
  const prominent = isProminentToolItem({ call, result }) || !!pin;
  const iconName = pending ? 'loader-circle' : failed ? 'circle-x' : cancelled ? 'hand' : meta.skill ? 'graduation-cap' : 'wrench';
  const iconColor = pending ? 'var(--tb-running)' : failed ? 'var(--tb-fail)' : cancelled ? 'var(--tb-amber)' : (meta.tb || meta.skill) ? 'var(--tb-ai)' : 'var(--text-subtle)';
  const summary = meta.summary ? (meta.summary.length > 96 ? meta.summary.slice(0, 95) + '…' : meta.summary) : '';
  const label = (
    <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7, minWidth: 0, maxWidth: '100%' }}>
      <Ico n={iconName} s={12.5} c={iconColor} spin={pending} style={{ flex: '0 0 auto' }} />
      <span className="tb-mono" style={{ fontSize: 12, fontWeight: 700, color: prominent ? 'var(--text-standard)' : 'var(--text-subtle-variant)', flex: '0 0 auto' }}>{meta.name}</span>
      {cancelled && <Chip tone="amber">{result.humanInterrupted ? 'paused by your tap' : 'cancelled'}</Chip>}
      {failed && <Chip tone="red">failed</Chip>}
      {meta.tb && <Chip tone="blue">trailblaze</Chip>}
      {meta.skill && <Chip tone="blue">skill</Chip>}
      {summary && <span className="tb-sub" data-selectable style={{ fontSize: 11.5, fontWeight: 400, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', maxWidth: 520 }}>{summary}</span>}
    </span>
  );
  const section = (title, body, mono = true) => (
    <div>
      <div className="tb-eyebrow" style={{ fontSize: 9, marginBottom: 3 }}>{title}</div>
      <pre className={mono ? 'tb-mono' : ''} data-selectable style={{ margin: 0, fontSize: 11.5, lineHeight: 1.5, color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', wordBreak: 'break-word', maxHeight: 340, overflow: 'auto' }}>{body}</pre>
    </div>
  );
  const hasInput = call && call.input && call.input !== '{}';
  return (
    <div style={{ alignSelf: 'stretch', maxWidth: 860 }}>
      <Collapsible bare label={label}>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          {pin && (
            <img src={pin} alt="Device at this step" onClick={() => setZoom(true)}
              style={{ width: 96, borderRadius: 6, border: '1px solid var(--tb-hairline-stronger)', cursor: 'zoom-in', alignSelf: 'flex-start', background: '#000' }} />
          )}
          {inlineResult && (
            <div data-selectable style={{ minWidth: 0, fontSize: 12, lineHeight: 1.5, color: 'var(--text-subtle-variant)', whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>{truncateMiddle(inlineResult, 700)}</div>
          )}
          {hasInput && section('Input', prettyJson(call.input))}
          {result && result.output && section('Result', prettyJson(result.output))}
          {result && !result.output && resultText && !inlineResult && section('Result', resultText)}
          {!hasInput && !result && !pin && !inlineResult && (
            <div className="tb-sub" style={{ fontSize: 11 }}>{pending ? 'Running…' : 'No input or result recorded.'}</div>
          )}
        </div>
      </Collapsible>
      {zoom && pin && (
        <div onClick={() => setZoom(false)} style={{ position: 'fixed', inset: 0, zIndex: 9999, background: 'rgba(0,0,0,.85)', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'zoom-out' }}>
          <img src={pin} style={{ maxWidth: '92vw', maxHeight: '92vh', borderRadius: 10, border: '1px solid var(--tb-hairline-stronger)' }} alt="Device screenshot (full)" />
        </div>
      )}
    </div>
  );
}

// A tool call earns prominence when it drove the device or reached for packaged expertise
// (Trailblaze MCP / Skill), or failed. Shared by the transcript's card-vs-quiet-row choice and
// the device panel's event-log filter, so "what counts as signal" can't drift between the two
// (a pinned screenshot additionally promotes a card in the transcript only).
function isProminentToolItem({ call, result }) {
  const meta = agentToolMeta(call, result);
  const failed = result && /"is_error"\s*:\s*true/.test(String(result.output || ''));
  return meta.tb || meta.skill || !!failed;
}

function agentToolMeta(call, result) {
  const rawName = (call && call.toolName) || (result && result.toolName) || 'tool';
  const tb = String(rawName).startsWith('mcp__trailblaze__');
  // A Skill invocation is the agent reaching for packaged expertise - name the skill itself, and
  // let the card render it prominently (the Skills panel cross-references what got used).
  const skill = String(rawName) === 'Skill';
  let name = tb ? rawName.slice('mcp__trailblaze__'.length) : rawName;
  let summary = '';
  const input = call ? parseMaybeJson(call.input) : null;
  if (input && typeof input === 'object' && !Array.isArray(input)) {
    if (skill) {
      const skillName = input.skill || input.name || '';
      if (skillName) name = String(skillName);
      summary = typeof input.args === 'string' ? input.args : '';
      return { tb, skill, name, summary: String(summary).slice(0, 160) };
    }
    const parts = [];
    if (typeof input.action === 'string') parts.push(input.action);
    for (const key of ['prompt', 'objective', 'command', 'description', 'text', 'selector', 'query', 'trailPath', 'path', 'file_path']) {
      const v = input[key];
      if (typeof v === 'string' && v.trim()) { parts.push(v.trim()); break; }
    }
    summary = parts.join(' · ');
  }
  if (!summary && call && call.text) summary = call.text;
  // Absolute home paths in shell/file summaries are the least informative characters on the card
  // and the first ones the eye hits - collapse the prefix so the meaningful tail survives the
  // 160-char cap.
  summary = String(summary || '').replace(/\/(?:Users|home)\/[^/\s]+\//g, '~/');
  return { tb, skill, name, summary: summary ? summary.slice(0, 160) : '' };
}

function prettyJson(value) {
  const parsed = parseMaybeJson(value);
  return typeof parsed === 'string' ? parsed : JSON.stringify(parsed, null, 2);
}

// ─── Demonstration tape ──────────────────────────────────────────────────────

// A human tap the recorder captured, grounded in the element it resolved to. The card leads with
// what you did (the label), then the durability of the selector that was chosen — the one piece of
// judgment that decides whether the recorded step survives an app change. Green throughout: this is
// the human actor's lane, distinct from the agent's blue tool cards (similarity by actor, ch06).
function AgentTapeCard({ e, compact, index, onDelete, deleting }) {
  const out = parseMaybeJson(e.output) || {};
  const el = (out && out.element) || {};
  const options = Array.isArray(out && out.options) ? out.options : [];
  const meta = tapSelectorMeta((options[0] && (options[0].label || options[0].strategy)) || '');
  const alt = options.length > 1 ? options.length - 1 : 0;
  const identity = decodeEntities(el.label) || el.resourceId || el.type || '';
  // Numbered = a captured trail step (the tape lists): leads with a STEP chip, same vocabulary as
  // the trail-details StepRow. Un-numbered = the chat transcript's human-action card: keeps its
  // hand icon and "you" chip so the transcript still reads as the human's lane.
  const numbered = typeof index === 'number';
  return (
    <div className="tb-card" style={{ alignSelf: 'stretch', maxWidth: compact ? 'none' : 860, overflow: 'hidden' }}>
      <div style={{ padding: '10px 12px' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
          {numbered
            ? <Chip tone="purple">STEP {index + 1}</Chip>
            : <Ico n="hand-pointer" s={14} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />}
          <span style={{ fontSize: 13.5, fontWeight: 600, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{decodeEntities(e.title) || 'Tap'}</span>
          {!compact && !numbered && <Chip tone="green">you</Chip>}
          {/* Recording-only affordance (the demo tape passes onDelete): remove a mistaken step. */}
          {onDelete && (
            <Btn sm ico={deleting ? 'loader-2' : 'trash-2'} spin={deleting} disabled={deleting}
              onClick={onDelete} title="Delete this step from the recording" />
          )}
        </div>
        {/* Selector-durability chips only when a selector actually resolved: a swipe or key press
            has no selector, and a "Layout / type" verdict on it is noise. */}
        {(identity || options.length > 0) && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 6, flexWrap: 'wrap' }}>
            {identity && <span className="tb-sub" data-selectable style={{ fontSize: 11.5, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{identity}</span>}
            <span style={{ flex: 1 }} />
            {options.length > 0 && (
              <>
                <span className="tb-sub" style={{ fontSize: 11.5 }}>{meta.name}</span>
                <Chip tone={meta.tone}>{meta.tier}</Chip>
              </>
            )}
          </div>
        )}
        {alt > 0 && <div className="tb-sub" style={{ fontSize: 10.5, marginTop: 4 }}>{alt} other match{alt === 1 ? '' : 'es'} available</div>}
      </div>
    </div>
  );
}

// The agent's declared result (trail_output): a prominent card, not a throwaway chip, because this
// is the point of the whole conversation. Shows what changed, which trail, its files, and whether
// the agent considers it ready - with one click to open it in Trails.
function AgentTrailOutputCard({ cmd, go }) {
  const status = String((cmd.params && cmd.params.status) || 'draft').toLowerCase();
  const ready = status === 'ready';
  const files = String((cmd.params && cmd.params.files) || '').split(',').map((s) => s.trim()).filter(Boolean);
  const slug = String(cmd.trailId || '').replace(/^\d+\//, '');
  return (
    <div style={{ alignSelf: 'stretch', maxWidth: 760, border: '1px solid ' + (ready ? 'rgba(0,224,19,.4)' : 'var(--tb-hairline-strong)'), borderLeft: '3px solid ' + (ready ? 'var(--tb-pass)' : 'var(--tb-ai)'), borderRadius: 11, background: 'var(--bg-subtle)', padding: '11px 13px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
        <Ico n={ready ? 'file-check-2' : 'file-pen-line'} s={15} c={ready ? 'var(--tb-pass)' : 'var(--tb-ai)'} />
        <span style={{ fontSize: 13, fontWeight: 700 }}>{ready ? 'Trail ready' : 'Trail updated'}</span>
        <Chip tone={ready ? 'green' : 'blue'}>{status}</Chip>
        <span style={{ flex: 1 }} />
        {cmd.trailId && <Btn sm ico="arrow-up-right" onClick={() => go && go('trails', { sel: cmd.trailId })} title="Open in Trails">Open</Btn>}
      </div>
      {cmd.message && <div data-selectable style={{ fontSize: 13, lineHeight: 1.5, marginTop: 7 }}>{cmd.message}</div>}
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 8, flexWrap: 'wrap' }}>
        <span className="tb-mono tb-sub" data-selectable style={{ fontSize: 11 }}>{slug || cmd.trailId}</span>
        {files.map((f) => (
          <span key={f} className="tb-mono" style={{ fontSize: 10.5, color: 'var(--text-subtle-variant)', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 6, padding: '1px 7px' }}>{f}</span>
        ))}
      </div>
    </div>
  );
}

// The selector option the author's pending step currently uses (tool 0 matched back against the
// resolve options), echoed into the confirmed step's HUMAN_ACTION event so the recorded step
// carries its selector-tier metadata. Null when tool 0 was hand-edited past every option.
function chosenRecordOption(pending) {
  const t0 = parseRecordStepTools(pending.yaml || '')[0];
  if (!t0) return null;
  return (pending.options || []).find((o) => {
    const ot = parseRecordStepTools(o.yaml)[0];
    return ot && ot.name === t0.name && JSON.stringify(ot.args || {}) === JSON.stringify(t0.args || {});
  }) || null;
}

// Plain-language reliability of a selector strategy, mirrored from the recorder's own tiering so
// the tape reads the same as the Record screen. Kept compact and local — the recorder's selectorMeta
// isn't a shared global.
function tapSelectorMeta(strategy) {
  const s = String(strategy || '').toLowerCase();
  const mk = (name, tier, tone) => ({ name, tier, tone });
  if (!s) return mk('Match', 'Okay', 'amber');
  if (s.includes('coordinate') || s.includes('point')) return mk('Screen coordinates', 'Fragile', 'red');
  if (s.includes('index')) return mk('Position number', 'Fragile', 'red');
  if (s.includes('resource') || s.includes('test tag') || s.includes('testtag') || s.includes('unique id') || s.includes('uniqueid') || s.includes('testid') || s.includes('css'))
    return mk('Accessibility ID', 'Rock solid', 'green');
  if (s.includes('text') || s.includes('label') || s.includes('accessibility') || s.includes('content desc') || s.includes('hint') || s.includes('aria'))
    return mk('Its label', 'Stable', 'green');
  return mk('Layout / type', 'Okay', 'amber');
}

// ─── Live device stage ───────────────────────────────────────────────────────

// Connect/disconnect ordering guard. Effect cleanup fires recordDisconnect without awaiting it,
// and the replacement effect (phase remount, Reconnect click) fires recordConnect right after.
// Server connections are keyed by device and disconnect closes unconditionally, so when the
// disconnect lands second it silently closes the connection the new effect just opened - the
// mirror then polls a dead stream forever ("Waiting for the first frame"). Chaining every
// connect/disconnect through one queue preserves issue order on the wire.
let mirrorOpChain = Promise.resolve();
function queueMirrorOp(fn) {
  const run = mirrorOpChain.then(fn, fn);
  mirrorOpChain = run.then(() => {}, () => {});
  return run;
}

// One live device connection, owned in a hook at the conversation level so only ever one
// connection contends for the device across the session's whole life. Reuses the Interact
// screen's connect/screen/gesture primitives.
function useAgentDeviceMirror(active) {
  const devices = TB.useDevices();
  const deviceList = (devices.data || []).filter((d) => d.connected);
  // Sticky, sharing a key with the new-conversation session brief: the device picked before the
  // session starts is the one the mirror connects to once it exists.
  const [selectedId, setSelectedId] = useStickyState('tb-agent-device', null);
  const [conn, setConn] = React.useState(null);
  const [connecting, setConnecting] = React.useState(false);
  const [frame, setFrame] = React.useState(null);
  const [err, setErr] = React.useState(null);
  const [tapBusy, setTapBusy] = React.useState(false);
  const connDeviceRef = React.useRef(null);
  const [rawById, setRawById] = React.useState({});
  // Bumping the nonce tears the connect effect down and back up: a user-facing "Reconnect" for a
  // wedged capture channel (frozen frames / no-op taps) that previously had no recovery control -
  // the device chip is only a picker, so the user could only wait and hope.
  const [reconnectNonce, setReconnectNonce] = React.useState(0);
  const reconnect = () => { setErr(null); setConn(null); setFrame(null); setReconnectNonce((n) => n + 1); };

  // A click maps to device coordinates: the <img> hugs the rendered frame, so its box IS the
  // device viewport. Null when the connection isn't ready or the box has no size.
  const toDeviceCoords = (e) => {
    if (!conn || !conn.w || !conn.h) return null;
    const rect = e.currentTarget.getBoundingClientRect();
    if (rect.width <= 0 || rect.height <= 0) return null;
    return {
      x: Math.max(0, Math.min(conn.w - 1, Math.round(((e.clientX - rect.left) / rect.width) * conn.w))),
      y: Math.max(0, Math.min(conn.h - 1, Math.round(((e.clientY - rect.top) / rect.height) * conn.h))),
    };
  };

  // Send any gesture payload to the connected device (the id is injected here so callers never
  // hold a stale device ref). Resolve-only proposals, confirmed steps, and drive taps all funnel
  // through this one sender.
  const sendGesture = async (payload) => {
    const id = connDeviceRef.current;
    if (!id) return { ok: false, error: 'No device connection.' };
    return await TB.recordGesture(id, payload);
  };

  // Dispatch a real tap at the click point. Passing a runId echoes the tap into that conversation
  // as a captured step; without it, it's just driving the device.
  const tapAt = async (e, runId) => {
    if (tapBusy) return;
    const p = toDeviceCoords(e);
    if (!p) return;
    setTapBusy(true);
    setErr(null);
    const r = await sendGesture(runId ? { type: 'tap', x: p.x, y: p.y, runId } : { type: 'tap', x: p.x, y: p.y });
    setTapBusy(false);
    if (!r.ok) setErr(r.error || 'The tap could not be dispatched.');
  };

  // Dispatch a drag as a swipe (down -> up device coords already resolved by the caller). A runId
  // echoes it into that conversation as a captured step; null just drives the device.
  const swipeAt = async (down, up, durationMs, runId) => {
    if (tapBusy || !down || !up) return;
    setTapBusy(true);
    setErr(null);
    const g = { type: 'swipe', startX: down.x, startY: down.y, endX: up.x, endY: up.y, durationMs };
    const r = await sendGesture(runId ? { ...g, runId } : g);
    setTapBusy(false);
    if (!r.ok) setErr(r.error || 'The swipe could not be dispatched.');
  };

  // Dispatch a long-press at a resolved point. Same runId semantics as tapAt / swipeAt.
  const longPressAt = async (p, runId) => {
    if (tapBusy || !p) return;
    setTapBusy(true);
    setErr(null);
    const g = { type: 'longPress', x: p.x, y: p.y };
    const r = await sendGesture(runId ? { ...g, runId } : g);
    setTapBusy(false);
    if (!r.ok) setErr(r.error || 'The long press could not be dispatched.');
  };

  React.useEffect(() => {
    let alive = true;
    window.TbRpc.getConnectedDevices().then((raw) => {
      if (!alive) return;
      const map = {};
      (raw?.devices || []).forEach((d) => {
        const id = d.instanceId || d.trailblazeDeviceId?.instanceId;
        if (id) map[id] = d.trailblazeDeviceId || { instanceId: id, trailblazeDevicePlatform: (d.platform || '').toUpperCase() };
      });
      setRawById(map);
    });
    return () => { alive = false; };
  }, [devices.data]);

  React.useEffect(() => {
    if ((!selectedId || !deviceList.find((d) => d.id === selectedId)) && deviceList.length) setSelectedId(deviceList[0].id);
  }, [devices.data]);

  const tbIdFor = (sid) => {
    if (!sid) return null;
    if (rawById[sid]) return rawById[sid];
    const d = deviceList.find((x) => x.id === sid);
    return d ? { instanceId: d.id, trailblazeDevicePlatform: (d.platform || '').toUpperCase() } : null;
  };

  // Hold a live mirror connection for the selected device while the mirror is shown anywhere.
  React.useEffect(() => {
    if (!active) return;
    const tbId = tbIdFor(selectedId);
    if (!tbId) return;
    let stale = false;
    setErr(null);
    setConnecting(true);
    queueMirrorOp(() => TB.recordConnect(tbId)).then((r) => {
      if (stale) { queueMirrorOp(() => TB.recordDisconnect(tbId)); return; }
      setConnecting(false);
      if (!r.ok) { setErr(r.error || 'Could not connect to the device.'); return; }
      connDeviceRef.current = tbId;
      setConn({ w: r.deviceWidth, h: r.deviceHeight });
    }).catch((e) => {
      if (stale) return;
      setConnecting(false);
      setErr((e && e.message) || 'Could not connect to the device.');
    });
    return () => {
      stale = true;
      if (connDeviceRef.current === tbId) { connDeviceRef.current = null; setConn(null); setFrame(null); }
      queueMirrorOp(() => TB.recordDisconnect(tbId));
    };
  }, [active, selectedId, rawById[selectedId] ? 1 : 0, reconnectNonce]);

  // Self-scheduling frame poll (same shape as the Interact screen): never lets requests pile up.
  React.useEffect(() => {
    if (!conn || !active) return;
    let stop = false;
    const sleep = (ms) => new Promise((res) => setTimeout(res, ms));
    const loop = async () => {
      while (!stop) {
        const id = connDeviceRef.current;
        if (!id) { await sleep(200); continue; }
        try {
          const r = await TB.recordScreen(id);
          if (!stop && r.ok && r.screenshotBase64) setFrame(`data:${r.mime || 'image/png'};base64,${r.screenshotBase64}`);
          // Self-heal: the daemon can lose the connection out from under us (daemon restart,
          // an external disconnect). Quietly re-open it instead of polling a dead stream forever.
          if (!stop && r && !r.ok && /not connected/i.test(r.error || '')) {
            await queueMirrorOp(() => TB.recordConnect(id));
          }
          // Back off on failure: a degraded device fails instantly, and spinning at full request
          // rate churns the embedded webview's renderer hard enough to kill the page.
          if (!r || !r.ok) await sleep(250);
        } catch (_) { await sleep(250); }
      }
    };
    loop();
    return () => { stop = true; };
  }, [conn, active]);

  // The structured device id of the LIVE connection (null until connected) - for callers that
  // address the device outside the gesture channel (tree inspector, trailhead replay).
  const tbId = () => connDeviceRef.current;

  return { deviceList, selectedId, setSelectedId, conn, connecting, frame, err, tapBusy, tapAt, swipeAt, longPressAt, toDeviceCoords, sendGesture, reconnect, tbId };
}

// The mirror's pre-frame states, told honestly. The Android connect brings up on-device
// instrumentation and can legitimately take up to a minute on first use; once connected, the
// first frame follows within a poll or two. The old single "Connecting to the device mirror…"
// string covered both and read as a hang. Errors and the no-device case render elsewhere.
function MirrorPlaceholder({ mirror, compact }) {
  const { conn, err, deviceList } = mirror;
  if (err || !deviceList.length) return null;
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 8, padding: compact ? '20px 14px' : '32px 16px' }}>
      <Ico n="loader-circle" s={compact ? 14 : 18} c="var(--text-subtle)" spin />
      <div className="tb-sub" style={{ fontSize: compact ? 11.5 : 12, textAlign: 'center' }}>
        {conn ? 'Connected. Waiting for the first frame…' : 'Connecting to the device…'}
      </div>
      {!conn && (
        <div className="tb-sub" style={{ fontSize: compact ? 10 : 10.5, textAlign: 'center', maxWidth: 300, lineHeight: 1.5, opacity: .8 }}>
          First connect can take up to a minute while the on-device recorder starts.
        </div>
      )}
    </div>
  );
}

// A device error that says what happened and what to do about it, instead of a bare status code.
// The reason now comes through from the server (the gesture route reports the real failure), so this
// shows that plus a recovery hint.
function AgentDeviceError({ err, onReconnect }) {
  if (!err) return null;
  return (
    <div role="alert" style={{ display: 'flex', gap: 7, alignItems: 'flex-start', marginTop: 8, padding: '7px 9px', borderRadius: 8, border: '1px solid rgba(255,90,90,.35)', background: 'rgba(255,90,90,.06)' }}>
      <Ico n="triangle-alert" s={13} c="var(--tb-danger-text)" style={{ flex: '0 0 auto', marginTop: 1 }} />
      <div style={{ minWidth: 0 }}>
        <div data-selectable style={{ fontSize: 11.5, color: 'var(--tb-danger-text)', fontWeight: 600, wordBreak: 'break-word', lineHeight: 1.4 }}>{err}</div>
        <div className="tb-sub" style={{ fontSize: 10.5, marginTop: 2, lineHeight: 1.45 }}>The device or its recording connection may have dropped.{onReconnect ? '' : ' Reselect the device above, or try the action again.'}</div>
        {onReconnect && <Btn sm ico="refresh-cw" onClick={onReconnect} style={{ marginTop: 6 }}>Reconnect</Btn>}
      </div>
    </div>
  );
}

// The details-panel mirror (normal mode): the device up top, then a focused event log of the
// actions that actually touch the trail — the agent's tool calls and your taps — in order.
// A signal-only feed of what has touched the trail, below the device: the agent's tool calls and
// your taps, in the order they happened — no reasoning, prose, or process noise. This is the "what
// is the agent actually doing to the device" view the chat transcript can't give at a glance.
// It also carries the turn boundary: a divider separates what the agent has already seen from what
// happened since your last message, and a footer says the new taps ride with your next reply -
// without it the demonstrate -> reply loop is invisible.
function AgentDeviceEventLog({ events }) {
  const { rows, dividerAt, pendingTaps } = React.useMemo(() => {
    const evs = events || [];
    let lastUserSeq = -1;
    evs.forEach((e) => { if (e.kind === 'user_message') lastUserSeq = e.seq; });
    // Prominent tool calls + your taps only. Plumbing calls (tool search, file reads, shell)
    // stay in the chat transcript's quiet rows; here they'd flood the at-a-glance device feed.
    const rows = buildAgentTranscript(evs).filter((it) =>
      it.type === 'human_action' || (it.type === 'tool' && isProminentToolItem(it)));
    const rowSeq = (it) => (it.type === 'human_action' ? it.e.seq : (it.call && it.call.seq) ?? (it.result && it.result.seq) ?? -1);
    // The turn boundary: index of the first row the agent has NOT seen yet. No divider when
    // everything is new (nothing to separate) or everything is delivered.
    const firstNew = rows.findIndex((it) => rowSeq(it) > lastUserSeq);
    const dividerAt = firstNew > 0 ? firstNew : -1;
    const pendingTaps = evs.filter((e) => e.kind === 'human_action' && e.seq > lastUserSeq).length;
    return { rows, dividerAt, pendingTaps };
  }, [events]);
  const scrollRef = React.useRef(null);
  // Pin to the newest row, and KEEP it pinned when the log resizes: on first render the log is
  // tall (the mirror frame hasn't loaded), everything fits, and a one-shot scroll clamps to 0 -
  // then the frame arrives, the log shrinks, and the newest rows are below the fold. Scrolling up
  // releases the pin (so inspecting an old row isn't yanked away); scrolling back down restores it.
  const stickRef = React.useRef(true);
  React.useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    const pin = () => { if (stickRef.current) el.scrollTop = el.scrollHeight; };
    pin();
    const ro = new ResizeObserver(pin);
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  React.useEffect(() => { const el = scrollRef.current; if (el && stickRef.current) el.scrollTop = el.scrollHeight; }, [rows.length]);
  return (
    <div style={{ flex: 1, minHeight: 80, display: 'flex', flexDirection: 'column', borderTop: '1px solid var(--tb-hairline)', marginTop: 4, paddingTop: 8 }}>
      <div className="tb-eyebrow" style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '0 2px 6px' }}>
        <Ico n="list-video" s={12} c="var(--text-subtle)" /> Event log
      </div>
      <div ref={scrollRef}
        onScroll={(e) => { const el = e.currentTarget; stickRef.current = el.scrollTop + el.clientHeight >= el.scrollHeight - 12; }}
        style={{ flex: 1, minHeight: 0, overflowY: 'auto', display: 'flex', flexDirection: 'column', gap: 3 }}>
        {rows.length === 0
          ? <div className="tb-sub" style={{ fontSize: 11, padding: '10px 2px', lineHeight: 1.5 }}>Tool calls and taps show here as the agent works and you demonstrate.</div>
          : rows.map((it, i) => (
            <React.Fragment key={i}>
              {i === dividerAt && (
                <div style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '5px 2px 2px' }}>
                  <span style={{ flex: 1, height: 1, background: 'var(--tb-hairline)' }} />
                  <span className="tb-sub" style={{ flex: '0 0 auto', fontSize: 9.5, fontWeight: 700, letterSpacing: '.06em', textTransform: 'uppercase' }}>since your last message</span>
                  <span style={{ flex: 1, height: 1, background: 'var(--tb-hairline)' }} />
                </div>
              )}
              <AgentEventLogRow item={it} />
            </React.Fragment>
          ))}
      </div>
      {pendingTaps > 0 && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 6, padding: '6px 9px', borderRadius: 8, background: 'rgba(0,224,19,.06)', border: '1px solid rgba(0,224,19,.18)' }}>
          <Ico n="send" s={12} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />
          <span style={{ fontSize: 11, lineHeight: 1.45, color: 'var(--text-subtle-variant)' }}>
            <b style={{ color: 'var(--text-standard)' }}>{pendingTaps} captured action{pendingTaps === 1 ? '' : 's'}</b> - the agent sees {pendingTaps === 1 ? 'it' : 'them'}, with evidence, when you next reply.
          </span>
        </div>
      )}
    </div>
  );
}

// One dense line per device-affecting event. A tap is green (your lane); an agent tool call carries
// the Trailblaze tint when it drove the device, subtle otherwise; a failed call goes red.
function AgentEventLogRow({ item }) {
  if (item.type === 'human_action') {
    const out = parseMaybeJson(item.e.output) || {};
    const el = (out && out.element) || {};
    const identity = decodeEntities(el.label) || el.resourceId || el.type || '';
    const ev = out && out.evidence;
    const evUrl = (n) => `/trailrunner/api/external-agent/${encodeURIComponent(item.e.runId)}/evidence/${encodeURIComponent(n)}`;
    const thumb = (side, name) => (
      <a href={evUrl(name)} target="_blank" rel="noopener" title={`Screen ${side} the action - open full size`} style={{ display: 'block', flex: '0 0 auto' }}>
        <img src={evUrl(name)} alt={`Screen ${side} the action`}
          onError={(e) => { const a = e.currentTarget.closest('a'); if (a) a.style.display = 'none'; }}
          style={{ height: 64, borderRadius: 4, border: '1px solid var(--tb-hairline-stronger)', background: '#000', display: 'block' }} />
      </a>
    );
    return (
      <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '3px 4px', minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 7, fontSize: 11.5, minWidth: 0 }}>
          <Ico n="hand-pointer" s={12} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />
          <span style={{ fontWeight: 600, flex: '0 0 auto' }}>{decodeEntities(item.e.title) || 'Tap'}</span>
          {identity && <span className="tb-sub" style={{ minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{identity}</span>}
          {ev && ev.screenChanged === false && (
            <span title="The view hierarchy did not change - this action may not have landed"
              style={{ flex: '0 0 auto', fontSize: 10, fontWeight: 700, letterSpacing: '.02em', color: 'var(--tb-amber)' }}>no change</span>
          )}
        </div>
        {ev && (ev.before?.screenshot || ev.after?.screenshot) && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, paddingLeft: 19 }}>
            {ev.before?.screenshot && thumb('before', ev.before.screenshot)}
            {ev.before?.screenshot && ev.after?.screenshot && <Ico n="arrow-right" s={12} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />}
            {ev.after?.screenshot && thumb('after', ev.after.screenshot)}
          </div>
        )}
      </div>
    );
  }
  const meta = agentToolMeta(item.call, item.result);
  const failed = item.result && /"is_error"\s*:\s*true/.test(String(item.result.output || ''));
  const pending = !!item.call && !item.result;
  const color = failed ? 'var(--tb-fail)' : meta.tb ? 'var(--tb-ai)' : 'var(--text-subtle)';
  return (
    // Summaries WRAP (word-break for unbroken selector/command strings) instead of clipping: the
    // log is the record of what happened, and an ellipsis hid exactly the part that varies.
    <div style={{ display: 'flex', alignItems: 'flex-start', gap: 7, padding: '3px 4px', fontSize: 11.5, minWidth: 0 }}>
      <Ico n={pending ? 'loader-circle' : failed ? 'circle-x' : 'wrench'} s={12} c={color} spin={pending} style={{ flex: '0 0 auto', marginTop: 2 }} />
      <span className="tb-mono" style={{ fontWeight: 600, color, flex: '0 0 auto', maxWidth: 130, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{meta.name}</span>
      {meta.summary && <span className="tb-sub" title={shortPathSummary(meta.summary) === meta.summary ? undefined : meta.summary} style={{ minWidth: 0, whiteSpace: 'normal', wordBreak: 'break-word', lineHeight: 1.45 }}>{shortPathSummary(meta.summary)}</span>}
    </div>
  );
}

// The log is a narrow column: an absolute path pushes the one discriminating part (the file name)
// past the ellipsis. Collapse path-shaped summaries to their last two segments; leave prose alone.
// The full value stays in the row's title attribute (and in the transcript's tool card).
function shortPathSummary(s) {
  const str = String(s || '');
  if (!/^[/~]/.test(str) || str.includes(' ') || str.length <= 42) return str;
  const parts = str.split('/').filter(Boolean);
  if (parts.length <= 2) return str;
  return '…/' + parts.slice(-2).join('/');
}

// The center stage: the device, dominant. It's the largest element, centered, and the only
// saturated/live region on screen (dominance, ch06) - the device is the subject and everything
// else is scaffolding. The chip names the stage's standing state: taps here are recorded as steps.
function AgentRecordStage({ mirror, runId, hasSteps, onStageClick, onStageSwipe, stepBusy, inspect, mode, readOnly }) {
  const { deviceList, selectedId, setSelectedId, frame, err, tapBusy } = mirror;
  const busy = tapBusy || stepBusy;
  // Drive mode: taps go straight to the device and record NOTHING - for navigating to a starting
  // state or backing out of a wrong screen. Two ways in: hold cmd/ctrl for a momentary drive, or
  // flip the Drive latch for a sustained one. Both are announced identically (chip + blue frame),
  // so the author always knows which lane a tap will land in.
  const [driveLatch, setDriveLatch] = React.useState(false);
  const [cmdHeld, setCmdHeld] = React.useState(false);
  React.useEffect(() => {
    const down = (e) => { if (e.key === 'Meta' || e.key === 'Control') setCmdHeld(true); };
    const up = (e) => { if (e.key === 'Meta' || e.key === 'Control') setCmdHeld(false); };
    // cmd-tab away eats the keyup; blur is the reliable reset.
    const clear = () => setCmdHeld(false);
    window.addEventListener('keydown', down);
    window.addEventListener('keyup', up);
    window.addEventListener('blur', clear);
    return () => { window.removeEventListener('keydown', down); window.removeEventListener('keyup', up); window.removeEventListener('blur', clear); };
  }, []);
  const driving = driveLatch || cmdHeld;
  // Recording-lane copy differs by capture mode: the confirm-gate Create session PROPOSES a step
  // (default), the demonstrate-first Position phase NAVIGATES to the starting screen (setup), and
  // its Demonstrate phase RECORDS every tap immediately. Only the copy changes - the routing is
  // identical (a plain tap carries runId; Drive / ⌘ still navigates without recording).
  const copy = mode === 'position'
    ? { badge: 'Setup - taps navigate', badgeTitle: 'Taps navigate to the starting screen and are captured as setup', alt: 'Live device screen (tap to navigate to the starting screen)', title: 'Tap the device to navigate to the starting screen' }
    : mode === 'record'
    ? { badge: 'Recording - taps become steps', badgeTitle: 'Every tap on the device is recorded as a step', alt: 'Live device screen (tap to record a step)', title: 'Tap the device - every tap is recorded as a step' }
    : { badge: 'Recording taps', badgeTitle: 'Taps on the device are captured as steps', alt: 'Live device screen (click to propose a step)', title: 'Tap the device to propose a step - you confirm it before it runs' };
  const stageClick = (e) => {
    // Route on the click's own modifier (not the tracked key state): the event is the truth of
    // what the author held at the moment they tapped.
    if (driveLatch || e.metaKey || e.ctrlKey) {
      // A drive tap moves the device, so the cached element tree is stale.
      const done = mirror.tapAt(e, null);
      if (done && done.then && inspect && inspect.onDeviceMaybeChanged) done.then(inspect.onDeviceMaybeChanged);
      return done;
    }
    return onStageClick ? onStageClick(e) : mirror.tapAt(e, runId);
  };
  // Drag-to-swipe: a pointer press is captured, and the gesture the release resolves to is decided
  // by distance and dwell (same thresholds as the Interact recorder). A short still press is a tap
  // (routed through stageClick above, exact existing semantics); a drag past 6% of the device width
  // is a swipe; a long still press is a long-press. Drive taps (⌘/Ctrl or the Drive latch) never
  // record; a plain gesture records into `runId`; and when a propose flow owns clicks (onStageClick),
  // a swipe rides onStageSwipe if given, else falls back to a drive-only send so the confirm gate
  // is never bypassed.
  const downRef = React.useRef(null);
  const onStagePointerDown = (e) => {
    if (busy || readOnly) return;
    if (e.pointerType === 'mouse' && e.button !== 0) return;
    const p = mirror.toDeviceCoords(e);
    if (!p) return;
    try { e.currentTarget.setPointerCapture(e.pointerId); } catch (_) {}
    downRef.current = { x: p.x, y: p.y, t: Date.now() };
  };
  const onStagePointerUp = (e) => {
    const down = downRef.current;
    downRef.current = null;
    if (busy || readOnly || !down) return;
    const up = mirror.toDeviceCoords(e);
    if (!up) return;
    const dw = (mirror.conn && mirror.conn.w) || 1;
    const dist = Math.hypot(up.x - down.x, up.y - down.y);
    const dt = Date.now() - down.t;
    const drive = driveLatch || e.metaKey || e.ctrlKey;
    if (dist > dw * 0.06) {
      const durationMs = Math.max(120, Math.min(1200, dt));
      if (drive) mirror.swipeAt(down, up, durationMs, null);
      else if (onStageClick) { if (onStageSwipe) onStageSwipe(down, up, durationMs); else mirror.swipeAt(down, up, durationMs, null); }
      else mirror.swipeAt(down, up, durationMs, runId);
    } else if (dt > 550) {
      if (drive || onStageClick) mirror.longPressAt(up, null);
      else mirror.longPressAt(up, runId);
    } else {
      // A tap keeps the exact existing routing (drive-only / propose / record).
      stageClick(e);
    }
  };
  // Map device-coordinate bounds to a percentage box over the frame. The wrapper below
  // shrink-wraps the <img> and the frame shares the device's aspect, so percentages map 1:1.
  const boxPct = (b) => {
    const dw = (mirror.conn && mirror.conn.w) || 1, dh = (mirror.conn && mirror.conn.h) || 1;
    return { left: (b.left / dw * 100) + '%', top: (b.top / dh * 100) + '%', width: ((b.right - b.left) / dw * 100) + '%', height: ((b.bottom - b.top) / dh * 100) + '%' };
  };
  const overlayOn = inspect && inspect.on;
  return (
    // minWidth floor: the stage is the subject of the screen - without it the two resizable
    // rails can crush the mirror to a sliver on narrow windows (dominance inverted, ch06).
    <div style={{ flex: 1, minWidth: 240, minHeight: 0, display: 'flex', flexDirection: 'column', alignItems: 'center', padding: '14px 18px', background: 'var(--bg-standard)' }}>
      <div style={{ width: '100%', maxWidth: 460, display: 'flex', alignItems: 'center', gap: 10 }}>
        {deviceList.length > 0 && (
          <Select compact value={selectedId || ''} options={deviceList.map((d) => [d.id, `${d.name || d.id} (${d.platform || '?'})`])} label={selectedId || 'device'} onChange={(e) => setSelectedId(e.target.value)} />
        )}
        {!readOnly && deviceList.length > 0 && (
          <Btn sm ico="refresh-cw" onClick={mirror.reconnect} title="Reconnect the device mirror - use this if the screen freezes or taps stop landing" />
        )}
        {!readOnly && (
          <Btn sm ico="gamepad-2" onClick={() => setDriveLatch((v) => !v)}
            title={driveLatch ? 'Driving: taps land on the device immediately and record nothing. Click to go back to recording.' : 'Drive the device without recording steps (or hold ⌘ while tapping)'}
            style={driveLatch ? { background: 'color-mix(in srgb, var(--tb-running) 22%, transparent)', borderColor: 'var(--tb-running)', color: 'var(--tb-running)' } : undefined}>Drive</Btn>
        )}
        <span style={{ flex: 1 }} />
        {/* Steady state, not an alarm: green is the human actor's recording lane, blue the
            informational driving lane (color signals the lane, ch09; red stays reserved for
            destructive/stop). No pulse either way. Read-only (a verification run the agent is
            driving) is a watch-only badge - the human doesn't touch the device here. */}
        {readOnly ? (
          <span title="The agent is running a trail on this device - watch only" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 700, letterSpacing: '.03em', color: 'var(--tb-running)' }}>
            <Ico n="eye" s={13} c="var(--tb-running)" /> Agent is driving - watch only
          </span>
        ) : driving ? (
          <span title="Taps land on the device immediately and are NOT recorded" style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 700, letterSpacing: '.03em', color: 'var(--tb-running)' }}>
            <span style={{ width: 8, height: 8, borderRadius: 99, background: 'var(--tb-running)', display: 'inline-block' }} /> Driving - not recording
          </span>
        ) : (
          <span title={copy.badgeTitle} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 11, fontWeight: 700, letterSpacing: '.03em', color: 'var(--tb-pass)' }}>
            <span style={{ width: 8, height: 8, borderRadius: 99, background: 'var(--tb-pass)', display: 'inline-block' }} /> {copy.badge}
          </span>
        )}
      </div>
      <AgentDeviceError err={err} onReconnect={mirror.reconnect} />
      <div style={{ flex: 1, minHeight: 0, display: 'flex', alignItems: 'center', justifyContent: 'center', marginTop: 12, overflow: 'hidden' }}>
        {frame ? (
          /* The wrapper shrink-wraps the frame so the inspector's percentage boxes land exactly on
             the device pixels they describe. */
          <div style={{ position: 'relative', display: 'flex', maxWidth: '100%', maxHeight: 'min(78vh, 900px)', minWidth: 0 }}>
            <img src={frame} alt={readOnly ? 'Live device screen (the agent is running a trail)' : driving ? 'Live device screen (driving - taps are not recorded)' : copy.alt}
              draggable={false} onPointerDown={onStagePointerDown} onPointerUp={onStagePointerUp}
              title={readOnly ? 'The agent is running a trail on this device - watch only' : driving ? 'Driving: taps land immediately and are not recorded' : copy.title}
              style={{ maxWidth: '100%', maxHeight: 'min(78vh, 900px)', objectFit: 'contain', borderRadius: 10, border: (driving && !readOnly) ? '1px solid var(--tb-running)' : '1px solid var(--tb-hairline-stronger)', boxShadow: '0 12px 40px rgba(0,0,0,.45)', background: '#000', touchAction: 'none', userSelect: 'none', cursor: readOnly ? 'default' : busy ? 'wait' : driving ? 'pointer' : 'crosshair' }} />
            {/* View-tree toggle - labeled boxes to see the structure and aim taps. */}
            {inspect && (
              <button onClick={(e) => { e.stopPropagation(); inspect.toggle(); }}
                title="Show the view tree (labeled boxes) on the device"
                style={{ position: 'absolute', top: 10, left: 10, zIndex: 4, display: 'inline-flex', alignItems: 'center', gap: 6, padding: '4px 9px', borderRadius: 8, cursor: 'pointer',
                  border: '1px solid ' + (inspect.toggled ? 'var(--tb-pass)' : 'rgba(255,255,255,.18)'),
                  background: inspect.toggled ? 'rgba(0,224,19,.16)' : 'rgba(0,0,0,.55)', color: inspect.toggled ? 'var(--tb-pass)' : '#cbd5e1', fontSize: 11, fontWeight: 600 }}>
                <Ico n="list-tree" s={13} c={inspect.toggled ? 'var(--tb-pass)' : '#cbd5e1'} /> View tree
              </button>
            )}
            {/* Element boxes + labels: interactive ring green, display-only ring blue. */}
            {overlayOn && (inspect.els || []).map((el, i) => {
              const lbl = (el.resourceId || '').trim() || (el.label || '').trim() || (el.type || '').trim();
              const tint = el.interactive ? '0,224,19' : '94,155,255';
              const w = el.right - el.left, h = el.bottom - el.top;
              const details = [
                el.label ? '“' + el.label + '”' : null,
                el.type || null,
                el.resourceId ? 'id: ' + el.resourceId : null,
                (el.interactive ? 'interactive' : 'display only') + ' · ' + w + '×' + h,
              ].filter(Boolean).join('\n');
              return (
                <div key={'o' + i} style={{ ...boxPct(el), position: 'absolute', border: `1px solid rgba(${tint},.45)`, borderRadius: 2, pointerEvents: 'none' }}>
                  {lbl && (
                    <span role="button" tabIndex={0} title={details} aria-label={'Select ' + lbl}
                      onClick={(e) => { e.stopPropagation(); if (!busy) inspect.onPickEl(el); }}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.stopPropagation(); e.preventDefault(); if (!busy) inspect.onPickEl(el); } }}
                      style={{ position: 'absolute', top: 0, left: 0, maxWidth: '100%', boxSizing: 'border-box', fontSize: 9, lineHeight: 1.25, padding: '0 3px', borderRadius: '0 0 3px 0', background: 'rgba(0,0,0,.72)', color: el.interactive ? '#7CFFA0' : '#bcd6ff', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', cursor: 'pointer', pointerEvents: 'auto' }}>{lbl}</span>
                  )}
                </div>
              );
            })}
            {/* The Elements list's hovered row, and the pending step's resolved element. */}
            {inspect && inspect.hoverEl && <div style={{ ...boxPct(inspect.hoverEl), position: 'absolute', border: '2px solid var(--tb-link)', background: 'rgba(94,155,255,.14)', borderRadius: 3, pointerEvents: 'none' }} />}
            {inspect && inspect.pendingEl && <div style={{ ...boxPct(inspect.pendingEl), position: 'absolute', border: '2px solid #c4a8ff', background: 'rgba(168,130,255,.14)', borderRadius: 3, pointerEvents: 'none' }} />}
          </div>
        ) : deviceList.length === 0 ? (
          <EmptyState ico="smartphone" title="Connect a device to demonstrate" sub="Taps you perform on a live device or emulator become the trail's steps. Connect one to begin." />
        ) : (
          <MirrorPlaceholder mirror={mirror} />
        )}
      </div>
      {/* One-time hint: after the first captured step, the trail rail itself teaches. The demo
          phases (mode set) carry their own guidance panels, so the confirm-gate hint stays out. */}
      {!hasSteps && !mode && !readOnly && (
        <div className="tb-sub" style={{ fontSize: 11, marginTop: 10, textAlign: 'center' }}>
          Tap the screen to propose a step - confirm it in the trail rail to run and record it. Hold ⌘ (or flip Drive) to navigate without recording.
        </div>
      )}
    </div>
  );
}

function externalAgentStatusIcon(status) {
  switch (status) {
    case 'running': return ['loader-circle', 'var(--tb-running)'];
    case 'completed': return ['check-circle-2', 'var(--tb-pass)'];
    case 'failed': return ['circle-x', 'var(--tb-fail)'];
    case 'cancelled': return ['octagon-x', 'var(--tb-amber)'];
    default: return ['circle', 'var(--text-subtle)'];
  }
}

function summarizeJson(value) {
  if (value == null) return '';
  try { return typeof value === 'string' ? value : JSON.stringify(value); } catch (_) { return String(value); }
}

function parseMaybeJson(value) {
  if (typeof value !== 'string') return value;
  try { return JSON.parse(value); } catch (_) { return value; }
}

function truncateMiddle(text, max) {
  const s = String(text || '');
  if (s.length <= max) return s;
  const head = Math.floor(max * 0.62);
  const tail = Math.max(80, max - head - 20);
  return s.slice(0, head) + '\n…\n' + s.slice(-tail);
}

Object.assign(window, {
  ExternalAgentsScreen, ExternalAgentChat, AgentConversationColumn, AgentComposer, AgentQuietPicker, AgentToolCard,
  AgentTapeCard, tapSelectorMeta, AgentTrailTapePanel, useAgentDeviceMirror, AgentRecordStage,
  AgentDeviceEventLog, AgentEventLogRow, AgentTrailOutputCard, AgentDeviceError, pendingAskQuestions, askQuestionOptions,
  AgentTrailRail, AgentYamlPanel, detectComposedTrail, buildTrailPreview,
  buildAgentTranscript, stripUiCommandLines, agentToolMeta, externalAgentStatusIcon,
});
