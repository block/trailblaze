// Stylesheet for the interactive run report, emitted into the <head> of every exported report
// document by buildMultiReportHtml (run-report-html.ts).

// The design-token block (light + dark variable sets) is exported on its own so sibling report
// stylesheets (perf-css.ts, the performance-analysis report) share the exact same palette without
// carrying this report's component rules - one source of truth, no drift.
export const RUN_REPORT_TOKENS_CSS = `
:root {
  color-scheme: light;
  --neutral-1: #f7f6f2; --neutral-2: #ffffff; --neutral-3: #f2f1eb; --neutral-4: #ebe9e1;
  --neutral-5: #dfddd4; --neutral-6: #cfccc2; --neutral-7: #bbb7ab; --neutral-8: #99958b;
  --neutral-9: #76736b; --neutral-10: #5f5d57; --neutral-11: #3a3a36; --neutral-12: #111111;
  --accent-1: #f7faff; --accent-2: #f0f6ff; --accent-3: #e5efff; --accent-4: #d4e5ff;
  --accent-5: #bdd7ff; --accent-6: #9fc4fb; --accent-7: #79abf5; --accent-8: #4b8bea;
  --accent-9: #1f6feb; --accent-10: #1b63d2; --accent-11: #1857b6; --accent-12: #0d2f66;
  --cyan-3: #e6f7ff; --cyan-9: #0aa7d9; --cyan-11: #087ca3;
  --violet-3: #f4efff; --violet-9: #8250df; --violet-11: #6f42c1;
  --error-3: #fff0f0; --error-9: #cf222e; --error-11: #cf222e;
  --success-3: #eef8f1; --success-9: #1a7f37; --success-11: #1a7f37;
  --warning-3: #fff7df; --warning-9: #d8a018; --warning-11: #9a6700;
  --info-3: #eaf3ff; --info-9: #1f6feb; --info-11: #1857b6;
  --bg: var(--neutral-1); --bg2: var(--neutral-2); --bg3: var(--neutral-3); --raised: var(--neutral-2);
  --header: var(--neutral-1); --button-hover: var(--neutral-4);
  --line: var(--neutral-5); --line2: var(--neutral-6);
  --txt: var(--neutral-12); --sub: var(--neutral-11); --sub2: var(--neutral-12);
  --pass: var(--success-11); --fail: var(--error-11); --run: var(--accent-11); --purple: var(--violet-11); --amber: var(--warning-11); --ai: var(--violet-9);
  --event: var(--cyan-11); --focus: var(--accent-9); --player-line: var(--neutral-6);
  --danger-surface: var(--error-3); --danger-border: var(--error-9); --danger-text: var(--error-11);
  --warning-surface: var(--warning-3); --warning-border: var(--warning-9); --warning-text: var(--warning-11);
  --success-surface: var(--success-3); --success-border: var(--success-9); --success-text: var(--success-11);
  --accent-surface: var(--accent-3); --violet-surface: var(--violet-3); --code-surface: var(--neutral-2); --code-text: var(--neutral-12);
  --r-sm: 6px; --r-md: 10px; --r-lg: 14px;
  --space-1: 4px; --space-2: 8px; --space-3: 12px; --space-4: 16px; --space-5: 24px; --space-6: 32px;
  --type-micro: 9px; --type-caption: 11px; --type-small: 12px; --type-body: 14px; --type-title: 24px;
  --page-x: var(--space-6); --page-y: var(--space-5); --content-wide: 1120px; --content-reading: 720px; --control-height: 32px;
  --shadow-raised: 0 16px 40px color-mix(in srgb,var(--accent-12) 12%,transparent), 0 2px 8px color-mix(in srgb,var(--accent-12) 9%,transparent);
}
[data-theme="dark"] {
  color-scheme: dark;
  --neutral-1: #121313; --neutral-2: #19191a; --neutral-3: #212224; --neutral-4: #282a2d;
  --neutral-5: #303236; --neutral-6: #393c41; --neutral-7: #454950; --neutral-8: #5b6169;
  --neutral-9: #7a818b; --neutral-10: #8c939d; --neutral-11: #b3b8be; --neutral-12: #e5e8ec;
  --accent-1: #111315; --accent-2: #171a1e; --accent-3: #1c232d; --accent-4: #202b3a;
  --accent-5: #243348; --accent-6: #293c59; --accent-7: #324a6d; --accent-8: #43618e;
  --accent-9: #5a81bb; --accent-10: #6e94cb; --accent-11: #a0b9de; --accent-12: #dae9ff;
  --cyan-3: #1b2428; --cyan-9: #86bdd6; --cyan-11: #9dbdcc;
  --violet-3: #21202f; --violet-9: #6457ac; --violet-11: #b4b0e8;
  --error-3: #2d1d1c; --error-9: #c56c65; --error-11: #e0a7a1;
  --success-3: #1a261a; --success-9: #84cc86; --success-11: #9bc49b;
  --warning-3: #262219; --warning-9: #ceb47e; --warning-11: #c5b696;
  --info-3: #1b2329; --info-9: #7aabce; --info-11: #9fbcd1;
  --bg: var(--neutral-1); --bg2: var(--neutral-2); --bg3: var(--neutral-3); --raised: var(--neutral-2);
  --header: var(--neutral-1); --button-hover: var(--neutral-4);
  --line: var(--neutral-4); --line2: var(--neutral-6);
  --txt: var(--neutral-12); --sub: var(--neutral-11); --sub2: var(--neutral-12);
  --pass: #39d16d; --fail: #ff626d; --run: #6aa6ff; --purple: #b08cff; --amber: #f2b84b; --ai: #c29aff;
  --event: #5ed3ff; --focus: #91bdff; --player-line: var(--neutral-6);
  --danger-surface: var(--error-3); --danger-border: #ff626d; --danger-text: #ff969d;
  --warning-surface: var(--warning-3); --warning-border: #f2b84b; --warning-text: #ffd27a;
  --success-surface: var(--success-3); --success-border: #39d16d; --success-text: #76e99a;
  --accent-surface: var(--accent-3); --violet-surface: var(--violet-3); --code-surface: var(--neutral-1); --code-text: var(--neutral-12);
  --shadow-raised: 0 18px 48px color-mix(in srgb,var(--accent-1) 76%,transparent), 0 2px 8px color-mix(in srgb,var(--accent-1) 82%,transparent);
}`;

