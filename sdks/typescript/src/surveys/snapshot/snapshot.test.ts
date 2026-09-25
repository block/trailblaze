// A session written as a snapshot and read back must be the session a survey reads from disk,
// minus exactly the bytes the snapshot drops — and each of those must throw by name when read,
// never read as `undefined`.
//
// So the session is compared against the same session loaded from DISK, kind by kind, rather than
// against values written out by hand: a field the writer forgets is caught, where a hand-written
// expectation would just be updated to match.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { existsSync, mkdirSync, mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

import { SurveySession } from "../session.js";
import { main } from "./cli.js";
import { REQUEST_BODY_LIMIT, SNAPSHOT_FORMAT } from "./format.js";
import { NotInSnapshotError, sessionFromSnapshot } from "./read.js";
import { snapshotOf } from "./write.js";

const LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog";
const STATUS = "xyz.block.trailblaze.logs.model.SessionStatus";
const T0 = 1_700_000_000_000;
const iso = (ms: number) => new Date(ms).toISOString();
const ndjson = (rows: unknown[]) => rows.map((r) => JSON.stringify(r)).join("\n") + "\n";
const TREE = { text: "Pay", children: [{ text: "Total $5.00" }] };

let root: string;
let fromDisk: SurveySession;
let fromSnapshot: SurveySession;

function writeSession(dir: string): void {
  mkdirSync(join(dir, "events"), { recursive: true });
  const w = (name: string, body: unknown) => writeFileSync(join(dir, name), JSON.stringify(body));
  w("000_TrailblazeSessionStatusChangeLog.json", {
    class: `${LOG}.TrailblazeSessionStatusChangeLog`,
    session: "snap",
    timestamp: iso(T0),
    sessionStatus: {
      class: `${STATUS}.Started`,
      trailConfig: { id: "trail-1", title: "Snapshot", target: "sampleapp", platform: "ANDROID" },
      trailblazeDeviceInfo: { platform: "ANDROID", classifiers: ["android", "phone"] },
      targetAppInfo: { appId: "com.example.sample" },
    },
  });
  w("001_TrailblazeToolLog.json", {
    class: `${LOG}.TrailblazeToolLog`,
    session: "snap",
    timestamp: iso(T0 + 1000),
    toolName: "tapOn",
    trailblazeTool: { toolName: "tapOn", raw: { text: "Pay" } },
    successful: true,
    durationMs: 30,
  });
  // A driver log with a screen, and one whose capture recorded none.
  w("002_AgentDriverLog.json", { class: `${LOG}.MaestroDriverLog`, session: "snap", timestamp: iso(T0 + 1500), action: { class: "TapPoint", x: 1, y: 2 }, viewHierarchy: TREE });
  w("003_AgentDriverLog.json", { class: `${LOG}.MaestroDriverLog`, session: "snap", timestamp: iso(T0 + 1600), action: { class: "TapPoint", x: 3, y: 4 }, viewHierarchy: null });
  w("004_ObjectiveStartLog.json", { class: `${LOG}.ObjectiveStartLog`, session: "snap", timestamp: iso(T0 + 500), promptStep: { step: "Pay" } });
  w("005_TrailblazeSessionStatusChangeLog.json", {
    class: `${LOG}.TrailblazeSessionStatusChangeLog`,
    session: "snap",
    timestamp: iso(T0 + 4000),
    sessionStatus: { class: `${STATUS}.Ended.Succeeded`, durationMs: 4000 },
  });
  writeFileSync(
    join(dir, "network.ndjson"),
    ndjson([
      { id: "r1", phase: "REQUEST_START", timestampMs: T0 + 1100, method: "POST", url: "https://api.example.com/v1/orders", urlPath: "/v1/orders", source: "FIXTURE", requestBodyRef: { inlineText: `{"order_id":"o-1"}` } },
      { id: "r1", phase: "RESPONSE_END", timestampMs: T0 + 1400, method: "", url: "", statusCode: 200, durationMs: 300, source: "FIXTURE", responseBodyRef: { inlineText: `{"ok":true}` } },
      { id: "r2", phase: "REQUEST_START", timestampMs: T0 + 1700, method: "GET", url: "https://api.example.com/v1/status", urlPath: "/v1/status", source: "FIXTURE" },
      { id: "r2", phase: "RESPONSE_END", timestampMs: T0 + 1750, method: "", url: "", statusCode: 503, durationMs: 50, source: "FIXTURE" },
      { id: "r3", phase: "REQUEST_START", timestampMs: T0 + 1760, method: "POST", url: "https://api.example.com/v1/log", urlPath: "/v1/log", source: "FIXTURE", requestBodyRef: { inlineText: "e".repeat(REQUEST_BODY_LIMIT + 1) } },
      { id: "r3", phase: "RESPONSE_END", timestampMs: T0 + 1770, method: "", url: "", statusCode: 200, durationMs: 10, source: "FIXTURE" },
    ]),
  );
  // The app's own copy of the network capture, which repeats every body.
  writeFileSync(join(dir, "events", "app.network.ndjson"), ndjson([{ timeMs: T0 + 1100, data: { request: { id: "r1", url: "https://api.example.com/v1/orders", httpMethod: "POST", body: `{"order_id":"o-1"}` } } }]));
  writeFileSync(
    join(dir, "events", "app.analytics.ndjson"),
    ndjson([{ timeMs: T0 + 1800, data: { name: "Payment Complete", source: "flat", properties: { amount: 500 } } }]),
  );
  writeFileSync(join(dir, "events", "crash.ndjson"), ndjson([{ timeMs: T0 + 3900, data: { reason: "SIGSEGV" } }]));
  writeFileSync(join(dir, "visible-strings.ndjson"), ndjson([{ kind: "screen", timestamp: iso(T0 + 2000), captureId: "c1", stepIndex: 0, strings: [{ text: "Total $5.00", source: "text" }] }]));
  writeFileSync(join(dir, "device.log"), "I/App: payment complete\n");
  writeFileSync(join(dir, "trace.json"), JSON.stringify([{ name: "launch", cat: "app", ph: "X", ts: 1_000, dur: 2_500, args: {} }]));
}

