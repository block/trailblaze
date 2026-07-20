// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function RunConfigDialog({ trail: initialTrail, seed, pinnedId, go, close, closing }) {
  const [trail, setTrail] = React.useState(initialTrail || null);
  const [trailQuery, setTrailQuery] = React.useState('');
  const [rightTab, setRightTab] = React.useState('steps');
  // Single-page config: the left rail jumps to a section and the body's scroll
  // position drives which one is highlighted (scroll-spy).
  const [activeSection, setActiveSection] = React.useState('target');
  const scrollRef = React.useRef(null);
  const sectionEls = React.useRef({});
  const registerSection = (id, el) => { if (el) sectionEls.current[id] = el; };
  const onBodyScroll = () => {
    const c = scrollRef.current;
    if (!c) return;
    const cTop = c.getBoundingClientRect().top;
    let cur = SECTIONS[0][0];
    for (const [id] of SECTIONS) {
      const el = sectionEls.current[id];
      if (el && el.getBoundingClientRect().top - cTop <= 90) cur = id;
    }
    setActiveSection(cur);
  };
  const jumpToSection = (id) => {
    const c = scrollRef.current;
    const el = sectionEls.current[id];
    if (!c || !el) return;
    c.scrollBy({ top: el.getBoundingClientRect().top - c.getBoundingClientRect().top - 10, behavior: 'smooth' });
  };
  const [phase, setPhase] = React.useState('config');
  const [runError, setRunError] = React.useState(null);
  const [copied, setCopied] = React.useState(false);
  // In-flight guard: run() awaits several round trips (connect, YAML fetch, target switch) with
  // the dialog still up, so without this a click stampede dispatches one run per click.
  const [launching, setLaunching] = React.useState(false);

  const trailsResult = TB.useTrails();
  const allTrails = trailsResult.data || [];
  const devicesResult = TB.useDevices();
  const deviceList = devicesResult.data || [];
  const [gt] = TB.useGlobalTarget(); // active target + its selected devices (the target picker)
  // The target selection holds a set of devices; a run defaults to the first one (the
  // user can switch device below). gtFirstDevice is that default.
  const gtFirstDevice = (gt && gt.deviceIds && gt.deviceIds[0]) || null;

  const [targetApps, setTargetApps] = React.useState(null);
  React.useEffect(() => {
    let cancelled = false;
    TB.getTargetApps().then((r) => { if (!cancelled) setTargetApps(r); });
    return () => { cancelled = true; };
  }, []);

  const seedDeviceId = seed && seed.deviceId;
  const [deviceId, setDeviceId] = React.useState(seedDeviceId || gtFirstDevice || pinnedId || null);
  React.useEffect(() => {
    if (deviceId && deviceList.find((d) => d.id === deviceId)) return;
    const inList = (id) => !!(id && deviceList.find((d) => d.id === id));
    // Prefer the seeded device (launched from the board for that variant), then the target
    // picker's first device, then the pinned device, then the first connected device.
    const next = inList(seedDeviceId) ? seedDeviceId : inList(gtFirstDevice) ? gtFirstDevice : inList(pinnedId) ? pinnedId : (deviceList[0] ? deviceList[0].id : null);
    if (next !== deviceId) setDeviceId(next);
  }, [devicesResult.data]);
  const selectedDevice = deviceList.find((d) => d.id === deviceId) || null;

  const detail = TB.useTrailDetail(trail ? trail.id : null);

  const [connectedId, setConnectedId] = React.useState(null);
  React.useEffect(() => {
    if (!selectedDevice || selectedDevice.platform === 'web' || connectedId === selectedDevice.id) return;
    let cancelled = false;
    const tbId = { instanceId: selectedDevice.id, trailblazeDevicePlatform: (selectedDevice.platform || '').toUpperCase() };
    TB.connectDevice(tbId).then((ok) => { if (!cancelled && ok) setConnectedId(selectedDevice.id); });
    return () => { cancelled = true; };
  }, [selectedDevice && selectedDevice.id]);

  const declaredTarget = trail && trail.target ? trail.target : null;
  const currentTarget = (targetApps && targetApps.currentTargetAppId) || null;

  // Installed target apps on the selected device — the wired "Target app" picker.
  // The trail's declared target is preselected when it's installed.
  const deviceApps = TB.useDeviceApps(selectedDevice && selectedDevice.platform !== 'web' ? selectedDevice.platform : null, selectedDevice ? selectedDevice.id : null);
  const installedTargets = (deviceApps.data && deviceApps.data.targets) || [];
  const deviceCurrentTarget = (deviceApps.data && deviceApps.data.currentTargetAppId) || null;
  const [targetApp, setTargetApp] = React.useState(null);
  React.useEffect(() => {
    if (installedTargets.length === 0) return;
    const ids = installedTargets.map((a) => a.id);
    if (targetApp && ids.includes(targetApp)) return;
    // A declared target that isn't resolvable on this device must NOT be silently substituted
    // with another app: leave the picker empty so the user makes an explicit choice (the run
    // warning explains). Auto-substituting bound a completely unrelated app (and launched its
    // bootstrap automation on connect) for a Settings trail.
    if (declaredTarget && !ids.includes(declaredTarget)) { setTargetApp(null); return; }
    // The trail's declared target wins (the trail is authored for it); otherwise fall
    // back to the app picked in the device picker, then the device's current target,
    // then the first installed. (The picked device always wins above.)
    const picked = (gt && (gt.deviceIds || []).includes(deviceId) && gt.target && ids.includes(gt.target)) ? gt.target : null;
    setTargetApp((declaredTarget && ids.includes(declaredTarget)) ? declaredTarget
      : picked
        || ((deviceCurrentTarget && ids.includes(deviceCurrentTarget)) ? deviceCurrentTarget
          : ids[0]));
  }, [installedTargets.map((a) => a.id).join(','), declaredTarget, deviceCurrentTarget, gt && gt.target, gtFirstDevice, deviceId]);

  const targetId = targetApp || declaredTarget || currentTarget || null;

  const [selfHeal, setSelfHeal] = React.useState(false);
  const [useRecordedSteps, setUseRecordedSteps] = React.useState(seed && seed.replay ? 'replay' : 'auto');
  const [agent, setAgent] = React.useState('TRAILBLAZE_RUNNER');
  const [maxLlmCalls, setMaxLlmCalls] = React.useState('50');
  const [llm, setLlm] = React.useState('');
  const [verbose, setVerbose] = React.useState(false);
  const [headless, setHeadless] = React.useState(true);

  const [captureVideo, setCaptureVideo] = React.useState(true);
  const [captureLogcat, setCaptureLogcat] = React.useState(false);
  const [captureNetwork, setCaptureNetwork] = React.useState(false);
  const [captureIosLogs, setCaptureIosLogs] = React.useState(false);
  const [captureAnalytics, setCaptureAnalytics] = React.useState(false);
  const [captureEvents, setCaptureEvents] = React.useState(true);
  const [saveRecording, setSaveRecording] = React.useState(true);
  const [noReport, setNoReport] = React.useState(false);
  const [markdown, setMarkdown] = React.useState(false);
  const [noLogging, setNoLogging] = React.useState(false);
  const [tags, setTags] = React.useState('');

  useLucide();

  const cfg = {
    trailPath: trail ? trail.path : null,
    trailId: trail ? trail.id : null,
    devicePlatform: selectedDevice ? selectedDevice.platform : null,
    deviceId: selectedDevice ? selectedDevice.id : null,
    selfHeal, useRecordedSteps, agent, maxLlmCalls, llm,
    verbose, headless, captureVideo, captureLogcat, captureNetwork, captureIosLogs, captureAnalytics, captureEvents,
    saveRecording, noReport, markdown, noLogging, tags,
  };
  const command = buildRunCommand(cfg);
  const liveYaml = applyYamlOverrides(detail.data?.yaml || '', {
    target: targetId,
    platform: selectedDevice ? selectedDevice.platform : null,
    driver: selectedDevice ? selectedDevice.driver : null,
  });
  const canRun = !!trail && !!selectedDevice && phase !== 'connecting' && !launching;

  // One-line live status per section, surfaced in the left nav so the rail reads
  // as a run summary rather than empty jump links.
  const captureCount = [captureVideo, captureLogcat, captureNetwork, captureIosLogs, captureAnalytics, captureEvents, saveRecording].filter(Boolean).length;
  const sectionSummaries = {
    target: selectedDevice ? selectedDevice.name : 'No device',
    behavior: (agent === 'TRAILBLAZE_RUNNER' ? 'Default runner' : agent) + (selfHeal ? ' · self-heal' : ''),
    capture: `${captureCount} artifact${captureCount === 1 ? '' : 's'}`,
  };

  function copyCommand() {
    navigator.clipboard.writeText(command).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); });
  }

  async function run() {
    if (!trail || !selectedDevice || launching) return;
    setLaunching(true);
    setRunError(null);
    // Every awaited call below is raced against a deadline: the RPC layer has no timeout, so a
    // wedged daemon/device otherwise leaves this dialog on a disabled "Starting…" forever with
    // no error and no way to retry (observed live: three Run clicks, 3+ minutes each, silent).
    const fail = (msg) => { setRunError(msg); setPhase('failed'); setLaunching(false); };
    try {
      const tbId = { instanceId: selectedDevice.id, trailblazeDevicePlatform: (selectedDevice.platform || '').toUpperCase() };
      if (connectedId !== selectedDevice.id) {
        setPhase('connecting');
        // Detailed connect keeps the daemon's real failure reason (e.g. "No target app selected...")
        // instead of a generic message the user can't act on.
        const conn = await TB.withTimeout(TB.connectDeviceDetailed(tbId), 45000);
        if (conn === '__timeout__') { fail('The daemon did not respond after 45s while connecting to the device. The device driver may be wedged - check the device and try again.'); return; }
        if (!conn.ok) { fail(conn.error || 'Could not connect to the device.'); return; }
        setConnectedId(selectedDevice.id);
      }
      const yaml = await TB.withTimeout(TB.fetchTrailYaml(trail.id), 30000);
      if (yaml === '__timeout__') { fail('The daemon did not respond after 30s while loading the trail. It may be wedged - try again.'); return; }
      if (!yaml) { fail('Could not load the trail YAML to run.'); return; }
      const maxCalls = parseInt(cfg.maxLlmCalls, 10);
      const opts = {
        selfHeal: cfg.selfHeal,
        useRecordedSteps: cfg.useRecordedSteps === 'replay' ? true : cfg.useRecordedSteps === 'ai' ? false : null,
        maxLlmCalls: (!isNaN(maxCalls) && maxCalls > 0 && String(cfg.maxLlmCalls) !== '50') ? maxCalls : null,
        agent: cfg.agent,
        captureVideo: cfg.captureVideo,
        captureLogcat: cfg.captureLogcat,
        captureNetworkTraffic: cfg.captureNetwork,
        captureIosLogs: cfg.captureIosLogs,
        captureAnalytics: cfg.captureAnalytics,
        captureEvents: cfg.captureEvents,
      };
      // Only rebind the daemon's global target on an explicit resolvable selection (targetApp).
      // targetId can still carry an unresolvable declared target - rebinding to it would fail, and
      // the run itself gets the honest server-side error ("target ... is not registered").
      if (targetApp && targetApp !== currentTarget) {
        const ok = await TB.withTimeout(TB.setTargetApp(targetApp), 30000);
        if (ok === '__timeout__') { fail('The daemon did not respond after 30s while switching the target app. It may be wedged - try again.'); return; }
        if (!ok) { fail('Could not switch to the selected target app.'); return; }
      }
      // No setLaunching(false) on the success path: the dialog is closing, and re-enabling the
      // button during the close animation would reopen the double-dispatch window.
      // Record the pending marker BEFORE navigating, then patch in the authoritative sessionId as
      // soon as dispatch answers - the Active screen locks onto that id instead of guessing which
      // session row is "the new run" (the guess mis-locked on fast finishes and stale rows).
      TB.recordPendingRun({ title: trail.title || trail.id, target: targetId, device: selectedDevice.name });
      // Fire-and-forget, but raced: a dispatch the daemon never answers must fail the pending
      // marker on the Active screen (a red "couldn't start" card), not evaporate silently. The
      // success shape is 2xx { ok: true, success, sessionId, error } - success:false is a dispatch
      // failure too, not just ok:false (non-2xx).
      TB.withTimeout(TB.dispatchRun(tbId, yaml, { ...opts, trailId: trail ? trail.id : null }), 45000).then((r) => {
        if (r === '__timeout__') { TB.failPendingRun('The run request was sent but the daemon never answered after 45s. It may be wedged - check the daemon and try again.'); return; }
        if (r && r.ok !== false && r.success !== false && r.sessionId) TB.setPendingRunSession(r.sessionId);
        else TB.failPendingRun((r && r.error) || 'Run failed to start');
      });
      go('active', { followLive: Date.now() });
      close();
    } catch (e) {
      fail('Starting the run failed unexpectedly: ' + ((e && e.message) || String(e)));
    }
  }

  const filteredTrails = trailQuery
    ? allTrails.filter((t) => `${t.title || ''} ${t.id} ${t.path || ''}`.toLowerCase().includes(trailQuery.toLowerCase()))
    : allTrails;

  return (
    <div className={'tb-overlay' + (closing ? ' closing' : '')} style={{ alignItems: 'stretch', justifyContent: 'center', padding: 14, paddingTop: 14 + (document.documentElement.classList.contains('tb-native') ? 28 : 0) }} onClick={close}>
      <div onClick={(e) => e.stopPropagation()}
        style={{ width: '100%', maxWidth: 1380, margin: 'auto', height: 'min(900px, 94vh)', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 16, boxShadow: '0 30px 90px rgba(0,0,0,.55)', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

        <div style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '16px 22px', borderBottom: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
          <div style={{ width: 40, height: 40, borderRadius: 12, background: 'rgba(0,224,19,.12)', border: '1px solid rgba(0,224,19,.32)', display: 'grid', placeItems: 'center', flex: '0 0 auto' }}>
            <Ico n="circle-play" s={20} c="var(--tb-primary-green)" />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 18, fontWeight: 600 }}>Configure run</div>
            {trail && <div className="tb-mono tb-sub" style={{ fontSize: 11.5, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{trail.path || trail.id}</div>}
          </div>
          <button onClick={close} title="Close" style={{ display: 'inline-flex', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-subtle)', padding: 4, marginLeft: 4 }}><Ico n="x" s={20} /></button>
        </div>

        {!trail ? (
          <div style={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', padding: '20px 24px' }}>
            <div className="tb-eyebrow" style={{ marginBottom: 10 }}>Choose a trail</div>
            <RcInput value={trailQuery} onChange={setTrailQuery} placeholder="Search trails…" style={{ marginBottom: 12 }} />
            <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 9 }}>
              {filteredTrails.length === 0 && <div className="tb-sub" style={{ padding: 12, fontSize: 12.5 }}>No matching trails.</div>}
              {filteredTrails.slice(0, 300).map((t) => (
                <div key={t.id} className="tb-pal-row" onClick={() => { setTrail(t); setActiveSection('target'); }} style={{ cursor: 'pointer' }}>
                  <Ico n="file-text" s={16} c="var(--text-subtle)" />
                  <span style={{ fontSize: 13.5, flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{t.title || t.id}</span>
                  <span className="tb-mono tb-sub" style={{ fontSize: 10.5 }}>{t.platform || ''}</span>
                </div>
              ))}
            </div>
          </div>
        ) : (
          <div style={{ flex: '1 1 auto', minHeight: 0, display: 'grid', gridTemplateColumns: '236px 1fr 440px' }}>
            <div style={{ borderRight: '1px solid var(--tb-hairline)', display: 'flex', flexDirection: 'column', minHeight: 0 }}>
              <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
                <SectionNav active={activeSection} onJump={jumpToSection} summaries={sectionSummaries} />
              </div>
            </div>
            {/* One scrolling page: every section stacked top-to-bottom. The left rail
                jumps here; this scroll position drives which rail item is active. */}
            <div ref={scrollRef} onScroll={onBodyScroll} style={{ overflowY: 'auto', padding: '22px 30px', display: 'flex', flexDirection: 'column', gap: 26 }}>
              <Section id="target" title="Target" sub={SECTIONS[0][2]} ico={SECTIONS[0][3]} registerRef={registerSection}>
                <TargetSection devices={deviceList} deviceId={deviceId} setDeviceId={setDeviceId} connectedId={connectedId}
                  installedTargets={installedTargets} targetApp={targetApp} setTargetApp={setTargetApp} appsLoading={deviceApps.loading} declaredTarget={declaredTarget} />
              </Section>
              <Section id="behavior" title="Behavior" sub={SECTIONS[1][2]} ico={SECTIONS[1][3]} registerRef={registerSection}>
                <BehaviorSection selfHeal={selfHeal} setSelfHeal={setSelfHeal} useRecordedSteps={useRecordedSteps} setUseRecordedSteps={setUseRecordedSteps}
                  agent={agent} setAgent={setAgent} maxLlmCalls={maxLlmCalls} setMaxLlmCalls={setMaxLlmCalls} llm={llm} setLlm={setLlm}
                  verbose={verbose} setVerbose={setVerbose} headless={headless} setHeadless={setHeadless} web={selectedDevice && selectedDevice.platform === 'web'} />
              </Section>
              <Section id="capture" title="Capture" sub={SECTIONS[2][2]} ico={SECTIONS[2][3]} registerRef={registerSection}>
                <CaptureSection captureVideo={captureVideo} setCaptureVideo={setCaptureVideo} captureLogcat={captureLogcat} setCaptureLogcat={setCaptureLogcat}
                  captureNetwork={captureNetwork} setCaptureNetwork={setCaptureNetwork} captureIosLogs={captureIosLogs} setCaptureIosLogs={setCaptureIosLogs}
                  captureAnalytics={captureAnalytics} setCaptureAnalytics={setCaptureAnalytics} captureEvents={captureEvents} setCaptureEvents={setCaptureEvents} saveRecording={saveRecording} setSaveRecording={setSaveRecording}
                  noReport={noReport} setNoReport={setNoReport} markdown={markdown} setMarkdown={setMarkdown} noLogging={noLogging} setNoLogging={setNoLogging}
                  tags={tags} setTags={setTags} />
              </Section>
            </div>
            <div style={{ borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-app)', minHeight: 0 }}>
              {/* The dialog isn't under the shell's screen Boundary, so a render throw in
                  the right rail (e.g. the Tools panel) would unmount the whole app. Contain it. */}
              <Boundary>
                <RightRail trail={trail} detail={detail} liveYaml={liveYaml} tab={rightTab} setTab={setRightTab}
                  targetId={targetId} driver={selectedDevice ? selectedDevice.driver : null} platform={selectedDevice ? selectedDevice.platform : null} />
              </Boundary>
            </div>
          </div>
        )}

        {trail && phase === 'failed' && (
          <div style={{ flex: '0 0 auto', borderTop: '1px solid var(--tb-hairline)', padding: '10px 22px', background: 'var(--bg-app)' }}>
            <div style={{ color: 'var(--tb-danger-text)', fontSize: 12.5, lineHeight: 1.5, padding: '9px 12px', background: 'rgba(248,71,82,.12)', border: '1px solid rgba(248,71,82,.25)', borderRadius: 8 }}>
              {runError || 'The run could not start.'}
            </div>
          </div>
        )}

        {trail && (
          // Footer is a single integrated bar: the live `trailblaze run …` command
          // (always visible, updates as you configure, wraps) with a small copy button,
          // and one green Run. Wizard nav (Back/Next) lives in the left step rail.
          <div style={{ display: 'flex', alignItems: 'stretch', gap: 12, padding: '12px 16px', borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-app)', flex: '0 0 auto' }}>
            <div className="tb-mono" style={{ position: 'relative', flex: 1, minWidth: 0, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 9, padding: '10px 42px 10px 13px', fontSize: 12, lineHeight: 1.6, color: 'var(--text-standard)', whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 92, overflowY: 'auto' }}>
              {command}
              <button data-testid="run-cmd-copy" onClick={copyCommand} title={copied ? 'Copied!' : 'Copy command'}
                className="tb-btn ghost sm"
                style={{ position: 'absolute', top: 6, right: 6, width: 28, height: 28, padding: 0, justifyContent: 'center' }}>
                <Ico n={copied ? 'check' : 'copy'} s={14} c={copied ? 'var(--tb-primary-green)' : undefined} />
              </button>
            </div>
            <div style={{ display: 'flex', alignItems: 'center', flex: '0 0 auto' }}>
              <span data-testid="run-config-run">
                <Btn kind="primary" ico="play" onClick={run} disabled={!canRun}>{phase === 'connecting' || launching ? 'Starting…' : 'Run'}</Btn>
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

window.RunConfigDialog = RunConfigDialog;
