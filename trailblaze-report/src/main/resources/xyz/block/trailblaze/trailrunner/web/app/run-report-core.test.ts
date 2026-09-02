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
import { declaredTrailSteps, mergeWebHierarchyBounds, traceToolCallCount } from "./run-report-extract";
import { hitTestNode, inspectorDetailsHtml, inspectorModel, inspectorRectsHtml, inspectorTreeHtml } from "./run-report-inspector";
import { chunkJsonWithoutRuntimeAttachments } from "./run-report-payload";
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

describe("declaredTrailSteps", () => {
  test("lists a unified trail's steps in authored order", () => {
    const yaml = `config:
  target: demo
trailhead:
  step: Open the demo app
trail:
  - step: "Add a bagel to the cart"
    recording:
      ios:
        - tapOnElementBySelector:
            nodeSelector:
              ios:
                textRegex: Bagel
  - verify: "The cart shows one item"
  - step: Pay with the saved card`;
    expect(declaredTrailSteps(yaml)).toEqual([
      "Add a bagel to the cart",
      "The cart shows one item",
      "Pay with the saved card",
    ]);
  });

  test("reads the v1 list shape and ignores its tool-only items", () => {
    const yaml = `- config:
    target: demo
- prompts:
  - step: Sign in as the owner
    recording:
      tools:
      - launchApp:
          appId: com.example
  - verify: The home screen is visible
- tools:
  - assertVisibleBySelector:
      reason: not a step`;
    expect(declaredTrailSteps(yaml)).toEqual(["Sign in as the owner", "The home screen is visible"]);
  });

  test("reads block, quoted, and commented scalars the way the runner does", () => {
    const yaml = `trail:
  - verify: |-
      - The name is visible.
      - The badge is visible.
  - step: 'Tap "Done"'
  - step: Scroll to the bottom # authored note`;
    expect(declaredTrailSteps(yaml)).toEqual([
      "- The name is visible.\n- The badge is visible.",
      'Tap "Done"',
      "Scroll to the bottom",
    ]);
  });

  test("folds a plain step prompt wrapped across several lines, the way YAML does", () => {
    // The label the runner logs is the folded one, so reading only the first line would put the
    // declared list out of step with what ran and cost the reader the whole tail.
    const yaml = `trail:
  - step: Open the cart and then
      pay with the saved card
    recording:
      ios:
        - tapOnElementBySelector: {}
  - step: Sign out`;
    expect(declaredTrailSteps(yaml)).toEqual(["Open the cart and then pay with the saved card", "Sign out"]);
  });

  test("does not mistake a nested step-named tool argument for a declared step", () => {
    const yaml = `trail:
  - step: Advance the wizard
    recording:
      android:
        - wizard_advance:
            - step: 3`;
    expect(declaredTrailSteps(yaml)).toEqual(["Advance the wizard"]);
  });

  test("ends a block scalar at the step's own sibling keys", () => {
    // `recording:` is indented past the list marker but short of the block body. Reading by the
    // marker alone swallowed it, and the label came out as garbled recording YAML.
    const yaml = `trail:
  - verify: |-
      The receipt shows the total.
    recording:
      ios:
        - assertVisibleBySelector:
            reason: total is visible
  - step: Sign out`;
    expect(declaredTrailSteps(yaml)).toEqual(["The receipt shows the total.", "Sign out"]);
  });

  test("keeps a comment written after a quoted step out of the label", () => {
    const yaml = `trail:
  - step: "Open the cart" # why this step exists
  - step: 'Pay with the saved card'  # and this one
  - step: Sign out`;
    expect(declaredTrailSteps(yaml)).toEqual(["Open the cart", "Pay with the saved card", "Sign out"]);
  });

  test("folds a quoted step that wraps across lines, without keeping the quotes", () => {
    // YAML folds a wrapped quoted scalar into one space-joined string, and the runner logs it that
    // way. Reading only the first physical line leaves the quote characters in the label, which is
    // enough to make an executed step look unrelated to the one that was declared.
    const yaml = `trail:
  - step: "Load the merchant account
      and open the cart"
  - step: 'Pay with the saved card
      then wait for the receipt'
    recording:
      ios:
        - tapOnElementBySelector: {}
  - step: Sign out`;
    expect(declaredTrailSteps(yaml)).toEqual([
      "Load the merchant account and open the cart",
      "Pay with the saved card then wait for the receipt",
      "Sign out",
    ]);
  });

  test("returns nothing when the run captured no trail source", () => {
    expect(declaredTrailSteps(null)).toEqual([]);
    expect(declaredTrailSteps("config:\n  target: demo")).toEqual([]);
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
    expect(prompt).toContain("`./trailblaze app --v2`");
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
  /** The mark overlay currently painted over the preview pane (playback repaints it in place). */
  paneMark: () => string;
  selectedSteps: () => string[];
  /** `<step>:<kid>` ids of highlighted dispatch rows inside a folded step. */
  selectedKids: () => string[];
  hoverStep: (step: number) => void;
  leaveStep: (step: number) => void;
  hoverGroup: (step: number) => void;
  leaveGroup: (step: number) => void;
  hoverKid: (id: string) => void;
  leaveKid: (id: string) => void;
  hoverTimelineEvent: (key: string) => void;
  leaveTimelineEvent: (key: string) => void;
  scrubAttr: (name: string) => string | undefined;
  shotImg: { src: string; alt: string; onclick?: () => void; onkeydown?: (e: { key: string; preventDefault(): void; stopPropagation(): void }) => void };
  prevBtn: { disabled: boolean };
  nextBtn: { disabled: boolean };
  clickShot: () => void;
  keyShot: (key: string) => { defaultPrevented: boolean; stopped: boolean };
  hoverScrub: (fraction: number, marker?: { step: string; kind: string; color?: string }) => void;
  leaveScrub: () => void;
  scrubHoverState: () => { tooltipVisible: boolean; rangeVisible: boolean; step: string; kind: string; ariaHidden: string | undefined };
};

type ViewerOptions = { session?: number; step?: number; clickGroup?: number; toggleKids?: number; clickKid?: string; routeStep?: number; query?: string; legacyHash?: string; protocol?: string; copyLink?: boolean; clipboardRejects?: boolean; tab?: string; toggleCell?: string; lightboxAll?: boolean; galZoom?: number[]; zoomShot?: string; zoomKey?: "ArrowLeft" | "ArrowRight"; timelineKey?: "ArrowLeft" | "ArrowRight" | "ArrowUp" | "ArrowDown"; timelineKeyTarget?: string; tlStream?: number; tlStreamBeforeTab?: number; spaceOnStep?: number; timelineScrollTop?: number; focusedStep?: number; focusedGroup?: number; focusedTlStream?: number; llmEnter?: number; llmClick?: number; openTx?: number; txEscape?: boolean; inspect?: number; inspectEscape?: boolean; popstate?: string; deferHistoryBack?: boolean; transport?: "prev" | "next"; stackedTimeline?: boolean; shotLayoutShift?: boolean; copyLocalPrompt?: boolean; exportLogs?: boolean; exportRun?: boolean; pointerDown?: "outside" | "insideTimelineMenu"; gotoTrail?: boolean | string; pick?: number[]; openRetries?: number[]; pickClear?: boolean; pickOpen?: boolean; pickDiff?: boolean; cmpGap?: number; cmpLane?: string; cmpStream?: string; cmpEvent?: string; cmpSide?: { side: "base" | "vs"; value: number }; cmpJump?: string | string[]; trailOpen?: string; toggleLanes?: number[]; back?: boolean; viewer?: () => void; drive?: (ctx: PlaybackDriveContext) => void; payloadViaGlobal?: boolean; sprites?: Record<string, string[]>; deferBoot?: boolean; rebootViewer?: boolean; shellDocument?: boolean; chunks?: { index: string; sessions: Record<string, string>; sprites: Record<string, string> }; holdChunks?: number[]; holdSpriteChunks?: number[]; streamingChunks?: number[]; loadingDocument?: boolean; baseURI?: string };

function renderViewerState(payload: unknown, opts: ViewerOptions = {}): { html: string; htmlBeforeBoot: string; liveHtml: () => string; readHtml: () => string; timelineScrollTop: number; mainScrollTop: number; restoredFocus: string | null; route: string; readRoute: () => string; routeWrites: () => Array<{ method: string; next: string }>; historyBack: () => void; historyForward: () => void; flushHistoryBack: () => void; escapeOverlay: () => void; liveZoomRoot: () => any; zoomSrc: string | null; zoomRoot: any; copiedText: string | null; copyBtnText: () => string; timelineMenuOpen: boolean; spriteMeasures: Array<{ src: string; fireLoad: (naturalWidth: number) => void }>; tlvframeStyle: Record<string, string>; releaseChunks: () => void; partialChunkReads: () => number; loadingProgressWrites: () => number; settleDocument: () => void; documentKeyListeners: Array<(e: any) => void>; autoplayMarker: () => string | undefined; embeddedMarker: () => string | undefined; llmScrolledTo: string | null; cmpScrolledTo: () => string | null; llmRow: (i: number) => any; readRestoredFocus: () => string | null; pageClass: () => string; pageClassWrites: () => string[]; readActiveElement: () => any; live: () => { update: (i: number, payload: Record<string, unknown>) => void; destroy: () => void } | undefined; readTimelineScrollTop: () => number; readMainScrollTop: () => number; expandTimelineEvent: (key: string) => void; timelineEvent: (key: string) => { open: boolean; body: string } | undefined; openAttachment: (key: string) => void; pickClicksStopped: () => string[]; pickLabelClicksStopped: () => number; pickLabels: () => number; firePopstate: (next?: string) => void } {
  const handlers: { session: Record<string, () => void>; tab: Record<string, () => void>; step: Map<string, () => void>; group: Record<string, () => void>; groupEnter: Record<string, (e: any) => void>; groupLeave: Record<string, (e: any) => void>; kids: Record<string, (e: any) => void>; kidsel: Record<string, (e: any) => void>; stepKey: Map<string, (e: any) => void>; shot: Record<string, () => void>; tlStream: Record<string, () => void>; cellToggle: Record<string, (e: any) => void>; retryToggle: Record<string, (open: boolean) => void>; galZoom: Record<string, () => void>; llmKey: Record<string, (e: any) => void>; llmClick: Record<string, () => void>; txOpen: Record<string, () => void>; inspect: Record<string, () => void>; trailOpen: Record<string, () => void>; trailLane: Record<string, () => void>; attach: Record<string, () => void>; gotoTrail: Record<string, () => void>; pick: Record<string, (e: any) => void>; pickClick: Record<string, (e: any) => void>; pickClear?: () => void; pickOpen?: () => void; pickDiff?: () => void; cmpGap: Record<string, () => void>; cmpLane: Record<string, () => void>; cmpStream: Record<string, () => void>; cmpEvent: Record<string, () => void>; cmpSide: Record<string, (value: string) => void>; cmpJump: Record<string, () => void>; back?: () => void; documentKey?: (e: any) => void; timelinePlay?: () => void; gridMode?: () => void; prev?: () => void; next?: () => void; shotLoad?: () => void; copyLocalPrompt?: () => void; copyLink?: () => void; exportLogs?: () => void; exportRun?: () => void } = { session: {}, tab: {}, step: new Map(), group: {}, groupEnter: {}, groupLeave: {}, kids: {}, kidsel: {}, stepKey: new Map(), shot: {}, tlStream: {}, cellToggle: {}, retryToggle: {}, galZoom: {}, llmKey: {}, llmClick: {}, txOpen: {}, inspect: {}, trailOpen: {}, trailLane: {}, attach: {}, gotoTrail: {}, pick: {}, pickClick: {}, cmpGap: {}, cmpLane: {}, cmpStream: {}, cmpEvent: {}, cmpSide: {}, cmpJump: {} };
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
  // Dispatch rows inside a folded step. Persistent like stepEls below, so a drive test observes the
  // in-place highlight playback paints between full renders.
  const kidEls = new Map<string, any>();
  const kidEl = (id: string) => {
    if (!kidEls.has(id)) {
      kidEls.set(id, {
        dataset: { kidsel: id },
        classes: new Set<string>(),
        attrs: {} as Record<string, string>,
        classList: { add: (c: string) => kidEls.get(id).classes.add(c), remove: (c: string) => kidEls.get(id).classes.delete(c) },
        setAttribute(name: string, value: string) { this.attrs[name] = value; },
        removeAttribute(name: string) { delete this.attrs[name]; },
        scrollIntoView() {},
      });
    }
    return kidEls.get(id);
  };
  const stepEls = new Map<string, any>();
  const stepEl = (id: string) => {
    if (!stepEls.has(id)) {
      stepEls.set(id, {
        dataset: { step: id },
        classes: new Set<string>(),
        attrs: {} as Record<string, string>,
        click() {},
        classList: { add: (c: string) => stepEls.get(id).classes.add(c), remove: (c: string) => stepEls.get(id).classes.delete(c) },
        setAttribute(name: string, value: string) { this.attrs[name] = value; },
        removeAttribute(name: string) { delete this.attrs[name]; },
        focus: () => { restoredFocus = `[data-step="${id}"]`; },
        getBoundingClientRect: () => ({ top: (shotLoaded ? 500 : 300) - (opts.stackedTimeline ? mainScroller.scrollTop : timelineList.scrollTop), height: 40 }),
      });
    }
    return stepEls.get(id);
  };
  // Timeline event <details> stand-ins, keyed by data-lazykey. Every render replaces this markup,
  // so a node comes back closed with an empty body (the reset in `set innerHTML` below) exactly as
  // a freshly parsed one would — whatever reopens and refills it has to be the viewer.
  const tlEventEls = new Map<string, any>();
  // Attachment "Open" buttons inside a filled event body: clicking one pushes the attachment
  // dialog. Fresh nodes per wire pass, like the real DOM's.
  const attachButtons = (html: string) => [...String(html || "").matchAll(/data-attach="(\d+)"/g)].map((m: any) => ({
    dataset: { attach: m[1] },
    focus: () => {},
    set onclick(fn: (e: any) => void) { handlers.attach[m[1]] = () => fn({ stopPropagation() {} }); },
  }));
  const tlEventEl = (key: string, step?: string) => {
    if (!tlEventEls.has(key)) {
      const el: any = {
        dataset: { lazykey: key } as Record<string, string>,
        open: false,
        body: "",
        querySelectorAll(sel: string) { return sel === "[data-attach]" ? attachButtons(el.body) : []; },
        querySelector(sel: string) {
          if (sel !== ".fmtbody" && sel !== "pre") return null;
          return {
            set innerHTML(v: string) { el.body = v; },
            set textContent(v: string) { el.body = v; },
            querySelectorAll(inner: string) { return inner === "[data-attach]" ? attachButtons(el.body) : []; },
            // The generic-event fill puts the attachment rows before the <pre>.
            insertAdjacentHTML(_position: string, html: string) { el.body = html + el.body; },
          };
        },
      };
      tlEventEls.set(key, el);
    }
    const el = tlEventEls.get(key)!;
    if (step != null) el.dataset.tleventStep = step;
    return el;
  };
  // The lazy-fill listener wireLazyTimelineBodies registers on the timeline pane. The real pane is
  // recreated per render so listeners never stack; this single slot models that.
  let timelineToggle: ((e: any) => void) | null = null;
  timelineList.addEventListener = (name: string, fn: (e: any) => void) => { if (name === "toggle") timelineToggle = fn; };
  // A reader clicking an event summary open: the details opens, and 'toggle' fills its body.
  const expandTimelineEvent = (key: string) => {
    if (!app._h.includes(`data-lazykey="${key}"`)) throw new Error(`no rendered timeline event ${key}`);
    const el = tlEventEl(key);
    el.open = true;
    if (timelineToggle) timelineToggle({ target: el });
  };
  // Persistent per-request-table-row stand-ins (the LLM tab): activation highlights the row IN
  // PLACE (classList.toggle + aria-current) and opens the transcript lightbox — no re-render — so
  // the same objects must be visible to both the wire pass and the assertions.
  let llmScrolledTo: string | null = null;
  // The change stepper moves the page instead of re-rendering, so what it did is only visible as
  // the anchor it scrolled to.
  let cmpScrolledTo: string | null = null;
  const cmpAnchorEl = (key: string) => ({ dataset: { cmpAnchor: key }, classList: { add() {}, remove() {} }, scrollIntoView: () => { cmpScrolledTo = key; } });
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
  const scrubNode = () => {
    const el: any = {
      attrs: {} as Record<string, string>, classes: new Set<string>(), style: {} as Record<string, string>, textContent: "",
      classList: { add: (c: string) => el.classes.add(c), remove: (c: string) => el.classes.delete(c), toggle: (c: string, on: boolean) => on ? el.classes.add(c) : el.classes.delete(c) },
      setAttribute(name: string, value: string) { this.attrs[name] = value; },
      getBoundingClientRect: () => ({ left: 0, width: 100 }),
    };
    el.style.setProperty = (name: string, value: string) => { el.style[name] = value; };
    return el;
  };
  const scrubHoverStep = scrubNode();
  const scrubHoverKind = scrubNode();
  const scrubHover = scrubNode();
  scrubHover.querySelector = (sel: string) => sel === "[data-scrubhover-step]" ? scrubHoverStep : sel === "[data-scrubhover-kind]" ? scrubHoverKind : null;
  const scrubHoverRange = scrubNode();
  const scrubEl: any = scrubNode();
  // The preview pane's mark overlay. Playback repaints it in place — clear, then insert the current
  // action's mark — so a drive test reads what is actually drawn over the frame between renders.
  const shotWrap: any = {
    marks: [] as string[],
    querySelectorAll: () => shotWrap.marks.map(() => ({ remove: () => { shotWrap.marks = []; } })),
    insertAdjacentHTML: (_position: string, html: string) => { shotWrap.marks.push(html); },
  };
  const shotImg: any = { src: "", alt: "", get complete() { return shotLoaded; }, addEventListener(_name: string, fn: () => void) { handlers.shotLoad = fn; } };
  const previewPaneSeed = (html: string) => html.includes('id="shot"') || html.includes('class="shot ')
    ? '<div class="shotwrap"><img id="shot" class="shot"></div>'
    : html.includes('id="tlvframe"') ? '<div class="shotwrap"><div id="tlvframe"></div></div>'
    : html.includes('class="noshot"') ? '<div class="noshot"></div>'
    : "";
  const devicePlayer: any = {
    _h: "",
    classes: new Set<string>(),
    classList: {
      toggle: (name: string, force?: boolean) => {
        const on = force == null ? !devicePlayer.classes.has(name) : force;
        if (on) devicePlayer.classes.add(name); else devicePlayer.classes.delete(name);
        return on;
      },
    },
    set innerHTML(v: string) { this._h = v; },
    get innerHTML() { return this._h; },
    querySelector: (sel: string) => (sel === ".noshot" && devicePlayer._h.includes('class="noshot"') ? {} : null),
  };
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
  const pageClassWrites: string[] = [];
  const app: any = {
    _h: "",
    _className: "",
    set className(v: string) { this._className = v; pageClassWrites.push(v); },
    get className() { return this._className; },
    get offsetWidth() { return 100; },
    set innerHTML(v: string) { this._h = v; devicePlayer.innerHTML = previewPaneSeed(v); timelineList.scrollTop = 0; renders++; progressText = null; tlEventEls.forEach((el) => { el.open = false; el.body = ""; delete el.dataset.lazyfilled; }); },
    get innerHTML() { return this._h; },
    querySelectorAll(sel: string) {
      if (sel === "[data-session]") return [...this._h.matchAll(/data-session="(\d+)"/g)].map((m: any) => ({ dataset: { session: m[1] }, set onclick(fn: () => void) { handlers.session[m[1]] = fn; } }));
      if (sel === "[data-tab]") return [...this._h.matchAll(/data-tab="([a-z]+)"/g)].map((m: any) => ({ dataset: { tab: m[1] }, set onclick(fn: () => void) { handlers.tab[m[1]] = fn; } }));
      if (sel === "[data-step]") return [...this._h.matchAll(/data-step="(\d+)"/g)].map((m: any) => {
        const el = stepEl(m[1]);
        Object.defineProperty(el, "onclick", { configurable: true, set(fn: () => void) { handlers.step.set(m[1], fn); el.click = fn; } });
        return el;
      });
      // A step header is a disclosure control: it carries its own expansion in aria-expanded and
      // shows/hides the .stepgroupbody next to it, so the shim has to answer both.
      if (sel === "[data-group]") return [...this._h.matchAll(/data-group="(\d+)" aria-expanded="(true|false)" data-group-leads="(true|false)"/g)].map((m: any) => {
        const attrs: Record<string, string> = { "aria-expanded": m[2] };
        const body = { hidden: m[2] !== "true" };
        return {
          // groupLeads is what the render decided this group deserves on its own merit, so the
          // in-place playback path can re-apply the same rule without a full render.
          dataset: { group: m[1], groupLeads: m[3] },
          getAttribute: (name: string) => (name in attrs ? attrs[name] : null),
          setAttribute: (name: string, value: string) => { attrs[name] = value; },
          closest: () => ({ querySelector: () => body }),
          set onclick(fn: () => void) { handlers.group[m[1]] = fn; },
          set onpointerenter(fn: (e: any) => void) { handlers.groupEnter[m[1]] = fn; },
          set onpointerleave(fn: (e: any) => void) { handlers.groupLeave[m[1]] = fn; },
        };
      });
      if (sel === "[data-kids]") return [...this._h.matchAll(/data-kids="(\d+)" data-open="(\d)"/g)].map((m: any) => ({ dataset: { kids: m[1], open: m[2] }, set onclick(fn: (e: any) => void) { handlers.kids[m[1]] = fn; } }));
      if (sel === "[data-kidsel]") return [...this._h.matchAll(/data-kidsel="(\d+:\d+)"/g)].map((m: any) => {
        const el = kidEl(m[1]);
        Object.defineProperty(el, "onclick", { configurable: true, set(fn: (e: any) => void) { handlers.kidsel[m[1]] = fn; } });
        Object.defineProperty(el, "onkeydown", { configurable: true, set(_fn: unknown) {} });
        return el;
      });
      if (sel === ".timelineevent[data-lazykey]") return [...this._h.matchAll(/<details class="timelineevent[^"]*"[^>]*data-lazykey="([^"]+)"([^>]*)>/g)].map((m: any) => tlEventEl(m[1], (m[2].match(/data-tlevent-step="(\d+)"/) || [])[1]));
      if (sel === "[data-tlstream]") return [...this._h.matchAll(/data-tlstream="(\d+)"/g)].map((m: any) => ({ dataset: { tlstream: m[1] }, set onclick(fn: () => void) { handlers.tlStream[m[1]] = fn; } }));
      // data-shot-run names the session a frame belongs to — the Trail view puts several runs'
      // frames on one page, so without it every frame would resolve against the open session.
      if (sel === "[data-shot]") return [...this._h.matchAll(/data-shot="([^"]+)"(?: data-shot-run="(\d+)")?(?: data-shot-token="([^"]*)")?(?: data-shot-label="([^"]*)")?(?: data-shot-tool="([^"]*)")?/g)].map((m: any) => ({ dataset: { shot: m[1], shotRun: m[2], shotToken: m[3], shotLabel: m[4], shotTool: m[5] }, set onclick(fn: () => void) { handlers.shot[m[1]] = fn; } }));
      // Trail-view navigation: the per-cell "Open →", the index's trail entry points, and the
      // header's escape back out.
      if (sel === "[data-trail-lane]") return [...this._h.matchAll(/data-trail-lane="(\d+)"/g)].map((m: any) => ({ dataset: { trailLane: m[1] }, set onclick(fn: () => void) { handlers.trailLane[m[1]] = fn; } }));
      if (sel === "[data-trail-open]") return [...this._h.matchAll(/data-trail-open="([^"]+)"/g)].map((m: any) => ({ dataset: { trailOpen: m[1] }, set onclick(fn: () => void) { handlers.trailOpen[m[1]] = fn; } }));
      // The attribute VALUE names which trail the entry point opens — the viewer reads it to scope
      // the view, so a stub with an empty dataset would exercise a click that can never happen.
      if (sel === "[data-goto-trail]") return [...this._h.matchAll(/data-goto-trail="([^"]*)"/g)].map((m: any) => ({ dataset: { gotoTrail: m[1] }, set onclick(fn: () => void) { handlers.gotoTrail[m[1]] = fn; } }));
      // The compare checkboxes and the selection bar's two buttons. Both listeners are kept: the
      // change toggles the selection, and the click is the one that must NOT reach the row.
      if (sel === "[data-pick]") return [...this._h.matchAll(/data-pick="(\d+)"/g)].map((m: any) => ({ dataset: { pick: m[1] }, set onclick(fn: (e: any) => void) { handlers.pickClick[m[1]] = fn; }, set onchange(fn: (e: any) => void) { handlers.pick[m[1]] = fn; } }));
      // The label wrapping each checkbox. Its own click is the one a retry row's <summary> would
      // otherwise read as "expand the attempt history" — the input's click being stopped is not
      // enough, because a click on the label's padding never reaches the input.
      if (sel === ".idxpick") return [...this._h.matchAll(/class="idxpick[^"]*"/g)].map(() => ({ dataset: {}, set onclick(fn: (e: any) => void) { pickLabelClicks.push(fn); } }));
      if (sel === "[data-pick-clear]") return [...this._h.matchAll(/data-pick-clear/g)].map(() => ({ dataset: {}, set onclick(fn: () => void) { handlers.pickClear = fn; } }));
      if (sel === "[data-pick-open]") return [...this._h.matchAll(/data-pick-open/g)].map(() => ({ dataset: {}, set onclick(fn: () => void) { handlers.pickOpen = fn; } }));
      if (sel === "[data-pick-diff]") return [...this._h.matchAll(/data-pick-diff/g)].map(() => ({ dataset: {}, set onclick(fn: () => void) { handlers.pickDiff = fn; } }));
      if (sel === "[data-cmp-gap]") return [...this._h.matchAll(/data-cmp-gap="(\d+)"/g)].map((m: any) => ({ dataset: { cmpGap: m[1] }, set onclick(fn: () => void) { handlers.cmpGap[m[1]] = fn; } }));
      if (sel === "[data-cmp-lane]") return [...this._h.matchAll(/data-cmp-lane="([^"]+)"/g)].map((m: any) => ({ dataset: { cmpLane: m[1] }, set onclick(fn: () => void) { handlers.cmpLane[m[1]] = fn; } }));
      // The All-streams chip carries an EMPTY value, so the match allows "" and keys the handler on it.
      if (sel === "[data-cmp-stream]") return [...this._h.matchAll(/data-cmp-stream="([^"]*)"/g)].map((m: any) => ({ dataset: { cmpStream: m[1] }, set onclick(fn: () => void) { handlers.cmpStream[m[1]] = fn; } }));
      if (sel === "[data-cmp-event]") return [...this._h.matchAll(/data-cmp-event="([^"]+)"/g)].map((m: any) => ({ dataset: { cmpEvent: m[1] }, set onclick(fn: () => void) { handlers.cmpEvent[m[1]] = fn; } }));
      // The run pickers are <select>s, so the viewer reads `value` off the element rather than a
      // data attribute — the shim carries the value the driver assigned before firing onchange.
      if (sel === "[data-cmp-side]") return [...this._h.matchAll(/data-cmp-side="(base|vs)"/g)].map((m: any) => {
        const el: any = { dataset: { cmpSide: m[1] }, value: "" };
        Object.defineProperty(el, "onchange", { configurable: true, set(fn: () => void) { handlers.cmpSide[m[1]] = (value: string) => { el.value = value; fn(); }; } });
        return el;
      });
      if (sel === "[data-cmp-jump]") return [...this._h.matchAll(/data-cmp-jump="([^"]+)"/g)].map((m: any) => ({ dataset: { cmpJump: m[1] }, set onclick(fn: () => void) { handlers.cmpJump[m[1]] = fn; } }));
      if (sel === "[data-cmp-anchor]") return [...this._h.matchAll(/data-cmp-anchor="([^"]+)"/g)].map((m: any) => cmpAnchorEl(m[1]));
      if (sel === "[data-back]") return [...this._h.matchAll(/data-back/g)].map(() => ({ dataset: {}, set onclick(fn: () => void) { handlers.back = fn; } }));
      // A flat row's attempt history is a native <details>, so the viewer only listens for the
      // toggle — the shim carries the element's own `open` the way the DOM does, because the
      // handler reads it back to decide whether the group was opened or closed.
      if (sel === "[data-retry-toggle]") return [...this._h.matchAll(/data-retry-toggle="(\d+)"( open)?/g)].map((m: any) => {
        const el: any = { dataset: { retryToggle: m[1] }, open: Boolean(m[2]) };
        Object.defineProperty(el, "ontoggle", { configurable: true, set(fn: () => void) { handlers.retryToggle[m[1]] = (open: boolean) => { el.open = open; fn(); }; } });
        return el;
      });
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
      if (sel === '[role="button"][tabindex="0"]') return [...this._h.matchAll(/<div[^>]*data-step="(\d+)"[^>]*role="button" tabindex="0"/g)].map((m: any) => {
        const el = stepEl(m[1]);
        Object.defineProperty(el, "onkeydown", { configurable: true, set(fn: (e: any) => void) { handlers.stepKey.set(m[1], fn); } });
        return el;
      });
      if (sel === ".step.sel" || sel === ".step.sel, .grphdr.sel") return [...stepEls.values()].filter((el) => el.classes.has("sel"));
      if (sel === ".kid.sel") return [...kidEls.values()].filter((el) => el.classes.has("sel"));
      return [];
    },
    querySelector(sel: string) {
      if ((sel === ".timeline-list" || sel === ".timelinescroll") && this._h.includes('class="timelinescroll"')) return timelineList;
      if (sel === "main" && this._h.includes("<main")) return mainScroller;
      if (sel === ".preview .deviceplayer" && this._h.includes('class="deviceplayer')) return devicePlayer;
      if (sel === ".preview .shot" && devicePlayer._h.includes('class="shot')) return shotImg;
      if (sel === ".preview .shotwrap" && devicePlayer._h.includes('class="shotwrap"')) return shotWrap;
      if (sel === "[data-scrub]" && this._h.includes("data-scrub")) return scrubEl;
      if (sel === "[data-scrubhover]" && this._h.includes("data-scrubhover")) return scrubHover;
      if (sel === "[data-scrubhover-range]" && this._h.includes("data-scrubhover-range")) return scrubHoverRange;
      if (sel === "[data-run-loading-progress]" && this._h.includes("data-run-loading-progress")) {
        if (progressText === null) progressText = (this._h.match(/data-run-loading-progress>([^<]*)</) || [])[1] || "";
        return progressNote;
      }
      const step = sel.match(/^\[data-step="(\d+)"\]$/);
      if (step && this._h.includes(`data-step="${step[1]}"`)) return stepEl(step[1]);
      // A step header survives every render, which is why focus can be handed back to it.
      const group = sel.match(/^\[data-group="(\d+)"\]$/);
      if (group && this._h.includes(`data-group="${group[1]}"`)) return { focus: () => { restoredFocus = sel; } };
      // Only present while the row's dispatch list is expanded — a collapsed list has no element
      // to highlight, which is why the pane still advances on its own.
      const kid = sel.match(/^\[data-kidsel="(\d+:\d+)"\]$/);
      if (kid && this._h.includes(`data-kidsel="${kid[1]}"`)) return kidEl(kid[1]);
      const tlStream = sel.match(/^\[data-tlstream="(\d+)"\]$/);
      if (tlStream && this._h.includes(`data-tlstream="${tlStream[1]}"`)) return { focus: () => { restoredFocus = sel; } };
      // The compare view's expandable rows are replaced wholesale by a re-render, so the viewer
      // looks the successor up by the same key to hand focus back to it.
      const cmpRow = sel.match(/^\[data-cmp-(?:event|gap)="[^"]+"\]$/);
      if (cmpRow && this._h.includes(sel.slice(1, -1))) return { focus: () => { restoredFocus = sel; } };
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
  // Page-level navigation sends the reader to the top of the new view.
  (globalThis as Record<string, unknown>).scrollTo = () => {};
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
  const tlvframeNode: any = { style: {}, attrs: {} as Record<string, string>, setAttribute(name: string, value: string) { this.attrs[name] = value; } };
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
  let route = `/report.html${routeQuery}${opts.legacyHash || ""}`;
  const routeWrites: Array<{ method: string; next: string }> = [];
  const pickClicksStopped: string[] = [];
  const pickLabelClicks: Array<(e: any) => void> = [];
  let pickLabelClicksStopped = 0;
  const historyEntries = [`/report.html${routeQuery}${opts.legacyHash || ""}`];
  let historyIndex = 0;
  let pendingHistoryBack: (() => void) | null = null;
  const navigate = (next: string) => {
    route = next;
    const parsed = new URL(next, "https://report.example");
    testLocation.pathname = parsed.pathname;
    testLocation.search = parsed.search;
    testLocation.hash = parsed.hash;
  };
  const firePopstate = () => popstateListeners.forEach((fn) => fn());
  const historyApi = {
    pushState(_state: unknown, _title: string, next: string) {
      routeWrites.push({ method: "push", next });
      historyEntries.splice(historyIndex + 1, historyEntries.length, next);
      historyIndex = historyEntries.length - 1;
      navigate(next);
    },
    replaceState(_state: unknown, _title: string, next: string) {
      routeWrites.push({ method: "replace", next });
      historyEntries[historyIndex] = next;
      navigate(next);
    },
    back() {
      if (historyIndex <= 0 || pendingHistoryBack) return;
      const nextIndex = historyIndex - 1;
      const next = historyEntries[nextIndex];
      routeWrites.push({ method: "back", next });
      const commit = () => {
        historyIndex = nextIndex;
        navigate(next);
        firePopstate();
      };
      if (opts.deferHistoryBack) pendingHistoryBack = commit;
      else commit();
    },
    forward() {
      if (historyIndex + 1 >= historyEntries.length || pendingHistoryBack) return;
      historyIndex += 1;
      const next = historyEntries[historyIndex];
      routeWrites.push({ method: "forward", next });
      navigate(next);
      firePopstate();
    },
  };
  (globalThis as Record<string, unknown>).history = historyApi;
  const activeElement = opts.focusedStep != null ? {
    id: "", dataset: { step: String(opts.focusedStep) }, matches: (sel: string) => sel === "[data-step]" || sel === "[data-step], [data-group]",
  } : opts.focusedGroup != null ? {
    id: "", dataset: { group: String(opts.focusedGroup) }, matches: (sel: string) => sel === "[data-group]" || sel === "[data-step], [data-group]",
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
      scrollTop: 0,
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
        const attributeSelectors = [...sel.matchAll(/\[([a-z-]+)(?:="([^"]*)")?\]/g)];
        if (attributeSelectors.length && attributeSelectors.map((match) => match[0]).join("") === sel) {
          return attributeSelectors.every((attr) => attrs[attr[1]] != null && (attr[2] == null || attrs[attr[1]] === attr[2]));
        }
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
        node.children = [];
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
        [...html.matchAll(/<div class="(inspshotwrap)" (data-insphit)/g)].forEach((m) => {
          const wrap = push({ "data-insphit": "" }, m[1]);
          const image: any = { _src: "", complete: false, naturalWidth: 0, set src(value: string) { this._src = value; }, get src() { return this._src; }, getBoundingClientRect: () => ({ left: 0, top: 0, width: 100, height: 200, right: 100, bottom: 200 }) };
          wrap.querySelector = (sel: string) => (sel === "img" ? image : null);
        });
        [...html.matchAll(/<span class="([^"]*)" (data-insphovlabel)/g)].forEach((m) => push({ "data-insphovlabel": "" }, m[1]));
        // Native media players, so a test can see both what the markup asked for (the autoplay
        // attribute) and what the viewer did to the element afterwards (play()).
        [...html.matchAll(/<(audio|video)\b([^>]*)>/g)].forEach((m) => {
          const media = push({}, m[1]);
          media._media = true;
          media._focusable = true;
          media.autoplayAttr = /\bautoplay\b/.test(m[2]);
          media.played = 0;
          media.play = () => { media.played++; return Promise.resolve(); };
          media._listeners = {};
          media.addEventListener = (name: string, fn: () => void) => { media._listeners[name] = fn; };
          media.fire = (name: string) => media._listeners[name] && media._listeners[name]();
        });
        // The blocked-playback note the viewer reveals when the player's load errors.
        [...html.matchAll(/<div class="([^"]*\battachblockednote\b[^"]*)"( hidden)?/g)].forEach((m) => {
          const note = push({}, m[1]);
          note.hidden = !!m[2];
        });
        [...html.matchAll(/<(?:aside|main) class="([^"]*\b(?:txcontext|txconversation)\b[^"]*)"/g)].forEach((m) =>
          push({}, m[1]),
        );
        [...html.matchAll(/<summary\b([^>]*)>/g)].forEach((m) => {
          const className = `${m[1].match(/class="([^"]*)"/)?.[1] || ""} txsummary-test`.trim();
          const summary = push({}, className);
          summary._focusable = true;
        });
        [...html.matchAll(/<button\b([^>]*)>/g)].forEach((m) => {
          const attributes = m[1];
          const className = attributes.match(/class="([^"]*)"/)?.[1] || "";
          const dataAttrs: Record<string, string> = {};
          for (const attr of attributes.matchAll(/\b(data-[a-z-]+)="([^"]*)"/g)) dataAttrs[attr[1]] = attr[2];
          const button = push(dataAttrs, className);
          button._focusable = true;
          button.disabled = /(?:^|\s)disabled(?:\s|$)/.test(attributes);
        });
      },
      get innerHTML() { return node._h || ""; },
      querySelectorAll(sel: string) {
        // The modal focus-trap query, whatever else its selector list grows to include.
        if (sel.startsWith("button, [href], summary")) return node._els.filter((el: any) => el._focusable);
        return node._els.filter((el: any) => el.matches(sel));
      },
      querySelector(sel: string) {
        if (sel === "audio, video") return node._els.find((el: any) => el._media) || null;
        return node._els.find((el: any) => el.matches(sel)) || null;
      },
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
  // A document-element clone for the export path (chunked documents only): the export rewrites the
  // clone's payload nodes and serializes it, so the clone carries the chunk text and its outerHTML
  // reflects whatever was rewritten. The live document is untouched, as in a real clone.
  const cloneDocumentElement = () => {
    if (!opts.chunks) throw new Error("cloneDocumentElement models the chunked layout only");
    const nodes = new Map<string, { id: string; textContent: string; remove(): void }>();
    const node = (id: string, textContent: string) => nodes.set(id, { id, textContent, remove() { nodes.delete(id); } });
    node("tb-index", opts.chunks.index);
    Object.entries(opts.chunks.sessions).forEach(([i, text]) => node(`tb-session-${i}`, text));
    Object.entries(opts.chunks.sprites).forEach(([i, text]) => node(`tb-sprites-${i}`, text));
    let titleText = "";
    return {
      querySelector(sel: string) {
        if (sel === "#app") return { set innerHTML(_v: string) {} };
        if (sel === "title") return { set textContent(v: string) { titleText = v; }, get textContent() { return titleText; } };
        return nodes.get(sel.replace(/^#/, "")) || null;
      },
      querySelectorAll(sel: string) {
        const prefixes = sel.split(",").map((one) => (one.trim().match(/^\[id\^="([^"]+)"\]$/) || [])[1]).filter(Boolean) as string[];
        return [...nodes.values()].filter((node) => prefixes.some((prefix) => node.id.startsWith(prefix)));
      },
      get outerHTML() {
        return `<html><title>${titleText}</title>`
          + [...nodes.values()].map((node) => `<script type="application/json" id="${node.id}">${node.textContent}</script>`).join("")
          + "</html>";
      },
    };
  };
  // Hoisted so a test can read back the capture-framing marker autoplay stamps on it.
  const documentElement = { dataset: {} as Record<string, string>, hasAttribute: (name: string) => name === "data-tb-shell" && !!opts.shellDocument, cloneNode: (_deep: boolean) => cloneDocumentElement() };
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
      : id === "tlvframe" && devicePlayer._h.includes('id="tlvframe"') ? tlvframeNode
      : id === "tlplay" ? { click: () => handlers.timelinePlay && handlers.timelinePlay(), set onclick(fn: () => void) { handlers.timelinePlay = fn; } }
      : id === "shot" && devicePlayer._h.includes('id="shot"') ? shotImg
      : id === "lightboxmode" && app._h.includes('id="lightboxmode"') ? { set onclick(fn: () => void) { handlers.gridMode = fn; } }
      : id === "prev" ? prevBtn
      : id === "next" ? nextBtn
      : id === "copylocalprompt" && app._h.includes('id="copylocalprompt"') ? { textContent: "", set onclick(fn: () => void) { handlers.copyLocalPrompt = fn; } }
      : (id === "copylink" || id === "copylinkrun") && app._h.includes(`id="${id}"`) ? copyBtn
      : id === "exportlogs" && app._h.includes('id="exportlogs"') ? { set onclick(fn: () => void) { handlers.exportLogs = fn; } }
      : id === "exportrun" && app._h.includes('id="exportrun"') ? { set onclick(fn: () => void) { handlers.exportRun = fn; } }
      : null),
    // The base a live daemon report is served from: the attachment link branch resolves the
    // root-relative `/static/...` link mode produces against it, and refuses anything that lands
    // on another origin.
    baseURI: opts.baseURI ?? "https://report.example/report.html",
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
  (globalThis as Record<string, unknown>).getComputedStyle = (el: any) => ({
    overflowY: el === mainScroller || (el === timelineList && !opts.stackedTimeline) ? "auto" : "visible",
    getPropertyValue: (name: string) => el?.style?.[name] || "",
  });
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
  if (opts.step != null) handlers.step.get(String(opts.step))?.();
  if (opts.clickGroup != null && handlers.group[String(opts.clickGroup)]) handlers.group[String(opts.clickGroup)]();
  if (opts.toggleKids != null && handlers.kids[String(opts.toggleKids)]) handlers.kids[String(opts.toggleKids)]({ preventDefault() {}, stopPropagation() {} });
  if (opts.clickKid != null && handlers.kidsel[opts.clickKid]) handlers.kidsel[opts.clickKid]({ preventDefault() {}, stopPropagation() {} });
  if (opts.tlStreamBeforeTab != null && handlers.tlStream[String(opts.tlStreamBeforeTab)]) handlers.tlStream[String(opts.tlStreamBeforeTab)]();
  if (opts.tab && handlers.tab[opts.tab]) handlers.tab[opts.tab]();
  if (opts.openTx != null && handlers.txOpen[String(opts.openTx)]) handlers.txOpen[String(opts.openTx)]();
  if (opts.txEscape && zoomRoot && zoomRoot.onkeydown) zoomRoot.onkeydown({ key: "Escape", preventDefault() {}, stopPropagation() {} });
  if (opts.llmEnter != null && handlers.llmKey[String(opts.llmEnter)]) handlers.llmKey[String(opts.llmEnter)]({ key: "Enter", preventDefault() {} });
  if (opts.llmClick != null && handlers.llmClick[String(opts.llmClick)]) handlers.llmClick[String(opts.llmClick)]();
  if (opts.lightboxAll && handlers.gridMode) handlers.gridMode();
  if (opts.galZoom) for (const delta of opts.galZoom) { const fn = handlers.galZoom[String(delta)]; if (fn) fn(); }
  // Trail-view navigation, in the order a reader performs it: into the trail, out to one device's
  // own timeline, then back the way they came.
  if (opts.gotoTrail) {
    // A string picks one trail's entry point by key; `true` takes whichever is offered first,
    // which is all a single-trail document has.
    const gotoKey = typeof opts.gotoTrail === "string" ? opts.gotoTrail : Object.keys(handlers.gotoTrail)[0];
    if (gotoKey != null) handlers.gotoTrail[gotoKey]?.();
  }
  // Expanding a retry group's attempt history, the way a reader does before ticking one of them.
  (opts.openRetries || []).forEach((run) => handlers.retryToggle[String(run)]?.(true));
  // Ticking runs on the index, then acting on the selection — the order a reader does it in. Each
  // tick re-renders, so the handler is re-read from the fresh markup between ticks.
  (opts.pick || []).forEach((run) => {
    // A real tick fires click then change. The click is recorded so a test can prove it was kept
    // from the row/summary around it rather than opening or expanding the run.
    handlers.pickClick[String(run)]?.({ stopPropagation() { pickClicksStopped.push(String(run)); } });
    handlers.pick[String(run)]?.({ stopPropagation() {} });
  });
  if (opts.pick) pickLabelClicks.forEach((fn) => fn({ stopPropagation() { pickLabelClicksStopped++; } }));
  if (opts.pickClear && handlers.pickClear) handlers.pickClear();
  if (opts.pickOpen && handlers.pickOpen) handlers.pickOpen();
  if (opts.pickDiff && handlers.pickDiff) handlers.pickDiff();
  if (opts.cmpGap != null && handlers.cmpGap[String(opts.cmpGap)]) handlers.cmpGap[String(opts.cmpGap)]();
  if (opts.cmpLane != null && handlers.cmpLane[opts.cmpLane]) handlers.cmpLane[opts.cmpLane]();
  if (opts.cmpStream != null && handlers.cmpStream[opts.cmpStream]) handlers.cmpStream[opts.cmpStream]();
  if (opts.cmpEvent != null && handlers.cmpEvent[opts.cmpEvent]) handlers.cmpEvent[opts.cmpEvent]();
  if (opts.cmpSide && handlers.cmpSide[opts.cmpSide.side]) handlers.cmpSide[opts.cmpSide.side](String(opts.cmpSide.value));
  if (opts.cmpJump != null) (Array.isArray(opts.cmpJump) ? opts.cmpJump : [opts.cmpJump]).forEach((k) => handlers.cmpJump[k]?.());
  (opts.toggleLanes || []).forEach((lane) => handlers.trailLane[String(lane)]?.());
  if (opts.trailOpen && handlers.trailOpen[opts.trailOpen]) handlers.trailOpen[opts.trailOpen]();
  if (opts.back && handlers.back) handlers.back();
  if (opts.zoomShot && handlers.shot[opts.zoomShot]) handlers.shot[opts.zoomShot]();
  // The report exposes one contextual inspector action for the selected timeline step. Tests that
  // ask to open a step directly first reproduce that selection, then activate the live action.
  if (opts.inspect != null && opts.step == null) handlers.step.get(String(opts.inspect))?.();
  if (opts.inspect != null && handlers.inspect[String(opts.inspect)]) handlers.inspect[String(opts.inspect)]();
  if (opts.inspectEscape && zoomRoot && zoomRoot.onkeydown) zoomRoot.onkeydown({ key: "Escape", preventDefault() {}, stopPropagation() {} });
  // Browser Back/Forward: point the address at another route and fire the viewer's popstate
  // listener, exactly as the browser would after a history pop. This comes after opening either
  // pushed destination so the same harness can prove a real open -> Back lifecycle.
  if (opts.popstate != null) {
    navigate(`/report.html${opts.popstate}`);
    popstateListeners.forEach((fn) => fn());
  }
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
  if (opts.exportRun && handlers.exportRun) handlers.exportRun();
  if (opts.shotLayoutShift && handlers.shotLoad) { shotLoaded = true; handlers.shotLoad(); }
  if (opts.spaceOnStep != null && handlers.stepKey.has(String(opts.spaceOnStep))) {
    const event = { key: " ", defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
    handlers.stepKey.get(String(opts.spaceOnStep))!(event);
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
        paneMark: () => shotWrap.marks.join(""),
        selectedSteps: () => [...stepEls.entries()].filter(([, el]) => el.classes.has("sel")).map(([id]) => id),
        selectedKids: () => [...kidEls.entries()].filter(([, el]) => el.classes.has("sel")).map(([id]) => id),
        hoverStep: (step) => stepEl(String(step)).onpointerenter?.({ pointerType: "mouse" }),
        leaveStep: (step) => stepEl(String(step)).onpointerleave?.({ pointerType: "mouse" }),
        hoverGroup: (step) => handlers.groupEnter[String(step)]?.({ pointerType: "mouse" }),
        leaveGroup: (step) => handlers.groupLeave[String(step)]?.({ pointerType: "mouse" }),
        hoverKid: (id) => kidEl(id).onpointerenter?.({ pointerType: "mouse" }),
        leaveKid: (id) => kidEl(id).onpointerleave?.({ pointerType: "mouse", relatedTarget: null }),
        hoverTimelineEvent: (key) => tlEventEl(key).onpointerenter?.({ pointerType: "mouse" }),
        leaveTimelineEvent: (key) => tlEventEl(key).onpointerleave?.({ pointerType: "mouse" }),
        scrubAttr: (name: string) => scrubEl.attrs[name],
        shotImg,
        prevBtn,
        nextBtn,
        clickShot: () => shotImg.onclick && shotImg.onclick(),
        keyShot: (key) => {
          const event = { key, defaultPrevented: false, stopped: false, preventDefault() { this.defaultPrevented = true; }, stopPropagation() { this.stopped = true; } };
          shotImg.onkeydown?.(event);
          return event;
        },
        hoverScrub: (fraction, marker) => scrubEl.onpointermove?.({ clientX: fraction * 100, target: { closest: () => marker ? { dataset: { scrubStep: marker.step, scrubKind: marker.kind }, style: { "--tick-color": marker.color || "" } } : null } }),
        leaveScrub: () => scrubEl.onpointerleave?.(),
        scrubHoverState: () => ({ tooltipVisible: scrubHover.classes.has("visible"), rangeVisible: scrubHoverRange.classes.has("visible"), step: scrubHoverStep.textContent, kind: scrubHoverKind.textContent, ariaHidden: scrubHover.attrs["aria-hidden"] }),
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
  return { html: app._h, htmlBeforeBoot, liveHtml: () => app._h as string, readHtml: () => app._h as string, timelineScrollTop: timelineList.scrollTop, mainScrollTop: mainScroller.scrollTop, restoredFocus, route, readRoute: () => route, routeWrites: () => routeWrites.slice(), historyBack: () => historyApi.back(), historyForward: () => historyApi.forward(), flushHistoryBack: () => { const pending = pendingHistoryBack; pendingHistoryBack = null; if (pending) pending(); }, escapeOverlay: () => { if (zoomRoot && zoomRoot.onkeydown) zoomRoot.onkeydown({ key: "Escape", preventDefault() {}, stopPropagation() {} }); }, liveZoomRoot: () => zoomRoot, zoomSrc, zoomRoot, copiedText, copyBtnText: () => copyBtn.textContent as string, timelineMenuOpen: timelineMenu.open, spriteMeasures, tlvframeStyle: tlvframeNode.style, shotImg, releaseChunks: () => { heldChunks.clear(); heldSpriteChunks.clear(); streamingChunks.clear(); }, partialChunkReads: () => partialChunkReads, loadingProgressWrites: () => progressWrites, settleDocument: () => { documentLoading = false; }, documentKeyListeners, autoplayMarker: () => documentElement.dataset.tbAutoplay, embeddedMarker: () => documentElement.dataset.tbEmbedded, llmScrolledTo, cmpScrolledTo: () => cmpScrolledTo, llmRow: (i: number) => llmRowEl(String(i)), readRestoredFocus: () => restoredFocus, pageClass: () => app.className || "", pageClassWrites: () => pageClassWrites.slice(), readActiveElement: () => (globalThis as any).document.activeElement, live: () => (globalThis as Record<string, any>).__TB_REPORT_LIVE__, openSession: (i: number) => handlers.session[String(i)]?.(), clickTab: (id: string) => handlers.tab[id]?.(), clickGotoTrail: (key: string) => handlers.gotoTrail[key]?.(), readTimelineScrollTop: () => timelineList.scrollTop, readMainScrollTop: () => mainScroller.scrollTop, expandTimelineEvent, timelineEvent: (key: string) => tlEventEls.get(key), openAttachment: (key: string) => handlers.attach[key]?.(), pickClicksStopped: () => pickClicksStopped.slice(), pickLabelClicksStopped: () => pickLabelClicksStopped, pickLabels: () => pickLabelClicks.length, firePopstate: (next?: string) => { if (next != null) navigate(`/report.html${next}`); firePopstate(); } };
}

function renderViewer(payload: unknown, opts: ViewerOptions = {}): string {
  return renderViewerState(payload, opts).html;
}

// "The cell with this outcome opens that run." The compare checkbox sits between the cell and its
// open control, so the two are no longer adjacent in the markup; the gap may not cross into a
// neighbouring cell, which is what keeps this from matching some other column's run.
function cellOpens(out: string, cell: string, session: number): boolean {
  return new RegExp(`<div class="${cell}">(?:(?!idxcell)[\\s\\S])*?class="idxcellopen"[^>]*data-session="${session}"`).test(out);
}

// "The cell for this run carries its own compare checkbox." Same gap rule as cellOpens: it may not
// cross into a neighbouring cell, so a checkbox belonging to another column can't satisfy it.
function cellPicks(out: string, cell: string, session: number): boolean {
  return new RegExp(`<div class="${cell}">(?:(?!idxcell)[\\s\\S])*?data-pick="${session}"`).test(out);
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
    traceId: "t1",
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

  test("keeps turn trace ids off timeline rows while compact LLM calls retain their stable id", () => {
    const extractedTrace = core.extractTrace(sampleLogs);
    const extractedLlm = core.extractLlmLogs(sampleLogs);
    expect(extractedTrace.find((row) => row.label === "tapOnElement")?.traceId).toBe("t1");
    expect(extractedTrace.find((row) => row.llm === 0)?.traceId).toBe("t1");
    expect(extractedLlm[0].traceId).toBe("t1");

    const trace = core.slimTraceForShare(extractedTrace);
    const llm = core.slimLlmForShare(extractedLlm);
    expect(trace.every((row) => !("traceId" in row))).toBe(true);
    expect(llm.map((row) => row.traceId)).toEqual(["t1"]);
  });

  test("does not fold a device action from another traced turn into the previous tool", () => {
    const trace = core.extractTrace([
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElement", traceId: "turn-a",
        trailblazeTool: { raw: { text: "Login" } }, successful: true,
        timestamp: "2024-01-01T00:00:00Z",
      },
      {
        class: `${T}.MaestroDriverLog`, traceId: "turn-b",
        action: { class: "xyz.AgentDriverAction.TapPoint", x: 4, y: 8 },
        deviceWidth: 10, deviceHeight: 20, timestamp: "2024-01-01T00:00:01Z",
      },
    ]);
    expect(trace.map((row) => row.traceId)).toEqual(["turn-a", "turn-b"]);
    expect(trace.map((row) => row.label)).toEqual(["tapOnElement", "TapPoint"]);
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
      {
        class: `${T}.TrailblazeLlmRequestLog`, traceId: "obj8", llmMessages: [],
        llmResponse: [{ parts: [
          { class: "Tool.Call", tool: "assertVisibleBySelector", args: '{"reasoning":"Check the end time"}' },
          { class: "Tool.Call", tool: "tapOnElementBySelector", args: '{"reasoning":"Open the end time"}' },
          { class: "Tool.Call", tool: "swipe", args: '{"reasoning":"Move to the desired minute"}' },
          { class: "Tool.Call", tool: "mobile_maestro", args: '{"reasoning":"Confirm the choice"}' },
        ] }],
        llmRequestUsageAndCost: { inputTokens: 10, outputTokens: 5, totalCost: 0.001 },
        durationMs: 100, timestamp: "2024-01-01T00:00:00.500Z",
      },
      tool("assertVisibleBySelector", { selector: { text: "End time" } }, 1),
      { class: `${T}.MaestroDriverLog`, traceId: "obj8", action: { class: "xyz.AgentDriverAction.TapPoint", x: 1, y: 2 }, deviceWidth: 10, deviceHeight: 20, timestamp: "2024-01-01T00:00:02Z" },
      tool("tapOnElementBySelector", { selector: { text: "End time" } }, 3),
      tool("swipe", { swipeOnElementText: "00 minutes" }, 4),
      tool("mobile_maestro", { commands: "tapOn 50%,91%" }, 5),
    ];
    const trace = core.extractTrace(logs);
    // Still one folded row per traceId — this fix adds detail, it does not split the row.
    const row = trace.find((r) => r.label === "assertVisibleBySelector");
    expect(trace.filter((r) => !r.objective && r.llm == null).length).toBe(1);
    expect(row.note).toBe("Check the end time");
    // The three calls that actually did the work are now followable.
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.label))
      .toEqual(["tapOnElementBySelector", "swipe", "mobile_maestro"]);
    expect((row.children as Array<Record<string, unknown>>).map((c) => c.note))
      .toEqual(["Open the end time", "Move to the desired minute", "Confirm the choice"]);
    // Device actions stay folded: the row already names the action, so they are not children.
    expect((row.children as unknown[]).length).toBe(3);
    // And they survive the share slimming — the standalone report renders from the slimmed shape.
    const slim = (core as any).slimTraceForShare(trace);
    expect(slim.find((r: any) => r.label === "assertVisibleBySelector").children.map((c: any) => c.label))
      .toEqual(["tapOnElementBySelector", "swipe", "mobile_maestro"]);
    expect(slim.find((r: any) => r.label === "assertVisibleBySelector").children.map((c: any) => c.note))
      .toEqual(["Open the end time", "Move to the desired minute", "Confirm the choice"]);
  });

  test("keeps repeated tool reasoning aligned when an earlier response has no note", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap twice" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeLlmRequestLog`, traceId: "repeat", llmMessages: [],
        llmResponse: [{ parts: [
          { class: "Tool.Call", tool: "tapOnElementBySelector", args: '{"text":"First"}' },
          { class: "Tool.Call", tool: "tapOnElementBySelector", args: '{"reasoning":"Tap the second row","text":"Second"}' },
        ] }],
        timestamp: "2024-01-01T00:00:00.500Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "repeat", successful: true,
        trailblazeTool: { raw: { text: "First" } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "repeat", successful: true,
        trailblazeTool: { raw: { text: "Second" } }, timestamp: "2024-01-01T00:00:02Z",
      },
    ];
    const row = core.extractTrace(logs).find((entry) => entry.label === "tapOnElementBySelector");
    expect(row.note).toBeUndefined();
    expect((row.children as Array<Record<string, unknown>>)[0].note).toBe("Tap the second row");
    // Correlation metadata belongs to the extracted timeline, not the shared runtime log objects
    // that also back the Raw logs tab.
    expect((logs as Array<Record<string, unknown>>).some((log) => "_timelineReasoning" in log)).toBe(false);
  });

  test("does not carry unmatched reasoning into a later turn that reuses the trace id", () => {
    const response = (reasoning: string, timestamp: string) => ({
      class: `${T}.TrailblazeLlmRequestLog`, traceId: "reused", llmMessages: [],
      llmResponse: [{ parts: [
        { class: "Tool.Call", tool: "tapOnElementBySelector", args: JSON.stringify({ reasoning }) },
      ] }],
      timestamp,
    });
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Choose the row" }, timestamp: "2024-01-01T00:00:00Z" },
      response("Stale reasoning from an abandoned turn", "2024-01-01T00:00:00.500Z"),
      response("Tap the row found by the current turn", "2024-01-01T00:00:01Z"),
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "reused", successful: true,
        trailblazeTool: { raw: { text: "Current row" } }, timestamp: "2024-01-01T00:00:02Z",
      },
    ];

    const row = core.extractTrace(logs).find((entry) => entry.label === "tapOnElementBySelector");
    expect(row.note).toBe("Tap the row found by the current turn");
    expect(row.note).not.toContain("Stale reasoning");
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
    expect(trace[0].note).toBeUndefined();
    expect(trace[0].count).toBe(3);
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

  test("declaration matching keys on raw args, so a truncation-collided summary can't absorb a dispatch", () => {
    // Display summaries truncate at 44 chars, so two different selectors can summarize identically.
    // Keying declaration-matching off the summary would treat the never-logged second dispatch as
    // "already ran" and drop it; keying off the raw args keeps every interaction visible.
    const prefix = "A".repeat(43);
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap two long-labeled rows" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objC", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: "List" } } }, timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objC", successful: true,
        durationMs: 10, trailblazeTool: { raw: { selector: { text: `${prefix} first` } } }, timestamp: "2024-01-01T00:00:02Z",
      },
      {
        class: `${T}.DelegatingTrailblazeToolLog`, toolName: "tap", traceId: "objC",
        trailblazeTool: { toolName: "tap", raw: { ref: "z4" } },
        executableTools: [{ toolName: "tapOnElementBySelector", raw: { selector: { text: `${prefix} second` } } }],
        timestamp: "2024-01-01T00:00:03Z",
      },
    ];
    const row = core.extractTrace(logs).find((r) => r.label === "assertVisibleBySelector");
    const kids = row.children as Array<Record<string, unknown>>;
    // Same lossy summary on both — the very collision that must not dedupe them.
    expect(new Set(kids.map((c) => c.tool)).size).toBe(1);
    expect(kids.map((c) => c.label)).toEqual(["tapOnElementBySelector", "tapOnElementBySelector"]);
    // The full args stay distinct, so the reader can still tell the dispatches apart.
    expect(kids[0].args).toContain(`${prefix} first`);
    expect(kids[1].args).toContain(`${prefix} second`);
  });

  test("iosAxe and Compose selectors summarize by their identity field, not declaration order", () => {
    // An iosAxe selector's first declared field is roleRegex, so the declaration-order fallback
    // summarized every AX tap as `roleRegex: AXButton` — label-less. The identity fields must win.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap native controls" }, timestamp: "2024-01-01T00:00:00Z" },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objX", successful: true,
        durationMs: 10, trailblazeTool: { raw: { nodeSelector: { iosAxe: { roleRegex: "AXButton", labelRegex: "Pay" } } } },
        timestamp: "2024-01-01T00:00:01Z",
      },
      {
        class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objY", successful: true,
        durationMs: 10, trailblazeTool: { raw: { nodeSelector: { compose: { testTag: "pay_button", role: "Button" } } } },
        timestamp: "2024-01-01T00:00:02Z",
      },
    ];
    const rows = core.extractTrace(logs).filter((r) => r.label === "tapOnElementBySelector");
    expect(rows.map((r) => r.tool)).toEqual(["labelRegex: Pay", "testTag: pay_button"]);
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
      maestro(2, { resultSummary: "tapped Next" }), maestro(3, { resultSummary: "tapped Next" }), maestro(4, { resultSummary: "tapped Next" }),
      {
        class: `${T}.TrailblazeToolLog`, toolName: "exec", traceId: "th1", successful: true,
        durationMs: 40, resultSummary: "broadcast delivered", trailblazeTool: { raw: { argv: ["adb", "shell", "am", "broadcast"], timeoutSeconds: 30 } }, timestamp: "2024-01-01T00:00:05Z",
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
    expect(kids.map((c) => c.result)).toEqual(["tapped Next", "broadcast delivered", null]);
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
    expect(slimRow.children.map((c: any) => c.result)).toEqual(["tapped Next", "broadcast delivered", undefined]);
    expect(slimRow.params).toEqual(row.params);
  });

  test("the fold hides no interaction: children carry their own args, frame, and tap mark", () => {
    // WASM-report parity (the Session-details gaps): a batched step folds onto one row that keeps
    // only the FIRST frame — each dispatched call must still expose its full call as trail-file
    // YAML, its own captured frame (its log's, else the driver log in its span), and the tap mark
    // on that frame, so every interaction inside the step stays followable.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Choose Gift Card as payment method" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objK", successful: true, durationMs: 10, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, screenshotFile: "first.png", timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objK", successful: true, durationMs: 20, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "objK", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: "2024-01-01T00:00:03Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "inputText", traceId: "objK", successful: true, durationMs: 30, trailblazeTool: { raw: { text: "42" } }, screenshotFile: "input.png", timestamp: "2024-01-01T00:00:04Z" },
    ];
    const trace = core.extractTrace(logs);
    const row = trace.find((r: any) => r.label === "assertVisibleBySelector");
    // The row itself: the batch's first frame, the nodeSelector summarized to its identity
    // property (not a bare tool name), and its full call as trail-file YAML.
    expect(row.screenshotFile).toBe("first.png");
    expect(row.tool).toBe("textRegex: ^Gift Card$");
    expect(row.args).toBe("- assertVisibleBySelector:\n    nodeSelector:\n      androidAccessibility:\n        textRegex: ^Gift Card$");
    const kids = row.children as Array<Record<string, unknown>>;
    expect(kids.map((c) => c.label)).toEqual(["tapOnElementBySelector", "inputText"]);
    // The tap dispatch's frame + mark come from the driver log in its span; inputText's frame is
    // its own log's.
    expect(kids[0].screenshotFile).toBe("tap.png");
    expect(kids[0].mark).toEqual({ kind: "tap", x: 100, y: 200, dw: 1080, dh: 2400 });
    expect(kids[0].args).toBe("- tapOnElementBySelector:\n    nodeSelector:\n      androidAccessibility:\n        textRegex: ^Gift Card$");
    expect(kids[1].screenshotFile).toBe("input.png");
    expect(kids[1].mark).toBeNull();
    expect(kids[1].args).toBe('- inputText:\n    text: "42"');
    // And all of it survives the share slimming the standalone report renders from.
    const slim = core.slimTraceForShare(trace);
    const slimRow = slim.find((r: any) => r.label === "assertVisibleBySelector");
    expect(slimRow.args).toBe(row.args);
    expect(slimRow.children.map((c: any) => [c.screenshotFile, c.args, c.mark])).toEqual([
      ["tap.png", kids[0].args, kids[0].mark],
      ["input.png", kids[1].args, undefined],
    ]);
  });

  test("a folded action's frame and mark carry the instant IT ran, not the tool's start", () => {
    // A tool log's timestamp is timeBeforeExecution. When the tool spent time resolving a selector
    // before it acted, the capture and the tap it contributes belong seconds later — and Replay
    // draws both over a recording synchronized to the run clock, where early is simply wrong.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Pay with a gift card" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objK", successful: true, durationMs: 4000, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "objK", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: "2024-01-01T00:00:05Z" },
    ];
    const row = core.extractTrace(logs).find((r: any) => r.label === "tapOnElementBySelector");
    // The row still starts when the tool was invoked — that is what its duration is measured from.
    expect(row.ts).toBe(Date.parse("2024-01-01T00:00:01Z"));
    // But the cues the driver log supplied are stamped with the driver log's own instant.
    expect(row.screenshotFile).toBe("tap.png");
    expect(row.shotTs).toBe(Date.parse("2024-01-01T00:00:05Z"));
    expect(row.mark).toEqual({ kind: "tap", x: 100, y: 200, dw: 1080, dh: 2400 });
    expect(row.markTs).toBe(Date.parse("2024-01-01T00:00:05Z"));
    // And they survive the share slimming the standalone report renders from.
    const slimRow = core.slimTraceForShare(core.extractTrace(logs)).find((r: any) => r.label === "tapOnElementBySelector");
    expect(slimRow.shotTs).toBe(row.shotTs);
    expect(slimRow.markTs).toBe(row.markTs);
  });

  test("a driver instant outside the tool's own span is a different clock, so the cues stay put", () => {
    // A MaestroDriverLog is written wherever the driver ran, so its timestamp is on the host clock
    // for a host-driven session and on the DEVICE clock for an on-device one — with no field saying
    // which, and the two routinely differing by seconds. Believing a device instant here is not
    // merely imprecise: Replay's axis takes the maximum of every step end AND every capture offset,
    // so one capture stamped by a clock ten minutes ahead stretches the whole replay and squeezes
    // the run into its first fraction.
    const skewed = (driverTimestamp: string) => [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Pay with a gift card" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objK", successful: true, durationMs: 4000, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "objK", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: driverTimestamp },
    ];
    // The tool ran [00:00:01, 00:00:05]. A device clock ten minutes ahead lands far past that.
    const ahead = core.extractTrace(skewed("2024-01-01T00:10:05Z")).find((r: any) => r.label === "tapOnElementBySelector");
    expect(ahead.ts).toBe(Date.parse("2024-01-01T00:00:01Z"));
    expect(ahead.screenshotFile).toBe("tap.png");
    // No separate cue instant at all, so every consumer falls back to the row's own — the same
    // placement every payload had before cue timing existed.
    expect(ahead.shotTs).toBeNull();
    expect(ahead.markTs).toBeNull();
    // A clock BEHIND the host is just as wrong: those cues would land before the run started.
    const behind = core.extractTrace(skewed("2023-12-31T23:59:58Z")).find((r: any) => r.label === "tapOnElementBySelector");
    expect(behind.shotTs).toBeNull();
    expect(behind.markTs).toBeNull();
    // The boundaries themselves are consistent with the host clock and stay believed.
    const atEnd = core.extractTrace(skewed("2024-01-01T00:00:05Z")).find((r: any) => r.label === "tapOnElementBySelector");
    expect(atEnd.shotTs).toBe(Date.parse("2024-01-01T00:00:05Z"));
  });

  test("a batched turn judges its driver instants against the whole batch, not its first tool", () => {
    // A turn's tools fold into one row that keeps the FIRST tool's label and duration. A cue from a
    // later tool in that batch is on the same clock and honest, so the span it gets judged against
    // has to cover the batch: judging it against a 10ms first tool would discard the placement of
    // every dispatch but the first.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Pay with a gift card" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "assertVisible", traceId: "objK", successful: true, durationMs: 10, trailblazeTool: { raw: { text: "Gift Card" } }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objK", successful: true, durationMs: 4000, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "objK", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: "2024-01-01T00:00:05Z" },
    ];
    const row = core.extractTrace(logs).find((r: any) => r.label === "assertVisible");
    expect(row.shotTs).toBe(Date.parse("2024-01-01T00:00:05Z"));
    expect(row.markTs).toBe(Date.parse("2024-01-01T00:00:05Z"));
    // Past the batch's own end is still a different clock.
    const past = [...logs.slice(0, 3), { ...logs[3], timestamp: "2024-01-01T00:00:07Z" }];
    expect(core.extractTrace(past).find((r: any) => r.label === "assertVisible").shotTs).toBeNull();

    // The batch's span is the union, so a short tool arriving after a long one cannot shrink it: the
    // cue below sits inside the SLOW tool's span and outside the last one's.
    const slowFirst = [
      logs[0],
      { ...logs[1], durationMs: 4000 },
      { ...logs[2], toolName: "assertVisible", durationMs: 10 },
      { ...logs[3], timestamp: "2024-01-01T00:00:04Z" },
    ];
    expect(core.extractTrace(slowFirst).find((r: any) => r.ts === Date.parse("2024-01-01T00:00:01Z")).shotTs)
      .toBe(Date.parse("2024-01-01T00:00:04Z"));
  });

  test("a tool row with no measured duration has no span to judge a driver instant against", () => {
    // Nothing to test the clocks against, so the cues keep the row's instant rather than trusting a
    // timestamp that may be from anywhere.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Pay with a gift card" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objK", successful: true, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "objK", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: "2024-01-01T00:00:05Z" },
    ];
    const row = core.extractTrace(logs).find((r: any) => r.label === "tapOnElementBySelector");
    expect(row.shotTs).toBeNull();
    expect(row.markTs).toBeNull();
  });

  test("a row whose cues start when it does carries no separate timestamps", () => {
    // The common case: nothing to say, so the payload says nothing. Consumers fall back to `ts`.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Pay with a gift card" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objK", successful: true, durationMs: 20, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "objK", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: "2024-01-01T00:00:01Z" },
    ];
    const slimRow = core.slimTraceForShare(core.extractTrace(logs)).find((r: any) => r.label === "tapOnElementBySelector");
    expect(slimRow.shotTs).toBeUndefined();
    expect(slimRow.markTs).toBeUndefined();
  });

  test("a device-action row carries its untruncated fields as args", () => {
    // The `tool` summary crops an assert condition at 40 chars; the expanded args keep the whole
    // condition, so what a step validated is readable in full (WASM-report parity).
    const cond = "the payment method sheet shows the Gift Card option with the full balance amount rendered";
    const logs = [{
      class: `${T}.MaestroDriverLog`, durationMs: 5, deviceWidth: 10, deviceHeight: 20,
      action: { class: "xyz.AgentDriverAction.AssertCondition", conditionDescription: cond, succeeded: true, x: 1, y: 1 },
      timestamp: "2024-01-01T00:00:01Z",
    }];
    const trace = core.extractTrace(logs);
    expect(String(trace[0].tool)).not.toContain(cond);
    expect(trace[0].args).toContain("- AssertCondition:");
    expect(trace[0].args).toContain(`conditionDescription: ${cond}`);
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
      shots: { "a1.png": "data:image/png;base64,QTE=", "a2.png": "data:image/png;base64,QTI=" },
    });
    const state = renderViewerState(payloadOf(html), { routeStep: Number(stepTwo!.i) });
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe("data:image/png;base64,QTI=");
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
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace, llmLogs: [], shots: { "a1.png": "data:image/png;base64,QTE=", "a3.png": "data:image/png;base64,QTM=" } });
    const state = renderViewerState(payloadOf(html), { step: Number(mid!.i) });
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe("data:image/png;base64,QTE="); // nearest earlier frame — NOT step three's a3
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
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace, llmLogs: [], shots: { "a1.png": "data:image/png;base64,QTE=" } });
    const state = renderViewerState(payloadOf(html), { step: Number(stepTwo!.i) });
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe("data:image/png;base64,QTE="); // not an empty pane on the missing gone.png
  });

  test("a folded row whose own span captured nothing previews its first dispatch's frame", () => {
    // A row's frame comes from the logs before its first dispatch. When the device logged none
    // there, the dispatches the row absorbed are the only interactions it stands for, so the pane
    // shows the first of THEIR frames instead of the next row's unrelated screen — and playback,
    // which paints into that pane, can then show the dispatch frames the row's entries address.
    const trace = [
      { i: 0, label: "Sign in", tool: "", ms: 0, ts: 1000, ok: true, objective: true, screenshotFile: null },
      {
        i: 1,
        label: "signInWithPassword",
        tool: "trailhead",
        ms: 60,
        ts: 1100,
        ok: true,
        screenshotFile: null,
        children: [
          { label: "tapOn", tool: "tapOn email", ms: 20, ts: 1110, screenshotFile: "kid.png" },
          { label: "inputText", tool: "inputText", ms: 20, ts: 1130, screenshotFile: "kid2.png" },
        ],
      },
      { i: 2, label: "tapOn", tool: "tapOn Continue", ms: 20, ts: 1200, ok: true, screenshotFile: "later.png" },
    ];
    const html = core.buildRunReportHtml({
      meta: { title: "R", status: "passed" },
      trace,
      llmLogs: [],
      shots: { "kid.png": "data:image/png;base64,SzE=", "kid2.png": "data:image/png;base64,SzI=", "later.png": "data:image/png;base64,TA==" },
    });
    const state = renderViewerState(payloadOf(html), { step: 1 });
    expect(state.shotImg.src).toBe("data:image/png;base64,SzE="); // the row's own first dispatch — NOT later.png
  });
});

describe("timeline hover screenshot preview", () => {
  const ONE = "data:image/png;base64,ONE";
  const TWO = "data:image/png;base64,TWO";
  const THREE = "data:image/png;base64,THREE";
  const trace = [
    { i: 1, label: "Prepare checkout", tool: "agent step", objective: true, trailhead: true, ok: true, ts: 1000, ms: 0, screenshotFile: null, children: [] },
    { i: 2, label: "launchApp", tool: "launchApp", objective: false, ok: true, ts: 1100, ms: 100, screenshotFile: "one.png", children: [] },
    { i: 3, label: "tapOnElement", tool: "text: Pay", objective: false, ok: true, ts: 2000, ms: 100, screenshotFile: "two.png", children: [] },
    { i: 4, label: "assertVisible", tool: "text: Receipt", objective: false, ok: true, ts: 3000, ms: 100, screenshotFile: "three.png", children: [] },
  ];
  const payload = (patch: Record<string, unknown> = {}) => ({
    generatedAt: "now",
    sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: { "one.png": ONE, "two.png": TWO, "three.png": THREE }, recordingYaml: null, ...patch }],
  });

  test("hovering a timeline row previews that row's screenshot and hover-out restores the selected row", () => {
    const state = renderViewerState(payload(), {
      step: 2,
      drive: (ctx) => {
        const renders = ctx.renders();
        expect(ctx.shotImg.src).toBe(ONE);

        ctx.hoverStep(3);
        expect(ctx.shotImg.src).toBe(TWO);
        expect(ctx.renders()).toBe(renders);

        ctx.leaveStep(3);
        expect(ctx.shotImg.src).toBe(ONE);
        expect(ctx.renders()).toBe(renders);
      },
    });

    expect(new URL(state.route, "https://report.example").searchParams.get("step")).toBe("2");
  });

  test("mouse hover still previews on touch-first hybrid devices", () => {
    const previousMatchMedia = (globalThis as any).matchMedia;
    (globalThis as any).matchMedia = () => ({ matches: false, addEventListener() {}, removeEventListener() {} });
    try {
      renderViewerState(payload(), {
        step: 2,
        drive: (ctx) => {
          expect(ctx.shotImg.src).toBe(ONE);

          ctx.hoverStep(3);
          expect(ctx.shotImg.src).toBe(TWO);
        },
      });
    } finally {
      if (previousMatchMedia == null) delete (globalThis as any).matchMedia;
      else (globalThis as any).matchMedia = previousMatchMedia;
    }
  });

  test("hovering a folded dispatch previews that dispatch's own screenshot without selecting it", () => {
    const dispatchPayload = payload({
      trace: [
        { i: 1, label: "Prepare checkout", tool: "agent step", objective: true, trailhead: true, ok: true, ts: 1000, ms: 0, screenshotFile: null, children: [] },
        {
          i: 2,
          label: "signInWithPassword",
          tool: "trailhead",
          objective: false,
          ok: true,
          ts: 1100,
          ms: 200,
          screenshotFile: "one.png",
          children: [
            { label: "tapOn", tool: "tap email", ms: 20, ts: 1120, screenshotFile: "two.png" },
            { label: "inputText", tool: "type password", ms: 20, ts: 1160, screenshotFile: "three.png" },
          ],
        },
      ],
    });

    renderViewerState(dispatchPayload, {
      step: 2,
      drive: (ctx) => {
        const renders = ctx.renders();
        expect(ctx.shotImg.src).toBe(ONE);

        ctx.hoverKid("2:1");
        expect(ctx.shotImg.src).toBe(THREE);
        expect(ctx.renders()).toBe(renders);

        ctx.leaveKid("2:1");
        expect(ctx.shotImg.src).toBe(ONE);
        expect(ctx.renders()).toBe(renders);
      },
    });
  });

  test("hovering a step header previews the same final frame used by the lightbox summary", () => {
    const groupedPayload = payload({
      trace: [
        { i: 1, label: "Open checkout", tool: "agent step", objective: true, ok: true, ts: 1000, ms: 0, screenshotFile: null, children: [] },
        { i: 2, label: "launchApp", tool: "launchApp", objective: false, ok: true, ts: 1100, ms: 100, screenshotFile: "one.png", children: [] },
        { i: 3, label: "tapOnElement", tool: "text: Pay", objective: false, ok: true, ts: 2000, ms: 100, screenshotFile: "two.png", children: [] },
        { i: 4, label: "assertVisible", tool: "text: Receipt", objective: false, ok: true, ts: 3000, ms: 100, screenshotFile: "three.png", children: [] },
      ],
    });
    const lightbox = renderViewer(groupedPayload, { tab: "lightbox" });
    expect(lightbox).not.toContain('data-shot="one.png"');
    expect(lightbox).toContain('data-shot="three.png"');

    renderViewerState(groupedPayload, {
      step: 2,
      drive: (ctx) => {
        const renders = ctx.renders();
        expect(ctx.shotImg.src).toBe(ONE);

        ctx.hoverGroup(1);
        expect(ctx.shotImg.src).toBe(THREE);
        expect(ctx.renders()).toBe(renders);

        ctx.leaveGroup(1);
        expect(ctx.shotImg.src).toBe(ONE);
        expect(ctx.renders()).toBe(renders);
      },
    });
  });

  test("hovering a step header previews the lightbox child frame when captures are folded dispatches", () => {
    const foldedPayload = payload({
      trace: [
        { i: 1, label: "Sign in", tool: "agent step", objective: true, ok: true, ts: 1000, ms: 0, screenshotFile: null, children: [] },
        {
          i: 2,
          label: "signInWithPassword",
          tool: "trailhead",
          objective: false,
          ok: true,
          ts: 1100,
          ms: 200,
          screenshotFile: null,
          children: [
            { label: "tapOn", tool: "tap email", ms: 20, ts: 1120, screenshotFile: "two.png" },
            { label: "inputText", tool: "type password", ms: 20, ts: 1160, screenshotFile: "three.png" },
          ],
        },
      ],
    });
    const lightbox = renderViewer(foldedPayload, { tab: "lightbox" });
    expect(lightbox).toContain('data-lightbox-kid="1"');
    expect(lightbox).toContain('data-shot="three.png"');

    renderViewerState(foldedPayload, {
      step: 2,
      drive: (ctx) => {
        const renders = ctx.renders();
        expect(ctx.shotImg.src).toBe(TWO);

        ctx.hoverGroup(1);
        expect(ctx.shotImg.src).toBe(THREE);
        expect(ctx.renders()).toBe(renders);

        ctx.leaveGroup(1);
        expect(ctx.shotImg.src).toBe(TWO);
        expect(ctx.renders()).toBe(renders);
      },
    });
  });

  test("scrubber hover previews the same nearest frame the scrubber click would select", () => {
    renderViewerState(payload(), {
      step: 2,
      drive: (ctx) => {
        expect(ctx.shotImg.src).toBe(ONE);

        ctx.hoverScrub(0.95);
        expect(ctx.shotImg.src).toBe(THREE);

        ctx.leaveScrub();
        expect(ctx.shotImg.src).toBe(ONE);
      },
    });
  });

  test("hovering a captured stream event previews the frame for that event's timestamp", () => {
    renderViewerState(payload({
      events: [{ name: "network observer", total: 1, truncated: false, events: [{ t: 2950, d: '{"path":"/pay"}' }] }],
    }), {
      tlStream: 0,
      step: 2,
      drive: (ctx) => {
        expect(ctx.shotImg.src).toBe(ONE);

        ctx.hoverTimelineEvent("network observer-0");
        expect(ctx.shotImg.src).toBe(THREE);

        ctx.leaveTimelineEvent("network observer-0");
        expect(ctx.shotImg.src).toBe(ONE);
      },
    });
  });

  test("keyboard zoom is rebound after hover replaces an empty pane with a screenshot", () => {
    const state = renderViewerState(payload({
      trace: [
        { i: 1, label: "Step without a capture", tool: "agent step", objective: true, ok: true, ts: 1000, ms: 0, screenshotFile: null, children: [] },
        { i: 2, label: "waitForIdle", tool: "no screenshot captured", objective: false, ok: true, ts: 1100, ms: 50, screenshotFile: null, children: [] },
        { i: 3, label: "Step with a capture", tool: "agent step", objective: true, ok: true, ts: 2000, ms: 0, screenshotFile: null, children: [] },
        { i: 4, label: "tapOnElement", tool: "text: Pay", objective: false, ok: true, ts: 2100, ms: 100, screenshotFile: "two.png", children: [] },
      ],
    }), {
      step: 2,
      drive: (ctx) => {
        expect(ctx.html()).toContain("No screenshot captured before this step.");

        ctx.hoverStep(4);
        expect(ctx.shotImg.src).toBe(TWO);
        const key = ctx.keyShot("Enter");
        expect(key.defaultPrevented).toBe(true);
        expect(key.stopped).toBe(true);
      },
    });

    expect(state.zoomSrc).toBe(TWO);
  });
});

// A report can carry its screenshots three ways, and all three have to reach the screen: embedded
// base64 (the exported, portable report), a document-relative path (`generate-report
// --link-images`), and an absolute URL (device-farm sessions, whose frames stay hosted). The
// daemon's own /report page is the relative case with a `/static/` root. Only the first shape has
// coverage elsewhere in this file, so these pin the other two — plus the values that must NOT
// render, since the same guard is what keeps a payload string out of script position.
describe("linked screenshots (reports that reference frames instead of embedding them)", () => {
  const logs = [
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Step one" }, timestamp: "2024-01-01T00:00:00Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapA", traceId: "t1", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:01Z" },
    { class: `${T}.MaestroDriverLog`, traceId: "t1", action: { class: "xyz.AgentDriverAction.TapPoint", x: 1, y: 1 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "a.png", timestamp: "2024-01-01T00:00:01.100Z" },
  ];

  function renderWithShot(src: string) {
    const trace = core.extractTrace(logs);
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace, llmLogs: [], shots: { "a.png": src } });
    return renderViewerState(payloadOf(html), { step: Number(trace[0].i) });
  }

  test("renders a screenshot the daemon hosts under /static/", () => {
    const state = renderWithShot("/static/sess-1/a.png");
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe("/static/sess-1/a.png");
  });

  test("renders a document-relative screenshot (generate-report --link-images)", () => {
    const state = renderWithShot("sess-1/a.png");
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe("sess-1/a.png");
  });

  test("renders an absolute https screenshot (device-farm sessions keep frames hosted)", () => {
    const state = renderWithShot("https://farm.example.com/sessions/sess-1/a.png");
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe("https://farm.example.com/sessions/sess-1/a.png");
  });

  test("still embeds a base64 screenshot", () => {
    const state = renderWithShot("data:image/png;base64,QTE=");
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe("data:image/png;base64,QTE=");
  });

  test("drops a javascript: screenshot rather than rendering it", () => {
    const state = renderWithShot("javascript:alert(1)");
    expect(state.html).not.toContain('id="shot"');
    expect(state.html).not.toContain("javascript:");
  });

  test("drops a data: screenshot that is not an image", () => {
    const state = renderWithShot("data:text/html;base64,PHNjcmlwdD4=");
    expect(state.html).not.toContain('id="shot"');
    expect(state.html).not.toContain("text/html");
  });

  test("drops a screenshot path that could break out of its HTML attribute", () => {
    const state = renderWithShot('a.png" onerror="alert(1)');
    expect(state.html).not.toContain('id="shot"');
    expect(state.html).not.toContain("onerror");
  });

  test("drops a control-prefixed scheme, which a browser would parse as javascript:", () => {
    // Written as an escape, not a raw control byte: the byte is invisible in a diff, so this reads
    // as a duplicate of the plain `javascript:` case above and an editor could silently eat it.
    const state = renderWithShot("\u0001javascript:alert(1)");
    expect(state.html).not.toContain('id="shot"');
    expect(state.html).not.toContain("javascript:");
  });

  test("drops an svg data URI, which is a document rather than one of the raster frames we emit", () => {
    const state = renderWithShot("data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=");
    expect(state.html).not.toContain('id="shot"');
    expect(state.html).not.toContain("svg+xml");
  });

  test("drops a path padded with whitespace instead of silently accepting the trimmed value", () => {
    const state = renderWithShot(" /static/sess-1/a.png ");
    expect(state.html).not.toContain('id="shot"');
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

  test("shows LLM reasoning as a quote before the tool call it produced", () => {
    const rendered = renderViewer(payloadOf(html));
    const quote = '<blockquote class="stepreason">“the login button is visible”</blockquote>';
    expect(rendered).toContain(quote);
    expect(rendered.indexOf(quote)).toBeLessThan(rendered.indexOf('<div class="lbl">tapOnElement</div>'));
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

// The seam Trail Runner's run details drives: while a run executes, the embedder polls and merges
// the grown payload in rather than rebooting the viewer. What these pin is that the merge shows the
// new work WITHOUT disturbing the reader — booting again would reset tab, step and scroll, which is
// exactly the thing this seam exists to avoid.
describe("live updates (__TB_REPORT_LIVE__)", () => {
  // Steps arrive one at a time, so a run in progress is a prefix of a longer trace.
  const liveLogs = (count: number) => {
    const out: Array<Record<string, unknown>> = [];
    for (let n = 1; n <= count; n++) {
      out.push({ class: `${T}.ObjectiveStartLog`, promptStep: { step: `Step ${n}` }, timestamp: `2024-01-01T00:00:0${n}Z` });
      out.push({ class: `${T}.TrailblazeToolLog`, toolName: `tap${n}`, traceId: `t${n}`, trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: `2024-01-01T00:00:0${n}.500Z` });
    }
    return out;
  };
  const livePayload = (count: number, status = "running", extra: Record<string, unknown> = {}) => {
    const payload = payloadOf(core.buildRunReportHtml({
      meta: { title: "Live run", status },
      trace: core.extractTrace(liveLogs(count)),
      llmLogs: [],
      shots: {},
    }));
    Object.assign(payload.sessions[0], extra);
    return payload;
  };
  const grown = (count: number, extra: Record<string, unknown> = {}) => livePayload(count, "running", extra).sessions[0];
  // The selected row is the one carrying aria-current; read it back rather than asserting on a
  // whole markup fragment, so these stay about the selection and not about class ordering.
  const selectedStep = (html: string) => (html.match(/data-step="(\d+)"[^>]*aria-current="step"/) || [])[1] || null;
  const tailOf = (session: { trace: Array<{ i: number }> }) => String(session.trace[session.trace.length - 1].i);

  test("publishes the seam once booted", () => {
    const state = renderViewerState(livePayload(2));
    expect(typeof state.live()?.update).toBe("function");
    expect(typeof state.live()?.destroy).toBe("function");
  });

  test("a torn-down handle stops painting, so a push queued before teardown lands nowhere", () => {
    const state = renderViewerState(livePayload(1));
    const handle = state.live()!;
    handle.destroy();
    handle.update(0, grown(2));
    expect(state.readHtml()).not.toContain("Step 2");
  });

  test("a handle from an earlier boot tears down its own viewer, not the one that replaced it", () => {
    const state = renderViewerState(livePayload(2));
    const stale = state.live()!;
    expect(state.documentKeyListeners.length).toBe(1);
    // The shell boots the viewer once per archived run into the same document; each boot disposes
    // the previous one, so there is still exactly one keyboard owner.
    core.RUN_REPORT_VIEWER();
    expect(state.documentKeyListeners.length).toBe(1);
    const fresh = state.live()!;
    expect(fresh).not.toBe(stale);
    // The stale handle's teardown is its own, already spent. Reaching for the module-scoped disposer
    // here would unhook the keyboard from the viewer now on screen.
    stale.destroy();
    expect(state.documentKeyListeners.length).toBe(1);
  });

  test("a pushed payload renders the steps that just arrived", () => {
    const state = renderViewerState(livePayload(1));
    expect(state.readHtml()).not.toContain("Step 2");
    state.live()!.update(0, grown(2));
    expect(state.readHtml()).toContain("Step 2");
  });

  test("a push keeps the reader on the tab they chose", () => {
    const state = renderViewerState(livePayload(2), { tab: "info" });
    expect(state.readHtml()).toContain('class="active" data-tab="info"');
    state.live()!.update(0, grown(3));
    // A reboot would have dropped back to the timeline.
    expect(state.readHtml()).toContain('class="active" data-tab="info"');
  });

  test("a push preserves the scroll offset of a reader who is NOT following the tail", () => {
    const three = livePayload(3);
    const state = renderViewerState(three, { step: Number(three.sessions[0].trace[1].i), timelineScrollTop: 240 });
    state.live()!.update(0, grown(4));
    expect(state.readTimelineScrollTop()).toBe(240);
  });

  test("a push DOES scroll to the new tail for a reader who is following it", () => {
    const two = livePayload(2);
    const state = renderViewerState(two, { step: Number(tailOf(two.sessions[0])), timelineScrollTop: 240 });
    state.live()!.update(0, grown(3));
    // Following means the newest row is brought into view; leaving the offset alone would park the
    // reader above content that keeps arriving.
    expect(state.readTimelineScrollTop()).not.toBe(240);
  });

  test("follows the tail while the reader is parked on the newest step", () => {
    const two = livePayload(2);
    const state = renderViewerState(two, { step: Number(tailOf(two.sessions[0])) });
    expect(selectedStep(state.readHtml())).toBe(tailOf(two.sessions[0]));
    const three = grown(3);
    state.live()!.update(0, three);
    expect(selectedStep(state.readHtml())).toBe(tailOf(three));
  });

  // A run's records land as they happen, so most of the time the trace ENDS on the objective header
  // of the step the device is in the middle of — the step has started, its tool call has not been
  // written yet. Those header rows carry no selection, so "the newest row" has to mean the newest
  // row a reader can sit on, in the seed AND in the follow. When only one of them resolved it, a run
  // streamed in with the selection frozen on whatever step it was opened at.
  const startedStep = (count: number) => {
    const payload = payloadOf(core.buildRunReportHtml({
      meta: { title: "Live run", status: "running" },
      trace: core.extractTrace([
        ...liveLogs(count),
        { class: `${T}.ObjectiveStartLog`, promptStep: { step: `Step ${count + 1}` }, timestamp: `2024-01-01T00:00:1${count}Z` },
      ]),
      llmLogs: [],
      shots: {},
    }));
    return payload;
  };
  const lastActionOf = (session: { trace: Array<{ i: number; objective?: boolean }> }) =>
    String([...session.trace].reverse().find((r) => !r.objective)!.i);

  test("opens a step-in-progress run on its newest ACTION, not the header of the step that just started", () => {
    const one = startedStep(1);
    const state = renderViewerState(one);
    expect(selectedStep(state.readHtml())).toBe(lastActionOf(one.sessions[0]));
  });

  test("follows the tail of a run whose trace ends on a step that has not acted yet", () => {
    const two = startedStep(2);
    const state = renderViewerState(two);
    expect(selectedStep(state.readHtml())).toBe(lastActionOf(two.sessions[0]));
    const three = startedStep(3).sessions[0];
    state.live()!.update(0, three);
    expect(selectedStep(state.readHtml())).toBe(lastActionOf(three));
    const four = startedStep(4).sessions[0];
    state.live()!.update(0, four);
    expect(selectedStep(state.readHtml())).toBe(lastActionOf(four));
  });

  test("stops following once the reader selects an earlier step", () => {
    const three = livePayload(3);
    const earlier = String(three.sessions[0].trace[1].i);
    const state = renderViewerState(three, { step: Number(earlier) });
    expect(selectedStep(state.readHtml())).toBe(earlier);
    state.live()!.update(0, grown(4));
    // Their step stays selected; the row that just arrived does not steal it.
    expect(selectedStep(state.readHtml())).toBe(earlier);
  });

  // A row's `i` is its position in the derived trace, and a record can land late enough to sort
  // BEFORE the reader's selection — which renumbers every row after it. Holding `st.step` steady
  // would then quietly move the reader one step down the timeline.
  test("keeps the reader on the row they selected when a late record renumbers the trace", () => {
    const late = () => {
      const logs = liveLogs(3);
      logs.splice(2, 0, {
        class: `${T}.TrailblazeToolLog`,
        toolName: "tapLate",
        traceId: "late",
        trailblazeTool: { raw: {} },
        successful: true,
        durationMs: 10,
        timestamp: "2024-01-01T00:00:01.700Z",
      });
      return payloadOf(core.buildRunReportHtml({
        meta: { title: "Live run", status: "running" },
        trace: core.extractTrace(logs),
        llmLogs: [],
        shots: {},
      })).sessions[0];
    };
    const rowFor = (session: any, label: string) => session.trace.find((r: any) => r.label === label);

    const three = livePayload(3);
    const mine = rowFor(three.sessions[0], "tap2");
    const state = renderViewerState(three, { step: Number(mine.i) });
    expect(selectedStep(state.readHtml())).toBe(String(mine.i));

    const grownWithLate = late();
    const moved = rowFor(grownWithLate, "tap2");
    // The row genuinely changed number, which is what makes this worth guarding.
    expect(moved.i).not.toBe(mine.i);
    state.live()!.update(0, grownWithLate);
    expect(selectedStep(state.readHtml())).toBe(String(moved.i));
  });

  test("a run in progress opens on its newest step, not the start of the trail", () => {
    const three = livePayload(3);
    const state = renderViewerState(three);
    expect(selectedStep(state.readHtml())).toBe(tailOf(three.sessions[0]));
  });

  test("a finished run still opens on its trail start, not its tail", () => {
    const finished = livePayload(3, "passed");
    const state = renderViewerState(finished);
    expect(selectedStep(state.readHtml())).not.toBe(tailOf(finished.sessions[0]));
  });

  // The cache hazard the seam's contract calls out: the inflaters key on the session OBJECT, which
  // a merge deliberately keeps, and the accessors prefer the inline field. So an uncompressed push
  // is read fresh on every render, where a gz push would be inflated once and then served from that
  // cache for the rest of the run — the timeline would grow while the device log stayed frozen.
  test("each uncompressed device-log push replaces the last one", () => {
    const state = renderViewerState(livePayload(2, "running", { deviceLog: "first poll line" }), { tab: "device" });
    expect(state.readHtml()).toContain("first poll line");
    state.live()!.update(0, grown(3, { deviceLog: "second poll line" }));
    expect(state.readHtml()).toContain("second poll line");
    expect(state.readHtml()).not.toContain("first poll line");
  });

  // A live producer assembles each push from several requests, so a channel it failed to fetch this
  // time arrives empty. Merging that would blank a stream the reader already has.
  test("a push that lost a side channel leaves the one already rendered in place", () => {
    const streams = [{ name: "com.example.plugin.network", total: 1, truncated: false, events: [{ t: 1000, d: "{}" }] }];
    const state = renderViewerState(livePayload(3, "running", { events: streams }));
    expect(state.readHtml()).toContain('<span class="streamname">network</span>');
    state.live()!.update(0, grown(4, { events: null }));
    expect(state.readHtml()).toContain('<span class="streamname">network</span>');
  });

  // An expanded event body is the one piece of "where the reader is" that lives in the DOM instead
  // of `st`: <details open> plus a body the lazy fill wrote. A render rebuilds both, so without
  // carrying them across, an event a reader opened would snap shut on the next log burst — several
  // times a step, on the payload they were in the middle of reading.
  const eventStream = (...urls: string[]) => [{
    name: "com.example.plugin.network",
    total: urls.length,
    truncated: false,
    events: urls.map((url, n) => ({ t: 1000 + n, d: JSON.stringify({ url }) })),
  }];
  const firstEventKey = "com.example.plugin.network-0";

  test("a push keeps the event payload the reader expanded open", () => {
    const state = renderViewerState(livePayload(2, "running", { events: eventStream("/first") }), { tlStream: 0 });
    state.expandTimelineEvent(firstEventKey);
    expect(state.timelineEvent(firstEventKey)!.body).toContain("/first");

    state.live()!.update(0, grown(3, { events: eventStream("/first", "/second") }));
    const reopened = state.timelineEvent(firstEventKey)!;
    expect(reopened.open).toBe(true);
    expect(reopened.body).toContain("/first");
  });

  // The attachment dialog is mounted on document.body, outside #app, so a caller that swaps the
  // report out — the shell loading the next archive — would otherwise leave it stranded over the
  // new run, still playing the previous archive's audio, with no handler left that can dismiss it.
  test("tearing the viewer down takes an open attachment dialog with it", () => {
    const speech = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 16044, label: "hello" }) }],
    }];
    const state = renderViewerState(livePayload(2, "running", { events: speech }), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    const body = state.timelineEvent("com.example.plugin.speech-0")!.body;
    state.openAttachment((/data-attach="(\d+)"/.exec(body) || [])[1]);
    const dialog = state.liveZoomRoot();
    expect(dialog.className).toBe("attachoverlay");
    expect(dialog.innerHTML).toContain("hello");

    state.live()!.destroy();
    expect(dialog.removed).toBe(true);
  });

  // Opening an attachment is a deliberate click on Open, so the media plays on arrival rather than
  // asking for a second click. Both halves matter: the attribute is what a browser acts on when the
  // element is parsed, and the explicit play() covers a player mounted after that moment.
  test("opening an attachment starts the media instead of waiting for a second click", () => {
    const speech = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 16044 }) }],
    }];
    const embedded = { events: speech, attachments: { "attachments/utterance_1.wav": "data:audio/wav;base64,AAAA" } };
    const state = renderViewerState(livePayload(2, "running", embedded), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);

    const player = state.liveZoomRoot().querySelector("audio, video");
    expect(player.autoplayAttr).toBe(true);
    expect(player.played).toBe(1);
  });

  // The inline player is best-effort — a browser whose demuxer refuses this particular file shows
  // a dead 0:00 player with no error surface — so every media attachment whose bytes are reachable
  // also offers Download. `download` on a data: href saves the bytes without ever giving them a
  // browsing context, so this doesn't widen the no-navigation boundary the non-media branch pins.
  test("a media attachment offers Download beside the player", () => {
    const speech = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 16044 }) }],
    }];
    const embedded = { events: speech, attachments: { "attachments/utterance_1.wav": "data:audio/wav;base64,AAAA" } };
    const state = renderViewerState(livePayload(2, "running", embedded), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);

    const html = state.liveZoomRoot().innerHTML;
    expect(html).toContain("<audio");
    expect(html).toContain('download="utterance_1.wav"');
    expect(html).toContain('href="data:audio/wav;base64,AAAA"');
  });

  // The native player surfaces a refused load as nothing but a dead 0:00 timeline — which reads
  // as "the recording is empty". It is real wherever a report is served under a CSP whose
  // `default-src` matches only network schemes: an embedded data: WAV is refused there while the
  // same report plays everywhere else. The dialog must say what happened AND take the useless
  // control away, and must do neither while playback is fine.
  test("a player whose load errors is replaced by the blocked-playback note; a healthy one stays quiet", () => {
    const speech = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 16044 }) }],
    }];
    const embedded = { events: speech, attachments: { "attachments/utterance_1.wav": "data:audio/wav;base64,AAAA" } };
    const state = renderViewerState(livePayload(2, "running", embedded), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);

    const root = state.liveZoomRoot();
    const note = root.querySelector(".attachblockednote");
    const player = root.querySelector("audio, video");
    expect(note.hidden).toBe(true);
    // Falsy, not `false`: the player renders with no `hidden` attribute at all, which this DOM
    // stub surfaces as undefined. What matters is that it is showing before the error.
    expect(player.hidden).toBeFalsy();

    player.fire("error");

    expect(note.hidden).toBe(false);
    // The dead control goes away with the explanation. Leaving it renders a play button that can
    // never play, directly under a note saying so.
    expect(player.hidden).toBe(true);
    // Pinned through the closing tag, not just the text. This ships in the open-source report
    // framework, so the explanation has to stay host-agnostic — the same CSP shape blocks embedded
    // media on any host whose `default-src` lists only network schemes, and naming one vendor
    // would be wrong everywhere else. The `</div>` is what makes appending "(X's viewer does)"
    // fail: without it the original text survives as a prefix and a substring check still passes.
    expect(root.innerHTML).toContain(
      "Inline playback isn't available on this page — its host may block embedded media, " +
        "or this browser may not support this format. Download the file below to play it.</div>",
    );

    // `hidden` only actually hides when no author display rule outranks the UA's [hidden] rule —
    // and `.attachnote`, which this note also wears, sets `display: grid`. Without the explicit
    // rule the note is visible from the moment the dialog opens, on every healthy attachment.
    expect(core.RUN_REPORT_CSS).toContain(".attachblockednote[hidden] { display: none; }");
    // Media elements carry a UA display of their own, so the hide above needs its own author rule.
    expect(core.RUN_REPORT_CSS).toContain(".attachmedia audio[hidden], .attachmedia video[hidden] { display: none; }");
  });

  // A blob: URL resolves only in the page that minted it. The zip pipeline mints them on this
  // origin; a foreign one in a bundle-authored map would render a Download that can never resolve.
  test("a blob: attachment from another origin gets no Download control", () => {
    const stream = (path: string, mime: string) => [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path, mimeType: mime, sizeBytes: 2048 }) }],
    }];
    const bodyFor = (uri: string, baseURI?: string) => {
      const payload = { events: stream("attachments/utterance_1.wav", "audio/wav"), attachments: { "attachments/utterance_1.wav": uri } };
      const state = renderViewerState(livePayload(2, "running", payload), { tlStream: 0, baseURI });
      state.expandTimelineEvent("com.example.plugin.speech-0");
      state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);
      return state.liveZoomRoot().innerHTML;
    };

    // The harness renders on https://report.example (see livePayload's base).
    expect(bodyFor("blob:https://report.example/att-wav")).toContain('download="utterance_1.wav"');
    expect(bodyFor("blob:https://evil.example/att-wav")).not.toContain("download=");

    // `trailblaze viewer` opens the standalone HTML over file://, where createObjectURL mints an
    // opaque `blob:null/<uuid>`. That is the surface with no other route to the bytes, so it must
    // keep Download — while a named foreign origin is still refused from the same opaque document.
    const filePage = "file:///Users/someone/report.html";
    expect(bodyFor("blob:null/3f2b1c4d-att", filePage)).toContain('download="utterance_1.wav"');
    expect(bodyFor("blob:https://evil.example/att-wav", filePage)).not.toContain("download=");
  });

  // A non-media data: embed used to dead-end at the bundle note even though the bytes were right
  // there in the report: the media-src gate (correctly) refuses to give them an element, but that
  // gate must not also decide downloadability. Any base64 data: URI is safe to SAVE.
  test("a non-media embedded attachment is downloadable rather than claiming it isn't embedded", () => {
    const stream = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/notes.json", mimeType: "application/json", sizeBytes: 64 }) }],
    }];
    const embedded = { events: stream, attachments: { "attachments/notes.json": "data:application/json;base64,e30=" } };
    const state = renderViewerState(livePayload(2, "running", embedded), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);

    const html = state.liveZoomRoot().innerHTML;
    expect(html).not.toContain("<audio");
    expect(html).not.toContain("not embedded");
    expect(html).toContain('download="notes.json"');
    expect(html).toContain('href="data:application/json;base64,e30="');
  });

  // A dialog that dims the report instead of replacing it promises that the dimmed part is still
  // there: clicking it has to dismiss. A click that lands INSIDE the panel must not — that would
  // close the dialog on every press of the player's own controls.
  test("clicking the dimmed report closes the attachment dialog; clicking the panel does not", () => {
    const speech = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 16044 }) }],
    }];
    const embedded = { events: speech, attachments: { "attachments/utterance_1.wav": "data:audio/wav;base64,AAAA" } };
    const state = renderViewerState(livePayload(2, "running", embedded), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);
    const dialog = state.liveZoomRoot();

    const clickOn = (target: any) => dialog.onclick({ target, stopPropagation() {} });
    clickOn(dialog.querySelector("audio, video"));
    expect(dialog.removed).toBe(false);
    clickOn(dialog);
    expect(dialog.removed).toBe(true);
  });

  // Back/Forward replaces the view UNDER the dialog: the attachment dialog is mounted on
  // document.body like the transcript and inspector, so without an explicit close it survives the
  // route change, stranded over a view it has nothing to do with and still playing its audio.
  test("navigating away with the browser closes the attachment dialog", () => {
    const speech = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 16044 }) }],
    }];
    const embedded = { events: speech, attachments: { "attachments/utterance_1.wav": "data:audio/wav;base64,AAAA" } };
    const state = renderViewerState(livePayload(2, "running", embedded), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);
    const dialog = state.liveZoomRoot();
    expect(dialog.className).toBe("attachoverlay");

    state.firePopstate("?view=runs");
    expect(dialog.removed).toBe(true);
  });

  // Link mode (the live daemon report) stores `/static/<id>/<path>` for every attachment MIME, so a
  // type with no native element — the only kind that reaches the link branch — arrives here.
  const linkedAttachmentState = (uri: string) => {
    const stream = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/notes.html", mimeType: "text/html", sizeBytes: 2048 }) }],
    }];
    const payload = { events: stream, attachments: { "attachments/notes.html": uri } };
    const state = renderViewerState(livePayload(2, "running", payload), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);
    return state.liveZoomRoot().innerHTML;
  };

  // A session bundle can carry any file, and the daemon serves /static from the SAME origin as the
  // app — so opening one in a tab runs whatever it is with everything that origin has. Downloading
  // never gives those bytes a browsing context. The declared mimeType is bundle-authored data and
  // decides nothing here; what a navigation would render is the daemon's Content-Type.
  test("a non-media attachment downloads instead of opening on the report's own origin", () => {
    const html = linkedAttachmentState("/static/run-1/attachments/notes.html");
    expect(html).toContain('href="https://report.example/static/run-1/attachments/notes.html"');
    expect(html).toContain('download="notes.html"');
    expect(html).not.toContain('target="_blank"');
  });

  // `download` is ignored cross-origin, so an absolute off-origin value would quietly turn back
  // into the navigation the download exists to prevent. That one gets no link at all.
  test("an attachment link pointing off-origin is refused rather than downloaded", () => {
    const html = linkedAttachmentState("https://evil.example/payload.html");
    expect(html).not.toContain("<a ");
    expect(html).toContain("attachments/notes.html");
  });

  // The dialog's body is a native <audio>/<video> player, so Space and the arrows belong to whatever
  // control the reader focused. Underneath, the timeline shortcuts preventDefault exactly those
  // keys — which would leave a player that looks focused and refuses to play.
  test("an open attachment dialog owns the keyboard instead of the timeline underneath", () => {
    const speech = [{
      name: "com.example.plugin.speech",
      total: 1,
      truncated: false,
      events: [{ t: 1000, d: JSON.stringify({ $attachment: true, path: "attachments/utterance_1.wav", mimeType: "audio/wav", sizeBytes: 16044 }) }],
    }];
    const state = renderViewerState(livePayload(2, "running", { events: speech }), { tlStream: 0 });
    state.expandTimelineEvent("com.example.plugin.speech-0");
    state.openAttachment((/data-attach="(\d+)"/.exec(state.timelineEvent("com.example.plugin.speech-0")!.body) || [])[1]);

    const press = (key: string) => {
      const event = { key, defaultPrevented: false, preventDefault() { this.defaultPrevented = true; } };
      state.documentKeyListeners[0](event);
      return event.defaultPrevented;
    };
    expect(press(" ")).toBe(false);
    expect(press("ArrowRight")).toBe(false);
    // Escape still closes it, as for every other modal destination.
    expect(press("Escape")).toBe(true);
    expect(state.liveZoomRoot().removed).toBe(true);
  });

  test("a push leaves the events the reader did NOT expand collapsed", () => {
    const state = renderViewerState(livePayload(2, "running", { events: eventStream("/first", "/second") }), { tlStream: 0 });
    state.expandTimelineEvent(firstEventKey);
    state.live()!.update(0, grown(3, { events: eventStream("/first", "/second") }));
    const untouched = state.timelineEvent("com.example.plugin.network-1")!;
    expect(untouched.open).toBe(false);
    expect(untouched.body).toBe("");
  });

  test("a push for a run the reader is NOT looking at repaints without costing them their place", () => {
    const multi = { generatedAt: "now", sessions: [grown(3), grown(3)] };
    const mine = multi.sessions[0].trace[1];
    const state = renderViewerState(multi, { session: 0, step: Number(mine.i), timelineScrollTop: 240 });
    state.live()!.update(1, grown(4));
    expect(state.readTimelineScrollTop()).toBe(240);
    expect(selectedStep(state.readHtml())).toBe(String(mine.i));
  });

  // The address bar is selection state everywhere else in the viewer, so reloading a running report
  // has to land where the reader was left, not on whatever row was newest when they arrived.
  test("a selection the seam moves is written to the shareable route", () => {
    const two = livePayload(2);
    const state = renderViewerState(two, { step: Number(tailOf(two.sessions[0])) });
    const three = grown(3);
    state.live()!.update(0, three);
    expect(state.readRoute()).toContain(`step=${tailOf(three)}`);
  });

  test("a push for a session that isn't loaded is ignored rather than throwing", () => {
    const state = renderViewerState(livePayload(2));
    expect(() => state.live()!.update(7, grown(3))).not.toThrow();
    expect(() => state.live()!.update(0, null as unknown as Record<string, unknown>)).not.toThrow();
    expect(state.readHtml()).toContain("Live run");
  });
});

describe("embedded chrome (?chrome=none)", () => {
  // Trail Runner mounts this report inside its run details, where the run's title, its status and
  // the app's own theme control are already on screen above the frame. Rendering them again inside
  // the frame is two headers for one run.
  const embeddedPayload = (shot = "data:image/png;base64,EMB") => ({
    generatedAt: "now",
    sessions: [{
      meta: { title: "Checkout flow", status: "passed" },
      trace: [{ i: 1, label: "Open app", objective: true, ok: true, screenshotFile: "s1.png" }],
      llm: [],
      shots: { "s1.png": shot },
      recordingYaml: null,
    }],
  });

  test("hands the run's identity row to the embedder and keeps the report's own controls", () => {
    const out = renderViewer(embeddedPayload(), { query: "?chrome=none" });
    expect(out).toContain('class="detailheader notitle"');
    expect(out).not.toContain("detailtitle"); // no second run title, status dot or back button
    expect(out).not.toContain("data-theme-toggle"); // theming follows the host app
    // What stays: the report's tabs, and the export menu whose items the host has no equivalent for.
    expect(out).toContain('data-tab="timeline"');
    expect(out).toContain("data-export-menu");
    expect(core.RUN_REPORT_CSS).toContain(".detailheader.notitle { padding-top: var(--space-2); }");
  });

  test("lets the host's surface show through instead of painting its own page colour", () => {
    expect(renderViewerState(embeddedPayload(), { query: "?chrome=none" }).embeddedMarker()).toBe("1");
    expect(renderViewerState(embeddedPayload()).embeddedMarker()).toBeUndefined();
    expect(core.RUN_REPORT_CSS).toContain("html[data-tb-embedded] body { background: transparent; }");
  });

  test("standalone, the report still renders its own full header", () => {
    const out = renderViewer(embeddedPayload());
    expect(out).toContain("detailtitle");
    expect(out).toContain("Checkout flow");
    expect(out).toContain("data-theme-toggle");
    expect(out).not.toContain("notitle");
  });

  test("the flag survives navigation inside the frame, and navigation never introduces it", () => {
    const moved = renderViewerState(embeddedPayload(), { query: "?chrome=none&run=0&tab=timeline&step=1", tab: "info" });
    const movedUrl = new URL(moved.route, "https://report.example");
    expect(movedUrl.searchParams.get("chrome")).toBe("none"); // a reload in the frame stays chromeless
    expect(movedUrl.searchParams.get("tab")).toBe("info");

    const standalone = renderViewerState(embeddedPayload(), { query: "?run=0&tab=timeline&step=1", tab: "info" });
    expect(new URL(standalone.route, "https://report.example").searchParams.has("chrome")).toBe(false);
  });

  // Export report snapshots the document it runs in. When the frames are references — the daemon's
  // live report, a `--link-images` build, a farm run whose screenshots stay hosted — that snapshot
  // is a file whose images point back at a server: it looks portable, and stops rendering the moment
  // the daemon exits or the artifacts age out.
  test("the image exports are offered for an embedded-frame report and withheld from a linked one", () => {
    const embedded = renderViewer(embeddedPayload());
    expect(embedded).toContain('id="exportrun"');
    expect(embedded).toContain('id="exportscreenshots"');

    const linked = renderViewer(embeddedPayload("/static/run-7/s1.png"));
    expect(linked).not.toContain('id="exportrun"');
    // Withheld rather than shown disabled at "0": the run has frames on screen, they just aren't
    // bytes this document can write out.
    expect(linked).not.toContain('id="exportscreenshots"');
    // The exports that don't depend on the frames stay.
    expect(linked).toContain('id="exportlogs"');
    expect(linked).toContain('id="copylocalprompt"');
  });

  // A report rendered with its object URLs kept (the zip viewer's in-page iframe) carries `blob:`
  // attachment values inside its #tb-session-<i> chunks. Downloading that document has to strip
  // them: the bytes belong to the page that read the archive, so in the saved file every Open would
  // resolve to nothing — while the live page it was exported from keeps playing them.
  test("a downloaded report drops the attachment object URLs only the page holding the archive can resolve", async () => {
    const html = core.buildMultiReportHtml({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Zip run", status: "passed" },
        trace: [{ i: 1, label: "Open app", objective: true, ok: true, screenshotFile: "s1.png" }],
        llmLogs: [],
        shots: { "s1.png": "data:image/png;base64,EMB" },
        attachments: {
          "attachments/utterance_1.wav": "blob:https://app.test/att-wav",
          "attachments/frame.png": "data:image/png;base64,ATTACHBYTES",
        },
      }] as never,
      keepAttachmentObjectUrls: true,
    });
    expect(html).toContain("blob:https://app.test/att-wav"); // the in-page document really does carry it

    const urlAny = URL as any;
    const original = { create: urlAny.createObjectURL, revoke: urlAny.revokeObjectURL };
    let downloaded: Blob | null = null;
    urlAny.createObjectURL = (blob: Blob) => { downloaded = blob; return "blob:test"; };
    urlAny.revokeObjectURL = () => {};
    try {
      renderViewerState(null, { chunks: chunksOf(html), exportRun: true });
      const text = await downloaded!.text();
      expect(text).not.toContain("blob:https://app.test/att-wav");
      // The portable embed is exactly what makes an attachment survive the trip, so it travels.
      expect(text).toContain("data:image/png;base64,ATTACHBYTES");
    } finally {
      urlAny.createObjectURL = original.create;
      urlAny.revokeObjectURL = original.revoke;
    }
  });

  // Same reasoning one level up: the index's "Download report" writes out every run in the document.
  test("the index withholds Download report when any run's frames are links", () => {
    const twoRuns = (shot: string) => ({
      generatedAt: "now",
      sessions: [embeddedPayload().sessions[0], { ...embeddedPayload(shot).sessions[0], meta: { title: "Second run", status: "failed" } }],
    });
    expect(renderViewer(twoRuns("data:image/png;base64,TWO"))).toContain('id="exportall"');
    expect(renderViewer(twoRuns("/static/run-7/s1.png"))).not.toContain('id="exportall"');
  });

  // The video's sprite sheets are frames the same way the screenshots are, and they follow the same
  // embed-or-link switch. A run can have a video and no step screenshots at all (link mode normally
  // links both, so this is the shape that slips past a `shots`-only guard) — and an export of it
  // would hand back a file whose Video tab goes blank the moment the serving daemon exits.
  const videoOnlyPayload = (spriteUri: string) => ({
    generatedAt: "now",
    sessions: [{
      meta: { title: "Video only", status: "passed" },
      trace: [{ i: 1, label: "Open app", objective: true, ok: true }],
      llm: [],
      shots: {},
      recordingYaml: null,
      video: { sprites: [{ uri: spriteUri, rows: 2 }], fps: 2, frames: 2, columns: 1, rows: 2, frameHeight: 40, frameMap: [0, 1], startFrame: 0, endFrame: 1 },
    }],
  });

  test("the image exports are withheld from a run whose only frames are linked video sprites", () => {
    const embedded = renderViewer(videoOnlyPayload("data:image/webp;base64,SPRITEBYTES"));
    expect(embedded).toContain('data-tab="video"'); // there really is a video either way
    expect(embedded).toContain('id="exportrun"');

    const linked = renderViewer(videoOnlyPayload("/static/run-7/video_sprites.webp"));
    expect(linked).toContain('data-tab="video"');
    expect(linked).not.toContain('id="exportrun"');
    expect(linked).not.toContain('id="exportscreenshots"');
    expect(linked).toContain('id="exportlogs"');
  });

  test("the index withholds Download report when a run's only frames are linked video sprites", () => {
    const twoRuns = (spriteUri: string) => ({
      generatedAt: "now",
      sessions: [embeddedPayload().sessions[0], { ...videoOnlyPayload(spriteUri).sessions[0], meta: { title: "Second run", status: "failed" } }],
    });
    expect(renderViewer(twoRuns("data:image/webp;base64,SPRITEBYTES"))).toContain('id="exportall"');
    expect(renderViewer(twoRuns("/static/run-7/video_sprites.webp"))).not.toContain('id="exportall"');
  });

  // Chunked documents hoist the sprite URIs out of the session payload (buildMultiReportHtml), so
  // the guard has to resolve the #tb-sprites-<i> chunk to see the links at all.
  test("a chunked run's hoisted sprite chunk is resolved before the export is offered", () => {
    const session = (spriteUri: string) => ({
      meta: { title: "Video only", status: "passed" },
      trace: core.extractTrace(sampleLogs),
      llmLogs: [],
      shots: {},
      video: { sprites: [{ uri: spriteUri, rows: 2 }], fps: 2, frames: 2, columns: 1, rows: 2, frameHeight: 40, frameMap: [0, 1], startFrame: 0, endFrame: 1 },
    });
    const chunked = (spriteUri: string) => renderViewer(null, {
      chunks: chunksOf(core.buildMultiReportHtml({ generatedAt: "now", sessions: [session(spriteUri)] })),
    });
    expect(chunked("data:image/webp;base64,SPRITEBYTES")).toContain('id="exportrun"');
    expect(chunked("/static/run-7/video_sprites.webp")).not.toContain('id="exportrun"');
  });

  // Both document exports clone the document and rewrite its payload node. The daemon's live report
  // (and any embedder injecting window.__TB_RUN_DATA__) has no such node, so exportReport bails and
  // the click does nothing. The frame guards hide the items for a linked report, but a run with no
  // frames at all leaves them nothing linked to find — that's the shape that leaked a dead click.
  test("the document exports are withheld when the payload arrives as a global, not a node", () => {
    const framelessRun = (title: string) => ({
      meta: { title, status: "passed" },
      trace: [{ i: 1, label: "Open app", objective: true, ok: true }],
      llm: [],
      shots: {},
      recordingYaml: null,
    });
    const oneRun = { generatedAt: "now", sessions: [framelessRun("Live run")] };
    const twoRuns = { generatedAt: "now", sessions: [framelessRun("Live run"), framelessRun("Second run")] };

    const run = renderViewer(oneRun, { payloadViaGlobal: true });
    expect(run).toContain("Live run"); // the run itself still renders off the global
    expect(run).not.toContain('id="exportrun"');
    // Export logs writes its own blob and never clones the document, so it stays.
    expect(run).toContain('id="exportlogs"');
    expect(renderViewer(twoRuns, { payloadViaGlobal: true })).not.toContain('id="exportall"');

    // The same runs out of a document that carries its own payload node: offered, as before.
    expect(renderViewer(oneRun)).toContain('id="exportrun"');
    expect(renderViewer(twoRuns)).toContain('id="exportall"');
  });

  // Inside the frame the browser's address is this document — `report-live.html?...&chrome=none` —
  // which opens as a header-less report rather than the run page a reader means to send someone.
  test("an embedded report offers no Copy link, and a hosted standalone one still does", () => {
    const embedded = renderViewer(embeddedPayload(), { protocol: "https:", query: "?chrome=none" });
    expect(embedded).not.toContain('id="copylinkrun"');
    expect(renderViewer(embeddedPayload(), { protocol: "https:" })).toContain('id="copylinkrun"');
    // A generator-supplied canonical URL is a real address, so it survives being embedded.
    const shared = renderViewer({ ...embeddedPayload(), shareUrl: "https://ci.example/report.html" }, { query: "?chrome=none" });
    expect(shared).toContain('id="copylinkrun"');
  });

  // With nothing left to offer, the ⋯ itself goes: an embedded live report links its frames, so
  // both of the index menu's items are gone.
  test("an index menu with no items left renders no menu at all", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [embeddedPayload("/static/run-7/s1.png").sessions[0], { ...embeddedPayload("/static/run-7/s1.png").sessions[0], meta: { title: "Second run", status: "failed" } }],
    }, { protocol: "https:", query: "?chrome=none" });
    expect(out).not.toContain('aria-label="Report options"');
    expect(out).not.toContain('id="exportall"');
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

  // A live push merges into the index stub, and in a chunked document that stub is NOT the whole
  // run: the trace, its screenshots and its side channels are still sitting in an unparsed
  // #tb-session chunk. A push carrying the trace IS what that chunk would have delivered, so
  // marking the run hydrated is right. A partial push is not, and marking it hydrated would make
  // the chunk unreachable for the life of the document.
  test("a partial live push leaves the run's chunk still to hydrate", () => {
    const state = renderViewerState(null, { chunks: chunksOf(html) });
    state.live()!.update(1, { deviceLog: "late poll line" } as unknown as Record<string, unknown>);
    state.openSession(1);
    expect(state.readHtml()).toContain("Tap login"); // trace content only the session chunk carries
  });

  // And the chunk that eventually parses must not roll that push back. It carries the run as it
  // stood when the document was written, so an Object.assign of it over the stub would undo a side
  // channel the push had already delivered — the reader would open the run and watch a device log
  // they were shown go back to an older copy.
  test("hydration keeps the fields a live push delivered before the run was opened", () => {
    const state = renderViewerState(null, { chunks: chunksOf(html) });
    state.live()!.update(1, { deviceLog: "late poll line" } as unknown as Record<string, unknown>);
    state.openSession(1);
    state.clickTab("device");
    expect(state.readHtml()).toContain("late poll line");
    expect(state.readHtml()).not.toContain("Loading run");
  });

  test("a live push carrying the trace hydrates the run without its chunk", () => {
    const state = renderViewerState(null, { chunks: chunksOf(html) });
    const pushed = JSON.parse(chunksOf(html).sessions["1"]);
    pushed.trace = [{ i: 1, label: "Pushed step", objective: true, ok: true }];
    state.live()!.update(1, pushed);
    state.openSession(1);
    expect(state.readHtml()).toContain("Pushed step");
    expect(state.readHtml()).not.toContain("Tap login"); // the chunk did not overwrite the push
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
    const state = renderViewerState(payloadOf(html), { sprites: spritesOf(html), session: 1 });
    expect(state.tlvframeStyle.backgroundImage).toBe("url('data:image/webp;base64,SPRITEBYTES')");
  });

  test("a video run holds its loading shell until the sprite chunk lands, then renders real frames", async () => {
    // The session chunk alone isn't enough: frame URLs resolve once at render, so hydrating
    // before #tb-sprites-<i> parses would paint blank frames that nothing ever re-renders.
    const state = renderViewerState(null, { chunks: chunksOf(html), holdSpriteChunks: [1], session: 1 });
    expect(state.html).toContain("Loading run");
    state.releaseChunks();
    for (let i = 0; i < 100 && state.readHtml().includes("Loading run"); i++) await new Promise((resolve) => setTimeout(resolve, 10));
    expect(state.tlvframeStyle.backgroundImage).toBe("url('data:image/webp;base64,SPRITEBYTES')");
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

describe("chunkJsonWithoutRuntimeAttachments (export sanitizing of an embedded session chunk)", () => {
  const chunk = (attachments: unknown) => JSON.stringify({ meta: { title: "Run" }, attachments });

  test("rewrites only a chunk that actually carries object URLs", () => {
    // Null means "leave the node alone" — a report full of portable values must not pay to have
    // every megabyte chunk parsed and reserialized on export.
    expect(chunkJsonWithoutRuntimeAttachments(chunk({ "a.wav": "data:audio/wav;base64,AAAA" }))).toBeNull();
    expect(chunkJsonWithoutRuntimeAttachments(chunk(null))).toBeNull();
    expect(chunkJsonWithoutRuntimeAttachments("not json, but mentions blob:")).toBeNull();
  });

  test("keeps the portable values and the rest of the chunk", () => {
    const mixed = JSON.parse(chunkJsonWithoutRuntimeAttachments(chunk({ "a.wav": "blob:x", "b.png": "/static/b.png" }))!);
    expect(mixed.attachments).toEqual({ "b.png": "/static/b.png" });
    expect(mixed.meta.title).toBe("Run");
    // Stripping every entry leaves null rather than an empty map, so the viewer's "any attachments?"
    // check reads the same as a session that referenced none.
    expect(JSON.parse(chunkJsonWithoutRuntimeAttachments(chunk({ "a.wav": "blob:x" }))!).attachments).toBeNull();
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
    expect(out).toContain('aria-label="Search"');
    expect(out).not.toContain('id="runcount"');
    expect(out).toContain('data-search="checkout flow passed android pixel demo"');
    // The failure code joins the haystack, so a reader can filter the index to one code.
    expect(out).toContain('data-search="sign-in flow failed ios iphone demo account-state"');
    expect(out).toContain("No runs match these filters.");
    expect(out).toContain('aria-label="Group runs by Status"');
    expect(out).toContain('aria-label="Sort runs by Order"');
    expect(out).not.toContain("<span>Sort</span>");
    expect(out).toContain('aria-pressed="true" data-run-group="status">Status</button>');
    expect(out).toContain('aria-pressed="true" data-run-sort="original">Order</button>');
    expect(out).toContain('<span class="idxsortvalue"><svg class="idxsorticon"');
    expect(out).not.toContain('class="idxsortprefix"');
    expect(out).toContain('data-run-group="owner">Owner</button>');
    expect(out).toContain('data-run-sort="cost">Cost</button>');
    expect(out).not.toContain("data-run-filter");
    expect(out).toContain('data-index-section="failed"');
    expect(out).toContain('data-index-section="passed"');
    expect(out.indexOf('data-session="1"')).toBeLessThan(out.indexOf('data-session="0"'));
    // The filter hides whole entries by setting `hidden` on them, and both kinds of entry are
    // display: grid — so without this rule the "hidden" rows keep painting and the filter silently
    // does nothing.
    expect(core.RUN_REPORT_CSS).toContain(".idxrowline[hidden], .idxattemptline[hidden] { display: none; }");
    // And a retry group whose separator the filter would strand at the top of the list drops it,
    // the same way a plain row and a section entry each already do.
    expect(core.RUN_REPORT_CSS).toContain(".idxretry:first-child, .idxretry.firstmatch { border-top: 0; }");
    // A column the trail never ran on is the one cell with no control in the gutter, so its own
    // padding has to stand in for one — otherwise its label sits left of every cell beside it.
    expect(core.RUN_REPORT_CSS).toContain(".idxcell.missing { display: flex; flex-direction: column; gap: 4px; padding: 9px 14px 9px 26px;");
  });

  test("owner metadata renders as a row subtitle, joins search, and supports Owner grouping", () => {
    const owned = (title: string, status: string, owner?: string) => ({
      ...session(title, status),
      meta: { title, status, ...(owner ? { metadata: { owner } } : {}) },
    });
    const sessions = [owned("Zeta", "passed", "team-b"), owned("Alpha", "passed"), owned("Beta", "failed", "team-a")];
    const grouped = renderViewer({ generatedAt: "now", sessions });
    expect(grouped).toContain('<div class="idxowner">team-b</div>');
    expect(grouped).toContain('data-search="zeta passed team-b"');
    expect(grouped).toContain('data-run-group="owner">Owner</button>');

    const byOwner = renderViewer({ generatedAt: "now", sessions }, { query: "?view=runs&group=owner&sort=name" });
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
    const hostedIndex = renderViewer(payload, { protocol: "https:", query: "?view=runs&sort=name&search=sign-in" });
    expect(hostedIndex).toContain('id="copylink"');
    expect(hostedIndex).toContain('value="sign-in"');
    const hostedDetail = renderViewer({ generatedAt: "now", sessions: [payload.sessions[0]] }, { protocol: "https:" });
    expect(hostedDetail).toContain('id="copylinkrun"');
    const local = renderViewer(payload);
    expect(local).not.toContain('id="copylink"');
    expect(renderViewer({ generatedAt: "now", sessions: [payload.sessions[0]] })).not.toContain('id="copylinkrun"');

    // Clicking copies the current (route-canonicalized) browser URL.
    const copied = renderViewerState(payload, { protocol: "https:", query: "?view=runs&sort=name&search=sign-in", copyLink: true }).copiedText;
    expect(copied).toContain("view=runs");
    expect(copied).toContain("sort=name");
    expect(copied).toContain("search=sign-in");
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

  test("a report with no owners still offers Owner grouping", () => {
    const sessions = [session("A", "passed"), session("B", "failed")];
    const out = renderViewer({ generatedAt: "now", sessions });
    expect(out).toContain('data-run-group="owner">Owner</button>');
    const byOwner = renderViewer({ generatedAt: "now", sessions }, { query: "?view=runs&group=owner" });
    expect(byOwner).toContain('<div class="idxsectionhead">No owner <span class="idxsectioncount">2</span>');
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
    expect(out.match(/class="nm[^"]*"[^>]*>login\/login</g)).toHaveLength(1);
    expect(out).toContain("<div class=\"k\">Platforms</div><div class=\"v\">android, ios</div>");
    // A cell per platform, each opening its own run; the iOS failure gives the row's cell a failed
    // treatment and sections the whole row under Failed (worst outcome wins).
    expect(cellOpens(out, "idxcell passed", 0)).toBe(true);
    expect(cellOpens(out, "idxcell failed", 1)).toBe(true);
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
    expect(out).toContain('aria-pressed="true" data-run-sort="cost">Cost</button>');
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
    expect(cellOpens(collapsed, "idxcell passed retried", 1)).toBe(true);
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
    expect(cellOpens(out, "idxcell failed", 0)).toBe(true);
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
    expect(cellOpens(out, "idxcell failed retried", 0)).toBe(true);
    expect(out).toContain('data-index-section="failed"');
    expect(out).not.toContain('data-index-section="passed"');
    // Both runs stay in the cell's history, in time order.
    expect(out).toContain('aria-label="Show 2 ios attempts"');
  });

  test("owner metadata composes with matrix rows: subtitle on the row, Owner grouping sections matrix entries", () => {
    const on = (platform: string) => ({
      ...session("Checkout", "passed"),
      meta: { title: "Checkout", trailId: "checkout", target: "demo", status: "passed", platform, metadata: { owner: "team-a" } },
    });
    const grouped = renderViewer({ generatedAt: "now", sessions: [on("android"), on("ios")] });
    expect(grouped).toContain('<div class="idxowner">team-a</div>');

    const byOwner = renderViewer({ generatedAt: "now", sessions: [on("android"), on("ios")] }, { query: "?view=runs&group=owner&sort=name" });
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

  test("a single-device report keeps flat per-run rows without device cells", () => {
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
    expect(out).not.toContain(">Device classifiers<");
    // These payloads carry no classifier at all, so there is nothing to name.
    expect(out).not.toContain(">Device classifier<");
    expect(out).not.toContain('data-run-sort="platform"');
  });

  test("a single-device report names the device it ran, not just the platform family", () => {
    // `ios` alone doesn't say whether this was an iPhone or an iPad, so the one device the report
    // covers is named in the header strip the same way a multi-device report names all of them.
    const on = (title: string, status: string) => ({
      ...session(title, status),
      meta: { title, status, trailId: title, target: "retail", platform: "ios", deviceClassifier: "ios-iphone", device: "5B1E2A9F-UDID", appId: "com.example.pos" },
    });
    const out = renderViewer({ generatedAt: "now", sessions: [on("A", "passed"), on("B", "failed")] });
    expect(out).toContain('<div class="k">Device classifier</div><div class="v">ios-iphone</div>');
    expect(out).not.toContain(">Device classifiers<");
    // It sits with the other device context, after Platform and before the bundle id.
    expect(out.indexOf(">Platform<")).toBeLessThan(out.indexOf(">Device classifier<"));
    expect(out.indexOf(">Device classifier<")).toBeLessThan(out.indexOf(">Bundle / package ID<"));
  });

  test("N devices on ONE platform still get a cell each, named by device classifier", () => {
    // A six-device estate, four of them Android. Nothing here is mixed-platform, so a
    // platform-count gate would have rendered these as a flat run list with no way to compare a
    // trail across devices.
    const on = (trailId: string, status: string, deviceClassifier: string) => ({
      ...session(trailId, status),
      meta: { title: trailId, status, trailId, target: "retail", platform: "android", deviceClassifier, device: "emulator-5554" },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        on("checkout/pay", "passed", "android-phone"),
        on("checkout/pay", "passed", "android-tablet"),
        on("checkout/pay", "failed", "android-kiosk"),
        on("checkout/pay", "passed", "android-handheld"),
      ],
    });
    // One row, four cells — and each column is titled with the specific device classifier verbatim, not a
    // `platform · classifier-tail` composition, because the key already names its platform.
    expect(out.match(/class="nm[^"]*"[^>]*>checkout\/pay</g)).toHaveLength(1);
    expect(out).toContain('<span class="pk">android-phone</span>');
    expect(out).toContain('<span class="pk">android-tablet</span>');
    expect(out).toContain('<span class="pk">android-kiosk</span>');
    expect(out).toContain('<span class="pk">android-handheld</span>');
    expect(out).not.toContain('<span class="pk">android · ');
    // The header names every device the report covers, and the T2 failure sections the whole row.
    expect(out).toContain('<div class="k">Device classifiers</div><div class="v">android-handheld, android-kiosk, android-phone, android-tablet</div>');
    expect(cellOpens(out, "idxcell failed", 2)).toBe(true);
    expect(out).toContain('data-index-section="failed"');
    // A shared adb serial across four device classes is NOT retry history.
    expect(out).not.toContain("data-cell-toggle");
  });

  test("the specific classifier beats the classifier tail as the column identity", () => {
    // Two devices whose classifier tails collide (both `tablet`) but whose keys don't. Keying
    // columns on the tail would fold them into one cell and report only the worse of the two.
    const on = (status: string, deviceClassifier: string) => ({
      ...session("Checkout", status),
      meta: { title: "Checkout", status, trailId: "checkout", target: "demo", platform: "android", deviceClassifier, deviceType: "tablet" },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [on("failed", "android-tablet"), on("passed", "android-kiosk")],
    });
    expect(out).toContain('<span class="pk">android-tablet</span>');
    expect(out).toContain('<span class="pk">android-kiosk</span>');
    expect(out.match(/idxcell failed/g)).toHaveLength(1);
    expect(out.match(/idxcell passed/g)).toHaveLength(1);
    expect(out).not.toContain("data-cell-toggle");
  });

  // A run-index document (the `generate-run-index` command): one stub per result row, carrying no
  // trace/llm payload at all, marked `meta.linkOut` and usually naming its own full report via
  // meta.reportUrl. It exists because a CI build's embedded report runs to hundreds of megabytes
  // and is silently dropped by artifact caps — the index is small enough to always publish, and
  // each cell navigates to the run.
  describe("link-out index stubs (meta.linkOut)", () => {
    const stub = (deviceClassifier: string, status: string, extra: Record<string, unknown> = {}) => ({
      meta: {
        title: "checkout/pay",
        status,
        trailId: "checkout/pay",
        platform: deviceClassifier.split("-")[0],
        deviceClassifier,
        duration: "35.4s",
        linkOut: true,
        reportUrl: `https://cdn.example/viewer/index.html?zip=https%3A%2F%2Fcdn.example%2Fruns%2F${deviceClassifier}.zip`,
        // The payload builder derives these two from the trace, so a real index document carries
        // them as 0 on every stub. Reproduced here because the viewer must ignore both.
        steps: 0,
        ...extra,
      },
      trace: [],
      llm: [],
      shots: {},
      toolCallCount: 0,
      recordingYaml: null,
    });

    test("each matrix cell links out to its own run's report instead of opening in-document", () => {
      const out = renderViewer({ generatedAt: "now", sessions: [stub("android-phone", "passed"), stub("ios-iphone", "failed")] });
      // One row, a cell per classifier — the same matrix an embedded multi-device report renders.
      expect(out.match(/<div class="nm">checkout\/pay<\/div>/g)).toHaveLength(1);
      expect(out).toContain('<span class="pk">android-phone</span>');
      expect(out).toContain('<span class="pk">ios-iphone</span>');
      // The open control is an anchor to that run's report, opened in a new tab.
      expect(out).toContain('<a class="idxcellopen" href="https://cdn.example/viewer/index.html?zip=https%3A%2F%2Fcdn.example%2Fruns%2Fandroid-phone.zip" target="_blank" rel="noopener noreferrer"');
      expect(out).toContain('<a class="idxcellopen" href="https://cdn.example/viewer/index.html?zip=https%3A%2F%2Fcdn.example%2Fruns%2Fios-iphone.zip" target="_blank" rel="noopener noreferrer"');
      // No in-document open path: a data-session click would try to hydrate a payload that isn't here.
      expect(out).not.toContain("idxcellopen\" type=\"button\"");
      expect(out).not.toContain("data-session");
      // The cell still reads its outcome and duration off the stub.
      expect(out).toContain('<div class="idxcell failed">');
      expect(out).toContain('<span class="pvtxt">35.4s</span>');
    });

    test("a single-device index links out from its flat rows too", () => {
      // A lone run normally auto-advances to its detail view; a lone STUB must not, because that
      // detail is empty by construction — the reader would land on a run with no steps and no link.
      const out = renderViewer({ generatedAt: "now", sessions: [stub("android-phone", "passed")] });
      expect(out).toContain('<a class="idxrow" href="https://cdn.example/viewer/index.html?zip=https%3A%2F%2Fcdn.example%2Fruns%2Fandroid-phone.zip" target="_blank" rel="noopener noreferrer"');
      expect(out).not.toContain("data-session");
    });

    test("retry attempts each link to their own run", () => {
      const first = stub("android-phone", "failed", { ranAt: "2026-08-24 01:00:00", reportUrl: "https://cdn.example/viewer/index.html?zip=attempt1" });
      const second = stub("android-phone", "passed", { ranAt: "2026-08-24 02:00:00", reportUrl: "https://cdn.example/viewer/index.html?zip=attempt2" });
      const sessions = [first, second, stub("ios-iphone", "passed")];
      const out = renderViewer({ generatedAt: "now", sessions });
      // The chevron expands the history in-document; the attempt rows themselves navigate out.
      const expanded = renderViewer({ generatedAt: "now", sessions }, { toggleCell: "trail:checkout%2Fpay::android:android-phone" });
      expect(out).toContain("data-cell-toggle");
      expect(expanded).toContain('<a class="idxattemptrow" href="https://cdn.example/viewer/index.html?zip=attempt1"');
      expect(expanded).toContain('<a class="idxattemptrow" href="https://cdn.example/viewer/index.html?zip=attempt2"');
    });

    test("a non-http(s) reportUrl is refused rather than rendered as a link", () => {
      // safeHref parity with shareUrl: a crafted results row must not put `javascript:` in an href.
      const out = renderViewer({ generatedAt: "now", sessions: [stub("android-phone", "passed", { reportUrl: "javascript:alert(1)" }), stub("ios-iphone", "passed")] });
      expect(out).not.toContain("javascript:alert(1)");
      // It stays a stub (isLinkOut is true), so the control goes inert rather than falling back to
      // an in-document open that would hydrate a payload the document doesn't carry.
      expect(out).toContain('<span class="idxcellopen" ');
      expect(out).not.toContain("data-session");
    });

    test("absent LLM figures read as unknown, never as a confident zero", () => {
      const withFigures = stub("android-phone", "passed", { llmCallCount: 12, llmCostUsd: 0.42 });
      const out = renderViewer({ generatedAt: "now", sessions: [withFigures, stub("ios-iphone", "passed")] });
      // The run that reported figures shows them; the one that didn't shows nothing at all.
      expect(out).toContain('<span class="pcounts">12 LLM</span>');
      expect(out).not.toContain('<span class="pcounts">0 LLM</span>');
      // Tool counts have no stub equivalent, and the payload builder derives `steps: 0` for a
      // traceless session — honouring that would report every run as having called no tools.
      expect(out).not.toContain("0 tools");
      // One unknown cost makes the row total and the header total unknown — a partial sum would
      // understate the run, and $0.00 would deny it happened.
      expect(out).toContain('<div class="idxstats">—</div>');
      expect(out).toContain('<span class="k">Total LLM cost</span><span class="v">—</span>');
      expect(out).toContain('<span class="k">Total tokens</span><span class="v">—</span>');
    });

    test("a stub with nowhere to link is still a stub, not an embedded run", () => {
      // The generator omits reportUrl whenever a row has no session archive, or whenever the index
      // was built without a viewer URL. Reading that absence as "ordinary embedded run" is what
      // `meta.linkOut` exists to prevent: the cell would go back to a data-session open on an empty
      // payload and start reporting the missing evidence as 0 tools and 0 LLM.
      const linkless = stub("ios-iphone", "passed", { reportUrl: undefined });
      const out = renderViewer({ generatedAt: "now", sessions: [stub("android-phone", "passed"), linkless] });
      expect(out).toContain('<span class="idxcellopen" aria-disabled="true"');
      expect(out).not.toContain("data-session");
      expect(out).not.toContain("0 tools");
      expect(out).not.toContain('<span class="pcounts">0 LLM</span>');
      // It still reads its outcome and duration — an unlinked cell reports, it just can't navigate.
      expect(out).toContain('<span class="pk">ios-iphone</span>');
      expect(out).toContain('<span class="pvtxt">35.4s</span>');
    });

    test("a lone linkless stub stays on the index instead of opening an empty run", () => {
      // A single run normally auto-advances to its detail view. That detail is empty by
      // construction here, so the reader would land on a run with no steps and no way back to the
      // one thing the index was for.
      const out = renderViewer({ generatedAt: "now", sessions: [stub("android-phone", "passed", { reportUrl: undefined })] });
      expect(out).toContain('<span class="idxrow" aria-disabled="true"');
      expect(out).not.toContain("data-session");
    });

    test("an inert control describes the run without offering to open it", () => {
      // aria-label parity with what the element actually is: a <span> that says "Open …" tells a
      // screen reader an action is available where none is.
      const out = renderViewer({ generatedAt: "now", sessions: [stub("android-phone", "passed"), stub("ios-iphone", "failed", { reportUrl: undefined })] });
      expect(out).toContain('aria-label="Open latest android-phone run, passed"');
      expect(out).toContain('aria-label="Latest ios-iphone run, failed (no report to open)"');
    });

    test("a document written before meta.linkOut existed still reads as an index", () => {
      const legacy = (deviceClassifier: string) => {
        const s = stub(deviceClassifier, "passed");
        return { ...s, meta: { ...s.meta, linkOut: undefined } };
      };
      const out = renderViewer({ generatedAt: "now", sessions: [legacy("android-phone"), legacy("ios-iphone")] });
      expect(out).toContain('<a class="idxcellopen" href="https://cdn.example/viewer/index.html?zip=https%3A%2F%2Fcdn.example%2Fruns%2Fandroid-phone.zip"');
      expect(out).not.toContain("data-session");
    });
  });

  describe("skipped trails (meta.status skipped)", () => {
    // A skip is honored before a session ever opens, so these rows are the runner reporting what it
    // declined to run. They carry a reason and nothing else: no duration, no tools, no LLM calls,
    // and nothing to open.
    const run = (trailId: string, deviceClassifier: string, status: string, extra: Record<string, unknown> = {}) => ({
      meta: {
        title: trailId,
        status,
        trailId,
        platform: deviceClassifier.split("-")[0],
        deviceClassifier,
        duration: "35.4s",
        linkOut: true,
        reportUrl: `https://cdn.example/viewer/index.html?zip=${deviceClassifier}.zip`,
        steps: 0,
        ...extra,
      },
      trace: [],
      llm: [],
      shots: {},
      toolCallCount: 0,
      recordingYaml: null,
    });
    const skipped = (trailId: string, deviceClassifier: string, reason: string) =>
      run(trailId, deviceClassifier, "skipped", { skipReason: reason, duration: "", reportUrl: undefined });
    const mixed = (reason = "backend outage, see #2194") => [
      run("checkout/pay", "android-phone", "passed"),
      run("checkout/pay", "ios-iphone", "passed"),
      skipped("checkout/refund", "android-phone", reason),
      skipped("checkout/refund", "ios-iphone", reason),
    ];

    test("a trail nobody ran gets its own section instead of going missing", () => {
      const out = renderViewer({ generatedAt: "now", sessions: mixed() });
      expect(out).toContain('<section class="idxsection" data-index-section="skipped"><div class="idxsectionhead skipped">Skipped <span class="idxsectioncount">1</span></div>');
      // Sectioned after the verdicts, so a skip can't be read as one of them.
      expect(out.indexOf('data-index-section="passed"')).toBeLessThan(out.indexOf('data-index-section="skipped"'));
      expect(out).toContain('<div class="idxcell skipped"');
    });

    test("the row states why, because the reason is all a skipped row has to say", () => {
      const out = renderViewer({ generatedAt: "now", sessions: mixed() });
      expect(out).toContain('<div class="idxowner">backend outage, see #2194</div>');
      // One subtitle, not one per device: both cells state the same reason.
      expect(out.match(/backend outage, see #2194<\/div>/g)).toHaveLength(1);
    });

    test("a trail skipped for different reasons on different devices states both", () => {
      // Showing only the latest cell's reason would attribute it to a device it was never written for.
      const out = renderViewer({
        generatedAt: "now",
        sessions: [
          run("checkout/pay", "android-phone", "passed"),
          run("checkout/pay", "ios-iphone", "passed"),
          skipped("checkout/refund", "android-phone", "no tablet fixture"),
          skipped("checkout/refund", "ios-iphone", "backend outage"),
        ],
      });
      expect(out).toMatch(/<div class="idxowner">(no tablet fixture · backend outage|backend outage · no tablet fixture)<\/div>/);
    });

    test("a skip annotates the tally rather than joining the pass or fail count", () => {
      const out = renderViewer({ generatedAt: "now", sessions: mixed() });
      expect(out).toContain('<span class="stat fail"><strong>0</strong> failed</span>');
      expect(out).toContain('<span class="stat pass"><strong>1</strong> passed</span>');
      expect(out).toContain('<span class="stat skip"><strong>1</strong> skipped</span>');
    });

    test("a report with nothing skipped says nothing about skips", () => {
      const out = renderViewer({
        generatedAt: "now",
        sessions: [run("checkout/pay", "android-phone", "passed"), run("checkout/pay", "ios-iphone", "passed")],
      });
      expect(out).not.toContain('class="stat skip"');
      expect(out).not.toContain('data-index-section="skipped"');
    });

    test("a skipped cell has nothing to open and says so", () => {
      const out = renderViewer({ generatedAt: "now", sessions: mixed() });
      expect(out).toContain('<span class="idxcellopen" aria-disabled="true"');
      expect(out).toContain('aria-label="Latest android-phone run, skipped: backend outage, see #2194 (no report to open)"');
      // The reason also rides the cell tooltip, which is the only place it fits beside the column label.
      expect(out).toContain('<div class="idxcell skipped" title="backend outage, see #2194">');
    });

    test("a single-device report carries the reason on the flat row", () => {
      // No matrix here, so the subtitle is the only place the reason can land.
      const out = renderViewer({
        generatedAt: "now",
        sessions: [run("checkout/pay", "android-phone", "passed"), skipped("checkout/refund", "android-phone", "backend outage, see #2194")],
      });
      expect(out).toContain('<div class="idxowner">backend outage, see #2194</div>');
      expect(out).toContain('<span class="idxstatusdot skipped"');
      expect(out).toContain('<span class="idxrow" aria-disabled="true"');
    });

    test("a skip leaves the duration, token and cost totals alone", () => {
      // Those three footer totals report "—" as soon as one run's figure is unknown, and a skipped
      // row has no figures at all: no duration, no calls, no cost. Counted as a run, a single
      // held-back trail would blank all three for a report whose other runs measured fine.
      const ran = (trailId: string, duration: string, cost: number) => ({
        meta: { title: trailId, status: "passed", trailId, platform: "android", deviceClassifier: "android-phone", duration, steps: 1 },
        trace: [],
        llm: [{ inputTokens: 100, outputTokens: 20, totalCost: cost }],
        shots: {},
        recordingYaml: null,
      });
      const out = renderViewer({
        generatedAt: "now",
        sessions: [ran("checkout/pay", "42.3s", 0.28), ran("checkout/ship", "51.8s", 0.02), skipped("checkout/refund", "android-phone", "backend outage")],
      });
      expect(out).toContain('<span class="k">Total duration</span><span class="v">1m 34s</span>');
      expect(out).toContain('<span class="k">Total tokens</span><span class="v">240</span>');
      expect(out).toContain('<span class="k">Total LLM cost</span><span class="v">$0.30</span>');
    });

    test("a skip does not strip the report's shared app and build identity", () => {
      // The header shows a value only when EVERY session agrees on it, and a skip stub agrees with
      // nothing: it carries no appId, appVersion, buildNumber or commitSha, because there was no
      // run to read them from. Counted, one held-back trail blanks all four for the whole report.
      const provenance = { appId: "com.example.shop", appVersion: "5.58.0 (67500009)", buildNumber: "4471", commitSha: "abc1234" };
      const out = renderViewer({
        generatedAt: "now",
        sessions: [
          run("checkout/pay", "android-phone", "passed", provenance),
          run("checkout/ship", "android-phone", "passed", provenance),
          skipped("checkout/refund", "android-phone", "backend outage"),
        ],
      });
      // Asserted on the header's own markup, not on the strings anywhere in the document: every
      // session's meta is embedded in the payload, so a bare `toContain` would pass on a header
      // that rendered nothing at all.
      expect(out).toContain('<div class="k">Bundle / package ID</div><div class="v">com.example.shop</div>');
      expect(out).toContain('<div class="k">App version</div><div class="v">5.58.0 (67500009)</div>');
      expect(out).toContain('<div class="k">Build</div><div class="v">4471</div>');
    });

    test("a skipped attempt does not make its whole row's cost unknown", () => {
      // A trail skipped once and run later folds into one row with two attempts. The row cost is
      // null-if-any-unknown, so counting the skip's absent cost turns a real figure into "—" and
      // sorts the row last under Cost.
      const attempt = (status: string, ranAt: string, extra: Record<string, unknown> = {}) => ({
        meta: { title: "checkout/refund", status, trailId: "checkout/refund", platform: "android", deviceClassifier: "android-phone", duration: "12.0s", ranAt, steps: 1, ...extra },
        trace: [],
        llm: status === "skipped" ? [] : [{ inputTokens: 100, outputTokens: 20, totalCost: 0.42 }],
        shots: {},
        recordingYaml: null,
      });
      const out = renderViewer({
        generatedAt: "now",
        sessions: [
          attempt("skipped", "2026-08-25 09:00:00", { skipReason: "backend outage", duration: "", linkOut: true }),
          attempt("passed", "2026-08-26 09:00:00"),
        ],
      });
      // The row's stats line, not the raw cost anywhere in the payload: `sumRunCosts` renders "—"
      // when any input is unknown, and that is what the skip's absent cost would produce here.
      expect(out).toContain('<div class="idxstats">$0.42</div>');
    });

    test("a skipped attempt buried under a later run still says why", () => {
      // The row subtitle only carries the LATEST attempt's reason. Without a reason on the attempt
      // row itself, a trail skipped Monday and run Tuesday shows a skipped dot in its history with
      // nothing anywhere in the report explaining it.
      const attempt = (status: string, ranAt: string, extra: Record<string, unknown> = {}) => ({
        meta: { title: "checkout/refund", status, trailId: "checkout/refund", platform: "android", deviceClassifier: "android-phone", duration: "12.0s", ranAt, steps: 1, ...extra },
        trace: [],
        llm: [],
        shots: {},
        recordingYaml: null,
      });
      const out = renderViewer({
        generatedAt: "now",
        sessions: [
          attempt("skipped", "2026-08-25 09:00:00", { skipReason: "no tablet fixture", duration: "", linkOut: true }),
          attempt("passed", "2026-08-26 09:00:00"),
        ],
      });
      expect(out).toContain('<div class="idxattemptskip" title="no tablet fixture">no tablet fixture</div>');
    });

    test("a skipped run is findable by its reason", () => {
      // Search text is how a reader filters a long index; the reason is the row's only distinguishing
      // content beyond its title.
      const out = renderViewer({ generatedAt: "now", sessions: mixed() });
      expect(out).toMatch(/data-search="[^"]*backend outage, see #2194/);
    });
  });

  test("payloads without a device classifier keep the platform-composed column labels", () => {
    // Back-compat: a report generated before `deviceClassifier` existed carries only the classifier tail,
    // and its columns must still read `platform · tail` rather than losing their platform.
    const on = (platform: string, deviceType: string) => ({
      ...session("Checkout", "passed"),
      meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "demo", platform, deviceType },
    });
    const out = renderViewer({
      generatedAt: "now",
      sessions: [on("ios", "iphone"), on("ios", "ipad"), on("android", "phone")],
    });
    expect(out).toContain('<span class="pk">ios · iphone</span>');
    expect(out).toContain('<span class="pk">ios · ipad</span>');
    expect(out).toContain('<span class="pk">android</span>');
  });

  test("a seven-device fleet widens the index instead of wrapping at the stock width", () => {
    // A row's cells wrap, so a wide fleet stays readable either way — but the stock 1120px shell
    // only fits four columns, so a seven-device report would wrap on a monitor with room to spare.
    const fleet = ["android-phone", "android-tablet", "android-kiosk", "android-handheld", "android-console", "ios-iphone", "ios-ipad"];
    const out = renderViewer({
      generatedAt: "now",
      sessions: fleet.map((deviceClassifier) => ({
        ...session("Checkout", "passed"),
        meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "retail", platform: deviceClassifier.split("-")[0], deviceClassifier },
      })),
    });
    // Seven columns, each still titled with its own classifier — nothing dropped or fused.
    fleet.forEach((deviceClassifier) => expect(out).toContain(`<span class="pk">${deviceClassifier}</span>`));
    expect(out.match(/class="idxcell passed"/g)).toHaveLength(7);
    // Widened to hold all seven on one line: borders + row padding + name column + 7 cells, sized to
    // the longest heading. Every `.indexshell` gets it, so the header strip stays aligned.
    const cell = Math.round(96 + "android-handheld".length * 7.2);
    const wide = 2 + 32 + 220 + 16 + 7 * cell + 6 * 8;
    expect(wide).toBeGreaterThan(1120);
    expect(out.match(new RegExp(`--idxcell-w: ${cell}px; --content-wide: ${wide}px`, "g"))).toHaveLength(3);
  });

  test("an identity-less session gets a column but stays out of the device list", () => {
    // A run carrying neither platform nor device still needs to be visible, so it keeps its
    // catch-all `other` column — but `other` is not a device and must not be listed in the header
    // as one of the classifiers the report covers.
    const out = renderViewer({
      generatedAt: "now",
      sessions: [
        { ...session("Checkout", "passed"), meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "retail", platform: "android", deviceClassifier: "android-phone" } },
        { ...session("Checkout", "passed"), meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "retail", platform: "ios", deviceClassifier: "ios-iphone" } },
        { ...session("Checkout", "failed"), meta: { title: "Checkout", status: "failed", trailId: "checkout", target: "retail" } },
      ],
    });
    expect(out).toContain('<span class="pk">other</span>');
    expect(out).toContain('<div class="k">Device classifiers</div><div class="v">android-phone, ios-iphone</div>');
  });

  test("a two-device report keeps the stock index width", () => {
    // The override only ever grows the shell; a narrow fleet must not shrink or widen it.
    const out = renderViewer({
      generatedAt: "now",
      sessions: ["android-phone", "ios-iphone"].map((deviceClassifier) => ({
        ...session("Checkout", "passed"),
        meta: { title: "Checkout", status: "passed", trailId: "checkout", target: "retail", platform: deviceClassifier.split("-")[0], deviceClassifier },
      })),
    });
    expect(out).toContain("--content-wide: 1120px");
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
    expect(out).toContain('<span class="idxstatus" role="img" aria-label="self-healed" title="self-healed"><span class="idxstatusdot selfheal" aria-hidden="true"></span></span>');
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

  test("device classes sharing one worker stay separate retry histories", () => {
    const attempt = (deviceType: string, status: string, minute: number) => ({
      ...session("Checkout", status),
      meta: {
        title: "Checkout", trailId: "checkout", target: "demo", status,
        platform: "android", deviceType, device: "shared-worker", ranAt: `2026-07-17T10:${String(minute).padStart(2, "0")}:00Z`,
      },
    });
    const sessions = ["phone", "tablet", "kiosk · gen2", "kiosk · gen3"].flatMap((deviceType, i) => [
      attempt(deviceType, "failed", i * 2),
      attempt(deviceType, "passed", i * 2 + 1),
    ]);
    const out = renderViewer({ generatedAt: "now", sessions });

    // Four device classes on one platform → one trail row with a cell each. The shared adb serial
    // must not fuse them: four two-attempt histories, never one eight-attempt blob.
    expect(out.match(/class="nm[^"]*"[^>]*>Checkout</g)).toHaveLength(1);
    expect(out.match(/class="idxcell [a-z]+ retried"/g)).toHaveLength(4);
    expect(out.match(/<span class="idxcellcount" aria-hidden="true">2<\/span>/g)).toHaveLength(4);
    expect(out).not.toContain('<span class="idxcellcount" aria-hidden="true">8</span>');
    ["phone", "tablet", "kiosk · gen2", "kiosk · gen3"].forEach((deviceType) => {
      expect(out).toContain(`<span class="pk">android · ${deviceType}</span>`);
      expect(out).toContain(`aria-label="Show 2 android · ${deviceType} attempts"`);
    });
  });

  test("flat rows never expose raw device instance identifiers as device classes", () => {
    const attempt = (device: string) => ({
      ...session("Checkout", "passed"),
      meta: { title: "Checkout", trailId: `checkout-${device}`, status: "passed", platform: "ios", device },
    });
    const out = renderViewer({ generatedAt: "now", sessions: [attempt("SIMULATOR-UDID-A"), attempt("SIMULATOR-UDID-B")] });

    expect(out).not.toContain('class="idxowner"');
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
        { ...session("Checkout flow", "passed"), llm: [{ inputTokens: 100, outputTokens: 20, totalCost: 0.28349 }], meta: {
          title: "Checkout flow", status: "passed", ...shared, duration: "42.3s", steps: 12,
        } },
        { ...session("Sign-in flow", "failed"), llm: [{ inputTokens: 180, outputTokens: 30, totalCost: 0.017822 }], meta: { title: "Sign-in flow", status: "failed", ...shared, duration: "51.8s", steps: 9 } },
      ],
    });
    expect(out).not.toContain(">Device type<");
    expect(out).not.toContain(">Device<");
    expect(out).toContain("Bundle / package ID");
    expect(out).toContain('<div class="k">Build</div><div class="v"><a class="indexmetalink"');
    expect(out).toContain('>10792 <span aria-hidden="true">↗</span></a>');
    expect(out).toContain('<div class="k">Commit</div><div class="v"><a class="indexmetalink"');
    expect(out).toContain('>01234567 <span aria-hidden="true">↗</span></a>');
    expect(out).not.toContain('class="quietlink mono"');
    expect(out).not.toContain('<div class="k">Date</div>');
    expect(out).toContain('<span class="detailfooteritem indexrundate"><span class="k">Run on</span><span class="v">2026-07-16</span></span>');
    expect(out.indexOf(">Target<")).toBeLessThan(out.indexOf(">App version<"));
    expect(out.indexOf(">App version<")).toBeLessThan(out.indexOf(">Platform<"));
    expect(out.match(/>Platform</g)).toHaveLength(1); // shared context is rendered once in the header
    expect(out.match(/class="idxfact"><div class="k">Tools/g)).toHaveLength(2);
    expect(out.match(/class="idxfact"><div class="k">LLM/g)).toHaveLength(2);
    // Steps + cost live in the row subtitle, under the title.
    expect(out).toContain('<div class="idxstats">1 step · $0.28</div>');
    expect(out).toContain('<div class="idxstats">1 step · $0.02</div>');
    expect(out).toContain("42.3s");
    expect(out).toContain("51.8s");
    expect(out).toContain('<span class="k">Total duration</span><span class="v">1m 34s</span>');
    expect(out).toContain('<span class="k">Total tokens</span><span class="v">330</span>');
    expect(out).toContain('<span class="k">Total LLM cost</span><span class="v">$0.30</span>');
    expect(out.match(/class="idxstatus"/g)).toHaveLength(2);
    expect(out).not.toContain('data-export-run');
    expect(out).toContain('<details class="exportmenu" data-export-menu>');
    expect(out).toContain('aria-label="Report options"');
    expect(out).toContain('<button class="exportmenuitem" type="button" id="exportall">Download report</button>');
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
    expect(out).toContain('class="backicon"');
    expect(out).toContain('stroke-width="1.75"');
    expect(core.RUN_REPORT_CSS).toContain("@keyframes reportPageForward");
    expect(core.RUN_REPORT_CSS).toContain("@keyframes reportPageBack");
    expect(core.RUN_REPORT_CSS).toContain("prefers-reduced-motion: reduce");
  });

  test("chat history and UI inspection push on with the run-detail transition", () => {
    expect(core.RUN_REPORT_CSS).toContain(".txoverlay, .inspector");
    expect(core.RUN_REPORT_CSS).toContain("background: var(--bg); animation: reportPageForward 220ms");
    expect(core.RUN_REPORT_CSS).toContain(".txpanel { display: flex; flex-direction: column; width: 100%; height: 100%");
    expect(core.RUN_REPORT_CSS).toContain(".insppanel { display: flex; flex-direction: column; width: 100%; height: 100%");
    expect(core.RUN_REPORT_CSS).toContain(".inspbody { display: grid; grid-template-columns: minmax(220px, 34%) minmax(0, 1fr); gap: var(--space-4); width: 100%; padding: var(--page-y) var(--page-x)");
    expect(core.RUN_REPORT_CSS).toContain(".inspraw { flex: 1; min-height: 0; max-height: none; overflow: auto");
    expect(core.RUN_REPORT_VIEWER.toString()).toContain('class="back" data-tx-close aria-label="Back to report"');
    expect(core.RUN_REPORT_VIEWER.toString()).toContain('class="back" type="button" data-inspclose aria-label="Back to report"');
    expect(core.RUN_REPORT_VIEWER.toString()).not.toContain('aria-label="Close transcript"');
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
    const selected = slim[2].i;
    const next = slim[1].i;
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

  test("a filtered deep link moves the roving timeline focus to a visible tool row", () => {
    const payload = {
      generatedAt: "now",
      sessions: [{
        meta: { title: "Filtered timeline", status: "passed" },
        trace: [
          { i: 0, label: "Check the screen", objective: true, ok: true, children: [] },
          { i: 1, label: "tapOnElement", tool: "tap", objective: false, terminal: false, ok: true, children: [] },
          { i: 2, label: "assertVisible", tool: "assertVisible", objective: false, terminal: false, ok: true, children: [] },
        ],
        llm: [], shots: {}, recordingYaml: null,
      }],
    };
    const state = renderViewerState(payload, { query: "?run=0&tab=timeline&step=1&types=assert" });

    expect(state.html).not.toContain('data-step="1"');
    expect(state.html).toContain('data-step="2" role="button" tabindex="0"');
    expect(new URL(state.route, "https://report.example").searchParams.get("step")).toBe("2");
  });

  test("non-tap tools use a wrench while taps keep the target icon", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Tool icons", status: "passed" },
        trace: [
          { i: 0, label: "Enter information", objective: true, ok: true, children: [] },
          { i: 1, label: "EnterText", tool: "text: example", objective: false, terminal: false, ok: true, children: [] },
          { i: 2, label: "tapOnElement", tool: "text: Continue", objective: false, terminal: false, ok: true, children: [] },
        ],
        llm: [], shots: {}, recordingYaml: null,
      }],
    });

    expect(out).toContain('<span class="ic tool"');
    expect(out).toContain('<span class="ic tool" aria-hidden="true"><svg');
    expect(out).toContain('<span class="ic tap" aria-hidden="true">◉</span>');
    expect(core.RUN_REPORT_CSS).toContain('.step .ic.tool { color: var(--txt); }');
  });

  test("the timeline scrubber owns previous, play, and next instead of the screenshot viewer", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).toContain('id="tlplay"');
    expect(out).toContain('aria-label="Play timeline"');
    expect(out).toContain('aria-label="Previous tool call"');
    expect(out).toContain('aria-label="Next tool call"');
    expect(out).not.toContain("Current frame");
    expect(out).not.toContain("Step 1 /");
    expect(out).toContain('class="deviceplayer');
    expect(out).not.toContain('<div class="detail">');
    expect(out).not.toContain('class="count mono"');
    expect(out).not.toContain('class="pvctl"');
    expect(out.indexOf('class="scrubtransport"')).toBeGreaterThan(out.indexOf('class="scrubtrack"'));
    expect(core.RUN_REPORT_CSS).toContain(".scrubtransport { flex-shrink: 0; display: inline-flex;");
    expect(core.RUN_REPORT_CSS).toContain(".scrubtransport button.timelinecontrol { width: 32px; height: 30px;");
    expect(core.RUN_REPORT_CSS).toContain("border: 2px solid var(--player-line)");
    expect(core.RUN_REPORT_CSS).toContain("border-left: 1px solid var(--line2)");
    expect(core.RUN_REPORT_CSS).toContain(".scrubtransport button.timelinecontrol:not(:disabled):hover");
    expect(out).toContain('id="prev" aria-label="Previous tool call"');
    expect(out).toContain('id="next" aria-label="Next tool call"');
    expect(out).toContain('class="transporticon direction" aria-hidden="true"></span>');
    expect(out).toContain('<svg class="transporticon playicon"');
    expect(core.RUN_REPORT_CSS).toContain(".transporticon { width: 18px; height: 18px;");
    expect(core.RUN_REPORT_CSS).toContain("border-bottom: 1.75px solid currentColor; border-left: 1.75px solid currentColor;");
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
    const first = data.sessions[0].trace[1].i;
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
    expect(core.RUN_REPORT_CSS).toContain('.scrubtrack:focus-visible { outline: 1px dashed var(--sub2);');
  });

  test("the scrubber distinguishes neutral step landmarks from actual LLM calls", () => {
    const trace = [
      { i: 1, label: "Complete checkout", tool: "agent step", objective: true, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "LLM Request", tool: "llm · model", objective: false, ok: true, ts: 2, ms: 100, llm: 0 },
      { i: 3, label: "tapOnElement", tool: "text: Pay", objective: false, ok: true, ts: 3, ms: 100 },
    ];
    const llm = [{ model: "model", inputTokens: 10, outputTokens: 5, cacheReadTokens: 0, totalCost: 0.001, promptCost: null, completionCost: null, cacheSavings: 0, comp: null, durationMs: 100, label: "LLM Request", instructions: null, response: [] }];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm, shots: {} }] });

    expect(out).toContain('class="scrubtick objective" data-scrub-step="Step 1" data-scrub-kind=""');
    expect(out).not.toContain('data-label=');
    expect(out).not.toContain('title="Step 1: Complete checkout"');
    expect(out).toContain('class="scrubtooltip scrubtracktooltip" data-scrubhover aria-hidden="true"');
    expect(out).toContain('class="scrubhoverstep" data-scrubhover-range aria-hidden="true"');
    expect(out).toContain('data-scrubhover-step');
    expect(out).toContain('data-scrubhover-kind');
    expect(out.match(/class="scrubtooltip(?: |")/g)).toHaveLength(1);
    expect(out).not.toContain('scrubtooltiplabel');
    expect(core.RUN_REPORT_CSS).toContain('.grphdr .chip, .galchip, .scrubtooltiptag { font-size: var(--type-micro);');
    expect(core.RUN_REPORT_CSS).toContain('.scrubtooltiptag { display: inline-flex; align-items: center; min-height: 21px; padding: 3px 11px; font-size: calc(var(--type-micro) * 1.5);');
    expect(core.RUN_REPORT_CSS).toContain('.scrubtooltip { position: absolute; z-index: 4;');
    expect(core.RUN_REPORT_CSS).toContain('padding: var(--space-3); border: 1px solid var(--line2); border-radius: var(--r-md);');
    expect(core.RUN_REPORT_CSS).toContain('.scrub { position: relative; z-index: 20;');
    expect(core.RUN_REPORT_CSS).not.toContain('border-left: 4px solid var(--tick-color)');
    expect(out).toContain('background:var(--timeline-objective-mark)');
    expect(out).toContain('class="scrubtick event llm" data-scrub-step="Step 1" data-scrub-kind="LLM"');
    expect(out).toContain('background:var(--ai)');
    expect(out).toContain('class="scrubtick event tool" data-scrub-step="Step 1" data-scrub-kind="Tool / action"');
    expect(out).toContain('background:var(--txt)');
    expect(core.RUN_REPORT_CSS).toContain('.scrubtracktooltip.visible { visibility: visible; opacity: 1; transform: none;');
    expect(core.RUN_REPORT_CSS).toContain('.scrubtooltipkind.visible { display: inline-flex; }');
    expect(core.RUN_REPORT_CSS).toContain('.scrubhoverstep { position: absolute; z-index: 0; top: 3px; bottom: 3px;');
    expect(core.RUN_REPORT_CSS).toContain('background: color-mix(in srgb,var(--run) 9%,transparent);');
    expect(core.RUN_REPORT_CSS).toContain('.scrubhead { position: absolute; z-index: 3; top: 50%; width: 12px; height: 12px;');
    expect(core.RUN_REPORT_CSS).toContain('border: 2px solid var(--run);');
  });

  test("scrubber hover follows authored ranges and event markers without hiding the underlying dot", () => {
    const trace = [
      { i: 1, label: "Prepare checkout", tool: "agent step", objective: true, trailhead: true, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "launchApp", tool: "launchApp", objective: false, ok: true, ts: 2, ms: 100 },
      { i: 3, label: "Choose payment", tool: "agent step", objective: true, ok: true, ts: 3, ms: 0 },
      { i: 4, label: "tapOnElement", tool: "text: Pay", objective: false, ok: true, ts: 4, ms: 100 },
      { i: 5, label: "Confirm receipt", tool: "agent step", objective: true, ok: true, ts: 5, ms: 0 },
      { i: 6, label: "assertVisible", tool: "text: Receipt", objective: false, ok: true, ts: 6, ms: 100 },
    ];
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] };

    renderViewerState(payload, {
      drive: (ctx) => {
        ctx.hoverScrub(0.05);
        expect(ctx.scrubHoverState()).toEqual({ tooltipVisible: true, rangeVisible: true, step: "Trailhead", kind: "", ariaHidden: "false" });

        ctx.hoverScrub(0.5, { step: "Step 1", kind: "Tool / action", color: "rgb(240, 246, 252)" });
        expect(ctx.scrubHoverState()).toEqual({ tooltipVisible: true, rangeVisible: true, step: "Step 1", kind: "Tool / action", ariaHidden: "false" });

        ctx.hoverScrub(0.95);
        expect(ctx.scrubHoverState().step).toBe("Step 2");

        ctx.leaveScrub();
        expect(ctx.scrubHoverState()).toEqual({ tooltipVisible: false, rangeVisible: false, step: "Step 2", kind: "", ariaHidden: "true" });
      },
    });
  });

  test("failed and self-healed authored steps use prominent semantic scrubber bars", () => {
    const healedTrace = [
      { i: 1, label: "Recover checkout", tool: "agent step", objective: true, selfHeal: true, ok: false, ts: 1, ms: 0 },
      { i: 2, label: "assertVisible", tool: "text: Pay", objective: false, selfHealSource: true, ok: false, ts: 2, ms: 100 },
      { i: 3, label: "Recover checkout", tool: "agent step", objective: true, ok: true, ts: 3, ms: 0 },
      { i: 4, label: "tapOnElement", tool: "text: Pay", objective: false, ok: true, ts: 4, ms: 100 },
      { i: 5, label: "Confirm receipt", tool: "agent step", objective: true, ok: true, ts: 5, ms: 0 },
      { i: 6, label: "assertVisible", tool: "text: Receipt", objective: false, ok: true, ts: 6, ms: 100 },
    ];
    const healed = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Recovered", status: "passed", selfHeal: true }, trace: healedTrace, llm: [], shots: {} }] });
    expect(healed).toContain('class="scrubstatusbox selfhealed" data-scrub-step="Step 1" data-scrub-kind="Self-healed"');
    expect(healed).toContain('data-scrub-step="Step 2"');
    expect(healed).not.toContain('right:calc(0% + 2px);--tick-color:var(--status-self-healed-mark)');
    expect(healed).not.toContain('class="scrubtick objective selfhealed"');
    expect(core.RUN_REPORT_CSS).toContain('.scrubstatusbox.selfhealed { border: 1px dashed var(--status-self-healed-mark);');

    const failedTrace = [
      { i: 1, label: "Submit checkout", tool: "agent step", objective: true, ok: false, ts: 1, ms: 0 },
      { i: 2, label: "tapOnElement", tool: "text: Pay", objective: false, ok: false, ts: 2, ms: 100 },
    ];
    const failed = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Failed", status: "failed" }, trace: failedTrace, llm: [], shots: {} }] });
    expect(failed).toContain('class="scrubstatusbox failed" data-scrub-step="Step 1" data-scrub-kind="Failed"');
    expect(failed).toContain('--tick-color:var(--status-failed-mark)');
    expect(failed).not.toContain('class="scrubtick objective failed"');
    expect(core.RUN_REPORT_CSS).toContain('.scrubstatusbox { position: absolute; z-index: 1;');
    expect(core.RUN_REPORT_CSS).toContain('.scrubstatusbox.failed { border: 1px dashed var(--status-failed-mark);');
  });

  test("a run with a trailhead renders it as its own labelled card above the numbered steps", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the demo checkout", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Review and submit the order" }, timestamp: "2024-01-01T00:00:01Z" },
    ];
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(logs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    // Trailhead section: dedicated semantic section, chip reads TRAILHEAD (no number).
    expect(out).toContain('<span class="name" id="trailhead-heading">Trailhead</span><span class="desc">0 actions</span>');
    expect(out).not.toContain('id="trailhead-heading">Trailhead</span><span class="counttoken"');
    expect(out).toContain('class="tlphase trailhead" aria-labelledby="trailhead-heading"');
    expect(core.RUN_REPORT_CSS).toContain('.tlphase.trailhead .steps { border: 1px dashed color-mix(in srgb,var(--trail-mark) 72%,var(--line2)); }');
    expect(out).toContain(">TRAILHEAD</span>");
    // Trail section: its count is a token beside the title, and numbering starts at STEP 1.
    expect(out).toContain('class="tlphase" aria-labelledby="trail-heading"');
    expect(out).toContain('<span class="name" id="trail-heading">Trail</span><span class="counttoken">1</span>');
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

  // The two Session-details parity gaps against the WASM report: (1) a tool call must expand to
  // its full content, and (2) a folded batch's every interaction — its own frame included — must
  // be reachable, not just the row's first frame.
  const batchedStepLogs = () => [
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Choose Gift Card as payment method" }, timestamp: "2024-01-01T00:00:00Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objV", successful: true, durationMs: 10, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, screenshotFile: "first.png", timestamp: "2024-01-01T00:00:01Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objV", successful: true, durationMs: 20, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: "^Gift Card$" } } } }, timestamp: "2024-01-01T00:00:02Z" },
    { class: `${T}.MaestroDriverLog`, traceId: "objV", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: "2024-01-01T00:00:03Z" },
  ];
  const batchedStepPayload = () => payloadOf(core.buildRunReportHtml({
    meta: { title: "R", status: "passed" },
    trace: core.extractTrace(batchedStepLogs()),
    llmLogs: [],
    shots: { "first.png": "data:image/png;base64,FIRST", "tap.png": "data:image/png;base64,TAP" },
  }));

  test("the selected row expands its full call as trail-file YAML", () => {
    const payload = batchedStepPayload();
    const row = payload.sessions[0].trace.find((t: any) => t.label === "assertVisibleBySelector");
    const out = renderViewer(payload, { step: Number(row.i) });
    expect(out).toContain('<pre class="toolargs mono">- assertVisibleBySelector:');
    expect(out).toContain("textRegex: ^Gift Card$");
    // Unselected rows stay summary-only: exactly one args panel per render.
    expect(out.match(/class="toolargs mono"/g)!.length).toBe(1);
  });

  test("selecting a folded dispatch previews ITS frame with its tap mark and expands ITS args", () => {
    const payload = batchedStepPayload();
    const row = payload.sessions[0].trace.find((t: any) => t.label === "assertVisibleBySelector");
    row.children[0].result = "Tapped Gift Card";
    row.children[0].resultVaries = true;
    const out = renderViewerState(payload, { clickKid: `${row.i}:0` });
    // The dispatch row is selected and its args panel expands under it...
    expect(out.html).toContain('class="kid sel"');
    expect(out.html).toContain("- tapOnElementBySelector:");
    expect(out.html).toContain('<div class="kidresultlabel">Result<span>varies across folded calls</span></div>');
    expect(out.html).toContain('<pre class="mono">Tapped Gift Card</pre>');
    // ...the preview pane shows the DISPATCH's own frame (not the row's first frame), with its
    // mark (src is assigned by wire(), so read the live <img>, not the rendered markup)...
    expect(out.shotImg.src).toBe("data:image/png;base64,TAP");
    expect(out.html).toContain('class="mark tap"');
    // ...and the selection round-trips through the route so the view is deep-linkable.
    expect(out.route).toContain("kid=0");
  });

  test("selecting a folded parent previews its OWN dispatch, and each dispatch is its own transport stop", () => {
    const payload = batchedStepPayload();
    const row = payload.sessions[0].trace.find((t: any) => t.label === "assertVisibleBySelector");
    const parked = renderViewerState(payload, { step: Number(row.i) });
    // A folded row stands for its FIRST dispatch, so it previews its own frame — it must not skip
    // ahead to the batch's last interaction, which is a separate stop the transport lands on.
    expect(parked.shotImg.src).toBe("data:image/png;base64,FIRST");
    expect(parked.route).not.toContain("kid=");
    // The rail counts interactions, not rows: objective header + row + its one folded dispatch.
    expect(parked.html).toContain('aria-valuemax="3"');

    // Next walks INTO the fold rather than past the whole step, landing on the dispatch's own
    // frame and tap mark — the interaction that used to be unreachable from the transport.
    const stepped = renderViewerState(payload, { step: Number(row.i), transport: "next" });
    expect(stepped.shotImg.src).toBe("data:image/png;base64,TAP");
    expect(stepped.html).toContain('class="mark tap"');
    expect(stepped.html).toContain('class="kid sel"');
    expect(stepped.route).toContain("kid=0");
  });

  test("with a video, a dispatch whose screenshot never inlined keeps its transport stop", () => {
    // The dispatch captured a frame that never made the payload (an export can drop images). In
    // steps mode there is nothing to show, so the transport skips it rather than repeating the
    // row's screen. With a run-clock video the frame comes from the clock instead, so the
    // interaction is visible after all and has to stay reachable — over its video frame, under its
    // own tap mark.
    const payload = batchedStepPayload();
    const session = payload.sessions[0];
    delete session.shots["tap.png"];
    const row = session.trace.find((t: any) => t.label === "assertVisibleBySelector");
    const noVideo = renderViewerState(payload, { step: Number(row.i), transport: "next" });
    expect(noVideo.route).not.toContain("kid=");

    session.video = { sprites: [{ uri: "data:image/webp;base64,SPRITEBYTES", rows: 2 }], fps: 2, frames: 2, columns: 1, rows: 2, frameHeight: 40, frameMap: [0, 1], startFrame: 0, endFrame: 1, startMs: row.ts };
    const stepped = renderViewerState(payload, { step: Number(row.i), transport: "next" });
    expect(stepped.route).toContain("kid=0");
    expect(stepped.html).toContain('class="kid sel"');
    expect(stepped.html).toContain('class="mark tap"');
  });

  test("with a video, a dispatch's frame is the one at ITS instant, not its row's", () => {
    // The mark drawn over this frame belongs to the dispatch, and a fold can span seconds - so the
    // frame under it has to be the video at the dispatch's own instant. Reading the row's clock
    // would circle a tap on a screen from an earlier interaction.
    const payload = batchedStepPayload();
    const session = payload.sessions[0];
    delete session.shots["tap.png"]; // no screenshot to show, so the pane falls to the video
    const row = session.trace.find((t: any) => t.label === "assertVisibleBySelector");
    session.video = { sprites: [{ uri: "data:image/webp;base64,U1BSSVRF", rows: 4 }], fps: 2, frames: 4, columns: 1, rows: 4, frameHeight: 40, frameMap: [0, 1, 2, 3], startFrame: 0, endFrame: 3, startMs: row.ts };
    const framePos = (html: string) => (html.match(/class="tlvframe"[^>]*background-position:([^";]+)/) || [])[1];
    const atRow = framePos(renderViewerState(payload, { step: Number(row.i) }).html);
    const atKid = framePos(renderViewerState(payload, { clickKid: `${row.i}:0` }).html);
    expect(atRow).toBeTruthy();
    expect(atKid).not.toBe(atRow); // the dispatch ran a second into the fold: a later frame
  });

  test("a dispatch's frame reads its sprite SHEET from the same instant as its position", () => {
    // The markup carries the background-position; wire() assigns the sheet image. Two sprite sheets
    // of two frames each put the row's frame on sheet 0 and the dispatch's on sheet 1, so reading
    // different clocks would paint one sheet's coordinates onto the other sheet's image.
    const payload = batchedStepPayload();
    const session = payload.sessions[0];
    delete session.shots["tap.png"];
    const row = session.trace.find((t: any) => t.label === "assertVisibleBySelector");
    session.video = {
      sprites: [{ uri: "data:image/webp;base64,U0hFRVQw", rows: 2 }, { uri: "data:image/webp;base64,U0hFRVQx", rows: 2 }],
      fps: 2, frames: 4, columns: 1, rows: 2, frameHeight: 40, frameMap: [0, 1, 2, 3], startFrame: 0, endFrame: 3, startMs: row.ts,
    };
    expect(renderViewerState(payload, { step: Number(row.i) }).tlvframeStyle.backgroundImage).toContain("U0hFRVQw");
    expect(renderViewerState(payload, { clickKid: `${row.i}:0` }).tlvframeStyle.backgroundImage).toContain("U0hFRVQx");
  });

  test("Next from a frameless dispatch keeps going forward, not back into the fold", () => {
    // Every dispatch in a fold is clickable, but only the ones that captured a frame are timeline
    // stops. Selecting a frameless one has to resolve to the nearest stop BEFORE it — resolving to
    // the row itself would make Next walk the selection backwards, onto a dispatch the reader has
    // already passed.
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Fill the form" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "f", successful: true, durationMs: 10, trailblazeTool: { raw: { text: "Name" } }, screenshotFile: "first.png", timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "f", successful: true, durationMs: 20, trailblazeTool: { raw: { text: "Name" } }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.MaestroDriverLog`, traceId: "f", action: { class: "xyz.AgentDriverAction.TapPoint", x: 100, y: 200 }, deviceWidth: 1080, deviceHeight: 2400, screenshotFile: "tap.png", timestamp: "2024-01-01T00:00:03Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "inputText", traceId: "f", successful: true, durationMs: 15, trailblazeTool: { raw: { text: "Ada" } }, timestamp: "2024-01-01T00:00:04Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "g", successful: true, durationMs: 12, trailblazeTool: { raw: { text: "Submit" } }, screenshotFile: "next.png", timestamp: "2024-01-01T00:00:05Z" },
    ];
    const payload = payloadOf(core.buildRunReportHtml({
      meta: { title: "R", status: "passed" },
      trace: core.extractTrace(logs),
      llmLogs: [],
      shots: { "first.png": "data:image/png;base64,RklSU1Q=", "tap.png": "data:image/png;base64,VEFQ", "next.png": "data:image/png;base64,TkVYVA==" },
    }));
    const trace = payload.sessions[0].trace;
    const folded = trace.find((t: any) => t.label === "assertVisibleBySelector");
    const after = trace.find((t: any) => t.label === "tapOnElementBySelector");
    expect(folded.children.map((c: any) => c.screenshotFile)).toEqual(["tap.png", undefined]); // the second dispatch captured nothing

    const stepped = renderViewerState(payload, { clickKid: `${folded.i}:1`, transport: "next" });
    expect(stepped.route).toContain(`step=${after.i}`);
    expect(stepped.route).not.toContain("kid=");
  });

  test("the step chip and phase header count dispatches, not folded rows", () => {
    // A step that tapped several targets reading "1 action" is the traceId fold leaking into the
    // reader's view. The arithmetic has to be traceToolCallCount's, which the run's headline stats
    // use: dispatches per row, and nothing at all for the trailing snapshot row.
    //
    // The fold carries THREE dispatches so the count discriminates: row-counting answers 1, the
    // whole step is 3, and including the Final state row would be 4. A two-dispatch fold plus a
    // terminal row also totals 2 by the old row arithmetic, which is why this fixture is bigger
    // than the batched one the neighbouring tests use.
    const payload = payloadOf(core.buildRunReportHtml({
      meta: { title: "R", status: "passed" },
      trace: core.extractTrace([
        ...batchedStepLogs(),
        { class: `${T}.TrailblazeToolLog`, toolName: "inputText", traceId: "objV", successful: true, durationMs: 15, trailblazeTool: { raw: { text: "Ada" } }, timestamp: "2024-01-01T00:00:03Z" },
        { class: `${T}.TrailblazeSnapshotLog`, displayName: "final_screenshot", screenshotFile: "final.png", timestamp: "2024-01-01T00:00:04Z" },
      ]),
      llmLogs: [],
      shots: { "first.png": "data:image/png;base64,FIRST", "tap.png": "data:image/png;base64,TAP", "final.png": "data:image/png;base64,FINAL" },
    }));
    const out = renderViewer(payload);
    // Both surfaces: the step card's chip and the phase header's summary.
    expect(out.match(/3 actions/g)!.length).toBe(2);
    expect(out).not.toContain("1 action"); // the fold's extra dispatches count
    expect(out).not.toContain("2 actions");
    expect(out).not.toContain("4 actions"); // the Final state row does not
  });

  test("an inspectable folded parent keeps its preview and inspector capture paired", () => {
    const logs = batchedStepLogs();
    (logs[1] as any).viewHierarchyFiltered = {
      className: "Screen", x1: 0, y1: 0, x2: 1080, y2: 2400,
      children: [{ text: "Gift Card", resourceId: "gift-card", x1: 20, y1: 20, x2: 200, y2: 100 }],
    };
    const payload = payloadOf(core.buildRunReportHtml({
      meta: { title: "R", status: "passed" },
      trace: core.extractTrace(logs),
      llmLogs: [],
      shots: { "first.png": "data:image/png;base64,FIRST", "tap.png": "data:image/png;base64,TAP" },
    }));
    const row = payload.sessions[0].trace.find((t: any) => t.label === "assertVisibleBySelector");
    const out = renderViewerState(payload, { step: Number(row.i), inspect: Number(row.i) });

    expect(out.shotImg.src).toBe("data:image/png;base64,FIRST");
    expect(out.zoomRoot.querySelector("[data-insphit]").querySelector("img").src).toBe("data:image/png;base64,FIRST");
  });

  test("a ?kid= deep link lands with that dispatch selected and previewed", () => {
    const payload = batchedStepPayload();
    const row = payload.sessions[0].trace.find((t: any) => t.label === "assertVisibleBySelector");
    const out = renderViewerState(payload, { query: `?run=0&tab=timeline&step=${row.i}&kid=0` });
    expect(out.html).toContain('class="kid sel"');
    expect(out.shotImg.src).toBe("data:image/png;base64,TAP");
  });

  test("args panels escape HTML-ish tool content (row and dispatch panels)", () => {
    // The args panels are the first place full untruncated tool content (app- or LLM-authored
    // strings) reaches root.innerHTML. This pins the escaping: an HTML-ish selector must render
    // inert text, never markup.
    const evil = '<img src=x onerror=alert(1)>';
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Tap the hostile row" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "assertVisibleBySelector", traceId: "objE", successful: true, durationMs: 10, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: evil } } } }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapOnElementBySelector", traceId: "objE", successful: true, durationMs: 20, trailblazeTool: { raw: { nodeSelector: { androidAccessibility: { textRegex: evil } } } }, timestamp: "2024-01-01T00:00:02Z" },
    ];
    const payload = payloadOf(core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(logs), llmLogs: [], shots: {} }));
    const row = payload.sessions[0].trace.find((t: any) => t.label === "assertVisibleBySelector");
    const rowView = renderViewer(payload, { step: Number(row.i) });
    expect(rowView).toContain("textRegex: &lt;img src=x onerror=alert(1)&gt;");
    expect(rowView).not.toContain(evil);
    const kidView = renderViewerState(payload, { clickKid: `${row.i}:0` });
    expect(kidView.html).toContain("textRegex: &lt;img src=x onerror=alert(1)&gt;");
    expect(kidView.html).not.toContain(evil);
  });

  test("the default Lightbox shows a batched-only step via its child frame instead of reading empty", () => {
    // Every frame in this run sits on folded dispatches: the row's own screenshotFile is null and
    // the capture lives on a child. The default (collapsed) view must fall back to the child frame
    // — not claim no screenshots exist until the reader finds "Show all".
    const trace = [
      { i: 1, label: "Choose Gift Card as payment method", objective: true, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "scriptedSignIn", tool: "", ok: true, ts: 2, ms: 30, children: [
        { label: "tapOnElementBySelector", tool: "textRegex: ^Gift Card$", ms: 20, ok: true, count: 1, screenshotFile: "kid_1.webp" },
      ] },
    ];
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: { "kid_1.webp": "data:image/png;base64,KID" } }] };
    const out = renderViewer(payload, { tab: "lightbox" });
    expect(out).not.toContain("No screenshots captured for this run.");
    expect(out).toContain('data-lightbox-kid="0"');
    expect(out).toContain('data-shot="kid_1.webp"');
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
    expect(out).toContain('<span class="name" id="trailhead-heading">Trailhead</span><span class="desc">20 actions</span><span class="phaseduration">2.0s</span>');
    expect(core.RUN_REPORT_CSS).toContain('.tlphasehead .desc { flex-shrink: 0; margin-left: auto;');
    expect(out).toContain('<span class="name" id="trail-heading">Trail</span><span class="counttoken">1</span>');
    expect(core.RUN_REPORT_CSS).toContain(".phasecontrol:hover .phasedisclosure { color: var(--txt); background: var(--button-hover); }");
    expect(out).not.toContain('class="grphdr sel"');
    // Step headers are disclosure controls: the selected step opens, every settled one collapses.
    expect(out).toContain('data-group="22" aria-expanded="true"');
    expect(out).toContain('data-group="1" aria-expanded="false"');
    expect(out).toContain('<div class="stepgroupbody" hidden>');
    // A collapsed step hides its rows, so the header is the only way in — it has to be a tab stop.
    expect(out).not.toContain('data-group="1" aria-expanded="false" tabindex="-1"');
    expect(out).not.toContain('data-group="22" aria-current="step"');
    expect(out).toContain('class="scrubphasebox"');
    expect(out).toContain('class="scrubline trail"');
    expect(out).toContain('Dashed box encloses Trailhead activity; solid rail marks the authored Trail.');
    expect(out).toContain('aria-valuetext="Trail, item 23 of 23: tapOnElement"');
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

  test("expanding a step header selects the step's first tool call", () => {
    const trace = [
      { i: 1, label: "Complete checkout", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 2, ms: 100 },
      { i: 3, label: "assertVisible", tool: "text: Done", objective: false, trailhead: false, ok: true, ts: 3, ms: 100 },
      { i: 4, label: "Review the order", objective: true, trailhead: false, ok: true, ts: 4, ms: 0 },
    ];
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] };
    // Step 1 opens on load because it holds the initial selection; step 4 starts collapsed.
    const laterActions = {
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: [
        ...trace,
        { i: 5, label: "tapOnElement", tool: "text: Done", objective: false, trailhead: false, ok: true, ts: 5, ms: 100 },
      ], llm: [], shots: {} }],
    };
    const expanded = renderViewerState(laterActions, { clickGroup: 4 });
    expect(expanded.html).toContain('data-group="4" aria-expanded="true"');
    expect(expanded.html).toContain('class="step sel child" data-step="5"');
    expect(expanded.route).toContain("step=5");
    // Clicking an open header closes it and leaves the selection alone — nothing to look at inside.
    const clicked = renderViewerState(payload, { clickGroup: 1 });
    expect(clicked.html).toContain('data-group="1" aria-expanded="false"');
    expect(clicked.route).toContain("step=2");
    // A step with no actions leaves the existing tool selection alone; headers are not selectable.
    const empty = renderViewerState(payload, { clickGroup: 4 });
    expect(empty.html).toContain('class="step sel child" data-step="2"');
    expect(empty.route).toContain("step=2");
    // An agent step's leading reasoning row is not a tool call either: the first real action wins.
    const agentStep = {
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: [
        { i: 1, label: "Sign in", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
        { i: 2, label: "tapOnElement", tool: "text: Next", objective: false, trailhead: false, ok: true, ts: 2, ms: 100 },
        { i: 3, label: "Complete checkout", objective: true, trailhead: false, ok: true, ts: 3, ms: 0 },
        { i: 4, label: "the login button is visible", tool: "llm · gpt-test", objective: false, trailhead: false, ok: true, ts: 4, ms: 100 },
        { i: 5, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 5, ms: 100 },
      ], llm: [], shots: {} }],
    };
    const reasoned = renderViewerState(agentStep, { clickGroup: 3 });
    expect(reasoned.route).toContain("step=5");
    // A trailing terminal snapshot after an action-less final step is not a "first tool call".
    const withSnapshot = {
      generatedAt: "now",
      sessions: [{ meta: { title: "R", status: "passed" }, trace: [
        ...trace,
        { i: 5, label: "Final state", tool: "", terminal: true, objective: false, trailhead: false, ok: true, ts: 5, ms: 0 },
      ], llm: [], shots: {} }],
    };
    const snapped = renderViewerState(withSnapshot, { clickGroup: 4 });
    expect(snapped.html).toContain('class="step sel child" data-step="2"');
    expect(snapped.route).toContain("step=2");
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
    expect(out).toContain('class="scrubphasebox" style="width:100%"');
    expect(out).not.toContain('class="scrubline trail"');
    expect(out).toContain('aria-label="Timeline for Trailhead setup. The dashed box encloses Trailhead activity."');
  });

  test("a run without a trailhead keeps the single unlabelled steps card", () => {
    const html = core.buildRunReportHtml({ meta: { title: "R", status: "passed" }, trace: core.extractTrace(sampleLogs), llmLogs: [], shots: {} });
    const out = renderViewer(payloadOf(html));
    expect(out).not.toContain("Deterministic setup");
    expect(out).not.toContain("thcard");
    expect(out).toContain(">STEP 1</span>");
  });

  test("an objective that ultimately passed keeps a green step token despite a failed row inside it", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Sign in" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.MaestroDriverLog`, action: { class: "xyz.AgentDriverAction.AssertCondition", conditionDescription: "field visible", x: 1, y: 2, succeeded: false }, deviceWidth: 100, deviceHeight: 200, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.ObjectiveCompleteLog`, promptStep: { step: "Sign in" }, objectiveResult: { class: "xyz.AgentTaskStatus.Success.ObjectiveComplete" }, timestamp: "2024-01-01T00:00:02Z" },
    ];
    const slim = (core as any).slimTraceForShare(core.extractTrace(logs));
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace: slim, llm: [], shots: {}, recordingYaml: null }] });
    const hdr = out.match(/grphdr[\s\S]*?<span class="chip (pass|fail)">/);
    expect(hdr).not.toBeNull();
    expect(hdr![1]).toBe("pass");
    expect(out).toContain('<span class="grpstatus pass">');
    // Tool-only roving selection still lands on the only actionable row, while the objective
    // group keeps its final successful outcome.
    const failedRow = slim.find((t: any) => !t.ok);
    expect(out).toContain(`class="step sel child" data-step="${failedRow.i}"`);
    expect(out).not.toContain("Run failure");
    expect(out).not.toContain('class="stepgroup failed"');
  });

  test("only the step holding the selection opens; walking on re-collapses the one behind", () => {
    // Expansion that FOLLOWS the selection has to be derived from it, not recorded. Recording it
    // would make every automatic reveal - playback, live tail, arrow keys - permanent, so watching
    // a run to the end would leave every step it passed through open: the wall this exists to remove.
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace: [
      { i: 1, label: "Open the cart", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "tapOnElement", tool: "text: Cart", objective: false, trailhead: false, ok: true, ts: 2, ms: 100 },
      { i: 3, label: "Pay with the saved card", objective: true, trailhead: false, ok: true, ts: 3, ms: 0 },
      { i: 4, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 4, ms: 100 },
    ], llm: [], shots: {} }] };
    const first = renderViewerState(payload, { routeStep: 2 });
    expect(first.html).toContain('data-group="1" aria-expanded="true"');
    expect(first.html).toContain('data-group="3" aria-expanded="false"');
    // Same document, selection moved on: the step behind it closes again on its own.
    const second = renderViewerState(payload, { routeStep: 4 });
    expect(second.html).toContain('data-group="1" aria-expanded="false"');
    expect(second.html).toContain('data-group="3" aria-expanded="true"');
  });

  test("a selection after a self-heal retry opens the step the retry was merged into", () => {
    // The retry is itself an objective row, but the trace model folds it into the step it retried,
    // so only the ORIGINAL header is rendered. Resolving the selection's group by scanning back for
    // the nearest objective finds the retry instead, and opens a header that does not exist.
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed", selfHeal: true }, trace: [
      { i: 1, label: "Open the cart", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "tapOnElement", tool: "text: Cart", objective: false, trailhead: false, ok: true, ts: 2, ms: 100 },
      { i: 3, label: "Pay", objective: true, trailhead: false, ok: false, selfHeal: true, ts: 3, ms: 0 },
      { i: 4, label: "assertVisible", tool: "text: Pay", objective: false, trailhead: false, ok: false, ts: 4, ms: 100 },
      { i: 5, label: "Pay", objective: true, trailhead: false, ok: true, ts: 5, ms: 0 },
      { i: 6, label: "tapOnElement", tool: "text: Pay", objective: false, trailhead: false, ok: true, ts: 6, ms: 100 },
    ], llm: [], shots: {} }] };
    // The retry header is folded away, so only 3 is a real control.
    expect(renderViewerState(payload, { routeStep: 4 }).html).not.toContain('data-group="5"');
    // Collapse the merged step by hand, then arrow forward into the rows after the retry. Resolving
    // the destination to the retry's own id would leave this explicit collapse in place and strand
    // the selection behind a closed header.
    const out = renderViewerState(payload, { routeStep: 4, clickGroup: 3, timelineKey: "ArrowDown" });
    expect(out.route).toContain("step=6");
    expect(out.html).toContain('data-group="3" aria-expanded="true"');
  });

  test("collapsing a focused step header hands focus back to the header", () => {
    const payload = { generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace: [
      { i: 1, label: "Open the cart", objective: true, trailhead: false, ok: true, ts: 1, ms: 0 },
      { i: 2, label: "tapOnElement", tool: "text: Cart", objective: false, trailhead: false, ok: true, ts: 2, ms: 100 },
    ], llm: [], shots: {} }] };
    // Focus cannot go to the selected step: collapsing is what just hid it.
    const collapsed = renderViewerState(payload, { focusedGroup: 1, clickGroup: 1 });
    expect(collapsed.html).toContain('data-group="1" aria-expanded="false"');
    expect(collapsed.readRestoredFocus()).toBe('[data-group="1"]');
  });

  test("the trailhead token stays distinguishable while still carrying its outcome colour", () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Launch the app", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "launchApp", traceId: "h1", trailblazeTool: { raw: {} }, successful: false, errorMessage: "app never came up", timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Sign in" }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.ObjectiveCompleteLog`, promptStep: { step: "Sign in" }, objectiveResult: { class: "xyz.AgentTaskStatus.Success.ObjectiveComplete" }, timestamp: "2024-01-01T00:00:03Z" },
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "failed" }, trace: core.extractTrace(logs), llm: [], shots: {} }] });
    expect(out).toContain('class="grphdr trailhead"');
    expect(out).toContain(">TRAILHEAD</span>");
    // A trailhead that broke reads red like any other failure. Its distinguishing mark is a ring,
    // deliberately not a hue, so nothing here can override the outcome colour it inherits.
    expect(out).toContain('<span class="chip fail">TRAILHEAD</span>');
    expect(core.RUN_REPORT_CSS).toContain(".grphdr.trailhead .chip { box-shadow: inset 0 0 0 1px currentColor; }");
    expect(core.RUN_REPORT_CSS).not.toContain(".grphdr.trailhead .chip { color:");
  });

  test("self-heal metadata without a trace anchor keeps a non-navigable status", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{ meta: { title: "Healed", status: "passed", selfHeal: true }, trace: slim, llm: [], shots: {}, recordingYaml: null }],
    });
    expect(out).toContain('<span class="badge selfheal">self-healed</span>');
    expect(out).not.toContain('class="statusjump selfhealjump"');
    expect(core.RUN_REPORT_CSS).toContain(".badge.selfheal { background: var(--warning-surface); color: var(--amber); }");
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
    expect(out).toContain('<span class="selfhealtitle" id="selfheal-title">SELF-HEALED</span>');
    expect(out).toContain("Trailblaze used AI to recover this step.");
    expect(out).toContain("Recorded selector no longer matched");
    expect(out.indexOf('class="selfhealpanel"')).toBeGreaterThan(out.indexOf(`data-step="${flaky.i}"`));
    expect(out).toContain('class="stepgroup selfhealed"');
    expect(out).toContain(`data-group="${healed.i}"`);
    expect(out).toContain(`child selfheal" data-step="${flaky.i}"`);
    expect(out).not.toContain(`class="step child selfheal" data-step="${recovered.i}"`);
    expect(out).toContain('<span class="chip selfheal">');
    expect(core.RUN_REPORT_CSS).toContain(".grphdr .chip.selfheal { color: var(--status-self-healed-mark);");
    expect(core.RUN_REPORT_CSS).toContain(".stepgroup.selfhealed .step.selfheal { background: var(--warning-surface); }");
    expect(core.RUN_REPORT_CSS).toContain(".stepgroup.selfhealed .step { background-color: var(--bg2); }");
  });

  test("a self-heal retry of the same objective stays inside the original step", () => {
    const trace = [
      { i: 1, label: "Submit the email address", tool: "agent step", note: null, ms: 0, ts: 1, ok: false, err: null, screenshotFile: null, objective: true, trailhead: false, selfHeal: true, count: null, mark: null, children: [] },
      { i: 2, label: "tapOnElementBySelector", tool: "textRegex: Next", note: null, ms: 100, ts: 2, ok: false, err: "Error: not found", screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [] },
      { i: 3, label: "Submit the email address", tool: "agent step", note: null, ms: 0, ts: 3, ok: false, err: null, screenshotFile: null, objective: true, trailhead: false, count: null, mark: null, children: [] },
      { i: 4, label: "Screen Analyzer", tool: "", note: null, ms: 100, ts: 4, ok: true, err: null, screenshotFile: null, objective: false, trailhead: false, llm: 0, count: null, mark: null, children: [] },
      { i: 5, label: "Failure state", tool: "", note: null, ms: 0, ts: 5, ok: false, err: null, screenshotFile: null, objective: false, trailhead: false, terminal: true, count: null, mark: null, children: [] },
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Failed", status: "failed" }, trace, llm: [{}], shots: {} }] });
    expect(out).toContain(">STEP 1<");
    expect(out).not.toContain(">STEP 2<");
    expect(out).toContain('<div class="retrydivider"><span>Retry 1</span></div>');
    expect(out).toContain('class="stepgroup failed"');
    expect(out).not.toContain('class="stepgroup selfhealed"');
    expect(out).toContain('<div class="lbl">LLM</div>');
    expect(out).not.toContain('<div class="lbl">Screen Analyzer</div>');
    expect(out.match(/Submit the email address/g)).toHaveLength(1); // card title; error context no longer repeats it
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
    expect(out).toContain("$0.001000");
    expect(out).toContain('data-tab="llm">LLM <span class="counttoken">1</span></button>');
    expect(out).not.toContain("LLM (1)");
    expect(core.RUN_REPORT_CSS).toContain("nav button::after { content: ''; position: absolute; right: 3px; bottom: -2px;");
    expect(core.RUN_REPORT_CSS).toContain("nav button.active::after { background: var(--run); }");
    expect(core.RUN_REPORT_CSS).toContain(".counttoken { min-width: 20px; height: 20px;");
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
    expect(out).toContain("−$0.00");
    expect(out).toContain("$0.02");
    // Input/output cost totals from the per-call costs.
    expect(out).toContain("$0.01");
    expect(out).toContain("$0.00");
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
    // A lone run still gets the Trail view (its Replay is the whole point of loading a recording),
    // and with no index to host the button, the detail header carries it.
    expect(out).toContain('<div class="detailactions"><button class="btn" type="button" data-goto-trail');
    expect(out).toContain('<details class="exportmenu"');
    expect(out).toContain('<span class="exportdots" aria-hidden="true"><span class="exportdot"></span><span class="exportdot"></span><span class="exportdot"></span></span>');
    expect(core.RUN_REPORT_CSS).toContain('.exportdot { width: 3px; height: 3px;');
    expect(out).not.toContain('class="headerfact"');
    expect(out).toContain('<div class="detailfootermeta" tabindex="0" aria-label="Run metadata"><span class="detailfooteritem"><span class="k">Target</span><span class="v">demo</span></span>');
    expect(out).toContain('<span class="k">Run on</span><span class="v">2026-07-17 07:30:00</span>');
    expect(out).toContain('<span class="k">Total duration</span><span class="v">1m 25s</span>');
    expect(out).toContain('<span class="k">Tokens used</span><span class="v">0</span>');
    expect(out).toContain('<span class="k">LLM cost</span><span class="v">$0.00</span>');
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
    expect(copied).toContain("`./trailblaze app --v2`");
  });

  test("the timeline separates trailhead setup from numbered trail steps", () => {
    const trace = (core as any).slimTraceForShare(core.extractTrace([
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the demo app", isTrailhead: true }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "launchApp", traceId: "setup", trailblazeTool: { raw: {} }, successful: true, timestamp: "2024-01-01T00:00:00.500Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Complete checkout" }, timestamp: "2024-01-01T00:00:01Z" },
    ]));
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "R", status: "passed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain('<span class="name" id="trailhead-heading">Trailhead</span><span class="desc">1 action</span>');
    expect(out).not.toContain('id="trailhead-heading">Trailhead</span><span class="counttoken"');
    expect(out).toContain(">TRAILHEAD<");
    expect(out).toContain(">STEP 1<");
    expect(out.indexOf('id="trailhead-heading"')).toBeLessThan(out.indexOf('id="trail-heading"'));
  });

  test("a failed run opens on its failure and presents the parsed error inside the failed step", () => {
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
    expect(out).toContain('<span class="failuretitle" id="failure-title">ERROR</span>');
    expect(out).toContain("Review and submit the order");
    expect(out).toContain("Failed tool call");
    expect(out).toContain("assertVisibleBySelector");
    expect(out).not.toContain("Go to step");
    expect(out).toContain("com.example.checkout.FeesDisclosureException");
    expect(out).toContain("Fees disclosure did not appear before checkout");
    expect(out).toContain("Stack trace");
    expect(out).toContain("FeesVerifier.kt:42");
    expect(out).toContain('<div class="timelinecontrols"><button type="button" class="statusjump failedjump" data-failure-step="3" title="Go to failed step 1" aria-label="Go to failed step 1"><span class="statusjumplabel">Failed</span><span class="statusjumptoken">STEP 1</span></button>');
    expect(out.indexOf('class="failurepanel"')).toBeGreaterThan(out.indexOf('class="step sel child" data-step="3"'));
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
    expect(out).toContain('<span class="failuretitle" id="failure-title">ERROR</span>');
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
    expect(out).toContain('<span class="failuretitle" id="failure-title">ERROR</span>');
    expect(out).toContain("Did not find any uploaded user journey named");
    expect(out.match(/failuremessage">([^<]*)</)![1]).not.toContain("Home not visible yet");
  });

  test("JSON commands embedded in failure messages render as indented code blocks", () => {
    const command = '{"tapOnElement":{"selector":{"textRegex":"^Next$","optional":false},"longPress":false}}';
    const trace = [
      { i: 1, label: "Submit", tool: "agent step", note: null, ms: 0, ts: 1, ok: false, err: null, screenshotFile: null, objective: true, trailhead: false, count: null, mark: null, children: [] },
      { i: 2, label: "tapOnElementBySelector", tool: "textRegex: Next", note: null, ms: 20, ts: 2, ok: false, err: `Error: Failed to run command: ${command}. Error: Element not found`, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [] },
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [{ meta: { title: "Failed", status: "failed" }, trace, llm: [], shots: {} }] });
    expect(out).toContain('<pre class="failurejson mono">{\n  &quot;tapOnElement&quot;: {');
    expect(out).toContain('<div class="k">Cause</div><span class="failuretype" title="Derived from error message · reported type: Error">Element not found</span>');
    expect(out).toContain('<div class="failureprose">Error: Element not found</div>');
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
    expect(out).toContain(">Events</span>");
    expect(out).toContain(">Streams</span>");
    expect(out).toContain('aria-label="Events, 4 of 4 selected"');
    expect(out).toContain('aria-label="Streams, 1 of 1 selected"');
    expect(out.indexOf('data-streamselect open')).toBeLessThan(out.indexOf('id="trail-heading"'));
    expect(out).toContain('streamtype">network observer');
    expect(out).not.toContain('streamtype">Stream');
    expect(out).toContain('style="--stream-color:oklch(74% .14 70)" data-lazykey=');
    expect(out).toContain('<span class="streamdot" aria-hidden="true"></span>');
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
    expect(out).toContain(">All</button>");
    expect(out).toContain(">None</button>");
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
    const chooser = out.indexOf(">Streams</span>");
    expect(chooser).toBeGreaterThan(-1);
    expect(out).toContain('aria-label="Events, 4 of 4 selected"');
    expect(out).toContain('aria-label="Streams, 0 of 4 selected"');
    expect(chooser).toBeLessThan(out.indexOf('id="trailhead-heading"'));
    expect(out).toContain('class="streamselectoricon"');
    expect(out).toContain('class="streamoptiondot"');
    expect(out).toContain('<div class="timelinecontrols"><span class="badge passed">PASSED</span><span class="timelinefilters"><details class="streamselect eventselect"');
    expect(core.RUN_REPORT_CSS).toContain('.timelinecontrols .badge.passed { padding: 0; border-radius: 0; background: transparent; color: var(--status-passed-mark);');
    expect(core.RUN_REPORT_CSS).toContain('.timelinecontrols button.failedjump { background: var(--status-failed-mark); color: #fff; }');
    expect(core.RUN_REPORT_CSS).toContain('.timelinecontrols button.selfhealjump { background: var(--status-self-healed-mark); color: #15181d; }');
    expect(core.RUN_REPORT_CSS).toContain('[data-theme="light"] .timelinecontrols button.selfhealjump { color: #fff; }');
    expect(out).toContain('<div class="runidentity"><span class="idxstatus" role="img" aria-label="passed" title="passed"><span class="idxstatusdot passed" aria-hidden="true"></span></span><h1>Streams</h1></div>');
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
    const down = renderViewerState(payload, { routeStep: slim[1].i, timelineKey: "ArrowDown" });
    expect(down.route).toContain(`step=${slim[2].i}`);
    const up = renderViewerState(payload, { routeStep: slim[2].i, timelineKey: "ArrowUp" });
    expect(up.route).toContain(`step=${slim[1].i}`);
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

  test("timeline actions use distinct tap, LLM, verification, and failure icons", () => {
    const out = renderViewer({
      generatedAt: "now",
      sessions: [{
        meta: { title: "Action icons", status: "failed" },
        trace: [
          { i: 1, label: "tapOnElementBySelector", ok: true },
          { i: 2, label: "LLM request", tool: "agent step", ok: true },
          { i: 3, label: "assertVisibleBySelector", ok: true },
          { i: 4, label: "assertVisibleBySelector", ok: false },
        ],
        llm: [], shots: {}, recordingYaml: null,
      }],
    });
    expect(out).toContain('<span class="ic tap" aria-hidden="true">◉</span>');
    expect(out).toContain('<span class="ic llm" aria-hidden="true"><svg viewBox="0 0 24 24"');
    expect(out).toContain('<span class="ic verify" aria-hidden="true">✓</span>');
    expect(out).toContain('<span class="ic failure" aria-hidden="true">×</span>');
  });
});

describe("steps a failure cut off", () => {
  // Four declared steps, two of which ever started. The logs know nothing about steps 3 and 4 —
  // they are only in the authored YAML — so the timeline used to end at the failure.
  const yaml = `trail:
  - step: Open the cart
  - step: Pay with the saved card
  - step: Confirm the receipt
  - verify: The order appears in history`;
  const logs = [
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the cart" }, timestamp: "2024-01-01T00:00:00Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapCart", traceId: "t1", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:01Z" },
    { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Pay with the saved card" }, timestamp: "2024-01-01T00:00:02Z" },
    { class: `${T}.TrailblazeToolLog`, toolName: "tapPay", traceId: "t2", trailblazeTool: { raw: {} }, successful: false, errorMessage: "no such element", durationMs: 10, timestamp: "2024-01-01T00:00:03Z" },
  ];
  const timeline = (status: string, originalYaml: string | null) => {
    const html = core.buildRunReportHtml({
      meta: { title: "Checkout", status, originalYaml },
      trace: core.extractTrace(logs),
      llmLogs: [],
      shots: {},
      originalYaml,
    });
    return renderViewerState(payloadOf(html)).html;
  };

  test("a failed run lists the steps that never ran, greyed and numbered where they would have been", () => {
    const out = timeline("failed", yaml);
    expect(out).toContain('<div class="stepgroup skipped">');
    expect(out).toContain("STEP 3");
    expect(out).toContain("Confirm the receipt");
    expect(out).toContain("STEP 4");
    expect(out).toContain("The order appears in history");
    expect(out).toContain('<span class="chip skip">STEP 3</span>');
    expect(out).toContain("Not run");
    // Inert: no trace row exists behind these, so nothing about them is selectable.
    expect(out).not.toContain('data-group="undefined"');
    // The phase count stays honest about how far the run actually got.
    expect(out).toContain('<span class="counttoken">2/4</span>');
  });

  test("a passing run and a run with no captured source are untouched", () => {
    expect(timeline("passed", yaml)).not.toContain("stepgroup skipped");
    expect(timeline("failed", null)).not.toContain("stepgroup skipped");
  });

  test("a trail source that disagrees with what ran shows no steps rather than the wrong ones", () => {
    const other = `trail:
  - step: Something else entirely
  - step: Pay with the saved card
  - step: Confirm the receipt`;
    expect(timeline("failed", other)).not.toContain("stepgroup skipped");
  });

  // The reconciliation compares a declared label against the label the runner logged, and a
  // multi-line `verify:` block is where those two can drift: the YAML carries it across several
  // indented lines while the log carries one string. If reading the block ever regressed, the
  // comparison would call the source unrelated and silently drop every un-run step.
  test("a ran step declared as a multi-line verify block still reconciles", () => {
    const blockYaml = `trail:
  - step: Open the cart
  - verify: |-
      The receipt shows the right total.
      The order appears in history.
  - step: Confirm the receipt
  - step: Sign out`;
    const verified = "The receipt shows the right total.\nThe order appears in history.";
    const blockLogs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the cart" }, timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "tapCart", traceId: "t1", trailblazeTool: { raw: {} }, successful: true, durationMs: 10, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: verified }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.TrailblazeToolLog`, toolName: "assertVisible", traceId: "t2", trailblazeTool: { raw: {} }, successful: false, errorMessage: "no such element", durationMs: 10, timestamp: "2024-01-01T00:00:03Z" },
    ];
    // The declared step and the logged objective have to be the same text read two different ways.
    expect(declaredTrailSteps(blockYaml)[1]).toBe(verified);
    const html = core.buildRunReportHtml({
      meta: { title: "Checkout", status: "failed", originalYaml: blockYaml },
      trace: core.extractTrace(blockLogs),
      llmLogs: [],
      shots: {},
      originalYaml: blockYaml,
    });
    const out = renderViewerState(payloadOf(html)).html;
    expect(out).toContain('<span class="chip skip">STEP 3</span>');
    expect(out).toContain("Confirm the receipt");
    expect(out).toContain('<span class="chip skip">STEP 4</span>');
    expect(out).toContain("Sign out");
    expect(out).toContain('<span class="counttoken">2/4</span>');
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
    expect(out).toContain('data-tab="lightbox">Lightbox <span class="counttoken">1</span></button>');
    for (const tab of ["Video", "Device logs", "Network"]) expect(out).toContain(">" + tab + "<");
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
    const state = renderViewerState(timed, { step: slim[1].i });
    expect(state.html).toContain('id="tlvframe"');
    expect(state.tlvframeStyle.backgroundImage).toBe("url('data:image/webp;base64,AAAA')");
    // slim[1] ran at capture start + 1s; at 2fps that's past the last frame, so it clamps to
    // endFrame 1 → sprite row 1 of a 1×2 sheet (background-position 0% 100%).
    expect(state.html).toContain("background-position:0% 100%");
    expect(state.html).not.toContain('id="shot"');
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

  test("there is no Events tab; event types and streams use paired timeline choosers", () => {
    const out = renderViewer(payload);
    expect(out).not.toContain('data-tab="events"');
    expect(out).toContain(">Events</span>");
    expect(out).toContain(">Streams</span>");
    expect(out).toContain("com.example.plugin.network");
    expect(out).toContain("com.example.plugin.analytics");
    expect(out).toContain('<span class="streamname">network</span>');
    expect(out).toContain('<span class="streamname">analytics</span>');
    // The chooser reports the driver's true total, not just the embedded lines.
    expect(out).toContain('<span class="streamcount">120</span>');
  });

  test("the status and stream controls sit outside the scrolling timeline body", () => {
    const out = renderViewer(payload);
    expect(out.indexOf('class="timelinecontrols"')).toBeLessThan(out.indexOf('class="timelinescroll"'));
    expect(core.RUN_REPORT_CSS).toContain(".timelinemain .timeline-list { grid-row: auto; min-height: 0; display: flex; flex-direction: column; overflow: visible; }");
    // The controls inset by the scroll pane's padding PLUS the 8px gutter `scrollbar-gutter: stable`
    // reserves, which is what lines their right edge up with a card's inside the pane.
    expect(core.RUN_REPORT_CSS).toContain(".timelinemain .timelinecontrols { margin-right: calc(var(--page-x) + 8px); }");
    expect(core.RUN_REPORT_CSS).toContain(".timelinemain .timelinescroll { min-height: 0; flex: 1; overflow-x: hidden; overflow-y: auto; scrollbar-gutter: stable;");
    expect(core.RUN_REPORT_CSS).toContain("*::-webkit-scrollbar { width: 8px; height: 8px; }");
  });

  test("a legacy tab=events URL lands on the timeline", () => {
    const state = renderViewerState(payload, { query: "?run=0&tab=events&stream=1" });
    expect(state.html).toContain('class="timeline-list"');
    expect(state.route).not.toContain("tab=events");
    expect(state.route).not.toContain("stream=");
  });

  test("a selected stream renders each event inline without repeated timestamps", () => {
    const out = renderViewer(payload, { tlStream: 0 });
    expect(out).toContain('class="timelineevent"');
    expect(out).toContain('<span class="streamtype">network</span>');
    expect(out).not.toContain("+0.50s");
    expect(out).not.toContain("+1.50s");
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
    expect(out).toContain(">Events</span>"); // the chooser renders without any steps to anchor it
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
    expect(out).toContain(">Events</span>");
    expect(out).not.toContain(">Streams</span>");
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
        expect(ctx.html()).toContain('aria-label="Stop timeline"');
        expect(ctx.html()).toContain('class="transporticon stopicon"');
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

  test("hover previews stand down while timeline playback is running", () => {
    renderViewerState(playbackPayload(), {
      drive: (ctx) => {
        ctx.hoverStep(4);
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S4");

        ctx.play();
        const rendersAfterPlay = ctx.renders();
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S1"); // play clears the parked hover preview

        ctx.hoverStep(4);
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S1");
        ctx.hoverScrub(0.95);
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S1");

        ctx.advance(600);
        expect(ctx.selectedSteps()).toEqual(["2"]);
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S2"); // playback, not hover, owns the pane

        ctx.leaveStep(4);
        ctx.leaveScrub();
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S2");
        expect(ctx.renders()).toBe(rendersAfterPlay);
      },
    });
  });

  test("playback skips timeline rows hidden by the Events filter", () => {
    const payload = playbackPayload();
    payload.sessions[0].trace[1] = {
      ...payload.sessions[0].trace[1],
      label: "LLM",
      llm: 0,
    };
    payload.sessions[0].llm = [{ model: "example-model" }];

    renderViewerState(payload, {
      query: "?run=0&tab=timeline&step=1&types=tool,assert",
      drive: (ctx) => {
        ctx.play();
        ctx.advance(0);
        ctx.advance(1100);
        expect(ctx.selectedSteps()).toEqual(["3"]);
        expect(ctx.html()).not.toContain('class="step sel" data-step="2"');
      },
    });
  });

  test("playback stays stopped when the Events filter hides every row", () => {
    renderViewerState(playbackPayload(), {
      query: "?run=0&tab=timeline&step=1&types=none",
      drive: (ctx) => {
        ctx.play();
        expect(ctx.html()).toContain('aria-label="Play timeline"');
        expect(ctx.html()).not.toContain('aria-label="Stop timeline"');
      },
    });
  });

  test("play hands the preview back to the video even when a dispatch capture is selected", () => {
    // A selected dispatch previews its own screenshot while parked, which must not survive the play
    // click: playback paints frames into #tlvframe only, so a static <img> would freeze on that one
    // child screenshot for the whole run.
    const payload = playbackPayload();
    payload.sessions[0].trace[0] = {
      ...payload.sessions[0].trace[0],
      children: [{ label: "tapOn", tool: "tapOn", ok: true, screenshotFile: "k1.png" }],
    };
    payload.sessions[0].shots["k1.png"] = "data:image/png;base64,K1";
    payload.sessions[0].video = {
      sprites: [{ uri: "data:image/webp;base64,AAAA", rows: 4 }],
      fps: 2, frames: 4, columns: 1, rows: 4, frameHeight: 40,
      frameMap: [0, 1, 2, 3], startFrame: 0, endFrame: 3, startMs: 100000,
    };

    const state = renderViewerState(payload, {
      query: "?run=0&tab=timeline&step=1&kid=0",
      drive: (ctx) => {
        expect(ctx.html()).toContain('id="shot"'); // parked: the selected capture wins over the frame
        ctx.play();
        expect(ctx.html()).toContain('id="tlvframe"');
        expect(ctx.html()).not.toContain('id="shot"');
        ctx.advance(0);
        // Entries are [row 1, its dispatch, rows 2-4], so playback resumes from the dispatch at
        // 350ms; 600ms more clears row 2's 850ms offset.
        ctx.advance(600);
        expect(ctx.selectedSteps()).toEqual(["2"]);
      },
    });
    // Row 2 is 950ms into a 2fps capture ⇒ frame 1 of a 1×4 sheet: the engine painted a new frame
    // rather than leaving the preview on the child screenshot.
    expect(state.tlvframeStyle.backgroundPosition).toBe("0% 33.33333333333333%");
  });

  test("a dispatch playback landed on marks its own interaction over the video frame", () => {
    // In video mode the frame follows the run clock, so the frame under a dispatch entry IS that
    // dispatch's moment. Drawing the row's mark there would circle a target from a different
    // interaction than the one on screen.
    const payload = playbackPayload();
    payload.sessions[0].trace[0] = {
      ...payload.sessions[0].trace[0],
      mark: { kind: "tap", x: 10, y: 10, dw: 100, dh: 100 },
      children: [{
        label: "tapOn", tool: "tapOn keypad", ok: true, ms: 100, ts: 100150,
        screenshotFile: "k1.png", mark: { kind: "tap", x: 80, y: 80, dw: 100, dh: 100 },
      }],
    };
    payload.sessions[0].shots["k1.png"] = "data:image/png;base64,K1";
    payload.sessions[0].video = {
      sprites: [{ uri: "data:image/webp;base64,AAAA", rows: 4 }],
      fps: 2, frames: 4, columns: 1, rows: 4, frameHeight: 40,
      frameMap: [0, 1, 2, 3], startFrame: 0, endFrame: 3, startMs: 100000,
    };

    renderViewerState(payload, {
      drive: (ctx) => {
        ctx.play();
        ctx.advance(0);
        expect(ctx.html()).toContain('left:10%;top:10%'); // the play render draws the row's own mark
        ctx.advance(200); // the dispatch's real 150ms offset — video mode keeps the run clock
        expect(ctx.selectedSteps()).toEqual(["1"]); // still the same step...
        expect(ctx.scrubAttr("aria-valuetext")).toContain("Open app · tapOn"); // ...on its dispatch
        expect(ctx.paneMark()).toBe('<div class="mark tap" style="left:80%;top:80%"></div>');
      },
    });
  });

  test("Play resumes at the selected step even when the selection is not a playback stop", () => {
    // A dispatch whose screenshot never inlined is selectable in the list but is not a stop
    // playback can land on. Play has to resume from the nearest stop at or before it - restarting
    // the run from the top would throw the reader back to the beginning.
    const payload = playbackPayload();
    payload.sessions[0].trace[2] = {
      ...payload.sessions[0].trace[2],
      children: [{ label: "tapOn", tool: "tapOn keypad", ok: true, ms: 100, ts: 101100, screenshotFile: "k3.png" }],
    };
    // k3.png is deliberately absent from `shots`.
    renderViewerState(payload, {
      query: "?run=0&tab=timeline&step=3&kid=0",
      drive: (ctx) => {
        ctx.play();
        ctx.advance(0);
        expect(ctx.selectedSteps()).toEqual(["3"]);
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S3");
      },
    });
  });

  test("playback moves the dispatch highlight, not just the step highlight", () => {
    // The reader has to see WHICH interaction inside the step is on screen; a step-level highlight
    // alone leaves a four-tap step looking like it never moved.
    const payload = playbackPayload();
    payload.sessions[0].trace[0] = {
      ...payload.sessions[0].trace[0],
      children: [
        { label: "tapOn", tool: "tapOn keypad", ok: true, ms: 100, ts: 100150, screenshotFile: "k1.png" },
        { label: "tapOn", tool: "tapOn Transactions", ok: true, ms: 100, ts: 100300, screenshotFile: "k2.png" },
      ],
    };
    payload.sessions[0].shots["k1.png"] = "data:image/png;base64,K1";
    payload.sessions[0].shots["k2.png"] = "data:image/png;base64,K2";

    renderViewerState(payload, {
      drive: (ctx) => {
        ctx.play();
        ctx.advance(0);
        expect(ctx.selectedKids()).toEqual([]); // parked on the row itself
        ctx.advance(400);
        expect(ctx.selectedKids()).toEqual(["1:0"]);
        ctx.advance(350);
        expect(ctx.selectedKids()).toEqual(["1:1"]); // the highlight moves with the frame
        ctx.advance(350);
        expect(ctx.selectedSteps()).toEqual(["2"]);
        expect(ctx.selectedKids()).toEqual([]); // and clears when playback leaves the step
      },
    });
  });

  test("a live push mid-playback keeps the scrubber on the entry playback is showing", () => {
    // A running report replaces its trace array while the reader is playing it. Playback has to
    // resolve its rail position from the (step, kid) it just assigned: holding on to an entry object
    // from the pre-push model found it nowhere, and the scrubber fell back to item 0.
    const payload = playbackPayload();
    payload.sessions[0].trace[0] = {
      ...payload.sessions[0].trace[0],
      children: [
        { label: "tapOn", tool: "tapOn keypad", ok: true, ms: 100, ts: 100150, screenshotFile: "k1.png" },
        { label: "tapOn", tool: "tapOn Transactions", ok: true, ms: 100, ts: 100300, screenshotFile: "k2.png" },
      ],
    };
    payload.sessions[0].shots["k1.png"] = "data:image/png;base64,K1";
    payload.sessions[0].shots["k2.png"] = "data:image/png;base64,K2";

    renderViewerState(payload, {
      drive: (ctx) => {
        ctx.play();
        ctx.advance(400);
        expect(ctx.scrubAttr("aria-valuenow")).toBe("2"); // the row's first dispatch
        // Same rows, fresh objects — what a live push delivers.
        (globalThis as any).__TB_REPORT_LIVE__.update(0, { trace: payload.sessions[0].trace.map((t: any) => ({ ...t })) });
        ctx.advance(350);
        expect(ctx.scrubAttr("aria-valuenow")).toBe("3");
      },
    });
  });

  test("playback stops on every interaction a folded row absorbed, not just the row", () => {
    // The reported bug: a step that tapped several targets replayed as one frame, because playback
    // walked trace rows and never descended into the dispatches a traceId fold moved into children.
    const payload = playbackPayload();
    payload.sessions[0].trace[0] = {
      ...payload.sessions[0].trace[0],
      children: [
        { label: "tapOn", tool: "tapOn keypad", ok: true, ms: 100, ts: 100150, screenshotFile: "k1.png" },
        { label: "tapOn", tool: "tapOn Transactions", ok: true, ms: 100, ts: 100300, screenshotFile: "k2.png" },
      ],
    };
    payload.sessions[0].shots["k1.png"] = "data:image/png;base64,K1";
    payload.sessions[0].shots["k2.png"] = "data:image/png;base64,K2";

    renderViewerState(payload, {
      drive: (ctx) => {
        ctx.play();
        ctx.advance(0);
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S1"); // the row's own frame first
        // Gaps 150ms and 150ms both floor to the 350ms minimum, so each dispatch gets its own
        // visible dwell before the next row's entry at 700ms.
        ctx.advance(400);
        expect(ctx.selectedSteps()).toEqual(["1"]); // still inside step 1...
        expect(ctx.shotImg.src).toBe("data:image/png;base64,K1"); // ...on its first dispatch
        ctx.advance(350);
        expect(ctx.selectedSteps()).toEqual(["1"]);
        expect(ctx.shotImg.src).toBe("data:image/png;base64,K2"); // ...then its second
        ctx.advance(350);
        expect(ctx.selectedSteps()).toEqual(["2"]); // only then does the step advance
        expect(ctx.shotImg.src).toBe("data:image/png;base64,S2");
      },
    });
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
          expect(ctx.html()).toContain('aria-label="Stop timeline"');
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
    traceId: `llm-${n}`,
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

  const contextualTimelinePayload = () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the buy-review screen" }, timestamp: "2024-01-01T00:00:00Z" },
      { ...requestLog(turn1, 1), promptStep: { step: "Open the buy-review screen" }, timestamp: "2024-01-01T00:00:01Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Confirm the purchase" }, timestamp: "2024-01-01T00:00:02Z" },
      {
        class: `${T}.TrailblazeToolLog`,
        toolName: "assertVisibleBySelector",
        traceId: "failed-confirm",
        trailblazeTool: { raw: { text: "Confirm" } },
        successful: false,
        errorMessage: "Element not found: Confirm",
        screenshotFile: "confirm-failure.png",
        durationMs: 15000,
        timestamp: "2024-01-01T00:00:03Z",
      },
      { ...requestLog(turn2, 2), promptStep: { step: "Confirm the purchase" }, timestamp: "2024-01-01T00:00:04Z" },
    ];
    const rows = (core as any).extractLlmLogs(logs);
    const trace = (core as any).slimTraceForShare(core.extractTrace(logs));
    const failedObjective = trace.find((row: any) => row.objective && row.label === "Confirm the purchase");
    if (failedObjective) failedObjective.ok = false;
    return { generatedAt: "now", sessions: [{
      meta: { title: "Run", status: "failed" },
      trace,
      llm: (core as any).slimLlmForShare(rows),
      shots: { "confirm-failure.png": "data:image/png;base64,RkFJTEVELUZSQU1F" },
      recordingYaml: null,
      llmMessages: (core as any).extractLlmTranscripts(rows),
    }] };
  };

  const multiCallContextualPayload = () => {
    const logs = [
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the buy-review screen" }, timestamp: "2024-01-01T00:00:00Z" },
      { ...requestLog(turn1, 1), promptStep: { step: "Open the buy-review screen" }, timestamp: "2024-01-01T00:00:01Z" },
      { ...requestLog(turn2, 2), promptStep: { step: "Open the buy-review screen" }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Confirm the purchase" }, timestamp: "2024-01-01T00:00:03Z" },
      { ...requestLog(turn2, 4), promptStep: { step: "Confirm the purchase" }, timestamp: "2024-01-01T00:00:04Z" },
    ];
    const rows = (core as any).extractLlmLogs(logs);
    return { generatedAt: "now", sessions: [{
      meta: { title: "Run", status: "passed" },
      trace: (core as any).slimTraceForShare(core.extractTrace(logs)),
      llm: (core as any).slimLlmForShare(rows),
      shots: {},
      recordingYaml: null,
      llmMessages: (core as any).extractLlmTranscripts(rows),
    }] };
  };

  const transcriptNavButton = (panel: any, kind: "step" | "call", direction: "previous" | "next") =>
    panel.querySelector(`[data-tx-nav-kind="${kind}"][data-tx-nav-direction="${direction}"]`);

  const activateTranscriptNav = (panel: any, kind: "step" | "call", direction: "previous" | "next") => {
    const button = transcriptNavButton(panel, kind, direction);
    panel.onclick({ target: button, stopPropagation() {} });
    return button;
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

  test("the public payload keeps stable LLM ids while timeline transcript links remain positional", () => {
    const payload = timelinePayload();
    expect(payload.sessions[0].llm.map((call: any) => call.traceId)).toEqual(["llm-1", "llm-2"]);
    expect(payload.sessions[0].trace.every((row: any) => !("traceId" in row))).toBe(true);

    const out = renderViewer(payload, {});
    expect([...out.matchAll(/data-tx="(\d+)"/g)].map((match: any) => match[1]))
      .toEqual(["0", "1"]);
  });

  test("a transcript trigger opens the lightbox over the timeline without touching it", () => {
    const state = renderViewerState(timelinePayload(), { timelineScrollTop: 240, openTx: 1 });
    expect(state.zoomRoot.className).toBe("txoverlay");
    const panel = state.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("LLM transcript");
    expect(panel.innerHTML).toContain("STEP 1");
    expect(panel.innerHTML).toContain("Call 2 of 2");
    expect(panel.innerHTML).toContain('aria-label="Transcript navigation"');
    expect(panel.innerHTML).toContain("gpt-test");
    const body = panel.children[0];
    expect(body.innerHTML).toContain('aria-label="Current screen and step context"');
    expect(body.innerHTML).toContain("Do the thing");
    expect(body.innerHTML).toContain("Screen unavailable");
    expect(body.innerHTML).toContain('aria-label="Transcript for call 2"');
    expect(body.innerHTML).toContain("SCREEN-DUMP-MARKER");
    // No re-render underneath: a render would have reset the harness's timeline scroll to 0.
    expect(state.timelineScrollTop).toBe(240);
  });

  test("a failed call opens with its authored step, captured screen, and failure context", () => {
    const state = renderViewerState(contextualTimelinePayload(), { openTx: 1 });
    const panel = state.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("STEP 2");
    expect(panel.innerHTML).toContain("Call 1 of 1");
    const body = panel.children[0];
    expect(body.innerHTML).toContain("Confirm the purchase");
    expect(body.innerHTML).toContain("FAILED");
    expect(body.innerHTML).toContain("data:image/png;base64,RkFJTEVELUZSQU1F");
    expect(body.innerHTML).toContain("What failed");
    expect(body.innerHTML).toContain("Element not found");
    expect(body.innerHTML).toContain("assertVisibleBySelector");
  });

  test("a video-backed call shows its matching timeline frame instead of an empty screen rail", () => {
    const payload: any = contextualTimelinePayload();
    payload.sessions[0].shots = {};
    payload.sessions[0].video = {
      sprites: [{ uri: "data:image/webp;base64,VFJBTlNDUklQVC1GUkFNRQ==", rows: 4 }],
      fps: 1,
      frames: 4,
      columns: 1,
      rows: 4,
      frameWidth: 20,
      frameHeight: 40,
      frameMap: [0, 1, 2, 3],
      startFrame: 0,
      endFrame: 3,
      startMs: 1704067200000,
    };

    const state = renderViewerState(payload, { openTx: 1 });
    const body = state.zoomRoot.children[0].children[0];
    expect(body.innerHTML).toContain('class="txscreenvideo"');
    expect(body.innerHTML).toContain('aria-label="Screen at step 2, call 2"');
    expect(body.innerHTML).toContain('--tx-screen-aspect:20 / 40');
    expect(core.RUN_REPORT_CSS).toContain('width: min(100%, calc(52vh * var(--tx-screen-aspect, .461538)))');
    expect(body.innerHTML).not.toContain("Screen unavailable");
  });

  test("stepping the transcript into another step opens that step underneath the dialog", () => {
    // Transcript navigation moves the timeline selection too, and closing the dialog returns the
    // reader to that row. Without a reveal it lands inside a collapsed step it cannot see.
    const state = renderViewerState(multiCallContextualPayload(), { openTx: 0 });
    const panel = state.zoomRoot.children[0];
    const groupOf = (html: string, id: string) => new RegExp(`data-group="${id}" aria-expanded="(true|false)"`).exec(html)?.[1];
    const before = state.readHtml();
    const [first, second] = [...before.matchAll(/data-group="(\d+)"/g)].map((m) => m[1]);
    expect(groupOf(before, first)).toBe("true");
    expect(groupOf(before, second)).toBe("false");

    activateTranscriptNav(panel, "step", "next");
    const after = state.readHtml();
    expect(groupOf(after, second)).toBe("true");
    expect(groupOf(after, first)).toBe("false");
  });

  test("Step and Call navigation stay in their authored scopes, update the URL, and retain keyboard focus", () => {
    const state = renderViewerState(multiCallContextualPayload(), { openTx: 0 });
    const panel = state.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("STEP 1");
    expect(panel.innerHTML).toContain("Call 1 of 2");
    expect(transcriptNavButton(panel, "call", "previous").disabled).toBe(true);
    expect(transcriptNavButton(panel, "call", "next").dataset.txNav).toBe("1");
    expect(transcriptNavButton(panel, "step", "next").dataset.txNav).toBe("2");

    activateTranscriptNav(panel, "call", "next");
    expect(panel.innerHTML).toContain("STEP 1");
    expect(panel.innerHTML).toContain("Call 2 of 2");
    expect(transcriptNavButton(panel, "call", "next").disabled).toBe(true);
    expect(state.readActiveElement().dataset.txNavDirection).toBe("previous");
    expect(state.readRoute()).toContain("llm=1");

    activateTranscriptNav(panel, "step", "next");
    expect(panel.innerHTML).toContain("STEP 2");
    expect(panel.innerHTML).toContain("Call 1 of 1");
    expect(panel.children[0].innerHTML).toContain("Confirm the purchase");
    expect(transcriptNavButton(panel, "call", "previous").disabled).toBe(true);
    expect(transcriptNavButton(panel, "call", "next").disabled).toBe(true);
    expect(state.readActiveElement().dataset.txNavDirection).toBe("previous");
    expect(state.readRoute()).toContain("llm=2");

    activateTranscriptNav(panel, "step", "previous");
    expect(panel.innerHTML).toContain("STEP 1");
    expect(panel.children[0].innerHTML).toContain("Open the buy-review screen");
    expect(state.readRoute()).toContain("llm=0");
  });

  test("an unscoped legacy call does not claim that an unknown authored step passed", () => {
    const payload = { generatedAt: "now", sessions: [{ ...sessionBase(), trace: [], llmMessages: tx() }] };
    const state = renderViewerState(payload, { tab: "llm", openTx: 0 });
    const panel = state.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("UNSCOPED");
    expect(panel.children[0].innerHTML).not.toContain("PASSED");
  });

  test("a scoped call in a running session does not claim its unfinished step passed", () => {
    const payload = timelinePayload();
    payload.sessions[0].meta.status = "running";
    const state = renderViewerState(payload, { openTx: 0 });
    const panel = state.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("STEP 1");
    expect(panel.children[0].innerHTML).not.toContain("PASSED");
  });

  test("an open transcript refreshes its live header and step context without losing focus", () => {
    const payload = timelinePayload();
    const state = renderViewerState(payload, { openTx: 0 });
    const panel = state.zoomRoot.children[0];
    const nextCall = transcriptNavButton(panel, "call", "next");
    nextCall.focus();
    const body = panel.children[0];
    body.scrollTop = 111;
    body.querySelector(".txcontext").scrollTop = 22;
    body.querySelector(".txconversation").scrollTop = 333;

    const updated = structuredClone(payload.sessions[0]);
    updated.llm[0].inputTokens = 999;
    const objective = updated.trace.find((row: any) => row.objective);
    objective.label = "Updated live objective";
    state.live()!.update(0, updated);

    expect(panel.innerHTML).toContain("in 999");
    const refreshedBody = panel.children[0];
    expect(refreshedBody.innerHTML).toContain("Updated live objective");
    expect(refreshedBody.scrollTop).toBe(111);
    expect(refreshedBody.querySelector(".txcontext").scrollTop).toBe(22);
    expect(refreshedBody.querySelector(".txconversation").scrollTop).toBe(333);
    expect(state.readActiveElement().dataset.txNavKind).toBe("call");
    expect(state.readActiveElement().dataset.txNavDirection).toBe("next");
  });

  test("a live transcript refresh preserves focus on a conversation disclosure", () => {
    const payload = timelinePayload();
    const state = renderViewerState(payload, { openTx: 0 });
    const panel = state.zoomRoot.children[0];
    const disclosure = panel.children[0].querySelector(".txsummary-test");
    disclosure.focus();

    const updated = structuredClone(payload.sessions[0]);
    updated.llm[0].outputTokens = 77;
    state.live()!.update(0, updated);

    const refreshedDisclosure = panel.children[0].querySelector(".txsummary-test");
    expect(refreshedDisclosure).not.toBe(disclosure);
    expect(state.readActiveElement()).toBe(refreshedDisclosure);
  });

  test("an open transcript stays on the same LLM request when a late request sorts before it", () => {
    const payload: any = timelinePayload();
    payload.sessions[0].llm[1].model = "selected-model";
    const state = renderViewerState(payload, { openTx: 1 });
    const panel = state.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("selected-model");

    const late = structuredClone(payload.sessions[0]);
    late.llm.unshift({ ...late.llm[0], traceId: "late-earlier-call", model: "late-model" });
    late.llmMessages.calls.unshift([]);
    late.trace.forEach((row: any) => { if (row.llm != null) row.llm += 1; });
    state.live()!.update(0, late);

    expect(panel.innerHTML).toContain("selected-model");
    expect(panel.innerHTML).not.toContain("late-model");
    expect(state.readRoute()).toContain("llm=2");
  });

  test("Step navigation excludes an unscoped legacy call while direct access remains available", () => {
    const logs = [
      { ...requestLog(turn1, 0), timestamp: "2024-01-01T00:00:00Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Open the buy-review screen" }, timestamp: "2024-01-01T00:00:01Z" },
      { ...requestLog(turn1, 1), promptStep: { step: "Open the buy-review screen" }, timestamp: "2024-01-01T00:00:02Z" },
      { class: `${T}.ObjectiveStartLog`, promptStep: { step: "Confirm the purchase" }, timestamp: "2024-01-01T00:00:03Z" },
      { ...requestLog(turn2, 2), promptStep: { step: "Confirm the purchase" }, timestamp: "2024-01-01T00:00:04Z" },
    ];
    const rows = (core as any).extractLlmLogs(logs);
    const payload = { generatedAt: "now", sessions: [{
      meta: { title: "Run", status: "passed" },
      trace: (core as any).slimTraceForShare(core.extractTrace(logs)),
      llm: (core as any).slimLlmForShare(rows),
      shots: {},
      recordingYaml: null,
      llmMessages: (core as any).extractLlmTranscripts(rows),
    }] };

    const unscoped = renderViewerState(payload, { openTx: 0 });
    expect(unscoped.zoomRoot.children[0].innerHTML).toContain("UNSCOPED");

    const authored = renderViewerState(payload, { openTx: 1 });
    const panel = authored.zoomRoot.children[0];
    expect(panel.innerHTML).toContain("STEP 1");
    expect(transcriptNavButton(panel, "step", "previous").disabled).toBe(true);
    expect(transcriptNavButton(panel, "step", "next").dataset.txNav).toBe("2");
  });

  test("navigating a transcript from the LLM table returns focus to the newly selected request", () => {
    const state = renderViewerState(timelinePayload(), { query: "?run=0&tab=llm", openTx: 0 });
    const panel = state.zoomRoot.children[0];
    activateTranscriptNav(panel, "call", "next");
    expect(state.readRoute()).toContain("llm=1");
    state.escapeOverlay();
    expect(state.readRestoredFocus()).toBe('[data-llm="1"]');
  });

  test("a transcript pushed from the timeline is a browser history destination", () => {
    const opened = renderViewerState(timelinePayload(), { openTx: 1 });
    expect(opened.routeWrites().at(-1)).toEqual({
      method: "push",
      next: expect.stringContaining("llm=1"),
    });

    const returned = renderViewerState(timelinePayload(), {
      query: "?run=0&tab=timeline&step=1",
      openTx: 1,
      popstate: "?run=0&tab=timeline&step=1",
    });
    expect(returned.zoomRoot.removed).toBe(true);
    expect(returned.route).toBe("/report.html?run=0&tab=timeline&step=1");
    expect(returned.pageClass()).toBe("page-enter-back");
  });

  test("Escape closes the lightbox, returns focus to the trigger, and leaves the view untouched", () => {
    const state = renderViewerState(timelinePayload(), { timelineScrollTop: 240, openTx: 0, txEscape: true });
    expect(state.restoredFocus).toBe('[data-tx="0"]');
    expect(state.timelineScrollTop).toBe(240);
    expect(state.routeWrites().at(-1)).toEqual({ method: "back", next: expect.not.stringContaining("llm=") });
    expect(state.pageClass()).toBe("page-enter-back");
    expect(state.pageClassWrites().slice(-2)).toEqual(["", "page-enter-back"]);
  });

  test("repeated Escape cannot consume a second history entry while transcript Back is pending", () => {
    const state = renderViewerState(timelinePayload(), { openTx: 0, deferHistoryBack: true });
    state.escapeOverlay();
    state.escapeOverlay();
    expect(state.routeWrites().filter((write) => write.method === "back")).toHaveLength(1);
    expect(state.liveZoomRoot().removed).toBe(false);

    state.flushHistoryBack();
    expect(state.liveZoomRoot().removed).toBe(true);
    expect(state.readRoute()).not.toContain("llm=");
  });

  test("browser Forward restores transcript history ownership so Escape returns with Back", () => {
    const state = renderViewerState(timelinePayload(), { openTx: 0 });
    state.historyBack();
    expect(state.liveZoomRoot().removed).toBe(true);

    state.historyForward();
    expect(state.liveZoomRoot().removed).toBe(false);
    expect(state.readRoute()).toContain("llm=0");
    state.escapeOverlay();
    expect(state.routeWrites().at(-1)?.method).toBe("back");
    expect(state.readRoute()).not.toContain("llm=");
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
    expect(body.innerHTML).toContain('class="txhead user-authored"');
    expect(body.innerHTML).toContain('<span class="txrole user">Trailblaze</span>');
    expect(body.innerHTML).toMatch(/voice-user"[^]*?class="txhead user-authored"[^]*?Tool result/);
    expect(core.RUN_REPORT_CSS).toContain('.txmsg .txhead { display: flex; align-items: center;');
    expect(core.RUN_REPORT_CSS).toContain('.txmsg .txhead.user-authored { justify-content: flex-end; }');
    expect(core.RUN_REPORT_CSS).toContain('.txmsg summary.user-authored .txrole { order: 2; margin-left: auto; }');
    expect(core.RUN_REPORT_CSS).toContain('.txmsg.voice-llm pre, .txmsg.voice-user pre, .txmsg.voice-sys pre { background: transparent; }');
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
    expect(state.routeWrites().some((write) => write.method === "back")).toBe(false);
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

  test("the device preview offers one contextual Inspect UI action for the selected step", () => {
    const payload = inspectorPayload();
    const tapStep = stepOf(payload, "tapOnElement");
    const inputStep = stepOf(payload, "inputText");
    const inspectable = renderViewer(payload, { step: tapStep });
    expect([...inspectable.matchAll(/data-inspect="(\d+)"/g)].map((match) => Number(match[1]))).toEqual([tapStep]);
    expect(inspectable).toContain('class="btn previewinspect" data-preview-inspect');
    expect(inspectable).not.toContain('class="inspectlink"');
    const unavailable = renderViewer(payload, { step: inputStep });
    expect(unavailable).toContain('class="btn previewinspect" data-preview-inspect disabled');
    expect(unavailable).toContain('class="previewinspecticon"');
    expect(unavailable).not.toContain('data-inspect=');
    // Video-backed rows and hierarchy-backed screenshots share the same vertical budget, so
    // moving between enabled and disabled Inspect UI states cannot resize the device frame.
    expect(core.RUN_REPORT_CSS).toContain(".tlvframe { max-width: 100%; height: calc(100vh - 372px);");
    expect(core.RUN_REPORT_CSS).toContain(".timelinemain .devicecolumn.hasinspect .shotwrap, .timelinemain .devicecolumn.hasinspect .shot { max-height: calc(100vh - 372px);");
    expect(core.RUN_REPORT_CSS).not.toContain("height: calc(100vh - 386px)");
  });

  test("an inspectable row keeps its hierarchy screenshot in the static preview when video exists", () => {
    const payload = inspectorPayload();
    const session = payload.sessions[0];
    const tapStep = stepOf(payload, "tapOnElement");
    const tap = session.trace.find((t: any) => t.i === tapStep);
    session.video = {
      sprites: [{ uri: "data:image/webp;base64,VIDEO", rows: 1 }],
      fps: 1,
      frames: 1,
      columns: 1,
      rows: 1,
      frameMap: [0],
      startFrame: 0,
      endFrame: 0,
      startMs: tap.ts,
    };

    const state = renderViewerState(payload, { step: tapStep, inspect: tapStep });
    expect(state.html).toContain('id="shot"');
    expect(state.shotImg.src).toBe(shots["a.png"]);
    expect(state.html).not.toContain('id="tlvframe"');
    expect(state.zoomRoot.querySelector('[data-insphit]').querySelector('img').src).toBe(shots["a.png"]);
  });

  test("an inspectable LLM row keeps its transcript beside the row and inspection under the device", () => {
    const payload = inspectorPayload();
    const tapStep = stepOf(payload, "tapOnElement");
    const session = payload.sessions[0];
    session.trace.find((t: any) => t.i === tapStep).llm = 0;
    session.llm = [{ model: "gpt-test", inputTokens: 10, outputTokens: 5, response: [] }];
    const html = renderViewer(payload, { step: tapStep });
    expect(html).toContain('data-tx="0"');
    expect([...html.matchAll(/data-inspect="(\d+)"/g)].map((match) => Number(match[1]))).toEqual([tapStep]);
    expect(html).toMatch(/<div class="steprow">[\s\S]*?<button[^>]*data-tx="0"/);
    expect(html.indexOf('data-inspect="')).toBeGreaterThan(html.indexOf('class="preview"'));
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
    expect(overlay.querySelector('[data-insphit]').querySelector('img').src).toBe(shots["a.png"]);
  });

  test("the Inspector is a browser history destination and Back returns to the selected tool", () => {
    const payload = inspectorPayload();
    const step = stepOf(payload, "tapOnElement");
    const opened = renderViewerState(payload, { inspect: step });
    expect(opened.routeWrites().at(-1)).toEqual({
      method: "push",
      next: expect.stringContaining(`inspect=${step}`),
    });

    const returned = renderViewerState(payload, {
      query: `?run=0&tab=timeline&step=${step}`,
      inspect: step,
      popstate: `?run=0&tab=timeline&step=${step}`,
    });
    expect(returned.zoomRoot.removed).toBe(true);
    expect(returned.route).toBe(`/report.html?run=0&tab=timeline&step=${step}`);
    expect(returned.pageClass()).toBe("page-enter-back");
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
    const state = renderViewerState(payload, { inspect: step, inspectEscape: true });
    expect(state.zoomRoot.removed).toBe(true);
    expect(state.readRestoredFocus()).toBe(`[data-inspect="${step}"]`);
    expect(state.routeWrites().at(-1)).toEqual({ method: "back", next: expect.not.stringContaining("inspect=") });
    expect(state.pageClass()).toBe("page-enter-back");
  });

  test("repeated Escape cannot consume a second history entry while Inspector Back is pending", () => {
    const payload = inspectorPayload();
    const step = stepOf(payload, "tapOnElement");
    const state = renderViewerState(payload, { inspect: step, deferHistoryBack: true });
    state.escapeOverlay();
    state.escapeOverlay();
    expect(state.routeWrites().filter((write) => write.method === "back")).toHaveLength(1);
    expect(state.liveZoomRoot().removed).toBe(false);

    state.flushHistoryBack();
    expect(state.liveZoomRoot().removed).toBe(true);
    expect(state.readRoute()).not.toContain("inspect=");
  });

  test("browser Forward restores Inspector history ownership so Escape returns with Back", () => {
    const payload = inspectorPayload();
    const step = stepOf(payload, "tapOnElement");
    const state = renderViewerState(payload, { inspect: step });
    state.historyBack();
    expect(state.liveZoomRoot().removed).toBe(true);

    state.historyForward();
    expect(state.liveZoomRoot().removed).toBe(false);
    expect(state.readRoute()).toContain(`inspect=${step}`);
    state.escapeOverlay();
    expect(state.routeWrites().at(-1)?.method).toBe("back");
    expect(state.readRoute()).not.toContain("inspect=");
  });

  test("closing a deep-linked inspector removes its route in place instead of popping history", () => {
    const payload = inspectorPayload();
    const step = stepOf(payload, "tapOnElement");
    const state = renderViewerState(payload, {
      query: `?run=0&tab=timeline&step=${step}&inspect=${step}`,
      inspectEscape: true,
    });
    expect(state.zoomRoot.removed).toBe(true);
    expect(state.route).toBe(`/report.html?run=0&tab=timeline&step=${step}`);
    expect(state.routeWrites().some((write) => write.method === "back")).toBe(false);
  });

  test("the inspector wraps Tab focus at both ends of its modal keyboard boundary", () => {
    const payload = inspectorPayload();
    const state = renderViewerState(payload, { inspect: stepOf(payload, "tapOnElement") });
    const focusables = state.zoomRoot.querySelectorAll('button, [href], summary, [tabindex]:not([tabindex="-1"])');
    expect(focusables.length).toBeGreaterThanOrEqual(2);
    const first = focusables[0];
    const last = focusables[focusables.length - 1];
    last.focus();
    let preventedForward = false;
    state.zoomRoot.onkeydown({ key: "Tab", shiftKey: false, preventDefault() { preventedForward = true; }, stopPropagation() {} });
    expect(preventedForward).toBe(true);
    expect(state.readActiveElement()).toBe(first);
    first.focus();
    let preventedBackward = false;
    state.zoomRoot.onkeydown({ key: "Tab", shiftKey: true, preventDefault() { preventedBackward = true; }, stopPropagation() {} });
    expect(preventedBackward).toBe(true);
    expect(state.readActiveElement()).toBe(last);
    expect(state.zoomRoot.removed).toBe(false);
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

describe("Trail view (the same trail across devices, one lane per run)", () => {
  const trailRow = (i: number, extra: Record<string, unknown> = {}) => ({ i, label: `row ${i}`, tool: "t", note: null, ms: 0, ts: null, ok: true, err: null, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [], ...extra });
  // Lane A runs the whole trail; its "Sign in" step captures two frames.
  const laneATrace = [
    trailRow(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000 }),
    trailRow(2, { ts: 1000, ms: 500, screenshotFile: "a-prep.webp" }),
    trailRow(3, { objective: true, label: "Sign in", ts: 2000 }),
    trailRow(4, { ts: 2000, ms: 3000, screenshotFile: "a-signin-1.webp" }),
    trailRow(5, { ts: 5000, ms: 1000, screenshotFile: "a-signin-2.webp" }),
  ];
  // Lane B (a different device, its own clock) fails during the trailhead and never reaches "Sign in".
  const laneBTrace = [
    trailRow(1, { objective: true, trailhead: true, label: "Prepare", ts: 90000, ok: false }),
    trailRow(2, { ts: 90000, ms: 800, ok: false, screenshotFile: "b-prep.webp" }),
  ];
  const shotsFor = (trace: Array<Record<string, unknown>>) => Object.fromEntries(
    trace.filter((r) => r.screenshotFile).map((r) => [r.screenshotFile, "data:image/webp;base64,AAAA"]));
  const run = (deviceClassifier: string, trace: Array<Record<string, unknown>>, extraMeta: Record<string, unknown> = {}) => ({
    meta: { title: "Checkout", status: trace.every((r) => r.ok) ? "passed" : "failed", trailId: "checkout/pay", platform: "android", deviceClassifier, ...extraMeta },
    trace, llm: [], shots: shotsFor(trace), recordingYaml: null,
  });
  const payload = { generatedAt: "now", sessions: [run("android-phone", laneATrace), run("ios-ipad", laneBTrace, { platform: "ios" })] };

  // A report holding several trails offers the view per TRAIL. Each row opens its own comparison;
  // only the header button — which names no trail — needs the whole document to be one trail.
  const mixed = { generatedAt: "now", sessions: [run("android-phone", laneATrace), { ...run("ios-ipad", laneBTrace), meta: { title: "Refunds", status: "failed", trailId: "refunds/full", platform: "ios", deviceClassifier: "ios-ipad" } }] };

  test("the run index offers the Trail view per trail, and the header button only for a single-trail report", () => {
    // One trail on two devices: the row opens it, and so does the header, which can only mean this.
    const single = renderViewer(payload, { query: "?view=runs" });
    expect(single).toContain('class="nm nmtrail" type="button" data-goto-trail="trail:checkout%2Fpay:"');
    expect(single).toContain('class="btn" type="button" data-goto-trail="trail:checkout%2Fpay:"');

    // Two trails: both rows are still entry points — each is a real comparison — but there is no
    // one trail a document-wide button could mean, so it is absent rather than picking one.
    const many = renderViewer(mixed, { query: "?view=runs" });
    expect(many).toContain('class="nm nmtrail" type="button" data-goto-trail="trail:checkout%2Fpay:"');
    expect(many).toContain('class="nm nmtrail" type="button" data-goto-trail="trail:refunds%2Ffull:"');
    expect(many).not.toContain('class="btn" type="button" data-goto-trail');
  });

  test("a row opens ITS trail, not whichever trail the document leads with", () => {
    // Refunds is the second run. Scoping is the whole point of the per-trail entry point: without
    // it the view would stage session 0's trail under the row the reader actually clicked.
    const state = renderViewerState(mixed, { query: "?view=runs", gotoTrail: "trail:refunds%2Ffull:" });
    const trail = state.readHtml();
    expect(trail).toContain('class="trailcanvas"');
    expect(trail).toContain("<h1>Refunds</h1>");
    expect(trail).not.toContain("<h1>Checkout</h1>");
    // The route carries the scope so a copied link opens the same comparison.
    expect(state.readRoute()).toContain("trail=trail%3Arefunds%252Ffull%3A");
  });

  test("the Map's start card names the scoped trail, not the document's first run", () => {
    // The card introduces the stage. Naming session 0's trail there would caption a comparison of
    // Refunds with the word Checkout, beside lanes and steps that are all Refunds.
    const map = renderViewerState(mixed, { query: "?view=runs", gotoTrail: "trail:refunds%2Ffull:" }).readHtml();
    const startCard = map.slice(map.indexOf("wpstart"), map.indexOf("wpstart") + 700);
    expect(startCard).toContain("Refunds");
    expect(startCard).not.toContain("Checkout");
  });

  test("the entry point promises a comparison only when the trail has more than one lane", () => {
    // The common CI report is many trails on one device each, so a blanket "compare across
    // devices" would promise a comparison that cannot exist on every row in it.
    const many = renderViewer(mixed, { query: "?view=runs" });
    expect(many).toContain('data-goto-trail="trail:refunds%2Ffull:" title="See this run as a trail');
    // Two devices on one trail is the real comparison, and says so.
    expect(renderViewer(payload, { query: "?view=runs" })).toContain('data-goto-trail="trail:checkout%2Fpay:" title="Compare this trail across devices');
  });

  test("a trail link naming no trail falls back to the index on a many-trail report", () => {
    // Pre-scoping links, and any hand-typed ?view=trail. There is no document trail to resolve
    // them to, and staging an arbitrary one would be a broken join.
    expect(renderViewer(mixed, { query: "?view=trail" })).toContain('class="idxsummary"');
    // A scope naming a trail this report does not hold lands there too.
    expect(renderViewer(mixed, { query: "?view=trail&trail=trail%3Ano%2Fsuch%3A" })).toContain('class="idxsummary"');
  });

  test("a device that was skipped does not take the Trail view away from the devices that ran", () => {
    // A skip is a link-out stub with no trace, so counted as a run it fails every condition the
    // view needs. The runs are still the same trail as each other; the reader would lose the
    // comparison because one device was configured not to take part in it.
    const withSkip = {
      generatedAt: "now",
      sessions: [
        run("android-phone", laneATrace),
        run("ios-ipad", laneBTrace, { platform: "ios" }),
        { meta: { title: "Checkout", status: "skipped", trailId: "checkout/pay", platform: "ios", deviceClassifier: "ios-tablet", linkOut: true, skipReason: "no tablet fixture" }, trace: [], llm: [], shots: {}, recordingYaml: null },
      ],
    };
    expect(renderViewer(withSkip, { query: "?view=runs" })).toContain("data-goto-trail");
    expect(renderViewer(withSkip, { query: "?view=trail" })).toContain('class="trailcanvas"');
  });

  // A stage the reader builds by hand, out of whichever runs they want to see together — the trail
  // scopes above are the report's own groupings, and a reader comparing two of yesterday's failures
  // is not asking for either of them.
  describe("runs picked by hand", () => {
    // Two runs carrying no trailId at all, so their identity is their title — the one identity the
    // report never lets stand for a shared trail.
    const sameTitle = { generatedAt: "now", sessions: [
      { ...run("android-phone", laneATrace), meta: { title: "Checkout", status: "passed", platform: "android", deviceClassifier: "android-phone" } },
      { ...run("ios-ipad", laneBTrace), meta: { title: "Checkout", status: "failed", platform: "ios", deviceClassifier: "ios-ipad" } },
    ] };
    const withSkip = {
      generatedAt: "now",
      sessions: [
        run("android-phone", laneATrace),
        run("ios-ipad", laneBTrace, { platform: "ios" }),
        { meta: { title: "Checkout", status: "skipped", trailId: "checkout/pay", platform: "ios", deviceClassifier: "ios-tablet", linkOut: true, skipReason: "no tablet fixture" }, trace: [], llm: [], shots: {}, recordingYaml: null },
      ],
    };

    test("every run this report can stage offers a checkbox; the ones it can't hold the gutter open instead", () => {
      const out = renderViewer(withSkip, { query: "?view=runs" });
      expect(out).toContain('data-pick="0"');
      expect(out).toContain('data-pick="1"');
      // The skipped link-out has no trace here to lane, so offering it would be offering a lane
      // that gets dropped the moment it is opened.
      expect(out).not.toContain('data-pick="2"');
      expect(out).toContain("idxpickempty");
      // Nothing picked yet, so no bar over the index.
      expect(out).not.toContain('class="pickbar"');
    });

    test("picking runs of different trails stages them side by side, with no step join and no Map", () => {
      const state = renderViewerState(mixed, { query: "?view=runs", pick: [0, 1] });
      // The bar says what opening the selection will actually give them.
      const index = state.readHtml();
      expect(index).toContain('class="pickbar"');
      expect(index).toContain("<strong>2</strong> selected");
      expect(index).toContain("different trails — shown side by side");

      const stage = renderViewerState(mixed, { query: "?view=runs", pick: [0, 1], pickOpen: true }).readHtml();
      expect(stage).toContain('class="trailgrid"');
      // Named by what it IS. Either trail's title would caption the other one's lane with it.
      expect(stage).toContain("<h1>2 selected runs</h1>");
      // Both runs are on stage, each still its own device.
      expect(stage).toContain("android-phone");
      expect(stage).toContain("ios-ipad");
      // The Map draws lanes leaving one shared step. These lanes share none, so the projection that
      // would claim they do is not offered at all.
      expect(stage).not.toContain('data-trail-mode="map"');
      expect(stage).toContain('data-trail-mode="steps" aria-pressed="true"');
      // Positional neighbours: row 1 holds each lane's own first step, so the row carries no
      // authored label — that would read as "both of these are step 1 of the same thing" — and
      // every cell keeps its own wording instead.
      expect(stage).toContain('<div class="trailsteplabel"></div>');
      expect(stage).toContain('class="trailvariant"');
      // And the stage says what it is, rather than claiming one trail across the lanes.
      expect(stage).toContain("different trails, side by side");
      expect(stage).not.toContain("same trail, one lane per run");
      // A lane that simply had fewer steps ran out of them; it did not fail to reach a step the
      // other lane defines, because there is no shared step for it to have missed.
      expect(stage).toContain("no step here");
      expect(stage).not.toContain("not reached");
    });

    test("picking runs of one trail keeps the step join the trail scopes have", () => {
      const state = renderViewerState(payload, { query: "?view=runs", pick: [0, 1] });
      expect(state.readHtml()).toContain("one trail — lanes line up step by step");
      const stage = renderViewerState(payload, { query: "?view=runs", pick: [0, 1], pickOpen: true }).readHtml();
      expect(stage).toContain("<h1>Checkout</h1>");
      expect(stage).toContain('data-trail-mode="map"');
      // The authored step spine both runs share, which is the thing a comparison is read across.
      expect(stage).toContain("Sign in");
    });

    test("the picked runs travel in the link, and an index this report doesn't have is dropped", () => {
      const state = renderViewerState(mixed, { query: "?view=runs", pick: [0, 1], pickOpen: true });
      expect(state.readRoute()).toContain("pick=0%2C1");
      // The same link, opened cold.
      expect(renderViewer(mixed, { query: "?view=trail&pick=0,1" })).toContain("<h1>2 selected runs</h1>");
      // A report regenerated with fewer runs still opens on the ones it kept…
      expect(renderViewer(mixed, { query: "?view=trail&pick=1,7" })).toContain("<h1>Refunds</h1>");
      // …and a link naming nothing this report has falls back to the index rather than an empty stage.
      expect(renderViewer(mixed, { query: "?view=trail&pick=7" })).toContain('class="idxsummary"');
      // A link asking for the Map by name doesn't get it either — the projection that would claim
      // one shared spine isn't reachable for a stage that has none.
      const asked = renderViewer(mixed, { query: "?view=trail&pick=0,1&mode=map" });
      expect(asked).toContain('class="trailgrid"');
      expect(asked).not.toContain("wpnotreached");
    });

    test("a link naming a run this report can't stage leaves it off the stage, not on it as an empty lane", () => {
      // Hand-edited links and links from a report that later gained a skip. The runs that CAN be
      // staged are still staged; the skip is simply not one of them.
      const stage = renderViewer(withSkip, { query: "?view=trail&pick=0,2" });
      expect(stage).toContain('class="trailcanvas"');
      expect(stage).toContain("android-phone");
      expect(stage).not.toContain("ios-tablet");
    });

    test("each run's line closes around its own row, so a row can't swallow the next one", () => {
      // The checkbox lives in a wrapper around the row. An unclosed wrapper nests every following
      // row inside the first one — a layout collapse no attribute assertion would notice.
      const flat = { generatedAt: "now", sessions: [run("android-phone", laneATrace), { ...run("android-phone", laneBTrace), meta: { title: "Refunds", status: "failed", trailId: "refunds/full", platform: "android", deviceClassifier: "android-phone" } }] };
      const out = renderViewer(flat, { query: "?view=runs" });
      expect(out.split('<div class="idxrowline"')).toHaveLength(3);
      // The line is also what the search filter walks and hides, so it carries the entry marker the
      // row itself used to — a filter that can't find an entry silently stops filtering.
      expect(out).toContain('<div class="idxrowline" data-run-entry data-search=');
      expect(out.split("<div").length).toBe(out.split("</div>").length);
    });

    test("a link-out and a skip are each unpickable on their own, not only when a run is both", () => {
      // Two separate reasons a run can't be staged, so both have to be checked apart: a link-out's
      // trace lives in another report, and a skip ran nothing at all. One fixture that is both
      // would pass even if the code only ever tested one of them.
      const spread = { generatedAt: "now", sessions: [
        run("android-phone", laneATrace),
        { meta: { title: "Checkout elsewhere", status: "passed", trailId: "checkout/elsewhere", platform: "android", deviceClassifier: "android-phone", linkOut: true }, trace: [], llm: [], shots: {}, recordingYaml: null },
        { meta: { title: "Refunds", status: "skipped", trailId: "refunds/full", platform: "android", deviceClassifier: "android-phone", skipReason: "no fixture" }, trace: [], llm: [], shots: {}, recordingYaml: null },
      ] };
      const out = renderViewer(spread, { query: "?view=runs" });
      expect(out).toContain('data-pick="0"');
      expect(out).not.toContain('data-pick="1"');
      expect(out).not.toContain('data-pick="2"');
      // Both still hold the gutter open, so the rows beside them don't step sideways.
      expect(out.split("idxpickempty").length - 1).toBe(2);
    });

    test("a run reached through a device cell carries its own checkbox, in its own cell", () => {
      // The matrix layout is where a multi-device trail is picked from, and a cell's checkbox has to
      // belong to THAT cell's run — the columns are what the reader is choosing between.
      const out = renderViewer(payload, { query: "?view=runs" });
      expect(cellPicks(out, "idxcell passed", 0)).toBe(true);
      expect(cellPicks(out, "idxcell failed", 1)).toBe(true);
      // Not the neighbour's: each cell offers the run that cell opens.
      expect(cellPicks(out, "idxcell passed", 1)).toBe(false);
      // And it stays outside the control that opens the run.
      expect(cellOpens(out, "idxcell passed", 0)).toBe(true);
    });

    test("a link carrying both a pick and a trail opens the pick, and stops carrying the trail", () => {
      // They name different stages, so one has to win. The pick does: the reader chose those runs,
      // where a trail scope is only the grouping the report happened to offer.
      const state = renderViewerState(mixed, { query: "?view=trail&trail=trail:refunds%2Ffull:&pick=0,1" });
      expect(state.readHtml()).toContain("<h1>2 selected runs</h1>");
      expect(state.readRoute()).toContain("pick=0%2C1");
      expect(state.readRoute()).not.toContain("trail=");
    });

    test("a link naming only runs this report can't stage falls back rather than staging nothing", () => {
      // Not an empty Trail view: a stage with no lanes says nothing, and writeRoute would keep
      // re-emitting the same dead indices, so a reload reproduces it forever. Two trails here, so
      // there is no document-wide trail to fall back to either — the index is where it lands.
      const skipOnly = { generatedAt: "now", sessions: [...mixed.sessions, { meta: { title: "Payouts", status: "skipped", trailId: "payouts/daily", platform: "android", deviceClassifier: "android-phone", skipReason: "no fixture" }, trace: [], llm: [], shots: {}, recordingYaml: null }] };
      const state = renderViewerState(skipOnly, { query: "?view=trail&pick=2" });
      expect(state.readHtml()).toContain('class="idxsummary"');
      expect(state.readHtml()).not.toContain("selected run");
      // And the dead index stops travelling: the route the viewer writes back no longer carries it.
      expect(state.readRoute()).not.toContain("pick=");
    });

    test("a repeated index in a link is one lane, and the link is rewritten in document order", () => {
      // Clicking dedupes and sorts; a hand-written or hand-edited link has to land in the same
      // place. Lane chips are keyed by session, so two lanes for one run would share one chip.
      const state = renderViewerState(mixed, { query: "?view=trail&pick=1,0,1" });
      expect(state.readHtml()).toContain("<h1>2 selected runs</h1>");
      expect(state.readHtml().match(/data-trail-lane="1"/g)).toHaveLength(1);
      expect(state.readRoute()).toContain("pick=0%2C1");
    });

    test("coming back from a picked link finds the runs still ticked", () => {
      // The link carries the stage; the checkboxes behind it have to agree, or Back lands on an
      // index showing nothing selected and the reader re-picks what they just came from.
      const index = renderViewerState(mixed, { query: "?view=trail&pick=0,1", back: true }).readHtml();
      expect(index).toContain('class="pickbar"');
      expect(index).toContain("<strong>2</strong> selected");
      expect(index).toContain('data-pick="0" checked');
    });

    test("opening a run out of a picked stage and pressing Back returns to the stage", () => {
      // A pick has no trail identity, so the "is there a stage to go back to?" question can't be
      // asked of the trail scope — ask it that way and the reader loses the set they assembled.
      const state = renderViewerState(mixed, { query: "?view=trail&pick=0,1", trailOpen: "0:1", back: true });
      expect(state.readHtml()).toContain("<h1>2 selected runs</h1>");
    });

    test("a picked link opened cold on a still-streaming report waits for its lanes, then stages them", async () => {
      // The traces a picked stage needs are parsed per run, so a shared link can land before any of
      // them exist. A pick has no trail identity to re-derive on arrival — the stage resolves only
      // if the token the wait was filed under is the same string the state carries, which is why
      // both sides must be spelled once.
      const chunked = core.buildMultiReportHtml({
        generatedAt: "now",
        sessions: mixed.sessions.map((s) => ({ meta: s.meta, trace: s.trace, llmLogs: [], shots: s.shots })),
      });
      const state = renderViewerState(null, { chunks: chunksOf(chunked), holdChunks: [0, 1], query: "?view=trail&pick=0,1" });
      expect(state.html).not.toContain('class="trailgrid"');
      state.releaseChunks();
      for (let i = 0; i < 100 && !state.readHtml().includes("trailgrid"); i++) await new Promise((resolve) => setTimeout(resolve, 10));
      expect(state.readHtml()).toContain("<h1>2 selected runs</h1>");
      expect(state.readHtml()).toContain("android-phone");
      expect(state.readHtml()).toContain("ios-ipad");
    });

    test("changing the selection while its traces are still arriving stages the run it just gained", async () => {
      // Two waits, one per selection, and the second names a set the first does not. A token that
      // only counted the runs would read the new selection as the one already in flight, file no
      // wait for the run it gained, and leave that lane empty for good.
      const settle = [trailRow(1, { objective: true, trailhead: true, label: "Settle up", ts: 1000 }), trailRow(2, { ts: 1000, ms: 200 })];
      const three = [...mixed.sessions, run("android-tablet", settle, { title: "Payouts", trailId: "payouts/daily" })];
      const chunked = core.buildMultiReportHtml({
        generatedAt: "now",
        sessions: three.map((s) => ({ meta: s.meta, trace: s.trace, llmLogs: [], shots: s.shots })),
      });
      const state = renderViewerState(null, { chunks: chunksOf(chunked), holdChunks: [0, 1, 2], query: "?view=trail&pick=0,1" });
      state.firePopstate("?view=trail&pick=0,2");
      state.releaseChunks();
      for (let i = 0; i < 100 && !state.readHtml().includes('data-trail-open="2:'); i++) await new Promise((resolve) => setTimeout(resolve, 10));
      // A step of the gained run's own trace, which only its parsed chunk carries — the index stub
      // would still name the device on an empty lane.
      expect(state.readHtml()).toContain('data-trail-open="2:1"');
      expect(state.readHtml()).toContain("android-tablet");
    });

    test("runs that share only a title are staged side by side, not joined by step number", () => {
      // No trailId, same title: the run index refuses to group these as one trail for exactly this
      // reason — two runs named the same can be unrelated histories — so a pick of them must not
      // claim a step spine the index itself won't claim.
      const index = renderViewerState(sameTitle, { query: "?view=runs", pick: [0, 1] }).readHtml();
      expect(index).toContain("different trails — shown side by side");
      const stage = renderViewerState(sameTitle, { query: "?view=runs", pick: [0, 1], pickOpen: true }).readHtml();
      expect(stage).toContain("<h1>2 selected runs</h1>");
      expect(stage).toContain('<div class="trailsteplabel"></div>');
      expect(stage).not.toContain('data-trail-mode="map"');
    });

    test("picking a single run still reads as that run's own trail", () => {
      // One lane has only its own spine to line up against, so there is nothing to disclaim.
      // Treating it as positional would blank the step labels of a run shown entirely on its own.
      const stage = renderViewerState(sameTitle, { query: "?view=runs", pick: [0], pickOpen: true }).readHtml();
      expect(stage).toContain("<h1>Checkout</h1>");
      expect(stage).toContain('<div class="wpnodelabel">Sign in</div>');
      expect(stage).toContain('data-trail-mode="map" aria-pressed="true"');
    });

    test("a stage spanning trails names each lane by its run; a trail's own stage by device alone", () => {
      // The heading of a spanning stage is a count, so the lane label is the only place the reader
      // can learn which run a column is. Device alone would show two unrelated runs as
      // "android-phone" and "android-phone (2)".
      const stage = renderViewerState(mixed, { query: "?view=runs", pick: [0, 1], pickOpen: true }).readHtml();
      expect(stage).toContain("Checkout · android-phone");
      expect(stage).toContain("Refunds · ios-ipad");
      // A trail's own stage is already headed by the trail name, so repeating it in every lane
      // would just push the device — the thing being compared — off the end of the chip.
      const scoped = renderViewer(payload, { query: "?view=trail" });
      expect(scoped).toContain('data-trail-lane="0"');
      expect(scoped).not.toContain("Checkout · android-phone");
    });

    test("a stage spanning trails counts its lanes as runs, not devices", () => {
      // Three picked runs can be the same device on three different trails, so "3 devices" would be
      // describing something else entirely.
      const stage = renderViewerState(mixed, { query: "?view=runs", pick: [0, 1], pickOpen: true }).readHtml();
      expect(stage).toContain("2 runs · ");
      expect(stage).toContain('aria-label="Runs shown"');
      // A trail's own stage IS a comparison across devices, and still says so.
      const scoped = renderViewer(payload, { query: "?view=trail" });
      expect(scoped).toContain("2 devices · ");
      expect(scoped).toContain('aria-label="Devices shown"');
    });

    test("a retried run offers one checkbox, not one per row that stands for it", () => {
      // The row above an attempt history already picks its latest attempt — a matrix cell does the
      // same — so a second control for that run would carry a second name for it and take the focus
      // restore that belongs to the first.
      const retried = { generatedAt: "now", sessions: [run("android-phone", laneATrace), run("android-phone", laneBTrace)] };
      const out = renderViewer(retried, { query: "?view=runs" });
      expect(out.match(/data-pick="1"/g)).toHaveLength(1);
      expect(out.match(/data-pick="0"/g)).toHaveLength(1);
      // The attempt it stands for keeps the gutter, so the rows below don't step sideways.
      expect(out).toContain('<div class="idxattemptline"><span class="idxpick idxpickempty"');
    });

    test("expanding a retried run's history survives ticking one of its attempts", () => {
      // Ticking re-renders the index. A group that lived only as an open <details> would snap shut
      // under the reader, taking the checkbox they were standing on with it.
      const retried = { generatedAt: "now", sessions: [run("android-phone", laneATrace), run("android-phone", laneBTrace)] };
      const state = renderViewerState(retried, { query: "?view=runs", openRetries: [1], pick: [0] });
      expect(state.readHtml()).toContain('data-retry-toggle="1" open');
      expect(state.readHtml()).toContain('data-pick="0" checked');
    });

    test("hiding a lane on one stage doesn't hide the same run on the next", () => {
      // Lane visibility is keyed by session index, and Back/forward walks between stages that can
      // hold the same run — so carrying it over starts the next stage with a lane already gone.
      const settle = [trailRow(1, { objective: true, trailhead: true, label: "Settle up", ts: 1000 }), trailRow(2, { ts: 1000, ms: 200 })];
      const three = { generatedAt: "now", sessions: [...mixed.sessions, run("android-tablet", settle, { title: "Payouts", trailId: "payouts/daily" })] };
      const state = renderViewerState(three, { query: "?view=trail&pick=0,1", toggleLanes: [1] });
      expect(state.readHtml()).toContain('data-trail-lane="1" aria-pressed="false"');
      state.firePopstate("?view=trail&pick=1,2");
      expect(state.readHtml()).toContain('data-trail-lane="1" aria-pressed="true"');
    });

    test("leaving a picked stage for a trail stops the pick travelling in the link", () => {
      // Back out of a picked stage leaves the runs ticked, so the next click can be a trail's own
      // entry point. Two stages, one route: the indices have to be dropped, or a reload or a share
      // reopens the set the reader just left instead of the trail they asked for.
      const state = renderViewerState(mixed, { query: "?view=trail&pick=0,1", back: true });
      expect(state.readHtml()).toContain('class="pickbar"');
      state.clickGotoTrail("trail:refunds%2Ffull:");
      expect(state.readHtml()).toContain("<h1>Refunds</h1>");
      expect(state.readRoute()).toContain("trail=");
      expect(state.readRoute()).not.toContain("pick=");
    });

    // Both runs are already in the document, so the pair the reader ticked is the pair the diff
    // should open on — otherwise choosing what to compare means going to the Compare view first and
    // re-picking there, with the index's own selection ignored.
    test("ticking two runs opens the diff on those two, not on the report's default pair", () => {
      const settle = [trailRow(1, { objective: true, trailhead: true, label: "Settle up", ts: 1000 }), trailRow(2, { ts: 1000, ms: 200 })];
      const three = { generatedAt: "now", sessions: [...mixed.sessions, run("android-tablet", settle, { title: "Payouts", trailId: "payouts/daily" })] };

      const index = renderViewerState(three, { query: "?view=runs", pick: [1, 2] }).readHtml();
      expect(index).toContain("data-pick-diff");

      // Runs 1 and 2, never 0 and 1 — the default pair, which is what a wiring that ignored the
      // selection would land on and which no assertion on run 0 could tell apart.
      const state = renderViewerState(three, { query: "?view=runs", pick: [1, 2], pickDiff: true });
      const diff = state.readHtml();
      expect(diff).toContain("<h1>Compare runs</h1>");
      expect(state.readRoute()).toContain("base=1");
      expect(state.readRoute()).toContain("vs=2");
    });

    test("a diff is offered only for a pick of two", () => {
      // One run has nothing to be diffed against, and three has no second side — both are stages
      // the side-by-side lanes handle and a two-column diff cannot.
      const one = renderViewerState(mixed, { query: "?view=runs", pick: [0] }).readHtml();
      expect(one).toContain('class="pickbar"');
      expect(one).not.toContain("data-pick-diff");

      const settle = [trailRow(1, { objective: true, trailhead: true, label: "Settle up", ts: 1000 }), trailRow(2, { ts: 1000, ms: 200 })];
      const three = { generatedAt: "now", sessions: [...mixed.sessions, run("android-tablet", settle, { title: "Payouts", trailId: "payouts/daily" })] };
      expect(renderViewerState(three, { query: "?view=runs", pick: [0, 1, 2] }).readHtml()).not.toContain("data-pick-diff");
    });

    test("clearing the selection puts the index back the way it was", () => {
      const cleared = renderViewerState(mixed, { query: "?view=runs", pick: [0, 1], pickClear: true }).readHtml();
      expect(cleared).not.toContain('class="pickbar"');
      expect(cleared).toContain('data-pick="0"');
    });

    test("ticking a run neither opens it nor expands its attempt history", () => {
      // Two runs of the same trail on the same device: the index folds them into one expandable
      // attempt history, so the checkbox sits inside a control that would otherwise toggle.
      const retried = { generatedAt: "now", sessions: [run("android-phone", laneATrace), run("android-phone", laneBTrace)] };
      const state = renderViewerState(retried, { query: "?view=runs", pick: [0] });
      expect(state.pickClicksStopped()).toEqual(["0"]);
      // The label around the box too: a click on its padding never reaches the input, so without
      // its own guard it bubbles into the <summary> and expands the attempt history.
      expect(state.pickLabels()).toBeGreaterThan(0);
      expect(state.pickLabelClicksStopped()).toBe(state.pickLabels());
      // Still on the index, with the run picked rather than opened.
      expect(state.readHtml()).toContain('class="pickbar"');
      expect(state.readHtml()).toContain("<strong>1</strong> selected");
    });
  });

  test("the Map projection is a waypoint chain: one node per step with every device inside it", () => {
    const out = renderViewer(payload, { query: "?view=trail" });
    // Map is the default projection; Grid and Time stay one click away.
    expect(out).toContain('class="trailcanvas"');
    expect(out).toContain('data-trail-mode="map" aria-pressed="true"');
    expect(out).toContain(">Grid</button>");
    // The chain opens with a start node carrying the trail's identity and each device's verdict.
    expect(out).toContain('class="wpnode wpstart" data-wp-start');
    expect(out).toContain('class="wpstarttitle">Checkout</h2>');
    // One hub per authored step (trailhead + "Sign in") fans out to one frame card per device —
    // so 4 frames, one of them lane B's honest "not reached" ghost in the step it never ran. The
    // ghost keeps the lane's slot but carries no wire anchor: its chain stops feeding the hubs.
    expect(out.match(/<section class="wphub" data-wp-hub="\d+">/g)).toHaveLength(2);
    expect(out.match(/class="wpframe /g)).toHaveLength(4);
    expect(out.match(/data-wp-frame="/g)).toHaveLength(3);
    expect(out).toContain('class="wpframe missing"');
    expect(out).toContain("not reached");
    // The natural-language step is the hub's headline, and outcomes mark the frame that owns
    // them: lane B's trailhead failure doesn't color lane A's frame at the same step.
    expect(out).toContain('class="wpnodelabel">Sign in</div>');
    expect(out).toContain('class="wpframe failed"');
    expect(out).toContain('class="wpframe passed"');
    // Each frame carries a pace bar scaled to the slowest device on that step.
    expect(out).toContain('class="wppace"');
    // Wires are drawn from measured positions after layout, into one overlay.
    expect(out).toContain('class="wpwires"');
    // The camera has its own controls, plus the orientation pivot and the screenshots switch.
    expect(out).toContain('data-trail-cam="fit"');
    expect(out).toContain('data-trail-dir="h"');
    expect(out).toContain('id="trailall" aria-checked="false"');
  });

  test("the Map's All screenshots switch puts every frame in the flow; the pivot flips the axis", () => {
    // Collapsed: only the step's final frame rides in the card.
    const collapsed = renderViewer(payload, { query: "?view=trail" });
    expect(collapsed).toContain('data-shot="a-signin-2.webp"');
    expect(collapsed).not.toContain('data-shot="a-signin-1.webp"');
    // All screenshots: the whole strip joins the card.
    const expanded = renderViewer(payload, { query: "?view=trail&all=1" });
    expect(expanded).toContain('data-shot="a-signin-1.webp"');
    expect(expanded).toContain('data-shot="a-signin-2.webp"');
    expect(expanded).toContain('class="wpshots all"');
    expect(expanded).toContain('id="trailall" aria-checked="true"');
    // The horizontal pivot re-lays the same world left→right; vertical stays the bare default.
    const horizontal = renderViewer(payload, { query: "?view=trail&dir=h" });
    expect(horizontal).toContain('class="trailworld wpflow wphoriz"');
    expect(horizontal).toContain('data-trail-dir="h" aria-pressed="true"');
    expect(collapsed).toContain('class="trailworld wpflow"');
  });

  test("lanes are named by device classifier and steps join positionally, with unreached steps marked", () => {
    const out = renderViewer(payload, { query: "?view=trail&mode=steps" });
    expect(out).toContain('<span class="traillanename">android-phone</span>');
    expect(out).toContain('<span class="traillanename">ios-ipad</span>');
    // One shared row per authored step: the trailhead row plus "Sign in".
    expect(out).toContain('<div class="trailsteplabel">Trailhead</div>');
    expect(out).toContain('<div class="trailsteplabel">Sign in</div>');
    // Lane B never got to "Sign in": an honest gap, not a fabricated cell.
    expect(out).toContain("not reached");
    // Each cell deep-links into its own run's timeline at that step (lane:headerId).
    expect(out).toContain('data-trail-open="0:3"');
    // Lane B's trailhead failure colors its cell without touching lane A's.
    expect(out).toContain('class="trailcell failed"');
    expect(out).toContain('class="trailcell passed"');
  });

  test("a crashed lane's failure lands on the step it died in, not on a green lane", () => {
    // This run died mid-step: no objective logged a Failure bookend, so every objective row is ok
    // and only the tool row that crashed is failed. The step outcomes come from the bookends, so
    // without the run's failure anchor the whole lane would read green while the index calls the
    // run failed — the Trail view would show nothing wrong anywhere.
    const crashedTrace = [
      trailRow(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000 }),
      trailRow(2, { ts: 1000, ms: 500, screenshotFile: "c-prep.webp" }),
      trailRow(3, { objective: true, label: "Sign in", ts: 2000 }),
      trailRow(4, { ts: 2000, ms: 900, ok: false, err: "process died", screenshotFile: "c-signin.webp" }),
    ];
    const crashed = { generatedAt: "now", sessions: [
      run("android-phone", laneATrace),
      run("ios-ipad", crashedTrace, { platform: "ios", status: "failed" }),
    ] };
    const out = renderViewer(crashed, { query: "?view=trail" });
    // Row 1 is "Sign in"; lane 1 is the crashed run. Its frame is failed, and the trailhead it got
    // through is not.
    expect(out).toContain('class="wpframe failed" data-wp-frame="1:1"');
    expect(out).toContain('class="wpframe passed" data-wp-frame="0:1"');
    // The other device passed the same step and keeps its own verdict.
    expect(out).toContain('class="wpframe passed" data-wp-frame="1:0"');

    // A tolerated failure inside a step that the run ultimately passed stays passing: retry polling
    // fails rows on purpose, and this run is not failed, so nothing anchors to it.
    const tolerated = { generatedAt: "now", sessions: [
      run("android-phone", laneATrace),
      run("ios-ipad", [
        trailRow(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000 }),
        trailRow(2, { ts: 1000, ms: 500, ok: false, err: "not ready yet", screenshotFile: "t-prep.webp" }),
        trailRow(3, { objective: true, label: "Sign in", ts: 2000 }),
        trailRow(4, { ts: 2000, ms: 900, screenshotFile: "t-signin.webp" }),
      ], { platform: "ios", status: "passed" }),
    ] };
    const toleratedOut = renderViewer(tolerated, { query: "?view=trail" });
    expect(toleratedOut).toContain('class="wpframe passed" data-wp-frame="0:1"');
    expect(toleratedOut).not.toContain('class="wpframe failed"');
  });

  test("collapsed cells summarize with the step's final frame; the All screenshots switch shows every frame", () => {
    const collapsed = renderViewer(payload, { query: "?view=trail&mode=steps" });
    expect(collapsed).toContain('data-shot="a-signin-2.webp"');
    expect(collapsed).not.toContain('data-shot="a-signin-1.webp"');
    // Frames carry their owning run so the zoom overlay resolves them from the right session.
    expect(collapsed).toContain('data-shot="b-prep.webp" data-shot-run="1"');

    const expanded = renderViewer(payload, { query: "?view=trail&mode=steps&all=1" });
    expect(expanded).toContain('data-shot="a-signin-1.webp"');
    expect(expanded).toContain('data-shot="a-signin-2.webp"');
    expect(expanded).toContain('id="trailall" aria-checked="true"');
  });

  test("every trail control names the device it belongs to, including the zoom gallery", () => {
    // The whole point of the view is N devices' takes on ONE step, so a control that only names
    // the step is ambiguous by construction: the reader can't tell which device they're opening.
    const out = renderViewer(payload, { query: "?view=trail" });
    expect(out).toContain(`aria-label="Open TRAILHEAD on ios-ipad in that run's timeline"`);
    expect(out).toContain('aria-label="android-phone · STEP 1 screenshot: row 5"');
    // Paging through the gallery, each frame says whose screen it is — on the rail and over the
    // image — instead of five identical "STEP 1" entries.
    const { zoomRoot } = renderViewerState(payload, { query: "?view=trail", zoomShot: "a-signin-2.webp" });
    const rail = zoomRoot.children.find((c: any) => c.className === "zoomsteps");
    expect(rail.children.map((item: any) => item.children.find((span: any) => span.className === "zoomstepdev")?.textContent))
      .toEqual(["android-phone", "ios-ipad", "android-phone"]);
    const wrap = zoomRoot.children.find((c: any) => c.className === "zoomwrap");
    expect(wrap.children.find((c: any) => c.className === "zoomdevice").textContent).toBe("android-phone");
  });

  test("a minute-long step reads as a real clock time", () => {
    // Rounding the seconds REMAINDER instead of the whole span renders 119.6s as "1m 60s".
    const slow = { generatedAt: "now", sessions: [
      run("android-phone", [
        trailRow(1, { objective: true, trailhead: true, label: "Prepare", ts: 0 }),
        trailRow(2, { ts: 0, ms: 119600, screenshotFile: "a-prep.webp" }),
      ]),
      run("ios-ipad", laneBTrace, { platform: "ios" }),
    ] };
    const out = renderViewer(slow, { query: "?view=trail&mode=steps" });
    expect(out).toContain("2m 0s");
    expect(out).not.toContain("1m 60s");
  });

  test("Open → lands on that device's own timeline at the step, and Back returns to the trail", () => {
    // The core loop: spot a difference, drill into the run that made it, come back to the map.
    const state = renderViewerState(payload, { query: "?view=trail", trailOpen: "0:3" });
    expect(state.readRoute()).toContain("run=0");
    expect(state.readRoute()).toContain("step=3");
    expect(state.readHtml()).toContain('class="timeline');
    const returned = renderViewerState(payload, { query: "?view=trail", trailOpen: "0:3", back: true });
    expect(returned.readRoute()).toContain("view=trail");
    expect(returned.readHtml()).toContain('class="trailcanvas"');
  });

  test("a lane chip takes its device off the stage, and its exits keep naming the real run", () => {
    // Five loaded devices are for coverage; a comparison is usually two. Chips — one per loaded
    // run, hidden or not — toggle lanes without reloading anything.
    const initial = renderViewer(payload, { query: "?view=trail" });
    expect(initial).toContain('data-trail-lane="0" aria-pressed="true"');
    expect(initial).toContain('data-trail-lane="1" aria-pressed="true"');
    expect(initial).toContain("2 devices");

    const state = renderViewerState(payload, { query: "?view=trail", toggleLanes: [0] });
    const filtered = state.readHtml();
    // The hidden device's frames are gone, the header says the view is a subset, and the chip
    // stays — dimmed — so bringing the device back is one click.
    expect(filtered).not.toContain("a-prep.webp");
    expect(filtered).toContain("b-prep.webp");
    expect(filtered).toContain("1 of 2 devices");
    expect(filtered).toContain('data-trail-lane="0" aria-pressed="false"');
    // The surviving lane is now lane 0 of the MATRIX, but its Open → and Lightbox exits still name
    // session 1 — renumbering them would open the wrong run.
    expect(filtered).toContain('data-trail-open="1:');
    expect(filtered).toContain('data-shot-run="1"');
    expect(filtered).not.toContain('data-trail-open="0:');

    // The Grid projection builds its cells on a separate path; its exits must keep the session
    // index too, or Open → out of a filtered grid opens the wrong run.
    const grid = renderViewerState(payload, { query: "?view=trail&mode=steps", toggleLanes: [0] }).readHtml();
    expect(grid).not.toContain("a-prep.webp");
    expect(grid).toContain('data-trail-open="1:');
    expect(grid).toContain('data-shot-run="1"');
    expect(grid).not.toContain('data-trail-open="0:');
  });

  test("hiding the last shown device is refused — the stage never goes empty", () => {
    const state = renderViewerState(payload, { query: "?view=trail", toggleLanes: [0, 1] });
    const html = state.readHtml();
    // The second toggle was a no-op: lane B is still on stage and still marked shown.
    expect(html).toContain("b-prep.webp");
    expect(html).toContain("1 of 2 devices");
    expect(html).toContain('data-trail-lane="1" aria-pressed="true"');
  });

  test("a single run gets the Trail view too, entered from its own header", () => {
    // One Android phone run is still a trail — its Replay in particular. With no run index to host
    // the button, the detail header carries it, and Back returns to the run rather than an index.
    const solo = { generatedAt: "now", sessions: [run("android-phone", laneATrace)] };
    const state = renderViewerState(solo, { gotoTrail: true });
    const trail = state.readHtml();
    expect(trail).toContain('class="trailcanvas"');
    expect(trail).toContain("1 device ·");
    expect(trail).toContain(">Back to run</button>");
    // A lane bar with one lane would be a switch with no positions.
    expect(trail).not.toContain("traillanebar");
    const returned = renderViewerState(solo, { gotoTrail: true, back: true });
    expect(returned.readHtml()).toContain('class="timeline');
  });

  test("the index's trail row opens the Trail view, and unnamed runs are not offered one", () => {
    // The row IS the trail — it was inert while only the header button worked.
    const index = renderViewer(payload, { query: "?view=runs" });
    expect(index).toContain('class="nm nmtrail" type="button" data-goto-trail');
    const state = renderViewerState(payload, { query: "?view=runs", gotoTrail: true });
    expect(state.readHtml()).toContain('class="trailcanvas"');
    // Runs with neither a trail id nor a title share no identity — they are unidentified, not
    // "the same trail", so neither entry point appears.
    const unnamed = { generatedAt: "now", sessions: [
      { ...run("android-phone", laneATrace), meta: { status: "passed", platform: "android", deviceClassifier: "android-phone" } },
      { ...run("ios-ipad", laneBTrace), meta: { status: "failed", platform: "ios", deviceClassifier: "ios-ipad" } },
    ] };
    expect(renderViewer(unnamed, { query: "?view=runs" })).not.toContain("data-goto-trail");
  });

  test("the retired Time projection is gone, and its old links land on the map", () => {
    // Replay carries everything Time showed (each lane's own wall-clock pacing, on one shared
    // axis) plus the playback, so Time was deleted rather than kept as a third timing view.
    const out = renderViewer(payload, { query: "?view=trail&mode=replay" });
    expect(out).not.toContain('data-trail-mode="time"');
    expect(out).not.toContain("trailtimegrid");
    // A mode=time link in an old message keeps working: unknown modes fall back to the map.
    const old = renderViewer(payload, { query: "?view=trail&mode=time" });
    expect(old).toContain('data-trail-mode="map" aria-pressed="true"');
    expect(old).toContain('class="trailcanvas"');
  });

  test("replay mode is its own projection, reachable by route, leaving the others in place", () => {
    // Three projections of one trail, so a reader can compare them rather than trade one for another.
    const out = renderViewer(payload, { query: "?view=trail&mode=replay" });
    expect(out).toContain('data-trail-mode="replay" aria-pressed="true"');
    expect(out).toContain('class="rpwrap"');
    ["map", "steps"].forEach((mode) => expect(out).toContain(`data-trail-mode="${mode}"`));
    // Replay is a moment in time, not a layout of every frame, so the all-screenshots switch and
    // the map's orientation pivot don't apply to it.
    expect(out).not.toContain('id="trailall"');
    expect(out).not.toContain("data-trail-dir");
  });

  test("the map and grid say the arrow keys walk the steps; replay's transport already does", () => {
    // The keyboard is invisible until someone tells the reader it listens.
    expect(renderViewer(payload, { query: "?view=trail" })).toContain('class="trailkeys"');
    expect(renderViewer(payload, { query: "?view=trail&mode=steps" })).toContain('class="trailkeys"');
    // Replay's transport carries its own richer hint (space plays, ↑↓ picks a device).
    expect(renderViewer(payload, { query: "?view=trail&mode=replay" })).not.toContain('class="trailkeys"');
  });

  test("the replay stage gives every device its own named column, chip, screen and Open →", () => {
    const out = renderViewer(payload, { query: "?view=trail&mode=replay" });
    // One column per run, each naming its device — the comparison is meaningless unnamed.
    expect(out).toContain('data-rp-lane="0"');
    expect(out).toContain('data-rp-lane="1"');
    expect(out).not.toContain('data-rp-lane="2"');
    // Named on the stage head itself (tabindex 0), not only on the strip's row label (tabindex -1).
    expect(out).toContain('data-rp-pick="0" role="button" tabindex="0" aria-pressed="false" aria-label="Follow android-phone');
    expect(out).toContain('data-rp-pick="1" role="button" tabindex="0" aria-pressed="false" aria-label="Follow ios-ipad');
    expect(out).toContain('data-rp-pick="0" role="button" tabindex="-1" aria-label="Follow android-phone');
    // Two stacked image layers per lane, so a new capture can cross-fade over the one it replaces.
    expect(out).toContain('data-rp-img="0:0"');
    expect(out).toContain('data-rp-img="0:1"');
    // A transport and one rail per lane, plus a single playhead across them.
    expect(out).toContain("data-rp-play");
    expect(out).toContain('data-rp-rail="0"');
    expect(out).toContain('data-rp-rail="1"');
    expect(out).toContain("data-rp-head");
  });

  test("a step block's width on the strip is its share of the whole run, not a fixed size", () => {
    const out = renderViewer(payload, { query: "?view=trail&mode=replay" });
    // Lane A starts its run at ts 1000 and its last row ends at 6000, so the shared axis is 5000ms
    // long. Its "Sign in" starts 1000ms in and spans 4000ms — a fifth along, four fifths wide.
    expect(out).toContain('style="left:20%;width:80%"');
    // Lane B failed, and its block says so rather than reading as a normal step.
    expect(out).toContain('class="rpblock failed"');
    // Lane B stopped at 800ms and the strip shows the rest of the axis as time it wasn't running.
    expect(out).toContain('class="rpdone" style="left:16%;width:84%"');
  });

  test("a capture whose own row carried no timestamp gets no tick — it has no instant to sit on", () => {
    // Lane A's three timed captures each mark an instant the stage changes.
    const timed = renderViewer(payload, { query: "?view=trail&mode=replay" });
    expect(timed.split('class="rpcap"').length - 1).toBe(4); // 3 on lane A, 1 on lane B
    // And each sits at its own instant: lane A captured at 0ms, 1000ms and 4000ms of a 5000ms axis.
    expect(timed).toContain('class="rpcap" style="left:20%"');
    expect(timed).toContain('class="rpcap" style="left:80%"');
    // Add a fourth capture to lane A's "Sign in" on a row with no timestamp. It belongs to a step
    // that IS on the clock, so the step still draws — but placing the frame would mean claiming it
    // was on screen at an instant nothing says it was.
    const withUntimed = { generatedAt: "now", sessions: [
      run("android-phone", [...laneATrace, trailRow(6, { ts: null, ms: 400, screenshotFile: "a-signin-3.webp" })]),
      run("ios-ipad", laneBTrace, { platform: "ios" }),
    ] };
    const out = renderViewer(withUntimed, { query: "?view=trail&mode=replay" });
    expect(out.split('class="rpcap"').length - 1).toBe(4);
  });

  test("a lane that recorded video plays the recording; one that didn't keeps its screenshots", () => {
    // Video availability is per device, not per report: on a real 5-device run only the iPad lane
    // recorded, so the stage has to mix a playing pane with stepping ones rather than pick one mode.
    const withClip = { generatedAt: "now", sessions: [
      run("android-phone", laneATrace),
      { ...run("ios-ipad", laneBTrace, { platform: "ios" }),
        videoClip: { url: "blob:tb-ipad", startMs: 90000, endMs: 120000, mime: "video/mp4" } },
    ] };
    const out = renderViewer(withClip, { query: "?view=trail&mode=replay" });
    expect(out).toContain('data-rp-vid="1"');
    expect(out).toContain('src="blob:tb-ipad"');
    // Muted and inline, or a browser refuses to play it at all without a gesture per lane.
    expect(out).toContain("muted playsinline");
    // The lane that recorded says so, so a reader knows why one pane glides and another steps.
    expect(out).toContain('class="rpsource"');
    // The screenshot lane gets no element — and keeps both of its cross-fade layers.
    expect(out).not.toContain('data-rp-vid="0"');
    expect(out).toContain('data-rp-img="0:0"');
    // With a recording present the "screenshots only" caveat is gone.
    expect(out).not.toContain("screenshots only");
    // And with none, it is stated rather than left to be inferred from panes that merely jump.
    const plain = renderViewer(payload, { query: "?view=trail&mode=replay" });
    expect(plain).toContain("screenshots only");
    expect(plain).not.toContain("data-rp-vid");
    expect(plain).not.toContain('class="rpsource"');
  });

  test("every pane has a mark overlay, and the strip pips each interaction at its own instant", () => {
    // Lane A taps at ts 2000 and asserts at ts 5000 — 1000ms and 4000ms into its own run, on a
    // 5000ms shared axis, so a fifth and four fifths along.
    const acting = { generatedAt: "now", sessions: [
      run("android-phone", [
        ...laneATrace.slice(0, 3),
        trailRow(4, { ts: 2000, ms: 3000, screenshotFile: "a-signin-1.webp", mark: { kind: "tap", x: 50, y: 100, dw: 200, dh: 400 } }),
        trailRow(5, { ts: 5000, ms: 1000, screenshotFile: "a-signin-2.webp", mark: { kind: "assert", x: 10, y: 20, dw: 200, dh: 400, ok: true } }),
      ]),
      run("ios-ipad", laneBTrace, { platform: "ios" }),
    ] };
    const out = renderViewer(acting, { query: "?view=trail&mode=replay" });
    // The overlay layer exists per lane whether or not that lane acted — the driver fills it as the
    // clock moves, and a lane without a layer could never show a mark at all.
    expect(out).toContain('data-rp-marks="0"');
    expect(out).toContain('data-rp-marks="1"');
    // Interactions are pipped on the strip, typed and positioned by their own instant.
    expect(out).toContain('class="rpact tap" style="left:20%"');
    expect(out).toContain('class="rpact assert" style="left:80%"');
    // Lane B performed none, so it gets no pips rather than a shared or guessed set.
    expect(out.split('class="rpact ').length - 1).toBe(2);
  });

  test("the picture sits in one aspect-driven frame, so nothing shifts as the clock runs", () => {
    // The reported jitter: the pane's contents re-heighted as the run played and pushed everything
    // below them around. The frame is the largest rectangle of the source's shape that fits the
    // pane, so a differently-shaped source changes the picture's size and never the pane's.
    const out = renderViewer(payload, { query: "?view=trail&mode=replay" });
    expect(out).toContain('data-rp-frame="0"');
    // Both capture layers and the overlay live INSIDE that frame: the marks' percentages are of the
    // picture, so an overlay measured against the padded pane would place a tap off its target.
    const frame = out.slice(out.indexOf('data-rp-frame="0"'), out.indexOf('data-rp-waiting="0"'));
    expect(frame).toContain('data-rp-img="0:0"');
    expect(frame).toContain('data-rp-img="0:1"');
    expect(frame).toContain('data-rp-marks="0"');
  });

  test("the frame is fit against BOTH pane axes, so a landscape recording is not letterboxed", () => {
    // `aspect-ratio` recomputes only the free axis: a full-height frame with a landscape ratio just
    // clamped on max-width and stayed portrait-shaped, which letterboxed the recording inside it AND
    // — because the marks are positioned against the frame, not the pixels — threw every tap a
    // third of a pane off target. Fitting with min() against both container axes is the fix, so the
    // frame needs a sized query container to measure and a plain w/h number to multiply by.
    expect(core.RUN_REPORT_CSS).toContain(".rpbox { position: absolute; inset: 0; container-type: size;");
    expect(core.RUN_REPORT_CSS).toContain("width: min(100cqw, calc(100cqh * var(--rp-ar, 0.4615)))");
    expect(core.RUN_REPORT_CSS).toContain("aspect-ratio: var(--rp-ar, 0.4615)");
    // The old single-axis sizing must be gone, not merely overridden further down the sheet.
    expect(core.RUN_REPORT_CSS).not.toContain("--rp-aspect");
  });

  test("the capture cross-fade is a fade, not a slide", () => {
    // The incoming capture used to slide up 6px as it faded in — at playback cadence that reads as
    // the picture jittering vertically, which is exactly the artifact this view was reworked to
    // kill. Nothing in a pane may MOVE when only its content changes.
    expect(core.RUN_REPORT_CSS).toContain(".rpimg { opacity: 0; transition: opacity 200ms ease; }");
    expect(core.RUN_REPORT_CSS).not.toContain("translateY(6px)");
  });

  test("the speed ring stops at 10x, where a played recording can still keep up", () => {
    // Browsers refuse playbackRate past 16x, so a faster ring would desynchronize the video lanes
    // from the screenshot ones at exactly the speed a reader reaches for to skim a long run.
    const out = renderViewer(payload, { query: "?view=trail&mode=replay" });
    expect(out).toContain("data-rp-speed");
    expect(out).toContain(">10×</button>");
    expect(out).not.toContain(">25×</button>");
  });

  test("a trail with no timestamps says so instead of playing an invented clock", () => {
    // Every projection but Replay works without timestamps; Replay's whole claim is synchronization.
    const untimed = { generatedAt: "now", sessions: [
      run("android-phone", laneATrace.map((r) => ({ ...r, ts: null }))),
      run("ios-ipad", laneBTrace.map((r) => ({ ...r, ts: null })), { platform: "ios" }),
    ] };
    const out = renderViewer(untimed, { query: "?view=trail&mode=replay" });
    expect(out).toContain("Nothing to replay");
    expect(out).not.toContain("data-rp-play");
    // The Grid still renders that same trail in full.
    expect(renderViewer(untimed, { query: "?view=trail&mode=steps" })).toContain('class="trailgrid"');
  });
});

describe("multi-device sessions: device attribution (assignTraceDevices)", () => {
  const objective = (step: string, ts: string) => ({ class: `${T}.ObjectiveStartLog`, promptStep: { step }, timestamp: ts });
  const tool = (name: string, traceId: string, ts: string, extra: Record<string, unknown> = {}) => ({
    class: `${T}.TrailblazeToolLog`, toolName: name, traceId, successful: true, durationMs: 10,
    trailblazeTool: { raw: {} }, timestamp: ts, ...extra,
  });
  // The host executes switchDevice itself and stamps the log with the DESTINATION binding — the
  // log is written after the switch. Device-dispatched tools arrive with NO deviceName: the
  // device writes those logs and never learns its own binding name.
  const switchTo = (name: string, traceId: string, ts: string) => ({
    class: `${T}.TrailblazeToolLog`, toolName: "switchDevice", traceId, successful: true, durationMs: 5,
    trailblazeTool: { raw: { name } }, deviceName: name, dispatchedHostSide: true, timestamp: ts,
  });

  test("attributes rows to the active device, flips on each handover, and backfills the start prefix", () => {
    const trace = core.extractTrace([
      objective("Place the order", "2024-01-01T00:00:00Z"),
      tool("tapOnElement", "t1", "2024-01-01T00:00:01Z"),
      switchTo("kitchen", "t2", "2024-01-01T00:00:02Z"),
      tool("assertVisible", "t3", "2024-01-01T00:00:03Z"),
      switchTo("storefront", "t4", "2024-01-01T00:00:04Z"),
      tool("tapOnElement", "t5", "2024-01-01T00:00:05Z"),
    ]);
    expect(trace.map((r) => [r.label, r.device])).toEqual([
      // Nothing before the first handover is stamped, but in a two-device session the start
      // device is the one that is NOT the first handover's destination.
      ["Place the order", "storefront"],
      ["tapOnElement", "storefront"],
      // A handover row belongs to its DESTINATION — the moment that device takes focus.
      ["switchDevice", "kitchen"],
      ["assertVisible", "kitchen"],
      ["switchDevice", "storefront"],
      ["tapOnElement", "storefront"],
    ]);
  });

  test("rows before the first handover take a pre-handover host stamp when one exists", () => {
    // Three devices: elimination can't name the start device, but a host-side trailhead tool
    // stamped with the binding name can — pre-handover, a stamp can only name the start device.
    const trace = core.extractTrace([
      objective("Prepare", "2024-01-01T00:00:00Z"),
      tool("prepareEnvironment", "t1", "2024-01-01T00:00:01Z", { deviceName: "expo" }),
      switchTo("kitchen", "t2", "2024-01-01T00:00:02Z"),
      tool("assertVisible", "t3", "2024-01-01T00:00:03Z"),
      switchTo("bar", "t4", "2024-01-01T00:00:04Z"),
      tool("tapOnElement", "t5", "2024-01-01T00:00:05Z"),
    ]);
    expect(trace.map((r) => r.device)).toEqual(["expo", "expo", "kitchen", "kitchen", "bar", "bar"]);
  });

  test("a host-stamped deviceName resynchronizes the walk when a handover itself went unlogged", () => {
    // The direct-MCP switch path emits no switchDevice log at all; the next host-emitted log's
    // stamp is the only signal the focus moved, and it must win over the carried-forward name.
    const trace = core.extractTrace([
      tool("prepareEnvironment", "t1", "2024-01-01T00:00:00Z", { deviceName: "seller" }),
      tool("tapOnElement", "t2", "2024-01-01T00:00:01Z"),
      tool("prepareEnvironment", "t3", "2024-01-01T00:00:02Z", { deviceName: "buyer" }),
      tool("assertVisible", "t4", "2024-01-01T00:00:03Z"),
    ]);
    expect(trace.map((r) => r.device)).toEqual(["seller", "seller", "buyer", "buyer"]);
  });

  test("a three-device session leaves the start prefix unattributed even when the start device returns", () => {
    // Elimination only names a start device when exactly two names are observed, so here the
    // prefix stays unattributed. It is the honest answer: `storefront → kitchen → bar` and
    // `storefront → kitchen → bar → storefront` emit the same log shape up to the last handover,
    // and nothing in the stream says the returning device is the one that started. The cost is
    // that the storefront's opening rows render as their own unnamed lane instead of joining the
    // storefront's; naming them needs the device roster serialized into the session start.
    const trace = core.extractTrace([
      tool("tapOnElement", "t1", "2024-01-01T00:00:01Z"),
      switchTo("kitchen", "t2", "2024-01-01T00:00:02Z"),
      switchTo("bar", "t3", "2024-01-01T00:00:03Z"),
      switchTo("storefront", "t4", "2024-01-01T00:00:04Z"),
      tool("assertVisible", "t5", "2024-01-01T00:00:05Z"),
    ]);
    expect(trace.map((r) => r.device ?? null)).toEqual([null, "kitchen", "bar", "storefront", "storefront"]);
  });

  test("a handover that failed leaves the session on the device it never left", () => {
    // A failed switchDevice never moved the focus, so the rows after it still ran on the source.
    // Its log names the destination in both `raw.name` and the host stamp, so it has to be
    // skipped outright — either signal alone would hand the rest of the run to the wrong device.
    const failedSwitch = {
      class: `${T}.TrailblazeToolLog`, toolName: "switchDevice", traceId: "t3", successful: false,
      durationMs: 5, trailblazeTool: { raw: { name: "bar" } }, deviceName: "bar",
      dispatchedHostSide: true, timestamp: "2024-01-01T00:00:03Z",
    };
    const trace = core.extractTrace([
      tool("prepareEnvironment", "t1", "2024-01-01T00:00:01Z", { deviceName: "storefront" }),
      switchTo("kitchen", "t2", "2024-01-01T00:00:02Z"),
      failedSwitch,
      tool("assertVisible", "t4", "2024-01-01T00:00:04Z"),
    ]);
    expect(trace.map((r) => r.device)).toEqual(["storefront", "kitchen", "kitchen", "kitchen"]);
  });

  test("a single-device session carries no device field at all, before and after slimming", () => {
    const logs = [
      objective("Sign in", "2024-01-01T00:00:00Z"),
      tool("tapOnElement", "t1", "2024-01-01T00:00:01Z"),
    ];
    const trace = core.extractTrace(logs);
    expect(trace.every((r) => !("device" in r))).toBe(true);
    expect((core as any).slimTraceForShare(trace).every((r: Record<string, unknown>) => !("device" in r))).toBe(true);
  });

  test("device attribution survives the share slimming", () => {
    const trace = core.extractTrace([
      tool("prepareEnvironment", "t1", "2024-01-01T00:00:00Z", { deviceName: "storefront" }),
      switchTo("kitchen", "t2", "2024-01-01T00:00:01Z"),
      tool("assertVisible", "t3", "2024-01-01T00:00:02Z"),
    ]);
    const slim = (core as any).slimTraceForShare(trace);
    expect(slim.map((r: Record<string, unknown>) => r.device)).toEqual(["storefront", "kitchen", "kitchen"]);
  });
});

describe("Trail view of a multi-device session (one lane per device)", () => {
  const deviceRow = (i: number, extra: Record<string, unknown> = {}) => ({ i, label: `row ${i}`, tool: "t", note: null, ms: 0, ts: null, ok: true, err: null, screenshotFile: null, objective: false, trailhead: false, count: null, mark: null, children: [], ...extra });
  // ONE session that drove two devices: trailhead + step 1 on the storefront, then a switchDevice
  // handover and step 2's work on the kitchen. Step 2 is ANNOUNCED after the handover.
  const multiDeviceTrace = [
    deviceRow(1, { objective: true, trailhead: true, label: "Prepare", ts: 1000, device: "storefront" }),
    deviceRow(2, { ts: 1000, ms: 500, screenshotFile: "front-prep.webp", device: "storefront" }),
    deviceRow(3, { objective: true, label: "Place the order", ts: 2000, device: "storefront" }),
    deviceRow(4, { ts: 2000, ms: 1000, screenshotFile: "front-order.webp", device: "storefront" }),
    deviceRow(5, { label: "switchDevice", ts: 3000, ms: 100, device: "kitchen" }),
    deviceRow(6, { objective: true, label: "Confirm the ticket", ts: 3500, device: "kitchen" }),
    deviceRow(7, { ts: 3500, ms: 2000, screenshotFile: "kitchen-ticket.webp", device: "kitchen" }),
  ];
  const sessionOf = (trace: Array<Record<string, unknown>>) => ({
    meta: { title: "Order to kitchen", status: "passed", trailId: "orders/to-kitchen", platform: "android", deviceClassifier: "android-phone" },
    trace, llm: [],
    shots: Object.fromEntries(trace.filter((r) => r.screenshotFile).map((r) => [r.screenshotFile, "data:image/webp;base64,AAAA"])),
    recordingYaml: null,
  });
  const payload = { generatedAt: "now", sessions: [sessionOf(multiDeviceTrace)] };

  test("one session splits into one lane per device, framed as one run", () => {
    const out = renderViewer(payload, { query: "?view=trail&mode=steps" });
    // The start device's lane carries the run's classifier — the one device the session metadata
    // actually describes; the companion is named by its binding alone.
    expect(out).toContain('<span class="traillanename">storefront · android-phone</span>');
    expect(out).toContain('<span class="traillanename">kitchen</span>');
    expect(out).toContain("2 devices, one run");
    expect(out).toContain("one trail, one lane per device");
    // Steps the kitchen sat out are honest gaps, not hollow cells.
    expect(out).toContain("not reached");
    // Every deep link lands in the ONE session's timeline (session index 0).
    expect(out).toContain('data-trail-open="0:3"');
    expect(out).not.toContain('data-trail-open="1:');
  });

  test("a single-device session keeps a single lane", () => {
    const singleTrace = multiDeviceTrace.map((r) => { const { device: _device, ...rest } = r as Record<string, unknown>; return rest; });
    const out = renderViewer({ generatedAt: "now", sessions: [sessionOf(singleTrace)] }, { query: "?view=trail&mode=steps" });
    expect(out.match(/class="traillanehead"/g)).toHaveLength(1);
    expect(out).not.toContain("one lane per device");
  });

  test("the detail timeline chips each row with its device and gives the handover its own glyph", () => {
    const out = renderViewer(payload, {});
    expect(out).toContain('<span class="devchip" title="Ran on the storefront device">storefront</span>');
    expect(out).toContain('<span class="devchip" title="Ran on the kitchen device">kitchen</span>');
    expect(out).toContain('class="ic switch"');
    expect(out).toContain("⇄");
  });

  test("the default timeline renders device swim lanes: colored rails, per-device columns, a cast list", () => {
    const out = renderViewer(payload, {});
    // Each row indents to its device's lane — storefront is lane 0, kitchen lane 1 (first
    // appearance order, matching the Trail view) — and carries the lane's color.
    expect(out).toContain("devlane devlane-0");
    expect(out).toContain("devlane devlane-1");
    expect(out).toMatch(/devlane devlane-0[^>]*--lane-color:/);
    // The handover row bridges the lanes rather than sitting in one column.
    expect(out).toContain("devlane-1 handover");
    // And the legend names the cast up top, where the verdict is.
    expect(out).toContain('class="devlegend"');
    expect(out).toContain('aria-label="Multi-device session: storefront, kitchen"');
    expect(out).toContain("2 devices");
  });

  test("the scrubber splits into stacked per-device bands with one shared playhead", () => {
    const out = renderViewer(payload, {});
    // The one slider grows a band per device …
    expect(out).toMatch(/class="scrubtrack devlanes" style="--scrub-lanes:2"/);
    // … each named and washed in its lane color …
    expect(out).toMatch(/class="scrublanename"[^>]*>storefront</);
    expect(out).toMatch(/class="scrublanename"[^>]*>kitchen</);
    expect(out).toContain('class="scrublane"');
    // … with solid activity segments where that device was driving (both lanes get one).
    expect(out).toMatch(/class="scrublaneseg"[^>]*--lane-color:oklch\(60% \.14 250\)/);
    expect(out).toMatch(/class="scrublaneseg"[^>]*--lane-color:oklch\(62% \.15 45\)/);
    // Action dots drop into their device's band: band centers for 2 lanes are 25% and 75%.
    expect(out).toMatch(/class="scrubtick event[^"]*"[^>]*top:25\.00%/);
    expect(out).toMatch(/class="scrubtick event[^"]*"[^>]*top:75\.00%/);
    // The slider announces the bands to assistive tech.
    expect(out).toContain("One band per device: storefront, kitchen.");
  });

  test("the lightbox names the device on every frame, above the screenshot", () => {
    const out = renderViewer(payload, { query: "?tab=lightbox" });
    expect(out).toMatch(/class="galdevbar"[^>]*title="Captured on the storefront device"/);
    expect(out).toMatch(/class="galdevbar"[^>]*title="Captured on the kitchen device"/);
    // The name caps the frame rather than trailing it: the bar precedes the image, and no part of
    // the captured screen is covered.
    expect(out).toMatch(/class="galdevbar"[\s\S]{0,120}kitchen<\/div><div class="galshot"/);
    // The thumbnail carries the lane color too, so a wall of shots groups by device at a glance.
    expect(out).toMatch(/class="galcell devlane devlane-0"[^>]*--lane-color:oklch\(60% \.14 250\)/);
    expect(out).toMatch(/class="galcell devlane devlane-1"[^>]*--lane-color:oklch\(62% \.15 45\)/);
    // Zooming a frame carries the device through to the fullscreen badge.
    expect(out).toContain('data-shot-device="kitchen"');
    // And alt text says which screen this is, not just what the step was.
    expect(out).toContain('alt="Place the order on the storefront device"');
    expect(out).toContain('class="devlegend"');
  });

  test("a step that captured two devices keeps a closing frame for each", () => {
    // "Check that BOTH screens show the ticket": one authored step, work on both devices. Keeping
    // only the step's last frame would silently drop the storefront's evidence.
    const bothTrace = [
      deviceRow(1, { objective: true, label: "Confirm on both screens", ts: 1000, device: "storefront" }),
      deviceRow(2, { ts: 1000, ms: 100, screenshotFile: "front-a.webp", device: "storefront" }),
      deviceRow(3, { ts: 1200, ms: 100, screenshotFile: "front-b.webp", device: "storefront" }),
      deviceRow(4, { label: "switchDevice", ts: 1400, ms: 10, device: "kitchen" }),
      deviceRow(5, { ts: 1500, ms: 100, screenshotFile: "kitchen-a.webp", device: "kitchen" }),
      deviceRow(6, { ts: 1700, ms: 100, screenshotFile: "kitchen-b.webp", device: "kitchen" }),
    ];
    const out = renderViewer({ generatedAt: "now", sessions: [sessionOf(bothTrace)] }, { query: "?tab=lightbox" });
    // The closing frame of each device survives; their earlier frames stay folded away.
    expect(out).toContain('data-shot="front-b.webp"');
    expect(out).toContain('data-shot="kitchen-b.webp"');
    expect(out).not.toContain('data-shot="front-a.webp"');
    expect(out).not.toContain('data-shot="kitchen-a.webp"');
    expect(out).toContain("2 step frames");
  });

  test("a device whose only capture is on a folded child still gets its closing frame", () => {
    // The kitchen's work in this step was batched into one folded row, so its screenshot hangs off
    // a child dispatch. Considering row captures first and children only as a whole-group fallback
    // would show the storefront alone and silently lose the kitchen's evidence.
    const foldedTrace = [
      deviceRow(1, { objective: true, label: "Confirm on both screens", ts: 1000, device: "storefront" }),
      deviceRow(2, { ts: 1000, ms: 100, screenshotFile: "front.webp", device: "storefront" }),
      deviceRow(3, { label: "switchDevice", ts: 1400, ms: 10, device: "kitchen" }),
      deviceRow(4, {
        ts: 1500, ms: 100, device: "kitchen", screenshotFile: null,
        children: [{ label: "tap", screenshotFile: "kitchen-kid.webp", ms: 50, ts: 1500, ok: true, tool: "t", err: null }],
      }),
    ];
    const session = sessionOf(foldedTrace);
    session.shots["kitchen-kid.webp"] = "data:image/webp;base64,AAAA";
    const out = renderViewer({ generatedAt: "now", sessions: [session] }, { query: "?tab=lightbox" });
    expect(out).toContain('data-shot="front.webp"');
    expect(out).toContain('data-shot="kitchen-kid.webp"');
    expect(out).toMatch(/class="galdevbar"[^>]*title="Captured on the kitchen device"/);
  });

  test("device names reach the scrubber label escaped", () => {
    // Binding names are configuration map keys with no reserved-name rule, so a crafted one must
    // not be able to close the aria-label attribute and add its own.
    const hostile = multiDeviceTrace.map((r) => (
      r.device === "kitchen" ? { ...r, device: 'x" onpointerenter="alert(1)' } : r
    ));
    const out = renderViewer({ generatedAt: "now", sessions: [sessionOf(hostile)] }, {});
    expect(out).not.toContain('onpointerenter="alert(1)"');
    expect(out).toContain("One band per device: storefront, x&quot; onpointerenter=&quot;alert(1).");
  });

  test("a single-device step still yields exactly one closing frame", () => {
    const singleTrace = multiDeviceTrace.map((r) => { const { device: _device, ...rest } = r as Record<string, unknown>; return rest; });
    const out = renderViewer({ generatedAt: "now", sessions: [sessionOf(singleTrace)] }, { query: "?tab=lightbox" });
    expect(out.match(/class="galcell/g)).toHaveLength(3);
    expect(out).not.toContain("galdevbar");
  });

  test("a single-device timeline shows no device chips, lanes, legend, or scrubber bands", () => {
    const singleTrace = multiDeviceTrace.map((r) => { const { device: _device, ...rest } = r as Record<string, unknown>; return rest; });
    const out = renderViewer({ generatedAt: "now", sessions: [sessionOf(singleTrace)] }, {});
    expect(out).not.toContain("devchip");
    expect(out).not.toContain("devlane");
    expect(out).not.toContain("devlegend");
    expect(out).not.toContain("scrublane");
    expect(out).not.toContain("One band per device");
  });
});

describe("Compare view (run-vs-run tool-call and event-stream diffs)", () => {
  const callRow = (i: number, label: string, body: string[], extra: Record<string, unknown> = {}) => ({
    i, label, tool: "", note: null, ms: 0, ts: null, ok: true, err: null, screenshotFile: null,
    objective: false, trailhead: false, count: null, mark: null,
    args: `- ${label}:\n${body.map((l) => `    ${l}`).join("\n")}`, ...extra,
  });
  const stream = (name: string, payloads: unknown[]) => ({
    name, total: payloads.length, truncated: false,
    events: payloads.map((data, idx) => ({ t: 1000 + idx, d: JSON.stringify(data) })),
  });
  const run = (deviceClassifier: string, trace: unknown[], events: unknown[]) => ({
    meta: { title: "Checkout", status: "passed", trailId: "checkout/pay", platform: "android", deviceClassifier },
    trace, llm: [], shots: {}, recordingYaml: null, events,
  });
  const payload = {
    generatedAt: "now",
    sessions: [
      run("android-phone", [
        callRow(1, "launchApp", ["appId: com.example.pos"]),
        callRow(2, "inputText", ["text: Coffee", "selector:", "  id: search"]),
      ], [stream("analytics", [{ Event: "Tap" }, { Event: "Tap" }, { Event: "View" }])]),
      run("android-tablet", [
        callRow(1, "launchApp", ["appId: com.example.pos"]),
        callRow(2, "inputText", ["text: Bagel", "selector:", "  id: search"]),
        callRow(3, "tapOnElementBySelector", ["selector:", "  id: checkout"]),
      ], [stream("analytics", [{ Event: "Tap" }, { Event: "Tap" }, { Event: "Tap" }, { Event: "View" }])]),
    ],
  };

  test("the run index offers Compare exactly when two payload-carrying runs are loaded", () => {
    expect(renderViewer(payload, { query: "?view=runs" })).toContain("data-goto-compare");
    // One run has nothing to compare against.
    const single = { generatedAt: "now", sessions: [payload.sessions[0]] };
    expect(renderViewer({ ...single, sessions: [...single.sessions] }, { query: "?run=0" })).not.toContain("data-goto-compare");
    // Link-out stubs carry no payload to diff.
    const linked = { generatedAt: "now", sessions: [payload.sessions[0], { ...payload.sessions[1], meta: { ...payload.sessions[1].meta, linkOut: true } }] };
    expect(renderViewer(linked, { query: "?view=runs" })).not.toContain("data-goto-compare");
    // And a compare route on such a document falls back to the index rather than a broken diff.
    expect(renderViewer(linked, { query: "?view=compare" })).toContain('class="idxsummary"');
  });

  test("the view diffs tool calls as one unified diff, agreement collapsed in place", () => {
    const out = renderViewer(payload, { query: "?view=compare" });
    // Both runs are pickable, with the device naming each option.
    expect(out).toContain('data-cmp-side="base"');
    expect(out).toContain("android-phone");
    expect(out).toContain("android-tablet");
    // A replaced value prints as the git pair — baseline line, then the line that replaced it.
    expect(out).toContain('<span class="dl dl-del">− text: <mark class="dlhi">Coffee</mark></span><span class="dl dl-add">+ text: <mark class="dlhi">Bagel</mark></span>');
    // A call only one run made is wholly signed, so the gutter alone says which run it came from.
    expect(out).toContain("only in current");
    expect(out).toContain("tapOnElementBySelector");
    expect(out).toContain('<span class="dl dl-add">+   id: checkout</span>'); // indent preserved: nested under selector:
    // Identical calls collapse to a gap line where they happened, not to a footnote at the end:
    // the reader keeps the shape of the run — how far the two agreed, then where they parted.
    expect(out).not.toContain("<code>launchApp</code>");
    expect(out).toContain("⋯ 1 matching tool call");
    expect(out).not.toContain("identical tool call(s) not shown");
    // Each diff row can jump to the call in either run's timeline.
    expect(out).toContain('data-cmp-open="0:2"');
    expect(out).toContain('data-cmp-open="1:2"');
  });

  // A many-trail report used to open on the document's first two runs, which are routinely two
  // DIFFERENT trails. The view then diffs them as confidently as it diffs a repeat, and a search
  // trail's step reads as "changed" into a navigation trail's — a difference that is not a change.
  // The gap line is a control, not just elision: expanded, it lists the identical calls dimmed,
  // so "what did the two runs agree ON" is answerable without leaving the diff.
  test("a collapsed gap expands to the identical calls it stands for", () => {
    const collapsed = renderViewer(payload, { query: "?view=compare" });
    expect(collapsed).toContain("⋯ 1 matching tool call — show");
    expect(collapsed).not.toContain('class="cmpsame"');

    const expanded = renderViewer(payload, { query: "?view=compare", cmpGap: 0 });
    expect(expanded).toContain("⋯ 1 matching tool call — hide");
    expect(expanded).toContain('class="cmpsame"');
    expect(expanded).toContain("<code>launchApp</code>");
  });

  // Each hunk carries the screens both runs were on when the call happened — the trail context the
  // argument text can't. A call that captured nothing borrows the most recent frame before it, and
  // a hunk whose screens haven't moved since the previous hunk repeats nothing.
  test("a hunk shows both runs' screens, once per scene change", () => {
    const shotRun = (deviceClassifier: string, trace: unknown[], shots: Record<string, string>) => ({
      meta: { title: "Checkout", status: "passed", trailId: "checkout/pay", platform: "android", deviceClassifier },
      trace, llm: [], shots, recordingYaml: null, events: [],
    });
    const withShots = {
      generatedAt: "now",
      sessions: [
        shotRun("android-phone", [
          callRow(1, "launchApp", ["appId: a"], { screenshotFile: "b1.png" }),
          callRow(2, "inputText", ["text: Coffee"]),
        ], { "b1.png": "data:image/png;base64,AA==" }),
        shotRun("android-tablet", [
          callRow(1, "launchApp", ["appId: a"], { screenshotFile: "c1.png" }),
          callRow(2, "inputText", ["text: Bagel"]),
          callRow(3, "wait", ["ms: 1"]),
        ], { "c1.png": "data:image/png;base64,BB==" }),
      ],
    };
    const out = renderViewer(withShots, { query: "?view=compare&base=0&vs=1" });
    // The inputText hunk captured nothing itself: both sides borrow launchApp's frame.
    expect(out).toContain('data-shot="b1.png" data-shot-run="0"');
    expect(out).toContain('data-shot="c1.png" data-shot-run="1"');
    expect(out).toContain('class="cmpframecap">baseline<');
    expect(out).toContain('class="cmpframecap">current<');
    // Two hunks (inputText changed, wait only-in-current) but one scene: the frames print once.
    expect((out.match(/cmphunkframes/g) || []).length).toBe(1);
  });

  // The reader's first question is "what KINDS of difference are there" — the overview answers it
  // per lane, and each card is also the control that narrows the page to that lane.
  test("the overview summarises each lane, and a card narrows the page to its lane", () => {
    const out = renderViewer(payload, { query: "?view=compare" });
    expect(out).toContain("1 args changed · 1 only in current");
    expect(out).toContain("1 of 1 stream differ");
    expect(out).toContain("no screenshots");
    expect(out).toContain("<h2>Tool calls</h2>");
    expect(out).toContain("<h2>Event streams</h2>");
    expect(out).toContain("<h2>Screens</h2>");

    // The lane choice rides the URL, so a shared link opens on the same slice.
    const narrowed = renderViewer(payload, { query: "?view=compare&lane=tools" });
    expect(narrowed).toContain('data-cmp-lane="tools" aria-pressed="true"');
    expect(narrowed).toContain("<h2>Tool calls</h2>");
    expect(narrowed).not.toContain("<h2>Event streams</h2>");
    expect(narrowed).not.toContain("<h2>Screens</h2>");

    // Clicking the active card restores the whole diff rather than trapping the reader in a lane.
    const restored = renderViewer(payload, { query: "?view=compare&lane=tools", cmpLane: "tools" });
    expect(restored).toContain("<h2>Event streams</h2>");
    expect(restored).toContain("<h2>Screens</h2>");
  });

  // "I only care about analytics" is a real way to read the events lane: one chip per stream
  // narrows the section to that stream alone.
  test("a stream chip narrows the events lane to that stream alone", () => {
    const twoStreams = {
      generatedAt: "now",
      sessions: [
        run("android-phone", [callRow(1, "launchApp", ["appId: a"])],
          [stream("analytics", [{ Event: "Tap" }]), stream("network", [{ url: "/pay" }])]),
        run("android-tablet", [callRow(1, "launchApp", ["appId: a"])],
          [stream("analytics", [{ Event: "Tap" }, { Event: "View" }]), stream("network", [{ url: "/pay" }])]),
      ],
    };
    const out = renderViewer(twoStreams, { query: "?view=compare&base=0&vs=1" });
    expect(out).toContain('data-cmp-stream=""'); // the All-streams chip
    expect(out).toContain('data-cmp-stream="analytics"');
    expect(out).toContain("network (1)"); // the unchanged stream is still accounted for

    const narrowed = renderViewer(twoStreams, { query: "?view=compare&base=0&vs=1&stream=analytics" });
    expect(narrowed).toContain("<code>analytics</code>");
    expect(narrowed).not.toContain("network (1)");

    // A filter naming a stream this pair does not carry filters nothing — an empty lane would
    // read as "no event differences", which is not what the data says.
    const stale = renderViewer(twoStreams, { query: "?view=compare&base=0&vs=1&stream=missing" });
    expect(stale).toContain("<code>analytics</code>");
    expect(stale).toContain("network (1)");
  });

  // The screens lane reduces the aligned run to its scene changes and pairs both runs' frames at
  // each one. Pixel verdicts need image decoding this environment does not have, so the cells hold
  // the comparing state; the states a comparison can't reach are spelled out, not folded into "match".
  test("the screens lane pairs each scene's frames and says what it could not compare", () => {
    const shotRun = (deviceClassifier: string, trace: unknown[], shots: Record<string, string>) => ({
      meta: { title: "Checkout", status: "passed", trailId: "checkout/pay", platform: "android", deviceClassifier },
      trace, llm: [], shots, recordingYaml: null, events: [],
    });
    const withShots = {
      generatedAt: "now",
      sessions: [
        shotRun("android-phone", [
          callRow(1, "launchApp", ["appId: a"], { screenshotFile: "b1.png" }),
          callRow(2, "inputText", ["text: Coffee"]),
        ], { "b1.png": "data:image/png;base64,AA==" }),
        shotRun("android-tablet", [
          callRow(1, "launchApp", ["appId: a"], { screenshotFile: "c1.png" }),
          callRow(2, "inputText", ["text: Bagel"]),
        ], { "c1.png": "data:image/png;base64,BB==" }),
      ],
    };
    const out = renderViewer(withShots, { query: "?view=compare&base=0&vs=1" });
    expect(out).toContain('class="cmpscenes"');
    expect(out).toContain("comparing pixels…");
    expect(out).toContain("1 scene · comparing pixels…"); // the overview card tracks the queue

    // A scene one run never captured has nothing to diff — the cell says which side is missing.
    const oneSided = {
      generatedAt: "now",
      sessions: [withShots.sessions[0], shotRun("android-tablet", [callRow(1, "launchApp", ["appId: a"])], {})],
    };
    expect(renderViewer(oneSided, { query: "?view=compare&base=0&vs=1" }))
      .toContain("only the baseline run has a frame here");
  });

  test("opens on a same-trail pair rather than the document's first two runs", () => {
    const other = (deviceClassifier: string) => ({
      meta: { title: "Refund", status: "passed", trailId: "refund/issue", platform: "android", deviceClassifier },
      trace: [callRow(1, "launchApp", ["appId: com.example.pos"])], llm: [], shots: {}, recordingYaml: null, events: [],
    });
    // Document order interleaves the trails, so runs 0 and 1 are different trails.
    const mixed = { generatedAt: "now", sessions: [payload.sessions[0], other("android-phone"), payload.sessions[1], other("android-tablet")] };

    const out = renderViewer(mixed, { query: "?view=compare" });

    // Runs 0 and 2 are the Checkout pair; the view opens on them, not on 0 and 1.
    expect(out).toContain('data-cmp-open="0:2"');
    expect(out).toContain('data-cmp-open="2:2"');
    expect(out).toContain('<span class="dl dl-del">− text: <mark class="dlhi">Coffee</mark></span><span class="dl dl-add">+ text: <mark class="dlhi">Bagel</mark></span>');
    expect(out).not.toContain("These are different trails");
  });

  test("picking across trails still diffs, but says the pairing carries no intent", () => {
    const other = {
      meta: { title: "Refund", status: "passed", trailId: "refund/issue", platform: "android", deviceClassifier: "android-phone" },
      trace: [callRow(1, "launchApp", ["appId: com.example.pos"])], llm: [], shots: {}, recordingYaml: null, events: [],
    };
    const mixed = { generatedAt: "now", sessions: [payload.sessions[0], other] };

    const out = renderViewer(mixed, { query: "?view=compare&base=0&vs=1" });

    expect(out).toContain("These are different trails");
    expect(out).toContain("Checkout");
    expect(out).toContain("Refund");
    // Named explicitly, so it is a choice the reader made rather than one the view made for them.
    expect(out).toContain("not by intent");
  });

  // An empty identity is "unidentified", not a trail two runs can share. Comparing the two keys
  // for equality alone made two identity-less runs pass as the same trail and suppressed the note
  // in the one case the reader most needs it.
  test("runs carrying no trail identity are labelled unknown, not silently treated as one trail", () => {
    const anonymous = (deviceClassifier: string, text: string) => ({
      meta: { status: "passed", platform: "android", deviceClassifier },
      trace: [callRow(1, "inputText", [`text: ${text}`])], llm: [], shots: {}, recordingYaml: null, events: [],
    });
    const unnamed = { generatedAt: "now", sessions: [anonymous("android-phone", "Coffee"), anonymous("android-tablet", "Bagel")] };

    const out = renderViewer(unnamed, { query: "?view=compare&base=0&vs=1" });

    expect(out).toContain("These runs carry no trail identity");
    expect(out).not.toContain("These are different trails");
    // Still diffed — the reader asked for this pair; only the claim about it is withheld.
    expect(out).toContain('<span class="dl dl-del">− text: <mark class="dlhi">Coffee</mark></span><span class="dl dl-add">+ text: <mark class="dlhi">Bagel</mark></span>');
  });

  // Grouping the picker by trail puts the runs that can meaningfully pair under one heading, so the
  // structure the comparison depends on is visible before the reader picks.
  test("the picker groups its options by trail when the document holds more than one", () => {
    const other = {
      meta: { title: "Refund", status: "passed", trailId: "refund/issue", platform: "android", deviceClassifier: "android-phone" },
      trace: [callRow(1, "launchApp", ["appId: com.example.pos"])], llm: [], shots: {}, recordingYaml: null, events: [],
    };
    const mixed = { generatedAt: "now", sessions: [payload.sessions[0], other, payload.sessions[1]] };

    expect(renderViewer(mixed, { query: "?view=compare" })).toContain('<optgroup label="Checkout">');
    // One trail needs no grouping — a single heading over every option is noise.
    expect(renderViewer(payload, { query: "?view=compare" })).not.toContain("<optgroup");
  });

  test("the view groups event streams by the auto-detected key and reports per-group deltas", () => {
    const out = renderViewer(payload, { query: "?view=compare" });
    expect(out).toContain("<code>analytics</code>");
    expect(out).toContain("3 → 4");
    // The grouping key was detected from the payloads, not configured.
    expect(out).toContain("<th>Event</th>");
    expect(out).toContain("<td class=\"cmpkey\">Tap</td>");
    // View (1→1) is unchanged, so only Tap's delta row renders.
    expect(out).toContain("1 group(s) unchanged");
  });

  test("each changed stream lists its events in order, one row each, with the extra one signed", () => {
    const out = renderViewer(payload, { query: "?view=compare" });
    // The list reads like a file of the events that fired: every event both runs share is a
    // context row, and the current run's extra Tap is the one row carrying a +.
    // The summary answers "how much of this run differs, and is it one place or all over" before
    // the reader counts a single row.
    expect(out).toContain("Events, in order — 1 of 4 differ (25%) in one place · 1 added");
    expect(out).toContain('class="dl dlrow dl-add" type="button" data-cmp-event="analytics:2"');
    expect(out).toContain("+ Tap");
    expect(out).toContain('class="dl dlrow dl-ctx" type="button" data-cmp-event="analytics:0"');
    // The fields stay behind the row until it's clicked.
    expect(out).not.toContain("cmpevtdetail");
  });

  test("clicking an event row reveals that event's fields under it", () => {
    const out = renderViewer(payload, { query: "?view=compare", cmpEvent: "analytics:2" });
    expect(out).toContain('data-cmp-event="analytics:2" data-cmp-anchor="analytics|0" aria-expanded="true"');
    expect(out).toContain("cmpevtdetail");
    expect(out).toContain("Event: &quot;Tap&quot;");
  });

  // A long stream with four separate places the runs part, so the summary, the fold positions and
  // the stepper all have something real to describe. Event ids are unique across both runs, so the
  // id field masks out and the events pair on their names.
  const scatteredPayload = () => {
    let id = 0;
    const cycle = (names: string[]) => names.map((Event) => ({ Event, id: `e${++id}` }));
    const filler = (n: number) => cycle(Array.from({ length: n }, () => "Tap"));
    const baseline = [...filler(20), { Event: "Promo", id: `e${++id}` }, ...filler(20), { Event: "Pay", amount: 1450, id: `e${++id}` }, ...filler(20)];
    id = 500;
    const current = [...filler(20), ...filler(20), { Event: "Pay", amount: 1625, id: `e${++id}` }, ...filler(19), { Event: "Search", id: `e${++id}` }];
    return {
      generatedAt: "now",
      sessions: [
        run("android-phone", [callRow(1, "launchApp", ["appId: com.example.pos"])], [stream("analytics", baseline)]),
        run("android-tablet", [callRow(1, "launchApp", ["appId: com.example.pos"])], [stream("analytics", current)]),
      ],
    };
  };

  test("the summary says how much of the run differs and whether it went wrong in one place", () => {
    const out = renderViewer(scatteredPayload(), { query: "?view=compare" });
    expect(out).toMatch(/Events, in order — \d+ of \d+ differ \(\d+%\) in \d+ places/);
  });

  test("a fold says which stretch of the run it stands for", () => {
    const out = renderViewer(scatteredPayload(), { query: "?view=compare" });
    // Not just "16 matching events" — where those events sit, so "early or late in the run" stops
    // being arithmetic on row numbers.
    expect(out).toMatch(/⋯ \d+ matching events \(\d+%–\d+%\)/);
  });

  // Percentages across a handful of events say less than the rows either side of the fold already do.
  test("a short stream's folds stay bare", () => {
    const shortPayload = {
      generatedAt: "now",
      sessions: [
        run("android-phone", [callRow(1, "launchApp", ["appId: com.example.pos"])],
          [stream("analytics", [{ Event: "A", id: "a1" }, { Event: "B", id: "a2" }, { Event: "C", id: "a3" }, { Event: "D", id: "a4" }, { Event: "E", id: "a5" }, { Event: "F", id: "a6" }, { Event: "G", id: "a7" }])]),
        run("android-tablet", [callRow(1, "launchApp", ["appId: com.example.pos"])],
          [stream("analytics", [{ Event: "A", id: "b1" }, { Event: "B", id: "b2" }, { Event: "C", id: "b3" }, { Event: "D", id: "b4" }, { Event: "E", id: "b5" }, { Event: "F", id: "b6" }, { Event: "Z", id: "b7" }])]),
      ],
    };
    const out = renderViewer(shortPayload, { query: "?view=compare" });
    expect(out).toContain("matching events");
    expect(out).not.toMatch(/matching events \(\d+%–\d+%\)/);
  });

  test("each place the runs diverge gets one anchor, and the stepper walks them", () => {
    const view = renderViewerState(scatteredPayload(), { query: "?view=compare" });
    const anchors = [...view.html.matchAll(/data-cmp-anchor="([^"]+)"/g)].map((m) => m[1]);
    // One per place, numbered in order — not one per differing row.
    expect(anchors).toEqual(["analytics|0", "analytics|1", "analytics|2"]);
    expect(view.html).toContain("3 places differ");
    expect(view.html).toContain('data-cmp-jump="analytics|next"');
  });

  test("stepping moves the page to each difference in turn, and wraps", () => {
    const first = renderViewerState(scatteredPayload(), { query: "?view=compare", cmpJump: "analytics|next" });
    expect(first.cmpScrolledTo()).toBe("analytics|0");
    const second = renderViewerState(scatteredPayload(), { query: "?view=compare", cmpJump: ["analytics|next", "analytics|next"] });
    expect(second.cmpScrolledTo()).toBe("analytics|1");
    // The first press of ↑ goes to the last difference rather than nowhere.
    const back = renderViewerState(scatteredPayload(), { query: "?view=compare", cmpJump: "analytics|prev" });
    expect(back.cmpScrolledTo()).toBe("analytics|2");
  });

  // One place to reach is the place the reader is already looking at.
  test("a stream that differs in one place gets no stepper", () => {
    const out = renderViewer(payload, { query: "?view=compare" });
    expect(out).toContain("Events, in order —");
    expect(out).not.toContain("cmpstepper");
  });

  // Masked fields are the ones the comparison never looked at, so a reader who can't see their
  // names can't tell "these runs agree" from "the field that disagreed is one we hid".
  test("a stream names the fields its comparison left out", () => {
    const out = renderViewer(scatteredPayload(), { query: "?view=compare" });
    // `id` never repeats across the two runs, so it was masked; `Event` and `amount` repeat and
    // were compared.
    expect(out).toContain("⊘ Not compared here: id");
    expect(out).not.toContain("Not compared here: Event");
    expect(out).toContain("left out of the comparison");
    // A comparison that looked at everything says nothing — both the per-stream list and the note
    // explaining it are about an exception.
    const nothingMasked = renderViewer(payload, { query: "?view=compare" });
    expect(nothingMasked).not.toContain("Not compared here");
    expect(nothingMasked).not.toContain("left out of the comparison");
  });

  // Every difference below reads differently depending on which side failed, and the picker labels
  // alone don't say. Without this the reader has to leave the view to find out.
  test("each picker says how the run it points at ended", () => {
    const failed = {
      generatedAt: "now",
      sessions: [
        payload.sessions[0],
        { ...payload.sessions[1], meta: { ...payload.sessions[1].meta, status: "failed" } },
      ],
    };
    const out = renderViewer(failed, { query: "?view=compare&base=0&vs=1" });
    expect(out).toContain('<span class="badge passed" title="The baseline run passed">passed</span>');
    expect(out).toContain('<span class="badge failed" title="The current run failed">failed</span>');
  });

  // `indexOutcome` folds cancelled, running and unstamped runs all into `other`, which would render
  // a badge reading `other` under the title "The baseline run other". The generator distinguishes
  // them, so the badge has to as well — a cancelled run called "no result" is a wrong answer.
  test("a run that neither passed nor failed reports its actual status", () => {
    const withStatus = (status: string | undefined) => renderViewer({
      generatedAt: "now",
      sessions: [
        { ...payload.sessions[0], meta: { ...payload.sessions[0].meta, status } },
        payload.sessions[1],
      ],
    }, { query: "?view=compare&base=0&vs=1" });

    expect(withStatus("cancelled")).toContain('<span class="badge unknown" title="The baseline run was cancelled">cancelled</span>');
    expect(withStatus("running")).toContain('<span class="badge unknown" title="The baseline run is still running">running</span>');
    // Only a run carrying no status at all — or the generator's own `unknown` — is "no result".
    expect(withStatus(undefined)).toContain('<span class="badge unknown" title="The baseline run has no recorded outcome">no result</span>');
    expect(withStatus("unknown")).toContain('>no result</span>');
    // Whatever the status, the enum name never reaches the reader and the title stays a sentence.
    ["cancelled", "running", "unknown", undefined].forEach((status) => {
      expect(withStatus(status)).not.toContain(">other<");
      expect(withStatus(status)).not.toContain("run other");
    });
  });

  // Masking is what made the two runs agree, so an unchanged stream is exactly where the reader
  // most needs to know which fields went uncompared — and it has no diff section to hang the chip
  // on, so the disclosure has to ride the summary sentence instead.
  test("a stream that masking made identical still names what it left out", () => {
    const ids = (prefix: string) => Array.from({ length: 4 }, (_, i) => ({ Event: "Tap", id: `${prefix}${i}` }));
    const maskedIntoAgreement = {
      generatedAt: "now",
      sessions: [
        run("android-phone", [callRow(1, "launchApp", ["appId: a"])], [stream("audit", ids("base-"))]),
        run("android-tablet", [callRow(1, "launchApp", ["appId: a"])], [stream("audit", ids("curr-"))]),
      ],
    };
    const out = renderViewer(maskedIntoAgreement, { query: "?view=compare" });
    // Every `id` differs, but each is unique, so the field is masked and the stream compares equal.
    expect(out).toContain("1 stream(s) unchanged: audit (4, not compared: id)");
    // And the note explaining why a field went uncompared fires off the unchanged stream too.
    expect(out).toContain("left out of the comparison");
    // A stream that really was compared end to end carries no such tail.
    expect(renderViewer(payload, { query: "?view=compare" })).not.toContain("not compared:");
  });

  test("a stream with identical counts but different payload content is surfaced, with the per-line change", () => {
    const contentPayload = {
      generatedAt: "now",
      sessions: [
        run("android-phone", [callRow(1, "launchApp", ["appId: com.example.pos"])], [stream("flags", [{ name: "x", on: true }, { name: "y", on: true }])]),
        run("android-tablet", [callRow(1, "launchApp", ["appId: com.example.pos"])], [stream("flags", [{ name: "x", on: false }, { name: "y", on: true }])]),
      ],
    };
    const out = renderViewer(contentPayload, { query: "?view=compare" });
    // Counts and group mix are identical — only the payload content flags this stream.
    expect(out).toContain("same counts, content differs");
    // The changed event is two rows leading with the event it is — `x` — and the changed value is
    // marked whole on the summary itself, so the reader sees what changed without opening anything.
    expect(out).toContain('− x  on=<mark class="dlhi">true</mark>');
    expect(out).toContain('+ x  on=<mark class="dlhi">false</mark>');
    // The unchanged `y` still lists, in place, so the change is read against the whole stream.
    expect(out).toContain("  y  on=true");
    // A diff this small shows itself instead of hiding behind the expander.
    expect(out).toContain('<details class="cmpdiffwrap" open>');
  });

  test("opening a changed event shows the per-line diff of its fields", () => {
    const contentPayload = {
      generatedAt: "now",
      sessions: [
        run("android-phone", [callRow(1, "launchApp", ["appId: com.example.pos"])], [stream("flags", [{ name: "x", on: true }, { name: "y", on: true }])]),
        run("android-tablet", [callRow(1, "launchApp", ["appId: com.example.pos"])], [stream("flags", [{ name: "x", on: false }, { name: "y", on: true }])]),
      ],
    };
    const out = renderViewer(contentPayload, { query: "?view=compare", cmpEvent: "flags:0" });
    // Behind the row: the git-style ± line diff, with the event's stable fields as context.
    expect(out).toContain('<span class="dl dl-del">− on: <mark class="dlhi">true</mark></span>');
    expect(out).toContain('<span class="dl dl-add">+ on: <mark class="dlhi">false</mark></span>');
    expect(out).toContain("name: &quot;x&quot;");
  });

  // A wall of differing events renders behind the expander, and the click that asks for one event's
  // fields re-renders the section — so the stream has to stay open across that render, or the
  // reader's click collapses the very thing they opened it to read.
  test("opening an event inside a large stream diff leaves the stream expanded", () => {
    const flag = (i: number, on: boolean) => ({ name: `f${i}`, on });
    const bigPayload = {
      generatedAt: "now",
      sessions: [
        run("android-phone", [callRow(1, "launchApp", ["appId: com.example.pos"])],
          [stream("flags", Array.from({ length: 12 }, (_, i) => flag(i, true)))]),
        run("android-tablet", [callRow(1, "launchApp", ["appId: com.example.pos"])],
          [stream("flags", Array.from({ length: 12 }, (_, i) => flag(i, false)))]),
      ],
    };
    expect(renderViewer(bigPayload, { query: "?view=compare" })).toContain('<details class="cmpdiffwrap"><summary>');
    const opened = renderViewer(bigPayload, { query: "?view=compare", cmpEvent: "flags:0" });
    expect(opened).toContain('<details class="cmpdiffwrap" open>');
    expect(opened).toContain('<span class="dl dl-add">+ on: <mark class="dlhi">false</mark></span>');
  });

  // The re-render detaches the row that was just activated, which drops focus to the document and
  // strands a keyboard reader at the top of the page.
  test("expanding an event or a gap hands focus to the row that replaced it", () => {
    const event = renderViewerState(payload, { query: "?view=compare", cmpEvent: "analytics:2" });
    expect(event.readRestoredFocus()).toBe('[data-cmp-event="analytics:2"]');
    const gap = renderViewerState(payload, { query: "?view=compare", cmpGap: 0 });
    expect(gap.readRestoredFocus()).toBe('[data-cmp-gap="0"]');
  });

  // Both sides holding the same run is not a comparison, and the pair-normalizer would quietly
  // substitute a partner when that URL is reopened — so the picker swaps instead.
  test("picking the run the other side holds swaps the pair rather than pairing a run with itself", () => {
    const state = renderViewerState(payload, { query: "?view=compare&base=0&vs=1", cmpSide: { side: "base", value: 1 } });
    expect(state.readRoute()).toContain("base=1");
    expect(state.readRoute()).toContain("vs=0");
  });

  // Clearing the lane filter has to clear it out of the URL too — a key the viewer writes but
  // never deletes leaves a shared link opening on a lane the reader had already left.
  test("clicking the active lane card drops the lane out of the URL", () => {
    const state = renderViewerState(payload, { query: "?view=compare&lane=tools", cmpLane: "tools" });
    expect(state.readRoute()).not.toContain("lane=");
  });

  // A run index that lists skipped rows as link-out stubs next to real runs is ordinary, so the
  // stubs drop out of the pickers rather than disqualifying the whole report.
  test("link-out stubs stay out of the pickers, and a route naming one falls back to a real run", () => {
    const stub = { ...payload.sessions[0], meta: { ...payload.sessions[0].meta, linkOut: true } };
    const withStub = { generatedAt: "now", sessions: [stub, payload.sessions[0], payload.sessions[1]] };
    const out = renderViewer(withStub, { query: "?view=compare&base=0&vs=2" });
    expect(out).toContain('data-cmp-side="base"');
    expect(out).not.toContain('<option value="0"');
    // base=0 named the stub, so it snaps to the first comparable run; vs=2 was already valid.
    expect(out).toMatch(/data-cmp-side="base"[^>]*>[^]*?value="1" selected/);
    expect(out).toMatch(/data-cmp-side="vs"[^>]*>[^]*?value="2" selected/);
  });

  // A payload that arrived but wouldn't inflate compares as if the run captured nothing. Silence
  // there would read as "this run emitted no events" — the opposite of what happened.
  test("an event payload that cannot be decoded says so instead of reading as no events", async () => {
    const broken = {
      generatedAt: "now",
      sessions: payload.sessions.map((s) => ({ ...s, events: null, eventsGz: Buffer.from("not actually gzip").toString("base64") })),
    };
    const state = renderViewerState(broken, { query: "?view=compare" });
    for (let i = 0; i < 100 && state.readHtml().includes("Inflating event payloads"); i++) {
      await new Promise((resolve) => setTimeout(resolve, 5));
    }
    const out = state.readHtml();
    expect(out).toContain("could not be decoded");
    expect(out).not.toContain("Inflating event payloads");
  });

  test("the baseline and current pickers ride the route so a copied link reopens the same pair", () => {
    const out = renderViewer(payload, { query: "?view=compare&base=1&vs=0" });
    expect(out).toContain('data-cmp-side="base"');
    // base=1 selects the second run as baseline: its option is the selected one.
    expect(out).toMatch(/data-cmp-side="base"[^>]*>[^]*?value="1" selected/);
    expect(out).toContain('<span class="dl dl-del">− text: <mark class="dlhi">Bagel</mark></span><span class="dl dl-add">+ text: <mark class="dlhi">Coffee</mark></span>');
    expect(out).toContain("only in baseline");
  });
});
