// Behavior tests for the headless-reusable report core (run-report-core.ts). These pin the
// observable contract a headless generator (or the in-app Share button) depends on: the derived
// trace shape, and the self-contained HTML's embedded payload (single run, multi-run index, and
// the recording-YAML tab). We deliberately don't drive the DOM viewer here — instead we parse the
// embedded __TB_RUN_DATA__ payload (the data contract) and compile the embedded viewer bundle to catch syntax
// regressions in the refactor, without coupling to render internals.
//
// Run: `bun test app/run-report-core.test.ts` from the web/ directory.
import { describe, expect, test } from "bun:test";

// Tests exercise the TypeScript SOURCE directly (bun strips types in memory); the packaged
// run-report-core.js artifact is exercised end-to-end by RunReportGeneratorTest's bun-subprocess
// test, which loads it from the JAR classpath. Loaded via ESM import (not require): the module
// graph embeds the prebuilt viewer script through a bun macro, and bun 1.3.14's sync CJS loader
// spins forever on a require()'d graph that combines a macro import with sibling imports.
import * as RUN_REPORT_CORE_MODULE from "./run-report-core";

const core = RUN_REPORT_CORE_MODULE as unknown as {
  originalYamlFromLogs: (logs: unknown[]) => string | null;
  yamlRootSection: (yaml: string | null, key: string) => string | null;
  localRunAgentPrompt: (meta: Record<string, unknown> | null) => string | null;
  extractTrace: (logs: unknown[]) => Array<Record<string, unknown>>;
  buildRunReportHtml: (a: unknown) => string;
  buildMultiReportHtml: (a: unknown) => string;
  RUN_REPORT_VIEWER: () => void;
  // Pure playback-timing helpers (exported via RUN_REPORT_EXPORTS alongside the builders above).
  playbackGapMs: (gap: number) => number;
  videoFrameAt: (v: unknown, clockMs: number) => number;
  videoEndMs: (v: unknown) => number;
  spriteFrameCss: (v: unknown, logical: number) => { sheet: number; size: string; position: string };
  buildPlaybackSchedule: (rows: Array<{ ts?: number | null; ms?: number | null }>, video: unknown) => { mode: string; clock0: number | null; offsets: number[]; totalMs: number; video: unknown; haveTs: boolean; lo: number; hi: number };
  playbackPositionAt: (schedule: unknown, playMs: number) => { stepIndex: number; clockMs: number | null; frame: number | null; done: boolean };
  videoLoopFrame: (base: number, total: number, fps: number, elapsedMs: number) => number;
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
// Handed to `opts.drive` to drive timeline playback against a controllable fake rAF clock:
// play/pause via the real tlplay handler, advance the clock, and read the paint-in-place
// observables (tracked step elements, scrubber ARIA, the preview <img>) plus the full-render count.
type PlaybackDriveContext = {
  play: () => void;
  advance: (ms: number) => void;
  renders: () => number;
  html: () => string;
  selectedSteps: () => string[];
  scrubAttr: (name: string) => string | undefined;
  shotImg: { src: string; alt: string };
  prevBtn: { disabled: boolean };
  nextBtn: { disabled: boolean };
  clickShot: () => void;
};

type ViewerOptions = { session?: number; step?: number; routeStep?: number; query?: string; legacyHash?: string; protocol?: string; copyLink?: boolean; clipboardRejects?: boolean; tab?: string; toggleCell?: string; lightboxAll?: boolean; galZoom?: number[]; zoomShot?: string; zoomKey?: "ArrowLeft" | "ArrowRight"; timelineKey?: "ArrowLeft" | "ArrowRight" | "ArrowUp" | "ArrowDown"; timelineKeyTarget?: string; tlStream?: number; tlStreamBeforeTab?: number; spaceOnStep?: number; timelineScrollTop?: number; focusedStep?: number; focusedTlStream?: number; transport?: "prev" | "next"; stackedTimeline?: boolean; shotLayoutShift?: boolean; copyLocalPrompt?: boolean; exportLogs?: boolean; pointerDown?: "outside" | "insideTimelineMenu"; viewer?: () => void; drive?: (ctx: PlaybackDriveContext) => void; payloadViaGlobal?: boolean; sprites?: Record<string, string>; deferBoot?: boolean };

function renderViewerState(payload: unknown, opts: ViewerOptions = {}): { html: string; htmlBeforeBoot: string; liveHtml: () => string; readHtml: () => string; timelineScrollTop: number; mainScrollTop: number; restoredFocus: string | null; route: string; zoomSrc: string | null; zoomRoot: any; copiedText: string | null; copyBtnText: () => string; timelineMenuOpen: boolean; spriteMeasures: Array<{ src: string; fireLoad: (naturalWidth: number) => void }>; tlvframeStyle: Record<string, string> } {
  const handlers: { session: Record<string, () => void>; tab: Record<string, () => void>; step: Record<string, () => void>; stepKey: Record<string, (e: any) => void>; shot: Record<string, () => void>; tlStream: Record<string, () => void>; cellToggle: Record<string, (e: any) => void>; galZoom: Record<string, () => void>; documentKey?: (e: any) => void; timelinePlay?: () => void; gridMode?: () => void; prev?: () => void; next?: () => void; shotLoad?: () => void; copyLocalPrompt?: () => void; copyLink?: () => void; exportLogs?: () => void } = { session: {}, tab: {}, step: {}, stepKey: {}, shot: {}, tlStream: {}, cellToggle: {}, galZoom: {} };
  let shotLoaded = !opts.shotLayoutShift;
  const mainScroller: any = { scrollTop: 0, clientHeight: 400, get scrollHeight() { return opts.shotLayoutShift && !shotLoaded ? 800 : 1200; }, parentElement: null, getBoundingClientRect: () => ({ top: 0 }), scrollTo({ top }: { top: number }) { this.scrollTop = top; } };
  const timelineList: any = { scrollTop: 0, clientHeight: 400, scrollHeight: opts.stackedTimeline ? 400 : 1200, parentElement: opts.stackedTimeline ? mainScroller : null, getBoundingClientRect: () => ({ top: 0 }), scrollTo({ top }: { top: number }) { this.scrollTop = top; } };
  let restoredFocus: string | null = null;
  // A <details class="streamselect"> stand-in: setting .open fires ontoggle (DOM semantics), and
  // .inside is a node that contains() recognizes, for simulating a tap inside the open menu.
  const detailsMenu = () => {
    const el: any = {
      _open: false,
      contains(n: unknown) { return n === el || n === el.inside; },
      addEventListener() {},
      set onkeydown(_fn: unknown) {},
      set ontoggle(fn: () => void) { el._ontoggle = fn; },
      get open() { return el._open; },
      set open(v: boolean) { if (v === el._open) return; el._open = v; if (el._ontoggle) el._ontoggle(); },
    };
    el.inside = { parentMenu: el };
    return el;
  };
  const timelineMenu = detailsMenu();
  // Full-render counter + persistent per-step / scrub / shot stand-ins: playback paints these in
  // place between renders, so the drive tests read them as the observable playback state.
  let renders = 0;
  const stepEls = new Map<string, any>();
  const stepEl = (id: string) => {
    if (!stepEls.has(id)) {
      stepEls.set(id, {
        classes: new Set<string>(),
        attrs: {} as Record<string, string>,
        classList: { add: (c: string) => stepEls.get(id).classes.add(c), remove: (c: string) => stepEls.get(id).classes.delete(c) },
        setAttribute(name: string, value: string) { this.attrs[name] = value; },
        removeAttribute(name: string) { delete this.attrs[name]; },
        focus: () => { restoredFocus = `[data-step="${id}"]`; },
        getBoundingClientRect: () => ({ top: (shotLoaded ? 500 : 300) - (opts.stackedTimeline ? mainScroller.scrollTop : timelineList.scrollTop), height: 40 }),
      });
    }
    return stepEls.get(id);
  };
  const scrubEl: any = { attrs: {} as Record<string, string>, setAttribute(name: string, value: string) { this.attrs[name] = value; } };
  const shotWrap: any = { querySelectorAll: () => [], insertAdjacentHTML() {} };
  const shotImg: any = { src: "", alt: "" };
  // Persistent transport stand-ins so drive tests observe the in-place `.disabled` paints between
  // full renders. `prev` starts disabled, mirroring the full render parked on the first row.
  const prevBtn: any = { disabled: true, set onclick(fn: () => void) { handlers.prev = fn; } };
  const nextBtn: any = { disabled: false, set onclick(fn: () => void) { handlers.next = fn; } };
  const app: any = {
    _h: "",
    set innerHTML(v: string) { this._h = v; timelineList.scrollTop = 0; renders++; },
    get innerHTML() { return this._h; },
    querySelectorAll(sel: string) {
      if (sel === "[data-session]") return [...this._h.matchAll(/data-session="(\d+)"/g)].map((m: any) => ({ dataset: { session: m[1] }, set onclick(fn: () => void) { handlers.session[m[1]] = fn; } }));
      if (sel === "[data-tab]") return [...this._h.matchAll(/data-tab="([a-z]+)"/g)].map((m: any) => ({ dataset: { tab: m[1] }, set onclick(fn: () => void) { handlers.tab[m[1]] = fn; } }));
      if (sel === "[data-step]") return [...this._h.matchAll(/data-step="(\d+)"/g)].map((m: any) => ({ dataset: { step: m[1] }, set onclick(fn: () => void) { handlers.step[m[1]] = fn; } }));
      if (sel === "[data-tlstream]") return [...this._h.matchAll(/data-tlstream="(\d+)"/g)].map((m: any) => ({ dataset: { tlstream: m[1] }, set onclick(fn: () => void) { handlers.tlStream[m[1]] = fn; } }));
      if (sel === "[data-shot]") return [...this._h.matchAll(/data-shot="([^"]+)"(?: data-shot-token="([^"]*)")?(?: data-shot-label="([^"]*)")?(?: data-shot-tool="([^"]*)")?/g)].map((m: any) => ({ dataset: { shot: m[1], shotToken: m[2], shotLabel: m[3], shotTool: m[4] }, set onclick(fn: () => void) { handlers.shot[m[1]] = fn; } }));
      if (sel === "[data-cell-toggle]") return [...this._h.matchAll(/data-cell-toggle="([^"]+)"/g)].map((m: any) => ({ dataset: { cellToggle: m[1] }, set onclick(fn: (e: any) => void) { handlers.cellToggle[m[1]] = fn; }, set onkeydown(_fn: unknown) {} }));
      if (sel === "[data-gal-zoom]") return [...this._h.matchAll(/data-gal-zoom="(-?\d+)"/g)].map((m: any) => ({ dataset: { galZoom: m[1] }, set onclick(fn: () => void) { handlers.galZoom[m[1]] = fn; } }));
      if (sel === '[role="button"][tabindex="0"]') return [...this._h.matchAll(/<div[^>]*data-step="(\d+)"[^>]*role="button" tabindex="0"/g)].map((m: any) => ({
        dataset: { step: m[1] },
        click: () => handlers.step[m[1]] && handlers.step[m[1]](),
        set onkeydown(fn: (e: any) => void) { handlers.stepKey[m[1]] = fn; },
      }));
      if (sel === ".step.sel, .grphdr.sel") return [...stepEls.values()].filter((el) => el.classes.has("sel"));
      return [];
    },
    querySelector(sel: string) {
      if (sel === ".timeline-list" && this._h.includes('class="timeline-list"')) return timelineList;
      if (sel === ".preview .shot" && this._h.includes('class="shot')) return { complete: shotLoaded, addEventListener: (_name: string, fn: () => void) => { handlers.shotLoad = fn; } };
      if (sel === ".preview .shotwrap" && this._h.includes('class="shotwrap"')) return shotWrap;
      if (sel === "[data-scrub]" && this._h.includes("data-scrub")) return scrubEl;
      const step = sel.match(/^\[data-step="(\d+)"\]$/);
      if (step && this._h.includes(`data-step="${step[1]}"`)) return stepEl(step[1]);
      const tlStream = sel.match(/^\[data-tlstream="(\d+)"\]$/);
      if (tlStream && this._h.includes(`data-tlstream="${tlStream[1]}"`)) return { focus: () => { restoredFocus = sel; } };
      // Each render produces fresh <details> markup; refresh the shim's open state from the html
      // without firing ontoggle, mimicking a newly-created element.
      if (sel === "[data-streamselect]" && this._h.includes("data-streamselect")) { timelineMenu._open = this._h.includes("data-streamselect open"); return timelineMenu; }
      return null;
    },
  };
  (globalThis as Record<string, unknown>).window = globalThis;
  // The shipped read path: the payload rides in the inert #tb-run-data JSON script and the viewer
  // JSON.parses its textContent (same `<` escaping as buildMultiReportHtml). payloadViaGlobal
  // exercises the window.__TB_RUN_DATA__ fallback for embedders that inject the payload directly.
  delete (globalThis as Record<string, unknown>).__TB_RUN_DATA__;
  if (opts.payloadViaGlobal) (globalThis as Record<string, unknown>).__TB_RUN_DATA__ = payload;
  const dataJson = JSON.stringify(payload).replace(/</g, "\\u003c");
  const tlvframeNode: any = { style: {} };
  // The viewer's decode-measurement fallback (measureSpriteAspect) constructs `new Image()`;
  // capture each instance so tests can drive onload with a fake natural size.
  const spriteMeasures: Array<{ src: string; fireLoad: (naturalWidth: number) => void }> = [];
  (globalThis as Record<string, unknown>).Image = function (this: any) {
    const img = this;
    img.onload = null;
    img.naturalWidth = 0;
    Object.defineProperty(img, "src", {
      set(value: string) { img._src = value; spriteMeasures.push({ src: value, fireLoad: (naturalWidth: number) => { img.naturalWidth = naturalWidth; if (img.onload) img.onload(); } }); },
      get() { return img._src; },
    });
  };
  const rafQueue: Array<() => void> = [];
  if (opts.deferBoot) (globalThis as Record<string, unknown>).requestAnimationFrame = (cb: () => void) => rafQueue.push(cb);
  else delete (globalThis as Record<string, unknown>).requestAnimationFrame;
  // The static loader element: removable like a real node - after .remove() the document no
  // longer finds it, so the boot gate behaves exactly as it would against live DOM.
  let bootNode: { remove(): void } | null = opts.deferBoot ? { remove() { bootNode = null; } } : null;
  const routeQuery = opts.query ?? (opts.routeStep == null ? "" : `?run=0&tab=timeline&step=${opts.routeStep}`);
  const testLocation = { pathname: "/report.html", search: routeQuery, hash: opts.legacyHash || "", protocol: opts.protocol || "" };
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
  let zoomRoot: any = null;
  let copiedText: string | null = null;
  const copyBtn: any = { textContent: "", set onclick(fn: () => void) { handlers.copyLink = fn; } };
  const createElement = (tag: string) => {
    const node: any = {
      children: [], style: {}, className: "", textContent: "", disabled: false,
      appendChild(child: any) { this.children.push(child); },
      setAttribute() {}, insertAdjacentHTML() {}, remove() {}, focus() {}, click() {},
      set src(value: string) { this._src = value; if (tag === "img") zoomSrc = value; },
      get src() { return this._src; },
    };
    return node;
  };
  (globalThis as Record<string, unknown>).document = {
    activeElement,
    getElementById: (id: string) => id === "app" ? app
      : id === "tb-run-data" && !opts.payloadViaGlobal ? { textContent: dataJson }
      : id === "tb-sprites" && opts.sprites ? { textContent: JSON.stringify(opts.sprites).replace(/</g, "\\u003c") }
      : id === "tb-boot" ? bootNode
      : id === "tlvframe" && app._h.includes('id="tlvframe"') ? tlvframeNode
      : id === "tlplay" ? { click: () => handlers.timelinePlay && handlers.timelinePlay(), set onclick(fn: () => void) { handlers.timelinePlay = fn; } }
      : id === "shot" && app._h.includes('id="shot"') ? shotImg
      : id === "lightboxmode" && app._h.includes('id="lightboxmode"') ? { set onclick(fn: () => void) { handlers.gridMode = fn; } }
      : id === "prev" ? prevBtn
      : id === "next" ? nextBtn
      : id === "copylocalprompt" && app._h.includes('id="copylocalprompt"') ? { textContent: "", set onclick(fn: () => void) { handlers.copyLocalPrompt = fn; } }
      : (id === "copylink" || id === "copylinkrun") && app._h.includes(`id="${id}"`) ? copyBtn
      : id === "exportlogs" && app._h.includes('id="exportlogs"') ? { set onclick(fn: () => void) { handlers.exportLogs = fn; } }
      : null,
    addEventListener: (name: string, fn: (e: any) => void) => { if (name === "keydown") handlers.documentKey = fn; },
    createElement,
    body: { appendChild(el: any) { zoomRoot = el; } },
  };
  (globalThis as Record<string, unknown>).navigator = { clipboard: { writeText(text: string) { if (opts.clipboardRejects) return Promise.reject(new Error("denied")); copiedText = text; } } };
  (globalThis as Record<string, unknown>).getComputedStyle = (el: unknown) => ({ overflowY: el === mainScroller || (el === timelineList && !opts.stackedTimeline) ? "auto" : "visible" });
  // Controllable rAF clock for playback drive tests: requestAnimationFrame queues callbacks and
  // advance(ms) moves the fake performance.now and flushes one frame — so the test, not wall time,
  // decides when the engine ticks and what dt it sees. Installed only for `drive` runs and
  // restored afterwards so every other test keeps bun's rAF-less environment.
  const realPerformance = (globalThis as Record<string, unknown>).performance;
  let fakeNow = 0;
  let nextFrameHandle = 1;
  const pendingFrames = new Map<number, (t: number) => void>();
  const advance = (ms: number) => {
    fakeNow += ms;
    const frames = [...pendingFrames.values()];
    pendingFrames.clear();
    frames.forEach((frame) => frame(fakeNow));
  };
  if (opts.drive) {
    (globalThis as Record<string, unknown>).performance = { now: () => fakeNow };
    (globalThis as Record<string, unknown>).requestAnimationFrame = (fn: (t: number) => void) => { const handle = nextFrameHandle++; pendingFrames.set(handle, fn); return handle; };
    (globalThis as Record<string, unknown>).cancelAnimationFrame = (handle: number) => { pendingFrames.delete(handle); };
  }
  // Capture the boot race's setTimeout arm (deferBoot only) so the harness can fire the losing
  // arm deterministically at the end of the run; the real setTimeout is restored right after the
  // initial (deferred) viewer call so the booted app's own timers behave normally.
  const bootTimeouts: Array<() => void> = [];
  const realSetTimeout = globalThis.setTimeout;
  if (opts.deferBoot) (globalThis as Record<string, unknown>).setTimeout = (cb: () => void) => { bootTimeouts.push(cb); return 0; };
  (opts.viewer || core.RUN_REPORT_VIEWER)();
  (globalThis as Record<string, unknown>).setTimeout = realSetTimeout;
  // With deferBoot the viewer must have painted nothing yet — the static loader owns the first
  // frame; the boot work runs from the queued rAF callbacks.
  const htmlBeforeBoot = app._h;
  while (rafQueue.length) rafQueue.shift()!();
  if (opts.toggleCell && handlers.cellToggle[opts.toggleCell]) handlers.cellToggle[opts.toggleCell]({ stopPropagation() {} });
  if (opts.session != null && handlers.session[String(opts.session)]) handlers.session[String(opts.session)]();
  if (opts.timelineScrollTop != null) timelineList.scrollTop = opts.timelineScrollTop;
  if (opts.step != null && handlers.step[String(opts.step)]) handlers.step[String(opts.step)]();
  if (opts.tlStreamBeforeTab != null && handlers.tlStream[String(opts.tlStreamBeforeTab)]) handlers.tlStream[String(opts.tlStreamBeforeTab)]();
  if (opts.tab && handlers.tab[opts.tab]) handlers.tab[opts.tab]();
  if (opts.lightboxAll && handlers.gridMode) handlers.gridMode();
  if (opts.galZoom) for (const delta of opts.galZoom) { const fn = handlers.galZoom[String(delta)]; if (fn) fn(); }
  if (opts.zoomShot && handlers.shot[opts.zoomShot]) handlers.shot[opts.zoomShot]();
  if (opts.zoomKey && handlers.documentKey) handlers.documentKey({ key: opts.zoomKey, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } });
  if (opts.timelineKey && handlers.documentKey) handlers.documentKey({
    key: opts.timelineKey,
    target: opts.timelineKeyTarget ? { tagName: opts.timelineKeyTarget, isContentEditable: false } : undefined,
    defaultPrevented: false,
    preventDefault() { this.defaultPrevented = true; },
  });
  if (opts.tlStream != null && handlers.tlStream[String(opts.tlStream)]) handlers.tlStream[String(opts.tlStream)]();
  if (opts.transport && handlers[opts.transport]) handlers[opts.transport]!();
  if (opts.copyLocalPrompt && handlers.copyLocalPrompt) handlers.copyLocalPrompt();
  if (opts.copyLink && handlers.copyLink) handlers.copyLink();
  if (opts.exportLogs && handlers.exportLogs) handlers.exportLogs();
  if (opts.shotLayoutShift && handlers.shotLoad) { shotLoaded = true; handlers.shotLoad(); }
  if (opts.spaceOnStep != null && handlers.stepKey[String(opts.spaceOnStep)]) {
    const event = { key: " ", defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    handlers.stepKey[String(opts.spaceOnStep)](event);
    if (handlers.documentKey) handlers.documentKey(event);
  }
  if (opts.pointerDown) {
    const onpointerdown = ((globalThis as Record<string, unknown>).document as { onpointerdown?: (e: unknown) => void }).onpointerdown;
    if (onpointerdown) onpointerdown({ target: opts.pointerDown === "insideTimelineMenu" ? timelineMenu.inside : {} });
  }
  if (opts.drive) {
    try {
      opts.drive({
        play: () => handlers.timelinePlay && handlers.timelinePlay(),
        advance,
        renders: () => renders,
        html: () => app._h,
        selectedSteps: () => [...stepEls.entries()].filter(([, el]) => el.classes.has("sel")).map(([id]) => id),
        scrubAttr: (name: string) => scrubEl.attrs[name],
        shotImg,
        prevBtn,
        nextBtn,
        clickShot: () => shotImg.onclick && shotImg.onclick(),
      });
    } finally {
      (globalThis as Record<string, unknown>).performance = realPerformance;
      delete (globalThis as Record<string, unknown>).requestAnimationFrame;
      delete (globalThis as Record<string, unknown>).cancelAnimationFrame;
    }
  }
  // Fire the losing arm of the boot race last: the rAF arm already booted, so the bootStarted
  // guard must make this a no-op - a second boot would re-render #app, observably resetting the
  // interaction state built up above (the idempotence test pins that).
  bootTimeouts.forEach((cb) => cb());
  // readHtml re-reads the rendered html after the synchronous pass — for asserting on renders
  // triggered by async work (e.g. the lazy gz inflation re-render).
  return { html: app._h, htmlBeforeBoot, liveHtml: () => app._h as string, readHtml: () => app._h as string, timelineScrollTop: timelineList.scrollTop, mainScrollTop: mainScroller.scrollTop, restoredFocus, route, zoomSrc, zoomRoot, copiedText, copyBtnText: () => copyBtn.textContent as string, timelineMenuOpen: timelineMenu.open, spriteMeasures, tlvframeStyle: tlvframeNode.style };
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
  const m = html.match(/<script type="application\/json" id="tb-run-data">([\s\S]*?)<\/script>/);
  if (!m) throw new Error("no tb-run-data block in report HTML");
  return JSON.parse(m[1]);
}

