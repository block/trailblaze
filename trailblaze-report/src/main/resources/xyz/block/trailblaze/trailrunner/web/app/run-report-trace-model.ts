// Canonical structural index for a report trace. The viewer renders the same authored-step
// grouping in the timeline, scrubber, lightbox, LLM table, and transcript panel; building it once
// keeps those surfaces in sync and avoids repeatedly walking a large trace during one render.

export interface ReportTraceGroup {
  header: TraceStep | null;
  num: number;
  items: TraceStep[];
  retryAt: number[];
  retryHeaders: TraceStep[];
}

export interface ReportTraceCallContext {
  group: ReportTraceGroup;
  row: TraceStep;
}

export interface ReportTraceCallStep {
  group: ReportTraceGroup;
  calls: number[];
}

export interface ReportTraceCallMap {
  byCall: Array<ReportTraceCallContext | null>;
  steps: ReportTraceCallStep[];
  stepIndexByGroup: Map<ReportTraceGroup, number>;
}

/**
 * One addressable unit of the timeline: a trace row, or one of the extra tool dispatches a
 * traceId fold absorbed into that row. A folded row stands for its FIRST dispatch and hangs the
 * rest off `children`, each with its own captured frame — so the scrub rail, the transport buttons
 * and playback all walk row-then-dispatches. Walking rows alone replays a step that tapped four
 * targets as a single frame.
 */
export interface ReportTimelineEntry {
  row: TraceStep;
  /** Index into `row.children` for a folded dispatch; null for the row's own entry. */
  kid: number | null;
  child: TraceChild | null;
  /** Run-clock instant and duration playback schedules this entry on (the row's, or the child's). */
  ts: number | null;
  ms: number | null;
}

export interface ReportTraceModel {
  groups: ReportTraceGroup[];
  groupByRow: Map<TraceStep, ReportTraceGroup>;
  indexById: Map<number, number>;
  traceT0: number | null;
  callMap: ReportTraceCallMap;
  entries: ReportTimelineEntry[];
  /** Entry index of each row's OWN entry, by row id (TraceStep.i). Its dispatches follow it. */
  entryIndexById: Map<number, number>;
}

