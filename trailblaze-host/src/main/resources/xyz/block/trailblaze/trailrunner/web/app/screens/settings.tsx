// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

// Short descriptions for each agent implementation, shown in the Agents section so the choice is
// meaningful (the settings DTO only carries id + display name).
const AGENT_DESCRIPTIONS = {
  TRAILBLAZE_RUNNER: 'Legacy YAML runner - stable, battle-tested. The default for every run and recording.',
  MULTI_AGENT_V3: 'Planner + explorer multi-agent (Mobile-Agent-v3 style). Goal-oriented action planning for trail + blaze modes.',
  KOOG_STRATEGY_GRAPH: 'A single Koog strategy-graph that owns the agent loop. Opt-in successor - run it to A/B against the runner.',
};

// Layout primitives for the settings panes. Defined at MODULE scope (not inside SettingsScreen) so
// their component identity is stable across renders — otherwise every parent re-render (a status
// poll, a settings.reload) would remount the whole pane, and `NumberSetting`'s focused input would
// lose focus + reset mid-type.
function SettingsSection({ title, sub, children }) {
  return (
    <div className="tb-card" style={{ marginBottom: 14, overflow: 'hidden' }}>
      <div style={{ padding: '14px 18px', borderBottom: '1px solid var(--tb-hairline)' }}>
        <div style={{ fontSize: 14, fontWeight: 600 }}>{title}</div>
        {sub && <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 3 }}>{sub}</div>}
      </div>
      <div style={{ padding: '6px 18px 10px' }}>{children}</div>
    </div>
  );
}
function SettingsRow({ label, desc, children }) {
  return (
    <div className="tb-srow" style={{ display: 'flex', alignItems: 'center', gap: 16, padding: '12px 0', borderBottom: '1px solid var(--tb-hairline)' }}>
      <div style={{ flex: '1 1 auto', minWidth: 0 }}>
        <div style={{ fontSize: 13.5, fontWeight: 500 }}>{label}</div>
        {desc && <div className="tb-sub" style={{ fontSize: 11.5, marginTop: 2 }}>{desc}</div>}
      </div>
      <div style={{ flex: '0 1 auto', minWidth: 0, textAlign: 'right', overflowWrap: 'anywhere', wordBreak: 'break-word' }}>{children}</div>
    </div>
  );
}
function SettingsVal({ v }) {
  return <span className="tb-mono tb-sub" style={{ fontSize: 12 }}>{v != null && v !== '' ? String(v) : '—'}</span>;
}
function SettingsNotWired() {
  return (
    <div className="tb-card" style={{ padding: '16px 18px', fontSize: 12.5, color: 'var(--text-subtle)' }}>
      Live settings aren't wired yet - the daemon isn't reporting <span className="tb-mono">GET /trailrunner/api/settings</span>.
    </div>
  );
}
// A numeric setting input that commits on blur/Enter. Empty commits 0 (the patch clears the
// clearable Int? fields when <= 0); quality is coerced to 0..1 on the daemon. `onCommit` re-fetches.
function NumberSetting({ field, value, placeholder, onCommit }) {
  const [v, setV] = React.useState(value == null ? '' : String(value));
  React.useEffect(() => { setV(value == null ? '' : String(value)); }, [value]);
  const commit = () => {
    const t = v.trim();
    const n = t === '' ? 0 : parseFloat(t);
    TB.updateSetting({ [field]: Number.isFinite(n) ? n : 0 }).then(onCommit);
  };
  return (
    <input value={v} placeholder={placeholder} inputMode="decimal"
      onChange={(e) => setV(e.target.value)} onBlur={commit}
      onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur(); } }}
      style={{ width: 96, background: 'var(--bg-subtle)', color: 'var(--text-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '5px 8px', fontSize: 13, textAlign: 'right' }} />
  );
}