beforeAll(() => {
  root = mkdtempSync(join(tmpdir(), "snapshot-"));
  const dir = join(root, "session");
  writeSession(dir);
  fromDisk = SurveySession.load(dir);
  // Through JSON, as a page receives it.
  fromSnapshot = sessionFromSnapshot(JSON.parse(JSON.stringify(snapshotOf(fromDisk))));
});

afterAll(() => rmSync(root, { recursive: true, force: true }));

/** A record list as plain data. A field the snapshot refuses is non-enumerable, so it drops out here. */
const plain = (records: unknown): unknown => JSON.parse(JSON.stringify(records));

describe("a session read back from its snapshot", () => {
  test("carries every record of every kind the disk session does", () => {
    for (const kind of ["tools", "objectives", "analytics", "screenText", "deviceLog", "trace"] as const) {
      expect({ kind, records: plain(fromSnapshot[kind].all()) }).toEqual({ kind, records: plain(fromDisk[kind].all()) });
    }
    expect(fromSnapshot.logs.length).toBe(fromDisk.logs.length);
    expect(fromSnapshot.network.length).toBe(fromDisk.network.length);
    expect(fromSnapshot.events.length).toBe(fromDisk.events.length);
    expect(fromSnapshot.summary).toEqual({ ...fromDisk.summary, path: fromDisk.summary.id });
  });

  test("keeps a tool call's log and an analytics event's raw row as the records they came from", () => {
    const tool = fromSnapshot.tools.find({ name: "tapOn" })!;
    expect(tool.log).toBe(fromSnapshot.logs.find({ type: "TrailblazeToolLog" })!.log as never);
    const event = fromSnapshot.analytics.find({ name: "Payment Complete" })!;
    expect(event.raw).toBe(fromSnapshot.stream("app.analytics")[0]!.data);
    // So a `contains:` query, which reads `raw`, answers as it does on disk.
    expect(fromSnapshot.analytics.filter({ contains: "amount" })).toHaveLength(1);
  });

  test("refuses a dropped view tree by name, and keeps a tree the capture recorded as absent", () => {
    const [withTree, without] = fromSnapshot.logs.filter({ type: "MaestroDriverLog" });
    expect(() => (withTree!.log as unknown as { viewHierarchy: unknown }).viewHierarchy).toThrow(NotInSnapshotError);
    expect(() => (withTree!.log as unknown as { viewHierarchy: unknown }).viewHierarchy).toThrow(/not its `viewHierarchy`/);
    expect((without!.log as unknown as { viewHierarchy: unknown }).viewHierarchy).toBeNull();
    // Everything else on the record is there.
    expect((withTree!.log as unknown as { action: unknown }).action).toEqual({ class: "TapPoint", x: 1, y: 2 });
  });

  test("a text search over logs refuses rather than misses what the snapshot left out", () => {
    // The text is only inside the dropped view tree: on disk it is found, from a snapshot it cannot be.
    expect(fromDisk.logs.filter({ contains: "Total $5.00" })).toHaveLength(1);
    expect(() => fromSnapshot.logs.filter({ contains: "Total $5.00" })).toThrow(NotInSnapshotError);
    // A search scoped to records that lost nothing still answers.
    expect(fromSnapshot.logs.filter({ type: "TrailblazeToolLog", contains: "tapOn" })).toHaveLength(1);
  });

  test("keeps request bodies under the limit, refuses the rest and every response body", () => {
    const orders = fromSnapshot.network.find({ path: "/v1/orders" })!;
    expect(orders.requestBody).toBe(`{"order_id":"o-1"}`);
    expect(() => orders.responseBody).toThrow(/Response bodies are not carried/);
    // A request that had no body is an answer, not a refusal.
    expect(fromSnapshot.network.find({ path: "/v1/status" })!.requestBody).toBeUndefined();
    expect(() => fromSnapshot.network.find({ path: "/v1/log" })!.requestBody).toThrow(/32,769 characters, longer than the 32 KB/);
    // Matching on path never reads the body, so the upload is still found.
    expect(fromSnapshot.network.filter({ status: 503 })).toHaveLength(1);
  });

  test("refuses the data of an in-app network stream row, and keeps every other stream's", () => {
    expect(fromSnapshot.stream("crash")[0]!.data).toEqual({ reason: "SIGSEGV" });
    expect(() => fromSnapshot.stream("app.network")[0]!.data).toThrow(/carries once, as `session.network`/);
  });

  test("refuses to be read by a reader of another format", () => {
    const snapshot = { ...snapshotOf(fromDisk), format: "trailblaze-session-snapshot/0" };
    expect(() => sessionFromSnapshot(snapshot as never)).toThrow(`this page reads "${SNAPSHOT_FORMAT}"`);
  });
});