export const RUN_REPORT_CSS = `${RUN_REPORT_TOKENS_CSS}
* { box-sizing: border-box; scrollbar-width: thin; scrollbar-color: rgba(144,152,164,.32) transparent; }
*::-webkit-scrollbar { width: 8px; height: 8px; }
*::-webkit-scrollbar-track { background: transparent; }
*::-webkit-scrollbar-thumb { min-height: 36px; border: 2px solid transparent; border-radius: 99px; background: rgba(144,152,164,.32); background-clip: padding-box; }
*::-webkit-scrollbar-thumb:hover { background-color: rgba(144,152,164,.52); }
*::-webkit-scrollbar-corner { background: transparent; }
html, body { margin: 0; height: 100%; overflow: hidden; }
body { background: var(--bg); color: var(--txt); font: var(--type-body)/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; text-rendering: optimizeLegibility; transition: background-color 140ms ease-out,color 140ms ease-out; }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
#app { display: flex; flex-direction: column; height: 100%; height: 100dvh; min-height: 0; overflow: hidden; }
#tb-boot { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--space-3); color: var(--sub); }
#tb-boot .tb-boot-spinner { width: 28px; height: 28px; border-radius: 50%; border: 3px solid var(--line2); border-top-color: var(--run); animation: tbBootSpin .8s linear infinite; }
#tb-boot .tb-boot-title { font-size: var(--type-body); font-weight: 650; color: var(--txt); }
#tb-boot .tb-boot-note { font-size: var(--type-small); }
@keyframes tbBootSpin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { #tb-boot .tb-boot-spinner { animation: none; } }
@keyframes reportPageForward { from { opacity: .35; transform: translateX(18px); } to { opacity: 1; transform: translateX(0); } }
@keyframes reportPageBack { from { opacity: .35; transform: translateX(-18px); } to { opacity: 1; transform: translateX(0); } }
#app.page-enter-forward { animation: reportPageForward 220ms cubic-bezier(.16,1,.3,1) both; }
#app.page-enter-back { animation: reportPageBack 220ms cubic-bezier(.16,1,.3,1) both; }
header { flex-shrink: 0; padding: var(--page-y) var(--page-x) 0; border-bottom: 1px solid var(--line); background: var(--header); }
.title-row { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; max-width: var(--content-wide); }
h1 { font-size: var(--type-title); line-height: 1.2; letter-spacing: -.018em; margin: 0; font-weight: 720; }
.badge { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; padding: 3px 9px; border-radius: 99px; }
.badge.passed, .badge.success { background: var(--success-surface); color: var(--success-text); }
.badge.failed, .badge.error { background: var(--danger-surface); color: var(--danger-text); }
.badge.running, .badge.cancelled, .badge.unknown { background: var(--accent-surface); color: var(--run); }
.meta { display: flex; flex-wrap: wrap; gap: var(--space-3) var(--space-5); margin-top: var(--space-4); }
.meta .k { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .1em; color: var(--sub); }
.meta .v { font-size: var(--type-small); font-weight: 500; margin-top: 1px; }
nav { display: flex; gap: var(--space-1); margin-top: 20px; margin-left: calc(-1 * var(--space-3)); overflow-x: auto; scrollbar-width: thin; }
nav button { background: none; border: none; color: var(--sub); font-size: 13px; font-weight: 650; padding: 10px 12px; cursor: pointer; border-bottom: 2px solid transparent; white-space: nowrap; transition: color 120ms ease-out, background-color 120ms ease-out, border-color 120ms ease-out; }
nav button:hover { color: var(--txt); background: var(--bg3); border-radius: var(--r-sm) var(--r-sm) 0 0; }
nav button.active { color: var(--txt); border-bottom-color: var(--run); border-radius: var(--r-sm) var(--r-sm) 0 0; background: var(--accent-surface); }
main { flex: 1; min-height: 0; overflow: auto; padding: var(--page-y) var(--page-x) var(--space-6); }
footer { flex-shrink: 0; padding: var(--space-3) var(--page-x); border-top: 1px solid var(--line); color: var(--sub); font-size: var(--type-caption); display: flex; gap: var(--space-2); align-items: center; }
.indexfooter, .detailfooter { min-height: 59px; box-sizing: border-box; justify-content: space-between; }
.detailfooter { min-width: 0; gap: var(--space-4); }
.detailfootermeta { min-width: 0; flex: 1; display: flex; align-items: center; gap: var(--space-4); overflow-x: auto; scrollbar-width: none; }
.detailfootermeta::-webkit-scrollbar { display: none; }
.detailfooteritem { display: grid; gap: 1px; white-space: nowrap; }
.detailfooteritem.runon { margin-left: auto; text-align: right; }
.detailfooteritem .k { color: var(--neutral-10); font-size: var(--type-micro); font-weight: 650; letter-spacing: .09em; line-height: 1.2; text-transform: uppercase; }
.detailfooteritem .v { color: var(--sub); font-size: var(--type-caption); font-weight: 600; line-height: 1.25; }
.indexshell { width: 100%; max-width: var(--content-wide); margin-inline: auto; }
.indexfootercontent { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
.indexmetrics { display: flex; align-items: center; gap: var(--space-5); margin-left: auto; }
.indexrundate { text-align: right; }
[data-theme="light"] nav button:hover, [data-theme="light"] .idxrow:hover, [data-theme="light"] .step:hover, [data-theme="light"] .grphdr:hover { background: var(--neutral-3); }
[data-theme="light"] nav button.active { background: var(--accent-surface); }
[data-theme="light"] .idxattempts { background: var(--neutral-2); }
[data-theme="light"] .idxattemptrow:hover { background: var(--neutral-3); }
[data-theme="light"] .quietlink, [data-theme="light"] .yamllink { background: var(--neutral-2); }
[data-theme="light"] .exportmenuitem:hover, [data-theme="light"] .idxsortoption:hover { background: var(--neutral-3); }
.tl { display: grid; grid-template-columns: 1fr; gap: 20px; align-items: start; }
.eyebrow { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .08em; color: var(--sub); margin-bottom: var(--space-2); }
.viewpage { width: 100%; max-width: var(--content-wide); }
.viewhead { display: flex; align-items: baseline; gap: var(--space-2); min-height: 24px; margin: 0 0 var(--space-3); }
.viewtitle { margin: 0; color: var(--txt); font-size: var(--type-small); font-weight: 720; line-height: 1.35; }
.viewmeta { color: var(--sub); font-size: var(--type-micro); font-weight: 550; letter-spacing: .075em; text-transform: uppercase; }
.viewbody { min-width: 0; }
.timelinephases { display: grid; gap: 18px; }
.tlphasehead { position: sticky; top: -1px; z-index: 6; width: 100%; min-height: 40px; display: flex; align-items: center; gap: 9px; margin: 0 0 7px; padding: 0 8px; border-bottom: 1px solid var(--line); background: color-mix(in srgb,var(--bg) 94%,transparent); backdrop-filter: blur(10px); }
.phasecontrol { min-width: 0; min-height: 40px; flex: 1; display: flex; align-items: center; gap: 9px; padding: 7px 0; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.phasecontrol:hover { color: var(--txt); }
.phasecontrol:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.tlphasehead .name { color: var(--txt); font-size: 12px; font-weight: 750; letter-spacing: .055em; text-transform: uppercase; }
.tlphasehead .desc { color: var(--sub); font-size: 10.5px; }
.tlphasehead .phasechev { width: 8px; height: 8px; margin-left: auto; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out; }
.phasecontrol[aria-expanded="false"] .phasechev { transform: rotate(-45deg); }
.tlphase.trailhead .tlphasehead .name { color: var(--purple); }
.tlphasebody[hidden], .stepgroupbody[hidden] { display: none; }
.timelinecontrols { position: sticky; top: 0; z-index: 7; display: flex; justify-content: flex-start; margin: 0 0 6px; padding: 0 0 8px; background: color-mix(in srgb,var(--bg) 94%,transparent); backdrop-filter: blur(10px); }
/* With the sticky stream chooser above, phase heads stick just below it instead of underneath. */
.timeline-list:has(.timelinecontrols) .tlphasehead { top: 39px; }
.selfhealpanel { margin-bottom: 14px; overflow: hidden; border: 1px solid color-mix(in srgb,var(--warning-border) 60%,var(--line2)); border-radius: var(--r-lg); background: var(--warning-surface); }
.selfhealhead { display: flex; align-items: center; gap: 10px; padding: 11px 13px; border-bottom: 1px solid color-mix(in srgb,var(--warning-border) 46%,var(--line)); }
.selfhealicon { width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; border-radius: 99px; background: var(--warning-border); color: var(--neutral-12); font-size: 13px; font-weight: 850; }
.selfhealtitle { color: var(--warning-text); font-size: 13px; font-weight: 720; }
.selfhealcontext { margin-left: auto; color: var(--sub2); font-size: 10.5px; }
.selfhealbody { display: grid; grid-template-columns: minmax(190px,.42fr) minmax(0,1fr); }
.selfhealfield { min-width: 0; padding: 10px 13px 11px; }
.selfhealfield + .selfhealfield { border-left: 1px solid color-mix(in srgb,var(--warning-border) 40%,var(--line)); }
.selfhealfield .k { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.selfhealtoolname { display: block; margin-top: 4px; color: var(--warning-text); font-size: 12.5px; font-weight: 650; overflow-wrap: anywhere; }
.selfhealmessage { margin-top: 4px; color: var(--txt); font-size: 12.5px; line-height: 1.45; overflow-wrap: anywhere; }
.yamllink { margin-top: 8px; width: fit-content; min-height: 28px; display: inline-flex; align-items: center; border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 5px 8px; background: var(--bg2); color: var(--sub2); font: inherit; font-size: 10.5px; font-weight: 700; cursor: pointer; }
.yamllink:hover { color: var(--txt); border-color: var(--run); }
.failurepanel { margin-bottom: 14px; overflow: hidden; border: 1px solid color-mix(in srgb,var(--danger-border) 64%,var(--line2)); border-radius: var(--r-lg); background: var(--danger-surface); }
.failurehead { display: flex; align-items: center; gap: 10px; padding: 11px 13px; border-bottom: 1px solid color-mix(in srgb,var(--danger-border) 48%,var(--line)); }
.failureicon { width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; border-radius: 99px; background: var(--danger-border); color: var(--neutral-12); font-size: 13px; font-weight: 850; }
.failuretitle { color: var(--danger-text); font-size: 13px; font-weight: 720; }
.failurecontext { margin-left: auto; color: var(--sub2); font-size: 10.5px; }
.failuretool { display: grid; grid-template-columns: 112px minmax(0,1fr); gap: 12px; align-items: center; padding: 10px 13px; border-bottom: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failuretool .k { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.failuretoolvalue { min-width: 0; display: flex; align-items: center; gap: 8px; }
.failuretoolname { color: var(--danger-text); font-size: 12.5px; font-weight: 650; }
.failuretoolargs { color: var(--sub2); font-size: 10.5px; }
.failuretool .yamllink { margin: 0 0 0 auto; flex-shrink: 0; }
.failurebody { display: grid; grid-template-columns: minmax(190px,.42fr) minmax(0,1fr); }
.failurefield { min-width: 0; padding: 10px 13px 11px; }
.failurefield + .failurefield { border-left: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failurefield .k, .failurestack summary { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.failuretype { display: block; margin-top: 4px; color: var(--danger-text); font-size: 11.5px; overflow-wrap: anywhere; }
.failuremessage { margin-top: 4px; color: var(--txt); font-size: 12.5px; line-height: 1.45; overflow-wrap: anywhere; }
.failurestack { border-top: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failurestack summary { display: flex; align-items: center; gap: 8px; padding: 9px 13px; cursor: pointer; list-style: none; }
.failurestack summary::-webkit-details-marker { display: none; }
.failurestack summary::before { content: '›'; color: var(--sub2); font-size: 17px; line-height: 1; transform: rotate(90deg); transition: transform 120ms ease-out; }
.failurestack:not([open]) summary::before { transform: rotate(0deg); }
.failurestack .frames { margin-left: auto; color: var(--sub); font-size: 10px; font-weight: 500; font-variant-numeric: tabular-nums; letter-spacing: 0; text-transform: none; }
.failurestack pre { max-height: 210px; margin: 0; border: 0; border-top: 1px solid color-mix(in srgb,var(--danger-border) 32%,var(--line)); border-radius: 0; background: var(--code-surface); color: var(--code-text); }
.tlphase.trailhead .steps { border-color: color-mix(in srgb,var(--violet-9) 52%,var(--line2)); }
.steps { border: 1px solid var(--line); border-radius: var(--r-lg); overflow: hidden; background: var(--bg2); box-shadow: inset 0 1px rgba(255,255,255,.025); }
.stepgroup { position: relative; }
.stepgroup.failed { background: var(--danger-surface); }
.stepgroup.failed .grphdr { background: color-mix(in srgb,var(--danger-surface) 80%,var(--bg3)); }
.stepgroup.failed .grphdr .chip { color: var(--danger-text); background: color-mix(in srgb,var(--danger-border) 26%,var(--danger-surface)); }
.stepgroup.failed .step { background-color: transparent; }
.stepgroup.selfhealed { background: var(--warning-surface); }
.stepgroup.selfhealed .grphdr { background: color-mix(in srgb,var(--warning-surface) 80%,var(--bg3)); }
.stepgroup.selfhealed .grphdr .chip { color: var(--warning-text); background: color-mix(in srgb,var(--warning-border) 24%,var(--warning-surface)); }
.stepgroup.selfhealed .step { background-color: var(--bg2); }
.grphdr { width: 100%; padding: 12px 14px 11px; background: var(--bg3); color: inherit; border: 0; border-top: 1px solid var(--line2); display: grid; grid-template-columns: auto auto auto 1fr auto; align-items: center; gap: 8px; font: inherit; text-align: left; cursor: pointer; }
.grphdr:hover { background: color-mix(in srgb,var(--bg3) 84%,white); }
.grphdr.sel { background: var(--accent-surface); }
.grphdr:focus-visible { position: relative; z-index: 1; outline: 2px solid var(--focus); outline-offset: -2px; }
.steps > .grphdr:first-child, .stepgroup:first-child > .grphdr { border-top: none; }
.grphdr .chip, .galchip { font-size: 9.5px; font-weight: 700; letter-spacing: .06em; color: var(--purple); background: var(--violet-surface); border-radius: 5px; padding: 2px 7px; white-space: nowrap; flex-shrink: 0; }
.grphdr.trailhead .chip { color: var(--purple); background: var(--violet-surface); }
.grphdr .dot { width: 8px; height: 8px; border-radius: 99px; }
.grphdr .lbl { grid-column: 1 / -1; display: block; font-size: 14px; font-weight: 650; margin-top: 4px; line-height: 1.4; }
.grphdr .groupchev { grid-column: 5; grid-row: 1; width: 8px; height: 8px; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out; }
.grphdr[aria-expanded="false"] .groupchev { transform: rotate(-45deg); }
.step { display: flex; gap: 10px; padding: 10px 14px; cursor: pointer; border-top: 1px solid var(--line); transition: background-color 120ms ease-out, box-shadow 120ms ease-out; }
.step.child { padding-left: 22px; }
.step:hover { background: var(--bg3); }
.step.sel { background: var(--accent-surface); }
.stepgroup.failed .step.sel { background: color-mix(in srgb,var(--danger-border) 18%,var(--danger-surface)); }
.stepgroup.selfhealed .grphdr.sel, .stepgroup.selfhealed .step.selfheal { background: color-mix(in srgb,var(--warning-border) 18%,var(--warning-surface)); }
.stepgroup.selfhealed .step.sel:not(.selfheal) { background: var(--accent-surface); }
.step .num { font-size: 11px; color: var(--sub); width: 20px; text-align: right; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.step .ic { width: 14px; height: 14px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 2px; font-size: 14px; font-weight: 800; line-height: 1; }
.step .ic.dot::before { content: ''; width: 9px; height: 9px; border-radius: 99px; background: var(--icon-color); }
.step .ic.tap { font-size: 13px; }
.step .ic.verify { color: var(--pass); }
.step .ic.failure { color: var(--fail); }
.step .lbl { font-size: 13px; font-weight: 560; }
.step .tl-tool { font-size: 11px; color: var(--sub); margin-top: 2px; word-break: break-word; }
.step .note { font-size: 11.5px; color: var(--sub2); margin-top: 3px; line-height: 1.4; }
.kids { margin-top: 6px; border-left: 1px solid var(--line2); padding-left: 10px; }
.kids div { font-size: 11.5px; margin-top: 3px; }
.kids .kt { color: var(--sub); }
.timeline-list { grid-row: 2; }
.preview { position: static; grid-row: 1; min-width: 0; display: flex; justify-content: center; }
.deviceplayer { width: fit-content; max-width: 100%; display: grid; grid-template-rows: minmax(0,1fr) auto; overflow: hidden; border: 2px solid var(--player-line); border-radius: 22px; background: var(--raised); box-shadow: var(--shadow-raised); }
.deviceplayer.empty { width: min(360px,100%); grid-template-rows: auto auto; }
.shotwrap { width: fit-content; max-width: 100%; margin: 0; }
.shot { max-width: 100%; max-height: calc(100vh - 334px); background: #000; border: 0; display: block; cursor: zoom-in; }
.tlvframe { max-width: 100%; height: calc(100vh - 386px); min-height: 240px; aspect-ratio: 1/2; background-color: #000; background-repeat: no-repeat; display: block; }
.noshot { width: 100%; aspect-ratio: 1/2; border: 0; display: flex; align-items: center; justify-content: center; color: var(--sub); font-size: 12px; text-align: center; padding: 20px; }
.pvctl { display: grid; grid-template-columns: repeat(3,minmax(0,1fr)); gap: 0; margin: 0; border-top: 2px solid var(--player-line); overflow: hidden; }
.pvctl button.btn { width: 100%; min-width: 0; min-height: 42px; border: 0; border-left: 2px solid var(--player-line); border-radius: 0; background: transparent; }
.pvctl button.btn:first-child { border-left: 0; }
.pvctl button.btn.play { min-width: 0; border-left-color: var(--player-line); background: rgba(106,166,255,.08); }
.transporticon { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; color: currentColor; }
.transporticon.direction::before { content: ''; width: 12px; height: 12px; box-sizing: border-box; border-bottom: 4px solid currentColor; border-left: 4px solid currentColor; border-radius: 2px; transform: rotate(45deg); }
.pvctl #next .transporticon.direction::before { transform: rotate(225deg); }
.transporticon.playicon { margin-left: 2px; }
.transporticon.pauseicon { gap: 5px; }
.transporticon.pauseicon::before, .transporticon.pauseicon::after { content: ''; width: 5px; height: 20px; border-radius: 2px; background: currentColor; }
button.btn { min-height: 34px; background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: var(--r-sm); padding: 6px 11px; font-size: 12.5px; font-weight: 650; cursor: pointer; transition: color 120ms ease-out, background-color 120ms ease-out, border-color 120ms ease-out, transform 100ms ease-out; }
button.btn:disabled { opacity: .4; cursor: default; }
button.btn:not(:disabled):hover { border-color: var(--run); background: var(--button-hover); }
.pvctl button.btn:not(:disabled):hover { border-left-color: var(--player-line); }
button.btn:not(:disabled):active { transform: translateY(1px); }
button.btn.play { border-color: var(--run); background: var(--accent-surface); color: var(--run); min-width: 84px; }
.llm { display: grid; grid-template-columns: 1fr; gap: 20px; align-items: start; }
.card { border: 1px solid var(--line); border-radius: 10px; background: var(--bg2); padding: 10px 13px; }
.totals { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 6px; }
.totals .n { font-size: 13px; font-weight: 700; font-variant-numeric: tabular-nums; }
.totals .t { font-size: 10.5px; color: var(--sub); }
.callrow { padding: 9px 11px; margin-top: 5px; cursor: pointer; border: 1px solid transparent; border-radius: var(--r-md); background: var(--bg2); transition: background-color 120ms ease-out, border-color 120ms ease-out; }
.callrow:hover { background: var(--bg3); border-color: var(--line2); }
.callrow.sel { background: var(--bg3); border-color: rgba(57,209,109,.45); }
.callrow .d { font-size: 11.5px; font-weight: 600; }
.callrow .m { font-size: 10.5px; color: var(--sub); margin-top: 3px; }
.resp { border: 1px solid rgba(181,140,255,.3); background: rgba(181,140,255,.07); border-radius: 10px; padding: 11px 13px; margin-top: 10px; }
.resp .h { font-size: 10.5px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; color: var(--ai); margin-bottom: 8px; }
.resp .reason { font-size: 12.5px; line-height: 1.55; margin-bottom: 6px; }
.resp .tool { font-size: 12px; font-weight: 700; margin: 4px 0; }
pre { margin: 0; font-size: 11px; line-height: 1.5; color: var(--sub2); white-space: pre-wrap; word-break: break-word; max-height: 260px; overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: 8px; padding: 8px 10px; }
.rows { display: grid; max-width: var(--content-reading); overflow: hidden; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg2); }
.rows .r { display: grid; grid-template-columns: 160px minmax(0,1fr); gap: var(--space-4); padding: var(--space-3) var(--space-4); border-top: 1px solid var(--line); font-size: var(--type-small); }
.rows .r:first-child { border-top: 0; }
.rows .r .k { color: var(--sub); font-size: var(--type-caption); }
.rows .r .v { overflow-wrap: anywhere; }
.infosection + .infosection { margin-top: var(--space-5); }
.cmd { display: flex; gap: var(--space-2); align-items: flex-start; margin-top: var(--space-2); max-width: var(--content-reading); }
.cmd pre { flex: 1; }
.zoom { position: fixed; inset: 0; background: rgba(2,6,12,.9); display: flex; align-items: center; justify-content: center; gap: 32px; cursor: zoom-out; z-index: 99; backdrop-filter: blur(4px); }
.zoom img { max-width: 92vw; max-height: 92vh; border-radius: 10px; border: 1px solid var(--line2); }
.zoomnav { position: fixed; top: 50%; width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--line2); border-radius: 9px; background: color-mix(in srgb,var(--raised) 90%,transparent); color: var(--txt); font-family: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, sans-serif; font-size: 21px; font-weight: 600; line-height: 1; cursor: pointer; transform: translateY(-50%); box-shadow: var(--shadow-raised); }
.zoomnav.prev { left: 24px; }
.zoomnav.next { right: 24px; }
.zoomnav:hover { border-color: var(--run); background: rgba(34,40,50,.96); }
.zoomnav:disabled { opacity: 0; pointer-events: none; }
/* Step-label column beside the zoomed screenshot (centered two-column layout). The scrim is dark
   in both themes, so the column's text colors are fixed light rather than theme vars. */
.zoom.haslist img { max-width: calc(92vw - 320px); }
.zoomsteps { position: relative; width: 280px; max-height: 88vh; overflow-y: auto; overscroll-behavior: contain; display: flex; flex-direction: column; gap: 2px; cursor: default; }
.zoomstep { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; flex-shrink: 0; padding: 10px 12px; border: 0; border-radius: 10px; background: transparent; font: inherit; text-align: left; white-space: normal; opacity: .2; cursor: pointer; transition: opacity 120ms ease-out; }
.zoomstep:hover { opacity: .6; }
.zoomstep.cur { opacity: 1; cursor: default; }
.zoomstep:focus-visible { outline: 2px solid #6aa6ff; outline-offset: -2px; }
.zoomstepchip { font-size: 9.5px; font-weight: 700; letter-spacing: .06em; color: #cdb8ff; background: rgba(133,102,255,.22); border-radius: 5px; padding: 2px 7px; white-space: nowrap; }
.zoomsteplabel { color: #fff; font-size: 12.5px; font-weight: 600; line-height: 1.4; word-break: break-word; }
.zoomsteptool { color: rgba(255,255,255,.65); font-size: 11px; line-height: 1.4; word-break: break-word; }
/* Narrow viewports: the fixed-width column would crush the screenshot, so drop it and give the
   image the full width back (arrow keys / nav buttons still page through the gallery). */
@media (max-width: 640px) { .zoomsteps { display: none; } .zoom.haslist img { max-width: 92vw; } }
.empty { color: var(--sub); font-size: 13px; padding: 30px; text-align: center; }
.indexheader { padding-bottom: var(--page-y); }
.indexheadrow { justify-content: space-between; }
.indexheadactions, .detailactions { display: flex; align-items: center; gap: var(--space-2); }
.themetoggle { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; padding: 0; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--sub); cursor: pointer; }
.themetoggle:hover { color: var(--txt); background: var(--button-hover); }
.themetoggle:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.themeicon { width: 19px; height: 19px; display: block; }
.themeicon.moon { display: none; }
[data-theme="light"] .themeicon.sun { display: none; }
[data-theme="light"] .themeicon.moon { display: block; }
.idxsummary { display: flex; align-items: center; gap: var(--space-2); flex-wrap: wrap; }
.idxsummary .stat { color: var(--sub2); font-size: 13px; font-weight: 500; white-space: nowrap; }
.idxsummary .stat strong { color: var(--txt); font-size: 16px; }
.idxsummary .stat.pass strong { color: var(--pass); }
.idxsummary .stat.selfheal strong { color: var(--amber); }
.idxsummary .stat.fail strong { color: var(--fail); }
.indexcontext { display: flex; align-items: flex-end; justify-content: space-between; gap: var(--space-5); max-width: var(--content-wide); margin-top: var(--space-4); }
.indexmeta { margin-top: 0; }
.indexlinks { display: flex; align-items: center; gap: var(--space-2); flex-shrink: 0; }
.idxfilter { display: grid; grid-template-columns: minmax(0,1fr) auto 132px; align-items: center; gap: var(--space-2); width: min(100%,var(--content-wide)); margin-bottom: var(--space-3); }
.idxfilter input { width: 100%; min-width: 0; min-height: var(--control-height); background: var(--bg2); border: 1px solid var(--line2); color: var(--txt); border-radius: 8px; padding: 6px 10px; font: inherit; font-size: var(--type-caption); outline: none; }
.idxfilter input:focus-visible, .idxsort summary:focus-visible, .idxhealedfilter:focus-visible { border-color: var(--accent); box-shadow: 0 0 0 2px rgba(77,139,255,.16); outline: none; }
.idxhealedfilter { min-height: var(--control-height); display: inline-flex; align-items: center; gap: 7px; border: 1px solid var(--line2); border-radius: var(--r-md); padding: 5px 11px; background: var(--bg3); color: var(--sub2); font: inherit; font-size: var(--type-caption); font-weight: 500; white-space: nowrap; cursor: pointer; }
.idxhealedfilter::before { content: ''; width: 7px; height: 7px; border: 2px solid var(--amber); border-radius: 50%; }
.idxhealedfilter:hover { border-color: var(--amber); color: var(--txt); }
.idxhealedfilter[aria-pressed="true"] { border-color: rgba(242,184,75,.55); background: rgba(242,184,75,.13); color: var(--amber); }
.idxsort { position: relative; width: 132px; color: var(--sub2); font-size: var(--type-caption); font-weight: 500; }
.idxsort summary { min-height: var(--control-height); display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); list-style: none; border: 1px solid var(--line2); border-radius: var(--r-md); padding: 5px var(--space-3); background: var(--bg3); cursor: pointer; transition: color 100ms ease-out,background-color 100ms ease-out,border-color 100ms ease-out; }
.idxsort summary::-webkit-details-marker { display: none; }
.idxsort summary:hover, .idxsort[open] summary { border-color: var(--run); background: var(--button-hover); color: var(--txt); }
.idxsortchev { width: 8px; height: 8px; flex-shrink: 0; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); }
.idxsortmenu { position: absolute; z-index: 30; top: calc(100% + 6px); right: 0; width: 164px; display: grid; gap: 3px; padding: 5px; border: 1px solid var(--line2); border-radius: 14px; background: var(--raised); box-shadow: 0 16px 36px rgba(0,0,0,.42); transform-origin: top right; animation: idxsortin 120ms cubic-bezier(.16,1,.3,1); }
.idxsortoption { min-height: 32px; display: flex; align-items: center; justify-content: space-between; width: 100%; border: 0; border-radius: 9px; padding: 6px 10px; background: transparent; color: var(--sub2); font: inherit; font-size: 11.5px; font-weight: 500; text-align: left; cursor: pointer; }
.idxsortoption:hover, .idxsortoption:focus-visible { background: var(--button-hover); color: var(--txt); outline: none; }
.idxsortoption[aria-selected="true"] { background: var(--accent-surface); color: var(--run); }
.idxsortoption[aria-selected="true"]::after { content: '✓'; color: var(--run); font-size: 11px; }
@keyframes idxsortin { from { opacity: 0; transform: translateY(-4px) scale(.98); } to { opacity: 1; transform: translateY(0) scale(1); } }
.idxsection + .idxsection { margin-top: var(--space-4); }
.idxsectionhead { display: flex; align-items: center; gap: 8px; margin: 0 0 7px 2px; color: var(--sub2); font-size: var(--type-caption); font-weight: 600; letter-spacing: .08em; text-transform: uppercase; }
.idxsectionhead::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: var(--sub); }
.idxsectionhead.failed::before { background: var(--fail); }
.idxsectionhead.selfheal::before { background: var(--amber); }
.idxsectionhead.passed::before { background: var(--pass); }
.idxsectioncount { color: var(--sub); font-weight: 500; letter-spacing: 0; text-transform: none; }
.idx { border: 1px solid var(--line); border-radius: var(--r-md); overflow: hidden; background: var(--bg2); max-width: var(--content-wide); }
.idxrow { display: grid; grid-template-columns: 12px minmax(220px,1fr) 256px 20px; align-items: center; gap: var(--space-4); padding: var(--space-3) var(--space-4); border-top: 1px solid var(--line); cursor: pointer; transition: background-color 120ms ease-out, box-shadow 120ms ease-out; }
.idxrow[hidden] { display: none; }
.idxrow:first-child { border-top: none; }
.idxrow.firstmatch { border-top: none; }
.idxrow:hover { background: var(--bg3); }
.idxrow:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxstatus { width: 12px; height: 12px; display: flex; align-items: center; justify-content: center; }
.idxstatusdot { width: 7px; height: 7px; border-radius: 50%; background: var(--sub); box-shadow: 0 0 0 3px rgba(160,169,184,.08); }
.idxstatusdot.failed { background: var(--fail); box-shadow: 0 0 0 3px rgba(255,91,106,.1); }
.idxstatusdot.selfheal { background: var(--amber); box-shadow: 0 0 0 3px rgba(242,184,75,.11); }
.idxstatusdot.passed { background: var(--pass); box-shadow: 0 0 0 3px rgba(48,211,109,.1); }
.idxmain { min-width: 0; }
.idxrow .nm { font-size: 14px; font-weight: 650; min-width: 0; word-break: break-word; }
.idxowner { margin-top: 2px; color: var(--sub); font-size: var(--type-micro); font-weight: 400; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.idxentry { border-top: 1px solid var(--line); }
.idxentry:first-child { border-top: 0; }
.idxentry.firstmatch { border-top: 0; }
.idxentry > .idxrow { border-top: 0; }
.idxmatrixrow { grid-template-columns: minmax(220px,1fr) minmax(0,auto); cursor: default; }
.idxmatrixrow .nm { font-weight: 550; }
.idxcells { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.idxcell { position: relative; width: 158px; box-sizing: border-box; border: 1px solid var(--line2); border-radius: 8px; background: var(--bg3); transition: border-color 120ms ease-out, background-color 120ms ease-out; }
.idxcell:hover { border-color: var(--run); }
.idxcellopen { display: flex; flex-direction: column; gap: 4px; width: 100%; box-sizing: border-box; margin: 0; padding: 9px 14px; border: 0; border-radius: 7px; background: none; font: inherit; color: inherit; text-align: left; cursor: pointer; }
.idxcellopen:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxcell .pk { font-size: 10px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: var(--sub); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.idxcell .pv { display: flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 500; color: var(--sub2); font-variant-numeric: tabular-nums; }
.idxcell .idxstatusdot { box-shadow: none; }
.idxcell.failed { border-color: rgba(255,91,106,.35); background: rgba(255,91,106,.06); }
.idxcell.selfheal { border-color: rgba(242,184,75,.3); background: rgba(242,184,75,.05); }
.idxcell.missing { display: flex; flex-direction: column; gap: 4px; padding: 9px 14px; border-style: dashed; background: transparent; }
.idxcell.missing:hover { border-color: var(--line2); }
.idxcell.missing .pv { color: var(--sub); opacity: .7; }
.idxcell.retried .idxcellopen { padding-right: 34px; }
.idxcelldots { display: inline-flex; align-items: center; gap: 4px; }
.idxcelldots .idxstatusdot { width: 6px; height: 6px; }
.idxcelldots .idxstatusdot:not(:last-child) { opacity: .5; }
.idxcellmore { margin-right: 1px; font-size: 10px; font-weight: 600; color: var(--sub); font-variant-numeric: tabular-nums; }
.idxcellchev { position: absolute; right: 0; top: 0; bottom: 0; width: 27px; display: flex; align-items: center; justify-content: center; border: 0; border-left: 1px solid var(--line2); border-radius: 0 7px 7px 0; padding: 0; background: transparent; cursor: pointer; }
.idxcellchev:hover { background: var(--button-hover); }
.idxcellchev:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxcellchev::before { content: ''; width: 7px; height: 7px; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out, color 120ms ease-out; }
.idxcellchev.open::before { color: var(--ai); transform: rotate(225deg) translate(-1px,-1px); }
.idxcell.failed .idxcellchev { border-left-color: rgba(255,91,106,.35); }
.idxcell.selfheal .idxcellchev { border-left-color: rgba(242,184,75,.3); }
.idxatthead { padding: 10px var(--space-4) 3px 28px; font-size: 10px; font-weight: 600; letter-spacing: .08em; text-transform: uppercase; color: var(--sub); }
.idxmatrixattempts .idxattemptrow { padding-left: 28px; border-top: 0; min-height: 44px; }
.idxmatrixattempts .idxattemptlabel { font-weight: 500; }
.idxmatrixattempts .idxattemptstatus { font-weight: 600; }
.idxfacts { display: grid; grid-template-columns: 104px 60px 60px; gap: 16px; align-items: center; }
.idxfact .k { color: var(--sub); font-size: var(--type-micro); letter-spacing: .08em; text-transform: uppercase; }
.idxfact .v { color: var(--sub2); font-size: var(--type-caption); font-weight: 500; margin-top: 1px; white-space: nowrap; }
.quietlink { min-height: 32px; display: inline-flex; align-items: center; color: var(--sub2); border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 5px 9px; font-size: 11px; font-weight: 500; text-decoration: none; background: var(--bg2); }
.quietlink:hover { color: var(--txt); border-color: var(--run); background: rgba(106,166,255,.07); }
.quietlink:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.idxrow .arr { color: var(--sub); font-size: 14px; align-self: center; }
.idxretry { border-top: 1px solid var(--line); }
.idxretry:first-child { border-top: 0; }
.idxretry > summary { list-style: none; border-top: 0; }
.idxretry > summary::-webkit-details-marker { display: none; }
.idxretryrow { grid-template-columns: auto minmax(220px,1fr) 256px 20px; }
.idxretrydots { display: inline-flex; align-items: center; gap: 5px; padding-inline: 1px; }
.idxretrydots .idxstatusdot { flex-shrink: 0; }
.idxretrychev { width: 8px; height: 8px; justify-self: center; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out,color 120ms ease-out; }
.idxretry[open] .idxretrychev { color: var(--ai); transform: rotate(225deg) translate(-1px,-1px); }
.idxattempts { background: var(--bg2); border-top: 1px solid var(--line); }
.idxattemptrow { display: grid; grid-template-columns: 12px minmax(220px,1fr) 256px 20px; align-items: center; gap: var(--space-4); min-height: 58px; padding: 10px var(--space-4) 10px 48px; border-top: 1px solid var(--line); cursor: pointer; transition: background-color 120ms ease-out,box-shadow 120ms ease-out; }
.idxattemptrow:first-child { border-top: 0; }
.idxattemptrow:hover { background: var(--bg3); }
.idxattemptrow[data-outcome="failed"]:hover { background: var(--danger-surface); box-shadow: inset 3px 0 var(--fail); }
.idxattemptrow[data-outcome="selfheal"]:hover { background: rgba(242,184,75,.055); box-shadow: inset 3px 0 var(--amber); }
.idxattemptrow[data-outcome="passed"]:hover { background: rgba(48,211,109,.045); box-shadow: inset 3px 0 var(--pass); }
.idxattemptrow:focus-visible { outline: 1px solid var(--line2); outline-offset: -1px; }
.idxattemptmain { min-width: 0; display: flex; align-items: baseline; gap: 10px; }
.idxattemptlabel { color: var(--txt); font-size: var(--type-caption); font-weight: 550; white-space: nowrap; }
.idxattemptstatus { font-size: var(--type-micro); font-weight: 600; text-transform: capitalize; }
.idxattemptstatus.failed { color: var(--fail); }
.idxattemptstatus.selfheal { color: var(--amber); }
.idxattemptstatus.passed { color: var(--pass); }
.idxattempttime { min-width: 0; overflow: hidden; color: var(--sub); font-size: var(--type-micro); text-overflow: ellipsis; white-space: nowrap; }
.detailheader { padding-top: var(--space-4); }
.detailheader h1 { font-size: 20px; }
.detailheader nav { margin-top: var(--space-3); }
.detailtitle { min-height: 32px; max-width: none; display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 12px; }
.detailtitle.noback { grid-template-columns: minmax(0,1fr) auto; }
.detailedge { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; }
.runidentity { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; min-width: 0; }
.exportmenu { position: relative; }
.exportmenu summary { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid transparent; border-radius: 7px; color: var(--sub); cursor: pointer; list-style: none; }
.exportmenu summary::-webkit-details-marker { display: none; }
.exportdots { display: inline-flex; align-items: center; gap: 3px; }
.exportdot { width: 5px; height: 5px; border-radius: 50%; background: currentColor; }
.exportmenu summary:hover, .exportmenu[open] summary { color: var(--txt); border-color: var(--line2); background: var(--button-hover); }
.exportmenuitems { position: absolute; z-index: 30; top: calc(100% + 5px); right: 0; width: 196px; padding: 5px; border: 1px solid var(--line2); border-radius: 9px; background: var(--bg2); box-shadow: 0 12px 30px rgba(0,0,0,.38); animation: idxsortin 120ms ease-out both; }
.exportmenuitem { width: 100%; min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 9px; border: 0; border-radius: 6px; background: transparent; color: var(--sub2); font: inherit; font-size: 11px; font-weight: 650; text-align: left; cursor: pointer; }
.exportmenuitem:hover { color: var(--txt); background: var(--button-hover); }
.exportmenuitem:disabled { color: var(--sub); cursor: not-allowed; opacity: .48; background: transparent; }
.exportmenuitem .count { color: var(--sub); font-size: 9px; line-height: 1.2; font-variant-numeric: tabular-nums; }
.headeraction { min-width: 72px; display: inline-flex; align-items: center; justify-content: center; }
.back { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; background: transparent; border: 1px solid transparent; border-radius: 7px; color: var(--sub); cursor: pointer; padding: 0; }
.back:hover { color: var(--txt); border-color: var(--line2); background: var(--button-hover); }
.back:focus-visible { color: var(--txt); outline: 2px solid var(--focus); outline-offset: 2px; }
.backarrow { font-family: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, sans-serif; font-size: 25px; font-weight: 600; line-height: .9; }
.yaml { max-height: none; max-width: 880px; }
.yamlline { display: block; min-height: 1.5em; }
.yamlmark { display: block; margin: 0 0 0 -10px; padding: 0 0 0 10px; border-left: 3px solid transparent; }
.yamlmark.failed { border-left-color: var(--fail); }
.yamlmark.selfheal { border-left-color: var(--amber); }
.yamlmark.tool.failed { border-left-width: 4px; }
.yamlmark.tool.selfheal { border-left-width: 4px; }
.shotwrap { position: relative; display: block; }
.mark { position: absolute; pointer-events: none; }
.mark.tap { width: 26px; height: 26px; margin: -13px 0 0 -13px; border: 2px solid var(--fail); border-radius: 99px; background: rgba(248,71,82,.25); box-shadow: 0 0 0 1px rgba(0,0,0,.5); }
.mark.assertok { width: 26px; height: 26px; margin: -13px 0 0 -13px; border: 2px solid var(--pass); border-radius: 99px; background: rgba(46,204,92,.22); }
.markborder { position: absolute; inset: 0; border: 3px solid var(--fail); border-radius: 6px; pointer-events: none; }
svg.swipe { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
.viewpage.lightboxpage { max-width: none; }
.gal { width: 100%; display: grid; grid-template-columns: repeat(auto-fill,minmax(min(var(--galsize,190px),100%),1fr)); gap: 16px; align-items: start; }
.lightboxtoolbar { display: flex; align-items: center; justify-content: flex-start; margin: -4px 0 var(--space-4); }
.lightboxzoom { display: inline-flex; gap: 4px; margin-left: auto; }
.lightboxzoombtn { width: 30px; min-height: 30px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--line2); border-radius: 8px; background: var(--bg2); color: var(--sub2); font: inherit; font-size: 15px; font-weight: 650; line-height: 1; cursor: pointer; }
.lightboxzoombtn:hover:not(:disabled) { color: var(--txt); border-color: var(--run); }
.lightboxzoombtn:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.lightboxzoombtn:disabled { opacity: .4; cursor: default; }
.lightboxtoggle { min-height: 30px; display: inline-flex; align-items: center; gap: 8px; padding: 4px 8px 4px 7px; border: 1px solid var(--line2); border-radius: 8px; background: var(--bg2); color: var(--sub2); font: inherit; font-size: var(--type-caption); font-weight: 650; cursor: pointer; }
.lightboxtoggle:hover { color: var(--txt); border-color: var(--run); }
.lightboxtoggle:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.lightboxtoggletrack { width: 24px; height: 14px; display: flex; align-items: center; padding: 2px; border-radius: 99px; background: var(--line2); transition: background-color 120ms ease-out; }
.lightboxtogglethumb { width: 10px; height: 10px; border-radius: 99px; background: var(--sub2); transition: transform 120ms ease-out, background-color 120ms ease-out; }
.lightboxtoggle[aria-checked="true"] .lightboxtoggletrack { background: rgba(106,166,255,.48); }
.lightboxtoggle[aria-checked="true"] .lightboxtogglethumb { background: var(--accent-7); transform: translateX(10px); }
.galcell { min-width: 0; border: 0; padding: 0; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.galcell:hover .gallabel, .galcell:hover .galtool { color: var(--txt); }
.galshot { cursor: zoom-in; }
.galcell img { width: 100%; border: 1px solid var(--line2); border-radius: 6px; display: block; background: #000; }
.galcell .cap { display: grid; gap: 5px; margin-top: 7px; line-height: 1.35; word-break: break-word; }
.galchip { width: fit-content; }
.galchip.trailhead { color: var(--purple); background: var(--violet-surface); }
.gallabel { color: var(--sub2); font-size: var(--type-caption); font-weight: 600; }
.galtool { color: var(--sub); font-size: var(--type-caption); }
.logpane { border: 1px solid var(--line); border-radius: 8px; background: var(--bg); max-height: 72vh; overflow: auto; margin-top: 8px; }
.logpane .ln { display: flex; gap: 10px; padding: 1px 11px; font-size: 11.5px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; border-top: 1px solid var(--line); }
.logpane .ln:first-child { border-top: none; }
.logpane .ln.e { color: var(--danger-text); } .logpane .ln.w { color: var(--warning-text); }
.logpane.net .ln span:first-child { font-weight: 700; min-width: 46px; }
.logpane.net .m { color: var(--sub); min-width: 96px; }
.evchips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.evchip { background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: 999px; padding: 4px 10px; font-size: 11.5px; font-weight: 600; cursor: pointer; display: inline-flex; align-items: center; gap: 6px; }
.evchip:hover { border-color: var(--run); }
.evchip.on { border-color: var(--run); background: var(--bg2); }
.evchip .c { color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.streamselect { position: relative; flex-shrink: 0; }
.streamselect summary { width: 218px; min-height: 32px; display: grid; grid-template-columns: auto minmax(0,1fr) auto auto; align-items: center; gap: 8px; padding: 5px 9px; border: 1px solid var(--line2); border-radius: 9px; background: var(--bg2); color: var(--sub2); cursor: pointer; list-style: none; font-size: 10.5px; font-weight: 650; }
.streamselect summary::-webkit-details-marker { display: none; }
.streamselect summary:hover, .streamselect[open] summary { border-color: color-mix(in srgb,var(--run) 58%,var(--line2)); background: var(--bg3); }
.streamselect summary:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.streamselectoricon, .streamoptiondot { width: 9px; height: 9px; border: 1.5px solid currentColor; border-radius: 99px; background: transparent; flex-shrink: 0; }
.streamselectoricon { color: var(--sub); }
.streamselect .selection { color: var(--sub); font-size: 10px; font-weight: 500; font-variant-numeric: tabular-nums; white-space: nowrap; }
.streamselect .chevron { width: 7px; height: 7px; flex-shrink: 0; border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor; color: var(--sub); transform: rotate(45deg); transform-origin: center; transition: transform 120ms ease-out; }
.streamselect[open] .chevron { transform: rotate(225deg); }
.streammenu { position: absolute; z-index: 20; top: calc(100% + 6px); left: 0; width: min(320px, calc(100vw - 48px)); overflow: hidden; border: 1px solid var(--line2); border-radius: 10px; background: var(--bg3); box-shadow: 0 14px 34px rgba(0,0,0,.38); }
.streammenuhead { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 9px 10px; border-bottom: 1px solid var(--line); color: var(--sub); font-size: 10.5px; }
.streammenuactions { display: flex; gap: 4px; }
.streammenuactions button { padding: 3px 6px; border: 0; background: transparent; color: var(--run); cursor: pointer; font: inherit; font-weight: 650; }
.streammenuactions button:hover, .streammenuactions button:focus-visible { color: var(--txt); outline: none; text-decoration: underline; }
.streamoption { display: grid; grid-template-columns: 16px 10px minmax(0,1fr) auto; align-items: center; gap: 9px; padding: 9px 10px; border-top: 1px solid var(--line); cursor: pointer; }
.streamoption:first-of-type { border-top: 0; }
.streamoption:hover { background: var(--button-hover); }
.streamoption input { width: 14px; height: 14px; margin: 0; accent-color: var(--run); cursor: pointer; }
.streamoptiondot { color: var(--stream-color); }
.streamoption .streamname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; font-weight: 600; }
.streamoption .streamcount { color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.video { max-width: 640px; }
.vframe { height: min(72vh, 900px); max-width: 100%; aspect-ratio: 1/2; background-repeat: no-repeat; background-color: #000; border: 1px solid var(--line2); border-radius: 6px; margin-top: 10px; }
.vctl { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.vctl .count { font-variant-numeric: tabular-nums; }
.vctl input[type=range] { flex: 1; accent-color: var(--run); }
.scrub { flex-shrink: 0; display: flex; align-items: center; gap: 12px; padding: 7px var(--page-x); border-top: 1px solid var(--line); background: var(--header); user-select: none; }
.scrubclock { color: var(--sub); font-size: 9.5px; text-align: center; font-variant-numeric: tabular-nums; }
.scrubtrack { position: relative; flex: 1; height: 28px; cursor: pointer; }
.scrubline { position: absolute; top: 50%; height: 1px; transform: translateY(-50%); pointer-events: none; }
.scrubline.setup { left: 0; height: 0; border-top: 1px dashed color-mix(in srgb,var(--purple) 62%,var(--line2)); }
.scrubline.trail { right: 0; background: var(--line2); }
.scrubphasebreak { position: absolute; top: 50%; width: 11px; height: 11px; border: 2px solid var(--bg); border-radius: 99px; background: var(--purple); box-shadow: 0 0 0 1px color-mix(in srgb,var(--purple) 55%,var(--line2)); transform: translate(-50%,-50%); pointer-events: none; }
.scrubtick { position: absolute; top: 4px; bottom: 4px; width: 3px; border: 0; padding: 0; border-radius: 2px; opacity: .72; pointer-events: none; }
.scrubhead { position: absolute; top: 50%; width: 10px; height: 10px; border-radius: 99px; transform: translate(-50%,-50%); background: #fff; border: 1px solid rgba(0,0,0,.45); box-shadow: 0 1px 5px rgba(0,0,0,.6); pointer-events: none; }
.streamrow { border-top: 1px solid var(--line); padding: 8px 14px 8px 22px; background: color-mix(in oklab, var(--stream-color) 5%, transparent); }
.streamrow summary { cursor: pointer; display: flex; align-items: center; gap: 8px; list-style: none; font-size: 11.5px; }
.streamrow summary::-webkit-details-marker { display: none; }
.streamrow .streamdot { width: 9px; height: 9px; border: 1.5px solid var(--stream-color); border-radius: 99px; background: transparent; flex-shrink: 0; }
.streamrow .streamtype { color: var(--stream-color); font-size: 11.5px; font-weight: 700; }
.streamrow .streamtime { margin-left: auto; color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.streamrow pre { margin: 7px 0 2px 16px; max-height: 220px; }
.streamitems { margin: 7px 0 2px 16px; display: grid; gap: 7px; }
.streamitems.timelineeventitems { margin-top: 3px; gap: 0; }
.timelineevent { min-width: 0; border-top: 1px solid var(--line); }
.timelineevent:first-child { border-top: 0; }
.timelineevent.e { border-left: 3px solid var(--fail); }
.timelineevent.w { border-left: 3px solid var(--amber); }
.timelineevent summary { min-height: 38px; display: grid; grid-template-columns: 62px minmax(0,1fr) 10px; align-items: center; gap: 8px; padding: 7px 9px; color: var(--sub2); cursor: pointer; list-style: none; }
.timelineevent summary::-webkit-details-marker { display: none; }
.timelineevent summary:hover { background: var(--button-hover); }
.timelineevent .streamtime { margin: 0; padding: 0; }
.timelineeventlabel { min-width: 0; color: var(--txt); font-size: 11.5px; font-weight: 650; line-height: 1.35; overflow-wrap: anywhere; white-space: normal; }
.timelineeventchev { width: 7px; height: 7px; border-right: 2px solid currentColor; border-bottom: 2px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out; }
.timelineevent[open] .timelineeventchev { transform: rotate(225deg) translate(-1px,-1px); }
.timelineevent pre { margin: 0 9px 9px; max-height: 220px; background: var(--code-surface); color: var(--code-text); }
.eventfields { display: grid; grid-template-columns: repeat(auto-fit,minmax(170px,1fr)); gap: 1px; background: var(--line); }
.eventfield { min-width: 0; padding: 6px 9px; background: var(--bg2); }
.eventfield .k { color: var(--sub); font-size: 9.5px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; }
.eventfield .v { margin-top: 2px; color: var(--sub2); font-size: 11.5px; font-weight: 600; overflow-wrap: anywhere; }
.fmtbadges { display: inline-flex; gap: 4px; flex-wrap: wrap; justify-content: flex-end; }
.rowbadge { padding: 1px 7px; border-radius: 99px; border: 1px solid var(--line2); background: var(--bg3); color: var(--sub2); font-size: 10px; font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.rowbadge.ok { color: var(--pass); border-color: color-mix(in srgb,var(--pass) 45%,var(--line2)); }
.rowbadge.warn { color: var(--amber); border-color: color-mix(in srgb,var(--amber) 45%,var(--line2)); }
.rowbadge.error { color: var(--fail); border-color: color-mix(in srgb,var(--fail) 45%,var(--line2)); }
.fmtbody { border-top: 1px solid var(--line); }
/* NOT named .tl — that class is the page-level timeline grid (.tl { display: grid; gap }) and would restyle this body. */
.fmtbody.tlbody { margin: 0 9px 9px; border: 1px solid var(--line2); border-radius: 8px; overflow: hidden; background: var(--bg2); }
.fmtbody.tlbody:first-child, .fmtbody .eventfields:first-child { border-top: 0; }
.fmtbody pre { margin: 0; border: 0; border-top: 1px solid var(--line); border-radius: 0; max-height: 480px; background: var(--code-surface); color: var(--code-text); }
.fmtbody pre:first-child { border-top: 0; }
.yamlcompare { display: grid; grid-template-columns: 1fr; gap: 20px; align-items: start; }
.yamlcol { min-width: 0; }
.yamlcolhead { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.yamlcolhead .eyebrow { margin: 0; }
.yamlcopy { min-height: 24px; padding: 3px 7px; border-radius: 6px; font-size: 10px; }
.yamlcol .cmd { max-width: none; }
@media (min-width: 820px) { .yamlcompare { grid-template-columns: repeat(2,minmax(0,1fr)); } .llm { grid-template-columns: 300px 1fr; } }
@media (min-width: 960px) {
  main.timelinemain { overflow: hidden; }
  .timelinemain .tl { height: 100%; min-height: 0; grid-template-columns: minmax(320px,1fr) minmax(340px,42%); grid-template-rows: minmax(0,1fr); gap: 24px; align-items: stretch; }
  .timelinemain .timeline-list { grid-row: auto; min-height: 0; overflow: auto; padding-right: 3px; }
  .timelinemain .preview { position: static; grid-row: auto; min-height: 0; height: 100%; display: flex; align-items: center; justify-content: center; }
  .timelinemain .deviceplayer { max-height: 100%; min-height: 0; align-self: center; }
  .timelinemain .shotwrap { max-height: calc(100vh - 330px); min-height: 0; }
  .timelinemain .shot { width: auto; height: auto; max-height: calc(100vh - 330px); object-fit: contain; }
  .timelinemain .noshot { height: auto; min-height: 0; aspect-ratio: 1/2; }
}
@media (max-width: 760px) { .indexcontext { align-items: flex-start; flex-direction: column; } .idxrow, .idxattemptrow { grid-template-columns: 12px minmax(0,1fr) 20px; gap: 10px 12px; } .idxrow.idxmatrixrow { grid-template-columns: minmax(0,1fr); } .idxmatrixrow .idxcells { justify-content: flex-start; } .idxretryrow { grid-template-columns: auto minmax(0,1fr) 20px; } .idxretrychev { grid-column: 3; grid-row: 1; } .idxattemptrow { padding-left: 28px; } .idxstatus { grid-row: 1 / span 2; } .idxfacts { grid-column: 2 / -1; } .idxrow .arr, .idxattemptrow .arr { grid-column: 3; grid-row: 1; } .idxfilter { grid-template-columns: minmax(0,1fr) auto 120px; } .idxsort { width: 120px; } .indexfootercontent { flex-wrap: wrap; } .indexmetrics { order: 2; width: 100%; margin-left: 0; } .indexrundate { margin-left: auto; } .streamselect summary { width: 100%; } .streammenu { left: 0; right: auto; } }
@media (max-width: 560px) { .idxfilter { grid-template-columns: minmax(0,1fr) 120px; } .idxfilter input { grid-column: 1 / -1; } .idxhealedfilter { justify-content: center; } }
@media (max-width: 560px) { .failurehead { align-items: flex-start; flex-wrap: wrap; } .failurecontext { width: 100%; margin-left: 30px; } .failuretool { grid-template-columns: 1fr; gap: 6px; } .failuretoolvalue { flex-wrap: wrap; } .failuretoolargs { display: block; } .failuretool .yamllink { margin-left: auto; } .failurebody { grid-template-columns: 1fr; } .failurefield + .failurefield { border-top: 1px solid rgba(248,71,82,.18); border-left: 0; } }
.step .ts { margin-left: auto; flex-shrink: 0; color: var(--sub); font-size: 10.5px; text-align: right; font-variant-numeric: tabular-nums; }
.step .ts .dur { display: block; color: var(--sub); opacity: .8; }
.lfilter { display: flex; align-items: center; gap: 8px; margin: 8px 0; flex-wrap: wrap; }
.lfilter input { background: var(--bg2); border: 1px solid var(--line2); color: var(--txt); border-radius: 8px; padding: 6px 10px; font-size: 12.5px; min-width: 220px; }
.lfilter input:focus { border-color: var(--run); }
.lfilter .count { font-size: 11px; color: var(--sub); margin-left: auto; font-variant-numeric: tabular-nums; }
.badge.selfheal { background: rgba(242,184,75,.16); color: var(--amber); }
.zoom .zoomwrap { position: relative; }
.zoom .zoomwrap img { display: block; }
button:focus-visible, [role="button"]:focus-visible, summary:focus-visible, input:focus-visible, .shot:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
@media (pointer: coarse) { nav button, button.btn, .evchip, .back, .streamselect summary, .idxsort summary, .exportmenu summary, .exportmenuitem, .phasecontrol, .grphdr { min-height: 44px; } .detailedge { width: 44px; height: 44px; } .back, .exportmenu summary { min-width: 44px; } .step { min-height: 44px; } .scrubtrack { height: 44px; } }
@media (prefers-reduced-motion: reduce) { #app.page-enter-forward, #app.page-enter-back { animation: none; } }
@media (max-width: 640px) {
  :root { --page-x: 18px; --page-y: 20px; }
  main { padding-bottom: var(--space-5); }
  h1 { font-size: 21px; }
  .detailheader h1 { font-size: 18px; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition-duration: .01ms !important; animation-duration: .01ms !important; } }
`;
