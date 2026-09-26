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
  --tb-analysis-bg: #f6f8fa;
  --tb-analysis-bar: #ffffff;
  --tb-analysis-card: #ffffff;
  --tb-analysis-control: #f6f8fa;
  --tb-analysis-control-hover: #eff2f5;
  --tb-analysis-chip: #eff2f5;
  --tb-analysis-text: #1f2328;
  --tb-analysis-deck: #3d444d;
  --tb-analysis-muted: #59636e;
  --tb-analysis-line: rgba(31,35,40,.14);
  --tb-analysis-control-line: rgba(31,35,40,.18);
  --tb-analysis-link: #0969da;
  --tb-analysis-info: #0969da;
  --tb-analysis-period: #c23b50;
  --tb-analysis-grad-start: #d97706;
  --tb-analysis-grad-mid: #d1244f;
  --tb-analysis-grad-end: #8250df;
  --tb-analysis-halo: rgba(209,36,79,.08);
  --tb-analysis-copy-error: #c22e38;
  --tb-analysis-unavailable-bg: #fff1d6;
  --tb-analysis-unavailable-text: #8a5200;
}
[data-theme="dark"] {
  --tb-analysis-red: #f85149;
  --tb-analysis-orange: #f0883e;
  --tb-analysis-yellow: #d4a72c;
  --tb-analysis-bg: #050505;
  --tb-analysis-bar: #0b0b0d;
  --tb-analysis-card: #121214;
  --tb-analysis-control: #1b1b1e;
  --tb-analysis-control-hover: #29292d;
  --tb-analysis-chip: #29292d;
  --tb-analysis-text: #f5f5f7;
  --tb-analysis-deck: #d8d8dd;
  --tb-analysis-muted: #a1a1a8;
  --tb-analysis-line: rgba(255,255,255,.12);
  --tb-analysis-control-line: rgba(255,255,255,.16);
  --tb-analysis-link: #b8d9ff;
  --tb-analysis-info: #b8d9ff;
  --tb-analysis-period: #ff6b6f;
  --tb-analysis-grad-start: #ff9f0a;
  --tb-analysis-grad-mid: #ff375f;
  --tb-analysis-grad-end: #bf5af2;
  --tb-analysis-halo: rgba(255,55,95,.12);
  --tb-analysis-copy-error: #ff969d;
  --tb-analysis-unavailable-bg: rgba(255,159,10,.15);
  --tb-analysis-unavailable-text: #ffd38f;
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
body:has(> #tb-shell) > #tb-shell { flex: 0 0 auto; min-width: 0; width: 100%; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
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
  flex: 1 1 auto; min-width: 0; width: 100%; min-height: 0; display: flex; flex-direction: column; align-items: center; justify-content: center;
  gap: 10px; padding: 48px 24px; overflow-y: auto; overscroll-behavior: contain; text-align: center;
}
#tb-shell-panel .tb-shell-title { font-size: var(--type-small); font-weight: var(--font-weight-emphasis); }
#tb-shell-panel .tb-shell-sub { font-size: var(--type-caption); color: var(--sub); max-width: 520px; }
#tb-shell-panel .tb-shell-err { font-size: var(--type-caption); color: var(--fail); max-width: 560px; user-select: text; white-space: pre-line; }
#tb-shell-panel code { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: var(--type-micro); color: var(--sub); word-break: break-all; }
#tb-shell-panel .tb-shell-hint { font-size: var(--type-micro); color: var(--sub); display: flex; align-items: center; gap: 8px; width: 100%; max-width: 520px; }
#tb-shell-panel .tb-shell-hint .rule { flex: 1; height: 1px; background: var(--line); min-width: 30px; }
#tb-shell-panel:has(.tb-analysis) { justify-content: flex-start; }
.tb-analysis {
  flex: none; min-width: 0; width: min(1120px, 100%); min-height: auto; display: grid; gap: 32px; margin: 0 auto;
  overflow: visible; text-align: left; color: var(--tb-analysis-text);
}
#tb-shell:has(.tb-analysis) { background: var(--tb-analysis-bg); color: var(--tb-analysis-text); }
#tb-shell:has(.tb-analysis) #tb-shell-bar { background: var(--tb-analysis-bar); border-color: var(--tb-analysis-line); }
#tb-shell:has(.tb-analysis) #tb-shell-bar .tb-shell-brand { color: var(--tb-analysis-text); }
#tb-shell:has(.tb-analysis) #tb-shell-bar .tb-shell-brand .dot { background: linear-gradient(135deg, var(--tb-analysis-grad-start), var(--tb-analysis-grad-mid) 55%, var(--tb-analysis-grad-end)); }
#tb-shell:has(.tb-analysis) #tb-shell-url,
#tb-shell:has(.tb-analysis) #tb-shell-bar button { background: var(--tb-analysis-control); border-color: var(--tb-analysis-control-line); color: var(--tb-analysis-text); }
#tb-shell:has(.tb-analysis) #tb-shell-stats { color: var(--tb-analysis-muted); }
#tb-shell-panel:has(.tb-analysis) { background: radial-gradient(ellipse 80% 35% at 50% -15%, var(--tb-analysis-halo), transparent), var(--tb-analysis-bg); padding: 48px 28px 80px; }
.tb-analysis h1, .tb-analysis h2, .tb-analysis h3, .tb-analysis p { margin: 0; }
.tb-analysis h1 { max-width: 18ch; font-size: clamp(36px, 5.3vw, 68px); line-height: 1.07; font-weight: 600; letter-spacing: -.045em; text-wrap: balance; overflow-wrap: anywhere; }
.tb-analysis h1:focus { outline: none; }
.tb-analysis h2 { font-size: 17px; line-height: 1.25; font-weight: 600; letter-spacing: -.015em; }
.tb-analysis h3 { font-size: 12px; font-weight: 600; }
.tb-analysis a { color: var(--tb-analysis-link); text-decoration: none; }
.tb-analysis a:hover { text-decoration: underline; }
.tb-analysis a:focus-visible, .tb-analysis button:focus-visible, .tb-analysis summary:focus-visible { outline: 2px solid var(--tb-analysis-link); outline-offset: 3px; }
.tb-analysis button { min-height: 36px; color: var(--tb-analysis-text); background: var(--tb-analysis-control); border: 1px solid var(--tb-analysis-line); border-radius: 999px; padding: 7px 14px; cursor: pointer; font: inherit; font-size: 12px; }
.tb-analysis button:hover { background: var(--tb-analysis-control-hover); }
.tb-analysis-heading { display: grid; gap: 18px; padding: 48px 0 12px; }
.tb-analysis-eyebrow { color: var(--tb-analysis-muted); font-size: 12px; font-weight: 600; letter-spacing: .13em; text-transform: uppercase; }
.tb-analysis-eyebrow span { color: var(--tb-analysis-period); margin: 0 6px; }
.tb-analysis-period { color: var(--tb-analysis-period); }
.tb-analysis-deck { max-width: 680px; color: var(--tb-analysis-deck); font-size: clamp(17px, 2vw, 22px); line-height: 1.4; letter-spacing: -.02em; }
.tb-analysis-overview-meta { display: flex; flex-wrap: wrap; gap: 8px; margin-top: 8px; }
.tb-analysis-overview-meta span { padding: 8px 12px; border: 1px solid var(--tb-analysis-line); border-radius: 999px; color: var(--tb-analysis-muted); font-size: 12px; }
.tb-analysis-list { display: grid; grid-template-columns: repeat(auto-fit, minmax(min(320px, 100%), 1fr)); gap: 18px; }
.tb-analysis-card, .tb-analysis-focus { --tb-analysis-tone: var(--tb-analysis-info); }
.tb-analysis-card { position: relative; display: grid; align-content: start; gap: 18px; padding: 28px; border: 1px solid var(--tb-analysis-line); border-radius: 20px; background: var(--tb-analysis-card); overflow: hidden; }
.tb-analysis-card::before, .tb-analysis-action::before { position: absolute; inset: 0 auto auto 0; width: 100%; height: 3px; background: linear-gradient(90deg, var(--tb-analysis-tone), var(--tb-analysis-grad-mid) 55%, var(--tb-analysis-grad-end)); content: ''; }
.tb-analysis-card header, .tb-analysis-toolbar, .tb-analysis-siblings { display: flex; justify-content: space-between; align-items: center; gap: 12px; }
.tb-analysis-card header { color: var(--tb-analysis-muted); font-size: 12px; }
.tb-analysis-card h2 { font-size: clamp(23px, 3vw, 32px); line-height: 1.14; }
.tb-analysis-card h2 a { color: var(--tb-analysis-text); }
.tb-analysis-card-summary { color: var(--tb-analysis-deck); font-size: 15px; line-height: 1.5; }
.tb-analysis-card-action { display: grid; gap: 7px; padding-top: 18px; border-top: 1px solid var(--tb-analysis-line); }
.tb-analysis-card h3, .tb-analysis-kind { color: var(--tb-analysis-muted); font-size: 11px; text-transform: uppercase; letter-spacing: .1em; }
.tb-analysis-card-action p { color: var(--tb-analysis-deck); }
.tb-analysis-open { display: inline-flex; align-items: center; gap: 10px; margin-top: 6px; width: fit-content; font-weight: 600; }
.tb-analysis-open span { font-size: 18px; }
.tb-analysis-critical { --tb-analysis-tone: var(--tb-analysis-red); }
.tb-analysis-warning { --tb-analysis-tone: var(--tb-analysis-orange); }
.tb-analysis-notice { --tb-analysis-tone: var(--tb-analysis-yellow); }
.tb-analysis-info, .tb-analysis-neutral { --tb-analysis-tone: var(--tb-analysis-info); }
.tb-analysis-status { display: inline-flex; align-items: center; gap: 8px; padding: 7px 11px; border: 1px solid color-mix(in srgb, var(--tb-analysis-tone) 40%, transparent); border-radius: 999px; background: color-mix(in srgb, var(--tb-analysis-tone) 12%, transparent); color: var(--tb-analysis-text); font-size: 12px; font-weight: 600; }
.tb-analysis-status::before { width: 8px; height: 8px; flex: none; border-radius: 50%; background: var(--tb-analysis-tone); content: ''; }
.tb-analysis-meta { color: var(--tb-analysis-muted); font-size: 12px; }
.tb-analysis-toolbar { padding: 4px 0; font-size: 13px; }
.tb-analysis-copy-error { color: var(--tb-analysis-copy-error); font-size: 12px; }
.tb-analysis-focus { display: grid; gap: 32px; }
.tb-analysis-hero { display: grid; gap: 20px; padding: 34px 0 8px; }
.tb-analysis-hero-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; }
.tb-analysis-hero .tb-analysis-eyebrow { margin-top: 12px; }
.tb-analysis-attention { max-width: 760px; color: var(--tb-analysis-deck); font-size: clamp(17px, 2vw, 22px); line-height: 1.45; letter-spacing: -.02em; }
.tb-analysis-priority { display: grid; grid-template-columns: minmax(0, .9fr) minmax(0, 1.1fr); gap: 16px; align-items: stretch; }
.tb-analysis-focus section { display: grid; align-content: start; gap: 14px; min-width: 0; }
.tb-analysis-priority > section { padding: 25px; border: 1px solid var(--tb-analysis-line); border-radius: 20px; background: var(--tb-analysis-card); }
.tb-analysis-action { position: relative; overflow: hidden; }
.tb-analysis-action p { font-size: 17px; line-height: 1.5; }
.tb-analysis-priority > .tb-analysis-key-evidence { border-color: color-mix(in srgb, var(--tb-analysis-tone) 32%, var(--tb-analysis-line)); }
.tb-analysis-detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.tb-analysis-detail-grid > section, .tb-analysis-lower { padding: 25px; border: 1px solid var(--tb-analysis-line); border-radius: 18px; background: var(--tb-analysis-card); }
.tb-analysis-detail-grid > section > h2, .tb-analysis-lower > h2 { padding-bottom: 14px; border-bottom: 1px solid var(--tb-analysis-line); }
.tb-analysis-subjects, .tb-analysis-evidence-list, .tb-analysis-focus ol, .tb-analysis-focus ul { display: grid; gap: 10px; margin: 0; padding-left: 20px; }
.tb-analysis-subjects li, .tb-analysis-focus li { overflow-wrap: anywhere; }
.tb-analysis-subjects li span, .tb-analysis-focus ol li span, .tb-analysis-focus ul li span { margin-left: 8px; color: var(--tb-analysis-muted); }
.tb-analysis-evidence-list { list-style: none; padding-left: 0 !important; }
.tb-analysis-evidence-list li + li { padding-top: 15px; border-top: 1px solid var(--tb-analysis-line); }
.tb-analysis-evidence { padding: 2px 0 10px; }
.tb-analysis-evidence > div { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px; }
.tb-analysis-evidence > div > a { font-weight: 600; }
.tb-analysis-evidence p { margin-top: 5px; color: var(--tb-analysis-deck); }
.tb-analysis-evidence .tb-analysis-meta { color: var(--tb-analysis-muted); font-size: 11px; line-height: 1.45; }
.tb-analysis-kind { padding: 4px 7px; border-radius: 5px; background: var(--tb-analysis-chip); }
.tb-analysis-unavailable { padding: 3px 7px; border-radius: 999px; background: var(--tb-analysis-unavailable-bg); color: var(--tb-analysis-unavailable-text); font-size: 11px; }
.tb-analysis-lower summary { cursor: pointer; font-weight: 600; }
.tb-analysis-lower summary + ul { margin-top: 18px; }
.tb-analysis-siblings { padding: 0 0 20px; font-size: 13px; }
.tb-analysis-siblings a:last-child { margin-left: auto; text-align: right; }
@media (max-width: 720px) {
  #tb-shell-panel:has(.tb-analysis) { padding: 24px 16px 48px; }
  .tb-analysis { gap: 22px; }
  .tb-analysis-heading { padding-top: 24px; }
  .tb-analysis-priority { grid-template-columns: 1fr; }
  .tb-analysis-detail-grid { grid-template-columns: 1fr; }
  .tb-analysis-siblings { display: grid; grid-template-columns: 1fr; }
  .tb-analysis-siblings a:last-child { margin-left: 0; text-align: left; }
}
@media (max-width: 480px) {
  .tb-analysis-toolbar { align-items: flex-start; flex-direction: column; }
  .tb-analysis-card, .tb-analysis-priority > section, .tb-analysis-detail-grid > section, .tb-analysis-lower { padding: 20px; }
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
