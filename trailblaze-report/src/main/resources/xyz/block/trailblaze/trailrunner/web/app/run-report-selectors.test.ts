// Behavior tests for the UI Inspector's selector-suggestions helpers (run-report-selectors.ts).
// These pin the observable contracts: which trees qualify for suggestions, the inspector-key →
// nodeId mapping the engine is targeted with, the trail-file-faithful YAML each copy button
// yields, the rendered suggestion cards, and the graceful-absence paths of the engine loader.
// The engine itself is stubbed at its DOCUMENTED contract — the raw string-in/string-out global
// the Kotlin/JS bundle installs (see SelectorEngineJs.kt) — never a laundered internal type; the
// real compiled engine is exercised by :trailblaze-selector-engine-js's own parity suite.
//
// Run: `bun test run-report-selectors.test.ts` from this directory.
import { afterEach, describe, expect, test } from "bun:test";
import { gzipSync } from "zlib";
import {
  inspectorKeyForNodeId,
  isSelectorAnalyzableTree,
  loadSelectorEngineFromChunk,
  mismatchVizHtml,
  nodeIdForInspectorKey,
  selectorSuggestionsHtml,
  selectorSuggestionYaml,
} from "./run-report-selectors";

// A TrailblazeNode tree as an accessibility-driver session logs it (`driverDetail` under the
// "class" discriminator) — the shape the engine analyzes.
const trailblazeNodeTree = {
  nodeId: 3,
  bounds: { left: 0, top: 0, right: 1080, bottom: 2400 },
  driverDetail: { class: "androidAccessibility", className: "android.widget.FrameLayout" },
  children: [
    {
      nodeId: 1,
      bounds: { left: 90, top: 600, right: 990, bottom: 720 },
      driverDetail: { class: "androidAccessibility", text: "Login", className: "android.widget.Button" },
    },
    {
      nodeId: 2,
      bounds: { left: 90, top: 800, right: 990, bottom: 920 },
      driverDetail: { class: "androidAccessibility", text: "Help", className: "android.widget.Button" },
    },
  ],
};

// The legacy ViewHierarchyTreeNode shape (older logs) — carries nodeId too, so the discriminator
// must be the required TrailblazeNode `driverDetail`, not id presence.
const legacyTree = {
  nodeId: 1,
  className: "android.widget.FrameLayout",
  x1: 0, y1: 0, x2: 1080, y2: 2400,
  children: [{ nodeId: 2, text: "Login", x1: 90, y1: 600, x2: 990, y2: 720 }],
};

describe("isSelectorAnalyzableTree", () => {
  test("accepts a TrailblazeNode tree and rejects the legacy shape", () => {
    expect(isSelectorAnalyzableTree(trailblazeNodeTree)).toBe(true);
    expect(isSelectorAnalyzableTree(legacyTree)).toBe(false);
  });

  test("rejects non-tree values outright", () => {
    expect(isSelectorAnalyzableTree(null)).toBe(false);
    expect(isSelectorAnalyzableTree("tree")).toBe(false);
    expect(isSelectorAnalyzableTree([trailblazeNodeTree])).toBe(false);
    expect(isSelectorAnalyzableTree({ driverDetail: "web" })).toBe(false);
  });
});

describe("nodeIdForInspectorKey", () => {
  test("maps pre-order inspector keys to the node's nodeId", () => {
    // Same pre-order the inspector model assigns: root=0, first child=1, second child=2.
    expect(nodeIdForInspectorKey(trailblazeNodeTree, 0)).toBe(3);
    expect(nodeIdForInspectorKey(trailblazeNodeTree, 1)).toBe(1);
    expect(nodeIdForInspectorKey(trailblazeNodeTree, 2)).toBe(2);
  });

  test("skips non-object children without consuming a key, like the inspector walk", () => {
    const tree = { nodeId: 10, driverDetail: { class: "web" }, children: [null, { nodeId: 20, driverDetail: { class: "web" } }] };
    expect(nodeIdForInspectorKey(tree, 1)).toBe(20);
  });

  test("an absent nodeId reads as the Kotlin default 0; out-of-range keys read as absent", () => {
    const tree = { driverDetail: { class: "web" } };
    expect(nodeIdForInspectorKey(tree, 0)).toBe(0);
    expect(nodeIdForInspectorKey(tree, 1)).toBeNull();
    expect(nodeIdForInspectorKey(tree, -1)).toBeNull();
    expect(nodeIdForInspectorKey(null, 0)).toBeNull();
  });

  test("inspectorKeyForNodeId is the exact inverse for ids in the tree, null otherwise", () => {
    expect(inspectorKeyForNodeId(trailblazeNodeTree, 3)).toBe(0);
    expect(inspectorKeyForNodeId(trailblazeNodeTree, 1)).toBe(1);
    expect(inspectorKeyForNodeId(trailblazeNodeTree, 2)).toBe(2);
    expect(inspectorKeyForNodeId(trailblazeNodeTree, 99)).toBeNull();
    expect(inspectorKeyForNodeId(null, 3)).toBeNull();
  });
});

