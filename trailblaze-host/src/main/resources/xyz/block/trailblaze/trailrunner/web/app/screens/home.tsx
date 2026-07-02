// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

const QUICK_START_SAMPLES = [
  {
    id: 'search-wikipedia',
    env: 'web',
    title: 'Search Wikipedia',
    desc: 'Open Wikipedia, search for a topic, and verify the article content.',
    ico: 'search',
    yaml: `- prompts:
    - step: Navigate to https://en.wikipedia.org
    - step: Search for "Golden Gate Bridge"
    - step: Verify the article page for "Golden Gate Bridge" has loaded
    - step: Read the opening paragraph and confirm it mentions San Francisco
    - step: Scroll down to find the "History" section
    - step: Verify the History section is visible on the page`,
  },
  {
    id: 'trailblaze-releases',
    env: 'web',
    title: 'Explore releases',
    desc: 'Browse the Trailblaze releases page and inspect the latest release notes.',
    ico: 'globe',
    yaml: `- prompts:
    - step: Navigate to https://github.com/block/trailblaze
    - step: Click on the "Releases" section
    - step: Browse the available releases
    - step: Click on the latest release to learn more about it
    - step: Read the release notes and describe what changed
    - step: Find the download assets listed for the release`,
  },
  {
    id: 'set-alarm',
    env: 'mobile',
    title: 'Set an alarm',
    desc: 'Open Clock, create a morning alarm, and verify it appears in the list.',
    ico: 'alarm-clock',
    yaml: `- prompts:
    - step: Open the Clock app
    - step: Navigate to the Alarms tab
    - step: Create a new alarm set for 7:30 AM
    - step: Save the alarm
    - step: Verify the alarm for 7:30 AM is visible in the list`,
  },
  {
    id: 'add-contact',
    env: 'mobile',
    title: 'Add a contact',
    desc: 'Create a sample contact and verify it is visible after saving.',
    ico: 'contact',
    yaml: `- prompts:
    - step: Open the Contacts app
    - step: Tap the button to add a new contact
    - step: Enter "Jane" as the first name
    - step: Enter "Doe" as the last name
    - step: Enter "555-0123" as the phone number
    - step: Save the contact
    - step: Verify the contact "Jane Doe" appears in the contacts list`,
  },
];

// One stage in the Create / Draft steps / Save pipeline shown on Home. Clickable when
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
// Each card jumps to its real stage: Create → Drafts (record) → Trails.
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
          title="Write a prompt" sub="Becomes ordered steps." onClick={() => go('create')}>
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
          title="Run on devices" sub="Each step records a real tool." onClick={() => go('drafts')}>
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

