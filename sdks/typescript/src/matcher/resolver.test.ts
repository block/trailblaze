// Unit tests for the TS-ported matcher.
//
// PORT of `trailblaze-models/src/jvmTest/kotlin/xyz/block/trailblaze/api/TrailblazeNodeSelectorResolverTest.kt`.
//
// Each test mirrors a named case in the Kotlin source. The test names match exactly
// (kebab-cased only where required by `describe`/`test` ergonomics) so a parity-test
// audit can map each Kotlin test to its TS counterpart by name. New cases added here
// must land in the Kotlin file too (and vice versa) — drift will be caught by the
// JVM-side `MatcherParityTest` (lands in a follow-up PR).
//
// Templating tests from the Kotlin suite (`resolver expands target appId placeholder`
// + friends) are NOT ported — see `resolver.ts` file header for rationale (templating
// happens at selector-authoring time in TS, not at match time).

import { describe, expect, test } from "bun:test";

import { selectors } from "../generated/selectors.js";
import type {
  DriverNodeDetailAndroidAccessibility,
  DriverNodeDetailAndroidMaestro,
  DriverNodeDetailCompose,
  DriverNodeDetailIosAxe,
  DriverNodeDetailIosMaestro,
  DriverNodeDetailWeb,
} from "./driver-node-detail.js";
import { resolve, resolveToCenter, type ResolveResult } from "./resolver.js";
import type { Bounds, TrailblazeNode } from "./trailblaze-node.js";

// ----- Fixture helpers -------------------------------------------------------

let nextId = 1;

function bounds(left: number, top: number, right: number, bottom: number): Bounds {
  return { left, top, right, bottom };
}

const DEFAULT_BOUNDS: Bounds = bounds(0, 0, 100, 50);

/** Build a node with an `AndroidAccessibility` detail (most common test fixture). */
function aaNode(
  detail: Partial<DriverNodeDetailAndroidAccessibility> = {},
  opts: { bounds?: Bounds | null; children?: TrailblazeNode[] } = {},
): TrailblazeNode {
  return {
    nodeId: nextId++,
    children: opts.children ?? [],
    bounds: opts.bounds === undefined ? DEFAULT_BOUNDS : opts.bounds,
    driverDetail: { class: "androidAccessibility", ...detail },
  };
}

/** Generic node builder for cross-driver fixtures. */
function nodeOf(
  detail: TrailblazeNode["driverDetail"],
  opts: { bounds?: Bounds | null; children?: TrailblazeNode[] } = {},
): TrailblazeNode {
  return {
    nodeId: nextId++,
    children: opts.children ?? [],
    bounds: opts.bounds === undefined ? DEFAULT_BOUNDS : opts.bounds,
    driverDetail: detail,
  };
}

/** Reset nextId before each test that relies on deterministic ids. */
function resetIds(): void {
  nextId = 1;
}

/** ECMAScript-side equivalent of Kotlin's `Regex.escape(...)` — escapes regex metacharacters. */
function escapeRegex(s: string): string {
  return s.replace(/[\\^$.*+?()[\]{}|]/g, "\\$&");
}

function asSingleMatch(result: ResolveResult): TrailblazeNode {
  if (result.kind !== "singleMatch") {
    throw new Error(`expected SingleMatch, got ${result.kind}`);
  }
  return result.node;
}

function asMultipleMatches(result: ResolveResult): readonly TrailblazeNode[] {
  if (result.kind !== "multipleMatches") {
    throw new Error(`expected MultipleMatches, got ${result.kind}`);
  }
  return result.nodes;
}

// ============================================================================
// Result-shape basics
// ============================================================================

