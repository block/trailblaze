// The Summary tab: a run's steps, screens and event streams as parallel lanes on one clock. Each
// step is a band; each lane holds the notes whose moment falls inside it, so a vertical scrub line
// reads every lane at once and the screen beside it follows the line.
//
// The model (run-report-summary-model.ts) owns every decision about WHAT is on the board and WHERE;
// this file draws it and wires the pointer, keyboard and lane arrangement. It is mounted into an
// empty container after each viewer render and disposed before the next, so everything it adds to
// the page (document and window listeners, timers, frames, observers) is released in dispose(), and
// whatever a reader would miss after a remount lives in host.state.
import type { ReportTabHost, ReportTabMount } from './run-report-tab-host';
import { theaterAvailable } from './run-report-theater';
import {
  DEFAULT_VISIBLE,
  arrangeLanes,
  bandAt,
  bandClock,
  bandY,
  buildSummaryModel,
  cardBeside,
  clockTime,
  defaultLaneIds,
  duration,
  emptyPreference,
  isLaneVisible,
  isNetworkLane,
  layoutBand,
  matchEntries,
  momentsOf,
  noteKey,
  noteWindow,
  offset,
  previewTop,
  screenAt,
  screenBeside,
  searchNotes,
  stepMoment,
  type BandLayout,
  type DiagnosisProblem,
  type DiagnosisStep,
  type LaneLayoutPreference,
  type NoteEntry,
  type NoteMatch,
  type ScreenBox,
  type StreamLane,
  type StreamNote,
  type StreamStep,
  type StreamsModel,
} from './run-report-summary-model';

/** The Summary lays out any run with a trace. */
export function summaryAvailable(session: SessionPayload): boolean {
  return (session.trace || []).length > 0;
}

const NOTE_HEIGHT = 20;
const BAND_PADDING = 10;
const STEP_MIN_HEIGHT = 92;
const METRICS = { noteHeight: NOTE_HEIGHT, padding: BAND_PADDING };
/** Lane cells with fewer notes render whole; denser ones render only the notes near the viewport. */
const WINDOW_MIN_NOTES = 150;
/** The rendered window moves in blocks, and reaches a block past each edge. */
const WINDOW_BLOCK = 800;
const COLLAPSED_HEIGHT = 40;
const LANE_WIDTH_REM = 15;
const NETWORK_LANE_WIDTH_REM = 24;
const MIN_LANE_WIDTH = 160;
const MAX_LANE_WIDTH = 1200;
const RESIZE_STEP = 16;
const MAX_MEMBERS_SHOWN = 400;
/** A hover card previews a long list; locking it opens the whole list to search. */
const ENTRIES_PREVIEWED = 12;
const PREVIEW_CHROME = 72;
const STORAGE_KEY = 'trailblaze-report-summary-lanes';
const TRACE_ID = /([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})/i;

const ESCAPES: Record<string, string> = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };
const signatures = new WeakMap<StreamNote, string>();
/** What a note says, as a short hash: two requests to one endpoint in the same instant share a time
 * and a label, but their status, timing and body tell them apart. Notes that hash alike say the same. */
export function noteSignature(note: StreamNote): string {
  let sig = signatures.get(note);
  if (sig !== undefined) return sig;
  const text = JSON.stringify([note.detail ?? '', note.body ?? '', note.fields, note.badges, note.entries?.length ?? 0, note.members ?? []]);
  let hash = 0x811c9dc5;
  for (let i = 0; i < text.length; i++) hash = Math.imul(hash ^ text.charCodeAt(i), 0x01000193);
  sig = (hash >>> 0).toString(36);
  signatures.set(note, sig);
  return sig;
}

/** Where a saved pin's note is now: the note with its time, label and content, nearest its old
 * place. Saved without an identity, the old indexes stand. */
export function resolvePin(lane: StreamLane, saved: SavedPin): { b: number; n: number } | undefined {
  if (saved.t === undefined) return lane.bands[saved.b]?.[saved.n] ? { b: saved.b, n: saved.n } : undefined;
  // The same content first; a note that changed since (its response landed) is found by time and label.
  let best: { b: number; n: number } | undefined;
  let bestSame = false;
  lane.bands.forEach((notes, b) => notes.forEach((note, n) => {
    if (note.t !== saved.t || note.label !== saved.label) return;
    const same = saved.sig !== undefined && noteSignature(note) === saved.sig;
    const nearer = !best || Math.abs(b - saved.b) < Math.abs(best.b - saved.b)
      || (b === best.b && Math.abs(n - saved.n) < Math.abs(best.n - saved.n));
    if ((same && !bestSame) || (same === bestSame && nearer)) { best = { b, n }; bestSame = same; }
  }));
  return best;
}

/** Whether a modal dialog (the LLM transcript, the inspector, a zoomed screenshot) is open over the
 * report; the viewer's own shortcuts stand down while one is, and so do this tab's. */
const modalOpen = (): boolean => typeof document !== 'undefined' && !!document.querySelector('[aria-modal="true"]');

/** A pinned note as it is saved. The indexes alone can name another note once a late payload reorders
 * a lane, so the note's own time and label ride along to find it again. */
export interface SavedPin { b: number; laneId: string; n: number; t?: number; label?: string; sig?: string; entryQuery?: string }

const esc = (value: unknown): string => String(value ?? '').replace(/[&<>"']/g, (char) => ESCAPES[char]);
const count = (value: number): string => value.toLocaleString('en-US');

// ─── Code blocks ──────────────────────────────────────────────────────────────────────────────

const JSON_TOKEN = /("(?:\\.|[^"\\])*")(\s*:)?|\b(?:true|false|null)\b|-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?/g;

function highlightJson(text: string): string {
  let out = '';
  let last = 0;
  for (const match of text.matchAll(JSON_TOKEN)) {
    const at = match.index ?? 0;
    out += esc(text.slice(last, at));
    const token = match[0];
    const cls = match[1] ? (match[2] ? 'key' : 'str') : /^-?\d/.test(token) ? 'num' : 'lit';
    out += match[2]
      ? `<span class="streamstok-key">${esc(match[1])}</span>${esc(match[2])}`
      : `<span class="streamstok-${cls}">${esc(token)}</span>`;
    last = at + token.length;
  }
  return out + esc(text.slice(last));
}

function highlightYamlValue(value: string): string {
  const quoted = /^(\s*)("(?:\\.|[^"\\])*"|'[^']*')(.*)$/.exec(value);
  if (quoted) return `${esc(quoted[1])}<span class="streamstok-str">${esc(quoted[2])}</span>${esc(quoted[3])}`;
  const plain = /^(\s*)(.*?)(\s*)$/.exec(value) ?? [];
  const body = plain[2] ?? '';
  const cls = /^-?\d+(\.\d+)?$/.test(body) ? 'num' : /^(true|false|null|~)$/.test(body) ? 'lit' : '';
  return cls ? `${esc(plain[1])}<span class="streamstok-${cls}">${esc(body)}</span>${esc(plain[3])}` : esc(value);
}

function highlightYaml(text: string): string {
  return text.split('\n').map((line) => {
    const key = /^(\s*(?:- )*)([^\s:#"'][^:]*?)(:)(\s.*|)$/.exec(line);
    return key ? `${esc(key[1])}<span class="streamstok-key">${esc(key[2])}</span>:${highlightYamlValue(key[4])}` : highlightYamlValue(line);
  }).join('\n');
}

/** An unset language is JSON when the text reads like it and plain otherwise; YAML is never guessed. */
function codeHtml(text: string, lang?: 'json' | 'yaml'): string {
  const syntax = lang ?? (/^\s*[{[]/.test(text) ? 'json' : undefined);
  return syntax === 'json' ? highlightJson(text) : syntax === 'yaml' ? highlightYaml(text) : esc(text);
}

// ─── Persisted preference ─────────────────────────────────────────────────────────────────────

function readPreference(): LaneLayoutPreference {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null');
    if (saved && Array.isArray(saved.order) && saved.visible && typeof saved.visible === 'object') {
      return {
        order: saved.order.filter((id: unknown): id is string => typeof id === 'string'),
        visible: Object.fromEntries(Object.entries(saved.visible).filter((entry): entry is [string, boolean] => typeof entry[1] === 'boolean')),
        widths: Object.fromEntries(
          Object.entries(saved.widths && typeof saved.widths === 'object' ? saved.widths : {}).filter(
            (entry): entry is [string, number] => typeof entry[1] === 'number' && Number.isFinite(entry[1]),
          ),
        ),
      };
    }
  } catch {
    // Storage blocked or corrupt: the defaults stand.
  }
  return emptyPreference();
}

function writePreference(preference: LaneLayoutPreference): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(preference));
  } catch {
    // Storage blocked: the arrangement lasts for this page only.
  }
}

// ─── Pure markup ──────────────────────────────────────────────────────────────────────────────

const GRIP = '<svg class="streamsgrip" viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="6" r="1.4"/><circle cx="15" cy="6" r="1.4"/><circle cx="9" cy="12" r="1.4"/><circle cx="15" cy="12" r="1.4"/><circle cx="9" cy="18" r="1.4"/><circle cx="15" cy="18" r="1.4"/></svg>';
const PLUS = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 5v14M5 12h14"/></svg>';
const CLOSE = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7l10 10M17 7L7 17"/></svg>';
const CHEVRON = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M9 6l6 6-6 6"/></svg>';

function stepChip(step: StreamStep): string {
  if (step.kind === 'trailhead') return 'Trailhead';
  if (step.kind === 'heal') return 'Self-heal';
  if (step.kind === 'teardown') return 'Teardown';
  return `Step ${step.number}`;
}

function diagnosisStepLabel(step: DiagnosisStep): string {
  if (step.kind === 'trailhead') return 'Trailhead';
  if (step.kind === 'teardown') return 'Teardown';
  if (step.kind === 'end') return 'After the last step';
  return `Step ${step.number} · ${step.title}`;
}

function diagnosisRowHtml(problem: DiagnosisProblem, label: string, index: number, open: boolean): string {
  const summary = problem.summary.split(TRACE_ID).map((part, i) => (i % 2 ? `<span class="streamsdiagid">${esc(part)}</span>` : esc(part))).join('');
  const { step, detail } = problem;
  return `<div class="streamsdiagrow">
    <div class="streamsdiagline">
      <span class="streamsdiagkind">${label}</span>
      <span class="streamsdiagtext"><span class="streamsdiagsummary">${summary}</span>${problem.via ? ` <span class="streamsdiagvia">via ${esc(problem.via)}</span>` : ''}${problem.note ? ` <span class="streamsdiagnote">${esc(problem.note)}</span>` : ''}${step ? ` <button type="button" class="streamsdiagstep" data-streams-jump="${step.bandIndex}">${esc(diagnosisStepLabel(step))}</button>` : ''}</span>
      ${detail ? `<button type="button" class="streamsdiagtoggle" data-streams-detail="${index}" aria-expanded="${open}">Details</button>` : ''}
    </div>
    ${open && detail ? `<pre class="streamsdiagpre">${esc(detail)}</pre>` : ''}
  </div>`;
}

