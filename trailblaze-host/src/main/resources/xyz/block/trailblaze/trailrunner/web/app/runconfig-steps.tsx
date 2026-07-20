// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function RcInput({ value, onChange, placeholder, type, mono, style, onKeyDown }) {
  return (
    <input
      type={type || 'text'} value={value} placeholder={placeholder}
      onChange={(e) => onChange(e.target.value)} onKeyDown={onKeyDown}
      className={mono ? 'tb-mono' : undefined}
      style={{
        background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8,
        padding: '8px 11px', fontSize: 13, color: 'var(--text-standard)', fontFamily: mono ? undefined : 'inherit', outline: 'none',
        width: '100%', ...style,
      }}
    />
  );
}

function RcSelect({ value, onChange, options, children, title, style }) {
  return <Select full value={value} onChange={(e) => onChange(e.target.value)} options={options} title={title} style={style}>{children}</Select>;
}

function Field({ flag, desc, children, full, ico, preview }) {
  return (
    <div style={preview
      ? { margin: '10px 0', padding: '11px 13px', border: '1px dashed var(--tb-hairline-strong)', borderRadius: 10, opacity: .68 }
      : { padding: '13px 0', borderBottom: '1px solid var(--tb-hairline)' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
            {ico && (typeof ico === 'string' ? <Ico n={ico} s={15} c="var(--text-subtle)" /> : ico)}
            <span className="tb-mono" style={{ fontSize: 13, color: 'var(--text-standard)', fontWeight: 600 }}>{flag}</span>
            {preview && <Chip>Not built yet</Chip>}
          </div>
          {desc && <div className="tb-sub" style={{ fontSize: 12, lineHeight: 1.5, marginTop: 4 }}>{desc}</div>}
          {full && <div style={{ marginTop: 9 }}>{children}</div>}
        </div>
        {!full && <div style={{ flex: '0 0 auto' }}>{children}</div>}
      </div>
    </div>
  );
}

// One condensed line: mono flag + small inline description on the left, a single
// control (switch / select / input) on the right. Used by the Behavior and Capture
// sections so a long list of toggles stays scannable in the single-page layout.
// No row divider — white space separates the rows (Tufte 1+1=3); grouping is done
// by ToggleGroup's labelled clusters instead.
function CompactField({ flag, desc, children }) {
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '7px 0' }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <span className="tb-mono" style={{ fontSize: 12.5, color: 'var(--text-standard)', fontWeight: 600 }}>{flag}</span>
        {desc && <span className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.4, marginLeft: 8 }}>{desc}</span>}
      </div>
      <div style={{ flex: '0 0 auto' }}>{children}</div>
    </div>
  );
}

const ToggleRow = ({ flag, desc, on, set }) => (
  <CompactField flag={flag} desc={desc}><Switch on={on} onClick={() => set(!on)} /></CompactField>
);

// A labelled cluster of compact rows. The eyebrow + the gap below it carry the
// grouping that per-row rules used to (badly) imply.
function ToggleGroup({ label, children }) {
  return (
    <div style={{ marginBottom: 16 }}>
      {label && <div className="tb-eyebrow" style={{ marginBottom: 3 }}>{label}</div>}
      {children}
    </div>
  );
}

// A jump-to-anchor section: the header doubles as the scroll target the left nav
// scrolls to. `id` matches the `data-section` the dialog's scroll-spy reads.
function Section({ id, n, title, sub, ico, children, registerRef }) {
  return (
    <section data-section={id} ref={registerRef ? (el) => registerRef(id, el) : null} style={{ scrollMarginTop: 12, paddingBottom: 8 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 6 }}>
        <div style={{ flex: '0 0 auto', width: 30, height: 30, borderRadius: 9, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', display: 'grid', placeItems: 'center', marginTop: 1 }}>
          <Ico n={ico} s={16} c="var(--text-subtle)" />
        </div>
        <div style={{ minWidth: 0 }}>
          <h2 style={{ fontSize: 18, fontWeight: 600, margin: 0, lineHeight: 1.25 }}>{title}</h2>
          {sub && <p className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5, margin: '3px 0 0' }}>{sub}</p>}
        </div>
      </div>
      <div style={{ paddingLeft: 42 }}>{children}</div>
    </section>
  );
}

