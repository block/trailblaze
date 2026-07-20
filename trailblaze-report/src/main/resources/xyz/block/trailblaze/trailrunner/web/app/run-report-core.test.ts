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
  originalYamlFromLogs: (logs: unknown[]) => string | null;
  yamlRootSection: (yaml: string | null, key: string) => string | null;
  localRunAgentPrompt: (meta: Record<string, unknown> | null) => string | null;
  extractTrace: (logs: unknown[]) => Array<Record<string, unknown>>;
  buildRunReportHtml: (a: unknown) => string;
  buildMultiReportHtml: (a: unknown) => string;
  RUN_REPORT_VIEWER: () => void;
};

const T = "xyz.block.trailblaze.logs.client.TrailblazeLog";

describe("originalYamlFromLogs", () => {
  test("uses the source captured at session start instead of a later trail revision", () => {
    expect(core.originalYamlFromLogs([
      { class: `${T}.TrailblazeSessionStatusChangeLog`, sessionStatus: { rawYaml: "trail:\n  - step: Original" } },
      { class: `${T}.TrailblazeSessionStatusChangeLog`, sessionStatus: { class: "Ended.Succeeded" } },
    ])).toContain("Original");
  });

  test("returns null for older sessions that did not capture source YAML", () => {
    expect(core.originalYamlFromLogs([{ sessionStatus: { trailFilePath: "/private/secret" } }])).toBeNull();
  });
});

describe("yamlRootSection", () => {
  test("preserves only the requested authored root block", () => {
    const yaml = `id: checkout/demo
config:
  retries: 2
  locale: en-US
trailhead:
  step: Open the demo app
trail:
  - step: Complete checkout`;
    expect(core.yamlRootSection(yaml, "config")).toBe("config:\n  retries: 2\n  locale: en-US");
  });

  test("supports the list-shaped v1 root form", () => {
    const yaml = `- config:
    retries: 2
- trail:
    - step: Continue`;
    expect(core.yamlRootSection(yaml, "config")).toBe("- config:\n    retries: 2");
  });
});

describe("localRunAgentPrompt", () => {
  test("gives an agent exact CLI and Trail Runner instructions for the same test", () => {
    const prompt = core.localRunAgentPrompt({
      title: "Checkout",
      trailId: "sample/checkout",
      target: "sample-ios",
      platform: "ios",
      cmd: "./trailblaze run trails/checkout.trail.yaml",
    });
    expect(prompt).toContain("Test: Checkout");
    expect(prompt).toContain("Trail: sample/checkout");
    expect(prompt).toContain("Target: sample-ios");
    expect(prompt).toContain("`./trailblaze run trails/checkout.trail.yaml`");
    expect(prompt).toContain("`./trailblaze app`");
    expect(prompt).toContain("select the sample/checkout trail");
  });

  test("is unavailable when the report did not capture a trail path", () => {
    expect(core.localRunAgentPrompt({ title: "Unknown run" })).toBeNull();
  });
});

// Execute the real RUN_REPORT_VIEWER against a minimal DOM shim and return what it rendered into
// #app. Proves the viewer runs without a runtime error and lets us assert its rendered output (the
// observable contract of the export). `opts` can click a step and/or a tab (by capturing the
// data-step / data-tab onclick handlers the viewer wires) to drive a re-render — enough to test the
// timeline overlay and the secondary tabs without a real browser.
type ViewerOptions = { session?: number; step?: number; routeStep?: number; query?: string; legacyHash?: string; tab?: string; lightboxAll?: boolean; zoomShot?: string; zoomKey?: "ArrowLeft" | "ArrowRight"; timelineKey?: "ArrowLeft" | "ArrowRight"; timelineKeyTarget?: string; evStream?: number; tlStream?: number; tlStreamBeforeTab?: number; spaceOnStep?: number; timelineScrollTop?: number; focusedStep?: number; focusedTlStream?: number; transport?: "prev" | "next"; stackedTimeline?: boolean; shotLayoutShift?: boolean; copyLocalPrompt?: boolean; viewer?: () => void };