describe("resolve — result shape", () => {
  test("single match returns SingleMatch", () => {
    resetIds();
    const target = aaNode({ text: "Submit" });
    const other = aaNode({ text: "Cancel" });
    const root = aaNode({}, { children: [target, other] });

    const result = resolve(root, selectors.androidAccessibility({ textRegex: "Submit" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("no match returns NoMatch", () => {
    resetIds();
    const root = aaNode({}, {
      children: [aaNode({ text: "A" }), aaNode({ text: "B" })],
    });
    const result = resolve(root, selectors.androidAccessibility({ textRegex: "C" }));
    expect(result.kind).toBe("noMatch");
  });

  test("multiple matches returns MultipleMatches", () => {
    resetIds();
    const root = aaNode({}, {
      children: [aaNode({ text: "Item" }), aaNode({ text: "Item" })],
    });
    const result = resolve(root, selectors.androidAccessibility({ textRegex: "Item" }));
    expect(asMultipleMatches(result)).toHaveLength(2);
  });
});

// ============================================================================
// Android Accessibility — composeTestTag, roleDescription
// ============================================================================

describe("resolve — AndroidAccessibility specific fields", () => {
  test("composeTestTag matches via composeTestTagRegex", () => {
    resetIds();
    const target = aaNode({ composeTestTag: "checkout_btn" });
    const other = aaNode({ composeTestTag: "cancel_btn" });
    const root = aaNode({}, { children: [target, other] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ composeTestTagRegex: "checkout_btn" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("composeTestTagRegex with no testTag in node returns NoMatch", () => {
    // A selector that constrains composeTestTag should not match nodes that don't
    // expose one — `requirePattern` returns false when pattern is set and text is null.
    resetIds();
    const target = aaNode({ text: "Submit" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ composeTestTagRegex: "anything" }),
    );
    expect(result.kind).toBe("noMatch");
  });

  test("roleDescription matches via roleDescriptionRegex", () => {
    resetIds();
    const target = aaNode({
      roleDescription: "Toggle",
      className: "android.widget.ImageButton",
    });
    const other = aaNode({
      roleDescription: "Tab",
      className: "android.widget.ImageButton",
    });
    const root = aaNode({}, { children: [target, other] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ roleDescriptionRegex: "Toggle" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });
});

// ============================================================================
// Regex semantics
// ============================================================================

describe("resolve — regex semantics", () => {
  test("regex special chars in currency are escaped", () => {
    resetIds();
    const target = aaNode({ text: "$3.00" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ textRegex: escapeRegex("$3.00") }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("invalid regex falls back to case-insensitive literal", () => {
    resetIds();
    const target = aaNode({ text: "[unclosed" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ textRegex: "[unclosed" }),
    );
    expect(result.kind).toBe("singleMatch");
  });

  test("full-string match: pattern 'ok' does NOT match text 'book'", () => {
    // Regression for the wrapping in matchesPattern — `Regex(p).matches(t)` on
    // Kotlin is full-string; the TS port wraps via `^(?:p)$`. Without the wrap,
    // `new RegExp("ok").test("book")` returns true (substring).
    resetIds();
    const target = aaNode({ text: "book" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(root, selectors.androidAccessibility({ textRegex: "ok" }));
    expect(result.kind).toBe("noMatch");
  });

  test("inline (?i) flag translates to case-insensitive RegExp", () => {
    // Kotlin's java.util.regex supports `(?i)foo` as a leading inline flag.
    // ECMAScript regex treats `(?i)` as a syntax error. Without translation,
    // the matcher falls back to case-insensitive LITERAL equality, which
    // matches `(?i)Submit` only against the literal text `(?i)Submit` — wrong.
    // With translation, `(?i)Submit` compiles to `new RegExp("^(?:Submit)$", "i")`
    // and matches "submit" / "SUBMIT" / etc. case-insensitively.
    resetIds();
    const target = aaNode({ text: "SUBMIT" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ textRegex: "(?i)Submit" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("inline (?s) flag translates to dotall RegExp", () => {
    // `(?s)` makes `.` match newlines. Without translation, `(?s).+` would
    // fail to compile; with translation, `(?s).+` matches a multi-line text.
    resetIds();
    const target = aaNode({ text: "line1\nline2" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ textRegex: "(?s).+" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("combined inline (?is) flag translates both", () => {
    resetIds();
    const target = aaNode({ text: "Line1\nLINE2" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(
      root,
      selectors.androidAccessibility({ textRegex: "(?is)line1.line2" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });
});

// ============================================================================
// Spatial predicates
// ============================================================================

describe("resolve — spatial predicates", () => {
  test("below predicate matches target below anchor", () => {
    resetIds();
    const anchor = aaNode({ text: "Header" }, { bounds: bounds(0, 0, 400, 50) });
    const target = aaNode({ text: "Content" }, { bounds: bounds(0, 60, 400, 110) });
    const root = aaNode({}, { children: [anchor, target] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Content" }),
      below: selectors.androidAccessibility({ textRegex: "Header" }),
    });
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("below predicate rejects overlapping elements", () => {
    resetIds();
    const anchor = aaNode({ text: "Header" }, { bounds: bounds(0, 0, 400, 100) });
    // target.top=80 < anchor.bottom=100 → overlapping → reject
    const target = aaNode({ text: "Content" }, { bounds: bounds(0, 80, 400, 150) });
    const root = aaNode({}, { children: [anchor, target] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Content" }),
      below: selectors.androidAccessibility({ textRegex: "Header" }),
    });
    expect(result.kind).toBe("noMatch");
  });

  test("above predicate matches target above anchor", () => {
    resetIds();
    const target = aaNode({ text: "Title" }, { bounds: bounds(0, 0, 400, 50) });
    const anchor = aaNode({ text: "Footer" }, { bounds: bounds(0, 60, 400, 110) });
    const root = aaNode({}, { children: [target, anchor] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Title" }),
      above: selectors.androidAccessibility({ textRegex: "Footer" }),
    });
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("leftOf predicate", () => {
    resetIds();
    const target = aaNode({ text: "Left" }, { bounds: bounds(0, 0, 50, 50) });
    const anchor = aaNode({ text: "Right" }, { bounds: bounds(60, 0, 120, 50) });
    const root = aaNode({}, { children: [target, anchor] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Left" }),
      leftOf: selectors.androidAccessibility({ textRegex: "Right" }),
    });
    expect(result.kind).toBe("singleMatch");
  });

  test("rightOf predicate", () => {
    resetIds();
    const anchor = aaNode({ text: "Left" }, { bounds: bounds(0, 0, 50, 50) });
    const target = aaNode({ text: "Right" }, { bounds: bounds(60, 0, 120, 50) });
    const root = aaNode({}, { children: [anchor, target] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Right" }),
      rightOf: selectors.androidAccessibility({ textRegex: "Left" }),
    });
    expect(result.kind).toBe("singleMatch");
  });

  test("spatial predicate rejects when anchor has null bounds", () => {
    // The anchor node matches the spatial sub-selector but has no bounds — the
    // matcher's `resolveFirstBounds` returns null, so the candidate is rejected.
    // Without this branch, a null anchor would incorrectly pass any spatial check.
    resetIds();
    const anchor = aaNode({ text: "Anchor" }, { bounds: null });
    const candidate = aaNode({ text: "Candidate" }, { bounds: bounds(0, 60, 100, 110) });
    const root = aaNode({}, { children: [anchor, candidate] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Candidate" }),
      below: selectors.androidAccessibility({ textRegex: "Anchor" }),
    });
    expect(result.kind).toBe("noMatch");
  });
});

// ============================================================================
// Hierarchy predicates
// ============================================================================

describe("resolve — hierarchy predicates", () => {
  test("childOf scopes search to parent subtree", () => {
    resetIds();
    const innerTarget = aaNode(
      { text: "OK" },
      { bounds: bounds(10, 110, 100, 150) },
    );
    const outsideOk = aaNode(
      { text: "OK" },
      { bounds: bounds(10, 310, 100, 350) },
    );
    const parent = aaNode(
      { resourceId: "com.example:id/dialog" },
      { children: [innerTarget] },
    );
    const root = aaNode({}, { children: [parent, outsideOk] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "OK" }),
      childOf: selectors.androidAccessibility({
        resourceIdRegex: escapeRegex("com.example:id/dialog"),
      }),
    });
    expect(asSingleMatch(result).nodeId).toBe(innerTarget.nodeId);
  });

  test("containsDescendants requires all to match", () => {
    resetIds();
    const child1 = aaNode({ text: "Title" });
    const child2 = aaNode({ text: "Subtitle" });
    const target = aaNode(
      { className: "android.widget.LinearLayout" },
      { children: [child1, child2] },
    );
    const root = aaNode({}, { children: [target] });

    const selectorAll = {
      ...selectors.androidAccessibility({
        classNameRegex: escapeRegex("android.widget.LinearLayout"),
      }),
      containsDescendants: [
        selectors.androidAccessibility({ textRegex: "Title" }),
        selectors.androidAccessibility({ textRegex: "Subtitle" }),
      ],
    };
    expect(resolve(root, selectorAll).kind).toBe("singleMatch");

    const selectorPartial = {
      ...selectors.androidAccessibility({
        classNameRegex: escapeRegex("android.widget.LinearLayout"),
      }),
      containsDescendants: [
        selectors.androidAccessibility({ textRegex: "Title" }),
        selectors.androidAccessibility({ textRegex: "MissingText" }),
      ],
    };
    expect(resolve(root, selectorPartial).kind).toBe("noMatch");
  });

  test("containsChild matches direct children only", () => {
    resetIds();
    const grandchild = aaNode({ text: "Deep" });
    const directChild = aaNode({ text: "Direct" });
    const targetContainer = aaNode(
      { className: "android.widget.FrameLayout" },
      { children: [directChild] },
    );
    const wrongContainer = aaNode(
      { className: "android.widget.FrameLayout" },
      { children: [aaNode({}, { children: [grandchild] })] },
    );
    const root = aaNode({}, { children: [targetContainer, wrongContainer] });

    const result = resolve(root, {
      ...selectors.androidAccessibility({
        classNameRegex: escapeRegex("android.widget.FrameLayout"),
      }),
      containsChild: selectors.androidAccessibility({ textRegex: "Direct" }),
    });
    expect(asSingleMatch(result).nodeId).toBe(targetContainer.nodeId);
  });

  test("childOf with non-matching parent returns NoMatch", () => {
    // When the childOf sub-selector finds no parent, the whole resolution
    // short-circuits to NoMatch — we don't fall through to a tree-wide search.
    resetIds();
    const candidate = aaNode({ text: "OK" });
    const root = aaNode({}, { children: [candidate] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "OK" }),
      childOf: selectors.androidAccessibility({
        resourceIdRegex: escapeRegex("com.example:id/nonexistent_dialog"),
      }),
    });
    expect(result.kind).toBe("noMatch");
  });

  test("containsDescendants with empty list satisfies vacuously (no constraint)", () => {
    // Kotlin's `.all { ... }` on an empty list returns true (vacuous truth).
    // TS `.every` matches. A selector with `containsDescendants: []` should
    // therefore match every node that satisfies its other predicates — the
    // empty list is effectively no hierarchy constraint at all.
    resetIds();
    const target = aaNode({ text: "Container" });
    const root = aaNode({}, { children: [target] });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Container" }),
      containsDescendants: [],
    });
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });
});

// ============================================================================
// Positioning / sort
// ============================================================================

describe("resolve — positioning and sort order", () => {
  test("index selects nth match", () => {
    resetIds();
    const root = aaNode({}, {
      children: [
        aaNode({ text: "Item" }, { bounds: bounds(0, 0, 100, 50) }),
        aaNode({ text: "Item" }, { bounds: bounds(0, 60, 100, 110) }),
        aaNode({ text: "Item" }, { bounds: bounds(0, 120, 100, 170) }),
      ],
    });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Item" }),
      index: 1,
    });
    const match = asSingleMatch(result);
    expect(match.bounds?.top).toBe(60);
  });

  test("index out of range returns NoMatch", () => {
    resetIds();
    const root = aaNode({}, {
      children: [aaNode({ text: "Item" }), aaNode({ text: "Item" })],
    });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Item" }),
      index: 5,
    });
    expect(result.kind).toBe("noMatch");
  });

  test("negative index returns NoMatch", () => {
    // Kotlin's `idx in matched.indices` is `[0, size)` — negative indices fall
    // outside. TS port uses `idx >= 0 && idx < matched.length` for parity.
    // Document the rejection so a future "wrap-around" refactor doesn't silently
    // change semantics.
    resetIds();
    const root = aaNode({}, {
      children: [aaNode({ text: "Item" }), aaNode({ text: "Item" })],
    });
    const result = resolve(root, {
      ...selectors.androidAccessibility({ textRegex: "Item" }),
      index: -1,
    });
    expect(result.kind).toBe("noMatch");
  });

  test("results sorted top-to-bottom then left-to-right", () => {
    resetIds();
    // Bottom-right element first in DOM order, top-left last — sort should reorder.
    const bottomRight = aaNode({ text: "Item" }, { bounds: bounds(100, 100, 200, 150) });
    const topLeft = aaNode({ text: "Item" }, { bounds: bounds(0, 0, 100, 50) });
    const topRight = aaNode({ text: "Item" }, { bounds: bounds(110, 0, 200, 50) });
    const root = aaNode({}, { children: [bottomRight, topLeft, topRight] });
    const result = resolve(root, selectors.androidAccessibility({ textRegex: "Item" }));
    const matches = asMultipleMatches(result);
    expect(matches[0]!.nodeId).toBe(topLeft.nodeId);
    expect(matches[1]!.nodeId).toBe(topRight.nodeId);
    expect(matches[2]!.nodeId).toBe(bottomRight.nodeId);
  });
});

// ============================================================================
// resolveToCenter
// ============================================================================

describe("resolveToCenter", () => {
  test("returns center point coordinates", () => {
    resetIds();
    const target = aaNode({ text: "Submit" }, { bounds: bounds(10, 20, 90, 80) });
    const root = aaNode({}, { children: [target] });
    const center = resolveToCenter(
      root,
      selectors.androidAccessibility({ textRegex: "Submit" }),
    );
    // (10 + 90) / 2 = 50, (20 + 80) / 2 = 50
    expect(center).toEqual({ x: 50, y: 50 });
  });

  test("returns null for no match", () => {
    resetIds();
    const root = aaNode({}, { children: [aaNode({ text: "X" })] });
    expect(
      resolveToCenter(root, selectors.androidAccessibility({ textRegex: "missing" })),
    ).toBeNull();
  });
});

// ============================================================================
// Compose driver
// ============================================================================

describe("resolve — Compose driver", () => {
  test("match by testTag", () => {
    resetIds();
    const target = nodeOf({ class: "compose", testTag: "submit_btn" });
    const other = nodeOf({ class: "compose", testTag: "cancel_btn" });
    const root = nodeOf({ class: "compose" }, { children: [target, other] });
    const result = resolve(root, selectors.compose({ testTag: "submit_btn" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by role and text", () => {
    resetIds();
    const target = nodeOf({ class: "compose", role: "Button", text: "Submit" });
    const other = nodeOf({ class: "compose", role: "Checkbox", text: "Submit" });
    const root = nodeOf({ class: "compose" }, { children: [target, other] });
    const result = resolve(root, selectors.compose({ role: "Button", textRegex: "Submit" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by editableText (resolveText priority)", () => {
    // Compose `resolveText()` is editableText > text > contentDescription.
    resetIds();
    const target = nodeOf({
      class: "compose",
      editableText: "user input",
      text: "stale label",
    });
    const root = nodeOf({ class: "compose" }, { children: [target] });
    const result = resolve(root, selectors.compose({ textRegex: "user input" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("boolean state matching", () => {
    resetIds();
    const target = nodeOf({ class: "compose", testTag: "check", isSelected: true });
    const other = nodeOf({ class: "compose", testTag: "check", isSelected: false });
    const root = nodeOf({ class: "compose" }, { children: [target, other] });
    const result = resolve(
      root,
      selectors.compose({ testTag: "check", isSelected: true }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by toggleableState", () => {
    // Mirrors the Kotlin `Compose - match by toggleableState` case. The matchable
    // toggleable values are "On" / "Off" / "Indeterminate" — selector picks "On",
    // confirms the "Off" node doesn't match.
    resetIds();
    const onNode = nodeOf({
      class: "compose",
      role: "Switch",
      toggleableState: "On",
    });
    const offNode = nodeOf({
      class: "compose",
      role: "Switch",
      toggleableState: "Off",
    });
    const root = nodeOf({ class: "compose" }, { children: [onNode, offNode] });
    const result = resolve(root, selectors.compose({ toggleableState: "On" }));
    expect(asSingleMatch(result).nodeId).toBe(onNode.nodeId);
  });
});

// ============================================================================
// AndroidMaestro driver
// ============================================================================

describe("resolve — AndroidMaestro driver", () => {
  test("match by resourceId", () => {
    resetIds();
    const target: DriverNodeDetailAndroidMaestro = {
      class: "androidMaestro",
      resourceId: "com.example:id/button_continue",
    };
    const other: DriverNodeDetailAndroidMaestro = {
      class: "androidMaestro",
      resourceId: "com.example:id/button_cancel",
    };
    const root = nodeOf({ class: "androidMaestro" }, {
      children: [nodeOf(target), nodeOf(other)],
    });
    const result = resolve(
      root,
      selectors.androidMaestro({
        resourceIdRegex: escapeRegex("com.example:id/button_continue"),
      }),
    );
    expect(asSingleMatch(result).driverDetail).toEqual(target);
  });

  test("match by text resolveText priority — falls through hintText", () => {
    // resolveText priority on Maestro: text > hintText > accessibilityText.
    resetIds();
    const target = nodeOf({
      class: "androidMaestro",
      hintText: "Search products",
    });
    const root = nodeOf({ class: "androidMaestro" }, { children: [target] });
    const result = resolve(
      root,
      selectors.androidMaestro({ textRegex: "Search products" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by boolean state", () => {
    resetIds();
    const target = nodeOf({
      class: "androidMaestro",
      text: "Switch",
      checked: true,
    });
    const other = nodeOf({
      class: "androidMaestro",
      text: "Switch",
      checked: false,
    });
    const root = nodeOf({ class: "androidMaestro" }, { children: [target, other] });
    const result = resolve(
      root,
      selectors.androidMaestro({ textRegex: "Switch", checked: true }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });
});

// ============================================================================
// Web driver
// ============================================================================

describe("resolve — Web driver", () => {
  test("match by ariaRole and ariaName", () => {
    resetIds();
    const target = nodeOf({ class: "web", ariaRole: "button", ariaName: "Submit" });
    const other = nodeOf({ class: "web", ariaRole: "link", ariaName: "Submit" });
    const root = nodeOf({ class: "web" }, { children: [target, other] });
    const result = resolve(
      root,
      selectors.web({ ariaRole: "button", ariaNameRegex: "Submit" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by dataTestId", () => {
    resetIds();
    const target = nodeOf({ class: "web", dataTestId: "submit-btn" });
    const root = nodeOf({ class: "web" }, { children: [target] });
    const result = resolve(root, selectors.web({ dataTestId: "submit-btn" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by cssSelector", () => {
    resetIds();
    const target = nodeOf({ class: "web", cssSelector: "#login-form" });
    const root = nodeOf({ class: "web" }, { children: [target] });
    const result = resolve(root, selectors.web({ cssSelector: "#login-form" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by nthIndex", () => {
    resetIds();
    const target = nodeOf({ class: "web", ariaRole: "link", ariaName: "Home", nthIndex: 1 });
    const other = nodeOf({ class: "web", ariaRole: "link", ariaName: "Home", nthIndex: 0 });
    const root = nodeOf({ class: "web" }, { children: [other, target] });
    const result = resolve(
      root,
      selectors.web({ ariaRole: "link", ariaNameRegex: "Home", nthIndex: 1 }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });
});

// ============================================================================
// IosMaestro driver
// ============================================================================

describe("resolve — IosMaestro driver", () => {
  test("match by resourceId", () => {
    resetIds();
    const target = nodeOf({
      class: "iosMaestro",
      resourceId: "submitButton",
    });
    const root = nodeOf({ class: "iosMaestro" }, { children: [target] });
    const result = resolve(root, selectors.iosMaestro({ resourceIdRegex: "submitButton" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });

  test("match by text resolveText priority", () => {
    // iOS Maestro priority: text > hintText > accessibilityText.
    resetIds();
    const target = nodeOf({
      class: "iosMaestro",
      accessibilityText: "Done button",
    });
    const root = nodeOf({ class: "iosMaestro" }, { children: [target] });
    const result = resolve(
      root,
      selectors.iosMaestro({ textRegex: "Done button" }),
    );
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });
});

// ============================================================================
// IosAxe driver
// ============================================================================

describe("resolve — IosAxe driver", () => {
  test("match by label and role", () => {
    resetIds();
    const target: DriverNodeDetailIosAxe = {
      class: "iosAxe",
      role: "AXButton",
      label: "Submit",
    };
    const root = nodeOf({ class: "iosAxe" }, { children: [nodeOf(target)] });
    const result = resolve(
      root,
      selectors.iosAxe({ roleRegex: "AXButton", labelRegex: "Submit" }),
    );
    expect(asSingleMatch(result).driverDetail).toEqual(target);
  });

  test("match by customAction requires that string to be in customActions", () => {
    resetIds();
    const target = nodeOf({
      class: "iosAxe",
      role: "AXCell",
      customActions: ["Edit mode", "Delete"],
    });
    const noActions = nodeOf({
      class: "iosAxe",
      role: "AXCell",
      customActions: [],
    });
    const root = nodeOf({ class: "iosAxe" }, { children: [target, noActions] });
    const result = resolve(root, selectors.iosAxe({ customAction: "Edit mode" }));
    expect(asSingleMatch(result).nodeId).toBe(target.nodeId);
  });
});

// ============================================================================
// Cross-driver mismatch
// ============================================================================

describe("resolve — cross-driver mismatch", () => {
  test("Web selector on Android tree returns NoMatch", () => {
    resetIds();
    const root = aaNode({}, { children: [aaNode({ text: "Submit" })] });
    const result = resolve(
      root,
      selectors.web({ ariaRole: "button", ariaNameRegex: "Submit" }),
    );
    expect(result.kind).toBe("noMatch");
  });

  test("IosMaestro selector on Android tree returns NoMatch", () => {
    resetIds();
    const root = aaNode({}, { children: [aaNode({ text: "Submit" })] });
    const result = resolve(root, selectors.iosMaestro({ textRegex: "Submit" }));
    expect(result.kind).toBe("noMatch");
  });

  test("AndroidAccessibility selector on Compose tree returns NoMatch", () => {
    // Mirrors the Kotlin `cross-driver mismatch returns NoMatch` case, which
    // specifically exercises Compose-tree-vs-AndroidAccessibility-selector. The
    // matchesDriverDetail dispatcher's `detail.kind === "..." && ...` guard
    // rejects mismatched (detail, match) pairs at the type-discriminator step;
    // covering more than just web/iOS pairs.
    resetIds();
    const root = nodeOf({ class: "compose" }, {
      children: [nodeOf({ class: "compose", text: "Submit" })],
    });
    const result = resolve(
      root,
      selectors.androidAccessibility({ textRegex: "Submit" }),
    );
    expect(result.kind).toBe("noMatch");
  });
});

// ============================================================================
// Smoke tests for tree helpers (delegated to trailblaze-node.ts but easier to verify here)
// ============================================================================

describe("tree helpers — smoke", () => {
  test("aggregate flattens in pre-order DFS", () => {
    resetIds();
    const leafA = aaNode({ text: "A" });
    const leafB = aaNode({ text: "B" });
    const branch = aaNode({}, { children: [leafA, leafB] });
    const root = aaNode({}, { children: [branch] });
    // Selector that would match all 4 nodes — used to indirectly assert aggregate
    // walks every node.
    const result = resolve(root, selectors.androidAccessibility({ isEnabled: true }));
    expect(asMultipleMatches(result)).toHaveLength(4);
  });

  test("walkers tolerate omitted `children` field (wire-format compat)", () => {
    // Kotlin's `children: List<TrailblazeNode> = emptyList()` default is NOT
    // emitted by `TrailblazeJson` (no `encodeDefaults`), so leaf nodes on the
    // wire arrive without a `children` field at all. All walkers must treat
    // absent `children` as an empty list. Build a leaf with the field literally
    // missing and verify the matcher doesn't throw on `for (const child of
    // node.children)`.
    resetIds();
    const leaf: TrailblazeNode = {
      nodeId: 1,
      bounds: DEFAULT_BOUNDS,
      driverDetail: { class: "androidAccessibility", text: "Leaf" },
      // children field deliberately omitted — mirrors the wire shape.
    };
    const root: TrailblazeNode = {
      nodeId: 2,
      bounds: DEFAULT_BOUNDS,
      driverDetail: { class: "androidAccessibility" },
      children: [leaf],
    };
    const result = resolve(root, selectors.androidAccessibility({ textRegex: "Leaf" }));
    expect(asSingleMatch(result).nodeId).toBe(leaf.nodeId);
  });
});
