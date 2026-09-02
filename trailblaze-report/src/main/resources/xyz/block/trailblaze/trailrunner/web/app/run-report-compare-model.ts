// Pure model for the viewer's Compare view: run-vs-run diffs of the tool-call timeline and the
// captured event streams, computed from the viewer's own payload shapes (TraceStep rows and
// EventStream/NetworkEvent side-channels) — no raw session logs required, so it works on any two
// hydrated sessions, including zip-loaded ones.
//
// Ported from the Kotlin `trailblaze report diff` engines (trailblaze-host
// host/golden/SessionToolDiff.kt and SessionEventDiff.kt); the grouping-key detection and the
// alignment semantics are kept identical so both surfaces tell the same story about the same pair
// of runs. The Kotlin lane reads raw logs; this one reads what the payload carries — tool
// arguments arrive as the deterministic trail-YAML `args` string (run-report-payload's
// jsonToYaml), so the argument diff here is line-based over that text rather than a JSON flatten.
//
// DOM-free and bun-tested in run-report-compare-model.test.ts.

// ---------------------------------------------------------------------------
// Which two runs to open on
// ---------------------------------------------------------------------------

/**
 * The pair the Compare view opens on when the address names neither side: the first trail that ran
 * more than once, baseline being its earlier run.
 *
 * Opening on the first two runs in the document instead means a many-trail report opens on two
 * DIFFERENT trails, and every lane below diffs them as confidently as it diffs a repeat — a search
 * trail's `textRegex: 'Pepperoni'` against a navigation trail's `textRegex: 'Search'` renders as a
 * change when in truth nothing changed, because the two runs were never the same test. Preferring a
 * same-trail pair makes the view's first impression one the data actually supports.
 *
 * Falling back to the first two runs is still right when no trail ran twice: there is no same-trail
 * pair to prefer, the reader picks from there, and the cross-trail note explains what they are
 * looking at.
 *
 * @param runs Comparable runs in document order. `trailKey` is the run's trail identity; an empty
 *   key means the run is unidentified and can never establish a same-trail pair.
 */
export function defaultComparePair(runs: Array<{ index: number; trailKey: string }>): [number, number] {
  const byTrail = new Map<string, number[]>();
  runs.forEach((run) => {
    if (!run.trailKey) return;
    byTrail.set(run.trailKey, (byTrail.get(run.trailKey) || []).concat(run.index));
  });
  for (const indexes of byTrail.values()) {
    if (indexes.length > 1) return [indexes[0], indexes[1]];
  }
  return [runs[0] ? runs[0].index : 0, runs[1] ? runs[1].index : 1];
}

// ---------------------------------------------------------------------------
// Tool-call lane
// ---------------------------------------------------------------------------

// Run-scoped argument fields excluded from comparison: LLM narration (`reasoning`, `reason`)
// differs every agent-driven run even when the behavior is identical, and element `ref` handles
// are assigned per run. Matches SessionToolDiff.IGNORED_ARG_KEYS.
const IGNORED_ARG_LINE = /^\s*(?:- )?(?:reasoning|reason|ref):(?:\s|$)/;
const MAX_REPORTED_CHANGES = 12;
const MAX_LINE_LENGTH = 110;

export type CompareToolStatus = 'same' | 'args_changed' | 'outcome_changed' | 'baseline_only' | 'current_only';

/**
 * One line of a unified diff: removed from the baseline, added by the current run, or context.
 *
 * The same shape the event-stream lane emits, and for the same reason: a reader scanning a column
 * of `−`/`+` gutters takes in a whole call at a glance, where a column of prose sentences — some
 * arrow-shaped, some signed, some bare — has to be read one line at a time to find out which of
 * the two runs each fact belongs to.
 *
 * `hi` is the [from, to) span of `text` that actually differs from this line's pair — the deeper
 * highlight GitHub paints on the changed word, so a long line whose key repeats verbatim doesn't
 * have to be char-scanned to find the one value that moved. `gap` marks a fold of matching context
 * lines ("⋯ 4 matching argument lines"), rendered dim rather than as content.
 */
export type CompareDiffLine = { sign: '-' | '+' | ' '; text: string; hi?: [number, number]; gap?: boolean };

export type CompareToolRow = {
  toolName: string;
  status: CompareToolStatus;
  /** The call's argument diff, capped at MAX_REPORTED_CHANGES lines. */
  changes: CompareDiffLine[];
  /** Trace indexes (TraceStep.i) for deep-linking into each run's timeline. */
  baselineStep: number | null;
  currentStep: number | null;
};

export type CompareToolResult = {
  rows: CompareToolRow[];
  sameCount: number;
  argsChangedCount: number;
  outcomeChangedCount: number;
  baselineOnlyCount: number;
  currentOnlyCount: number;
  summary: string;
};

type ToolRow = { i: number; label: string; tool: string; ok: boolean; lines: string[] };

// The rows of a trace that represent executed calls: not objective headers, not terminal status
// rows, not LLM request rows (`llm` marks a transcript row; older rows without the index still
// carry the `llm · <model>` / `agent step` tool summary).
export function toolTimelineOf(trace: TraceStep[] | null | undefined): TraceStep[] {
  return (trace || []).filter((t) => t && t.label && !t.objective && !t.terminal
    && t.llm == null && t.tool !== 'agent step' && !/^llm · /.test(t.tool || ''));
}

// A row's comparable argument lines: the trail-YAML `args` body (head `- toolName:` line dropped,
// body dedented), blank and run-scoped lines removed. jsonToYaml escapes newlines inside values,
// so every argument is exactly one line.
export function comparableArgLines(args: string | null | undefined): string[] {
  if (!args) return [];
  const lines = String(args).split('\n');
  const body = lines.length && lines[0].startsWith('- ') ? lines.slice(1) : lines;
  return body
    .map((line) => (line.startsWith('    ') ? line.slice(4) : line))
    .filter((line) => line.trim() !== '' && !IGNORED_ARG_LINE.test(line));
}

// Leading indentation survives the crop: it is YAML structure — `id: search` nested under
// `selector:` is a different fact flush-left — and the diff renders monospace, where indent reads.
const cropLine = (line: string): string => {
  const t = line.replace(/\s+$/, '');
  return t.length > MAX_LINE_LENGTH ? `${t.slice(0, MAX_LINE_LENGTH)}…` : t;
};

// A line's key part (indentation + optional dash + key), used to pair a removed line with the
// added line that replaced it so the two print adjacently as `− key: a` / `+ key: b`.
const lineKey = (line: string): string | null => {
  const m = /^(\s*(?:- )?[^:]+):/.exec(line);
  return m ? m[1] : null;
};

// Multiset difference a − b, preserving a's order.
function subtractLines(a: string[], b: string[]): string[] {
  const counts = new Map<string, number>();
  b.forEach((line) => counts.set(line, (counts.get(line) || 0) + 1));
  return a.filter((line) => {
    const n = counts.get(line) || 0;
    if (n > 0) { counts.set(line, n - 1); return false; }
    return true;
  });
}

const del = (line: string): CompareDiffLine => ({ sign: '-', text: cropLine(line) });
const add = (line: string): CompareDiffLine => ({ sign: '+', text: cropLine(line) });
const ctx = (line: string): CompareDiffLine => ({ sign: ' ', text: cropLine(line) });
const capLines = (lines: CompareDiffLine[]): CompareDiffLine[] => (lines.length > MAX_REPORTED_CHANGES
  ? lines.slice(0, MAX_REPORTED_CHANGES).concat({ sign: ' ', text: `… ${lines.length - MAX_REPORTED_CHANGES} more line(s)`, gap: true })
  : lines);