function renderViewerState(payload: unknown, opts: ViewerOptions = {}): { html: string; timelineScrollTop: number; mainScrollTop: number; restoredFocus: string | null; route: string; zoomSrc: string | null; copiedText: string | null } {
  const handlers: { session: Record<string, () => void>; tab: Record<string, () => void>; step: Record<string, () => void>; stepKey: Record<string, (e: any) => void>; shot: Record<string, () => void>; evStream: Record<string, () => void>; tlStream: Record<string, () => void>; documentKey?: (e: any) => void; timelinePlay?: () => void; gridMode?: () => void; prev?: () => void; next?: () => void; shotLoad?: () => void; copyLocalPrompt?: () => void } = { session: {}, tab: {}, step: {}, stepKey: {}, shot: {}, evStream: {}, tlStream: {} };
  let shotLoaded = !opts.shotLayoutShift;
  const mainScroller: any = { scrollTop: 0, clientHeight: 400, get scrollHeight() { return opts.shotLayoutShift && !shotLoaded ? 800 : 1200; }, parentElement: null, getBoundingClientRect: () => ({ top: 0 }), scrollTo({ top }: { top: number }) { this.scrollTop = top; } };
  const timelineList: any = { scrollTop: 0, clientHeight: 400, scrollHeight: opts.stackedTimeline ? 400 : 1200, parentElement: opts.stackedTimeline ? mainScroller : null, getBoundingClientRect: () => ({ top: 0 }), scrollTo({ top }: { top: number }) { this.scrollTop = top; } };
  let restoredFocus: string | null = null;
  const app: any = {
    _h: "",
    set innerHTML(v: string) { this._h = v; timelineList.scrollTop = 0; },
    get innerHTML() { return this._h; },
    querySelectorAll(sel: string) {
      if (sel === "[data-session]") return [...this._h.matchAll(/data-session="(\d+)"/g)].map((m: any) => ({ dataset: { session: m[1] }, set onclick(fn: () => void) { handlers.session[m[1]] = fn; } }));
      if (sel === "[data-tab]") return [...this._h.matchAll(/data-tab="([a-z]+)"/g)].map((m: any) => ({ dataset: { tab: m[1] }, set onclick(fn: () => void) { handlers.tab[m[1]] = fn; } }));
      if (sel === "[data-step]") return [...this._h.matchAll(/data-step="(\d+)"/g)].map((m: any) => ({ dataset: { step: m[1] }, set onclick(fn: () => void) { handlers.step[m[1]] = fn; } }));
      if (sel === "[data-evstream]") return [...this._h.matchAll(/data-evstream="(\d+)"/g)].map((m: any) => ({ dataset: { evstream: m[1] }, set onclick(fn: () => void) { handlers.evStream[m[1]] = fn; } }));
      if (sel === "[data-tlstream]") return [...this._h.matchAll(/data-tlstream="(\d+)"/g)].map((m: any) => ({ dataset: { tlstream: m[1] }, set onclick(fn: () => void) { handlers.tlStream[m[1]] = fn; } }));
      if (sel === "[data-shot]") return [...this._h.matchAll(/data-shot="([^"]+)"/g)].map((m: any) => ({ dataset: { shot: m[1] }, set onclick(fn: () => void) { handlers.shot[m[1]] = fn; } }));
      if (sel === '[role="button"][tabindex="0"]') return [...this._h.matchAll(/<div[^>]*data-step="(\d+)"[^>]*role="button" tabindex="0"/g)].map((m: any) => ({
        dataset: { step: m[1] },
        click: () => handlers.step[m[1]] && handlers.step[m[1]](),
        set onkeydown(fn: (e: any) => void) { handlers.stepKey[m[1]] = fn; },
      }));
      return [];
    },
    querySelector(sel: string) {
      if (sel === ".timeline-list" && this._h.includes('class="timeline-list"')) return timelineList;
      if (sel === ".preview .shot" && this._h.includes('class="shot')) return { complete: shotLoaded, addEventListener: (_name: string, fn: () => void) => { handlers.shotLoad = fn; } };
      const step = sel.match(/^\[data-step="(\d+)"\]$/);
      if (step && this._h.includes(`data-step="${step[1]}"`)) return { focus: () => { restoredFocus = sel; }, getBoundingClientRect: () => ({ top: (shotLoaded ? 500 : 300) - (opts.stackedTimeline ? mainScroller.scrollTop : timelineList.scrollTop), height: 40 }) };
      const tlStream = sel.match(/^\[data-tlstream="(\d+)"\]$/);
      if (tlStream && this._h.includes(`data-tlstream="${tlStream[1]}"`)) return { focus: () => { restoredFocus = sel; } };
      return null;
    },
  };
  (globalThis as Record<string, unknown>).window = globalThis;
  (globalThis as Record<string, unknown>).__TB_RUN_DATA__ = payload;
  const routeQuery = opts.query ?? (opts.routeStep == null ? "" : `?run=0&tab=timeline&step=${opts.routeStep}`);
  const testLocation = { pathname: "/report.html", search: routeQuery, hash: opts.legacyHash || "" };
  (globalThis as Record<string, unknown>).location = testLocation;
  let route = "";
  const navigate = (next: string) => {
    route = next;
    const parsed = new URL(next, "https://report.example");
    testLocation.pathname = parsed.pathname;
    testLocation.search = parsed.search;
    testLocation.hash = parsed.hash;
  };
  (globalThis as Record<string, unknown>).history = {
    pushState(_state: unknown, _title: string, next: string) { navigate(next); },
    replaceState(_state: unknown, _title: string, next: string) { navigate(next); },
  };
  const activeElement = opts.focusedStep != null ? {
    id: "", dataset: { step: String(opts.focusedStep) }, matches: (sel: string) => sel === "[data-step]",
  } : opts.focusedTlStream != null ? {
    id: "", dataset: { tlstream: String(opts.focusedTlStream) }, matches: (sel: string) => sel === "[data-tlstream]",
  } : null;
  let zoomSrc: string | null = null;
  let copiedText: string | null = null;
  const createElement = (tag: string) => {
    const node: any = {
      children: [], style: {}, className: "", textContent: "", disabled: false,
      appendChild(child: any) { this.children.push(child); },
      setAttribute() {}, insertAdjacentHTML() {}, remove() {}, focus() {},
      set src(value: string) { this._src = value; if (tag === "img") zoomSrc = value; },
      get src() { return this._src; },
    };
    return node;
  };
  (globalThis as Record<string, unknown>).document = {
    activeElement,
    getElementById: (id: string) => id === "app" ? app
      : id === "tlplay" ? { click: () => handlers.timelinePlay && handlers.timelinePlay(), set onclick(fn: () => void) { handlers.timelinePlay = fn; } }
      : id === "lightboxmode" && app._h.includes('id="lightboxmode"') ? { set onclick(fn: () => void) { handlers.gridMode = fn; } }
      : id === "prev" ? { set onclick(fn: () => void) { handlers.prev = fn; } }
      : id === "next" ? { set onclick(fn: () => void) { handlers.next = fn; } }
      : id === "copylocalprompt" && app._h.includes('id="copylocalprompt"') ? { textContent: "", set onclick(fn: () => void) { handlers.copyLocalPrompt = fn; } }
      : null,
    addEventListener: (name: string, fn: (e: any) => void) => { if (name === "keydown") handlers.documentKey = fn; },
    createElement,
    body: { appendChild() {} },
  };
  (globalThis as Record<string, unknown>).navigator = { clipboard: { writeText(text: string) { copiedText = text; } } };
  (globalThis as Record<string, unknown>).getComputedStyle = (el: unknown) => ({ overflowY: el === mainScroller || (el === timelineList && !opts.stackedTimeline) ? "auto" : "visible" });
  (opts.viewer || core.RUN_REPORT_VIEWER)();
  if (opts.session != null && handlers.session[String(opts.session)]) handlers.session[String(opts.session)]();
  if (opts.timelineScrollTop != null) timelineList.scrollTop = opts.timelineScrollTop;
  if (opts.step != null && handlers.step[String(opts.step)]) handlers.step[String(opts.step)]();
  if (opts.tlStreamBeforeTab != null && handlers.tlStream[String(opts.tlStreamBeforeTab)]) handlers.tlStream[String(opts.tlStreamBeforeTab)]();
  if (opts.tab && handlers.tab[opts.tab]) handlers.tab[opts.tab]();
  if (opts.lightboxAll && handlers.gridMode) handlers.gridMode();
  if (opts.zoomShot && handlers.shot[opts.zoomShot]) handlers.shot[opts.zoomShot]();
  if (opts.zoomKey && handlers.documentKey) handlers.documentKey({ key: opts.zoomKey, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } });
  if (opts.timelineKey && handlers.documentKey) handlers.documentKey({
    key: opts.timelineKey,
    target: opts.timelineKeyTarget ? { tagName: opts.timelineKeyTarget, isContentEditable: false } : undefined,
    defaultPrevented: false,
    preventDefault() { this.defaultPrevented = true; },
  });
  if (opts.evStream != null && handlers.evStream[String(opts.evStream)]) handlers.evStream[String(opts.evStream)]();
  if (opts.tlStream != null && handlers.tlStream[String(opts.tlStream)]) handlers.tlStream[String(opts.tlStream)]();
  if (opts.transport && handlers[opts.transport]) handlers[opts.transport]!();
  if (opts.copyLocalPrompt && handlers.copyLocalPrompt) handlers.copyLocalPrompt();
  if (opts.shotLayoutShift && handlers.shotLoad) { shotLoaded = true; handlers.shotLoad(); }
  if (opts.spaceOnStep != null && handlers.stepKey[String(opts.spaceOnStep)]) {
    const event = { key: " ", defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    handlers.stepKey[String(opts.spaceOnStep)](event);
    if (handlers.documentKey) handlers.documentKey(event);
  }
  return { html: app._h, timelineScrollTop: timelineList.scrollTop, mainScrollTop: mainScroller.scrollTop, restoredFocus, route, zoomSrc, copiedText };
}

function renderViewer(payload: unknown, opts: ViewerOptions = {}): string {
  return renderViewerState(payload, opts).html;
}

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

// The report's executable script (embedded helper declarations + the viewer IIFE) — the exact code
// a browser runs when someone opens the exported file.
function viewerScriptOf(html: string): string {
  const last = html.split("<script>").pop() ?? "";
  const end = last.indexOf("</script>");
  if (end < 0) throw new Error("no viewer script block in report HTML");
  return last.slice(0, end);
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

  test("a trailhead objective carries the trailhead flag; plain objectives don't", () => {
    // The trail's `trailhead:` (step 0) lowers to a DirectionStep with isTrailhead, which rides
    // through the ObjectiveStartLog's promptStep — the timeline renders it TRAILHEAD, unnumbered.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch signed in", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap login" }, timestamp: "2024-01-01T00:00:01Z" },
    ];
    const trace = core.extractTrace(logs);
    const th = trace.find((r) => String(r.label).includes("Launch signed in"));
    const plain = trace.find((r) => String(r.label).includes("Tap login"));
    expect(th.objective).toBe(true);
    expect(th.trailhead).toBe(true);
    expect(plain.objective).toBe(true);
    expect(plain.trailhead).toBe(false);
    // And it survives the share slimming (the standalone report renders from the slimmed shape).
    const slim = core.slimTraceForShare(trace);
    expect(slim.find((r) => String(r.label).includes("Launch signed in")).trailhead).toBe(true);
  });
  test("renders a terminal snapshot (final_screenshot) as its own trailing cell", () => {
    // captureFinalScreenshot logs a TrailblazeSnapshotLog carrying only a screenshotFile +
    // displayName (no tool/action/prompt). It must still produce a cell so the state after the
    // last action is shown; otherwise it falls through every branch and is silently dropped.
    const logs = [
      ...sampleLogs,
      {
        class: `${T}.TrailblazeSnapshotLog`,
        displayName: "final_screenshot",
        screenshotFile: "final.png",
        timestamp: "2024-01-01T00:00:03Z",
      },
    ];
    const trace = core.extractTrace(logs);
    const last = trace[trace.length - 1];
    expect(last.screenshotFile).toBe("final.png");
    expect(String(last.label)).toContain("Final");
  });
});

