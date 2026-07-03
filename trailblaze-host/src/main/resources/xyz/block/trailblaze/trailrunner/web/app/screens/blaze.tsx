// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

const BLAZE_EXAMPLES = [
  'Sign in, open the dashboard, and verify the welcome message',
  'Create a new item and verify it appears in the list',
  'Go to settings and turn on push notifications',
];

// The component types a trailmap exposes to a blaze, in the order the rail shows them.
const BLAZE_TM_KINDS = ['tools', 'trailheads'];

function BlazeScreen({ pinnedId, go }) {
  useLucide();
  const devicesResult = TB.useDevices();
  const deviceList = devicesResult.data || [];
  const tmResult = TB.useTrailmaps();
  const trailmaps = tmResult.data || [];

  const [objective, setObjective] = React.useState('');
  const [sel, setSel] = React.useState(null); // "<deviceId>::<targetId|>"
  const [appsByDevice, setAppsByDevice] = React.useState({}); // { [deviceId]: { targets, currentTargetAppId } }
  const [collapsed, setCollapsed] = React.useState(() => new Set());
  const [showHelp, setShowHelp] = React.useState(false);
  const [showCommand, setShowCommand] = React.useState(false);
  const [copied, setCopied] = React.useState(false);
  const [tmTab, setTmTab] = React.useState(null); // which trailmap component type is expanded inline
  const [refreshing, setRefreshing] = React.useState(false);
  const [treeW, startTreeDrag] = useResizableWidth('tb-blaze-tree-w', 300, 240, 560);

  // Load the installed target apps for every connected device, so the left rail can
  // list devices + apps up front (selecting an app is the unit of "what to blaze").
  React.useEffect(() => {
    let live = true;
    deviceList.forEach((d) => {
      if (appsByDevice[d.id]) return;
      Promise.resolve(TB.fetchDeviceApps(d.platform, d.id)).then((r) => {
        if (live) setAppsByDevice((m) => (m[d.id] ? m : { ...m, [d.id]: r || { targets: [], currentTargetAppId: null } }));
      });
    });
    return () => { live = false; };
  }, [deviceList.map((d) => d.id).join(',')]);

  // Re-poll the device list and re-fetch every device's apps. Apps are replaced
  // in place (never cleared first) so the list doesn't blank out and jump.
  const refreshDevices = async () => {
    if (refreshing) return;
    setRefreshing(true);
    devicesResult.reload();
    try {
      await Promise.all(deviceList.map((d) =>
        Promise.resolve(TB.fetchDeviceApps(d.platform, d.id)).then((r) => setAppsByDevice((m) => ({ ...m, [d.id]: r || { targets: [], currentTargetAppId: null } }))),
      ));
    } finally { setRefreshing(false); }
  };

  // Flatten devices → selectable rows. A device with known target apps yields one row
  // per app; a device with none (e.g. web) yields a single device-level row.
  const rows = React.useMemo(() => {
    const out = [];
    deviceList.forEach((d) => {
      const apps = (appsByDevice[d.id] && appsByDevice[d.id].targets) || [];
      if (apps.length) apps.forEach((a) => out.push({ key: d.id + '::' + a.id, device: d, app: a }));
      else out.push({ key: d.id + '::', device: d, app: null });
    });
    return out;
  }, [deviceList, appsByDevice]);

  // Keep a valid selection — prefer the pinned device, else the first row.
  React.useEffect(() => {
    if (sel && rows.find((r) => r.key === sel)) return;
    const preferred = pinnedId && rows.find((r) => r.device.id === pinnedId);
    const next = preferred || rows[0];
    if (next) setSel(next.key);
  }, [rows]);

  const cur = rows.find((r) => r.key === sel) || null;
  const device = cur ? cur.device : null;
  const target = cur && cur.app ? cur.app.id : null;
  const currentTarget = device ? (appsByDevice[device.id] && appsByDevice[device.id].currentTargetAppId) || null : null;
  const tm = target ? trailmaps.find((t) => t.id === target) : null;
  const canBlaze = objective.trim().length > 0 && !!device;
  const toggle = (id) => setCollapsed((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });
  React.useEffect(() => { setTmTab(null); }, [target]);

  async function blaze() {
    if (!canBlaze) return;
    const obj = objective.trim();
    const platformUpper = (device.platform || '').toUpperCase();
    const tbDeviceId = { instanceId: device.id, trailblazeDevicePlatform: platformUpper };
    const title = 'Blaze: ' + (obj.length > 56 ? obj.slice(0, 53) + '…' : obj);
    const prepend = [];
    const yaml = TB.buildPromptTrailYaml(title, target, platformUpper, obj, prepend);
    // Show the run on the Active screen immediately, then connect + dispatch in the
    // background. Connecting can stall if the device's Trailblaze server isn't up
    // (the daemon probes the forwarded port), so we time it out and surface any
    // failure on the pending marker there instead of blocking this screen.
    TB.recordPendingRun({ title, target, device: device.name });
    go('active', { followLive: Date.now() });
    const connected = await Promise.race([
      TB.connectDevice(tbDeviceId),
      new Promise((res) => setTimeout(() => res('__timeout__'), 45000)),
    ]);
    if (connected === '__timeout__' || !connected) {
      TB.failPendingRun(connected === '__timeout__'
        ? `Couldn't reach ${device.name}. Its Trailblaze server isn't responding. Make sure the device is provisioned and the Trailblaze app is running.`
        : `Couldn't connect to ${device.name}.`);
      return;
    }
    if (target && target !== currentTarget) {
      const ok = await TB.setTargetApp(target);
      if (!ok) { TB.failPendingRun(`Could not switch to the ${target} target app.`); return; }
    }
    const r = await TB.dispatchRun(tbDeviceId, yaml);
    if (r && r.ok === false) TB.failPendingRun(r.error || 'Failed to start the run.');
  }

  const appLabel = (a) => (a.displayName || a.id) + (a.versionName ? ` (${a.versionName})` : '');

  return (
    <div className="tb-in" style={{ display: 'flex', height: '100%' }}>
      {/* Left rail - Devices, styled to match the Trailmaps tabs. */}
      <div style={{ width: treeW, flex: '0 0 ' + treeW + 'px', borderRight: '1px solid var(--tb-hairline)', background: 'var(--bg-subtle)', display: 'flex', flexDirection: 'column' }}>
        <RailHeader ico="smartphone" iconColor="var(--tb-running)" title="Devices"
          help={<HelpButton title="How blazing works" onClick={() => setShowHelp(true)} />}
          right={<button className="tb-btn ghost sm" style={{ padding: 6 }} title="Refresh devices" onClick={refreshDevices}><Ico n="refresh-cw" s={16} spin={refreshing} /></button>} />
        <div tabIndex={0} style={{ flex: 1, overflowY: 'auto', padding: '4px 8px 10px', outline: 'none' }}
          onKeyDown={(e) => listNavKeyDown(e, { index: rows.findIndex((x) => x.key === sel), count: rows.length, set: (i) => setSel(rows[i].key) })}>
          {devicesResult.loading && !deviceList.length && <div style={{ padding: 12 }}><Skeleton rows={4} /></div>}
          {deviceList.map((d) => {
            const apps = (appsByDevice[d.id] && appsByDevice[d.id].targets) || [];
            const loaded = d.platform === 'web' || !!appsByDevice[d.id];
            // No known target apps (web, or a device with nothing recognized): the device
            // itself is the selectable unit — blaze against it with no specific target.
            if (loaded && apps.length === 0) {
              const key = d.id + '::', on = key === sel;
              return (
                <div key={d.id} data-navrow role="button" onClick={() => setSel(key)} title={d.id}
                  className={'tb-tree ' + (on ? 'active' : '')} style={{ cursor: 'pointer' }}>
                  <PlatformGlyph platform={d.platform} s={15} c={on ? 'var(--tb-running)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{d.name || d.short || d.id}</span>
                  <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto' }}>{d.platform}</span>
                </div>
              );
            }
            const open = !collapsed.has(d.id);
            return (
              <div key={d.id}>
                <div className="tb-tree" style={{ cursor: 'pointer' }} onClick={() => toggle(d.id)} title={open ? 'Collapse' : 'Expand'}>
                  <span style={{ display: 'inline-flex', flex: '0 0 auto', transition: 'transform .15s var(--ease-out-soft)', transform: open ? 'none' : 'rotate(-90deg)' }}><Ico n="chevron-down" s={13} c="var(--text-subtle)" /></span>
                  <PlatformGlyph platform={d.platform} s={15} c="var(--tb-running)" style={{ flex: '0 0 auto' }} />
                  <span style={{ flex: 1, overflow: 'hidden', textOverflow: 'ellipsis' }}>{d.name || d.short || d.id}</span>
                  <span className="tb-sub" style={{ fontSize: 11, flex: '0 0 auto' }}>{d.platform}</span>
                </div>
                {open && apps.map((a) => {
                  const key = d.id + '::' + a.id, on = key === sel;
                  return (
                    <div key={key} data-navrow role="button" onClick={() => setSel(key)} title={a.appId || a.id}
                      className={'tb-tree ' + (on ? 'active' : '')} style={{ paddingLeft: 23, cursor: 'pointer' }}>
                      <Ico n="app-window" s={13} c={on ? 'var(--tb-running)' : 'var(--text-subtle)'} style={{ flex: '0 0 auto' }} />
                      <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', fontSize: 12.5 }}>{appLabel(a)}</span>
                    </div>
                  );
                })}
                {open && !loaded && (
                  <div className="tb-sub" style={{ fontSize: 11.5, padding: '3px 8px 5px 23px' }}>Checking apps…</div>
                )}
              </div>
            );
          })}
          {!devicesResult.loading && deviceList.length === 0 && (
            <div style={{ padding: '24px 12px' }}>
              <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5 }}>
                No device connected - <span onClick={() => go('home')} style={{ cursor: 'pointer', color: 'var(--tb-primary-green)' }}>connect one</span>
              </div>
            </div>
          )}
        </div>
        <div style={{ padding: '9px 12px', borderTop: '1px solid var(--tb-hairline)' }}>
          <div className="tb-sub" style={{ fontSize: 11.5, color: 'var(--text-standard)' }}>{deviceList.length} device{deviceList.length === 1 ? '' : 's'}</div>
        </div>
      </div>
      <Splitter onDown={startTreeDrag} />

      {/* Right pane - the prompt + the trailmap the selected target exposes. */}
      <div style={{ flex: 1, minWidth: 0, overflowY: 'auto' }}>
        <div style={{ maxWidth: 820, margin: '0 auto', padding: '24px 28px' }}>
          <DetailHeader
            eyebrow="Blaze"
            title="Run from a prompt"
            right={
              <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
                <span className="tb-sub" style={{ fontSize: 11.5 }}>⌘↵</span>
                <Btn kind="primary" ico="sparkles" onClick={blaze} disabled={!canBlaze} title="⌘↵ to blaze">
                  Blaze it
                </Btn>
                <HelpButton title="How blazing works" onClick={() => setShowHelp(true)} />
              </div>
            }
          />
          <p className="tb-sub" style={{ fontSize: 13.5, lineHeight: 1.55, margin: '8px 0 18px' }}>
            Describe what you want to test in plain language. Trailblaze's agent drives the device to do it - no recorded steps needed.
          </p>

          {/* Selected target context */}
          <div className="tb-eyebrow" style={{ marginBottom: 6 }}>Blazing on</div>
          {device ? (
            <div style={{ display: 'flex', alignItems: 'center', gap: 10, flexWrap: 'wrap', marginBottom: 18 }}>
              <span style={{ display: 'inline-flex', alignItems: 'center', gap: 7 }}>
                <PlatformGlyph platform={device.platform} s={15} c="var(--tb-running)" />
                <span style={{ fontSize: 13.5, fontWeight: 600, color: 'var(--text-standard)' }}>{device.name || device.short || device.id}</span>
              </span>
              <Chip>{device.platform}</Chip>
              {cur && cur.app ? <Chip>{appLabel(cur.app)}</Chip> : <span className="tb-sub" style={{ fontSize: 12 }}>no target app selected</span>}
            </div>
          ) : (
            <div className="tb-sub" style={{ fontSize: 13, marginBottom: 18 }}>
              Select a device on the left - or <span onClick={() => go('home')} style={{ cursor: 'pointer', color: 'var(--tb-primary-green)' }}>connect one</span>.
            </div>
          )}

          <div className="tb-card" style={{ padding: 2, marginTop: 4 }}>
            <textarea
              value={objective}
              onChange={(e) => setObjective(e.target.value)}
              onKeyDown={(e) => { if ((e.metaKey || e.ctrlKey) && e.key === 'Enter') { e.preventDefault(); blaze(); } }}
              placeholder="e.g. Sign in, create a new item, then verify it appears in the list"
              rows={5}
              data-selectable
              style={{ width: '100%', boxSizing: 'border-box', resize: 'vertical', border: 'none', background: 'transparent', color: 'var(--text-standard)', fontSize: 15, lineHeight: 1.5, padding: '14px 16px', outline: 'none', fontFamily: 'inherit' }}
            />
          </div>

          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8, marginTop: 12 }}>
            {BLAZE_EXAMPLES.map((ex, i) => (
              <button key={i} className="tb-btn sm" onClick={() => setObjective(ex)} style={{ textAlign: 'left', whiteSpace: 'normal', height: 'auto', padding: '6px 10px', maxWidth: '100%' }}>
                <Ico n="wand-sparkles" s={13} c="var(--text-subtle)" />{ex}
              </button>
            ))}
          </div>

          {/* What the agent can reach for this target - the trailmap behind the blaze.
              Each pill expands that component type's contents inline (this trailmap only);
              a row click opens the item on its Trailmaps page. */}
          {target && (
            <div style={{ marginTop: 22 }}>
              <div className="tb-eyebrow" style={{ marginBottom: 8 }}>Trailmap{tm ? ` · ${tm.displayName || tm.id}` : ''}</div>
              {tm ? (
                <React.Fragment>
                  <div style={{ display: 'flex', flexWrap: 'wrap', gap: 8 }}>
                    {BLAZE_TM_KINDS.map((k) => {
                      const m = (typeof TM_COMP_TYPES !== 'undefined' && TM_COMP_TYPES[k]) || { ico: 'box', color: 'var(--text-subtle)', label: k };
                      const n = (tm[k] || []).length;
                      const on = tmTab === k;
                      return (
                        <button key={k} className="tb-btn sm" onClick={() => setTmTab(on ? null : k)} title={`Show ${m.label.toLowerCase()} in ${tm.displayName || tm.id}`}
                          style={{ opacity: n ? 1 : 0.55, ...(on ? { background: 'var(--bg-elevated)', borderColor: 'var(--text-subtle-variant)', color: 'var(--text-standard)' } : {}) }}>
                          <Ico n={m.ico} s={13} c={m.color} />{m.label}
                          <span className="tb-sub" style={{ fontSize: 11, marginLeft: 2 }}>{n}</span>
                          <Ico n={on ? 'chevron-up' : 'chevron-down'} s={12} c="var(--text-subtle)" style={{ marginLeft: 1 }} />
                        </button>
                      );
                    })}
                  </div>
                  {tmTab && (() => {
                    const m = (typeof TM_COMP_TYPES !== 'undefined' && TM_COMP_TYPES[tmTab]) || { ico: 'box', color: 'var(--text-subtle)', label: tmTab };
                    const items = tm[tmTab] || [];
                    return (
                      <div className="tb-card" style={{ marginTop: 10, padding: 6, maxHeight: 320, overflowY: 'auto' }}>
                        {items.length === 0 ? (
                          <div className="tb-sub" style={{ fontSize: 12.5, padding: '8px 10px' }}>No {m.label.toLowerCase()} in this trailmap.</div>
                        ) : items.map((c) => {
                          const label = (typeof tmDisplayLabel !== 'undefined') ? tmDisplayLabel(tmTab, tm.id, tmInnerLabel(c.relPath, c.name)) : c.name;
                          const open = () => (tmTab === 'tools' ? go('tools', { tool: c.name }) : go(tmTab, { sel: c.relPath }));
                          return (
                            <div key={c.relPath} role="button" onClick={open} className="tb-tree" style={{ cursor: 'pointer' }} title={c.relPath}>
                              <Ico n={tmTab === 'tools' ? 'wrench' : m.ico} s={13} c={m.color} style={{ flex: '0 0 auto' }} />
                              <span className="tb-mono" style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', fontSize: 12 }}>{label}</span>
                              <Ico n="arrow-up-right" s={12} c="var(--text-subtle)" style={{ flex: '0 0 auto' }} />
                            </div>
                          );
                        })}
                      </div>
                    );
                  })()}
                </React.Fragment>
              ) : (
                <div className="tb-sub" style={{ fontSize: 12.5 }}>No trailmap in this workspace maps to <span className="tb-mono">{target}</span> - the agent uses Trailblaze's built-in device tools only.</div>
              )}
            </div>
          )}

          {(() => {
            const obj = objective.trim();
            const dev = device ? `${device.platform}/${device.id}` : null;
            const cmd = ['trailblaze blaze', shQuote(obj || '<describe the test>'), ...(dev ? ['--device', dev] : [])].join(' ')
              + (target ? `  # target: ${target}` : '');
            return (
              <div style={{ marginTop: 18 }}>
                <button onClick={() => setShowCommand((v) => !v)} style={{ display: 'inline-flex', alignItems: 'center', gap: 6, background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-subtle)', fontSize: 12.5, padding: 0 }}>
                  <Ico n={showCommand ? 'chevron-down' : 'chevron-right'} s={15} />
                  <span>Show command</span>
                </button>
                {showCommand && (
                  <div className="tb-mono" data-selectable style={{ position: 'relative', marginTop: 8, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '11px 13px', paddingRight: 84, fontSize: 12, lineHeight: 1.6, color: 'var(--text-standard)', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
                    {cmd}
                    <span style={{ position: 'absolute', top: 8, right: 8 }}>
                      <Btn sm ico="copy" onClick={() => navigator.clipboard.writeText(cmd).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); })}>{copied ? 'Copied!' : 'Copy'}</Btn>
                    </span>
                  </div>
                )}
              </div>
            );
          })()}
        </div>
      </div>

      {showHelp && (
        <HelpOverlay
          title="How blazing works"
          sub="Blazing turns a plain-language objective into a working UI test. You describe the goal; the AI agent figures out the taps, swipes, and checks on a real device - and records everything it did, so the next run doesn't need the AI at all."
          onClose={() => setShowHelp(false)}
        >
          <HelpCard ico="smartphone" color="var(--tb-running)" title="1 · Pick a device and app">
            Choose the device and target app on the left - that's what the agent drives, and it determines which trailmap (tools, known UI states, navigation) the agent can use for this run.
          </HelpCard>
          <HelpCard ico="message-square-text" color="var(--tb-running)" title="2 · Describe the objective">
            Write what you want to happen the way you'd tell a teammate - including what to verify (e.g. "…then verify it shows in the activity feed"). The agent sees the live screen and acts through the same tools a trail uses.
          </HelpCard>
          <HelpCard ico="sparkles" color="var(--tb-ai)" title="3 · The agent drives - and records">
            Each round the agent reads the screen, decides one action, and performs it. Every action is captured as an exact, replayable tool call. Watch it live under Active; the full reasoning is in the run's LLM tab afterwards.
          </HelpCard>
          <HelpCard ico="save" color="var(--tb-amber)" title="4 · Keep it as a trail">
            When the run finishes, save its recording as a trail. Replays re-run the exact recorded steps - fast, deterministic, zero LLM calls - and the AI only steps back in if you re-record or self-heal kicks in on a drifted step.
          </HelpCard>
        </HelpOverlay>
      )}
    </div>
  );
}
window.BlazeScreen = BlazeScreen;
