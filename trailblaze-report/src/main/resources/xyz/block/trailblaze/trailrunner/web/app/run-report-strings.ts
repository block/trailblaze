// Pure model + markup builders for the report's Strings tab: every string the run's screens showed
// (recorded on each capture log, read by report/run-report-visible-strings.ts), listed once, each
// tied back to the screenshots it appeared on and its box there. No DOM and no viewer state — the
// viewer (run-report-viewer.ts) owns the tab state and event wiring, and the tests exercise these
// builders directly.

import { looksVolatile } from '../../../report/run-report-visible-strings';
import { parseLogTimestamp } from './run-report-extract';

/** How the tab sorts a string for its filter chips. */
type StringKind = 'copy' | 'changing' | 'a11y';

/** Which strings the tab shows by whether they were on screen when captured. */
type StringVisibility = 'visible' | 'hidden' | 'all';

/** One distinct screen: a capture that carried strings, plus every later capture that showed the same. */
interface StringsScreen {
  id: number;
  /** Where each capture of this screen falls among the run's captures (1-based), first one first. */
  captures: number[];
  captureId: string | null;
  /** The screenshot's filename, when the capture has one. */
  screenshot: string | null;
  /** The key into SessionPayload.shots for this screen's screenshot, when the report has it. */
  shot: string | null;
  action: string | null;
  w: number;
  h: number;
  /** Where the capture sits in the Timeline (trace step + folded dispatch), when it is there. */
  at: { step: number; kid: number | null } | null;
  /** When the capture was taken, epoch ms on the recording's clock; null when its log had no time. */
  atMs: number | null;
  /**
   * Every capture of this screen that had no screenshot but a time, first one first: where a frame
   * from the recording can stand in for the missing screenshot. Any of them will do, since they all
   * showed the same thing, and the recording may cover only some.
   */
  frameTimes: Array<{ captureId: string | null; atMs: number; at: { step: number; kid: number | null } | null }>;
}

/** One place a string appeared. `bounds` is `[left, top, right, bottom]` in device points. */
interface StringsHit {
  screen: number;
  text: string;
  bounds: [number, number, number, number] | null;
  visible: boolean;
}

/** One distinct string: text + source, with texts that differ only in their digits folded together. */
interface StringsEntry {
  id: number;
  text: string;
  source: string;
  kind: StringKind;
  /** Every distinct text folded into this entry ("code in 55s", "code in 54s"), first seen first. */
  variants: string[];
  hits: StringsHit[];
}

interface StringsModel {
  captures: number;
  rows: number;
  screens: StringsScreen[];
  entries: StringsEntry[];
}

interface StringsViewState {
  mode: 'string' | 'screen';
  sel: number | null;
  screen: number;
  /** Index into the selected entry's shown hits (see shownHits). */
  hit: number;
  kinds: Record<StringKind, boolean>;
  vis: StringVisibility;
}

const STRING_KINDS: Array<{ kind: StringKind; label: string; hint: string }> = [
  { kind: 'copy', label: 'Copy', hint: 'Text the screen shows the user' },
  { kind: 'changing', label: 'Changing', hint: 'Clocks, amounts, counters: text whose digits change between screens' },
  { kind: 'a11y', label: 'Accessibility', hint: 'Role names, hints and actions that only assistive tech reads out' },
];

const DEFAULT_STRING_KINDS: Record<StringKind, boolean> = { copy: true, changing: false, a11y: false };

const STRING_VISIBILITY: Array<{ vis: StringVisibility; label: string; hint: string }> = [
  { vis: 'visible', label: 'Visible', hint: 'On screen when captured' },
  { vis: 'hidden', label: 'Not visible', hint: 'In the view tree but scrolled away or hidden when captured' },
  { vis: 'all', label: 'All', hint: 'Every string in the view tree' },
];

const DEFAULT_STRING_VISIBILITY: StringVisibility = 'visible';