// The hoisted sprite chunk (session index → sprite data URI) the viewer resolves lazily.
function spritesOf(html: string): Record<string, string> {
  const m = html.match(/<script type="application\/json" id="tb-sprites">([\s\S]*?)<\/script>/);
  if (!m) throw new Error("no tb-sprites block in report HTML");
  return JSON.parse(m[1]);
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
    // The payload is an inert JSON script the viewer JSON.parses, never a JS literal the parser
    // must evaluate before first paint.
    expect(html).toContain('<script type="application/json" id="tb-run-data">');
    expect(html).not.toContain("window.__TB_RUN_DATA__ =");
    expect(html).toContain("function RUN_REPORT_VIEWER");
    expect(html).toContain("My run"); // title in <title>
  });

  test("paints a static loader before the data script, themed and titled", () => {
    const boot = html.indexOf('id="tb-boot"');
    expect(boot).toBeGreaterThan(-1);
    expect(boot).toBeLessThan(html.indexOf('id="tb-run-data"'));
    // Loader carries the run title and is styled from the head CSS (present before it parses).
    expect(html.slice(boot, boot + 300)).toContain("My run");
    expect((core as any).RUN_REPORT_CSS).toContain("#tb-boot");
    expect((core as any).RUN_REPORT_CSS).toContain(".tb-boot-spinner");
  });

  test("with rAF available, boot yields first (the loader owns the first frame) and renders after", () => {
    const state = renderViewerState(payloadOf(html), { deferBoot: true });
    expect(state.htmlBeforeBoot).toBe(""); // nothing rendered synchronously — the static loader is on screen
    expect(state.html).toContain("My run"); // the queued double-rAF boot then rendered the report
  });

  test("the raced boot arms are idempotent: the losing timeout arm never boots a second time", () => {
    // The harness fires the captured 300ms-timeout arm last, after the rAF arm booted and after
    // the timeline scroll was set; a second boot would re-render #app, observably resetting the
    // timeline scroll to 0.
    const state = renderViewerState(payloadOf(html), { deferBoot: true, timelineScrollTop: 120 });
    expect(state.html).toContain("My run");
    expect(state.timelineScrollTop).toBe(120);
  });

  test("falls back to window.__TB_RUN_DATA__ when no data script is present (in-app embedders)", () => {
    const out = renderViewer(payloadOf(html), { payloadViaGlobal: true });
    expect(out).toContain("My run");
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

  test("the embedded viewer script is syntactically valid and ships its helper dependencies", () => {
    // Compile (not run) the embedded script to catch brace/scope regressions from the refactor.
    const script = viewerScriptOf(html);
    expect(script).toContain("function RUN_REPORT_VIEWER");
    expect(script).toContain("function yamlRootSection"); // Config dependency ships with the viewer
    expect(() => new Function(script)).not.toThrow();
    // Every module-level helper the embedded viewer calls must ship inside its bundle — a missing
    // declaration compiles fine but throws ReferenceError on first render, leaving the whole
    // report inert. The bundler includes imports by construction; this pins the contract anyway.
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

describe("sprite hoist + frame aspect", () => {
  const trace = core.extractTrace(sampleLogs);
  const video = { sprites: [{ uri: "data:image/webp;base64,SPRITEBYTES", rows: 2 }], fps: 2, frames: 2, columns: 1, rows: 2, frameHeight: 40, frameMap: [0, 1], startFrame: 0, endFrame: 1, startMs: 1704067200000 };
  const html = core.buildMultiReportHtml({
    generatedAt: "now",
    sessions: [
      { meta: { title: "No video", status: "passed" }, trace, llmLogs: [], shots: {} },
      { meta: { title: "With video", status: "passed" }, trace, llmLogs: [], shots: {}, video },
    ],
  });

  test("the boot payload carries no sprite bytes; they ride in the #tb-sprites chunk", () => {
    const dataScript = html.match(/<script type="application\/json" id="tb-run-data">([\s\S]*?)<\/script>/)![1];
    expect(dataScript).not.toContain("SPRITEBYTES");
    const p = payloadOf(html);
    expect(p.sessions[1].video.sprites).toEqual([{ uri: "", rows: 2 }]);
    expect(spritesOf(html)).toEqual({ "1": ["data:image/webp;base64,SPRITEBYTES"] });
  });

  test("the viewer resolves the hoisted sprite lazily when the session's frames render", () => {
    const out = renderViewer(payloadOf(html), { sprites: spritesOf(html), session: 1 });
    expect(out).toContain("background-image:url('data:image/webp;base64,SPRITEBYTES')");
  });

  test("a recorded frameWidth sizes the frame box without decoding the sprite", () => {
    const withWidth = {
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "passed" }, trace: (core as any).slimTraceForShare(trace), llm: [], shots: {}, video: { ...video, frameWidth: 20 } }],
    };
    const state = renderViewerState(withWidth);
    expect(state.html).toContain("aspect-ratio:20 / 40");
    expect(state.spriteMeasures).toHaveLength(0); // no Image decode was needed
  });

  test("without frameWidth, the sprite is measured after first paint and patched in place — no re-render", () => {
    const legacy = {
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "passed" }, trace: (core as any).slimTraceForShare(trace), llm: [], shots: {}, video }],
    };
    const state = renderViewerState(legacy);
    expect(state.html).not.toContain("aspect-ratio:");
    expect(state.spriteMeasures).toHaveLength(1);
    expect(state.spriteMeasures[0].src).toBe(video.sprites[0].uri);
    state.spriteMeasures[0].fireLoad(20); // 20px-wide sheet, 1 column → 20 / 40 per frame
    expect(state.tlvframeStyle.aspectRatio).toBe("20 / 40");
    // A second boot render would have inlined the aspect into fresh markup; the live document
    // still carries the original render with the patch applied to the frame box directly.
    expect(state.liveHtml()).not.toContain("aspect-ratio:");
  });
});

