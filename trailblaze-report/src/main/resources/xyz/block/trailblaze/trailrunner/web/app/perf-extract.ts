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
// Trace spans (kind "trace") are the exception to all of the above, and the only spans with REAL
// parentage: they come from the session's `trace.json` — the Chrome Trace "X" events
// TrailblazeTracer recorded in-process from lexically nested `trace { }` blocks. They nest in TWO
// LAYERS, so a trace can never rearrange the tool hierarchy:
//  - Log spans nest only among log spans (the epsilon rule above), exactly as if no trace existed.
//  - Trace spans nest only among trace spans, by the parentage the tracer DECLARED: an event's
//    `psid` names the `trace { }` frame it opened inside, so no inference is involved and a span
//    with no `psid` really is a root. Events with no `sid` at all — an older trace, or one pushed
//    straight into the recorder — fall back to inferring it, and only where the nesting is
//    provably lexical: same trace, same (pid, tid), EXACT containment (no epsilon — an enclosing block
//    contains its inner block by construction, off one monotonic clock), and neither span marked
//    `args.async` (async producers — the HTTP emitters — stamp their thread at observation time,
//    so same-thread containment among them is coincidence, not calls).
//  - Each resulting trace ROOT then attaches UNDER the innermost log span containing its midpoint.
//    Midpoint, not containment: a dispatch-layer wrapper trace (e.g. Playwright's
//    executePlaywrightTool) opens before the tool log's start stamp and closes after it, so it
//    CONTAINS the tool it belongs inside — the clamp sweep trims its overhang to the parent. A
//    trace root over no log span becomes a top-level root.
// They also record what the log records cannot: HTTP calls, Maestro driver ops on the HOST clock,
// and selector matching — the inside of a tool, where a log-only profile can only report a total.
//
// Self-time accounting: real sessions have partial sibling overlaps (a child's tail extending a
// few ms past its parent, two siblings overlapping), so a naive sum of (dur - Σ child dur)
// over-counts union coverage by 15-30%. The clamp sweep below assigns every span an EFFECTIVE
// interval — clamped into its parent's effective interval and de-overlapped against earlier
// siblings — and derives self time as the effective interval minus the children's effective
// intervals (kept as segments so aggregates can be clipped to a selected time range exactly).
// By construction Σ selfMs over the whole tree == union coverage of the roots; the tests pin it.

import { logClass, stepText, summarizeToolArgs, truncate } from './run-report-extract';

/**
 * Containment tolerance, ms, for log-vs-log nesting: a wrapper's child can overhang either edge
 * by bookkeeping ms, because both edges are separately-stamped clock reads. Trace-vs-trace
 * nesting uses NO tolerance — TrailblazeTracer's Complete events come from lexically nested
 * `trace { }` blocks measured off one monotonic clock, so an inner span is contained in its
 * enclosing span by construction, and a tolerance would only let genuinely-overlapping siblings
 * masquerade as parent and child.
 */
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
  /**
   * Trace spans: the producer declared this event an async observation (`args.async`), so its
   * (pid, tid) is where it was RECORDED, not where the work ran — so it is neither parented nor
   * parented BY INFERENCE. A declared `psid` still nests it: the producer read its parent from the
   * coroutine context, which is knowledge, not a guess about threads.
   * Extraction-internal, stripped from the emitted contract.
   */
  async: boolean;
  /**
   * Trace spans: the tracer's own span id and its declared parent's, when the producer recorded
   * them. Present means parentage is DECLARED, not inferred — including "declared root" when sid
   * is set and psid is not. Extraction-internal; the resolved answer ships as parent/kids.
   */
  sid: string | null;
  psid: string | null;
  /**
   * Trace spans: which trace the producer recorded this span into. A session dir can hold spans
   * merged from more than one process, and a span never nests into a span from another trace.
   * Extraction-internal, stripped from the emitted contract.
   */
  trid: string | null;
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
      pid: null,
      tid: null,
      cat: null,
      spanKind: null,
      order,
      async: false,
      sid: null,
      psid: null,
      trid: null,
    });
  });
  return spans;
}

/**
 * Whether this event was stamped by a device's own wall clock rather than the host's.
 *
 * That clock drifts from the host's by whole seconds, so these events get the same treatment
 * MaestroDriverLog does: they render on the Device lane, they are never nested into the host tree,
 * and they are excluded from the session window — folding their skew into t0/t1 would shift or
 * stretch the entire profile by the drift. `SessionTraceFile.merge` stamps the flag at the endpoint
 * that received the upload, because only that endpoint knows where the batch came from.
 */
function isDeviceClockEvent(event: TrailblazeTraceEvent): boolean {
  return event?.clock === 'device';
}