// Sources that describe an element to assistive tech rather than being text drawn on screen: an
// icon's content description, a state or role read aloud, a tooltip shown only on long-press, the
// label another element lends by reference. `hint` is not here: on Android it is the placeholder an
// empty field draws, and an iOS accessibility hint arrives as `help`.
const A11Y_SOURCES = new Set(['contentDescription', 'state', 'tooltip', 'labeledBy', 'roleDescription', 'paneTitle', 'help', 'customAction']);

const basename = (s: string) => s.slice(s.lastIndexOf('/') + 1);

/** A capture's strings as one comparable value: two captures with the same value show the same screen. */
const contentKey = (line: VisibleStringsScreen) => JSON.stringify([
  line.deviceWidth, line.deviceHeight,
  line.strings.map((s) => [s.text, s.source, s.bounds || null, s.visible]),
]);

/**
 * The tab's model. A capture that shows exactly what an earlier one did folds into that screen, so
 * each screen appears once with every capture that showed it. `shots` is the session's screenshot
 * map (only its keys are read); `shotSteps` maps a screenshot file, or the id of a capture with no
 * screenshot, to where the Timeline shows it.
 */
function buildStringsModel(
  lines: VisibleStringsScreen[] | null | undefined,
  shots: Record<string, unknown> | null | undefined,
  shotSteps: Record<string, { step: number; kid: number | null }> = {},
): StringsModel {
  const shotKeys = Object.keys(shots || {});
  const byBase = new Map<string, string>();
  for (const k of shotKeys) if (!byBase.has(basename(k))) byBase.set(basename(k), k);
  // Lines written before `screenshot` existed named the capture by its image, so an image-shaped
  // captureId is still read as the screenshot.
  const screenshotOf = (line: VisibleStringsScreen): string | null =>
    line.screenshot || (/\.(png|webp|jpe?g|gif)$/i.test(line.captureId || '') ? line.captureId : null);
  const shotFor = (line: VisibleStringsScreen): string | null => {
    for (const name of [screenshotOf(line), line.captureUrl]) {
      if (!name) continue;
      if (shots && Object.prototype.hasOwnProperty.call(shots, name)) return name;
      const hit = byBase.get(basename(name));
      if (hit) return hit;
    }
    return null;
  };

  const screens: StringsScreen[] = [];
  const screenOfContent = new Map<string, StringsScreen>();
  const perScreen: VisibleStringEntry[][] = [];
  let rows = 0;
  (lines || []).forEach((line, i) => {
    rows += line.strings.length;
    if (!line.strings.length) return;
    const key = contentKey(line);
    const earlier = screenOfContent.get(key);
    const atMs = parseLogTimestamp(line.timestamp);
    if (earlier) {
      earlier.captures.push(i + 1);
      if (!shotFor(line) && atMs != null) {
        earlier.frameTimes.push({ captureId: line.captureId || null, atMs, at: (line.captureId && shotSteps[line.captureId]) || null });
      }
      // A screen first seen without a screenshot shows, and is cropped from, the first one a later
      // look at it has, rather than a frame from the recording.
      if (!earlier.shot) {
        const later = shotFor(line);
        if (later) {
          earlier.shot = later;
          earlier.screenshot = screenshotOf(line);
          earlier.at = earlier.at || shotSteps[later] || (earlier.screenshot && shotSteps[earlier.screenshot]) || null;
        }
      }
      return;
    }
    const shot = shotFor(line);
    const screenshot = screenshotOf(line);
    const at = (shot && shotSteps[shot]) || (screenshot && shotSteps[screenshot]) || (line.captureId && shotSteps[line.captureId]) || null;
    const screen: StringsScreen = {
      id: screens.length,
      captures: [i + 1],
      captureId: line.captureId || null,
      screenshot,
      shot,
      action: line.action || null,
      w: line.deviceWidth,
      h: line.deviceHeight,
      at,
      atMs,
      frameTimes: !shot && atMs != null ? [{ captureId: line.captureId || null, atMs, at }] : [],
    };
    screens.push(screen);
    perScreen.push(line.strings);
    screenOfContent.set(key, screen);
  });

  // Texts that differ only in their digits fold into one entry only across screens: a countdown
  // ticking between captures is one string, but "Table 1" and "Table 2" side by side are two. So a
  // text joins the entry that already holds it, else one of its digit-shape that has not appeared
  // on this screen yet — the one whose last box sits nearest, since a ticking label keeps its place
  // — else starts its own.
  const centre = (b: [number, number, number, number] | null | undefined) => (b ? [(b[0] + b[2]) / 2, (b[1] + b[3]) / 2] : null);
  const distance = (e: StringsEntry, b: [number, number, number, number] | null | undefined) => {
    const a = centre(e.hits[e.hits.length - 1].bounds), c = centre(b);
    return a && c ? Math.hypot(a[0] - c[0], a[1] - c[1]) : Number.POSITIVE_INFINITY;
  };
  const entries: StringsEntry[] = [];
  const byKey = new Map<string, StringsEntry[]>();
  screens.forEach((screen, i) => {
    for (const s of perScreen[i]) {
      const key = `${s.source}\u0000${s.text.replace(/\d+/g, '#')}`;
      const family = byKey.get(key) || [];
      byKey.set(key, family);
      let entry = family.find((e) => e.variants.indexOf(s.text) >= 0)
        || family.filter((e) => !e.hits.some((h) => h.screen === screen.id))
          .reduce<StringsEntry | undefined>((best, e) => (!best || distance(e, s.bounds) < distance(best, s.bounds) ? e : best), undefined);
      if (!entry) {
        entry = { id: entries.length, text: s.text, source: s.source, kind: 'copy', variants: [], hits: [] };
        family.push(entry);
        entries.push(entry);
      }
      if (entry.variants.indexOf(s.text) < 0) entry.variants.push(s.text);
      entry.hits.push({ screen: screen.id, text: s.text, bounds: s.bounds || null, visible: s.visible !== false });
    }
  });
  for (const e of entries) {
    e.kind = A11Y_SOURCES.has(e.source) ? 'a11y'
      : e.variants.length > 1 || e.variants.some(looksVolatile) ? 'changing'
      : 'copy';
  }
  return { captures: (lines || []).length, rows, screens, entries };
}