describe("rekeySprites (export re-keying)", () => {
  const rekey = (core as any).rekeySprites as (exported: any[], all: any[], spriteFor: (v: any, i: number) => string[]) => Record<string, string[]>;
  // spriteFor mirrors the viewer's spriteUrls contract: inline video.sprites URIs win, otherwise
  // the hoisted chunk is consulted by original session index.
  const spriteForStore = (store: Record<string, string[]>) => (v: any, i: number) => (v && v.sprites && v.sprites.some((sp: any) => sp.uri)) ? v.sprites.map((sp: any) => sp.uri) : store[String(i)] || [];
  const all = [
    { video: null },
    { video: { sprites: [{ uri: "", rows: 2 }] } },
    { video: { sprites: [{ uri: "", rows: 2 }] } },
  ];
  const store = { "1": ["data:image/webp;base64,S1"], "2": ["data:image/webp;base64,S2"] };

  test("exporting one session out of a multi-session report shifts its sprite key to the new index", () => {
    expect(rekey([all[2]], all, spriteForStore(store))).toEqual({ "0": ["data:image/webp;base64,S2"] });
  });

  test("sessions without a video (or without a sprite) contribute no key", () => {
    expect(rekey([all[0], all[2]], all, spriteForStore(store))).toEqual({ "1": ["data:image/webp;base64,S2"] });
    expect(rekey([all[0]], all, spriteForStore(store))).toEqual({});
  });

  test("an export of an export round-trips: re-keying the already re-keyed chunk is stable", () => {
    const firstExport = [all[1], all[2]];
    const firstChunk = rekey(firstExport, all, spriteForStore(store));
    expect(firstChunk).toEqual({ "0": ["data:image/webp;base64,S1"], "1": ["data:image/webp;base64,S2"] });
    // Inside the exported document, `firstExport` IS the full session list and `firstChunk` its
    // sprite store; exporting the second session again lands its sprite back at key 0.
    expect(rekey([firstExport[1]], firstExport, spriteForStore(firstChunk))).toEqual({ "0": ["data:image/webp;base64,S2"] });
    // Exporting everything from an export leaves the chunk unchanged.
    expect(rekey(firstExport, firstExport, spriteForStore(firstChunk))).toEqual(firstChunk);
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

  test("owner metadata renders as a row subtitle, joins search, and unlocks the Owner sort", () => {
    const owned = (title: string, status: string, owner?: string) => ({
      ...session(title, status),
      meta: { title, status, ...(owner ? { metadata: { owner } } : {}) },
    });
    const sessions = [owned("Zeta", "passed", "team-b"), owned("Alpha", "passed"), owned("Beta", "failed", "team-a")];
    const grouped = renderViewer({ generatedAt: "now", sessions });
    expect(grouped).toContain('<div class="idxowner">team-b</div>');
    expect(grouped).toContain('data-search="zeta passed team-b"');
    expect(grouped).toContain('data-run-sort="owner">Owner</button>');

    const byOwner = renderViewer({ generatedAt: "now", sessions }, { query: "?view=runs&sort=owner" });
    // Alphabetized owner sections with ownerless runs last; the subtitle is redundant inside its
    // own owner section, so it drops.
    expect(byOwner).toContain('<div class="idxsectionhead">team-a <span class="idxsectioncount">1</span>');
    expect(byOwner).toContain('<div class="idxsectionhead">team-b <span class="idxsectioncount">1</span>');
    expect(byOwner).toContain('<div class="idxsectionhead">No owner <span class="idxsectioncount">1</span>');
    expect(byOwner.indexOf('data-index-section="owner:team-a"')).toBeLessThan(byOwner.indexOf('data-index-section="owner:team-b"'));
    expect(byOwner.indexOf('data-index-section="owner:team-b"')).toBeLessThan(byOwner.indexOf('data-index-section="owner:"'));
    expect(byOwner).not.toContain('class="idxowner"');
  });

  test("hosted reports offer Copy link and copy the browser's deep-link URL", () => {
    const payload = { generatedAt: "now", sessions: [session("A", "passed"), session("B", "failed")] };
    // Index header button + run-menu item appear only when the report has a shareable address.
    const hostedIndex = renderViewer(payload, { protocol: "https:", query: "?view=runs&sort=name" });
    expect(hostedIndex).toContain('id="copylink"');
    const hostedDetail = renderViewer({ generatedAt: "now", sessions: [payload.sessions[0]] }, { protocol: "https:" });
    expect(hostedDetail).toContain('id="copylinkrun"');
    const local = renderViewer(payload);
    expect(local).not.toContain('id="copylink"');
    expect(renderViewer({ generatedAt: "now", sessions: [payload.sessions[0]] })).not.toContain('id="copylinkrun"');

    // Clicking copies the current (route-canonicalized) browser URL.
    const copied = renderViewerState(payload, { protocol: "https:", query: "?view=runs&sort=name", copyLink: true }).copiedText;
    expect(copied).toContain("view=runs");
    expect(copied).toContain("sort=name");
    const copiedRun = renderViewerState({ generatedAt: "now", sessions: [payload.sessions[0]] }, { protocol: "https:", copyLink: true }).copiedText;
    expect(copiedRun).toContain("run=0");
  });

  test("Copy link only claims Copied once the clipboard write settles", async () => {
    const payload = { generatedAt: "now", sessions: [session("A", "passed"), session("B", "failed")] };
    // writeText resolves → the button reads Copied.
    const ok = renderViewerState(payload, { protocol: "https:", query: "?view=runs", copyLink: true });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(ok.copyBtnText()).toBe("Copied");
    // writeText rejects (permission / insecure context) → no false Copied.
    const denied = renderViewerState(payload, { protocol: "https:", query: "?view=runs", copyLink: true, clipboardRejects: true });
    await new Promise((resolve) => setTimeout(resolve, 0));
    expect(denied.copyBtnText()).not.toBe("Copied");
    expect(denied.copiedText).toBeNull();
  });

  test("a generation-time shareUrl overrides the browser address for Copy link", () => {
    const payload = { generatedAt: "now", shareUrl: "https://ci.example/artifacts/report.html?jwt=abc", sessions: [session("A", "passed"), session("B", "failed")] };
    // The baked-in URL makes Copy link available even without an http(s) address (file://).
    expect(renderViewer(payload, { query: "?view=runs&sort=name" })).toContain('id="copylink"');
    // Copying grafts the current route onto the canonical URL, preserving its own params (jwt).
    const copied = renderViewerState(payload, { query: "?view=runs&sort=name", copyLink: true }).copiedText;
    expect(copied).toStartWith("https://ci.example/artifacts/report.html?");
    expect(copied).toContain("jwt=abc");
    expect(copied).toContain("view=runs");
    expect(copied).toContain("sort=name");
    // The route is serialized from viewer state, not read back off the address — a sandboxed
    // embed (no URL writes, empty location.search) still copies a deep link into the open run.
    const embedded = renderViewerState({ ...payload, sessions: [payload.sessions[0]] }, { copyLink: true }).copiedText;
    expect(embedded).toStartWith("https://ci.example/artifacts/report.html?");
    expect(embedded).toContain("run=0");
    // A non-http(s) shareUrl is refused (safeHref), leaving the file:// report link-less.
    expect(renderViewer({ ...payload, shareUrl: "javascript:alert(1)" })).not.toContain('id="copylink"');
  });

  test("a report with no owners offers no Owner sort option", () => {
    const out = renderViewer({ generatedAt: "now", sessions: [session("A", "passed"), session("B", "failed")] });
    expect(out).not.toContain('data-run-sort="owner"');
  });

  test("consumer metadata key/values render as Info tab rows", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ ...session("Checkout", "passed"), meta: { title: "Checkout", status: "passed", metadata: { owner: "team-a", accountToken: "AT_123" } } }],
    }, { tab: "info" });
    expect(out).toContain('<span class="k">owner</span><span class="v">team-a</span>');
    expect(out).toContain('<span class="k">accountToken</span><span class="v">AT_123</span>');
  });

  test("mixed platforms coalesce a trail into one row of per-platform cells", () => {
    const on = (title: string, status: string, platform: string, duration: string) => ({
      ...session(title, status),
      meta: { title, status, platform, duration, trailId: "login/login", target: "demo" },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [on("login/login", "passed", "android", "35.4s"), on("login/login", "failed", "ios", "44.4s")],
    });
    // One row for the trail; the header lists every platform once.
    expect(out.match(/<div class="nm">login\/login<\/div>/g)).toHaveLength(1);
    expect(out).toContain("<div class=\"k\">Platforms</div><div class=\"v\">android, ios</div>");
    // A cell per platform, each opening its own run; the iOS failure gives the row's cell a failed
    // treatment and sections the whole row under Failed (worst outcome wins).
    expect(out).toContain('<div class="idxcell passed"><button class="idxcellopen" type="button" data-session="0"');
    expect(out).toContain('<div class="idxcell failed"><button class="idxcellopen" type="button" data-session="1"');
    expect(out).toContain('<span class="pk">android</span>');
    expect(out).toContain('<span class="pk">ios</span>');
    expect(out).toContain('data-index-section="failed"');
    expect(out).not.toContain('data-index-section="passed"');
    // The footer tallies rows, matching the section counts.
    expect(out).toContain("<strong>1</strong> failed");
    expect(out).toContain("<strong>0</strong> passed");
    // No far-left status dot column and no per-run Platform sort on a matrix index.
    expect(out).not.toContain('class="idxstatus"');
    expect(out).not.toContain('data-run-sort="platform"');
  });

  test("a platform that never ran a trail renders a dashed placeholder cell", () => {
    const on = (title: string, trailId: string, platform: string) => ({
      ...session(title, "passed"),
      meta: { title, status: "passed", platform, trailId, target: "demo" },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [on("login/login", "login/login", "android"), on("login/login", "login/login", "ios"), on("settings/profile", "settings/profile", "ios")],
    });
    expect(out).toContain('<div class="idxcell missing"><span class="pk">android</span><span class="pv">—</span></div>');
    // The placeholder is inert: exactly the three real runs are clickable cells.
    expect(out.match(/idxcell passed/g)).toHaveLength(3);
  });

  test("a retried platform cell shows attempt dots and its chevron expands only that platform's attempts", () => {
    const attempt = (status: string, platform: string, ranAt: string) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", trailId: "checkout", target: "demo", status, platform, ranAt, duration: "20s" },
    });
    const sessions = [
      attempt("failed", "ios", "2026-07-17 10:00:00"),
      attempt("passed", "ios", "2026-07-17 10:05:00"),
      attempt("passed", "android", "2026-07-17 10:01:00"),
    ];
    const collapsed = renderViewer({ generatedAt: "now", sessions });
    // Only the retried iOS cell gets a chevron; the single-attempt android cell does not.
    expect(collapsed.match(/data-cell-toggle/g)).toHaveLength(1);
    expect(collapsed).toContain('data-cell-toggle="trail:checkout:demo:ios"');
    // The retried cell links to the latest attempt and shows the attempt-history dots.
    expect(collapsed).toContain('<div class="idxcell passed retried"><button class="idxcellopen" type="button" data-session="1"');
    expect(collapsed).toContain('class="idxcelldots" role="img" aria-label="Attempt history: failed, passed"');
    // Collapsed by default: no attempt panel.
    expect(collapsed).not.toContain('class="idxatthead"');

    const expanded = renderViewer({ generatedAt: "now", sessions }, { toggleCell: "trail:checkout:demo:ios" });
    expect(expanded).toContain('class="idxcellchev open"');
    expect(expanded).toContain('<div class="idxatthead">ios</div>');
    expect(expanded).not.toContain('<div class="idxatthead">android</div>');
    expect(expanded).toContain('class="idxattemptrow" data-session="0"');
    expect(expanded).toContain('Attempt 1</span><span class="idxattemptstatus failed">failed</span>');
    expect(expanded).toContain('Attempt 2</span><span class="idxattemptstatus passed">passed</span>');
  });

  test("same-platform devices keep their own cells instead of merging into retry history", () => {
    const on = (status: string, platform: string, device: string, ranAt: string) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", trailId: "checkout", target: "demo", status, platform, device, ranAt },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        on("failed", "android", "Pixel Tablet", "2026-07-17 10:00:00"),
        on("passed", "android", "Pixel 7", "2026-07-17 10:05:00"),
        on("passed", "ios", "iPhone Demo", "2026-07-17 10:02:00"),
      ],
    });
    // Two android devices → two device-qualified android columns, not one cell with fake retries.
    expect(out).not.toContain("data-cell-toggle");
    expect(out).toContain('<span class="pk">android · Pixel 7</span>');
    expect(out).toContain('<span class="pk">android · Pixel Tablet</span>');
    // The single-device platform keeps its plain label.
    expect(out).toContain('<span class="pk">ios</span>');
    // The tablet failure stays visible on its own cell and still sections the row under Failed.
    expect(out).toContain('<div class="idxcell failed"><button class="idxcellopen" type="button" data-session="0"');
    expect(out).toContain('data-index-section="failed"');
    expect(out).not.toContain('data-index-section="passed"');
  });

  test("owner metadata composes with matrix rows: subtitle on the row, Owner sort sections matrix entries", () => {
    const on = (platform: string) => ({
      ...session("Checkout", "passed"),
      meta: { title: "Checkout", trailId: "checkout", target: "demo", status: "passed", platform, metadata: { owner: "team-a" } },
    });
    const grouped = renderViewer({ generatedAt: "now", sessions: [on("android"), on("ios")] });
    expect(grouped).toContain('<div class="idxowner">team-a</div>');

    const byOwner = renderViewer({ generatedAt: "now", sessions: [on("android"), on("ios")] }, { query: "?view=runs&sort=owner" });
    // The owner section renders the matrix entry (cells), and the in-section subtitle drops.
    expect(byOwner).toContain('<div class="idxsectionhead">team-a <span class="idxsectioncount">1</span>');
    expect(byOwner).toContain('<div class="idxcell passed');
    expect(byOwner).not.toContain('class="idxowner"');
  });

  test("past four attempts the cell compresses history into a +N prefix and the last three dots", () => {
    const attempt = (status: string, minute: number) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", trailId: "checkout", target: "demo", status, platform: "ios", ranAt: `2026-07-17 10:${String(minute).padStart(2, "0")}:00` },
    });
    const sessions = [
      ...[0, 1, 2, 3, 4].map((minute) => attempt("failed", minute)),
      attempt("passed", 5),
      { ...session("Other", "passed"), meta: { title: "Other", status: "passed", platform: "android", trailId: "other", target: "demo" } },
    ];
    const out = renderViewer({ generatedAt: "now", sessions });
    expect(out).toContain('<span class="idxcellmore">+3</span>');
    // Three dots follow the prefix; the full six-attempt inventory lives in the expandable panel.
    expect(out.match(/idxcelldots[^>]*>(?:<span class="idxcellmore">\+3<\/span>)(<span class="idxstatusdot [a-z]+" aria-hidden="true"><\/span>){3}/)).not.toBeNull();
  });

  test("a single-platform report keeps flat per-run rows without platform cells", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        { ...session("A", "passed"), meta: { title: "A", status: "passed", platform: "android" } },
        { ...session("B", "failed"), meta: { title: "B", status: "failed", platform: "android" } },
      ],
    });
    expect(out).not.toContain('class="idxcell');
    expect(out).not.toContain(">Platforms<");
    expect(out).toContain(">Platform<");
    expect(out).not.toContain('data-run-sort="platform"');
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

  test("index row facts show real test steps and actions, not the flat trace length", () => {
    const row = (i: number, extra: Record<string, unknown> = {}) => ({ i, label: `row ${i}`, tool: "t", note: null, ms: 0, ts: null, ok: true, err: null, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [], ...extra });
    // 1 trailhead step + 1 trailhead action, 2 test steps, 4 trail actions, and a terminal
    // 'Final state' snapshot (tool-less, not an action) → 9 trace rows total.
    const trace = [
      row(1, { objective: true, trailhead: true }), row(2),
      row(3, { objective: true }), row(4), row(5),
      row(6, { objective: true }), row(7), row(8),
      row(9, { label: "Final state", tool: "", screenshotFile: "final.png" }),
    ];
    const mk = (title: string) => ({ meta: { title, status: "passed", duration: "10s", steps: trace.length }, trace, llm: [], shots: {}, recordingYaml: null });
    const out = renderViewer({ generatedAt: "now", sessions: [mk("A"), mk("B")] });
    expect(out).toContain('<div class="k">Steps</div><div class="v">2</div>');
    expect(out).toContain('<div class="k">Actions</div><div class="v">5</div>');
    // meta.steps (the flat trace length) no longer leaks into the facts.
    expect(out).not.toContain('<div class="v">9</div>');
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

  test("a lone run has no back destination, so the header starts at the run title with no empty slot", () => {
    const out = renderViewer({ generatedAt: "now", sessions: [session("Checkout flow", "passed")] });
    expect(out).not.toContain('class="detailedge"');
    expect(out).toContain('<div class="title-row detailtitle noback"><div class="runidentity">');
    expect(core.RUN_REPORT_CSS).toContain('.detailtitle.noback { grid-template-columns: minmax(0,1fr) auto; }');
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
    // offset and its own duration, and the page gets the shared time-scaled horizontal scrubber
    // pinned between the main pane and the footer.
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).toContain("+1.0s");
    expect(out).toContain("100ms");
    expect(out).toContain('class="scrubtrack"');
    expect(out).toContain("scrubtick");
    expect(out).not.toContain("tldot");
    expect(out.indexOf('<div class="scrub">')).toBeGreaterThan(out.indexOf("</main>"));
    expect(out.indexOf('<div class="scrub">')).toBeLessThan(out.indexOf('<footer class="detailfooter">'));
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
    expect(out).toContain('class="scrubline setup" style="width:100%"');
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
        events: [{ name: "network observer", total: 1, truncated: false, events: [{ t: 1, d: "{}" }] }],
      }],
    };
    const info = renderViewer(payload, { tab: "info" });
    expect(info).toContain('<section class="viewpage">');
    expect(info).toContain('<div class="viewhead"><h2 class="viewtitle">Run details</h2>');
    expect(info).toContain('<div class="rows"><div class="r">');
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
    expect(core.RUN_REPORT_CSS).toContain('.detailfooteritem .k { color: var(--neutral-10); font-size: var(--type-micro);');
    expect(core.RUN_REPORT_CSS).toContain('.detailfooteritem .v { color: var(--sub); font-size: var(--type-caption);');
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

  test("tolerated failures inside a passing trailhead don't steal the failure attribution", () => {
    // Mirrors a real run: the trailhead's sign-in tool retries internally (failed rows, no err)
    // but the trailhead objective completes OK; the run then fails at trail step 2, whose
    // objective row carries the failure from its Complete bookend.
    const row = (extra: Record<string, unknown>) => ({ note: null, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [], err: null, ...extra });
    const trace = [
      row({ i: 1, label: "Launch signed in", tool: "agent step", ms: 0, ts: 1, ok: true, objective: true, trailhead: true }),
      row({ i: 2, label: "mobile_maestro", tool: "", ms: 100, ts: 2, ok: false }),
      row({ i: 3, label: "mobile_maestro", tool: "", ms: 100, ts: 3, ok: true }),
      row({ i: 4, label: "Verify the landing screen", tool: "agent step", ms: 0, ts: 4, ok: true, objective: true }),
      row({ i: 5, label: "assertVisibleBySelector", tool: "desc: Money", ms: 10, ts: 5, ok: true }),
      row({ i: 6, label: "Assert the login journey uploaded", tool: "agent step", ms: 0, ts: 6, ok: false, objective: true, err: "Error: Did not find any uploaded user journey named 'login'" }),
      row({ i: 7, label: "Failure state", tool: "", ms: 0, ts: 7, ok: false }),
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Failed", status: "failed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain("Step 2 failed");
    expect(out).not.toContain("Trailhead failed");
    expect(out).toContain("Did not find any uploaded user journey named");
    // The failed run opens on the failing step's row, not the trailhead's tolerated retry.
    expect(out).toContain('class="step sel child" data-step="7"');
    // The failed step's group is painted failed; the passing trailhead group is not.
    expect(out).toContain('class="stepgroup failed"');
    expect(out).not.toContain('class="stepgroup failed"><button type="button" class="grphdr trailhead');
  });

  test("the failure message comes from the failed step, not an earlier tolerated failure's error", () => {
    // A recovered assertion poll in the passing trailhead carries an err; the failed step's only
    // failed tool row (the failure snapshot) carries none, so the message must come from the
    // failed objective's Complete bookend — not from a trace-wide error scan.
    const row = (extra: Record<string, unknown>) => ({ note: null, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [], err: null, ...extra });
    const trace = [
      row({ i: 1, label: "Launch signed in", tool: "agent step", ms: 0, ts: 1, ok: true, objective: true, trailhead: true }),
      row({ i: 2, label: "assertVisibleBySelector", tool: "text: Home", ms: 100, ts: 2, ok: false, err: "Assertion poll attempt failed: Home not visible yet" }),
      row({ i: 3, label: "assertVisibleBySelector", tool: "text: Home", ms: 100, ts: 3, ok: true }),
      row({ i: 4, label: "Assert the login journey uploaded", tool: "agent step", ms: 0, ts: 4, ok: false, objective: true, err: "Error: Did not find any uploaded user journey named 'login'" }),
      row({ i: 5, label: "Failure state", tool: "", ms: 0, ts: 5, ok: false }),
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Failed", status: "failed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain("Step 1 failed");
    expect(out).toContain("Did not find any uploaded user journey named");
    expect(out.match(/failuremessage">([^<]*)</)![1]).not.toContain("Home not visible yet");
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
        events: [{ name: "network observer", total: 1, truncated: false, events: [{ t: 1704067200500, d: '{"path":"/payments"}' }] }],
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
    // Payload bodies are lazy (filled on first open); the pretty text itself comes from the
    // shared normalizer the lazy fill uses.
    expect(out).toContain("data-lazykey=");
    expect((core as any).eventPrettyText({ t: 1704067200500, d: '{"path":"/payments"}' })).toBe('{\n  "path": "/payments"\n}');
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

  test("a tap outside the timeline stream dropdown dismisses it", () => {
    const payload = {
      generatedAt: "now",
      sessions: [{
        meta: { title: "Plugin events", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "network", total: 1, truncated: false, events: [{ t: 1, d: "{}" }] }],
      }],
    };
    // Toggling a stream re-renders with the chooser open; a pointerdown outside then dismisses it.
    const dismissed = renderViewerState(payload, { tlStream: 0, pointerDown: "outside" });
    expect(dismissed.html).toContain("data-streamselect open");
    expect(dismissed.timelineMenuOpen).toBe(false);
    // A tap inside the open menu leaves it alone.
    const kept = renderViewerState(payload, { tlStream: 0, pointerDown: "insideTimelineMenu" });
    expect(kept.timelineMenuOpen).toBe(true);
  });

  test("timeline stream controls sit above Trailhead and summarize selection", () => {
    const events = ["network", "lifecycle", "analytics", "eligibility"].map((name, i) => ({
      name, total: 1, truncated: false, events: [{ t: 1704067200500 + i, d: "{}" }],
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

  test("vertical arrow keys step the timeline like the horizontal ones", () => {
    const payload = { generatedAt: "now", sessions: [session("Keyboard", "passed")] };
    const down = renderViewerState(payload, { routeStep: slim[0].i, timelineKey: "ArrowDown" });
    expect(down.route).toContain(`step=${slim[1].i}`);
    const up = renderViewerState(payload, { routeStep: slim[1].i, timelineKey: "ArrowUp" });
    expect(up.route).toContain(`step=${slim[0].i}`);
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
      video: { sprites: [{ uri: "data:image/webp;base64,AAAA", rows: 2 }], fps: 2, frames: 2, columns: 1, rows: 2, frameHeight: 40, frameMap: [0, 1], startFrame: 0, endFrame: 1 },
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

  test("the timeline preview shows the captured video frame when the video carries capture timestamps", () => {
    const timed = {
      ...payload,
      sessions: [{ ...payload.sessions[0], video: { ...payload.sessions[0].video, startMs: 1704067200000 } }],
    };
    const out = renderViewer(timed, { step: slim[1].i });
    expect(out).toContain('id="tlvframe"');
    expect(out).toContain("background-image:url('data:image/webp;base64,AAAA')");
    // slim[1] ran at capture start + 1s; at 2fps that's past the last frame, so it clamps to
    // endFrame 1 → sprite row 1 of a 1×2 sheet (background-position 0% 100%).
    expect(out).toContain("background-position:0% 100%");
    expect(out).not.toContain('id="shot"');
  });

  test("the timeline preview reads a multi-column sprite sheet row-major", () => {
    // A 2×3 sheet, so physical frame 1 is the SECOND cell of the top row (ffmpeg's `tile` fills
    // left-to-right, then down). Reading the grid transposed puts it a row down instead — still
    // a real frame of this run, just not the one the step is on, which is why the misalignment
    // went unnoticed until sessions grew past one column's worth of unique frames.
    const wide = {
      ...payload,
      sessions: [{
        ...payload.sessions[0],
        video: {
          ...payload.sessions[0].video,
          frames: 6, columns: 2, rows: 3, frameMap: [0, 1, 2, 3, 4, 5],
          sprites: [{ uri: "data:image/webp;base64,AAAA", rows: 3 }],
          startFrame: 0, endFrame: 5,
          startMs: slim[1].ts - 500, // 500ms before this step ⇒ logical frame 1 at 2fps
        },
      }],
    };
    const out = renderViewer(wide, { step: slim[1].i });
    expect(out).toContain("background-size:200% 300%");
    expect(out).toContain("background-position:100% 0%");
  });

  test("the timeline preview keeps per-step screenshots when the video has no capture timestamps", () => {
    const out = renderViewer(payload, { step: slim[1].i });
    expect(out).not.toContain("tlvframe");
    expect(out).toContain('id="shot"');
  });

  test("lightbox tab renders a thumbnail cell per screenshot step", () => {
    const out = renderViewer(payload, { tab: "lightbox" });
    expect(out).toContain("galcell");
  });

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

  test("lightbox defaults to the last screenshot in each authored step and can show every frame", () => {
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
    expect(core.RUN_REPORT_CSS).toContain('grid-template-columns: repeat(auto-fill,minmax(min(var(--galsize,190px),100%),1fr))');

    const expanded = renderViewer(groupedPayload, { tab: "lightbox", lightboxAll: true });
    expect(expanded).toContain('data-shot="first.png"');
    expect(expanded).toContain('aria-checked="true"');

    expect(renderViewerState(groupedPayload, { tab: "lightbox", zoomShot: "last.png", zoomKey: "ArrowRight" }).zoomSrc)
      .toBe("data:image/png;base64,CONFIRM");
    expect(renderViewerState(groupedPayload, { tab: "lightbox", zoomShot: "confirm.png", zoomKey: "ArrowLeft" }).zoomSrc)
      .toBe("data:image/png;base64,LAST");
  });

  test("lightbox thumbnails keep a fixed default size and the zoom buttons step shots-per-row", () => {
    const out = renderViewer(groupedPayload, { tab: "lightbox" });
    expect(out).toContain('style="--galsize:190px"');
    expect(out).toContain('data-gal-zoom="-1"');
    expect(out).toContain('data-gal-zoom="1"');

    expect(renderViewer(groupedPayload, { tab: "lightbox", galZoom: [1] })).toContain('style="--galsize:260px"');
    expect(renderViewer(groupedPayload, { tab: "lightbox", galZoom: [-1] })).toContain('style="--galsize:140px"');
    // Zoom clamps at both ends, and the exhausted direction reads disabled.
    const min = renderViewer(groupedPayload, { tab: "lightbox", galZoom: [-1, -1, -1] });
    expect(min).toContain('style="--galsize:140px"');
    expect(min).toMatch(/data-gal-zoom="-1"[^>]* disabled/);
    const max = renderViewer(groupedPayload, { tab: "lightbox", galZoom: [1, 1, 1, 1, 1] });
    expect(max).toContain('style="--galsize:500px"');
    expect(max).toMatch(/data-gal-zoom="1"[^>]* disabled/);
  });

  test("the lightbox zoom shows a step-label rail with the current step highlighted, no count pill", () => {
    const { zoomRoot } = renderViewerState(groupedPayload, { tab: "lightbox", zoomShot: "last.png", zoomKey: "ArrowRight" });
    expect(zoomRoot.children.some((c: any) => c.className === "zoomcount")).toBe(false);
    const rail = zoomRoot.children.find((c: any) => c.className === "zoomsteps");
    const labels = rail.children.map((item: any) => item.children.map((span: any) => span.textContent).join(" · "));
    expect(labels).toEqual(["STEP 1 · Open checkout · waitForAnimation", "STEP 2 · Confirm order · assertVisible"]);
    // After ArrowRight the second entry is the highlighted one.
    expect(rail.children.map((item: any) => item.className)).toEqual(["zoomstep", "zoomstep cur"]);
    // A one-screenshot gallery gets no rail — the labels are context for navigating, not a caption.
    const onePayload = {
      generatedAt: "now",
      sessions: [{
        meta: { title: "Run", status: "passed" },
        trace: [
          { i: 1, label: "Open checkout", objective: true, ok: true },
          { i: 2, label: "tapOnElement", screenshotFile: "only.png", ok: true },
        ],
        llm: [],
        shots: { "only.png": "data:image/png;base64,ONLY" },
        recordingYaml: null,
      }],
    };
    const single = renderViewerState(onePayload, { tab: "lightbox", zoomShot: "only.png" });
    expect(single.zoomRoot.children.some((c: any) => c.className === "zoomsteps")).toBe(false);
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

describe("inline event streams (timeline, generic shape)", () => {
  // Producer-agnostic: two streams as the driver emits them; the renderer knows nothing about any
  // specific producer. `total` > events.length marks a stream the driver truncated.
  const trace = [
    { i: 1, label: "Open app", tool: null, note: null, ms: 0, ts: 500, ok: true, err: null, screenshotFile: null, objective: true, trailhead: false, count: null, mark: null, children: [] },
  ];
  const payload = {
    generatedAt: "now",
    sessions: [{
      meta: { title: "Events run", status: "passed" },
      trace, llm: [], shots: {}, recordingYaml: null, deviceLog: null, network: null, video: null,
      events: [
        { name: "com.example.plugin.network", total: 3, truncated: false, events: [
          { t: 1000, d: '{"request":{"url":"https://api.test/foo"}}' },
          { t: 1500, d: '{"finalizedResponse":{"statusCode":200}}' },
          { t: 2000, d: '{"error":{"reason":"x"}}' },
        ] },
        { name: "com.example.plugin.analytics", total: 120, truncated: true, events: [
          { t: 1200, d: '{"Event":"ColdStart"}' },
        ] },
      ],
    }],
  };

  test("there is no Events tab; streams live in the timeline chooser with their total counts", () => {
    const out = renderViewer(payload);
    expect(out).not.toContain('data-tab="events"');
    expect(out).toContain("Event streams");
    expect(out).toContain("com.example.plugin.network");
    expect(out).toContain("com.example.plugin.analytics");
    // The chooser reports the driver's true total, not just the embedded lines.
    expect(out).toContain('<span class="streamcount">120</span>');
  });

  test("the stream chooser stays pinned while the timeline scrolls", () => {
    expect(core.RUN_REPORT_CSS).toContain(".timelinecontrols { position: sticky; top: 0;");
    // Phase heads re-anchor below the pinned chooser instead of sliding underneath it.
    expect(core.RUN_REPORT_CSS).toContain(".timeline-list:has(.timelinecontrols) .tlphasehead { top: 39px; }");
  });

  test("a legacy tab=events URL lands on the timeline", () => {
    const state = renderViewerState(payload, { query: "?run=0&tab=events&stream=1" });
    expect(state.html).toContain('class="timeline-list"');
    expect(state.route).not.toContain("tab=events");
    expect(state.route).not.toContain("stream=");
  });

  test("a selected stream renders each event inline at its offset with a lazy body", () => {
    const out = renderViewer(payload, { tlStream: 0 });
    expect(out).toContain('class="timelineevent"');
    expect(out).toContain("+0.50s");
    expect(out).toContain("+1.50s");
    // Payload bodies fill on first open (wireLazyTimelineBodies); until then the pre is empty.
    expect(out).toContain('<pre class="mono"></pre>');
    expect(out).not.toContain("api.test/foo");
  });

  test("escaped-JSON payloads get a readable label and a fully de-escaped body", () => {
    const escapedEvent = { t: 1000, d: '{\\"columnItems\\":{\\"Event\\":\\"BlockerFlow Interact CompleteFlow\\",\\"Raw Message\\":\\"{\\\\\\"event_name\\\\\\":\\\\\\"NestedAction\\\\\\",\\\\\\"action_text\\\\\\":\\\\\\"Done\\\\\\"}\\"}}' };
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Escaped", status: "passed" },
        trace, llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "com.example.plugin.analytics", total: 1, truncated: false, events: [escapedEvent] }],
      }],
    }, { tlStream: 0 });
    expect(out).toContain("BlockerFlow Interact CompleteFlow");
    // The full payload (rendered when the body opens) is fully de-escaped, nested layers included.
    const pretty = (core as any).eventPrettyText(escapedEvent);
    expect(pretty).toContain("NestedAction");
    expect(pretty).toContain("Done");
    expect(pretty).not.toContain('\\\\"');
  });

  test("event label priority is semantic rather than object insertion order", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Labels", status: "passed" },
        trace, llm: [], shots: {}, recordingYaml: null,
        events: [{ name: "analytics", total: 1, truncated: false, events: [
          { t: 1000, d: JSON.stringify({ message: "Secondary detail", event: "Checkout completed" }) },
        ] }],
      }],
    }, { tlStream: 0 });

    expect(out).toContain('<span class="timelineeventlabel">Checkout completed</span>');
  });

  test("raw event JSON preserves fields beyond the summary scan budget", () => {
    const large = Object.fromEntries(Array.from({ length: 100 }, (_, i) => [`field${i}`, `value${i}`]));
    // The summary scan is bounded, but the payload text (rendered when the body opens) is not.
    expect((core as any).eventPrettyText({ t: 1000, d: JSON.stringify(large) })).toContain('"field99": "value99"');
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

describe("formatted event streams (EventStream.rows)", () => {
  const slim = (core as any).slimTraceForShare(core.extractTrace(sampleLogs));
  const formattedStream = {
    name: "com.example.plugin.network",
    total: 2,
    truncated: false,
    events: [],
    rows: [
      {
        t: 1000,
        label: "POST /2.0/pay",
        tone: "ok",
        badges: [{ text: "200", tone: "ok" }, { text: "142ms" }],
        fields: [{ k: "Host", v: "api.example.com" }],
        raw: [{ request: { id: "r1" } }],
      },
      { t: 2000, label: "POST /2.0/fail", tone: "error", badges: [{ text: "503", tone: "error" }] },
    ],
  };
  const genericStream = {
    name: "com.example.plugin.analytics",
    total: 1,
    truncated: false,
    events: [{ t: 1500, d: '{"event":"screen_view"}' }],
  };
  const payload = {
    generatedAt: "now",
    sessions: [{ meta: { title: "Run", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null, events: [formattedStream, genericStream] }],
  };

  test("buildMultiReportHtml embeds formatter rows untouched in the payload", () => {
    const html = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "passed" }, trace: [], llmLogs: [], shots: {}, events: [formattedStream] }],
    });
    const embedded = payloadOf(html).sessions[0].events[0];
    expect(embedded.rows).toEqual(formattedStream.rows);
  });

  test("the timeline renders formatted rows with badges up front and a lazy body", () => {
    const out = renderViewer(payload, { tlStream: 0 });
    expect(out).toContain('class="timelineevent"');
    expect(out).toContain("POST /2.0/pay");
    expect(out).toContain('class="rowbadge ok">200<');
    expect(out).toContain('class="rowbadge error">503<');
    // The body (fields + pretty-printed raw payloads) fills lazily on first open — the summary
    // carries only the row-level chrome. The modifier must not be the page-level `tl` layout class,
    // whose grid+gap display would restyle the body.
    expect(out).toContain('<div class="fmtbody tlbody"></div>');
    expect(out).not.toContain('class="fmtbody tl"');
    expect(out).not.toContain("api.example.com");
  });

  test("a stream without rows keeps the generic event rendering", () => {
    const out = renderViewer(payload, { tlStream: 1 });
    expect(out).toContain('<span class="timelineeventlabel">screen_view</span>');
    expect(out).not.toContain('class="fmtbody tlbody"');
  });

  test("a row's tone marks the timeline row so severity reads without expanding", () => {
    const out = renderViewer(payload, { tlStream: 0 });
    expect(out).toContain('class="timelineevent e"'); // the 503 row carries tone: "error"
    expect(out).toContain('class="timelineevent"'); // the ok-tone row stays untinted
  });

  test("an event-only session (no trace steps) still exposes its streams on the timeline", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "Startup failure", status: "failed" }, trace: [], llm: [], shots: {}, recordingYaml: null, events: [genericStream] }],
    }, { tlStream: 0 });
    expect(out).toContain("Event streams"); // the chooser renders without any steps to anchor it
    expect(out).toContain('<span class="timelineeventlabel">screen_view</span>');
  });

  test("rawPrettyText pretty-prints raw values, recursively parsing JSON-in-string layers", () => {
    const pretty = (core as any).rawPrettyText({ body: '{"event_name":"NestedAction","meta":"{\\"depth\\":2}"}' });
    expect(pretty).toContain('"event_name": "NestedAction"');
    expect(pretty).toContain('"depth": 2');
    expect(pretty).not.toContain('\\"');
    // A plain string stays a plain string rather than being JSON-quoted.
    expect((core as any).rawPrettyText("plain text")).toBe("plain text");
  });
});

