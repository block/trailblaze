// Behavior tests for the headless-reusable report core (run-report-core.ts). These pin the
// observable contract a headless generator (or the in-app Share button) depends on: the derived
// trace shape, and the self-contained HTML's embedded payload (single run, multi-run index, and
// the recording-YAML tab). We deliberately don't drive the DOM viewer here — instead we parse the
// embedded __TB_RUN_DATA__ payload (the data contract) and compile the serialized viewer to catch syntax
// regressions in the refactor, without coupling to render internals.
//
// Run: `bun test app/run-report-core.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";

// Tests exercise the TypeScript SOURCE directly (bun strips types in memory); the packaged
// run-report-core.js artifact is exercised end-to-end by RunReportGeneratorTest's bun-subprocess
// test, which loads it from the JAR classpath.
// eslint-disable-next-line @typescript-eslint/no-var-requires
const core = require("./run-report-core.ts") as {
  extractTrace: (logs: unknown[]) => Array<Record<string, unknown>>;
  buildRunReportHtml: (a: unknown) => string;
  buildMultiReportHtml: (a: unknown) => string;
  RUN_REPORT_VIEWER: () => void;
};

// Execute the real RUN_REPORT_VIEWER against a minimal DOM shim and return what it rendered into
// #app. Proves the viewer runs without a runtime error and lets us assert its rendered output (the
// observable contract of the export). `opts` can click a step and/or a tab (by capturing the
// data-step / data-tab onclick handlers the viewer wires) to drive a re-render — enough to test the
// timeline overlay and the secondary tabs without a real browser.
function renderViewer(payload: unknown, opts: { step?: number; tab?: string; evStream?: number } = {}): string {
  const handlers: { tab: Record<string, () => void>; step: Record<string, () => void>; evStream: Record<string, () => void> } = { tab: {}, step: {}, evStream: {} };
  const app: any = {
    _h: "",
    set innerHTML(v: string) { this._h = v; },
    get innerHTML() { return this._h; },
    querySelectorAll(sel: string) {
      if (sel === "[data-tab]") return [...this._h.matchAll(/data-tab="([a-z]+)"/g)].map((m: any) => ({ dataset: { tab: m[1] }, set onclick(fn: () => void) { handlers.tab[m[1]] = fn; } }));
      if (sel === "[data-step]") return [...this._h.matchAll(/data-step="(\d+)"/g)].map((m: any) => ({ dataset: { step: m[1] }, set onclick(fn: () => void) { handlers.step[m[1]] = fn; } }));
      if (sel === "[data-evstream]") return [...this._h.matchAll(/data-evstream="(\d+)"/g)].map((m: any) => ({ dataset: { evstream: m[1] }, set onclick(fn: () => void) { handlers.evStream[m[1]] = fn; } }));
      return [];
    },
    querySelector() { return null; },
  };
  (globalThis as Record<string, unknown>).window = globalThis;
  (globalThis as Record<string, unknown>).__TB_RUN_DATA__ = payload;
  (globalThis as Record<string, unknown>).document = {
    getElementById: (id: string) => (id === "app" ? app : null),
    addEventListener: () => {},
    createElement: () => ({ appendChild() {}, set onclick(_v: unknown) {}, remove() {}, style: {} }),
    body: { appendChild() {} },
  };
  (globalThis as Record<string, unknown>).navigator = { clipboard: { writeText() {} } };
  core.RUN_REPORT_VIEWER();
  if (opts.step != null && handlers.step[String(opts.step)]) handlers.step[String(opts.step)]();
  if (opts.tab && handlers.tab[opts.tab]) handlers.tab[opts.tab]();
  if (opts.evStream != null && handlers.evStream[String(opts.evStream)]) handlers.evStream[String(opts.evStream)]();
  return app._h;
}

