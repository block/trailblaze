// Unit tests for the performance-analysis extraction layer (perf-extract.ts). Run with
// `bun test` (auto-discovered by the repo's TypeScript unit-test CI leg).
//
// These pin the span-model invariants verified against the Kotlin log writers and real CI
// sessions:
//  - spans are START-anchored: [timestamp, timestamp + durationMs)
//  - nesting is interval containment with a small epsilon; partial overlap promotes to sibling
//  - Σ self time over the tree == union coverage of the roots, exactly (clamp sweep)
//  - MaestroDriverLog (device clock) never enters the containment tree
//  - timeout budgets come from any /timeout/i numeric raw-arg key
//  - gaps are root-union holes above the threshold
import { describe, expect, test } from 'bun:test';
import {
  GAP_MIN_MS,
  NEST_EPSILON_MS,
  bottomUpAggregate,
  buildTraceSpans,
  extractPerfSession,
  parsePerfTimestamp,
  timeoutBudgetMs,
} from './perf-extract';

const T0 = Date.parse('2026-07-27T10:00:00.000Z');

function iso(offsetMs: number): string {
  return new Date(T0 + offsetMs).toISOString();
}

/** A minimal TrailblazeToolLog-shaped record whose span is [start, start + dur). */
function toolLog(name: string, startMs: number, durMs: number, extra: Record<string, unknown> = {}): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeToolLog',
    toolName: name,
    timestamp: iso(startMs),
    durationMs: durMs,
    successful: true,
    trailblazeTool: { raw: {} },
    ...extra,
  };
}

function llmLog(startMs: number, durMs: number, extra: Record<string, unknown> = {}): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog',
    llmRequestLabel: 'Planner',
    timestamp: iso(startMs),
    durationMs: durMs,
    ...extra,
  };
}

function driverLog(startMs: number, durMs: number): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.MaestroDriverLog',
    action: { class: 'maestro.orchestra.Action.Tap' },
    timestamp: iso(startMs),
    durationMs: durMs,
  };
}

function objectiveStart(atMs: number, step: string): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveStartLog',
    timestamp: iso(atMs),
    promptStep: { step },
  };
}

function objectiveComplete(atMs: number, step: string, failed = false): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog',
    timestamp: iso(atMs),
    promptStep: { step },
    objectiveResult: failed
      ? { class: 'AgentTaskStatus.Failure.ObjectiveFailed', llmExplanation: 'could not find it', statusData: { callCount: 4 } }
      : { class: 'AgentTaskStatus.Success.ObjectiveComplete', statusData: { callCount: 2 } },
  };
}

/** Anchor log so t0 is deterministic across fixtures. */
function sessionAnchor(offsetMs = 0): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeSessionStatusChangeLog',
    timestamp: iso(offsetMs),
    sessionStatus: { class: 'SessionStatus.Started' },
  };
}

/**
 * One TrailblazeTracer Complete event, the shape trace.json carries: MICROsecond ts/dur, and a
 * (pid, tid) identifying the emitting thread.
 */
function traceEvent(
  name: string,
  startMs: number,
  durMs: number,
  extra: Partial<TrailblazeTraceEvent> = {},
): TrailblazeTraceEvent {
  return {
    name,
    cat: 'app',
    ph: 'X',
    ts: (T0 + startMs) * 1000,
    dur: durMs * 1000,
    pid: 7,
    tid: 1,
    args: {},
    ...extra,
  };
}

/** Σ self over every span (tree spans only) — must equal root union coverage exactly. */
function treeSelfSum(data: PerfSessionData): number {
  return data.spans.filter((sp) => sp.kind !== 'driver').reduce((sum, sp) => sum + sp.self, 0);
}

describe('span anchoring', () => {
  test('a span is start-anchored: [timestamp, timestamp + durationMs)', () => {
    const data = extractPerfSession([sessionAnchor(0), toolLog('tapOn', 600, 400)])!;
    const span = data.spans.find((sp) => sp.name === 'tapOn')!;
    expect(span.s).toBe(600);
    expect(span.e).toBe(1_000);
    expect(span.dur).toBe(400);
  });

  test('nanosecond-precision timestamps parse (fraction trimmed to ms)', () => {
    expect(parsePerfTimestamp('2026-07-27T10:00:00.123456789Z')).toBe(Date.parse('2026-07-27T10:00:00.123Z'));
    expect(parsePerfTimestamp(undefined)).toBeNull();
    expect(parsePerfTimestamp('not a date')).toBeNull();
  });

  test('a long span extends the window past the last log timestamp (its log is written at start)', () => {
    // Real-session shape: a 77s trailhead tool's log lands moments after the session anchor and
    // its duration runs past every later log timestamp — the window must end at the span end.
    const data = extractPerfSession([sessionAnchor(0), toolLog('signInToWorkspace', 300, 77_000), toolLog('tapOn', 78_000, 400)])!;
    const trailhead = data.spans.find((sp) => sp.name === 'signInToWorkspace')!;
    expect(trailhead.s).toBe(300);
    expect(trailhead.e).toBe(77_300);
    expect(data.t0).toBe(T0);
    expect(data.t1).toBe(78_400);
    expect(data.covered).toBeLessThanOrEqual(data.t1);
    expect(data.covered).toBe(77_000 + 400);
  });

  test('t0/t1 come from host-clock logs only (device-clock driver logs cannot stretch the window)', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('tapOn', 600, 400),
      driverLog(9_000_000, 100), // seconds of device-clock skew
    ])!;
    expect(data.t1).toBe(1_000);
  });
});