function spotlightHtml(boxes: ScreenBox[], id: string): string {
  return `<svg class="streamsspotlight" viewBox="0 0 1 1" preserveAspectRatio="none" aria-hidden="true">
    <mask id="${id}"><rect width="1" height="1" fill="white"/>${boxes.map((b) => `<rect x="${b.left}" y="${b.top}" width="${b.width}" height="${b.height}" fill="black"/>`).join('')}</mask>
    <rect width="1" height="1" mask="url(#${id})"/>
    ${boxes.map((b) => `<rect class="streamsspotlightbox" x="${b.left}" y="${b.top}" width="${b.width}" height="${b.height}"/>`).join('')}
  </svg>`;
}

interface NoteFlags {
  selected: boolean;
  match: boolean;
  current: boolean;
}

function noteHtml(note: StreamNote, lane: StreamLane, b: number, l: number, n: number, top: number, flags: NoteFlags): string {
  const network = isNetworkLane(lane.id);
  const marker = network ? note.badges.find((badge) => badge.tone) : undefined;
  const meter = note.meter === undefined ? undefined : `${Math.min(1, Math.max(0, note.meter)) * 100}%`;
  const attrs = [
    note.tone ? `data-streams-tone="${note.tone}"` : '',
    note.members ? 'data-streams-burst' : '',
    flags.selected ? 'data-streams-selected' : '',
    flags.match ? 'data-streams-match' : '',
    flags.current ? 'data-streams-current' : '',
  ].filter(Boolean).join(' ');
  const dot = network
    ? `<span class="streamnotemarker">${marker ? `<span class="streambadge" data-streams-tone="${marker.tone}">${esc(marker.text)}</span>` : '<span class="streamnotedot" aria-hidden="true"></span>'}</span>`
    : '<span class="streamnotedot" aria-hidden="true"></span>';
  const toned = note.badges.filter((badge) => badge.tone && badge !== marker)
    .map((badge) => `<span class="streambadge" data-streams-tone="${badge.tone}">${esc(badge.text)}</span>`).join('');
  const trailing = note.badges.filter((badge) => !badge.tone)
    .map((badge) => `<span class="streambadge streambadgetrail"${badge.strong ? ' data-streams-strong' : ''}>${esc(badge.text)}</span>`).join('');
  return `<button type="button" class="streamnote" ${attrs} data-streams-note data-streams-b="${b}" data-streams-l="${l}" data-streams-n="${n}" data-focus-key="${esc(`${lane.id}:${note.t}:${noteSignature(note)}`)}" aria-pressed="${flags.selected}" style="top:${top}px;height:${NOTE_HEIGHT}px">`
    + (meter === undefined ? '' : `<span class="streamnotemeter" style="inline-size:${meter}" aria-hidden="true"></span>`)
    + dot + toned
    + `<span class="streamnotelabel"${meter === undefined ? '' : ` style="min-inline-size:calc(${meter} - 1.25rem)"`}>${esc(note.label)}</span>`
    + (note.detail ? `<span class="streamnotedetail">${esc(note.detail)}</span>` : '')
    + trailing
    + '</button>';
}

function badgeHtml(text: string, tone?: string): string {
  return `<span class="streambadge"${tone ? ` data-streams-tone="${tone}"` : ''}>${esc(text)}</span>`;
}

// ─── The tab ──────────────────────────────────────────────────────────────────────────────────

interface Hover {
  key: string;
  b: number;
  l: number;
  n: number;
  laneId: string;
  /** The note's centre, in px from the top of its band. */
  y: number;
  /** The note's box in the scroller's content coordinates, so a locked card scrolls with its note. */
  anchor: { top: number; left: number; right: number };
  /** The find query, when a find landed on this note, so its list opens on the rows that matched. */
  query?: string;
  byFind?: boolean;
}

interface Guide {
  band: number;
  y: number;
}

/** The pinned moment: a navigation's always wins; otherwise the card the reader pinned, then the route's. */
export function pinnedAt(seek: boolean | undefined, routeT: number | null | undefined, saved: unknown): number | null {
  if (seek && routeT != null) return routeT;
  return typeof saved === 'number' ? saved : routeT ?? null;
}

