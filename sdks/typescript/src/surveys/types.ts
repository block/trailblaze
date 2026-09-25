// Public data model for session surveys.
//
// A survey reads one finished session — its Trailblaze log records plus every captured sidecar
// (network requests, analytics streams, device log, visible screen text) — and reports which known
// features that session exercised, each backed by the concrete records that prove it. This file
// holds only the shapes; `session.ts` loads them and `survey.ts` defines the authoring surface.

import type { TrailblazeLog, TrailblazeToolLog } from "../generated/trailrunner-dtos.js";

/** Where a piece of evidence came from. Drives how a report renders it. */
import type { FootprintSpec } from "./survey.js";

export type RecordKind =
  | "network"
  | "analytics"
  | "event"
  | "log"
  | "tool"
  | "objective"
  | "screen-text"
  | "device-log"
  | "trace"
  | "custom";

/**
 * One observed fact inside a session that supports a finding. Every record a session query returns
 * IS an `SessionRecord`, so a survey passes query results straight through as its proof. `file` and
 * `line` point a reader at the exact record in the session directory.
 */
export interface SessionRecord {
  kind: RecordKind;
  /** One human-readable line: what was observed. */
  summary: string;
  /** Session-relative path of the file the observation came from. */
  file: string;
  /** 1-based line for line-oriented files (ndjson, device log); array index for trace spans. */
  line?: number;
  /** Epoch milliseconds when it happened, when the source records a time. */
  timeMs?: number;
  /** Small, report-friendly payload. Bodies and view hierarchies are deliberately left out. */
  detail?: unknown;
}

/** One HTTP exchange, joined from its request-start and response-end records. */
export interface NetworkRequest extends SessionRecord {
  kind: "network";
  id: string;
  method: string;
  url: string;
  host: string;
  /** Path component only, no host or query string. */
  path: string;
  /** Query string without the `?`, when the request had one. */
  query?: string;
  status?: number;
  /** 2xx or 3xx response was recorded. */
  ok: boolean;
  /** The capture recorded a transport failure or never saw a response. */
  failed: boolean;
  durationMs?: number;
  startMs: number;
  endMs?: number;
  /** Which capture produced it (`IOS`, `ANDROID`, `PLAYWRIGHT_WEB`, ...). */
  source: string;
  requestHeaders?: Record<string, string>;
  responseHeaders?: Record<string, string>;
  /** Present only when the capture kept bodies. */
  requestBody?: string;
  responseBody?: string;
  /**
   * The line the response, or the transport failure, was recorded on. One exchange spans several
   * rows and `line` is the request's, so a finding resting on `status` or a response body is proven
   * by THIS row rather than by the one `line` points at.
   */
  responseLine?: number;
  /** The file that row is in, when the response arrived on a stream other than the one `file` names. */
  responseFile?: string;
  /** Sidecar files the bodies were read out of, when the capture wrote them beside the index. */
  bodyFiles?: string[];
}

/** One analytics event, normalized from whatever stream shape the app's capture emits. */
export interface AnalyticsEvent extends SessionRecord {
  kind: "analytics";
  /** Stream name, i.e. the `events/<stream>.ndjson` file it came from. */
  stream: string;
  /** Event name as the app logged it. */
  name: string;
  /** Emitting subsystem when the stream records one. */
  source?: string;
  /** Parsed event properties. Empty object when the stream has none or they fail to parse. */
  properties: Record<string, unknown>;
  /** The row exactly as captured. */
  raw: unknown;
}

/** One row of any `events/<stream>.ndjson` sidecar, undecoded. */
export interface StreamEvent extends SessionRecord {
  kind: "event";
  stream: string;
  data: unknown;
}

/** One Trailblaze log record with its parsed payload. */
export interface LogRecord<L extends TrailblazeLog = TrailblazeLog> extends SessionRecord {
  kind: "log";
  /** Short type name: the last dotted segment of the record's `class`. */
  type: string;
  /** Position in the session's ordered log list. */
  index: number;
  log: L;
}

/** One tool invocation, whether the runner, the LLM, or a recording issued it. */
export interface ToolCall extends SessionRecord {
  kind: "tool";
  name: string;
  args: Record<string, unknown>;
  successful: boolean;
  durationMs: number;
  /** Set when a multi-device trail bound this call to a named device. */
  deviceName?: string;
  log: TrailblazeToolLog;
}

