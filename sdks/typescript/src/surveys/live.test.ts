import { describe, expect, test } from "bun:test";
import { FinishedSessionRequiredError, LiveSessionReader, runLiveSurvey, type LiveEventSource } from "./live.js";
import { survey, withId } from "./survey.js";

/**
 * `ctx.events` over in-memory streams. A cursor is the count of rows read. `onWait` runs when a
 * caller sleeps, standing in for records that arrive while the trail runs; the clock moves by the
 * time slept.
 */
function fakeEvents(
  initial: Record<string, unknown[]>,
  opts: {
    incomplete?: Record<string, string>;
    onWait?: (add: (stream: string, data: unknown, timeMs?: number) => void) => void;
    /** Runs before each query, for records that land between two streams' reads. */
    beforeQuery?: (stream: string, add: (stream: string, data: unknown) => void) => void;
    files?: Record<string, string>;
    timeFloorMs?: number;
  } = {},
) {
  const streams = new Map<string, Array<{ timeMs: number; data: unknown }>>();
  const marks = new Map<string, Map<string, number>>();
  const mark = (name: string) => marks.set(name, new Map([...streams].map(([stream, rows]) => [stream, rows.length])));
  let clock = 1_000;
  const add = (stream: string, data: unknown, timeMs?: number) => {
    const rows = streams.get(stream) ?? [];
    streams.set(stream, rows);
    rows.push({ timeMs: timeMs ?? clock++, data });
  };
  for (const [stream, rows] of Object.entries(initial)) {
    streams.set(stream, []);
    for (const r of rows) add(stream, r);
  }
  const queries: Array<{ stream: string; since?: string; after?: number; waitMs?: number; limit?: number }> = [];
  const source: LiveEventSource = {
    async list() {
      return [...streams.keys()].sort();
    },
    async query(stream, options = {}) {
      queries.push({ stream, ...options });
      const timeFloorMs = options.after === undefined && (options.since === "trail" || options.since === "trailhead") ? opts.timeFloorMs : undefined;
      const boundary = options.since === undefined || options.since === "session" ? undefined : marks.get(options.since);
      if (options.after === undefined && options.since !== undefined && options.since !== "session" && !boundary && timeFloorMs === undefined) throw new Error(`No mark named ${options.since}`);
      opts.beforeQuery?.(stream, add);
      if (options.waitMs) {
        opts.onWait?.(add);
        clock += options.waitMs;
      }
      const rows = streams.get(stream);
      const reason = opts.incomplete?.[stream];
      const health = { complete: reason === undefined, ...(reason ? { incompleteReason: reason } : {}), ...(timeFloorMs !== undefined ? { timeFloorMs } : {}) };
      if (!rows) return { captured: false, ...health, events: [], cursor: 0 };
      const from = options.after ?? boundary?.get(stream) ?? 0;
      const limit = options.limit ?? 100;
      const page = rows.map((row, index) => ({ row, index })).slice(from)
        .filter(({ row }) => timeFloorMs === undefined || row.timeMs >= timeFloorMs)
        .slice(0, limit);
      const cursor = page.length === limit ? page.at(-1)!.index + 1 : rows.length;
      const file = opts.files?.[stream];
      return { captured: true, ...health, events: page.map(({ row }) => row), cursor, ...(file ? { file } : {}) };
    },
  };
  return { source, queries, add, mark, now: () => clock };
}

const request = (id: string, path: string, status = 200) => [
  { id, phase: "REQUEST_START", method: "POST", url: `https://api.example.com${path}`, urlPath: path, timestampMs: 1 },
  { id, phase: "RESPONSE_END", statusCode: status, timestampMs: 2 },
];

const checkout = survey({
  description: "payments.checkout",
  finding: { network: { path: "/v2/payments", status: 200 }, analytics: { name: "checkout_done" } },
});

