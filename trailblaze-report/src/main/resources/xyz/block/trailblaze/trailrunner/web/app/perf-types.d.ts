// Ambient (global) types for the performance-analysis report's data contract — the payload shared
// by its producer (the headless bun driver perf-report-cli.ts, fed by PerformanceAnalysisGenerator.kt's
// input JSON) and its consumer (the embedded viewer in perf-viewer.ts).
//
// Deliberately a GLOBAL declaration file (no import/export), matching run-report-types.d.ts —
// one declaration serves the extractor, the driver, and the viewer with zero runtime footprint.
// `RunMeta` and `TrailblazeLogRecord` are reused from run-report-types.d.ts (same ambient project).
// Never packaged into the JAR (see build.gradle.kts).
//
// Time model: every `s`/`e` in this contract is an OFFSET in ms from the session's `t0` (epoch ms),
// and every span is START-ANCHORED at extraction time — a log's `timestamp` is when the operation
// STARTED, `durationMs` how long it took, so the span is [timestamp, timestamp + durationMs).
// The one exception is McpSamplingLog, stamped AFTER the call: its span is (ts - durationMs, ts].

/** Which extraction family a span came from (drives track routing + color). */
type PerfSpanKind = "tool" | "llm" | "maestro" | "driver";

/**
 * One extracted span. Spans with kind tool/llm/maestro live in the containment tree (host clock);
 * kind "driver" spans are timestamped on the DEVICE clock, which skews from the host clock by
 * whole seconds — they are never nested into the tree and render on their own track.
 */
interface PerfSpan {
  /** Index into PerfSessionData.spans — stable id for parent/kids/selection references. */
  id: number;
  name: string;
  kind: PerfSpanKind;
  /** Original (unclamped) start/end offsets from t0, ms. */
  s: number;
  e: number;
  /** e - s. */
  dur: number;
  /** Tree spans: effective (clamped) self time in ms — see selfSegs. Driver spans: dur. */
  self: number;
  /**
   * Effective self-time segments [start, end][], offsets from t0: the parts of this span's
   * effective interval not covered by any child's effective interval. Computed by the
   * deterministic clamp sweep in perf-extract.ts, so summing (end - start) over every tree
   * span's selfSegs equals the union coverage of the root spans EXACTLY — the accounting
   * invariant the tests pin. Range-scoped aggregates (bottom-up) clip these segments.
   */
  selfSegs: Array<[number, number]>;
  /** Effective (clamped into parent + de-overlapped against earlier siblings) interval. */
  effS: number;
  effE: number;
  /** 0 for roots; driver spans are always 0. */
  depth: number;
  parent: number | null;
  kids: number[];
  /** Index into PerfSessionData.steps this span fell inside, or null. */
  step: number | null;
  ok: boolean;
  err: string | null;
  /** Short human summary of the tool args / llm label / maestro command. */
  detail: string;
  /** Compact JSON of the recorded raw args (truncated), for the inspector. */
  args: string | null;
  /** Requested timeout budget in ms (any numeric raw arg key matching /timeout/i), or null. */
  budget: number | null;
  /** LLM spans: total cost in USD when the usage object carried one. */
  cost: number | null;
  /** LLM spans: "in→out" token summary. */
  tokens: string | null;
  /** Screenshot file reference (name only — the perf report does not inline image bytes). */
  shot: string | null;
}

/** One trail step (objective): ObjectiveStartLog → matching ObjectiveCompleteLog. */
interface PerfStep {
  i: number;
  label: string;
  s: number;
  /** Null when no ObjectiveCompleteLog matched (session died mid-step). */
  e: number | null;
  ok: boolean;
  err: string | null;
  /** LLM call count the objective result reported, when present. */
  calls: number | null;
  trailhead: boolean;
}

/** One stretch of session wall-clock covered by NO tree span (root-union gap > threshold). */
interface PerfGap {
  s: number;
  e: number;
  dur: number;
  /** Name of the tree span ending at the gap's left edge, or null at the session head. */
  before: string | null;
  /** Name of the tree span starting at the gap's right edge, or null at the session tail. */
  after: string | null;
}

/** One tool invocation that declared a timeout budget — the timeout-tax table's row. */
interface PerfTaxRow {
  spanId: number;
  name: string;
  detail: string;
  spent: number;
  budget: number;
  ok: boolean;
  /** spent >= 98% of budget — the tool burned its whole timeout. */
  full: boolean;
}

/** One row of a bottom-up (heaviest self time) aggregation. */
interface PerfBottomUpRow {
  name: string;
  kind: PerfSpanKind;
  self: number;
  count: number;
  maxSelf: number;
}

/** Everything extractPerfSession derives from one session's raw logs. */
interface PerfSessionData {
  /**
   * Epoch ms of the session window origin — the zero point of every offset. The earliest
   * host-clock log timestamp, possibly pulled earlier by the derived start of an end-anchored
   * McpSamplingLog span (ts - durationMs).
   */
  t0: number;
  /**
   * Session end as an offset from t0: the latest host-clock bound — the last log timestamp or
   * the latest end of a span-producing log ([timestamp + durationMs), which can outlast every
   * later log; see extractPerfSession).
   */
  t1: number;
  /** Flat, id-indexed span list: the containment tree (tool/llm/maestro) + driver spans. */
  spans: PerfSpan[];
  /** ids of tree roots (parent == null, kind != driver), sorted by s. */
  roots: number[];
  steps: PerfStep[];
  gaps: PerfGap[];
  tax: PerfTaxRow[];
  /** Union coverage of the tree roots, ms (== Σ self over the tree). */
  covered: number;
  /** Σ gap durations, ms. */
  gapTotal: number;
  /** Σ spent over full-burn tax rows, ms. */
  taxFullBurn: number;
  llmCount: number;
  llmTotalMs: number;
  llmCostUsd: number | null;
  /** Σ duration of trailhead steps (setup cost), ms. 0 when the session has no trailhead. */
  trailheadMs: number;
  /** Session-level self-heal marker (a SelfHealInvokedLog was seen). */
  selfHealed: boolean;
}

/** One session inside the embedded perf payload. */
interface PerfSessionPayload {
  meta: RunMeta;
  data: PerfSessionData;
}

/**
 * The payload the self-contained performance report embeds as the inert
 * `<script type="application/json" id="tb-perf-data">` element the viewer JSON.parses at boot.
 */
interface PerfReportPayload {
  generatedAt: string;
  sessions: PerfSessionPayload[];
}
