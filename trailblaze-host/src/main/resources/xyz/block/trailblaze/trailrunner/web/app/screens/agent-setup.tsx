// @ts-nocheck -- migrated-file convention; Babel strips types at load time and the typecheck gate
// covers regressions separately.

// Agents → Setup. Renders the external-agent provider registry served by the daemon: which coding
// agent CLIs Trail Runner can drive, whether each is installed, and how to install one and grant
// it model access. Providers added to the server registry show up here with no UI change.

function AgentSetupScreen({ go }) {
  useLucide();
  const agentsHook = TB.useExternalAgents();
  const supported = (agentsHook.data && agentsHook.data.supportedAgents) || [];

  return (
    <div style={{ height: '100%', minHeight: 0, overflowY: 'auto' }}>
      <div style={{ padding: '30px 40px 60px', maxWidth: 860, margin: '0 auto' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, marginBottom: 4 }}>
          <Btn sm ico="arrow-left" onClick={() => go('create')}>Back</Btn>
        </div>
        <RailHeader ico="settings-2" iconColor="var(--tb-ai)" title="Configure agents"
          sub="Trail Runner drives coding-agent CLIs installed on this machine. Model access is configured in each CLI, not in Trail Runner." />

        {agentsHook.loading && !supported.length && <div style={{ marginTop: 20 }}><Skeleton rows={4} /></div>}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 14, marginTop: 22 }}>
          {supported.map((a) => <AgentProviderCard key={a.id} provider={a} />)}
        </div>

        <p className="tb-sub" style={{ marginTop: 26, fontSize: 12, lineHeight: 1.6, maxWidth: 640 }}>
          After installing or signing in to a CLI, restart the Trail Runner daemon so it re-detects
          the executable. More providers can be added to the daemon's agent registry over time —
          anything listed there appears here and in the agent picker automatically.
        </p>
      </div>
    </div>
  );
}

function AgentProviderCard({ provider }) {
  const available = provider.available !== false;
  return (
    <div style={{ border: '1px solid var(--tb-hairline)', borderLeft: '3px solid ' + (available ? 'var(--tb-pass)' : 'var(--tb-amber)'), borderRadius: 12, background: 'var(--bg-subtle)', padding: '14px 17px' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
        <Ico n={available ? 'check-circle-2' : 'download'} s={16} c={available ? 'var(--tb-pass)' : 'var(--tb-amber)'} />
        <span style={{ fontSize: 14.5, fontWeight: 700 }}>{provider.display}</span>
        <Chip tone={available ? 'green' : ''}>{available ? 'installed' : 'not installed'}</Chip>
        <span className="tb-mono tb-sub" style={{ fontSize: 11 }}>{provider.executable}</span>
        <span style={{ flex: 1 }} />
        {provider.docsUrl && (
          <a href={provider.docsUrl} target="_blank" rel="noreferrer"
            style={{ display: 'inline-flex', alignItems: 'center', gap: 5, fontSize: 11.5, color: 'var(--tb-ai)', textDecoration: 'none' }}>
            <Ico n="external-link" s={12} /> docs
          </a>
        )}
      </div>
      {!available && provider.detail && (
        <div className="tb-sub" style={{ fontSize: 11.5, margin: '6px 0 0 26px' }}>{provider.detail}</div>
      )}
      <div style={{ display: 'flex', flexDirection: 'column', gap: 9, marginTop: 12 }}>
        {provider.installHint && <ProviderHint label="Install" mono text={provider.installHint} />}
        {provider.authHint && <ProviderHint label="Model access" text={provider.authHint} />}
        {provider.modelsHint && <ProviderHint label="Models" text={provider.modelsHint} />}
      </div>
    </div>
  );
}

function ProviderHint({ label, text, mono = false }) {
  return (
    <div style={{ display: 'flex', gap: 12, alignItems: 'baseline' }}>
      <span className="tb-eyebrow" style={{ flex: '0 0 92px' }}>{label}</span>
      {mono ? (
        <code className="tb-mono" data-selectable style={{ fontSize: 12, background: 'var(--bg-prominent)', border: '1px solid var(--tb-hairline)', borderRadius: 7, padding: '5px 9px' }}>{text}</code>
      ) : (
        <span data-selectable style={{ fontSize: 12.5, lineHeight: 1.55, color: 'var(--text-subtle-variant)' }}>{text}</span>
      )}
    </div>
  );
}

Object.assign(window, { AgentSetupScreen, AgentProviderCard });
