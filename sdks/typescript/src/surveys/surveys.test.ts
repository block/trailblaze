// Behavioral contract for the session-survey module, exercised against a synthetic session
// directory laid out the way the runner and the CI zips write one. Every assertion is on what a
// survey author observes (records found, findings reported, report shape), not on internals.

import { afterAll, beforeAll, describe, expect, test } from "bun:test";
import { spawnSync } from "node:child_process";
import { mkdtempSync, mkdirSync, readFileSync, rmSync, symlinkSync, utimesSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { basename, join } from "node:path";
import { SurveySession, survey, discoverSessions, findWorkspaceConfigDir, loadSurveys, loadTargetCatalog, loadWorkspace, matchFinding, matchFootprint, runSurveys, renderMarkdown, targetsOf } from "./index.js";
import { redactUrl } from "./session.js";
import { withId } from "./survey.js";

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
    expect(s.trailblazeLogs.all().map((l) => l.type)).toEqual([
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
    expect(s.trailblazeLogs.filter({ contains: /tapOn/g }).length).toBe(2);
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

  test("ofType keeps only the Trailblaze logs of the types it names, and any verb takes a plain function", () => {
    const s = SurveySession.load(hashed);
    const taps = s.trailblazeLogs.ofType("TrailblazeToolLog").filter((r) => r.log.toolName === "tapOn");
    expect(taps.map((r) => [r.type, r.log.toolName])).toEqual([["TrailblazeToolLog", "tapOn"], ["TrailblazeToolLog", "tapOn"]]);
    expect(s.trailblazeLogs.ofType("ObjectiveStartLog", "ObjectiveCompleteLog").all().map((r) => r.type)).toEqual(["ObjectiveStartLog", "ObjectiveCompleteLog", "ObjectiveStartLog", "ObjectiveCompleteLog"]);
    expect(s.tools.count((t) => t.name === "tapOn")).toBe(2);
    expect(s.trailblazeLogs.findLast((r) => r.type === "TrailblazeSessionStatusChangeLog")).toBe(s.trailblazeLogs.findLast({ type: "TrailblazeSessionStatusChangeLog" }));
  });

  test("`logs` is the old name of the Trailblaze logs, not a second list", () => {
    const s = SurveySession.load(hashed);
    expect(s.logs).toBe(s.trailblazeLogs);
  });

  test("a stream read with a named shape hands back its rows typed, as they were captured", () => {
    const s = SurveySession.load(hashed);
    const crashes = s.stream<{ reason: string }>("crash");
    // The shape only types the rows; they are the captured ones, unchanged.
    expect(crashes.map((c) => c.data.reason.toLowerCase())).toEqual(["sigsegv"]);
    expect(crashes).toEqual(s.stream("crash"));
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

  test("XCTest's logging inside the app is left out of an iOS device log, continuation lines too", () => {
    const dir = join(root, "ios-xctest-chatter");
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "001_TrailblazeToolLog.json"), JSON.stringify(toolLog(1_700_000_400_000, "tapOn", {})));
    writeFileSync(join(dir, "device.log"), [
      "Timestamp               Ty Process[PID:TID]",
      "2026-10-15 11:44:54.364 Df Sample[7:a1] [com.apple.dt.xctest:Default] Other faulted in {",
      '    "XC_kAXXCAttributeChildren" = ();',
      "}: (no error)",
      "2026-10-15 11:44:54.365 Df Sample[7:a1] [com.example.sample:payments] payment complete",
      "    receipt sent",
      "2026-10-15 11:44:54.366 Df Sample[7:a1] [com.apple.dt.xctest:Default] Got attributes",
      // A dotted sub-subsystem, which the capture's prefix match leaves out too.
      "2026-10-15 11:44:54.366 Df Sample[7:a1] [com.apple.dt.xctest.automation:Default] Snapshot",
      // The app's own entry, quoting XCTest output in its message, stays.
      "2026-10-15 11:44:54.367 E  Sample[7:a1] [com.example.sample:diagnostics] Previous error: [com.apple.dt.xctest:Default] query failed",
      "    retrying",
    ].join("\n") + "\n");
    const lines = SurveySession.load(dir).deviceLog.all();
    expect(lines.map((l) => l.text)).toEqual([
      "Timestamp               Ty Process[PID:TID]",
      "2026-10-15 11:44:54.365 Df Sample[7:a1] [com.example.sample:payments] payment complete",
      "    receipt sent",
      "2026-10-15 11:44:54.367 E  Sample[7:a1] [com.example.sample:diagnostics] Previous error: [com.apple.dt.xctest:Default] query failed",
      "    retrying",
    ]);
    // Still the file's line numbers, so evidence points at the line that holds it.
    expect(lines.map((l) => l.line)).toEqual([1, 5, 6, 9, 10]);
  });

  test("a capture written as a repeat of an earlier screen still shows that screen, at its own time", () => {
    // The strings file collapses a screen seen again into a pointer at the step that first showed
    // it, so after A, B, A the third capture has no strings of its own.
    const dir = join(root, "repeated-screen");
    mkdirSync(dir, { recursive: true });
    const t0 = 1_700_000_300_000;
    writeFileSync(join(dir, "001_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0, "tapOn", {})));
    const screen = (stepIndex: number, ms: number, strings: string[], repeatOfStepIndex?: number) =>
      ({ kind: "screen", stepIndex, captureId: `shot-${stepIndex}.webp`, timestamp: iso(t0 + ms), repeatOfStepIndex, strings: strings.map((text) => ({ text, source: "text" })) });
    writeFileSync(join(dir, "visible-strings.ndjson"), ndjson([{ kind: "run", session: "r" }, screen(0, 1000, ["Home"]), screen(1, 2000, ["Settings"]), screen(2, 3000, [], 0)]));
    const last = SurveySession.load(dir).screenText.all().filter((t) => t.stepIndex === 2);
    expect(last.map((t) => [t.text, t.captureId, t.timeMs, t.line])).toEqual([["Home", "shot-2.webp", t0 + 3000, 4]]);
  });

  test("the strings each capture log recorded are the screen text, one capture per screenshot, with box and visibility", () => {
    const dir = join(root, "strings-on-logs");
    mkdirSync(dir, { recursive: true });
    const t0 = 1_700_000_400_000;
    const capture = (ms: number, screenshotFile: string, strings: unknown[]) => ({
      class: "xyz.block.trailblaze.logs.client.TrailblazeLog.AgentDriverLog", timestamp: iso(t0 + ms), screenshotFile, visibleStrings: strings,
    });
    writeFileSync(join(dir, "001_AgentDriverLog.json"), JSON.stringify(capture(1000, "https://files.example/run/a.webp?sig=x", [
      { text: "Pay", source: "text", bounds: [24, 180, 320, 224], visible: true },
      { text: "Terms", source: "text", visible: false },
    ])));
    // The LLM request made on the same screen names the same image: not a second capture.
    writeFileSync(join(dir, "002_TrailblazeLlmRequestLog.json"), JSON.stringify({ ...capture(1500, "a.webp", [{ text: "Pay", source: "text" }]), class: "xyz.block.trailblaze.logs.client.TrailblazeLog.TrailblazeLlmRequestLog" }));
    writeFileSync(join(dir, "003_AgentDriverLog.json"), JSON.stringify(capture(2000, "b.webp", [{ text: "Paid", source: "text" }])));
    // An export left beside the logs is not read twice.
    writeFileSync(join(dir, "visible-strings.ndjson"), ndjson([{ kind: "screen", captureId: "x.webp", strings: [{ text: "From the file", source: "text" }] }]));
    const rows = SurveySession.load(dir).screenText.all();
    expect(rows.map((t) => [t.text, t.captureId, t.stepIndex, t.visible, t.file])).toEqual([
      ["Pay", "a.webp", 0, true, "001_AgentDriverLog.json"],
      ["Terms", "a.webp", 0, false, "001_AgentDriverLog.json"],
      ["Paid", "b.webp", 1, true, "003_AgentDriverLog.json"],
    ]);
    expect(rows[0].bounds).toEqual([24, 180, 320, 224]);
    expect(rows[0].timeMs).toBe(t0 + 1000);
    const session = SurveySession.load(dir);
    expect(session.screenText.filter({ visible: false }).map((t) => t.text)).toEqual(["Terms"]);
    expect(session.screenText.filter({ captureId: "b.webp" }).map((t) => t.text)).toEqual(["Paid"]);
  });

  test("a capture with no screenshot is screen text named by the id its log was stamped with", () => {
    const dir = join(root, "strings-no-screenshot");
    mkdirSync(dir, { recursive: true });
    const log = (ms: number, extra: Record<string, unknown>) => ({
      class: "xyz.block.trailblaze.logs.client.TrailblazeLog.AgentDriverLog", timestamp: iso(1_700_000_500_000 + ms),
      visibleStrings: [{ text: `At ${ms}`, source: "text" }], ...extra,
    });
    writeFileSync(join(dir, "001_AgentDriverLog.json"), JSON.stringify(log(1000, { captureId: "capture-1-0000abcd" })));
    writeFileSync(join(dir, "002_AgentDriverLog.json"), JSON.stringify(log(2000, { screenshotFile: "b.webp" })));
    // Recorded before ids existed: nothing names it, so it is not read.
    writeFileSync(join(dir, "003_AgentDriverLog.json"), JSON.stringify(log(3000, {})));
    const rows = SurveySession.load(dir).screenText.all();
    expect(rows.map((t) => [t.text, t.captureId, t.screenshot ?? null, t.stepIndex])).toEqual([
      ["At 1000", "capture-1-0000abcd", null, 0],
      ["At 2000", "b.webp", "b.webp", 1],
    ]);
  });

  test("a version 1 export's boxes, stored as position and size, read as corners like every other source", () => {
    const dir = join(root, "v1-bounds");
    mkdirSync(dir, { recursive: true });
    writeFileSync(join(dir, "001_TrailblazeToolLog.json"), JSON.stringify(toolLog(1_700_000_450_000, "tapOn", {})));
    writeFileSync(join(dir, "visible-strings.ndjson"), ndjson([
      { v: 1, kind: "run", session: "r" },
      { v: 1, kind: "screen", stepIndex: 0, captureId: "a.png", strings: [{ text: "Pay", source: "text", bounds: [24, 180, 296, 44] }] },
      { v: 1, kind: "screen", stepIndex: 1, captureId: "b.png", repeatOfStepIndex: 0, strings: [] },
      { v: 2, kind: "screen", captureId: "c.png", strings: [{ text: "Paid", source: "text", bounds: [24, 180, 320, 224] }] },
    ]));
    expect(SurveySession.load(dir).screenText.all().map((t) => [t.captureId, t.bounds])).toEqual([
      ["a.png", [24, 180, 320, 224]],
      ["b.png", [24, 180, 320, 224]],
      ["c.png", [24, 180, 320, 224]],
    ]);
  });

  test("a version 2 export's repeat, which names the capture it repeats, still shows that screen", () => {
    const dir = join(root, "repeated-screen-v2");
    mkdirSync(dir, { recursive: true });
    const t0 = 1_700_000_500_000;
    writeFileSync(join(dir, "001_TrailblazeToolLog.json"), JSON.stringify(toolLog(t0, "tapOn", {})));
    const screen = (id: string, ms: number, strings: string[], repeatOf?: string) =>
      ({ v: 2, kind: "screen", captureId: id, timestamp: iso(t0 + ms), repeatOf, strings: strings.map((text) => ({ text, source: "text", visible: true })) });
    writeFileSync(join(dir, "visible-strings.ndjson"), ndjson([{ v: 2, kind: "run" }, screen("a.png", 1000, ["Home"]), screen("b.png", 2000, ["Settings"]), screen("c.png", 3000, [], "a.png")]));
    const last = SurveySession.load(dir).screenText.all().filter((t) => t.captureId === "c.png");
    expect(last.map((t) => [t.text, t.stepIndex, t.timeMs])).toEqual([["Home", 2, t0 + 3000]]);
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
  // Discovery names a survey by its export; a survey built in a test gets its name the same way.
  const paid = withId(
    survey({ description: "A payment completed" }, (session) => {
      const req = session.network.find({ method: "POST", path: "/v1/orders/checkout", status: "ok" });
      const evt = session.analytics.find({ name: "Payment Complete" });
      if (!req || !evt) return;
      return { summary: `Paid ${String(evt.properties.amount)}`, evidence: [req, evt], data: { paymentId: evt.properties.payment_id } };
    }),
    "paid",
  );
  const crashed = withId(survey({ finding: { summary: "Crashed", events: { stream: "crash" } } }), "crashed");
  const cleanRun = withId(survey({ description: "Ran clean", finding: { tools: { successful: true }, absent: { events: { stream: "crash" } } } }), "cleanRun");
  const iosOnly = withId(survey({ description: "Ran on iOS", platforms: ["ios"], finding: { tools: {} } }), "iosOnly");
  const broken = withId(
    survey({}, () => {
      throw new Error("survey bug");
    }),
    "broken",
  );
  const surveysFound = (r: { findings: Array<{ survey: string }> }) => r.findings.map((f) => f.survey).sort();

  test("findings carry their survey, evidence with file locations, and the data the survey gave them", async () => {
    const report = await runSurveys({ surveys: [paid, crashed, cleanRun, iosOnly, broken], sessions: [hashed, sequential] });
    const ci = report.sessions.find((s) => s.session.id === "ci-session-42")!;
    const payment = ci.findings.find((f) => f.survey === "paid")!;
    expect(payment).toEqual({ survey: "paid", session: "ci-session-42", summary: "Paid 500", evidence: expect.any(Array), data: { paymentId: "p-1" } });
    expect(payment.evidence.map((e) => `${e.kind}:${e.file}:${e.line}`)).toEqual(["network:network.ndjson:1", "analytics:events/app.analytics.ndjson:1"]);
  });

  test("rules require every query to match and honour absent queries and platform filters", async () => {
    const report = await runSurveys({ surveys: [crashed, cleanRun, iosOnly], sessions: [hashed, sequential] });
    const byId = Object.fromEntries(report.sessions.map((s) => [s.session.id, s]));
    expect(surveysFound(byId["ci-session-42"]!)).toEqual(["crashed"]);
    expect(surveysFound(byId["seq"]!)).toEqual(["cleanRun", "iosOnly"]);
    expect(byId["ci-session-42"]!.skipped).toContain("iosOnly");
  });

  test("one matching absent query vetoes the finding even when its other absent queries miss", async () => {
    const strict = withId(survey({ description: "Strict", finding: { tools: {}, absent: { events: { stream: "crash" }, analytics: { name: "never-logged" } } } }), "strict");
    const report = await runSurveys({ surveys: [strict], sessions: [hashed, sequential] });
    const byId = Object.fromEntries(report.sessions.map((s) => [s.session.id, s]));
    expect(byId["ci-session-42"]!.findings).toEqual([]);
    expect(surveysFound(byId["seq"]!)).toEqual(["strict"]);
  });

  test("a throwing survey is reported per session and does not stop the others", async () => {
    const report = await runSurveys({ surveys: [broken, crashed], sessions: [hashed] });
    const only = report.sessions[0]!;
    expect(only.errors).toEqual([{ survey: "broken", message: expect.stringContaining("survey bug") }]);
    expect(surveysFound(only)).toEqual(["crashed"]);
  });

  test("ctx.error reports a failure as the session's error and keeps what the handler still found", async () => {
    const partial = withId(
      survey({}, (session, ctx) => {
        ctx.error?.("odd Message");
        return { summary: "The other check still ran", evidence: [session.stream("crash")[0]!] };
      }),
      "partial",
    );
    const only = (await runSurveys({ surveys: [partial], sessions: [hashed] })).sessions[0]!;
    expect(only.errors).toEqual([{ survey: "partial", message: "odd Message" }]);
    expect(only.findings.map((f) => f.summary)).toEqual(["The other check still ran"]);
  });

  test("a finding with no evidence is rejected, so nothing reaches the report unproven", async () => {
    const unproven = withId(survey({}, () => ({ summary: "The run looked fine to me", evidence: [] })), "unproven");
    const report = await runSurveys({ surveys: [unproven, crashed], sessions: [hashed] });
    const only = report.sessions[0]!;
    expect(only.errors).toEqual([expect.objectContaining({ survey: "unproven", message: expect.stringContaining("no evidence") })]);
    expect(surveysFound(only)).toEqual(["crashed"]);
  });

  test("a survey's row counts each session once even when it reports several findings there", async () => {
    const twice = withId(survey({ description: "Each tap" }, (session) => session.tools.filter({ name: "tapOn" }).map((t) => ({ summary: t.summary, evidence: [t] }))), "taps");
    const report = await runSurveys({ surveys: [twice], sessions: [hashed, sequential] });
    expect(report.surveys).toEqual([{ id: "taps", description: "Each tap", sessionsMatched: 1, sessionIds: ["ci-session-42"], findings: 2 }]);
    expect(renderMarkdown(report)).toContain("| taps | 1/2 | 2 | Each tap |");
  });

  test("a markdown cell escapes a backslash before it escapes a pipe, so neither can break the table", async () => {
    // A summary that already carries an escaped pipe: `a\|b`. Escaping the pipe alone would
    // produce `a\\|b`, which markdown reads as a literal backslash followed by a column break.
    const tricky = withId(survey({}, (session) => session.tools.filter({ name: "tapOn" }).slice(0, 1)
      .map((t) => ({ summary: "a\\|b", evidence: [t] }))), "tricky");
    const report = await runSurveys({ surveys: [tricky], sessions: [hashed] });
    expect(renderMarkdown(report)).toContain("— a\\\\\\|b");
  });

  test("a finding's data is whatever its survey chose, carried to the report as given", async () => {
    interface Journey { journey: { variant: string; tags: string[] } }
    const journey = withId(
      survey<Journey>({ description: "Payment completed and the app logged it" }, (session) => {
        const evt = session.analytics.find({ name: "Payment Complete" });
        if (!evt) return;
        return { summary: "Payment logged", evidence: [evt], data: { journey: { variant: "card", tags: ["fast"] } } };
      }),
      "paymentJourney",
    );
    const report = await runSurveys({ surveys: [journey], sessions: [hashed, sequential] });
    const [finding, ...rest] = report.sessions.flatMap((s) => s.findings);
    expect(rest).toEqual([]);
    expect(finding).toEqual({ survey: "paymentJourney", session: "ci-session-42", summary: "Payment logged", evidence: [expect.objectContaining({ kind: "analytics" })], data: { journey: { variant: "card", tags: ["fast"] } } });
    const md = renderMarkdown(report);
    expect(md).toContain("| paymentJourney | 1/2 | 1 | Payment completed and the app logged it |");
    expect(md).toContain('- **paymentJourney** — Payment logged `{"journey":{"variant":"card","tags":["fast"]}}`');
  });

  test("a finding's data reads in the report exactly as its JSON, backslashes, pipes and backticks included", async () => {
    const odd = withId(survey({}, (session) => session.tools.filter({ name: "tapOn" }).slice(0, 1)
      .map((t) => ({ summary: "Odd data", evidence: [t], data: { path: "C:\\logs", pick: "a|b", tick: "`x`" } }))), "odd");
    const report = await runSurveys({ surveys: [odd], sessions: [hashed] });
    const json = JSON.stringify({ path: "C:\\logs", pick: "a|b", tick: "`x`" });
    expect(renderMarkdown(report)).toContain(`— Odd data \`\`${json}\`\``);
  });

  test("ctx.found records a finding with its data, the same as returning it", async () => {
    const found = withId(
      survey<{ tool: string }>({}, (session, ctx) => {
        for (const t of session.tools.filter({ name: "tapOn" }).slice(0, 1)) ctx.found("Tapped", [t], { tool: t.name });
      }),
      "tapped",
    );
    const report = await runSurveys({ surveys: [found], sessions: [hashed] });
    expect(report.sessions[0]!.findings.map((f) => [f.survey, f.summary, f.data])).toEqual([["tapped", "Tapped", { tool: "tapOn" }]]);
  });

  test("a declarative survey takes its summary from its description, carries its finding's data, and needs a query", async () => {
    const crash = withId(survey({ description: "The app crashed", finding: { events: { stream: "crash" }, data: { severity: "fatal" } } }), "appCrashed");
    const report = await runSurveys({ surveys: [crash], sessions: [hashed] });
    expect(report.sessions[0]!.findings.map((f) => [f.survey, f.summary, f.data])).toEqual([["appCrashed", "The app crashed", { severity: "fatal" }]]);
    // An empty query list is not a query: unrejected, it would match every session with no evidence.
    expect(() => survey({ description: "Hollow", finding: { network: [] } })).toThrow(/no queries/);
    expect(() => survey({ description: "Nothing" })).toThrow(/needs a handler or a finding/);
    expect(matchFinding(SurveySession.load(hashed), { network: [], analytics: { name: "No Such Event" } })).toBeUndefined();
  });

  test("a survey written before findings were free-form still reports its feature, kind and confidence, in data", async () => {
    // Its spec defaulted `kind`, and its findings set `feature` and `confidence` beside `summary`.
    const declared = withId(survey({ feature: "app.crash", kind: "feature", finding: { events: { stream: "crash" }, kind: "signal", confidence: "high" } } as never), "crashFinding");
    const legacy = withId(
      survey({ kind: "gap" } as never, (session) =>
        session.tools.filter({ name: "tapOn" }).slice(0, 2).map((t, i) => ({ summary: t.summary, evidence: [t], feature: "tool.tapOn", confidence: "low", ...(i === 1 ? { data: { kind: "own" } } : {}) }) as never),
      ),
      "legacyTaps",
    );
    const report = await runSurveys({ surveys: [legacy, declared], sessions: [hashed] });
    expect(report.sessions[0]!.findings.map((f) => [f.summary === "Matched the survey's finding", f.data])).toEqual([
      [false, { feature: "tool.tapOn", kind: "gap", confidence: "low" }],
      [false, { feature: "tool.tapOn", kind: "own", confidence: "low" }],
      [true, { feature: "app.crash", kind: "signal", confidence: "high" }],
    ]);
    expect(report.sessions[0]!.findings[0]).not.toHaveProperty("feature");
  });

  test("matchFinding evaluates one declarative finding against a loaded session", () => {
    const session = SurveySession.load(hashed);
    expect(matchFinding(session, { events: { stream: "crash" } })?.map((e) => e.kind)).toEqual(["event"]);
    expect(matchFinding(session, { tools: {}, absent: { events: { stream: "crash" } } })).toBeUndefined();
  });

  test("a survey still written with the old `footprint` key reports the same finding as `finding`", async () => {
    const old = withId(survey({ description: "The app crashed", footprint: { events: { stream: "crash" }, data: { severity: "fatal" } } }), "oldSpelling");
    const report = await runSurveys({ surveys: [old], sessions: [hashed] });
    expect(report.sessions[0]!.findings.map((f) => [f.survey, f.summary, f.data, f.evidence.map((e) => e.kind)])).toEqual([
      ["oldSpelling", "The app crashed", { severity: "fatal" }, ["event"]],
    ]);
    expect(matchFootprint(SurveySession.load(hashed), { events: { stream: "crash" } })?.map((e) => e.kind)).toEqual(["event"]);
    expect(() => survey({ description: "Both", finding: { tools: {} }, footprint: { tools: {} } })).toThrow(/both `finding` and `footprint`/);
    expect(() => survey({ description: "Both", finding: { tools: {} }, footprint: { tools: {} } }, () => undefined)).toThrow(/both `finding` and `footprint`/);
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
    const acmeOnly = withId(survey({ description: "Any acme session", targets: ["acme"], finding: { tools: {} } }), "acmeAny");
    // The fixture's trail named `sampleapp`, so without a catalog `acme` is a different target.
    const bare = await runSurveys({ surveys: [acmeOnly], sessions: [hashed] });
    expect(bare.sessions[0]!.skipped).toEqual(["acmeAny"]);
    // With a catalog saying acme owns the app the session drove, it belongs to acme too.
    const catalog = { acme: { id: "acme", appIds: ["com.example.sample"], platforms: { android: ["com.example.sample"] } } };
    const withCatalog = await runSurveys({ surveys: [acmeOnly], sessions: [hashed], targets: catalog });
    expect(withCatalog.sessions[0]!.findings.map((f) => f.survey)).toEqual(["acmeAny"]);
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
      `import { trailblaze } from ${JSON.stringify(join(import.meta.dir, "index.ts"))};\nexport const helper = 1;\nexport const alpha = trailblaze.survey({ description: "a", finding: { tools: {} } });\n`,
    );
    writeFileSync(join(dir, "ignored.ts"), `export const beta = 2;\n`);
    const loaded = await loadSurveys([dir]);
    expect(loaded.map((c) => c.definition.id)).toEqual(["alpha"]);
  });

  test("a survey file named twice, directly and through its directory, loads once", async () => {
    const dir = join(root, "once");
    mkdirSync(dir, { recursive: true });
    const file = join(dir, "solo.survey.ts");
    writeFileSync(file, `import { trailblaze } from ${JSON.stringify(join(import.meta.dir, "index.ts"))};\nexport const solo = trailblaze.survey({ description: "a", finding: { tools: {} } });\n`);
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

  test("a zip replaced at the same path is unpacked again, not served from the old copy", async () => {
    // A download cache replaces a zip in place, and a reused extract directory still holds what the
    // old one unpacked to. Reading that would put the old archive's session under the new one.
    const dir = join(root, "zips", "replaced");
    const zipAt = join(dir, "run.zip");
    const extractDir = join(root, "zips", "replaced-extracted");
    writeSequentialSession(join(dir, "old-session"));
    expect(spawnSync("zip", ["-qr", "run.zip", "old-session"], { cwd: dir }).status).toBe(0);
    expect((await discoverSessions([zipAt], { extractDir })).map((p) => basename(p))).toEqual(["old-session"]);
    rmSync(zipAt);
    writeSequentialSession(join(dir, "new-session"));
    expect(spawnSync("zip", ["-qr", "run.zip", "new-session"], { cwd: dir }).status).toBe(0);
    utimesSync(zipAt, new Date(), new Date(Date.now() + 60_000));
    expect((await discoverSessions([zipAt], { extractDir })).map((p) => basename(p))).toEqual(["new-session"]);
  });

  test("an extract directory inside an input is read only through the zips it unpacks", async () => {
    // Zips unpack early, so walking the extract directory as part of the input could read a
    // session a failing zip only half wrote, or one an earlier run left behind for a zip now gone.
    const input = join(root, "zips", "with-extract-inside");
    const extractDir = join(input, "a-extracted");
    writeSequentialSession(join(input, "src", "live-session"));
    expect(spawnSync("zip", ["-qr", join(input, "b-run.zip"), "live-session"], { cwd: join(input, "src") }).status).toBe(0);
    rmSync(join(input, "src"), { recursive: true });
    writeSequentialSession(join(extractDir, "gone-zip-abc", "leftover-session"));
    const found = await discoverSessions([input], { extractDir });
    expect(found.map((p) => basename(p))).toEqual(["live-session"]);
  });

  test("a link into the extract directory is not a way into it", async () => {
    // Skipping the extract directory by name alone left every link into it open: a link to a
    // leftover from an earlier run handed that session back as though it were part of the input.
    const input = join(root, "zips", "with-link-inside");
    const extractDir = join(input, "a-extracted");
    writeSequentialSession(join(input, "live-session"));
    writeSequentialSession(join(extractDir, "gone-zip-abc", "leftover-session"));
    symlinkSync(join(extractDir, "gone-zip-abc"), join(input, "b-alias"));
    const found = await discoverSessions([input], { extractDir });
    expect(found.map((p) => basename(p))).toEqual(["live-session"]);
  });

  test("an extract directory that does not exist yet is skipped once the first unzip creates it", async () => {
    // Created by the first zip, partway through the input that contains it. Deciding the skip when
    // the input started left it off, and the walk then read the unzipped archive unconfined,
    // following a link the archive carried out to sessions that were never in it.
    const input = join(root, "zips", "extract-created-late");
    const elsewhere = join(root, "zips", "extract-created-late-elsewhere");
    writeSequentialSession(elsewhere);
    writeSequentialSession(join(input, "src", "box", "from-the-zip"));
    symlinkSync(elsewhere, join(input, "src", "box", "borrowed"));
    expect(spawnSync("zip", ["-qry", join(input, "a.zip"), "from-the-zip", "borrowed"], { cwd: join(input, "src", "box") }).status).toBe(0);
    rmSync(join(input, "src"), { recursive: true });
    mkdirSync(join(input, "z"));
    const found = await discoverSessions([input], { extractDir: join(input, "z", "out") });
    expect(found.map((p) => basename(p))).toEqual(["from-the-zip"]);
  });

  test("a zip that links into the extract directory is never unpacked", async () => {
    // The walk skips it, so an unzip started for it early would never be awaited, and discovery
    // would return with it still writing.
    const input = join(root, "zips", "zip-link-inside");
    const extractDir = join(input, "a-extracted");
    const bin = join(root, "zips", "zip-link-inside-bin");
    const log = join(root, "zips", "zip-link-inside.log");
    mkdirSync(extractDir, { recursive: true });
    mkdirSync(bin, { recursive: true });
    writeFileSync(join(extractDir, "stale.zip"), "");
    symlinkSync(join(extractDir, "stale.zip"), join(input, "b-link.zip"));
    writeFileSync(log, "");
    writeFileSync(join(bin, "unzip"), `#!/bin/sh\necho "$3" >> "${log}"\n`, { mode: 0o755 });
    const path = process.env.PATH;
    process.env.PATH = `${bin}:${path}`;
    try {
      expect(await discoverSessions([input], { extractDir })).toEqual([]);
      expect(readFileSync(log, "utf8")).toBe("");
    } finally {
      process.env.PATH = path;
    }
  });

  test("an extract directory the caller names, or a folder inside it, is still walked", async () => {
    // Only an extract directory met BELOW an input is skipped. Naming it, or one of its folders,
    // is asking for what is there.
    const extractDir = join(root, "zips", "named-extract");
    writeSequentialSession(join(extractDir, "here-session"));
    writeSequentialSession(join(extractDir, "nested", "deeper-session"));
    expect((await discoverSessions([extractDir], { extractDir })).map((p) => basename(p))).toEqual(["here-session", "deeper-session"]);
    expect((await discoverSessions([join(extractDir, "nested")], { extractDir })).map((p) => basename(p))).toEqual(["deeper-session"]);
  });

  test("a failed discovery leaves no unzip running or waiting to start", async () => {
    // Zips unpack ahead of the walk, a few at a time. When the walk fails, the ones still queued
    // must not start, and the ones running must finish, before the failure reaches the caller.
    // A stub `unzip` makes that observable: 00.zip finishes at once and fails the walk, while every
    // other zip is still running or queued behind it.
    const input = join(root, "zips", "failing-walk");
    const bin = join(root, "zips", "failing-walk-bin");
    const log = join(root, "zips", "failing-walk.log");
    mkdirSync(input, { recursive: true });
    mkdirSync(bin, { recursive: true });
    for (let i = 0; i < 40; i++) writeFileSync(join(input, `${String(i).padStart(2, "0")}.zip`), "");
    writeFileSync(log, "");
    writeFileSync(
      join(bin, "unzip"),
      `#!/bin/sh\necho start >> "${log}"\ncase "$3" in */00.zip) ;; *) sleep 1 ;; esac\necho done >> "${log}"\n`,
      { mode: 0o755 },
    );
    const lines = (word: string) => readFileSync(log, "utf8").split("\n").filter((l) => l === word).length;
    const path = process.env.PATH;
    process.env.PATH = `${bin}:${path}`;
    try {
      const failed = await discoverSessions([input], {
        extractDir: join(root, "zips", "failing-walk-extracted"),
        onExtract: () => {
          throw new Error("stop here");
        },
      }).catch((e: Error) => e.message);
      expect(failed).toBe("stop here");
      // Everything that started had finished when the failure arrived...
      const started = lines("start");
      expect(lines("done")).toBe(started);
      // ...and nothing queued starts afterwards.
      await new Promise((r) => setTimeout(r, 1_500));
      expect(lines("start")).toBe(started);
      expect(started).toBeLessThan(40);
    } finally {
      process.env.PATH = path;
    }
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

  test("a trailmap's surveys/ directory loads beside the ones passed directly", async () => {
    // A target's surveys ship inside its trailmap, the way its tools do, so a repo that owns a
    // target owns its surveys too, and naming the trailmaps is enough to run them.
    const sdk = JSON.stringify(join(import.meta.dir, "index.ts"));
    const maps = join(root, "survey-trailmaps");
    mkdirSync(join(maps, "acme", "surveys", "nested"), { recursive: true });
    writeFileSync(join(maps, "acme", "trailmap.yaml"), "id: acme\n");
    writeFileSync(join(maps, "acme", "surveys", "nested", "checkout.survey.ts"), `import { trailblaze } from ${sdk};\nexport const acmeCheckout = trailblaze.survey({ description: "checkout", targets: ["acme"], finding: { tools: {} } });\n`);
    // Only surveys/ counts: a survey file elsewhere in the trailmap is not picked up.
    mkdirSync(join(maps, "acme", "tools"), { recursive: true });
    writeFileSync(join(maps, "acme", "tools", "stray.survey.ts"), `import { trailblaze } from ${sdk};\nexport const stray = trailblaze.survey({ description: "x", finding: { tools: {} } });\n`);
    // A trailmap with no surveys/ contributes nothing and says nothing.
    mkdirSync(join(maps, "plain"), { recursive: true });
    writeFileSync(join(maps, "plain", "trailmap.yaml"), "id: plain\n");
    const direct = join(root, "survey-direct");
    mkdirSync(direct, { recursive: true });
    writeFileSync(join(direct, "generic.survey.ts"), `import { trailblaze } from ${sdk};\nexport const generic = trailblaze.survey({ description: "g", finding: { tools: {} } });\n`);

    const said: string[] = [];
    const loaded = await loadSurveys([direct], (m) => said.push(m), { trailmaps: [maps] });
    expect(loaded.map((c) => c.definition.id).sort()).toEqual(["acmeCheckout", "generic"]);
    expect(said).toEqual([]);
    // Without trailmaps, nothing changes for existing callers.
    expect((await loadSurveys([direct])).map((c) => c.definition.id)).toEqual(["generic"]);
  });

  test("a survey in a trailmap and one passed directly cannot share a name", async () => {
    const src = `import { trailblaze } from ${JSON.stringify(join(import.meta.dir, "index.ts"))};\nexport const clash = trailblaze.survey({ description: "a", finding: { tools: {} } });\n`;
    const maps = join(root, "clash-trailmaps");
    mkdirSync(join(maps, "acme", "surveys"), { recursive: true });
    writeFileSync(join(maps, "acme", "trailmap.yaml"), "id: acme\n");
    writeFileSync(join(maps, "acme", "surveys", "a.survey.ts"), src);
    const direct = join(root, "clash-direct");
    mkdirSync(direct, { recursive: true });
    writeFileSync(join(direct, "b.survey.ts"), src);
    await expect(loadSurveys([direct], undefined, { trailmaps: [maps] })).rejects.toThrow(/exported by both/);
  });

  test("a staged trailmap's surveys get the loader's SDK, whatever their own tsconfig points at", async () => {
    // A trailmap's home repo maps the SDK to its own materialized copy (`.trailblaze/sdk/dist/`),
    // which does not exist where the trailmap is staged. The loader's copy must win, for both
    // public subpaths, and a helper the survey imports from a sibling trailmap must see it too.
    const maps = join(root, "staged-trailmaps");
    mkdirSync(join(maps, "acme", "surveys"), { recursive: true });
    mkdirSync(join(maps, "kiosk", "surveys"), { recursive: true });
    writeFileSync(join(maps, "acme", "trailmap.yaml"), "id: acme\n");
    writeFileSync(join(maps, "kiosk", "trailmap.yaml"), "id: kiosk\n");
    const tsconfig = JSON.stringify({ compilerOptions: { paths: { "@trailblaze/scripting/*": ["../../../no-such-sdk/dist/*"] } } });
    writeFileSync(join(maps, "acme", "surveys", "tsconfig.json"), tsconfig);
    writeFileSync(join(maps, "kiosk", "surveys", "tsconfig.json"), tsconfig);
    writeFileSync(
      join(maps, "acme", "surveys", "shared.ts"),
      `import { matchesPattern } from "@trailblaze/scripting/matcher";\nexport const literal = (s: string) => matchesPattern("$5.00", s, "native");\n`,
    );
    writeFileSync(
      join(maps, "kiosk", "surveys", "kiosk.survey.ts"),
      `import { trailblaze, isSurvey } from "@trailblaze/scripting/surveys";\nimport { literal } from "../../acme/surveys/shared";\nexport const kioskPrice = trailblaze.survey({ description: literal("$5.00") && isSurvey ? "price" : "none", finding: { tools: {} } });\n`,
    );

    const loaded = await loadSurveys([], undefined, { trailmaps: [maps] });
    expect(loaded.map((c) => [c.definition.id, c.definition.spec.description])).toEqual([["kioskPrice", "price"]]);
  });

  test("a workspace is found from anywhere inside it, and carries its own and its trailmaps' surveys", async () => {
    // Running surveys should need no paths: the repo a survey lives in is the workspace it runs in.
    const sdk = JSON.stringify(join(import.meta.dir, "index.ts"));
    const ws = join(root, "workspace");
    const config = join(ws, "trailblaze-config");
    mkdirSync(join(config, "surveys"), { recursive: true });
    mkdirSync(join(config, "trailmaps", "acme", "surveys"), { recursive: true });
    mkdirSync(join(ws, "deep", "er"), { recursive: true });
    writeFileSync(join(config, "trailblaze.yaml"), "trailmaps: []\n");
    writeFileSync(join(config, "surveys", "any.survey.ts"), `import { trailblaze } from ${sdk};\nexport const anyTarget = trailblaze.survey({ description: "any", finding: { tools: {} } });\n`);
    writeFileSync(join(config, "trailmaps", "acme", "trailmap.yaml"), "id: acme\ntarget:\n  platforms:\n    android:\n      app_ids: [com.example.acme]\n");
    // The public specifier, with no tsconfig `paths` or installed package: a trailmap from another
    // repo has neither, so the loader must serve the SDK itself.
    writeFileSync(join(config, "trailmaps", "acme", "surveys", "pay.survey.ts"), `import { trailblaze } from "@trailblaze/scripting/surveys";\nexport const acmePay = trailblaze.survey({ description: "pay", targets: ["acme"], finding: { tools: {} } });\n`);

    const saved = process.env.TRAILBLAZE_CONFIG_DIR;
    delete process.env.TRAILBLAZE_CONFIG_DIR;
    try {
      expect(findWorkspaceConfigDir(join(ws, "deep", "er"))).toBe(config);
      const workspace = await loadWorkspace(config);
      expect(workspace.surveys.map((s) => s.definition.id).sort()).toEqual(["acmePay", "anyTarget"]);
      expect(Object.keys(workspace.targets)).toEqual(["acme"]);

      // The legacy layout is a workspace too.
      const legacy = join(root, "legacy-workspace");
      mkdirSync(join(legacy, "trails", "config"), { recursive: true });
      writeFileSync(join(legacy, "trails", "config", "trailblaze.yaml"), "trailmaps: []\n");
      expect(findWorkspaceConfigDir(join(legacy, "trails"))).toBe(join(legacy, "trails", "config"));

      process.env.TRAILBLAZE_CONFIG_DIR = join(legacy, "trails", "config");
      expect(findWorkspaceConfigDir(join(ws, "deep"))).toBe(join(legacy, "trails", "config"));

      // An override that is not a directory is ignored, as the CLI ignores it.
      process.env.TRAILBLAZE_CONFIG_DIR = join(ws, "no-such-dir");
      expect(findWorkspaceConfigDir(join(ws, "deep"))).toBe(config);
      delete process.env.TRAILBLAZE_CONFIG_DIR;

      // A workspace with no trailmaps/ loads its shared surveys and says nothing about it.
      const said: string[] = [];
      const bare = await loadWorkspace(join(legacy, "trails", "config"), (m) => said.push(m));
      expect(bare.surveys).toEqual([]);
      expect(said).toEqual([]);
    } finally {
      if (saved === undefined) delete process.env.TRAILBLAZE_CONFIG_DIR;
      else process.env.TRAILBLAZE_CONFIG_DIR = saved;
    }
  });

  test("two files exporting the same survey name is an error", async () => {
    const dir = join(root, "dupes");
    mkdirSync(dir, { recursive: true });
    const src = `import { trailblaze } from ${JSON.stringify(join(import.meta.dir, "index.ts"))};\nexport const same = trailblaze.survey({ description: "a", finding: { tools: {} } });\n`;
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
    const times = SurveySession.load(dir).trailblazeLogs.all().map((l) => l.timeMs ?? 0);
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
