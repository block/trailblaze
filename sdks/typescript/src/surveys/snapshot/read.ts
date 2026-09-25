// A snapshot (see `format.ts`) back into the session a survey handler is given.
//
// Runs in a browser tab, so nothing here may touch `node:fs` or import anything that does.
//
// The rule: what the snapshot left out throws by name when read. A
// dropped view tree read as `undefined` is a screen that "was never captured"; a dropped response
// body read as `undefined` is a request that "returned nothing". Both are wrong answers that look
// like right ones, so both are errors instead.

import { Records, matchAnalytics, matchDeviceLog, matchEvent, matchLog, matchNetwork, matchObjective, matchScreenText, matchTool, matchTrace } from "../match.js";
import type { AnalyticsQuery, DeviceLogQuery, EventQuery, LogQuery, NetworkQuery, ObjectiveQuery, ScreenTextQuery, ToolQuery, TraceQuery } from "../match.js";
import type { SurveySession } from "../session.js";
import type { AnalyticsEvent, LogRecord, NetworkRequest, StreamEvent, ToolCall } from "../types.js";
import { REQUEST_BODY_LIMIT, SNAPSHOT_FORMAT, type SessionSnapshot } from "./format.js";

/**
 * Thrown by a field the snapshot left out. Carries the field, so a caller can group. A runner stops
 * on one rather than reporting half a batch: "12 sessions exercised this" is not an answer to a
 * question asked of 200.
 */
export class NotInSnapshotError extends Error {
  constructor(readonly field: string, message: string) {
    super(message);
    this.name = "NotInSnapshotError";
  }
}

/**
 * Defines `field` on `record` as a getter that throws. Leaving it `undefined` instead is the failure
 * this file is arranged against: `complete.requestBody ?? ""` reads a body that was never carried as
 * a payment that never happened.
 */
function absentField(record: object, field: string, why: string): void {
  Object.defineProperty(record, field, {
    enumerable: false,
    configurable: true,
    get(): never {
      throw new NotInSnapshotError(field, why);
    },
  });
}

const RUN_LOCALLY = "Run the survey over the session directories to read it.";

const TREE_WHY = (key: string, type: string) =>
  `These snapshots carry every log record but not its \`${key}\`: a ${type}'s view tree and LLM conversation are nearly all of a session's bytes, and are left out. ${RUN_LOCALLY}`;

export function sessionFromSnapshot(snapshot: SessionSnapshot): SurveySession {
  if (snapshot.format !== SNAPSHOT_FORMAT) {
    throw new Error(`This session snapshot is "${String(snapshot.format)}", and this page reads "${SNAPSHOT_FORMAT}". Rebuild the page's sessions.`);
  }
  const summary = snapshot.summary;

  const logs: LogRecord[] = snapshot.logs.map(({ droppedKeys, ...rest }) => {
    const record = rest as LogRecord;
    for (const key of droppedKeys ?? []) absentField(record.log as object, key, TREE_WHY(key, record.type));
    return record;
  });

  const events: StreamEvent[] = snapshot.events.map(({ dataDropped, ...rest }) => {
    const record = rest as StreamEvent;
    if (dataDropped) {
      absentField(record, "data", `Rows of the \`${record.stream}\` stream are the app's network capture, which this page carries once, as \`session.network\` (request line, headers, status, request body). Read that instead, or ${RUN_LOCALLY.charAt(0).toLowerCase()}${RUN_LOCALLY.slice(1)}`);
    }
    return record;
  });

  const tools: ToolCall[] = snapshot.tools.map(({ logIndex, ...rest }) => ({ ...rest, log: logs[logIndex]!.log as ToolCall["log"] }));

  const network: NetworkRequest[] = snapshot.network.map(({ requestBodyLength, hadResponseBody, ...rest }) => {
    const record = rest as NetworkRequest;
    if (requestBodyLength !== undefined) {
      absentField(
        record,
        "requestBody",
        `This request's body is ${requestBodyLength.toLocaleString("en-US")} characters, longer than the ${REQUEST_BODY_LIMIT / 1024} KB this page keeps — the bodies it drops are mostly the app's own analytics uploads. ${RUN_LOCALLY}`,
      );
    }
    if (hadResponseBody) {
      absentField(record, "responseBody", `Response bodies are not carried by this page — they are most of a network capture. ${RUN_LOCALLY}`);
    }
    return record;
  });

  // Same object as the stream row it was read from, as on disk.
  const analytics: AnalyticsEvent[] = snapshot.analytics.map(({ eventIndex, ...rest }) => {
    const record = rest as AnalyticsEvent;
    Object.defineProperty(record, "raw", { enumerable: true, configurable: true, get: () => events[eventIndex]!.data });
    return record;
  });

  const noFiles = (what: string): never => {
    throw new NotInSnapshotError("file", `${what} needs the session directory, and this session came from a snapshot instead. Its records are here; its files are not.`);
  };
  const session: SurveySession = {
    dir: summary.path,
    summary,
    get id(): string {
      return summary.id;
    },
    logs: new Records<LogRecord, LogQuery>(logs, matchLog),
    tools: new Records<ToolCall, ToolQuery>(tools, matchTool),
    objectives: new Records<SessionSnapshot["objectives"][number], ObjectiveQuery>(snapshot.objectives, matchObjective),
    network: new Records<NetworkRequest, NetworkQuery>(network, matchNetwork),
    analytics: new Records<AnalyticsEvent, AnalyticsQuery>(analytics, matchAnalytics),
    events: new Records<StreamEvent, EventQuery>(events, matchEvent),
    screenText: new Records<SessionSnapshot["screenText"][number], ScreenTextQuery>(snapshot.screenText, matchScreenText),
    deviceLog: new Records<SessionSnapshot["deviceLog"][number], DeviceLogQuery>(snapshot.deviceLog, matchDeviceLog),
    trace: new Records<SessionSnapshot["trace"][number], TraceQuery>(snapshot.trace, matchTrace),
    stream: (name: string) => session.events.filter({ stream: name }),
    hasFile: () => noFiles("Asking whether a session file exists"),
    readText: () => noFiles("Reading a session file"),
    readJson: () => noFiles("Reading a session file"),
    evidence: (summaryLine: string, file: string, extra = {}) => ({ kind: "custom", summary: summaryLine, file, ...extra }),
  };
  return session;
}
