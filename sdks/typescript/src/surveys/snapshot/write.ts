// A loaded session, written out as a snapshot (see `format.ts`).
//
// Redacted, because a snapshot is built to be published beside a page other people open, and a
// session carries the credentials its trail typed.

import { redactUrl, type SurveySession } from "../session.js";
import { REQUEST_BODY_LIMIT, SNAPSHOT_FORMAT, isNetworkStream, type SessionSnapshot, type SnapshotLog } from "./format.js";
import { redactDeep } from "./redact.js";

/**
 * Payload keys dropped from every log record: a whole view tree, or a whole LLM conversation. These
 * are ~95% of the bytes of a session's logs (23 MB of 31 MB across 43 local sessions) and no survey
 * over a snapshot reads them. Dropping the keys rather than truncating the payload keeps the rest of
 * the record intact, and the snapshot names which keys went so a read of one fails by name.
 */
const DROPPED_PAYLOAD_KEYS: ReadonlyArray<string> = [
  "viewHierarchy",
  // The same capture filtered, and the accessibility-shape tree the selector migration logs
  // alongside it. A record can carry all three trees at once, so dropping only the first leaves
  // most of the bytes behind.
  "viewHierarchyFiltered",
  "trailblazeNodeTree",
  "driverMigrationTreeNode",
  "viewHierarchyText",
  "llmMessages",
  // The other half of the conversation. A transcript is not half a transcript.
  "llmResponse",
  // The JSON schema of every tool offered to the model: 2.4 MB of the 2.5 MB an LLM request log
  // weighs, and the same catalog every time.
  "toolOptions",
];

const DROPPED = new Set(DROPPED_PAYLOAD_KEYS);

const redacted = <T>(v: T): T => redactDeep(v) as T;

function logOf(record: ReturnType<SurveySession["logs"]["all"]>[number]): SnapshotLog {
  const { log, ...rest } = record;
  if (log === null || typeof log !== "object" || Array.isArray(log)) return redacted({ ...rest, log });
  const kept: Record<string, unknown> = {};
  const droppedKeys: string[] = [];
  for (const [k, v] of Object.entries(log)) {
    if (DROPPED.has(k)) {
      // A null tree is the capture saying "no screen", which is an answer and costs nothing to keep.
      if (v === null || v === undefined) kept[k] = v;
      else droppedKeys.push(k);
    } else kept[k] = v;
  }
  return redacted({ ...rest, log: kept, ...(droppedKeys.length > 0 ? { droppedKeys } : {}) });
}

export function snapshotOf(session: SurveySession): SessionSnapshot {
  const logs = session.logs.all();
  const logIndex = new Map<unknown, number>(logs.map((r, i) => [r.log, i]));
  const events = session.events.all();
  const eventIndex = new Map<unknown, number>(events.map((e, i) => [e.data, i]));

  return {
    format: SNAPSHOT_FORMAT,
    // The directory is this machine's, and nothing a survey reads needs it.
    summary: redacted({ ...session.summary, path: session.summary.id }),
    logs: logs.map(logOf),
    tools: session.tools.all().map(({ log, ...rest }) => {
      const index = logIndex.get(log);
      if (index === undefined) throw new Error(`Tool call "${rest.name}" in ${session.id} has no log record of its own to point at.`);
      return redacted({ ...rest, logIndex: index });
    }),
    objectives: redacted(session.objectives.all()),
    network: session.network.all().map(({ responseBody, requestBody, url, query, ...rest }) => {
      const keep = requestBody !== undefined && requestBody.length <= REQUEST_BODY_LIMIT;
      return {
        ...redacted({
          ...rest,
          ...(keep ? { requestBody } : {}),
          ...(requestBody !== undefined && !keep ? { requestBodyLength: requestBody.length } : {}),
          ...(responseBody !== undefined ? { hadResponseBody: true } : {}),
        }),
        // Redacted as a URL, not as prose: the prose pass misses `user:password@` in the authority,
        // and takes every parameter after a token with it, losing the ones a survey reads.
        url: redactUrl(url),
        ...(query !== undefined ? { query: redactUrl(`?${query}`).slice(1) } : {}),
      };
    }),
    analytics: session.analytics.all().map(({ raw, ...rest }) => {
      const index = eventIndex.get(raw);
      if (index === undefined) throw new Error(`Analytics event "${rest.name}" in ${session.id} has no stream row of its own to point at.`);
      return redacted({ ...rest, eventIndex: index });
    }),
    events: events.map(({ data, ...rest }) =>
      isNetworkStream(rest.stream) ? redacted({ ...rest, dataDropped: true as const }) : redacted({ ...rest, data }),
    ),
    screenText: redacted(session.screenText.all()),
    deviceLog: redacted(session.deviceLog.all()),
    trace: redacted(session.trace.all()),
  };
}
