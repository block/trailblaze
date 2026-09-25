// Behavioral contract for the session-survey module, exercised against a synthetic session
// directory laid out the way the runner and the CI zips write one. Every assertion is on what a
// survey author observes (records found, findings reported, report shape), not on internals.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, rmSync, symlinkSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { SurveySession, survey, discoverSessions, loadSurveys, loadTargetCatalog, matchFootprint, runSurveys, renderMarkdown, targetsOf } from "./index.js";
import { redactUrl } from "./session.js";

const LOG = "xyz.block.trailblaze.logs.client.TrailblazeLog";
const STATUS = "xyz.block.trailblaze.logs.model.SessionStatus";

let root: string;
let hashed: string;
let sequential: string;

function iso(ms: number): string {
  return new Date(ms).toISOString();
}

function toolLog(ms: number, toolName: string, args: Record<string, unknown>, successful = true) {
  return {
    class: `${LOG}.TrailblazeToolLog`,
    session: "fixture",
    timestamp: iso(ms),
    toolName,
    trailblazeTool: { toolName, raw: args },
    successful,
    durationMs: 12,
    llmResponseId: "x",
    exceptionMessage: null,
    deviceName: "sim",
  };
}

function ndjson(rows: unknown[]): string {
  return rows.map((r) => JSON.stringify(r)).join("\n") + "\n";
}

