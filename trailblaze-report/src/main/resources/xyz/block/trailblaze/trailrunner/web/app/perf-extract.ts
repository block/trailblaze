// Log → profile extraction for the performance-analysis report. Pure functions over raw
// Trailblaze log records — no DOM, no fetch — consumed by the bun driver (perf-report-cli.ts)
// and unit-tested in perf-extract.test.ts. Shared contract types come from the ambient
// perf-types.d.ts / run-report-types.d.ts.
//
// The span model (verified against the Kotlin log writers AND real CI sessions):
//  - A log's `timestamp` is when the operation STARTED and `durationMs` how long it took, so
//    its span is START-ANCHORED: [timestamp, timestamp + durationMs). The writers pin this:
//    TrailblazeAgentContext sets `timestamp = timeBeforeExecution`, TrailblazeLogger/
//    OrchestraRunner set `timestamp = startTime` with `durationMs = end - start`. Real sessions
//    confirm it: consecutive same-level tool logs satisfy next.ts ≈ this.ts + this.durationMs.
//    ONE exception: McpSamplingLog is END-anchored (LocalLlmSamplingSource stamps it with
//    Clock.System.now() after the call), so its span is [timestamp - durationMs, timestamp).
//  - TrailblazeToolLog / TrailblazeLlmRequestLog / MaestroCommandLog share the HOST clock and
//    nest by interval containment (a tool that delegates fully contains what it delegated to,
//    give or take single-digit ms of bookkeeping — hence the epsilon).
//  - MaestroDriverLog timestamps are on the DEVICE clock, which skews from the host clock by
//    whole seconds. They are NEVER nested into the containment tree; they ride a separate track.
//  - `traceId` groups all logs of one objective/step — it is NOT parentage. Steps come from
//    ObjectiveStartLog/ObjectiveCompleteLog pairs instead; spans are attributed to the step
//    whose window contains them.
//
// Self-time accounting: real sessions have partial sibling overlaps (a child's tail extending a
// few ms past its parent, two siblings overlapping), so a naive sum of (dur - Σ child dur)
// over-counts union coverage by 15-30%. The clamp sweep below assigns every span an EFFECTIVE
// interval — clamped into its parent's effective interval and de-overlapped against earlier
// siblings — and derives self time as the effective interval minus the children's effective
// intervals (kept as segments so aggregates can be clipped to a selected time range exactly).
// By construction Σ selfMs over the whole tree == union coverage of the roots; the tests pin it.

import { logClass, stepText, summarizeToolArgs, truncate } from './run-report-extract';

/** Containment tolerance, ms: a wrapper's child can overhang either edge by bookkeeping ms. */
const NEST_EPSILON_MS = 12;
/** Root-union gaps shorter than this are bookkeeping noise, not actionable idle time. */
const GAP_MIN_MS = 250;
/** spent/budget at or above this ratio counts as burning the whole timeout. */
const FULL_BURN_RATIO = 0.98;

/**
 * Parse a Trailblaze log timestamp (ISO-8601, possibly with nanosecond precision from
 * kotlinx-datetime) to epoch ms. Fractional digits beyond ms are trimmed before Date.parse —
 * engines differ on >3-digit fractions. Null for absent/unparseable values.
 */
function parsePerfTimestamp(value: unknown): number | null {
  if (typeof value !== 'string' || !value) return null;
  const trimmed = value.replace(/(\.\d{3})\d+/, '$1');
  const ms = Date.parse(trimmed);
  return Number.isFinite(ms) ? ms : null;
}

/** The requested timeout, ms: any top-level numeric raw-arg key matching /timeout/i. */
function timeoutBudgetMs(raw: unknown): number | null {
  if (!raw || typeof raw !== 'object') return null;
  for (const key of Object.keys(raw)) {
    const value = (raw as Record<string, unknown>)[key];
    if (/timeout/i.test(key) && typeof value === 'number' && Number.isFinite(value) && value > 0) return value;
  }
  return null;
}

function compactArgs(raw: unknown): string | null {
  if (!raw || typeof raw !== 'object' || !Object.keys(raw).length) return null;
  try {
    return truncate(JSON.stringify(raw), 2000);
  } catch (_) {
    return null;
  }
}

function llmCostOf(usage: any): number | null {
  if (!usage) return null;
  if (usage.totalCost != null) return usage.totalCost;
  if (usage.promptCost != null || usage.completionCost != null) return (usage.promptCost || 0) + (usage.completionCost || 0);
  return null;
}