// What ends a token in the text these spans mark: argument lines (`text: Coffee`) and event
// summaries (`payment_started  amount_cents=1450`).
const TOKEN_BOUNDARY = new Set([' ', '\t', '"', "'", '=', ':', ',', '(', ')', '[', ']', '{', '}', '/']);

// Past this width a token stops reading as one value, so the exact offset is worth more than the
// whole: in a 300-character blob, WHICH characters moved is the only thing the mark can add.
const MAX_HIGHLIGHT_TOKEN = 40;

const tokenSpan = (text: string, [start, end]: [number, number]): [number, number] => {
  let from = start;
  let to = end;
  while (from > 0 && !TOKEN_BOUNDARY.has(text[from - 1])) from--;
  while (to < text.length && !TOKEN_BOUNDARY.has(text[to])) to++;
  return [from, to];
};

/**
 * Marks the differing span of a replaced-value pair: the common prefix and suffix of the two lines
 * are what repeats (the key, shared punctuation), and what's left between them is the change
 * itself. Skipped when a side's middle is empty (a pure insertion has nothing to mark on the
 * shorter line) or when the lines share nothing (the whole line IS the change, and painting all of
 * it says less than the plain −/+ already does).
 *
 * The span then grows to its token's edges, because the characters two values happen to share are
 * not the same thing as the part that changed: `amount_cents=1450` against `amount_cents=1625`
 * shares the leading `1`, and marking `450` against `625` reads as though the value were elided
 * rather than replaced. Marking `1450` against `1625` names the two values.
 */
export function highlightSpans(x: string, y: string): [[number, number] | undefined, [number, number] | undefined] {
  let prefix = 0;
  while (prefix < x.length && prefix < y.length && x[prefix] === y[prefix]) prefix++;
  let suffix = 0;
  while (suffix < x.length - prefix && suffix < y.length - prefix && x[x.length - 1 - suffix] === y[y.length - 1 - suffix]) suffix++;
  if (prefix === 0 && suffix === 0) return [undefined, undefined];
  // An astral character (an emoji, say) is two code units that only mean anything together, and the
  // renderer slices these offsets into separate text and <mark> nodes. A cut between the two halves
  // would leave each node holding half a character, which draws as a replacement glyph instead of
  // the character that changed — so nudge either boundary off a split pair. Both nudges only ever
  // widen the span, which marks at worst one shared character too many.
  const highSurrogate = (code: number) => code >= 0xd800 && code <= 0xdbff;
  while (prefix > 0 && highSurrogate(x.charCodeAt(prefix - 1))) prefix--;
  while (suffix > 0 && (highSurrogate(x.charCodeAt(x.length - suffix - 1)) || highSurrogate(y.charCodeAt(y.length - suffix - 1)))) suffix--;
  const spanOf = (text: string): [number, number] | undefined => (prefix < text.length - suffix ? [prefix, text.length - suffix] : undefined);
  const [xSpan, ySpan] = [spanOf(x), spanOf(y)];
  const [xToken, yToken] = [xSpan && tokenSpan(x, xSpan), ySpan && tokenSpan(y, ySpan)];
  // Both sides fall back together: one line marking a whole value while the other marks a fragment
  // of one would read as two different kinds of change.
  const oversized = (span?: [number, number]) => !!span && span[1] - span[0] > MAX_HIGHLIGHT_TOKEN;
  if (oversized(xToken) || oversized(yToken)) return [xSpan, ySpan];
  return [xToken, yToken];
}

function highlightPair(removedLine: CompareDiffLine, addedLine: CompareDiffLine): [CompareDiffLine, CompareDiffLine] {
  const [xSpan, ySpan] = highlightSpans(removedLine.text, addedLine.text);
  return [xSpan ? { ...removedLine, hi: xSpan } : removedLine, ySpan ? { ...addedLine, hi: ySpan } : addedLine];
}

// How many unchanged argument lines stay visible on each side of a change. Matching lines further
// out fold into a dim "⋯ N matching argument lines" — present enough to say the rest of the call
// agreed, without the agreement drowning the change.
const CONTEXT_RADIUS = 2;

function collapseContext(lines: CompareDiffLine[]): CompareDiffLine[] {
  const keep = new Set<number>();
  lines.forEach((line, at) => {
    if (line.sign === ' ') return;
    for (let d = -CONTEXT_RADIUS; d <= CONTEXT_RADIUS; d++) {
      if (at + d >= 0 && at + d < lines.length) keep.add(at + d);
    }
  });
  const out: CompareDiffLine[] = [];
  let folded = 0;
  const flush = () => {
    if (folded) out.push({ sign: ' ', text: `⋯ ${folded} matching argument line${folded === 1 ? '' : 's'}`, gap: true });
    folded = 0;
  };
  lines.forEach((line, at) => {
    if (line.sign === ' ' && !keep.has(at)) { folded++; return; }
    flush();
    out.push(line);
  });
  flush();
  return out;
}

// A YAML sequence entry. Only these carry order: a `commands:` list and a swipe's points execute in
// the order they are written, so moving one is a behavior change. Mapping keys do not — jsonToYaml
// emits them in insertion order, so two runs can serialize the same argument object as `text` then
// `timeout` or the reverse, and calling that a change would be a false positive on every such pair.
const SEQUENCE_ITEM = /^\s*- /;

function argChanges(baselineLines: string[], currentLines: string[]): CompareDiffLine[] {
  const removed = subtractLines(baselineLines, currentLines);
  const added = subtractLines(currentLines, baselineLines);
  // Same lines in a different sequence: no line was added or removed, so the only real change left
  // is a moved sequence entry, which the multiset difference cannot see. Every line survives, so
  // the difference is only legible as the two orders printed one after the other.
  if (!removed.length && !added.length) {
    const before = baselineLines.filter((line) => SEQUENCE_ITEM.test(line));
    const after = currentLines.filter((line) => SEQUENCE_ITEM.test(line));
    if (before.join('\n') === after.join('\n')) return [];
    const note: CompareDiffLine = { sign: ' ', text: 'argument order changed' };
    return capLines([note].concat(before.map(del)).concat(after.map(add)));
  }
  // Walk the baseline in argument order: a removed line prints where it sat, its unchanged
  // neighbors print around it as context — the anchor GitHub's surrounding lines provide, without
  // which `− retries: 3` floats free of the call whose other arguments identify it.
  const removedLeft = removed.slice();
  const addedLeft = added.slice();
  const lines: CompareDiffLine[] = [];
  baselineLines.forEach((line) => {
    const at = removedLeft.indexOf(line);
    if (at < 0) { lines.push(ctx(line)); return; }
    removedLeft.splice(at, 1);
    const key = lineKey(line);
    const matchAt = key == null ? -1 : addedLeft.findIndex((a) => lineKey(a) === key);
    // A replaced value prints as the pair git would print — the old line, then the line that took
    // its place — so the reader sees both sides rather than an arrow they have to parse.
    if (matchAt >= 0) lines.push(...highlightPair(del(line), add(addedLeft.splice(matchAt, 1)[0])));
    else lines.push(del(line));
  });
  addedLeft.forEach((line) => lines.push(add(line)));
  return capLines(collapseContext(lines));
}

/**
 * How good a pairing of two rows is: 0 means they cannot pair at all, and a larger number is a
 * better pairing. Label alone can't tell repeated calls apart — baseline `tap(A), tap(B)` against
 * current `tap(X), tap(A), tap(B)` pairs A→X and B→A on label, then reports the real B as added,
 * three wrong rows instead of one inserted call. Scoring an argument-identical pairing above a
 * label-only one makes the alignment prefer the reading where X is the insertion, while a
 * label-only pairing still beats no pairing, so a call whose arguments genuinely changed keeps
 * pairing up and reports as args_changed.
 */
function toolMatchWeight(baseline: ToolRow, current: ToolRow): number {
  if (baseline.label !== current.label) return 0;
  return baseline.lines.join('\n') === current.lines.join('\n') ? 2 : 1;
}