const T = "xyz.block.trailblaze.logs.client.TrailblazeLog";
const sampleLogs = [
  { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap login" }, timestamp: "2024-01-01T00:00:00Z" },
  {
    class: `${T}.TrailblazeToolLog`,
    toolName: "tapOnElement",
    traceId: "t1",
    trailblazeTool: { raw: { text: "Login" } },
    screenshotFile: "a.png",
    successful: true,
    durationMs: 100,
    timestamp: "2024-01-01T00:00:01Z",
  },
  {
    class: `${T}.TrailblazeLlmRequestLog`,
    llmMessages: [],
    llmResponse: [{ parts: [{ class: "Tool.Call", tool: "tapOnElement", args: '{"reasoning":"the login button is visible","text":"Login"}' }] }],
    llmRequestUsageAndCost: { inputTokens: 10, outputTokens: 5, totalCost: 0.001, trailblazeLlmModel: { modelId: "gpt-test" } },
    durationMs: 200,
    timestamp: "2024-01-01T00:00:02Z",
  },
];

// A tool call that folds a device tap (with coordinates + device dimensions) — drives the
// set-of-mark / tap-overlay path.
const tapLogs = [
  { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "t1", trailblazeTool: { raw: { text: "Login" } }, screenshotFile: "a.png", successful: true, durationMs: 50, timestamp: "2024-01-01T00:00:00Z" },
  { class: `${T}.MaestroDriverLog`, traceId: "t1", action: { class: "xyz.AgentDriverAction.TapPoint", x: 270, y: 600 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "a.png", timestamp: "2024-01-01T00:00:00.100Z" },
];

// Pull the embedded JSON payload back out of a generated report so we can assert the data contract.
function payloadOf(html: string): { generatedAt: string; sessions: Array<Record<string, any>> } {
  const m = html.match(/window\.__TB_RUN_DATA__ = ([\s\S]*?);<\/script>/);
  if (!m) throw new Error("no __TB_RUN_DATA__ block in report HTML");
  return JSON.parse(m[1].replace(/\\u003c/g, "<"));
}

describe("extractTrace", () => {
  test("folds a tool call into one step and marks the objective", () => {
    const trace = core.extractTrace(sampleLogs);
    expect(trace.length).toBeGreaterThan(0);
    expect(trace.some((r) => r.objective === true)).toBe(true);
    expect(trace.some((r) => r.label === "tapOnElement")).toBe(true);
    // Each row carries a 1-based ordinal.
    expect(trace[0].i).toBe(1);
  });
});

describe("buildRunReportHtml (single run)", () => {
  const trace = core.extractTrace(sampleLogs);
  const llm = (core as any).extractLlmLogs(sampleLogs);
  const html = core.buildRunReportHtml({
    meta: { title: "My run", status: "passed", platform: "android", recordingYaml: "- prompts:\n  - tap login\n" },
    trace,
    llmLogs: llm,
    shots: { "a.png": "data:image/png;base64,AAAA" },
  });

  test("is a self-contained document embedding the viewer + data", () => {
    expect(html.startsWith("<!doctype html>")).toBe(true);
    expect(html).toContain("window.__TB_RUN_DATA__ =");
    expect(html).toContain("function RUN_REPORT_VIEWER");
    expect(html).toContain("My run"); // title in <title>
  });

  test("wraps the single run into a sessions[] payload of length 1", () => {
    const p = payloadOf(html);
    expect(p.sessions).toHaveLength(1);
    expect(p.sessions[0].meta.title).toBe("My run");
    expect(p.sessions[0].meta.steps).toBe(trace.length);
    expect(p.sessions[0].shots["a.png"]).toContain("data:image/png");
  });

  test("carries the recording YAML so the Recording tab can render", () => {
    const p = payloadOf(html);
    expect(p.sessions[0].recordingYaml).toContain("tap login");
  });

  test("the serialized viewer is syntactically valid", () => {
    // Compile (not run) the embedded viewer to catch brace/scope regressions from the refactor.
    const m = html.match(/<script>\((function RUN_REPORT_VIEWER[\s\S]*?)\)\(\);<\/script>/);
    expect(m).not.toBeNull();
    expect(() => new Function(`return (${m![1]})`)).not.toThrow();
  });
});

describe("buildMultiReportHtml (multi run)", () => {
  const trace = core.extractTrace(sampleLogs);
  const html = core.buildMultiReportHtml({
    generatedAt: "2024-01-01 00:00:00",
    sessions: [
      { meta: { title: "Run A", status: "passed", platform: "android" }, trace, llmLogs: [], shots: {}, recordingYaml: null },
      { meta: { title: "Run B", status: "failed", platform: "ios" }, trace, llmLogs: [], shots: {}, recordingYaml: "- prompts: []" },
    ],
  });

  test("embeds every session in the payload", () => {
    const p = payloadOf(html);
    expect(p.sessions).toHaveLength(2);
    expect(p.sessions.map((s) => s.meta.title)).toEqual(["Run A", "Run B"]);
    expect(p.sessions[1].meta.status).toBe("failed");
  });

  test("titles the document by run count", () => {
    expect(html).toContain("2 Trailblaze runs");
  });
});

describe("RUN_REPORT_VIEWER (rendered output)", () => {
  const trace = core.extractTrace(sampleLogs);
  const slim = (core as any).slimTraceForShare(trace);
  const session = (title: string, status: string) => ({ meta: { title, status }, trace: slim, llm: [], shots: {}, recordingYaml: null });

  test("multi-session index counts passed/failed/other distinctly (cancelled is not failed)", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [session("A", "passed"), session("B", "failed"), session("C", "cancelled")],
    });
    // The index renders without a runtime error and the tally agrees with the per-row statuses:
    // 1 passed, 1 failed, 1 other (the cancelled run) — NOT 2 failed.
    expect(out).toContain("idxsum");
    expect(out).toContain(">passed<");
    expect(out).toContain(">other<");
    const cell = (label: string) => {
      const m = out.match(new RegExp(`>(\\d+)</div><div class="t">${label}<`));
      return m ? Number(m[1]) : null;
    };
    expect(cell("passed")).toBe(1);
    expect(cell("failed")).toBe(1);
    expect(cell("other")).toBe(1);
  });

  test("the timeline preview offers a Play control to scrub screenshots", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).toContain('id="tlplay"');
    expect(out).toContain("▶ Play");
  });

  test("the timeline shows per-step elapsed time and duration, and a per-step dots rail", () => {
    // sampleLogs: objective at T+0s, tool (100ms) at T+1s — the row carries both the run-clock
    // offset and its own duration, and the preview gets one clickable dot per step.
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).toContain("+1.0s");
    expect(out).toContain("100ms");
    expect(out).toContain('class="tlrail"');
    expect(out).toContain("tldot");
  });

  test("an objective that ultimately passed keeps a green group dot despite a failed row inside it", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Sign in" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.MaestroDriverLog`, action: { class: "xyz.AgentDriverAction.AssertCondition", conditionDescription: "field visible", x: 1, y: 2, succeeded: false }, deviceWidth: 100, deviceHeight: 200, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.ObjectiveCompleteLog`, promptStep: { step: "Sign in" }, objectiveResult: { class: "xyz.AgentTaskStatus.Success.ObjectiveComplete" }, timestamp: "2024-01-01T00:00:02Z" },
    ];
    const slim = (core as any).slimTraceForShare(core.extractTrace(logs));
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null }] });
    const hdr = out.match(/grphdr[\s\S]*?dot" style="background:(var\(--(?:pass|fail)\))/);
    expect(hdr).not.toBeNull();
    expect(hdr![1]).toBe("var(--pass)");
    // ...and a PASSED run doesn't auto-select the recovered-from failed row on open.
    const failedRow = slim.find((t: any) => !t.ok);
    expect(out).not.toContain(`class="step sel child" data-step="${failedRow.i}"`);
  });

  test("a self-heal run shows the self-heal marker badge next to its status", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "Healed", status: "passed", selfHeal: true }, trace: slim, llm: [], shots: {}, recordingYaml: null }],
    });
    expect(out).toContain("badge selfheal");
  });

  test("LLM session totals surface cached input tokens and average response time", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "R", status: "passed" }, trace: slim, shots: {}, recordingYaml: null,
        llm: [{ model: "m", inputTokens: 100, outputTokens: 10, cacheReadTokens: 40, totalCost: 0.001, durationMs: 2000, label: "LLM Request", instructions: null, response: [] }],
      }],
    }, { tab: "llm" });
    expect(out).toContain("cached input");
    expect(out).toContain("avg response");
    expect(out).toContain("2.0s");
  });

  test("a single run opens straight on its detail with a Recording tab when YAML is present", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "Solo", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: "- prompts: []" }],
    });
    expect(out).toContain("Solo");
    expect(out).toContain(">Recording<");
    expect(out).toContain('class="steps"');
  });
});

