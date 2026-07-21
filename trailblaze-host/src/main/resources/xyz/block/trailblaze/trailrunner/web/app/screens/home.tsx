// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// One stage in the Create / Record / Save pipeline shown on Home. Clickable when
// `onClick` is given (it jumps to the matching tab). The preview inside `children` makes the
// stage concrete: a prompt field, a recording device, a saved trail folder. Titles are kept
// short so they never truncate; the caption sits directly under the preview, extra height
// pads the bottom so the three cards stay even.
function FlowStage({ kicker, ico, color, bg, title, sub, onClick, children }) {
  const clickable = !!onClick;
  return (
    <div
      className="tb-card"
      onClick={onClick}
      role={clickable ? 'button' : undefined}
      tabIndex={clickable ? 0 : undefined}
      onKeyDown={clickable ? (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onClick(); } } : undefined}
      style={{ flex: 1, minWidth: 0, padding: 14, display: 'flex', flexDirection: 'column', gap: 11, cursor: clickable ? 'pointer' : 'default', background: 'var(--bg-subtle)' }}
    >
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <div style={{ width: 32, height: 32, borderRadius: 9, background: bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
          <Ico n={ico} s={17} c={color} />
        </div>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div className="tb-eyebrow" style={{ color, marginBottom: 2 }}>{kicker}</div>
          <div style={{ fontSize: 15, fontWeight: 700, lineHeight: 1.1, letterSpacing: '-.01em' }}>{title}</div>
        </div>
        {clickable && <Ico n="arrow-up-right" s={14} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />}
      </div>
      {children}
      {sub && <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.45 }}>{sub}</div>}
    </div>
  );
}

function FlowArrow({ vertical }) {
  return (
    <div style={{ flex: '0 0 auto', alignSelf: 'center', display: 'flex' }} aria-hidden="true">
      <span style={{ width: 26, height: 26, borderRadius: 99, border: '1px solid var(--tb-hairline-strong)', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'var(--text-subtle)' }}>
        <Ico n={vertical ? 'arrow-down' : 'arrow-right'} s={13} />
      </span>
    </div>
  );
}