describe("compressed event streams (SessionPayload.eventsGz)", () => {
  const slim = (core as any).slimTraceForShare(core.extractTrace(sampleLogs));
  const streams = [{
    name: "com.example.plugin.network",
    total: 1,
    truncated: false,
    events: [],
    rows: [{ t: 1000, label: "POST /2.0/pay", badges: [{ text: "200", tone: "ok" }] }],
  }];
  const gz = (value: unknown) => require("zlib").gzipSync(JSON.stringify(value)).toString("base64");

  test("inflateEventsGz round-trips a driver-compressed payload", async () => {
    const inflated = await (core as any).inflateEventsGz(gz(streams));
    expect(inflated).toEqual(streams);
  });

  test("inflateEventsGz returns null for malformed input instead of throwing", async () => {
    expect(await (core as any).inflateEventsGz("not base64 gzip")).toBeNull();
    expect(await (core as any).inflateEventsGz(gz({ not: "an array" }))).toBeNull();
  });

  test("buildMultiReportHtml embeds eventsGz verbatim without inflating it", () => {
    const html = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "passed" }, trace: [], llmLogs: [], shots: {}, eventsGz: gz(streams) }],
    });
    const embedded = payloadOf(html).sessions[0];
    expect(embedded.eventsGz).toBe(gz(streams));
    expect(embedded.events).toBeNull();
  });

  test("a compressed session renders the timeline immediately; the stream chooser waits for inflation", () => {
    const payload = {
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null, eventsGz: gz(streams) }],
    };
    const out = renderViewer(payload);
    expect(out).toContain('class="timeline-list"');
    // Streams aren't inflated yet at first render — the chooser appears on the post-inflate
    // re-render (inflateEventsGz round-trip covered above).
    expect(out).not.toContain("Event streams");
  });
});