describe('containment nesting', () => {
  test('a contained span nests under its container; partial overlap promotes to sibling root', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('outer', 0, 1_000),         // [0, 1000)
      toolLog('inner', 200, 300),         // [200, 500)  → child of outer
      toolLog('overlapper', 900, 600),    // [900, 1500) → overlaps outer's tail → sibling root
    ])!;
    const outer = data.spans.find((sp) => sp.name === 'outer')!;
    const inner = data.spans.find((sp) => sp.name === 'inner')!;
    const overlapper = data.spans.find((sp) => sp.name === 'overlapper')!;
    expect(inner.parent).toBe(outer.id);
    expect(inner.depth).toBe(1);
    expect(overlapper.parent).toBeNull();
    expect(data.roots).toEqual([outer.id, overlapper.id]);
  });

  test('a child overhanging its parent by <= the epsilon still nests (and is clamped)', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('parent', 0, 1_000),      // [0, 1000)
      toolLog('child', 502, 500),       // [502, 1002) — 2ms overhang
    ])!;
    const parent = data.spans.find((sp) => sp.name === 'parent')!;
    const child = data.spans.find((sp) => sp.name === 'child')!;
    expect(child.parent).toBe(parent.id);
    expect(child.effE).toBe(1_000); // clamped into the parent
    expect(treeSelfSum(data)).toBeCloseTo(data.covered, 6);
  });

  test('the epsilon boundary is exact: overhang == epsilon nests, epsilon + 1 promotes to sibling', () => {
    const atLimit = extractPerfSession([
      sessionAnchor(0),
      toolLog('parent', 0, 1_000),
      toolLog('child', 500, 500 + NEST_EPSILON_MS), // ends exactly epsilon past the parent
    ])!;
    expect(atLimit.spans.find((sp) => sp.name === 'child')!.parent)
      .toBe(atLimit.spans.find((sp) => sp.name === 'parent')!.id);
    const pastLimit = extractPerfSession([
      sessionAnchor(0),
      toolLog('parent', 0, 1_000),
      toolLog('child', 500, 500 + NEST_EPSILON_MS + 1),
    ])!;
    expect(pastLimit.spans.find((sp) => sp.name === 'child')!.parent).toBeNull();
  });

  test('deep delegation chains keep their depth', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('a', 0, 10_000),
      toolLog('b', 1_000, 8_000),
      toolLog('c', 2_000, 6_000),
      toolLog('d', 3_000, 4_000),
    ])!;
    const depths = Object.fromEntries(data.spans.map((sp) => [sp.name, sp.depth]));
    expect(depths).toEqual({ a: 0, b: 1, c: 2, d: 3 });
  });
});

describe('self-time accounting', () => {
  test('self = own duration minus children; Σ self == union coverage of roots', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('root', 0, 10_000),     // [0, 10000)
      toolLog('kidA', 1_000, 3_000),  // [1000, 4000)
      toolLog('kidB', 5_000, 4_000),  // [5000, 9000)
    ])!;
    const root = data.spans.find((sp) => sp.name === 'root')!;
    expect(root.self).toBe(3_000); // 0-1000, 4000-5000, 9000-10000
    expect(root.selfSegs).toEqual([[0, 1_000], [4_000, 5_000], [9_000, 10_000]]);
    expect(treeSelfSum(data)).toBeCloseTo(data.covered, 6);
    expect(data.covered).toBe(10_000);
  });

  test('overlapping siblings are de-overlapped deterministically (earlier start wins)', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('parent', 0, 10_000),
      toolLog('first', 1_000, 4_000),   // [1000, 5000)
      toolLog('second', 4_000, 4_000),  // [4000, 8000) — overlaps first's tail by 1s
    ])!;
    const second = data.spans.find((sp) => sp.name === 'second')!;
    expect(second.effS).toBe(5_000); // head clamped to first's end
    expect(treeSelfSum(data)).toBeCloseTo(data.covered, 6);
  });

  test('overlapping roots keep union coverage exact', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('r1', 0, 5_000),      // [0, 5000)
      toolLog('r2', 4_000, 5_000),  // [4000, 9000) — overlaps r1 by 1s
    ])!;
    expect(data.covered).toBe(9_000);
    expect(treeSelfSum(data)).toBeCloseTo(9_000, 6);
  });

  test('property: randomized span soup keeps Σ self == union coverage (seeded)', () => {
    // Deterministic LCG so the fixture is reproducible.
    let seed = 42;
    const rand = () => (seed = (seed * 1103515245 + 12345) % 2 ** 31) / 2 ** 31;
    const logs: TrailblazeLogRecord[] = [sessionAnchor(0)];
    for (let i = 0; i < 200; i++) {
      const start = 1_000 + Math.floor(rand() * 120_000);
      const dur = 1 + Math.floor(rand() * 30_000);
      logs.push(toolLog(`t${i % 13}`, start, dur));
    }
    const data = extractPerfSession(logs)!;
    expect(treeSelfSum(data)).toBeCloseTo(data.covered, 3);
    // And the full-range bottom-up over the tree sums to the same total.
    const bottomUp = bottomUpAggregate(data.spans, -Infinity, Infinity);
    expect(bottomUp.reduce((sum, row) => sum + row.self, 0)).toBeCloseTo(data.covered, 3);
  });

  test('extraction is deterministic for identical input', () => {
    const logs = [
      sessionAnchor(0),
      toolLog('a', 0, 3_000),
      toolLog('b', 1_000, 1_000),
      toolLog('b', 2_000, 1_000),
      llmLog(3_500, 1_500),
    ];
    expect(extractPerfSession(logs)).toEqual(extractPerfSession(logs));
  });
});

