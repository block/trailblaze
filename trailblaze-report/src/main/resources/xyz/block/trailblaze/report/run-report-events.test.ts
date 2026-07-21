// Behavior tests for the session-events pipeline (run-report-events.ts): raw NDJSON lines in,
// embeddable EventStream out. These pin the two observable contracts formatter authors and the
// viewer depend on: (1) a stream with a matching formatter embeds clamped FormattedRow data built
// from FULL payloads, and (2) a stream without one — or whose formatter fails — keeps the generic
// event shape. Either way, every line and every payload is embedded in full (no last-N window,
// no preview truncation) — size is managed downstream by gzip + lazy rendering, not by dropping data.
//
// Run: `bun test run-report-events.test.ts` from this directory.
import { describe, expect, test } from "bun:test";

const ev = require("./run-report-events.ts") as {
  MAX_VALUE_CHARS: number;
  parseStreamFileName: (file: string) => string | null;
  decodeEventLine: (line: string) => FormatterEntry | null;
  resolveFormatterModule: (mod: unknown) => EventStreamFormatter | null;
  formatterForStream: (formatters: EventStreamFormatter[], name: string) => EventStreamFormatter | null;
  formatRows: (formatter: EventStreamFormatter, entries: FormatterEntry[]) => FormattedRow[] | null;
  buildEventStream: (file: string, lines: string[], formatters?: EventStreamFormatter[]) => EventStream | null;
};

// A request/response-pairing formatter, the shape a real network formatter takes: it sees the whole
// stream, so it can join a response to its request and emit ONE row with status + duration.
const pairingFormatter: EventStreamFormatter = {
  id: "sample-network",
  streams: ["com.example.plugin.network"],
  format(entries) {
    const rows: FormatterRowInput[] = [];
    const byId = new Map<string, { row: FormatterRowInput; t: number | null }>();
    for (const e of entries) {
      const d = e.data || {};
      if (d.request) {
        const row: FormatterRowInput = {
          t: e.t,
          label: `${d.request.method} ${d.request.path}`,
          sections: [{ title: "Request Headers", kv: Object.entries(d.request.headers || {}).map(([k, v]) => ({ k, v: String(v) })) }],
          raw: [d],
        };
        rows.push(row);
        byId.set(d.request.id, { row, t: e.t });
      } else if (d.response) {
        const m = byId.get(d.response.requestId);
        if (!m) continue;
        const code = d.response.statusCode;
        m.row.badges = [{ text: String(code), tone: code >= 400 ? "error" : "ok" }];
        if (code >= 400) m.row.tone = "error";
        if (m.t != null && e.t != null) m.row.badges.push({ text: `${e.t - m.t}ms` });
        m.row.sections!.push({ title: "Response Body", json: d.response.body });
        m.row.raw!.push(d);
      }
    }
    return rows;
  },
};

const reqLine = (id: string, t: number, path: string) =>
  JSON.stringify({ timeMs: t, data: { request: { id, method: "POST", path, headers: { Accept: "application/json" } } } });
const respLine = (requestId: string, t: number, statusCode: number) =>
  JSON.stringify({ timeMs: t, data: { response: { requestId, statusCode, body: { ok: statusCode < 400 } } } });

// Cross-language behavioral contract: the same fixture drives the Kotlin side
// (SessionEventsParityFixturesTest, exercising SessionEvents.parseFileName + SessionEventsReader),
// so a semantic drift in either implementation fails that side's suite. To change either rule,
// update both implementations AND the fixture in the same change.
describe("cross-language parity fixtures", () => {
  const fixtures = require("./session-events-parity-fixtures.json") as {
    fileNames: Array<{ file: string; name: string | null }>;
    lines: Array<{ line: string; t: number | null; payload: unknown | null }>;
  };

  test("file-name parsing agrees with the shared fixtures", () => {
    expect(fixtures.fileNames.length).toBeGreaterThan(0);
    for (const c of fixtures.fileNames) expect(ev.parseStreamFileName(c.file)).toBe(c.name);
  });

  test("line decoding agrees with the shared fixtures", () => {
    expect(fixtures.lines.length).toBeGreaterThan(0);
    for (const c of fixtures.lines) {
      const e = ev.decodeEventLine(c.line);
      if (c.payload === null) {
        expect(e).toBeNull();
        continue;
      }
      expect(e!.t).toBe(c.t);
      expect(e!.data).toEqual(c.payload);
    }
  });
});