/** Weighted-LCS alignment; an unmatched call appears with the other side null. */
export function alignByLabel<T extends { label: string }>(
  baseline: T[],
  current: T[],
  weightOf: (b: T, c: T) => number = (b, c) => (b.label === c.label ? 1 : 0),
): Array<[T | null, T | null]> {
  const n = baseline.length;
  const m = current.length;
  const weights: number[][] = Array.from({ length: n }, (_, i) => Array.from({ length: m }, (__, j) => weightOf(baseline[i], current[j])));
  const best: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      const skip = Math.max(best[i + 1][j], best[i][j + 1]);
      best[i][j] = weights[i][j] > 0 ? Math.max(weights[i][j] + best[i + 1][j + 1], skip) : skip;
    }
  }
  const aligned: Array<[T | null, T | null]> = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    const weight = weights[i][j];
    if (weight > 0 && weight + best[i + 1][j + 1] >= Math.max(best[i + 1][j], best[i][j + 1])) {
      aligned.push([baseline[i++], current[j++]]);
      continue;
    }
    if (best[i + 1][j] >= best[i][j + 1]) aligned.push([baseline[i++], null]);
    else aligned.push([null, current[j++]]);
  }
  while (i < n) aligned.push([baseline[i++], null]);
  while (j < m) aligned.push([null, current[j++]]);
  return aligned;
}

// What an unmatched call shows: its summary crop when the extraction produced one, else its
// argument lines (SessionToolDiff describes flattened args here; the summary is the same
// information already shaped for reading).
const describeCall = (row: ToolRow): string[] => (row.tool ? [row.tool] : row.lines);

/**
 * The executed calls of a trace, folded turns expanded. A turn row stands for its FIRST tool and
 * carries the rest of the turn's executed calls as `children` (toolChildren), so comparing parents
 * alone reports two runs that opened a turn the same way and then diverged as identical. A child's
 * ×N fold rides along as a comparable line — tapping twice instead of once is a difference. A child
 * deep-links to its parent row, the timeline entry that expands to it.
 */
export function flatToolRows(trace: TraceStep[] | null | undefined): ToolRow[] {
  const rows: ToolRow[] = [];
  toolTimelineOf(trace).forEach((t) => {
    // The parent's own fold count is comparable for the same reason a child's is: a polled
    // assertion that took one attempt is not the run that took three, and without this the two
    // parents produce identical rows and compare `same`.
    rows.push({
      i: t.i,
      label: t.label,
      tool: t.tool || '',
      ok: t.ok !== false,
      lines: comparableArgLines(t.args).concat((t.count || 1) > 1 ? [`× ${t.count}`] : []),
    });
    (t.children || []).forEach((child) => rows.push({
      i: t.i,
      label: child.label,
      tool: child.tool || '',
      ok: child.ok !== false,
      lines: comparableArgLines(child.args).concat((child.count || 1) > 1 ? [`× ${child.count}`] : []),
    }));
  });
  return rows;
}

export function compareToolTimelines(baselineTrace: TraceStep[] | null | undefined, currentTrace: TraceStep[] | null | undefined): CompareToolResult {
  const aligned = alignByLabel(flatToolRows(baselineTrace), flatToolRows(currentTrace), toolMatchWeight);

  const rows: CompareToolRow[] = aligned.map(([baseline, current]) => {
    // A call only one run made is wholly added or wholly removed, so every one of its lines carries
    // that side's gutter — the reader never has to consult the badge to know which run it came from.
    if (!baseline) return { toolName: current!.label, status: 'current_only', changes: capLines(describeCall(current!).map(add)), baselineStep: null, currentStep: current!.i };
    if (!current) return { toolName: baseline.label, status: 'baseline_only', changes: capLines(describeCall(baseline).map(del)), baselineStep: baseline.i, currentStep: null };
    const changes = argChanges(baseline.lines, current.lines);
    const status: CompareToolStatus = baseline.ok !== current.ok ? 'outcome_changed' : changes.length ? 'args_changed' : 'same';
    const outcomeNote: CompareDiffLine[] = status === 'outcome_changed'
      ? highlightPair(del(`outcome: ${baseline.ok ? 'succeeded' : 'FAILED'}`), add(`outcome: ${current.ok ? 'succeeded' : 'FAILED'}`))
      : [];
    // An outcome flip with identical arguments would otherwise show only the flip — the shared
    // arguments ARE the call's identity, so they print as context under it. Uncollapsed: with no
    // signed line among them, collapseContext would fold the whole body into one gap line.
    const body = status === 'outcome_changed' && !changes.length
      ? capLines(baseline.lines.map(ctx))
      : changes;
    return { toolName: baseline.label, status, changes: outcomeNote.concat(body), baselineStep: baseline.i, currentStep: current.i };
  });

  const count = (status: CompareToolStatus) => rows.filter((r) => r.status === status).length;
  const sameCount = count('same');
  const argsChangedCount = count('args_changed');
  const outcomeChangedCount = count('outcome_changed');
  const baselineOnlyCount = count('baseline_only');
  const currentOnlyCount = count('current_only');
  let summary = `Tool calls: ${sameCount} identical`;
  if (argsChangedCount) summary += `, ${argsChangedCount} with changed args`;
  if (outcomeChangedCount) summary += `, ${outcomeChangedCount} with a different outcome`;
  if (baselineOnlyCount) summary += `, ${baselineOnlyCount} only in baseline`;
  if (currentOnlyCount) summary += `, ${currentOnlyCount} only in current run`;
  return { rows, sameCount, argsChangedCount, outcomeChangedCount, baselineOnlyCount, currentOnlyCount, summary };
}

// ---------------------------------------------------------------------------
// Screens lane
// ---------------------------------------------------------------------------

/**
 * differ's SimpleImageComparator default (see SnapshotGoldenComparison.MAX_DISTANCE): two pixels
 * whose Euclidean distance in normalised RGBA space exceeds this differ. Matching the JVM golden
 * gate exactly means this view and a CI golden failure tell the same story about the same pair.
 */
export const PIXEL_MAX_DISTANCE = 0.1;

/**
 * The JVM gate's default pass threshold (SnapshotGoldenComparison.compare thresholdPercent): a
 * scene whose differing-pixel share exceeds this counts as "differs" in the overview, so the
 * headline number here agrees with what a golden comparison would have failed.
 */
export const SCENE_DIFF_THRESHOLD_PERCENT = 2;

export type ComparePixels = { width: number; height: number; data: Uint8ClampedArray | number[] };

export type ComparePixelResult =
  | { kind: 'diff'; differing: number; total: number; percent: number; mask: Uint8Array }
  | { kind: 'size_mismatch'; baseline: [number, number]; current: [number, number] };

/**
 * Per-pixel comparison of two same-sized RGBA buffers. `mask` holds 1 per differing pixel, in
 * row-major order — the caller paints it over the baseline to show WHERE the runs disagree.
 *
 * Different dimensions are not an error but they are not a diff either: two devices' captures
 * share no pixel grid, and scaling one to the other would manufacture differences everywhere.
 * The mismatch is reported as its own result so the view can say so.
 */
export function diffPixels(baseline: ComparePixels, current: ComparePixels): ComparePixelResult {
  if (baseline.width !== current.width || baseline.height !== current.height) {
    return { kind: 'size_mismatch', baseline: [baseline.width, baseline.height], current: [current.width, current.height] };
  }
  const total = baseline.width * baseline.height;
  const mask = new Uint8Array(total);
  const a = baseline.data;
  const b = current.data;
  // Squared-distance comparison: dist > MAX ⇔ dist² > MAX², without a sqrt per pixel.
  const maxSq = PIXEL_MAX_DISTANCE * PIXEL_MAX_DISTANCE;
  let differing = 0;
  for (let p = 0; p < total; p++) {
    const o = p * 4;
    const dr = (a[o] - b[o]) / 255;
    const dg = (a[o + 1] - b[o + 1]) / 255;
    const db = (a[o + 2] - b[o + 2]) / 255;
    const da = (a[o + 3] - b[o + 3]) / 255;
    if (dr * dr + dg * dg + db * db + da * da > maxSq) {
      mask[p] = 1;
      differing++;
    }
  }
  return { kind: 'diff', differing, total, percent: total ? (differing / total) * 100 : 0, mask };
}

