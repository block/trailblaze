// The cross-run trail matrix: the same authored trail executed on several devices, joined on the
// authored step. Each session's trace already groups into authored steps (buildReportTraceModel);
// this aligns those groups ACROSS sessions — step N of one run beside step N of every other — so a
// view can draw one lane per device with the shared natural-language step as the row.
//
// Alignment is by step NUMBER, not by matching text: every lane ran the same trail YAML, so the
// numbered objectives correspond positionally even when a step's wording carries per-platform
// notes. The row label still comes from the first lane that has the step, and a lane whose label
// disagrees keeps its own text on the cell (`labelDiffers`) rather than being silently relabelled.

import type { ReportTraceGroup, ReportTraceModel } from './run-report-trace-model';

/** One captured frame inside a step group: the trace row (and folded dispatch) that captured it. */
export interface TrailFrame {
  /** TraceStep.i of the capturing row — the timeline deep-link target. */
  rowId: number;
  /** Index into that row's folded children, or null for the row's own capture. */
  kid: number | null;
  /** Key into the owning session's `shots` map. */
  file: string;
  /** The capturing tool's label, for captions. */
  label: string;
  /**
   * Offset of this capture from the lane's run start, ms — the same shared clock TrailCell.startMs
   * is on, so a replay can ask what every device had on screen at one instant. Null without
   * timestamps (a folded dispatch falls back to its owning row's).
   */
  atMs: number | null;
}

/**
 * One device interaction inside a step: where it landed and when. Independent of the frame list,
 * because plenty of actions never captured a screenshot of their own — a replay driving a video
 * still wants to draw the tap that happened at that instant.
 */
export interface TrailAction {
  /** Offset from the lane's run start, ms — the shared clock TrailFrame.atMs is on. */
  atMs: number;
  /** Where and how the action landed, in device pixels (see ActionMark). */
  mark: ActionMark;
  /** The acting tool's label, for captions. */
  label: string;
}

/** One lane's execution of one authored step. */
export interface TrailCell {
  /** The group header's TraceStep.i — where "open this step on this device" lands. */
  headerId: number;
  ok: boolean;
  selfHeal: boolean;
  /** This lane's own step text, kept when it disagrees with the row label. */
  label: string;
  labelDiffers: boolean;
  /** Offset of the step's first record from the lane's run start, ms. Null without timestamps. */
  startMs: number | null;
  /** Wall-clock span of the step: first record start → last record end. Null without timestamps. */
  durationMs: number | null;
  /** Tool rows the step ran (LLM turn rows excluded — they narrate, they don't act). */
  toolCount: number;
  /** Every distinct frame the step captured, in execution order (row frames and folded-dispatch
   * frames, deduped per row the way the Lightbox's expanded mode does). */
  frames: TrailFrame[];
  /** The step's final frame — the Lightbox's default-mode pick: the last row-own capture, falling
   * back to the last folded dispatch's capture when every frame sits on dispatches. */
  lastFrame: TrailFrame | null;
  /** Every positioned interaction the step performed, in clock order. */
  actions: TrailAction[];
  /**
   * The instant the lane's run failure landed inside this step, on the lane clock — non-null only
   * on the cell holding the lane's failure anchor. It is the anchor row's own `ts` (its
   * timeBeforeExecution, so the last placeable moment before the failing work ran); an anchor with
   * no timestamp falls back to the step span's end, which is where a crashed step's clock stopped.
   */
  failureAtMs: number | null;
}

export interface TrailRow {
  /** The step's number within its own lane; 0 is the trailhead/setup row. In a `position` join the
   * cells on a row are only neighbours at that number, not the same step. */
  num: number;
  /** The shared natural-language step text (first lane that has this step). Empty in a `position`
   * join, where there is no shared step for a row to be named after. */
  label: string;
  /** Aligned with the lanes array; null where a lane never reached this step. */
  cells: Array<TrailCell | null>;
}