describe("parseStreamFileName", () => {
  test("everything before .ndjson is the stream name, dots included", () => {
    expect(ev.parseStreamFileName("com.example.plugin.network.ndjson")).toBe("com.example.plugin.network");
    expect(ev.parseStreamFileName("analytics.ndjson")).toBe("analytics");
  });

  test("strips the legacy .json style segment so old sessions keep their stream names", () => {
    expect(ev.parseStreamFileName("com.example.plugin.network.json.ndjson")).toBe("com.example.plugin.network");
  });

  test("rejects non-events files", () => {
    expect(ev.parseStreamFileName("notes.txt")).toBeNull();
    expect(ev.parseStreamFileName(".ndjson")).toBeNull();
    expect(ev.parseStreamFileName(".json.ndjson")).toBeNull();
  });
});

describe("decodeEventLine", () => {
  test("unwraps the { timeMs, data } envelope", () => {
    const e = ev.decodeEventLine('{"timeMs":123,"data":{"a":1}}');
    expect(e).toEqual({ t: 123, data: { a: 1 } });
  });

  test("keeps a bare rich payload whole, ordered by its own timestampMs", () => {
    const e = ev.decodeEventLine('{"timestampMs":456,"phase":"REQUEST_START"}');
    expect(e!.t).toBe(456);
    expect(e!.data).toEqual({ timestampMs: 456, phase: "REQUEST_START" });
  });

  test("a top-level data field without timeMs is NOT an envelope (JVM reader parity)", () => {
    const e = ev.decodeEventLine('{"timestampMs":9,"data":{"inner":true},"other":1}');
    expect(e!.data).toHaveProperty("other", 1);
  });

  test("malformed and non-object lines decode to null", () => {
    expect(ev.decodeEventLine("not json")).toBeNull();
    expect(ev.decodeEventLine('"just a string"')).toBeNull();
  });
});

describe("formatterForStream", () => {
  const f = (id: string, streams: string[]): EventStreamFormatter => ({ id, streams, format: () => [] });

  test("matches exact stream names", () => {
    expect(ev.formatterForStream([f("a", ["com.example.plugin.network"])], "com.example.plugin.network")?.id).toBe("a");
    expect(ev.formatterForStream([f("a", ["com.example.plugin.network"])], "com.example.plugin.analytics")).toBeNull();
  });

  test("matches prefix.* wildcards without crossing the dot boundary", () => {
    const w = f("w", ["com.example.plugin.*"]);
    expect(ev.formatterForStream([w], "com.example.plugin.analytics")?.id).toBe("w");
    expect(ev.formatterForStream([w], "com.example.pluginx")).toBeNull();
  });

  test("first matching formatter wins", () => {
    const exact = f("exact", ["com.example.plugin.network"]);
    const wild = f("wild", ["com.example.plugin.*"]);
    expect(ev.formatterForStream([exact, wild], "com.example.plugin.network")?.id).toBe("exact");
  });
});

describe("resolveFormatterModule", () => {
  test("accepts a plain module and a default-export wrapper", () => {
    expect(ev.resolveFormatterModule(pairingFormatter)?.id).toBe("sample-network");
    expect(ev.resolveFormatterModule({ default: pairingFormatter })?.id).toBe("sample-network");
  });

  test("rejects modules missing the contract", () => {
    expect(ev.resolveFormatterModule(null)).toBeNull();
    expect(ev.resolveFormatterModule({ id: "x", streams: [], format: () => [] })).toBeNull();
    expect(ev.resolveFormatterModule({ id: "x", streams: ["s"] })).toBeNull();
  });
});

