// The standalone report viewer. run-report-viewer-boot.ts bundles this module (and everything it
// imports) into a self-executing classic script that buildMultiReportHtml embeds into every
// exported report, so the exported file runs offline anywhere — plain DOM only, no React, no
// external scripts. It reads its data from inert JSON scripts: the #tb-index boot chunk plus
// lazily-parsed per-session #tb-session-<i> chunks (with #tb-run-data and window.__TB_RUN_DATA__
// as monolithic fallbacks for older files and in-app embedders).
// Shared contract types come from the ambient run-report-types.d.ts (see its header for why it
// stays ambient rather than becoming module exports).
import { declaredTrailSteps, isLlmTurnRow, localRunAgentPrompt, rowToolCallCount, traceStepCount, traceToolCallCount, transcriptCallMessages, yamlRootSection } from './run-report-extract';
import { hitTestNode, inspectorDetailsHtml, inspectorModel, inspectorRectsHtml, inspectorTreeHtml } from './run-report-inspector';
import { eventPrettyText, eventValueText, inflateEventsGz, inflateGzJsonArray, inflateGzJsonRecord, inflateGzText, inflateLlmMessagesGz, normalizeEventPayload, parseEventJsonish, rawPrettyText, rekeySprites, tbBootLoaderHtml, jsonToYaml, toInertJson, transcriptToolCallYaml, transcriptToolResultDisplay } from './run-report-payload';
import { buildExportSchedule, buildPlaybackSchedule, playbackGapMs, playbackPositionAt, spriteFrameCss, videoEndMs, videoFrameAt, videoLoopFrame } from './run-report-playback';
import { inspectorKeyForNodeId, isSelectorAnalyzableTree, loadSelectorEngine, loadSelectorEngineFromChunk, mismatchVizHtml, nodeIdForInspectorKey, selectorSuggestionsHtml } from './run-report-selectors';
import { createReportTraceModelResolver, type ReportTraceGroup } from './run-report-trace-model';
import { formatUsd } from './report-format';

// Run `fn` once the document has finished streaming (immediately when it already has). A chunked
// report's UI is interactive while the document tail — later sessions' #tb-session-<i> /
// #tb-sprites-<i> chunks — is still arriving, so work that snapshots the whole document (export)
// must wait for readyState 'complete': by then every chunk that will ever exist is in the DOM.
// One pending slot, latest call wins: re-invoking while armed replaces the deferred work rather
// than queueing a second snapshot.
let pendingWhenComplete: (() => void) | null = null;