describe("device-action marks (set-of-mark / tap overlay)", () => {
  test("extractTrace folds a tap's coordinates + device dimensions onto the step", () => {
    const trace = core.extractTrace(tapLogs);
    const marked = trace.find((r: any) => r.mark) as any;
    expect(marked).toBeTruthy();
    expect(marked.mark.kind).toBe("tap");
    expect(marked.mark.x).toBe(270);
    expect(marked.mark.y).toBe(600);
    expect(marked.mark.dw).toBe(1080);
    expect(marked.mark.dh).toBe(2400);
  });

  test("the timeline overlays the tap mark on the step's own screenshot", () => {
    const slim = (core as any).slimTraceForShare(core.extractTrace(tapLogs));
    const marked = slim.find((t: any) => t.mark);
    const out = renderViewer(
      { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace: slim, llm: [], shots: { "a.png": "data:image/png;base64,AAAA" }, recordingYaml: null }] },
      { step: marked.i },
    );
    expect(out).toContain("mark tap");
  });

  test("a failed assertion renders the red full-screen border (from action.succeeded)", () => {
    const failAssert = [
      { class: `${T}.MaestroDriverLog`, action: { class: "xyz.AgentDriverAction.AssertCondition", conditionDescription: "X visible", x: 10, y: 20, succeeded: false }, deviceWidth: 100, deviceHeight: 200, screenshotFile: "a.png", timestamp: "2024-01-01T00:00:00Z" },
    ];
    const slim = (core as any).slimTraceForShare(core.extractTrace(failAssert));
    const marked = slim.find((t: any) => t.mark);
    expect(marked.mark.kind).toBe("assert");
    expect(marked.mark.ok).toBe(false);
    const out = renderViewer(
      { generatedAt: "now", sessions: [{ meta: { title: "R", status: "failed" }, trace: slim, llm: [], shots: { "a.png": "data:image/png;base64,AAAA" }, recordingYaml: null }] },
      { step: marked.i },
    );
    expect(out).toContain("markborder");
  });
});

