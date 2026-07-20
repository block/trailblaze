// @ts-nocheck -- migrated from .jsx; this file has pre-existing type errors from years of
// untyped legacy JS (mostly optional params/props without defaults, inferred by TS as required).
// Babel strips types at load time regardless, so the browser runtime is unaffected.
// Remove this pragma once the file's real errors are fixed; run `bun run typecheck` to see them.

function Timeline({ trace, step, setStep, sessionId, analytics = [], streamEvents = [] }) {
  const [playing, setPlaying] = React.useState(false);
  // Two independent, persisted filters (localStorage via useStickyState) — survive tab
  // changes, navigation, and full app restarts, and are shared across every run view.
  //  • Events: the trace step categories. Default all on (absent key = on).
  //  • Streams: the per-stream captured events. Default all OFF (absent key = off) — streams are
  //    high-volume, so you opt the ones you care about in, and they stay in.
  const [enabledCats, setEnabledCats] = useStickyState('tb-tl-event-cats', ALL_CATS_ON);
  const [enabledStreams, setEnabledStreams] = useStickyState('tb-tl-streams', {});
  const [eventsOpen, setEventsOpen] = React.useState(false);
  const [streamsOpen, setStreamsOpen] = React.useState(false);
  const eventsBtnRef = React.useRef(null);
  const streamsBtnRef = React.useRef(null);

  // Per-stream metadata in first-seen order — drives stable colors + the filter rows.
  const streams = React.useMemo(() => {
    const m = new Map();
    streamEvents.forEach((e) => {
      const cur = m.get(e.streamId) || { streamId: e.streamId, label: e.label || e.streamId, count: 0 };
      cur.count++; m.set(e.streamId, cur);
    });
    return Array.from(m.values());
  }, [streamEvents]);
  const streamColorOf = React.useMemo(() => {
    const idx = {}; streams.forEach((p, i) => { idx[p.streamId] = i; });
    return (pid) => STREAM_PALETTE[(idx[pid] || 0) % STREAM_PALETTE.length];
  }, [streams]);
  const streamShown = (pid) => enabledStreams[pid] === true;

  // The trace step that was current at a given wall-clock time: the latest step whose timestamp
  // is <= time (falling back to the first step). Lets clicking a time-stamped side event (a stream
  // event) move the timeline/device preview to the step it happened during.
  const stepIndexForTime = React.useMemo(() => {
    const withTs = trace.filter((t) => t.ts != null);
    return (timeMs) => {
      const first = trace.length ? trace[0].i : null;
      if (timeMs == null || withTs.length === 0) return first;
      let best = first;
      for (const t of withTs) { if (t.ts <= timeMs) best = t.i; }
      return best;
    };
  }, [trace]);

  // Merge analytics + streamEvents into one time-sorted side-event stream, then interleave it
  // against the trace steps (each side event lands just after the last step at or before
  // its timestamp). Same boundary walk the analytics-only path used, generalized.
  const sideEvents = React.useMemo(() => {
    const arr = [];
    analytics.forEach((a) => arr.push({ kind: 'analytics', time: a.timeMs, key: 'an-' + a.id, ev: a, navStep: stepIndexForTime(a.timeMs) }));
    streamEvents.forEach((e, i) => arr.push({ kind: 'stream', time: e.timeMs, key: 'stream-' + i, ev: e, color: streamColorOf(e.streamId), navStep: stepIndexForTime(e.timeMs) }));
    arr.sort((a, b) => a.time - b.time);
    return arr;
  }, [analytics, streamEvents, streamColorOf]);
  const feed = React.useMemo(() => {
    if (sideEvents.length === 0) {
      return trace.map((t) => ({ kind: 'step', key: 'step-' + t.i, t }));
    }
    const stepMs = [];
    let last = null;
    for (const t of trace) { if (t.ts != null) last = t.ts; stepMs.push(last); }
    const out = [];
    let ei = 0;
    for (let si = 0; si < trace.length; si++) {
      while (ei < sideEvents.length && stepMs[si] != null && sideEvents[ei].time <= stepMs[si]) {
        out.push(sideEvents[ei]); ei++;
      }
      out.push({ kind: 'step', key: 'step-' + trace[si].i, t: trace[si] });
    }
    while (ei < sideEvents.length) { out.push(sideEvents[ei]); ei++; }
    return out;
  }, [trace, sideEvents]);
  const catCounts = React.useMemo(() => {
    const c = { tool: 0, llm: 0, assertion: 0, analytics: 0, error: 0, log: 0 };
    trace.forEach((t) => { c[stepCategory(t)]++; });
    c.analytics += analytics.length;
    return c;
  }, [trace, analytics]);
  const visibleFeed = feed.filter((it) => {
    if (it.kind === 'analytics') return enabledCats.analytics;
    if (it.kind === 'stream') return streamShown(it.ev.streamId);
    return enabledCats[stepCategory(it.t)];
  });
  // Per-filter tallies for the "N / M" pills. Events: categories actually present in the
  // trace; Streams: every stream seen this run.
  const eventCats = CAT_ORDER.filter((c) => catCounts[c] > 0);
  const eventsOnCount = eventCats.filter((c) => enabledCats[c] !== false).length;
  const streamsOnCount = streams.filter((p) => enabledStreams[p.streamId] === true).length;
  const anyStreamFiltered = streamsOnCount < streams.length;
  const anyStepFiltered = eventsOnCount < eventCats.length;
  // Step header counts STEPS only — the feed also carries streamEvents/analytics rows (often
  // thousands), so counting the whole feed against "steps" read as a wildly wrong number.
  const visibleStepCount = visibleFeed.reduce((n, it) => n + (it.kind === 'step' ? 1 : 0), 0);
  const visibleStreamCount = visibleFeed.reduce((n, it) => n + (it.kind === 'stream' ? 1 : 0), 0);
  const streamEventsTotal = streamEvents.length;
  const [phoneW, setPhoneW] = React.useState(() => {
    const v = parseInt(window.localStorage.getItem('tb-phone-w') || '', 10);
    return Number.isFinite(v) && v >= 240 && v <= 620 ? v : 340;
  });
  const dragCleanupRef = React.useRef(null);
  React.useEffect(() => () => { if (dragCleanupRef.current) dragCleanupRef.current(); }, []);
  const onSplitterDown = (e) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = phoneW;
    let lastW = startW;
    const move = (ev) => { lastW = Math.min(620, Math.max(240, startW + (startX - ev.clientX))); setPhoneW(lastW); };
    const cleanup = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      document.body.style.cursor = '';
      dragCleanupRef.current = null;
    };
    const up = () => { cleanup(); try { window.localStorage.setItem('tb-phone-w', String(lastW)); } catch (_) {} };
    dragCleanupRef.current = cleanup;
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
    document.body.style.cursor = 'col-resize';
  };

  const axis = React.useMemo(() => {
    const tsVals = trace.map((t) => t.ts).filter((x) => x != null);
    // reduce, not Math.min/max(...spread) — a long trace would blow the call-stack/arg limit.
    const tsMin = tsVals.length ? tsVals.reduce((a, b) => (b < a ? b : a), tsVals[0]) : 0;
    const tsMax = tsVals.length ? tsVals.reduce((a, b) => (b > a ? b : a), tsVals[0]) : 0;
    const haveTs = tsVals.length >= 2 && tsMax > tsMin;
    const lo = haveTs ? tsMin : 0;
    const hi = haveTs ? tsMax : 0;
    const MIN_GAP = 350, MAX_GAP = 4000;
    let cum = 0; const pos = []; const realFromLo = []; let prevTs = null;
    trace.forEach((t, i) => {
      if (i > 0) {
        const realGap = (haveTs && t.ts != null && prevTs != null) ? (t.ts - prevTs) : Math.max(t.ms || 0, MIN_GAP);
        cum += Math.max(MIN_GAP, Math.min(MAX_GAP, realGap));
      }
      pos.push(cum);
      const rawReal = (haveTs && t.ts != null) ? (t.ts - lo) : (i > 0 ? realFromLo[i - 1] : 0);
      realFromLo.push(i > 0 ? Math.max(realFromLo[i - 1], rawReal) : Math.max(0, rawReal));
      if (t.ts != null) prevTs = t.ts;
    });
    const span = Math.max(1, cum);
    const totalMs = haveTs ? Math.max(1, hi - lo) : span;
    const stepFrac = pos.map((p) => p / span);
    const tsFrac = (ms) => {
      if (ms == null || !haveTs || !trace.length) return null;
      const r = ms - lo;
      if (r <= realFromLo[0]) return stepFrac[0];
      for (let i = 1; i < trace.length; i++) {
        if (r <= realFromLo[i]) {
          const d = (realFromLo[i] - realFromLo[i - 1]) || 1;
          return Math.min(1, Math.max(0, stepFrac[i - 1] + ((r - realFromLo[i - 1]) / d) * (stepFrac[i] - stepFrac[i - 1])));
        }
      }
      return 1;
    };
    const clockMs = (frac) => {
      if (!trace.length) return 0;
      const f = Math.min(1, Math.max(0, frac));
      if (!haveTs) return f * span;
      if (f <= stepFrac[0]) return realFromLo[0];
      for (let i = 1; i < trace.length; i++) {
        if (f <= stepFrac[i]) {
          const d = (stepFrac[i] - stepFrac[i - 1]) || 1;
          return realFromLo[i - 1] + ((f - stepFrac[i - 1]) / d) * (realFromLo[i] - realFromLo[i - 1]);
        }
      }
      return realFromLo[trace.length - 1];
    };
    return { span, totalMs, stepFrac, tsFrac, clockMs };
  }, [trace, analytics]);

  const cur = trace.find((t) => t.i === step) || trace[0];
  const idx = Math.max(0, trace.findIndex((t) => t.i === step));

  const playheadRef = React.useRef(null);
  const fillRef = React.useRef(null);
  const clockRef = React.useRef(null);
  const playFracRef = React.useRef(0);
  const paintFrac = (f) => {
    const cf = f < 0 ? 0 : f > 1 ? 1 : f;
    const pct = (cf * 100) + '%';
    if (playheadRef.current) playheadRef.current.style.top = 'calc(' + pct + ' - 5px)';
    if (fillRef.current) fillRef.current.style.height = pct;
    if (clockRef.current) clockRef.current.textContent = fmtClock(axis.clockMs(cf));
  };
  React.useLayoutEffect(() => {
    if (playing) return;
    const f = axis.stepFrac[idx] != null ? axis.stepFrac[idx] : 0;
    playFracRef.current = f;
    paintFrac(f);
  }, [idx, playing, axis]);
  React.useEffect(() => {
    if (!playing) return;
    if (playFracRef.current >= 0.999) playFracRef.current = 0;
    const stepIndexForFrac = (f) => { let best = 0; const sf = axis.stepFrac; for (let i = 0; i < sf.length; i++) { if (sf[i] <= f + 1e-9) best = i; } return best; };
    let raf = 0, lastT = performance.now(), lastIdx = -1;
    const loop = (now) => {
      const dt = now - lastT; lastT = now;
      let f = playFracRef.current + dt / axis.span;
      if (f >= 1) {
        playFracRef.current = 1; paintFrac(1);
        if (trace[trace.length - 1]) setStep(trace[trace.length - 1].i);
        setPlaying(false);
        return;
      }
      playFracRef.current = f; paintFrac(f);
      const ci = stepIndexForFrac(f);
      if (ci !== lastIdx) { lastIdx = ci; if (trace[ci]) setStep(trace[ci].i); }
      raf = requestAnimationFrame(loop);
    };
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, [playing, axis]);

  React.useEffect(() => {
    const onKey = (e) => {
      if (e.code !== 'Space' && e.key !== ' ') return;
      if (e.repeat) return;
      const a = document.activeElement;
      const tag = a && a.tagName;
      if (a && (a.isContentEditable || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT' || tag === 'BUTTON' || a.getAttribute('role') === 'button')) return;
      e.preventDefault();
      setPlaying((p) => !p);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);

  // ← / → step through the timeline, same as the prev/next buttons in the device preview.
  React.useEffect(() => {
    const onKey = (e) => {
      if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
      const a = document.activeElement;
      const tag = a && a.tagName;
      if (a && (a.isContentEditable || tag === 'INPUT' || tag === 'TEXTAREA' || tag === 'SELECT')) return;
      if (!trace.length) return;
      e.preventDefault();
      setPlaying(false);
      const cur = Math.max(0, trace.findIndex((t) => t.i === step));
      const ni = e.key === 'ArrowLeft' ? Math.max(0, cur - 1) : Math.min(trace.length - 1, cur + 1);
      if (trace[ni]) setStep(trace[ni].i);
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [trace, step, setPlaying, setStep]);

  const curFrac = axis.stepFrac[idx] != null ? axis.stepFrac[idx] : 0;

  if (trace.length === 0) {
    return <EmptyState ico="route" title="No trace events" sub="This session didn't emit any agent-task logs we can render." />;
  }
  return (
    <div data-testid="trace-timeline" style={{ display: 'flex', alignItems: 'stretch', height: '100%', minHeight: 0, width: '100%', overflowX: 'auto' }}>
      <div style={{ flex: 1, minWidth: 250, minHeight: 0, display: 'flex', flexDirection: 'column', paddingLeft: 0, paddingRight: 14 }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '0 2px 8px', flex: '0 0 auto' }}>
          <span className="tb-eyebrow">
            {anyStepFiltered ? visibleStepCount + ' / ' + trace.length : trace.length} step{trace.length === 1 ? '' : 's'}
            {streamEventsTotal > 0 ? ' · ' + (anyStreamFiltered ? visibleStreamCount + ' / ' + streamEventsTotal : streamEventsTotal) + ' streams' : ''}
          </span>
          <div style={{ display: 'flex', gap: 8 }}>
            {eventCats.length > 0 && (
              <FilterChip label="Events" n={eventsOnCount} m={eventCats.length} btnRef={eventsBtnRef} testid="events-filter"
                onClick={() => { setStreamsOpen(false); setEventsOpen((v) => !v); }} />
            )}
            {streams.length > 0 && (
              <FilterChip label="Streams" n={streamsOnCount} m={streams.length} btnRef={streamsBtnRef} testid="stream-filter"
                onClick={() => { setEventsOpen(false); setStreamsOpen((v) => !v); }} />
            )}
          </div>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflowY: 'auto' }}>
          <StepStack feed={visibleFeed} step={step} setStep={setStep} sessionId={sessionId} />
        </div>
      </div>
      {eventsOpen && (
        <TimelineFilterPopover
          anchor={eventsBtnRef.current} title="Events"
          rows={eventCats.map((c) => ({ key: c, label: CAT_LABELS[c], color: TL_CAT_COLORS[c], count: catCounts[c] }))}
          enabledOf={(c) => enabledCats[c] !== false}
          onToggle={(c) => setEnabledCats((p) => ({ ...p, [c]: p[c] === false }))}
          onAll={() => setEnabledCats(Object.fromEntries(CAT_ORDER.map((c) => [c, true])))}
          onNone={() => setEnabledCats(Object.fromEntries(CAT_ORDER.map((c) => [c, false])))}
          onClose={() => setEventsOpen(false)}
        />
      )}
      {streamsOpen && (
        <TimelineFilterPopover
          anchor={streamsBtnRef.current} title="Event streams"
          rows={streams.map((p) => ({ key: p.streamId, label: p.label, color: streamColorOf(p.streamId), count: p.count, sub: p.streamId }))}
          enabledOf={(pid) => enabledStreams[pid] === true}
          onToggle={(pid) => setEnabledStreams((p) => ({ ...p, [pid]: !p[pid] }))}
          onAll={() => setEnabledStreams(Object.fromEntries(streams.map((p) => [p.streamId, true])))}
          onNone={() => setEnabledStreams({})}
          onClose={() => setStreamsOpen(false)}
        />
      )}
      <VerticalScrubber trace={trace} step={step} setStep={setStep} setPlaying={setPlaying}
        analytics={analytics} streamEvents={streamEvents} enabledCats={enabledCats} streamShown={streamShown} streamColorOf={streamColorOf}
        axis={axis} curFrac={curFrac}
        playheadRef={playheadRef} fillRef={fillRef} />
      <div data-testid="device-splitter" onMouseDown={onSplitterDown} title="Drag to resize the device"
        style={{ flex: '0 0 14px', alignSelf: 'stretch', cursor: 'col-resize', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <div style={{ width: 3, height: 44, borderRadius: 3, background: 'var(--tb-hairline-stronger)' }} />
      </div>
      <div style={{ flex: '0 0 ' + phoneW + 'px', width: phoneW, minHeight: 0, display: 'flex', flexDirection: 'column' }}>
        <PhonePreview
          trace={trace} step={step} setStep={setStep} sessionId={sessionId} cur={cur}
          width={phoneW} playing={playing} setPlaying={setPlaying}
          clockRef={clockRef} span={axis.totalMs}
          hasHierarchy={!!cur?.viewHierarchy}
        />
      </div>
    </div>
  );
}

function fmtClock(ms) {
  if (ms == null) return '0:00';
  const m = Math.floor(ms / 60000);
  const sec = Math.floor((ms % 60000) / 1000);
  return m + ':' + String(sec).padStart(2, '0');
}

const TL_CAT_COLORS = {
  tool: 'var(--tb-pass)',
  llm: 'var(--tb-running)',
  analytics: '#a679f5',
  assertion: 'var(--tb-analytics)',
  log: 'var(--text-subtle)',
  error: 'var(--tb-fail)',
  stream: '#4dd0e1',
};

// Distinct hues cycled per event stream so different stream types read apart at a glance
// in the timeline rows and the scrubber. Stable by first-seen stream order.
const STREAM_PALETTE = ['#4dd0e1', '#ffb74d', '#81c784', '#ba68c8', '#f06292', '#7986cb', '#a1887f', '#4db6ac'];

const CAT_LABELS = { tool: 'Tool / action', llm: 'LLM / agent', assertion: 'Assertion', analytics: 'Analytics', error: 'Error', log: 'Log' };
const CAT_ORDER = ['tool', 'llm', 'assertion', 'analytics', 'error', 'log'];
const ALL_CATS_ON = { tool: true, llm: true, assertion: true, analytics: true, error: true, log: true };

function stepCategory(t) {
  if (!t.ok) return 'error';
  const tool = String(t.tool || '');
  if (tool === 'agent step' || tool.startsWith('llm')) return 'llm';
  const label = String(t.label || '').toLowerCase();
  if (label.startsWith('assert') || label.startsWith('verify') || tool.toLowerCase().includes('assert')) return 'assertion';
  if (t.tool || t.label) return 'tool';
  return 'log';
}

// Picks a lucide icon that hints at what an action does, from its tool/label name.
// Color still comes from stepCategory so the icon doubles as the pass/fail signal.
function actionIcon(tr) {
  if (!tr.ok) return 'circle-x';
  const s = ((tr.label || '') + ' ' + (tr.tool || '')).toLowerCase();
  const has = (...w) => w.some((x) => s.includes(x));
  if (has('assert', 'verify', 'visible', 'condition', 'expect')) return 'circle-check';
  if (has('erasetext', 'cleartext')) return 'eraser';
  if (has('entertext', 'inputtext', 'settext', 'typetext')) return 'keyboard';
  if (has('hidekeyboard')) return 'chevron-down';
  if (has('pressback', 'navigateback')) return 'corner-up-left';
  if (has('longpress')) return 'hand';
  if (has('tap', 'click', 'press', 'touch')) return 'mouse-pointer-click';
  if (has('swipe', 'scroll', 'fling', 'drag')) return 'move-vertical';
  if (has('launch', 'openapp', 'startactivity')) return 'rocket';
  if (has('stopapp', 'killapp', 'forcestop', 'closeapp')) return 'circle-stop';
  if (has('permission', 'grant')) return 'shield-check';
  if (has('wait', 'sleep', 'delay', 'idle')) return 'clock';
  if (has('screenshot', 'snapshot', 'capture')) return 'camera';
  if (has('adbshell', 'shell', 'runcommand', 'exec')) return 'terminal';
  if (has('deeplink', 'openlink', 'url', 'navigate')) return 'link';
  if (has('factory', 'loadaccount', 'provision', 'seed', 'fund')) return 'database';
  return 'wrench';
}

function VerticalScrubber({ trace, step, setStep, setPlaying, analytics = [], streamEvents = [], enabledCats = ALL_CATS_ON, streamShown = () => true, streamColorOf = () => TL_CAT_COLORS.stream, axis, curFrac = 0, playheadRef, fillRef }) {
  const ref = React.useRef(null);
  const [hover, setHover] = React.useState(null);
  const idx = Math.max(0, trace.findIndex((t) => t.i === step));
  const fracOf = (i) => (axis && axis.stepFrac[i] != null ? axis.stepFrac[i] : (trace.length > 1 ? i / (trace.length - 1) : 0));
  const pickAt = (clientY) => {
    const el = ref.current; if (!el) return;
    const r = el.getBoundingClientRect();
    const f = Math.min(1, Math.max(0, (clientY - r.top) / r.height));
    let best = 0, bestD = Infinity;
    trace.forEach((t, i) => { const d = Math.abs(fracOf(i) - f); if (d < bestD) { bestD = d; best = i; } });
    if (trace[best]) setStep(trace[best].i);
  };
  const dragCleanupRef = React.useRef(null);
  React.useEffect(() => () => { if (dragCleanupRef.current) dragCleanupRef.current(); }, []);
  const onDown = (e) => {
    e.preventDefault();
    if (setPlaying) setPlaying(false);
    pickAt(e.clientY);
    const move = (ev) => pickAt(ev.clientY);
    const up = () => {
      document.removeEventListener('mousemove', move);
      document.removeEventListener('mouseup', up);
      dragCleanupRef.current = null;
    };
    dragCleanupRef.current = up;
    document.addEventListener('mousemove', move);
    document.addEventListener('mouseup', up);
  };

  const lines = [];
  trace.forEach((t, i) => {
    const cat = stepCategory(t);
    if (enabledCats[cat] === false) return;
    lines.push({ key: 's' + t.i, frac: fracOf(i), cat, active: i === idx, stepI: t.i, label: t.i + '. ' + t.label, sub: t.tool || '' });
  });
  if (enabledCats.analytics !== false) {
    analytics.forEach((a) => {
      const f = axis ? axis.tsFrac(a.timeMs) : null;
      if (f == null) return;
      lines.push({ key: 'a' + a.id, frac: f, cat: 'analytics', active: false, stepI: null, label: a.name, sub: 'analytics event' });
    });
  }
  streamEvents.forEach((e, i) => {
    if (!streamShown(e.streamId)) return;
    const f = axis ? axis.tsFrac(e.timeMs) : null;
    if (f == null) return;
    const lbl = e.label || e.streamId;
    lines.push({ key: 'e' + i, frac: f, cat: 'stream', color: streamColorOf(e.streamId), active: false, stepI: null, label: lbl, sub: 'stream · ' + lbl });
  });

  return (
    <div style={{ flex: '0 0 36px', display: 'flex', flexDirection: 'column', minHeight: 0, userSelect: 'none' }}>
      <div className="tb-mono tb-sub" style={{ fontSize: 9.5, textAlign: 'center', marginBottom: 5 }}>0:00</div>
      <div ref={ref} onMouseDown={onDown} style={{ position: 'relative', flex: 1, minHeight: 0, width: 24, margin: '0 auto', cursor: 'pointer' }}>
        <div style={{ position: 'absolute', left: '50%', transform: 'translateX(-50%)', top: 0, bottom: 0, width: 1, background: 'var(--tb-hairline)', pointerEvents: 'none' }} />
        <div ref={fillRef} style={{ position: 'absolute', left: '50%', transform: 'translateX(-50%)', top: 0, height: (curFrac * 100) + '%', width: 2, background: 'rgba(255,255,255,.28)', borderRadius: 2, pointerEvents: 'none' }} />
        {lines.map((ln) => {
          const thick = ln.active || (hover && hover.key === ln.key);
          const h = thick ? 3 : 1;
          return (
            <div key={ln.key}
              title={ln.label}
              onMouseEnter={() => setHover({ key: ln.key, frac: ln.frac, label: ln.label, sub: ln.sub })}
              onMouseLeave={() => setHover((cur) => (cur && cur.key === ln.key ? null : cur))}
              onMouseDown={ln.stepI != null ? (e) => { e.stopPropagation(); if (setPlaying) setPlaying(false); setStep(ln.stepI); } : undefined}
              style={{
                position: 'absolute', left: 0, right: 0,
                top: 'calc(' + (ln.frac * 100) + '% - ' + (h / 2) + 'px)',
                height: h, background: ln.color || TL_CAT_COLORS[ln.cat] || TL_CAT_COLORS.log,
                borderRadius: 1, cursor: ln.stepI != null ? 'pointer' : 'default',
                opacity: thick ? 1 : 0.8, transition: 'height .08s, opacity .08s',
              }} />
          );
        })}
        <div ref={playheadRef} style={{ position: 'absolute', left: '50%', transform: 'translateX(-50%)', top: 'calc(' + (curFrac * 100) + '% - 5px)', width: 10, height: 10, borderRadius: 99, background: '#fff', border: '1px solid rgba(0,0,0,.45)', boxShadow: '0 1px 5px rgba(0,0,0,.6)', pointerEvents: 'none' }} />
        {hover && (
          <div style={{ position: 'absolute', right: 'calc(100% + 8px)', top: 'calc(' + (hover.frac * 100) + '% - 13px)', zIndex: 20, pointerEvents: 'none',
            background: 'var(--bg-elevated)', border: '1px solid var(--tb-hairline-strong)', borderRadius: 7, padding: '4px 9px', boxShadow: '0 8px 24px rgba(0,0,0,.5)' }}>
            <div style={{ fontSize: 11.5, fontWeight: 600, color: 'var(--text-standard)', whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 240 }}>{hover.label}</div>
            {hover.sub ? <div className="tb-mono tb-sub" style={{ fontSize: 10, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis', maxWidth: 240 }}>{hover.sub}</div> : null}
          </div>
        )}
      </div>
      <div className="tb-mono tb-sub" style={{ fontSize: 9.5, textAlign: 'center', marginTop: 5 }}>{fmtClock(axis ? axis.totalMs : 0)}</div>
    </div>
  );
}

// A filter pill in the timeline toolbar. Always shows "n / m" so it's obvious how many
// of the group are enabled (e.g. Streams "2 / 14"); highlights when not everything is on.
function FilterChip({ label, n, m, btnRef, onClick, testid }) {
  const filtered = n < m;
  return (
    <button ref={btnRef} data-testid={testid} onClick={onClick} title={'Filter ' + label.toLowerCase()}
      style={{ display: 'flex', alignItems: 'center', gap: 6, fontSize: 11.5, fontWeight: 600, padding: '4px 9px', borderRadius: 7, cursor: 'pointer',
        border: '1px solid ' + (filtered ? 'rgba(94,155,255,.5)' : 'var(--tb-hairline-strong)'),
        background: filtered ? 'rgba(94,155,255,.12)' : 'transparent',
        color: filtered ? 'var(--tb-running)' : 'var(--text-subtle)' }}>
      <Ico n="list-filter" s={13} />
      {label}
      <span className="tb-mono" style={{ fontSize: 11, opacity: 0.95 }}>{n}/{m}</span>
    </button>
  );
}

// Generic filter popover for one group (Events or Streams). Rows are {key, label, color,
// count, sub?}; the parent owns the enabled state + persistence. "All"/"None" select or
// clear the whole group at once.
function TimelineFilterPopover({ anchor, title, rows, enabledOf, onToggle, onAll, onNone, onClose }) {
  React.useEffect(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  if (!anchor) return null;
  const r = anchor.getBoundingClientRect();
  const W = 250;
  const left = Math.max(8, Math.min(r.right - W, window.innerWidth - W - 8));
  const top = Math.min(r.bottom + 6, window.innerHeight - 320);
  const onCount = rows.reduce((n, row) => n + (enabledOf(row.key) ? 1 : 0), 0);
  return (
    <>
      <div style={{ position: 'fixed', inset: 0, zIndex: 60 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }}></div>
      <div className="tb-card tb-pop" style={{ position: 'fixed', left, top, zIndex: 61, minWidth: W, maxHeight: 'min(70vh, 460px)', overflowY: 'auto', padding: 6, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)' }}>
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', padding: '4px 8px 6px' }}>
          <span className="tb-eyebrow">{title} · {onCount}/{rows.length}</span>
          <span style={{ display: 'inline-flex', gap: 12 }}>
            <span data-testid="filter-all" {...clickable(onAll)} style={{ fontSize: 11, fontWeight: 600, color: 'var(--tb-running)', cursor: 'pointer' }}>All</span>
            <span data-testid="filter-none" {...clickable(onNone)} style={{ fontSize: 11, fontWeight: 600, color: 'var(--text-subtle)', cursor: 'pointer' }}>None</span>
          </span>
        </div>
        {rows.length === 0 && <div className="tb-sub" style={{ fontSize: 12.5, padding: '6px 8px' }}>Nothing to filter.</div>}
        {rows.map((row) => {
          const on = enabledOf(row.key);
          return (
            <div key={row.key} data-testid="filter-row" className="tb-pal-row" onClick={() => onToggle(row.key)}
              style={{ cursor: 'pointer', padding: '6px 8px', display: 'flex', alignItems: 'center', gap: 9, opacity: on ? 1 : 0.5 }}>
              <span style={{ width: 9, height: 9, borderRadius: 99, background: row.color, flexShrink: 0 }} />
              <span title={row.sub || row.label} style={{ fontSize: 13, color: 'var(--text-standard)', flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{row.label}</span>
              <span className="tb-mono tb-sub" style={{ fontSize: 11 }}>{row.count}</span>
              <span style={{ width: 16, flexShrink: 0, display: 'inline-flex', justifyContent: 'flex-end' }}>{on ? <Ico n="check" s={14} c="var(--tb-running)" /> : null}</span>
            </div>
          );
        })}
      </div>
    </>
  );
}

// One trace row (a tool call, assertion, analytics event, or agent turn). Used both
// flat (a run with no trail steps) and nested as a child under a step.
function TraceRow({ item, step, setStep, rel, selRef, expanded, setExpanded, last, child, onContext, onActivate }) {
  // Clicking a time-anchored side event (stream / analytics) also moves the timeline + device
  // preview to the step it happened during (item.navStep), so the surrounding screen state is in
  // view — not just the expanded payload.
  if (item.kind === 'analytics') {
    return (
      <AnalyticsRow ev={item.ev} last={last} indent={child}
        open={expanded === item.ev.id}
        onToggle={() => { if (item.navStep != null) setStep(item.navStep); setExpanded((cur) => (cur === item.ev.id ? null : item.ev.id)); }} />
    );
  }
  if (item.kind === 'stream') {
    return (
      <StreamRow ev={item.ev} color={item.color} last={last} indent={child}
        open={expanded === item.key}
        onToggle={() => { if (item.navStep != null) setStep(item.navStep); setExpanded((cur) => (cur === item.key ? null : item.key)); }} />
    );
  }
  const tr = item.t;
  const sel = step === tr.i;
  const cat = stepCategory(tr);
  // A tool that delegated to concrete executor tool(s) — e.g. the agent's `tap` (on a ref)
  // ran `tapOnElementBySelector` (resolved selector). Expandable so the "this tool called
  // those tools" hierarchy is visible without cluttering the collapsed row.
  const kids = tr.children || null;
  const kidKey = 'kids-' + tr.i;
  const kidsOpen = kids && expanded === kidKey;
  return (
    <div
      ref={sel ? selRef : null}
      data-testid="trace-step"
      onClick={() => (onActivate ? onActivate(tr) : setStep(tr.i))}
      onContextMenu={(e) => onContext && onContext(e, tr)}
      style={{
        display: 'flex', alignItems: 'flex-start', gap: 9, padding: child ? '7px 12px 7px 20px' : '8px 12px', cursor: 'pointer',
        borderLeft: '2px solid ' + (sel ? 'var(--tb-running)' : 'transparent'),
        background: sel ? 'rgba(94,155,255,.10)' : 'transparent',
        borderBottom: !last ? '1px solid var(--tb-hairline)' : 'none',
      }}
    >
      {!child && <span className="tb-mono tb-sub" style={{ fontSize: 11, width: 20, textAlign: 'right', flexShrink: 0, marginTop: 1 }}>{tr.i}</span>}
      <span title={CAT_LABELS[cat]} style={{ display: 'inline-flex', flexShrink: 0, marginTop: 1 }}><Ico n={actionIcon(tr)} s={14} c={TL_CAT_COLORS[cat] || TL_CAT_COLORS.log} /></span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <span style={{ fontSize: 13, fontWeight: sel ? 600 : 500, lineHeight: 1.35, flex: 1, minWidth: 0 }}>{tr.label}</span>
          {kids && (
            <span role="button" data-testid="tool-children-toggle"
              title={kidsOpen ? 'Hide the tools this called' : 'Show the ' + kids.length + ' tool' + (kids.length === 1 ? '' : 's') + ' this called'}
              onClick={(e) => { e.stopPropagation(); setExpanded((cur) => (cur === kidKey ? null : kidKey)); }}
              style={{ display: 'inline-flex', alignItems: 'center', gap: 3, flexShrink: 0, fontSize: 10.5, fontWeight: 600, color: 'var(--text-subtle)', padding: '1px 5px', borderRadius: 5, border: '1px solid var(--tb-hairline)' }}>
              <Ico n="git-fork" s={10.5} /> {kids.length}
              <Ico n={kidsOpen ? 'chevron-down' : 'chevron-right'} s={11} />
            </span>
          )}
          {rel != null ? <span className="tb-mono tb-sub" style={{ fontSize: 11, flexShrink: 0 }} title="Time into the run">{rel.toFixed(1)}s</span> : null}
        </div>
        {tr.tool && <div className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle)', marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{tr.tool}</div>}
        {tr.note && <div title={tr.note} style={{ fontSize: 11.5, color: 'var(--text-subtle-variant)', marginTop: 3, lineHeight: 1.4, display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' }}>{tr.note}</div>}
        {!tr.ok && tr.err && <div style={{ fontSize: 11.5, color: 'var(--tb-danger-text)', marginTop: 3, lineHeight: 1.4 }}>{tr.err}</div>}
        {kidsOpen && (
          <div style={{ marginTop: 6, marginLeft: 1, borderLeft: '1px solid var(--tb-hairline-strong)', paddingLeft: 11, display: 'flex', flexDirection: 'column', gap: 4 }}>
            {kids.map((c, ci) => (
              <div key={ci} style={{ display: 'flex', alignItems: 'baseline', gap: 7, minWidth: 0 }}>
                <Ico n="corner-down-right" s={12} c="var(--text-subtle-variant)" style={{ flexShrink: 0, position: 'relative', top: 2 }} />
                <span className="tb-mono" style={{ fontSize: 11.5, color: 'var(--text-standard)', flexShrink: 0 }}>{c.label}</span>
                {c.tool && <span className="tb-mono tb-sub" style={{ fontSize: 11, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{c.tool}</span>}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

function StepStack({ feed, step, setStep, sessionId }) {
  const selRef = React.useRef(null);
  const [expanded, setExpanded] = React.useState(null);
  const [menu, setMenu] = React.useState(null);   // right-click menu: { x, y, row }
  const [rawRow, setRawRow] = React.useState(null); // row whose raw-log viewer is open
  const onContext = (e, tr) => { e.preventDefault(); e.stopPropagation(); setStep(tr.i); setMenu({ x: e.clientX, y: e.clientY, row: tr }); };
  // First click selects; clicking an already-selected step opens its raw log.
  const onActivate = (tr) => { if (step === tr.i) setRawRow(tr); else setStep(tr.i); };
  React.useEffect(() => {
    if (selRef.current && selRef.current.scrollIntoView) {
      selRef.current.scrollIntoView({ block: 'nearest', behavior: 'smooth' });
    }
  }, [step]);
  const _ts = feed.map((f) => f.t && f.t.ts).filter((x) => x != null);
  const startTs = _ts.length ? _ts.reduce((a, b) => (b < a ? b : a), _ts[0]) : null;

  // "Time into the run" per row, clamped to be monotonic — composed/recorded tools
  // (e.g. sample_ios_signInViaUI) can carry an out-of-order timestamp that would
  // otherwise show a time jumping backwards. Mirrors the scrubber's axis clamping.
  const relS = React.useMemo(() => {
    const m = new Map();
    if (startTs == null) return m;
    let maxRel = 0;
    for (const it of feed) {
      const t = it.t;
      if (t && t.ts != null) { maxRel = Math.max(maxRel, t.ts - startTs); m.set(it.key, maxRel / 1000); }
    }
    return m;
  }, [feed, startTs]);

  // Group the flat feed under the trail's steps: each objective row starts a new
  // group; the tool calls / assertions / analytics that follow nest under it. Rows
  // before the first objective (or a run with no objectives) get a headerless group.
  // The trailhead (step 0) keeps num 0 so the trail steps still read STEP 1..N.
  const groups = React.useMemo(() => {
    const gs = [];
    let cur = null;
    let n = 0;
    for (const item of feed) {
      if (item.kind === 'step' && item.t.objective) {
        cur = { header: item, num: item.t.trailhead ? 0 : ++n, items: [] };
        gs.push(cur);
      } else {
        if (!cur) { cur = { header: null, num: 0, items: [] }; gs.push(cur); }
        cur.items.push(item);
      }
    }
    return gs;
  }, [feed]);
  const hasSteps = groups.some((g) => g.header);
  const rowProps = { step, setStep, selRef, expanded, setExpanded, onContext, onActivate };
  const overlays = (
    <>
      {menu && <StepContextMenu menu={menu} sessionId={sessionId} onClose={() => setMenu(null)} onView={() => { setRawRow(menu.row); setMenu(null); }} />}
      {rawRow && <RawLogModal row={rawRow} onClose={() => setRawRow(null)} />}
    </>
  );

  // No trail steps to nest under — render the flat list (original look).
  if (!hasSteps) {
    return (
      <>
        <div style={{ border: '1px solid var(--tb-hairline)', borderRadius: 10, overflow: 'hidden', background: 'var(--bg-subtle)' }}>
          {feed.map((item, idx) => (
            <TraceRow key={item.key} item={item} {...rowProps} rel={relS.get(item.key)} last={idx === feed.length - 1} />
          ))}
        </div>
        {overlays}
      </>
    );
  }

  const renderGroup = (g, gi, arr) => {
    const tr = g.header && g.header.t;
    const sel = tr && step === tr.i;
    const childActive = g.items.some((it) => it.kind === 'step' && it.t.i === step);
    const failed = (tr && !tr.ok) || g.items.some((it) => it.kind === 'step' && !it.t.ok);
    const lastGroup = gi === arr.length - 1;
    return (
      <React.Fragment key={g.header ? g.header.key : 'pre-' + gi}>
        {tr && (
          <div
            ref={sel ? selRef : null}
            data-testid="trace-step-header"
            onClick={() => onActivate(tr)}
            onContextMenu={(e) => onContext(e, tr)}
            style={{ display: 'flex', flexDirection: 'column', gap: 5, padding: '10px 13px', cursor: 'pointer',
              background: sel ? 'rgba(94,155,255,.10)' : 'var(--bg-subtle)',
              borderLeft: '3px solid ' + (sel ? 'var(--tb-running)' : childActive ? 'rgba(94,155,255,.4)' : 'transparent'),
              borderTop: gi > 0 ? '1px solid var(--tb-hairline-strong)' : 'none',
              borderBottom: g.items.length > 0 ? '1px solid var(--tb-hairline)' : (lastGroup ? 'none' : '1px solid var(--tb-hairline)') }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
              <Chip tone="purple">{tr.trailhead ? 'TRAILHEAD' : `STEP ${g.num}`}</Chip>
              <span title={failed ? 'Step had a failure' : 'Step passed'} style={{ width: 8, height: 8, borderRadius: 99, background: failed ? 'var(--tb-fail)' : 'var(--tb-pass)', flexShrink: 0 }} />
              {g.items.length > 0 && <span className="tb-sub" style={{ fontSize: 11 }}>{g.items.length} action{g.items.length === 1 ? '' : 's'}</span>}
              {relS.get(g.header.key) != null ? <span className="tb-mono tb-sub" style={{ fontSize: 11, marginLeft: 'auto' }} title="Time into the run">{relS.get(g.header.key).toFixed(1)}s</span> : null}
            </div>
            <span data-selectable style={{ fontSize: 13.5, fontWeight: 600, lineHeight: 1.45 }}>{tr.label}</span>
            {!tr.ok && tr.err && <span style={{ fontSize: 11.5, color: 'var(--tb-danger-text)', lineHeight: 1.4 }}>{tr.err}</span>}
          </div>
        )}
        {g.items.map((item, j) => (
          <TraceRow key={item.key} item={item} {...rowProps} rel={relS.get(item.key)} child last={j === g.items.length - 1} />
        ))}
      </React.Fragment>
    );
  };

  // The trailhead (step 0) gets its own labelled card above the trail's steps, so the
  // deterministic setup reads apart from the test itself.
  const thGroups = groups.filter((g) => g.header && g.header.t.trailhead);
  const trailGroups = groups.filter((g) => !(g.header && g.header.t.trailhead));
  const testStepCount = trailGroups.filter((g) => g.header).length;
  const card = (children, trailheadTint) => (
    <div style={{ border: '1px solid ' + (trailheadTint ? 'rgba(151,82,255,.38)' : 'var(--tb-hairline)'), borderRadius: 10, overflow: 'hidden', background: 'var(--bg-standard)' }}>
      {children}
    </div>
  );
  const sectionLabel = (title, sub, purple, top) => (
    <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, padding: '0 2px', margin: (top ? '18px' : '0') + ' 0 8px' }}>
      <span className={'tb-eyebrow' + (purple ? ' trailhead' : '')} style={{ fontSize: 11.5, letterSpacing: '.12em', ...(purple ? {} : { color: 'var(--text-standard)' }) }}>{title}</span>
      <span className="tb-sub" style={{ fontSize: 12 }}>{sub}</span>
    </div>
  );
  return (
    <>
    {thGroups.length > 0 && (
      <>
        {sectionLabel('Trailhead', 'Deterministic setup · step 0', true, false)}
        {card(thGroups.map(renderGroup), true)}
        {trailGroups.length > 0 && sectionLabel('Trail', testStepCount + ' test step' + (testStepCount === 1 ? '' : 's'), false, true)}
      </>
    )}
    {trailGroups.length > 0 && card(trailGroups.map(renderGroup), false)}
    {overlays}
    </>
  );
}

// Right-click menu for a timeline step: view/copy the raw log record(s) it was built
// from, or reveal the run's folder in Finder.
function StepContextMenu({ menu, sessionId, onClose, onView }) {
  useLucide();
  React.useEffect(() => {
    const k = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', k);
    return () => window.removeEventListener('keydown', k);
  }, []);
  const logs = (menu.row && menu.row._logs) || [];
  const copy = () => { try { navigator.clipboard.writeText(JSON.stringify(logs.length === 1 ? logs[0] : logs, null, 2)); } catch (_) {} onClose(); };
  const left = Math.min(menu.x, window.innerWidth - 210);
  const top = Math.min(menu.y, window.innerHeight - 140);
  const items = [
    { ico: 'file-json-2', label: 'View raw log', fn: onView, disabled: logs.length === 0 },
    { ico: 'copy', label: 'Copy raw log', fn: copy, disabled: logs.length === 0 },
    { ico: 'folder-open', label: 'Open in Finder', fn: () => { onClose(); if (sessionId) TB.revealSession(sessionId); }, disabled: !sessionId },
  ];
  return (
    <React.Fragment>
      <div style={{ position: 'fixed', inset: 0, zIndex: 60 }} onClick={onClose} onContextMenu={(e) => { e.preventDefault(); onClose(); }}></div>
      <div className="tb-card" style={{ position: 'fixed', left, top, zIndex: 61, minWidth: 190, padding: 5, background: 'var(--bg-elevated)', boxShadow: '0 16px 44px rgba(0,0,0,.5)' }}>
        {items.map((it, i) => (
          <div key={i} onClick={it.disabled ? undefined : it.fn}
            style={{ display: 'flex', alignItems: 'center', gap: 9, cursor: it.disabled ? 'default' : 'pointer', padding: '7px 10px', borderRadius: 6, fontSize: 13, opacity: it.disabled ? 0.4 : 1 }}
            onMouseEnter={(e) => { if (!it.disabled) e.currentTarget.style.background = 'var(--bg-standard)'; }}
            onMouseLeave={(e) => { e.currentTarget.style.background = 'transparent'; }}>
            <Ico n={it.ico} s={15} c="var(--text-subtle-variant)" />
            <span style={{ color: 'var(--text-standard)' }}>{it.label}</span>
          </div>
        ))}
      </div>
    </React.Fragment>
  );
}

// Modal showing the raw log record(s) a timeline step was built from.
function RawLogModal({ row, onClose }) {
  useLucide();
  const logs = (row && row._logs) || [];
  const text = React.useMemo(() => JSON.stringify(logs.length === 1 ? logs[0] : logs, null, 2), [row]);
  return (
    <div className="tb-overlay" onClick={onClose} style={{ alignItems: 'center', padding: 24 }}>
      <div className="tb-card" onClick={(e) => e.stopPropagation()} style={{ width: 'min(820px, 94vw)', maxHeight: '86vh', display: 'flex', flexDirection: 'column', padding: 0, overflow: 'hidden' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '14px 16px', borderBottom: '1px solid var(--tb-hairline)' }}>
          <Ico n="file-json-2" s={16} c="var(--text-subtle-variant)" />
          <div style={{ flex: 1, minWidth: 0 }}>
            <div style={{ fontSize: 14, fontWeight: 700 }}>Raw log{logs.length > 1 ? ` · ${logs.length} records` : ''}</div>
            <div className="tb-sub" style={{ fontSize: 11.5, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>Step {row.i} · {row.label}</div>
          </div>
          <Btn sm ico="x" onClick={onClose}>Close</Btn>
        </div>
        <div style={{ flex: 1, minHeight: 0, overflow: 'auto', padding: '12px 14px' }}>
          <SearchableText text={text} language="json" fontSize={11.5} />
        </div>
      </div>
    </div>
  );
}

function AnalyticsRow({ ev, last, open, onToggle, indent }) {
  const props = ev.properties || {};
  const keys = Object.keys(props);
  return (
    <div
      data-testid="analytics-event"
      onClick={onToggle}
      style={{
        display: 'flex', alignItems: 'flex-start', gap: 9, padding: indent ? '7px 12px 7px 20px' : '8px 12px', cursor: 'pointer',
        borderLeft: '2px solid rgba(166,121,245,.7)',
        background: 'rgba(166,121,245,.07)',
        borderBottom: !last ? '1px solid var(--tb-hairline)' : 'none',
      }}
    >
      <span style={{ width: 20, flexShrink: 0 }} />
      <span style={{ width: 8, height: 8, borderRadius: 99, background: '#a679f5', flexShrink: 0, marginTop: 5 }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <span style={{ fontSize: 13, fontWeight: 600, lineHeight: 1.35, flex: 1, minWidth: 0, color: '#c4a7f7' }}>{ev.name}</span>
          {keys.length > 0 && <Ico n={open ? 'chevron-down' : 'chevron-right'} s={13} c="var(--text-subtle)" style={{ flexShrink: 0 }} />}
        </div>
        {(() => { const sub = TB.summarizeAnalyticsProps(props); return sub ? (
          <div className="tb-mono" style={{ fontSize: 11, color: 'var(--text-subtle)', marginTop: 2, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{sub}</div>
        ) : null; })()}
        {open && keys.length > 0 && (
          <div style={{ marginTop: 6, display: 'grid', gridTemplateColumns: 'auto 1fr', gap: '2px 10px', fontSize: 11 }}>
            {keys.map((k) => (
              <React.Fragment key={k}>
                <span className="tb-mono tb-sub" style={{ whiteSpace: 'nowrap' }}>{k}</span>
                <span className="tb-mono" style={{ color: 'var(--text-standard)', wordBreak: 'break-all' }}>{String(props[k])}</span>
              </React.Fragment>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

// HTTP status → color, so a 4xx/5xx in the timeline reads red/amber at a glance.
function streamStatusColor(code) {
  const n = Number(code);
  if (!Number.isFinite(n)) return null;
  if (n >= 500) return 'var(--tb-fail)';
  if (n >= 400) return 'var(--tb-amber)';
  if (n >= 300) return 'var(--tb-running)';
  if (n >= 200) return 'var(--tb-pass)';
  return null;
}
function streamShortId(s) { const t = String(s || ''); return t.length > 10 ? t.slice(0, 8) + '…' : t; }
// Log levels get a color so ERROR/WARN pop in the stream.
const STREAM_LOG_COLOR = { ERROR: 'var(--tb-fail)', WARN: 'var(--tb-amber)', INFO: 'var(--tb-running)', DEBUG: 'var(--text-subtle)', VERBOSE: 'var(--text-subtle-variant)' };

// Turn one stream event's decoded payload into skimmable display fields, per stream contract:
//   { badge, badgeColor, title, sub }  — title is the meaningful headline (URL, message,
//   event name…), badge a short tag (HTTP method, log level, source), sub a dim detail.
// Plugins are keyed by their short label (the id's last dotted segment). Anything unknown
// or off-contract falls through to genericStreamDisplay, which prefers meaningful keys over ids.
function streamEventDisplay(label, data) {
  const obj = (data && typeof data === 'object' && !Array.isArray(data)) ? data : null;
  try {
    switch (label) {
      case 'network': {
        const req = obj && obj.request && obj.request._0;
        if (req) return { badge: req.httpMethod || 'REQ', title: req.url || '(request)', sub: null };
        const res = obj && obj.finalizedResponse && obj.finalizedResponse._0;
        if (res) return { badge: res.statusCode != null ? String(res.statusCode) : 'RESP', badgeColor: streamStatusColor(res.statusCode), title: 'Response', sub: res.requestID ? 'req ' + streamShortId(res.requestID) : null };
        break;
      }
      case 'logging': {
        const ci = obj && obj.columnItems;
        if (ci) return { badge: ci.Priority || null, badgeColor: STREAM_LOG_COLOR[ci.Priority], title: ci.Message || '(log)', sub: ci.Tag || null };
        break;
      }
      case 'analytics': {
        const ci = obj && obj.columnItems;
        if (ci) return { badge: ci.Source || null, title: ci.Event || '(event)', sub: null };
        break;
      }
      case 'userjourneys':
      case 'flowdebugger': {
        if (obj) {
          const et = obj.eventType;
          const type = et && (typeof et === 'object' ? et.type : et);
          const scenario = obj.metadata && obj.metadata.client_scenario;
          const title = [obj.journeyName, obj.eventValue].filter(Boolean).join(' · ') || scenario || type || '(event)';
          return { badge: type || null, title, sub: null };
        }
        break;
      }
      case 'featureflags':
        if (Array.isArray(data)) return { title: 'Feature flags snapshot', sub: data.length + ' flags' };
        break;
      case 'feature_eligibility': {
        const vals = obj && obj.values;
        if (Array.isArray(vals)) return { title: 'Feature eligibility', sub: vals.length + ' entries' };
        break;
      }
      case 'syncvalue':
        if (obj) return { badge: obj.updateTrigger || null, title: 'Sync values', sub: (Array.isArray(obj.syncValues) ? obj.syncValues.length : 0) + ' values' };
        break;
      case 'keyvaluestore': {
        const stores = obj && obj.updateSnapshot && obj.updateSnapshot._0 && obj.updateSnapshot._0.stores;
        if (Array.isArray(stores)) return { title: 'Key-value store snapshot', sub: stores.length + ' stores' };
        break;
      }
      case 'debugmenu': {
        const sections = obj && obj.updateSnapshot && obj.updateSnapshot._0 && obj.updateSnapshot._0.sections;
        if (Array.isArray(sections)) return { title: 'Debug menu snapshot', sub: sections.length + ' sections' };
        break;
      }
      case 'clientroutes':
        if (obj) return { title: obj.name || obj.id || '(route)', sub: null };
        break;
      case 'pushnotifications':
        if (obj && obj.appToken) return { title: 'Push token', sub: streamShortId(obj.appToken) };
        break;
      case 'appinfo':
        if (Array.isArray(data)) return { title: 'App info', sub: data.length ? data.length + ' items' : 'empty' };
        break;
    }
  } catch (_) { /* fall through to generic */ }
  return genericStreamDisplay(data);
}

// Fallback when a plugin has no bespoke contract: surface the most meaningful scalar,
// preferring message/event/name/url-style keys and explicitly skipping id/timestamp noise.
const STREAM_PREFERRED_KEY = /^(message|event|eventName|eventValue|name|url|title|value|description|reason|status|method|priority|tag)$/i;
const STREAM_NOISE_KEY = /^(id|uuid|timestamp|ts|time|receivedat)$/i;
function genericStreamDisplay(data) {
  if (data == null) return { title: '(no data)' };
  if (typeof data === 'string') return { title: data.trim() || '(empty)' };
  if (typeof data !== 'object') return { title: String(data) };
  if (Array.isArray(data)) return { title: data.length + ' item' + (data.length === 1 ? '' : 's') };
  const keys = Object.keys(data);
  const scalar = (k) => data[k] != null && typeof data[k] !== 'object';
  const pref = keys.find((k) => STREAM_PREFERRED_KEY.test(k) && scalar(k));
  if (pref) return { title: String(data[pref]), sub: null };
  const first = keys.find((k) => scalar(k) && !STREAM_NOISE_KEY.test(k));
  if (first) return { title: first + ': ' + String(data[first]), sub: null };
  const nested = keys.find((k) => !STREAM_NOISE_KEY.test(k));
  return { title: nested || keys.slice(0, 4).join(', ') || '(event)', sub: null };
}

// The event's own id (top-level `data.id`), pulled out so it can sit with the timestamp
// instead of cluttering the headline and the JSON body.
function streamEventId(ev) {
  const d = ev && ev.data;
  return (d && typeof d === 'object' && !Array.isArray(d) && d.id != null) ? String(d.id) : null;
}

// Expanded detail for a stream event: a muted id + timestamp line, then the decoded JSON
// (with the top-level id removed — it's shown above). Plain block, no in-text search.
// Scalar colors for the JSON tree, so types read apart at a glance.
const JSON_COLORS = {
  key: 'var(--text-subtle)',
  str: '#98c379',
  num: '#d19a66',
  bool: '#c678dd',
  nul: 'var(--text-subtle-variant)',
  punc: 'var(--text-subtle-variant)',
};

// Many stream-event payloads stash JSON inside a string (analytics `Properties`, user-journey
// blobs, network bodies). Detect + parse those so they render as a real subtree instead
// of one long line of escaped text.
function tryParseJsonString(s) {
  if (typeof s !== 'string') return undefined;
  const t = s.trim();
  const wrapped = (t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'));
  if (!wrapped || t.length < 2) return undefined;
  try { const v = JSON.parse(t); return (v && typeof v === 'object') ? v : undefined; } catch (_) { return undefined; }
}

// A long string value with a show-more toggle, so a base64 body or giant blob doesn't
// flood the view.
function JsonString({ s }) {
  const [more, setMore] = React.useState(false);
  const LIMIT = 500;
  const long = s.length > LIMIT;
  return (
    <span style={{ color: JSON_COLORS.str, whiteSpace: 'pre-wrap', wordBreak: 'break-word' }}>
      {long && !more ? s.slice(0, LIMIT) : s}
      {long && (
        <span onClick={(e) => { e.stopPropagation(); setMore((m) => !m); }}
          style={{ cursor: 'pointer', color: 'var(--tb-running)', marginLeft: 4, whiteSpace: 'nowrap' }}>
          {more ? ' show less' : `… +${s.length - LIMIT}`}
        </span>
      )}
    </span>
  );
}

// Fixed indent step per nesting level (px). Constant — never compounded by key width — so
// deep trees stay dense on the left.
const JSON_INDENT = 12;

// A leaf value, color-coded by type. Long strings get a show-more toggle.
function JsonScalar({ value }) {
  if (value === null) return <span style={{ color: JSON_COLORS.nul }}>null</span>;
  if (typeof value === 'number') return <span style={{ color: JSON_COLORS.num }}>{String(value)}</span>;
  if (typeof value === 'boolean') return <span style={{ color: JSON_COLORS.bool }}>{String(value)}</span>;
  return <JsonString s={String(value)} />;
}

// One key/value entry. Scalars render inline (`key: value`). Objects/arrays (and strings
// that are themselves JSON) render a collapsible header (`▸ key  N fields`) with children
// indented a FIXED step below — so indentation is constant per level, not pushed right by
// the width of each parent key (the bug that ate the left half of the panel).
function JsonNode({ k, value, depth }) {
  const embedded = typeof value === 'string' ? tryParseJsonString(value) : undefined;
  const node = embedded !== undefined ? embedded : value;
  const composite = node != null && typeof node === 'object';
  const isArr = Array.isArray(node);
  const entries = composite ? (isArr ? node.map((v, i) => [i, v]) : Object.entries(node)) : [];
  const big = entries.length > 25;
  const [open, setOpen] = React.useState(depth < 3 && !big);
  const [showAll, setShowAll] = React.useState(false);

  const keyEl = (
    <span style={{ color: JSON_COLORS.key, fontWeight: 500, flexShrink: 0 }}>{String(k)}<span style={{ color: JSON_COLORS.punc, fontWeight: 400 }}>:</span></span>
  );

  if (!composite) {
    return (
      <div style={{ display: 'flex', gap: 5, alignItems: 'baseline' }}>
        {keyEl}
        <div style={{ minWidth: 0, flex: 1 }}><JsonScalar value={value} /></div>
      </div>
    );
  }
  if (entries.length === 0) {
    return <div style={{ display: 'flex', gap: 5 }}>{keyEl}<span style={{ color: JSON_COLORS.punc }}>{isArr ? '[ ]' : '{ }'}</span></div>;
  }
  const CAP = 200;
  const shown = (!showAll && entries.length > CAP) ? entries.slice(0, CAP) : entries;
  const summary = isArr ? `${entries.length} item${entries.length === 1 ? '' : 's'}` : `${entries.length} field${entries.length === 1 ? '' : 's'}`;
  return (
    <div>
      <div onClick={(e) => { e.stopPropagation(); setOpen((o) => !o); }} style={{ display: 'flex', gap: 4, alignItems: 'center', cursor: 'pointer', userSelect: 'none' }}>
        <Ico n={open ? 'chevron-down' : 'chevron-right'} s={11} c="var(--text-subtle)" style={{ flexShrink: 0 }} />
        {keyEl}
        {embedded !== undefined && <span title="parsed from a JSON string" style={{ fontSize: 8.5, fontWeight: 700, letterSpacing: 0.3, color: 'var(--text-subtle-variant)', border: '1px solid var(--tb-hairline)', borderRadius: 3, padding: '0 3px' }}>JSON</span>}
        <span style={{ color: JSON_COLORS.punc, fontSize: 10.5 }}>{summary}</span>
      </div>
      {open && (
        <div style={{ paddingLeft: JSON_INDENT, marginLeft: 4, borderLeft: '1px solid var(--tb-hairline)' }}>
          {shown.map(([ck, cv]) => <JsonNode key={ck} k={ck} value={cv} depth={depth + 1} />)}
          {entries.length > CAP && !showAll && (
            <div onClick={(e) => { e.stopPropagation(); setShowAll(true); }} style={{ cursor: 'pointer', color: 'var(--tb-running)', fontSize: 11 }}>+ {entries.length - CAP} more…</div>
          )}
        </div>
      )}
    </div>
  );
}

// Root of the JSON tree. Renders the top-level fields flush-left — no wrapping "N fields"
// header row and no extra indent level for the outermost object. Caps the rendered rows like
// JsonNode does, so a root that is itself a huge array (e.g. a feature-flags snapshot of ~1600
// items) doesn't mount every row on first open.
function JsonView({ value }) {
  const [showAll, setShowAll] = React.useState(false);
  if (value == null || typeof value !== 'object') return <JsonScalar value={value} />;
  const isArr = Array.isArray(value);
  const entries = isArr ? value.map((v, i) => [i, v]) : Object.entries(value);
  if (entries.length === 0) return <span style={{ color: JSON_COLORS.punc }}>{isArr ? '[ ]' : '{ }'}</span>;
  const CAP = 200;
  const shown = (!showAll && entries.length > CAP) ? entries.slice(0, CAP) : entries;
  return (
    <div>
      {shown.map(([k, v]) => <JsonNode key={k} k={k} value={v} depth={1} />)}
      {entries.length > CAP && !showAll && (
        <div onClick={(e) => { e.stopPropagation(); setShowAll(true); }} style={{ cursor: 'pointer', color: 'var(--tb-running)', fontSize: 11 }}>+ {entries.length - CAP} more…</div>
      )}
    </div>
  );
}

// Expanded detail for a stream event: a muted id + timestamp line and a Copy button, then a
// readable JSON tree (top-level id removed — it's shown above).
function StreamExpanded({ ev }) {
  const id = streamEventId(ev);
  const [copied, setCopied] = React.useState(false);
  const data = React.useMemo(() => {
    if (id != null && ev.data && typeof ev.data === 'object' && !Array.isArray(ev.data)) {
      const d = {}; for (const k of Object.keys(ev.data)) if (k !== 'id') d[k] = ev.data[k];
      return d;
    }
    return ev.data;
  }, [ev]);
  const copy = (e) => {
    e.stopPropagation();
    // writeText is async — only flag "Copied" on resolve, and swallow rejection so it can't surface
    // as an unhandled promise rejection.
    try {
      navigator.clipboard.writeText(JSON.stringify(ev.data, null, 2))
        .then(() => { setCopied(true); setTimeout(() => setCopied(false), 1200); })
        .catch(() => {});
    } catch (_) {}
  };
  // Stop clicks inside the detail (node toggles, text selection) from bubbling up to the
  // row's onToggle, which would collapse the whole event.
  return (
    <div style={{ marginTop: 6 }} onClick={(e) => e.stopPropagation()}>
      <div className="tb-mono tb-sub" style={{ fontSize: 10.5, marginBottom: 5, display: 'flex', gap: 10, alignItems: 'center', flexWrap: 'wrap' }}>
        {ev.receivedAt && <span>{ev.receivedAt}</span>}
        {id && <span>id {id}</span>}
        <span style={{ flex: 1 }} />
        <span {...clickable(copy)} style={{ cursor: 'pointer', color: copied ? 'var(--tb-pass)' : 'var(--tb-running)' }}>{copied ? 'Copied' : 'Copy JSON'}</span>
      </div>
      <div className="tb-mono" data-selectable style={{ fontSize: 11.5, lineHeight: 1.5, background: 'var(--bg-standard)', border: '1px solid var(--tb-hairline)', borderRadius: 8, padding: '8px 10px', maxHeight: 380, overflow: 'auto' }}>
        <JsonView value={data} />
      </div>
    </div>
  );
}

// One captured stream event, interlaced into the timeline. Tinted per stream type; shows a
// skimmable headline (URL / message / event name) + short badge; expands to full JSON.
function StreamRow({ ev, color, last, open, onToggle, indent }) {
  const c = color || TL_CAT_COLORS.stream;
  const label = ev.label || ev.streamId;
  const disp = React.useMemo(() => streamEventDisplay(label, ev.data), [ev, label]);
  return (
    <div
      data-testid="stream-event"
      onClick={onToggle}
      style={{
        display: 'flex', alignItems: 'flex-start', gap: 9, padding: indent ? '7px 12px 7px 20px' : '8px 12px', cursor: 'pointer',
        borderLeft: '2px solid ' + c,
        background: 'rgba(255,255,255,.02)',
        borderBottom: !last ? '1px solid var(--tb-hairline)' : 'none',
      }}
    >
      <span style={{ width: 20, flexShrink: 0 }} />
      <span style={{ width: 8, height: 8, borderRadius: 2, background: c, flexShrink: 0, marginTop: 5 }} />
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
          <span title={ev.streamId} style={{ fontSize: 9.5, fontWeight: 700, letterSpacing: 0.3, textTransform: 'uppercase', color: c, flexShrink: 0 }}>{label}</span>
          <StreamBadge badge={disp.badge} color={disp.badgeColor || c} />
          <span data-selectable title={disp.title} style={{ fontSize: 12.5, fontWeight: 500, color: 'var(--text-standard)', flex: 1, minWidth: 0, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{disp.title}</span>
          {disp.sub && <span className="tb-mono tb-sub" style={{ fontSize: 11, flexShrink: 0, maxWidth: 200, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{disp.sub}</span>}
          <Ico n={open ? 'chevron-down' : 'chevron-right'} s={13} c="var(--text-subtle)" style={{ flexShrink: 0 }} />
        </div>
        {open && <StreamExpanded ev={ev} />}
      </div>
    </div>
  );
}

// Short monospace tag (HTTP method, log level, analytics source) shown before the headline.
function StreamBadge({ badge, color }) {
  if (!badge) return null;
  return (
    <span className="tb-mono" style={{ fontSize: 9.5, fontWeight: 700, letterSpacing: 0.2, color: color, background: 'color-mix(in srgb, ' + color + ' 16%, transparent)', borderRadius: 4, padding: '1px 5px', flexShrink: 0, whiteSpace: 'nowrap' }}>{badge}</span>
  );
}

Object.assign(window, { Timeline, fmtClock, TL_CAT_COLORS, STREAM_PALETTE, CAT_LABELS, CAT_ORDER, ALL_CATS_ON, stepCategory, VerticalScrubber, FilterChip, TimelineFilterPopover, StepStack, TraceRow, AnalyticsRow, StreamRow, StreamBadge, StreamExpanded, JsonView, JsonNode, JsonScalar, JsonString, tryParseJsonString, streamEventId, streamEventDisplay, genericStreamDisplay, streamStatusColor, StepContextMenu, RawLogModal });
