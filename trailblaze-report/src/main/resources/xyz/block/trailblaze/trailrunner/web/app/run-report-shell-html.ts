// The viewer shell document: the data-less edition of the interactive report. It carries the report's
// own stylesheet, the same prebuilt viewer bundle every exported report embeds, the ZIP pipeline, and
// the shell's loader — so a session archive becomes a full interactive report with no daemon, no
// backend, and no upload.
//
// Deliberately NOT part of the run-report-core entry graph. Exporting it from there dragged both macro
// payloads (the inlined ZIP pipeline and the loader bundle) into the packaged run-report-core.js as a
// second, inert copy — +59 KB on a bundle Trail Runner's page loads, which also fetches
// zip-report-core.js executably and would transfer that pipeline twice. viewer-shell-cli.ts imports
// this module directly, which is the only consumer that needs it.
import { RUN_REPORT_CSS } from './run-report-css';
import { embeddedShellScript } from './run-report-shell-bundle.macro' with { type: 'macro' };
import { embeddedViewerScript } from './run-report-viewer-bundle.macro' with { type: 'macro' };
import { inertScriptBody, toInertJson } from './run-report-payload';
import { embeddedSelectorEngine } from './selector-engine-bundle.macro' with { type: 'macro' };
import { embeddedZipReportCoreScript } from './zip-report-core-bundle.macro' with { type: 'macro' };

const RUN_REPORT_VIEWER_SCRIPT: string = embeddedViewerScript();

// The UI Inspector's selector engine, in the same `#tb-selector-engine` transport an exported report
// uses. Null when the Kotlin/JS bundle wasn't built — the shell then embeds no chunk and the
// Inspector degrades exactly as it did before, rather than failing the build.
const SELECTOR_ENGINE = embeddedSelectorEngine();

// Unlike a report — which embeds the engine only when a session carries an analyzable hierarchy —
// the shell has no session at build time and must carry it unconditionally: any archive dropped
// later may need it, and there is no second chance to fetch one in an offline, single-file viewer.
const SELECTOR_ENGINE_CHUNK: string = SELECTOR_ENGINE && (SELECTOR_ENGINE.js || SELECTOR_ENGINE.gz)
  ? `\n<script type="application/json" id="tb-selector-engine">${toInertJson({
    ...(SELECTOR_ENGINE.js ? { js: SELECTOR_ENGINE.js } : {}),
    ...(SELECTOR_ENGINE.gz ? { gz: SELECTOR_ENGINE.gz } : {}),
  })}</script>`
  : '';

// The viewer shell's loader script, inlined the same way (see run-report-shell-bundle.macro.ts).
const VIEWER_SHELL_SCRIPT: string = embeddedShellScript();

// Chrome for the viewer shell. Uses the report's own theme variables (RUN_REPORT_CSS defines them
// for both themes) so the loader follows light/dark with the report it renders, instead of pinning
// its own palette.
const VIEWER_SHELL_CSS = `
html[data-tb-shell] body, body:has(> #tb-shell) { margin: 0; }
/* The report's #app sizes itself to the full viewport (height: 100dvh) because in an exported
   document it IS the whole page. Here it sits below the shell bar, so that height overflows by
   exactly the bar's height and the report's bottom row (the run's target/platform/duration
   footer) lands under the fold — unreachable, since the report also sets overflow: hidden. Give
   the shell a flex column body instead and let #app take what's left, which also keeps it right
   when the bar wraps to two lines on a narrow window.
   Selector note: NOT html[data-tb-shell] — the loader clears that marker when it boots the
   viewer, i.e. precisely when #app becomes visible. #tb-shell is the stable hook. */
body:has(> #tb-shell) { display: flex; flex-direction: column; height: 100dvh; }
body:has(> #tb-shell) > #app { flex: 1 1 auto; height: auto; min-height: 0; }
#tb-shell-bar {
  display: flex; align-items: center; gap: 10px; padding: 10px 14px;
  border-bottom: 1px solid var(--line); background: var(--header);
}
#tb-shell-bar .tb-shell-brand { font-weight: var(--font-weight-emphasis); font-size: var(--type-small); white-space: nowrap; display: flex; align-items: center; gap: 8px; }
#tb-shell-bar .tb-shell-brand .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--pass); }
#tb-shell-url {
  flex: 1; min-width: 120px; font: 12px ui-monospace, SFMono-Regular, Menlo, monospace;
  padding: 7px 10px; border-radius: var(--r-md); border: 1px solid var(--line);
  background: var(--raised); color: var(--txt); outline: none;
}
#tb-shell-url:focus { border-color: var(--focus); }
#tb-shell-bar button {
  font: 500 12.5px inherit; padding: 7px 14px; border-radius: var(--r-md); cursor: pointer; white-space: nowrap;
  border: 1px solid var(--line); background: var(--raised); color: var(--txt);
}
#tb-shell-render { border-color: transparent !important; background: var(--pass) !important; color: var(--bg) !important; }
#tb-shell-bar button:not(:disabled):hover { border-color: var(--focus); }
#tb-shell-bar button:disabled { opacity: .4; cursor: default; }
#tb-shell-stats { font-size: var(--type-micro); color: var(--sub); white-space: nowrap; }
#tb-shell-panel {
  display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; padding: 48px 24px; text-align: center;
}
#tb-shell-panel .tb-shell-title { font-size: var(--type-small); font-weight: var(--font-weight-emphasis); }
#tb-shell-panel .tb-shell-sub { font-size: var(--type-caption); color: var(--sub); max-width: 520px; }
#tb-shell-panel .tb-shell-err { font-size: var(--type-caption); color: var(--fail); max-width: 560px; user-select: text; white-space: pre-line; }
#tb-shell-panel code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: var(--type-micro); color: var(--sub); word-break: break-all; }
#tb-shell-panel .tb-shell-hint { font-size: var(--type-micro); color: var(--sub); display: flex; align-items: center; gap: 8px; width: 100%; max-width: 520px; }
#tb-shell-panel .tb-shell-hint .rule { flex: 1; height: 1px; background: var(--line); min-width: 30px; }
.tb-shell-spinner {
  width: 16px; height: 16px; border-radius: 50%; border: 2px solid var(--line);
  border-top-color: var(--focus); animation: tb-shell-spin .8s linear infinite;
}
@keyframes tb-shell-spin { to { transform: rotate(360deg); } }
#tb-shell-overlay {
  position: fixed; inset: 12px; z-index: 100; display: none;
  align-items: center; justify-content: center; text-align: center;
  background: color-mix(in srgb, var(--bg) 90%, transparent);
  border: 3px dashed var(--focus); border-radius: var(--r-lg);
  font-size: 16px; font-weight: var(--font-weight-emphasis); color: var(--txt); pointer-events: none;
}
#tb-shell-overlay.show { display: flex; }
#tb-shell-overlay .tb-shell-sub { font-weight: var(--font-weight-body); font-size: var(--type-caption); color: var(--sub); margin-top: 6px; }
`;

