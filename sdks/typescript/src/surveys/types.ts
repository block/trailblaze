// Public data model for session surveys.
//
// A survey is a named check over one session — its Trailblaze log records plus every captured
// sidecar (network requests, analytics streams, device log, visible screen text). It reports
// whatever it finds, each finding backed by the concrete records that prove it. This file holds
// only the shapes; `session.ts` loads them and `survey.ts` defines the authoring surface.

import type { TrailblazeLog, TrailblazeToolLog } from "../generated/trailrunner-dtos.js";

/** Where a piece of evidence came from. Drives how a report renders it. */
import type { FindingSpec } from "./survey.js";

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

/**
 * One row of any `events/<stream>.ndjson` sidecar, undecoded. `T` is the shape its reader says the
 * row has (see `SurveySession.stream`); nothing checks it.
 */
export interface StreamEvent<T = unknown> extends SessionRecord {
  kind: "event";
  stream: string;
  data: T;
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

/** One string in the view tree at some capture. */
export interface ScreenText extends SessionRecord {
  kind: "screen-text";
  text: string;
  /** Which accessibility attribute carried it (`text`, `contentDescription`, ...). */
  source: string;
  /** Names the capture; opaque. The screenshot's file name when it has one, else the id its log was stamped with. */
  captureId: string;
  /** The screenshot the capture took, by file name. Absent when the capture has no screenshot. */
  screenshot?: string;
  /** Which capture, in session order, starting at 0. */
  stepIndex: number;
  /** False when it was in the tree but scrolled away or hidden when captured. */
  visible: boolean;
  /** Its box as `[left, top, right, bottom]` in device points, when recorded. */
  bounds?: [number, number, number, number];
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

/**
 * What a survey hands back for one match. The runner fills in `survey` and `session`. A finding is
 * a one-line `summary`, the `evidence` that proves it, and whatever `data` its survey chooses to
 * carry: like an event stream's rows, its shape is the survey's own, and the readers that know the
 * survey know what to expect in it.
 */
export interface FindingInput<D extends object = Record<string, unknown>> {
  /** One line: what happened in this session. */
  summary: string;
  /** The records that prove it. Query results can be passed straight through. */
  evidence: SessionRecord[];
  /** Anything else worth carrying into the report (an id, an amount, a variant), in any shape. */
  data?: D;
}

/** A finding as it appears in a report: what one survey found in one session, with the records that prove it. */
export interface Finding<D extends object = Record<string, unknown>> extends FindingInput<D> {
  /** The survey that found it: its export name. */
  survey: string;
  session: string;
}

/**
 * Declares what a survey checks and which sessions it applies to. The survey's name is the name it
 * is exported under. A survey with a handler needs nothing here; a declarative one needs a
 * `description`, or a `summary` on its finding, to say what it found.
 */
export interface SurveySpec {
  /** What the survey checks, for readers of the report. */
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
   * True when the survey needs the finished session: its outcome or duration, logs, tool calls,
   * screen text, the device log, the trace, or any file. Such a survey runs only after the run, on
   * the host under Bun, where it may import anything.
   *
   * Default false: the survey also runs while the trail is running, from a tool
   * (`ctx.surveys`), on the host or on a device. It then sees only what is captured
   * so far in the session's event streams (`network`, `analytics`, `events`) and runs under the
   * same rules as a scripted tool, so it cannot import `node:*` or Bun APIs.
   */
  requiresFinishedSession?: boolean;
  /**
   * Declarative form: the finding the survey reports and the queries that prove it. Give this
   * instead of a handler when the check is "these records were observed" and nothing needs
   * computing.
   */
  finding?: FindingSpec;
  /** @deprecated Renamed to {@link finding}; still read when `finding` is unset. */
  footprint?: FindingSpec;
}