describe("secondary tabs (device logs, network, grid, video)", () => {
  const slim = (core as any).slimTraceForShare(core.extractTrace(sampleLogs));
  const payload = {
    generatedAt: "now",
    sessions: [{
      meta: { title: "Run", status: "passed" },
      trace: slim,
      llm: [],
      shots: { "a.png": "data:image/png;base64,AAAA" },
      recordingYaml: null,
      deviceLog: "I/x ok\nE/y FATAL boom",
      network: [
        { method: "GET", statusCode: 200, durationMs: 5, urlPath: "/ok", phase: "RESPONSE_END" },
        { method: "POST", statusCode: 500, durationMs: 9, urlPath: "/fail", phase: "RESPONSE_END" },
      ],
      video: { sprite: "data:image/webp;base64,AAAA", fps: 2, frames: 2, columns: 1, rows: 2, frameHeight: 40, frameMap: [0, 1], startFrame: 0, endFrame: 1 },
    }],
  };

  test("nav exposes Grid, Video, Device logs and Network tabs when their data is present", () => {
    const out = renderViewer(payload);
    for (const tab of ["Grid", "Video", "Device logs", "Network"]) expect(out).toContain(">" + tab + "<");
  });

  test("network tab flags >=400 responses as errors", () => {
    const out = renderViewer(payload, { tab: "network" });
    expect(out).toContain("/fail");
    expect(out).toContain("ln e"); // error-level row class
  });

  test("video tab renders the sprite frame box and a scrubber", () => {
    const out = renderViewer(payload, { tab: "video" });
    expect(out).toContain('id="vframe"');
    expect(out).toContain('id="vseek"');
  });

  test("video tab offers play, elapsed/total time, and a playback-speed control", () => {
    const out = renderViewer(payload, { tab: "video" });
    expect(out).toContain('id="vplay"');
    // 2 frames @ 2fps → a 1.0s clip; the readout is time-based, not a bare frame counter.
    expect(out).toContain("1.0s");
    expect(out).toContain('id="vspeed"');
  });

  test("grid tab renders a thumbnail cell per screenshot step", () => {
    const out = renderViewer(payload, { tab: "grid" });
    expect(out).toContain("galcell");
  });

  test("device-logs tab renders the log with error-level highlighting", () => {
    const out = renderViewer(payload, { tab: "device" });
    expect(out).toContain("logpane");
    expect(out).toContain("FATAL");
    expect(out).toContain("ln e");
  });

  test("device-logs and network tabs offer a text filter and severity chips", () => {
    const dev = renderViewer(payload, { tab: "device" });
    expect(dev).toContain('id="dlq"');
    expect(dev).toContain('data-lvl="e"');
    const net = renderViewer(payload, { tab: "network" });
    expect(net).toContain('id="nlq"');
    expect(net).toContain('data-lvl="e"');
  });
});

