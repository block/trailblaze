// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// The build-time transpile strips types regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function RunConfigDialog({ trail: initialTrail, seed, pinnedId, go, close, closing }) {
  const [trail, setTrail] = React.useState(initialTrail || null);
  const [trailQuery, setTrailQuery] = React.useState('');
  const [rightTab, setRightTab] = React.useState('steps');
  // Two panes and nothing else by default: WHICH DEVICES on the left, WHICH STEPS on the right, Play
  // and Record at the bottom. Model, agent, AI call limit, capture toggles and the CLI command are
  // all real but rarely touched, so they live behind one disclosure. Sticky, so anyone who does tune
  // them isn't reopening it every run. Visibility only: every default VALUE is unchanged.
  const [advanced, setAdvanced] = useStickyState('tb-run-advanced', false);
  const [phase, setPhase] = React.useState('config');
  const [runError, setRunError] = React.useState(null);
  // One entry per device a multi-device launch was asked to run on, so a device that failed to
  // start is reported as that device's failure instead of being averaged into a single message.
  const [runOutcomes, setRunOutcomes] = React.useState(null);
  const [copied, setCopied] = React.useState(false);
  // In-flight guard: run() awaits several round trips (connect, YAML fetch, target switch) with
  // the dialog still up, so without this a click stampede dispatches one run per click. Holds WHICH
  // verb is in flight ('play' | 'record'), so only that button says what it is doing.
  const [launching, setLaunching] = React.useState(null);
  const launchingRef = React.useRef(false);

  const trailsResult = TB.useTrails();
  const allTrails = trailsResult.data || [];
  const devicesResult = TB.useDevices();
  const deviceList = devicesResult.data || [];
  const [gt] = TB.useGlobalTarget(); // active target + its selected devices (the target picker)
  // The target selection holds a set of devices; a run starts out on the first one (the user can
  // check more devices, or a different one, below). gtFirstDevice is that default.
  const gtFirstDevice = (gt && gt.deviceIds && gt.deviceIds[0]) || null;

  const [targetApps, setTargetApps] = React.useState(null);
  React.useEffect(() => {
    let cancelled = false;
    TB.getTargetApps().then((r) => { if (!cancelled) setTargetApps(r); });
    return () => { cancelled = true; };
  }, []);

  const seedDeviceId = seed && seed.deviceId;
  // Which steps this run covers: 0-based inclusive indices into the trail's `trail:` list, always a
  // contiguous span (see RunStepsPane), and the whole trail until someone narrows it. `null` while
  // the trail's steps are still loading, or for a trail with no addressable steps at all.
  const [range, setRange] = React.useState(null);
  // A caller can seed the span - the steps board's and the YAML gutter's "from here to the end" - and
  // whether it means to Play or Record, which only decides which of the two buttons is emphasized.
  const seedRange = (seed && seed.stepRange) || null;
  const emphasis = seed && seed.mode === 'record' ? 'record' : 'play';
  // A run can go out to several devices at once (one run each), so the selection is a set. The
  // defaulting order for a freshly opened dialog lives in TbRunFanout.defaultDeviceIds.
  const [deviceIds, setDeviceIds] = React.useState(() => [seedDeviceId || gtFirstDevice || pinnedId].filter(Boolean));
  const deviceTouched = React.useRef(false);
  // Each Run click owns the dialog until the next one. A dispatch the previous click is still
  // waiting on can answer at any time, and it must not repaint outcomes the user has replaced.
  const launchSeq = React.useRef(0);
  // Dismissing the dialog ends its launch's claim too: a late answer must not repaint a dialog the
  // user closed, and must certainly not navigate the app out from under them.
  React.useEffect(() => { if (closing) launchSeq.current += 1; }, [closing]);
  React.useEffect(() => {
    const next = TbRunFanout.defaultDeviceIds({ selected: deviceIds, devices: deviceList, seedDeviceId, gtFirstDevice, pinnedId, touched: deviceTouched.current });
    if (next.join(',') !== deviceIds.join(',')) setDeviceIds(next);
  }, [devicesResult.data]);
  const selectedDevices = deviceIds.map((id) => deviceList.find((d) => d.id === id)).filter(Boolean);
  // The first selected device is the primary one: it drives the target-app picker, the YAML
  // preview and the platform-specific options, exactly as the single selected device used to.
  const selectedDevice = selectedDevices[0] || null;
  const deviceId = selectedDevice ? selectedDevice.id : null;
  const toggleDevice = (id) => { deviceTouched.current = true; setDeviceIds((cur) => TbRunFanout.toggleDeviceId(cur, id)); };

  const detail = TB.useTrailDetail(trail ? trail.id : null);
  // The trail's own steps, read from the file the same way the steps board reads them, so a step's
  // position here is the position the server slices and merges by. A trail this can't model - a
  // legacy list-root file - yields no steps and can only be run whole.
  const stepModel = React.useMemo(() => parseUnifiedModel(detail.data?.yaml || ''), [detail.data?.yaml]);
  const stepList = (stepModel && stepModel.steps) || [];
  const total = stepList.length;
  const detailSteps = detail.data?.steps || [];
  // Seed the span once the step count is known. Keyed on the count and the trail, not on the polled
  // YAML: re-running this on every poll would throw away a selection the user just made.
  React.useEffect(() => {
    if (!total) { setRange(null); return; }
    const clamp = (n) => Math.max(0, Math.min(n, total - 1));
    setRange(seedRange
      ? { start: clamp(seedRange.from), end: clamp(seedRange.to) }
      : { start: 0, end: total - 1 });
  }, [total, trail && trail.id]);
  const whole = !!(range && range.start === 0 && range.end === total - 1);
  // Click a step: run from there to the end. That is the case this exists for - the device is
  // already in the state the earlier steps would put it in. Shift-click moves the other end instead,
  // anchored on the current start, so the span stays contiguous however it is built.
  const pickStep = (i, extend) => setRange((r) => (extend && r
    ? { start: Math.min(r.start, i), end: Math.max(r.start, i) }
    : { start: i, end: total - 1 }));

  // If no caller/user selection anchors the run, prefer the device shape declared by the trail
  // instead of blindly taking the first connected device. Unified trails commonly declare this as
  // `config.devices.web: PLAYWRIGHT_NATIVE` (rather than top-level platform/driver), so inspect both.
  // This prevents a web trail from briefly binding to an iOS simulator and surfacing a bogus
  // "target isn't resolvable" warning before the user has touched the form.
  const deviceHints = React.useMemo(() => {
    const yaml = detail.data?.yaml || '';
    let config = {};
    try { config = (yaml && parseTrailYaml(yaml).config) || {}; } catch (_) {}
    const drivers = new Set();
    const platforms = new Set();
    if (config.driver) drivers.add(String(config.driver).toUpperCase());
    if (config.platform) platforms.add(String(config.platform).toLowerCase());
    Object.entries(config.devices || {}).forEach(([platform, value]) => {
      platforms.add(String(platform).toLowerCase());
      if (typeof value === 'string') drivers.add(value.toUpperCase());
      else if (value && typeof value === 'object' && value.driver) drivers.add(String(value.driver).toUpperCase());
    });
    return { drivers, platforms };
  }, [detail.data?.yaml, trail && trail.id]);
  React.useEffect(() => {
    // Explicit board launches and a complete global target selection are user intent. `pinnedId`
    // is only the shell's automatic first-device fallback, so a trail hint is allowed to beat it.
    // Once the user changes this dialog's picker, never auto-switch underneath them.
    if (seedDeviceId || (gt && gt.target && gtFirstDevice) || deviceTouched.current || deviceList.length === 0) return;
    const hinted = deviceList.find((d) => deviceHints.drivers.has(String(d.driver || '').toUpperCase()))
      || deviceList.find((d) => deviceHints.platforms.has(String(d.platform || '').toLowerCase()));
    if (hinted && hinted.id !== deviceId) setDeviceIds([hinted.id]);
  }, [deviceList, seedDeviceId, gt && gt.target, gtFirstDevice, deviceHints, deviceId]);

  // Which devices this dialog has connected, mapped to the target app each one was connected
  // under: a connect binds its target for the life of the connection, so the picker has to know
  // what a live connection is bound to before it can be trusted for this run.
  const [connectedTargets, setConnectedTargets] = React.useState({});
  const connectedIds = Object.keys(connectedTargets);
  // The ref is the authoritative copy and the state is the rendered mirror of it. Run reads the
  // bindings immediately after awaiting its own dials, and a `setState` one of those dials
  // scheduled has not necessarily been rendered by then - so reading state there would decide what
  // to release from the map as it looked before the dials landed.
  const connectedTargetsRef = React.useRef({});
  const bindConnected = (mutate) => {
    mutate(connectedTargetsRef.current);
    setConnectedTargets({ ...connectedTargetsRef.current });
  };
  // Devices with a connect already in flight, each mapped to that call's promise. Checking a second
  // device re-runs this effect while the first connect is still pending, and `connectedTargets`
  // can't have caught up yet - without this the same device gets dialed twice. Run awaits the
  // promises so it never decides what to release while a dial is still deciding what is bound.
  const connecting = React.useRef({});

  const declaredTarget = trail && trail.target ? trail.target : null;
  const currentTarget = (targetApps && targetApps.currentTargetAppId) || null;

  // Installed target apps come from the first checked device that can host an app, NOT simply the
  // primary one: a mixed-platform run that starts with a web device has no installed apps to read
  // there, and would offer no target at all for the Android device checked next to it.
  const appsDevice = TbRunFanout.appsDevice(selectedDevices);
  const deviceApps = TB.useDeviceApps(appsDevice ? appsDevice.platform : null, appsDevice ? appsDevice.id : null);
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
    // Judged against the device the apps were listed from, the same device the target will be
    // installed on: keyed on a web primary instead, the picker's own choice for the Android device
    // checked beside it would be discarded.
    const picked = (gt && appsDevice && (gt.deviceIds || []).includes(appsDevice.id) && gt.target && ids.includes(gt.target)) ? gt.target : null;
    setTargetApp((declaredTarget && ids.includes(declaredTarget)) ? declaredTarget
      : picked
        || ((deviceCurrentTarget && ids.includes(deviceCurrentTarget)) ? deviceCurrentTarget
          : ids[0]));
  }, [installedTargets.map((a) => a.id).join(','), declaredTarget, deviceCurrentTarget, gt && gt.target, gtFirstDevice, appsDevice && appsDevice.id]);

  const targetId = targetApp || declaredTarget || currentTarget || null;

  // Connect each selected device as it's checked, so the picker can show which ones are ready -
  // but only once the target app is resolved, and re-connecting any device this dialog bound to a
  // different one (TbRunFanout.connectPlan explains why the binding is what matters). A device
  // with nothing to install is ready as soon as the apps list has settled.
  const targetReady = !!targetApp || (!deviceApps.loading && installedTargets.length === 0);
  // No cancel-on-cleanup here, unlike the fetch effects above: checking another device re-runs this
  // effect, and dropping the first device's answer would strand it. The in-flight guard is already
  // cleared by then, so the picker would show it unconnected AND never dial it again.
  React.useEffect(() => {
    // Frozen once a launch starts: run() owns the connections from then on, and a dial or drop
    // started here in parallel isn't in `connecting.current` for it to wait on - it lands on a
    // device run() has already rebound and takes the fresh session away. Read off the ref so an
    // effect scheduled in the same pass as the launch still sees it; `launching` is in the deps
    // only to re-arm this after a failed run releases it.
    if (launchingRef.current) return;
    const plan = TbRunFanout.connectPlan({ devices: selectedDevices, connected: connectedTargetsRef.current, inFlight: connecting.current, targetApp, targetReady });
    // Each in-flight entry says what it is, so a launch can tell apart the two things this map
    // holds: a release (or a dial for some other target), whose answer changes what the launch must
    // release, versus a dial already binding the target the launch itself wants. Only the first kind
    // has to settle before a launch can plan - see the wait in `run()`. A release is flagged rather
    // than being spelled `target: null`, because `targetApp` is itself nullable and the two would
    // then be indistinguishable exactly when no target is resolved.
    plan.drop.forEach((device) => {
      connecting.current[device.id] = {
        release: true,
        p: TB.disconnectDevice(TbRunFanout.deviceRunId(device))
          .then(() => { bindConnected((m) => { delete m[device.id]; }); })
          .finally(() => { delete connecting.current[device.id]; }),
      };
    });
    plan.dial.forEach((device) => {
      connecting.current[device.id] = {
        target: targetApp,
        p: TB.connectDevice(TbRunFanout.deviceRunId(device), targetApp).then((ok) => {
          if (ok) bindConnected((m) => { m[device.id] = targetApp; });
        }).finally(() => { delete connecting.current[device.id]; }),
      };
    });
  }, [deviceIds.join(','), targetApp, targetReady, connectedIds.join(','), launching]);

  const [selfHeal, setSelfHeal] = React.useState(false);
  const [useRecordedSteps, setUseRecordedSteps] = React.useState(seed && seed.replay ? 'replay' : 'auto');
  const [agent, setAgent] = React.useState('TRAILBLAZE_RUNNER');
  const [maxLlmCalls, setMaxLlmCalls] = React.useState(String(DEFAULT_MAX_LLM_CALLS));
  const [llm, setLlm] = React.useState('');
  const [verbose, setVerbose] = React.useState(false);
  const [headless, setHeadless] = React.useState(true);

  const [captureVideo, setCaptureVideo] = React.useState(false);
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
  // One command per selected device: that IS the run, since each device gets its own run.
  const command = selectedDevices.length > 1
    ? selectedDevices.map((d) => buildRunCommand({ ...cfg, devicePlatform: d.platform, deviceId: d.id })).join('\n')
    : buildRunCommand(cfg);
  const liveYaml = applyYamlOverrides(detail.data?.yaml || '', {
    target: targetId,
    platform: selectedDevice ? selectedDevice.platform : null,
    driver: selectedDevice ? selectedDevice.driver : null,
  });
  // The YAML tab previews the trail the run actually dispatches, so a narrowed span shows the slice
  // - trailhead dropped, `Partial:` title - rather than the whole file the run wouldn't send. Falls
  // back to the whole document if the slice can't be built; run() reports that failure for real.
  const previewYaml = React.useMemo(() => {
    if (!range || whole) return liveYaml;
    const sliced = TM.sliceSteps(parseUnifiedModel(liveYaml), range.start, range.end);
    return sliced ? serializeUnifiedModel(sliced) : liveYaml;
  }, [liveYaml, range && range.start, range && range.end, whole]);
  // Keyed on the device the apps were read FROM, so a mixed-platform run is judged against the
  // device that can actually host the declared target rather than a web primary that can't.
  const declaredTargetUnavailable = !!(appsDevice
    && declaredTarget && !deviceApps.loading
    && !installedTargets.some((a) => a.id === declaredTarget) && !targetApp);
  // Deliberately NOT gated on a device being checked: an empty selection is refused with a reason
  // (see run()), which a disabled button couldn't give.
  const canRun = !!trail && !declaredTargetUnavailable && phase !== 'connecting' && !launching;

  function copyCommand() {
    navigator.clipboard.writeText(command).then(() => { setCopied(true); setTimeout(() => setCopied(false), 1500); });
  }

  async function run(mode) {
    if (!trail || launching) return;
    setRunError(null);
    setRunOutcomes(null);
    // Every awaited call below is raced against a deadline: the RPC layer has no timeout, so a
    // wedged daemon/device otherwise leaves this dialog on a disabled "Starting…" forever with
    // no error and no way to retry (observed live: three Run clicks, 3+ minutes each, silent).
    const fail = (msg) => { setRunError(msg); setPhase('failed'); launchingRef.current = false; setLaunching(null); };
    const refusal = TbRunFanout.selectionError(selectedDevices);
    if (refusal) { fail(refusal); return; }
    // Recording writes into per-step recording slots, which only a unified single-file trail has.
    if (mode === 'record' && !range) {
      fail("Recording writes each step's tool calls back into the trail file, and this trail has no steps to write into. Only a unified single-file trail can be recorded step by step.");
      return;
    }
    launchingRef.current = true;
    const seq = (launchSeq.current += 1);
    setLaunching(mode);
    try {
      setPhase('connecting');
      // Release any device this dialog bound to a different target app before dialing: the daemon
      // refuses a live connection bound to another target rather than rebinding it.
      //
      // Run must not plan that release while a dial it started is still deciding what to bind: an
      // in-flight dial has recorded nothing yet, so it reads as an unconnected device and nothing
      // gets released. Only the checked devices, since a dial for an unchecked one could time this
      // out over a device the run never touches.
      //
      // And only the dials that could bind something else (see `blockingDials`). This wait is the
      // second way a cold device lost its run: the dialog starts dialing the moment a device is
      // checked, so a device slow enough to matter is usually already dialing when Run is clicked,
      // and the launch died here at 45s without ever reaching the connect that knows how to wait.
      // The daemon serializes per device, so that connect joins this dial rather than racing it.
      const pending = TbRunFanout.blockingDials(selectedDevices, connecting.current, targetApp);
      if (pending.length > 0) {
        // allSettled: `all` rejects on the first failure and leaves the rest unsettled.
        const settled = await TB.withTimeout(Promise.allSettled(pending), TbRunFanout.RUN_TIMEOUT_MS);
        if (settled === '__timeout__') {
          // Failing beats planning against a map those releases are still about to change.
          fail(`A device connect this dialog started is still running after ${Math.round(TbRunFanout.RUN_TIMEOUT_MS / 1000)}s. Wait for it to finish, or reopen the dialog.`);
          return;
        }
      }
      const stale = TbRunFanout.connectPlan({ devices: selectedDevices, connected: connectedTargetsRef.current, targetApp, targetReady: true }).drop;
      if (stale.length > 0) {
        await Promise.all(stale.map((d) => TB.withTimeout(TB.disconnectDevice(TbRunFanout.deviceRunId(d)), TbRunFanout.RUN_TIMEOUT_MS)));
        bindConnected((m) => { stale.forEach((d) => { delete m[d.id]; }); });
      }
      // Connect every checked device first, before the daemon's global target is touched - the
      // order a single-device run has always used. Each device keeps the daemon's own failure
      // reason (e.g. "No target app selected...") instead of a generic message.
      const connects = await TbRunFanout.connectDevices(selectedDevices, {
        // Only a connection bound to the target app THIS run is for counts as already connected:
        // a device the dialog bound to another app has to be re-dialed, not reused.
        isConnected: (device) => connectedTargetsRef.current[device.id] === targetApp,
        // Undeadlined on purpose: connectDevices owns the connect deadline, because a device that
        // blows it has not failed - it is usually still starting up - and only the unwrapped promise
        // can be waited out. Deadlining here threw it away and cancelled runs that would have run.
        connect: (tbId) => TB.connectDeviceDetailed(tbId, targetApp),
      });
      // Only a device that actually answered is recorded as connected: this map is what tells the
      // NEXT launch it can skip the dial, and a device still connecting has not bound anything yet.
      bindConnected((m) => { connects.filter((c) => c.ok).forEach((c) => { m[c.device.id] = targetApp; }); });
      // A slow device still counts as reachable though: its run is on its way, so the launch must
      // not report "could not start on any device" over a device that is merely still connecting.
      const reachable = connects.filter((c) => c.ok || c.slow).map((c) => c.device.id);
      if (reachable.length === 0) {
        // Nothing to run on. One device keeps its bare reason in the banner; several devices get a
        // row each, so the user can see which device gave which reason.
        if (connects.length > 1) setRunOutcomes(connects);
        fail(connects.length > 1 ? 'The run could not start on any of the selected devices.' : connects[0].error);
        return;
      }
      const yaml = await TB.withTimeout(TB.fetchTrailYaml(trail.id), 30000);
      if (yaml === '__timeout__') { fail('The daemon did not respond after 30s while loading the trail. It may be wedged - try again.'); return; }
      if (!yaml) { fail('Could not load the trail YAML to run.'); return; }
      const maxCalls = parseInt(cfg.maxLlmCalls, 10);
      const opts = {
        selfHeal: cfg.selfHeal,
        useRecordedSteps: cfg.useRecordedSteps === 'replay' ? true : cfg.useRecordedSteps === 'ai' ? false : null,
        maxLlmCalls: (!isNaN(maxCalls) && maxCalls > 0 && maxCalls !== DEFAULT_MAX_LLM_CALLS) ? maxCalls : null,
        agent: cfg.agent,
        captureVideo: cfg.captureVideo,
        captureLogcat: cfg.captureLogcat,
        captureNetworkTraffic: cfg.captureNetwork,
        captureIosLogs: cfg.captureIosLogs,
        captureAnalytics: cfg.captureAnalytics,
        captureEvents: cfg.captureEvents,
      };
      // Every await since the click is now allowed to take real time rather than failing - the
      // connect can run 45s, the YAML load 30s - and the dialog is closable throughout. So each
      // step that changes something OUTSIDE this dialog re-checks that its launch is still the
      // current one first. This one rebinds the daemon's persisted target app, which outlives the
      // dialog entirely; the one below mints a card and navigates. Checked immediately before the
      // mutation rather than once up front, because another long await sits between the two.
      if (launchSeq.current !== seq) return;
      // Only rebind the daemon's global target on an explicit resolvable selection (targetApp).
      // targetId can still carry an unresolvable declared target - rebinding to it would fail, and
      // the run itself gets the honest server-side error ("target ... is not registered").
      if (targetApp && targetApp !== currentTarget) {
        const ok = await TB.withTimeout(TB.setTargetApp(targetApp), 30000);
        if (ok === '__timeout__') { fail('The daemon did not respond after 30s while switching the target app. It may be wedged - try again.'); return; }
        if (!ok) { fail('Could not switch to the selected target app.'); return; }
      }
      // Record hands the whole thing to the daemon: the server slices the same window it later
      // merges the recordings back into, so the two can't drift apart, and no client gets to name a
      // file the daemon will write. Play stays on the client - it writes nothing, so it needs no
      // write authority.
      const label = !range || whole
        ? 'this trail'
        : (range.start === range.end ? `step ${range.start + 1}` : `steps ${range.start + 1}-${range.end + 1}`);
      let runYaml = yaml;
      if (mode === 'record') {
        const reachable = connects.filter((c) => c.ok);
        const started = await TB.withTimeout(TB.recordTrailRange(trail.id, range.start, range.end, reachable.map((c) => TbRunFanout.deviceRunId(c.device)), {
          maxLlmCalls: opts.maxLlmCalls,
          agent: opts.agent,
          selfHeal: opts.selfHeal,
          captureVideo: opts.captureVideo,
          captureLogcat: opts.captureLogcat,
          captureNetworkTraffic: opts.captureNetworkTraffic,
          captureIosLogs: opts.captureIosLogs,
          captureAnalytics: opts.captureAnalytics,
          captureEvents: opts.captureEvents,
        }), TbRunFanout.RUN_TIMEOUT_MS);
        if (started === '__timeout__') {
          fail(`The daemon did not answer after ${Math.round(TbRunFanout.RUN_TIMEOUT_MS / 1000)}s while starting the recording of ${label}. Check the Runs screen before starting it again.`);
          return;
        }
        if (!started.ok || started.sessionIds.length === 0) { fail(started.error || `Could not start recording ${label}.`); return; }
        // A partial launch is not a success: some devices are recording and some never started, and
        // closing on that would leave the reader to notice the missing runs themselves. The reasons
        // are reported as text rather than as per-device rows, because the response says how many
        // runs started but not which device each one belongs to.
        const problems = connects
          .filter((c) => !c.ok)
          .map((c) => `${TbRunFanout.deviceRunId(c.device)}: ${c.error || 'could not connect'}`)
          .concat(started.error ? [started.error] : []);
        if (problems.length > 0) {
          // Reported without re-arming the buttons, unlike every other refusal on this path: some
          // devices ARE recording into this step window already, and `fail` would re-enable Record
          // so the obvious retry starts a second recording that merges into the same slots. The
          // launch is spent either way, so it stays spent - read the reasons, then close.
          setRunError(`Recording ${label} started on ${started.sessionIds.length} of ${connects.length} devices. ${problems.join('; ')}`);
          setPhase('failed');
          return;
        }
        // Same marker the Play path installs, for the same reason: the Active screen locks onto the
        // session the daemon named instead of guessing which row is "the new run". No `awaitsDispatch`
        // here - the recording route answers with its session ids, so the id is already in hand and
        // there is no later patch to wait for. Only for a single run, matching Play: the marker holds
        // one session, and a fan-out has no one run to follow.
        if (started.sessionIds.length === 1) {
          TB.recordPendingRun({ title: trail.title || trail.id, target: targetId, device: reachable[0].device.name, sessionId: started.sessionIds[0] });
        }
        go('runs', { followLive: Date.now() });
        close();
        return;
      }
      // A Play of every step is a run of the file as authored, trailhead included. Only a narrowed
      // span gets sliced, which is also what drops the trailhead.
      if (range && !whole) {
        const sliced = TM.sliceSteps(parseUnifiedModel(yaml), range.start, range.end);
        if (!sliced) { fail(`Could not read ${label} out of this trail. Run the whole trail instead, or fix the trail file first.`); return; }
        runYaml = serializeUnifiedModel(sliced);
      }
      // Handed over undeadlined: dispatchRuns owns the run deadline, so a dispatch that blows it is
      // still awaited and can report the session it started instead of being written off.
      const dispatch = (tbId) => TB.dispatchRun(tbId, runYaml, { ...opts, trailId: trail ? trail.id : null });
      // No setLaunching(null) on the success path: the dialog is closing, and re-enabling the
      // button during the close animation would reopen the double-dispatch window.
      // Re-checked, because the target rebind above is allowed 30s of its own.
      if (launchSeq.current !== seq) return;
      if (connects.length === 1) {
        // Record the pending marker BEFORE navigating, then patch in the authoritative sessionId as
        // soon as dispatch answers - the Active screen locks onto that id instead of guessing which
        // session row is "the new run" (the guess mis-locked on fast finishes and stale rows).
        const marker = TB.recordPendingRun({ title: trail.title || trail.id, target: targetId, device: selectedDevice.name, awaitsDispatch: true });
        // Fire-and-forget, but never dropped: a dispatch that fails must fail the pending marker on
        // the Active screen (a red "couldn't start" card) rather than evaporate silently, and one
        // that is only slow keeps the card waiting until the daemon answers - marking it failed at
        // the deadline claimed the run never started seconds before its session appeared. Both
        // patches name THIS marker, so an answer that arrives after the user started another run
        // no longer lands on that run's card.
        // `isLive` asks whether this run is still WANTED, not whether its card is still on screen:
        // the card expires after 90s and the device this exists for can take longer than that.
        TbRunFanout.dispatchRuns(connects, { dispatch, isLive: () => TB.pendingRunWanted(marker) }).then(function reconcile([outcome]) {
          if (outcome.ok) TB.setPendingRunSession(outcome.sessionId, marker);
          else if (outcome.settled) outcome.settled.then((late) => reconcile([late]));
          // An abandoned device is left alone on purpose: its card is already stopped or expired,
          // and failing it would bring that card back to report a run nobody is waiting for.
          else if (!outcome.abandoned) TB.failPendingRun(outcome.error, marker);
        });
        go('runs', { followLive: Date.now() });
        close();
        return;
      }
      // Several devices: one run each, awaited, so every device's outcome is known before the
      // dialog goes anywhere. A device that failed to connect is reported, never dispatched to.
      // Here `isLive` is this launch rather than a card: the dialog bumps `launchSeq` when it closes
      // or relaunches, and a slow device that comes up after that belongs to a launch nobody is
      // reading outcomes for any more - `show()` below already discards them on the same test.
      const outcomes = await TbRunFanout.dispatchRuns(connects, { dispatch, isLive: () => launchSeq.current === seq });
      // A slow dispatch is still in flight, so the rows, the summary and what stays checked are all
      // re-derived when it answers - and a launch whose last slow device turns out to have started
      // IS a finished launch, late answer or not. One subscription per device, replacing that
      // device's row by index, so two late answers can't overwrite each other.
      const latest = outcomes.slice();
      const show = () => {
        if (launchSeq.current !== seq) return;
        const list = latest.slice();
        if (list.every((o) => o.ok)) { go('runs', { followLive: Date.now() }); close(); return; }
        setRunOutcomes(list);
        // Narrow the selection to what didn't start. All the outcomes stay on screen, but the obvious
        // retry must not re-dispatch to a device that IS already running the trail: it comes back
        // "This device is busy", so the row that just succeeded reads as a failure on the second click.
        // Marked touched for the same reason a checkbox is: narrowing moves the first selected device,
        // which re-runs the trail-hint effect above - it would otherwise re-check its own hinted device.
        deviceTouched.current = true;
        setDeviceIds(TbRunFanout.retryDeviceIds(list));
        fail(TbRunFanout.launchSummary(list));
      };
      show();
      outcomes.forEach((o, i) => {
        if (o.settled) o.settled.then((late) => { latest[i] = late; show(); });
      });
    } catch (e) {
      fail('Starting the run failed unexpectedly: ' + ((e && e.message) || String(e)));
    }
  }

  // The `failed` phase covers "the launch stopped short", which includes a launch waiting on a slow
  // dispatch. Only paint it as a failure when a device actually failed: painting a slow-only launch
  // red is the same slow-read-as-failed this PR removes from the rows and the retry set, one level
  // up at the summary. No outcomes at all means the launch died before dispatching - a real failure.
  const launchFailed = !runOutcomes || TbRunFanout.launchFailed(runOutcomes);

  const filteredTrails = trailQuery
    ? allTrails.filter((t) => `${t.title || ''} ${t.id} ${t.path || ''}`.toLowerCase().includes(trailQuery.toLowerCase()))
    : allTrails;

  return (
    <div className={'tb-overlay' + (closing ? ' closing' : '')} style={{ alignItems: 'stretch', justifyContent: 'center', padding: 14, paddingTop: 14 + (document.documentElement.classList.contains('tb-native') ? 28 : 0) }} onClick={close}>
      <div className="tb-run-dialog" onClick={(e) => e.stopPropagation()}
        style={{ width: '100%', maxWidth: advanced ? 1240 : 1000, margin: 'auto', height: 'min(720px, 94vh)', maxHeight: '94vh', background: 'var(--bg-subtle)', border: '1px solid var(--tb-hairline)', borderRadius: 16, boxShadow: '0 30px 90px rgba(0,0,0,.55)', display: 'flex', flexDirection: 'column', overflow: 'hidden' }}>

        <div className="tb-run-head" style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '16px 22px', borderBottom: '1px solid var(--tb-hairline)', flex: '0 0 auto' }}>
          <div className="tb-run-mark" style={{ width: 40, height: 40, borderRadius: 12, background: 'rgba(0,224,19,.12)', border: '1px solid rgba(0,224,19,.32)', display: 'grid', placeItems: 'center', flex: '0 0 auto' }}>
            <Ico n="circle-play" s={20} c="var(--tb-primary-green)" />
          </div>
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 18, fontWeight: 600 }}>Run trail</div>
            {trail && <div className="tb-mono tb-sub" style={{ fontSize: 11.5, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{trail.path || trail.id}</div>}
          </div>
          <button onClick={close} title="Close" style={{ display: 'inline-flex', background: 'none', border: 'none', cursor: 'pointer', color: 'var(--text-subtle)', padding: 4, marginLeft: 4 }}><Ico n="x" s={20} /></button>
        </div>

        {trail && range && !whole && (
          // The one thing a partial run surprises people with: it does not start the trail over.
          <div data-testid="run-step-range" style={{ display: 'flex', alignItems: 'flex-start', gap: 10, padding: '10px 22px', borderBottom: '1px solid var(--tb-hairline)', background: 'rgba(0,224,19,.06)', flex: '0 0 auto' }}>
            <Ico n="play" s={15} c="var(--tb-pass)" />
            <div style={{ fontSize: 12.5, lineHeight: 1.45 }}>
              <b>{range.start === range.end ? `Step ${range.start + 1}` : `Steps ${range.start + 1}-${range.end + 1}`} of {total}.</b>
              {' '}
              <span className="tb-sub">The trail doesn't start over: the run begins from whatever is on the device's screen right now.</span>
            </div>
          </div>
        )}

        {!trail ? (
          <div style={{ flex: '1 1 auto', minHeight: 0, overflowY: 'auto', padding: '20px 24px' }}>
            <div className="tb-eyebrow" style={{ marginBottom: 10 }}>Choose a trail</div>
            <RcInput value={trailQuery} onChange={setTrailQuery} placeholder="Search trails…" style={{ marginBottom: 12 }} />
            <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 9 }}>
              {filteredTrails.length === 0 && <div className="tb-sub" style={{ padding: 12, fontSize: 12.5 }}>No matching trails.</div>}
              {filteredTrails.slice(0, 300).map((t) => (
                <div key={t.id} className="tb-pal-row" onClick={() => setTrail(t)} style={{ cursor: 'pointer' }}>
                  <Ico n="file-text" s={16} c="var(--text-subtle)" />
                  <span style={{ fontSize: 13.5, flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{t.title || t.id}</span>
                  <span className="tb-sub" style={{ fontSize: 10.5 }}>{t.platform || ''}</span>
                </div>
              ))}
            </div>
          </div>
        ) : (
          // Two panes: which devices on the left, which steps on the right. Advanced expands the
          // left pane in place rather than opening a third rail - a run only ever needs the two
          // questions above it answered.
          <div className="tb-run-grid" style={{ flex: '1 1 auto', minHeight: 0, display: 'grid', gridTemplateColumns: 'minmax(0, 1fr) 400px' }}>
            <div className="tb-run-form" style={{ overflowY: 'auto', padding: '20px 26px 24px', display: 'flex', flexDirection: 'column', gap: 22 }}>
              <Section id="target" title={deviceIds.length > 1 ? 'Targets' : 'Target'} ico="crosshair">
                <TargetSection devices={deviceList} deviceIds={deviceIds} toggleDevice={toggleDevice} connectedIds={connectedIds}
                  appsDevice={appsDevice} installedTargets={installedTargets} targetApp={targetApp} setTargetApp={setTargetApp} appsLoading={deviceApps.loading} declaredTarget={declaredTarget}
                  launching={!!launching} />
                {/* Self-heal is the one behavior knob that changes what a run means to the person
                    starting it, so it stays out here with the devices. */}
                {!advanced && <div style={{ marginTop: 6 }}><ToggleRow label="Self-heal failures" flag="--self-heal" on={selfHeal} set={setSelfHeal} /></div>}
              </Section>
              {advanced && (
                <Section id="behavior" title="Behavior" ico="bot">
                  <BehaviorSection selfHeal={selfHeal} setSelfHeal={setSelfHeal} useRecordedSteps={useRecordedSteps} setUseRecordedSteps={setUseRecordedSteps}
                    agent={agent} setAgent={setAgent} maxLlmCalls={maxLlmCalls} setMaxLlmCalls={setMaxLlmCalls} llm={llm} setLlm={setLlm}
                    verbose={verbose} setVerbose={setVerbose} headless={headless} setHeadless={setHeadless} web={selectedDevice && selectedDevice.platform === 'web'} />
                </Section>
              )}
              {advanced && (
                <Section id="capture" title="Capture" ico="clapperboard">
                  <CaptureSection captureVideo={captureVideo} setCaptureVideo={setCaptureVideo} captureLogcat={captureLogcat} setCaptureLogcat={setCaptureLogcat}
                    captureNetwork={captureNetwork} setCaptureNetwork={setCaptureNetwork} captureIosLogs={captureIosLogs} setCaptureIosLogs={setCaptureIosLogs}
                    captureAnalytics={captureAnalytics} setCaptureAnalytics={setCaptureAnalytics} captureEvents={captureEvents} setCaptureEvents={setCaptureEvents} saveRecording={saveRecording} setSaveRecording={setSaveRecording}
                    noReport={noReport} setNoReport={setNoReport} markdown={markdown} setMarkdown={setMarkdown} noLogging={noLogging} setNoLogging={setNoLogging}
                    tags={tags} setTags={setTags} />
                </Section>
              )}
              {advanced && (
                <div className="tb-mono tb-run-command" style={{ position: 'relative', background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 9, padding: '10px 42px 10px 13px', fontSize: 12, lineHeight: 1.6, color: 'var(--text-standard)', whiteSpace: 'pre-wrap', wordBreak: 'break-all', maxHeight: 110, overflowY: 'auto' }}>
                  {command}
                  <button data-testid="run-cmd-copy" onClick={copyCommand} title={copied ? 'Copied!' : 'Copy command'}
                    className="tb-btn ghost sm"
                    style={{ position: 'absolute', top: 6, right: 6, width: 28, height: 28, padding: 0, justifyContent: 'center' }}>
                    <Ico n={copied ? 'check' : 'copy'} s={14} c={copied ? 'var(--tb-primary-green)' : undefined} />
                  </button>
                  {/* The CLI has no partial-run verb: `trailblaze run` takes a file and runs all of
                      it, so a narrowed span makes this command something other than the buttons
                      below. Say so rather than hand out a command that does something else. */}
                  {range && !whole && (
                    <div className="tb-sub" style={{ marginTop: 6, fontSize: 11.5, whiteSpace: 'normal' }}>
                      Runs the WHOLE trail: running part of one is a Trail Runner action.
                    </div>
                  )}
                </div>
              )}
              {/* Aligned with the section content column (the 42px inset Section uses), so it reads as
                  the last row of the form rather than as something hanging off its left edge. */}
              <div style={{ display: 'flex', justifyContent: 'flex-start', paddingLeft: 42 }}>
                <button data-testid="run-advanced-toggle" onClick={() => setAdvanced(!advanced)} className="tb-btn ghost sm">
                  <Ico n={advanced ? 'chevron-up' : 'chevron-down'} s={14} />
                  {advanced ? 'Hide advanced options' : 'Advanced options'}
                </button>
              </div>
            </div>
            <div className="tb-run-preview" style={{ borderLeft: '1px solid var(--tb-hairline)', background: 'var(--bg-app)', minHeight: 0 }}>
              {/* The dialog isn't under the shell's screen Boundary, so a render throw in
                  the right pane (e.g. the Tools panel) would unmount the whole app. Contain it. */}
              <Boundary>
                <RunStepsPane steps={stepList} trailhead={stepModel && stepModel.trailhead} range={range} total={total}
                  onPick={pickStep} onAll={() => setRange({ start: 0, end: total - 1 })}
                  detailSteps={detailSteps} loading={detail.loading}
                  showTabs={advanced} tab={rightTab} setTab={setRightTab} liveYaml={previewYaml}
                  targetId={targetId} driver={selectedDevice ? selectedDevice.driver : null} platform={selectedDevice ? selectedDevice.platform : null} />
              </Boundary>
            </div>
          </div>
        )}

        {trail && phase === 'failed' && (
          <div style={{ flex: '0 0 auto', borderTop: '1px solid var(--tb-hairline)', padding: '10px 22px', background: 'var(--bg-app)' }}>
            <div style={{ color: launchFailed ? 'var(--tb-danger-text)' : 'var(--text-subtle)', fontSize: 12.5, lineHeight: 1.5, padding: '9px 12px', background: launchFailed ? 'rgba(248,71,82,.12)' : 'var(--bg-standard)', border: '1px solid ' + (launchFailed ? 'rgba(248,71,82,.25)' : 'var(--tb-hairline)'), borderRadius: 8 }}>
              {runError || 'The run could not start.'}
            </div>
            {/* One row per device the launch was asked for: the runs that did start keep their
                session id visible next to the ones that didn't and why. */}
            {runOutcomes && (
              <div data-testid="run-device-outcomes" style={{ marginTop: 8, display: 'flex', flexDirection: 'column', gap: 4 }}>
                {runOutcomes.map((o) => (
                  <div key={o.device.id} style={{ display: 'flex', alignItems: 'baseline', gap: 8, fontSize: 12.5, lineHeight: 1.5 }}>
                    {/* A slow dispatch is neither of the two verdicts: the daemon just hasn't
                        answered yet, so it reads as waiting rather than as a failed run. */}
                    <Ico n={o.ok ? 'circle-check' : o.slow ? 'clock' : 'circle-x'} s={13}
                      c={o.ok ? 'var(--tb-primary-green)' : o.slow ? 'var(--text-subtle)' : 'var(--tb-fail)'} />
                    <span style={{ fontWeight: 600, flex: '0 0 auto' }}>{o.device.name}</span>
                    <span className={o.ok ? 'tb-mono tb-sub' : o.slow ? 'tb-sub' : undefined} style={{ minWidth: 0, color: o.ok || o.slow ? undefined : 'var(--tb-danger-text)' }}>
                      {o.ok ? o.sessionId : o.error}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>
        )}

        {trail && (
          // Two verbs, because a trail run is one of two different things and the difference is what
          // happens to the FILE afterwards. Both act on the steps selected on the right.
          <div className="tb-run-footer" style={{ display: 'flex', alignItems: 'center', gap: 14, padding: '12px 16px 12px 22px', borderTop: '1px solid var(--tb-hairline)', background: 'var(--bg-app)', flex: '0 0 auto' }}>
            {/* One line, not a paragraph: the difference between the two verbs is what happens to the
                file, and each button's tooltip carries the rest. */}
            <div className="tb-sub" style={{ flex: 1, minWidth: 0, fontSize: 11.5, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>
              Record updates this file's tool calls when the run finishes.
            </div>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, flex: '0 0 auto' }}>
              <span data-testid="run-config-run">
                <Btn kind={emphasis === 'play' ? 'primary' : 'ghost'} ico="play" onClick={() => run('play')} disabled={!canRun}
                  title="Replay the tool calls the selected steps already hold">
                  {launching === 'play' ? 'Starting…' : 'Play'}
                </Btn>
              </span>
              <span data-testid="run-config-record">
                <Btn kind={emphasis === 'record' ? 'primary' : 'ghost'} ico="circle-dot" onClick={() => run('record')} disabled={!canRun || !range}
                  title={range
                    ? 'Let the agent drive the selected steps, then rewrite their tool calls in this trail file to match'
                    : 'Only a unified single-file trail has per-step recordings to write back into.'}>
                  {launching === 'record' ? 'Recording…' : 'Record'}
                </Btn>
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

window.RunConfigDialog = RunConfigDialog;
