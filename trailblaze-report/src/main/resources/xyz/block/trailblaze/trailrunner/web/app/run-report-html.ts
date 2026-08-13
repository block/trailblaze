// Self-contained report HTML assembly (moved from share-export.jsx; this is now the only copy):
// slims each session's derived data into the embedded payload and wraps it, the stylesheet, and
// the prebuilt viewer bundle into one offline document.
// Shared contract types come from the ambient run-report-types.d.ts (see its header for why it
// stays ambient rather than becoming module exports).
import { RUN_REPORT_CSS } from './run-report-css';
import { toSessionPayloads, traceStepCount, traceToolCallCount } from './run-report-extract';
import { inertScriptBody, tbBootLoaderHtml, toInertJson } from './run-report-payload';
import { embeddedViewerScript } from './run-report-viewer-bundle.macro' with { type: 'macro' };

// The exact classic-script source of the standalone viewer, prebuilt by the bun bundler and
// inlined here at transpile time (see run-report-viewer-bundle.macro.ts). Embedding a real bundle
// (instead of .toString()-serializing functions) lets the viewer live in ordinary modules.
const RUN_REPORT_VIEWER_SCRIPT: string = embeddedViewerScript();

// Assemble the full self-contained HTML document for ONE run. Thin wrapper over
// buildMultiReportHtml so the in-app Share button (browser) and the single-run case share one data
// contract. Optional generic event streams, the authored/recorded YAML, and pre-packed hierarchies
// (packSessionInputsHierarchies — the Share path compresses before serializing) ride alongside the
// trace, LLM calls, and screenshots. Pure: no fetch, no DOM — usable identically in browser and bun.
function buildRunReportHtml({ meta, trace, llmLogs, shots, events = null, hierarchies = null, hierarchiesGz = null }: { meta: RunMeta; trace: RawTraceRow[]; llmLogs: RawLlmRow[]; shots: Record<string, string>; events?: EventStream[] | null; hierarchies?: Record<string, unknown> | null; hierarchiesGz?: string | null }): string {
  return buildMultiReportHtml({
    generatedAt: (meta || {}).generatedAt || '',
    sessions: [{ meta, trace, llmLogs, shots, events, hierarchies, hierarchiesGz }],
  });
}

// Assemble the full self-contained HTML document for ONE OR MORE runs. Each session carries its own
// derived trace/llmLogs, screenshot map, and (optional) recording YAML. A single session opens
// straight on that run's detail (mirroring the old WASM report's single-session auto-advance); with
// several it opens on a pass/fail session index that drills into each run. Pure: callers supply
// already-derived data; no fetch, no DOM — identical in the browser and in bun.
function buildMultiReportHtml({ generatedAt, shareUrl, sessions }: { generatedAt?: string; shareUrl?: string; sessions: SessionInput[] }): string {
  // Slimming, the llmLogs → llm rename, and lifting recording/original YAML off meta are shared with
  // the viewer shell's in-place hydration (toSessionPayloads in run-report-extract), so an embedded
  // payload and a shell-loaded one are the same shape. The sprite hoist below is this path's alone:
  // it depends on the #tb-sprites-<i> chunks only a document carries.
  const list: SessionPayload[] = toSessionPayloads({ generatedAt, sessions });
  // Hoist each session's sprite-sheet data URIs out of the main payload: they're the largest
  // blobs in the document and are only needed once a video frame actually renders. Keeping them
  // out of the payload the viewer JSON.parses at boot means first paint never waits on sprite
  // bytes; the viewer resolves them lazily from #tb-sprites (keyed by session index, one URI
  // array per session in sheet order) on first access. Per-sheet row counts stay inline — the
  // frame math needs them and they're tiny.
  const sprites: Record<string, string[]> = {};
  list.forEach((s, i) => {
    if (s.video && s.video.sprites.some((sp) => sp.uri)) {
      sprites[String(i)] = s.video.sprites.map((sp) => sp.uri);
      s.video = { ...s.video, sprites: s.video.sprites.map((sp) => ({ ...sp, uri: '' })) };
    }
  });
  // Split the document so boot time is independent of report size. The tiny #tb-index chunk (per
  // session: meta + per-call LLM token/cost summaries + the two trace-derived counts the run list
  // shows) and the viewer script come FIRST, so on a streaming multi-megabyte document the browser
  // can boot the viewer and paint the full run index while the heavy per-session chunks
  // (#tb-session-<i>, #tb-sprites-<i>) are still arriving. The viewer JSON.parses one session
  // chunk only when that run is opened, so a 100-session report never parses 100 sessions' bytes
  // to show the list — and no single JSON string ever approaches the JS engine's
  // max-string-length ceiling.
  const indexEntries = list.map((s) => ({
    meta: s.meta,
    // The run list reads exactly three numeric fields per LLM call (call count, token totals, and
    // the cost total/sort). Instructions and response text stay in the session chunk, so the boot
    // index doesn't scale with LLM log size.
    llm: s.llm.map(({ inputTokens, outputTokens, totalCost }) => ({ inputTokens, outputTokens, totalCost })),
    stepCount: s.trace.length ? traceStepCount(s.trace) : null,
    toolCallCount: s.trace.length ? traceToolCallCount(s.trace) : (s.meta.steps != null ? s.meta.steps : null),
  }));
  // Every embedded JSON chunk is an inert <script type="application/json"> the viewer reads back
  // with JSON.parse. That keeps megabytes of data out of the JS parser on the critical boot path
  // (a JS object literal blocks evaluation — and paint — until fully parsed). Security-wise this
  // is equivalent to the old object-literal embed: textContent → JSON.parse is not an HTML sink
  // (nothing is reinterpreted as markup), and every user-supplied field is still escaped at
  // render time. toInertJson keeps the `</script>`-closes-the-element escape in one place.
  const indexJson = toInertJson({ generatedAt: generatedAt || '', ...(shareUrl ? { shareUrl } : {}), sessions: indexEntries });
  const sessionChunks = list.map((s, i) => `<script type="application/json" id="tb-session-${i}">${toInertJson(s)}</script>`
    + (sprites[String(i)] ? `\n<script type="application/json" id="tb-sprites-${i}">${toInertJson(sprites[String(i)])}</script>` : '')).join('\n');
  const escText = (s: string) => s.replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));
  const heading = list.length === 1 ? (list[0].meta.title || 'Trailblaze run') : 'Trailblaze Report';
  const title = escText(list.length === 1 ? heading + ' · Trailblaze run' : heading);
  // The #tb-boot loader lives INSIDE #app and BEFORE the data scripts: it's plain markup styled by
  // the already-parsed head CSS (theme included), so it paints while the index is still being
  // parsed. The viewer's first render replaces #app's content, which removes it.
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${title}</title>
<script>(()=>{let theme='dark';try{const saved=localStorage.getItem('trailblaze-report-theme');theme=saved==='light'||saved==='dark'?saved:(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark')}catch(e){theme=typeof matchMedia==='function'&&matchMedia('(prefers-color-scheme: light)').matches?'light':'dark'}document.documentElement.dataset.theme=theme})()</script>
<style>${RUN_REPORT_CSS}</style>
</head>
<body>
<div id="app">${tbBootLoaderHtml(heading)}</div>
<script type="application/json" id="tb-index">${indexJson}</script>
<script>${inertScriptBody(RUN_REPORT_VIEWER_SCRIPT)}</script>
${sessionChunks}
</body>
</html>`;
}

export { buildRunReportHtml, buildMultiReportHtml };