describe("selectorSuggestionYaml (trail-file fidelity)", () => {
  // Pinned exactly: this is the recorder's own YAML form — the block a user pastes under a
  // recorded tapOnElementBySelector / assertVisibleBySelector in a trail file. Fixtures mirror
  // committed trails.
  test("a plain textRegex selector renders as the recorder writes it", () => {
    expect(selectorSuggestionYaml({ androidAccessibility: { textRegex: "^Next$" } }))
      .toBe("nodeSelector:\n  androidAccessibility:\n    textRegex: ^Next$");
  });

  test("an index-disambiguated selector keeps index beside the driver block", () => {
    expect(selectorSuggestionYaml({ androidAccessibility: { classNameRegex: "android.widget.FrameLayout" }, index: 0 }))
      .toBe("nodeSelector:\n  androidAccessibility:\n    classNameRegex: android.widget.FrameLayout\n  index: 0");
  });

  test("a containsChild selector nests exactly like the recorded form (colon values quoted)", () => {
    expect(selectorSuggestionYaml({ containsChild: { androidAccessibility: { resourceIdRegex: "android:id/content" } } }))
      .toBe('nodeSelector:\n  containsChild:\n    androidAccessibility:\n      resourceIdRegex: "android:id/content"');
  });

  test("leading-$ price literals stay unescaped and unquoted, as recorded", () => {
    expect(selectorSuggestionYaml({ androidAccessibility: { textRegex: "$5.00" } }))
      .toBe("nodeSelector:\n  androidAccessibility:\n    textRegex: $5.00");
  });
});