describe("events tab (generic session-events streams)", () => {
  // Producer-agnostic: two streams as the driver emits them; the renderer knows nothing about any
  // specific producer. `total` > events.length marks a stream the driver truncated.
  const payload = {
    generatedAt: "now",
    sessions: [{
      meta: { title: "Events run", status: "passed" },
      trace: [], llm: [], shots: {}, recordingYaml: null, deviceLog: null, network: null, video: null,
      events: [
        { name: "com.example.plugin.network", style: "json", total: 3, truncated: false, events: [
          { t: 1000, d: '{"request":{"url":"https://api.test/foo"}}' },
          { t: 1500, d: '{"finalizedResponse":{"statusCode":200}}' },
          { t: 2000, d: '{"error":{"reason":"x"}}' },
        ] },
        { name: "com.example.plugin.analytics", style: "json", total: 120, truncated: true, events: [
          { t: 1200, d: '{"Event":"ColdStart"}' },
        ] },
      ],
    }],
  };

  test("nav exposes an Events tab only when event streams are present", () => {
    expect(renderViewer(payload)).toContain('data-tab="events"');
    const noEvents = { ...payload, sessions: [{ ...payload.sessions[0], events: null }] };
    expect(renderViewer(noEvents)).not.toContain('data-tab="events"');
  });

  test("events tab lists one selectable chip per producer with its total count", () => {
    const out = renderViewer(payload, { tab: "events" });
    expect(out).toContain("com.example.plugin.network");
    expect(out).toContain("com.example.plugin.analytics");
    expect(out).toContain('data-evstream="0"');
    expect(out).toContain('data-evstream="1"');
    expect(out).toContain("2 streams");
  });

  test("the default stream renders each event at its offset from the stream's first event", () => {
    const out = renderViewer(payload, { tab: "events" });
    expect(out).toContain("+0.00s");
    expect(out).toContain("+0.50s");
    expect(out).toContain("+1.00s");
    expect(out).toContain("api.test/foo");
  });

  test("selecting a truncated stream shows the 'last N of M' note", () => {
    const out = renderViewer(payload, { tab: "events", evStream: 1 });
    expect(out).toContain("ColdStart");
    expect(out).toContain("showing last 1 of 120");
  });
});