// The hero: how a test gets made, left to right, in the real order of operations. The blaze comes
// FIRST — a prompt becomes ordered, plain-language steps (the spec; no device, no recording yet).
// THEN you run that blaze on each platform to RECORD the actual tool calls (one recording per
// device). FINALLY the trail is saved and replays deterministically. Color carries the story:
// purple = AI-authored (--tb-ai), blue = live run (--tb-running), green = deterministic (--tb-pass).
// Each card jumps to its real stage: Create → Create (record) → Trails.
function HomePipeline({ go, vertical }) {
  // Each plain-language step (authored at Create) resolves to a concrete tool call when recorded
  // on a device. Card 1 shows just the step; card 2 (Record) shows the tool each step captured.
  const steps = [
    { name: 'Launch app', tool: 'launchApp' },
    { name: 'Tap Sign in', tool: 'tapOn' },
    { name: 'Enter email', tool: 'inputText' },
    { name: 'Check balance', tool: 'assertVisible' },
  ];
  const variants = ['ios', 'android', 'web'];
  return (
    <div>
      {!vertical && <div className="tb-eyebrow" style={{ marginBottom: 11 }}>How a test is made</div>}
      <div style={{ display: 'flex', flexDirection: vertical ? 'column' : 'row', gap: 10, alignItems: 'stretch' }}>
        {/* 1 · Create the blaze: a prompt becomes ordered, plain-language steps — one spec, no
            device or recording yet. Purple = AI-authored content (--tb-ai). */}
        <FlowStage kicker="1 · Create" ico="sparkles" color="var(--tb-ai)" bg="rgba(181,140,255,.16)"
          title="Write a prompt" sub="An agent composes the trail with you." onClick={() => go('create')}>
          <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '9px 11px', borderBottom: '1px solid var(--tb-hairline)' }}>
              <Ico n="sparkles" s={12} c="var(--tb-ai)" style={{ flex: '0 0 auto' }} />
              <span style={{ fontSize: 12, color: 'var(--text-standard)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Sign in and check my balance</span>
            </div>
            <div style={{ padding: '7px 0' }}>
              {steps.map((s, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '3px 11px' }}>
                  <span style={{ width: 15, height: 15, borderRadius: 4, flex: '0 0 auto', background: 'rgba(181,140,255,.2)', color: 'var(--tb-ai)', fontSize: 9, fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{i + 1}</span>
                  <span style={{ fontSize: 11.5, color: 'var(--text-standard)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.name}</span>
                </div>
              ))}
            </div>
          </div>
        </FlowStage>

        <FlowArrow vertical={vertical} />

        {/* 2 · Record on platforms: run the same blaze on each device; the agent performs the
            authored steps and captures the real tool calls — one recording per platform. This is
            where devices/platforms enter. Blue = live run (--tb-running). */}
        <FlowStage kicker="2 · Record" ico="circle-play" color="var(--tb-running)" bg="rgba(94,155,255,.16)"
          title="Run on devices" sub="Each step records a real tool." onClick={() => go('create')}>
          {/* Full-width box matching cards 1 and 3 (header row + rows that fill the width) so the
              three pipeline stages read at the same size. A RECORDING header stands in for card 1's
              prompt header / card 3's folder header. */}
          <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 11px', borderBottom: '1px solid var(--tb-hairline)' }}>
              <span className="tb-pulse" style={{ width: 6, height: 6, borderRadius: 99, flex: '0 0 auto', background: 'var(--tb-running)', boxShadow: '0 0 6px var(--tb-running)' }} />
              <span className="tb-mono" style={{ fontSize: 9, letterSpacing: '.1em', color: 'var(--tb-running)' }}>RECORDING</span>
            </div>
            <div style={{ padding: '6px 0' }}>
              {steps.map((s, i) => (
                <div key={i} style={{ display: 'flex', alignItems: 'flex-start', gap: 8, padding: '4px 11px' }}>
                  <span style={{ width: 15, height: 15, borderRadius: 4, flex: '0 0 auto', marginTop: 1, background: 'var(--bg-prominent)', color: 'var(--text-subtle)', fontSize: 9, fontWeight: 700, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>{i + 1}</span>
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ fontSize: 11.5, color: 'var(--text-standard)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.name}</div>
                    <div className="tb-mono" title={'Recorded tool: ' + s.tool} style={{ fontSize: 9.5, color: 'var(--tb-running)', marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.tool}</div>
                  </div>
                </div>
              ))}
            </div>
          </div>
          {/* one recording per platform — the same steps, captured on each device */}
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 12, marginTop: 1 }}>
            {variants.map((name) => (
              <span key={name} title={'records on ' + name} style={{ display: 'inline-flex', alignItems: 'center', gap: 4, color: 'var(--text-subtle)' }}>
                <PlatformGlyph platform={name} s={13} c="var(--text-subtle)" /><span className="tb-mono" style={{ fontSize: 9.5 }}>{name}</span>
              </span>
            ))}
          </div>
        </FlowStage>

        <FlowArrow vertical={vertical} />

        {/* 3 · Save the trail: the folder of per-platform recordings, replayed deterministically
            with zero LLM calls. Green = deterministic / done (--tb-pass). */}
        <FlowStage kicker="3 · Save" ico="save" color="var(--tb-pass)" bg="rgba(0,224,19,.13)"
          title="Keep the trail" sub="Replay forever. Zero AI." onClick={() => go('trails')}>
          <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', overflow: 'hidden' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 7, padding: '8px 11px', borderBottom: '1px solid var(--tb-hairline)' }}>
              <Ico n="folder" s={13} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />
              <span className="tb-mono" style={{ fontSize: 11.5, color: 'var(--text-standard)' }}>balance/</span>
            </div>
            <div style={{ padding: '6px 0' }}>
              {variants.map((name) => (
                <div key={name} style={{ display: 'flex', alignItems: 'center', gap: 8, padding: '4px 11px' }}>
                  <PlatformGlyph platform={name} s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
                  <span className="tb-mono" style={{ flex: 1, minWidth: 0, fontSize: 11, color: 'var(--text-subtle-variant)', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</span>
                  <Ico n="check" s={11} c="var(--tb-pass)" style={{ flex: '0 0 auto' }} />
                </div>
              ))}
            </div>
            <div style={{ padding: '2px 11px 11px' }}><Chip tone="green" ico="zap">deterministic · 0 LLM</Chip></div>
          </div>
        </FlowStage>
      </div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 7, marginTop: 12, color: 'var(--text-subtle)' }}>
        <Ico n="refresh-cw" s={13} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
        <span className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.45 }}>Steps are authored once. Replays are deterministic; the AI only returns to re-record a step that drifted.</span>
      </div>
    </div>
  );
}

// The ways to start a test: draft inside Trail Runner, or bring your own coding agent (companion
// mode - the agent authors the trail from its own CLI session while Trail Runner mirrors the
// folder live). The first door lands on a blank Create session (sel: 'new'); the second opens the
// paste-a-prompt drawer instead of navigating - the actual session appears once the agent attaches.
function CreateOptions({ go, onCompanion }) {
  const opts = [
    { key: 'create', onClick: () => go('create', { sel: 'new' }), ico: 'sparkles', color: 'var(--tb-ai)', bg: 'rgba(181,140,255,.16)', kicker: 'Create', title: 'Draft in Trail Runner', sub: 'Drive a live device yourself or describe the flow to an agent - every action becomes an editable step.', cta: 'New session' },
    { key: 'companion', onClick: onCompanion, ico: 'satellite-dish', color: 'var(--tb-running)', bg: 'rgba(94,155,255,.16)', kicker: 'Companion', title: 'Work with your own agent', sub: 'Already pairing with Claude Code or Codex? Paste one prompt there and your agent authors the trail while Trail Runner mirrors it live.', cta: 'Get the prompt' },
  ];
  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'stretch', flexWrap: 'wrap' }}>
      {opts.map((o) => {
        return (
          <div key={o.key} className="tb-card" role="button" tabIndex={0}
            onClick={o.onClick}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); o.onClick(); } }}
            style={{ flex: '1 1 210px', minWidth: 0, padding: 16, display: 'flex', flexDirection: 'column', gap: 11, cursor: 'pointer', background: 'var(--bg-subtle)' }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
              <div style={{ width: 36, height: 36, borderRadius: 10, background: o.bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
                <Ico n={o.ico} s={18} c={o.color} />
              </div>
              <div style={{ minWidth: 0, flex: 1 }}>
                <div className="tb-eyebrow" style={{ color: o.color, marginBottom: 2 }}>{o.kicker}</div>
                <div style={{ fontSize: 16, fontWeight: 700, lineHeight: 1.1, letterSpacing: '-.01em' }}>{o.title}</div>
              </div>
            </div>
            <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5, flex: 1 }}>{o.sub}</div>
            <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, color: o.color, fontSize: 12.5, fontWeight: 600 }}>{o.cta} <Ico n="arrow-right" s={14} c={o.color} /></span>
          </div>
        );
      })}
    </div>
  );
}