export type CompareScene = {
  /** 1-based position in the aligned tool timeline where this scene first appears. */
  position: number;
  toolName: string;
  baselineFile: string | null;
  currentFile: string | null;
};

/**
 * The aligned run reduced to its scene changes: walking the tool timeline, a new scene starts
 * whenever either side's resolved screenshot moves on. A side whose call resolved no frame keeps
 * the one it was last seen on — the run was still looking at SOMETHING — so a scene never loses
 * a side just because one call captured nothing.
 */
export function alignedScenes(
  rows: CompareToolRow[],
  baselineFrameAt: (step: number) => string | null,
  currentFrameAt: (step: number) => string | null,
): CompareScene[] {
  const scenes: CompareScene[] = [];
  let lastBase: string | null = null;
  let lastCurrent: string | null = null;
  rows.forEach((row, index) => {
    const base = row.baselineStep == null ? lastBase : (baselineFrameAt(row.baselineStep) || lastBase);
    const current = row.currentStep == null ? lastCurrent : (currentFrameAt(row.currentStep) || lastCurrent);
    if ((base || current) && (base !== lastBase || current !== lastCurrent)) {
      scenes.push({ position: index + 1, toolName: row.toolName, baselineFile: base, currentFile: current });
    }
    lastBase = base;
    lastCurrent = current;
  });
  return scenes;
}

// ---------------------------------------------------------------------------
// Event-streams lane
// ---------------------------------------------------------------------------

const MAX_GROUP_VALUES = 128;
const MAX_LEAF_PATHS_PER_EVENT = 300;
const MAX_DEPTH = 6;

export type CompareGroupDelta = { key: string; baselineCount: number; currentCount: number; delta: number };

export type CompareStreamDiff = {
  stream: string;
  baselineCount: number;
  currentCount: number;
  delta: number;
  changed: boolean;
  /** The auto-detected grouping field (dot path into the event payload), if one qualified. */
  groupPath: string | null;
  /** Per-group counts, changed groups first (by |delta| descending). */
  groups: CompareGroupDelta[];
  /** True when both runs carry the same event multiset once volatile fields are masked. */
  contentSame: boolean;
  /**
   * The field paths this stream masked, sorted. Masking is what keeps ids and timestamps from
   * reporting as differences, but it also means the diff is quietly not comparing them — and a
   * reader who cannot see which fields those were has no way to tell "these runs agree" from
   * "the field that disagreed was one we hid".
   */
  maskedPaths: string[];
  /** The git-style content diff, present exactly when contentSame is false. */
  content: ContentDiff | null;
  /**
   * Either run's stream was capped by the reader, so only its retained prefix was compared. A
   * matching prefix says nothing about the tail, which is why an incomplete stream must never be
   * presented as identical.
   */
  incomplete: boolean;
};

export type CompareEventsResult = { streams: CompareStreamDiff[]; summary: string };

/** Flattens an event payload to its leaf string fields: dot path → first value. */
export function leafStrings(value: unknown, prefix = '', out: Map<string, string> = new Map(), depth = 0): Map<string, string> {
  if (depth > MAX_DEPTH || out.size > MAX_LEAF_PATHS_PER_EVENT) return out;
  if (Array.isArray(value)) {
    // Array elements share one path — the first value wins, matching the set-if-absent below.
    value.forEach((v) => leafStrings(v, `${prefix}[]`, out, depth + 1));
  } else if (value != null && typeof value === 'object') {
    Object.keys(value as Record<string, unknown>).forEach((key) => {
      leafStrings((value as Record<string, unknown>)[key], prefix ? `${prefix}.${key}` : key, out, depth + 1);
    });
  } else if (typeof value === 'string' && value.length >= 1 && value.length <= 100) {
    if (!out.has(prefix)) out.set(prefix, value);
  }
  return out;
}

/**
 * Picks the grouping field for a stream: among leaf string fields, the one present in at least
 * half the events whose values repeat (distinct ≤ half its occurrences), stay enumerable
 * (2..MAX_GROUP_VALUES distinct), and are shared by both runs — a field whose two runs have no
 * value in common (a session id, a trace id) partitions the runs, not the events, so it never
 * qualifies. Ties prefer higher presence, then more distinct values, then the shorter path.
 * Identical semantics to SessionEventDiff.pickGroupPath.
 */
export function pickGroupPath(baselineLeafs: Array<Map<string, string>>, currentLeafs: Array<Map<string, string>>): string | null {
  const total = baselineLeafs.length + currentLeafs.length;
  if (total < 2) return null;

  const presence = new Map<string, number>();
  const distinct = new Map<string, Set<string>>();
  const baselineValues = new Map<string, Set<string>>();
  const currentValues = new Map<string, Set<string>>();
  ([[baselineLeafs, baselineValues], [currentLeafs, currentValues]] as const).forEach(([leafsList, sideValues]) => {
    leafsList.forEach((leafs) => {
      leafs.forEach((value, path) => {
        presence.set(path, (presence.get(path) || 0) + 1);
        let values = distinct.get(path);
        if (!values) { values = new Set(); distinct.set(path, values); }
        if (values.size <= MAX_GROUP_VALUES) values.add(value);
        let side = sideValues.get(path);
        if (!side) { side = new Set(); sideValues.set(path, side); }
        if (side.size <= MAX_GROUP_VALUES) side.add(value);
      });
    });
  });

  const bothRunsHaveEvents = baselineLeafs.length > 0 && currentLeafs.length > 0;
  let best: string | null = null;
  presence.forEach((seen, path) => {
    const values = distinct.get(path)!.size;
    const sharedAcrossRuns = !bothRunsHaveEvents
      || Array.from(baselineValues.get(path) || []).some((v) => (currentValues.get(path) || new Set()).has(v));
    const qualifies = seen * 2 >= total && values >= 2 && values <= MAX_GROUP_VALUES && values * 2 <= seen && sharedAcrossRuns;
    if (!qualifies) return;
    if (best == null) { best = path; return; }
    const bestSeen = presence.get(best)!;
    const bestValues = distinct.get(best)!.size;
    const beats = seen !== bestSeen ? seen > bestSeen
      : values !== bestValues ? values > bestValues
        : path.length !== best.length ? path.length < best.length
          : path < best;
    if (beats) best = path;
  });
  return best;
}

/**
 * A session's diffable event streams as payload objects per stream name: each EventStream's
 * parsed generic events (SessionEvent.d), or one object per formatter row; plus the parsed network
 * events as a `network` stream when the session captured one (skipped if an `events/network`
 * stream already claims the name).
 *
 * A formatter row keeps `events` empty and carries the payloads it covers in `raw`, so the row's
 * object pairs its label with that raw — label alone would report two same-named analytics events
 * with different properties as unchanged. One object per row either way, so a stream's compared
 * count stays the count the viewer shows.
 */