describe("selectorSuggestionsHtml", () => {
  const analysis = {
    options: [
      {
        selector: { androidAccessibility: { textRegex: "Login" } },
        strategy: "Text",
        isBest: true,
        matchCount: 1,
        matchingNodeIds: [1],
        resolvedCenterX: 540,
        resolvedCenterY: 660,
        hitsTarget: true,
      },
      {
        selector: { androidAccessibility: { classNameRegex: "android.widget.Button" } },
        strategy: "Class name",
        isBest: false,
        matchCount: 2,
        matchingNodeIds: [1, 2],
        resolvedCenterX: 540,
        resolvedCenterY: 660,
        hitsTarget: false,
      },
      {
        selector: { androidAccessibility: { classNameRegex: "android.widget.Button" }, index: 0 },
        strategy: "Structural: class + index",
        isBest: false,
        matchCount: 1,
        matchingNodeIds: [1],
        resolvedCenterX: 540,
        resolvedCenterY: 660,
        hitsTarget: true,
      },
    ],
  };

  test("renders ranked cards with uniqueness badges, the BEST mark, and tap verification", () => {
    const built = selectorSuggestionsHtml(analysis as any);
    expect(built.html).toContain("Selector suggestions");
    expect(built.html).toContain("UNIQUE");
    expect(built.html).toContain("2 MATCHES");
    expect(built.html).toContain("BEST");
    expect(built.html).toContain("Tap (540, 660) hits this element");
    expect(built.html).toContain("Tap (540, 660) would hit a different element");
    // The structural (content-free) option renders under its own group, strategy prefix stripped.
    expect(built.html).toContain("Structural (content-free)");
    expect(built.html).not.toContain("Structural: class + index");
    expect(built.html).toContain("class + index");
    // One copy button per option, indexed into the returned YAML payloads.
    expect(built.yamls).toHaveLength(3);
    expect(built.html).toContain('data-inspselcopy="0"');
    expect(built.html).toContain('data-inspselcopy="2"');
    expect(built.yamls[0]).toBe("nodeSelector:\n  androidAccessibility:\n    textRegex: Login");
    // The YAML is what the card shows (html-escaped interpolation of the same string).
    expect(built.html).toContain("textRegex: Login");
  });

  test("mismatch cards carry the visualization payload and engagement hook; hitting cards don't", () => {
    const built = selectorSuggestionsHtml(analysis as any);
    // Only the second option (resolved center + !hitsTarget) is a mismatch.
    expect(built.viz).toEqual([null, { tapX: 540, tapY: 660, hitNodeId: null }, null]);
    expect(built.html).toContain('data-inspselviz="1"');
    expect(built.html).not.toContain('data-inspselviz="0"');
    expect(built.html).not.toContain('data-inspselviz="2"');
    expect(built.html).toContain("hover to visualize");
  });

  test("a mismatch with a known hit node names the intercepting element", () => {
    const built = selectorSuggestionsHtml({
      options: [{ selector: { androidAccessibility: { textRegex: "Login" } }, strategy: "Text", isBest: true, matchCount: 1, matchingNodeIds: [1], resolvedCenterX: 540, resolvedCenterY: 660, hitsTarget: false, hitNodeId: 3 }],
    } as any, { hitLabelFor: (nodeId) => (nodeId === 3 ? "<ScrollView>" : null) });
    expect(built.html).toContain("Tap (540, 660) lands on &lt;ScrollView&gt; — not this element");
    expect(built.html).not.toContain("would hit a different element");
    expect(built.viz).toEqual([{ tapX: 540, tapY: 660, hitNodeId: 3 }]);
  });

  // Everything this function interpolates is escaped, including the tap coordinates: the emitted
  // markup can't depend on the engine having typed them as numbers.
  test("nothing reaches the markup unescaped — coordinates and labels alike", () => {
    const built = selectorSuggestionsHtml({
      options: [{ selector: { androidAccessibility: { textRegex: "Login" } }, strategy: "Text", isBest: true, matchCount: 1, matchingNodeIds: [1], resolvedCenterX: '<img src=x onerror="alert(1)">' as unknown as number, resolvedCenterY: 660, hitsTarget: true }],
    } as any);
    expect(built.html).not.toContain("<img");
    expect(built.html).toContain("&lt;img src=x onerror=&quot;alert(1)&quot;&gt;");
  });

  test("the section header names the subject, with a chip when it is a hover preview", () => {
    const one = { options: [analysis.options[0]] } as any;
    const committed = selectorSuggestionsHtml(one, { subjectLabel: '"Login"' });
    expect(committed.html).toContain("&quot;Login&quot;");
    expect(committed.html).not.toContain("hover preview");
    const preview = selectorSuggestionsHtml(one, { subjectLabel: '"Login"', preview: true });
    expect(preview.html).toContain("hover preview");
  });

  test("a matching option with no bounds says tap verification is unavailable", () => {
    const built = selectorSuggestionsHtml({
      options: [{ selector: { web: { ariaName: "Home" } }, strategy: "Name", isBest: true, matchCount: 1, matchingNodeIds: [2], hitsTarget: false }],
    } as any);
    expect(built.html).toContain("No bounds — tap verification unavailable");
    expect(built.html).not.toContain("would hit a different element");
    // No resolved center → nothing to visualize.
    expect(built.viz).toEqual([null]);
  });

  test("renders NOTHING for an absent, failed, or empty analysis — graceful absence", () => {
    expect(selectorSuggestionsHtml(null)).toEqual({ html: "", yamls: [], viz: [] });
    expect(selectorSuggestionsHtml(undefined)).toEqual({ html: "", yamls: [], viz: [] });
    expect(selectorSuggestionsHtml({ options: [], error: "node 9 not found in tree" } as any)).toEqual({ html: "", yamls: [], viz: [] });
    expect(selectorSuggestionsHtml({ options: [] } as any)).toEqual({ html: "", yamls: [], viz: [] });
  });

  test("escapes selector content interpolated into the markup", () => {
    const built = selectorSuggestionsHtml({
      options: [{ selector: { web: { ariaName: '<img src=x onerror="pwn">' } }, strategy: "Name", isBest: false, matchCount: 1, matchingNodeIds: [1], hitsTarget: false }],
    } as any);
    expect(built.html).not.toContain("<img src=x");
    expect(built.html).toContain("&lt;img src=x");
  });
});