function TargetSection({ devices, deviceId, setDeviceId, connectedId, installedTargets = [], targetApp, setTargetApp, appsLoading, declaredTarget }) {
  const sel = devices.find((d) => d.id === deviceId) || null;
  return (
    <div>
      <Field flag="Device" ico={sel ? <PlatformGlyph platform={sel.platform} s={15} c="var(--text-subtle)" /> : 'smartphone'} full
        desc="The simulator, emulator, or browser this trail drives.">
        {devices.length === 0
          ? <span className="tb-sub" style={{ fontSize: 12.5 }}>No device connected - open Devices to connect one.</span>
          : <RcSelect value={deviceId || ''} onChange={setDeviceId}
              options={devices.map((d) => [d.id, d.name + (connectedId === d.id ? '  ✓ connected' : '')])} />}
      </Field>
      <Field flag="Target app" ico="package" full
        desc={'The app under test, as installed on the selected device.'
          + (declaredTarget && installedTargets.some((a) => a.id === declaredTarget)
            ? ` This trail declares '${declaredTarget}', so it's preselected.` : '')}>
        {declaredTarget && installedTargets.length > 0 && !installedTargets.some((a) => a.id === declaredTarget) && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6, color: 'var(--tb-warn, #e0a800)', fontSize: 12.5 }}>
            <Ico n="triangle-alert" s={13} c="var(--tb-warn, #e0a800)" />
            <span>This trail declares target '{declaredTarget}', which isn't resolvable on this device - the run is blocked unless you explicitly pick an app below to run against instead.</span>
          </div>
        )}
        {sel && sel.platform === 'web'
          ? <span className="tb-sub" style={{ fontSize: 12.5 }}>Web runs drive the browser - no installed app to pick.</span>
          : installedTargets.length > 0
            ? <Select full value={targetApp || ''} onChange={(e) => setTargetApp(e.target.value)}
                options={installedTargets.map((a) => ({
                  value: a.id,
                  short: (a.displayName || a.id) + (a.versionName ? ` (${a.versionName})` : ''),
                  label: (
                    <span style={{ display: 'flex', flexDirection: 'column', gap: 2, minWidth: 0 }}>
                      <span style={{ fontWeight: 600 }}>{(a.displayName || a.id) + (a.versionName ? ` (${a.versionName})` : '')}</span>
                      <span className="tb-mono tb-sub" style={{ fontSize: 11 }}>{a.appId}{a.versionCode ? ' - ' + a.versionCode : ''}{a.buildNumber ? ' · build ' + a.buildNumber : ''}</span>
                    </span>
                  ),
                }))} />
            : <span className="tb-sub" style={{ fontSize: 12.5 }}>{appsLoading ? 'Checking the device…' : 'No known target apps installed on this device.'}</span>}
      </Field>
    </div>
  );
}

function BehaviorSection(p) {
  return (
    <div>
      <ToggleRow flag="--self-heal" desc="When a recorded step fails, let AI take over and continue." on={p.selfHeal} set={p.setSelfHeal} />
      <CompactField flag="--use-recorded-steps" desc="Replay recorded tools vs let the LLM drive.">
        <RcSelect value={p.useRecordedSteps} onChange={p.setUseRecordedSteps} style={{ minWidth: 210 }}
          options={[['auto', 'Auto (replay if recorded)'], ['replay', 'Replay (--use-recorded-steps)'], ['ai', 'AI (--no-use-recorded-steps)']]} />
      </CompactField>
      <CompactField flag="model" desc="Model that drives AI steps.">
        <ModelPicker />
      </CompactField>
      <CompactField flag="-a, --agent" desc="Which agent runner drives the objective.">
        <RcSelect value={p.agent} onChange={p.setAgent} style={{ minWidth: 200 }}
          options={[['TRAILBLAZE_RUNNER', 'TRAILBLAZE_RUNNER'], ['MULTI_AGENT_V3', 'MULTI_AGENT_V3'], ['KOOG_STRATEGY_GRAPH', 'KOOG_STRATEGY_GRAPH']]} />
      </CompactField>
      <CompactField flag="--max-llm-calls" desc="Cap LLM calls per objective (TRAILBLAZE_RUNNER only).">
        <RcInput value={p.maxLlmCalls} onChange={p.setMaxLlmCalls} type="number" style={{ width: 90 }} />
      </CompactField>
      <CompactField flag="--llm" desc="Provider / model shorthand.">
        <RcInput value={p.llm} onChange={p.setLlm} placeholder="openai/gpt-4-1" style={{ width: 200 }} />
      </CompactField>
      <ToggleRow flag="--verbose" desc="Verbose logging." on={p.verbose} set={p.setVerbose} />
      {p.web && <ToggleRow flag="--headless" desc="Run the Playwright browser headless (web)." on={p.headless} set={p.setHeadless} />}
    </div>
  );
}

