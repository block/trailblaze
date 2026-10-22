import { describe, expect, test } from "bun:test";
import { LiveSessionReader, runLiveSurvey, type LiveEventSource } from "./live.js";
import { nameSource } from "./live-plan.js";
import { survey, withId, type SurveyDefinition } from "./survey.js";

/**
 * `ctx.events` over in-memory streams, filtering on `match` and counting as the host does: a
 * field is a subset match, `{ $regex }` tests a value's text or an object's JSON. It records each
 * call and how many records crossed back, so a test can see what reached the survey. Without
 * `count` it is a host that can't filter, and every stream is read whole.
 */
function hostEvents(initial: Record<string, unknown[]>, opts: { count?: boolean; incomplete?: Record<string, string> } = {}) {
  const streams = new Map<string, Array<{ timeMs: number; data: unknown }>>();
  let clock = 1_000;
  for (const [stream, rows] of Object.entries(initial)) streams.set(stream, rows.map((data) => ({ timeMs: clock++, data })));
  const calls: Array<{ stream: string; count?: boolean; match?: Record<string, unknown>; after?: number; returned: number }> = [];
  const scan = (stream: string, options: { match?: Record<string, unknown>; after?: number }) => {
    const rows = streams.get(stream);
    const reason = opts.incomplete?.[stream];
    const health = { complete: reason === undefined, ...(reason ? { incompleteReason: reason } : {}) };
    const from = options.after ?? 0;
    const range = (rows ?? []).slice(from);
    return { rows, health, from, range, kept: range.filter((r) => options.match === undefined || matches(options.match, r.data)) };
  };
  const source: LiveEventSource = {
    async list() {
      return [...streams.keys()].sort();
    },
    async query(stream, options = {}) {
      const { rows, health, from, range, kept } = scan(stream, options);
      const page = kept.slice(0, options.limit ?? 100);
      // Oldest-first stops after the last record it keeps.
      const cursor = page.length === (options.limit ?? 100) ? from + range.indexOf(page[page.length - 1]!) + 1 : from + range.length;
      calls.push({ stream, ...(options.match ? { match: options.match } : {}), ...(options.after !== undefined ? { after: options.after } : {}), returned: page.length });
      if (!rows) return { captured: false, ...health, events: [], cursor: 0 };
      return { captured: true, ...health, events: page, cursor, scan: { lines: range.length } };
    },
  };
  if (opts.count !== false) {
    source.count = async (stream, options = {}) => {
      const { rows, health, from, range, kept } = scan(stream, options);
      calls.push({ stream, count: true, ...(options.match ? { match: options.match } : {}), ...(options.after !== undefined ? { after: options.after } : {}), returned: 0 });
      if (!rows) return { captured: false, ...health, count: 0, cursor: 0, scan: { lines: 0 } };
      return { captured: true, ...health, count: kept.length, cursor: from + range.length, scan: { lines: range.length } };
    };
  }
  /** Records that crossed into the survey: what every query returned. */
  const crossed = () => calls.reduce((n, c) => n + c.returned, 0);
  return { source, calls, crossed, now: () => clock };
}

function matches(match: Record<string, unknown>, value: unknown): boolean {
  if (value === null || typeof value !== "object" || Array.isArray(value)) return false;
  return Object.entries(match).every(([key, expected]) => {
    const actual = (value as Record<string, unknown>)[key];
    if (actual === undefined) return expected === null;
    if (expected !== null && typeof expected === "object" && "$regex" in expected) {
      const { $regex, $flags } = expected as { $regex: string; $flags?: string };
      return new RegExp($regex, $flags?.includes("i") ? "i" : "").test(typeof actual === "string" ? actual : JSON.stringify(actual));
    }
    if (expected !== null && typeof expected === "object") return matches(expected as Record<string, unknown>, actual);
    return actual === expected;
  });
}