describe("runLiveSurvey mid-trail", () => {
  const operations = withId(survey({ description: "Shop operations" }, (session) =>
    session.analytics.filter({ name: "operation_done" }).map((event) => ({
      summary: "Operation completed", evidence: [event], data: event.properties,
    })),
  ), "operations");

  test("waits past nonmatching findings and returns only findings matching the expected structure", async () => {
    const { source, now } = fakeEvents({
      analytics: [{ name: "operation_done", properties: { variant: "bulk", count: 1 } }], network: [],
    }, { onWait: (add) => add("analytics", { name: "operation_done", properties: { variant: "individual", count: 1, extra: "allowed" } }) });
    const result = await runLiveSurvey(operations, new LiveSessionReader(source), {
      expected: { survey: "operations", data: { variant: "individual", count: 1 } }, waitMs: 2_000, now,
    });
    expect(result.passed).toBe(true);
    expect(result.waitedMs).toBeGreaterThan(0);
    expect(result.findings).toHaveLength(1);
    expect(result.findings[0]!.data).toEqual({ variant: "individual", count: 1, extra: "allowed" });
  });

  test("matches nested array items in any order, allowing extra items and keys", async () => {
    const { source, now } = fakeEvents({ network: [], analytics: [{ name: "operation_done", properties: {
      runs: [{ variant: "other" }, { variant: "individual", properties: { count: 1, enabled: true, note: null, extra: 2 } }],
    } }] });
    const result = await runLiveSurvey(operations, new LiveSessionReader(source), {
      expected: { data: { runs: [{ variant: "individual", properties: { count: 1, enabled: true, note: null } }] } }, now,
    });
    expect(result.passed).toBe(true);
  });

  test.each([
    { count: "1" }, { enabled: "true" }, { note: "null" }, { missing: null },
    { runs: [{ variant: "individual", count: 1 }] },
  ])("fails when a finding does not contain the expected typed fields: %j", async (expected) => {
    const { source, now } = fakeEvents({ network: [], analytics: [{ name: "operation_done", properties: {
      count: 1, enabled: true, note: null, runs: [{ variant: "individual" }, { count: 1 }],
    } }] });
    const result = await runLiveSurvey(operations, new LiveSessionReader(source), { expected: { data: expected }, now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("Expected a finding matching");
  });

  test("does not combine fields from separate findings", async () => {
    const { source, now } = fakeEvents({ network: [], analytics: [
      { name: "operation_done", properties: { variant: "individual" } },
      { name: "operation_done", properties: { count: 1 } },
    ] });
    expect((await runLiveSurvey(operations, new LiveSessionReader(source), {
      expected: { data: { variant: "individual", count: 1 } }, now,
    })).passed).toBe(false);
  });

  test("passes on a finding that is already captured, without waiting", async () => {
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments"), analytics: [{ name: "checkout_done" }] });
    const result = await runLiveSurvey(checkout, new LiveSessionReader(source), { waitMs: 5_000, now });
    expect(result.passed).toBe(true);
    expect(result.findings).toHaveLength(1);
    expect(result.findings[0]!.evidence.map((e) => e.kind)).toEqual(["network", "analytics"]);
    expect(result.waitedMs).toBe(0);
  });

  test("waits for a record that arrives later and passes once it does", async () => {
    let waits = 0;
    const { source, now } = fakeEvents(
      { network: request("1", "/v2/payments") },
      { onWait: (add) => ++waits === 2 && add("analytics", { name: "checkout_done" }) },
    );
    const result = await runLiveSurvey(checkout, new LiveSessionReader(source), { waitMs: 5_000, pollMs: 500, now });
    expect(result.passed).toBe(true);
    // Two half-second waits; the arriving record ticks the clock too.
    expect(result.waitedMs).toBeGreaterThanOrEqual(1_000);
    expect(result.waitedMs).toBeLessThan(1_500);
  });

  test("fails at the deadline, naming the queries that matched and the ones that did not", async () => {
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments") });
    const result = await runLiveSurvey(checkout, new LiveSessionReader(source), { waitMs: 1_200, pollMs: 500, now });
    expect(result.passed).toBe(false);
    expect(result.waitedMs).toBeGreaterThanOrEqual(1_200);
    expect(result.message).toContain("found nothing in");
    expect(result.message).toContain('Matched network {"path":"/v2/payments","status":200}');
    expect(result.message).toContain('missed analytics {"name":"checkout_done"}');
  });

  test("fails at once when the capture lost data, with the capture's reason", async () => {
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments") }, { incomplete: { network: "queue overflowed, 3 records dropped" } });
    const result = await runLiveSurvey(checkout, new LiveSessionReader(source), { waitMs: 60_000, now });
    expect(result.passed).toBe(false);
    expect(result.waitedMs).toBe(0);
    expect(result.message).toContain("network: queue overflowed, 3 records dropped");
  });

  test("a presence finding still passes when some other capture lost data", async () => {
    const { source, now } = fakeEvents(
      { network: request("1", "/v2/payments"), analytics: [{ name: "checkout_done" }] },
      { incomplete: { network: "queue overflowed" } },
    );
    expect((await runLiveSurvey(checkout, new LiveSessionReader(source), { now })).passed).toBe(true);
  });

  test("fixed finding expectations preserve presence-only checks on incomplete captures", async () => {
    const payment = withId(survey({ description: "Payment captured", finding: {
      network: { path: "/v2/payments", status: 200 }, summary: "Payment captured", data: { tender: "card" },
    } }), "payments");
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments") }, {
      incomplete: { network: "an unrelated record was dropped" },
    });
    const result = await runLiveSurvey(payment, new LiveSessionReader(source), {
      now, expected: { survey: "payments", summary: "Payment captured", data: { tender: "card" } },
    });
    expect(result.passed).toBe(true);
  });

  // The summary is mutable evidence: a response changes it from "in flight" to its status.
  const submittedPayment = survey({ description: "Payment submitted", finding: { network: { path: "/v2/payments" } } });
  const inFlightEvidence = { evidence: [{ summary: "POST /v2/payments → in flight" }] };

  test("an evidence expectation cannot infer an in-flight request from a dropped response", async () => {
    const { source, now } = fakeEvents({ network: [request("1", "/v2/payments")[0]!] }, {
      incomplete: { network: "response record dropped" },
    });
    const result = await runLiveSurvey(submittedPayment, new LiveSessionReader(source), { now, expected: inFlightEvidence });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("response record dropped");
  });

  test("an evidence expectation is reconsidered when a response arrives between stream reads", async () => {
    let responseDelivered = false;
    const { source, now } = fakeEvents({ network: [request("1", "/v2/payments")[0]!] }, {
      beforeQuery: (stream, add) => {
        if (stream === "analytics" && !responseDelivered) {
          responseDelivered = true;
          add("network", request("1", "/v2/payments")[1]!);
        }
      },
    });
    const result = await runLiveSurvey(submittedPayment, new LiveSessionReader(source), { now, expected: inFlightEvidence });
    expect(responseDelivered).toBe(true);
    expect(result.passed).toBe(false);
  });

  test("an in-flight evidence expectation can pass when capture is complete and no response arrived", async () => {
    const { source, now } = fakeEvents({ network: [request("1", "/v2/payments")[0]!] });
    expect((await runLiveSurvey(submittedPayment, new LiveSessionReader(source), { now, expected: inFlightEvidence })).passed).toBe(true);
  });

  test("absent means not seen so far: passes before the record arrives and fails after", async () => {
    const noDecline = survey({
      description: "payments.no-decline",
      finding: { network: { path: "/v2/payments" }, absent: { analytics: { name: "card_declined" } } },
    });
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments"), analytics: [{ name: "checkout_started" }] });
    const reader = new LiveSessionReader(source);
    expect((await runLiveSurvey(noDecline, reader, { now })).passed).toBe(true);

    const later = fakeEvents({ network: request("1", "/v2/payments"), analytics: [{ name: "card_declined" }] });
    const result = await runLiveSurvey(noDecline, new LiveSessionReader(later.source), { now: later.now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain('ruled out by absent analytics {"name":"card_declined"}');
  });

  test("an absence is not proved while a capture is losing data", async () => {
    const noDecline = survey({
      description: "payments.no-decline",
      finding: { network: { path: "/v2/payments" }, absent: { analytics: { name: "card_declined" } } },
    });
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments"), analytics: [] }, { incomplete: { analytics: "2 records dropped" } });
    const result = await runLiveSurvey(noDecline, new LiveSessionReader(source), { now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("analytics: 2 records dropped");
  });

  test("a handler an author wrote is not trusted while a capture is losing data", async () => {
    // It finds something only when no decline was seen, which a dropped record could hide.
    const noDecline = survey({ description: "payments.no-decline" }, (s) =>
      s.analytics.find({ name: "card_declined" }) ? undefined : { summary: "no decline", evidence: [s.network.all()[0]!] },
    );
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments"), analytics: [] }, { incomplete: { analytics: "2 records dropped" } });
    const result = await runLiveSurvey(noDecline, new LiveSessionReader(source), { now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("analytics: 2 records dropped");
    // It did find something; the failure says so, rather than sending a reader after a missing record.
    expect(result.message).toContain("found no decline, but the capture lost data");
    expect(result.message).not.toContain("found nothing");
  });

  test("a request still waiting for its response is in flight, not failed", async () => {
    const failedPayment = survey({ description: "payments.failed", finding: { network: { path: "/v2/payments", status: "failed" } } });
    const { source, now } = fakeEvents(
      { network: [request("1", "/v2/payments")[0]] },
      { onWait: (add) => add("network", { id: "1", phase: "FAILED", timestampMs: 3 }) },
    );
    const reader = new LiveSessionReader(source);
    const before = await reader.read();
    expect(before.session.network.all()[0]!.failed).toBe(false);
    expect(before.session.network.all()[0]!.summary).toContain("in flight");
    expect((await runLiveSurvey(failedPayment, reader, { now })).passed).toBe(false);
    expect((await runLiveSurvey(failedPayment, reader, { now, waitMs: 5_000 })).passed).toBe(true);
  });

  test("a survey names itself by its id, in a pass and in a failure", async () => {
    const named = withId(survey({ description: "Checkout reached the server", finding: { network: { path: "/v2/payments", status: 200 } } }), "checkoutReachedServer");
    const seen = fakeEvents({ network: request("1", "/v2/payments") });
    const passed = await runLiveSurvey(named, new LiveSessionReader(seen.source), { now: seen.now });
    expect(passed).toMatchObject({ passed: true, message: "Survey checkoutReachedServer found Checkout reached the server." });
    expect(passed.findings[0]).toMatchObject({ survey: "checkoutReachedServer", summary: "Checkout reached the server" });
    expect(passed.findings[0]).not.toHaveProperty("feature");
    const empty = fakeEvents({ network: request("1", "/v2/refunds") });
    const failed = await runLiveSurvey(named, new LiveSessionReader(empty.source), { now: empty.now });
    expect(failed.passed).toBe(false);
    expect(failed.message).toStartWith("Survey checkoutReachedServer found nothing");
    expect(failed.message).toContain('Missed network {"path":"/v2/payments","status":200}.');
  });

  test("refuses a survey that needs the finished session", async () => {
    const afterRun = survey({ description: "platform.failure", requiresFinishedSession: true, finding: { network: { path: "/x" } } });
    const { source } = fakeEvents({ network: request("1", "/x") });
    const result = await runLiveSurvey(afterRun, new LiveSessionReader(source));
    expect(result.passed).toBe(false);
    expect(result.message).toContain("requiresFinishedSession");
  });
});

describe("live session", () => {
  test("reading anything that exists only at the end throws, naming the flag", async () => {
    const { source } = fakeEvents({ network: request("1", "/x") });
    const { session } = await new LiveSessionReader(source).read();
    expect(session.final).toBe(false);
    for (const read of [() => session.summary.outcome, () => session.summary.durationMs, () => session.tools.find({ name: "tapOn" }), () => session.screenText.all(), () => session.readText("trace.json")]) {
      expect(read).toThrow(FinishedSessionRequiredError);
      expect(read).toThrow("requiresFinishedSession: true");
    }
  });

  test("a handler that reads the outcome fails the assertion with the same message", async () => {
    const outcome = survey({ description: "x" }, (s) => (s.summary.outcome === "SUCCEEDED" ? { summary: "ok", evidence: [] } : undefined));
    const { source } = fakeEvents({ network: request("1", "/x") });
    await expect(runLiveSurvey(outcome, new LiveSessionReader(source))).rejects.toThrow("summary.outcome only exists once the session has finished");
  });

  test("a loss on one page stays after later pages come through whole", async () => {
    const rows = Array.from({ length: 1_500 }, (_, i) => ({ name: `e${i}` }));
    const { source } = fakeEvents({ analytics: rows });
    // Only the first page reports the loss, as a capture does once it has dropped rows.
    const lossy: LiveEventSource = {
      list: () => source.list(),
      async query(stream, options = {}) {
        const page = await source.query(stream, options);
        return options.after === undefined ? { ...page, complete: false, incompleteReason: "queue overflowed" } : page;
      },
    };
    const reader = new LiveSessionReader(lossy);
    const first = await reader.read();
    expect(first.session.analytics.length).toBe(1_500);
    expect(first.complete).toBe(false);
    expect(first.incompleteReason).toContain("queue overflowed");
    expect((await reader.read()).complete).toBe(false);
  });

  test("reads urls without the URL global, as the scripted-tool sandbox must", async () => {
    const saved = globalThis.URL;
    // @ts-expect-error The sandbox has no URL.
    delete globalThis.URL;
    try {
      const { source } = fakeEvents({
        network: [
          { id: "1", phase: "REQUEST_START", method: "GET", url: "https://user:pw@API.Example.com:443/v2/orders?limit=5#top", timestampMs: 1 },
          { id: "1", phase: "RESPONSE_END", statusCode: 200, timestampMs: 2 },
          { id: "2", phase: "REQUEST_START", method: "GET", url: "http://localhost:8080", timestampMs: 3 },
        ],
      });
      const [orders, local] = (await new LiveSessionReader(source).read()).session.network.all();
      expect(orders).toMatchObject({ host: "api.example.com", path: "/v2/orders", query: "limit=5" });
      expect(local).toMatchObject({ host: "localhost:8080", path: "/" });
    } finally {
      globalThis.URL = saved;
    }
  });

  test("reads every page of a long stream, then only what arrived since", async () => {
    const rows = Array.from({ length: 2_500 }, (_, i) => ({ name: `e${i}` }));
    const { source, queries } = fakeEvents({ analytics: rows });
    const reader = new LiveSessionReader(source);
    expect((await reader.read()).session.analytics.length).toBe(2_500);
    expect(queries.map((q) => q.after)).toEqual([undefined, 1_000, 2_000]);
    queries.length = 0;
    expect((await reader.read()).session.analytics.length).toBe(2_500);
    expect(queries.map((q) => q.after)).toEqual([2_500]);
  });

  test("an inline body is read, and reading a body kept in a file throws naming the flag", async () => {
    const { source } = fakeEvents({
      network: [
        { id: "1", phase: "REQUEST_START", method: "GET", url: "https://api.example.com/a", timestampMs: 1, requestBodyRef: { blobPath: "bodies/1.req" } },
        { id: "1", phase: "RESPONSE_END", statusCode: 201, timestampMs: 2, responseBodyRef: { inlineText: '{"ok":true}' } },
      ],
    });
    const { session } = await new LiveSessionReader(source).read();
    const r = session.network.find({ path: "/a" })!;
    expect(r.status).toBe(201);
    expect(r.responseBody).toBe('{"ok":true}');
    expect(() => r.requestBody).toThrow(FinishedSessionRequiredError);
    expect(() => r.requestBody).toThrow(/request body of GET https:\/\/api.example.com\/a.*requiresFinishedSession: true/);
    // Neither a presence nor an absence query over that body is judged without it.
    const overBody = survey({ description: "body", finding: { network: { requestBody: "card" } } });
    await expect(runLiveSurvey(overBody, new LiveSessionReader(source))).rejects.toThrow(FinishedSessionRequiredError);
    // A query that never asks about the body still matches the request.
    expect(session.network.find({ path: "/a", status: 201 })).toBeDefined();
  });
});

describe("survey observation windows", () => {
  test.each(["trail", "trailhead"])("%s rejects a runtime that omits the timestamp cutoff", async (since) => {
    const { source } = fakeEvents({ analytics: [{ name: "checkout_done" }] }, { timeFloorMs: 1_000 });
    const olderRuntime: LiveEventSource = {
      ...source,
      async query(stream, options) {
        const { timeFloorMs, ...result } = await source.query(stream, options);
        return result;
      },
    };
    const done = survey({ description: "checkout.done", finding: { analytics: { name: "checkout_done" } } });
    await expect(runLiveSurvey(done, new LiveSessionReader(olderRuntime, { since }))).rejects.toThrow("timestamp cutoff");
  });

  test.each(["trail", "trailhead"])("%s excludes stale successes on later pages but accepts a new success", async (since) => {
    const { source, add, now } = fakeEvents({ analytics: [] }, { timeFloorMs: 1_000 });
    for (let i = 0; i < 1_000; i++) add("analytics", { name: "progress" });
    add("analytics", { name: "checkout_done" }, 999);
    const done = survey({ description: "checkout.done", finding: { analytics: { name: "checkout_done" } } });
    const attempt = () => new LiveSessionReader(source, { since });
    expect((await runLiveSurvey(done, attempt(), { now })).passed).toBe(false);
    add("analytics", { name: "checkout_done" });
    expect((await runLiveSurvey(done, attempt(), { now })).passed).toBe(true);
  });

  test.each(["trail", "trailhead"])("%s excludes a stale success captured during polling", async (since) => {
    const { source, now } = fakeEvents({ analytics: [] }, {
      timeFloorMs: 1_000,
      onWait: (add) => add("analytics", { name: "checkout_done" }, 999),
    });
    const done = survey({ description: "checkout.done", finding: { analytics: { name: "checkout_done" } } });
    expect((await runLiveSurvey(done, new LiveSessionReader(source, { since }), { now, waitMs: 1_000 })).passed).toBe(false);
  });

  test.each(["trail", "trailhead"])("%s excludes stale failures from an absence check during polling", async (since) => {
    const { source, now } = fakeEvents({ analytics: [] }, {
      timeFloorMs: 1_000,
      onWait: (add) => {
        add("analytics", { name: "checkout_done" });
        add("analytics", { name: "card_declined" }, 999);
      },
    });
    const noDecline = survey({ description: "checkout.no-decline", finding: {
      analytics: { name: "checkout_done" }, absent: { analytics: { name: "card_declined" } },
    } });
    expect((await runLiveSurvey(noDecline, new LiveSessionReader(source, { since }), { now, waitMs: 1_000 })).passed).toBe(true);
  });

  test("named marks still include a later arrival whose timestamp predates the mark", async () => {
    const { source, add, mark, now } = fakeEvents({ analytics: [] });
    mark("retry");
    add("analytics", { name: "checkout_done" }, 1);
    const done = survey({ description: "checkout.done", finding: { analytics: { name: "checkout_done" } } });
    expect((await runLiveSurvey(done, new LiveSessionReader(source, { since: "retry" }), { now })).passed).toBe(true);
  });

  test("an old result cannot pass a new attempt, and separate checks do not consume events", async () => {
    const { source, now, mark, add } = fakeEvents({ network: request("old", "/v2/payments"), analytics: [{ name: "checkout_done" }] });
    mark("second-checkout");
    const secondAttempt = () => new LiveSessionReader(source, { since: "second-checkout" });
    expect((await runLiveSurvey(checkout, secondAttempt(), { now })).passed).toBe(false);

    add("analytics", { name: "checkout_done" });
    expect((await runLiveSurvey(checkout, secondAttempt(), { now })).passed).toBe(false);
    for (const row of request("new", "/v2/payments")) add("network", row);
    expect((await runLiveSurvey(checkout, secondAttempt(), { now })).passed).toBe(true);
    expect((await runLiveSurvey(checkout, secondAttempt(), { now })).passed).toBe(true);
    const scoped = await secondAttempt().read();
    expect(scoped.session.analytics.count({ name: "checkout_done" })).toBe(1);
    expect(scoped.session.network.count({ path: "/v2/payments" })).toBe(1);

    mark("second-checkout");
    expect((await runLiveSurvey(checkout, secondAttempt(), { now })).passed).toBe(false);
    const wholeSession = await new LiveSessionReader(source).read();
    expect(wholeSession.session.analytics.count({ name: "checkout_done" })).toBe(2);
    expect(wholeSession.session.network.count({ path: "/v2/payments" })).toBe(2);
  });

  test("waiting includes a stream first created after the mark", async () => {
    const { source, now, mark, add } = fakeEvents(
      { network: request("old", "/v2/payments") },
      { onWait: (append) => append("analytics", { name: "checkout_done" }) },
    );
    mark("next-checkout");
    for (const row of request("new", "/v2/payments")) add("network", row);
    const result = await runLiveSurvey(checkout, new LiveSessionReader(source, { since: "next-checkout" }), { now, waitMs: 5_000 });
    expect(result.passed).toBe(true);
    expect(result.findings[0]!.evidence.map((e) => e.kind)).toEqual(["network", "analytics"]);
  });

  test("pagination and polling retain each new record once", async () => {
    const { source, now, mark, add } = fakeEvents(
      { analytics: [{ name: "old" }], network: [] },
      { onWait: (append) => append("analytics", { name: "last" }) },
    );
    mark("next-batch");
    for (let i = 0; i < 1_500; i++) add("analytics", { name: "new" });
    const batch = survey({ description: "batch.done" }, (session) => {
      const rows = session.analytics.all();
      return rows.length === 1_501 && rows.every((e) => e.name !== "old") && rows.at(-1)?.name === "last"
        ? { summary: "Every new event, once", evidence: rows }
        : undefined;
    });
    const result = await runLiveSurvey(batch, new LiveSessionReader(source, { since: "next-batch" }), { now, waitMs: 2_000 });
    expect(result.passed).toBe(true);
    expect(result.findings[0]!.evidence).toHaveLength(1_501);
  });

  test("absence queries ignore old records but still reject a new matching record", async () => {
    const { source, now, mark, add } = fakeEvents({ analytics: [{ name: "card_declined" }], network: [] });
    const noDecline = survey({ description: "checkout.no-decline", finding: {
      analytics: { name: "checkout_done" }, absent: { analytics: { name: "card_declined" } },
    } });
    mark("retry");
    add("analytics", { name: "checkout_done" });
    const retry = () => new LiveSessionReader(source, { since: "retry" });
    expect((await runLiveSurvey(noDecline, retry(), { now })).passed).toBe(true);
    add("analytics", { name: "card_declined" });
    expect((await runLiveSurvey(noDecline, retry(), { now })).passed).toBe(false);
  });

  test("an unknown mark fails even when the stream has not been captured", async () => {
    const { source } = fakeEvents({});
    await expect(new LiveSessionReader(source, { since: "missing" }).read()).rejects.toThrow("missing");
  });
});

describe("runLiveSurvey judges only what it can see", () => {
  test("a body error names the request without the credentials in its URL", async () => {
    const { source } = fakeEvents({
      network: [{ id: "1", phase: "REQUEST_START", method: "GET", url: "https://user:hunter2@api.example.com/a?token=s3cret", timestampMs: 1, requestBodyRef: { blobPath: "bodies/1.req" } }],
    });
    const { session } = await new LiveSessionReader(source).read();
    const r = session.network.all()[0]!;
    expect(() => r.requestBody).toThrow(/request body of GET https:\/\/\[redacted\]@api.example.com\/a\?token=\[redacted\]/);
    let message = "";
    try {
      void r.requestBody;
    } catch (e) {
      message = (e as Error).message;
    }
    expect(message).not.toContain("hunter2");
    expect(message).not.toContain("s3cret");
  });

  test("an absence over a stream that was never captured proves nothing", async () => {
    const noPayment = survey({
      description: "checkout.free",
      finding: { analytics: { name: "checkout_done" }, absent: { network: { path: "/v2/payments" } } },
    });
    // Network capture is off: the stream has no records, and that is not "no payment was made".
    const off = fakeEvents({ analytics: [{ name: "checkout_done" }] });
    const result = await runLiveSurvey(noPayment, new LiveSessionReader(off.source), { now: off.now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("network was never captured");
    const on = fakeEvents({ analytics: [{ name: "checkout_done" }], network: [] });
    expect((await runLiveSurvey(noPayment, new LiveSessionReader(on.source), { now: on.now })).passed).toBe(true);
  });

  test("a pass the second read takes back keeps waiting while there is time", async () => {
    // Passes once every checkout that started has finished.
    const allDone = survey({ description: "checkout.all-done" }, (s) => {
      const started = s.analytics.filter({ name: "checkout_started" });
      const done = s.analytics.filter({ name: "checkout_done" });
      return done.length > 0 && done.length === started.length ? { summary: "all done", evidence: done } : undefined;
    });
    let analyticsReads = 0;
    const { source, now } = fakeEvents(
      { analytics: [{ name: "checkout_started" }, { name: "checkout_done" }], network: [] },
      {
        // A second checkout starts just as the pass is checked again, and finishes while it waits.
        beforeQuery: (stream, add) => {
          if (stream === "analytics" && ++analyticsReads === 2) add("analytics", { name: "checkout_started" });
        },
        onWait: (add) => add("analytics", { name: "checkout_done" }),
      },
    );
    const result = await runLiveSurvey(allDone, new LiveSessionReader(source), { now, waitMs: 5_000 });
    expect(result.passed).toBe(true);
    expect(result.findings[0]!.evidence).toHaveLength(2);
  });

  test("a survey scoped to a target applies to a session on one of that target's apps", async () => {
    const shopOnly = survey({ description: "shop.pay", targets: ["shop"], finding: { network: { path: "/v2/payments" } } });
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments") });
    const reader = () => new LiveSessionReader(source, { summary: { target: "shop-lite", appId: "com.example.shop" } });
    expect((await runLiveSurvey(shopOnly, reader(), { now })).passed).toBe(false);
    const targets = { shop: { id: "shop", appIds: ["com.example.shop"], platforms: { android: ["com.example.shop"] } } };
    expect((await runLiveSurvey(shopOnly, reader(), { now, targets })).passed).toBe(true);
  });

  test("a request carries the host's time, not the device stamp, so it orders with other streams", async () => {
    // The fake host stamps rows from 1000; the request's own timestampMs (1, 2) is the device clock.
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments"), analytics: [{ name: "checkout_done" }] });
    const result = await runLiveSurvey(checkout, new LiveSessionReader(source), { now });
    expect(result.findings[0]!.evidence.map((e) => e.timeMs)).toEqual([1000, 1002]);
  });

  test("a finding with a predicate is judged only on a complete read", async () => {
    // The response was lost, so `status === undefined` would hold on a request that got one.
    const unanswered = survey({ description: "payments.unanswered", finding: { network: { path: "/v2/payments", where: (r) => r.status === undefined } } });
    const { source, now } = fakeEvents(
      { network: [request("1", "/v2/payments")[0]], analytics: [] },
      { incomplete: { network: "dropped 1 record" } },
    );
    const result = await runLiveSurvey(unanswered, new LiveSessionReader(source), { now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("dropped 1 record");
  });

  const noDecline = survey({
    description: "payments.no-decline",
    finding: { network: { path: "/v2/payments" }, absent: { analytics: { name: "card_declined" } } },
  });

  test("an absence fails when a stream lost its first write, so it has no file", async () => {
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments") }, { incomplete: { analytics: "analytics: 1 write lost" } });
    const result = await runLiveSurvey(noDecline, new LiveSessionReader(source), { now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("analytics: 1 write lost");
  });

  test("an absence is checked again, so a record landing between two streams' reads is not missed", async () => {
    let landed = false;
    const { source, now } = fakeEvents(
      { analytics: [{ name: "checkout_started" }], network: [] },
      {
        // The decline lands on analytics just after it was read, then the request completes.
        beforeQuery: (stream, add) => {
          if (stream !== "network" || landed) return;
          landed = true;
          add("analytics", { name: "card_declined" });
          for (const row of request("1", "/v2/payments")) add("network", row);
        },
      },
    );
    const result = await runLiveSurvey(noDecline, new LiveSessionReader(source), { now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("ruled out by absent");
  });

  test("an error the survey reports keeps it from passing", async () => {
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments") });
    const half = survey({ description: "payments.half" }, (s, ctx) => {
      ctx.error("the payments schema changed");
      const paid = s.network.find({ path: "/v2/payments" });
      return paid ? { summary: "paid", evidence: [paid] } : undefined;
    });
    const result = await runLiveSurvey(half, new LiveSessionReader(source), { now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("the payments schema changed");
  });

  test("a survey scoped to another platform does not apply", async () => {
    const { source, now } = fakeEvents({ network: request("1", "/v2/payments") });
    const iosOnly = survey({ description: "payments.ios", platforms: ["ios"], finding: { network: { path: "/v2/payments" } } });
    const android = await runLiveSurvey(iosOnly, new LiveSessionReader(source, { summary: { platform: "android" } }), { now });
    expect(android.passed).toBe(false);
    expect(android.message).toContain("is scoped to platforms ios, and this session is platform android");
    expect((await runLiveSurvey(iosOnly, new LiveSessionReader(source, { summary: { platform: "ios" } }), { now })).passed).toBe(true);
  });

  test("evidence names the file the host read, and no position in it", async () => {
    const { source, now } = fakeEvents(
      { analytics: [{ name: "checkout_done" }], network: request("1", "/v2/payments") },
      { files: { analytics: "events/analytics@pixel-7.ndjson" } },
    );
    const result = await runLiveSurvey(checkout, new LiveSessionReader(source, { id: "s1" }), { now });
    const analytics = result.findings[0]!.evidence.find((e) => e.kind === "analytics")!;
    expect(analytics.file).toBe("events/analytics@pixel-7.ndjson");
    expect(analytics.line).toBeUndefined();
    expect(result.findings[0]!.session).toBe("s1");
  });
});
