// The Theater tab's stylesheet, appended to RUN_REPORT_CSS. Colors come from the report's design
// tokens (report-design-tokens.ts) so light and dark themes both hold; the stage is a dark
// monitor in both, so it takes its own pair of tokens per theme.
import { DEVICE_FRAME_CSS } from './run-report-device-frames';

export const THEATER_CSS = `
.tabmount { flex: 1; min-height: 0; display: flex; flex-direction: column; }
/* The Theater fills the report's main area edge to edge and scrolls its own panes. */
main.tabmountmain:has([data-tabmount="theater"]) { display: flex; flex-direction: column; overflow: hidden; padding: 0; }

.storyshell {
  container: storyshell / inline-size;
  display: flex; flex: 1 1 auto; min-height: 0; flex-direction: column;
  --story-stage: var(--neutral-12);
  --story-ink: var(--neutral-2);
  --story-ink-muted: color-mix(in oklab, var(--story-ink) 64%, var(--story-stage));
  --story-line: color-mix(in oklab, var(--story-ink) 14%, var(--story-stage));
  --story-raised: color-mix(in oklab, var(--story-ink) 6%, var(--story-stage));
  --story-hw: color-mix(in oklab, var(--story-ink) 16%, var(--story-stage));
  --story-hw-rim: color-mix(in oklab, var(--story-ink) 32%, var(--story-stage));
  --story-hw-deep: color-mix(in oklab, var(--story-ink) 3%, var(--story-stage));
  --story-screen: color-mix(in oklab, var(--story-ink) 7%, var(--story-stage));
  --story-inset-w: 14rem;
  --story-ease: cubic-bezier(0.3, 0.85, 0.25, 1);
  --story-band: 6%;
  --story-robot: var(--ai);
  --story-mono: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
}
[data-theme="dark"] .storyshell {
  --story-stage: var(--neutral-3);
  --story-ink: var(--neutral-12);
  --story-band: 9%;
}

/* Each device wears one of these, by its place in the cast. */
.storyshell [data-story-device="0"] { --story-device: var(--run); }
.storyshell [data-story-device="1"] { --story-device: var(--purple); }
.storyshell [data-story-device="2"] { --story-device: var(--decision); }
.storyshell [data-story-device="3"] { --story-device: var(--amber); }
.storyshell [data-story-device="4"] { --story-device: color-mix(in oklab, var(--run) 50%, var(--fail)); }
.storyshell [data-story-device="5"] { --story-device: color-mix(in oklab, var(--pass) 55%, var(--amber)); }

.storyshell button { font: inherit; }
.storysr { position: absolute; width: 1px; height: 1px; margin: -1px; padding: 0; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; border: 0; }

/* Facts and layout choices ---------------------------------------------------------------------- */

.storytoolbar { display: flex; flex: 0 0 auto; flex-wrap: wrap; gap: var(--space-2) var(--space-4); align-items: center; padding: var(--space-2) var(--space-4); border-bottom: 1px solid var(--line); background: var(--bg); }
.storyfacts { display: flex; min-width: 0; flex: 1 1 auto; flex-wrap: wrap; gap: 2px var(--space-4); align-items: center; font-size: var(--type-small); color: var(--sub); }
.storytitle { min-width: 0; max-width: 40rem; overflow: hidden; color: var(--txt); font-size: 13px; font-weight: var(--font-weight-emphasis); text-overflow: ellipsis; white-space: nowrap; }
.storyfact { white-space: nowrap; font-variant-numeric: tabular-nums; }
.storystatus { display: inline-flex; align-items: center; height: 20px; padding: 0 var(--space-2); border: 1px solid var(--line2); border-radius: 999px; color: var(--sub); font-size: var(--type-caption); font-weight: var(--font-weight-emphasis); text-transform: capitalize; }
.storystatus[data-outcome="passed"] { border-color: var(--success-border); background: var(--success-surface); color: var(--success-text); }
.storystatus[data-outcome="failed"] { border-color: var(--danger-border); background: var(--danger-surface); color: var(--danger-text); }
.storystatus[data-outcome="healed"] { border-color: var(--warning-border); background: var(--warning-surface); color: var(--warning-text); }
.storytoolbar .storyopen { display: inline-flex; align-items: center; gap: 6px; height: 28px; padding: 0 var(--space-3); border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--bg3); color: var(--txt); font-size: 12px; font-weight: var(--font-weight-emphasis); cursor: pointer; }
.storytoolbar .storyopen:hover { border-color: var(--run); background: var(--button-hover); }

.storyeyebrow { color: var(--sub); font-size: 0.6875rem; font-weight: var(--font-weight-strong); letter-spacing: 0.045em; text-transform: uppercase; }

.storydevice { display: inline-flex; gap: 0.375rem; align-items: center; font-weight: var(--font-weight-strong); white-space: nowrap; }
.storydeviceindex { display: inline-grid; width: 1rem; height: 1rem; place-items: center; border-radius: 0.25rem; background: var(--story-device); color: var(--bg); font-size: 0.625rem; font-weight: 650; line-height: 1; }
.storyvar { display: inline-block; padding: 0 0.3125rem; border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--bg3); color: var(--sub); font: 500 0.6875rem / 1.45 var(--story-mono); white-space: nowrap; vertical-align: 0.0625rem; }

.storycheck, .storyfailed { display: inline-block; margin-right: 0.375rem; padding: 0.1875rem 0.3125rem; border: 1px solid color-mix(in oklab, var(--pass) 40%, transparent); border-radius: 0.25rem; color: var(--status-passed-text); font-size: 0.625rem; font-weight: 650; letter-spacing: 0.045em; line-height: 1; text-transform: uppercase; vertical-align: 0.125rem; }
.storyfailed { margin: 0 0 0 0.375rem; border-color: color-mix(in oklab, var(--fail) 45%, transparent); color: var(--status-failed-text); }
.storyrobot { width: 0.75rem; height: 0.75rem; flex: none; fill: currentColor; }

/* Director -------------------------------------------------------------------------------------- */

.storydirector { display: grid; flex: 1 1 auto; grid-template-columns: 19rem minmax(0, 1fr); min-height: 0; }
.storydirector:has(.storyrailscroll[data-mode="yaml"]) { grid-template-columns: 23rem minmax(0, 1fr); }

.storyrail { position: relative; display: flex; min-height: 0; flex-direction: column; border-right: 1px solid var(--line); }
.storyrailhead { display: flex; flex: 0 0 auto; gap: 0.625rem; align-items: center; height: 2.75rem; padding: 0 0.75rem 0 1rem; border-bottom: 1px solid var(--line); }
.storyrailmeta { overflow: hidden; color: var(--sub); font-size: 0.75rem; font-variant-numeric: tabular-nums; text-overflow: ellipsis; white-space: nowrap; }
.storyrailscroll { flex: 1 1 auto; min-height: 0; padding: 0.5rem 0.5rem 4rem; overflow-y: auto; overscroll-behavior: contain; }
.storyrailscroll[data-mode="yaml"] { padding: 0.5rem 0 4rem; }
.storysteps, .storyactions { margin: 0; padding: 0; list-style: none; }
.storyrailscroll button { width: 100%; border: 0; background: none; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.storyrailscroll button:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.storyrailhead .storysegmented { margin-left: auto; }
.storyrailhead .storysegmented button { padding: 0.125rem 0.5rem; }

.storyact { display: flex; gap: 0.5rem; align-items: center; margin: 1.25rem 0.5rem 0.25rem; color: var(--sub); font-size: 0.6875rem; font-weight: var(--font-weight-strong); letter-spacing: 0.045em; text-transform: uppercase; white-space: nowrap; }
.storyact:first-child { margin-top: 0.5rem; }
.storyact::after { content: ""; flex: 1 1 auto; height: 1px; background: var(--line); }

.storyscene { margin-top: 0.75rem; }
.storyact + .storyscene { margin-top: 0.25rem; }
.storysteps > .storyscene:first-child { margin-top: 0.25rem; }
.storyscene button { display: flex; gap: 0.5rem; align-items: center; padding: 0.375rem 0.5rem; border-radius: var(--r-sm); color: var(--sub); font-size: 0.75rem; }
.storyscene button:hover { color: var(--txt); }
.storyscene .storydevice { color: var(--txt); }
.storyscenehardware { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.storyscene time { margin-left: auto; font: 0.6875rem / 1 var(--story-mono); font-variant-numeric: tabular-nums; }

.storychapter > button { display: grid; grid-template-columns: 1.625rem minmax(0, 1fr); padding: 0.375rem 0.5rem; border-radius: var(--r-sm); color: var(--txt); font-size: 0.8125rem; font-weight: var(--font-weight-emphasis); line-height: 1.4; }
.storychapter > button:hover { background: var(--button-hover); }
.storychapter[data-state="past"] > button { color: var(--sub); }
.storychapter[data-state="now"] > button { background: var(--button-hover); font-weight: 600; }
.storychapterno { display: inline-grid; width: 1.25rem; height: 1.25rem; place-items: center; border-radius: 50%; background: var(--step-token-surface); color: var(--step-token-text); font-size: 0.6875rem; font-weight: 650; line-height: 1; font-variant-numeric: tabular-nums; }
.storychapter > button .storychapterno { margin-top: -0.0625rem; }
.storychapter[data-state="past"] .storychapterno { color: var(--sub); }
.storychapter[data-state="now"] .storychapterno { background: var(--txt); color: var(--bg); }
.storychapter[data-outcome="failed"] .storychapterno { background: var(--fail); color: var(--bg); }

/* A self-heal hangs under the step it retried, amber when it worked and red when it failed too. */
.storychapter[data-heal] { --story-heal: var(--amber); --story-heal-ink: var(--bg); margin: 0.125rem 0 0.375rem; border: 1px solid color-mix(in oklab, var(--story-heal) 35%, transparent); border-radius: var(--r-md); background: color-mix(in oklab, var(--story-heal) 6%, transparent); }
.storychapter[data-heal][data-outcome="failed"] { --story-heal: var(--fail); }
.storychapter[data-heal] > button { border-radius: var(--r-sm) var(--r-sm) 0 0; background: var(--story-heal); color: var(--story-heal-ink); }
.storychapter[data-heal] > button:hover, .storychapter[data-heal][data-state="now"] > button { background: color-mix(in oklab, var(--story-heal) 90%, var(--txt)); }
.storychapterno[data-heal], .storychapter[data-heal] .storychapterno { background: var(--story-robot); color: var(--bg); }
.storychapterno .storyrobot { width: 0.8125rem; height: 0.8125rem; }
.storyhealtitle { display: flex; gap: 0.5rem; align-items: baseline; font-size: 0.75rem; font-weight: 650; }
.storyhealtitle .storyfailed { margin: 0; border-color: color-mix(in oklab, var(--story-heal-ink) 60%, transparent); color: inherit; }
.storychapter[data-heal] .storyactions [data-state="now"] button { background: color-mix(in oklab, var(--story-heal) 14%, transparent); }
.storychapter[data-heal] .storyactions button::before { background: var(--story-heal); opacity: 1; }
.storychapter[data-heal] .storyactions [data-state="now"] .storyactiontext { -webkit-line-clamp: unset; }

.storyactions { padding: 0.125rem 0 0.375rem 1.625rem; }
.storyactions button { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) auto; gap: 0.5rem; align-items: baseline; padding: 0.25rem 0.5rem 0.25rem 1.25rem; border-radius: var(--r-sm); color: var(--sub); font-size: 0.75rem; line-height: 1.4; }
.storyactions button::before { content: ""; position: absolute; top: 0.75rem; left: 0.5rem; width: 0.25rem; height: 0.25rem; border-radius: 50%; background: currentColor; opacity: 0.6; }
.storyactions button:hover { color: var(--txt); }
.storyactions [data-state="past"] button { color: color-mix(in oklab, var(--sub) 75%, var(--bg)); }
.storyactions [data-state="now"] button { background: var(--button-hover); color: var(--txt); }
.storyactions [data-ok="false"] button { color: var(--status-failed-text); }
.storyactiontext { display: -webkit-box; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.storyactionmeta { color: var(--sub); font: 0.6875rem / 1 var(--story-mono); font-variant-numeric: tabular-nums; white-space: nowrap; }

.storyhandoff { margin: 0.625rem 0.25rem; }
.storyhandoff button { display: grid; gap: 0.375rem; padding: 0.5rem 0.625rem; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--raised); font-size: 0.75rem; }
.storyhandoff button:hover { background: var(--button-hover); }
.storyhandoff[data-live] button { border-color: color-mix(in oklab, var(--run) 55%, transparent); }
.storyhandoffroute { display: flex; gap: 0.375rem; align-items: center; color: var(--sub); }
.storyhandoffroute .storydevice { color: var(--txt); }
.storyhandoffvalue { display: flex; flex-wrap: wrap; gap: 0.375rem; align-items: center; color: var(--sub); font-size: 0.6875rem; font-weight: var(--font-weight-strong); letter-spacing: 0.045em; text-transform: uppercase; }
.storyhandoffvalue .storyvar { letter-spacing: 0; text-transform: none; }
.storyhandoffvalue > span { font-weight: var(--font-weight-body); letter-spacing: 0; text-transform: none; }

/* YAML script */

.storyyaml { margin: 0; padding: 0; font: 0.71875rem / 1.6 var(--story-mono); list-style: none; tab-size: 2; }
.storyyamlline { display: grid; grid-template-columns: 2.75rem minmax(0, 1fr); padding-right: 0.75rem; color: var(--txt); }
.storyyamlline code { font: inherit; overflow-wrap: anywhere; white-space: pre-wrap; }
.storyyamlno { padding-right: 0.75rem; color: var(--sub); font-variant-numeric: tabular-nums; text-align: right; user-select: none; }
.storyyamlline[data-seek] { cursor: pointer; }
.storyyamlline[data-seek]:hover { background: var(--button-hover); }
.storyyamlline[data-state="rest"] code { opacity: 0.55; }
.storyyamlline[data-state="next"] code { opacity: 0.8; }
.storyyamlline:is([data-state="done"], [data-state="next"], [data-state="now"]) { background: color-mix(in oklab, var(--run) var(--story-band), transparent); }
.storyyamlline[data-state="now"] { background: color-mix(in oklab, var(--run) 16%, transparent); }
.storyyamlline:is([data-state="done"], [data-state="now"]) .storyyamlno { color: var(--txt); }
.storyyamlline[data-state="now"] .storyyamlno { font-weight: 650; }
.storyyamlline .syntax-key { color: var(--run); }
.storyyamlline .syntax-string { color: var(--status-passed-text); }
.storyyamlline .syntax-number { color: var(--status-self-healed-text); }
.storyyamlline .syntax-keyword { color: var(--purple); }
.storyyamlline .syntax-comment, .storyyamlline .syntax-punct { color: var(--sub); }
.storyyamlvar { border-radius: 0.25rem; background: var(--bg3); box-shadow: 0 0 0 1px var(--line2); }

.storynowpill { position: absolute; bottom: 1rem; left: 50%; height: 1.75rem; padding: 0 0.75rem; border: 1px solid var(--line2); border-radius: 999px; background: var(--raised); color: var(--txt); box-shadow: var(--shadow-raised); font-size: 0.75rem; font-weight: var(--font-weight-emphasis); opacity: 0; pointer-events: none; translate: -50% 0.5rem; transition: opacity 160ms ease, translate 160ms ease; cursor: pointer; }
.storynowpill[data-show] { opacity: 1; pointer-events: auto; translate: -50% 0; }
.storynowpill:hover { background: var(--button-hover); }
.storynowpill:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }

/* Program, wall and frames ---------------------------------------------------------------------- */

.storymain { display: grid; grid-template-rows: minmax(0, 1fr) auto; gap: 0.75rem; min-width: 0; min-height: 0; padding: 0.75rem 1rem 0.75rem 0.75rem; container-type: inline-size; }
.storystage { position: relative; display: grid; grid-template-columns: minmax(0, 1fr) 11.5rem; min-height: 0; overflow: hidden; border-radius: var(--r-lg); background: var(--story-stage); color: var(--story-ink); }
.storydirector[data-single] .storystage, .storystage[data-grid] { grid-template-columns: minmax(0, 1fr); }
.storyprogram { position: relative; min-height: 0; overflow: hidden; }
.storyprogram .storyvar { border-color: var(--story-line); background: var(--story-raised); color: var(--story-ink-muted); }
.storyprogramhead { position: absolute; z-index: 4; top: 0.75rem; right: 0.875rem; left: 0.875rem; display: flex; gap: 0.75rem; align-items: center; height: 1.375rem; font-size: 0.75rem; }
.storyprogramhead .storyeyebrow { color: var(--story-ink-muted); }
.storycamera { display: flex; gap: 0.5rem; align-items: center; min-width: 0; color: var(--story-ink-muted); white-space: nowrap; }
.storycamera .storydevice { color: var(--story-ink); }
.storybadges { display: flex; gap: 0.375rem; margin-left: auto; }
.storybadge { display: inline-flex; gap: 0.375rem; align-items: center; height: 1.375rem; padding: 0 0.5rem; border: 1px solid var(--story-line); border-radius: 0.3125rem; color: var(--story-ink-muted); font-size: 0.6875rem; font-weight: var(--font-weight-strong); font-variant-numeric: tabular-nums; letter-spacing: 0.045em; text-transform: uppercase; white-space: nowrap; }
.storybadge[data-tone="lapse"] { background: var(--story-raised); color: var(--story-ink); }
.storybadge[data-tone="heal"] { --story-heal: var(--amber); padding-left: 0.1875rem; border-color: color-mix(in oklab, var(--story-heal) 45%, transparent); background: color-mix(in oklab, var(--story-heal) 16%, var(--story-stage)); color: var(--story-heal); }
.storybadge .storychapterno { width: 1rem; height: 1rem; }
.storybadge .storyrobot { width: 0.6875rem; height: 0.6875rem; }
.storybadge[hidden] { display: none; }

.storyzone { position: absolute; top: 2.875rem; right: 1.25rem; bottom: 1.25rem; left: 1.25rem; pointer-events: none; }
.storygrid { display: grid; gap: 0.75rem; }
.storygridcell { display: grid; grid-template-rows: auto minmax(0, 1fr); gap: 0.5rem; min-width: 0; min-height: 0; padding: 0.5rem 0.625rem 0.625rem; border: 1px solid var(--story-line); border-radius: var(--r-md); transition: border-color 200ms var(--story-ease); }
.storygridcell[data-role="program"] { border-color: var(--story-device); }
.storygridwell { min-height: 0; }

.storyinset { position: absolute; z-index: 1; right: 0.875rem; bottom: 0.875rem; display: flex; width: var(--story-inset-w); height: 11rem; flex-direction: column; gap: 0.375rem; padding: 0.5rem; border: 1px solid var(--story-line); border-radius: var(--r-md); background: var(--story-raised); opacity: 0; scale: 0.96; transform-origin: 100% 100%; transition: opacity 240ms var(--story-ease), scale 240ms var(--story-ease); }
.storyinset[data-show] { opacity: 1; scale: 1; }
.storyinsetlabel { display: flex; gap: 0.5rem; align-items: center; justify-content: space-between; min-height: 1.125rem; color: var(--story-ink-muted); font-size: 0.6875rem; white-space: nowrap; }
.storyinsetlabel .storydevice { color: var(--story-ink); font-size: 0.75rem; }
.storyinsetwell { flex: 1 1 auto; min-height: 0; }

.storylinks { position: absolute; z-index: 2; inset: 0; width: 100%; height: 100%; overflow: visible; pointer-events: none; fill: none; stroke: var(--story-ink-muted); stroke-width: 2.5; stroke-linecap: round; stroke-linejoin: round; opacity: 0; transition: opacity 160ms var(--story-ease); }
/* Waits for the program frame to make room before drawing to it. */
.storylinks[data-show] { opacity: 1; transition: opacity 240ms var(--story-ease) 220ms; }
.storyhandoffnode { position: absolute; z-index: 4; top: 0; left: 0; display: grid; gap: 0.3125rem; justify-items: start; padding: 0.5rem 0.625rem 0.5625rem; border: 1px solid var(--story-line); border-radius: var(--r-md); background: var(--story-raised); box-shadow: var(--shadow-device); opacity: 0; scale: 0.96; pointer-events: none; transition: opacity 160ms var(--story-ease), scale 160ms var(--story-ease); }
.storyhandoffnode[data-show] { opacity: 1; scale: 1; transition: opacity 240ms var(--story-ease) 120ms, scale 240ms var(--story-ease) 120ms; }
.storyhandoffnode .storyvar { border-color: var(--story-line); background: var(--story-stage); color: var(--story-ink); font-size: 0.75rem; }

.storywall { display: grid; grid-template-rows: auto minmax(0, 1fr); gap: 0.5rem; min-height: 0; padding: 0.75rem 0.75rem 0.75rem 0; }
.storywallhead { display: flex; align-items: center; justify-content: space-between; height: 1.375rem; }
.storywallhead .storyeyebrow { color: var(--story-ink-muted); }
.storyauto { height: 1.375rem; padding: 0 0.5rem; border: 1px solid var(--story-line); border-radius: 0.3125rem; background: transparent; color: var(--story-ink-muted); font-size: 0.6875rem; font-weight: var(--font-weight-strong); cursor: pointer; }
.storyauto:hover { color: var(--story-ink); }
.storyauto[aria-pressed="true"] { background: var(--story-raised); color: var(--story-ink); }
.storywallgrid { display: grid; grid-template-rows: repeat(var(--storycams), minmax(0, 1fr)); gap: 0.5rem; min-height: 0; }
.storycam { display: grid; grid-template-rows: minmax(0, 1fr) auto; gap: 0.375rem; min-height: 0; padding: 0.375rem 0.375rem 0.4375rem; border: 1px solid var(--story-line); border-radius: var(--r-md); background: transparent; color: var(--story-ink); text-align: left; cursor: pointer; }
.storycam:hover { background: var(--story-raised); }
.storycam[data-role="program"] { border-color: var(--story-ink); }
.storycam[aria-pressed="true"] { box-shadow: inset 0 0 0 1px var(--story-ink); }
.storycamwell { display: grid; min-height: 0; place-items: center; border-radius: var(--r-sm); background: var(--story-raised); }
.storycammeta { display: grid; grid-template-columns: auto minmax(0, 1fr) auto; gap: 0.375rem; align-items: center; font-size: 0.75rem; }
.storycamhardware { overflow: hidden; color: var(--story-ink-muted); text-overflow: ellipsis; white-space: nowrap; }
.storycamstate { color: var(--story-ink); font-size: 0.625rem; font-weight: 650; letter-spacing: 0.045em; text-transform: uppercase; }

.storyframes { position: absolute; z-index: 3; inset: 0; pointer-events: none; }
.storyframe { position: absolute; top: 0; left: 0; transform-origin: 0 0; }
.storyframes[data-settled] .storyframe { transition: transform 420ms var(--story-ease), opacity 200ms var(--story-ease); }
.storyframe[data-role="program"] { z-index: 2; }
.storyframe[data-role="inset"] { z-index: 3; }
.storystage[data-grid] .storyframe[data-role="wall"] { opacity: 0.6; }

/* The screen: the recording, else the run's screenshots on the same clock, with the marks over it. */
.devframecontent > .storymirror { position: absolute; inset: 0; width: 100%; height: 100%; }
.devframecontent > video, .devframecontent > .storyshotimg { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: contain; }
.devframecontent[data-source="video"] > .storyshotimg { display: none; }
.devframecontent[data-source="shots"] > video { display: none; }
.devframecontent[data-source="none"] > video, .devframecontent[data-source="none"] > .storyshotimg { display: none; }
.storynorecording { position: absolute; inset: 0; display: none; place-items: center; color: color-mix(in oklab, var(--story-ink) 55%, transparent); font-size: calc(0.75rem / var(--storyscale, 1)); }
.devframecontent[data-source="none"] > .storynorecording { display: grid; }
.storywaiting { position: absolute; top: 50%; left: 50%; display: none; width: calc(1.75rem / var(--storyscale, 1)); aspect-ratio: 1; margin: calc(-0.875rem / var(--storyscale, 1)); border: calc(2px / var(--storyscale, 1)) solid color-mix(in oklab, var(--story-ink) 25%, transparent); border-top-color: var(--story-ink); border-radius: 50%; animation: storyspin 800ms linear infinite; }
.devframecontent[data-waiting] > .storywaiting { display: block; }
@keyframes storyspin { to { rotate: 360deg; } }

.storytaps { position: absolute; inset: 0; overflow: hidden; pointer-events: none; }
.storytap { position: absolute; width: calc(2.25rem / var(--storyscale, 1)); aspect-ratio: 1; border: calc(2px / var(--storyscale, 1)) solid var(--story-ink); border-radius: 50%; background: color-mix(in oklab, var(--story-ink) 30%, transparent); box-shadow: 0 0 0 calc(1px / var(--storyscale, 1)) color-mix(in oklab, var(--story-stage) 50%, transparent); animation: storytap 650ms ease-out forwards; }
@keyframes storytap {
  from { opacity: 1; transform: translate(-50%, -50%) scale(0.4); }
  to { opacity: 0; transform: translate(-50%, -50%) scale(1.6); }
}

/* Marks sit on the app's own pixels, so they keep one look in either theme, with a dark halo to
   hold up on light screens. */
.storymark { --story-mark-halo: 0 0 0 calc(1px / var(--storyscale, 1)) color-mix(in oklab, var(--story-stage) 60%, transparent); position: absolute; box-sizing: border-box; pointer-events: none; animation: storymark 160ms ease-out; }
.storymark[data-kind="tap"], .storymark[data-kind="check"] { width: calc(1.5rem / var(--storyscale, 1)); aspect-ratio: 1; border-radius: 50%; translate: -50% -50%; }
.storymark[data-kind="tap"] { border: calc(3px / var(--storyscale, 1)) solid var(--story-ink); background: color-mix(in oklab, var(--story-stage) 20%, transparent); box-shadow: 0 0 0 calc(1.5px / var(--storyscale, 1)) color-mix(in oklab, var(--story-stage) 60%, transparent), inset 0 0 0 calc(1px / var(--storyscale, 1)) color-mix(in oklab, var(--story-stage) 40%, transparent); }
.storymark[data-kind="check"] { border: calc(2px / var(--storyscale, 1)) solid var(--pass); background: color-mix(in oklab, var(--pass) 30%, transparent); box-shadow: var(--story-mark-halo); }
.storymark[data-kind="check"][data-ok="false"] { border-color: var(--fail); background: color-mix(in oklab, var(--fail) 30%, transparent); }
.storymark[data-kind="box"] { border-radius: calc(4px / var(--storyscale, 1)); outline: calc(2px / var(--storyscale, 1)) solid var(--pass); outline-offset: calc(2px / var(--storyscale, 1)); background: color-mix(in oklab, var(--pass) 14%, transparent); box-shadow: 0 0 0 calc(5px / var(--storyscale, 1)) color-mix(in oklab, var(--story-stage) 20%, transparent); }
.storymark[data-kind="box"][data-ok="false"] { outline-color: var(--fail); background: color-mix(in oklab, var(--fail) 14%, transparent); }
.storymark[data-kind="swipe"] { inset: 0; width: 100%; height: 100%; overflow: visible; fill: none; stroke: var(--story-ink); stroke-linecap: round; filter: drop-shadow(0 0 calc(1.5px / var(--storyscale, 1)) color-mix(in oklab, var(--story-stage) 70%, transparent)); }
.storymark[data-kind="swipe"] line { stroke-dasharray: 1; stroke-dashoffset: 1; animation: storyswipe 420ms ease-out forwards; }
.storymark[data-kind="swipe"] circle { fill: var(--story-ink); stroke: none; }
@keyframes storymark { from { opacity: 0; } }
@keyframes storyswipe { to { stroke-dashoffset: 0; } }

/* Transport ------------------------------------------------------------------------------------- */

.storytransport { display: grid; gap: 0.375rem; }
.storytransportbar { display: flex; gap: 1rem; align-items: center; min-width: 0; height: 2rem; }
.storyplay { display: grid; flex: 0 0 auto; width: 2rem; height: 2rem; place-items: center; padding: 0; border: 0; border-radius: 50%; background: var(--txt); color: var(--bg); cursor: pointer; transition: opacity 140ms ease, scale 140ms var(--story-ease); }
.storyplay:hover { opacity: 0.88; }
.storyplay:active { scale: 0.94; }
.storyplay svg { width: 1rem; height: 1rem; fill: currentColor; }
.storyplay [data-icon="pause"], .storyplay[data-playing] [data-icon="play"] { display: none; }
.storyplay[data-playing] [data-icon="pause"] { display: block; }
.storytime { flex: 0 0 auto; color: var(--sub); font-size: 0.8125rem; line-height: 1; font-variant-numeric: tabular-nums; white-space: nowrap; }
.storytime b { margin-right: 0.25rem; color: var(--txt); font-weight: 600; }

.storysegmented { display: inline-flex; flex: 0 0 auto; padding: 0.1875rem; border-radius: var(--r-lg); background: var(--bg3); }
.storysegmented button { padding: 0.1875rem 0.625rem; border: 0; border-radius: var(--r-sm); background: transparent; color: var(--sub); font-size: 0.75rem; font-variant-numeric: tabular-nums; font-weight: var(--font-weight-emphasis); cursor: pointer; }
.storysegmented button:hover { color: var(--txt); }
.storysegmented button[aria-pressed="true"] { background: var(--raised); color: var(--txt); box-shadow: 0 1px 2px color-mix(in oklab, var(--txt) 12%, transparent); }

.storyswitch { display: inline-flex; flex: 0 0 auto; gap: 0.5rem; align-items: center; padding: 0; border: 0; background: none; color: var(--sub); font-size: 0.8125rem; white-space: nowrap; cursor: pointer; }
.storyswitch:hover, .storyswitch[aria-checked="true"] { color: var(--txt); }
.storyswitchtrack { position: relative; width: 1.75rem; height: 1rem; border-radius: 999px; background: var(--line2); transition: background-color 140ms ease; }
.storyswitchtrack::after { content: ""; position: absolute; top: 0.125rem; left: 0.125rem; width: 0.75rem; height: 0.75rem; border-radius: 50%; background: var(--bg); box-shadow: 0 1px 2px color-mix(in oklab, var(--txt) 20%, transparent); transition: translate 140ms var(--story-ease); }
.storyswitch[aria-checked="true"] .storyswitchtrack { background: var(--run); }
.storyswitch[aria-checked="true"] .storyswitchtrack::after { translate: 0.75rem 0; }
.storykeys { margin-left: auto; overflow: hidden; color: var(--sub); font-size: 0.75rem; line-height: 1; text-overflow: ellipsis; white-space: nowrap; }

@container (max-width: 56rem) {
  .storykeys { display: none; }
  .storyprogram { --story-inset-w: 12rem; }
}

.storyplay:focus-visible, .storysegmented button:focus-visible, .storyswitch:focus-visible, .storyauto:focus-visible, .storycam:focus-visible, .storyopen:focus-visible { outline: 2px solid var(--focus); outline-offset: 2px; }

.storyscrub { height: 2rem; border-radius: 0.25rem; cursor: pointer; touch-action: none; user-select: none; }
.storyscrub:focus-visible { outline: 2px solid var(--focus); outline-offset: 3px; }
.storyscrubtrack { position: relative; height: 100%; }
.storyscrubacts { position: absolute; top: 0; right: 0; left: 0; height: 0.875rem; }
.storyscrubacts span { position: absolute; top: 0; height: 0.875rem; padding-left: 0.375rem; overflow: hidden; border-left: 1px solid var(--line2); color: var(--sub); font-size: 0.625rem; font-weight: var(--font-weight-strong); letter-spacing: 0.045em; line-height: 0.875rem; text-overflow: ellipsis; text-transform: uppercase; white-space: nowrap; }
.storyscrubbar { position: absolute; top: 1.125rem; right: 0; left: 0; height: 0.625rem; overflow: hidden; border-radius: 0.1875rem; background: var(--bg3); }
.storyscrubaction { position: absolute; top: 0; bottom: 0; min-width: 2px; background: var(--story-device); }
.storyscrubheal { position: absolute; top: 0; bottom: 0; background: color-mix(in oklab, var(--amber) 35%, transparent); }
.storyscrubaction[data-healed] { background: var(--amber); }
.storyscrubaction[data-ok="false"] { background: var(--fail); }
.storyscrubtick { position: absolute; top: 0; bottom: 0; width: 1px; background: var(--bg); }
.storyscrubunplayed { position: absolute; top: 0; right: 0; bottom: 0; background: color-mix(in oklab, var(--bg) 50%, transparent); }
.storyscrubbar[data-focus] .storyscrubunplayed { display: none; }
.storyscrubdim { position: absolute; top: 0; bottom: 0; background: color-mix(in oklab, var(--bg) 70%, transparent); }
.storyscrubdim[hidden] { display: none; }
.storyscrubhead { position: absolute; top: 0.9375rem; width: 2px; height: 1rem; margin-left: -1px; border-radius: 1px; background: var(--txt); pointer-events: none; }
.storyscrubhead::before { content: ""; position: absolute; top: 50%; left: 50%; width: 0.625rem; height: 0.625rem; border: 2px solid var(--bg); border-radius: 50%; background: var(--txt); translate: -50% -50%; }
.storyscrubtip { position: absolute; z-index: 6; bottom: calc(100% + 0.375rem); display: grid; gap: 0.125rem; max-width: 14rem; padding: 0.375rem 0.5rem; border: 1px solid var(--line2); border-radius: var(--r-md); background: var(--raised); box-shadow: var(--shadow-raised); font-size: 0.75rem; pointer-events: none; translate: -50% 0; }
.storyscrubtip[hidden] { display: none; }
.storyscrubtiphead { display: flex; gap: 0.5rem; align-items: center; color: var(--sub); font-variant-numeric: tabular-nums; }
.storyscrubtiphead b { color: var(--txt); font-family: var(--story-mono); font-size: 0.6875rem; }
.storyscrubtiphead .storydevice { margin-left: auto; color: var(--txt); }
.storyscrubtiptext { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

/* Narrow panels stack the script over the stage, and the wall under the program. */
@container storyshell (max-width: 48rem) {
  .storydirector, .storydirector:has(.storyrailscroll[data-mode="yaml"]) { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 2fr) minmax(0, 3fr); }
  .storyrail { border-right: 0; border-bottom: 1px solid var(--line); }
  .storymain { padding: 0.5rem; }
  .storystage { grid-template-columns: minmax(0, 1fr); grid-template-rows: minmax(0, 1fr) 6.5rem; }
  .storydirector[data-single] .storystage, .storystage[data-grid] { grid-template-rows: minmax(0, 1fr); }
  .storywall { padding: 0 0.5rem 0.5rem; }
  .storywallgrid { grid-template-rows: minmax(0, 1fr); grid-template-columns: repeat(var(--storycams), minmax(0, 1fr)); }
  .storytransportbar { flex-wrap: wrap; row-gap: 0.375rem; height: auto; }
}

@media (prefers-reduced-motion: reduce) {
  .storyframes[data-settled] .storyframe, .storyinset, .storylinks, .storyhandoffnode, .storynowpill, .storyplay, .storyswitchtrack, .storyswitchtrack::after, .devframecontent { transition: none; }
  .storytap { display: none; }
  .storymark, .storymark[data-kind="swipe"] line { animation: none; stroke-dashoffset: 0; }
  .storywaiting { animation: none; }
}
/* The same, when the viewer is told rather than the OS. */
.storyshell[data-reduced] .storyframes .storyframe, .storyshell[data-reduced] .storyinset, .storyshell[data-reduced] .storylinks, .storyshell[data-reduced] .storyhandoffnode, .storyshell[data-reduced] .devframecontent { transition: none; }
.storyshell[data-reduced] .storytap { display: none; }
.storyshell[data-reduced] .storymark, .storyshell[data-reduced] .storymark[data-kind="swipe"] line { animation: none; stroke-dashoffset: 0; }
${DEVICE_FRAME_CSS}`;