describe('clock domains', () => {
  test('MaestroDriverLog spans never enter the containment tree', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('outer', 0, 10_000),
      driverLog(3_000, 2_000), // would be "contained" — but device clock, so kept out
    ])!;
    const driver = data.spans.find((sp) => sp.kind === 'driver')!;
    expect(driver.parent).toBeNull();
    expect(driver.depth).toBe(0);
    const outer = data.spans.find((sp) => sp.name === 'outer')!;
    expect(outer.kids).toEqual([]);
    expect(data.roots).toEqual([outer.id]);
    expect(data.covered).toBe(10_000); // driver span contributes nothing to tree coverage
  });

  test('non-span duration carriers never stretch the session window', () => {
    // TrailblazeAgentTaskStatusChangeLog stamps at status-change time with the task's TOTAL
    // duration — ts + dur would push t1 out by nearly the whole session and fabricate a tail gap.
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('tap', 0, 1_000),
      {
        class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeAgentTaskStatusChangeLog',
        timestamp: iso(1_000),
        durationMs: 60_000,
        agentTaskStatus: { statusData: { totalDurationMs: 60_000 } },
      },
    ])!;
    expect(data.t1).toBe(1_000);
    expect(data.gaps).toEqual([]);
  });

  test('a device-clock trace event goes on the Device lane, not into the tree', () => {
    // The device's own trace.json half. Before it was marked, the extractor read it as an ordinary
    // host span: it rendered in Tools and its parent-by-containment was whatever host tool happened
    // to span the same wall-clock window.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('outer', 0, 10_000)],
      [traceEvent('a11y-capture', 3_000, 2_000, { clock: 'device' })],
    )!;
    const device = data.spans.find((sp) => sp.name === 'a11y-capture')!;
    expect(device.kind).toBe('driver');
    expect(device.parent).toBeNull();
    const outer = data.spans.find((sp) => sp.name === 'outer')!;
    expect(outer.kids).toEqual([]);
    expect(data.covered).toBe(10_000);
  });

  test("a device clock running seconds ahead does not stretch the session window", () => {
    // The concrete harm: a device 30s ahead of the host turns a 1s run into a 31s profile, and
    // every host span collapses into a sliver at the left edge.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tap', 0, 1_000)],
      [traceEvent('a11y-capture', 30_000, 500, { clock: 'device' })],
    )!;
    expect(data.t1).toBe(1_000);
  });

  test('a host-clock trace event still bounds the window', () => {
    // The other side of the same gate: excluding device events must not start excluding the host
    // spans that are the whole reason trace.json bounds the window at all.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tap', 0, 1_000)],
      [traceEvent('slow-http', 2_000, 3_000)],
    )!;
    expect(data.t1).toBe(5_000);
    expect(data.spans.find((sp) => sp.name === 'slow-http')!.kind).toBe('trace');
  });
});

describe('timeout tax', () => {
  test('budget comes from any /timeout/i numeric raw key', () => {
    expect(timeoutBudgetMs({ timeoutMs: 10_000 })).toBe(10_000);
    expect(timeoutBudgetMs({ waitToSettleTimeoutMs: 5_000 })).toBe(5_000);
    expect(timeoutBudgetMs({ TIMEOUT: 3_000 })).toBe(3_000);
    expect(timeoutBudgetMs({ timeoutMs: 'soon' })).toBeNull();
    expect(timeoutBudgetMs({ retries: 3 })).toBeNull();
    expect(timeoutBudgetMs(null)).toBeNull();
  });

  test('full burns are flagged; fast passes are not', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('block_dismissIfPresent', 0, 10_600, { trailblazeTool: { raw: { timeoutMs: 10_000 } } }),
      toolLog('findMatches', 12_000, 158, { trailblazeTool: { raw: { timeoutMs: 20_000 } } }),
      toolLog('assertVisible', 20_000, 17_900, { successful: false, trailblazeTool: { raw: { timeoutMs: 15_000 } } }),
    ])!;
    expect(data.tax.map((t) => [t.name, t.full])).toEqual([
      ['assertVisible', true],
      ['block_dismissIfPresent', true],
      ['findMatches', false],
    ]);
    expect(data.taxFullBurn).toBe(10_600 + 17_900);
    const failed = data.tax.find((t) => t.name === 'assertVisible')!;
    expect(failed.ok).toBe(false);
  });
});

describe('gaps', () => {
  test('root-union holes above the threshold become gaps with flanking span names', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('first', 0, 2_000),      // [0, 2000)
      toolLog('second', 3_000, 2_000), // [3000, 5000) → 1s gap
      { ...sessionAnchor(6_000), sessionStatus: { class: 'SessionStatus.Ended.Succeeded' } }, // tail gap
    ])!;
    expect(data.gaps).toEqual([
      { s: 2_000, e: 3_000, dur: 1_000, before: 'first', after: 'second' },
      { s: 5_000, e: 6_000, dur: 1_000, before: 'second', after: null },
    ]);
    expect(data.gapTotal).toBe(2_000);
  });

  test(`gaps at or below ${GAP_MIN_MS}ms are noise, not rows`, () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('first', 0, 2_000),
      toolLog('second', 2_200, 2_000), // 200ms gap
    ])!;
    expect(data.gaps).toEqual([]);
  });
});