// The statement seed mirrors Record's Context card: the trail's intent line always reads
// "Validates that a user can …", so the on-ramp asks for exactly that sentence.
const COMPANION_STATEMENT_SEED = 'Validates that a user can ';

// The one prompt the developer pastes into their own agent CLI. Regenerated on every keystroke of
// the statement; the clarifying-questions ask comes FIRST so the agent gathers context in the
// thread before it attaches or writes anything.
function companionAgentPrompt(statement, root) {
  return [
    "Help me author a Trailblaze trail - a natural-language UI test that gets recorded into deterministic YAML - using Trail Runner's companion mode.",
    '',
    'Trail intent: ' + statement.trim(),
    '',
    'Before touching any files, ask me 5 clarifying questions in this thread to pin down the context for this trail - things like the app target and platforms, the account and starting data state, what each step should assert, edge cases worth covering, and where the trail folder should live. Wait for my answers.',
    '',
    'Then:',
    '1. Run `trailblaze companion --agent-help` and follow that contract.',
    '2. From the workspace root (' + (root || '<your workspace root>') + '), attach with `trailblaze companion start --folder <trail-folder> --title "<short title>"`.',
    '3. Author the trail folder on disk, narrating with companion events as you go - I will follow along in the Trail Runner window and record steps on a device when you arm a recording.',
    '4. Disconnect when the trail is done.',
  ].join('\n');
}

