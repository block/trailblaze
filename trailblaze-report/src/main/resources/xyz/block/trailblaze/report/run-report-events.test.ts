// Behavior tests for the session-events pipeline (run-report-events.ts): raw NDJSON lines in,
// embeddable EventStream out. These pin the two observable contracts formatter authors and the
// viewer depend on: (1) a stream with a matching formatter embeds clamped FormattedRow data built
// from FULL payloads, and (2) a stream without one — or whose formatter fails — keeps the generic
// event shape. Either way, this pipeline embeds every line and every payload in full (no last-N
// window, no preview truncation) — size is managed downstream by gzip + lazy rendering, and by
// the formatters themselves, which receive the session outcome (FormatterContext) and may
// size-budget raw payloads of passed sessions.
//
// Run: `bun test run-report-events.test.ts` from this directory.
import { describe, expect, test } from "bun:test";

const ev = require("./run-report-events.ts") as {
  MAX_VALUE_CHARS: number;
  parseStreamFileName: (file: string) => string | null;
  rootNetworkStreamFiles: (rootFileNames: readonly string[], eventStreamNames: readonly string[]) => string[];
  decodeEventLine: (line: string) => FormatterEntry | null;
  resolveFormatterModule: (mod: unknown) => EventStreamFormatter | null;
  formatterForStream: (formatters: EventStreamFormatter[], name: string) => EventStreamFormatter | null;
  formatRows: (formatter: EventStreamFormatter, entries: FormatterEntry[], ctx?: FormatterContext) => FormattedRow[] | null;
  buildEventStream: (
    file: string,
    lines: string[],
    formatters?: EventStreamFormatter[],
    ctx?: FormatterContext,
    clockSync?: unknown,
  ) => EventStream | null;
  attachmentRefOf: (value: unknown) => AttachmentRef | null;
  findAttachmentRefs: (value: unknown) => AttachmentRef[];
  collectStreamAttachmentRefs: (streams: EventStream[] | null | undefined) => AttachmentRef[];
  derivedLanes: (json: string | null) => Map<string, { title?: string; derivedFrom: string[]; deriveMs?: number }>;
  applyDerivedLanes: (streams: EventStream[], json: string | null) => void;
  derivedStreamStatus: (json: string | null) => Array<{ name: string; title?: string; rows: number; ms?: number; error?: string }>;
};

// A request/response-pairing formatter, the shape a real network formatter takes: it sees the whole
// stream, so it can join a response to its request and emit ONE row with status + duration.
const pairingFormatter: EventStreamFormatter = {
  id: "sample-network",
  streams: ["com.example.plugin.network"],
  comparisonNames: { "com.example.plugin.network": "network" },
  comparisonValues(entries) {
    return entries.map((entry) => ({ kind: entry.data.request ? "request" : "response" }));
  },
  format(entries) {
    const rows: FormatterRowInput[] = [];
    const byId = new Map<string, { row: FormatterRowInput; t: number | null }>();
    for (const e of entries) {
      const d = e.data || {};
      if (d.request) {
        const row: FormatterRowInput = {
          t: e.t,
          label: `${d.request.method} ${d.request.path}`,
          fields: [{ k: "path", v: d.request.path }],
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
    lines: Array<{ line: string; t: number | null; payload: unknown | null; clock?: "device" }>;
    attachments: Array<{ value: unknown; refs: AttachmentRef[] }>;
    safePaths: Array<{ path: string; safe: boolean }>;
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
      expect(e!.clock ?? null).toBe(c.clock ?? null);
    }
  });

  test("attachment detection agrees with the shared fixtures", () => {
    expect(fixtures.attachments.length).toBeGreaterThan(0);
    for (const c of fixtures.attachments) {
      expect(ev.findAttachmentRefs(c.value)).toEqual(c.refs);
    }
  });

  // Shared with the CI uploader's `_is_safe_session_relative` rather than with Kotlin: the report
  // links every path this accepts, so a path only the uploader rejects ships a green build whose
  // Open link 404s.
  test("path safety agrees with the shared fixtures", () => {
    expect(fixtures.safePaths.length).toBeGreaterThan(0);
    for (const c of fixtures.safePaths) {
      expect(ev.isSafeSessionRelativePath(c.path)).toBe(c.safe);
    }
  });
});