/**
 * The single accept predicate for trace events — used by BOTH the span builder and the session
 * window bounds, so an event that produces no span can never move t0/t1 either. Accepts only "X"
 * (Complete) events with a finite timestamp and a finite non-negative duration; returns their
 * microsecond values, or null.
 */
function acceptTraceEvent(event: TrailblazeTraceEvent): { tsUs: number; durUs: number } | null {
  if (!event || typeof event !== 'object') return null;
  if (event.ph !== undefined && event.ph !== 'X') return null;
  const tsUs = event.ts;
  const durUs = event.dur;
  if (typeof tsUs !== 'number' || !Number.isFinite(tsUs)) return null;
  if (typeof durUs !== 'number' || !Number.isFinite(durUs) || durUs < 0) return null;
  return { tsUs, durUs };
}

/**
 * Build the spans for one session's `trace.json` — the TrailblazeTracer Complete events. Exported
 * for tests.
 *
 * `ts`/`dur` are microseconds (epoch / elapsed), so both are divided down to the ms offsets the
 * rest of the contract uses. Only events passing [acceptTraceEvent] become spans; anything else
 * (metadata, instant, async pairs, missing duration) is skipped. The tracer folds a thrown
 * exception into `args.error`, which becomes the span's failure.
 *
 * [order0] continues the file-order counter the log spans used, so the nest sort's final tie-break
 * stays a total order across both sources.
 */
function buildTraceSpans(events: TrailblazeTraceEvent[], t0: number, order0: number): MutableSpan[] {
  const spans: MutableSpan[] = [];
  events.forEach((event, i) => {
    const accepted = acceptTraceEvent(event);
    if (!accepted) return;
    const deviceClock = isDeviceClockEvent(event);
    const s = accepted.tsUs / 1000 - t0;
    const dur = accepted.durUs / 1000;
    const cat = typeof event.cat === 'string' && event.cat ? event.cat : null;
    const bare = typeof event.name === 'string' && event.name ? event.name : 'trace';
    // Category-qualified, matching the `maestro.tap` / `driver.Tap` convention the log-derived
    // names use — bare tracer names ("TapPoint", "contentDescriptor") collide across categories,
    // and the bottom-up table aggregates by (kind, name).
    const name = cat && cat !== 'app' ? `${cat}.${bare}` : bare;
    const args = event.args && typeof event.args === 'object' ? event.args : {};
    const err = typeof args.error === 'string' && args.error ? args.error : null;
    const detail = Object.keys(args)
      .filter((key) => key !== 'error' && key !== 'async')
      .map((key) => `${key}=${args[key]}`)
      .join(' ');
    spans.push({
      id: 0,
      name,
      // A device-clock event is a driver span: same lane, same exclusion from the host tree, same
      // whole-duration self accounting as the MaestroDriverLog spans it sits beside.
      kind: deviceClock ? 'driver' : 'trace',
      s,
      e: s + dur,
      dur,
      self: deviceClock ? dur : 0,
      selfSegs: [],
      effS: s,
      effE: s + dur,
      depth: 0,
      parent: null,
      kids: [],
      step: null,
      ok: err == null,
      err,
      detail: truncate(detail, 80),
      args: compactArgs(args),
      budget: null,
      cost: null,
      tokens: null,
      shot: null,
      pid: typeof event.pid === 'number' ? event.pid : null,
      tid: typeof event.tid === 'number' ? event.tid : null,
      cat,
      spanKind: typeof event.kind === 'string' && event.kind ? event.kind : null,
      order: order0 + i,
      async: args.async === 'true',
      sid: typeof event.sid === 'string' && event.sid ? event.sid : null,
      psid: typeof event.psid === 'string' && event.psid ? event.psid : null,
      trid: typeof event.trid === 'string' && event.trid ? event.trid : null,
    });
  });
  return spans;
}

/** Log-vs-log containment: NEST_EPSILON_MS of overhang allowed on both edges. */
function logContains(outer: MutableSpan, inner: MutableSpan): boolean {
  return inner.s >= outer.s - NEST_EPSILON_MS && inner.e <= outer.e + NEST_EPSILON_MS;
}

/**
 * Whether trace span [outer] may PARENT trace span [inner]: the nesting must be provably lexical.
 * Exact containment (no epsilon — an enclosing `trace { }` block contains its inner block by
 * construction), same process AND thread (a tid is only unique within its pid, and concurrent
 * work overlapping in time is not nested work), and neither is an async observation (its (pid,
 * tid) is where the event was recorded, not where the work ran, so same-thread containment among
 * async events is coincidence).
 */
