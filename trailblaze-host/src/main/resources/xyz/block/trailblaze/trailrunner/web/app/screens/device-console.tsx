// @ts-nocheck -- every screen opts out: the shared ui.tsx components declare their optional props as
// required, so any call site fails the check. The typed surface is daemon.ts (window.TbRpc).

// Devices: every connected device as a live mirror you can drive - tap, swipe, long-press, type,
// press keys, point a browser at a URL - plus a composer that runs a tool or a typed step/verify on
// the selected device. Focusing one device gives it the whole screen with a live feed of the steps
// and tools that ran on it. Nothing here records a trail; the Record screen does that. Gesture/YAML
// rules live in device-console-model.js, the feed's in device-activity-model.js.
//
// Connections are opened for the global target and kept while you navigate away (only the video
// stream stops when the screen is hidden). Leaving the page releases the ones this screen opened.

const DCM = window.DeviceConsoleModel;
const DAM = window.DeviceActivityModel;
// Mirror height per size choice; width follows the device's aspect ratio.
const DEVICE_MIRROR_SIZES = { s: 360, m: 520, l: 720 };

function DeviceConsoleScreen({ go, active, initFocus }) {
  useLucide();
  const [gt] = TB.useGlobalTarget();
  const target = gt && gt.target;
  const devices = TB.useDevices();
  const deviceList = devices.data || [];
  // instanceId → { status: 'connecting' | 'connected' | 'error', target, deviceWidth, deviceHeight, logSessionId, error }
  const [conns, setConns] = React.useState({});
  // The latest `conns`, for a connect that finishes after the render that started it.
  const connsRef = React.useRef(conns);
  connsRef.current = conns;
  const [selectedId, setSelectedId] = React.useState(initFocus || null);
  // The device given the whole screen, or null for the grid.
  const [focusId, setFocusId] = React.useState(initFocus || null);
  React.useEffect(() => { if (initFocus) { setFocusId(initFocus); setSelectedId(initFocus); } }, [initFocus]);
  const [size, setSize] = React.useState(() => {
    try { return window.localStorage.getItem('tb-device-console-size') || 'm'; } catch (_) { return 'm'; }
  });
  const pickSize = (v) => { setSize(v); try { window.localStorage.setItem('tb-device-console-size', v); } catch (_) {} };
  // Device ids this screen connected, so leaving the page releases exactly those.
  const openedRef = React.useRef(new Map());
  // The grid's width, so a wide browser mirror shrinks to fit instead of overflowing it.
  const gridRef = React.useRef(null);
  const [gridW, setGridW] = React.useState(0);
  React.useEffect(() => {
    const el = gridRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => setGridW(el.clientWidth));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);

  const patchConn = (id, patch) => setConns((m) => ({ ...m, [id]: { ...(m[id] || {}), ...patch } }));

  async function connect(device) {
    const tbId = DCM.deviceIdFor(device);
    if (!tbId) return;
    const forTarget = target || null;
    patchConn(device.id, { status: 'connecting', error: null, target: forTarget });
    const opened = (r) => {
      openedRef.current.set(device.id, tbId);
      patchConn(device.id, { status: 'connected', deviceWidth: r.deviceWidth, deviceHeight: r.deviceHeight, logSessionId: r.logSessionId, error: null, target: forTarget });
      setSelectedId((cur) => cur || device.id);
    };
    // The first Android connect installs the on-device runner, which can take a while.
    const pending = TB.connectDeviceDetailed(tbId, forTarget);
    const r = await TB.withTimeout(pending, 120000);
    if (r === '__timeout__') {
      patchConn(device.id, { status: 'error', error: 'Connecting timed out. Try again.' });
      // The daemon keeps going after the wait gives up. A connect that lands late is still this
      // screen's to show and to release, unless something else has happened to the device since.
      pending.then((late) => {
        const c = connsRef.current[device.id];
        if (late && late.ok && c && c.status === 'error' && c.target === forTarget) opened(late);
      });
      return;
    }
    if (!r || !r.ok) {
      patchConn(device.id, { status: 'error', error: (r && r.error) || 'Could not connect.' });
      return;
    }
    opened(r);
  }

  async function disconnect(device) {
    const tbId = DCM.deviceIdFor(device);
    openedRef.current.delete(device.id);
    setConns((m) => { const next = { ...m }; delete next[device.id]; return next; });
    if (tbId) await TB.disconnectDevice(tbId);
  }

  // A connection is bound to the target it was opened for: the device runs that app, and its tools
  // are that target's. When the target changes, each one this screen holds is reopened for the new
  // one, so the tools listed and the app being driven never disagree.
  React.useEffect(() => {
    Object.keys(conns).forEach((id) => {
      const c = conns[id];
      if (!c || c.status !== 'connected' || c.target === (target || null)) return;
      const d = deviceList.find((x) => x.id === id);
      if (!d) return;
      disconnect(d).then(() => connect(d));
    });
  }, [target]);

  const connectAll = () => {
    deviceList.forEach((d) => {
      const c = conns[d.id];
      if (!c || c.status === 'error') connect(d);
    });
  };

  // Release this screen's connections when the page goes away. keepalive lets the request outlive
  // the page; a connection left open stays bound to this target and blocks a run for another one.
  React.useEffect(() => {
    const release = () => {
      openedRef.current.forEach((tbId) => {
        try {
          fetch('/rpc/DisconnectDeviceRequest', {
            method: 'POST', keepalive: true, headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ trailblazeDeviceId: tbId }),
          });
        } catch (_) { /* page is closing */ }
      });
    };
    window.addEventListener('pagehide', release);
    return () => window.removeEventListener('pagehide', release);
  }, []);

  // A device that went away takes its connection state and selection with it.
  React.useEffect(() => {
    if (!devices.data) return;
    const ids = new Set(devices.data.map((d) => d.id));
    setConns((m) => {
      const stale = Object.keys(m).filter((id) => !ids.has(id));
      if (stale.length === 0) return m;
      const next = { ...m };
      stale.forEach((id) => { delete next[id]; openedRef.current.delete(id); });
      return next;
    });
    setSelectedId((cur) => (cur && !ids.has(cur) ? null : cur));
    setFocusId((cur) => (cur && !ids.has(cur) ? null : cur));
  }, [devices.data]);

  // Refresh the device list whenever the screen comes back into view.
  const wasActive = React.useRef(active);
  React.useEffect(() => {
    if (active && !wasActive.current) devices.reload();
    wasActive.current = active;
  }, [active]);

  const selected = deviceList.find((d) => d.id === selectedId) || null;
  const selectedConn = selected ? conns[selected.id] : null;
  const anyIdle = deviceList.some((d) => !conns[d.id] || conns[d.id].status === 'error');
  const focused = deviceList.find((d) => d.id === focusId) || null;

  const focus = (d) => { setSelectedId(d.id); setFocusId(d.id); };
  // A focused device is connected for you, including one opened by a #devices/<id> link before
  // the device list had loaded.
  React.useEffect(() => {
    if (focused && !conns[focused.id]) connect(focused);
  }, [focused && focused.id]);

  // A link opened at #devices follows the open device, so the address can be copied or reloaded.
  // Left alone when the screen was reached some other way: the app does not route by the address.
  React.useEffect(() => {
    if (!active || !/^#devices(\/|$)/.test(window.location.hash || '')) return;
    const want = focusId ? '#devices/' + encodeURIComponent(focusId) : '#devices';
    if (window.location.hash !== want) history.replaceState(null, '', want);
  }, [focusId, active]);

  if (focused) {
    return (
      <DeviceFocusView device={focused} conn={conns[focused.id] || null} active={active} target={target} go={go}
        onBack={() => setFocusId(null)}
        onConnect={() => connect(focused)} onDisconnect={() => disconnect(focused)}
        onDims={(w, h) => patchConn(focused.id, { deviceWidth: w, deviceHeight: h })}
        onLost={() => connect(focused)} />
    );
  }

  return (
    <div className="tb-in" style={{ padding: '28px 32px', height: '100%', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <ScreenHead ico="monitor-smartphone" iconColor="var(--tb-running)" eyebrow="Live" title="Devices"
        sub={target
          ? <>Connected for <b>{TB.targetLabel ? TB.targetLabel(target) : target}</b>. Click to tap, drag to swipe, hold or right-click to long-press.</>
          : <>Click to tap, drag to swipe, hold or right-click to long-press. <a onClick={() => go('home')} style={{ color: 'var(--tb-link)', cursor: 'pointer' }}>Pick a target on Home</a> to load your app's tools.</>}
        right={
          <div style={{ display: 'flex', gap: 8, alignItems: 'center' }}>
            <Select compact value={size} onChange={(e) => pickSize(e.target.value)} title="Mirror size"
              options={[{ value: 's', label: 'Small' }, { value: 'm', label: 'Medium' }, { value: 'l', label: 'Large' }]} />
            <Btn sm kind="ghost" ico="refresh-cw" onClick={() => devices.reload()}>Refresh</Btn>
            <Btn sm kind="primary" ico="plug" disabled={!anyIdle} onClick={connectAll}>Connect all</Btn>
          </div>
        } />
      <div style={{ flex: 1, minHeight: 0, display: 'flex', gap: 18 }}>
        <div ref={gridRef} style={{ flex: 1, minWidth: 0, overflow: 'auto', display: 'flex', flexWrap: 'wrap', gap: 16, alignContent: 'flex-start', alignItems: 'flex-start', paddingBottom: 12 }}>
          {devices.loading && deviceList.length === 0 && (
            <div className="tb-sub" style={{ display: 'flex', gap: 8, alignItems: 'center' }}><Ico n="loader-2" s={14} spin /> Looking for devices…</div>
          )}
          {!devices.loading && deviceList.length === 0 && (
            <div className="tb-card pad" style={{ maxWidth: 460 }}>
              <div style={{ fontWeight: 600, marginBottom: 6 }}>No devices found</div>
              <div className="tb-sub">Start an Android emulator, boot an iOS simulator, or open a browser device, then press Refresh.</div>
            </div>
          )}
          {deviceList.map((d) => (
            <DeviceConsoleCard key={d.id} device={d} conn={conns[d.id] || null} active={active}
              maxH={DEVICE_MIRROR_SIZES[size] || DEVICE_MIRROR_SIZES.m} gridW={gridW}
              selected={d.id === selectedId} onSelect={() => setSelectedId(d.id)} onFocus={() => focus(d)}
              onConnect={() => connect(d)} onDisconnect={() => disconnect(d)}
              onDims={(w, h) => patchConn(d.id, { deviceWidth: w, deviceHeight: h })}
              onLost={() => connect(d)} />
          ))}
        </div>
        <ToolComposer device={selected} connected={!!(selectedConn && selectedConn.status === 'connected')} target={target} />
      </div>
    </div>
  );
}

function DeviceConsoleCard({ device, conn, active, maxH, gridW, selected, onSelect, onFocus, onConnect, onDisconnect, onDims, onLost }) {
  const connected = !!(conn && conn.status === 'connected');
  const connecting = !!(conn && conn.status === 'connecting');
  const tbId = React.useMemo(() => DCM.deviceIdFor(device), [device.id, device.platform]);
  const imgRef = React.useRef(null);
  const canvasRef = React.useRef(null);
  const surfaceRef = React.useRef(null);
  const downRef = React.useRef(null);
  const [frame, setFrame] = React.useState(false);
  const [err, setErr] = React.useState(null);
  const [touch, setTouch] = React.useState(null); // { left, top, key } in % of the mirror
  const [text, setText] = React.useState('');
  const [url, setUrl] = React.useState('');
  const onDimsRef = React.useRef(onDims); onDimsRef.current = onDims;
  const onLostRef = React.useRef(onLost); onLostRef.current = onLost;

  const dw = (conn && conn.deviceWidth) || 0;
  const dh = (conn && conn.deviceHeight) || 0;
  const isWeb = device.platform === 'web';
  // Fit the device inside maxH tall, a wider box for landscape browsers, and never wider than the
  // grid (less the card's padding). A device that isn't connected gets a short placeholder.
  const CARD_PAD = 24;
  const fallbackRatio = isWeb ? 10 / 16 : 2.1;
  const ratio = dw > 0 && dh > 0 ? dh / dw : fallbackRatio;
  let maxW = isWeb ? maxH * 1.6 : maxH;
  if (gridW > 0) maxW = Math.min(maxW, gridW - CARD_PAD - 2);
  let mirrorH = maxH;
  let mirrorW = mirrorH / ratio;
  if (mirrorW > maxW) { mirrorW = maxW; mirrorH = mirrorW * ratio; }
  if (!connected) { mirrorW = 256; mirrorH = 120; }

  // Stream only while connected and visible. The transport never connects or disconnects; this
  // card's parent owns that. On "not connected" (the daemon dropped it) the parent reconnects.
  React.useEffect(() => {
    if (!connected || !active || !tbId || !window.TbLiveDeviceStream) return;
    setFrame(false);
    const handle = window.TbLiveDeviceStream.openLiveDeviceStream({
      deviceId: tbId,
      deviceWidth: dw,
      deviceHeight: dh,
      img: imgRef.current,
      canvas: canvasRef.current,
      onFrame: () => setFrame(true),
      onDims: (w, h) => { if (w > 0 && h > 0) onDimsRef.current(w, h); },
      onError: (m) => setErr(m || null),
      onNotConnected: async () => { onLostRef.current(); },
    });
    return () => handle.close();
  }, [connected, active, tbId]);

  async function send(interaction) {
    if (!tbId) return;
    const r = await TB.sendDeviceInteraction(tbId, interaction);
    setErr(r.ok ? null : r.error);
  }

  const point = (e) => {
    const el = surfaceRef.current;
    if (!el) return null;
    return DCM.toDevicePoint(el.getBoundingClientRect(), e.clientX, e.clientY, dw, dh);
  };
  const flash = (p) => setTouch({ left: (p.x / dw) * 100, top: (p.y / dh) * 100, key: Date.now() });

  const onPointerDown = (e) => {
    if (!connected || e.button !== 0) return;
    const p = point(e);
    if (!p) return;
    try { e.currentTarget.setPointerCapture(e.pointerId); } catch (_) {}
    downRef.current = { ...p, t: Date.now() };
  };
  const onPointerUp = (e) => {
    const down = downRef.current; downRef.current = null;
    if (!connected || !down) return;
    const up = point(e);
    if (!up) return;
    flash(down);
    send(DCM.gestureFor(down, up, Date.now() - down.t, dw));
  };
  const onContextMenu = (e) => {
    e.preventDefault();
    if (!connected) return;
    const p = point(e);
    if (!p) return;
    downRef.current = null;
    flash(p);
    send({ type: 'longPress', x: p.x, y: p.y });
  };

  const typeText = () => {
    if (!text) return;
    send({ type: 'inputText', text });
    setText('');
  };
  const navigate = async () => {
    const u = url.trim();
    if (!u || !tbId) return;
    const r = await TB.navigateWebUrl(tbId, /^[a-z][a-z0-9+.-]*:/i.test(u) ? u : 'https://' + u);
    setErr(r.ok ? null : r.error);
  };

  const platLabel = device.platform === 'ios' ? 'iOS' : device.platform === 'android' ? 'Android' : device.platform === 'web' ? 'Web' : device.platform;
  const cardW = Math.max(mirrorW + CARD_PAD, 280);

  return (
    <div className="tb-card" data-testid={'device-card-' + device.id} onClick={onSelect}
      style={{ width: cardW, flex: '0 0 auto', padding: 12, boxSizing: 'border-box', display: 'flex', flexDirection: 'column', gap: 10,
        borderColor: selected ? 'var(--tb-running)' : undefined, boxShadow: selected ? '0 0 0 1px var(--tb-running)' : undefined, cursor: 'default' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, minWidth: 0 }}>
        <PlatformGlyph platform={device.platform} s={16} c="var(--text-subtle-variant)" title={platLabel} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div className="tb-device-name" style={{ fontSize: 13, fontWeight: 600, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', cursor: onFocus ? 'pointer' : 'default' }}
            title={onFocus ? 'Open ' + device.name : device.name} onClick={onFocus ? (e) => { e.stopPropagation(); onFocus(); } : undefined}>{device.name}</div>
          <div className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }} title={device.id + ' · ' + device.driver}>{device.id} · {device.driver}</div>
        </div>
        {onFocus && (
          <Btn sm kind="ghost" ico="maximize-2" title="Open this device on its own, with what runs on it" aria-label={'Open ' + device.name}
            onClick={(e) => { e.stopPropagation(); onFocus(); }}>Open</Btn>
        )}
        {connected && <Btn sm kind="ghost" ico="unplug" title="Disconnect" aria-label={'Disconnect ' + device.name} onClick={(e) => { e.stopPropagation(); onDisconnect(); }} />}
      </div>

      <div ref={surfaceRef} onPointerDown={onPointerDown} onPointerUp={onPointerUp} onContextMenu={onContextMenu}
        data-testid={'device-mirror-' + device.id}
        style={{ width: mirrorW, height: mirrorH, margin: '0 auto', borderRadius: isWeb ? 8 : 16, background: '#000', position: 'relative', overflow: 'hidden',
          touchAction: 'none', userSelect: 'none', cursor: connected ? 'crosshair' : 'default', border: '1px solid var(--tb-hairline)' }}>
        <img ref={imgRef} alt={device.name} draggable={false} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', objectFit: 'fill', display: 'none', pointerEvents: 'none' }} />
        <canvas ref={canvasRef} aria-label={device.name} style={{ position: 'absolute', inset: 0, width: '100%', height: '100%', display: 'none', pointerEvents: 'none' }} />
        {connected && !frame && (
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', color: 'var(--tb-inverse-muted)' }}><Ico n="loader-2" s={20} spin /></div>
        )}
        {!connected && (
          <div style={{ position: 'absolute', inset: 0, display: 'grid', placeItems: 'center', textAlign: 'center', padding: 16, color: 'var(--tb-inverse-muted)', fontSize: 12.5 }}>
            {connecting ? <span style={{ display: 'flex', gap: 8, alignItems: 'center' }}><Ico n="loader-2" s={16} spin /> Connecting…</span>
              : (
                <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', gap: 10 }}>
                  {conn && conn.error ? <span style={{ color: 'var(--tb-fail)' }}>{conn.error}</span> : <span>Not connected</span>}
                  <Btn sm ico="plug" onClick={(e) => { e.stopPropagation(); onConnect(); }}>{conn && conn.error ? 'Try again' : 'Connect'}</Btn>
                </div>
              )}
          </div>
        )}
        {touch && (
          <span key={touch.key} className="tb-device-touch" style={{ left: touch.left + '%', top: touch.top + '%' }} />
        )}
      </div>

      {connected && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }} onClick={(e) => e.stopPropagation()}>
          {isWeb && (
            <div className="tb-input" style={{ padding: '5px 6px 5px 10px' }}>
              <Ico n="globe" s={13} />
              <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="Go to URL"
                onKeyDown={(e) => { if (e.key === 'Enter') navigate(); }} aria-label="URL" />
              <Btn sm kind="ghost" onClick={navigate} disabled={!url.trim()}>Go</Btn>
            </div>
          )}
          {/* Wraps the keys under the text field on a narrow phone card rather than squeezing it. */}
          <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6, alignItems: 'center' }}>
            <div className="tb-input" style={{ flex: '1 1 230px', minWidth: 0, padding: '5px 6px 5px 10px' }}>
              <Ico n="keyboard" s={13} />
              <input value={text} onChange={(e) => setText(e.target.value)} placeholder="Type into the focused field"
                onKeyDown={(e) => { if (e.key === 'Enter') typeText(); }} aria-label="Text to type" />
              <Btn sm kind="ghost" onClick={typeText} disabled={!text}>Type</Btn>
            </div>
            <div style={{ display: 'flex', gap: 6 }}>
              {DCM.keysFor(device.platform).map((k) => (
                <Btn key={k} sm kind="ghost" title={'Press ' + k} onClick={() => send({ type: 'pressKey', key: k })}
                  ico={k === 'Back' ? 'arrow-left' : k === 'Home' ? 'house' : 'corner-down-left'} aria-label={'Press ' + k} />
              ))}
            </div>
          </div>
        </div>
      )}
      {err && <div style={{ fontSize: 12, color: 'var(--tb-fail)', wordBreak: 'break-word' }}>{err}</div>}
    </div>
  );
}

