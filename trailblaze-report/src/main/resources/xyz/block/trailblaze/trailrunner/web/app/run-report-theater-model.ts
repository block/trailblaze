// The Theater's model: a run's flat trace turned into the story its stage plays, and the pure
// reads over it (who is on air, which line runs now, where the clock fast-forwards). No DOM, so
// the view stays thin and every rule here is testable on its own.
//
// Every time in a story is milliseconds from its start. The payload's own times are epoch ms on
// the capture clock; `originMs` is the epoch the story's zero sits at.
import { declaredTrailSteps } from './run-report-extract';
import { childInstants, failureAnchorIndex } from './run-report-trace-model';

export interface YamlSpan {
  /** 1-based, inclusive. */
  from: number;
  to: number;
}

/** What an action drew on the screen: a tap, a swipe, or the point an assertion found. */
export interface TheaterMark {
  kind: 'tap' | 'swipe' | 'check';
  t: number;
  /** Device pixels, in a screen of `dw` x `dh`. A swipe runs from (x, y) to (x2, y2). */
  x: number;
  y: number;
  x2?: number;
  y2?: number;
  dw: number;
  dh: number;
  ok: boolean;
  /** For a check, the element it found, in the same device pixels. */
  box?: MarkBox;
}

export interface TheaterAction {
  t: number;
  durMs: number;
  device: string;
  tool: string;
  label: string;
  /** The tool's own summary or the model's full reasoning, for a tooltip. */
  detail?: string;
  ok: boolean;
  /** Something the AI did while recovering a failed recorded step. */
  healed?: boolean;
  mark?: TheaterMark;
  yaml?: YamlSpan;
  url?: string;
}

export interface TheaterFrame {
  /** When the screenshot was taken. */
  t: number;
  file: string;
}

export interface TheaterRecording {
  /** Index into the host's clips. */
  clip: number;
  startMs: number;
  endMs: number;
}

export interface TheaterDevice {
  /** The trail's own name for the device, e.g. "seller"; "device" when the run names none. */
  name: string;
  classifier?: string;
  platform?: string;
  screen?: { w: number; h: number };
  recordings: TheaterRecording[];
  /** Screenshots, by capture time, for a device with no recording that plays. */
  frames: TheaterFrame[];
  /** Time this device led a chapter. */
  activeMs: number;
}

export type ChapterKind = 'trailhead' | 'step' | 'verify' | 'teardown';
export type Outcome = 'passed' | 'failed' | 'healed' | 'unknown';

export interface TheaterChapter {
  index: number;
  kind: ChapterKind;
  text: string;
  headline: string;
  startMs: number;
  endMs: number;
  device: string;
  devices: string[];
  outcome: Outcome;
  /** The recorded step failed and the AI took over. */
  selfHeal?: true;
  yaml?: YamlSpan;
  actions: TheaterAction[];
}

export interface TheaterHandoff {
  variable: string;
  from: string;
  to: string;
  producedMs: number;
  consumedMs: number;
  fromChapter: number;
  toChapter: number;
}

export interface TheaterStory {
  /** The epoch ms the story's zero sits at. */
  originMs: number;
  endMs: number;
  title: string;
  outcome: Outcome;
  devices: TheaterDevice[];
  chapters: TheaterChapter[];
  handoffs: TheaterHandoff[];
  yaml?: { text: string; source: 'original' | 'recording' };
}

export interface TheaterInput {
  trace: TraceStep[];
  meta: RunMeta;
  recordingYaml?: string | null;
  originalYaml?: string | null;
  clips: Array<{ startMs: number; endMs: number; device?: string | null }>;
  /** Whether a screenshot's image is in the report; one that isn't is skipped so the last one holds. */
  hasShot?: (file: string) => boolean;
}

// ---- Building the story ------------------------------------------------------------------------

const normalized = (text: string): string => text.trim().replace(/\s+/g, ' ');

/** Tools that are driver plumbing, never a step of the trail's story. A capture or mark makes one
 * worth showing anyway (a device's own `back` is logged through one of these). */
/** The device a dispatch handed over to. Payloads predating `switchTo` name it only in a successful
 * `switchDevice` dispatch's own arguments. */
const switchOf = (kid: TraceChild): string | null => kid.switchTo
  || (kid.label === 'switchDevice' && kid.ok !== false && /^\s*name\s*:\s*["']?([^"'\n]+)/m.exec(kid.args || '')?.[1]?.trim())
  || null;

const PLUMBING = /^(android_|mobile_|ios_|findSelectorMatches$|exec$|block_runIf$|runIf$|wait$|objectiveStatus$|switchDevice$|_)/;

/** The step's first sentence, without the device it names. */
export function headline(text: string): string {
  const first = normalized(text)
    .replace(/^On the [^,]+,\s*/, '')
    .split(/(?<=[.,])\s/)[0]
    .replace(/[.,]+$/, '')
    .replace(/\s*\(\{\{\w+\}\}\)/, '');
  return first.slice(0, 1).toUpperCase() + first.slice(1);
}

export function humanizeTool(name: string): string {
  const words = name
    .replace(/^((web|android|mobile|ios)_)+/, '')
    .replace(/([a-z])([A-Z])/g, '$1 $2')
    .replaceAll('_', ' ')
    .split(/\s+/)
    .filter(Boolean)
    .map((word) => (word === word.toUpperCase() && word !== word.toLowerCase() ? word : word.toLowerCase()));
  const text = words.join(' ');
  return text.slice(0, 1).toUpperCase() + text.slice(1);
}

/** The visible text a selector looked for, from the tool's arguments or its summary. */
function selectorText(row: TraceStep): string | null {
  const source = `${row.args || ''}\n${row.tool || ''}`;
  const found = /(?:textRegex|contentDescriptionRegex|accessibilityTextRegex|text)["']?\s*:\s*["']?\^?([^\n"'$,}\\]+)/.exec(source);
  const text = found?.[1]?.trim().replace(/\([^)]*\)\?/g, '');
  return text || null;
}

const SHORT_NOTE_CHARS = 80;

/** What a script line says: a short reason as written, else what the tool did to what. */
export function actionLabel(row: TraceStep): { label: string; detail?: string } {
  const note = (row.note || '').trim();
  const text = selectorText(row);
  const name = row.label || '';
  const verb = /^(tap|click)/i.test(name) ? 'Tap' : /^(assert|verify|web_verify|waitUntil)/i.test(name) ? 'See' : null;
  const derived = verb && text ? `${verb} "${text}"` : null;
  const plain = /^(mobile_maestro|_)/.test(name) && row.tool ? row.tool.replace(/^./, (c) => c.toUpperCase()) : humanizeTool(name);
  const detail = note || (row.tool || undefined);
  if (note && note.length <= SHORT_NOTE_CHARS) return { label: note, ...(detail ? { detail } : {}) };
  return { label: derived || plain || note, ...(detail ? { detail } : {}) };
}

/** A tool row the run logged only to hand over to the one that does the work: it carries the same
 * reasoning, ran no time, and captured nothing. */
function isDispatch(row: TraceStep, next: TraceStep | undefined): boolean {
  return !row.ms && !row.screenshotFile && !row.mark && !!row.note && !!next && !next.objective && next.note === row.note && next.label !== row.label;
}

