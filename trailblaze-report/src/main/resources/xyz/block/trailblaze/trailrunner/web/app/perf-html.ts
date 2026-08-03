// Self-contained performance-analysis report HTML assembly: wraps the extracted per-session
// profiles, the stylesheet, and the prebuilt viewer bundle into one offline document. Sibling of
// run-report-html.ts (interactive run report) — same conventions: inline CSS, inert JSON payload
// script, embedded viewer IIFE, pre-paint theme script sharing the run report's localStorage key.
// Shared contract types come from the ambient perf-types.d.ts.
import { PERF_REPORT_CSS } from './perf-css';
import { tbBootLoaderHtml, toInertJson } from './run-report-payload';
import { embeddedPerfViewerScript } from './perf-viewer-bundle.macro' with { type: 'macro' };

// The exact classic-script source of the standalone viewer, prebuilt by the bun bundler and
// inlined here at transpile time (see perf-viewer-bundle.macro.ts).
const PERF_VIEWER_SCRIPT: string = embeddedPerfViewerScript();

// Assemble the full self-contained HTML document for one build's profiled sessions. Pure: callers
// supply already-extracted PerfSessionData (see extractPerfSession in perf-extract.ts); no fetch,
// no DOM — identical in the browser and in bun.
function buildPerfReportHtml({ generatedAt, sessions }: { generatedAt?: string; sessions: PerfSessionPayload[] }): string {
  const payload: PerfReportPayload = { generatedAt: generatedAt || '', sessions: sessions || [] };
  const json = toInertJson(payload);
  const escText = (s: string): string => s.replace(/[<>&]/g, (c) => ({ '<': '&lt;', '>': '&gt;', '&': '&amp;' }[c] as string));
  const heading = 'Trailblaze Performance Analysis';
  const title = escText(payload.sessions.length === 1 ? `${payload.sessions[0].meta.title || 'Trailblaze run'} · Performance Analysis` : heading);
  // Same document skeleton as buildMultiReportHtml: pre-paint theme script (shared localStorage
  // key so both reports agree on light/dark), #tb-boot loader painted while the payload parses,
  // inert JSON payload, then the viewer bundle.
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>${title}</title>
<script>(()=>{let theme='dark';try{const saved=localStorage.getItem('trailblaze-report-theme');theme=saved==='light'||saved==='dark'?saved:(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark')}catch(e){theme=typeof matchMedia==='function'&&matchMedia('(prefers-color-scheme: light)').matches?'light':'dark'}document.documentElement.dataset.theme=theme})()</script>
<style>${PERF_REPORT_CSS}</style>
</head>
<body>
<div id="app">${tbBootLoaderHtml(heading)}</div>
<script type="application/json" id="tb-perf-data">${json}</script>
<script>${PERF_VIEWER_SCRIPT}</script>
</body>
</html>`;
}

export { buildPerfReportHtml };