// Run something on the selected device: a tool from its catalog with its parameters filled in, or a
// typed step or verify for the AI to carry out. Either becomes a one-step trail. The tool's YAML is
// editable: once you change it by hand the form stops overwriting it, so it can also run any unified
// trail pasted in.
function ToolComposer({ device, connected, target, style }) {
  const driver = device ? device.driver : null;
  const platform = device ? device.platform : null;
  const [catalog, setCatalog] = React.useState({ driver: null, tools: null, loading: false, error: null });
  const [toolName, setToolName] = React.useState('');
  const [values, setValues] = React.useState({});
  const [yaml, setYaml] = React.useState('');
  const [yamlEdited, setYamlEdited] = React.useState(false);
  const [run, setRun] = React.useState(null); // { state: 'running' | 'ok' | 'error', message, ms }
  const [mode, setMode] = React.useState('tool'); // 'tool' | 'prompt'
  const [promptKind, setPromptKind] = React.useState('step'); // 'step' | 'verify'
  const [promptText, setPromptText] = React.useState('');

  // A tool's form and YAML are for one device's platform and target. Switching either starts the
  // tool over, so Run never sends one device's recording to another.
  React.useEffect(() => {
    setToolName(''); setValues({}); setYaml(''); setYamlEdited(false); setRun(null);
  }, [device && device.id, driver, target]);

  // The catalog depends on the driver and on the daemon's target (target tools join it).
  React.useEffect(() => {
    if (!driver) { setCatalog({ driver: null, tools: null, loading: false, error: null }); return; }
    let cancelled = false;
    setCatalog({ driver, tools: null, loading: true, error: null });
    TB.fetchDriverToolCatalog(driver).then((tools) => {
      if (cancelled) return;
      if (!tools) setCatalog({ driver, tools: null, loading: false, error: 'Could not load the tool catalog.' });
      else setCatalog({ driver, tools: tools.slice().sort((a, b) => a.name.localeCompare(b.name)), loading: false, error: null });
    });
    return () => { cancelled = true; };
  }, [driver, target]);

  const tools = catalog.tools || [];
  const tool = tools.find((t) => t.name === toolName) || null;

  const pickTool = (name) => {
    const t = tools.find((x) => x.name === name) || null;
    setToolName(name);
    const v = t ? DCM.initialValues(t) : {};
    setValues(v);
    setYaml(t ? DCM.toolYaml(t, v, platform) : '');
    setYamlEdited(false);
    setRun(null);
  };
  const setValue = (name, v) => {
    const next = { ...values, [name]: v };
    setValues(next);
    if (tool && !yamlEdited) setYaml(DCM.toolYaml(tool, next, platform));
  };

  const runYaml = mode === 'prompt' ? DAM.promptYaml(promptKind, promptText) : yaml;

  async function runIt() {
    const tbId = DCM.deviceIdFor(device);
    if (!tbId || !runYaml.trim()) return;
    const started = Date.now();
    setRun({ state: 'running', message: null, ms: 0 });
    const r = await TB.runTrailYamlOnDevice(tbId, runYaml);
    const ms = Date.now() - started;
    // Android hands the run to the on-device runner and answers once it is accepted.
    const accepted = device.platform === 'android';
    setRun(r.ok
      ? { state: 'ok', message: accepted ? 'Sent to the device' : mode === 'prompt' && promptKind === 'verify' ? 'Verified' : 'Completed', ms }
      : { state: 'error', message: DAM.runErrorSummary(r.error), ms });
  }
  const switchMode = (m) => { setMode(m); setRun(null); };

  const params = tool ? DCM.paramsOf(tool).filter((p) => DCM.isVisible(p, values)) : [];
  const required = params.filter((p) => !p.optional);
  const optional = params.filter((p) => p.optional);

  return (
    <div className="tb-card" data-testid="tool-composer"
      style={{ width: 380, flex: '0 0 auto', padding: 14, boxSizing: 'border-box', display: 'flex', flexDirection: 'column', gap: 12, minHeight: 0, overflow: 'auto', ...style }}>
      <div>
        <div style={{ fontSize: 14, fontWeight: 600, display: 'flex', alignItems: 'center', gap: 8 }}><Ico n="play" s={15} /> Run</div>
        <div className="tb-sub" style={{ marginTop: 4 }}>
          {device ? <>On <b>{device.name}</b>{connected ? '' : ' (connect it first)'}</> : 'Select a device to load its tools.'}
        </div>
      </div>
      {device && (
        <div className="tb-tabs" role="tablist" aria-label="Run a tool or a prompt" style={{ marginTop: -4 }}>
          {[['tool', 'Tool', 'wrench'], ['prompt', 'Prompt', 'sparkles']].map(([id, label, ico]) => (
            <button key={id} type="button" role="tab" aria-selected={mode === id} className={'tb-tab ' + (mode === id ? 'active' : '')} onClick={() => switchMode(id)}
              style={{ display: 'flex', alignItems: 'center', gap: 7, cursor: 'pointer' }}>
              <Ico n={ico} s={14} />{label}
            </button>
          ))}
        </div>
      )}
      {device && mode === 'prompt' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
          <Select full value={promptKind} onChange={(e) => { setPromptKind(e.target.value); setRun(null); }} title="Prompt kind"
            options={[{ value: 'step', label: 'Step - the AI carries it out' }, { value: 'verify', label: 'Verify - the AI checks the screen' }]} />
          <textarea value={promptText} aria-label="Prompt" spellCheck={false}
            placeholder={promptKind === 'verify' ? 'The cart shows 2 items' : 'Add a banana to the cart'}
            onChange={(e) => setPromptText(e.target.value)}
            onKeyDown={(e) => { if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) runIt(); }}
            style={{ minHeight: 72, resize: 'vertical', fontSize: 13, lineHeight: 1.5, padding: '8px 10px', borderRadius: 8, fontFamily: 'inherit',
              background: 'var(--bg-standard)', color: 'var(--text-standard)', border: '1px solid var(--tb-hairline)' }} />
          <div className="tb-sub" style={{ fontSize: 11.5 }}>Runs one AI step on the live device. ⌘↵ runs it.</div>
        </div>
      )}
      {device && mode === 'tool' && (
        <Select searchable full value={toolName} onChange={(e) => pickTool(e.target.value)} title="Tool"
          options={[{ value: '', label: catalog.loading ? 'Loading tools…' : 'Choose a tool…' }].concat(tools.map((t) => ({ value: t.name, label: t.name })))} />
      )}
      {mode === 'tool' && catalog.error && <div style={{ fontSize: 12, color: 'var(--tb-fail)' }}>{catalog.error}</div>}
      {mode === 'tool' && tool && tool.description && <div className="tb-sub" style={{ fontSize: 12, whiteSpace: 'pre-wrap' }}>{tool.description}</div>}
      {mode === 'tool' && tool && required.map((p) => <ToolParamField key={p.name} param={p} value={values[p.name]} onChange={(v) => setValue(p.name, v)} />)}
      {mode === 'tool' && tool && optional.length > 0 && (
        <details open={required.length === 0}>
          <summary style={{ cursor: 'pointer', fontSize: 12, color: 'var(--text-subtle-variant)' }}>{optional.length} optional parameter{optional.length === 1 ? '' : 's'}</summary>
          <div style={{ display: 'flex', flexDirection: 'column', gap: 10, marginTop: 10 }}>
            {optional.map((p) => <ToolParamField key={p.name} param={p} value={values[p.name]} onChange={(v) => setValue(p.name, v)} />)}
          </div>
        </details>
      )}
      {device && mode === 'tool' && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between' }}>
            <span style={{ fontSize: 12, color: 'var(--text-subtle-variant)' }}>Trail YAML</span>
            {yamlEdited && tool && (
              <a style={{ fontSize: 12, color: 'var(--tb-link)', cursor: 'pointer' }}
                onClick={() => { setYaml(DCM.toolYaml(tool, values, platform)); setYamlEdited(false); }}>Reset to form</a>
            )}
          </div>
          <textarea className="tb-mono" value={yaml} spellCheck={false} aria-label="Trail YAML"
            placeholder={'Pick a tool above, or paste a trail:\n\ntrail:\n  - step: Open settings\n    recording:\n      ' + (platform || 'android') + ':\n        - tapOnPoint:\n            x: 100\n            y: 200'}
            onChange={(e) => { setYaml(e.target.value); setYamlEdited(true); }}
            style={{ minHeight: 130, resize: 'vertical', fontSize: 12, lineHeight: 1.5, padding: '8px 10px', borderRadius: 8,
              background: 'var(--bg-standard)', color: 'var(--text-standard)', border: '1px solid var(--tb-hairline)' }} />
        </div>
      )}
      {device && (
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <Btn sm kind="primary" ico={run && run.state === 'running' ? 'loader-2' : 'play'} spin={!!(run && run.state === 'running')}
            disabled={!connected || !runYaml.trim() || !!(run && run.state === 'running')} onClick={runIt}>Run</Btn>
          {run && run.state === 'running' && mode === 'prompt' && <span className="tb-sub" style={{ fontSize: 12 }}>The AI is working…</span>}
          {run && run.state !== 'running' && (
            <span data-testid="tool-run-result" style={{ fontSize: 12, color: run.state === 'ok' ? 'var(--tb-pass)' : 'var(--tb-fail)', wordBreak: 'break-word', minWidth: 0 }}>
              {run.message}{run.state === 'ok' ? ' · ' + formatRunMs(run.ms) : ''}
            </span>
          )}
        </div>
      )}
    </div>
  );
}