function CaptureSection(p) {
  return (
    <div>
      <ToggleGroup label="Artifacts">
        <ToggleRow flag="--capture-video" desc="Record a video of the run." on={p.captureVideo} set={p.setCaptureVideo} />
        <ToggleRow flag="--capture-logcat" desc="Capture Android logcat." on={p.captureLogcat} set={p.setCaptureLogcat} />
        <ToggleRow flag="--capture-network" desc="Capture network traffic." on={p.captureNetwork} set={p.setCaptureNetwork} />
        <ToggleRow flag="--capture-ios-logs" desc="Capture iOS simulator system logs." on={p.captureIosLogs} set={p.setCaptureIosLogs} />
        <ToggleRow flag="--capture-analytics" desc="Capture analytics events so they show in the timeline." on={p.captureAnalytics} set={p.setCaptureAnalytics} />
        <ToggleRow flag="Event streams" desc="Capture host-provided event streams and interlace them into the run timeline. Turns on network capture." on={p.captureEvents} set={p.setCaptureEvents} />
        <ToggleRow flag="--save-recording" desc="Save the recording back after a successful run." on={p.saveRecording} set={p.setSaveRecording} />
      </ToggleGroup>
      <ToggleGroup label="Reports & logging">
        <ToggleRow flag="--no-report" desc="Skip HTML report." on={p.noReport} set={p.setNoReport} />
        <ToggleRow flag="--markdown" desc="Generate a markdown report." on={p.markdown} set={p.setMarkdown} />
        <ToggleRow flag="--no-logging" desc="Disable session logging." on={p.noLogging} set={p.setNoLogging} />
      </ToggleGroup>
      <ToggleGroup label="Filter">
        <CompactField flag="--tags" desc="Only run trails with these tags.">
          <RcInput value={p.tags} onChange={p.setTags} placeholder="smoke,login" style={{ width: 180 }} />
        </CompactField>
      </ToggleGroup>
    </div>
  );
}

function RightRail({ trail, detail, liveYaml, tab, setTab, targetId, driver, platform }) {
  const steps = detail.data?.steps || [];
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%' }}>
      <div style={{ display: 'flex', gap: 10, alignItems: 'flex-start', padding: '18px 18px 12px' }}>
        <Ico n="file-text" s={20} c="var(--text-subtle)" style={{ marginTop: 2, flex: '0 0 auto' }} />
        <div style={{ minWidth: 0 }}>
          <div className="tb-eyebrow" style={{ marginBottom: 5 }}>Running this trail</div>
          <div style={{ fontSize: 14.5, fontWeight: 600, lineHeight: 1.3 }}>{trail.title || trail.id}</div>
          <div className="tb-mono tb-sub" style={{ fontSize: 11, marginTop: 3, wordBreak: 'break-all' }}>{trail.path || trail.id}</div>
        </div>
      </div>
      <div style={{ padding: '0 18px 12px' }}>
        <div style={{ display: 'flex', gap: 4, padding: 4, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 10 }}>
          {[['steps', 'Steps', 'list'], ['yaml', 'YAML', 'code'], ['tools', 'Tools', 'box']].map(([id, label, ico]) => {
            const on = tab === id;
            return (
              <button key={id} onClick={() => setTab(id)}
                style={{ flex: 1, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', gap: 7, padding: '7px 10px', borderRadius: 7, cursor: 'pointer', fontSize: 12.5, fontWeight: 600,
                  border: '1px solid ' + (on ? 'var(--tb-hairline-strong)' : 'transparent'),
                  background: on ? 'var(--bg-subtle)' : 'transparent',
                  color: on ? 'var(--text-standard)' : 'var(--text-subtle)' }}>
                <Ico n={ico} s={14} />{label}
              </button>
            );
          })}
        </div>
      </div>
      <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 18px 18px' }}>
        {tab === 'steps' ? (
          <>
            {detail.loading && <div className="tb-sub" style={{ fontSize: 12 }}>Loading steps…</div>}
            {!detail.loading && steps.length === 0 && (
              <div className="tb-sub" style={{ fontSize: 12 }}>No recorded steps - the agent will drive from the objective.</div>
            )}
            <div style={{ display: 'flex', flexDirection: 'column' }}>
              {/* The trailhead (step 0) keeps its own kind and shows unnumbered above the trail steps. */}
              {(() => { let n = 0; return steps.map((s, i) => <RailStep key={i} idx={s.kind === 'trailhead' ? null : n++} step={s} last={i === steps.length - 1} />); })()}
            </div>
          </>
        ) : tab === 'tools' ? (
          <RunToolsPanel targetId={targetId} driver={driver} platform={platform} />
        ) : (
          <SearchableText text={liveYaml} language="yaml" fontSize={12} minHeight={120} />
        )}
      </div>
    </div>
  );
}