// Settings is a sidebar-tabbed view: a left rail of full sections (Models, Agents, Integrations,
// Run behavior, Workspace, Appearance) and a scrolling content pane. Each setting writes to the
// daemon via TB.updateSetting; the model + agent sections mirror the run-controls pickers elsewhere.
function SettingsScreen({ go, initTab }) {
  const settings = TB.useSettings();
  const status = TB.useStatus();
  const integrations = TB.useIntegrations();
  const intgConnected = (integrations.data || []).filter((i) => i.connected).length;
  const [themePref, setThemePref] = useStickyState('tb-theme', 'system');
  const [tab, setTab] = useStickyState('tb-settings-tab', 'models');
  const [wsBusy, setWsBusy] = React.useState(false);
  const [wsNotice, setWsNotice] = React.useState(null);
  const [wsError, setWsError] = React.useState(null);
  useLucide();
  React.useEffect(() => { if (initTab) setTab(initTab); }, [initTab]);

  const s = settings.data;
  const available = s && s.available !== false;
  const llm = (available && s && s.llm) ? s.llm : null;

  // Pick a directory via the native shell and persist it to the given config field.
  const pickDir = async (field) => {
    const path = await TB.pickDirectoryViaShell(status.data?.trailsDirectory);
    if (!path) return;
    setWsBusy(true);
    // The trails directory IS the workspace: route it through switchWorkspace so it persists, broadcasts
    // tb:workspace-changed (every useFetched view re-resolves), and reports empty/error. Restart-needed
    // (app targets) surfaces on the always-visible workspace chip badge. Other dirs are plain settings.
    if (field === 'trailsDirectory') {
      setWsNotice(null);
      setWsError(null);
      const res = await TB.switchWorkspace(path);
      setWsBusy(false);
      if (!res.ok) {
        setWsError(res.error || 'Could not switch to that folder. It may not be a readable directory.');
        return;
      }
      if (res.empty) setWsNotice(TB.WORKSPACE_EMPTY_NOTICE);
      return;
    }
    const res = await TB.updateSetting({ [field]: path });
    setWsBusy(false);
    if (res.ok) { status.reload(); settings.reload(); }
  };
  const dirRowStyle = { display: 'flex', alignItems: 'center', gap: 10, justifyContent: 'flex-end', flexWrap: 'wrap' };
  const DEFAULT_HTTP_PORT = 52525;
  const DEFAULT_HTTPS_PORT = 52526;

  // Local aliases for the module-scope primitives (keeps the section-body JSX below unchanged).
  // `reload` is bound once so NumberSetting can re-fetch after a commit without a closure.
  const Section = SettingsSection, Row = SettingsRow, Val = SettingsVal, NotWired = SettingsNotWired;
  const reload = () => settings.reload();
  const toggle = async (field, current) => {
    await TB.updateSetting({ [field]: !current });
    settings.reload();
  };

  const PortSetting = ({ field, value, defaultValue }) => {
    const [v, setV] = React.useState(value == null ? String(defaultValue) : String(value));
    const [error, setError] = React.useState(null);
    React.useEffect(() => {
      setV(value == null ? String(defaultValue) : String(value));
      setError(null);
    }, [value, defaultValue]);
    const n = parseInt(v, 10);
    const dirty = String(value == null ? defaultValue : value) !== v.trim();
    const valid = Number.isInteger(n) && n >= 1 && n <= 65535 && String(n) === v.trim();
    const commit = async () => {
      if (!dirty) return;
      if (!valid) {
        setError('Use a port from 1 to 65535.');
        return;
      }
      setError(null);
      await TB.updateSetting({ [field]: n });
      settings.reload();
    };
    return (
      <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'flex-end', gap: 5 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
          <input value={v} inputMode="numeric" pattern="[0-9]*"
            onChange={(e) => { setV(e.target.value); setError(null); }}
            onBlur={commit}
            onKeyDown={(e) => { if (e.key === 'Enter') { e.preventDefault(); e.currentTarget.blur(); } }}
            style={{ width: 92, background: 'var(--bg-subtle)', color: 'var(--text-standard)', border: '1px solid ' + (error ? 'var(--tb-danger-text)' : 'var(--tb-hairline)'), borderRadius: 8, padding: '5px 8px', fontSize: 13, textAlign: 'right' }} />
          <Btn sm kind="ghost" ico="rotate-ccw" disabled={value === defaultValue}
            onClick={() => TB.updateSetting({ [field]: defaultValue }).then(() => settings.reload())}>Reset</Btn>
        </div>
        {error && <span role="alert" style={{ fontSize: 11.5, color: 'var(--tb-danger-text)' }}>{error}</span>}
      </div>
    );
  };

  if (settings.loading || status.loading) {
    return (
      <div className="tb-in" style={{ padding: '28px 32px', maxWidth: 760, margin: '0 auto' }}>
        <ScreenHead ico="settings" title="Settings" sub="Loading…" />
        <div className="tb-card" style={{ padding: '18px' }}><Skeleton rows={6} /></div>
      </div>
    );
  }

  // ── Section bodies ───────────────────────────────────────────────────────
  const ModelsBody = () => (!llm ? <NotWired /> : (
    <Section title="Language model" sub="The provider + model the agent uses whenever a run needs AI - blazing a prompt, re-recording a step, or self-healing. Replaying a recorded trail uses no model at all.">
      <Row label="Provider" desc="Which LLM provider runs your trails">
        <Select value={llm.provider || ''}
          options={(() => {
            const ps = (llm.availableProviders || []).map((p) => [p.id, p.display]);
            if (llm.provider && !ps.some(([id]) => id === llm.provider)) ps.unshift([llm.provider, llm.provider]);
            return ps;
          })()}
          onChange={(e) => TB.updateSetting({ llmProvider: e.target.value }).then(() => settings.reload())} />
      </Row>
      <Row label="Model" desc="Model id for the selected provider. Add custom models in your Trailblaze YAML config.">
        <Select value={llm.model || ''}
          options={(() => {
            const forProvider = (llm.availableModels || []).filter((m) => m.provider === llm.provider).map((m) => [m.id, m.id]);
            if (llm.model && !forProvider.some(([id]) => id === llm.model)) forProvider.unshift([llm.model, llm.model + ' · current']);
            return forProvider;
          })()}
          onChange={(e) => TB.updateSetting({ llmModel: e.target.value }).then(() => settings.reload())} />
      </Row>
    </Section>
  ));

  const AgentsBody = () => {
    if (!llm) return <NotWired />;
    const agents = llm.availableAgents || [];
    const cur = llm.agent || '';
    return (
      <Section title="Agent" sub="The agent implementation that owns the run loop - tool dispatch, planning, recovery. Drives runs and recordings; a run can still override it in the advanced Configure-run dialog.">
        <Row label="Active agent" desc="Used by default for every run and recording">
          <Select value={cur} options={agents.map((a) => [a.id, a.display])}
            onChange={(e) => TB.updateSetting({ agent: e.target.value }).then(() => settings.reload())} />
        </Row>
        <div style={{ display: 'grid', gap: 9, padding: '12px 0 4px' }}>
          {agents.map((a) => {
            const on = a.id === cur;
            return (
              <div key={a.id} style={{ border: '1px solid ' + (on ? 'var(--tb-ai)' : 'var(--tb-hairline)'), borderRadius: 10, padding: '11px 13px', background: on ? 'var(--bg-prominent)' : 'transparent' }}>
                <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                  <Ico n="bot" s={15} c={on ? 'var(--tb-ai)' : 'var(--text-subtle-variant)'} />
                  <span style={{ fontSize: 13.5, fontWeight: 600 }}>{a.display}</span>
                  <span className="tb-mono tb-sub" style={{ fontSize: 10.5 }}>{a.id}</span>
                  <span style={{ flex: 1 }} />
                  {on && <Chip tone="green">Active</Chip>}
                </div>
                <div className="tb-sub" style={{ fontSize: 12, marginTop: 6, lineHeight: 1.5 }}>{AGENT_DESCRIPTIONS[a.id] || ''}</div>
              </div>
            );
          })}
        </div>
      </Section>
    );
  };

  const IntegrationsBody = () => <IntegrationsScreen embedded />;

  const RunsBody = () => {
    if (!available) return <NotWired />;
    return (
      <React.Fragment>
        <Section title="Run behavior" sub="Defaults applied when a run needs AI. A run can still override these in the Configure-run dialog at launch.">
          <Row label="Self-heal on drift" desc="When a recorded step no longer matches the screen, let the model re-resolve it instead of failing - and record the fix">
            <Switch on={!!s.selfHealEnabled} onClick={() => toggle('selfHealEnabled', s.selfHealEnabled)} />
          </Row>
          <Row label="Require recorded steps" desc="Fail rather than let the LLM improvise a missing step">
            <Switch on={!!s.requireSteps} onClick={() => toggle('requireSteps', s.requireSteps)} />
          </Row>
          <Row label="Run Android agent on host" desc="Drive the Android agent over host RPC instead of on-device">
            <Switch on={!!s.preferHostAgent} onClick={() => toggle('preferHostAgent', s.preferHostAgent)} />
          </Row>
          <Row label="Max LLM calls" desc="Budget per objective (blank = unbounded)">
            <NumberSetting field="maxLlmCalls" value={s.maxLlmCalls} placeholder="∞" onCommit={reload} />
          </Row>
          <Row label="Save annotated screenshots" desc="Keep the set-of-mark annotated screenshots alongside the raw ones">
            <Switch on={!!s.saveAnnotatedScreenshots} onClick={() => toggle('saveAnnotatedScreenshots', s.saveAnnotatedScreenshots)} />
          </Row>
        </Section>
        <Section title="Capture defaults" sub="Defaults for new runs; the Configure-run dialog can still override per run.">
          <Row label="Android logcat"><Switch on={!!s.captureLogcat} onClick={() => toggle('captureLogcat', s.captureLogcat)} /></Row>
          <Row label="iOS simulator logs"><Switch on={!!s.captureIosLogs} onClick={() => toggle('captureIosLogs', s.captureIosLogs)} /></Row>
          <Row label="Network traffic"><Switch on={!!s.captureNetworkTraffic} onClick={() => toggle('captureNetworkTraffic', s.captureNetworkTraffic)} /></Row>
          <Row label="Analytics"><Switch on={!!s.captureAnalytics} onClick={() => toggle('captureAnalytics', s.captureAnalytics)} /></Row>
        </Section>
        <Section title="Screenshots" sub="How screenshots captured during a run are stored.">
          <Row label="Format" desc="Image encoding for stored screenshots">
            <Select value={s.screenshotImageFormat || ''}
              options={[['', 'Default'], ['PNG', 'PNG'], ['JPEG', 'JPEG'], ['WEBP', 'WEBP']]}
              onChange={(e) => TB.updateSetting({ screenshotImageFormat: e.target.value }).then(() => settings.reload())} />
          </Row>
          <Row label="Max longer side" desc="Downscale so the longer side ≤ this (px; blank = no limit)"><NumberSetting field="screenshotMaxLongerSide" value={s.screenshotMaxLongerSide} placeholder="—" onCommit={reload} /></Row>
          <Row label="Max shorter side" desc="px; blank = no limit"><NumberSetting field="screenshotMaxShorterSide" value={s.screenshotMaxShorterSide} placeholder="—" onCommit={reload} /></Row>
          <Row label="Compression quality" desc="0.0 - 1.0"><NumberSetting field="screenshotCompressionQuality" value={s.screenshotCompressionQuality} placeholder="1.0" onCommit={reload} /></Row>
        </Section>
      </React.Fragment>
    );
  };

  const WorkspaceBody = () => (
    <React.Fragment>
      <Section title="Workspace" sub="Where Trailblaze scans for .trail.yaml files and writes run logs + state.">
        <Row label="Trails directory" desc="Where Trailblaze scans for .trail.yaml files">
          <div style={dirRowStyle}>
            <Val v={status.data?.trailsDirectory} />
            <Btn sm ico="folder-open" onClick={() => pickDir('trailsDirectory')} disabled={wsBusy}>Change</Btn>
          </div>
        </Row>
        {wsError && <div role="alert" style={{ padding: '0 0 10px', fontSize: 12, lineHeight: 1.5, color: 'var(--tb-danger-text)' }}>{wsError}</div>}
        {wsNotice && <div role="status" style={{ padding: '0 0 10px', fontSize: 12, lineHeight: 1.5, color: 'var(--text-subtle)' }}>{wsNotice}</div>}
        <Row label="Logs directory" desc="Where run logs + screenshots are written">
          <div style={dirRowStyle}>
            <Val v={available ? s.logsDirectory : null} />
            <Btn sm ico="folder-open" onClick={() => pickDir('logsDirectory')} disabled={wsBusy}>Change</Btn>
          </div>
        </Row>
        <Row label="App data directory" desc="Root for daemon state; logs/config default under it. Restart to fully apply.">
          <div style={dirRowStyle}>
            <Val v={available ? s.appDataDirectory : null} />
            <Btn sm ico="folder-open" onClick={() => pickDir('appDataDirectory')} disabled={wsBusy}>Change</Btn>
          </div>
        </Row>
      </Section>
      <Section title="Daemon">
        <Row label="Active HTTP port" desc="The port this running daemon is currently serving">
          <Val v={status.data?.daemonPort || DEFAULT_HTTP_PORT} />
        </Row>
        <Row label="Next HTTP port" desc="Saved for the next daemon start; TRAILBLAZE_PORT still wins when set">
          {available ? <PortSetting field="serverPort" value={s.serverPort} defaultValue={DEFAULT_HTTP_PORT} /> : <Val v={DEFAULT_HTTP_PORT} />}
        </Row>
        <Row label="Next HTTPS port" desc="Saved for the next daemon start; TRAILBLAZE_HTTPS_PORT still wins when set">
          {available ? <PortSetting field="serverHttpsPort" value={s.serverHttpsPort} defaultValue={DEFAULT_HTTPS_PORT} /> : <Val v={DEFAULT_HTTPS_PORT} />}
        </Row>
        {available && (s.serverPort !== status.data?.daemonPort || s.serverHttpsPort !== DEFAULT_HTTPS_PORT) && (
          <div role="status" style={{ padding: '0 0 10px', fontSize: 12, lineHeight: 1.5, color: 'var(--text-subtle)' }}>
            Port changes apply after restarting the daemon.
          </div>
        )}
        <Row label="Status"><Chip tone={status.data?.running ? 'green' : 'red'}>{status.data?.running ? 'Running' : 'Offline'}</Chip></Row>
        <Row label="Version"><Val v={status.data?.appVersion} /></Row>
      </Section>
    </React.Fragment>
  );

  const AppearanceBody = () => (
    <Section title="Appearance">
      <Row label="Theme" desc="System follows your OS appearance">
        <Select value={themePref} onChange={(e) => { setThemePref(e.target.value); applyTheme(e.target.value); }}
          options={[['system', 'System'], ['light', 'Light'], ['dark', 'Dark']]} />
      </Row>
    </Section>
  );

  const TABS = [
    { id: 'models', label: 'Models', ico: 'cpu', body: ModelsBody },
    { id: 'agents', label: 'Agents', ico: 'bot', body: AgentsBody },
    { id: 'integrations', label: 'Integrations', ico: 'plug', body: IntegrationsBody, badge: intgConnected || null },
    { id: 'runs', label: 'Run behavior', ico: 'wand-sparkles', body: RunsBody },
    { id: 'workspace', label: 'Workspace', ico: 'folder', body: WorkspaceBody },
    { id: 'appearance', label: 'Appearance', ico: 'palette', body: AppearanceBody },
  ];
  const activeTab = TABS.find((t) => t.id === tab) || TABS[0];

  return (
    <div className="tb-in" style={{ padding: '24px 28px', height: '100%', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <ScreenHead ico="settings" title="Settings" sub={available ? 'Live from the daemon - the process that scans your trails, talks to devices, and serves this app.' : 'Daemon facts are live; per-setting config is not yet wired.'} />
      <div style={{ display: 'flex', gap: 22, alignItems: 'flex-start', flex: 1, minHeight: 0 }}>
        <nav style={{ flex: '0 0 190px', display: 'flex', flexDirection: 'column', gap: 2 }}>
          {TABS.map((t) => {
            const on = t.id === activeTab.id;
            return (
              <button key={t.id} className="tb-settings-tab" data-on={on ? '1' : null} onClick={() => setTab(t.id)}>
                <Ico n={t.ico} s={15} c={on ? 'var(--tb-ai)' : 'var(--text-subtle-variant)'} />
                <span style={{ flex: 1 }}>{t.label}</span>
                {t.badge ? <span className="tb-mono tb-sub" style={{ fontSize: 11 }}>{t.badge}</span> : null}
              </button>
            );
          })}
        </nav>
        <div style={{ flex: 1, minWidth: 0, overflowY: 'auto', maxHeight: '100%', paddingRight: 4 }}>
          {activeTab.body()}
        </div>
      </div>
    </div>
  );
}

window.SettingsScreen = SettingsScreen;