/**
 * How a matrix decides which cells share a row.
 *
 * - `step` — by AUTHORED step number. The lanes are runs of one trail, so row 3 is that trail's
 *   step 3 on every device and comparing across a row is meaningful. This is the Trail view.
 * - `position` — by nothing more than that number. Used when the picked runs are NOT one trail:
 *   they share no step spine, so the rows carry no shared label and nothing claims that one lane's
 *   third step corresponds to another's. Cells sit side by side and each names itself.
 */
export type TrailJoin = 'step' | 'position';

export interface TrailMatrix {
  rows: TrailRow[];
  /** The longest lane's total span (ms) — the time view's shared axis length. */
  maxEndMs: number;
  /** How rows were formed. `position` means rows are neighbours, not a comparison. */
  join: TrailJoin;
}

type HasShot = (laneIndex: number, file: string | null | undefined) => boolean;
type IsLlmTurn = (row: TraceStep) => boolean;

const groupRows = (group: ReportTraceGroup): TraceStep[] =>
  [group.header, ...group.items].filter((row): row is TraceStep => Boolean(row));

// A record's capture counts only when it names a file AND the session can actually show it —
// the same `has` rule the Lightbox applies, so a frameless header row can never contribute.
const shows = (hasShot: HasShot, lane: number, file: string | null | undefined): file is string =>
  Boolean(file) && hasShot(lane, file);

// Offset of a captured moment from the lane's run start. A folded dispatch that logged no
// timestamp of its own rides its owning row's, so a capture is never stranded off the clock while
// the row around it is on it. That fallback is also what places a row's OWN cue: `shotTs`/`markTs`
// are only set when a folded driver action supplied the capture or mark, in which case they are the
// instant it actually ran rather than the tool's timeBeforeExecution.
const atMs = (ts: number | null | undefined, rowTs: number | null | undefined, t0: number | null): number | null => {
  const at = ts != null ? ts : rowTs;
  return at != null && t0 != null ? at - t0 : null;
};

// Every distinct frame the group captured, in order — the Lightbox's expanded-mode walk: a row's
// own capture first, then each folded dispatch's capture, deduped against the frames that row
// already contributed.
const collectFrames = (group: ReportTraceGroup, lane: number, t0: number | null, hasShot: HasShot): TrailFrame[] =>
  groupRows(group).flatMap((row) => {
    const seen = new Set([row.screenshotFile]);
    return [
      ...(shows(hasShot, lane, row.screenshotFile) ? [{ rowId: row.i, kid: null, file: row.screenshotFile as string, label: row.label, atMs: atMs(row.shotTs, row.ts, t0) }] : []),
      ...(row.children || []).flatMap((child, kid) => {
        if (!shows(hasShot, lane, child.screenshotFile) || seen.has(child.screenshotFile)) return [];
        seen.add(child.screenshotFile);
        return [{ rowId: row.i, kid, file: child.screenshotFile as string, label: `${row.label} · ${child.label}`, atMs: atMs(child.ts, row.ts, t0) }];
      }),
    ];
  });

// The Lightbox default-mode pick: the last ROW-OWN frame wins; only a group whose every capture
// sits on folded dispatches falls back to the last dispatch frame. This is not simply the last
// entry of collectFrames — a trailing dispatch frame must not displace the row frame the Lightbox
// summarizes the step with.
const pickLastFrame = (group: ReportTraceGroup, lane: number, t0: number | null, hasShot: HasShot): TrailFrame | null => {
  const rows = groupRows(group);
  for (let r = rows.length - 1; r >= 0; r--) {
    if (shows(hasShot, lane, rows[r].screenshotFile)) return { rowId: rows[r].i, kid: null, file: rows[r].screenshotFile as string, label: rows[r].label, atMs: atMs(rows[r].shotTs, rows[r].ts, t0) };
  }
  for (let r = rows.length - 1; r >= 0; r--) {
    const kids = rows[r].children || [];
    for (let k = kids.length - 1; k >= 0; k--) {
      if (shows(hasShot, lane, kids[k].screenshotFile)) return { rowId: rows[r].i, kid: k, file: kids[k].screenshotFile as string, label: `${rows[r].label} · ${kids[k].label}`, atMs: atMs(kids[k].ts, rows[r].ts, t0) };
    }
  }
  return null;
};

