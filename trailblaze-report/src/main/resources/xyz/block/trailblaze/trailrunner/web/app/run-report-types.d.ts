// Ambient (global) types for the interactive run report's data contract — the `__TB_RUN_DATA__`
// payload shared by its three producers (the in-app Share button via share-export.jsx, the headless
// bun driver run-report-cli.ts, and RunReportGenerator.kt's input JSON) and its one consumer (the
// embedded viewer in run-report-viewer.ts).
//
// Deliberately still a GLOBAL declaration file (no import/export). The run-report modules could
// import these types now that they're real ES modules, but the contract is also referenced from
// run-report-cli.ts / run-report-events.ts (packaged, bun-executed sources in ../../../report/)
// and mirrored by the Trail Runner web app's plain-script/babel files, which cannot import —
// ambient interfaces keep one declaration serving every consumer with zero runtime footprint.
// Never packaged into the JAR (see build.gradle.kts).

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
  /** Machine-readable failure code lifted from the session's structured failure payload (`failureCodeOf`); rendered as a chip on the failure banner. */
  failureCode?: string;
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
  /**
   * Consumer-injected key/values lifted from the trail's `config.metadata` (account ids, links,
   * team-specific context). Rendered as rows on the Info tab and searchable from the index. The
   * well-known key `owner` additionally renders as the run row's subtitle and powers the index's
   * "Owner" sort sections.
   */
  metadata?: Record<string, string>;
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
  /** Summed execution time of the folded dispatches; null for a declared-but-never-logged dispatch. */
  ms?: number | null;
  /** `false` when the dispatch logged a failure. */
  ok?: boolean;
  /** The failed dispatch's error message (errorMessage or the JVM log's exceptionMessage); null when it passed or logged none. */
  err?: string | null;
  /** Machine-readable code from the failed dispatch's structured error payload (top-level string `code`); null when it passed or the payload carried none. */
  code?: string | null;
  /** Consecutive identical dispatches folded into this child (×N); 1 when it ran once. */
  count?: number | null;
  /** Full tool call as trail-file YAML (`- toolName:` + indented args) — what the selected child
   * expands to, matching the WASM report's per-call YAML detail. Absent when the call had no args. */
  args?: string | null;
  /** This dispatch's own captured frame (its log's, else the driver log in its span) — the
   * screenshot the preview pane shows when the child is selected. Absent when none was captured. */
  screenshotFile?: string | null;
  /** Tap/swipe/assert overlay for this dispatch's frame (see ActionMark). */
  mark?: ActionMark | null;
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
  /** True for trailing non-action rows (final/failure snapshots, errors) — never a step's "first tool call". */
  terminal?: boolean;
  /** Fold count for repeated actions / polled assertions (rendered as ×N), or null. */
  count: number | null;
  mark: ActionMark | null;
  /**
   * Full call content as trail-file YAML — a tool row's complete arguments (`- toolName:` +
   * indented args, the WASM report's toolToYaml shape) or a raw device action's full fields (the
   * untruncated assert condition). Rendered expanded under the SELECTED row, so what a step
   * tapped/validated is readable in full, not just the `tool` summary's crop. Absent when the
   * call carried nothing beyond its label.
   */
  args?: string | null;
  /**
   * Index into the session's llm call list (extractLlmLogs order) for LLM-call rows — the link
   * that lets the timeline open this call's transcript/usage. Absent on every other row.
   */
  llm?: number | null;
  /**
   * Device/viewport extent of this row's capture (the log's deviceWidth×deviceHeight) — the
   * coordinate space the screenshot shows. The UI Inspector anchors its bounds overlay and
   * hit-testing on it, because a web trailblazeNodeTree's own extent is page-relative and
   * polluted by off-viewport nodes. Present only on rows that carried a hierarchy.
   */
  viewport?: { w: number; h: number } | null;
  /** Composite-tool dispatch list (see toolChildren). Only present on rows that carry one. */
  children?: TraceChild[];
  /** A composite call's full argument list as preformatted `key=value` lines — unabridged, unlike
   * the `tool` summary's three-key crop. Only present on rows that carry children. */
  params?: string[] | null;
}