describe("mismatchVizHtml", () => {
  const dims = { w: 1000, h: 2000 };
  const target = { x1: 100, y1: 200, x2: 300, y2: 400 };
  const hit = { x1: 0, y1: 0, x2: 1000, y2: 1000 };

  test("paints both bounds, the tap point, and the legend, percentage-positioned", () => {
    const html = mismatchVizHtml({ target, hit, tap: { x: 200, y: 300 }, dims });
    expect(html).toContain('class="inspselvizrect intended"');
    expect(html).toContain("left:10.000%"); // 100 / 1000
    expect(html).toContain("top:10.000%"); // 200 / 2000
    expect(html).toContain("width:20.000%"); // 200 / 1000
    expect(html).toContain('class="inspselvizrect actual"');
    expect(html).toContain('class="inspselviztap"');
    expect(html).toContain("left:20.000%;top:15.000%"); // tap 200/1000, 300/2000
    expect(html).toContain("this element");
    expect(html).toContain("actual tap target");
    expect(html).toContain("tap point");
  });

  test("degrades to the parts it has, and to nothing when it has none", () => {
    const noHit = mismatchVizHtml({ target, hit: null, tap: { x: 200, y: 300 }, dims });
    expect(noHit).toContain("inspselvizrect intended");
    expect(noHit).not.toContain("inspselvizrect actual");
    expect(mismatchVizHtml({ target: null, hit: null, tap: { x: 1, y: 1 }, dims })).toBe("");
    expect(mismatchVizHtml({ target, hit, tap: { x: 1, y: 1 }, dims: null })).toBe("");
  });
});

describe("loadSelectorEngineFromChunk", () => {
  const rawEngine = () => ({
    computeSelectorAnalysis: (_tree: string, nodeId: string) => JSON.stringify({ options: [], error: `stub ${nodeId}` }),
    resolveTapTarget: () => JSON.stringify({ roundTripValid: false }),
    resolveSelector: () => JSON.stringify({ matchCount: 0, matchingNodeIds: [] }),
  });
  afterEach(() => {
    delete (globalThis as Record<string, unknown>).TrailblazeSelectorEngine;
  });

  test("evaluates a gz chunk and returns the typed engine over the installed global", async () => {
    // A miniature stand-in for the real IIFE bundle: installs the documented raw global.
    const code = `globalThis.TrailblazeSelectorEngine = {
      computeSelectorAnalysis: (tree, nodeId) => JSON.stringify({ options: [], error: "from-chunk " + nodeId }),
      resolveTapTarget: () => JSON.stringify({ roundTripValid: false }),
      resolveSelector: () => JSON.stringify({ matchCount: 0, matchingNodeIds: [] }),
    };`;
    const engine = await loadSelectorEngineFromChunk({ gz: gzipSync(code).toString("base64") });
    expect(engine).not.toBeNull();
    expect(engine!.computeSelectorAnalysis(trailblazeNodeTree, 3)).toEqual({ options: [], error: "from-chunk 3" });
  });

  test("an already-installed engine global short-circuits the chunk", async () => {
    (globalThis as Record<string, unknown>).TrailblazeSelectorEngine = rawEngine();
    const engine = await loadSelectorEngineFromChunk(null);
    expect(engine).not.toBeNull();
    expect(engine!.computeSelectorAnalysis(trailblazeNodeTree, 1)).toEqual({ options: [], error: "stub 1" });
  });

  test("absence and failure paths all read as null, never a throw", async () => {
    expect(await loadSelectorEngineFromChunk(null)).toBeNull();
    expect(await loadSelectorEngineFromChunk({} as any)).toBeNull();
    expect(await loadSelectorEngineFromChunk({ gz: "not base64 gzip" })).toBeNull();
    expect(await loadSelectorEngineFromChunk({ js: "this is not ( valid js" })).toBeNull();
    // Code that evaluates but installs nothing usable — a partial global is "absent".
    expect(await loadSelectorEngineFromChunk({ js: "globalThis.TrailblazeSelectorEngine = { computeSelectorAnalysis: () => '{}' };" })).toBeNull();
  });
});