// The parity fixtures pin WHAT counts as a ref; these pin the TS-only surfaces around them: the
// single-object probe's non-JSON inputs and the stream-level sweep the payload builders call.
describe("attachment refs", () => {
  const REF = { $attachment: true, path: "attachments/tone.wav", mimeType: "audio/wav", sizeBytes: 3 };

  test("attachmentRefOf rejects non-objects, arrays, and a non-finite sizeBytes", () => {
    expect(ev.attachmentRefOf(null)).toBeNull();
    expect(ev.attachmentRefOf("string")).toBeNull();
    expect(ev.attachmentRefOf([REF])).toBeNull();
    // Unreachable through JSON.parse, but the probe also sees live JS values.
    expect(ev.attachmentRefOf({ ...REF, sizeBytes: Infinity })).toBeNull();
    expect(ev.attachmentRefOf({ ...REF, sizeBytes: NaN })).toBeNull();
    expect(ev.attachmentRefOf(REF)).toEqual({ path: "attachments/tone.wav", mimeType: "audio/wav", sizeBytes: 3, label: null });
  });

  test("attachmentRefOf rejects a negative sizeBytes rather than truncating toward zero", () => {
    // A negative size is bad input, not a very small file. Truncating -0.5 would yield -0, which
    // renders as an empty byte count on a row claiming bytes that cannot exist — so the ref is
    // refused outright, exactly as Kotlin's AttachmentRef.fromJsonObjectOrNull refuses it. This
    // pair is pinned in session-events-parity-fixtures.json too; the direct assertions are here so
    // the rule is legible next to the implementation.
    expect(ev.attachmentRefOf({ ...REF, sizeBytes: -1 })).toBeNull();
    expect(ev.attachmentRefOf({ ...REF, sizeBytes: -0.5 })).toBeNull();
    // Zero is a real size (an empty capture), so it stays a ref.
    expect(ev.attachmentRefOf({ ...REF, sizeBytes: 0 })?.sizeBytes).toBe(0);
  });

  test("collectStreamAttachmentRefs sweeps generic events and formatted rows' raw payloads", () => {
    const streams: EventStream[] = [
      {
        name: "speech", total: 2, truncated: false,
        events: [
          { t: 1, d: JSON.stringify({ text: "hi", audio: REF }) },
          { t: 2, d: "{\"no\":\"refs\"}" },
        ],
      },
      {
        name: "net", total: 1, truncated: false, events: [],
        rows: [{ t: 3, label: "POST /x", raw: [{ body: { $attachment: true, path: "attachments/body.png", mimeType: "image/png", sizeBytes: 9 } }] }],
      },
    ];
    expect(ev.collectStreamAttachmentRefs(streams).map((r) => r.path))
      .toEqual(["attachments/tone.wav", "attachments/body.png"]);
  });

  test("a truncated (non-JSON) event payload contributes nothing rather than throwing", () => {
    const streams: EventStream[] = [{
      name: "s", total: 1, truncated: false,
      events: [{ t: 1, d: '{"cut":"off…' }, { t: 2, d: JSON.stringify({ a: REF }) }],
    }];
    expect(ev.collectStreamAttachmentRefs(streams).map((r) => r.path)).toEqual(["attachments/tone.wav"]);
    expect(ev.collectStreamAttachmentRefs(null)).toEqual([]);
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

describe("rootNetworkStreamFiles", () => {
  test("picks the session-root network captures, one per device", () => {
    expect(ev.rootNetworkStreamFiles(["video.webm", "network.seller.ndjson", "network.ndjson", "device.log", "trace.json"], []))
      .toEqual(["network.ndjson", "network.seller.ndjson"]);
  });

  test("a stream events/ already carries wins", () => {
    expect(ev.rootNetworkStreamFiles(["network.ndjson", "network.buyer.ndjson"], ["network"])).toEqual(["network.buyer.ndjson"]);
  });

  test("a plugin's capture of the same traffic doesn't hide the root capture", () => {
    expect(ev.rootNetworkStreamFiles(
      ["network.ndjson", "network.buyer.ndjson"],
      ["com.example.plugin.network", "com.example.plugin.network.buyer", "visible-strings"],
    )).toEqual(["network.buyer.ndjson", "network.ndjson"]);
  });

  test("ignores other ndjson files at the root", () => {
    expect(ev.rootNetworkStreamFiles(["visible-strings.ndjson", "mynetwork.ndjson"], [])).toEqual([]);
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
  test("pairs raw request/response lines into one row with status, duration, and fields", () => {
    const stream = ev.buildEventStream(
      "com.example.plugin.network.ndjson",
      [reqLine("r1", 1000, "/2.0/pay"), respLine("r1", 1142, 200)],
      [pairingFormatter],
    )!;
    expect(stream.name).toBe("com.example.plugin.network");
    expect(stream.comparisonName).toBe("network");
    expect(stream.comparisonValues).toEqual([{ kind: "request" }, { kind: "response" }]);
    expect(stream.rows).toHaveLength(1);
    const row = stream.rows![0];
    expect(row.label).toBe("POST /2.0/pay");
    expect(row.t).toBe(1000);
    expect(row.badges).toEqual([{ text: "200", tone: "ok" }, { text: "142ms" }]);
    expect(row.fields).toEqual([{ k: "path", v: "/2.0/pay" }]);
    // Raw payloads (request + response) ride along as JSON VALUES, not pre-rendered strings —
    // the viewer pretty-prints them when the row is expanded.
    expect(row.raw).toHaveLength(2);
    expect(row.raw![0]).toEqual({
      request: { id: "r1", method: "POST", path: "/2.0/pay", headers: { Accept: "application/json" } },
    });
    expect(row.raw![1]).toEqual({ response: { requestId: "r1", statusCode: 200, body: { ok: true } } });
    expect(stream.total).toBe(1);
    expect(stream.truncated).toBe(false);
    expect(stream.events).toEqual([]);
  });

  test("hands the session outcome to the formatter, defaulting to not-passed", () => {
    // A formatter keys report size budgets off ctx.sessionPassed, so the pipeline must deliver an
    // affirmative "passed" when the driver supplies one and the conservative default otherwise.
    const seen: Array<FormatterContext | undefined> = [];
    const observing: EventStreamFormatter = {
      id: "observer",
      streams: ["s"],
      format(entries, ctx) {
        seen.push(ctx);
        return [{ t: 1, label: "row" }];
      },
    };
    ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [observing], { sessionPassed: true });
    ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [observing]);
    expect(seen).toEqual([{ sessionPassed: true }, { sessionPassed: false }]);
  });

  test("a formatter's lane options open its lane and set how it folds", () => {
    const lane = (options: unknown) => ({ id: "lane", streams: ["s"], lane: options, format: () => [{ t: 1, label: "row" }] }) as EventStreamFormatter;
    const built = (options: unknown) => ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [lane(options)])!;
    expect(built({ shown: true, fold: "none" })).toMatchObject({ shown: true, fold: "none" });
    const plain = built(undefined);
    expect(plain.shown).toBeUndefined();
    expect(plain.fold).toBeUndefined();
    // A value the viewer does not know is dropped, so the lane keeps its defaults.
    const odd = built({ shown: "yes", fold: "sideways" });
    expect(odd.shown).toBeUndefined();
    expect(odd.fold).toBeUndefined();
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

  test("UI-chrome parts are clamped; raw payloads survive as whole values", () => {
    const noisy: EventStreamFormatter = {
      id: "noisy",
      streams: ["s"],
      format: () => [{
        label: "x".repeat(5000),
        badges: [{ text: "y".repeat(500) }],
        raw: [{ big: "w".repeat(100_000) }],
      }],
    };
    const row = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [noisy])!.rows![0];
    expect(row.label.length).toBeLessThanOrEqual(301);
    expect(row.badges![0].text.length).toBeLessThanOrEqual(41);
    // Raw entries are embedded as the value itself, untouched below the pathological backstop.
    expect(row.raw![0]).toEqual({ big: "w".repeat(100_000) });
  });

  test("a field's http(s) href survives the clamp; non-web and malformed hrefs are dropped", () => {
    const linky: EventStreamFormatter = {
      id: "linky",
      streams: ["s"],
      format: () => [{
        label: "notice",
        fields: [
          { k: "docs", v: "how to configure", href: "https://example.com/docs/page.md" },
          { k: "plain", v: "no link" },
          { k: "js", v: "evil", href: "javascript:alert(1)" },
          { k: "rel", v: "relative", href: "/just/a/path" },
        ],
      }],
    };
    const row = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [linky])!.rows![0];
    expect(row.fields).toEqual([
      { k: "docs", v: "how to configure", href: "https://example.com/docs/page.md" },
      { k: "plain", v: "no link" },
      { k: "js", v: "evil" },
      { k: "rel", v: "relative" },
    ]);
  });

  test("an unserializable raw entry degrades to a string instead of breaking the row", () => {
    const circular: Record<string, unknown> = { name: "loop" };
    circular.self = circular;
    const weird: EventStreamFormatter = {
      id: "weird",
      streams: ["s"],
      format: () => [{ label: "row", raw: [circular] }],
    };
    const row = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [weird])!.rows![0];
    expect(typeof row.raw![0]).toBe("string");
  });

  test("a stringify-to-undefined raw entry (function) degrades to a BOUNDED string", () => {
    // JSON.stringify(fn) returns undefined without throwing — this path must hit the same size
    // backstop as the throwing path, or a pathological entry bypasses MAX_VALUE_CHARS entirely.
    const huge = new Function(`return 1; //${"x".repeat(ev.MAX_VALUE_CHARS + 100)}`);
    const weird: EventStreamFormatter = {
      id: "weird",
      streams: ["s"],
      format: () => [{ label: "row", raw: [huge] }],
    };
    const row = ev.buildEventStream("s.ndjson", ['{"timeMs":1,"data":{}}'], [weird])!.rows![0];
    expect(typeof row.raw![0]).toBe("string");
    expect((row.raw![0] as string).length).toBeLessThanOrEqual(ev.MAX_VALUE_CHARS + 1);
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

  test("a failed comparison projection keeps the producer identity", () => {
    const formatter: EventStreamFormatter = {
      id: "unsafe-alias",
      streams: ["producer"],
      comparisonNames: { producer: "canonical" },
      comparisonValues: () => null,
      format: () => [{ label: "row" }],
    };
    const stream = ev.buildEventStream("producer.ndjson", ['{"timeMs":1,"data":{}}'], [formatter])!;
    expect(stream.comparisonName).toBeUndefined();
    expect(stream.comparisonValues).toBeUndefined();
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

describe("buildEventStream with a line naming its clock's stream", () => {
  // The phone (the start device) runs 5s ahead and the tablet 9s; a derived `screens` row copied the tablet's time.
  const sync = {
    startDeviceId: "phone-id",
    devices: [
      { deviceId: "phone-id", deviceName: "phone", samples: [{ hostBeforeMs: 1000, hostAfterMs: 1010, deviceMs: 6005 }] },
      { deviceId: "tablet-id", deviceName: "tablet", samples: [{ hostBeforeMs: 1000, hostAfterMs: 1010, deviceMs: 10005 }] },
    ],
  };

  test("converts it on that stream's device, and the rest on the file's", () => {
    const stream = ev.buildEventStream("screens.ndjson", [
      '{"timeMs":10100,"clock":"device","clockStream":"analytics.tablet","data":{"screen":"cart"}}',
      '{"timeMs":6100,"clock":"device","data":{"screen":"home"}}',
    ], [], undefined, sync)!;
    expect(stream.events).toEqual([
      { t: 1100, d: '{"screen":"cart"}' },
      { t: 1100, d: '{"screen":"home"}' },
    ]);
  });

  test("moves a row's end with its start, so an interval keeps its length", () => {
    const stream = ev.buildEventStream("spans.ndjson", [
      '{"timeMs":10100,"clock":"device","clockStream":"analytics.tablet","data":{},"row":{"label":"load","endT":10200}}',
    ], [], undefined, sync)!;
    expect(stream.rows!.map((r) => [r.t, r.endT])).toEqual([[1100, 1200]]);
  });

  test("stays on the device clock when that device measured nothing, never another's", () => {
    const none = { ...sync, devices: [sync.devices[0], { ...sync.devices[1], samples: [] }] };
    const stream = ev.buildEventStream("screens.ndjson", ['{"timeMs":10100,"clock":"device","clockStream":"analytics.tablet","data":{}}'], [], undefined, none)!;
    expect(stream.events).toEqual([{ t: 10100, d: "{}", clock: "device" }]);
  });
});

describe("buildEventStream with device clock readings", () => {
  // Device 5000ms ahead of the host, measured once.
  const sync = { devices: [{ deviceId: "emulator-5554", samples: [{ hostBeforeMs: 1000, hostAfterMs: 1010, deviceMs: 6005 }] }] };
  const lines = [
    '{"timeMs":6100,"data":{"buffered":1},"clock":"device"}',
    '{"timeMs":1200,"data":{"live":1}}',
  ];

  test("moves device-clock records onto the host timeline", () => {
    const stream = ev.buildEventStream("app-events.ndjson", lines, [], undefined, sync)!;
    expect(stream.events).toEqual([
      { t: 1100, d: '{"buffered":1}' },
      { t: 1200, d: '{"live":1}' },
    ]);
  });

  test("keeps the raw stamp when the session measured nothing", () => {
    const stream = ev.buildEventStream("app-events.ndjson", lines, [])!;
    expect(stream.events.map((e) => e.t)).toEqual([6100, 1200]);
  });

  test("a formatter sees host times and no device marker on converted entries", () => {
    let seen: FormatterEntry[] = [];
    const observing: EventStreamFormatter = {
      id: "observing",
      streams: ["app-events"],
      format(entries) {
        seen = entries;
        return [];
      },
    };
    ev.buildEventStream("app-events.ndjson", lines, [observing], undefined, sync);
    expect(seen.map((e) => [e.t, e.clock ?? null])).toEqual([
      [1100, null],
      [1200, null],
    ]);
  });
});

describe("a stream whose lines carry their own rows", () => {
  const line = (t: number, screen: string, row?: unknown) => JSON.stringify({ timeMs: t, data: { screen }, ...(row ? { row } : {}) });

  test("is shown as those rows, each keeping its payload", () => {
    const stream = ev.buildEventStream("screens.ndjson", [line(5, "Home", { label: "Home", badges: [{ text: "tab", tone: "ok" }] }), line(9, "Cart", { label: "Cart" })])!;
    expect(stream.rows!.map((r) => [r.t, r.label])).toEqual([[5, "Home"], [9, "Cart"]]);
    expect(stream.rows![0].badges).toEqual([{ text: "tab", tone: "ok" }]);
    expect(stream.rows![0].raw).toEqual([{ screen: "Home" }]);
    expect(stream.events).toEqual([]);
  });

  test("says so when it holds more rows than a lane keeps", () => {
    const lines = Array.from({ length: 100_001 }, (_, i) => line(i, "Home", { label: "Home" }));
    const stream = ev.buildEventStream("screens.ndjson", lines)!;
    expect(stream.rows!.length).toBe(100_000);
    expect(stream).toMatchObject({ total: 100_001, truncated: true });
  });

  test("stays generic when any line has no usable row, so no payload is hidden", () => {
    expect(ev.buildEventStream("screens.ndjson", [line(5, "Home", { label: "Home" }), line(9, "Cart")])!.rows).toBeUndefined();
    expect(ev.buildEventStream("screens.ndjson", [line(5, "Home", { label: "" })])!.rows).toBeUndefined();
  });

  test("stays generic when a row cannot be read, rather than dropping the stream", () => {
    const stream = ev.buildEventStream("screens.ndjson", [line(5, "Home", { label: "Home", badges: [{ text: { toString: null } }] })])!;
    expect(stream.rows).toBeUndefined();
    expect(stream.events.length).toBe(1);
  });

  test("stays generic while a line is on the device clock with no reading to move it", () => {
    const device = JSON.stringify({ timeMs: 5, clock: "device", data: { screen: "Home" }, row: { label: "Home" } });
    expect(ev.buildEventStream("screens.ndjson", [device])!.rows).toBeUndefined();
  });

  test("defers to a formatter that claims the stream", () => {
    const formatter: EventStreamFormatter = { id: "screens", streams: ["screens"], format: (entries) => entries.map((e) => ({ t: e.t, label: `fmt ${e.data.screen}` })) };
    expect(ev.buildEventStream("screens.ndjson", [line(5, "Home", { label: "Home" })], [formatter])!.rows!.map((r) => r.label)).toEqual(["fmt Home"]);
  });
});

describe("derivedStreamStatus", () => {
  test("lists every derived stream with its rows and time, including one that failed or found nothing", () => {
    const json = JSON.stringify({ format: 1, streams: {
      "experiment-eval": { rows: 16, ms: 5.4, title: "Experiment Eval" },
      flags: { rows: 0, error: "Flag service answered HTTP 403", ms: 120 },
      quiet: { rows: 0 },
    } });
    expect(ev.derivedStreamStatus(json)).toEqual([
      { name: "experiment-eval", title: "Experiment Eval", rows: 16, ms: 5 },
      { name: "flags", rows: 0, ms: 120, error: "Flag service answered HTTP 403" },
      { name: "quiet", rows: 0 },
    ]);
  });

  test("a stream recorded with an empty error is still listed as failed", () => {
    expect(ev.derivedStreamStatus(JSON.stringify({ format: 1, streams: { silent: { rows: 0, error: "" } } }))).toEqual([
      { name: "silent", rows: 0, error: "failed without a message" },
    ]);
  });

  test("drops values that are not what the manifest records, and is empty for no manifest", () => {
    expect(ev.derivedStreamStatus(JSON.stringify({ format: 1, streams: { odd: { rows: "3", ms: -2, error: 7 }, bad: null } }))).toEqual([{ name: "odd", rows: 0 }]);
    expect(ev.derivedStreamStatus(null)).toEqual([]);
    expect(ev.derivedStreamStatus("not json")).toEqual([]);
  });
});

describe("derivedLanes", () => {
  test("lists each derived stream with its title", () => {
    const lanes = ev.derivedLanes(JSON.stringify({ format: 1, streams: { screens: { rows: 3, title: "Screens" }, flags: { rows: 0 } } }));
    expect([...lanes]).toEqual([["screens", { title: "Screens", derivedFrom: [] }], ["flags", { derivedFrom: [] }]]);
  });

  test("says what each derived stream was derived from, keeping only names", () => {
    const lanes = ev.derivedLanes(JSON.stringify({ format: 1, streams: { visits: { rows: 1, derivedFrom: ["screens", 7, "", "screens", "tools"] }, odd: { rows: 1, derivedFrom: "screens" } } }));
    expect(lanes.get("visits")?.derivedFrom).toEqual(["screens", "tools"]);
    expect(lanes.get("odd")?.derivedFrom).toEqual([]);
  });

  test("marks a listed stream derived, naming its sources, and leaves a captured one alone", () => {
    const streams = [{ name: "visits", total: 1, truncated: false, events: [] }, { name: "app.views", total: 1, truncated: false, events: [] }] as EventStream[];
    ev.applyDerivedLanes(streams, JSON.stringify({ format: 1, streams: { visits: { rows: 1, title: "Cart visits", derivedFrom: ["screens"] } } }));
    expect(streams[0]).toMatchObject({ title: "Cart visits", shown: true, derivedFrom: ["screens"] });
    expect(streams[1]!.derivedFrom).toBeUndefined();
  });

  test("carries how long each stream took to derive onto its lane, and drops a time that is not one", () => {
    const streams: EventStream[] = [{ name: "visits", events: [] }, { name: "flags", events: [] }];
    ev.applyDerivedLanes(streams, JSON.stringify({ format: 1, streams: { visits: { rows: 1, ms: 34.4 }, flags: { rows: 1, ms: -1 } } }));
    expect(streams[0]!.deriveMs).toBe(34);
    expect(streams[1]!.deriveMs).toBeUndefined();
    expect(ev.derivedLanes(JSON.stringify({ format: 1, streams: { odd: { rows: 1, ms: "34" } } })).get("odd")?.deriveMs).toBeUndefined();
  });

  test("applies a derived stream's lane fold, and only a known one", () => {
    const streams = [{ name: "flags", total: 1, truncated: false, events: [] }, { name: "odd", total: 1, truncated: false, events: [] }] as EventStream[];
    ev.applyDerivedLanes(streams, JSON.stringify({ format: 1, streams: { flags: { rows: 1, lane: { fold: "none" } }, odd: { rows: 1, lane: { fold: "sideways" } } } }));
    expect(streams[0]!.fold).toBe("none");
    expect(streams[1]!.fold).toBeUndefined();
  });

  test("keeps the fold a formatter set over the one the stream declares", () => {
    const streams = [{ name: "flags", total: 1, truncated: false, events: [], fold: "none" }] as EventStream[];
    ev.applyDerivedLanes(streams, JSON.stringify({ format: 1, streams: { flags: { rows: 1, lane: { fold: "any" } } } }));
    expect(streams[0]!.fold).toBe("none");
  });

  test("is empty for an absent or malformed manifest", () => {
    expect(ev.derivedLanes(null).size).toBe(0);
    expect(ev.derivedLanes("not json").size).toBe(0);
    expect(ev.derivedLanes(JSON.stringify({ streams: [] })).size).toBe(0);
  });
});