/**
 * The selection carried from one model to its rebuild. A live update can insert an earlier capture,
 * which renumbers screens and entries, so the selected string is found again by its source and
 * text and the selected screen by its screenshot; either falls back to its old number when the
 * rebuild no longer has it, and the next render re-resolves that against the chips.
 */
/**
 * Every capture of a screen with no screenshot that may have a frame saved from the recording, first
 * one first. They all showed the same thing, so whichever has one will do.
 */
function stillCaptureIds(screen: StringsScreen): string[] {
  if (screen.shot) return [];
  const ids = [screen.captureId, ...screen.frameTimes.map((f) => f.captureId)];
  return [...new Set(ids.filter((id): id is string => Boolean(id)))];
}

function carryStringsSelection(prev: StringsModel, next: StringsModel, state: StringsViewState): Pick<StringsViewState, 'sel' | 'screen' | 'hit'> {
  const screenKey = (m: StringsModel, id: number) => {
    const s = m.screens.find((x) => x.id === id);
    return s && s.captureId;
  };
  const oldScreenKey = screenKey(prev, state.screen);
  const screen = oldScreenKey ? next.screens.find((s) => s.captureId === oldScreenKey) : undefined;
  const oldSel = state.sel != null ? prev.entries[state.sel] : undefined;
  const sel = oldSel ? next.entries.find((e) => e.source === oldSel.source && e.variants.indexOf(oldSel.text) >= 0) : undefined;
  let hit = state.hit;
  const oldHit = oldSel ? shownHits(oldSel, state.vis)[state.hit] : undefined;
  if (sel && oldHit) {
    const hitScreen = screenKey(prev, oldHit.screen);
    const at = shownHits(sel, state.vis).findIndex((h) => h.text === oldHit.text && screenKey(next, h.screen) === hitScreen);
    if (at >= 0) hit = at;
  }
  return { sel: sel ? sel.id : state.sel, screen: screen ? screen.id : state.screen, hit };
}