describe("buildEventStream with a formatter", () => {
  test("pairs raw request/response lines into one row with status, duration, and sections", () => {
    const stream = ev.buildEventStream(
      "com.example.plugin.network.ndjson",
      [reqLine("r1", 1000, "/2.0/pay"), respLine("r1", 1142, 200)],
      [pairingFormatter],
    )!;
    expect(stream.name).toBe("com.example.plugin.network");
    expect(stream.rows).toHaveLength(1);
    const row = stream.rows![0];
    expect(row.label).toBe("POST /2.0/pay");
    expect(row.t).toBe(1000);
    expect(row.badges).toEqual([{ text: "200", tone: "ok" }, { text: "142ms" }]);
    // Structured `json` sections are serialized to text at embed time; kv sections stay tables.
    expect(row.sections![0]).toEqual({ title: "Request Headers", kv: [{ k: "Accept", v: "application/json" }] });
    expect(row.sections![1].text).toContain('"ok": true');
    // Raw payloads (request + response) ride along for the Raw JSON expando.
    expect(row.raw).toHaveLength(2);
    expect(stream.total).toBe(1);
    expect(stream.truncated).toBe(false);
    expect(stream.events).toEqual([]);
  });

  test("failed responses carry the error tone", () => {
    const stream = ev.buildEventStream(
      "com.example.plugin.network.ndjson",
      [reqLine("r1", 1000, "/2.0/pay"), respLine("r1", 1400, 503)],
      [pairingFormatter],
    )!;
    expect(stream.rows![0].tone).toBe("error");
    expect(stream.rows![0].badges![0]).toEqual({ text: "503", tone: "error" });
  });

  test("UI-chrome parts are clamped; content parts survive far beyond the old preview budget", () => {
    const noisy: EventStreamFormatter = {
      id: "noisy",
      streams: ["s"],
      format: () => [{
        label: "x".repeat(5000),
        badges: [{ text: "y".repeat(500) }],
        sections: [{ title: "Body", text: "z".repeat(100_000) }],
        raw: [{ big: "w".repeat(100_000) }],
      }],
    };
    const row = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [noisy])!.rows![0];
    expect(row.label.length).toBeLessThanOrEqual(301);
    expect(row.badges![0].text.length).toBeLessThanOrEqual(41);
    expect(row.sections![0].text!.length).toBe(100_000);
    expect(row.raw![0].length).toBeGreaterThan(100_000);
  });

  test("a section with blank text still renders its json instead of being dropped", () => {
    const blankText: EventStreamFormatter = {
      id: "blank",
      streams: ["s"],
      format: () => [{
        label: "row",
        sections: [
          { title: "Body", text: "", json: { a: 1 } },
          { title: "Empty", text: "   " },
        ],
      }],
    };
    const row = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [blankText])!.rows![0];
    expect(row.sections).toHaveLength(1);
    expect(row.sections![0].title).toBe("Body");
    expect(row.sections![0].text).toContain('"a": 1');
  });

  test("rows without a valid label are dropped; an all-invalid result falls back to generic", () => {
    const invalid: EventStreamFormatter = { id: "bad", streams: ["s"], format: (es) => es.map(() => ({ label: "" })) };
    const stream = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{"a":1}}'], [invalid])!;
    expect(stream.rows).toBeUndefined();
    expect(stream.events).toHaveLength(1);
  });

  test("a throwing formatter falls back to the generic shape instead of losing the stream", () => {
    const throwing: EventStreamFormatter = { id: "boom", streams: ["s"], format: () => { throw new Error("boom"); } };
    const stream = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{"a":1}}'], [throwing])!;
    expect(stream.rows).toBeUndefined();
    expect(stream.events).toEqual([{ t: 1, d: '{"a":1}' }]);
  });

  test("the formatter sees EVERY line, and every produced row is kept", () => {
    let seen = 0;
    const perLine: EventStreamFormatter = {
      id: "count",
      streams: ["s"],
      format: (entries) => { seen = entries.length; return entries.map((e, i) => ({ t: e.t, label: `row ${i}` })); },
    };
    const lines = Array.from({ length: 250 }, (_, i) => `{"timeMs":${i},"data":{"i":${i}}}`);
    const stream = ev.buildEventStream("s.ndjson", lines, [perLine])!;
    expect(seen).toBe(250);
    expect(stream.rows).toHaveLength(250);
    expect(stream.truncated).toBe(false);
  });
});

describe("buildEventStream without a formatter (generic shape)", () => {
  test("keeps every event with a true total and no truncation", () => {
    const lines = Array.from({ length: 120 }, (_, i) => `{"timeMs":${i},"data":{"i":${i}}}`);
    const stream = ev.buildEventStream("com.example.plugin.analytics.ndjson", lines, [])!;
    expect(stream.events).toHaveLength(120);
    expect(stream.total).toBe(120);
    expect(stream.truncated).toBe(false);
    expect(stream.events[0]).toEqual({ t: 0, d: '{"i":0}' });
    expect(stream.rows).toBeUndefined();
  });

  test("large payloads are embedded whole (old 2000-char preview budget is gone)", () => {
    const stream = ev.buildEventStream("s.ndjson", [`{"timeMs":1,"data":{"big":"${"x".repeat(5000)}"}}`], [])!;
    expect(stream.events[0].d.length).toBeGreaterThan(5000);
    expect(stream.events[0].d.endsWith("…")).toBe(false);
  });

  test("blank and malformed lines are skipped; a non-events file is rejected", () => {
    const stream = ev.buildEventStream("s.ndjson", ["", "not json", '{"timeMs":2,"data":{"ok":1}}'], [])!;
    expect(stream.events).toEqual([{ t: 2, d: '{"ok":1}' }]);
    expect(ev.buildEventStream("notes.txt", ['{"timeMs":1}'], [])).toBeNull();
  });
});
