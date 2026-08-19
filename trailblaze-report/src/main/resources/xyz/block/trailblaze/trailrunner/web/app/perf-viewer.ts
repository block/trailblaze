// Embedded viewer for the performance-analysis report: an Instruments-style time profiler over
// one build's trail sessions. Vanilla DOM + one canvas, no dependencies, bundled standalone via
// perf-viewer-boot.ts (see perf-viewer-bundle.macro.ts) and embedded into every exported document
// by buildPerfReportHtml.
//
// Shape mirrors run-report-viewer.ts: a single PERF_VIEWER() closure over a mutable `st` state
// object. The chrome (header, stats, tabs, tables, inspector) is DOM re-rendered per state
// change; the timeline itself is a canvas redrawn per animation frame (zoom/pan at 60fps would
// thrash innerHTML). Hit-testing uses the rect list the draw pass emits.
//
// Interaction model (Instruments conventions):
//   wheel                zoom at cursor (horizontal wheel / shift+wheel pans)
//   drag                 select a time range (detail tables recompute over it)
//   click span           inspect it; double-click zooms to it
//   click step band      select that step's range
//   + / - / 0, Esc       zoom in / out / fit, clear selection
//
// Data contract: PerfReportPayload from the inert #tb-perf-data JSON script (perf-types.d.ts).
// All span times are ms offsets from each session's t0; driver spans are device-clock and render
// on their own lane, never against the host-clock ruler's truth.
import { bottomUpAggregate } from './perf-extract';

/** Clipped overlap length of [s, e] with [lo, hi]. */
function clipLen(s: number, e: number, lo: number, hi: number): number {
  return Math.max(0, Math.min(e, hi) - Math.max(s, lo));
}

/** A span's self time inside [lo, hi]: its selfSegs clipped to the range. */
function rangeSelf(span: PerfSpan, lo: number, hi: number): number {
  let total = 0;
  for (const [a, b] of span.selfSegs) total += clipLen(a, b, lo, hi);
  return total;
}