/** The places an entry appeared that the visibility filter keeps. */
function shownHits(entry: StringsEntry, vis: StringVisibility): StringsHit[] {
  return vis === 'all' ? entry.hits : entry.hits.filter((h) => h.visible === (vis === 'visible'));
}

/** The entries the list shows under the current chips (the text filter is applied in place). */
function visibleStringEntries(model: StringsModel, state: StringsViewState): StringsEntry[] {
  const on = model.entries.filter((e) => state.kinds[e.kind] && shownHits(e, state.vis).length);
  return state.mode === 'screen' ? on.filter((e) => shownHits(e, state.vis).some((h) => h.screen === state.screen)) : on;
}

/**
 * The selection the tab renders: the state's own when it still names a shown entry and screen,
 * otherwise the first of each — so toggling a chip that hides the selected string lands on the
 * next one rather than on nothing.
 */
function resolveStringsSelection(model: StringsModel, state: StringsViewState): StringsViewState {
  const next = { ...state, kinds: { ...state.kinds } };
  if (!model.screens.some((s) => s.id === next.screen)) next.screen = model.screens.length ? model.screens[0].id : 0;
  const shown = visibleStringEntries(model, next);
  if (next.sel == null || !shown.some((e) => e.id === next.sel)) {
    next.sel = next.mode === 'string' && shown.length ? shown[0].id : null;
    next.hit = 0;
  }
  const sel = next.sel != null ? model.entries[next.sel] : null;
  if (sel) {
    const hits = shownHits(sel, next.vis);
    if (next.mode === 'screen') {
      const at = hits.findIndex((h) => h.screen === next.screen);
      next.hit = at >= 0 ? at : 0;
    } else if (next.hit >= hits.length) {
      next.hit = 0;
    }
  }
  return next;
}

const pct = (v: number, span: number) => `${span > 0 ? ((v / span) * 100).toFixed(3) : 0}%`;

const CROP_W = 120;
const CROP_H = 24;

/**
 * The string's box cut out of its screenshot, scaled to the list's crop column: the screenshot
 * is a background sized to the whole screen and shifted so only the box shows. The image itself
 * comes from the screen's class (see cropRules), so a crop is a few numbers, not another copy of it.
 */
function cropHtml(screen: StringsScreen, h: StringsHit | undefined): string {
  if (!h || !h.bounds || !(screen.w > 0) || !(screen.h > 0)) return '';
  const [l, t, r, b] = h.bounds;
  const bw = Math.max(1, r - l), bh = Math.max(1, b - t);
  // Fitted to the row's height, and clipped at the column's width from the start: a full-width
  // label fitted both ways would shrink to a sliver nobody can read.
  const k = CROP_H / bh;
  const px = (v: number) => `${v.toFixed(1)}px`;
  return `<span class="strcrop strsh-${screen.id}" aria-hidden="true" style="width:${px(Math.min(bw * k, CROP_W))};height:${px(CROP_H)};background-size:${px(screen.w * k)} ${px(screen.h * k)};background-position:${px(-l * k)} ${px(-t * k)}"></span>`;
}

/**
 * The screenshot source each crop can cut from, by screen. A source that could end the `url("…")`
 * of its rule early is left out, and that screen's strings get no crop.
 */