/** Rows as extractTrace produces them, before slimTraceForShare strips extraction bookkeeping. */
interface RawTraceRow extends Partial<Omit<TraceStep, "children">> {
  label: string;
  children?: TraceChild[] | null;
  /** Raw view-hierarchy JSON captured with this row's log (viewHierarchyFiltered ||
   * trailblazeNodeTree || viewHierarchy). Lifted into SessionPayload.hierarchies at share time
   * (traceHierarchies); never embedded on the row itself. */
  viewHierarchy?: unknown;
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

/**
 * Per-call input-token composition — what filled the request's context window. Small numbers
 * only, never the messages themselves: sourced from the runtime-computed LlmInputTokenBreakdown
 * stored on the request log when present, else re-estimated from the log's flattened messages at
 * extraction time (estimateLlmComp). Token values are estimates scaled so the categories sum to
 * the LLM-reported input total; `est` is that sum, which therefore equals the reported input total
 * by construction (why the LLM tab shows no estimate-total column).
 */
interface LlmComp {
  /** Estimated tokens per category. */
  system: number;
  user: number;
  tools: number;
  images: number;
  /** Item counts per category (messages / messages / tool descriptors / images). */
  systemCount: number;
  userCount: number;
  toolsCount: number;
  imagesCount: number;
  /** Sum of the four token categories — the estimated input total. */
  est: number;
}

/** One LLM call after slimLlmForShare — the embedded shape the LLM tab renders. */
interface LlmCall {
  model: string;
  /**
   * Provider id (`TrailblazeLlmProvider.id`, e.g. "openai") that owns `model`, forming the
   * repo's canonical `<provider>/<model>` identity. Absent when the log carried no provider (older
   * payloads, modelName-only logs) — render the bare model id then, never a guessed prefix.
   */
  provider?: string | null;
  inputTokens: number | null;
  outputTokens: number | null;
  cacheReadTokens: number;
  totalCost: number | null;
  /** Input-side cost of this call (cache-discounted), when the log carried per-call costs. */
  promptCost: number | null;
  /** Output-side cost of this call, when the log carried per-call costs. */
  completionCost: number | null;
  /** USD saved by cached input reads vs full-rate input pricing (0 when nothing cached / no pricing). */
  cacheSavings: number;
  /** Input-token composition, or null when the log had neither a stored breakdown nor messages. */
  comp: LlmComp | null;
  durationMs: number;
  label: string;
  instructions: string | null;
  response: LlmResponsePart[];
}

/** Rows as extractLlmLogs produces them (superset of LlmCall; messages move to LlmTranscripts at share time). */
interface RawLlmRow extends Omit<LlmCall, "response"> {
  messages?: unknown[];
  response?: LlmResponsePart[];
}

/** One transcript message: role + index into LlmTranscripts.texts (+ tool name on tool turns). */
interface LlmTranscriptMessage {
  role: string;
  /** Index into LlmTranscripts.texts (message texts are pooled — see extractLlmTranscripts). */
  t: number;
  toolName?: string | null;
}

/**
 * A session's full LLM chat transcripts, one message list per call, aligned by index with the slim
 * `llm` array. The conversation history accumulates across calls (call N repeats every earlier
 * turn verbatim), so message texts are pooled: `texts` holds each distinct text once and each
 * call's messages reference it by index. Image data URIs inside messages are replaced with a
 * placeholder at extraction time (screenshots are already embedded separately in the report).
 */
interface LlmTranscripts {
  texts: string[];
  calls: LlmTranscriptMessage[][];
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

/** Tone accent for a formatted row or badge. */
type RowTone = "ok" | "warn" | "error";

/** Short status chip on a formatted row's summary line (e.g. "200", "142ms"). */
interface RowBadge {
  text: string;
  tone?: RowTone;
}

/** One key/value pair in a formatted row's summary strip. */
interface RowField {
  k: string;
  v: string;
  /**
   * Optional absolute http(s) URL; when present the viewer renders `v` as a link opening in a
   * new tab. Validated at embed time (`safeFieldHref` in run-report-events.ts) and re-checked by
   * the viewer before an anchor is emitted; anything non-http(s) is dropped, never rendered.
   */
  href?: string;
}

/**
 * One display row a stream formatter produced — the embedded, already-clamped shape the viewer
 * renders netlog-style (see run-report-events.ts for the clamping pass and EventStreamFormatter
 * for the author-side contract). Formatting is summary-line chrome only (label, badges, fields);
 * the expanded body is the raw payload itself.
 */
interface FormattedRow {
  t: number | null;
  label: string;
  tone?: RowTone;
  badges?: RowBadge[];
  fields?: RowField[];
  /**
   * Source payload(s) this row covers — JSON values, embedded compact. The viewer pretty-prints
   * them (recursively parsing JSON-in-string values) when the row is expanded. An entry past the
   * pathological-size backstop is embedded as a truncated string instead.
   */
  raw?: unknown[];
}

/** One decoded `events/` line handed to a stream formatter (full payload, pre-truncation). */
interface FormatterEntry {
  t: number | null;
  data: any;
}

/**
 * Author-side row shape returned by EventStreamFormatter.format: FormattedRow before the embed-time
 * clamp. Formatters describe the summary line only; `raw` carries the payload(s) the row covers.
 */
interface FormatterRowInput {
  t?: number | null;
  label: string;
  tone?: RowTone;
  badges?: RowBadge[];
  fields?: RowField[];
  raw?: unknown[];
}

/**
 * Session-level context handed to EventStreamFormatter.format so a formatter can apply a report
 * size budget where full payloads aren't worth their bytes. `sessionPassed` is true only when the
 * session affirmatively passed — anything else (failed, cancelled, unknown, or a driver that
 * doesn't supply context) reads as not-passed, so failure evidence is never budgeted away. A
 * driver may also deliberately present a passed session as not-passed when the user opted out of
 * budgeting (`--full-report-payloads`) — see formatterContext in run-report-cli.ts.
 */
interface FormatterContext {
  sessionPassed: boolean;
}

/**
 * The default export of an event-formatter module. RunReportGenerator discovers these on the JVM
 * classpath (xyz/block/trailblaze/report/event-formatters/*.formatter.ts|js), stages them beside
 * run-report-cli.ts, and the driver maps each `events/<name>.ndjson` stream to the first
 * formatter whose `streams` matches `name`. Formatters see the WHOLE decoded stream at once so
 * they can pair related events (e.g. a request with its response) into a single row.
 */
interface EventStreamFormatter {
  id: string;
  /** Stream names this formatter owns: exact names, or `prefix.*` wildcards. */
  streams: string[];
  format(entries: FormatterEntry[], ctx?: FormatterContext): Array<FormatterRowInput | null | undefined>;
}

/** One `events/<name>.ndjson` producer stream, embedded in full. */
interface EventStream {
  name: string;
  total: number;
  truncated: boolean;
  events: SessionEvent[];
  /** Formatter-produced rows; when present the viewer renders these instead of `events`. */
  rows?: FormattedRow[] | null;
}

/** Video sprite-sheet layout + playable logical-frame range (see run-report-cli.ts readVideo). */
interface VideoInfo {
  /**
   * data: URIs of the sprite sheet image(s), in sheet order, each with that sheet's actual row
   * count (`columns`/`rows` describe one FULL sheet, so physical frame N lives on sheet
   * `N / (columns*rows)`; only the final sheet may have fewer rows). Usually length 1. Callers
   * hand full URIs to buildMultiReportHtml; in the emitted document the URIs are hoisted into a
   * per-session inert `#tb-sprites-<i>` JSON chunk (one URI array per session; older exports use
   * a single `#tb-sprites` map keyed by session index) and the embedded payload carries
   * `uri: ''` — the viewer resolves them lazily on first access, so booting never parses sprite
   * bytes.
   */
  sprites: Array<{ uri: string; rows: number }>;
  fps: number;
  frames: number;
  columns: number;
  rows: number;
  frameHeight: number;
  /**
   * Per-frame pixel width from `frameWidth=` in video_sprites.txt. Optional: sprite files written
   * before the key existed lack it (null/undefined), and consumers must keep deriving the width
   * from the sheet's natural size in that case.
   */
  frameWidth?: number | null;
  /** logical frame index → physical sprite cell (identity when no alias dedup ran). */
  frameMap: number[];
  startFrame: number;
  endFrame: number;
  /**
   * Wall-clock epoch ms of logical frame 0 (the VIDEO_FRAMES artifact's capture start). Lets the
   * timeline map a step's `ts` onto a video frame; when absent (older exports, no capture
   * timestamps) the timeline preview falls back to per-step screenshots.
   */
  startMs?: number | null;
}

/**
 * The Kotlin/JS selector-engine bundle (:trailblaze-selector-engine-js) as the report transports
 * it: `js` inline below the driver's threshold, `gz` (gzip+base64) above it — the same split as
 * every other side-channel (see packGz in run-report-cli.ts). buildMultiReportHtml embeds it ONCE
 * per document as the inert `#tb-selector-engine` JSON chunk, and only when a session carries
 * hierarchies; the viewer inflates + evaluates it on first inspector use, never on page load.
 */
interface SelectorEnginePayload {
  js?: string | null;
  gz?: string | null;
}

/** One run inside the embedded payload. */
interface SessionPayload {
  meta: RunMeta;
  trace: TraceStep[];
  llm: LlmCall[];
  /**
   * Index-chunk precomputes (chunked documents only): the run list's step / tool-call counts,
   * derived from the trace at build time (traceStepCount/traceToolCallCount) so the index renders
   * before any session chunk is hydrated. Absent on monolithic payloads, where the viewer derives
   * both from the trace directly.
   */
  stepCount?: number | null;
  toolCallCount?: number | null;
  /** screenshotFile → data: URI. */
  shots: Record<string, string>;
  recordingYaml: string | null;
  originalYaml: string | null;
  deviceLog?: string | null;
  /** gzip(deviceLog text) as base64 — used instead of `deviceLog` past the driver's inline
   * threshold; the viewer inflates it lazily via DecompressionStream. */
  deviceLogGz?: string | null;
  network?: NetworkEvent[] | null;
  /** gzip(JSON.stringify(NetworkEvent[])) as base64 — see deviceLogGz. */
  networkGz?: string | null;
  events?: EventStream[] | null;
  /** gzip(JSON.stringify(EventStream[])) as base64 — used instead of `events` past the driver's
   * inline threshold; the viewer inflates it lazily via DecompressionStream. */
  eventsGz?: string | null;
  /** Per-call LLM chat transcripts (see LlmTranscripts), inline below the driver's threshold. */
  llmMessages?: LlmTranscripts | null;
  /** gzip(JSON.stringify(LlmTranscripts)) as base64 — see deviceLogGz. */
  llmMessagesGz?: string | null;
  /** Per-step view-hierarchy snapshots for the UI Inspector, keyed by TraceStep.i (stringified).
   * Each value is the raw hierarchy JSON captured with that step's log. */
  hierarchies?: Record<string, unknown> | null;
  /** gzip(JSON.stringify(hierarchies)) as base64 — used instead of `hierarchies` past the
   * driver's inline threshold; the viewer inflates it lazily when an inspector is opened. */
  hierarchiesGz?: string | null;
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
  /** See SessionPayload.deviceLogGz. */
  deviceLogGz?: string | null;
  network?: NetworkEvent[] | null;
  /** See SessionPayload.networkGz. */
  networkGz?: string | null;
  events?: EventStream[] | null;
  /** See SessionPayload.eventsGz. */
  eventsGz?: string | null;
  /** See SessionPayload.llmMessages. When neither transcript field is supplied, toSessionPayloads
   * derives the transcripts from `llmLogs` (the browser/zip paths); the bun driver supplies them
   * pre-packed instead. */
  llmMessages?: LlmTranscripts | null;
  /** See SessionPayload.llmMessagesGz. */
  llmMessagesGz?: string | null;
  /** See SessionPayload.hierarchies. When neither this nor hierarchiesGz is supplied,
   * toSessionPayloads lifts hierarchies off the trace rows itself. */
  hierarchies?: Record<string, unknown> | null;
  /** See SessionPayload.hierarchiesGz. */
  hierarchiesGz?: string | null;
  video?: VideoInfo | null;
}

/**
 * The run payload the self-contained report embeds. Chunked documents (buildMultiReportHtml)
 * split it across inert JSON script elements: `#tb-index` (this shape, with each session reduced
 * to a stub of meta + per-call LLM token/cost summaries + stepCount/toolCallCount) is JSON.parsed
 * at boot, and each session's
 * full payload rides in its own `#tb-session-<i>` element, parsed into the stub when that run
 * opens. Fallback reads, in order: a monolithic `#tb-run-data` element (older exported files),
 * then `window.__TB_RUN_DATA__` (out-of-repo embedders that set the global directly instead of
 * shipping a JSON script element).
 */
interface ReportPayload {
  generatedAt: string;
  /**
   * Canonical URL where this report is hosted, baked in at generation time
   * (`trailblaze report --share-url …`). When set, the Copy-link affordances use it (with the
   * current route state grafted on) instead of the browser's address, and stay available even
   * when the document is opened from file:// or an embed.
   */
  shareUrl?: string;
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

/**
 * The zip pipeline (`zip-report-core.js`), loaded as a classic script beside the viewer. Only the
 * members the shell calls are declared; the module's full surface is exercised from its own tests.
 */
interface ZipReportExports {
  buildSessionInputsFromZipBytes: (
    zipBytes: Uint8Array,
    options?: { render?: unknown; onStage?: (stage: string) => void; inflateRaw?: unknown; generatedAt?: string },
  ) => Promise<{ sessions: SessionInput[]; generatedAt: string; zipBytes: number }>;
}

interface Window {
  __TB_RUN_DATA__?: ReportPayload;
  /** Set by run-report-viewer-boot; the viewer shell's handoff into the report once a payload is in place. */
  __TB_BOOT_REPORT__?: () => void;
  /** Published by zip-report-core.js. */
  TbZipReport?: ZipReportExports;
}