test("a snapshot is redacted, in every shape a credential reaches a session in", () => {
  const dir = join(root, "secret");
  writeSession(dir);
  // Prose inside the trail's own context block, which is a string as far as any walker can tell.
  writeFileSync(
    join(dir, "000_TrailblazeSessionStatusChangeLog.json"),
    JSON.stringify({
      class: `${LOG}.TrailblazeSessionStatusChangeLog`,
      session: "snap",
      timestamp: iso(T0),
      sessionStatus: {
        class: `${STATUS}.Started`,
        trailConfig: { id: "trail-1", title: "Snapshot", target: "sampleapp", platform: "ANDROID", context: "login credentials -> email: buyer@example.com password: hunter2" },
        trailblazeDeviceInfo: { platform: "ANDROID", classifiers: ["android", "phone"] },
        targetAppInfo: { appId: "com.example.sample" },
      },
    }),
  );
  // A field named for it; a name/value pair, where the credential's NAME is another field's value;
  // and `cardCvv` / `passphrase`, whose names share no word with any other credential.
  writeFileSync(
    join(dir, "006_TrailblazeToolLog.json"),
    JSON.stringify({
      class: `${LOG}.TrailblazeToolLog`,
      session: "snap",
      timestamp: iso(T0 + 3000),
      toolName: "launchApp",
      trailblazeTool: { toolName: "launchApp", raw: { password: "hunter2", extras: [{ key: "password", value: "hunter2" }], cardCvv: "737", passphrase: "correct horse" } },
      successful: true,
      durationMs: 5,
    }),
  );
  writeFileSync(
    join(dir, "network.ndjson"),
    ndjson([{ id: "r1", phase: "REQUEST_START", timestampMs: T0 + 1100, method: "GET", url: "https://api.example.com/v1/orders", urlPath: "/v1/orders", source: "FIXTURE", requestHeaders: { cookie: "session=abc123", accept: "application/json" } }]),
  );
  writeFileSync(join(dir, "device.log"), "I/App: payment complete\nE/App: token=shouldbegone\n");
  // The CI verdict: a failure reason in prose, and metadata whose KEY is the credential beside one
  // whose value carries it and one that is neither and must survive.
  writeFileSync(
    join(dir, "session_result.json"),
    JSON.stringify({
      session_id: "snap",
      outcome: "FAILED",
      failure_reason: "step 3 failed after sign-in with password: hunter2",
      metadata: { authToken: "metadata-secret", runNote: "signed in with password: hunter2", gitBranch: "main" },
    }),
  );

  const snapshot = snapshotOf(SurveySession.load(dir));
  const text = JSON.stringify(snapshot);
  for (const secret of ["hunter2", "737", "correct horse", "abc123", "shouldbegone", "metadata-secret"]) expect(text).not.toContain(secret);
  // The shape is kept, so a reader can still see that a credential was passed.
  expect(text).toContain(`{"key":"password","value":"…"}`);
  expect(text).toContain(`"cardCvv":"…"`);
  expect(snapshot.summary.metadata).toMatchObject({ authToken: "…", gitBranch: "main" });
  expect(snapshot.network[0]!.requestHeaders).toEqual({ cookie: "…", accept: "application/json" });
});