// Every positioned interaction the group performed, in clock order. Unlike collectFrames this does
// NOT require a screenshot: an action's mark is worth drawing wherever the replay gets its picture
// from, and a run recording video captures far fewer stills than it performs taps. An action with
// no placeable instant is dropped — a mark drawn at a guessed moment would point at the wrong
// screen.
const collectActions = (group: ReportTraceGroup, t0: number | null): TrailAction[] =>
  groupRows(group).flatMap((row) => {
    const at = atMs(row.markTs, row.ts, t0);
    return [
      ...(row.mark && at != null ? [{ atMs: at, mark: row.mark, label: row.label }] : []),
      ...(row.children || []).flatMap((child) => {
        const childAt = atMs(child.ts, row.ts, t0);
        return child.mark && childAt != null
          ? [{ atMs: childAt, mark: child.mark, label: `${row.label} · ${child.label}` }]
          : [];
      }),
    ];
  }).sort((a, b) => a.atMs - b.atMs);

const groupSpan = (group: ReportTraceGroup): { start: number | null; end: number | null } => {
  let start: number | null = null;
  let end: number | null = null;
  for (const row of groupRows(group)) {
    if (row.ts == null) continue;
    if (start == null || row.ts < start) start = row.ts;
    const rowEnd = row.ts + (row.ms || 0);
    if (end == null || rowEnd > end) end = rowEnd;
  }
  return { start, end };
};

const cellFromGroups = (
  groups: ReportTraceGroup[],
  lane: number,
  t0: number | null,
  hasShot: HasShot,
  isLlmTurn: IsLlmTurn,
  failureAnchor: TraceStep | null,
): TrailCell | null => {
  if (!groups.length) return null;
  const headers = groups.map((g) => g.header).filter((h): h is TraceStep => Boolean(h));
  let start: number | null = null;
  let end: number | null = null;
  for (const group of groups) {
    const span = groupSpan(group);
    if (span.start != null && (start == null || span.start < start)) start = span.start;
    if (span.end != null && (end == null || span.end > end)) end = span.end;
  }
  // A step's outcome comes from its objective bookends — a tool row that failed inside a step which
  // ultimately passed (retry polling, a recovery loop) is tolerated, not a failure. But a run can
  // fail without any objective recording it: a crash logs no Complete bookend, so every header
  // stays ok and the step that actually died would read green. The lane's failure anchor covers
  // that case, and it is the same anchor the detail timeline and scrubber mark.
  const anchored = failureAnchor != null && groups.some((g) =>
    g.header === failureAnchor || g.items.indexOf(failureAnchor) >= 0 || g.retryHeaders.indexOf(failureAnchor) >= 0);
  // The anchor's own instant, clamped into the step span so a skewed record can't place the death
  // outside the block a strip draws for it. Unplaceable without the lane clock (t0) or a span.
  let failureAtMs: number | null = null;
  if (anchored && failureAnchor && t0 != null && start != null && end != null) {
    const at = failureAnchor.ts != null ? Math.min(Math.max(failureAnchor.ts, start), end) : end;
    failureAtMs = at - t0;
  }
  return {
    headerId: headers.length ? headers[0].i : groupRows(groups[0])[0]?.i ?? 0,
    ok: !anchored && headers.every((h) => h.ok) && groups.every((g) => g.retryHeaders.every((h) => h.ok)),
    selfHeal: headers.some((h) => h.selfHeal) || groups.some((g) => g.retryHeaders.length > 0),
    label: headers.length ? headers[0].label : '',
    labelDiffers: false,
    startMs: start != null && t0 != null ? start - t0 : null,
    durationMs: start != null && end != null ? Math.max(0, end - start) : null,
    toolCount: groups.reduce((total, g) => total + g.items.filter((row) => !isLlmTurn(row)).length, 0),
    frames: groups.flatMap((group) => collectFrames(group, lane, t0, hasShot)),
    lastFrame: groups.reduce<TrailFrame | null>((last, group) => pickLastFrame(group, lane, t0, hasShot) || last, null),
    actions: groups.flatMap((group) => collectActions(group, t0)),
    failureAtMs,
  };
};

