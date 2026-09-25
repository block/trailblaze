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
:root {
  --tb-analysis-red: #cf222e;
  --tb-analysis-orange: #bc4c00;
  --tb-analysis-yellow: #bf8700;
}
[data-theme="dark"] {
  --tb-analysis-red: #f85149;
  --tb-analysis-orange: #f0883e;
  --tb-analysis-yellow: #d4a72c;
}
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
body:has(> #tb-shell) > #tb-shell { flex: 0 0 auto; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
body:has(> #tb-shell) > #tb-shell.tb-shell-panel-visible { flex: 1 1 auto; }
body:has(> #tb-shell) > #tb-shell.tb-shell-min { flex: 0 0 auto; }
body:has(> #tb-shell) > #app { flex: 1 1 auto; height: auto; min-height: 0; }
/* Wraps rather than overflowing: on a narrow window an unwrapped row pushes the trailing controls
   (Choose files…, Share) off the edge with no way to scroll to them, and the body rule above already
   gives #app whatever height is left over when the bar takes two lines. */
#tb-shell-bar {
  flex: none; display: flex; flex-wrap: wrap; align-items: center; gap: 10px; padding: 10px 14px;
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
  /* Spelled out rather than as a font: shorthand. A CSS-wide keyword is only legal as a shorthand's
     WHOLE value, so naming inherit in its family slot makes the declaration invalid and drops the
     size and weight with it, leaving the bar in the UA's default font. */
  font-family: inherit; font-size: 12.5px; font-weight: 500; padding: 7px 14px; border-radius: var(--r-md); cursor: pointer; white-space: nowrap;
  border: 1px solid var(--line); background: var(--raised); color: var(--txt);
}
#tb-shell-render { border-color: transparent !important; background: var(--pass) !important; color: var(--bg) !important; }
#tb-shell-bar button:not(:disabled):hover { border-color: var(--focus); }
#tb-shell-bar button:disabled { opacity: .4; cursor: default; }
#tb-shell-stats { font-size: var(--type-micro); color: var(--sub); white-space: nowrap; }
/* The archives lined up so far, one removable chip each. Its own row under the bar rather than
   inside it: several long artifact URLs would otherwise squeeze the input down to nothing, and the
   row has to wrap freely as the list grows. */
/* Scrolls once it is taller than a third of the window. The page itself cannot scroll — the report's
   stylesheet fixes html/body to the viewport with overflow: hidden — so a list long enough to run
   past the fold would simply clip its last rows, and those rows carry the buttons for taking them
   back out. */
#tb-shell-list {
  flex: none; display: flex; flex-wrap: wrap; gap: 6px; padding: 8px 14px;
  max-height: 33dvh; overflow-y: auto; overscroll-behavior: contain;
  border-bottom: 1px solid var(--line); background: var(--header);
}
#tb-shell-list[hidden] { display: none; }
/* The list's changes are announced through a region of its own rather than by making the list itself
   live. A live list re-reads every surviving row when one is removed — four artifact links to
   report that a fifth is gone — and it carries [hidden] while empty, which keeps the FIRST archive
   added from being announced at all. This one is always in the tree, and only ever holds the delta.
   Off-screen rather than display:none, which is not announced either. */
.tb-shell-sr {
  position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0;
  overflow: hidden; clip-path: inset(50%); white-space: nowrap; border: 0;
}
#tb-shell.tb-shell-min #tb-shell-list { display: none; }
.tb-shell-src {
  display: inline-flex; align-items: center; gap: 6px; max-width: 100%;
  padding: 3px 4px 3px 9px; border-radius: var(--r-md);
  border: 1px solid var(--line); background: var(--raised);
  font: 12px ui-monospace, SFMono-Regular, Menlo, monospace; color: var(--txt);
}
/* A file source reads differently from a URL one because it behaves differently: it can't be shared
   by link, and the list it belongs to renders in place instead of navigating. */
.tb-shell-src-file { border-style: dashed; }
.tb-shell-srcname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 46ch; }
.tb-shell-srcx {
  flex: none; padding: 0 5px; border: 0; border-radius: var(--r-sm); line-height: 1.4;
  background: transparent; color: var(--sub); font-family: inherit; font-size: 13px; cursor: pointer;
}
.tb-shell-srcx:hover { background: var(--line); color: var(--txt); }
/* Once a report is on screen the loader has done its job, so the bar collapses to a slim handle:
   the report gets the height back, and one click on the handle brings the loader back for the next
   archive. (Dropping a zip anywhere still works while collapsed.) */
#tb-shell.tb-shell-min #tb-shell-bar { display: none; }
#tb-shell-handle {
  flex: none; display: none; width: 100%; height: 14px; padding: 0; align-items: center; justify-content: center;
  border: 0; border-bottom: 1px solid var(--line); background: var(--header); color: var(--sub); cursor: pointer;
}
#tb-shell.tb-shell-min #tb-shell-handle { display: flex; }
#tb-shell-handle svg { width: 12px; height: 12px; }
#tb-shell-handle:hover { color: var(--txt); background: var(--raised); }
#tb-shell-panel {
  flex: 1 1 auto; min-height: 0; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; padding: 48px 24px; overflow-y: auto; overscroll-behavior: contain; text-align: center;
}
#tb-shell-panel .tb-shell-title { font-size: var(--type-small); font-weight: var(--font-weight-emphasis); }
#tb-shell-panel .tb-shell-sub { font-size: var(--type-caption); color: var(--sub); max-width: 520px; }
#tb-shell-panel .tb-shell-err { font-size: var(--type-caption); color: var(--fail); max-width: 560px; user-select: text; white-space: pre-line; }
#tb-shell-panel code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: var(--type-micro); color: var(--sub); word-break: break-all; }
#tb-shell-panel .tb-shell-hint { font-size: var(--type-micro); color: var(--sub); display: flex; align-items: center; gap: 8px; width: 100%; max-width: 520px; }
#tb-shell-panel .tb-shell-hint .rule { flex: 1; height: 1px; background: var(--line); min-width: 30px; }
#tb-shell-panel:has(.tb-analysis) { justify-content: flex-start; }
.tb-analysis { flex: none; width: min(980px, 100%); min-height: auto; display: grid; gap: 18px; margin: 0 auto; overflow: visible; text-align: left; }
.tb-analysis h1, .tb-analysis h2, .tb-analysis h3, .tb-analysis p { margin: 0; }
.tb-analysis a { color: var(--focus); }
.tb-analysis button { color: var(--txt); background: var(--raised); border: 1px solid var(--line); border-radius: var(--r-md); padding: 7px 10px; cursor: pointer; }
.tb-analysis-heading { display: flex; align-items: end; justify-content: space-between; gap: 20px; }
.tb-analysis-heading > div { display: grid; gap: 5px; }
.tb-analysis-heading > p, .tb-analysis-card header, .tb-analysis-meta, .tb-analysis-eyebrow { color: var(--sub); font-size: var(--type-caption); }
.tb-analysis-eyebrow { text-transform: uppercase; letter-spacing: .06em; }
.tb-analysis-coverage { width: fit-content; padding: 5px 9px; border-radius: 999px; background: var(--raised); color: var(--sub); font-size: var(--type-caption); }
.tb-analysis-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(300px, 100%), 1fr)); gap: 14px; }
.tb-analysis-card, .tb-analysis-focus { --tb-analysis-tone: var(--focus); }
.tb-analysis-card { display: grid; align-content: start; gap: 10px; padding: 18px; border: 1px solid var(--line); border-left: 5px solid var(--tb-analysis-tone); border-radius: var(--r-lg); background: var(--raised); }
.tb-analysis-card header, .tb-analysis-focus > header, .tb-analysis-toolbar, .tb-analysis-siblings { display: flex; justify-content: space-between; gap: 12px; }
.tb-analysis-card h2 { font-size: var(--type-title); }
.tb-analysis-card h3, .tb-analysis-kind { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .05em; color: var(--sub); }
.tb-analysis-open { margin-top: auto; width: fit-content; font-weight: var(--font-weight-emphasis); }
.tb-analysis-critical { --tb-analysis-tone: var(--tb-analysis-red); }
.tb-analysis-warning { --tb-analysis-tone: var(--tb-analysis-orange); }
.tb-analysis-notice { --tb-analysis-tone: var(--tb-analysis-yellow); }
.tb-analysis-info, .tb-analysis-neutral { --tb-analysis-tone: var(--focus); }
.tb-analysis-status { display: inline-flex; align-items: center; gap: 6px; font-weight: var(--font-weight-emphasis); color: var(--txt); }
.tb-analysis-status::before { width: 7px; height: 7px; flex: none; border-radius: 50%; background: var(--tb-analysis-tone); content: ''; }
.tb-analysis-toolbar { align-items: center; }
.tb-analysis-copy-error { color: var(--fail); font-size: var(--type-caption); }
.tb-analysis-focus { display: grid; gap: 22px; padding: 24px; border: 1px solid var(--line); border-left: 5px solid var(--tb-analysis-tone); border-radius: var(--r-lg); background: var(--raised); }
.tb-analysis-focus > header > div { display: flex; gap: 12px; align-items: baseline; }
.tb-analysis-focus section { display: grid; gap: 8px; }
.tb-analysis-focus h2 { font-size: var(--type-small); }
.tb-analysis-attention { font-size: var(--type-title); line-height: 1.45; }
.tb-analysis-action { padding: 15px; border-radius: var(--r-md); background: color-mix(in srgb, var(--focus) 10%, var(--bg)); }
.tb-analysis-priority { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 14px; align-items: stretch; }
.tb-analysis-priority > section { align-content: start; padding: 15px; border-radius: var(--r-md); }
.tb-analysis-key-evidence { background: color-mix(in srgb, var(--raised) 78%, var(--bg)); border: 1px solid var(--line); }
.tb-analysis-interpretation { padding: 15px; border-left: 3px solid var(--focus); background: color-mix(in srgb, var(--focus) 6%, transparent); }
.tb-analysis-uncertainty { padding: 15px; border-left: 3px solid var(--warn); background: color-mix(in srgb, var(--warn) 7%, transparent); }
.tb-analysis-subjects, .tb-analysis-evidence-list, .tb-analysis-focus ol, .tb-analysis-focus ul { display: grid; gap: 6px; margin: 0; padding-left: 20px; }
.tb-analysis-subjects li span, .tb-analysis-focus ol li span, .tb-analysis-focus ul li span { margin-left: 8px; color: var(--sub); }
.tb-analysis-evidence { padding: 10px 0; }
.tb-analysis-evidence > div { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; }
.tb-analysis-evidence p { margin-top: 5px; }
.tb-analysis-unavailable { padding: 2px 6px; border-radius: 999px; background: color-mix(in srgb, var(--warn) 14%, transparent); color: var(--warn); font-size: var(--type-micro); }
.tb-analysis details { border-top: 1px solid var(--line); padding-top: 14px; }
.tb-analysis summary { cursor: pointer; font-weight: var(--font-weight-emphasis); }
.tb-analysis-siblings a:last-child { margin-left: auto; text-align: right; }
@media (max-width: 480px) {
  #tb-shell-panel { padding: 24px 12px; }
  .tb-analysis-heading, .tb-analysis-focus > header, .tb-analysis-toolbar { align-items: flex-start; flex-direction: column; }
  .tb-analysis-focus { padding: 16px; }
  .tb-analysis-priority { grid-template-columns: 1fr; }
  .tb-analysis-siblings { display: grid; grid-template-columns: 1fr; }
  .tb-analysis-siblings a:last-child { margin-left: 0; text-align: left; }
}
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
    <button id="tb-shell-add" type="button" title="Line this URL up without rendering yet">Add</button>
    <button id="tb-shell-render" type="button">Render</button>
    <button id="tb-shell-pick" type="button">Choose files…</button>
    <button id="tb-shell-share" type="button" disabled>Share</button>
    <span id="tb-shell-stats"></span>
    <button id="tb-shell-collapse" type="button" hidden aria-label="Hide the loader bar" title="Hide the loader bar">Hide</button>
    <input id="tb-shell-file" type="file" accept=".zip,application/zip" multiple hidden />
  </div>
  <div id="tb-shell-list" hidden role="list" aria-label="Archives lined up to render"></div>
  <div id="tb-shell-live" class="tb-shell-sr" role="status" aria-live="polite"></div>
  <button id="tb-shell-handle" type="button" aria-label="Show the report loader" title="Load a different report"><svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"/></svg></button>
  <div id="tb-shell-panel">
    <div class="tb-shell-title">Render a report from a session archive</div>
    <div class="tb-shell-sub">
      Drop Trailblaze session <code>.zip</code> files anywhere on this page — or <b>Choose files…</b> — and every
      log, screenshot, and step timeline in them renders right here. The archives are read in your browser and
      never leave your machine, so this needs no network at all.
    </div>
    <div class="tb-shell-hint"><span class="rule"></span>or load one by URL<span class="rule"></span></div>
    <div class="tb-shell-sub">
      Paste an archive URL above, or link straight to one with <code>?zip=&lt;archive-url&gt;</code> — the way to
      share a report as a link. That fetches the archive across origins, so it works only when the host
      serving it sends an <code>Access-Control-Allow-Origin</code> header.
    </div>
    <div class="tb-shell-sub">
      To see several runs together, <b>Add</b> them one at a time — each becomes a row you can take back out —
      or drop and pick as many files at once as you like. A list of URLs is also a link:
      <code>?zip=&lt;url-1&gt;&amp;zip=&lt;url-2&gt;</code>. They render as one report, so its run index can compare
      any runs you pick — and when they are the same trail, lane them up side by side in <b>Replay</b>, <b>Grid</b> and <b>Map</b>.
    </div>
  </div>
</div>
<div id="app" style="display:none"></div>
<div id="tb-shell-overlay">
  <div>
    Drop the <code>.zip</code> files to render them
    <div class="tb-shell-sub">Files loaded this way stay on your machine — they can't be shared by link.</div>
  </div>
</div>
<script>${inertScriptBody(embeddedZipReportCoreScript())}</script>
<script>${inertScriptBody(RUN_REPORT_VIEWER_SCRIPT)}</script>
<script>${inertScriptBody(VIEWER_SHELL_SCRIPT)}</script>${SELECTOR_ENGINE_CHUNK}
</body>
</html>`;
}

export { buildViewerShellHtml };
