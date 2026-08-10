// Canonical reference for unit-testing an iOS-trailmap scripted tool. Imports the tool
// function directly (no daemon, no simulator) and drives it with the mock client + mock
// context from `@trailblaze/scripting/testing`. Run via:
//
//   ./trailblaze check contacts
//
// — its third phase discovers `*.test.ts` files in this directory and shells out to
// `bun test`. `bun test` also works as a drop-in if invoked from this directory.

import { describe, expect, test } from "bun:test";
import { createMockClient, createMockContext } from "@trailblaze/scripting/testing";
import type { MatchDescriptor, TrailblazeNodeSelector } from "@trailblaze/scripting";

import {
  contacts_ios_searchContacts,
  type SearchContactsArgs,
} from "./contacts_ios_searchContacts";

/** One canned `findMatches` hit — the shape the daemon returns for a visible row. */
const ROW_MATCH: MatchDescriptor = {
  indexPath: [0, 1],
  bounds: { left: 0, top: 200, right: 390, bottom: 260 },
  matchedText: "John Appleseed",
};

/** Stubs `findMatches` to report the result row as visible. */
function stubRowVisible(client: ReturnType<typeof createMockClient>): void {
  client.stub("findMatches", { textContent: "", structuredContent: [ROW_MATCH] });
}

/**
 * Compiles a selector's emitted `*Regex` the way the device resolves it: node selectors
 * full-match their pattern, so anchor it before testing candidate labels.
 */
function anchored(pattern: string | null | undefined): RegExp {
  expect(typeof pattern).toBe("string");
  return new RegExp(`^(?:${pattern})$`);
}

/** Runs the tool against a stubbed visible row and returns the selector the tap dispatched. */
async function rowSelectorFor(args: SearchContactsArgs): Promise<TrailblazeNodeSelector> {
  const client = createMockClient();
  stubRowVisible(client);
  await contacts_ios_searchContacts(args, createMockContext({ platform: "ios" }), client);
  const tapCall = client.calls.find((c) => c.tool === "tapOnElementBySelector");
  expect(tapCall).toBeDefined();
  return tapCall!.args.nodeSelector as TrailblazeNodeSelector;
}