export function eventObjectsOf(session: { events?: EventStream[] | null; network?: NetworkEvent[] | null }): Map<string, unknown[]> {
  const byStream = new Map<string, unknown[]>();
  (session.events || []).forEach((stream) => {
    if (!stream || !stream.name) return;
    const objects: unknown[] = stream.rows && stream.rows.length
      ? stream.rows.map((row) => (row.raw && row.raw.length ? { label: row.label, raw: row.raw } : { label: row.label }))
      // Any JsonElement is a legal payload, log strings included — so a scalar is an event, not a
      // parse failure. Only an unparseable record drops out; keeping the `typeof === 'object'`
      // test here made a stream of `{"timeMs":1,"d":"ready"}` records read as empty.
      : (stream.events || []).map((event) => {
        try { return { ok: true, value: JSON.parse(event.d) as unknown }; } catch { return null; }
      }).filter((o): o is { ok: true; value: unknown } => o != null).map((o) => o.value);
    byStream.set(stream.name, objects);
  });
  if (session.network && session.network.length && !byStream.has('network')) {
    byStream.set('network', session.network);
  }
  return byStream;
}

/** What the producer said each stream holds, as distinct from what survived into the payload. */
export type StreamMeta = { total: number; truncated: boolean };

/**
 * Per-stream producer counts and completeness, keyed like [eventObjectsOf].
 *
 * Separate from the objects because the two answer different questions: a formatter folds a
 * request and its response into ONE row, so row count is not event count — reporting `1 → 1` for
 * a completed exchange against a request-only run. `total` is what the viewer displays and what
 * the count lane must use; `truncated` says the comparison only ever saw a prefix.
 */
export function eventStreamMetaOf(session: { events?: EventStream[] | null; network?: NetworkEvent[] | null }): Map<string, StreamMeta> {
  const meta = new Map<string, StreamMeta>();
  (session.events || []).forEach((stream) => {
    if (!stream || !stream.name) return;
    const rows = stream.rows && stream.rows.length ? stream.rows.length : (stream.events || []).length;
    meta.set(stream.name, {
      total: typeof stream.total === 'number' && stream.total >= 0 ? stream.total : rows,
      truncated: stream.truncated === true,
    });
  });
  if (session.network && session.network.length && !meta.has('network')) {
    meta.set('network', { total: session.network.length, truncated: false });
  }
  return meta;
}

// ---------------------------------------------------------------------------
// Event content diff (viewer-only extension — no Kotlin counterpart)
// ---------------------------------------------------------------------------
// The count tables above say a stream changed; this says WHAT changed, git-diff style: each event
// canonicalized to YAML-ish lines, the two sequences aligned by LCS, and the result rendered as
// hunks — runs of matching events collapsed, removed/added events shown whole, and an adjacent
// removed+added run paired into per-line changes. Fields whose values never repeat (timestamps,
// uuids, sequence numbers) would make every event unique, so they are detected statistically and
// masked before matching — the same never-repeats principle pickGroupPath uses to reject id-like
// grouping fields.

const VOLATILE_MIN_OCCURRENCES = 4;
const MAX_EVENT_LINES = 30;
const MAX_CONTENT_VALUE_CHARS = 160;
const MAX_DIFF_CELLS = 4_000_000;
const MASKED_VALUE = '‹…›';

/** Same `hi` contract as [CompareDiffLine]: the [from, to) span of the changed word, if one. */
export type ContentDiffLine = { sign: '+' | '-' | ' '; text: string; hi?: [number, number] };

/**
 * One event as the reader meets it in this lane: a single line naming what fired, plus the full
 * payload behind it for when that line isn't enough.
 *
 * A stream is a list of events, and the question asked of it is which ones fired and how they
 * differ — so the unit of the diff is an event, one row each, the way a file diff's unit is a
 * line. The payload's fields are the detail underneath, not the thing being scanned.
 */
export type ContentEventRow = { summary: string; detail: string[]; hi?: [number, number] };

/** A replaced event: both rows, plus the per-field diff that says what moved inside it. */
export type ContentChangedPair = { before: ContentEventRow; after: ContentEventRow; lines: ContentDiffLine[] };

export type ContentHunk =
  // A run of events both runs emitted. `head` and `tail` are the rows kept as context on each side
  // of the run (git's surrounding lines); `folded` counts the ones between them that aren't
  // retained — bounding what a 10k-event stream holds in memory. `from` is where the run starts in
  // the union sequence, so a fold can say which stretch of the run it stands for.
  | { kind: 'same'; count: number; head: ContentEventRow[]; tail: ContentEventRow[]; folded: number; from: number }
  | { kind: 'removed'; rows: ContentEventRow[] }
  | { kind: 'added'; rows: ContentEventRow[] }
  | { kind: 'changed'; pairs: ContentChangedPair[] };
export type ContentDiff = {
  /** False when the streams were too large to align in order (unordered multiset fallback). */
  ordered: boolean;
  hunks: ContentHunk[];
  removedCount: number;
  addedCount: number;
  changedCount: number;
  /**
   * Length of the sequence the reader is scrolling: every event both runs emitted, plus every one
   * only one of them did, each counted once. The denominator for "how much of this run differs" —
   * a question the view otherwise leaves to counting rows by hand.
   */
  slots: number;
  /**
   * How many separate places the runs diverge — runs of adjacent differing hunks, so a removal
   * immediately followed by an addition counts once. Four scattered changes and four in one burst
   * are different failures, and the count is what tells them apart before any reading.
   */
  clusters: number;
};

/**
 * Scalar leaf paths whose values are id-like — seen often enough to judge (≥ VOLATILE_MIN_OCCURRENCES)
 * with at least 90% of the sightings distinct. Covers numbers and booleans too (timestamps are
 * numbers), unlike leafStrings which only feeds the string-valued grouping-key search. The
 * threshold errs toward keeping fields: an over-masked field hides real content, while an
 * under-masked one just leaves its events unmatched — and unmatched neighbors pair into per-line
 * changes, so the difference still shows.
 */
export function volatileScalarPaths(events: unknown[]): Set<string> {
  const stats = new Map<string, { n: number; values: Set<string> }>();
  const walk = (value: unknown, prefix: string, depth: number) => {
    if (depth > MAX_DEPTH) return;
    if (Array.isArray(value)) {
      value.forEach((v) => walk(v, `${prefix}[]`, depth + 1));
    } else if (value != null && typeof value === 'object') {
      Object.keys(value as Record<string, unknown>).forEach((key) => {
        walk((value as Record<string, unknown>)[key], prefix ? `${prefix}.${key}` : key, depth + 1);
      });
    } else if (value != null) {
      let s = stats.get(prefix);
      if (!s) { s = { n: 0, values: new Set() }; stats.set(prefix, s); }
      s.n += 1;
      if (s.values.size <= s.n) s.values.add(String(value).slice(0, 200));
    }
  };
  events.forEach((e) => walk(e, '', 0));
  const volatile = new Set<string>();
  stats.forEach((s, path) => {
    if (s.n >= VOLATILE_MIN_OCCURRENCES && s.values.size * 10 >= s.n * 9) volatile.add(path);
  });
  return volatile;
}

// A string is quoted and every other scalar is bare, so the rendered line carries the JSON type
// as well as the value. These lines ARE the identity events match on, and an untyped `String(v)`
// rendered `{value: 1}` and `{value: "1"}` alike — a schema change that reads as no change.
const contentValue = (value: unknown): string => (typeof value === 'string'
  ? `"${value.replace(/\\/g, '\\\\').replace(/"/g, '\\"').replace(/\n/g, '\\n')}"`
  : String(value).replace(/\n/g, '\\n'));

/**
 * One event as deterministic YAML-ish lines: keys sorted, volatile values masked as ‹…›, nothing
 * truncated. Joined, this is the identity events match on — matching must stay lossless, or a
 * change past a display cap would read as identical. `toDisplayLines` applies the caps afterwards,
 * to rendered output only.
 */