describe("playback timing (pure core)", () => {
  const pure = core; // the playback helpers are part of the typed require surface above
  // 2fps sprite starting at run-clock 1000ms, 10 playable frames (ends at 6000ms).
  const video = { sprites: [{ uri: "data:image/webp;base64,X", rows: 5 }], fps: 2, frames: 10, columns: 2, rows: 5, frameHeight: 100, frameMap: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], startFrame: 0, endFrame: 9, startMs: 1000 };

  test("playbackGapMs keeps real gaps but floors fast bursts and caps long idles", () => {
    expect(pure.playbackGapMs(900)).toBe(900); // a real gap plays at its real duration
    expect(pure.playbackGapMs(50)).toBe(350); // a fast burst stays visible
    expect(pure.playbackGapMs(30000)).toBe(4000); // a 30s LLM wait doesn't stall playback
  });

  test("videoFrameAt maps run-clock time to a frame, clamped to the playable range", () => {
    expect(pure.videoFrameAt(video, 1000)).toBe(0);
    expect(pure.videoFrameAt(video, 1499)).toBe(0);
    expect(pure.videoFrameAt(video, 1500)).toBe(1);
    expect(pure.videoFrameAt(video, 3000)).toBe(4);
    expect(pure.videoFrameAt(video, 0)).toBe(0); // before capture start
    expect(pure.videoFrameAt(video, 999999)).toBe(9); // past the end
    expect(pure.videoFrameAt({ ...video, startFrame: 3 }, 1000)).toBe(3);
  });

  test("videoEndMs is the run-clock instant the last playable frame ends", () => {
    expect(pure.videoEndMs(video)).toBe(6000); // 1000 + 10 frames × 500ms
  });

  test("spriteFrameCss lays frames out row-major and honors frameMap aliases", () => {
    // The sheet is 2 wide × 5 tall and ffmpeg's `tile` fills it left-to-right then down, so
    // physical frame N is at row N/2, column N%2. Frame 1 sitting beside frame 0 rather than
    // below it is the whole difference: reading this transposed serves a real frame of the run
    // at the wrong step.
    expect(pure.spriteFrameCss(video, 0)).toEqual({ sheet: 0, size: "200% 500%", position: "0% 0%" });
    expect(pure.spriteFrameCss(video, 1)).toEqual({ sheet: 0, size: "200% 500%", position: "100% 0%" });
    expect(pure.spriteFrameCss(video, 3)).toEqual({ sheet: 0, size: "200% 500%", position: "100% 25%" });
    expect(pure.spriteFrameCss(video, 5)).toEqual({ sheet: 0, size: "200% 500%", position: "100% 50%" });
    const aliased = { ...video, frameMap: [0, 0, 2, 3, 4, 5, 6, 7, 8, 9] };
    expect(pure.spriteFrameCss(aliased, 1)).toEqual(pure.spriteFrameCss(aliased, 0));
  });

  test("spriteFrameCss spans sheets: frames beyond one full 2x2 sheet land on later sheets, sized to that sheet's own rows", () => {
    // 10 unique frames across 2x2 sheets → sheets 0/1 full, sheet 2 holds frames 8-9 in one row.
    const multi = {
      ...video,
      columns: 2,
      rows: 2,
      sprites: [
        { uri: "data:image/webp;base64,S0", rows: 2 },
        { uri: "data:image/webp;base64,S1", rows: 2 },
        { uri: "data:image/webp;base64,S2", rows: 1 },
      ],
    };
    expect(pure.spriteFrameCss(multi, 3)).toEqual({ sheet: 0, size: "200% 200%", position: "100% 100%" });
    expect(pure.spriteFrameCss(multi, 4)).toEqual({ sheet: 1, size: "200% 200%", position: "0% 0%" });
    // Final partial sheet: one row, so the vertical axis collapses to 0%.
    expect(pure.spriteFrameCss(multi, 9)).toEqual({ sheet: 2, size: "200% 100%", position: "100% 0%" });
  });

  describe("steps mode (no video): real pacing with clamped idle gaps", () => {
    // Each row dwells until the NEXT row's schedule entry (the clamped gap that row adds): a 100ms
    // gap (floored to 350), a 30s idle (capped to 4000), the untimed row's own 500ms recorded
    // duration (which times the dwell of the row BEFORE it), then a 100ms gap (floored to 350)
    // that times the untimed row's dwell.
    const rows = [
      { ts: 10000, ms: 100 },
      { ts: 10100, ms: 100 },
      { ts: 40100, ms: 200 },
      { ts: null, ms: 500 },
      { ts: 40200, ms: 100 },
    ];
    const schedule = pure.buildPlaybackSchedule(rows, null);

    test("builds the compressed schedule", () => {
      expect(schedule.mode).toBe("steps");
      expect(schedule.clock0).toBeNull();
      expect(schedule.video).toBeNull();
      expect(schedule.offsets).toEqual([0, 350, 4350, 4850, 5200]);
      expect(schedule.totalMs).toBe(5550); // the last row dwells its own clamped duration before playback ends
    });

    test("carries the rows' timestamp coverage for the timeline axis", () => {
      expect(schedule.haveTs).toBe(true);
      expect(schedule.lo).toBe(10000);
      expect(schedule.hi).toBe(40200);
      expect(pure.buildPlaybackSchedule([{ ts: null, ms: 100 }], null).haveTs).toBe(false);
    });

    test("positions advance step-by-step as the playback clock passes each offset", () => {
      expect(pure.playbackPositionAt(schedule, 0).stepIndex).toBe(0);
      expect(pure.playbackPositionAt(schedule, 349).stepIndex).toBe(0);
      expect(pure.playbackPositionAt(schedule, 350).stepIndex).toBe(1);
      expect(pure.playbackPositionAt(schedule, 4349).stepIndex).toBe(1);
      expect(pure.playbackPositionAt(schedule, 4350).stepIndex).toBe(2);
      expect(pure.playbackPositionAt(schedule, 5200).stepIndex).toBe(4);
    });

    test("no frame or run clock without video, and playback finishes after the final dwell", () => {
      const mid = pure.playbackPositionAt(schedule, 5200);
      expect(mid.frame).toBeNull();
      expect(mid.clockMs).toBeNull();
      expect(mid.done).toBe(false);
      expect(pure.playbackPositionAt(schedule, 5550).done).toBe(true);
    });

    test("rows without any timestamps fall back to duration-based dwells", () => {
      const untimed = pure.buildPlaybackSchedule([{ ts: null, ms: 1000 }, { ts: null, ms: 100 }], null);
      expect(untimed.offsets).toEqual([0, 350]);
      expect(untimed.totalMs).toBe(700);
    });
  });

  describe("video mode: the playback clock is the run clock", () => {
    const rows = [{ ts: 1000 }, { ts: 2000 }, { ts: null }, { ts: 5000 }];
    const schedule = pure.buildPlaybackSchedule(rows, video);

    test("offsets are real timestamp deltas; untimed rows ride along with the last timed row", () => {
      expect(schedule.mode).toBe("video");
      expect(schedule.clock0).toBe(1000);
      expect(schedule.offsets).toEqual([0, 1000, 1000, 4000]);
    });

    test("one clock value yields the step, the run-clock ms, and the video frame together", () => {
      const start = pure.playbackPositionAt(schedule, 0);
      expect(start).toEqual({ stepIndex: 0, clockMs: 1000, frame: 0, done: false });
      const later = pure.playbackPositionAt(schedule, 1000);
      expect(later.stepIndex).toBe(2); // advanced through the untimed rider
      expect(later.clockMs).toBe(2000);
      expect(later.frame).toBe(2);
    });

    test("a video longer than the trace keeps playing to the video's end", () => {
      expect(schedule.totalMs).toBe(5000); // videoEndMs 6000 - clock0 1000
      expect(pure.playbackPositionAt(schedule, 4999).done).toBe(false);
      expect(pure.playbackPositionAt(schedule, 5000).done).toBe(true);
    });

    test("a video shorter than the trace cannot wedge the stop", () => {
      const shortVideo = { ...video, endFrame: 1 }; // ends at run-clock 2000
      const s = pure.buildPlaybackSchedule(rows, shortVideo);
      expect(s.totalMs).toBe(4000); // trace end governs
      expect(pure.playbackPositionAt(s, 3999).done).toBe(false);
      const end = pure.playbackPositionAt(s, 4000);
      expect(end.done).toBe(true);
      expect(end.frame).toBe(1); // frame stays clamped to the short video's last frame
    });

    test("falls back to the steps schedule when the video cannot be mapped onto the run clock", () => {
      expect(pure.buildPlaybackSchedule(rows, { ...video, startMs: null }).mode).toBe("steps");
      expect(pure.buildPlaybackSchedule([{ ts: null }], video).mode).toBe("steps");
    });
  });

  test("videoLoopFrame advances by wall-clock time and wraps so the Video tab loops", () => {
    expect(pure.videoLoopFrame(0, 10, 2, 0)).toBe(0);
    expect(pure.videoLoopFrame(0, 10, 2, 499)).toBe(0);
    expect(pure.videoLoopFrame(0, 10, 2, 500)).toBe(1);
    expect(pure.videoLoopFrame(0, 10, 2, 5000)).toBe(0); // wrapped
    expect(pure.videoLoopFrame(8, 10, 2, 1000)).toBe(0); // resume near the end wraps too
    expect(pure.videoLoopFrame(0, 0, 2, 1000)).toBe(0); // degenerate: no frames
  });
});