/**
 * Join every lane's authored-step groups into shared rows. `models` is one ReportTraceModel per
 * session, in lane order. Row 0 folds together each lane's trailhead group AND any headerless
 * preamble groups — setup, not authored trail — and is omitted when no lane has one.
 *
 * `laneFailureAnchor` names the row a lane's run failure belongs to (failureAnchorIndex over that
 * session's trace), or null for a lane whose run did not fail — the cell holding that row reads
 * failed even when its objective bookends are clean, which is how a crash keeps its attribution.
 *
 * `join` decides what a row MEANS — see [TrailJoin]. A `position` join is for a hand-picked set of
 * runs that are not one trail: their step numbers are not comparable, so a row of them is captioned
 * by nothing rather than by whichever lane's "Sign in" came first.
 */
export function buildTrailMatrix(
  models: ReportTraceModel[],
  hasShot: HasShot,
  isLlmTurn: IsLlmTurn,
  laneFailureAnchor: (lane: number) => TraceStep | null = () => null,
  join: TrailJoin = 'step',
): TrailMatrix {
  const laneNumbered = models.map((model) => {
    const byNum = new Map<number, ReportTraceGroup[]>();
    // A lane's step numbers are its own 1..n running count either way (buildReportTraceModel numbers
    // them as it walks that lane's trace), so both joins put a lane's k-th step on row k. What the
    // join changes is what a row is allowed to SAY about the cells that land on it.
    for (const group of model.groups) {
      const num = group.header && !group.header.trailhead ? group.num : 0;
      if (!byNum.has(num)) byNum.set(num, []);
      (byNum.get(num) as ReportTraceGroup[]).push(group);
    }
    return byNum;
  });
  const maxNum = laneNumbered.reduce((max, byNum) => Math.max(max, ...Array.from(byNum.keys())), 0);
  const anchors = models.map((_, lane) => laneFailureAnchor(lane));
  const rows: TrailRow[] = [];
  for (let num = 0; num <= maxNum; num++) {
    const cells = models.map((model, lane) =>
      cellFromGroups(laneNumbered[lane].get(num) || [], lane, model.traceT0, hasShot, isLlmTurn, anchors[lane]));
    if (cells.every((cell) => cell == null)) continue;
    // A position join has no shared step to name the row after, so the row carries no label and
    // every cell shows its own (labelDiffers, which is what the renderers already key on).
    const label = join === 'position' && num !== 0
      ? ''
      : num === 0
        ? 'Trailhead'
        : (cells.find((cell) => cell && cell.label) as TrailCell | undefined)?.label || `Step ${num}`;
    cells.forEach((cell) => { if (cell && num !== 0) cell.labelDiffers = Boolean(cell.label) && cell.label !== label; });
    rows.push({ num, label, cells });
  }
  const maxEndMs = rows.reduce((max, row) => row.cells.reduce((laneMax, cell) =>
    cell && cell.startMs != null && cell.durationMs != null ? Math.max(laneMax, cell.startMs + cell.durationMs) : laneMax, max), 0);
  return { rows, maxEndMs, join };
}

/** One device's slice of a multi-device session's trace (see traceDeviceLanes). */
export interface DeviceLaneTrace {
  /** Binding name, or null for a leading prefix assignTraceDevices could not attribute — those
   * rows still ran on the start device, so the caller labels the lane from session metadata. */
  device: string | null;
  trace: TraceStep[];
}