/** Build the authored-step, retry, row-id, and exact LLM-call indexes in one trace pass. */
export function buildReportTraceModel(trace: TraceStep[], llmCount: number): ReportTraceModel {
  const groups: ReportTraceGroup[] = [];
  const groupByRow = new Map<TraceStep, ReportTraceGroup>();
  const indexById = new Map<number, number>();
  let traceT0: number | null = null;
  let current: ReportTraceGroup | null = null;
  let authoredStepNumber = 0;

  trace.forEach((row, index) => {
    indexById.set(row.i, index);
    if (traceT0 == null && row.ts != null) traceT0 = row.ts;

    if (row.objective) {
      const retry = current && current.header && current.header.selfHeal && !current.header.ok
        && !row.trailhead && current.header.label === row.label;
      if (retry && current) {
        current.retryAt.push(current.items.length);
        current.retryHeaders.push(row);
        groupByRow.set(row, current);
        return;
      }
      current = {
        header: row,
        num: row.trailhead ? 0 : ++authoredStepNumber,
        items: [],
        retryAt: [],
        retryHeaders: [],
      };
      groups.push(current);
      groupByRow.set(row, current);
      return;
    }

    if (!current) {
      current = { header: null, num: 0, items: [], retryAt: [], retryHeaders: [] };
      groups.push(current);
    }
    current.items.push(row);
    groupByRow.set(row, current);
  });

  const byCall: Array<ReportTraceCallContext | null> = new Array(llmCount).fill(null);
  const steps: ReportTraceCallStep[] = [];
  const stepIndexByGroup = new Map<ReportTraceGroup, number>();
  for (const group of groups) {
    const calls: number[] = [];
    for (const row of group.items) {
      const callIndex = row.llm;
      if (callIndex == null || callIndex < 0 || callIndex >= llmCount) continue;
      byCall[callIndex] = { group, row };
      calls.push(callIndex);
    }
    // Headerless groups support direct access to legacy calls, but are not Step-navigation
    // destinations because the report cannot name an authored step for them.
    if (group.header && calls.length) {
      stepIndexByGroup.set(group, steps.length);
      steps.push({ group, calls });
    }
  }

  // Timeline entries: every row, each followed by the dispatches it absorbed that captured a frame
  // the row is not already showing. Dedupe runs per row and against the row's OWN capture only: a
  // dispatch repeating the frame its row displays would stall playback on one image, while a
  // frameless row has nothing of its own to dedupe against — it only BORROWS a frame for the
  // preview pane, and the dispatch that captured it still owns the label and tap mark that frame is
  // about. Carrying the shown frame across rows would suppress a dispatch just because some earlier
  // row happened to display its image.
  const entries: ReportTimelineEntry[] = [];
  const entryIndexById = new Map<number, number>();
  for (const row of trace) {
    const kids = row.children || [];
    entryIndexById.set(row.i, entries.length);
    entries.push({ row, kid: null, child: null, ts: row.ts ?? null, ms: row.ms ?? null });
    let shownShot: string | null = row.screenshotFile || null;
    // A dispatch whose log carried no timestamp (payloads predating TraceChild.ts) still needs its
    // OWN instant: sharing the row's would collapse the whole fold onto its last dispatch, which is
    // the frame-skipping this list exists to fix. They ran back to back INSIDE the row, so their
    // instants are spread across the row's span, weighted by each dispatch's duration. That span is
    // a ceiling, not a starting point: the row's ms comes from the delegating wrapper log, whose
    // duration already covers the executors it waited on, so walking past it lets a fold overtake
    // the NEXT row's real ts and drags every offset after it forward. Midpoints keep each synthetic
    // instant strictly inside the span, clear of the row's own entry and of the next row alike.
    const span = row.ms ?? 0;
    const kidsMs = kids.reduce((total, child) => total + (child.ms ?? 0), 0);
    let elapsed = 0;
    kids.forEach((child, kid) => {
      elapsed += child.ms ?? 0;
      const frac = kidsMs > 0 ? (elapsed - (child.ms ?? 0) / 2) / kidsMs : (kid + 1) / (kids.length + 1);
      // With no span to divide (a row that recorded no duration), fall back to the dispatches' own
      // cumulative durations — nothing to overrun, and the fold still gets distinct instants.
      const ts = child.ts ?? (row.ts == null ? null : row.ts + Math.round(span > 0 ? span * frac : elapsed));
      if (child.screenshotFile && child.screenshotFile !== shownShot) {
        entries.push({ row, kid, child, ts, ms: child.ms ?? null });
        shownShot = child.screenshotFile;
      }
    });
  }

  return {
    groups,
    groupByRow,
    indexById,
    traceT0,
    callMap: { byCall, steps, stepIndexByGroup },
    entries,
    entryIndexById,
  };
}

/**
 * Resolve a session's model without retaining dead sessions. Live updates usually replace the
 * trace/LLM arrays; the length checks also cover append-in-place updates from a streaming report.
 */
export function createReportTraceModelResolver(): (session: SessionPayload) => ReportTraceModel {
  type CacheEntry = {
    trace: TraceStep[];
    traceLength: number;
    llm: LlmCall[];
    llmLength: number;
    model: ReportTraceModel;
  };
  const cache = new WeakMap<SessionPayload, CacheEntry>();
  return (session) => {
    const prior = cache.get(session);
    if (prior && prior.trace === session.trace && prior.traceLength === session.trace.length
      && prior.llm === session.llm && prior.llmLength === session.llm.length) return prior.model;
    const model = buildReportTraceModel(session.trace, session.llm.length);
    cache.set(session, {
      trace: session.trace,
      traceLength: session.trace.length,
      llm: session.llm,
      llmLength: session.llm.length,
      model,
    });
    return model;
  };
}