test("a record carrying every tree and both halves of the conversation keeps none of them", () => {
  // One LLM request log really can carry all of these at once, which is what a migration capture
  // logs. Named here rather than read from the writer's list, so a key dropped from that list fails.
  const bulk = ["viewHierarchy", "viewHierarchyFiltered", "trailblazeNodeTree", "driverMigrationTreeNode", "viewHierarchyText", "llmMessages", "llmResponse", "toolOptions"];
  const dir = join(root, "every-tree");
  writeSession(dir);
  const usage = { inputTokens: 38200, outputTokens: 74, promptCost: 0.00764, inputTokenBreakdown: { toolDescriptors: { tokens: 33368 } } };
  writeFileSync(
    join(dir, "006_TrailblazeLlmRequestLog.json"),
    JSON.stringify({
      class: `${LOG}.TrailblazeLlmRequestLog`,
      session: "snap",
      timestamp: iso(T0 + 3000),
      llmRequestLabel: "Screen Analyzer",
      llmRequestUsageAndCost: usage,
      toolCatalogId: "c0ffee",
      ...Object.fromEntries(bulk.map((k) => [k, { bulk: `${k}-bytes` }])),
    }),
  );

  const text = JSON.stringify(snapshotOf(SurveySession.load(dir)));
  for (const k of bulk) expect(text).not.toContain(`${k}-bytes`);
  const llm = sessionFromSnapshot(JSON.parse(text)).logs.find({ type: "TrailblazeLlmRequestLog" })!.log as unknown as Record<string, unknown>;
  for (const k of bulk) expect(() => llm[k]).toThrow(NotInSnapshotError);
  // The model's own numbers are the reason to keep an LLM record at all, and they survive.
  expect(llm.llmRequestLabel).toBe("Screen Analyzer");
  expect(llm.llmRequestUsageAndCost).toEqual(usage);
  expect(llm.toolCatalogId).toBe("c0ffee");
});

test("a request URL loses its credentials and keeps its other parameters", () => {
  const dir = join(root, "credentialed-url");
  writeSession(dir);
  const url = "https://alice:hunter2@api.example.com/v1/orders?access_token=tok-1&page=2";
  writeFileSync(
    join(dir, "network.ndjson"),
    ndjson([{ id: "r1", phase: "REQUEST_START", timestampMs: T0 + 1100, method: "GET", url, urlPath: "/v1/orders?access_token=tok-1&page=2", source: "FIXTURE" }]),
  );
  const [request] = snapshotOf(SurveySession.load(dir)).network;
  const text = JSON.stringify(request);
  for (const secret of ["alice", "hunter2", "tok-1"]) expect(text).not.toContain(secret);
  expect(request.url).toContain("api.example.com/v1/orders?");
  expect(request.url).toContain("page=2");
  expect(request.query).toContain("page=2");
});

describe("the snapshot CLI", () => {
  test("refuses an output that would delete the sessions it reads", async () => {
    const runs = join(root, "cli-runs");
    writeSession(join(runs, "s1"));
    for (const out of [runs, join(runs, "s1"), root]) {
      expect(await main(["--out", out, runs])).toBe(1);
      expect(existsSync(join(runs, "s1", "001_TrailblazeToolLog.json"))).toBe(true);
    }
  });

  test("writes beside the sessions it reads", async () => {
    const runs = join(root, "cli-beside");
    writeSession(join(runs, "s1"));
    const out = join(root, "cli-beside-out");
    expect(await main(["--out", out, runs])).toBe(0);
    for (const file of ["index.json", "0.json.gz"]) expect(existsSync(join(out, file))).toBe(true);
  });
});