/**
 * Split ONE session's trace into per-device lanes — a session that drove several devices through
 * `switchDevice` handovers becomes one lane per device, ready for the same buildReportTraceModel →
 * buildTrailMatrix path a multi-run comparison uses. Lanes come out in first-appearance order, so
 * lane 0 is the start device (the one whose recording video, if any, exists).
 *
 * Every lane keeps every authored-step header, because buildReportTraceModel NUMBERS steps by
 * counting the objective rows it sees — drop a header from one lane and every later step
 * misaligns across lanes. A lane that does not own the header gets a CLONE stripped of its clock
 * and captures: the original's timestamp would stretch the idle lane's cell across the whole span
 * another device spent, and the original object itself must stay unique to its owning lane
 * because the failure anchor is matched by identity. Non-header rows appear only in the lane that
 * owns them. Returns [] when the trace names fewer than two devices — nothing to split.
 */
export function traceDeviceLanes(trace: TraceStep[]): DeviceLaneTrace[] {
  const keys: Array<string | null> = [];
  for (const row of trace) {
    const key = row.device ?? null;
    if (keys.indexOf(key) < 0) keys.push(key);
  }
  if (keys.length < 2) return [];
  return keys.map((device) => ({
    device,
    trace: trace.flatMap((row) => {
      if ((row.device ?? null) === device) return [row];
      if (!row.objective) return [];
      return [{ ...row, ts: null, ms: 0, screenshotFile: null, mark: null, shotTs: null, markTs: null, children: undefined }];
    }),
  }));
}

/**
 * Null out matrix cells that hold nothing this lane's device actually DID — no tool calls, no
 * frames, no interactions, no failure. In a per-device split every lane carries every step header
 * for alignment (see traceDeviceLanes), so a device that sat idle through a step would otherwise
 * render a hollow cell where the honest answer is the "not reached" gap. This also drops a cell
 * holding only the step's announcement — the objective row lands on whichever device had focus
 * when the step STARTED, which says nothing about where its work ran — and, deliberately, a cell
 * whose device contributed only LLM narration: lanes show where devices acted. A row every lane
 * went idle on disappears entirely.
 *
 * A CAPTURE counts as activity even on a bare announcement: the frame is a real screen this
 * device was showing at that moment, so the lane that owns the header keeps a cell when the
 * announcement itself was captured. Only the lanes that BORROWED the header (their clones are
 * stripped of captures, see traceDeviceLanes) prune away to a gap.
 */
export function pruneIdleTrailCells(matrix: TrailMatrix): TrailMatrix {
  const rows = matrix.rows
    .map((row) => ({
      ...row,
      cells: row.cells.map((cell) =>
        cell && (cell.toolCount > 0 || cell.frames.length > 0 || cell.actions.length > 0 || cell.failureAtMs != null) ? cell : null),
    }))
    .filter((row) => row.cells.some((cell) => cell != null));
  return { rows, maxEndMs: matrix.maxEndMs, join: matrix.join };
}

// ── Which trails a document can offer a Trail view for ────────────────────────────────────────
// The view compares ONE authored trail across runs — one lane per device, joined on the authored
// step — so it is offered per TRAIL, not per document. A report holding fifty unrelated trails can
// still offer it for each of them. A trail that ran ONCE is offered too, on one lane: that is the
// single-run report's own case, where the view is the map/grid/replay of a single run rather than a
// comparison. The rule below is what decides, and it is here rather than in the viewer so it can be
// tested without a DOM.

/** A session reduced to what Trail-view availability turns on. */
export interface TrailCandidate {
  /** Trail identity — see [trailIdentity]. Empty when the run names no trail. */
  key: string;
  /** Held back rather than run. Excluded from the comparison instead of disqualifying it. */
  skipped: boolean;
  /** A stub pointing at another document: no trace here, so nothing to align. */
  linkOut: boolean;
  /** Whether this run's chunk has been parsed. An unparsed run's trace is unknown, not empty. */
  hydrated: boolean;
  hasTrace: boolean;
}

/**
 * A run's trail identity, shared by the index's per-trail rows and the Trail view's scope so a
 * button and the view it opens can never disagree about which runs belong together.
 *
 * `trailId` is the authored trail, qualified by target: the same YAML driven against two apps is
 * two trails, and aligning them would put unrelated steps in one row. Title is the fallback for a
 * run that carries no trailId — weaker (two trails can share a title), but it is the identity the
 * reader sees, and without it an unidentified run could never see itself as a trail at all. A run
 * with neither returns '' and is never offered the view: unnamed runs are not "the same trail",
 * they are unidentified.
 *
 * The `trail:` / `title:` prefix is load-bearing, not decoration — see [TITLE_IDENTITY_PREFIX].
 */