export function eventLines(value: unknown, volatile: Set<string>): string[] {
  const out: string[] = [];
  const emit = (v: unknown, path: string, indent: string, head: string) => {
    if (volatile.has(path)) { out.push(`${indent}${head} ${MASKED_VALUE}`); return; }
    if (Array.isArray(v)) {
      if (!v.length) { out.push(`${indent}${head} []`); return; }
      out.push(`${indent}${head}`);
      v.forEach((item) => emit(item, `${path}[]`, `${indent}  `, '-'));
    } else if (v != null && typeof v === 'object') {
      const keys = Object.keys(v as Record<string, unknown>).sort();
      if (!keys.length) { out.push(`${indent}${head} {}`); return; }
      out.push(`${indent}${head}`);
      keys.forEach((key) => emit((v as Record<string, unknown>)[key], path ? `${path}.${key}` : key, `${indent}  `, `${key}:`));
    } else {
      out.push(`${indent}${head} ${v == null ? 'null' : contentValue(v)}`);
    }
  };
  if (value != null && typeof value === 'object' && !Array.isArray(value)) {
    Object.keys(value as Record<string, unknown>).sort().forEach((key) => emit((value as Record<string, unknown>)[key], key, '', `${key}:`));
  } else {
    emit(value, '', '', '');
  }
  return out;
}

const truncateForDisplay = (line: string): string => (line.length > MAX_CONTENT_VALUE_CHARS
  ? `${line.slice(0, MAX_CONTENT_VALUE_CHARS)}…`
  : line);

/** Render-only caps over lossless lines: long lines cropped, the tail elided with a count. */
export function toDisplayLines(lines: string[]): string[] {
  const shown = lines.slice(0, MAX_EVENT_LINES).map(truncateForDisplay);
  if (lines.length > MAX_EVENT_LINES) {
    const extra = lines.length - MAX_EVENT_LINES;
    shown.push(`… +${extra} more line${extra === 1 ? '' : 's'}`);
  }
  return shown;
}

/** One event as capped, truncated display lines. Matching uses `eventLines` — see its note. */
export function eventDisplayLines(value: unknown, volatile: Set<string>): string[] {
  return toDisplayLines(eventLines(value, volatile));
}

/**
 * Field names the events reader takes as a record's ORDER key rather than its payload (see
 * SessionEventsReader.EventEntry.timeMs). Producers write them inside the record, so they reach the
 * diff as content — but a wall-clock stamp is not what anyone comparing two runs is reading, and on
 * a one-line summary it crowds out the fields that are. Dropped from the summary only: `eventLines`
 * still carries them, so matching and the expanded payload are unaffected.
 */
const ORDER_KEY_NAMES = new Set(['t', 'ts', 'time', 'timems', 'timestamp', 'timestampms']);

const MAX_SUMMARY_CHARS = 150;

const unquoted = (rendered: string): string => (rendered.length >= 2 && rendered.startsWith('"') && rendered.endsWith('"')
  ? rendered.slice(1, -1)
  : rendered);

/**
 * One event as a single line: what fired, then its remaining fields as `key=value`.
 *
 * Leads with [groupPath] — the auto-detected type field, `event_name` for a typical analytics
 * stream — because that is the event's name, and a row whose name sits alphabetically in the middle
 * of its own fields can't be scanned. Field order is the producer's own, not sorted: it reads as
 * the event was written. Volatile and order-key fields are dropped as noise; when that would leave
 * the row empty, they come back rather than rendering a blank line.
 */
export function eventSummary(value: unknown, volatile: Set<string>, groupPath: string | null, keepNoisyFields = false): string {
  const crop = (line: string) => (line.length > MAX_SUMMARY_CHARS ? `${line.slice(0, MAX_SUMMARY_CHARS)}…` : line);
  if (value == null || typeof value !== 'object' || Array.isArray(value)) {
    return crop(value == null ? 'null' : contentValue(value));
  }
  const fields: Array<{ path: string; text: string; noisy: boolean }> = [];
  const walk = (v: unknown, path: string, depth: number) => {
    if (depth > MAX_DEPTH) return;
    const noisy = volatile.has(path) || ORDER_KEY_NAMES.has(path.toLowerCase());
    if (Array.isArray(v)) {
      // A summary says how big a list is; its items are payload, and payload lives in the detail.
      fields.push({ path, text: `[${v.length}]`, noisy });
    } else if (v != null && typeof v === 'object') {
      Object.keys(v as Record<string, unknown>).forEach((key) => walk((v as Record<string, unknown>)[key], path ? `${path}.${key}` : key, depth + 1));
    } else {
      fields.push({ path, text: v == null ? 'null' : contentValue(v), noisy });
    }
  };
  Object.keys(value as Record<string, unknown>).forEach((key) => walk((value as Record<string, unknown>)[key], key, 0));
  const quiet = fields.filter((f) => !f.noisy);
  const shown = keepNoisyFields || !quiet.length ? fields : quiet;
  const lead = groupPath == null ? undefined : shown.find((f) => f.path === groupPath);
  const rest = shown.filter((f) => f !== lead);
  const body = rest.map((f) => `${f.path}=${f.text}`).join(' ');
  return crop([lead ? unquoted(lead.text) : '', body].filter(Boolean).join('  ')) || '(no fields)';
}

// Render-only reduction of a changed pair: every ± line survives, unchanged context is kept only
// next to a change and elided elsewhere. A blind head-crop would hide a change past the cap — the
// exact case a content diff exists to show.
const PAIR_CONTEXT_LINES = 2;
// A bare ⋯ leaves the reader guessing whether one field was hidden or forty, so it says which.
const elisionOf = (count: number): ContentDiffLine => ({ sign: ' ', text: `⋯ ${count} unchanged field${count === 1 ? '' : 's'}` });

export function toDisplayPair(lines: ContentDiffLine[]): ContentDiffLine[] {
  const keep = new Set<number>();
  lines.forEach((line, at) => {
    if (line.sign === ' ') return;
    for (let d = -PAIR_CONTEXT_LINES; d <= PAIR_CONTEXT_LINES; d++) {
      if (at + d >= 0 && at + d < lines.length) keep.add(at + d);
    }
  });
  if (!keep.size) return toDisplayLines(lines.map((l) => l.text)).map((text) => ({ sign: ' ' as const, text }));
  const out: ContentDiffLine[] = [];
  let elided = 0;
  lines.forEach((line, at) => {
    if (!keep.has(at)) { elided++; return; }
    if (elided) { out.push(elisionOf(elided)); elided = 0; }
    out.push({ sign: line.sign, text: truncateForDisplay(line.text) });
  });
  if (elided) out.push(elisionOf(elided));
  // Word-level tint, same as the tool lane: a removed-run followed by an added-run is a replaced
  // value (`- amount_cents: 1275` / `+ amount_cents: 1050`), so mark just the span that moved.
  // Computed on the truncated display text — the span indexes what the reader sees.
  for (let at = 0; at < out.length; at++) {
    if (out[at].sign !== '-') continue;
    let removedEnd = at;
    while (removedEnd < out.length && out[removedEnd].sign === '-') removedEnd++;
    let addedEnd = removedEnd;
    while (addedEnd < out.length && out[addedEnd].sign === '+') addedEnd++;
    const pairCount = Math.min(removedEnd - at, addedEnd - removedEnd);
    for (let p = 0; p < pairCount; p++) {
      const removed = out[at + p];
      const added = out[removedEnd + p];
      const [removedSpan, addedSpan] = highlightSpans(removed.text, added.text);
      if (removedSpan) removed.hi = removedSpan;
      if (addedSpan) added.hi = addedSpan;
    }
    at = addedEnd - 1;
  }
  return out;
}

/**
 * Alignment budget for ONE changed pair. The LCS below allocates an (n+1)×(m+1) matrix up front, so
 * two payloads of tens of thousands of lines each would ask for billions of cells and take the tab
 * down with them. Identity stays lossless — this bounds only how a pair is rendered.
 */
export const MAX_LINE_DIFF_CELLS = 250_000;