// The main ways to start a test.
function CreateOptions({ go }) {
  const opts = [
    { key: 'create', ico: 'sparkles', color: 'var(--tb-ai)', bg: 'rgba(181,140,255,.16)', kicker: 'Agent', title: 'Prompt', sub: 'Describe the flow in plain language. The agent proposes ordered steps you refine, then record.', cta: 'Write a prompt' },
    { key: 'interact', ico: 'pointer', color: 'var(--tb-running)', bg: 'rgba(94,155,255,.16)', kicker: 'Hands-on', title: 'Interact', sub: 'Drive a live device yourself. Every tap, type, and check becomes an editable step as you go.', cta: 'Open a device' },
    { key: 'yaml', ico: 'braces', color: 'var(--tb-amber)', bg: 'rgba(255,190,92,.14)', kicker: 'Ad hoc', title: 'Run YAML', sub: 'Paste or load a trail YAML definition and run it immediately on a connected device.', cta: 'Run YAML' },
    { key: 'import', ico: 'download', color: 'var(--tb-pass)', bg: 'rgba(76,209,148,.14)', kicker: 'Import', title: 'Import', sub: 'Bring in an existing test from another source and turn it into a trail.', cta: 'Import a file' },
  ];
  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'stretch', flexWrap: 'wrap' }}>
      {opts.map((o) => {
        return (
          <div key={o.key} className="tb-card" role="button" tabIndex={0}
            onClick={() => go(o.key)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(o.key); } }}
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

function QuickStartSamples({ go }) {
  const devices = TB.useDevices();
  const deviceList = devices.data || [];
  const hasWeb = deviceList.some((d) => d.platform === 'web' && d.connected !== false);
  const hasMobile = deviceList.some((d) => (d.platform === 'android' || d.platform === 'ios') && d.connected !== false);
  const grouped = [
    { id: 'web', label: 'Web', ready: hasWeb, empty: 'Connect a web target from Devices to run these immediately.' },
    { id: 'mobile', label: 'Mobile', ready: hasMobile, empty: 'Connect an Android or iOS device to run these immediately.' },
  ];
  const openSample = (sample) => go('yaml', { name: sample.title, yaml: sample.yaml });
  return (
    <div style={{ display: 'grid', gap: 12 }}>
      {grouped.map((group) => {
        const samples = QUICK_START_SAMPLES.filter((s) => s.env === group.id);
        return (
          <div key={group.id}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 8 }}>
              <div className="tb-eyebrow">{group.label}</div>
              <Chip tone={group.ready ? 'green' : 'amber'}>{group.ready ? 'Ready' : 'Needs device'}</Chip>
            </div>
            <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(210px, 1fr))', gap: 10 }}>
              {samples.map((sample) => (
                <div key={sample.id} className="tb-card" role="button" tabIndex={0}
                  onClick={() => openSample(sample)}
                  onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openSample(sample); } }}
                  style={{ padding: 14, cursor: 'pointer', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column', gap: 10, minWidth: 0 }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 9 }}>
                    <div style={{ width: 32, height: 32, borderRadius: 9, background: group.id === 'web' ? 'rgba(94,155,255,.14)' : 'rgba(76,209,148,.14)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
                      <Ico n={sample.ico} s={16} c={group.id === 'web' ? 'var(--tb-running)' : 'var(--tb-pass)'} />
                    </div>
                    <div style={{ minWidth: 0, flex: 1 }}>
                      <div style={{ fontSize: 14, fontWeight: 700, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{sample.title}</div>
                      <div className="tb-mono tb-sub" style={{ fontSize: 10.5, marginTop: 2 }}>{group.label.toLowerCase()} sample</div>
                    </div>
                    <Ico n="arrow-right" s={14} c="var(--text-subtle)" />
                  </div>
                  <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.45, flex: 1 }}>{sample.desc}</div>
                </div>
              ))}
            </div>
            {!group.ready && <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, marginTop: 7 }}>{group.empty}</div>}
          </div>
        );
      })}
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
  const rows = [
    {
      label: 'Daemon',
      desc: status.data?.running ? `Running on port ${status.data.daemonPort || '?'}` : 'Start the daemon before running tests.',
      ok: !!status.data?.running,
      action: 'settings',
    },
    {
      label: 'Target',
      desc: gt && gt.target ? (gt.label || gt.target) : 'Choose the app or web target under test.',
      ok: !!(gt && gt.target),
      action: 'home',
    },
    {
      label: 'Devices',
      desc: selectedConnected ? `${selectedConnected} selected and connected` : deviceList.length ? 'Select a connected device for runs.' : 'Connect Android, iOS, or web.',
      ok: selectedConnected > 0,
      action: 'devices',
    },
    {
      label: 'Workspace',
      desc: totalTrails ? `${totalTrails} saved trail${totalTrails === 1 ? '' : 's'} available` : 'No saved trails yet; create, import, or run a sample.',
      ok: totalTrails > 0,
      action: totalTrails > 0 ? 'trails' : 'import',
    },
  ];
  return (
    <div className="tb-card" style={{ padding: 14, background: 'var(--bg-subtle)' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10 }}>
        <Ico n="activity" s={15} c="var(--tb-running)" />
        <span style={{ fontSize: 13.5, fontWeight: 700 }}>Readiness</span>
      </div>
      <div style={{ display: 'grid', gap: 7 }}>
        {rows.map((r) => (
          <div key={r.label} role="button" tabIndex={0}
            onClick={() => go(r.action)}
            onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go(r.action); } }}
            style={{ display: 'flex', alignItems: 'center', gap: 9, padding: '8px 0', cursor: 'pointer', borderTop: '1px solid var(--tb-hairline)' }}>
            <Ico n={r.ok ? 'circle-check-big' : 'circle-alert'} s={15} c={r.ok ? 'var(--tb-pass)' : 'var(--tb-amber)'} />
            <div style={{ flex: 1, minWidth: 0 }}>
              <div style={{ fontSize: 12.5, fontWeight: 600 }}>{r.label}</div>
              <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{r.desc}</div>
            </div>
            <Ico n="chevron-right" s={13} c="var(--text-subtle)" />
          </div>
        ))}
      </div>
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
// with how many saved trails are in the workspace. Pairs with Recent runs as the two ways to
// re-run something that already exists.
function BrowseTests({ go }) {
  const trails = TB.useTrails();
  const count = TB.countTrailBundles(trails.data || []);
  return (
    <div className="tb-card" role="button" tabIndex={0} data-testid="browse-tests"
      onClick={() => go('trails')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go('trails'); } }}
      style={{ padding: '14px 16px', display: 'flex', alignItems: 'center', gap: 13, cursor: 'pointer', marginBottom: 22, background: 'var(--bg-subtle)' }}>
      <div style={{ width: 38, height: 38, borderRadius: 10, background: 'rgba(94,155,255,.14)', display: 'flex', alignItems: 'center', justifyContent: 'center', flex: '0 0 auto' }}>
        <Ico n="route" s={19} c="var(--tb-running)" />
      </div>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ fontSize: 14.5, fontWeight: 700 }}>Browse tests</div>
        <div className="tb-sub" style={{ fontSize: 12, marginTop: 2 }}>{count ? `${count} saved trail${count === 1 ? '' : 's'} in your workspace - pick one and run it.` : 'Open the trail library to pick a saved test and run it.'}</div>
      </div>
      <Ico n="arrow-right" s={16} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
    </div>
  );
}