function llmTokensOf(usage: any): string | null {
  if (!usage) return null;
  const input = usage.inputTokens;
  const output = usage.outputTokens;
  if (input == null && output == null) return null;
  return `${input ?? '?'}→${output ?? '?'}`;
}

/** Display name for a maestro command log: the command object's single key. */
function maestroCommandName(log: TrailblazeLogRecord): string {
  const command = log.maestroCommandJsonObj || log.command;
  if (command && typeof command === 'object') {
    const key = Object.keys(command)[0];
    if (key) return `maestro.${key}`;
  }
  return 'maestro';
}

function driverActionName(log: TrailblazeLogRecord): string {
  const cls = String((log.action && log.action.class) || '');
  const last = cls.split('.').pop();
  return `driver.${last || 'action'}`;
}

interface MutableSpan extends PerfSpan {
  /** File-order index for deterministic tie-breaks in the nest sort. */
  order: number;
}

/**
 * Build the raw (un-nested) spans for one session. Exported for tests.
 *
 * [llmRequestTraceIds] carries the traceIds of the session's TrailblazeLlmRequestLogs: the
 * MCP-sampling agent path logs the same LLM call twice (a start-anchored request log AND an
 * end-anchored McpSamplingLog, shared traceId), and the request log is the span. A sampling log
 * with no paired request (the producer had no screen context) is the only record of that call,
 * so it becomes the LLM span instead.
 */
function buildRawSpans(logs: TrailblazeLogRecord[], t0: number, llmRequestTraceIds: Set<string> = new Set()): MutableSpan[] {
  const spans: MutableSpan[] = [];
  logs.forEach((log, order) => {
    const dur = log.durationMs;
    if (typeof dur !== 'number' || !Number.isFinite(dur) || dur < 0) return;
    const ts = parsePerfTimestamp(log.timestamp);
    if (ts == null) return;
    const cls = logClass(log);
    // McpSamplingLog is the one END-anchored duration carrier; see extractPerfSession's bounds.
    const start = cls === 'McpSamplingLog' ? ts - dur : ts;
    let kind: PerfSpanKind;
    let name: string;
    let detail = '';
    let args: string | null = null;
    let budget: number | null = null;
    let cost: number | null = null;
    let tokens: string | null = null;
    let ok = true;
    let err: string | null = null;
    if (cls === 'TrailblazeToolLog') {
      kind = 'tool';
      name = log.toolName || 'tool';
      const raw = (log.trailblazeTool && log.trailblazeTool.raw) || {};
      detail = summarizeToolArgs(raw, {});
      args = compactArgs(raw);
      budget = timeoutBudgetMs(raw);
      ok = log.successful !== false;
      err = typeof log.exceptionMessage === 'string' && log.exceptionMessage ? log.exceptionMessage : null;
      if (err) ok = false;
    } else if (cls === 'TrailblazeLlmRequestLog') {
      kind = 'llm';
      name = `LLM · ${log.llmRequestLabel || (log.trailblazeLlmModel && log.trailblazeLlmModel.modelId) || log.modelName || 'request'}`;
      const usage = log.llmRequestUsageAndCost;
      cost = llmCostOf(usage);
      tokens = llmTokensOf(usage);
      detail = tokens ? `${tokens} tokens` : '';
    } else if (cls === 'McpSamplingLog') {
      if (log.traceId && llmRequestTraceIds.has(String(log.traceId))) return; // paired duplicate of a request log
      kind = 'llm';
      name = `LLM · ${log.modelName || 'sampling'}`;
      const usage = log.usageAndCost;
      cost = llmCostOf(usage);
      tokens = llmTokensOf(usage);
      detail = tokens ? `${tokens} tokens` : '';
      ok = log.successful !== false;
      err = typeof log.errorMessage === 'string' && log.errorMessage ? log.errorMessage : null;
      if (err) ok = false;
    } else if (cls === 'MaestroCommandLog') {
      kind = 'maestro';
      name = maestroCommandName(log);
      ok = log.successful !== false;
      err = typeof log.errorMessage === 'string' && log.errorMessage ? log.errorMessage : null;
      if (err) ok = false;
    } else if (cls === 'MaestroDriverLog') {
      kind = 'driver';
      name = driverActionName(log);
      const action = log.action || {};
      if (action.conditionDescription) detail = truncate(String(action.conditionDescription), 60);
      else if (action.text) detail = `"${truncate(String(action.text), 40)}"`;
      if (action.succeeded === false) { ok = false; err = `Assertion failed: ${action.conditionDescription || ''}`; }
    } else {
      return; // Other durationMs carriers (e.g. task-status bookends) are not profile spans.
    }
    const s = start - t0;
    spans.push({
      id: 0, // assigned after the deterministic sort
      name,
      kind,
      s,
      e: s + dur,
      dur,
      self: kind === 'driver' ? dur : 0,
      selfSegs: [],
      effS: s,
      effE: s + dur,
      depth: 0,
      parent: null,
      kids: [],
      step: null,
      ok,
      err,
      detail,
      args,
      budget,
      cost,
      tokens,
      shot: typeof log.screenshotFile === 'string' && log.screenshotFile ? log.screenshotFile : null,
      order,
    });
  });
  return spans;
}

