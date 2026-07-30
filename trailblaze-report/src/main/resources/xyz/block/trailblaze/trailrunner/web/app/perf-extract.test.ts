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
