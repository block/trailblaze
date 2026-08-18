// Behavior tests for the headless-reusable report core (run-report-core.ts). These pin the
// observable contract a headless generator (or the in-app Share button) depends on: the derived
// trace shape, and the self-contained HTML's embedded payload (single run, multi-run index, and
// the recording-YAML tab). We deliberately don't drive the DOM viewer here — instead we parse the
// embedded __TB_RUN_DATA__ payload (the data contract) and compile the embedded viewer bundle to catch syntax
// regressions in the refactor, without coupling to render internals.
//
// Run: `bun test app/run-report-core.test.ts` from the web/ directory.
import { afterEach, describe, expect, test } from "bun:test";

// Tests exercise the TypeScript SOURCE directly (bun strips types in memory); the packaged
// run-report-core.js artifact is exercised end-to-end by RunReportGeneratorTest's bun-subprocess
// test, which loads it from the JAR classpath. Loaded via ESM import (not require): the module
// graph embeds the prebuilt viewer script through a bun macro, and bun 1.3.14's sync CJS loader
// spins forever on a require()'d graph that combines a macro import with sibling imports.
import * as RUN_REPORT_CORE_MODULE from "./run-report-core";
import { mergeWebHierarchyBounds, traceToolCallCount } from "./run-report-extract";
import { hitTestNode, inspectorDetailsHtml, inspectorModel, inspectorRectsHtml, inspectorTreeHtml } from "./run-report-inspector";
import { whenDocumentComplete } from "./run-report-viewer";
// A real captured web hierarchy (405 nodes, both parallel trees), scrubbed of page content — see
// its _source note. Excluded from the packaged JAR alongside the other test fixtures.
import webMergeFixture from "./web-hierarchy-merge-fixtures.json";

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
  buildExportSchedule: (rows: Array<{ ts?: number | null; ms?: number | null }>, video: unknown) => { mode: string; clock0: number | null; offsets: number[]; totalMs: number; video: unknown; haveTs: boolean; lo: number; hi: number; clockAnchors?: number[] | null };
  exportGapMs: (gap: number) => number;
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

type ViewerOptions = { session?: number; step?: number; clickGroup?: number; toggleKids?: number; routeStep?: number; query?: string; legacyHash?: string; protocol?: string; copyLink?: boolean; clipboardRejects?: boolean; tab?: string; toggleCell?: string; lightboxAll?: boolean; galZoom?: number[]; zoomShot?: string; zoomKey?: "ArrowLeft" | "ArrowRight"; timelineKey?: "ArrowLeft" | "ArrowRight" | "ArrowUp" | "ArrowDown"; timelineKeyTarget?: string; tlStream?: number; tlStreamBeforeTab?: number; spaceOnStep?: number; timelineScrollTop?: number; focusedStep?: number; focusedTlStream?: number; llmEnter?: number; llmClick?: number; openTx?: number; txEscape?: boolean; inspect?: number; popstate?: string; transport?: "prev" | "next"; stackedTimeline?: boolean; shotLayoutShift?: boolean; copyLocalPrompt?: boolean; exportLogs?: boolean; pointerDown?: "outside" | "insideTimelineMenu"; viewer?: () => void; drive?: (ctx: PlaybackDriveContext) => void; payloadViaGlobal?: boolean; sprites?: Record<string, string[]>; deferBoot?: boolean; rebootViewer?: boolean; shellDocument?: boolean; chunks?: { index: string; sessions: Record<string, string>; sprites: Record<string, string> }; holdChunks?: number[]; holdSpriteChunks?: number[]; streamingChunks?: number[]; loadingDocument?: boolean };