/**
 * Nest tree spans by interval containment with an epsilon, then run the clamp sweep that assigns
 * effective intervals + exact self-time segments. Mutates the spans in place; returns root ids.
 *
 * Nesting (validated against real sessions): process sorted by (s asc, e desc, file order asc)
 * with a stack, popping while the current span is not contained in the stack top (allowing
 * NEST_EPSILON_MS of overhang on both edges). A partially-overlapping span therefore pops its
 * would-be parent and becomes a sibling further up (or a root).
 */
function nestAndAccount(spans: MutableSpan[]): number[] {
  const tree = spans.filter((sp) => sp.kind !== 'driver');
  tree.sort((a, b) => a.s - b.s || b.e - a.e || a.order - b.order);
  const stack: MutableSpan[] = [];
  const roots: MutableSpan[] = [];
  for (const sp of tree) {
    while (stack.length) {
      const top = stack[stack.length - 1];
      if (sp.s >= top.s - NEST_EPSILON_MS && sp.e <= top.e + NEST_EPSILON_MS) break;
      stack.pop();
    }
    const parent = stack.length ? stack[stack.length - 1] : null;
    if (parent) {
      sp.parent = parent.id;
      sp.depth = parent.depth + 1;
      parent.kids.push(sp.id);
    } else {
      sp.depth = 0;
      roots.push(sp);
    }
    stack.push(sp);
  }

  const byId = new Map(spans.map((sp) => [sp.id, sp]));
  // Effective intervals: children clamped into the parent's effective interval and de-overlapped
  // against earlier siblings (deterministic: earlier-starting sibling keeps the contested time).
  // Self = the parts of the effective interval no child's effective interval covers.
  const sweep = (sp: MutableSpan, lo: number, hi: number): void => {
    sp.effS = Math.min(Math.max(sp.s, lo), hi);
    sp.effE = Math.min(Math.max(sp.e, sp.effS), hi);
    const kids = sp.kids.map((id) => byId.get(id)!).sort((a, b) => a.s - b.s || a.order - b.order);
    let cursor = sp.effS;
    const segs: Array<[number, number]> = [];
    for (const kid of kids) {
      sweep(kid, cursor, sp.effE);
      if (kid.effS > cursor) segs.push([cursor, kid.effS]);
      cursor = Math.max(cursor, kid.effE);
    }
    if (sp.effE > cursor) segs.push([cursor, sp.effE]);
    sp.selfSegs = segs;
    sp.self = segs.reduce((sum, [a, b]) => sum + (b - a), 0);
  };
  roots.sort((a, b) => a.s - b.s || a.order - b.order);
  let cursor = -Infinity;
  for (const root of roots) {
    sweep(root, Math.max(root.s, cursor), Infinity);
    cursor = Math.max(cursor, root.effE);
  }
  return roots.map((r) => r.id);
}

/** Steps: pair each ObjectiveStartLog with its matching ObjectiveCompleteLog (same promptStep). */
function buildSteps(logs: TrailblazeLogRecord[], t0: number): PerfStep[] {
  const steps: PerfStep[] = [];
  const openByKey = new Map<string, PerfStep[]>();
  for (const log of logs) {
    const cls = logClass(log);
    if (cls !== 'ObjectiveStartLog' && cls !== 'ObjectiveCompleteLog') continue;
    const ts = parsePerfTimestamp(log.timestamp);
    if (ts == null) continue;
    let key = '';
    try { key = JSON.stringify(log.promptStep ?? null); } catch (_) { key = String(log.promptStep); }
    if (cls === 'ObjectiveStartLog') {
      const step: PerfStep = {
        i: steps.length,
        label: truncate(stepText(log.promptStep) || 'Step', 140),
        s: ts - t0,
        e: null,
        ok: true,
        err: null,
        calls: null,
        trailhead: log.promptStep?.isTrailhead === true,
      };
      steps.push(step);
      const open = openByKey.get(key) || [];
      open.push(step);
      openByKey.set(key, open);
    } else {
      const open = openByKey.get(key);
      const step = open && open.length ? open.pop()! : null;
      if (!step) continue;
      step.e = ts - t0;
      const result = log.objectiveResult;
      const failed = result && String(result.class || '').indexOf('Failure') >= 0;
      if (failed) {
        step.ok = false;
        step.err = String(result.llmExplanation || log.errorMessage || 'Objective failed');
      }
      const calls = result && result.statusData && result.statusData.callCount;
      if (typeof calls === 'number') step.calls = calls;
    }
  }
  return steps;
}