// Removes the global listeners (and stops the timeline) of the most recent RUN_REPORT_VIEWER run.
// Module-scoped because the teardown has to outlive the run that installed it: the NEXT run is what
// invokes it, before installing its own.
let disposeViewerGlobals: (() => void) | null = null;
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
  // A report references its frames one of three ways, and every one has to reach the screen:
  // embedded base64 (the exported, portable report), a relative path (the daemon's /static/ tree
  // and `generate-report --link-images`), or an absolute http(s) URL (device-farm sessions leave
  // frames hosted rather than inlining hundreds of megabytes). What this must keep out is a
  // payload string reaching script or non-image position through an HTML attribute, so: data:
  // must be an image, any other scheme must be http(s), and a relative path may not carry the
  // characters that would end the attribute or open a tag.
  //
  // Nothing is trimmed and no C0 control survives: a browser strips leading controls and whitespace
  // BEFORE it parses a scheme, so a `\u0001javascript:` payload would reach the sink as `javascript:` while
  // a check on the raw string sees a harmless non-letter first character. Reject those outright
  // rather than normalizing, since no producer here emits them.
  const safeImageSrc = (value: unknown) => {
    const uri = String(value || '');
    if (/[\u0000-\u001F\u007F]/.test(uri.replace(/[\r\n]/g, ''))) return '';
    // Only the raster formats the report generators actually produce. `image/svg+xml` is an image to
    // the browser but a document to an attacker, and no producer emits one.
    if (/^data:/i.test(uri)) return /^data:image\/(png|jpe?g|webp|gif);base64,[a-z0-9+/=\r\n]+$/i.test(uri) ? uri : '';
    if (/["'<>`\\\s]/.test(uri) || uri.startsWith('//')) return '';
    return !/^[a-z][a-z0-9+.-]*:/i.test(uri) || /^https?:\/\//i.test(uri) ? uri : '';
  };
  const safeHref = (value: unknown) => {
    try { const url = new URL(String(value || '')); return url.protocol === 'https:' || url.protocol === 'http:' ? url.href : null; }
    catch (e) { return null; }
  };

  // A LINK-OUT run: one whose evidence lives in ANOTHER report rather than in this document. The
  // `generate-run-index` command emits a whole document of these — a CI build's per-session
  // evidence runs to hundreds of megabytes, far past what one embedded report can carry, so the
  // index holds only the matrix and each cell navigates to that run's own report.
  //
  // `meta.linkOut` says a run is one; `meta.reportUrl` only says where it went. Those are separate
  // because a stub can legitimately have nowhere to point — a row with no session archive, or an
  // index built without a viewer URL — and reading absence as "ordinary embedded run" would offer
  // to open a detail view that is empty by construction and report the missing payload as zero
  // tools and no LLM spend. `reportUrl` still implies it, so a document written before the flag
  // existed reads the same. Two consequences below: the index's open controls become links or go
  // inert (see openControl), and every per-run number comes from what the generator precomputed
  // onto `meta` rather than from a payload that isn't here.
  const isLinkOut = (s) => !!(s.meta && (s.meta.linkOut || s.meta.reportUrl));

  // Normalize to a sessions[] array. Chunked documents list index stubs (hydrated on open);
  // monolithic payloads embed full sessions; tolerate the older single-run shape
  // ({ meta, trace, llm, shots }) so previously-exported files still open.
  const SESSIONS: SessionPayload[] = (RAW.sessions && RAW.sessions.length)
    ? RAW.sessions.map((s) => INDEX_PAYLOAD ? { trace: [], llm: [], shots: {}, recordingYaml: null, originalYaml: null, ...s } : s)
    : [{ meta: RAW.meta || {}, trace: RAW.trace || [], llm: RAW.llm || [], shots: RAW.shots || {}, recordingYaml: (RAW.meta && RAW.meta.recordingYaml) || null, originalYaml: (RAW.meta && RAW.meta.originalYaml) || null }];
  // A lone run normally opens straight on its own detail. Not when it is a link-out stub: that
  // detail is an empty document by construction, so a one-row index stays an index — showing the
  // one link the reader came for instead of a run with no steps.
  const MULTI = SESSIONS.length > 1 || SESSIONS.every(isLinkOut);
  // Sessions still awaiting their #tb-session-<i> chunk. Hydration assigns the chunk's fields
  // INTO the existing stub (object identity preserved — the inflater caches and `D` hold object
  // references) and removes the entry. Empty for monolithic payloads (everything starts hydrated).
  const unhydrated = new Set<number>(INDEX_PAYLOAD && RAW.sessions && RAW.sessions.length ? SESSIONS.map((_, i) => i) : []);
  // What a live push has delivered for a run whose chunk is still unparsed. Hydration assigns the
  // chunk over the stub, and that chunk is the run as it stood when the document was written — so
  // without this a `deviceLog` or `events` stream a push had already merged in would silently roll
  // back to the older copy the moment the reader opened the run.
  const livePatched = new Map<number, Record<string, unknown>>();
  // A chunk element the HTML parser has NOT closed yet holds a partial payload: its text keeps
  // growing until the `</script>` end tag lands, and `nextSibling` is the parser's own signal that
  // it has. A completed document has nothing left to stream, so the final chunk qualifies there.
  const chunkComplete = (el: HTMLElement | null) => !!el && (el.nextSibling != null || String(document.readyState || 'complete') === 'complete');
  // readJsonScript for a chunk that arrives with the streaming document tail. Parsing a
  // still-growing chunk can only fail, and on a large report (CI aggregates run to hundreds of
  // megabytes) re-scanning a multi-megabyte string on every poll turn burns the same main thread
  // the download runs on, so the wait feeds itself and the run never opens.
  const readStreamedJsonScript = (id: string) => {
    const el = document.getElementById(id);
    if (!chunkComplete(el)) return null;
    try { return JSON.parse(el.textContent || ''); } catch (_) { return null; }
  };
  // How many session chunks have finished streaming. They arrive in document order, so the first
  // one still missing is the count, which is the honest progress the loading view reports.
  const arrivedSessionChunks = (): number => {
    let n = 0;
    while (n < SESSIONS.length && chunkComplete(document.getElementById(`tb-session-${n}`))) n++;
    return n;
  };
  const loadingProgressText = () => (MULTI
    ? `Downloaded ${arrivedSessionChunks()} of ${SESSIONS.length} runs. This one opens as soon as its data arrives.`
    : 'This run opens as soon as its data arrives.');
  // Patched in place rather than re-rendered: the loading view is otherwise static, and a full
  // render every 50ms would throw away the spinner's animation frame each turn. Only a changed
  // count is written back, because the note sits in a role=status live region and rewriting the
  // same sentence 20 times a second would have a screen reader read it out on every turn.
  const refreshLoadingProgress = () => {
    const note = root.querySelector<HTMLElement>('[data-run-loading-progress]');
    if (!note) return;
    const text = loadingProgressText();
    if (note.textContent !== text) note.textContent = text;
  };
  // Parse a session's chunk into its stub. Returns true once the session is usable: synchronously
  // when the chunk is already in the DOM (the common case), or — document fully loaded but the
  // chunk genuinely absent/malformed — by giving up on hydration so the run opens with what the
  // index carries instead of hanging.
  const hydrateSession = (i: number): boolean => {
    if (!unhydrated.has(i)) return true;
    const docComplete = String(document.readyState || 'complete') === 'complete';
    const full = readStreamedJsonScript(`tb-session-${i}`);
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
      Object.assign(SESSIONS[i], full);
      const patch = livePatched.get(i);
      if (patch) { Object.assign(SESSIONS[i], patch); livePatched.delete(i); }
      unhydrated.delete(i); return true;
    }
    if (docComplete) { unhydrated.delete(i); return true; }
    return false;
  };
  // Await a chunk that hasn't streamed in yet (the run was opened while the document tail is
  // still downloading). Cheap 50ms poll — it only ever runs during that streaming window, which
  // hydrateSession's readyState check bounds. Each turn refreshes the loading view's progress line
  // so a long wait on a big report reads as a download in flight, not a hung page.
  const awaitSessionChunk = (i: number): Promise<void> => new Promise((resolve) => {
    const poll = () => {
      if (hydrateSession(i)) { resolve(); return; }
      refreshLoadingProgress();
      setTimeout(poll, 50);
    };
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
    const chunk = readStreamedJsonScript(`tb-sprites-${key}`);
    if (chunk) spriteChunkCache[key] = chunk;
    return Boolean(chunk);
  };
  const spriteUrls = (v: VideoInfo | null | undefined, sessionIndex?: number): string[] => {
    if (v && v.sprites.some((sp) => sp.uri)) return v.sprites.map((sp) => sp.uri);
    const key = String(sessionIndex == null ? st.session : sessionIndex);
    if (spriteChunkCache[key]) return spriteChunkCache[key];
    const chunk = readStreamedJsonScript(`tb-sprites-${key}`);
    if (chunk) { spriteChunkCache[key] = chunk; return chunk; }
    return spriteStore()[key] || [];
  };
  const spriteUrl = (v: VideoInfo | null | undefined, sheet: number, sessionIndex?: number) => safeImageSrc(spriteUrls(v, sessionIndex)[sheet]);
  const generatedAt = RAW.generatedAt || (SESSIONS[0] && SESSIONS[0].meta && SESSIONS[0].meta.generatedAt) || '';
  // `?chrome=none` — the report is embedded in a host that already renders a run header of its own
  // (Trail Runner's run details). Drop the duplicated identity row: the run title, the status dot,
  // and the theme toggle, whose owner is then the embedder, right down to which theme is on. It is
  // deliberately NOT a route key: it describes the frame the report is mounted in, not which
  // run/tab/step is on screen. writeRoute leaves params it doesn't own alone, so the flag rides
  // along through the route writes navigation performs and a reload inside the frame stays
  // chromeless — but nothing in here ever writes it, and no history entry can turn it on.
  const EMBEDDED = (() => {
    if (typeof location === 'undefined') return false;
    const search = String(location.search || '').replace(/^\?/, '');
    return !!search && search.split('&').some((pair) => pair === 'chrome=none');
  })();
  const themeKey = 'trailblaze-report-theme';
  const currentTheme = () => document.documentElement?.dataset?.theme === 'light' ? 'light' : 'dark';
  const renderThemeToggle = () => {
    const theme = currentTheme();
    const next = theme === 'dark' ? 'light' : 'dark';
    return `<button class="themetoggle" type="button" data-theme-toggle aria-label="Use ${next} mode" title="Use ${next} mode"><svg class="themeicon sun" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3.6" fill="none" stroke="currentColor" stroke-width="1.75"/><path d="M12 2.5v2M12 19.5v2M5.28 5.28l1.42 1.42M17.3 17.3l1.42 1.42M2.5 12h2M19.5 12h2M5.28 18.72l1.42-1.42M17.3 6.7l1.42-1.42" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg><svg class="themeicon moon" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.5 15.1A8 8 0 0 1 8.9 4.5a8 8 0 1 0 10.6 10.6Z" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg></button>`;
  };
  const BACK_ICON_SVG = '<svg class="backicon" viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5 5 12l7 7M5 12h14" fill="none" stroke="currentColor" stroke-width="1.75" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const CHEVRON_LEFT_SVG = '<svg class="txnavicon" viewBox="0 0 16 16" aria-hidden="true"><path d="m10 3-5 5 5 5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const CHEVRON_RIGHT_SVG = '<svg class="txnavicon" viewBox="0 0 16 16" aria-hidden="true"><path d="m6 3 5 5-5 5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const setTheme = (theme, persist = true) => {
    document.documentElement.dataset.theme = theme;
    if (persist) { try { localStorage.setItem(themeKey, theme); } catch (e) {} }
    root.querySelectorAll<HTMLElement>('[data-theme-toggle]').forEach((button) => {
      const next = theme === 'dark' ? 'light' : 'dark';
      button.setAttribute('aria-label', `Use ${next} mode`);
      button.setAttribute('title', `Use ${next} mode`);
    });
  };
  // Registered per boot, so it is torn down per boot too (disposeThemeListener, called from
  // disposeViewerGlobals) — otherwise a document that boots repeatedly, like the viewer shell loading
  // one archive after another, accumulates one stale follower per load.
  // Not registered when embedded: the host app owns the theme there, and its choice may be a pinned
  // one. Following the OS instead would yank the frame to dark the moment the OS flipped, with the
  // app around it still light.
  let disposeThemeListener = null;
  if (typeof matchMedia === 'function' && !EMBEDDED) {
    const media = matchMedia('(prefers-color-scheme: light)');
    const followSystem = (event) => { try { if (!localStorage.getItem(themeKey)) setTheme(event.matches ? 'light' : 'dark', false); } catch (e) {} };
    if (media.addEventListener) {
      media.addEventListener('change', followSystem);
      if (media.removeEventListener) disposeThemeListener = () => media.removeEventListener('change', followSystem);
    }
  }

  // Rebuild this self-contained document around either the full payload or one selected session.
  // No server is needed: screenshots, logs, event streams, and viewer code are already embedded.
  const downloadBlob = (parts, type, filename) => {
    const blob = new Blob(parts, { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a'); a.href = url; a.download = filename; a.style.display = 'none';
    document.body.appendChild(a); a.click(); a.remove(); setTimeout(() => URL.revokeObjectURL(url), 0);
  };
  // Both document exports (Export report, Download report) clone this document and rewrite its
  // payload node. A document that publishes its payload as a global instead — the daemon's
  // report-live.html, any embedder that injects window.__TB_RUN_DATA__ — has no node to rewrite, so
  // exportReport bails at its `if (!data) return` and the click does nothing. Adding a node to those
  // documents wouldn't fix it either: report-live.html loads its renderer from daemon-absolute
  // /trailrunner/app/*.js, so the clone would be a file that can't boot once the daemon exits.
  // The frame guards below already hide these items for such a document in the common case (a live
  // report links its frames), but a run with no frames at all leaves them nothing to find.
  const documentCarriesItsPayload = !!document.getElementById('tb-index') || !!document.getElementById('tb-run-data');
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
  const screenshotEntries = (session) => (session.trace || []).flatMap((step) => {
    // Each row's inlined frame plus each folded child dispatch's own (duplicates within a row skipped).
    const inlined = (f) => f && /^data:image\//.test(String((session.shots || {})[f] || ''));
    const seen = new Set([step.screenshotFile]);
    return [
      ...(inlined(step.screenshotFile) ? [[step.label || step.screenshotFile, session.shots[step.screenshotFile]]] : []),
      ...(step.children || []).flatMap((c) => {
        if (!inlined(c.screenshotFile) || seen.has(c.screenshotFile)) return [];
        seen.add(c.screenshotFile);
        return [[`${step.label || ''} · ${c.label}`, session.shots[c.screenshotFile]]];
      }),
    ];
  }).map(([name, src], index) => [`${index + 1}. ${name}`, src]);
  // A report whose frames are references, not bytes: the daemon's own /report page, a
  // `--link-images` build, a device-farm run whose screenshots stay hosted. Exporting one produces
  // a file whose images point back at a server, so it looks portable and silently isn't — the
  // pictures die with the daemon or the artifact retention window.
  const linkedFrame = (value) => { const v = String(value || ''); return !!v && !/^data:image\//.test(v); };
  // The video's sprite sheets are frames too, and they follow the same embed-or-link switch as the
  // step screenshots (readVideo's spriteValue in run-report-cli.ts). A run can have a video and no
  // step screenshots at all, so a guard reading only `shots` would clear a linked video-only run
  // for export and hand back a file whose Video tab dies outside the serving daemon.
  const linksItsFrames = (session, spriteUris) => Object.keys(session.shots || {}).some((f) => linkedFrame(session.shots[f]))
    || spriteUris.some(linkedFrame);
  // The sprite URIs a payload carries inline. Deliberately NOT the chunk-resolving spriteUrls: the
  // index header asks this of every session, and JSON.parsing every #tb-sprites-<i> chunk there is
  // the boot cost the hoist exists to avoid — and would make the answer depend on which runs the
  // reader had already opened. A hoisted URI reads as '' here, which is neither a link nor a claim
  // of embedded bytes; the detail menu resolves the open run's for real.
  const inlineSpriteUris = (session) => session.video ? session.video.sprites.map((sheet) => sheet.uri) : [];
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
  const transcriptInflater = makeInflater(
    (session) => session.llmMessagesGz && !session.llmMessages,
    (session) => inflateLlmMessagesGz(session.llmMessagesGz),
  );
  const ensureTranscriptsInflated = transcriptInflater.ensure;
  const sessionTranscripts = (session) => session.llmMessages || transcriptInflater.cache.get(session) || null;
  // Per-step view hierarchies for the UI Inspector (SessionPayload.hierarchies / hierarchiesGz).
  // Unlike events/logs, inflation is NOT kicked off when the session opens — a large hierarchies
  // map costs a main-thread JSON.parse most readers never need, so it's paid only when an
  // inspector is first opened (openInspector calls ensure; its completion re-render then corrects
  // the row affordances).
  const hierarchiesInflater = makeInflater(
    (session) => session.hierarchiesGz && !session.hierarchies,
    (session) => inflateGzJsonRecord(session.hierarchiesGz),
  );
  const ensureHierarchiesInflated = hierarchiesInflater.ensure;
  const sessionHierarchies = (session) => session.hierarchies || hierarchiesInflater.cache.get(session) || null;
  const stepHierarchy = (i) => { const h = sessionHierarchies(D); return h ? h[String(i)] : null; };
  // The selected step gets the device-side "Inspect UI" affordance when it is a non-header row
  // with an inlined screenshot AND a hierarchy. Known precisely once hierarchies are inline (or
  // inflated); while a compressed payload hasn't inflated yet the affordance shows optimistically
  // for a screenshot step, then corrects on the post-inflate re-render.
  const stepInspectable = (t) => Boolean(!t.objective && t.screenshotFile && safeImageSrc(D.shots[t.screenshotFile])
    && (stepHierarchy(t.i) != null || (D.hierarchiesGz && !hierarchiesInflater.cache.has(D))));

  const logPayload = (session) => ({
    run: session.meta || {},
    deviceLog: sessionDeviceLog(session),
    network: sessionNetwork(session) || [],
    events: sessionEvents(session) || [],
    llm: session.llm || [],
    // The pooled transcript shape, exported as-is ({texts, calls[]} with calls aligned to `llm`
    // by index; resolve a message's text as texts[m.t]). Deliberately NOT resolved per call:
    // the history accumulates, so re-expanding pool refs rebuilds the quadratic naive shape —
    // ~200MB of export JSON for a 100-call session whose report carries ~4MB.
    llmMessages: sessionTranscripts(session),
  });
  const hasLogs = (session) =>
    Boolean(sessionDeviceLog(session) || session.deviceLogGz || (session.network && session.network.length) || session.networkGz || hasEvents(session) || (session.llm && session.llm.length));
  const exportLogs = async (session) => {
    if (!hasLogs(session)) return;
    // Compressed payloads export inflated, never as opaque base64 - wait out any in-flight
    // inflation (logs, events AND transcripts) so the download can't race it and export empty fields.
    await Promise.all([ensureLogsInflated(session), ensureEventsInflated(session), ensureTranscriptsInflated(session)]);
    downloadBlob([JSON.stringify(logPayload(session), null, 2)], 'application/json;charset=utf-8', `trailblaze_run_${fileSlug(session.meta.title)}_logs.json`);
  };

  // `D` is the session currently in view; every renderer reads D.trace / D.llm / D.shots / D.meta /
  // D.recordingYaml, so the single-run renderers below are unchanged across a session switch.
  let D: SessionPayload = SESSIONS[0];
  const resolveTraceModel = createReportTraceModelResolver();
  const traceModel = () => resolveTraceModel(D);
  const TIMELINE_EVENT_KINDS = ['tool', 'llm', 'assert', 'fail'];
  const stepCat = (t) => {
    if (!t.ok) return 'fail';
    const tool = String(t.tool || ''); const lbl = String(t.label || '').toLowerCase();
    if (t.llm != null || tool === 'agent step' || tool.indexOf('llm') === 0) return 'llm';
    if (lbl.indexOf('assert') === 0 || lbl.indexOf('verify') === 0 || tool.toLowerCase().indexOf('assert') >= 0) return 'assert';
    return 'tool';
  };
  const allTimelineEventKinds = () => [...TIMELINE_EVENT_KINDS];
  // `kid` narrows the step selection to one folded child dispatch (index into the row's children):
  // the preview pane shows that dispatch's own frame and its args panel expands — how a batched
  // step's every interaction is reachable (WASM-report parity). Null selects the row itself.
  const st = { view: MULTI ? 'index' : 'detail', session: 0, tab: 'timeline', step: 0, kid: null, llmSel: 0, tlStreams: [], tlEventKinds: allTimelineEventKinds(), tlMenuOpen: false, tlEventMenuOpen: false, trailheadOpen: true, trailOpen: true, stepsOpen: {}, kidsOpen: {}, lightboxAll: false, lightboxZoom: 1, runGroup: 'status', runSort: 'original', runSearch: '', idxOpen: [], playing: false, vSpeed: 1, pageTransition: '' };
  // Timeline playback stop handle (the active rAF engine run's stop function). Declared up here
  // (before openSession, which stops it) so the init-time openSession() call for a single-session
  // report doesn't hit a temporal-dead-zone ref.
  let timelinePlaybackStop = null;
  const stopTimeline = () => { st.playing = false; if (!timelinePlaybackStop) return; const stop = timelinePlaybackStop; timelinePlaybackStop = null; stop(); };
  // Transcript-lightbox state, declared up here (like timelinePlaybackStop) so the init-time
  // openSession() call can close a stale dialog without a temporal-dead-zone ref. The dialog
  // itself (openTranscript etc.) lives beside the zoom overlay below.
  let txEl = null;
  let txPanelEl = null;
  let txBodyEl = null;
  let txReturnFocus = null;
  let txReturnSelector = null;
  let txCallIndex = 0;
  let txHistoryPushed = false;
  let txHistoryClosing = false;
  // Inspector route state also participates in the init-time canonical URL write. Keep these
  // declarations above routeParams so a report without an open inspector serializes safely.
  let inspectorEl = null;
  let inspectorReturnFocus = null;
  let inspectorHistoryPushed = false;
  let inspectorHistoryClosing = false;
  const inspState = { step: 0, selected: null, hovered: null, raw: false, session: null };
  // Pushed destinations share the report's navigation motion in both directions. They are mounted
  // outside #app so the report beneath keeps its exact scroll and selection state; on dismissal,
  // animate that preserved page back into place without forcing a render.
  const animateReportReturn = () => {
    // CSS animations only restart when the class is removed for a rendered frame. Pushed views can
    // be opened and dismissed repeatedly without a report render in between, so explicitly reset
    // the class and force style resolution before applying the shared reverse transition again.
    root.className = '';
    void root.offsetWidth;
    root.className = 'page-enter-back';
  };
  // One keyboard boundary for every aria-modal report destination. Keeping this beside the shared
  // pushed-view state prevents the transcript and Inspector from drifting into different dialog
  // behavior as their controls evolve.
  const trapModalTab = (container, e) => {
    if (e.key !== 'Tab' || !container || typeof container.querySelectorAll !== 'function') return false;
    const focusables = Array.from(container.querySelectorAll('button, [href], summary, [tabindex]:not([tabindex="-1"])'))
      .filter((el: any) => !el.disabled && (el.offsetParent !== undefined ? el.offsetParent !== null || el === document.activeElement : true));
    if (!focusables.length) { e.preventDefault(); return true; }
    const first = focusables[0] as any; const last = focusables[focusables.length - 1] as any;
    if (e.shiftKey && (document.activeElement === first || document.activeElement === container)) { e.preventDefault(); last.focus(); }
    else if (!e.shiftKey && document.activeElement === last) { e.preventDefault(); first.focus(); }
    return true;
  };
  // Armed by a `?llm=N` route: the next LLM-tab render scrolls to the deep-linked table row and
  // opens its transcript lightbox (the tab's only detail surface).
  let pendingLlmOpen = false;
  let pendingLlmHistoryBacked = false;
  let pendingInspectorOpen = null;
  let pendingInspectorHistoryBacked = false;
  // `syncRoute` is for the reader-initiated dismissals (Escape, the close button): the lightbox is
  // what `?llm=N` encodes, so closing drops the param back to the tab route. Every other caller
  // (re-open, opening a session, popstate, teardown) is mid-navigation and owns the URL itself —
  // writing here would replaceState the state being navigated away from back over the new one.
  const closeTranscript = (syncRoute = false, animateReturn = false) => {
    if (!txEl) return;
    // A pointer/keyboard dismissal should consume the history entry created by openTranscript,
    // exactly like the browser's Back button. Deep-linked transcripts did not create an entry in
    // this document, so they still close in place and replace the `llm` parameter below.
    if (syncRoute && txHistoryClosing) return;
    if (syncRoute && txHistoryPushed && typeof history !== 'undefined' && typeof history.back === 'function') {
      txHistoryClosing = true;
      history.back();
      return;
    }
    txEl.remove(); txEl = null;
    txPanelEl = null;
    txBodyEl = null;
    txHistoryPushed = false;
    txHistoryClosing = false;
    if (animateReturn) animateReportReturn();
    const back = txReturnFocus; txReturnFocus = null;
    const backSelector = txReturnSelector; txReturnSelector = null;
    // Re-resolve the trigger by selector first: a gz report's transcript inflation completes with a
    // full render(), which replaces #app and detaches the node this dialog captured on open.
    const live = backSelector ? root.querySelector<HTMLElement>(backSelector) : null;
    const target = live || back;
    if (target && target.focus) target.focus();
    if (syncRoute) writeRoute(true);
  };
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

  // Objective and terminal rows describe structure; the spatial selection belongs to a tool-call
  // row. Old links may still name an objective, so resolve those to the first actionable row inside
  // that objective instead of leaving the timeline without a roving tab stop.
  const selectableTimelineIndexFor = (stepId) => {
    const at = D.trace.findIndex((t) => t.i === stepId);
    if (at < 0) return -1;
    const visible = (t) => Boolean(t && !t.objective && !t.terminal && st.tlEventKinds.indexOf(stepCat(t)) >= 0);
    if (visible(D.trace[at])) return at;
    for (let i = at + 1; i < D.trace.length && !D.trace[i].objective; i++) {
      if (visible(D.trace[i])) return i;
    }
    // A filter can hide the currently selected row. Keep the roving tab stop and preview attached
    // to the nearest remaining tool row instead of leaving keyboard focus on detached markup.
    for (let distance = 1; distance < D.trace.length; distance++) {
      const after = at + distance;
      const before = at - distance;
      if (after < D.trace.length && visible(D.trace[after])) return after;
      if (before >= 0 && visible(D.trace[before])) return before;
    }
    return -1;
  };

  // The newest row a reader can actually be parked on. A growing run's trace usually ENDS on a
  // structural row — the objective header of a step that has started but not acted yet — and a
  // structural row holds no selection. Both the seed for a running session and the live seam's
  // follow ask this one question, so they can't answer it differently: when they did, the seed
  // resolved the tail to a real row while the follow compared against the raw one, the two never
  // matched, and a run streamed in with its selection stuck on the step it opened at.
  const newestSelectableStep = () => {
    if (!D || !D.trace || !D.trace.length) return null;
    const last = D.trace[D.trace.length - 1].i;
    const at = selectableTimelineIndexFor(last);
    return at >= 0 ? D.trace[at].i : last;
  };
  const normalizeTimelineSelection = () => {
    const selectable = selectableTimelineIndexFor(st.step);
    if (selectable < 0) return;
    if (D.trace[selectable].i !== st.step) st.kid = null; // the selection moved rows; the old row's child can't follow
    st.step = D.trace[selectable].i;
    revealTimelineStep(st.step);
  };

  // A route into a not-yet-hydrated session, parked until the chunk lands: the step/llm bounds
  // checks in applyDetailRoute need the real trace, so seedSessionDetail re-applies it.
  let pendingDetailRoute = null;
  let pendingDetailRouteFromHistory = false;
  // Seed the detail view of the (hydrated) session in D. Failed runs lead with the actionable
  // tool; passing runs start at the authored trail so any recovery summary remains the first
  // thing visible above it. Incidental failed polling rows (a passing run, or a passing step of a
  // failed run) are intentionally ignored.
  const seedSessionDetail = () => {
    spriteAspectFromMeta(D.video);
    ensureEventsInflated(D);
    ensureLogsInflated(D);
    ensureTranscriptsInflated(D);
    const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
    const runInProgress = String((D.meta && D.meta.status) || '').toLowerCase() === 'running';
    const firstFail = runFailed ? failureAnchorIndex() : -1;
    st.step = firstFail >= 0 ? D.trace[firstFail].i : ((D.trace[0] && D.trace[0].i) || 0);
    const trailheadStart = D.trace.findIndex((t) => t.objective && t.trailhead);
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    // How many ROWS the trailhead phase would render, which is what the threshold below is about:
    // how much of the screen setup takes. Deliberately not the step cards' dispatch count — a folded
    // row's dispatches stay collapsed inside it, so they cost no height. Per-call LLM rows are not
    // rows the reader counts as setup work either.
    const trailheadEnd = trailStart >= 0 ? trailStart : D.trace.length;
    const trailheadRows = trailheadStart >= 0
      ? D.trace.slice(trailheadStart + 1, trailheadEnd).filter((t) => !isLlmTurnRow(t)).length
      : 0;
    const failureIsInTrailhead = firstFail >= 0 && trailheadStart >= 0 && (trailStart < 0 || firstFail < trailStart);
    // Setup is supporting context. Keep small setup visible, but collapse high-volume setup so the
    // authored Trail remains the dominant content. A setup failure overrides that default.
    st.trailheadOpen = trailStart < 0 || failureIsInTrailhead || trailheadRows <= 12;
    if (firstFail < 0 && !st.trailheadOpen && trailStart >= 0) st.step = D.trace[trailStart].i;
    // A run still in progress has no conclusion to lead with, and its interesting end is the end:
    // open on the newest row so the first thing on screen is what the device is doing now. The
    // live seam below keeps following that tail on each update.
    if (firstFail < 0 && runInProgress) { const newest = newestSelectableStep(); if (newest != null) st.step = newest; }
    const selectable = selectableTimelineIndexFor(st.step);
    if (selectable >= 0) st.step = D.trace[selectable].i;
    if (pendingDetailRoute) {
      const r = pendingDetailRoute;
      const fromHistory = pendingDetailRouteFromHistory;
      pendingDetailRoute = null;
      pendingDetailRouteFromHistory = false;
      applyDetailRoute(r, fromHistory);
    }
    // Last, so it opens the group holding whatever the route settled on rather than the seeded
    // step the route was about to replace.
    revealTimelineStep(st.step);
  };
  // Open a session's detail view.
  const openSession = (i) => {
    // st.lightboxZoom deliberately survives this reset: thumbnail size is a cross-run viewing
    // preference, unlike the per-session lightboxAll expansion.
    stopTimeline(); closeTranscript(); spriteAspect = null; pendingDetailRoute = null; pendingDetailRouteFromHistory = false; st.session = i; D = SESSIONS[i]; st.view = 'detail'; st.tab = 'timeline'; st.step = 0; st.kid = null; st.llmSel = 0; st.tlStreams = []; st.tlEventKinds = allTimelineEventKinds(); st.tlMenuOpen = false; st.tlEventMenuOpen = false; st.trailOpen = true; st.stepsOpen = {}; st.kidsOpen = {}; st.lightboxAll = false;
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
        if (st.tab === 'timeline') centerTimelineSelection(true);
      });
      return;
    }
    seedSessionDetail();
  };
  // The header id of the group a row belongs to. Resolved through the trace model rather than by
  // scanning back for the nearest objective, because a self-heal retry is itself an objective row
  // that the model MERGES into the group it retried — its own id names no rendered header.
  const groupHeaderIdOf = (stepId) => {
    const index = traceModel().indexById.get(stepId);
    const row = index == null ? null : D.trace[index];
    const group = row ? traceModel().groupByRow.get(row) : null;
    return group && group.header ? group.header.i : null;
  };
  // Whether a step group renders expanded: an explicit toggle first, then the group holding the
  // selection (the preview pane must never point into a hidden body), then the caller's default.
  //
  // The selected group is DERIVED, never recorded. Recording it would make every automatic reveal
  // permanent, so following a live tail or watching a playback to the end would leave every step it
  // walked through expanded — rebuilding the wall this collapse exists to remove. It also keeps
  // `st.stepsOpen` to entries the reader actually asked for, which matters because a row's `i` is
  // positional and a live push can renumber it.
  const groupOpen = (headerId, byDefault) => (headerId in st.stepsOpen
    ? !!st.stepsOpen[headerId]
    : !!byDefault || headerId === groupHeaderIdOf(st.step));
  const revealTimelineStep = (stepId) => {
    const index = traceModel().indexById.get(stepId);
    if (index == null) return;
    const trailStart = D.trace.findIndex((t) => t.objective && !t.trailhead);
    if (trailStart >= 0 && index >= trailStart) st.trailOpen = true;
    else if (D.trace.some((t) => t.objective && t.trailhead)) st.trailheadOpen = true;
    // Navigating into a step the reader had collapsed re-opens it, so a selection is never stranded
    // behind a closed header. This only ever CLEARS a toggle — see groupOpen on why it never sets one.
    const headerId = groupHeaderIdOf(stepId);
    if (headerId != null && st.stepsOpen[headerId] === false) delete st.stepsOpen[headerId];
  };

  // ── Autoplay-capture contract (`?autoplay=1`) ───────────────────────────────────────────────
  // The two-signal handshake the CLI's `trailblaze report --video/--gif/--webp` exporters drive:
  // they load this report in headless Chromium with `?autoplay=1`, screen-record the tab, and stop
  // when `globalThis.__tbPlaybackEnded` turns true. So the report must play its timeline start to
  // finish with no user interaction and then say so, exactly once, after the last frame is on
  // screen. Deliberately NOT in routeKeys below: writeRoute only rewrites the keys it owns, so the
  // flag survives the route writes playback itself performs.
  const AUTOPLAY = (() => {
    if (typeof location === 'undefined') return false;
    const search = String(location.search || '').replace(/^\?/, '');
    // Lenient about the value like the legacy report was — `?autoplay` and `?autoplay=1` both fire.
    return !!search && search.split('&').some((pair) => pair === 'autoplay' || pair.indexOf('autoplay=') === 0);
  })();
  let playbackEndSignaled = false;
  const signalPlaybackEnded = () => {
    if (playbackEndSignaled) return; // the recorder stops on the first true; a second is a no-op anyway
    playbackEndSignaled = true;
    const raise = () => { (globalThis as Record<string, unknown>).__tbPlaybackEnded = true; };
    // Raise it a full paint AFTER the caller's final render: the recorder polls the flag right
    // after a screenshot, so flipping it synchronously can hand it a frame the compositor drew
    // before the last step landed. Two rAF turns guarantee that frame is on screen first.
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(() => requestAnimationFrame(raise));
    else raise();
  };
  // Marks the document for capture framing (see the html[data-tb-autoplay] rules in the CSS):
  // pure-affordance chrome is hidden and transitions are stilled so no frame catches a half-played
  // one. Set at boot, before the first render, so the very first captured frame is already framed.
  if (AUTOPLAY && document.documentElement && document.documentElement.dataset) document.documentElement.dataset.tbAutoplay = '1';
  // Same idea for the embedded frame (see the html[data-tb-embedded] rules): the host's surface
  // shows through instead of the report painting its own page colour, so the frame reads as a panel
  // of the app rather than a window cut into it.
  if (EMBEDDED && document.documentElement && document.documentElement.dataset) document.documentElement.dataset.tbEmbedded = '1';

  // Report state lives in query parameters so copied URLs communicate their selected run, view,
  // and step. Only these owned keys are changed: signed-artifact parameters such as `jwt` survive
  // every navigation. Legacy hash routes remain readable and are canonicalized on initial load.
  const routeKeys = ['view', 'runs', 'run', 'tab', 'step', 'kid', 'streams', 'types', 'llm', 'inspect', 'stream', 'group', 'sort', 'search', 'filter'];
  // 'stream' (the retired Events tab's selected-stream index) and 'filter' (the retired
  // Self-healed index filter) stay in routeKeys so legacy URLs that carry them are still
  // canonicalized away, but they are no longer read or written.
  const readRoute = () => {
    if (typeof location === 'undefined') return null;
    const query = new URLSearchParams(String(location.search || ''));
    const hasQueryRoute = routeKeys.some((key) => query.has(key));
    const p = hasQueryRoute ? query : new URLSearchParams(String(location.hash || '').replace(/^#/, ''));
    if (p.get('view') === 'runs' || p.has('runs')) {
      // `sort=grouped|owner` came from the original overloaded menu. Read those links as their
      // equivalent independent grouping + ordering pair, then let writeRoute canonicalize them.
      const legacySort = p.get('sort') || 'original';
      const group = p.get('group') || (legacySort === 'owner' ? 'owner' : 'status');
      const sort = legacySort === 'grouped' ? 'original' : legacySort === 'owner' ? 'name' : legacySort;
      return { view: 'index', group, sort, search: p.get('search') || '' };
    }
    if (!p.has('run') && !p.has('tab') && !p.has('step')) return null;
    return {
      view: 'detail', session: Number(p.get('run') || 0), tab: p.get('tab') || 'timeline',
      step: p.has('step') ? Number(p.get('step')) : null,
      kid: p.has('kid') ? Number(p.get('kid')) : null,
      llm: p.has('llm') ? Number(p.get('llm')) : null,
      inspect: p.has('inspect') ? Number(p.get('inspect')) : null,
      streams: p.get('streams'),
      types: p.get('types'),
    };
  };
  // Apply the detail-view parts of a parsed route (tab/step/llm/streams) to the open session.
  // Split out of applyRoute because a route into a not-yet-hydrated session parks here and
  // re-applies from seedSessionDetail once the chunk lands.
  const applyDetailRoute = (r, fromHistory = false) => {
    pendingInspectorOpen = null;
    pendingInspectorHistoryBacked = false;
    pendingLlmHistoryBacked = false;
    const requestedTab = r.tab === 'grid' ? 'lightbox' : r.tab;
    // Legacy 'events' routes land on the timeline, where inline event streams now live.
    const allowed = ['timeline', 'lightbox', 'video', 'llm', 'config', 'recording', 'device', 'network', 'info'];
    if (allowed.indexOf(requestedTab) >= 0) st.tab = requestedTab;
    if (r.types != null) st.tlEventKinds = r.types === 'none' ? [] : r.types.split(',').filter((kind) => TIMELINE_EVENT_KINDS.indexOf(kind) >= 0);
    if (r.step != null && Number.isFinite(r.step) && D.trace.some((t) => t.i === r.step)) {
      const selectable = selectableTimelineIndexFor(r.step);
      st.step = selectable >= 0 ? D.trace[selectable].i : r.step;
      revealTimelineStep(st.step);
      // A deep-linked child selection is honored only when the landed row actually has that child.
      const routed = D.trace.find((t) => t.i === st.step);
      st.kid = r.kid != null && Number.isFinite(r.kid) && routed && routed.children && r.kid >= 0 && r.kid < routed.children.length ? r.kid : null;
    }
    // A deep-linked call (`?llm=N`) highlights its per-request table row AND opens that call's
    // transcript lightbox — the lightbox IS the detail surface, so the link lands the reader in
    // the transcript with the row waiting underneath when it closes.
    if (r.llm != null && Number.isFinite(r.llm) && r.llm >= 0 && r.llm < D.llm.length) {
      st.llmSel = r.llm;
      pendingLlmOpen = true;
      pendingLlmHistoryBacked = fromHistory;
    }
    if (r.inspect != null && Number.isFinite(r.inspect) && D.trace.some((t) => t.i === r.inspect)) {
      pendingInspectorOpen = r.inspect;
      pendingInspectorHistoryBacked = fromHistory;
    }
    // No upper-bound check here: stream counts may be unknown while a compressed events payload
    // is still inflating, so the consumer owns the clamp (streamEvents ignores unknown tlStreams
    // indices).
    if (r.streams != null) st.tlStreams = r.streams.split(',').map(Number).filter((i) => Number.isInteger(i) && i >= 0);
  };
  const applyRoute = (fromHistory = false) => {
    const r = readRoute();
    if (!r) return;
    if (r.view === 'index' && MULTI) {
      stopTimeline(); st.view = 'index';
      if (['status', 'owner'].indexOf(r.group) >= 0) st.runGroup = r.group;
      if (['original', 'name', 'cost'].indexOf(r.sort) >= 0) st.runSort = r.sort;
      st.runSearch = r.search || '';
      return;
    }
    const si = Number.isFinite(r.session) ? Math.max(0, Math.min(SESSIONS.length - 1, r.session)) : 0;
    openSession(si);
    if (unhydrated.has(si)) { pendingDetailRoute = r; pendingDetailRouteFromHistory = fromHistory; }
    else applyDetailRoute(r, fromHistory);
  };
  // The viewer's owned route state, serialized. The single source both the URL writer and the
  // Copy-link grafter consume — the grafter can't read the state back off location.search in a
  // sandboxed embed, where writeRoute's history write is refused (see below).
  const routeParams = () => {
    const params = new URLSearchParams();
    if (st.view === 'index') {
      params.set('view', 'runs');
      if (st.runGroup !== 'status') params.set('group', st.runGroup);
      if (st.runSort !== 'original') params.set('sort', st.runSort);
      if (st.runSearch) params.set('search', st.runSearch);
    } else {
      params.set('run', String(st.session));
      params.set('tab', st.tab);
      if (st.tab === 'timeline' && Number.isFinite(st.step)) params.set('step', String(st.step));
      if (st.tab === 'timeline' && st.kid != null) params.set('kid', String(st.kid));
      if (st.tab === 'timeline' && st.tlStreams.length) params.set('streams', st.tlStreams.join(','));
      if (st.tab === 'timeline' && st.tlEventKinds.length !== TIMELINE_EVENT_KINDS.length) params.set('types', st.tlEventKinds.length ? st.tlEventKinds.join(',') : 'none');
      // Pushed destinations are route state: opening one creates a history entry, so browser Back
      // dismisses it without navigating away from the selected report. The same parameters make
      // copied links and browser Forward reopen the exact transcript or hierarchy capture.
      if (txEl || pendingLlmOpen) params.set('llm', String(txEl ? txCallIndex : st.llmSel));
      if (inspectorEl || pendingInspectorOpen != null) params.set('inspect', String(inspectorEl ? inspState.step : pendingInspectorOpen));
    }
    return params;
  };
  const writeRoute = (replace) => {
    if (typeof history === 'undefined' || typeof location === 'undefined') return false;
    const params = new URLSearchParams(String(location.search || ''));
    routeKeys.forEach((key) => params.delete(key));
    routeParams().forEach((value, key) => params.set(key, value));
    const search = params.toString();
    const legacyHash = /^#(?:runs(?:&|$)|run=|tab=|step=)/.test(String(location.hash || ''));
    const next = `${String(location.pathname || '')}${search ? `?${search}` : ''}${legacyHash ? '' : String(location.hash || '')}`;
    const current = `${String(location.pathname || '')}${String(location.search || '')}${String(location.hash || '')}`;
    if (current === next) return false;
    // Route persistence is a progressive enhancement: in an `about:srcdoc`/sandboxed embed (e.g.
    // Trail Runner's zip-report iframe) the History API refuses URL writes with a SecurityError —
    // the report must still render, just without deep-linkable tab/step state.
    try { history[replace ? 'replaceState' : 'pushState'](null, '', next); return !replace; } catch (_) { return false; /* embedded */ }
  };
  // A report served over HTTP(S) is shareable by URL — the route already encodes the view, sort,
  // run, and step state, so the browser's current address IS the deep link. file:// documents and
  // srcdoc embeds (Trail Runner's zip-report iframe) have no address worth sharing, so the
  // Copy-link affordances hide there — unless the generator baked in a canonical share URL
  // (`trailblaze report --share-url …`, e.g. CI pointing at the hosted artifact), which wins over
  // the browser address and keeps the affordance available from any viewing context.
  const SHARE_URL = safeHref(RAW.shareUrl) || '';
  // Embedded, the address IS this frame's `report-live.html?...&chrome=none` URL: it opens, but it
  // is a header-less document rather than the run page the host would send someone to, and the
  // host owns sharing its own runs. A baked share URL still wins, from any context.
  const shareLinkAvailable = () => !!SHARE_URL || (!EMBEDDED && typeof location !== 'undefined' && /^https?:$/.test(String(location.protocol || '')));
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

  const catColor = { fail: 'var(--fail)', llm: 'var(--ai)', assert: 'var(--pass)', tool: 'var(--txt)' };
  const timelineEventKindMeta = {
    tool: { label: 'Tool / action', color: 'var(--txt)' },
    llm: { label: 'LLM / agent', color: 'var(--ai)' },
    assert: { label: 'Assertion', color: 'var(--amber)' },
    fail: { label: 'Error', color: 'var(--fail)' },
  };
  const TIMELINE_FILTER_ICON_SVG = '<svg class="streamselectoricon" viewBox="0 0 16 16" aria-hidden="true"><path d="M2.5 4h11M4.5 8h7M6.5 12h3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
  const TIMELINE_CHECK_ICON_SVG = '<svg class="streamoptioncheck" viewBox="0 0 16 16" aria-hidden="true"><path d="m3 8.5 3 3 7-7" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const STEP_DISCLOSURE_SVG = '<svg class="grpchev" viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  // The step outcome, stated twice on purpose: as the colour of its STEP token (scannable down a
  // long collapsed trail) and as a word in its status line (readable, and not colour-only).
  const stepStatusIcon = (glyph) => `<svg class="grpstatusicon" viewBox="0 0 16 16" aria-hidden="true"><circle cx="8" cy="8" r="7" fill="currentColor" opacity=".16"/><path d="${glyph}" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>`;
  const STEP_STATUS_ICON_SVG = {
    pass: stepStatusIcon('m4.8 8.3 2.2 2.2 4.2-4.6'),
    fail: stepStatusIcon('m5.6 5.6 4.8 4.8M10.4 5.6l-4.8 4.8'),
    selfheal: stepStatusIcon('M8 4.6v4.2l2.6 1.6'),
    skip: stepStatusIcon('M5.2 8h5.6'),
  };
  const llmStepIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M12 8V4H8"/><rect width="16" height="12" x="4" y="8" rx="2"/><path d="M2 14h2M20 14h2M15 13v2M9 13v2"/></svg>';
  const toolStepIcon = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"><path d="M14.7 6.3a1 1 0 0 0 0 1.4l1.6 1.6a1 1 0 0 0 1.4 0l3.8-3.8a6 6 0 0 1-7.9 7.9l-6.9 6.9a2.1 2.1 0 0 1-3-3l6.9-6.9a6 6 0 0 1 7.9-7.9Z"/></svg>';
  const stepIcon = (t) => {
    const label = String(t.label || '').toLowerCase();
    const tool = String(t.tool || '').toLowerCase();
    const assertion = label.indexOf('assert') === 0 || label.indexOf('verify') === 0 || tool.indexOf('assert') >= 0;
    const tap = label.indexOf('tap') === 0 || label.indexOf('longpress') === 0 || label.indexOf('long press') === 0;
    const llm = t.llm != null || tool === 'agent step' || tool.indexOf('llm') === 0;
    if (!t.ok) return { cls: 'failure', glyph: '×' };
    if (llm) return { cls: 'llm', glyph: llmStepIcon };
    if (assertion) return { cls: 'verify', glyph: '✓' };
    if (tap) return { cls: 'tap', glyph: '◉' };
    return { cls: 'tool', glyph: toolStepIcon };
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

  // Failure messages often wrap a serialized driver command in prose. Pull every balanced,
  // parseable JSON object/array into its own readable code block while leaving non-JSON braces
  // and the surrounding explanation untouched.
  const failureMessageHtml = (raw) => {
    const text = String(raw || '');
    const parts: Array<{ kind: 'prose' | 'json'; value: string }> = [];
    let cursor = 0; let search = 0;
    while (search < text.length) {
      const objectAt = text.indexOf('{', search);
      const arrayAt = text.indexOf('[', search);
      const start = objectAt < 0 ? arrayAt : arrayAt < 0 ? objectAt : Math.min(objectAt, arrayAt);
      if (start < 0) break;
      const stack: string[] = [];
      let quoted = false; let escaped = false; let end = -1;
      for (let i = start; i < text.length; i++) {
        const ch = text[i];
        if (quoted) {
          if (escaped) escaped = false;
          else if (ch === '\\') escaped = true;
          else if (ch === '"') quoted = false;
          continue;
        }
        if (ch === '"') { quoted = true; continue; }
        if (ch === '{') stack.push('}');
        else if (ch === '[') stack.push(']');
        else if (ch === '}' || ch === ']') {
          if (stack.pop() !== ch) break;
          if (!stack.length) { end = i; break; }
        }
      }
      if (end < 0) { search = start + 1; continue; }
      const candidate = text.slice(start, end + 1);
      try {
        const formatted = JSON.stringify(JSON.parse(candidate), null, 2);
        if (start > cursor) parts.push({ kind: 'prose', value: text.slice(cursor, start) });
        parts.push({ kind: 'json', value: formatted });
        cursor = end + 1;
        search = cursor;
      } catch (_) { search = start + 1; }
    }
    if (!parts.length) return esc(text).replace(/\n/g, '<br>');
    if (cursor < text.length) parts.push({ kind: 'prose', value: text.slice(cursor) });
    return parts.map((part, index) => {
      if (part.kind === 'json') return `<pre class="failurejson mono">${esc(part.value)}</pre>`;
      const value = index > 0 && parts[index - 1].kind === 'json' ? part.value.replace(/^\s*\.\s*/, '') : part.value;
      return value.trim() ? `<div class="failureprose">${esc(value.trim()).replace(/\n/g, '<br>')}</div>` : '';
    }).join('');
  };

  // Reports currently carry the failure as serialized text, not a strongly typed cause field.
  // Prefer a real exception class when one exists; for the generic `Error` wrapper, derive the
  // final nested cause (for example "Element not found") without discarding the source message.
  const failureCauseName = (parsed) => {
    const typeName = parsed.type.split('.').pop() || parsed.type;
    if (!/^(?:Error|Exception|Failure|Throwable)$/i.test(typeName)) return typeName;
    const matches = Array.from(String(parsed.message || '').matchAll(/\b(?:Error|Exception|Failure):\s*([^:\n.{}]+)/gi));
    if (matches.length) return String(matches[matches.length - 1][1]).trim();
    const first = String(parsed.message || '').split('\n')[0].trim();
    return first.split(/:\s|\.\s/)[0].trim() || typeName;
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
    const frames = parsed.stack ? parsed.stack.split('\n').filter((line) => /^\s*at\s/.test(line)).length : 0;
    const typeName = parsed.type.split('.').pop() || parsed.type;
    const causeName = failureCauseName(parsed);
    const causeTitle = causeName === typeName ? parsed.type : `Derived from error message · reported type: ${typeName}`;
    const yamlLink = failedStep && (D.recordingYaml || D.originalYaml) ? `<button type="button" class="yamllink" data-yaml-step="${esc(failedStep.i)}">View YAML</button>` : '';
    return `<section class="failurepanel" aria-labelledby="failure-title">
      <div class="failurehead"><span class="failureicon" aria-hidden="true">!</span><span class="failuretitle" id="failure-title">ERROR</span>${D.meta && D.meta.failureCode ? `<span class="failurecode">${esc(D.meta.failureCode)}</span>` : ''}</div>
      ${failedTool ? `<div class="failuretool"><div class="k">Failed tool call</div><div class="failuretoolvalue"><span class="failuretoolname">${esc(failedTool.label)}</span>${failedTool.tool ? `<code class="failuretoolargs mono">${esc(failedTool.tool)}</code>` : ''}${yamlLink}</div></div>` : yamlLink}
      <div class="failurebody"><div class="failurefield"><div class="k">Cause</div><span class="failuretype" title="${esc(causeTitle)}">${esc(causeName)}</span></div><div class="failurefield"><div class="k">Message</div><div class="failuremessage">${failureMessageHtml(parsed.message)}</div></div></div>
      ${parsed.stack ? `<details class="failurestack" open><summary>Stack trace<span class="frames">${frames} frame${frames === 1 ? '' : 's'}</span></summary><pre class="mono">${esc(parsed.stack)}</pre></details>` : ''}
    </section>`;
  };

  const renderSelfHealSummary = (groups) => {
    const status = String((D.meta && D.meta.status) || '').toLowerCase();
    if (!(D.meta && D.meta.selfHeal) || (status !== 'passed' && status !== 'success')) return '';
    const healedGroup = groups.find((g) => g.header && g.header.selfHeal);
    if (!healedGroup) return `<section class="selfhealpanel" aria-labelledby="selfheal-title"><div class="selfhealhead"><span class="selfhealicon" aria-hidden="true">✓</span><span class="selfhealtitle" id="selfheal-title">SELF-HEALED</span></div></section>`;
    const healed = healedGroup.header;
    const parsed = parseFailure(healed.selfHealError);
    return `<section class="selfhealpanel" aria-labelledby="selfheal-title">
      <div class="selfhealhead"><span class="selfhealicon" aria-hidden="true">✓</span><span class="selfhealtitle" id="selfheal-title">SELF-HEALED</span></div>
      <div class="selfhealbody">
        <div class="selfhealfield"><div class="k">Failed recorded action</div><span class="selfhealtoolname">${esc(healed.selfHealTool || 'Recorded action')}</span></div>
        <div class="selfhealfield"><div class="k">Recovery</div><div class="selfhealmessage">Trailblaze used AI to recover this step.${parsed && parsed.message ? ` <span title="${esc(parsed.type)}">${esc(parsed.message)}</span>` : ''}</div>${D.recordingYaml || D.originalYaml ? `<button type="button" class="yamllink" data-yaml-step="${esc(healed.i)}">View YAML</button>` : ''}</div>
      </div>
    </section>`;
  };

  const idxOf = (i) => Math.max(0, traceModel().indexById.get(i) ?? -1);
  // What a row IS, independent of where it sits: its capture time and label. Only the live seam
  // needs this, to recognize the reader's selected row again after a push renumbered the trace.
  // A row with no capture time has no identity to match on, so it yields null and is left alone.
  const traceRowKey = (t) => (t && t.ts ? String(t.ts) + ' ' + String(t.label || '') : null);
  const shotForStep = (i) => {
    const at = idxOf(i);
    // Resolve a row to its inlined screenshot — but only if the image is actually present in
    // D.shots. A screenshotFile whose inline failed (the Share path skips failed fetches;
    // run-report-cli skips files dataUri() can't read) must NOT short-circuit the fallbacks and
    // leave the pane empty.
    const shot = (r) => (r && r.screenshotFile) ? safeImageSrc(D.shots[r.screenshotFile]) || null : null;
    // 1. The row's own frame — the screen it acted on (action/tool rows carry their pre-action frame).
    let s = shot(D.trace[at]);
    if (s) return s;
    // 2. A folded row whose own span captured nothing borrows from the dispatches it absorbed: they
    // are the interactions it stands for, so their first frame beats a neighbouring row's screen.
    for (const c of (D.trace[at] && D.trace[at].children) || []) { s = shot(c); if (s) return s; }
    // 3. Screenshot-less rows (step/objective headers, agent-reasoning turns) show the NEXT frame —
    // the screen this step is about to act on. Bounded to THIS step: stop at the next objective
    // header so a frameless middle step never previews a future step's screen.
    for (let k = at + 1; k < D.trace.length && !D.trace[k].objective; k++) {
      s = shot(D.trace[k]);
      if (s) return s;
    }
    // 4. Nothing usable ahead in this step: fall back to the nearest earlier frame so the pane is
    // never empty.
    for (let k = at - 1; k >= 0; k--) {
      s = shot(D.trace[k]);
      if (s) return s;
    }
    return null;
  };

  // The child dispatch a (step, kid) selection previews — its own frame and tap/swipe mark. A row
  // selection (kid null) previews the row's own frame: a folded row stands for its FIRST dispatch,
  // and every dispatch it absorbed is its own timeline entry the transport and playback walk
  // through, so the row has no reason to jump ahead to the batch's last interaction.
  const selectedChild = (row) => (st.kid != null && row && row.children) ? row.children[st.kid] || null : null;

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
      return `<svg class="swipe" viewBox="0 0 ${esc(mk.dw)} ${esc(mk.dh)}" preserveAspectRatio="none">
        <defs><marker id="ah${esc(t.i)}" markerWidth="5" markerHeight="5" refX="2.5" refY="2.5" orient="auto"><path d="M0,0 L5,2.5 L0,5 Z" fill="#5e9bff"/></marker></defs>
        <line x1="${esc(mk.x1)}" y1="${esc(mk.y1)}" x2="${esc(mk.x2)}" y2="${esc(mk.y2)}" stroke="#5e9bff" stroke-width="6" marker-end="url(#ah${esc(t.i)})" /></svg>`;
    }
    // A failed assertion gets the red full-screen border (matches the old report's
    // ScreenshotAnnotation), keyed off the action's own `succeeded` flag.
    if (mk.kind === 'assert' && mk.ok === false) return `<div class="markborder"></div>`;
    const left = (mk.x / mk.dw) * 100;
    const top = (mk.y / mk.dh) * 100;
    const cls = mk.kind === 'assert' ? 'assertok' : 'tap';
    return `<div class="mark ${cls}" style="left:${esc(left)}%;top:${esc(top)}%"></div>`;
  };

  // Group flat trace under objective rows -> { header, num, items }. The trailhead (step 0) keeps
  // num 0 so the trail steps still read STEP 1..N.
  // A failed recorded objective followed by the same objective is the recovery retry emitted by
  // self-healing, not another authored step. Keep both attempts in one card and remember where the
  // retry begins so the UI can label the transition without inventing another step number.
  const groupTrace = () => traceModel().groups;

  // The timeline's addressable units — every trace row, each followed by the extra tool dispatches
  // a traceId fold absorbed into it that captured their own frame (see ReportTimelineEntry). The
  // scrub rail, its tick marks, the transport buttons, keyboard navigation and playback all index
  // into THIS list, so a step that tapped four targets replays as four frames rather than one.
  const timelineEntries = () => traceModel().entries;
  const entryIndexOfRow = (i) => Math.max(0, traceModel().entryIndexById.get(i) ?? -1);
  // Entry index of the current (step, kid) selection. A row's dispatches sit immediately after its
  // own entry, so the scan is bounded by that row's dispatch count. Every dispatch in a fold is
  // clickable but only the ones that captured a frame are entries, so a selection can land between
  // two of them: resolve that to the nearest entry BEFORE it, never the row's own. Falling back to
  // the row would put the rail behind the reader's selection and make Next walk backwards, onto a
  // dispatch they already passed.
  const selectedEntryIndex = () => {
    const entries = timelineEntries();
    const at = entryIndexOfRow(st.step);
    let preceding = at;
    for (let k = at; k < entries.length && entries[k].row.i === st.step; k++) {
      if (entries[k].kid === st.kid) return k;
      if (st.kid != null && entries[k].kid != null && entries[k].kid < st.kid) preceding = k;
    }
    return preceding;
  };
  // The run-clock instant the current selection describes, which is what the video frame under it
  // must show. A selected dispatch has an instant of its own - its entry carries one even when the
  // log had none - and a fold can span seconds, so falling back to the row's clock here would draw
  // that dispatch's mark over a screen from a different interaction.
  const selectedClockMs = () => {
    const entry = timelineEntries()[selectedEntryIndex()];
    if (entry && entry.kid === st.kid && entry.ts != null) return entry.ts;
    const child = selectedChild(D.trace.find((t) => t.i === st.step));
    return (child && child.ts != null) ? child.ts : stepClockMs(st.step);
  };

  // First wall-clock timestamp in the trace — the run-clock zero every row's elapsed offset is
  // measured from (parity with the legacy report's elapsed-from-session-start gutter).
  const traceT0 = () => traceModel().traceT0;
  const fmtDur = (ms) => !ms ? '' : ms < 1000 ? `${ms}ms` : `${(ms / 1000).toFixed(1)}s`;
  const fmtClock = (ms) => `${Math.floor((ms || 0) / 60000)}:${String(Math.floor(((ms || 0) % 60000) / 1000)).padStart(2, '0')}`;

  // Time-compressed scrub rail: real gaps are preserved but
  // clamped so a fast burst stays clickable and a long idle period does not consume the rail. The
  // whole axis derives from the shared steps-mode playback schedule (compressed positions AND the
  // haveTs/lo/hi timestamp coverage) so the rail, its tick marks, and the steps-mode playback
  // clock can never drift apart. Callers that already built the steps schedule pass it in.
  const timelineAxis = (schedule = buildPlaybackSchedule(timelineEntries(), null)) => {
    const entries = timelineEntries();
    const haveTs = schedule.haveTs; const lo = schedule.lo; const hi = schedule.hi;
    const pos = schedule.offsets;
    const real = [];
    entries.forEach((t, i) => {
      const raw = haveTs && t.ts != null ? t.ts - lo : (i > 0 ? real[i - 1] : 0);
      real.push(i > 0 ? Math.max(real[i - 1], raw) : Math.max(0, raw));
    });
    const span = Math.max(1, pos.length ? pos[pos.length - 1] : 0);
    const stepFrac = pos.map((p) => p / span);
    const tsFrac = (ms) => {
      if (ms == null || !haveTs || !entries.length) return null;
      const r = ms - lo;
      if (r <= real[0]) return stepFrac[0];
      for (let i = 1; i < entries.length; i++) {
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
  // Extension ids are reverse-domain transport identifiers, not useful scanning labels.
  // Keep the full id in data/title attributes while presenting the extension-owned suffix.
  const streamDisplayName = (name) => {
    const raw = String(name || 'stream');
    const markers = ['.plugin.', '.extension.', '.trailblaze.'];
    let splitAt = -1; let markerLength = 0;
    markers.forEach((marker) => {
      const at = raw.lastIndexOf(marker);
      if (at > splitAt) { splitAt = at; markerLength = marker.length; }
    });
    return splitAt >= 0 && splitAt + markerLength < raw.length ? raw.slice(splitAt + markerLength) : raw;
  };
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
    return `<div class="streamitems timelineeventitems">${events.map((e) => {
      tlEventByKey.set(e.key, e);
      const producer = streamDisplayName(e.stream);
      if (e.row) {
        const badges = rowBadgesHtml(e.row);
        const tone = e.row.tone === 'error' ? ' e' : e.row.tone === 'warn' ? ' w' : '';
        return `<details class="timelineevent${tone}" style="--stream-color:${streamColor(e.streamIndex)}" data-lazykey="${esc(e.key)}"><summary title="${esc(e.stream)}"><span class="streamdot" aria-hidden="true"></span><span class="streamtype">${esc(producer)}</span><span class="timelineeventlabel">${esc(e.row.label)}</span>${badges ? `<span class="fmtbadges">${badges}</span>` : '<span></span>'}<span class="timelineeventchev" aria-hidden="true"></span></summary><div class="fmtbody tlbody"></div></details>`;
      }
      const { semanticLabel } = normalizeEventPayload(e);
      const label = semanticLabel || 'Event';
      return `<details class="timelineevent" style="--stream-color:${streamColor(e.streamIndex)}" data-lazykey="${esc(e.key)}"><summary title="${esc(e.stream)}"><span class="streamdot" aria-hidden="true"></span><span class="streamtype">${esc(producer)}</span><span class="timelineeventlabel">${esc(label)}</span><span></span><span class="timelineeventchev" aria-hidden="true"></span></summary><pre class="mono"></pre></details>`;
    }).join('')}</div>`;
  };

  // Screen-reader value text for the scrubber's current position — used by the static render AND
  // updated in place as playback advances, so assistive tech always hears the current row.
  const scrubValueText = (pos) => {
    const entries = timelineEntries();
    const current = entries[pos];
    const trailStart = entries.findIndex((e) => e.row.objective && !e.row.trailhead);
    const hasTrailhead = entries.some((e) => e.row.objective && e.row.trailhead);
    const phase = hasTrailhead && (trailStart < 0 || pos < trailStart) ? 'Trailhead' : 'Trail';
    const label = current ? (current.child ? `${current.row.label} · ${current.child.label}` : current.row.label) : null;
    return `${phase}, item ${pos + 1} of ${entries.length}: ${label || 'Timeline item'}`;
  };

  // Objective rows are structural group headers, not selectable timeline actions. Keyboard and
  // transport navigation move between the tool-call rows inside those groups so the selection
  // rail never lands on (or visually promotes) the step container itself.
  const isSelectableTimelineRow = (t) => !!t && !t.objective && !t.terminal && st.tlEventKinds.indexOf(stepCat(t)) >= 0;
  // A dispatch entry is selectable when its row is AND it has a frame of its own to show. In steps
  // mode that means its screenshotFile actually inlined; a failed inline would be an entry showing
  // the frame before it. With a run-clock video there is always a frame at the dispatch's instant —
  // the same reason paintTimelinePane lets `hasVideo` carry a dispatch's mark — so dropping it would
  // hide an interaction the video is displaying.
  const isSelectableTimelineEntry = (e) => !!e && isSelectableTimelineRow(e.row)
    && (e.kid == null || !!tlVideo() || !!(e.child.screenshotFile && safeImageSrc(D.shots[e.child.screenshotFile])));
  const adjacentSelectableIndex = (from, direction) => {
    const entries = timelineEntries();
    for (let i = from + direction; i >= 0 && i < entries.length; i += direction) {
      if (isSelectableTimelineEntry(entries[i])) return i;
    }
    return -1;
  };

  // Resolve empty space on the scrubber to its authored objective. This keeps the rail useful
  // between dense event markers: every horizontal position can still explain which step owns that
  // part of the run. Self-heal retries keep the original authored step number.
  const scrubTimelineModel = (axis) => {
    const outcome = indexOutcome(D);
    const runFailed = outcome === 'failed';
    const failureAnchor = runFailed ? D.trace[failureAnchorIndex()] : null;
    const groups = groupTrace().filter((g) => g.header);
    const rowRange = new Map();
    const ranges = groups.map((g, groupIndex) => {
      const selfHealed = outcome === 'selfheal' && !!g.header.selfHeal;
      const failed = !selfHealed && (!g.header.ok || (runFailed && (
        g.header === failureAnchor || g.items.indexOf(failureAnchor) >= 0 || g.retryHeaders.indexOf(failureAnchor) >= 0
      )));
      const startIndex = entryIndexOfRow(g.header.i);
      const next = groups[groupIndex + 1];
      const endIndex = next ? entryIndexOfRow(next.header.i) : -1;
      const range = {
        group: g,
        token: g.header.trailhead ? 'Trailhead' : `Step ${g.num}`,
        start: axis.stepFrac[startIndex] || 0,
        end: endIndex >= 0 ? (axis.stepFrac[endIndex] || 0) : 1,
        tone: selfHealed ? 'selfhealed' : failed ? 'failed' : '',
      };
      [g.header, ...g.retryHeaders, ...g.items].forEach((row) => rowRange.set(row, range));
      return range;
    });
    return { ranges, rowRange };
  };

  const scrubStepAtFraction = (ranges, fraction) => {
    if (!ranges.length) return { token: 'Timeline', start: 0, end: 1, tone: '' };
    let lo = 0; let hi = ranges.length - 1; let selected = 0;
    while (lo <= hi) {
      const mid = Math.floor((lo + hi) / 2);
      if (ranges[mid].start <= fraction) { selected = mid; lo = mid + 1; } else hi = mid - 1;
    }
    return ranges[selected];
  };

  const scrubberHtml = (axis, events, pos) => {
    // Objective rows are authored step landmarks, not LLM calls. Although their stored tool name
    // is "agent step", painting them with --ai made most scrubber marks purple and obscured the
    // handful of real LLM turns. Ordinary structural steps use the neutral grayscale token, while
    // failed and recovered objectives reuse the same semantic status as their cards. This makes a
    // recovery or terminal failure visible on the rail without mistaking every objective for AI.
    const scrubModel = scrubTimelineModel(axis);
    const ticks = timelineEntries().map((entry, i) => {
      const t = entry.row;
      // A dispatch entry marks one interaction inside a folded row: it earns its own tick only when
      // the transport can actually land on it, so the rail's marks and playback's stops agree.
      if (entry.kid != null && !isSelectableTimelineEntry(entry)) return '';
      if (entry.kid == null && !t.objective && !t.terminal && st.tlEventKinds.indexOf(stepCat(t)) < 0) return '';
      const cat = stepCat(t);
      const authoredRange = scrubModel.rowRange.get(t);
      const currentStep = authoredRange?.token || 'Timeline';
      const objectiveTone = t.objective ? (authoredRange?.tone || '') : '';
      const color = objectiveTone === 'selfhealed' ? 'var(--status-self-healed-mark)'
        : objectiveTone === 'failed' ? 'var(--status-failed-mark)'
        : t.objective ? 'var(--timeline-objective-mark)' : catColor[cat];
      const kind = objectiveTone === 'selfhealed' ? 'Self-healed'
        : objectiveTone === 'failed' ? 'Failed'
        : t.objective ? '' : cat === 'llm' ? 'LLM' : cat === 'fail' ? 'Error' : cat === 'assert' ? 'Assertion' : 'Tool / action';
      // A semantic range below represents a recovered or failed authored step more clearly than
      // paired objective bars. A recovered objective can also appear twice (attempt + retry), so
      // this avoids duplicating its status at both boundaries.
      if (t.objective && (objectiveTone === 'selfhealed' || objectiveTone === 'failed')) return '';
      const fraction = axis.stepFrac[i] || 0;
      return `<span class="scrubtick ${t.objective ? `objective${objectiveTone ? ` ${objectiveTone}` : ''}` : `event ${cat}`}" data-scrub-step="${esc(currentStep)}" data-scrub-kind="${esc(kind)}" aria-hidden="true" style="left:calc(${fraction * 100}% - 1px);background:${color};--tick-color:${color}"></span>`;
    }).join('');
    const statusRanges = scrubModel.ranges.map((range) => {
      const tone = range.tone;
      if (tone !== 'selfhealed' && tone !== 'failed') return '';
      const start = range.start; const end = range.end; const token = range.token;
      const kind = tone === 'selfhealed' ? 'Self-healed' : 'Failed';
      const color = tone === 'selfhealed' ? 'var(--status-self-healed-mark)' : 'var(--status-failed-mark)';
      // Inset both edges so adjacent semantic ranges leave the step landmark visible between
      // them instead of joining into one continuous failed/self-healed outline.
      return `<span class="scrubstatusbox ${tone}" data-scrub-step="${esc(token)}" data-scrub-kind="${kind}" aria-hidden="true" style="left:calc(${start * 100}% + 2px);right:calc(${Math.max(0, 1 - end) * 100}% + 2px);--tick-color:${color}"></span>`;
    }).join('');
    const eventTicks = events.map((e) => {
      const f = axis.tsFrac(e.t); if (f == null) return '';
      const color = streamColor(e.streamIndex);
      return `<span class="scrubtick event stream" data-scrub-step="${esc(scrubStepAtFraction(scrubModel.ranges, f).token)}" data-scrub-kind="Stream" aria-hidden="true" style="left:calc(${f * 100}% - 1px);background:${color};--tick-color:${color}"></span>`;
    }).join('');
    const frac = axis.stepFrac[pos] || 0;
    const trailStart = timelineEntries().findIndex((e) => e.row.objective && !e.row.trailhead);
    const hasTrailhead = D.trace.some((t) => t.objective && t.trailhead);
    const trailFrac = trailStart >= 0 ? (axis.stepFrac[trailStart] || 0) : 1;
    const rail = hasTrailhead && trailStart < 0
      ? `<div class="scrubphasebox" style="width:100%"></div>`
      : hasTrailhead
      ? `<div class="scrubphasebox" style="width:${trailFrac * 100}%"></div><div class="scrubline trail" style="left:${trailFrac * 100}%"></div>`
      : `<div class="scrubline trail" style="left:0"></div>`;
    const phaseLabel = hasTrailhead && trailStart < 0 ? 'Timeline for Trailhead setup. The dashed box encloses Trailhead activity.'
      : hasTrailhead ? 'Timeline. Dashed box encloses Trailhead activity; solid rail marks the authored Trail.'
      : 'Timeline for the authored Trail.';
    const playbackLabel = st.playing ? 'Stop' : 'Play';
    const previousAction = adjacentSelectableIndex(pos, -1);
    const nextAction = adjacentSelectableIndex(pos, 1);
    const transport = `<div class="scrubtransport" role="group" aria-label="Timeline playback controls">
      <button type="button" class="timelinecontrol" id="prev" aria-label="Previous tool call" title="Previous tool call"${previousAction < 0 ? ' disabled' : ''}><span class="transporticon direction" aria-hidden="true"></span></button>
      <button type="button" class="timelinecontrol play" id="tlplay" aria-label="${playbackLabel} timeline" title="${playbackLabel} timeline">${st.playing ? '<span class="transporticon stopicon" aria-hidden="true"></span>' : '<svg class="transporticon playicon" viewBox="0 0 24 24" aria-hidden="true"><path d="M7 3.5v17L20 12Z" fill="currentColor"/></svg>'}</button>
      <button type="button" class="timelinecontrol" id="next" aria-label="Next tool call" title="Next tool call"${nextAction < 0 ? ' disabled' : ''}><span class="transporticon direction" aria-hidden="true"></span></button>
    </div>`;
    return `<div class="scrub"><div class="scrubclock">0:00</div><div class="scrubtrack" data-scrub role="slider" tabindex="0" aria-label="${phaseLabel}" aria-valuemin="1" aria-valuemax="${timelineEntries().length}" aria-valuenow="${pos + 1}" aria-valuetext="${esc(scrubValueText(pos))}">${rail}<span class="scrubhoverstep" data-scrubhover-range aria-hidden="true"></span>${statusRanges}${ticks}${eventTicks}<span class="scrubtooltip scrubtracktooltip" data-scrubhover aria-hidden="true" style="--tick-color:var(--timeline-objective-mark)"><span class="scrubtooltipmeta"><span class="scrubtooltiptag scrubtooltipstep" data-scrubhover-step></span><span class="scrubtooltiptag scrubtooltipkind" data-scrubhover-kind></span></span></span><div class="scrubhead" style="left:${frac * 100}%"></div></div><div class="scrubclock">${fmtClock(axis.totalMs)}</div>${transport}</div>`;
  };

  // Chat glyph for every "open this call's transcript" affordance (timeline rows, LLM tab rows) —
  // mirrors the WASM report's per-row Chat History icon button.
  const TX_ICON_SVG = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5v8a2.5 2.5 0 0 1-2.5 2.5H9.4L5.7 19.7A1 1 0 0 1 4 18.9Z" fill="currentColor"/></svg>';
  const txOpenBtnHtml = (llmIndex, context) =>
    `<button type="button" class="txopenbtn" data-tx="${esc(llmIndex)}" aria-label="Open LLM transcript${context ? ` for ${esc(context)}` : ''}" title="LLM transcript">${TX_ICON_SVG}</button>`;

  // Public reports stamp each produced row with the exact LLM-list index. Trace ids are only an
  // extraction-time join key: one id can cover a whole tool batch, so it cannot identify a single
  // transcript after the report has been compacted.
  const llmIndexForTraceRow = (row) => {
    if (row.llm != null && row.llm >= 0 && row.llm < D.llm.length) return row.llm;
    return null;
  };

  const INSPECTOR_CODE_ICON_SVG = '<svg class="inspactionicon" viewBox="0 0 16 16" aria-hidden="true"><path d="m6 4-4 4 4 4M10 4l4 4-4 4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const INSPECTOR_TREE_ICON_SVG = '<svg class="inspactionicon" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 3.5h3v3H3zM10 3.5h3v3h-3zM10 10h3v3h-3zM6 5h2.25A1.75 1.75 0 0 1 10 6.75v4.75M8.25 8.25H10" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
  const INSPECTOR_COPY_ICON_SVG = '<svg class="inspactionicon" viewBox="0 0 16 16" aria-hidden="true"><rect x="5" y="5" width="8" height="8" rx="1.5" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="M3 11V4.5A1.5 1.5 0 0 1 4.5 3H11" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';

  const stepRowHtml = (t, child) => {
    const cat = stepCat(t); const sel = t.i === st.step;
    if (!t.objective && !t.terminal && st.tlEventKinds.indexOf(cat) < 0) return '';
    const icon = stepIcon(t);
    // A handful of children (an agent `tap` resolving to its executor) reads best inline; a
    // composite tool's long dispatch list (a scripted trailhead's sign-in) collapses to one
    // summary line that keeps what matters — the dispatch count, the biggest time sink, and every
    // failed dispatch with its error, shown even while collapsed — so the failure the reader came
    // for is reachable without expanding the plumbing.
    //
    // Each dispatch is itself selectable (WASM-report parity): activating it previews THAT
    // dispatch's own frame in the pane and expands its full args below it, so a batched step's
    // every interaction is followable, not just the row's first frame.
    const kidList = t.children || [];
    const kidSelected = (k) => sel && st.kid === k;
    const kidRow = (c, k) => `<div class="kid${c.ok === false ? ' bad' : ''}${kidSelected(k) ? ' sel' : ''}" data-kidsel="${esc(t.i)}:${k}" role="button" tabindex="0"${kidSelected(k) ? ' aria-current="step"' : ''}>${c.note ? `<blockquote class="stepreason">“${esc(c.note)}”</blockquote>` : ''}<span class="mono">${esc(c.label)}</span>${(c.count || 1) > 1 ? `<span class="kcount">×${esc(c.count)}</span>` : ''}<span class="kt mono">${esc(c.tool)}</span>${c.ms != null ? `<span class="kms">${esc(fmtDur(c.ms) || '0ms')}</span>` : ''}</div>${kidSelected(k) && c.args ? `<pre class="toolargs mono">${esc(c.args)}</pre>` : ''}${kidSelected(k) && c.result != null ? `<div class="kidresult"><div class="kidresultlabel">Result${c.resultVaries ? '<span>varies across folded calls</span>' : ''}</div><pre class="mono">${esc(c.result)}</pre></div>` : ''}${c.ok === false && (c.err || c.code) ? `<div class="kiderr">${c.code ? `<span class="kidcode">${esc(c.code)}</span>` : ''}${esc(c.err || '')}</div>` : ''}`;
    const kidRows = kidList.map(kidRow).join('');
    const dispatchCount = kidList.reduce((n, c) => n + (c.count || 1), 0);
    const failedCount = kidList.reduce((n, c) => n + (c.ok === false ? (c.count || 1) : 0), 0);
    // A selected child forces its list open — a deep-linked `?kid=` must never land on a
    // collapsed summary hiding the selection.
    const kidsOpen = !!st.kidsOpen[t.i] || (sel && st.kid != null);
    const slowest = kidList.reduce((a, c) => ((c.ms || 0) > ((a && a.ms) || 0) ? c : a), null);
    const failedRows = kidList.map((c, k) => ({ c, k })).filter(({ c }) => c.ok === false).map(({ c, k }) => kidRow(c, k)).join('');
    const stepId = esc(t.i);
    const kidSummary = `<div class="kidsummary${kidsOpen ? ' open' : ''}" data-kids="${stepId}" data-open="${kidsOpen ? 1 : 0}" role="button" tabindex="0" aria-expanded="${kidsOpen}">${esc(dispatchCount)} tool dispatches${failedCount ? ` · <span class="bad">${esc(failedCount)} failed</span>${[...new Set(kidList.filter((c) => c.ok === false && c.code).map((c) => c.code))].map((code) => `<span class="kidcode">${esc(code)}</span>`).join('')}` : ''}${slowest && slowest.ms ? ` · slowest <span class="mono">${esc(slowest.label)}</span> ${esc(fmtDur(slowest.ms))}` : ''}</div>`;
    const kids = !kidList.length ? ''
      : kidList.length <= 4
      ? `<div class="kids">${kidRows}</div>`
      : `<div class="kids">${kidSummary}${kidsOpen ? kidRows : failedRows}</div>`;
    const count = t.count ? ` <span style="color:var(--sub);font-variant-numeric:tabular-nums">×${esc(t.count)}</span>` : '';
    const t0 = traceT0();
    const rel = (t.ts != null && t0 != null) ? `+${((t.ts - t0) / 1000).toFixed(1)}s` : '';
    const dur = fmtDur(t.ms);
    const time = (rel || dur) ? `<span class="ts">${esc(rel)}${dur ? `<span class="dur">${esc(dur)}</span>` : ''}</span>` : '';
    // An LLM-call row shows the call's own accounting (model + tokens, from the linked llm entry)
    // as its detail line — the same metadata the WASM report's "LLM Request" child row carries.
    const llmIndex = t.llm != null ? llmIndexForTraceRow(t) : null;
    const llmCall = llmIndex != null ? D.llm[llmIndex] : null;
    // Keep the dense timeline categorical. Producer-specific labels such as "Screen Analyzer",
    // "Outer Agent", or "Koog Strategy Graph" remain available in the LLM detail view.
    const rowLabel = llmCall ? 'LLM' : t.label;
    const detail = llmCall
      ? `${llmCall.model || ''}${llmCall.inputTokens != null ? ` · in ${fmtN(llmCall.inputTokens)}` : ''}${llmCall.outputTokens != null ? ` · out ${fmtN(llmCall.outputTokens)}` : ''}`
      : t.tool;
    const row = `<div class="step${sel ? ' sel' : ''}${child ? ' child' : ''}${t.selfHealSource ? ' selfheal' : ''}${llmCall ? ' llmturn' : ''}" data-step="${stepId}" role="button" tabindex="${sel ? 0 : -1}"${sel ? ' aria-current="step"' : ''}>

      ${child ? '' : `<span class="num">${stepId}</span>`}
      <span class="ic ${icon.cls}"${icon.cls === 'dot' ? ` style="--icon-color:${catColor[cat]}"` : ''} aria-hidden="true">${icon.glyph}</span>
      <div style="flex:1;min-width:0">
        ${t.note ? `<blockquote class="stepreason">“${esc(t.note)}”</blockquote>` : ''}
        <div class="lbl">${esc(rowLabel)}${count}</div>
        ${t.params && t.params.length ? t.params.map((p) => `<div class="tl-tool mono">${esc(p)}</div>`).join('') : detail ? `<div class="tl-tool mono">${esc(detail)}</div>` : ''}
        ${sel && st.kid == null && t.args ? `<pre class="toolargs mono">${esc(t.args)}</pre>` : ''}
      </div>
      ${time}
      ${kids}
    </div>`;
    // The transcript affordance remains a sibling of its row because the row itself is a button.
    // UI inspection belongs to the device preview and is rendered once for the selected step.
    const affordances = llmCall ? txOpenBtnHtml(llmIndex, `call ${Number(llmIndex) + 1}`) : '';
    return affordances ? `<div class="steprow">${row}${affordances}</div>` : row;
  };

  const renderTimeline = () => {
    const groups = groupTrace();
    const failureSummary = renderFailureSummary(groups);
    const selfHealSummary = renderSelfHealSummary(groups);
    const runFailed = ['failed', 'error'].indexOf(String((D.meta && D.meta.status) || '').toLowerCase()) >= 0;
    const failureAnchor = runFailed ? D.trace[failureAnchorIndex()] : null;
    const failureGroup = failureAnchor && groups.find((g) => g.header === failureAnchor || g.items.indexOf(failureAnchor) >= 0);
    const healedGroup = groups.find((g) => g.header && g.header.selfHeal);
    const selfHealAnchor = healedGroup && (healedGroup.items.find((t) => t.selfHealSource)
      || (healedGroup.retryAt.length ? healedGroup.items[Math.max(0, healedGroup.retryAt[0] - 1)] : healedGroup.items[0])
      || healedGroup.header);
    const streams = sessionEvents(D) || [];
    tlEventByKey.clear();
    const events = streamEvents();
    const kindCounts = Object.fromEntries(TIMELINE_EVENT_KINDS.map((kind) => [kind, D.trace.filter((t) => !t.objective && !t.terminal && stepCat(t) === kind).length]));
    const eventChooser = `<details class="streamselect eventselect" data-eventselect${st.tlEventMenuOpen ? ' open' : ''}><summary aria-label="Events, ${st.tlEventKinds.length} of ${TIMELINE_EVENT_KINDS.length} selected">${TIMELINE_FILTER_ICON_SVG}<span>Events</span><span class="streamselectcount">${st.tlEventKinds.length}/${TIMELINE_EVENT_KINDS.length}</span></summary><div class="streammenu"><div class="streammenuhead"><span>Events · ${st.tlEventKinds.length}/${TIMELINE_EVENT_KINDS.length}</span><span class="streammenuactions"><button type="button" data-tlkinds="all">All</button><button type="button" data-tlkinds="none">None</button></span></div>${TIMELINE_EVENT_KINDS.map((kind) => { const meta = timelineEventKindMeta[kind]; return `<label class="streamoption" style="--stream-color:${meta.color}"><input type="checkbox" data-tlkind="${kind}"${st.tlEventKinds.indexOf(kind) >= 0 ? ' checked' : ''}><span class="streamoptiondot" aria-hidden="true"></span><span class="streamname">${meta.label}</span><span class="streamcount">${kindCounts[kind]}</span>${TIMELINE_CHECK_ICON_SVG}</label>`; }).join('')}</div></details>`;
    const streamChooser = streams.length ? `<details class="streamselect" data-streamselect${st.tlMenuOpen ? ' open' : ''}><summary aria-label="Streams, ${st.tlStreams.length} of ${streams.length} selected">${TIMELINE_FILTER_ICON_SVG}<span>Streams</span><span class="streamselectcount">${st.tlStreams.length}/${streams.length}</span></summary><div class="streammenu"><div class="streammenuhead"><span>Event streams · ${st.tlStreams.length}/${streams.length}</span><span class="streammenuactions"><button type="button" data-tlstreams="all">All</button><button type="button" data-tlstreams="none">None</button></span></div>${streams.map((stream, i) => `<label class="streamoption" style="--stream-color:${streamColor(i)}" title="${esc(stream.name)}"><input type="checkbox" data-tlstream="${i}"${st.tlStreams.indexOf(i) >= 0 ? ' checked' : ''}><span class="streamoptiondot" aria-hidden="true"></span><span class="streamname">${esc(streamDisplayName(stream.name))}</span><span class="streamcount">${stream.total || (stream.events || []).length}</span>${TIMELINE_CHECK_ICON_SVG}</label>`).join('')}</div></details>` : '';
    const outcome = indexOutcome(D);
    const outcomeLabel = outcome === 'failed' && failureGroup && failureGroup.header
      ? (failureGroup.header.trailhead ? 'failed trailhead' : `failed step ${failureGroup.num}`)
      : indexOutcomeLabel(outcome);
    const failureStepToken = failureGroup && failureGroup.header && failureGroup.header.trailhead ? 'TRAILHEAD' : failureGroup ? `STEP ${failureGroup.num}` : 'ERROR';
    const selfHealStepToken = healedGroup && healedGroup.header && healedGroup.header.trailhead ? 'TRAILHEAD' : healedGroup ? `STEP ${healedGroup.num}` : 'RECOVERY';
    const outcomeControl = outcome === 'failed' && failureAnchor
      ? `<button type="button" class="statusjump failedjump" data-failure-step="${esc(failureAnchor.i)}" title="Go to ${esc(outcomeLabel)}" aria-label="Go to ${esc(outcomeLabel)}"><span class="statusjumplabel">Failed</span><span class="statusjumptoken">${esc(failureStepToken)}</span></button>`
      : outcome === 'selfheal' && selfHealAnchor
      ? `<button type="button" class="statusjump selfhealjump" data-selfheal-step="${esc(selfHealAnchor.i)}" title="Go to self-healed ${esc(selfHealStepToken.toLowerCase())}" aria-label="Go to self-healed ${esc(selfHealStepToken.toLowerCase())}"><span class="statusjumplabel">Self-healed</span><span class="statusjumptoken">${esc(selfHealStepToken)}</span></button>`
      : `<span class="badge ${esc(outcome)}">${esc(outcome === 'passed' ? 'PASSED' : outcomeLabel)}</span>`;
    const controls = `<div class="timelinecontrols">${outcomeControl}<span class="timelinefilters">${eventChooser}${streamChooser}</span></div>`;
    // An event-only session (e.g. a run that failed before its first step) still gets its streams:
    // the chooser plus a flat stream list — there are no steps to bucket the events under.
    if (!D.trace.length) return `<div class="timeline-list">${controls}<div class="timelinescroll">${failureSummary}${selfHealSummary}<div class="empty">This run didn't emit any agent-task steps.</div>${streamGroupHtml(events)}</div></div>`;
    const buckets = eventBuckets(events);
    const withEvents = (t, child) => {
      const at = idxOf(t.i);
      return stepRowHtml(t, child) + streamGroupHtml(buckets[at] || []);
    };
    const hasSteps = groups.some((g) => g.header);
    let stepsHtml;
    if (!hasSteps) {
      stepsHtml = D.trace.map((t) => withEvents(t, false) + (t === failureAnchor ? failureSummary : '')).join('');
    } else {
      const anchorRow = failureAnchor;
      const groupsHtml = (phaseGroups) => phaseGroups.map((g) => {
        // The header dot reports the OBJECTIVE's outcome (from its Complete bookend), not the worst
        // row inside it: an assertion poll can fail and recover, and a trailhead's internal retry
        // loops can fail rows inside a step that succeeded. For a failed run whose failing step has
        // no failed Complete bookend (a crash), the step holding the failure anchor is still failed.
        // A recovery attempt is only a self-heal when the run ultimately succeeds. Failed runs can
        // carry the same retry metadata, but their affected step and tool rows remain failed/red.
        const selfHealed = outcome === 'selfheal' && !!(g.header && g.header.selfHeal);
        const failed = !selfHealed && (g.header ? (!g.header.ok || (runFailed && g.items.indexOf(anchorRow) >= 0)) : g.items.some((t) => !t.ok));
        const isTrailhead = g.header && g.header.trailhead;
        // The header's count keeps tool-call semantics (LLM-turn rows render below but are calls,
        // not device actions — same split the WASM header's "N tools" subtitle makes).
        const actionCount = g.items.reduce((n, t) => n + rowToolCallCount(t), 0);
        const durationMs = g.items.reduce((ms, t) => ms + (t.ms || 0), 0);
        const mood = selfHealed ? 'selfheal' : failed ? 'fail' : 'pass';
        // A step whose outcome the reader already accepts is noise in a long trail — collapse it so
        // what needs attention leads. groupOpen keeps an explicit toggle, and the selected step's
        // group, ahead of that default: the preview pane must never point into a hidden body.
        const leads = failed || selfHealed;
        const open = !g.header || groupOpen(g.header.i, leads);
        const status = [
          selfHealed ? 'Self-healed' : failed ? 'Failed' : 'Succeeded',
          actionCount ? `${actionCount} action${actionCount === 1 ? '' : 's'}` : '',
          fmtDur(durationMs),
        ].filter(Boolean).join(' · ');
        const hdr = g.header ? `<button type="button" class="grphdr${isTrailhead ? ' trailhead' : ''}" data-group="${g.header.i}" aria-expanded="${open}" data-group-leads="${leads}">
            ${STEP_DISCLOSURE_SVG}
            <span class="chip ${mood}">${isTrailhead ? 'TRAILHEAD' : `STEP ${g.num}`}</span>
            <span class="lbl">${esc(g.header.label)}</span>
            <span class="grpstatus ${mood}">${STEP_STATUS_ICON_SVG[mood]}${esc(status)}</span>
          </button>` : '';
        const headerEvents = g.header ? streamGroupHtml(buckets[idxOf(g.header.i)] || []) : '';
        const inlineHeaderFailure = failureSummary && g === failureGroup && (!anchorRow || g.header === anchorRow) ? failureSummary : '';
        const inlineHeaderSelfHeal = selfHealSummary && g === healedGroup && selfHealAnchor === g.header ? selfHealSummary : '';
        const items = g.items.map((t, itemIndex) => `${g.retryAt.indexOf(itemIndex) >= 0 ? `<div class="retrydivider"><span>Retry ${g.retryAt.indexOf(itemIndex) + 1}</span></div>` : ''}${withEvents(t, hasSteps)}${failureSummary && g === failureGroup && t === anchorRow ? failureSummary : ''}${selfHealSummary && g === healedGroup && t === selfHealAnchor ? selfHealSummary : ''}`).join('');
        // Collapsed bodies stay in the document and hide, the way the phase sections do: playback
        // paints the moving selection in place, and it has to find the row it is moving to.
        return `<div class="stepgroup${selfHealed ? ' selfhealed' : failed ? ' failed' : ''}">${hdr}<div class="stepgroupbody"${open ? '' : ' hidden'}>${headerEvents}${inlineHeaderFailure}${inlineHeaderSelfHeal}${items}</div></div>`;
      }).join('');
      const trailheadGroups = groups.filter((g) => g.header && g.header.trailhead);
      const trailGroups = groups.filter((g) => !g.header || !g.header.trailhead);
      const trailStepCount = trailGroups.filter((g) => g.header).length;
      // The steps a failure cut off. Nothing in the logs records them — a step that never started
      // emitted no objective — so the timeline used to just stop, taking with it the "which stage
      // did it die in?" read that the remaining steps give a triager. Only for a failed run: a
      // passing run has no missing tail, and a shorter declared list than what ran means the run
      // didn't come from this YAML.
      const skippedSteps = (() => {
        if (!runFailed) return [];
        const declared = declaredTrailSteps(D.originalYaml);
        const ran = trailGroups.filter((g) => g.header);
        if (declared.length <= ran.length) return [];
        // Trust the tail only when the head lines up. A declared list that disagrees with what
        // actually ran is a YAML we misread, and inventing step numbers beats showing none.
        const key = (s) => String(s == null ? '' : s).replace(/\s+/g, ' ').trim().toLowerCase().slice(0, 40);
        if (ran.some((g, i) => key(g.header.label) !== key(declared[i]))) return [];
        return declared.slice(ran.length);
      })();
      // Deliberately inert: no data-group, no button, nothing to select. There is no trace row
      // behind these, so every affordance the timeline offers a step would dead-end.
      const skippedHtml = skippedSteps.map((label, i) => `<div class="stepgroup skipped">
          <div class="grphdr">
            <span class="chip skip">STEP ${trailStepCount + i + 1}</span>
            <span class="lbl">${esc(label)}</span>
            <span class="grpstatus skip">${STEP_STATUS_ICON_SVG.skip}Not run</span>
          </div>
        </div>`).join('');
      const phaseStats = (phaseGroups) => {
        const actions = phaseGroups.reduce((n, g) => n + g.items.reduce((m, t) => m + rowToolCallCount(t), 0), 0);
        const duration = phaseGroups.reduce((ms, g) => ms + g.items.reduce((sum, t) => sum + (t.ms || 0), 0), 0);
        return { actions: `${actions} action${actions === 1 ? '' : 's'}`, duration: duration ? fmtDur(duration) : '' };
      };
      const trailheadStats = phaseStats(trailheadGroups);
      const trailStats = phaseStats(trailGroups);
      const phaseDisclosure = '<span class="phasedisclosure" aria-hidden="true"><svg class="phasechev" viewBox="0 0 16 16"><path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></span>';
      stepsHtml = `<div class="timelinephases">
        ${trailheadGroups.length ? `<section class="tlphase trailhead" aria-labelledby="trailhead-heading"><div class="tlphasehead"><button type="button" class="phasecontrol" data-phase="trailhead" aria-expanded="${st.trailheadOpen}">${phaseDisclosure}<span class="name" id="trailhead-heading">Trailhead</span><span class="desc">${trailheadStats.actions}</span>${trailheadStats.duration ? `<span class="phaseduration">${trailheadStats.duration}</span>` : ''}</button></div><div class="tlphasebody"${st.trailheadOpen ? '' : ' hidden'}><div class="steps">${groupsHtml(trailheadGroups)}</div></div></section>` : ''}
        ${trailGroups.length || skippedSteps.length ? `<section class="tlphase" aria-labelledby="trail-heading"><div class="tlphasehead"><button type="button" class="phasecontrol" data-phase="trail" aria-expanded="${st.trailOpen}">${phaseDisclosure}<span class="name" id="trail-heading">Trail</span><span class="counttoken">${trailStepCount}${skippedSteps.length ? `/${trailStepCount + skippedSteps.length}` : ''}</span><span class="desc">${trailStats.actions}</span>${trailStats.duration ? `<span class="phaseduration">${trailStats.duration}</span>` : ''}</button></div><div class="tlphasebody"${st.trailOpen ? '' : ' hidden'}><div class="steps">${groupsHtml(trailGroups)}${skippedHtml}</div></div></section>` : ''}
      </div>`;
    }
    const cur = D.trace.find((t) => t.i === st.step) || D.trace[0];
    // A selected child dispatch previews ITS own frame + tap/swipe mark (falling back to the
    // row's frame when the dispatch captured none) — how a folded batch's every interaction is
    // visible, not just the row's first frame. The explicit child selection also wins over the
    // run-clock video frame: the reader asked for that dispatch, and the row's clock can't
    // address one dispatch inside the fold.
    const paneCapture = selectedChild(cur);
    const captureShot = paneCapture && paneCapture.screenshotFile ? safeImageSrc(D.shots[paneCapture.screenshotFile]) || null : null;
    const shot = captureShot || shotForStep(st.step);
    const paneLabel = paneCapture ? `${cur.label} · ${paneCapture.label}` : cur.label;
    // The mark for a selected dispatch's own frame — its screenshot here, or the video frame at its
    // instant below. A dispatch owns that frame, so the row's mark must never be drawn over it: it
    // would circle a point from a different interaction than the one on screen. Null when the
    // selection is the row itself.
    const captureMark = paneCapture
      ? (paneCapture.mark ? markHtml({ i: `${cur.i}k${st.kid}`, mark: paneCapture.mark }) : '')
      : null;
    const paneMark = captureShot ? captureMark : (cur.screenshotFile ? markHtml(cur) : '');
    const pos = selectedEntryIndex();
    const inspectable = stepInspectable(cur);
    // An inspectable row's hierarchy and screenshot are one capture and must remain paired. A
    // nearby video frame can show a different screen for a long-running composite tool (notably a
    // Trailhead), which makes the inspector look corrupt even though its tree matches its own
    // capture. Keep that exact screenshot in the static preview; playback still uses the video,
    // and video remains the fallback for rows without an inspectable capture.
    const v = tlVideo();
    // Playback paints frames into `#tlvframe` and nothing else, so a capture that displaces the
    // frame box would freeze the preview on one screenshot for the whole run. While playing, the
    // video always wins.
    const clockAtStep = v && (st.playing || !captureShot) ? selectedClockMs() : null;
    const cell = v && clockAtStep != null ? spriteFrameCss(v, videoFrameAt(v, clockAtStep)) : null;
    const pane = inspectable && shot && !st.playing
      ? `<div class="shotwrap"><img class="shot" id="shot" role="button" tabindex="0" alt="${esc(paneLabel)} at step ${pos + 1}" />${paneMark}</div>`
      : cell
      ? `<div class="shotwrap"><div class="tlvframe" id="tlvframe" role="img" aria-label="Video frame at ${esc(paneLabel)}, step ${pos + 1}" style="${spriteAspect ? `aspect-ratio:${spriteAspect};` : ''}background-size:${cell.size};background-position:${cell.position}"></div>${captureMark ?? markHtml(cur)}</div>`
      : shot
      ? `<div class="shotwrap"><img class="shot" id="shot" role="button" tabindex="0" alt="${esc(paneLabel)} at step ${pos + 1}" />${paneMark}</div>`
      : `<div class="noshot">No screenshot captured before this step.</div>`;
    return `<div class="tl">
      <div class="timeline-list">${controls}<div class="timelinescroll">${failureSummary && !failureGroup ? failureSummary : ''}${hasSteps ? stepsHtml : `<div class="steps">${stepsHtml}</div>`}</div></div>
      <div class="preview">
        <div class="devicecolumn hasinspect">
          <div class="deviceplayer${String((D.meta || {}).platform || '').toLowerCase() === 'ios' ? ' device-ios' : ''}${(cell || shot) ? '' : ' empty'}">
            ${pane}
          </div>
          <div class="previewactions">
            <button type="button" class="btn previewinspect" data-preview-inspect${inspectable ? ` data-inspect="${esc(cur.i)}"` : ' disabled'} title="${inspectable ? 'Inspect the selected step\'s UI hierarchy' : 'No UI hierarchy captured for this step'}" aria-label="${inspectable ? `Inspect UI for: ${esc(cur.label)}` : `Inspect UI unavailable for: ${esc(cur.label)}`}"><svg class="previewinspecticon" viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="m10.5 10.5 3 3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg><span>Inspect UI</span></button>
          </div>
        </div>
      </div>
    </div>`;
  };

  const fmtN = (n) => n == null ? '—' : n.toLocaleString();
  const fmtCost = (c) => c == null ? '—' : formatUsd(c);
  const decisionOf = (r) => { const t = (r.response || []).find((p) => p.kind === 'tool'); return t ? t.tool : ((r.response || []).find((p) => p.kind === 'text') ? 'text reply' : r.label); };
  // The repo's canonical LLM identity: `<provider id>/<model id>` — the form `trailblaze config`
  // prints, the CLI's "Using LLM:" line uses, and workspace LLM config keys models under. A call
  // whose log carried no provider renders the bare model id (never a guessed prefix), and a call
  // with no model at all takes the table's em-dash convention.
  const llmModelLabel = (call) => {
    const model = call && call.model ? String(call.model) : '';
    if (!model || model === '?') return '—';
    const provider = call && call.provider ? String(call.provider) : '';
    return provider ? `${provider}/${model}` : model;
  };
  // Every distinct model a set of calls used, in first-use order. A session is usually one model,
  // but a mixed run (an agent step plus an MCP-sampling call, or a mid-run model switch) genuinely
  // uses several — printing one of them as "the session's model" would be a lie, so the totals card
  // lists what it finds and the per-request table stays the per-call source of truth.
  const llmModelsUsed = (calls) => {
    const seen = [];
    for (const call of calls || []) {
      const label = llmModelLabel(call);
      if (label !== '—' && seen.indexOf(label) < 0) seen.push(label);
    }
    return seen;
  };

  // One transcript message row for the LLM tab. Short messages (the objective, tool results) show
  // in full; long ones (the system prompt, per-turn screen-state dumps) collapse behind a
  // <details> expander so the transcript stays skimmable.
  const TX_COLLAPSE_CHARS = 600;
  const txRoleClass = (role) => role === 'user' ? 'user' : role === 'assistant' ? 'assistant' : role === 'system' ? 'system' : 'tool';
  // Role word only — the tool name renders in its own span beside it (txMsgHtml), so the CSS
  // small-caps treatment can never mangle a camelCase tool name.
  const txRoleLabel = (m) => {
    const role = String(m.role || '');
    if (role === 'user') return 'Trailblaze';
    if (role === 'assistant') return 'Assistant';
    if (role === 'system') return 'System';
    if (role === 'tool_call' || role === 'tool_use') return 'Tool call';
    if (role === 'tool_result') return 'Tool result';
    // Legacy logs emit both tool calls AND results as bare `tool` turns (today's logger writes
    // tool_use/tool_result), so label them direction-neutrally rather than inverting a call into
    // its own consequence.
    if (role === 'tool' || role === 'function') return 'Tool';
    return role || 'Message';
  };
  // The transcript's two VOICES (the reason the layout is conversational at all): what the model
  // authored (assistant turns + the tool calls it chose) reads on the left with the --ai accent;
  // what our agent/harness supplied (user turns — objective, screen dumps, hints — and the tool
  // results the device reported back) reads on the right with the blue accent, chat-app style.
  // The system prompt is a quiet full-width preamble.
  const txVoice = (role) => role === 'assistant' || role === 'tool_call' || role === 'tool_use' ? 'llm'
    : role === 'system' ? 'sys' : 'user';
  const txAvatar = (m) => {
    const role = String(m.role || '');
    const voice = txVoice(role);
    const glyph = voice === 'llm' ? 'AI' : role === 'system' ? 'S' : role === 'user' ? 'T' : '⚙';
    return `<span class="txavatar ${voice}" aria-hidden="true">${glyph}</span>`;
  };
  const txMsgHtml = (m) => {
    const role = String(m.role || '');
    const voice = txVoice(role);
    const isResult = role === 'tool' || role === 'function' || role === 'tool_result';
    // Tool calls render exactly like a trail-file tool entry; tool results get the logger's
    // markdown envelope parsed away (clean body + a "raw" expander for the verbatim text);
    // everything else shows the raw message text.
    let text; let raw = null;
    if (isResult) {
      const display = transcriptToolResultDisplay(m);
      text = display ? display.text : String(m.text == null ? '' : m.text);
      raw = display ? display.raw : null;
    } else {
      const yaml = transcriptToolCallYaml(m);
      text = yaml != null ? yaml : String(m.text == null ? '' : m.text);
    }
    // The tool name rides in its own untransformed span — the role word is small-caps styled, but
    // a camelCase tool name must read exactly as authored (never uppercased).
    const roleTag = `${txAvatar(m)}<span class="txrole ${txRoleClass(role)}">${esc(txRoleLabel(m))}</span>${m.toolName ? `<span class="txtool mono">${esc(String(m.toolName))}</span>` : ''}`;
    // User prompts and tool results are both Trailblaze-authored turns. Keep their identity on the
    // right in both the compact header and the expanded <summary>; otherwise long messages snap the
    // avatar back beside the character count when their disclosure opens.
    const authoredClass = voice === 'user' ? ' user-authored' : '';
    const rawHtml = raw != null ? `<details class="txraw"><summary>raw</summary><pre class="txbody">${esc(raw)}</pre></details>` : '';
    if (text.length <= TX_COLLAPSE_CHARS) return `<div class="txmsg voice-${voice}"><div class="txhead${authoredClass}">${roleTag}</div><pre class="txbody">${esc(text)}</pre>${rawHtml}</div>`;
    return `<details class="txmsg voice-${voice}"><summary class="${authoredClass.trim()}">${roleTag}<span class="txpeek">${esc(text.slice(0, 140))}…</span><span class="txlen">${fmtN(text.length)} chars</span></summary><pre class="txbody">${esc(text)}</pre>${rawHtml}</details>`;
  };
  // The transcript conversation for one call: role-labeled messages in request order, or the
  // pending/failed/empty note. Reused on the async refresh after gz inflation lands.
  const txConversationHtml = (callIndex) => {
    const tx = sessionTranscripts(D);
    if (!tx && !D.llmMessagesGz) return `<div class="txnote">No transcript was captured for this run (older report payload).</div>`;
    const messages = transcriptCallMessages(tx, callIndex);
    const note = messages ? (messages.length ? null : 'No transcript captured for this call.')
      : transcriptInflater.inflight.has(D) ? 'Decompressing transcript…'
      : 'Could not decompress the transcript (requires DecompressionStream support).';
    return note ? `<div class="txnote">${esc(note)}</div>` : messages.map(txMsgHtml).join('');
  };

  // Resolve every LLM call back to the authored step containing its trace row. This one mapping
  // drives the transcript header, context rail, screenshot, and navigation so they cannot drift
  // into showing different steps.
  const txCallMap = () => traceModel().callMap;

  const txCallContext = (callIndex) => {
    const map = txCallMap();
    const mapped = map.byCall[callIndex];
    const group = mapped && mapped.group;
    const row = mapped && mapped.row;
    const stepAt = group ? map.stepIndexByGroup.get(group) ?? -1 : -1;
    const outcome = indexOutcome(D);
    const runFailed = outcome === 'failed';
    const anchor = runFailed ? D.trace[failureAnchorIndex()] : null;
    const anchorGroup = anchor ? traceModel().groupByRow.get(anchor) || null : null;
    const selfHealed = outcome === 'selfheal' && !!(group && group.header && group.header.selfHeal);
    const failed = !selfHealed && !!(runFailed && group && anchorGroup && group.header === anchorGroup.header);
    const rows = group ? [group.header].concat(group.items).filter(Boolean) : [];
    const rowAt = row ? rows.indexOf(row) : rows.length - 1;
    const preceding = rows.slice(0, Math.max(0, rowAt) + 1).reverse();
    const errorRow = preceding.find((item) => item && item.ok === false && item.err)
      || rows.find((item) => item && item.ok === false && item.err)
      || (group && group.header && group.header.selfHealError ? group.header : null);
    const parsed = parseFailure(errorRow && (errorRow.err || errorRow.selfHealError));
    const stepToken = group && group.header && group.header.trailhead ? 'TRAILHEAD'
      : group && group.num ? `STEP ${group.num}` : 'UNSCOPED';
    // A legacy/unscoped call has no authored step outcome to report. Avoid presenting that missing
    // relationship as a passed step: the transcript still opens, but only scoped calls get a
    // semantic outcome token.
    const scoped = !!(group && group.header);
    const passed = scoped && outcome === 'passed';
    const status = failed ? 'failed' : selfHealed ? 'selfheal' : passed ? 'passed' : 'neutral';
    const statusLabel = failed ? 'FAILED' : selfHealed ? 'SELF-HEALED' : passed ? 'PASSED' : '';
    // Most exported runs use the session video as their canonical visual record rather than
    // attaching a standalone screenshot to every LLM row. Resolve the call onto that same sprite
    // clock used by the timeline preview so opening a transcript cannot show an older fallback
    // screenshot (or an empty rail) while the report itself has the exact current frame.
    const video = row ? tlVideo() : null;
    const clock = video && row ? stepClockMs(row.i) : null;
    const videoCell = video && clock != null ? spriteFrameCss(video, videoFrameAt(video, clock)) : null;
    return {
      map, group, row, stepAt, status, statusLabel, stepToken,
      title: group && group.header ? group.header.label : 'LLM call',
      shot: !videoCell && row ? shotForStep(row.i) : null,
      video, videoCell,
      errorRow, parsed,
    };
  };

  const txContextHtml = (callIndex) => {
    const context = txCallContext(callIndex);
    const shot = context.videoCell
      ? `<div class="txscreenframe"><div class="txscreenvideo" role="img" aria-label="Screen at ${esc(context.stepToken.toLowerCase())}, call ${callIndex + 1}" style="${spriteAspect ? `--tx-screen-aspect:${spriteAspect};aspect-ratio:${spriteAspect};` : ''}background-size:${context.videoCell.size};background-position:${context.videoCell.position}"></div></div>`
      : context.shot
      ? `<div class="txscreenframe"><img src="${esc(context.shot)}" alt="Screen at ${esc(context.stepToken.toLowerCase())}, call ${callIndex + 1}"></div>`
      : `<div class="txscreenempty"><span>Screen unavailable</span><small>No frame was captured near this call.</small></div>`;
    const failure = context.parsed ? `<section class="txfailure ${esc(context.status)}">
      <span class="txcontextlabel">${context.status === 'selfheal' ? 'Recovered failure' : 'What failed'}</span>
      <strong>${esc(failureCauseName(context.parsed))}</strong>
      ${context.errorRow && context.errorRow.label ? `<span class="txfailuretool mono">${esc(context.errorRow.label)}</span>` : ''}
      <p>${esc(context.parsed.message)}</p>
    </section>` : '';
    return `<aside class="txcontext" aria-label="Current screen and step context">
      <section class="txstepcontext">
        <div class="txcontexttokens"><span class="txsteptoken">${esc(context.stepToken)}</span>${context.statusLabel ? `<span class="txstatustoken ${esc(context.status)}">${esc(context.statusLabel)}</span>` : ''}</div>
        <h2>${esc(context.title)}</h2>
      </section>
      ${shot}
      ${failure}
    </aside>`;
  };

  const txWorkspaceHtml = (callIndex) => `<div class="txworkspace">
    ${txContextHtml(callIndex)}
    <main class="txconversation" aria-label="Transcript for call ${callIndex + 1}"><div class="txconversationinner">${txConversationHtml(callIndex)}</div></main>
  </div>`;

  const viewPage = (title, meta, body, className = '') => `<section class="viewpage${className ? ` ${className}` : ''}">
    <div class="viewhead"><h2 class="viewtitle">${esc(title)}</h2>${meta ? `<span class="viewmeta">${esc(meta)}</span>` : ''}</div>
    <div class="viewbody">${body}</div>
  </section>`;

  const renderLlm = () => {
    if (!D.llm.length) return viewPage('LLM', '', `<div class="empty">This run has no LLM request logs.</div>`);
    const totals = D.llm.reduce((a, r) => ({ i: a.i + (r.inputTokens || 0), o: a.o + (r.outputTokens || 0), c: a.c + (r.totalCost || 0), k: a.k + (r.cacheReadTokens || 0), d: a.d + (r.durationMs || 0), pc: a.pc + (r.promptCost || 0), oc: a.oc + (r.completionCost || 0), s: a.s + (r.cacheSavings || 0) }), { i: 0, o: 0, c: 0, k: 0, d: 0, pc: 0, oc: 0, s: 0 });
    const haveCosts = D.llm.some((r) => r.promptCost != null || r.completionCost != null);
    // Which model(s) produced this session's calls and cost. Listed rather than reduced to one:
    // a mixed run genuinely uses several, and the per-request table ties each cost to its model.
    const modelsUsed = llmModelsUsed(D.llm);
    // Group calls by the objective they ran under (the trace's llm-stamped rows sit inside their
    // objective's step, so one trace walk recovers the mapping). "Request 12345" alone isn't
    // actionable; "which objective burned the budget" is — a deliberate improvement over the WASM
    // report's flat request list. Old payloads without stamped trace rows keep the flat list.
    const objectiveByCall = txCallMap().byCall.map((mapped) => mapped && mapped.group && mapped.group.header);
    const callGroups: Array<{ objective: any; calls: number[] }> = [];
    D.llm.forEach((_, i) => {
      const objective = objectiveByCall[i];
      const last = callGroups[callGroups.length - 1];
      if (last && last.objective === objective) last.calls.push(i);
      else callGroups.push({ objective, calls: [i] });
    });
    const grouped = callGroups.some((g) => g.objective != null);
    const groupLabel = (g) => g.objective ? String(g.objective.label || 'Step') : 'Run';
    // Per-objective subtotals on the header row — the budget question the grouping exists for.
    const groupMeta = (g) => {
      const calls = g.calls.map((i) => D.llm[i]);
      const tin = calls.reduce((n, c) => n + (c.inputTokens || 0), 0);
      const tout = calls.reduce((n, c) => n + (c.outputTokens || 0), 0);
      const cost = llmCostTotal(calls);
      return `${g.calls.length} call${g.calls.length === 1 ? '' : 's'} · in ${fmtN(tin)} · out ${fmtN(tout)}${cost != null ? ` · ${fmtCost(cost)}` : ''}`;
    };
    // Input-token composition, ported from the legacy WASM report's LLM Usage tab
    // (LlmUsageComposable.kt): aggregate the per-call comp numbers into the "what takes up space
    // in the context window" breakdown, mirroring computeUsageSummary's aggregation over the
    // requests that carry a breakdown.
    const comps = D.llm.map((call) => call.comp).filter((c) => c);
    const agg = comps.reduce((a, c) => a
      ? { system: a.system + (c.system || 0), user: a.user + (c.user || 0), tools: a.tools + (c.tools || 0), images: a.images + (c.images || 0), systemCount: a.systemCount + (c.systemCount || 0), userCount: a.userCount + (c.userCount || 0), toolsCount: a.toolsCount + (c.toolsCount || 0), imagesCount: a.imagesCount + (c.imagesCount || 0) }
      : { system: c.system || 0, user: c.user || 0, tools: c.tools || 0, images: c.images || 0, systemCount: c.systemCount || 0, userCount: c.userCount || 0, toolsCount: c.toolsCount || 0, imagesCount: c.imagesCount || 0 }, null);
    const breakdown = agg ? (() => {
      const total = agg.system + agg.user + agg.tools + agg.images;
      const segs = [
        { label: 'System prompts', v: agg.system, count: `${fmtN(agg.systemCount)} message${agg.systemCount === 1 ? '' : 's'}`, color: 'var(--run)' },
        { label: 'User prompts', v: agg.user, count: `${fmtN(agg.userCount)} message${agg.userCount === 1 ? '' : 's'}`, color: 'var(--pass)' },
        { label: 'Tool descriptors', v: agg.tools, count: `${fmtN(agg.toolsCount)} tool${agg.toolsCount === 1 ? '' : 's'}`, color: 'var(--event)' },
        ...(agg.imagesCount > 0 ? [{ label: 'Images', v: agg.images, count: `${fmtN(agg.imagesCount)} image${agg.imagesCount === 1 ? '' : 's'}`, color: 'var(--amber)' }] : []),
      ];
      const pct = (v) => total > 0 ? `${Math.round((v / total) * 1000) / 10}%` : '0%';
      return `<div class="card llmbreak"><div style="font-size:12px;font-weight: var(--font-weight-emphasis);color:var(--sub)">Input token breakdown · estimated split of the reported input tokens</div>
        <div class="llmbreakbar" aria-hidden="true">${segs.filter((s) => s.v > 0).map((s) => `<span style="width:${total > 0 ? (s.v / total) * 100 : 0}%;background:${s.color}"></span>`).join('')}</div>
        ${segs.map((s) => `<div class="llmbreakcat"><span class="llmbreakdot" style="background:${s.color}"></span><span class="llmbreaklabel">${esc(s.label)}</span><span class="llmbreaktokens">${fmtN(s.v)}</span><span class="llmbreakpct">${pct(s.v)}</span><span class="llmbreakcount">${esc(s.count)}</span></div>`).join('')}
        <div class="llmbreaktotal">${fmtN(total)} input tokens · aggregated across ${comps.length === D.llm.length ? `all ${D.llm.length}` : `${comps.length} of ${D.llm.length}`} request${D.llm.length === 1 ? '' : 's'}</div>
        <div class="llmbreaknote">These four categories are measured; conversation history and the per-turn screen state after the first are not, and their tokens are distributed across the categories so the split sums to the reported total. A category growing across a run can therefore be history growing, not that category.</div></div>`;
    })() : '';
    // Per-request table mirroring the WASM report's Per-Request Details columns, grouped by
    // objective (full-width group rows with per-objective subtotals). This is the tab's ONLY
    // per-call surface — activating a row (or its chat button) opens the transcript lightbox,
    // which is the detail view; there is no master list or inline detail pane. Numbering stays
    // global across groups so `?llm=N` deep links are stable. A call with no composition shows
    // em-dashes (never zeros); the Images cell is an em-dash when no images were sent.
    const tableRowHtml = (call, i, inGroup = false) => {
      const c = call.comp;
      return `<tr class="llmrow${i === st.llmSel ? ' sel' : ''}${inGroup ? ' grouped' : ''}" data-llm="${i}" tabindex="0"${i === st.llmSel ? ' aria-current="true"' : ''}>
        <td class="llmreq">${i + 1}. ${esc(decisionOf(call))}</td>
        <td class="llmmodel mono" title="${esc(llmModelLabel(call))}">${esc(llmModelLabel(call))}</td>
        <td class="num">${c ? fmtN(c.system) : '—'}</td>
        <td class="num">${c ? fmtN(c.user) : '—'}</td>
        <td class="num">${c ? fmtN(c.tools) : '—'}</td>
        <td class="num">${c && c.imagesCount > 0 ? fmtN(c.images) : '—'}</td>
        <td class="num"><span style="font-weight: var(--font-weight-emphasis)">${fmtN(call.inputTokens)}</span>${call.cacheReadTokens ? `<span class="llmcached">${fmtN(call.cacheReadTokens)} cached</span>` : ''}</td>
        <td class="num">${fmtN(call.outputTokens)}</td>
        <td class="txcell">${txOpenBtnHtml(i, `call ${i + 1}`)}</td>
        <td class="num">${call.totalCost != null ? fmtCost(call.totalCost) : '—'}</td>
      </tr>`;
    };
    // Grouping is rendered as containment, not just a divider: each objective is its own <tbody>
    // carrying a hairline rail, the header row bands the objective's prompt (wrapped to two lines,
    // never mid-word truncated — full text in the title), and its calls are inset so the nesting
    // reads at a glance. `.grouped` is what indents the Request cell.
    const tableRows = grouped
      ? callGroups.map((g) => `<tbody class="llmgroup"><tr class="llmgrouprow"><td colspan="10" title="${esc(groupLabel(g))}"><span class="lbl">${esc(groupLabel(g))}</span><span class="llmgroupmeta">${esc(groupMeta(g))}</span></td></tr>${g.calls.map((i) => tableRowHtml(D.llm[i], i, true)).join('')}</tbody>`).join('')
      : `<tbody>${D.llm.map((call, i) => tableRowHtml(call, i)).join('')}</tbody>`;
    // No "Input (Est)" column: the estimated split is folded to sum to the reported total, so an
    // estimate column always equals Input (LLM) — two columns agreeing by construction read as an
    // independent check that isn't happening. The per-category estimates stay (that's the split).
    const table = `<div class="card llmtablewrap"><div style="font-size:12px;font-weight: var(--font-weight-emphasis);color:var(--sub)">Per-request details</div>
      <table class="llmtable${grouped ? ' grouped' : ''}"><thead><tr><th>Request</th><th>Model</th><th class="num">System</th><th class="num">User</th><th class="num">Tools</th><th class="num">Images</th><th class="num">Input (LLM)</th><th class="num">Output</th><th><span class="srlabel">Transcript</span></th><th class="num">Cost</th></tr></thead>${tableRows}</table></div>`;
    // Three stacked blocks: session totals, the context-window breakdown, and the per-request
    // table. Per-call detail lives in the transcript lightbox alone.
    return viewPage('LLM', `${D.llm.length} call${D.llm.length === 1 ? '' : 's'}`, `<div class="card"><div style="font-size:12px;font-weight: var(--font-weight-emphasis);color:var(--sub)">Session totals · ${D.llm.length} calls</div>
        <div class="totals"><div><div class="n">${fmtN(totals.i)}</div><div class="t">input tokens</div></div>
        <div><div class="n">${fmtN(totals.o)}</div><div class="t">output tokens</div></div>
        <div><div class="n">${fmtCost(totals.c)}</div><div class="t">total cost</div></div>
        ${haveCosts ? `<div><div class="n">${fmtCost(totals.pc)}</div><div class="t">input cost</div></div>
        <div><div class="n">${fmtCost(totals.oc)}</div><div class="t">output cost</div></div>` : ''}
        ${totals.k ? `<div><div class="n">${fmtN(totals.k)} <span style="font-weight: var(--font-weight-emphasis);color:var(--sub)">(${Math.round((totals.k / (totals.i || 1)) * 100)}%)</span></div><div class="t">cached input</div></div>` : ''}
        ${totals.s > 0 ? `<div><div class="n">−${fmtCost(totals.s)}</div><div class="t">cache savings · ${fmtCost(totals.c + totals.s)} without cache</div></div>` : ''}
        ${totals.d ? `<div><div class="n">${(totals.d / D.llm.length / 1000).toFixed(1)}s</div><div class="t">avg response</div></div>` : ''}</div>
        ${modelsUsed.length ? `<div class="llmmodels"><span class="k">${modelsUsed.length === 1 ? 'Model' : `Models (${modelsUsed.length})`}</span>${modelsUsed.map((m) => `<span class="v mono">${esc(m)}</span>`).join('')}</div>` : ''}</div>${breakdown}${table}`);
  };

  // Thumbnail width steps for the lightbox zoom control. The grid packs as many columns of the
  // selected width as fit (auto-fill), so stepping the width is what decides shots-per-row — and
  // a run with only a couple of screenshots keeps them thumbnail-sized instead of stretching them
  // across the whole page.
  const GAL_ZOOM_SIZES = [140, 190, 260, 360, 500];

  // Screenshot lightbox: default to the final captured frame for each authored step so the view is
  // a concise visual summary. The optional expanded mode preserves access to every tool-level frame
  // — including each folded child dispatch's own capture, so a batched step's interactions are all
  // present (frames the row's fold would otherwise hide; duplicates within a row are skipped).
  const renderLightbox = () => {
    type LightboxEntry = { trace: TraceStep; group: ReportTraceGroup; kid?: TraceChild; kidIndex?: number };
    const entries: LightboxEntry[] = groupTrace().flatMap((group): LightboxEntry[] => {
      const rows = [group.header, ...group.items].filter((row): row is TraceStep => Boolean(row));
      const has = (f) => f && safeImageSrc(D.shots[f]);
      if (!st.lightboxAll) {
        const shots = rows.filter((t) => has(t.screenshotFile));
        if (shots.length) return shots.slice(-1).map((trace) => ({ trace, group }));
        // Every frame in this group sits on folded dispatches (row screenshotFile null, children
        // captured): fall back to the group's last child frame so the default view still shows
        // the step's final screen instead of skipping the step.
        for (let r = rows.length - 1; r >= 0; r--) {
          const kidList = rows[r].children || [];
          for (let k = kidList.length - 1; k >= 0; k--) {
            if (has(kidList[k].screenshotFile)) return [{ trace: rows[r], group, kid: kidList[k], kidIndex: k }];
          }
        }
        return [];
      }
      return rows.flatMap((trace) => {
        const seen = new Set([trace.screenshotFile]);
        return [
          ...(has(trace.screenshotFile) ? [{ trace, group }] : []),
          ...(trace.children || []).flatMap((c, k) => {
            if (!has(c.screenshotFile) || seen.has(c.screenshotFile)) return [];
            seen.add(c.screenshotFile);
            return [{ trace, group, kid: c, kidIndex: k }];
          }),
        ];
      });
    });
    const cells = entries.map(({ trace, group, kid, kidIndex }) => {
      const trailhead = Boolean(group.header && group.header.trailhead);
      const token = trailhead ? 'TRAILHEAD' : (group.num ? `STEP ${group.num}` : 'RUN');
      const label = (group.header && group.header.label) || trace.label;
      const tool = kid ? `${trace.label} · ${kid.label}` : (trace !== group.header ? trace.label : '');
      const file = kid ? kid.screenshotFile : trace.screenshotFile;
      return `<button type="button" class="galcell" data-lightbox-step="${trace.i}"${kid ? ` data-lightbox-kid="${kidIndex}"` : ''}>
        <div class="galshot" data-shot="${esc(file)}" data-shot-token="${esc(token)}" data-shot-label="${esc(label)}"${tool ? ` data-shot-tool="${esc(tool)}"` : ''} role="button" tabindex="0"><img alt="${esc(label)}" /></div>
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

  // Which event bodies the reader has expanded. A <details> open state lives in the DOM and nowhere
  // in `st`, so a re-render collapses every one of them — and on a live run that is once per log
  // burst, several times a step, on an event the reader is still reading. Keys are the same
  // per-stream `data-lazykey` the lazy fill resolves through, so an event that keeps its key across
  // a push (an append, which is what a growing stream does) stays open.
  const openTimelineEventKeys = () => {
    const keys = new Set<string>();
    Array.from(root.querySelectorAll<HTMLElement>('.timelineevent[data-lazykey]')).forEach((el: any) => {
      if (el.open) keys.add(el.dataset.lazykey);
    });
    return keys;
  };
  // Reopening is not enough: the new node's body is the empty one every render emits, and the
  // 'toggle' listener only ever fires for the reader's own click, so fill it here too.
  const reopenTimelineEvents = (keys) => {
    Array.from(root.querySelectorAll<HTMLElement>('.timelineevent[data-lazykey]')).forEach((el: any) => {
      if (!keys.has(el.dataset.lazykey)) return;
      el.open = true;
      const entry = tlEventByKey.get(el.dataset.lazykey);
      if (entry) fillLazyBody(el, entry.row, entry);
    });
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
      <div class="vframe" id="vframe" style="${spriteAspect ? `aspect-ratio:${spriteAspect};` : ''}"></div>
    </div>`);
  };

  const renderInfo = () => {
    const m = D.meta;
    // Consumer-injected `config.metadata` key/values render after the built-in rows, keys as-is.
    const rows = [['Target', m.target], ['App version', m.appVersion], ['Platform', m.platform], ['Device classifier', m.deviceClassifier], ['Device type', m.deviceType], ['Device', m.device], ['Bundle / package ID', m.appId], ['Trail', m.trailId], ['Total duration', m.duration], ['Steps', m.steps ? String(m.steps) : null], ['Ran', m.ranAt], ['Build', m.buildNumber], ['Commit', m.commitSha], ['Branch', m.branch], ...Object.entries(m.metadata || {})]
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
  // adb serial) names one concrete device. Retry groups use it together with the LANE leg, because
  // one CI worker can execute several device classes; sharing an instance id must not collapse a
  // phone, tablet, T2, and T3 into one attempt history. The LANE leg is the stable device identity
  // and keys matrix columns, so a build sharded across N interchangeable simulators is ONE column
  // instead of N mostly-dashed ones (every CI shard creates a fresh UDID). Either leg falls back to
  // the other when a payload carries only one of them.
  //
  // The lane prefers `meta.deviceClassifier` — the device's SPECIFIC compound classifier
  // (`android-phone`, `ios-ipad`, `android-kiosk`), not the broad platform family it falls back to.
  // It's the identity the CI config names, the trail files its `recordings:` under, and the results
  // store keys a cell on, so a column heading is a string the reader can carry straight back to
  // those — and it already names its own platform, so it needs no platform prefix.
  // `meta.deviceType` (the platform-stripped, ` · `-joined classifier tail) is the fallback for
  // payloads generated before deviceClassifier existed; those columns still get composed with their
  // platform below.
  const runDeviceInstance = (s) => String((s.meta && (s.meta.device || s.meta.deviceClassifier || s.meta.deviceType)) || '');
  const runDeviceType = (s) => String((s.meta && s.meta.deviceType) || '');
  const runDeviceClassifier = (s) => String((s.meta && s.meta.deviceClassifier) || '');
  const runLane = (s) => String((s.meta && (s.meta.deviceClassifier || s.meta.deviceType || s.meta.device)) || '');
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
    // A link-out run has no trace here, and neither fallback survives that: both the index
    // precompute and meta.steps are DERIVED from a trace, so on a stub both read 0 and would
    // report an agent-driven run as having called no tools.
    if (isLinkOut(s)) return null;
    if (s.toolCallCount != null) return s.toolCallCount;
    return s.meta && s.meta.steps != null ? s.meta.steps : null;
  };
  const runReportHref = (s) => safeHref(s.meta && s.meta.reportUrl);
  /**
   * Element name + attributes for an index control that opens a run — the matrix cell, the flat
   * row, the attempt row. A run carried by this document keeps the interactive in-document form the
   * caller describes (`inDocTag`/`inDocAttrs`, always including `data-session` so wire() binds it).
   * A link-out run becomes an anchor to its own report instead: no `data-session`, role, or
   * tabindex, since an anchor is already keyboard-activable and the [data-session] handler would
   * otherwise hijack the click. With no usable http(s) URL — absent, or a scheme the viewer
   * refuses — the control goes INERT rather than falling back to the in-document form, which could
   * only try to hydrate a payload the document never carried.
   *
   * `interactive` is what callers label against. An inert control still needs its outcome
   * described, but describing it as "Open …" would promise assistive tech an action that does not
   * exist, so callers word the label for the control they actually got.
   */
  const openControl = (s, inDocTag, inDocAttrs) => {
    if (!isLinkOut(s)) return { tag: inDocTag, attrs: inDocAttrs, interactive: true };
    const href = runReportHref(s);
    return href
      ? { tag: 'a', attrs: `href="${esc(href)}" target="_blank" rel="noopener noreferrer"`, interactive: true }
      : { tag: 'span', attrs: 'aria-disabled="true"', interactive: false };
  };
  // Counts a link-out run can't derive from an absent payload come from the generator; null when it
  // supplied none, which renders blank rather than as a confident zero.
  const runLlmCallCount = (s) => (isLinkOut(s) ? (s.meta.llmCallCount != null ? Number(s.meta.llmCallCount) : null) : (s.llm || []).length);
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
  // Column order: alphabetical by platform then lane, platform-less runs last.
  //
  // Labels, in priority order:
  //  1. the specific device classifier (`android-kiosk`, `ios-ipad`) verbatim;
  //  2. otherwise `platform · lane`, but only when the platform ran on more than one lane —
  //     a lone lane adds nothing the platform name doesn't already say;
  //  3. otherwise the bare platform.
  // SESSIONS is fixed for the life of the document, so the columns are computed once and shared by
  // the header strip, the matrix gate and the row renderer rather than rebuilt on every render.
  const matrixColumns = (() => {
    const byKey = new Map<string, any>();
    SESSIONS.forEach((s) => {
      const key = matrixColKey(s);
      if (!byKey.has(key)) byKey.set(key, { key, platform: runPlatform(s), lane: runLane(s), deviceClassifier: runDeviceClassifier(s) });
    });
    const cols: any[] = Array.from(byKey.values()).sort((a: any, b: any) => Number(!a.platform) - Number(!b.platform) || a.platform.localeCompare(b.platform) || a.lane.localeCompare(b.lane));
    const perPlatform = new Map<string, number>();
    cols.forEach((col: any) => perPlatform.set(col.platform, (perPlatform.get(col.platform) || 0) + 1));
    cols.forEach((col: any) => {
      col.label = col.deviceClassifier
        ? col.deviceClassifier
        : col.lane && (perPlatform.get(col.platform) as number) > 1 ? `${platformLabel(col.platform)} · ${col.lane}` : platformLabel(col.platform);
    });
    return cols;
  })();
  // Columns that actually name a device. A session carrying neither platform nor device (a bare
  // local run, an older payload) still gets a column so its runs stay visible, but that column is
  // labelled `other` and is not a device — so it neither counts toward the matrix gate nor appears
  // in the header's list of what the report covers.
  const identifiedColumns = matrixColumns.filter((col: any) => col.platform || col.lane);
  // Every device the report covers, in column order — the header's `Device classifiers` strip.
  const allDeviceLabels = identifiedColumns.map((col: any) => col.label);
  // One row per trail with a cell per device whenever the report covers more than one device.
  // Gating on the COLUMN count rather than on a platform count is what makes an N-device
  // single-platform report (five Android devices, say) a matrix instead of a flat run list — the
  // two-platform case is just the instance of that rule people happened to hit first. One device
  // has nothing to compare across, so it keeps the flat per-run rows, and letting a lone `other`
  // column tip the gate would turn a plain run list into a grid whose second column is all dashes.
  const useMatrix = identifiedColumns.length > 1;
  // Only a semantic device class belongs in the reader-facing row subtitle. `meta.device` is an
  // instance identity (often a simulator UDID or adb serial); it remains part of retry identity,
  // but must never leak into the compact index just because a sharded build used several devices.
  // Unreachable while a multi-device report is a matrix — kept for the one-column reports where a
  // device still varies (interchangeable instances of a single class).
  const qualifyFlatRowsByDevice = !useMatrix && new Set(SESSIONS.map(runDeviceType).filter(Boolean)).size > 1;
  // Cells are one uniform width — misaligned columns would defeat the point of a grid — but that
  // width has to come from the LABELS, not from a constant. The stock 164px was sized for
  // `android`/`ios`; a classifier column heading (`android-tablet`) is twice that and would just
  // ellipsis away the part that distinguishes it from its neighbour. Widen every cell to fit the
  // longest heading in the report, never below the stock width.
  //
  // Sized in ch of the .pk type ramp (10px, uppercase, .08em tracking ≈ 0.72em per character)
  // rather than measured, because the index HTML is built as a string with no layout to measure
  // against — and the CSS ellipsis is still there as the backstop if a repo's keys outrun it.
  //
  // Sized from every RENDERED column, `other` included — the header lists only real devices, but a
  // column that renders still has to fit its heading.
  const matrixCellWidth = () => {
    const longest = matrixColumns.reduce((max: number, col: any) => Math.max(max, String(col.label).length), 0);
    return Math.max(164, Math.round(96 + longest * 7.2));
  };
  // How many cells the shell is allowed to widen to hold on ONE line. `.idxcells` wraps, and every
  // row carries the same cell count (a device a trail didn't run on is still a `—` cell), so a wrap
  // breaks at the same place on every row and the grid stays aligned — a wrapped matrix is
  // readable, just two lines per trail. But the stock 1120px shell fits four device columns, so a
  // seven-device fleet wraps even on a monitor with room to spare. Widen the shell to fit the
  // fleet, up to this many columns, then let wrapping take over rather than growing without bound.
  const MATRIX_INLINE_COLUMN_LIMIT = 7;
  // `--content-wide` is what caps each `.indexshell` — the header strip, the run list and the
  // footer — and a custom property set on an element feeds that element's own
  // `max-width: var(--content-wide)`, so putting the override on all three widens them together and
  // keeps the `Device classifiers` strip aligned with the columns it names. It only ever grows: the
  // stock width is the floor, and `max-width` means a narrow viewport still wraps responsively.
  // Everything between the shell edge and the cells, from the CSS above: the run list's 1px border
  // either side, a row's `var(--space-4)` padding either side, the trail-name column's 220px
  // minimum, and the `var(--space-4)` grid gap after it.
  const CELLS_LEFT_OF_ROW = 2 + 32 + 220 + 16;
  const CELL_GAP = 8;
  const DEFAULT_CONTENT_WIDE = 1120;
  const indexShellStyle = () => {
    if (!useMatrix) return '';
    const cell = matrixCellWidth();
    const inline = Math.min(matrixColumns.length, MATRIX_INLINE_COLUMN_LIMIT);
    const needed = CELLS_LEFT_OF_ROW + inline * cell + (inline - 1) * CELL_GAP;
    return ` style="--idxcell-w: ${cell}px; --content-wide: ${Math.max(DEFAULT_CONTENT_WIDE, needed)}px"`;
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

  const renderIndexControls = () => {
    const groupLabel = st.runGroup === 'owner' ? 'Owner' : 'Status';
    const sortLabel = st.runSort === 'name' ? 'Name' : st.runSort === 'cost' ? 'Cost' : 'Order';
    const searchIcon = '<svg class="idxsearchicon" viewBox="0 0 16 16" aria-hidden="true"><circle cx="7" cy="7" r="4.5" fill="none" stroke="currentColor" stroke-width="1.5"/><path d="m10.5 10.5 3 3" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
    const groupIcon = '<svg class="idxsorticon" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 3.5h3v3H3zm0 6h3v3H3zm5-5h5m-5 6h5" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg>';
    const sortIcon = '<svg class="idxsorticon" viewBox="0 0 16 16" aria-hidden="true"><path d="M3 4h10M5 8h8m-6 4h6" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>';
    return `<div class="idxfilter">
      <div class="idxsearch">${searchIcon}<input id="runsearch" type="search" aria-label="Search" placeholder="Search" autocomplete="off" value="${esc(st.runSearch)}" /></div>
      <details class="idxsort idxgroup" id="rungroup" data-rungroup><summary aria-label="Group runs by ${groupLabel}"><span class="idxsortvalue">${groupIcon}<span>${groupLabel}</span></span><span class="idxsortchev" aria-hidden="true"></span></summary><div class="idxsortmenu"><div class="idxsortmenulabel">Group by</div><button class="idxsortoption" type="button" aria-pressed="${st.runGroup === 'status'}" data-run-group="status">Status</button><button class="idxsortoption" type="button" aria-pressed="${st.runGroup === 'owner'}" data-run-group="owner">Owner</button></div></details>
      <details class="idxsort idxorder" id="runsort" data-runsort><summary aria-label="Sort runs by ${sortLabel}"><span class="idxsortvalue">${sortIcon}<span>${sortLabel}</span></span><span class="idxsortchev" aria-hidden="true"></span></summary><div class="idxsortmenu"><div class="idxsortmenulabel">Sort by</div><button class="idxsortoption" type="button" aria-pressed="${st.runSort === 'name'}" data-run-sort="name">Name</button><button class="idxsortoption" type="button" aria-pressed="${st.runSort === 'original'}" data-run-sort="original">Order</button><button class="idxsortoption" type="button" aria-pressed="${st.runSort === 'cost'}" data-run-sort="cost">Cost</button></div></details>
    </div>`;
  };

  const renderIndexHeader = () => {
    const platformEntry = mixedPlatforms ? ['Platforms', allPlatforms.filter(Boolean).join(', ')] : ['Platform', sharedMeta('platform')];
    const targetEntry = allTargets.length > 1 ? ['Targets', allTargets.join(', ')] : ['Target', sharedMeta('target') || allTargets[0]];
    // Plural when the report covers several devices, singular otherwise: a one-device report still
    // wants to say WHICH device it ran (`ios-iphone`, `android-tablet`, `android-kiosk`) — the Platform
    // row only names the family. Back-compat payloads that carry no classifier drop the row rather
    // than restate the platform, and a disagreeing set of runs is not shared context.
    const deviceEntry = useMatrix
      ? ['Device classifiers', allDeviceLabels.join(', ')]
      : ['Device classifier', sharedMeta('deviceClassifier')];
    const buildUrl = safeHref(sharedMeta('buildUrl'));
    const commitUrl = safeHref(sharedMeta('commitUrl'));
    const buildNumber = sharedMeta('buildNumber');
    const commitSha = sharedMeta('commitSha');
    const metaEntries: any[] = [targetEntry, ['App version', sharedMeta('appVersion')], platformEntry, deviceEntry, ['Bundle / package ID', sharedMeta('appId')]];
    if (buildNumber || buildUrl) metaEntries.push(['Build', buildNumber || 'Open build', buildUrl]);
    if (commitSha || commitUrl) metaEntries.push(['Commit', commitSha ? String(commitSha).slice(0, 8) : 'Open commit', commitUrl]);
    const meta = metaEntries.filter(([, value]) => value).map(([label, value, url]) => `<div><div class="k">${label}</div><div class="v">${url ? `<a class="indexmetalink" href="${esc(url)}" target="_blank" rel="noopener">${esc(value)} <span aria-hidden="true">↗</span></a>` : esc(value)}</div></div>`).join('');
    // Same rule the run menu applies to its own Export items: a report whose frames are links would
    // download as a file whose pictures die with the server holding them, so it isn't offered.
    const downloadable = documentCarriesItsPayload && !SESSIONS.some((session) => linksItsFrames(session, inlineSpriteUris(session)));
    const reportMenuItems = `${shareLinkAvailable() ? '<button class="exportmenuitem" type="button" id="copylink">Copy link</button>' : ''}${downloadable ? '<button class="exportmenuitem" type="button" id="exportall">Download report</button>' : ''}`;
    // Both items can be gone at once — an embedded, link-framed report has nothing here to offer —
    // and a ⋯ that opens on nothing is worse than no ⋯.
    const reportMenu = reportMenuItems
      ? `<details class="exportmenu" data-export-menu><summary aria-label="Report options" title="Report options"><span class="exportdots" aria-hidden="true"><span class="exportdot"></span><span class="exportdot"></span><span class="exportdot"></span></span></summary><div class="exportmenuitems">${reportMenuItems}</div></details>`
      : '';
    return `<header class="indexheader"><div class="indexshell"${indexShellStyle()}>
      <div class="title-row indexheadrow"><h1>Trailblaze Report</h1><div class="indexheadactions">${renderThemeToggle()}${reportMenu}</div></div>
      <div class="indexcontext"><div class="meta indexmeta">${meta}</div>${renderIndexControls()}</div>
      </div>
    </header>`;
  };

  const renderIndexSummary = () => {
    // Count what the index shows: matrix rows (one per trail) on mixed-platform reports, per-run
    // retry groups otherwise — so the footer tallies always match the section counts.
    const groups = useMatrix ? indexMatrixRows() : indexRunGroups();
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
  const fmtCostShort = fmtCost;

  // One run's LLM spend. A link-out run has no calls to sum, so it reports what the generator
  // precomputed — or null, because summing an absent payload would report every such run as $0.00.
  const runLlmCost = (s) => (isLinkOut(s) ? (s.meta.llmCostUsd != null ? Number(s.meta.llmCostUsd) : null) : llmCostTotal(s.llm || []));
  // Total across runs, null as soon as one is unknown — same rule llmCostTotal applies to calls.
  const sumRunCosts = (runs) => { const costs = runs.map(runLlmCost); return costs.some((c) => c == null) ? null : costs.reduce((sum, c) => sum + c, 0); };

  const aggregateLlmCostLabel = () => { const total = sumRunCosts(SESSIONS); return total == null ? '—' : fmtCost(total); };

  const renderIndexMetrics = () => {
    // Token totals have no link-out equivalent (the generator carries cost and call count, not
    // tokens), so a document holding any payload-less run reports them as unknown rather than as
    // the zero an empty call list would sum to.
    const tokens = SESSIONS.some((s) => isLinkOut(s) && !(s.llm || []).length) ? '—' : llmTokensLabel(SESSIONS.flatMap((s) => s.llm || []));
    return `<div class="indexmetrics"><span class="detailfooteritem"><span class="k">Total duration</span><span class="v">${esc(aggregateDurationLabel())}</span></span><span class="detailfooteritem"><span class="k">Total tokens</span><span class="v">${esc(tokens)}</span></span><span class="detailfooteritem"><span class="k">Total LLM cost</span><span class="v">${esc(aggregateLlmCostLabel())}</span></span></div>`;
  };

  const indexGroupKey = (s, index) => {
    const m = (s && s.meta) || {};
    // Only an explicit trail identity is safe to coalesce. Older exports without trailId can carry
    // independent same-title runs; keeping them separate avoids hiding a failure as retry history.
    if (!m.trailId) return `session:${index}`;
    // Retry identity includes BOTH the stable device class and the concrete instance. The same
    // worker/device id may run multiple form factors, while different instances of one form factor
    // remain independent histories. indexMatrixRows folds a lane's instance groups afterwards.
    return [m.trailId, m.target || '', runPlatform(s), runLane(s), runDeviceInstance(s)].join('\u0001');
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
    const allRuns = useMatrix ? indexMatrixRows() : indexRunGroups();
    const entryHasRetries = (entry) => useMatrix
      ? Array.from(entry.cells.values()).some((cell: any) => cell.attempts.length > 1)
      : entry.attempts.length > 1;
    // Every LLM call a row paid for: all attempts, and on a matrix row all platforms' cells —
    // the total the row subtitle shows and the Cost sort orders by.
    const entryRuns = (entry) => (useMatrix
      ? Array.from(entry.cells.values()).flatMap((cell: any) => cell.attempts)
      : entry.attempts).map((attempt) => attempt.s);
    const compareOrder = (a, b) => {
      if (st.runSort === 'name') return String(a.latest.s.meta.title || '').localeCompare(String(b.latest.s.meta.title || '')) || a.first - b.first;
      if (st.runSort === 'cost') {
        const aCost = sumRunCosts(entryRuns(a));
        const bCost = sumRunCosts(entryRuns(b));
        // Most expensive first; rows whose cost is unknowable sort last.
        return Number(aCost == null) - Number(bCost == null) || (bCost || 0) - (aCost || 0) || a.first - b.first;
      }
      return a.first - b.first;
    };
    const ordered = allRuns.sort((a, b) => {
      if (st.runGroup === 'owner') {
        const aOwner = runOwner(a.latest.s);
        const bOwner = runOwner(b.latest.s);
        // Alphabetical owner sections, ownerless runs last; the selected ordering applies within.
        return Number(!aOwner) - Number(!bOwner) || aOwner.localeCompare(bOwner) || compareOrder(a, b);
      }
      const statusOrder = outcomeRank[a.outcome] - outcomeRank[b.outcome];
      if (statusOrder) return statusOrder;
      // Preserve the established default: retried runs lead their status section in run order.
      if (st.runSort === 'original') {
        const retryOrder = Number(entryHasRetries(b)) - Number(entryHasRetries(a));
        if (retryOrder) return retryOrder;
      }
      return compareOrder(a, b);
    });
    const searchText = (s, outcome) => {
      const status = String((s.meta && s.meta.status) || 'unknown').toLowerCase();
      const outcomeLabel = indexOutcomeLabel(outcome);
      return [s.meta.title, status, outcomeLabel !== status ? outcomeLabel : null, s.meta.platform, s.meta.deviceClassifier, s.meta.deviceType, s.meta.device, s.meta.target, s.meta.appId, s.meta.appVersion, s.meta.steps, s.meta.duration, s.meta.ranAt, s.meta.buildNumber, s.meta.commitSha, s.meta.branch, s.meta.failureCode, ...Object.values(s.meta.metadata || {})]
        .filter((v) => v != null && v !== '').join(' ').toLowerCase();
    };
    const facts = (pairs) => `<div class="idxfacts">${pairs.map(([label, value]) => `<div class="idxfact"><div class="k">${label}</div><div class="v">${esc(value != null && value !== '' ? value : '—')}</div></div>`).join('')}</div>`;
    const runFacts = (s) => facts([['Duration', s.meta.duration], ['Tools', runToolCallCount(s)], ['LLM', runLlmCallCount(s)]]);
    // Steps + LLM cost under the trail id: steps from the latest attempt's trace, cost summed
    // across every attempt on the row (all platforms) — the same total the Cost sort orders by.
    const entryStats = (entry) => {
      const steps = runStepCount(entry.latest.s);
      const parts = [...(steps != null ? [`${steps} step${steps === 1 ? '' : 's'}`] : []), fmtCostShort(sumRunCosts(entryRuns(entry)))];
      return `<div class="idxstats">${esc(parts.join(' · '))}</div>`;
    };
    const attemptRows = (attempts) => attempts.map((attempt, attemptIndex) => {
      const label = indexOutcomeLabel(attempt.outcome);
      const ctl = openControl(attempt.s, 'div', `data-session="${attempt.i}" role="button" tabindex="0"`);
      return `<${ctl.tag} class="idxattemptrow" ${ctl.attrs} data-outcome="${esc(attempt.outcome)}" aria-label="${ctl.interactive ? 'Open attempt' : 'Attempt'} ${attemptIndex + 1}, ${esc(label)}${ctl.interactive ? '' : ' (no report to open)'}">
            <span class="idxstatus" role="img" aria-label="${esc(label)}" title="${esc(label)}"><span class="idxstatusdot ${esc(attempt.outcome)}" aria-hidden="true"></span></span>
            <div class="idxattemptmain"><span class="idxattemptlabel">Attempt ${attemptIndex + 1}</span><span class="idxattemptstatus ${esc(attempt.outcome)}">${esc(label)}</span></div>
            ${runFacts(attempt.s)}
            <span class="arr" aria-hidden="true">→</span>
          </${ctl.tag}>`;
    }).join('');
    const renderRow = (entry) => {
      const { attempts, latest, outcome } = entry;
      const { s, i } = latest;
      const outcomeLabel = indexOutcomeLabel(outcome);
      const search = attempts.map((attempt) => searchText(attempt.s, attempt.outcome)).join(' ');
      // The owner subtitle is redundant inside its own owner section — the section head already says it.
      const owner = st.runGroup === 'owner' ? '' : runOwner(s);
      const context = [owner, qualifyFlatRowsByDevice ? runDeviceType(s) : ''].filter(Boolean).join(' · ');
      const rowMain = `<div class="idxmain"><div class="nm">${esc(s.meta.title || ('Run ' + (i + 1)))}</div>${context ? `<div class="idxowner">${esc(context)}</div>` : ''}${entryStats(entry)}</div>`;
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
      const ctl = openControl(s, 'div', `data-session="${i}" role="button" tabindex="0"`);
      return `<${ctl.tag} class="idxrow" data-run-entry ${ctl.attrs} data-search="${esc(search)}">
          <span class="idxstatus" role="img" aria-label="${esc(outcomeLabel)}" title="${esc(outcomeLabel)}"><span class="idxstatusdot ${esc(outcome)}" aria-hidden="true"></span></span>
          ${rowMain}
          ${runFacts(s)}
          <span class="arr">→</span>
        </${ctl.tag}>`;
    };
    // --- mixed-platform matrix rows ---------------------------------------------------------
    const matrixCols = useMatrix ? matrixColumns : [];
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
      const llmCalls = runLlmCallCount(cell.latest.s);
      const ctl = openControl(cell.latest.s, 'button', `type="button" data-session="${cell.latest.i}"`);
      return `<div class="idxcell ${esc(cell.outcome)}${retried ? ' retried' : ''}"><${ctl.tag} class="idxcellopen" ${ctl.attrs} aria-label="${ctl.interactive ? 'Open latest' : 'Latest'} ${esc(col.label)} run, ${esc(outcomeLabel)}${ctl.interactive ? '' : ' (no report to open)'}"><span class="pk">${esc(col.label)}</span><span class="pcounts">${tools != null ? `${tools} tool${tools === 1 ? '' : 's'}` : ''}</span><span class="pv">${value}${duration ? `<span class="pvtxt">${esc(duration)}</span>` : ''}</span><span class="pcounts">${llmCalls != null ? `${llmCalls} LLM` : ''}</span></${ctl.tag}>${chev}</div>`;
    };
    const renderMatrixRow = (row) => {
      const title = row.latest.s.meta.title || ('Run ' + (row.latest.i + 1));
      const owner = st.runGroup === 'owner' ? '' : runOwner(row.latest.s);
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
    const renderEntry = useMatrix ? renderMatrixRow : renderRow;
    const sectionLabel = { failed: 'Failed', selfheal: 'Self-healed', passed: 'Passed', other: 'Other' };
    // `ordered` is already owner-alphabetized (ownerless last), so distinct owners come out in
    // section order and each section's runs retain the selected ordering.
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
    const rows = st.runGroup === 'owner' ? ownerSections()
      : ['failed', 'selfheal', 'passed', 'other'].map((outcome) => {
          const runs = ordered.filter((run) => run.outcome === outcome);
          if (!runs.length) return '';
          return `<section class="idxsection" data-index-section="${outcome}"><div class="idxsectionhead ${outcome}">${sectionLabel[outcome]} <span class="idxsectioncount">${runs.length}</span></div><div class="idx">${runs.map(renderEntry).join('')}</div></section>`;
        }).join('');
    return `<div class="idxsections">${rows}<div class="empty" id="runempty" ${ordered.length ? 'hidden' : ''}>No runs match these filters.</div></div>`;
  };

  const render = (preserveTimelineScroll = false) => {
    const previousTimelineScroll = preserveTimelineScroll ? root.querySelector<HTMLElement>('.timelinescroll')?.scrollTop : null;
    const previousMainScroll = preserveTimelineScroll ? root.querySelector<HTMLElement>('main')?.scrollTop : null;
    const previousPageScroll = preserveTimelineScroll && typeof window.scrollY === 'number' ? window.scrollY : null;
    const openEventKeys = preserveTimelineScroll ? openTimelineEventKeys() : null;
    const active = preserveTimelineScroll ? document.activeElement as HTMLElement | null : null;
    const focusSelector = active && active.matches('[data-scrub]') ? '[data-scrub]'
      : active && active.matches('[data-kidsel]') ? `[data-kidsel="${active.dataset.kidsel}"]`
      // A step header stays put across a render, so focus returns to the header the reader just
      // toggled. Sending it to the selected step instead would aim at a row the collapse just hid.
      : active && active.matches('[data-group]') ? `[data-group="${active.dataset.group}"]`
      : active && active.matches('[data-step]') ? `[data-step="${st.step}"]`
      : active && active.matches('[data-llm]') ? `[data-llm="${active.dataset.llm}"]`
      : active && active.matches('[data-tlstream]') ? `[data-tlstream="${active.dataset.tlstream}"]`
      : active && active.matches('[data-tlstreams]') ? `[data-tlstreams="${active.dataset.tlstreams}"]`
      : active && active.matches('[data-tlkind]') ? `[data-tlkind="${active.dataset.tlkind}"]`
      : active && active.matches('[data-tlkinds]') ? `[data-tlkinds="${active.dataset.tlkinds}"]`
      : active && ['prev', 'next', 'tlplay'].indexOf(active.id) >= 0 ? `#${active.id}`
      : null;
    const pageTransition = st.pageTransition;
    st.pageTransition = '';
    root.className = pageTransition ? `page-enter-${pageTransition}` : '';
    if (st.view === 'index') {
      const runDate = indexRunDate();
      root.innerHTML = `
        ${renderIndexHeader()}
        <main><div class="indexshell"${indexShellStyle()}>${renderIndex()}</div></main>
        <footer class="indexfooter"><div class="indexshell indexfootercontent"${indexShellStyle()}>${renderIndexSummary()}${renderIndexMetrics()}${runDate ? `<span class="detailfooteritem indexrundate"><span class="k">Run on</span><span class="v">${esc(runDate)}</span></span>` : ''}</div></footer>`;
      wire();
      return;
    }
    if (unhydrated.has(st.session)) {
      // The session's #tb-session chunk hasn't streamed in yet (openSession is awaiting it): hold
      // the detail view with its header + a loading note instead of rendering empty-trace panes.
      // `notabs` restores the bottom padding the tab nav normally contributes, so the header isn't
      // a title flush against its own border. A deep link into a late run of a big CI report can
      // wait a while (the chunk is behind every earlier run's bytes), so the note carries live
      // download progress, and the run index (already fully rendered from #tb-index) stays one
      // click away instead of the view being a dead end.
      const outcome = indexOutcome(D);
      const outcomeLabel = indexOutcomeLabel(outcome);
      root.innerHTML = `
        <header class="detailheader notabs">
          <div class="title-row detailtitle${MULTI ? '' : ' noback'}">${MULTI ? `<div class="detailedge"><button class="back" type="button" data-back aria-label="All runs" title="All runs">${BACK_ICON_SVG}</button></div>` : ''}<div class="runidentity"><span class="idxstatus" role="img" aria-label="${esc(outcomeLabel)}" title="${esc(outcomeLabel)}"><span class="idxstatusdot ${esc(outcome)}" aria-hidden="true"></span></span><h1>${esc((D.meta || {}).title)}</h1></div><div class="detailactions">${renderThemeToggle()}</div></div>
        </header>
        <main><div class="runloading" role="status">
          <div class="tb-boot-spinner" aria-hidden="true"></div>
          <div class="tb-boot-title">Loading run…</div>
          <div class="tb-boot-note" data-run-loading-progress>${esc(loadingProgressText())}</div>
          ${MULTI ? '<button class="btn" type="button" data-back>All runs</button>' : ''}
        </div></main>`;
      wire();
      return;
    }
    const m = D.meta;
    const detailOutcome = indexOutcome(D);
    const detailOutcomeLabel = indexOutcomeLabel(detailOutcome);
    const lightboxStepFrameCount = groupTrace().filter((group) => [group.header, ...group.items]
      .some((t) => t && ((t.screenshotFile && D.shots[t.screenshotFile])
        || (t.children || []).some((c) => c.screenshotFile && D.shots[c.screenshotFile])))).length;
    const hasShots = lightboxStepFrameCount > 0;
    const tabs = [
      ['timeline', 'Timeline'],
      ...(hasShots ? [['lightbox', `Lightbox <span class="counttoken">${lightboxStepFrameCount}</span>`]] : []),
      ...(D.video ? [['video', 'Video']] : []),
      ...(D.llm.length ? [['llm', `LLM <span class="counttoken">${D.llm.length}</span>`]] : []),
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
    // Both image exports write out bytes this document holds: Export report snapshots the document
    // itself, Export screenshots zips its frames. A linked report holds neither, so it offers
    // neither — an "Export screenshots 0" that a reader can't click would also be claiming a run
    // with frames on screen has none. Producing a portable copy of a linked report is the embedding
    // host's job, and its own share action does exactly that.
    // Sprite URLs resolved for real here (chunk included): the open run's chunk is one parse, and
    // its Video tab pays that same parse on first frame anyway. Export report additionally needs a
    // payload node to rewrite in the clone (documentCarriesItsPayload); Export screenshots builds
    // its own blob from the frames and doesn't care where the payload came from.
    const framesEmbedded = !linksItsFrames(D, spriteUrls(D.video));
    const exportMenu = `<details class="exportmenu" data-export-menu><summary aria-label="Run and export options" title="Run and export options"><span class="exportdots" aria-hidden="true"><span class="exportdot"></span><span class="exportdot"></span><span class="exportdot"></span></span></summary><div class="exportmenuitems">${shareLinkAvailable() ? '<button class="exportmenuitem" type="button" id="copylinkrun">Copy link</button>' : ''}<button class="exportmenuitem" type="button" id="copylocalprompt"${localPrompt ? '' : ' disabled'}>Copy local run prompt</button>${framesEmbedded ? `${documentCarriesItsPayload ? '<button class="exportmenuitem" type="button" id="exportrun">Export report</button>' : ''}<button class="exportmenuitem" type="button" id="exportscreenshots"${shotCount ? '' : ' disabled'}><span>Export screenshots</span><span class="count">${shotCount}</span></button>` : ''}<button class="exportmenuitem" type="button" id="exportlogs"${logsAvailable ? '' : ' disabled'}>Export logs</button></div></details>`;
    const footerItems = [['Target', m.target], ['App version', m.appVersion], ['Platform', m.platform], ['Device classifier', m.deviceClassifier], ['Device type', m.deviceType], ['Device', m.device], ['Bundle / package', m.appId], ['Total duration', m.duration], ['Tokens used', llmTokensLabel(D.llm || [])], ['LLM cost', llmCostLabel(D.llm || [])]]
      .filter(([, v]) => v != null && v !== '').map(([k, v]) => `<span class="detailfooteritem"><span class="k">${k}</span><span class="v">${esc(v)}</span></span>`).join('');
    const runOn = m.ranAt ? `<span class="detailfooteritem runon"><span class="k">Run on</span><span class="v">${esc(m.ranAt)}</span></span>` : '';
    const tabsNav = `<nav aria-label="Report views">${tabs.map(([id, l]) => `<button class="${st.tab === id ? 'active' : ''}" data-tab="${id}">${l}</button>`).join('')}</nav>`;
    // Embedded, the identity row is the host's job, so the tabs and the export menu share one row
    // instead: tabs left, ⋯ right. The menu can't simply be dropped with the row — four of its five
    // items (Copy link, Copy local run prompt, Export screenshots, Export logs) have no equivalent
    // in the host's own run actions.
    const header = EMBEDDED
      ? `<header class="detailheader notitle"><div class="tabrow">${tabsNav}<div class="detailactions">${exportMenu}</div></div></header>`
      : `<header class="detailheader">
        <div class="title-row detailtitle${MULTI ? '' : ' noback'}">${MULTI ? `<div class="detailedge"><button class="back" type="button" data-back aria-label="All runs" title="All runs">${BACK_ICON_SVG}</button></div>` : ''}<div class="runidentity"><span class="idxstatus" role="img" aria-label="${esc(detailOutcomeLabel)}" title="${esc(detailOutcomeLabel)}"><span class="idxstatusdot ${esc(detailOutcome)}" aria-hidden="true"></span></span><h1>${esc(m.title)}</h1></div><div class="detailactions">${renderThemeToggle()}${exportMenu}</div></div>
        ${tabsNav}
      </header>`;
    root.innerHTML = `
      ${header}
      <main class="${st.tab === 'timeline' ? 'timelinemain' : ''}">${body}</main>
      ${st.tab === 'timeline' && D.trace.length ? scrubberHtml(timelineAxis(), streamEvents(), selectedEntryIndex()) : ''}
      <footer class="detailfooter"><div class="detailfootermeta" tabindex="0" aria-label="Run metadata">${footerItems}${runOn}</div></footer>`;
    wire();
    // Before the scroll restores below: an expanded body is content, and clamping a scrollTop
    // against a shorter list would land the reader somewhere other than where they were.
    if (openEventKeys && openEventKeys.size) reopenTimelineEvents(openEventKeys);
    if (previousTimelineScroll != null) {
      const timelineList = root.querySelector<HTMLElement>('.timelinescroll');
      if (timelineList) timelineList.scrollTop = previousTimelineScroll;
    }
    if (previousMainScroll != null) {
      const main = root.querySelector<HTMLElement>('main');
      if (main) main.scrollTop = previousMainScroll;
    }
    if (previousPageScroll != null && typeof window.scrollTo === 'function') window.scrollTo(0, previousPageScroll);
    if (focusSelector) root.querySelector<HTMLElement>(focusSelector)?.focus({ preventScroll: true });
    // A `?llm=N` deep link: land the reader on the highlighted table row and open its transcript
    // (the lightbox is the detail surface, so the link opens straight into it; closing leaves the
    // reader at the row).
    if (pendingLlmOpen) {
      const historyBacked = pendingLlmHistoryBacked;
      pendingLlmOpen = false;
      pendingLlmHistoryBacked = false;
      if (st.llmSel >= 0 && st.llmSel < D.llm.length) {
        const rowEl = root.querySelector<HTMLElement>(st.tab === 'llm' ? `[data-llm="${st.llmSel}"]` : `[data-tx="${st.llmSel}"]`);
        if (rowEl && typeof rowEl.scrollIntoView === 'function') rowEl.scrollIntoView({ block: 'center' });
        openTranscript(st.llmSel, rowEl, false, historyBacked);
      }
    }
    if (pendingInspectorOpen != null) {
      const step = pendingInspectorOpen;
      const historyBacked = pendingInspectorHistoryBacked;
      pendingInspectorOpen = null;
      pendingInspectorHistoryBacked = false;
      if (st.tab === 'timeline' && D.trace.some((t) => t.i === step)) openInspector(step, false, historyBacked);
    }
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

  // ── LLM transcript pushed view (see the state + closeTranscript beside stopTimeline above) ──
  // The full conversation for ONE call pushes on from the right like run-detail navigation. It
  // still lives on document.body OUTSIDE #app, so opening and closing it cannot disturb the
  // timeline's scroll, selection, or render state. Triggers: the per-call timeline rows and the
  // LLM tab's per-request table. Dismissal remains Escape or the close button only.
  const txHeaderHtml = (i) => {
    const r: any = D.llm[i] || {};
    const context = txCallContext(i);
    const step = context.stepAt >= 0 ? context.map.steps[context.stepAt] : null;
    const callAt = step ? step.calls.indexOf(i) : -1;
    const previousStep = context.stepAt > 0 ? context.map.steps[context.stepAt - 1].calls[0] : -1;
    const nextStep = context.stepAt >= 0 && context.stepAt < context.map.steps.length - 1
      ? context.map.steps[context.stepAt + 1].calls[0] : -1;
    const previousCall = step && callAt > 0 ? step.calls[callAt - 1] : -1;
    const nextCall = step && callAt >= 0 && callAt < step.calls.length - 1 ? step.calls[callAt + 1] : -1;
    const meta = [
      // Same `<provider>/<model>` identity the LLM tab shows, so the two surfaces agree.
      llmModelLabel(r) !== '—' ? `<span class="mono">${esc(llmModelLabel(r))}</span>` : '',
      r.inputTokens != null ? `<span>in ${fmtN(r.inputTokens)}${r.cacheReadTokens ? ` (${fmtN(r.cacheReadTokens)} cached)` : ''}</span>` : '',
      r.outputTokens != null ? `<span>out ${fmtN(r.outputTokens)}</span>` : '',
      r.totalCost != null ? `<span>${fmtCost(r.totalCost)}</span>` : '',
      r.durationMs ? `<span>${(r.durationMs / 1000).toFixed(1)}s</span>` : '',
    ].filter(Boolean).join('');
    return `<div class="txpanelhead">
      <div class="detailedge"><button type="button" class="back" data-tx-close aria-label="Back to report" title="Back to report">${BACK_ICON_SVG}</button></div>
      <div class="txpaneltitle">
        <div class="txpanelidentity"><span class="txsteptoken">${esc(context.stepToken)}</span><span class="h" id="txpanel-title">LLM transcript</span><span class="txcallposition">Call ${callAt >= 0 ? callAt + 1 : i + 1} of ${step ? step.calls.length : D.llm.length}</span></div>
        <div class="txpanelmeta">${meta}</div>
      </div>
      <nav class="txnav" aria-label="Transcript navigation">
        <div class="txnavgroup"><span class="txnavlabel">Step</span><button type="button" class="btn txnavbutton" data-tx-nav="${previousStep}" data-tx-nav-kind="step" data-tx-nav-direction="previous" ${previousStep < 0 ? 'disabled' : ''} aria-label="Previous step with an LLM call">${CHEVRON_LEFT_SVG}</button><button type="button" class="btn txnavbutton" data-tx-nav="${nextStep}" data-tx-nav-kind="step" data-tx-nav-direction="next" ${nextStep < 0 ? 'disabled' : ''} aria-label="Next step with an LLM call">${CHEVRON_RIGHT_SVG}</button></div>
        <div class="txnavgroup"><span class="txnavlabel">Call</span><button type="button" class="btn txnavbutton" data-tx-nav="${previousCall}" data-tx-nav-kind="call" data-tx-nav-direction="previous" ${previousCall < 0 ? 'disabled' : ''} aria-label="Previous LLM call in this step">${CHEVRON_LEFT_SVG}</button><button type="button" class="btn txnavbutton" data-tx-nav="${nextCall}" data-tx-nav-kind="call" data-tx-nav-direction="next" ${nextCall < 0 ? 'disabled' : ''} aria-label="Next LLM call in this step">${CHEVRON_RIGHT_SVG}</button></div>
      </nav>
    </div>`;
  };
  // Re-render the open panel in place (used by navigation and the post-inflate refresh). The
  // overlay node itself is stable, so the report beneath keeps its exact scroll position.
  const renderTranscriptPanel = () => {
    if (!txPanelEl) return;
    txPanelEl.innerHTML = txHeaderHtml(txCallIndex);
    txBodyEl = document.createElement('div');
    txBodyEl.className = 'txbodylayout';
    txBodyEl.innerHTML = txWorkspaceHtml(txCallIndex);
    txPanelEl.appendChild(txBodyEl);
    wireTranscriptScreen();
    if (txEl) txEl.setAttribute('aria-label', `LLM transcript, call ${txCallIndex + 1} of ${D.llm.length}`);
  };
  const wireTranscriptScreen = () => {
    if (!txBodyEl) return;
    const box: any = txBodyEl.querySelector('.txscreenvideo');
    const context = txCallContext(txCallIndex);
    if (!box || !context.video || !context.videoCell) return;
    box.style.backgroundImage = `url('${spriteUrl(context.video, context.videoCell.sheet)}')`;
    if (spriteAspect == null) measureSpriteAspect(context.video, () => {
      box.style.aspectRatio = spriteAspect;
      if (box.style.setProperty) box.style.setProperty('--tx-screen-aspect', spriteAspect);
    });
  };
  const transcriptFocusDescriptor = () => {
    const active: any = document.activeElement;
    if (!active) return null;
    if (active.matches && active.matches('[data-tx-close]')) return { close: true };
    const kind = active.dataset && active.dataset.txNavKind;
    const direction = active.dataset && active.dataset.txNavDirection;
    if (kind && direction) return { kind, direction };
    // The conversation contains disclosure summaries and other controls that do not have a
    // semantic navigation key. Preserve their ordinal position through a live DOM refresh so
    // keyboard focus never falls back to the now-detached node.
    const focusables = transcriptFocusableControls();
    const focusIndex = focusables.indexOf(active);
    return focusIndex >= 0 ? { focusIndex } : null;
  };
  const transcriptFocusableControls = () => {
    const selector = 'button, [href], summary, [tabindex]:not([tabindex="-1"])';
    const controls: any[] = [];
    [txPanelEl, txBodyEl].forEach((container: any) => {
      if (!container || typeof container.querySelectorAll !== 'function') return;
      Array.from(container.querySelectorAll(selector)).forEach((control: any) => {
        if (!controls.includes(control) && !control.disabled) controls.push(control);
      });
    });
    return controls;
  };
  const restoreTranscriptControlFocus = (descriptor) => {
    if (!descriptor || !txPanelEl || !txPanelEl.querySelector) return;
    let nextFocus: any = descriptor.focusIndex != null
      ? transcriptFocusableControls()[descriptor.focusIndex]
      : descriptor.close
        ? txPanelEl.querySelector('[data-tx-close]')
        : txPanelEl.querySelector(`[data-tx-nav-kind="${descriptor.kind}"][data-tx-nav-direction="${descriptor.direction}"]`);
    // A live update can finish the run or move the selected call to a boundary. Keep focus in the
    // same control group when the equivalent button is no longer available.
    if (nextFocus && nextFocus.disabled && descriptor.kind && descriptor.direction) {
      const opposite = descriptor.direction === 'next' ? 'previous' : 'next';
      nextFocus = txPanelEl.querySelector(`[data-tx-nav-kind="${descriptor.kind}"][data-tx-nav-direction="${opposite}"]`);
    }
    if (nextFocus && !nextFocus.disabled && nextFocus.focus) nextFocus.focus();
  };
  const transcriptScrollState = () => ({
    body: txBodyEl ? txBodyEl.scrollTop || 0 : 0,
    context: txBodyEl && txBodyEl.querySelector ? txBodyEl.querySelector('.txcontext')?.scrollTop || 0 : 0,
    conversation: txBodyEl && txBodyEl.querySelector ? txBodyEl.querySelector('.txconversation')?.scrollTop || 0 : 0,
  });
  const restoreTranscriptScrollState = (scroll) => {
    if (!scroll || !txBodyEl) return;
    txBodyEl.scrollTop = scroll.body;
    const context: any = txBodyEl.querySelector && txBodyEl.querySelector('.txcontext');
    const conversation: any = txBodyEl.querySelector && txBodyEl.querySelector('.txconversation');
    if (context) context.scrollTop = scroll.context;
    if (conversation) conversation.scrollTop = scroll.conversation;
  };
  const refreshTranscriptPanel = (includeHeader = false, preserveReaderState = false) => {
    if (!txEl) return;
    const focus = includeHeader || preserveReaderState ? transcriptFocusDescriptor() : null;
    const scroll = preserveReaderState ? transcriptScrollState() : null;
    if (includeHeader) {
      renderTranscriptPanel();
    } else if (txBodyEl) {
      txBodyEl.innerHTML = txWorkspaceHtml(txCallIndex);
      wireTranscriptScreen();
    }
    restoreTranscriptScrollState(scroll);
    restoreTranscriptControlFocus(focus);
  };
  const navigateTranscript = (callIndex, focusKind = null, focusDirection = null) => {
    if (!txEl || callIndex < 0 || callIndex >= D.llm.length || callIndex === txCallIndex) return;
    txCallIndex = callIndex;
    st.llmSel = callIndex;
    const context = txCallContext(callIndex);
    // Stepping through the transcript moves the timeline selection with it, so the phase and step
    // holding the destination have to open — closing the dialog returns the reader to that row.
    if (context.row) { st.step = context.row.i; st.kid = null; revealTimelineStep(st.step); }
    txReturnSelector = st.tab === 'llm' ? `[data-llm="${callIndex}"]` : `[data-tx="${callIndex}"]`;
    writeRoute(true);
    render(true);
    renderTranscriptPanel();
    // Re-rendering replaces the activated navigation button. Put focus on its matching live
    // control so keyboard readers can advance repeatedly and always retain a visible focus cue.
    const focusSelector = focusKind && focusDirection
      ? `[data-tx-nav-kind="${focusKind}"][data-tx-nav-direction="${focusDirection}"]`
      : null;
    let nextFocus: any = focusSelector && txPanelEl && txPanelEl.querySelector ? txPanelEl.querySelector(focusSelector) : null;
    // At a step/call boundary the activated direction becomes disabled after navigation. Keep
    // focus inside the same control group by falling back to its enabled opposite direction.
    if (nextFocus && nextFocus.disabled && focusKind && focusDirection && txPanelEl && txPanelEl.querySelector) {
      const opposite = focusDirection === 'next' ? 'previous' : 'next';
      nextFocus = txPanelEl.querySelector(`[data-tx-nav-kind="${focusKind}"][data-tx-nav-direction="${opposite}"]`);
    }
    if (nextFocus && !nextFocus.disabled && nextFocus.focus) nextFocus.focus();
  };
  const openTranscript = (i, opener, pushHistory = true, historyBacked = false) => {
    closeTranscript();
    txHistoryClosing = false;
    txReturnFocus = opener || document.activeElement;
    // Selector for the trigger, re-resolved at close time so focus still returns after a render()
    // has replaced the captured node (the gz-transcript inflation path does exactly that).
    const openerTx = opener && opener.dataset ? opener.dataset.tx : null;
    const openerLlm = opener && opener.dataset ? opener.dataset.llm : null;
    txReturnSelector = openerTx != null ? `[data-tx="${openerTx}"]` : openerLlm != null ? `[data-llm="${openerLlm}"]` : null;
    txCallIndex = i;
    const session = D;
    txEl = document.createElement('div');
    txEl.className = 'txoverlay';
    txEl.setAttribute('role', 'dialog'); txEl.setAttribute('aria-modal', 'true');
    txEl.setAttribute('aria-label', `LLM transcript, call ${i + 1} of ${D.llm.length}`);
    txEl.tabIndex = -1;
    const panel = document.createElement('div');
    panel.className = 'txpanel';
    txPanelEl = panel;
    renderTranscriptPanel();
    // Close clicks by delegation (the header close button lives inside panel.innerHTML).
    panel.onclick = (e) => {
      const target = e && (e.target as any);
      if (target && target.closest && target.closest('[data-tx-close]')) closeTranscript(true, true);
      else if (target && target.closest) {
        const nav = target.closest('[data-tx-nav]');
        if (nav && !nav.disabled) navigateTranscript(+nav.dataset.txNav, nav.dataset.txNavKind, nav.dataset.txNavDirection);
      }
      if (e && e.stopPropagation) e.stopPropagation();
    };
    txEl.appendChild(panel);
    // Keyboard contract: Escape closes (and never reaches the document-level handlers under the
    // modal); Tab is trapped inside the dialog, wrapping at both ends.
    txEl.onkeydown = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeTranscript(true, true); return; }
      trapModalTab(txEl, e);
    };
    document.body.appendChild(txEl);
    txEl.focus();
    // The pushed transcript IS the route's `llm` param (see routeParams) — record it so the
    // address stays a shareable deep link whether it opened from the timeline or the LLM table.
    txHistoryPushed = pushHistory ? writeRoute(false) : historyBacked;
    // A gz transcript may still be inflating: the panel shows the Decompressing note now and
    // swaps in the messages when the shared inflater resolves (same session + still open).
    ensureTranscriptsInflated(D).then(() => { if (txEl && D === session) refreshTranscriptPanel(); });
  };
  // ── UI Inspector ──────────────────────────────────────────────────────────────────────────────
  // Per-step view-hierarchy inspector (tree + node details + bounds overlay on the screenshot +
  // raw JSON), opened from the selected step's device-side "Inspect UI" control. It pushes on as a
  // full-page destination but remains mounted on document.body, so full app re-renders can replace
  // #app underneath it without touching it. The markup builders are pure
  // (run-report-inspector.ts); this block owns only the pushed-view lifecycle, state, and wiring.
  // Memoized model of the hierarchy being inspected — the painters and the screenshot hit-testing
  // all read it, and rebuilding a few-hundred-node model per hover/click is avoidable.
  let inspModelCache = { hier: null, model: null };
  const inspectedModel = () => {
    const hier = stepHierarchy(inspState.step);
    if (hier == null) return null;
    if (inspModelCache.hier !== hier) inspModelCache = { hier, model: inspectorModel(hier) };
    return inspModelCache.model;
  };
  const closeInspector = (syncRoute = false, animateReturn = false) => {
    if (!inspectorEl) return;
    // Match transcript dismissal: pop only when this open created a browser-history entry. A
    // directly loaded `?inspect=N` route closes locally because there is no report entry behind it.
    if (syncRoute && inspectorHistoryClosing) return;
    if (syncRoute && inspectorHistoryPushed && typeof history !== 'undefined' && typeof history.back === 'function') {
      inspectorHistoryClosing = true;
      history.back();
      return;
    }
    inspectorEl.remove(); inspectorEl = null;
    inspectorHistoryPushed = false;
    inspectorHistoryClosing = false;
    if (animateReturn) animateReportReturn();
    // Re-resolve the "Inspect UI" trigger by selector first (same reason closeTranscript does): a gz
    // report's hierarchy inflation completes with a full render() that replaces #app, detaching the
    // node captured on open — focusing that node would drop the reader on <body>.
    const back = inspectorReturnFocus; inspectorReturnFocus = null;
    const live = root.querySelector(`[data-inspect="${inspState.step}"]`);
    const target = live || back;
    if (target && target.focus) target.focus();
    if (syncRoute) writeRoute(true);
  };
  // FULL rebuild of the overlay markup. Reserved for changes that alter its structure (open, the
  // raw-JSON toggle, a decompress landing) — NEVER for hover or selection: see
  // syncInspectorHighlight for why those must be in-place.
  // The capture's shape drives the panel geometry (see the .insp-* rules): the screenshot pane is
  // the priority claimant on space — a landscape capture (web/tablet) widens the whole panel and
  // gives the image the free column while the data column caps; a very tall scroll capture renders
  // at pane width and scrolls vertically instead of being scaled to a sliver. Portrait phone
  // captures keep the classic split. Thresholds: landscape is wider than tall; "tall" is h > 3w
  // (a 936×3694 web scroll capture, not a 1080×2400 phone). Classified from the tree's extent at
  // paint time and re-classified from the measured image (applyInspectorImageDims) once it decodes.
  const inspectorShape = (dims) => (!dims ? 'portrait' : (dims.w > dims.h ? 'landscape' : (dims.h > 3 * dims.w ? 'tall' : 'portrait')));
  const inspectorImgEl = () => {
    const wrap = inspectorEl && inspectorEl.querySelector ? inspectorEl.querySelector('[data-insphit]') : null;
    return wrap && wrap.querySelector ? wrap.querySelector('img') : null;
  };
  // Effective overlay coordinate space. The tree's OWN extent cannot be trusted on web: a
  // trailblazeNodeTree carries PAGE-relative bounds (nodes run to the full scroll height, the
  // "document" root has no bounds at all) and off-viewport nodes (hidden carousel slides past the
  // right edge), while the logged screenshot is a viewport capture — an extent derived from the
  // nodes (max x2/y2, ~1999×10700 on a 1280×800 viewport) skewed every rect and hit-tested most of
  // the screenshot onto the wrong nodes. Two better anchors, in order:
  //  - the log's viewport (deviceWidth×deviceHeight, lifted onto the trace row) — the capture's
  //    real coordinate space;
  //  - the image's own aspect ratio, refining the height once it decodes (h = w × naturalH/naturalW)
  //    so a capture taller than the viewport (a full-page export) still lines up.
  // On captures whose tree already matches the image (Android, iOS) all three agree.
  const stepViewport = () => {
    const row = D.trace.find((t) => t.i === inspState.step);
    const vp = row && row.viewport;
    return vp && vp.w > 0 && vp.h > 0 ? vp : null;
  };
  let inspEffDims = null;
  // Best dims available right now: measured (image-refined) > viewport > tree-derived.
  const inspectorAnchorDims = () => {
    if (inspEffDims) return inspEffDims;
    const vp = stepViewport();
    if (vp) return { w: vp.w, h: vp.h };
    const model = inspectedModel();
    return model ? model.dims : null;
  };
  const applyInspectorImageDims = () => {
    if (!inspectorEl) return;
    const model = inspectedModel();
    const img = inspectorImgEl();
    if (!model || !model.dims || !img || !(img.naturalWidth > 0) || !(img.naturalHeight > 0)) return;
    const vp = stepViewport();
    const anchorW = vp ? vp.w : model.dims.w;
    const eff = { w: anchorW, h: (anchorW * img.naturalHeight) / img.naturalWidth };
    if (inspEffDims && Math.abs(inspEffDims.h - eff.h) < 0.5 && Math.abs(inspEffDims.w - eff.w) < 0.5) return;
    inspEffDims = eff;
    // Restyle each rect IN PLACE — a repaint here would reset tree scroll and focus (see
    // syncInspectorHighlight).
    const pctOf = (v, span) => `${((v / span) * 100).toFixed(3)}%`;
    inspectorEl.querySelectorAll('[data-insprect]').forEach((el) => {
      const n = model.nodes[+el.dataset.insprect];
      if (!n || !n.bounds || !el.style) return;
      el.style.left = pctOf(n.bounds.x1, eff.w);
      el.style.width = pctOf(n.bounds.x2 - n.bounds.x1, eff.w);
      el.style.top = pctOf(n.bounds.y1, eff.h);
      el.style.height = pctOf(n.bounds.y2 - n.bounds.y1, eff.h);
    });
    // Re-classify the panel shape from what the reader actually sees (the image), in place.
    const panel = inspectorEl.querySelector('.insppanel');
    if (panel && panel.classList) {
      ['portrait', 'landscape', 'tall'].forEach((s) => panel.classList.remove(`insp-${s}`));
      panel.classList.add(`insp-${inspectorShape({ w: img.naturalWidth, h: img.naturalHeight })}`);
    }
    syncInspectorHighlight();
  };
  const paintInspector = () => {
    if (!inspectorEl) return;
    inspState.hovered = null; // a rebuilt overlay has no pointer over it yet
    inspEffDims = null; // re-measured against the freshly-painted image below
    const row = D.trace.find((t) => t.i === inspState.step);
    const hier = stepHierarchy(inspState.step);
    const model = inspectedModel();
    const shot = row && row.screenshotFile ? safeImageSrc(D.shots[row.screenshotFile]) || null : null;
    const anchorDims = inspectorAnchorDims();
    const shape = inspectorShape(anchorDims);
    let body;
    if (model) {
      const dataPane = inspState.raw
        ? `<pre class="mono inspraw">${esc(safeJson(hier))}</pre>`
        : `<div class="inspdetails">${inspectorDetailsHtml(model, inspState.selected)}</div><div class="inspselectors" data-inspselectors></div><div class="insptree">${inspectorTreeHtml(model, inspState.selected)}</div>`;
      body = `<div class="inspbody">
        <div class="insppane inspshotpane">${shot
          ? `<div class="inspshotwrap" data-insphit><img alt="Screenshot at ${esc((row && row.label) || 'this step')}" /><div class="insprects" aria-hidden="true">${inspectorRectsHtml(model, inspState.selected, anchorDims)}</div><div class="inspselvizlayer" data-inspselvizlayer aria-hidden="true"></div><span class="insphovlabel mono" data-insphovlabel aria-hidden="true"></span></div>`
          : `<div class="inspnote">No screenshot captured for this step.</div>`}</div>
        <div class="insppane inspdatapane">${dataPane}</div>
      </div>`;
    } else if (D.hierarchiesGz && !hierarchiesInflater.cache.has(D)) {
      body = `<div class="inspbody"><div class="inspnote">Decompressing UI hierarchy…</div></div>`;
    } else if (D.hierarchiesGz && hier == null) {
      body = `<div class="inspbody"><div class="inspnote">Could not decompress the UI hierarchy (requires DecompressionStream support).</div></div>`;
    } else {
      body = `<div class="inspbody"><div class="inspnote">No view hierarchy was captured for this step.</div></div>`;
    }
    const rawAction = inspState.raw
      ? `${INSPECTOR_TREE_ICON_SVG}<span>Show tree</span>`
      : `${INSPECTOR_CODE_ICON_SVG}<span>Raw JSON</span>`;
    inspectorEl.innerHTML = `<div class="insppanel insp-${shape}">
      <div class="insphead">
        <div class="detailedge"><button class="back" type="button" data-inspclose aria-label="Back to report" title="Back to report">${BACK_ICON_SVG}</button></div>
        <span class="insptitle" id="insp-title">UI Inspector</span>
        <span class="inspcontext">${esc((row && row.label) || `Step ${inspState.step}`)}</span>
        <span class="inspactions">${model ? `<button class="btn inspaction" type="button" data-inspraw>${rawAction}</button><button class="btn inspaction" type="button" data-inspcopy>${INSPECTOR_COPY_ICON_SVG}<span data-inspcopy-label>Copy JSON</span></button>` : ''}</span>
      </div>
      ${body}
    </div>`;
    // Anchor the overlay's coordinate space to the image once it has decoded (data-URI images are
    // usually ready immediately; a late decode corrects in place).
    const img = inspectorImgEl();
    if (img) {
      if (shot) img.src = shot;
      if (img.complete && img.naturalWidth > 0) applyInspectorImageDims();
      else img.onload = applyInspectorImageDims;
    }
    // A full rebuild replaced the suggestions container; re-render it for the retained selection
    // (no-op — the container stays empty and hidden — when nothing is committed).
    updateInspectorSuggestions();
  };
  const safeJson = (value) => { try { return JSON.stringify(value, null, 2); } catch (e) { return String(value); } };
  // ── Selector suggestions (hover-follow, committed fallback) ──────────────────────────────────
  // Ranked nodeSelector suggestions computed by the embedded Kotlin/JS selector engine — the
  // daemon's own generator/resolver, so a suggestion is exactly what the recorder would write.
  // The SUBJECT follows the same rule as the properties card: the hovered node when a hover
  // preview is active, the committed selection otherwise (hover-out restores the committed
  // cards). Hover-driven computes are debounced and stale-discarded so a rapid sweep never
  // queues; analyses are cached per (step, node) so re-visits render instantly; and the engine
  // is preloaded when the inspector opens (async — the modal paints first) so hover suggestions
  // aren't dead during the one-time bundle eval. Graceful absence is the contract: no engine
  // chunk (older report / bundle unavailable at generation time), a malformed chunk, or a legacy
  // ViewHierarchyTreeNode capture all leave the container empty — the inspector reads exactly as
  // it did before suggestions.
  let selectorEngineLoad = null;
  // True once the engine load settled (found or definitively absent): before that, a compute
  // shows the "Computing…" note; after it, warm computes (~tens of ms) render without a flash.
  let selectorEngineReady = false;
  const ensureSelectorEngine = () => {
    if (selectorEngineLoad) return selectorEngineLoad;
    const chunk = readJsonScript('tb-selector-engine');
    const load = loadSelectorEngineFromChunk(chunk).then((engine) => { selectorEngineReady = true; return engine; });
    // Don't memoize a miss while the document tail (where the engine chunk rides) may still be
    // streaming in — the next use retries; a hit or a settled document caches for good.
    if (chunk || loadSelectorEngine() != null || String(document.readyState || 'complete') === 'complete') selectorEngineLoad = load;
    return load;
  };
  // The engine is worth a "Computing…" placeholder only when a source exists at all: an inert
  // chunk in the document, or an engine global already installed (the Trail Runner web app).
  const selectorEngineAvailable = () => loadSelectorEngine() != null || Boolean(document.getElementById('tb-selector-engine'));
  // Render-state behind the suggestions section: YAML payloads for the copy buttons and mismatch
  // payloads for the visualization (both indexed by data-inspselcopy / data-inspselviz), the
  // subject key the rendered cards describe, and the analysis cache (per step:node — analyses
  // are position-independent, so hover re-visits and commit-after-hover render from cache).
  let inspSelYamls = [];
  let inspSelViz = [];
  let inspSelSubjectKey = null;
  let inspSelTimer = null;
  let inspSelToken = 0;
  let inspSelVizPinned = null;
  const inspSelCache = new Map();
  const SUGGESTION_HOVER_DEBOUNCE_MS = 120;
  const mismatchVizLayer = () => (inspectorEl && inspectorEl.querySelector ? inspectorEl.querySelector('[data-inspselvizlayer]') : null);
  const clearMismatchViz = () => {
    inspSelVizPinned = null;
    const layer = mismatchVizLayer();
    if (layer) layer.innerHTML = '';
  };
  // Paint one engaged mismatch onto the screenshot: the intended element's bounds, the actual
  // receiver's bounds, and the tap point — its own layer, so it never fights the hover/selection
  // rects painted by syncInspectorHighlight.
  const paintMismatchViz = (idx) => {
    const layer = mismatchVizLayer();
    const model = inspectedModel();
    const viz = inspSelViz[idx];
    if (!layer || !model || !viz) return;
    const hier = stepHierarchy(inspState.step);
    const subject = inspSelSubjectKey != null ? model.nodes[inspSelSubjectKey] : null;
    const hitKey = viz.hitNodeId != null ? inspectorKeyForNodeId(hier, viz.hitNodeId) : null;
    const hitNode = hitKey != null ? model.nodes[hitKey] : null;
    layer.innerHTML = mismatchVizHtml({
      target: subject ? subject.bounds : null,
      hit: hitNode ? hitNode.bounds : null,
      tap: { x: viz.tapX, y: viz.tapY },
      dims: inspectorAnchorDims(),
    });
  };
  const clearInspectorSuggestions = (box) => {
    inspSelYamls = []; inspSelViz = []; inspSelSubjectKey = null;
    if (inspSelTimer != null) { clearTimeout(inspSelTimer); inspSelTimer = null; }
    if (box) box.innerHTML = '';
    clearMismatchViz();
  };
  // Render one node's cached/computed analysis into the section. The preview flag (and the
  // header's subject label) make it unambiguous WHICH element the cards describe now that the
  // subject follows hover.
  const renderInspectorSuggestions = (box, key, analysis) => {
    const model = inspectedModel();
    const hier = stepHierarchy(inspState.step);
    const built = selectorSuggestionsHtml(analysis, {
      subjectLabel: model && model.nodes[key] ? model.nodes[key].label : null,
      preview: inspState.hovered != null && key === inspState.hovered && key !== inspState.selected,
      hitLabelFor: (nodeId) => {
        const hitKey = inspectorKeyForNodeId(hier, nodeId);
        return hitKey != null && model && model.nodes[hitKey] ? model.nodes[hitKey].label : null;
      },
    });
    inspSelYamls = built.yamls;
    inspSelViz = built.viz;
    inspSelSubjectKey = key;
    box.innerHTML = built.html;
    clearMismatchViz(); // fresh cards — any engaged paint belongs to the old ones
  };
  const updateInspectorSuggestions = () => {
    const token = ++inspSelToken; // any newer call supersedes an in-flight compute
    if (inspSelTimer != null) { clearTimeout(inspSelTimer); inspSelTimer = null; }
    if (!inspectorEl) return;
    const box = inspectorEl.querySelector('[data-inspselectors]');
    if (!box) { clearInspectorSuggestions(null); return; } // raw JSON view / no model
    const hier = stepHierarchy(inspState.step);
    const subject = inspState.hovered != null ? inspState.hovered : inspState.selected;
    if (subject == null || hier == null || !isSelectorAnalyzableTree(hier)) { clearInspectorSuggestions(box); return; }
    if (!selectorEngineAvailable()) {
      // The engine chunk rides LAST, after the session chunks that carry the hierarchies — so an
      // inspector opened while the document tail is still streaming is usable before the chunk
      // exists. Without this retry that window renders a permanently empty section (and nothing
      // re-arms: re-selecting the same node short-circuits on the cache stamp), indistinguishable
      // from the genuine no-engine path. whenDocumentComplete keeps ONE pending slot, latest wins,
      // and only defers while the document is still loading — so a sweep can't queue retries and a
      // settled document with no chunk stays the plain absence path.
      clearInspectorSuggestions(box);
      if (String(document.readyState || 'complete') !== 'complete') whenDocumentComplete(() => { if (inspectorEl) updateInspectorSuggestions(); });
      return;
    }
    const nodeId = nodeIdForInspectorKey(hier, subject);
    if (nodeId == null) { clearInspectorSuggestions(box); return; }
    const step = inspState.step;
    const stamp = `${step}:${subject}`;
    if (inspSelCache.has(stamp)) { renderInspectorSuggestions(box, subject, inspSelCache.get(stamp)); return; }
    const session = D;
    const run = () => {
      inspSelTimer = null;
      // The note only covers the one-time engine load; once warm, computes render in ~a frame.
      if (!selectorEngineReady) box.innerHTML = '<div class="inspselnote">Computing selector suggestions…</div>';
      ensureSelectorEngine().then((engine) => {
        // Only the newest subject paints — a rapid hover sweep discards every superseded result.
        if (token !== inspSelToken) return;
        if (!inspectorEl || inspState.session !== session || inspState.step !== step) return;
        const live = inspectorEl.querySelector('[data-inspselectors]');
        if (!live) return;
        if (!engine) { clearInspectorSuggestions(live); return; }
        let analysis = null;
        try { analysis = engine.computeSelectorAnalysis(hier, nodeId); } catch (e) { analysis = null; }
        inspSelCache.set(stamp, analysis);
        renderInspectorSuggestions(live, subject, analysis);
      });
    };
    // Hover-driven subjects debounce so a sweep across the screenshot computes only where the
    // pointer dwells; commit (and hover-out restore) runs immediately.
    const hoverDriven = inspState.hovered != null && subject === inspState.hovered && subject !== inspState.selected;
    if (hoverDriven) inspSelTimer = setTimeout(run, SUGGESTION_HOVER_DEBOUNCE_MS);
    else run();
  };
  // Selection and hover paint IN PLACE: toggle the two classes on the tree rows and the bounds
  // rects, and re-render only the small details card. Rebuilding the overlay for these would reset
  // the tree's scrollTop and drop keyboard focus to <body> on every click — and with hover driven
  // by mousemove it would rebuild the whole DOM on every pointer move.
  const syncInspectorHighlight = () => {
    if (!inspectorEl) return;
    const model = inspectedModel();
    if (!model) return;
    const { selected, hovered } = inspState;
    const mark = (el, key) => {
      el.classList.toggle('sel', key === selected);
      el.classList.toggle('hov', key === hovered);
    };
    inspectorEl.querySelectorAll('[data-inspnode]').forEach((el) => mark(el, +el.dataset.inspnode));
    inspectorEl.querySelectorAll('[data-insprect]').forEach((el) => mark(el, +el.dataset.insprect));
    const details = inspectorEl.querySelector('.inspdetails');
    if (details) details.innerHTML = inspectorDetailsHtml(model, selected, hovered);
    // The floating label rides the hovered node's own rect (not the cursor), so a tree-row hover
    // and a screenshot hover point at the same place.
    const label = inspectorEl.querySelector('[data-insphovlabel]');
    const node = hovered != null ? model.nodes[hovered] : null;
    const labelDims = inspectorAnchorDims();
    if (label) {
      label.textContent = node ? node.label : '';
      label.classList.toggle('on', !!(node && node.bounds && labelDims));
      if (node && node.bounds && labelDims) {
        label.style.left = `${Math.max(0, Math.min(100, (node.bounds.x1 / labelDims.w) * 100)).toFixed(3)}%`;
        label.style.top = `${Math.max(0, Math.min(100, (node.bounds.y1 / labelDims.h) * 100)).toFixed(3)}%`;
      }
    }
  };
  // Bring the COMMITTED selection into view in the tree: expand any collapsed ancestor branch
  // (a screenshot-originated selection can land deep inside one), then center the row — unless it
  // is already fully visible, so selecting a row you're looking at never moves the tree (the same
  // no-jump guarantee the in-place paint gives). Reveal is a commit affordance only; hover never
  // calls it — a preview must not scroll or expand anything.
  const revealSelectedNode = (key) => {
    if (!inspectorEl || key == null) return;
    const rows = inspectorEl.querySelectorAll('[data-inspnode]');
    let row = null;
    rows.forEach((el) => { if (+el.dataset.inspnode === key) row = el; });
    if (!row) return;
    let expanded = false;
    // The row's own <summary> stays visible when its branch is collapsed; only collapsed ANCESTOR
    // branches hide it — open every one on the chain.
    for (let d = row.closest && row.closest('details'); d; d = d.parentElement && d.parentElement.closest ? d.parentElement.closest('details') : null) {
      if (!d.open) { d.open = true; expanded = true; }
    }
    const tree = inspectorEl.querySelector('.insptree');
    const canMeasure = tree && row.getBoundingClientRect && tree.getBoundingClientRect;
    if (!expanded && canMeasure) {
      const a = row.getBoundingClientRect();
      const b = tree.getBoundingClientRect();
      if (a.top >= b.top && a.bottom <= b.bottom) return; // already fully visible — don't move the tree
    }
    if (row.scrollIntoView) row.scrollIntoView({ block: 'center' });
  };
  // Committing a selection is what computes suggestions (hover only previews the properties card).
  const selectInspectorNode = (key) => { inspState.selected = key; syncInspectorHighlight(); revealSelectedNode(key); updateInspectorSuggestions(); };
  const hoverInspectorNode = (key) => {
    if (inspState.hovered === key) return;
    inspState.hovered = key;
    syncInspectorHighlight();
    // Suggestions follow the hover subject (debounced; hover-out restores the committed node's).
    updateInspectorSuggestions();
  };
  // Hover is a pointer affordance: a coarse pointer (touch) has no hover state, and a tap would
  // otherwise leave a stuck preview behind.
  const hoverCapablePointer = (e) => {
    const kind = e && e.pointerType;
    if (kind && kind !== 'mouse' && kind !== 'pen') return false;
    if (typeof matchMedia !== 'function') return true;
    try { return matchMedia('(hover: hover)').matches; } catch (err) { return true; }
  };
  // Map a pointer position inside the screenshot to the smallest node containing it. Coordinates
  // are IMAGE-relative (the rect is re-read per hit), so the mapping stays correct while the shot
  // pane scrolls a tall capture; the device space is the image-anchored one (inspEffDims), so a
  // page-relative web tree doesn't skew the vertical mapping.
  const inspectorHitAt = (hit, clientX, clientY) => {
    const model = inspectedModel();
    const img = hit && hit.querySelector ? hit.querySelector('img') : null;
    if (!model || !model.dims || !img || !img.getBoundingClientRect) return null;
    const r = img.getBoundingClientRect();
    if (!(r.width > 0) || !(r.height > 0)) return null;
    if (!inspEffDims) applyInspectorImageDims(); // late decode — measure on first use
    const dims = inspectorAnchorDims();
    if (!dims) return null;
    return hitTestNode(model, ((clientX - r.left) / r.width) * dims.w, ((clientY - r.top) / r.height) * dims.h);
  };
  // The SCREENSHOT is the only hover source. Pointing at the tree deliberately previews nothing:
  // the tree's one interaction is commit-on-activate (click / Enter / Space) plus expand/collapse,
  // so a preview there would be a second, competing meaning for pointing at a row. Hovering the
  // screenshot does light the matching tree row — that direction locates the node in the hierarchy.
  // Hit-testing runs against a few hundred rects, so it's throttled to one frame.
  let inspHoverScheduled = false;
  let inspHoverPending = null;
  const onInspectorPointerMove = (e) => {
    if (!inspectorEl || !hoverCapablePointer(e)) return;
    const target = e && e.target;
    const closest = (sel) => (target && target.closest ? target.closest(sel) : null);
    const hit = closest('[data-insphit]');
    // Pointer anywhere but the screenshot (the tree included) ends the preview.
    if (!hit || e.clientX == null) { hoverInspectorNode(null); return; }
    inspHoverPending = { hit, x: e.clientX, y: e.clientY };
    if (inspHoverScheduled) return;
    inspHoverScheduled = true;
    const run = () => {
      inspHoverScheduled = false;
      const p = inspHoverPending;
      inspHoverPending = null;
      if (p && inspectorEl) hoverInspectorNode(inspectorHitAt(p.hit, p.x, p.y));
    };
    if (typeof requestAnimationFrame === 'function') requestAnimationFrame(run); else setTimeout(run, 16);
  };
  // One delegated handler for everything inside the overlay — a full paint replaces the markup
  // wholesale, so per-element wiring would have to be redone each time.
  const onInspectorClick = (e) => {
    const target = e && e.target;
    const closest = (sel) => (target && target.closest ? target.closest(sel) : null);
    if (closest('[data-inspclose]')) { closeInspector(true, true); return; }
    if (closest('[data-inspraw]')) { inspState.raw = !inspState.raw; paintInspector(); return; }
    if (closest('[data-inspcopy]')) {
      const btn = closest('[data-inspcopy]');
      const label = btn.querySelector('[data-inspcopy-label]');
      try {
        Promise.resolve(navigator.clipboard.writeText(safeJson(stepHierarchy(inspState.step))))
          .then(() => {
            if (!label) return;
            label.textContent = 'Copied';
            setTimeout(() => { if (label.isConnected) label.textContent = 'Copy JSON'; }, 1200);
          }, () => {});
      } catch (err) { /* clipboard unavailable */ }
      return;
    }
    // Copy one suggestion's trail-file nodeSelector YAML (held in inspSelYamls by the render).
    const selCopyBtn = closest('[data-inspselcopy]');
    if (selCopyBtn) {
      const yaml = inspSelYamls[+selCopyBtn.dataset.inspselcopy];
      if (yaml != null) {
        try {
          Promise.resolve(navigator.clipboard.writeText(yaml))
            .then(() => { selCopyBtn.textContent = 'Copied'; setTimeout(() => { selCopyBtn.textContent = 'Copy'; }, 1200); }, () => {});
        } catch (err) { /* clipboard unavailable */ }
      }
      return;
    }
    // Clicking a mismatch card pins its visualization (tap/touch counterpart of the hover
    // engagement); clicking it again unpins.
    const vizCard = closest('[data-inspselviz]');
    if (vizCard) {
      const idx = +vizCard.dataset.inspselviz;
      if (inspSelVizPinned === idx) { clearMismatchViz(); return; }
      paintMismatchViz(idx);
      inspSelVizPinned = idx;
      return;
    }
    const nodeEl = closest('[data-inspnode]');
    if (nodeEl) {
      // Selecting a branch row must not also collapse its <details>; collapse stays available on
      // the summary chevron / whitespace outside the row span.
      if (e.preventDefault) e.preventDefault();
      selectInspectorNode(+nodeEl.dataset.inspnode);
      return;
    }
    if (closest('[data-insptoggle]')) return; // native <details> collapse
    // Click-to-commit on the screenshot: hover previewed which node a click would take; this makes
    // it the selection. Hit-tested rather than read off the hover state so a tap (no hover) works.
    const hit = closest('[data-insphit]');
    if (hit && e.clientX != null) {
      const key = inspectorHitAt(hit, e.clientX, e.clientY);
      if (key != null) selectInspectorNode(key);
    }
  };
  const openInspector = (stepId, pushHistory = true, historyBacked = false) => {
    closeInspector();
    inspectorHistoryClosing = false;
    inspState.step = stepId; inspState.selected = null; inspState.hovered = null; inspState.raw = false; inspState.session = D;
    // Fresh pushed view, fresh suggestion state; preload the engine now (async — the view paints
    // first) so the first hover/commit isn't dead for the one-time bundle eval.
    inspSelCache.clear();
    clearInspectorSuggestions(null);
    if (selectorEngineAvailable()) ensureSelectorEngine();
    inspectorReturnFocus = document.activeElement;
    inspectorEl = document.createElement('div');
    inspectorEl.className = 'inspector';
    inspectorEl.setAttribute('role', 'dialog');
    inspectorEl.setAttribute('aria-modal', 'true');
    inspectorEl.setAttribute('aria-labelledby', 'insp-title');
    inspectorEl.tabIndex = -1;
    inspectorEl.onclick = onInspectorClick;
    inspectorEl.onpointermove = onInspectorPointerMove;
    inspectorEl.onpointerleave = () => hoverInspectorNode(null);
    // Mismatch-visualization engagement: pointing at a mismatch card paints where its tap would
    // land vs the element it describes; leaving the card reverts (unless click-pinned above).
    inspectorEl.onpointerover = (e) => {
      const target = e && e.target;
      const card = target && target.closest ? target.closest('[data-inspselviz]') : null;
      if (card) paintMismatchViz(+card.dataset.inspselviz);
    };
    inspectorEl.onpointerout = (e) => {
      const target = e && e.target;
      const card = target && target.closest ? target.closest('[data-inspselviz]') : null;
      if (!card) return;
      const to = e && e.relatedTarget;
      if (to && to.closest && to.closest('[data-inspselviz]') === card) return; // still inside the card
      if (inspSelVizPinned != null) { paintMismatchViz(inspSelVizPinned); return; } // pinned paint stays
      const layer = mismatchVizLayer();
      if (layer) layer.innerHTML = '';
    };
    // No focus-driven preview on tree rows, deliberately: with the screenshot as the only hover
    // source, a focus preview would be an interaction no pointer user has. Focusing a row gives
    // its focus ring; activating it commits — identical to what the mouse does on the tree.
    // Keyboard interaction for the tree rows (role="button" spans rebuilt on every state change).
    // Each row is the single tab stop for its node (the branch <summary> is tabindex="-1"), so the
    // row also carries the branch keys: Enter/space selects, ArrowRight/ArrowLeft expand/collapse.
    inspectorEl.onkeydown = (e) => {
      if (e.key === 'Escape') { e.preventDefault(); e.stopPropagation(); closeInspector(true, true); return; }
      if (trapModalTab(inspectorEl, e)) return;
      const target = e.target;
      const nodeEl = target && target.closest ? target.closest('[data-inspnode]') : null;
      if (!nodeEl) return;
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); selectInspectorNode(+nodeEl.dataset.inspnode); return; }
      if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
        const branch = nodeEl.closest('summary') ? nodeEl.closest('details') : null;
        if (branch) { e.preventDefault(); branch.open = e.key === 'ArrowRight'; }
      }
    };
    document.body.appendChild(inspectorEl);
    paintInspector();
    // A compressed hierarchies payload inflates on first open; repaint the overlay (and let the
    // inflater's own completion hook re-render the app's row affordances) once it lands.
    if (stepHierarchy(stepId) == null && D.hierarchiesGz) {
      const session = D;
      ensureHierarchiesInflated(session).then(() => { if (inspectorEl && inspState.session === session) paintInspector(); });
    }
    if (inspectorEl.focus) inspectorEl.focus();
    inspectorHistoryPushed = pushHistory ? writeRoute(false) : historyBacked;
  };

  const centerTimelineSelection = (immediate = false) => {
    const center = () => {
      const list = root.querySelector<HTMLElement>('.timelinescroll');
      const selected = root.querySelector<HTMLElement>(`[data-step="${st.step}"]`);
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
      scroller.scrollTo({ top, behavior: immediate || reducedMotion ? 'auto' : 'smooth' });
    };
    if (typeof requestAnimationFrame === 'undefined') center();
    else requestAnimationFrame(() => requestAnimationFrame(center));
  };
  // Select the timeline entry at index `p` — a trace row, or one folded dispatch inside it: the
  // shared landing sequence for every explicit timeline navigation (transport buttons, scrubber,
  // arrow keys).
  const gotoEntry = (p) => {
    const entry = timelineEntries()[p];
    if (!entry) return;
    stopTimeline(); st.step = entry.row.i; st.kid = entry.kid; revealTimelineStep(st.step); writeRoute(true); render(true); centerTimelineSelection();
  };
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
    // Same for the step playback just walked into: its rows are in the document but hidden, so
    // without this the selection would advance behind a closed header. The render stamped each
    // header with whether it leads on its own merit, so this applies the same groupOpen rule the
    // render did — including re-closing the step playback just left.
    root.querySelectorAll<HTMLElement>('[data-group]').forEach((control) => {
      const open = groupOpen(+control.dataset.group!, control.dataset.groupLeads === 'true');
      control.setAttribute('aria-expanded', String(open));
      const body = control.closest('.stepgroup')?.querySelector<HTMLElement>('.stepgroupbody');
      if (body) body.hidden = !open;
    });
  };
  // Move the current-step highlight in place (class + aria toggles, keep-in-view scroll) — the
  // step list's markup is otherwise untouched during playback.
  const paintTimelineSelection = () => {
    root.querySelectorAll<HTMLElement>('.step.sel').forEach((el) => { el.classList.remove('sel'); el.removeAttribute('aria-current'); });
    // The dispatch highlight inside a folded row moves too, so the reader can see WHICH interaction
    // is on screen — and so a dispatch highlight painted by the render playback started from does
    // not sit there stale while playback walks away from it. A collapsed dispatch list has no
    // element to paint; the pane still advances, and the full render at stop opens the list around
    // the landed selection.
    root.querySelectorAll<HTMLElement>('.kid.sel').forEach((el) => { el.classList.remove('sel'); el.removeAttribute('aria-current'); });
    const kidEl = st.kid == null ? null : root.querySelector<HTMLElement>(`[data-kidsel="${st.step}:${st.kid}"]`);
    if (kidEl) { kidEl.classList.add('sel'); kidEl.setAttribute('aria-current', 'step'); }
    const el = root.querySelector<HTMLElement>(`[data-step="${st.step}"]`);
    if (!el) return;
    el.classList.add('sel');
    el.setAttribute('aria-current', 'step');
    const scrollTarget = kidEl || el;
    if (scrollTarget.scrollIntoView) scrollTarget.scrollIntoView({ block: 'nearest' });
  };
  // Per-step paint of the preview pane during playback: swap the screenshot <img> source (steps
  // mode only — in video mode the frame follows the clock), the pane's accessible name (img alt /
  // frame aria-label, kept in lockstep with what the static render would produce), and the
  // action-mark overlay in place.
  const paintTimelinePane = (hasVideo) => {
    const wrap = root.querySelector<HTMLElement>('.preview .shotwrap');
    const cur = D.trace.find((t) => t.i === st.step);
    const inspect = root.querySelector<HTMLButtonElement>('[data-preview-inspect]');
    if (inspect && cur) {
      const available = stepInspectable(cur);
      inspect.disabled = !available;
      if (available) inspect.dataset.inspect = String(cur.i);
      else inspect.removeAttribute('data-inspect');
      inspect.title = available ? 'Inspect the selected step\'s UI hierarchy' : 'No UI hierarchy captured for this step';
      inspect.setAttribute('aria-label', available ? `Inspect UI for: ${cur.label}` : `Inspect UI unavailable for: ${cur.label}`);
    }
    if (!wrap) return;
    const pos = selectedEntryIndex();
    // A dispatch position paints ITS own frame and mark — this is how every interaction inside a
    // folded row replays instead of only the row's own.
    const kid = cur ? selectedChild(cur) : null;
    const kidShot = kid && kid.screenshotFile ? safeImageSrc(D.shots[kid.screenshotFile]) : null;
    const paneLabel = cur ? (kid ? `${cur.label} · ${kid.label}` : cur.label) : '';
    if (hasVideo) {
      const frame = document.getElementById('tlvframe');
      if (frame && cur) frame.setAttribute('aria-label', `Video frame at ${paneLabel}, step ${pos + 1}`);
    } else {
      const img = document.getElementById('shot') as HTMLImageElement | null;
      const src = kidShot || shotForStep(st.step);
      if (img && src) { img.src = src; if (cur) img.alt = `${paneLabel} at step ${pos + 1}`; }
    }
    wrap.querySelectorAll<HTMLElement>('.mark, .swipe, .markborder').forEach((el) => el.remove());
    // The dispatch owns the frame on screen in both modes — its own screenshot in steps mode, the
    // video frame at its instant in video mode — so its mark wins. A dispatch whose screenshot
    // failed to inline is showing the row's frame, and keeps the row's mark.
    const shownKid = kid && (hasVideo || kidShot) ? kid : null;
    const markup = shownKid
      ? (shownKid.mark ? markHtml({ i: `${cur.i}k${st.kid}`, mark: shownKid.mark }) : '')
      : cur && (hasVideo || cur.screenshotFile) ? markHtml(cur) : '';
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
    // Positions are timeline ENTRIES, not rows: a folded row's absorbed dispatches each carry their
    // own frame, so playing rows alone showed one screenshot for a step that tapped four targets.
    const playbackEntries = timelineEntries().filter(isSelectableTimelineEntry);
    if (!playbackEntries.length) {
      st.playing = false;
      render(true);
      if (AUTOPLAY) signalPlaybackEnded();
      return;
    }
    const v = tlVideo();
    const stepsSchedule = buildPlaybackSchedule(playbackEntries, null);
    // Under capture the export schedule replaces both modes: it compresses idle gaps even when a
    // video is driving, so the artifact's length tracks the step count instead of the session's
    // wall clock (a session recorded over an hour must not export an hour of a static screen).
    const schedule = AUTOPLAY ? buildExportSchedule(playbackEntries, v) : v ? buildPlaybackSchedule(playbackEntries, v) : stepsSchedule;
    const axis = timelineAxis();
    // Where Play resumes from, resolved by POSITION rather than by finding the selected entry among
    // the playable ones: the selection can be an entry playback skips (a dispatch whose screenshot
    // never inlined), and a live push mid-run replaces the entry objects outright. Counting the
    // playable entries up to the selection lands on the nearest frame at or before it; the old
    // `indexOf` answered -1 in both cases and restarted the run from the top.
    const played = timelineEntries().slice(0, selectedEntryIndex() + 1).filter(isSelectableTimelineEntry).length;
    const selectedPlaybackIndex = Math.max(0, played - 1);
    const startMs = schedule.offsets[selectedPlaybackIndex] ?? 0;
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
        const entry = playbackEntries[pos.stepIndex];
        if (entry && (entry.row.i !== st.step || entry.kid !== st.kid)) {
          st.step = entry.row.i;
          st.kid = entry.kid;
          revealTimelineStepInPlace();
          paintTimelineSelection();
          paintTimelinePane(pos.frame != null);
          if (els.scrub) {
            // Resolve the rail position from the (step, kid) just assigned, NOT by looking up the
            // entry object: a live push replaces the trace array mid-playback, and an entry held
            // from the pre-push model would then be found nowhere.
            const entryIndex = selectedEntryIndex();
            els.scrub.setAttribute('aria-valuenow', String(entryIndex + 1));
            els.scrub.setAttribute('aria-valuetext', scrubValueText(entryIndex));
          }
          // Keep the frame transport live as playback advances (the full render only runs at
          // stop): Previous must work once playback has moved off the first row, and Next must
          // disable on the last one.
          if (els.prev) els.prev.disabled = pos.stepIndex <= 0;
          if (els.next) els.next.disabled = pos.stepIndex >= playbackEntries.length - 1;
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
        const f = pos.clockMs != null ? axis.tsFrac(pos.clockMs) : axis.stepFrac[selectedEntryIndex()];
        if (f != null) els.head.style.left = `${f * 100}%`;
      }
      if (pos.done) { endTimelinePlayback(); if (AUTOPLAY) signalPlaybackEnded(); return false; }
      return true;
    });
  };
  // The `?autoplay=1` entry point: land on the timeline of the first run, at its first step, and
  // play through to the end without a click. Runs once the document is COMPLETE — a chunked report
  // streams its per-session payload after this script, so starting earlier would play a run whose
  // steps are still arriving. A run with nothing to play signals immediately rather than leaving
  // the recorder waiting out its whole timeout for playback that can never start.
  const startExportAutoplay = () => {
    if (st.view !== 'detail') openSession(0); // multi-run documents land on the index; capture is per-run
    st.tab = 'timeline';
    if (!D.trace.length) { render(true); signalPlaybackEnded(); return; }
    st.step = D.trace[0].i;
    revealTimelineStep(st.step);
    st.playing = true;
    render(true); // paint the playing state first; the engine caches its paint targets from it
    playTimeline();
  };
  const wire = () => {
    stopVideo(); // a re-render replaces the video element; drop any running playback timer.
    if (st.tab !== 'timeline') stopTimeline(); // playback only lives on the timeline tab
    root.querySelectorAll<HTMLElement>('[data-theme-toggle]').forEach((button) => button.onclick = () => setTheme(currentTheme() === 'dark' ? 'light' : 'dark'));
    root.querySelectorAll<HTMLElement>('[data-session]').forEach((el) => {
      const open = () => { openSession(+el.dataset.session); st.pageTransition = 'forward'; writeRoute(false); render(); if (st.tab === 'timeline') centerTimelineSelection(true); };
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
    if (exportAll) exportAll.onclick = () => { exportReport(SESSIONS, 'trailblaze_runs.html', 'Trailblaze Report'); closeExportMenu(); };
    wireCopyLink(document.getElementById('copylink'), closeExportMenu);
    wireCopyLink(document.getElementById('copylinkrun'), closeExportMenu);
    const runGroup = root.querySelector<HTMLDetailsElement>('[data-rungroup]');
    const runSort = root.querySelector<HTMLDetailsElement>('[data-runsort]');
    const wireIndexMenu = (menu: HTMLDetailsElement | null, selector: string, choose: (option: HTMLElement) => void, returnSelector: string) => {
      if (!menu) return;
      menu.addEventListener('focusout', (e) => { if (!menu.contains(e.relatedTarget as Node | null)) menu.open = false; });
      menu.onkeydown = (e) => { if (e.key === 'Escape') { menu.open = false; menu.querySelector<HTMLElement>('summary')?.focus(); } };
      menu.querySelectorAll<HTMLElement>(selector).forEach((option) => option.onclick = () => {
        choose(option); menu.open = false; writeRoute(false); render();
        root.querySelector<HTMLElement>(returnSelector)?.focus({ preventScroll: true });
      });
    };
    wireIndexMenu(runGroup, '[data-run-group]', (option) => { st.runGroup = option.dataset.runGroup || 'status'; }, '[data-rungroup] > summary');
    wireIndexMenu(runSort, '[data-run-sort]', (option) => { st.runSort = option.dataset.runSort || 'original'; }, '[data-runsort] > summary');
    if (runGroup && runSort) {
      runGroup.ontoggle = () => { if (runGroup.open) runSort.open = false; };
      runSort.ontoggle = () => { if (runSort.open) runGroup.open = false; };
    }
    const runSearch = document.getElementById('runsearch') as HTMLInputElement | null;
    const filterIndexRows = () => {
      const terms = st.runSearch.trim().toLowerCase().split(/\s+/).filter(Boolean);
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
    if (runSearch) {
      runSearch.oninput = () => { st.runSearch = runSearch.value; writeRoute(true); filterIndexRows(); };
      filterIndexRows();
    }
    // querySelectorAll, not querySelector: the loading view offers the same escape as a labelled
    // button in the body as well as the header's back arrow.
    root.querySelectorAll<HTMLElement>('[data-back]').forEach((backBtn) => { backBtn.onclick = () => { stopTimeline(); st.view = 'index'; st.pageTransition = 'back'; writeRoute(false); render(); window.scrollTo({ top: 0 }); }; });
    root.querySelectorAll<HTMLElement>('[data-tab]').forEach((b) => b.onclick = () => { st.tab = b.dataset.tab; writeRoute(false); render(); if (st.tab === 'timeline') centerTimelineSelection(true); });
    root.querySelectorAll<HTMLElement>('[data-failure-step]').forEach((button) => button.onclick = () => {
      const at = D.trace.findIndex((trace) => trace.i === +button.dataset.failureStep);
      if (at < 0) return;
      stopTimeline();
      st.step = D.trace[at].i;
      st.kid = null;
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
      const reveal = () => {
        const panel = root.querySelector<HTMLElement>('.failurepanel');
        if (!panel || !panel.scrollIntoView) return;
        const reducedMotion = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
        panel.scrollIntoView({ block: 'center', behavior: reducedMotion ? 'auto' : 'smooth' });
      };
      if (typeof requestAnimationFrame === 'undefined') reveal();
      else requestAnimationFrame(() => requestAnimationFrame(reveal));
    });
    root.querySelectorAll<HTMLElement>('[data-selfheal-step]').forEach((button) => button.onclick = () => {
      const at = selectableTimelineIndexFor(+button.dataset.selfhealStep);
      if (at < 0) return;
      stopTimeline();
      st.step = D.trace[at].i;
      st.kid = null;
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
      const reveal = () => {
        const panel = root.querySelector<HTMLElement>('.selfhealpanel');
        if (!panel || !panel.scrollIntoView) return;
        const reducedMotion = typeof matchMedia !== 'undefined' && matchMedia('(prefers-reduced-motion: reduce)').matches;
        panel.scrollIntoView({ block: 'center', behavior: reducedMotion ? 'auto' : 'smooth' });
      };
      if (typeof requestAnimationFrame === 'undefined') reveal();
      else requestAnimationFrame(() => requestAnimationFrame(reveal));
    });
    root.querySelectorAll<HTMLElement>('[data-step]').forEach((el) => el.onclick = (e) => {
      if (e) e.stopPropagation();
      if (el.focus) el.focus({ preventScroll: true });
      stopTimeline(); st.step = +el.dataset.step; st.kid = null; revealTimelineStep(st.step); writeRoute(true); render(true);
    });
    // Highlight the activated per-request table row in place (no re-render — the lightbox opens
    // over an untouched table, and closing it leaves the reader at the highlighted row).
    const selectLlmRow = (i) => {
      st.llmSel = i;
      root.querySelectorAll<HTMLElement>('[data-llm]').forEach((el) => {
        const on = +el.dataset.llm === i;
        if (el.classList && el.classList.toggle) el.classList.toggle('sel', on);
        if (el.setAttribute && el.removeAttribute) { if (on) el.setAttribute('aria-current', 'true'); else el.removeAttribute('aria-current'); }
      });
    };
    root.querySelectorAll<HTMLElement>('[data-llm]').forEach((el) => {
      // A table row and its chat button share one path: highlight the row, open the transcript
      // lightbox (the tab's only detail surface).
      const open = () => { selectLlmRow(+el.dataset.llm); openTranscript(+el.dataset.llm, el); };
      el.onclick = open;
      // The rows are focusable via tabindex; <tr>s don't get implicit Enter/Space activation, so
      // wire it explicitly.
      el.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); open(); } };
    });
    // Transcript triggers (timeline LLM rows + the LLM tab's table rows). stopPropagation so the
    // sibling row's own select/scrub handler doesn't also fire; the trigger element is passed as
    // the focus-return target for close. A trigger inside a data-llm table row also moves the
    // row highlight, same as activating the row itself.
    root.querySelectorAll<HTMLElement>('[data-tx]').forEach((el) => el.onclick = (e) => {
      if (e) e.stopPropagation();
      const row = el.closest ? el.closest('[data-llm]') as HTMLElement | null : null;
      if (row && row.dataset) selectLlmRow(+row.dataset.llm);
      openTranscript(+el.dataset.tx, el);
    });
    const lightboxMode = document.getElementById('lightboxmode');
    if (lightboxMode) lightboxMode.onclick = () => { st.lightboxAll = !st.lightboxAll; render(); };
    root.querySelectorAll<HTMLElement>('[data-gal-zoom]').forEach((el) => el.onclick = () => {
      st.lightboxZoom = Math.max(0, Math.min(GAL_ZOOM_SIZES.length - 1, st.lightboxZoom + +el.dataset.galZoom));
      render();
    });
    root.querySelectorAll<HTMLElement>('[data-tlkind]').forEach((el) => el.onclick = () => {
      const kind = el.dataset.tlkind;
      st.tlEventKinds = st.tlEventKinds.indexOf(kind) >= 0 ? st.tlEventKinds.filter((value) => value !== kind) : TIMELINE_EVENT_KINDS.filter((value) => value === kind || st.tlEventKinds.indexOf(value) >= 0);
      normalizeTimelineSelection();
      st.tlEventMenuOpen = true; st.tlMenuOpen = false; writeRoute(true); render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-tlkinds]').forEach((el) => el.onclick = () => {
      st.tlEventKinds = el.dataset.tlkinds === 'all' ? allTimelineEventKinds() : [];
      normalizeTimelineSelection();
      st.tlEventMenuOpen = true; st.tlMenuOpen = false; writeRoute(true); render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-tlstream]').forEach((el) => el.onclick = () => {
      const i = +el.dataset.tlstream; st.tlStreams = st.tlStreams.indexOf(i) >= 0 ? st.tlStreams.filter((v) => v !== i) : [...st.tlStreams, i].sort((a, b) => a - b);
      st.tlMenuOpen = true; st.tlEventMenuOpen = false; writeRoute(true); render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-tlstreams]').forEach((el) => el.onclick = () => {
      st.tlStreams = el.dataset.tlstreams === 'all' ? (sessionEvents(D) || []).map((_, i) => i) : [];
      st.tlMenuOpen = true; st.tlEventMenuOpen = false; writeRoute(true); render(true);
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
    // A step header expands and collapses its own tool calls. Expanding also selects the step's
    // first tool call — skipping agent-reasoning rows and trailing terminal snapshots — so the
    // preview pane jumps to that screenshot, which is what the header click has always done.
    // Collapsing leaves the selection alone; there is nothing to look at in a closed step.
    root.querySelectorAll<HTMLElement>('[data-group]').forEach((control) => control.onclick = () => {
      const headerId = +control.dataset.group;
      const open = control.getAttribute('aria-expanded') !== 'true';
      st.stepsOpen[headerId] = open;
      const at = D.trace.findIndex((t) => t.i === headerId);
      let nextIndex = -1;
      if (open && at >= 0) {
        for (let j = at + 1; j < D.trace.length && !D.trace[j].objective; j++) {
          if (!D.trace[j].terminal && !isLlmTurn(D.trace[j])) { nextIndex = j; break; }
        }
      }
      if (nextIndex >= 0) { stopTimeline(); st.step = D.trace[nextIndex].i; st.kid = null; revealTimelineStep(st.step); }
      writeRoute(true); render(true);
    });
    const eventSelect = root.querySelector<HTMLDetailsElement>('[data-eventselect]');
    const streamSelect = root.querySelector<HTMLDetailsElement>('[data-streamselect]');
    if (eventSelect) eventSelect.ontoggle = () => {
      st.tlEventMenuOpen = eventSelect.open;
      if (eventSelect.open && streamSelect) streamSelect.open = false;
    };
    if (streamSelect) streamSelect.ontoggle = () => {
      st.tlMenuOpen = streamSelect.open;
      if (streamSelect.open && eventSelect) eventSelect.open = false;
    };
    // Dismiss either timeline dropdown on a tap/click outside it. Assignment (not addEventListener)
    // means each re-render replaces the handler instead of stacking stale ones.
    document.onpointerdown = (e) => {
      const target = e.target as Node | null;
      if (eventSelect && eventSelect.open && !eventSelect.contains(target)) eventSelect.open = false;
      if (streamSelect && streamSelect.open && !streamSelect.contains(target)) streamSelect.open = false;
    };
    root.querySelectorAll<HTMLElement>('[data-yaml-step]').forEach((el) => el.onclick = () => {
      stopTimeline();
      st.step = +el.dataset.yamlStep;
      st.kid = null;
      st.tab = 'recording';
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
    });
    root.querySelectorAll<HTMLElement>('[data-inspect]').forEach((el) => el.onclick = (e) => {
      if (e && e.stopPropagation) e.stopPropagation();
      openInspector(+el.dataset.inspect);
    });
    const previewInspect = root.querySelector<HTMLElement>('[data-preview-inspect]');
    if (previewInspect) previewInspect.onclick = (e) => {
      if (e && e.stopPropagation) e.stopPropagation();
      if (previewInspect.dataset.inspect != null) openInspector(+previewInspect.dataset.inspect);
    };
    root.querySelectorAll<HTMLElement>('[data-lightbox-step]').forEach((el) => el.onclick = () => {
      stopTimeline();
      st.step = +el.dataset.lightboxStep;
      st.kid = el.dataset.lightboxKid != null ? +el.dataset.lightboxKid : null;
      st.tab = 'timeline';
      revealTimelineStep(st.step);
      writeRoute(true);
      render(true);
      centerTimelineSelection();
    });
    const galleryShots = Array.from(root.querySelectorAll<HTMLElement>('[data-shot]'));
    const galleryEntries = galleryShots.map((el) => ({ src: safeImageSrc(D.shots[el.dataset.shot]), token: el.dataset.shotToken, label: el.dataset.shotLabel, tool: el.dataset.shotTool }));
    galleryShots.forEach((el, index) => {
      const entry = galleryEntries[index];
      const image = el.querySelector?.('img') as HTMLImageElement | null;
      if (entry.src && image) image.src = entry.src;
      el.onclick = (e) => { if (e) e.stopPropagation(); if (entry.src) openZoom(entry.src, '', galleryEntries, index); };
    });
    root.querySelectorAll<HTMLElement>('[role="button"][tabindex="0"]').forEach((el) => el.onkeydown = (e) => {
      if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); el.click(); }
    });
    // The dispatch-list summary sits inside a selectable step row: without stopPropagation the
    // toggle would also select the step and re-render — so the open state lives in st.kidsOpen
    // and the render owns what the summary shows. `data-open` carries the effective state so the
    // first toggle flips from what the reader actually sees. Bound after the generic
    // role=button keydown pass above, which would otherwise overwrite this onkeydown (handler
    // assignment, not addEventListener) and let Enter/Space bubble into selecting the step.
    root.querySelectorAll<HTMLElement>('[data-kids]').forEach((el) => {
      const toggle = (e) => {
        if (e) { e.preventDefault(); e.stopPropagation(); }
        st.kidsOpen[+el.dataset.kids] = el.dataset.open !== '1';
        render(true);
      };
      el.onclick = toggle;
      el.onkeydown = (e) => { if (e && (e.key === 'Enter' || e.key === ' ')) toggle(e); };
    });
    // A folded child dispatch: select the row AND that dispatch, so the pane previews its own
    // frame and its args panel expands. Same stopPropagation + post-generic-keydown binding rules
    // as the dispatch-list summary above — the kid sits inside the selectable step row.
    root.querySelectorAll<HTMLElement>('[data-kidsel]').forEach((el) => {
      const activate = (e) => {
        if (e) { e.preventDefault(); e.stopPropagation(); }
        const [step, kid] = String(el.dataset.kidsel).split(':').map(Number);
        stopTimeline(); st.step = step; st.kid = kid; revealTimelineStep(st.step); writeRoute(true); render(true);
      };
      el.onclick = activate;
      el.onkeydown = (e) => { if (e && (e.key === 'Enter' || e.key === ' ')) activate(e); };
    });
    const previewShot = root.querySelector<HTMLImageElement>('.preview .shot');
    if (previewShot) {
      // Same resolution as the pane render: a selected child dispatch's own frame wins.
      const capture = selectedChild(D.trace.find((t) => t.i === st.step));
      const captureSrc = capture && capture.screenshotFile ? safeImageSrc(D.shots[capture.screenshotFile]) : '';
      const src = captureSrc || shotForStep(st.step);
      if (src) previewShot.src = src;
    }
    if (previewShot && !previewShot.complete) previewShot.addEventListener('load', () => centerTimelineSelection(), { once: true });
    // First timeline render with a video whose payload lacks frameWidth: measure the sprite once
    // and patch the live frame box in place (same as wireVideo) — a render(true) here would replace
    // the whole DOM out from under a running playback; later renders inline the now-cached spriteAspect.
    const tlvframeBox = document.getElementById('tlvframe');
    const timelineVideo = tlVideo();
    // Same instant the pane's background-POSITION was rendered from: a dispatch's frame can live on
    // a different sprite sheet than its row's, and reading a different clock here would apply one
    // sheet's coordinates to the other sheet's image.
    const timelineClock = timelineVideo ? selectedClockMs() : null;
    const timelineCell = timelineVideo && timelineClock != null ? spriteFrameCss(timelineVideo, videoFrameAt(timelineVideo, timelineClock)) : null;
    if (tlvframeBox && timelineCell) tlvframeBox.style.backgroundImage = `url('${spriteUrl(timelineVideo, timelineCell.sheet)}')`;
    if (tlvframeBox && spriteAspect == null) measureSpriteAspect(tlVideo(), () => { tlvframeBox.style.aspectRatio = spriteAspect; });
    const prev = document.getElementById('prev'); const next = document.getElementById('next');
    if (prev) prev.onclick = () => { stopTimeline(); const target = adjacentSelectableIndex(selectedEntryIndex(), -1); if (target >= 0) gotoEntry(target); };
    if (next) next.onclick = () => { stopTimeline(); const target = adjacentSelectableIndex(selectedEntryIndex(), 1); if (target >= 0) gotoEntry(target); };
    const scrub = root.querySelector<HTMLElement>('[data-scrub]');
    const scrubHover = root.querySelector<HTMLElement>('[data-scrubhover]');
    const scrubHoverRange = root.querySelector<HTMLElement>('[data-scrubhover-range]');
    if (scrub && scrubHover) {
      const hoverRanges = scrubTimelineModel(timelineAxis()).ranges;
      const hoverStep = scrubHover.querySelector<HTMLElement>('[data-scrubhover-step]');
      const hoverKind = scrubHover.querySelector<HTMLElement>('[data-scrubhover-kind]');
      scrub.onpointermove = (e) => {
        const r = scrub.getBoundingClientRect();
        const pointerX = Math.min(r.width, Math.max(0, e.clientX - r.left));
        const f = pointerX / Math.max(1, r.width);
        const hoveredRange = scrubStepAtFraction(hoverRanges, f);
        const marker = (e.target as HTMLElement | null)?.closest?.('.scrubtick,.scrubstatusbox') as HTMLElement | null;
        const step = marker?.dataset.scrubStep || hoveredRange.token;
        const kind = marker?.dataset.scrubKind || '';
        if (scrubHoverRange) {
          scrubHoverRange.style.left = `${hoveredRange.start * 100}%`;
          scrubHoverRange.style.right = `${Math.max(0, 1 - hoveredRange.end) * 100}%`;
          scrubHoverRange.classList.add('visible');
          scrubHoverRange.setAttribute('aria-hidden', 'false');
        }
        if (hoverStep) {
          hoverStep.textContent = step;
          hoverStep.classList.toggle('scrubtooltiptrailhead', step === 'Trailhead');
        }
        if (hoverKind) {
          hoverKind.textContent = kind;
          hoverKind.classList.toggle('visible', !!kind);
        }
        const markerColor = marker ? getComputedStyle(marker).getPropertyValue('--tick-color') : '';
        scrubHover.style.setProperty('--tick-color', markerColor || 'var(--timeline-objective-mark)');
        scrubHover.classList.add('visible');
        scrubHover.setAttribute('aria-hidden', 'false');
        const tooltipRect = scrubHover.getBoundingClientRect();
        const stepRect = hoverStep?.getBoundingClientRect();
        const stepCenterOffset = stepRect
          ? stepRect.left - tooltipRect.left + stepRect.width / 2
          : tooltipRect.width / 2;
        const desiredLeft = pointerX - stepCenterOffset;
        const maxLeft = Math.max(0, r.width - tooltipRect.width);
        scrubHover.style.left = `${Math.min(maxLeft, Math.max(0, desiredLeft))}px`;
      };
      scrub.onpointerleave = () => {
        scrubHover.classList.remove('visible');
        scrubHover.setAttribute('aria-hidden', 'true');
        if (scrubHoverRange) {
          scrubHoverRange.classList.remove('visible');
          scrubHoverRange.setAttribute('aria-hidden', 'true');
        }
      };
    }
    if (scrub) scrub.onclick = (e) => {
      const r = scrub.getBoundingClientRect();
      const f = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width));
      const axis = timelineAxis(); const entries = timelineEntries(); let best = -1; let dist = Infinity;
      axis.stepFrac.forEach((sf, i) => { if (!isSelectableTimelineEntry(entries[i])) return; const d = Math.abs(sf - f); if (d < dist) { dist = d; best = i; } });
      if (best >= 0) gotoEntry(best);
    };
    if (scrub) scrub.onkeydown = (e) => {
      const p = selectedEntryIndex();
      const total = timelineEntries().length;
      const target = e.key === 'Home' ? adjacentSelectableIndex(-1, 1) : e.key === 'End' ? adjacentSelectableIndex(total, -1) : (e.key === 'ArrowUp' || e.key === 'ArrowLeft') ? adjacentSelectableIndex(p, -1) : (e.key === 'ArrowDown' || e.key === 'ArrowRight') ? adjacentSelectableIndex(p, 1) : -1;
      if (target >= 0 && target < total) { e.preventDefault(); e.stopPropagation(); gotoEntry(target); }
    };
    const tlplay = document.getElementById('tlplay');
    if (tlplay) tlplay.onclick = () => {
      if (timelinePlaybackStop) { endTimelinePlayback(); return; }
      if (!D.trace.length) return;
      // Restart from the top if parked at the end.
      if (selectedEntryIndex() >= timelineEntries().length - 1) { st.step = D.trace[0].i; st.kid = null; }
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
      const cur = D.trace.find((t) => t.i === st.step);
      // A selected child dispatch zooms ITS own frame + mark (same resolution as the pane).
      const capture = selectedChild(cur);
      const captureSrc = capture && capture.screenshotFile ? safeImageSrc(D.shots[capture.screenshotFile]) || null : null;
      const src = captureSrc || shotForStep(st.step);
      if (!src) return;
      openZoom(src, captureSrc
        ? (capture.mark ? markHtml({ i: `${cur.i}k${st.kid}`, mark: capture.mark }) : '')
        : (cur && cur.screenshotFile ? markHtml(cur) : ''));
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
    let shownSheet = -1;
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

  // Global listeners are torn down before this run registers its own, so booting a second time into
  // the same document (the viewer shell loading another archive, in-app reuse) can't leave the
  // previous run's handlers live. They would still be bound to that run's own SESSIONS/st and render
  // it back into the shared #app — an arrow key would be handled by the stale closure first, which
  // also calls preventDefault, so the current run would never see it.
  if (disposeViewerGlobals) { disposeViewerGlobals(); disposeViewerGlobals = null; }

  const onKeydown = (e: KeyboardEvent) => {
    // The transcript dialog owns the keyboard while open (its own handler covers Escape and the
    // Tab trap); the timeline/zoom shortcuts below must not fire underneath an aria-modal dialog.
    if (txEl) { if (e.key === 'Escape') { e.preventDefault(); closeTranscript(true, true); } return; }
    if (inspectorEl) {
      if (e.key === 'Escape') { e.preventDefault(); closeInspector(true, true); }
      return; // the overlay is modal — timeline/zoom shortcuts stay inert underneath it
    }
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
    if (e.key === 'ArrowLeft' || e.key === 'ArrowUp') { stopTimeline(); const target = adjacentSelectableIndex(selectedEntryIndex(), -1); if (target >= 0) { e.preventDefault(); gotoEntry(target); } }
    if (e.key === 'ArrowRight' || e.key === 'ArrowDown') { stopTimeline(); const target = adjacentSelectableIndex(selectedEntryIndex(), 1); if (target >= 0) { e.preventDefault(); gotoEntry(target); } }
    if (e.key === ' ') { e.preventDefault(); const b = document.getElementById('tlplay'); if (b) b.click(); } // space toggles play/pause
  };
  const onPopstate = () => {
    const hadPushedDestination = Boolean(inspectorEl || txEl);
    closeInspector(); // history navigation replaces the view under the modal overlay
    const previousView = st.view;
    const restoreTimelineFocus = st.view === 'detail' && st.tab === 'timeline'
      && Boolean((document.activeElement as HTMLElement | null)?.closest?.('[data-step]'));
    // Navigating away closes the dialog: applyRoute's index branch never reaches openSession, so
    // without this a Back to the runs index re-renders the index with the modal stranded over it.
    closeTranscript();
    applyRoute(true);
    if (st.view !== previousView) st.pageTransition = st.view === 'detail' ? 'forward' : 'back';
    render(hadPushedDestination);
    if (hadPushedDestination && !inspectorEl && !txEl) animateReportReturn();
    if (!hadPushedDestination && st.view === 'detail' && st.tab === 'timeline') centerTimelineSelection(true);
    if (restoreTimelineFocus && st.view === 'detail' && st.tab === 'timeline') {
      root.querySelector<HTMLElement>(`[data-step="${st.step}"]`)?.focus({ preventScroll: true });
    }
  };

  document.addEventListener('keydown', onKeydown);
  const canListenOnWindow = typeof window.addEventListener === 'function';
  if (canListenOnWindow) window.addEventListener('popstate', onPopstate);
  // Teardown must never break the boot that invokes it, so every step is guarded: the reduced DOMs
  // this bundle also runs against (in-app reuse, the fake-DOM harness in the tests) do not
  // necessarily implement removeEventListener, and a stale playback stopper closes over the previous
  // run's timers.
  disposeViewerGlobals = () => {
    if (typeof document.removeEventListener === 'function') document.removeEventListener('keydown', onKeydown);
    if (canListenOnWindow && typeof window.removeEventListener === 'function') window.removeEventListener('popstate', onPopstate);
    if (disposeThemeListener) { try { disposeThemeListener(); } catch (e) { /* media query already gone */ } }
    // A timeline left playing would keep stepping the previous run's state into a replaced DOM.
    try { stopTimeline(); } catch (e) { /* previous run's timers are already gone */ }
    // The zoom overlay lives on document.body, not inside #app, so a caller that swaps the report out
    // (the shell clearing #app for the next archive) would otherwise leave it stranded over the new
    // one — and the next boot's Escape handler sees its own zoomEl as null, so it can't dismiss it.
    try { closeZoom(); } catch (e) { /* overlay's own nodes are already gone */ }
    // Same for the transcript dialog and the UI Inspector overlay.
    try { closeTranscript(); } catch (e) { /* overlay's own nodes are already gone */ }
    try { closeInspector(); } catch (e) { /* overlay's own nodes are already gone */ }
  };

  // The live seam. A same-origin embedder (Trail Runner's run details) follows a run as it executes —
  // the live report document does it with the daemon's log stream — and pushes the grown payload in
  // through here. It exists because the alternative — booting the viewer again with a fresh payload —
  // resets `st`: the selected tab, the selected step, scroll offsets, which groups are expanded. A
  // run's records arrive several times a step, so that would yank the view out from under whoever is
  // reading it at exactly that rate.
  //
  // Two rules for callers, both load-bearing:
  //
  //  1. Push UNCOMPRESSED `events` / `deviceLog` / `network` / `llmMessages` / `hierarchies`, never
  //     their `*Gz` counterparts. The inflaters cache by session OBJECT and the accessors prefer the
  //     inline field, so an inline value is always read fresh, whereas a gz value would be inflated
  //     once on first use and then served from that cache forever — the reader would watch the
  //     timeline grow while the device log stayed frozen at its first push.
  //  2. Only push when something actually changed. `render` rebuilds the whole subtree, so a
  //     no-change push is pure cost, and on a long run it is cost paid on every update.
  if (typeof window !== 'undefined') {
    // This handle belongs to THIS boot. A document that boots the viewer more than once (the shell
    // does, once per archived run) leaves the module-scoped disposer pointing at the newest boot, so
    // a handle held from an earlier one would tear down its successor's listeners instead of its own.
    const ownDispose = disposeViewerGlobals;
    let torn = false;
    window.__TB_REPORT_LIVE__ = {
      // Merge a payload into the session at `sessionIndex` and re-render in place. Merging INTO the
      // existing object rather than replacing it is required, not incidental: `D` and the inflater
      // caches both hold that object by reference.
      //
      // A null or absent field is left alone rather than merged. A push carries what its producer
      // could assemble at that moment, so a side channel it merely failed to fetch this time (the
      // live document's `/events`, `/analytics`) arrives empty; merging that would blank a stream
      // the reader already has, and if no later push succeeds it would stay blank for good. A push
      // adds and replaces; it never clears.
      update(sessionIndex, payload) {
        // A push queued before teardown must not paint into a document this viewer no longer owns.
        if (torn) return;
        const i = Number(sessionIndex) || 0;
        const session = SESSIONS[i];
        if (!session || !payload) return;
        // Where the tail was before the merge, so the follow decision below can tell "parked at the
        // end" from "reading something earlier".
        const wasAtTail = session.trace && session.trace.length ? st.step === newestSelectableStep() : true;
        // A row's `i` is its POSITION in the derived trace, so a record that lands late and sorts
        // before the reader's selection renumbers it: same `st.step`, different step underneath.
        // Remember which row that number named, and put the selection back on it after the merge.
        const selectedKey = st.view === 'detail' && D === session && session.trace
          ? traceRowKey(session.trace[idxOf(st.step)])
          : null;
        // Positional LLM indexes can also move when an older request arrives late and the live
        // payload is rebuilt in timestamp order. Remember the open request by its stable trace id
        // before replacing the session arrays, then resolve its new index after the merge.
        const selectedLlmTraceId = txEl && D === session && session.llm && session.llm[txCallIndex]
          ? session.llm[txCallIndex].traceId
          : null;
        Object.keys(payload).forEach((key) => { if (payload[key] != null) session[key] = payload[key]; });
        // A push carrying the trace IS what hydration would have delivered, so the chunk it would
        // have come from is moot. A partial push is not: clearing the marker for one of those would
        // make hydrateSession short-circuit, the #tb-session-<i> chunk would never be parsed, and
        // that run would lose its trace, screenshots and side channels for the life of the document.
        if (payload.trace) unhydrated.delete(i);
        // Still unhydrated: remember what this push delivered, so the chunk that eventually parses
        // over the stub does not roll these fields back (see livePatched).
        if (unhydrated.has(i)) {
          const patch = livePatched.get(i) || {};
          Object.keys(payload).forEach((key) => { if (payload[key] != null) patch[key] = payload[key]; });
          livePatched.set(i, patch);
        }
        // A push for a run the reader is not looking at still has to repaint (the index counts its
        // steps and outcome), but it must not cost them their scroll position or focus.
        if (st.view !== 'detail' || D !== session) { render(true); return; }
        // Sticky follow: track the newest row for as long as the reader is parked on the newest row,
        // and stop the moment they select an earlier one. No flag to keep in sync — where the
        // selection sits IS the intent. Selecting the last row again resumes following, which is
        // what "watch the tail" means.
        let followed = false;
        let movedSelection = false;
        if (wasAtTail && session.trace && session.trace.length) {
          const tail = newestSelectableStep();
          if (tail != null && st.step !== tail) { st.step = tail; st.kid = null; followed = true; }
        } else if (selectedKey) {
          const moved = (session.trace || []).find((t) => traceRowKey(t) === selectedKey);
          if (moved && moved.i !== st.step) { st.step = moved.i; st.kid = null; movedSelection = true; }
        }
        let movedTranscript = false;
        if (selectedLlmTraceId) {
          const nextCallIndex = (session.llm || []).findIndex((call) => call.traceId === selectedLlmTraceId);
          if (nextCallIndex >= 0 && nextCallIndex !== txCallIndex) {
            txCallIndex = nextCallIndex;
            st.llmSel = nextCallIndex;
            const context = txCallContext(nextCallIndex);
            if (context.row) { st.step = context.row.i; st.kid = null; }
            txReturnSelector = st.tab === 'llm' ? `[data-llm="${nextCallIndex}"]` : `[data-tx="${nextCallIndex}"]`;
            movedTranscript = true;
          }
        }
        // The address bar is selection state here as everywhere else: leaving it behind would mean
        // reloading a running report lands on whatever step was newest minutes ago. Replace rather
        // than push, so following a run doesn't fill the reader's history with one entry per step.
        // A run in progress has failed nothing yet, so every step group defaults to collapsed.
        // Following the tail into a new step therefore has to open it, or the reader watches a
        // preview pane track a selection hidden behind a closed header.
        if (followed || movedSelection || movedTranscript) { revealTimelineStep(st.step); writeRoute(true); }
        render(true);
        // The pushed transcript is mounted outside #app. Refresh it explicitly so a running call's
        // tokens, status, objective context, and messages stay in sync with the live session while
        // retaining both the selected call and the reader's current header control focus.
        if (txEl && D === session && txCallIndex >= 0 && txCallIndex < D.llm.length) refreshTranscriptPanel(true, true);
        // Only scroll when the selection actually moved. render(true) already restored the reader's
        // scroll offset, and centering on a push that changed nothing they were looking at would
        // drag the timeline out from under them on every tick.
        if (followed && st.tab === 'timeline') centerTimelineSelection(false);
      },
      destroy() {
        torn = true;
        if (ownDispose) ownDispose();
        if (disposeViewerGlobals === ownDispose) disposeViewerGlobals = null;
      },
    };
  }

  render();
  if (st.view === 'detail' && st.tab === 'timeline') centerTimelineSelection(true);

  // Autoplay is the LAST thing boot does: everything above (route, listeners, first render) is the
  // state it plays from.
  if (AUTOPLAY) whenDocumentComplete(startExportAutoplay);
}