/** One prompt step of the trail, with how it ended. */
export interface Objective extends SessionRecord {
  kind: "objective";
  /** Step number, 1-based, in the order the trail ran them. */
  step: number;
  prompt: string;
  status: "complete" | "failed" | "unknown";
  /** Tools the step's recording replayed, in order. */
  recordedTools: string[];
  startMs?: number;
  endMs?: number;
  explanation?: string;
}

/** One string visible on screen at some capture. */
export interface ScreenText extends SessionRecord {
  kind: "screen-text";
  text: string;
  /** Which accessibility attribute carried it (`text`, `contentDescription`, ...). */
  source: string;
  captureId: string;
  stepIndex: number;
}

/** One line of the captured device log. */
export interface DeviceLogLine extends SessionRecord {
  kind: "device-log";
  text: string;
}

/** One span of the session's trace file. */
export interface TraceSpan extends SessionRecord {
  kind: "trace";
  name: string;
  category: string;
  /** Start, epoch microseconds as the trace format stores it. */
  ts: number;
  durationUs: number;
  args: Record<string, unknown>;
}

/** What is known about a session before any survey runs. */
export interface SessionSummary {
  id: string;
  /** Absolute path of the session directory. */
  path: string;
  title?: string;
  trailId?: string;
  /** Stable test identity when the run had one (for example a case key). */
  testKey?: string;
  target?: string;
  platform?: string;
  appId?: string;
  appVersion?: string;
  driver?: string;
  deviceClassifiers: string[];
  /** `PASSED`, `FAILED`, `CANCELLED`, `TIMEOUT`, or `UNKNOWN` when the session never ended. */
  outcome: string;
  failureReason?: string;
  startedAtMs?: number;
  endedAtMs?: number;
  durationMs?: number;
  /** Free-form metadata the trail declared; a list or map value arrives as JSON text. */
  metadata: Record<string, string>;
  ci?: { jobId?: string; agentName?: string; zipFilename?: string };
  /** Names of the `events/*.ndjson` streams present. */
  eventStreams: string[];
  hasNetworkCapture: boolean;
  hasDeviceLog: boolean;
}

/** What a survey hands back for one match. The runner fills in `survey` and `session`. */
export interface FindingInput {
  /**
   * Which feature this finding belongs to. Defaults to the survey's `feature`; a survey that
   * recognizes many features sets it per finding.
   */
  feature?: string;
  /**
   * What kind of thing `feature` names. Defaults to the survey's `kind`, then `"feature"`. A
   * survey can also place a session on another axis, such as `"job"` or `"gap"`.
   */
  kind?: string;
  /** One line: what happened in this session. */
  summary: string;
  /** The records that prove it. Query results can be passed straight through. */
  evidence: SessionRecord[];
  confidence?: "high" | "medium" | "low";
  /** Anything else worth carrying into the report (an amount, an id, a variant). */
  data?: Record<string, unknown>;
}

/** A finding as it appears in a report: one feature a session exercised, with the records that prove it. */
export interface Finding extends FindingInput {
  feature: string;
  kind: string;
  survey: string;
  session: string;
}

/** Declares what a survey detects and which sessions it applies to. */
export interface SurveySpec {
  /**
   * Stable id of the feature this survey detects, e.g. `payments.checkout.gift-card`. Reports
   * group by it, so several surveys may share one feature. A survey that recognizes many features
   * (`footprints`, or a handler that sets `feature` per footprint) uses it as the family name.
   */
  feature: string;
  /** Default `kind` of this survey's findings. `"feature"` when unset. */
  kind?: string;
  /**
   * Every feature id a handler may report, when that is more than `feature`. Declared features are
   * report rows even when no session matched; `footprints` keys declare themselves.
   */
  features?: ReadonlyArray<string>;
  /** What the feature is, for readers of the report. */
  description?: string;
  /** Only run on sessions whose platform is listed. Unset runs everywhere. */
  platforms?: ReadonlyArray<string>;
  /** Only run on sessions of these apps. */
  appIds?: ReadonlyArray<string>;
  /** Only run on sessions of these trail targets. */
  targets?: ReadonlyArray<string>;
  /** Free-form tags for filtering which surveys a run includes. */
  tags?: ReadonlyArray<string>;
  /**
   * Declarative form: what this feature's footprint looks like. Give this instead of a handler
   * when the feature is "these records were observed" and nothing needs computing.
   */
  footprint?: FootprintSpec;
  /**
   * Declarative form for many features at once: each key is a feature id and its value is that
   * feature's footprint. One finding is reported per key that matches, so one survey can place a
   * session against a whole catalog.
   */
  footprints?: Readonly<Record<string, FootprintSpec>>;
}