/** Root-union gaps over [0, t1] longer than GAP_MIN_MS, with the flanking span names. */
function buildGaps(spans: PerfSpan[], roots: number[], t1: number): PerfGap[] {
  const gaps: PerfGap[] = [];
  const rootSpans = roots.map((id) => spans[id]);
  let cursor = 0;
  let before: string | null = null;
  for (const root of rootSpans) {
    if (root.effS - cursor > GAP_MIN_MS) {
      gaps.push({ s: cursor, e: root.effS, dur: root.effS - cursor, before, after: root.name });
    }
    if (root.effE >= cursor) { cursor = root.effE; before = root.name; }
  }
  if (t1 - cursor > GAP_MIN_MS) gaps.push({ s: cursor, e: t1, dur: t1 - cursor, before, after: null });
  return gaps;
}

/** Timeout-tax rows: every tree tool span that declared a budget, heaviest first. */
function buildTax(spans: PerfSpan[]): PerfTaxRow[] {
  return spans
    .filter((sp) => sp.kind === 'tool' && sp.budget != null)
    .map((sp) => ({
      spanId: sp.id,
      name: sp.name,
      detail: sp.detail,
      spent: sp.dur,
      budget: sp.budget!,
      ok: sp.ok,
      full: sp.dur >= sp.budget! * FULL_BURN_RATIO,
    }))
    .sort((a, b) => b.spent - a.spent);
}

/**
 * Bottom-up (heaviest self time) aggregation over the tree spans whose self segments intersect
 * [rangeS, rangeE], clipping each segment to the range so the numbers are exact for any zoom
 * selection. Driver spans are included by their raw duration clipped to the range (they have no
 * tree accounting). Sorted by self desc.
 */
function bottomUpAggregate(spans: PerfSpan[], rangeS: number, rangeE: number): PerfBottomUpRow[] {
  const rows = new Map<string, PerfBottomUpRow>();
  for (const sp of spans) {
    let self = 0;
    if (sp.kind === 'driver') {
      self = Math.max(0, Math.min(sp.e, rangeE) - Math.max(sp.s, rangeS));
    } else {
      for (const [a, b] of sp.selfSegs) self += Math.max(0, Math.min(b, rangeE) - Math.max(a, rangeS));
    }
    if (self <= 0) continue;
    const key = `${sp.kind}:${sp.name}`;
    const row = rows.get(key);
    if (row) {
      row.self += self;
      row.count += 1;
      row.maxSelf = Math.max(row.maxSelf, self);
    } else {
      rows.set(key, { name: sp.name, kind: sp.kind, self, count: 1, maxSelf: self });
    }
  }
  return [...rows.values()].sort((a, b) => b.self - a.self);
}

/**
 * Extract one session's full profile from its raw log records. Returns null when the logs carry
 * no host-clock timestamps at all (nothing to anchor a timeline on).
 *
 * The session window [t0, t1] comes from host-clock logs AND host-clock span ENDS — a span can
 * end after the session's last log timestamp (a long-running tool's log is written at its START;
 * its duration can extend past every later log, e.g. a trailhead tool logged at session start
 * that runs 77s). That stretch is real execution time and must be on the timeline, not clipped.
 * MaestroDriverLog device-clock timestamps must not stretch the window by their skew, so they're
 * excluded from both bounds.
 */
