// `ctx.events`: read this session's event streams mid-run, from any tool, in either runtime.
//
// Matching happens in Kotlin, over the stream file's bytes: a line without the literals a match
// needs is skipped before it is decoded, and a query reads only the bytes after its starting point.
// So a check like "did the LOGIN journey end since the app launched?" stays cheap even on a device
// with no host. Which streams exist is up to the trailmap that captures them.

import type { EventMatch, SessionEvent } from "./trailhead.js";
import { encodeMatch } from "./trailhead.js";

/** Reserved host functions behind [SessionEvents]. Mirrors `SessionEventsHostFunctions.kt`. */
export const HOST_FUNCTION_SESSION_EVENTS_LIST = "trailblaze.sessionEvents.list";
export const HOST_FUNCTION_SESSION_EVENTS_QUERY = "trailblaze.sessionEvents.query";
export const HOST_FUNCTION_SESSION_EVENTS_MARK = "trailblaze.sessionEvents.mark";

/**
 * Where a query starts. `"session"` (default): the start of the stream. `"trailhead"` / `"trail"`:
 * when this trail's trailhead started / returned. Any other string names a mark from
 * `ctx.events.mark(name)`.
 */
export type SessionEventsSince = "session" | "trailhead" | "trail" | (string & {});

export interface SessionEventsQueryOptions {
  match?: EventMatch;
  since?: SessionEventsSince;
  /** A `cursor` from an earlier result: read only what arrived after it. Wins over `since`. */
  after?: number;
  /** Wait up to this long for `minCount` matches. Default 0: one immediate read. */
  waitMs?: number;
  /** Matches to wait for. Default 1. */
  minCount?: number;
  /** Most records returned. Default and ceiling are set by the framework. */
  limit?: number;
  /** The last matches to arrive, newest first, read from the end of the stream. */
  newest?: boolean;
}

/** What a query read. */
export interface SessionEventsScan {
  bytesRead: number;
  lines: number;
  /** Lines that held every literal of the match and so were decoded. */
  parsed: number;
  /** Lines over the size cap, skipped. */
  oversized: number;
  waitedMs: number;
}

export interface SessionEventsQueryResult {
  /**
   * False when the stream has no file in this session: nothing captured it, so "no events" proves
   * nothing. Tell that apart from "captured, and nothing matched".
   */
  captured: boolean;
  /**
   * False when a capture writing these streams recorded losing data, or the read skipped a record
   * over 4 MB, so a missing record may have been dropped. Check it before treating "no match" as proof something didn't happen.
   */
  complete: boolean;
  /** What the capture said it lost, when `complete` is false. */
  incompleteReason?: string;
  events: SessionEvent[];
  /** Pass as `after` to read only what arrives next. */
  cursor: number;
  /** Inclusive host-clock timestamp cutoff resolved for a time-based starting point. A cursor alone does not retain it. */
  timeFloorMs?: number;
  /**
   * True when the matches reached the size budget for one result (about 4 MB of record text) and
   * stopped early. An oldest-first read continues from `cursor` passed as `after`.
   */
  truncated?: boolean;
  /** The file the stream was read from, relative to the session directory, when it has one. */
  file?: string;
  scan: SessionEventsScan;
}

export interface SessionEventsCountResult {
  captured: boolean;
  /** False when a capture writing these streams recorded losing data; see the query result. */
  complete: boolean;
  incompleteReason?: string;
  count: number;
  cursor: number;
  /** Inclusive host-clock timestamp cutoff resolved for a time-based starting point. */
  timeFloorMs?: number;
  scan: SessionEventsScan;
}

export interface SessionEventsMark {
  name: string;
  timeMs: number;
  /** Streams that existed when the mark was taken. One that appears later starts after it. */
  streams: number;
}

export interface SessionEvents {
  /** Streams present in this session right now. */
  list(): Promise<string[]>;
  /** Matching records. Filtered in Kotlin, so a large stream never loads into JavaScript. */
  query(stream: string, options?: SessionEventsQueryOptions): Promise<SessionEventsQueryResult>;
  /** How many records match, with no payloads crossing into JavaScript. */
  count(stream: string, options?: SessionEventsQueryOptions): Promise<SessionEventsCountResult>;
  /** Name this point in every stream. Re-marking moves it; do not overwrite a mark while a survey using it is running. */
  mark(name: string): Promise<SessionEventsMark>;
}

type HostCall = (args: unknown) => Promise<unknown>;

export function createSessionEvents(host: unknown): SessionEvents {
  const call = (name: string, args: Record<string, unknown>): Promise<unknown> => {
    const fn = (host as Record<string, HostCall> | undefined)?.[name];
    if (typeof fn !== "function") {
      throw new Error(`ctx.events is unavailable: this runtime has no host function '${name}'.`);
    }
    return fn(args);
  };
  const queryArgs = (stream: string, options: SessionEventsQueryOptions | undefined): Record<string, unknown> => {
    // Every key is sent, not just the known ones, so the host rejects a misspelled option
    // instead of it vanishing here and quietly changing the query.
    const args: Record<string, unknown> = { stream };
    for (const [key, value] of Object.entries(options ?? {})) {
      if (value === undefined || key === "stream") continue;
      args[key] = key === "match" ? encodeMatch(value as EventMatch, "ctx.events.query") : value;
    }
    return args;
  };
  return {
    async list() {
      return (await call(HOST_FUNCTION_SESSION_EVENTS_LIST, {})) as string[];
    },
    async query(stream, options) {
      return (await call(HOST_FUNCTION_SESSION_EVENTS_QUERY, queryArgs(stream, options))) as SessionEventsQueryResult;
    },
    async count(stream, options) {
      const args = { ...queryArgs(stream, options), count: true };
      return (await call(HOST_FUNCTION_SESSION_EVENTS_QUERY, args)) as SessionEventsCountResult;
    },
    async mark(name) {
      return (await call(HOST_FUNCTION_SESSION_EVENTS_MARK, { name })) as SessionEventsMark;
    },
  };
}
