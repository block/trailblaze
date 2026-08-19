// Stylesheet for the interactive run report, emitted into the <head> of every exported report
// document by buildMultiReportHtml (run-report-html.ts).
import { REPORT_DESIGN_TOKENS_CSS } from './report-design-tokens';

// Kept as a public alias for existing report embedders. The source of truth lives in the dedicated
// design-token module shared by the run and performance reports.
export const RUN_REPORT_TOKENS_CSS = REPORT_DESIGN_TOKENS_CSS;

export const RUN_REPORT_CSS = `${RUN_REPORT_TOKENS_CSS}
* { box-sizing: border-box; scrollbar-width: thin; scrollbar-color: rgba(144,152,164,.32) transparent; }
*::-webkit-scrollbar { width: 8px; height: 8px; }
*::-webkit-scrollbar-track { background: transparent; }
*::-webkit-scrollbar-thumb { min-height: 36px; border: 2px solid transparent; border-radius: 99px; background: rgba(144,152,164,.32); background-clip: padding-box; }
*::-webkit-scrollbar-thumb:hover { background-color: rgba(144,152,164,.52); }
*::-webkit-scrollbar-corner { background: transparent; }
html, body { margin: 0; height: 100%; overflow: hidden; }
body { background: var(--bg); color: var(--txt); font: var(--font-weight-body) var(--type-body)/1.45 -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif; text-rendering: optimizeLegibility; transition: background-color 140ms ease-out,color 140ms ease-out; }
strong, b { font-weight: var(--font-weight-emphasis); }
.mono { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; }
#app { display: flex; flex-direction: column; height: 100%; height: 100dvh; min-height: 0; overflow: hidden; }
/* The static boot loader and the viewer's still-downloading-this-run view read as the same thing,
   so they share one presentation; only the box they fill differs (#tb-boot is a flex child of #app,
   .runloading fills <main>). */
#tb-boot, .runloading { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--space-3); color: var(--sub); text-align: center; }
#tb-boot { flex: 1; }
.runloading { min-height: 100%; box-sizing: border-box; }
.tb-boot-spinner { width: 28px; height: 28px; border-radius: 50%; border: 3px solid var(--line2); border-top-color: var(--run); animation: tbBootSpin .8s linear infinite; }
.tb-boot-title { font-size: var(--type-body); font-weight: var(--font-weight-emphasis); color: var(--txt); }
.tb-boot-note { font-size: var(--type-small); max-width: 46ch; text-wrap: balance; }
@keyframes tbBootSpin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .tb-boot-spinner { animation: none; } }
@keyframes reportPageForward { from { opacity: .35; transform: translateX(18px); } to { opacity: 1; transform: translateX(0); } }
@keyframes reportPageBack { from { opacity: .35; transform: translateX(-18px); } to { opacity: 1; transform: translateX(0); } }
#app.page-enter-forward { animation: reportPageForward 220ms cubic-bezier(.16,1,.3,1) both; }
#app.page-enter-back { animation: reportPageBack 220ms cubic-bezier(.16,1,.3,1) both; }
header { flex-shrink: 0; padding: var(--page-y) var(--page-x) 0; border-bottom: 1px solid var(--line); background: var(--header); }
.title-row { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; max-width: var(--content-wide); }
h1 { font-size: var(--type-title); line-height: 1.2; letter-spacing: -.018em; margin: 0; font-weight: var(--font-weight-emphasis); }
.badge { font-size: 11px; font-weight: var(--font-weight-emphasis); text-transform: uppercase; letter-spacing: .04em; padding: 3px 9px; border-radius: 99px; }
.badge.passed, .badge.success { background: var(--success-surface); color: var(--success-text); }
.badge.failed, .badge.error { background: var(--danger-surface); color: var(--danger-text); }
.badge.running, .badge.cancelled, .badge.unknown { background: var(--accent-surface); color: var(--run); }
.meta { display: flex; flex-wrap: wrap; gap: var(--space-3) var(--space-5); margin-top: var(--space-4); }
.meta .k { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .1em; color: var(--sub); }
.meta .v { font-size: var(--type-small); font-weight: var(--font-weight-emphasis); margin-top: 1px; }
nav { display: flex; gap: var(--space-1); margin-top: 20px; margin-bottom: -1px; margin-left: calc(-1 * var(--space-3)); overflow-x: auto; scrollbar-width: none; }
nav::-webkit-scrollbar { display: none; width: 0; height: 0; }
nav button { position: relative; isolation: isolate; display: inline-flex; align-items: center; gap: 6px; background: none; border: none; color: var(--sub); font-size: 13px; font-weight: var(--font-weight-strong); padding: 10px 12px; cursor: pointer; border-bottom: 2px solid transparent; white-space: nowrap; transition: color 120ms ease-out, border-color 120ms ease-out; }
nav button::before { content: ''; position: absolute; inset: 4px 3px 6px; z-index: -1; border-radius: var(--r-sm); background: transparent; transition: background-color 120ms ease-out; }
nav button::after { content: ''; position: absolute; right: 3px; bottom: -2px; left: 3px; height: 2px; background: transparent; }
nav button:hover { color: var(--txt); }
nav button:hover::before { background: var(--button-hover); }
nav button.active { color: var(--txt); border-radius: 0; background: transparent; }
nav button.active::after { background: var(--run); }
.counttoken { min-width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; padding: 0 6px; border-radius: 99px; background: var(--control-counter-surface); color: var(--control-counter-text); font-size: 11px; font-weight: var(--font-weight-emphasis); line-height: 20px; font-variant-numeric: tabular-nums; }
main { flex: 1; min-height: 0; overflow: auto; padding: var(--page-y) var(--page-x) var(--space-6); }
main.timelinemain { padding-top: var(--space-3); }
footer { flex-shrink: 0; padding: var(--space-3) var(--page-x); border-top: 1px solid var(--line); color: var(--sub); font-size: var(--type-caption); display: flex; gap: var(--space-2); align-items: center; }
.indexfooter, .detailfooter { min-height: 59px; box-sizing: border-box; justify-content: space-between; }
.detailfooter { min-width: 0; gap: var(--space-4); }
.detailfootermeta { min-width: 0; flex: 1; display: flex; align-items: center; gap: var(--space-4); overflow-x: auto; scrollbar-width: none; }
.detailfootermeta::-webkit-scrollbar { display: none; }
.detailfooteritem { display: grid; gap: 1px; white-space: nowrap; }
.detailfooteritem.runon { margin-left: auto; text-align: right; }
.detailfooteritem .k { color: var(--neutral-10); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .09em; line-height: 1.2; text-transform: uppercase; }
.detailfooteritem .v { color: var(--sub); font-size: var(--type-caption); font-weight: var(--font-weight-emphasis); line-height: 1.25; }
.indexshell { width: 100%; max-width: var(--content-wide); margin-inline: auto; }
.indexfootercontent { display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); }
.indexmetrics { display: flex; align-items: center; gap: var(--space-5); margin-left: auto; }
.indexrundate { text-align: right; }
[data-theme="light"] .idxrow:hover, [data-theme="light"] .grphdr:hover { background: var(--neutral-3); }
[data-theme="light"] .idxattempts { background: var(--neutral-2); }
[data-theme="light"] .idxattemptrow:hover { background: var(--neutral-3); }
[data-theme="light"] .quietlink, [data-theme="light"] .yamllink { background: var(--neutral-2); }
[data-theme="light"] .exportmenuitem:hover, [data-theme="light"] .idxsortoption:hover { background: var(--neutral-3); }
.tl { min-width: 0; display: grid; grid-template-columns: minmax(0,1fr); gap: 20px; align-items: start; }
.eyebrow { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .08em; color: var(--sub); margin-bottom: var(--space-2); }
.viewpage { width: 100%; max-width: var(--content-wide); }
.viewhead { display: flex; align-items: baseline; gap: var(--space-2); min-height: 24px; margin: 0 0 var(--space-3); }
.viewtitle { margin: 0; color: var(--txt); font-size: var(--type-small); font-weight: var(--font-weight-emphasis); line-height: 1.35; }
.viewmeta { color: var(--sub); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .075em; text-transform: uppercase; }
.viewbody { min-width: 0; }
.timelinephases { min-width: 0; display: grid; grid-template-columns: minmax(0,1fr); gap: 0; }
.tlphase, .tlphasebody, .steps, .stepgroup, .stepgroupbody { min-width: 0; }
.tlphasehead { position: sticky; top: -1px; z-index: 6; width: 100%; min-height: 48px; display: flex; align-items: center; margin: 0; background: color-mix(in srgb,var(--bg) 94%,transparent); backdrop-filter: blur(10px); }
.phasecontrol { min-width: 0; min-height: 48px; flex: 1; display: flex; align-items: center; gap: 10px; padding: 7px 8px 7px 0; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.phasecontrol:hover { color: var(--txt); }
.phasecontrol:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.tlphasehead .phasedisclosure { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; border-radius: var(--r-sm); color: var(--sub); transition: color 120ms ease-out, background-color 120ms ease-out; }
.phasecontrol:hover .phasedisclosure { color: var(--txt); background: var(--button-hover); }
.tlphasehead .name, .tlphasehead .desc, .tlphasehead .phaseduration { height: 32px; display: inline-flex; align-items: center; line-height: 1; }
.tlphasehead .name { color: var(--txt); font-size: 16px; font-weight: var(--font-weight-emphasis); letter-spacing: -.01em; }
.tlphasehead .desc, .tlphasehead .phaseduration { min-width: 0; color: var(--sub); font-size: 12px; }
.tlphasehead .desc { flex-shrink: 0; margin-left: auto; padding-left: 16px; }
.tlphasehead .phaseduration { flex-shrink: 0; padding-left: 12px; padding-right: 4px; font-variant-numeric: tabular-nums; }
.tlphasehead .phasechev { width: 16px; height: 16px; display: block; transform: translateY(0) rotate(0); transform-origin: center; transition: transform 120ms ease-out; }
.phasecontrol[aria-expanded="false"] .phasechev { transform: translateY(0) rotate(-90deg); }
.tlphasebody { padding: 7px 0 18px; }
.tlphase:last-child .tlphasebody { padding-bottom: 0; }
.tlphasebody[hidden] { display: none; }
.timelinecontrols { z-index: 7; min-height: 48px; display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); flex-shrink: 0; margin: 0 0 var(--space-3); padding: 7px 10px; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg2); }
.timelinecontrols .badge { flex-shrink: 0; }
.timelinecontrols button.statusjump { display: inline-flex; align-items: center; gap: 8px; flex-shrink: 0; border: 0; padding: 0; background: transparent; color: var(--danger-text); font-family: inherit; line-height: inherit; cursor: pointer; }
.statusjumplabel { font-size: var(--type-body); font-weight: var(--font-weight-emphasis); line-height: 1; letter-spacing: .025em; text-transform: uppercase; }
.statusjumptoken { border-radius: var(--r-sm); padding: 2px 7px; font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .06em; white-space: nowrap; }
.timelinecontrols button.failedjump { color: var(--danger-text); }
.failedjump .statusjumptoken { background: color-mix(in srgb,var(--danger-border) 26%,var(--danger-surface)); color: var(--danger-text); }
.timelinecontrols button.failedjump:hover .statusjumptoken { background: color-mix(in srgb,var(--danger-border) 38%,var(--danger-surface)); }
.timelinecontrols button.selfhealjump { color: var(--status-self-healed-mark); }
.selfhealjump .statusjumptoken { background: var(--warning-surface); color: var(--status-self-healed-mark); }
.timelinecontrols button.selfhealjump:hover .statusjumptoken { background: color-mix(in srgb,var(--warning-border) 34%,var(--warning-surface)); }
.timelinecontrols button.statusjump:active { transform: translateY(1px); }
.timelinefilters { min-width: 0; margin-left: auto; display: flex; align-items: center; justify-content: flex-end; gap: 8px; }
.timelinecontrols .streammenu { right: 0; left: auto; }
.timelinecontrols .eventselect .streammenu { right: auto; left: 0; }
.retrydivider { display: flex; align-items: center; gap: 8px; padding: 8px 12px; border-top: 1px solid var(--warning-border); background: var(--warning-surface); color: var(--warning-text); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .075em; text-transform: uppercase; }
.retrydivider::after { content: ''; height: 1px; flex: 1; background: var(--warning-border); }
.selfhealpanel { margin-bottom: 14px; overflow: hidden; border: 1px solid color-mix(in srgb,var(--warning-border) 60%,var(--line2)); border-radius: var(--r-lg); background: var(--warning-surface); }
.selfhealhead { display: flex; align-items: center; gap: 10px; padding: 11px 13px; border-bottom: 1px solid color-mix(in srgb,var(--warning-border) 46%,var(--line)); }
.selfhealicon { width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; border-radius: 99px; background: var(--amber); color: #fff; font-size: 13px; font-weight: var(--font-weight-emphasis); }
.selfhealtitle { color: var(--warning-text); font-size: 13px; font-weight: var(--font-weight-emphasis); }
.selfhealcontext { margin-left: auto; color: var(--sub2); font-size: 10.5px; }
.selfhealbody { display: grid; grid-template-columns: minmax(190px,.42fr) minmax(0,1fr); }
.selfhealfield { min-width: 0; padding: 10px 13px 11px; }
.selfhealfield + .selfhealfield { border-left: 1px solid color-mix(in srgb,var(--warning-border) 40%,var(--line)); }
.selfhealfield .k { color: var(--sub); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; }
.selfhealtoolname { display: block; margin-top: 4px; color: var(--warning-text); font-size: 12.5px; font-weight: var(--font-weight-emphasis); overflow-wrap: anywhere; }
.selfhealmessage { margin-top: 4px; color: var(--txt); font-size: 12.5px; line-height: 1.45; overflow-wrap: anywhere; }
.yamllink { margin-top: 8px; width: fit-content; min-height: 28px; display: inline-flex; align-items: center; border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 5px 8px; background: var(--bg2); color: var(--sub2); font: inherit; font-size: 10.5px; font-weight: var(--font-weight-emphasis); cursor: pointer; }
.yamllink:hover { color: var(--txt); border-color: var(--run); }
.failurepanel { margin-bottom: 14px; overflow: hidden; border: 1px solid color-mix(in srgb,var(--danger-border) 64%,var(--line2)); border-radius: var(--r-lg); background: var(--danger-surface); }
.failurehead { display: flex; align-items: center; gap: 10px; padding: 11px 13px; border-bottom: 1px solid color-mix(in srgb,var(--danger-border) 48%,var(--line)); }
.failureicon { width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; border-radius: 99px; background: var(--fail); color: #fff; font-size: 13px; font-weight: var(--font-weight-emphasis); }
.failuretitle { color: var(--danger-text); font-size: 13px; font-weight: var(--font-weight-emphasis); }
.failurecontext { min-width: 0; margin-left: auto; overflow: hidden; color: var(--sub2); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.failuretool { display: grid; grid-template-columns: 112px minmax(0,1fr); gap: 12px; align-items: center; padding: 10px 13px; border-bottom: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failuretool .k { color: var(--sub); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; }
.failuretoolvalue { min-width: 0; display: flex; align-items: center; gap: 8px; }
.failuretoolname { color: var(--danger-text); font-size: 12.5px; font-weight: var(--font-weight-emphasis); }
.failuretoolargs { color: var(--sub2); font-size: 10.5px; }
.failuretool .yamllink { margin: 0 0 0 auto; flex-shrink: 0; }
.failurebody { display: grid; grid-template-columns: minmax(190px,.42fr) minmax(0,1fr); }
.failurefield { min-width: 0; padding: 10px 13px 11px; }
.failurefield + .failurefield { border-left: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failurefield .k, .failurestack summary { color: var(--sub); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; }
.failuretype { display: block; margin-top: 4px; color: var(--danger-text); font-size: 11.5px; overflow-wrap: anywhere; }
.failuremessage { margin-top: 4px; color: var(--txt); font-size: 12.5px; line-height: 1.45; overflow-wrap: anywhere; }
.failureprose + .failurejson, .failurejson + .failureprose { margin-top: 8px; }
.failurejson { max-height: 280px; margin: 8px 0 0; overflow: auto; border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 9px 11px; background: var(--code-surface); color: var(--code-text); font-size: 11px; line-height: 1.5; white-space: pre; }
.failurestack { border-top: 1px solid color-mix(in srgb,var(--danger-border) 42%,var(--line)); }
.failurestack summary { display: flex; align-items: center; gap: 8px; padding: 9px 13px; cursor: pointer; list-style: none; }
.failurestack summary::-webkit-details-marker { display: none; }
.failurestack summary::before { content: '›'; color: var(--sub2); font-size: 17px; line-height: 1; transform: rotate(90deg); transition: transform 120ms ease-out; }
.failurestack:not([open]) summary::before { transform: rotate(0deg); }
.failurestack .frames { margin-left: auto; color: var(--sub); font-size: 10px; font-weight: var(--font-weight-emphasis); font-variant-numeric: tabular-nums; letter-spacing: 0; text-transform: none; }
.failurestack pre { max-height: 210px; margin: 0; border: 0; border-top: 1px solid color-mix(in srgb,var(--danger-border) 32%,var(--line)); border-radius: 0; background: var(--code-surface); color: var(--code-text); }
.stepgroupbody > .failurepanel { margin: 0; border: 0; border-top: 1px solid color-mix(in srgb,var(--danger-border) 64%,var(--line2)); border-radius: 0; }
.stepgroupbody > .selfhealpanel { margin: 0; border: 0; border-top: 1px solid color-mix(in srgb,var(--warning-border) 60%,var(--line2)); border-radius: 0; }
.tlphase.trailhead .steps { border-color: color-mix(in srgb,var(--trail-mark) 52%,var(--line2)); }
/* overflow: clip (not hidden) — hidden would make .steps a scroll container and break the sticky step headers. */
.steps { border: 1px solid var(--line); border-radius: var(--r-lg); overflow: clip; background: var(--bg2); box-shadow: inset 0 1px rgba(255,255,255,.025); }
.tlphase:not(.trailhead) .steps { display: grid; grid-template-columns: minmax(0,1fr); gap: 12px; overflow: visible; border: 0; border-radius: 0; background: transparent; box-shadow: none; }
.tlphase:not(.trailhead) .stepgroup { overflow: clip; border: 1px solid var(--line); border-radius: var(--r-lg); background: var(--bg2); box-shadow: inset 0 1px rgba(255,255,255,.025); }
.tlphase:not(.trailhead) .stepgroup > .grphdr { border-top: 0; }
.stepgroup { position: relative; }
.stepgroup.failed { background: var(--danger-surface); }
.stepgroup.failed .grphdr { background: color-mix(in srgb,var(--danger-surface) 80%,var(--bg3)); }
.stepgroup.failed .grphdr .chip { color: var(--danger-text); background: color-mix(in srgb,var(--danger-border) 26%,var(--danger-surface)); }
.stepgroup.failed .step { background-color: transparent; }
.stepgroup.selfhealed { background: var(--bg2); }
.stepgroup.selfhealed .grphdr { background: var(--bg3); }
.stepgroup.selfhealed .grphdr .chip { color: var(--warning-text); background: var(--warning-surface); }
.stepgroup.selfhealed .step { background-color: var(--bg2); }
.grphdr { position: sticky; top: 39px; z-index: 5; width: 100%; padding: 12px 14px 11px; background: var(--bg3); color: inherit; border: 0; border-top: 1px solid var(--line2); display: grid; grid-template-columns: auto auto auto 1fr; align-items: center; gap: 8px; font: inherit; text-align: left; cursor: pointer; }
.grphdr:hover { background: color-mix(in srgb,var(--bg3) 84%,white); }
.grphdr:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.steps > .grphdr:first-child, .stepgroup:first-child > .grphdr { border-top: none; }
.grphdr .chip, .galchip { font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .06em; color: var(--step-token-text); background: var(--step-token-surface); border-radius: var(--r-sm); padding: 2px 7px; white-space: nowrap; flex-shrink: 0; }
.grphdr.trailhead .chip { color: var(--trail-text); background: var(--trail-surface); }
.grphdr .dot { width: 8px; height: 8px; border-radius: 99px; }
.grphdr .lbl { grid-column: 1 / -1; min-width: 0; display: block; font-size: 14px; font-weight: var(--font-weight-emphasis); margin-top: 4px; line-height: 1.4; overflow-wrap: anywhere; }
.step { display: flex; flex-wrap: wrap; gap: 10px; padding: 10px 14px; cursor: pointer; border-top: 1px solid var(--line); transition: background-color 120ms ease-out, box-shadow 120ms ease-out; }
.step.child { padding-left: 22px; }
.step:hover, .step.sel:hover { background: var(--accent-surface); }
.step.sel { background: transparent; box-shadow: inset 3px 0 var(--run); }
.stepgroup.failed .step.sel { background: transparent; }
.stepgroup.failed .step:hover, .stepgroup.failed .step.sel:hover { background: color-mix(in srgb,var(--danger-border) 18%,var(--danger-surface)); }
.stepgroup.selfhealed .step.selfheal { background: var(--warning-surface); }
.stepgroup.selfhealed .step.sel:not(.selfheal) { background: transparent; }
.stepgroup.selfhealed .step:hover { background: var(--warning-surface); }
.step .num { font-size: 11px; color: var(--sub); width: 20px; text-align: right; flex-shrink: 0; font-variant-numeric: tabular-nums; }
.step .ic { width: 14px; height: 14px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; margin-top: 2px; font-size: 14px; font-weight: var(--font-weight-emphasis); line-height: 1; }
.step .ic.dot::before { content: ''; width: 9px; height: 9px; border-radius: 99px; background: var(--icon-color); }
.step .ic.tap { color: var(--sub2); font-size: 13px; }
.step .ic.llm { color: var(--ai); }
.step .ic.llm svg { width: 14px; height: 14px; display: block; }
.step .ic.verify { color: var(--pass); }
.step .ic.failure { color: var(--fail); }
.step .lbl { min-width: 0; font-size: 13px; font-weight: var(--font-weight-emphasis); overflow-wrap: anywhere; }
.step .tl-tool { font-size: 11px; color: var(--sub); margin-top: 2px; word-break: break-word; }
.step .note { font-size: 11.5px; color: var(--sub2); margin-top: 3px; line-height: 1.4; overflow-wrap: anywhere; }
/* A full-width flex row below the [num][icon][label][time] line: the kid durations right-align
   to the same edge as the step's own time, and the list's left aligns with the label text. */
.kids { min-width: 0; max-width: 100%; flex-basis: 100%; margin-top: -4px; padding-left: 54px; }
.step.child .kids { padding-left: 24px; }
.kids .kid { display: flex; gap: 6px; align-items: baseline; font-size: 11.5px; margin-top: 3px; min-width: 0; cursor: pointer; border-radius: 6px; padding: 2px 6px; margin-left: -6px; }
.kids .kid:hover { background: var(--bg3); }
.kids .kid.sel { background: var(--accent-surface); box-shadow: inset 2px 0 0 var(--run); }
/* The selected row's / dispatch's full call content, as trail-file YAML (WASM-report parity). */
.toolargs { margin: 6px 0 2px; padding: 8px 10px; font-size: 11px; line-height: 1.5; color: var(--text); background: var(--bg2); border: 1px solid var(--line); border-radius: 8px; white-space: pre; overflow: auto; max-height: 260px; }
.kids .toolargs { margin-left: 0; }
.kids .kt { flex: 1; color: var(--sub); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.kids .kcount { color: var(--sub); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.kids .kms { margin-left: auto; color: var(--sub); font-variant-numeric: tabular-nums; flex-shrink: 0; }
.kids .kid.bad, .kids .kid.bad .kt, .kids .bad { color: var(--fail); }
.kids .kiderr { color: var(--fail); font-size: 11px; margin-top: 2px; line-height: 1.4; overflow-wrap: anywhere; opacity: .85; }
.kidcode, .failurecode { display: inline-block; font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .06em; color: var(--danger-text); background: color-mix(in srgb,var(--danger-border) 26%,var(--danger-surface)); border-radius: var(--r-sm); padding: 1.5px 6px; white-space: nowrap; flex-shrink: 0; }
.kids .kiderr .kidcode { margin-right: 6px; }
.kids .kidsummary .kidcode { margin-left: 6px; }
.kids .kidsummary { cursor: pointer; font-size: 11.5px; color: var(--sub); user-select: none; }
.kids .kidsummary::before { content: '▸'; display: inline-block; margin-right: 5px; transition: transform .12s ease; }
.kids .kidsummary.open::before { transform: rotate(90deg); }
.kids .kidsummary .mono { color: var(--text); }
.timeline-list { min-width: 0; grid-row: 2; }
.timelinescroll { min-width: 0; }
.preview { position: static; grid-row: 1; min-width: 0; display: flex; align-items: center; justify-content: center; }
.devicecolumn { width: fit-content; max-width: 100%; min-height: 0; display: flex; flex-direction: column; align-items: stretch; gap: var(--space-2); }
.deviceplayer { width: fit-content; max-width: 100%; overflow: hidden; border: 2px solid var(--player-line); border-radius: var(--r-lg); background: var(--raised); box-shadow: var(--shadow-device); }
.deviceplayer.device-ios { border-radius: var(--r-lg); }
.deviceplayer.empty { width: min(360px,100%); }
.previewactions { display: flex; justify-content: center; }
button.btn.previewinspect { flex-shrink: 0; font-weight: var(--font-weight-strong); }
.previewinspecticon { width: 13px; height: 13px; flex-shrink: 0; }
.shotwrap { width: fit-content; max-width: 100%; margin: 0; }
.shot { max-width: 100%; max-height: calc(100vh - 334px); background: #000; border: 0; display: block; cursor: zoom-in; }
.tlvframe { max-width: 100%; height: calc(100vh - 386px); min-height: 240px; aspect-ratio: 1/2; background-color: #000; background-repeat: no-repeat; display: block; }
.noshot { width: 100%; aspect-ratio: 1/2; border: 0; display: flex; align-items: center; justify-content: center; color: var(--sub); font-size: 12px; text-align: center; padding: 20px; }
.scrubtransport { flex-shrink: 0; display: inline-flex; align-items: stretch; overflow: hidden; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--bg2); }
.scrubtransport button.timelinecontrol { width: 32px; height: 30px; min-width: 32px; min-height: 30px; display: inline-flex; align-items: center; justify-content: center; padding: 0; border: 0; border-left: 1px solid var(--line2); border-radius: 0; background: transparent; color: var(--sub2); cursor: pointer; }
.scrubtransport button.timelinecontrol:first-child { border-left: 0; }
.scrubtransport button.timelinecontrol.play { color: var(--txt); }
.transporticon { width: 18px; height: 18px; display: inline-flex; align-items: center; justify-content: center; color: currentColor; }
.transporticon.direction::before { content: ''; width: 9px; height: 9px; box-sizing: border-box; border-bottom: 1.75px solid currentColor; border-left: 1.75px solid currentColor; border-radius: 1px; transform: rotate(45deg); }
.scrubtransport #next .transporticon.direction::before { transform: rotate(225deg); }
.transporticon.playicon { margin-left: 0; }
.transporticon.stopicon::before { content: ''; width: 9px; height: 9px; border-radius: 1px; background: currentColor; }
button.btn { min-height: 34px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: var(--r-sm); padding: 6px 11px; font-size: 12.5px; font-weight: var(--font-weight-emphasis); cursor: pointer; transition: color 120ms ease-out, background-color 120ms ease-out, border-color 120ms ease-out, transform 100ms ease-out; }
button.btn:disabled { opacity: .4; cursor: default; }
button.btn:not(:disabled):hover { border-color: var(--run); background: var(--button-hover); }
.scrubtransport button.timelinecontrol:not(:disabled):hover { background: var(--button-hover); color: var(--txt); }
.scrubtransport button.timelinecontrol.play:not(:disabled):hover { background: var(--button-hover); color: var(--txt); }
.scrubtransport button.timelinecontrol:focus-visible { position: relative; outline: 2px solid var(--focus); outline-offset: -3px; }
.scrubtransport button.timelinecontrol:disabled { color: var(--disabled-text); cursor: default; }
button.btn:not(:disabled):active { transform: translateY(1px); }
button.btn.play { border-color: var(--run); background: var(--accent-surface); color: var(--run); min-width: 84px; }
.card { border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg2); padding: 10px 13px; }
.totals { display: flex; gap: 16px; flex-wrap: wrap; margin-top: 6px; }
.totals .n { font-size: 13px; font-weight: var(--font-weight-emphasis); font-variant-numeric: tabular-nums; }
.totals .t { font-size: 10.5px; color: var(--sub); }
/* Which model(s) produced the session's calls, in the repo's provider/model form. */
.llmmodels { display: flex; align-items: baseline; flex-wrap: wrap; gap: 8px; margin-top: 10px; padding-top: 9px; border-top: 1px solid var(--line); font-size: 11px; }
.llmmodels .k { font-size: 10.5px; font-weight: var(--font-weight-emphasis); color: var(--sub); }
.llmmodels .v { font-weight: var(--font-weight-emphasis); overflow-wrap: anywhere; }
.llmbreak { margin-top: 20px; }
.llmbreakbar { display: flex; height: 10px; border-radius: 5px; overflow: hidden; margin: 10px 0 12px; background: var(--bg3); }
.llmbreakbar span { display: block; height: 100%; min-width: 2px; }
.llmbreakcat { display: flex; align-items: baseline; gap: 10px; font-size: 11.5px; padding: 3px 0; }
.llmbreakdot { width: 8px; height: 8px; border-radius: 50%; align-self: center; flex: none; }
.llmbreaklabel { min-width: 110px; font-weight: var(--font-weight-emphasis); }
.llmbreakcat .llmbreaktokens { min-width: 64px; text-align: right; font-variant-numeric: tabular-nums; font-weight: var(--font-weight-emphasis); }
.llmbreakcat .llmbreakpct { min-width: 44px; text-align: right; color: var(--sub); font-variant-numeric: tabular-nums; }
.llmbreakcat .llmbreakcount { color: var(--sub); }
.llmbreaktotal { margin-top: 8px; font-size: 10.5px; color: var(--sub); }
.llmbreaknote { margin-top: 6px; font-size: 10.5px; line-height: 1.5; color: var(--sub2); }
.llmtablewrap { margin-top: 20px; overflow-x: auto; }
.llmtable { width: 100%; border-collapse: collapse; margin-top: 8px; font-size: 11.5px; }
.llmtable th { text-align: left; font-size: 10.5px; color: var(--sub); font-weight: var(--font-weight-emphasis); padding: 6px 8px; border-bottom: 1px solid var(--line2); white-space: nowrap; }
.llmtable td { padding: 6px 8px; border-bottom: 1px solid var(--line); font-variant-numeric: tabular-nums; vertical-align: top; }
.llmtable th.num, .llmtable td.num { text-align: right; }
.llmtable td.llmreq { font-weight: var(--font-weight-emphasis); }
.llmtable td.llmmodel { color: var(--sub); font-size: 10.5px; max-width: 190px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.llmtable tr.llmrow { cursor: pointer; }
.llmtable tr.llmrow:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.llmtable tr.llmrow:hover td { background: var(--bg3); }
.llmtable tr.llmrow.sel td { background: var(--bg3); }
.llmtable .llmcached { display: block; font-size: 10px; color: var(--sub); }
/* ── LLM transcript lightbox + its triggers ──────────────────────────────────────────────── */
/* Trigger buttons: a sibling of the interactive row it belongs to (never nested inside a
   role="button" row), mirroring the WASM report's per-row Chat History icon. */
.txopenbtn { flex: none; display: inline-flex; align-items: center; justify-content: center; width: 26px; height: 26px; padding: 0; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--bg2); color: var(--sub); cursor: pointer; }
.txopenbtn svg { width: 14px; height: 14px; }
.txopenbtn:hover { background: var(--bg3); color: var(--txt); }
.txopenbtn:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
/* Timeline LLM-call rows: row + trigger side by side; the wrap takes over the row separator. */
.steprow { display: flex; align-items: stretch; border-top: 1px solid var(--line); background: var(--ai-surface); transition: background-color 120ms ease-out; }
.steprow > .step { flex: 1; min-width: 0; border-top: none; }
.steprow > .step.llmturn, .stepgroup.failed .steprow > .step.llmturn, .stepgroup.selfhealed .steprow > .step.llmturn { background: transparent; }
.steprow:hover { background: color-mix(in srgb,var(--ai) 12%,var(--ai-surface)); }
.steprow:hover > .step.llmturn { background: transparent; }
.steprow .txopenbtn { align-self: center; margin: 0 10px 0 4px; }
.llmtable td.txcell { text-align: center; }
.srlabel { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }
/* Objective groups — the per-request table groups by the objective each call ran under, with
   per-objective subtotals (which objective burned the budget). Each objective is its own <tbody>
   so the nesting is structural: a banded header row states the objective, a hairline rail runs the
   height of the group, and the calls inside are inset from it. */
.llmgroupmeta { color: var(--sub); font-weight: var(--font-weight-emphasis); font-size: 10.5px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.llmtable tbody.llmgroup + tbody.llmgroup tr.llmgrouprow td { padding-top: 14px; }
.llmtable tr.llmgrouprow td { background: var(--bg3); border-bottom: 1px solid var(--line2); font-weight: var(--font-weight-emphasis); font-size: 11px; }
.llmtable tr.llmgrouprow td .lbl { display: block; max-width: 62ch; line-height: 1.4; overflow-wrap: anywhere; }
.llmtable tr.llmgrouprow td .llmgroupmeta { display: block; margin-top: 3px; }
/* Containment: a hairline rail runs the height of the group (one segment per row, so it is
   continuous) with a short elbow into each inset request cell. */
.llmtable tr.llmrow.grouped td.llmreq { padding-left: 26px; position: relative; }
.llmtable tr.llmrow.grouped td.llmreq::before { content: ""; position: absolute; left: 11px; top: 0; bottom: -1px; width: 1px; background: var(--line2); }
.llmtable tr.llmrow.grouped td.llmreq::after { content: ""; position: absolute; left: 11px; top: 13px; width: 9px; height: 1px; background: var(--line2); }
.llmtable tbody.llmgroup tr.llmrow.grouped:last-child td.llmreq::before { bottom: 50%; }
/* The dialog itself: modal over the current view (the report's zoom-overlay language); dismissed
   by Escape or the close button only — scrim clicks are inert, like the WASM Chat History dialog. */
.txoverlay { position: fixed; inset: 0; background: rgba(2,6,12,.72); display: flex; align-items: center; justify-content: center; z-index: 99; backdrop-filter: blur(4px); }
.txpanel { display: flex; flex-direction: column; width: min(940px, 94vw); max-height: 90vh; background: var(--bg); border: 1px solid var(--line2); border-radius: var(--r-lg); box-shadow: 0 24px 64px rgba(0,0,0,.45); overflow: hidden; }
.txpanelhead { display: flex; align-items: flex-start; gap: 12px; padding: 14px 16px; border-bottom: 1px solid var(--line); background: var(--bg2); }
.txpaneltitle { flex: 1; min-width: 0; }
.txpaneltitle .h { font-size: 13px; font-weight: var(--font-weight-emphasis); }
.txpaneltitle .txof { color: var(--sub); font-weight: var(--font-weight-emphasis); }
.txpanelmeta { display: flex; gap: 14px; flex-wrap: wrap; margin-top: 4px; font-size: 11px; color: var(--sub); font-variant-numeric: tabular-nums; }
.txclose { flex: none; width: 28px; height: 28px; padding: 0; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--bg2); color: var(--sub); font-size: 16px; line-height: 1; cursor: pointer; }
.txclose:hover { background: var(--bg3); color: var(--txt); }
.txclose:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.txscroll { overflow: auto; padding: 10px 16px 16px; }
.txnote { color: var(--sub); font-size: 12px; padding: 8px 0; }
/* Message bubbles (rendered only inside the dialog) — conversational, chat-app style. Two
   voices: what the model authored (assistant + its tool calls) sits left with the --ai accent;
   what the agent/harness supplied (user turns + tool results) sits right with the blue accent;
   the system prompt is a quiet full-width preamble. Same split the WASM LlmMessageComposable
   drew with avatars + user-right alignment. */
.txmsg { border: 1px solid var(--line2); border-radius: var(--r-lg); background: var(--bg2); margin-top: 8px; overflow: hidden; }
.txmsg.voice-llm { max-width: 86%; margin-right: auto; background: rgba(181,140,255,.08); border-color: rgba(181,140,255,.32); border-bottom-left-radius: 4px; }
.txmsg.voice-user { max-width: 86%; margin-left: auto; background: rgba(77,139,255,.08); border-color: rgba(77,139,255,.30); border-bottom-right-radius: 4px; }
.txmsg.voice-sys { background: var(--bg2); border-color: var(--line); color: var(--sub); }
.txmsg.voice-llm pre, .txmsg.voice-user pre { background: transparent; }
.txavatar { width: 20px; height: 20px; border-radius: 50%; display: inline-flex; align-items: center; justify-content: center; flex: none; align-self: center; font-size: 8.5px; font-weight: var(--font-weight-emphasis); letter-spacing: 0; }
.txavatar.llm { background: rgba(181,140,255,.22); color: var(--ai); }
.txavatar.user { background: rgba(77,139,255,.22); color: var(--run); }
.txavatar.sys { background: var(--bg3); color: var(--sub); }
/* Verbatim escape hatch for cleaned tool-result envelopes. */
.txraw summary { font-size: 10px; color: var(--sub2); cursor: pointer; padding: 4px 10px 6px; list-style: none; }
.txraw summary::-webkit-details-marker { display: none; }
.txraw summary::before { content: '› '; }
.txraw[open] summary::before { content: '⌄ '; }
.txraw pre { border-top: 1px dashed var(--line); }
.txmsg .txhead { display: flex; align-items: baseline; gap: 8px; padding: 8px 10px 0; }
.txmsg summary { display: flex; align-items: baseline; gap: 8px; padding: 8px 10px; cursor: pointer; list-style: none; }
.txmsg summary::-webkit-details-marker { display: none; }
.txmsg summary::after { content: '›'; color: var(--sub2); font-size: 15px; line-height: 1; margin-left: auto; transition: transform 120ms ease-out; }
.txmsg[open] summary::after { transform: rotate(90deg); }
/* Inset ring: .txmsg clips overflow, so an outset ring on the summary would be invisible. */
.txmsg summary:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.txrole { font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; color: var(--sub); white-space: nowrap; }
.txrole.user { color: var(--run); }
.txrole.assistant { color: var(--ai); }
.txrole.tool { color: var(--pass); }
.txrole.system { color: var(--sub2); }
/* Tool names keep their authored casing — never run through the role label's uppercase. */
.txtool { font-size: 11px; color: var(--txt); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.txpeek { font-size: 11px; color: var(--sub2); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; flex: 1; }
/* The one-line preview duplicates the expanded body — hide it while open. */
.txmsg[open] summary .txpeek { display: none; }
.txlen { font-size: 10px; color: var(--sub2); white-space: nowrap; }
.txmsg pre { margin: 0; border: 0; border-radius: 0; max-height: 420px; background: var(--code-surface); color: var(--code-text); }
/* An expander the reader opened means "show me everything": uncap — the dialog body scrolls. */
details.txmsg[open] pre { max-height: none; }
details.txmsg pre { border-top: 1px solid var(--line); }
pre { margin: 0; font-size: 11px; line-height: 1.5; color: var(--sub2); white-space: pre-wrap; word-break: break-word; max-height: 260px; overflow: auto; background: var(--bg); border: 1px solid var(--line); border-radius: var(--r-md); padding: 8px 10px; }
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
.zoomnav { position: fixed; top: 50%; width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; border: 1px solid var(--line2); border-radius: var(--r-md); background: color-mix(in srgb,var(--raised) 90%,transparent); color: var(--txt); font-family: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, sans-serif; font-size: 21px; font-weight: var(--font-weight-emphasis); line-height: 1; cursor: pointer; transform: translateY(-50%); box-shadow: var(--shadow-raised); }
.zoomnav.prev { left: 24px; }
.zoomnav.next { right: 24px; }
.zoomnav:hover { border-color: var(--run); background: rgba(34,40,50,.96); }
.zoomnav:disabled { opacity: 0; pointer-events: none; }
/* Step-label column beside the zoomed screenshot (centered two-column layout). The scrim is dark
   in both themes, so the column's text colors are fixed light rather than theme vars. */
.zoom.haslist img { max-width: calc(92vw - 320px); }
.zoomsteps { position: relative; width: 280px; max-height: 88vh; overflow-y: auto; overscroll-behavior: contain; display: flex; flex-direction: column; gap: 2px; cursor: default; }
.zoomstep { display: flex; flex-direction: column; align-items: flex-start; gap: 4px; flex-shrink: 0; padding: 10px 12px; border: 0; border-radius: var(--r-md); background: transparent; font: inherit; text-align: left; white-space: normal; opacity: .2; cursor: pointer; transition: opacity 120ms ease-out; }
.zoomstep:hover { opacity: .6; }
.zoomstep.cur { opacity: 1; cursor: default; }
.zoomstep:focus-visible { outline: 2px solid #6aa6ff; outline-offset: -2px; }
.zoomstepchip { font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .06em; color: #cdb8ff; background: rgba(133,102,255,.22); border-radius: var(--r-sm); padding: 2px 7px; white-space: nowrap; }
.zoomsteplabel { color: #fff; font-size: 12.5px; font-weight: var(--font-weight-emphasis); line-height: 1.4; word-break: break-word; }
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
.idxsummary .stat { color: var(--sub2); font-size: 13px; font-weight: var(--font-weight-emphasis); white-space: nowrap; }
.idxsummary .stat strong { color: var(--txt); font-size: 16px; }
.idxsummary .stat.pass strong { color: var(--pass); }
.idxsummary .stat.selfheal strong { color: var(--amber); }
.idxsummary .stat.fail strong { color: var(--fail); }
.indexcontext { display: grid; grid-template-columns: minmax(0,1fr) auto; align-items: end; gap: var(--space-5); max-width: var(--content-wide); margin-top: var(--space-4); }
.indexmeta { margin-top: 0; }
.indexmetalink { color: inherit; text-decoration: none; text-underline-offset: 2px; }
.indexmetalink:hover { color: var(--run); text-decoration: underline; }
.indexmetalink:focus-visible { border-radius: 2px; outline: 2px solid var(--focus); outline-offset: 2px; }
.idxfilter { display: grid; grid-template-columns: minmax(160px,200px) 112px 104px; align-items: center; gap: 10px; width: min(100%,436px); margin: 0; }
.idxsearch { position: relative; min-width: 0; }
.idxsearchicon { position: absolute; z-index: 1; left: 11px; top: 50%; width: 15px; height: 15px; color: var(--sub); pointer-events: none; transform: translateY(-50%); }
.idxfilter input { width: 100%; min-width: 0; min-height: 34px; background: var(--bg2); border: 1px solid var(--line2); color: var(--txt); border-radius: var(--r-md); padding: 6px 30px 6px 34px; font: inherit; font-size: var(--type-small); outline: none; transition: border-color 100ms ease-out,box-shadow 100ms ease-out,background-color 100ms ease-out; }
.idxfilter input::placeholder { color: var(--sub); opacity: 1; }
.idxfilter input:hover { border-color: var(--neutral-7); }
.idxfilter input:focus-visible, .idxsort summary:focus-visible { border-color: var(--run); box-shadow: 0 0 0 2px color-mix(in srgb,var(--run) 18%,transparent); outline: none; }
.idxsort { position: relative; color: var(--sub2); font-size: var(--type-small); font-weight: var(--font-weight-emphasis); }
.idxgroup { width: 112px; }
.idxorder { width: 104px; }
.idxsort summary { min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: var(--space-2); list-style: none; border: 1px solid var(--line2); border-radius: var(--r-md); padding: 5px 10px; background: var(--bg2); cursor: pointer; transition: color 100ms ease-out,background-color 100ms ease-out,border-color 100ms ease-out; }
.idxsort summary::-webkit-details-marker { display: none; }
.idxsort summary:hover, .idxsort[open] summary { border-color: var(--run); background: var(--button-hover); color: var(--txt); }
.idxsortvalue { min-width: 0; display: inline-flex; align-items: center; gap: 7px; }
.idxsortvalue > span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.idxsorticon { width: 15px; height: 15px; flex: none; color: var(--sub); }
.idxsortchev { width: 7px; height: 7px; flex-shrink: 0; margin: -3px 2px 0 0; border-right: 1.5px solid currentColor; border-bottom: 1.5px solid currentColor; color: var(--sub); transform: rotate(45deg); transition: transform 100ms ease-out; }
.idxsort[open] .idxsortchev { margin-top: 3px; transform: rotate(225deg); }
.idxsortmenu { position: absolute; z-index: 30; top: calc(100% + 6px); right: 0; width: 100%; min-width: 128px; display: grid; gap: 1px; padding: 6px; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--raised); box-shadow: var(--shadow-raised); transform-origin: top right; animation: idxsortin 120ms cubic-bezier(.16,1,.3,1); }
.idxsortmenulabel { margin: 0 4px 4px; padding: 3px 6px 7px; border-bottom: 1px solid var(--line); color: var(--sub); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .04em; }
.idxsortoption { position: relative; min-height: 32px; display: flex; align-items: center; width: 100%; border: 0; border-radius: var(--r-sm); padding: 6px 10px 6px 30px; background: transparent; color: var(--sub2); font: inherit; font-size: var(--type-small); font-weight: var(--font-weight-body); text-align: left; cursor: pointer; }
.idxsortoption:hover, .idxsortoption:focus-visible { background: var(--button-hover); color: var(--txt); outline: none; }
.idxsortoption[aria-selected="true"] { color: var(--txt); font-weight: var(--font-weight-emphasis); }
.idxsortoption[aria-selected="true"]::before { content: '✓'; position: absolute; left: 10px; color: var(--run); font-size: 12px; }
@keyframes idxsortin { from { opacity: 0; transform: translateY(-4px) scale(.98); } to { opacity: 1; transform: translateY(0) scale(1); } }
.idxsection + .idxsection { margin-top: var(--space-4); }
.idxsectionhead { display: flex; align-items: center; gap: 8px; margin: 0 0 7px 2px; color: var(--sub2); font-size: var(--type-caption); font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; }
.idxsectionhead::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: var(--sub); }
.idxsectionhead.failed::before { background: var(--status-failed-mark); }
.idxsectionhead.selfheal::before { background: var(--status-self-healed-mark); }
.idxsectionhead.passed::before { background: var(--status-passed-mark); }
.idxsectioncount { color: var(--sub); font-weight: var(--font-weight-emphasis); letter-spacing: 0; text-transform: none; }
.idx { border: 1px solid var(--line); border-radius: var(--r-md); overflow: hidden; background: var(--bg2); max-width: var(--content-wide); }
.idxrow { display: grid; grid-template-columns: 12px minmax(220px,1fr) 256px 20px; align-items: center; gap: var(--space-4); padding: var(--space-3) var(--space-4); border-top: 1px solid var(--line); cursor: pointer; transition: background-color 120ms ease-out, box-shadow 120ms ease-out; }
.idxrow[hidden] { display: none; }
.idxrow:first-child { border-top: none; }
.idxrow.firstmatch { border-top: none; }
.idxrow:hover { background: var(--bg3); }
.idxrow:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxstatus { width: 12px; height: 12px; display: flex; align-items: center; justify-content: center; }
.idxstatusdot { width: 7px; height: 7px; border-radius: 50%; background: var(--sub); }
.idxstatusdot.failed { background: var(--status-failed-mark); }
.idxstatusdot.selfheal { background: var(--status-self-healed-mark); }
.idxstatusdot.passed { background: var(--status-passed-mark); }
.idxmain { min-width: 0; }
.idxrow .nm { font-size: 14px; font-weight: var(--font-weight-emphasis); min-width: 0; word-break: break-word; }
.idxowner { margin-top: 2px; color: var(--sub); font-size: var(--type-micro); font-weight: var(--font-weight-body); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.idxstats { margin-top: 3px; color: var(--sub); font-size: var(--type-caption); font-weight: var(--font-weight-body); font-variant-numeric: tabular-nums; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.idxentry { border-top: 1px solid var(--line); }
.idxentry:first-child { border-top: 0; }
.idxentry.firstmatch { border-top: 0; }
.idxentry > .idxrow { border-top: 0; }
.idxmatrixrow { grid-template-columns: minmax(220px,1fr) minmax(0,auto); cursor: default; }
.idxmatrixrow .nm { font-weight: var(--font-weight-emphasis); }
.idxcells { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 8px; }
.idxcell { position: relative; width: 164px; box-sizing: border-box; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--bg3); transition: border-color 120ms ease-out, background-color 120ms ease-out; }
.idxcell:hover { border-color: var(--run); }
.idxcellopen { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 4px 8px; width: 100%; box-sizing: border-box; margin: 0; padding: 9px 14px; border: 0; border-radius: var(--r-sm); background: none; font: inherit; color: inherit; text-align: left; cursor: pointer; }
.idxcellopen:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxcell .pk { font-size: 10px; font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; color: var(--sub); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.idxcell .pv { display: flex; align-items: center; gap: 6px; min-width: 0; font-size: 12px; font-weight: var(--font-weight-emphasis); color: var(--sub2); font-variant-numeric: tabular-nums; white-space: nowrap; }
.idxcell .pv .idxstatusdot { flex: none; }
.idxcell .pv .pvtxt { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.idxcell .pcounts { justify-self: end; text-align: right; font-size: var(--type-micro); color: var(--sub); font-variant-numeric: tabular-nums; white-space: nowrap; }
.idxcell .idxstatusdot { box-shadow: none; }
.idxcell.failed { border-color: var(--danger-border); background: var(--danger-surface); }
.idxcell.selfheal { border-color: var(--warning-border); background: var(--bg2); }
[data-theme="dark"] .idxcell.failed { border-color: color-mix(in srgb,var(--danger-border) 58%,var(--line2)); }
[data-theme="dark"] .idxcell.selfheal { border-color: color-mix(in srgb,var(--warning-border) 28%,var(--line2)); }
[data-theme="dark"] .idxcell.failed .idxcellchev { border-left-color: color-mix(in srgb,var(--danger-border) 58%,var(--line2)); }
[data-theme="dark"] .idxcell.selfheal .idxcellchev { border-left-color: color-mix(in srgb,var(--warning-border) 28%,var(--line2)); }
.idxcell.missing { display: flex; flex-direction: column; gap: 4px; padding: 9px 14px; border-style: dashed; background: transparent; }
.idxcell.missing:hover { border-color: var(--line2); }
.idxcell.missing .pv { color: var(--sub); opacity: .7; }
.idxcell.retried .idxcellopen { padding-right: 40px; }
.idxcellcount { font-size: 11px; font-weight: var(--font-weight-emphasis); line-height: 1; color: var(--sub); font-variant-numeric: tabular-nums; }
/* The rail stacks the attempt history over the expand glyph (::after), so the history reads as
   "what this control reveals" and the main button's stats line keeps its full width. */
.idxcellchev { position: absolute; right: 0; top: 0; bottom: 0; width: 34px; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 5px; border: 0; border-left: 1px solid var(--line2); border-radius: 0 var(--r-sm) var(--r-sm) 0; padding: 0; background: transparent; cursor: pointer; }
.idxcellchev:hover { background: var(--button-hover); }
.idxcellchev:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxcellchev::after { content: ''; width: 7px; height: 7px; border-right: 1.75px solid currentColor; border-bottom: 1.75px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out, color 120ms ease-out; }
.idxcellchev.open::after { color: var(--focus); transform: rotate(225deg) translate(-1px,-1px); }
.idxcell.failed .idxcellchev { border-left-color: var(--danger-border); }
.idxcell.selfheal .idxcellchev { border-left-color: var(--warning-border); }
.idxatthead { padding: 10px var(--space-4) 3px 28px; font-size: 10px; font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; color: var(--sub); }
.idxmatrixattempts .idxattemptrow { padding-left: 28px; border-top: 0; min-height: 44px; }
.idxmatrixattempts .idxattemptlabel { font-weight: var(--font-weight-emphasis); }
.idxmatrixattempts .idxattemptstatus { font-weight: var(--font-weight-emphasis); }
.idxfacts { display: grid; grid-template-columns: 104px 60px 60px; gap: 16px; align-items: center; }
.idxfact .k { color: var(--sub); font-size: var(--type-micro); letter-spacing: .08em; text-transform: uppercase; }
.idxfact .v { color: var(--sub2); font-size: var(--type-caption); font-weight: var(--font-weight-emphasis); margin-top: 1px; white-space: nowrap; }
.quietlink { min-height: 32px; display: inline-flex; align-items: center; color: var(--sub2); border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 5px 9px; font-size: 11px; font-weight: var(--font-weight-emphasis); text-decoration: none; background: var(--bg2); }
.quietlink:hover { color: var(--txt); border-color: var(--run); background: var(--accent-surface); }
.quietlink:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.idxrow .arr { color: var(--sub); font-size: 14px; align-self: center; }
.idxretry { border-top: 1px solid var(--line); }
.idxretry:first-child { border-top: 0; }
.idxretry > summary { list-style: none; border-top: 0; }
.idxretry > summary::-webkit-details-marker { display: none; }
.idxretryrow { grid-template-columns: auto minmax(220px,1fr) 256px 20px; }
.idxretrydots { display: inline-flex; align-items: center; gap: 5px; padding-inline: 1px; }
.idxretrydots .idxstatusdot { flex-shrink: 0; }
.idxretrychev { width: 8px; height: 8px; justify-self: center; border-right: 1.75px solid currentColor; border-bottom: 1.75px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out,color 120ms ease-out; }
.idxretry[open] .idxretrychev { color: var(--focus); transform: rotate(225deg) translate(-1px,-1px); }
.idxattempts { background: var(--bg2); border-top: 1px solid var(--line); }
.idxattemptrow { display: grid; grid-template-columns: 12px minmax(220px,1fr) 256px 20px; align-items: center; gap: var(--space-4); min-height: 58px; padding: 10px var(--space-4) 10px 48px; border-top: 1px solid var(--line); cursor: pointer; transition: background-color 120ms ease-out,box-shadow 120ms ease-out; }
.idxattemptrow:first-child { border-top: 0; }
.idxattemptrow:hover { background: var(--bg3); }
.idxattemptrow[data-outcome="failed"]:hover { background: var(--danger-surface); box-shadow: inset 3px 0 var(--fail); }
.idxattemptrow[data-outcome="selfheal"]:hover { background: color-mix(in srgb,var(--warning-surface) 44%,var(--bg2)); box-shadow: inset 3px 0 var(--amber); }
.idxattemptrow[data-outcome="passed"]:hover { background: color-mix(in srgb,var(--success-surface) 44%,var(--bg2)); box-shadow: inset 3px 0 var(--pass); }
.idxattemptrow:focus-visible { outline: 1px solid var(--line2); outline-offset: -1px; }
.idxattemptmain { min-width: 0; display: flex; align-items: baseline; gap: 10px; }
.idxattemptlabel { color: var(--txt); font-size: var(--type-caption); font-weight: var(--font-weight-emphasis); white-space: nowrap; }
.idxattemptstatus { font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); text-transform: capitalize; }
.idxattemptstatus.failed { color: var(--fail); }
.idxattemptstatus.selfheal { color: var(--amber); }
.idxattemptstatus.passed { color: var(--pass); }
.detailheader { padding-top: var(--space-4); }
.detailheader h1 { font-size: 20px; }
.detailheader nav { margin-top: var(--space-3); }
/* header's bottom padding is 0 because the tab nav supplies that space. A header rendered without
   tabs (the still-loading run view) has to supply it itself or the title sits on the border. */
.detailheader.notabs { padding-bottom: var(--page-y); }
.detailtitle { min-height: 32px; max-width: none; display: grid; grid-template-columns: auto minmax(0,1fr) auto; align-items: center; gap: 12px; }
.detailtitle.noback { grid-template-columns: minmax(0,1fr) auto; }
.detailedge { width: 32px; height: 32px; display: flex; align-items: center; justify-content: center; }
.runidentity { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; min-width: 0; }
.runidentity > .idxstatus { flex: none; }
.exportmenu { position: relative; }
.exportmenu summary { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid transparent; border-radius: var(--r-sm); color: var(--sub); cursor: pointer; list-style: none; }
.exportmenu summary::-webkit-details-marker { display: none; }
.exportdots { display: inline-flex; align-items: center; gap: 2.5px; }
.exportdot { width: 3px; height: 3px; border-radius: 50%; background: currentColor; }
.exportmenu summary:hover, .exportmenu[open] summary { color: var(--txt); border-color: var(--line2); background: var(--button-hover); }
.exportmenuitems { position: absolute; z-index: 30; top: calc(100% + 5px); right: 0; width: 196px; padding: 5px; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--bg2); box-shadow: 0 12px 30px rgba(0,0,0,.38); animation: idxsortin 120ms ease-out both; }
.exportmenuitem { width: 100%; min-height: 34px; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 9px; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--sub2); font: inherit; font-size: 11px; font-weight: var(--font-weight-emphasis); text-align: left; cursor: pointer; }
.exportmenuitem:hover { color: var(--txt); background: var(--button-hover); }
.exportmenuitem:disabled { color: var(--sub); cursor: not-allowed; opacity: .48; background: transparent; }
.exportmenuitem .count { color: var(--sub); font-size: var(--type-micro); line-height: 1.2; font-variant-numeric: tabular-nums; }
.headeraction { min-width: 72px; display: inline-flex; align-items: center; justify-content: center; }
.back { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; background: transparent; border: 1px solid transparent; border-radius: var(--r-sm); color: var(--sub); cursor: pointer; padding: 0; }
.back:hover { color: var(--txt); border-color: var(--line2); background: var(--button-hover); }
.back:focus-visible { color: var(--txt); outline: 2px solid var(--focus); outline-offset: 2px; }
.backicon { width: 19px; height: 19px; display: block; }
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
.markborder { position: absolute; inset: 0; border: 3px solid var(--fail); border-radius: var(--r-lg); pointer-events: none; }
svg.swipe { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; overflow: visible; }
.viewpage.lightboxpage { max-width: none; }
.gal { width: 100%; display: grid; grid-template-columns: repeat(auto-fill,minmax(min(var(--galsize,190px),100%),1fr)); gap: 16px; align-items: start; }
.lightboxtoolbar { display: flex; align-items: center; justify-content: flex-start; margin: -4px 0 var(--space-4); }
.lightboxzoom { display: inline-flex; gap: 4px; margin-left: auto; }
.lightboxzoombtn { width: 30px; min-height: 30px; display: inline-flex; align-items: center; justify-content: center; border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--bg2); color: var(--sub2); font: inherit; font-size: 15px; font-weight: var(--font-weight-emphasis); line-height: 1; cursor: pointer; }
.lightboxzoombtn:hover:not(:disabled) { color: var(--txt); border-color: var(--run); }
.lightboxzoombtn:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.lightboxzoombtn:disabled { opacity: .4; cursor: default; }
.lightboxtoggle { min-height: 30px; display: inline-flex; align-items: center; gap: 8px; padding: 4px 8px 4px 7px; border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--bg2); color: var(--sub2); font: inherit; font-size: var(--type-caption); font-weight: var(--font-weight-emphasis); cursor: pointer; }
.lightboxtoggle:hover { color: var(--txt); border-color: var(--run); }
.lightboxtoggle:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.lightboxtoggletrack { width: 24px; height: 14px; display: flex; align-items: center; padding: 2px; border-radius: 99px; background: var(--line2); transition: background-color 120ms ease-out; }
.lightboxtogglethumb { width: 10px; height: 10px; border-radius: 99px; background: var(--sub2); transition: transform 120ms ease-out, background-color 120ms ease-out; }
.lightboxtoggle[aria-checked="true"] .lightboxtoggletrack { background: rgba(106,166,255,.48); }
.lightboxtoggle[aria-checked="true"] .lightboxtogglethumb { background: var(--accent-7); transform: translateX(10px); }
.galcell { min-width: 0; border: 0; padding: 0; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.galcell:hover .gallabel, .galcell:hover .galtool { color: var(--txt); }
.galshot { cursor: zoom-in; }
.galcell img { width: 100%; border: 1px solid var(--line2); border-radius: var(--r-sm); display: block; background: #000; }
.galcell .cap { display: grid; gap: 5px; margin-top: 7px; line-height: 1.35; word-break: break-word; }
.galchip { width: fit-content; }
.galchip.trailhead { color: var(--trail-text); background: var(--trail-surface); }
.gallabel { color: var(--sub2); font-size: var(--type-caption); font-weight: var(--font-weight-emphasis); }
.galtool { color: var(--sub); font-size: var(--type-caption); }
.logpane { border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg); max-height: 72vh; overflow: auto; margin-top: 8px; }
.logpane .ln { display: flex; gap: 10px; padding: 1px 11px; font-size: 11.5px; line-height: 1.6; white-space: pre-wrap; word-break: break-word; border-top: 1px solid var(--line); }
.logpane .ln:first-child { border-top: none; }
.logpane .ln.e { color: var(--danger-text); } .logpane .ln.w { color: var(--warning-text); }
.logpane.net .ln span:first-child { font-weight: var(--font-weight-emphasis); min-width: 46px; }
.logpane.net .m { color: var(--sub); min-width: 96px; }
.evchips { display: flex; flex-wrap: wrap; gap: 6px; margin-bottom: 8px; }
.evchip { background: var(--bg3); border: 1px solid var(--line2); color: var(--txt); border-radius: 999px; padding: 4px 10px; font-size: 11.5px; font-weight: var(--font-weight-emphasis); cursor: pointer; display: inline-flex; align-items: center; gap: 6px; }
.evchip:hover { border-color: var(--run); }
.evchip.on { border-color: var(--run); background: var(--bg2); }
.evchip .c { color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.streamselect { position: relative; flex-shrink: 0; }
.streamselect summary { width: max-content; min-height: 34px; display: flex; align-items: center; gap: 7px; padding: 5px 10px; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--bg2); color: var(--sub2); cursor: pointer; list-style: none; font-size: var(--type-small); font-weight: var(--font-weight-emphasis); }
.streamselect summary::-webkit-details-marker { display: none; }
.streamselect summary:hover { border-color: var(--run); color: var(--txt); }
.streamselect[open] summary { border-color: var(--run); background: var(--accent-surface); color: var(--run); }
.streamselect summary:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.streamselectoricon { width: 15px; height: 15px; color: var(--sub); flex-shrink: 0; }
.streamselect[open] summary .streamselectoricon { color: currentColor; }
.streamselectcount { color: var(--sub); font-variant-numeric: tabular-nums; }
.streamselect[open] .streamselectcount { color: currentColor; }
.streamoptiondot { width: 9px; height: 9px; border-radius: 99px; background: currentColor; flex-shrink: 0; }
.streamselect .chevron { width: 7px; height: 7px; flex-shrink: 0; justify-self: center; border-right: 1.75px solid currentColor; border-bottom: 1.75px solid currentColor; color: var(--sub); transform: translateY(-2px) rotate(45deg); transform-origin: center; transition: transform 120ms ease-out; }
.streamselect[open] .chevron { transform: translateY(2px) rotate(225deg); }
.streammenu { position: absolute; z-index: 20; top: calc(100% + 6px); left: 0; width: min(320px, calc(100vw - 48px)); max-height: min(70vh, 560px); overflow-x: hidden; overflow-y: auto; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--raised); box-shadow: var(--shadow-raised); }
.streammenuhead { position: sticky; top: 0; z-index: 1; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border-bottom: 1px solid var(--line); background: var(--raised); color: var(--sub); font-size: 10.5px; font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; }
.streammenuactions { display: flex; gap: 4px; }
.streammenuactions button { padding: 3px 6px; border: 0; background: transparent; color: var(--run); cursor: pointer; font: inherit; font-weight: var(--font-weight-emphasis); letter-spacing: 0; text-transform: none; }
.streammenuactions button:hover, .streammenuactions button:focus-visible { color: var(--txt); outline: none; text-decoration: underline; }
.streamoption { position: relative; display: grid; grid-template-columns: 10px minmax(0,1fr) auto 16px; align-items: center; gap: 10px; padding: 9px 12px; border-top: 1px solid var(--line); cursor: pointer; }
.streamoption:first-of-type { border-top: 0; }
.streamoption:hover { background: var(--button-hover); }
.streamoption:focus-within { outline: 2px solid var(--focus); outline-offset: -2px; }
.streamoption input { position: absolute; width: 1px; height: 1px; opacity: 0; pointer-events: none; }
.streamoptiondot { color: var(--stream-color); }
.streamoption .streamname { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; font-weight: var(--font-weight-emphasis); }
.streamoption .streamcount { color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.streamoptioncheck { width: 16px; height: 16px; color: var(--run); opacity: 0; }
.streamoption input:checked ~ .streamoptioncheck { opacity: 1; }
.video { display: flex; flex-direction: column; align-items: center; }
.vframe { height: min(72vh, 900px); max-width: 100%; aspect-ratio: 1/2; background-repeat: no-repeat; background-color: #000; border: 1px solid var(--line2); border-radius: var(--r-lg); margin-top: 10px; }
.vctl { display: flex; align-items: center; gap: 10px; width: min(100%, 560px); margin-top: 12px; padding: 8px 12px; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--bg2); }
.vctl .btn.play { min-width: 84px; }
.vctl .count { font-variant-numeric: tabular-nums; }
.vctl input[type=range] { flex: 1; accent-color: var(--run); }
.scrub { flex-shrink: 0; display: flex; align-items: center; gap: 12px; padding: 7px var(--page-x); border-top: 1px solid var(--line); background: var(--header); user-select: none; }
.scrubclock { color: var(--sub); font-size: var(--type-micro); text-align: center; font-variant-numeric: tabular-nums; }
.scrubtrack { position: relative; flex: 1; height: 28px; cursor: pointer; }
.scrubline { position: absolute; top: 50%; height: 1px; transform: translateY(-50%); pointer-events: none; }
.scrubline.setup { left: 0; height: 0; border-top: 1px dashed color-mix(in srgb,var(--trail-mark) 62%,var(--line2)); }
.scrubline.trail { right: 0; background: var(--line2); }
.scrubphasebreak { position: absolute; top: 50%; width: 11px; height: 11px; border: 2px solid var(--bg); border-radius: 99px; background: var(--trail-mark); box-shadow: 0 0 0 1px color-mix(in srgb,var(--trail-mark) 55%,var(--line2)); transform: translate(-50%,-50%); pointer-events: none; }
.scrubtick { position: absolute; top: 4px; bottom: 4px; width: 3px; border: 0; padding: 0; border-radius: 2px; opacity: .72; pointer-events: none; }
.scrubhead { position: absolute; top: 50%; width: 10px; height: 10px; border-radius: 99px; transform: translate(-50%,-50%); background: #fff; border: 1px solid rgba(0,0,0,.45); box-shadow: 0 1px 5px rgba(0,0,0,.6); pointer-events: none; }
.streamitems { display: grid; gap: 0; }
.streamitems.timelineeventitems { margin: 0; }
.timelineevent { min-width: 0; border-top: 1px solid var(--line); border-left: 3px solid var(--stream-color); background: var(--bg2); }
.timelineevent:first-child { border-top: 0; }
.timelineevent.e { border-left: 3px solid var(--fail); }
.timelineevent.w { border-left: 3px solid var(--amber); }
.timelineevent summary { min-height: 40px; display: grid; grid-template-columns: 9px max-content minmax(0,1fr) auto 10px; align-items: center; gap: 10px; padding: 7px 10px; color: var(--sub2); cursor: pointer; list-style: none; }
.timelineevent summary::-webkit-details-marker { display: none; }
.timelineevent summary:hover { background: var(--button-hover); }
.timelineevent .streamdot { width: 8px; height: 8px; border-radius: 2px; background: var(--stream-color); }
.timelineevent.e .streamdot { background: var(--fail); }
.timelineevent.w .streamdot { background: var(--amber); }
.timelineevent .streamtype { color: var(--stream-color); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .08em; text-transform: uppercase; white-space: nowrap; }
.timelineevent.e .streamtype { color: var(--fail); }
.timelineevent.w .streamtype { color: var(--amber); }
.timelineeventlabel { min-width: 0; color: var(--txt); font-size: 11.5px; font-weight: var(--font-weight-body); line-height: 1.35; overflow-wrap: anywhere; white-space: normal; }
.timelineeventchev { width: 7px; height: 7px; border-right: 1.75px solid currentColor; border-bottom: 1.75px solid currentColor; color: var(--sub); transform: rotate(45deg) translate(-1px,-1px); transition: transform 120ms ease-out; }
.timelineevent[open] .timelineeventchev { transform: rotate(225deg) translate(-1px,-1px); }
.timelineevent pre { margin: 0 10px 10px; max-height: 220px; background: var(--code-surface); color: var(--code-text); }
.eventfields { display: grid; grid-template-columns: repeat(auto-fit,minmax(170px,1fr)); gap: 1px; background: var(--line); }
.eventfield { min-width: 0; padding: 6px 9px; background: var(--bg2); }
.eventfield .k { color: var(--sub); font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .06em; text-transform: uppercase; }
.eventfield .v { margin-top: 2px; color: var(--sub2); font-size: 11.5px; font-weight: var(--font-weight-emphasis); overflow-wrap: anywhere; }
.fmtbadges { display: inline-flex; gap: 4px; flex-wrap: wrap; justify-content: flex-end; }
.rowbadge { padding: 1px 7px; border-radius: 99px; border: 1px solid var(--line2); background: var(--bg3); color: var(--sub2); font-size: 10px; font-weight: var(--font-weight-emphasis); font-variant-numeric: tabular-nums; white-space: nowrap; }
.rowbadge.ok { color: var(--pass); border-color: color-mix(in srgb,var(--pass) 45%,var(--line2)); }
.rowbadge.warn { color: var(--amber); border-color: color-mix(in srgb,var(--amber) 45%,var(--line2)); }
.rowbadge.error { color: var(--fail); border-color: color-mix(in srgb,var(--fail) 45%,var(--line2)); }
.fmtbody { border-top: 1px solid var(--line); }
/* NOT named .tl — that class is the page-level timeline grid (.tl { display: grid; gap }) and would restyle this body. */
.fmtbody.tlbody { margin: 0 10px 10px; border: 1px solid var(--line2); border-radius: var(--r-md); overflow: hidden; background: var(--bg2); }
.fmtbody.tlbody:first-child, .fmtbody .eventfields:first-child { border-top: 0; }
.fmtbody pre { margin: 0; border: 0; border-top: 1px solid var(--line); border-radius: 0; max-height: 480px; background: var(--code-surface); color: var(--code-text); }
.fmtbody pre:first-child { border-top: 0; }
.yamlcompare { display: grid; grid-template-columns: 1fr; gap: 20px; align-items: start; }
.yamlcol { min-width: 0; }
.yamlcolhead { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-bottom: 8px; }
.yamlcolhead .eyebrow { margin: 0; }
.yamlcopy { min-height: 24px; padding: 3px 7px; border-radius: var(--r-sm); font-size: 10px; }
.yamlcol .cmd { max-width: none; }
@media (min-width: 820px) { .yamlcompare { grid-template-columns: repeat(2,minmax(0,1fr)); } }
@media (min-width: 960px) {
  main.timelinemain { overflow: hidden; }
  .timelinemain .tl { height: 100%; min-height: 0; grid-template-columns: minmax(320px,1fr) minmax(340px,42%); grid-template-rows: minmax(0,1fr); gap: 24px; align-items: stretch; }
  .timelinemain .timeline-list { grid-row: auto; min-height: 0; display: flex; flex-direction: column; overflow: visible; }
  /* Match the cards' usable width: their scroll pane also gives up the 8px scrollbar gutter. */
  .timelinemain .timelinecontrols { margin-right: calc(var(--page-x) + 8px); }
  .timelinemain .timelinescroll { min-height: 0; flex: 1; overflow-x: hidden; overflow-y: auto; padding-right: var(--page-x); }
  .timelinemain .preview { position: static; grid-row: auto; min-height: 0; height: 100%; display: flex; align-items: center; justify-content: center; }
  .timelinemain .devicecolumn { max-height: 100%; }
  .timelinemain .deviceplayer { max-height: 100%; min-height: 0; align-self: center; }
  .timelinemain .devicecolumn.hasinspect .deviceplayer { max-height: calc(100% - 42px); }
  .timelinemain .shotwrap { max-height: calc(100vh - 330px); min-height: 0; }
  .timelinemain .shot { width: auto; height: auto; max-height: calc(100vh - 330px); object-fit: contain; }
  .timelinemain .devicecolumn.hasinspect .shotwrap, .timelinemain .devicecolumn.hasinspect .shot { max-height: calc(100vh - 372px); }
  .timelinemain .noshot { height: auto; min-height: 0; aspect-ratio: 1/2; }
}
@media (max-width: 760px) {
  .indexcontext { grid-template-columns: minmax(0,1fr); align-items: start; }
  .indexcontext .idxfilter { justify-self: end; }
  .idxrow, .idxattemptrow { grid-template-columns: 12px minmax(0,1fr) 20px; gap: 10px 12px; }
  .idxrow.idxmatrixrow { grid-template-columns: minmax(0,1fr); }
  .idxmatrixrow .idxcells { justify-content: flex-start; }
  .idxretryrow { grid-template-columns: auto minmax(0,1fr) 20px; }
  .idxretrychev { grid-column: 3; grid-row: 1; }
  .idxattemptrow { padding-left: 28px; }
  .idxstatus { grid-row: 1 / span 2; }
  .idxfacts { grid-column: 2 / -1; }
  .idxrow .arr, .idxattemptrow .arr { grid-column: 3; grid-row: 1; }
  .indexfootercontent { flex-wrap: wrap; }
  .indexmetrics { order: 2; width: 100%; margin-left: 0; }
  .indexrundate { margin-left: auto; }
  .streammenu { left: 0; right: auto; }
  /* Preserve the media context without letting a tall phone frame consume the working viewport. */
  .timelinemain .preview, .timelinemain .devicecolumn, .timelinemain .deviceplayer { max-height: 40vh; }
  .timelinemain .preview { overflow: hidden; align-items: center; }
  .timelinemain .shot, .timelinemain .tlvframe, .timelinemain .noshot { max-height: calc(40vh - 4px); }
  .timelinemain .tlvframe { height: calc(40vh - 4px); min-height: 0; }
  .timelinemain .noshot { height: calc(40vh - 4px); aspect-ratio: auto; }
  .timelinemain .devicecolumn.hasinspect .deviceplayer { max-height: calc(40vh - 42px); }
  .timelinemain .devicecolumn.hasinspect .shot, .timelinemain .devicecolumn.hasinspect .tlvframe, .timelinemain .devicecolumn.hasinspect .noshot { max-height: calc(40vh - 46px); }
  .timelinemain .devicecolumn.hasinspect .tlvframe, .timelinemain .devicecolumn.hasinspect .noshot { height: calc(40vh - 46px); }
  /* Narrow layouts recompose metadata into a compact, scrollable wrap instead of clipping a
     desktop-width row with no visible continuation. */
  .detailfooter { align-items: flex-start; max-height: 112px; }
  .detailfootermeta { flex-wrap: wrap; align-content: flex-start; gap: 8px 16px; max-height: 88px; overflow-x: visible; overflow-y: auto; scrollbar-width: thin; }
  .detailfootermeta::-webkit-scrollbar { display: block; width: 6px; }
}
@media (max-width: 560px) { .failurehead { align-items: flex-start; flex-wrap: wrap; } .failurecontext { width: calc(100% - 30px); margin-left: 30px; white-space: normal; } .failuretool { grid-template-columns: 1fr; gap: 6px; } .failuretoolvalue { flex-wrap: wrap; } .failuretoolargs { display: block; } .failuretool .yamllink { margin-left: auto; } .failurebody { grid-template-columns: 1fr; } .failurefield + .failurefield { border-top: 1px solid var(--danger-border); border-left: 0; } .idxfilter { grid-template-columns: minmax(0,1fr) minmax(0,1fr); } .idxsearch { grid-column: 1 / -1; } .idxgroup, .idxorder { width: 100%; } .timelinecontrols { align-items: flex-start; flex-wrap: wrap; } .timelinefilters { width: 100%; } }
.step .ts { margin-left: auto; flex-shrink: 0; color: var(--sub); font-size: 10.5px; text-align: right; font-variant-numeric: tabular-nums; }
.step .ts .dur { display: block; color: var(--sub); opacity: .8; }
.lfilter { display: flex; align-items: center; gap: 8px; margin: 8px 0; flex-wrap: wrap; }
.lfilter input { background: var(--bg2); border: 1px solid var(--line2); color: var(--txt); border-radius: var(--r-md); padding: 6px 10px; font-size: 12.5px; min-width: 220px; }
.lfilter input:focus { border-color: var(--run); }
.lfilter .count { font-size: 11px; color: var(--sub); margin-left: auto; font-variant-numeric: tabular-nums; }
.badge.selfheal { background: var(--warning-surface); color: var(--amber); }
.zoom .zoomwrap { position: relative; }
.zoom .zoomwrap img { display: block; }
button:focus-visible, [role="button"]:focus-visible, summary:focus-visible, input:focus-visible, .shot:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
@media (pointer: coarse) { nav button, button.btn, .evchip, .back, .streamselect summary, .idxsort summary, .exportmenu summary, .exportmenuitem, .phasecontrol, .grphdr { min-height: 44px; } .detailedge { width: 44px; height: 44px; } .back, .exportmenu summary { min-width: 44px; } .step { min-height: 44px; } .scrubtrack { height: 44px; } .scrubtransport button.timelinecontrol { width: 44px; height: 44px; min-width: 44px; min-height: 44px; } .txopenbtn, .txclose { min-width: 44px; min-height: 44px; } }
@media (prefers-reduced-motion: reduce) { #app.page-enter-forward, #app.page-enter-back { animation: none; } }
@media (max-width: 640px) {
  :root { --page-x: 18px; --page-y: 20px; }
  main { padding-bottom: var(--space-5); }
  h1 { font-size: 21px; }
  .detailheader h1 { font-size: 18px; }
}
@media (prefers-reduced-motion: reduce) { *, *::before, *::after { scroll-behavior: auto !important; transition-duration: .01ms !important; animation-duration: .01ms !important; } }
/* ── UI Inspector (per-step view-hierarchy overlay) ─────────────────────────────────────────── */
.inspector { position: fixed; inset: 0; background: rgba(2,6,12,.72); display: flex; align-items: center; justify-content: center; z-index: 99; backdrop-filter: blur(4px); padding: 24px; }
.insppanel { display: flex; flex-direction: column; width: min(1240px, 100%); height: 100%; max-height: 94vh; background: var(--bg); border: 1px solid var(--line2); border-radius: var(--r-lg); box-shadow: var(--shadow-raised); overflow: hidden; }
.insphead { display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid var(--line); flex-shrink: 0; }
.insptitle { font-size: 14px; font-weight: var(--font-weight-emphasis); }
.inspcontext { font-size: 12px; color: var(--sub); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; }
.inspactions { display: flex; gap: 8px; flex-shrink: 0; }
.inspactions button.btn.inspaction { min-height: 28px; padding: 4px 8px; font-size: 11px; }
.inspactionicon { width: 13px; height: 13px; display: block; flex-shrink: 0; }
.inspbody { display: grid; grid-template-columns: minmax(220px, 34%) minmax(0, 1fr); gap: 16px; padding: 16px; flex: 1; min-height: 0; }
.insppane { min-height: 0; overflow: auto; }
.inspshotpane { display: flex; align-items: flex-start; justify-content: center; }
.inspshotwrap { position: relative; max-width: 100%; cursor: crosshair; }
.inspshotwrap img { display: block; max-width: 100%; max-height: calc(94vh - 120px); border-radius: var(--r-sm); border: 1px solid var(--line2); }
/* Panel geometry adapts to the CAPTURE's shape (insp-* set from the hierarchy's device extent) and
   the screenshot pane is the priority claimant on space — hovering it is the primary interaction,
   so when space is tight the DATA column is the one that caps (its own scroll), never the image.
   - insp-landscape (web / tablet): the panel takes the full padded viewport (~96vw) and the grid
     flips — image column gets the free space, data column caps at a readable width.
   - insp-tall (a scrolled full-page web capture, e.g. 936×3694): scaling to the panel height would
     leave a sliver, so the image renders at pane width and the pane scrolls vertically. The bounds
     rects live inside .inspshotwrap (percentage-positioned against the image), so they scroll with
     it and stay glued. */
.insppanel.insp-landscape, .insppanel.insp-tall { width: 100%; }
.insppanel.insp-landscape .inspbody, .insppanel.insp-tall .inspbody { grid-template-columns: minmax(0, 1fr) minmax(260px, 26rem); }
.insppanel.insp-tall .inspshotwrap { width: 100%; }
.insppanel.insp-tall .inspshotwrap img { width: 100%; max-height: none; }
/* overflow:hidden — a web tree's page-relative bounds run past the viewport capture, so rects for
   below-the-fold nodes land outside the image and must clip rather than paint over the panel. */
.insprects { position: absolute; inset: 0; pointer-events: none; overflow: hidden; }
/* Every node has a rect, but only the hovered / selected one paints — drawing them all turned the
   screenshot into a wireframe. Hover (dashed, cool) and selection (solid, accent) read apart. */
.insprect { position: absolute; border: 0 solid transparent; }
.insprect.hov { border: 2px dashed var(--pass); background: color-mix(in srgb, var(--pass) 12%, transparent); z-index: 1; }
.insprect.sel { border: 2px solid var(--run); background: color-mix(in srgb, var(--run) 18%, transparent); z-index: 2; }
.insphovlabel { position: absolute; display: none; transform: translateY(-100%); max-width: 90%; margin-top: -3px; padding: 1px 5px; border-radius: var(--r-sm); background: var(--pass); color: var(--bg); font-size: 10.5px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; pointer-events: none; z-index: 3; }
.insphovlabel.on { display: block; }
.inspdatapane { display: flex; flex-direction: column; gap: 12px; }
.inspdetails { flex-shrink: 0; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg2); padding: 10px 12px; max-height: 38%; overflow: auto; }
.inspdetails .rows { display: grid; gap: 4px; }
.inspdetails .r { display: grid; grid-template-columns: 140px minmax(0, 1fr); gap: 10px; font-size: 12px; }
.inspdetails .r .k { color: var(--sub); }
.inspdetails .r .v { word-break: break-word; }
.inspdetails .r.inspreview .k, .inspdetails .r.inspreview .v { color: var(--pass); font-weight: var(--font-weight-emphasis); }
.inspflag { display: inline-block; margin: 0 4px 2px 0; padding: 1px 6px; border-radius: 99px; background: var(--accent-surface); color: var(--run); font-size: 10.5px; font-weight: var(--font-weight-emphasis); }
/* Selector suggestions — ranked nodeSelector candidates for the COMMITTED selection, computed by
   the embedded Kotlin/JS selector engine (the daemon's own generator/resolver). Lives in the
   capped data column (the screenshot stays the priority claimant on space) and is empty — and
   therefore invisible — when nothing is committed, the capture is a legacy-shaped tree, or the
   report carries no engine payload. */
.inspselectors { flex-shrink: 0; max-height: 44%; overflow: auto; display: flex; flex-direction: column; gap: 8px; }
.inspselectors:empty { display: none; }
.inspseltitle { display: flex; align-items: center; gap: 7px; min-width: 0; font-size: 10.5px; font-weight: var(--font-weight-emphasis); text-transform: uppercase; letter-spacing: .05em; color: var(--sub); }
/* The subject label + preview chip say WHICH element the cards describe — suggestions follow the
   hovered node (like the properties card) and revert to the committed selection on hover-out. */
.inspselsubject { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; text-transform: none; letter-spacing: 0; font-weight: var(--font-weight-emphasis); color: var(--txt); font-size: 11px; }
.inspselpreviewchip { flex-shrink: 0; padding: 1px 6px; border-radius: 99px; background: var(--success-surface); color: var(--pass); font-size: var(--type-micro); letter-spacing: .03em; }
.inspselgroup { font-size: 10px; font-weight: var(--font-weight-emphasis); text-transform: uppercase; letter-spacing: .05em; color: var(--sub); margin-top: 2px; }
.inspselnote { color: var(--sub); font-size: 12px; }
.inspselcard { border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg2); padding: 8px 10px; display: grid; gap: 5px; }
.inspselcard.best { border-color: color-mix(in srgb, var(--run) 55%, var(--line)); background: var(--accent-surface); }
.inspselhead { display: flex; align-items: center; gap: 8px; }
.inspselstrategy { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11.5px; font-weight: var(--font-weight-emphasis); color: var(--sub2); }
.inspselbadges { display: inline-flex; gap: 5px; flex-shrink: 0; }
.inspselbadge { display: inline-block; padding: 1px 6px; border-radius: 99px; font-size: var(--type-micro); font-weight: var(--font-weight-emphasis); letter-spacing: .03em; }
.inspselbadge.unique { background: var(--success-surface); color: var(--success-text); }
.inspselbadge.multi { background: var(--warning-surface); color: var(--warning-text); }
.inspselbadge.nomatch { background: var(--danger-surface); color: var(--danger-text); }
.inspselbadge.bestpick { background: var(--accent-surface); color: var(--run); box-shadow: inset 0 0 0 1px var(--run); }
.inspselverify { font-size: 10.5px; color: var(--sub); }
.inspselverify.ok { color: var(--pass); }
.inspselverify.bad { color: var(--fail); }
.inspselvizhint { margin-left: 6px; color: var(--sub); font-size: var(--type-micro); }
/* A mismatch card is engageable: pointing at it (or clicking to pin) paints the mismatch on the
   screenshot via the .inspselvizlayer below. */
.inspselcard[data-inspselviz] { cursor: pointer; }
.inspselcard[data-inspselviz]:hover { border-color: color-mix(in srgb, var(--fail) 55%, var(--line)); }
/* Mismatch visualization — its own overlay layer above the hover/selection rects so engaging a
   card never repaints or fights them. Intended element in the selection accent, the element that
   would actually receive the tap in the failure color, the tap point as a ring marker. */
.inspselvizlayer { position: absolute; inset: 0; pointer-events: none; overflow: hidden; z-index: 4; }
.inspselvizlayer:empty { display: none; }
.inspselvizrect { position: absolute; }
.inspselvizrect.intended { border: 2px solid var(--run); background: color-mix(in srgb, var(--run) 14%, transparent); }
.inspselvizrect.actual { border: 2px solid var(--fail); background: color-mix(in srgb, var(--fail) 16%, transparent); }
.inspselviztap { position: absolute; width: 14px; height: 14px; margin: -7px 0 0 -7px; border: 2.5px solid var(--fail); border-radius: 50%; background: color-mix(in srgb, var(--fail) 40%, transparent); box-shadow: 0 0 0 1.5px color-mix(in srgb, var(--bg) 85%, transparent); }
.inspselvizlegend { position: absolute; left: 6px; bottom: 6px; display: flex; gap: 9px; align-items: center; padding: 3px 8px; border-radius: var(--r-sm); border: 1px solid var(--line); background: color-mix(in srgb, var(--bg) 88%, transparent); font-size: var(--type-micro); color: var(--txt); white-space: nowrap; }
.inspselvizlegend .k { display: inline-flex; align-items: center; gap: 4px; }
.inspselvizlegend .sw { display: inline-block; width: 8px; height: 8px; border-radius: 2px; }
.inspselvizlegend .sw.intended { background: var(--run); }
.inspselvizlegend .sw.actual { background: var(--fail); }
.inspselvizlegend .sw.tappt { width: 8px; height: 8px; border-radius: 50%; background: transparent; border: 2px solid var(--fail); }
.inspselyaml { margin: 0; padding: 6px 8px; border-radius: var(--r-sm); background: var(--code-surface); color: var(--code-text); font-size: 11px; line-height: 1.45; overflow: auto; }
.inspselcopy { flex: none; min-height: 22px; display: inline-flex; align-items: center; border: 1px solid var(--line2); border-radius: var(--r-sm); padding: 2px 7px; background: var(--bg2); color: var(--sub2); font: inherit; font-size: 10px; font-weight: var(--font-weight-emphasis); white-space: nowrap; cursor: pointer; }
.inspselcopy:hover { color: var(--txt); border-color: var(--run); }
.inspselcopy:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.insptree { flex: 1; min-height: 0; overflow: auto; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--bg2); padding: 8px 10px; font-size: 12px; }
.insptree details { margin: 0; }
.insptree summary { list-style: revert; cursor: default; }
.inspkids { margin-left: 14px; border-left: 1px solid var(--line); padding-left: 6px; }
.inspleaf { margin-left: 15px; }
.inspnoderow { display: inline-flex; gap: 8px; align-items: baseline; padding: 1px 6px; border-radius: var(--r-sm); cursor: pointer; max-width: 100%; }
/* .hov marks the row of the node currently previewed by hovering the SCREENSHOT — it locates that
   node in the hierarchy. Pointing at a row is not a hover source and never sets it. .sel is the
   committed selection and wins the background. */
.inspnoderow.hov { background: var(--success-surface); box-shadow: inset 0 0 0 1px var(--pass); }
.inspnoderow.sel { background: var(--accent-surface); box-shadow: none; }
.inspnoderow .inspkey { color: var(--sub); font-size: 10.5px; font-variant-numeric: tabular-nums; flex-shrink: 0; }
.inspnoderow .insplabel { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.inspraw { flex: 1; min-height: 0; overflow: auto; border: 1px solid var(--line); border-radius: var(--r-md); background: var(--code-surface); color: var(--code-text); padding: 10px 12px; font-size: 11.5px; margin: 0; }
.inspnote { color: var(--sub); font-size: 12.5px; padding: 12px; }
@media (max-width: 760px) { .inspbody { grid-template-columns: 1fr; } .inspshotwrap img { max-height: 38vh; } }

/* Export capture framing (?autoplay=1) - the document the CLI's --video/--gif/--webp exporters
   screen-record. Affordances nobody can click are chrome in a recording, and an in-flight
   transition is a frame the encoder keeps, so both are dropped for the capture only. */
html[data-tb-autoplay] .detailactions { display: none; }
html[data-tb-autoplay] *, html[data-tb-autoplay] *::before, html[data-tb-autoplay] *::after { animation: none !important; transition: none !important; scroll-behavior: auto !important; }
`;