function isLlmRow(row: TraceStep): boolean {
  return row.llm != null || !!row.decision || (row.tool === 'agent step' && !row.objective);
}

/** The line ranges of the trail's own YAML: the trailhead's block and each `- step:` / `- verify:`
 * item, found by indentation so the authored bytes can be shown as written. */
export interface YamlItem {
  kind: 'step' | 'verify';
  text: string;
  span: YamlSpan;
}

export interface YamlMap {
  lines: string[];
  trailhead?: YamlSpan;
  items: YamlItem[];
}

const ROOT_KEY = /^(?:-\s+)?[A-Za-z_][\w-]*\s*:/;

export function locateYaml(yaml: string): YamlMap {
  const lines = yaml.replace(/\r\n/g, '\n').split('\n');
  // 0-based [start, end) to a 1-based inclusive span, without the blank lines that trail a block.
  const span = (start: number, end: number): YamlSpan => {
    let last = end;
    while (last > start + 1 && !lines[last - 1].trim()) last--;
    return { from: start + 1, to: last };
  };
  const regionEnd = (start: number) => {
    let end = start + 1;
    while (end < lines.length && !ROOT_KEY.test(lines[end])) end++;
    return end;
  };
  const trailheadAt = lines.findIndex((line) => /^(?:-\s+)?trailhead\s*:/.test(line));
  const trailhead = trailheadAt >= 0 ? span(trailheadAt, regionEnd(trailheadAt)) : undefined;
  const item = /^(\s*)-\s+(step|verify)\s*:/;
  const found: Array<{ kind: 'step' | 'verify'; start: number; end: number }> = [];
  lines.forEach((line, start) => {
    if (!/^(?:-\s+)?(?:trail|prompts)\s*:/.test(line)) return;
    const end = regionEnd(start);
    const region: Array<{ at: number; indent: number; kind: 'step' | 'verify' }> = [];
    for (let at = start + 1; at < end; at++) {
      const match = item.exec(lines[at]);
      if (match) region.push({ at, indent: match[1].length, kind: match[2] as 'step' | 'verify' });
    }
    if (!region.length) return;
    // The shallowest items are the steps; a `step`-named argument nested under a recording is not one.
    const depth = Math.min(...region.map((entry) => entry.indent));
    const steps = region.filter((entry) => entry.indent === depth);
    steps.forEach((entry, k) => found.push({ kind: entry.kind, start: entry.at, end: steps[k + 1]?.at ?? end }));
  });
  const texts = declaredTrailSteps(yaml);
  const aligned = texts.length === found.length;
  return {
    lines,
    ...(trailhead ? { trailhead } : {}),
    items: found.map((entry, k) => ({ kind: entry.kind, text: aligned ? texts[k] : '', span: span(entry.start, entry.end) })),
  };
}

/** The YAML span each action came from: the next line inside the step's block that names its
 * tool, so repeated tools walk down the block in order. */
function spanActions(lines: string[], within: YamlSpan, actions: TheaterAction[]): void {
  const keys: Array<{ name: string; line: number; indent: number }> = [];
  for (let line = within.from; line <= within.to; line++) {
    const match = /^(\s*)(-\s+)?([A-Za-z_]\w*)\s*:/.exec(lines[line - 1] ?? '');
    if (match) keys.push({ name: match[3], line, indent: match[1].length + (match[2] ? match[2].length : 0) });
  }
  let cursor = 0;
  for (const action of actions) {
    const at = keys.findIndex((key, k) => k >= cursor && key.name === action.tool);
    if (at < 0) continue;
    cursor = at + 1;
    const key = keys[at];
    const next = keys.slice(at + 1).find((other) => other.indent <= key.indent);
    let to = next ? next.line - 1 : within.to;
    while (to > key.line && !(lines[to - 1] ?? '').trim()) to--;
    action.yaml = { from: key.line, to };
  }
}

const TOKEN = /\{\{\s*([\w.-]+)\s*\}\}|\$\{([\w.-]+)\}/g;

/** A record's picture: its screenshot, else the still the report saved for its first capture. */
const pictureOf = (record: { screenshotFile?: string | null; captureIds?: string[] }): string | null =>
  record.screenshotFile || (record.captureIds?.[0] ? `${record.captureIds[0]}.webp` : null);

/** The largest of `values`, at least `floor`. A loop, not `Math.max(...values)`: a long run has more
 * timestamps than a call can take as arguments. */
function largest(floor: number, values: number[]): number {
  let high = floor;
  for (const value of values) if (value > high) high = value;
  return high;
}

function smallest(values: number[]): number {
  let low = Infinity;
  for (const value of values) if (value < low) low = value;
  return low;
}