function extractPerfSession(rawLogs: TrailblazeLogRecord[]): PerfSessionData | null {
  // The caller's order is not trustworthy (the report input carries filename-sorted raw logs,
  // not the timestamp-sorted typed list) and step pairing is order-sensitive — sort by
  // timestamp up front (stable, so same-timestamp records keep their given order).
  const sortKey = (log: TrailblazeLogRecord): number => parsePerfTimestamp(log.timestamp) ?? -8.64e15; // min date: timestampless records first
  const logs = [...rawLogs].sort((a, b) => sortKey(a) - sortKey(b));
  const hostBounds: number[] = [];
  for (const log of logs) {
    const cls = logClass(log);
    if (cls === 'MaestroDriverLog') continue;
    const ts = parsePerfTimestamp(log.timestamp);
    if (ts == null) continue;
    hostBounds.push(ts);
    const dur = log.durationMs;
    if (typeof dur === 'number' && Number.isFinite(dur) && dur > 0) {
      // Only classes that buildRawSpans turns into spans may stretch the window by their
      // duration — other duration carriers don't share the start-anchored contract (e.g.
      // TrailblazeAgentTaskStatusChangeLog stamps at status-change time with the task's TOTAL
      // duration; ts + dur would fabricate a tail gap of nearly the whole session).
      // McpSamplingLog is the one END-anchored span source (LocalLlmSamplingSource stamps it
      // with Clock.System.now() AFTER the call); the other span sources are start-anchored.
      if (cls === 'McpSamplingLog') hostBounds.push(ts - dur);
      else if (cls === 'TrailblazeToolLog' || cls === 'TrailblazeLlmRequestLog' || cls === 'MaestroCommandLog') hostBounds.push(ts + dur);
    }
  }
  if (!hostBounds.length) return null;
  const t0 = Math.min(...hostBounds);
  const t1 = Math.max(...hostBounds) - t0;

  // TraceIds of the request logs, for the paired-sampling dedupe (see buildRawSpans kdoc).
  const llmRequestTraceIds = new Set<string>(
    logs.filter((l) => logClass(l) === 'TrailblazeLlmRequestLog' && l.traceId).map((l) => String(l.traceId)),
  );
  const spans = buildRawSpans(logs, t0, llmRequestTraceIds);
  // Deterministic id space: tree spans in nest order first (s asc, e desc, file order), then
  // driver spans by start — so ids are stable for equal inputs and roots reference tree ids.
  spans.sort((a, b) => {
    const aDriver = a.kind === 'driver' ? 1 : 0;
    const bDriver = b.kind === 'driver' ? 1 : 0;
    if (aDriver !== bDriver) return aDriver - bDriver;
    return a.s - b.s || b.e - a.e || a.order - b.order;
  });
  spans.forEach((sp, i) => { sp.id = i; });
  const roots = nestAndAccount(spans);

  const steps = buildSteps(logs, t0);
  // Attribute each span to the step whose window contains its START (a tool is dispatched while
  // its step is active; the start is on the host clock for tree spans).
  for (const sp of spans) {
    if (sp.kind === 'driver') continue;
    const step = steps.find((st) => sp.s >= st.s && (st.e == null || sp.s <= st.e));
    sp.step = step ? step.i : null;
  }

  const gaps = buildGaps(spans, roots, t1);
  const tax = buildTax(spans);
  const covered = roots.reduce((sum, id) => sum + (spans[id].effE - spans[id].effS), 0);
  // Trailhead setup cost: total time inside trailhead steps (an unfinished step runs to t1).
  const trailheadMs = steps.reduce((sum, step) => sum + (step.trailhead ? (step.e == null ? t1 : step.e) - step.s : 0), 0);
  const llmSpans = spans.filter((sp) => sp.kind === 'llm');
  const llmCosts = llmSpans.map((sp) => sp.cost).filter((c): c is number => c != null);
  const selfHealed = logs.some((log) => logClass(log) === 'SelfHealInvokedLog');

  const clean = spans.map(({ order, ...rest }) => rest);
  return {
    t0,
    t1,
    spans: clean,
    roots,
    steps,
    gaps,
    tax,
    covered,
    gapTotal: gaps.reduce((sum, g) => sum + g.dur, 0),
    taxFullBurn: tax.filter((t) => t.full).reduce((sum, t) => sum + t.spent, 0),
    llmCount: llmSpans.length,
    llmTotalMs: llmSpans.reduce((sum, sp) => sum + sp.dur, 0),
    llmCostUsd: llmCosts.length ? llmCosts.reduce((a, b) => a + b, 0) : null,
    trailheadMs,
    selfHealed,
  };
}

export {
  GAP_MIN_MS,
  NEST_EPSILON_MS,
  bottomUpAggregate,
  buildRawSpans,
  extractPerfSession,
  parsePerfTimestamp,
  timeoutBudgetMs,
};