// The data-less edition of the report: the same stylesheet and the same viewer bundle an exported
// report carries, plus the zip pipeline and the loader chrome — so a session archive can be turned
// into a full interactive report with no daemon, no backend, and no upload. This is the artifact the
// hosted viewer is published from; there is no separate hand-maintained viewer page to drift from the
// renderer.
//
// The `data-tb-shell` attribute is the contract with run-report-viewer-boot: it suppresses the
// viewer's auto-boot in a document that has no payload yet. The shell's loader clears it and boots
// the viewer once an archive is loaded.
function buildViewerShellHtml(): string {
  return `<!doctype html>
<html lang="en" data-tb-shell>
<head>
<meta charset="utf-8" />
<meta name="viewport" content="width=device-width, initial-scale=1.0" />
<title>Trailblaze Report Viewer</title>
<script>(()=>{let theme='dark';try{const saved=localStorage.getItem('trailblaze-report-theme');theme=saved==='light'||saved==='dark'?saved:(matchMedia('(prefers-color-scheme: light)').matches?'light':'dark')}catch(e){theme=typeof matchMedia==='function'&&matchMedia('(prefers-color-scheme: light)').matches?'light':'dark'}document.documentElement.dataset.theme=theme})()</script>
<style>${RUN_REPORT_CSS}</style>
<style>${VIEWER_SHELL_CSS}</style>
</head>
<body>
<div id="tb-shell">
  <div id="tb-shell-bar">
    <span class="tb-shell-brand"><span class="dot"></span>Trailblaze Report</span>
    <input id="tb-shell-url" placeholder="https://…/runs/&lt;build&gt;-&lt;job&gt;-&lt;session&gt;.zip" spellcheck="false" aria-label="Session archive URL" />
    <button id="tb-shell-render" type="button">Render</button>
    <button id="tb-shell-pick" type="button">Choose file…</button>
    <button id="tb-shell-share" type="button" disabled>Share</button>
    <span id="tb-shell-stats"></span>
    <input id="tb-shell-file" type="file" accept=".zip,application/zip" hidden />
  </div>
  <div id="tb-shell-panel">
    <div class="tb-shell-title">Render a report from a session archive</div>
    <div class="tb-shell-sub">
      Drop a Trailblaze session <code>.zip</code> anywhere on this page — or <b>Choose file…</b> — and every
      log, screenshot, and step timeline in it renders right here. The archive is read in your browser and
      never leaves your machine, so this needs no network at all.
    </div>
    <div class="tb-shell-hint"><span class="rule"></span>or load one by URL<span class="rule"></span></div>
    <div class="tb-shell-sub">
      Paste an archive URL above, or link straight to one with <code>?zip=&lt;archive-url&gt;</code> — the way to
      share a report as a link. That fetches the archive across origins, so it works only when the host
      serving it sends an <code>Access-Control-Allow-Origin</code> header.
    </div>
  </div>
</div>
<div id="app" style="display:none"></div>
<div id="tb-shell-overlay">
  <div>
    Drop the <code>.zip</code> to render it
    <div class="tb-shell-sub">A file loaded this way stays on your machine — it can't be shared by link.</div>
  </div>
</div>
<script>${inertScriptBody(embeddedZipReportCoreScript())}</script>
<script>${inertScriptBody(RUN_REPORT_VIEWER_SCRIPT)}</script>
<script>${inertScriptBody(VIEWER_SHELL_SCRIPT)}</script>${SELECTOR_ENGINE_CHUNK}
</body>
</html>`;
}

export { buildViewerShellHtml };