// The companion on-ramp behind the "Work with your own agent" door: ask for the trail's
// "Validates that a user can …" statement, then hand over the paste-ready prompt. Copy stays
// locked until the statement says something - shipping the seed verbatim would brief the agent
// with a blank intent.
function CompanionPromptDrawer({ onClose }) {
  const status = TB.useStatus();
  const [statement, setStatement] = React.useState(COMPANION_STATEMENT_SEED);
  const [copied, setCopied] = React.useState(false);
  const [copyErr, setCopyErr] = React.useState(false);
  const inputRef = React.useRef(null);
  React.useEffect(() => {
    const el = inputRef.current;
    if (el) { el.focus(); el.setSelectionRange(el.value.length, el.value.length); }
  }, []);
  const done = statement.trim() !== '' && statement.trim() !== COMPANION_STATEMENT_SEED.trim();
  const prompt = companionAgentPrompt(statement, status.data && status.data.trailsDirectory);
  const copy = () => {
    setCopyErr(false);
    navigator.clipboard.writeText(prompt)
      .then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); })
      .catch(() => setCopyErr(true));
  };
  return (
    <HelpOverlay
      title="Work with your own agent"
      sub="Your coding agent authors the trail from its own CLI session; Trail Runner mirrors the folder live and lends you a device for recordings. One paste sets it up."
      onClose={onClose}
    >
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <Ico n="target" s={15} c="var(--tb-ai)" />
          <span style={{ fontSize: 12.5, fontWeight: 700 }}>What should this trail prove?</span>
        </div>
        <textarea ref={inputRef} value={statement} onChange={(e) => setStatement(e.target.value)} spellCheck={false}
          aria-label="What should this trail prove?"
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.blur(); } }}
          placeholder="e.g. Validates that a user can send $5 to a friend"
          style={{ width: '100%', boxSizing: 'border-box', minHeight: 46, resize: 'vertical', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, outline: 'none', color: 'var(--text-standard)', padding: '8px 10px', font: 'inherit', fontSize: 13.5, lineHeight: 1.5 }} />
        <div className="tb-sub" style={{ fontSize: 11, lineHeight: 1.5, marginTop: 6, color: done ? undefined : 'var(--tb-amber)' }}>
          {done ? 'This becomes the trail’s intent line - the agent uses it to recover when a screen looks unexpected.' : 'Finish the sentence - it becomes the trail’s intent line and briefs your agent.'}
        </div>
      </div>
      <div>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
          <Ico n="clipboard" s={15} c="var(--tb-running)" />
          <span style={{ fontSize: 12.5, fontWeight: 700, flex: 1 }}>Prompt for your agent</span>
          <Btn sm ico={copied ? 'check' : 'copy'} disabled={!done} onClick={copy}>{copied ? 'Copied!' : 'Copy'}</Btn>
        </div>
        {copyErr && <div role="alert" style={{ fontSize: 11, color: 'var(--tb-danger-text)', marginBottom: 6 }}>Couldn’t copy automatically - select the prompt above and copy it manually.</div>}
        <pre data-selectable style={{ margin: 0, background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '10px 12px', overflowX: 'auto', fontSize: 11, lineHeight: 1.6 }}>
          <code className="tb-mono" style={{ color: '#cbd5e1', whiteSpace: 'pre-wrap' }}>{prompt}</code>
        </pre>
        <div className="tb-sub" style={{ fontSize: 11, lineHeight: 1.5, marginTop: 8 }}>
          Paste it into Claude Code, Codex, or any agent CLI running in this workspace. It will ask its five questions right in that thread; once it attaches, the live session appears in the sidebar under Create.
        </div>
      </div>
    </HelpOverlay>
  );
}

