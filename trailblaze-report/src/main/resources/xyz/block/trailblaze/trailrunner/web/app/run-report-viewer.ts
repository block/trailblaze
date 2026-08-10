// The standalone report viewer. run-report-viewer-boot.ts bundles this module (and everything it
// imports) into a self-executing classic script that buildMultiReportHtml embeds into every
// exported report, so the exported file runs offline anywhere — plain DOM only, no React, no
// external scripts. It reads its data from inert JSON scripts: the #tb-index boot chunk plus
// lazily-parsed per-session #tb-session-<i> chunks (with #tb-run-data and window.__TB_RUN_DATA__
// as monolithic fallbacks for older files and in-app embedders).
// Shared contract types come from the ambient run-report-types.d.ts (see its header for why it
// stays ambient rather than becoming module exports).
import { isLlmTurnRow, localRunAgentPrompt, traceStepCount, traceToolCallCount, yamlRootSection } from './run-report-extract';
import { eventPrettyText, eventValueText, inflateEventsGz, inflateGzJsonArray, inflateGzText, normalizeEventPayload, parseEventJsonish, rawPrettyText, rekeySprites, tbBootLoaderHtml, toInertJson } from './run-report-payload';
import { buildPlaybackSchedule, playbackGapMs, playbackPositionAt, spriteFrameCss, videoEndMs, videoFrameAt, videoLoopFrame } from './run-report-playback';

// Run `fn` once the document has finished streaming (immediately when it already has). A chunked
// report's UI is interactive while the document tail — later sessions' #tb-session-<i> /
// #tb-sprites-<i> chunks — is still arriving, so work that snapshots the whole document (export)
// must wait for readyState 'complete': by then every chunk that will ever exist is in the DOM.
// One pending slot, latest call wins: re-invoking while armed replaces the deferred work rather
// than queueing a second snapshot.
let pendingWhenComplete: (() => void) | null = null;
export function whenDocumentComplete(fn: () => void): void {
  if (String(document.readyState || 'complete') === 'complete') { fn(); return; }
  const armed = pendingWhenComplete != null;
  pendingWhenComplete = fn;
  if (armed) return;
  const poll = () => {
    if (String(document.readyState || 'complete') !== 'complete') { setTimeout(poll, 50); return; }
    const run = pendingWhenComplete;
    pendingWhenComplete = null;
    if (run) run();
  };
  setTimeout(poll, 50);
}