function traceCanParent(outer: MutableSpan, inner: MutableSpan): boolean {
  if (inner.s < outer.s || inner.e > outer.e) return false;
  if (outer.trid !== inner.trid) return false;
  if (outer.pid !== inner.pid || outer.tid !== inner.tid) return false;
  if (outer.async || inner.async) return false;
  return true;
}

/**
 * Nest tree spans by containment, then run the clamp sweep that assigns effective intervals +
 * exact self-time segments. Mutates the spans in place; returns root ids.
 *
 * Two-layer nesting (see the header):
 *
 * 1. LOG spans nest among log spans (validated against real sessions): process sorted by
 *    (s asc, e desc, file order asc) with a stack, popping while the current span is not contained
 *    in the stack top (NEST_EPSILON_MS of overhang allowed on both edges). A partially-overlapping
 *    span therefore pops its would-be parent and becomes a sibling further up (or a root). A trace
 *    can never change this layer — the tool hierarchy is identical with or without a trace.json.
 * 2. TRACE spans nest among trace spans. A span the tracer gave an id (`sid`) uses its DECLARED
 *    parent (`psid`) — no inference, and no parent declared means it really is a root. Only spans
 *    with no id fall back to [traceCanParent] inference over the same stack walk, where the parent
 *    is the nearest enclosing stack entry the predicate accepts (an enclosing span from another
 *    thread still occludes deeper candidates that could not have called the current span either).
 * 3. Each trace ROOT attaches under the innermost log span containing its MIDPOINT. Midpoint, not
 *    containment: a dispatch-layer wrapper trace opens before the tool log's start stamp and
 *    closes after it, so it CONTAINS the tool it belongs inside — a containment rule would either
 *    invert the hierarchy (trace parenting the tool) or orphan it into a root that the root
 *    de-overlap would starve. As a child, the clamp sweep trims its overhang to the parent.
 */