// Recent runs list for the Run section. Was a standalone block at the bottom of Home; now lives
// under "Run an existing test" beside Browse tests.
function RecentRuns({ go }) {
  const sessions = TB.useSessions();
  const recent = (sessions.data || []).slice(0, 4);
  // Hide the whole section on Home when there are no runs (and loading is done) — no empty-state
  // clutter; it reappears the moment a run lands.
  if (!sessions.loading && recent.length === 0) return null;
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline', marginBottom: 11 }}>
        <div className="tb-eyebrow">Recent runs</div>
        <span role="button" tabIndex={0} style={{ fontSize: 12.5, color: 'var(--tb-running)', fontWeight: 600, cursor: 'pointer' }} onClick={() => go('completed')} onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); go('completed'); } }}>All runs →</span>
      </div>
      {sessions.loading && !sessions.data && <Skeleton rows={3} />}
      {recent.map((s) => (
        <div className="tb-row tb-enter" key={s.id} {...clickable(() => go('completed', { sel: s.id }))} style={{ marginBottom: 8, cursor: 'pointer' }}>
          <Dot c={STATUS[s.status][1]} s={9} />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 13.5, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{s.title}</div>
            <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 2 }}>{[s.target, s.device, s.dur].filter(Boolean).join(' · ')}</div>
          </div>
          <StatusChip s={s.status} />
          <span className="tb-sub" style={{ fontSize: 11.5, width: 60, textAlign: 'right' }}>{s.ago}</span>
        </div>
      ))}
    </div>
  );
}

function HomeContent({ go, openRun }) {
  useLucide();
  const [showHelp, setShowHelp] = React.useState(false);

  return (
    <div style={{ maxWidth: 940, margin: '0 auto', padding: '28px 30px' }}>
      <ScreenHead
        eyebrow="Trail Runner"
        title={<>What would you like to <span style={{ color: 'var(--tb-pass)' }}>do today?</span></>}
        sub="Start a new test or run an existing one."
        right={<HelpButton title="How Trail Runner works" onClick={() => setShowHelp(true)} />}
      />

      {/* Section 1 — author a new test: the three ways to start, with the teaching pipeline tucked
          behind a "?" popover on the header (no longer inline). */}
      <HomeSection first ico="sparkles" color="var(--tb-ai)" bg="rgba(181,140,255,.16)"
        title="Create a new test" sub="Describe a flow, then record it once on your devices."
        headerRight={
          <HelpButton title="How a test is made" sub="A prompt becomes ordered steps, you record them once on each device, and the saved trail replays forever — deterministic, zero AI.">
            <HomePipeline go={go} vertical />
          </HelpButton>
        }>
        <CreateOptions go={go} />
      </HomeSection>

      <HomeSection ico="rocket" color="var(--tb-amber)" bg="rgba(255,190,92,.14)"
        title="Quick start" sub="Load a sample trail into Run YAML, then run it on a connected device.">
        <QuickStartSamples go={go} />
      </HomeSection>

      {/* Section 2 — re-run something that already exists: browse the library or revisit a run. */}
      <HomeSection ico="circle-play" color="var(--tb-running)" bg="rgba(94,155,255,.16)"
        title="Run an existing test" sub="Browse your saved trails or revisit a recent run.">
        <BrowseTests go={go} />
        <RecentRuns go={go} />
      </HomeSection>

      {/* First-run status — mirrors the native app's environment status without duplicating the
          workspace chip's path details. */}
      <div style={{ marginTop: 34, paddingTop: 18, borderTop: '1px solid var(--tb-hairline)' }}>
        <ReadinessPanel go={go} />
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
            Under Drafts, run the blaze on the devices you picked. The agent performs the authored steps and captures the real tool calls, producing one recording per platform. Watch each run live under Active.
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

function HomeScreen({ go, openRun }) {
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
          <HomeContent go={go} openRun={openRun} />
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
function Empty({ ico, title, sub }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', textAlign: 'center', padding: '40px 24px', color: 'var(--text-subtle)' }}>
      <div style={{ width: 56, height: 56, borderRadius: 16, display: 'flex', alignItems: 'center', justifyContent: 'center', background: 'var(--bg-elevated)', border: '1px solid var(--tb-hairline)', marginBottom: 14 }}>
        <Ico n={ico} s={24} c="var(--text-subtle)" />
      </div>
      <div style={{ fontSize: 14.5, fontWeight: 600, color: 'var(--text-standard)' }}>{title}</div>
      {sub && <div className="tb-sub" style={{ fontSize: 12.5, marginTop: 5, maxWidth: 320, lineHeight: 1.5 }}>{sub}</div>}
    </div>
  );
}

Object.assign(window, { HomeScreen, Skeleton, EmptyState: Empty });