export function buildTheaterStory(input: TheaterInput): TheaterStory {
  const { trace, meta, clips } = input;
  const stamps = [
    ...trace.map((row) => row.ts).filter((ts): ts is number => ts != null),
    ...clips.map((clip) => clip.startMs),
  ];
  const origin = stamps.length ? smallest(stamps) : 0;
  const rel = (ms: number) => Math.max(0, ms - origin);

  // Devices, in first-seen order, the start device first.
  const traced: string[] = [];
  for (const row of trace) {
    // A handover folded under a batch row names a device the row's own stamp (the last) may not.
    for (const kid of row.children || []) {
      const to = switchOf(kid);
      if (to && !traced.includes(to)) traced.push(to);
    }
    if (row.device && !traced.includes(row.device)) traced.push(row.device);
  }
  const start = clips[0]?.device || traced[0] || 'device';
  const names = [start, ...[...traced, ...clips.map((clip) => clip.device || '')].filter((name, k, all) => name && name !== start && all.indexOf(name) === k)];
  const devices: TheaterDevice[] = names.map((name) => ({ name, recordings: [], frames: [], activeMs: 0 }));
  const deviceOf = (name: string) => devices.find((device) => device.name === name)!;
  clips.forEach((clip, index) => {
    deviceOf(clip.device || start).recordings.push({ clip: index, startMs: rel(clip.startMs), endMs: rel(clip.endMs) });
  });
  for (const device of devices) device.recordings.sort((a, b) => a.startMs - b.startMs);
  if (devices.length === 1) {
    devices[0].classifier = meta.deviceClassifier || undefined;
    devices[0].platform = meta.platform || undefined;
  }
  // The run names only the device it started on. Any other is known by the tools that drove it:
  // a binding driven by browser or desktop-window tools is shown as a window, not as a phone.
  const surfaceOf = (tool?: string | null) => (/^web_/.test(tool || '') ? 'web' : /^playwright_desktop_/.test(tool || '') ? 'desktop' : undefined);
  for (const row of devices.length > 1 ? trace : []) {
    const device = row.device ? deviceOf(row.device) : undefined;
    const surface = surfaceOf(row.label) ?? (row.children || []).map((kid) => surfaceOf(kid.label)).find(Boolean);
    if (device && surface && !device.platform) device.platform = surface;
  }

  interface Draft {
    row: TraceStep;
    /** The same-label objective the agent retried a failed self-heal as; its result is the step's. */
    retry?: TraceStep;
    startMs: number;
    actions: TheaterAction[];
    visited: string[];
    startDevice: string;
    healing: boolean;
  }
  const drafts: Draft[] = [];
  let open: Draft | undefined;
  let current = start;
  let clock = origin;
  let lastEnd = 0;
  const handoffUses: Array<{ variable: string; device: string; chapter: number; t: number }> = [];
  const produced = new Map<string, { chapter: number; device: string; t: number }>();
  const handoffs: TheaterHandoff[] = [];

  const addFrame = (device: TheaterDevice, file: string | null | undefined, taken: number) => {
    if (file && (input.hasShot?.(file) ?? true) && !device.frames.some((frame) => frame.file === file)) device.frames.push({ t: rel(taken), file });
  };
  const toMark = (mark: ActionMark, t: number): TheaterMark | undefined => {
    if (mark.kind === 'swipe' && mark.x1 != null && mark.y1 != null && mark.x2 != null && mark.y2 != null) {
      return { kind: 'swipe', t, x: mark.x1, y: mark.y1, x2: mark.x2, y2: mark.y2, dw: mark.dw, dh: mark.dh, ok: true };
    }
    // A not-visible check points at the screen's center, which says nothing about where it looked.
    if (mark.kind === 'assert' && mark.visible === false) return undefined;
    if (mark.x != null && mark.y != null) return { kind: mark.kind === 'assert' ? 'check' : 'tap', t, x: mark.x, y: mark.y, dw: mark.dw, dh: mark.dh, ok: mark.ok !== false, ...(mark.kind === 'assert' && mark.box ? { box: { ...mark.box } } : {}) };
    return undefined;
  };
  const sameMark = (a: ActionMark, b: ActionMark | null | undefined) => !!b && a.kind === b.kind && a.x === b.x && a.y === b.y && a.x1 === b.x1 && a.y1 === b.y1;
  // The row's overlay is a child's own only when the same driver cue drew it: two taps on one spot
  // match by geometry alone, and the cue instant is what tells them apart.
  const drawnByKid = (row: TraceStep, kid: TraceChild) => !!row.mark && sameMark(row.mark, kid.mark) && (row.markTs ?? row.ts) === (kid.markTs ?? kid.ts);
  // One script line, from a row or one of the dispatches folded under it.
  const addAction = (draft: Draft, source: TraceStep, ts: number, mark: ActionMark | null | undefined, markTs: number) => {
    const t = rel(ts);
    const { label, detail } = actionLabel(source);
    const action: TheaterAction = {
      t,
      durMs: Math.max(0, source.ms || 0),
      device: current,
      tool: source.label,
      label,
      ...(detail ? { detail } : {}),
      ok: source.ok !== false,
      ...(draft.healing ? { healed: true } : {}),
    };
    const drawn = mark ? toMark(mark, Math.max(t, rel(markTs))) : undefined;
    if (drawn) action.mark = drawn;
    if (/^web_navigate$/.test(source.label)) {
      const url = /^\s*url\s*:\s*["']?([^"'\n]+)/m.exec(source.args || '')?.[1];
      if (url) action.url = url;
    }
    draft.actions.push(action);
    if (!draft.visited.includes(current)) draft.visited.push(current);
    // A value read on one device and used on another is a handoff; the trace carries the tokens.
    const chapter = drafts.indexOf(draft);
    const args = source.args || '';
    const variable = /^\s*variable\s*:\s*["']?([\w.-]+)/m.exec(args)?.[1];
    for (const match of args.matchAll(TOKEN)) {
      handoffUses.push({ variable: (match[1] ?? match[2]).replace(/^memory\./, ''), device: current, chapter, t });
    }
    if (variable && source.ok !== false) produced.set(variable, { chapter, device: current, t: t + (source.ms || 0) });
    // A use is judged against the producer known at that moment, in trace order.
    while (handoffUses.length) {
      const use = handoffUses.shift()!;
      const from = produced.get(use.variable);
      if (!from || from.device === use.device) continue;
      if (handoffs.some((h) => h.variable === use.variable && h.to === use.device && h.fromChapter === from.chapter)) continue;
      handoffs.push({
        variable: use.variable,
        from: from.device,
        to: use.device,
        producedMs: from.t,
        consumedMs: use.t,
        fromChapter: from.chapter,
        toChapter: use.chapter,
      });
    }
  };
  const visibleTool = (label: string, drew: boolean) => !PLUMBING.test(label || '') || drew;

  // Which chapter each row belongs to, for blaming a failed run's anchor row on its chapter.
  const chapterOf: Array<Draft | undefined> = [];
  // The last chapter that is not the Teardown: the run's end-of-run rows belong to it.
  let beforeTeardown: Draft | undefined;

  trace.forEach((row, at) => {
    // The device the row's own record ran on. A batch that handed over mid-way is stamped with its
    // LAST destination, so its own record (and anything captured before the first handover) ran on
    // the device active before it — or on the destination, when the row IS the handover.
    const kids = (row.children || []).filter((kid) => kid.ms != null);
    // Older payloads carry no per-dispatch timestamp, so those are spread inside the row's span.
    const instants = childInstants(row);
    const instantOf = new Map((row.children || []).map((kid, k) => [kid, instants[k]]));
    const handsOver = kids.some((kid) => switchOf(kid));
    const own = handsOver
      ? (row.label === 'switchDevice' && /^\s*name\s*:\s*["']?([^"'\n]+)/m.exec(row.args || '')?.[1]) || current
      : row.device || current;
    // Each executed dispatch's device, walking its handovers in order (a failed one never moved).
    let walking = own;
    const kidDevice = kids.map((kid) => (walking = switchOf(kid) || walking));
    current = own;
    const ts = row.ts ?? clock;
    clock = Math.max(clock, ts);
    const t = rel(ts);
    const device = deviceOf(current);
    // A frame the batch's row only inherited from a dispatch after a handover is that dispatch's.
    const picture = pictureOf(row);
    const frameFromKid = handsOver && !!picture && kids.some((kid) => pictureOf(kid) === picture);
    if (picture && !frameFromKid) addFrame(device, picture, row.shotEndTs ?? (row.ts != null ? row.ts + (row.ms || 0) : ts));
    // A mark the row only inherited from a dispatch on another device says nothing of this one's screen.
    const ownMark = row.mark && !kids.some((kid, k) => kidDevice[k] !== own && drawnByKid(row, kid)) ? row.mark : null;
    const seen = ownMark ? { w: ownMark.dw, h: ownMark.dh } : row.viewport ? { w: row.viewport.w, h: row.viewport.h } : null;
    if (seen && seen.w > 0 && seen.h > 0 && !device.screen) device.screen = seen;
    lastEnd = Math.max(lastEnd, rel(row.shotEndTs ?? ts + (row.ms || 0)));
    // A folded tool batch keeps each later dispatch under the row; a null `ms` is a declared
    // dispatch that never ran, so it has no screen or moment of its own.
    kids.forEach((kid, k) => {
      const kidTs = instantOf.get(kid) ?? ts;
      const onDevice = deviceOf(kidDevice[k]);
      addFrame(onDevice, pictureOf(kid), kid.shotEndTs ?? (kid.ts != null ? kid.ts + (kid.ms || 0) : kidTs));
      if (kid.mark && kid.mark.dw > 0 && kid.mark.dh > 0 && !onDevice.screen) onDevice.screen = { w: kid.mark.dw, h: kid.mark.dh };
      lastEnd = Math.max(lastEnd, rel(kid.shotEndTs ?? kidTs + (kid.ms || 0)));
    });
    const settle = () => {
      current = kidDevice.at(-1) ?? current;
    };
    if (row.objective) {
      // A failed self-heal retried under the same objective is still that one authored step.
      const last = drafts.at(-1);
      const base = last?.row;
      if (last && base && base.selfHeal && !base.ok && !(last.retry && last.retry.ok) && !row.trailhead && !row.teardown && !base.trailhead && !base.teardown && base.label === row.label) {
        last.retry = row;
        last.healing = true;
        open = last;
        chapterOf[at] = last;
        settle();
        return;
      }
      if (open && !open.row.teardown) beforeTeardown = open;
      open = { row, startMs: t, actions: [], visited: [current], startDevice: current, healing: false };
      drafts.push(open);
      chapterOf[at] = open;
      settle();
      return;
    }
    // The run's error and failure snapshot are logged after teardown but belong to the trail.
    chapterOf[at] = row.endOfRun && open?.row.teardown ? beforeTeardown : open;
    if (!open || row.terminal || isLlmRow(row)) return settle();
    if (row.selfHealSource) open.healing = true;
    const visible = visibleTool(row.label, !!row.mark || !!pictureOf(row));
    if (visible && !isDispatch(row, trace[at + 1])) {
      // The row's own overlay is its first dispatch's; a child drawn at the same cue owns it.
      const mark = kids.some((kid) => drawnByKid(row, kid)) ? null : row.mark;
      addAction(open, row, ts, mark, row.markTs ?? row.shotTs ?? ts);
    }
    kids.forEach((kid, k) => {
      current = kidDevice[k];
      if (!visibleTool(kid.label, !!kid.mark || !!pictureOf(kid))) return;
      const kidTs = instantOf.get(kid) ?? ts;
      const source = { ...row, label: kid.label, tool: kid.tool, note: kid.note ?? null, ms: kid.ms || 0, ok: kid.ok !== false, args: kid.args ?? null, mark: kid.mark ?? null, screenshotFile: kid.screenshotFile ?? null, children: undefined } as TraceStep;
      addAction(open!, source, kidTs, kid.mark, kid.markTs ?? kidTs);
    });
    settle();
  });
  for (const device of devices) device.frames.sort((a, b) => a.t - b.t);

  // A tool exception or run-level error can fail the run before its objective logs a completion, so
  // the objective keeps ok=true. When no step failed itself, blame the chapter the trace's shared
  // failure anchor sits in (a crash fails a run whose steps passed, so it names none). Failed
  // self-heal attempts are tolerated by their step, so they cannot stand in for the run's failure.
  const status = (meta.status || '').toLowerCase();
  const runFailed = status === 'failed' || status === 'error';
  const stepFailed = drafts.some((draft) => !draft.row.teardown && (draft.retry ?? draft.row).ok === false);
  const anchor = runFailed && !stepFailed && meta.failureCode !== 'app_crashed'
    ? failureAnchorIndex(trace.map((row) => (row.selfHealSource || (row.objective && row.selfHeal) ? { ...row, ok: true } : row)))
    : -1;
  const failedDraft = anchor >= 0 ? chapterOf[anchor] : undefined;

  const yamlText = input.originalYaml || input.recordingYaml || null;
  const yamlMap = yamlText ? locateYaml(yamlText) : null;
  let cursor = 0;
  let ordinal = 0;
  const chapters: TheaterChapter[] = drafts.map((draft, index) => {
    const row = draft.row;
    const kind: ChapterKind = row.trailhead ? 'trailhead' : row.teardown ? 'teardown' : 'step';
    const next = drafts[index + 1];
    const actionEnd = largest(draft.startMs, draft.actions.map((action) => action.t + action.durMs));
    const endMs = next ? Math.max(draft.startMs, next.startMs) : Math.max(actionEnd, lastEnd);
    const counts = new Map<string, number>();
    for (const action of draft.actions) counts.set(action.device, (counts.get(action.device) ?? 0) + 1);
    const lead = [...counts].reduce<[string, number] | undefined>((best, entry) => (!best || entry[1] > best[1] ? entry : best), undefined)?.[0]
      ?? draft.visited.at(-1) ?? draft.startDevice;
    const touched = [...new Set([lead, ...draft.visited, ...draft.actions.map((action) => action.device)])];
    const final = draft.retry ?? row;
    const outcome: Outcome = final.ok === false || draft === failedDraft ? 'failed' : row.selfHeal || final.selfHeal ? 'healed' : 'passed';
    let chapterKind: ChapterKind = kind;
    let yaml: YamlSpan | undefined;
    if (yamlMap) {
      if (kind === 'trailhead') yaml = yamlMap.trailhead;
      else if (kind === 'step') {
        const wanted = normalized(row.label).slice(0, 100);
        let hit = yamlMap.items.findIndex((item, k) => k >= cursor && !!item.text && normalized(item.text).slice(0, 100) === wanted);
        if (hit < 0 && yamlMap.items.every((item) => !item.text) && yamlMap.items[ordinal]) hit = ordinal;
        if (hit >= 0) {
          cursor = hit + 1;
          yaml = yamlMap.items[hit].span;
          chapterKind = yamlMap.items[hit].kind;
        }
        ordinal++;
      }
      if (yaml) spanActions(yamlMap.lines, yaml, draft.actions);
    }
    return {
      index,
      kind: chapterKind,
      text: row.label,
      headline: headline(row.label),
      startMs: draft.startMs,
      endMs,
      device: lead,
      devices: touched,
      outcome,
      ...(row.selfHeal || final.selfHeal ? { selfHeal: true as const } : {}),
      ...(yaml ? { yaml } : {}),
      actions: draft.actions,
    };
  });
  for (const chapter of chapters) deviceOf(chapter.device).activeMs += chapter.endMs - chapter.startMs;

  const ends = [
    ...chapters.map((chapter) => chapter.endMs),
    ...devices.flatMap((device) => [...device.recordings.map((recording) => recording.endMs), ...device.frames.map((frame) => frame.t)]),
  ];
  return {
    originMs: origin,
    endMs: largest(1, ends),
    title: meta.title || meta.trailId || 'Run',
    outcome: status === 'passed' || status === 'success' ? (meta.selfHeal ? 'healed' : 'passed') : runFailed ? 'failed' : 'unknown',
    devices,
    chapters,
    handoffs,
    ...(yamlText ? { yaml: { text: yamlText, source: input.originalYaml ? ('original' as const) : ('recording' as const) } } : {}),
  };
}

// ---- Formatting ---------------------------------------------------------------------------------

export function clock(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, '0')}`;
}

/** "4s", "11s", "1:02". */
export function span(ms: number): string {
  return ms >= 60_000 ? clock(ms) : `${Math.round(ms / 1000)}s`;
}

export function deviceTitle(name: string): string {
  return name.slice(0, 1).toUpperCase() + name.slice(1);
}

/** Text with each `{{variable}}` split out; values are never resolved, so secrets stay out. */
export function labelParts(text: string): Array<string | { variable: string }> {
  const parts: Array<string | { variable: string }> = [];
  let last = 0;
  for (const match of text.matchAll(/\{\{\s*(\w+)\s*\}\}/g)) {
    if (match.index > last) parts.push(text.slice(last, match.index));
    parts.push({ variable: match[1] });
    last = match.index + match[0].length;
  }
  if (last < text.length) parts.push(text.slice(last));
  return parts;
}

// ---- Reads over a story ---------------------------------------------------------------------------

/** The trailhead is setup, step 0; a teardown is not a step of the trail either. */
const counted = (chapter: TheaterChapter) => chapter.kind !== 'trailhead' && chapter.kind !== 'teardown';

export const stepNumber = (chapters: TheaterChapter[], index: number): string => {
  const chapter = chapters[index];
  if (chapter?.kind === 'trailhead') return '0';
  if (chapter?.kind === 'teardown') return '';
  return String(chapters.slice(0, index + 1).filter(counted).length);
};

export const stepCount = (chapters: TheaterChapter[]): number => chapters.filter(counted).length;

export function chapterIndexAt(chapters: TheaterChapter[], t: number): number {
  let hit = 0;
  for (let index = 0; index < chapters.length; index++) {
    if (chapters[index].startMs <= t) hit = index;
    else break;
  }
  return hit;
}

/** Where a step jump parks: the end of its last action, when its screenshot is taken, so the
 * screen has settled. A step's start can still be mid-transition. */
export function settledMs(chapter: TheaterChapter): number {
  const last = chapter.actions.at(-1);
  return last ? Math.max(chapter.startMs, Math.min(last.t + last.durMs, chapter.endMs - 1)) : chapter.startMs;
}

/** The step a jump from `t` lands on: the next one, or back to the one played well past its
 * settled moment, else the one before. */
export function chapterStep(chapters: TheaterChapter[], t: number, direction: -1 | 1): number {
  const index = chapterIndexAt(chapters, t);
  const current = chapters[index];
  if (direction > 0) return Math.min(chapters.length - 1, index + 1);
  return current && t - settledMs(current) > 1500 ? index : Math.max(0, index - 1);
}

/** The action most recently started at `t`, or undefined before the first. */
export function actionAt(chapter: TheaterChapter, t: number): TheaterAction | undefined {
  let hit: TheaterAction | undefined;
  for (const action of chapter.actions) if (action.t <= t) hit = action;
  return hit;
}

/** A mark stays on screen this long past its action, so a quick one still reads. */
export const MARK_HOLD_MS = 1000;

/** Each device's actions by start, for `markAt`. */
export function deviceActions(story: TheaterStory): Map<string, TheaterAction[]> {
  const byDevice = new Map<string, TheaterAction[]>();
  for (const action of story.chapters.flatMap((chapter) => chapter.actions).sort((a, b) => a.t - b.t)) {
    if (!byDevice.has(action.device)) byDevice.set(action.device, []);
    byDevice.get(action.device)!.push(action);
  }
  return byDevice;
}

/** The action whose mark a device's screen shows at `t`: its latest action's, from the moment it
 * happened until a beat after the action ends. */
export function markAt(actions: TheaterAction[], t: number): TheaterAction | undefined {
  let last: TheaterAction | undefined;
  for (const action of actions) {
    if (action.t > t) break;
    last = action;
  }
  const mark = last?.mark;
  return last && mark && mark.t <= t && t < Math.max(mark.t, last.t + last.durMs) + MARK_HOLD_MS ? last : undefined;
}

/** Consecutive runs of one label collapse into a line, so five identical waits read as one line
 * counted five times. */
export interface ScriptLine {
  label: string;
  detail?: string;
  t: number;
  /** Where the line seeks: its first mark, so the stage shows it, else its start. */
  at: number;
  endMs: number;
  count: number;
  ok: boolean;
  actions: TheaterAction[];
}

export function scriptLines(chapter: TheaterChapter): ScriptLine[] {
  const lines: ScriptLine[] = [];
  for (const action of chapter.actions) {
    const last = lines.at(-1);
    const end = action.t + action.durMs;
    if (last && last.label === action.label) {
      last.count++;
      last.endMs = Math.max(last.endMs, end);
      last.ok &&= action.ok;
      last.actions.push(action);
    } else {
      lines.push({ label: action.label, ...(action.detail ? { detail: action.detail } : {}), t: action.t, at: action.mark?.t ?? action.t, endMs: end, count: 1, ok: action.ok, actions: [action] });
    }
  }
  return lines;
}

/** A run of consecutive chapters led by one device. */
export interface Scene {
  device: string;
  chapters: number[];
  startMs: number;
  endMs: number;
}

export function scenes(chapters: TheaterChapter[]): Scene[] {
  const out: Scene[] = [];
  for (const chapter of chapters) {
    const last = out.at(-1);
    if (last && last.device === chapter.device) {
      last.chapters.push(chapter.index);
      last.endMs = chapter.endMs;
    } else {
      out.push({ device: chapter.device, chapters: [chapter.index], startMs: chapter.startMs, endMs: chapter.endMs });
    }
  }
  return out;
}

export interface Act {
  title: string;
  chapters: number[];
}

/** The trailhead's chapters, then the trail's. A run without a trailhead is one trail. */
export function acts(story: TheaterStory): Act[] {
  const trailhead = story.chapters.filter((chapter) => chapter.kind === 'trailhead').map((chapter) => chapter.index);
  const trail = story.chapters.filter((chapter) => chapter.kind !== 'trailhead').map((chapter) => chapter.index);
  return [
    { title: 'Trailhead', chapters: trailhead },
    { title: 'Trail', chapters: trail },
  ].filter((act) => act.chapters.length > 0);
}

/** No cut sooner than this after the last one, so a blink of a chapter on another device doesn't
 * flash the program. */
export const MIN_SHOT_MS = 1200;

export interface Cut {
  t: number;
  device: string;
}

/** The program's edit while playing: each chapter's lead device, held at least MIN_SHOT_MS. A
 * chapter that ends before its shot could start is left on the wall. */
export function cameraCuts(chapters: TheaterChapter[]): Cut[] {
  const cuts: Cut[] = [];
  for (const chapter of chapters) {
    const last = cuts.at(-1);
    if (last?.device === chapter.device) continue;
    const t = last ? Math.max(chapter.startMs, last.t + MIN_SHOT_MS) : 0;
    if (last && t >= chapter.endMs && chapter !== chapters.at(-1)) continue;
    cuts.push({ t, device: chapter.device });
  }
  return cuts;
}

export function cutAt(cuts: Cut[], t: number): string | undefined {
  let device = cuts[0]?.device;
  for (const cut of cuts) {
    if (cut.t <= t) device = cut.device;
    else break;
  }
  return device;
}

export interface Inset {
  device: string;
  reason: 'handoff' | 'meanwhile';
  variable?: string;
  /** The handoff's sender, which its arrows run from. */
  from?: string;
}

const HANDOFF_LEAD_MS = 3000;
const HANDOFF_HOLD_MS = 3000;
/** A handoff whose value waits longer than this between devices is shown only as its use comes
 * up, not across everything in between. */
const HANDOFF_LONG_MS = 15_000;
const HANDOFF_UPCOMING_MS = 5000;
const WAIT_MS = 2000;
const RESPONSE_MS = 5000;

/** The other device worth watching beside the program: the other end of a handoff it is part of,
 * around the moment it happens, else, while it waits, the device whose chapter it is waiting on. */
export function insetAt(story: TheaterStory, t: number, onAir: string): Inset | undefined {
  if (story.devices.length < 2) return undefined;
  for (const handoff of story.handoffs) {
    const from = handoff.consumedMs - handoff.producedMs > HANDOFF_LONG_MS
      ? handoff.consumedMs - HANDOFF_UPCOMING_MS
      : handoff.producedMs - HANDOFF_LEAD_MS;
    if (t < from || t > handoff.consumedMs + HANDOFF_HOLD_MS) continue;
    // Its arrows join the program, so a third device on air must not stand in for an end.
    if (onAir !== handoff.from && onAir !== handoff.to) continue;
    const device = onAir === handoff.from ? handoff.to : handoff.from;
    return { device, reason: 'handoff', variable: handoff.variable, from: handoff.from };
  }
  const index = chapterIndexAt(story.chapters, t);
  const chapter = story.chapters[index];
  const before = story.chapters[index - 1];
  const waiting = chapter?.actions.find((action) => action.device === onAir && action.durMs >= WAIT_MS && action.t <= t && t < action.t + action.durMs);
  if (waiting && before && before.device !== onAir && waiting.t - before.endMs <= RESPONSE_MS) {
    return { device: before.device, reason: 'meanwhile' };
  }
  return undefined;
}

/** The address a browser device last navigated to, from its navigate actions. */
export function browserHost(story: TheaterStory, device: string, t: number): string | undefined {
  let url: string | undefined;
  for (const chapter of story.chapters) {
    for (const action of chapter.actions) {
      if (action.device === device && action.tool === 'web_navigate' && action.t <= t && action.url) url = action.url.replace(/^https?:\/\//, '');
    }
  }
  return url && `https://${url}`;
}

/** The recording a device shows at story time `t`: the last one started by then, or its first
 * before any has. A device rebound mid-run has one per binding. */
export function recordingAt(device: TheaterDevice, t: number): TheaterRecording | undefined {
  return device.recordings.filter((recording) => recording.startMs <= t).at(-1) ?? device.recordings[0];
}

/** The screenshot a device shows at `t`: the latest taken by then, else its first. */
export function frameAt(device: TheaterDevice, t: number): TheaterFrame | undefined {
  let hit: TheaterFrame | undefined;
  for (const frame of device.frames) {
    if (frame.t <= t) hit = frame;
    else break;
  }
  return hit ?? device.frames[0];
}

/** True when `t` is past the recording's end and a screenshot taken after it, by `t`, is up: a feed
 * that died early is followed by the screenshots that show what happened next, each in its turn. */
export function screenshotOutlasts(recording: TheaterRecording | undefined, frame: TheaterFrame | undefined, t: number): boolean {
  return !!recording && !!frame && t > recording.endMs && frame.t > recording.endMs && frame.t <= t;
}

// ---- The trail's YAML as a script ---------------------------------------------------------------

export type YamlLineState = 'now' | 'done' | 'next' | 'rest';

/** The latest action with a YAML span started by `t`. */
export function yamlCue(chapter: TheaterChapter | undefined, t: number): TheaterAction | undefined {
  let hit: TheaterAction | undefined;
  for (const action of chapter?.actions ?? []) if (action.yaml && action.t <= t) hit = action;
  return hit;
}

const within = (range: YamlSpan | undefined, line: number) => !!range && line >= range.from && line <= range.to;

/** Lines through the furthest one any started action reached, in the chapter. */
export function yamlReached(chapter: TheaterChapter | undefined, cue: TheaterAction | undefined): number {
  if (!chapter?.yaml || !cue) return 0;
  let reached = 0;
  for (const action of chapter.actions) if (action.yaml && action.t <= cue.t) reached = Math.max(reached, action.yaml.to);
  return reached;
}

export function yamlLineState(line: number, chapter: YamlSpan | undefined, cue: YamlSpan | undefined, reached: number): YamlLineState {
  if (within(cue, line)) return 'now';
  if (!within(chapter, line)) return 'rest';
  return line <= reached ? 'done' : 'next';
}

/** Where clicking a line seeks: the first action written on it, else the start of the chapter it
 * belongs to. */
export function yamlSeekTimes(story: TheaterStory): Map<number, number> {
  const times = new Map<number, number>();
  const actions = story.chapters.flatMap((chapter) => chapter.actions).filter((action) => action.yaml).sort((a, b) => a.t - b.t);
  for (const action of actions) {
    for (let line = action.yaml!.from; line <= action.yaml!.to; line++) if (!times.has(line)) times.set(line, action.t);
  }
  for (const chapter of story.chapters) {
    if (!chapter.yaml) continue;
    for (let line = chapter.yaml.from; line <= chapter.yaml.to; line++) if (!times.has(line)) times.set(line, chapter.startMs);
  }
  return times;
}

export interface YamlSegment {
  text: string;
  type?: 'key' | 'string' | 'number' | 'keyword' | 'comment' | 'punct';
  /** A `{{variable}}` or `${variable}`, or the name a `variable:` key holds. */
  variable?: boolean;
}

/** One YAML line's runs for highlighting: keys, quoted and plain scalars, numbers, comments,
 * with variable references split out of them. Deliberately line-local: a block scalar's body
 * reads as plain text. */
export function yamlSegments(line: string): YamlSegment[] {
  const out: YamlSegment[] = [];
  const push = (text: string, type?: YamlSegment['type']) => { if (text) out.push({ text, ...(type ? { type } : {}) }); };
  const scalar = (text: string, forceVariable: boolean) => {
    if (!text) return;
    const trimmed = text.trim();
    const lead = text.slice(0, text.length - text.trimStart().length);
    push(lead);
    const body = trimmed;
    const type: YamlSegment['type'] = /^["']/.test(body) ? 'string' : /^(true|false|null|~)$/.test(body) ? 'keyword' : /^-?\d+(\.\d+)?$/.test(body) ? 'number' : undefined;
    if (forceVariable && /^[\w.-]+$/.test(body)) {
      out.push({ text: body, ...(type ? { type } : {}), variable: true });
    } else {
      let last = 0;
      for (const match of body.matchAll(/\{\{\s*[\w.-]+\s*\}\}|\$\{[\w.-]+\}/g)) {
        if (match.index > last) push(body.slice(last, match.index), type);
        out.push({ text: match[0], ...(type ? { type } : {}), variable: true });
        last = match.index + match[0].length;
      }
      if (last < body.length) push(body.slice(last), type);
    }
    push(text.slice(lead.length + body.length));
  };
  const comment = /(^|\s)#.*$/.exec(line);
  const code = comment ? line.slice(0, comment.index + comment[1].length) : line;
  const key = /^(\s*(?:-\s+)*)([A-Za-z_][\w.-]*)(\s*:)(\s|$)/.exec(code);
  if (key) {
    push(key[1]);
    push(key[2], 'key');
    push(key[3], 'punct');
    scalar(code.slice(key[0].length - key[4].length), key[2] === 'variable');
  } else {
    const dash = /^(\s*(?:-\s+)+)(.*)$/.exec(code);
    if (dash) {
      push(dash[1]);
      scalar(dash[2], false);
    } else {
      scalar(code, false);
    }
  }
  if (comment) push(line.slice(code.length), 'comment');
  return out;
}

// ---- The clock ----------------------------------------------------------------------------------

export const SPEEDS = [0.5, 1, 2, 4] as const;
export const LAPSE_RATE = 8;
/** Real time stays this long after a moment before the clock speeds up... */
const LAPSE_SETTLE_MS = 1000;
/** ...and resumes this long before the next one. */
const LAPSE_LOOK_MS = 1500;
const LAPSE_MIN_MS = 1000;

/** Story time where nothing starts or ends: no chapter boundary, no action starting or
 * finishing. A device waiting on a spinner is such a stretch, and so are the minutes a device
 * boots before its first chapter. */
export function lapseWindows(story: TheaterStory): Array<[number, number]> {
  const moments = [0, story.endMs];
  for (const chapter of story.chapters) {
    moments.push(chapter.startMs, chapter.endMs);
    for (const action of chapter.actions) moments.push(action.t, action.t + action.durMs);
  }
  moments.sort((a, b) => a - b);
  const windows: Array<[number, number]> = [];
  for (let index = 1; index < moments.length; index++) {
    const from = moments[index - 1] + LAPSE_SETTLE_MS;
    const to = moments[index] - LAPSE_LOOK_MS;
    if (to - from >= LAPSE_MIN_MS) windows.push([from, to]);
  }
  return windows;
}

/** End of the window `t` is in; windows are half-open so a clock held at an end leaves it. */
export function lapseEnd(windows: Array<[number, number]>, t: number): number | undefined {
  let low = 0;
  let high = windows.length - 1;
  while (low <= high) {
    const mid = (low + high) >> 1;
    if (t < windows[mid][0]) high = mid - 1;
    else if (t >= windows[mid][1]) low = mid + 1;
    else return windows[mid][1];
  }
  return undefined;
}

/** Eases the playback rate toward its target: quick to speed up, quicker to slow down, so the
 * clock lands on the next moment at real speed. */
export function easeRate(rate: number, target: number, dtSeconds: number): number {
  const tau = target > rate ? 0.22 : 0.1;
  const next = rate + (target - rate) * (1 - Math.exp(-dtSeconds / tau));
  return Math.abs(next - target) < 0.02 ? target : next;
}

/** One frame of the clock, by the real time it took however late: recordings playing along keep
 * their own clocks through a delayed frame, so a clock that fell behind them would seek them back. */
export function advance(t: number, rate: number, target: number, elapsedSeconds: number): { t: number; rate: number } {
  const next = easeRate(rate, target, elapsedSeconds);
  return { t: t + elapsedSeconds * 1000 * next, rate: next };
}

/** One frame of the story clock. A time-lapse stops at its window's end, so a late frame can't
 * carry the lapse rate past the moment that follows it. */
export function stepClock(
  t: number, rate: number, speed: number, lapse: boolean, windows: Array<[number, number]>, elapsedSeconds: number,
): { t: number; rate: number } {
  const end = lapse ? lapseEnd(windows, t) : undefined;
  const next = advance(t, rate, end === undefined ? speed : Math.max(LAPSE_RATE, speed), elapsedSeconds);
  return end === undefined ? next : { t: Math.min(next.t, end), rate: next.rate };
}

const LAPSE_THRESHOLD = 1.8;

/** A time-lapse is running once the clock outruns the chosen speed well past what recordings
 * play smoothly at; they then hold still and step. */
export const isLapsing = (rate: number, speed: number): boolean => rate > Math.max(speed * 1.5, LAPSE_THRESHOLD);

/** Drift past which a playing recording is seeked back onto the clock. */
const DRIFT_S = 0.25;
/** While time-lapsing, a held frame steps once the clock is this far ahead. */
const LAPSE_STEP_S = 1.5;

export interface VideoState {
  currentTime: number;
  duration: number;
  paused: boolean;
  seeking: boolean;
}

export interface VideoCommand {
  seek?: number;
  play: boolean;
  rate?: number;
}

/** What one recording should do for the clock at `target` seconds into it, running at `rate`
 * with `speed` chosen. `reseek` seeks whatever the drift, since a loop shorter than the drift
 * tolerance would otherwise never pull back. */
export function syncVideo(video: VideoState, target: number, playing: boolean, rate: number, speed: number, reseek = false): VideoCommand {
  const end = Number.isFinite(video.duration) ? Math.max(0, video.duration - 0.05) : Infinity;
  const time = Math.max(0, Math.min(target, end));
  const live = playing && !isLapsing(rate, speed) && target >= 0 && target < end;
  const tolerance = live ? DRIFT_S : playing ? LAPSE_STEP_S : 0.03;
  const seek = !video.seeking && (reseek || Math.abs(video.currentTime - time) > tolerance) ? time : undefined;
  return { ...(seek !== undefined ? { seek } : {}), play: live, ...(live ? { rate } : {}) };
}

/** One frame of a hovered step's preview, which plays its stretch on a loop. */
export function loopClock(
  t: number, rate: number, speed: number, lapse: boolean, windows: Array<[number, number]>, loop: [number, number], elapsedSeconds: number,
): { t: number; rate: number; wrapped?: true } {
  const next = stepClock(t, rate, speed, lapse, windows, elapsedSeconds);
  return next.t >= loop[1] ? { t: loop[0], rate: speed, wrapped: true } : next;
}

/** The story time a loop frame passed over, from `before` up to but not including the returned
 * end. Half-open, so the loop's first moment counts and its end, which starts the next step, doesn't. */
export function loopSpan(before: number, next: { t: number; wrapped?: true }, loop: [number, number]): [number, number] {
  return [before, next.wrapped ? loop[1] : next.t];
}

// ---- Stage geometry -----------------------------------------------------------------------------

/** Frames render at this width and scale into place, so a camera move is one transform. */
export const BASE = 1000;
/** Room each side of a handoff's box for its arrows. */
export const HANDOFF_GAP = 32;

export interface Box {
  x: number;
  y: number;
  w: number;
  h: number;
}

export function fit(box: Box, ratio: number, pad = 0): { x: number; y: number; s: number } {
  const width = Math.max(1, Math.min(box.w - pad * 2, (box.h - pad * 2) / ratio));
  return { x: box.x + (box.w - width) / 2, y: box.y + (box.h - width * ratio) / 2, s: width / BASE };
}

/**
 * Where the on-air screen goes while the inset sits in the zone's bottom-right corner: the strip
 * above it or the strip beside it, whichever shows the screen bigger. A portrait phone fills the
 * strip beside; a landscape screen would be squeezed there and takes the strip above.
 */
export function besideInset(zone: Box, inset: { x: number; y: number }, ratio: number, gap: number): Box {
  const above: Box = { ...zone, h: Math.min(zone.h, inset.y - gap - zone.y) };
  const beside: Box = { ...zone, w: Math.min(zone.w, inset.x - gap - zone.x) };
  return fit(beside, ratio).s > fit(above, ratio).s ? beside : above;
}

const clamp = (value: number, low: number, high: number) => Math.max(low, Math.min(high, value));
const frameBox = (spot: { x: number; y: number; s: number }, ratio: number): Box => ({ x: spot.x, y: spot.y, w: BASE * spot.s, h: BASE * spot.s * ratio });
const point = (x: number, y: number) => `${+x.toFixed(1)} ${+y.toFixed(1)}`;

/** Clear space between an arrow's ends and the boxes it joins. */
const LINK_GAP = 6;
/** Each arm of an arrow's head runs this far back and out, so the tip is square. */
const HEAD = 7;

/** A connector from `from` to `to` that leaves and arrives square to the axis it runs along,
 * stopping short of both and ending in an open chevron. */
function curve(from: { x: number; y: number }, to: { x: number; y: number }, axis: 'x' | 'y'): string {
  const along = (p: { x: number; y: number }, by: number) => (axis === 'x' ? { x: p.x + by, y: p.y } : { x: p.x, y: p.y + by });
  const side = (p: { x: number; y: number }, by: number) => (axis === 'x' ? { x: p.x, y: p.y + by } : { x: p.x + by, y: p.y });
  const dir = Math.sign(axis === 'x' ? to.x - from.x : to.y - from.y) || 1;
  const a = along(from, dir * LINK_GAP);
  const b = along(to, -dir * LINK_GAP);
  const half = ((axis === 'x' ? b.x - a.x : b.y - a.y)) / 2;
  const back = along(b, -dir * HEAD);
  const [left, right] = [side(back, -HEAD), side(back, HEAD)];
  const [c1, c2] = [along(a, half), along(b, -half)];
  return `M${point(a.x, a.y)}C${point(c1.x, c1.y)} ${point(c2.x, c2.y)} ${point(b.x, b.y)}M${point(left.x, left.y)}L${point(b.x, b.y)}L${point(right.x, right.y)}`;
}

/** Sets a handoff's box between the program and the far device's camera, on the side the
 * cameras sit (beside the program, or under it on a narrow panel), and runs one arrow out of the
 * sender into the box and one out of the box into the receiver. `sending` says the camera's
 * device is the sender. */
export function handoffLayout(
  zone: Box,
  size: { w: number; h: number },
  cam: Box,
  ratios: { program: number; cam: number },
  sending: boolean,
): { program: Box; node: Box; links: [string, string] } {
  const below = cam.y >= zone.y + zone.h;
  const camFrame = frameBox(fit(cam, ratios.cam, 6), ratios.cam);
  const camMid = below ? camFrame.x + camFrame.w / 2 : camFrame.y + camFrame.h / 2;
  const program = below ? { ...zone, h: zone.h - size.h - HANDOFF_GAP * 2 } : { ...zone, w: zone.w - size.w - HANDOFF_GAP * 2 };
  const frame = frameBox(fit(program, ratios.program), ratios.program);
  // Centered in the gap the program frame leaves before the camera.
  const node = below
    ? { x: clamp(camMid - size.w / 2, zone.x, zone.x + zone.w - size.w), y: Math.max(frame.y + frame.h + HANDOFF_GAP, (frame.y + frame.h + camFrame.y - size.h) / 2), ...size }
    : { x: Math.max(frame.x + frame.w + HANDOFF_GAP, (frame.x + frame.w + camFrame.x - size.w) / 2), y: clamp(camMid - size.h / 2, zone.y, zone.y + zone.h - size.h), ...size };
  // Arrows meet a frame within its middle half, clear of rounded corners and stands.
  const across = (box: Box, at: number) =>
    below ? clamp(at, box.x + box.w / 4, box.x + box.w * 3 / 4) : clamp(at, box.y + box.h / 4, box.y + box.h * 3 / 4);
  const mid = below ? node.x + node.w / 2 : node.y + node.h / 2;
  const programEnd = below ? { x: across(frame, mid), y: frame.y + frame.h } : { x: frame.x + frame.w, y: across(frame, mid) };
  const camEnd = below ? { x: across(camFrame, mid), y: camFrame.y } : { x: camFrame.x, y: across(camFrame, mid) };
  const nodeIn = below ? { x: mid, y: node.y } : { x: node.x, y: mid };
  const nodeOut = below ? { x: mid, y: node.y + node.h } : { x: node.x + node.w, y: mid };
  const axis = below ? 'y' : 'x';
  return {
    program,
    node,
    links: sending
      ? [curve(camEnd, nodeOut, axis), curve(nodeIn, programEnd, axis)]
      : [curve(programEnd, nodeIn, axis), curve(nodeOut, camEnd, axis)],
  };
}

// ---- The director's state -------------------------------------------------------------------------

export interface Snapshot {
  /** The playhead's chapter and action, which the script follows. */
  chapter: number;
  action?: TheaterAction;
  /** What the stage shows: the playhead's moment, or the script line under the pointer. */
  shown: number;
  shownAction?: TheaterAction;
  previewing: boolean;
  /** The latest action with a YAML span. */
  cue?: TheaterAction;
  onAir: string;
  inset?: Inset;
  playing: boolean;
  lapsing: boolean;
}

export function sameSnapshot(a: Snapshot, b: Snapshot): boolean {
  return a.chapter === b.chapter && a.action === b.action && a.shown === b.shown && a.shownAction === b.shownAction
    && a.previewing === b.previewing && a.cue === b.cue && a.onAir === b.onAir && a.playing === b.playing
    && a.lapsing === b.lapsing && a.inset?.device === b.inset?.device && a.inset?.reason === b.inset?.reason
    && a.inset?.variable === b.inset?.variable && a.inset?.from === b.inset?.from;
}

/** The director's state at clock time `t`. A `preview` time takes over the stage, the way a cut to
 * that moment would while paused, and leaves the playhead's chapter and action for the script. */
export function snapshotAt(
  story: TheaterStory,
  cuts: Cut[],
  clockState: { t: number; playing: boolean; rate: number },
  settings: { speed: number; pinned?: string; grid?: boolean },
  preview?: number,
): Snapshot {
  const { t, playing, rate } = clockState;
  const view = preview ?? t;
  const live = playing && preview === undefined;
  const chapter = chapterIndexAt(story.chapters, t);
  const current = story.chapters[chapter];
  const shown = preview === undefined ? chapter : chapterIndexAt(story.chapters, view);
  const showing = story.chapters[shown];
  // The grid has no camera to hold a shot, so it follows the step the moment it starts.
  const auto = (live && !settings.grid ? cutAt(cuts, view) : showing?.device) ?? story.devices[0]?.name ?? '';
  const onAir = settings.pinned ?? auto;
  return {
    chapter,
    action: current && actionAt(current, t),
    cue: yamlCue(current, t),
    shown,
    shownAction: showing && actionAt(showing, view),
    previewing: preview !== undefined,
    onAir,
    inset: settings.pinned ? undefined : insetAt(story, view, onAir),
    playing,
    lapsing: live && isLapsing(rate, settings.speed),
  };
}
