// Ambient (global) types for the interactive run report's data contract — the `__TB_RUN_DATA__`
// payload shared by its three producers (the in-app Share button via share-export.jsx, the headless
// bun driver run-report-cli.ts, and RunReportGenerator.kt's input JSON) and its one consumer (the
// embedded viewer in run-report-core.ts).
//
// Deliberately a GLOBAL declaration file (no import/export): run-report-core.ts must stay a plain
// script — an `import` would make it a module, and its transpiled output has to keep working both
// as a classic browser <script> and as a CommonJS `require()` from bun. Ambient interfaces give it
// type coverage with zero runtime footprint. Never packaged into the JAR (see build.gradle.kts).

/** The run header the viewer renders (title, badge, meta strip, error banner, rerun command). */
interface RunMeta {
  title?: string;
  /** Badge class: passed | failed | cancelled | running | unknown (see RunReportGenerator.statusLabel). */
  status?: string;
  target?: string;
  /** Resolved package name (Android) / bundle id (iOS) of the app under test. */
  appId?: string;
  /** Display version of the app under test, e.g. "5.58.0.0 (67500009)". */
  appVersion?: string;
  device?: string;
  /** Human-readable device category/classifier, e.g. "phone" or "tablet". */
  deviceType?: string;
  platform?: string;
  trailId?: string;
  steps?: number;
  ranAt?: string;
  duration?: string;
  /** Copyable rerun command for the Info tab. */
  cmd?: string;
  /** Failure reason shown as the header error banner. */
  error?: string;
  /** True for *WithSelfHeal statuses — renders the separate self-heal marker badge. */
  selfHeal?: boolean;
  generatedAt?: string;
  /** CI build that produced the run, when the report was generated in CI. */
  buildUrl?: string;
  buildNumber?: string;
  /** Source revision that produced the run. */
  commitSha?: string;
  commitUrl?: string;
  branch?: string;
  /** Legacy single-run payloads carried the YAML on meta; lifted onto the session by the builder. */
  recordingYaml?: string | null;
  /** Legacy-compatible transport for the authored trail before the run recorded concrete actions. */
  originalYaml?: string | null;
}

/** Report-time action overlay on a step's screenshot, in device-pixel coordinates (dw×dh). */
interface ActionMark {
  kind: "tap" | "swipe" | "assert";
  x?: number;
  y?: number;
  x1?: number;
  y1?: number;
  x2?: number;
  y2?: number;
  dw: number;
  dh: number;
  /** AssertCondition outcome; `false` renders the red full-screen border. */
  ok?: boolean;
}

/** A tool the outer tool delegated to (expandable child row). */
interface TraceChild {
  label: string;
  tool: string;
}

/** One timeline row after slimTraceForShare — the embedded shape the viewer renders. */
interface TraceStep {
  /** 1-based ordinal, stable across filtering (rows are looked up by `i`, not index). */
  i: number;
  label: string;
  tool: string;
  note: string | null;
  /** Duration in ms, summed across folded log records. */
  ms: number;
  /** Wall-clock epoch ms of the first folded record, or null when the log carried no timestamp. */
  ts: number | null;
  ok: boolean;
  err: string | null;
  screenshotFile: string | null;
  /** True for top-level trail steps (ObjectiveStartLog) — starts a STEP group header. */
  objective: boolean;
  /** True when the objective is the trail's `trailhead:` (step 0) — rendered as TRAILHEAD, unnumbered. */
  trailhead: boolean;
  /** True when recorded actions for this authored objective failed and self-heal took over. */
  selfHeal?: boolean;
  /** Recorded tool that triggered self-heal, retained for the recovery summary. */
  selfHealTool?: string | null;
  /** Original recording failure, retained separately from the recovered objective outcome. */
  selfHealError?: string | null;
  /** True only on the recorded tool row whose failure triggered self-heal. */
  selfHealSource?: boolean;
  /** Fold count for repeated actions / polled assertions (rendered as ×N), or null. */
  count: number | null;
  mark: ActionMark | null;
  children: TraceChild[];
}

/** Rows as extractTrace produces them, before slimTraceForShare strips extraction bookkeeping. */
interface RawTraceRow extends Partial<Omit<TraceStep, "children">> {
  label: string;
  children?: TraceChild[] | null;
  _logs?: unknown[];
}

/** One parsed part of an LLM response: a tool call (with optional extracted reasoning) or text. */
interface LlmResponsePart {
  kind: "tool" | "text";
  tool?: string;
  args?: string | null;
  reasoning?: string | null;
  text?: string;
}

/** One LLM call after slimLlmForShare — the embedded shape the LLM tab renders. */
interface LlmCall {
  model: string;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReadTokens: number;
  totalCost: number | null;
  durationMs: number;
  label: string;
  instructions: string | null;
  response: LlmResponsePart[];
}

/** Rows as extractLlmLogs produces them (superset of LlmCall; messages are dropped at share time). */
interface RawLlmRow extends Omit<LlmCall, "response"> {
  promptCost?: number | null;
  completionCost?: number | null;
  messages?: unknown[];
  response?: LlmResponsePart[];
}

/** One network.ndjson event, reduced to the fields the Network tab renders. */
interface NetworkEvent {
  method: string;
  statusCode: number | null;
  durationMs: number | null;
  urlPath: string;
  phase: string;
}

/** One generic session event: offset epoch ms (or null) + pre-truncated serialized data. */
interface SessionEvent {
  t: number | null;
  d: string;
}

/** One `events/<name>.<style>.ndjson` producer stream (last-N events + true total). */
interface EventStream {
  name: string;
  style: string;
  total: number;
  truncated: boolean;
  events: SessionEvent[];
}

/** Video sprite-sheet layout + playable logical-frame range (see run-report-cli.ts readVideo). */
interface VideoInfo {
  /** data: URI of the sprite sheet image. */
  sprite: string;
  fps: number;
  frames: number;
  columns: number;
  rows: number;
  frameHeight: number;
  /** logical frame index → physical sprite cell (identity when no alias dedup ran). */
  frameMap: number[];
  startFrame: number;
  endFrame: number;
}

/** One run inside the embedded payload. */
interface SessionPayload {
  meta: RunMeta;
  trace: TraceStep[];
  llm: LlmCall[];
  /** screenshotFile → data: URI. */
  shots: Record<string, string>;
  recordingYaml: string | null;
  originalYaml: string | null;
  deviceLog?: string | null;
  network?: NetworkEvent[] | null;
  events?: EventStream[] | null;
  video?: VideoInfo | null;
}

/** One run as callers hand it to buildMultiReportHtml, before the slim/normalize pass. */
interface SessionInput {
  meta?: RunMeta;
  trace?: RawTraceRow[];
  llmLogs?: RawLlmRow[];
  shots?: Record<string, string>;
  recordingYaml?: string | null;
  originalYaml?: string | null;
  deviceLog?: string | null;
  network?: NetworkEvent[] | null;
  events?: EventStream[] | null;
  video?: VideoInfo | null;
}

/** The `window.__TB_RUN_DATA__` global the self-contained report embeds. */
interface ReportPayload {
  generatedAt: string;
  sessions: SessionPayload[];
  /** Pre-multi-session single-run shape, tolerated by the viewer for old exports. */
  meta?: RunMeta;
  trace?: TraceStep[];
  llm?: LlmCall[];
  shots?: Record<string, string>;
}

/** A raw Trailblaze log record as the daemon serves it (`class` discriminator + open fields). */
interface TrailblazeLogRecord {
  class?: string;
  [key: string]: any;
}

interface Window {
  __TB_RUN_DATA__?: ReportPayload;
}