function formatRunMs(ms) {
  return ms < 1000 ? ms + ' ms' : (ms / 1000).toFixed(1) + ' s';
}

function ToolParamField({ param, value, onChange }) {
  const kind = DCM.inputKindFor(param);
  const v = value == null ? '' : String(value);
  const label = (
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 6, marginBottom: 4 }}>
      <span className="tb-mono" style={{ fontSize: 12, fontWeight: 600 }}>{param.name}</span>
      <span style={{ fontSize: 11, color: 'var(--text-subtle)' }}>{param.type}</span>
    </div>
  );
  let control;
  if (kind === 'select' || kind === 'boolean') {
    const choices = kind === 'boolean' ? ['true', 'false'] : param.validValues;
    const opts = (param.optional ? [{ value: '', label: '(unset)' }] : []).concat(choices.map((c) => ({ value: c, label: c })));
    control = <Select full value={v} onChange={(e) => onChange(e.target.value)} options={opts} title={param.name} />;
  } else {
    control = (
      <div className="tb-input" style={{ padding: '6px 10px' }}>
        <input type={kind === 'number' ? 'number' : 'text'} value={v} onChange={(e) => onChange(e.target.value)}
          aria-label={param.name} placeholder={param.optional ? 'optional' : ''} />
      </div>
    );
  }
  return (
    <div title={param.description || undefined}>
      {label}
      {control}
      {param.description && <div style={{ fontSize: 11, color: 'var(--text-subtle)', marginTop: 3, lineHeight: 1.4 }}>{param.description}</div>}
    </div>
  );
}

