// The Summary tab's stylesheet, appended to RUN_REPORT_CSS. Colors come from the report's design
// tokens (report-design-tokens.ts) so light and dark themes both hold.
//
// Every state hook is a `data-streams-*` attribute so none can collide with the viewer's own
// delegated handlers or global rules.
export const SUMMARY_CSS = `
/* The tab owns the whole main area: no page padding, and the board scrolls on its own. */
main.tabmountmain:has(> .tabmount[data-tabmount="summary"]) { padding: 0; overflow: hidden; display: flex; flex-direction: column; }

.streamsroot {
  --streams-step-w: 13rem;
  /* The Screen column's right gutter, where the screen's time rides the line. */
  --streams-stamp-w: 3.875rem;
  --streams-shot-w: calc(13rem + var(--streams-stamp-w));
  --streams-line-x: 0.875rem;
  --streams-shadow: rgb(0 0 0 / 0.3);
  position: relative;
  display: flex;
  flex: 1 1 auto;
  min-height: 0;
  flex-direction: column;
  background: var(--bg);
}
[data-theme="dark"] .streamsroot { --streams-shadow: rgb(0 0 0 / 0.6); }

.streamsempty { margin: auto; max-width: 28rem; padding: 2rem; color: var(--sub); font-size: 13px; text-align: center; }

.streamstoolbar {
  display: flex;
  flex: 0 0 auto;
  gap: 1rem;
  align-items: center;
  justify-content: space-between;
  min-height: calc(3rem + 1px);
  padding: 0.5rem var(--page-x);
  border-bottom: 1px solid var(--line);
}

.streamsfacts { display: flex; flex-wrap: wrap; gap: 0.25rem 1.125rem; min-width: 0; margin: 0; }
.streamsfacts > div { display: flex; gap: 0.375rem; align-items: baseline; min-width: 0; }
.streamsfacts dt { color: var(--sub); font-size: 11px; font-weight: 500; letter-spacing: 0.045em; text-transform: uppercase; }
.streamsfacts dd { margin: 0; color: var(--txt); font-size: 13px; font-weight: 500; white-space: nowrap; }
.streamsfacts dd[data-streams-busy] { color: var(--sub); }
.streamstoolbarend { display: flex; flex: 0 0 auto; gap: 0.5rem; align-items: center; }

/* Find: one field with its count and steppers inside. */
.streamsfind {
  display: flex;
  gap: 0.125rem;
  align-items: center;
  width: min(22rem, 40vw);
  max-width: 100%;
  height: 2rem;
  padding: 0 0.25rem 0 0.5rem;
  border: 1px solid var(--line2);
  border-radius: var(--r-md);
  background: var(--bg2);
}
.streamsfind:focus-within { outline: 2px solid var(--focus); outline-offset: -1px; }
.streamsfindicon { flex: 0 0 auto; width: 0.875rem; height: 0.875rem; fill: none; stroke: var(--sub); stroke-width: 2; stroke-linecap: round; }
.streamsfind input { flex: 1 1 0; width: 0; min-width: 0; padding: 0 0.25rem 0 0.375rem; border: 0; background: none; color: var(--txt); font: inherit; font-size: 13px; outline: none; }
.streamsfind input:focus-visible { outline: none; }
.streamsfind input::placeholder { color: var(--sub); }
.streamsfindcount { flex: 0 0 auto; padding: 0 0.375rem; color: var(--sub); font-size: 12px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.streamsfind button {
  display: inline-flex; flex: 0 0 auto; align-items: center; justify-content: center;
  width: 1.5rem; height: 1.5rem; padding: 0; border: 0; border-radius: var(--r-sm);
  background: none; color: var(--sub); cursor: pointer;
}
.streamsfind button:hover:not(:disabled) { background: var(--button-hover); color: var(--txt); }
.streamsfind button:disabled { opacity: 0.4; cursor: default; }
.streamsfind button svg,
.streamsmenubutton svg,
.streamsadd svg,
.streamslanehide svg,
.streamssteptoggle svg { width: 0.875rem; height: 0.875rem; fill: none; stroke: currentColor; stroke-width: 2; stroke-linecap: round; stroke-linejoin: round; }

.streamsmenubutton {
  display: inline-flex; gap: 0.375rem; align-items: center; justify-content: center;
  min-width: 2rem; height: 2rem; padding: 0 0.4375rem;
  border: 1px solid var(--line2); border-radius: var(--r-md);
  background: var(--bg2); color: var(--txt); font: inherit; font-size: 13px; cursor: pointer;
}
.streamsmenubutton:hover,
.streamsmenubutton[aria-expanded="true"] { background: var(--button-hover); }
.streamsmenubuttoncount { padding: 0 0.375rem; border-radius: 999px; background: var(--control-counter-surface); color: var(--control-counter-text); font-size: 11px; font-weight: 500; line-height: 1.125rem; font-variant-numeric: tabular-nums; }

.streamsmenu {
  position: absolute; z-index: 30; top: 3.25rem; right: var(--page-x); width: 17rem; padding: 0.375rem;
  border: 1px solid var(--line2); border-radius: var(--r-lg);
  background: var(--raised); color: var(--txt); box-shadow: 0 10px 30px -10px var(--streams-shadow);
}
.streamsmenu[data-streams-at] { right: auto; top: auto; }
.streamsmenuhead { display: flex; align-items: center; justify-content: space-between; padding: 0.375rem 0.5rem 0.5rem; color: var(--sub); font-size: 11px; font-weight: 500; letter-spacing: 0.045em; text-transform: uppercase; }
.streamsmenuhead button { padding: 0; border: 0; background: none; color: var(--sub); font: inherit; cursor: pointer; }
.streamsmenuhead button:hover { color: var(--txt); }
.streamsmenu ul { max-height: min(24rem, 60vh); margin: 0; padding: 0; overflow-y: auto; list-style: none; }
.streamsmenu li { cursor: grab; user-select: none; }
.streamsmenu label { display: flex; gap: 0.5rem; align-items: center; padding: 0.375rem 0.5rem 0.375rem 0.375rem; border-radius: var(--r-sm); font-size: 13px; cursor: grab; }
.streamsmenu label:hover,
.streamsmenu li[data-streams-dragging] label { background: var(--button-hover); }
.streamsmenu li:hover .streamsgrip { opacity: 1; }
.streamsmenu input { margin: 0; cursor: pointer; accent-color: var(--focus); }
.streamsmenuname { flex: 1 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.streamsmenucount,
.streamslanecount { color: var(--sub); font-size: 12px; font-variant-numeric: tabular-nums; }
.streamsmenuhint { margin: 0.25rem 0 0; padding: 0.5rem 0.5rem 0.25rem; border-top: 1px solid var(--line); color: var(--sub); font-size: 12px; }

/* Diagnosis -------------------------------------------------------------- */
.streamsdiag { display: flex; flex: 0 0 auto; flex-direction: column; max-height: 40vh; overflow-y: auto; border-bottom: 1px solid var(--line); background: var(--bg2); }
.streamsdiagrow { padding: 0.375rem var(--page-x); }
.streamsdiagrow + .streamsdiagrow { border-top: 1px solid var(--line); }
.streamsdiagline { display: flex; gap: 0.5rem; align-items: baseline; font-size: 13px; line-height: 1.25rem; }
.streamsdiagkind { flex: none; padding: 0 0.375rem; border-radius: 999px; background: var(--danger-surface); color: var(--danger-text); font-size: 11px; font-weight: 500; line-height: 1rem; letter-spacing: 0.02em; text-transform: uppercase; }
.streamsdiagtext { flex: 1 1 auto; min-width: 0; overflow-wrap: anywhere; }
.streamsdiagtext > :not(:last-child) { margin-right: 0.25rem; }
.streamsdiagsummary { color: var(--txt); font-weight: 500; }
.streamsdiagid { white-space: nowrap; }
.streamsdiagvia { color: var(--sub); white-space: nowrap; }
.streamsdiagnote { color: var(--sub); }
.streamsdiagnote::before { margin-right: 0.5rem; content: "·"; }
.streamsdiagstep,
.streamsdiagtoggle { padding: 0; border: 0; background: none; color: var(--sub); font: inherit; cursor: pointer; }
.streamsdiagstep { text-align: left; text-decoration: underline; text-decoration-color: var(--line2); text-underline-offset: 0.15em; }
.streamsdiagstep:hover,
.streamsdiagtoggle:hover { color: var(--txt); text-decoration-color: currentColor; }
.streamsdiagtoggle { flex: none; margin-left: auto; font-size: 12px; }
.streamsdiagtoggle::before { display: inline-block; margin-right: 0.25rem; content: "▸"; }
.streamsdiagtoggle[aria-expanded="true"]::before { transform: rotate(90deg); }
.streamsdiagmore { margin: 0; padding: 0 var(--page-x) 0.375rem; color: var(--sub); font-size: 12px; }
.streamsdiagpre { max-height: 12rem; margin: 0.375rem 0 0; padding: 0.5rem; overflow: auto; border: 0; border-radius: var(--r-sm); background: var(--bg3); color: var(--txt); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px; line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere; }

/* Grid ------------------------------------------------------------------- */
.streamsscroll { position: relative; flex: 1 1 auto; min-height: 0; overflow: auto; overscroll-behavior: contain; }
.streamsgrid { position: relative; display: grid; width: max-content; min-width: 100%; min-height: 100%; }
.streamsband { display: contents; }

.streamshead {
  position: sticky; z-index: 3; top: 0; display: flex; gap: 0.5rem; align-items: center;
  height: 2.5rem; padding: 0 0.75rem; border-bottom: 1px solid var(--line);
  background: var(--bg2); color: var(--sub); font-size: 11px; font-weight: 500; letter-spacing: 0.045em; text-transform: uppercase;
}
.streamsstepcol,
.streamsshotcol { position: sticky; z-index: 2; background-color: var(--bg2); }
.streamsstepcol { left: 0; }
.streamsshotcol { left: var(--streams-step-w); border-right: 1px solid var(--line); }
.streamshead.streamsstepcol,
.streamshead.streamsshotcol { z-index: 4; }

.streamslanehead { position: sticky; color: var(--txt); font-size: 13px; letter-spacing: 0; text-transform: none; cursor: grab; user-select: none; }
.streamslanehead:focus-visible { outline: 2px solid var(--focus); outline-offset: -2px; }
.streamslanehead[data-streams-dragging] { background: var(--button-hover); cursor: grabbing; }
.streamsroot[data-streams-resizing],
.streamsroot[data-streams-resizing] * { cursor: col-resize; user-select: none; }

.streamsresize { position: absolute; z-index: 1; top: 0; right: -1px; bottom: 0; width: 0.4375rem; cursor: col-resize; touch-action: none; }
.streamsresize::after {
  content: ""; position: absolute; top: calc(50% - 0.375rem); right: 0.125rem; width: 3px; height: 0.75rem;
  border-radius: 999px; background: var(--sub); opacity: 0.55;
  transition: opacity 120ms ease, background-color 120ms ease, top 120ms ease, height 120ms ease;
}
.streamsresize:hover::after,
.streamsresize:focus-visible::after,
.streamsresize[data-streams-active]::after { top: 0.5rem; height: calc(100% - 1rem); background: var(--focus); opacity: 1; }
.streamsresize:focus-visible { outline: none; }

.streamsgrip { flex: 0 0 auto; width: 0.875rem; height: 0.875rem; margin-left: -0.25rem; fill: var(--sub); opacity: 0.55; }
.streamslanehead .streamsgrip { width: 1.25rem; height: 1.25rem; margin-left: -0.375rem; }
.streamslanehead:hover .streamsgrip,
.streamslanehead:hover .streamsresize::after { opacity: 1; }
.streamslanename { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.streamslanehide {
  display: inline-flex; align-items: center; justify-content: center; width: 1.375rem; height: 1.375rem;
  margin-left: auto; padding: 0; border: 0; border-radius: var(--r-sm); background: none; color: var(--sub); opacity: 0; cursor: pointer;
}
.streamslanehead:hover .streamslanehide,
.streamslanehide:focus-visible { opacity: 1; }
.streamslanehide:hover { background: var(--button-hover); color: var(--txt); }
.streamsaddcol { border-left: 1px solid transparent; }
.streamsadd {
  display: inline-flex; margin-left: auto; align-items: center; justify-content: center; width: 1.75rem; height: 1.75rem;
  padding: 0; border: 1px dashed var(--line2); border-radius: var(--r-sm); background: none; color: var(--sub); cursor: pointer;
}
.streamsadd:hover { border-style: solid; background: var(--button-hover); color: var(--txt); }

/* Bands ------------------------------------------------------------------ */
.streamsband > * { border-bottom: 1px solid var(--line); }
.streamsband[data-streams-fill] > * { border-bottom: 0; }
.streamsband[data-streams-kind="heal"] > *,
.streamspreviewband[data-streams-kind="heal"] {
  background-image: repeating-linear-gradient(-45deg, color-mix(in oklab, var(--amber) 14%, transparent) 0 1px, transparent 1px 9px);
}
.streamsband[data-streams-status="failed"] > *,
.streamspreviewband[data-streams-status="failed"] { background-color: color-mix(in oklab, var(--fail) 8%, var(--bg2)); }

/* Clip, not hidden: a scroll container here would pin the label to the cell instead of the board. */
.streamsstep { overflow: clip; }
.streamsstepbody { position: sticky; top: 2.5rem; display: flex; flex-direction: column; gap: 0.25rem; padding: 0.5rem 0.75rem 0.625rem 0.5rem; }
.streamssteptoggle { display: flex; gap: 0.375rem; align-items: center; width: 100%; padding: 0.125rem 0.25rem; border: 0; border-radius: var(--r-sm); background: none; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.streamssteptoggle:hover { background: var(--button-hover); }
.streamssteptoggle svg { flex: 0 0 auto; color: var(--sub); transform: rotate(90deg); transition: transform 120ms ease; }
.streamssteptoggle[aria-expanded="false"] svg { transform: none; }
.streamsstepchip { color: var(--txt); font-size: 11px; font-weight: 500; letter-spacing: 0.045em; text-transform: uppercase; }
.streamsband[data-streams-kind="trailhead"] .streamsstepchip { color: var(--sub); }
.streamsband[data-streams-kind="heal"] .streamsstepchip { color: var(--warning-text); }
.streamsband[data-streams-status="failed"] .streamsstepchip { color: var(--danger-text); }
.streamssteptime { margin-left: auto; color: var(--sub); font-size: 11px; font-variant-numeric: tabular-nums; }
.streamssteptitle { padding-left: 1.5rem; color: var(--txt); font-size: 13px; line-height: 1.3; overflow-wrap: anywhere; }
.streamsstepmeta { display: flex; flex-wrap: wrap; gap: 0.375rem; align-items: center; padding-left: 1.5rem; color: var(--sub); font-size: 12px; font-variant-numeric: tabular-nums; }
.streamsstepflag { padding: 0 0.375rem; border-radius: 999px; background: var(--warning-surface); color: var(--warning-text); font-size: 11px; font-weight: 500; }
.streamsstepflag[data-streams-tone="error"] { background: var(--danger-surface); color: var(--danger-text); }

.streamslane { position: relative; }
.streamslane::before { content: ""; position: absolute; top: 0; bottom: 0; left: var(--streams-line-x); width: 1px; background: var(--line2); }
.streamslane[data-streams-network] { --streams-line-x: 1.625rem; }
.streamslane[data-streams-network] .streamnote { left: 0.25rem; }
.streamsnotes { position: absolute; inset: 0; }

/* The guide cuts across the lanes under the notes, so it never strikes through a label. */
.streamsguide { position: absolute; z-index: 0; right: 0; left: 0; border-top: 1px dashed color-mix(in oklab, var(--focus) 60%, transparent); pointer-events: none; }
.streamsguideline { grid-column: 3 / -1; align-self: start; height: 0; pointer-events: none; }
.streamsguideline[hidden] { display: none; }

/* Preview column: one screen for the whole run, pinned over the run's line. */
.streamspreview::before { content: ""; position: absolute; top: 0; bottom: 0; left: calc((100% + 1rem - var(--streams-stamp-w)) / 2); width: 1px; background: var(--line2); }
.streamspreviewband { position: absolute; z-index: -1; right: 0; left: 0; pointer-events: none; }
.streamspreviewrule { position: absolute; right: 0; left: 0; height: 1px; background: var(--line); pointer-events: none; }
.streamsscrubdot { position: absolute; left: calc((100% + 1rem - var(--streams-stamp-w)) / 2); width: 0.5rem; height: 0.5rem; border-radius: 999px; background: var(--focus); transform: translate(-50%, -50%); pointer-events: none; }
.streamsscrubdot[hidden],
.streamspreviewtime[hidden],
.streamspreviewframe[hidden] { display: none; }
.streamspreviewframe { position: sticky; top: 2.5rem; display: flex; justify-content: center; padding: 1rem var(--streams-stamp-w) 1rem 1rem; }
.streamspreviewlink {
  position: relative; display: block; padding: 0; overflow: hidden; border: 1px solid var(--line2); border-radius: var(--r-lg);
  background: var(--bg3); cursor: pointer; transition: box-shadow 120ms ease;
}
.streamspreviewlink[data-streams-plain] { cursor: default; }
.streamspreviewlink:hover:not([data-streams-plain]),
.streamspreviewframe[data-streams-locked] .streamspreviewlink { box-shadow: 0 0 0 2px var(--focus); }
.streamspreviewlink img { display: block; width: 100%; object-fit: cover; }
.streamspreviewtime {
  position: absolute; left: calc(100% - var(--streams-stamp-w) + 0.25rem); padding: 0 0.3125rem;
  border: 1px solid color-mix(in oklab, var(--focus) 55%, transparent); border-radius: 999px;
  background: var(--bg2); color: var(--txt); font-size: 11px; line-height: 1.125rem; font-variant-numeric: tabular-nums;
  white-space: nowrap; transform: translateY(-50%); pointer-events: none;
}

/* A hovered strings row's elements on the screen. */
.streamsspotlight { position: absolute; inset: 0; width: 100%; height: 100%; fill: color-mix(in oklab, black 55%, transparent); pointer-events: none; }
.streamsspotlightbox { fill: none; stroke: white; stroke-width: 1.5px; vector-effect: non-scaling-stroke; }

/* Notes ------------------------------------------------------------------ */
.streamnote {
  position: absolute; z-index: 1; left: calc(var(--streams-line-x) - 0.25rem); right: 0.5rem;
  display: flex; gap: 0.375rem; align-items: center; min-width: 0; padding: 0 0.375rem 0 0;
  border: 0; border-radius: 0.25rem; background: none; color: var(--txt);
  font: inherit; font-size: 12px; line-height: 1; text-align: left; white-space: nowrap; cursor: pointer;
}
.streamnote:hover,
.streamnote:focus-visible,
.streamnote[data-streams-selected] { background: var(--button-hover); outline: none; }
.streamnote[data-streams-selected] { box-shadow: inset 0 0 0 1px var(--focus); }
.streamsgrid[data-streams-selecting] .streamnote:not([data-streams-selected], :hover, :focus-visible) { opacity: 0.75; }
/* Find marks every match, and rings the one it is on; mixed over the card so the guide never shows through. */
.streamnote[data-streams-match] { background: color-mix(in oklab, var(--amber) 18%, var(--bg2)); }
.streamnote[data-streams-current] { background: color-mix(in oklab, var(--amber) 34%, var(--bg2)); box-shadow: inset 0 0 0 1px var(--amber); }

.streamnotedot { flex: 0 0 auto; width: 0.5rem; height: 0.5rem; border: 1.5px solid var(--bg2); border-radius: 999px; background: var(--sub); box-shadow: 0 0 0 1px var(--sub); }
.streamnotemarker { display: inline-flex; flex: 0 0 2.75rem; justify-content: center; }
.streamnotemarker .streambadge { background: var(--bg); }
.streamnote[data-streams-tone="error"] .streamnotedot { background: var(--fail); box-shadow: 0 0 0 1px var(--fail); }
.streamnote[data-streams-burst] .streamnotedot { width: 0.625rem; height: 0.625rem; border-radius: 0.1875rem; }
.streamnote[data-streams-tone="error"] .streamnotelabel { color: var(--danger-text); }
.streamnote[data-streams-tone="muted"] .streamnotelabel { color: var(--sub); }
.streamnotelabel { flex: 0 1 auto; min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.streamnotedetail { flex: 1 1 0; min-width: 0; overflow: hidden; color: var(--sub); text-overflow: ellipsis; }

.streambadge { flex: 0 0 auto; padding: 0.125rem 0.3125rem; border: 1px solid var(--line2); border-radius: 0.25rem; color: var(--sub); font-size: 11px; font-variant-numeric: tabular-nums; line-height: 1; }
.streambadgetrail { margin-left: auto; padding-inline: 0; border-color: transparent; }
.streambadgetrail + .streambadgetrail { margin-left: 0; }
.streambadgetrail[data-streams-strong] { color: var(--txt); }
.streamslane[data-streams-network] .streambadgetrail { min-inline-size: 2.75rem; text-align: end; }
.streambadge[data-streams-tone="ok"] { border-color: var(--success-border); color: var(--success-text); }
.streambadge[data-streams-tone="error"] { border-color: var(--danger-border); color: var(--danger-text); }

/* A reading's bar runs behind its row on one scale for the lane, over a faint track marking the ceiling. */
.streamnote:has(> .streamnotemeter) { isolation: isolate; padding-inline-end: 0; }
.streamnote:has(> .streamnotemeter)::before { content: ""; position: absolute; z-index: -1; inset-block: 0.1875rem; inset-inline: 0; border-radius: 0.1875rem; background: color-mix(in oklab, var(--sub) 10%, transparent); }
.streamnote:has(> .streamnotemeter) .streamnotelabel { flex-shrink: 0; font-variant-numeric: tabular-nums; text-align: end; }
.streamnotemeter { position: absolute; z-index: -1; inset-block: 0.1875rem; left: 0; max-inline-size: 100%; border-radius: 0.1875rem; background: color-mix(in oklab, var(--accent-9) 24%, transparent); }
.streamnote[data-streams-tone="error"] .streamnotemeter { background: color-mix(in oklab, var(--fail) 24%, transparent); }

.streamscollapsedcount {
  position: absolute; top: 50%; left: calc(var(--streams-line-x) - 0.25rem); padding: 0.125rem 0.4375rem;
  border: 1px solid var(--line2); border-radius: 999px; background: var(--bg2); color: var(--sub);
  font: inherit; font-size: 11px; font-variant-numeric: tabular-nums; transform: translateY(-50%); cursor: pointer;
}
.streamscollapsedcount:hover { color: var(--txt); }
.streamscollapsedcount[data-streams-match] { background: color-mix(in oklab, var(--amber) 18%, var(--bg2)); }

/* Hover card ------------------------------------------------------------- */
.streamcard {
  position: absolute; z-index: 1; display: flex; width: 22rem; max-width: calc(100vw - 1rem); flex-direction: column; gap: 0.5rem;
  padding: 0.75rem; overflow-y: auto; overscroll-behavior: contain;
  border: 1px solid var(--line2); border-radius: var(--r-lg); background: var(--raised); color: var(--txt);
  box-shadow: 0 12px 32px -12px var(--streams-shadow); font-size: 13px;
}
.streamcard[data-streams-expanded] { width: 30rem; }
.streamcardmeta { display: flex; gap: 0.5rem; align-items: baseline; color: var(--sub); font-size: 11px; font-variant-numeric: tabular-nums; }
.streamcardmeta > span:first-child { color: var(--txt); font-weight: 500; }
.streamcardmeta .streamcardlink:first-of-type { margin-left: auto; }
.streamcardlink { padding: 0; border: 0; background: none; color: var(--sub); font: inherit; text-decoration: underline dotted; text-underline-offset: 2px; cursor: pointer; }
.streamcardlink:hover { color: var(--txt); }
.streamcardtitle { font-weight: 500; line-height: 1.35; overflow-wrap: anywhere; }
.streamcardtitle[data-streams-tone="error"] { color: var(--danger-text); }
.streamcarddetail { color: var(--sub); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 12px; overflow-wrap: anywhere; }
.streamcardbadges { display: flex; flex-wrap: wrap; gap: 0.25rem; }
.streamcardfields { display: grid; gap: 0.25rem; margin: 0; }
.streamcardfields > div { display: grid; grid-template-columns: 6rem minmax(0, 1fr); gap: 0.5rem; }
.streamcardfields dt { color: var(--sub); font-size: 12px; overflow-wrap: anywhere; }
.streamcardfields dd { margin: 0; font-size: 12px; overflow-wrap: anywhere; }
.streamcardfields a { color: var(--run); }
.streamcardmembers { max-height: 14rem; margin: 0; padding: 0.375rem 0.5rem 0.375rem 1.75rem; overflow-y: auto; border-radius: var(--r-sm); background: var(--bg3); font-size: 12px; line-height: 1.5; }
.streamcardmore { color: var(--sub); list-style: none; }
.streamcardbody {
  max-height: 12rem; margin: 0; padding: 0.5rem; overflow: auto; border: 0; border-radius: var(--r-sm); background: var(--bg3); color: var(--txt);
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px; line-height: 1.5; white-space: pre-wrap; overflow-wrap: anywhere;
}
.streamcard[data-streams-expanded] :is(.streamcardbody, .streamcardmembers) { max-height: none; }
.streamstok-key { color: var(--accent-11); }
.streamstok-str { color: var(--forest-11); }
.streamstok-num { color: var(--info-11); }
.streamstok-lit { color: var(--violet-11); }
.streamcardentries { display: flex; flex-direction: column; gap: 0.5rem; }
.streamcardsearch { position: sticky; top: -0.75rem; z-index: 1; display: flex; gap: 0.5rem; align-items: center; margin: -0.25rem -0.75rem 0; padding: 0.25rem 0.75rem 0.375rem; background: var(--raised); }
.streamcardsearch input { flex: 1 1 auto; min-width: 0; padding: 0.3125rem 0.5rem; border: 1px solid var(--line2); border-radius: var(--r-sm); background: var(--bg); color: var(--txt); font: inherit; font-size: 12px; }
.streamcardsearch span { flex: 0 0 auto; color: var(--sub); font-size: 11px; font-variant-numeric: tabular-nums; }
.streamcardtable { width: 100%; table-layout: fixed; border-collapse: collapse; font-size: 12px; line-height: 1.45; }
.streamcardtable :is(th, td) { padding: 0.1875rem 0.5rem; text-align: left; vertical-align: baseline; overflow-wrap: anywhere; }
.streamcardkey { width: 60%; }
.streamcardtable :is(th, td):first-child { padding-left: 0.25rem; }
.streamcardtable tbody tr:not(.streamcardgroup) > * { border-top: 1px solid var(--line); }
.streamcardtable th[scope="row"] { font-weight: inherit; }
.streamcardtable td { color: var(--sub); font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace; font-size: 11px; }
.streamcardtable td .streambadge { margin-left: 0.375rem; }
.streamcardtable thead th,
.streamcardgroup th { color: var(--sub); font-size: 11px; font-weight: 500; letter-spacing: 0.045em; text-transform: uppercase; }
.streamcardtable thead th { padding-top: 0; }
.streamcardgroup th { padding-top: 0.875rem; color: var(--txt); }
.streamcardtable tr[data-streams-boxed]:hover > * { background: var(--button-hover); }
.streamcardnote { margin: 0; color: var(--sub); font-size: 12px; }

/* The locked card's screen at reading size, standing in for the thumbnail. */
.streamcardscreen { position: absolute; z-index: 2; overflow: hidden; border: 1px solid var(--line2); border-radius: var(--r-lg); background: var(--bg3); box-shadow: 0 12px 32px -12px var(--streams-shadow); }
.streamcardscreen img { display: block; width: 100%; height: 100%; object-fit: cover; }
.streamsscroll:has(> .streamcardscreen[data-streams-covers]) .streamspreview :is(.streamspreviewframe, .streamspreviewframe *, .streamspreviewtime) { visibility: hidden; }

/* A phone-width view would be all pinned columns and no lanes: Step narrows and Screen scrolls away. */
@media (max-width: 40rem) {
  .streamsroot { --streams-step-w: 8rem; }
  .streamsshotcol { position: relative; left: auto; }
  .streamshead.streamsshotcol { position: sticky; left: auto; }
}

@media (prefers-reduced-motion: reduce) {
  .streamssteptoggle svg,
  .streamsresize::after,
  .streamspreviewlink { transition: none; }
}
.streamsroot[data-streams-reduced] .streamssteptoggle svg,
.streamsroot[data-streams-reduced] .streamsresize::after,
.streamsroot[data-streams-reduced] .streamspreviewlink { transition: none; }
`;