function cropSources(screens: StringsScreen[], imageOf: (screen: StringsScreen) => string): Map<number, string> {
  const out = new Map<number, string>();
  for (const s of screens) {
    const src = imageOf(s).replace(/[\r\n]/g, '');
    if (src && !/["\\<>\s]/.test(src)) out.set(s.id, src);
  }
  return out;
}

/**
 * One rule per screenshot. A screenshot is often an inline data URL hundreds of KB long, so it is
 * written once here rather than on every row that shows a piece of it.
 */
function cropRules(sources: Map<number, string>, ids: Iterable<number> = sources.keys()): string {
  return Array.from(ids).map((id) => `.strsh-${id}{background-image:url("${sources.get(id)}")}`).join('');
}

/**
 * Every screen's crop source and the stylesheet that carries them, worked out once per model. A
 * page that mounts `css` itself (see `stringsTabHtml`) doesn't re-send every screenshot with each
 * render.
 */
interface StringCrops {
  sources: Map<number, string>;
  css: string;
}

function stringCrops(
  model: StringsModel,
  shotSrc: (key: string) => string,
  frameFor: (screen: StringsScreen) => string | null | undefined = () => null,
): StringCrops {
  const sources = cropSources(model.screens, (s) => (s.shot ? shotSrc(s.shot) : frameFor(s) || ''));
  return { sources, css: cropRules(sources) };
}

/**
 * The page's filter row, list, filmstrip, screenshot with boxes, and details. `frameFor` gives a
 * screen with no screenshot the frame taken from the run's recording at that capture's time: its
 * image source, `undefined` while it is still being taken, or null when there is none.
 */
function stringsTabHtml(
  model: StringsModel,
  state: StringsViewState,
  query: string,
  shotSrc: (key: string) => string,
  esc: (s: unknown) => string,
  frameFor: (screen: StringsScreen) => string | null | undefined = () => null,
  // The crops worked out ahead; `mounted` when the page already carries their stylesheet, else
  // the rules the rows use are written into the markup.
  crops: StringCrops & { mounted?: boolean } = stringCrops(model, shotSrc, frameFor),
): string {
  if (!model.entries.length) return `<div class="empty">This run recorded no strings.</div>`;
  const frameOf = (s: StringsScreen) => (s.shot ? null : frameFor(s));
  const imageOf = (s: StringsScreen) => (s.shot ? shotSrc(s.shot) : frameOf(s) || '');
  // Each count is what the list would hold with that button on, the other control left as it is.
  const kindCounts = Object.fromEntries(STRING_KINDS.map(({ kind }) => [kind, model.entries.filter((e) => e.kind === kind && shownHits(e, state.vis).length).length]));
  const visCounts = Object.fromEntries(STRING_VISIBILITY.map(({ vis }) => [vis, model.entries.filter((e) => state.kinds[e.kind] && shownHits(e, vis).length).length]));
  const visButtons = STRING_VISIBILITY.map(({ vis, label, hint }) => `<button type="button" class="evchip${state.vis === vis ? ' on' : ''}" data-str-vis="${vis}" aria-pressed="${state.vis === vis}" title="${esc(hint)}">${esc(label)} <span class="c">${visCounts[vis]}</span></button>`).join('');
  const chips = STRING_KINDS.map(({ kind, label, hint }) => `<button type="button" class="evchip strchip k-${kind}${state.kinds[kind] ? ' on' : ''}" data-str-kind="${kind}" aria-pressed="${state.kinds[kind]}" title="${esc(hint)}"><i></i>${esc(label)} <span class="c">${kindCounts[kind]}</span></button>`).join('');
  const modes = [['string', 'By string'], ['screen', 'By screen']].map(([m, label]) => `<button type="button" class="evchip${state.mode === m ? ' on' : ''}" data-str-mode="${m}" aria-pressed="${state.mode === m}">${label}</button>`).join('');
  const tools = `<div class="lfilter strtools"><input id="strq" type="search" placeholder="Filter strings…" autocomplete="off" value="${esc(query)}" /><span class="strmodes strvis" role="group" aria-label="Visibility">${visButtons}</span>${chips}<span class="strmodes">${modes}</span><span class="count" id="strcount"></span></div>`;

  const shown = visibleStringEntries(model, state);
  const sel = state.sel != null ? model.entries[state.sel] : null;
  const selHitsAll = sel ? shownHits(sel, state.vis) : [];
  // The crop shows the string where it is in view: on the screen picked in By screen, else the
  // first place it appeared with a box and a screenshot. Only a place it was ON SCREEN counts: a
  // crop is what the device drew there, so an off-screen box would cut out blank and a covered one
  // would cut out whatever covers it. The column is there only when some row has one.
  const { sources } = crops;
  const usedShots = new Set<number>();
  const cropFor = (e: StringsEntry) => {
    const hits = shownHits(e, state.vis).filter((x) => x.visible && x.bounds && sources.has(x.screen));
    // By screen shows the selected screen, so a crop from any other would be a different picture.
    const h = state.mode === 'screen' ? hits.find((x) => x.screen === state.screen) : hits[0];
    if (!h) return '';
    usedShots.add(h.screen);
    return cropHtml(model.screens[h.screen], h);
  };
  const rowCrops = new Map(shown.map((e) => [e.id, cropFor(e)]));
  const withCrops = usedShots.size > 0;
  const rows = shown.map((e) => {
    const n = new Set(shownHits(e, state.vis).map((h) => h.screen)).size;
    const where = state.mode === 'screen' ? esc(e.source) : `${esc(e.source)} · ${n} screen${n === 1 ? '' : 's'}`;
    const extra = e.variants.length > 1 ? ` <span class="strvar">+${e.variants.length - 1}</span>` : '';
    return `<button type="button" class="strrow k-${e.kind}${sel && sel.id === e.id ? ' sel' : ''}" data-str-id="${e.id}" data-str-q="${esc(e.variants.join('\n').toLowerCase())}"><span class="strbar"></span>${withCrops ? `<span class="strcropcol">${rowCrops.get(e.id)}</span>` : ''}<span class="strtext">${esc(e.text)}${extra}</span><span class="strmeta">${where}</span></button>`;
  }).join('');
  const list = `<div class="strlist" id="strlist">${rows || `<div class="empty">No strings match these filters.</div>`}</div>`;

  const screenLabel = (s: StringsScreen) => `Screen ${s.id + 1}${s.action ? ` · ${s.action}` : ''}`;
  const thumb = (s: StringsScreen, attrs: string, on: boolean) => {
    const src = imageOf(s);
    const none = frameOf(s) === undefined ? 'Loading frame' : 'No screenshot';
    return `<button type="button" class="strthumb${on ? ' on' : ''}" ${attrs} title="${esc(screenLabel(s))}">${src ? `<img alt="" loading="lazy" src="${esc(src)}" />` : `<span class="strnoshot">${none}</span>`}<span class="strthumblabel">${esc(screenLabel(s))}</span></button>`;
  };
  const strip = state.mode === 'string'
    ? selHitsAll.map((h, i) => thumb(model.screens[h.screen], `data-str-hit="${i}"`, i === state.hit)).join('')
    : model.screens.map((s) => thumb(s, `data-str-screen="${s.id}"`, s.id === state.screen)).join('');

  const screen = state.mode === 'string'
    ? (selHitsAll[state.hit] ? model.screens[selHitsAll[state.hit].screen] : null)
    : model.screens[state.screen] || null;
  let stage = `<div class="empty">Pick a string to see where it appeared.</div>`;
  if (screen) {
    const selHits = selHitsAll.filter((h, i) => h.screen === screen.id && (state.mode === 'screen' || i === state.hit));
    // `bounds` are corners; the box is placed by its top-left and sized by the span between them.
    // Another string's box is a button, so it can be reached and picked from the keyboard; the
    // selected string's is only a marker.
    const box = (h: StringsHit, cls: string, tag: 'button' | 'div', attrs: string) => {
      const [l, t, r, b] = h.bounds!;
      return `<${tag}${tag === 'button' ? ' type="button"' : ''} class="strbox ${cls}${h.visible ? '' : ' off'}" ${attrs} style="left:${pct(l, screen.w)};top:${pct(t, screen.h)};width:${pct(r - l, screen.w)};height:${pct(b - t, screen.h)}"></${tag}>`;
    };
    const others = shown.filter((e) => !sel || e.id !== sel.id).flatMap((e) => shownHits(e, state.vis)
      .filter((h) => h.screen === screen.id && h.bounds)
      .map((h) => box(h, `k-${e.kind}`, 'button', `data-str-id="${e.id}" title="${esc(h.text)}" aria-label="${esc(`Select “${h.text}”`)}"`)));
    const mine = selHits.filter((h) => h.bounds).map((h) => box(h, 'sel', 'div', `title="${esc(h.text)}"`));
    const src = imageOf(screen);
    const frame = frameOf(screen);
    // A frame is a still of the recording, not what the device captured: it can be softer, and
    // it is labelled so the two are never confused.
    const fromVideo = frame ? `<span class="strfromvideo">From video</span>` : '';
    const missing = frame === undefined ? 'Taking this frame from the recording…'
      : screen.screenshot ? `Screenshot ${esc(screen.screenshot)} is not in this report` : 'No screenshot for this capture';
    const shotShape = `--ar:${(screen.w || 9) / (screen.h || 16)};aspect-ratio:${screen.w || 9} / ${screen.h || 16}`;
    const shot = src
      ? `<div class="strshot" style="${shotShape}"><img alt="${esc(screenLabel(screen))}" src="${esc(src)}" />${fromVideo}<div class="strboxes">${others.join('')}${mine.join('')}</div></div>`
      : `<div class="strshot strshotmissing" style="${shotShape}"><span>${missing}</span><div class="strboxes">${others.join('')}${mine.join('')}</div></div>`;
    const b = selHits[0] && selHits[0].bounds;
    const open = screen.at ? `<button type="button" class="btn strgo" data-lightbox-step="${screen.at.step}"${screen.at.kid != null ? ` data-lightbox-kid="${screen.at.kid}"` : ''}>Open in Timeline</button>` : '';
    const facts: Array<[string, string]> = [['Screen', esc(screenLabel(screen))]];
    if (screen.captures.length > 1) facts.push(['Captured', esc(`${screen.captures.length} times (captures ${screen.captures.join(', ')} of ${model.captures})`)]);
    facts.push(['Screenshot', screen.screenshot ? esc(screen.screenshot) : frame ? 'None — this is a frame from the recording' : 'None']);
    if (b) facts.push(['Box', esc(`(${b[0]}, ${b[1]}) to (${b[2]}, ${b[3]}), ${b[2] - b[0]}×${b[3] - b[1]} of ${screen.w}×${screen.h}`)]);
    if (selHits.length && !selHits[0].visible) facts.push(['Visible', 'No — in the view tree but not on screen']);
    if (sel && sel.variants.length > 1) facts.push(['Variants', sel.variants.map(esc).join('<br>')]);
    if (sel) facts.push(['Seen on', esc(selHitsAll.map((h) => `screen ${h.screen + 1}`).join(', '))]);
    const kindLabel = sel ? (STRING_KINDS.find((k) => k.kind === sel.kind) || STRING_KINDS[0]).label : '';
    const head = sel
      ? `<h3 class="strtitle">${esc(sel.text)}</h3><p class="strtags"><span class="strtag k-${sel.kind}"><i></i>${esc(kindLabel)}</span><span class="strtag">${esc(sel.source)}</span></p>`
      : `<h3 class="strtitle">${esc(screenLabel(screen))}</h3><p class="strhint">Hover a box to read it; click it to select the string.</p>`;
    stage = `<div class="strmain">${shot}<div class="strinfo">${head}<table class="strfacts">${facts.map(([k, v]) => `<tr><td>${k}</td><td>${v}</td></tr>`).join('')}</table>${open}</div></div>`;
  }
  const rules = crops.mounted ? '' : cropRules(sources, usedShots);
  return `${rules ? `<style>${rules}</style>` : ''}${tools}<div class="strlayout">${list}<div class="strstage"><div class="strstrip">${strip}</div>${stage}</div></div>`;
}

export {
  buildStringsModel, carryStringsSelection, DEFAULT_STRING_KINDS, DEFAULT_STRING_VISIBILITY, resolveStringsSelection, shownHits, stillCaptureIds, STRING_KINDS, STRING_VISIBILITY,
  stringCrops, stringsTabHtml, visibleStringEntries,
  type StringCrops, type StringKind, type StringsEntry, type StringsModel, type StringsScreen, type StringsViewState, type StringVisibility,
};
