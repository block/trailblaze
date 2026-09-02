// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// The build-time transpile strips types regardless, so the browser runtime is unaffected.
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

function Field({ flag, children, full, ico, preview }) {
  return (
    <div style={preview
      ? { margin: '10px 0', padding: '11px 13px', border: '1px dashed var(--tb-hairline-strong)', borderRadius: 10, opacity: .68 }
      : { padding: '13px 0', borderBottom: '1px solid var(--tb-hairline)' }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 14 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7 }}>
            {ico && (typeof ico === 'string' ? <Ico n={ico} s={15} c="var(--text-subtle)" /> : ico)}
            <span style={{ fontSize: 13, color: 'var(--text-standard)', fontWeight: 600 }}>{flag}</span>
            {preview && <Chip>Not built yet</Chip>}
          </div>
          {full && <div style={{ marginTop: 9 }}>{children}</div>}
        </div>
        {!full && <div style={{ flex: '0 0 auto' }}>{children}</div>}
      </div>
    </div>
  );
}

// One condensed line: a direct UI label on the left and a single control on the
// right. CLI details live in the generated command below the form. Used by Behavior and Capture
// sections so a long list of toggles stays scannable in the single-page layout.
// No row divider — white space separates the rows (Tufte 1+1=3); grouping is done
// by ToggleGroup's labelled clusters instead.
function CompactField({ label, flag, children }) {
  return (
    <div className="tb-run-field" style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '7px 0' }}>
      <div style={{ flex: 1, minWidth: 0 }}>
        <span style={{ fontSize: 12.5, color: 'var(--text-standard)', fontWeight: 600 }}>{label || flag}</span>
      </div>
      <div style={{ flex: '0 0 auto' }}>{children}</div>
    </div>
  );
}