describe("timeline playback drive (rAF engine + paint in place)", () => {
  // Four screenshot steps with real timestamps: gaps 500ms, 500ms, then a 9s idle capped at
  // 4000ms — steps-mode schedule offsets [0, 500, 1000, 5000], totalMs 5350 (final 350ms dwell).
  const playbackPayload = () => ({
    generatedAt: "now",
    sessions: [{
      meta: { title: "Playback run", status: "passed" },
      trace: [
        { i: 1, label: "Open app", ts: 100000, ms: 100, ok: true, screenshotFile: "s1.png" },
        { i: 2, label: "Tap login", ts: 100500, ms: 100, ok: true, screenshotFile: "s2.png" },
        { i: 3, label: "Enter code", ts: 101000, ms: 100, ok: true, screenshotFile: "s3.png" },
        { i: 4, label: "See home", ts: 110000, ms: 100, ok: true, screenshotFile: "s4.png" },
      ],
      llm: [],
      shots: { "s1.png": "data:image/png;base64,S1", "s2.png": "data:image/png;base64,S2", "s3.png": "data:image/png;base64,S3", "s4.png": "data:image/png;base64,S4" },
      recordingYaml: null,
    }],
  });

  test("play advances the selection on the schedule by painting in place, with exactly one full render at stop", () => {
    renderViewerState(playbackPayload(), {
      drive: (ctx) => {
        ctx.play();
        const rendersAfterPlay = ctx.renders(); // the play click itself renders the playing chrome once
        expect(ctx.html()).toContain('aria-label="Pause timeline"');
        ctx.advance(0); // first engine frame: dt 0, still parked on the first row
        expect(ctx.html()).toContain('class="step sel" data-step="1"'); // the play render already shows it; no in-place repaint needed
        ctx.advance(600); // past the 500ms offset → second row
        expect(ctx.selectedSteps()).toEqual(["2"]);
        expect(ctx.scrubAttr("aria-valuenow")).toBe("2");
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S2");
        expect(ctx.shotImg.alt).toContain("Tap login"); // accessible name tracks playback too
        expect(ctx.prevBtn.disabled).toBe(false); // Previous re-enables in place once playback leaves row 1
        ctx.advance(500); // 1100ms → past the 1000ms offset → third row
        expect(ctx.selectedSteps()).toEqual(["3"]);
        expect(ctx.scrubAttr("aria-valuenow")).toBe("3");
        expect(ctx.scrubAttr("aria-valuetext")).toContain("Enter code");
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S3");
        expect(ctx.renders()).toBe(rendersAfterPlay); // three steps advanced with ZERO re-renders
        ctx.advance(4000); // 5100ms → past the capped 5000ms offset → last row, not yet done
        expect(ctx.selectedSteps()).toEqual(["4"]);
        expect(ctx.nextBtn.disabled).toBe(true); // Next disables in place on the last row
        expect(ctx.prevBtn.disabled).toBe(false);
        expect(ctx.renders()).toBe(rendersAfterPlay);
        ctx.advance(300); // 5400ms ≥ totalMs 5350 → playback ends
        expect(ctx.renders()).toBe(rendersAfterPlay + 1); // exactly ONE full render at stop
        expect(ctx.html()).toContain('aria-label="Play timeline"');
        expect(ctx.html()).toContain('class="step sel" data-step="4"');
        ctx.advance(1000); // engine is gone: further clock advances change nothing
        expect(ctx.renders()).toBe(rendersAfterPlay + 1);
      },
    });
  });

  test("a mid-playback zoom shows the step being played, and pause lands with one render", () => {
    const state = renderViewerState(playbackPayload(), {
      drive: (ctx) => {
        ctx.play();
        const rendersAfterPlay = ctx.renders();
        ctx.advance(0);
        ctx.advance(1200); // → third row
        expect(ctx.selectedSteps()).toEqual(["3"]);
        // The zoom must resolve the CURRENT step at click time — not the step playback started on
        // (the screenshot handler used to capture the play-start shot in its closure).
        ctx.clickShot();
        ctx.play(); // toggle → pause
        expect(ctx.renders()).toBe(rendersAfterPlay + 1);
        expect(ctx.html()).toContain('aria-label="Play timeline"');
        expect(ctx.html()).toContain('class="step sel" data-step="3"');
        ctx.advance(5000); // paused: the clock advancing must not resume or advance anything
        expect(ctx.renders()).toBe(rendersAfterPlay + 1);
        expect(ctx.html()).toContain('class="step sel" data-step="3"');
      },
    });
    expect(state.zoomSrc).toBe("data:image/png;base64,S3");
    expect(state.route).toContain("step=3"); // pause wrote the landed step into the shareable route
  });
});