function ReadinessPanel({ go }) {
  const status = TB.useStatus();
  const devices = TB.useDevices();
  const trails = TB.useTrails();
  const [gt] = TB.useGlobalTarget();
  const deviceList = devices.data || [];
  const selectedIds = (gt && gt.deviceIds) || [];
  const selectedDevices = selectedIds.map((id) => deviceList.find((d) => d.id === id)).filter(Boolean);
  const selectedConnected = selectedDevices.filter((d) => d.connected !== false).length;
  const totalTrails = TB.countTrailBundles(trails.data || []);
  const targetSelected = window.TargetPickerModel.hasTargetSelection(gt);
  const targetLabel = targetSelected ? (gt.label || gt.target) : null;
  // One compact segment per check; the short value renders inline, the full guidance rides on hover.
  // Before the first /status response, the daemon row is genuinely unknown - painting
  // "not running" for that beat reads as a broken app. After the first response, "not running"
  // is the honest state (a failed fetch maps to { running: false }).
  const statusPending = status.loading && !status.data;
  const rows = [
    {
      label: 'Daemon',
      value: statusPending ? 'checking…' : status.data?.running ? `port ${status.data.daemonPort || '?'}` : 'not running',
      hint: statusPending ? 'Checking the daemon…' : status.data?.running ? `Running on port ${status.data.daemonPort || '?'}` : 'Start the daemon before running tests.',
      ok: statusPending ? null : !!status.data?.running,
      action: 'settings',
    },
    {
      label: 'Target',
      value: targetLabel || 'none',
      hint: targetLabel || 'Choose the app or web target under test.',
      ok: targetSelected,
      action: 'home',
    },
    {
      label: 'Devices',
      value: selectedConnected ? `${selectedConnected} connected` : 'none',
      hint: selectedConnected ? `${selectedConnected} selected and connected` : deviceList.length ? 'Select a connected device for runs.' : 'Connect Android, iOS, or web.',
      ok: selectedConnected > 0,
      action: 'home',
    },
    {
      label: 'Workspace',
      value: totalTrails ? `${totalTrails} trail${totalTrails === 1 ? '' : 's'}` : 'no trails',
      hint: totalTrails ? `${totalTrails} saved trail${totalTrails === 1 ? '' : 's'} available` : 'No saved trails yet; create a trail to get started.',
      ok: totalTrails > 0,
      action: totalTrails > 0 ? 'trails' : 'create',
    },
  ];
  return (
    <div className="tb-card" style={{ padding: '6px 10px', background: 'var(--bg-subtle)', display: 'flex', alignItems: 'center', gap: 4, flexWrap: 'wrap' }}>
      <span className="tb-eyebrow" style={{ padding: '0 6px 0 2px', flex: '0 0 auto' }}>Readiness</span>
      {rows.map((r) => (
        <div key={r.label} role="button" tabIndex={0} title={r.hint}
          onClick={() => go(r.action)}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(r.action); } }}
          style={{ display: 'flex', alignItems: 'center', gap: 6, padding: '4px 8px', borderRadius: 7, cursor: 'pointer', minWidth: 0 }}>
          <Ico n={r.ok === null ? 'loader-circle' : r.ok ? 'circle-check-big' : 'circle-alert'} s={13} c={r.ok === null ? 'var(--text-subtle)' : r.ok ? 'var(--tb-pass)' : 'var(--tb-amber)'} spin={r.ok === null} />
          <span style={{ fontSize: 11.5, fontWeight: 600, flex: '0 0 auto' }}>{r.label}</span>
          <span className="tb-sub" style={{ fontSize: 11, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 180 }}>{r.value}</span>
        </div>
      ))}
    </div>
  );
}

// A top-level Home section ("Create a new test" / "Run an existing test"). An icon chip + bold
// title anchors each half of the page (dominance, ch07) so the two jobs-to-be-done read as
// distinct; the sub-sections inside (eyebrow headers) sit one level below it.
function HomeSection({ ico, color, bg, title, sub, first, headerRight, children }) {
  return (
    <div style={{ marginTop: first ? 22 : 34 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 11, marginBottom: 16 }}>
        <div style={{ width: 30, height: 30, borderRadius: 9, background: bg, display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
          <Ico n={ico} s={16} c={color} />
        </div>
        <div style={{ minWidth: 0, flex: 1 }}>
          <h2 className="tb-h2" style={{ fontSize: 18 }}>{title}</h2>
          {sub && <div className="tb-sub" style={{ fontSize: 12, marginTop: 1 }}>{sub}</div>}
        </div>
        {headerRight && <div style={{ flex: '0 0 auto' }}>{headerRight}</div>}
      </div>
      {children}
    </div>
  );
}

// "Browse tests" entry for the Run section: a clickable card into the Trails library, captioned
// with how many saved trails are in the workspace.
function BrowseTests({ go }) {
  const trails = TB.useTrails();
  const count = TB.countTrailBundles(trails.data || []);
  return (
    <div className="tb-card" role="button" tabIndex={0} data-testid="browse-tests"
      onClick={() => go('trails')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go('trails'); } }}
      style={{ padding: '14px 16px', display: 'flex', alignItems: 'center', gap: 13, cursor: 'pointer', background: 'var(--bg-subtle)' }}>
      <div style={{ width: 38, height: 38, borderRadius: 10, background: 'rgba(94,155,255,.14)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
        <Ico n="route" s={19} c="var(--tb-running)" />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, fontWeight: 700 }}>Browse tests</div>
        <div className="tb-sub" style={{ fontSize: 12, marginTop: 2 }}>{count ? `${count} trail${count === 1 ? '' : 's'} in your workspace` : 'Pick a saved test and run it'}</div>
      </div>
      <Ico n="arrow-right" s={16} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
    </div>
  );
}