const ToggleRow = ({ label, flag, on, set }) => (
  <CompactField label={label} flag={flag}><Switch on={on} onClick={() => set(!on)} /></CompactField>
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

// One titled block of the Run dialog's form column.
function Section({ id, title, ico, children }) {
  return (
    <section data-section={id} style={{ paddingBottom: 8 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 12, marginBottom: 6 }}>
        <div style={{ flex: '0 0 auto', width: 30, height: 30, borderRadius: 9, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', display: 'grid', placeItems: 'center', marginTop: 1 }}>
          <Ico n={ico} s={16} c="var(--text-subtle)" />
        </div>
        <div style={{ minWidth: 0 }}>
          <h2 style={{ fontSize: 18, fontWeight: 600, margin: 0, lineHeight: 1.25 }}>{title}</h2>
        </div>
      </div>
      <div style={{ paddingLeft: 42 }}>{children}</div>
    </section>
  );
}

// The device picker is a multi-select: a trail runs once on each checked device (one run, one
// session per device), and the FIRST checked device is the primary one whose installed apps drive
// the target-app picker below.
// `launching` locks the checkboxes: the launch has already captured which devices it went out to, so
// a check landing mid-flight would neither join the run nor survive the outcome (a partial launch
// narrows the selection to what didn't start).
function TargetSection({ devices, deviceIds = [], toggleDevice, connectedIds = [], appsDevice = null, installedTargets = [], targetApp, setTargetApp, appsLoading, declaredTarget, launching }) {
  const sel = devices.find((d) => d.id === deviceIds[0]) || null;
  return (
    <div>
      <Field flag="Devices" ico={sel ? <PlatformGlyph platform={sel.platform} s={15} c="var(--text-subtle)" /> : 'smartphone'} full>
        {devices.length === 0
          ? <span className="tb-sub" style={{ fontSize: 12.5 }}>No connected devices.</span>
          : <div data-testid="run-device-picker" style={{ display: 'flex', flexDirection: 'column', gap: 1, margin: '0 -8px' }}>
              {devices.map((d) => {
                const on = deviceIds.includes(d.id);
                return (
                  <label key={d.id} className={'tb-pick' + (on ? ' is-on' : '') + (launching ? ' is-static' : '')}
                    style={{ alignItems: 'center', fontSize: 13, opacity: launching ? 0.6 : 1 }}>
                    <input className="tb-pick-input" type="checkbox" checked={on} onChange={() => toggleDevice(d.id)}
                      disabled={!!launching} aria-label={`Run on ${d.name}`} />
                    <span className="tb-pick-box" style={{ marginTop: 0 }}><Ico n="check" s={11} c="currentColor" /></span>
                    <span style={{ flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{d.name}</span>
                    {/* Right-aligned rather than trailing each name: the statuses then form one column
                        instead of stepping in and out with the length of every device name. */}
                    {connectedIds.includes(d.id) && <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto' }}>connected</span>}
                  </label>
                );
              })}
              <span className="tb-sub" style={{ fontSize: 11.5, marginTop: 5, paddingLeft: 33 }}>
                {deviceIds.length > 1
                  ? `Runs this trail on all ${deviceIds.length} checked devices - one run each.`
                  : 'Check more devices to run this trail on each of them.'}
              </span>
            </div>}
      </Field>
      {/* Shown for the checked device the apps were listed FROM, which is not always the primary
          one: a mixed-platform run can start with a web device, which hosts no app to pick. */}
      {appsDevice && <Field flag="Target app" ico="package" full>
        {deviceIds.length > 1 && (
          <div className="tb-sub" style={{ fontSize: 11.5, marginBottom: 6 }}>
            Listed from {appsDevice.name}; every run uses the app picked here.
          </div>
        )}
        {declaredTarget && !appsLoading && !targetApp && !installedTargets.some((a) => a.id === declaredTarget) && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, marginBottom: 6, color: 'var(--tb-warn, #e0a800)', fontSize: 12.5 }}>
            <Ico n="triangle-alert" s={13} c="var(--tb-warn, #e0a800)" />
            <span>This trail declares target '{declaredTarget}', which isn't resolvable on this device - the run is blocked unless you explicitly pick an app below to run against instead.</span>
          </div>
        )}
        {installedTargets.length > 0
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
      </Field>}
    </div>
  );
}

function BehaviorSection(p) {
  return (
    <div>
      <ToggleRow label="Self-heal failures" flag="--self-heal" on={p.selfHeal} set={p.setSelfHeal} />
      <CompactField label="Step execution" flag="--use-recorded-steps">
        <RcSelect value={p.useRecordedSteps} onChange={p.setUseRecordedSteps} style={{ minWidth: 210 }}
          options={[['auto', 'Automatic'], ['replay', 'Recorded replay'], ['ai', 'AI-driven']]} />
      </CompactField>
      <CompactField label="Model">
        <ModelPicker />
      </CompactField>
      <CompactField label="Agent" flag="--agent">
        <RcSelect value={p.agent} onChange={p.setAgent} style={{ minWidth: 200 }}
          options={[['TRAILBLAZE_RUNNER', 'TRAILBLAZE_RUNNER'], ['MULTI_AGENT_V3', 'MULTI_AGENT_V3'], ['KOOG_STRATEGY_GRAPH', 'KOOG_STRATEGY_GRAPH']]} />
      </CompactField>
      <CompactField label="AI call limit" flag="--max-llm-calls">
        <RcInput value={p.maxLlmCalls} onChange={p.setMaxLlmCalls} type="number" style={{ width: 90 }} />
      </CompactField>
      <CompactField label="Model override" flag="--llm">
        <RcInput value={p.llm} onChange={p.setLlm} placeholder="openai/gpt-4-1" style={{ width: 200 }} />
      </CompactField>
      <ToggleRow label="Verbose logs" flag="--verbose" on={p.verbose} set={p.setVerbose} />
      {p.web && <ToggleRow label="Headless browser" flag="--headless" on={p.headless} set={p.setHeadless} />}
    </div>
  );
}

function CaptureSection(p) {
  return (
    <div>
      <ToggleGroup label="Artifacts">
        <ToggleRow label="Run video" flag="--capture-video" on={p.captureVideo} set={p.setCaptureVideo} />
        <ToggleRow label="Android logs" flag="--capture-logcat" on={p.captureLogcat} set={p.setCaptureLogcat} />
        <ToggleRow label="Network traffic" flag="--capture-network" on={p.captureNetwork} set={p.setCaptureNetwork} />
        <ToggleRow label="iOS system logs" flag="--capture-ios-logs" on={p.captureIosLogs} set={p.setCaptureIosLogs} />
        <ToggleRow label="Analytics events" flag="--capture-analytics" on={p.captureAnalytics} set={p.setCaptureAnalytics} />
        <ToggleRow label="Event streams" on={p.captureEvents} set={p.setCaptureEvents} />
        <ToggleRow label="Save recording" flag="--save-recording" on={p.saveRecording} set={p.setSaveRecording} />
      </ToggleGroup>
      <ToggleGroup label="Reports & logging">
        <ToggleRow label="Skip HTML report" flag="--no-report" on={p.noReport} set={p.setNoReport} />
        <ToggleRow label="Markdown report" flag="--markdown" on={p.markdown} set={p.setMarkdown} />
        <ToggleRow label="Disable session logs" flag="--no-logging" on={p.noLogging} set={p.setNoLogging} />
      </ToggleGroup>
      <ToggleGroup label="Filter">
        <CompactField label="Tags" flag="--tags">
          <RcInput value={p.tags} onChange={p.setTags} placeholder="smoke,login" style={{ width: 180 }} />
        </CompactField>
      </ToggleGroup>
    </div>
  );
}

// The right-hand pane of the Run dialog: WHICH steps this run covers, and the control that changes
// it. Every step is selectable, because running a trail is usually not running the whole trail - the
// device is already in the state the earlier steps would put it in, so only the tail is worth
// running.
//
// The selection is a contiguous span by construction. A run window is one `from..to`: it is what
// gets sliced into the dispatched trail, and for a Record run it is also the window the recording is
// merged back into, so a non-contiguous set has nothing to mean.
//
// `steps` are the trail's authored steps (the unified `trail:` list, whose indices the server means
// by a step window). A trail that has none - a legacy list-root file - falls back to the read-only
// step list from the trail detail and can only be run whole.
function RunStepsPane({ steps, trailhead, range, total, onPick, onAll, detailSteps, loading, tab, setTab, showTabs, liveYaml, targetId, driver, platform }) {
  // No span yet - the first render, before the dialog has read the trail's step count - reads as the
  // whole trail, which is also what it will settle on. Every span below is therefore non-null.
  const inRange = (i) => !range || (i >= range.start && i <= range.end);
  const whole = !range || (range.start === 0 && range.end === total - 1);
  return (
    <div style={{ display: 'flex', flexDirection: 'column', minHeight: 0, height: '100%' }}>
      {showTabs && (
        <div style={{ padding: '14px 16px 10px', flex: '0 0 auto' }}>
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
      )}
      {(!showTabs || tab === 'steps') && (
        <React.Fragment>
          <div style={{ flex: '0 0 auto', padding: showTabs ? '0 16px 10px' : '16px 16px 10px', borderBottom: '1px solid var(--tb-hairline)' }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
              <div style={{ fontSize: 13.5, fontWeight: 600, flex: 1, minWidth: 0 }}>
                {steps.length === 0 ? 'Steps' : whole ? `All ${total} step${total === 1 ? '' : 's'}` : (
                  range.start === range.end ? `Step ${range.start + 1} of ${total}` : `Steps ${range.start + 1}-${range.end + 1} of ${total}`
                )}
              </div>
              {steps.length > 0 && !whole && (
                <button data-testid="run-steps-all" onClick={onAll} className="tb-btn ghost sm">All steps</button>
              )}
            </div>
            <div className="tb-sub" style={{ fontSize: 11.5, lineHeight: 1.5, marginTop: 3 }}>
              {steps.length === 0
                ? 'This trail runs as a whole.'
                : whole
                  ? 'Click a step to run from there to the end instead. Shift-click to set the other end.'
                  : "Starts from whatever is on the device's screen right now - the trailhead is skipped."}
            </div>
          </div>
          <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '6px 8px 16px' }}>
            {loading && steps.length === 0 && detailSteps.length === 0 && <div className="tb-sub" style={{ fontSize: 12, padding: 6 }}>Loading steps…</div>}
            {steps.length === 0
              ? (
                <React.Fragment>
                  {!loading && detailSteps.length === 0 && (
                    <div className="tb-sub" style={{ fontSize: 12, padding: 6 }}>No recorded steps - the agent will drive from the objective.</div>
                  )}
                  {/* Read-only: a legacy list-root trail has no per-step recording slots, so neither a
                      slice nor a save-back has an index to name. */}
                  {(() => { let n = 0; return detailSteps.map((st, i) => <RailStep key={i} idx={st.kind === 'trailhead' ? null : n++} step={st} last={i === detailSteps.length - 1} />); })()}
                </React.Fragment>
              )
              : (
                <div data-testid="run-steps-picker" style={{ display: 'flex', flexDirection: 'column', gap: 1 }}>
                  {trailhead && (
                    // Not selectable: the trailhead is the run's deterministic step 0, and a window
                    // names steps of `trail:`. It runs only when the whole trail does, which is
                    // exactly what makes a partial run start from the current screen.
                    <div className="tb-pick is-static" style={{ opacity: whole ? 0.9 : 0.45 }}>
                      {/* Empty box column and the flag in the number column: the trailhead's prose then
                          starts on the same line as every step's, which is the point of the columns. */}
                      <span className="tb-pick-box" style={{ border: 0, background: 'transparent' }} />
                      <span className="tb-pick-num" style={{ display: 'grid', placeItems: 'center', height: 18 }}>
                        <Ico n="flag" s={12} c={whole ? 'var(--tb-primary-green)' : 'var(--text-subtle)'} />
                      </span>
                      <span style={{ minWidth: 0, flex: 1, fontSize: 12.5, lineHeight: 1.45 }}>
                        {trailhead.text || 'Trailhead'}
                        <span className="tb-sub" style={{ display: 'block', fontSize: 11 }}>{whole ? 'Runs first' : 'Skipped'}</span>
                      </span>
                    </div>
                  )}
                  {steps.map((st, i) => {
                    const on = inRange(i);
                    const recorded = Object.keys(st.recording || {}).some((k) => (st.recording[k] || []).length > 0);
                    return (
                      <button key={i} type="button" aria-pressed={on} className={'tb-pick' + (on ? ' is-on' : '')}
                        onClick={(e) => onPick(i, e.shiftKey)}
                        title={`Run from step ${i + 1} to the end (shift-click to end the range here)`}>
                        <span className="tb-pick-box"><Ico n="check" s={11} c="currentColor" /></span>
                        <span className="tb-pick-num">{i + 1}</span>
                        <span style={{ minWidth: 0, flex: 1 }}>
                          <span style={{ display: 'block', fontSize: 12.5, lineHeight: 1.45, wordBreak: 'break-word' }}>{st.text || 'step'}</span>
                          {/* Says what a Play of this step would actually do: with nothing recorded
                              for any device, replay has nothing to replay and the agent drives it. */}
                          {!recorded && <span className="tb-sub" style={{ display: 'block', fontSize: 10.5, marginTop: 1 }}>no recording - the agent drives this step</span>}
                        </span>
                        {st.kind === 'verify' && <span style={{ flex: '0 0 auto', marginTop: 1 }}><Chip tone="blue">VERIFY</Chip></span>}
                      </button>
                    );
                  })}
                </div>
              )}
          </div>
        </React.Fragment>
      )}
      {showTabs && tab !== 'steps' && (
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '0 16px 16px' }}>
          {tab === 'tools'
            ? <RunToolsPanel targetId={targetId} driver={driver} platform={platform} />
            : <SearchableText text={liveYaml} language="yaml" fontSize={12} minHeight={120} />}
        </div>
      )}
    </div>
  );
}

function RailStep({ idx, step, last }) {
  const isTrailhead = step.kind === 'trailhead';
  const isVerify = step.kind === 'verify';
  const tools = step.tools || [];
  return (
    <div style={{ display: 'flex', gap: 12, paddingBottom: last ? 0 : 18 }}>
      <span style={{ flex: '0 0 auto', minWidth: 14, textAlign: 'right', fontSize: 12, color: 'var(--text-subtle-variant)', lineHeight: '20px', fontVariantNumeric: 'tabular-nums' }}>
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

Object.assign(window, {
  RcInput, RcSelect, Field, CompactField, ToggleRow, ToggleGroup, Section, TargetSection,
  BehaviorSection, CaptureSection,
  RunStepsPane, RailStep, RunToolsPanel, ToolSetRow,
});