export function RUN_REPORT_VIEWER(booted?: boolean): void {
  // First paint must be the static #tb-boot loader, not a frozen blank page: on a multi-megabyte
  // report, JSON.parsing the payload and building the first render are heavy main-thread work.
  // When the document carries the loader (the standalone export), yield to the compositor first —
  // a double rAF guarantees a frame with the loader on screen — and boot in the second callback.
  // Raced against a timeout because browsers throttle rAF to zero in hidden/occluded tabs: without
  // it a report opened in a background tab would sit unbooted until fronted (breaking anything
  // reading its DOM headlessly). Environments without the loader or without rAF (in-app reuse,
  // tests) boot synchronously.
  if (!booted && document.getElementById('tb-boot') && typeof requestAnimationFrame === 'function') {
    let bootStarted = false;
    let bootTimer: ReturnType<typeof setTimeout> | undefined;
    // Whichever arm wins clears the timeout so no stale timer outlives the race (the losing rAF
    // arm can't be cancelled from here and is neutralized by the bootStarted guard instead).
    const boot = () => { if (!bootStarted) { bootStarted = true; clearTimeout(bootTimer); RUN_REPORT_VIEWER(true); } };
    requestAnimationFrame(() => requestAnimationFrame(boot));
    bootTimer = setTimeout(boot, 300);
    return;
  }
  // Parse one inert application/json script's textContent; null when the element is absent or its
  // JSON is malformed, so callers can fall back.
  const readJsonScript = (id: string) => {
    const el = document.getElementById(id);
    if (!el) return null;
    try { return JSON.parse(el.textContent || ''); } catch (_) { return null; }
  };
  // The payload ships as inert JSON scripts so the JS parser never sees megabytes of data on the
  // boot path. Chunked documents (buildMultiReportHtml) split it: #tb-index (per-session meta +
  // per-call LLM token/cost summaries + trace-derived counts — everything the run list renders) arrives BEFORE
  // this script, and each session's heavy remainder rides in its own #tb-session-<i> chunk AFTER
  // it, JSON.parsed only when that run opens (hydrateSession). Older layouts keep working: a
  // monolithic #tb-run-data document and the window.__TB_RUN_DATA__ fallback (for embedders that
  // inject the payload directly) both boot fully hydrated. textContent → JSON.parse is not an
  // HTML sink (nothing is reinterpreted as markup); render-time escaping still covers every
  // user-supplied field.
  const INDEX_PAYLOAD: Partial<ReportPayload> | null = readJsonScript('tb-index');
  const RAW: Partial<ReportPayload> = INDEX_PAYLOAD || readJsonScript('tb-run-data') || window.__TB_RUN_DATA__ || {};
  const root = document.getElementById('app') as HTMLElement;
  const esc = (s: unknown) => String(s == null ? '' : s).replace(/[<>&"]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' }[c]));
  const safeHref = (value: unknown) => {
    try { const url = new URL(String(value || '')); return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null; }
    catch (e) { return null; }
  };

  // Normalize to a sessions[] array. Chunked documents list index stubs (hydrated on open);
  // monolithic payloads embed full sessions; tolerate the older single-run shape
  // ({ meta, trace, llm, shots }) so previously-exported files still open.
  const SESSIONS: SessionPayload[] = (RAW.sessions && RAW.sessions.length)
    ? RAW.sessions.map((s) => INDEX_PAYLOAD ? { trace: [], llm: [], shots: {}, recordingYaml: null, originalYaml: null, ...s } : s)
    : [{ meta: RAW.meta || {}, trace: RAW.trace || [], llm: RAW.llm || [], shots: RAW.shots || {}, recordingYaml: (RAW.meta && RAW.meta.recordingYaml) || null, originalYaml: (RAW.meta && RAW.meta.originalYaml) || null }];
  const MULTI = SESSIONS.length > 1;
  // Sessions still awaiting their #tb-session-<i> chunk. Hydration assigns the chunk's fields
  // INTO the existing stub (object identity preserved — the inflater caches and `D` hold object
  // references) and removes the entry. Empty for monolithic payloads (everything starts hydrated).
  const unhydrated = new Set<number>(INDEX_PAYLOAD && RAW.sessions && RAW.sessions.length ? SESSIONS.map((_, i) => i) : []);
  // Parse a session's chunk into its stub. Returns true once the session is usable: synchronously
  // when the chunk is already in the DOM (the common case), or — document fully loaded but the
  // chunk genuinely absent/malformed — by giving up on hydration so the run opens with what the
  // index carries instead of hanging.
  const hydrateSession = (i: number): boolean => {
    if (!unhydrated.has(i)) return true;
    const docComplete = String(document.readyState || 'complete') === 'complete';
    const full = readJsonScript(`tb-session-${i}`);
    if (full) {
      // Blanked sprite URIs mean this session's frames ride in the #tb-sprites-<i> chunk directly
      // after this one (see buildMultiReportHtml) — usually the bulk of the session's bytes, so on
      // a streaming document it can lag well behind. The video pane resolves each frame's URL only
      // at render, so hydrating early would paint blank frames that nothing ever re-renders. Hold
      // until the sprites chunk parses (primeSpriteChunk caches it, so the render won't re-parse);
      // a completed document without one is the truncated-download case — open degraded, as below.
      const awaitingSprites = !docComplete && full.video && full.video.sprites.length
        && full.video.sprites.every((sp) => !sp.uri) && !primeSpriteChunk(i);
      if (awaitingSprites) return false;
      Object.assign(SESSIONS[i], full); unhydrated.delete(i); return true;
    }
    if (docComplete) { unhydrated.delete(i); return true; }
    return false;
  };
  // Await a chunk that hasn't streamed in yet (the run was opened while the document tail is
  // still downloading). Cheap 50ms poll — it only ever runs during that streaming window, which
  // hydrateSession's readyState check bounds.
  const awaitSessionChunk = (i: number): Promise<void> => new Promise((resolve) => {
    const poll = () => { if (hydrateSession(i)) resolve(); else setTimeout(poll, 50); };
    poll();
  });
  // Sprite sheets are hoisted out of the boot payload into inert JSON chunks (see
  // buildMultiReportHtml): one #tb-sprites-<i> per session (one URI array in sheet order), so boot
  // never parses their bytes. Resolved lazily — a chunk is only JSON.parsed on the first frame
  // render that needs it — and cached (misses are NOT cached: the chunk may still be streaming
  // in). Older exports carry a single #tb-sprites map keyed by session index; payloads that still
  // carry video.sprites URIs inline (in-app embedders) short-circuit before any store is touched.
  let spriteStoreCache: Record<string, string[]> | null = null;
  const spriteStore = () => spriteStoreCache || (spriteStoreCache = readJsonScript('tb-sprites') || {});
  const spriteChunkCache: Record<string, string[]> = {};
  // Parse-and-cache a session's sprite chunk once it has streamed in. hydrateSession (above)
  // holds a hoisted-sprites session on this, so the detail render never sees frames whose chunk
  // hasn't arrived.
  const primeSpriteChunk = (i: number): boolean => {
    const key = String(i);
    if (spriteChunkCache[key]) return true;
    const chunk = readJsonScript(`tb-sprites-${key}`);
    if (chunk) spriteChunkCache[key] = chunk;
    return Boolean(chunk);
  };
  const spriteUrls = (v: VideoInfo | null | undefined, sessionIndex?: number): string[] => {
    if (v && v.sprites.some((sp) => sp.uri)) return v.sprites.map((sp) => sp.uri);
    const key = String(sessionIndex == null ? st.session : sessionIndex);
    if (spriteChunkCache[key]) return spriteChunkCache[key];
    const chunk = readJsonScript(`tb-sprites-${key}`);
    if (chunk) { spriteChunkCache[key] = chunk; return chunk; }
    return spriteStore()[key] || [];
  };
  const spriteUrl = (v: VideoInfo | null | undefined, sheet: number, sessionIndex?: number) => spriteUrls(v, sessionIndex)[sheet] || '';
  const generatedAt = RAW.generatedAt || (SESSIONS[0] && SESSIONS[0].meta && SESSIONS[0].meta.generatedAt) || '';
  const themeKey = 'trailblaze-report-theme';
  const currentTheme = () => document.documentElement?.dataset?.theme === 'light' ? 'light' : 'dark';
  const renderThemeToggle = () => {
    const theme = currentTheme();
    const next = theme === 'dark' ? 'light' : 'dark';
    return `<button class="themetoggle" type="button" data-theme-toggle aria-label="Use ${next} mode" title="Use ${next} mode"><svg class="themeicon sun" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3.6" fill="none" stroke="currentColor" stroke-width="3.2"/><path d="M12 2.5v2M12 19.5v2M5.28 5.28l1.42 1.42M17.3 17.3l1.42 1.42M2.5 12h2M19.5 12h2M5.28 18.72l1.42-1.42M17.3 6.7l1.42-1.42" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/></svg><svg class="themeicon moon" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 15.1A8 8 0 0 1 8.9 4.5a8 8 0 1 0 10.6 10.6Z" fill="none" stroke="currentColor" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round"/></svg></button>`;
  };
  const setTheme = (theme, persist = true) => {
    document.documentElement.dataset.theme = theme;
    if (persist) { try { localStorage.setItem(themeKey, theme); } catch (e) {} }
    root.querySelectorAll<HTMLElement>('[data-theme-toggle]').forEach((button) => {
      const next = theme === 'dark' ? 'light' : 'dark';
      button.setAttribute('aria-label', `Use ${next} mode`);
      button.setAttribute('title', `Use ${next} mode`);
    });
  };
  if (typeof matchMedia === 'function') {
    const media = matchMedia('(prefers-color-scheme: light)');
    const followSystem = (event) => { try { if (!localStorage.getItem(themeKey)) setTheme(event.matches ? 'light' : 'dark', false); } catch (e) {} };
    if (media.addEventListener) media.addEventListener('change', followSystem);
  }

  // Rebuild this self-contained document around either the full payload or one selected session.
  // No server is needed: screenshots, logs, event streams, and viewer code are already embedded.
  const downloadBlob = (parts, type, filename) => {
    const blob = new Blob(parts, { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = filename; a.style.display = 'none';
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 0);
  };
  // Deferred through whenDocumentComplete: exporting while the document tail is still streaming
  // would clone a DOM missing later #tb-session-<i>/#tb-sprites-<i> chunks — a truncated file.
  // The exported runs travel as the `sessions` array (captured at click), so a deferred export
  // can't follow the user's later navigation to another run.
  const exportReport = (sessions, filename, title) => whenDocumentComplete(() => {
    const clone = document.documentElement.cloneNode(true) as HTMLElement;
    // Re-seed the static boot loader (the live document's first render replaced it) so the
    // exported file also paints a loader instead of a blank page while it boots.
    const heading = sessions.length === 1 ? (sessions[0].meta.title || 'Trailblaze run') : 'Trailblaze Report';
    const app = clone.querySelector('#app');
    if (app) app.innerHTML = tbBootLoaderHtml(heading);
    const titleEl = clone.querySelector('title'); if (titleEl) titleEl.textContent = title;
    const index = clone.querySelector('#tb-index');
    if (index) {
      // Chunked layout. A FULL export ships the clone as-is: every #tb-session-<i> /
      // #tb-sprites-<i> chunk (and the canonical share URL in #tb-index) is already in place. A
      // single-run export renumbers instead: the exported run becomes run 0, so its chunks are
      // re-id'd, every other session's chunks are dropped, and the index is rewritten to just its
      // entry — shareUrl dropped, since a grafted deep link would point at a different run in the
      // hosted original. Session identity is stable across hydration (chunks Object.assign into
      // the boot stubs), so indexOf recovers the exported run's index.
      if (sessions.length !== SESSIONS.length) {
        const exportSession = SESSIONS.indexOf(sessions[0]);
        const entries = (readJsonScript('tb-index') || {}).sessions || [];
        index.textContent = toInertJson({ generatedAt, sessions: [entries[exportSession] || { meta: sessions[0].meta, llm: sessions[0].llm }] });
        clone.querySelectorAll('[id^="tb-session-"], [id^="tb-sprites-"]').forEach((el) => {
          if (el.id === `tb-session-${exportSession}`) el.id = 'tb-session-0';
          else if (el.id === `tb-sprites-${exportSession}`) el.id = 'tb-sprites-0';
          else el.remove();
        });
      }
      downloadBlob(['<!doctype html>\n' + clone.outerHTML], 'text/html;charset=utf-8', filename);
      return;
    }
    const data = clone.querySelector('#tb-run-data');
    if (!data) return;
    // Legacy monolithic layout (older exported files, in-app embed re-exports). The canonical
    // share URL only survives a FULL export: a single-run export out of a multi-run report
    // renumbers sessions (the exported run becomes run=0), so a grafted deep link would point at
    // a different run in the hosted original.
    data.textContent = toInertJson({ generatedAt, ...(SHARE_URL && sessions.length === SESSIONS.length ? { shareUrl: SHARE_URL } : {}), sessions });
    // Re-key the hoisted sprite chunk for the exported subset (session indices shift when a single
    // run is exported out of a multi-run report).
    const spriteData = clone.querySelector('#tb-sprites');
    if (spriteData) spriteData.textContent = toInertJson(rekeySprites(sessions, SESSIONS, spriteUrls));
    downloadBlob(['<!doctype html>\n' + clone.outerHTML], 'text/html;charset=utf-8', filename);
  });
  const fileSlug = (value) => String(value || 'run').toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '').slice(0, 60) || 'run';
  const screenshotEntries = (session) => (session.trace || []).filter((step) => step.screenshotFile && /^data:image\//.test(String((session.shots || {})[step.screenshotFile] || '')))
    .map((step, index) => [`${index + 1}. ${step.label || step.screenshotFile}`, session.shots[step.screenshotFile]]);
  const exportScreenshots = (session) => {
    const screenshots = screenshotEntries(session);
    if (!screenshots.length) return;
    const title = `${session.meta.title || 'Trailblaze run'} screenshots`;
    const cells = screenshots.map(([name, src]) => `<figure><img src="${esc(src)}" alt="${esc(name)}"><figcaption>${esc(name)}</figcaption></figure>`).join('');
    const html = `<!doctype html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${esc(title)}</title><style>body{margin:0;padding:24px;background:#0b0e11;color:#f4f5f7;font:14px/1.4 -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif}h1{font-size:20px}.gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(220px,1fr));gap:18px}figure{margin:0;padding:12px;border:1px solid #2a3038;border-radius:10px;background:#14181d}img{display:block;width:100%;height:auto;border-radius:6px;background:#000}figcaption{margin-top:8px;color:#a8b0bc;font-size:12px;word-break:break-word}</style></head><body><h1>${esc(title)}</h1><div class="gallery">${cells}</div></body></html>`;
    downloadBlob([html], 'text/html;charset=utf-8', `trailblaze_run_${fileSlug(session.meta.title)}_screenshots.html`);
  };
  // Payloads past the driver's inline thresholds arrive gzipped (eventsGz, deviceLogGz/networkGz -
  // see packGz in run-report-cli.ts) and inflate lazily through one shared lifecycle: a cache Map
  // plus an inflight Map of promises, keyed by session and living OUTSIDE the session object so
  // exportReport re-embeds the compact form. Inflation kicks off when the session opens and
  // re-renders on completion; a failed inflate caches null so the tab renders a note instead of
  // retrying or breaking. ensure() always returns a promise so exports can await complete data.
  const makeInflater = (needsInflate, inflate) => {
    const cache = new Map();
    const inflight = new Map();
    const ensure = (session) => {
      if (!needsInflate(session) || cache.has(session)) return Promise.resolve();
      if (inflight.has(session)) return inflight.get(session);
      const done = inflate(session).then((value) => {
        inflight.delete(session);
        cache.set(session, value);
        if (st.view === 'detail' && D === session) render();
      });
      inflight.set(session, done);
      return done;
    };
    return { cache, inflight, ensure };
  };
  const eventsInflater = makeInflater(
    (session) => session.eventsGz && !session.events,
    (session) => inflateEventsGz(session.eventsGz),
  );
  const ensureEventsInflated = eventsInflater.ensure;
  const sessionEvents = (session) => session.events || eventsInflater.cache.get(session) || null;
  // The session has events to show: inflated (or inline) streams, or a compressed payload that
  // will inflate once the session opens.
  const hasEvents = (session) => Boolean((sessionEvents(session) || []).length || session.eventsGz);
  const logsInflater = makeInflater(
    (session) => session.deviceLogGz || session.networkGz,
    async (session) => {
      const [deviceLog, network] = await Promise.all([
        session.deviceLogGz ? inflateGzText(session.deviceLogGz) : null,
        session.networkGz ? inflateGzJsonArray(session.networkGz) : null,
      ]);
      return { deviceLog, network };
    },
  );
  const ensureLogsInflated = logsInflater.ensure;
  const sessionDeviceLog = (session) => session.deviceLog || (logsInflater.cache.get(session) || {}).deviceLog || null;
  const sessionNetwork = (session) => session.network || (logsInflater.cache.get(session) || {}).network || null;

  const logPayload = (session) => ({
    run: session.meta || {},
    deviceLog: sessionDeviceLog(session),
    network: sessionNetwork(session) || [],
    events: sessionEvents(session) || [],
    llm: session.llm || [],
  });
  const hasLogs = (session) =>
    Boolean(sessionDeviceLog(session) || session.deviceLogGz || (session.network && session.network.length) || session.networkGz || hasEvents(session) || (session.llm && session.llm.length));
  const exportLogs = async (session) => {
    if (!hasLogs(session)) return;
    // Compressed payloads export inflated, never as opaque base64 - wait out any in-flight
    // inflation (logs AND events) so the download can't race it and export empty fields.
    await Promise.all([ensureLogsInflated(session), ensureEventsInflated(session)]);
    downloadBlob([JSON.stringify(logPayload(session), null, 2)], 'application/json;charset=utf-8', `trailblaze_run_${fileSlug(session.meta.title)}_logs.json`);
  };

  // `D` is the session currently in view; every renderer reads D.trace / D.llm / D.shots / D.meta /
  // D.recordingYaml, so the single-run renderers below are unchanged across a session switch.
  let D: SessionPayload = SESSIONS[0];
  const st = { view: MULTI ? 'index' : 'detail', session: 0, tab: 'timeline', step: 0, llmSel: 0, tlStreams: [], tlMenuOpen: false, trailheadOpen: true, trailOpen: true, lightboxAll: false, lightboxZoom: 1, runSort: 'grouped', idxOpen: [], playing: false, vSpeed: 1, pageTransition: '' };
  // Timeline playback stop handle (the active rAF engine run's stop function). Declared up here
  // (before openSession, which stops it) so the init-time openSession() call for a single-session
  // report doesn't hit a temporal-dead-zone ref.
  let timelinePlaybackStop = null;
  const stopTimeline = () => { st.playing = false; if (!timelinePlaybackStop) return; const stop = timelinePlaybackStop; timelinePlaybackStop = null; stop(); };
  // Per-frame aspect ratio of the current session's video sprite (`w / h`). Newer payloads record
  // frameWidth alongside frameHeight, so the aspect is known before anything renders
  // (spriteAspectFromMeta, called when a session opens). Older payloads without frameWidth fall
  // back to a one-shot decode measurement (measureSpriteAspect) applied after first paint.
  let spriteAspect = null;
  const spriteAspectFromMeta = (v) => {
    if (spriteAspect != null || !v) return;
    const fw = Number(v.frameWidth);
    if (Number.isFinite(fw) && fw > 0 && v.frameHeight > 0) spriteAspect = `${fw} / ${v.frameHeight}`;
  };

  // Anchor a failed run to its actionable row. The failure belongs to the first FAILED objective's
  // step (the Complete bookend marks that row): within it the first failed tool row wins, and a
  // step with no failed tool row anchors on the objective itself. Tolerated tool failures inside a
  // step that ultimately passed (retry polling, a trailhead's internal recovery loops) never anchor
  // the failure. Only when no objective recorded a failure (a run-level error, or a crash without a
  // Complete bookend) does the first failed row in the trace anchor it.
  const failureAnchorIndex = () => {
    const objIdx = D.trace.findIndex((t) => t.objective && !t.ok);
    if (objIdx < 0) {
      const toolIdx = D.trace.findIndex((t) => !t.objective && !t.ok);
      return toolIdx >= 0 ? toolIdx : D.trace.findIndex((t) => !t.ok);
    }
    for (let k = objIdx + 1; k < D.trace.length && !D.trace[k].objective; k++) {
      if (!D.trace[k].ok) return k;
    }
    return objIdx;
  };

  // A route into a not-yet-hydrated session, parked until the chunk lands: the step/llm bounds
  // checks in applyDetailRoute need the real trace, so seedSessionDetail re-applies it.
  let pendingDetailRoute = null;
  // Seed the detail view of the (hydrated) session in D. Failed runs lead with the actionable
  // tool; passing runs start at the authored trail so any recovery summary remains the first
  // thing visible above it. Incidental failed polling rows (a passing run, or a passing step of a
  // failed run) are intentionally ignored.
  const seedSessionDetail = () => {
    spriteAspectFromMeta(D.video);
    ensureEventsInflated(D);
    ensureLogsInflated(D);
    const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
    const firstFail = runFailed ? failureAnchorIndex() : -1;
    st.step = firstFail >= 0 ? D.trace[firstFail].i : ((D.trace[0] && D.trace[0].i) || 0);
    const trailheadStart = D.trace.findIndex((t) => t.objective && t.trailhead);
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    const trailheadActions = trailheadStart >= 0 ? (trailStart >= 0 ? trailStart : D.trace.length) - trailheadStart - 1 : 0;
    const failureIsInTrailhead = firstFail >= 0 && trailheadStart >= 0 && (trailStart < 0 || firstFail < trailStart);
    // Setup is supporting context. Keep small setup visible, but collapse high-volume setup so the
    // authored Trail remains the dominant content. A setup failure overrides that default.
    st.trailheadOpen = trailStart < 0 || failureIsInTrailhead || trailheadActions <= 12;
    if (firstFail < 0 && !st.trailheadOpen && trailStart >= 0) st.step = D.trace[trailStart].i;
    if (pendingDetailRoute) { const r = pendingDetailRoute; pendingDetailRoute = null; applyDetailRoute(r); }
  };
  // Open a session's detail view.
  const openSession = (i) => {
    // st.lightboxZoom deliberately survives this reset: thumbnail size is a cross-run viewing
    // preference, unlike the per-session lightboxAll expansion.
    stopTimeline(); spriteAspect = null; pendingDetailRoute = null; st.session = i; D = SESSIONS[i]; st.view = 'detail'; st.tab = 'timeline'; st.step = 0; st.llmSel = 0; st.tlStreams = []; st.tlMenuOpen = false; st.trailOpen = true; st.lightboxAll = false;
    // Chunked documents hydrate on open: synchronous when the session's chunk has already
    // streamed in (the common case). Otherwise render()'s loading shell holds the view until the
    // chunk lands, then the seed + re-render below run.
    if (!hydrateSession(i)) {
      const session = D;
      awaitSessionChunk(i).then(() => {
        if (D !== session || st.view !== 'detail') return;
        seedSessionDetail();
        writeRoute(true);
        render();
      });
      return;
    }
    seedSessionDetail();
  };
  const revealTimelineStep = (stepId) => {
    const index = D.trace.findIndex((t) => t.i === stepId);
    if (index < 0) return;
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    if (trailStart >= 0 && index >= trailStart) st.trailOpen = true;
    else if (D.trace.some((t) => t.objective && t.trailhead)) st.trailheadOpen = true;
  };

  // Report state lives in query parameters so copied URLs communicate their selected run, view,
  // and step. Only these owned keys are changed: signed-artifact parameters such as `jwt` survive
  // every navigation. Legacy hash routes remain readable and are canonicalized on initial load.
  const routeKeys = ['view', 'runs', 'run', 'tab', 'step', 'streams', 'llm', 'stream', 'sort', 'filter'];
  // 'stream' (the retired Events tab's selected-stream index) and 'filter' (the retired
  // Self-healed index filter) stay in routeKeys so legacy URLs that carry them are still
  // canonicalized away, but they are no longer read or written.
  const readRoute = () => {
    if (typeof location === 'undefined') return null;
    const query = new URLSearchParams(String(location.search || ''));
    const hasQueryRoute = routeKeys.some((key) => query.has(key));
    const p = hasQueryRoute ? query : new URLSearchParams(String(location.hash || '').replace(/^#/, ''));
    if (p.get('view') === 'runs' || p.has('runs')) return { view: 'index', sort: p.get('sort') || 'grouped' };
    if (!p.has('run') && !p.has('tab') && !p.has('step')) return null;
    return {
      view: 'detail', session: Number(p.get('run') || 0), tab: p.get('tab') || 'timeline',
      step: p.has('step') ? Number(p.get('step')) : null,
      llm: Number(p.get('llm') || 0),
      streams: p.get('streams'),
    };
  };
  // Apply the detail-view parts of a parsed route (tab/step/llm/streams) to the open session.
  // Split out of applyRoute because a route into a not-yet-hydrated session parks here and
  // re-applies from seedSessionDetail once the chunk lands.
  const applyDetailRoute = (r) => {
    const requestedTab = r.tab === 'grid' ? 'lightbox' : r.tab;
    // Legacy 'events' routes land on the timeline, where inline event streams now live.
    const allowed = ['timeline', 'lightbox', 'video', 'llm', 'config', 'recording', 'device', 'network', 'info'];
    if (allowed.indexOf(requestedTab) >= 0) st.tab = requestedTab;
    if (r.step != null && Number.isFinite(r.step) && D.trace.some((t) => t.i === r.step)) { st.step = r.step; revealTimelineStep(st.step); }
    if (Number.isFinite(r.llm) && r.llm >= 0 && r.llm < D.llm.length) st.llmSel = r.llm;
    // No upper-bound check here: stream counts may be unknown while a compressed events payload
    // is still inflating, so the consumer owns the clamp (streamEvents ignores unknown tlStreams
    // indices).
    if (r.streams != null) st.tlStreams = r.streams.split(',').map(Number).filter((i) => Number.isInteger(i) && i >= 0);
  };
  const applyRoute = () => {
    const r = readRoute();
    if (!r) return;
    if (r.view === 'index' && MULTI) {
      stopTimeline(); st.view = 'index';
      if (['grouped', 'original', 'name', 'owner', 'cost'].indexOf(r.sort) >= 0) st.runSort = r.sort;
      return;
    }
    const si = Number.isFinite(r.session) ? Math.max(0, Math.min(SESSIONS.length - 1, r.session)) : 0;
    openSession(si);
    if (unhydrated.has(si)) pendingDetailRoute = r;
    else applyDetailRoute(r);
  };
  // The viewer's owned route state, serialized. The single source both the URL writer and the
  // Copy-link grafter consume — the grafter can't read the state back off location.search in a
  // sandboxed embed, where writeRoute's history write is refused (see below).
  const routeParams = () => {
    const params = new URLSearchParams();
    if (st.view === 'index') {
      params.set('view', 'runs');
      if (st.runSort !== 'grouped') params.set('sort', st.runSort);
    } else {
      params.set('run', String(st.session));
      params.set('tab', st.tab);
      if (st.tab === 'timeline' && Number.isFinite(st.step)) params.set('step', String(st.step));
      if (st.tab === 'timeline' && st.tlStreams.length) params.set('streams', st.tlStreams.join(','));
      if (st.tab === 'llm' && st.llmSel) params.set('llm', String(st.llmSel));
    }
    return params;
  };
  const writeRoute = (replace) => {
    if (typeof history === 'undefined' || typeof location === 'undefined') return;
    const params = new URLSearchParams(String(location.search || ''));
    routeKeys.forEach((key) => params.delete(key));
    routeParams().forEach((value, key) => params.set(key, value));
    const search = params.toString();
    const legacyHash = /^#(?:runs(?:&|$)|run=|tab=|step=)/.test(String(location.hash || ''));
    const next = `${String(location.pathname || '')}${search ? `?${search}` : ''}${legacyHash ? '' : String(location.hash || '')}`;
    const current = `${String(location.pathname || '')}${String(location.search || '')}${String(location.hash || '')}`;
    if (current === next) return;
    // Route persistence is a progressive enhancement: in an `about:srcdoc`/sandboxed embed (e.g.
    // Trail Runner's zip-report iframe) the History API refuses URL writes with a SecurityError —
    // the report must still render, just without deep-linkable tab/step state.
    try { history[replace ? 'replaceState' : 'pushState'](null, '', next); } catch (_) { /* embedded */ }
  };
  // A report served over HTTP(S) is shareable by URL — the route already encodes the view, sort,
  // run, and step state, so the browser's current address IS the deep link. file:// documents and
  // srcdoc embeds (Trail Runner's zip-report iframe) have no address worth sharing, so the
  // Copy-link affordances hide there — unless the generator baked in a canonical share URL
  // (`trailblaze report --share-url …`, e.g. CI pointing at the hosted artifact), which wins over
  // the browser address and keeps the affordance available from any viewing context.
  const SHARE_URL = safeHref(RAW.shareUrl) || '';
  const shareLinkAvailable = () => !!SHARE_URL || (typeof location !== 'undefined' && /^https?:$/.test(String(location.protocol || '')));
  const reportLink = () => {
    if (SHARE_URL) {
      // Graft the current route state onto the canonical URL so the copied link deep-links to
      // the run/step being looked at. Serialized from viewer state, not read back off
      // location.search — a sandboxed embed never gets the URL write. Only our owned keys
      // move — a signed artifact URL's own parameters (e.g. jwt) survive untouched.
      try {
        const url = new URL(SHARE_URL);
        routeKeys.forEach((key) => url.searchParams.delete(key));
        routeParams().forEach((value, key) => url.searchParams.set(key, value));
        return url.toString();
      } catch (e) { return SHARE_URL; }
    }
    return String(location.href || `${location.pathname || ''}${location.search || ''}${location.hash || ''}`);
  };
  const wireCopyLink = (el, after = null) => {
    if (!el) return;
    el.onclick = () => {
      writeRoute(true);
      const label = el.textContent;
      const done = (text) => {
        el.textContent = text;
        setTimeout(() => { el.textContent = label; if (after) after(); }, 1200);
      };
      // writeText resolves async and rejects on permission/insecure-context failures — only
      // claim "Copied" once it settles (Promise.resolve also covers non-promise test doubles).
      try {
        Promise.resolve(navigator.clipboard.writeText(reportLink())).then(() => done('Copied'), () => done('Copy failed'));
      } catch (e) { done('Copy failed'); }
    };
  };

  // One openSession per boot: a routed URL opens its session inside applyRoute (single-session
  // documents included — readRoute's session index clamps to 0); only a bare, route-less
  // single-session document needs the default openSession here.
  if (!MULTI && !readRoute()) openSession(0);
  applyRoute();
  writeRoute(true);

  const stepCat = (t) => {
    if (!t.ok) return 'fail';
    const tool = String(t.tool || ''); const lbl = String(t.label || '').toLowerCase();
    if (tool === 'agent step' || tool.indexOf('llm') === 0) return 'llm';
    if (lbl.indexOf('assert') === 0 || lbl.indexOf('verify') === 0 || tool.toLowerCase().indexOf('assert') >= 0) return 'assert';
    return 'tool';
  };
  const catColor = { fail: 'var(--fail)', llm: 'var(--run)', assert: 'var(--pass)', tool: 'var(--pass)' };
  const stepIcon = (t) => {
    const label = String(t.label || '').toLowerCase();
    const tool = String(t.tool || '').toLowerCase();
    const assertion = label.indexOf('assert') === 0 || label.indexOf('verify') === 0 || tool.indexOf('assert') >= 0;
    const tap = label.indexOf('tap') === 0 || label.indexOf('longpress') === 0 || label.indexOf('long press') === 0;
    if (!t.ok) return { cls: 'failure', glyph: '×' };
    if (assertion) return { cls: 'verify', glyph: '✓' };
    if (tap) return { cls: 'tap', glyph: '👆' };
    return { cls: 'dot', glyph: '' };
  };

  // Error producers do not share one wire shape: JVM failures usually arrive as
  // `qualified.Exception: message\n\tat ...`, JS failures use `TypeError: message`, and plugins
  // may serialize a structured error object. Normalize those forms for one digestible summary.
  const parseFailure = (raw) => {
    const text = String(raw || '').replace(/\r\n/g, '\n').trim();
    if (!text) return null;
    try {
      const value = JSON.parse(text);
      const source = value && typeof value.error === 'object' ? value.error : value;
      if (source && typeof source === 'object') {
        const type = source.type || source.name || source.errorType || source.class || 'Error';
        const message = source.message || source.errorMessage || source.reason || source.detail || text;
        const stack = source.stack || source.stackTrace || source.stacktrace || '';
        return { type: String(type), message: String(message), stack: String(stack).trim() };
      }
    } catch (_) { /* Plain-text exception; parsed below. */ }
    const lines = text.split('\n');
    const first = lines[0].trim();
    const typed = first.match(/^(?:Caused by:\s*)?([A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*(?:Error|Exception|Failure|Throwable))(?::\s*(.*))?$/);
    const stackAt = lines.findIndex((line, i) => i > 0 && /^\s*(?:at\s|Caused by:|Suppressed:|\.\.\. \d+ more)/.test(line));
    const beforeStack = stackAt >= 0 ? lines.slice(0, stackAt) : lines;
    const stack = stackAt >= 0 ? lines.slice(stackAt).join('\n').trim() : '';
    const messageLines = typed ? [typed[2] || '', ...beforeStack.slice(1)] : beforeStack;
    return {
      type: typed ? typed[1] : 'Error',
      message: messageLines.join('\n').trim() || first,
      stack,
    };
  };

  const renderFailureSummary = (groups) => {
    const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
    if (!runFailed && !(D.meta && D.meta.error)) return '';
    const anchorIdx = failureAnchorIndex();
    const failedStep = anchorIdx >= 0 ? D.trace[anchorIdx] : null;
    const failedTool = failedStep && !failedStep.objective ? failedStep : null;
    const failedGroup = failedStep && groups.find((g) => g.header === failedStep || g.items.indexOf(failedStep) >= 0);
    // The message must come from the failed step itself (the anchor, its objective's Complete
    // bookend, or a sibling row): a trace-wide scan could surface an earlier tolerated failure's
    // error (a recovered assertion poll carries err) for a failure that happened steps later.
    const groupRows = failedGroup ? [failedGroup.header].concat(failedGroup.items) : (failedStep ? [failedStep] : []);
    const errorStep = (failedStep && failedStep.err) ? failedStep : groupRows.find((t) => t && !t.ok && t.err);
    const parsed = parseFailure((errorStep && errorStep.err) || (D.meta && D.meta.error));
    if (!parsed) return '';
    const objective = failedGroup && failedGroup.header;
    const frames = parsed.stack ? parsed.stack.split('\n').filter((line) => /^\s*at\s/.test(line)).length : 0;
    const title = objective ? (objective.trailhead ? 'Trailhead failed' : `Step ${failedGroup.num} failed`) : 'Run failure';
    const context = objective ? objective.label : (failedStep ? failedStep.label : 'Run-level error');
    const typeName = parsed.type.split('.').pop() || parsed.type;
    const yamlLink = failedStep && (D.recordingYaml || D.originalYaml) ? `<button type="button" class="yamllink" data-yaml-step="${failedStep.i}">View YAML</button>` : '';
    return `<section class="failurepanel" aria-labelledby="failure-title">
      <div class="failurehead"><span class="failureicon" aria-hidden="true">!</span><span class="failuretitle" id="failure-title">${esc(title)}</span><span class="failurecontext">${esc(context)}</span></div>
      ${failedTool ? `<div class="failuretool"><div class="k">Failed tool call</div><div class="failuretoolvalue"><span class="failuretoolname">${esc(failedTool.label)}</span>${failedTool.tool ? `<code class="failuretoolargs mono">${esc(failedTool.tool)}</code>` : ''}${yamlLink}</div></div>` : yamlLink}
      <div class="failurebody"><div class="failurefield"><div class="k">Type</div><code class="failuretype mono" title="${esc(parsed.type)}">${esc(typeName)}</code></div><div class="failurefield"><div class="k">Message</div><div class="failuremessage">${esc(parsed.message).replace(/\n/g, '<br>')}</div></div></div>
      ${parsed.stack ? `<details class="failurestack" open><summary>Stack trace<span class="frames">${frames} frame${frames === 1 ? '' : 's'}</span></summary><pre class="mono">${esc(parsed.stack)}</pre></details>` : ''}
    </section>`;
  };

  const renderSelfHealSummary = (groups) => {
    const status = String((D.meta && D.meta.status) || '').toLowerCase();
    if (!(D.meta && D.meta.selfHeal) || (status !== 'passed' && status !== 'success')) return '';
    const healedGroup = groups.find((g) => g.header && g.header.selfHeal);
    if (!healedGroup) return `<section class="selfhealpanel" aria-labelledby="selfheal-title"><div class="selfhealhead"><span class="selfhealicon" aria-hidden="true">✓</span><span class="selfhealtitle" id="selfheal-title">Self-healed</span><span class="selfhealcontext">Recorded actions were repaired during this run</span></div></section>`;
    const healed = healedGroup.header;
    const parsed = parseFailure(healed.selfHealError);
    const title = healed.trailhead ? 'Trailhead self-healed' : `Step ${healedGroup.num} self-healed`;
    return `<section class="selfhealpanel" aria-labelledby="selfheal-title">
      <div class="selfhealhead"><span class="selfhealicon" aria-hidden="true">✓</span><span class="selfhealtitle" id="selfheal-title">${esc(title)}</span><span class="selfhealcontext">${esc(healed.label)}</span></div>
      <div class="selfhealbody">
        <div class="selfhealfield"><div class="k">Failed recorded action</div><span class="selfhealtoolname">${esc(healed.selfHealTool || 'Recorded action')}</span></div>
        <div class="selfhealfield"><div class="k">Recovery</div><div class="selfhealmessage">Trailblaze used AI to recover this step.${parsed && parsed.message ? ` <span title="${esc(parsed.type)}">${esc(parsed.message)}</span>` : ''}</div>${D.recordingYaml || D.originalYaml ? `<button type="button" class="yamllink" data-yaml-step="${healed.i}">View YAML</button>` : ''}</div>
      </div>
    </section>`;
  };

  const idxOf = (i) => Math.max(0, D.trace.findIndex((t) => t.i === i));
  const shotForStep = (i) => {
    const at = idxOf(i);
    // Resolve a row to its inlined screenshot — but only if the image is actually present in
    // D.shots. A screenshotFile whose inline failed (the Share path skips failed fetches;
    // run-report-cli skips files dataUri() can't read) must NOT short-circuit the fallbacks and
    // leave the pane empty.
    const shot = (r) => (r && r.screenshotFile && D.shots[r.screenshotFile]) ? D.shots[r.screenshotFile] : null;
    // 1. The row's own frame — the screen it acted on (action/tool rows carry their pre-action frame).
    let s = shot(D.trace[at]);
    if (s) return s;
    // 2. Screenshot-less rows (step/objective headers, agent-reasoning turns) show the NEXT frame —
    // the screen this step is about to act on. Bounded to THIS step: stop at the next objective
    // header so a frameless middle step never previews a future step's screen.
    for (let k = at + 1; k < D.trace.length && !D.trace[k].objective; k++) {
      s = shot(D.trace[k]);
      if (s) return s;
    }
    // 3. Nothing usable ahead in this step: fall back to the nearest earlier frame so the pane is
    // never empty.
    for (let k = at - 1; k >= 0; k--) {
      s = shot(D.trace[k]);
      if (s) return s;
    }
    return null;
  };

  // The session's video, but only when it can be mapped onto the run clock (its capture-start
  // timestamp and at least one step timestamp exist). Otherwise the timeline keeps screenshots.
  const tlVideo = () => (D.video && D.video.startMs != null && traceT0() != null) ? D.video : null;
  // Wall-clock ms a step represents on the run clock: its own timestamp, else the nearest earlier
  // (then next) timed row — mirroring shotForStep's never-empty fallback. Non-null whenever
  // tlVideo() is non-null (its traceT0 gate guarantees a timed row exists).
  const stepClockMs = (i) => {
    const at = idxOf(i);
    for (let k = at; k >= 0; k--) { if (D.trace[k].ts != null) return D.trace[k].ts; }
    for (let k = at + 1; k < D.trace.length; k++) { if (D.trace[k].ts != null) return D.trace[k].ts; }
    return null;
  };
  // Frame math (videoFrameAt / videoEndMs / spriteFrameCss) lives at module level with the other
  // playback-timing helpers — see run-report-playback.ts.
  // One-shot fallback measurement backing `spriteAspect` for payloads without frameWidth (frame
  // boxes are background-image divs with no intrinsic size); `done` runs on first resolution so
  // the caller can apply it to the live box. The frame box is already using the same sprite URL
  // as its background, so this decode hits the image cache rather than paying a second full decode.
  const measureSpriteAspect = (v, done) => {
    if (!v) return;
    const src = spriteUrl(v, 0);
    if (!src) return;
    const img = new Image();
    img.onload = () => { const fw = img.naturalWidth / v.columns; if (fw > 0 && v.frameHeight > 0 && spriteAspect == null) { spriteAspect = `${fw} / ${v.frameHeight}`; done(); } };
    img.src = src;
  };

  // The report-time action overlay on a step's screenshot: a tap/long-press dot, a swipe arrow, an
  // assertion ok-dot, or a failed-assertion red border. Positioned by device-pixel ratio over an
  // <img> that's width:100% and preserves the screenshot's aspect, so percentages map directly.
  const markHtml = (t) => {
    const mk = t.mark;
    if (!mk) return '';
    if (mk.kind === 'swipe') {
      return `<svg class="swipe" viewBox="0 0 ${mk.dw} ${mk.dh}" preserveAspectRatio="none">
        <defs><marker id="ah${t.i}" markerWidth="5" markerHeight="5" refX="2.5" refY="2.5" orient="auto"><path d="M0,0 L5,2.5 L0,5 Z" fill="#5e9bff"/></marker></defs>
        <line x1="${mk.x1}" y1="${mk.y1}" x2="${mk.x2}" y2="${mk.y2}" stroke="#5e9bff" stroke-width="6" marker-end="url(#ah${t.i})" /></svg>`;
    }
    // A failed assertion gets the red full-screen border (matches the old report's
    // ScreenshotAnnotation), keyed off the action's own `succeeded` flag.
    if (mk.kind === 'assert' && mk.ok === false) return `<div class="markborder"></div>`;
    const left = (mk.x / mk.dw) * 100;
    const top = (mk.y / mk.dh) * 100;
    const cls = mk.kind === 'assert' ? 'assertok' : 'tap';
    return `<div class="mark ${cls}" style="left:${left}%;top:${top}%"></div>`;
  };

  // Group flat trace under objective rows -> { header, num, items } (same shape as the app's
  // StepStack). The trailhead (step 0) keeps num 0 so the trail steps still read STEP 1..N.
  const groupTrace = () => {
    const gs = []; let cur = null; let n = 0;
    for (const t of D.trace) {
      if (t.objective) { cur = { header: t, num: t.trailhead ? 0 : ++n, items: [] }; gs.push(cur); }
      else { if (!cur) { cur = { header: null, num: 0, items: [] }; gs.push(cur); } cur.items.push(t); }
    }
    return gs;
  };

  // First wall-clock timestamp in the trace — the run-clock zero every row's elapsed offset is
  // measured from (parity with the legacy report's elapsed-from-session-start gutter).
  const traceT0 = () => { for (const t of D.trace) { if (t.ts != null) return t.ts; } return null; };
  const fmtDur = (ms) => !ms ? '' : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
  const fmtClock = (ms) => `${Math.floor((ms || 0) / 60000)}:${String(Math.floor(((ms || 0) % 60000) / 1000)).padStart(2, '0')}`;

  // Same time-compression model as Trail Runner's VerticalScrubber: real gaps are preserved but
  // clamped so a fast burst stays clickable and a long idle period does not consume the rail. The
  // whole axis derives from the shared steps-mode playback schedule (compressed positions AND the
  // haveTs/lo/hi timestamp coverage) so the rail, its tick marks, and the steps-mode playback
  // clock can never drift apart. Callers that already built the steps schedule pass it in.
  const timelineAxis = (schedule = buildPlaybackSchedule(D.trace, null)) => {
    const haveTs = schedule.haveTs; const lo = schedule.lo; const hi = schedule.hi;
    const pos = schedule.offsets;
    const real = [];
    D.trace.forEach((t, i) => {
      const raw = haveTs && t.ts != null ? t.ts - lo : (i > 0 ? real[i - 1] : 0);
      real.push(i > 0 ? Math.max(real[i - 1], raw) : Math.max(0, raw));
    });
    const span = Math.max(1, pos.length ? pos[pos.length - 1] : 0);
    const stepFrac = pos.map((p) => p / span);
    const tsFrac = (ms) => {
      if (ms == null || !haveTs || !D.trace.length) return null;
      const r = ms - lo;
      if (r <= real[0]) return stepFrac[0];
      for (let i = 1; i < D.trace.length; i++) {
        if (r <= real[i]) {
          const d = real[i] - real[i - 1] || 1;
          return Math.min(1, Math.max(0, stepFrac[i - 1] + ((r - real[i - 1]) / d) * (stepFrac[i] - stepFrac[i - 1])));
        }
      }
      return 1;
    };
    return { stepFrac, tsFrac, totalMs: haveTs ? Math.max(1, hi - lo) : span };
  };

  // Match Trail Runner's high-volume stream behavior: streams are opt-in on the timeline. The
  // selected indices live in the URL so a filtered timeline can be shared exactly as viewed.
  const streamEvents = () => (sessionEvents(D) || []).flatMap((stream, streamIndex): Array<{ t: number | null; d?: string; row?: FormattedRow; stream: string; streamIndex: number; key: string }> => {
    if (st.tlStreams.indexOf(streamIndex) < 0) return [];
    // A formatted stream contributes its formatter-produced rows to the timeline; a generic one
    // contributes its raw events. Both carry the same clock + stream identity downstream.
    if (stream.rows && stream.rows.length) {
      return stream.rows.map((row, n) => ({ t: row.t, row, stream: stream.name, streamIndex, key: `${stream.name}-${n}` }));
    }
    return (stream.events || []).map((e, n) => ({ ...e, stream: stream.name, streamIndex, key: `${stream.name}-${n}` }));
  }).sort((a, b) => (a.t || 0) - (b.t || 0));

  const eventBuckets = (events) => {
    const buckets = D.trace.map(() => []);
    const timedSteps = D.trace
      .map((t, i) => ({ i, t: t.ts }))
      .filter((step) => step.t != null);
    let timedStep = -1;
    events.forEach((e) => {
      let at = 0;
      if (e.t != null) {
        while (timedStep + 1 < timedSteps.length && timedSteps[timedStep + 1].t <= e.t) timedStep++;
        if (timedStep >= 0) at = timedSteps[timedStep].i;
      }
      if (buckets[at]) buckets[at].push(e);
    });
    return buckets;
  };

  // Equal OKLCH lightness/chroma keeps qualitative stream colors visually balanced. Advancing by
  // the golden angle makes adjacent producer colors distinct without giving any stream a semantic
  // status color; the producer name and diamond remain redundant cues when color is unavailable.
  const streamColor = (index) => `oklch(74% .14 ${(70 + index * 137.508) % 360})`;

  // Formatter-produced rows (EventStream.rows): the netlog-style rendering. Rows are pure data
  // built at report-generation time (see run-report-events.ts) — the viewer owns ALL markup, so a
  // formatter can never inject HTML or depend on the report's internals. The summary line carries
  // all the formatting (label, badges, fields); the expanded body is the raw payload, pretty-printed.
  const rowBadgesHtml = (row) => (row.badges || []).map((b) => `<span class="rowbadge ${b.tone || ''}">${esc(b.text)}</span>`).join('');
  const formattedRowBody = (row) => {
    // A field may carry an embed-time-validated `href` (see RowField); re-check it here and
    // render the value as a link only when it is still a well-formed http(s) URL.
    const fieldValueHtml = (f) => {
      const url = f.href ? safeHref(f.href) : null;
      return url ? `<a class="quietlink" href="${esc(url)}" target="_blank" rel="noopener">${esc(f.v)} ↗</a>` : esc(f.v);
    };
    const fields = (row.fields || []).length ? `<div class="eventfields">${row.fields.map((f) => `<div class="eventfield"><div class="k">${esc(f.k)}</div><div class="v">${fieldValueHtml(f)}</div></div>`).join('')}</div>` : '';
    const raw = (row.raw || []).map((r) => `<pre class="mono">${esc(rawPrettyText(r))}</pre>`).join('');
    return `${fields}${raw}`;
  };

  // Timeline event bodies are lazy (payloads are untruncated): each rendered <details> carries a
  // data-lazykey resolved through this map by wireLazyTimelineBodies. Rebuilt on every timeline
  // render (streamGroupHtml runs per step bucket within one render pass).
  const tlEventByKey = new Map();
  const streamGroupHtml = (events) => {
    if (!events.length) return '';
    const t0 = traceT0();
    const groups = [];
    events.forEach((e) => {
      let group = groups.find((g) => g.stream === e.stream);
      if (!group) { group = { stream: e.stream, streamIndex: e.streamIndex, events: [] }; groups.push(group); }
      group.events.push(e);
    });
    return groups.map((group) => {
      const rel = (e) => e.t != null && t0 != null ? `+${((e.t - t0) / 1000).toFixed(2)}s` : '';
      const items = group.events.map((e) => {
        tlEventByKey.set(e.key, e);
        if (e.row) {
          const badges = rowBadgesHtml(e.row);
          const tone = e.row.tone === 'error' ? ' e' : e.row.tone === 'warn' ? ' w' : '';
          return `<details class="timelineevent${tone}" data-lazykey="${esc(e.key)}"><summary><span class="streamtime">${esc(rel(e))}</span><span class="timelineeventlabel">${esc(e.row.label)}${badges ? ` <span class="fmtbadges">${badges}</span>` : ''}</span><span class="timelineeventchev" aria-hidden="true"></span></summary><div class="fmtbody tlbody"></div></details>`;
        }
        const { semanticLabel } = normalizeEventPayload(e);
        const label = semanticLabel || 'Event';
        return `<details class="timelineevent" data-lazykey="${esc(e.key)}"><summary><span class="streamtime">${esc(rel(e))}</span><span class="timelineeventlabel">${esc(label)}</span><span class="timelineeventchev" aria-hidden="true"></span></summary><pre class="mono"></pre></details>`;
      }).join('');
      return `<details class="streamrow" style="--stream-color:${streamColor(group.streamIndex)}" open><summary><span class="streamdot"></span><span class="streamtype">${esc(group.stream)}</span><span class="streamtime">${group.events.length} event${group.events.length === 1 ? '' : 's'}</span></summary><div class="streamitems timelineeventitems">${items}</div></details>`;
    }).join('');
  };

  // Screen-reader value text for the scrubber's current position — used by the static render AND
  // updated in place as playback advances, so assistive tech always hears the current row.
  const scrubValueText = (pos) => {
    const current = D.trace[pos];
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    const hasTrailhead = D.trace.some((t) => t.objective && t.trailhead);
    const phase = hasTrailhead && (trailStart < 0 || pos < trailStart) ? 'Trailhead' : 'Trail';
    return `${phase}, item ${pos + 1} of ${D.trace.length}: ${(current && current.label) || 'Timeline item'}`;
  };

  const scrubberHtml = (axis, events, pos) => {
    const ticks = D.trace.map((t, i) => `<span class="scrubtick" aria-hidden="true" style="left:calc(${(axis.stepFrac[i] || 0) * 100}% - 1px);background:${catColor[stepCat(t)]}"></span>`).join('');
    const eventTicks = events.map((e) => {
      const f = axis.tsFrac(e.t); if (f == null) return '';
      return `<span class="scrubtick" aria-hidden="true" title="${esc(e.stream)}" style="left:calc(${f * 100}% - 1px);background:${streamColor(e.streamIndex)}"></span>`;
    }).join('');
    const frac = axis.stepFrac[pos] || 0;
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    const hasTrailhead = D.trace.some((t) => t.objective && t.trailhead);
    const trailFrac = trailStart >= 0 ? (axis.stepFrac[trailStart] || 0) : 1;
    const rail = hasTrailhead && trailStart < 0
      ? `<div class="scrubline setup" style="width:100%"></div>`
      : hasTrailhead
      ? `<div class="scrubline setup" style="width:${trailFrac * 100}%"></div><div class="scrubline trail" style="left:${trailFrac * 100}%"></div><span class="scrubphasebreak" style="left:${trailFrac * 100}%" title="Trail begins" aria-hidden="true"></span>`
      : `<div class="scrubline trail" style="left:0"></div>`;
    const phaseLabel = hasTrailhead && trailStart < 0 ? 'Timeline for Trailhead setup. The dotted rail marks deterministic setup.'
      : hasTrailhead ? 'Timeline. Dotted segment is Trailhead setup; solid segment is the authored Trail.'
      : 'Timeline for the authored Trail.';
    return `<div class="scrub"><div class="scrubclock">0:00</div><div class="scrubtrack" data-scrub role="slider" tabindex="0" aria-label="${phaseLabel}" aria-valuemin="1" aria-valuemax="${D.trace.length}" aria-valuenow="${pos + 1}" aria-valuetext="${esc(scrubValueText(pos))}">${rail}${ticks}${eventTicks}<div class="scrubhead" style="left:${frac * 100}%"></div></div><div class="scrubclock">${fmtClock(axis.totalMs)}</div></div>`;
  };

  const stepRowHtml = (t, child) => {
    const cat = stepCat(t); const sel = t.i === st.step;
    const icon = stepIcon(t);
    const kids = (t.children || []).length
      ? `<div class="kids">${t.children.map((c) => `<div><span class="mono">${esc(c.label)}</span> <span class="kt mono">${esc(c.tool)}</span></div>`).join('')}</div>` : '';
    const count = t.count ? ` <span style="color:var(--sub);font-variant-numeric:tabular-nums">×${t.count}</span>` : '';
    const t0 = traceT0();
    const rel = (t.ts != null && t0 != null) ? `+${((t.ts - t0) / 1000).toFixed(1)}s` : '';
    const dur = fmtDur(t.ms);
    const time = (rel || dur) ? `<span class="ts">${rel}${dur ? `<span class="dur">${dur}</span>` : ''}</span>` : '';
    return `<div class="step${sel ? ' sel' : ''}${child ? ' child' : ''}${t.selfHealSource ? ' selfheal' : ''}" data-step="${t.i}" role="button" tabindex="0"${sel ? ' aria-current="step"' : ''}>
      ${child ? '' : `<span class="num">${t.i}</span>`}
      <span class="ic ${icon.cls}"${icon.cls === 'dot' ? ` style="--icon-color:${catColor[cat]}"` : ''} aria-hidden="true">${icon.glyph}</span>
      <div style="flex:1;min-width:0">
        <div class="lbl">${esc(t.label)}${count}</div>
        ${t.tool ? `<div class="tl-tool mono">${esc(t.tool)}</div>` : ''}
        ${t.note ? `<div class="note">${esc(t.note)}</div>` : ''}
        ${kids}
      </div>
      ${time}
    </div>`;
  };

  const renderTimeline = () => {
    const groups = groupTrace();
    const failureSummary = renderFailureSummary(groups);
    const selfHealSummary = renderSelfHealSummary(groups);
    const streams = sessionEvents(D) || [];
    tlEventByKey.clear();
    const events = streamEvents();
    const streamChooser = streams.length ? `<details class="streamselect" data-streamselect${st.tlMenuOpen ? ' open' : ''}><summary><span class="streamselectoricon" aria-hidden="true"></span><span>Event streams</span><span class="selection">${st.tlStreams.length} of ${streams.length}</span><span class="chevron" aria-hidden="true"></span></summary><div class="streammenu"><div class="streammenuhead"><span>Include in timeline</span><span class="streammenuactions"><button type="button" data-tlstreams="all">Select all</button><button type="button" data-tlstreams="none">Clear</button></span></div>${streams.map((stream, i) => `<label class="streamoption" style="--stream-color:${streamColor(i)}"><input type="checkbox" data-tlstream="${i}"${st.tlStreams.indexOf(i) >= 0 ? ' checked' : ''}><span class="streamoptiondot" aria-hidden="true"></span><span class="streamname">${esc(stream.name)}</span><span class="streamcount">${stream.total || (stream.events || []).length}</span></label>`).join('')}</div></details>` : '';
    const controls = streamChooser ? `<div class="timelinecontrols">${streamChooser}</div>` : '';
    // An event-only session (e.g. a run that failed before its first step) still gets its streams:
    // the chooser plus a flat stream list — there are no steps to bucket the events under.
    if (!D.trace.length) return `${failureSummary}${selfHealSummary}<div class="timeline-list">${controls}<div class="empty">This run didn't emit any agent-task steps.</div>${streamGroupHtml(events)}</div>`;
    const buckets = eventBuckets(events);
    const withEvents = (t, child) => {
      const at = idxOf(t.i);
      return stepRowHtml(t, child) + streamGroupHtml(buckets[at] || []);
    };
    const hasSteps = groups.some((g) => g.header);
    let stepsHtml;
    if (!hasSteps) {
      stepsHtml = D.trace.map((t) => withEvents(t, false)).join('');
    } else {
      const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
      const anchorRow = runFailed ? D.trace[failureAnchorIndex()] : null;
      const groupsHtml = (phaseGroups) => phaseGroups.map((g) => {
        // The header dot reports the OBJECTIVE's outcome (from its Complete bookend), not the worst
        // row inside it: an assertion poll can fail and recover, and a trailhead's internal retry
        // loops can fail rows inside a step that succeeded. For a failed run whose failing step has
        // no failed Complete bookend (a crash), the step holding the failure anchor is still failed.
        const failed = g.header ? (!g.header.ok || (runFailed && g.items.indexOf(anchorRow) >= 0)) : g.items.some((t) => !t.ok);
        const selfHealed = !!(g.header && g.header.selfHeal);
        const isTrailhead = g.header && g.header.trailhead;
        const groupSelected = g.header && g.header.i === st.step;
        const hdr = g.header ? `<button type="button" class="grphdr${isTrailhead ? ' trailhead' : ''}${groupSelected ? ' sel' : ''}" data-group="${g.header.i}"${groupSelected ? ' aria-current="step"' : ''}>
            <span class="chip">${isTrailhead ? 'TRAILHEAD' : `STEP ${g.num}`}</span>
            <span class="dot" style="background:${failed ? 'var(--fail)' : selfHealed ? 'var(--amber)' : 'var(--pass)'}"></span>
            ${g.items.length ? `<span style="font-size:11px;color:var(--sub)">${g.items.length} action${g.items.length === 1 ? '' : 's'}</span>` : ''}
            <span class="lbl" style="width:100%">${esc(g.header.label)}</span>
          </button>` : '';
        const headerEvents = g.header ? streamGroupHtml(buckets[idxOf(g.header.i)] || []) : '';
        return `<div class="stepgroup${failed ? ' failed' : selfHealed ? ' selfhealed' : ''}">${hdr}<div class="stepgroupbody">${headerEvents}${g.items.map((t) => withEvents(t, hasSteps)).join('')}</div></div>`;
      }).join('');
      const trailheadGroups = groups.filter((g) => g.header && g.header.trailhead);
      const trailGroups = groups.filter((g) => !g.header || !g.header.trailhead);
      const trailStepCount = trailGroups.filter((g) => g.header).length;
      const phaseStats = (phaseGroups) => {
        const actions = phaseGroups.reduce((n, g) => n + g.items.length, 0);
        const duration = phaseGroups.reduce((ms, g) => ms + g.items.reduce((sum, t) => sum + (t.ms || 0), 0), 0);
        return `${actions} action${actions === 1 ? '' : 's'}${duration ? ` · ${fmtDur(duration)}` : ''}`;
      };
      stepsHtml = `<div class="timelinephases">
        ${trailheadGroups.length ? `<section class="tlphase trailhead" aria-labelledby="trailhead-heading"><div class="tlphasehead"><button type="button" class="phasecontrol" data-phase="trailhead" aria-expanded="${st.trailheadOpen}"><span class="name" id="trailhead-heading">Trailhead</span><span class="desc">Deterministic setup · step 0 · ${phaseStats(trailheadGroups)}</span><span class="phasechev" aria-hidden="true"></span></button></div><div class="tlphasebody"${st.trailheadOpen ? '' : ' hidden'}><div class="steps">${groupsHtml(trailheadGroups)}</div></div></section>` : ''}
        ${trailGroups.length ? `<section class="tlphase" aria-labelledby="trail-heading"><div class="tlphasehead"><button type="button" class="phasecontrol" data-phase="trail" aria-expanded="${st.trailOpen}"><span class="name" id="trail-heading">Trail</span><span class="desc">${trailStepCount} test step${trailStepCount === 1 ? '' : 's'} · ${phaseStats(trailGroups)}</span><span class="phasechev" aria-hidden="true"></span></button></div><div class="tlphasebody"${st.trailOpen ? '' : ' hidden'}><div class="steps">${groupsHtml(trailGroups)}</div></div></section>` : ''}
      </div>`;
    }
    const cur = D.trace.find((t) => t.i === st.step) || D.trace[0];
    const shot = shotForStep(st.step);
    const pos = idxOf(st.step);
    // Prefer the captured video over per-step screenshots: show the video frame at this step's
    // run-clock time. Screenshot (then the empty note) remains the fallback.
    const v = tlVideo();
    const clockAtStep = v ? stepClockMs(st.step) : null;
    const cell = v && clockAtStep != null ? spriteFrameCss(v, videoFrameAt(v, clockAtStep)) : null;
    const pane = cell
      ? `<div class="shotwrap"><div class="tlvframe" id="tlvframe" role="img" aria-label="Video frame at ${esc(cur.label)}, step ${pos + 1}" style="${spriteAspect ? `aspect-ratio:${spriteAspect};` : ''}background-image:url('${spriteUrl(v, cell.sheet)}');background-size:${cell.size};background-position:${cell.position}"></div>${markHtml(cur)}</div>`
      : shot
      ? `<div class="shotwrap"><img class="shot" id="shot" src="${shot}" role="button" tabindex="0" alt="${esc(cur.label)} at step ${pos + 1}" />${cur.screenshotFile ? markHtml(cur) : ''}</div>`
      : `<div class="noshot">No screenshot captured before this step.</div>`;
    return `<div class="tl">
      <div class="timeline-list">${failureSummary}${selfHealSummary}${controls}${hasSteps ? stepsHtml : `<div class="steps">${stepsHtml}</div>`}</div>
      <div class="preview">
        <div class="deviceplayer${(cell || shot) ? '' : ' empty'}">
          ${pane}
          <div class="pvctl" aria-label="Frame controls">
            <button class="btn transport" id="prev" aria-label="Previous frame" title="Previous frame"${pos <= 0 ? ' disabled' : ''}><span class="transporticon direction" aria-hidden="true"></span></button>
            <button class="btn play transport" id="tlplay" aria-label="${st.playing ? 'Pause' : 'Play'} timeline" title="${st.playing ? 'Pause' : 'Play'} timeline">${st.playing ? '<span class="transporticon pauseicon" aria-hidden="true"></span>' : '<svg class="transporticon playicon" viewBox="0 0 24 24" aria-hidden="true"><path d="M7.7 5.8c0-1.25 1.37-2.02 2.44-1.38l9.18 5.52c1.03.62 1.03 2.11 0 2.73l-9.18 5.52c-1.07.64-2.44-.13-2.44-1.38Z" fill="currentColor"/></svg>'}</button>
            <button class="btn transport" id="next" aria-label="Next frame" title="Next frame"${pos >= D.trace.length - 1 ? ' disabled' : ''}><span class="transporticon direction" aria-hidden="true"></span></button>
          </div>
        </div>
      </div>
    </div>`;
  };

  const fmtN = (n) => n == null ? '—' : n.toLocaleString();
  const fmtCost = (c) => c == null ? '—' : c === 0 ? '$0.000000' : c < 0.000001 ? '<$0.000001' : '$' + c.toFixed(6);
  const decisionOf = (r) => { const t = (r.response || []).find((p) => p.kind === 'tool'); return t ? t.tool : ((r.response || []).find((p) => p.kind === 'text') ? 'text reply' : r.label); };

  const viewPage = (title, meta, body, className = '') => `<section class="viewpage${className ? ` ${className}` : ''}">
    <div class="viewhead"><h2 class="viewtitle">${esc(title)}</h2>${meta ? `<span class="viewmeta">${esc(meta)}</span>` : ''}</div>
    <div class="viewbody">${body}</div>
  </section>`;

  const renderLlm = () => {
    if (!D.llm.length) return viewPage('LLM', '', `<div class="empty">This run has no LLM request logs.</div>`);
    const totals = D.llm.reduce((a, r) => ({ i: a.i + (r.inputTokens || 0), o: a.o + (r.outputTokens || 0), c: a.c + (r.totalCost || 0), k: a.k + (r.cacheReadTokens || 0), d: a.d + (r.durationMs || 0) }), { i: 0, o: 0, c: 0, k: 0, d: 0 });
    const list = D.llm.map((r, i) => `<div class="callrow${i === st.llmSel ? ' sel' : ''}" data-llm="${i}" role="button" tabindex="0"${i === st.llmSel ? ' aria-current="true"' : ''}>
        <div class="d">${i + 1}. ${esc(decisionOf(r))}</div>
        <div class="m">in ${fmtN(r.inputTokens)} · out ${fmtN(r.outputTokens)}${r.durationMs ? ' · ' + (r.durationMs / 1000).toFixed(1) + 's' : ''}</div>
      </div>`).join('');
    const r = D.llm[st.llmSel] || D.llm[0];
    const respParts = (r.response || []).length ? (r.response || []).map((p) => p.kind === 'tool'
      ? `${p.reasoning ? `<div class="reason">${esc(p.reasoning)}</div>` : ''}<div class="tool mono">⚙ ${esc(p.tool)}</div>${p.args && p.args !== '{}' ? `<pre>${esc(p.args)}</pre>` : ''}`
      : `<div class="reason">${esc(p.text)}</div>`).join('') : '<div style="color:var(--sub);font-size:12px">No response captured for this call.</div>';
    const detail = `<div class="card" style="display:flex;gap:16px;flex-wrap:wrap;align-items:baseline">
        <span style="font-weight:700">Call ${st.llmSel + 1} <span style="color:var(--sub);font-weight:500">of ${D.llm.length}</span></span>
        <span style="color:var(--sub);font-size:11.5px">${esc(r.model)}</span>
        <span style="color:var(--sub);font-size:11.5px">in ${fmtN(r.inputTokens)}${r.cacheReadTokens ? ' (' + fmtN(r.cacheReadTokens) + ' cached)' : ''} · out ${fmtN(r.outputTokens)}</span>
        ${r.totalCost != null ? `<span style="color:var(--sub);font-size:11.5px">${fmtCost(r.totalCost)}</span>` : ''}
      </div>
      ${r.instructions ? `<div style="margin:10px 2px 0;font-size:13px;font-weight:600">${esc(r.instructions)}</div>` : ''}
      <div class="resp"><div class="h">Assistant response</div>${respParts}</div>`;
    return viewPage('LLM', `${D.llm.length} call${D.llm.length === 1 ? '' : 's'}`, `<div class="llm">
      <div><div class="card"><div style="font-size:12px;font-weight:600;color:var(--sub)">Session totals · ${D.llm.length} calls</div>
        <div class="totals"><div><div class="n">${fmtN(totals.i)}</div><div class="t">input tokens</div></div>
        <div><div class="n">${fmtN(totals.o)}</div><div class="t">output tokens</div></div>
        <div><div class="n">${fmtCost(totals.c)}</div><div class="t">total cost</div></div>
        ${totals.k ? `<div><div class="n">${fmtN(totals.k)} <span style="font-weight:500;color:var(--sub)">(${Math.round((totals.k / (totals.i || 1)) * 100)}%)</span></div><div class="t">cached input</div></div>` : ''}
        ${totals.d ? `<div><div class="n">${(totals.d / D.llm.length / 1000).toFixed(1)}s</div><div class="t">avg response</div></div>` : ''}</div></div>
        <div style="margin-top:12px">${list}</div></div>
      <div>${detail}</div>
    </div>`);
  };

  // Thumbnail width steps for the lightbox zoom control. The grid packs as many columns of the
  // selected width as fit (auto-fill), so stepping the width is what decides shots-per-row — and
  // a run with only a couple of screenshots keeps them thumbnail-sized instead of stretching them
  // across the whole page.
  const GAL_ZOOM_SIZES = [140, 190, 260, 360, 500];

  // Screenshot lightbox: default to the final captured frame for each authored step so the view is
  // a concise visual summary. The optional expanded mode preserves access to every tool-level frame.
  const renderLightbox = () => {
    const entries = groupTrace().flatMap((group) => {
      const shots = [group.header, ...group.items].filter((t) => t && t.screenshotFile && D.shots[t.screenshotFile]);
      const selected = st.lightboxAll ? shots : shots.slice(-1);
      return selected.map((trace) => ({ trace, group }));
    });
    const cells = entries.map(({ trace, group }) => {
      const trailhead = Boolean(group.header && group.header.trailhead);
      const token = trailhead ? 'TRAILHEAD' : (group.num ? `STEP ${group.num}` : 'RUN');
      const label = (group.header && group.header.label) || trace.label;
      const tool = trace !== group.header ? trace.label : '';
      return `<button type="button" class="galcell" data-lightbox-step="${trace.i}">
        <div class="galshot" data-shot="${esc(trace.screenshotFile)}" data-shot-token="${esc(token)}" data-shot-label="${esc(label)}"${tool ? ` data-shot-tool="${esc(tool)}"` : ''} role="button" tabindex="0"><img src="${D.shots[trace.screenshotFile]}" alt="${esc(label)}" /></div>
        <div class="cap"><span class="galchip${trailhead ? ' trailhead' : ''}">${token}</span><span class="gallabel">${esc(label)}</span>${tool ? `<span class="galtool">${esc(tool)}</span>` : ''}</div>
      </button>`;
    }).join('');
    const zoom = `<div class="lightboxzoom" role="group" aria-label="Thumbnail size"><button type="button" class="lightboxzoombtn" data-gal-zoom="-1" aria-label="Smaller thumbnails" title="Smaller thumbnails"${st.lightboxZoom <= 0 ? ' disabled' : ''}>−</button><button type="button" class="lightboxzoombtn" data-gal-zoom="1" aria-label="Larger thumbnails" title="Larger thumbnails"${st.lightboxZoom >= GAL_ZOOM_SIZES.length - 1 ? ' disabled' : ''}>+</button></div>`;
    const toggle = `<div class="lightboxtoolbar"><button class="lightboxtoggle" type="button" role="switch" id="lightboxmode" aria-checked="${st.lightboxAll}"><span class="lightboxtoggletrack" aria-hidden="true"><span class="lightboxtogglethumb"></span></span><span>Show all</span></button>${zoom}</div>`;
    const meta = entries.length ? `${entries.length} ${st.lightboxAll ? 'screenshots' : `step frame${entries.length === 1 ? '' : 's'}`}` : '';
    return viewPage('Lightbox', meta, `${toggle}${cells ? `<div class="gal" style="--galsize:${GAL_ZOOM_SIZES[st.lightboxZoom]}px">${cells}</div>` : `<div class="empty">No screenshots captured for this run.</div>`}`, 'lightboxpage');
  };

  // Severity class for a logcat line. Reads the logcat level token (`E/Tag…` brief form or a
  // standalone `E` column in threadtime form), falling back to crash keywords. Heuristic, but
  // tighter than a bare letter match — used only for row coloring.
  const logLevelClass = (l) => {
    const m = l.match(/(?:^|\s)([VDIWEF])[\/\s]/);
    const lvl = m ? m[1] : '';
    if (lvl === 'E' || lvl === 'F' || /\b(FATAL|ANR)\b|Exception/.test(l)) return 'e';
    if (lvl === 'W' || /\bWARN\b/.test(l)) return 'w';
    return '';
  };

  // Empty-state note for a Logs tab whose payload may be compressed: nothing captured, still
  // decompressing, or inflation failed (the payload needs DecompressionStream support to inflate).
  const gzEmptyNote = (label, gz, missingNote) =>
    !gz ? missingNote
    : logsInflater.inflight.has(D) ? `Decompressing ${label}…`
    : `Could not decompress the ${label} (requires DecompressionStream support).`;

  const renderDevice = () => {
    const deviceLog = sessionDeviceLog(D);
    if (!deviceLog) {
      return viewPage('Device log', '', `<div class="empty">${gzEmptyNote('device log', D.deviceLogGz, 'No device log captured.')}</div>`);
    }
    const lines = deviceLog.split('\n');
    const html = lines.map((l) => `<div class="ln ${logLevelClass(l)}">${esc(l)}</div>`).join('');
    return viewPage('Device log', `${lines.length} lines`, `
      <div class="lfilter" id="dlbar"><input id="dlq" type="search" placeholder="Filter log lines…" />
        <button class="evchip on" data-lvl="">All</button>
        <button class="evchip" data-lvl="w">Warn+</button>
        <button class="evchip" data-lvl="e">Errors</button>
        <span class="count" id="dlcount"></span></div>
      <div class="logpane" id="dlpane">${html}</div>`, 'logview');
  };

  const renderNetwork = () => {
    const network = sessionNetwork(D);
    if (!network || !network.length) {
      return viewPage('Network', '', `<div class="empty">${gzEmptyNote('network log', D.networkGz, 'No network activity captured.')}</div>`);
    }
    const rows = network.map((e) => {
      const fail = e.phase === 'FAILED' || (e.statusCode != null && e.statusCode >= 400);
      const status = e.phase === 'FAILED' ? 'FAILED' : (e.statusCode != null ? String(e.statusCode) : (e.phase === 'REQUEST_START' ? '→' : ''));
      const dur = e.durationMs != null ? ` ${e.durationMs}ms` : '';
      return `<div class="ln ${fail ? 'e' : ''}"><span>${esc(e.method)}</span><span class="m">${esc(status)}${esc(dur)}</span><span>${esc(e.urlPath)}</span></div>`;
    }).join('');
    return viewPage('Network', `${network.length} events`, `
      <div class="lfilter" id="nlbar"><input id="nlq" type="search" placeholder="Filter by method, path, status…" />
        <button class="evchip on" data-lvl="">All</button>
        <button class="evchip" data-lvl="e">Failed</button>
        <span class="count" id="nlcount"></span></div>
      <div class="logpane net" id="nlpane">${rows}</div>`, 'logview');
  };

  // Shared text + severity filtering for the device/network log panes. Filters rows in place
  // (display:none) rather than re-rendering, so typing keeps input focus and stays fast on
  // thousands of lines. Severity rides on the row's coloring class: 'e' = error, 'w' = warning.
  const wireLogFilter = (paneId: string, inputId: string, barId: string, countId: string) => {
    const pane = document.getElementById(paneId);
    if (!pane) return;
    const input = document.getElementById(inputId) as HTMLInputElement | null;
    const countEl = document.getElementById(countId);
    const chips = Array.from(root.querySelectorAll<HTMLElement>(`#${barId} [data-lvl]`));
    let lvl = '';
    const apply = () => {
      const q = ((input && input.value) || '').toLowerCase();
      let shown = 0;
      for (const r of Array.from(pane.children) as HTMLElement[]) {
        const okLvl = !lvl || (lvl === 'e' ? r.classList.contains('e') : (r.classList.contains('e') || r.classList.contains('w')));
        const okQ = !q || r.textContent.toLowerCase().indexOf(q) >= 0;
        const on = okLvl && okQ;
        r.style.display = on ? '' : 'none';
        if (on) shown++;
      }
      if (countEl) countEl.textContent = `${shown} shown`;
    };
    if (input) input.oninput = apply;
    chips.forEach((c) => c.onclick = () => { lvl = c.dataset.lvl; chips.forEach((x) => x.classList.toggle('on', x === c)); apply(); });
    apply();
  };

  // Event payloads are embedded untruncated, so their bodies are rendered EMPTY and filled the
  // first time their <details> opens — building tens of MB of payload HTML up front would freeze
  // the tab. 'toggle' doesn't bubble, so wireLazyTimelineBodies listens in the capture phase on
  // its pane; the pane is recreated on every render, so listeners never stack.
  //
  // fillLazyBody is the shared fill: a formatted row gets its full row body (.fmtbody), a generic
  // event gets its pretty-printed payload (pre).
  const fillLazyBody = (el, row, event) => {
    if (row) {
      const body = el.querySelector('.fmtbody');
      if (!body) return;
      body.innerHTML = formattedRowBody(row);
    } else if (event) {
      const pre = el.querySelector('pre');
      if (!pre) return;
      pre.textContent = eventPrettyText(event);
    } else {
      return;
    }
    el.dataset.lazyfilled = '1';
  };

  const wireLazyTimelineBodies = () => {
    const list = root.querySelector('.timeline-list') as any;
    if (!list || !list.addEventListener) return;
    list.addEventListener('toggle', (e) => {
      const el = e.target as any;
      if (!el || !el.open || !el.dataset || el.dataset.lazyfilled || el.dataset.lazykey == null) return;
      const entry = tlEventByKey.get(el.dataset.lazykey);
      if (entry) fillLazyBody(el, entry.row, entry);
    }, true);
  };

  // Video playback over the embedded sprite sheet — pure CSS background-position scrubbing, no decode
  // step. Frame layout + range are precomputed (D.video); wireVideo() drives play/seek.
  const renderVideo = () => {
    const v = D.video;
    if (!v) return viewPage('Video', '', `<div class="empty">No video frames captured for this run.</div>`);
    const total = v.endFrame - v.startFrame + 1;
    // Controls ABOVE the frame (the frame is device-tall; controls below it would sit under the
    // fold), frame height-capped to the viewport — matching the legacy player's always-visible
    // transport with elapsed/total time and a playback-speed toggle.
    return viewPage('Video', `${total} frame${total === 1 ? '' : 's'} · ${v.fps}fps`, `<div class="video">
      <div class="vctl">
        <button class="btn play" id="vplay">▶ Play</button>
        <input type="range" id="vseek" min="0" max="${total - 1}" value="0" />
        <span class="count" id="vpos">0.0s / ${(total / v.fps).toFixed(1)}s</span>
        <button class="btn" id="vspeed" title="Playback speed">${st.vSpeed}×</button>
      </div>
      <div class="vframe" id="vframe" style="${spriteAspect ? `aspect-ratio:${spriteAspect};` : ''}background-image:url('${spriteUrl(v, 0)}')"></div>
    </div>`);
  };

  const renderInfo = () => {
    const m = D.meta;
    // Consumer-injected `config.metadata` key/values render after the built-in rows, keys as-is.
    const rows = [['Target', m.target], ['App version', m.appVersion], ['Platform', m.platform], ['Device type', m.deviceType], ['Device', m.device], ['Bundle / package ID', m.appId], ['Trail', m.trailId], ['Total duration', m.duration], ['Steps', m.steps ? String(m.steps) : null], ['Ran', m.ranAt], ['Build', m.buildNumber], ['Commit', m.commitSha], ['Branch', m.branch], ...Object.entries(m.metadata || {})]
      .filter(([, v]) => v).map(([k, v]) => `<div class="r"><span class="k">${esc(k)}</span><span class="v">${esc(v)}</span></div>`).join('');
    return viewPage('Run details', '', `<div>
      ${m.cmd ? `<section class="infosection"><div class="eyebrow">Rerun this in the CLI</div><div class="cmd"><pre class="mono" id="cmd">${esc(m.cmd)}</pre><button class="btn" id="copycmd">Copy</button></div></section>` : ''}
      <section class="infosection"><div class="rows">${rows}</div></section>
    </div>`);
  };

  const yamlHighlightTarget = () => {
    const selected = D.trace.find((t) => t.i === st.step);
    if (!selected) return null;
    const groups = groupTrace();
    const group = groups.find((g) => g.header === selected || g.items.indexOf(selected) >= 0);
    const header = group && group.header;
    const tone = selected.selfHealSource || (header && header.selfHeal) ? 'selfheal' : (!selected.ok || (header && !header.ok)) ? 'failed' : '';
    if (!tone || !header) return null;
    const toolTerms = [selected.label, selected.tool || '', header.selfHealTool || '']
      .flatMap((term) => String(term).split(/\s{2,}|:\s*/))
      .map((term) => term.trim()).filter((term) => term.length >= 3);
    return { tone, stepLabel: header.label, toolTerms };
  };

  const highlightedYaml = (text) => {
    const target = yamlHighlightTarget();
    if (!text || !target) return esc(text || '');
    const lines = text.replace(/\r\n/g, '\n').split('\n');
    const lowerStep = target.stepLabel.toLowerCase();
    let start = lines.findIndex((line) => line.toLowerCase().indexOf(lowerStep) >= 0 && /(?:^|\s)step\s*:/.test(line));
    if (start < 0) start = lines.findIndex((line) => line.toLowerCase().indexOf(lowerStep) >= 0);
    if (start < 0) return esc(text);
    const startIndent = (lines[start].match(/^\s*/) || [''])[0].length;
    let end = lines.length;
    for (let i = start + 1; i < lines.length; i++) {
      const indent = (lines[i].match(/^\s*/) || [''])[0].length;
      if (indent <= startIndent && (/^\s*(?:-\s*)?step\s*:/.test(lines[i]) || /^[a-zA-Z0-9_-]+\s*:/.test(lines[i]))) { end = i; break; }
      if (/^\s*-\s*step\s*:/.test(lines[i]) && indent <= startIndent) { end = i; break; }
    }
    const lowerTerms = target.toolTerms.map((term) => term.toLowerCase());
    const toolLine = lines.findIndex((line, i) => i >= start && i < end && lowerTerms.some((term) => line.toLowerCase().indexOf(term) >= 0));
    let toolEnd = toolLine >= 0 ? toolLine + 1 : -1;
    if (toolLine >= 0) {
      const toolIndent = (lines[toolLine].match(/^\s*/) || [''])[0].length;
      for (let i = toolLine + 1; i < end; i++) {
        const indent = (lines[i].match(/^\s*/) || [''])[0].length;
        if (lines[i].trim() && indent <= toolIndent && /^\s*-?\s*[A-Za-z0-9_]+\s*:/.test(lines[i])) { toolEnd = i; break; }
        toolEnd = i + 1;
      }
    }
    return lines.map((line, i) => {
      const inStep = i >= start && i < end;
      const inTool = toolLine >= 0 && i >= toolLine && i < toolEnd;
      const cls = inTool ? `yamlmark tool ${target.tone}` : inStep ? `yamlmark ${target.tone}` : '';
      return `<span class="${cls || 'yamlline'}">${esc(line) || ' '}</span>`;
    }).join('');
  };

  const yamlColumn = (title, sub, text, id) => `<div class="yamlcol"><div class="yamlcolhead"><div class="eyebrow">${title} · ${sub}</div>${text ? `<button class="btn yamlcopy" id="copy-${id}">Copy</button>` : ''}</div>${text
      ? `<div class="cmd"><pre class="mono yaml" id="${id}">${highlightedYaml(text)}</pre></div>`
      : `<div class="empty">Not available for this run.</div>`}</div>`;

  const renderConfig = () => {
    const original = yamlRootSection(D.originalYaml, 'config');
    const recorded = yamlRootSection(D.recordingYaml, 'config');
    if (!original && !recorded) return viewPage('Config', '', `<div class="empty">No config captured for this run.</div>`);
    return viewPage('Config', 'Authored and recorded', `<div class="yamlcompare">${yamlColumn('Original config', 'authored inputs', original, 'config-original')}${yamlColumn('Recorded config', 'run snapshot', recorded, 'config-recorded')}</div>`);
  };

  const renderRecording = () => {
    if (!D.recordingYaml && !D.originalYaml) return viewPage('Recording', '', `<div class="empty">No trail YAML captured for this run.</div>`);
    return viewPage('Recording', 'Original and recorded YAML', `<div class="yamlcompare">${yamlColumn('Original trail', 'authored intent', D.originalYaml, 'original-yaml')}${yamlColumn('Recorded run', 'what actually ran', D.recordingYaml, 'recorded-yaml')}</div>`);
  };

  const isPass = (s) => { const v = String((s.meta && s.meta.status) || '').toLowerCase(); return v === 'passed' || v === 'success'; };
  const isFail = (s) => { const v = String((s.meta && s.meta.status) || '').toLowerCase(); return v === 'failed' || v === 'error'; };
  const indexOutcome = (s) => isFail(s) ? 'failed' : (s.meta && s.meta.selfHeal) ? 'selfheal' : isPass(s) ? 'passed' : 'other';
  const indexOutcomeLabel = (outcome) => outcome === 'selfheal' ? 'self-healed' : outcome;
  // The well-known `owner` metadata key: a run's owning group, rendered as the row subtitle and
  // the section key for the "Owner" sort.
  const runOwner = (s) => String((s.meta && s.meta.metadata && s.meta.metadata.owner) || '').trim();
  const runPlatform = (s) => String((s.meta && s.meta.platform) || '').trim();
  // A run's device identity, in two flavors. The INSTANCE leg (`meta.device` — a simulator UDID or
  // adb serial) names one concrete device and keys retry groups: two runs on two instances are
  // independent runs, never each other's attempt history. The LANE leg prefers the stable device
  // classifier (`meta.deviceType` — "iphone", "phone", "tablet") and keys matrix columns, so a
  // build sharded across N interchangeable simulators is ONE column instead of N mostly-dashed
  // ones (every CI shard creates a fresh UDID). Either leg falls back to the other when a payload
  // carries only one of them.
  const runDeviceInstance = (s) => String((s.meta && (s.meta.device || s.meta.deviceType)) || '');
  const runLane = (s) => String((s.meta && (s.meta.deviceType || s.meta.device)) || '');
  // Real step / tool-call counts come from the trace (traceStepCount/traceToolCallCount in
  // run-report-extract — shared with buildMultiReportHtml so the run list and detail view always
  // agree). Chunked index stubs precompute both counts at build time (s.stepCount/s.toolCallCount)
  // since the run list renders before any trace is hydrated; older payloads without either keep
  // meta.steps as the tool-call fallback.
  const isLlmTurn = isLlmTurnRow;
  const runStepCount = (s) => { const trace = s.trace || []; return trace.length ? traceStepCount(trace) : (s.stepCount != null ? s.stepCount : null); };
  const runToolCallCount = (s) => {
    const trace = s.trace || [];
    if (trace.length) return traceToolCallCount(trace);
    if (s.toolCallCount != null) return s.toolCallCount;
    return s.meta && s.meta.steps != null ? s.meta.steps : null;
  };
  const runLlmCallCount = (s) => (s.llm || []).length;
  // A mixed-platform report renders one row per trail with a per-platform cell matrix; a
  // single-platform report keeps the flat per-run rows (the header already names the platform once).
  const mixedPlatforms = new Set(SESSIONS.map(runPlatform).filter(Boolean)).size > 1;
  const allPlatforms = Array.from(new Set(SESSIONS.map(runPlatform))).sort((a, b) => Number(!a) - Number(!b) || a.localeCompare(b));
  const runTarget = (s) => String((s.meta && s.meta.target) || '').trim();
  const allTargets = Array.from(new Set(SESSIONS.map(runTarget).filter(Boolean))).sort((a, b) => a.localeCompare(b));
  const platformLabel = (platform) => platform || 'other';
  // A matrix column is a platform+lane pair, so two device classes on the same platform (an iPhone
  // and an iPad, a phone and a tablet) stay separate cells instead of masquerading as each other's
  // retry history, while interchangeable instances of one class share a column. encodeURIComponent
  // keeps the key collision-free (':' never appears in its output) and safe to round-trip through
  // the expansion chevron's data attribute; a device-less run keys on the platform alone.
  const matrixColKey = (s) => {
    const lane = runLane(s);
    return lane ? `${encodeURIComponent(runPlatform(s))}:${encodeURIComponent(lane)}` : encodeURIComponent(runPlatform(s));
  };
  // Column order: alphabetical by platform then lane, platform-less runs last. The lane qualifies
  // the label only when a platform ran on more than one lane.
  const matrixColumns = () => {
    const byKey = new Map<string, any>();
    SESSIONS.forEach((s) => {
      const key = matrixColKey(s);
      if (!byKey.has(key)) byKey.set(key, { key, platform: runPlatform(s), lane: runLane(s) });
    });
    const cols: any[] = Array.from(byKey.values()).sort((a: any, b: any) => Number(!a.platform) - Number(!b.platform) || a.platform.localeCompare(b.platform) || a.lane.localeCompare(b.lane));
    const perPlatform = new Map<string, number>();
    cols.forEach((col: any) => perPlatform.set(col.platform, (perPlatform.get(col.platform) || 0) + 1));
    cols.forEach((col: any) => { col.label = col.lane && (perPlatform.get(col.platform) as number) > 1 ? `${platformLabel(col.platform)} · ${col.lane}` : platformLabel(col.platform); });
    return cols;
  };
  const sharedMeta = (key) => {
    const first = SESSIONS[0] && SESSIONS[0].meta && SESSIONS[0].meta[key];
    if (first == null || first === '') return null;
    return SESSIONS.every((s) => s.meta && String(s.meta[key] || '') === String(first)) ? first : null;
  };
  const dateLabel = (value) => {
    const raw = String(value || '').trim();
    return raw.match(/^\d{4}-\d{2}-\d{2}/)?.[0] || raw.match(/^[A-Za-z]{3,9} \d{1,2}, \d{4}/)?.[0] || null;
  };

  const indexRunDate = () => {
    const runDates = SESSIONS.map((s) => dateLabel(s.meta && s.meta.ranAt)).filter(Boolean);
    return runDates.length === SESSIONS.length && runDates.every((date) => date === runDates[0])
      ? runDates[0]
      : (runDates.length ? null : dateLabel(generatedAt));
  };

  const renderIndexHeader = () => {
    const platformEntry = mixedPlatforms ? ['Platforms', allPlatforms.filter(Boolean).join(', ')] : ['Platform', sharedMeta('platform')];
    const targetEntry = allTargets.length > 1 ? ['Targets', allTargets.join(', ')] : ['Target', sharedMeta('target') || allTargets[0]];
    const meta = [targetEntry, ['App version', sharedMeta('appVersion')], platformEntry, ['Bundle / package ID', sharedMeta('appId')]]
      .filter(([, value]) => value).map(([label, value]) => `<div><div class="k">${label}</div><div class="v">${esc(value)}</div></div>`).join('');
    const buildUrl = safeHref(sharedMeta('buildUrl'));
    const commitUrl = safeHref(sharedMeta('commitUrl'));
    const buildNumber = sharedMeta('buildNumber');
    const commitSha = sharedMeta('commitSha');
    const links = `${buildUrl ? `<a class="quietlink" href="${esc(buildUrl)}" target="_blank" rel="noopener">${esc(buildNumber ? `Build ${buildNumber}` : 'Build')} ↗</a>` : ''}${commitUrl ? `<a class="quietlink" href="${esc(commitUrl)}" target="_blank" rel="noopener">${esc(commitSha ? String(commitSha).slice(0, 8) : 'Commit')} ↗</a>` : ''}`;
    return `<header class="indexheader"><div class="indexshell">
      <div class="title-row indexheadrow"><h1>Trailblaze Report</h1><div class="indexheadactions">${renderThemeToggle()}${shareLinkAvailable() ? '<button class="btn headeraction" type="button" id="copylink">Copy link</button>' : ''}<button class="btn headeraction" type="button" id="exportall">Share</button></div></div>
      ${(meta || links) ? `<div class="indexcontext"><div class="meta indexmeta">${meta}</div>${links ? `<div class="indexlinks">${links}</div>` : ''}</div>` : ''}
      </div>
    </header>`;
  };

  const renderIndexSummary = () => {
    // Count what the index shows: matrix rows (one per trail) on mixed-platform reports, per-run
    // retry groups otherwise — so the footer tallies always match the section counts.
    const groups = mixedPlatforms ? indexMatrixRows() : indexRunGroups();
    const outcomes = groups.map((group) => group.outcome);
    const pass = outcomes.filter((outcome) => outcome === 'passed').length;
    const selfHeal = outcomes.filter((outcome) => outcome === 'selfheal').length;
    const fail = outcomes.filter((outcome) => outcome === 'failed').length;
    const other = outcomes.filter((outcome) => outcome === 'other').length;
    return `<div class="idxsummary"><span class="stat fail"><strong>${fail}</strong> failed</span><span class="stat selfheal"><strong>${selfHeal}</strong> self-healed</span><span class="stat pass"><strong>${pass}</strong> passed</span>${other ? `<span class="stat"><strong>${other}</strong> other</span>` : ''}</div>`;
  };

  const durationMs = (value) => {
    const raw = String(value || '').trim().toLowerCase();
    if (!raw) return null;
    const clock = raw.match(/^(?:(\d+):)?(\d+):(\d+(?:\.\d+)?)$/);
    if (clock) return Math.round(((Number(clock[1] || 0) * 3600) + (Number(clock[2]) * 60) + Number(clock[3])) * 1000);
    let total = 0;
    let matched = false;
    const token = /(\d+(?:\.\d+)?)\s*(ms|h|m|s)\b/g;
    let part;
    while ((part = token.exec(raw)) != null) {
      matched = true;
      const amount = Number(part[1]);
      total += part[2] === 'h' ? amount * 3_600_000 : part[2] === 'm' ? amount * 60_000 : part[2] === 's' ? amount * 1000 : amount;
    }
    return matched ? Math.round(total) : null;
  };

  const aggregateDurationLabel = () => {
    const durations = SESSIONS.map((s) => durationMs(s.meta && s.meta.duration)).filter((value) => value != null);
    if (!durations.length || durations.length !== SESSIONS.length) return '—';
    const totalSeconds = Math.round(durations.reduce((sum, value) => sum + value, 0) / 1000);
    const hours = Math.floor(totalSeconds / 3600);
    const minutes = Math.floor((totalSeconds % 3600) / 60);
    const seconds = totalSeconds % 60;
    return hours ? `${hours}h ${minutes}m ${seconds}s` : minutes ? `${minutes}m ${seconds}s` : `${seconds}s`;
  };

  const llmTokensLabel = (calls) => {
    if (!calls.length) return fmtN(0);
    if (calls.some((call) => call.inputTokens == null || call.outputTokens == null || String(call.inputTokens).trim() === '' || String(call.outputTokens).trim() === '' || !Number.isFinite(Number(call.inputTokens)) || !Number.isFinite(Number(call.outputTokens)))) return '—';
    return fmtN(calls.reduce((sum, call) => sum + Number(call.inputTokens) + Number(call.outputTokens), 0));
  };

  // Null when any call lacks a finite cost — a partial sum would understate real spend.
  const llmCostTotal = (calls) => calls.some((call) => call.totalCost == null || String(call.totalCost).trim() === '' || !Number.isFinite(Number(call.totalCost))) ? null : calls.reduce((sum, call) => sum + Number(call.totalCost), 0);
  const llmCostLabel = (calls) => { const total = llmCostTotal(calls); return total == null ? '—' : fmtCost(total); };
  // Compact cost for index rows; sub-dollar runs (the common case) keep 4 decimals so small
  // per-trail costs stay comparable at a glance.
  const fmtCostShort = (c) => c == null ? '—' : c === 0 ? '$0.00' : c < 1 ? `$${c.toFixed(4)}` : `$${c.toFixed(2)}`;

  const aggregateLlmCostLabel = () => llmCostLabel(SESSIONS.flatMap((s) => s.llm || []));

  const renderIndexMetrics = () => {
    const calls = SESSIONS.flatMap((s) => s.llm || []);
    return `<div class="indexmetrics"><span class="detailfooteritem"><span class="k">Total duration</span><span class="v">${esc(aggregateDurationLabel())}</span></span><span class="detailfooteritem"><span class="k">Total tokens</span><span class="v">${esc(llmTokensLabel(calls))}</span></span><span class="detailfooteritem"><span class="k">Total LLM cost</span><span class="v">${esc(aggregateLlmCostLabel())}</span></span></div>`;
  };

  const indexGroupKey = (s, index) => {
    const m = (s && s.meta) || {};
    // Only an explicit trail identity is safe to coalesce. Older exports without trailId can carry
    // independent same-title runs; keeping them separate avoids hiding a failure as retry history.
    if (!m.trailId) return `session:${index}`;
    // The device leg is the INSTANCE id, not the column's lane: attempts only ever coalesce
    // within one device, so a second simulator's run can never slip into another's attempt
    // history. indexMatrixRows folds a lane's instance groups into one column afterwards.
    return [m.trailId, m.target || '', runPlatform(s), runDeviceInstance(s)].join('\u0001');
  };

  const attemptTime = (attempt) => {
    const parsed = Date.parse(String(attempt.s.meta && attempt.s.meta.ranAt || ''));
    return Number.isFinite(parsed) ? parsed : null;
  };

  // Chronological attempt order, falling back to payload order when any attempt lacks a timestamp
  // (a partially dated set can't be ordered by time without inventing a position for the undated
  // ones). Shared by retry groups and by the lane cells that fold several groups together.
  const sortAttempts = (attempts) => {
    const allDated = attempts.every((attempt) => attemptTime(attempt) != null);
    return attempts.slice().sort((a, b) => allDated ? attemptTime(a) - attemptTime(b) || a.i - b.i : a.i - b.i);
  };

  const indexRunGroups = () => {
    const byTest = new Map();
    SESSIONS.forEach((s, i) => {
      const key = indexGroupKey(s, i);
      if (!byTest.has(key)) byTest.set(key, { key, first: i, attempts: [] });
      byTest.get(key).attempts.push({ s, i, outcome: indexOutcome(s) });
    });
    return Array.from(byTest.values()).map((group) => {
      const attempts = sortAttempts(group.attempts);
      const latest = attempts[attempts.length - 1];
      return { ...group, attempts, latest, outcome: latest.outcome };
    });
  };

  const outcomeRank = { failed: 0, selfheal: 1, passed: 2, other: 3 };
  // Mixed-platform reports coalesce a trail's per-platform runs into one row: a cell per
  // platform+lane column, holding the attempt history of every retry group that ran in that lane
  // (one group in the normal case - a sharded build only sends a trail to a single device per
  // platform). Sessions without an explicit trail identity stay solo rows (same rule as
  // indexGroupKey). The worst cell outcome sections the row — a trail that failed anywhere is a
  // failed trail.
  const indexMatrixRows = () => {
    const byTrail = new Map();
    indexRunGroups().forEach((group) => {
      const m = group.latest.s.meta || {};
      const key = m.trailId ? `trail:${encodeURIComponent(m.trailId)}:${encodeURIComponent(m.target || '')}` : `session:${group.first}`;
      if (!byTrail.has(key)) byTrail.set(key, { key, first: group.first, cells: new Map<string, any>() });
      const row = byTrail.get(key);
      row.first = Math.min(row.first, group.first);
      const colKey = matrixColKey(group.latest.s);
      if (!row.cells.has(colKey)) row.cells.set(colKey, { groups: [] });
      row.cells.get(colKey).groups.push(group);
    });
    return Array.from(byTrail.values()).map((row: any) => {
      // When a lane did hold more than one device, the cell takes the WORST group's outcome and
      // opens that group's latest attempt: the same rule the row applies across its cells, so
      // another device's later pass can never bury a failure the way plain attempt order would.
      row.cells.forEach((cell) => {
        const worst = cell.groups.reduce((acc, group) => outcomeRank[group.outcome] < outcomeRank[acc.outcome] ? group : acc);
        cell.attempts = cell.groups.length > 1 ? sortAttempts(cell.groups.flatMap((group) => group.attempts)) : cell.groups[0].attempts;
        cell.latest = worst.latest;
        cell.outcome = worst.outcome;
      });
      const cells: any[] = Array.from(row.cells.values());
      const outcome = cells.reduce((worst: any, cell: any) => outcomeRank[cell.outcome] < outcomeRank[worst] ? cell.outcome : worst, cells[0].outcome);
      return { ...row, outcome, latest: cells.reduce((last: any, cell: any) => cell.latest.i > last.i ? cell.latest : last, cells[0].latest) };
    });
  };

  // The landing page is grouped by unique trail, not raw session. A retry is attempt history, so
  // the final attempt determines the section while the earlier attempts remain nested beneath it.
  // On mixed-platform reports a trail's per-platform runs share one row (see indexMatrixRows).
  const renderIndex = () => {
    const allRuns = mixedPlatforms ? indexMatrixRows() : indexRunGroups();
    const entryHasRetries = (entry) => mixedPlatforms
      ? Array.from(entry.cells.values()).some((cell: any) => cell.attempts.length > 1)
      : entry.attempts.length > 1;
    // Every LLM call a row paid for: all attempts, and on a matrix row all platforms' cells —
    // the total the row subtitle shows and the Cost sort orders by.
    const entryLlmCalls = (entry) => (mixedPlatforms
      ? Array.from(entry.cells.values()).flatMap((cell: any) => cell.attempts)
      : entry.attempts).flatMap((attempt) => attempt.s.llm || []);
    const ordered = allRuns.sort((a, b) => {
      if (st.runSort === 'grouped') return outcomeRank[a.outcome] - outcomeRank[b.outcome] || Number(entryHasRetries(b)) - Number(entryHasRetries(a)) || a.first - b.first;
      if (st.runSort === 'name') return String(a.latest.s.meta.title || '').localeCompare(String(b.latest.s.meta.title || '')) || a.first - b.first;
      if (st.runSort === 'cost') {
        const aCost = llmCostTotal(entryLlmCalls(a));
        const bCost = llmCostTotal(entryLlmCalls(b));
        // Most expensive first; rows whose cost is unknowable sort last.
        return Number(aCost == null) - Number(bCost == null) || (bCost || 0) - (aCost || 0) || a.first - b.first;
      }
      if (st.runSort === 'owner') {
        const aOwner = runOwner(a.latest.s);
        const bOwner = runOwner(b.latest.s);
        // Alphabetical owner sections, ownerless runs last, names A–Z within a section.
        return Number(!aOwner) - Number(!bOwner) || aOwner.localeCompare(bOwner)
          || String(a.latest.s.meta.title || '').localeCompare(String(b.latest.s.meta.title || '')) || a.first - b.first;
      }
      return a.first - b.first;
    });
    const searchText = (s, outcome) => {
      const status = String((s.meta && s.meta.status) || 'unknown').toLowerCase();
      const outcomeLabel = indexOutcomeLabel(outcome);
      return [s.meta.title, status, outcomeLabel !== status ? outcomeLabel : null, s.meta.platform, s.meta.deviceType, s.meta.device, s.meta.target, s.meta.appId, s.meta.appVersion, s.meta.steps, s.meta.duration, s.meta.ranAt, s.meta.buildNumber, s.meta.commitSha, s.meta.branch, ...Object.values(s.meta.metadata || {})]
        .filter((v) => v != null && v !== '').join(' ').toLowerCase();
    };
    const facts = (pairs) => `<div class="idxfacts">${pairs.map(([label, value]) => `<div class="idxfact"><div class="k">${label}</div><div class="v">${esc(value != null && value !== '' ? value : '—')}</div></div>`).join('')}</div>`;
    const runFacts = (s) => facts([['Duration', s.meta.duration], ['Tools', runToolCallCount(s)], ['LLM', runLlmCallCount(s)]]);
    // Steps + LLM cost under the trail id: steps from the latest attempt's trace, cost summed
    // across every attempt on the row (all platforms) — the same total the Cost sort orders by.
    const entryStats = (entry) => {
      const steps = runStepCount(entry.latest.s);
      const parts = [...(steps != null ? [`${steps} step${steps === 1 ? '' : 's'}`] : []), fmtCostShort(llmCostTotal(entryLlmCalls(entry)))];
      return `<div class="idxstats">${esc(parts.join(' · '))}</div>`;
    };
    const attemptRows = (attempts) => attempts.map((attempt, attemptIndex) => {
      const label = indexOutcomeLabel(attempt.outcome);
      return `<div class="idxattemptrow" data-session="${attempt.i}" data-outcome="${esc(attempt.outcome)}" role="button" tabindex="0" aria-label="Open attempt ${attemptIndex + 1}, ${esc(label)}">
            <span class="idxstatus" aria-label="${esc(label)}" title="${esc(label)}"><span class="idxstatusdot ${esc(attempt.outcome)}" aria-hidden="true"></span></span>
            <div class="idxattemptmain"><span class="idxattemptlabel">Attempt ${attemptIndex + 1}</span><span class="idxattemptstatus ${esc(attempt.outcome)}">${esc(label)}</span></div>
            ${runFacts(attempt.s)}
            <span class="arr" aria-hidden="true">→</span>
          </div>`;
    }).join('');
    const renderRow = (entry) => {
      const { attempts, latest, outcome } = entry;
      const { s, i } = latest;
      const outcomeLabel = indexOutcomeLabel(outcome);
      const search = attempts.map((attempt) => searchText(attempt.s, attempt.outcome)).join(' ');
      // The owner subtitle is redundant inside its own owner section — the section head already says it.
      const owner = st.runSort === 'owner' ? '' : runOwner(s);
      const rowMain = `<div class="idxmain"><div class="nm">${esc(s.meta.title || ('Run ' + (i + 1)))}</div>${owner ? `<div class="idxowner">${esc(owner)}</div>` : ''}${entryStats(entry)}</div>`;
      if (attempts.length > 1) {
        const attemptLabels = attempts.map((attempt) => indexOutcomeLabel(attempt.outcome));
        const attemptDots = attempts.map((attempt, attemptIndex) => `<span class="idxstatusdot ${esc(attempt.outcome)}" aria-hidden="true" title="Attempt ${attemptIndex + 1}: ${esc(attemptLabels[attemptIndex])}"></span>`).join('');
        return `<details class="idxretry" data-run-entry data-search="${esc(search)}"><summary class="idxrow idxretryrow" aria-label="${attempts.length} attempts for ${esc(s.meta.title || ('Run ' + (i + 1)))}">
          <span class="idxretrydots" role="img" aria-label="Attempt history: ${esc(attemptLabels.join(', '))}">${attemptDots}</span>
          ${rowMain}
          ${facts([['Latest', s.meta.duration], ['Attempts', attempts.length]])}
          <span class="idxretrychev" aria-hidden="true"></span>
        </summary><div class="idxattempts">${attemptRows(attempts)}</div></details>`;
      }
      return `<div class="idxrow" data-run-entry data-session="${i}" data-search="${esc(search)}" role="button" tabindex="0">
          <span class="idxstatus" aria-label="${esc(outcomeLabel)}" title="${esc(outcomeLabel)}"><span class="idxstatusdot ${esc(outcome)}" aria-hidden="true"></span></span>
          ${rowMain}
          ${runFacts(s)}
          <span class="arr">→</span>
        </div>`;
    };
    // --- mixed-platform matrix rows ---------------------------------------------------------
    const matrixCols = mixedPlatforms ? matrixColumns() : [];
    const cellKey = (row, col) => `${row.key}:${col.key}`;
    const renderCell = (row, col) => {
      const cell = row.cells.get(col.key);
      if (!cell) return `<div class="idxcell missing"><span class="pk">${esc(col.label)}</span><span class="pv">—</span></div>`;
      const retried = cell.attempts.length > 1;
      const open = retried && st.idxOpen.indexOf(cellKey(row, col)) >= 0;
      const outcomeLabel = indexOutcomeLabel(cell.outcome);
      const duration = cell.latest.s.meta.duration;
      // The main button always reads latest-outcome dot + duration; the chevron rail — the control
      // that expands the attempt history — previews it as a bare attempt count, so the stats line
      // never shares width with variable-length history (long durations were wrapping mid-value).
      // Per-attempt outcomes live only in the expanded panel.
      const value = `<span class="idxstatusdot ${esc(cell.outcome)}" aria-hidden="true"></span>`;
      const chev = retried ? `<button class="idxcellchev${open ? ' open' : ''}" type="button" data-cell-toggle="${esc(cellKey(row, col))}" aria-expanded="${open}" aria-label="${open ? 'Hide' : 'Show'} ${cell.attempts.length} ${esc(col.label)} attempts"><span class="idxcellcount" aria-hidden="true">${cell.attempts.length}</span></button>` : '';
      // The open-latest and expand controls are sibling <button>s inside a plain wrapper — nesting
      // an interactive chevron inside a role="button" cell would be invalid HTML (two tab stops
      // with ambiguous activation for keyboard and screen-reader users).
      const tools = runToolCallCount(cell.latest.s);
      return `<div class="idxcell ${esc(cell.outcome)}${retried ? ' retried' : ''}"><button class="idxcellopen" type="button" data-session="${cell.latest.i}" aria-label="Open latest ${esc(col.label)} run, ${esc(outcomeLabel)}"><span class="pk">${esc(col.label)}</span><span class="pcounts">${tools != null ? `${tools} tool${tools === 1 ? '' : 's'}` : ''}</span><span class="pv">${value}${duration ? `<span class="pvtxt">${esc(duration)}</span>` : ''}</span><span class="pcounts">${runLlmCallCount(cell.latest.s)} LLM</span></button>${chev}</div>`;
    };
    const renderMatrixRow = (row) => {
      const title = row.latest.s.meta.title || ('Run ' + (row.latest.i + 1));
      const owner = st.runSort === 'owner' ? '' : runOwner(row.latest.s);
      const search = Array.from(row.cells.values()).flatMap((cell: any) => cell.attempts.map((attempt) => searchText(attempt.s, attempt.outcome))).join(' ');
      const cells = matrixCols.map((col) => renderCell(row, col)).join('');
      const openPanels = matrixCols.filter((col) => {
        const cell = row.cells.get(col.key);
        return cell && cell.attempts.length > 1 && st.idxOpen.indexOf(cellKey(row, col)) >= 0;
      });
      const panel = openPanels.length
        ? `<div class="idxattempts idxmatrixattempts">${openPanels.map((col) => `<div class="idxatthead">${esc(col.label)}</div>${attemptRows(row.cells.get(col.key).attempts)}`).join('')}</div>`
        : '';
      return `<div class="idxentry" data-run-entry data-search="${esc(search)}">
          <div class="idxrow idxmatrixrow"><div class="idxmain"><div class="nm">${esc(title)}</div>${owner ? `<div class="idxowner">${esc(owner)}</div>` : ''}${entryStats(row)}</div><div class="idxcells">${cells}</div></div>
          ${panel}</div>`;
    };
    const renderEntry = mixedPlatforms ? renderMatrixRow : renderRow;
    const sectionLabel = { failed: 'Failed', selfheal: 'Self-healed', passed: 'Passed', other: 'Other' };
    // `ordered` is already owner-alphabetized (ownerless last), so distinct owners come out in
    // section order and each section's runs stay name-sorted.
    const ownerSections = () => {
      const groups = new Map();
      ordered.forEach((run) => {
        const owner = runOwner(run.latest.s);
        if (!groups.has(owner)) groups.set(owner, []);
        groups.get(owner).push(run);
      });
      return Array.from(groups, ([owner, runs]) =>
        `<section class="idxsection" data-index-section="owner:${esc(owner)}"><div class="idxsectionhead">${owner ? esc(owner) : 'No owner'} <span class="idxsectioncount">${runs.length}</span></div><div class="idx">${runs.map(renderEntry).join('')}</div></section>`).join('');
    };
    const rows = st.runSort === 'grouped'
      ? ['failed', 'selfheal', 'passed', 'other'].map((outcome) => {
          const runs = ordered.filter((run) => run.outcome === outcome);
          if (!runs.length) return '';
          return `<section class="idxsection" data-index-section="${outcome}"><div class="idxsectionhead ${outcome}">${sectionLabel[outcome]} <span class="idxsectioncount">${runs.length}</span></div><div class="idx">${runs.map(renderEntry).join('')}</div></section>`;
        }).join('')
      : st.runSort === 'owner' ? ownerSections()
      : `<div class="idx">${ordered.map(renderEntry).join('')}</div>`;
    return `<div class="idxfilter">
        <input id="runsearch" type="search" aria-label="Search runs" placeholder="Search runs…" autocomplete="off" />
        <details class="idxsort" id="runsort" data-runsort><summary aria-label="Sort runs" aria-haspopup="listbox"><span>${st.runSort === 'original' ? 'Run order' : st.runSort === 'name' ? 'Name A–Z' : st.runSort === 'owner' ? 'Owner' : st.runSort === 'cost' ? 'Cost' : 'Status groups'}</span><span class="idxsortchev" aria-hidden="true"></span></summary><div class="idxsortmenu" role="listbox" aria-label="Sort runs"><button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'grouped'}" data-run-sort="grouped">Status groups</button><button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'original'}" data-run-sort="original">Run order</button><button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'name'}" data-run-sort="name">Name A–Z</button><button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'cost'}" data-run-sort="cost">Cost</button>${allRuns.some((run) => runOwner(run.latest.s)) ? `<button class="idxsortoption" type="button" role="option" aria-selected="${st.runSort === 'owner'}" data-run-sort="owner">Owner</button>` : ''}</div></details>
      </div>
      <div class="idxsections">${rows}<div class="empty" id="runempty" ${ordered.length ? 'hidden' : ''}>No runs match these filters.</div></div>`;
  };

  const render = (preserveTimelineScroll = false) => {
    const previousTimelineScroll = preserveTimelineScroll ? root.querySelector<HTMLElement>('.timeline-list')?.scrollTop : null;
    const active = preserveTimelineScroll ? document.activeElement as HTMLElement | null : null;
    const focusSelector = active && active.matches('[data-scrub]') ? '[data-scrub]'
      : active && active.matches('[data-step]') ? `[data-step="${active.dataset.step}"]`
      : active && active.matches('[data-tlstream]') ? `[data-tlstream="${active.dataset.tlstream}"]`
      : active && active.matches('[data-tlstreams]') ? `[data-tlstreams="${active.dataset.tlstreams}"]`
      : active && ['prev', 'next', 'tlplay'].indexOf(active.id) >= 0 ? `#${active.id}`
      : null;
    const pageTransition = st.pageTransition;
    st.pageTransition = '';
    root.className = pageTransition ? `page-enter-${pageTransition}` : '';
    if (st.view === 'index') {
      const runDate = indexRunDate();
      root.innerHTML = `
        ${renderIndexHeader()}
        <main><div class="indexshell">${renderIndex()}</div></main>
        <footer class="indexfooter"><div class="indexshell indexfootercontent">${renderIndexSummary()}${renderIndexMetrics()}${runDate ? `<span class="detailfooteritem indexrundate"><span class="k">Run on</span><span class="v">${esc(runDate)}</span></span>` : ''}</div></footer>`;
      wire();
      return;
    }
    if (unhydrated.has(st.session)) {
      // The session's #tb-session chunk hasn't streamed in yet (openSession is awaiting it): hold
      // the detail view with its header + a loading note instead of rendering empty-trace panes.
      const outcome = indexOutcome(D);
      root.innerHTML = `
        <header class="detailheader">
          <div class="title-row detailtitle${MULTI ? '' : ' noback'}">${MULTI ? '<div class="detailedge"><button class="back" type="button" data-back aria-label="All runs" title="All runs"><span class="backarrow" aria-hidden="true">←</span></button></div>' : ''}<div class="runidentity"><span class="badge ${esc(outcome)}">${esc(indexOutcomeLabel(outcome))}</span><h1>${esc((D.meta || {}).title)}</h1></div><div class="detailactions">${renderThemeToggle()}</div></div>
        </header>
        <main><div class="empty">Loading run…</div></main>`;
      wire();
      return;
    }
    const m = D.meta;
    const detailOutcome = indexOutcome(D);
    const detailOutcomeLabel = indexOutcomeLabel(detailOutcome);
    const hasShots = D.trace.some((t) => t.screenshotFile && D.shots[t.screenshotFile]);
    const tabs = [
      ['timeline', 'Timeline'],
      ...(hasShots ? [['lightbox', 'Lightbox']] : []),
      ...(D.video ? [['video', 'Video']] : []),
      ...(D.llm.length ? [['llm', `LLM (${D.llm.length})`]] : []),
      ...(yamlRootSection(D.recordingYaml, 'config') || yamlRootSection(D.originalYaml, 'config') ? [['config', 'Config']] : []),
      ...(D.recordingYaml || D.originalYaml ? [['recording', 'YAML']] : []),
      ...(D.deviceLog || D.deviceLogGz ? [['device', 'Device logs']] : []),
      ...((D.network && D.network.length) || D.networkGz ? [['network', 'Network']] : []),
      ['info', 'Info'],
    ];
    const body = st.tab === 'timeline' ? renderTimeline()
      : st.tab === 'lightbox' ? renderLightbox()
      : st.tab === 'video' ? renderVideo()
      : st.tab === 'llm' ? renderLlm()
      : st.tab === 'config' ? renderConfig()
      : st.tab === 'recording' ? renderRecording()
      : st.tab === 'device' ? renderDevice()
      : st.tab === 'network' ? renderNetwork()
      : renderInfo();
    const shotCount = screenshotEntries(D).length;
    const logsAvailable = hasLogs(D);
    const localPrompt = localRunAgentPrompt(m);
    const exportMenu = `<details class="exportmenu" data-export-menu><summary aria-label="Run and export options" title="Run and export options"><span class="exportdots" aria-hidden="true"><span class="exportdot"></span><span class="exportdot"></span><span class="exportdot"></span></span></summary><div class="exportmenuitems">${shareLinkAvailable() ? '<button class="exportmenuitem" type="button" id="copylinkrun">Copy link</button>' : ''}<button class="exportmenuitem" type="button" id="copylocalprompt"${localPrompt ? '' : ' disabled'}>Copy local run prompt</button><button class="exportmenuitem" type="button" id="exportrun">Export report</button><button class="exportmenuitem" type="button" id="exportscreenshots"${shotCount ? '' : ' disabled'}><span>Export screenshots</span><span class="count">${shotCount}</span></button><button class="exportmenuitem" type="button" id="exportlogs"${logsAvailable ? '' : ' disabled'}>Export logs</button></div></details>`;
    const footerItems = [['Target', m.target], ['App version', m.appVersion], ['Platform', m.platform], ['Device type', m.deviceType], ['Device', m.device], ['Bundle / package', m.appId], ['Total duration', m.duration], ['Tokens used', llmTokensLabel(D.llm || [])], ['LLM cost', llmCostLabel(D.llm || [])]]
      .filter(([, v]) => v != null && v !== '').map(([k, v]) => `<span class="detailfooteritem"><span class="k">${k}</span><span class="v">${esc(v)}</span></span>`).join('');
    const runOn = m.ranAt ? `<span class="detailfooteritem runon"><span class="k">Run on</span><span class="v">${esc(m.ranAt)}</span></span>` : '';
    root.innerHTML = `
      <header class="detailheader">
        <div class="title-row detailtitle${MULTI ? '' : ' noback'}">${MULTI ? `<div class="detailedge"><button class="back" type="button" data-back aria-label="All runs" title="All runs"><span class="backarrow" aria-hidden="true">←</span></button></div>` : ''}<div class="runidentity"><span class="badge ${esc(detailOutcome)}">${esc(detailOutcomeLabel)}</span><h1>${esc(m.title)}</h1></div><div class="detailactions">${renderThemeToggle()}${exportMenu}</div></div>
        <nav aria-label="Report views">${tabs.map(([id, l]) => `<button class="${st.tab === id ? 'active' : ''}" data-tab="${id}">${l}</button>`).join('')}</nav>
      </header>
      <main class="${st.tab === 'timeline' ? 'timelinemain' : ''}">${body}</main>
      ${st.tab === 'timeline' && D.trace.length ? scrubberHtml(timelineAxis(), streamEvents(), idxOf(st.step)) : ''}
      <footer class="detailfooter"><div class="detailfootermeta">${footerItems}${runOn}</div></footer>`;
    wire();
    if (previousTimelineScroll != null) {
      const timelineList = root.querySelector<HTMLElement>('.timeline-list');
      if (timelineList) timelineList.scrollTop = previousTimelineScroll;
    }
    if (focusSelector) root.querySelector<HTMLElement>(focusSelector)?.focus({ preventScroll: true });
  };

  let zoomEl = null;
  let zoomReturnFocus = null;
  let zoomMove = null;
  // Video-tab playback stop handle (same engine as the timeline; see startPlaybackLoop below).
  let videoPlaybackStop = null;
  const stopVideo = () => { if (!videoPlaybackStop) return; const stop = videoPlaybackStop; videoPlaybackStop = null; stop(); };
  // Build the zoom overlay via DOM APIs (not innerHTML) — the image src is a data: URI but we never
  // reinterpret any value as HTML here.
  // `markup` is the step's action-mark overlay (markHtml) so the zoomed view keeps the tap dot /
  // swipe arrow — it's built from numeric coordinates only, never from user strings, so inserting
  // it as HTML is safe.
  const closeZoom = () => {
    if (!zoomEl) return;
    zoomEl.remove(); zoomEl = null; zoomMove = null;
    if (zoomReturnFocus && zoomReturnFocus.focus) zoomReturnFocus.focus();
  };
  const openZoom = (src: string, markup?: string, gallery: { src: string; token?: string; label?: string; tool?: string }[] = [{ src }], startIndex = 0) => {
    zoomReturnFocus = document.activeElement;
    zoomEl = document.createElement('div'); zoomEl.className = gallery.length > 1 ? 'zoom haslist' : 'zoom';
    zoomEl.setAttribute('role', 'dialog'); zoomEl.setAttribute('aria-modal', 'true'); zoomEl.setAttribute('aria-label', 'Expanded screenshot'); zoomEl.tabIndex = -1;
    const wrap = document.createElement('div'); wrap.className = 'zoomwrap';
    const big = document.createElement('img'); big.src = src; big.alt = 'screenshot';
    wrap.appendChild(big);
    if (markup) wrap.insertAdjacentHTML('beforeend', markup);
    zoomEl.appendChild(wrap);
    let galleryIndex = Math.max(0, Math.min(gallery.length - 1, startIndex));
    const previous = document.createElement('button'); previous.type = 'button'; previous.className = 'zoomnav prev'; previous.setAttribute('aria-label', 'Previous screenshot'); previous.textContent = '‹';
    const next = document.createElement('button'); next.type = 'button'; next.className = 'zoomnav next'; next.setAttribute('aria-label', 'Next screenshot'); next.textContent = '›';
    // Step-label rail on the right: the current entry reads at full strength, the rest are dimmed
    // context. Clicking a label jumps straight to that screenshot.
    const stepItems = [];
    let stepList = null;
    if (gallery.length > 1) {
      stepList = document.createElement('nav'); stepList.className = 'zoomsteps'; stepList.setAttribute('aria-label', 'Screenshot steps');
      stepList.onclick = (e) => e.stopPropagation();
      gallery.forEach((entry, i) => {
        const item = document.createElement('button'); item.type = 'button'; item.className = 'zoomstep';
        if (entry.token) { const chip = document.createElement('span'); chip.className = 'zoomstepchip'; chip.textContent = entry.token; item.appendChild(chip); }
        const label = document.createElement('span'); label.className = 'zoomsteplabel'; label.textContent = entry.label || `Screenshot ${i + 1}`; item.appendChild(label);
        if (entry.tool) { const tool = document.createElement('span'); tool.className = 'zoomsteptool'; tool.textContent = entry.tool; item.appendChild(tool); }
        item.onclick = (e) => { e.stopPropagation(); if (i !== galleryIndex) { galleryIndex = i; show(); } };
        stepItems.push(item); stepList.appendChild(item);
      });
    }
    const show = () => {
      big.src = gallery[galleryIndex].src;
      previous.disabled = galleryIndex === 0; next.disabled = galleryIndex === gallery.length - 1;
      stepItems.forEach((item, i) => {
        item.className = i === galleryIndex ? 'zoomstep cur' : 'zoomstep';
        item.setAttribute('aria-current', i === galleryIndex ? 'true' : 'false');
      });
      const cur = stepItems[galleryIndex];
      if (cur && stepList) stepList.scrollTop = cur.offsetTop - (stepList.clientHeight - cur.offsetHeight) / 2;
    };
    zoomMove = (delta) => { const target = galleryIndex + delta; if (target < 0 || target >= gallery.length) return; galleryIndex = target; show(); };
    previous.onclick = (e) => { e.stopPropagation(); zoomMove(-1); };
    next.onclick = (e) => { e.stopPropagation(); zoomMove(1); };
    zoomEl.appendChild(previous); zoomEl.appendChild(next); if (stepList) zoomEl.appendChild(stepList);
    zoomEl.onclick = closeZoom;
    // show() after attach: the initial scroll-centering needs real layout offsets, which are all
    // zero while the overlay is still detached.
    document.body.appendChild(zoomEl);
    show();
    zoomEl.focus();
  };
  const centerTimelineSelection = () => {
    const center = () => {
      const list = root.querySelector<HTMLElement>('.timeline-list');
      const selected = root.querySelector<HTMLElement>(`[data-step="${st.step}"]`) || root.querySelector<HTMLElement>(`[data-group="${st.step}"]`);
      if (!list || !selected || !list.scrollTo || !list.getBoundingClientRect || !selected.getBoundingClientRect) return;
      const scrolls = (el: HTMLElement) => el.scrollHeight > el.clientHeight + 1
        && (typeof getComputedStyle === 'undefined' || /(auto|scroll)/.test(getComputedStyle(el).overflowY));
      let scroller = list;
      if (!scrolls(scroller)) {
        for (let parent = list.parentElement; parent; parent = parent.parentElement) {
          if (scrolls(parent)) { scroller = parent; break; }
        }
      }
      const listRect = scroller.getBoundingClientRect();
      const selectedRect = selected.getBoundingClientRect();
      const top = Math.max(0, scroller.scrollTop + selectedRect.top - listRect.top - (scroller.clientHeight - selectedRect.height) / 2);
      const reducedMotion = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
      scroller.scrollTo({ top, behavior: reducedMotion ? 'auto' : 'smooth' });
    };
    if (typeof requestAnimationFrame === 'undefined') center();
    else requestAnimationFrame(() => requestAnimationFrame(center));
  };
  // Select the trace row at index `p`: the shared landing sequence for every explicit timeline
  // navigation (transport buttons, scrubber, arrow keys).
  const gotoStep = (p) => { stopTimeline(); st.step = D.trace[p].i; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection(); };
  // The single playback engine behind the timeline AND the Video tab: one requestAnimationFrame
  // loop that accumulates elapsed playback time from real frame-to-frame deltas (dt × speed(), so a
  // throttled or late frame never slows the clock, and a mid-flight speed change applies from that
  // moment without rewinding). paint(elapsedMs) draws the position and returns false to end
  // playback. Returns a stop function. Requires requestAnimationFrame (every viewer host has it;
  // the headless tests stub it with a controllable clock).
  const startPlaybackLoop = (speed, paint) => {
    let live = true; let handle = null; let elapsed = 0; let last = performance.now();
    const tick = (now) => {
      if (!live) return;
      elapsed += Math.max(0, now - last) * speed();
      last = now;
      if (paint(elapsed) === false) { live = false; return; }
      handle = requestAnimationFrame(tick);
    };
    handle = requestAnimationFrame(tick);
    return () => { if (!live) return; live = false; if (handle != null) cancelAnimationFrame(handle); };
  };
  // Playback-time counterpart of revealTimelineStep: apply the same phase expansion to the
  // LIVE DOM so the advancing selection is visible without a full re-render.
  const revealTimelineStepInPlace = () => {
    revealTimelineStep(st.step);
    root.querySelectorAll<HTMLElement>('[data-phase]').forEach((control) => {
      const open = control.dataset.phase === 'trailhead' ? st.trailheadOpen : st.trailOpen;
      control.setAttribute('aria-expanded', String(open));
      const body = control.closest('.tlphase')?.querySelector<HTMLElement>('.tlphasebody');
      if (body) body.hidden = !open;
    });
  };
  // Move the current-step highlight in place (class + aria toggles, keep-in-view scroll) — the
  // step list's markup is otherwise untouched during playback.
  const paintTimelineSelection = () => {
    root.querySelectorAll<HTMLElement>('.step.sel, .grphdr.sel').forEach((el) => { el.classList.remove('sel'); el.removeAttribute('aria-current'); });
    const el = root.querySelector<HTMLElement>(`[data-step="${st.step}"]`) || root.querySelector<HTMLElement>(`[data-group="${st.step}"]`);
    if (!el) return;
    el.classList.add('sel');
    el.setAttribute('aria-current', 'step');
    if (el.scrollIntoView) el.scrollIntoView({ block: 'nearest' });
  };
  // Per-step paint of the preview pane during playback: swap the screenshot <img> source (steps
  // mode only — in video mode the frame follows the clock), the pane's accessible name (img alt /
  // frame aria-label, kept in lockstep with what the static render would produce), and the
  // action-mark overlay in place.
  const paintTimelinePane = (hasVideo) => {
    const wrap = root.querySelector<HTMLElement>('.preview .shotwrap');
    if (!wrap) return;
    const cur = D.trace.find((t) => t.i === st.step);
    const pos = idxOf(st.step);
    if (hasVideo) {
      const frame = document.getElementById('tlvframe');
      if (frame && cur) frame.setAttribute('aria-label', `Video frame at ${cur.label}, step ${pos + 1}`);
    } else {
      const img = document.getElementById('shot') as HTMLImageElement | null;
      const src = shotForStep(st.step);
      if (img && src) { img.src = src; if (cur) img.alt = `${cur.label} at step ${pos + 1}`; }
    }
    wrap.querySelectorAll<HTMLElement>('.mark, .swipe, .markborder').forEach((el) => el.remove());
    const markup = cur && (hasVideo || cur.screenshotFile) ? markHtml(cur) : '';
    if (markup) wrap.insertAdjacentHTML('beforeend', markup);
  };
  // Shared landing sequence when playback ends or is paused: drop the engine, then ONE route write
  // + full render restoring canonical (non-playing) state.
  const endTimelinePlayback = () => { stopTimeline(); writeRoute(true); render(true); };
  // Auto-play the timeline like a video: ONE master clock drives the video frame (when the run has
  // a run-clock-mappable video), the advancing step selection, and the scrub head, so they can
  // never disagree. With video, the clock is the real run clock — steps advance exactly when their
  // timestamps pass and the sprite frame follows videoFrameAt. Without video, the clock runs on the
  // compressed steps schedule (real gaps clamped to the axis's 350–4000ms window — see
  // buildPlaybackSchedule), so pacing is real but a long idle gap never stalls playback. Every tick
  // paints by direct DOM mutation only — no render(true), no writeRoute — until playback ends.
  const playTimeline = () => {
    if (!D.trace.length) return;
    const v = tlVideo();
    const stepsSchedule = buildPlaybackSchedule(D.trace, null);
    const schedule = v ? buildPlaybackSchedule(D.trace, v) : stepsSchedule;
    const axis = timelineAxis(stepsSchedule);
    const startMs = schedule.offsets[idxOf(st.step)] ?? 0;
    const span = Math.max(1, schedule.offsets.length ? schedule.offsets[schedule.offsets.length - 1] : 0);
    const grab = () => ({
      frame: document.getElementById('tlvframe'),
      head: root.querySelector<HTMLElement>('.scrubhead'),
      scrub: root.querySelector<HTMLElement>('[data-scrub]'),
      prev: document.getElementById('prev') as HTMLButtonElement | null,
      next: document.getElementById('next') as HTMLButtonElement | null,
    });
    let els = grab();
    let lastIndex = -1; let lastFrame = -1; let lastSheet = -1;
    timelinePlaybackStop = startPlaybackLoop(() => 1, (elapsed) => {
      // A stray mid-playback re-render replaces the DOM; re-grab the paint targets so playback
      // keeps painting the live elements.
      if (els.head && !els.head.isConnected) { els = grab(); lastFrame = -1; lastSheet = -1; }
      const playMs = startMs + elapsed;
      const pos = playbackPositionAt(schedule, playMs);
      if (pos.stepIndex !== lastIndex) {
        lastIndex = pos.stepIndex;
        const row = D.trace[pos.stepIndex];
        if (row && row.i !== st.step) {
          st.step = row.i;
          revealTimelineStepInPlace();
          paintTimelineSelection();
          paintTimelinePane(pos.frame != null);
          if (els.scrub) {
            els.scrub.setAttribute('aria-valuenow', String(pos.stepIndex + 1));
            els.scrub.setAttribute('aria-valuetext', scrubValueText(pos.stepIndex));
          }
          // Keep the frame transport live as playback advances (the full render only runs at
          // stop): Previous must work once playback has moved off the first row, and Next must
          // disable on the last one.
          if (els.prev) els.prev.disabled = pos.stepIndex <= 0;
          if (els.next) els.next.disabled = pos.stepIndex >= D.trace.length - 1;
        }
      }
      if (els.frame && pos.frame != null && pos.frame !== lastFrame) {
        lastFrame = pos.frame;
        const cell = spriteFrameCss(v, pos.frame);
        // Reassign the (multi-megabyte data-URI) background only on a sheet change — a per-frame
        // reassignment would force the browser to re-resolve the URI on every tick.
        if (cell.sheet !== lastSheet) {
          lastSheet = cell.sheet;
          els.frame.style.backgroundImage = `url('${spriteUrl(v, cell.sheet)}')`;
        }
        els.frame.style.backgroundSize = cell.size;
        els.frame.style.backgroundPosition = cell.position;
      }
      if (els.head) {
        const f = pos.clockMs != null ? axis.tsFrac(pos.clockMs) : Math.min(1, playMs / span);
        if (f != null) els.head.style.left = `${f * 100}%`;
      }
      if (pos.done) { endTimelinePlayback(); return false; }
      return true;
    });
  };
  const wire = () => {
    stopVideo(); // a re-render replaces the video element; drop any running playback timer.
    if (st.tab !== 'timeline') stopTimeline(); // playback only lives on the timeline tab
    root.querySelectorAll<HTMLElement>('[data-theme-toggle]').forEach((button) => button.onclick = () => setTheme(currentTheme() === 'dark' ? 'light' : 'dark'));
    root.querySelectorAll<HTMLElement>('[data-session]').forEach((el) => {
      const open = () => { openSession(+el.dataset.session); st.pageTransition = 'forward'; writeRoute(false); render(); };
      el.onclick = open;
      el.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } };
    });
    root.querySelectorAll<HTMLElement>('[data-cell-toggle]').forEach((chev) => {
      const key = chev.dataset.cellToggle;
      chev.onclick = () => {
        st.idxOpen = st.idxOpen.indexOf(key) >= 0 ? st.idxOpen.filter((k) => k !== key) : [...st.idxOpen, key];
        render();
      };
    });
    const exportRun = document.getElementById('exportrun');
    const exportMenu = root.querySelector<HTMLDetailsElement>('[data-export-menu]');
    const closeExportMenu = () => { if (exportMenu) exportMenu.open = false; };
    const copyLocalPrompt = document.getElementById('copylocalprompt');
    if (copyLocalPrompt) copyLocalPrompt.onclick = () => {
      const prompt = localRunAgentPrompt(D.meta);
      if (!prompt) return;
      try {
        navigator.clipboard.writeText(prompt);
        copyLocalPrompt.textContent = 'Copied local run prompt';
        setTimeout(() => { copyLocalPrompt.textContent = 'Copy local run prompt'; }, 1500);
        closeExportMenu();
      } catch (e) {}
    };
    if (exportRun) exportRun.onclick = () => {
      const name = fileSlug(D.meta && D.meta.title);
      exportReport([D], `trailblaze_run_${name}.html`, `${D.meta.title || 'Trailblaze run'} · Trailblaze run`);
      closeExportMenu();
    };
    const exportScreenshotsButton = document.getElementById('exportscreenshots');
    if (exportScreenshotsButton) exportScreenshotsButton.onclick = () => { exportScreenshots(D); closeExportMenu(); };
    const exportLogsButton = document.getElementById('exportlogs');
    if (exportLogsButton) exportLogsButton.onclick = () => { exportLogs(D); closeExportMenu(); };
    if (exportMenu) {
      exportMenu.addEventListener('focusout', (e) => { if (!exportMenu.contains(e.relatedTarget as Node | null)) exportMenu.open = false; });
      exportMenu.onkeydown = (e) => { if (e.key === 'Escape') { exportMenu.open = false; exportMenu.querySelector<HTMLElement>('summary')?.focus(); } };
    }
    const exportAll = document.getElementById('exportall');
    if (exportAll) exportAll.onclick = () => exportReport(SESSIONS, 'trailblaze_runs.html', 'Trailblaze Report');
    wireCopyLink(document.getElementById('copylink'));
    wireCopyLink(document.getElementById('copylinkrun'), closeExportMenu);
    const runSort = root.querySelector<HTMLDetailsElement>('[data-runsort]');
    if (runSort) {
      runSort.addEventListener('focusout', (e) => { if (!runSort.contains(e.relatedTarget as Node | null)) runSort.open = false; });
      runSort.onkeydown = (e) => { if (e.key === 'Escape') { runSort.open = false; runSort.querySelector<HTMLElement>('summary')?.focus(); } };
      runSort.querySelectorAll<HTMLElement>('[data-run-sort]').forEach((option) => option.onclick = () => {
        st.runSort = option.dataset.runSort || 'grouped'; runSort.open = false; writeRoute(false); render();
      });
    }
    const runSearch = document.getElementById('runsearch') as HTMLInputElement | null;
    if (runSearch) runSearch.oninput = () => {
      const terms = runSearch.value.trim().toLowerCase().split(/\s+/).filter(Boolean);
      let shown = 0;
      root.querySelectorAll<HTMLElement>('[data-run-entry]').forEach((row) => {
        const match = terms.every((term) => String(row.dataset.search || '').indexOf(term) >= 0);
        row.hidden = !match;
        row.classList.toggle('firstmatch', match && shown === 0);
        if (match) shown++;
      });
      root.querySelectorAll<HTMLElement>('[data-index-section]').forEach((section) => {
        section.hidden = !Array.from(section.querySelectorAll<HTMLElement>('[data-run-entry]')).some((row) => !row.hidden);
      });
      const empty = document.getElementById('runempty');
      if (empty) empty.hidden = shown !== 0;
    };
    const backBtn = root.querySelector<HTMLElement>('[data-back]'); if (backBtn) backBtn.onclick = () => { stopTimeline(); st.view = 'index'; st.pageTransition = 'back'; writeRoute(false); render(); window.scrollTo({ top: 0 }); };
    root.querySelectorAll<HTMLElement>('[data-tab]').forEach((b) => b.onclick = () => { st.tab = b.dataset.tab; writeRoute(false); render(); });
    root.querySelectorAll<HTMLElement>('[data-step]').forEach((el) => el.onclick = (e) => { if (e) e.stopPropagation(); stopTimeline(); st.step = +el.dataset.step; revealTimelineStep(st.step); writeRoute(true); render(true); });
    root.querySelectorAll<HTMLElement>('[data-llm]').forEach((el) => el.onclick = () => { st.llmSel = +el.dataset.llm; writeRoute(true); render(); });
    const lightboxMode = document.getElementById('lightboxmode');
    if (lightboxMode) lightboxMode.onclick = () => { st.lightboxAll = !st.lightboxAll; render(); };
    root.querySelectorAll<HTMLElement>('[data-gal-zoom]').forEach((el) => el.onclick = () => {
      st.lightboxZoom = Math.max(0, Math.min(GAL_ZOOM_SIZES.length - 1, st.lightboxZoom + +el.dataset.galZoom));
      render();
    });
    root.querySelectorAll<HTMLElement>('[data-tlstream]').forEach((el) => el.onclick = () => {
      const i = +el.dataset.tlstream; st.tlStreams = st.tlStreams.indexOf(i) >= 0 ? st.tlStreams.filter((v) => v !== i) : [...st.tlStreams, i].sort((a, b) => a - b);
      st.tlMenuOpen = true; writeRoute(true); render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-tlstreams]').forEach((el) => el.onclick = () => {
      st.tlStreams = el.dataset.tlstreams === 'all' ? (sessionEvents(D) || []).map((_, i) => i) : [];
      st.tlMenuOpen = true; writeRoute(true); render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-phase]').forEach((control) => control.onclick = () => {
      const phase = control.dataset.phase;
      const open = control.getAttribute('aria-expanded') !== 'true';
      if (phase === 'trailhead') st.trailheadOpen = open;
      if (phase === 'trail') st.trailOpen = open;
      control.setAttribute('aria-expanded', String(open));
      const body = control.closest('.tlphase')?.querySelector<HTMLElement>('.tlphasebody');
      if (body) body.hidden = !open;
    });
    // Clicking a step header selects the step's first tool call — skipping agent-reasoning rows
    // and trailing terminal snapshots, falling back to the header row itself when the step has no
    // actions — so the preview pane jumps to that screenshot.
    root.querySelectorAll<HTMLElement>('[data-group]').forEach((control) => control.onclick = () => {
      const at = D.trace.findIndex((t) => t.i === +control.dataset.group);
      if (at < 0) return;
      let next = D.trace[at];
      for (let j = at + 1; j < D.trace.length && !D.trace[j].objective; j++) {
        if (!D.trace[j].terminal && !isLlmTurn(D.trace[j])) { next = D.trace[j]; break; }
      }
      stopTimeline(); st.step = next.i; revealTimelineStep(st.step); writeRoute(true); render(true);
    });
    const streamSelect = root.querySelector<HTMLDetailsElement>('[data-streamselect]');
    if (streamSelect) streamSelect.ontoggle = () => { st.tlMenuOpen = streamSelect.open; };
    // Dismiss the stream dropdown on a tap/click outside it. Assignment (not addEventListener)
    // so each re-render replaces the handler instead of stacking stale ones; closing the timeline
    // chooser fires its ontoggle above, keeping st.tlMenuOpen in sync.
    document.onpointerdown = (e) => {
      if (streamSelect && streamSelect.open && !streamSelect.contains(e.target as Node | null)) streamSelect.open = false;
    };
    root.querySelectorAll<HTMLElement>('[data-yaml-step]').forEach((el) => el.onclick = () => {
      stopTimeline();
      st.step = +el.dataset.yamlStep;
      st.tab = 'recording';
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-lightbox-step]').forEach((el) => el.onclick = () => {
      stopTimeline();
      st.step = +el.dataset.lightboxStep;
      st.tab = 'timeline';
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
      centerTimelineSelection();
    });
    const galleryShots = Array.from(root.querySelectorAll<HTMLElement>('[data-shot]'));
    const galleryEntries = galleryShots.map((el) => ({ src: D.shots[el.dataset.shot], token: el.dataset.shotToken, label: el.dataset.shotLabel, tool: el.dataset.shotTool }));
    galleryShots.forEach((el, index) => el.onclick = (e) => { if (e) e.stopPropagation(); const s = D.shots[el.dataset.shot]; if (s) openZoom(s, '', galleryEntries, index); });
    root.querySelectorAll<HTMLElement>('[role="button"][tabindex="0"]').forEach((el) => el.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); el.click(); }
    });
    const previewShot = root.querySelector<HTMLImageElement>('.preview .shot');
    if (previewShot && !previewShot.complete) previewShot.addEventListener('load', centerTimelineSelection, { once: true });
    // First timeline render with a video whose payload lacks frameWidth: measure the sprite once
    // and patch the live frame box in place (same as wireVideo) — a render(true) here would replace
    // the whole DOM out from under a running playback; later renders inline the now-cached spriteAspect.
    const tlvframeBox = document.getElementById('tlvframe');
    if (tlvframeBox && spriteAspect == null) measureSpriteAspect(tlVideo(), () => { tlvframeBox.style.aspectRatio = spriteAspect; });
    const prev = document.getElementById('prev'); const next = document.getElementById('next');
    if (prev) prev.onclick = () => { stopTimeline(); const p = idxOf(st.step); if (p > 0) gotoStep(p - 1); };
    if (next) next.onclick = () => { stopTimeline(); const p = idxOf(st.step); if (p < D.trace.length - 1) gotoStep(p + 1); };
    const scrub = root.querySelector<HTMLElement>('[data-scrub]');
    if (scrub) scrub.onclick = (e) => {
      const r = scrub.getBoundingClientRect();
      const f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
      const axis = timelineAxis(); let best = 0; let dist = Infinity;
      axis.stepFrac.forEach((sf, i) => { const d = Math.abs(sf - f); if (d < dist) { dist = d; best = i; } });
      if (D.trace[best]) gotoStep(best);
    };
    if (scrub) scrub.onkeydown = (e) => {
      const p = idxOf(st.step);
      const target = e.key === 'Home' ? 0 : e.key === 'End' ? D.trace.length - 1 : (e.key === 'ArrowUp' || e.key === 'ArrowLeft') ? p - 1 : (e.key === 'ArrowDown' || e.key === 'ArrowRight') ? p + 1 : -1;
      if (target >= 0 && target < D.trace.length) { e.preventDefault(); e.stopPropagation(); gotoStep(target); }
    };
    const tlplay = document.getElementById('tlplay');
    if (tlplay) tlplay.onclick = () => {
      if (timelinePlaybackStop) { endTimelinePlayback(); return; }
      if (!D.trace.length) return;
      if (idxOf(st.step) >= D.trace.length - 1) st.step = D.trace[0].i; // restart from the top if parked at the end
      st.playing = true;
      // Render the playing state (pause icon, selection) FIRST, then start the engine so it caches
      // paint targets from the fresh DOM — playback itself never re-renders.
      render(true);
      playTimeline();
    };
    // While playing, keep the advancing step in view in the step list.
    if (st.playing) { const selEl = root.querySelector('.step.sel'); if (selEl && selEl.scrollIntoView) selEl.scrollIntoView({ block: 'nearest' }); }
    // Zoom from the data model (the step's screenshot data URI), not by reading the rendered
    // <img>'s src back out of the DOM. The zoomed view keeps the step's tap/swipe mark. Resolved
    // at CLICK time, not captured at wire time — during playback the current step advances without
    // a re-wire, and the zoom must show the step being played, not the step playback started on.
    const shot = document.getElementById('shot');
    if (shot) shot.onclick = () => {
      const src = shotForStep(st.step);
      if (!src) return;
      const cur = D.trace.find((t) => t.i === st.step);
      openZoom(src, cur && cur.screenshotFile ? markHtml(cur) : '');
    };
    if (st.tab === 'video') wireVideo();
    if (st.tab === 'device') wireLogFilter('dlpane', 'dlq', 'dlbar', 'dlcount');
    if (st.tab === 'network') wireLogFilter('nlpane', 'nlq', 'nlbar', 'nlcount');
    if (st.tab === 'timeline') wireLazyTimelineBodies();
    const copycmd = document.getElementById('copycmd');
    if (copycmd) copycmd.onclick = () => { try { navigator.clipboard.writeText(D.meta.cmd); copycmd.textContent = 'Copied'; setTimeout(() => { copycmd.textContent = 'Copy'; }, 1500); } catch (e) {} };
    const wireCopyYaml = (id, text) => {
      const btn = document.getElementById(`copy-${id}`);
      if (btn) btn.onclick = () => { try { navigator.clipboard.writeText(text); btn.textContent = 'Copied'; setTimeout(() => { btn.textContent = 'Copy'; }, 1500); } catch (e) {} };
    };
    wireCopyYaml('original-yaml', D.originalYaml);
    wireCopyYaml('recorded-yaml', D.recordingYaml);
    wireCopyYaml('config-original', yamlRootSection(D.originalYaml, 'config'));
    wireCopyYaml('config-recorded', yamlRootSection(D.recordingYaml, 'config'));
  };

  // Drive the video sprite scrubber: map the logical-frame index to a grid cell and show it via CSS
  // background-position (no per-frame image fetch). The frame box aspect comes from the shared
  // spriteAspect measurement (renderVideo inlines it once cached).
  const wireVideo = () => {
    const v = D.video;
    const box = document.getElementById('vframe');
    if (!v || !box) return;
    const total = v.endFrame - v.startFrame + 1;
    const seek = document.getElementById('vseek') as HTMLInputElement | null;
    const posEl = document.getElementById('vpos');
    const playBtn = document.getElementById('vplay');
    const speedBtn = document.getElementById('vspeed');
    let shownSheet = 0; // renderVideo inlined sheet 0 as the initial background
    const show = (k) => {
      const kk = Math.max(0, Math.min(total - 1, k));
      const cell = spriteFrameCss(v, v.startFrame + kk);
      // Reassign the (multi-megabyte data-URI) background only on a sheet change — a per-frame
      // reassignment would force the browser to re-resolve the URI on every tick.
      if (cell.sheet !== shownSheet) {
        shownSheet = cell.sheet;
        box.style.backgroundImage = `url('${spriteUrl(v, cell.sheet)}')`;
      }
      box.style.backgroundSize = cell.size;
      box.style.backgroundPosition = cell.position;
      if (posEl) posEl.textContent = `${(kk / v.fps).toFixed(1)}s / ${(total / v.fps).toFixed(1)}s`;
      if (seek && +seek.value !== kk) seek.value = String(kk);
    };
    if (spriteAspect == null) measureSpriteAspect(v, () => { box.style.aspectRatio = spriteAspect; });
    show(seek ? +seek.value : 0);
    // Same rAF engine as the timeline: the frame index derives from elapsed wall-clock time (dt ×
    // st.vSpeed), so main-thread contention or a backgrounded tab can no longer silently slow
    // playback — late frames just skip ahead to the right frame.
    const startPlayback = () => {
      stopVideo();
      const baseFrame = seek ? Math.max(0, Math.min(total - 1, +seek.value)) : 0;
      let lastShown = -1;
      videoPlaybackStop = startPlaybackLoop(() => st.vSpeed, (elapsed) => {
        const k = videoLoopFrame(baseFrame, total, v.fps, elapsed);
        if (k !== lastShown) { lastShown = k; show(k); }
        return true;
      });
    };
    if (seek) seek.oninput = () => { stopVideo(); if (playBtn) playBtn.textContent = '▶ Play'; show(+seek.value); };
    if (playBtn) playBtn.onclick = () => {
      if (videoPlaybackStop) { stopVideo(); playBtn.textContent = '▶ Play'; return; }
      playBtn.textContent = '⏸ Pause';
      startPlayback();
    };
    // Playback-speed toggle (0.5× → 1× → 2× → 4×), multiplying the frame clock — parity with the
    // legacy player's speed control. The dt-based engine picks the new multiplier up on the next
    // frame, so an in-flight playback changes speed without restarting or rewinding.
    if (speedBtn) speedBtn.onclick = () => {
      const speeds = [0.5, 1, 2, 4];
      st.vSpeed = speeds[(speeds.indexOf(st.vSpeed) + 1) % speeds.length];
      speedBtn.textContent = `${st.vSpeed}×`;
    };
  };

  document.addEventListener('keydown', (e) => {
    if (zoomEl) {
      if (e.key === 'Escape') { e.preventDefault(); closeZoom(); }
      if (e.key === 'ArrowLeft') { e.preventDefault(); if (zoomMove) zoomMove(-1); }
      if (e.key === 'ArrowRight') { e.preventDefault(); if (zoomMove) zoomMove(1); }
      return;
    }
    if (e.defaultPrevented) return;
    const target = e.target as HTMLElement | null;
    if (target && (target.isContentEditable || /^(INPUT|TEXTAREA|SELECT|BUTTON|SUMMARY|A)$/.test(target.tagName))) return;
    // Space toggles playback on the video tab too (parity with the legacy player's spacebar).
    if (st.view === 'detail' && st.tab === 'video' && e.key === ' ') { e.preventDefault(); const b = document.getElementById('vplay'); if (b) b.click(); return; }
    if (st.view !== 'detail' || st.tab !== 'timeline' || !D.trace.length) return;
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { stopTimeline(); const p = idxOf(st.step); if (p > 0) { e.preventDefault(); gotoStep(p - 1); } }
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { stopTimeline(); const p = idxOf(st.step); if (p < D.trace.length - 1) { e.preventDefault(); gotoStep(p + 1); } }
    if (e.key === ' ') { e.preventDefault(); const b = document.getElementById('tlplay'); if (b) b.click(); } // space toggles play/pause
  });

  if (typeof window.addEventListener === 'function') {
    window.addEventListener('popstate', () => {
      const previousView = st.view;
      applyRoute();
      if (st.view !== previousView) st.pageTransition = st.view === 'detail' ? 'forward' : 'back';
      render();
    });
  }

  render();
}