describe("shotForStep (timeline preview image)", () => {
  // Two steps, each with its own screenshot. A later step's header should preview that step's
  // OWN first screen (what it's about to do), not the previous step's trailing frame.
  const twoStepLogs = [
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step one" }, timestamp: "2024-01-01T00:00:00Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapA", traceId: "t1", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:01Z" },
    { class: `${T}.MaestroDriverLog`, traceId: "t1", action: { class: "xyz.AgentDriverAction.TapPoint", x: 1, y: 1 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "a1.png", timestamp: "2024-01-01T00:00:01.100Z" },
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step two" }, timestamp: "2024-01-01T00:00:02Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapB", traceId: "t2", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:03Z" },
    { class: `${T}.MaestroDriverLog`, traceId: "t2", action: { class: "xyz.AgentDriverAction.TapPoint", x: 2, y: 2 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "a2.png", timestamp: "2024-01-01T00:00:03.100Z" },
  ];

  test("a later step header previews its own step's first screen, not the previous step's frame", () => {
    const trace = core.extractTrace(twoStepLogs);
    const stepTwo = trace.find((r) => r.objective === true && String(r.label).includes("Step two"));
    expect(stepTwo).toBeTruthy();
    const html = core.buildRunReportHtml({
      meta: { title: "R", status: "passed" },
      trace,
      llmLogs: [],
      shots: { "a1.png": "data:img,A1", "a2.png": "data:img,A2" },
    });
    const out = renderViewer(payloadOf(html), { routeStep: Number(stepTwo!.i) });
    const shot = out.match(/id="shot" src="([^"]*)"/);
    expect(shot).not.toBeNull();
    expect(shot![1]).toBe("data:img,A2");
  });

  test("a frameless middle step's header does NOT preview the next objective's frame", () => {
    // Step two captures nothing; its forward scan must stop at Step three's header and fall back to
    // the nearest earlier frame (Step one), never crossing into a future step's screen.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step one" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapA", traceId: "t1", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "t1", action: { class: "xyz.AgentDriverAction.TapPoint", x: 1, y: 1 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "a1.png", timestamp: "2024-01-01T00:00:01.100Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step two (no capture)" }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step three" }, timestamp: "2024-01-01T00:00:03Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapC", traceId: "t3", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:04Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "t3", action: { class: "xyz.AgentDriverAction.TapPoint", x: 3, y: 3 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "a3.png", timestamp: "2024-01-01T00:00:04.100Z" },
    ];
    const trace = core.extractTrace(logs);
    const mid = trace.find((r) => r.objective === true && String(r.label).includes("Step two"));
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace, llmLogs: [], shots: { "a1.png": "data:img,A1", "a3.png": "data:img,A3" } });
    const out = renderViewer(payloadOf(html), { step: Number(mid!.i) });
    const shot = out.match(/id="shot" src="([^"]*)"/);
    expect(shot).not.toBeNull();
    expect(shot![1]).toBe("data:img,A1"); // nearest earlier frame — NOT step three's a3
  });

  test("falls back to an earlier frame when the forward candidate's screenshot didn't inline", () => {
    // Step two's only forward frame (gone.png) failed to inline (absent from shots). The scan must
    // skip it and fall back to Step one's a1, not render an empty pane.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step one" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapA", traceId: "t1", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "t1", action: { class: "xyz.AgentDriverAction.TapPoint", x: 1, y: 1 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "a1.png", timestamp: "2024-01-01T00:00:01.100Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step two" }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapB", traceId: "t2", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:03Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "t2", action: { class: "xyz.AgentDriverAction.TapPoint", x: 2, y: 2 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "gone.png", timestamp: "2024-01-01T00:00:03.100Z" },
    ];
    const trace = core.extractTrace(logs);
    const stepTwo = trace.find((r) => r.objective === true && String(r.label).includes("Step two"));
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace, llmLogs: [], shots: { "a1.png": "data:img,A1" } });
    const out = renderViewer(payloadOf(html), { step: Number(stepTwo!.i) });
    const shot = out.match(/id="shot" src="([^"]*)"/);
    expect(shot).not.toBeNull();
    expect(shot![1]).toBe("data:img,A1"); // not an empty pane on the missing gone.png
  });
});