describe("extractLlmLogs accounting", () => {
  const extractLlmLogs = (core as any).extractLlmLogs;
  const usage = { inputTokens: 100, outputTokens: 10, promptCost: 0.002, completionCost: 0.0001, trailblazeLlmModel: { modelId: "m" } };

  test("dedupes a request log and its paired MCP-sampling log (same traceId) and computes cost", () => {
    const rows = extractLlmLogs([
      { class: `${T}.TrailblazeLlmRequestLog`, traceId: "llm-1", llmMessages: [], llmResponse: [], llmRequestUsageAndCost: usage, durationMs: 100, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.McpSamplingLog`, traceId: "llm-1", usageAndCost: usage, systemPrompt: "sys", userMessage: "u", durationMs: 100, timestamp: "2024-01-01T00:00:00.1Z" },
    ]);
    expect(rows).toHaveLength(1); // not 2 — the sampling log is the same call as the request log
    expect(rows[0].inputTokens).toBe(100);
    // cost = promptCost + completionCost (the logs carry these, not a precomputed totalCost)
    expect(rows[0].totalCost).toBeCloseTo(0.0021, 6);
  });

  test("still counts an MCP-sampling log that has no paired request log (pure-MCP session)", () => {
    const rows = extractLlmLogs([
      { class: `${T}.McpSamplingLog`, traceId: "llm-solo", usageAndCost: usage, systemPrompt: "sys", userMessage: "u", durationMs: 50, timestamp: "2024-01-01T00:00:00Z" },
    ]);
    expect(rows).toHaveLength(1);
    expect(rows[0].label).toBe("MCP Sampling");
  });
});

describe("extractTrace failed assertion", () => {
  test("a failed AssertCondition marks the step ok:false so it renders as failed", () => {
    const trace = core.extractTrace([
      { class: `${T}.MaestroDriverLog`, action: { class: "xyz.AgentDriverAction.AssertCondition", conditionDescription: "X visible", x: 1, y: 2, succeeded: false }, deviceWidth: 100, deviceHeight: 200, screenshotFile: "a.png", errorMessage: "Assertion failed: not found", timestamp: "2024-01-01T00:00:00Z" },
    ]) as any[];
    const assertRow = trace.find((r) => r.label === "AssertCondition");
    expect(assertRow).toBeTruthy();
    expect(assertRow.ok).toBe(false);
    expect(String(assertRow.err)).toContain("Assertion failed");
  });
});

describe("extractTrace objective failure (MCP-sampling agents)", () => {
  test("a Failure ObjectiveCompleteLog marks its objective row failed", () => {
    const trace = core.extractTrace([
      { class: `${T}.ObjectiveStartLog`, promptStep: { verify: "A cart is visible" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.ObjectiveCompleteLog`, promptStep: { verify: "A cart is visible" }, objectiveResult: { class: "xyz.block.trailblaze.agent.model.AgentTaskStatus.Failure.ObjectiveFailed", llmExplanation: "No cart on screen" }, timestamp: "2024-01-01T00:00:01Z" },
    ]) as any[];
    const obj = trace.find((r) => r.objective);
    expect(obj).toBeTruthy();
    expect(obj.ok).toBe(false);
    expect(String(obj.err)).toContain("No cart");
  });

  test("a Success ObjectiveCompleteLog leaves its objective row passing", () => {
    const trace = core.extractTrace([
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open Settings" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.ObjectiveCompleteLog`, promptStep: { step: "Open Settings" }, objectiveResult: { class: "xyz.block.trailblaze.agent.model.AgentTaskStatus.Success.ObjectiveComplete" }, timestamp: "2024-01-01T00:00:01Z" },
    ]) as any[];
    expect(trace.find((r) => r.objective).ok).toBe(true);
  });
});
