// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// Home has one authoring door. The demonstration composer owns the later choice between recording
// here and handing the work to an external agent, so Home does not make the user choose a tool
// before they have even framed the trail.
function CreateOptions({ go }) {
  return (
    <Btn kind="primary" ico="plus" onClick={() => go('create', { sel: 'new' })}
      style={{ minHeight: 42, paddingInline: 17, fontSize: 13.5 }}>
      Create New Trail
    </Btn>
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

// The external-agent path lives inside the demonstration composer. Ask for the trail's
// "Validates that a user can …" statement, then hand over one paste-ready companion prompt.
// Copy stays locked until the statement says something - shipping the seed verbatim would brief
// the agent with a blank intent.
function ExternalAgentPromptPanel({ onCopied, onPickTarget, waiting = false }) {
  const status = TB.useStatus();
  const [gt] = TB.useGlobalTarget();
  const [statement, setStatement] = React.useState(COMPANION_STATEMENT_SEED);
  const [copied, setCopied] = React.useState(false);
  const [copyErr, setCopyErr] = React.useState(false);
  const [preview, setPreview] = React.useState(false);
  const inputRef = React.useRef(null);
  React.useEffect(() => {
    const el = inputRef.current;
    if (el) { el.focus({ preventScroll: true }); el.setSelectionRange(el.value.length, el.value.length); }
  }, []);
  const done = statement.trim() !== '' && statement.trim() !== COMPANION_STATEMENT_SEED.trim();
  const prompt = companionAgentPrompt(statement, status.data && status.data.trailsDirectory);
  const copy = () => {
    setCopyErr(false);
    navigator.clipboard.writeText(prompt)
      .then(() => {
        setCopied(true);
        if (onCopied) onCopied({ prompt, copiedAt: Date.now() });
      })
      .catch(() => {
        setCopyErr(true);
        setPreview(true);
        // The brief is selectable below even without Clipboard API access. Start watching now so
        // a companion opened from that manual paste is discovered without another navigation.
        if (onCopied) onCopied({ prompt, copiedAt: Date.now(), manual: true });
      });
  };
  const root = status.data && status.data.trailsDirectory;
  const branch = status.data && status.data.workspaceBranch;
  const isWorktree = !!(status.data && status.data.workspaceIsWorktree);
  return (
    <div className="tb-create-brief">
      <div>
        <label htmlFor="tb-create-intent" className="tb-create-field-label">Trail intent</label>
        <textarea ref={inputRef} value={statement} onChange={(e) => setStatement(e.target.value)} spellCheck={false}
          id="tb-create-intent"
          aria-label="What should this trail prove?"
          onKeyDown={(e) => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); e.currentTarget.blur(); } }}
          placeholder="e.g. Validates that a user can send $5 to a friend"
          className="tb-create-intent" />
        {!done && <div className="tb-create-field-hint">Finish the sentence to create the brief.</div>}
      </div>

      <div className="tb-create-context" aria-label="Trail context">
        <div className="tb-create-context-item">
          <Ico n="folder-git-2" s={16} c="var(--text-subtle-variant)" />
          <span><small>Workspace{isWorktree ? ' · worktree' : ''}</small><strong data-selectable title={root || ''}>{root || 'Loading workspace…'}</strong>{branch && <em>{branch}</em>}</span>
        </div>
        <button type="button" className="tb-create-context-item tb-create-target" onClick={onPickTarget}>
          <AppIcon target={gt && gt.target} size={22} radius={6} fallbackColor="var(--text-subtle-variant)" />
          <span><small>Target</small><strong>{gt ? (gt.label || gt.target) : 'Choose later'}</strong></span>
          <em>{gt ? 'Change' : 'Choose'}</em>
        </button>
      </div>

      <div className="tb-create-actions">
        <button type="button" className="tb-create-copy" disabled={!done} onClick={copy}>
          <Ico n={copied ? 'check' : 'copy'} s={15} />
          {copied ? 'Copy brief again' : 'Copy agent brief'}
        </button>
        <button type="button" className="tb-create-preview" aria-expanded={preview} onClick={() => setPreview((v) => !v)}>
          {preview ? 'Hide brief' : 'Preview brief'} <Ico n={preview ? 'chevron-up' : 'chevron-down'} s={13} />
        </button>
      </div>

      {copied && (
        <div className="tb-create-handoff" role="status">
          <Ico n={waiting ? 'loader-circle' : 'check-circle-2'} s={16} c={waiting ? 'var(--tb-running)' : 'var(--tb-pass)'} spin={waiting} />
          <div><strong>{waiting ? 'Waiting for your agent' : 'Brief copied'}</strong><span>Paste it into Codex, Claude Code, or another agent running in this workspace. Trail Runner will open its companion when it connects.</span></div>
          <Ico n="bot" s={18} c="var(--tb-ai)" />
        </div>
      )}

      {copyErr && <div role="alert" className="tb-create-copy-error">Couldn’t copy automatically. Select the brief below and copy it manually.</div>}
      {preview && (
        <pre data-selectable aria-label="Prompt for external AI agent" className="tb-create-prompt">
          <code className="tb-mono">{prompt}</code>
        </pre>
      )}
    </div>
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
  const devicesPending = devices.loading && !devices.data;
  const trailsPending = trails.loading && !trails.data;
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
      value: devicesPending ? 'checking…' : selectedConnected ? `${selectedConnected} connected` : 'none',
      hint: devicesPending ? 'Checking connected devices…' : selectedConnected ? `${selectedConnected} selected and connected` : deviceList.length ? 'Select a connected device for runs.' : 'Connect Android, iOS, or web.',
      ok: devicesPending ? null : selectedConnected > 0,
      action: 'home',
    },
    {
      label: 'Workspace',
      value: trailsPending ? 'checking…' : totalTrails ? `${totalTrails} trail${totalTrails === 1 ? '' : 's'}` : 'no trails',
      hint: trailsPending ? 'Checking the workspace…' : totalTrails ? `${totalTrails} saved trail${totalTrails === 1 ? '' : 's'} available` : 'No saved trails yet; create a trail to get started.',
      ok: trailsPending ? null : totalTrails > 0,
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

function HomeContent({ go }) {
  useLucide();
  const [showHelp, setShowHelp] = React.useState(false);

  return (
    <div style={{ minHeight: '100%', boxSizing: 'border-box' }}>
      <div style={{ maxWidth: 900, margin: '0 auto', padding: 'clamp(44px, 12vh, 148px) 30px 40px' }}>
        <ScreenHead
          title="Trail Runner"
          right={<HelpButton title="How Trail Runner works" onClick={() => setShowHelp(true)} />}
        />

        <div style={{ marginTop: 24 }}>
          <CreateOptions go={go} />
        </div>

        <div style={{ marginTop: 18 }}>
          <ReadinessPanel go={go} />
        </div>
      </div>

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

function Skeleton({ rows = 3, label = 'Loading' }) {
  return (
    <div className="tb-skeleton" role="status" aria-label={label} aria-busy="true">
      {Array.from({ length: rows }).map((_, i) => (
        <div className="tb-row" key={i} aria-hidden="true" style={{ marginBottom: 8 }}>
          <div className="tb-skel" style={{ width: 9, height: 9, borderRadius: 99 }}></div>
          <div style={{ flex: 1 }}>
            <div className="tb-skel" style={{ height: 10, width: '60%' }}></div>
            <div className="tb-skel" style={{ height: 8, width: '40%', marginTop: 5, opacity: .7 }}></div>
          </div>
        </div>
      ))}
    </div>
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