describe("compressed device/network logs (SessionPayload.deviceLogGz / networkGz)", () => {
  const slim = (core as any).slimTraceForShare(core.extractTrace(sampleLogs));
  const gzText = (value: string) => require("zlib").gzipSync(value).toString("base64");
  const deviceLog = Array.from({ length: 3000 }, (_, i) => `I/Tag(${i}): device line ${i}`).join("\n") + "\nE/Boom: FATAL crash";
  const network = [
    { method: "GET", statusCode: 200, durationMs: 5, urlPath: "/inflated-ok", phase: "RESPONSE_END" },
    { method: "POST", statusCode: 500, durationMs: 9, urlPath: "/inflated-fail", phase: "RESPONSE_END" },
  ];
  const gzPayload = () => ({
    generatedAt: "now",
    sessions: [{
      meta: { title: "Run", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null,
      deviceLogGz: gzText(deviceLog), networkGz: gzText(JSON.stringify(network)),
    }],
  });
  // Wait for the async inflate → re-render pass to land (bounded, no fixed sleep).
  const settled = async (read: () => string, needle: string): Promise<string> => {
    for (let i = 0; i < 100 && !read().includes(needle); i++) await new Promise((resolve) => setTimeout(resolve, 5));
    return read();
  };

  test("inflateGzText round-trips a driver-compressed payload", async () => {
    expect(await (core as any).inflateGzText(gzText(deviceLog))).toBe(deviceLog);
  });

  test("inflateGzText returns null for malformed input instead of throwing", async () => {
    expect(await (core as any).inflateGzText("not base64 gzip")).toBeNull();
  });

  test("buildMultiReportHtml embeds deviceLogGz/networkGz verbatim without inflating them", () => {
    const html = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "passed" }, trace: [], llmLogs: [], shots: {}, deviceLogGz: gzText(deviceLog), networkGz: gzText(JSON.stringify(network)) }],
    });
    const embedded = payloadOf(html).sessions[0];
    expect(embedded.deviceLogGz).toBe(gzText(deviceLog));
    expect(embedded.deviceLog).toBeNull();
    expect(embedded.networkGz).toBe(gzText(JSON.stringify(network)));
    expect(embedded.network).toBeNull();
  });

  test("nav exposes the Device logs and Network tabs for compressed-only sessions", () => {
    const out = renderViewer(gzPayload());
    for (const tab of ["Device logs", "Network"]) expect(out).toContain(">" + tab + "<");
  });

  test("device-logs tab shows the user the identical log text once inflation lands", async () => {
    const state = renderViewerState(gzPayload(), { tab: "device" });
    // First render happens before the async inflate completes.
    expect(state.html).toContain("Decompressing device log");
    const out = await settled(state.readHtml, "FATAL crash");
    expect(out).toContain("device line 0");
    expect(out).toContain("device line 2999");
    expect(out).toContain("FATAL crash");
    expect(out).toContain("3001 lines");
    expect(out).toContain("ln e"); // severity highlighting works on the inflated text too
  });

  test("network tab renders the inflated events once inflation lands", async () => {
    const state = renderViewerState(gzPayload(), { tab: "network" });
    expect(state.html).toContain("Decompressing network log");
    const out = await settled(state.readHtml, "/inflated-ok");
    expect(out).toContain("/inflated-ok");
    expect(out).toContain("/inflated-fail");
    expect(out).toContain("2 events");
    expect(out).toContain("ln e"); // >=400 rows keep their error class
  });

  test("Export logs clicked before inflation still downloads the complete logs and events", async () => {
    const urlAny = URL as any;
    const original = { create: urlAny.createObjectURL, revoke: urlAny.revokeObjectURL };
    let downloaded: Blob | null = null;
    urlAny.createObjectURL = (blob: Blob) => { downloaded = blob; return "blob:test"; };
    urlAny.revokeObjectURL = () => {};
    // Opening the session already kicked off inflation; the export click is a second, concurrent
    // request for the same payloads - it must still download them complete, never empty.
    const streams = [{ name: "net", total: 1, truncated: false, events: [], rows: [{ t: 1, label: "POST /pay", badges: [] }] }];
    const payload = gzPayload();
    (payload.sessions[0] as Record<string, unknown>).eventsGz = gzText(JSON.stringify(streams));
    try {
      renderViewerState(payload, { exportLogs: true });
      for (let i = 0; i < 100 && !downloaded; i++) await new Promise((resolve) => setTimeout(resolve, 5));
      const logs = JSON.parse(await downloaded!.text());
      expect(logs.deviceLog).toBe(deviceLog);
      expect(logs.network).toEqual(network);
      expect(logs.events).toEqual(streams);
    } finally {
      urlAny.createObjectURL = original.create;
      urlAny.revokeObjectURL = original.revoke;
    }
  });
});

// The packaged bundle republishes its exports to bun consumers via a CJS footer that reads the
// __TRAILBLAZE_RUN_REPORT_CORE__ global the entry module publishes (see bundleRunReportCore in
// build.gradle.kts). This pins the two surfaces together: an export added to the module but not
// the published global (or vice versa) would ship a bundle whose require() surface silently
// diverges from the module's.
describe("bundle export surface (CJS footer parity)", () => {
  test("the module's ESM exports equal the __TRAILBLAZE_RUN_REPORT_CORE__ surface the footer republishes", () => {
    const published = (globalThis as Record<string, unknown>).__TRAILBLAZE_RUN_REPORT_CORE__ as Record<string, unknown>;
    expect(Object.keys(RUN_REPORT_CORE_MODULE).sort()).toEqual(Object.keys(published).sort());
  });
});