function nestAndAccount(spans: MutableSpan[]): number[] {
  const roots: MutableSpan[] = [];
  const attach = (sp: MutableSpan, parent: MutableSpan | null): void => {
    if (parent) {
      sp.parent = parent.id;
      sp.depth = parent.depth + 1;
      parent.kids.push(sp.id);
    } else {
      sp.depth = 0;
      roots.push(sp);
    }
  };

  // Layer 1: the log tree.
  const logTree = spans.filter((sp) => sp.kind !== 'driver' && sp.kind !== 'trace');
  logTree.sort((a, b) => a.s - b.s || b.e - a.e || a.order - b.order);
  const logStack: MutableSpan[] = [];
  for (const sp of logTree) {
    while (logStack.length && !logContains(logStack[logStack.length - 1], sp)) logStack.pop();
    attach(sp, logStack.length ? logStack[logStack.length - 1] : null);
    logStack.push(sp);
  }

  // Layer 2: the trace forest. Resolve every parent first, then derive depth — a declared parent
  // can sit anywhere in the sort order, so depth cannot be assigned during the walk.
  const traceSpans = spans.filter((sp) => sp.kind === 'trace');
  traceSpans.sort((a, b) => a.s - b.s || b.e - a.e || a.order - b.order);
  // Keyed by trace as well as span: a span id is only unique within its trace, so a bare `sid`
  // map would let a declared parent resolve to a same-id span from a different recording.
  const spanKey = (trid: string | null, sid: string) => `${trid ?? ''}\u0000${sid}`;
  const bySid = new Map<string, MutableSpan>();
  for (const sp of traceSpans) if (sp.sid != null) bySid.set(spanKey(sp.trid, sp.sid), sp);
  const traceParent = new Map<number, MutableSpan>();
  // One containment stack PER TRACE. A single shared stack is popped by whichever trace's span
  // comes next in time, so a span from a partially-overlapping second trace would evict the
  // still-open ancestors of the first — and the id-less events that depend on inference are exactly
  // the ones that then come out as roots.
  const traceStacks = new Map<string, MutableSpan[]>();
  for (const sp of traceSpans) {
    const traceOf = sp.trid ?? '';
    let traceStack = traceStacks.get(traceOf);
    if (traceStack === undefined) traceStacks.set(traceOf, (traceStack = []));
    while (traceStack.length) {
      const top = traceStack[traceStack.length - 1];
      if (sp.s >= top.s && sp.e <= top.e) break;
      traceStack.pop();
    }
    // The stack is maintained for every span (an id-bearing span is still a valid enclosing
    // candidate for an id-less one), but only consulted when this span declares nothing.
    let parent: MutableSpan | null = null;
    if (sp.sid != null) {
      parent = (sp.psid != null ? bySid.get(spanKey(sp.trid, sp.psid)) : undefined) ?? null;
    } else {
      for (let i = traceStack.length - 1; i >= 0; i--) {
        if (traceCanParent(traceStack[i], sp)) { parent = traceStack[i]; break; }
      }
    }
    if (parent && parent !== sp) traceParent.set(sp.id, parent);
    traceStack.push(sp);
  }

  // Wire the edges, dropping any that would close a cycle — a declared parent is producer data,
  // and a cycle would make the depth walk and the clamp sweep recurse forever.
  const traceRoots: MutableSpan[] = [];
  const ancestorOf = (candidate: MutableSpan, sp: MutableSpan): boolean => {
    const seen = new Set<number>();
    let cursor: MutableSpan | undefined = candidate;
    while (cursor && !seen.has(cursor.id)) {
      if (cursor === sp) return true;
      seen.add(cursor.id);
      cursor = traceParent.get(cursor.id);
    }
    return false;
  };
  for (const sp of traceSpans) {
    const parent = traceParent.get(sp.id);
    if (parent && !ancestorOf(parent, sp)) {
      sp.parent = parent.id;
      parent.kids.push(sp.id);
    } else {
      traceParent.delete(sp.id);
      traceRoots.push(sp);
    }
  }
  // Depth within the forest, relative to its own root; rebased on attachment in layer 3. Walks
  // each chain once and memoizes, so a long chain costs O(chain) total rather than per span.
  const depthMemo = new Map<number, number>();
  const traceDepth = (sp: MutableSpan): number => {
    const cached = depthMemo.get(sp.id);
    if (cached !== undefined) return cached;
    const chain: MutableSpan[] = [];
    let cursor: MutableSpan | undefined = sp;
    while (cursor && !depthMemo.has(cursor.id)) {
      chain.push(cursor);
      cursor = traceParent.get(cursor.id);
    }
    let depth = cursor ? depthMemo.get(cursor.id)! : -1;
    for (let i = chain.length - 1; i >= 0; i--) depthMemo.set(chain[i].id, ++depth);
    return depthMemo.get(sp.id)!;
  };
  for (const sp of traceSpans) sp.depth = traceDepth(sp);

  // Layer 3: attach each trace root under the innermost log span containing its midpoint.
  const byId = new Map(spans.map((sp) => [sp.id, sp]));
  const rebase = (sp: MutableSpan, offset: number): void => {
    sp.depth += offset;
    for (const id of sp.kids) rebase(byId.get(id)!, offset);
  };
  for (const root of traceRoots) {
    const mid = (root.s + root.e) / 2;
    let host: MutableSpan | null = null;
    for (const log of logTree) {
      if (log.s <= mid && mid <= log.e && (!host || log.depth > host.depth)) host = log;
    }
    if (host) {
      root.parent = host.id;
      host.kids.push(root.id);
      rebase(root, host.depth + 1); // forest depths are relative (root = 0); shift the subtree
    } else {
      roots.push(root);
    }
  }

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
 *
 * [traceEvents] is the session's `trace.json` (empty for sessions that recorded none). Tracer
 * events recorded on the host share the logs' wall clock, so they bound the window like any other
 * host-clock span. Ones a device recorded and uploaded carry `clock: "device"` and are excluded
 * from both bounds, exactly as MaestroDriverLog is — see [isDeviceClockEvent].
 */
function extractPerfSession(rawLogs: TrailblazeLogRecord[], traceEvents: TrailblazeTraceEvent[] = []): PerfSessionData | null {
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
  // Same accept predicate as buildTraceSpans, so an event that produces no span cannot move the
  // window either.
  for (const event of traceEvents) {
    // Device-clock events are excluded for the same reason MaestroDriverLog is, above: their skew
    // is not elapsed time, and letting it bound the window shifts or stretches the whole profile.
    if (isDeviceClockEvent(event)) continue;
    const accepted = acceptTraceEvent(event);
    if (!accepted) continue;
    hostBounds.push(accepted.tsUs / 1000);
    hostBounds.push((accepted.tsUs + accepted.durUs) / 1000);
  }
  if (!hostBounds.length) return null;
  const t0 = Math.min(...hostBounds);
  const t1 = Math.max(...hostBounds) - t0;

  // TraceIds of the request logs, for the paired-sampling dedupe (see buildRawSpans kdoc).
  const llmRequestTraceIds = new Set<string>(
    logs.filter((l) => logClass(l) === 'TrailblazeLlmRequestLog' && l.traceId).map((l) => String(l.traceId)),
  );
  const spans = buildRawSpans(logs, t0, llmRequestTraceIds);
  spans.push(...buildTraceSpans(traceEvents, t0, logs.length));
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

  const clean = spans.map(({ order, async, sid, psid, trid, ...rest }) => rest);
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
  buildTraceSpans,
  extractPerfSession,
  parsePerfTimestamp,
  timeoutBudgetMs,
};