/** A CI-reassembled session: hash-prefixed log files whose order comes only from timestamps. */
function writeHashedSession(dir: string): void {
  mkdirSync(join(dir, "events"), { recursive: true });
  const t0 = 1_700_000_000_000;
  const started = {
    class: `${LOG}.TrailblazeSessionStatusChangeLog`,
    session: "fixture",
    timestamp: iso(t0),
    sessionStatus: {
      class: `${STATUS}.Started`,
      trailConfig: { id: "trail-1", title: "Fixture trail", target: "sampleapp", platform: "ANDROID", metadata: { suite: "smoke", owners: ["payments", "checkout"], tracker: { ticket: "PROJ-1" } } },
      trailblazeDeviceInfo: { platform: "ANDROID", classifiers: ["android", "phone"] },
      targetAppInfo: { appId: "com.example.sample", versionName: "1.2.3" },
    },
  };
  // Written with names that sort in the WRONG order alphabetically, to prove ordering is by time.
  writeFileSync(join(dir, "ff0_TrailblazeSessionStatusChangeLog.json"), JSON.stringify(started));
  writeFileSync(join(dir, "aa1_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0 + 2000, "tapOn", { text: "Pay" })));
  writeFileSync(join(dir, "bb2_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0 + 1000, "launchApp", { appId: "com.example.sample" })));
  writeFileSync(join(dir, "cc3_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0 + 3000, "tapOn", { text: "Missing" }, false)));
  writeFileSync(
    join(dir, "dd4_ObjectiveStartLog.json"),
    JSON.stringify({ class: `${LOG}.ObjectiveStartLog`, session: "fixture", timestamp: iso(t0 + 500), promptStep: { step: "Pay for the order" } }),
  );
  writeFileSync(
    join(dir, "ee5_ObjectiveCompleteLog.json"),
    JSON.stringify({
      class: `${LOG}.ObjectiveCompleteLog`,
      session: "fixture",
      timestamp: iso(t0 + 3500),
      promptStep: { step: "Pay for the order" },
      objectiveResult: { class: "xyz.block.trailblaze.AgentTaskStatus.Success.ObjectiveComplete", llmExplanation: "done" },
    }),
  );
  // A verification step: its prompt lives in `verify`, not `step`.
  writeFileSync(
    join(dir, "ee6_ObjectiveStartLog.json"),
    JSON.stringify({ class: `${LOG}.ObjectiveStartLog`, session: "fixture", timestamp: iso(t0 + 3600), promptStep: { verify: "The receipt screen is displayed" } }),
  );
  writeFileSync(
    join(dir, "ee7_ObjectiveCompleteLog.json"),
    JSON.stringify({
      class: `${LOG}.ObjectiveCompleteLog`,
      session: "fixture",
      timestamp: iso(t0 + 3800),
      promptStep: { verify: "The receipt screen is displayed" },
      objectiveResult: { class: "xyz.block.trailblaze.AgentTaskStatus.Success.ObjectiveComplete", llmExplanation: "Completed via recording" },
    }),
  );
  writeFileSync(
    join(dir, "0a6_TrailblazeSessionStatusChangeLog.json"),
    JSON.stringify({
      class: `${LOG}.TrailblazeSessionStatusChangeLog`,
      session: "fixture",
      timestamp: iso(t0 + 4000),
      sessionStatus: { class: `${STATUS}.Ended.Failed`, durationMs: 4000, exceptionMessage: "boom" },
    }),
  );
  writeFileSync(join(dir, "capture_metadata.json"), JSON.stringify({ class: "not-a-log" }));

  // Framework network capture: phases paired by id; response rows carry no url.
  writeFileSync(
    join(dir, "network.ndjson"),
    ndjson([
      { id: "r1", phase: "REQUEST_START", timestampMs: t0 + 1100, method: "POST", url: "https://api.example.com/v1/orders/checkout?x=1", urlPath: "/v1/orders/checkout", source: "FIXTURE" },
      // The Android capture keeps the query on urlPath; `path:` queries must still see the bare route.
      { id: "r2", phase: "REQUEST_START", timestampMs: t0 + 1200, method: "GET", url: "https://api.example.com/v1/status?fields=all", urlPath: "/v1/status?fields=all", source: "FIXTURE" },
      { id: "r3", phase: "REQUEST_START", timestampMs: t0 + 1300, method: "GET", url: "https://api.example.com/v1/flaky", urlPath: "/v1/flaky", source: "FIXTURE" },
      { id: "r1", phase: "RESPONSE_END", timestampMs: t0 + 1400, method: "", url: "", statusCode: 200, durationMs: 300, source: "FIXTURE" },
      { id: "r2", phase: "RESPONSE_END", timestampMs: t0 + 1450, method: "", url: "", statusCode: 503, durationMs: 250, source: "FIXTURE" },
      { id: "r3", phase: "FAILED", timestampMs: t0 + 1500, method: "", url: "", error: "connection reset", source: "FIXTURE" },
    ]),
  );
  // In-app network stream contributes the bodies the framework capture does not carry.
  writeFileSync(
    join(dir, "events", "app.network.ndjson"),
    ndjson([
      { timeMs: t0 + 1100, data: { request: { _0: { id: "r1", url: "https://api.example.com/v1/orders/checkout?x=1", httpMethod: "POST", humanReadableBody: "amount_money { amount: 500 currency: USD }" } } } },
      { timeMs: t0 + 1400, data: { finalizedResponse: { _0: { requestID: "r1", statusCode: 200, body: '{"order_id":"o-1"}' } } } },
    ]),
  );
  // Analytics in both shapes the loader understands.
  writeFileSync(
    join(dir, "events", "app.analytics.ndjson"),
    ndjson([
      { timeMs: t0 + 1600, data: { columnItems: { Time: "00:00:01", Event: "Payment Complete", Source: "sdk", Properties: JSON.stringify({ payment_id: "p-1", amount: 500 }) } } },
      { timeMs: t0 + 1700, data: { name: "Screen Viewed", source: "flat", properties: { screen: "receipt" } } },
      { timeMs: t0 + 1800, data: { columnItems: { Event: "Broken Properties", Properties: "{not json" } } },
    ]),
  );
  writeFileSync(join(dir, "events", "crash.ndjson"), ndjson([{ timeMs: t0 + 3900, data: { reason: "SIGSEGV" } }]));
  writeFileSync(
    join(dir, "visible-strings.ndjson"),
    ndjson([{ kind: "screen", timeMs: t0 + 2000, strings: [{ text: "Total $5.00", source: "text" }, { text: "Pay", source: "accessibility" }] }]),
  );
  writeFileSync(join(dir, "device.log"), "I/App: payment complete\nE/App: Fatal signal 11\n");
  writeFileSync(
    join(dir, "session_result.json"),
    JSON.stringify({ session_id: "ci-session-42", outcome: "FAILED", platform: "ANDROID", app_id: "com.example.sample", app_version_name: "1.2.3", test_key: "C123", title: "CI title wins", failure_reason: "step 3 failed", ci_job_id: "job-9" }),
  );
}

/** A runner-written session: sequential prefixes are the order, even when timestamps disagree. */
function writeSequentialSession(dir: string): void {
  mkdirSync(dir, { recursive: true });
  const t0 = 1_700_000_100_000;
  writeFileSync(
    join(dir, "000_TrailblazeSessionStatusChangeLog.json"),
    JSON.stringify({
      class: `${LOG}.TrailblazeSessionStatusChangeLog`,
      session: "seq",
      timestamp: iso(t0),
      sessionStatus: { class: `${STATUS}.Started`, trailConfig: { id: "trail-2", title: "Sequential", target: "sampleapp", platform: "IOS" }, trailblazeDeviceInfo: { platform: "IOS", classifiers: ["ios"] }, targetAppInfo: { appId: "com.example.other" } },
    }),
  );
  writeFileSync(join(dir, "001_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0 + 9000, "first", {})));
  writeFileSync(join(dir, "002_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0 + 1000, "second", {})));
  writeFileSync(
    join(dir, "003_TrailblazeSessionStatusChangeLog.json"),
    JSON.stringify({ class: `${LOG}.TrailblazeSessionStatusChangeLog`, session: "seq", timestamp: iso(t0 + 9500), sessionStatus: { class: `${STATUS}.Ended.Succeeded`, durationMs: 9500 } }),
  );
}

beforeAll(() => {
  root = mkdtempSync(join(tmpdir(), "surveys-test-"));
  hashed = join(root, "sessions", "ci-session-42");
  sequential = join(root, "sessions", "seq");
  writeHashedSession(hashed);
  writeSequentialSession(sequential);
});

afterAll(() => rmSync(root, { recursive: true, force: true }));

describe("loading a session", () => {
  test("hash-named log files are ordered by record time, and the metadata sidecar is ignored", () => {
    const s = SurveySession.load(hashed);
    expect(s.logs.all().map((l) => l.type)).toEqual([
      "TrailblazeSessionStatusChangeLog",
      "ObjectiveStartLog",
      "TrailblazeToolLog",
      "TrailblazeToolLog",
      "TrailblazeToolLog",
      "ObjectiveCompleteLog",
      "ObjectiveStartLog",
      "ObjectiveCompleteLog",
      "TrailblazeSessionStatusChangeLog",
    ]);
    expect(s.tools.all().map((t) => t.name)).toEqual(["launchApp", "tapOn", "tapOn"]);
  });

  test("sequentially numbered log files keep their written order even when timestamps disagree", () => {
    const s = SurveySession.load(sequential);
    expect(s.tools.all().map((t) => t.name)).toEqual(["first", "second"]);
  });

  test("the CI verdict file wins over the status logs for the summary", () => {
    const { summary } = SurveySession.load(hashed);
    expect(summary).toMatchObject({
      id: "ci-session-42",
      title: "CI title wins",
      testKey: "C123",
      outcome: "FAILED",
      failureReason: "step 3 failed",
      platform: "android",
      appId: "com.example.sample",
      appVersion: "1.2.3",
      target: "sampleapp",
      hasNetworkCapture: true,
      hasDeviceLog: true,
      metadata: { suite: "smoke", owners: "[\"payments\",\"checkout\"]", tracker: "{\"ticket\":\"PROJ-1\"}" },
      ci: { jobId: "job-9" },
    });
    expect(summary.eventStreams.sort()).toEqual(["app.analytics", "app.network", "crash"]);
  });

  test("without a CI verdict the summary derives from the session status logs", () => {
    const { summary } = SurveySession.load(sequential);
    expect(summary).toMatchObject({ id: "seq", title: "Sequential", outcome: "PASSED", platform: "ios", appId: "com.example.other", durationMs: 9500 });
  });

  test("a directory with no log records is rejected", () => {
    const empty = join(root, "empty");
    mkdirSync(empty, { recursive: true });
    expect(() => SurveySession.load(empty)).toThrow(/Not a session directory/);
  });

  test("a resumed session's timestamped log names are records too", () => {
    const dir = join(root, "resumed");
    writeSequentialSession(dir);
    writeFileSync(join(dir, "004_1700000100000_TrailblazeToolLog.json"), JSON.stringify(toolLog(1_700_000_100_000 + 9600, "third", {})));
    expect(SurveySession.load(dir).tools.all().map((t) => t.name)).toEqual(["first", "second", "third"]);
  });

  test("credential-named tool arguments are redacted in the summary and detail but still matchable", () => {
    const dir = join(root, "secrets");
    writeSequentialSession(dir);
    writeFileSync(join(dir, "004_TrailblazeToolLog.json"), JSON.stringify(toolLog(1_700_000_100_000 + 9600, "login", { username: "sam", password: "hunter2", card: { pin: "1234" } })));
    const s = SurveySession.load(dir);
    const login = s.tools.find({ name: "login", args: { password: "hunter2" } })!;
    expect(login.summary).toContain('username="sam"');
    expect(login.summary).not.toContain("hunter2");
    expect(JSON.stringify(login.detail)).not.toMatch(/hunter2|1234/);
    expect((login.detail as { args: Record<string, unknown> }).args).toEqual({ username: "sam", password: "[redacted]", card: { pin: "[redacted]" } });
  });

  test("a credential in a request's query string is redacted in the evidence detail but still matchable", () => {
    const dir = join(root, "query-secrets");
    writeSequentialSession(dir);
    const t0 = 1_700_000_100_000;
    writeFileSync(
      join(dir, "network.ndjson"),
      ndjson([
        { id: "q1", phase: "REQUEST_START", timestampMs: t0 + 1100, method: "GET", url: "https://api.example.com/v1/session?access_token=s3cret&fields=all", urlPath: "/v1/session", source: "FIXTURE" },
        { id: "q1", phase: "RESPONSE_END", timestampMs: t0 + 1200, method: "", url: "", statusCode: 200, durationMs: 100, source: "FIXTURE" },
      ]),
    );
    const s = SurveySession.load(dir);
    const r = s.network.find({ path: "/v1/session", query: /access_token=s3cret/ })!;
    expect(r).toBeDefined();
    // The query predicate has to actually select, or this row would be found by `path` alone and
    // the assertion above would hold for any parameter value at all.
    expect(s.network.find({ path: "/v1/session", query: /access_token=other/ })).toBeUndefined();
    const detail = r.detail as { url: string; at: string[] };
    expect(detail.url).toBe("https://api.example.com/v1/session?access_token=[redacted]&fields=all");
    expect(JSON.stringify(r.detail) + r.summary).not.toContain("s3cret");
    // The status lives on the response row. Citing only the request row points a reader at a
    // record that says nothing about the outcome the finding rests on.
    expect(r.responseLine).toBe(2);
    expect(detail.at).toEqual(["network.ndjson:1", "network.ndjson:2"]);
  });

  test("a credential in the URL's own authority is redacted too, where no parameter name marks it", () => {
    // `https://user:secret@host/x` carries the secret by position, not under a name, so the
    // parameter pass has nothing to test it against — and it took the early return for a URL with
    // no query string at all, reaching the evidence detail verbatim.
    const dir = join(root, "userinfo-secrets");
    writeSequentialSession(dir);
    const t0 = 1_700_000_100_000;
    writeFileSync(
      join(dir, "network.ndjson"),
      ndjson([
        { id: "u1", phase: "REQUEST_START", timestampMs: t0 + 1100, method: "GET", url: "https://sam:s3cret@api.example.com/v1/session", urlPath: "/v1/session", source: "FIXTURE" },
        { id: "u1", phase: "RESPONSE_END", timestampMs: t0 + 1200, method: "", url: "", statusCode: 200, durationMs: 100, source: "FIXTURE" },
        { id: "u2", phase: "REQUEST_START", timestampMs: t0 + 1300, method: "GET", url: "https://gh0st-t0ken:@api.example.com/v1/other", urlPath: "/v1/other", source: "FIXTURE" },
        { id: "u2", phase: "RESPONSE_END", timestampMs: t0 + 1400, method: "", url: "", statusCode: 200, durationMs: 100, source: "FIXTURE" },
      ]),
    );
    const s = SurveySession.load(dir);
    const pair = s.network.find({ path: "/v1/session" })!;
    expect((pair.detail as { url: string }).url).toBe("https://[redacted]@api.example.com/v1/session");
    // The field that looks like a username goes too. `TOKEN:@host` is how a bearer token is passed,
    // and it is indistinguishable from a login name, so keeping the first field publishes the token.
    const bare = s.network.find({ path: "/v1/other" })!;
    expect((bare.detail as { url: string }).url).toBe("https://[redacted]@api.example.com/v1/other");
    expect(redactUrl("https://TOKEN:@api.example.com/v1/x")).toBe("https://[redacted]@api.example.com/v1/x");
    expect(JSON.stringify([pair.detail, bare.detail]) + pair.summary + bare.summary).not.toContain("s3cret");
    expect(JSON.stringify([pair.detail, bare.detail]) + pair.summary + bare.summary).not.toContain("gh0st-t0ken");
    // A host:port authority is not userinfo and keeps its port.
    expect(redactUrl("https://api.example.com:8443/v1/x")).toBe("https://api.example.com:8443/v1/x");
    // An `@` after the path starts belongs to the path.
    expect(redactUrl("https://api.example.com/users/sam@example.com")).toBe("https://api.example.com/users/sam@example.com");
    // `jwt` is a common parameter for a signed URL's bearer, and it is not a spelling of `token`.
    expect(redactUrl("https://cdn.example.com/a/shot.webp?jwt=ey.J0eXAi.sig&w=4")).toBe("https://cdn.example.com/a/shot.webp?jwt=[redacted]&w=4");
    // Still matched when the key is decorated, since the whole key is tested.
    expect(redactUrl("https://cdn.example.com/x?X-Amz-Jwt=abc")).toBe("https://cdn.example.com/x?X-Amz-Jwt=[redacted]");
  });

  test("a global regex matches every record, not every other one", () => {
    const s = SurveySession.load(hashed);
    const g = /tapOn/g;
    expect(s.tools.filter({ name: g }).length).toBe(2);
    expect(s.logs.filter({ contains: /tapOn/g }).length).toBe(2);
  });
});

describe("network records", () => {
  test("request and response phases are one record, with bodies pulled from the in-app stream", () => {
    const s = SurveySession.load(hashed);
    expect(s.network.length).toBe(3);
    const checkout = s.network.find({ method: "post", path: "/v1/orders/checkout" });
    expect(checkout).toMatchObject({ status: 200, ok: true, failed: false, host: "api.example.com", durationMs: 300, query: "x=1" });
    expect(s.network.find({ path: "/v1/status" })).toMatchObject({ status: 503, query: "fields=all" });
    expect(s.network.find({ path: "/v1/flaky" })?.query).toBeUndefined();
    expect(checkout?.requestBody).toContain("amount: 500");
    expect(checkout?.responseBody).toContain("o-1");
    expect(checkout?.line).toBe(1);
  });

  test("status shorthands select ok, error, and failed requests", () => {
    const s = SurveySession.load(hashed);
    expect(s.network.filter({ status: "ok" }).map((r) => r.path)).toEqual(["/v1/orders/checkout"]);
    expect(s.network.filter({ status: "error" }).map((r) => r.path)).toEqual(["/v1/status"]);
    expect(s.network.filter({ status: "failed" }).map((r) => r.path)).toEqual(["/v1/flaky"]);
    expect(s.network.has({ status: (code) => code === 503 })).toBe(true);
  });

  test("body matching is a case-insensitive substring for strings and a test for regular expressions", () => {
    const s = SurveySession.load(hashed);
    expect(s.network.has({ requestBody: "CURRENCY: usd" })).toBe(true);
    expect(s.network.has({ requestBody: /amount:\s*\d+/ })).toBe(true);
    expect(s.network.has({ requestBody: "EUR" })).toBe(false);
  });
});

describe("analytics and other streams", () => {
  test("both analytics row shapes normalize to name, source, and parsed properties", () => {
    const s = SurveySession.load(hashed);
    const finished = s.analytics.find({ name: "Payment Complete" });
    expect(finished).toMatchObject({ source: "sdk", stream: "app.analytics", properties: { payment_id: "p-1", amount: 500 } });
    expect(s.analytics.find({ name: "Screen Viewed" })).toMatchObject({ source: "flat", properties: { screen: "receipt" } });
    expect(s.analytics.has({ name: "Payment Complete", properties: { payment_id: "p-1" } })).toBe(true);
    expect(s.analytics.has({ name: "Payment Complete", properties: { payment_id: "p-2" } })).toBe(false);
  });

  test("unparseable property strings keep the event and expose the raw text", () => {
    const s = SurveySession.load(hashed);
    const broken = s.analytics.find({ name: "Broken Properties" });
    expect(broken).toBeDefined();
    expect(s.analytics.has({ name: "Broken Properties", contains: "not json" })).toBe(true);
  });

  test("named streams, screen text, and the device log are queryable", () => {
    const s = SurveySession.load(hashed);
    expect(s.stream("crash").length).toBe(1);
    expect(s.events.has({ stream: "crash", data: { reason: "SIGSEGV" } })).toBe(true);
    expect(s.screenText.has({ text: /^Total \$/ })).toBe(true);
    expect(s.deviceLog.has({ text: "fatal signal" })).toBe(true);
  });

  test("objectives pair start and completion and carry the verdict", () => {
    const s = SurveySession.load(hashed);
    expect(s.objectives.all()).toHaveLength(2);
    expect(s.objectives.find({ status: "complete" })).toMatchObject({ step: 1, prompt: "Pay for the order", explanation: "done" });
    expect(s.objectives.find({ prompt: /receipt screen/, status: "complete" })).toMatchObject({ step: 2 });
    expect(s.tools.filter({ name: "tapOn", successful: false }).map((t) => t.args.text)).toEqual(["Missing"]);
  });

  test("a finished step cites the record that proves it finished, and still names where it started", () => {
    const done = SurveySession.load(hashed).objectives.find({ status: "complete" })!;
    expect(done.file).toContain("ObjectiveCompleteLog");
    expect(done.timeMs).toBe(done.endMs);
    expect(done.detail).toMatchObject({ startLog: expect.stringContaining("ObjectiveStartLog") });
  });
});

describe("surveys and the runner", () => {
  const paid = survey({ feature: "sample.payment", description: "A payment completed" }, (session) => {
    const req = session.network.find({ method: "POST", path: "/v1/orders/checkout", status: "ok" });
    const evt = session.analytics.find({ name: "Payment Complete" });
    if (!req || !evt) return;
    return { summary: `Paid ${String(evt.properties.amount)}`, evidence: [req, evt], data: { paymentId: evt.properties.payment_id } };
  });
  const crashed = survey({ feature: "sample.crashed", footprint: { summary: "Crashed", events: { stream: "crash" } } });
  const cleanRun = survey({ feature: "sample.clean-run", footprint: { tools: { successful: true }, absent: { events: { stream: "crash" } } } });
  const iosOnly = survey({ feature: "sample.ios", platforms: ["ios"], footprint: { tools: {} } });
  const broken = survey({ feature: "sample.broken" }, () => {
    throw new Error("survey bug");
  });

  test("findings carry the feature, the survey, evidence with file locations, and data", async () => {
    const report = await runSurveys({ surveys: [paid, crashed, cleanRun, iosOnly, broken], sessions: [hashed, sequential] });
    const ci = report.sessions.find((s) => s.session.id === "ci-session-42")!;
    const payment = ci.findings.find((f) => f.feature === "sample.payment")!;
    expect(payment).toMatchObject({ survey: "sample.payment", summary: "Paid 500", data: { paymentId: "p-1" } });
    expect(payment.evidence.map((e) => `${e.kind}:${e.file}:${e.line}`)).toEqual(["network:network.ndjson:1", "analytics:events/app.analytics.ndjson:1"]);
  });

  test("rules require every query to match and honour absent queries and platform filters", async () => {
    const report = await runSurveys({ surveys: [crashed, cleanRun, iosOnly], sessions: [hashed, sequential] });
    const byId = Object.fromEntries(report.sessions.map((s) => [s.session.id, s]));
    expect(byId["ci-session-42"]!.findings.map((f) => f.feature).sort()).toEqual(["sample.crashed"]);
    expect(byId["seq"]!.findings.map((f) => f.feature).sort()).toEqual(["sample.clean-run", "sample.ios"]);
    expect(byId["ci-session-42"]!.skipped).toContain("sample.ios");
  });

  test("one matching absent query vetoes the footprint even when its other absent queries miss", async () => {
    const strict = survey({ feature: "sample.strict", footprint: { tools: {}, absent: { events: { stream: "crash" }, analytics: { name: "never-logged" } } } });
    const report = await runSurveys({ surveys: [strict], sessions: [hashed, sequential] });
    const byId = Object.fromEntries(report.sessions.map((s) => [s.session.id, s]));
    expect(byId["ci-session-42"]!.findings).toEqual([]);
    expect(byId["seq"]!.findings.map((f) => f.feature)).toEqual(["sample.strict"]);
  });

  test("a never-matched footprint keeps its own kind in the feature table", async () => {
    const mixed = survey({ feature: "sample.mixed", footprints: { "sample.seen": { tools: {} }, "sample.gap": { kind: "gap", analytics: { name: "never-logged" } } } });
    const report = await runSurveys({ surveys: [mixed], sessions: [sequential] });
    expect(report.features.map((f) => [f.feature, f.kind, f.findings])).toEqual([
      ["sample.seen", "feature", 1],
      ["sample.gap", "gap", 0],
    ]);
  });

  test("a throwing survey is reported per session and does not stop the others", async () => {
    const report = await runSurveys({ surveys: [broken, crashed], sessions: [hashed] });
    const only = report.sessions[0]!;
    expect(only.errors).toEqual([expect.objectContaining({ survey: "sample.broken", message: expect.stringContaining("survey bug") })]);
    expect(only.findings.map((f) => f.feature)).toEqual(["sample.crashed"]);
  });

  test("a finding with no evidence is rejected, so nothing reaches the report unproven", async () => {
    const unproven = survey({ feature: "sample.unproven" }, () => ({ summary: "The run looked fine to me", evidence: [] }));
    const report = await runSurveys({ surveys: [unproven, crashed], sessions: [hashed] });
    const only = report.sessions[0]!;
    expect(only.errors).toEqual([expect.objectContaining({ survey: "sample.unproven", message: expect.stringContaining("no evidence") })]);
    expect(only.findings.map((f) => f.feature)).toEqual(["sample.crashed"]);
  });

  test("the feature summary counts sessions once even when a survey reports several findings", async () => {
    const twice = survey({ feature: "sample.twice" }, (session) => session.tools.filter({ name: "tapOn" }).map((t) => ({ summary: t.summary, evidence: [t] })));
    const report = await runSurveys({ surveys: [twice], sessions: [hashed, sequential] });
    expect(report.features).toEqual([expect.objectContaining({ feature: "sample.twice", sessionsMatched: 1, sessionIds: ["ci-session-42"] })]);
    expect(report.features[0]!.findings).toBe(2);
    expect(renderMarkdown(report)).toContain("| sample.twice | 1/2 | 2 | sample.twice |");
  });

  test("a markdown cell escapes a backslash before it escapes a pipe, so neither can break the table", async () => {
    // A summary that already carries an escaped pipe: `a\|b`. Escaping the pipe alone would
    // produce `a\\|b`, which markdown reads as a literal backslash followed by a column break.
    const tricky = survey({ feature: "sample.tricky" }, (session) => session.tools.filter({ name: "tapOn" }).slice(0, 1)
      .map((t) => ({ summary: "a\\|b", evidence: [t] })));
    const report = await runSurveys({ surveys: [tricky], sessions: [hashed] });
    expect(renderMarkdown(report)).toContain("— a\\\\\\|b");
  });

  test("one survey can declare many features and reports each that matched under its own id", async () => {
    const catalog = survey({
      feature: "sample.catalog",
      description: "Sample feature catalog",
      footprints: {
        checkout: { summary: "Checked out", network: { method: "POST", path: "/v1/orders/checkout", status: "ok" } },
        "crash-free": { kind: "quality", tools: { successful: true }, absent: { events: { stream: "crash" } } },
        never: { analytics: { name: "No Such Event" } },
      },
    });
    const report = await runSurveys({ surveys: [catalog], sessions: [hashed, sequential] });
    const byId = Object.fromEntries(report.sessions.map((s) => [s.session.id, s]));
    expect(byId["ci-session-42"]!.findings.map((f) => [f.feature, f.kind, f.summary, f.survey])).toEqual([["checkout", "feature", "Checked out", "sample.catalog"]]);
    expect(byId["seq"]!.findings.map((f) => [f.feature, f.kind, f.summary])).toEqual([["crash-free", "quality", "crash-free"]]);
    // Declared features are report rows even at zero matches; the survey's family name is not.
    expect(report.features.map((f) => `${f.feature}:${f.kind}:${f.sessionsMatched}`)).toEqual(["checkout:feature:1", "crash-free:quality:1", "never:feature:0"]);
    expect(report.surveys[0]).toMatchObject({ id: "sample.catalog", features: ["checkout", "crash-free", "never"] });
    expect(renderMarkdown(report)).toContain("| crash-free | quality | 1/2 | 1 | sample.catalog |");
    expect(() => survey({ feature: "sample.empty", footprints: {} })).toThrow(/no entries/);
    expect(() => survey({ feature: "sample.both", footprint: { tools: {} }, footprints: { a: { tools: {} } } })).toThrow(/use one/);
    // An empty query list is not a query: unrejected, it would match every session with no evidence.
    expect(() => survey({ feature: "sample.hollow", footprint: { network: [] } })).toThrow(/no queries/);
    expect(() => survey({ feature: "sample.hollow", footprints: { a: { network: [], analytics: [] } } })).toThrow(/no queries/);
    expect(matchFootprint(SurveySession.load(hashed), { network: [], analytics: { name: "No Such Event" } })).toBeUndefined();
  });

  test("a handler can place each finding under a feature and kind of its own", async () => {
    const perTool = survey({ feature: "sample.tools", kind: "gap", features: ["tool.tapOn", "tool.swipe"] }, (session) =>
      session.tools.filter({ name: "tapOn" }).map((t) => ({ feature: `tool.${t.name}`, summary: t.summary, evidence: [t] })),
    );
    const report = await runSurveys({ surveys: [perTool], sessions: [hashed] });
    expect(report.sessions[0]!.findings.map((f) => [f.feature, f.kind])).toEqual([["tool.tapOn", "gap"], ["tool.tapOn", "gap"]]);
    expect(report.features.find((f) => f.feature === "tool.tapOn")).toMatchObject({ kind: "gap", sessionsMatched: 1, findings: 2, surveys: ["sample.tools"] });
    // Declared but unreported features are rows; the family name is not one when features are declared.
    expect(report.features.map((f) => `${f.feature}:${f.sessionsMatched}`)).toEqual(["tool.tapOn:1", "tool.swipe:0"]);
    expect(renderMarkdown(report)).toContain("**gap: tool.tapOn**");
  });

  test("matchFootprint evaluates one declarative footprint against a loaded session", () => {
    const session = SurveySession.load(hashed);
    expect(matchFootprint(session, { events: { stream: "crash" } })?.map((e) => e.kind)).toEqual(["event"]);
    expect(matchFootprint(session, { tools: {}, absent: { events: { stream: "crash" } } })).toBeUndefined();
  });

  test("a session filter keeps only matching sessions and lists the rest as excluded", async () => {
    const report = await runSurveys({
      surveys: [crashed],
      sessions: [hashed, sequential],
      where: (s) => s.platform === "ios" && s.deviceClassifiers.includes("ios"),
    });
    expect(report.sessions.map((s) => s.session.id)).toEqual(["seq"]);
    expect(report.excluded).toEqual([{ id: "ci-session-42", path: hashed }]);
  });

  test("an unreadable session path is reported, not fatal", async () => {
    const report = await runSurveys({ surveys: [crashed], sessions: [join(root, "does-not-exist")] });
    expect(report.sessions).toHaveLength(0);
    expect(report.unreadable).toEqual([expect.objectContaining({ path: join(root, "does-not-exist") })]);
  });
});

describe("targets", () => {
  test("a survey scoped to a target also runs on sessions that drove one of the target's apps", async () => {
    const acmeOnly = survey({ feature: "acme.any", targets: ["acme"], footprint: { tools: {} } });
    // The fixture's trail named `sampleapp`, so without a catalog `acme` is a different target.
    const bare = await runSurveys({ surveys: [acmeOnly], sessions: [hashed] });
    expect(bare.sessions[0]!.skipped).toEqual(["acme.any"]);
    // With a catalog saying acme owns the app the session drove, it belongs to acme too.
    const catalog = { acme: { id: "acme", appIds: ["com.example.sample"], platforms: { android: ["com.example.sample"] } } };
    const withCatalog = await runSurveys({ surveys: [acmeOnly], sessions: [hashed], targets: catalog });
    expect(withCatalog.sessions[0]!.findings.map((f) => f.feature)).toEqual(["acme.any"]);
    expect(targetsOf(withCatalog.sessions[0]!.session, catalog)).toEqual(["sampleapp", "acme"]);
  });

  test("a catalog is read from trailmap.yaml files: id plus app ids across platforms", () => {
    const dir = join(root, "trailmaps", "acme");
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      join(dir, "trailmap.yaml"),
      [
        "id: acme",
        "dependencies: [shared]",
        "target:",
        "  display_name: Acme",
        "  platforms:",
        "    android:",
        "      app_ids:",
        "        - com.example.sample",
        "        - com.example.sample.debug",
        "      tool_sets: [core]",
        "    ios:",
        "      app_ids:",
        "        - com.example.sample",
        "",
      ].join("\n"),
    );
    mkdirSync(join(root, "trailmaps", "shared"), { recursive: true });
    writeFileSync(join(root, "trailmaps", "shared", "trailmap.yaml"), "id: shared\ntools: []\n");
    const catalog = loadTargetCatalog([join(root, "trailmaps")]);
    expect(Object.keys(catalog)).toEqual(["acme"]);
    expect(catalog.acme).toMatchObject({
      appIds: ["com.example.sample", "com.example.sample.debug"],
      platforms: { android: ["com.example.sample", "com.example.sample.debug"], ios: ["com.example.sample"] },
    });
  });
});

describe("discovery", () => {
  test("session directories are found under a parent, and survey files by suffix with the export name as id", async () => {
    const found = await discoverSessions([join(root, "sessions")]);
    expect(found.sort()).toEqual([hashed, sequential].sort());

    const dir = join(root, "surveys");
    mkdirSync(dir, { recursive: true });
    writeFileSync(
      join(dir, "one.survey.ts"),
      `import { trailblaze } from ${JSON.stringify(join(import.meta.dir, "index.ts"))};\nexport const helper = 1;\nexport const alpha = trailblaze.survey({ feature: "a", footprint: { tools: {} } });\n`,
    );
    writeFileSync(join(dir, "ignored.ts"), `export const beta = 2;\n`);
    const loaded = await loadSurveys([dir]);
    expect(loaded.map((c) => c.definition.id)).toEqual(["alpha"]);
  });

  test("a survey file named twice, directly and through its directory, loads once", async () => {
    const dir = join(root, "once");
    mkdirSync(dir, { recursive: true });
    const file = join(dir, "solo.survey.ts");
    writeFileSync(file, `import { trailblaze } from ${JSON.stringify(join(import.meta.dir, "index.ts"))};\nexport const solo = trailblaze.survey({ feature: "a", footprint: { tools: {} } });\n`);
    const loaded = await loadSurveys([dir, file]);
    expect(loaded.map((c) => c.definition.id)).toEqual(["solo"]);
  });

  test("two zips with the same file name extract to different places", async () => {
    const a = join(root, "zips", "build-a");
    const b = join(root, "zips", "build-b");
    for (const [dir, name] of [[a, "session-a"], [b, "session-b"]] as const) {
      writeSequentialSession(join(dir, name));
      const zip = spawnSync("zip", ["-qr", "logs.zip", name], { cwd: dir });
      expect(zip.status).toBe(0);
    }
    const extractDir = join(root, "zips", "extracted");
    const found = await discoverSessions([join(a, "logs.zip"), join(b, "logs.zip")], { extractDir });
    expect(found.map((p) => basename(p)).sort()).toEqual(["session-a", "session-b"]);
  });

  test("a zip that links out of itself does not hand over the machine's own sessions", async () => {
    // A zip can carry symlinks and `unzip` recreates them. Following one meant a link named after
    // anything at all could pull in sessions that were never in the archive — the agent's own log
    // directory, say — and everything found this way ends up in snapshots that get published.
    const box = join(root, "escape", "box");
    const elsewhere = join(root, "escape", "not-in-the-zip");
    writeSequentialSession(elsewhere);
    // A real session inside the archive too, so passing cannot just mean the zip was unreadable.
    writeSequentialSession(join(box, "from-the-zip"));
    symlinkSync(elsewhere, join(box, "borrowed"));
    // `-y` stores the link as a link rather than following it, which is the case under test.
    expect(spawnSync("zip", ["-qry", "payload.zip", "box"], { cwd: join(root, "escape") }).status).toBe(0);

    const said: string[] = [];
    const found = await discoverSessions([join(root, "escape", "payload.zip")], {
      extractDir: join(root, "escape", "unpacked"),
      log: (m) => said.push(m),
    });
    expect(found.map((p) => basename(p))).toEqual(["from-the-zip"]);
    expect(said.join("\n")).toContain("outside the archive");
  });

  test("two files exporting the same survey name is an error", async () => {
    const dir = join(root, "dupes");
    mkdirSync(dir, { recursive: true });
    const src = `import { trailblaze } from ${JSON.stringify(join(import.meta.dir, "index.ts"))};\nexport const same = trailblaze.survey({ feature: "a", footprint: { tools: {} } });\n`;
    writeFileSync(join(dir, "a.survey.ts"), src);
    writeFileSync(join(dir, "b.survey.ts"), src);
    await expect(loadSurveys([dir])).rejects.toThrow(/exported by both/);
  });
});

describe("a session the runtime restarted partway through", () => {
  // The write counter lives in memory, so a resumed session numbers its second batch from 1 again
  // and both batches claim `001`. The resumed records are the ones carrying a timestamp in the
  // name, which is how the runtime keeps them from overwriting the first batch's files.
  function writeResumedSession(dir: string): void {
    mkdirSync(dir, { recursive: true });
    const t0 = 1_700_000_200_000;
    const write = (name: string, ms: number, tool: string) => writeFileSync(join(dir, name), JSON.stringify(toolLog(ms, tool, {})));
    write("001_TrailblazeToolLog.json", t0 + 1000, "before-restart-first");
    write("002_TrailblazeToolLog.json", t0 + 2000, "before-restart-second");
    write(`001_${t0 + 3000}_TrailblazeToolLog.json`, t0 + 3000, "after-restart-first");
    write(`002_${t0 + 4000}_TrailblazeToolLog.json`, t0 + 4000, "after-restart-second");
  }

  test("reads in the order it happened, instead of interleaving the two batches that share ordinals", () => {
    const dir = join(root, "restart-collision");
    writeResumedSession(dir);
    const s = SurveySession.load(dir);
    expect(s.tools.all().map((t) => t.name)).toEqual([
      "before-restart-first",
      "before-restart-second",
      "after-restart-first",
      "after-restart-second",
    ]);
  });

  test("and so never goes backwards in time, which is what the lookups over it assume", () => {
    const dir = join(root, "restart-collision-monotonic");
    writeResumedSession(dir);
    const times = SurveySession.load(dir).logs.all().map((l) => l.timeMs ?? 0);
    expect(times).toEqual([...times].sort((a, b) => a - b));
    // `findLast` is the lookup that a non-monotonic order answers wrongly rather than incompletely.
    expect(SurveySession.load(dir).tools.findLast({})?.name).toBe("after-restart-second");
  });

  test("a record with no timestamp sorts last instead of jumping to the front", () => {
    // The colliding-ordinal path falls back to timestamp order; a record missing one — corrupted,
    // since every real log type requires the field — used to fall back further, to 0, hoisting it
    // ahead of every timestamped record and reintroducing the same backwards-in-time read this sort
    // exists to close. Its own write-order ordinal is not a substitute: it is tiny next to an
    // epoch-ms timestamp, so using it would land the record at the front just the same.
    const dir = join(root, "restart-collision-no-timestamp");
    mkdirSync(dir, { recursive: true });
    const t0 = 1_700_000_500_000;
    writeFileSync(join(dir, "001_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0 + 1000, "before-restart-first", {})));
    writeFileSync(join(dir, "002_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0 + 2000, "before-restart-second", {})));
    const untimed = toolLog(t0 + 3000, "after-restart-untimed", {}) as { timestamp?: string };
    delete untimed.timestamp;
    writeFileSync(join(dir, `001_${t0 + 3000}_TrailblazeToolLog.json`), JSON.stringify(untimed));
    writeFileSync(join(dir, `002_${t0 + 4000}_TrailblazeToolLog.json`), JSON.stringify(toolLog(t0 + 4000, "after-restart-second", {})));

    expect(SurveySession.load(dir).tools.all().map((t) => t.name)).toEqual([
      "before-restart-first",
      "before-restart-second",
      "after-restart-second",
      "after-restart-untimed",
    ]);
  });
});

describe("the blob a captured body points at", () => {
  /** A session whose one request's body lives at `blobPath`, wherever the caller put that. */
  function writeBlobSession(dir: string, blobPath: string): void {
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "001_TrailblazeToolLog.json"), JSON.stringify(toolLog(1_700_000_300_000, "launchApp", {})));
    writeFileSync(
      join(dir, "network.ndjson"),
      ndjson([
        {
          id: "r1",
          phase: "REQUEST_START",
          timestampMs: 1_700_000_300_100,
          method: "POST",
          url: "https://api.example.com/v1/orders",
          urlPath: "/v1/orders",
          source: "FIXTURE",
          requestBodyRef: { blobPath },
        },
      ]),
    );
  }

  test("is read when it is really inside the session, including under a symlinked parent", () => {
    // `/tmp` is a symlink on macOS, so resolving the session directory is load-bearing for the
    // ordinary case and not only for the hostile one.
    const outer = mkdtempSync(join(tmpdir(), "surveys-blob-"));
    mkdirSync(join(outer, "real"));
    symlinkSync(join(outer, "real"), join(outer, "link"));
    const dir = join(outer, "link", "session");
    writeBlobSession(dir, "blobs/body.json");
    mkdirSync(join(dir, "blobs"), { recursive: true });
    writeFileSync(join(dir, "blobs", "body.json"), '{"amount":500}');

    expect(SurveySession.load(dir).network.find({})?.requestBody).toBe('{"amount":500}');
    rmSync(outer, { recursive: true, force: true });
  });

  test("is refused when it is a symlink out of the session, so a zip cannot read the host", () => {
    // Unpacking restores symlinks, so a session directory from elsewhere can point a body at any
    // file the reader can open. Resolving the path lexically let it through and then followed it.
    const outer = mkdtempSync(join(tmpdir(), "surveys-blob-"));
    const secret = join(outer, "id_rsa");
    writeFileSync(secret, "PRIVATE-KEY-MATERIAL");
    const dir = join(outer, "session");
    writeBlobSession(dir, "blobs/body.json");
    mkdirSync(join(dir, "blobs"), { recursive: true });
    symlinkSync(secret, join(dir, "blobs", "body.json"));

    const request = SurveySession.load(dir).network.find({});
    expect(request?.requestBody).toBeUndefined();
    // And it is nowhere in the record a finding would cite, not merely absent from `requestBody`.
    expect(JSON.stringify(request)).not.toContain("PRIVATE-KEY");
    rmSync(outer, { recursive: true, force: true });
  });

  test("is refused when it climbs out of the session with ..", () => {
    const outer = mkdtempSync(join(tmpdir(), "surveys-blob-"));
    writeFileSync(join(outer, "outside.json"), "NOT-OURS");
    const dir = join(outer, "session");
    writeBlobSession(dir, "../outside.json");

    expect(SurveySession.load(dir).network.find({})?.requestBody).toBeUndefined();
    rmSync(outer, { recursive: true, force: true });
  });

  test("a rejected blob is not cited as evidence either, only read as a body", () => {
    // Naming a path this loader refused to read is its own leak: a report reader who trusts `at`
    // entries as files this session actually produced would be pointed at a location outside it.
    const outer = mkdtempSync(join(tmpdir(), "surveys-blob-"));
    writeFileSync(join(outer, "outside.json"), "NOT-OURS");
    const dir = join(outer, "session");
    writeBlobSession(dir, "../outside.json");

    const request = SurveySession.load(dir).network.find({});
    expect(request?.bodyFiles).toBeUndefined();
    expect(JSON.stringify(request?.detail)).not.toContain("outside.json");
    rmSync(outer, { recursive: true, force: true });
  });

  test("a blob that IS inside the session is still cited", () => {
    const dir = join(root, "blob-citation");
    writeBlobSession(dir, "blobs/body.json");
    mkdirSync(join(dir, "blobs"), { recursive: true });
    writeFileSync(join(dir, "blobs", "body.json"), '{"amount":500}');

    const request = SurveySession.load(dir).network.find({});
    expect(request?.bodyFiles).toEqual(["blobs/body.json"]);
    expect(request?.detail).toMatchObject({ at: expect.arrayContaining(["blobs/body.json"]) });
  });
});