/** An analytics row as a table capture writes it: its event name is a column. */
const tableRow = (event: string, properties: Record<string, unknown> = {}) => ({
  id: event,
  columnItems: { Time: "12:00", Source: "app", Event: event, Properties: JSON.stringify(properties) },
});

const noise = (n: number) => Array.from({ length: n }, (_, i) => tableRow(i % 2 === 0 ? "flag_read" : "screen_view", { i }));

/** [def] run over [rows] by a host that filters and one that can't: the outcome must be the same. */
async function bothWays(def: SurveyDefinition, rows: Record<string, unknown[]>, opts: { incomplete?: Record<string, string> } = {}) {
  const filtered = hostEvents(rows, opts);
  const whole = hostEvents(rows, { ...opts, count: false });
  const named = withId(def, "probe");
  const a = await runLiveSurvey(named, new LiveSessionReader(filtered.source), { now: filtered.now });
  const b = await runLiveSurvey(named, new LiveSessionReader(whole.source), { now: whole.now });
  expect({ passed: a.passed, findings: a.findings, message: a.message }).toEqual({ passed: b.passed, findings: b.findings, message: b.message });
  return { result: a, filtered, whole };
}

describe("a mid-trail finding reads only what it matches", () => {
  test("an analytics name reaches the survey as only its matching records", async () => {
    const addedItem = survey({ description: "shop.item-added", finding: { analytics: { name: "item_added" } } });
    const rows = { analytics: [...noise(600), tableRow("item_added", { item: "bagel" }), ...noise(400)], network: [], logging: noise(400) };

    const { result, filtered, whole } = await bothWays(addedItem, rows);

    expect(result.passed).toBe(true);
    expect(filtered.crossed()).toBe(1);
    expect(whole.crossed()).toBe(1_401);
    // The name went to the host as a match; the streams it doesn't read were only counted.
    const fetched = filtered.calls.filter((c) => !c.count);
    expect(fetched).toEqual([{ stream: "analytics", match: { columnItems: { $regex: '"item_added"' } }, returned: 1 }]);
    expect(filtered.calls.filter((c) => c.count && c.stream !== "analytics").map((c) => c.stream)).toEqual(["logging", "network"]);
  });

  test("a later read fetches only what matched since the last one", async () => {
    const addedItem = withId(survey({ description: "shop.item-added", finding: { analytics: { name: "item_added" } } }), "probe");
    const host = hostEvents({ analytics: noise(50) });
    const reader = new LiveSessionReader(host.source);
    expect((await runLiveSurvey(addedItem, reader, { now: host.now })).passed).toBe(false);
    const afterFirst = host.calls.length;
    await runLiveSurvey(addedItem, reader, { now: host.now });
    expect(host.calls.slice(afterFirst).filter((c) => c.stream === "analytics" && !c.count)).toEqual([
      { stream: "analytics", match: { columnItems: { $regex: '"item_added"' } }, after: 50, returned: 0 },
    ]);
  });

  test("an absence settles on a count of zero, and no record crosses", async () => {
    const noDecline = survey({
      description: "payments.no-decline",
      finding: { analytics: { name: "checkout_done", stream: "analytics" }, absent: { analytics: { name: "card_declined", stream: "payments-analytics" } } },
    });
    const { result, filtered } = await bothWays(noDecline, { analytics: [tableRow("checkout_done")], "payments-analytics": noise(300), network: [] });
    expect(result.passed).toBe(true);
    expect(filtered.calls.filter((c) => c.stream === "payments-analytics" && !c.count)).toEqual([]);
    expect(filtered.crossed()).toBe(1);
  });

  test("an absence that matches is fetched and judged, and rules the finding out", async () => {
    const noDecline = survey({
      description: "payments.no-decline",
      finding: { analytics: { name: "checkout_done", stream: "analytics" }, absent: { analytics: { name: "card_declined", stream: "payments-analytics" } } },
    });
    const { result } = await bothWays(noDecline, { analytics: [tableRow("checkout_done")], "payments-analytics": [...noise(10), tableRow("card_declined")], network: [] });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("ruled out by absent analytics");
  });

  test("a flat analytics row, whose name the host can't reach, makes the stream read whole", async () => {
    const addedItem = survey({ description: "shop.item-added", finding: { analytics: { name: "item_added" } } });
    const { result, filtered } = await bothWays(addedItem, { analytics: [...noise(20), { name: "item_added" }] });
    expect(result.passed).toBe(true);
    expect(filtered.calls.some((c) => c.stream === "analytics" && !c.count && c.match === undefined)).toBe(true);
  });

  test("network and event queries read their streams whole, and judge the same", async () => {
    const paid = survey({ description: "payments.paid", finding: { network: { path: "/v2/payments", status: 200 }, events: { stream: "logging", contains: "paid" } } });
    const request = [
      { id: "1", phase: "REQUEST_START", method: "POST", url: "https://api.example.com/v2/payments", urlPath: "/v2/payments", timestampMs: 1 },
      { id: "1", phase: "RESPONSE_END", statusCode: 200, timestampMs: 2 },
    ];
    const { result, filtered } = await bothWays(paid, { network: request, logging: [{ msg: "paid" }], analytics: noise(30) });
    expect(result.passed).toBe(true);
    expect(filtered.calls.filter((c) => !c.count).map((c) => c.stream)).toEqual(["logging", "network"]);
  });

  test("a loss on a stream only counted still fails an absence", async () => {
    const noDecline = survey({ description: "payments.no-decline", finding: { analytics: { name: "checkout_done" }, absent: { network: { path: "/v2/refunds" } } } });
    const { result } = await bothWays(noDecline, { analytics: [tableRow("checkout_done")], network: [], logging: [] }, { incomplete: { analytics: "3 records dropped" } });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("analytics: 3 records dropped");
  });

  test("names that need escaping, case-insensitive and pattern names judge the same as a whole read", async () => {
    const names = ['say "hi"', "a\\b", "price $5.00 (x2)", "naïve café", "Tap Item"];
    const rows = { analytics: [...noise(5), ...names.map((n) => tableRow(n)), tableRow("TAP ITEM")] };
    for (const name of [...names, /tap item/i, /Tap Item/, /Tap.Item/, /tap item/iu, (n: string) => n.startsWith("price")]) {
      const def = survey({ description: "probe", finding: { analytics: { name }, evidence: "all" } });
      const { result } = await bothWays(def, rows);
      expect(result.passed).toBe(true);
    }
  });
});