function renderViewerState(payload: unknown, opts: ViewerOptions = {}): { html: string; htmlBeforeBoot: string; liveHtml: () => string; readHtml: () => string; timelineScrollTop: number; mainScrollTop: number; restoredFocus: string | null; route: string; zoomSrc: string | null; zoomRoot: any; copiedText: string | null; copyBtnText: () => string; timelineMenuOpen: boolean; spriteMeasures: Array<{ src: string; fireLoad: (naturalWidth: number) => void }>; tlvframeStyle: Record<string, string>; releaseChunks: () => void; partialChunkReads: () => number; loadingProgressWrites: () => number; settleDocument: () => void; documentKeyListeners: Array<(e: any) => void>; autoplayMarker: () => string | undefined; llmScrolledTo: string | null; llmRow: (i: number) => any; readRestoredFocus: () => string | null } {
  const handlers: { session: Record<string, () => void>; tab: Record<string, () => void>; step: Record<string, () => void>; group: Record<string, () => void>; kids: Record<string, (e: any) => void>; stepKey: Record<string, (e: any) => void>; shot: Record<string, () => void>; tlStream: Record<string, () => void>; cellToggle: Record<string, (e: any) => void>; galZoom: Record<string, () => void>; llmKey: Record<string, (e: any) => void>; llmClick: Record<string, () => void>; txOpen: Record<string, () => void>; inspect: Record<string, () => void>; documentKey?: (e: any) => void; timelinePlay?: () => void; gridMode?: () => void; prev?: () => void; next?: () => void; shotLoad?: () => void; copyLocalPrompt?: () => void; copyLink?: () => void; exportLogs?: () => void } = { session: {}, tab: {}, step: {}, group: {}, kids: {}, stepKey: {}, shot: {}, tlStream: {}, cellToggle: {}, galZoom: {}, llmKey: {}, llmClick: {}, txOpen: {}, inspect: {} };
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
  // Persistent per-request-table-row stand-ins (the LLM tab): activation highlights the row IN
  // PLACE (classList.toggle + aria-current) and opens the transcript lightbox — no re-render — so
  // the same objects must be visible to both the wire pass and the assertions.
  let llmScrolledTo: string | null = null;
  const llmRowEls = new Map<string, any>();
  const llmRowEl = (id: string) => {
    if (!llmRowEls.has(id)) {
      const el: any = {
        dataset: { llm: id },
        classes: new Set<string>(),
        attrs: {} as Record<string, string>,
        classList: { toggle(c: string, on: boolean) { if (on) el.classes.add(c); else el.classes.delete(c); } },
        setAttribute(name: string, value: string) { el.attrs[name] = value; },
        removeAttribute(name: string) { delete el.attrs[name]; },
        focus: () => { restoredFocus = `[data-llm="${id}"]`; },
        scrollIntoView: () => { llmScrolledTo = `[data-llm="${id}"]`; },
      };
      Object.defineProperty(el, "onclick", { set(fn: () => void) { handlers.llmClick[id] = fn; } });
      Object.defineProperty(el, "onkeydown", { set(fn: (e: any) => void) { handlers.llmKey[id] = fn; } });
      llmRowEls.set(id, el);
    }
    return llmRowEls.get(id);
  };
  const scrubEl: any = { attrs: {} as Record<string, string>, setAttribute(name: string, value: string) { this.attrs[name] = value; } };
  const shotWrap: any = { querySelectorAll: () => [], insertAdjacentHTML() {} };
  const shotImg: any = { src: "", alt: "" };
  // Persistent transport stand-ins so drive tests observe the in-place `.disabled` paints between
  // full renders. `prev` starts disabled, mirroring the full render parked on the first row.
  const prevBtn: any = { disabled: true, set onclick(fn: () => void) { handlers.prev = fn; } };
  const nextBtn: any = { disabled: false, set onclick(fn: () => void) { handlers.next = fn; } };
  // The loading view's progress note, seeded from the rendered markup like the real node and reset
  // by each render. Writes are counted because it sits in a role=status live region: assigning the
  // same sentence back would have a screen reader announce it again on every poll turn.
  let progressText: string | null = null;
  let progressWrites = 0;
  const progressNote: any = {
    get textContent() { return progressText; },
    set textContent(v: string) { progressWrites++; progressText = v; },
  };
  const app: any = {
    _h: "",
    set innerHTML(v: string) { this._h = v; timelineList.scrollTop = 0; renders++; progressText = null; },
    get innerHTML() { return this._h; },
    querySelectorAll(sel: string) {
      if (sel === "[data-session]") return [...this._h.matchAll(/data-session="(\d+)"/g)].map((m: any) => ({ dataset: { session: m[1] }, set onclick(fn: () => void) { handlers.session[m[1]] = fn; } }));
      if (sel === "[data-tab]") return [...this._h.matchAll(/data-tab="([a-z]+)"/g)].map((m: any) => ({ dataset: { tab: m[1] }, set onclick(fn: () => void) { handlers.tab[m[1]] = fn; } }));
      if (sel === "[data-step]") return [...this._h.matchAll(/data-step="(\d+)"/g)].map((m: any) => ({ dataset: { step: m[1] }, set onclick(fn: () => void) { handlers.step[m[1]] = fn; } }));
      if (sel === "[data-group]") return [...this._h.matchAll(/data-group="(\d+)"/g)].map((m: any) => ({ dataset: { group: m[1] }, set onclick(fn: () => void) { handlers.group[m[1]] = fn; } }));
      if (sel === "[data-kids]") return [...this._h.matchAll(/data-kids="(\d+)" data-open="(\d)"/g)].map((m: any) => ({ dataset: { kids: m[1], open: m[2] }, set onclick(fn: (e: any) => void) { handlers.kids[m[1]] = fn; } }));
      if (sel === "[data-tlstream]") return [...this._h.matchAll(/data-tlstream="(\d+)"/g)].map((m: any) => ({ dataset: { tlstream: m[1] }, set onclick(fn: () => void) { handlers.tlStream[m[1]] = fn; } }));
      if (sel === "[data-shot]") return [...this._h.matchAll(/data-shot="([^"]+)"(?: data-shot-token="([^"]*)")?(?: data-shot-label="([^"]*)")?(?: data-shot-tool="([^"]*)")?/g)].map((m: any) => ({ dataset: { shot: m[1], shotToken: m[2], shotLabel: m[3], shotTool: m[4] }, set onclick(fn: () => void) { handlers.shot[m[1]] = fn; } }));
      if (sel === "[data-cell-toggle]") return [...this._h.matchAll(/data-cell-toggle="([^"]+)"/g)].map((m: any) => ({ dataset: { cellToggle: m[1] }, set onclick(fn: (e: any) => void) { handlers.cellToggle[m[1]] = fn; }, set onkeydown(_fn: unknown) {} }));
      if (sel === "[data-gal-zoom]") return [...this._h.matchAll(/data-gal-zoom="(-?\d+)"/g)].map((m: any) => ({ dataset: { galZoom: m[1] }, set onclick(fn: () => void) { handlers.galZoom[m[1]] = fn; } }));
      if (sel === "[data-llm]") return [...this._h.matchAll(/data-llm="(\d+)"/g)].map((m: any) => llmRowEl(m[1]));
      // Transcript-dialog triggers: clicking passes the element itself as the focus-return target,
      // so `focus()` records where close puts the reader back.
      if (sel === "[data-tx]") return [...this._h.matchAll(/data-tx="(\d+)"/g)].map((m: any) => {
        // Fresh node per wire pass, mirroring the real DOM: every render() replaces this markup, so
        // a node captured on open is detached by the next one. Focus landing HERE (rather than on
        // the node querySelector resolves) is the stale-reference bug.
        const el: any = { dataset: { tx: m[1] }, focus: () => { restoredFocus = `[data-tx="${m[1]}"] (captured)`; } };
        Object.defineProperty(el, "onclick", { set(fn: (e: any) => void) { handlers.txOpen[m[1]] = () => fn({ stopPropagation() {} }); } });
        return el;
      });
      if (sel === "[data-inspect]") return [...this._h.matchAll(/data-inspect="(\d+)"/g)].map((m: any) => ({ dataset: { inspect: m[1] }, set onclick(fn: () => void) { handlers.inspect[m[1]] = fn; } }));
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
      if (sel === "[data-run-loading-progress]" && this._h.includes("data-run-loading-progress")) {
        if (progressText === null) progressText = (this._h.match(/data-run-loading-progress>([^<]*)</) || [])[1] || "";
        return progressNote;
      }
      const step = sel.match(/^\[data-step="(\d+)"\]$/);
      if (step && this._h.includes(`data-step="${step[1]}"`)) return stepEl(step[1]);
      const tlStream = sel.match(/^\[data-tlstream="(\d+)"\]$/);
      if (tlStream && this._h.includes(`data-tlstream="${tlStream[1]}"`)) return { focus: () => { restoredFocus = sel; } };
      const llmRow = sel.match(/^\[data-llm="(\d+)"\]$/);
      if (llmRow && this._h.includes(`data-llm="${llmRow[1]}"`)) return llmRowEl(llmRow[1]);
      // The live (currently-rendered) transcript trigger, re-resolved at dialog-close time.
      const txBtn = sel.match(/^\[data-tx="(\d+)"\]$/);
      if (txBtn && this._h.includes(`data-tx="${txBtn[1]}"`)) return { focus: () => { restoredFocus = sel; } };
      // Likewise the live "Inspect UI" trigger, re-resolved when the inspector closes.
      const inspectBtn = sel.match(/^\[data-inspect="(\d+)"\]$/);
      if (inspectBtn && this._h.includes(`data-inspect="${inspectBtn[1]}"`)) return { focus: () => { restoredFocus = sel; } };
      // Each render produces fresh <details> markup; refresh the shim's open state from the html
      // without firing ontoggle, mimicking a newly-created element.
      if (sel === "[data-streamselect]" && this._h.includes("data-streamselect")) { timelineMenu._open = this._h.includes("data-streamselect open"); return timelineMenu; }
      return null;
    },
  };
  (globalThis as Record<string, unknown>).window = globalThis;
  // window-level listeners (the viewer registers exactly one: popstate), captured so a test can
  // fire browser Back.
  const popstateListeners: Array<() => void> = [];
  (globalThis as Record<string, unknown>).addEventListener = (name: string, fn: () => void) => {
    if (name === "popstate") popstateListeners.push(fn);
  };
  (globalThis as Record<string, unknown>).removeEventListener = (name: string, fn: () => void) => {
    if (name !== "popstate") return;
    const at = popstateListeners.indexOf(fn);
    if (at >= 0) popstateListeners.splice(at, 1);
  };
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
  // The element inside an overlay that currently holds focus (null once it's been detached by a
  // markup rewrite) — what document.activeElement reports while an overlay is open.
  let overlayFocus: any = null;
  const copyBtn: any = { textContent: "", set onclick(fn: () => void) { handlers.copyLink = fn; } };
  // An element inside an overlay's parsed markup: enough of a real node to observe in-place paints
  // (class toggles, textContent/innerHTML writes, focus). `detached` models what a real innerHTML
  // rewrite does to nodes built from the previous markup — the browser drops focus off them, so a
  // test can tell an in-place update from a rebuild.
  const overlayChild = (attrs: Record<string, string>, className: string, onFocus: (el: any) => void) => {
    const el: any = {
      classes: new Set(className.split(/\s+/).filter(Boolean)),
      dataset: {} as Record<string, string>,
      style: {} as Record<string, string>,
      detached: false,
      textContent: "",
      _h: "",
      // The nearest ancestor <details> branch (wired by the overlay's innerHTML parse for tree
      // rows) — what reveal-on-select expands.
      _branch: null,
      set innerHTML(v: string) { el._h = v; },
      get innerHTML() { return el._h; },
      get className() { return [...el.classes].join(" "); },
      classList: {
        add: (c: string) => el.classes.add(c),
        remove: (c: string) => el.classes.delete(c),
        contains: (c: string) => el.classes.has(c),
        toggle: (c: string, force?: boolean) => { const on = force == null ? !el.classes.has(c) : force; if (on) el.classes.add(c); else el.classes.delete(c); return on; },
      },
      focus: () => onFocus(el),
      closest: (sel: string) => (sel === "details" ? el._branch : el.matches(sel) ? el : null),
      matches: (sel: string) => {
        const attr = sel.match(/^\[([a-z-]+)(?:="([^"]*)")?\]$/);
        if (attr) return attrs[attr[1]] != null && (attr[2] == null || attrs[attr[1]] === attr[2]);
        return sel.startsWith(".") ? el.classes.has(sel.slice(1)) : false;
      },
      querySelector: (sel: string) => (sel === "img" ? { getBoundingClientRect: () => ({ left: 0, top: 0, width: 100, height: 200, right: 100, bottom: 200 }) } : null),
      getBoundingClientRect: () => ({ left: 0, top: 0, width: 100, height: 200, right: 100, bottom: 200 }),
      scrollIntoView: (arg: unknown) => { el.scrolledIntoView = arg; },
    };
    Object.keys(attrs).forEach((name) => {
      const m = name.match(/^data-(.+)$/);
      if (m) el.dataset[m[1].replace(/-([a-z])/g, (_s, c) => c.toUpperCase())] = attrs[name];
    });
    return el;
  };
  const createElement = (tag: string) => {
    const node: any = {
      children: [], style: {}, className: "", textContent: "", disabled: false, removed: false, attrs: {} as Record<string, string>, _els: [] as any[], scrollTop: 0,
      appendChild(child: any) { this.children.push(child); },
      setAttribute(name: string, value: string) { this.attrs[name] = value; }, insertAdjacentHTML() {}, remove() { this.removed = true; }, focus() {}, click() {},
      set src(value: string) { this._src = value; if (tag === "img") zoomSrc = value; },
      get src() { return this._src; },
      // Setting innerHTML re-parses the overlay's markup into fresh child stand-ins — the elements
      // built from the previous markup are detached, exactly as a real rewrite would leave them.
      set innerHTML(html: string) {
        node._h = html;
        node._els.forEach((el: any) => { el.detached = true; });
        node._els = [];
        const push = (attrs: Record<string, string>, className: string) => { const el = overlayChild(attrs, className, (el2) => { overlayFocus = el2; }); node._els.push(el); return el; };
        // The tree's <details> nesting, parsed sequentially so each row knows its nearest branch —
        // a details stand-in carries `open` plus a parent link, the shape reveal-on-select walks.
        const branchOf: Record<string, any> = {};
        {
          const stack: any[] = [];
          for (const t of html.matchAll(/<details class="inspbranch"( open)?|<\/details>|data-inspnode="(\d+)"/g)) {
            if (t[0].startsWith("<details")) {
              const d: any = { open: !!t[1], _parent: stack[stack.length - 1] || null };
              d.parentElement = { closest: (sel: string) => (sel === "details" ? d._parent : null) };
              stack.push(d);
            } else if (t[0] === "</details>") stack.pop();
            else branchOf[t[2]] = stack[stack.length - 1] || null;
          }
        }
        [...html.matchAll(/<(?:span|div)\s+class="([^"]*)"\s+data-inspnode="(\d+)"/g)].forEach((m) => { push({ "data-inspnode": m[2] }, m[1])._branch = branchOf[m[2]] || null; });
        [...html.matchAll(/<div class="([^"]*)" data-insprect="(\d+)"/g)].forEach((m) => push({ "data-insprect": m[2] }, m[1]));
        [...html.matchAll(/<div class="(inspdetails|insptree)"/g)].forEach((m) => push({}, m[1]));
        [...html.matchAll(/<div class="(inspselectors)" (data-inspselectors)/g)].forEach((m) => push({ "data-inspselectors": "" }, m[1]));
        [...html.matchAll(/<div class="(inspselvizlayer)" (data-inspselvizlayer)/g)].forEach((m) => push({ "data-inspselvizlayer": "" }, m[1]));
        [...html.matchAll(/<div class="(inspshotwrap)" (data-insphit)/g)].forEach((m) => push({ "data-insphit": "" }, m[1]));
        [...html.matchAll(/<span class="([^"]*)" (data-insphovlabel)/g)].forEach((m) => push({ "data-insphovlabel": "" }, m[1]));
      },
      get innerHTML() { return node._h || ""; },
      querySelectorAll(sel: string) { return node._els.filter((el: any) => el.matches(sel)); },
      querySelector(sel: string) { return node._els.find((el: any) => el.matches(sel)) || null; },
    };
    return node;
  };
  // Chunked-layout delivery (opts.chunks, extracted from real builder output by chunksOf): serve
  // #tb-index plus per-session chunks; opts.holdChunks / opts.holdSpriteChunks list session
  // indices whose #tb-session / #tb-sprites chunk hasn't "streamed in" yet — releaseChunks()
  // (returned below) makes them appear, the way the parser would as the document tail downloads.
  // opts.streamingChunks models the state IN BETWEEN, which is what a real browser shows for most
  // of a big report's download: the parser has seen the chunk's start tag, so the element exists
  // and its text keeps growing, but the `</script>` end tag (and with it `nextSibling`) hasn't
  // landed. Reads of that partial text are counted, since the viewer must not keep parsing it.
  const heldChunks = new Set((opts.holdChunks || []).map(String));
  const heldSpriteChunks = new Set((opts.holdSpriteChunks || []).map(String));
  const streamingChunks = new Set((opts.streamingChunks || []).map(String));
  let partialChunkReads = 0;
  let documentLoading = !!opts.loadingDocument;
  // A chunk the parser has closed: the next node after it exists.
  const closedChunk = (textContent: string) => ({ textContent, nextSibling: {} });
  const chunkElement = (id: string) => {
    if (!opts.chunks) return null;
    if (id === "tb-index") return closedChunk(opts.chunks.index);
    const session = id.match(/^tb-session-(\d+)$/);
    if (session) {
      const text = opts.chunks.sessions[session[1]];
      if (text == null || heldChunks.has(session[1])) return null;
      if (!streamingChunks.has(session[1])) return closedChunk(text);
      return { get textContent() { partialChunkReads++; return text.slice(0, Math.floor(text.length / 2)); }, nextSibling: null };
    }
    const sprites = id.match(/^tb-sprites-(\d+)$/);
    if (sprites) return opts.chunks.sprites[sprites[1]] != null && !heldSpriteChunks.has(sprites[1]) ? closedChunk(opts.chunks.sprites[sprites[1]]) : null;
    return null;
  };
  // Every keydown listener currently registered on the document, in registration order — a viewer
  // that boots twice into ONE document must leave exactly one behind (disposeViewerGlobals).
  const documentKeyListeners: Array<(e: any) => void> = [];
  // Hoisted so a test can read back the capture-framing marker autoplay stamps on it.
  const documentElement = { dataset: {} as Record<string, string>, hasAttribute: (name: string) => name === "data-tb-shell" && !!opts.shellDocument };
  (globalThis as Record<string, unknown>).document = {
    get activeElement() { return overlayFocus && !overlayFocus.detached ? overlayFocus : activeElement; },
    // While a held chunk is pending the document reads as still loading, so the viewer keeps
    // polling instead of giving up on hydration. opts.loadingDocument models the same thing without
    // chunk plumbing (the document tail — where the selector-engine chunk rides — still streaming);
    // settleDocument() below is the "tail arrived" edge.
    get readyState() { return documentLoading || heldChunks.size || heldSpriteChunks.size || streamingChunks.size ? "loading" : undefined; },
    getElementById: (id: string) => (opts.chunks && chunkElement(id))
      || (id === "app" ? app
      : id === "tb-run-data" && !opts.payloadViaGlobal && !opts.chunks ? { textContent: dataJson }
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
      : null),
    // The viewer's boot asks whether this document is a viewer shell (no payload yet, loader chrome
    // in place) before deciding to auto-boot.
    documentElement,
    addEventListener: (name: string, fn: (e: any) => void) => {
      if (name !== "keydown") return;
      handlers.documentKey = fn;
      documentKeyListeners.push(fn);
    },
    removeEventListener: (name: string, fn: (e: any) => void) => {
      if (name !== "keydown") return;
      const at = documentKeyListeners.indexOf(fn);
      if (at >= 0) documentKeyListeners.splice(at, 1);
    },
    createElement,
    body: { appendChild(el: any) { zoomRoot = el; } },
  };
  // Every keydown listener currently registered on the document, in registration order. A viewer
  // that boots twice into one document must leave exactly one behind (see disposeViewerGlobals).
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
  // A second boot into the same document — what the viewer shell does when another archive is
  // loaded in place.
  if (opts.rebootViewer) { (opts.viewer || core.RUN_REPORT_VIEWER)(); while (rafQueue.length) rafQueue.shift()!(); }
  if (opts.toggleCell && handlers.cellToggle[opts.toggleCell]) handlers.cellToggle[opts.toggleCell]({ stopPropagation() {} });
  if (opts.session != null && handlers.session[String(opts.session)]) handlers.session[String(opts.session)]();
  if (opts.timelineScrollTop != null) timelineList.scrollTop = opts.timelineScrollTop;
  if (opts.step != null && handlers.step[String(opts.step)]) handlers.step[String(opts.step)]();
  if (opts.clickGroup != null && handlers.group[String(opts.clickGroup)]) handlers.group[String(opts.clickGroup)]();
  if (opts.toggleKids != null && handlers.kids[String(opts.toggleKids)]) handlers.kids[String(opts.toggleKids)]({ preventDefault() {}, stopPropagation() {} });
  if (opts.tlStreamBeforeTab != null && handlers.tlStream[String(opts.tlStreamBeforeTab)]) handlers.tlStream[String(opts.tlStreamBeforeTab)]();
  if (opts.tab && handlers.tab[opts.tab]) handlers.tab[opts.tab]();
  if (opts.openTx != null && handlers.txOpen[String(opts.openTx)]) handlers.txOpen[String(opts.openTx)]();
  if (opts.txEscape && zoomRoot && zoomRoot.onkeydown) zoomRoot.onkeydown({ key: "Escape", preventDefault() {}, stopPropagation() {} });
  if (opts.llmEnter != null && handlers.llmKey[String(opts.llmEnter)]) handlers.llmKey[String(opts.llmEnter)]({ key: "Enter", preventDefault() {} });
  if (opts.llmClick != null && handlers.llmClick[String(opts.llmClick)]) handlers.llmClick[String(opts.llmClick)]();
  // Browser Back/Forward: point the address at another route and fire the viewer's popstate
  // listener, exactly as the browser would after a history pop.
  if (opts.popstate != null) {
    navigate(`/report.html${opts.popstate}`);
    popstateListeners.forEach((fn) => fn());
  }
  if (opts.lightboxAll && handlers.gridMode) handlers.gridMode();
  if (opts.galZoom) for (const delta of opts.galZoom) { const fn = handlers.galZoom[String(delta)]; if (fn) fn(); }
  if (opts.zoomShot && handlers.shot[opts.zoomShot]) handlers.shot[opts.zoomShot]();
  if (opts.inspect != null && handlers.inspect[String(opts.inspect)]) handlers.inspect[String(opts.inspect)]();
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
  return { html: app._h, htmlBeforeBoot, liveHtml: () => app._h as string, readHtml: () => app._h as string, timelineScrollTop: timelineList.scrollTop, mainScrollTop: mainScroller.scrollTop, restoredFocus, route, zoomSrc, zoomRoot, copiedText, copyBtnText: () => copyBtn.textContent as string, timelineMenuOpen: timelineMenu.open, spriteMeasures, tlvframeStyle: tlvframeNode.style, releaseChunks: () => { heldChunks.clear(); heldSpriteChunks.clear(); streamingChunks.clear(); }, partialChunkReads: () => partialChunkReads, loadingProgressWrites: () => progressWrites, settleDocument: () => { documentLoading = false; }, documentKeyListeners, autoplayMarker: () => documentElement.dataset.tbAutoplay, llmScrolledTo, llmRow: (i: number) => llmRowEl(String(i)), readRestoredFocus: () => restoredFocus };
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

// Extract the chunked layout's inert JSON scripts — the #tb-index boot chunk plus the raw text of
// every per-session #tb-session-<i> / #tb-sprites-<i> chunk — so tests can assert the layout and
// the harness can serve the REAL builder output through the fake DOM.
function chunksOf(html: string): { index: string; sessions: Record<string, string>; sprites: Record<string, string> } {
  const index = html.match(/<script type="application\/json" id="tb-index">([\s\S]*?)<\/script>/);
  if (!index) throw new Error("no tb-index block in report HTML");
  const sessions: Record<string, string> = {};
  const sprites: Record<string, string> = {};
  for (const m of html.matchAll(/<script type="application\/json" id="tb-(session|sprites)-(\d+)">([\s\S]*?)<\/script>/g)) {
    (m[1] === "session" ? sessions : sprites)[m[2]] = m[3];
  }
  return { index: index[1], sessions, sprites };
}

// Pull the embedded JSON payload back out of a generated report so we can assert the data
// contract. The chunked layout splits it (#tb-index stubs + one #tb-session-<i> chunk per run —
// exactly what hydrateSession assembles when a run opens); reassemble the logical whole here.
function payloadOf(html: string): { generatedAt: string; sessions: Array<Record<string, any>> } {
  const chunks = chunksOf(html);
  const payload = JSON.parse(chunks.index);
  payload.sessions = payload.sessions.map((stub: Record<string, any>, i: number) => {
    const chunk = chunks.sessions[String(i)];
    if (!chunk) throw new Error(`no tb-session-${i} block in report HTML`);
    return { ...stub, ...JSON.parse(chunk) };
  });
  return payload;
}

// The hoisted sprite chunks (session index → sprite data URI array) the viewer resolves lazily.
function spritesOf(html: string): Record<string, string[]> {
  const { sprites } = chunksOf(html);
  const out: Record<string, string[]> = {};
  for (const [key, text] of Object.entries(sprites)) out[key] = JSON.parse(text);
  return out;
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

  test("surfaces the tool calls the traceId fold merged in as children", () => {
    // A traceId is allocated per LLM request (one turn's tool batch), not per tool call, so a turn's
    // whole batch shares one traceId and folds onto its first tool. Without children, the other calls
    // are absent from the payload entirely and the fold increments no count to reveal it.
    const tool = (name: string, raw: Record<string, unknown>, s: number) => ({
      class: `${T}.TrailblazeToolLog`, toolName: name, traceId: "obj8", successful: true,
      durationMs: 10, trailblazeTool: { raw }, timestamp: `2024-01-01T00:00:0${s}Z`,
    });
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Edit the end time" }, timestamp: "2024-01-01T00:00:00Z" },
      tool("assertVisibleBySelector", { selector: { text: "End time" } }, 1),
      { class: `${T}.MaestroDriverLog`, traceId: "obj8", action: { class: "xyz.AgentDriverAction.TapPoint", x: 1, y: 2 }, deviceWidth: 10, deviceHeight: 20, timestamp: "2024-01-01T00:00:02Z" },
      tool("tapOnElementBySelector", { selector: { text: "End time" } }, 3),
      tool("swipe", { swipeOnElementText: "00 minutes" }, 4),
      tool("mobile_maestro", { commands: "tapOn 50%,91%" }, 5),
    ];
    const trace = core.extractTrace(logs);
    // Still one folded row per traceId — this fix adds detail, it does not split the row.
    const row = trace.find((r) => r.label === "assertVisibleBySelector");
    expect(trace.filter((r) => !r.objective).length).toBe(1);
    // The three calls that actually did the work are now followable.
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label))
      .toEqual(["tapOnElementBySelector", "swipe", "mobile_maestro"]);
    // Device actions stay folded: the row already names the action, so they are not children.
    expect((row.children as unknown[]).length).toBe(3);
    // And they survive the share slimming — the standalone report renders from the slimmed shape.
    const slim = (core as any).slimTraceForShare(trace);
    expect(slim.find((r: any) => r.label === "assertVisibleBySelector").children.map((c: any) => c.label))
      .toEqual(["tapOnElementBySelector", "swipe", "mobile_maestro"]);
  });

  test("a delegating tool's executor is one child, not one per source", () => {
    // On-device instrumentation logs the DelegatingTrailblazeToolLog and, under the same traceId,
    // the executor's own TrailblazeToolLog (TrailCommand.kt:1836) — so it arrives twice.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap the row" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.DelegatingTrailblazeToolLog`, toolName: "tapOnElementWithNodeId", traceId: "objD",
        trailblazeTool: { toolName: "tapOnElementWithNodeId", raw: { nodeId: 7 } },
        executableTools: [{ toolName: "tapOnElementBySelector", raw: { selector: { text: "Row" } } }],
        timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objD", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "Row" } } }, timestamp: "2024-01-01T00:00:02Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "swipe", traceId: "objD", successful: true,
        durationMs: 10, trailblazeTool: { raw: { swipeOnElementText: "list" } }, timestamp: "2024-01-01T00:00:03Z",
      },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "tapOnElementWithNodeId");
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label))
      .toEqual(["tapOnElementBySelector", "swipe"]);
  });

  test("a delegating wrapper folded mid-objective is not a child alongside its executor", () => {
    // The wrapper can arrive at any position in the batch, not just first. It is a dispatch record,
    // not a step — SessionCombinedView.kt:893 and TrailblazeRecordingGenerator.kt:211 both skip it.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Check then tap" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objM", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "Row" } } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.DelegatingTrailblazeToolLog`, toolName: "tapOnElementWithNodeId", traceId: "objM",
        trailblazeTool: { toolName: "tapOnElementWithNodeId", raw: { nodeId: 7 } },
        executableTools: [{ toolName: "tapOnElementBySelector", raw: { selector: { text: "Row" } } }],
        timestamp: "2024-01-01T00:00:02Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objM", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "Row" } } }, timestamp: "2024-01-01T00:00:03Z",
      },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "assertVisibleBySelector");
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label))
      .toEqual(["tapOnElementBySelector"]);
  });

  test("a delegating tool whose executor never logged still shows what it dispatched", () => {
    // The fallback that keeps the dedupe from hiding work: some tools route around the device's
    // tool-log emit site (HostOnDeviceRpcTrailblazeAgent.kt:743), so only the declaration exists.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap by ref" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objF", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "Row" } } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.DelegatingTrailblazeToolLog`, toolName: "tap", traceId: "objF",
        trailblazeTool: { toolName: "tap", raw: { ref: "z639" } },
        executableTools: [{ toolName: "tapOnElementBySelector", raw: { selector: { text: "Row" } } }],
        timestamp: "2024-01-01T00:00:02Z",
      },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "assertVisibleBySelector");
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label))
      .toEqual(["tapOnElementBySelector"]);
  });

  test("repeated polls keep their ×N count instead of becoming N children", () => {
    // The assertion fold already annotates the row, so expanding it would trade a readable count
    // for noise. Only the silent tool-into-tool fold gets children.
    const poll = (s: number) => ({
      class: `${T}.MaestroDriverLog`, durationMs: 5, deviceWidth: 10, deviceHeight: 20,
      action: { class: "xyz.AgentDriverAction.AssertCondition", conditionDescription: "shows 5:00 PM", succeeded: true, x: 1, y: 1 },
      timestamp: `2024-01-01T00:00:${String(s).padStart(2, "0")}Z`,
    });
    const trace = core.extractTrace([poll(1), poll(2), poll(3)]);
    expect(trace.length).toBe(1);
    expect(trace[0].note).toBe("×3");
    expect(trace[0].children).toBeUndefined();
  });

  test("an MCP tool's response log is not a child of itself", () => {
    // McpToolCallRequestLog / McpToolCallResponseLog share one traceId and the same toolName
    // (TrailblazeMcpServer.kt:1615), so folding on "anything with a toolName" would nest the row's
    // own tool under itself. Only a TrailblazeToolLog is an executed child.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Connect the device" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.McpToolCallRequestLog`, toolName: "trailblaze_connect_device", traceId: "mcp1", timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.McpToolCallResponseLog`, toolName: "trailblaze_connect_device", traceId: "mcp1", timestamp: "2024-01-01T00:00:02Z" },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "trailblaze_connect_device");
    expect(row).toBeDefined();
    expect(row.children).toBeUndefined();
  });

  test("a repeated primitive with one unlogged dispatch still shows the dispatched call", () => {
    // One tapOnElementBySelector logged its executor; a second (different selector) was dispatched
    // via a delegating wrapper whose executor never logged. A name-only dedupe drops the second as
    // "already ran"; matching on name AND args keeps it, so the dispatched-but-unlogged call shows.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap two rows" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objP", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "Header" } } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objP", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "First" } } }, timestamp: "2024-01-01T00:00:02Z",
      },
      {
        class: `${T}.DelegatingTrailblazeToolLog`, toolName: "tap", traceId: "objP",
        trailblazeTool: { toolName: "tap", raw: { ref: "z2" } },
        executableTools: [{ toolName: "tapOnElementBySelector", raw: { selector: { text: "Second" } } }],
        timestamp: "2024-01-01T00:00:03Z",
      },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "assertVisibleBySelector");
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label))
      .toEqual(["tapOnElementBySelector", "tapOnElementBySelector"]);
    // The args distinguish them: both dispatches survive, not just the one that logged.
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.tool))
      .toEqual(["text: First", "text: Second"]);
  });

  test("children render in dispatch order, not declarations-first", () => {
    // swipe ran and logged first; a later delegating wrapper dispatched tapOnElementBySelector whose
    // executor never logged. Concatenating declarations ahead of executions would list the tap first
    // even though the swipe happened first — order children by log position instead.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Swipe then tap by ref" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objO", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "List" } } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "swipe", traceId: "objO", successful: true,
        durationMs: 10, trailblazeTool: { raw: { swipeOnElementText: "list" } }, timestamp: "2024-01-01T00:00:02Z",
      },
      {
        class: `${T}.DelegatingTrailblazeToolLog`, toolName: "tap", traceId: "objO",
        trailblazeTool: { toolName: "tap", raw: { ref: "z3" } },
        executableTools: [{ toolName: "tapOnElementBySelector", raw: { selector: { text: "Row" } } }],
        timestamp: "2024-01-01T00:00:03Z",
      },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "assertVisibleBySelector");
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label))
      .toEqual(["swipe", "tapOnElementBySelector"]);
  });

  test("a ref dispatch that reuses the row's own primitive name is not filtered as self", () => {
    // logs[0] is a directly-invoked tapOnElementBySelector, so it labels the row. A later ref-based
    // tap resolves to the same primitive with a DIFFERENT selector and its executor never logged.
    // Filtering every declaration named like the row would drop this genuine second call; the
    // self-filter must key on the row's own name AND args, not the name alone.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap one directly, one by ref" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objS", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "First" } } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.DelegatingTrailblazeToolLog`, toolName: "tap", traceId: "objS",
        trailblazeTool: { toolName: "tap", raw: { ref: "z9" } },
        executableTools: [{ toolName: "tapOnElementBySelector", raw: { selector: { text: "Second" } } }],
        timestamp: "2024-01-01T00:00:02Z",
      },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "tapOnElementBySelector");
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label)).toEqual(["tapOnElementBySelector"]);
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.tool)).toEqual(["text: Second"]);
  });

  test("children carry duration and outcome, and consecutive identical dispatches fold to ×N", () => {
    // A composite scripted tool (a trailhead's UI sign-in) dispatches the same primitive dozens of
    // times in a row under one traceId. N identical unannotated lines hid both where the time went
    // and which dispatch failed; the fold keeps the list scannable, the per-child ms/ok keep it
    // dissectible, and a failed dispatch is never absorbed into a green ×N.
    const maestro = (s: number, extra: Record<string, unknown> = {}) => ({
      class: `${T}.TrailblazeToolLog`, toolName: "mobile_maestro", traceId: "th1", successful: true,
      durationMs: 100, trailblazeTool: { raw: { commands: [{ tapOn: { text: "Next" } }] } },
      timestamp: `2024-01-01T00:00:0${s}Z`, ...extra,
    });
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch signed in", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "demo_signedInToClientRoute", traceId: "th1", successful: true,
        durationMs: 5000, trailblazeTool: { raw: { startingClientRoute: "/dl/view/activity", account: "user@example.com", flags: { newHome: true } } }, timestamp: "2024-01-01T00:00:01Z",
      },
      maestro(2), maestro(3), maestro(4),
      {
        class: `${T}.TrailblazeToolLog`, toolName: "exec", traceId: "th1", successful: true,
        durationMs: 40, trailblazeTool: { raw: { argv: ["adb", "shell", "am", "broadcast"], timeoutSeconds: 30 } }, timestamp: "2024-01-01T00:00:05Z",
      },
      maestro(6, { successful: false, durationMs: 900, exceptionMessage: "Element not found: Next", errorPayload: { schema: "example-repo/trailhead-error/v1", code: "navigation", ticket: "TICKET-123" } }),
    ];
    const trace = core.extractTrace(logs);
    const row = trace.find((r) => r.label === "demo_signedInToClientRoute");
    const kids = row.children as Array<Record<string, unknown>>;
    expect(kids.map((c) => [c.label, c.count, c.ms, c.ok])).toEqual([
      ["mobile_maestro", 3, 300, true],
      ["exec", 1, 40, true],
      ["mobile_maestro", 1, 900, false],
    ]);
    // Structured payloads summarize instead of vanishing: maestro names its commands, exec its argv.
    expect(kids.map((c) => c.tool)).toEqual(["tapOn", "adb shell am broadcast", "tapOn"]);
    // The failed dispatch keeps its error (the JVM log spells it exceptionMessage); passes carry none.
    expect(kids.map((c) => c.err)).toEqual([null, null, "Element not found: Next"]);
    // A structured errorPayload's top-level string `code` rides beside the message; passes carry none.
    expect(kids.map((c) => c.code)).toEqual([null, null, "navigation"]);
    // The composite call keeps ALL its arguments — including the object-valued one the three-key
    // `tool` summary drops — because a trailhead's config is its documentation.
    expect(row.params).toEqual(["startingClientRoute=/dl/view/activity", "account=user@example.com", 'flags={"newHome":true}']);
    // The fold is lossless for the index's tool-call count (5 dispatches + the row itself), and the
    // annotations survive the share slimming the standalone report renders from.
    const slim = core.slimTraceForShare(trace);
    expect(traceToolCallCount(slim)).toBe(6);
    const slimRow = slim.find((r: any) => r.label === "demo_signedInToClientRoute");
    // Slim children keep only fields that carry signal: ms when executed, ok/err only on failure,
    // count only past 1 — the viewer treats each absent field as its default.
    expect(slimRow.children.map((c: any) => [c.count, c.ms, c.ok, c.err, c.code])).toEqual([[3, 300, undefined, undefined, undefined], [undefined, 40, undefined, undefined, undefined], [undefined, 900, false, "Element not found: Next", "navigation"]]);
    expect(slimRow.params).toEqual(row.params);
  });

  test("dispatches whose display summaries collide but whose raw args differ do not fold", () => {
    // The `tool` summary is lossy (a maestro command summarizes to just its command name), so the
    // fold must compare the raw args — otherwise a tap on "Next" and a tap on "Back" collapse into
    // a misleading ×2 of the first.
    const tap = (s: number, text: string) => ({
      class: `${T}.TrailblazeToolLog`, toolName: "mobile_maestro", traceId: "th2", successful: true,
      durationMs: 100, trailblazeTool: { raw: { commands: [{ tapOn: { text } }] } },
      timestamp: `2024-01-01T00:00:0${s}Z`,
    });
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch signed in", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "demo_signedInToClientRoute", traceId: "th2", successful: true,
        durationMs: 5000, trailblazeTool: { raw: { startingClientRoute: "/dl/view/activity" } }, timestamp: "2024-01-01T00:00:01Z",
      },
      tap(2, "Next"), tap(3, "Back"), tap(4, "Back"),
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "demo_signedInToClientRoute");
    expect((row.children as Array<Record<string, unknown>>).map((c) => [c.tool, c.count])).toEqual([["tapOn", 1], ["tapOn", 2]]);
  });

  test("only an object payload's top-level string `code` becomes a child code (Kotlin failureCodeOf twin)", () => {
    // Mirror of FailureCodeOfTest's lift rules: non-object payloads, missing `code`, and
    // non-string `code` values (7, true) all yield null — the chip renders nothing rather
    // than a coerced value the CI classifier would never see.
    const failing = (s: number, errorPayload: unknown) => ({
      class: `${T}.TrailblazeToolLog`, toolName: "mobile_maestro", traceId: "th3", successful: false,
      durationMs: 100, errorMessage: "boom", trailblazeTool: { raw: { commands: [{ tapOn: { text: `s${s}` } }] } },
      timestamp: `2024-01-01T00:00:0${s}Z`, ...(errorPayload !== undefined ? { errorPayload } : {}),
    });
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch signed in", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "demo_signedInToClientRoute", traceId: "th3", successful: true,
        durationMs: 5000, trailblazeTool: { raw: { startingClientRoute: "/x" } }, timestamp: "2024-01-01T00:00:01Z",
      },
      failing(2, { code: "session" }),
      failing(3, { code: 7 }),
      failing(4, { code: true }),
      failing(5, "session"),
      failing(6, ["session"]),
      failing(7, undefined),
      failing(8, { detail: "none" }),
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "demo_signedInToClientRoute");
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.code)).toEqual(["session", null, null, null, null, null, null]);
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
    // The payload rides in inert JSON scripts the viewer JSON.parses, never a JS literal the
    // parser must evaluate before first paint: a tiny #tb-index boot chunk plus one per-session
    // #tb-session-<i> chunk parsed lazily when that run opens.
    expect(html).toContain('<script type="application/json" id="tb-index">');
    expect(html).toContain('<script type="application/json" id="tb-session-0">');
    expect(html).not.toContain("window.__TB_RUN_DATA__ =");
    expect(html).toContain("function RUN_REPORT_VIEWER");
    expect(html).toContain("My run"); // title in <title>
  });

  test("boot never waits on session bytes: loader, index, and viewer all precede the session chunks", () => {
    const boot = html.indexOf('id="tb-boot"');
    expect(boot).toBeGreaterThan(-1);
    expect(boot).toBeLessThan(html.indexOf('id="tb-index"'));
    expect(html.indexOf('id="tb-index"')).toBeLessThan(html.indexOf("function RUN_REPORT_VIEWER"));
    expect(html.indexOf("function RUN_REPORT_VIEWER")).toBeLessThan(html.indexOf('id="tb-session-0"'));
    // Loader carries the run title and is styled from the head CSS (present before it parses).
    expect(html.slice(boot, boot + 300)).toContain("My run");
    expect((core as any).RUN_REPORT_CSS).toContain("#tb-boot");
    expect((core as any).RUN_REPORT_CSS).toContain(".tb-boot-spinner");
  });

  test("the index chunk carries the run list's data but no traces, screenshots, or logs", () => {
    const index = JSON.parse(chunksOf(html).index);
    expect(index.sessions).toHaveLength(1);
    expect(index.sessions[0].meta.title).toBe("My run");
    expect(index.sessions[0].stepCount).toBeGreaterThan(0);
    expect(index.sessions[0].toolCallCount).toBeGreaterThan(0);
    expect(index.sessions[0].trace).toBeUndefined();
    expect(index.sessions[0].shots).toBeUndefined();
    expect(chunksOf(html).index).not.toContain("data:image/png;base64,AAAA");
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

  test("a shell document is not auto-booted, but hands the viewer a way to boot once it has a payload", () => {
    // The viewer shell is a report document with no run in it: the loader chrome owns the page until
    // an archive is loaded. Auto-booting there would paint an empty report over that chrome. The
    // marker is the whole contract, so drive the real embedded bundle, not the module export.
    const selfHtml = core.buildRunReportHtml({ meta: { title: "My run", status: "passed" }, trace, llmLogs: llm, shots: {} });
    const script = viewerScriptOf(selfHtml);
    const globals = globalThis as Record<string, unknown>;
    delete globals.__TB_BOOT_REPORT__;

    const shell = renderViewerState(payloadOf(selfHtml), { viewer: () => new Function(script)(), shellDocument: true });
    expect(shell.html).toBe("");
    // …and the handoff it leaves behind renders when the shell calls it.
    const boot = globals.__TB_BOOT_REPORT__ as (() => void) | undefined;
    expect(typeof boot).toBe("function");
    boot!();
    expect(shell.liveHtml()).toContain("My run");
  });

  test("booting twice into one document leaves a single keydown listener, belonging to the live run", () => {
    // The viewer shell loads a dropped archive in place, so one document can boot the viewer more
    // than once. A surviving listener from the first run stays bound to THAT run's sessions and would
    // render it back into the shared #app — and because it calls preventDefault, the live run would
    // never see the key at all.
    const once = renderViewerState(payloadOf(html));
    expect(once.documentKeyListeners.length).toBe(1);

    const twice = renderViewerState(payloadOf(html), { rebootViewer: true });
    expect(twice.documentKeyListeners.length).toBe(1);
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

describe("chunked session hydration (lazy #tb-session parsing)", () => {
  const trace = core.extractTrace(sampleLogs);
  const html = core.buildMultiReportHtml({
    generatedAt: "2024-01-01 00:00:00",
    sessions: [
      { meta: { title: "Run A", status: "passed", platform: "android" }, trace, llmLogs: [], shots: {} },
      { meta: { title: "Run B", status: "failed", platform: "ios" }, trace, llmLogs: [], shots: {} },
    ],
  });

  test("the run index rendered from #tb-index stubs matches the fully-hydrated render", () => {
    const chunked = renderViewer(null, { chunks: chunksOf(html) });
    expect(chunked).toContain("Run A");
    expect(chunked).toContain("Run B");
    // Byte-identical to the same report booted from a monolithic payload: the index never needs
    // a session chunk (step/tool counts come precomputed on the stubs).
    expect(chunked).toBe(renderViewer(payloadOf(html)));
  });

  test("opening a run parses its #tb-session chunk and renders the full detail", () => {
    const out = renderViewer(null, { chunks: chunksOf(html), session: 1 });
    expect(out).toContain("Run B");
    expect(out).toContain("Tap login"); // trace content only the session chunk carries
  });

  test("a deep link into a chunked report hydrates the routed run", () => {
    const out = renderViewer(null, { chunks: chunksOf(html), query: "?run=1&tab=info" });
    expect(out).toContain("Run B");
  });

  test("a run opened before its chunk streams in holds a loading shell, then hydrates when it lands", async () => {
    const state = renderViewerState(null, { chunks: chunksOf(html), holdChunks: [1], session: 1 });
    expect(state.html).toContain("Loading run");
    expect(state.html).toContain("Run B"); // the header renders from the index stub immediately
    state.releaseChunks();
    // The viewer polls for the chunk while the document is still streaming (its own 50ms timer).
    for (let i = 0; i < 100 && state.readHtml().includes("Loading run"); i++) await new Promise((resolve) => setTimeout(resolve, 10));
    expect(state.readHtml()).toContain("Tap login");
  });

  test("a chunk that is still streaming in is left alone until the parser closes it", async () => {
    // The element exists but its payload is half-arrived. Re-reading and re-parsing that partial
    // text every 50ms is what turns a big report's deep link into an apparently hung page: the
    // chunk can be tens of megabytes, and the parse burns the same main thread the download runs
    // on. The viewer must wait for the parser's end-tag signal instead.
    const state = renderViewerState(null, { chunks: chunksOf(html), streamingChunks: [1], session: 1 });
    expect(state.html).toContain("Loading run");
    await new Promise((resolve) => setTimeout(resolve, 200)); // several poll turns
    expect(state.partialChunkReads()).toBe(0);
    expect(state.readHtml()).toContain("Loading run");
    state.releaseChunks();
    for (let i = 0; i < 100 && state.readHtml().includes("Loading run"); i++) await new Promise((resolve) => setTimeout(resolve, 10));
    expect(state.readHtml()).toContain("Tap login");
  });

  test("the loading view reports download progress and keeps the run index one click away", () => {
    const state = renderViewerState(null, { chunks: chunksOf(html), streamingChunks: [1], session: 1 });
    // Chunks arrive in order, so run A's is already parsed while run B's is still streaming.
    expect(state.html).toContain("Downloaded 1 of 2 runs");
    expect(state.html).toContain("data-back"); // an escape to the index, which #tb-index already rendered
    // The tab nav is what normally gives the detail header its bottom padding; without tabs the
    // header has to supply it or the title sits flush on the header border.
    expect(state.html).toContain('class="detailheader notabs"');
  });

  test("the loading view only rewrites its progress line when the download actually advances", async () => {
    const state = renderViewerState(null, { chunks: chunksOf(html), streamingChunks: [1], session: 1 });
    await new Promise((resolve) => setTimeout(resolve, 200)); // several poll turns, no new chunk
    // The note lives in a role=status live region, so a repaint per poll turn is a screen reader
    // reading the same sentence out 20 times a second.
    expect(state.loadingProgressWrites()).toBe(0);
  });

  test("a chunk missing from a fully-loaded document opens with index data instead of hanging", () => {
    const chunks = chunksOf(html);
    delete chunks.sessions["1"];
    const out = renderViewer(null, { chunks, session: 1 });
    expect(out).not.toContain("Loading run");
    expect(out).toContain("Run B");
  });

  test("a single-run chunked document auto-opens and hydrates its only session", () => {
    const single = core.buildRunReportHtml({ meta: { title: "Solo run", status: "passed" }, trace, llmLogs: [], shots: {} });
    const out = renderViewer(null, { chunks: chunksOf(single) });
    expect(out).toContain("Solo run");
    expect(out).toContain("Tap login");
  });

  test("YAML riding in on meta (zip importer shape) is lifted to session fields, never the index", () => {
    // The zip importer's buildRunMeta puts recordingYaml/originalYaml on meta AND passes them as
    // dedicated session fields; the index copies meta per session, so leaving them there would
    // make the boot chunk scale with recording size.
    const withYamlMeta = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [
        { meta: { title: "Run A", status: "passed", recordingYaml: "RECYAML: 1\n", originalYaml: "ORIGYAML: 1\n" }, trace, llmLogs: [], shots: {}, recordingYaml: "RECYAML: 1\n", originalYaml: "ORIGYAML: 1\n" },
        { meta: { title: "Run B", status: "failed" }, trace, llmLogs: [], shots: {} },
      ],
    });
    const chunks = chunksOf(withYamlMeta);
    expect(chunks.index).not.toContain("RECYAML");
    expect(chunks.index).not.toContain("ORIGYAML");
    const session = JSON.parse(chunks.sessions["0"]);
    expect(session.recordingYaml).toBe("RECYAML: 1\n");
    expect(session.originalYaml).toBe("ORIGYAML: 1\n");
    expect(session.meta.recordingYaml).toBeUndefined();
    expect(session.meta.originalYaml).toBeUndefined();
  });

  test("the index carries only per-call token/cost summaries — LLM text stays in the session chunk", () => {
    const llmLogs = [{ model: "gpt", inputTokens: 11, outputTokens: 7, cacheReadTokens: 0, totalCost: 0.5, durationMs: 1200, label: "Turn 1", instructions: "SYSTEM PROMPT TEXT", response: [{ kind: "text" as const, text: "LONG RESPONSE TEXT" }] }];
    const withLlm = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [
        { meta: { title: "Run A", status: "passed" }, trace, llmLogs, shots: {} },
        { meta: { title: "Run B", status: "failed" }, trace, llmLogs: [], shots: {} },
      ],
    });
    const chunks = chunksOf(withLlm);
    expect(JSON.parse(chunks.index).sessions[0].llm).toEqual([{ inputTokens: 11, outputTokens: 7, totalCost: 0.5 }]);
    expect(chunks.index).not.toContain("SYSTEM PROMPT TEXT");
    expect(chunks.index).not.toContain("LONG RESPONSE TEXT");
    expect(chunks.sessions["0"]).toContain("SYSTEM PROMPT TEXT");
    // The summaries are everything the run list renders: token/cost totals and the cost sort
    // come out byte-identical to the same report booted fully hydrated.
    expect(renderViewer(null, { chunks })).toBe(renderViewer(payloadOf(withLlm)));
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

  test("the boot payload carries no sprite bytes; they ride in the per-session #tb-sprites chunk", () => {
    const chunks = chunksOf(html);
    expect(chunks.index).not.toContain("SPRITEBYTES");
    expect(chunks.sessions["1"]).not.toContain("SPRITEBYTES");
    const p = payloadOf(html);
    expect(p.sessions[1].video.sprites).toEqual([{ uri: "", rows: 2 }]);
    expect(spritesOf(html)).toEqual({ "1": ["data:image/webp;base64,SPRITEBYTES"] });
  });

  test("the viewer resolves the hoisted sprite lazily when the session's frames render", () => {
    const out = renderViewer(payloadOf(html), { sprites: spritesOf(html), session: 1 });
    expect(out).toContain("background-image:url('data:image/webp;base64,SPRITEBYTES')");
  });

  test("a video run holds its loading shell until the sprite chunk lands, then renders real frames", async () => {
    // The session chunk alone isn't enough: frame URLs resolve once at render, so hydrating
    // before #tb-sprites-<i> parses would paint blank frames that nothing ever re-renders.
    const state = renderViewerState(null, { chunks: chunksOf(html), holdSpriteChunks: [1], session: 1 });
    expect(state.html).toContain("Loading run");
    state.releaseChunks();
    for (let i = 0; i < 100 && state.readHtml().includes("Loading run"); i++) await new Promise((resolve) => setTimeout(resolve, 10));
    expect(state.readHtml()).toContain("background-image:url('data:image/webp;base64,SPRITEBYTES')");
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

describe("whenDocumentComplete (export deferral while the document streams)", () => {
  test("holds work while streaming, runs only the latest request once complete, immediate when already complete", async () => {
    // A chunked report's UI is live while the document tail (later #tb-session chunks) is still
    // arriving; exportReport routes through this gate so a Share click can't snapshot a
    // half-streamed DOM into a truncated file.
    let ready: string | undefined = "loading";
    (globalThis as Record<string, unknown>).document = { get readyState() { return ready; } };
    const ran: string[] = [];
    whenDocumentComplete(() => ran.push("first click"));
    whenDocumentComplete(() => ran.push("second click"));
    expect(ran).toEqual([]);
    ready = undefined; // parser finished (a live DOM reports 'complete'; the gate treats absent as complete)
    for (let i = 0; i < 100 && !ran.length; i++) await new Promise((resolve) => setTimeout(resolve, 10));
    expect(ran).toEqual(["second click"]);
    whenDocumentComplete(() => ran.push("post-load click"));
    expect(ran).toEqual(["second click", "post-load click"]);
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
        { ...session("Sign-in flow", "failed"), meta: { title: "Sign-in flow", status: "failed", platform: "ios", device: "iPhone Demo", failureCode: "account-state" } },
      ],
    });
    expect(out).toContain('type="search"');
    expect(out).toContain('aria-label="Search runs"');
    expect(out).not.toContain('id="runcount"');
    expect(out).toContain('data-search="checkout flow passed android pixel demo"');
    // The failure code joins the haystack, so a reader can filter the index to one code.
    expect(out).toContain('data-search="sign-in flow failed ios iphone demo account-state"');
    expect(out).toContain("No runs match these filters.");
    expect(out).toContain('aria-label="Sort runs"');
    expect(out).not.toContain("<span>Sort</span>");
    expect(out).toContain('role="option" aria-selected="true" data-run-sort="grouped">Status groups</button>');
    expect(out).toContain('data-run-sort="cost">Cost</button>');
    expect(out).not.toContain("data-run-filter");
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
    // Each cell counts its run's tool and LLM calls; the row subtitle carries steps + cost.
    expect(out.match(/<span class="pcounts">1 tool<\/span>/g)).toHaveLength(2);
    expect(out.match(/<span class="pcounts">0 LLM<\/span>/g)).toHaveLength(2);
    expect(out).toContain('<div class="idxstats">1 step · $0.00</div>');
    expect(out).toContain('data-index-section="failed"');
    expect(out).not.toContain('data-index-section="passed"');
    // The footer tallies rows, matching the section counts.
    expect(out).toContain("<strong>1</strong> failed");
    expect(out).toContain("<strong>0</strong> passed");
    // No far-left status dot column and no per-run Platform sort on a matrix index.
    expect(out).not.toContain('class="idxstatus"');
    expect(out).not.toContain('data-run-sort="platform"');
  });

  test("a cell's tool count includes the calls a traceId fold merged into children", () => {
    const on = (title: string, status: string, platform: string) => ({
      ...session(title, status),
      meta: { title, status, platform, trailId: "login/login", target: "demo" },
    });
    const folded = on("login/login", "passed", "android");
    // One visible row standing in for a batched turn (the fold kept two more executed calls as
    // children), plus a no-arg tool (empty summary, still a call) and a terminal snapshot (not one).
    folded.trace = [
      ...folded.trace.map((t: any) => t.label === "tapOnElement" ? {
        ...t, children: [{ label: "swipe", tool: "up" }, { label: "assertVisible", tool: "text: Done" }],
      } : t),
      { i: 90, label: "pressBack", tool: "", objective: false, trailhead: false, ok: true, ts: 90, ms: 50 },
      { i: 91, label: "Final state", tool: "", terminal: true, objective: false, trailhead: false, ok: true, ts: 91, ms: 0 },
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [folded, on("login/login", "failed", "ios")] });
    expect(out).toContain('<span class="pcounts">4 tools</span>');
    expect(out).toContain('<span class="pcounts">1 tool</span>');
  });

  test("mixed targets list every target in the report header", () => {
    const on = (title: string, target: string) => ({
      ...session(title, "passed"),
      meta: { title, status: "passed", platform: "android", target },
    });
    const out = renderViewer({ generatedAt: "now", sessions: [on("A", "beta"), on("B", "alpha")] });
    expect(out).toContain('<div class="k">Targets</div><div class="v">alpha, beta</div>');
  });

  test("Cost sort orders rows most expensive first with unknowable costs last", () => {
    const sessions = [
      { ...session("Cheap", "passed"), llm: [{ totalCost: 0.001 }, { totalCost: 0.002 }] },
      { ...session("Pricey", "passed"), llm: [{ totalCost: 0.05 }] },
      { ...session("Unknown", "passed"), llm: [{ inputTokens: 5 }] },
    ];
    const out = renderViewer({ generatedAt: "now", sessions }, { query: "?view=runs&sort=cost" });
    expect(out).toContain('aria-selected="true" data-run-sort="cost">Cost</button>');
    expect(out.indexOf('data-session="1"')).toBeLessThan(out.indexOf('data-session="0"'));
    expect(out.indexOf('data-session="0"')).toBeLessThan(out.indexOf('data-session="2"'));
    // A row whose cost can't be summed shows the dash, not a partial total.
    expect(out).toContain('<div class="idxstats">1 step · —</div>');
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
    // The retried cell links to the latest attempt; the chevron rail (the control that expands
    // the history) previews it as a bare attempt count.
    expect(collapsed).toContain('<div class="idxcell passed retried"><button class="idxcellopen" type="button" data-session="1"');
    expect(collapsed).toContain('aria-label="Show 2 ios attempts"');
    expect(collapsed.match(/<button class="idxcellchev"[^>]*><span class="idxcellcount"[^>]*>2<\/span><\/button>/)).not.toBeNull();
    // The value line carries exactly the latest-outcome dot + duration — the history cluster must
    // not creep back into the main button (that's the wrapping regression this layout fixes).
    expect(collapsed).toContain('<span class="pv"><span class="idxstatusdot passed" aria-hidden="true"></span><span class="pvtxt">20s</span></span>');
    // Collapsed by default: no attempt panel.
    expect(collapsed).not.toContain('class="idxatthead"');

    const expanded = renderViewer({ generatedAt: "now", sessions }, { toggleCell: "trail:checkout:demo:ios" });
    expect(expanded).toContain('class="idxcellchev open"');
    // The rail narrates its current action: Show when collapsed, Hide when expanded.
    expect(expanded).toContain('aria-label="Hide 2 ios attempts"');
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

  test("a build sharded across interchangeable simulators keeps ONE column per platform", () => {
    // Every CI shard creates its own simulator, so the same iOS lane arrives under two UDIDs. The
    // device CLASS is what a reader compares, so both shards share the `ios` column instead of
    // splitting into two columns that are three-quarters dashes.
    const on = (trailId: string, platform: string, deviceType: string, device: string) => ({
      ...session(trailId, "passed"),
      meta: { title: trailId, status: "passed", trailId, target: "demo", platform, deviceType, device },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        on("login/login", "ios", "iphone", "0AE1DC9E-01D5-4C3E-9E4B-7A0F1D2E3B4C"),
        on("send-money/send", "ios", "iphone", "A9189CF7-5B22-4D71-8E90-2C3D4E5F6A7B"),
        on("login/login", "android", "phone", "emulator-5554"),
      ],
    });
    expect(out).toContain('<span class="pk">ios</span>');
    expect(out).not.toContain("0AE1DC9E");
    expect(out).not.toContain("A9189CF7");
    // Two rows, and the only dashed cell is the android side of the trail it never ran.
    expect(out.match(/idxcell passed/g)).toHaveLength(3);
    expect(out.match(/idxcell missing/g)).toHaveLength(1);
    // Distinct simulators are still distinct runs, never each other's attempt history.
    expect(out).not.toContain("data-cell-toggle");
  });

  test("device classes on one platform stay separate columns", () => {
    const on = (deviceType: string, device: string) => ({
      ...session("Checkout", "passed"),
      meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "demo", platform: "ios", deviceType, device },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [on("iphone", "UDID-1"), on("ipad", "UDID-2"), { ...session("Checkout", "passed"), meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "demo", platform: "android", deviceType: "phone", device: "emulator-5554" } }],
    });
    expect(out).toContain('<span class="pk">ios · ipad</span>');
    expect(out).toContain('<span class="pk">ios · iphone</span>');
    expect(out).toContain('<span class="pk">android</span>');
  });

  test("when one lane did hold two devices, the cell reports the worst of them", () => {
    const on = (status: string, device: string, ranAt: string) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", status, trailId: "checkout", target: "demo", platform: "ios", deviceType: "iphone", device, ranAt, duration: "20s" },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        on("failed", "UDID-1", "2026-07-17 10:00:00"),
        on("passed", "UDID-2", "2026-07-17 10:05:00"),
        { ...session("Checkout", "passed"), meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "demo", platform: "android", deviceType: "phone", ranAt: "2026-07-17 10:01:00" } },
      ],
    });
    // The later pass on the OTHER simulator does not bury the failure: the cell opens the failed
    // run and the row sections under Failed, exactly as two columns would have.
    expect(out).toContain('<div class="idxcell failed retried"><button class="idxcellopen" type="button" data-session="0"');
    expect(out).toContain('data-index-section="failed"');
    expect(out).not.toContain('data-index-section="passed"');
    // Both runs stay in the cell's history, in time order.
    expect(out).toContain('aria-label="Show 2 ios attempts"');
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

  test("the rail previews history as the bare attempt count; per-attempt outcomes live only in the panel", () => {
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
    expect(out.match(/<button class="idxcellchev"[^>]*><span class="idxcellcount"[^>]*>6<\/span><\/button>/)).not.toBeNull();
    expect(out).toContain('aria-label="Show 6 ios attempts"');
    // No dot cluster in the rail (the count-span-only match above pins the button's full content);
    // the six-attempt outcome inventory lives in the expandable panel.
    expect(out).not.toContain('idxcelldots');
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
    expect(out.match(/class="idxfact"><div class="k">Tools/g)).toHaveLength(2);
    expect(out.match(/class="idxfact"><div class="k">LLM/g)).toHaveLength(2);
    // Steps + cost live in the row subtitle, under the title.
    expect(out).toContain('<div class="idxstats">1 step · $0.0010</div>');
    expect(out).toContain('<div class="idxstats">1 step · $0.0025</div>');
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

  test("index row facts show real tool calls, not the flat trace length", () => {
    const row = (i: number, extra: Record<string, unknown> = {}) => ({ i, label: `row ${i}`, tool: "t", note: null, ms: 0, ts: null, ok: true, err: null, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [], ...extra });
    // 1 trailhead step + 1 trailhead action, 2 test steps, 3 trail tool calls, 1 LLM turn, and a
    // terminal 'Final state' snapshot (tool-less, not an action) → 9 trace rows total.
    const trace = [
      row(1, { objective: true, trailhead: true }), row(2),
      row(3, { objective: true }), row(4), row(5),
      row(6, { objective: true }), row(7), row(8, { tool: "llm · gpt-test" }),
      row(9, { label: "Final state", tool: "", terminal: true, screenshotFile: "final.png" }),
    ];
    const mk = (title: string) => ({ meta: { title, status: "passed", duration: "10s", steps: trace.length }, trace, llm: [], shots: {}, recordingYaml: null });
    const out = renderViewer({ generatedAt: "now", sessions: [mk("A"), mk("B")] });
    // Tools counts only real tool calls: no objectives, no LLM turns, no terminal snapshot.
    expect(out).toContain('<div class="k">Tools</div><div class="v">4</div>');
    expect(out).toContain('<div class="k">LLM</div><div class="v">0</div>');
    // Steps live in the row subtitle; a run with no LLM calls costs $0.00.
    expect(out).toContain('<div class="idxstats">2 steps · $0.00</div>');
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

  // A composite trailhead tool folds dozens of dispatches into one row's children. The shared
  // fixture mirrors that shape: one scripted tool whose traceId fold merged a repeated primitive,
  // an exec, and (optionally) one failed dispatch.
  const compositeToolLogs = (failLast: boolean) => {
    const maestro = (s: number, extra: Record<string, unknown> = {}) => ({
      class: `${T}.TrailblazeToolLog`, toolName: "mobile_maestro", traceId: "thv", successful: true,
      durationMs: 100, trailblazeTool: { raw: { commands: [{ tapOn: { text: "Next" } }] } },
      timestamp: `2024-01-01T00:00:${String(s).padStart(2, "0")}Z`, ...extra,
    });
    return [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch signed in", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "demo_signedInToClientRoute", traceId: "thv", successful: true,
        durationMs: 9000, trailblazeTool: { raw: { startingClientRoute: "/dl/view/activity", account: "user@example.com" } }, timestamp: "2024-01-01T00:00:01Z",
      },
      { class: `${T}.TrailblazeToolLog`, toolName: "demo_signInViaUI", traceId: "thv", successful: true, durationMs: 6000, trailblazeTool: { raw: { email: "a@b.c" } }, timestamp: "2024-01-01T00:00:02Z" },
      maestro(3), maestro(4), maestro(5),
      { class: `${T}.TrailblazeToolLog`, toolName: "exec", traceId: "thv", successful: true, durationMs: 40, trailblazeTool: { raw: { argv: ["adb", "shell", "am", "broadcast"] } }, timestamp: "2024-01-01T00:00:06Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "demo_bootstrapTarget", traceId: "thv", successful: true, durationMs: 1200, trailblazeTool: { raw: { relaunch: false } }, timestamp: "2024-01-01T00:00:07Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "demo_launchClientRoute", traceId: "thv", successful: !failLast, durationMs: 800, trailblazeTool: { raw: { route: "app://home" } }, timestamp: "2024-01-01T00:00:08Z", ...(failLast ? { errorMessage: "Deep link route crashed", errorPayload: { schema: "example-repo/trailhead-error/v1", code: "navigation" } } : {}) },
    ];
  };

  test("a long dispatch list collapses to a summary that names the biggest time sink", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(compositeToolLogs(false)), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    // Collapsed by default: the summary counts DISPATCHES (the ×3 fold still counts as 3) and
    // names the slowest dispatch, and no child rows render until the reader expands.
    expect(out).toContain('data-open="0"');
    expect(out).toContain('7 tool dispatches · slowest <span class="mono">demo_signInViaUI</span> 6.0s');
    expect(out).not.toContain('<span class="kt mono">tapOn</span>');
    // The composite call itself stays fully legible: every parameter on its own line, not the
    // summarized three-key crop ordinary rows get.
    expect(out).toContain('<div class="tl-tool mono">startingClientRoute=/dl/view/activity</div>');
    expect(out).toContain('<div class="tl-tool mono">account=user@example.com</div>');
  });

  test("a failed dispatch stays visible with its error while the list is collapsed", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "failed" }, trace: core.extractTrace(compositeToolLogs(true)), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    // Still collapsed — but the summary counts the failure and the failed row (with its error
    // message) renders anyway, so the line the reader came for needs no expanding.
    expect(out).toContain('data-open="0"');
    expect(out).toContain('1 failed</span>');
    expect(out).toContain('class="kid bad"');
    expect(out).toContain('demo_launchClientRoute');
    // The structured payload's code renders as a chip on both surfaces the reader scans: the
    // collapsed summary line and the failed dispatch's error line.
    expect(out).toContain('1 failed</span><span class="kidcode">navigation</span>');
    expect(out).toContain('<div class="kiderr"><span class="kidcode">navigation</span>Deep link route crashed</div>');
    // The passing plumbing stays hidden.
    expect(out).not.toContain('<span class="kt mono">tapOn</span>');
  });

  test("toggling the dispatch list survives the re-render its own click causes", () => {
    // The summary sits inside a selectable step row and every state change re-renders from st,
    // so the open state must live in st.kidsOpen, not the DOM.
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(compositeToolLogs(false)), llmLogs: [], shots: {} });
    const payload = payloadOf(html);
    const row = payload.sessions[0].trace.find((t: any) => t.label === "demo_signedInToClientRoute");
    const out = renderViewer(payload, { toggleKids: row.i });
    // Expanded: each child row carries its duration and ×N so the fold stays dissectible.
    expect(out).toContain(`data-kids="${Number(row.i)}" data-open="1"`);
    expect(out).toContain('<span class="kcount">×3</span>');
    expect(out).toContain('<span class="kms">300ms</span>');
    expect(out).toContain('<span class="kt mono">tapOn</span>');
    expect(out).toContain('<span class="kt mono">adb shell am broadcast</span>');
  });

  test("a short delegation list stays inline, annotated the same way", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap the row" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementWithNodeId", traceId: "inl", successful: true,
        durationMs: 30, trailblazeTool: { raw: { nodeId: 7 } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "inl", successful: true,
        durationMs: 25, trailblazeTool: { raw: { selector: { text: "Row" } } }, timestamp: "2024-01-01T00:00:02Z",
      },
    ];
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(logs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).toContain('<div class="kids">');
    expect(out).not.toContain('kidsummary');
    expect(out).toContain('<span class="kms">25ms</span>');
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
    expect(out).toContain('class="grphdr sel" data-group="22"');
    // Step headers are permanent landmarks now: no collapse affordance on them.
    expect(out).not.toContain('data-group="22" aria-expanded');
    expect(out).not.toContain("groupchev");
    expect(out).toContain('aria-current="step"');
    expect(out).toContain('class="scrubline setup"');
    expect(out).toContain('class="scrubline trail"');
    expect(out).toContain('title="Trail begins"');
    expect(out).toContain('Dotted segment is Trailhead setup; solid segment is the authored Trail.');
    expect(out).toContain('aria-valuetext="Trail, item 22 of 23: Complete checkout"');
    expect(out).not.toContain('<button type="button" class="scrubtick"');
    expect(out).not.toContain('class="scrubfill"');
  });

  test("per-call LLM rows don't count toward the Trailhead auto-collapse threshold", () => {
    // 7 setup tool actions, each preceded by the LLM call that chose it. Only the tool actions are
    // actions, so this trailhead stays expanded exactly as it did before per-call rows existed.
    const trace = [
      { i: 1, label: "Prepare the app", objective: true, trailhead: true, ok: true, ts: 1, ms: 0 },
      ...Array.from({ length: 7 }, (_, i) => [
        { i: 100 + i, label: "LLM Request", tool: "llm · m", objective: false, trailhead: false, ok: true, ts: 100 + i, ms: 500, llm: i },
        { i: 200 + i, label: `setup action ${i + 1}`, tool: "t", objective: false, trailhead: false, ok: true, ts: 200 + i, ms: 100 },
      ]).flat(),
      { i: 300, label: "Complete checkout", objective: true, trailhead: false, ok: true, ts: 300, ms: 0 },
      { i: 301, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 301, ms: 100 },
    ];
    const llm = Array.from({ length: 7 }, () => ({ model: "m", inputTokens: 10, outputTokens: 5, cacheReadTokens: 0, totalCost: 0.001, promptCost: null, completionCost: null, cacheSavings: 0, comp: null, durationMs: 500, label: "LLM Request", instructions: null, response: [] }));
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm, shots: {} }] });
    expect(out).toContain('data-phase="trailhead" aria-expanded="true"');
  });

  test("clicking a step header selects the step's first tool call", () => {
    const trace = [
      { i: 1, label: "Complete checkout", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 2, ms: 100 },
      { i: 3, label: "assertVisible", tool: "text: Done", objective: false, trailhead: false, ok: true, ts: 3, ms: 100 },
      { i: 4, label: "Review the order", objective: true, trailhead: false, ok: true, ts: 4, ms: 0 },
    ];
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] };
    const clicked = renderViewerState(payload, { clickGroup: 1 });
    expect(clicked.html).toContain('class="step sel child" data-step="2"');
    expect(clicked.route).toContain("step=2");
    // A step with no actions keeps the selection on the header itself.
    const empty = renderViewerState(payload, { clickGroup: 4 });
    expect(empty.html).toContain('class="grphdr sel" data-group="4"');
    expect(empty.route).toContain("step=4");
    // An agent step's leading reasoning row is not a tool call either: the first real action wins.
    const agentStep = {
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: [
        { i: 1, label: "Complete checkout", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
        { i: 2, label: "the login button is visible", tool: "llm · gpt-test", objective: false, trailhead: false, ok: true, ts: 2, ms: 100 },
        { i: 3, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 3, ms: 100 },
      ], llm: [], shots: {} }],
    };
    const reasoned = renderViewerState(agentStep, { clickGroup: 1 });
    expect(reasoned.route).toContain("step=3");
    // A trailing terminal snapshot after an action-less final step is not a "first tool call".
    const withSnapshot = {
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: [
        ...trace,
        { i: 5, label: "Final state", tool: "", terminal: true, objective: false, trailhead: false, ok: true, ts: 5, ms: 0 },
      ], llm: [], shots: {} }],
    };
    const snapped = renderViewerState(withSnapshot, { clickGroup: 4 });
    expect(snapped.html).toContain('class="grphdr sel" data-group="4"');
    expect(snapped.route).toContain("step=4");
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

  test("the LLM tab names the model in the repo's provider/model form, on the totals card and every row", () => {
    const call = (model: string, provider: string | null) => ({
      model, ...(provider ? { provider } : {}), inputTokens: 10, outputTokens: 5, cacheReadTokens: 0,
      totalCost: 0.001, promptCost: null, completionCost: null, cacheSavings: 0, comp: null,
      durationMs: 100, label: "LLM Request", instructions: null, response: [],
    });
    const render = (llm: unknown[]) => renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: slim, shots: {}, recordingYaml: null, llm }],
    }, { tab: "llm" });
    // Single-model session: the totals card names it once, every table row carries it.
    const one = render([call("gpt-5-6-luna", "openai"), call("gpt-5-6-luna", "openai")]);
    expect(one).toContain(">Model</span>");
    expect(one).toContain("openai/gpt-5-6-luna");
    expect([...one.matchAll(/class="llmmodel mono"/g)].length).toBe(2);
    // Mixed session: both models are listed and counted, rather than one standing in for the run.
    const mixed = render([call("gpt-5-6-luna", "openai"), call("claude-x", "anthropic")]);
    expect(mixed).toContain(">Models (2)</span>");
    expect(mixed).toContain("openai/gpt-5-6-luna");
    expect(mixed).toContain("anthropic/claude-x");
    // No provider recorded (older payload / modelName-only log): the bare model id, never a
    // fabricated prefix.
    const bare = render([call("some-model", null)]);
    expect(bare).toContain("some-model");
    expect(bare).not.toContain("/some-model");
    // No model at all: the table's em-dash convention, and the totals card omits the line.
    const none = render([call("?", null)]);
    expect(none).not.toContain(">Model</span>");
    expect(none).toContain('class="llmmodel mono" title="—">—<');
  });

  test("LLM tab renders per-request composition columns, the input-token breakdown, and cache savings", () => {
    const comp = { system: 511, user: 233, tools: 199, images: 57, systemCount: 1, userCount: 2, toolsCount: 10, imagesCount: 1, est: 1000 };
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "R", status: "passed" }, trace: slim, shots: {}, recordingYaml: null,
        llm: [
          { model: "m", inputTokens: 1000, outputTokens: 10, cacheReadTokens: 400, totalCost: 0.01, promptCost: 0.008, completionCost: 0.002, cacheSavings: 0.0012, comp, durationMs: 1000, label: "LLM Request", instructions: null, response: [] },
          { model: "m", inputTokens: 900, outputTokens: 9, cacheReadTokens: 0, totalCost: 0.009, promptCost: null, completionCost: null, cacheSavings: 0, comp: null, durationMs: 900, label: "LLM Request", instructions: null, response: [] },
        ],
      }],
    }, { tab: "llm" });
    // Per-request table: a row per call with the reported input total and its estimated split.
    expect(out).toContain('class="llmtable');
    expect(out).toContain("Input (LLM)");
    // No estimate-total column: the split is folded to sum to the reported total, so such a column
    // would equal Input (LLM) on every row by construction.
    expect(out).not.toContain("Input (Est)");
    expect((out.match(/<tr class="llmrow/g) || []).length).toBe(2);
    // Rows are keyboard-reachable like the call-list rows.
    expect((out.match(/<tr class="llmrow[^>]*tabindex="0"/g) || []).length).toBe(2);
    // Call 1 carries its composition numbers…
    expect(out).toContain(">511<");
    expect(out).toContain(">233<");
    expect(out).toContain(">199<");
    expect(out).toContain(">57<");
    // …and call 2 (no composition captured) renders em-dashes in all four composition-derived
    // cells (System/User/Tools/Images), never zeros.
    expect((out.match(/<td class="num">—<\/td>/g) || []).length).toBe(4);
    // The aggregated input-token breakdown renders (one legend row per category, images included
    // because the run sent one).
    expect(out).toContain('class="llmbreakbar"');
    expect((out.match(/class="llmbreakcat"/g) || []).length).toBe(4);
    // Cache-savings figure with the without-cache total (0.01 + 0.009 + 0.0012 savings).
    expect(out).toContain("−$0.001200");
    expect(out).toContain("$0.020200");
    // Input/output cost totals from the per-call costs.
    expect(out).toContain("$0.008000");
    expect(out).toContain("$0.002000");
  });

  test("LLM tab with no composition data renders the table with em-dashes and no breakdown card", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "R", status: "passed" }, trace: slim, shots: {}, recordingYaml: null,
        // Older export shape: rows predating the composition fields entirely.
        llm: [{ model: "m", inputTokens: 100, outputTokens: 10, cacheReadTokens: 0, totalCost: 0.001, durationMs: 500, label: "LLM Request", instructions: null, response: [] }],
      }],
    }, { tab: "llm" });
    expect(out).toContain('class="llmtable');
    // All four composition-derived cells (System/User/Tools/Images) fall back — not just one.
    expect((out.match(/<td class="num">—<\/td>/g) || []).length).toBe(4);
    expect(out).not.toContain('class="llmbreakbar"');
  });

  test("activating a per-request table row opens the transcript lightbox and highlights the row in place", () => {
    const llmCall = (i: number) => ({ model: "m", inputTokens: 100 + i, outputTokens: 10, cacheReadTokens: 0, totalCost: 0.001, promptCost: null, completionCost: null, cacheSavings: 0, comp: null, durationMs: 500, label: "LLM Request", instructions: null, response: [] });
    const payload = {
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: slim, shots: {}, recordingYaml: null, llm: [llmCall(0), llmCall(1)] }],
    };
    // Enter on a table row: the lightbox opens on that call (the tab's only detail view) and the
    // row highlight moves IN PLACE — no re-render, so the reader's place in the table survives.
    const state = renderViewerState(payload, { tab: "llm", llmEnter: 1 });
    expect(state.zoomRoot.className).toBe("txoverlay");
    expect(state.zoomRoot.attrs["aria-label"]).toBe("LLM transcript, call 2 of 2");
    expect(state.llmRow(1).classes.has("sel")).toBe(true);
    expect(state.llmRow(1).attrs["aria-current"]).toBe("true");
    expect(state.llmRow(0).classes.has("sel")).toBe(false);
    // While the lightbox is open on the LLM tab, the address deep-links to the call.
    expect(state.route).toContain("llm=1");
    // Mouse click takes the identical path.
    const clicked = renderViewerState(payload, { tab: "llm", llmClick: 1 });
    expect(clicked.zoomRoot.className).toBe("txoverlay");
    expect(clicked.llmRow(1).classes.has("sel")).toBe(true);
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

  test("the failure banner renders meta.failureCode as a chip, and only when the meta carries one", () => {
    const trace = [
      { i: 1, label: "Launch signed in", tool: "agent step", note: null, ms: 0, ts: 1, ok: true, err: null, screenshotFile: null, objective: true, trailhead: true, count: null, mark: null, children: [] },
      { i: 2, label: "demo_signedInToClientRoute", tool: "route: /x", note: null, ms: 900, ts: 2, ok: false, err: "TrailheadException: staging account locked out", screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [] },
    ];
    const session = (meta: Record<string, unknown>) => ({ generatedAt: "now", sessions: [{ meta, trace, llm: [], shots: {} }] });
    const out = renderViewer(session({ title: "Failed", status: "failed", failureCode: "account-state" }));
    expect(out).toContain('<span class="failurecode">account-state</span>');
    // Legacy/uncoded failures render the banner exactly as before — no empty chip.
    const uncoded = renderViewer(session({ title: "Failed", status: "failed" }));
    expect(uncoded).toContain('class="failurepanel"');
    expect(uncoded).not.toContain('failurecode');
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

// Port of LlmTokenBreakdownEstimator.estimateBreakdown (trailblaze-models) over the log's
// flattened message shape — the extraction-time fallback when a log carries no stored breakdown.
describe("LLM input-token composition estimate (estimateLlmComp)", () => {
  const estimateLlmComp = (core as any).estimateLlmComp;
  const sys = { role: "system", message: "s".repeat(400) };
  const user = { role: "user", message: "u".repeat(400) };

  test("categorizes system/user/tool-descriptor chars and scales the parts to the reported input total", () => {
    // tool chars = name(3) + description(196) + 200 structure overhead = 399.
    const comp = estimateLlmComp([sys, user], [{ name: "tap", description: "d".repeat(196) }], 300);
    expect(comp.system).toBeGreaterThan(0);
    expect(comp.user).toBeGreaterThan(0);
    expect(comp.tools).toBeGreaterThan(0);
    expect(comp.images).toBe(0);
    // Scaled so the categories sum exactly to the LLM-reported total; `est` is that sum.
    expect(comp.system + comp.user + comp.tools + comp.images).toBe(300);
    expect(comp.est).toBe(300);
    expect(comp.systemCount).toBe(1);
    expect(comp.userCount).toBe(1);
    expect(comp.toolsCount).toBe(1);
  });

  test("user turns after conversation history starts count as messages, not as prompt chars", () => {
    const base = estimateLlmComp([sys, user], [], 200);
    const withHistory = estimateLlmComp(
      [sys, user, { role: "assistant", message: "ok" }, { role: "user", message: "x".repeat(4000) }],
      [],
      200,
    );
    // The huge post-history turn joins the user message count but not the user char pool, so
    // the user-vs-system token split is unchanged.
    expect(withHistory.userCount).toBe(2);
    expect(withHistory.user).toBe(base.user);
    expect(withHistory.system).toBe(base.system);
  });

  test("a tool_result entry ends the initial prompt phase too", () => {
    const base = estimateLlmComp([sys, user], [], 200);
    const after = estimateLlmComp(
      [sys, user, { role: "tool_result", message: "r", toolName: "tap" }, { role: "user", message: "y".repeat(4000) }],
      [],
      200,
    );
    expect(after.user).toBe(base.user);
  });

  test("image attachment inventory lines count as images at the flat per-image estimate", () => {
    const imgUser = { role: "user", message: "look\n\nAttachments:\n- Image (png), Binary, 100 Base64 Encoded Characters\n" };
    const comp = estimateLlmComp([sys, imgUser], [], 1000);
    expect(comp.imagesCount).toBe(1);
    expect(comp.images).toBeGreaterThan(0);
    expect(comp.system + comp.user + comp.tools + comp.images).toBe(1000);
  });

  test("returns null when there is nothing to estimate from or distribute", () => {
    expect(estimateLlmComp([], [{ name: "t", description: "d" }], 100)).toBeNull();
    expect(estimateLlmComp([sys], [], 0)).toBeNull();
    expect(estimateLlmComp([sys], [], null)).toBeNull();
  });

  test("pins the port's constants: exact per-category tokens at scale factor 1", () => {
    // Fixture chosen so the pre-scale estimate equals the reported total (scale = 1), making the
    // assertions sensitive to the ported constants themselves — 4 chars/token, the 200-char
    // per-tool structure overhead, and the 765-token flat image estimate — not just to the
    // normalized shape, which scaling makes true for ANY nonzero constants:
    //   system 400 chars → 100 tokens; user 400 chars (incl. the image inventory line) → 100;
    //   tool 3 + 196 + 200 overhead = 399 chars → 99; one image → 765.
    //   text tokens trunc(1199/4) = 299; + 765 image = 1064 = the reported input total.
    const attach = "\n\nAttachments:\n- Image (png)";
    const userText = "u".repeat(400 - attach.length) + attach;
    const comp = estimateLlmComp(
      [{ role: "system", message: "s".repeat(400) }, { role: "user", message: userText }],
      [{ name: "tap", description: "d".repeat(196) }],
      1064,
    );
    expect(comp).toEqual({
      system: 100, user: 100, tools: 99, images: 765,
      systemCount: 1, userCount: 1, toolsCount: 1, imagesCount: 1,
      est: 1064,
    });
  });
});

describe("extractLlmLogs composition + cache savings", () => {
  const extractLlmLogs = (core as any).extractLlmLogs;
  const requestLog = (usage: Record<string, unknown>, extra: Record<string, unknown> = {}) => ({
    class: `${T}.TrailblazeLlmRequestLog`,
    llmMessages: [],
    llmResponse: [],
    llmRequestUsageAndCost: usage,
    durationMs: 5,
    timestamp: "2024-01-01T00:00:00Z",
    ...extra,
  });

  test("carries the model's provider id through to the share payload, and omits it when absent", () => {
    // The log's TrailblazeLlmModel carries { trailblazeLlmProvider: { id, display }, modelId } —
    // the two halves of the repo's canonical `<provider>/<model>` identity.
    const withProvider = extractLlmLogs([requestLog({
      inputTokens: 10, outputTokens: 1, promptCost: 0, completionCost: 0,
      trailblazeLlmModel: { modelId: "gpt-5-6-luna", trailblazeLlmProvider: { id: "openai", display: "OpenAI" } },
    })]);
    expect(withProvider[0].model).toBe("gpt-5-6-luna");
    expect(withProvider[0].provider).toBe("openai");
    expect((core as any).slimLlmForShare(withProvider)[0].provider).toBe("openai");
    // A log with only a model name has no provider, and none is invented; the share payload omits
    // the key entirely, so an older payload and a provider-less new one render identically.
    const modelNameOnly = extractLlmLogs([requestLog(
      { inputTokens: 10, outputTokens: 1, promptCost: 0, completionCost: 0 },
      { modelName: "some-model" },
    )]);
    expect(modelNameOnly[0].model).toBe("some-model");
    expect(modelNameOnly[0].provider).toBeNull();
    expect("provider" in (core as any).slimLlmForShare(modelNameOnly)[0]).toBe(false);
  });

  test("prefers the runtime-computed inputTokenBreakdown stored on the log", () => {
    const rows = extractLlmLogs([requestLog({
      inputTokens: 1000, outputTokens: 10, promptCost: 0.01, completionCost: 0.001,
      trailblazeLlmModel: { modelId: "m" },
      inputTokenBreakdown: {
        systemPrompt: { tokens: 600, count: 1 },
        userPrompt: { tokens: 100, count: 2 },
        toolDescriptors: { tokens: 200, count: 12 },
        images: { tokens: 100, count: 1 },
        assistantMessageCount: 3,
        toolMessageCount: 4,
      },
    })]);
    expect(rows[0].comp).toEqual({
      system: 600, user: 100, tools: 200, images: 100,
      systemCount: 1, userCount: 2, toolsCount: 12, imagesCount: 1,
      est: 1000,
    });
  });

  test("falls back to estimating from the raw messages when the log has no stored breakdown", () => {
    const rows = extractLlmLogs([requestLog(
      { inputTokens: 500, outputTokens: 5, promptCost: 0.001, completionCost: 0.0001, trailblazeLlmModel: { modelId: "m" } },
      {
        llmMessages: [{ role: "system", message: "s".repeat(400) }, { role: "user", message: "u".repeat(400) }],
        toolOptions: [{ name: "tap", description: "Tap an element" }],
      },
    )]);
    const comp = rows[0].comp;
    expect(comp).toBeTruthy();
    expect(comp.system + comp.user + comp.tools + comp.images).toBe(500);
    expect(comp.toolsCount).toBe(1);
  });

  test("comp is null when the log has neither a breakdown nor messages", () => {
    const rows = extractLlmLogs([requestLog(
      { inputTokens: 100, outputTokens: 1, promptCost: 0.001, completionCost: 0.0001, trailblazeLlmModel: { modelId: "m" } },
    )]);
    expect(rows[0].comp).toBeNull();
  });

  test("cache savings = cached reads × (full − cached) input rate", () => {
    const rows = extractLlmLogs([requestLog({
      inputTokens: 2_000_000, cacheReadInputTokens: 1_000_000, outputTokens: 1, promptCost: 0.01, completionCost: 0.001,
      trailblazeLlmModel: { modelId: "m", inputCostPerOneMillionTokens: 3.0, cachedInputCostPerOneMillionTokens: 0.3 },
    })]);
    expect(rows[0].cacheSavings).toBeCloseTo(2.7, 6);
  });

  test("a standalone MCP sampling log estimates composition from its prompt fields", () => {
    const rows = extractLlmLogs([{
      class: `${T}.McpSamplingLog`,
      traceId: "llm-solo",
      usageAndCost: { inputTokens: 1000, outputTokens: 10, promptCost: 0.001, completionCost: 0.0001, trailblazeLlmModel: { modelId: "m" } },
      systemPrompt: "s".repeat(400),
      userMessage: "u".repeat(400),
      includedScreenshot: true,
      durationMs: 50,
      timestamp: "2024-01-01T00:00:00Z",
    }]);
    const comp = rows[0].comp;
    expect(comp).toBeTruthy();
    expect(comp.system).toBeGreaterThan(0);
    expect(comp.user).toBeGreaterThan(0);
    expect(comp.imagesCount).toBe(1);
    expect(comp.images).toBeGreaterThan(0);
    expect(comp.system + comp.user + comp.tools + comp.images).toBe(1000);
  });

  test("no estimated category goes negative when the measured text overshoots the reported total", () => {
    // A short sampling call against a big screenshot: the flat per-image estimate alone exceeds the
    // reported input total, so the unclamped remainder fold would hand back a negative Tools figure
    // that the bar cannot draw (legend and bar disagreeing).
    const rows = extractLlmLogs([{
      class: `${T}.McpSamplingLog`,
      traceId: "llm-overshoot",
      usageAndCost: { inputTokens: 20, outputTokens: 2, promptCost: 0.001, completionCost: 0.0001, trailblazeLlmModel: { modelId: "m" } },
      systemPrompt: "s".repeat(4000),
      userMessage: "u".repeat(4000),
      includedScreenshot: true,
      durationMs: 10,
      timestamp: "2024-01-01T00:00:00Z",
    }]);
    const comp = rows[0].comp;
    for (const v of [comp.system, comp.user, comp.tools, comp.images]) expect(v).toBeGreaterThanOrEqual(0);
  });

  test("a screenshot-only sampling call (empty userMessage) still carries the image signal", () => {
    const rows = extractLlmLogs([{
      class: `${T}.McpSamplingLog`,
      traceId: "llm-shot-only",
      usageAndCost: { inputTokens: 900, outputTokens: 5, promptCost: 0.001, completionCost: 0.0001, trailblazeLlmModel: { modelId: "m" } },
      systemPrompt: "s".repeat(100),
      userMessage: "",
      includedScreenshot: true,
      durationMs: 10,
      timestamp: "2024-01-01T00:00:00Z",
    }]);
    expect(rows[0].comp.imagesCount).toBe(1);
    expect(rows[0].comp.images).toBeGreaterThan(0);
  });

  test("cache savings is 0 with no cached reads, and with no pricing (cached rate defaults to the full rate)", () => {
    const noCache = extractLlmLogs([requestLog({
      inputTokens: 10, outputTokens: 1, promptCost: 0, completionCost: 0,
      trailblazeLlmModel: { modelId: "m", inputCostPerOneMillionTokens: 3.0 },
    })]);
    expect(noCache[0].cacheSavings).toBe(0);
    const noRates = extractLlmLogs([requestLog({
      inputTokens: 10, cacheReadInputTokens: 5, outputTokens: 1, promptCost: 0, completionCost: 0,
      trailblazeLlmModel: { modelId: "m" },
    })]);
    expect(noRates[0].cacheSavings).toBe(0);
    // The branch the name advertises: a model that prices input but omits the cached rate charges
    // cached reads at the full rate, so the discount is exactly zero (not "free cached reads").
    const noCachedRate = extractLlmLogs([requestLog({
      inputTokens: 1_000_000, cacheReadInputTokens: 1_000_000, outputTokens: 1, promptCost: 0, completionCost: 0,
      trailblazeLlmModel: { modelId: "m", inputCostPerOneMillionTokens: 3.0 },
    })]);
    expect(noCachedRate[0].cacheSavings).toBe(0);
    // …and a model that does price cached reads discounts by the rate difference (proving the
    // default above is the full rate rather than an unconditional zero).
    const cachedRate = extractLlmLogs([requestLog({
      inputTokens: 1_000_000, cacheReadInputTokens: 1_000_000, outputTokens: 1, promptCost: 0, completionCost: 0,
      trailblazeLlmModel: { modelId: "m", inputCostPerOneMillionTokens: 3.0, cachedInputCostPerOneMillionTokens: 0.3 },
    })]);
    expect(cachedRate[0].cacheSavings).toBeCloseTo(2.7, 6);
  });
});

describe("embedded LLM payload carries composition numbers, not messages", () => {
  test("slimmed llm rows keep comp/cacheSavings/per-call costs; the boot index stub stays minimal", () => {
    const logs = [{
      class: `${T}.TrailblazeLlmRequestLog`,
      llmMessages: [{ role: "system", message: "sys prompt" }],
      llmResponse: [],
      llmRequestUsageAndCost: {
        inputTokens: 100, outputTokens: 10, promptCost: 0.002, completionCost: 0.0005, cacheReadInputTokens: 40,
        trailblazeLlmModel: { modelId: "m", inputCostPerOneMillionTokens: 3, cachedInputCostPerOneMillionTokens: 0.3 },
        inputTokenBreakdown: {
          systemPrompt: { tokens: 50, count: 1 },
          userPrompt: { tokens: 30, count: 1 },
          toolDescriptors: { tokens: 20, count: 5 },
          images: { tokens: 0, count: 0 },
          assistantMessageCount: 0,
          toolMessageCount: 0,
        },
      },
      durationMs: 5,
      timestamp: "2024-01-01T00:00:00Z",
    }];
    const html = core.buildRunReportHtml({
      meta: { title: "R", status: "passed" },
      trace: [],
      llmLogs: (core as any).extractLlmLogs(logs),
      shots: {},
    });
    const call = payloadOf(html).sessions[0].llm[0];
    expect(call.comp).toEqual({ system: 50, user: 30, tools: 20, images: 0, systemCount: 1, userCount: 1, toolsCount: 5, imagesCount: 0, est: 100 });
    expect(call.cacheSavings).toBeCloseTo((40 * (3 - 0.3)) / 1_000_000, 12);
    expect(call.promptCost).toBe(0.002);
    expect(call.completionCost).toBe(0.0005);
    // The composition rides as numbers only — the messages stay out of the share payload.
    expect(call.messages).toBeUndefined();
    // The boot index stub keeps exactly the numbers the run list needs — composition stays in
    // the per-session chunk.
    const stub = JSON.parse(chunksOf(html).index).sessions[0].llm[0];
    expect(Object.keys(stub).sort()).toEqual(["inputTokens", "outputTokens", "totalCost"]);
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

describe("autoplay-capture contract (?autoplay=1)", () => {
  // The document `trailblaze report --video/--gif/--webp` loads in headless Chromium: four steps,
  // 500ms apart, then a 9-minute idle. The exporter screen-records the tab and stops on
  // `globalThis.__tbPlaybackEnded`, so the contract is "play start to finish unattended, then say
  // so once" — and that idle must not become 9 minutes of a static screen.
  const capturePayload = () => ({
    generatedAt: "now",
    sessions: [{
      meta: { title: "Capture run", status: "passed" },
      trace: [
        { i: 1, label: "Open app", ts: 100000, ms: 100, ok: true, screenshotFile: "s1.png" },
        { i: 2, label: "Tap login", ts: 100500, ms: 100, ok: true, screenshotFile: "s2.png" },
        { i: 3, label: "Enter code", ts: 101000, ms: 100, ok: true, screenshotFile: "s3.png" },
        { i: 4, label: "See home", ts: 641000, ms: 100, ok: true, screenshotFile: "s4.png" },
      ],
      llm: [],
      shots: { "s1.png": "data:image/png;base64,S1", "s2.png": "data:image/png;base64,S2", "s3.png": "data:image/png;base64,S3", "s4.png": "data:image/png;base64,S4" },
      recordingYaml: null,
    }],
  });

  // Records every write to the global the recorder polls, so "raised exactly once, and not before
  // the end" is observable rather than inferred from a final boolean.
  const trackEndFlag = () => {
    let value: unknown;
    const writes: unknown[] = [];
    Object.defineProperty(globalThis, "__tbPlaybackEnded", {
      configurable: true,
      get() { return value; },
      set(next: unknown) { value = next; writes.push(next); },
    });
    return { writes, dispose: () => { delete (globalThis as Record<string, unknown>).__tbPlaybackEnded; } };
  };

  test("plays start to finish with no interaction and raises the end flag once, after the last step is on screen", () => {
    const flag = trackEndFlag();
    try {
      const state = renderViewerState(capturePayload(), {
        query: "?autoplay=1",
        drive: (ctx) => {
          // Note the absence of ctx.play(): the document started itself.
          expect(ctx.html()).toContain('aria-label="Pause timeline"');
          ctx.advance(0);
          expect(ctx.html()).toContain('class="step sel" data-step="1"'); // playback starts at the top
          ctx.advance(600); // past the 350ms offset
          expect(ctx.selectedSteps()).toEqual(["2"]);
          expect(ctx.shotImg.src).toBe("data:image/png;base64,S2");
          expect(flag.writes).toEqual([]);
          ctx.advance(1200); // 1800ms → past the COMPRESSED 1700ms offset of the post-idle row
          expect(ctx.selectedSteps()).toEqual(["4"]);
          expect(flag.writes).toEqual([]); // still dwelling on the last step
          ctx.advance(300); // 2100ms ≥ totalMs 2050 → playback ends
          expect(ctx.html()).toContain('class="step sel" data-step="4"');
          expect(ctx.html()).toContain('aria-label="Play timeline"');
          expect(flag.writes).toEqual([]); // the final frame has to paint before the recorder stops
          ctx.advance(0);
          ctx.advance(0);
          expect(flag.writes).toEqual([true]);
          // A replay tells the recorder nothing new — it already stopped on the first signal.
          ctx.play();
          ctx.advance(0);
          ctx.advance(3000);
          ctx.advance(0);
          ctx.advance(0);
          expect(flag.writes).toEqual([true]);
        },
      });
      expect(state.autoplayMarker()).toBe("1"); // capture framing is stamped on the document
    } finally {
      flag.dispose();
    }
  });

  test("a run with nothing to play raises the flag immediately instead of stalling the recorder", () => {
    const flag = trackEndFlag();
    try {
      renderViewerState(
        { generatedAt: "now", sessions: [{ meta: { title: "Nothing ran", status: "failed" }, trace: [], llm: [], shots: {}, recordingYaml: null }] },
        { query: "?autoplay=1" },
      );
      expect(flag.writes).toEqual([true]);
    } finally {
      flag.dispose();
    }
  });

  test("a report opened without the flag never plays itself and never signals", () => {
    const flag = trackEndFlag();
    try {
      const state = renderViewerState(capturePayload(), {
        drive: (ctx) => {
          ctx.advance(10000);
          expect(ctx.html()).toContain('aria-label="Play timeline"');
          expect(ctx.html()).toContain('class="step sel" data-step="1"');
          expect(flag.writes).toEqual([]);
        },
      });
      expect(state.autoplayMarker()).toBeUndefined();
    } finally {
      flag.dispose();
    }
  });
});

describe("export playback schedule (idle-gap compression)", () => {
  const pure = core;
  // 500ms of real activity, then a 10-minute idle.
  const rows = [{ ts: 10000, ms: 100 }, { ts: 10500, ms: 100 }, { ts: 610500, ms: 100 }];
  const video = { sprites: [{ uri: "data:image/webp;base64,X", rows: 5 }], fps: 2, frames: 10, columns: 2, rows: 5, frameHeight: 100, frameMap: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9], startFrame: 0, endFrame: 9, startMs: 10000 };

  test("exportGapMs plays at 4x, caps an idle at 1s, and floors a fast burst at one captured frame", () => {
    expect(pure.exportGapMs(2000)).toBe(500); // real activity plays through at 4x
    expect(pure.exportGapMs(20)).toBe(350); // a sub-frame burst still survives the 5fps shutter
    expect(pure.exportGapMs(600000)).toBe(1000); // a 10-minute idle costs one second of animation
  });

  test("a long idle collapses to the same second an hour of dead air would", () => {
    const exported = pure.buildExportSchedule(rows, null);
    expect(exported.offsets).toEqual([0, 350, 1350]);
    expect(exported.totalMs).toBe(1700); // + the last row's own floored dwell
    // Interactive playback keeps its own wider window — compression is an export-only concern.
    expect(pure.buildPlaybackSchedule(rows, null).offsets).toEqual([0, 500, 4500]);
  });

  test("a video rides the compressed clock instead of stretching the export to the session's wall clock", () => {
    const plain = pure.buildPlaybackSchedule(rows, video);
    const exported = pure.buildExportSchedule(rows, video);
    expect(plain.mode).toBe("video");
    expect(plain.totalMs).toBeGreaterThan(600000); // real-time playback: 10 minutes of dead air
    expect(exported.mode).toBe("video");
    expect(exported.totalMs).toBe(1700);
    // The run clock still lands on each row's real timestamp — it just fast-forwards between them,
    // so the sprite frame tracks the session rather than freezing.
    expect(pure.playbackPositionAt(exported, 0).clockMs).toBe(10000);
    expect(pure.playbackPositionAt(exported, 350).clockMs).toBe(10500);
    expect(pure.playbackPositionAt(exported, 850).clockMs).toBe(310500); // halfway across the collapsed idle
    expect(pure.playbackPositionAt(exported, 1350).clockMs).toBe(610500);
    expect(pure.playbackPositionAt(exported, 0).frame).toBe(0);
    expect(pure.playbackPositionAt(exported, 1350).frame).toBe(9); // clamped to the last playable frame
  });

  test("an untimed row rides the previous row's clock and still gets its own dwell", () => {
    const mixed = [{ ts: 10000, ms: 100 }, { ms: 800 }, { ts: 11000, ms: 100 }];
    const exported = pure.buildExportSchedule(mixed, null);
    expect(exported.offsets).toEqual([0, 350, 700]);
    expect(exported.clockAnchors).toBeNull(); // no video → the scrub head runs off playback time
    expect(pure.buildExportSchedule(mixed, video).clockAnchors).toEqual([10000, 10000, 11000]);
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

describe("LLM chat transcripts (SessionPayload.llmMessages / llmMessagesGz)", () => {
  const gzText = (value: string) => require("zlib").gzipSync(value).toString("base64");
  // Wait for the async inflate → re-render pass to land (bounded, no fixed sleep).
  const settled = async (read: () => string, needle: string): Promise<string> => {
    for (let i = 0; i < 100 && !read().includes(needle); i++) await new Promise((resolve) => setTimeout(resolve, 5));
    return read();
  };
  const SYSTEM_PROMPT = "You are an agent that controls a device. SYSTEM-PROMPT-MARKER " + "s".repeat(700);
  const SCREEN_DUMP = "Here is the view hierarchy: SCREEN-DUMP-MARKER " + "n".repeat(900);
  const requestLog = (messages: unknown[], n: number) => ({
    class: `${T}.TrailblazeLlmRequestLog`,
    llmMessages: messages,
    llmResponse: [{ parts: [{ class: "Tool.Call", tool: "tapOnElement", args: `{"reasoning":"turn ${n}"}` }] }],
    llmRequestUsageAndCost: { inputTokens: 10, outputTokens: 5, totalCost: 0.001, trailblazeLlmModel: { modelId: "gpt-test" } },
    durationMs: 200,
    timestamp: `2024-01-01T00:00:0${n}Z`,
  });
  // Conversation history accumulates: call 2 repeats call 1's turns verbatim. The tool_use body
  // is the markdown+fence shape TrailblazeLogger.toTrailblazeLlmMessages persists.
  const turn1 = [
    { role: "system", message: SYSTEM_PROMPT },
    { role: "user", message: "Tap login. Screenshot: data:image/png;base64,AAAA////====" },
    { role: "tool_use", message: '**tapOnElement**\n\n```json\n{"text":"Login"}\n```\n', toolName: "tapOnElement" },
  ];
  const turn2 = [...turn1, { role: "tool_result", message: "tapped", toolName: "tapOnElement" }, { role: "user", message: SCREEN_DUMP }];
  const transcriptLogs = [requestLog(turn1, 1), requestLog(turn2, 2)];
  const llmRows = () => (core as any).extractLlmLogs(transcriptLogs);
  const tx = () => (core as any).extractLlmTranscripts(llmRows());
  const slim = (core as any).slimTraceForShare(core.extractTrace(sampleLogs));
  const sessionBase = () => ({ meta: { title: "Run", status: "passed" }, trace: slim, llm: (core as any).slimLlmForShare(llmRows()), shots: {}, recordingYaml: null });
  const inlinePayload = () => ({ generatedAt: "now", sessions: [{ ...sessionBase(), llmMessages: tx() }] });
  const gzPayload = () => ({ generatedAt: "now", sessions: [{ ...sessionBase(), llmMessagesGz: gzText(JSON.stringify(tx())) }] });

  test("extractLlmTranscripts pools repeated history and aligns calls with the slim llm rows", () => {
    const rows = llmRows();
    const transcripts = tx();
    expect(transcripts.calls.length).toBe(rows.length);
    const call2 = (core as any).transcriptCallMessages(transcripts, 1);
    expect(call2.map((m: any) => m.role)).toEqual(["system", "user", "tool_use", "tool_result", "user"]);
    expect(call2[0].text).toContain("SYSTEM-PROMPT-MARKER");
    expect(call2[2].toolName).toBe("tapOnElement");
    expect(call2[4].text).toContain("SCREEN-DUMP-MARKER");
    // The system prompt repeats verbatim in every call's history; the pool stores it once.
    expect(transcripts.texts.filter((t: string) => t.includes("SYSTEM-PROMPT-MARKER")).length).toBe(1);
  });

  test("image data URIs inside messages become a placeholder instead of a second embedded screenshot", () => {
    const call1 = (core as any).transcriptCallMessages(tx(), 0);
    expect(call1[1].text).toContain("[screenshot]");
    expect(call1[1].text).toContain("Tap login");
    expect(JSON.stringify(tx())).not.toContain("data:image/");
  });

  test("sessions whose calls carry no messages embed no transcript at all", () => {
    expect((core as any).extractLlmTranscripts((core as any).extractLlmLogs(sampleLogs))).toBeNull();
    expect((core as any).transcriptCallMessages(null, 0)).toBeNull();
  });

  test("a malformed transcript degrades instead of throwing", () => {
    // Non-array where a shape member belongs → no transcript.
    expect((core as any).transcriptCallMessages({ texts: "nope", calls: [] }, 0)).toBeNull();
    // A truthy non-array per-call entry → that call reads as empty, no crash.
    expect((core as any).transcriptCallMessages({ texts: ["hi"], calls: [{ bogus: true }] }, 0)).toEqual([]);
    expect((core as any).transcriptCallMessages({ texts: ["hi"], calls: [[{ role: "user", t: 0 }]] }, 5)).toEqual([]);
  });

  test("a malformed llm row (truthy non-array messages) reads as no messages instead of failing extraction", () => {
    // A string passes a truthy `.length` probe, so the producer must guard with Array.isArray —
    // extraction runs per session, and one bad record must not fail the whole multi-session report.
    expect((core as any).extractLlmTranscripts([{ ...llmRows()[0], messages: "not-an-array" }])).toBeNull();
    const transcripts = (core as any).extractLlmTranscripts([...llmRows(), { ...llmRows()[0], messages: "not-an-array" }]);
    expect(transcripts.calls.length).toBe(3);
    expect((core as any).transcriptCallMessages(transcripts, 2)).toEqual([]);
    expect((core as any).transcriptCallMessages(transcripts, 0)!.length).toBeGreaterThan(0);
  });

  test("buildMultiReportHtml embeds transcripts in the session chunk but never the boot index", () => {
    const html = core.buildMultiReportHtml({ generatedAt: "now", sessions: [
      { meta: { title: "Run A", status: "passed" }, trace: [], llmLogs: llmRows(), shots: {} },
      { meta: { title: "Run B", status: "failed" }, trace: [], llmLogs: [], shots: {} },
    ] });
    const chunks = chunksOf(html);
    expect(chunks.index).not.toContain("SYSTEM-PROMPT-MARKER");
    expect(chunks.index).not.toContain("llmMessages");
    const embedded = payloadOf(html).sessions[0];
    expect(embedded.llmMessages.texts.join("\n")).toContain("SYSTEM-PROMPT-MARKER");
    expect((core as any).transcriptCallMessages(embedded.llmMessages, 0)[0].role).toBe("system");
    // The slim llm rows stay exactly as slim as before — no messages ride on them.
    expect(embedded.llm.every((c: Record<string, unknown>) => !("messages" in c))).toBe(true);
    // A session with no messages carries no transcript payload.
    expect(payloadOf(html).sessions[1].llmMessages).toBeNull();
  });

  test("buildMultiReportHtml embeds llmMessagesGz verbatim without inflating or re-deriving", () => {
    const gz = gzText(JSON.stringify(tx()));
    const html = core.buildMultiReportHtml({ generatedAt: "now", sessions: [
      { meta: { title: "Run", status: "passed" }, trace: [], llmLogs: llmRows(), shots: {}, llmMessages: null, llmMessagesGz: gz },
    ] });
    const embedded = payloadOf(html).sessions[0];
    expect(embedded.llmMessagesGz).toBe(gz);
    expect(embedded.llmMessages).toBeNull();
  });

  test("inflateLlmMessagesGz round-trips a driver-compressed payload and rejects malformed input", async () => {
    expect(await (core as any).inflateLlmMessagesGz(gzText(JSON.stringify(tx())))).toEqual(tx());
    expect(await (core as any).inflateLlmMessagesGz("not base64 gzip")).toBeNull();
    expect(await (core as any).inflateLlmMessagesGz(gzText(JSON.stringify(["not", "the", "shape"])))).toBeNull();
    // The pooled shape's per-call entries must themselves be arrays.
    expect(await (core as any).inflateLlmMessagesGz(gzText(JSON.stringify({ texts: [], calls: ["nope"] })))).toBeNull();
  });

  // Every LLM request must surface as its own timeline row inside its step (multiple per step),
  // carrying the index of its llm-list twin — the timeline is the primary way into a transcript.
  test("every LLM request becomes a timeline row linked to its llm call, even objective echoes", () => {
    const timelineLogs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Do the thing" }, timestamp: "2024-01-01T00:00:00Z" },
      // An agent turn that re-logs the objective as its promptStep — previously folded away.
      { ...requestLog(turn1, 1), promptStep: { step: "Do the thing" } },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "t9", trailblazeTool: { raw: { text: "Login" } }, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01.500Z" },
      requestLog(turn2, 2),
    ];
    const trace = core.extractTrace(timelineLogs);
    const llmTraceRows = trace.filter((t: any) => t.llm != null);
    expect(llmTraceRows.map((t: any) => t.llm)).toEqual([0, 1]);
    expect(llmTraceRows.every((t: any) => !t.objective)).toBe(true);
    // The linkage survives the share slimming, and LLM rows still don't count as tool calls.
    const slimmed = (core as any).slimTraceForShare(trace);
    expect(slimmed.filter((t: any) => t.llm != null).map((t: any) => t.llm)).toEqual([0, 1]);
    expect(traceToolCallCount(slimmed as any)).toBe(1);
    // The rows add no embedded screenshots: a request log carries its own set-of-mark image, and
    // passing it through would inline one more screenshot per LLM call (roughly doubling a real
    // report's screenshot bytes). Each row previews the next captured frame instead.
    expect(llmTraceRows.every((t: any) => t.screenshotFile == null)).toBe(true);
    expect(slimmed.filter((t: any) => t.llm != null).every((t: any) => t.screenshotFile == null)).toBe(true);
  });

  const timelinePayload = () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Do the thing" }, timestamp: "2024-01-01T00:00:00Z" },
      { ...requestLog(turn1, 1), promptStep: { step: "Do the thing" } },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "t9", trailblazeTool: { raw: { text: "Login" } }, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01.500Z" },
      requestLog(turn2, 2),
    ];
    const rows = (core as any).extractLlmLogs(logs);
    return { generatedAt: "now", sessions: [{ meta: { title: "Run", status: "passed" }, trace: (core as any).slimTraceForShare(core.extractTrace(logs)), llm: (core as any).slimLlmForShare(rows), shots: {}, recordingYaml: null, llmMessages: (core as any).extractLlmTranscripts(rows) }] };
  };

  test("the timeline renders a transcript trigger beside each LLM-call row", () => {
    const out = renderViewer(timelinePayload(), {});
    // One trigger per call, as a SIBLING of the role=button row (nested interactive is an a11y fault).
    expect([...out.matchAll(/class="steprow"/g)].length).toBe(2);
    expect([...out.matchAll(/data-tx="(\d+)"/g)].map((m: any) => m[1])).toEqual(["0", "1"]);
    expect(out).not.toMatch(/<div class="step[^>]*role="button"[^>]*>[^]*?<button[^>]*data-tx=[^]*?<\/div>\s*<\/div>\s*<button/);
    // The row shows the call's own accounting, from its llm-list twin.
    expect(out).toContain("gpt-test · in 10 · out 5");
  });

  test("a transcript trigger opens the lightbox over the timeline without touching it", () => {
    const state = renderViewerState(timelinePayload(), { timelineScrollTop: 240, openTx: 1 });
    expect(state.zoomRoot.className).toBe("txoverlay");
    const panel = state.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("Transcript · Call 2");
    expect(panel.innerHTML).toContain("gpt-test");
    const body = panel.children[0];
    expect(body.innerHTML).toContain("SCREEN-DUMP-MARKER");
    // No re-render underneath: a render would have reset the harness's timeline scroll to 0.
    expect(state.timelineScrollTop).toBe(240);
  });

  test("Escape closes the lightbox, returns focus to the trigger, and leaves the view untouched", () => {
    const state = renderViewerState(timelinePayload(), { timelineScrollTop: 240, openTx: 0, txEscape: true });
    expect(state.restoredFocus).toBe('[data-tx="0"]');
    expect(state.timelineScrollTop).toBe(240);
  });

  test("the lightbox shows role-labeled messages with trail-file tool YAML and expanders", () => {
    const state = renderViewerState(inlinePayload(), { tab: "llm", openTx: 0 });
    const body = state.zoomRoot.children[0].children[0];
    expect(body.innerHTML).toContain("System");
    expect(body.innerHTML).toContain("SYSTEM-PROMPT-MARKER");
    expect(body.innerHTML).toContain("[screenshot]");
    // Call 1 opened; call 2's fresh screen dump belongs to the other call.
    expect(body.innerHTML).not.toContain("SCREEN-DUMP-MARKER");
    // The long system prompt collapses behind an expander; the short tool turn does not.
    expect(body.innerHTML).toContain('<details class="txmsg');
    expect(body.innerHTML).toContain('<div class="txmsg');
    // The tool call renders as a trail-file tool entry, not the raw markdown/JSON blob.
    expect(body.innerHTML).toContain("- tapOnElement:");
    expect(body.innerHTML).toContain("text: Login");
    expect(body.innerHTML).not.toContain("```json");
    // The tool name never runs through the role label's uppercase styling.
    expect(body.innerHTML).toContain('<span class="txtool mono">tapOnElement</span>');
  });

  test("the conversation splits into two voices: model-authored vs agent/harness-supplied", () => {
    const state = renderViewerState(inlinePayload(), { query: "?run=0&tab=llm&llm=1", openTx: 1 });
    const body = state.zoomRoot.children[0].children[0];
    // The model's voice: the tool call it chose. The harness's voice: user turns + tool results.
    // The system prompt is its own quiet preamble.
    expect(body.innerHTML).toContain('class="txmsg voice-llm"');
    expect(body.innerHTML).toContain('class="txmsg voice-user"');
    expect(body.innerHTML).toContain('voice-sys"');
    expect(body.innerHTML).toContain('class="txavatar llm"');
    expect(body.innerHTML).toContain('class="txavatar user"');
    // Tool results side with the harness (the device reporting back), never the model.
    expect(body.innerHTML).toMatch(/voice-user"[^]*?Tool result/);
  });

  test("tool-result envelopes render cleaned, with the verbatim text behind a raw expander", () => {
    const envelope = "**tap**\n\n```json\n**Executed `tap`.** Typed 'TKT-1'\n```\n";
    const tx = { texts: [envelope, '{"matches":2}'], calls: [[{ role: "tool_result", t: 0, toolName: "tap" }, { role: "tool_result", t: 1, toolName: "findMatches" }]] };
    const state = renderViewerState({ generatedAt: "now", sessions: [{ ...sessionBase(), llmMessages: tx }] }, { tab: "llm", openTx: 0 });
    const body = state.zoomRoot.children[0].children[0];
    // Prose envelope: header + fence + markdown markers gone from the displayed body (the
    // verbatim text lives only inside the raw expanders), message intact.
    const cleaned = body.innerHTML.split('<details class="txraw"')[0];
    expect(cleaned).toContain("Executed tap. Typed 'TKT-1'");
    expect(cleaned).not.toContain("```");
    expect(cleaned).not.toContain("**");
    // JSON payload: rendered as YAML.
    expect(body.innerHTML).toContain("matches: 2");
    // Fidelity: the verbatim text stays reachable behind the raw expander.
    expect(body.innerHTML).toContain('<details class="txraw"');
    expect(body.innerHTML).toContain("Executed `tap`.");
  });

  test("transcriptToolResultDisplay parses the logger's markdown envelope (pure)", () => {
    const envelope = { role: "tool_result", toolName: "tap", text: "**tap**\n\n```json\n**Executed `tap`.** Typed 'TKT-1'\n```\n" };
    expect((core as any).transcriptToolResultDisplay(envelope)).toEqual({ text: "Executed tap. Typed 'TKT-1'", raw: envelope.text });
    // Structured output renders as YAML; already-clean text carries no raw fallback.
    expect((core as any).transcriptToolResultDisplay({ role: "tool_result", text: '{"ok":true}' })).toEqual({ text: "ok: true", raw: '{"ok":true}' });
    expect((core as any).transcriptToolResultDisplay({ role: "tool_result", text: "tapped" })).toEqual({ text: "tapped", raw: null });
    // Only result roles apply — tool calls keep their trail-file YAML path.
    expect((core as any).transcriptToolResultDisplay({ role: "tool_use", text: "x" })).toBeNull();
  });

  test("the LLM tab's table rows open the same lightbox as the timeline; the table is the only per-call surface", () => {
    const out = renderViewer(inlinePayload(), { tab: "llm" });
    // One chat trigger per per-request table row — no master call list and no inline detail pane
    // (the lightbox is the detail view).
    expect([...out.matchAll(/td class="txcell"/g)].length).toBe(2);
    expect(out).not.toContain("llmcalls");
    expect(out).not.toContain("Assistant response");
    const state = renderViewerState(inlinePayload(), { tab: "llm", openTx: 1 });
    expect(state.zoomRoot.className).toBe("txoverlay");
    expect(state.zoomRoot.children[0].children[0].innerHTML).toContain("SCREEN-DUMP-MARKER");
  });

  test("a ?llm=N deep link scrolls to the table row, highlights it, and opens its transcript", () => {
    const state = renderViewerState(inlinePayload(), { query: "?run=0&tab=llm&llm=1" });
    // No manual interaction: the route alone lands the reader in call 2's transcript…
    expect(state.zoomRoot.className).toBe("txoverlay");
    expect(state.zoomRoot.attrs["aria-label"]).toBe("LLM transcript, call 2 of 2");
    // …with the table row scrolled into view and highlighted underneath.
    expect(state.llmScrolledTo).toBe('[data-llm="1"]');
    expect(state.html).toMatch(/data-llm="1"[^>]*aria-current="true"/);
    expect(state.route).toContain("llm=1");
  });

  test("closing the deep-linked transcript leaves the highlighted row and drops llm from the URL", () => {
    const state = renderViewerState(inlinePayload(), { query: "?run=0&tab=llm&llm=1", txEscape: true });
    // Escape: focus returns to the deep-linked row, its highlight stays…
    expect(state.restoredFocus).toBe('[data-llm="1"]');
    expect(state.html).toMatch(/data-llm="1"[^>]*aria-current="true"/);
    // …and the URL drops back to the tab route (the lightbox is what `llm` encodes).
    expect(state.route).toBe("/report.html?run=0&tab=llm");
  });

  test("navigating away with the browser closes the transcript instead of stranding it over the new view", () => {
    const two = { generatedAt: "now", sessions: [{ ...sessionBase(), llmMessages: tx() }, { ...sessionBase(), meta: { title: "Other", status: "passed" }, llmMessages: tx() }] };
    const state = renderViewerState(two, { query: "?run=0&tab=llm&llm=1", popstate: "?view=runs" });
    // Back to the runs index takes the dialog with it…
    expect(state.zoomRoot.removed).toBe(true);
    expect(state.html).toContain('class="idxsections"');
    // …and the dismissal does not write the detail route back over the popped-to URL.
    expect(state.route).toBe("/report.html?view=runs");
  });

  test("focus returns to the transcript trigger even after the gz inflation re-render replaces it", async () => {
    const state = renderViewerState(gzPayload(), { tab: "llm", openTx: 0 });
    const body = state.zoomRoot.children[0].children[0];
    // The inflater finishes with a full render(), so the trigger captured on open is now detached.
    for (let i = 0; i < 100 && !body.innerHTML.includes("SYSTEM-PROMPT-MARKER"); i++) await new Promise((resolve) => setTimeout(resolve, 5));
    state.zoomRoot.onkeydown({ key: "Escape", preventDefault() {}, stopPropagation() {} });
    // Focus lands on the trigger that is actually in the document, not the stale captured node.
    expect(state.readRestoredFocus()).toBe('[data-tx="0"]');
  });

  test("the LLM tab groups the per-request table by objective, with subtotals", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Do the thing" }, timestamp: "2024-01-01T00:00:00Z" },
      { ...requestLog(turn1, 1), promptStep: { step: "Do the thing" } },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Do the other thing" }, timestamp: "2024-01-01T00:00:03Z" },
      requestLog(turn2, 2),
    ];
    const rows = (core as any).extractLlmLogs(logs);
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "Run", status: "passed" }, trace: (core as any).slimTraceForShare(core.extractTrace(logs)), llm: (core as any).slimLlmForShare(rows), shots: {}, recordingYaml: null }] };
    const out = renderViewer(payload, { tab: "llm" });
    // Full-width group rows in the per-request table, keyed by objective text.
    expect([...out.matchAll(/class="llmgrouprow"/g)].length).toBe(2);
    expect(out).toContain("Do the thing");
    expect(out).toContain("Do the other thing");
    // Per-objective subtotals on the group row; global call numbering intact (deep links stable).
    expect(out).toContain("1 call · in 10 · out 5");
    expect(out).toContain("1. tapOnElement");
    expect(out).toContain("2. tapOnElement");
    // Nesting is structural, not just a divider: one tbody per objective, and each call inside is
    // marked as grouped (what the stylesheet insets from the group's rail).
    expect([...out.matchAll(/<tbody class="llmgroup">/g)].length).toBe(2);
    expect([...out.matchAll(/<tr class="llmrow[^"]*grouped"/g)].length).toBe(2);
    // Each call sits inside its own objective's tbody — call 1 under the first, call 2 under the
    // second — so the association is readable from the structure alone.
    const groups = out.split('<tbody class="llmgroup">').slice(1);
    expect(groups[0]).toContain("Do the thing");
    expect(groups[0]).toContain('data-llm="0"');
    expect(groups[0]).not.toContain('data-llm="1"');
    expect(groups[1]).toContain("Do the other thing");
    expect(groups[1]).toContain('data-llm="1"');
    // The grouped rows are inset and carry the group's rail; the header row is banded.
    expect(core.RUN_REPORT_CSS).toContain(".llmtable tr.llmrow.grouped td.llmreq {");
    expect(core.RUN_REPORT_CSS).toContain(".llmtable tr.llmrow.grouped td.llmreq::before {");
    expect(core.RUN_REPORT_CSS).toContain(".llmtable tr.llmgrouprow td {");
    // Old payloads without llm-stamped trace rows keep the flat, ungrouped rendering.
    const flat = renderViewer({ generatedAt: "now", sessions: [{ ...sessionBase(), trace: [] }] }, { tab: "llm" });
    expect(flat).not.toContain("llmgrouprow");
    expect(flat).not.toContain('class="llmgroup"');
    expect(flat).not.toContain("grouped");
  });

  test("an objective label is clamped at a word boundary, never mid-word", () => {
    const objective = "Option 1: If a search bar or search icon is visible (it may say 'Search all items') then tap it and search for the item by name";
    const trace = core.extractTrace([
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: objective }, timestamp: "2024-01-01T00:00:00Z" },
    ]);
    const label = String(trace[0].label);
    expect(label.endsWith("…")).toBe(true);
    // The visible text is a prefix of the objective that ends at a word boundary: dropping the
    // ellipsis leaves whole words, and the next character in the original is whitespace.
    const shown = label.slice(0, -1);
    expect(objective.startsWith(shown)).toBe(true);
    expect(objective[shown.length]).toBe(" ");
  });

  test("legacy bare `tool` turns get a direction-neutral label (older logs use them for calls AND results)", () => {
    const legacy = { texts: ["sys", "**tapOnElement**\nI will tap the login button."], calls: [[{ role: "system", t: 0 }, { role: "tool", t: 1 }]] };
    const state = renderViewerState({ generatedAt: "now", sessions: [{ ...sessionBase(), llmMessages: legacy }] }, { tab: "llm", openTx: 0 });
    const body = state.zoomRoot.children[0].children[0];
    expect(body.innerHTML).toContain(">Tool</span>");
    expect(body.innerHTML).not.toContain("Tool result");
    expect(body.innerHTML).not.toContain("Tool call");
  });

  test("a payload with no transcript data keeps the LLM tab intact; the lightbox explains", () => {
    const state = renderViewerState({ generatedAt: "now", sessions: [sessionBase()] }, { tab: "llm", openTx: 0 });
    expect(state.html).toContain('class="llmtable');
    expect(state.zoomRoot.children[0].children[0].innerHTML).toContain("No transcript was captured");
  });

  test("a compressed transcript shows a decompressing note in the lightbox, then the messages once inflation lands", async () => {
    const state = renderViewerState(gzPayload(), { tab: "llm", openTx: 0 });
    const body = state.zoomRoot.children[0].children[0];
    expect(body.innerHTML).toContain("Decompressing transcript");
    for (let i = 0; i < 100 && !body.innerHTML.includes("SYSTEM-PROMPT-MARKER"); i++) await new Promise((resolve) => setTimeout(resolve, 5));
    expect(body.innerHTML).toContain("System");
    expect(body.innerHTML).toContain("SYSTEM-PROMPT-MARKER");
  });

  test("Export logs clicked before inflation still downloads the complete transcripts", async () => {
    const urlAny = URL as any;
    const original = { create: urlAny.createObjectURL, revoke: urlAny.revokeObjectURL };
    let downloaded: Blob | null = null;
    urlAny.createObjectURL = (blob: Blob) => { downloaded = blob; return "blob:test"; };
    urlAny.revokeObjectURL = () => {};
    try {
      renderViewerState(gzPayload(), { exportLogs: true });
      for (let i = 0; i < 100 && !downloaded; i++) await new Promise((resolve) => setTimeout(resolve, 5));
      const logs = JSON.parse(await downloaded!.text());
      // The export carries the POOLED shape (resolving per call would rebuild the quadratic
      // naive shape); it must be complete and resolvable, never the opaque base64 blob.
      expect(logs.llmMessages.calls.length).toBe(logs.llm.length);
      const call2 = (core as any).transcriptCallMessages(logs.llmMessages, 1);
      expect(call2.map((m: any) => m.role)).toEqual(["system", "user", "tool_use", "tool_result", "user"]);
      expect(call2[4].text).toContain("SCREEN-DUMP-MARKER");
    } finally {
      urlAny.createObjectURL = original.create;
      urlAny.revokeObjectURL = original.revoke;
    }
  });
});

describe("display YAML for tool calls (jsonToYaml / transcriptToolCallYaml)", () => {
  test("renders nested objects, arrays and scalars with trail-file indentation", () => {
    expect((core as any).jsonToYaml({ tapOnElementBySelector: { selector: { textRegex: "Save", index: 2 }, flags: [true, null] } }))
      .toBe("tapOnElementBySelector:\n  selector:\n    textRegex: Save\n    index: 2\n  flags:\n    - true\n    - null");
  });

  test("quotes what the recorder quotes — numbers, keywords, ':'/'#'/newline — not leading '-' or '^'", () => {
    expect((core as any).jsonToYaml({ a: "12345", b: "true", c: "-flag", d: "key: value", e: "plain text", f: "", g: "^Next$" }))
      .toBe('a: "12345"\nb: "true"\nc: -flag\nd: "key: value"\ne: plain text\nf: ""\ng: ^Next$');
  });

  test("multiline strings render double-quoted with newline escapes (recorder style), never block scalars", () => {
    expect((core as any).jsonToYaml({ msg: "line one\nline two" })).toBe('msg: "line one\\nline two"');
  });

  test("objects inside arrays use the compact dash form", () => {
    expect((core as any).jsonToYaml({ steps: [{ tool: "tap", x: 1 }, "plain"] }))
      .toBe("steps:\n  - tool: tap\n    x: 1\n  - plain");
  });

  test("empty containers, booleans and null render inline", () => {
    expect((core as any).jsonToYaml({ a: {}, b: [], c: false, d: null })).toBe("a: {}\nb: []\nc: false\nd: null");
  });

  test("a fenced tool_use payload renders exactly as a trail-file tool entry", () => {
    const m = { role: "tool_use", toolName: "tapOnElementBySelector", text: '**tapOnElementBySelector**\n\n```json\n{"selector":{"textRegex":"Save"}}\n```\n' };
    expect((core as any).transcriptToolCallYaml(m)).toBe("- tapOnElementBySelector:\n    selector:\n      textRegex: Save");
  });

  test("a tool call with empty args renders as the bare dash entry", () => {
    expect((core as any).transcriptToolCallYaml({ role: "tool_use", toolName: "pressBack", text: "```json\n{}\n```" })).toBe("- pressBack:");
  });

  // External contract: the transcript's tool-call YAML must read exactly like the same call in a
  // trail file. The expected text is a recorded entry from a real trail, dedented out of its
  // `recording:` block — exact-match is deliberate here; if this drifts, the transcript no longer
  // looks like a trail.
  test("a tool call renders byte-identical to its recorded twin in a trail file", () => {
    const trailFileEntry = [
      "- tapOnElementBySelector:",
      "    reason: Submit the category selection",
      "    nodeSelector:",
      "      androidAccessibility:",
      "        textRegex: ^Next$",
    ].join("\n");
    const m = {
      role: "tool_use",
      toolName: "tapOnElementBySelector",
      text: '**tapOnElementBySelector**\n\n```json\n{"reason":"Submit the category selection","nodeSelector":{"androidAccessibility":{"textRegex":"^Next$"}}}\n```\n',
    };
    expect((core as any).transcriptToolCallYaml(m)).toBe(trailFileEntry);
  });

  test("a JSON tool result renders as bare YAML; prose output and non-tool roles fall back to raw text", () => {
    expect((core as any).transcriptToolCallYaml({ role: "tool_result", toolName: "tap", text: '{"ok":true}' })).toBe("ok: true");
    expect((core as any).transcriptToolCallYaml({ role: "tool_result", toolName: "tap", text: "**tap**\n\n```json\n**Executed `tap`.** Typed 'TKT-1'\n```\n" })).toBeNull();
    expect((core as any).transcriptToolCallYaml({ role: "user", text: '{"looks":"like json"}' })).toBeNull();
  });
});

describe("UI Inspector data path (SessionPayload.hierarchies / hierarchiesGz)", () => {
  const gz = (value: unknown) => require("zlib").gzipSync(JSON.stringify(value)).toString("base64");
  // A small two-node hierarchy in the legacy ViewHierarchyTreeNode shape.
  const vh = {
    nodeId: 1, className: "android.widget.FrameLayout", x1: 0, y1: 0, x2: 1080, y2: 2400,
    children: [{ nodeId: 2, text: "Login", resourceId: "com.example:id/login", clickable: true, x1: 90, y1: 600, x2: 990, y2: 720 }],
  };
  // Two tool rows with screenshots; only the first captured a hierarchy.
  const hierLogs = [
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap login" }, timestamp: "2024-01-01T00:00:00Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "t1", trailblazeTool: { raw: { text: "Login" } }, screenshotFile: "a.png", viewHierarchyFiltered: vh, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "inputText", trailblazeTool: { raw: { text: "user" } }, screenshotFile: "b.png", successful: true, durationMs: 50, timestamp: "2024-01-01T00:00:02Z" },
  ];
  const shots = { "a.png": "data:image/png;base64,AAA", "b.png": "data:image/png;base64,BBB" };

  test("extractTrace carries each log's view hierarchy onto its trace row", () => {
    const trace = core.extractTrace(hierLogs);
    expect((trace.find((t) => t.label === "tapOnElement") as any).viewHierarchy).toEqual(vh);
    expect((trace.find((t) => t.label === "inputText") as any).viewHierarchy ?? null).toBeNull();
  });

  test("traceHierarchies lifts hierarchies keyed by the row's step ordinal", () => {
    const trace = core.extractTrace(hierLogs);
    const tapStep = trace.find((t) => t.label === "tapOnElement") as any;
    const lifted = (core as any).traceHierarchies(trace, false);
    expect(Object.keys(lifted)).toEqual([String(tapStep.i)]);
    expect(lifted[String(tapStep.i)]).toEqual(vh);
    expect((core as any).traceHierarchies([{ label: "no-vh", i: 1 }], false)).toBeNull();
  });

  test("every status is bounded: passed trims at the tight budget, the rest at the unconditional cap", () => {
    const big = (text: string) => ({ text, filler: "x".repeat(200) });
    const trace = [
      { label: "a", i: 1, viewHierarchy: big("first") },
      { label: "b", i: 2, viewHierarchy: big("second") },
    ];
    // An injected budget that fits one hierarchy but not two applies regardless of status — it
    // stands in for the pass-gated budget (passed) and the unconditional structural cap (every
    // other status, which defaults far larger but is never absent).
    const budget = JSON.stringify(big("first")).length + 10;
    expect(Object.keys((core as any).traceHierarchies(trace, true, budget))).toEqual(["1"]);
    expect(Object.keys((core as any).traceHierarchies(trace, false, budget))).toEqual(["1"]);
    // Without an injected budget a failed session's small hierarchies sit far under the default
    // structural cap, so everything is kept.
    expect(Object.keys((core as any).traceHierarchies(trace, false))).toEqual(["1", "2"]);
  });

  test("packSessionInputsHierarchies gives browser producers the same gz side-channel the CLI emits", async () => {
    // Big enough to cross the 64 KB inline threshold once lifted; small stays inline; a caller
    // that already packed is untouched.
    const bigVh = { className: "Root", x1: 0, y1: 0, x2: 10, y2: 10, blob: "y".repeat(80 * 1024) };
    const sessions: any[] = [
      { meta: { status: "failed" }, trace: [{ label: "a", i: 1, viewHierarchy: bigVh }], llmLogs: [], shots: {} },
      { meta: { status: "failed" }, trace: [{ label: "b", i: 1, viewHierarchy: vh }], llmLogs: [], shots: {} },
      { meta: { status: "failed" }, trace: [{ label: "c", i: 1, viewHierarchy: vh }], llmLogs: [], shots: {}, hierarchiesGz: "prepacked" },
    ];
    await (core as any).packSessionInputsHierarchies(sessions);
    expect(sessions[0].hierarchiesGz).toBeTruthy();
    expect(sessions[0].hierarchies ?? null).toBeNull();
    expect(await (core as any).inflateGzJsonRecord(sessions[0].hierarchiesGz)).toEqual({ "1": bigVh });
    expect(sessions[1].hierarchies).toEqual({ "1": vh });
    expect(sessions[1].hierarchiesGz ?? null).toBeNull();
    expect(sessions[2].hierarchiesGz).toBe("prepacked");
    expect(sessions[2].hierarchies ?? null).toBeNull();
  });

  test("buildMultiReportHtml embeds hierarchies in the session chunk and keeps them out of the boot index", () => {
    const html = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "failed" }, trace: core.extractTrace(hierLogs), llmLogs: [], shots }],
    });
    const embedded = payloadOf(html).sessions[0];
    const tapStep = embedded.trace.find((t: any) => t.label === "tapOnElement");
    expect(embedded.hierarchies[String(tapStep.i)]).toEqual(vh);
    // The heavy field never rides on the embedded trace rows themselves…
    expect(embedded.trace.every((t: any) => t.viewHierarchy === undefined)).toBe(true);
    // …and never reaches the #tb-index boot chunk the run list parses at startup.
    expect(chunksOf(html).index).not.toContain("com.example:id/login");
  });

  test("buildMultiReportHtml embeds hierarchiesGz verbatim without inflating it", () => {
    const html = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "passed" }, trace: [], llmLogs: [], shots: {}, hierarchiesGz: gz({ "2": vh }) }],
    });
    const embedded = payloadOf(html).sessions[0];
    expect(embedded.hierarchiesGz).toBe(gz({ "2": vh }));
    expect(embedded.hierarchies).toBeNull();
  });

  test("inflateGzJsonRecord round-trips a driver-compressed hierarchies map and rejects non-objects", async () => {
    expect(await (core as any).inflateGzJsonRecord(gz({ "2": vh }))).toEqual({ "2": vh });
    expect(await (core as any).inflateGzJsonRecord("not base64 gzip")).toBeNull();
    expect(await (core as any).inflateGzJsonRecord(gz([1, 2]))).toBeNull();
  });

  // ── viewer behavior ───────────────────────────────────────────────────────────────────────────
  const inspectorPayload = () => payloadOf(core.buildMultiReportHtml({
    generatedAt: "now",
    sessions: [{ meta: { title: "Run", status: "failed" }, trace: core.extractTrace(hierLogs), llmLogs: [], shots }],
  }));
  const stepOf = (payload: any, label: string) => payload.sessions[0].trace.find((t: any) => t.label === label).i;

  test("timeline rows with a hierarchy and screenshot get the Inspect UI affordance; hierarchy-less rows don't", () => {
    const payload = inspectorPayload();
    const html = renderViewer(payload);
    expect(html).toContain(`data-inspect="${stepOf(payload, "tapOnElement")}"`);
    expect(html).not.toContain(`data-inspect="${stepOf(payload, "inputText")}"`);
  });

  // A timeline row can qualify for BOTH row affordances (the transcript button and Inspect UI).
  // They are disjoint on real logs — an LLM-call row carries no screenshot — but the row renderer
  // must emit both rather than let one shadow the other, and both must stay SIBLINGS of the row
  // (the row is itself role="button", so a nested control would be a second ambiguous tab stop).
  test("a row that is both an LLM call and inspectable carries both affordances, outside the row", () => {
    const payload = inspectorPayload();
    const tapStep = stepOf(payload, "tapOnElement");
    const session = payload.sessions[0];
    session.trace.find((t: any) => t.i === tapStep).llm = 0;
    session.llm = [{ model: "gpt-test", inputTokens: 10, outputTokens: 5, response: [] }];
    const html = renderViewer(payload);
    expect(html).toContain('data-tx="0"');
    expect(html).toContain(`data-inspect="${tapStep}"`);
    // Both buttons follow the row's closing </div> inside the shared .steprow wrapper.
    expect(html).toMatch(new RegExp(`<div class="steprow">[\\s\\S]*?</div><button[^>]*data-tx="0"[\\s\\S]*?<button[^>]*data-inspect="${tapStep}"`));
  });

  // The two side-channels are independent: the LLM transcripts #5788 added and the hierarchies this
  // inspector reads both survive slimming, and an LLM row keeps screenshotFile null (no screenshot
  // to inline, so it is never inspectable).
  test("payload slimming carries transcripts and hierarchies together", () => {
    const withLlm = [
      ...hierLogs,
      {
        class: `${T}.TrailblazeLlmRequestLog`,
        llmMessages: [{ role: "user", message: "Tap login" }],
        llmResponse: [{ parts: [{ class: "Tool.Call", tool: "tapOnElement", args: "{}" }] }],
        llmRequestUsageAndCost: { inputTokens: 10, outputTokens: 5, totalCost: 0.001, trailblazeLlmModel: { modelId: "gpt-test" } },
        durationMs: 200,
        timestamp: "2024-01-01T00:00:03Z",
      },
    ];
    const trace = core.extractTrace(withLlm);
    const embedded = payloadOf(core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "failed" }, trace, llmLogs: core.extractLlmLogs(withLlm), shots }],
    })).sessions[0];
    expect(embedded.llmMessages.texts).toContain("Tap login");
    expect(Object.keys(embedded.hierarchies)).toEqual([String(trace.find((t) => t.label === "tapOnElement")!.i)]);
    embedded.trace.filter((t: any) => t.llm != null).forEach((t: any) => expect(t.screenshotFile).toBeNull());
  });

  test("opening the inspector shows the node tree, the details panel hint, and the bounds overlay", () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot; // the inspector overlay is appended to document.body
    expect(overlay.innerHTML).toContain("UI Inspector");
    // Tree rows: the root by class name, the leaf by its text (both html-escaped).
    expect(overlay.innerHTML).toContain("&lt;FrameLayout&gt;");
    expect(overlay.innerHTML).toContain("&quot;Login&quot;");
    // Bounds rectangles scaled onto the screenshot in device-percent coordinates: the leaf spans
    // x 90..990 of a 1080-wide capture.
    expect(overlay.innerHTML).toContain('class="insprect"');
    expect(overlay.innerHTML).toContain("left:8.333%");
    expect(overlay.innerHTML).toContain(shots["a.png"]);
  });

  // The overlay's live children (parsed from its markup) — how selection and hover are observed
  // now that both paint in place instead of rebuilding the overlay.
  const nodeRow = (overlay: any, key: number) => overlay.querySelectorAll("[data-inspnode]").find((el: any) => el.dataset.inspnode === String(key));
  const rectFor = (overlay: any, key: number) => overlay.querySelectorAll("[data-insprect]").find((el: any) => el.dataset.insprect === String(key));
  const detailsText = (overlay: any) => String(overlay.querySelector(".inspdetails").innerHTML);
  const hoverLabel = (overlay: any) => overlay.querySelector("[data-insphovlabel]");
  const clickNode = (overlay: any, key: number) => overlay.onclick({ preventDefault() {}, target: nodeRow(overlay, key) });
  const movePointer = (overlay: any, target: any, extra: Record<string, unknown> = {}) => overlay.onpointermove({ pointerType: "mouse", target, ...extra });

  test("selecting a tree node highlights its rectangle and shows its properties", () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    clickNode(overlay, 1);
    expect(rectFor(overlay, 1).classList.contains("sel")).toBe(true);
    expect(rectFor(overlay, 0).classList.contains("sel")).toBe(false);
    expect(nodeRow(overlay, 1).classList.contains("sel")).toBe(true);
    expect(detailsText(overlay)).toContain("com.example:id/login");
    expect(detailsText(overlay)).toContain("clickable");
  });

  test("selection paints in place: the tree's scroll position and keyboard focus survive a click", () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    const tree = overlay.querySelector(".insptree");
    tree.scrollTop = 1920;
    nodeRow(overlay, 1).focus();
    clickNode(overlay, 1);
    // A full overlay rebuild would hand back a fresh tree (scrollTop 0) and detach the focused row.
    expect(overlay.querySelector(".insptree")).toBe(tree);
    expect(tree.scrollTop).toBe(1920);
    expect((globalThis as any).document.activeElement).toBe(nodeRow(overlay, 1));
  });

  // Closing re-resolves the trigger by selector rather than focusing the node captured on open: a gz
  // report's hierarchy inflation lands with a full render() that replaces the row markup, so the
  // captured node is detached by then and focusing it would drop the reader on <body>.
  test("closing the inspector returns focus to the live Inspect UI trigger", () => {
    const payload = inspectorPayload();
    const step = stepOf(payload, "tapOnElement");
    const state = renderViewerState(payload, { inspect: step });
    expect(state.zoomRoot.removed).toBe(false);
    state.documentKeyListeners.forEach((fn) => fn({ key: "Escape", defaultPrevented: false, preventDefault() {}, stopPropagation() {} }));
    expect(state.zoomRoot.removed).toBe(true);
    expect(state.readRestoredFocus()).toBe(`[data-inspect="${step}"]`);
  });

  test("hovering the screenshot previews the node a click would select, without committing it", async () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    // Screenshot hit-testing is throttled to one frame, so let the scheduled pass run.
    const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
    // The fake screenshot is 100x200 for a 1080x2400 capture; (50, 55) lands inside the leaf's
    // 90..990 x 600..720 bounds, so the smallest containing node is the leaf.
    movePointer(overlay, overlay.querySelector(".inspshotwrap"), { clientX: 50, clientY: 55 });
    await settled();
    expect(rectFor(overlay, 1).classList.contains("hov")).toBe(true);
    expect(rectFor(overlay, 1).classList.contains("sel")).toBe(false);
    expect(nodeRow(overlay, 1).classList.contains("hov")).toBe(true);
    expect(hoverLabel(overlay).textContent).toContain("Login");
    // The preview shows the node's properties and says it isn't committed yet.
    expect(detailsText(overlay)).toContain("com.example:id/login");
    expect(detailsText(overlay)).toContain("Click to keep");
    // Moving further down the screenshot previews the enclosing node instead.
    movePointer(overlay, overlay.querySelector(".inspshotwrap"), { clientX: 50, clientY: 150 });
    await settled();
    expect(rectFor(overlay, 0).classList.contains("hov")).toBe(true);
    expect(rectFor(overlay, 1).classList.contains("hov")).toBe(false);
    // Leaving the overlay clears the preview entirely.
    overlay.onpointerleave();
    expect(overlay.querySelectorAll("[data-insprect]").some((el: any) => el.classList.contains("hov"))).toBe(false);
    expect(hoverLabel(overlay).classList.contains("on")).toBe(false);
  });

  test("the screenshot is the only hover source — pointing at a tree row previews nothing", async () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
    clickNode(overlay, 0);
    movePointer(overlay, nodeRow(overlay, 1));
    await settled();
    // The tree's one interaction is commit-on-activate; a row under the pointer gets no preview
    // class, no rect on the screenshot, and no preview in the details card.
    expect(overlay.querySelectorAll("[data-inspnode]").some((el: any) => el.classList.contains("hov"))).toBe(false);
    expect(overlay.querySelectorAll("[data-insprect]").some((el: any) => el.classList.contains("hov"))).toBe(false);
    expect(detailsText(overlay)).not.toContain("Click to keep");
    // The committed selection is untouched, and clicking the row still commits it.
    expect(nodeRow(overlay, 0).classList.contains("sel")).toBe(true);
    clickNode(overlay, 1);
    expect(nodeRow(overlay, 1).classList.contains("sel")).toBe(true);
    expect(nodeRow(overlay, 0).classList.contains("sel")).toBe(false);
    expect(detailsText(overlay)).not.toContain("Click to keep");
  });

  test("a screenshot hover previews over a committed selection without replacing it", async () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
    clickNode(overlay, 0);
    movePointer(overlay, overlay.querySelector(".inspshotwrap"), { clientX: 50, clientY: 55 });
    await settled();
    // Node 1 previews (including its tree row, so you can see where it lives) while node 0 stays
    // the committed selection.
    expect(nodeRow(overlay, 1).classList.contains("hov")).toBe(true);
    expect(nodeRow(overlay, 1).classList.contains("sel")).toBe(false);
    expect(nodeRow(overlay, 0).classList.contains("sel")).toBe(true);
    expect(detailsText(overlay)).toContain("Click to keep");
  });

  test("focusing a tree row previews nothing either — keyboard matches the mouse on the tree", () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    nodeRow(overlay, 1).focus();
    if (overlay.onfocusin) overlay.onfocusin({ target: nodeRow(overlay, 1) });
    expect(overlay.querySelectorAll("[data-inspnode]").some((el: any) => el.classList.contains("hov"))).toBe(false);
    expect(detailsText(overlay)).not.toContain("Click to keep");
    // Activation is what commits from the keyboard.
    overlay.onkeydown({ key: "Enter", preventDefault() {}, target: nodeRow(overlay, 1) });
    expect(nodeRow(overlay, 1).classList.contains("sel")).toBe(true);
  });

  test("a touch pointer never hovers — a tap would otherwise leave a stuck preview", () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    overlay.onpointermove({ pointerType: "touch", target: nodeRow(overlay, 1) });
    overlay.onpointermove({ pointerType: "touch", target: overlay.querySelector(".inspshotwrap"), clientX: 50, clientY: 55 });
    expect(overlay.querySelectorAll("[data-inspnode]").some((el: any) => el.classList.contains("hov"))).toBe(false);
    // …and a tap still commits a selection.
    clickNode(overlay, 1);
    expect(nodeRow(overlay, 1).classList.contains("sel")).toBe(true);
  });

  // ── web-shaped captures ─────────────────────────────────────────────────────────────────────
  // Mirrors what a real Playwright session logs (see PlaywrightTrailblazeNodeMapper): the tree is
  // trailblazeNodeTree whose "document" root has NO bounds, node bounds are PAGE-relative (they run
  // to the full scroll height), off-viewport nodes exist (a hidden carousel slide past the right
  // edge), and the screenshot is a viewport-only capture whose real coordinate space is the log's
  // deviceWidth×deviceHeight. Deriving the space from the tree (max x2/y2) skewed every rect and
  // hit-tested most of the image onto the wrong nodes — the "can't hover some elements" bug.
  const webVh = {
    nodeId: 9,
    driverDetail: { class: "web", ariaRole: "document", ariaDescriptor: "document" },
    children: [
      { nodeId: 1, bounds: { left: 0, top: 0, right: 1000, bottom: 60 }, driverDetail: { class: "web", ariaRole: "banner", ariaDescriptor: "banner", isLandmark: true } },
      { nodeId: 2, bounds: { left: 100, top: 100, right: 300, bottom: 140 }, driverDetail: { class: "web", ariaRole: "link", ariaName: "Home", ariaDescriptor: "link: Home", isInteractive: true } },
      { nodeId: 3, bounds: { left: 100, top: 3000, right: 300, bottom: 3040 }, driverDetail: { class: "web", ariaRole: "link", ariaName: "Footer", ariaDescriptor: "link: Footer", isInteractive: true } },
      { nodeId: 4, bounds: { left: 1100, top: 0, right: 2100, bottom: 60 }, driverDetail: { class: "web", ariaRole: "group", ariaName: "Slide 2", ariaDescriptor: "group: Slide 2" } },
    ],
  };
  const webPayload = () => payloadOf(core.buildMultiReportHtml({
    generatedAt: "now",
    sessions: [{
      meta: { title: "Web run", status: "failed" },
      trace: core.extractTrace([
        { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open dashboard" }, timestamp: "2024-01-01T00:00:00Z" },
        { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "w1", trailblazeTool: { raw: { text: "Home" } }, screenshotFile: "w.png", trailblazeNodeTree: webVh, deviceWidth: 1000, deviceHeight: 500, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01Z" },
      ]),
      llmLogs: [],
      shots: { "w.png": "data:image/png;base64,WWW" },
    }],
  }));

  test("the capture's viewport rides the slim trace row, so the inspector has a real coordinate anchor", () => {
    const payload = webPayload();
    const row = payload.sessions[0].trace.find((t: any) => t.label === "tapOnElement");
    expect(row.viewport).toEqual({ w: 1000, h: 500 });
    // Rows without a hierarchy don't pay for it.
    payload.sessions[0].trace.filter((t: any) => t.label !== "tapOnElement").forEach((t: any) => expect(t.viewport ?? undefined).toBeUndefined());
  });
  // Swap the wrap's img stub for one that reports a decoded size (the default stub is undecoded,
  // which keeps the tree-derived fallback in force for the portrait fixtures above).
  const patchImg = (overlay: any, natural: { w: number; h: number }, rect: { left: number; top: number; width: number; height: number }) => {
    overlay.querySelector(".inspshotwrap").querySelector = (sel: string) => (sel === "img"
      ? { complete: true, naturalWidth: natural.w, naturalHeight: natural.h, getBoundingClientRect: () => ({ ...rect, right: rect.left + rect.width, bottom: rect.top + rect.height }) }
      : null);
  };

  test("a page-relative web tree hit-tests against the image's aspect, not the tree's scroll height", async () => {
    const payload = webPayload();
    const state = renderViewerState(payload, { inspect: payload.sessions[0].trace.find((t: any) => t.label === "tapOnElement").i });
    const overlay = state.zoomRoot;
    const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
    // Viewport capture: 1000×500 page-pixels, rendered at 100×50.
    patchImg(overlay, { w: 1000, h: 500 }, { left: 0, top: 0, width: 100, height: 50 });
    // (20, 12) on the image is page point (200, 120) — inside the "Home" link. Under the
    // tree-derived height (max y2 = 3040) the same pointer mapped to page y≈730 and hit nothing.
    movePointer(overlay, overlay.querySelector(".inspshotwrap"), { clientX: 20, clientY: 12 });
    await settled();
    expect(nodeRow(overlay, 2).classList.contains("hov")).toBe(true);
    expect(hoverLabel(overlay).textContent).toContain("Home");
    // Rect verticals are restyled in place against the image-anchored space: the link sits at
    // 100/500 = 20% down the capture, and the below-the-fold footer clips past 100%.
    expect(rectFor(overlay, 2).style.top).toBe("20.000%");
    expect(parseFloat(rectFor(overlay, 3).style.top)).toBeGreaterThan(100);
  });

  test("hover hit-testing is image-relative, so it stays correct while a tall capture's pane scrolls", async () => {
    const payload = webPayload();
    const state = renderViewerState(payload, { inspect: payload.sessions[0].trace.find((t: any) => t.label === "tapOnElement").i });
    const overlay = state.zoomRoot;
    const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
    // Full-page capture (1000×5000) rendered 100×500, scrolled 300px up within its pane — the
    // image's rect has a negative top, exactly what getBoundingClientRect reports mid-scroll.
    patchImg(overlay, { w: 1000, h: 5000 }, { left: 0, top: -300, width: 100, height: 500 });
    // Client (20, 3): image y = 3 − (−300) = 303 → page y = 3030 — the below-the-fold footer link.
    movePointer(overlay, overlay.querySelector(".inspshotwrap"), { clientX: 20, clientY: 3 });
    await settled();
    expect(nodeRow(overlay, 3).classList.contains("hov")).toBe(true);
    expect(hoverLabel(overlay).textContent).toContain("Footer");
    // …and a click at the same point commits the same node.
    overlay.onclick({ target: overlay.querySelector(".inspshotwrap"), clientX: 20, clientY: 3 });
    expect(nodeRow(overlay, 3).classList.contains("sel")).toBe(true);
  });

  // ── reveal on commit ────────────────────────────────────────────────────────────────────────
  // A committed selection must become visible in the tree (expand collapsed ancestors, center the
  // row); hover must never move the tree; selecting an already-visible row is a no-op scroll-wise.
  const deepVh = {
    nodeId: 1, className: "android.widget.FrameLayout", x1: 0, y1: 0, x2: 1000, y2: 2000,
    children: [{
      nodeId: 2, className: "android.widget.ScrollView", x1: 100, y1: 300, x2: 900, y2: 1700,
      children: [{ nodeId: 3, text: "Buried", clickable: true, x1: 400, y1: 900, x2: 600, y2: 1000 }],
    }],
  };
  const deepPayload = () => payloadOf(core.buildMultiReportHtml({
    generatedAt: "now",
    sessions: [{
      meta: { title: "Run", status: "failed" },
      trace: core.extractTrace([
        { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap buried" }, timestamp: "2024-01-01T00:00:00Z" },
        { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "d1", trailblazeTool: { raw: { text: "Buried" } }, screenshotFile: "d.png", viewHierarchyFiltered: deepVh, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01Z" },
      ]),
      llmLogs: [],
      shots: { "d.png": "data:image/png;base64,DDD" },
    }],
  }));
  const openDeep = () => {
    const payload = deepPayload();
    return renderViewerState(payload, { inspect: payload.sessions[0].trace.find((t: any) => t.label === "tapOnElement").i }).zoomRoot;
  };

  test("committing from the screenshot expands collapsed ancestor branches and centers the row", () => {
    const overlay = openDeep();
    const leaf = nodeRow(overlay, 2);
    const branch = leaf.closest("details");
    branch.open = false; // reader collapsed the ScrollView branch; the leaf is buried inside it
    // Screenshot click at (50, 95) → device (500, 950) → the buried leaf.
    overlay.onclick({ target: overlay.querySelector(".inspshotwrap"), clientX: 50, clientY: 95 });
    expect(leaf.classList.contains("sel")).toBe(true);
    expect(branch.open).toBe(true);
    expect(leaf.scrolledIntoView).toEqual({ block: "center" });
  });

  test("hover never scrolls or expands the tree — a preview inside a collapsed branch leaves it collapsed", async () => {
    const overlay = openDeep();
    const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
    const branch = nodeRow(overlay, 2).closest("details");
    branch.open = false;
    movePointer(overlay, overlay.querySelector(".inspshotwrap"), { clientX: 50, clientY: 95 });
    await settled();
    expect(nodeRow(overlay, 2).classList.contains("hov")).toBe(true); // the preview itself is fine
    expect(branch.open).toBe(false);
    expect(overlay.querySelectorAll("[data-inspnode]").every((el: any) => el.scrolledIntoView === undefined)).toBe(true);
  });

  test("selecting an already-visible row via the tree does not move the tree; an off-viewport row centers", () => {
    const overlay = openDeep();
    // Every row measures inside the tree's viewport by default — committing one must not scroll.
    clickNode(overlay, 1);
    expect(nodeRow(overlay, 1).classList.contains("sel")).toBe(true);
    expect(nodeRow(overlay, 1).scrolledIntoView).toBeUndefined();
    // A row measuring outside the tree's viewport re-centers on commit.
    nodeRow(overlay, 2).getBoundingClientRect = () => ({ left: 0, top: 500, right: 100, bottom: 520, width: 100, height: 20 });
    clickNode(overlay, 2);
    expect(nodeRow(overlay, 2).scrolledIntoView).toEqual({ block: "center" });
  });

  test("the raw JSON toggle shows the hierarchy verbatim", () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const overlay = state.zoomRoot;
    overlay.onclick({ target: { closest: (sel: string) => (sel === "[data-inspraw]" ? {} : null) } });
    expect(overlay.innerHTML).toContain('class="mono inspraw"');
    expect(overlay.innerHTML).toContain("com.example:id/login");
  });

  // ── iOS coordinate space ─────────────────────────────────────────────────────────────────────
  // An iOS (XCUITest) capture: the tree is in POINTS, the root declares no bounds, and a
  // descendant overhangs the screen on every side (real captures carry one at exactly 3x the
  // screen). The screenshot is in PIXELS at the device scale — which must not matter, since every
  // rect is a percentage of the tree's own extent. Shape taken from the committed
  // trails/config/trailmaps/contacts/waypoints/ios captures.
  const iosTree = {
    nodeId: 0,
    bounds: { left: 0, top: 0, right: 0, bottom: 0 },
    driverDetail: { class: "iosMaestro", elementType: "Application" },
    children: [{
      nodeId: 1,
      bounds: { left: 0, top: 0, right: 402, bottom: 874 },
      driverDetail: { class: "iosMaestro", elementType: "Window" },
      children: [
        // The off-screen container: 3x the screen, origin outside it.
        { nodeId: 2, bounds: { left: -402, top: -874, right: 804, bottom: 1748 }, driverDetail: { class: "iosMaestro", elementType: "Other" } },
        { nodeId: 3, bounds: { left: 0, top: 68, right: 402, bottom: 124 }, driverDetail: { class: "iosMaestro", elementType: "NavigationBar", label: "Contacts" } },
      ],
    }],
  };
  // The failing shape: the hierarchy-bearing log carries no device dims, so the anchor has to come
  // from the tree itself.
  const iosPayload = () => payloadOf(core.buildMultiReportHtml({
    generatedAt: "now",
    sessions: [{
      meta: { title: "iOS run", status: "failed" },
      trace: core.extractTrace([
        { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch the app" }, timestamp: "2024-01-01T00:00:00Z" },
        { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "i1", trailblazeTool: { raw: { text: "Contacts" } }, screenshotFile: "i.png", trailblazeNodeTree: iosTree, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01Z" },
      ]),
      llmLogs: [],
      shots: { "i.png": "data:image/png;base64,III" },
    }],
  }));

  for (const scale of [2, 3]) {
    test(`an iOS capture's rects match the points tree, not the ${scale}x screenshot or the off-screen container`, async () => {
      const payload = iosPayload();
      const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
      const overlay = state.zoomRoot;
      const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
      // Screenshot in device pixels (402x874 points at <scale>x), rendered 100 wide.
      patchImg(overlay, { w: 402 * scale, h: 874 * scale }, { left: 0, top: 0, width: 100, height: 217.4 });
      // Hit-testing shares the anchor: mid-width, 10% down the image is inside the nav bar (68-124
      // of 874 points). This also drives the in-place restyle the assertions below read.
      movePointer(overlay, overlay.querySelector(".inspshotwrap"), { clientX: 50, clientY: 217.4 * 0.1 });
      await settled();
      expect(hoverLabel(overlay).textContent).toContain("Contacts");
      // The window fills the capture…
      expect(rectFor(overlay, 1).style.left).toBe("0.000%");
      expect(rectFor(overlay, 1).style.width).toBe("100.000%");
      expect(rectFor(overlay, 1).style.height).toBe("100.000%");
      // …the navigation bar spans its full width at 68/874 down…
      expect(rectFor(overlay, 3).style.width).toBe("100.000%");
      expect(rectFor(overlay, 3).style.top).toBe("7.780%");
      expect(rectFor(overlay, 3).style.height).toBe("6.407%");
      // …and the off-screen container still reads as off-screen (negative origin, overhanging).
      expect(parseFloat(rectFor(overlay, 2).style.left)).toBeLessThan(0);
      expect(parseFloat(rectFor(overlay, 2).style.width)).toBeGreaterThan(100);
    });
  }

  test("a compressed hierarchies payload inflates when the inspector opens", async () => {
    const payload = inspectorPayload();
    const tapStep = stepOf(payload, "tapOnElement");
    const session = payload.sessions[0] as Record<string, any>;
    session.hierarchiesGz = gz(session.hierarchies);
    session.hierarchies = null;
    const state = renderViewerState(payload, { inspect: tapStep });
    // The affordance shows before inflation (which steps have hierarchies isn't knowable yet)…
    expect(state.html).toContain(`data-inspect="${tapStep}"`);
    // …and the inspector holds a decompressing note until the inflate lands, then renders.
    const overlay = state.zoomRoot;
    expect(overlay.innerHTML).toContain("Decompressing UI hierarchy");
    for (let i = 0; i < 100 && !String(overlay.innerHTML).includes("&quot;Login&quot;"); i++) await new Promise((resolve) => setTimeout(resolve, 5));
    expect(overlay.innerHTML).toContain("&quot;Login&quot;");
    expect(overlay.innerHTML).toContain('class="insprect"');
  });
});

describe("UI Inspector model (pure builders)", () => {
  test("normalizes the legacy ViewHierarchyTreeNode shape (top-level fields, x1..y2 bounds)", () => {
    const model = inspectorModel({
      className: "android.widget.FrameLayout", x1: 0, y1: 0, x2: 1000, y2: 2000,
      children: [{ text: "Pay", accessibilityText: "Pay button", resourceId: "id/pay", clickable: true, x1: 100, y1: 200, x2: 300, y2: 260 }],
    })!;
    expect(model.dims).toEqual({ w: 1000, h: 2000 });
    expect(model.nodes.length).toBe(2);
    expect(model.nodes[1].label).toBe('"Pay"');
    expect(model.nodes[1].bounds).toEqual({ x1: 100, y1: 200, x2: 300, y2: 260 });
    expect(model.nodes[1].fields).toContainEqual({ k: "Content description", v: "Pay button" });
    expect(model.nodes[1].fields).toContainEqual({ k: "Resource ID", v: "id/pay" });
    expect(model.nodes[1].flags).toContain("clickable");
  });

  test("normalizes the TrailblazeNode shape (driverDetail fields, left/top/right/bottom bounds)", () => {
    const model = inspectorModel({
      nodeId: 0,
      bounds: { left: 0, top: 0, right: 1080, bottom: 2400 },
      driverDetail: { className: "android.view.View" },
      children: [{
        nodeId: 1, ref: "y778",
        bounds: { left: 40, top: 100, right: 240, bottom: 160 },
        driverDetail: { className: "android.widget.Button", text: "Charge", isClickable: true },
      }],
    })!;
    expect(model.dims).toEqual({ w: 1080, h: 2400 });
    expect(model.nodes[1].label).toBe('"Charge"');
    expect(model.nodes[1].bounds).toEqual({ x1: 40, y1: 100, x2: 240, y2: 160 });
    expect(model.nodes[1].fields).toContainEqual({ k: "Class", v: "android.widget.Button" });
    expect(model.nodes[1].fields).toContainEqual({ k: "Ref", v: "y778" });
    expect(model.nodes[1].flags).toContain("isClickable");
  });

  // iOS trees declare no root bounds and carry containers that overhang the screen, so the widest
  // extent overall is a multiple of the screen. The anchor comes from the origin-anchored nodes.
  test("a rootless tree anchors on its origin-anchored extent, ignoring off-screen overhang", () => {
    const model = inspectorModel({
      nodeId: 0, bounds: { left: 0, top: 0, right: 0, bottom: 0 }, driverDetail: { class: "iosMaestro" },
      children: [{
        nodeId: 1, bounds: { left: 0, top: 0, right: 402, bottom: 874 }, driverDetail: { class: "iosMaestro" },
        children: [{ nodeId: 2, bounds: { left: -402, top: -874, right: 804, bottom: 1748 }, driverDetail: { class: "iosMaestro" } }],
      }],
    })!;
    expect(model.dims).toEqual({ w: 402, h: 874 });
  });

  test("with nothing anchored at the origin, dims still fall back to the widest extent", () => {
    const model = inspectorModel({
      nodeId: 0, bounds: { left: 0, top: 0, right: 0, bottom: 0 }, driverDetail: { class: "iosMaestro" },
      children: [{ nodeId: 1, bounds: { left: 10, top: 20, right: 300, bottom: 500 }, driverDetail: { class: "iosMaestro" } }],
    })!;
    expect(model.dims).toEqual({ w: 300, h: 500 });
  });

  test("parses legacy centerPoint/dimensions bounds and falls back to max extent for dims", () => {
    const model = inspectorModel({
      text: "Old iOS capture", centerPoint: "200,300", dimensions: "100x50",
    })!;
    expect(model.nodes[0].bounds).toEqual({ x1: 150, y1: 275, x2: 250, y2: 325 });
    expect(model.dims).toEqual({ w: 250, h: 325 });
  });

  test("hitTestNode picks the smallest node containing the point", () => {
    const model = inspectorModel({
      x1: 0, y1: 0, x2: 1000, y2: 1000,
      children: [
        { text: "big", x1: 0, y1: 0, x2: 500, y2: 500 },
        { text: "small", x1: 100, y1: 100, x2: 200, y2: 200 },
      ],
    })!;
    expect(hitTestNode(model, 150, 150)).toBe(2); // the small node wins where they overlap
    expect(hitTestNode(model, 400, 400)).toBe(1);
    expect(hitTestNode(model, 900, 900)).toBe(0);
  });

  test("hit-test ties on identical bounds resolve to the deepest node, like a browser hit-test", () => {
    // Web DOMs wrap elements in containers with byte-identical bounds (a link filling its list
    // item, a button around its label) — the wrapper must not shadow the element itself.
    const model = inspectorModel({
      x1: 0, y1: 0, x2: 100, y2: 100,
      children: [{ x1: 10, y1: 10, x2: 90, y2: 90, children: [{ text: "Buy", x1: 10, y1: 10, x2: 90, y2: 90 }] }],
    })!;
    expect(hitTestNode(model, 50, 50)).toBe(2);
    // Overlapping equal-area SIBLINGS resolve to the later one — DOM paint order.
    const siblings = inspectorModel({
      x1: 0, y1: 0, x2: 100, y2: 100,
      children: [{ text: "under", x1: 10, y1: 10, x2: 50, y2: 50 }, { text: "over", x1: 10, y1: 10, x2: 50, y2: 50 }],
    })!;
    expect(hitTestNode(siblings, 30, 30)).toBe(2);
  });

  test("web (Playwright) nodes render their ARIA fields — labels and details, never (node)", () => {
    // TrailblazeNode shape with DriverNodeDetail.Web fields (ariaRole / ariaName / dataTestId):
    // the accessible name feeds the text leg of the label, the role feeds the class leg.
    const model = inspectorModel({
      bounds: { left: 0, top: 0, right: 1280, bottom: 800 },
      driverDetail: { ariaRole: "main" },
      children: [{
        bounds: { left: 10, top: 10, right: 200, bottom: 40 },
        driverDetail: { ariaRole: "button", ariaName: "Sign in", dataTestId: "sign-in" },
      }],
    })!;
    expect(model.nodes[1].label).toContain("Sign in");
    expect(model.nodes[0].label).toContain("main");
    expect(model.nodes.every((n) => n.label !== "(node)")).toBe(true);
    const details = inspectorDetailsHtml(model, 1);
    expect(details).toContain("Name");
    expect(details).toContain("Sign in");
    expect(details).toContain("Role");
    expect(details).toContain("button");
    expect(details).toContain("Test ID");
    expect(details).toContain("sign-in");
  });

  test("tree html renders collapsible branches and selectable rows; details render the selection", () => {
    const model = inspectorModel({
      className: "Root", x1: 0, y1: 0, x2: 100, y2: 100,
      children: [{ text: "Leaf", x1: 0, y1: 0, x2: 10, y2: 10 }],
    })!;
    const tree = inspectorTreeHtml(model, 1);
    expect(tree).toContain("<details");
    expect(tree).toContain('data-inspnode="0"');
    expect(tree).toContain('data-inspnode="1"');
    expect(tree).toContain('inspnoderow sel');
    // Exactly one tab stop per row: the row span is the focusable control, and the branch
    // <summary> is out of the tab order (no nested focusables inside it).
    expect(tree).toContain('<summary data-insptoggle tabindex="-1">');
    const details = inspectorDetailsHtml(model, 1);
    expect(details).toContain("Leaf");
    expect(details).toContain("Bounds");
    // No selection yet → a hint, not an empty pane.
    expect(inspectorDetailsHtml(model, null)).toContain("Hover the screenshot");
    // A hovered node takes precedence over the committed selection and is marked as a preview.
    expect(inspectorDetailsHtml(model, 0, 1)).toContain("Click to keep");
    expect(inspectorDetailsHtml(model, 0, 1)).toContain("Leaf");
    expect(inspectorDetailsHtml(model, 1, 1)).not.toContain("Click to keep");
    const rects = inspectorRectsHtml(model, 1);
    expect(rects).toContain('class="insprect sel"');
  });
});

// ── web bounds merge + dialog-scoped hit-testing ──────────────────────────────────────────────
// A web capture logs the same ARIA snapshot as two parallel trees whose bounds come from two
// different DOM correlations: `trailblazeNodeTree` (the shape the inspector renders) gets bounds
// from a fuzzy role+name walk that leaves most nodes with no geometry — and occasionally assigns
// a node the rect of a same-named element elsewhere on the page — while the legacy
// `viewHierarchy` sibling gets ref-resolved bounds covering 3–10x more nodes. Hit-testing the
// sparse tree resolved most of a form to its giant `<main>` landmark (the reported bug: on a
// web app's "Create item" dialog, only rows whose node happened to have bounds were selectable;
// everything else lit up the whole main container). The fix has two halves, both covered here:
//  - extraction grafts the dense legacy bounds onto the ARIA tree (mergeWebHierarchyBounds);
//  - hitTestNode scopes candidates to the last dialog containing the point, so the occluded page
//    UNDER a modal (which keeps its bounds in the capture) can't steal the hit.
describe("web hierarchy bounds merge + dialog-scoped hit-testing", () => {
  // Shaped like the session this was reported against: a full-screen "Create item" dialog over
  // an items table, a `<main>` landmark filling the dialog, and form rows where only some nodes
  // carry bounds in the ARIA tree while the legacy tree has them all.
  const createItemNodeTree = {
    driverDetail: { class: "web", ariaRole: "document", ariaDescriptor: "document" },
    children: [
      // The occluded page under the dialog — still in the snapshot, with real bounds.
      {
        bounds: { left: 312, top: 170, right: 1248, bottom: 569 },
        driverDetail: { class: "web", ariaRole: "table" },
        children: [{ bounds: { left: 312, top: 218, right: 653, bottom: 275 }, driverDetail: { class: "web", ariaRole: "cell", ariaName: "Row A" } }],
      },
      {
        bounds: { left: 0, top: 0, right: 1280, bottom: 800 },
        driverDetail: { class: "web", ariaRole: "dialog", ariaName: "Create item" },
        children: [{
          bounds: { left: 0, top: 152, right: 1280, bottom: 784 },
          driverDetail: { class: "web", ariaRole: "main", isLandmark: true },
          children: [
            { bounds: { left: 44, top: 152, right: 844, bottom: 216 }, driverDetail: { class: "web", ariaRole: "combobox", ariaName: "Item type", dataTestId: "field_select_itemData.productType" } },
            { driverDetail: { class: "web", ariaRole: "text", ariaName: "Name (required)" } }, // no bounds in either tree
            { driverDetail: { class: "web", ariaRole: "textbox", ariaName: "Name (required)" } }, // bounds only in the legacy tree
            { driverDetail: { class: "web", ariaRole: "button", ariaName: "Auto create" } }, // bounds only in the legacy tree
            { bounds: { left: 60, top: 344, right: 693, bottom: 368 }, driverDetail: { class: "web", ariaRole: "textbox", ariaName: "Price" } },
            { driverDetail: { class: "web", ariaRole: "textbox", ariaName: "Customer-facing description" } }, // bounds only in the legacy tree
          ],
        }],
      },
    ],
  };
  // Same structure in the legacy ViewHierarchyTreeNode shape, with the dense ref-resolved bounds.
  const createItemLegacyTree = {
    children: [
      {
        className: "table", x1: 312, y1: 170, x2: 1248, y2: 569,
        children: [{ className: "cell", text: "Row A", x1: 312, y1: 218, x2: 653, y2: 275 }],
      },
      {
        className: "dialog", text: "Create item", x1: 0, y1: 0, x2: 1280, y2: 800,
        children: [{
          className: "main", x1: 0, y1: 152, x2: 1280, y2: 784,
          children: [
            { className: "combobox", text: "Item type", x1: 44, y1: 152, x2: 844, y2: 216 },
            { className: "text", text: "Name (required)" },
            { className: "textbox", text: "Name (required)", x1: 60, y1: 252, x2: 732, y2: 276 },
            { className: "button", text: "Auto create", x1: 748, y1: 244, x2: 788, y2: 284 },
            { className: "textbox", text: "Price", x1: 60, y1: 344, x2: 693, y2: 368 },
            { className: "textbox", text: "Customer-facing description", x1: 60, y1: 464, x2: 828, y2: 486 },
          ],
        }],
      },
    ],
  };
  // Pre-order keys of the merged model: 0 document, 1 table, 2 cell, 3 dialog, 4 main,
  // 5 combobox, 6 text Name, 7 textbox Name, 8 button Auto create, 9 textbox Price,
  // 10 textbox description.
  const KEY = { table: 1, cell: 2, dialog: 3, main: 4, combobox: 5, nameBox: 7, autoCreate: 8, price: 9, description: 10 };

  test("hovering each form row resolves the row's element, not the <main> landmark it sits in", () => {
    const merged = mergeWebHierarchyBounds(createItemNodeTree, createItemLegacyTree);
    const model = inspectorModel(merged)!;
    // The rows whose ARIA nodes had no geometry — the reported failure — now resolve themselves…
    expect(hitTestNode(model, 396, 264)).toBe(KEY.nameBox); // "Name (required)" textbox
    expect(hitTestNode(model, 768, 264)).toBe(KEY.autoCreate); // "Auto create" button
    expect(hitTestNode(model, 444, 475)).toBe(KEY.description); // "Customer-facing description"
    // …the rows that already worked keep working…
    expect(hitTestNode(model, 444, 184)).toBe(KEY.combobox); // "Item type" combobox
    expect(hitTestNode(model, 376, 356)).toBe(KEY.price); // "Price" textbox
    // …and a gap between rows honestly resolves the most specific thing there: <main>.
    expect(hitTestNode(model, 400, 320)).toBe(KEY.main);
    // The merge keeps the ARIA tree's detail — the reason we graft bounds instead of swapping
    // to the legacy tree, which has no test ids / landmark flags.
    expect(model.nodes[KEY.combobox].fields).toContainEqual({ k: "Test ID", v: "field_select_itemData.productType" });
    // Without the merge the same probes dead-end at giant containers (main under dialog scoping):
    // the failure this fix exists for.
    const sparse = inspectorModel(createItemNodeTree)!;
    expect(hitTestNode(sparse, 396, 264)).toBe(KEY.main);
    expect(hitTestNode(sparse, 768, 264)).toBe(KEY.main);
  });

  test("a point inside the dialog never resolves to the occluded page underneath it", () => {
    const merged = mergeWebHierarchyBounds(createItemNodeTree, createItemLegacyTree);
    const model = inspectorModel(merged)!;
    // (330, 230) sits inside the background table's cell (smaller than <main>), but the dialog
    // covers it — the visible surface there is the dialog's main region.
    expect(hitTestNode(model, 330, 230)).toBe(KEY.main);
    // Sanity: the same point on a model WITHOUT the dialog present would resolve the cell.
    const noDialog = inspectorModel({
      driverDetail: { class: "web", ariaRole: "document" },
      children: [(createItemNodeTree.children as any[])[0]],
    })!;
    expect(hitTestNode(noDialog, 330, 230)).toBe(2); // cell "Row A" (document → table → cell)
  });

  test("stacked and nested dialogs scope to the last one containing the point", () => {
    const model = inspectorModel({
      driverDetail: { class: "web", ariaRole: "document" },
      children: [
        { bounds: { left: 0, top: 0, right: 100, bottom: 100 }, driverDetail: { class: "web", ariaRole: "button", ariaName: "under everything" } },
        {
          bounds: { left: 10, top: 10, right: 90, bottom: 90 }, driverDetail: { class: "web", ariaRole: "dialog", ariaName: "first" },
          children: [
            { bounds: { left: 20, top: 20, right: 40, bottom: 40 }, driverDetail: { class: "web", ariaRole: "button", ariaName: "in first" } },
            {
              bounds: { left: 30, top: 30, right: 80, bottom: 80 }, driverDetail: { class: "web", ariaRole: "dialog", ariaName: "nested" },
              children: [{ bounds: { left: 60, top: 60, right: 70, bottom: 70 }, driverDetail: { class: "web", ariaRole: "button", ariaName: "in nested" } }],
            },
          ],
        },
      ],
    })!;
    // Keys: 0 document, 1 under-everything button, 2 first dialog, 3 in-first button,
    // 4 nested dialog, 5 in-nested button.
    expect(hitTestNode(model, 65, 65)).toBe(5); // nested dialog's own button
    expect(hitTestNode(model, 35, 35)).toBe(4); // covered by the nested dialog — not "in first" (3)
    expect(hitTestNode(model, 25, 25)).toBe(3); // first dialog's button, outside the nested one
    expect(hitTestNode(model, 5, 5)).toBe(1); // outside every dialog: the page is the surface
  });

  test("extractTrace lifts the MERGED hierarchy onto the trace row for web records", () => {
    const trace = core.extractTrace([
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "w1", trailblazeTool: { raw: { text: "Item type" } }, screenshotFile: "w.png", trailblazeNodeTree: createItemNodeTree, viewHierarchy: createItemLegacyTree, deviceWidth: 1280, deviceHeight: 800, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01Z" },
    ]);
    const row = trace.find((t: any) => t.label === "tapOnElement") as any;
    const model = inspectorModel(row.viewHierarchy)!;
    expect(hitTestNode(model, 396, 264)).toBe(KEY.nameBox);
    // The raw record itself is untouched — the merge builds a new tree for the row.
    expect((createItemNodeTree.children[1].children[0].children[2] as any).bounds).toBeUndefined();
  });

  test("merge policy: legacy bounds win, node-tree bounds survive where the legacy tree has none", () => {
    const merged = mergeWebHierarchyBounds(
      {
        driverDetail: { class: "web", ariaRole: "document" },
        children: [
          // Fuzzy-matched to the WRONG element (a same-named node elsewhere on the page) — the
          // ref-resolved legacy rect must override it.
          { bounds: { left: 0, top: 900, right: 10, bottom: 910 }, driverDetail: { class: "web", ariaRole: "link", ariaName: "Pricing" } },
          // No legacy bounds → the node tree's own rect survives.
          { bounds: { left: 5, top: 5, right: 15, bottom: 15 }, driverDetail: { class: "web", ariaRole: "img" } },
          // All-zero legacy coordinates mean "unset", not a rect at the origin.
          { driverDetail: { class: "web", ariaRole: "text", ariaName: "loose" } },
        ],
      },
      {
        children: [
          { className: "link", text: "Pricing", x1: 40, y1: 4, x2: 80, y2: 20 },
          { className: "img" },
          { className: "text", text: "loose", x1: 0, y1: 0, x2: 0, y2: 0 },
        ],
      },
    ) as any;
    expect(merged.children[0].bounds).toEqual({ left: 40, top: 4, right: 80, bottom: 20 });
    expect(merged.children[1].bounds).toEqual({ left: 5, top: 5, right: 15, bottom: 15 });
    expect(merged.children[2].bounds).toBeUndefined();
  });

  test("merge bails to the untouched node tree on any structural or role disagreement, and skips non-web trees", () => {
    const webTree = { driverDetail: { class: "web", ariaRole: "document" }, children: [{ driverDetail: { class: "web", ariaRole: "button", ariaName: "Go" } }] };
    // Child-count mismatch → same instance back.
    expect(mergeWebHierarchyBounds(webTree, { children: [] })).toBe(webTree);
    // Role mismatch at any position → same instance back.
    expect(mergeWebHierarchyBounds(webTree, { children: [{ className: "link", x1: 1, y1: 1, x2: 2, y2: 2 }] })).toBe(webTree);
    // A non-web tree (Android accessibility) is never rewritten, even with a parallel legacy tree.
    const androidTree = { driverDetail: { class: "androidAccessibility", className: "android.view.View" }, children: [] };
    expect(mergeWebHierarchyBounds(androidTree, { children: [] })).toBe(androidTree);
    // Missing either side degrades to the extractor's existing fallthrough.
    expect(mergeWebHierarchyBounds(null, { children: [] })).toBe(null);
    expect(mergeWebHierarchyBounds(webTree, null)).toBe(webTree);
  });

  // A real capture, scrubbed: 405 nodes from a web session with a sticky header nav and a long
  // scrolling body (see the fixture's _source note). Its ARIA tree carries bounds on 202 nodes —
  // and the fuzzy matcher SWAPPED the header nav link's rect with a same-named footer list item's
  // (node 15 sits at y≈9636 in the ARIA tree, node 395 at y≈5) — while the legacy tree carries
  // ref-resolved bounds on 260.
  test("a real 405-node web capture: merged bounds are denser and the header nav link wins its own hover", () => {
    const raw = inspectorModel(webMergeFixture.trailblazeNodeTree)!;
    const merged = inspectorModel(mergeWebHierarchyBounds(webMergeFixture.trailblazeNodeTree, webMergeFixture.viewHierarchy))!;
    const bounded = (m: ReturnType<typeof inspectorModel>) => m!.nodes.filter((n) => n.bounds).length;
    expect(bounded(raw)).toBe(202);
    expect(bounded(merged)).toBe(263); // 260 legacy rects, plus 3 the ARIA walk alone resolved
    // Hovering the header nav link (node 15; its on-screen rect is 402,4–479,67) used to light the
    // footer list item that stole its rect. With the ref-resolved bounds grafted on, it wins.
    expect(hitTestNode(raw, 440, 35)).toBe(395);
    expect(hitTestNode(merged, 440, 35)).toBe(15);
    // Sweep a 16×10 grid over the 1280×800 viewport (the capture's bounds are page-relative and
    // run far below the fold, so this probes the first viewport only): the share of points that
    // resolve to a node covering more than half the viewport — the "everything selects a giant
    // container" failure — must drop once the dense bounds are in place. Deliberately relative,
    // not exact counts: the absolute numbers also encode the tie-break, the grid resolution, and
    // the dialog scoping, and would redden this test on unrelated hit-test tuning.
    const bigHits = (m: ReturnType<typeof inspectorModel>) => {
      const { deviceWidth: w, deviceHeight: h } = webMergeFixture;
      let big = 0;
      for (let gx = 0; gx < 16; gx++) {
        for (let gy = 0; gy < 10; gy++) {
          const key = hitTestNode(m!, (w * (gx + 0.5)) / 16, (h * (gy + 0.5)) / 10);
          const b = key != null ? m!.nodes[key].bounds : null;
          if (b && (b.x2 - b.x1) * (b.y2 - b.y1) > 0.5 * w * h) big++;
        }
      }
      return big;
    };
    expect(bigHits(merged)).toBeLessThan(bigHits(raw));
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

// ── Selector suggestions (UI Inspector, committed selection) ─────────────────────────────────────
// Viewer-level contract for run-report-selectors.ts: suggestions render for the COMMITTED selection
// of a TrailblazeNode capture when an engine is present, and every absence path (no engine, legacy
// tree) leaves the inspector exactly as it was — empty container, no note, no errors. The engine is
// stubbed at its DOCUMENTED contract: the raw string-in/string-out global the Kotlin/JS bundle
// installs (SelectorEngineJs.kt); the real compiled engine is pinned byte-identical to the JVM by
// :trailblaze-selector-engine-js's parity suite.
describe("UI Inspector selector suggestions", () => {
  // A TrailblazeNode capture (accessibility driver): required driverDetail, {left,top,right,bottom}
  // bounds — pre-order inspector keys: 0 = root (nodeId 7), 1 = "Login" (nodeId 3),
  // 2 = "Help" (nodeId 5).
  const tbTree = {
    nodeId: 7,
    bounds: { left: 0, top: 0, right: 1080, bottom: 2400 },
    driverDetail: { class: "androidAccessibility", className: "android.widget.FrameLayout" },
    children: [{
      nodeId: 3,
      bounds: { left: 90, top: 600, right: 990, bottom: 720 },
      driverDetail: { class: "androidAccessibility", text: "Login", className: "android.widget.Button", clickable: true },
    }, {
      nodeId: 5,
      bounds: { left: 90, top: 800, right: 990, bottom: 920 },
      driverDetail: { class: "androidAccessibility", text: "Help", className: "android.widget.Button", clickable: true },
    }],
  };
  const tbLogs = [
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap login" }, timestamp: "2024-01-01T00:00:00Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "t1", trailblazeTool: { raw: { text: "Login" } }, screenshotFile: "a.png", trailblazeNodeTree: tbTree, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01Z" },
  ];
  const tbShots = { "a.png": "data:image/png;base64,AAA" };
  const tbPayload = () => payloadOf(core.buildMultiReportHtml({
    generatedAt: "now",
    sessions: [{ meta: { title: "Run", status: "failed" }, trace: core.extractTrace(tbLogs), llmLogs: [], shots: tbShots }],
  }));
  const tapStepOf = (payload: any) => payload.sessions[0].trace.find((t: any) => t.label === "tapOnElement").i;
  const suggestionsBox = (overlay: any) => overlay.querySelector("[data-inspselectors]");
  const nodeRowOf = (overlay: any, key: number) => overlay.querySelectorAll("[data-inspnode]").find((el: any) => el.dataset.inspnode === String(key));
  const commitNode = (overlay: any, key: number) => overlay.onclick({ preventDefault() {}, target: nodeRowOf(overlay, key) });
  const settled = () => new Promise((resolve) => setTimeout(resolve, 25));
  // The documented raw-global contract, instrumented so tests can observe when and with what the
  // engine is asked. Installed per test; afterEach removes it so absence tests stay absent.
  const installEngineStub = () => {
    const seen: Array<{ tree: unknown; nodeId: string }> = [];
    (globalThis as Record<string, unknown>).TrailblazeSelectorEngine = {
      computeSelectorAnalysis: (tree: string, nodeId: string) => {
        seen.push({ tree: JSON.parse(tree), nodeId });
        return JSON.stringify({
          options: [
            { selector: { androidAccessibility: { textRegex: "Login" } }, strategy: "Text", isBest: true, matchCount: 1, matchingNodeIds: [3], resolvedCenterX: 540, resolvedCenterY: 660, hitsTarget: true },
            { selector: { androidAccessibility: { classNameRegex: "android.widget.Button" }, index: 0 }, strategy: "Structural: class + index", isBest: false, matchCount: 1, matchingNodeIds: [3], resolvedCenterX: 540, resolvedCenterY: 660, hitsTarget: true },
          ],
        });
      },
      resolveTapTarget: () => JSON.stringify({ roundTripValid: false }),
      resolveSelector: () => JSON.stringify({ matchCount: 0, matchingNodeIds: [] }),
    };
    return seen;
  };
  afterEach(() => {
    delete (globalThis as Record<string, unknown>).TrailblazeSelectorEngine;
  });

  test("committing a selection renders ranked suggestions computed for that node's nodeId", async () => {
    const seen = installEngineStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    // Inspector key 1 (pre-order first child) maps to nodeId 3 — the id the engine was asked about.
    expect(seen).toHaveLength(1);
    expect(seen[0].nodeId).toBe("3");
    const html = String(suggestionsBox(overlay).innerHTML);
    expect(html).toContain("Selector suggestions");
    expect(html).toContain("UNIQUE");
    expect(html).toContain("BEST");
    expect(html).toContain("textRegex: Login");
    expect(html).toContain("Structural (content-free)");
    expect(html).toContain('data-inspselcopy="0"');
  });

  test("re-committing the same node renders from cache — the engine is asked once per node", async () => {
    const seen = installEngineStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    commitNode(overlay, 1);
    await settled();
    expect(seen).toHaveLength(1);
  });

  // The suggestions subject follows HOVER (like the properties card), reverting to the committed
  // selection on hover-out. The fake screenshot is 100x200 for a 1080x2400 capture: (50, 55)
  // maps into "Login" (key 1) and (50, 72) into "Help" (key 2).
  const hoverShot = (overlay: any, clientX: number, clientY: number) =>
    overlay.onpointermove({ pointerType: "mouse", target: overlay.querySelector(".inspshotwrap"), clientX, clientY });
  // rAF-less hover throttle (~16ms) + the 120ms hover debounce + the async render.
  const hoverSettled = () => new Promise((resolve) => setTimeout(resolve, 300));

  test("hovering the screenshot computes suggestions for the hovered node, labeled as a preview", async () => {
    const seen = installEngineStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 0);
    await settled();
    expect(seen.map((s) => s.nodeId)).toEqual(["7"]);
    hoverShot(overlay, 50, 55);
    await hoverSettled();
    expect(seen.map((s) => s.nodeId)).toEqual(["7", "3"]);
    const html = String(suggestionsBox(overlay).innerHTML);
    expect(html).toContain("hover preview");
    expect(html).toContain("&quot;Login&quot;"); // the subject label names the hovered node
    // Hover-out restores the committed node's suggestions from cache — no new engine call.
    overlay.onpointerleave();
    await settled();
    expect(seen).toHaveLength(2);
    const restored = String(suggestionsBox(overlay).innerHTML);
    expect(restored).not.toContain("hover preview");
    expect(restored).toContain("&lt;FrameLayout&gt;"); // the committed root's label
  });

  test("a hover sweep debounces: only the node the pointer dwells on is computed", async () => {
    const seen = installEngineStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    hoverShot(overlay, 50, 55); // "Login" — swept over, never dwelt on
    await new Promise((resolve) => setTimeout(resolve, 60)); // > rAF fallback, < debounce
    hoverShot(overlay, 50, 72); // "Help" — the dwell target
    await hoverSettled();
    expect(seen.map((s) => s.nodeId)).toEqual(["5"]);
  });

  test("committing while a hover preview is showing re-labels it as the selection", async () => {
    installEngineStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    hoverShot(overlay, 50, 55);
    await hoverSettled();
    expect(String(suggestionsBox(overlay).innerHTML)).toContain("hover preview");
    // Clicking the same point commits the hovered node; same cards, no longer a preview.
    overlay.onclick({ preventDefault() {}, target: overlay.querySelector(".inspshotwrap"), clientX: 50, clientY: 55 });
    await settled();
    expect(String(suggestionsBox(overlay).innerHTML)).not.toContain("hover preview");
  });

  // ── mismatch visualization ────────────────────────────────────────────────────────────────────
  // A card whose resolved tap would land on a DIFFERENT element names the interceptor and, while
  // engaged (hover or click-pin), paints the mismatch onto the screenshot: intended bounds,
  // actual receiver bounds, tap point, legend.
  const installMismatchStub = () => {
    (globalThis as Record<string, unknown>).TrailblazeSelectorEngine = {
      computeSelectorAnalysis: () => JSON.stringify({
        options: [
          // Tap for "Login" (nodeId 3) resolves to (540, 660) but the hit test says the root
          // (nodeId 7) would receive it.
          { selector: { androidAccessibility: { textRegex: "Login" } }, strategy: "Text", isBest: true, matchCount: 1, matchingNodeIds: [3], resolvedCenterX: 540, resolvedCenterY: 660, hitsTarget: false, hitNodeId: 7 },
        ],
      }),
      resolveTapTarget: () => JSON.stringify({ roundTripValid: false }),
      resolveSelector: () => JSON.stringify({ matchCount: 0, matchingNodeIds: [] }),
    };
  };
  const vizLayer = (overlay: any) => overlay.querySelector("[data-inspselvizlayer]");
  const vizCardTarget = { closest: (sel: string) => (sel === "[data-inspselviz]" ? { dataset: { inspselviz: "0" } } : null) };

  test("a mismatch card names the intercepting element and paints/clears the visualization on engage/disengage", async () => {
    installMismatchStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    const html = String(suggestionsBox(overlay).innerHTML);
    expect(html).toContain("Tap (540, 660) lands on &lt;FrameLayout&gt; — not this element");
    expect(html).toContain('data-inspselviz="0"');
    expect(String(vizLayer(overlay).innerHTML)).toBe("");
    // Engage (pointer over the card): intended = "Login" bounds (90..990 x 600..720 of 1080x2400),
    // actual = the root, tap marker at (540, 660), plus the legend.
    overlay.onpointerover({ target: vizCardTarget });
    const painted = String(vizLayer(overlay).innerHTML);
    expect(painted).toContain('class="inspselvizrect intended"');
    expect(painted).toContain("left:8.333%");
    expect(painted).toContain('class="inspselvizrect actual"');
    expect(painted).toContain('class="inspselviztap"');
    expect(painted).toContain("left:50.000%;top:27.500%");
    expect(painted).toContain("actual tap target");
    // The existing selection paint is untouched by the viz layer.
    expect(overlay.querySelectorAll("[data-insprect]").find((el: any) => el.dataset.insprect === "1").classList.contains("sel")).toBe(true);
    // Disengage (pointer out, not pinned) clears the paint.
    overlay.onpointerout({ target: vizCardTarget });
    expect(String(vizLayer(overlay).innerHTML)).toBe("");
  });

  test("clicking a mismatch card pins the visualization; clicking again unpins", async () => {
    installMismatchStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    overlay.onclick({ preventDefault() {}, target: vizCardTarget });
    expect(String(vizLayer(overlay).innerHTML)).toContain("inspselvizrect");
    // A pointer-out no longer clears a pinned paint.
    overlay.onpointerout({ target: vizCardTarget });
    expect(String(vizLayer(overlay).innerHTML)).toContain("inspselvizrect");
    // Toggling the card off clears it.
    overlay.onclick({ preventDefault() {}, target: vizCardTarget });
    expect(String(vizLayer(overlay).innerHTML)).toBe("");
  });

  test("the copy button yields that suggestion's trail-file nodeSelector YAML", async () => {
    installEngineStub();
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    let copied: string | null = null;
    (globalThis as Record<string, unknown>).navigator = { clipboard: { writeText(text: string) { copied = text; return Promise.resolve(); } } };
    const copyBtn = { dataset: { inspselcopy: "0" }, textContent: "Copy" };
    overlay.onclick({ preventDefault() {}, target: { closest: (sel: string) => (sel === "[data-inspselcopy]" ? copyBtn : null) } });
    await settled();
    expect(copied).toBe("nodeSelector:\n  androidAccessibility:\n    textRegex: Login");
  });

  test("no engine anywhere → committing renders no suggestions section and the inspector still works", async () => {
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    expect(String(suggestionsBox(overlay).innerHTML)).toBe("");
    // The rest of the inspector is untouched: the committed row highlights and details render.
    expect(nodeRowOf(overlay, 1).classList.contains("sel")).toBe(true);
    expect(String(overlay.querySelector(".inspdetails").innerHTML)).toContain("Login");
  });

  // The engine chunk rides after the session chunks, so an inspector can be open and usable before
  // the chunk exists. That window must not read like the permanent no-engine path.
  test("a selection made while the document tail is still streaming picks up the engine when it lands", async () => {
    const payload = tbPayload();
    const state = renderViewerState(payload, { inspect: tapStepOf(payload), loadingDocument: true });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    expect(String(suggestionsBox(overlay).innerHTML)).toBe("");
    // The tail arrives: the chunk evaluates (installing the global) and the document completes.
    const seen = installEngineStub();
    state.settleDocument();
    await new Promise((resolve) => setTimeout(resolve, 250));
    expect(seen.map((s) => s.nodeId)).toEqual(["3"]);
    expect(String(suggestionsBox(overlay).innerHTML)).toContain("inspselcard");
  });

  test("legacy ViewHierarchyTreeNode captures get no suggestions section even with an engine present", async () => {
    const seen = installEngineStub();
    // The legacy shape: no driverDetail, x1..y2 bounds — the excluded TapSelectorV2 domain.
    const legacyLogs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap login" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "t1", trailblazeTool: { raw: { text: "Login" } }, screenshotFile: "a.png", viewHierarchyFiltered: { nodeId: 1, className: "android.widget.FrameLayout", x1: 0, y1: 0, x2: 1080, y2: 2400, children: [{ nodeId: 2, text: "Login", x1: 90, y1: 600, x2: 990, y2: 720 }] }, successful: true, durationMs: 100, timestamp: "2024-01-01T00:00:01Z" },
    ];
    const payload = payloadOf(core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{ meta: { title: "Run", status: "failed" }, trace: core.extractTrace(legacyLogs), llmLogs: [], shots: tbShots }],
    }));
    const state = renderViewerState(payload, { inspect: tapStepOf(payload) });
    const overlay = state.zoomRoot;
    commitNode(overlay, 1);
    await settled();
    expect(seen).toHaveLength(0);
    expect(String(suggestionsBox(overlay).innerHTML)).toBe("");
    expect(nodeRowOf(overlay, 1).classList.contains("sel")).toBe(true);
  });

  test("buildMultiReportHtml embeds the engine chunk once at document level, only when passed", () => {
    const sessions = [{ meta: { title: "Run", status: "failed" }, trace: core.extractTrace(tbLogs), llmLogs: [], shots: tbShots }];
    const without = core.buildMultiReportHtml({ generatedAt: "now", sessions });
    expect(without).not.toContain('id="tb-selector-engine"');
    const withEngine = (core.buildMultiReportHtml as any)({ generatedAt: "now", sessions, selectorEngine: { gz: "abc123" } });
    expect(withEngine.split('id="tb-selector-engine"')).toHaveLength(2);
    expect(withEngine).toContain('<script type="application/json" id="tb-selector-engine">{"gz":"abc123"}</script>');
    // …and never inside the boot index or a session chunk.
    expect(chunksOf(withEngine).index).not.toContain("abc123");
    expect(chunksOf(withEngine).sessions["0"]).not.toContain("abc123");
  });
});