/** Human duration: 450ms / 2.41s / 12.3s / 3m 12s. */
function fmtMs(ms: number): string {
  if (!Number.isFinite(ms)) return '';
  const abs = Math.abs(ms);
  if (abs < 1000) return `${Math.round(ms)}ms`;
  if (abs < 10_000) return `${(ms / 1000).toFixed(2)}s`;
  if (abs < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  const m = Math.trunc(ms / 60_000);
  const s = Math.abs(ms - m * 60_000) / 1000;
  return `${m}m ${s.toFixed(0)}s`;
}

/** Signed duration for diff columns: "+2.41s" / "-450ms" / "0ms". */
function fmtDelta(ms: number): string {
  const r = fmtMs(Math.abs(ms));
  return ms > 0 ? `+${r}` : ms < 0 ? `-${r}` : '0ms';
}

/** Largest 1/2/5 * 10^k step at or below `raw` px-derived spacing (ms). Never below 1ms. */
function niceTickStep(raw: number): number {
  const target = Math.max(1, raw);
  const mag = Math.pow(10, Math.floor(Math.log10(target)));
  for (const m of [5, 2, 1]) if (mag * m <= target) return mag * m;
  return mag;
}

/** Ruler tick label for offset `t` at tick step `step` (both ms). */
function fmtTick(t: number, step: number): string {
  const s = t / 1000;
  if (step >= 1000) {
    const m = Math.floor(s / 60);
    const sec = s - m * 60;
    return m ? `${m}:${String(Math.round(sec)).padStart(2, '0')}` : `${Math.round(sec)}s`;
  }
  return `${s.toFixed(step >= 100 ? 1 : step >= 10 ? 2 : 3)}s`;
}

/**
 * A/B comparison rows: tree spans of both sessions grouped by (kind, name), self time clipped to
 * [lo, hi] in each session's own offset space, sorted by |delta| descending. Pure (unit-tested).
 */
function diffRows(a: PerfSessionData, b: PerfSessionData, lo: number, hi: number): Array<{ name: string; kind: PerfSpanKind; selfA: number; selfB: number; countA: number; countB: number; delta: number }> {
  const byKey = new Map<string, { name: string; kind: PerfSpanKind; selfA: number; selfB: number; countA: number; countB: number; delta: number }>();
  const fold = (data: PerfSessionData, side: 'a' | 'b') => {
    for (const sp of data.spans) {
      if (sp.kind === 'driver') continue;
      const self = rangeSelf(sp, lo, hi);
      if (self <= 0 && clipLen(sp.effS, sp.effE, lo, hi) <= 0) continue;
      const key = `${sp.kind}|${sp.name}`;
      let row = byKey.get(key);
      if (!row) { row = { name: sp.name, kind: sp.kind, selfA: 0, selfB: 0, countA: 0, countB: 0, delta: 0 }; byKey.set(key, row); }
      if (side === 'a') { row.selfA += self; row.countA += 1; } else { row.selfB += self; row.countB += 1; }
    }
  };
  fold(a, 'a');
  fold(b, 'b');
  const rows = [...byKey.values()];
  rows.forEach((r) => { r.delta = r.selfB - r.selfA; });
  rows.sort((x, y) => Math.abs(y.delta) - Math.abs(x.delta) || y.selfA + y.selfB - (x.selfA + x.selfB));
  return rows;
}

/**
 * Human display identity for a session. CLI sessions carry the raw session-dir id as their title
 * (`ios_send_money__contribute_to_pool_trail_8ab83d55`); parse that shape into a readable trail
 * name plus platform - strip the trailing `_trail_<hex>` run suffix, lift a leading platform
 * token, humanize the `__`-separated suite/case segments. A title that isn't dir-shaped (a real
 * display name) passes through untouched. The raw id stays available via meta.title for the
 * session-details surface. Pure (unit-tested).
 */
function sessionDisplay(meta: RunMeta): { name: string; platform: string } {
  const raw = String(meta.title || meta.trailId || '');
  let platform = String(meta.platform || '').toLowerCase();
  if (!raw || !/^[a-z0-9_]+$/.test(raw) || !raw.includes('_')) return { name: raw, platform };
  let rest = raw.replace(/_trail_[0-9a-f]{6,}$/, '');
  const head = rest.split('_', 1)[0];
  if (head === 'android' || head === 'ios' || head === 'web') {
    if (!platform) platform = head;
    rest = rest.slice(head.length + 1);
  }
  const seg = (s: string): string => {
    const words = s.replace(/_+/g, ' ').trim();
    return words ? words[0].toUpperCase() + words.slice(1) : '';
  };
  const name = rest.split('__').map(seg).filter(Boolean).join(' · ');
  return { name: name || raw, platform };
}

function PERF_VIEWER(): void {
  const esc = (s: unknown): string => String(s == null ? '' : s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c] as string));

  // ---- payload ----
  const readPayload = (): PerfReportPayload | null => {
    try {
      const el = document.getElementById('tb-perf-data');
      if (el && el.textContent) return JSON.parse(el.textContent);
    } catch (_) {}
    const w = window as unknown as Record<string, unknown>;
    return (w.__TB_PERF_DATA__ as PerfReportPayload) || null;
  };
  const payload = readPayload();
  const app = document.getElementById('app');
  if (!app) return;
  const sessions: PerfSessionPayload[] = (payload && payload.sessions) || [];
  if (!sessions.length) {
    app.innerHTML = `<div class="perf-empty">No profiled sessions in this report.</div>`;
    return;
  }

  // Parsed display identities, in session order (index rows, pickers, compare labels). The raw
  // session id is demoted to the session-details line at the bottom of the profile.
  const labels = sessions.map((s) => sessionDisplay(s.meta));
  const labelOf = (i: number): string => labels[i].name || `Run ${i + 1}`;

  // ---- state ----
  const canPushState = location.protocol !== 'about:' && location.href !== 'about:srcdoc';
  const q = new URLSearchParams(location.search);
  const MULTI = sessions.length > 1;
  const clampSession = (n: number): number => Math.min(sessions.length - 1, Math.max(0, n));
  const TABS: Array<[string, string]> = [['bottomup', 'Bottom-Up'], ['tree', 'Call Tree'], ['tax', 'Timeout Tax'], ['gaps', 'Gaps']];
  const st = {
    // Landing view mirrors the interactive run report: a run summary index when the report holds
    // several sessions, straight into the profile when there's only one.
    view: (q.get('view') === 'runs' || (MULTI && !q.has('session')) ? 'index' : 'detail') as 'index' | 'detail',
    s: clampSession(parseInt(q.get('session') || '0', 10) || 0),
    cmp: null as number | null,
    tab: q.get('tab') || (q.get('compare') ? 'diff' : 'bottomup'), // writeUrl omits the mode default

    vS: 0, vE: 1,                        // visible window (ms offsets)
    sel: null as [number, number] | null, // selected range
    focus: null as { side: 'a' | 'b'; id: number } | null,
    hover: null as { side: 'a' | 'b'; id: number } | null,
    expanded: new Set<string>(),
    timelineH: Math.max(180, Math.round(window.innerHeight * 0.42)),
  };
  const qCmp = q.get('compare');
  if (qCmp != null && qCmp !== '' && !Number.isNaN(parseInt(qCmp, 10))) st.cmp = clampSession(parseInt(qCmp, 10));
  const dataOf = (side: 'a' | 'b'): PerfSessionData => sessions[side === 'a' ? st.s : (st.cmp as number)].data;
  const domainEnd = (): number => Math.max(dataOf('a').t1, st.cmp != null ? dataOf('b').t1 : 0);
  // Compare mode adds the diff tab and defaults to it (setCompare), but every range-scoped table
  // stays reachable — the A side keeps its meaning while B is overlaid.
  const validTab = (): string => {
    if (TABS.some(([id]) => id === st.tab)) return st.tab;
    if (st.cmp != null && st.tab === 'diff') return 'diff';
    return st.cmp != null ? 'diff' : 'bottomup';
  };
  const qSel = (q.get('sel') || '').split('-').map(Number);
  if (qSel.length === 2 && qSel.every((n) => Number.isFinite(n)) && qSel[1] > qSel[0]) st.sel = [qSel[0], qSel[1]];

  const writeUrl = (): void => {
    if (!canPushState) return;
    const params = new URLSearchParams();
    if (st.view === 'index') {
      params.set('view', 'runs');
    } else {
      params.set('session', String(st.s));
      if (st.cmp != null) params.set('compare', String(st.cmp));
      if (st.tab !== (st.cmp != null ? 'diff' : 'bottomup')) params.set('tab', st.tab);
      if (st.sel) params.set('sel', `${Math.round(st.sel[0])}-${Math.round(st.sel[1])}`);
    }
    try { history.replaceState(null, '', `?${params}`); } catch (_) {}
  };

  // Same sun/moon toggle as the interactive run report (theme state shares its localStorage key,
  // the icons are CSS-swapped on [data-theme]).
  const themeToggleHtml = `<button class="themetoggle" type="button" data-theme-toggle aria-label="Toggle theme" title="Toggle theme"><svg class="themeicon sun" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3.6" fill="none" stroke="currentColor" stroke-width="1.75"/><path d="M12 2.5v2M12 19.5v2M5.28 5.28l1.42 1.42M17.3 17.3l1.42 1.42M2.5 12h2M19.5 12h2M5.28 18.72l1.42-1.42M17.3 6.7l1.42-1.42" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg><svg class="themeicon moon" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 15.1A8 8 0 0 1 8.9 4.5a8 8 0 1 0 10.6 10.6Z" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg></button>`;
  const backIconSvg = '<svg class="backicon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5 5 12l7 7M5 12h14" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>';

  // ---- skeleton ----
  app.innerHTML = `
    <div id="pf-index" hidden></div>
    <div id="pf-detail" hidden>
    <div class="perf-header">
      ${MULTI ? `<button class="back" type="button" id="pf-back" aria-label="All runs" title="All runs">${backIconSvg}</button>` : ''}
      <h1>Performance Analysis</h1>
      <span class="badge" id="pf-badge" hidden></span>
      <span class="spacer"></span>
      <span class="perf-field"><span class="perf-label">Session</span><span class="selectwrap"><select class="perf-select" id="pf-session"></select></span></span>
      <span class="perf-field"><span class="perf-label">Compare</span><span class="selectwrap"><select class="perf-select" id="pf-compare"></select></span></span>
      <button class="iconbtn" id="pf-fit" title="Fit whole session (0)">Fit</button>
      ${themeToggleHtml}
    </div>
    <div class="perf-stats" id="pf-stats"></div>
    <div class="perf-timeline" id="pf-timeline">
      <canvas id="pf-canvas"></canvas>
      <div class="perf-hint">wheel: zoom · shift+wheel: pan · drag: select range · double-click: zoom to span</div>
    </div>
    <div class="perf-resizer" id="pf-resizer" title="Drag to resize"></div>
    <div class="perf-detail">
      <div class="perf-pane">
        <div class="perf-tabs" id="pf-tabs"></div>
        <div class="perf-body" id="pf-body"></div>
      </div>
      <div class="perf-inspector" id="pf-inspector" hidden></div>
    </div>
    <div class="perf-footer">
      <div class="perf-session-meta" id="pf-session-meta"></div>
      <div>Generated on demand by <code>trailblaze profile &lt;logs-dir&gt;</code>: it profiles every
      session in that logs directory and writes
      <code>&lt;logs-dir&gt;/trailblaze_performance_analysis.html</code>.</div>
    </div>
    </div>
    <div class="perf-tooltip" id="pf-tooltip"></div>`;
  const $ = (id: string): HTMLElement => document.getElementById(id) as HTMLElement;
  const timelineEl = $('pf-timeline');
  const canvas = $('pf-canvas') as HTMLCanvasElement;
  const ctx = canvas.getContext('2d') as CanvasRenderingContext2D;
  const tooltip = $('pf-tooltip');

  // ---- palette (canvas needs resolved colors; re-read on theme flips) ----
  let pal: Record<string, string> = {};
  const readPalette = (): void => {
    const cs = getComputedStyle(document.documentElement);
    const v = (name: string): string => cs.getPropertyValue(name).trim();
    pal = {
      bg: v('--bg'), bg2: v('--bg2'), bg3: v('--bg3'), line: v('--line'), line2: v('--line2'),
      txt: v('--txt'), sub: v('--sub'), focus: v('--focus'),
      tool: v('--accent-8'), llm: v('--violet-9'), maestro: v('--cyan-9'), driver: v('--neutral-7'),
      fail: v('--error-9'), pass: v('--success-9'), amber: v('--warning-9'),
      stepOk: v('--success-3'), stepFail: v('--error-3'), stepHead: v('--neutral-4'),
    };
  };
  readPalette();

  // ---- timeline geometry ----
  const GUTTER = 150;
  const RULER_H = 22;
  const ROW_H = 18;
  const ROW_GAP = 2;
  const SECTION_GAP = 8;
  const kindColor = (sp: PerfSpan): string => (!sp.ok ? pal.fail : sp.kind === 'llm' ? pal.llm : sp.kind === 'maestro' ? pal.maestro : sp.kind === 'driver' ? pal.driver : pal.tool);

  interface Hit { x0: number; x1: number; y0: number; y1: number; side: 'a' | 'b'; id?: number; step?: number }
  let hits: Hit[] = [];

  /** Track rows for one session block: label + y/height per lane. */
  const blockLayout = (data: PerfSessionData): { rows: Array<{ label: string; y: number; h: number; lane: string }>; h: number } => {
    const maxDepth = data.spans.reduce((m, sp) => (sp.kind !== 'driver' ? Math.max(m, sp.depth) : m), 0);
    const rows: Array<{ label: string; y: number; h: number; lane: string }> = [];
    let y = 0;
    const push = (label: string, h: number, lane: string): void => { rows.push({ label, y, h, lane }); y += h + ROW_GAP; };
    push('Steps', ROW_H + 4, 'steps');
    push('Gaps', 10, 'gaps');
    push('Tools', (maxDepth + 1) * (ROW_H + ROW_GAP) - ROW_GAP, 'flame');
    if (data.spans.some((sp) => sp.kind === 'llm')) push('LLM', ROW_H, 'llm');
    if (data.spans.some((sp) => sp.kind === 'driver')) push('Device (skewed clock)', ROW_H, 'driver');
    return { rows, h: y - ROW_GAP };
  };

  const contentHeight = (): number => {
    let h = RULER_H + SECTION_GAP + blockLayout(dataOf('a')).h;
    if (st.cmp != null) h += SECTION_GAP + 16 + blockLayout(dataOf('b')).h;
    return h + SECTION_GAP;
  };

  const cssW = (): number => timelineEl.clientWidth;
  const tToX = (t: number): number => GUTTER + ((t - st.vS) / (st.vE - st.vS)) * (cssW() - GUTTER);
  const xToT = (x: number): number => st.vS + ((x - GUTTER) / (cssW() - GUTTER)) * (st.vE - st.vS);

  const clampView = (): void => {
    const end = domainEnd();
    const minW = 10;
    const maxW = end * 1.04 || 1;
    let w = Math.min(maxW, Math.max(minW, st.vE - st.vS));
    let s = Math.max(-end * 0.02, Math.min(st.vS, end * 1.02 - w));
    st.vS = s;
    st.vE = s + w;
  };
  const fit = (): void => { st.vS = 0; st.vE = domainEnd() || 1; schedule(); writeUrl(); };
  const zoomAt = (x: number, factor: number): void => {
    const t = xToT(Math.max(GUTTER, x));
    st.vS = t - (t - st.vS) * factor;
    st.vE = t + (st.vE - t) * factor;
    clampView();
    schedule();
  };
  const zoomToRange = (a: number, b: number): void => {
    const pad = Math.max(1, (b - a) * 0.06);
    st.vS = a - pad;
    st.vE = b + pad;
    clampView();
    schedule();
  };

  // ---- draw ----
  let rafPending = false;
  const schedule = (): void => {
    if (rafPending) return;
    rafPending = true;
    requestAnimationFrame(() => { rafPending = false; draw(); });
  };

  const drawBlock = (data: PerfSessionData, top: number, side: 'a' | 'b'): number => {
    const { rows } = blockLayout(data);
    const w = cssW();
    const barLabel = (sp: PerfSpan, x0: number, x1: number, y: number): void => {
      if (x1 - x0 < 34) return;
      ctx.save();
      ctx.beginPath();
      ctx.rect(x0 + 3, y, x1 - x0 - 6, ROW_H);
      ctx.clip();
      ctx.fillStyle = '#fff';
      ctx.font = '10px -apple-system, BlinkMacSystemFont, sans-serif';
      ctx.textBaseline = 'middle';
      ctx.fillText(sp.name, x0 + 4, y + ROW_H / 2 + 0.5);
      ctx.restore();
    };
    for (const row of rows) {
      const y = top + row.y;
      ctx.fillStyle = pal.sub;
      ctx.font = '600 10px -apple-system, BlinkMacSystemFont, sans-serif';
      ctx.textBaseline = 'middle';
      ctx.fillText(row.label, 8, y + Math.min(row.h, ROW_H) / 2);
      if (row.lane === 'steps') {
        data.steps.forEach((step, i) => {
          const e = step.e == null ? data.t1 : step.e;
          const x0 = Math.max(GUTTER, tToX(step.s));
          const x1 = Math.min(w, tToX(e));
          if (x1 <= GUTTER || x0 >= w) return;
          ctx.fillStyle = step.trailhead ? pal.stepHead : step.ok ? pal.stepOk : pal.stepFail;
          ctx.fillRect(x0, y, x1 - x0, row.h);
          ctx.strokeStyle = step.trailhead ? pal.line2 : step.ok ? pal.pass : pal.fail;
          ctx.strokeRect(x0 + 0.5, y + 0.5, x1 - x0 - 1, row.h - 1);
          if (x1 - x0 > 40) {
            ctx.save();
            ctx.beginPath();
            ctx.rect(x0 + 3, y, x1 - x0 - 6, row.h);
            ctx.clip();
            ctx.fillStyle = pal.txt;
            ctx.font = '10px -apple-system, BlinkMacSystemFont, sans-serif';
            ctx.fillText(`${i + 1}. ${step.label}`, x0 + 5, y + row.h / 2 + 0.5);
            ctx.restore();
          }
          hits.push({ x0, x1, y0: y, y1: y + row.h, side, step: i });
        });
      } else if (row.lane === 'gaps') {
        ctx.fillStyle = pal.amber;
        for (const gap of data.gaps) {
          const x0 = Math.max(GUTTER, tToX(gap.s));
          const x1 = Math.min(w, tToX(gap.e));
          if (x1 > GUTTER && x0 < w) ctx.fillRect(x0, y, Math.max(1, x1 - x0), row.h);
        }
      } else {
        if (row.lane === 'driver') {
          // Driver spans keep their raw device-clock offsets (quarantined, never re-anchored), so
          // a skew larger than the session window pushes the whole lane off the timeline. Say so
          // instead of rendering a silently empty lane.
          const drivers = data.spans.filter((sp) => sp.kind === 'driver');
          if (drivers.length && !drivers.some((sp) => sp.e > 0 && sp.s < data.t1)) {
            ctx.fillStyle = pal.sub;
            ctx.font = '10px -apple-system, BlinkMacSystemFont, sans-serif';
            ctx.textBaseline = 'middle';
            ctx.fillText('device-clock spans are beyond the timeline (clock skew exceeds the session window)', GUTTER + 5, y + row.h / 2 + 0.5);
            continue;
          }
        }
        for (const sp of data.spans) {
          const inLane = row.lane === 'flame' ? sp.kind !== 'driver' : sp.kind === row.lane;
          if (!inLane) continue;
          const yRow = row.lane === 'flame' ? y + sp.depth * (ROW_H + ROW_GAP) : y;
          const x0 = Math.max(GUTTER, tToX(sp.s));
          const x1 = Math.min(w, tToX(sp.e));
          if (x1 <= GUTTER || x0 >= w) continue;
          const focused = (st.focus && st.focus.side === side && st.focus.id === sp.id) || (st.hover && st.hover.side === side && st.hover.id === sp.id);
          ctx.globalAlpha = row.lane === 'flame' || sp.kind !== 'llm' ? 1 : 1;
          ctx.fillStyle = kindColor(sp);
          ctx.fillRect(x0, yRow, Math.max(1, x1 - x0), ROW_H);
          if (focused) {
            ctx.strokeStyle = pal.focus;
            ctx.lineWidth = 2;
            ctx.strokeRect(x0 + 1, yRow + 1, Math.max(1, x1 - x0) - 2, ROW_H - 2);
            ctx.lineWidth = 1;
          }
          barLabel(sp, x0, x1, yRow);
          hits.push({ x0, x1, y0: yRow, y1: yRow + ROW_H, side, id: sp.id });
        }
      }
    }
    return rows.length ? rows[rows.length - 1].y + rows[rows.length - 1].h : 0;
  };

  const draw = (): void => {
    const w = cssW();
    const h = contentHeight();
    const dpr = window.devicePixelRatio || 1;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) {
      canvas.width = Math.round(w * dpr);
      canvas.height = Math.round(h * dpr);
      canvas.style.width = `${w}px`;
      canvas.style.height = `${h}px`;
    }
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.clearRect(0, 0, w, h);
    ctx.fillStyle = pal.bg;
    ctx.fillRect(0, 0, w, h);
    hits = [];

    // Ruler + grid.
    const msPerPx = (st.vE - st.vS) / Math.max(1, w - GUTTER);
    const step = niceTickStep(msPerPx * 90);
    ctx.font = '10px ui-monospace, Menlo, monospace';
    ctx.textBaseline = 'middle';
    for (let t = Math.ceil(st.vS / step) * step; t <= st.vE; t += step) {
      const x = tToX(t);
      if (x < GUTTER) continue;
      ctx.strokeStyle = pal.line;
      ctx.beginPath();
      ctx.moveTo(Math.round(x) + 0.5, RULER_H);
      ctx.lineTo(Math.round(x) + 0.5, h);
      ctx.stroke();
      ctx.fillStyle = pal.sub;
      ctx.fillText(fmtTick(t, step), x + 4, RULER_H / 2);
    }
    ctx.strokeStyle = pal.line2;
    ctx.beginPath();
    ctx.moveTo(0, RULER_H + 0.5);
    ctx.lineTo(w, RULER_H + 0.5);
    ctx.stroke();

    // Session block(s).
    let top = RULER_H + SECTION_GAP;
    top += drawBlock(dataOf('a'), top, 'a') + SECTION_GAP;
    if (st.cmp != null) {
      ctx.fillStyle = pal.sub;
      ctx.font = '700 10px -apple-system, BlinkMacSystemFont, sans-serif';
      ctx.fillText(`B: ${labelOf(st.cmp)}`, 8, top + 8);
      ctx.strokeStyle = pal.line2;
      ctx.beginPath();
      ctx.moveTo(0, top + 0.5 - SECTION_GAP / 2);
      ctx.lineTo(w, top + 0.5 - SECTION_GAP / 2);
      ctx.stroke();
      top += 16;
      drawBlock(dataOf('b'), top, 'b');
    }

    // Selection overlay.
    if (st.sel) {
      const x0 = Math.max(GUTTER, tToX(st.sel[0]));
      const x1 = Math.min(w, tToX(st.sel[1]));
      if (x1 > x0) {
        ctx.fillStyle = pal.focus;
        ctx.globalAlpha = 0.14;
        ctx.fillRect(x0, RULER_H, x1 - x0, h - RULER_H);
        ctx.globalAlpha = 1;
        ctx.strokeStyle = pal.focus;
        ctx.beginPath();
        ctx.moveTo(Math.round(x0) + 0.5, RULER_H);
        ctx.lineTo(Math.round(x0) + 0.5, h);
        ctx.moveTo(Math.round(x1) - 0.5, RULER_H);
        ctx.lineTo(Math.round(x1) - 0.5, h);
        ctx.stroke();
      }
    }

    // Gutter separator.
    ctx.strokeStyle = pal.line2;
    ctx.beginPath();
    ctx.moveTo(GUTTER - 0.5, 0);
    ctx.lineTo(GUTTER - 0.5, h);
    ctx.stroke();
  };

  // ---- header / stats / tabs / tables ----
  const renderSessionPickers = (): void => {
    const opts = sessions.map((s, i) => `<option value="${i}">${esc(`${i + 1}. ${labelOf(i)}${labels[i].platform ? ` (${labels[i].platform})` : ''} · ${fmtMs(s.data.t1)}`)}</option>`).join('');
    ($('pf-session') as HTMLSelectElement).innerHTML = opts;
    ($('pf-session') as HTMLSelectElement).value = String(st.s);
    ($('pf-compare') as HTMLSelectElement).innerHTML = `<option value="">off</option>${opts}`;
    ($('pf-compare') as HTMLSelectElement).value = st.cmp == null ? '' : String(st.cmp);
    const badge = $('pf-badge');
    const status = sessions[st.s].meta.status || '';
    badge.hidden = !status;
    badge.className = `badge ${esc(status)}`;
    badge.textContent = status;
  };

  const statChip = (k: string, v: string, warn = false, deltaHtml = ''): string => `<div class="stat${warn ? ' warn' : ''}"><span class="k">${esc(k)}</span><span class="v">${esc(v)}${deltaHtml}</span></div>`;
  const renderStats = (): void => {
    const a = dataOf('a');
    const chips: string[] = [];
    const deltaOf = (get: (d: PerfSessionData) => number, invert = false): string => {
      if (st.cmp == null) return '';
      const d = get(dataOf('b')) - get(a);
      if (Math.abs(d) < 1) return '';
      const worse = invert ? d < 0 : d > 0;
      return ` <span class="delta ${worse ? 'up' : 'down'}">${esc(fmtDelta(d))}</span>`;
    };
    chips.push(statChip('Wall', fmtMs(a.t1), false, deltaOf((d) => d.t1)));
    chips.push(statChip('Covered', `${fmtMs(a.covered)} (${a.t1 ? Math.round((100 * a.covered) / a.t1) : 0}%)`, false, deltaOf((d) => d.covered)));
    chips.push(statChip('Idle gaps', fmtMs(a.gapTotal), false, deltaOf((d) => d.gapTotal)));
    chips.push(statChip('Timeout tax', fmtMs(a.taxFullBurn), a.taxFullBurn > 5000, deltaOf((d) => d.taxFullBurn)));
    if (a.trailheadMs > 0 || (st.cmp != null && dataOf('b').trailheadMs > 0)) chips.push(statChip('Trailhead', fmtMs(a.trailheadMs), false, deltaOf((d) => d.trailheadMs)));
    chips.push(statChip('LLM', a.llmCount ? `${a.llmCount} calls · ${fmtMs(a.llmTotalMs)}${a.llmCostUsd != null ? ` · $${a.llmCostUsd.toFixed(2)}` : ''}` : 'none', false, deltaOf((d) => d.llmTotalMs)));
    chips.push(statChip('Steps', String(a.steps.length)));
    if (a.selfHealed) chips.push(statChip('Self-heal', 'invoked', true));
    if (st.cmp != null) chips.push(statChip('Compare', `B = ${labelOf(st.cmp)}`));
    $('pf-stats').innerHTML = chips.join('');
  };

  const range = (): [number, number] => st.sel || [0, domainEnd()];

  const renderTabs = (): void => {
    const tabs: Array<[string, string]> = st.cmp != null ? [['diff', 'A/B Diff'], ...TABS] : TABS;
    const active = validTab();
    const [lo, hi] = range();
    $('pf-tabs').innerHTML = tabs.map(([id, label]) => `<button class="perf-tab${id === active ? ' active' : ''}" data-tab="${id}">${label}</button>`).join('')
      + `<span class="perf-range-note">${st.sel ? `Selection ${esc(fmtMs(lo))} to ${esc(fmtMs(hi))} (${esc(fmtMs(hi - lo))}) <button class="clear" id="pf-clear-sel">clear</button>` : 'Whole session'}</span>`;
    $('pf-tabs').querySelectorAll('.perf-tab').forEach((el) => el.addEventListener('click', () => { st.tab = (el as HTMLElement).dataset.tab as string; renderDetail(); writeUrl(); }));
    const clear = document.getElementById('pf-clear-sel');
    if (clear) clear.addEventListener('click', () => setSel(null));
  };

  const pct = (part: number, whole: number): string => (whole > 0 ? `${((100 * part) / whole).toFixed(1)}%` : '');
  const rowsHtml = (inner: string, head: string): string => `<table class="perf"><thead><tr>${head}</tr></thead><tbody>${inner}</tbody></table>`;

  const renderBottomUp = (): string => {
    const [lo, hi] = range();
    const data = dataOf('a');
    // Driver spans are deliberately excluded (here and in diffRows): they're stamped on the
    // DEVICE clock, so clipping them against a host-clock range is wrong by the skew, and their
    // wall-clock overlaps the host-side tool spans already accounted in the tree — including
    // them would double-count time and distort the % column.
    const rows = bottomUpAggregate(data.spans.filter((sp) => sp.kind !== 'driver'), lo, hi);
    const total = rows.reduce((sum, r) => sum + r.self, 0);
    if (!rows.length) return `<div class="perf-empty">Nothing ran in this range.</div>`;
    const max = rows[0].self || 1;
    return rowsHtml(rows.map((r) => `
      <tr data-name="${esc(r.name)}" data-kind="${esc(r.kind)}">
        <td class="num">${esc(fmtMs(r.self))}<span class="bar"><i style="width:${Math.round((100 * r.self) / max)}%"></i></span></td>
        <td class="num">${pct(r.self, total)}</td>
        <td class="num">${r.count}</td>
        <td class="num">${esc(fmtMs(r.maxSelf))}</td>
        <td><span class="kindchip ${esc(r.kind)}">${esc(r.kind)}</span></td>
        <td>${esc(r.name)}</td>
      </tr>`).join(''), `<th class="num">Self</th><th class="num">%</th><th class="num">Count</th><th class="num">Max</th><th>Kind</th><th>Name</th>`);
  };

  const renderTree = (): string => {
    const [lo, hi] = range();
    const data = dataOf('a');
    const out: string[] = [];
    const walk = (id: number, depth: number): void => {
      const sp = data.spans[id];
      const total = clipLen(sp.effS, sp.effE, lo, hi);
      if (total <= 0) return;
      const self = rangeSelf(sp, lo, hi);
      const key = `a:${id}`;
      const open = st.expanded.has(key);
      const kids = sp.kids.filter((k) => clipLen(data.spans[k].effS, data.spans[k].effE, lo, hi) > 0);
      out.push(`
        <tr data-span="${id}" class="${st.focus && st.focus.side === 'a' && st.focus.id === id ? 'sel' : ''}">
          <td class="num">${esc(fmtMs(total))}</td>
          <td class="num">${esc(fmtMs(self))}</td>
          <td class="num">${pct(total, hi - lo)}</td>
          <td style="padding-left:${10 + depth * 18}px"><span class="tree-name">${kids.length ? `<button class="tree-caret" data-toggle="${id}">${open ? '▾' : '▸'}</button>` : '<span class="tree-caret"></span>'}<span class="kindchip ${esc(sp.kind)}">${esc(sp.kind)}</span> ${sp.ok ? '' : '<span class="fail-txt">✗</span> '}${esc(sp.name)}<span style="color:var(--sub)"> ${esc(sp.detail ? `· ${sp.detail}` : '')}</span></span></td>
        </tr>`);
      if (open) {
        const ordered = [...kids].sort((x, y) => clipLen(data.spans[y].effS, data.spans[y].effE, lo, hi) - clipLen(data.spans[x].effS, data.spans[x].effE, lo, hi));
        for (const k of ordered) walk(k, depth + 1);
      }
    };
    const roots = [...data.roots].sort((x, y) => clipLen(data.spans[y].effS, data.spans[y].effE, lo, hi) - clipLen(data.spans[x].effS, data.spans[x].effE, lo, hi));
    for (const id of roots) walk(id, 0);
    if (!out.length) return `<div class="perf-empty">Nothing ran in this range.</div>`;
    return rowsHtml(out.join(''), `<th class="num">Total</th><th class="num">Self</th><th class="num">% range</th><th>Span</th>`);
  };

  // Unlike the other tabs, Timeout Tax values are per-invocation, not range-clipped: a budget
  // verdict ("burned 10.3s of a 10s budget") is a fact of the whole call, and clipping "spent"
  // to the selection would claim the tool never burned its budget. The selection only filters
  // which invocations appear; a note says so whenever a range is active.
  const renderTax = (): string => {
    const [lo, hi] = range();
    const data = dataOf('a');
    const rows = data.tax.filter((t) => clipLen(data.spans[t.spanId].s, data.spans[t.spanId].e, lo, hi) > 0);
    if (!rows.length) return `<div class="perf-empty">No tool in this range declared a timeout budget.</div>`;
    const sorted = [...rows].sort((x, y) => Number(y.full) - Number(x.full) || y.spent - x.spent);
    const note = st.sel ? `<div class="perf-tabnote">Budgeted tools intersecting the selection; Spent and Budget are whole-invocation totals, not clipped to the range.</div>` : '';
    return note + rowsHtml(sorted.map((t) => `
      <tr data-span="${t.spanId}">
        <td class="num">${esc(fmtMs(t.spent))}<span class="bar${t.full ? ' full' : ''}"><i style="width:${Math.min(100, Math.round((100 * t.spent) / t.budget))}%"></i></span></td>
        <td class="num">${esc(fmtMs(t.budget))}</td>
        <td>${t.full ? '<span class="fail-txt">full burn</span>' : ''}</td>
        <td>${t.ok ? '<span class="pass-txt">ok</span>' : '<span class="fail-txt">failed</span>'}</td>
        <td>${esc(t.name)}<span style="color:var(--sub)"> ${esc(t.detail ? `· ${t.detail}` : '')}</span></td>
      </tr>`).join(''), `<th class="num">Spent</th><th class="num">Budget</th><th>Burn</th><th>Result</th><th>Tool</th>`);
  };

  const renderGaps = (): string => {
    const [lo, hi] = range();
    const data = dataOf('a');
    // Like every other range-scoped table, a gap only counts the part inside the selection —
    // a 10s gap grazed by a 1s selection reads as its overlap, not 10s. Zoom targets keep the
    // full gap so "zoom to row" still frames it whole.
    const rows = data.gaps
      .map((g) => ({ ...g, clipped: clipLen(g.s, g.e, lo, hi), at: Math.max(g.s, lo) }))
      .filter((g) => g.clipped > 0);
    if (!rows.length) return `<div class="perf-empty">No idle gaps in this range.</div>`;
    return rowsHtml(rows.sort((x, y) => y.clipped - x.clipped).map((g) => `
      <tr data-gap-s="${g.s}" data-gap-e="${g.e}">
        <td class="num">${esc(fmtMs(g.clipped))}</td>
        <td class="num">${esc(fmtMs(g.at))}</td>
        <td>${esc(g.before || '(session start)')}</td>
        <td>${esc(g.after || '(session end)')}</td>
      </tr>`).join(''), `<th class="num">Idle</th><th class="num">At</th><th>After</th><th>Before</th>`);
  };

  const renderDiff = (): string => {
    const [lo, hi] = range();
    const rows = diffRows(dataOf('a'), dataOf('b'), lo, hi);
    if (!rows.length) return `<div class="perf-empty">Nothing ran in this range in either session.</div>`;
    return rowsHtml(rows.map((r) => `
      <tr>
        <td class="num ${r.delta > 0 ? 'delta-pos' : r.delta < 0 ? 'delta-neg' : ''}">${esc(fmtDelta(r.delta))}</td>
        <td class="num">${esc(fmtMs(r.selfA))} <span style="color:var(--sub)">(${r.countA})</span></td>
        <td class="num">${esc(fmtMs(r.selfB))} <span style="color:var(--sub)">(${r.countB})</span></td>
        <td><span class="kindchip ${esc(r.kind)}">${esc(r.kind)}</span></td>
        <td>${esc(r.name)}</td>
      </tr>`).join(''), `<th class="num">Delta (B-A)</th><th class="num">A self</th><th class="num">B self</th><th>Kind</th><th>Name</th>`);
  };

  const renderDetail = (): void => {
    renderTabs();
    const body = $('pf-body');
    const tab = validTab();
    body.innerHTML = tab === 'diff' ? renderDiff() : tab === 'tree' ? renderTree() : tab === 'tax' ? renderTax() : tab === 'gaps' ? renderGaps() : renderBottomUp();
    body.querySelectorAll('[data-toggle]').forEach((el) => el.addEventListener('click', (e) => {
      e.stopPropagation();
      const key = `a:${(el as HTMLElement).dataset.toggle}`;
      if (st.expanded.has(key)) st.expanded.delete(key); else st.expanded.add(key);
      renderDetail();
    }));
    body.querySelectorAll('tr[data-span]').forEach((el) => {
      const id = parseInt((el as HTMLElement).dataset.span as string, 10);
      el.addEventListener('click', () => setFocus({ side: 'a', id }));
      el.addEventListener('dblclick', () => { const sp = dataOf('a').spans[id]; zoomToRange(sp.s, sp.e); });
    });
    body.querySelectorAll('tr[data-gap-s]').forEach((el) => el.addEventListener('dblclick', () => {
      zoomToRange(parseFloat((el as HTMLElement).dataset.gapS as string), parseFloat((el as HTMLElement).dataset.gapE as string));
    }));
    body.querySelectorAll('tr[data-name]').forEach((el) => el.addEventListener('dblclick', () => {
      // Zoom to the heaviest in-range instance of this bottom-up row's tool. The row's number is
      // range-scoped self time, so the drill-down ranks by the same contribution measure
      // (bottomUpAggregate's: clipped self segments, raw clipped duration for driver spans)
      // rather than jumping to a global heaviest that may sit outside the selection.
      const name = (el as HTMLElement).dataset.name;
      const kind = (el as HTMLElement).dataset.kind;
      const [lo, hi] = range();
      const contrib = (sp: PerfSpan): number => (sp.kind === 'driver' ? clipLen(sp.s, sp.e, lo, hi) : rangeSelf(sp, lo, hi));
      const best = dataOf('a').spans.filter((sp) => sp.name === name && sp.kind === kind && contrib(sp) > 0).sort((x, y) => contrib(y) - contrib(x))[0];
      if (best) { setFocus({ side: 'a', id: best.id }); zoomToRange(best.s, best.e); }
    }));
  };

  const renderInspector = (): void => {
    const el = $('pf-inspector');
    if (!st.focus) { el.hidden = true; el.innerHTML = ''; schedule(); return; }
    const data = dataOf(st.focus.side);
    const sp = data.spans[st.focus.id];
    const step = sp.step != null ? data.steps[sp.step] : null;
    const dl: Array<[string, string]> = [
      ['Kind', sp.kind + (sp.kind === 'driver' ? ' (device clock)' : '')],
      ['Start', fmtMs(sp.s)],
      ['End', fmtMs(sp.e)],
      ['Duration', fmtMs(sp.dur)],
      ['Self', fmtMs(sp.self)],
    ];
    if (sp.budget != null) dl.push(['Timeout budget', `${fmtMs(sp.budget)} (${pct(sp.dur, sp.budget)} burned)`]);
    if (sp.cost != null) dl.push(['LLM cost', `$${sp.cost.toFixed(4)}`]);
    if (sp.tokens) dl.push(['Tokens', sp.tokens]);
    if (step) dl.push(['Step', `${(sp.step as number) + 1}. ${step.label}${step.trailhead ? ' (trailhead)' : ''}`]);
    if (sp.shot) dl.push(['Screenshot', sp.shot]);
    dl.push(['Result', sp.ok ? 'ok' : 'failed']);
    el.hidden = false;
    el.innerHTML = `
      <button class="close" title="Close (Esc)">×</button>
      <h3>${esc(sp.name)}</h3>
      <div style="color:var(--sub);font-size:var(--type-caption)">${esc(sp.detail || '')}</div>
      <dl>${dl.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${esc(v)}</dd>`).join('')}</dl>
      ${sp.err ? `<div class="insp-err">${esc(sp.err)}</div>` : ''}
      ${sp.args ? `<pre class="mono">${esc(sp.args)}</pre>` : ''}
      <button class="iconbtn" id="pf-insp-zoom" style="margin-top:10px">Zoom to span</button>`;
    (el.querySelector('.close') as HTMLElement).addEventListener('click', () => setFocus(null));
    ($('pf-insp-zoom') as HTMLElement).addEventListener('click', () => zoomToRange(sp.s, sp.e));
    schedule();
  };

  const setFocus = (focus: { side: 'a' | 'b'; id: number } | null): void => {
    st.focus = focus;
    renderInspector();
    renderDetail();
  };
  const setSel = (sel: [number, number] | null): void => {
    st.sel = sel;
    renderDetail();
    schedule();
    writeUrl();
  };
  const setSession = (i: number): void => {
    st.s = clampSession(i);
    if (st.cmp === st.s) st.cmp = null;
    st.sel = null;
    st.focus = null;
    st.expanded.clear();
    fit();
    renderAll();
    writeUrl();
  };
  const setCompare = (i: number | null): void => {
    const was = st.cmp;
    st.cmp = i == null || i === st.s ? null : clampSession(i);
    if (st.cmp != null && was == null) st.tab = 'diff'; // land on the diff, then switch freely
    if (st.cmp == null && st.tab === 'diff') st.tab = 'bottomup';
    st.focus = null;
    fit();
    renderAll();
    writeUrl();
  };

  // The raw session id (and device facts) live down here, out of the header - the parsed trail
  // name is the identity everywhere above.
  const renderSessionMeta = (): void => {
    const m = sessions[st.s].meta;
    const raw = String(m.title || m.trailId || '');
    const bits: string[] = [];
    if (raw && raw !== labelOf(st.s)) bits.push(`Session <code>${esc(raw)}</code>`);
    for (const fact of [m.device, m.deviceType, m.appVersion ? `app ${m.appVersion}` : '', m.ranAt]) {
      if (fact) bits.push(esc(fact));
    }
    $('pf-session-meta').innerHTML = bits.join(' · ');
  };

  const renderAll = (): void => {
    renderSessionPickers();
    renderStats();
    renderSessionMeta();
    renderDetail();
    renderInspector();
    schedule();
  };

  // ---- index (run summary) view - mirrors the interactive run report's landing page ----
  const outcomeOf = (s: PerfSessionPayload): string => {
    // Same mapping as the interactive report's index (isFail/isPass + the self-heal marker).
    const status = String(s.meta.status || '').toLowerCase();
    if (status === 'failed' || status === 'error') return 'failed';
    if (s.meta.selfHeal || s.data.selfHealed) return 'selfheal';
    return status === 'passed' || status === 'success' ? 'passed' : 'other';
  };
  const showView = (): void => {
    $('pf-index').hidden = st.view !== 'index';
    $('pf-detail').hidden = st.view !== 'detail';
  };
  const renderIndex = (): void => {
    const outcomes = sessions.map(outcomeOf);
    const count = (o: string): number => outcomes.filter((x) => x === o).length;
    const sectionLabel: Record<string, string> = { failed: 'Failed', selfheal: 'Self-healed', passed: 'Passed', other: 'Other' };
    const sections = ['failed', 'selfheal', 'passed', 'other'].map((o) => {
      const rows = sessions.map((s, i) => ({ s, i })).filter(({ i }) => outcomes[i] === o);
      if (!rows.length) return '';
      return `<section class="idxsection"><div class="idxsectionhead ${o}">${sectionLabel[o]} <span class="idxsectioncount">${rows.length}</span></div><div class="idx">${rows.map(({ s, i }) => `
        <div class="idxrow" data-session="${i}" role="button" tabindex="0" aria-label="Open profile for ${esc(labelOf(i))}">
          <span class="idxstatus" title="${esc(o === 'selfheal' ? 'self-healed' : o)}"><span class="idxstatusdot ${o}" aria-hidden="true"></span></span>
          <div class="idxmain"><div class="nm">${esc(labelOf(i))}${labels[i].platform ? `<span class="platchip">${esc(labels[i].platform)}</span>` : ''}</div></div>
          <div class="idxfacts">
            <div class="idxfact"><div class="k">Wall</div><div class="v">${esc(fmtMs(s.data.t1))}</div></div>
            <div class="idxfact"><div class="k">Steps</div><div class="v">${s.data.steps.length}</div></div>
          </div>
          <span class="arr" aria-hidden="true">→</span>
        </div>`).join('')}</div></section>`;
    }).join('');
    $('pf-index').innerHTML = `<div class="idxshell">
      <div class="idxhead"><h1>Performance Analysis</h1><span class="spacer"></span>${themeToggleHtml}</div>
      <div class="idxsummary">
        <span class="idxstat fail"><strong>${count('failed')}</strong> failed</span>
        <span class="idxstat selfheal"><strong>${count('selfheal')}</strong> self-healed</span>
        <span class="idxstat pass"><strong>${count('passed')}</strong> passed</span>
        ${count('other') ? `<span class="idxstat"><strong>${count('other')}</strong> other</span>` : ''}
      </div>
      ${sections}</div>`;
    $('pf-index').querySelectorAll('.idxrow').forEach((el) => {
      const open = (): void => openSession(parseInt((el as HTMLElement).dataset.session as string, 10));
      el.addEventListener('click', open);
      el.addEventListener('keydown', (e) => { const key = (e as KeyboardEvent).key; if (key === 'Enter' || key === ' ') { e.preventDefault(); open(); } });
    });
  };
  const openSession = (i: number): void => {
    st.s = clampSession(i);
    if (st.cmp === st.s) st.cmp = null;
    st.sel = null;
    st.focus = null;
    st.expanded.clear();
    st.view = 'detail';
    showView();
    fit();
    renderAll();
    writeUrl();
  };
  const goIndex = (): void => {
    st.view = 'index';
    showView();
    renderIndex();
    writeUrl();
  };

  // ---- timeline interaction ----
  const hitAt = (x: number, y: number): Hit | null => {
    for (let i = hits.length - 1; i >= 0; i--) {
      const r = hits[i];
      if (x >= r.x0 && x <= r.x1 && y >= r.y0 && y <= r.y1) return r;
    }
    return null;
  };
  const canvasPos = (e: MouseEvent): { x: number; y: number } => {
    const rect = canvas.getBoundingClientRect();
    return { x: e.clientX - rect.left, y: e.clientY - rect.top };
  };

  canvas.addEventListener('wheel', (e: WheelEvent) => {
    e.preventDefault();
    const { x } = canvasPos(e);
    const panX = e.shiftKey ? e.deltaY : e.deltaX;
    if (Math.abs(panX) > Math.abs(e.shiftKey ? 0 : e.deltaY)) {
      const msPerPx = (st.vE - st.vS) / Math.max(1, cssW() - GUTTER);
      st.vS += panX * msPerPx;
      st.vE += panX * msPerPx;
      clampView();
      schedule();
    } else {
      zoomAt(x, Math.exp(e.deltaY * 0.0022));
    }
  }, { passive: false });

  let drag: { x0: number; t0: number; moved: boolean } | null = null;
  canvas.addEventListener('mousedown', (e: MouseEvent) => {
    if (e.button !== 0) return;
    const { x } = canvasPos(e);
    if (x < GUTTER) return;
    drag = { x0: x, t0: xToT(x), moved: false };
  });
  window.addEventListener('mousemove', (e: MouseEvent) => {
    const { x, y } = canvasPos(e);
    if (drag) {
      if (Math.abs(x - drag.x0) > 3) drag.moved = true;
      if (drag.moved) {
        const t = xToT(Math.max(GUTTER, x));
        st.sel = t >= drag.t0 ? [drag.t0, t] : [t, drag.t0];
        renderDetail();
        schedule();
      }
      return;
    }
    // Hover tooltip.
    const hit = e.target === canvas ? hitAt(x, y) : null;
    const prev = st.hover;
    st.hover = hit && hit.id != null ? { side: hit.side, id: hit.id } : null;
    if ((prev && prev.id) !== (st.hover && st.hover.id)) schedule();
    if (hit && hit.id != null) {
      const sp = dataOf(hit.side).spans[hit.id];
      tooltip.style.display = 'block';
      tooltip.style.left = `${Math.min(e.clientX + 14, window.innerWidth - 300)}px`;
      tooltip.style.top = `${e.clientY + 14}px`;
      tooltip.innerHTML = `<div class="tt-name">${esc(sp.name)}</div><div class="tt-sub">${esc(fmtMs(sp.dur))} total · ${esc(fmtMs(sp.self))} self · at ${esc(fmtMs(sp.s))}${sp.ok ? '' : ' · <b>failed</b>'}</div>${sp.detail ? `<div class="tt-sub">${esc(sp.detail)}</div>` : ''}`;
    } else if (hit && hit.step != null) {
      const step = dataOf(hit.side).steps[hit.step];
      tooltip.style.display = 'block';
      tooltip.style.left = `${Math.min(e.clientX + 14, window.innerWidth - 300)}px`;
      tooltip.style.top = `${e.clientY + 14}px`;
      tooltip.innerHTML = `<div class="tt-name">${hit.step + 1}. ${esc(step.label)}</div><div class="tt-sub">${esc(fmtMs((step.e == null ? dataOf(hit.side).t1 : step.e) - step.s))}${step.trailhead ? ' · trailhead' : ''}${step.ok ? '' : ' · <b>failed</b>'} · click to select range</div>`;
    } else {
      tooltip.style.display = 'none';
    }
  });
  window.addEventListener('mouseup', (e: MouseEvent) => {
    if (!drag) return;
    const wasDrag = drag.moved;
    drag = null;
    if (wasDrag) {
      if (st.sel && st.sel[1] - st.sel[0] < 1) st.sel = null;
      setSel(st.sel);
      return;
    }
    const { x, y } = canvasPos(e);
    const hit = hitAt(x, y);
    if (hit && hit.id != null) setFocus({ side: hit.side, id: hit.id });
    else if (hit && hit.step != null) {
      const step = dataOf(hit.side).steps[hit.step];
      setSel([step.s, step.e == null ? dataOf(hit.side).t1 : step.e]);
    } else setFocus(null);
  });
  canvas.addEventListener('dblclick', (e: MouseEvent) => {
    const { x, y } = canvasPos(e);
    const hit = hitAt(x, y);
    if (hit && hit.id != null) {
      const sp = dataOf(hit.side).spans[hit.id];
      zoomToRange(sp.s, sp.e);
    } else if (hit && hit.step != null) {
      const step = dataOf(hit.side).steps[hit.step];
      zoomToRange(step.s, step.e == null ? dataOf(hit.side).t1 : step.e);
    }
  });
  canvas.addEventListener('mouseleave', () => { tooltip.style.display = 'none'; });

  window.addEventListener('keydown', (e: KeyboardEvent) => {
    if (st.view !== 'detail') return;
    if ((e.target as HTMLElement).tagName === 'SELECT' || (e.target as HTMLElement).tagName === 'INPUT') return;
    if (e.key === 'Escape') { if (st.sel) setSel(null); else setFocus(null); }
    else if (e.key === '+' || e.key === '=') zoomAt(cssW() / 2 + GUTTER / 2, 0.7);
    else if (e.key === '-') zoomAt(cssW() / 2 + GUTTER / 2, 1.4);
    else if (e.key === '0') fit();
  });

  // ---- chrome wiring ----
  ($('pf-session') as HTMLSelectElement).addEventListener('change', (e) => setSession(parseInt((e.target as HTMLSelectElement).value, 10)));
  ($('pf-compare') as HTMLSelectElement).addEventListener('change', (e) => {
    const v = (e.target as HTMLSelectElement).value;
    setCompare(v === '' ? null : parseInt(v, 10));
  });
  $('pf-fit').addEventListener('click', fit);
  const backBtn = document.getElementById('pf-back');
  if (backBtn) backBtn.addEventListener('click', goIndex);
  // Delegated so the toggle works in both the index header and the detail header (the index
  // re-renders its innerHTML; the icon swap itself is pure CSS on [data-theme]).
  document.addEventListener('click', (e: MouseEvent) => {
    const target = e.target as HTMLElement;
    if (!target.closest || !target.closest('[data-theme-toggle]')) return;
    const next = document.documentElement.dataset.theme === 'dark' ? 'light' : 'dark';
    document.documentElement.dataset.theme = next;
    try { localStorage.setItem('trailblaze-report-theme', next); } catch (_) {}
    readPalette();
    schedule();
  });

  const applyTimelineH = (): void => { timelineEl.style.height = `${st.timelineH}px`; schedule(); };
  $('pf-resizer').addEventListener('mousedown', (e: MouseEvent) => {
    e.preventDefault();
    const startY = e.clientY;
    const startH = st.timelineH;
    const move = (ev: MouseEvent): void => {
      st.timelineH = Math.max(120, Math.min(Math.round(window.innerHeight * 0.75), startH + ev.clientY - startY));
      applyTimelineH();
    };
    const up = (): void => { window.removeEventListener('mousemove', move); window.removeEventListener('mouseup', up); };
    window.addEventListener('mousemove', move);
    window.addEventListener('mouseup', up);
  });
  window.addEventListener('resize', () => { schedule(); });

  // ---- boot ----
  st.vS = 0;
  st.vE = domainEnd() || 1;
  applyTimelineH();
  showView();
  if (st.view === 'index') renderIndex();
  renderAll();
}

export { PERF_VIEWER, clipLen, rangeSelf, fmtMs, fmtDelta, niceTickStep, fmtTick, diffRows, sessionDisplay };
