// Which records a mid-trail survey reads from each stream.
//
// A declarative survey's finding says what it looks for, so the host can drop most records before
// they cross into JavaScript: memory then grows with the matches, not with the session. The host's
// match is only a prefilter. It must keep every record the survey's own matcher would accept, and
// the matcher still runs on what comes back, so findings are the same as reading everything.
// Wherever the host's match can't promise that, the stream is read whole.

import type { AnalyticsQuery, EventQuery, NetworkQuery, TextMatch } from "./match.js";
import { matchText } from "./match.js";
import { findingOf, isDeclarative, type FindingQueries, type SurveyDefinition } from "./survey.js";

/** How one stream is read. */
export type StreamRead =
  /** Every record. */
  | { kind: "full" }
  /** Only records the host's `match` keeps. `absentOnly`: only `absent` queries read them, so a count of zero settles it. */
  | { kind: "filtered"; match: Record<string, unknown>; absentOnly: boolean }
  /** No records, only whether it is captured and whether its capture lost data. */
  | { kind: "health" };

export interface ReadPlan {
  /** The read for [stream]; a stream that appears mid-run is planned when it appears. */
  readFor(stream: string): StreamRead;
}

/** Reads every stream whole: a handler's reads can't be seen. */
export const READ_EVERYTHING: ReadPlan = { readFor: () => ({ kind: "full" }) };

const NETWORK_STREAM = "network";

/**
 * Which records [def] needs from each stream. A handler needs every one. A finding needs:
 *  - for `network`: the network stream and every in-app network stream, whole. A request is joined
 *    from several rows by id, so a row can't be judged on its own: its response status is on another;
 *  - for `events`: each stream its `stream` names, whole. `contains` and `where` test the row's
 *    JavaScript JSON, which the host's match can't reproduce (number formatting, escaping);
 *  - for `analytics`: each analytics stream its `stream` names, filtered by event name when every
 *    query on that stream names one the host can match exactly or more loosely (see [nameSource]),
 *    else whole.
 * A stream no query reads is read for its capture health only.
 */
export function planReads(def: SurveyDefinition): ReadPlan {
  const finding = isDeclarative(def) ? findingOf(def.spec) : undefined;
  if (!finding) return READ_EVERYTHING;
  const sides: Array<{ queries: FindingQueries; absent: boolean }> = [{ queries: finding, absent: false }];
  if (finding.absent) sides.push({ queries: finding.absent, absent: true });
  return {
    readFor(stream) {
      let full = false;
      const names: Array<{ source: string; ignoreCase: boolean }> = [];
      let presence = false;
      for (const { queries, absent } of sides) {
        if (list<NetworkQuery>(queries.network).length > 0 && (stream === NETWORK_STREAM || /network/i.test(stream))) full = true;
        if (stream !== NETWORK_STREAM && list<EventQuery>(queries.events).some((q) => streamMayMatch(stream, q.stream))) full = true;
        if (!/analytic/i.test(stream)) continue;
        for (const q of list<AnalyticsQuery>(queries.analytics)) {
          if (!streamMayMatch(stream, q.stream)) continue;
          const name = nameSource(q.name);
          if (!name) full = true;
          else names.push(name);
          if (!absent) presence = true;
        }
      }
      if (full) return { kind: "full" };
      if (names.length === 0) return { kind: "health" };
      const source = [...new Set(names.map((n) => n.source))].join("|");
      const regex = names.some((n) => n.ignoreCase) ? { $regex: source, $flags: "i" } : { $regex: source };
      return { kind: "filtered", match: { columnItems: regex }, absentOnly: !presence };
    },
  };
}

/**
 * The host match every row an analytics read can turn into an event must pass: a table row. A flat
 * row (`{name, ...}`) carries its name at the top level, which a field match can't reach without
 * also keeping every other row, so a stream holding one is read whole.
 */
export const TABLE_ROW: Record<string, unknown> = { columnItems: {} };

/**
 * Never true of a record, so a count with it reads no record into the result and still says
 * whether the stream is captured, where its cursor is, and whether its capture lost data. Its
 * literal also lets the host skip each line on a byte search.
 */
export const NO_RECORD: Record<string, unknown> = { "trailblaze.health": "trailblaze-no-such-record" };

/** Whether a query's `stream` filter could accept [stream]. A predicate is not called here: it may. */
function streamMayMatch(stream: string, filter: TextMatch | undefined): boolean {
  if (filter === undefined || typeof filter === "function") return true;
  return matchText(stream, filter);
}

/**
 * A regex the host tests on a table row's `columnItems` JSON that matches whenever [name] matches
 * the event's name there, or undefined when no such regex is sure to:
 *  - a string `X` becomes `"X"`, the JSON of the string, which the row's JSON holds whenever a
 *    column equals `X`. One with a control character or an unpaired surrogate is read whole, since
 *    the host and JavaScript may escape those differently;
 *  - a `RegExp` that is a plain literal of printable ASCII becomes that literal, case-insensitive
 *    when it is: JavaScript folds case for ASCII as the host does, except with `u`/`v`, which also
 *    fold letters outside ASCII onto it (the Kelvin sign onto `k`), so those are read whole;
 *  - a predicate, a pattern with any operator, and no name at all are read whole.
 */
export function nameSource(name: TextMatch | undefined): { source: string; ignoreCase: boolean } | undefined {
  if (typeof name === "string") {
    if (/[\u0000-\u001f\ud800-\udfff]/.test(name)) return undefined;
    return { source: `"${escapeRegex(name.replace(/[\\"]/g, (c) => `\\${c}`))}"`, ignoreCase: false };
  }
  if (name instanceof RegExp) {
    if (!/^[\x20-\x7e]+$/.test(name.source) || /[\\^$.|?*+()[\]{}"]/.test(name.source)) return undefined;
    const ignoreCase = name.flags.includes("i");
    if (ignoreCase && /[uv]/.test(name.flags)) return undefined;
    return { source: name.source, ignoreCase };
  }
  return undefined;
}

function escapeRegex(text: string): string {
  return text.replace(/[\\^$.|?*+()[\]{}]/g, (c) => `\\${c}`);
}

function list<T>(v: T | ReadonlyArray<T> | undefined): ReadonlyArray<T> {
  if (v === undefined) return [];
  return Array.isArray(v) ? (v as ReadonlyArray<T>) : [v as T];
}