describe('steps', () => {
  test('ObjectiveStart/Complete pair into step spans with failure + call count', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      objectiveStart(100, 'Sign in'),
      toolLog('signIn', 1_000, 3_000),
      objectiveComplete(5_000, 'Sign in'),
      objectiveStart(5_100, 'Buy bitcoin'),
      toolLog('tapOn', 7_000, 1_000),
      objectiveComplete(9_000, 'Buy bitcoin', true),
    ])!;
    expect(data.steps).toHaveLength(2);
    expect(data.steps[0]).toMatchObject({ label: 'Sign in', s: 100, e: 5_000, ok: true, calls: 2 });
    expect(data.steps[1]).toMatchObject({ label: 'Buy bitcoin', ok: false, calls: 4 });
    expect(data.steps[1].err).toContain('could not find it');
    // Spans are attributed to the step whose window contains their start.
    expect(data.spans.find((sp) => sp.name === 'signIn')!.step).toBe(0);
    expect(data.spans.find((sp) => sp.name === 'tapOn')!.step).toBe(1);
  });

  test('an unmatched ObjectiveStart stays open (e = null)', () => {
    const data = extractPerfSession([sessionAnchor(0), objectiveStart(100, 'Never finishes')])!;
    expect(data.steps[0].e).toBeNull();
  });

  test('input order does not matter (report input is filename-sorted, not time-sorted)', () => {
    const ordered = [
      sessionAnchor(0),
      objectiveStart(100, 'Sign in'),
      toolLog('signIn', 1_000, 3_000),
      objectiveComplete(5_000, 'Sign in'),
    ];
    const shuffled = [ordered[3], ordered[1], ordered[0], ordered[2]];
    expect(extractPerfSession(shuffled)).toEqual(extractPerfSession(ordered));
    expect(extractPerfSession(shuffled)!.steps[0]).toMatchObject({ e: 5_000, ok: true });
  });

  test('trailhead steps roll up into trailheadMs', () => {
    const head = { step: 'Launch signed in', isTrailhead: true };
    const data = extractPerfSession([
      sessionAnchor(0),
      { class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveStartLog', timestamp: iso(100), promptStep: head },
      { class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.ObjectiveCompleteLog', timestamp: iso(5_100), promptStep: head, objectiveResult: { class: 'AgentTaskStatus.Success.ObjectiveComplete' } },
      objectiveStart(5_200, 'Buy bitcoin'),
      objectiveComplete(9_200, 'Buy bitcoin'),
    ])!;
    expect(data.steps[0].trailhead).toBe(true);
    expect(data.steps[1].trailhead).toBe(false);
    expect(data.trailheadMs).toBe(5_000);
  });
});

describe('llm + session facts', () => {
  test('llm spans carry cost/tokens and roll up into session totals', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      llmLog(1_000, 2_000, { llmRequestUsageAndCost: { inputTokens: 1200, outputTokens: 80, promptCost: 0.01, completionCost: 0.002 } }),
      llmLog(5_000, 1_000, { llmRequestUsageAndCost: { inputTokens: 900, outputTokens: 40, totalCost: 0.05 } }),
    ])!;
    expect(data.llmCount).toBe(2);
    expect(data.llmTotalMs).toBe(3_000);
    expect(data.llmCostUsd).toBeCloseTo(0.062, 6);
    const first = data.spans.find((sp) => sp.tokens === '1200→80')!;
    expect(first.cost).toBeCloseTo(0.012, 6);
    expect(first.name).toBe('LLM · Planner');
  });

  test('a session with no host-clock timestamps yields null', () => {
    expect(extractPerfSession([])).toBeNull();
    expect(extractPerfSession([driverLog(1_000, 100)])).toBeNull();
  });
});

/** McpSamplingLog is END-anchored: timestamp is stamped after the call, span is [ts - dur, ts). */
function samplingLog(endMs: number, durMs: number, extra: Record<string, unknown> = {}): TrailblazeLogRecord {
  return {
    class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.McpSamplingLog',
    timestamp: iso(endMs),
    durationMs: durMs,
    modelName: 'gpt-test',
    successful: true,
    usageAndCost: { inputTokens: 500, outputTokens: 20, totalCost: 0.03 },
    ...extra,
  };
}

describe('MCP sampling logs (the end-anchored exception)', () => {
  test('a solo sampling log becomes an end-anchored LLM span with its usage rolled up', () => {
    const data = extractPerfSession([sessionAnchor(0), toolLog('tapOn', 0, 400), samplingLog(5_000, 3_000)])!;
    const llm = data.spans.find((sp) => sp.kind === 'llm')!;
    expect(llm.s).toBe(2_000);
    expect(llm.e).toBe(5_000);
    expect(llm.name).toBe('LLM · gpt-test');
    expect(data.llmCount).toBe(1);
    expect(data.llmTotalMs).toBe(3_000);
    expect(data.llmCostUsd).toBeCloseTo(0.03, 6);
  });

  test('a sampling log cannot stretch the window past its own timestamp', () => {
    const data = extractPerfSession([sessionAnchor(0), samplingLog(5_000, 3_000)])!;
    expect(data.t1).toBe(5_000);
  });

  test('a sampling log paired with a request log (shared traceId) is not double-counted', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      llmLog(1_000, 2_000, { traceId: 'trace-1', llmRequestUsageAndCost: { inputTokens: 900, outputTokens: 40, totalCost: 0.05 } }),
      samplingLog(3_000, 2_000, { traceId: 'trace-1' }),
    ])!;
    const llm = data.spans.filter((sp) => sp.kind === 'llm');
    expect(llm).toHaveLength(1);
    expect(llm[0].name).toBe('LLM · Planner');
    expect(data.llmCostUsd).toBeCloseTo(0.05, 6);
    expect(data.t1).toBe(3_000);
  });
});