/**
 * Linear fallback for a pair too large to align: shared head and tail as context, everything
 * between them as a removal followed by an addition. Coarser than the LCS — a moved line inside the
 * middle reads as removed-and-added rather than moved — but the display caps elide most of that
 * span anyway, and it cannot blow up.
 */
function coarseLineDiff(before: string[], after: string[]): ContentDiffLine[] {
  let head = 0;
  while (head < before.length && head < after.length && before[head] === after[head]) head++;
  let tail = 0;
  while (tail < before.length - head && tail < after.length - head
    && before[before.length - 1 - tail] === after[after.length - 1 - tail]) tail++;
  const lines: ContentDiffLine[] = [];
  for (let i = 0; i < head; i++) lines.push({ sign: ' ', text: before[i] });
  for (let i = head; i < before.length - tail; i++) lines.push({ sign: '-', text: before[i] });
  for (let j = head; j < after.length - tail; j++) lines.push({ sign: '+', text: after[j] });
  for (let i = before.length - tail; i < before.length; i++) lines.push({ sign: ' ', text: before[i] });
  return lines;
}

/** Per-line diff of one changed event pair, over lossless lines. */
export function lineDiff(before: string[], after: string[]): ContentDiffLine[] {
  const n = before.length;
  const m = after.length;
  if ((n + 1) * (m + 1) > MAX_LINE_DIFF_CELLS) return coarseLineDiff(before, after);
  const lcs: number[][] = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      lcs[i][j] = before[i] === after[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
    }
  }
  const lines: ContentDiffLine[] = [];
  let i = 0;
  let j = 0;
  while (i < n && j < m) {
    if (before[i] === after[j]) { lines.push({ sign: ' ', text: before[i++] }); j++; continue; }
    if (lcs[i + 1][j] >= lcs[i][j + 1]) lines.push({ sign: '-', text: before[i++] });
    else lines.push({ sign: '+', text: after[j++] });
  }
  while (i < n) lines.push({ sign: '-', text: before[i++] });
  while (j < m) lines.push({ sign: '+', text: after[j++] });
  return lines;
}

// `lines` are the lossless masked lines; `print` is their join — the identity events match on.
// Display caps are applied only when a hunk is emitted. `summary` is the one-line row the lane
// shows; `summaryNoisy` is the same line with the dropped order/volatile fields put back, used when
// two paired events would otherwise render as the same text (see the pairing note in contentDiffOf).
type EventText = { lines: string[]; print: string; summary: string; summaryNoisy: string };

const eventTextOf = (event: unknown, volatile: Set<string>, groupPath: string | null): EventText => {
  const lines = eventLines(event, volatile);
  return {
    lines,
    print: lines.join('\n'),
    summary: eventSummary(event, volatile, groupPath),
    summaryNoisy: eventSummary(event, volatile, groupPath, true),
  };
};

// Events kept as context on each side of a run both runs emitted. Enough to place a change in the
// sequence; the rest of a long run folds, so a huge stream can't hold its whole self in memory.
const CONTEXT_EVENTS = 2;

const eventRow = (event: EventText): ContentEventRow => ({ summary: event.summary, detail: toDisplayLines(event.lines) });

const sameHunkOf = (events: EventText[], from: number): ContentHunk => {
  const count = events.length;
  if (count <= CONTEXT_EVENTS * 2) return { kind: 'same', count, head: events.map(eventRow), tail: [], folded: 0, from };
  return {
    kind: 'same',
    count,
    head: events.slice(0, CONTEXT_EVENTS).map(eventRow),
    tail: events.slice(count - CONTEXT_EVENTS).map(eventRow),
    folded: count - CONTEXT_EVENTS * 2,
    from,
  };
};

// How many places in the sequence a hunk occupies: an event both runs emitted, an event only one
// did, and a replaced pair each take one.
const hunkSlots = (hunk: ContentHunk): number => (hunk.kind === 'same' ? hunk.count
  : hunk.kind === 'changed' ? hunk.pairs.length
    : hunk.rows.length);

// A replaced event: two rows plus the per-field diff behind them. The summaries carry the same
// word-level span the tool lane paints. Two events that pair but summarize identically differ only
// in a field the summary drops, so both sides fall back to the line that shows those fields —
// rendering `- x` above `+ x` would report a change while hiding every trace of it.
const changedPairOf = (before: EventText, after: EventText): ContentChangedPair => {
  const identical = before.summary === after.summary;
  const beforeText = identical ? before.summaryNoisy : before.summary;
  const afterText = identical ? after.summaryNoisy : after.summary;
  const [beforeSpan, afterSpan] = highlightSpans(beforeText, afterText);
  return {
    before: { summary: beforeText, detail: toDisplayLines(before.lines), ...(beforeSpan ? { hi: beforeSpan } : {}) },
    after: { summary: afterText, detail: toDisplayLines(after.lines), ...(afterSpan ? { hi: afterSpan } : {}) },
    lines: toDisplayPair(lineDiff(before.lines, after.lines)),
  };
};

function contentDiffOf(baseline: EventText[], current: EventText[], maxCells: number): ContentDiff {
  // Strip the matching prefix and suffix first: appended-events is the common shape, and it keeps
  // the quadratic alignment to the region that actually moved.
  let lo = 0;
  while (lo < baseline.length && lo < current.length && baseline[lo].print === current[lo].print) lo++;
  let hi = 0;
  while (hi < baseline.length - lo && hi < current.length - lo
    && baseline[baseline.length - 1 - hi].print === current[current.length - 1 - hi].print) hi++;
  const midB = baseline.slice(lo, baseline.length - hi);
  const midC = current.slice(lo, current.length - hi);

  // Same-runs accumulate as their events, not as a bare count: the reader is looking at a list, and
  // the events on each side of a change are what place it in that list. sameHunkOf keeps only the
  // context rows at emit time, so holding them here stays bounded per run.
  const hunks: Array<ContentHunk | { kind: 'same'; events: EventText[] }> = [];
  const pushSame = (events: EventText[]) => {
    if (!events.length) return;
    const last = hunks[hunks.length - 1];
    if (last && last.kind === 'same' && 'events' in last) last.events = last.events.concat(events);
    else hunks.push({ kind: 'same', events });
  };
  pushSame(baseline.slice(0, lo));

  let ordered = true;
  if (midB.length * midC.length > maxCells) {
    // Too large to align in order: report the multiset difference — what appears more often in one
    // run than the other — without claiming where in the sequence it happened.
    ordered = false;
    const counts = new Map<string, number>();
    midC.forEach((e) => counts.set(e.print, (counts.get(e.print) || 0) + 1));
    const removed: EventText[] = [];
    const matched: EventText[] = [];
    midB.forEach((e) => {
      const nAvailable = counts.get(e.print) || 0;
      if (nAvailable > 0) { counts.set(e.print, nAvailable - 1); matched.push(e); }
      else removed.push(e);
    });
    const surplus = new Map(counts);
    const added: EventText[] = [];
    midC.forEach((e) => {
      const nLeft = surplus.get(e.print) || 0;
      if (nLeft > 0) { surplus.set(e.print, nLeft - 1); added.push(e); }
    });
    pushSame(matched);
    if (removed.length) hunks.push({ kind: 'removed', rows: removed.map(eventRow) });
    if (added.length) hunks.push({ kind: 'added', rows: added.map(eventRow) });
  } else {
    const aligned = alignByLabel(
      midB.map((e) => ({ label: e.print, event: e })),
      midC.map((e) => ({ label: e.print, event: e })),
    );
    let sameRun: EventText[] = [];
    let removedRun: EventText[] = [];
    let addedRun: EventText[] = [];
    const flushEdits = () => {
      // An adjacent removed+added run pairs positionally into changed events, git-style; the
      // longer side's leftovers stay plain removals/additions.
      const pairCount = Math.min(removedRun.length, addedRun.length);
      if (pairCount) {
        hunks.push({ kind: 'changed', pairs: removedRun.slice(0, pairCount).map((before, at) => changedPairOf(before, addedRun[at])) });
      }
      if (removedRun.length > pairCount) hunks.push({ kind: 'removed', rows: removedRun.slice(pairCount).map(eventRow) });
      if (addedRun.length > pairCount) hunks.push({ kind: 'added', rows: addedRun.slice(pairCount).map(eventRow) });
      removedRun = [];
      addedRun = [];
    };
    aligned.forEach(([b, c]) => {
      if (b && c) {
        if (!sameRun.length) flushEdits();
        sameRun.push(b.event);
        return;
      }
      if (sameRun.length) { pushSame(sameRun); sameRun = []; }
      if (b) removedRun.push(b.event);
      else addedRun.push(c!.event);
    });
    flushEdits();
    pushSame(sameRun);
  }
  pushSame(baseline.slice(baseline.length - hi));

  // One forward pass places every hunk: the sequence position a same-run starts at, and how many
  // separate stretches of it differ. Adjacent differing hunks — a removal the alignment split from
  // the addition beside it — are one divergence to a reader, so they close one cluster, not two.
  let slots = 0;
  let clusters = 0;
  let diverging = false;
  const finalized: ContentHunk[] = hunks.map((raw) => {
    const hunk = raw.kind === 'same' && 'events' in raw ? sameHunkOf(raw.events, slots) : raw as ContentHunk;
    if (hunk.kind === 'same') diverging = false;
    else if (!diverging) { clusters++; diverging = true; }
    slots += hunkSlots(hunk);
    return hunk;
  });
  const removedCount = finalized.reduce((n, h) => n + (h.kind === 'removed' ? h.rows.length : 0), 0);
  const addedCount = finalized.reduce((n, h) => n + (h.kind === 'added' ? h.rows.length : 0), 0);
  const changedCount = finalized.reduce((n, h) => n + (h.kind === 'changed' ? h.pairs.length : 0), 0);
  return { ordered, hunks: finalized, removedCount, addedCount, changedCount, slots, clusters };
}