function RailStep({ idx, step, last }) {
  const isTrailhead = step.kind === 'trailhead';
  const isVerify = step.kind === 'verify';
  const tools = step.tools || [];
  return (
    <div style={{ display: 'flex', gap: 12, paddingBottom: last ? 0 : 18 }}>
      <span className="tb-mono" style={{ flex: '0 0 auto', minWidth: 14, textAlign: 'right', fontSize: 12, color: 'var(--text-subtle-variant)', lineHeight: '20px' }}>
        {isTrailhead ? <Ico n="flag" s={12} c="var(--tb-primary-green)" /> : idx + 1}
      </span>
      <div style={{ minWidth: 0, flex: 1 }}>
        <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
          <Chip tone={isTrailhead ? 'green' : isVerify ? 'blue' : 'purple'}>{isTrailhead ? 'TRAILHEAD' : isVerify ? 'VERIFY' : 'STEP'}</Chip>
          <span style={{ fontSize: 13, color: 'var(--text-standard)', lineHeight: 1.4, wordBreak: 'break-word' }}>{step.text || tools[0] || 'step'}</span>
        </div>
        {tools.length > 0 && (
          <div className="tb-mono" style={{ display: 'flex', gap: 6, alignItems: 'center', marginTop: 7, fontSize: 11, color: 'var(--text-subtle-variant)' }}>
            <Ico n="box" s={12} c="var(--text-subtle-variant)" style={{ flex: '0 0 auto' }} />
            <span style={{ wordBreak: 'break-word' }}>{tools.join(' · ')}</span>
          </div>
        )}
      </div>
    </div>
  );
}

// "Tools for this run" — the toolsets (and the tools inside each) that actually register
// for the selected target + device driver, resolved server-side by /api/run-tools.
function RunToolsPanel({ targetId, driver, platform }) {
  const rt = TB.useRunTools(targetId, driver, platform);
  const data = rt.data;
  if (!targetId) {
    return <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5 }}>Pick a target app on the Target step to see the toolsets and tools that register for this run.</div>;
  }
  if (rt.loading && !data) return <div className="tb-sub" style={{ fontSize: 12 }}>Resolving tools for this target…</div>;
  if (!data || !data.resolved) {
    return <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5 }}>Couldn&apos;t resolve tools for this target on the selected device.</div>;
  }
  const toolsets = data.toolsets || [];
  if (toolsets.length === 0) {
    return <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5 }}>No toolsets register for this target.</div>;
  }
  const totalTools = new Set(toolsets.flatMap((ts) => ts.tools)).size;
  return (
    <div data-testid="run-tools-panel">
      <div className="tb-eyebrow" style={{ marginBottom: 9 }}>{toolsets.length} toolset{toolsets.length === 1 ? '' : 's'} · {totalTools} tool{totalTools === 1 ? '' : 's'} register</div>
      {toolsets.map((ts) => <ToolSetRow key={ts.id} ts={ts} />)}
    </div>
  );
}