describe("buildRunReportHtml (single run)", () => {
  const trace = core.extractTrace(sampleLogs);
  const llm = (core as any).extractLlmLogs(sampleLogs);
  const html = core.buildRunReportHtml({
    meta: { title: "My run", status: "passed", platform: "android", originalYaml: "- step: tap login\n", recordingYaml: "- prompts:\n  - tap login\n" },
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

  test("carries the original and recorded YAML so the report can compare them", () => {
    const p = payloadOf(html);
    expect(p.sessions[0].originalYaml).toContain("step: tap login");
    expect(p.sessions[0].recordingYaml).toContain("tap login");
  });

  test("the serialized viewer script is syntactically valid and ships its helper dependencies", () => {
    // Compile (not run) the embedded script to catch brace/scope regressions from the refactor.
    const script = viewerScriptOf(html);
    expect(script).toContain("function RUN_REPORT_VIEWER");
    expect(script).toContain("function yamlRootSection"); // Config dependency ships with the viewer
    expect(() => new Function(script)).not.toThrow();
    // Every module-level helper the serialized viewer calls must be embedded alongside it — the
    // viewer cannot close over module scope once exported (a leak compiles fine but throws
    // ReferenceError on first render, leaving the whole report inert).
    const viewerBody = script.slice(script.indexOf("function RUN_REPORT_VIEWER"));
    for (const name of Object.keys(core)) {
      if (typeof (core as any)[name] !== "function" || name === "RUN_REPORT_VIEWER") continue;
      if (new RegExp(`\\b${name}\\(`).test(viewerBody)) expect(script).toContain(`function ${name}(`);
    }
  });

  test("the exported script is self-contained: renders and copies the local run prompt without module scope", () => {
    // Execute the exact script a browser runs on the exported file, via new Function so module
    // scope is genuinely absent, then drive a full detail render + the copy-prompt action.
    const selfHtml = core.buildRunReportHtml({
      meta: { title: "My run", status: "passed", trailId: "sample/checkout", cmd: "trailblaze run trails/checkout.trail.yaml" },
      trace,
      llmLogs: llm,
      shots: {},
    });
    const script = viewerScriptOf(selfHtml);
    const state = renderViewerState(payloadOf(selfHtml), { viewer: () => new Function(script)(), copyLocalPrompt: true });
    expect(state.html).toContain('id="copylocalprompt"');
    expect(state.copiedText).toContain("`trailblaze run trails/checkout.trail.yaml`");
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

  test("uses the report title for a multi-run document", () => {
    expect(html).toContain("<title>Trailblaze Report</title>");
  });

  test("starts from the system color scheme and persists an explicit theme", () => {
    expect(html).toContain("prefers-color-scheme: light");
    expect(html).toContain("trailblaze-report-theme");
    expect(html).not.toContain('<html lang="en" data-theme="dark">');
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
    expect(out).toContain("idxsummary");
    expect(out).toContain("<h1>Trailblaze Report</h1>");
    expect(out).toContain('data-theme-toggle aria-label="Use light mode"');
    expect(out).toContain("<strong>1</strong> passed");
    expect(out).not.toContain('class="stat retried"');
    expect(out).toContain("<strong>0</strong> self-healed");
    expect(out).toContain("<strong>1</strong> failed");
    expect(out).toContain("<strong>1</strong> other");
    expect(out.indexOf('class="idxsummary"')).toBeGreaterThan(out.indexOf('<footer class="indexfooter">'));
    expect(out).not.toContain("Exported from Trailblaze");
  });

  test("multi-session index offers metadata search without a redundant result counter", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        { ...session("Checkout flow", "passed"), meta: { title: "Checkout flow", status: "passed", platform: "android", device: "Pixel Demo" } },
        { ...session("Sign-in flow", "failed"), meta: { title: "Sign-in flow", status: "failed", platform: "ios", device: "iPhone Demo" } },
      ],
    });
    expect(out).toContain('type="search"');
    expect(out).toContain('aria-label="Search runs"');
    expect(out).not.toContain('id="runcount"');
    expect(out).toContain('data-search="checkout flow passed android pixel demo"');
    expect(out).toContain("No runs match these filters.");
    expect(out).toContain('aria-label="Sort runs"');
    expect(out).not.toContain("<span>Sort</span>");
    expect(out).toContain('role="option" aria-selected="true" data-run-sort="grouped">Status groups</button>');
    expect(out).toContain('data-run-filter="self-healed">Self-healed</button>');
    expect(out).toContain('data-index-section="failed"');
    expect(out).toContain('data-index-section="passed"');
    expect(out.indexOf('data-session="1"')).toBeLessThan(out.indexOf('data-session="0"'));
  });

  test("multi-session index separates self-healed runs from clean passes", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        session("Clean", "passed"),
        { ...session("Recovered", "passed"), meta: { title: "Recovered", status: "passed", selfHeal: true } },
        session("Broken", "failed"),
      ],
    });
    expect(out).toContain('<div class="idxsectionhead failed">Failed <span class="idxsectioncount">1</span>');
    expect(out).toContain('<div class="idxsectionhead selfheal">Self-healed <span class="idxsectioncount">1</span>');
    expect(out).toContain('<div class="idxsectionhead passed">Passed <span class="idxsectioncount">1</span>');
    expect(out).toContain('<span class="idxstatus" aria-label="self-healed" title="self-healed"><span class="idxstatusdot selfheal" aria-hidden="true"></span></span>');
    expect(out).toContain('<strong>1</strong> self-healed');
    expect(out).toContain('aria-pressed="false" data-run-filter="self-healed"');
    expect(out.indexOf('data-session="2"')).toBeLessThan(out.indexOf('data-session="1"'));
    expect(out.indexOf('data-session="1"')).toBeLessThan(out.indexOf('data-session="0"'));
  });

  test("multi-session index keeps retry history under the final outcome and prioritizes it", () => {
    const retry = (status: string, ranAt: string, duration: string) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", trailId: "checkout", status, platform: "ios", device: "simulator", ranAt, duration, steps: 4 },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        session("Profile", "passed"),
        retry("failed", "2026-07-17 10:00:00", "20s"),
        retry("passed", "2026-07-17 10:01:00", "18s"),
      ],
    });

    expect(out).not.toContain('data-index-section="retried"');
    expect(out).toContain('<div class="idxsectionhead passed">Passed <span class="idxsectioncount">2</span>');
    expect(out).toContain('class="idxretrydots" role="img" aria-label="Attempt history: failed, passed"');
    expect(out).toContain('<span class="idxstatusdot failed" aria-hidden="true" title="Attempt 1: failed"></span>');
    expect(out).toContain('<span class="idxstatusdot passed" aria-hidden="true" title="Attempt 2: passed"></span>');
    expect(out).toContain('class="idxattemptrow" data-session="1"');
    expect(out).toContain('class="idxattemptrow" data-session="2"');
    expect(out).toContain('Attempt 1</span><span class="idxattemptstatus failed">failed</span>');
    expect(out).toContain('Attempt 2</span><span class="idxattemptstatus passed">passed</span>');
    expect(out).not.toContain('class="stat retried"');
    expect(out).toContain('<strong>2</strong> passed');
    expect(out.indexOf('<div class="nm">Checkout</div>')).toBeLessThan(out.indexOf('<div class="nm">Profile</div>'));
    expect(out.match(/<div class="nm">Checkout<\/div>/g)).toHaveLength(1);
  });

  test("same-title legacy sessions remain independent without an explicit trail identity", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [session("Checkout", "failed"), session("Checkout", "passed")],
    });

    expect(out.match(/<div class="nm">Checkout<\/div>/g)).toHaveLength(2);
    expect(out).not.toContain('class="idxretrydots"');
    expect(out).not.toContain('class="stat retried"');
    expect(out).toContain('<strong>1</strong> failed');
    expect(out).toContain('<strong>1</strong> passed');
  });

  test("retry history is chronological and the latest attempt determines the section", () => {
    const retry = (status: string, ranAt: string) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", trailId: "checkout", status, ranAt },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [retry("passed", "2026-07-17T10:01:00Z"), retry("failed", "2026-07-17T10:00:00Z")],
    });

    expect(out).toContain('aria-label="Attempt history: failed, passed"');
    expect(out).toContain('class="idxattemptrow" data-session="1"');
    expect(out).toContain('class="idxattemptrow" data-session="0"');
    expect(out.indexOf('data-session="1"')).toBeLessThan(out.indexOf('data-session="0"'));
    expect(out).toContain('<strong>0</strong> failed');
    expect(out).toContain('<strong>1</strong> passed');
  });

  test("retry history preserves session order when any attempt lacks a timestamp", () => {
    const retry = (status: string, ranAt?: string) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", trailId: "checkout", status, ...(ranAt ? { ranAt } : {}) },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [retry("failed", "2026-07-17T10:00:00Z"), retry("passed")],
    });

    expect(out).toContain('aria-label="Attempt history: failed, passed"');
    expect(out).toContain('<strong>0</strong> failed');
    expect(out).toContain('<strong>1</strong> passed');
  });

  test("multi-session index groups shared context and keeps row facts aligned", () => {
    const shared = {
      platform: "android", deviceType: "phone", device: "Pixel Demo", appVersion: "1.2.3 (456)", appId: "com.example.app",
      ranAt: "2026-07-16 16:58:26",
      buildNumber: "10792", buildUrl: "https://ci.example/builds/10792",
      commitSha: "0123456789abcdef", commitUrl: "https://github.com/example/app/commit/0123456789abcdef",
    };
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        { ...session("Checkout flow", "passed"), llm: [{ inputTokens: 100, outputTokens: 20, totalCost: 0.001 }], meta: {
          title: "Checkout flow", status: "passed", ...shared, duration: "42.3s", steps: 12,
        } },
        { ...session("Sign-in flow", "failed"), llm: [{ inputTokens: 180, outputTokens: 30, totalCost: 0.0025 }], meta: { title: "Sign-in flow", status: "failed", ...shared, duration: "51.8s", steps: 9 } },
      ],
    });
    expect(out).not.toContain(">Device type<");
    expect(out).not.toContain(">Device<");
    expect(out).toContain("Bundle / package ID");
    expect(out).toContain("Build 10792 ↗");
    expect(out).toContain("01234567 ↗");
    expect(out).not.toContain('class="quietlink mono"');
    expect(out).not.toContain('<div class="k">Date</div>');
    expect(out).toContain('<span class="detailfooteritem indexrundate"><span class="k">Run on</span><span class="v">2026-07-16</span></span>');
    expect(out.indexOf(">Target<")).toBeLessThan(out.indexOf(">App version<"));
    expect(out.indexOf(">App version<")).toBeLessThan(out.indexOf(">Platform<"));
    expect(out.match(/>Platform</g)).toHaveLength(1); // shared context is rendered once in the header
    expect(out.match(/class="idxfact"><div class="k">Steps/g)).toHaveLength(2);
    expect(out).toContain("42.3s");
    expect(out).toContain("51.8s");
    expect(out).toContain('<span class="k">Total duration</span><span class="v">1m 34s</span>');
    expect(out).toContain('<span class="k">Total tokens</span><span class="v">330</span>');
    expect(out).toContain('<span class="k">Total LLM cost</span><span class="v">$0.003500</span>');
    expect(out.match(/class="idxstatus"/g)).toHaveLength(2);
    expect(out).not.toContain('data-export-run');
    expect(out).toContain('<button class="btn headeraction" type="button" id="exportall">Share</button>');
    expect(out).toContain('<footer class="indexfooter">');
    expect(out).not.toContain("Exported from Trailblaze");
    expect(out.match(/class="indexshell/g)).toHaveLength(3);
    expect(core.RUN_REPORT_CSS).toContain(".indexshell { width: 100%; max-width: var(--content-wide); margin-inline: auto; }");
    expect(out.indexOf('id="exportall"')).toBeLessThan(out.indexOf('id="runsearch"'));
  });

  test("invalid LLM usage is omitted instead of rendering NaN", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        ...session("Checkout", "passed"),
        meta: { title: "Checkout", status: "passed", duration: "1s" },
        llm: [{ inputTokens: "unknown", outputTokens: 20, totalCost: "unknown" }],
      }, {
        ...session("Profile", "passed"),
        meta: { title: "Profile", status: "passed", duration: "1s" },
      }],
    });

    expect(out).not.toContain("NaN");
    expect(out).toContain('<span class="k">Total tokens</span><span class="v">—</span>');
    expect(out).toContain('<span class="k">Total LLM cost</span><span class="v">—</span>');
  });

  test("nullable LLM usage is unavailable rather than zero", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        ...session("Checkout", "passed"),
        meta: { title: "Checkout", status: "passed", duration: "1s" },
        llm: [{ inputTokens: null, outputTokens: null, totalCost: null }],
      }, {
        ...session("Profile", "passed"),
        meta: { title: "Profile", status: "passed", duration: "1s" },
      }],
    });

    expect(out).toContain('<span class="k">Total tokens</span><span class="v">—</span>');
    expect(out).toContain('<span class="k">Total LLM cost</span><span class="v">—</span>');
  });

  test("multi-session index does not mislabel the export date as a shared run date", () => {
    const out = renderViewer({
      generatedAt: "2026-07-18 10:00:00",
      sessions: [
        { ...session("Earlier", "passed"), meta: { title: "Earlier", status: "passed", ranAt: "2026-07-16 10:00:00" } },
        { ...session("Later", "passed"), meta: { title: "Later", status: "passed", ranAt: "2026-07-17 10:00:00" } },
      ],
    });
    expect(out).not.toContain('<div class="k">Date</div>');
    expect(out).not.toContain('class="detailfooteritem indexrundate"');
  });

  test("run navigation uses directional page motion and a matching back arrow", () => {
    const payload = {
      generatedAt: "now",
      sessions: [session("Checkout flow", "passed"), session("Sign-in flow", "failed")],
    };
    const out = renderViewer(payload, { session: 1 });
    expect(out).toContain('class="backarrow" aria-hidden="true">←</span>');
    expect(core.RUN_REPORT_CSS).toContain("@keyframes reportPageForward");
    expect(core.RUN_REPORT_CSS).toContain("@keyframes reportPageBack");
    expect(core.RUN_REPORT_CSS).toContain("prefers-reduced-motion: reduce");
  });

  test("query routes share the selected run, tab, and step without dropping signed URL params", () => {
    const payload = {
      generatedAt: "now",
      sessions: [session("Checkout flow", "passed"), session("Sign-in flow", "failed")],
    };
    const selected = slim[1].i;
    const next = slim[0].i;
    const query = `?jwt=signed-token&run=1&tab=timeline&step=${selected}`;

    const direct = renderViewerState(payload, { query });
    expect(direct.html).toContain("Sign-in flow");
    expect(direct.html).toContain(`class="step sel child" data-step="${selected}"`);

    const moved = renderViewerState(payload, { query, transport: "prev" });
    const movedUrl = new URL(moved.route, "https://report.example");
    expect(movedUrl.searchParams.get("jwt")).toBe("signed-token");
    expect(movedUrl.searchParams.get("run")).toBe("1");
    expect(movedUrl.searchParams.get("tab")).toBe("timeline");
    expect(movedUrl.searchParams.get("step")).toBe(String(next));

    const tabbed = renderViewerState(payload, { query, tab: "info" });
    const tabbedUrl = new URL(tabbed.route, "https://report.example");
    expect(tabbedUrl.searchParams.get("jwt")).toBe("signed-token");
    expect(tabbedUrl.searchParams.get("run")).toBe("1");
    expect(tabbedUrl.searchParams.get("tab")).toBe("info");
    expect(tabbedUrl.searchParams.has("step")).toBe(false);
  });

  test("legacy hash routes canonicalize to query parameters", () => {
    const payload = {
      generatedAt: "now",
      sessions: [session("Checkout flow", "passed"), session("Sign-in flow", "failed")],
    };
    const selected = slim[1].i;
    const state = renderViewerState(payload, {
      query: "?jwt=signed-token",
      legacyHash: `#run=1&tab=timeline&step=${selected}`,
    });
    const canonical = new URL(state.route, "https://report.example");
    expect(canonical.searchParams.get("jwt")).toBe("signed-token");
    expect(canonical.searchParams.get("run")).toBe("1");
    expect(canonical.searchParams.get("tab")).toBe("timeline");
    expect(canonical.searchParams.get("step")).toBe(String(selected));
    expect(canonical.hash).toBe("");
  });

  test("legacy grid links open and canonicalize to the Lightbox tab", () => {
    const payload = {
      generatedAt: "now",
      sessions: [{
        ...session("Checkout flow", "passed"),
        trace: [{ i: 1, label: "Checkout ready", objective: true, ok: true, screenshotFile: "ready.png" }],
        shots: { "ready.png": "data:image/png;base64,READY" },
      }],
    };
    const state = renderViewerState(payload, { query: "?run=0&tab=grid" });
    const canonical = new URL(state.route, "https://report.example");
    expect(state.html).toContain(">Lightbox<");
    expect(state.html).toContain("galcell");
    expect(canonical.searchParams.get("tab")).toBe("lightbox");
  });

  test("the timeline preview offers a Play control to scrub screenshots", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).toContain('id="tlplay"');
    expect(out).toContain('aria-label="Play timeline"');
    expect(out).toContain('aria-label="Previous frame"');
    expect(out).toContain('aria-label="Next frame"');
    expect(out).not.toContain("Current frame");
    expect(out).not.toContain("Step 1 /");
    expect(out).toContain('class="deviceplayer');
    expect(out).not.toContain('<div class="detail">');
    expect(out).not.toContain('class="count mono"');
    expect(core.RUN_REPORT_CSS).toContain(".pvctl { display: grid; grid-template-columns: repeat(3,minmax(0,1fr));");
    expect(core.RUN_REPORT_CSS).toContain(".pvctl button.btn { width: 100%;");
    expect(core.RUN_REPORT_CSS).toContain("border: 2px solid var(--player-line)");
    expect(core.RUN_REPORT_CSS).toContain("margin: 0; border-top: 2px solid var(--player-line)");
    expect(core.RUN_REPORT_CSS).toContain("border-left: 2px solid var(--player-line)");
    expect(core.RUN_REPORT_CSS).toContain(".pvctl button.btn:not(:disabled):hover { border-left-color: var(--player-line); }");
    expect(out).toContain('id="prev" aria-label="Previous frame"');
    expect(out).toContain('id="next" aria-label="Next frame"');
    expect(out).toContain('class="transporticon direction" aria-hidden="true"></span>');
    expect(out).toContain('<svg class="transporticon playicon"');
    expect(core.RUN_REPORT_CSS).toContain(".transporticon { width: 24px; height: 24px;");
    expect(core.RUN_REPORT_CSS).toContain("border-bottom: 4px solid currentColor; border-left: 4px solid currentColor;");
    expect(out).toContain('aria-label="Play timeline"');
    expect(out).not.toContain('aria-hidden="true">←</span>');
    expect(out).not.toContain('aria-hidden="true">→</span>');
    expect(out).toContain('<nav aria-label="Report views">');
    expect(out).not.toContain('role="tablist"');
    expect(out).toContain('role="button" tabindex="0"');
  });

  test("Space on a focused timeline row selects it without also starting playback", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const payload = payloadOf(html);
    const step = payload.sessions[0].trace[1].i;
    const out = renderViewer(payload, { spaceOnStep: step });
    expect(out).toContain(`class="step sel child" data-step="${step}"`);
    expect(out).toContain('aria-label="Play timeline"');
    expect(out).not.toContain("⏸ Pause");
  });

  test("arrow keys on interactive controls do not change the selected timeline row", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const data = payloadOf(html);
    const first = data.sessions[0].trace[0].i;
    const out = renderViewerState(data, { step: first, timelineKey: "ArrowRight", timelineKeyTarget: "BUTTON" });

    expect(out.route).toContain(`step=${first}`);
  });

  test("the timeline shows per-step elapsed time and duration on the Trail Runner scrubber", () => {
    // sampleLogs: objective at T+0s, tool (100ms) at T+1s — the row carries both the run-clock
    // offset and its own duration, and the preview gets the shared time-scaled vertical scrubber.
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).toContain("+1.0s");
    expect(out).toContain("100ms");
    expect(out).toContain('class="scrubtrack"');
    expect(out).toContain("scrubtick");
    expect(out).not.toContain("tldot");
  });

  test("a run with a trailhead renders it as its own labelled card above the numbered steps", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the demo checkout", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Review and submit the order" }, timestamp: "2024-01-01T00:00:01Z" },
    ];
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(logs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    // Trailhead section: dedicated semantic section, chip reads TRAILHEAD (no number).
    expect(out).toContain("Deterministic setup · step 0");
    expect(out).toContain('class="tlphase trailhead" aria-labelledby="trailhead-heading"');
    expect(out).toContain(">TRAILHEAD</span>");
    // Trail section: labelled with the test-step count, and numbering starts at STEP 1.
    expect(out).toContain('class="tlphase" aria-labelledby="trail-heading"');
    expect(out).toContain("1 test step");
    expect(out).toContain(">STEP 1</span>");
    expect(out).not.toContain(">STEP 2</span>");
  });

  test("a high-volume Trailhead yields visual priority to the authored Trail", () => {
    const trace = [
      { i: 1, label: "Prepare the app", objective: true, trailhead: true, ok: true, ts: 1, ms: 0 },
      ...Array.from({ length: 20 }, (_, i) => ({ i: i + 2, label: `setup action ${i + 1}`, objective: false, trailhead: false, ok: i % 4 !== 0, ts: i + 2, ms: 100 })),
      { i: 22, label: "Complete checkout", objective: true, trailhead: false, ok: true, ts: 22, ms: 0 },
      { i: 23, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 23, ms: 100 },
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain('data-phase="trailhead" aria-expanded="false"');
    expect(out).toContain('class="tlphasebody" hidden');
    expect(out).toContain('data-phase="trail" aria-expanded="true"');
    expect(out).toContain('class="grphdr sel" data-group="22" aria-expanded="true"');
    expect(out).toContain('aria-current="step"');
    expect(out).toContain('class="scrubline setup"');
    expect(out).toContain('class="scrubline trail"');
    expect(out).toContain('title="Trail begins"');
    expect(out).toContain('Dotted segment is Trailhead setup; solid segment is the authored Trail.');
    expect(out).toContain('aria-valuetext="Trail, item 22 of 23: Complete checkout"');
    expect(out).not.toContain('<button type="button" class="scrubtick"');
    expect(out).not.toContain('class="scrubfill"');
  });

  test("selecting a low timeline row preserves the list scroll position", () => {
    const trace = [
      { i: 1, label: "Start", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
      ...Array.from({ length: 24 }, (_, i) => ({ i: i + 2, label: `action ${i + 1}`, objective: false, trailhead: false, ok: true, ts: i + 2, ms: 100 })),
    ];
    const result = renderViewerState({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] }, { step: 25, timelineScrollTop: 640, focusedStep: 25 });
    expect(result.timelineScrollTop).toBe(640);
    expect(result.restoredFocus).toBe('[data-step="25"]');
    expect(result.html).toContain('class="step sel child" data-step="25"');
  });

  test("a setup-only run keeps its high-volume Trailhead visible", () => {
    const trace = [
      { i: 1, label: "Prepare the app", objective: true, trailhead: true, ok: true, ts: 1, ms: 0 },
      ...Array.from({ length: 20 }, (_, i) => ({ i: i + 2, label: `setup action ${i + 1}`, objective: false, trailhead: false, ok: true, ts: i + 2, ms: 100 })),
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain('data-phase="trailhead" aria-expanded="true"');
    expect(out).not.toContain('class="tlphasebody" hidden');
    expect(out).not.toContain('id="trail-heading"');
    expect(out).toContain('class="scrubline setup" style="height:100%"');
    expect(out).not.toContain('class="scrubline trail"');
    expect(out).not.toContain('class="scrubphasebreak"');
    expect(out).toContain('aria-label="Timeline for Trailhead setup. The dotted rail marks deterministic setup."');
  });

  test("a run without a trailhead keeps the single unlabelled steps card", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).not.toContain("Deterministic setup");
    expect(out).not.toContain("thcard");
    expect(out).toContain(">STEP 1</span>");
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
    expect(out).not.toContain("Run failure");
    expect(out).not.toContain('class="stepgroup failed"');
  });

  test("a self-heal run shows the self-heal marker badge next to its status", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "Healed", status: "passed", selfHeal: true }, trace: slim, llm: [], shots: {}, recordingYaml: null }],
    });
    expect(out).toContain("badge selfheal");
    expect(out).toContain(">self-healed</span>");
    expect(core.RUN_REPORT_CSS).toContain(".badge.selfheal { background: rgba(242,184,75,.16); color: var(--amber); }");
  });

  test("a self-healed objective leads the timeline and receives the yellow recovery treatment", () => {
    const trace = (core as any).slimTraceForShare(core.extractTrace([
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch the app", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.ObjectiveCompleteLog`, promptStep: { step: "Launch the app", isTrailhead: true }, objectiveResult: { class: "xyz.AgentTaskStatus.Success.ObjectiveComplete" }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Submit the order" }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "recorded-failure", trailblazeTool: { raw: { text: "Place order" } }, successful: false, errorMessage: "Recorded selector no longer matched", timestamp: "2024-01-01T00:00:03Z" },
      { class: `${T}.SelfHealInvokedLog`, promptStep: { step: "Submit the order" }, recordingResult: { failedTool: { name: "assertVisibleBySelector" }, failureResult: { class: "xyz.TrailblazeToolResult.Error.ExceptionThrown", errorMessage: "Recorded selector no longer matched" } }, timestamp: "2024-01-01T00:00:04Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "healed-action", trailblazeTool: { raw: { text: "Place order" } }, successful: true, timestamp: "2024-01-01T00:00:05Z" },
      { class: `${T}.ObjectiveCompleteLog`, promptStep: { step: "Submit the order" }, objectiveResult: { class: "xyz.AgentTaskStatus.Success.ObjectiveComplete" }, timestamp: "2024-01-01T00:00:06Z" },
    ]));
    const healed = trace.find((row: any) => row.objective && row.selfHeal);
    const flaky = trace.find((row: any) => row.selfHealSource);
    const recovered = trace.find((row: any) => row.label === "tapOnElementBySelector");
    expect(healed).toMatchObject({ label: "Submit the order", selfHealTool: "assertVisibleBySelector" });
    expect(flaky).toMatchObject({ label: "assertVisibleBySelector", selfHealSource: true });
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Recovered", status: "passed", selfHeal: true }, trace, llm: [], shots: {} }] });
    expect(out).toContain("Step 1 self-healed");
    expect(out).toContain("Trailblaze used AI to recover this step.");
    expect(out).toContain("Recorded selector no longer matched");
    expect(out.indexOf('class="selfhealpanel"')).toBeLessThan(out.indexOf('id="trailhead-heading"'));
    expect(out).toContain('class="stepgroup selfhealed"');
    expect(out).toContain(`data-group="${healed.i}"`);
    expect(out).toContain(`class="step child selfheal" data-step="${flaky.i}"`);
    expect(out).not.toContain(`class="step child selfheal" data-step="${recovered.i}"`);
    expect(out).toContain("background:var(--amber)");
    expect(core.RUN_REPORT_CSS).toContain(".stepgroup.selfhealed { background: var(--warning-surface); }");
    expect(core.RUN_REPORT_CSS).toContain(".stepgroup.selfhealed .step { background-color: var(--bg2); }");
  });

  test("a run that captured the target app's version shows it in the detail footer", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed", appId: "com.example.pos", appVersion: "5.58.0.0 (67500009)" }, trace: slim, llm: [], shots: {}, recordingYaml: null }],
    });
    expect(out).toContain("App version");
    expect(out).toContain("5.58.0.0 (67500009)");
    // A run without app info renders no empty App rows.
    const bare = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null }],
    });
    expect(bare).not.toContain("App version");
  });

  test("detail tabs share one page heading and content frame", () => {
    const payload = {
      generatedAt: "now",
      sessions: [{
        meta: { title: "R", status: "passed", target: "demo" }, trace: slim, llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "network observer", style: "json", total: 1, truncated: false, events: [{ t: 1, d: "{}" }] }],
      }],
    };
    const info = renderViewer(payload, { tab: "info" });
    const events = renderViewer(payload, { tab: "events" });
    expect(info).toContain('<section class="viewpage">');
    expect(info).toContain('<div class="viewhead"><h2 class="viewtitle">Run details</h2>');
    expect(info).toContain('<div class="rows"><div class="r">');
    expect(events).toContain('<section class="viewpage eventview">');
    expect(events).toContain('<div class="viewhead"><h2 class="viewtitle">Events</h2>');
    expect(core.RUN_REPORT_CSS).toContain(".viewhead { display: flex;");
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
    expect(out).not.toContain('class="d mono"');
    expect(out).not.toContain('class="n mono"');
    expect(out).not.toContain('<span class="mono" style="color:var(--sub);font-size:11.5px">m</span>');
  });

  test("a single run opens straight on its detail with a YAML comparison tab", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "Solo", status: "passed", target: "demo", appVersion: "1.2.3", platform: "android", deviceType: "phone", device: "Pixel Demo", appId: "com.example.demo", duration: "1m 25s", steps: 6, ranAt: "2026-07-17 07:30:00" }, trace: slim, llm: [], shots: {}, originalYaml: "- step: launch", recordingYaml: "- prompts: []" }],
    });
    expect(out).toContain("Solo");
    expect(out).toContain(">YAML<");
    expect(out).toContain('class="steps"');
    expect(out).toContain('data-export-menu');
    expect(out).toContain('aria-label="Run and export options"');
    expect(out).not.toContain('aria-haspopup="menu"');
    expect(out).not.toContain('role="menuitem"');
    expect(out).toContain('id="exportrun">Export report</button>');
    expect(out).toContain('id="copylocalprompt" disabled>Copy local run prompt</button>');
    expect(out).toContain('id="exportscreenshots" disabled');
    expect(out).toContain('id="exportlogs" disabled');
    expect(out).toContain('<main class="timelinemain">');
    expect(out).toContain('<footer class="detailfooter">');
    expect(out).toContain('<header class="detailheader">');
    expect(out).toContain('<div class="detailactions"><button class="themetoggle"');
    expect(out).toContain('<details class="exportmenu"');
    expect(out).toContain('<span class="exportdots" aria-hidden="true"><span class="exportdot"></span><span class="exportdot"></span><span class="exportdot"></span></span>');
    expect(core.RUN_REPORT_CSS).toContain('.exportdot { width: 5px; height: 5px;');
    expect(out).not.toContain('class="headerfact"');
    expect(out).toContain('<div class="detailfootermeta"><span class="detailfooteritem"><span class="k">Target</span><span class="v">demo</span></span>');
    expect(out).toContain('<span class="k">Run on</span><span class="v">2026-07-17 07:30:00</span>');
    expect(out).toContain('<span class="k">Total duration</span><span class="v">1m 25s</span>');
    expect(out).toContain('<span class="k">Tokens used</span><span class="v">0</span>');
    expect(out).toContain('<span class="k">LLM cost</span><span class="v">$0.000000</span>');
    expect(out.indexOf('>Run on<')).toBeGreaterThan(out.indexOf('>Total duration<'));
    expect(out).not.toContain('<span class="k">Exported</span>');
    expect(out).not.toContain('<div class="meta">');
    expect(out.indexOf('id="exportrun"')).toBeLessThan(out.indexOf('<nav aria-label="Report views">'));
    expect(out.indexOf('>Total duration<')).toBeGreaterThan(out.indexOf('</main>'));
    expect(out.indexOf('>Target<')).toBeGreaterThan(out.indexOf('</main>'));
    expect(out.indexOf(">Target<")).toBeLessThan(out.indexOf(">App version<"));
    expect(out.indexOf(">App version<")).toBeLessThan(out.indexOf(">Platform<"));
    expect(core.RUN_REPORT_CSS).toContain('.detailfooteritem { display: grid; gap: 1px;');
    expect(core.RUN_REPORT_CSS).toContain('.indexfooter, .detailfooter { min-height: 59px;');
    expect(core.RUN_REPORT_CSS).toContain('.detailfooteritem .k { color: var(--sub); font-size: var(--type-micro);');
    expect(core.RUN_REPORT_CSS).toContain('.detailfooteritem .v { color: var(--sub2); font-size: var(--type-caption);');
    expect(core.RUN_REPORT_CSS).toContain('.detailtitle { min-height: 32px; max-width: none; display: grid; grid-template-columns: auto minmax(0,1fr) auto;');
    expect(core.RUN_REPORT_CSS).toContain('.detailedge { width: 32px; height: 32px;');
  });

  test("the export menu enables screenshot and log downloads only when that data exists", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Captured", status: "passed", steps: 1 },
        trace: [{ ...slim[0], screenshotFile: "frame.png" }],
        llm: [],
        shots: { "frame.png": "data:image/png;base64,AAAA" },
        deviceLog: "I/Trailblaze: ready",
      }],
    });
    expect(out).toContain('id="exportscreenshots"><span>Export screenshots</span><span class="count">1</span>');
    expect(out).toContain('id="exportlogs">Export logs</button>');
    expect(out).not.toContain('id="exportscreenshots" disabled');
    expect(out).not.toContain('id="exportlogs" disabled');
  });

  test("the detail menu offers a local-run agent prompt when the trail command is known", () => {
    const payload = {
      generatedAt: "now",
      sessions: [{
        meta: { title: "Checkout", status: "failed", trailId: "sample/checkout", cmd: "./trailblaze run trails/checkout.trail.yaml" },
        trace: slim,
        llm: [],
        shots: {},
      }],
    };
    const out = renderViewer(payload);
    expect(out).toContain('id="copylocalprompt">Copy local run prompt</button>');
    expect(out).not.toContain('id="copylocalprompt" disabled');
    const copied = renderViewerState(payload, { copyLocalPrompt: true }).copiedText;
    expect(copied).toContain("`./trailblaze run trails/checkout.trail.yaml`");
    expect(copied).toContain("`./trailblaze app`");
  });

  test("the timeline separates trailhead setup from numbered trail steps", () => {
    const trace = (core as any).slimTraceForShare(core.extractTrace([
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the demo app", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "launchApp", traceId: "setup", trailblazeTool: { raw: {} }, successful: true, timestamp: "2024-01-01T00:00:00.500Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Complete checkout" }, timestamp: "2024-01-01T00:00:01Z" },
    ]));
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain("Deterministic setup · step 0");
    expect(out).toContain(">TRAILHEAD<");
    expect(out).toContain(">STEP 1<");
    expect(out.indexOf('id="trailhead-heading"')).toBeLessThan(out.indexOf('id="trail-heading"'));
  });

  test("a failed run opens on its failure and presents a parsed error above the timeline", () => {
    const failure = [
      "com.example.checkout.FeesDisclosureException: Fees disclosure did not appear before checkout",
      "    at com.example.checkout.FeesVerifier.requireDisclosure(FeesVerifier.kt:42)",
      "    at com.example.checkout.CheckoutTrail.run(CheckoutTrail.kt:118)",
      "Caused by: java.util.concurrent.TimeoutException: selector timed out after 15000ms",
      "    at xyz.example.SelectorPoller.await(SelectorPoller.kt:76)",
    ].join("\n");
    const trace = [
      { i: 1, label: "Open checkout", tool: "agent step", note: null, ms: 0, ts: 1, ok: true, err: null, screenshotFile: null, objective: true, trailhead: true, count: null, mark: null, children: [] },
      { i: 2, label: "Review and submit the order", tool: "agent step", note: null, ms: 0, ts: 2, ok: true, err: null, screenshotFile: null, objective: true, trailhead: false, count: null, mark: null, children: [] },
      { i: 3, label: "assertVisibleBySelector", tool: "text: Fees disclosure", note: null, ms: 15000, ts: 3, ok: false, err: failure, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [] },
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Failed", status: "failed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain("Step 1 failed");
    expect(out).toContain("Review and submit the order");
    expect(out).toContain("Failed tool call");
    expect(out).toContain("assertVisibleBySelector");
    expect(out).toContain("com.example.checkout.FeesDisclosureException");
    expect(out).toContain("Fees disclosure did not appear before checkout");
    expect(out).toContain("Stack trace");
    expect(out).toContain("FeesVerifier.kt:42");
    expect(out.indexOf('class="failurepanel"')).toBeLessThan(out.indexOf('id="trailhead-heading"'));
    expect(out).toContain('class="stepgroup failed"');
    expect(out).toContain('class="step sel child" data-step="3"');
    expect(out.match(/Fees disclosure did not appear before checkout/g)).toHaveLength(1);
    expect(core.RUN_REPORT_CSS).toContain(".stepgroup.failed { background: var(--danger-surface); }");
    expect(core.RUN_REPORT_CSS).not.toContain(".stepgroup.failed::after");
  });

  test("the Config tab compares only the authored and recorded config blocks", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "R", status: "passed" }, trace: slim, llm: [], shots: {},
        originalYaml: "config:\n  retries: 2\ntrailhead:\n  step: Authored setup\ntrail:\n  - step: Authored test",
        recordingYaml: "config:\n  retries: 3\ntrailhead:\n  step: Recorded setup\ntrail:\n  - step: Recorded test",
      }],
    }, { tab: "config" });
    expect(out).toContain("Original config · authored inputs");
    expect(out).toContain("Recorded config · run snapshot");
    expect(out).toContain("retries: 2");
    expect(out).toContain("retries: 3");
    expect(out).not.toContain("Authored setup");
    expect(out).not.toContain("Recorded test");
  });

  test("captured event streams are visible in the timeline and its scrubber", () => {
    const result = renderViewerState({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Plugin events", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "network observer", style: "json", total: 1, truncated: false, events: [{ t: 1704067200500, d: '{"path":"/payments"}' }] }],
      }],
    }, { tlStream: 0, focusedTlStream: 0 });
    const out = result.html;
    expect(out).toContain("network observer");
    expect(out).not.toContain("captured events shown");
    expect(out).not.toContain("Add optional captured events to the timeline");
    expect(out).toContain("Event streams");
    expect(out).toContain('class="selection">1 of 1');
    expect(out.indexOf('data-streamselect open')).toBeLessThan(out.indexOf('id="trail-heading"'));
    expect(out).toContain('streamtype">network observer');
    expect(out).not.toContain('streamtype">Stream');
    expect(out).toContain('style="--stream-color:oklch(74% .14 70)" open');
    expect(out).toContain('<span class="streamdot"></span>');
    expect(out).toContain('{\n  &quot;path&quot;: &quot;/payments&quot;\n}');
    expect(out).not.toContain("data-navstep");
    expect(out).toContain('data-streamselect open');
    expect(out).toContain('type="checkbox" data-tlstream="0" checked');
    expect(out).not.toContain('class="streamtime mono"');
    expect(out).not.toContain('class="scrubclock mono"');
    expect(out).not.toContain('class="ts mono"');
    expect(out).not.toContain('class="streamcount mono"');
    expect(out).toContain('<pre class="mono">');
    expect(result.restoredFocus).toBe('[data-tlstream="0"]');
    expect(out).toContain("Select all");
    expect(out).toContain("Clear");
    expect(out).not.toContain('<div class="evchips">');
  });

  test("timeline stream controls sit above Trailhead and summarize selection", () => {
    const events = ["network", "lifecycle", "analytics", "eligibility"].map((name, i) => ({
      name, style: "json", total: 1, truncated: false, events: [{ t: 1704067200500 + i, d: "{}" }],
    }));
    const trace = [
      { i: 1, label: "Open app", tool: null, note: null, ms: 0, ts: 1, ok: true, err: null, screenshotFile: null, objective: true, trailhead: true, count: null, mark: null, children: [] },
      { i: 2, label: "Complete checkout", tool: null, note: null, ms: 0, ts: 2, ok: true, err: null, screenshotFile: null, objective: true, trailhead: false, count: null, mark: null, children: [] },
    ];
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "Streams", status: "passed" }, trace, llm: [], shots: {}, recordingYaml: null, events }],
    });
    const chooser = out.indexOf("Event streams");
    expect(chooser).toBeGreaterThan(-1);
    expect(out).toContain('class="selection">0 of 4');
    expect(chooser).toBeLessThan(out.indexOf('id="trailhead-heading"'));
    expect(out).toContain('class="streamselectoricon"');
    expect(out).toContain('class="streamoptiondot"');
    const html = core.buildMultiReportHtml({ generatedAt: "now", sessions: [{ meta: { title: "Streams", status: "passed" }, trace, llmLogs: [], shots: {}, events }] });
    expect(html).toContain(".tlphasehead { position: sticky;");
  });

  test("the scrubber centers its selected timeline row with reduced-motion support", () => {
    const viewer = core.RUN_REPORT_VIEWER.toString();
    expect(viewer).toContain("centerTimelineSelection");
    expect(viewer).toContain("scroller.scrollTo({ top, behavior:");
    expect(viewer).toContain("prefers-reduced-motion: reduce");
    expect(viewer.match(/centerTimelineSelection\(\)/g)?.length).toBeGreaterThanOrEqual(2);
  });

  test("the frame transport centers the corresponding timeline row", () => {
    const payload = { generatedAt: "now", sessions: [session("Transport", "passed")] };
    const state = renderViewerState(payload, { routeStep: slim[0].i, transport: "next" });
    expect(state.html).toContain(`data-step="${slim[1].i}"`);
    expect(state.html).toContain('class="step sel');
    expect(state.timelineScrollTop).toBe(320);
  });

  test("timeline arrow keys center the corresponding timeline row", () => {
    const payload = { generatedAt: "now", sessions: [session("Keyboard", "passed")] };
    const state = renderViewerState(payload, { routeStep: slim[0].i, timelineKey: "ArrowRight" });
    expect(state.html).toContain(`data-step="${slim[1].i}"`);
    expect(state.html).toContain('class="step sel');
    expect(state.timelineScrollTop).toBe(320);
  });

  test("the frame transport centers the timeline in the stacked layout's main scroller", () => {
    const payload = { generatedAt: "now", sessions: [session("Transport", "passed")] };
    const state = renderViewerState(payload, { routeStep: slim[0].i, transport: "next", stackedTimeline: true });
    expect(state.timelineScrollTop).toBe(0);
    expect(state.mainScrollTop).toBe(320);
  });

  test("the frame transport recenters after a stacked preview image changes the layout", () => {
    const payload = { generatedAt: "now", sessions: [{ ...session("Transport", "passed"), shots: { "a.png": "data:image/png;base64,AAAA" } }] };
    const state = renderViewerState(payload, { routeStep: slim[0].i, transport: "next", stackedTimeline: true, shotLayoutShift: true });
    expect(state.timelineScrollTop).toBe(0);
    expect(state.mainScrollTop).toBe(320);
  });

  test("scroll surfaces use quiet thumbs and transparent tracks", () => {
    const html = core.buildMultiReportHtml({ generatedAt: "now", sessions: [{ meta: { title: "Scrollbars", status: "passed" }, trace: slim, llmLogs: [], shots: {} }] });
    expect(html).toContain("scrollbar-color: rgba(144,152,164,.32) transparent");
    expect(html).toContain("*::-webkit-scrollbar-track { background: transparent; }");
    expect(html).toContain("*::-webkit-scrollbar-thumb { min-height: 36px;");
    expect(html).toContain("html, body { margin: 0; height: 100%; overflow: hidden; }");
    expect(html).toContain("height: 100dvh; min-height: 0; overflow: hidden; }");
  });

  test("timeline actions use distinct tap, verification, and failure icons", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Action icons", status: "failed" },
        trace: [
          { i: 1, label: "tapOnElementBySelector", ok: true },
          { i: 2, label: "assertVisibleBySelector", ok: true },
          { i: 3, label: "assertVisibleBySelector", ok: false },
        ],
        llm: [], shots: {}, recordingYaml: null,
      }],
    });
    expect(out).toContain('<span class="ic tap" aria-hidden="true">👆</span>');
    expect(out).toContain('<span class="ic verify" aria-hidden="true">✓</span>');
    expect(out).toContain('<span class="ic failure" aria-hidden="true">×</span>');
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

