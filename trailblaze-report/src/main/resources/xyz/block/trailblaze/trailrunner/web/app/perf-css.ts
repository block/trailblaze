// Stylesheet for the performance-analysis report, emitted into the <head> of every exported
// document by buildPerfReportHtml (perf-html.ts). Shares the interactive run report's design
// tokens (RUN_REPORT_TOKENS_CSS — one palette, no drift); everything below the token block is
// perf-report-specific: the Instruments-style timeline chrome, the detail-pane tables, and the
// span inspector.
import { RUN_REPORT_TOKENS_CSS } from './run-report-css';

export const PERF_REPORT_CSS = `${RUN_REPORT_TOKENS_CSS}
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
#pf-detail { flex: 1; min-height: 0; display: flex; flex-direction: column; }
#pf-index { flex: 1; min-height: 0; overflow-y: auto; padding: var(--space-4) var(--space-5) var(--space-6); }
#pf-detail[hidden], #pf-index[hidden] { display: none; }
#tb-boot { flex: 1; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: var(--space-3); color: var(--sub); }
#tb-boot .tb-boot-spinner { width: 28px; height: 28px; border-radius: 50%; border: 3px solid var(--line2); border-top-color: var(--run); animation: tbBootSpin .8s linear infinite; }
#tb-boot .tb-boot-title { font-size: var(--type-body); font-weight: 650; color: var(--txt); }
#tb-boot .tb-boot-note { font-size: var(--type-small); }
@keyframes tbBootSpin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { #tb-boot .tb-boot-spinner { animation: none; } }

/* ---- index (run summary) view - mirrors the interactive run report's landing page ---- */
.idxshell { width: 100%; max-width: var(--content-wide); margin-inline: auto; }
.idxhead { display: flex; align-items: center; gap: var(--space-3); padding: var(--space-3) 0 var(--space-4); }
.idxhead h1 { font-size: 20px; line-height: 1.2; letter-spacing: -.01em; margin: 0; font-weight: 720; }
.idxhead .spacer { flex: 1; }
.idxsummary { display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; margin-bottom: var(--space-4); }
.idxstat { color: var(--sub2); font-size: 13px; font-weight: 650; white-space: nowrap; }
.idxstat strong { color: var(--txt); font-size: 16px; }
.idxstat.pass strong { color: var(--pass); }
.idxstat.selfheal strong { color: var(--amber); }
.idxstat.fail strong { color: var(--fail); }
.idxsection + .idxsection { margin-top: var(--space-4); }
.idxsectionhead { display: flex; align-items: center; gap: 8px; margin: 0 0 7px 2px; color: var(--sub2); font-size: var(--type-caption); font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.idxsectionhead::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: var(--sub); }
.idxsectionhead.failed::before { background: var(--fail); }
.idxsectionhead.selfheal::before { background: var(--amber); }
.idxsectionhead.passed::before { background: var(--pass); }
.idxsectioncount { color: var(--sub); font-weight: 600; letter-spacing: 0; text-transform: none; }
.idx { border: 1px solid var(--line); border-radius: var(--r-md); overflow: hidden; background: var(--bg2); }
.idxrow { display: grid; grid-template-columns: 12px minmax(220px,1fr) 180px 20px; align-items: center; gap: var(--space-4); padding: var(--space-3) var(--space-4); border-top: 1px solid var(--line); cursor: pointer; transition: background-color 120ms ease-out; }
.idxrow:first-child { border-top: none; }
.idxrow:hover { background: var(--bg3); }
.idxrow:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.idxstatusdot { display: inline-block; width: 7px; height: 7px; border-radius: 50%; background: var(--sub); }
.idxstatusdot.failed { background: var(--fail); }
.idxstatusdot.selfheal { background: var(--amber); }
.idxstatusdot.passed { background: var(--pass); }
.idxmain { min-width: 0; }
.idxrow .nm { font-size: 14px; font-weight: 650; min-width: 0; word-break: break-word; }
/* Platform chip, same shape as the interactive report's .galchip but neutral-toned. */
.platchip { display: inline-block; margin-left: 8px; vertical-align: 2px; font-size: 9.5px; font-weight: 700; letter-spacing: .06em; text-transform: uppercase; color: var(--sub2); background: var(--bg3); border: 1px solid var(--line2); border-radius: 5px; padding: 1px 6px; white-space: nowrap; }
.idxfacts { display: grid; grid-template-columns: 104px 60px; gap: 16px; align-items: center; }
.idxfact .k { color: var(--sub); font-size: var(--type-micro); letter-spacing: .08em; text-transform: uppercase; }
.idxfact .v { color: var(--sub2); font-size: var(--type-caption); font-weight: 600; margin-top: 1px; white-space: nowrap; font-variant-numeric: tabular-nums; }
.idxrow .arr { color: var(--sub); font-size: 14px; }

/* ---- theme toggle + back button (same chrome as the interactive run report) ---- */
.themetoggle { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; padding: 0; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--sub); cursor: pointer; }
.themetoggle:hover { color: var(--txt); background: var(--button-hover); }
.themetoggle:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }
.themeicon { width: 19px; height: 19px; display: block; }
.themeicon.moon { display: none; }
[data-theme="light"] .themeicon.sun { display: none; }
[data-theme="light"] .themeicon.moon { display: block; }
.back { width: 32px; height: 32px; display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0; background: transparent; border: 1px solid transparent; border-radius: 7px; color: var(--sub); cursor: pointer; padding: 0; }
.back:hover { color: var(--txt); border-color: var(--line2); background: var(--button-hover); }
.back:focus-visible { color: var(--txt); outline: 2px solid var(--focus); outline-offset: 2px; }
.backarrow { font-family: ui-rounded, "SF Pro Rounded", -apple-system, BlinkMacSystemFont, sans-serif; font-size: 25px; font-weight: 600; line-height: .9; }

/* ---- header ---- */
.perf-header { flex-shrink: 0; display: flex; align-items: center; gap: var(--space-3); flex-wrap: wrap; padding: var(--space-3) var(--space-4); border-bottom: 1px solid var(--line); background: var(--header); }
.perf-header h1 { font-size: 16px; line-height: 1.2; letter-spacing: -.01em; margin: 0; font-weight: 720; white-space: nowrap; }
.perf-header .spacer { flex: 1; }
/* Selects share the interactive report's quiet control chrome (.quietlink: bg2 fill, line2
   border, r-sm radius, 32px height, 11px/650 sub2 text, accent hover). Native select arrows
   can't be themed, so the wrapper draws the chevron. */
.selectwrap { position: relative; display: inline-flex; min-width: 0; }
.selectwrap::after { content: ''; position: absolute; right: 10px; top: 50%; width: 6px; height: 6px; margin-top: -4.5px; border-right: 1.5px solid var(--sub); border-bottom: 1.5px solid var(--sub); transform: rotate(45deg); pointer-events: none; }
.perf-select { appearance: none; -webkit-appearance: none; min-height: 32px; max-width: 380px; padding: 5px 26px 5px 9px; border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--bg2); color: var(--sub2); font-size: 11px; font-weight: 650; cursor: pointer; min-width: 0; text-overflow: ellipsis; }
.perf-select:hover { color: var(--txt); border-color: var(--run); background: rgba(106,166,255,.07); }
.perf-select:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
[data-theme="light"] .perf-select { background: var(--neutral-2); }
[data-theme="light"] .perf-select:hover { background: rgba(106,166,255,.07); }
.perf-field { display: inline-flex; align-items: center; gap: var(--space-2); min-width: 0; }
.perf-label { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .08em; color: var(--sub); }
.badge { font-size: 11px; font-weight: 700; text-transform: uppercase; letter-spacing: .04em; padding: 3px 9px; border-radius: 99px; }
.badge.passed, .badge.success { background: var(--success-surface); color: var(--success-text); }
.badge.failed, .badge.error { background: var(--danger-surface); color: var(--danger-text); }
.badge.running, .badge.cancelled, .badge.unknown { background: var(--accent-surface); color: var(--run); }
.iconbtn { min-height: 32px; min-width: 32px; display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 5px 9px; border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--bg2); color: var(--sub2); font-size: 11px; font-weight: 650; cursor: pointer; }
.iconbtn:hover { color: var(--txt); border-color: var(--run); background: rgba(106,166,255,.07); }
.iconbtn:focus-visible { outline: 2px solid var(--focus); outline-offset: 1px; }
[data-theme="light"] .iconbtn { background: var(--neutral-2); }
[data-theme="light"] .iconbtn:hover { background: rgba(106,166,255,.07); }

/* ---- headline stats ---- */
.perf-stats { flex-shrink: 0; display: flex; gap: var(--space-2); flex-wrap: wrap; padding: var(--space-2) var(--space-4); border-bottom: 1px solid var(--line); background: var(--bg); }
.stat { display: flex; flex-direction: column; gap: 1px; padding: 4px 12px; border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--bg2); min-width: 86px; }
.stat .k { font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .08em; color: var(--sub); white-space: nowrap; }
.stat .v { font-size: var(--type-small); font-weight: 700; font-variant-numeric: tabular-nums; white-space: nowrap; }
.stat .v .delta { font-weight: 600; margin-left: 6px; }
.stat .v .delta.up { color: var(--fail); }
.stat .v .delta.down { color: var(--pass); }
.stat.warn { border-color: var(--warning-border); background: var(--warning-surface); }
.stat.warn .v { color: var(--warning-text); }

/* ---- timeline ---- */
.perf-timeline { position: relative; flex-shrink: 0; overflow-y: auto; overflow-x: hidden; border-bottom: 1px solid var(--line); background: var(--bg); min-height: 120px; }
.perf-timeline canvas { display: block; cursor: crosshair; }
.perf-timeline.panning canvas { cursor: grabbing; }
.perf-tooltip { position: fixed; z-index: 40; pointer-events: none; max-width: 420px; padding: 6px 10px; border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--raised); color: var(--txt); font-size: var(--type-caption); box-shadow: var(--shadow-raised); display: none; }
.perf-tooltip .tt-name { font-weight: 700; }
.perf-tooltip .tt-sub { color: var(--sub); font-variant-numeric: tabular-nums; }
.perf-hint { position: absolute; right: var(--space-3); bottom: var(--space-2); z-index: 5; font-size: var(--type-micro); color: var(--sub); background: color-mix(in srgb,var(--bg2) 84%,transparent); border: 1px solid var(--line); border-radius: 99px; padding: 2px 10px; pointer-events: none; }

/* ---- resizer + detail pane ---- */
.perf-resizer { flex-shrink: 0; height: 5px; cursor: row-resize; background: var(--bg3); border-bottom: 1px solid var(--line); }
.perf-resizer:hover { background: var(--accent-5); }
.perf-detail { flex: 1; min-height: 0; display: flex; min-width: 0; }
.perf-pane { flex: 1; min-width: 0; display: flex; flex-direction: column; }
.perf-tabs { flex-shrink: 0; display: flex; align-items: center; gap: 2px; padding: var(--space-2) var(--space-3) 0; border-bottom: 1px solid var(--line); background: var(--bg); }
.perf-tab { border: none; background: none; color: var(--sub); font-size: var(--type-small); font-weight: 600; padding: 6px 12px 8px; cursor: pointer; border-radius: var(--r-sm) var(--r-sm) 0 0; border-bottom: 2px solid transparent; }
.perf-tab:hover { color: var(--txt); background: var(--button-hover); }
.perf-tab.active { color: var(--txt); border-bottom-color: var(--focus); }
.perf-range-note { margin-left: auto; font-size: var(--type-caption); color: var(--sub); font-variant-numeric: tabular-nums; padding-bottom: 6px; display: flex; align-items: center; gap: var(--space-2); }
.perf-range-note .clear { border: 1px solid var(--line2); background: var(--bg2); color: var(--txt); border-radius: 99px; font-size: var(--type-micro); padding: 1px 8px; cursor: pointer; }
.perf-body { flex: 1; min-height: 0; overflow: auto; padding: var(--space-2) var(--space-3) var(--space-4); }
.perf-empty { color: var(--sub); font-size: var(--type-small); padding: var(--space-4); }
.perf-tabnote { color: var(--sub); font-size: var(--type-caption); padding: var(--space-2) var(--space-4) 0; }

/* ---- tables ---- */
table.perf { border-collapse: collapse; width: 100%; font-size: var(--type-small); }
table.perf th { position: sticky; top: 0; z-index: 2; text-align: left; font-size: var(--type-micro); text-transform: uppercase; letter-spacing: .08em; color: var(--sub); font-weight: 700; padding: 6px 10px; background: var(--bg); border-bottom: 1px solid var(--line2); white-space: nowrap; }
table.perf th.num, table.perf td.num { text-align: right; font-variant-numeric: tabular-nums; }
table.perf td { padding: 4px 10px; border-bottom: 1px solid var(--line); vertical-align: top; }
table.perf tbody tr { cursor: pointer; }
table.perf tbody tr:hover { background: var(--bg3); }
table.perf tbody tr.sel { background: var(--accent-surface); }
table.perf .bar { position: relative; display: inline-block; vertical-align: middle; width: 120px; height: 8px; margin-left: 8px; border-radius: 99px; background: var(--bg3); overflow: hidden; }
table.perf .bar i { position: absolute; inset: 0 auto 0 0; border-radius: 99px; background: var(--accent-8); }
table.perf .bar.full i { background: var(--fail); }
.kindchip { display: inline-block; min-width: 52px; text-align: center; font-size: var(--type-micro); font-weight: 700; text-transform: uppercase; letter-spacing: .05em; border-radius: 99px; padding: 1px 8px; }
.kindchip.tool { background: var(--accent-surface); color: var(--run); }
.kindchip.llm { background: var(--violet-surface); color: var(--ai); }
.kindchip.maestro { background: var(--cyan-3); color: var(--event); }
.kindchip.driver { background: var(--bg3); color: var(--sub); }
.tree-name { display: inline-flex; align-items: center; gap: 6px; }
.tree-caret { display: inline-flex; width: 14px; justify-content: center; color: var(--sub); font-size: 10px; }
.fail-txt { color: var(--fail); font-weight: 700; }
.pass-txt { color: var(--pass); font-weight: 700; }
.delta-pos { color: var(--fail); font-weight: 700; }
.delta-neg { color: var(--pass); font-weight: 700; }

/* ---- inspector ---- */
.perf-inspector { flex-shrink: 0; width: 340px; min-width: 0; border-left: 1px solid var(--line); background: var(--bg2); overflow-y: auto; padding: var(--space-3) var(--space-4) var(--space-5); }
.perf-inspector h3 { margin: 0 0 2px; font-size: var(--type-body); font-weight: 720; overflow-wrap: anywhere; }
.perf-inspector .close { float: right; border: none; background: none; color: var(--sub); font-size: 16px; cursor: pointer; padding: 0 2px; }
.perf-inspector .close:hover { color: var(--txt); }
.perf-inspector dl { display: grid; grid-template-columns: auto 1fr; gap: 3px 12px; margin: var(--space-3) 0 0; font-size: var(--type-small); }
.perf-inspector dt { color: var(--sub); font-size: var(--type-caption); white-space: nowrap; }
.perf-inspector dd { margin: 0; font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.perf-inspector pre { margin: var(--space-2) 0 0; padding: var(--space-2) var(--space-3); border: 1px solid var(--line); border-radius: var(--r-sm); background: var(--code-surface); color: var(--code-text); font-size: var(--type-caption); line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; max-height: 260px; overflow: auto; }
.perf-inspector .insp-err { margin-top: var(--space-2); padding: var(--space-2) var(--space-3); border: 1px solid var(--danger-border); border-radius: var(--r-sm); background: var(--danger-surface); color: var(--danger-text); font-size: var(--type-caption); overflow-wrap: anywhere; }

/* ---- footer ---- */
.perf-footer { flex-shrink: 0; border-top: 1px solid var(--line); background: var(--bg2); color: var(--sub); font-size: var(--type-caption); line-height: 1.6; padding: var(--space-2) var(--space-4); }
.perf-footer code { font-size: var(--type-caption); color: var(--txt); }
.perf-session-meta { overflow-wrap: anywhere; }
.perf-session-meta:empty { display: none; }
.perf-session-meta code { color: var(--sub2); }
`;