describe('tracer spans (trace.json)', () => {
  test('a traced call nests under the tool that made it and takes its self time', () => {
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('assertVisible', 0, 1_000)],
      [traceEvent('findMatches', 200, 600, { cat: 'viewmatcher' })],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'assertVisible')!;
    const traced = data.spans.find((sp) => sp.kind === 'trace')!;
    expect(traced.name).toBe('viewmatcher.findMatches');
    expect(traced.parent).toBe(tool.id);
    expect(traced.dur).toBe(600);
    // The tool's own time is now only what the traced call does not explain.
    expect(tool.self).toBe(400);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('trace-vs-trace containment is exact: a sub-epsilon overhang is a sibling, not a child', () => {
    // The later event overhangs the earlier one's end by NEST_EPSILON_MS/2 — the log-derived
    // epsilon would swallow that and nest them, but two tracer spans on one thread nest only when
    // they truly nest. Same offsets as log records DO nest (asserted below), so this pins the
    // difference in treatment, not the geometry.
    const overhang = NEST_EPSILON_MS / 2;
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('outer-tool', 0, 2_000)],
      [
        traceEvent('outer-traced', 500, 500, { tid: 1 }),
        traceEvent('inner-traced', 600, 400 + overhang, { tid: 1 }),
      ],
    )!;
    const outerTraced = data.spans.find((sp) => sp.name === 'outer-traced')!;
    const innerTraced = data.spans.find((sp) => sp.name === 'inner-traced')!;
    const tool = data.spans.find((sp) => sp.name === 'outer-tool')!;
    expect(innerTraced.parent).toBe(tool.id);
    expect(innerTraced.parent).not.toBe(outerTraced.id);
    expect(treeSelfSum(data)).toBe(data.covered);

    // Control: the same two intervals as LOG records nest, because their edges are separately
    // stamped clock reads and the epsilon exists for exactly that.
    const logDerived = extractPerfSession([
      sessionAnchor(0),
      toolLog('outer-tool', 0, 2_000),
      toolLog('outer-logged', 500, 500),
      toolLog('inner-logged', 600, 400 + overhang),
    ])!;
    const outerLogged = logDerived.spans.find((sp) => sp.name === 'outer-logged')!;
    const innerLogged = logDerived.spans.find((sp) => sp.name === 'inner-logged')!;
    expect(innerLogged.parent).toBe(outerLogged.id);
  });

  test('a traced call on another thread attaches to the enclosing tool, not to concurrent work', () => {
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('sendPrompt', 0, 3_000)],
      [
        traceEvent('contentDescriptor', 100, 2_000, { cat: 'MaestroDriver', tid: 1 }),
        // Runs inside the driver call in TIME, but on the HTTP client's own thread.
        traceEvent('POST https://api.openai.com/v1/chat/completions', 200, 900, { cat: 'http', tid: 2 }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'sendPrompt')!;
    const driver = data.spans.find((sp) => sp.name === 'MaestroDriver.contentDescriptor')!;
    const http = data.spans.find((sp) => sp.cat === 'http')!;
    expect(driver.parent).toBe(tool.id);
    expect(http.parent).toBe(tool.id);
    expect(http.tid).toBe(2);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('args land on the span, and args.error fails it', () => {
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 1_000)],
      [traceEvent('TapPoint', 100, 100, { cat: 'MaestroDriver', args: { x: '10', error: 'no such node' } })],
    )!;
    const traced = data.spans.find((sp) => sp.kind === 'trace')!;
    expect(traced.ok).toBe(false);
    expect(traced.err).toBe('no such node');
    expect(traced.detail).toBe('x=10');
    expect(traced.cat).toBe('MaestroDriver');
  });

  test('tracer events bound the session window like any other host-clock span', () => {
    // The last log ends at 1000ms; a traced call runs past it to 2500ms.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('launch', 0, 1_000)],
      [traceEvent('slow', 500, 2_000)],
    )!;
    expect(data.t1).toBe(2_500);
  });

  test('only Complete events with a finite duration become spans', () => {
    const spans = buildTraceSpans(
      [
        traceEvent('complete', 0, 10),
        { name: 'instant', cat: 'app', ph: 'i', ts: T0 * 1000, pid: 7, tid: 1 },
        { name: 'metadata', cat: '__metadata', ph: 'M', ts: T0 * 1000, pid: 7, tid: 1 },
        { name: 'no-duration', cat: 'app', ph: 'X', ts: T0 * 1000, pid: 7, tid: 1 },
      ],
      T0,
      0,
    );
    expect(spans.map((sp) => sp.name)).toEqual(['complete']);
  });

  test('a session with no trace.json profiles exactly as before', () => {
    const logs = [sessionAnchor(0), toolLog('outer', 0, 1_000), toolLog('inner', 100, 500)];
    expect(extractPerfSession(logs, [])).toEqual(extractPerfSession(logs)!);
  });

  test('a wrapper trace that CONTAINS its tool log becomes the tool\'s child, never its parent', () => {
    // Playwright's executePlaywrightTool trace opens before the tool log's start stamp and
    // closes after it — the trace interval contains the tool log's interval. The tool stays the
    // outer span; the wrapper attaches under it (midpoint rule) with its overhang clamped.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('playwrightTool', 100, 900)],
      [traceEvent('executePlaywrightTool', 50, 1_000, { cat: 'tool' })],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'playwrightTool')!;
    const wrapper = data.spans.find((sp) => sp.kind === 'trace')!;
    expect(tool.parent).toBeNull();
    expect(wrapper.parent).toBe(tool.id);
    // The wrapper's effective interval is clamped into the tool's; accounting still balances.
    expect(wrapper.effS).toBeGreaterThanOrEqual(tool.effS);
    expect(wrapper.effE).toBeLessThanOrEqual(tool.effE);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('a trace span never adopts a log-derived span it happens to enclose', () => {
    // A long http trace on another thread encloses a short maestro command in TIME; the command
    // must keep its log-derived parent — nothing log-derived ever nests under a trace span.
    const data = extractPerfSession(
      [
        sessionAnchor(0),
        toolLog('toolA', 0, 2_000),
        {
          class: 'xyz.block.trailblaze.logs.client.TrailblazeLog.MaestroCommandLog',
          maestroCommandJsonObj: { tapOnPoint: {} },
          timestamp: iso(400),
          durationMs: 100,
          successful: true,
        },
      ],
      [traceEvent('POST https://tracked.example/collect', 100, 900, { cat: 'http', tid: 2 })],
    )!;
    const toolA = data.spans.find((sp) => sp.name === 'toolA')!;
    const maestro = data.spans.find((sp) => sp.kind === 'maestro')!;
    const http = data.spans.find((sp) => sp.kind === 'trace')!;
    expect(maestro.parent).toBe(toolA.id);
    expect(http.parent).toBe(toolA.id);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('trace-vs-trace nesting requires the same pid, not just the same tid', () => {
    // Thread ids are only unique within a process: tid 1 in pid 7 and tid 1 in pid 8 are
    // different threads, so containment between them is concurrency, not calls.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tool', 0, 2_000)],
      [
        traceEvent('host-side', 100, 1_000, { pid: 7, tid: 1 }),
        traceEvent('device-side', 200, 500, { pid: 8, tid: 1 }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'tool')!;
    const inner = data.spans.find((sp) => sp.name === 'device-side')!;
    expect(inner.parent).toBe(tool.id);
    expect(inner.pid).toBe(8);
  });

  test('async-marked events never nest among trace spans, even same-thread exactly contained', () => {
    // The http emitters stamp events from a shared callback thread — same tid, overlapping
    // intervals, no call relationship. args.async pins them as observations: both attach to the
    // enclosing tool as siblings.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('navigate', 0, 3_000)],
      [
        traceEvent('GET https://app.example/page', 100, 2_000, { cat: 'http', args: { async: 'true' } }),
        traceEvent('GET https://app.example/api', 300, 400, { cat: 'http', args: { async: 'true' } }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'navigate')!;
    const [outer, inner] = data.spans.filter((sp) => sp.kind === 'trace');
    expect(outer.parent).toBe(tool.id);
    expect(inner.parent).toBe(tool.id);
    // The marker is plumbing, not payload: it never reaches the detail line.
    expect(outer.detail).not.toContain('async');
  });

  test('an event that produces no span cannot move the session window either', () => {
    // A duration-less "X" event 5s before the session must not drag t0 earlier: the window and
    // the span builder share one accept predicate.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('launch', 0, 1_000)],
      [{ name: 'stray', cat: 'app', ph: 'X', ts: (T0 - 5_000) * 1000, pid: 7, tid: 1 }],
    )!;
    expect(data.spans.filter((sp) => sp.kind === 'trace')).toHaveLength(0);
    expect(data.t1).toBe(1_000);
    const tool = data.spans.find((sp) => sp.name === 'launch')!;
    expect(tool.s).toBe(0);
  });
});

describe('declared span parentage (sid/psid)', () => {
  test('a declared parent wins over inference: a child on another thread still nests', () => {
    // The tracer carries its span across a suspension, so a child can resume on a different
    // thread. Inference refuses that (different tid = no proven call); the declaration knows.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('sendPrompt', 0, 3_000)],
      [
        traceEvent('llm-call', 200, 2_000, { sid: '1', tid: 1 }),
        traceEvent('http-post', 300, 1_800, { sid: '2', psid: '1', tid: 9 }),
      ],
    )!;
    const outer = data.spans.find((sp) => sp.name === 'llm-call')!;
    const inner = data.spans.find((sp) => sp.name === 'http-post')!;
    expect(inner.parent).toBe(outer.id);
    expect(inner.depth).toBe(outer.depth + 1);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('a span the tracer declared a root stays a root, even fully enclosed by another', () => {
    // sid present, psid absent means "the tracer looked and there was no enclosing frame" — not
    // "unknown". Inference would have nested this; the declaration overrules it.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 3_000)],
      [
        traceEvent('outer', 500, 1_000, { sid: '1', tid: 1 }),
        traceEvent('unrelated', 600, 200, { sid: '2', tid: 1 }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'tapOn')!;
    const outer = data.spans.find((sp) => sp.name === 'outer')!;
    const unrelated = data.spans.find((sp) => sp.name === 'unrelated')!;
    expect(unrelated.parent).toBe(tool.id);
    expect(unrelated.parent).not.toBe(outer.id);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('events with no sid still infer, alongside declared ones in the same trace', () => {
    // Mixed traces are the migration case: a producer that pushes events straight into the
    // recorder has no span identity, and must keep nesting the way it always did.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('assertVisible', 0, 3_000)],
      [
        traceEvent('declared-outer', 200, 1_000, { sid: '1', tid: 1 }),
        traceEvent('inferred-inner', 300, 400, { tid: 1 }),
      ],
    )!;
    const outer = data.spans.find((sp) => sp.name === 'declared-outer')!;
    const inner = data.spans.find((sp) => sp.name === 'inferred-inner')!;
    expect(inner.parent).toBe(outer.id);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('a psid naming an event missing from the trace becomes a root, not an orphan', () => {
    // Truncated or partially-exported traces are real: the parent may simply not be in the file.
    // The enclosing span is NOT a substitute — it is not the frame the tracer named.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 2_000)],
      [
        traceEvent('enclosing', 300, 800, { sid: '1', tid: 1 }),
        traceEvent('orphan', 400, 300, { sid: '9', psid: '4', tid: 1 }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'tapOn')!;
    const orphan = data.spans.find((sp) => sp.name === 'orphan')!;
    expect(orphan.parent).toBe(tool.id);
    expect(orphan.parent).not.toBe(data.spans.find((sp) => sp.name === 'enclosing')!.id);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('mutually-declared parents resolve instead of hanging the extractor', () => {
    // Declared parentage is producer data, so the extractor treats a cycle as corrupt input:
    // break one edge, keep both spans, and never recurse forever.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 2_000)],
      [
        traceEvent('a', 400, 500, { sid: '1', psid: '2', tid: 1 }),
        traceEvent('b', 450, 300, { sid: '2', psid: '1', tid: 1 }),
      ],
    )!;
    const traced = data.spans.filter((sp) => sp.kind === 'trace');
    expect(traced).toHaveLength(2);
    expect(traced.filter((sp) => sp.parent === traced.find((o) => o.name === 'a')!.id).length
      + traced.filter((sp) => sp.parent === traced.find((o) => o.name === 'b')!.id).length).toBe(1);
    expect(treeSelfSum(data)).toBe(data.covered);
  });

  test('a long declared chain gets exact depths without recursing per span', () => {
    // 300 spans, each declared inside the last. Depth is derived by walking each chain once and
    // memoizing, so this is linear rather than quadratic — and iterative, so it cannot blow the
    // stack on a deep trace.
    const N = 300;
    const events = Array.from({ length: N }, (_, i) =>
      traceEvent(`level-${i}`, 10 + i, 2 * (N - i), { sid: String(i), ...(i > 0 ? { psid: String(i - 1) } : {}) }));
    const data = extractPerfSession([sessionAnchor(0), toolLog('deep', 0, 5_000)], events)!;
    const tool = data.spans.find((sp) => sp.name === 'deep')!;
    for (let i = 0; i < N; i++) {
      expect(data.spans.find((sp) => sp.name === `level-${i}`)!.depth).toBe(tool.depth + 1 + i);
    }
    expect(treeSelfSum(data)).toBeCloseTo(data.covered, 6);
  });

  test('spans from different traces never nest, even same-thread exactly contained', () => {
    // A session dir can hold spans merged from more than one process (a host run plus the
    // on-device run it drove). Their intervals overlap for real, but neither called the other.
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 3_000)],
      [
        traceEvent('host-side', 300, 1_000, { trid: 'a'.repeat(32), tid: 1 }),
        traceEvent('device-side', 400, 200, { trid: 'b'.repeat(32), tid: 1 }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'tapOn')!;
    const host = data.spans.find((sp) => sp.name === 'host-side')!;
    const device = data.spans.find((sp) => sp.name === 'device-side')!;
    expect(device.parent).toBe(tool.id);
    expect(device.parent).not.toBe(host.id);

    // Control: the same two intervals inside ONE trace do nest.
    const sameTrace = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 3_000)],
      [
        traceEvent('host-side', 300, 1_000, { trid: 'a'.repeat(32), tid: 1 }),
        traceEvent('device-side', 400, 200, { trid: 'a'.repeat(32), tid: 1 }),
      ],
    )!;
    expect(sameTrace.spans.find((sp) => sp.name === 'device-side')!.parent)
      .toBe(sameTrace.spans.find((sp) => sp.name === 'host-side')!.id);
  });

  test('a declared parent nests an async span that inference would refuse', () => {
    // The HTTP emitters stamp `tid` at observation time, so containment among them is coincidence
    // and inference must refuse it. A declared psid is different in kind: the producer read its
    // parent off the coroutine context. Nesting it is what stops the tool span from reporting the
    // request's time as its own.
    const TR = 'a'.repeat(32);
    const tool = '1'.repeat(16);
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('sendPrompt', 0, 4_000)],
      [
        traceEvent('LlmClient.execute', 100, 2_000, { trid: TR, sid: tool, tid: 1 }),
        traceEvent('POST https://llm.example/v1', 300, 1_500, {
          trid: TR,
          sid: '2'.repeat(16),
          psid: tool,
          tid: 99, // a different thread, and async: inference could never place this
          cat: 'http',
          args: { async: 'true' },
        }),
      ],
    )!;
    const parent = data.spans.find((sp) => sp.name === 'LlmClient.execute')!;
    const http = data.spans.find((sp) => sp.name === 'http.POST https://llm.example/v1')!;
    expect(http.parent).toBe(parent.id);
    // The request's 1.5s must come OUT of the caller's self time, not be double-counted.
    expect(parent.self).toBeLessThan(600);
    expect(treeSelfSum(data)).toBeCloseTo(data.covered, 6);
  });

  test('an async span with no declared parent is still refused by inference', () => {
    // The control for the test above: same geometry, no psid. Same-thread exact containment is
    // exactly the coincidence the async marker exists to reject.
    const TR = 'a'.repeat(32);
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('sendPrompt', 0, 4_000)],
      [
        traceEvent('LlmClient.execute', 100, 2_000, { trid: TR, sid: '1'.repeat(16), tid: 1 }),
        traceEvent('POST https://llm.example/v1', 300, 1_500, {
          trid: TR,
          tid: 1,
          cat: 'http',
          args: { async: 'true' },
        }),
      ],
    )!;
    const parent = data.spans.find((sp) => sp.name === 'LlmClient.execute')!;
    const http = data.spans.find((sp) => sp.name === 'http.POST https://llm.example/v1')!;
    expect(http.parent).not.toBe(parent.id);
  });

  test('an inferred parent survives a partially overlapping span from another trace', () => {
    // Spans arrive in one global time order, so a second trace's span lands between a first
    // trace's parent and child. With one shared containment stack it evicts the parent, and the
    // id-less child — which has nothing but inference to go on — comes out a root.
    const A = 'a'.repeat(32);
    const B = 'b'.repeat(32);
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 4_000)],
      [
        traceEvent('a-outer', 100, 1_000, { trid: A, sid: '1'.repeat(16), tid: 1 }),
        traceEvent('b-overlapping', 600, 1_000, { trid: B, sid: '2'.repeat(16), tid: 1 }),
        traceEvent('a-inner-legacy', 800, 100, { trid: A, tid: 1 }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'tapOn')!;
    const aOuter = data.spans.find((sp) => sp.name === 'a-outer')!;
    const aInner = data.spans.find((sp) => sp.name === 'a-inner-legacy')!;
    expect(aInner.parent).toBe(aOuter.id);
    expect(aInner.parent).not.toBe(tool.id);
  });

  test('a declared parent from another trace is refused, not silently adopted', () => {
    // Span ids are only unique within a trace, so two merged recordings can each hold sid "aa..".
    // Keying the declared-parent lookup by sid alone would reparent one trace's span into the
    // other's, inverting the hierarchy across a process boundary.
    const A = 'a'.repeat(32);
    const B = 'b'.repeat(32);
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 3_000)],
      [
        traceEvent('a-parent', 100, 2_000, { trid: A, sid: '1'.repeat(16), tid: 1 }),
        traceEvent('b-child', 300, 200, { trid: B, sid: '2'.repeat(16), psid: '1'.repeat(16), tid: 2 }),
      ],
    )!;
    const tool = data.spans.find((sp) => sp.name === 'tapOn')!;
    const aParent = data.spans.find((sp) => sp.name === 'a-parent')!;
    const bChild = data.spans.find((sp) => sp.name === 'b-child')!;
    expect(bChild.parent).not.toBe(aParent.id);
    expect(bChild.parent).toBe(tool.id);

    // Control: the identical psid inside the SAME trace does resolve.
    const sameTrace = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 3_000)],
      [
        traceEvent('a-parent', 100, 2_000, { trid: A, sid: '1'.repeat(16), tid: 1 }),
        traceEvent('b-child', 300, 200, { trid: A, sid: '2'.repeat(16), psid: '1'.repeat(16), tid: 2 }),
      ],
    )!;
    expect(sameTrace.spans.find((sp) => sp.name === 'b-child')!.parent)
      .toBe(sameTrace.spans.find((sp) => sp.name === 'a-parent')!.id);
  });

  test('an OpenTelemetry span kind reaches the contract; plain in-process work has none', () => {
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('sendPrompt', 0, 2_000)],
      [
        traceEvent('POST https://llm.example/v1', 100, 900, { cat: 'http', kind: 'CLIENT' }),
        traceEvent('createNextChatRequest', 1_100, 100, { cat: 'agent' }),
      ],
    )!;
    expect(data.spans.find((sp) => sp.name === 'http.POST https://llm.example/v1')!.spanKind).toBe('CLIENT');
    expect(data.spans.find((sp) => sp.name === 'agent.createNextChatRequest')!.spanKind).toBeNull();
    // Log-derived spans have no OpenTelemetry kind at all.
    expect(data.spans.find((sp) => sp.name === 'sendPrompt')!.spanKind).toBeNull();
  });

  test('span identity is plumbing: it never reaches the emitted span contract', () => {
    const data = extractPerfSession(
      [sessionAnchor(0), toolLog('tapOn', 0, 2_000)],
      [traceEvent('traced', 400, 300, { sid: '1' })],
    )!;
    const traced = data.spans.find((sp) => sp.kind === 'trace')! as Record<string, unknown>;
    expect('sid' in traced).toBe(false);
    expect('psid' in traced).toBe(false);
    expect('trid' in traced).toBe(false);
  });
});

describe('bottom-up aggregation', () => {
  test('aggregates self time by name and clips segments to the range', () => {
    const data = extractPerfSession([
      sessionAnchor(0),
      toolLog('outer', 0, 10_000),    // self: 0-2000 and 6000-10000
      toolLog('inner', 2_000, 4_000), // self: 2000-6000
    ])!;
    const full = bottomUpAggregate(data.spans, 0, 10_000);
    expect(full).toEqual([
      { name: 'outer', kind: 'tool', self: 6_000, count: 1, maxSelf: 6_000 },
      { name: 'inner', kind: 'tool', self: 4_000, count: 1, maxSelf: 4_000 },
    ]);
    // Range [1000, 3000]: outer contributes 1000-2000, inner 2000-3000.
    const ranged = bottomUpAggregate(data.spans, 1_000, 3_000);
    expect(ranged).toEqual([
      { name: 'outer', kind: 'tool', self: 1_000, count: 1, maxSelf: 1_000 },
      { name: 'inner', kind: 'tool', self: 1_000, count: 1, maxSelf: 1_000 },
    ]);
  });
});