describe("secondary tabs (device logs, network, lightbox, video)", () => {
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

  test("nav exposes Lightbox, Video, Device logs and Network tabs when their data is present", () => {
    const out = renderViewer(payload);
    for (const tab of ["Lightbox", "Video", "Device logs", "Network"]) expect(out).toContain(">" + tab + "<");
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

  test("lightbox tab renders a thumbnail cell per screenshot step", () => {
    const out = renderViewer(payload, { tab: "lightbox" });
    expect(out).toContain("galcell");
  });

  test("lightbox defaults to the last screenshot in each authored step and can show every frame", () => {
    const groupedPayload = {
      generatedAt: "now",
      sessions: [{
        meta: { title: "Run", status: "passed" },
        trace: [
          { i: 1, label: "Open checkout", objective: true, ok: true },
          { i: 2, label: "tapOnElement", screenshotFile: "first.png", ok: true },
          { i: 3, label: "waitForAnimation", screenshotFile: "last.png", ok: true },
          { i: 4, label: "Confirm order", objective: true, ok: true },
          { i: 5, label: "assertVisible", screenshotFile: "confirm.png", ok: true },
        ],
        llm: [],
        shots: {
          "first.png": "data:image/png;base64,FIRST",
          "last.png": "data:image/png;base64,LAST",
          "confirm.png": "data:image/png;base64,CONFIRM",
        },
        recordingYaml: null,
      }],
    };
    const summary = renderViewer(groupedPayload, { tab: "lightbox" });
    expect(summary).not.toContain('data-shot="first.png"');
    expect(summary).toContain('data-shot="last.png"');
    expect(summary).toContain('data-shot="confirm.png"');
    expect(summary).toContain('aria-checked="false"');
    expect(summary).toContain('<span class="galchip">STEP 1</span><span class="gallabel">Open checkout</span>');
    expect(summary).toContain('<span class="galtool">waitForAnimation</span>');
    expect(summary).toContain('<span class="galchip">STEP 2</span><span class="gallabel">Confirm order</span>');
    expect(summary.indexOf('id="lightboxmode"')).toBeLessThan(summary.indexOf('class="gal"'));
    expect(summary).toContain('class="viewpage lightboxpage"');
    expect(core.RUN_REPORT_CSS).toContain('grid-template-columns: repeat(auto-fit,minmax(min(190px,100%),1fr))');

    const expanded = renderViewer(groupedPayload, { tab: "lightbox", lightboxAll: true });
    expect(expanded).toContain('data-shot="first.png"');
    expect(expanded).toContain('aria-checked="true"');

    expect(renderViewerState(groupedPayload, { tab: "lightbox", zoomShot: "last.png", zoomKey: "ArrowRight" }).zoomSrc)
      .toBe("data:image/png;base64,CONFIRM");
    expect(renderViewerState(groupedPayload, { tab: "lightbox", zoomShot: "confirm.png", zoomKey: "ArrowLeft" }).zoomSrc)
      .toBe("data:image/png;base64,LAST");
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

  test("events tab lists each producer in a single-select dropdown with its total count", () => {
    const out = renderViewer(payload, { tab: "events" });
    expect(out).toContain("com.example.plugin.network");
    expect(out).toContain("com.example.plugin.analytics");
    expect(out).toContain('data-evstream="0"');
    expect(out).toContain('data-evstream="1"');
    expect(out).toContain('data-evstreamselect');
    expect(out).toContain('role="listbox" aria-label="Event stream"');
    expect(out).toContain('role="option" aria-selected="true" data-evstream="0"');
    expect(out).not.toContain('<div class="evchips">');
    expect(out).toContain("2 streams");
  });

  test("the default stream renders each event at its offset from the stream's first event", () => {
    const out = renderViewer(payload, { tab: "events" });
    expect(out).toContain("+0.00s");
    expect(out).toContain("+0.50s");
    expect(out).toContain("+1.00s");
    expect(out).toContain("api.test/foo");
    expect(out).toContain("eventcard");
    expect(out).toContain("Raw JSON");
  });

  test("events tab parses escaped JSON and extracts a readable label", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Escaped", status: "passed" },
        trace: [], llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "com.example.plugin.analytics", style: "json", total: 1, truncated: false, events: [
          { t: 1000, d: '{\\"columnItems\\":{\\"Event\\":\\"BlockerFlow Interact CompleteFlow\\",\\"Raw Message\\":\\"{\\\\\\"event_name\\\\\\":\\\\\\"NestedAction\\\\\\",\\\\\\"action_text\\\\\\":\\\\\\"Done\\\\\\"}\\"}}' },
        ] }],
      }],
    }, { tab: "events" });
    expect(out).toContain("BlockerFlow Interact CompleteFlow");
    expect(out).toContain("NestedAction");
    expect(out).toContain("Done");
    expect(out).not.toContain("\\\\&quot;");
  });

  test("event label priority is semantic rather than object insertion order", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Labels", status: "passed" },
        trace: [], llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "analytics", style: "json", total: 1, truncated: false, events: [
          { t: 1000, d: JSON.stringify({ message: "Secondary detail", event: "Checkout completed" }) },
        ] }],
      }],
    }, { tab: "events" });

    expect(out).toContain('<span class="eventlabel">Checkout completed</span>');
  });

  test("timeline fallback labels do not leak into the Events view cache", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Fallback", status: "passed" },
        trace: [{ i: 1, t: 1000, label: "Start", objective: true, ok: true }], llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "analytics producer", style: "json", total: 1, truncated: false, events: [
          { t: 1000, d: JSON.stringify({ status: 201 }) },
        ] }],
      }],
    }, { tlStreamBeforeTab: 0, tab: "events" });

    expect(out).toContain('<span class="eventlabel">analytics producer</span>');
  });

  test("raw event JSON preserves fields beyond the summary scan budget", () => {
    const large = Object.fromEntries(Array.from({ length: 100 }, (_, i) => [`field${i}`, `value${i}`]));
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Large payload", status: "passed" },
        trace: [], llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "analytics", style: "json", total: 1, truncated: false, events: [
          { t: 1000, d: JSON.stringify(large) },
        ] }],
      }],
    }, { tab: "events" });

    expect(out).toContain('&quot;field99&quot;: &quot;value99&quot;');
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