export const TITLE_IDENTITY_PREFIX = 'title:';

export function trailIdentity(meta: { trailId?: unknown; title?: unknown; target?: unknown } | null | undefined): string {
  const m = meta || {};
  const trailId = m.trailId == null ? '' : String(m.trailId);
  const target = m.target == null ? '' : String(m.target);
  if (trailId) return `trail:${encodeURIComponent(trailId)}:${encodeURIComponent(target)}`;
  const title = m.title == null ? '' : String(m.title);
  return title ? `${TITLE_IDENTITY_PREFIX}${encodeURIComponent(title)}:${encodeURIComponent(target)}` : '';
}

/**
 * How a stage of runs may be read ACROSS, given each lane's [trailIdentity]. One trail's runs share
 * the authored step spine, so row k means "step k of this trail" and comparing along it is the
 * whole point. Anything else is positional: row k is just each lane's own k-th step, and captioning
 * it would claim a spine those runs never shared.
 *
 * A shared TITLE is not a shared trail — the same rule [trailViewScopes] applies, for the same
 * reason — and a lane on its own always keeps its own spine, since there is nothing to line it up
 * against.
 */
export function trailJoinFor(keys: string[]): TrailJoin {
  if (keys.length < 2) return 'step';
  const unique = new Set(keys);
  if (unique.size !== 1) return 'position';
  const only = Array.from(unique)[0];
  return !only || only.startsWith(TITLE_IDENTITY_PREFIX) ? 'position' : 'step';
}

/**
 * Every trail in the document the view can be opened for, mapped to the session indices that
 * become its lanes — in document order, so lane order matches the index.
 *
 * Skips are excluded rather than disqualifying: a held-back trail is a link-out stub with no
 * trace, so counting it would take the view away from a trail whose actual runs are all present,
 * and the reader would lose the comparison because one device was configured not to participate.
 * A trail whose every run was skipped therefore has no lanes and is absent from the map.
 */
export function trailViewScopes(candidates: TrailCandidate[]): Map<string, number[]> {
  const scopes = new Map<string, number[]>();
  candidates.forEach((candidate, index) => {
    if (!candidate.key || candidate.skipped) return;
    if (!scopes.has(candidate.key)) scopes.set(candidate.key, []);
    scopes.get(candidate.key)!.push(index);
  });
  candidates.forEach((candidate) => {
    // One unusable run poisons its whole trail: a lane with no trace to align would render as an
    // empty column beside real ones, which reads as "this device did nothing" rather than "this
    // run is not here".
    //
    // An UNHYDRATED run is not that. Chunked documents parse a run's trace on first open, so on a
    // fresh load every run looks traceless — judging those empty would hide the entry points on
    // exactly the big CI reports the view exists for, and would make the run index change shape as
    // chunks land. They are offered, and opening one waits for its chunks.
    if (candidate.skipped || !scopes.has(candidate.key)) return;
    if (candidate.linkOut || (candidate.hydrated && !candidate.hasTrace)) scopes.delete(candidate.key);
  });
  // A TITLE identity never joins runs to each other. Only an explicit trailId is safe to coalesce —
  // the run index takes the same position (indexGroupKey gives a trailId-less run a key of its own,
  // because same-title runs can be independent histories rather than one trail's devices). Joining
  // them here would stage as one comparison the very runs the index shows as unrelated rows, and
  // each of those rows would carry a button opening it. A title still identifies a run to ITSELF,
  // which is what lets a single unidentified run see its own trail.
  Array.from(scopes.entries()).forEach(([key, sessions]) => {
    if (key.startsWith(TITLE_IDENTITY_PREFIX) && sessions.length > 1) scopes.delete(key);
  });
  return scopes;
}