function ToolSetRow({ ts }) {
  const [open, setOpen] = React.useState(false);
  return (
    <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, background: 'var(--bg-standard)', marginBottom: 8, overflow: 'hidden' }}>
      <button onClick={() => setOpen((o) => !o)}
        style={{ display: 'flex', alignItems: 'center', gap: 9, width: '100%', textAlign: 'left', padding: '10px 12px', background: 'none', border: 'none', cursor: 'pointer' }}>
        <Ico n={open ? 'chevron-down' : 'chevron-right'} s={14} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
        <Ico n="boxes" s={14} c="var(--tb-primary-green)" style={{ flex: '0 0 auto' }} />
        <span className="tb-mono" style={{ fontSize: 12.5, fontWeight: 600, flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', color: 'var(--text-standard)' }}>{ts.id}</span>
        {ts.alwaysEnabled && <Chip tone="green">always on</Chip>}
        <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto', fontVariantNumeric: 'tabular-nums' }}>{ts.tools.length}</span>
      </button>
      {open && (
        <div style={{ padding: '0 12px 12px 35px', borderTop: '1px solid var(--tb-hairline)' }}>
          {ts.description
            ? <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, margin: '9px 0' }}>{ts.description}</div>
            : <div style={{ height: 9 }} />}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
            {ts.tools.length === 0
              ? <span className="tb-sub" style={{ fontSize: 11.5 }}>No tools listed.</span>
              : ts.tools.map((t) => (
                <span key={t} className="tb-mono" style={{ fontSize: 11, padding: '3px 7px', borderRadius: 6, background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', color: 'var(--text-subtle-variant)', wordBreak: 'break-all' }}>{t}</span>
              ))}
          </div>
        </div>
      )}
    </div>
  );
}

// Sections shown top-to-bottom in the single-page config. The left rail jumps to
// each; the dialog's scroll-spy highlights the one in view.
const SECTIONS = [
  ['target', 'Target', 'The device and the app under test on it.', 'crosshair'],
  ['behavior', 'Behavior', 'How the agent drives. Defaults replay the recorded steps.', 'bot'],
  ['capture', 'Capture', 'Artifacts recorded during the run. Video and recording on by default.', 'clapperboard'],
];

// The nav items double as a live summary of the run: each carries the current
// value for its section (device, context counts, agent, artifact count). Fills
// what would otherwise be dead rail space with glanceable state.
function SectionNav({ active, onJump, summaries = {} }) {
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: '10px 8px' }}>
      {SECTIONS.map(([id, name, , ico]) => {
        const on = active === id;
        const summary = summaries[id];
        return (
          <button key={id} onClick={() => onJump(id)}
            style={{ display: 'flex', alignItems: 'center', gap: 11, textAlign: 'left', padding: '9px 12px', borderRadius: 10, cursor: 'pointer',
              border: '1px solid ' + (on ? 'var(--tb-hairline-strong)' : 'transparent'),
              background: on ? 'var(--bg-standard)' : 'transparent' }}>
            <span style={{ flex: '0 0 auto', width: 26, height: 26, borderRadius: 8, display: 'grid', placeItems: 'center',
              background: on ? 'var(--tb-primary-green)' : 'var(--bg-standard)',
              border: on ? 'none' : '1px solid var(--tb-hairline-strong)' }}>
              <Ico n={ico} s={14} c={on ? '#04210a' : 'var(--text-subtle)'} />
            </span>
            <span style={{ minWidth: 0, flex: 1 }}>
              <span style={{ display: 'block', fontSize: 13.5, fontWeight: 600, color: on ? 'var(--text-standard)' : 'var(--text-subtle)' }}>{name}</span>
              {summary && <span className="tb-sub" style={{ display: 'block', fontSize: 11, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{summary}</span>}
            </span>
          </button>
        );
      })}
    </div>
  );
}

Object.assign(window, {
  RcInput, RcSelect, Field, CompactField, ToggleRow, ToggleGroup, Section, TargetSection,
  BehaviorSection, CaptureSection,
  RightRail, RailStep, RunToolsPanel, ToolSetRow, SECTIONS, SectionNav,
});
