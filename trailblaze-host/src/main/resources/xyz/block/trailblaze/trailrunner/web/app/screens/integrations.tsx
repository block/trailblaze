// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function integrationIcon(id) {
  if (id === 'mcp') return 'plug-zap';
  return 'puzzle';
}

function IntegrationCard({ it }) {
  const [busy, setBusy] = React.useState(false);
  const [result, setResult] = React.useState(null);
  const connected = !!it.connected;
  const name = it.name || it.id;
  const desc = it.detail || '';

  async function runAction() {
    if (busy) return;
    setBusy(true);
    setResult(null);
    const res = it.action
      ? await TB.runIntegrationAction(it.id, it.action.id)
      : { ok: false, error: 'No action.' };
    setBusy(false);
    setResult(res.ok ? 'ok' : (res.error || 'Failed.'));
    setTimeout(() => setResult(null), 2500);
  }

  const actionLabel = busy
    ? 'Working...'
    : result === 'ok' ? 'Done'
    : result ? 'Retry'
    : (it.action ? it.action.label : '');

  return (
    <div className="tb-card pad" data-testid={'integration-' + it.id} style={connected ? null : { opacity: .82 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 11, marginBottom: 10 }}>
        <div style={{ width: 34, height: 34, borderRadius: 9, background: 'var(--bg-prominent)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <Ico n={integrationIcon(it.id)} s={18} c={connected ? 'var(--tb-running)' : 'var(--text-subtle-variant)'} />
        </div>
        <div style={{ flex: 1, fontSize: 14, fontWeight: 600 }}>{name}</div>
        <Chip tone={connected ? 'green' : ''}>{connected ? 'Connected' : 'Not connected'}</Chip>
      </div>
      <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5 }}>{desc}</div>
      {it.action && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 12 }}>
          <span data-testid={'integration-' + it.id + '-open'}>
            <Btn sm ico="external-link" onClick={runAction}>{actionLabel}</Btn>
          </span>
          {result && result !== 'ok' && (
            <span className="tb-sub" style={{ fontSize: 11.5, color: 'var(--tb-danger-text)' }}>{result}</span>
          )}
        </div>
      )}
    </div>
  );
}

function IntegrationsScreen({ embedded }) {
  useLucide();
  const status = TB.useStatus();
  const integrations = TB.useIntegrations();
  const [showHelp, setShowHelp] = React.useState(false);
  const port = status.data?.daemonPort;
  const running = !!status.data?.running;
  const [copied, setCopied] = React.useState(null);

  const effectivePort = port || 52525;
  const mcpUrl = `http://localhost:${effectivePort}/mcp`;
  const mcpConfig = JSON.stringify({ mcpServers: { trailblaze: { url: mcpUrl } } }, null, 2);

  function copyText(text, key) {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(key);
      setTimeout(() => setCopied(null), 1500);
    });
  }

  const live = {
    id: 'mcp', name: 'MCP Server', icon: 'plug-zap', tone: 'blue',
    status: running ? `Running · :${effectivePort}` : 'Offline',
    statusTone: running ? 'green' : 'red',
    desc: 'This daemon is exposed as an MCP server for tools that can list trails, inspect tools, and drive runs.',
  };

  const list = integrations.data || [];
  const connected = list.filter((it) => it.connected);
  const notConnected = list.filter((it) => !it.connected);

  return (
    // `embedded` renders just the body (no ScreenHead / page padding) so the Settings → Integrations
    // tab can host the same content inside its tabbed content pane.
    <div className={embedded ? '' : 'tb-in'} style={embedded ? {} : { padding: '28px 32px', maxWidth: 1000, margin: '0 auto' }}>
      {!embedded && (
        <ScreenHead ico="plug" iconColor="var(--tb-running)" eyebrow="Setup" title="Integrations" sub="External systems that feed and run your trails."
          right={<HelpButton title="How integrations work" onClick={() => setShowHelp(true)} />} />
      )}
      {!embedded && showHelp && (
        <HelpOverlay
          title="How integrations work"
          sub="Integrations connect this daemon to the systems around your tests - where test cases live, where CI runs them, and which tools can drive Trailblaze."
          onClose={() => setShowHelp(false)}
        >
          <HelpCard ico="clipboard-check" color="var(--tb-running)" title="Test management">
            Browse external cases and keep recorded trails linked to the work they cover when a provider is configured.
          </HelpCard>
          <HelpCard ico="cloud-cog" color="var(--tb-pass)" title="CI">
            Trigger remote runs and watch pass/fail status from here when a CI provider is configured.
          </HelpCard>
          <HelpCard ico="plug-zap" color="var(--tb-amber)" title="AI access - MCP">
            The daemon doubles as an MCP server so compatible clients can list trails, inspect tools, and drive runs.
          </HelpCard>
        </HelpOverlay>
      )}

      <div className="tb-eyebrow" style={{ marginBottom: 11 }}>Live</div>
      <div className="tb-card pad" style={{ marginBottom: 26 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 11 }}>
          <div style={{ width: 36, height: 36, borderRadius: 9, background: 'var(--bg-prominent)', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Ico n={live.icon} s={19} c="var(--tb-running)" />
          </div>
          <div style={{ flex: 1 }}>
            <div style={{ fontSize: 14.5, fontWeight: 600 }}>{live.name}</div>
            <div className="tb-mono tb-sub" style={{ fontSize: 11, marginTop: 2 }}>{mcpUrl}</div>
          </div>
          <Chip tone={live.statusTone}>{live.status}</Chip>
        </div>
        <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5, marginTop: 10 }}>{live.desc}</div>
        {running && (
          <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
            <Btn
              data-testid="mcp-copy-config"
              sm ico="copy"
              onClick={() => copyText(mcpConfig, 'config')}
            >
              {copied === 'config' ? 'Copied!' : 'Copy config'}
            </Btn>
            <Btn
              sm kind="ghost"
              onClick={() => copyText(mcpUrl, 'url')}
            >
              {copied === 'url' ? 'Copied!' : 'Copy URL'}
            </Btn>
          </div>
        )}
      </div>

      {integrations.loading ? (
        <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 12 }}>
          {[0, 1].map((i) => (
            <div className="tb-card pad" key={i}>
              <div className="tb-skel" style={{ height: 12, width: '45%' }}></div>
              <div className="tb-skel" style={{ height: 9, width: '80%', marginTop: 10 }}></div>
            </div>
          ))}
        </div>
      ) : (
        <React.Fragment>
          {list.length === 0 && (
            <EmptyState ico="puzzle" title="No optional integrations" sub="This Trail Runner build has not registered any extra integration providers." />
          )}
          {connected.length > 0 && (
            <React.Fragment>
              <div className="tb-eyebrow" style={{ marginBottom: 11 }}>Connected</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 12, marginBottom: 26 }}>
                {connected.map((it) => <IntegrationCard key={it.id} it={it} />)}
              </div>
            </React.Fragment>
          )}
          {notConnected.length > 0 && (
            <React.Fragment>
              <div className="tb-eyebrow" style={{ marginBottom: 11 }}>Not connected</div>
              <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fill, minmax(300px, 1fr))', gap: 12 }}>
                {notConnected.map((it) => <IntegrationCard key={it.id} it={it} />)}
              </div>
            </React.Fragment>
          )}
        </React.Fragment>
      )}
    </div>
  );
}

window.IntegrationsScreen = IntegrationsScreen;