// The Workspace section: which checkout the daemon is rooted at - worktrees make this genuinely
// non-obvious, so the full path is shown selectable - what's inside it (trails, app targets), and
// the controls to re-point or reveal it. Same change flow as the sidebar's WorkspaceChip popover:
// pickDirectoryViaShell then switchWorkspace, which broadcasts tb:workspace-changed so every
// list (and this panel's own counts) re-resolves against the new folder.
function WorkspacePanel({ go }) {
  useLucide();
  const status = TB.useStatus();
  const trails = TB.useTrails();
  const [targetCount, setTargetCount] = React.useState(null);
  const [busy, setBusy] = React.useState(false);
  const [err, setErr] = React.useState(null);
  const [notice, setNotice] = React.useState(null);
  React.useEffect(() => {
    let alive = true;
    const load = async () => {
      const r = await Promise.resolve(TB.getTargetApps()).catch(() => null);
      if (alive) setTargetCount(r && Array.isArray(r.targetApps) ? r.targetApps.length : null);
    };
    load();
    window.addEventListener('tb:workspace-changed', load);
    window.addEventListener('tb:daemon-recovered', load);
    return () => {
      alive = false;
      window.removeEventListener('tb:workspace-changed', load);
      window.removeEventListener('tb:daemon-recovered', load);
    };
  }, []);
  const dir = status.data && status.data.trailsDirectory;
  if (!dir) return null;
  const name = workspaceRepoName(dir);
  const branch = status.data.workspaceBranch;
  const isWorktree = !!status.data.workspaceIsWorktree;
  const trailCount = trails.data ? TB.countTrailBundles(trails.data) : null;
  const change = async () => {
    setBusy(true);
    setErr(null);
    setNotice(null);
    try {
      const path = await TB.pickDirectoryViaShell(dir);
      if (!path) return; // cancelled - finally still clears busy
      const res = await TB.switchWorkspace(path);
      if (!res.ok) { setErr(res.error || 'Could not switch to that folder. It may not be a readable directory.'); return; }
      if (res.empty) setNotice(TB.WORKSPACE_EMPTY_NOTICE);
    } catch (e) {
      setErr('Could not change the workspace: ' + (e && e.message ? e.message : String(e)));
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="tb-card" style={{ padding: '14px 16px', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', gap: 10 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 13 }}>
        <div style={{ width: 38, height: 38, borderRadius: 10, background: 'rgba(0,224,19,.13)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
          <Ico n="folder-git-2" s={19} c="var(--tb-pass)" />
        </div>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 14.5, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{name}</div>
          {branch && (
            <div style={{ display: 'flex', alignItems: 'center', gap: 5, fontSize: 11.5, color: 'var(--text-subtle)', marginTop: 2, overflow: 'hidden' }}>
              <Ico n="git-branch" s={11} c={isWorktree ? 'var(--tb-running)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
              <span style={{ whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{branch}</span>
              {isWorktree && <Chip>git worktree</Chip>}
            </div>
          )}
        </div>
        <div style={{ display: 'flex', gap: 8, flex: '0 0 auto' }}>
          <Btn sm ico={busy ? 'loader-2' : 'folder-open'} spin={busy} onClick={change} disabled={busy}>Change…</Btn>
          <Btn sm kind="ghost" ico="external-link" onClick={() => TB.revealTrailsRoot()}>Reveal in Finder</Btn>
        </div>
      </div>
      <div className="tb-mono" data-selectable style={{ fontSize: 11, lineHeight: 1.5, color: '#94a3b8', padding: '8px 10px', background: '#0a0a0a', border: '1px solid var(--tb-hairline)', borderRadius: 8, overflowWrap: 'anywhere', wordBreak: 'break-word' }}>{dir}</div>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16 }}>
        <span role="button" tabIndex={0} onClick={() => go('trails')}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go('trails'); } }}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600, color: 'var(--text-standard)', cursor: 'pointer' }}>
          <Ico n="route" s={13} c="var(--tb-running)" />{trailCount == null ? '…' : trailCount} trail{trailCount === 1 ? '' : 's'}
        </span>
        <span style={{ display: 'inline-flex', alignItems: 'center', gap: 6, fontSize: 12, fontWeight: 600 }}>
          <Ico n="package" s={13} c="var(--text-subtle)" />{targetCount == null ? '…' : targetCount} target{targetCount === 1 ? '' : 's'}
        </span>
      </div>
      {err && <div role="alert" style={{ fontSize: 11.5, lineHeight: 1.5, color: 'var(--tb-danger-text)' }}>{err}</div>}
      {notice && <div role="status" style={{ fontSize: 11.5, lineHeight: 1.5, color: 'var(--text-subtle)' }}>{notice}</div>}
    </div>
  );
}

function HomeContent({ go }) {
  useLucide();
  const [showHelp, setShowHelp] = React.useState(false);
  const [showCompanion, setShowCompanion] = React.useState(false);

  return (
    <div style={{ maxWidth: 940, margin: '0 auto', padding: '28px 30px' }}>
      <ScreenHead
        eyebrow="Trail Runner"
        title={<>What would you like to <span style={{ color: 'var(--tb-pass)' }}>do today?</span></>}
        right={<HelpButton title="How Trail Runner works" onClick={() => setShowHelp(true)} />}
      />

      {/* Section 1 - author a new test: the ways to start, with the teaching pipeline tucked
          behind a "?" popover on the header (no longer inline). */}
      <HomeSection first ico="sparkles" color="var(--tb-ai)" bg="rgba(181,140,255,.16)"
        title="Create a new test"
        headerRight={
          <HelpButton title="How a test is made" sub="A prompt becomes ordered steps, you record them once on each device, and the saved trail replays forever - deterministic, zero AI.">
            <HomePipeline go={go} vertical />
          </HelpButton>
        }>
        <CreateOptions go={go} onCompanion={() => setShowCompanion(true)} />
      </HomeSection>

      {/* Section 2 — re-run something that already exists: browse the library. */}
      <HomeSection ico="circle-play" color="var(--tb-running)" bg="rgba(94,155,255,.16)"
        title="Run an existing test">
        <BrowseTests go={go} />
      </HomeSection>

      {/* Section 3 — where everything lives: the active workspace (checkout/worktree), its
          contents at a glance, and the controls to re-point it. */}
      <HomeSection ico="folder-git-2" color="var(--tb-pass)" bg="rgba(0,224,19,.13)"
        title="Workspace"
        sub="Every trail, tool, and recording resolves against this folder.">
        <WorkspacePanel go={go} />
      </HomeSection>

      {/* First-run status — mirrors the native app's environment status without duplicating the
          workspace chip's path details. Rendered as one slim strip. */}
      <div style={{ marginTop: 26, paddingTop: 14, borderTop: '1px solid var(--tb-hairline)' }}>
        <ReadinessPanel go={go} />
      </div>

      {showCompanion && <CompanionPromptDrawer onClose={() => setShowCompanion(false)} />}

      {showHelp && (
        <HelpOverlay
          title="How Trail Runner works"
          sub="Trailblaze tests your app's UI with natural language. The loop: describe a flow, let the AI drive a real device once, keep the recording, and replay it forever - deterministic and free. Every screen here is one stage of that loop."
          onClose={() => setShowHelp(false)}
        >
          <HelpCard ico="package" color="var(--tb-running)" title="First · pick a target">
            On the left, choose the app under test and the devices to run it on. The target scopes the Trailmaps the agent can use and is the default for every run.
          </HelpCard>
          <HelpCard ico="sparkles" color="var(--tb-ai)" title="Create · a blaze from a prompt">
            On the Prompt screen, write the objective in plain language. The model turns it into an ordered list of plain-language steps - the blaze. That is one portable spec; no device is touched and nothing is recorded yet.
          </HelpCard>
          <HelpCard ico="circle-play" color="var(--tb-running)" title="Record · run on each device">
            Run the blaze on the devices you picked - it waits under the In progress entry on Home until every platform is recorded. The agent performs the authored steps and captures the real tool calls, producing one recording per platform. Watch each run live under Active.
          </HelpCard>
          <HelpCard ico="save" color="var(--tb-pass)" title="Save · keep it as a trail">
            Save the folder of recordings under Trails. Replays re-run the exact recorded tools - fast, deterministic, zero LLM calls - and the AI only steps back in if you re-record or self-heal kicks in on a drifted step.
          </HelpCard>
        </HelpOverlay>
      )}
    </div>
  );
}

// Derive a readable repo-root name for the active trails workspace, shown in the sidebar's
// workspace marker (WorkspaceChip in shell.jsx). Worktrees live under
// <repo>/.claude/worktrees/<wt>/..., so the repo name is the segment before .claude; for a
// normal checkout the workspace sits at <repo>/trails, so the parent of `trails` is the repo.
function workspaceRepoName(dir) {
  if (!dir) return null;
  const parts = String(dir).replace(/\/+$/, '').split('/').filter(Boolean);
  if (!parts.length) return null;
  const ci = parts.indexOf('.claude');
  if (ci > 0) return parts[ci - 1];
  const last = parts[parts.length - 1];
  if (last === 'trails' && parts.length >= 2) return parts[parts.length - 2];
  return last;
}

function HomeScreen({ go }) {
  useLucide();
  // Home and the old standalone Targets screen are now one screen: the target/device
  // picker is the left rail (like the other tabs), the informational content is on the right.
  const [railW, startDrag] = useResizableWidth('tb-home-target-w', 332, 280, 520);
  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%' }}>
      <div style={{ width: railW, flex: '0 0 ' + railW + 'px', minWidth: 0, borderRight: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
        <TargetDevicePicker go={go} />
      </div>
      <Splitter onDown={startDrag} />
      <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column' }}>
        <div style={{ flex: 1, minWidth: 0, overflowY: 'auto' }}>
          <HomeContent go={go} />
        </div>
      </div>
    </div>
  );
}

function Skeleton({ rows = 3 }) {
  return (
    <>
      {Array.from({ length: rows }).map((_, i) => (
        <div className="tb-row" key={i} style={{ marginBottom: 8, opacity: .35 }}>
          <div className="tb-skel" style={{ width: 9, height: 9, borderRadius: 99 }}></div>
          <div style={{ flex: 1 }}>
            <div className="tb-skel" style={{ height: 10, width: '60%' }}></div>
            <div className="tb-skel" style={{ height: 8, width: '40%', marginTop: 5, opacity: .7 }}></div>
          </div>
        </div>
      ))}
    </>
  );
}
// icoColor/icoBg: optional accent so a screen's empty state can wear that surface's signature
// hue (e.g. Create's violet) instead of always reading generic gray.
function Empty({ ico, title, sub, icoColor, icoBg }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', padding: '40px 24px', color: 'var(--text-subtle)' }}>
      <div style={{ width: 56, height: 56, borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', background: icoBg || 'var(--bg-elevated)', border: '1px solid var(--tb-hairline)', marginBottom: 14 }}>
        <Ico n={ico} s={24} c={icoColor || 'var(--text-subtle)'} />
      </div>
      <div style={{ fontSize: 14.5, fontWeight: 600, color: 'var(--text-standard)' }}>{title}</div>
      {sub && <div className="tb-sub" style={{ fontSize: 12.5, marginTop: 5, maxWidth: 320, lineHeight: 1.5 }}>{sub}</div>}
    </div>
  );
}

Object.assign(window, { HomeScreen, Skeleton, EmptyState: Empty });