describe("contacts_ios_searchContacts", () => {
  test("throws a descriptive no-results error when the 'No Results' banner is present", async () => {
    // The no-results pre-flight probes with `assertNotVisibleWithText({ text: "No Results" })`
    // — a NEGATIVE assertion whose `text` is treated as a regex, so it matches iOS's real
    // `No Results for "<query>"` banner (an exact `assertVisibleWithAccessibilityText`
    // needle never would — that gap let the not-found path tap the query text in the
    // search field and report a false "opened"). The daemon fails that assertion when a
    // matching element IS on screen, so stub it to throw: `tryOrFalse` turns that into
    // `hasResults === false` and the tool surfaces the descriptive error.
    const client = createMockClient();
    client.stub("assertNotVisibleWithText", {
      textContent: "",
      errorMessage: "element is visible",
    });
    const ctx = createMockContext({ platform: "ios" });

    await expect(
      contacts_ios_searchContacts({ query: "Nobody" }, ctx, client),
    ).rejects.toThrow(/contacts_ios_searchContacts: query "Nobody" returned no results/);

    // Order matters — a regression that reorders the gesture sequence (or drops the
    // ensureContactsRoot prelude) breaks this test even though tsc would still be green.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "launchApp", // ensureContactsRoot — force-restart Contacts
      "assertVisibleWithAccessibilityText", // ensureContactsRoot — "Contacts" list-root anchor
      "swipe", // pull the search field into view
      "tapOnElementWithText", // focus the "Search" input
      "inputText", // type the query
      "assertNotVisibleWithText", // no-results pre-flight probe — stubbed to fail (banner present), fires the branch
    ]);
    // The launchApp force-restart is a load-bearing implementation detail — the tool's
    // contract is "swipe-down from a known list-root state", not "swipe-down from
    // wherever the device happens to be". Assert the launchMode so a refactor that
    // downgrades the restart to a plain launch fails here.
    expect(client.calls[0]?.args).toMatchObject({
      appId: "com.apple.MobileAddressBook",
      launchMode: "FORCE_RESTART",
    });
    expect(client.calls[1]?.args).toMatchObject({ accessibilityText: "Contacts" });
    expect(client.calls[2]?.args).toMatchObject({ direction: "DOWN" });
    expect(client.calls[3]?.args).toMatchObject({ text: "Search" });
    expect(client.calls[4]?.args).toMatchObject({ text: "Nobody" });
    // The pre-flight probe ran with the right needle — a regex that matches the real
    // `No Results for "<query>"` banner, not just the exact string "No Results".
    expect(client.calls[5]?.args).toMatchObject({ text: "No Results" });
    // The row-tap was NOT called — the no-results branch short-circuited before it.
    expect(client.calls).toHaveLength(6);
  });

  test("waits for and taps the matching row via a label-scoped selector", async () => {
    // The default mock returns success for the negative no-results probe (no banner →
    // results ARE present); `findMatches` is stubbed to report the row as visible. The
    // tool then taps the row matching `rowText` and returns.
    const client = createMockClient();
    stubRowVisible(client);
    const ctx = createMockContext({ platform: "ios" });

    const result = await contacts_ios_searchContacts(
      { query: "John", rowText: "John Appleseed" },
      ctx,
      client,
    );

    expect(client.calls.map((c) => c.tool)).toEqual([
      "launchApp", // ensureContactsRoot — force-restart Contacts
      "assertVisibleWithAccessibilityText", // ensureContactsRoot — "Contacts" list-root anchor
      "swipe", // pull the search field into view
      "tapOnElementWithText", // focus the "Search" input
      "inputText", // type the query
      "assertNotVisibleWithText", // no-results probe — passes (no banner) → results present
      "findMatches", // wait for the result row to render
      "tapOnElementBySelector", // tap the result row
    ]);
    // The row tap targets `rowText`, not the raw query — the partial-prefix flow — and the
    // wait probe uses the same selector the tap dispatches, so they can't drift apart.
    const tapSelector = client.calls[7]?.args.nodeSelector as TrailblazeNodeSelector;
    expect(client.calls[6]?.args.selector).toEqual(tapSelector);
    expect(tapSelector.iosMaestro?.accessibilityTextRegex).toContain("John Appleseed");
    expect(result).toContain('opened the row matching "John Appleseed"');
  });

  test("query == rowText: the node-selector row tap cannot resolve the search field", async () => {
    // THE regression this tool shipped with (ios-contacts-replay-smoke red 23/25 on main):
    // `contacts_ios_openContact` passes the same full name as `query` and `rowText`, and on
    // the host driver a bare text tap resolved the search field's own typed text (a text
    // field's Maestro `text` attribute is its VALUE), so no navigation happened. The
    // contract pinned here: the row tap is `tapOnElementBySelector` matching on the AX
    // *label* only — a text field's label is its placeholder, never the typed value — with
    // no text/value-shaped predicate the typed query could satisfy, and no bare text tap of
    // `rowText` is dispatched at all. The framework's Maestro fallback lowers
    // `accessibilityTextRegex` to legacy `textRegex` (text | hintText | accessibilityText),
    // which the search field's typed value CAN satisfy — but the same lowering carries the
    // results-list `childOf` scope through, so the field stays out of the candidate set on
    // that path too (see the results-list scope test below).
    const client = createMockClient();
    stubRowVisible(client);
    const ctx = createMockContext({ platform: "ios" });

    await contacts_ios_searchContacts(
      { query: "John Appleseed", rowText: "John Appleseed" },
      ctx,
      client,
    );

    // No bare text tap ever targets the row text (the only tapOnElementWithText allowed is
    // the "Search" field focus).
    const bareTextTaps = client.calls
      .filter((c) => c.tool === "tapOnElementWithText")
      .map((c) => c.args.text);
    expect(bareTextTaps).toEqual(["Search"]);

    const tapCall = client.calls.find((c) => c.tool === "tapOnElementBySelector");
    expect(tapCall).toBeDefined();
    const selector = tapCall!.args.nodeSelector as TrailblazeNodeSelector;
    // Label-scoped only: no predicate that a text field's typed value can satisfy.
    expect(selector.iosMaestro?.accessibilityTextRegex).toBeDefined();
    expect(selector.iosMaestro?.textRegex).toBeUndefined();
    expect(selector.iosMaestro?.hintTextRegex).toBeUndefined();
    // Deterministic single-node resolution when the cell and its inner label both match.
    expect(selector.index).toBe(0);
  });

  test("row selector matches the row label literally, anywhere in the label (contains)", async () => {
    // The emitted accessibilityTextRegex is a wire contract the device resolves. Verify its
    // BEHAVIOR (not its exact string). Node selectors full-match their regex, so the emitted
    // pattern must preserve the CONTAINS semantics the old `tapOnElementWithText` had:
    // `rowText` may appear anywhere in the row label — a last-name-only query (rowText
    // defaults to the query) must match the full-name row, and labels may append detail
    // text after the name. A name containing regex metacharacters is matched literally,
    // not as a pattern.
    const client = createMockClient();
    stubRowVisible(client);
    const ctx = createMockContext({ platform: "ios" });

    await contacts_ios_searchContacts(
      { query: "Dr", rowText: "Dr. O'Brien (Work)" },
      ctx,
      client,
    );

    const tapCall = client.calls.find((c) => c.tool === "tapOnElementBySelector");
    const selector = tapCall!.args.nodeSelector as TrailblazeNodeSelector;
    const pattern = anchored(selector.iosMaestro?.accessibilityTextRegex);
    expect(pattern.test("Dr. O'Brien (Work)")).toBe(true);
    expect(pattern.test("Dr. O'Brien (Work), mobile")).toBe(true);
    // Contains, not prefix: the label may carry text BEFORE rowText too.
    expect(pattern.test("Prof. Dr. O'Brien (Work)")).toBe(true);
    // "." must not act as a wildcard — a literal-escape regression would match this.
    expect(pattern.test("DrX O'Brien (Work)")).toBe(false);
  });

  test("a last-name-only rowText still matches the full-name row", async () => {
    // The canonical contains case, and the one a prefix-shaped pattern would break: searching
    // by surname (rowText defaults to the query) must still match the row whose label leads
    // with the first name.
    const selector = await rowSelectorFor({ query: "Appleseed" });
    const pattern = anchored(selector.iosMaestro?.accessibilityTextRegex);
    expect(pattern.test("John Appleseed")).toBe(true);
    expect(pattern.test("Appleseed")).toBe(true);
    expect(pattern.test("Kate Bell")).toBe(false);
  });

  test("a rowText that also matches the search field's label is scoped to the results list", async () => {
    // Contains matching on the label is necessary (previous test) but not sufficient. Any
    // `rowText` that is a substring of the search chrome's labels — "ear" for a "Teddy Bear"
    // row is a substring of the field's "Search" placeholder, of the "Search results" panel
    // label, and of the "Clear text" button — makes the label predicate alone ambiguous. Both
    // `findMatches` and the tap use this selector with `index: 0`, so if the search field were
    // still a candidate the topmost match could be the field: the wait would succeed, the tap
    // would only focus it, and the tool would report success without opening the row.
    const selector = await rowSelectorFor({ query: "ear" });

    // The ambiguity is real — this is why the extra constraint exists, not a regression.
    const rowPattern = anchored(selector.iosMaestro?.accessibilityTextRegex);
    expect(rowPattern.test("Teddy Bear")).toBe(true);
    expect(rowPattern.test("Search")).toBe(true);
    expect(rowPattern.test("Clear text")).toBe(true);

    // What removes the search field is the structural scope, not a narrower text match: the
    // match must be a DESCENDANT of the search-results panel. On the real Contacts hierarchy
    // the rows live under that panel while the search field and its chrome live under a
    // sibling "Toolbar" branch, so the whole field/chrome family is out of the candidate set
    // before `index: 0` is applied. The scope anchor is the panel and only the panel — it can
    // never resolve to the search field itself, and `childOf` excludes the anchor, so the
    // full-screen panel node can't be tapped either.
    const scopePattern = anchored(selector.childOf?.iosMaestro?.accessibilityTextRegex);
    expect(scopePattern.test("Search results")).toBe(true);
    expect(scopePattern.test("Search")).toBe(false);
    expect(scopePattern.test("Search: ear")).toBe(false);
    expect(scopePattern.test("Clear text")).toBe(false);
    expect(scopePattern.test("Toolbar")).toBe(false);

    // Index stays the last-resort row disambiguator (cell vs. its inner StaticText).
    expect(selector.index).toBe(0);
  });

  test("throws a descriptive error when results exist but the rowText row never appears", async () => {
    // No banner (all the tool actually established) but `findMatches` reports no row labeled
    // `rowText` within the wait budget — the "wrong rowText" failure, distinct from "wrong
    // query". No tap may be dispatched against a row that never rendered.
    const client = createMockClient();
    client.stub("findMatches", { textContent: "", structuredContent: [] });
    const ctx = createMockContext({ platform: "ios" });

    await expect(
      contacts_ios_searchContacts({ query: "John", rowText: "Johnny Nonexistent" }, ctx, client),
    ).rejects.toThrow(/shows no "No Results" banner, but no row labeled "Johnny Nonexistent"/);

    expect(client.calls.map((c) => c.tool)).not.toContain("tapOnElementBySelector");
  });

  test("returns early without probing No Results when openFirstResult is false", async () => {
    // `openFirstResult: false` is the "type the query and stop" branch — used by callers
    // that want to verify the inline autocomplete suggestions instead of opening a row.
    // The tool returns after the inputText call without ever probing for the No Results
    // banner or tapping a row.
    const client = createMockClient();
    const ctx = createMockContext({ platform: "ios" });

    const result = await contacts_ios_searchContacts(
      { query: "alb", openFirstResult: false },
      ctx,
      client,
    );

    expect(client.calls.map((c) => c.tool)).toEqual([
      "launchApp",
      "assertVisibleWithAccessibilityText",
      "swipe",
      "tapOnElementWithText",
      "inputText",
    ]);
    expect(result).toContain("stopped (no result tapped)");
  });

  test("applies module defaults when args fields are omitted", async () => {
    const client = createMockClient();
    stubRowVisible(client);
    const ctx = createMockContext({ platform: "ios" });

    // No `query` → tool falls back to its `DEFAULT_QUERY` module constant. Under the
    // default mock the no-results probe passes (results present), so the tool completes;
    // the exact default string is a tool-side implementation detail — assert via shape
    // (non-empty string forwarded to inputText) rather than equality so a doc-only tweak
    // to the default doesn't break the test.
    await contacts_ios_searchContacts({}, ctx, client);

    const inputCall = client.calls.find((c) => c.tool === "inputText");
    expect(inputCall).toBeDefined();
    expect(typeof inputCall!.args.text).toBe("string");
    expect((inputCall!.args.text as string).length).toBeGreaterThan(0);
  });

  test("propagates a stubbed inputText failure with the production error wrapping", async () => {
    // Demonstrates `client.stub(name, response)` from `@trailblaze/scripting/testing` —
    // when `errorMessage` is non-empty, every call to the named tool throws with the same
    // wording the real daemon emits (see `unwrapCallbackResponse` in
    // `sdks/typescript/src/client.ts`). Stubs persist for every call to the named tool
    // until `client.reset()`.
    const client = createMockClient();
    client.stub("inputText", {
      textContent: "",
      errorMessage: "field not found",
    });
    const ctx = createMockContext({ platform: "ios" });

    // Match the production wrapper format (`tool failed: <errorMessage>`) so a future
    // tweak to the wrapping string in `client.ts` fails this assertion explicitly
    // instead of silently passing on the substring.
    await expect(
      contacts_ios_searchContacts({}, ctx, client),
    ).rejects.toThrow(/tool failed: field not found/);

    // The pre-input gesture chain still ran — the stub fires only at the inputText
    // dispatch. Useful for "regression: did the tool short-circuit before reaching the
    // search field?" assertions.
    expect(client.calls.map((c) => c.tool)).toEqual([
      "launchApp",
      "assertVisibleWithAccessibilityText",
      "swipe",
      "tapOnElementWithText",
      "inputText", // thrown by the stub
    ]);
  });
});
