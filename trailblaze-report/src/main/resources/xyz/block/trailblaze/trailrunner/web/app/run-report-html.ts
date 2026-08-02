// Self-contained report HTML assembly (moved from share-export.jsx; this is now the only copy):
// slims each session's derived data into the embedded payload and wraps it, the stylesheet, and
// the prebuilt viewer bundle into one offline document.
// Shared contract types come from the ambient run-report-types.d.ts (see its header for why it
// stays ambient rather than becoming module exports).
import { RUN_REPORT_CSS } from './run-report-css';
import { slimLlmForShare, slimTraceForShare } from './run-report-extract';
import { tbBootLoaderHtml, toInertJson } from './run-report-payload';
import { embeddedViewerScript } from './run-report-viewer-bundle.macro' with { type: 'macro' };

// The exact classic-script source of the standalone viewer, prebuilt by the bun bundler and
// inlined here at transpile time (see run-report-viewer-bundle.macro.ts). Embedding a real bundle
// (instead of .toString()-serializing functions) lets the viewer live in ordinary modules.
const RUN_REPORT_VIEWER_SCRIPT: string = embeddedViewerScript();

// Assemble the full self-contained HTML document for ONE run. Thin wrapper over
// buildMultiReportHtml so the in-app Share button (browser) and the single-run case share one data
// contract. Optional generic event streams and the authored/recorded YAML ride alongside the trace,
// LLM calls, and screenshots. Pure: no fetch, no DOM — usable identically in the browser and bun.
function buildRunReportHtml({ meta, trace, llmLogs, shots, events = null }: { meta: RunMeta; trace: RawTraceRow[]; llmLogs: RawLlmRow[]; shots: Record<string, string>; events?: EventStream[] | null }): string {
  // Recording YAML rides in on meta.recordingYaml; lift it into the dedicated session field and drop
  // it from meta so the (potentially large) string isn't embedded twice in the payload.
  const { recordingYaml = null, originalYaml = null, ...metaRest } = meta || {};
  return buildMultiReportHtml({
    generatedAt: metaRest.generatedAt || '',
    sessions: [{ meta: metaRest, trace, llmLogs, shots, recordingYaml, originalYaml, events }],
  });
}

// Assemble the full self-contained HTML document for ONE OR MORE runs. Each session carries its own
// derived trace/llmLogs, screenshot map, and (optional) recording YAML. A single session opens
// straight on that run's detail (mirroring the old WASM report's single-session auto-advance); with
// several it opens on a pass/fail session index that drills into each run. Pure: callers supply
// already-derived data; no fetch, no DOM — identical in the browser and in bun.
function buildMultiReportHtml({ generatedAt, shareUrl, sessions }: { generatedAt?: string; shareUrl?: string; sessions: SessionInput[] }): string {
  const list: SessionPayload[] = (sessions || []).map((s) => {
    const trace = s.trace || [];
    return {
      meta: { generatedAt: generatedAt || '', ...(s.meta || {}), steps: trace.length || (s.meta && s.meta.steps) || 0 },
      trace: slimTraceForShare(trace),
      llm: slimLlmForShare(s.llmLogs),
      shots: s.shots || {},
      recordingYaml: s.recordingYaml || null,
      originalYaml: s.originalYaml || null,
      deviceLog: s.deviceLog || null,
      deviceLogGz: s.deviceLogGz || null,
      network: s.network || null,
      networkGz: s.networkGz || null,
      events: s.events || null,
      eventsGz: s.eventsGz || null,
      video: s.video || null,
    };
  });
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
  const payload = { generatedAt: generatedAt || '', ...(shareUrl ? { shareUrl } : {}), sessions: list };
  // Embed the payload as an inert JSON <script> the viewer reads back with JSON.parse. That keeps
  // megabytes of data out of the JS parser on the critical boot path (a JS object literal blocks
  // evaluation — and paint — until fully parsed). Security-wise this is equivalent to the old
  // object-literal embed: textContent → JSON.parse is not an HTML sink (nothing is reinterpreted
  // as markup), and every user-supplied field is still escaped at render time. toInertJson keeps
  // the `</script>`-closes-the-element escape in one place.
  const json = toInertJson(payload);
  const spritesJson = toInertJson(sprites);
  const escText = (s: string) => s.replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c]));
  const heading = list.length === 1 ? (list[0].meta.title || 'Trailblaze run') : 'Trailblaze Report';
  const title = escText(list.length === 1 ? heading + ' · Trailblaze run' : heading);
  // The #tb-boot loader lives INSIDE #app and BEFORE the data script: it's plain markup styled by
  // the already-parsed head CSS (theme included), so it paints while the payload is still being
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
<script type="application/json" id="tb-run-data">${json}</script>
<script type="application/json" id="tb-sprites">${spritesJson}</script>
<script>${RUN_REPORT_VIEWER_SCRIPT}</script>
</body>
</html>`;
}

export { buildRunReportHtml, buildMultiReportHtml };