// One device with the whole screen: its mirror as large as fits, and beside it what has run on it -
// the steps and tools of its recent sessions, live - above the composer that runs more.
function DeviceFocusView({ device, conn, active, target, go, onBack, onConnect, onDisconnect, onDims, onLost }) {
  useLucide();
  const connected = !!(conn && conn.status === 'connected');
  const areaRef = React.useRef(null);
  const [area, setArea] = React.useState({ w: 0, h: 0 });
  React.useEffect(() => {
    const el = areaRef.current;
    if (!el || typeof ResizeObserver === 'undefined') return;
    const ro = new ResizeObserver(() => setArea({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  // Room for the card's header and its controls under the mirror.
  const chrome = device.platform === 'web' ? 150 : 120;
  const maxH = Math.max(240, area.h - chrome);

  return (
    <div className="tb-in" style={{ padding: '28px 32px', height: '100%', boxSizing: 'border-box', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
      <ScreenHead ico="monitor-smartphone" iconColor="var(--tb-running)" eyebrow="Live device" title={device.name}
        sub={<>Click to tap, drag to swipe, hold or right-click to long-press. Everything that runs on it shows on the right as it happens.</>}
        right={<Btn sm kind="ghost" ico="minimize-2" onClick={onBack}>All devices</Btn>} />
      <div style={{ flex: 1, minHeight: 0, display: 'flex', gap: 18 }}>
        <div ref={areaRef} style={{ flex: 1, minWidth: 0, minHeight: 0, display: 'flex', justifyContent: 'center', alignItems: 'flex-start', overflow: 'auto' }}>
          <DeviceConsoleCard device={device} conn={conn} active={active} maxH={maxH} gridW={area.w}
            selected={false} onSelect={() => {}} onConnect={onConnect} onDisconnect={onDisconnect} onDims={onDims} onLost={onLost} />
        </div>
        <div style={{ width: 420, flex: '0 0 auto', minHeight: 0, display: 'flex', flexDirection: 'column', gap: 12 }}>
          <DeviceActivity device={device} logSessionId={conn && conn.logSessionId} active={active} go={go} />
          <ToolComposer device={device} connected={connected} target={target} style={{ width: '100%', maxHeight: '55%' }} />
        </div>
      </div>
    </div>
  );
}

// How far back a focused device's feed reaches, and how many sessions it follows at once.
const ACTIVITY_WINDOW_MS = 60 * 60 * 1000;
const ACTIVITY_MAX_SESSIONS = 5;

// The sessions a device's feed follows, each tailed over the session stream and folded into steps.
// Returns [{ id, row, activity }] in the order they started. A session's records are kept slim (see
// DeviceActivityModel.slimLog) and indexed by the stream's event id, so a reconnect that replays the
// backlog overwrites instead of doubling.
function useDeviceActivity(deviceId, logSessionId, active) {
  const sessions = TB.useSessions();
  const rows = sessions.data || [];
  const followed = React.useMemo(() => {
    const picked = DAM.sessionsForDevice(rows, deviceId, Date.now(), ACTIVITY_WINDOW_MS, ACTIVITY_MAX_SESSIONS).map((r) => r.id);
    if (logSessionId && picked.indexOf(logSessionId) < 0) picked.push(logSessionId);
    return picked;
  }, [rows, deviceId, logSessionId]);
  const key = followed.join('|');

  const logsRef = React.useRef(new Map()); // id -> slim logs by event id
  const doneRef = React.useRef(new Set());
  const streamsRef = React.useRef(new Map());
  const followedRef = React.useRef(followed);
  const [version, setVersion] = React.useState(0);
  const dirtyRef = React.useRef(false);

  // Opens a stream for every followed session that has none. Also run on a timer: a connection's
  // session has no folder until its first log, and the daemon answers a stream for it with
  // "session not found", so that one is retried until it exists.
  const openMissing = React.useCallback(() => {
    const streams = streamsRef.current;
    followedRef.current.forEach((id) => {
      if (streams.has(id) || doneRef.current.has(id)) return;
      if (!logsRef.current.has(id)) logsRef.current.set(id, []);
      const es = new EventSource('/trailrunner/api/session/' + encodeURIComponent(id) + '/stream');
      es.addEventListener('log', (e) => {
        let raw = null;
        try { raw = JSON.parse(e.data); } catch (_) { return; }
        const list = logsRef.current.get(id);
        const at = Number(e.lastEventId);
        const slim = DAM.slimLog(raw);
        if (Number.isFinite(at)) list[at] = slim; else list.push(slim);
        dirtyRef.current = true;
      });
      es.addEventListener('done', () => { doneRef.current.add(id); es.close(); streams.delete(id); dirtyRef.current = true; });
      // The daemon's own `error` event carries data (no such session yet); a dropped connection
      // does not, and EventSource reconnects that by itself.
      es.addEventListener('error', (e) => { if (e && e.data) { es.close(); if (streams.get(id) === es) streams.delete(id); } });
      streams.set(id, es);
    });
  }, []);

  React.useEffect(() => {
    followedRef.current = followed;
    const streams = streamsRef.current;
    if (!active) {
      streams.forEach((es) => es.close());
      streams.clear();
      return;
    }
    streams.forEach((es, id) => { if (followed.indexOf(id) < 0) { es.close(); streams.delete(id); } });
    openMissing();
    const t = setInterval(openMissing, 2000);
    return () => clearInterval(t);
  }, [key, active]);

  React.useEffect(() => () => { streamsRef.current.forEach((es) => es.close()); streamsRef.current.clear(); }, []);

  // A backlog arrives as a burst of events; redraw at most a few times a second.
  React.useEffect(() => {
    const t = setInterval(() => { if (dirtyRef.current) { dirtyRef.current = false; setVersion((v) => v + 1); } }, 250);
    return () => clearInterval(t);
  }, []);

  return React.useMemo(() => {
    const byId = new Map(rows.map((r) => [r.id, r]));
    return followed
      .map((id) => {
        const slims = (logsRef.current.get(id) || []).filter(Boolean);
        const firstTs = slims.length ? slims[0].ts : 0;
        const row = byId.get(id) || null;
        return { id, row, activity: DAM.fold(slims), startTs: (row && row.timestampMs) || firstTs };
      })
      .filter((g) => g.activity.items.length > 0)
      .sort((a, b) => a.startTs - b.startTs);
  }, [version, key, rows]);
}

function DeviceActivity({ device, logSessionId, active, go }) {
  const groups = useDeviceActivity(device.id, logSessionId, active);
  const running = groups.some((g) => g.activity.now);
  // Ticks the "for 4s" on the running step.
  const [, setTick] = React.useState(0);
  React.useEffect(() => {
    if (!running) return;
    const t = setInterval(() => setTick((n) => n + 1), 1000);
    return () => clearInterval(t);
  }, [running]);

  // Stay pinned to the newest entry unless you have scrolled up to read something.
  const scrollRef = React.useRef(null);
  const pinnedRef = React.useRef(true);
  const onScroll = () => {
    const el = scrollRef.current;
    if (el) pinnedRef.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40;
  };
  React.useLayoutEffect(() => {
    const el = scrollRef.current;
    if (el && pinnedRef.current) el.scrollTop = el.scrollHeight;
  });

  return (
    <div className="tb-card" data-testid="device-activity"
      style={{ flex: 1, minHeight: 180, padding: 0, display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>
      <div style={{ padding: '12px 14px', borderBottom: '1px solid var(--tb-hairline)', display: 'flex', alignItems: 'center', gap: 8 }}>
        <Ico n="activity" s={15} />
        <span style={{ fontSize: 14, fontWeight: 600, flex: 1 }}>Activity</span>
        {running && <span style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 12, color: 'var(--tb-running)' }}><Dot c="var(--tb-running)" cls="tb-pulse" s={7} /> Live</span>}
      </div>
      <div ref={scrollRef} onScroll={onScroll} style={{ flex: 1, minHeight: 0, overflowY: 'auto', padding: '10px 14px', display: 'flex', flexDirection: 'column', gap: 14 }}>
        {groups.length === 0 && (
          <div className="tb-sub" style={{ fontSize: 12.5, lineHeight: 1.5 }}>
            Nothing has run on this device in the last hour. Run a tool or a prompt below, or start a trail on it, and each step and tool shows here as it happens.
          </div>
        )}
        {groups.map((g) => <ActivityGroup key={g.id} group={g} go={go} />)}
      </div>
    </div>
  );
}

function ActivityGroup({ group, go }) {
  const { activity, row, id } = group;
  const title = DAM.sessionLabel(id, row && row.title, activity.title, group.startTs);
  const state = activity.now ? 'running' : activity.ended;
  return (
    <div data-testid={'activity-session-' + id} style={{ display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: 11.5, color: 'var(--text-subtle-variant)' }}>
        <Dot c={state === 'running' ? 'var(--tb-running)' : state === 'failed' ? 'var(--tb-fail)' : state === 'passed' ? 'var(--tb-pass)' : 'var(--text-subtle)'} s={7} />
        <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap', fontWeight: 600 }} title={title}>{title}</span>
        {row && <span>{row.ago}</span>}
        {row && go && <a style={{ color: 'var(--tb-link)', cursor: 'pointer' }} onClick={() => go('runs', { sel: id })}>Open in Runs</a>}
      </div>
      {activity.items.map((item, i) => item.kind === 'step'
        ? <ActivityStep key={i} step={item} now={item.state === 'running' ? activity.now : null} />
        : <ActivityToolRow key={i} tool={item} loose />)}
    </div>
  );
}

const STEP_STATE_ICONS = {
  running: ['loader-2', 'var(--tb-running)'],
  passed: ['circle-check', 'var(--tb-pass)'],
  failed: ['circle-x', 'var(--tb-fail)'],
  stopped: ['ban', 'var(--text-subtle)'],
};

function ActivityStep({ step, now }) {
  const [ico, color] = STEP_STATE_ICONS[step.state] || STEP_STATE_ICONS.stopped;
  const ms = step.endTs && step.startTs ? step.endTs - step.startTs : 0;
  const failed = step.state === 'failed';
  return (
    <div data-testid="activity-step" data-state={step.state}
      style={{ border: '1px solid ' + (step.state === 'running' ? 'var(--tb-running)' : 'var(--tb-hairline)'), borderRadius: 8, padding: '8px 10px',
        display: 'flex', flexDirection: 'column', gap: 6 }}>
      <div style={{ display: 'flex', alignItems: 'flex-start', gap: 8 }}>
        <Ico n={ico} s={15} c={color} spin={step.state === 'running'} style={{ marginTop: 1, flex: '0 0 auto' }} />
        <div style={{ flex: 1, minWidth: 0, fontSize: 13, lineHeight: 1.4 }}>
          <span style={{ fontSize: 10.5, fontWeight: 600, letterSpacing: 0.4, textTransform: 'uppercase', color: 'var(--text-subtle)', marginRight: 6 }}>
            {step.stepKind === 'verify' ? 'Verify' : 'Step'}
          </span>
          {step.text}
        </div>
        {ms > 0 && <span className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle)' }}>{formatRunMs(ms)}</span>}
      </div>
      {step.tools.length > 0 && (
        <div style={{ display: 'flex', flexDirection: 'column', gap: 3, marginLeft: 7, paddingLeft: 14, borderLeft: '1px solid var(--tb-hairline)' }}>
          {step.tools.map((t, i) => <ActivityToolRow key={i} tool={t} />)}
        </div>
      )}
      {now && (
        <div data-testid="activity-now" style={{ display: 'flex', alignItems: 'center', gap: 7, marginLeft: 7, paddingLeft: 14, borderLeft: '1px dashed var(--tb-running)', fontSize: 12, color: 'var(--tb-running)' }}>
          <Ico n="loader-2" s={12} spin />
          <span className={now.tool ? 'tb-mono' : undefined}>{now.label}</span>
          {step.startTs > 0 && <span style={{ color: 'var(--text-subtle)' }}>· {Math.max(0, Math.round((Date.now() - step.startTs) / 1000))}s</span>}
        </div>
      )}
      {step.note && (
        <div style={{ fontSize: 12, lineHeight: 1.45, color: failed ? 'var(--tb-fail)' : 'var(--text-subtle-variant)', wordBreak: 'break-word' }}>{step.note}</div>
      )}
    </div>
  );
}

function ActivityToolRow({ tool, loose }) {
  return (
    <div data-testid="activity-tool" style={{ display: 'flex', flexDirection: 'column', gap: 2, padding: loose ? '2px 10px' : 0 }}>
      <div style={{ display: 'flex', alignItems: 'baseline', gap: 7, fontSize: 12, minWidth: 0 }}>
        <Ico n={tool.ok ? (loose ? 'pointer' : 'check') : 'x'} s={12} c={tool.ok ? 'var(--text-subtle-variant)' : 'var(--tb-fail)'} style={{ flex: '0 0 auto', alignSelf: 'center' }} />
        <span className="tb-mono" style={{ fontWeight: 600, flex: '0 0 auto' }}>{tool.name}</span>
        <span className="tb-mono" style={{ flex: 1, minWidth: 0, color: 'var(--text-subtle)', overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={tool.args}>{tool.args}</span>
        {tool.ms > 0 && <span className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle)', flex: '0 0 auto' }}>{formatRunMs(tool.ms)}</span>}
      </div>
      {tool.error && <div style={{ fontSize: 11.5, color: 'var(--tb-fail)', marginLeft: 19, wordBreak: 'break-word' }}>{tool.error}</div>}
    </div>
  );
}

window.DeviceConsoleScreen = DeviceConsoleScreen;