/** Git-style content diff of two event sequences; exported for tests (maxCells caps the alignment). */
export function diffEventContent(baseline: unknown[], current: unknown[], maxCells: number = MAX_DIFF_CELLS): ContentDiff {
  const volatile = volatileScalarPaths(baseline.concat(current));
  const groupPath = pickGroupPath(baseline.map((e) => leafStrings(e)), current.map((e) => leafStrings(e)));
  return contentDiffOf(
    baseline.map((e) => eventTextOf(e, volatile, groupPath)),
    current.map((e) => eventTextOf(e, volatile, groupPath)),
    maxCells,
  );
}

function diffStream(
  stream: string,
  baseline: unknown[],
  current: unknown[],
  baselineMeta?: StreamMeta,
  currentMeta?: StreamMeta,
): CompareStreamDiff {
  const baselineLeafs = baseline.map((e) => leafStrings(e));
  const currentLeafs = current.map((e) => leafStrings(e));
  const groupPath = pickGroupPath(baselineLeafs, currentLeafs);

  let groups: CompareGroupDelta[] = [];
  if (groupPath != null) {
    const countBy = (leafs: Array<Map<string, string>>) => {
      const counts = new Map<string, number>();
      leafs.forEach((l) => {
        const key = l.get(groupPath) ?? '(absent)';
        counts.set(key, (counts.get(key) || 0) + 1);
      });
      return counts;
    };
    const baselineCounts = countBy(baselineLeafs);
    const currentCounts = countBy(currentLeafs);
    const keys = new Set<string>([...baselineCounts.keys(), ...currentCounts.keys()]);
    groups = Array.from(keys)
      .map((key) => {
        const b = baselineCounts.get(key) || 0;
        const c = currentCounts.get(key) || 0;
        return { key, baselineCount: b, currentCount: c, delta: c - b };
      })
      .sort((a, b) => Math.abs(b.delta) - Math.abs(a.delta) || (a.key < b.key ? -1 : a.key > b.key ? 1 : 0));
  }

  // Content identity: the same masked events in the same order on both sides means nothing changed
  // beyond volatile fields — a cheaper check than the full diff, and it catches streams whose counts
  // and groups match but whose payloads differ. Order is part of it: a stream is a sequence, so a
  // run that emitted A then B is not the run that emitted B then A, and comparing the two as
  // multisets would report that pair as unchanged and skip the diff that shows the swap.
  const volatile = volatileScalarPaths(baseline.concat(current));
  const baselineTexts = baseline.map((e) => eventTextOf(e, volatile, groupPath));
  const currentTexts = current.map((e) => eventTextOf(e, volatile, groupPath));
  const contentSame = baselineTexts.length === currentTexts.length
    && baselineTexts.every((e, at) => e.print === currentTexts[at].print);

  // The producer's own totals, not the compared array lengths: a folding formatter emits one row
  // for two records, so row counts would call a completed exchange and a request-only run `1 → 1`.
  const baselineCount = baselineMeta ? baselineMeta.total : baseline.length;
  const currentCount = currentMeta ? currentMeta.total : current.length;
  const incomplete = (baselineMeta ? baselineMeta.truncated : false) || (currentMeta ? currentMeta.truncated : false);
  const delta = currentCount - baselineCount;
  return {
    stream,
    baselineCount,
    currentCount,
    delta,
    // An incomplete stream is never reported as unchanged — the tail nobody compared could hold
    // anything, and "identical" is a claim the data does not support.
    changed: delta !== 0 || groups.some((g) => g.delta !== 0) || !contentSame || incomplete,
    groupPath,
    groups,
    contentSame,
    maskedPaths: Array.from(volatile).sort(),
    content: contentSame ? null : contentDiffOf(baselineTexts, currentTexts, MAX_DIFF_CELLS),
    incomplete,
  };
}

export function compareEventStreams(
  baseline: { events?: EventStream[] | null; network?: NetworkEvent[] | null },
  current: { events?: EventStream[] | null; network?: NetworkEvent[] | null },
  excludeStreams: Set<string> = new Set(),
): CompareEventsResult {
  const baselineStreams = eventObjectsOf(baseline);
  const currentStreams = eventObjectsOf(current);
  const baselineMeta = eventStreamMetaOf(baseline);
  const currentMeta = eventStreamMetaOf(current);
  const names = Array.from(new Set<string>([...baselineStreams.keys(), ...currentStreams.keys()]))
    .filter((name) => !excludeStreams.has(name))
    .sort();

  const streams = names
    .map((name) => diffStream(
      name,
      baselineStreams.get(name) || [],
      currentStreams.get(name) || [],
      baselineMeta.get(name),
      currentMeta.get(name),
    ))
    .sort((a, b) => Number(b.changed) - Number(a.changed) || Math.abs(b.delta) - Math.abs(a.delta) || (a.stream < b.stream ? -1 : a.stream > b.stream ? 1 : 0));

  let summary: string;
  if (!streams.length) {
    summary = 'Events: none captured';
  } else {
    const changed = streams.filter((s) => s.changed);
    // A stream flagged only by its payload content would read as a no-op `(8→8)` — say why it's here.
    const describe = (s: CompareStreamDiff) => {
      const why = s.incomplete ? ', partial' : (s.delta === 0 && s.groups.every((g) => g.delta === 0) ? ', content' : '');
      return `${s.stream} (${s.baselineCount}→${s.currentCount}${why})`;
    };
    summary = `Events: ${streams.length} stream(s)`;
    summary += changed.length
      ? `; changed: ${changed.map(describe).join(', ')}`
      : ', all identical in count';
  }
  return { streams, summary };
}