export function mountSummary(container: HTMLElement, host: ReportTabHost): ReportTabMount {
  const state = host.state;
  const hasShotFor = (file: string): boolean => host.shotSrc(file) !== null;
  const model: StreamsModel = buildSummaryModel({ session: host.session, events: host.events, hasShot: hasShotFor });
  const theaterOk = theaterAvailable(host.session);

  const root = document.createElement('div');
  root.className = 'streamsroot';
  if (host.reducedMotion) root.setAttribute('data-streams-reduced', '');
  container.appendChild(root);

  const failedStatus = String(host.session.meta?.status ?? '');
  const diagLabel = /error/i.test(failedStatus) ? 'Error' : 'Failed';

  if (model.steps.length === 0) {
    root.innerHTML = `${model.diagnosis ? `<section class="streamsdiag" aria-label="Run diagnosis">${model.diagnosis.problems.map((p, i) => diagnosisRowHtml(p, diagLabel, i, false)).join('')}</section>` : ''}<div class="streamsempty">This run's archive has no trace steps or timestamped events.</div>`;
    return { dispose() { root.remove(); } };
  }

  // ── Persistent state ────────────────────────────────────────────────────────────────────────
  const steps = model.steps;
  const hasScreens = model.shots.length > 0;
  const hasDefaultLane = model.lanes.some((lane) => (DEFAULT_VISIBLE as readonly string[]).includes(isNetworkLane(lane.id) ? 'network' : lane.id));
  const defaults: readonly string[] = hasDefaultLane ? DEFAULT_VISIBLE : defaultLaneIds(model.lanes);
  let pref: LaneLayoutPreference = (state.pref as LaneLayoutPreference | undefined) ?? readPreference();
  const collapsed = new Set<number>(
    Array.isArray(state.collapsed)
      ? (state.collapsed as number[])
      : steps.length > 1 ? steps.flatMap((step, index) => (step.kind === 'trailhead' ? [index] : [])) : [],
  );
  let pinnedT = pinnedAt(host.seek, host.initialEpochMs, state.pinnedT);
  let query = typeof state.query === 'string' ? state.query : '';
  let findIndex = 0;
  const remPx = parseFloat(getComputedStyle(document.documentElement).fontSize) || 16;
  const diagOpen = new Set<number>();

  // ── Derived layout ──────────────────────────────────────────────────────────────────────────
  let arranged: StreamLane[] = [];
  let visible: StreamLane[] = [];
  /** The lanes as the current layout saw them: note indexes into `layouts` follow this order. */
  let layoutLanes: StreamLane[] = [];
  let layouts: BandLayout[] = [];
  let bandTops: number[] = [];
  let stepHeights: number[] = [];
  let clocks: Array<((y: number) => number) | undefined> = [];
  let ys: Array<((t: number) => number) | undefined> = [];
  let moments: number[] = [];
  const shotTimes = new Map(model.shots.map((shot) => [shot.file, shot.t]));

  const widthOf = (id: string): number => resizing?.id === id ? resizing.width : pref.widths[id] ?? remPx * (isNetworkLane(id) ? NETWORK_LANE_WIDTH_REM : LANE_WIDTH_REM);
  let resizing: { id: string; width: number } | null = null;

  const shown = (id: string): boolean => isLaneVisible(id, pref, defaults);
  const arrange = (): void => {
    arranged = arrangeLanes(model.lanes, pref, defaults);
    visible = arranged.filter((lane) => shown(lane.id));
  };

  const persist = (): void => {
    state.pref = pref;
    state.collapsed = [...collapsed];
    state.query = query;
    state.pinnedT = pinnedT;
  };
  const setPref = (next: LaneLayoutPreference): void => {
    pref = next;
    state.pref = next;
    writePreference(next);
  };

  // ── Skeleton ────────────────────────────────────────────────────────────────────────────────
  const lanesWord = (): string => host.eventsPending ? 'Loading streams…' : `${count(model.lanes.length)}`;
  root.innerHTML = `
    <div class="streamstoolbar">
      <dl class="streamsfacts">
        <div><dt>Steps</dt><dd>${count(steps.filter((s) => s.kind === 'step').length)}</dd></div>
        <div><dt>Duration</dt><dd>${esc(duration(Math.max(0, steps[steps.length - 1].endMs - model.startMs)))}</dd></div>
        <div><dt>Streams</dt><dd${host.eventsPending ? ' data-streams-busy aria-busy="true"' : ''}>${esc(lanesWord())}</dd></div>
      </dl>
      <div class="streamstoolbarend">
        <div class="streamsfind" role="search">
          <svg class="streamsfindicon" viewBox="0 0 24 24" aria-hidden="true"><circle cx="11" cy="11" r="6.5"/><path d="M20 20l-4.2-4.2"/></svg>
          <input type="text" placeholder="Find in events" aria-label="Find in events" data-streams-find autocomplete="off" spellcheck="false">
          <span class="streamsfindcount" aria-live="polite" hidden></span>
          <button type="button" title="Previous match (Shift+Enter)" aria-label="Previous match" data-streams-find-step="-1"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 15l6-6 6 6"/></svg></button>
          <button type="button" title="Next match (Enter)" aria-label="Next match" data-streams-find-step="1"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 9l6 6 6-6"/></svg></button>
          <button type="button" title="Clear" aria-label="Clear find" data-streams-find-clear><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7 7l10 10M17 7L7 17"/></svg></button>
        </div>
        <button type="button" class="streamsmenubutton" data-streams-menu-toggle="toolbar" title="Choose streams"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h10M4 18h6"/></svg><span class="streamsmenubuttoncount" hidden></span></button>
      </div>
    </div>
    ${model.diagnosis ? '<section class="streamsdiag" aria-label="Run diagnosis"></section>' : ''}
    <div class="streamsscroll"><div class="streamsgrid"></div></div>`;
  const scroll = root.querySelector<HTMLElement>('.streamsscroll')!;
  const grid = root.querySelector<HTMLElement>('.streamsgrid')!;
  const findInput = root.querySelector<HTMLInputElement>('[data-streams-find]')!;
  const findCount = root.querySelector<HTMLElement>('.streamsfindcount')!;
  const menuButton = root.querySelector<HTMLElement>('.streamsmenubutton')!;
  const menuButtonCount = root.querySelector<HTMLElement>('.streamsmenubuttoncount')!;
  const diagEl = root.querySelector<HTMLElement>('.streamsdiag');
  findInput.value = query;

  const renderDiagnosis = (): void => {
    if (!diagEl || !model.diagnosis) return;
    diagEl.innerHTML = model.diagnosis.problems.map((problem, i) => diagnosisRowHtml(problem, diagLabel, i, diagOpen.has(i))).join('')
      + (model.diagnosis.hidden > 0 ? `<p class="streamsdiagmore">+${model.diagnosis.hidden} earlier failed ${model.diagnosis.hidden === 1 ? 'step' : 'steps'}</p>` : '');
  };
  renderDiagnosis();

  // ── Live handles into the grid, refreshed by every render ──────────────────────────────────
  let headEl: HTMLElement | null = null;
  let previewEl: HTMLElement | null = null;
  let frameEl: HTMLElement | null = null;
  let linkEl: HTMLElement | null = null;
  let imgEl: HTMLImageElement | null = null;
  let lineStamp: HTMLElement | null = null;
  let frameStamp: HTMLElement | null = null;
  let previewGuide: HTMLElement | null = null;
  let scrubDot: HTMLElement | null = null;
  let laneGuide: HTMLElement | null = null;

  // ── Interaction state ───────────────────────────────────────────────────────────────────────
  let hover: Hover | null = null;
  let locked: Hover | null = null;
  /** The pinned note being restored on first paint, before it becomes `locked`. */
  let restoring: { b: number; laneId: string; n: number } | null = null;
  let scrub: { band: number; y: number; t: number } | null = null;
  let pendingScrub: PointerEvent | null = null;
  let highlight: ScreenBox[] | null = null;
  let topBand = 0;
  let viewHeight = 0;
  let span: [number, number] = [0, 2 * WINDOW_BLOCK];
  let pointerY: number | null = null;
  let matches: NoteMatch[] = [];
  let matchKeys = new Set<string>();
  let currentKey: string | undefined;
  let hideTimer: number | undefined;
  let frameId = 0;
  let framePending = { scrub: false, preview: false, measure: false };
  let jumpTimer = 0;
  let suppressScrolls = 0;
  let draggingLane: string | null = null;
  let dragDirty = false;
  let lastImgFile: string | undefined;
  /** When the screen the preview shows was taken; what its "open in Theater" falls back to. */
  let shownShotT: number | undefined;
  // A run's devices can have different screens: each image is shown in its own shape — what its
  // capture said, else its pixels once loaded, else the run's first screen.
  const naturalAspect = new Map<string, number>();
  const shotAspect = new Map(model.shots.flatMap((shot) => (shot.aspect ? [[shot.file, shot.aspect] as const] : [])));
  const aspectFor = (file: string | undefined): number =>
    (file !== undefined ? shotAspect.get(file) ?? naturalAspect.get(file) : undefined) ?? model.aspect;
  /** Records an image's own shape; true when that changed how it should be drawn. */
  const learnAspect = (file: string, img: HTMLImageElement): boolean => {
    if (!img.naturalWidth || !img.naturalHeight || shotAspect.has(file) || naturalAspect.has(file)) return false;
    naturalAspect.set(file, img.naturalWidth / img.naturalHeight);
    return true;
  };
  /** The thumbnail's width for the view's height and the shown image's shape. */
  const sizeThumb = (): void => {
    if (linkEl) linkEl.style.width = `min(100%, ${Math.round(Math.max(0, viewHeight - PREVIEW_CHROME) * aspectFor(lastImgFile))}px)`;
  };
  let spotId = 0;
  const cleanups = new Set<() => void>();

  const keyOf = (b: number, l: number, n: number): string => noteKey(b, layoutLanes[l].id, n);
  const noteAt = (b: number, l: number, n: number): StreamNote | undefined => layoutLanes[l]?.bands[b]?.[n];
  const flagsFor = (key: string): NoteFlags => ({ selected: locked?.key === key, match: matchKeys.has(key), current: key === currentKey });
  const headHeight = (): number => headEl?.offsetHeight ?? 40;

  // ── Layout ──────────────────────────────────────────────────────────────────────────────────
  const computeLayout = (): void => {
    arrange();
    layoutLanes = visible.slice();
    layouts = steps.map((step, index) => collapsed.has(index)
      ? { height: COLLAPSED_HEIGHT, offsets: layoutLanes.map(() => []) }
      : layoutBand(step, layoutLanes.map((lane) => lane.bands[index]), Math.max(STEP_MIN_HEIGHT, stepHeights[index] ?? 0), METRICS));
    clocks = [];
    ys = [];
    let top = 0;
    bandTops = layouts.map((layout) => {
      const at = top;
      top += layout.height;
      return at;
    });
    moments = momentsOf(layoutLanes);
  };

  /** A band's pixel-to-moment map; a collapsed band has no notes to follow, so it runs linearly. */
  const clockOf = (band: number): ((y: number) => number) => {
    let clock = clocks[band];
    if (!clock) {
      const step = steps[band];
      const layout = layouts[band];
      clock = collapsed.has(band)
        ? (y: number) => step.startMs + ((step.endMs - step.startMs) * Math.min(1, Math.max(0, y / layout.height)))
        : bandClock(step, layoutLanes.map((lane) => lane.bands[band]), layout, METRICS);
      clocks[band] = clock;
    }
    return clock;
  };
  const yOf = (band: number): ((t: number) => number) => {
    let fn = ys[band];
    if (!fn) {
      const step = steps[band];
      const layout = layouts[band];
      fn = collapsed.has(band)
        ? (t: number) => Math.min(layout.height, Math.max(0, ((t - step.startMs) / Math.max(1, step.endMs - step.startMs)) * layout.height))
        : bandY(step, layoutLanes.map((lane) => lane.bands[band]), layout, METRICS);
      ys[band] = fn;
    }
    return fn;
  };
  const guideForMoment = (t: number): Guide => {
    const band = bandAt(model, t);
    return { band, y: yOf(band)(t) };
  };

  const laneCol0 = hasScreens ? 3 : 2;
  const applyColumns = (): void => {
    grid.style.gridTemplateColumns = `var(--streams-step-w) ${hasScreens ? 'var(--streams-shot-w) ' : ''}${visible.map((lane) => `${widthOf(lane.id)}px `).join('')}minmax(3rem, 1fr)`;
    visible.forEach((lane, index) => {
      for (const el of grid.querySelectorAll<HTMLElement>(`[data-streams-lane="${CSS.escape(lane.id)}"]`)) el.style.gridColumn = String(laneCol0 + index);
    });
    const fillCol = laneCol0 + visible.length;
    for (const el of grid.querySelectorAll<HTMLElement>('[data-streams-fillcol]')) el.style.gridColumn = String(fillCol);
    if (laneGuide) laneGuide.style.gridColumn = `${laneCol0} / -1`;
  };

  const notesInner = (b: number, l: number): string => {
    const lane = layoutLanes[l];
    const notes = lane.bands[b];
    const offsets = layouts[b].offsets[l];
    const dense = notes.length >= WINDOW_MIN_NOTES;
    const [from, to] = dense ? noteWindow(offsets, span[0] - bandTops[b], span[1] - bandTops[b], NOTE_HEIGHT) : [0, notes.length];
    let html = '';
    for (let n = from; n < to; n++) html += noteHtml(notes[n], lane, b, l, n, offsets[n], flagsFor(keyOf(b, l, n)));
    // The pinned note stays rendered when scrolled out of the window, so its card has an anchor.
    const held = locked ?? restoring;
    if (held && held.b === b && held.laneId === lane.id && (held.n < from || held.n >= to) && held.n < notes.length) {
      html += noteHtml(notes[held.n], lane, b, l, held.n, offsets[held.n], flagsFor(keyOf(b, l, held.n)));
    }
    return html;
  };

  const collapsedCountHtml = (b: number, l: number): string => {
    const notes = layoutLanes[l].bands[b];
    if (notes.length === 0) return '';
    const matched = notes.some((_, n) => matchKeys.has(keyOf(b, l, n)));
    const total = notes.reduce((sum, note) => sum + (note.members?.length ?? 1), 0);
    return `<button type="button" class="streamscollapsedcount"${matched ? ' data-streams-match' : ''} data-streams-toggle="${b}">${count(total)}</button>`;
  };

  const buildGrid = (): string => {
    const lastRow = steps.length + 2;
    let html = `<div class="streamshead streamsstepcol" style="grid-area:1/1" data-streams-headcell>Step${hasScreens ? '' : '<button type="button" class="streamsadd" data-streams-menu-toggle="add" title="Choose streams">' + PLUS + '</button>'}</div>`;
    if (hasScreens) html += `<div class="streamshead streamsshotcol" style="grid-area:1/2">Screen<button type="button" class="streamsadd" data-streams-menu-toggle="add" title="Choose streams">${PLUS}</button></div>`;
    visible.forEach((lane, index) => {
      html += `<div class="streamshead streamslanehead" draggable="true" tabindex="0" data-streams-lane="${esc(lane.id)}" style="grid-area:1/${laneCol0 + index}" aria-label="${esc(lane.name)} stream. Alt+Arrow keys move it.">`
        + `${GRIP}<span class="streamslanename">${esc(lane.name)}</span><span class="streamslanecount">${count(lane.total)}</span>`
        + `<button type="button" class="streamslanehide" title="Hide ${esc(lane.name)}" data-streams-hide="${esc(lane.id)}">${CLOSE}</button>`
        + `<span class="streamsresize" role="separator" aria-orientation="vertical" aria-valuenow="${Math.round(widthOf(lane.id))}" aria-valuemin="${MIN_LANE_WIDTH}" aria-valuemax="${MAX_LANE_WIDTH}" aria-label="Resize ${esc(lane.name)}. Arrow keys change its width; double-click resets it." tabindex="0" data-streams-resize="${esc(lane.id)}"></span></div>`;
    });
    html += `<div class="streamshead streamsaddcol" data-streams-fillcol style="grid-area:1/${laneCol0 + visible.length}"></div>`;

    if (hasScreens) {
      const tints = steps.flatMap((step, index) => step.kind === 'heal' || step.status === 'failed'
        ? [`<span class="streamspreviewband" data-streams-kind="${step.kind}" data-streams-status="${step.status}" style="top:${bandTops[index]}px;height:${layouts[index].height}px" aria-hidden="true"></span>`]
        : []);
      const rules = layouts.map((layout, index) => `<span class="streamspreviewrule" style="top:${bandTops[index] + layout.height - 1}px" aria-hidden="true"></span>`);
      html += `<div class="streamsshotcol streamspreview" style="grid-column:2;grid-row:2 / span ${steps.length + 1}">${tints.join('')}${rules.join('')}`
        + '<div class="streamsguide" hidden aria-hidden="true"></div><span class="streamsscrubdot" hidden aria-hidden="true"></span>'
        + '<div class="streamspreviewframe" hidden><button type="button" class="streamspreviewlink" aria-label="Open this moment in Theater"><img alt=""></button>'
        + '<span class="streamspreviewtime" style="top:50%" data-streams-stamp="frame" hidden></span></div>'
        + '<span class="streamspreviewtime" data-streams-stamp="line" hidden></span></div>';
    }

    steps.forEach((step, b) => {
      const layout = layouts[b];
      const isCollapsed = collapsed.has(b);
      const size = `height:${layout.height}px`;
      html += `<div class="streamsband" data-streams-kind="${step.kind}" data-streams-status="${step.status}">`
        + `<div class="streamsstep streamsstepcol" style="grid-area:${b + 2}/1;${size}"><div class="streamsstepbody">`
        + `<button type="button" class="streamssteptoggle" aria-expanded="${!isCollapsed}" data-streams-toggle="${b}">${CHEVRON}<span class="streamsstepchip">${esc(stepChip(step))}</span><span class="streamssteptime">+${offset(step.startMs - model.startMs)}</span></button>`
        + (isCollapsed ? '' : `<div class="streamssteptitle">${esc(step.title)}</div><div class="streamsstepmeta">${esc(duration(step.endMs - step.startMs))}${step.status === 'recovered' ? '<span class="streamsstepflag">Recording failed</span>' : ''}${step.status === 'failed' ? '<span class="streamsstepflag" data-streams-tone="error">Failed</span>' : ''}</div>`)
        + '</div></div>';
      layoutLanes.forEach((lane, l) => {
        const dense = lane.bands[b].length >= WINDOW_MIN_NOTES && !isCollapsed;
        html += `<div class="streamslane" data-streams-lane="${esc(lane.id)}" data-streams-b="${b}" data-streams-l="${l}"${dense ? ' data-streams-dense' : ''}${isNetworkLane(lane.id) ? ' data-streams-network' : ''} style="grid-area:${b + 2}/${laneCol0 + visible.indexOf(lane)};${size}">`
          + `<div class="streamsnotes">${isCollapsed ? collapsedCountHtml(b, l) : notesInner(b, l)}</div></div>`;
      });
      html += `<div class="streamsaddcol streamslanefill" data-streams-fillcol style="grid-area:${b + 2}/${laneCol0 + visible.length};${size}"></div></div>`;
    });

    html += `<div class="streamsband" data-streams-fill aria-hidden="true"><div class="streamsstepcol" style="grid-area:${lastRow}/1"></div>`;
    layoutLanes.forEach((lane) => {
      html += `<div class="streamslane" data-streams-lane="${esc(lane.id)}"${isNetworkLane(lane.id) ? ' data-streams-network' : ''} style="grid-area:${lastRow}/${laneCol0 + visible.indexOf(lane)}"></div>`;
    });
    html += `<div class="streamsaddcol streamslanefill" data-streams-fillcol style="grid-area:${lastRow}/${laneCol0 + visible.length}"></div></div>`;
    html += `<div class="streamsguideline" hidden aria-hidden="true" style="grid-column:${laneCol0} / -1"></div>`;
    return html;
  };

  const measureSteps = (): boolean => {
    const bodies = grid.querySelectorAll<HTMLElement>('.streamsstepbody');
    let changed = false;
    // Each band's cells also carry a 1px bottom border.
    bodies.forEach((body, index) => {
      const height = body.offsetHeight + 1;
      if (stepHeights[index] !== height) {
        stepHeights[index] = height;
        changed = true;
      }
    });
    return changed;
  };

  const refreshHandles = (): void => {
    headEl = grid.querySelector<HTMLElement>('[data-streams-headcell]');
    previewEl = grid.querySelector<HTMLElement>('.streamspreview');
    frameEl = previewEl?.querySelector<HTMLElement>('.streamspreviewframe') ?? null;
    linkEl = frameEl?.querySelector<HTMLElement>('.streamspreviewlink') ?? null;
    imgEl = frameEl?.querySelector<HTMLImageElement>('img') ?? null;
    if (imgEl) {
      const img = imgEl;
      img.onload = () => {
        if (!lastImgFile || !learnAspect(lastImgFile, img)) return;
        img.style.aspectRatio = String(aspectFor(lastImgFile));
        sizeThumb();
      };
    }
    frameStamp = frameEl?.querySelector<HTMLElement>('[data-streams-stamp]') ?? null;
    lineStamp = previewEl?.querySelector<HTMLElement>(':scope > [data-streams-stamp="line"]') ?? null;
    previewGuide = previewEl?.querySelector<HTMLElement>('.streamsguide') ?? null;
    scrubDot = previewEl?.querySelector<HTMLElement>('.streamsscrubdot') ?? null;
    laneGuide = grid.querySelector<HTMLElement>('.streamsguideline');
    lastImgFile = undefined;
  };

  const updateMenuButton = (): void => {
    const hidden = visible.length < arranged.length;
    menuButtonCount.hidden = !hidden;
    menuButtonCount.textContent = `${visible.length}/${arranged.length}`;
    menuButton.setAttribute('aria-label', `Streams, ${visible.length} of ${arranged.length} shown`);
  };

  const renderAll = (pass = 0): void => {
    computeLayout();
    grid.style.gridTemplateRows = `auto repeat(${steps.length}, auto) 1fr`;
    grid.setAttribute('data-streams-selecting', '');
    if (!locked) grid.removeAttribute('data-streams-selecting');
    grid.innerHTML = buildGrid();
    applyColumns();
    refreshHandles();
    // Step titles wrap in full, so each band is at least as tall as its step's text.
    if (pass < 2 && measureSteps()) {
      renderAll(pass + 1);
      return;
    }
    updateMenuButton();
    runFind(false);
    measure();
    paintGuide();
    refreshCard();
  };

  // ── Windowing and measuring ─────────────────────────────────────────────────────────────────
  const renderWindow = (): void => {
    for (const cell of grid.querySelectorAll<HTMLElement>('.streamslane[data-streams-dense]')) {
      const b = Number(cell.dataset.streamsB);
      const l = Number(cell.dataset.streamsL);
      const inner = cell.querySelector<HTMLElement>('.streamsnotes');
      if (inner) inner.innerHTML = notesInner(b, l);
    }
  };

  const measure = (): void => {
    const start = (Math.floor(scroll.scrollTop / WINDOW_BLOCK) - 1) * WINDOW_BLOCK;
    const end = (Math.ceil((scroll.scrollTop + scroll.clientHeight) / WINDOW_BLOCK) + 1) * WINDOW_BLOCK;
    if (span[0] !== start || span[1] !== end) {
      span = [start, end];
      renderWindow();
    }
    viewHeight = scroll.clientHeight;
    let band = 0;
    while (band + 1 < bandTops.length && bandTops[band + 1] <= scroll.scrollTop) band++;
    topBand = band;
    sizeThumb();
  };

  // ── Guide, preview ──────────────────────────────────────────────────────────────────────────
  const shownHover = (): Hover | null => locked ?? hover;
  const currentGuide = (): Guide | null => {
    if (scrub) return { band: scrub.band, y: scrub.y };
    const h = shownHover();
    if (h) return { band: h.b, y: h.y };
    return pinnedT === null ? null : guideForMoment(pinnedT);
  };

  const paintGuide = (): void => {
    const guide = currentGuide();
    if (laneGuide) {
      laneGuide.hidden = !guide;
      if (guide) {
        laneGuide.style.gridRow = String(guide.band + 2);
        laneGuide.style.marginTop = `${guide.y}px`;
      }
    }
    if (previewGuide) {
      previewGuide.hidden = !guide;
      if (guide) previewGuide.style.top = `${bandTops[guide.band] + guide.y}px`;
    }
    if (scrubDot) {
      const marker = scrub ? guide : !shownHover() && pinnedT !== null ? guide : null;
      scrubDot.hidden = !marker;
      if (marker) scrubDot.style.top = `${bandTops[marker.band] + marker.y}px`;
    }
    paintPreview();
  };

  const paintPreview = (): void => {
    if (!imgEl || !frameEl || !linkEl) return;
    const h = shownHover();
    const guide = currentGuide();
    let file: string | undefined;
    let time: number | undefined;
    if (scrub) {
      const shot = screenAt(model, scrub.t);
      file = shot?.file;
      time = shot?.t;
    } else if (h) {
      const note = noteAt(h.b, h.l, h.n);
      if (note?.shot) {
        file = note.shot;
        time = note.shotT ?? shotTimes.get(note.shot);
      } else if (note) {
        const shot = screenAt(model, note.t);
        file = shot?.file;
        time = shot?.t;
      }
    } else if (pinnedT !== null) {
      const shot = screenAt(model, pinnedT);
      file = shot?.file;
      time = shot?.t;
    } else {
      const step = steps[topBand];
      file = step?.shot ?? screenAt(model, step?.startMs ?? 0)?.file;
      time = file ? shotTimes.get(file) : undefined;
    }
    const src = file ? host.shotSrc(file) : null;
    shownShotT = src ? time : undefined;
    frameEl.hidden = !src;
    if (src && file !== lastImgFile) {
      imgEl.src = src;
      lastImgFile = file;
      imgEl.style.aspectRatio = String(aspectFor(file));
      sizeThumb();
    }
    linkEl.toggleAttribute('data-streams-plain', !theaterOk);
    frameEl.toggleAttribute('data-streams-locked', locked !== null);
    const label = src && time !== undefined ? `+${offset(time - model.startMs)}` : '';
    if (frameStamp) {
      frameStamp.hidden = !label || !!guide;
      frameStamp.textContent = label;
    }
    if (lineStamp) {
      lineStamp.hidden = !label || !guide;
      lineStamp.textContent = label;
      if (guide) lineStamp.style.top = `${bandTops[guide.band] + guide.y}px`;
    }
    paintHighlight();
    placePreview();
  };

  const placePreview = (): void => {
    if (!frameEl || frameEl.hidden) return;
    const head = headHeight();
    const y = locked ? head + bandTops[locked.b] + locked.y - scroll.scrollTop : pointerY;
    if (y === null) return;
    frameEl.style.top = `${previewTop(y, frameEl.offsetHeight, head, scroll.clientHeight)}px`;
  };

  const paintHighlight = (): void => {
    const slots: HTMLElement[] = [];
    if (linkEl) slots.push(linkEl);
    const zoom = scroll.querySelector<HTMLElement>('.streamcardscreen');
    if (zoom) slots.push(zoom);
    for (const slot of slots) {
      slot.querySelector('.streamsspotlight')?.remove();
      if (highlight) slot.insertAdjacentHTML('beforeend', spotlightHtml(highlight, `streams-spot-${++spotId}`));
    }
  };

  // ── Frames ──────────────────────────────────────────────────────────────────────────────────
  const scrubFromPointer = (event: { clientY: number }): { band: number; y: number; t: number } | null => {
    const rect = scroll.getBoundingClientRect();
    const columnY = event.clientY - rect.top - scroll.clientTop + scroll.scrollTop - headHeight();
    const last = bandTops.length - 1;
    // Below the last band is the fill that runs a short board to its foot, not time.
    if (last < 0 || columnY < 0 || columnY >= bandTops[last] + layouts[last].height) return null;
    let band = 0;
    while (band + 1 < bandTops.length && bandTops[band + 1] <= columnY) band++;
    const y = Math.min(layouts[band].height, Math.max(0, columnY - bandTops[band]));
    return { band, y, t: clockOf(band)(y) };
  };

  const runFrame = (): void => {
    frameId = 0;
    const flags = framePending;
    framePending = { scrub: false, preview: false, measure: false };
    if (flags.measure) {
      measure();
      paintPreview();
    }
    if (flags.scrub && pendingScrub) {
      const event = pendingScrub;
      pendingScrub = null;
      scrub = locked ? null : scrubFromPointer(event);
      paintGuide();
    } else if (flags.preview) {
      placePreview();
    }
  };
  const schedule = (what: keyof typeof framePending): void => {
    framePending[what] = true;
    if (!frameId) frameId = requestAnimationFrame(runFrame);
  };

  // ── Card ────────────────────────────────────────────────────────────────────────────────────
  let cardEl: HTMLElement | null = null;
  let screenEl: HTMLElement | null = null;
  let screenFile: string | undefined;
  let cardObserver: ResizeObserver | null = null;
  let cardKey = '';
  let cardExpanded = false;
  let entriesCtx: { entries: NoteEntry[]; columns?: [string, string]; expanded: boolean; found: string; query: string; list: NoteEntry[] } | null = null;

  const closeCard = (): void => {
    cardObserver?.disconnect();
    cardObserver = null;
    cardEl?.remove();
    screenEl?.remove();
    cardEl = null;
    screenEl = null;
    screenFile = undefined;
    cardKey = '';
    entriesCtx = null;
    if (highlight) {
      highlight = null;
      paintHighlight();
    }
  };

  const entryRows = (): string => {
    const ctx = entriesCtx!;
    const { entries, expanded, found } = ctx;
    const foundRows = found.trim() ? matchEntries(entries, found) : [];
    const matched = expanded ? matchEntries(entries, ctx.query) : (foundRows.length > 0 ? foundRows : entries).slice(0, ENTRIES_PREVIEWED);
    const shownRows = matched.slice(0, MAX_MEMBERS_SHOWN);
    ctx.list = shownRows;
    const groups: Array<{ name?: string; rows: Array<{ entry: NoteEntry; index: number }> }> = [];
    shownRows.forEach((entry, index) => {
      let group = groups.find((candidate) => candidate.name === entry.group);
      if (!group) {
        group = { name: entry.group, rows: [] };
        groups.push(group);
      }
      group.rows.push({ entry, index });
    });
    let html = '';
    if (shownRows.length > 0) {
      html += '<table class="streamcardtable"><colgroup><col class="streamcardkey"><col></colgroup>';
      if (ctx.columns) html += `<thead><tr>${ctx.columns.map((column) => `<th scope="col">${esc(column)}</th>`).join('')}</tr></thead>`;
      for (const group of groups) {
        html += '<tbody>';
        if (group.name) html += `<tr class="streamcardgroup"><th colspan="2" scope="rowgroup">${esc(group.name)}</th></tr>`;
        for (const { entry, index } of group.rows) {
          html += `<tr${entry.boxes ? ' data-streams-boxed' : ''} data-streams-entry="${index}"><th scope="row">${esc(entry.k)}</th><td>${esc(entry.v)}${entry.badge ? badgeHtml(entry.badge.text, entry.badge.tone) : ''}</td></tr>`;
        }
        html += '</tbody>';
      }
      html += '</table>';
    }
    if (matched.length === 0) html += '<p class="streamcardnote">Nothing matches.</p>';
    if (!expanded && foundRows.length > 0) {
      html += `<p class="streamcardnote">${count(foundRows.length)} of ${count(entries.length)} match “${esc(found.trim())}”. Click to search all ${count(entries.length)}.</p>`;
    } else if (!expanded && entries.length > ENTRIES_PREVIEWED) {
      html += `<p class="streamcardnote">${count(entries.length - ENTRIES_PREVIEWED)} more. Click to search all ${count(entries.length)}.</p>`;
    }
    if (expanded && matched.length > MAX_MEMBERS_SHOWN) html += `<p class="streamcardnote">and ${count(matched.length - MAX_MEMBERS_SHOWN)} more. Search to narrow them.</p>`;
    return html;
  };

  const cardHtml = (h: Hover, note: StreamNote, lane: StreamLane, expanded: boolean): string => {
    const members = note.members ?? [];
    let html = `<div class="streamcardmeta"><span>${esc(lane.name)}</span><span>+${offset(note.t - model.startMs)}</span><span>${esc(clockTime(note.t))}</span>`
      + (theaterOk ? `<button type="button" class="streamcardlink" data-streams-theater="${note.shotT ?? note.t}">Open in Theater</button>` : '')
      + (expanded && host.shareLink ? '<button type="button" class="streamcardlink" data-streams-copy>Copy link</button>' : '')
      + '</div>';
    html += `<div class="streamcardtitle"${note.tone ? ` data-streams-tone="${note.tone}"` : ''}>${esc(note.label)}</div>`;
    if (note.detail && !note.members && !note.entries) html += `<div class="streamcarddetail">${esc(note.detail)}</div>`;
    if (note.badges.length > 0) html += `<div class="streamcardbadges">${note.badges.map((badge) => badgeHtml(badge.text, badge.tone)).join('')}</div>`;
    if (note.fields.length > 0) {
      html += `<dl class="streamcardfields">${note.fields.map((field) => `<div><dt>${esc(field.k)}</dt><dd>${field.href ? `<a href="${esc(field.href)}" target="_blank" rel="noreferrer">${esc(field.v)}</a>` : esc(field.v)}</dd></div>`).join('')}</dl>`;
    }
    if (members.length > 0) {
      html += `<ol class="streamcardmembers">${members.slice(0, MAX_MEMBERS_SHOWN).map((member) => `<li>${esc(member)}</li>`).join('')}${members.length > MAX_MEMBERS_SHOWN ? `<li class="streamcardmore">and ${count(members.length - MAX_MEMBERS_SHOWN)} more</li>` : ''}</ol>`;
    }
    if (note.entries && note.entries.length > 0) {
      const found = h.query ?? '';
      const searchable = note.entries.length > ENTRIES_PREVIEWED;
      const foundRows = found.trim() ? matchEntries(note.entries, found) : [];
      // What the reader typed into this card's own search outlives a refresh, like the pin it is on.
      const typed = expanded && searchable ? (state.locked as SavedPin | null | undefined)?.entryQuery : undefined;
      entriesCtx = { entries: note.entries, columns: note.entryColumns, expanded, found, query: typed ?? (searchable && foundRows.length > 0 ? found.trim() : ''), list: [] };
      const shownCount = typed?.trim() ? `${count(matchEntries(note.entries, typed).length)} of ${count(note.entries.length)}` : '';
      html += '<div class="streamcardentries">'
        + (expanded && searchable ? `<div class="streamcardsearch"><input type="search" placeholder="Search ${count(note.entries.length)}" aria-label="Search this list" data-streams-entry-search value="${esc(entriesCtx.query)}"><span data-streams-entry-count${shownCount ? '' : ' hidden'}>${shownCount}</span></div>` : '')
        + `<div data-streams-entry-rows>${entryRows()}</div></div>`;
    }
    if (note.body) html += `<pre class="streamcardbody">${codeHtml(note.body, note.bodyLang)}</pre>`;
    return html;
  };

  const placeCard = (): void => {
    const h = shownHover();
    if (!cardEl || !h) return;
    const gap = 8;
    const head = headHeight();
    const pinned = pinnedWidth();
    const viewLeft = scroll.scrollLeft + pinned;
    const viewRight = scroll.scrollLeft + scroll.clientWidth;
    const viewTop = scroll.scrollTop + head + gap;
    const viewBottom = scroll.scrollTop + scroll.clientHeight - gap;
    // Measured at its stylesheet width each time, so a card narrowed for one note widens again for the next.
    cardEl.style.width = '';
    const natural = cardEl.offsetWidth;
    const { left, width } = cardBeside(h.anchor, natural, { left: viewLeft, right: viewRight }, gap);
    cardEl.style.width = width < natural ? `${width}px` : '';
    const height = cardEl.offsetHeight;
    // The screen may cover the pinned columns: it is the thumbnail there, made big enough to read.
    const beside = screenEl
      ? screenBeside({ left, width }, { left: scroll.scrollLeft + gap, right: viewRight - gap }, viewBottom - viewTop, aspectFor(screenFile), gap, left > h.anchor.left ? 'after' : 'before')
      : undefined;
    const top = Math.min(Math.max(viewTop, h.anchor.top - 6), Math.max(viewTop, viewBottom - Math.max(height, beside?.height ?? 0)));
    cardEl.style.left = `${left}px`;
    cardEl.style.top = `${top}px`;
    cardEl.style.maxHeight = `${viewBottom - viewTop}px`;
    cardEl.style.visibility = '';
    if (screenEl) {
      screenEl.hidden = !beside;
      if (beside) {
        screenEl.style.left = `${beside.left}px`;
        screenEl.style.top = `${top}px`;
        screenEl.style.width = `${beside.width}px`;
        screenEl.style.height = `${beside.height}px`;
      }
      checkCovers();
    }
  };

  /** The thumbnail is sticky, so scrolling can slide it under the big screen or out from under it. */
  const checkCovers = (): void => {
    if (!screenEl || screenEl.hidden || !linkEl) return;
    const big = screenEl.getBoundingClientRect();
    const thumb = linkEl.getBoundingClientRect();
    screenEl.toggleAttribute('data-streams-covers', big.left < thumb.right && thumb.left < big.right && big.top < thumb.bottom && thumb.top < big.bottom);
  };

  const showCard = (): void => {
    const h = shownHover();
    if (!h) {
      closeCard();
      return;
    }
    const note = noteAt(h.b, h.l, h.n);
    const lane = layoutLanes[h.l];
    if (!note || !lane) {
      closeCard();
      return;
    }
    const expanded = locked !== null && h === locked;
    // Each lock opens the card afresh; a hover that merely moves on rebuilds it for the next note.
    if (cardEl && cardKey === h.key && cardExpanded === expanded) {
      placeCard();
      return;
    }
    closeCard();
    cardKey = h.key;
    cardExpanded = expanded;
    const card = document.createElement('div');
    card.className = 'streamcard';
    card.setAttribute('role', expanded ? 'dialog' : 'tooltip');
    if (expanded) {
      card.setAttribute('aria-label', note.label);
      card.setAttribute('data-streams-expanded', '');
    }
    card.style.visibility = 'hidden';
    card.innerHTML = cardHtml(h, note, lane, expanded);
    card.addEventListener('pointerenter', () => window.clearTimeout(hideTimer));
    card.addEventListener('pointerleave', scheduleHide);
    scroll.appendChild(card);
    cardEl = card;
    // A locked list of on-screen elements shows its screen big, over the thumbnail.
    const file = note.shot ?? (screenAt(model, note.t)?.file);
    const src = file ? host.shotSrc(file) : null;
    if (expanded && src && note.entries?.some((entry) => entry.boxes)) {
      const big = document.createElement('div');
      big.className = 'streamcardscreen';
      big.innerHTML = `<img alt="" src="${esc(src)}">`;
      scroll.appendChild(big);
      screenEl = big;
      screenFile = file;
      // Its own pixels settle its shape once they load, if the capture didn't say.
      big.querySelector('img')?.addEventListener('load', (event) => {
        if (learnAspect(file!, event.target as HTMLImageElement) && screenEl === big) placeCard();
      });
    }
    placeCard();
    // Headers and bodies can change size after the card opens, and the board can be resized under them.
    cardObserver = new ResizeObserver(placeCard);
    cardObserver.observe(card);
    cardObserver.observe(scroll);
    paintHighlight();
  };

  const anchorOf = (target: HTMLElement): Hover['anchor'] => {
    const rect = target.getBoundingClientRect();
    const box = scroll.getBoundingClientRect();
    const dx = scroll.scrollLeft - box.left - scroll.clientLeft;
    const dy = scroll.scrollTop - box.top - scroll.clientTop;
    return { top: rect.top + dy, left: rect.left + dx, right: rect.right + dx };
  };

  const noteElement = (key: string): HTMLElement | null => {
    for (const el of grid.querySelectorAll<HTMLElement>('[data-streams-note]')) {
      if (keyOf(Number(el.dataset.streamsB), Number(el.dataset.streamsL), Number(el.dataset.streamsN)) === key) return el;
    }
    return null;
  };

  const hoverFor = (el: HTMLElement): Hover => {
    const b = Number(el.dataset.streamsB);
    const l = Number(el.dataset.streamsL);
    const n = Number(el.dataset.streamsN);
    const key = keyOf(b, l, n);
    return {
      key, b, l, n, laneId: layoutLanes[l].id,
      y: layouts[b].offsets[l][n] + NOTE_HEIGHT / 2,
      anchor: anchorOf(el),
      ...(matchKeys.has(key) ? { query } : {}),
    };
  };

  function scheduleHide(): void {
    window.clearTimeout(hideTimer);
    hideTimer = window.setTimeout(() => {
      hover = null;
      showCard();
      paintGuide();
    }, 120);
  }
  const showHover = (next: Hover): void => {
    window.clearTimeout(hideTimer);
    hover = next;
    showCard();
    paintGuide();
  };

  /** A locked card's anchor was measured against the last layout, so it is measured again, and lets go
   * once its note is collapsed or hidden. */
  const refreshCard = (): void => {
    if (locked) {
      const b = locked.b;
      const l = layoutLanes.findIndex((lane) => lane.id === locked!.laneId);
      const element = l < 0 ? null : noteElement(noteKey(b, locked.laneId, locked.n));
      const top = l < 0 ? undefined : layouts[b]?.offsets[l]?.[locked.n];
      if (element && top !== undefined) {
        const key = noteKey(b, locked.laneId, locked.n);
        locked = { ...locked, key, l, y: top + NOTE_HEIGHT / 2, anchor: anchorOf(element) };
        hover = null;
      } else {
        locked = null;
        grid.removeAttribute('data-streams-selecting');
      }
    } else {
      hover = null;
    }
    closeCard();
    showCard();
    paintGuide();
  };

  const lock = (next: Hover | null): void => {
    locked = next;
    scrub = null;
    if (next) grid.setAttribute('data-streams-selecting', '');
    else grid.removeAttribute('data-streams-selecting');
    for (const el of grid.querySelectorAll<HTMLElement>('[data-streams-note]')) {
      const key = keyOf(Number(el.dataset.streamsB), Number(el.dataset.streamsL), Number(el.dataset.streamsN));
      el.toggleAttribute('data-streams-selected', locked?.key === key);
      el.setAttribute('aria-pressed', String(locked?.key === key));
    }
    closeCard();
    if (next) {
      hover = next;
      const note = noteAt(next.b, next.l, next.n);
      if (note) host.setEpochMs(note.t);
      state.locked = { b: next.b, laneId: next.laneId, n: next.n, ...(note ? { t: note.t, label: note.label, sig: noteSignature(note) } : {}) } satisfies SavedPin;
    } else {
      state.locked = null;
      // The guide falls back to the scrubber pin, so the address does too, or clears with none.
      host.setEpochMs(pinnedT);
    }
    showCard();
    paintGuide();
  };

  // ── Collapse, jump ──────────────────────────────────────────────────────────────────────────
  const toggleBand = (band: number): void => {
    if (!collapsed.delete(band)) collapsed.add(band);
    persist();
    renderAll();
  };

  const scrollTo = (top: number, left?: number): void => {
    const beforeTop = scroll.scrollTop;
    const beforeLeft = scroll.scrollLeft;
    scroll.scrollTop = Math.max(0, top);
    if (left !== undefined) scroll.scrollLeft = Math.max(0, left);
    // The scroll event clears hover; a jump that moved nothing raises none.
    if (scroll.scrollTop !== beforeTop || scroll.scrollLeft !== beforeLeft) suppressScrolls++;
    measure();
  };

  const goToStep = (band: number): void => {
    const wasCollapsed = collapsed.delete(band);
    if (wasCollapsed) {
      persist();
      renderAll();
    }
    const top = bandTops[band] ?? 0;
    if (host.reducedMotion) scrollTo(top);
    else scroll.scrollTo({ top, behavior: 'smooth' });
  };

  // ── Find ────────────────────────────────────────────────────────────────────────────────────
  function runFind(resetIndex: boolean): void {
    matches = searchNotes(layoutLanes, query);
    if (resetIndex) findIndex = 0;
    findIndex = matches.length > 0 ? Math.min(findIndex, matches.length - 1) : 0;
    matchKeys = new Set(matches.map((match) => noteKey(match.band, layoutLanes[match.lane].id, match.note)));
    const current = matches[findIndex];
    currentKey = current ? noteKey(current.band, layoutLanes[current.lane].id, current.note) : undefined;
    paintMatches();
  }

  function paintMatches(): void {
    for (const el of grid.querySelectorAll<HTMLElement>('[data-streams-note]')) {
      const key = keyOf(Number(el.dataset.streamsB), Number(el.dataset.streamsL), Number(el.dataset.streamsN));
      el.toggleAttribute('data-streams-match', matchKeys.has(key));
      el.toggleAttribute('data-streams-current', key === currentKey);
    }
    for (const el of grid.querySelectorAll<HTMLElement>('.streamscollapsedcount')) {
      const b = Number(el.dataset.streamsToggle);
      const cell = el.closest<HTMLElement>('.streamslane');
      const l = Number(cell?.dataset.streamsL);
      el.toggleAttribute('data-streams-match', layoutLanes[l]?.bands[b]?.some((_, n) => matchKeys.has(keyOf(b, l, n))) ?? false);
    }
    const active = query.trim() !== '';
    findCount.hidden = !active;
    findCount.textContent = matches.length > 0 ? `${findIndex + 1} of ${count(matches.length)}` : 'No matches';
    for (const button of root.querySelectorAll<HTMLButtonElement>('[data-streams-find-step]')) button.disabled = matches.length === 0;
    const clear = root.querySelector<HTMLButtonElement>('[data-streams-find-clear]');
    if (clear) clear.disabled = query === '';
  }

  const jumpToMatch = (match: NoteMatch | undefined): void => {
    if (!match) {
      // A card the find opened goes with its target; one the reader opened stays.
      window.clearTimeout(jumpTimer);
      if (hover?.byFind && !locked) {
        hover = null;
        showCard();
        paintGuide();
      }
      return;
    }
    if (locked) lock(null);
    const laneId = layoutLanes[match.lane].id;
    if (collapsed.delete(match.band)) {
      persist();
      renderAll();
    }
    const l = layoutLanes.findIndex((lane) => lane.id === laneId);
    const top = layouts[match.band]?.offsets[l]?.[match.note];
    if (top === undefined) return;
    const head = headHeight();
    const y = head + bandTops[match.band] + top;
    let nextTop = scroll.scrollTop;
    if (y < scroll.scrollTop + head || y + NOTE_HEIGHT > scroll.scrollTop + scroll.clientHeight) {
      nextTop = y - head - (scroll.clientHeight - head - NOTE_HEIGHT) / 2;
    }
    let nextLeft = scroll.scrollLeft;
    const laneHead = grid.querySelector<HTMLElement>(`.streamslanehead[data-streams-lane="${CSS.escape(laneId)}"]`);
    const pinned = pinnedWidth();
    if (laneHead && (laneHead.offsetLeft < scroll.scrollLeft + pinned || laneHead.offsetLeft + laneHead.offsetWidth > scroll.scrollLeft + scroll.clientWidth)) {
      nextLeft = laneHead.offsetLeft - pinned;
    }
    scrollTo(nextTop, nextLeft);
    const key = noteKey(match.band, laneId, match.note);
    window.clearTimeout(jumpTimer);
    // The scroll's own event clears hover, so the card opens once it has passed.
    jumpTimer = window.setTimeout(() => {
      const element = noteElement(key);
      if (!element) return;
      const next = hoverFor(element);
      showHover({ ...next, query, byFind: true });
    }, 60);
  };

  const search = (next: string): void => {
    query = next;
    state.query = next;
    if (findInput.value !== next) findInput.value = next;
    runFind(true);
    jumpToMatch(matches[0]);
  };
  const stepFind = (direction: -1 | 1): void => {
    if (matches.length === 0) return;
    findIndex = (findIndex + direction + matches.length) % matches.length;
    runFind(false);
    jumpToMatch(matches[findIndex]);
  };

  // ── Pin and keyboard steps ──────────────────────────────────────────────────────────────────
  const revealGuide = (): void => {
    const guide = currentGuide();
    if (!guide) return;
    const head = headHeight();
    const y = head + bandTops[guide.band] + guide.y;
    if (y < scroll.scrollTop + head + 24 || y > scroll.scrollTop + scroll.clientHeight - 24) {
      scrollTo(y - head - (scroll.clientHeight - head) / 2);
    }
  };
  const pinAt = (t: number | null, reveal: boolean): void => {
    pinnedT = t;
    state.pinnedT = t;
    host.setEpochMs(t);
    paintGuide();
    if (reveal && t !== null) revealGuide();
  };

  // ── Lane arrangement ────────────────────────────────────────────────────────────────────────
  const moveLaneOrder = (id: string, targetId: string): boolean => {
    if (id === targetId) return false;
    const order = arranged.map((lane) => lane.id).filter((laneId) => laneId !== id);
    const target = order.indexOf(targetId);
    const from = arranged.findIndex((lane) => lane.id === id);
    const to = arranged.findIndex((lane) => lane.id === targetId);
    if (target < 0 || from < 0) return false;
    order.splice(from < to ? target + 1 : target, 0, id);
    setPref({ ...pref, order });
    arrange();
    return true;
  };
  const toggleLane = (id: string): void => {
    setPref({ ...pref, order: arranged.map((lane) => lane.id), visible: { ...pref.visible, [id]: !shown(id) } });
    renderAll();
    refreshMenu();
  };
  const setLaneWidth = (id: string, width: number | undefined): void => {
    const widths = { ...pref.widths };
    if (width === undefined) delete widths[id];
    else widths[id] = Math.round(Math.min(MAX_LANE_WIDTH, Math.max(MIN_LANE_WIDTH, width)));
    setPref({ ...pref, widths });
    applyColumns();
  };

  const startResize = (event: PointerEvent, handle: HTMLElement, id: string): void => {
    if (event.button !== 0) return;
    event.preventDefault();
    event.stopPropagation();
    const startX = event.clientX;
    const startWidth = handle.parentElement!.getBoundingClientRect().width;
    const widthAt = (x: number) => Math.min(MAX_LANE_WIDTH, Math.max(MIN_LANE_WIDTH, startWidth + x - startX));
    handle.setPointerCapture(event.pointerId);
    hover = null;
    showCard();
    root.setAttribute('data-streams-resizing', '');
    handle.setAttribute('data-streams-active', '');
    resizing = { id, width: startWidth };
    const move = (e: PointerEvent) => {
      resizing = { id, width: widthAt(e.clientX) };
      applyColumns();
    };
    const end = (e: PointerEvent) => {
      stop();
      resizing = null;
      root.removeAttribute('data-streams-resizing');
      handle.removeAttribute('data-streams-active');
      // A cancel aborts the drag, and its clientX isn't a drop position.
      if (e.type === 'pointerup') setLaneWidth(id, widthAt(e.clientX));
      else applyColumns();
    };
    const stop = () => {
      handle.removeEventListener('pointermove', move);
      handle.removeEventListener('pointerup', end);
      handle.removeEventListener('pointercancel', end);
      cleanups.delete(stop);
    };
    handle.addEventListener('pointermove', move);
    handle.addEventListener('pointerup', end);
    handle.addEventListener('pointercancel', end);
    cleanups.add(stop);
  };

  // ── Streams menu ────────────────────────────────────────────────────────────────────────────
  let menuEl: HTMLElement | null = null;
  let menuDragging: string | null = null;
  let menuCleanup: (() => void) | null = null;

  const menuHtml = (): string => `<div class="streamsmenuhead"><span>Event streams</span><button type="button" data-streams-reset>Reset</button></div>
    <ul>${arranged.map((lane) => `<li draggable="true" data-streams-menu-lane="${esc(lane.id)}"><label>${GRIP}<input type="checkbox"${shown(lane.id) ? ' checked' : ''} data-streams-menu-check="${esc(lane.id)}" aria-label="Show ${esc(lane.name)}. Alt+Up and Alt+Down move it."><span class="streamsmenuname">${esc(lane.name)}</span><span class="streamsmenucount">${count(lane.total)}</span></label></li>`).join('')}</ul>
    <p class="streamsmenuhint">Drag to reorder, here or by a stream's header.</p>`;

  function refreshMenu(): void {
    if (menuEl) menuEl.innerHTML = menuHtml();
  }

  const closeMenu = (): void => {
    menuCleanup?.();
    menuCleanup = null;
    menuEl?.remove();
    menuEl = null;
    menuButton.setAttribute('aria-expanded', 'false');
  };

  const openMenu = (at: { x: number; y: number } | null): void => {
    closeMenu();
    const menu = document.createElement('div');
    menu.className = 'streamsmenu';
    menu.setAttribute('role', 'dialog');
    menu.setAttribute('aria-label', 'Choose event streams');
    menu.innerHTML = menuHtml();
    root.appendChild(menu);
    menuEl = menu;
    if (at) {
      const room = root.getBoundingClientRect();
      menu.setAttribute('data-streams-at', '');
      menu.style.left = `${Math.max(8, Math.min(at.x - room.left, room.width - menu.offsetWidth - 8))}px`;
      menu.style.top = `${Math.max(8, Math.min(at.y - room.top, room.height - menu.offsetHeight - 8))}px`;
    } else {
      menuButton.setAttribute('aria-expanded', 'true');
    }
    const list = menu.querySelector<HTMLElement>('ul');
    const overflow = menu.getBoundingClientRect().bottom - root.getBoundingClientRect().bottom + 8;
    if (list && overflow > 0) list.style.maxHeight = `${Math.max(96, list.clientHeight - overflow)}px`;
    const outside = (event: PointerEvent) => {
      const target = event.target as Element;
      if (!menu.contains(target) && !target.closest('[data-streams-menu-toggle]')) closeMenu();
    };
    const escape = (event: KeyboardEvent) => {
      if (event.key === 'Escape' && !modalOpen()) closeMenu();
    };
    document.addEventListener('pointerdown', outside);
    document.addEventListener('keydown', escape);
    menuCleanup = () => {
      document.removeEventListener('pointerdown', outside);
      document.removeEventListener('keydown', escape);
    };
    menu.addEventListener('change', (event) => {
      const id = (event.target as HTMLElement).getAttribute('data-streams-menu-check');
      if (id) toggleLane(id);
    });
    menu.addEventListener('click', (event) => {
      if ((event.target as Element).closest('[data-streams-reset]')) {
        setPref(emptyPreference());
        renderAll();
        refreshMenu();
      }
    });
    menu.addEventListener('dragstart', (event) => {
      const li = (event.target as Element).closest<HTMLElement>('[data-streams-menu-lane]');
      if (!li || !event.dataTransfer) return;
      event.dataTransfer.effectAllowed = 'move';
      event.dataTransfer.setData('text/plain', li.dataset.streamsMenuLane ?? '');
      menuDragging = li.dataset.streamsMenuLane ?? null;
      li.setAttribute('data-streams-dragging', '');
    });
    menu.addEventListener('dragover', (event) => {
      const li = (event.target as Element).closest<HTMLElement>('[data-streams-menu-lane]');
      if (!menuDragging || !li) return;
      event.preventDefault();
      if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
      const target = li.dataset.streamsMenuLane ?? '';
      if (moveLaneOrder(menuDragging, target)) {
        const dragged = menu.querySelector<HTMLElement>(`[data-streams-menu-lane="${CSS.escape(menuDragging)}"]`);
        const after = arranged.findIndex((lane) => lane.id === menuDragging) > arranged.findIndex((lane) => lane.id === target);
        if (dragged) li.parentElement?.insertBefore(dragged, after ? li.nextSibling : li);
        renderAll();
      }
    });
    menu.addEventListener('drop', (event) => event.preventDefault());
    menu.addEventListener('dragend', () => {
      menuDragging = null;
      menu.querySelector('[data-streams-dragging]')?.removeAttribute('data-streams-dragging');
    });
    menu.addEventListener('keydown', (event) => {
      if (!event.altKey || (event.key !== 'ArrowUp' && event.key !== 'ArrowDown')) return;
      const li = (event.target as Element).closest<HTMLElement>('[data-streams-menu-lane]');
      if (!li) return;
      event.preventDefault();
      const index = arranged.findIndex((lane) => lane.id === li.dataset.streamsMenuLane);
      const neighbour = arranged[index + (event.key === 'ArrowUp' ? -1 : 1)];
      if (neighbour && moveLaneOrder(arranged[index].id, neighbour.id)) {
        renderAll();
        refreshMenu();
        menu.querySelector<HTMLInputElement>(`[data-streams-menu-check="${CSS.escape(li.dataset.streamsMenuLane ?? '')}"]`)?.focus();
      }
    });
  };

  // ── Events ──────────────────────────────────────────────────────────────────────────────────
  const closest = (event: Event, selector: string): HTMLElement | null => (event.target as Element | null)?.closest?.<HTMLElement>(selector) ?? null;

  const onPointerMove = (event: PointerEvent): void => {
    const rect = scroll.getBoundingClientRect();
    pointerY = event.clientY - rect.top - scroll.clientTop;
    schedule('preview');
    // Empty space on any row scrubs the clock; a note shows its own moment, and a locked one holds it.
    const target = event.target as Element;
    if (hasScreens && !locked && target.closest('.streamsband, .streamspreview') && !target.closest('.streamnote, .streamcard, .streamcardscreen')) {
      pendingScrub = event;
      schedule('scrub');
    } else if (scrub) {
      scrub = null;
      pendingScrub = null;
      paintGuide();
    }
  };
  const onPointerLeave = (): void => {
    pendingScrub = null;
    if (scrub) {
      scrub = null;
      paintGuide();
    }
  };
  const onScroll = (): void => {
    if (suppressScrolls > 0) suppressScrolls--;
    else if (!locked && hover) {
      window.clearTimeout(hideTimer);
      hover = null;
      showCard();
      paintGuide();
    }
    if (scrub) {
      scrub = null;
      paintGuide();
    }
    state.scrollTop = scroll.scrollTop;
    state.scrollLeft = scroll.scrollLeft;
    schedule('measure');
    if (screenEl) checkCovers();
  };

  const onPointerOver = (event: PointerEvent): void => {
    const note = closest(event, '[data-streams-note]');
    if (note) {
      if (locked) return;
      showHover(hoverFor(note));
      return;
    }
    const row = closest(event, '[data-streams-boxed]');
    if (row && entriesCtx) {
      highlight = entriesCtx.list[Number(row.dataset.streamsEntry)]?.boxes ?? null;
      paintHighlight();
    }
  };
  const onPointerOut = (event: PointerEvent): void => {
    if (closest(event, '[data-streams-note]') && !locked) scheduleHide();
    if (closest(event, '[data-streams-boxed]') && highlight) {
      highlight = null;
      paintHighlight();
    }
  };
  const onFocusIn = (event: FocusEvent): void => {
    const note = closest(event, '[data-streams-note]');
    if (note && !locked) showHover(hoverFor(note));
  };
  const onFocusOut = (event: FocusEvent): void => {
    if (closest(event, '[data-streams-note]') && !locked) scheduleHide();
  };

  const onClick = (event: MouseEvent): void => {
    const target = event.target as Element;
    const note = closest(event, '[data-streams-note]');
    if (note) {
      const next = hoverFor(note);
      lock(locked?.key === next.key ? null : next);
      return;
    }
    const toggle = closest(event, '[data-streams-toggle]');
    if (toggle) return toggleBand(Number(toggle.dataset.streamsToggle));
    const hide = closest(event, '[data-streams-hide]');
    if (hide) return toggleLane(hide.dataset.streamsHide ?? '');
    const menuToggle = closest(event, '[data-streams-menu-toggle="add"]');
    if (menuToggle) {
      if (menuEl) closeMenu();
      else {
        const { left, bottom } = menuToggle.getBoundingClientRect();
        openMenu({ x: left, y: bottom + 4 });
      }
      return;
    }
    const theater = closest(event, '[data-streams-theater]');
    if (theater) return host.openTab('theater', Number(theater.dataset.streamsTheater));
    if (closest(event, '[data-streams-copy]')) {
      const done = (button: HTMLElement) => {
        button.textContent = 'Copied';
        window.setTimeout(() => { button.textContent = 'Copy link'; }, 1500);
      };
      const button = closest(event, '[data-streams-copy]')!;
      if (host.shareLink) navigator.clipboard?.writeText(host.shareLink()).then(() => done(button), () => {});
      return;
    }
    if (closest(event, '.streamspreviewlink')) {
      const guide = currentGuide();
      const t = scrub?.t ?? (shownHover() ? noteAt(shownHover()!.b, shownHover()!.l, shownHover()!.n)?.t : undefined) ?? pinnedT ?? (guide ? clockOf(guide.band)(guide.y) : undefined) ?? shownShotT;
      if (theaterOk && t !== undefined) host.openTab('theater', t);
      return;
    }
    // Empty space on a row pins the scrub line there, and clicking it again lets go.
    if (!locked && target.closest('.streamsband, .streamspreview') && !target.closest('button, a, .streamcard, .streamcardscreen, .streamsstep')) {
      const at = scrubFromPointer(event);
      if (at) pinAt(pinnedT !== null && Math.abs(pinnedT - at.t) < 1 ? null : at.t, false);
    }
  };

  const onDocPointerDown = (event: PointerEvent): void => {
    if (!locked) return;
    if (!(event.target as Element).closest?.('.streamcard, .streamcardscreen, .streamnote, .streamspreviewlink')) lock(null);
  };

  /** How much of the left edge the pinned columns cover; on a narrow screen only Step stays pinned. */
  const pinnedWidth = (): number => [...grid.querySelectorAll<HTMLElement>('.streamshead.streamsstepcol, .streamshead.streamsshotcol')]
    .reduce((sum, cell) => sum + (getComputedStyle(cell).left !== 'auto' ? cell.offsetWidth : 0), 0);

  const onInput = (event: Event): void => {
    const input = event.target as HTMLInputElement;
    if (input === findInput) return search(input.value);
    if (input.matches?.('[data-streams-entry-search]') && entriesCtx) {
      entriesCtx.query = input.value;
      const pin = state.locked as SavedPin | null | undefined;
      if (pin) pin.entryQuery = input.value;
      const rows = cardEl?.querySelector<HTMLElement>('[data-streams-entry-rows]');
      if (rows) rows.innerHTML = entryRows();
      const counter = cardEl?.querySelector<HTMLElement>('[data-streams-entry-count]');
      if (counter) {
        counter.hidden = !input.value.trim();
        counter.textContent = `${count(matchEntries(entriesCtx.entries, input.value).length)} of ${count(entriesCtx.entries.length)}`;
      }
      placeCard();
    }
  };

  const onGridKeyDown = (event: KeyboardEvent): void => {
    const target = event.target as HTMLElement;
    const resize = target.closest<HTMLElement>('[data-streams-resize]');
    if (resize && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
      event.preventDefault();
      event.stopPropagation();
      const id = resize.dataset.streamsResize ?? '';
      setLaneWidth(id, widthOf(id) + (event.key === 'ArrowLeft' ? -RESIZE_STEP : RESIZE_STEP));
      resize.setAttribute('aria-valuenow', String(Math.round(widthOf(id))));
      return;
    }
    const head = target.closest<HTMLElement>('.streamslanehead');
    if (head && event.altKey && (event.key === 'ArrowLeft' || event.key === 'ArrowRight')) {
      event.preventDefault();
      const id = head.dataset.streamsLane ?? '';
      const index = visible.findIndex((lane) => lane.id === id);
      const neighbour = visible[index + (event.key === 'ArrowLeft' ? -1 : 1)];
      if (neighbour && moveLaneOrder(id, neighbour.id)) {
        renderAll();
        refreshMenu();
        grid.querySelector<HTMLElement>(`.streamslanehead[data-streams-lane="${CSS.escape(id)}"]`)?.focus();
      }
    }
  };

  // Header drag, delegated: the heads are rebuilt by every render.
  const onDragStart = (event: DragEvent): void => {
    const head = closest(event, '.streamslanehead');
    if (!head || !event.dataTransfer) return;
    if (resizing) {
      event.preventDefault();
      return;
    }
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', head.dataset.streamsLane ?? '');
    draggingLane = head.dataset.streamsLane ?? null;
    head.setAttribute('data-streams-dragging', '');
    hover = null;
    showCard();
  };
  const onDragOver = (event: DragEvent): void => {
    const head = closest(event, '.streamslanehead');
    if (!draggingLane || !head) return;
    event.preventDefault();
    if (event.dataTransfer) event.dataTransfer.dropEffect = 'move';
    // Only the columns move while the header is held; notes keep their layout until it lands.
    if (moveLaneOrder(draggingLane, head.dataset.streamsLane ?? '')) {
      dragDirty = true;
      applyColumns();
    }
  };
  const onDragEnd = (): void => {
    draggingLane = null;
    grid.querySelector('[data-streams-dragging]')?.removeAttribute('data-streams-dragging');
    if (dragDirty) {
      dragDirty = false;
      renderAll();
      refreshMenu();
    }
  };

  const onResizeDown = (event: PointerEvent): void => {
    const handle = closest(event, '[data-streams-resize]');
    if (handle) startResize(event, handle, handle.dataset.streamsResize ?? '');
  };
  const onDoubleClick = (event: MouseEvent): void => {
    const handle = closest(event, '[data-streams-resize]');
    if (handle) setLaneWidth(handle.dataset.streamsResize ?? '', undefined);
  };
  const onContextMenu = (event: MouseEvent): void => {
    if (!closest(event, '.streamslanehead')) return;
    event.preventDefault();
    openMenu({ x: event.clientX, y: event.clientY });
  };

  // Toolbar and diagnosis: both sit outside the scroller.
  const onRootClick = (event: MouseEvent): void => {
    if (scroll.contains(event.target as Node)) return;
    const step = closest(event, '[data-streams-find-step]');
    if (step) return stepFind(Number(step.dataset.streamsFindStep) < 0 ? -1 : 1);
    if (closest(event, '[data-streams-find-clear]')) {
      search('');
      findInput.focus();
      return;
    }
    const toolbarToggle = closest(event, '[data-streams-menu-toggle="toolbar"]');
    if (toolbarToggle) {
      if (menuEl) closeMenu();
      else openMenu(null);
      return;
    }
    const jump = closest(event, '[data-streams-jump]');
    if (jump) return goToStep(Number(jump.dataset.streamsJump));
    const detail = closest(event, '[data-streams-detail]');
    if (detail) {
      const index = Number(detail.dataset.streamsDetail);
      if (!diagOpen.delete(index)) diagOpen.add(index);
      renderDiagnosis();
      schedule('measure');
    }
  };
  const onDiagJump = (event: MouseEvent): void => {
    // Diagnosis lives above the scroller, so root-level delegation covers it.
    onRootClick(event);
  };

  findInput.addEventListener('keydown', (event) => {
    if (event.key === 'Enter') {
      event.preventDefault();
      stepFind(event.shiftKey ? -1 : 1);
    } else if (event.key === 'Escape') {
      event.stopPropagation();
      if (query) search('');
      else findInput.blur();
    }
  });

  const onWindowKeyDown = (event: KeyboardEvent): void => {
    if (!root.isConnected || modalOpen()) return;
    const target = event.target instanceof Element ? event.target : null;
    if ((event.metaKey || event.ctrlKey) && !event.altKey && !event.shiftKey && event.key.toLowerCase() === 'f') {
      event.preventDefault();
      event.stopPropagation();
      findInput.focus();
      findInput.select();
      return;
    }
    if (target && target.closest('input, textarea, select, [contenteditable]')) return;
    if (event.metaKey || event.ctrlKey || event.altKey) return;
    if (event.key === 'Escape') {
      if (locked) {
        event.preventDefault();
        event.stopPropagation();
        lock(null);
      } else if (menuEl) {
        closeMenu();
      } else if (pinnedT !== null) {
        event.preventDefault();
        event.stopPropagation();
        pinAt(null, false);
      }
      return;
    }
    // With a pinned moment, the arrows step it note to note; otherwise they scroll as usual.
    if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && pinnedT !== null && !locked && !target?.closest?.('.streamsmenu')) {
      event.preventDefault();
      event.stopPropagation();
      const next = stepMoment(moments, pinnedT, event.key === 'ArrowDown' ? 1 : -1);
      if (next !== pinnedT) pinAt(next, true);
    }
  };

  // ── Wire up ─────────────────────────────────────────────────────────────────────────────────
  const listen = <K extends keyof HTMLElementEventMap>(el: HTMLElement, type: K, handler: (event: HTMLElementEventMap[K]) => void, options?: AddEventListenerOptions): void => {
    el.addEventListener(type, handler as EventListener, options);
  };
  listen(scroll, 'pointermove', onPointerMove);
  listen(scroll, 'pointerleave', onPointerLeave);
  listen(scroll, 'scroll', onScroll, { passive: true });
  listen(scroll, 'pointerover', onPointerOver);
  listen(scroll, 'pointerout', onPointerOut);
  listen(scroll, 'focusin', onFocusIn);
  listen(scroll, 'focusout', onFocusOut);
  listen(scroll, 'click', onClick);
  listen(root, 'input', onInput);
  listen(scroll, 'keydown', onGridKeyDown);
  listen(scroll, 'dragstart', onDragStart);
  listen(scroll, 'dragover', onDragOver);
  listen(scroll, 'drop', (event) => event.preventDefault());
  listen(scroll, 'dragend', onDragEnd);
  listen(scroll, 'pointerdown', onResizeDown);
  listen(scroll, 'dblclick', onDoubleClick);
  listen(scroll, 'contextmenu', onContextMenu);
  listen(root, 'click', onDiagJump);
  document.addEventListener('pointerdown', onDocPointerDown);
  window.addEventListener('keydown', onWindowKeyDown, true);
  const resizeObserver = new ResizeObserver(() => schedule('measure'));
  resizeObserver.observe(scroll);

  // ── First paint ─────────────────────────────────────────────────────────────────────────────
  if (pinnedT !== null) {
    // The band the moment sits in opens, so the line has somewhere to be.
    const band = bandAt(model, pinnedT);
    if (collapsed.delete(band)) persist();
  }
  // An explicit seek (a link, history, another tab's "open this moment") outranks where the reader
  // had scrolled and what they had selected, which would otherwise hide the moment they asked for.
  const seeking = host.seek === true && host.initialEpochMs !== null;
  if (seeking) {
    state.locked = null;
    delete state.scrollTop;
    delete state.scrollLeft;
  }
  const saved = state.locked as SavedPin | null | undefined;
  // The lanes are arranged here, ahead of renderAll, so the saved pin has lanes to be found in.
  arrange();
  const savedLane = saved ? visible.find((lane) => lane.id === saved.laneId) : undefined;
  // The note the reader selected is found again by what it is, and one that no longer exists
  // clears the selection rather than selecting its neighbour.
  const at = saved && savedLane ? resolvePin(savedLane, saved) : undefined;
  restoring = saved && at ? { b: at.b, laneId: saved.laneId, n: at.n } : null;
  renderAll();
  if (typeof state.scrollTop === 'number') scrollTo(state.scrollTop as number, typeof state.scrollLeft === 'number' ? state.scrollLeft as number : 0);
  else if (pinnedT !== null) revealGuide();
  if (query.trim()) {
    findInput.value = query;
    runFind(false);
  }
  if (saved) {
    const l = layoutLanes.findIndex((lane) => lane.id === saved.laneId);
    const key = at ? noteKey(at.b, saved.laneId, at.n) : '';
    const element = key ? noteElement(key) : null;
    if (element) {
      const next = hoverFor(element);
      locked = next;
      grid.setAttribute('data-streams-selecting', '');
      element.setAttribute('data-streams-selected', '');
      hover = next;
      showCard();
      paintGuide();
      // The restored card's moment goes in the address, as selecting it did, so Copy link names it.
      // A link that asked for another moment keeps that one.
      const note = noteAt(at!.b, l, at!.n);
      if (note && !host.seek) host.setEpochMs(note.t);
    } else {
      state.locked = null;
    }
    restoring = null;
  }
  // Coming back to the tab restores the scrubber pin, and the address names it again so Copy link does.
  if (!host.seek && !locked && pinnedT !== null && pinnedT !== host.initialEpochMs) host.setEpochMs(pinnedT);
  persist();

  return {
    dispose() {
      document.removeEventListener('pointerdown', onDocPointerDown);
      window.removeEventListener('keydown', onWindowKeyDown, true);
      menuCleanup?.();
      menuCleanup = null;
      for (const stop of [...cleanups]) stop();
      resizeObserver.disconnect();
      cardObserver?.disconnect();
      window.clearTimeout(hideTimer);
      window.clearTimeout(jumpTimer);
      if (frameId) cancelAnimationFrame(frameId);
      frameId = 0;
      persist();
      state.scrollTop = scroll.scrollTop;
      state.scrollLeft = scroll.scrollLeft;
      root.remove();
    },
  };
}
