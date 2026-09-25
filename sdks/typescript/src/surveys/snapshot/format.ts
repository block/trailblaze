// A session snapshot: one session's in-memory survey model, written out as JSON.
//
// A survey handler reads a `SurveySession` — lists of records, already parsed. The session
// directory those lists are parsed FROM is mostly bytes no survey reads: a view tree on every driver
// log, every response body, a screenshot a step. A snapshot is the lists themselves, minus those
// bytes, so a page can fetch one, rebuild the session, run surveys over it and let it go.
//
// This file is the shape both halves agree on, and nothing else: `write.ts` produces it from a
// loaded session and needs the filesystem; `read.ts` turns it back into a session and must not.

import type {
  AnalyticsEvent,
  DeviceLogLine,
  LogRecord,
  NetworkRequest,
  Objective,
  ScreenText,
  SessionSummary,
  StreamEvent,
  ToolCall,
  TraceSpan,
} from "../types.js";

/** Bumped when a reader of the previous format would misread this one. */
export const SNAPSHOT_FORMAT = "trailblaze-session-snapshot/1";

/**
 * The longest request body a snapshot keeps, in characters. The bodies surveys read — a payment
 * being completed, a timecard started, an add-on switched on — are hundreds of bytes; what the limit
 * keeps out is the app's own analytics uploads, which run to megabytes EACH and are most of all
 * request-body bytes. In a sample of sessions, the bodies at or under this size are a small fraction
 * of the total, and only a few percent of requests are longer.
 */
export const REQUEST_BODY_LIMIT = 32 * 1024;

/**
 * A log record. `log` is the record's own payload with `droppedKeys` removed — the view trees and
 * the LLM conversation, which are nearly all of a session's bytes and which only screen-matching
 * reads. The keys are named so a reader asking for one is told it was dropped, rather than handed
 * `undefined` and left to conclude the capture had no screen.
 */
export interface SnapshotLog extends Omit<LogRecord, "log"> {
  log: unknown;
  droppedKeys?: string[];
}

/** A tool call. Its `log` is the same object as one of the session's log records, so it is stored as that record's index. */
export interface SnapshotTool extends Omit<ToolCall, "log"> {
  logIndex: number;
}

/**
 * An HTTP exchange. The response body is never kept: it is most of a network capture and no
 * committed survey reads one. A request body is kept up to a size limit — the ones over it are
 * nearly all the app's own analytics uploads — and `requestBodyLength` records a body that was
 * kept out, so reading it throws rather than reads as "posted nothing".
 */
export interface SnapshotNetwork extends Omit<NetworkRequest, "responseBody"> {
  requestBodyLength?: number;
  hadResponseBody?: boolean;
}

/** An analytics event. `raw` is the same object as one stream event's `data`, so it is stored as that event's index. */
export interface SnapshotAnalytics extends Omit<AnalyticsEvent, "raw"> {
  eventIndex: number;
}

/**
 * A stream row. The rows of an in-app network stream carry the request and response bodies all
 * over again — the same exchanges `network` already holds — so their `data` is dropped and
 * `dataDropped` says so.
 */
export interface SnapshotEvent extends Omit<StreamEvent, "data"> {
  data?: unknown;
  dataDropped?: true;
}

export interface SessionSnapshot {
  format: typeof SNAPSHOT_FORMAT;
  summary: SessionSummary;
  logs: SnapshotLog[];
  tools: SnapshotTool[];
  objectives: Objective[];
  network: SnapshotNetwork[];
  analytics: SnapshotAnalytics[];
  events: SnapshotEvent[];
  screenText: ScreenText[];
  deviceLog: DeviceLogLine[];
  trace: TraceSpan[];
}

/** What a page lists before it fetches anything: every session's summary, and which file holds it. */
export interface SnapshotIndex {
  format: typeof SNAPSHOT_FORMAT;
  /** When the batch was built, ISO-8601. Given rather than read off the clock, so the same batch indexes to the same bytes. */
  builtAt?: string;
  sessions: Array<{ file: string; bytes: number; summary: SessionSummary }>;
}

/** Streams whose rows are the in-app network capture, as `session.ts` reads them into `network`. */
export const isNetworkStream = (stream: string): boolean => /network/i.test(stream);