describe("which names the host can filter on", () => {
  test("a string becomes its JSON, escaped for a regex", () => {
    expect(nameSource('say "hi" $5')?.source).toBe('"say \\\\"hi\\\\" \\$5"');
    expect(nameSource("tab\there")).toBeUndefined();
  });

  test("only a plain ASCII literal pattern is passed on, and never one that folds case beyond ASCII", () => {
    expect(nameSource(/Tap Item/)).toEqual({ source: "Tap Item", ignoreCase: false });
    expect(nameSource(/tap item/i)).toEqual({ source: "tap item", ignoreCase: true });
    expect(nameSource(/kelvin/iu)).toBeUndefined();
    expect(nameSource(/Tap.Item/)).toBeUndefined();
    expect(nameSource(/café/)).toBeUndefined();
    expect(nameSource((n: string) => n === "x")).toBeUndefined();
  });
});

describe("a handler mid-trail holds a bounded number of records", () => {
  const counted = survey({ description: "shop.counted" }, (s) => {
    const all = s.analytics.all();
    return all.length > 0 ? { summary: `saw ${all.length}`, evidence: [all[0]!] } : undefined;
  });

  test("below the limit it reads everything, as before", async () => {
    const host = hostEvents({ analytics: noise(500), network: [] });
    const result = await runLiveSurvey(withId(counted, "probe"), new LiveSessionReader(host.source, { maxRecords: 500 }), { now: host.now });
    expect(result.passed).toBe(true);
    expect(result.findings[0]!.summary).toBe("saw 500");
  });

  test("past the limit it stops reading and fails, pointing at a finding", async () => {
    const host = hostEvents({ analytics: noise(501), network: [], logging: noise(10) });
    const result = await runLiveSurvey(withId(counted, "probe"), new LiveSessionReader(host.source, { maxRecords: 500 }), { now: host.now, waitMs: 60_000 });
    expect(result.passed).toBe(false);
    expect(result.waitedMs).toBe(0);
    expect(result.message).toContain("past the 500 records");
    expect(result.message).toContain("Write the check as a finding");
    // Nothing after the stream that crossed the limit is read.
    expect(host.calls.some((c) => c.stream === "logging" || c.stream === "network")).toBe(false);
  });

  test("the limit counts the characters held, too", async () => {
    const host = hostEvents({ analytics: [tableRow("big", { blob: "x".repeat(2_000) })] });
    const result = await runLiveSurvey(withId(counted, "probe"), new LiveSessionReader(host.source, { maxRecordChars: 1_000 }), { now: host.now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("1000 characters");
  });
});

describe("a finding mid-trail holds a bounded number of records", () => {
  const addedItem = withId(survey({ description: "shop.item-added", finding: { analytics: { name: "item_added" } } }), "probe");
  const fetches = (host: ReturnType<typeof hostEvents>) => host.calls.filter((c) => c.stream === "analytics" && !c.count);

  test("a name matching past the record limit stops fetching on the page that crosses it", async () => {
    // Four pages of matches; the second crosses the limit, so the last two are never fetched.
    const host = hostEvents({ analytics: Array.from({ length: 4_000 }, (_, i) => tableRow("item_added", { i })), network: [] });
    const result = await runLiveSurvey(addedItem, new LiveSessionReader(host.source, { maxRecords: 1_500 }), { now: host.now, waitMs: 60_000 });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("past the 1500 records");
    expect(result.message).toContain("Narrow the finding");
    expect(fetches(host).map((c) => c.returned)).toEqual([1_000, 1_000]);
    // Nothing after the stream that crossed the limit is read: no shape count, no other stream.
    expect(host.calls.some((c) => c.stream === "network" || (c.stream === "analytics" && c.count))).toBe(false);
  });

  test("a name matching past the character limit stops fetching on the page that crosses it", async () => {
    const rows = Array.from({ length: 3_000 }, (_, i) => tableRow("item_added", { i, blob: "x".repeat(500) }));
    const host = hostEvents({ analytics: rows, network: [] });
    const result = await runLiveSurvey(addedItem, new LiveSessionReader(host.source, { maxRecordChars: 1_000_000 }), { now: host.now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain(`${1_000_000} characters`);
    expect(fetches(host).map((c) => c.returned)).toEqual([1_000, 1_000]);
  });

  test("a finding read whole past the limit is told to move after the session, not to narrow its name", async () => {
    // A network finding can't be filtered on an analytics name, so narrowing one would not help.
    const paid = withId(survey({ description: "payments.paid", finding: { network: { path: "/v2/payments", status: 200 } } }), "probe");
    const host = hostEvents({ logging: noise(10), network: noise(600) });
    const result = await runLiveSurvey(paid, new LiveSessionReader(host.source, { maxRecords: 500 }), { now: host.now });
    expect(result.passed).toBe(false);
    expect(result.message).toContain("past the 500 records");
    expect(result.message).toContain("This finding reads its streams whole, so move it after the session");
    expect(result.message).not.toContain("Narrow the finding");
  });

  test("matches under both limits are held and judged, as before", async () => {
    const rows = [...noise(3_000), ...Array.from({ length: 1_200 }, (_, i) => tableRow("item_added", { i }))];
    const { result, filtered } = await bothWays(survey({ description: "shop.item-added", finding: { analytics: { name: "item_added" } } }), { analytics: rows });
    expect(result.passed).toBe(true);
    expect(filtered.crossed()).toBe(1_200);
  });
});
